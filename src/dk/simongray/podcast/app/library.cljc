(ns dk.simongray.podcast.app.library
  "The subscriptions, feeds, queue and play progress of an app, in one map.

  Each part is a value of its own namespace, e.g. every function of
  profile takes (:profile library):

      {:device        \"…\"              ; the id of this device
       :profile       {…}              ; the profile, a CRDT that syncs
       :feeds         {feed-guid {…}}  ; the feeds, as app.feeds keeps them
       :fetched-lists {url {…}}        ; the followed OPML lists, as fetched
       :index         {…}              ; the search index, with :search?
       :sync          {:cursors {…} …} ; the last sync's cursors and report
       :refresh       {:added […] …}   ; the new episodes, moves and report
       :effects       [[:seek id 784]] ; the effects of the last step, to apply
       :sleep         {:episodes 1}    ; the sleep timer of this device, if set
       :config        {…}}             ; given once to make

  Keep the library in an atom and swap! it. The listener can change it
  while refresh! or sync! fetches, so merge their result in, which keeps
  those changes:

      (swap! lib merge (refresh! @lib))

  Each function returns the library along with what it did, under
  :effects, :refresh or :sync. The profile is a CRDT, so change it only
  with the functions of profile, never with assoc or dissoc.

  The config holds the options that the functions take, given once:

      {:fetch          f              ; in place of podcast/fetch!
       :send           f              ; in place of http/send!
       :allow?         http/public?   ; which URLs a request may go to
       :credentials-of f              ; credentials of a private feed by URL
       :index          {…}            ; the credentials of the Podcast Index
       :languages      [\"da\" \"en\"]    ; of the listener, for transcripts
       :search?        false          ; whether to keep the search index
       :cadence        {…}            ; as app.feeds takes it
       :concurrency    8              ; how many feeds are fetched at once
       :keep           app.feeds/slim ; what to keep of a feed, all by default
       :now            f              ; the clock, util.time/now by default
       :timeout-seconds 15 :max-bytes … :user-agent …}

  Each function also takes the same keys as options, for one call.
  A config with functions doesn't print as EDN, so save the library
  without its :config, and assoc the config back when you read it.

  With :keep app.feeds/slim, a library holds a fraction of each feed,
  which is enough to sync and refresh. The functions that show the feeds,
  such as episodes, show, episode and search, then lack the titles and the
  rest, so it's for a device that never shows them, such as a server.

  Each change is stamped with the time of the clock, or with the :at of
  the options, an instant. Give :at for an event that you handle later,
  e.g. in a background task, since it needs the time it happened."
  (:refer-clojure :exclude [merge])
  (:require [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.downloads :as downloads]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.search :as search]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]))

(def default-config
  "The config that a library has when make isn't given one. The
  :list-seconds is how long a fetched OPML list stays current."
  {:search?      false
   :list-seconds 86400})

(defn- options
  "The `opts` of a call over the config of `library` and the defaults."
  [library opts]
  (clojure.core/merge default-config (:config library) opts))

(defn ^:no-doc time-of
  "The instant of a call to `library` with `opts`: their :at, or else the
  time of the clock, the :now of the options or of the config."
  [library opts]
  (or (:at opts) ((:now (options library opts) time/now))))

(defn make
  "A library for the device `device`, made with the `opts` below.

  The `device` is a stable id that tells the listener's devices apart,
  made once per installation. The `opts` describe the device for the other
  devices, such as its :name and :platform, and give the :config."
  ([device]
   (make device {}))
  ([device {:keys [config] :as opts}]
   {:pre [(map? opts)]}
   (let [description (dissoc opts :config :at)
         config      (clojure.core/merge default-config config)
         described   (fn [p]
                       (let [at (time-of {:config config} opts)]
                         (crdt/identify p description at)))]
     {:device        device
      :profile       (cond-> (profile/make device)
                       (seq description) described)
      :feeds         {}
      :fetched-lists {}
      :index         (when (:search? config) (search/index))
      :sync          {}
      :config        config})))

(defn urls
  "A map from each feed of `library` to its URL, which search/episodes and
  downloads/plan take as :url."
  [library]
  (into {} (map (fn [[_ {:keys [feed url]}]] [feed url])) (:feeds library)))

