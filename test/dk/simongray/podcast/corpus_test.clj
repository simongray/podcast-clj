(ns dk.simongray.podcast.corpus-test
  "Every feed in dev-resources/corpus parses, round-trips and validates
  without an exception, and every companion file below it parses into
  something. The corpus is not in git; see dev/corpus.clj. When it is absent
  the tests pass vacuously."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.feeds-test :refer [same-for-slim?]]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.test-support :refer [corpus-feeds]]))

(deftest every-corpus-feed
  (doseq [{:keys [name feed]} @corpus-feeds]
    (testing name
      (is (string? (:rss/title feed)))
      (is (every? map? (:rss/items feed)))
      (is (= feed (podcast/parse (podcast/emit feed))) "lossless round trip")
      (is (vector? (validate/validate feed {:requirements :psp-1}))))))

(deftest every-corpus-feed-slim
  (doseq [{:keys [name feed]} @corpus-feeds]
    (testing name
      (is (same-for-slim? feed (update feed :rss/items #(vec (drop 3 %))) "https://example.com/feed.xml"
                          #inst "2026-10-01T00:00:00Z")))))

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
