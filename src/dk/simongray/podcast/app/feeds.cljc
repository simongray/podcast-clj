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
                     :whole-at     #inst \"…\"   ; when it last came whole
                     :next-check   #inst \"…\"
                     :failures     0
                     :status       200
                     :validates?   true}}       ; whether it answers 304

  The feed keeps the items that a fetch no longer brings back, after the
  fresh ones, since many hosts serve only the newest items. Each has a fact
  under :retained: when a fetch first missed it, and :gone? when the host
  took it out rather than leaving it to age out of a capped feed, as it's
  later than the oldest fresh item, or has no date.

  An entry with only its :url is a feed to fetch for the first time, e.g.
  of a new subscription, and it's due at once. An entry that's :gone? is
  never due, as default-cadence says, and nor is one that's :local?, the
  feed of a show of local files, which has no URL."
  (:require [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.feeds.impl :as impl]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.podping :as podping]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.qname-hiccup :as xml]
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
  at the last wait. It's then fetched only when asked for by name.

  A refresh with :head-check? fetches a feed whose server never answers
  304 whole only once its start shows something new, or at least every
  :whole-every seconds, so that an edit further down shows up too."
  {:max-wait            43200
   :silent              43200
   :complete            604800
   :after-failure       [900 3600 14400 86400]
   :gone-after-failures 30
   :whole-every         86400})

(defn- head-checked?
  "Whether a refresh at `at` with `cadence` checks the feed of the entry
  `old` by its start first: when the entry holds a feed whose server can't
  answer 304, as it gives no validators or ignored them, and the feed came
  whole within the :whole-every of `cadence`."
  [old at cadence]
  (let [whole-at (:whole-at old)]
    (boolean (and (:feed old)
                  whole-at
                  (or (false? (:validates? old))
                      (not (or (:etag old) (:last-modified old))))
                  (< (- (inst-ms at) (inst-ms whole-at))
                     (* 1000 (:whole-every cadence)))))))