(defn- documents
  "The search documents of the show and each item of `feed` at `url`."
  [feed url]
  (into [(search/feed-document feed {:url url})]
        (map #(search/item-document feed % {:url url}))
        (:rss/items feed)))

(defn- unindexed
  "The `index` without the documents of the feed `feed-guid`."
  [index feed-guid]
  (let [of-feed? #(or (= feed-guid %)
                      (and (vector? %) (= feed-guid (first %))))]
    (reduce search/remove index (filter of-feed? (keys (:docs index))))))

(defn- reindexed
  "The `library` with the feed `feed-guid` indexed again, when it keeps an
  index."
  [library feed-guid]
  (if-let [index (:index library)]
    (let [{:keys [feed url]} (get-in library [:feeds feed-guid])
          cleared (unindexed index feed-guid)
          docs    (when feed (documents feed url))]
      (assoc library :index (reduce search/add cleared docs)))
    library))

(defn- kept
  "The `library` with the feed `feed-guid` cut down to what `keep` gives of it,
  once nothing else needs the whole feed."
  [library feed-guid keep]
  (cond-> library
    (and keep (get-in library [:feeds feed-guid :feed]))
    (update-in [:feeds feed-guid :feed] keep)))

(defn- fetch-result
  "The result of a fetch that the `x` given to subscribe stands for. It's
  `x` itself, or a map with `x` as the :feed when `x` is a parsed feed,
  which has namespaced keys."
  [x]
  (if (some #(and (keyword? %) (namespace %)) (keys x))
    {:feed x}
    x))

;; TODO: consider an arity of the library and the URL alone, which subscribes
;; without a fetch. refresh! already fetches the subscriptions that have no
;; feed, as it does for those of an OPML list.
(defn subscribe
  "The `library` subscribed to the feed at `url`, with what was `fetched`
  and the settings in `opts`.

  The `fetched` is the result of dk.simongray.podcast/fetch! or a parsed
  feed, and the `opts` are as profile/configure takes them, besides :at.

  It throws when `fetched` has no feed, e.g. after a fetch that failed,
  and when the feed declares the guid of another subscription, at another
  URL, since the feed would take that subscription over. The ex-data is
  the diagnostic of the fetch, or the one that profile/takeover gives. To
  replace the other subscription, unsubscribe from it first."
  ([library url fetched]
   (subscribe library url fetched {}))
  ([library url fetched opts]
   {:pre [(map? opts)]}
   (let [at        (time-of library opts)
         settings  (dissoc opts :at :now)
         result    (fetch-result fetched)
         feed      (:feed result)
         feed-guid (feed/feed-guid feed url)]
     (when-not (map? feed)
       (let [status (:status result)
             failed (or (:error result)
                        (diagnostics/describe
                         {:id      :fetch/status
                          :url     url
                          :status  status
                          :message (str "No feed to subscribe to: " url
                                        " answered " status)}))]
         (throw (ex-info (:message failed) failed))))
     (when-let [taken (profile/takeover (:profile library) feed-guid url)]
       (throw (ex-info (:message taken) taken)))
     (let [cadence (:cadence (:config library) {})
           details {:url url :title (resolve/title feed)}]
       (-> library
           (update :feeds feeds/add url result at cadence)
           (update :profile profile/subscribe feed-guid details at)
           (cond-> (seq settings)
             (update :profile profile/configure feed-guid settings at))
           (reindexed feed-guid)
           (kept feed-guid (:keep (:config library))))))))

(defn unsubscribe
  "The `library` unsubscribed from the feed `feed-guid`, with `opts`. The feed
  and its search documents go, and the profile keeps a record of the
  subscription."
  ([library feed-guid]
   (unsubscribe library feed-guid {}))
  ([library feed-guid opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unsubscribe feed-guid (time-of library opts))
       (update :feeds dissoc feed-guid)
       (cond-> (:index library) (update :index unindexed feed-guid)))))

(defn subscriptions
  "The live subscriptions of `library` by guid, each as
  profile/subscriptions gives it, with its :settings filled in by
  profile/settings, and its :feed once it's fetched."
  [library]
  (let [profile  (:profile library)
        detailed (fn [[feed-guid sub]]
                   (let [entry    (get-in library [:feeds feed-guid])
                         settings (profile/settings profile feed-guid)]
                     [feed-guid (cond-> (assoc sub :settings settings)
                                  entry (assoc :feed (:feed entry)))]))]
    (into {} (map detailed) (profile/subscriptions profile))))

