(ns dk.simongray.podcast.format.chapters
  "Chapters in the two forms podcasts carry them: the JSON Chapters file
  that podcast:chapters links to, and Podlove Simple Chapters embedded in
  the feed as psc:chapters. Both read into one shape and write back out.

  A chapters document is a map:

      {:version \"1.2.0\"
       :title \"Episode 7\" :author \"…\" :podcast-name \"…\"  ; optional
       :chapters [{:start-time 94 :title \"Donation Segment\"
                   :img \"https://…\" :url \"https://…\" :toc false
                   :end-time 120 :location {:name \"…\" :geo \"geo:…\"}}]}

  Times are seconds. Chapters keep the order they're given in, and the keys
  that the format doesn't define are kept too. Reading never throws on
  content, and what it had to forgive is in the report, in the metadata."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

(def version
  "The JSON Chapters format version this namespace writes: version 1.2 of
  15 April 2021."
  "1.2.0")

(def ^:no-doc document-keys
  "The model key of each JSON property of a chapters document."
  {"version"     :version
   "author"      :author
   "title"       :title
   "podcastName" :podcast-name
   "description" :description
   "fileName"    :file-name
   "waypoints"   :waypoints
   "chapters"    :chapters})

(def ^:no-doc chapter-keys
  "The model key of each JSON property of a chapter."
  {"startTime" :start-time
   "endTime"   :end-time
   "title"     :title
   "img"       :img
   "url"       :url
   "toc"       :toc
   "location"  :location})

(def ^:no-doc location-keys
  "The model key of each JSON property of a chapter's location."
  {"name" :name "geo" :geo "osm" :osm})

(defn- rename
  "The map `m` with its keys renamed by `table`, or kept where `table` has
  none."
  [table m]
  (into {} (map (fn [[k v]] [(get table k k) v])) m))

(defn- unname
  "The map `m` with its keys named back as JSON properties by `table`, and
  its nil values left out. A keyword that `table` lacks becomes its name."
  [table m]
  (let [inverse (into {} (map (fn [[k v]] [v k])) table)]
    (into {} (for [[k v] m :when (some? v)]
               [(get inverse k (if (keyword? k) (name k) k)) v]))))

(defn report
  "The diagnostics recorded while reading `doc`."
  [doc]
  (:podcast/report (meta doc)))

(defn- diagnostic
  [id level message & {:as details}]
  (merge {:id id :level level :message message} details))

(def ^:no-doc text-paths
  "Where a chapter holds text, as paths into its JSON object."
  [["title"] ["img"] ["url"] ["location" "name"] ["location" "geo"]])

(defn- diagnostics
  "The diagnostics for the read document `doc`, given the `raw` chapter maps it
  was read from."
  [doc raw]
  (let [chapters (:chapters doc)
        strings  (count (filter #(or (string? (get % "startTime"))
                                     (string? (get % "endTime")))
                                raw))
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
                        (str strings " chapters give their times as strings"
                             " rather than numbers")
                        :count strings))

      (pos? not-text)
      (conj (diagnostic :chapters/not-text :warning
                        (str not-text " titles, links or place names are not"
                             " text and were left out")
                        :count not-text))

      (pos? missing)
      (conj (diagnostic :chapters/missing-start-time :warning
                        (str missing " chapters have no start time and cannot"
                             " be placed")
                        :count missing))

      (not= starts (sort starts))
      (conj (diagnostic :chapters/unsorted :info
                        (str "the chapters are not in order of start time,"
                             " which the format assumes")))

      (seq unknown)
      (conj (diagnostic :chapters/unknown-keys :info
                        (str "keys the format does not define: "
                             (str/join ", " unknown))
                        :keys (vec unknown)))

      (and (some? v)
           (not (str/starts-with? (str v) "1.2")))
      (conj (diagnostic :chapters/old-version :info
                        (str "written against version " (pr-str v)
                             " of the format; the specification is at "
                             version)
                        :version v)))))

(defn- read-text
  "The raw chapter `m` with the value at `path` read as text, or without
  that value when it isn't text."
  [m path]
  (let [v (get-in m path)]
    (if (nil? v)
      m
      (if-some [t (json/text v)]
        (assoc-in m path t)
        (if (next path)
          (update-in m (pop path) dissoc (peek path))
          (dissoc m (peek path)))))))

(defn- read-chapter
  [m]
  (let [chapter (rename chapter-keys (reduce read-text m text-paths))]
    (cond-> chapter
      (contains? chapter :start-time)
      (update :start-time time/seconds)

      (contains? chapter :end-time)
      (update :end-time time/seconds)

      (map? (:location chapter))
      (update :location #(rename location-keys %)))))

(defn parse-json
  "The chapters map of the JSON Chapters document `s`.

  Bad content doesn't throw. The report says what was left out instead:

  - a document that isn't a JSON object reads as no chapters
  - a chapter that isn't an object is left out
  - a title, link or place name that isn't text is left out, but a number
    counts as text"
  [s]
  (let [raw (json/parse s)]
    (if (map? raw)
      (let [entries (get raw "chapters")
            objects (if (sequential? entries) (filterv map? entries) [])
            dropped (cond
                      (sequential? entries) (- (count entries) (count objects))
                      (some? entries)       1
                      :else                 0)
            doc     (assoc (rename document-keys raw)
                           :chapters (mapv read-chapter objects))
            report  (cond-> (diagnostics doc objects)
                      (pos? dropped)
                      (conj (diagnostic :chapters/malformed :warning
                                        (str dropped " chapters are not JSON"
                                             " objects in a list and were"
                                             " left out")
                                        :count dropped)))]
        (with-meta doc {:podcast/report report}))
      (with-meta {:chapters []}
        {:podcast/report [(diagnostic :chapters/not-an-object :warning
                                      "the document is not a JSON object")]}))))

(defn- write-chapter
  [chapter]
  (let [m (cond-> chapter
            (map? (:location chapter))
            (update :location #(unname location-keys %)))]
    (unname chapter-keys m)))

(defn emit-json
  "The chapters map `doc` as a JSON Chapters document, indented unless
  `opts` has :pretty? false. A `doc` without a :version gets the version
  that this namespace writes."
  ([doc]
   (emit-json doc {}))
  ([doc {:keys [pretty?] :or {pretty? true} :as opts}]
   (json/write-str (unname document-keys
                           (-> (merge {:version version} doc)
                               (update :chapters #(mapv write-chapter %))))
                   {:pretty? pretty?})))

(defn update-image-urls
  "The chapters map `doc` with the URL of each chapter's image replaced by
  what `f` gives for it.

  The images of an MP3 file's chapters come as data URLs, but JSON
  Chapters says that an image is a URL for a player to fetch. A host
  writes each one as a file before it serves the chapters file, e.g. with
  a function of its own, write-image!, that gives the URL of the file:

      (update-image-urls doc #(if-let [{:keys [type data]}
                                       (bytes/from-data-url %)]
                                (write-image! type data)
                                %))"
  [doc f]
  (let [image (fn [chapter]
                (cond-> chapter
                  (string? (:img chapter)) (update :img f)))]
    (cond-> doc
      (:chapters doc) (update :chapters (partial mapv image)))))

(defn from-psc
  "The chapters map of the Podlove chapters `psc`, the :psc/chapters map of
  a feed item. A chapter whose start isn't a timestamp is dropped, and the
  report says so."
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
                                      (str dropped " Podlove chapters have a"
                                           " start that is not a timestamp"
                                           " and were dropped")
                                      :count dropped)]
                         [])})))

(defn to-psc
  "The Podlove chapters of the chapters map `doc`, as the :psc/chapters map
  of a feed item, which from-psc reads back, e.g. for a feed that embeds
  chapters as well as linking to them. Podlove requires a start time, so
  the chapters without one are left out.

      (assoc item :psc/chapters (to-psc doc))"
  [doc]
  {:version  "1.2"
   :chapters (vec (for [{:keys [start-time title url img]} (:chapters doc)
                        :let  [start (time/format-timestamp start-time)]
                        :when start]
                    (cond-> {:start start}
                      title (assoc :title title)
                      url   (assoc :href url)
                      img   (assoc :image img))))})

(def ^:no-doc timestamp-first
  "A line of show notes that starts with a timestamp, e.g. \"1. (12:34) -
  The topic\". The list marker, the brackets and the separator are
  optional."
  (re-pattern
   (str ;; the list marker
        "^[ \\t]*(?:[-*•–—]\\s*|\\d{1,2}[.)]\\s+)?"
        ;; the time, in brackets or not
        "[\\[(]?\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\s*[\\])]?"
        ;; the separator, and the title
        "\\s*(?:[-–—:|.,]\\s*)?"
        "(\\S.*?)\\s*$")))

(def ^:no-doc timestamp-last
  "A line of show notes that ends with a timestamp, e.g. \"- The topic
  (12:34)\". The list marker, the separator and the brackets are optional."
  (re-pattern
   (str ;; the list marker, the title and the separator
        "^[ \\t]*(?:[-*•–—]\\s*)?"
        "(\\S.*?)\\s*"
        "(?:[-–—:|,]\\s*)?"
        ;; the time, in brackets or not
        "[\\[(]?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})[\\])]?[ \\t]*$")))

(defn- line-chapter
  "The chapter that `line` of show notes lists by `pattern`, or nil when it
  lists none. The time is the first group of `pattern` when `time-first?`,
  and the second otherwise."
  [pattern time-first? line]
  (when-let [[_ a b] (re-matches pattern line)]
    (let [[timestamp title] (if time-first? [a b] [b a])]
      (when-let [seconds (time/parse-clock timestamp)]
        {:start-time seconds :title title}))))

(defn from-lines
  "The chapters map of the timestamps in `lines`, the lines of the plain
  text of show notes, or nil when they list no chapters.

  Each chapter is on a line of its own, e.g. \"12:34 The topic\" or \"The
  topic (12:34)\". The time comes first, or last when no line starts with
  one. Two lines with the time first make a list, and so do three with the
  time last in rising order. The report says the chapters were inferred.

  A caller that has the plain text of the notes already can give its
  lines here, so they're parsed once."
  [lines]
  ;; the patterns' optional parts meet around runs of spaces, over which
  ;; they would backtrack quadratically, so each run is one space first
  (when (some #(re-find #"\d:\d\d" %) lines)
    (let [lines    (map #(str/replace % #"\s+" " ") lines)
          leading  (keep #(line-chapter timestamp-first true %) lines)
          trailing (keep #(line-chapter timestamp-last false %) lines)
          chapters (cond
                     (<= 2 (count leading))
                     leading

                     (and (<= 3 (count trailing))
                          (apply < (map :start-time trailing)))
                     trailing)]
      (when chapters
        (let [n (count chapters)]
          (with-meta {:version version :chapters (vec chapters)}
            {:podcast/report [(diagnostic :chapters/inferred :info
                                          (str n " chapters were read from"
                                               " timestamps in the show notes")
                                          :count n)]}))))))

(defn from-text
  "The chapters map of the timestamps in the show notes `s`, HTML or plain
  text, with each chapter on a line of its own."
  [s]
  ;; notes with no digit, colon and two digits in a row, a colon reference
  ;; counted, hold no time, and most notes have none, so they skip the parse
  (when (and s (re-find #"\d(?::|&#0*58;|&#[xX]0*3[aA];|&colon;)\d\d" s))
    (from-lines (str/split-lines (html/text (html/hiccup s))))))

(defn sorted
  "The chapters of `doc` that have a start time, in order of it."
  [doc]
  (sort-by :start-time (filter (comp number? :start-time) (:chapters doc))))

(defn visible
  "The chapters of `doc` meant for the table of contents, in order: those
  whose :toc isn't false."
  [doc]
  (remove #(false? (:toc %)) (sorted doc)))

(defn with-end-times
  "The chapters of `doc` in order, each with the start of the next as its
  :end-time when it has none."
  [doc]
  (let [chapters (vec (sorted doc))]
    (map-indexed (fn [i chapter]
                   (if-let [next-start (:start-time (get chapters (inc i)))]
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
  "The position in seconds that a jump from `seconds` into the media goes
  to, by the table of contents of `doc`. The `direction` is :next or
  :previous, and `opts` can give the :restart seconds.

  A jump to the next chapter goes to the first chapter that starts after
  `seconds`. It's nil after the start of the last chapter. A jump back
  goes to the start of the chapter that plays. In the first :restart
  seconds of a chapter, it goes to the chapter before instead. Before the
  first chapter, it goes to 0.

  Chapters with :toc false aren't in the table of contents, as JSON
  Chapters has it, so no jump goes to them. No standard says where a jump
  back goes, so the default :restart is only a choice."
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
     (def example (parse-json (slurp "test/resources/chapters/example.json")))
     (at example 300)
     (jump example 300 :next)
     (jump example 300 :previous)
     #_.))
