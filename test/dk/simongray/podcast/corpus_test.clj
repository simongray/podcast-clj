(ns dk.simongray.podcast.corpus-test
  "Every feed in dev-resources/corpus parses, round-trips and validates
  without an exception. The corpus is not in git; see dev/corpus.clj. When
  it is absent the test passes vacuously."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.validate :as validate]))

(def files
  (delay (->> (file-seq (io/file "dev-resources/corpus"))
              (filter #(.isFile ^java.io.File %))
              (sort-by #(.getName ^java.io.File %)))))

(deftest every-corpus-feed
  (doseq [file @files]
    (testing (.getName ^java.io.File file)
      (let [feed (podcast/parse (slurp file))]
        (is (string? (:rss/title feed)))
        (is (every? map? (:rss/items feed)))
        (is (= feed (podcast/parse (podcast/emit feed))) "lossless round trip")
        (is (vector? (validate/validate feed :psp-1)))))))
