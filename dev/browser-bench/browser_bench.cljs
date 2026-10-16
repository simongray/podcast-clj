(ns browser-bench
  "Timings of the parse of a feed in a browser, stage by stage, which
  dev/browser-bench/run.mjs takes in a headless Chrome over feeds of the
  corpus.

  Each stage runs a few times to warm up the JIT, and its time is the
  median of the runs after those. The first parses of a page are timed on
  their own, since a listener's first feed is parsed cold."
  (:require [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.quirks :as quirks]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.qname-hiccup :as xml]))

(defonce ^{:doc "The text of each feed fetched so far, by name."}
  texts
  (atom {}))

(defonce ^{:doc "The stages of each feed prepared so far, by name."}
  prepared
  (atom {}))

(defn median
  "The median of the numbers `xs`."
  [xs]
  (let [sorted (vec (sort xs))
        n      (count sorted)]
    (if (odd? n)
      (sorted (quot n 2))
      (/ (+ (sorted (dec (quot n 2))) (sorted (quot n 2))) 2))))

(defn elapsed
  "The milliseconds that a call of `f` takes."
  [f]
  (let [start (js/performance.now)]
    (f)
    (- (js/performance.now) start)))

(defn timing
  "The median milliseconds of `runs` calls of `f`, after `warmup` calls."
  [f warmup runs]
  (dotimes [_ warmup] (f))
  (median (repeatedly runs #(elapsed f))))

(defn stages
  "The stages of the parse of the feed text `s` as pairs of a name and a
  function of no arguments, each given what the stages before it return."
  [s]
  (let [raw      (xml/parse-raw s)
        resolved (xml/resolve-namespaces raw quirks/uri->alias)
        tree     (rss/parse-tree s)]
    [["DOMParser" #(.parseFromString (js/DOMParser.) s "text/xml")]
     ["xml/parse-raw" #(xml/parse-raw s)]
     ["xml/resolve-namespaces" #(xml/resolve-namespaces raw quirks/uri->alias)]
     ["quirks/namespace-variants" #(quirks/namespace-variants raw)]
     ["quirks/normalize" #(quirks/normalize resolved)]
     ["quirks/report" #(quirks/report tree)]
     ["rss/parse-tree, strict" #(rss/parse-tree s {:quirks? false})]
     ["rss/parse-tree" #(rss/parse-tree s)]
     ["feed/parse" #(feed/parse tree)]
     ["rss/parse" #(rss/parse s)]]))

(defn fetch-text!
  "A promise of the text of the corpus feed `name`, fetched once."
  [name]
  (if-let [s (get @texts name)]
    (js/Promise.resolve s)
    (-> (js/fetch (str "/corpus/" name ".xml"))
        (.then (fn [response]
                 (if (.-ok response)
                   (.text response)
                   (throw (js/Error. (str "No feed " name " in the corpus"))))))
        (.then (fn [s] (swap! texts assoc name s) s)))))

(defn prepare!
  "The stages of the feed `name` with the text `s`, prepared once."
  [name s]
  (or (get @prepared name)
      (get (swap! prepared assoc name (stages s)) name)))

(defn measure
  "The timings of the feed `name` with the text `s`: its size, its items,
  and the median milliseconds of each stage, of `runs` after `warmup`."
  [name s warmup runs]
  {:feed   name
   :bytes  (count s)
   :items  (count (:rss/items (rss/parse s)))
   :stages (vec (for [[label f] (prepare! name s)]
                  [label (timing f warmup runs)]))})

(defn ^:export run
  "A promise of the timings of the corpus feeds `names`, a JS array, as
  JSON, with `warmup` runs before the `runs` of each stage."
  [names warmup runs]
  (-> (js/Promise.all (map fetch-text! names))
      (.then (fn [ss]
               (js/JSON.stringify
                (clj->js (mapv #(measure %1 %2 warmup runs) names ss)))))))

(defn ^:export repeat-stage
  "A promise of nil, once the stage `label` of the corpus feed `name` has
  run `n` times, e.g. while the profiler of the browser records."
  [name label n]
  (-> (fetch-text! name)
      (.then (fn [s]
               (let [f (some (fn [[l f]] (when (= label l) f))
                             (prepare! name s))]
                 (dotimes [_ n] (f)))))))

(defn ^:export first-runs
  "A promise of the milliseconds of the first `n` calls of rss/parse on the
  corpus feed `name`, as JSON. In a fresh page, the first is a cold parse."
  [name n]
  (-> (fetch-text! name)
      (.then (fn [s]
               (let [parse #(rss/parse s)]
                 (js/JSON.stringify
                  (clj->js (vec (repeatedly n #(elapsed parse))))))))))
