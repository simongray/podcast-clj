(ns dk.simongray.podcast.transcript
  "Transcripts in the four formats the podcast namespace allows, read into one
  shape and written back: SRT, WebVTT, the JSON segments format and the
  constrained HTML form.

  A transcript is a map:

      {:format   :vtt                                  ; :srt :json :html
       :segments [{:start 0.0 :end 2.76 :speaker \"Sarah\" :body \"In today's…\"}]}

  Times are seconds; `:end` and `:speaker` are present when the source had
  them."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.quirks :as quirks]
            [dk.simongray.podcast.time :as time]))

(def formats
  "Transcript format by the mime type `podcast:transcript` gives it."
  {"text/vtt"             :vtt
   "application/x-subrip" :srt
   "application/srt"      :srt
   "text/srt"             :srt
   "application/json"     :json
   "text/html"            :html
   "text/plain"           :text})

(def timing-pattern
  "A cue timing line in SRT or WebVTT: start, arrow, end, optional settings."
  #"^\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}[.,]\d{1,3})\s*-->\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}[.,]\d{1,3})")

(defn- blocks
  "The blank-line separated blocks of `s`, each a vector of lines."
  [s]
  (->> (str/split-lines (str/replace s "\r\n" "\n"))
       (partition-by str/blank?)
       (remove #(str/blank? (first %)))
       (map vec)))

(defn- speaker-prefix
  "Split a leading \"Name: \" off `body`; returns `[speaker body]`."
  [body]
  (if-let [[_ speaker rest] (re-find #"(?s)^([A-Za-z][^:\n<>]{0,40}?):\s+(.*)$" body)]
    [speaker rest]
    [nil body]))

(defn- cue
  "The segment for a cue whose timing line is `timing` and text lines
  `lines`, or nil when the timing does not parse."
  [timing lines]
  (when-let [[_ from to] (re-find timing-pattern timing)]
    (let [[speaker body] (speaker-prefix (str/join "\n" lines))]
      (cond-> {:start (time/parse-clock from) :end (time/parse-clock to) :body body}
        speaker (assoc :speaker speaker)))))

;;;; SRT

(defn parse-srt
  "Read the SRT transcript `s`."
  [s]
  {:format   :srt
   :segments (vec (keep (fn [[first-line & more]]
                          (if (re-find timing-pattern first-line)
                            (cue first-line more)
                            (cue (or (first more) "") (rest more))))
                        (blocks s)))})

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
  "The segment for a WebVTT cue block, reading `<v Speaker>` voice spans."
  [lines]
  (let [[timing & payload] (if (re-find timing-pattern (first lines)) lines (rest lines))]
    (when (and timing (re-find timing-pattern timing))
      (let [text    (str/join "\n" payload)
            speaker (second (re-find #"<v(?:\.[^\s>]*)?\s+([^>]+)>" text))
            body    (str/trim (strip-tags text))]
        (cond-> {:start (time/parse-clock (second (re-find timing-pattern timing)))
                 :end   (time/parse-clock (nth (re-find timing-pattern timing) 2))
                 :body  (quirks/decode-entities body)}
          speaker (assoc :speaker speaker))))))

(defn parse-vtt
  "Read the WebVTT transcript `s`, skipping NOTE, STYLE and REGION blocks."
  [s]
  {:format   :vtt
   :segments (vec (keep (fn [block]
                          (when-not (re-find #"^(?:WEBVTT|NOTE|STYLE|REGION)" (first block))
                            (vtt-cue block)))
                        (blocks s)))})

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
  \"startTime\" \"endTime\" \"body\"}]}`."
  [s]
  (let [doc (json/read-str s)]
    {:format   :json
     :version  (get doc "version")
     :segments (vec (for [seg (get doc "segments")]
                      (cond-> {:start (get seg "startTime") :body (str (get seg "body"))}
                        (get seg "endTime") (assoc :end (get seg "endTime"))
                        (get seg "speaker") (assoc :speaker (get seg "speaker")))))}))

(defn emit-json
  "Write `transcript` in the JSON transcript format."
  ([transcript]
   (emit-json transcript true))
  ([transcript pretty?]
   (json/write-str
    {"version"  (or (:version transcript) "1.0.0")
     "segments" (for [{:keys [start end speaker body]} (:segments transcript)]
                  (cond-> {"startTime" start "body" body}
                    end     (assoc "endTime" end)
                    speaker (assoc "speaker" speaker)))}
    pretty?)))

;;;; HTML

(defn parse-html
  "Read the HTML transcript `s`, a sequence of `<cite>` speakers, `<time>`
  starts and `<p>` paragraphs."
  [s]
  (let [tokens (re-seq #"(?is)<(cite|time|p)[^>]*>(.*?)</\1>" s)]
    {:format   :html
     :segments (loop [tokens tokens speaker nil start nil acc []]
                 (if-let [[_ tag body] (first tokens)]
                   (let [text (quirks/decode-entities (str/trim (strip-tags body)))]
                     (case (str/lower-case tag)
                       "cite" (recur (rest tokens) (str/replace text #":\s*$" "") start acc)
                       "time" (recur (rest tokens) speaker (time/parse-clock text) acc)
                       "p"    (recur (rest tokens) speaker start
                                     (conj acc (cond-> {:body text}
                                                 start   (assoc :start start)
                                                 speaker (assoc :speaker speaker))))))
                   acc))}))

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

(defn parse
  "Read the transcript `s`, in the format named by `type` (a mime type from
  `podcast:transcript` or a format keyword) or, without one, by inspection.
  Plain text becomes a single segment."
  ([s]
   (parse s (sniff s)))
  ([s type]
   (case (get formats type type)
     :vtt  (parse-vtt s)
     :srt  (parse-srt s)
     :json (parse-json s)
     :html (parse-html s)
     {:format :text :segments [{:body (str/trim s)}]})))

;;;; Working with transcripts

(defn text
  "The plain text of `transcript`: the bodies of its segments, one per line,
  speakers as prefixes where they change."
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

(comment
  (parse (slurp "test/resources/transcripts/example.vtt"))
  (parse (slurp "test/resources/transcripts/example.srt") "application/x-subrip")
  (parse (slurp "test/resources/transcripts/example.json"))
  (parse (slurp "test/resources/transcripts/example.html"))
  (println (emit-vtt (parse (slurp "test/resources/transcripts/example.srt"))))
  #_.)
