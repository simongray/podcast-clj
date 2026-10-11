(ns dk.simongray.podcast.corpus-test
  "Every feed in dev-resources/corpus parses, round-trips and validates
  without an exception, the descriptions of its items render as Hiccup,
  text and Markdown, and every companion file below it parses into
  something. The corpus is not in git; see dev/corpus.clj. When it is absent
  the tests pass vacuously."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.feeds-test :refer [same-for-slim?]]
            [dk.simongray.podcast.feed.guid :as feed.guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.test-support :refer [corpus-feeds]]))

(deftest every-corpus-feed
  (doseq [{:keys [name feed]} @corpus-feeds]
    (testing name
      (is (string? (:rss/title feed)))
      (is (every? map? (:rss/items feed)))
      (is (= feed (rss/parse (rss/emit feed))) "lossless round trip")
      (is (vector? (validate/validate feed {:requirements :psp-1}))))))

(deftest descriptions-in-the-corpus-render
  (doseq [{:keys [name feed]} @corpus-feeds
          item  (take 20 (:rss/items feed))
          k     [:rss/description :content/encoded :itunes/summary]
          :let  [s (get item k)]
          :when (and (string? s) (re-find #"\w" s))]
    (let [nodes (html/hiccup s)]
      (is (seq? nodes) name)
      (is (re-find #"\w" (html/text nodes)) (str name " " k))
      (is (string? (html/markdown nodes))))))

(deftest every-corpus-feed-slim
  (doseq [{:keys [name feed]} @corpus-feeds]
    (testing name
      (is (same-for-slim? feed (update feed :rss/items #(vec (drop 3 %))) "https://example.com/feed.xml"
                          #inst "2026-10-01T00:00:00Z")))))

(deftest every-corpus-feed-capped
  (doseq [{:keys [name feed]} @corpus-feeds
          :let [url     "https://example.com/feed.xml"
                stored  (feeds/add {} url {:status 200 :feed feed} #inst "2026-10-01")
                k       (first (keys stored))
                newest  (vec (take 10 (:rss/items feed)))
                refresh #(feeds/refresh stored k {:status 200 :feed (assoc feed :rss/items %)} #inst "2026-10-02")
                entry   (get-in (refresh newest) [:feeds k])
                guids   #(set (keep feed.guid/item-guid (:rss/items %)))]]
    (testing name
      (is (= newest (take 10 (:rss/items (:feed entry)))) "the fresh items first")
      (is (= (guids feed) (guids (:feed entry))) "and every item that can be told apart kept")
      (is (= (apply disj (guids feed) (guids {:rss/items newest})) (set (keys (:retained entry))))
          "with a fact of each")
      (is (= (guids (:feed entry)) (guids (feeds/slim (:feed entry)))) "in the slim form too")
      (when (seq (:rss/items feed))
        (is (= feed (get-in (refresh []) [:feeds k :feed])) "and an empty answer changes nothing")))))

(def companions-dir
  (io/file "dev-resources/corpus/companions"))

(def manifest
  (delay (let [file (io/file companions-dir "manifest.edn")]
           (when (.exists file)
             (edn/read-string (slurp file))))))

(deftest every-companion-file
  (doseq [{:keys [file kind type status]} @manifest
          :when (= 200 status)]
    (testing file
      (let [s (slurp (io/file companions-dir file))]
        (if (= :transcript kind)
          (let [{:keys [segments]} (transcript/parse s type)]
            (is (seq segments))
            (is (every? (comp string? :body) segments)))
          (let [{:keys [chapters]} (chapters/parse-json s)]
            (is (seq chapters))
            (is (every? (comp number? :start-time) chapters))))))))
