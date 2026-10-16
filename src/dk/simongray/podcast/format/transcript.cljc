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
  had them. A WebVTT cue with inline timestamps has the :runs of its text
  too, each with its :start-time and :text, e.g. a word each in a
  word-level transcript. Speakers are read from these:

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
            [dk.simongray.podcast.format.transcript.impl :as impl]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.webvtt :as webvtt]))

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

(defn report
  "The diagnostics recorded while reading `transcript`."
  [transcript]
  (:podcast/report (meta transcript)))

(defn parse-srt
  "The transcript map of the SRT text `s`."
  [s]
  (let [stats    (volatile! {})
        segments (->> (impl/blocks s)
                      (keep (fn [[first-line & more]]
                              (if (re-find impl/timing-pattern first-line)
                                (impl/cue stats first-line more)
                                (impl/cue stats
                                          (or (first more) "")
                                          (rest more)))))
                      impl/carry-speakers)]
    (impl/with-report {:format :srt :segments segments}
      (concat (impl/prefix-diagnostics (:prefixes @stats 0))
              (when-let [n (:tags @stats)]
                [(impl/diagnostic :transcript/srt-tags :info
                                  (str n " cues hold formatting tags, which"
                                       " SRT doesn't define, so their text"
                                       " is read without them")
                                  :count n)])))))

(defn emit-srt
  "The `transcript` as SRT, with one numbered cue for each segment that has
  a start time, and the speaker as a \"Name: \" prefix."
  [transcript]
  (let [srt-cue (fn [i {:keys [start-time end-time speaker body]}]
                  (str (inc i) "\n"
                       (time/format-timestamp start-time ",") " --> "
                       (time/format-timestamp (or end-time start-time) ",") "\n"
                       (when speaker (str speaker ": "))
                       (impl/cue-body body)))]
    (str/join "\n\n" (map-indexed srt-cue (impl/timed-segments transcript)))))

