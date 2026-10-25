(ns ^:no-doc dk.simongray.podcast.format.transcript.impl
  "The cue, paragraph and HTML readers, the speaker and diagnostic helpers,
  and the passage and paragraph arithmetic behind format.transcript."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.format.transcript :as-alias transcript]
            [dk.simongray.podcast.util.entities :as entities]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.webvtt :as webvtt]))

(def timing-pattern
  "A cue timing line in SRT or WebVTT: the start, an arrow, the end and any
  settings. The fraction of a second may be missing, as some exports leave
  it out."
  (let [clock "((?:\\d{1,3}:)?\\d{1,2}:\\d{1,2}(?:[.,]\\d{1,3})?)"]
    (re-pattern (str "^\\s*" clock "\\s*-->\\s*" clock))))

(defn diagnostic
  "The diagnostic of `id` at `level`, with its `message` and any `details`,
  as the report of a transcript holds it."
  [id level message & {:as details}]
  (merge {:id id :level level :message message} details))

(defn with-report
  "The `transcript` with `diagnostics` as its report."
  [transcript diagnostics]
  (with-meta transcript {:podcast/report (vec diagnostics)}))

(defn add-diagnostic
  "The `transcript` with `diagnostic` added to its report."
  [transcript diagnostic]
  (vary-meta transcript update :podcast/report (fnil conj []) diagnostic))

(defn prose?
  "Whether the `stats` of a file count at least as many colons after words
  that can't be a name as after names, so that its colons belong to its
  sentences."
  [{:keys [prefixes prose] :or {prefixes 0 prose 0}}]
  (and (pos? prose) (>= prose prefixes)))

(defn prefix-diagnostics
  "The diagnostic for the cues that the `stats` of a file count as naming
  their speaker with a prefix, or for its colons in sentences."
  [{:keys [prefixes prose] :or {prefixes 0} :as stats}]
  (cond
    (prose? stats)
    [(diagnostic :transcript/prose-colons :info
                 (str prose " cues start with words before a colon that "
                      "can't be a name, so the file's colons are read as "
                      "part of its sentences, and no \"Name:\" prefix "
                      "names a speaker")
                 :count prose)]

    (pos? prefixes)
    [(diagnostic :transcript/speaker-prefixes :info
                 (str prefixes " cues name their speaker with a \"Name:\" "
                      "prefix; the name is carried over the cues that follow")
                 :count prefixes)]))

