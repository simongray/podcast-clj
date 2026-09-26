(ns dk.simongray.podcast.transcript
  "Transcripts in the four formats the podcast namespace allows, read into one
  shape and written back: SRT, WebVTT, the JSON segments format and the
  constrained HTML form, plus plain text.

  A transcript is a map:

      {:format   :vtt                                  ; :srt :json :html :text
       :segments [{:start 0.0 :end 2.76 :speaker \"Sarah\" :body \"In today's…\"}]}

  Times are seconds; `:end` and `:speaker` are present when the source had
  them. Speakers are read from `<v>` voice spans, `<cite>` elements and JSON
  fields, and from the \"Name:\" or \"[Name]:\" prefixes caption files use,
  which mark a change of speaker and so carry over the cues that follow.

  Reading is lenient and never throws on content. What it had to forgive is
  in the report, metadata under `:podcast/report` read with `report`: times
  written as strings, speakers taken from prefixes, an SRT wrapped in HTML,
  a declared type that does not match the file."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.xml :as xml]))

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
  "A cue timing line in SRT or WebVTT: start, arrow, end, optional settings."
  #"^\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}[.,]\d{1,3})\s*-->\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}[.,]\d{1,3})")

;;;; The report

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

;;;; Shared pieces

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

;;;; SRT

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

(defn emit-srt
  "Write `transcript` as SRT, one numbered cue per segment, the speaker as a
  \"Name: \" prefix."
  [transcript]
  (str/join "\n\n"
            (map-indexed (fn [i {:keys [start end speaker body]}]
                           (str (inc i) "\n"
                                (time/format-timestamp start ",") " --> "
                                (time/format-timestamp (or end start) ",") "\n"
                                (when speaker (str speaker ": ")) body))
                         (:segments transcript))))

;;;; WebVTT

(defn- strip-tags
  [s]
  (str/replace s #"<[^>]*>" ""))

(defn- vtt-cue
  "The segment for a WebVTT cue block, reading `<v Speaker>` voice spans and,
  failing those, a \"Name:\" prefix."
  [stats lines]
  (let [[timing & payload] (if (re-find timing-pattern (first lines)) lines (rest lines))]
    (when-let [[_ from to] (some->> timing (re-find timing-pattern))]
      (let [text            (str/join "\n" payload)
            voice           (second (re-find #"<v(?:\.[^\s>]*)?\s+([^>]+)>" text))
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
  "Write `transcript` as WebVTT, speakers as `<v>` voice spans."
  [transcript]
  (str "WEBVTT\n\n"
       (str/join "\n\n"
                 (for [{:keys [start end speaker body]} (:segments transcript)]
                   (str (time/format-timestamp start) " --> "
                        (time/format-timestamp (or end start)) "\n"
                        (when speaker (str "<v " speaker ">")) body)))
       "\n"))

;;;; JSON

(defn parse-json
  "Read the JSON transcript `s`: `{\"version\" \"segments\" [{\"speaker\"
  \"startTime\" \"endTime\" \"body\"}]}`. Times written as strings are read
  as numbers and reported."
  [s]
  (let [doc      (json/read-str s)
        raw      (get doc "segments")
        strings  (count (filter #(or (string? (get % "startTime")) (string? (get % "endTime"))) raw))
        segments (vec (for [segment raw
                            :let [start   (time/seconds (get segment "startTime"))
                                  end     (time/seconds (get segment "endTime"))
                                  speaker (get segment "speaker")]]
                        (cond-> {:body (str (get segment "body"))}
                          (some? start) (assoc :start start)
                          (some? end)   (assoc :end end)
                          (seq speaker) (assoc :speaker speaker))))]
    (with-report {:format :json :version (get doc "version") :segments segments}
      (when (pos? strings)
        [(diagnostic :transcript/string-times :warning
                  (str strings " segments give their times as strings rather than numbers")
                  :count strings)]))))

(defn emit-json
  "Write `transcript` in the JSON transcript format, indented unless `opts`
  says `{:pretty? false}`."
  ([transcript]
   (emit-json transcript {}))
  ([transcript {:keys [pretty?] :or {pretty? true}}]
   (json/write-str
    {"version"  (or (:version transcript) "1.0.0")
     "segments" (for [{:keys [start end speaker body]} (:segments transcript)]
                  (cond-> {"startTime" start "body" body}
                    end     (assoc "endTime" end)
                    speaker (assoc "speaker" speaker)))}
    {:pretty? pretty?})))

;;;; HTML

(defn- tidy
  "Collapse the layout whitespace of the HTML text `s`, keeping blank lines
  as paragraph breaks."
  [s]
  (->> (paragraphs s)
       (map #(str/trim (str/replace % #"\s+" " ")))
       (remove str/blank?)
       (str/join "\n\n")))

(def ^:no-doc html-token-pattern
  "A `<cite>`, `<time>` or `<p>` element and its content."
  #"(?is)<(cite|time|p)(?:\s[^>]*)?>(.*?)</\1>")

(defn- html-tokens
  "The `[tag text]` pairs of the cite, time and p elements in `s`, in order."
  [s]
  (for [[_ tag body] (re-seq html-token-pattern s)]
    [(str/lower-case tag) (tidy (xml/decode-entities (strip-tags body)))]))

(defn- html-segments
  "Segments from `tokens`: a cite sets the speaker, a time the start, and
  every p yields a segment with both."
  [tokens]
  (loop [tokens tokens speaker nil start nil acc []]
    (if-let [[tag text] (first tokens)]
      (case tag
        "cite" (recur (rest tokens) (str/replace text #":\s*$" "") start acc)
        "time" (recur (rest tokens) speaker (time/parse-clock text) acc)
        "p"    (recur (rest tokens) speaker start
                      (if (str/blank? text)
                        acc
                        (conj acc (cond-> {:body text}
                                    start   (assoc :start start)
                                    speaker (assoc :speaker speaker))))))
      acc)))

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

;;;; Plain text

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

;;;; Reading any format

(defn sniff
  "Guess the format of the transcript text `s`."
  [s]
  (let [s (str/triml s)]
    (cond
      (str/starts-with? s "WEBVTT")            :vtt
      (str/starts-with? s "{")                 :json
      (re-find #"(?i)<(cite|time|p)\b" s)     :html
      (re-find #"^\d+\s*\n\s*\d{1,2}:\d{2}" s) :srt
      (re-find timing-pattern s)               :srt
      :else                                    :text)))

(def format-keys
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
  its type says, it is read as what it is and the mismatch is reported."
  ([s]
   (parse s nil))
  ([s type]
   (let [declared (get formats type (when (contains? format-keys type) type))
         sniffed  (sniff s)
         format   (cond
                    (nil? declared)       sniffed
                    (= declared sniffed)  declared
                    (= sniffed :text)     declared
                    :else                 sniffed)
         result   (parse-as format s)]
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

;;;; Working with transcripts

(defn turns
  "`transcript` with consecutive segments by the same speaker merged into
  one turn each, spanning their time with their text joined by spaces.
  Segments without a speaker are left as they are. A word-level transcript
  reads as prose this way."
  [transcript]
  (update transcript :segments
          (fn [segments]
            (reduce (fn [acc {:keys [speaker end body] :as segment}]
                      (let [previous (peek acc)]
                        (if (and speaker (= speaker (:speaker previous)))
                          (conj (pop acc) (cond-> (update previous :body str " " body)
                                            end (assoc :end end)))
                          (conj acc segment))))
                    []
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
  "The segment of `transcript` being spoken `seconds` into the media."
  [transcript seconds]
  (last (take-while #(and (:start %) (<= (:start %) seconds)) (:segments transcript))))

#?(:clj
   (comment
     (parse (slurp "test/resources/transcripts/example.vtt"))
     (report (parse (slurp "test/resources/transcripts/example.srt") "text/plain"))
     (println (emit-vtt (parse (slurp "test/resources/transcripts/example.srt"))))
     #_.))
