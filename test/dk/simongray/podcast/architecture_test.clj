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
  the formats, the profile, sync and the directories, the root namespace,
  and the high level under app, the only namespaces that require the root
  namespace."
  {"diagnostics" 0 "feed.guid" 0
   "util.async" 0 "util.bech32" 0 "util.bytes" 0 "util.bytes.view" 0 "util.crdt" 0 "util.crdt.list" 0 "util.http" 0 "util.url" 0 "util.json" 0 "util.rank" 0
   "util.nostr" 0 "util.proxy" 0 "util.secret" 0 "util.text" 0 "util.text.query" 0 "util.text.search" 0 "util.text.search.ciff" 0 "util.time" 0
   "util.time.rrule" 0
   "util.xml" 0 "util.xml.jsoup" 0
   "crypto.aes-gcm" 0 "crypto.chacha20" 0 "crypto.nip44" 0 "crypto.secp256k1" 0
   "feed.spec" 1 "feed.quirks" 1
   "feed" 2 "feed.rss" 2 "feed.coerce" 2 "feed.validate" 2 "feed.schema" 2
   "feed.resolve" 3 "feed.check" 3 "feed.discovery" 3 "feed.publish" 3
   "format.chapters" 3 "format.transcript" 3 "format.opml" 3 "format.html" 3 "format.image" 3
   "format.media" 3 "format.media.id3" 3 "format.media.mp3" 3 "format.media.mp4" 3
   "value" 3 "value.lnaddress" 3 "value.metadata" 3 "value.nwc" 3 "value.webln" 3
   "profile" 3 "search" 3 "downloads" 3
   "sync" 3 "sync.store" 3 "sync.file" 3 "sync.indexeddb" 3 "sync.webdav" 3 "sync.webdav.server" 3
   "sync.encrypted" 3 "sync.gpodder" 3 "sync.gpodder.server" 3
   "directory" 3 "directory.podcastindex" 3 "directory.links" 3
   "updates.podping" 3 "updates.websub" 3
   "comments" 3 "comments.activitypub" 3 "comments.atproto" 3 "comments.common" 3
   "comments.nostr" 3
   "analytics" 3 "analytics.op3" 3
   "" 4
   "app.episode" 5 "app.show" 5 "app.feeds" 5 "app.library" 5})

(defn- short-name
  "The name of `ns` under `prefix`: \"util.xml\" for
  dk.simongray.podcast.util.xml, \"\" for the root namespace."
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
    (is (= #{} (get @graph "feed.spec"))))
  (testing "the companion formats stand on the primitives and each other alone"
    (doseq [ns ["format.chapters" "format.transcript" "format.opml" "format.html" "format.image"
                "format.media" "format.media.id3" "format.media.mp3" "format.media.mp4" "value"]]
      (is (every? #(or (zero? (get layers %)) (str/starts-with? % "format.")) (get @graph ns))
          (str ns " requires " (get @graph ns)))))
  (testing "the XML layer needs nothing of podcasts"
    (is (= #{} (get @graph "util.xml")))))

(deftest a-name-shows-a-primitive
  (doseq [ns    (keys @graph)
          :when (re-find #"^(util|crypto)\." ns)]
    (is (zero? (get layers ns -1)) (str ns " is under util or crypto, so it is a primitive"))))

(deftest a-name-shows-the-high-level
  (doseq [ns (keys @graph)]
    (is (= (str/starts-with? ns "app.") (= 5 (get layers ns)))
        (str ns " is in layer " (get layers ns) ", and app holds layer 5 and nothing else"))))
