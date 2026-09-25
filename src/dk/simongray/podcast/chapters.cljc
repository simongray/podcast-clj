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

  Times are seconds. Chapters are kept in the order given, keys the format
  does not define included; `sorted` orders them by start time and `at`
  finds the one playing at a position. Reading never throws on content; what
  it had to forgive is in the report, metadata under `:podcast/report` read
  with `report`."
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

;;;; The report

(defn report
  "The findings recorded while reading `doc`."
  [doc]
  (:podcast/report (meta doc)))

(defn- finding
  [id level message & {:as extra}]
  (merge {:id id :level level :message message} extra))

(defn- findings
  "The findings for the read document `doc`, given the `raw` chapter maps it
  was read from."
  [doc raw]
  (let [chapters (:chapters doc)
        strings  (count (filter #(or (string? (get % "startTime")) (string? (get % "endTime"))) raw))
        missing  (count (remove (comp number? :start-time) chapters))
        starts   (filter number? (map :start-time chapters))
        unknown  (into (sorted-set) (comp (mapcat keys) (filter string?))
                       (cons (dissoc doc :chapters) chapters))
        v        (:version doc)]
    (cond-> []
      (pos? strings)
      (conj (finding :chapters/string-times :warning
                     (str strings " chapters give their times as strings rather than numbers")
                     :count strings))

      (pos? missing)
      (conj (finding :chapters/missing-start-time :warning
                     (str missing " chapters have no start time and cannot be placed")
                     :count missing))

      (not= starts (sort starts))
      (conj (finding :chapters/unsorted :info
                     "the chapters are not in order of start time, which the format assumes"))

      (seq unknown)
      (conj (finding :chapters/unknown-keys :info
                     (str "keys the format does not define: " (str/join ", " unknown))
                     :keys (vec unknown)))

      (not (str/starts-with? (str v) "1.2"))
      (conj (finding :chapters/old-version :info
                     (str "written against version " (pr-str v) " of the format; "
                          "the specification is at " version)
                     :version v)))))

;;;; JSON Chapters

(defn- read-chapter
  [m]
  (let [chapter (rename chapter-keys m)]
    (cond-> chapter
      (contains? chapter :start-time) (update :start-time time/seconds)
      (contains? chapter :end-time)   (update :end-time time/seconds)
      (:location chapter)             (update :location #(rename location-keys %)))))

(defn parse-json
  "Read the JSON Chapters document `s` into a chapters map."
  [s]
  (let [raw (json/read-str s)
        doc (-> (rename document-keys raw)
                (update :chapters #(mapv read-chapter %)))]
    (with-meta doc {:podcast/report (findings doc (get raw "chapters"))})))

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
  `:image`. Chapters whose start is not a timestamp are dropped and reported."
  [psc]
  (let [entries  (:chapters psc)
        chapters (vec (for [{:keys [start title href image]} entries
                            :let [seconds (time/parse-clock (str start))]
                            :when seconds]
                        (cond-> {:start-time seconds}
                          title (assoc :title title)
                          href  (assoc :url href)
                          image (assoc :img image))))
        dropped  (- (count entries) (count chapters))]
    (with-meta {:version version :chapters chapters}
      {:podcast/report (if (pos? dropped)
                         [(finding :chapters/bad-timestamps :warning
                                   (str dropped " Podlove chapters have a start that is not a "
                                        "timestamp and were dropped")
                                   :count dropped)]
                         [])})))

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
  "The chapters of `doc` that have a start time, in order of it."
  [doc]
  (sort-by :start-time (filter (comp number? :start-time) (:chapters doc))))

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
  (report *1)
  (println (emit-json *1))
  (at (parse-json (slurp "test/resources/chapters/example.json")) 300)
  #_.)
