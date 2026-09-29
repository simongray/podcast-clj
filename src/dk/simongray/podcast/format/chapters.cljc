(ns dk.simongray.podcast.format.chapters
  "Chapters in the two forms podcasts carry them: the JSON Chapters file that
  `podcast:chapters` links to, and Podlove Simple Chapters embedded in the
  feed under `psc:chapters`. Both read into one shape and write back out.

  A chapters document is a map:

      {:version \"1.2.0\"
       :title \"Episode 7\" :author \"…\" :podcast-name \"…\"  ; optional
       :chapters [{:start-time 94 :title \"Donation Segment\"
                   :img \"https://…\" :url \"https://…\" :toc false
                   :end-time 120 :location {:name \"…\" :geo \"geo:…\"}}]}

  Times are seconds. Chapters are kept in the order given, keys the format
  does not define included; `sorted` orders them by start time and `at`
  finds the one playing at a position. Reading never throws on content; what
  it had to forgive is in the report, metadata under `:podcast/report` read
  with `report`."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

(def version
  "The JSON Chapters format version this namespace writes."
  "1.2.0")

(def ^:no-doc document-keys
  "Model key by JSON property, for the document."
  {"version" :version "author" :author "title" :title "podcastName" :podcast-name
   "description" :description "fileName" :file-name "waypoints" :waypoints
   "chapters" :chapters})

(def ^:no-doc chapter-keys
  "Model key by JSON property, for a chapter."
  {"startTime" :start-time "endTime" :end-time "title" :title "img" :img
   "url" :url "toc" :toc "location" :location})

(def ^:no-doc location-keys
  "The keys of a chapter's location object, JSON name to model key."
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

(defn report
  "The diagnostics recorded while reading `doc`."
  [doc]
  (:podcast/report (meta doc)))

(defn- diagnostic
  [id level message & {:as extra}]
  (merge {:id id :level level :message message} extra))

(def ^:no-doc text-paths
  "Where a chapter holds text, as paths into its JSON object."
  [["title"] ["img"] ["url"] ["location" "name"] ["location" "geo"]])

(defn- diagnostics
  "The diagnostics for the read document `doc`, given the `raw` chapter maps it
  was read from."
  [doc raw]
  (let [chapters (:chapters doc)
        strings  (count (filter #(or (string? (get % "startTime")) (string? (get % "endTime"))) raw))
        not-text (count (for [m raw, path text-paths
                              :let [v (get-in m path)]
                              :when (and (some? v) (nil? (json/text v)))]
                          path))
        missing  (count (remove (comp number? :start-time) chapters))
        starts   (filter number? (map :start-time chapters))
        unknown  (into (sorted-set) (comp (mapcat keys) (filter string?))
                       (cons (dissoc doc :chapters) chapters))
        v        (:version doc)]
    (cond-> []
      (pos? strings)
      (conj (diagnostic :chapters/string-times :warning
                     (str strings " chapters give their times as strings rather than numbers")
                     :count strings))

      (pos? not-text)
      (conj (diagnostic :chapters/not-text :warning
                     (str not-text " titles, links or place names are not text and were left out")
                     :count not-text))

      (pos? missing)
      (conj (diagnostic :chapters/missing-start-time :warning
                     (str missing " chapters have no start time and cannot be placed")
                     :count missing))

      (not= starts (sort starts))
      (conj (diagnostic :chapters/unsorted :info
                     "the chapters are not in order of start time, which the format assumes"))

      (seq unknown)
      (conj (diagnostic :chapters/unknown-keys :info
                     (str "keys the format does not define: " (str/join ", " unknown))
                     :keys (vec unknown)))

      (and (some? v) (not (str/starts-with? (str v) "1.2")))
      (conj (diagnostic :chapters/old-version :info
                     (str "written against version " (pr-str v) " of the format; "
                          "the specification is at " version)
                     :version v)))))

(defn- read-text
  "The raw chapter `m` with the value at `path` as text, as `json/text` reads
  it, or without it when it is no text."
  [m path]
  (let [v (get-in m path)]
    (if (nil? v)
      m
      (if-some [t (json/text v)]
        (assoc-in m path t)
        (if (next path) (update-in m (pop path) dissoc (peek path)) (dissoc m (peek path)))))))

(defn- read-chapter
  [m]
  (let [chapter (rename chapter-keys (reduce read-text m text-paths))]
    (cond-> chapter
      (contains? chapter :start-time) (update :start-time time/seconds)
      (contains? chapter :end-time)   (update :end-time time/seconds)
      (map? (:location chapter))      (update :location #(rename location-keys %)))))

(defn parse-json
  "Read the JSON Chapters document `s` into a chapters map. A document that
  is not JSON, or not a JSON object, reads as no chapters, chapters that are
  not objects are left out, and so are titles, links and place names that
  are not text; the report says so. A number counts as text."
  [s]
  (let [raw (json/parse s)]
    (if (map? raw)
      (let [entries (get raw "chapters")
            objects (if (sequential? entries) (filterv map? entries) [])
            dropped (cond (sequential? entries) (- (count entries) (count objects))
                          (some? entries)       1
                          :else                 0)
            doc     (assoc (rename document-keys raw) :chapters (mapv read-chapter objects))]
        (with-meta doc
          {:podcast/report (cond-> (diagnostics doc objects)
                             (pos? dropped)
                             (conj (diagnostic :chapters/malformed :warning
                                               (str dropped " chapters are not JSON objects in a list and were left out")
                                               :count dropped)))}))
      (with-meta {:chapters []}
        {:podcast/report [(diagnostic :chapters/not-an-object :warning
                                      "the document is not a JSON object")]}))))

(defn- write-chapter
  [chapter]
  (let [m (cond-> chapter
            (map? (:location chapter)) (update :location #(unname location-keys %)))]
    (unname chapter-keys m)))

(defn emit-json
  "Write the chapters map `doc` as a JSON Chapters document, indented unless
  `opts` says `{:pretty? false}`; the version defaults to `version`."
  ([doc]
   (emit-json doc {}))
  ([doc {:keys [pretty?] :or {pretty? true}}]
   (json/write-str (unname document-keys
                           (-> (merge {:version version} doc)
                               (update :chapters #(mapv write-chapter %))))
                   {:pretty? pretty?})))

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
                         [(diagnostic :chapters/bad-timestamps :warning
                                   (str dropped " Podlove chapters have a start that is not a "
                                        "timestamp and were dropped")
                                   :count dropped)]
                         [])})))

(defn to-psc
  "The `psc:chapters` Hiccup element for the chapters map `doc`, for feeds
  that embed chapters as well as linking to them; chapters without a start
  time are left out, as Podlove requires one."
  [doc]
  (into [:psc/chapters {:version "1.2"}]
        (for [{:keys [start-time title url img]} (:chapters doc)
              :when (time/format-timestamp start-time)]
          [:psc/chapter (cond-> {:start (time/format-timestamp start-time)}
                          title (assoc :title title)
                          url   (assoc :href url)
                          img   (assoc :image img))])))

(def ^:no-doc timestamp-first
  "A line of show notes that starts with a timestamp: an optional list
  marker, the time, perhaps in brackets, a separator, then the title."
  #"^[ \t]*(?:[-*•–—]\s*|\d{1,2}[.)]\s+)?[\[(]?\s*((?:\d{1,2}:)?\d{1,2}:\d{2})\s*[\])]?\s*(?:[-–—:|.,]\s*)?(\S.*?)\s*$")

(def ^:no-doc timestamp-last
  "A line of show notes that ends with a timestamp: the title, a separator,
  then the time, perhaps in brackets."
  #"^[ \t]*(?:[-*•–—]\s*)?(\S.*?)\s*(?:[-–—:|,]\s*)?[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?[ \t]*$")

(defn- line-chapter
  "The chapter a line of show notes lists, by `pattern`, whose groups are
  the timestamp and the title in the order `time-first?` says; nil when the
  line lists none."
  [pattern time-first? line]
  (when-let [[_ a b] (re-matches pattern line)]
    (let [[timestamp title] (if time-first? [a b] [b a])]
      (when-let [seconds (time/parse-clock timestamp)]
        {:start-time seconds :title title}))))

(defn from-text
  "The chapters listed as timestamps in the show notes `s`, HTML or plain
  text, as a chapters map, or nil when they list none. A line per chapter,
  \"12:34 The topic\" in any of the ways notes write it, with the time first
  or, when no line starts with one, last, as in \"The topic (12:34)\"; two
  such lines make a list when the time comes first, three in rising order
  when it comes last. The report says the chapters were inferred."
  [s]
  ;; notes with no digit, colon and two digits in a row, a colon reference
  ;; counted, hold no time, and most notes have none, so they skip the parse;
  ;; the patterns' optional parts meet around runs of spaces, over which they
  ;; would backtrack quadratically, so each run is one space first
  (when (and s (re-find #"\d(?::|&#0*58;|&#[xX]0*3[aA];|&colon;)\d\d" s))
    (let [lines    (map #(str/replace % #"\s+" " ") (str/split-lines (html/text (html/hiccup s))))
          leading  (keep #(line-chapter timestamp-first true %) lines)
          trailing (keep #(line-chapter timestamp-last false %) lines)
          chapters (cond
                     (<= 2 (count leading))
                     leading

                     (and (<= 3 (count trailing)) (apply < (map :start-time trailing)))
                     trailing)]
      (when chapters
        (with-meta {:version version :chapters (vec chapters)}
          {:podcast/report [(diagnostic :chapters/inferred :info
                                        (str (count chapters) " chapters were read from timestamps in the show notes")
                                        :count (count chapters))]})))))

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

(def restart
  "How many seconds of a chapter a listener can hear before a jump back
  goes to the start of that chapter. Earlier in the chapter, the jump
  goes to the chapter before it, as in most players."
  3)

(defn jump
  "Where a jump from `seconds` into the media goes, in seconds, by the
  table of contents of `doc`. `direction` is :next or :previous.

  A jump to the next chapter goes to the first chapter that starts after
  `seconds`. It is nil after the start of the last chapter. A jump to the
  previous chapter goes to the start of the chapter that plays, or to the
  chapter before it in the first `restart` seconds of a chapter. Before
  the first chapter, it goes to 0.

  The chapters with `:toc false` are not in the table of contents, as
  the JSON Chapters format of the podcast namespace has it, so no jump
  goes to them. No standard says where a jump back goes, so the seconds
  are a choice: `opts` can give `:restart`."
  ([doc seconds direction]
   (jump doc seconds direction {}))
  ([doc seconds direction opts]
   (let [back   (- seconds (:restart opts restart))
         starts (map :start-time (visible doc))]
     (case direction
       :next     (first (drop-while #(<= % seconds) starts))
       :previous (or (last (take-while #(<= % back) starts)) 0)))))

#?(:clj
   (comment
     (parse-json (slurp "test/resources/chapters/example-complex.json"))
     (report *1)
     (println (emit-json *1))
     (at (parse-json (slurp "test/resources/chapters/example.json")) 300)
     (jump (parse-json (slurp "test/resources/chapters/example.json")) 300 :next)
     (jump (parse-json (slurp "test/resources/chapters/example.json")) 300 :previous)
     #_.))
