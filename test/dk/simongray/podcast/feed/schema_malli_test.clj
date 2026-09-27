(ns dk.simongray.podcast.feed.schema-malli-test
  "The generated schemas put to work with Malli: they accept every real feed,
  and feeds generated from them survive emit and parse."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed.schema :as schema]
            [dk.simongray.podcast.test-support :refer [corpus-feeds]]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]))

(def Feed
  (m/schema schema/feed))

(defn- explain
  [feed]
  (me/humanize (m/explain Feed feed)))

(deftest real-feeds-validate
  (is (nil? (explain (podcast/parse (slurp (io/resource "feeds/namespace-example.xml"))))))
  (is (nil? (explain (podcast/parse (slurp (io/resource "feeds/quirky.xml"))))))
  (testing "every feed in the corpus, when it is there"
    (doseq [{:keys [name feed]} @corpus-feeds]
      (is (nil? (explain feed)) name))))

(deftest schemas-catch-what-parse-forgave
  (let [feed (podcast/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>T</title>
                              <item><title>E</title><itunes:episode>seven</itunes:episode></item></channel></rss>")]
    (is (= {:rss/items [{:itunes/episode ["should be an integer"]}]} (explain feed)))))

(deftest generated-feeds-emit-and-parse
  (testing "whatever the generators produce, at Malli's default sizes, emit writes XML that parses"
    (doseq [seed (range 20)]
      (let [feed (mg/generate Feed {:seed seed})]
        (is (map? (podcast/parse (podcast/emit feed))) (str "seed " seed))))
    (is (= 10 (count (mg/sample Feed))))))

(def realistic
  "Generators for values feeds could actually hold: printable words, dates
  on whole seconds this side of 2100, finite numbers whose fractions
  survive printing."
  (merge (m/default-schemas)
         {:string  (m/schema [:string {:gen/gen (gen/fmap str/join (gen/vector gen/char-alphanumeric 1 12))}])
          'inst?   (m/schema [inst? {:gen/gen (gen/fmap #(java.util.Date. (* 1000 ^long %))
                                                        (gen/choose 0 4102444800))}])
          'number? (m/schema [number? {:gen/gen (gen/one-of [(gen/choose 0 100000)
                                                             (gen/fmap #(+ % 0.25) (gen/choose 0 100000))])}])}))

(defn- prune
  "`x` without the empty collections, which emit leaves out and parse
  therefore cannot bring back."
  [x]
  (walk/postwalk (fn [v]
                   (if (map? v)
                     (into {} (remove (fn [[_ v]] (and (coll? v) (empty? v)))) v)
                     v))
                 x))

(deftest realistic-feeds-round-trip-exactly
  (let [Realistic (m/schema schema/feed {:registry realistic})]
    (doseq [seed (range 30)]
      (let [feed (mg/generate Realistic {:seed seed})
            back (podcast/parse (podcast/emit feed) {:quirks? false})]
        (is (= (prune (merge {:rss/version "2.0"} feed)) (prune back)) (str "seed " seed))
        (is (nil? (explain back)) (str "seed " seed))))))
