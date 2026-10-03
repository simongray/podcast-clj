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

  The new episodes of a changed feed are found by
  dk.simongray.podcast.profile/refresh."
  (:require [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.time :as time]))

(def default-cadence
  "How many seconds to wait before the next fetch of a feed that gives no
  time itself.

  The wait is :silent for most feeds, :complete for a feed that says it's
  complete, and :after-failure for each failure in a row, repeating the
  last wait."
  {:silent        86400
   :complete      604800
   :after-failure [900 3600 14400 86400]})

(def default-parallel
  "How many feeds refresh! fetches at once."
  8)

(defn- later
  [now seconds]
  (time/->inst (+ (inst-ms now) (* 1000 seconds))))

(defn- check-at
  "When to fetch `feed` again, at `now`: as resolve/next-check says with
  the server's `not-before`, or else after the wait of `cadence`."
  [feed now not-before cadence]
  (or (resolve/next-check feed now {:not-before not-before})
      (later now (if (resolve/complete? feed) (:complete cadence) (:silent cadence)))))

(defn- fetched
  "The entry `old` after a fetch from `url` gave the fresh feed of `result`
  at `now`, with the next check by `cadence`.

  The entry has the feed, its ETag and Last-Modified, and the new URL of a
  feed that moved."
  [old url result now cadence]
  (let [{:keys [feed status etag last-modified not-before websub permanent-url new-feed-url]} result]
    (-> (dissoc old :etag :last-modified :websub :status)
        (assoc :url (or permanent-url new-feed-url url)
               :feed feed
               :fetched-at now
               :next-check (check-at feed now not-before cadence)
               :failures 0)
        (cond->
          status        (assoc :status status)
          etag          (assoc :etag etag)
          last-modified (assoc :last-modified last-modified)
          websub        (assoc :websub websub)))))

(defn- unchanged
  "The entry `old` after the server answered 304 at `now` with `result`,
  and its next check by `cadence`."
  [old {:keys [not-before] :as result} now cadence]
  (assoc old
         :fetched-at now
         :next-check (check-at (:feed old) now not-before cadence)
         :failures 0
         :status 304))

(defn- failed
  "The entry `old` after a fetch failed at `now` with `result`, with one
  more failure in a row, and the next check by `cadence`.

  The entry keeps the :status of any answer. The next check waits as
  `cadence` says for that many failures, or until the server's
  :not-before, if that's later."
  [old {:keys [status not-before] :as result} now cadence]
  (let [failures (inc (:failures old 0))
        waits    (:after-failure cadence)
        wait     (nth waits (min (dec failures) (dec (count waits))))
        at       (later now wait)]
    (assoc old
           :fetched-at now
           :next-check (if (and not-before (> (inst-ms not-before) (inst-ms at))) not-before at)
           :failures failures
           :status status)))

(defn add
  "The `feeds` with the feed in the `result` of fetching `url` at `now`,
  under its guid, and its next check by `cadence`.

  The `result` is that of dk.simongray.podcast/fetch!, and must have a
  :feed. It replaces any entry under the same guid, and the `cadence`
  defaults to default-cadence."
  ([feeds url result now]
   (add feeds url result now {}))
  ([feeds url result now cadence]
   {:pre [(map? (:feed result)) (map? cadence)]}
   (let [cadence (merge default-cadence cadence)
         guid    (feed/feed-guid (:feed result) url)]
     (assoc feeds guid (fetched (get feeds guid {}) url result now cadence)))))

(defn due
  "The guids of the feeds of `feeds` that are due at `now`, the earliest
  first."
  [feeds now]
  (->> feeds
       (filter (fn [[_ {:keys [next-check]}]]
                 (or (nil? next-check) (<= (inst-ms next-check) (inst-ms now)))))
       (sort-by (fn [[_ {:keys [next-check]}]] (if next-check (inst-ms next-check) 0)))
       (mapv key)))

(defn affected
  "The guids of the feeds of `feeds` that the Podping `pings` name, to
  fetch now whatever their next check."
  [feeds pings]
  (let [by-url (into {} (map (fn [[guid {:keys [url]}]] [url guid])) feeds)]
    (mapv by-url (podping/affected pings (keys by-url)))))