(def default-concurrency
  "How many feeds are fetched at once, by default."
  8)

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
            (first (impl/fetched old feed-guid url result at cadence))))))

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
  first. A feed that's gone is never due, and nor is one of local files,
  which no fetch brings."
  [feeds at]
  (let [due?  (fn [[_ {:keys [next-check gone? local?]}]]
                (and (not gone?)
                     (not local?)
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
                   (let [entry (impl/failed old result at cadence)
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
       {:feeds (assoc feeds feed-guid (impl/unchanged old result at cadence))}

       (and (:feed result)
            (empty? (:rss/items (:feed result)))
            (seq (get-in old [:feed :rss/items])))
       (failure (diagnostics/describe {:id :fetch/empty-feed :url url}))

       (:feed result)
       (let [[entry same?] (impl/fetched old feed-guid url result at cadence)
             report        (:move-report result)]
         (cond-> {:feeds (assoc feeds feed-guid entry)}
           (not same?)             (assoc :changed [feed-guid])
           (not= url (:url entry)) (assoc :moved {feed-guid (:url entry)})
           report                  (assoc :report [(noted report)])))

       :else
       (failure (diagnostics/fetch-failure {:url    url
                                            :status (:status result)}))))))

;; WebSub 7: a hub can push a diff in place of the whole feed
(defn pushed
  "The `feeds` after a WebSub hub pushed `feed` for the feed `feed-guid` at
  `at`, with `cadence`, and what it changed, as refresh returns it.

  A hub can push only what changed, so a push adds and updates episodes
  and the feed's details but removes nothing; the next fetch does that.
  The entry keeps its ETag, Last-Modified and hubs, and must hold its
  feed."
  ([feeds feed-guid feed at]
   (pushed feeds feed-guid feed at {}))
  ([feeds feed-guid feed at cadence]
   {:pre [(map? cadence)]}
   (let [old (get feeds feed-guid)]
     (when-not (:feed old)
       (throw (ex-info (str "No feed of the guid " feed-guid " is held")
                       {:feed-guid feed-guid})))
     (refresh feeds
              feed-guid
              (-> (select-keys old [:etag :last-modified :websub])
                  (assoc :status 200 :feed (impl/with-pushed old feed)))
              at
              cadence))))

(defn- unreachable
  "The result of a fetch of `url` that threw `error`, with the :type of the
  failure when wary-fetch gives one."
  [url error]
  {:url   url
   :error (diagnostics/fetch-failure (assoc (http/failure error) :url url))})

(defn connection-failed?
  "Whether the fetch `result` failed before any server answered: the name
  of its host wasn't found, the host couldn't be reached, or the secure
  connection failed."
  [result]
  (contains? #{::http/unresolved ::http/unreachable ::http/tls}
             (get-in result [:error :type])))

(defn- aborted?
  "Whether the fetch `result` failed since the :signal of the refresh
  stopped it."
  [result]
  (= ::http/aborted (get-in result [:error :type])))

(defn- offline?
  "Whether the `results` of a round of fetches, pairs of a feed guid and a
  result, say that the device is offline: each failed before any server
  answered, at more than one host, since one host can be down by itself."
  [results]
  (and (every? (comp connection-failed? second) results)
       (< 1 (count (into #{} (map (comp url/host :url second)) results)))))

(defn refresh!
  "Fetch the feeds of `feeds` that are due with the `opts` below, and apply
  each result.

  Returns a map of :feeds, :changed, :failed, :moved and :report for all
  the fetches, as refresh gives them, :checked, the guids of the feeds
  fetched, and :left, those of the feeds that stay due, in ClojureScript
  as a promise. Nothing is thrown for what the network or a server does,
  but a platform without an XML parser throws, e.g. a web worker without
  a DOMParser installed. The `opts` are:

  - :at, the instant of the refresh, the time of the clock by default
  - :feed-guids, the feeds to fetch instead of the due ones, e.g. those
    that a Podping names, but for those of local files
  - :fetch, in place of dk.simongray.podcast/fetch!
  - :head-check?, true to read only the start of a feed whose server can't
    answer 304, as it gives no ETag or Last-Modified or ignores them, and
    to fetch it whole only when its newest items changed, or when the
    whole feed is older than the :whole-every of the cadence. Most often
    the start is the first 32 KB of a feed of megabytes, which needn't be
    parsed whole, e.g. on the main thread of a web page.
  - :fetch-head, in place of discovery/fetch-head!
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
  - :signal, to stop the refresh, e.g. when the listener cancels it, as
    http/send! takes it: an AbortSignal in ClojureScript, and on the JVM
    an atom that turns true
  - :send, :timeout-seconds, :max-bytes, :user-agent and :allow-pred, as
    fetch! takes them

  The feeds that :max-feeds or :max-seconds leave out stay due, for the
  next refresh. So do those that the :signal stopped, or kept from
  starting, with no failure counted, and the report has
  :refresh/stopped. A round in which every fetch failed before a server
  answered, at more than one host, counts as offline too. Offline, every
  feed stays as it was, so that none counts it as a failure and each is
  due at the next refresh, and the report has :refresh/offline.

  A move that a feed declares, e.g. by itunes:new-feed-url, is followed
  only when a feed of the same show answers at the new URL. Otherwise the
  feed stays where it is, and the report has :fetch/move-unverified."
  ([feeds]
   (refresh! feeds {}))
  ([feeds {:keys [at feed-guids fetch fetch-head head-check? credentials-of
                  concurrency cadence max-pages online? max-feeds max-seconds
                  on-feed-fn now-fn]
           :or   {fetch       podcast/fetch!
                  fetch-head  discovery/fetch-head!
                  concurrency default-concurrency
                  cadence     {}
                  now-fn      time/now}
           :as   opts}]
   {:pre [(map? opts)]}
   (let [at     (or at (now-fn))
         whole-cadence (merge default-cadence cadence)
         start  (now-fn)
         wanted (vec (remove (comp :local? feeds)
                             (or feed-guids (due feeds at))))
         guids  (cond-> wanted
                  max-feeds (subvec 0 (min max-feeds (count wanted))))
         ;; a fetch that the :signal stopped says to start no more
         over?  (fn [results]
                  (or (some (comp aborted? second) results)
                      (and max-seconds
                           (<= (* 1000 max-seconds)
                               (- (inst-ms (now-fn)) (inst-ms start))))))
         done   (atom 0)
         common (select-keys opts [:send :timeout-seconds :max-bytes
                                   :user-agent :allow-pred :signal])
         private (fn [url]
                   (let [credentials (when credentials-of (credentials-of url))]
                     (cond-> common
                       credentials (assoc :credentials credentials))))
         fetch-whole
                (fn [feed-guid]
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
                           (= ::xml/no-parser (:type (ex-data error)))
                           (throw error)

                           error
                           [feed-guid (unreachable url error)]

                           ;; a declared move goes where a feed answers
                           (and new-url (not= new-url url))
                           (async/then
                            (async/attempt #(fetch new-url (private new-url)))
                            #(vector feed-guid
                                     (impl/verified-move url value %)))

                           :else
                           [feed-guid value]))))))
         ;; the start of a feed whose server can't answer 304, which shows
         ;; nothing new, counts as one, and costs no whole fetch or parse
         fetch1 (fn [feed-guid]
                  (let [{:keys [url feed] :as old} (get feeds feed-guid)]
                    (if (and head-check? (head-checked? old at whole-cadence))
                      (async/then
                       (async/attempt #(fetch-head url (assoc (private url) :items impl/head-items)))
                       (fn [{:keys [value]}]
                         (if (impl/same-start? feed (:feed value))
                           [feed-guid {:status 304 :head? true}]
                           (fetch-whole feed-guid))))
                      (fetch-whole feed-guid))))
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
                       (impl/merged acc)))
         stop   (diagnostics/describe {:id :refresh/stopped})
         round  (fn [all]
                  (let [results (remove (comp aborted? second) all)
                        stopped (not= (count results) (count all))]
                    (cond-> (if (and (seq results) (offline? results))
                              (impl/offline feeds (get-in (first results)
                                                     [1 :error :reason]))
                              (reduce step
                                      {:feeds   feeds
                                       :checked (mapv first results)
                                       :changed []
                                       :failed  []
                                       :moved   {}
                                       :report  []}
                                      results))
                      stopped (update :report conj stop))))
         left   #(assoc % :left (vec (remove (set (:checked %)) wanted)))]
     (if (false? online?)
       (async/then nil (fn [_] (left (impl/offline feeds nil))))
       (async/then (impl/in-time told concurrency guids over?)
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
