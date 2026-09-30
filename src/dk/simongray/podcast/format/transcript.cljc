(ns dk.simongray.podcast.format.transcript
  "Transcripts in the four formats the podcast namespace allows, read into one
  shape and written back: SRT, WebVTT, the JSON segments format and the
  constrained HTML form, plus plain text.

  A transcript is a map:

      {:format   :vtt                     ; :srt :json :html :text
       :segments [{:start 0.0 :end 2.76 :speaker \"Sarah\" :body \"…\"}]}

  Times are seconds; `:end` and `:speaker` are present when the source had
  them. Speakers are read from `<v>` voice spans, `<cite>` elements and JSON
  fields, and from the \"Name:\" or \"[Name]:\" prefixes caption files use,
  which mark a change of speaker and so carry over the cues that follow.

  Reading is lenient and never throws on content. What it had to forgive is
  in the report, metadata under `:podcast/report` read with `report`: times
  written as strings, speakers taken from prefixes, an SRT wrapped in HTML,
  a declared type that does not match the file."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.xml :as xml]))

;; podcast:transcript names five types. application/srt is of Apple's
;; guide, and text/srt is in feeds.
(def formats
  "Transcript format by the mime type `podcast:transcript` gives it."
  {"text/vtt"             :vtt
   "application/x-subrip" :srt
   "application/srt"      :srt
   "text/srt"             :srt
   "application/json"     :json
   "text/html"            :html
   "text/plain"           :text})

(def ^:no-doc timing-pattern
  "A cue timing line in SRT or WebVTT: start, arrow, end, optional settings;
  the fraction of a second may be missing, as some exports leave it out."
  #"^\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}(?:[.,]\d{1,3})?)\s*-->\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}(?:[.,]\d{1,3})?)")

(defn report
  "The diagnostics recorded while reading `transcript`."
  [transcript]
  (:podcast/report (meta transcript)))

(defn- diagnostic
  [id level message & {:as extra}]
  (merge {:id id :level level :message message} extra))

(defn- with-report
  "`transcript` with `diagnostics` as its report."
  [transcript diagnostics]
  (with-meta transcript {:podcast/report (vec diagnostics)}))

(defn- add-diagnostic
  [transcript diagnostic]
  (vary-meta transcript update :podcast/report (fnil conj []) diagnostic))

(defn- prefix-diagnostics
  "The diagnostic for `n` cues that named their speaker with a prefix."
  [n]
  (when (pos? n)
    [(diagnostic :transcript/speaker-prefixes :info
              (str n " cues name their speaker with a \"Name:\" prefix; "
                   "the name is carried over the cues that follow")
              :count n)]))

(defn- paragraphs
  "The blank-line separated stretches of `s`."
  [s]
  (str/split (str/replace s "\r\n" "\n") #"\n[ \t]*\n"))

(defn- blocks
  "The blank-line separated blocks of `s`, each a vector of lines."
  [s]
  (->> (str/split-lines (str/replace s "\r\n" "\n"))
       (partition-by str/blank?)
       (remove #(str/blank? (first %)))
       (map vec)))

(defn- speaker-prefix
  "Split a leading \"Name: \" or \"[Name]: \" off `body`; returns `[speaker
  body]`, the speaker nil when there is none. Counts a hit in `stats`."
  [stats body]
  (if-let [[_ speaker rest] (re-find #"(?s)^\[?([A-Za-z][^:\n<>\[\]]{0,40}?)\]?:\s+(.*)$" body)]
    (do (vswap! stats update :prefixes (fnil inc 0))
        [speaker rest])
    [nil body]))

(defn- carry-speakers
  "Give the segments that name no speaker the last one named before them,
  the way caption files mark only the changes of speaker."
  [segments]
  (second (reduce (fn [[speaker acc] segment]
                    (let [speaker (or (:speaker segment) speaker)]
                      [speaker (conj acc (if (and speaker (not (:speaker segment)))
                                           (assoc segment :speaker speaker)
                                           segment))]))
                  [nil []]
                  segments)))

(defn- cue
  "The segment for a cue whose timing line is `timing` and text lines
  `lines`, or nil when the timing does not parse."
  [stats timing lines]
  (when-let [[_ from to] (re-find timing-pattern timing)]
    (let [[speaker body] (speaker-prefix stats (str/join "\n" lines))]
      (cond-> {:start (time/parse-clock from) :end (time/parse-clock to) :body body}
        speaker (assoc :speaker speaker)))))

(defn parse-srt
  "Read the SRT transcript `s`."
  [s]
  (let [stats    (volatile! {})
        segments (->> (blocks s)
                      (keep (fn [[first-line & more]]
                              (if (re-find timing-pattern first-line)
                                (cue stats first-line more)
                                (cue stats (or (first more) "") (rest more)))))
                      carry-speakers)]
    (with-report {:format :srt :segments segments}
      (prefix-diagnostics (:prefixes @stats 0)))))

(defn- timed-segments
  "The segments of `transcript` that have a start time; what the timed
  formats write."
  [transcript]
  (filter (comp number? :start) (:segments transcript)))

(defn ^:no-doc cue-body
  "The `body` of a segment as a cue carries it: trimmed, and without blank
  lines, which end a cue in SRT and WebVTT, so that the next read would
  lose the rest of the body."
  [body]
  (str/replace (str/trim (str body)) #"\n[ \t\r]*\n\s*" "\n"))

(defn emit-srt
  "Write `transcript` as SRT, one numbered cue per segment with a start time,
  the speaker as a \"Name: \" prefix; segments without one are left out."
  [transcript]
  (str/join "\n\n"
            (map-indexed (fn [i {:keys [start end speaker body]}]
                           (str (inc i) "\n"
                                (time/format-timestamp start ",") " --> "
                                (time/format-timestamp (or end start) ",") "\n"
                                (when speaker (str speaker ": ")) (cue-body body)))
                         (timed-segments transcript))))

(defn- strip-tags
  [s]
  ;; [^<>] rather than [^>], so that a < with no > after it is given up at
  ;; the next <, not at the end of the text, once for every < there is
  (str/replace s #"<[^<>]*>" ""))

(defn- vtt-cue
  "The segment for a WebVTT cue block, reading `<v Speaker>` voice spans and,
  failing those, a \"Name:\" prefix."
  [stats lines]
  (let [[timing & payload] (if (re-find timing-pattern (first lines)) lines (rest lines))]
    (when-let [[_ from to] (some->> timing (re-find timing-pattern))]
      (let [text            (str/join "\n" payload)
            voice           (second (re-find #"<v(?:\.[^\s<>]*)?\s+([^<>]+)>" text))
            plain           (xml/decode-entities (str/trim (strip-tags text)))
            [prefixed body] (if voice [nil plain] (speaker-prefix stats plain))
            speaker         (or voice prefixed)]
        (cond-> {:start (time/parse-clock from) :end (time/parse-clock to) :body body}
          speaker (assoc :speaker speaker))))))

(defn parse-vtt
  "Read the WebVTT transcript `s`, skipping NOTE, STYLE and REGION blocks."
  [s]
  (let [stats    (volatile! {})
        segments (->> (blocks s)
                      (keep (fn [block]
                              (when-not (re-find #"^(?:WEBVTT|NOTE|STYLE|REGION)" (first block))
                                (vtt-cue stats block))))
                      carry-speakers)]
    (with-report {:format :vtt :segments segments}
      (prefix-diagnostics (:prefixes @stats 0)))))

(defn emit-vtt
  "Write `transcript` as WebVTT, speakers as `<v>` voice spans; segments
  without a start time are left out."
  [transcript]
  (str "WEBVTT\n\n"
       (str/join "\n\n"
                 (for [{:keys [start end speaker body]}
                       (timed-segments transcript)]
                   (str (time/format-timestamp start) " --> "
                        (time/format-timestamp (or end start)) "\n"
                        (when speaker (str "<v " speaker ">")) (cue-body body))))
       "\n"))

(defn parse-json
  "Read the JSON transcript `s`: `{\"version\" \"segments\" [{\"speaker\"
  \"startTime\" \"endTime\" \"body\"}]}`. Times written as strings are read
  as numbers and reported, and a speaker written as a number as its string.
  A file that is not JSON, or not a JSON object, reads as no segments,
  segments that are not objects are left out, and so are speakers that are
  not text; the report says so."
  [s]
  (let [doc      (json/parse s)
        raw      (when (map? doc) (get doc "segments"))
        objects  (if (sequential? raw) (filterv map? raw) [])
        dropped  (cond (sequential? raw) (- (count raw) (count objects))
                       (some? raw)       1
                       :else             0)
        strings  (count (filter #(or (string? (get % "startTime")) (string? (get % "endTime"))) objects))
        not-text (count (filter #(let [v (get % "speaker")] (and (some? v) (nil? (json/text v)))) objects))
        segments (vec (for [segment objects
                            :let [start   (time/seconds (get segment "startTime"))
                                  end     (time/seconds (get segment "endTime"))
                                  speaker (json/text (get segment "speaker"))]]
                        (cond-> {:body (str (get segment "body"))}
                          (some? start) (assoc :start start)
                          (some? end)   (assoc :end end)
                          (seq speaker) (assoc :speaker speaker))))]
    (with-report {:format :json :version (when (map? doc) (get doc "version")) :segments segments}
      (cond-> []
        (not (map? doc))
        (conj (diagnostic :transcript/not-an-object :warning "the file is not a JSON object"))

        (pos? dropped)
        (conj (diagnostic :transcript/malformed :warning
                          (str dropped " segments are not JSON objects in a list and were left out")
                          :count dropped))

        (pos? strings)
        (conj (diagnostic :transcript/string-times :warning
                          (str strings " segments give their times as strings rather than numbers")
                          :count strings))

        (pos? not-text)
        (conj (diagnostic :transcript/not-text :warning
                          (str not-text " speakers are not text and were left out")
                          :count not-text))))))

(defn emit-json
  "Write `transcript` in the JSON transcript format, indented unless `opts`
  says `{:pretty? false}`; segments without a start time are left out."
  ([transcript]
   (emit-json transcript {}))
  ([transcript {:keys [pretty?] :or {pretty? true}}]
   (json/write-str
    {"version"  (or (:version transcript) "1.0.0")
     "segments" (for [{:keys [start end speaker body]}
                      (timed-segments transcript)]
                  (cond-> {"startTime" start "body" body}
                    end     (assoc "endTime" end)
                    speaker (assoc "speaker" speaker)))}
    {:pretty? pretty?})))

(defn- tidy
  "Collapse the layout whitespace of the HTML text `s`, keeping blank lines
  as paragraph breaks."
  [s]
  (->> (paragraphs s)
       (map #(str/trim (str/replace % #"\s+" " ")))
       (remove str/blank?)
       (str/join "\n\n")))

(def ^:no-doc html-token-pattern
  "A `<cite>`, `<time>` or `<p>` element and its content, which ends at its
  end tag or, the end tag of a p being optional in HTML, at the next p or
  the end of the text, so that an unclosed p is read, and not searched to
  the end of the text for an end tag once for every p there is."
  #"(?is)<(cite|time|p)(?:\s[^>]*)?>(.*?)(?:</\1>|(?=<p\b)|$)")

(defn- html-tokens
  "The `[tag text]` pairs of the cite, time and p elements in `s`, in order."
  [s]
  (for [[_ tag body] (re-seq html-token-pattern s)]
    [(str/lower-case tag) (tidy (xml/decode-entities (strip-tags body)))]))

(defn- html-segments
  "Segments from `tokens`: a cite sets the speaker, a time the start, and
  every p yields a segment with both."
  [tokens]
  (:segments
   (reduce (fn [{:keys [speaker start] :as state} [tag text]]
             (case tag
               "cite" (assoc state :speaker (str/replace text #":\s*$" ""))
               "time" (assoc state :start (time/parse-clock text))
               "p"    (if (str/blank? text)
                        state
                        (update state :segments conj
                                (cond-> {:body text}
                                  start   (assoc :start start)
                                  speaker (assoc :speaker speaker))))))
           {:segments []}
           tokens)))

(defn parse-html
  "Read the HTML transcript `s`, a sequence of `<cite>` speakers, `<time>`
  starts and `<p>` paragraphs. A file that is really an SRT with every line
  wrapped in a `<p>`, which some exports produce, is read as the SRT it is
  and reported."
  [s]
  (let [tokens (html-tokens s)
        texts  (map second (filter (comp #{"p"} first) tokens))]
    (if (>= (count (filter #(re-find timing-pattern %) texts)) 2)
      (-> (parse-srt (str/join "\n" texts))
          (assoc :format :html)
          (add-diagnostic (diagnostic :transcript/wrapped-srt :warning
                                "the HTML is an SRT file with every line wrapped in a p element")))
      (with-report {:format :html :segments (html-segments tokens)} []))))

(defn parse-text
  "Read the plain-text transcript `s`: one segment per paragraph, with
  \"Name:\" prefixes as speakers, carried over the paragraphs that follow."
  [s]
  (let [stats    (volatile! {})
        segments (->> (paragraphs s)
                      (map str/trim)
                      (remove str/blank?)
                      (map (fn [paragraph]
                             (let [[speaker body] (speaker-prefix stats paragraph)]
                               (cond-> {:body body}
                                 speaker (assoc :speaker speaker)))))
                      carry-speakers)]
    (with-report {:format :text :segments segments}
      (prefix-diagnostics (:prefixes @stats 0)))))

(defn sniff
  "Guess the format of the transcript text `s`. Cue timings come before
  markup, and markup counts only when the text starts with it, since a
  cue or a paragraph of plain text may mention a tag."
  [s]
  (let [s (str/triml s)]
    (cond
      (str/starts-with? s "WEBVTT")            :vtt
      (str/starts-with? s "{")                 :json
      (re-find #"^\d+\s*\n\s*\d{1,2}:\d{2}" s) :srt
      (re-find timing-pattern s)               :srt
      (and (str/starts-with? s "<")
           (re-find #"(?i)<(cite|time|p)\b" s)) :html
      :else                                    :text)))

(def ^:no-doc format-keys
  "The formats as keywords: :srt, :vtt, :json, :html and :text."
  (set (vals formats)))

(defn- parse-as
  [format s]
  (case format
    :vtt  (parse-vtt s)
    :srt  (parse-srt s)
    :json (parse-json s)
    :html (parse-html s)
    (parse-text s)))

(defn parse
  "Read the transcript `s` in the format its `type` names: a mime type from
  `podcast:transcript`, or one of the keywords in `format-keys`. Without a
  type the format is sniffed. When the file plainly is another format than
  its type says, it is read as what it is and the mismatch is reported. A
  sniff for JSON or HTML can be wrong, since plain text can start with {
  or hold a p tag: when such a reading finds no segments, the file is read
  as its type says after all, or as plain text when it names none."
  ([s]
   (parse s nil))
  ([s type]
   (let [s        (cond-> s (str/starts-with? s "\uFEFF") (subs 1))
         declared (get formats type (when (contains? format-keys type) type))
         sniffed  (sniff s)
         guess    (cond
                    (nil? declared)       sniffed
                    (= declared sniffed)  declared
                    (= sniffed :text)     declared
                    :else                 sniffed)
         fallback (when (#{:json :html} guess) (or declared :text))
         tried    (parse-as guess s)
         [format result] (if (and fallback (not= guess fallback) (empty? (:segments tried)))
                           [fallback (parse-as fallback s)]
                           [guess tried])]
     (cond-> result
       (and (some? type) (nil? declared))
       (add-diagnostic (diagnostic :transcript/unknown-type :warning
                             (str "the declared type " (pr-str type) " is not one the "
                                  "specification names; the file was read as " (name format))
                             :declared type))

       (and declared (not= declared format))
       (add-diagnostic (diagnostic :transcript/type-mismatch :warning
                             (str "declared as " (name declared) " but the file is "
                                  (name format) ", which is how it was read")
                             :declared declared :used format))))))

(defn- turn
  "The segments `run` by one speaker as one turn: the first segment, with
  the bodies of all joined by spaces and the last end the others have."
  [[segment & more :as run]]
  (let [end (some :end (reverse more))]
    (cond-> (assoc segment :body (str/join " " (map :body run)))
      end (assoc :end end))))

(defn turns
  "`transcript` with consecutive segments by the same speaker merged into
  one turn each, spanning their time with their text joined by spaces.
  Segments without a speaker are left as they are. A word-level transcript
  reads as prose this way."
  [transcript]
  (update transcript :segments
          (fn [segments]
            (into []
                  (comp (partition-by :speaker)
                        (mapcat #(if (and (:speaker (first %)) (next %)) [(turn %)] %)))
                  segments))))

(defn text
  "The plain text of `transcript`: the bodies of its segments, one line per
  speaker turn, speakers as prefixes."
  [transcript]
  (->> (:segments transcript)
       (partition-by :speaker)
       (map (fn [run]
              (str (when-let [speaker (:speaker (first run))] (str speaker ": "))
                   (str/join " " (map :body run)))))
       (str/join "\n")))

(defn at
  "The segment of `transcript` being spoken `seconds` into the media, among
  the segments that have a start time."
  [transcript seconds]
  (last (take-while #(<= (:start %) seconds) (filter (comp number? :start) (:segments transcript)))))

#?(:clj
   (comment
     (parse (slurp "test/resources/transcripts/example.vtt"))
     (report (parse (slurp "test/resources/transcripts/example.srt") "text/plain"))
     (println (emit-vtt (parse (slurp "test/resources/transcripts/example.srt"))))
     #_.))