(defn refresh
  "The `feeds` with `result`, the result of fetching the feed `guid`,
  applied at `now` with `cadence`, and what it changed:

      {:feeds   {…}                 ; with the entry of the feed updated
       :changed [guid]              ; when the fetch gave a fresh feed
       :moved   {guid \"https://…\"}  ; when the feed moved for good
       :report  [diagnostic]}       ; when the fetch failed

  A fresh feed replaces the old one, and a 304 keeps it. After a failure,
  the feed stays and the next check waits longer for each failure in a
  row. A feed that moved keeps its guid, and its subscription should move
  to the URL under :moved. The `cadence` defaults to default-cadence."
  ([feeds guid result now]
   (refresh feeds guid result now {}))
  ([feeds guid result now cadence]
   {:pre [(map? cadence)]}
   (let [cadence (merge default-cadence cadence)
         old     (get feeds guid)
         _       (when-not old (throw (ex-info (str "No feed of the guid " guid) {:guid guid})))
         url     (:url old)
         failure (fn [error]
                   {:feeds  (assoc feeds guid (failed old result now cadence))
                    :report [(assoc error :feed-guid guid)]})]
     (cond
       (:error result)
       (failure (:error result))

       (= 304 (:status result))
       {:feeds (assoc feeds guid (unchanged old result now cadence))}

       (:feed result)
       (let [entry (fetched old url result now cadence)]
         (cond-> {:feeds (assoc feeds guid entry) :changed [guid]}
           (not= url (:url entry)) (assoc :moved {guid (:url entry)})))

       :else
       (failure (diagnostics/describe {:id :fetch/status :url url :status (:status result)}))))))

(defn- merged
  "The changes of the refreshes so far in `acc`, with those of the `result`
  of one more refresh added."
  [acc {:keys [feeds changed moved report] :as result}]
  (cond-> (assoc acc :feeds feeds)
    changed (update :changed into changed)
    moved   (update :moved merge moved)
    report  (update :report into report)))

(defn refresh!
  "Fetch the feeds of `feeds` that are due at `now` with the `opts` below,
  and apply each result as refresh does.

  Returns a map of :feeds, :changed, :moved and :report for all the
  fetches, in ClojureScript as a promise. Nothing is thrown for what the
  network or a server does. The `opts` are:

  - :guids, the feeds to fetch instead of the due ones, e.g.
    (affected feeds pings) after a Podping
  - :fetch, in place of dk.simongray.podcast/fetch!
  - :credentials-of, a function from the URL of a private feed to its
    :credentials, so that no password is stored in the feeds and none
    goes to another URL
  - :parallel, how many feeds to fetch at once, and :cadence
  - :send, :timeout-seconds, :max-bytes, :user-agent and :allow?, as
    fetch! takes them"
  ([feeds now]
   (refresh! feeds now {}))
  ([feeds now {:keys [guids fetch credentials-of parallel cadence]
               :or   {fetch    podcast/fetch!
                      parallel default-parallel
                      cadence  {}}
               :as   opts}]
   {:pre [(map? opts)]}
   (let [guids  (vec (or guids (due feeds now)))
         common (select-keys opts [:send :timeout-seconds :max-bytes :user-agent :allow?])
         fetch1 (fn [guid]
                  (let [{:keys [url etag last-modified]} (get feeds guid)
                        credentials (when credentials-of (credentials-of url))
                        fetch-opts  (cond-> (assoc common :etag etag :last-modified last-modified)
                                      credentials (assoc :credentials credentials))]
                    (async/then (async/attempt #(fetch url fetch-opts))
                                (fn [{:keys [value error]}]
                                  [guid (if error
                                          {:url url :error (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})}
                                          value)]))))]
     (async/then (async/in-groups fetch1 parallel guids)
                 (fn [results]
                   (reduce (fn [acc [guid result]] (merged acc (refresh (:feeds acc) guid result now cadence)))
                           {:feeds feeds :changed [] :moved {} :report []}
                           results))))))

#?(:clj
   (comment
     (def now (java.util.Date.))
     (def url "https://feeds.podcastindex.org/pc20.xml")
     (def feeds (add {} url (podcast/fetch! url {:max-pages 2}) now))

     (update-vals feeds #(select-keys % [:url :next-check :etag]))
     (due feeds now)
     (due feeds (later now 604800))

     ;; a day later: a 304 or a fresh feed
     (def r (refresh! feeds (later now 86400)))
     (select-keys r [:changed :moved :report])
     (get-in r [:feeds (first (keys feeds)) :status])
     #_.))