(defn- listed
  "The `library` with the parse `list` of the OPML list at `url`, fetched
  at `at`, applied as profile/refresh-list does, and kept with the ETag
  and Last-Modified of `fetched`."
  [library url list at {:keys [etag last-modified] :as fetched}]
  (let [seconds    (:list-seconds (options library {}))
        next-check (time/->inst (+ (inst-ms at) (* 1000 seconds)))
        entry      (cond-> {:list list :fetched-at at :next-check next-check}
                     etag          (assoc :etag etag)
                     last-modified (assoc :last-modified last-modified))
        was        (get-in library [:fetched-lists url :list])
        refreshed  #(:profile (profile/refresh-list % url was list at))]
    (-> library
        (update :profile refreshed)
        (assoc-in [:fetched-lists url] entry))))

(defn follow-list
  "The `library` following the OPML list at `url`, whose parse is `list`,
  with the `opts` below.

  The `opts` give the :etag and :last-modified of the fetch, and the
  :title and :folder of the list as profile/follow-list takes them. The
  shows of the list become subscriptions, which refresh! fetches."
  ([library url list]
   (follow-list library url list {}))
  ([library url list opts]
   {:pre [(map? opts)]}
   (when-not (map? list)
     (throw (ex-info "The list isn't a parsed OPML list" {:url url})))
   (let [at      (time-of library opts)
         details (select-keys opts [:title :folder])]
     (-> library
         (update :profile profile/follow-list url details at)
         (listed url list at opts)))))

(defn unfollow-list
  "The `library` no longer following the list at `url`, with `opts`."
  ([library url]
   (unfollow-list library url {}))
  ([library url opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unfollow-list url (time-of library opts))
       (update :fetched-lists dissoc url))))

(defn- unreachable
  "The result of a fetch of `url` that threw `error`."
  [url error]
  {:error (diagnostics/describe {:id     :fetch/unreachable
                                 :url    url
                                 :reason (async/explain error)})})

