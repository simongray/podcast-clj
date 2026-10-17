(ns ^:no-doc dk.simongray.podcast.app.feeds.impl
  "The internals of app.feeds: how a fetch, a 304 or a failure changes the
  entry of a feed and its next check, and the rounds of fetches of
  refresh!."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

;; The podcast namespace, podcast:liveItem: a status of pending, live or
;; ended, and a start and an end
(defn- live-due
  "The first start or end of a live item of `feed` after `at` that isn't
  over, or nil, when the feed most likely changes."
  [feed at]
  (->> (:podcast/live-items feed)
       (remove #(= "ended" (some-> (:status %) str/lower-case)))
       (mapcat (juxt :start :end))
       (filter #(and (inst? %) (> (inst-ms %) (inst-ms at))))
       (apply time/earliest)))

(defn- check-at
  "When to fetch `feed` again, at `at`: when the feed says, or else after
  the wait of `cadence`, but within its :max-wait unless the feed is
  complete, and no earlier than the server's `not-before`. The start and
  the end of a live item to come bring it forward."
  [feed at not-before cadence]
  (let [complete? (resolve/complete? feed)
        wait      (if complete? (:complete cadence) (:silent cadence))
        ceiling   (when-not complete?
                    (some->> (:max-wait cadence) (time/later at)))]
    (time/latest (time/earliest (or (schedule/next-check feed at)
                                    (time/later at wait))
                                ceiling
                                (live-due feed at))
                 not-before)))

(defn- oldest
  "The earliest date of the items of `feed` in milliseconds, or nil when
  none has a date."
  [feed]
  (let [ms (keep #(some-> (resolve/published %) inst-ms) (:rss/items feed))]
    (when (seq ms)
      (apply min ms))))

(defn- gone?
  "Whether the host took `item` out of its feed rather than leaving it to
  age out: whether it's later than `oldest`, the earliest date of the fresh
  items, or has no date."
  [oldest item]
  (if-let [d (resolve/published item)]
    (boolean (and oldest (< oldest (inst-ms d))))
    true))

;; RSS 2.0 sets no limit on the items of a channel, and RFC 5005, sections 3
;; and 4, serves the older ones on other pages or nowhere
(defn- with-retained
  "The `fresh` feed with the items of the `old` entry that it lacks after
  its own, and the facts of those items at `at`, as a pair.

  The items that it lacks are those that feed/diff gives as removed, so an
  item that's back under a new guid isn't kept, and neither is one without
  a guid or an enclosure URL, which can't be told from another."
  [old fresh at]
  (let [missing (:removed (feed/diff (:feed old) fresh))
        since   (oldest fresh)
        fact    (fn [item]
                  (let [g (guid/item-guid item)]
                    [g (cond-> (get-in old [:retained g] {:missed-at at})
                         (gone? since item) (assoc :gone? true))]))]
    [(cond-> fresh
       (seq missing) (update :rss/items #(into (vec %) missing)))
     (into {} (map fact) missing)]))

(def head-items
  "How many of the newest items of a feed its start must have, as the feed
  had them, to show nothing new."
  3)

(defn same-start?
  "Whether the start of a feed, `head`, has the newest items of `feed`, by
  their guids, in the same order."
  [feed head]
  (let [guids  #(map guid/item-guid (take head-items (:rss/items %)))
        newest (guids head)]
    (boolean (and (seq newest)
                  (= newest (take (count newest) (guids feed)))))))

(defn fetched
  "The entry `old` of the feed `feed-guid` after a fetch from `url` gave
  the fresh feed of `result` at `at`, with the next check by `cadence`, and
  whether its feed is the one it had, as a pair.

  The entry has the feed, with `feed-guid` as its key and the items it
  retains, its ETag and Last-Modified, the new URL of a feed that moved,
  and the :guid that the feed gives itself there. Its :changed-at is `at`
  unless the feed is the one it had, since a server without validators
  answers 200 for a feed that didn't change, and its :whole-at is `at`. A
  server that answered so to the validators of the entry, with the same
  newest items, is marked as one that doesn't :validate?, since a feed
  that stamps the time of each answer differs every time."
  [old feed-guid url result at cadence]
  (let [{:keys [status etag last-modified not-before websub
                permanent-url new-feed-url]} result
        fresh           (guid/with-key (:feed result) feed-guid)
        [feed retained] (with-retained old fresh at)
        url             (or permanent-url new-feed-url url)
        same?           (and (= feed (:feed old))
                             (= retained (:retained old {})))
        asked?          (boolean (or (:etag old) (:last-modified old)))
        nothing-new?    (same-start? (:feed old) fresh)]
    [(-> (dissoc old :etag :last-modified :websub :status :retained :gone?)
         (assoc :url url
                :guid (guid/feed-guid fresh url)
                :feed feed
                :changed-at (if same? (:changed-at old at) at)
                :fetched-at at
                :whole-at at
                :next-check (check-at fresh at not-before cadence)
                :failures 0)
         (cond->
           (seq retained)     (assoc :retained retained)
           status             (assoc :status status)
           etag               (assoc :etag etag)
           last-modified      (assoc :last-modified last-modified)
           websub             (assoc :websub websub)
           (and asked? nothing-new?) (assoc :validates? false)))
     same?]))

(defn unchanged
  "The entry `old` after the server answered 304 at `at` with `result`,
  and its next check by `cadence`. A server that answered so validates,
  but the start of a feed that showed nothing new, a result that's
  :head?, says nothing of that."
  [old {:keys [not-before head?] :as result} at cadence]
  (-> (dissoc old :gone?)
      (assoc :fetched-at at
             :next-check (check-at (:feed old) at not-before cadence)
             :failures 0)
      (cond->
        head?       (assoc :head-at at)
        (not head?) (assoc :status 304 :validates? true))))

(defn failed
  "The entry `old` after a fetch failed at `at` with `result`, with one
  more failure in a row, and the next check by `cadence`.

  The entry keeps the :status of any answer. The next check waits as
  `cadence` says for that many failures, or until the server's
  :not-before, if that's later. The entry is :gone? after a 410, or after
  as many failures as `cadence` says."
  [old {:keys [status not-before] :as result} at cadence]
  (let [failures (inc (:failures old 0))
        waits    (:after-failure cadence)
        wait     (nth waits (min (dec failures) (dec (count waits))))
        gone?    (or (= 410 status)
                     (<= (:gone-after-failures cadence ##Inf) failures))]
    (cond-> (assoc old
                   :fetched-at at
                   :next-check (time/latest (time/later at wait) not-before)
                   :failures failures
                   :status status)
      gone? (assoc :gone? true))))

(defn with-pushed
  "The feed of the `old` entry updated by the `pushed` feed, whose details
  replace the old ones. Its items come first, then those of the last fetch
  that it doesn't have. The retained items stay retained."
  [old pushed]
  (let [ids   (into #{} (map guid/item-guid) (:rss/items pushed))
        kept? (fn [item]
                (let [g (guid/item-guid item)]
                  (not (or (contains? ids g)
                           (contains? (:retained old) g)))))
        items (into (vec (:rss/items pushed))
                    (filter kept?)
                    (get-in old [:feed :rss/items]))]
    (-> (merge (:feed old) pushed)
        (with-meta (meta pushed))
        (assoc :rss/items items))))

(defn verified-move
  "The `result` of the fetch of the feed at `url`, with the move that it
  declares kept when `attempt`, the fetch of the new URL, gave a feed of
  the same show, and else without it but with a :move-report.

  A feed of the same show answers at the new URL, with the podcast:guid of
  the feed if the feed has one."
  [url result {:keys [value error] :as attempt}]
  (let [new-url  (:new-feed-url result)
        declared (:podcast/guid (:feed result))
        moved    (:feed value)
        reason   (cond
                   error
                   (async/explain error)

                   (:error value)
                   (or (get-in value [:error :reason])
                       (str "the new URL answered "
                            (get-in value [:error :status])))

                   (nil? moved)
                   "the new URL gave no feed"

                   (and (guid/guid? declared)
                        (not= (str/lower-case declared)
                              (some-> (:podcast/guid moved) str/lower-case)))
                   "the feed there gives another podcast:guid")]
    (if reason
      (-> (dissoc result :new-feed-url)
          (assoc :move-report
                 (diagnostics/describe {:id      :fetch/move-unverified
                                        :url     url
                                        :new-url new-url
                                        :reason  reason})))
      result)))

(defn offline
  "The result of a refresh of `feeds` while the device is offline, which
  changes none of them, with the :reason of a failure that said so."
  [feeds reason]
  {:feeds   feeds
   :checked []
   :changed []
   :failed  []
   :moved   {}
   :report  [(diagnostics/describe (cond-> {:id :refresh/offline}
                                     reason (assoc :reason reason)))]})

(defn merged
  "The changes of the refreshes so far in `acc`, with those of the `result`
  of one more refresh added."
  [acc {:keys [feeds changed failed moved report] :as result}]
  (cond-> (assoc acc :feeds feeds)
    changed (update :changed into changed)
    failed  (update :failed into failed)
    moved   (update :moved merge moved)
    report  (update :report into report)))

(defn in-time
  "The results of `f` on each of `xs` as a vector, `n` calls at a time, in
  a future or a promise, as async/in-groups gives them, but starting no
  group once `over?`, a function of the results so far, says to stop."
  [f n xs over?]
  (reduce (fn [acc group]
            (async/then acc (fn [done]
                              (if (over? done)
                                done
                                (async/then (async/all f group)
                                            #(into done %))))))
          []
          (partition-all n xs)))
