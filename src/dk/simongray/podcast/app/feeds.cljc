(ns dk.simongray.podcast.app.feeds
  "The fetched feeds of an app, and keeping them current.

  The feeds are a map by key, the guid that the feed was first fetched
  under. Each entry has the parsed feed, its URL, the ETag and
  Last-Modified of the last fetch, the guid that the feed gives itself
  now, when the feed last changed, and the time of the next check:

      {\"917393e3-…\" {:url          \"https://feeds.podcastindex.org/pc20.xml\"
                     :feed         {…}
                     :retained     {item-guid {:missed-at #inst \"…\"
                                               :gone?     true}}
                     :etag         \"…\"
                     :last-modified \"…\"
                     :guid         \"917393e3-…\"  ; what the feed says now
                     :changed-at   #inst \"…\"
                     :fetched-at   #inst \"…\"
                     :next-check   #inst \"…\"
                     :failures     0
                     :status       200}}

  The feed keeps the items that a fetch no longer brings back, after the
  fresh ones, since many hosts serve only the newest items. Each has a fact
  under :retained: when a fetch first missed it, and :gone? when the host
  took it out rather than leaving it to age out of a capped feed, as it's
  later than the oldest fresh item, or has no date.

  An entry with only its :url is a feed to fetch for the first time, e.g.
  of a new subscription, and it's due at once. An entry that's :gone? is
  never due, as default-cadence says."
  (:require [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(def default-cadence
  "How many seconds to wait before the next fetch of a feed.

  A feed is fetched when it says that it changes, but at most :max-wait
  later, so that an early or an extra episode shows up the same day. That's
  twelve hours by default, as in AntennaPod. The wait is :silent for a feed
  that gives no time itself, :complete for a feed that says it's complete,
  which :max-wait doesn't cap, and :after-failure for each failure in a
  row, repeating the last wait.

  A feed is gone after its server answers 410 (RFC 9110, section
  15.5.11), or after :gone-after-failures failures in a row, about a month
  at the last wait. It's then fetched only when asked for by name."
  {:max-wait            43200
   :silent              43200
   :complete            604800
   :after-failure       [900 3600 14400 86400]
   :gone-after-failures 30})

(def default-concurrency
  "How many feeds are fetched at once, by default."
  8)

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

(defn- fetched
  "The entry `old` of the feed `feed-guid` after a fetch from `url` gave
  the fresh feed of `result` at `at`, with the next check by `cadence`, and
  whether its feed is the one it had, as a pair.

  The entry has the feed, with `feed-guid` as its key and the items it
  retains, its ETag and Last-Modified, the new URL of a feed that moved,
  and the :guid that the feed gives itself there. Its :changed-at is `at`
  unless the feed is the one it had, since a server without validators
  answers 200 for a feed that didn't change."
  [old feed-guid url result at cadence]
  (let [{:keys [status etag last-modified not-before websub
                permanent-url new-feed-url]} result
        fresh           (guid/with-key (:feed result) feed-guid)
        [feed retained] (with-retained old fresh at)
        url             (or permanent-url new-feed-url url)
        same?           (and (= feed (:feed old))
                             (= retained (:retained old {})))]
    [(-> (dissoc old :etag :last-modified :websub :status :retained :gone?)
         (assoc :url url
                :guid (guid/feed-guid fresh url)
                :feed feed
                :changed-at (if same? (:changed-at old at) at)
                :fetched-at at
                :next-check (check-at fresh at not-before cadence)
                :failures 0)
         (cond->
           (seq retained) (assoc :retained retained)
           status         (assoc :status status)
           etag           (assoc :etag etag)
           last-modified  (assoc :last-modified last-modified)
           websub         (assoc :websub websub)))
     same?]))

(defn- unchanged
  "The entry `old` after the server answered 304 at `at` with `result`,
  and its next check by `cadence`."
  [old {:keys [not-before] :as result} at cadence]
  (-> (dissoc old :gone?)
      (assoc :fetched-at at
             :next-check (check-at (:feed old) at not-before cadence)
             :failures 0
             :status 304)))

(defn- failed
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

(defn add
  "The `feeds` with the feed in the `result` of fetching `url` at `at`,
  under its guid, and its next check by `cadence`.

  The `result` is that of dk.simongray.podcast/fetch!, and must have a
  :feed. An entry under the same guid takes the feed as refresh would,
  and the `cadence` goes over the defaults."
  ([feeds url result at]
   (add feeds url result at {}))
  ([feeds url result at cadence]
   {:pre [(map? cadence)]}
   (when-not (map? (:feed result))
     (throw (ex-info "A result without a :feed can't be added"
                     {:url url :status (:status result)})))
   (let [cadence   (merge default-cadence cadence)
         feed-guid (guid/feed-key (:feed result) url)
         old       (get feeds feed-guid {})]
     (assoc feeds feed-guid
            (first (fetched old feed-guid url result at cadence))))))

(defn- slim-item
  "The part of `item` that a slim feed keeps: its guid, date, title and
  media."
  [item]
  (let [kept [:rss/guid :rss/pub-date :rss/title :itunes/title :rss/enclosure
              :podcast/alternate-enclosures :media/contents]]
    (cond-> (select-keys item kept)
      (:media/group item)
      (assoc :media/group (select-keys (:media/group item) [:contents])))))

(defn slim
  "The part of `feed` that keeping it current needs: what says when to
  check it again, and the guid, date, title and media of each item.

  A device that only syncs and refreshes, such as a server, can keep this
  in place of the whole feed. It gives the same next check, the same new
  and renamed episodes and the same names for the gpodder API, but no
  descriptions or the rest. It keeps the key of the feed's metadata, as
  guid/with-key gives it."
  [feed]
  (let [kept [:podcast/guid :itunes/complete :podcast/update-frequency
              :rss/ttl :sy/update-period :sy/update-frequency]]
    (-> (select-keys feed kept)
        (assoc :rss/items (mapv slim-item (:rss/items feed))))))

(defn due
  "The guids of the feeds of `feeds` that are due at `at`, the earliest
  first. A feed that's gone is never due."
  [feeds at]
  (let [due?  (fn [[_ {:keys [next-check gone?]}]]
                (and (not gone?)
                     (or (nil? next-check)
                         (<= (inst-ms next-check) (inst-ms at)))))
        order (fn [[_ {:keys [next-check]}]]
                (if next-check (inst-ms next-check) 0))]
    (->> feeds
         (filter due?)
         (sort-by order)
         (mapv key))))

(defn affected
  "The guids of the feeds of `feeds` that the Podping `pings` name, to
  fetch now whatever their next check."
  [feeds pings]
  (let [by-url (into {}
                     (map (fn [[feed-guid {:keys [url]}]] [url feed-guid]))
                     feeds)]
    (mapv by-url (podping/affected pings (keys by-url)))))

(defn pinged
  "Why the Podping `pings` name each feed of `feeds` that they name, by
  guid, as the distinct pairs of the :reason and the :medium of its pings,
  e.g. {feed-guid #{[\"live\" \"podcast\"]}} for a live stream that
  started."
  [feeds pings]
  ;; a ping names a feed by any URL with the podcast:guid of its own, as
  ;; podping/affected matches them
  (let [by-guid (into {}
                      (keep (fn [[feed-guid {:keys [url]}]]
                              (when url
                                [(guid/from-url url) feed-guid])))
                      feeds)
        note    (fn [why acc iri]
                  (if-let [feed-guid (by-guid (guid/from-url iri))]
                    (update acc feed-guid (fnil conj #{}) why)
                    acc))]
    (reduce (fn [acc {:keys [reason medium iris]}]
              (reduce (partial note [reason medium]) acc iris))
            {}
            pings)))

(defn refresh
  "The `feeds` with `result`, the result of fetching the feed `feed-guid`,
  applied at `at` with `cadence`, and what it changed:

      {:feeds   {…}                      ; with the entry of the feed updated
       :changed [feed-guid]              ; when the fetch gave a fresh feed
       :failed  [feed-guid]              ; when the fetch failed
       :moved   {feed-guid \"https://…\"}  ; when the feed moved for good
       :report  [diagnostic]}            ; why it failed, and other news

  A fresh feed replaces the old one, but for the items that it lacks,
  which stay after its own, and a 304 keeps it. A fresh feed that's the
  one the entry had, from a server without ETag or Last-Modified, changed
  nothing. After a failure, the feed stays and the next check waits longer
  for each failure in a row, and the report has :fetch/gone once the feed
  is gone, as default-cadence says. A fresh feed without items, where the
  old one had some, counts as a failure, with :fetch/empty-feed, since
  hosts serve such a feed during an outage. A feed that moved keeps its
  guid, and its subscription should move to the URL under :moved. The
  :move-report of `result`, e.g. a move that refresh! couldn't verify,
  goes into the report. The `cadence` goes over the defaults."
  ([feeds feed-guid result at]
   (refresh feeds feed-guid result at {}))
  ([feeds feed-guid result at cadence]
   {:pre [(map? cadence)]}
   (let [cadence (merge default-cadence cadence)
         old     (get feeds feed-guid)
         _       (when-not old
                   (throw (ex-info (str "No feed of the guid " feed-guid)
                                   {:feed-guid feed-guid})))
         url     (:url old)
         noted   #(assoc % :feed-guid feed-guid)
         failure (fn [error]
                   (let [entry (failed old result at cadence)
                         gone  (when (and (:gone? entry) (not (:gone? old)))
                                 (diagnostics/describe
                                  {:id       :fetch/gone
                                   :url      url
                                   :status   (:status entry)
                                   :failures (:failures entry)}))]
                     {:feeds  (assoc feeds feed-guid entry)
                      :failed [feed-guid]
                      :report (mapv noted (cond-> [error] gone (conj gone)))}))]
     (cond
       (:error result)
       (failure (:error result))

       (= 304 (:status result))
       {:feeds (assoc feeds feed-guid (unchanged old result at cadence))}

       (and (:feed result)
            (empty? (:rss/items (:feed result)))
            (seq (get-in old [:feed :rss/items])))
       (failure (diagnostics/describe {:id :fetch/empty-feed :url url}))

       (:feed result)
       (let [[entry same?] (fetched old feed-guid url result at cadence)
             report        (:move-report result)]
         (cond-> {:feeds (assoc feeds feed-guid entry)}
           (not same?)             (assoc :changed [feed-guid])
           (not= url (:url entry)) (assoc :moved {feed-guid (:url entry)})
           report                  (assoc :report [(noted report)])))

       :else
       (failure (diagnostics/fetch-failure {:url    url
                                            :status (:status result)}))))))

(defn- unreachable
  "The result of a fetch of `url` that threw `error`, with the :type of the
  failure when wary-fetch gives one."
  [url error]
  {:url   url
   :error (diagnostics/fetch-failure (assoc (http/failure error) :url url))})

(defn- verified-move
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

(defn connection-failed?
  "Whether the fetch `result` failed before any server answered: the name
  of its host wasn't found, the host couldn't be reached, or the secure
  connection failed."
  [result]
  (contains? #{::http/unresolved ::http/unreachable ::http/tls}
             (get-in result [:error :type])))

(defn- offline?
  "Whether the `results` of a round of fetches, pairs of a feed guid and a
  result, say that the device is offline: each failed before any server
  answered, at more than one host, since one host can be down by itself."
  [results]
  (and (every? (comp connection-failed? second) results)
       (< 1 (count (into #{} (map (comp url/host :url second)) results)))))

(defn- offline
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

(defn- merged
  "The changes of the refreshes so far in `acc`, with those of the `result`
  of one more refresh added."
  [acc {:keys [feeds changed failed moved report] :as result}]
  (cond-> (assoc acc :feeds feeds)
    changed (update :changed into changed)
    failed  (update :failed into failed)
    moved   (update :moved merge moved)
    report  (update :report into report)))

(defn- in-time
  "The results of `f` on each of `xs` as a vector, `n` calls at a time, in
  a future or a promise, as async/in-groups gives them, but starting no
  group once `over?` says that the time is up."
  [f n xs over?]
  (reduce (fn [acc group]
            (async/then acc (fn [done]
                              (if (over?)
                                done
                                (async/then (async/all f group)
                                            #(into done %))))))
          []
          (partition-all n xs)))

(defn refresh!
  "Fetch the feeds of `feeds` that are due with the `opts` below, and apply
  each result.

  Returns a map of :feeds, :changed, :failed, :moved and :report for all
  the fetches, as refresh gives them, :checked, the guids of the feeds
  fetched, and :left, those of the feeds that stay due, in ClojureScript
  as a promise. Nothing is thrown for what the network or a server does.
  The `opts` are:

  - :at, the instant of the refresh, the time of the clock by default
  - :feed-guids, the feeds to fetch instead of the due ones, e.g. those
    that a Podping names
  - :fetch, in place of dk.simongray.podcast/fetch!
  - :credentials-of, a function from the URL of a private feed to its
    :credentials, so that no password is stored in the feeds and none
    goes to another URL
  - :concurrency, how many feeds to fetch at once, default-concurrency by
    default, and :cadence
  - :max-pages, the most pages of a paged feed to read at its first fetch,
    one by default, since the later fetches keep what it read
  - :online?, false when the device has no network, so that nothing is
    fetched
  - :max-feeds, the most feeds to fetch, the earliest due first
  - :max-seconds, a deadline: no group of fetches starts once that many
    seconds have passed by the clock of :now-fn, util.time/now by default,
    so a refresh ends at most a :timeout-seconds later, e.g. within the
    time that a platform gives a task in the background
  - :on-feed-fn, a function called as each fetch ends, with a map of the
    :feed-guid, its :status or the :error, and how many fetches are :done
    of the :total, e.g. to show progress. On the JVM, fetches that run at
    once can call it at once.
  - :send, :timeout-seconds, :max-bytes, :user-agent and :allow-pred, as
    fetch! takes them

  The feeds that :max-feeds or :max-seconds leave out stay due, for the
  next refresh. A round in which every fetch failed before a server
  answered, at more than one host, counts as offline too. Offline, every
  feed stays as it was, so that none counts it as a failure and each is
  due at the next refresh, and the report has :refresh/offline.

  A move that a feed declares, e.g. by itunes:new-feed-url, is followed
  only when a feed of the same show answers at the new URL. Otherwise the
  feed stays where it is, and the report has :fetch/move-unverified."
  ([feeds]
   (refresh! feeds {}))
  ([feeds {:keys [at feed-guids fetch credentials-of concurrency cadence
                  max-pages online? max-feeds max-seconds on-feed-fn now-fn]
           :or   {fetch       podcast/fetch!
                  concurrency default-concurrency
                  cadence     {}
                  now-fn      time/now}
           :as   opts}]
   {:pre [(map? opts)]}
   (let [at     (or at (now-fn))
         start  (now-fn)
         wanted (vec (or feed-guids (due feeds at)))
         guids  (cond-> wanted
                  max-feeds (subvec 0 (min max-feeds (count wanted))))
         over?  #(and max-seconds
                      (<= (* 1000 max-seconds)
                          (- (inst-ms (now-fn)) (inst-ms start))))
         done   (atom 0)
         common (select-keys opts [:send :timeout-seconds :max-bytes
                                   :user-agent :allow-pred])
         private (fn [url]
                   (let [credentials (when credentials-of (credentials-of url))]
                     (cond-> common
                       credentials (assoc :credentials credentials))))
         fetch1 (fn [feed-guid]
                  (let [{:keys [url etag last-modified feed]}
                        (get feeds feed-guid)

                        fetch-opts (cond-> (private url)
                                     etag          (assoc :etag etag)
                                     last-modified (assoc :last-modified
                                                          last-modified)
                                     (and max-pages (not feed))
                                     (assoc :max-pages max-pages))]
                    (async/then
                     (async/attempt #(fetch url fetch-opts))
                     (fn [{:keys [value error]}]
                       (let [new-url (:new-feed-url value)]
                         (cond
                           error
                           [feed-guid (unreachable url error)]

                           ;; a declared move goes where a feed answers
                           (and new-url (not= new-url url))
                           (async/then
                            (async/attempt #(fetch new-url (private new-url)))
                            #(vector feed-guid (verified-move url value %)))

                           :else
                           [feed-guid value]))))))
         told   (fn [feed-guid]
                  (async/then
                   (fetch1 feed-guid)
                   (fn [[_ {:keys [status error]} :as pair]]
                     (when on-feed-fn
                       (on-feed-fn (cond-> {:feed-guid feed-guid
                                            :done      (swap! done inc)
                                            :total     (count guids)}
                                     status (assoc :status status)
                                     error  (assoc :error error))))
                     pair)))
         step   (fn [acc [feed-guid result]]
                  (->> (refresh (:feeds acc) feed-guid result at cadence)
                       (merged acc)))
         round  (fn [results]
                  (if (offline? results)
                    (offline feeds (get-in (first results) [1 :error :reason]))
                    (reduce step
                            {:feeds   feeds
                             :checked (mapv first results)
                             :changed []
                             :failed  []
                             :moved   {}
                             :report  []}
                            results)))
         left   #(assoc % :left (vec (remove (set (:checked %)) wanted)))]
     (if (false? online?)
       (async/then nil (fn [_] (left (offline feeds nil))))
       (async/then (in-time told concurrency guids over?)
                   (comp left round))))))

#?(:clj
   (comment
     (def at (java.util.Date.))
     (def url "https://feeds.podcastindex.org/pc20.xml")
     (def feeds (add {} url (podcast/fetch! url {:max-pages 2}) at))

     (update-vals feeds #(select-keys % [:url :next-check :etag]))
     (due feeds at)
     (due feeds (time/later at 604800))

     ;; a day later: a 304 or a fresh feed
     (def r (refresh! feeds {:at (time/later at 86400)}))
     (select-keys r [:changed :moved :report])
     (get-in r [:feeds (first (keys feeds)) :status])
     #_.))
