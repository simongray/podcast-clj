(ns dk.simongray.podcast.diagnostics-test
  "The catalogue of diagnostics and the source agree: every id the code
  raises is catalogued at the level the code gives it, and nothing is
  catalogued that the code never raises."
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
    ;; the endpoints of the Podcast Index
    :value/by-feed-id :value/by-feed-url :value/by-podcast-guid :value/by-episode-guid
    :value/batch-by-episode-guid})

(defn- ids-in-source
  "The diagnostic ids that the source names. A keyword of an alias, such as
  `::comments/for`, is none."
  []
  (->> (re-seq #"(?<!:):(?:fetch|repair|quirk|xml|feed|transcript|chapters|id3|mp3|mp4|file|validate|check|sync|comments|value|analytics)/[a-z0-9-]+" source)
       (map #(keyword (subs % 1)))
       (remove not-diagnostics)
       set))

(defn- levels-in-source
  "The `[id level]` pairs visible at the sites that raise diagnostics: map
  literals, the `diagnostic` helpers of the validator and companions, and
  the profile requirements."
  []
  (concat (for [[_ id level] (re-seq #"\{:id (:[a-z][a-z0-9]*/[a-z0-9-]+) :level (:[a-z]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])
          (for [[_ id level] (re-seq #"\(diagnostic (:[a-z][a-z0-9]*/[a-z0-9-]+) (:[a-z]+)" source)]
            [(keyword (subs id 1)) (keyword (subs level 1))])
          (for [[_ level id] (re-seq #"\(requirement-diagnostics (:[a-z]+) (:validate/[a-z-]+)" source)]
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
