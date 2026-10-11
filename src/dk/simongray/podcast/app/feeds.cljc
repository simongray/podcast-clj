(ns dk.simongray.podcast.app.feeds
  "The fetched feeds of an app, and keeping them current.

  The feeds are a map by guid. Each entry has the parsed feed, its URL,
  the ETag and Last-Modified of the last fetch, and the time of the next
  check:

      {\"917393e3-…\" {:url          \"https://feeds.podcastindex.org/pc20.xml\"
                     :feed         {…}
                     :etag         \"…\"
                     :last-modified \"…\"
                     :fetched-at   #inst \"…\"
                     :next-check   #inst \"…\"
                     :failures     0
                     :status       200}}

  An entry with only its :url is a feed to fetch for the first time, e.g.
  of a new subscription, and it's due at once."
  (:require [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def default-cadence
  "How many seconds to wait before the next fetch of a feed.

  A feed is fetched when it says that it changes, but at most :max-wait
  later, so that an early or an extra episode shows up the same day. That's
  twelve hours by default, as in AntennaPod. The wait is :silent for a feed
  that gives no time itself, :complete for a feed that says it's complete,
  which :max-wait doesn't cap, and :after-failure for each failure in a
  row, repeating the last wait."
  {:max-wait      43200
   :silent        43200
   :complete      604800
   :after-failure [900 3600 14400 86400]})

(def default-concurrency
  "How many feeds are fetched at once, by default."
  8)

(defn- check-at
  "When to fetch `feed` again, at `at`: when the feed says, or else after
  the wait of `cadence`, but within its :max-wait unless the feed is
  complete, and no earlier than the server's `not-before`."
  [feed at not-before cadence]
  (let [complete? (resolve/complete? feed)
        wait      (if complete? (:complete cadence) (:silent cadence))
        ceiling   (when-not complete?
                    (some->> (:max-wait cadence) (time/later at)))]
    (time/latest (time/earliest (or (schedule/next-check feed at)
                                    (time/later at wait))
                                ceiling)
                 not-before)))

(defn- fetched
  "The entry `old` after a fetch from `url` gave the fresh feed of `result`
  at `at`, with the next check by `cadence`.

  The entry has the feed, its ETag and Last-Modified, and the new URL of a
  feed that moved."
  [old url result at cadence]
  (let [{:keys [feed status etag last-modified not-before websub
                permanent-url new-feed-url]} result]
    (-> (dissoc old :etag :last-modified :websub :status)
        (assoc :url (or permanent-url new-feed-url url)
               :feed feed
               :fetched-at at
               :next-check (check-at feed at not-before cadence)
               :failures 0)
        (cond->
          status        (assoc :status status)
          etag          (assoc :etag etag)
          last-modified (assoc :last-modified last-modified)
          websub        (assoc :websub websub)))))

(defn- unchanged
  "The entry `old` after the server answered 304 at `at` with `result`,
  and its next check by `cadence`."
  [old {:keys [not-before] :as result} at cadence]
  (assoc old
         :fetched-at at
         :next-check (check-at (:feed old) at not-before cadence)
         :failures 0
         :status 304))

(defn- failed
  "The entry `old` after a fetch failed at `at` with `result`, with one
  more failure in a row, and the next check by `cadence`.

  The entry keeps the :status of any answer. The next check waits as
  `cadence` says for that many failures, or until the server's
  :not-before, if that's later."
  [old {:keys [status not-before] :as result} at cadence]
  (let [failures (inc (:failures old 0))
        waits    (:after-failure cadence)
        wait     (nth waits (min (dec failures) (dec (count waits))))]
    (assoc old
           :fetched-at at
           :next-check (time/latest (time/later at wait) not-before)
           :failures failures
           :status status)))

(defn add
  "The `feeds` with the feed in the `result` of fetching `url` at `at`,
  under its guid, and its next check by `cadence`.

  The `result` is that of dk.simongray.podcast/fetch!, and must have a
  :feed. It replaces any entry under the same guid, and the `cadence` goes
  over the defaults."
  ([feeds url result at]
   (add feeds url result at {}))
  ([feeds url result at cadence]
   {:pre [(map? cadence)]}
   (when-not (map? (:feed result))
     (throw (ex-info "A result without a :feed can't be added"
                     {:url url :status (:status result)})))
   (let [cadence   (merge default-cadence cadence)
         feed-guid (guid/feed-guid (:feed result) url)
         old       (get feeds feed-guid {})]
     (assoc feeds feed-guid (fetched old url result at cadence)))))

(defn- slim-item
  "The part of `item` that a slim feed keeps: its guid, date and media."
  [item]
  (let [kept [:rss/guid :rss/pub-date :rss/enclosure
              :podcast/alternate-enclosures :media/contents]]
    (cond-> (select-keys item kept)
      (:media/group item)
      (assoc :media/group (select-keys (:media/group item) [:contents])))))

(defn slim
  "The part of `feed` that keeping it current needs: what says when to
  check it again, and the guid, date and media of each item.

  A device that only syncs and refreshes, such as a server, can keep this
  in place of the whole feed. It gives the same next check, the same new
  episodes and the same names for the gpodder API, but no titles,
  descriptions or the rest."
  [feed]
  (let [kept [:podcast/guid :itunes/complete :podcast/update-frequency
              :rss/ttl :sy/update-period :sy/update-frequency]]
    (-> (select-keys feed kept)
        (assoc :rss/items (mapv slim-item (:rss/items feed))))))

(defn due
  "The guids of the feeds of `feeds` that are due at `at`, the earliest
  first."
  [feeds at]
  (let [due?  (fn [[_ {:keys [next-check]}]]
                (or (nil? next-check)
                    (<= (inst-ms next-check) (inst-ms at))))
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

(defn refresh
  "The `feeds` with `result`, the result of fetching the feed `feed-guid`,
  applied at `at` with `cadence`, and what it changed:

      {:feeds   {…}                      ; with the entry of the feed updated
       :changed [feed-guid]              ; when the fetch gave a fresh feed
       :moved   {feed-guid \"https://…\"}  ; when the feed moved for good
       :report  [diagnostic]}            ; when the fetch failed

  A fresh feed replaces the old one, and a 304 keeps it. After a failure,
  the feed stays and the next check waits longer for each failure in a
  row. A feed that moved keeps its guid, and its subscription should move
  to the URL under :moved. The `cadence` goes over the defaults."
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
         failure (fn [error]
                   (let [entry (failed old result at cadence)]
                     {:feeds  (assoc feeds feed-guid entry)
                      :report [(assoc error :feed-guid feed-guid)]}))]
     (cond
       (:error result)
       (failure (:error result))

       (= 304 (:status result))
       {:feeds (assoc feeds feed-guid (unchanged old result at cadence))}

       (:feed result)
       (let [entry (fetched old url result at cadence)]
         (cond-> {:feeds (assoc feeds feed-guid entry) :changed [feed-guid]}
           (not= url (:url entry)) (assoc :moved {feed-guid (:url entry)})))

       :else
       (failure (diagnostics/fetch-failure {:url    url
                                            :status (:status result)}))))))

(defn- unreachable
  "The result of a fetch of `url` that threw `error`."
  [url error]
  {:url   url
   :error (diagnostics/fetch-failure {:url    url
                                      :reason (async/explain error)})})

(defn- merged
  "The changes of the refreshes so far in `acc`, with those of the `result`
  of one more refresh added."
  [acc {:keys [feeds changed moved report] :as result}]
  (cond-> (assoc acc :feeds feeds)
    changed (update :changed into changed)
    moved   (update :moved merge moved)
    report  (update :report into report)))

(defn refresh!
  "Fetch the feeds of `feeds` that are due with the `opts` below, and apply
  each result.

  Returns a map of :feeds, :changed, :moved and :report for all the
  fetches, in ClojureScript as a promise. Nothing is thrown for what the
  network or a server does. The `opts` are:

  - :at, the instant of the refresh, the time of the clock by default
  - :feed-guids, the feeds to fetch instead of the due ones, e.g. those
    that a Podping names
  - :fetch, in place of dk.simongray.podcast/fetch!
  - :credentials-of, a function from the URL of a private feed to its
    :credentials, so that no password is stored in the feeds and none
    goes to another URL
  - :concurrency, how many feeds to fetch at once, and :cadence
  - :send, :timeout-seconds, :max-bytes, :user-agent and :allow-pred, as
    fetch! takes them"
  ([feeds]
   (refresh! feeds {}))
  ([feeds {:keys [at feed-guids fetch credentials-of concurrency cadence]
           :or   {fetch       podcast/fetch!
                  concurrency default-concurrency
                  cadence     {}}
           :as   opts}]
   {:pre [(map? opts)]}
   (let [at     (or at (time/now))
         guids  (vec (or feed-guids (due feeds at)))
         common (select-keys opts [:send :timeout-seconds :max-bytes
                                   :user-agent :allow-pred])
         fetch1 (fn [feed-guid]
                  (let [{:keys [url etag last-modified]} (get feeds feed-guid)
                        credentials (when credentials-of (credentials-of url))
                        fetch-opts  (cond-> common
                                      etag          (assoc :etag etag)
                                      last-modified (assoc :last-modified
                                                           last-modified)
                                      credentials   (assoc :credentials
                                                           credentials))]
                    (async/then (async/attempt #(fetch url fetch-opts))
                                (fn [{:keys [value error]}]
                                  [feed-guid (if error
                                               (unreachable url error)
                                               value)]))))
         step   (fn [acc [feed-guid result]]
                  (->> (refresh (:feeds acc) feed-guid result at cadence)
                       (merged acc)))]
     (async/then (async/in-groups fetch1 concurrency guids)
                 (fn [results]
                   (reduce step
                           {:feeds feeds :changed [] :moved {} :report []}
                           results))))))

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
