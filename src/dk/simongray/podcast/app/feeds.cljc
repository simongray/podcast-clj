(ns dk.simongray.podcast.app.feeds
  "The feeds that an app keeps, and the step that keeps them current. The
  feeds are a map by the guid of each feed, and an entry holds the parsed
  feed, its URL, the validators of the last fetch and the time of the
  next check:

      {\"917393e3-…\" {:url          \"https://feeds.podcastindex.org/pc20.xml\"
                     :feed         {…}
                     :etag         \"…\"
                     :last-modified \"…\"
                     :fetched-at   #inst \"…\"
                     :next-check   #inst \"…\"
                     :failures     0
                     :status       200}}

  `add` puts a fetched feed in. `due` gives the feeds to fetch now, and
  `refresh` applies the result of a fetch: the validators, a 304, the
  next check as `resolve/next-check` gives it, and the URL when the feed
  moved. `refresh!` fetches the due feeds and applies each result. The
  map is data, so an app keeps it as it keeps its other values, and reads
  `(:feed entry)` as it reads any parsed feed. There is no player here:
  `dk.simongray.podcast.app.player/refresh` takes the feeds that
  `refresh!` names as changed."
  (:require #?(:clj [dk.simongray.podcast :as podcast])
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.time :as time]))

(def default-cadence
  "How long to wait before the next fetch of a feed, in seconds, when the
  feed itself gives no time: `:silent` for a feed that gives none,
  `:complete` for a feed that says it is complete, and `:after-failure`
  for each failure in a row, the last wait repeated."
  {:silent        86400
   :complete      604800
   :after-failure [900 3600 14400 86400]})

(def default-parallel
  "How many feeds `refresh!` fetches at once."
  8)

(defn- later
  "The instant `seconds` after `now`."
  [now seconds]
  (time/->inst (+ (inst-ms now) (* 1000 seconds))))

(defn- check-at
  "When to fetch `feed` again, judged at `now`: as `resolve/next-check`
  gives it, with the `not-before` of the server, else by `cadence`."
  [feed now not-before cadence]
  (or (resolve/next-check feed now {:not-before not-before})
      (later now (if (resolve/complete? feed) (:complete cadence) (:silent cadence)))))

(defn- fetched
  "The entry `old` after the fetch from `url` gave the fresh feed of
  `result` at `now`: the feed, the validators, the next check, and the
  URL of the feed when it moved."
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
  "The entry `old` after the server answered 304 at `now`."
  [old {:keys [not-before]} now cadence]
  (assoc old
         :fetched-at now
         :next-check (check-at (:feed old) now not-before cadence)
         :failures 0
         :status 304))

(defn- failed
  "The entry `old` after a fetch failed at `now`, with the `status` of the
  answer when there was one: one more failure in a row, and the next
  check after the wait of `cadence` for that many, or after the
  `not-before` of the server when that is later."
  [old {:keys [status not-before]} now cadence]
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
  "The `feeds` with the feed that `result`, the result of
  `dk.simongray.podcast/fetch!` from `url`, gave at `now`, under its
  guid. `result` must have a `:feed`: an app looks at `:error` first.

  `cadence` is `default-cadence` unless given. An entry that is there
  already is replaced, its validators and its next check fresh."
  ([feeds url result now]
   (add feeds url result now {}))
  ([feeds url result now cadence]
   {:pre [(map? (:feed result)) (map? cadence)]}
   (let [cadence (merge default-cadence cadence)
         guid    (feed/feed-guid (:feed result) url)]
     (assoc feeds guid (fetched (get feeds guid {}) url result now cadence)))))

(defn due
  "The guids of the feeds of `feeds` whose next check is at `now` or
  before, the earliest first. An entry without a next check is due."
  [feeds now]
  (->> feeds
       (filter (fn [[_ {:keys [next-check]}]]
                 (or (nil? next-check) (<= (inst-ms next-check) (inst-ms now)))))
       (sort-by (fn [[_ {:keys [next-check]}]] (if next-check (inst-ms next-check) 0)))
       (mapv key)))

(defn affected
  "The guids of the feeds of `feeds` that the Podping `pings` concern, as
  `podping/affected` reads them: fetch these now, whatever their next
  check."
  [feeds pings]
  (let [by-url (into {} (map (fn [[guid {:keys [url]}]] [url guid])) feeds)]
    (mapv by-url (podping/affected pings (keys by-url)))))