(defn- fetch-list!
  "Fetch the OPML list at `url` with `opts` and the ETag and Last-Modified
  in its library `entry`, in a future or a promise.

  The result has the :list, :etag and :last-modified, or :unchanged? when
  the list didn't change, or an :error."
  [url {:keys [etag last-modified] :as entry} {:keys [send] :as opts}]
  (let [accept  "text/x-opml, application/xml;q=0.9, */*;q=0.1"
        headers (cond-> {"Accept" accept}
                  etag          (assoc "If-None-Match" etag)
                  last-modified (assoc "If-Modified-Since" last-modified))
        failure (fn [d] {:error (diagnostics/describe d)})
        parsed  (fn [body]
                  (try
                    {:list (opml/parse (str body))}
                    (catch #?(:clj Exception :cljs :default) e
                      {:error e})))]
    (async/then (http/try-send! send {:url url :headers headers} opts)
                (fn [{:keys [value error]}]
                  (let [status (:status value)
                        h      #(http/header (:headers value) %)]
                    (cond
                      error
                      (unreachable url error)

                      (= 304 status)
                      {:unchanged? true}

                      (not= 200 status)
                      (failure {:id :fetch/status :url url :status status})

                      :else
                      (let [{:keys [list error]} (parsed (:body value))
                            tag      (h "etag")
                            modified (h "last-modified")]
                        (if error
                          (failure {:id     :fetch/malformed
                                    :url    url
                                    :reason (async/explain error)})
                          (cond-> {:list list}
                            tag      (assoc :etag tag)
                            modified (assoc :last-modified modified))))))))))

(defn- refresh-lists!
  "Fetch the lists of `library` that are due at `at` with `opts`, and
  apply them, in a future or a promise. The result is a map of the
  :library and the :report."
  [library at opts]
  (let [due    (for [[url entry] (:fetched-lists library)
                     :when (<= (inst-ms (:next-check entry)) (inst-ms at))]
                 url)
        fetch! (fn [url]
                 (let [entry (get-in library [:fetched-lists url])]
                   (async/then (fetch-list! url entry opts) #(vector url %))))
        apply1 (fn [{:keys [library] :as acc} [url fetched]]
                 (let [{:keys [list unchanged? error]} fetched
                       seconds (:list-seconds (options library {}))
                       later   (time/->inst (+ (inst-ms at) (* 1000 seconds)))
                       path    [:library :fetched-lists url]]
                   (cond
                     error
                     (-> acc
                         (assoc-in (conj path :next-check) later)
                         (update :report conj (assoc error :list url)))

                     unchanged?
                     (-> acc
                         (assoc-in (conj path :fetched-at) at)
                         (assoc-in (conj path :next-check) later))

                     :else
                     (->> (listed library url list at fetched)
                          (assoc acc :library)))))]
    (async/then (async/all fetch! due)
                (fn [results]
                  (reduce apply1 {:library library :report []} results)))))

(defn- unfetched
  "The guids and URLs of the live subscriptions of `library` that have no
  feed yet."
  [library]
  (for [[feed-guid {:keys [url]}] (profile/subscriptions (:profile library))
        :when (and url (not (contains? (:feeds library) feed-guid)))]
    [feed-guid url]))

(defn- known
  "The `library` with the feed of `result`, fetched from `url` at `at` for
  the subscription `feed-guid`, and cut down by `keep`.

  The subscription moves to the guid that the feed declares, as
  profile/rekey does."
  [library feed-guid url result at keep]
  (let [declared (feed/feed-guid (:feed result) url)]
    (-> library
        (update :profile profile/rekey feed-guid declared at)
        (update :feeds feeds/add url result at (:cadence (:config library) {}))
        (reindexed declared)
        (kept declared keep))))

(defn- fetch-new-feeds!
  "Fetch the feeds of the new subscriptions of `library` at `at` with
  `opts`, and add them, in a future or a promise.

  The result is a map of the :library and the :report. The :concurrency
  of `opts` feeds are fetched at a time, as app.feeds/refresh! fetches
  them.
  A feed that couldn't be fetched, or that would take another
  subscription over, is tried again next time."
  [library at {:keys [fetch credentials-of concurrency keep]
               :or   {concurrency feeds/default-concurrency}
               :as   opts}]
  (let [common (select-keys opts [:send :timeout-seconds :max-bytes
                                  :user-agent :allow?])
        fetch1 (fn [[feed-guid url]]
                 (let [credentials (when credentials-of (credentials-of url))
                       fetch-opts  (cond-> common
                                     credentials
                                     (assoc :credentials credentials))]
                   (async/then (async/attempt #(fetch url fetch-opts))
                               (fn [{:keys [value error]}]
                                 [feed-guid url (if error
                                                  (unreachable url error)
                                                  value)]))))
        add1   (fn [acc [feed-guid url {:keys [feed] :as result}]]
                 (let [current  (get-in acc [:library :profile])
                       declared (when feed (feed/feed-guid feed url))
                       taken    (when feed
                                  (profile/takeover current declared url))]
                   (cond
                     taken
                     (update acc :report conj taken)

                     (:feed result)
                     (update acc :library known feed-guid url result at keep)

                     (:error result)
                     (update acc :report conj
                             (assoc (:error result) :feed-guid feed-guid))

                     :else
                     (update acc :report conj
                             (diagnostics/describe
                              {:id        :fetch/status
                               :url       url
                               :status    (:status result)
                               :feed-guid feed-guid})))))]
    (async/then (async/in-groups fetch1 concurrency (unfetched library))
                (fn [results]
                  (reduce add1 {:library library :report []} results)))))

(defn- applied
  "The `library` with the `result` of app.feeds/refresh! applied to the
  profile at `at`, and the fresh feeds cut down by `keep`, as a map of
  the :library and what was :added and :moved."
  [library {:keys [feeds changed moved] :as result} at keep]
  (let [before   (:feeds library)
        library  (assoc library :feeds feeds)
        fresh    (fn [acc feed-guid]
                   (let [{:keys [feed url]} (get feeds feed-guid)
                         old-feed (get-in before [feed-guid :feed])
                         current  (:profile (:library acc))
                         {:keys [profile added]}
                         (profile/refresh current url old-feed feed at)]
                     (-> acc
                         (assoc-in [:library :profile] profile)
                         (update :library reindexed feed-guid)
                         (update :library kept feed-guid keep)
                         (update :added into added))))
        relocate (fn [acc [feed-guid new-url]]
                   (let [parsed  (get-in feeds [feed-guid :feed])
                         details {:url new-url :title (resolve/title parsed)}]
                     (update-in acc [:library :profile]
                                profile/relocate feed-guid details)))
        result   (reduce fresh {:library library :added []} changed)]
    (reduce relocate (assoc result :moved moved) moved)))

(defn refresh!
  "Fetch and apply whatever `library` is due to fetch, with the `opts`
  below.

  It fetches the followed lists, the feeds of new subscriptions, and the
  feeds whose next check has come. Returns the library with the step under
  :refresh, in ClojureScript as a promise, to swap! in with merge.

      {:at     #inst \"…\"
       :added  [[feed-guid item-guid] …]   ; the new episodes
       :moved  {feed-guid \"https://…\"}    ; the feeds that moved, and so
                                          ; their subscriptions
       :report [...]}                     ; the fetches that failed, and why

  Nothing is thrown for what the network or a server does. The `opts` are
  the config for this call, and :feed-guids, the feeds to fetch instead of
  those that are due, e.g. after a Podping:

      (app.feeds/affected (:feeds library) pings)"
  ([library]
   (refresh! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at         (time-of library opts)
         defaults   {:send http/send! :fetch podcast/fetch!}
         opts       (clojure.core/merge defaults (options library opts))
         feeds-opts (-> opts
                        (select-keys [:feed-guids :fetch :credentials-of
                                      :concurrency :cadence :send
                                      :timeout-seconds :max-bytes
                                      :user-agent :allow?])
                        (assoc :at at))]
     (async/then
      (refresh-lists! library at opts)
      (fn [{library :library report :report}]
        (async/then
         (fetch-new-feeds! library at opts)
         (fn [{library :library report-new :report}]
           (async/then
            (feeds/refresh! (:feeds library) feeds-opts)
            (fn [step]
              (let [{:keys [library added moved]}
                    (applied library step at (:keep opts))

                    reports (-> []
                                (into report)
                                (into report-new)
                                (into (:report step)))]
                (assoc library :refresh {:at     at
                                         :added  added
                                         :moved  moved
                                         :report reports})))))))))))

(defn episode-id
  "The id of the episode `item` of `feed` in `library`, [feed-guid
  item-guid], as feed/episode-id gives it with the URL of the feed."
  [library feed item]
  (feed/episode-id feed (get (urls library) feed) item))

(defn- item-of
  "The entry of the feed `feed-guid` in `library` and its item
  `item-guid`, as a pair, or nil."
  [library [feed-guid item-guid]]
  (when-let [{:keys [feed] :as entry} (get-in library [:feeds feed-guid])]
    (when-let [item (some #(when (= item-guid (feed/item-guid %)) %)
                          (:rss/items feed))]
      [entry item])))

(defn show
  "The view of the show `feed-guid` of `library`, as app.show/view gives it
  with the `opts` it takes, or nil when the library has no such feed."
  ([library feed-guid]
   (show library feed-guid {}))
  ([library feed-guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds feed-guid])]
     (show/view feed (assoc opts :url url)))))

(defn fetch-linked-shows!
  "Fetch the shows that the show `feed-guid` of `library` links to, with `opts`
  for this call, for a page to merge into the view that show gives.

  The result is that of app.show/fetch-linked-shows! with the config of
  the library, or nil when the library has no such feed. The `opts` are
  those of app.show/fetch-linked-shows!."
  ([library feed-guid]
   (fetch-linked-shows! library feed-guid {}))
  ([library feed-guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds feed-guid])]
     (let [unused #{:notes :text :episodes :seasons :links}
           view   (show/view feed {:url url :except unused})]
       (show/fetch-linked-shows! view (options library opts))))))

