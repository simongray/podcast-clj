(ns dk.simongray.podcast.format.transcript
  "Transcripts in the four formats that the podcast namespace allows, read
  into one shape and written back: SRT, WebVTT, the JSON segments format
  and the constrained HTML form, plus plain text.

  Each format has its reader, parse-srt, parse-vtt, parse-json, parse-html
  and parse-text, and parse picks one by the type or by the file. The
  first three have writers too, emit-srt, emit-vtt and emit-json.

  A transcript is a map:

      {:format   :vtt                     ; :srt :json :html :text
       :segments [{:start-time 0.0 :end-time 2.76 :speaker \"Sarah\"
                   :body \"…\"}]}

  Times are seconds, and :end-time and :speaker are there when the source
  had them. Speakers are read from these:

  - the voice spans of WebVTT, which name the speaker of their cue, or of
    the cues that follow too in a file that marks only the changes of
    speaker
  - the cite elements of HTML
  - the fields of JSON
  - the \"Name:\" or \"[Name]:\" prefixes of caption files, which mark a
    change of speaker, so the name carries over the cues that follow

  Reading is lenient and never throws on content. The report says what it
  had to forgive, e.g. times written as strings, an SRT wrapped in HTML or
  a declared type that doesn't match the file. The report is in the
  metadata."
  (:require [clojure.string :as str]
            [dk.simongray.drop-in-search.queries :as search.queries]
            [dk.simongray.podcast.util.entities :as entities]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

;; podcast:transcript names five types. application/srt is of Apple's
;; guide, and text/srt is in feeds.
(def formats
  "The transcript format of each MIME type that podcast:transcript gives."
  {"text/vtt"             :vtt
   "application/x-subrip" :srt
   "application/srt"      :srt
   "text/srt"             :srt
   "application/json"     :json
   "text/html"            :html
   "text/plain"           :text})

(def ^:no-doc timing-pattern
  "A cue timing line in SRT or WebVTT: the start, an arrow, the end and any
  settings. The fraction of a second may be missing, as some exports leave
  it out."
  (let [clock "((?:\\d{1,3}:)?\\d{1,2}:\\d{1,2}(?:[.,]\\d{1,3})?)"]
    (re-pattern (str "^\\s*" clock "\\s*-->\\s*" clock))))

(defn report
  "The diagnostics recorded while reading `transcript`."
  [transcript]
  (:podcast/report (meta transcript)))

(defn- diagnostic
  [id level message & {:as details}]
  (merge {:id id :level level :message message} details))

(defn- with-report
  "The `transcript` with `diagnostics` as its report."
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
  "The speaker of a leading \"Name: \" or \"[Name]: \" in `body`, and the
  rest of `body`, as a pair. The speaker is nil when there's none, and a
  hit counts in `stats`."
  [stats body]
  (let [prefixed #"(?s)^\[?([A-Za-z][^:\n<>\[\]]{0,40}?)\]?:\s+(.*)$"]
    (if-let [[_ speaker rest] (re-find prefixed body)]
      (do (vswap! stats update :prefixes (fnil inc 0))
          [speaker rest])
      [nil body])))

(defn- carry-speakers
  "The `segments`, each that names no speaker with the last one named
  before it, the way caption files mark only the changes of speaker. Only
  the speaker of a segment that `carries?` carries over, and another ends
  the one before, by default every segment."
  ([segments]
   (carry-speakers segments (constantly true)))
  ([segments carries?]
   (second (reduce (fn [[speaker acc] segment]
                     (cond
                       (:speaker segment)
                       [(when (carries? segment) (:speaker segment))
                        (conj acc segment)]

                       speaker
                       [speaker (conj acc (assoc segment :speaker speaker))]

                       :else
                       [nil (conj acc segment)]))
                   [nil []]
                   segments))))

(defn- strip-tags
  [s]
  ;; [^<>] rather than [^>], so that a < with no > after it is given up at
  ;; the next <, not at the end of the text, once for every < there is
  (str/replace s #"<[^<>]*>" ""))

(def ^:no-doc srt-tag-pattern
  "A tag in the text of an SRT cue, e.g. <i> or <font color=\"red\">. It
  starts with a letter, so that x < y > z stays as it is."
  #"</?[A-Za-z][^<>]*>")

(defn- cue
  "The segment of an SRT cue whose timing line is `timing` and text lines
  `lines`, with a prefix and formatting tags counted in `stats`, or nil
  when the timing doesn't parse.

  SRT has no markup, but captioning tools write the tags of HTML, so the
  text is read without them, and with its character references decoded,
  as a WebVTT cue is."
  [stats timing lines]
  (when-let [[_ from to] (re-find timing-pattern timing)]
    (let [text           (str/join "\n" lines)
          tagged?        (re-find srt-tag-pattern text)
          plain          (-> (str/replace text srt-tag-pattern "")
                             entities/decode-entities)
          [speaker body] (speaker-prefix stats plain)]
      (when tagged?
        (vswap! stats update :tags (fnil inc 0)))
      (cond-> {:start-time (time/parse-clock from)
               :end-time   (time/parse-clock to)
               :body       body}
        speaker (assoc :speaker speaker)))))

(defn parse-srt
  "The transcript map of the SRT text `s`."
  [s]
  (let [stats    (volatile! {})
        segments (->> (blocks s)
                      (keep (fn [[first-line & more]]
                              (if (re-find timing-pattern first-line)
                                (cue stats first-line more)
                                (cue stats (or (first more) "") (rest more)))))
                      carry-speakers)]
    (with-report {:format :srt :segments segments}
      (concat (prefix-diagnostics (:prefixes @stats 0))
              (when-let [n (:tags @stats)]
                [(diagnostic :transcript/srt-tags :info
                             (str n " cues hold formatting tags, which SRT"
                                  " doesn't define, so their text is read"
                                  " without them")
                             :count n)])))))

(defn- timed-segments
  "The segments of `transcript` that have a start time, which are what the
  timed formats write."
  [transcript]
  (filter (comp number? :start-time) (:segments transcript)))

(defn ^:no-doc cue-body
  "The `body` of a segment as a cue carries it: trimmed, and without blank
  lines. A blank line ends a cue in SRT and WebVTT, so the next read would
  lose the rest of the body."
  [body]
  (str/replace (str/trim (str body)) #"\n[ \t\r]*\n\s*" "\n"))

(defn emit-srt
  "The `transcript` as SRT, with one numbered cue for each segment that has
  a start time, and the speaker as a \"Name: \" prefix."
  [transcript]
  (let [srt-cue (fn [i {:keys [start-time end-time speaker body]}]
                  (str (inc i) "\n"
                       (time/format-timestamp start-time ",") " --> "
                       (time/format-timestamp (or end-time start-time) ",") "\n"
                       (when speaker (str speaker ": "))
                       (cue-body body)))]
    (str/join "\n\n" (map-indexed srt-cue (timed-segments transcript)))))

(defn- vtt-cue
  "The segment of the WebVTT cue block `lines`, with its speaker from a
  voice span or else from a \"Name:\" prefix, which counts in `stats`."
  [stats lines]
  (let [[timing & payload] (if (re-find timing-pattern (first lines))
                             lines
                             (rest lines))]
    (when-let [[_ from to] (some->> timing (re-find timing-pattern))]
      (let [voice-span      #"<v(?:\.[^\s<>]*)?\s+([^<>]+)>"
            text            (str/join "\n" payload)
            voice           (some-> (re-find voice-span text)
                                    second
                                    entities/decode-entities)
            plain           (-> text strip-tags str/trim entities/decode-entities)
            [prefixed body] (if voice [nil plain] (speaker-prefix stats plain))
            speaker         (or voice prefixed)]
        (cond-> {:start-time (time/parse-clock from)
                 :end-time   (time/parse-clock to)
                 :body       body}
          speaker (assoc :speaker speaker)
          voice   (assoc ::voice? true))))))

;; WebVTT, section 4.2.2, scopes a voice span to its cue, but some hosts,
;; e.g. Transistor, write one only where the speaker changes. A file that
;; marks every cue repeats a voice on cues in a row, and one that marks the
;; changes never does.
(defn- turn-marked?
  "Whether the WebVTT `cues` give a voice span only where the speaker
  changes: some have one, and no two in a row have the same speaker's."
  [cues]
  (and (some ::voice? cues)
       (not-any? (fn [[a b]]
                   (and (::voice? a) (::voice? b) (= (:speaker a) (:speaker b))))
                 (partition 2 1 cues))))

(defn- voices-diagnostics
  "The diagnostic for `n` cues that took the speaker of a voice span before
  them."
  [n]
  (when (pos? n)
    [(diagnostic :transcript/voices-carried :info
                 (str n " cues have no voice span of their own; the file "
                      "marks only where the speaker changes, so each takes "
                      "the speaker before it")
                 :count n)]))

(defn parse-vtt
  "The transcript map of the WebVTT text `s`, without its NOTE, STYLE and
  REGION blocks.

  A voice span names the speaker of its cue alone, unless the file marks
  only where the speaker changes, when the speaker carries over the cues
  that follow, and the report says so. A \"Name:\" prefix always carries
  over."
  [s]
  (let [stats    (volatile! {})
        cue?     #(not (re-find #"^(?:WEBVTT|NOTE|STYLE|REGION)" (first %)))
        cues     (->> (blocks s) (filter cue?) (keep #(vtt-cue stats %)) vec)
        turns?   (turn-marked? cues)
        carried  (carry-speakers cues (if turns? (constantly true)
                                          (complement ::voice?)))
        voiced   (when turns?
                   (count (filter true? (map #(and (nil? (:speaker %1))
                                                   (some? (:speaker %2)))
                                             cues carried))))
        segments (mapv #(dissoc % ::voice?) carried)]
    (with-report {:format :vtt :segments segments}
      (concat (prefix-diagnostics (:prefixes @stats 0))
              (voices-diagnostics (or voiced 0))))))

;; WebVTT, section 4.2.2: cue text has no & or <, and the annotation of a
;; voice span no & or >, so those go as the character references of HTML
(defn- vtt-text
  "The text `s` as the payload of a cue writes it."
  [s]
  (str/escape s {\& "&amp;" \< "&lt;" \> "&gt;"}))

(defn emit-vtt
  "The `transcript` as WebVTT, with the speakers as voice spans. Segments
  without a start time are left out."
  [transcript]
  (str "WEBVTT\n\n"
       (str/join "\n\n"
                 (for [{:keys [start-time end-time speaker body]}
                       (timed-segments transcript)]
                   (str (time/format-timestamp start-time) " --> "
                        (time/format-timestamp (or end-time start-time)) "\n"
                        (when speaker (str "<v " (vtt-text speaker) ">"))
                        (vtt-text (cue-body body)))))
       "\n"))

(defn parse-json
  "The transcript map of the JSON transcript `s`, whose segments have a
  speaker, a startTime, an endTime and a body.

  Bad content doesn't throw, and the report says what was forgiven:

  - times written as strings are read as numbers
  - a file that isn't a JSON object reads as no segments
  - a segment that isn't an object is left out
  - a speaker that isn't text is left out, but a number counts as text"
  [s]
  (let [doc      (json/parse s)
        raw      (when (map? doc) (get doc "segments"))
        objects  (if (sequential? raw) (filterv map? raw) [])
        dropped  (cond
                   (sequential? raw) (- (count raw) (count objects))
                   (some? raw)       1
                   :else             0)
        strings  (count (filter #(or (string? (get % "startTime"))
                                     (string? (get % "endTime")))
                                objects))
        not-text (count (filter #(let [v (get % "speaker")]
                                   (and (some? v) (nil? (json/text v))))
                                objects))
        read-one (fn [segment]
                   (let [start   (time/seconds (get segment "startTime"))
                         end     (time/seconds (get segment "endTime"))
                         speaker (json/text (get segment "speaker"))]
                     (cond-> {:body (str (get segment "body"))}
                       (some? start) (assoc :start-time start)
                       (some? end)   (assoc :end-time end)
                       (seq speaker) (assoc :speaker speaker))))
        segments (mapv read-one objects)
        version  (when (map? doc) (get doc "version"))]
    (with-report {:format :json :version version :segments segments}
      (cond-> []
        (not (map? doc))
        (conj (diagnostic :transcript/not-an-object :warning
                          "the file is not a JSON object"))

        (pos? dropped)
        (conj (diagnostic :transcript/malformed :warning
                          (str dropped " segments are not JSON objects in a"
                               " list and were left out")
                          :count dropped))

        (pos? strings)
        (conj (diagnostic :transcript/string-times :warning
                          (str strings " segments give their times as strings"
                               " rather than numbers")
                          :count strings))

        (pos? not-text)
        (conj (diagnostic :transcript/not-text :warning
                          (str not-text " speakers are not text and were"
                               " left out")
                          :count not-text))))))

(defn emit-json
  "The `transcript` in the JSON transcript format, indented unless `opts`
  has :pretty? false. Segments without a start time are left out."
  ([transcript]
   (emit-json transcript {}))
  ([transcript {:keys [pretty?] :or {pretty? true} :as opts}]
   (json/write-str
    {"version"  (or (:version transcript) "1.0.0")
     "segments" (for [{:keys [start-time end-time speaker body]}
                      (timed-segments transcript)]
                  (cond-> {"startTime" start-time "body" body}
                    end-time (assoc "endTime" end-time)
                    speaker  (assoc "speaker" speaker)))}
    {:pretty? pretty?})))

(defn- tidy
  "The HTML text `s` with its layout whitespace collapsed, and its blank
  lines kept as paragraph breaks."
  [s]
  (->> (paragraphs s)
       (map #(str/trim (str/replace % #"\s+" " ")))
       (remove str/blank?)
       (str/join "\n\n")))

(def ^:no-doc html-token-pattern
  "A cite, time or p element and its content.

  The content ends at the end tag, or for a p, whose end tag is optional
  in HTML, at the next p or the end of the text. So an unclosed p is read,
  and the pattern doesn't search to the end of the text for an end tag
  once for every p."
  #"(?is)<(cite|time|p)(?:\s[^>]*)?>(.*?)(?:</\1>|(?=<p\b)|$)")

(defn- html-tokens
  "The tag and the text of each cite, time and p element in `s`, in order,
  as pairs."
  [s]
  (for [[_ tag body] (re-seq html-token-pattern s)]
    [(str/lower-case tag) (tidy (entities/decode-entities (strip-tags body)))]))

(defn- html-segments
  "The segments of `tokens`: a cite sets the speaker, a time the start, and
  every p gives a segment with both."
  [tokens]
  (:segments
   (reduce (fn [{:keys [speaker start-time] :as state} [tag text]]
             (case tag
               "cite" (assoc state :speaker (str/replace text #":\s*$" ""))
               "time" (assoc state :start-time (time/parse-clock text))
               "p"    (if (str/blank? text)
                        state
                        (update state :segments conj
                                (cond-> {:body text}
                                  start-time (assoc :start-time start-time)
                                  speaker    (assoc :speaker speaker))))))
           {:segments []}
           tokens)))

(defn parse-html
  "The transcript map of the HTML transcript `s`, a sequence of cite
  speakers, time starts and p paragraphs.

  Some exports wrap every line of an SRT file in a p. Such a file is read
  as the SRT that it is, and the report says so."
  [s]
  (let [tokens (html-tokens s)
        texts  (map second (filter (comp #{"p"} first) tokens))]
    (if (>= (count (filter #(re-find timing-pattern %) texts)) 2)
      (-> (parse-srt (str/join "\n" texts))
          (assoc :format :html)
          (add-diagnostic (diagnostic :transcript/wrapped-srt :warning
                                      (str "the HTML is an SRT file with every"
                                           " line wrapped in a p element"))))
      (with-report {:format :html :segments (html-segments tokens)} []))))

(defn parse-text
  "The transcript map of the plain-text transcript `s`, with a segment for
  each paragraph. A \"Name:\" prefix gives the speaker, which carries over
  the paragraphs that follow."
  [s]
  (let [stats    (volatile! {})
        segment  (fn [paragraph]
                   (let [[speaker body] (speaker-prefix stats paragraph)]
                     (cond-> {:body body}
                       speaker (assoc :speaker speaker))))
        segments (->> (paragraphs s)
                      (map str/trim)
                      (remove str/blank?)
                      (map segment)
                      carry-speakers)]
    (with-report {:format :text :segments segments}
      (prefix-diagnostics (:prefixes @stats 0)))))

(defn sniff
  "The likely format of the transcript text `s`. Cue timings come before
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
  "The transcript map of `s`, in the format that `type` names: a MIME type
  of podcast:transcript, or one of format-keys.

  Without a type, the format is sniffed. When the file is plainly in
  another format than its type says, it's read as what it is, and the
  report names the mismatch.

  A sniff for JSON or HTML can be wrong, since plain text can start with a
  brace or hold a p tag. When such a reading finds no segments, the file
  is read as its type says after all, or as plain text when it names none."
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
         retry?   (and fallback
                       (not= guess fallback)
                       (empty? (:segments tried)))
         [format result] (if retry?
                           [fallback (parse-as fallback s)]
                           [guess tried])]
     (cond-> result
       (and (some? type) (nil? declared))
       (add-diagnostic (diagnostic :transcript/unknown-type :warning
                                   (str "the declared type " (pr-str type)
                                        " is not one the specification"
                                        " names; the file was read as "
                                        (name format))
                                   :declared type))

       (and declared (not= declared format))
       (add-diagnostic (diagnostic :transcript/type-mismatch :warning
                                   (str "declared as " (name declared)
                                        " but the file is " (name format)
                                        ", which is how it was read")
                                   :declared declared
                                   :used format))))))

(defn- turn
  "The segments `run` by one speaker as one turn: the first segment, with
  the bodies of all joined by spaces and the last end the others have."
  [[segment & more :as run]]
  (let [end-time (some :end-time (reverse more))]
    (cond-> (assoc segment :body (str/join " " (map :body run)))
      end-time (assoc :end-time end-time))))

(defn turns
  "The `transcript` with each run of segments by the same speaker merged
  into one turn, which spans their time and joins their text by spaces.

  Segments without a speaker stay as they are. A word-level transcript
  reads as prose this way."
  [transcript]
  (update transcript :segments
          (fn [segments]
            (let [merged #(if (and (:speaker (first %)) (next %)) [(turn %)] %)]
              (into []
                    (comp (partition-by :speaker) (mapcat merged))
                    segments)))))

(defn text
  "The plain text of `transcript`: the bodies of its segments, with a line
  for each speaker turn and the speakers as prefixes."
  [transcript]
  (->> (:segments transcript)
       (partition-by :speaker)
       (map (fn [run]
              (let [speaker (:speaker (first run))]
                (str (when speaker (str speaker ": "))
                     (str/join " " (map :body run))))))
       (str/join "\n")))

(defn at
  "The segment of `transcript` being spoken `seconds` into the media, among
  the segments that have a start time."
  [transcript seconds]
  (->> (:segments transcript)
       (filter (comp number? :start-time))
       (take-while #(<= (:start-time %) seconds))
       last))

(defn- pieces
  "The segments of `run` in groups in order, each with bodies of at least
  `length` characters but the last."
  [run length]
  (loop [run run piece [] size 0 acc []]
    (if-let [[segment & more] (seq run)]
      (let [piece (conj piece segment)
            size  (+ size 1 (count (:body segment)))]
        (if (<= length size)
          (recur more [] 0 (conj acc piece))
          (recur more piece size acc)))
      (cond-> acc
        (seq piece) (conj piece)))))

(def ^:no-doc passage-length
  "The fewest characters of a matching passage, which a caption file's
  cues mostly have and a word-level transcript's words make up."
  60)

(defn- start-of-match
  "The start time of the segment of `piece`, the segments of a passage,
  where the first of the `parts` that match starts."
  [piece parts]
  (let [offset (reduce (fn [n {:keys [text match?]}]
                         (if match?
                           (reduced n)
                           (+ n (count text))))
                       0
                       parts)
        starts (reductions + 0 (map #(inc (count (:body %))) piece))]
    (->> (map vector starts piece)
         (take-while #(<= (first %) offset))
         last
         second
         :start-time)))

(defn matches
  "The passages of `transcript` that match the query `q`, each with :parts
  that mark the matches in its :body, with the `opts` below.

  A passage is a run of segments by one speaker of at least the :length
  in `opts`, merged into one turn, with the :start-time of the segment
  where its first match is, e.g. for a player to list where words are
  said and seek there. A caption file matches by a cue or two, and a
  word-level transcript by a phrase. The `opts` are those of
  dk.simongray.drop-in-search.queries/parse, and :length, 60 characters by
  default."
  ([transcript q]
   (matches transcript q {}))
  ([transcript q {:keys [length] :or {length passage-length} :as opts}]
   {:pre [(map? opts)]}
   (let [query  (if (string? q)
                  (search.queries/parse q opts)
                  q)
         marked (fn [piece]
                  (let [{:keys [body] :as passage} (turn piece)
                        width {:width (count body)}]
                    (when (search.queries/matches? body query)
                      (let [parts (search.queries/snippet body query width)]
                        (assoc passage
                               :start-time (start-of-match piece parts)
                               :parts      parts)))))]
     (into []
           (comp (partition-by :speaker)
                 (mapcat #(pieces % length))
                 (keep marked))
           (:segments transcript)))))

#?(:clj
   (comment
     (parse (slurp "test/resources/transcripts/example.vtt"))
     (def srt (slurp "test/resources/transcripts/example.srt"))
     (report (parse srt "text/plain"))
     (println (emit-vtt (parse srt)))
     #_.))
