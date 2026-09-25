(ns corpus
  "The corpus of real feeds the library is tested against, one or more per
  hosting platform, kept outside git in dev-resources/corpus. `download!`
  recreates it; `sweep` parses every feed and summarizes what happened."
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [print-table]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.validate :as validate]))

(def dir
  (io/file "dev-resources/corpus"))

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
  "The feed files in `dir`, sorted."
  []
  (->> (file-seq dir)
       (filter #(.isFile ^java.io.File %))
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

(comment
  (download!)
  (sweep)
  #_.)
