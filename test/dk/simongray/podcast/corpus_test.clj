(ns dk.simongray.podcast.corpus-test
  "Every feed in dev-resources/corpus parses, round-trips and validates
  without an exception, and every companion file below it parses into
  something. The corpus is not in git; see dev/corpus.clj. When it is absent
  the tests pass vacuously."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.chapters :as chapters]
            [dk.simongray.podcast.transcript :as transcript]
            [dk.simongray.podcast.validate :as validate]))

(def files
  "The feed files: the .xml files directly in the corpus directory, not the
  companion files in the directory below it."
  (delay (->> (.listFiles (io/file "dev-resources/corpus"))
              (filter #(and (.isFile ^java.io.File %)
                            (.endsWith (.getName ^java.io.File %) ".xml")))
              (sort-by #(.getName ^java.io.File %)))))

(deftest every-corpus-feed
  (doseq [file @files]
    (testing (.getName ^java.io.File file)
      (let [feed (podcast/parse (slurp file))]
        (is (string? (:rss/title feed)))
        (is (every? map? (:rss/items feed)))
        (is (= feed (podcast/parse (podcast/emit feed))) "lossless round trip")
        (is (vector? (validate/validate feed :psp-1)))))))

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