(defn episode
  "The view of the episode `id` of `library`, as app.episode/view gives it
  with `opts`.

  The `id` is [feed-guid item-guid], and the view uses the :languages
  of the config. Returns nil when the library has no such episode."
  ([library id]
   (episode library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (episode/view feed item (assoc (options library opts) :url url)))))

(defn read-files!
  "Read the files of the episode `id` of `library`, with `opts` for this
  call, for a page to merge into the view that episode gives.

  The result is that of app.episode/read-files! with the config of the
  library, or nil when the library has no such episode. The `opts` are
  those of app.episode/read-files!."
  ([library id]
   (read-files! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (let [opts   (options library opts)
           unused #{:notes :text :links}
           view   (episode/view feed item (assoc opts :url url :except unused))]
       (episode/read-files! view opts)))))

(defn fetch-remote-values!
  "Fetch the value blocks that the time splits of the episode `id` of
  `library` point at, with `opts` for this call, for a page to merge into
  the view that episode gives.

  The result is that of app.episode/fetch-remote-values! with the config
  of the library, or nil when the library has no such episode. The `opts`
  are those of app.episode/fetch-remote-values!."
  ([library id]
   (fetch-remote-values! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (let [unused #{:notes :text :chapters :links}
           view   (episode/view feed item {:url url :except unused})]
       (episode/fetch-remote-values! view (options library opts))))))

