(ns corpus
  "The corpus of real feeds the library is tested against, one or more per
  hosting platform, kept outside git in dev-resources/corpus. `download!`
  recreates it; `sweep` parses every feed and summarizes what happened."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint :refer [print-table]]
            [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.chapters :as chapters]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.index :as index]
            [dk.simongray.podcast.transcript :as transcript]
            [dk.simongray.podcast.validate :as validate]
            [dk.simongray.podcast.xml :as xml]))

(def dir
  "Where the named feeds are kept, outside git."
  (io/file "dev-resources/corpus"))

(def companions-dir
  "Where the chapter and transcript files the feeds link to are kept."
  (io/file dir "companions"))

(def feeds
  "Feed URLs by the name of their file, chosen to cover the hosting
  platforms that produce most of the world's listening, the independent
  hosts that implement the podcast namespace, and a few public broadcasters."
  {"acast"               "https://feeds.acast.com/public/shows/b19ac1f5-6adf-4c8b-aa1a-2af2160f99e4"
   "anchor"              "https://anchor.fm/s/e4e7ebb0/podcast/rss"
   "anchor2"             "https://anchor.fm/s/52280288/podcast/rss"
   "art19"               "https://rss.art19.com/tim-ferriss-show"
   "atp"                 "https://atp.fm/rss"
   "audioboom"           "https://audioboom.com/channels/2399216.rss"
   "ausha"               "https://feed.ausha.co/b3ZpnFJKZxdp"
   "bbc"                 "https://podcasts.files.bbci.co.uk/p02nq0gn.rss"
   "buzzsprout-buzzcast" "https://feeds.buzzsprout.com/231452.rss"
   "captivate"           "https://feeds.captivate.fm/podcastaccelerator/"
   "changelog"           "https://changelog.com/podcast/feed"
   "defn"                "https://feeds.soundcloud.com/users/soundcloud:users:220484243/sounds.rss"
   "dr-dk"               "https://api.dr.dk/podcasts/v1/feeds/genstart"
   "feedburner"          "https://feeds.feedburner.com/dancarlin/history?format=xml"
   "ivoox"               "https://feeds.ivoox.com/feed_fg_f161886_filtro_1.xml"
   "libsyn"              "https://feeds.libsyn.com/60664/rss"
   "megaphone"           "https://feeds.megaphone.fm/GLT4787413333"
   "megaphone-darknet"   "https://podcast.darknetdiaries.com"
   "npr"                 "https://feeds.npr.org/510289/podcast.xml"
   "omny"                "https://www.omnycontent.com/d/playlist/3beb6a7f-7830-4fcc-9c67-b3fa00934bca/1511e96c-a349-4f26-8c74-b40801842a46/4e2f91e5-8b34-49d6-98c3-b40801842a5b/podcast.rss"
   "pc20-nashownotes"    "https://mp3s.nashownotes.com/pc20rss.xml"
   "podco"               "https://feed.pod.co/seriously-social-with-simone-douglas"
   "podnews-buzzsprout"  "https://podnews.net/rss"
   "rsscom"              "https://media.rss.com/podcasting101/feed.xml"
   "simplecast"          "https://feeds.simplecast.com/54nAGcIl"
   "simplecast2"         "https://feeds.simplecast.com/UCwaTX1J"
   "talkpython"          "https://talkpython.fm/episodes/rss"
   "transistor"          "https://feeds.transistor.fm/build-your-saas"})

(defn download!
  "Fetch every feed in `feeds` into `dir`, printing one line per feed."
  []
  (.mkdirs dir)
  (doseq [[name url] (sort feeds)]
    (let [{:keys [status body]} (try (http/fetch! url) (catch Exception e {:status (.getMessage e)}))]
      (if (and (= 200 status) body)
        (do (spit (io/file dir (str name ".xml")) body)
            (println name status (count body) "chars"))
        (println name "FAILED" status)))))