(defn paragraphs
  "The blank-line separated stretches of `s`."
  [s]
  (str/split (str/replace s "\r\n" "\n") #"\n[ \t]*\n"))

(defn blocks
  "The blank-line separated blocks of `s`, each a vector of lines."
  [s]
  (->> (str/split-lines (str/replace s "\r\n" "\n"))
       (partition-by str/blank?)
       (remove #(str/blank? (first %)))
       (map vec)))

;; A caption file can wrap a sentence just before a colon, so that a cue
;; starts with its last words, e.g. "so: Wenn du", and a title has one too
(defn speaker-name?
  "Whether the words `s` before a colon can name a speaker: they start
  with a letter that isn't lower case, as a name does in a script with
  case, and are at most four."
  [s]
  ;; JavaScript needs the u flag for \p{Ll}
  (and (not (re-find #"(?u)^\p{Ll}" s))
       (<= (count (str/split (str/trim s) #"\s+")) 4)))

(defn speaker-prefix
  "The speaker of a leading \"Name: \" or \"[Name]: \" in `body`, the rest
  of `body`, and the prefix, or the pair of nil and `body` when there's
  none. A name starts with a letter of any script, so a timestamp never
  counts as one, and words that speaker-name? refuses aren't a name. The
  `stats` count each name as one of the :prefixes, and the other words as
  :prose."
  [stats body]
  ;; JavaScript needs the u flag for \p{L}
  (let [prefixed #"(?su)^(\[?(\p{L}[^:\n<>\[\]]{0,40}?)\]?:\s+)(.*)$"
        [_ prefix speaker rest] (re-find prefixed body)]
    (cond
      (nil? prefix)
      [nil body]

      (speaker-name? speaker)
      (do (vswap! stats update :prefixes (fnil inc 0))
          [speaker rest prefix])

      :else
      (do (vswap! stats update :prose (fnil inc 0))
          [nil body]))))

(defn- as-written
  "The `segment` with the prefix that named its speaker put back in its
  body and its first run, and without the speaker."
  [{::transcript/keys [prefix] :keys [runs] :as segment}]
  (cond-> segment
    prefix            (-> (dissoc :speaker)
                          (update :body #(str prefix %)))
    (and prefix runs) (update-in [:runs 0 :text] #(str prefix %))))

(defn settle-prefixes
  "The `segments` with the speakers of their prefixes, or without them when
  the `stats` of the file say that its colons belong to its sentences."
  [stats segments]
  (let [segments (vec segments)
        prose    (prose? @stats)]
    (mapv #(dissoc (cond-> % prose as-written) ::transcript/prefix)
          segments)))

(defn carry-speakers
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

(def srt-tag-pattern
  "A tag in the text of an SRT cue, e.g. <i> or <font color=\"red\">. It
  starts with a letter, so that x < y > z stays as it is."
  #"</?[A-Za-z][^<>]*>")

(defn cue
  "The segment of an SRT cue whose timing line is `timing` and text lines
  `lines`, with a prefix and formatting tags counted in `stats`, or nil
  when the timing doesn't parse.

  SRT has no markup, but captioning tools write the tags of HTML, so the
  text is read without them, and with its character references decoded,
  as a WebVTT cue is."
  [stats timing lines]
  (when-let [[_ from to] (re-find timing-pattern timing)]
    (let [text                  (str/join "\n" lines)
          tagged?               (re-find srt-tag-pattern text)
          plain                 (-> (str/replace text srt-tag-pattern "")
                                    entities/decode-entities)
          [speaker body prefix] (speaker-prefix stats plain)]
      (when tagged?
        (vswap! stats update :tags (fnil inc 0)))
      (cond-> {:start-time (time/parse-clock from)
               :end-time   (time/parse-clock to)
               :body       body}
        speaker (assoc :speaker speaker ::transcript/prefix prefix)))))

(defn timed-segments
  "The segments of `transcript` that have a start time, which are what the
  timed formats write."
  [transcript]
  (filter (comp number? :start-time) (:segments transcript)))

(defn cue-body
  "The `body` of a segment as a cue carries it: trimmed, and without blank
  lines. A blank line ends a cue in SRT and WebVTT, so the next read would
  lose the rest of the body."
  [body]
  (str/replace (str/trim (str body)) #"\n[ \t\r]*\n\s*" "\n"))

(defn- first-voice
  "The voice of the first voice span in the cue text `nodes`, as Hiccup
  from webvtt/hiccup, or nil."
  [nodes]
  (->> (tree-seq sequential? #(if (keyword? (first %)) (drop 2 %) %) nodes)
       (some #(when (and (vector? %) (= :v (first %)))
                (:voice (second %))))))

(defn vtt-cue
  "The segment of the WebVTT `cue`. Its speaker comes from its first voice
  span, or else from a \"Name:\" prefix, which counts in `stats`. A cue
  with inline timestamps gives the timed runs of its text too."
  [stats cue]
  (let [voice                  (first-voice (webvtt/hiccup cue))
        plain                  (str/trim (webvtt/text cue))
        [prefixed body prefix] (if voice
                                 [nil plain]
                                 (speaker-prefix stats plain))
        speaker                (or voice prefixed)
        unprefixed             #(second (speaker-prefix (volatile! {})
                                                        (str/triml %)))
        runs                   (webvtt/runs cue)]
    (cond-> {:start-time (:start-time cue)
             :end-time   (:end-time cue)
             :body       body}
      speaker     (assoc :speaker speaker)
      prefixed    (assoc ::transcript/prefix prefix)
      voice       (assoc ::transcript/voice? true)
      (next runs) (assoc :runs (cond-> (vec runs)
                                 prefixed (update-in [0 :text] unprefixed))))))

;; WebVTT, section 4.2.2, scopes a voice span to its cue, but some hosts,
;; e.g. Transistor, write one only where the speaker changes. A file that
;; marks every cue repeats a voice on cues in a row, and one that marks the
;; changes never does.
(defn turn-marked?
  "Whether the WebVTT `cues` give a voice span only where the speaker
  changes: some have one, and no two in a row have the same speaker's."
  [cues]
  (and (some ::transcript/voice? cues)
       (not-any? (fn [[a b]]
                   (and (::transcript/voice? a)
                        (::transcript/voice? b)
                        (= (:speaker a) (:speaker b))))
                 (partition 2 1 cues))))

(defn voices-diagnostics
  "The diagnostic for `n` cues that took the speaker of a voice span before
  them."
  [n]
  (when (pos? n)
    [(diagnostic :transcript/voices-carried :info
                 (str n " cues have no voice span of their own; the file "
                      "marks only where the speaker changes, so each takes "
                      "the speaker before it")
                 :count n)]))

(defn dropped-diagnostics
  "The diagnostic for `n` cues whose timings don't parse."
  [n]
  (when (pos? n)
    [(diagnostic :transcript/cues-dropped :warning
                 (str n " cues have timings that don't parse, so they were "
                      "left out, as a browser leaves them out")
                 :count n)]))

;; WebVTT, section 4.2.2: a voice annotation is one line
(defn cue-nodes
  "The cue text of the `segment` as Hiccup: its timed runs as inline
  timestamps, or else its body, in a voice span of its speaker."
  [{:keys [speaker body runs] :as segment}]
  (let [content (if (seq runs)
                  (into [(:text (first runs))]
                        (mapcat (fn [{:keys [start-time text]}]
                                  [[:timestamp {:time start-time}] text]))
                        (rest runs))
                  [(cue-body body)])]
    (if speaker
      [(into [:v {:voice (str/join " " (str/split (str/trim speaker) #"\s+"))}]
             content)]
      content)))

(defn- tidy
  "The HTML text `s` with its layout whitespace collapsed, and its blank
  lines kept as paragraph breaks."
  [s]
  (->> (paragraphs s)
       (map #(str/trim (str/replace % #"\s+" " ")))
       (remove str/blank?)
       (str/join "\n\n")))

(def html-token-pattern
  "A cite, time or p element and its content.

  The content ends at the end tag, or for a p, whose end tag is optional
  in HTML, at the next p or the end of the text. So an unclosed p is read,
  and the pattern doesn't search to the end of the text for an end tag
  once for every p."
  #"(?is)<(cite|time|p)(?:\s[^>]*)?>(.*?)(?:</\1>|(?=<p\b)|$)")

(defn html-tokens
  "The tag and the text of each cite, time and p element in `s`, in order,
  as pairs."
  [s]
  (for [[_ tag body] (re-seq html-token-pattern s)]
    [(str/lower-case tag) (tidy (entities/decode-entities (strip-tags body)))]))

(defn html-segments
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

(defn turn
  "The segments `run` by one speaker as one turn: the first segment, with
  the bodies of all joined by spaces and the last end the others have."
  [[segment & more :as run]]
  (let [end-time (some :end-time (reverse more))]
    (cond-> (assoc segment :body (str/join " " (map :body run)))
      end-time (assoc :end-time end-time))))

(defn pieces
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

(defn start-of-match
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

(defn sentence-end?
  "Whether the body of `segment` ends a sentence, also inside quotes or
  brackets."
  [segment]
  (re-find #"[.!?…。！？][\"'”’)\]]*$" (str/trimr (:body segment))))

(defn paragraph-pieces
  "The segments of `run` in groups in order, each ending at the end of a
  sentence once it's `span` seconds long, or at twice that. A segment
  without a start time is a group of its own."
  [run span]
  (let [ends? (fn [piece segment]
                (let [from (:start-time (first piece))
                      to   (:start-time segment)]
                  (or (not (and (number? from) (number? to)))
                      (<= (* 2 span) (- to from))
                      (and (<= span (- to from))
                           (sentence-end? (peek piece))))))]
    (reduce (fn [acc segment]
              (let [piece (peek acc)]
                (if (and piece (not (ends? piece segment)))
                  (conj (pop acc) (conj piece segment))
                  (conj acc [segment]))))
            []
            run)))

(defn paragraph
  "The segments `piece` as one paragraph: their turn, with its text on one
  line. The runs are those of each segment, or the segment's body as one
  run when it has none."
  [piece]
  (let [runs (reduce (fn [acc {:keys [start-time body runs]}]
                       (into (cond-> acc
                               (seq acc) (update-in [(dec (count acc)) :text]
                                                    str " "))
                             (or runs [{:start-time start-time :text body}])))
                     []
                     piece)
        line #(str/replace % #"\s+" " ")]
    (cond-> (update (turn piece) :body (comp str/trim line))
      (next runs) (assoc :runs (mapv #(update % :text line) runs)))))