(defn refresh
  "The `feeds` with `result`, the result of a fetch of the feed `guid`
  from its URL, applied at `now`, and what it changed:

      {:feeds   {…}                 ; the map, the entry of the feed updated
       :changed [guid]              ; when the fetch gave a fresh feed
       :moved   {guid \"https://…\"}  ; when the feed moved for good
       :report  [diagnostic]}       ; when the fetch failed

  A fresh feed replaces the feed of the entry, with the validators of
  the answer, and the next check as `resolve/next-check` gives it, else
  as `cadence` gives it. A 304 keeps the feed and sets the next check.
  When the fetch got no answer, no feed, or a status that is not a
  success, the feed stays, the report has the `:error` of the result or
  `:fetch/status`, and the next check waits by `:after-failure` of
  `cadence`, longer for each failure in a row.

  The URL of the entry changes when the answer names `:permanent-url`, a
  redirect for good, or `:new-feed-url`, which the feed declares. An app
  moves its subscription to the URL under `:moved`. The guid of the
  entry stays, also when the moved feed declares another.

  `cadence` is `default-cadence` unless given."
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
  "The changes `changes` of a refresh added to those of `acc`."
  [acc {:keys [feeds changed moved report]}]
  (cond-> (assoc acc :feeds feeds)
    changed (update :changed into changed)
    moved   (update :moved merge moved)
    report  (update :report into report)))

(defn- in-groups
  "The results of `f` over `xs`, `n` of them at once; deferred."
  [f n xs]
  (reduce (fn [acc group]
            (async/then acc (fn [done] (async/then (async/all (map f group)) #(into done %)))))
          []
          (partition-all n xs)))

(defn refresh!
  "Fetch the feeds of `feeds` that are due at `now`, and apply each
  result as `refresh` does: `{:feeds :changed :moved :report}`, with the
  changes of all the fetches. On the JVM the map, in ClojureScript a
  promise of it.

  Each fetch sends the validators of the entry. `refresh!` throws nothing
  for what the network or a server does: a feed that did not come stays
  as it was, with its failure in the report. `opts`:

  - `:guids`, the feeds to fetch, in the place of those that `due` gives:
    `(affected feeds pings)` after a Podping, or `(concat (due feeds now)
    (affected feeds pings))`
  - `:fetch`, a function of a URL and the options of
    `dk.simongray.podcast/fetch!` that gives its result, or a promise of
    it, in the place of `fetch!`. ClojureScript has no `fetch!`, so an
    app there gives one, which fetches through its own server.
  - `:credentials-of`, a function of a guid that gives the `:credentials`
    of that feed, for private feeds. Keep passwords out of the map of
    feeds, which an app stores.
  - `:parallel`, how many feeds to fetch at once, `default-parallel`
    unless given
  - `:cadence`, in the place of `default-cadence`
  - `:timeout-seconds`, `:max-bytes`, `:user-agent` and `:allow?`, as
    `fetch!` takes them, for every feed"
  ([feeds now]
   (refresh! feeds now {}))
  ([feeds now {:keys [guids fetch credentials-of parallel cadence]
               :or   {fetch    #?(:clj podcast/fetch! :cljs nil)
                      parallel default-parallel
                      cadence  {}}
               :as   opts}]
   {:pre [(map? opts)]}
   (when-not fetch
     (throw (ex-info "refresh! needs :fetch, a function that fetches a feed" {})))
   (let [guids  (vec (or guids (due feeds now)))
         common (select-keys opts [:timeout-seconds :max-bytes :user-agent :allow?])
         fetch1 (fn [guid]
                  (let [{:keys [url etag last-modified]} (get feeds guid)
                        credentials (when credentials-of (credentials-of guid))
                        fetch-opts  (cond-> (assoc common :etag etag :last-modified last-modified)
                                      credentials (assoc :credentials credentials))]
                    (async/then (async/attempt #(fetch url fetch-opts))
                                (fn [{:keys [value error]}]
                                  [guid (if error
                                          {:url url :error (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})}
                                          value)]))))]
     (async/then (in-groups fetch1 parallel guids)
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