(defn files
  "The feed files: the .xml files directly in `d` (`dir` by default), sorted."
  ([]
   (files dir))
  ([d]
   (->> (.listFiles ^java.io.File d)
        (filter #(and (.isFile ^java.io.File %)
                      (str/ends-with? (.getName ^java.io.File %) ".xml")))
        (sort-by #(.getName ^java.io.File %)))))

(defn sweep
  "Parse every feed in `dir`, round-trip it, validate it against PSP-1, and
  print a table: items, parse time, whether the round trip was lossless, and
  the counts of report entries and validation errors."
  []
  (print-table
   [:file :items :ms :round-trip :report :errors :unknown]
   (for [file (files)]
     (let [s     (slurp file)
           t0    (System/nanoTime)
           feed  (podcast/parse s)
           ms    (quot (- (System/nanoTime) t0) 1000000)
           v     (validate/validate feed {:profile :psp-1})]
       {:file       (.getName ^java.io.File file)
        :items      (count (:rss/items feed))
        :ms         ms
        :round-trip (= feed (podcast/parse (podcast/emit feed)))
        :report     (count (podcast/report feed))
        :errors     (count (filter (comp #{:error} :level) v))
        :unknown    (count (filter (comp #{:validate/unknown-element} :id) v))}))))

;;;; The second corpus: the chapter and transcript files the feeds link to

(def extensions
  "The file extension to save a companion file under, by its declared type."
  {"text/vtt" "vtt" "application/x-subrip" "srt" "application/json" "json"
   "text/html" "html" "text/plain" "txt" "application/json+chapters" "json"})

(defn companion-refs
  "The transcript and chapter references in the corpus feeds, as
  `{:kind :feed :guid :type :url}`, most recent items first."
  []
  (for [file  (files)
        :let  [name (str/replace (.getName ^java.io.File file) #"\.xml$" "")
               feed (podcast/parse (slurp file))]
        item  (:rss/items feed)
        ref   (concat (for [t (:podcast/transcripts item)] (assoc t :kind :transcript))
                      (when-let [c (:podcast/chapters item)] [(assoc c :kind :chapters)]))]
    {:kind (:kind ref) :feed name :guid (feed/item-guid item) :type (:type ref) :url (:url ref)}))

(defn sample
  "A stratified sample of `refs`: per feed, the `n` most recent of each
  transcript type and the `n` most recent chapter files."
  [refs n]
  (for [[_ group] (group-by (juxt :feed :kind :type) refs)
        ref       (take n group)]
    ref))

(defn download-companions!
  "Fetch a sample of the companion files into `companions-dir` and write a
  manifest of what was fetched next to them."
  []
  (.mkdirs companions-dir)
  (let [manifest (vec (map-indexed
                       (fn [i {:keys [kind feed type url] :as ref}]
                         (let [file (io/file companions-dir
                                             (str feed "--" (name kind) "-" i "." (get extensions type "bin")))
                               {:keys [status body headers]}
                               (try (http/fetch! url) (catch Exception e {:status (.getMessage e)}))]
                           (when (and (= 200 status) body)
                             (spit file body))
                           (println (.getName file) status)
                           (assoc ref :file (.getName file) :status status
                                  :content-type (first (get headers "content-type")))))
                       (sample (companion-refs) 2)))]
    (spit (io/file companions-dir "manifest.edn") (with-out-str (pprint/pprint manifest)))
    manifest))

(defn manifest
  "The manifest written by `download-companions!`."
  []
  (edn/read-string (slurp (io/file companions-dir "manifest.edn"))))

(defn sweep-companions
  "Parse every downloaded companion file and print a table: segments or
  chapters, speakers, the format sniffed against the one declared, and any
  error."
  []
  (print-table
   [:file :declared :sniffed :count :speakers :note]
   (for [{:keys [file kind type status]} (manifest)
         :when (= 200 status)
         :let  [s (slurp (io/file companions-dir file))]]
     (if (= :transcript kind)
       (let [{:keys [segments] :as t} (try (transcript/parse s type)
                                          (catch Exception e {:error (.getMessage e)}))]
         {:file file :declared type :sniffed (transcript/sniff s)
          :count (count segments) :speakers (count (distinct (keep :speaker segments)))
          :note (or (:error t) (str/join " " (map (comp name :id) (transcript/report t))))})
       (let [{:keys [chapters version] :as doc} (try (chapters/parse-json s)
                                                    (catch Exception e {:error (.getMessage e)}))]
         {:file file :declared type :sniffed "json" :count (count chapters) :speakers ""
          :note (or (:error doc)
                    (str "version " version " "
                         (str/join " " (map (comp name :id) (chapters/report doc)))))})))))

;;;; The third corpus: hundreds of feeds sampled through the Podcast Index

(def sweep-dir
  "Where the feeds sampled through the Podcast Index are kept."
  (io/file dir "sweep"))

(def mediums
  "The non-podcast mediums to sample feeds of, so that lists, remote items
  and the tags particular to each medium are exercised."
  ["music" "video" "film" "audiobook" "newsletter" "blog" "publisher" "course"])

(defn- distinct-by
  "The elements of `coll` with distinct values of `f`, the first of each kept."
  [f coll]
  (vals (reduce (fn [m x] (update m (f x) #(or % x))) {} coll)))

(defn sweep-populations
  "Feeds worth sweeping, sampled through the Podcast Index with
  `credentials` as `{:population :id :url :title}`: a random slice of the
  recently updated feeds, one of the newly added feeds, one of the feeds
  with value blocks, and a slice of every medium in `mediums`. `sizes` sets
  how many of each: `{:recent 300 :new 100 :value 100 :medium 20}` by
  default."
  ([credentials]
   (sweep-populations credentials {}))
  ([credentials sizes]
   (let [{:keys [recent new value medium]} (merge {:recent 300 :new 100 :value 100 :medium 20} sizes)
         feeds (fn [endpoint params]
                 (:feeds (index/call! credentials endpoint params)))
         pick  (fn [population n rows]
                 (for [{:keys [index/id index/url rss/title]} (take n (shuffle (vec rows)))
                       :when (and id url)]
                   {:population population :id id :url url :title title}))]
     (->> (concat (pick :recent recent (feeds :recent/feeds {:max 1000}))
                  (pick :new new (feeds :recent/new-feeds {:max 1000}))
                  (pick :value value (feeds :podcasts/by-tag {:podcast-value true :max 1000}))
                  (mapcat (fn [m]
                            (pick (keyword (str "medium-" m)) medium
                                  (feeds :podcasts/by-medium {:medium m :max 200})))
                          mediums))
          (distinct-by :id)
          (sort-by (juxt :population :id))))))

(defn download-sweep!
  "Fetch every feed of `rows` (see `sweep-populations`) into `sweep-dir`, in
  parallel, and merge what happened into the manifest next to them: the row
  plus `:status`, `:chars`, `:final-url` and, when kept, `:file`. A row may
  name its `:id` freely, so that hand-picked feeds can join the sample."
  [rows]
  (.mkdirs sweep-dir)
  (let [fetch    (fn [{:keys [id url] :as row}]
                   (let [{:keys [status body] final :url}
                         (try (http/fetch! url {:timeout-seconds 20})
                              (catch Exception e {:status (str (class e) ": " (ex-message e))}))
                         kept? (and (= 200 status) (seq body))]
                     (when kept?
                       (spit (io/file sweep-dir (str id ".xml")) body))
                     (assoc row :status status :chars (count body) :final-url final
                            :file (when kept? (str id ".xml")))))
        fetched  (doall (pmap fetch rows))
        previous (let [f (io/file sweep-dir "manifest.edn")]
                   (when (.exists f) (edn/read-string (slurp f))))
        manifest (vec (sort-by (juxt (comp str :population) (comp str :id))
                               (distinct-by :id (concat fetched previous))))]
    (spit (io/file sweep-dir "manifest.edn") (with-out-str (pprint/pprint manifest)))
    manifest))

(defn sweep-manifest
  "The manifest written by `download-sweep!`."
  []
  (edn/read-string (slurp (io/file sweep-dir "manifest.edn"))))

(defn- unknown-elements
  "Every element kept as unknown anywhere in the parsed `feed`."
  [feed]
  (->> (tree-seq coll? seq feed)
       (filter map?)
       (mapcat :xml/unknown)))

(defn- sweep-one
  "What parsing the feed in `file` turned up: the error, or the report and
  validation diagnostic counts, the unknown elements, the values that would
  not coerce, and whether the round trip changed anything."
  [^java.io.File file]
  (let [s    (slurp file)
        feed (try (podcast/parse s) (catch Exception e e))]
    (if (instance? Exception feed)
      {:file (.getName file) :error (str (class feed) ": " (ex-message feed))}
      (let [report  (podcast/report feed)
            unknown (unknown-elements feed)]
        {:file       (.getName file)
         :items      (count (:rss/items feed))
         :report     (frequencies (map :id report))
         :invalid    (for [{:keys [id path type value]} report
                           :when (= :feed/invalid-value id)]
                       {:key (vec (take-last 2 (remove integer? path))) :type type :value value})
         :unknown    (frequencies (map xml/tag unknown))
         :examples   (into {} (map (juxt xml/tag identity)) unknown)
         :validation (frequencies (map (juxt :id :message)
                                       (remove (comp #{:validate/unknown-element} :id)
                                               (validate/validate feed))))
         :round-trip (= feed (podcast/parse (podcast/emit feed)))}))))

(defn- by-feeds
  "The keys of the `k` maps of `results`, by the number of results holding
  them, most first."
  [k results]
  (->> results (mapcat (comp keys k)) frequencies (sort-by (comp - val))))

(defn sweep-report
  "Parse every feed in `sweep-dir` and aggregate what happened: parse
  errors, report ids and validation diagnostics by the number of feeds they
  touch, unknown elements by tag with one example each, values that would
  not coerce by key with examples, and the feeds whose round trip changed
  something."
  []
  (let [results (doall (pmap sweep-one (files sweep-dir)))
        ok      (remove :error results)
        example (fn [tag]
                  (some #(when-let [e (get (:examples %) tag)]
                           (subs (pr-str e) 0 (min 160 (count (pr-str e)))))
                        ok))]
    {:feeds      (count results)
     :parsed     (count ok)
     :errors     (frequencies (keep :error results))
     :report     (by-feeds :report ok)
     :validation (by-feeds :validation ok)
     :unknown    (for [[tag n] (by-feeds :unknown ok)]
                   {:tag tag :feeds n :example (example tag)})
     :invalid    (->> (mapcat :invalid ok)
                      (group-by (juxt :key :type))
                      (map (fn [[k vs]] {:key (first k) :type (second k) :count (count vs)
                                         :examples (take 4 (distinct (map :value vs)))}))
                      (sort-by (comp - :count)))
     :changed    (map :file (remove :round-trip ok))}))

(comment
  (download!)
  (sweep)
  (download-companions!)
  (sweep-companions)
  (let [creds (:podcastindex (edn/read-string (slurp (str (System/getProperty "user.home") "/Code/secrets.edn"))))]
    (download-sweep! (sweep-populations creds)))
  (sweep-report)
  #_.)
