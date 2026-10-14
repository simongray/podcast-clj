(ns dk.simongray.podcast.feed.local
  "A show of local files that no feed serves, e.g. an audiobook, a folder
  of recordings or a podcast that someone sent, as a feed that
  app.library/subscribe-local takes.

  The feed is built from what each file says about itself, as
  format.media/read! gives it, in the shape that feed.rss/parse gives. Its
  episodes go in the order of their file names, as those of a serial show
  do. The guid of the show comes from its title and author, and that of an
  episode from its file name, so two devices with the same files make the
  same show, and the profile syncs its progress between them."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.util.time :as time]))

(defn- natural-key
  "The parts of the name `s`, so that names compare as a person reads
  them: a run of digits by its value, so that 2 comes before 10, and the
  rest by its letters in lower case."
  [s]
  (mapv (fn [part]
          (if-let [digits (re-matches #"\d+" part)]
            (let [n (str/replace digits #"^0+(?=\d)" "")]
              [0 (count n) n])
            [1 0 (str/lower-case part)]))
        (re-seq #"\d+|\D+" s)))

(defn- natural-compare
  "Compare the names `a` and `b` as a person reads them."
  [a b]
  (let [ka (natural-key a)
        kb (natural-key b)]
    (or (some #(let [c (compare (ka %) (kb %))]
                 (when-not (zero? c) c))
              (range (min (count ka) (count kb))))
        (compare (count ka) (count kb)))))

(defn- most-named
  "The text that the most of `xs` give, the first of them on a tie, or nil
  when none gives one."
  [xs]
  (let [xs (remove str/blank? xs)
        n  (frequencies xs)]
    (when (seq xs)
      (reduce (fn [best x] (if (> (n x) (n best)) x best)) xs))))

(defn- base-name
  "The `name` of a file without its folders and its extension."
  [name]
  (-> name
      (str/replace #"^.*[/\\]" "")
      (str/replace #"(.)\.[^.]*$" "$1")))

;; ID3v2.4, section 4.2.5, and the iTunes day item: a timestamp that can
;; stop at the year or the month
(defn- tag-date
  "The instant of the date `s` of a tag, which can be a year or a month
  alone, or nil."
  [s]
  (or (time/parse-date s)
      (when-let [[_ y m] (re-matches #"(\d{4})(?:-(\d{2}))?" (str/trim s))]
        (time/parse-iso8601 (str y "-" (or m "01") "-01")))))

(defn- media-type
  "The media type of a file that `media`, a reading of format.media,
  describes, or nil when the reading doesn't tell it."
  [{:keys [format width] :as media}]
  (case format
    :mp3 "audio/mpeg"
    :mp4 (if width "video/mp4" "audio/mp4")
    :ogg "audio/ogg"
    :aac "audio/aac"
    nil))

(defn- item
  "The item of the local `file` that comes `n`th in its show."
  [n {:keys [url name media type modified] :as file}]
  (let [{:keys [tags duration bytes]} media
        date                          (or (some-> (:date tags) tag-date)
                                          modified)
        type                          (or type (media-type media))
        seconds                       (when (number? duration)
                                        (Math/round (double duration)))]
    (cond-> {:rss/guid       {:content name :is-perma-link false}
             :rss/title      (or (not-empty (:title tags)) (base-name name))
             :itunes/episode n
             :rss/enclosure  (cond-> {:url url}
                               type  (assoc :type type)
                               bytes (assoc :length bytes))}
      seconds             (assoc :itunes/duration seconds)
      (inst? date)        (assoc :rss/pub-date date)
      (:description tags) (assoc :rss/description (:description tags)))))

(defn feed
  "The feed of the local `files`, made with the `opts` below, in the shape
  that feed.rss/parse gives.

  Each file is a map of these keys:

  - :url, what the app plays the file by, e.g. a file URL or a path
  - :name, its name, or its path in the folder of the show, which orders
    the episodes and is the guid of its episode
  - :media, what format.media/read! gave for it
  - :type, its media type, when the reading doesn't tell it
  - :modified, the instant it last changed, which dates an episode whose
    tags give no date

  The `opts` are these:

  - :title, the title of the show, by default the album that the most
    files name
  - :author, by default the artist that the most files name
  - :guid, the guid of the show, by default one that the podcast namespace
    derives from its title and author, as it does from a URL
  - :image, the URL of its artwork, by default the artwork of the first
    file that has one, as a data URL

  It throws when neither the `opts` nor the tags give a title."
  ([files]
   (feed files {}))
  ([files {:keys [title author guid image] :as opts}]
   {:pre [(map? opts)]}
   (let [files  (sort-by :name natural-compare files)
         tags   (map (comp :tags :media) files)
         title  (or title (most-named (map :album tags)))
         author (or author (most-named (map :artist tags)))
         art    (some (comp :artwork :media) files)
         image  (or image
                    (when art (image/data-url (:type art) (:data art))))]
     (when (str/blank? title)
       (throw (ex-info (str "A show of local files needs a :title, since no "
                            "file names an album")
                       {:files (mapv :name files)})))
     (cond-> {:podcast/guid (or guid
                                (guid/uuid-v5 guid/podcast-namespace
                                              (str "local:" author "/" title)))
              :rss/title    title
              :itunes/type  "serial"
              :rss/items    (vec (map-indexed #(item (inc %1) %2) files))}
       author (assoc :itunes/author author)
       image  (assoc :itunes/image {:href image})))))

(comment
  (def dune
    {:album "Dune" :artist "Frank Herbert"})

  ;; two parts of an audiobook, in the order of their names
  (feed [{:url   "file:///Users/ann/Books/Dune/02.mp3"
          :name  "02.mp3"
          :media {:format :mp3 :duration 3600.5 :bytes 57600000
                  :tags   (assoc dune :title "Part two")}}
         {:url   "file:///Users/ann/Books/Dune/01.mp3"
          :name  "01.mp3"
          :media {:format :mp3 :duration 3540.2 :bytes 56640000
                  :tags   (assoc dune :title "Part one")}}])
  #_.)
