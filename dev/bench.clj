(ns bench
  "A set of timings over the corpus that guards the core of the library:
  parsing, writing, validating and resolving feeds, the diff, the search
  index, the library's calls, saving and restoring, syncing, the profile's
  merge, and the readers of companion and media files. `run!` times every
  workload with criterium and writes the result next to the corpus, and
  `compare` sets two results side by side."
  (:refer-clojure :exclude [run! compare])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint :refer [print-table]]
            [corpus]
            [criterium.core :as criterium]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.time :as time]))

(def results-dir
  "Where the results of each run are kept, outside git."
  (io/file corpus/dir "bench"))

(def bench-opts
  "The options of criterium for each workload: its quick benchmark, with
  two seconds of warm-up in place of five, since there are many
  workloads."
  (assoc criterium/*default-quick-bench-opts* :warmup-jit-period (* 2 1e9)))

(def at
  "The instant that every stamped change of the workloads is made at."
  #inst "2026-10-11T12:00:00Z")

;; The inputs are read once and kept, since the workloads share them and
;; the files are large.

(def feeds
  "Three corpus feeds by size: a :small one of tens of KB, a :medium one of
  a few MB, and a :large one of tens of MB, parsed."
  (delay
   (into {}
         (for [[k name] {:small "feedburner" :medium "buzzsprout-buzzcast"
                         :large "art19"}
               :let [file (io/file corpus/dir (str name ".xml"))]
               :when (.exists file)]
           [k (rss/parse (slurp file))]))))

(def sweep
  "A hundred feeds of the sweep with their URLs, parsed, for the workloads
  over a library of many feeds."
  (delay
   (vec (for [{:keys [file url]} (take 100 (filter :file (corpus/sweep-manifest)))]
          {:url url :feed (rss/parse (slurp (io/file corpus/sweep-dir file)))}))))

(def companions
  "One transcript of each format and one chapters file from the corpus's
  companions, as text by kind."
  (delay
   (let [manifest (corpus/manifest)
         pick     (fn [kind type]
                    (some #(when (and (= kind (:kind %)) (= type (:type %))
                                      (= 200 (:status %)))
                             (slurp (io/file corpus/companions-dir (:file %))))
                          manifest))]
     {:srt      (pick :transcript "application/x-subrip")
      :vtt      (pick :transcript "text/vtt")
      :json     (pick :transcript "application/json")
      :chapters (pick :chapters "application/json+chapters")})))

(def media-views
  "A view of an MP3 and of an MP4 from the parts that the corpus keeps of
  them, by format."
  (delay
   (let [manifest (edn/read-string (slurp (io/file corpus/media-dir
                                                   "manifest.edn")))
         add-part (fn [v {:keys [file start total]}]
                    (let [path (.toPath (io/file corpus/media-dir file))
                          data (java.nio.file.Files/readAllBytes path)]
                      (view/add v start data total)))
         view-of  (fn [{:keys [parts]}] (reduce add-part (view/view) parts))
         by-format (fn [format]
                     (some (fn [{:keys [error parts] :as entry}]
                             (when (and (not error) (seq parts))
                               (let [v (view-of entry)]
                                 (when (= format (:format (media/read v)))
                                   v))))
                           manifest))]
     {:mp3 (by-format :mp3) :mp4 (by-format :mp4)})))

(def many
  "A library subscribed to the hundred feeds of `sweep`, with the search
  index, and the id of the newest episode of its first show."
  (delay
   (let [;; a feed that declares the guid of another is left out
         subscribe (fn [lib {:keys [url feed]}]
                     (try
                       (library/subscribe lib url feed {:at at})
                       (catch clojure.lang.ExceptionInfo _ lib)))
         lib (reduce subscribe
                     (library/make "bench" {:config {:now-fn (constantly at)}})
                     @sweep)
         [show item] (first (library/episodes lib {:order :newest}))]
     {:library lib :id (library/episode-id lib show item)})))

(defn- played-profile
  "A profile of the `device` with `n` episodes of the feeds of `sweep`
  played, one stamp apart."
  [device n]
  (let [ids (for [{:keys [url feed]} @sweep
                  item (:rss/items feed)]
              [(guid/feed-guid feed url) (guid/item-guid item)])]
    (reduce (fn [p [i id]]
              (profile/mark-played p id (time/later at i)))
            (profile/make device)
            (map-indexed vector (take n ids)))))

(def profiles
  "Two profiles of two devices with five thousand played episodes each,
  for the merge."
  (delay {:a (played-profile "a" 5000) :b (played-profile "b" 5000)}))

(defn- synced-pair
  "Two libraries of two devices and a memory store between them, the first
  with a thousand played episodes."
  []
  (let [store (store/memory)
        a     (assoc (:library @many) :device "a"
                     :profile (played-profile "a" 1000))
        b     (library/make "b" {:config {:now-fn (constantly at)}})]
    {:store store :a a :b b}))

(def workloads
  "The workloads by name, each a function of no arguments whose time is
  measured, in the order they're run. A workload whose input the corpus
  lacks gives nil and is left out."
  [[:parse-small    #(some-> (:small @feeds) rss/emit rss/parse)]
   [:parse-medium   #(some-> (:medium @feeds) rss/emit rss/parse)]
   [:parse-large    #(some-> (:large @feeds) rss/emit rss/parse)]
   [:emit-medium    #(some-> (:medium @feeds) rss/emit)]
   [:report-medium  #(some-> (:medium @feeds) rss/report count)]
   [:validate-psp-1 #(some-> (:medium @feeds)
                             (validate/validate {:requirements :psp-1}))]
   [:resolve-properties #(some-> (:medium @feeds) resolve/properties)]
   [:resolve-episodes #(some-> (:medium @feeds) resolve/episodes count)]
   [:diff-medium    #(when-let [f (:medium @feeds)]
                       (feed/diff f (update f :rss/items rest)))]
   [:search-index   #(feed.search/index
                      (mapcat (fn [{:keys [url feed]}]
                                (feed.search/feed-documents feed url))
                              @sweep))]
   [:search-query   #(library/search (:library @many) "podcast episode")]
   [:episodes-newest #(library/episodes (:library @many)
                                        {:order :newest :limit 20})]
   [:episodes-text  #(library/episodes (:library @many)
                                       {:where {:text "news"} :limit 20})]
   [:episode-view   #(library/episode (:library @many) (:id @many))]
   [:step-progress  #(library/step (:library @many)
                                   [:progress (:id @many) 120 3600]
                                   {:at at})]
   [:plan           #(library/plan (:library @many) {} {:at at})]
   [:refresh-304    #(library/refresh!
                      (:library @many)
                      {:at    (time/later at 100000000)
                       :fetch (fn [_ _] {:status 304})})]
   [:refresh-changed #(library/refresh!
                       (:library @many)
                       {:at    (time/later at 100000000)
                        :fetch (let [by-url (into {} (map (juxt :url :feed))
                                                   @sweep)]
                                 (fn [url _] {:status 200
                                              :feed   (by-url url)}))})]
   [:save           #(library/save! (:library @many) (store/memory))]
   ;; the save after a refresh, to a store that holds the library already
   [:save-again     (let [store (delay (doto (store/memory)
                                         (->> (library/save! (:library @many)))))]
                      #(library/save! (:library @many) @store))]
   [:restore        (let [store (delay (doto (store/memory)
                                         (->> (library/save! (:library @many)))))]
                      #(library/restore! (library/make "bench") @store))]
   [:sync-round     #(let [{:keys [store a b]} (synced-pair)
                           backend (sync/backend store)]
                       (library/sync! (library/merge b (library/sync! a [backend]))
                                      [backend]))]
   [:profile-merge  #(profile/merge (:a @profiles) (:b @profiles))]
   [:profile-compact #(profile/compact (profile/merge (:a @profiles)
                                                      (:b @profiles))
                                       at)]
   [:transcript-srt #(some-> (:srt @companions) (transcript/parse "application/x-subrip"))]
   [:transcript-vtt #(some-> (:vtt @companions) (transcript/parse "text/vtt"))]
   [:transcript-json #(some-> (:json @companions) (transcript/parse "application/json"))]
   [:chapters-json  #(some-> (:chapters @companions) chapters/parse-json)]
   [:media-mp3      #(some-> (:mp3 @media-views) media/read)]
   [:media-mp4      #(some-> (:mp4 @media-views) media/read)]])

(defn- timed
  "The time of the workload `f` by criterium, as a map of the mean in
  milliseconds, its standard deviation, and the samples, or nil when the
  workload has no input."
  [f]
  (when (some? (f))
    (let [{:keys [mean variance sample-count execution-count]}
          (criterium/quick-benchmark* f bench-opts)]
      {:ms      (/ (Math/round (* 1000000.0 (first mean))) 1000.0)
       :sd-ms   (/ (Math/round (* 1000000.0 (Math/sqrt (first variance)))) 1000.0)
       :samples sample-count
       :runs    execution-count})))

(defn run!
  "Time every workload, or those of `names`, print a table of the times,
  write them to results-dir as <date>.edn, and give them as a vector of
  rows."
  ([]
   (run! (map first workloads)))
  ([names]
   (let [wanted (set names)
         rows   (vec (for [[name f] workloads
                           :when (wanted name)
                           :let  [_ (print name "... ") _ (flush)
                                  t (timed f)]]
                       (do (println (if t (str (:ms t) " ms") "skipped"))
                           (assoc t :name name))))
         date   (subs (time/format-iso8601 (time/now)) 0 10)]
     (.mkdirs results-dir)
     (spit (io/file results-dir (str date ".edn"))
           (with-out-str (pprint/pprint rows)))
     (print-table [:name :ms :sd-ms :samples :runs] (filter :ms rows))
     rows)))

(defn read-results
  "The rows of the run of `date`, a string such as 2026-10-11."
  [date]
  (edn/read-string (slurp (io/file results-dir (str date ".edn")))))

(defn compare
  "The rows of the runs `before` and `after`, two dates or two vectors of
  rows, side by side, with the ratio of each workload's time and a mark
  on those that grew by more than a third."
  [before after]
  (let [rows  (fn [x] (if (string? x) (read-results x) x))
        by    (fn [x] (into {} (map (juxt :name :ms)) (rows x)))
        b     (by before)
        a     (by after)
        table (for [name (map first workloads)
                    :when (and (b name) (a name))
                    :let  [ratio (/ (Math/round (* 100.0 (/ (a name) (b name))))
                                    100.0)]]
                {:name name :before (b name) :after (a name) :ratio ratio
                 :slower? (when (> ratio 1.33) "!")})]
    (print-table [:name :before :after :ratio :slower?] table)
    (vec table)))

(comment
  (run!)
  (run! [:parse-medium :search-query :sync-round])
  (compare "2026-10-11" "2026-10-18")
  #_.)