(defn episodes
  "The episodes of the shows of `library` that `opts` select, as [feed
  item] pairs.

  The selection and the order are those of search/episodes across all
  the feeds. The criteria read the profile of the library, and the
  time of its clock unless the `opts` give :at.

      (episodes library {:where {:status :unplayed} :order :listening})"
  ([library]
   (episodes library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (search/episodes (map :feed (vals (:feeds library)))
                    (assoc opts
                           :profile (:profile library)
                           :url     (urls library)
                           :at      (time-of library opts)))))

(defn search
  "The episodes and shows of `library` that match the query `q`, best
  first, with the `opts` of search/query.

  The library has an index when its config has :search?, and
  without one, this returns nil."
  ([library q]
   (search library q {}))
  ([library q opts]
   (some-> (:index library) (search/query q opts))))

(defn step
  "The `library` after the player `event`, with `opts`, both as
  profile/step takes them.

  It has the new profile, the :effects to apply, and what's left of
  the :sleep timer of this device. The `opts` go over the :sleep of
  the library."
  ([library event]
   (step library event {}))
  ([library event opts]
   {:pre [(map? opts)]}
   (let [at   (time-of library opts)
         opts (clojure.core/merge (select-keys library [:sleep]) opts)
         {:keys [profile effects sleep]}
         (profile/step (:profile library) event at opts)]
     (cond-> (assoc (dissoc library :sleep) :profile profile :effects effects)
       sleep (assoc :sleep sleep)))))

