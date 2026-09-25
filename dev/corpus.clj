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
            [dk.simongray.podcast.transcript :as transcript]
            [dk.simongray.podcast.validate :as validate]))

(def dir
  (io/file "dev-resources/corpus"))

(def companions-dir
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
  "The feed files: the .xml files directly in `dir`, sorted."
  []
  (->> (.listFiles dir)
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".xml")))
       (sort-by #(.getName ^java.io.File %))))

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
           v     (validate/validate feed :psp-1)]
       {:file       (.getName ^java.io.File file)
        :items      (count (:rss/items feed))
        :ms         ms
        :round-trip (= feed (podcast/parse (podcast/emit feed)))
        :report     (count (podcast/report feed))
        :errors     (count (filter (comp #{:error} :level) v))
        :unknown    (count (filter (comp #{:validate/unknown-element} :id) v))}))))

;;;; The second corpus: the chapter and transcript files the feeds link to

(def extensions
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

(comment
  (download!)
  (sweep)
  (download-companions!)
  (sweep-companions)
  #_.)
