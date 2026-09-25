(ns dk.simongray.podcast.chapters
  "Chapters in the two forms podcasts carry them: the JSON Chapters file that
  `podcast:chapters` links to, and Podlove Simple Chapters embedded in the
  feed under `psc:chapters`. Both read into one shape and write back out.

  A chapters document is a map:

      {:version \"1.2.0\"
       :title \"Episode 7\" :author \"…\" :podcast-name \"…\"      ; optional
       :chapters [{:start-time 94 :title \"Donation Segment\"
                   :img \"https://…\" :url \"https://…\" :toc false
                   :end-time 120 :location {:name \"…\" :geo \"geo:…\"}}]}

  Times are seconds. Chapters are kept in the order given; `sorted` orders
  them by start time and `at` finds the one playing at a position."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.time :as time]))

(def version
  "The JSON Chapters format version this namespace writes."
  "1.2.0")

(def document-keys
  "Model key by JSON property, for the document."
  {"version" :version "author" :author "title" :title "podcastName" :podcast-name
   "description" :description "fileName" :file-name "waypoints" :waypoints
   "chapters" :chapters})

(def chapter-keys
  "Model key by JSON property, for a chapter."
  {"startTime" :start-time "endTime" :end-time "title" :title "img" :img
   "url" :url "toc" :toc "location" :location})

(def location-keys
  {"name" :name "geo" :geo "osm" :osm})

(defn- rename
  "Rename the string keys of `m` by `table`, keeping the ones it lacks."
  [table m]
  (into {} (map (fn [[k v]] [(get table k k) v])) m))

(defn- unname
  "The inverse of `rename`: model keys back to JSON properties."
  [table m]
  (let [inverse (into {} (map (fn [[k v]] [v k])) table)]
    (into {} (for [[k v] m :when (some? v)]
               [(get inverse k (if (keyword? k) (name k) k)) v]))))

;;;; JSON Chapters

(defn- read-chapter
  [m]
  (let [chapter (rename chapter-keys m)]
    (cond-> chapter
      (:location chapter) (update :location #(rename location-keys %)))))

(defn parse-json
  "Read the JSON Chapters document `s` into a chapters map."
  [s]
  (let [doc (rename document-keys (json/read-str s))]
    (update doc :chapters #(mapv read-chapter %))))

(defn- write-chapter
  [chapter]
  (let [m (cond-> chapter
            (:location chapter) (update :location #(unname location-keys %)))]
    (unname chapter-keys m)))

(defn emit-json
  "Write the chapters map `doc` as a JSON Chapters document, indented when
  `pretty?`; the version defaults to `version`."
  ([doc]
   (emit-json doc true))
  ([doc pretty?]
   (json/write-str (unname document-keys
                           (-> (merge {:version version} doc)
                               (update :chapters #(mapv write-chapter %))))
                   pretty?)))

;;;; Podlove Simple Chapters

(defn from-psc
  "The chapters map for the `psc:chapters` value of a feed item, i.e. the
  `:psc/chapters` map with `:chapters` of `:start`, `:title`, `:href` and
  `:image`."
  [psc]
  {:version  version
   :chapters (vec (for [{:keys [start title href image]} (:chapters psc)
                        :let [seconds (time/parse-clock (str start))]
                        :when seconds]
                    (cond-> {:start-time seconds}
                      title (assoc :title title)
                      href  (assoc :url href)
                      image (assoc :img image))))})

(defn to-psc
  "The `psc:chapters` Hiccup element for the chapters map `doc`, for feeds
  that embed chapters as well as linking to them."
  [doc]
  (into [:psc/chapters {:version "1.2"}]
        (for [{:keys [start-time title url img]} (:chapters doc)]
          [:psc/chapter (cond-> {:start (time/format-timestamp start-time)}
                          title (assoc :title title)
                          url   (assoc :href url)
                          img   (assoc :image img))])))

;;;; Working with chapters

(defn sorted
  "The chapters of `doc` in order of start time."
  [doc]
  (sort-by :start-time (:chapters doc)))

(defn visible
  "The chapters of `doc` meant for the table of contents: those without
  `:toc false`, in order."
  [doc]
  (remove #(false? (:toc %)) (sorted doc)))

(defn with-end-times
  "The chapters of `doc` in order, each given the start of the next as its
  `:end-time` when it has none."
  [doc]
  (let [chapters (vec (sorted doc))]
    (map-indexed (fn [i chapter]
                   (if-let [next-start (some-> (get chapters (inc i)) :start-time)]
                     (update chapter :end-time #(or % next-start))
                     chapter))
                 chapters)))

(defn at
  "The chapter of `doc` that is playing `seconds` into the media, or nil
  before the first chapter starts."
  [doc seconds]
  (last (take-while #(<= (:start-time %) seconds) (sorted doc))))

(comment
  (parse-json (slurp "test/resources/chapters/example-complex.json"))
  (println (emit-json *1))
  (at (parse-json (slurp "test/resources/chapters/example.json")) 300)
  (str/join "\n" (map :title (visible (parse-json (slurp "test/resources/chapters/example-complex.json")))))
  #_.)