(defn update-profile
  "The `library` with its profile replaced by what `f` gives for it, the
  `args` and the time of the clock, in that order.

  It suits most functions of profile, e.g. (update-profile library
  profile/enqueue id). For a series of changes at one time, give a
  function of the profile and the time:

      (update-profile library (fn [profile at]
                                (-> profile
                                    (profile/tag guid \"Daily\" at)
                                    (profile/star id at))))

  It takes no :at, so for an event that you handle later, call the
  function with the time it happened: (update library :profile
  profile/enqueue id at)."
  [library f & args]
  (update library :profile #(apply f % (concat args [(time-of library {})]))))

(defn sync!
  "Sync `library` with the listener's other devices through `backends`,
  with the `opts` of sync/sync! except :cursors.

  It syncs as sync/sync! does, keeps the cursors for the next sync, and
  compacts the tombstones. Returns the library with the step under
  :sync, in ClojureScript as a promise, to swap! in with merge.

      {:cursors  {…}         ; those of the stores, for the next step
       :at       #inst \"…\"
       :clock    #inst \"…\"   ; the clock of the profile after the step
       :failures 0           ; the steps in a row in which a backend failed
       :report   [...]}      ; with :sync/backend-failed for each that did"
  ([library backends]
   (sync! library backends {}))
  ([library backends opts]
   {:pre [(map? opts)]}
   (let [at        (time-of library opts)
         before    (:profile library)
         last-step (:sync library)
         sync-opts (assoc opts :at at :cursors (:cursors last-step))]
     (async/then (sync/sync! before backends sync-opts)
                 (fn [{:keys [state cursors report]}]
                   (let [profile  (-> before
                                      (profile/merge state)
                                      (profile/compact at))
                         failed?  (some #(= :sync/backend-failed (:id %))
                                        report)
                         failures (if failed? (inc (:failures last-step 0)) 0)]
                     (assoc library
                            :profile profile
                            :sync    {:cursors  cursors
                                      :at       at
                                      :clock    (:clock profile)
                                      :failures failures
                                      :report   report})))))))

(defn- newer
  "Of `a` and `b`, two maps with an instant under `k`, the newer one, or
  `b` when `a` isn't newer."
  [k a b]
  (if (pos? (compare (k a) (k b))) a b))

(defn- merged-entries
  "The entries of `mine` and `theirs` whose key `keep?` holds for, and of
  two with one key, the one fetched later."
  [mine theirs keep?]
  (into {}
        (filter (comp keep? key))
        (merge-with (partial newer :fetched-at) mine theirs)))

(defn merge
  "The `library` with `result` merged in, where `result` is what refresh!
  or sync! gave for an earlier value of `library`, e.g.

      (swap! lib merge (refresh! @lib))

  The profiles merge as profile/merge does, so nothing that the listener
  did while those fetched is lost. A feed or a list stays only while the
  merged profile subscribes to it or follows it, so one that the listener
  left on another device goes. Of two entries of one feed or list, the
  later fetch wins. The later :refresh and :sync are kept too, and the
  rest is that of `library`."
  [library result]
  (let [profile (profile/merge (:profile library) (:profile result))
        synced  (newer :at (:sync library) (:sync result))
        refresh (newer :at (:refresh library) (:refresh result))
        feeds   (merged-entries (:feeds library) (:feeds result)
                                (profile/subscriptions profile))
        lists   (merged-entries (:fetched-lists library) (:fetched-lists result)
                                (profile/lists profile))
        same?   #(identical? (get-in library [:feeds % :feed])
                             (get-in feeds [% :feed]))
        guids   (into (set (keys feeds)) (keys (:feeds library)))
        changed (remove same? guids)]
    (reduce reindexed
            (cond-> (assoc library
                           :profile       (cond-> profile
                                            (not= synced (:sync library))
                                            (profile/compact (:at synced)))
                           :feeds         feeds
                           :fetched-lists lists)
              synced  (assoc :sync synced)
              refresh (assoc :refresh refresh))
            changed)))

(defn next-sync
  "When `library` should sync again, with `opts`, as sync/next-sync says:
  right away before the first step, and sooner when the profile changed
  since the last step."
  ([library]
   (next-sync library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [{:keys [at clock failures]} (:sync library)
         changed?  (not= clock (:clock (:profile library)))
         last-step (when at
                     {:at at :changed? changed? :failures failures})]
     (sync/next-sync last-step (time-of library opts)))))

(defn document
  "This device's sync document in `library`, with the :app and :at of
  `opts`, as a map of the file's :name and :body.

  A backend writes the same document to its store. Where an app can't
  write to the store, e.g. a sync folder in a browser without a folder
  picker, the listener can save this file into it by hand."
  ([library]
   (document library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [{:keys [device] :as profile} (:profile library)
         at (time-of library opts)]
     {:name (store/document-name device)
      :body (sync/document profile at (select-keys opts [:app]))})))

(defn plan
  "The episodes to download and the files to delete on this device with
  `files`. It's what downloads/plan gives for the profile and feeds of
  `library`, with the `opts`."
  ([library files]
   (plan library files {}))
  ([library files opts]
   {:pre [(map? opts)]}
   (downloads/plan (:profile library)
                   (map :feed (vals (:feeds library)))
                   files
                   (time-of library opts)
                   (assoc opts :url (urls library)))))

#?(:clj
   (comment
     (def url "https://feeds.podcastindex.org/pc20.xml")

     (def library
       (-> (make "dev-laptop" {:name   "Laptop"
                               :config {:languages ["da" "en"]}})
           (subscribe url (podcast/fetch! url {:max-pages 2})
                      {:auto-queue? true})))

     (keys (subscriptions library))
     (map (comp :title second) (episodes library {:limit 3}))
     (select-keys (show library (first (keys (subscriptions library))))
                  [:title :author])

     ;; the new episodes since the last fetch, and the moves
     (:refresh (refresh! library))

     ;; the newest episode, played a minute in
     (def id
       (let [[feed item] (first (episodes library {:order :newest :limit 1}))]
         (episode-id library feed item)))
     (:effects (step library [:progress id 60 3600]))
     (select-keys (clojure.core/merge (episode library id)
                                      (read-files! library id))
                  [:title :duration :chapters])
     #_.))