(defn parse-vtt
  "The transcript map of the WebVTT text `s`, whose cues webvtt-clj reads
  as a browser does.

  A voice span names the speaker of its cue alone, unless the file marks
  only where the speaker changes, when the speaker carries over the cues
  that follow, and the report says so. A \"Name:\" prefix always carries
  over. A cue whose timings don't parse is left out, and the report counts
  those."
  [s]
  (let [stats    (volatile! {})
        cues     (mapv #(impl/vtt-cue stats %) (:cues (webvtt/parse s)))
        dropped  (->> (webvtt/conformance-errors s :metadata)
                      (filter (comp #{:timings} :type))
                      count)
        turns?   (impl/turn-marked? cues)
        carried  (impl/carry-speakers cues (if turns? (constantly true)
                                               (complement ::voice?)))
        voiced   (when turns?
                   (count (filter true? (map #(and (nil? (:speaker %1))
                                                   (some? (:speaker %2)))
                                             cues carried))))
        segments (mapv #(dissoc % ::voice?) carried)]
    (impl/with-report {:format :vtt :segments segments}
      (concat (impl/dropped-diagnostics dropped)
              (impl/prefix-diagnostics (:prefixes @stats 0))
              (impl/voices-diagnostics (or voiced 0))))))

(defn emit-vtt
  "The `transcript` as WebVTT, with the speakers as voice spans and the
  timed runs of a segment as inline timestamps. Segments without a start
  time are left out."
  [transcript]
  (webvtt/emit {:cues (mapv (fn [{:keys [start-time end-time] :as segment}]
                              {:start-time start-time
                               :end-time   (or end-time start-time)
                               :text       (webvtt/cue-text
                                            (impl/cue-nodes segment))})
                            (impl/timed-segments transcript))}))

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
    (impl/with-report {:format :json :version version :segments segments}
      (cond-> []
        (not (map? doc))
        (conj (impl/diagnostic :transcript/not-an-object :warning
                               "the file is not a JSON object"))

        (pos? dropped)
        (conj (impl/diagnostic :transcript/malformed :warning
                               (str dropped " segments are not JSON objects"
                                    " in a list and were left out")
                               :count dropped))

        (pos? strings)
        (conj (impl/diagnostic :transcript/string-times :warning
                               (str strings " segments give their times as"
                                    " strings rather than numbers")
                               :count strings))

        (pos? not-text)
        (conj (impl/diagnostic :transcript/not-text :warning
                               (str not-text " speakers are not text and"
                                    " were left out")
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
                      (impl/timed-segments transcript)]
                  (cond-> {"startTime" start-time "body" body}
                    end-time (assoc "endTime" end-time)
                    speaker  (assoc "speaker" speaker)))}
    {:pretty? pretty?})))

(defn parse-html
  "The transcript map of the HTML transcript `s`, a sequence of cite
  speakers, time starts and p paragraphs.

  Some exports wrap every line of an SRT file in a p. Such a file is read
  as the SRT that it is, and the report says so."
  [s]
  (let [tokens (impl/html-tokens s)
        texts  (map second (filter (comp #{"p"} first) tokens))]
    (if (>= (count (filter #(re-find impl/timing-pattern %) texts)) 2)
      (-> (parse-srt (str/join "\n" texts))
          (assoc :format :html)
          (impl/add-diagnostic
           (impl/diagnostic :transcript/wrapped-srt :warning
                            (str "the HTML is an SRT file with every"
                                 " line wrapped in a p element"))))
      (impl/with-report {:format   :html
                         :segments (impl/html-segments tokens)}
        []))))

(defn parse-text
  "The transcript map of the plain-text transcript `s`, with a segment for
  each paragraph. A \"Name:\" prefix gives the speaker, which carries over
  the paragraphs that follow."
  [s]
  (let [stats    (volatile! {})
        segment  (fn [paragraph]
                   (let [[speaker body] (impl/speaker-prefix stats paragraph)]
                     (cond-> {:body body}
                       speaker (assoc :speaker speaker))))
        segments (->> (impl/paragraphs s)
                      (map str/trim)
                      (remove str/blank?)
                      (map segment)
                      impl/carry-speakers)]
    (impl/with-report {:format :text :segments segments}
      (impl/prefix-diagnostics (:prefixes @stats 0)))))

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
      (re-find impl/timing-pattern s)          :srt
      (and (str/starts-with? s "<")
           (re-find #"(?i)<(cite|time|p)\b" s)) :html
      :else                                    :text)))

(def format-keys
  "The formats as keywords, :srt, :vtt, :json, :html and :text, which parse
  takes as a type too."
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
       (impl/add-diagnostic
        (impl/diagnostic :transcript/unknown-type :warning
                         (str "the declared type " (pr-str type)
                              " is not one the specification"
                              " names; the file was read as "
                              (name format))
                         :declared type))

       (and declared (not= declared format))
       (impl/add-diagnostic
        (impl/diagnostic :transcript/type-mismatch :warning
                         (str "declared as " (name declared)
                              " but the file is " (name format)
                              ", which is how it was read")
                         :declared declared
                         :used format))))))

(defn turns
  "The `transcript` with each run of segments by the same speaker merged
  into one turn, which spans their time and joins their text by spaces.

  Segments without a speaker stay as they are. A word-level transcript
  reads as prose this way."
  [transcript]
  (update transcript :segments
          (fn [segments]
            (let [merged #(if (and (:speaker (first %)) (next %))
                            [(impl/turn %)]
                            %)]
              (into []
                    (comp (partition-by :speaker) (mapcat merged))
                    segments)))))

;; WAI, Transcripts: put the text in paragraphs and name who speaks
;; https://www.w3.org/WAI/media/av/transcripts/
(defn paragraphs
  "The `transcript` with its segments merged into paragraphs for reading,
  which last up to about `span` seconds, by default 60.

  A paragraph holds segments by one speaker, or by none, as turns merges
  them. It ends at the end of a sentence once it's `span` seconds long, or
  at twice that when the text has no punctuation. A caption file of short
  cues reads as prose this way, and a long turn reads as paragraphs. Each
  paragraph has its text on one line. Its :runs time that text by the cue
  or by the word, e.g. to mark what's being said.

  Segments without text are left out. A segment without a start time is a
  paragraph of its own, since plain text and HTML without times come in
  paragraphs already."
  ([transcript]
   (paragraphs transcript 60))
  ([transcript span]
   (update transcript :segments
           (fn [segments]
             (into []
                   (comp (remove (comp str/blank? :body))
                         (partition-by :speaker)
                         (mapcat #(impl/paragraph-pieces % span))
                         (map impl/paragraph))
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

(def passage-length
  "The default :length of matches: the fewest characters of a passage,
  which a caption file's cues mostly have and a word-level transcript's
  words make up."
  60)

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
                  (search.queries/parse q (dissoc opts :length))
                  q)
         marked (fn [piece]
                  (let [{:keys [body] :as passage} (impl/turn piece)
                        width {:width (count body)}]
                    (when (search.queries/matches? body query)
                      (let [parts (search.queries/snippet body query width)]
                        (assoc passage
                               :start-time (impl/start-of-match piece parts)
                               :parts      parts)))))]
     (into []
           (comp (partition-by :speaker)
                 (mapcat #(impl/pieces % length))
                 (keep marked))
           (:segments transcript)))))

#?(:clj
   (comment
     (parse (slurp "test/resources/transcripts/example.vtt"))
     (def srt (slurp "test/resources/transcripts/example.srt"))
     (report (parse srt "text/plain"))
     (println (emit-vtt (parse srt)))
     #_.))
