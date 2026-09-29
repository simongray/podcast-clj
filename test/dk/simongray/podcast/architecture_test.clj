(ns dk.simongray.podcast.architecture-test
  "The layering the architecture document describes, checked against the
  ns forms of the source: every namespace is placed in a layer, every
  require points to its own layer or a lower one, the graph has no cycles,
  and the companion formats stand on the primitives alone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def prefix
  "The root of the library's namespaces."
  "dk.simongray.podcast")

(def layers
  "The layer of each namespace, by its name under `prefix`: the primitives,
  the vocabulary, the feed model with its interpreters, the companions with
  the formats, the app and the directories, and the front door."
  {"diagnostics" 0 "time" 0 "time.rrule" 0 "guid" 0 "json" 0 "xml" 0 "xml.jsoup" 0
   "http" 0 "rank" 0 "async" 0 "bytes" 0 "bech32" 0 "secp256k1" 0
   "spec" 1 "quirks" 1
   "feed" 2 "feed.coerce" 2 "feed.validate" 2 "feed.schema" 2
   "format.chapters" 3 "format.transcript" 3 "format.opml" 3 "format.html" 3
   "format.id3" 3 "format.mp3" 3 "format.mp4" 3 "format.media" 3 "format.image" 3
   "value" 3 "value.lnaddress" 3 "value.metadata" 3 "resolve" 3 "check" 3 "app.player" 3 "app.search" 3 "app.downloads" 3
   "app.sync" 3 "app.sync.store" 3 "app.sync.file" 3 "app.sync.webdav" 3 "app.sync.webdav.server" 3
   "app.sync.encrypted" 3 "app.sync.gpodder" 3 "app.sync.gpodder.server" 3
   "directory" 3 "directory.podcastindex" 3 "podping" 3 "websub" 3 "links" 3
   "comments" 3 "comments.activitypub" 3 "comments.atproto" 3 "comments.common" 3
   "comments.nostr" 3 "nostr" 3
   "analytics" 3 "analytics.op3" 3
   "" 4})

(defn- short-name
  "The name of `ns` under `prefix`: \"xml\" for dk.simongray.podcast.xml,
  \"\" for the front door."
  [ns]
  (str/replace (str ns) (re-pattern (str "^" (java.util.regex.Pattern/quote prefix) "\\.?")) ""))

(defn- requires
  "The library namespaces the ns form in `file` requires, on either
  platform. An `:as-alias` loads nothing, so it requires nothing."
  [file]
  (let [source (slurp file)]
    (into (sorted-set)
          (for [features [#{:clj} #{:cljs}]
                :let [form (read-string {:read-cond :allow :features features} source)]
                clause (rest form)
                :when (and (seq? clause) (= :require (first clause)))
                spec (rest clause)
                :let [ns (if (sequential? spec) (first spec) spec)]
                :when (and (symbol? ns)
                           (str/starts-with? (str ns) prefix)
                           (not (and (sequential? spec) (some #{:as-alias} spec))))]
            (short-name ns)))))

(def graph
  "Each source namespace, by short name, with the library namespaces it
  requires."
  (delay
    (into (sorted-map)
          (for [file (file-seq (io/file "src"))
                :when (re-find #"\.clj[cs]?$" (.getName ^java.io.File file))
                :let [form (read-string {:read-cond :allow :features #{:clj}} (slurp file))]]
            [(short-name (second form)) (requires file)]))))

(defn- cycle-from
  "A cycle in `graph` reachable from `start`, as the path that closes it, or
  nil."
  [graph start]
  (loop [stack [[start []]] seen #{}]
    (when-let [[ns path] (peek stack)]
      (cond
        (some #{ns} path) (conj path ns)
        (seen ns)         (recur (pop stack) seen)
        :else             (recur (into (pop stack) (map #(vector % (conj path ns))) (get graph ns))
                                 (conj seen ns))))))

(deftest every-namespace-is-placed
  (doseq [ns (keys @graph)]
    (is (contains? layers ns) (str ns " is not placed in a layer; add it to the layers"))))

(deftest requires-point-down-or-sideways
  (doseq [[ns reqs] @graph
          req       reqs]
    (is (<= (get layers req 99) (get layers ns -1))
        (str ns " (layer " (get layers ns) ") requires " req " (layer " (get layers req) ")"))))

(deftest no-cycles
  (doseq [ns (keys @graph)]
    (is (nil? (cycle-from @graph ns)) (str "a cycle: " (cycle-from @graph ns)))))

(deftest the-lowest-layers-know-nothing-above-them
  (testing "the registry is data with no interpreter in sight"
    (is (= #{} (get @graph "spec"))))
  (testing "the companion formats stand on the primitives and each other alone"
    (doseq [ns ["format.chapters" "format.transcript" "format.opml" "format.html" "format.id3"
                "format.mp3" "format.mp4" "format.media" "format.image" "value"]]
      (is (every? #(or (zero? (get layers %)) (str/starts-with? % "format.")) (get @graph ns))
          (str ns " requires " (get @graph ns)))))
  (testing "the XML layer needs only the diagnostics"
    (is (= #{"diagnostics"} (get @graph "xml")))))
