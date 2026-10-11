(ns study
  "A study of what real feeds look like, over the feeds in
  dev-resources/corpus and its sweep: how they parse, what the validator
  finds, how they identify themselves and their items, how often they
  change, and which tags they use. `measure` reads every feed, `summary`
  aggregates the measures, and `report!` writes both next to the corpus."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [corpus]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.url :as url]))

(defn records
  "The feeds on disk as records of a :file, a :url, a :final-url and a
  :population: the named corpus feeds under :named, and the rows of the
  sweep's manifest that kept a file."
  []
  (concat (for [[name u] corpus/feeds
                :let [file (io/file corpus/dir (str name ".xml"))]
                :when (.exists file)]
            {:file file :url u :final-url u :population :named})
          (for [{:keys [file url final-url population]} (corpus/sweep-manifest)
                :when file]
            {:file       (io/file corpus/sweep-dir file)
             :url        url
             :final-url  (or final-url url)
             :population population})))

(defn domain
  "The domain of `u`, lower case, or nil."
  [u]
  (try
    (some-> (.getHost (java.net.URI. (url/legal-url u))) str/lower-case)
    (catch Exception _ nil)))

(defn parse-outcome
  "How the feed in `s` parsed: :strict, :repaired with the ids of the
  repairs, :lenient when the fallback parser read it, or :failed with the
  error, along with the :feed when there is one."
  [s]
  (let [{:keys [feed error]} (rss/try-parse s)]
    (if feed
      (let [repairs (->> (rss/report feed)
                         (map :id)
                         (filter #(= "repair" (namespace %)))
                         distinct
                         vec)]
        {:feed    feed
         :outcome (cond
                    (some #{:repair/lenient-parser} repairs) :lenient
                    (seq repairs)                            :repaired
                    :else                                    :strict)
         :repairs repairs})
      {:outcome :failed :error (:id error) :reason (:reason error)})))

(defn duplicates
  "How many groups of `xs` share a value of `f`, and the size of the
  largest group."
  [f xs]
  (let [groups (->> xs (keep f) frequencies vals (filter #(> % 1)))]
    {:groups (count groups) :largest (apply max 0 groups)}))

(defn item-identity
  "How the `items` identify themselves: how many have a guid, fall back
  on the enclosure URL, or have neither, how many guids look like URLs,
  and the duplicate guids and enclosure URLs."
  [items]
  (let [guid-of   #(not-empty (:content (:rss/guid %)))
        url-guid? #(re-find #"^https?://" (or (guid-of %) ""))]
    {:with-guid           (count (filter guid-of items))
     :enclosure-as-guid   (count (filter #(and (nil? (guid-of %))
                                               (:url (:rss/enclosure %)))
                                         items))
     :no-identity         (count (filter #(nil? (guid/item-guid %)) items))
     :url-guids           (count (filter url-guid? items))
     :duplicate-guids     (duplicates guid/item-guid items)
     :duplicate-enclosures (duplicates (comp :url :rss/enclosure) items)}))

(defn enclosure-facts
  "What the enclosures of the `items` declare: how many items have none,
  and of the enclosures, how many lack a length, say 0, lack a type, or
  declare a type that isn't audio or video, with the odd types counted."
  [items]
  (let [enclosures (keep :rss/enclosure items)
        types      (map :type enclosures)
        odd?       #(not (re-find #"^(audio|video)/" (or % "")))]
    {:items-without  (- (count items) (count enclosures))
     :no-length      (count (remove :length enclosures))
     :zero-length    (count (filter #(= 0 (:length %)) enclosures))
     :no-type        (count (remove :type enclosures))
     :odd-types      (frequencies (filter odd? types))
     :audio-mp3      (count (filter #{"audio/mp3"} types))}))

(defn date-facts
  "The dates of the `items` at `now`: how many have none, lie in the
  future, and whether the feed lists them newest first."
  [items now]
  (let [dates  (filter inst? (map :rss/pub-date items))
        ms     (map inst-ms dates)
        now-ms (inst-ms now)]
    {:undated      (- (count items) (count dates))
     :future       (count (filter #(> % now-ms) ms))
     :newest-first (boolean (and (seq ms) (apply >= ms)))}))

(defn category-facts
  "The itunes categories of `feed` against Apple's list: the names that
  Apple doesn't know, and whether any path nests deeper than Apple's two
  levels."
  [feed]
  (let [paths (resolve/categories feed)
        names (distinct (flatten paths))]
    {:count    (count paths)
     :unknown  (vec (remove directory/apple-genres names))
     :too-deep (count (filter #(> (count %) 2) paths))}))

(defn guid-facts
  "How `feed` identifies itself, fetched from `u` and finally from
  `final-u`: whether it declares a podcast:guid, whether that is a UUID,
  and whether it equals the one derived from either URL."
  [feed u final-u]
  (let [declared (:podcast/guid feed)]
    {:declared?   (some? declared)
     :uuid?       (guid/guid? declared)
     :from-url?   (= declared (guid/from-url u))
     :from-final? (= declared (guid/from-url final-u))}))

(defn schedule-facts
  "When `feed` says it changes, judged at `now`: the days until the next
  check, and which of the signals it gives."
  [feed now]
  (let [next (schedule/next-check feed now)]
    {:wait-days        (when next
                         (/ (- (inst-ms next) (inst-ms now)) 86400000.0))
     :update-frequency (some? (:podcast/update-frequency feed))
     :ttl              (some? (:rss/ttl feed))
     :syndication      (some? (:sy/update-period feed))
     :complete?        (resolve/complete? feed)}))

(def tags
  "The tags whose adoption the study counts, by the key of the model."
  [:podcast/guid :podcast/locked :podcast/funding :podcast/txt
   :podcast/medium :podcast/update-frequency :podcast/license
   :podcast/people :podcast/locations :podcast/images
   :podcast/srcset-images :podcast/values :podcast/podroll
   :podcast/publisher :podcast/podping :podcast/live-items
   :podcast/trailers :podcast/chat :podcast/blocks :itunes/type
   :itunes/complete :itunes/new-feed-url :itunes/applepodcastsverify
   :sy/update-period :rss/ttl :rss/cloud :rss/skip-hours :rss/skip-days])

(def item-tags
  "The item tags whose adoption the study counts, by the key of the model."
  [:podcast/transcripts :podcast/chapters :podcast/people :podcast/values
   :podcast/social-interacts :podcast/alternate-enclosures
   :podcast/soundbites :podcast/season :podcast/episode :podcast/images
   :podcast/srcset-images :podcast/locations :podcast/txt
   :podcast/content-links :content/encoded :itunes/image :itunes/duration
   :itunes/episode-type :itunes/season :itunes/episode :media/contents
   :media/group :rss/link :rss/description])

(defn tag-facts
  "Which of `tags` `feed` has, and which of `item-tags` any of its items
  has, as sets."
  [feed]
  {:channel (set (filter #(some? (get feed %)) tags))
   :items   (set (filter (fn [k] (some #(some? (get % k)) (:rss/items feed)))
                         item-tags))})

(defn validation-facts
  "The diagnostics of `feed` under `requirements`, as counts by id, with
  the unknown elements left out."
  [feed requirements]
  (->> (validate/validate feed {:requirements requirements})
       (remove (comp #{:validate/unknown-element} :id))
       (map :id)
       frequencies))

(defn measure-one
  "Everything the study measures of the feed of `record`, read at `now`."
  [{:keys [file url final-url population]} now]
  (let [s (slurp file)
        {:keys [feed outcome repairs error reason]} (parse-outcome s)
        base {:file       (.getName ^java.io.File file)
              :population population
              :domain     (domain final-url)
              :chars      (count s)
              :redirected (not= url final-url)
              :outcome    outcome
              :repairs    repairs}]
    (if-not feed
      (assoc base :error error :reason reason)
      (let [items (:rss/items feed)]
        (assoc base
               :medium     (or (resolve/medium feed) "podcast")
               :items      (count items)
               :empty      (empty? items)
               :paged      (some? (resolve/next-page feed))
               :self-url   (some? (resolve/self-url feed))
               :hubs       (count (resolve/hubs feed))
               :generator  (:rss/generator feed)
               :guid       (guid-facts feed url final-url)
               :identity   (item-identity items)
               :enclosures (enclosure-facts items)
               :dates      (date-facts items now)
               :categories (category-facts feed)
               :schedule   (schedule-facts feed now)
               :tags       (tag-facts feed)
               :report     (frequencies (map :id (rss/report feed)))
               :psp-1      (validation-facts feed :psp-1)
               :apple      (validation-facts feed :apple))))))

(defn measure
  "The measures of every feed of `records`, read at `now`, in parallel."
  ([]
   (measure (records) (time/now)))
  ([records now]
   (vec (pmap #(measure-one % now) records))))

(defn- share
  "How many of `ms` satisfy `pred`, as a count and a percentage of them."
  [pred ms]
  (let [n (count (filter pred ms))]
    {:count n :percent (if (seq ms) (Math/round (* 100.0 (/ n (count ms)))) 0)}))

(defn- quantiles
  "The minimum, quartiles, median, ninth decile and maximum of the numbers
  `xs`, rounded to one decimal."
  [xs]
  (let [v (vec (sort xs))
        n (count v)
        q (fn [p] (when (pos? n)
                    (/ (Math/round (* 10.0 (v (min (dec n) (int (* p n)))))) 10.0)))]
    (when (pos? n)
      {:min (q 0) :q1 (q 0.25) :median (q 0.5) :q3 (q 0.75) :p90 (q 0.9)
       :max (q 1.0) :n n})))

(defn- by-feeds
  "The keys of the `k` maps of `ms`, by the number of measures holding
  them, most first."
  [k ms]
  (->> ms (mapcat (comp keys k)) frequencies (sort-by (comp - val)) vec))

(defn summary
  "What the measures `ms` say, aggregated: the counts and shares of each
  question of the study."
  [ms]
  (let [parsed  (filter :items ms)
        n       (count parsed)
        items   (map :items parsed)
        pct     (fn [pred] (share pred parsed))
        sizes   (frequencies items)
        capped  (->> sizes (filter (fn [[size feeds]] (and (>= size 20) (>= feeds 3))))
                     (sort-by (comp - val)) (take 12) vec)]
    {:feeds       (count ms)
     :populations (frequencies (map :population ms))
     :parsing     {:outcomes (frequencies (map :outcome ms))
                   :repairs  (frequencies (mapcat :repairs ms))
                   :errors   (frequencies (map (juxt :error :reason) (remove :items ms)))}
     :fetch       {:redirected (share :redirected ms)
                   :chars      (quantiles (map :chars ms))}
     :items       {:count            (quantiles items)
                   :empty            (pct :empty)
                   :paged            (pct :paged)
                   :self-url         (pct :self-url)
                   :hubs             (pct (comp pos? :hubs))
                   :common-sizes     capped
                   :undated          (pct (comp pos? :undated :dates))
                   :future           (pct (comp pos? :future :dates))
                   :not-newest-first (pct (complement (comp :newest-first :dates)))}
     :identity    {:declared-guid     (pct (comp :declared? :guid))
                   :guid-not-uuid     (pct #(and (:declared? (:guid %))
                                                 (not (:uuid? (:guid %)))))
                   :guid-from-url     (pct (comp :from-url? :guid))
                   :guid-from-final   (pct (comp :from-final? :guid))
                   :guid-neither      (pct #(and (:declared? (:guid %))
                                                 (not (:from-url? (:guid %)))
                                                 (not (:from-final? (:guid %)))))
                   :duplicate-guids   (pct (comp pos? :groups :duplicate-guids :identity))
                   :duplicate-enclosures (pct (comp pos? :groups :duplicate-enclosures :identity))
                   :items-without-guid (pct (comp pos? :enclosure-as-guid :identity))
                   :items-no-identity (pct (comp pos? :no-identity :identity))
                   :url-guids         (pct (comp pos? :url-guids :identity))}
     :enclosures  {:items-without (pct (comp pos? :items-without :enclosures))
                   :no-length     (pct (comp pos? :no-length :enclosures))
                   :zero-length   (pct (comp pos? :zero-length :enclosures))
                   :no-type       (pct (comp pos? :no-type :enclosures))
                   :audio-mp3     (pct (comp pos? :audio-mp3 :enclosures))
                   :odd-types     (->> parsed (mapcat (comp keys :odd-types :enclosures))
                                       frequencies (sort-by (comp - val)) vec)}
     :mediums     (frequencies (map :medium parsed))
     :categories  (let [podcasts (filter #(= "podcast" (:medium %)) parsed)
                        pct      (fn [pred] (share pred podcasts))]
                    {:of-podcasts (count podcasts)
                     :none        (pct (comp zero? :count :categories))
                     :unknown     (pct (comp seq :unknown :categories))
                     :too-deep    (pct (comp pos? :too-deep :categories))
                     :unknown-names (->> podcasts
                                         (mapcat (comp :unknown :categories))
                                         frequencies (sort-by (comp - val)) vec)})
     :schedule    (let [waits (keep (comp :wait-days :schedule) parsed)]
                    {:wait-days        (quantiles waits)
                     :no-time          (pct (comp nil? :wait-days :schedule))
                     :over-a-day       (share #(> % 1) waits)
                     :over-a-week      (share #(> % 7) waits)
                     :over-a-month     (share #(> % 30) waits)
                     :update-frequency (pct (comp :update-frequency :schedule))
                     :ttl              (pct (comp :ttl :schedule))
                     :syndication      (pct (comp :syndication :schedule))
                     :complete         (pct (comp :complete? :schedule))})
     :tags        {:channel (->> parsed (mapcat (comp :channel :tags)) frequencies
                                 (sort-by (comp - val)) vec)
                   :items   (->> parsed (mapcat (comp :items :tags)) frequencies
                                 (sort-by (comp - val)) vec)}
     :report      (by-feeds :report parsed)
     :psp-1       (by-feeds :psp-1 parsed)
     :apple       (by-feeds :apple parsed)
     :generators  (->> parsed (keep :generator)
                       (map #(first (str/split % #"[ /]")))
                       frequencies (sort-by (comp - val)) (take 20) vec)
     :domains     (->> parsed (keep :domain) frequencies (sort-by (comp - val))
                       (take 20) vec)
     :n           n}))

(defn by-population
  "The summary of the measures `ms` of each population, by population."
  [ms]
  (into (sorted-map)
        (for [[population group] (group-by :population ms)]
          [population (summary group)])))

(def phenomena
  "What the study looks for in single feeds, by name: a predicate of a
  measure, for the feeds that show each."
  {:failed-parse        (comp #{:failed} :outcome)
   :repaired-parse      (comp #{:repaired :lenient} :outcome)
   :empty               :empty
   :paged               :paged
   :duplicate-guids     (comp pos? :groups :duplicate-guids :identity)
   :duplicate-enclosures (comp pos? :groups :duplicate-enclosures :identity)
   :items-without-guid  (comp pos? :enclosure-as-guid :identity)
   :no-identity         (comp pos? :no-identity :identity)
   :guid-not-uuid       #(and (:declared? (:guid %)) (not (:uuid? (:guid %))))
   :no-enclosure-type   (comp pos? :no-type :enclosures)
   :no-enclosure-length (comp pos? :no-length :enclosures)
   :odd-enclosure-type  (comp seq :odd-types :enclosures)
   :unknown-category    #(and (= "podcast" (:medium %)) (seq (:unknown (:categories %))))
   :deep-category       (comp pos? :too-deep :categories)
   :future-dates        (comp pos? :future :dates)
   :undated             (comp pos? :undated :dates)
   :not-newest-first    (complement (comp :newest-first :dates))
   :long-wait           #(some-> (:wait-days (:schedule %)) (> 30))
   :complete            (comp :complete? :schedule)
   :thousand-items      #(= 1000 (:items %))})

(defn exhibits
  "The feeds among the measures `ms` that show each of the `phenomena`, as
  up to `n` files each, for a conformance set."
  ([ms]
   (exhibits ms 5))
  ([ms n]
   (into (sorted-map)
         (for [[k pred] phenomena]
           [k (->> ms (filter :items) (filter pred) (map :file) (take n) vec)]))))

(defn report!
  "Measure the feeds on disk and write the measures and their summary next
  to the corpus, as study-measures.edn and study-summary.edn, giving the
  summary."
  []
  (let [ms (measure)
        s  (summary ms)]
    (spit (io/file corpus/dir "study-measures.edn")
          (with-out-str (pprint/pprint ms)))
    (spit (io/file corpus/dir "study-summary.edn")
          (with-out-str (pprint/pprint s)))
    s))

(def index-dir
  "Where the Podcast Index database and the sample drawn from it live."
  (io/file corpus/dir "index"))

(def study-dir
  "Where the feeds of the sample are kept, with their manifest."
  (io/file corpus/dir "study"))

(defn read-sample
  "The rows of the sample in `file`, a tab-separated export of the index
  with a header row, as maps keyed by the column names as keywords."
  [file]
  (let [[header & rows] (map #(str/split % #"\t" -1)
                             (str/split-lines (slurp file)))
        ks (map keyword header)]
    (vec (for [row rows :when (= (count row) (count ks))]
           (zipmap ks row)))))

(def kept-headers
  "The response headers the manifest keeps of each fetch."
  ["content-type" "content-encoding" "cache-control" "etag" "last-modified"
   "link" "server" "vary" "x-cache" "cf-cache-status"])

(defn fetch-row!
  "Fetch the feed of `row` into `study-dir`, and give the row with what
  happened: the :status, the :final-url, the :chars of the body, the kept
  :headers, and the :file when the body was kept, or the :error."
  [{:keys [id url] :as row}]
  (let [{:keys [status body headers error] final :url}
        (try
          (http/fetch! url {:timeout-seconds 20
                            :max-bytes       (* 32 1024 1024)})
          (catch Exception e
            {:error (str (.getSimpleName (class e)) ": " (ex-message e))}))

        kept? (and (= 200 status) (seq body))
        file  (str id ".xml")]
    (when kept?
      (spit (io/file study-dir file) body))
    (cond-> (assoc row
                   :status    status
                   :final-url final
                   :chars     (count body)
                   :headers   (select-keys headers kept-headers))
      kept? (assoc :file file)
      error (assoc :error error))))

(defn download-sample!
  "Fetch every feed of `rows` into `study-dir`, one request at a time per
  domain with a pause between them, and `concurrency` domains at once,
  and write the manifest next to the feeds, giving it."
  ([rows]
   (download-sample! rows 8))
  ([rows concurrency]
   (.mkdirs study-dir)
   (let [groups  (java.util.concurrent.LinkedBlockingQueue.
                  ^java.util.Collection (vec (vals (group-by (comp domain :url)
                                                             rows))))
         results (java.util.concurrent.ConcurrentLinkedQueue.)
         done    (atom 0)
         work    (fn []
                   (loop []
                     (when-let [group (.poll groups)]
                       (doseq [row group]
                         (.add results (fetch-row! row))
                         (Thread/sleep 250)
                         (when (zero? (mod (swap! done inc) 100))
                           (println @done "fetched")))
                       (recur))))
         threads (doall (repeatedly concurrency
                                    #(doto (Thread. ^Runnable work) .start)))]
     (run! #(.join ^Thread %) threads)
     (let [manifest (vec (sort-by (juxt :stratum :id) (seq results)))]
       (spit (io/file study-dir "manifest.edn")
             (with-out-str (pprint/pprint manifest)))
       manifest))))

(defn study-manifest
  "The manifest written by `download-sample!`."
  []
  (edn/read-string (slurp (io/file study-dir "manifest.edn"))))

(def browser-agent
  "A browser's User-Agent, to tell a refusal of the library's agent from a
  refusal of everyone."
  (str "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/130.0 Safari/537.36"))

(defn reprobe-blocked!
  "Fetch again, with the User-Agent `agent`, every row of `manifest` that
  was refused (403, 406 or 429), and give the manifest with the status of
  that fetch under `k`, so that the study can tell a refusal of one agent
  from one of everyone. The manifest is written back, and a body that the
  first fetch didn't get is kept."
  ([manifest]
   (reprobe-blocked! manifest browser-agent :browser-status))
  ([manifest agent k]
   (let [blocked? (comp #{403 406 429} :status)
         probe    (fn [{:keys [url] :as row}]
                    (if-not (blocked? row)
                      row
                      (let [{:keys [status body]}
                            (try
                              (http/fetch! url {:timeout-seconds 20
                                                :max-bytes       (* 32 1024 1024)
                                                :user-agent      agent})
                              (catch Exception e
                                {:status (.getSimpleName (class e))}))

                            kept? (and (= 200 status) (seq body)
                                       (not (:file row)))
                            file  (str (:id row) ".xml")]
                        (when kept?
                          (spit (io/file study-dir file) body))
                        (cond-> (assoc row k status)
                          kept? (assoc :file file)))))
         probed   (vec (pmap probe manifest))]
     (spit (io/file study-dir "manifest.edn")
           (with-out-str (pprint/pprint probed)))
     probed)))

(defn- header
  "The first value of the header `k` in the kept `headers` of a row."
  [headers k]
  (first (get headers k)))

(defn fetch-summary
  "What the fetches of `manifest` say, overall and by stratum: the
  statuses, the errors, the refusals that a browser's agent got past,
  the redirects, and which validators, encodings and hubs the servers
  gave."
  [manifest]
  (let [by-stratum (group-by :stratum manifest)
        pct        (fn [pred xs]
                     (let [n (count (filter pred xs))]
                       {:count n :percent (Math/round (* 100.0 (/ n (max 1 (count xs)))))}))
        ok?        #(= 200 (:status %))
        one        (fn [rows]
                     (let [ok (filter ok? rows)]
                       {:rows          (count rows)
                        :statuses      (->> rows
                                            (map #(or (:status %) :error))
                                            frequencies
                                            (sort-by (comp - val))
                                            vec)
                        :errors        (frequencies (keep #(some-> (:error %) (str/replace #":.*" "")) rows))
                        :browser-got   (pct #(and (contains? #{403 406 429} (:status %))
                                                  (= 200 (:browser-status %)))
                                            rows)
                        :redirected    (pct #(and (:final-url %) (not= (:url %) (:final-url %))) ok)
                        :to-https      (pct #(and (str/starts-with? (:url %) "http://")
                                                  (str/starts-with? (str (:final-url %)) "https://"))
                                            ok)
                        :etag          (pct #(header (:headers %) "etag") ok)
                        :last-modified (pct #(header (:headers %) "last-modified") ok)
                        :either        (pct #(or (header (:headers %) "etag")
                                                 (header (:headers %) "last-modified")) ok)
                        :gzip          (pct #(= "gzip" (header (:headers %) "content-encoding")) ok)
                        :cache-control (pct #(header (:headers %) "cache-control") ok)
                        :hub-link      (pct #(some->> (header (:headers %) "link")
                                                      (re-find #"rel=\"?hub\"?")) ok)
                        :content-types (->> ok
                                            (map #(some-> (header (:headers %) "content-type")
                                                          (str/replace #";.*" "") str/lower-case))
                                            frequencies (sort-by (comp - val)) (take 8) vec)
                        :servers       (->> ok
                                            (map #(some-> (header (:headers %) "server")
                                                          (str/replace #"[/ ].*" "") str/lower-case))
                                            frequencies (sort-by (comp - val)) (take 8) vec)
                        :chars         (quantiles (map :chars ok))}))]
    (into {:all (one manifest)}
          (for [[stratum rows] by-stratum]
            [(keyword stratum) (one rows)]))))

(defn sample-records
  "The feeds of the sample on disk as records for `measure`, with the
  stratum of the index as their population."
  []
  (for [{:keys [file url final-url stratum]} (study-manifest)
        :when file]
    {:file       (io/file study-dir file)
     :url        url
     :final-url  (or final-url url)
     :population (keyword stratum)}))

(comment
  (def ms (measure))
  (summary ms)
  (edn/read-string (slurp (io/file corpus/dir "study-summary.edn")))
  ;; The sample of 11 October 2026 came out of the index's SQLite dump,
  ;; unpacked in index-dir, with sqlite3 in tab mode with a header row:
  ;;
  ;;   WITH cols AS (SELECT id, url, lastHttpStatus, itunesId, generator,
  ;;                        newestItemPubdate, oldestItemPubdate,
  ;;                        episodeCount, popularityScore, updateFrequency,
  ;;                        host, podcastGuid, language, itunesType,
  ;;                        contentType, lastUpdate FROM podcasts),
  ;;   popular AS (SELECT 'popular' AS stratum, * FROM cols
  ;;               WHERE lastHttpStatus = 200
  ;;                 AND newestItemPubdate > strftime('%s', 'now', '-1 year')
  ;;               ORDER BY popularityScore DESC, id LIMIT 2000),
  ;;   live AS (SELECT 'live' AS stratum, * FROM cols
  ;;            WHERE lastHttpStatus = 200
  ;;              AND newestItemPubdate > strftime('%s', 'now', '-1 year')
  ;;              AND id NOT IN (SELECT id FROM popular)
  ;;            ORDER BY random() LIMIT 2000),
  ;;   stale AS (SELECT 'stale' AS stratum, * FROM cols
  ;;             WHERE lastHttpStatus = 200 AND newestItemPubdate > 0
  ;;               AND newestItemPubdate < strftime('%s', 'now', '-2 years')
  ;;             ORDER BY random() LIMIT 400),
  ;;   failing AS (SELECT 'failing' AS stratum, * FROM cols
  ;;               WHERE lastHttpStatus <> 200 ORDER BY random() LIMIT 300),
  ;;   any AS (SELECT 'any' AS stratum, * FROM cols
  ;;           WHERE id NOT IN (SELECT id FROM popular)
  ;;             AND id NOT IN (SELECT id FROM live)
  ;;             AND id NOT IN (SELECT id FROM stale)
  ;;             AND id NOT IN (SELECT id FROM failing)
  ;;           ORDER BY random() LIMIT 300)
  ;;   SELECT * FROM popular UNION ALL SELECT * FROM live
  ;;   UNION ALL SELECT * FROM stale UNION ALL SELECT * FROM failing
  ;;   UNION ALL SELECT * FROM any;
  (def rows (read-sample (io/file index-dir "sample.tsv")))
  (download-sample! (take 20 rows))
  (reprobe-blocked! (study-manifest))
  (def sample (measure (sample-records) (time/now)))
  (summary sample)
  (by-population sample)
  (fetch-summary (study-manifest))
  (exhibits sample)
  #_.)
