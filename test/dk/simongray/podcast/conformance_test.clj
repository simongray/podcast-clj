(ns dk.simongray.podcast.conformance-test
  "The rules of the parse, the validator and the refresh on real feeds: the
  exhibits of the corpus study, which dev-resources/corpus/study-exhibits.edn
  names for each phenomenon, from the study's sample in
  dev-resources/corpus/study. Neither is in git; see dev/study.clj. When
  they are absent the tests pass vacuously."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.validate :as validate]))

(def exhibits
  "The exhibits of each phenomenon, as maps of the :id and the :url of a
  feed, or none when the study isn't on disk."
  (delay (let [f (io/file "dev-resources/corpus/study-exhibits.edn")]
           (if (.exists f)
             (edn/read-string (slurp f))
             {}))))

(def study-time
  "When the study read its sample, which the dates are judged at."
  #inst "2026-10-11T12:00:00Z")

(defn- feed-of
  "The parsed feed of the exhibit `id`, or nil when its file is absent."
  [id]
  (let [f (io/file "dev-resources/corpus/study" (str id ".xml"))]
    (when (.exists f)
      (:feed (rss/try-parse (slurp f))))))

(defn- diagnosed?
  "Whether validating `feed` under the `requirements`, if any, gives a
  diagnostic with an id of `ids` at a path that ends in `k`, if given."
  ([feed requirements ids]
   (diagnosed? feed requirements ids nil))
  ([feed requirements ids k]
   (some #(and (contains? ids (:id %))
               (or (nil? k) (= k (peek (:path %)))))
         (validate/validate feed (if requirements
                                   {:requirements requirements}
                                   {})))))

(defn- dates
  "The dates of the items of `feed` in milliseconds."
  [feed]
  (keep #(when (inst? (:rss/pub-date %)) (inst-ms (:rss/pub-date %)))
        (:rss/items feed)))

(def rules
  "What the library makes of the feeds of each phenomenon, as a predicate
  of the feed that holds for every exhibit."
  {:repaired-parse
   #(some (comp #{"repair"} namespace :id) (rss/report %))

   :empty
   #(empty? (:rss/items %))

   :paged
   #(some? (resolve/next-page %))

   :thousand-items
   #(= 1000 (count (:rss/items %)))

   ;; the study's identity of an item is its guid or else its enclosure URL
   :duplicate-guids
   #(diagnosed? % nil #{:validate/duplicate-guid
                        :validate/duplicate-enclosure})

   :duplicate-enclosures
   #(diagnosed? % nil #{:validate/duplicate-enclosure})

   :items-without-guid
   #(diagnosed? % :apple #{:validate/required} :rss/guid)

   :no-identity
   #(and (some (comp nil? guid/item-guid) (:rss/items %))
         (diagnosed? % nil #{:validate/missing-enclosure}))

   :guid-not-uuid
   #(and (diagnosed? % nil #{:validate/invalid-guid})
         (not= (:podcast/guid %) (guid/feed-guid % "https://example.com/")))

   :no-enclosure-type
   #(diagnosed? % nil #{:validate/missing-attribute} :type)

   :no-enclosure-length
   #(diagnosed? % nil #{:validate/missing-attribute} :length)

   ;; the study counts a blank type as one that isn't audio or video
   :odd-enclosure-type
   #(or (diagnosed? % nil #{:validate/unknown-media-type})
        (diagnosed? % nil #{:validate/missing-attribute} :type))

   :unknown-category
   #(diagnosed? % nil #{:validate/unknown-category :validate/renamed-category
                        :validate/escaped-category
                        :validate/wrong-subcategory})

   ;; a date that doesn't read is an invalid value rather than a missing one
   :undated
   #(some (fn [{:keys [id key path]}]
            (or (and (= :validate/recommended id) (= :rss/pub-date key))
                (and (= :validate/invalid-value id) (= :rss/pub-date (peek path)))))
          (validate/validate % {:requirements :apple}))

   :future-dates
   #(some (fn [ms] (> ms (inst-ms study-time))) (dates %))

   :not-newest-first
   #(let [ms (dates %)]
      (not (and (seq ms) (apply >= ms))))

   ;; the study found these waiting a month or more, which a refresh caps
   :long-wait
   #(let [entry (first (vals (feeds/add {} "https://example.com/feed"
                                        {:feed %} study-time)))]
      (<= (inst-ms (:next-check entry))
          (+ (inst-ms study-time) (* 1000 (:max-wait feeds/default-cadence)))))

   :complete
   #(resolve/complete? %)})

(deftest the-exhibits-of-the-corpus-study
  (doseq [[phenomenon rule] rules
          {:keys [id]}      (get @exhibits phenomenon)
          :let              [feed (feed-of id)]
          :when             feed]
    (testing (str phenomenon " " id)
      (is (rule feed)))))

(deftest every-phenomenon-has-a-rule
  (is (every? (fn [[phenomenon feeds]]
                (or (empty? feeds) (contains? rules phenomenon)))
              @exhibits)))
