(ns dk.simongray.podcast.reference-test
  "The generated documents in doc/ say what the registry and the diagnostics
  catalogue say today. When this fails, run `(reference/write!)` from
  dev/reference.clj and commit the result."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.spec :as spec]
            [reference]))

(deftest the-generated-documents-are-current
  (is (= (slurp "doc/tags.md") (reference/markdown))
      "doc/tags.md is stale; run (reference/write!)")
  (is (= (slurp "doc/diagnostics.md") (reference/diagnostics-markdown))
      "doc/diagnostics.md is stale; run (reference/write!)"))

(deftest every-vocabulary-has-a-section
  (let [covered (set (mapcat :namespaces reference/vocabularies))]
    (is (= #{} (set (remove #(contains? covered %) (map (comp namespace :tag) spec/tags))))
        "a namespace of the registry has no section in reference/vocabularies")))
