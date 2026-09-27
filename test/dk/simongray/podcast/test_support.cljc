(ns dk.simongray.podcast.test-support
  "What the tests share: fixtures inlined from the classpath, the instants
  and the two devices the sync tests put through a store, and on the JVM a
  temporary directory and the feeds of the corpus."
  #?(:cljs (:require-macros [dk.simongray.podcast.test-support]))
  (:require #?(:clj [clojure.java.io :as io])
            #?(:clj [dk.simongray.podcast :as podcast])
            [dk.simongray.podcast.app.player :as player])
  #?(:clj (:import [java.io File]
                   [java.nio.file Files]
                   [java.nio.file.attribute FileAttribute])))

#?(:clj
   (defmacro fixture
     "The classpath resource at `path`, a literal, inlined as a string when
     the test compiles, on both platforms."
     [path]
     (slurp (io/resource path))))

(def t0
  "The instant the sync tests start from; `t1` to `t4` follow an hour apart."
  #inst "2026-01-01T00:00:00Z")

(def t1
  "An hour after `t0`."
  #inst "2026-01-01T01:00:00Z")

(def t2
  "Two hours after `t0`."
  #inst "2026-01-01T02:00:00Z")

(def t3
  "Three hours after `t0`."
  #inst "2026-01-01T03:00:00Z")

(def t4
  "Four hours after `t0`."
  #inst "2026-01-01T04:00:00Z")

(def a
  "The id of an episode: the first of feed-a."
  ["feed-a" "a1"])

(defn phone-state
  "The state of a phone that named itself and subscribed to feed-a at `t0`,
  and was 100 seconds into `a` at `t1`."
  []
  (-> (player/state "phone")
      (player/identify {:name "Phone"} t0)
      (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
      (player/progress a 100 3600 t1)))

(defn laptop-state
  "The state of a laptop that subscribed to feed-b at `t1` and was 900
  seconds into `a` at `t2`, further than the phone."
  []
  (-> (player/state "laptop")
      (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t1)
      (player/progress a 900 3600 t2)))

(defn same?
  "Whether two states agree on all the listener sees: everything but whose
  they are, the registry, whose entries reach the other devices a round
  later, and the clock that follows it."
  [x y]
  (= (dissoc x :device :devices :clock) (dissoc y :device :devices :clock)))

#?(:clj
   (defn with-temp-dir
     "Call `f` with a fresh temporary directory, a `java.io.File`, and delete
     it with everything in it afterwards."
     [f]
     (let [dir (.toFile (Files/createTempDirectory "podcast-test" (make-array FileAttribute 0)))]
       (try
         (f dir)
         (finally
           (doseq [file (reverse (file-seq dir))]
             (io/delete-file file true)))))))

#?(:clj
   (def corpus-feeds
     "The feeds of dev-resources/corpus, which is not in git: `{:name :feed}`,
     the file name and the parsed feed, for every .xml file directly in the
     directory, in name order. Empty when the corpus is absent."
     (delay (->> (.listFiles (io/file "dev-resources/corpus"))
                 (filter #(and (.isFile ^File %) (.endsWith (.getName ^File %) ".xml")))
                 (sort-by #(.getName ^File %))
                 (mapv (fn [^File file] {:name (.getName file) :feed (podcast/parse (slurp file))}))))))
