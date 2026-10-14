(ns dk.simongray.podcast.format.media.id3
  "The ID3v2 tags at the start of an MP3 or AAC file, and what they say
  about the file: its tags, its duration, its artwork and its chapters.

  The tags come through a view of the file, and id3-clj reads them from
  their bytes, as dk.simongray.id3 describes. Its frames are true to the
  ID3 specs, and this namespace gives what they say in the shapes of
  podcast-clj, e.g. the chapters in seconds. The quirks of the reading
  and its diagnostics are those of id3-clj."
  (:require [clojure.string :as str]
            [dk.simongray.id3 :as id3]
            [dk.simongray.id3.chapters :as id3.chapters]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn ^:no-doc skip
  "Where the ID3v2 tags in a row from `p` in `view` end, or `p` when no
  tag begins there. It needs each whole tag with the ten bytes after it,
  as a reader of the tags would, so that they come at once."
  [view p]
  (let [total (or (:total view) ##Inf)]
    (loop [p p]
      (if-let [{:keys [end]} (when (<= (+ p 10) total)
                               (view/need view p (+ p 10))
                               (id3/header (view/slice view p (+ p 10))))]
        (do (view/need view p (+ p end 10))
            (recur (+ p end)))
        p))))

(defn ^:no-doc frame
  "The first of `frames` with one of the `ids`."
  [frames & ids]
  (some #(when (some #{(:id %)} ids) %) frames))

;; ID3v2.2 (id3v2-00), section 4.15: the image format of a PIC frame
(def ^:no-doc image-formats
  "The MIME type of each image format of a PIC frame of ID3v2.2."
  {"JPG" "image/jpeg"
   "PNG" "image/png"})

(defn- image-type
  "The MIME type of the picture of the APIC or PIC frame `picture`, in
  lower case: the one it names, or else that of its bytes."
  [{:keys [mime-type image-format data] :as picture}]
  (or (some-> mime-type str/trim str/lower-case not-empty)
      (some-> image-format str/upper-case image-formats)
      (image/sniff data)))

(defn artwork
  "The picture in `frames` as a map of :type, :data and :description, or
  nil when they hold none. The front cover comes before each other
  picture."
  [frames]
  (let [pictures (filter #(and (#{"APIC" "PIC"} (:id %)) (:data %)) frames)
        front    (first (filter #(= 3 (:picture-type %)) pictures))]
    (when-let [picture (or front (first pictures))]
      (let [type (image-type picture)]
        (cond-> (select-keys picture [:data :description])
          type (assoc :type type))))))

;; ID3v2.4 native frames, section 4.2, with the TYER of ID3v2.3, the
;; three-letter ids of ID3v2.2, and the TDES of Apple Podcasts
(defn named
  "The tags of `frames` as one map of :title, :artist, :album, :date,
  :genre and :description, each absent when the frames don't have it. The
  description is that of a podcast's episode, or else the comment."
  [frames]
  (let [text    #(some-> (apply frame frames %&) :text str/trim not-empty)
        ;; iTunes writes comments such as iTunNORM with a description
        comment (some #(when (and (#{"COMM" "COM"} (:id %))
                                  (str/blank? (:description %)))
                         (some-> (:text %) str/trim not-empty))
                      frames)
        named   {:title       (text "TIT2" "TT2")
                 :artist      (text "TPE1" "TP1")
                 :album       (text "TALB" "TAL")
                 :date        (text "TDRC" "TYER" "TYE")
                 :genre       (text "TCON" "TCO")
                 :description (or (text "TDES") comment)}]
    (into {} (filter val) named)))

(defn duration
  "The duration of the audio in seconds, from the TLEN frame in `frames`,
  or nil when no TLEN frame gives a number. The frame gives the length in
  milliseconds."
  [frames]
  (when-let [s (:text (frame frames "TLEN" "TLE"))]
    (when (re-matches #"\s*\d{1,12}\s*" s)
      (/ (parse-long (str/trim s)) 1000.0))))

(defn- chapter-of
  "The chapter of the CHAP frame `chap`, which has :toc false when the
  element ids that the tables of contents list, `listed`, lack it."
  [listed {:keys [element-id start-time end-time frames] :as chap}]
  (let [title   (:text (frame frames "TIT2"))
        url     (some #(when (re-matches #"W[A-Z0-9]{3}" (:id %))
                         (some-> (:url %) str/trim not-empty))
                      frames)
        picture (artwork frames)]
    (cond-> {:start-time (/ start-time 1000.0)}
      end-time (assoc :end-time (/ end-time 1000.0))
      title    (assoc :title title)
      url      (assoc :url url)
      picture  (assoc :artwork (select-keys picture [:type :data]))
      (and listed (not (listed element-id))) (assoc :toc false))))

(defn chapters
  "The chapters in `frames`, from their CHAP and CTOC frames, in the shape
  of dk.simongray.podcast.format.chapters.

  Each chapter is a map with :start-time and :end-time in seconds. It has
  the :title, the :url and the picture of the frames inside its frame, the
  picture as :artwork, a map of :type and :data. The top-level table of
  contents gives the order. A chapter that the table doesn't list comes
  after the others, by its start, with :toc false."
  [frames]
  (let [listed (some-> (id3.chapters/listed frames) set)]
    (mapv #(chapter-of listed %) (id3.chapters/ordered frames))))

(defn ^:no-doc read-front
  "Read the ID3v2 tags at the start of the file in `view` with `opts`, as
  dk.simongray.id3/read-all takes them, and what their frames say about
  the file.

  It's a map of the tags as :id3, where they :end, the :tags that named
  gives, and the :duration of a TLEN frame, the :chapters and the
  :artwork, each absent when the frames don't have it. Its report is that
  of the tags."
  [view opts]
  (let [tags    (id3/read-all (view/slice view 0 (skip view 0)) opts)
        frames  (mapcat :frames tags)
        seconds (duration frames)
        chs     (chapters frames)
        art     (artwork frames)]
    (view/reported
     (cond-> {:id3  tags
              :tags (named frames)
              :end  (or (:end (peek tags)) 0)}
       seconds   (assoc :duration seconds)
       (seq chs) (assoc :chapters chs)
       art       (assoc :artwork art))
     (mapcat id3/report tags))))

(comment
  ;; a tag of ID3v2.4 with a title, TIT2, and a length in milliseconds, TLEN
  (def sample
    (let [header [0x49 0x44 0x33 4 0 0 0 0 0 28]
          tit2   [0x54 0x49 0x54 0x32 0 0 0 3 0 0 3 0x48 0x69]
          tlen   [0x54 0x4C 0x45 0x4E 0 0 0 5 0 0 0 0x31 0x35 0x30 0x30]]
      (view/whole (bytes/of (concat header tit2 tlen)))))

  (skip sample 0)
  (read-front sample {})
  (view/report (read-front sample {}))
  #_.)
