(ns dk.simongray.podcast.diagnostics-test
  "The catalogue of diagnostics and the source agree: every id the code
  raises is catalogued at the level the code gives it, and nothing is
  catalogued that the code never raises. Also the summary of a report."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.like-minded.diagnostics :as sync-diagnostics]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.diagnostics.impl :as diagnostics.impl]))

(def source
  "The library's source, one string."
  (->> (file-seq (io/file "src"))
       (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))
       (map slurp)
       (str/join "\n")))

(def not-diagnostics
  "Keywords in the diagnostic namespaces that name something else."
  #{:xml/unknown :xml/namespaces :xml/report :xml/malformed
    ;; the calls of the Podcast Index
    :value/by-feed-id :value/by-feed-url :value/by-podcast-guid :value/by-episode-guid
    :value/batch-by-episode-guid
    ;; the keys of a show or an episode from a directory
    :directory/episode-count :directory/id :directory/rank :directory/raw :directory/source
    :directory/sources :directory/subscribers :directory/url :directory/show})

(defn- ids-in-source
  "The diagnostic ids that the source names. A keyword of an alias, such as
  `::comments/for`, is none."
  []
  (->> (re-seq #"(?<!:):(?:fetch|refresh|discovery|directory|repair|quirk|xml|feed|transcript|chapters|id3|mp3|mp4|ogg|aac|file|validate|check|sync|gpodder|podping|comments|value|analytics|library|nostr)/[a-z0-9-]+" source)
       (map #(keyword (subs % 1)))
       (remove not-diagnostics)
       set))

(defn- levels-in-source
  "The `[id level]` pairs visible at the sites that raise diagnostics: map
  literals, the `diagnostic` helpers of the validator and companions, and
  the chosen requirements."
  []
  (concat (for [[_ id level] (re-seq #"\{:id\s+(:[a-z][a-z0-9]*/[a-z0-9-]+)\s+:level\s+(:[a-z]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])
          (for [[_ id level] (re-seq #"\((?:[a-z]+/)?diagnostic\s+(:[a-z][a-z0-9]*/[a-z0-9-]+)\s+(:[a-z]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])
          (for [[_ level id] (re-seq #"\(missing-diagnostics\s+(:[a-z]+)\s+(:validate/[a-z-]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])))

(deftest the-catalogue-matches-the-source
  (testing "the same ids, and those of like-minded, which the source can match on"
    (is (= (set (map :id diagnostics.impl/podcast-catalogue))
           (set (remove (set (map :id sync-diagnostics/catalogue)) (ids-in-source)))))
    (is (every? diagnostics/by-id (ids-in-source))))
  (testing "the same levels"
    (is (seq (levels-in-source)))
    (doseq [[id level] (levels-in-source)]
      (is (= level (:level (diagnostics/by-id id))) (str id))))
  (testing "every entry documented"
    (is (every? (comp seq :doc) diagnostics/catalogue))))

(deftest the-secrets-of-a-url
  (let [url    "https://u:p@a.example/feed.xml?token=abc&page=2&api_key=K#top"
        masked "https://a.example/feed.xml?token=…&page=2&api_key=…#top"
        d      (diagnostics/fetch-failure {:url url :reason (str "The request to " url " is not allowed")})]
    (is (= masked (:url d)) "the token and the key of a private feed, and the credentials")
    (is (= (str "The request to " masked " is not allowed") (:reason d)) "in the reason too"))
  (is (= "https://a.example/feed.xml?q=1" (diagnostics.impl/masked-url "https://a.example/feed.xml?q=1"))))

(deftest a-step-that-failed
  (let [{:keys [error] :as result} (diagnostics/failed {:id :fetch/malformed :url "https://a.example/?token=abc"})]
    (is (= [:error] (keys result)))
    (is (= [:fetch/malformed :warning "https://a.example/?token=…"] ((juxt :id :level :url) error))
        "described, with its secrets masked")
    (is (string? (:message error)))))

(deftest summarizing-diagnostics
  (let [diagnostics [{:id :a :level :warning :message "m" :path [:rss/items 0]}
                     {:id :a :level :warning :message "m" :path [:rss/items 1]}
                     {:id :b :level :info :tag :x}]
        summary     (diagnostics/summarize diagnostics)]
    (is (= [[:a 2] [:b 1]] (map (juxt :id :count) summary)))
    (is (= {:id :a :level :warning :message "m" :path [:rss/items 0]} (:example (first summary))))
    (is (= [] (diagnostics/summarize [])))))

(deftest a-verdict
  (is (= {:passed? true :level :warning :counts {:error 0 :warning 1 :info 1} :errors []}
         (diagnostics/verdict [{:id :a :level :warning} {:id :b :level :info}])))
  (let [{:keys [passed? level counts errors]} (diagnostics/verdict [{:id :x :level :error :message "m"}
                                                                   {:id :x :level :error :message "m"}
                                                                   {:id :y :level :info}])]
    (is (= [false :error {:error 2 :warning 0 :info 1}] [passed? level counts]))
    (is (= [[:x 2]] (map (juxt :id :count) errors)) "what fails, by kind"))
  (is (= {:passed? true :level nil :counts {:error 0 :warning 0 :info 0} :errors []}
         (diagnostics/verdict []))))
