(ns dk.simongray.podcast.diagnostics-test
  "The catalogue of diagnostics and the source agree: every id the code
  raises is catalogued at the level the code gives it, and nothing is
  catalogued that the code never raises. Also the summary of a report."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.diagnostics :as diagnostics]))

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
    :value/batch-by-episode-guid})

(defn- ids-in-source
  "The diagnostic ids that the source names. A keyword of an alias, such as
  `::comments/for`, is none."
  []
  (->> (re-seq #"(?<!:):(?:fetch|discovery|repair|quirk|xml|feed|transcript|chapters|id3|mp3|mp4|file|validate|check|sync|podping|comments|value|analytics)/[a-z0-9-]+" source)
       (map #(keyword (subs % 1)))
       (remove not-diagnostics)
       set))

(defn- levels-in-source
  "The `[id level]` pairs visible at the sites that raise diagnostics: map
  literals, the `diagnostic` helpers of the validator and companions, and
  the chosen requirements."
  []
  (concat (for [[_ id level] (re-seq #"\{:id (:[a-z][a-z0-9]*/[a-z0-9-]+) :level (:[a-z]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])
          (for [[_ id level] (re-seq #"\(diagnostic (:[a-z][a-z0-9]*/[a-z0-9-]+) (:[a-z]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])
          (for [[_ level id] (re-seq #"\(missing-diagnostics (:[a-z]+) (:validate/[a-z-]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])))

(deftest the-catalogue-matches-the-source
  (testing "the same ids"
    (is (= (ids-in-source) (set (keys diagnostics/by-id)))))
  (testing "the same levels"
    (is (seq (levels-in-source)))
    (doseq [[id level] (levels-in-source)]
      (is (= level (:level (diagnostics/by-id id))) (str id))))
  (testing "every entry documented"
    (is (every? (comp seq :doc) diagnostics/catalogue))))

(deftest summarizing-diagnostics
  (let [diagnostics [{:id :a :level :warning :message "m" :path [:rss/items 0]}
                     {:id :a :level :warning :message "m" :path [:rss/items 1]}
                     {:id :b :level :info :tag :x}]
        summary     (diagnostics/summarize diagnostics)]
    (is (= [[:a 2] [:b 1]] (map (juxt :id :count) summary)))
    (is (= {:id :a :level :warning :message "m" :path [:rss/items 0]} (:example (first summary))))
    (is (= [] (diagnostics/summarize [])))))
