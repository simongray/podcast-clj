(ns dk.simongray.podcast.reference-test
  "The generated documents in doc/ say what the registry and the diagnostics
  catalogue say today. When this fails, run `(reference/write!)` from
  dev/reference.clj and commit the result."
  (:require [clojure.test :refer [deftest is]]
            [reference]))

(deftest the-generated-documents-are-current
  (is (= (slurp "doc/tags.md") (reference/markdown))
      "doc/tags.md is stale; run (reference/write!)")
  (is (= (slurp "doc/diagnostics.md") (reference/diagnostics-markdown))
      "doc/diagnostics.md is stale; run (reference/write!)"))
