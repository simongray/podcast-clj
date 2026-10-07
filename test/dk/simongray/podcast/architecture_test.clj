(ns dk.simongray.podcast.architecture-test
  "The layering the architecture document describes, checked against the
  ns forms of the source: every namespace is placed in a layer, every
  require points to its own layer or a lower one, the graph has no cycles,
  the companion formats stand on the primitives alone, and the libraries
  beside podcast-clj require nothing of it or of each other."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def prefix
  "The root of the library's namespaces."
  "dk.simongray.podcast")

(def libraries
  "The roots of the libraries beside podcast-clj, which it requires, and
  which require nothing of it or of each other."
  #{"dk.simongray.crdt" "dk.simongray.search"})

(defn- library-of
  "The root in `libraries` of the namespace named `ns`, or nil."
  [ns]
  (some #(when (or (= ns %) (str/starts-with? ns (str % "."))) %) libraries))

(def layers
  "The layer of each namespace, by its name under `prefix`: the libraries
  beside podcast-clj, by their full names, the primitives, the vocabulary,
  the feed model with its interpreters, the companions with the formats,
  the profile, sync and the directories, the root namespace, and the high
  level under app, the only namespaces that require the root namespace."
  {"dk.simongray.crdt" -1 "dk.simongray.crdt.list" -1 "dk.simongray.crdt.rank" -1
   "dk.simongray.search" -1 "dk.simongray.search.analysis" -1 "dk.simongray.search.queries" -1
   "dk.simongray.search.ciff" -1 "dk.simongray.search.segment" -1
   "diagnostics" 0 "feed.guid" 0 "nostr" 0
   "util.async" 0 "util.bech32" 0 "util.bytes" 0 "util.bytes.view" 0 "util.http" 0 "util.url" 0 "util.json" 0
   "util.proxy" 0 "util.secret" 0
   "util.time" 0
   "util.time.rrule" 0
   "util.xml" 0 "util.xml.jsoup" 0
   "crypto.aes-gcm" 0 "crypto.chacha20" 0 "crypto.nip44" 0 "crypto.secp256k1" 0
   "store" 0 "store.file" 0 "store.indexeddb" 0 "store.webdav" 0 "store.webdav.server" 0 "store.encrypted" 0
   "feed.spec" 1 "feed.quirks" 1
   "feed" 2 "feed.rss" 2 "feed.coerce" 2 "feed.validate" 2 "feed.schema" 2
   "feed.resolve" 3 "feed.check" 3 "feed.discovery" 3 "feed.publish" 3 "feed.search" 3
   "format.chapters" 3 "format.transcript" 3 "format.opml" 3 "format.html" 3 "format.image" 3
   "format.integrity" 3
   "format.media" 3 "format.media.id3" 3 "format.media.mp3" 3 "format.media.mp4" 3
   "value" 3 "value.bolt11" 3 "value.lnaddress" 3 "value.metadata" 3 "value.nwc" 3
   "value.payment" 3 "value.webln" 3
   "profile" 3 "criteria" 3 "downloads" 3
   "sync" 3 "sync.gpodder" 3 "sync.gpodder.server" 3
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
                           (or (str/starts-with? (str ns) prefix)
                               (library-of (str ns)))
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
  (testing "the companion formats stand on the primitives, the libraries and each other alone"
    (doseq [ns ["format.chapters" "format.transcript" "format.opml" "format.html" "format.image"
                "format.media" "format.media.id3" "format.media.mp3" "format.media.mp4"
                "format.integrity" "value" "value.payment" "value.bolt11"]]
      (is (every? #(or (<= (get layers %) 0)
                       (str/starts-with? % "format.")
                       (= "value.payment" %))
                  (get @graph ns))
          (str ns " requires " (get @graph ns)))))
  (testing "the XML layer needs nothing of podcasts"
    (is (= #{} (get @graph "util.xml")))))

(deftest a-name-shows-a-primitive
  (doseq [ns    (keys @graph)
          :when (re-find #"^(util|crypto|store)\.|^(store|nostr)$" ns)]
    (is (zero? (get layers ns -1)) (str ns " is under util, crypto or store, or is nostr, so it is a primitive"))))

(deftest the-libraries-stand-alone
  (doseq [[ns reqs] @graph
          :let      [root (library-of ns)]
          :when     root
          req       reqs]
    (is (= root (library-of req)) (str ns " requires " req ", outside " root))))

(deftest the-libraries-are-tested-alone
  (doseq [file  (file-seq (io/file "test"))
          :when (re-find #"\.clj[cs]?$" (.getName ^java.io.File file))
          :let  [form (read-string {:read-cond :allow :features #{:clj}} (slurp file))
                 ns   (str/replace (str (second form)) #"-test$" "")
                 root (library-of ns)]
          :when root
          req   (requires file)]
    (is (= root (library-of req)) (str ns "-test requires " req ", outside " root))))

(deftest a-name-shows-the-high-level
  (doseq [ns (keys @graph)]
    (is (= (str/starts-with? ns "app.") (= 5 (get layers ns)))
        (str ns " is in layer " (get layers ns) ", and app holds layer 5 and nothing else"))))
