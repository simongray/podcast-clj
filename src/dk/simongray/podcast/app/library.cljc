(ns dk.simongray.podcast.app.library
  "The subscriptions, feeds, queue and play progress of an app, in one map.

  The parts of a library are:

      {:device        \"…\"              ; the id of this device
       :profile       {…}              ; the profile, a CRDT that syncs
       :feeds         {feed-guid {…}}  ; the feeds, as app.feeds keeps them
       :fetched-lists {url {…}}        ; the followed OPML lists, as fetched
       :index         {…}              ; the search index, with :search?
       :sync          {:cursors {…} …} ; the last sync's cursors and report
       :refresh       {:added […] …}   ; the new episodes, moves and report
       :effects       [[:seek id 784]] ; the effects of the last event, to apply
       :sleep         {:episodes 1}    ; the sleep timer of this device, if set
       :config        {…}}             ; the options below

  The profile is a CRDT, so never change it with assoc or dissoc. The
  config holds the options that the functions take, given once:

      {:fetch          f              ; in place of podcast/fetch!
       :send           f              ; in place of client/send!
       :allow-pred     url/public?    ; which URLs a request may go to
       :credentials-of f              ; credentials of a private feed by URL
       :index          {…}            ; the credentials of the Podcast Index
       :languages      [\"da\" \"en\"]    ; of the listener, for transcripts
       :search?        true           ; whether to keep the search index
       :cadence        {…}            ; as app.feeds takes it
       :concurrency    8              ; how many feeds are fetched at once
       :keep-fn        app.feeds/slim ; what to keep of a feed, all by default
       :now-fn         f              ; the clock, util.time/now by default
       :timeout-seconds 15 :max-bytes … :user-agent …}

  Each function also takes the same keys as options, for one call, and
  :at, an instant to stamp each change with in place of the time of the
  clock."
  (:refer-clojure :exclude [merge])
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.ciff :as ciff]
            [dk.simongray.like-minded :as like-minded]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.downloads :as downloads]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def default-config
  "The config of a library when none is given. The :list-seconds is how
  long a fetched OPML list stays current."
  {:search?      true
   :list-seconds 86400})

(defn- options
  "The `opts` of a call over the config of `library` and the defaults."
  [library opts]
  (clojure.core/merge default-config (:config library) opts))

(defn ^:no-doc time-of
  "The instant of a call to `library` with `opts`: their :at, or else the
  time of the clock, the :now-fn of the options or of the config."
  [library opts]
  (or (:at opts) ((:now-fn (options library opts) time/now))))

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
      :index         (when (:search? config) (feed.search/index))
      :sync          {}
      :config        config})))

(defn- remote-guids
  "The item guids of the remote items of `profile` in the feed
  `feed-guid`."
  [profile feed-guid]
  (into #{}
        (keep (fn [[[g item-guid] _]]
                (when (= feed-guid g)
                  item-guid)))
        (profile/remote-items profile)))

(defn- listing
  "How `profile` lists the episodes of the feed `feed-guid`: whether it's
  subscribed, and the item guids of its remote items, as a pair."
  [profile feed-guid]
  [(profile/subscribed? profile feed-guid) (remote-guids profile feed-guid)])

(defn- shown
  "The feed `feed-guid` of `library` as the library lists its episodes, or
  nil without the feed: whole for a subscription, and else with the items
  of the remote items alone."
  [library feed-guid]
  (when-let [feed (get-in library [:feeds feed-guid :feed])]
    (if (profile/subscribed? (:profile library) feed-guid)
      feed
      (let [guids (remote-guids (:profile library) feed-guid)]
        (update feed :rss/items
                (partial filterv #(contains? guids (guid/item-guid %))))))))

(defn- shown-feeds
  "The feeds of `library` with the episodes that it lists."
  [library]
  (keep #(shown library %) (keys (:feeds library))))

(defn urls
  "A map from each feed of `library`, with the episodes that it lists, to
  its URL."
  [library]
  (into {} (for [[feed-guid {:keys [url]}] (:feeds library)
                 :let  [feed (shown library feed-guid)]
                 :when feed]
             [feed url])))

(defn- documents
  "The search documents of the feed `feed-guid` of `library`: of the show
  and its episodes for a subscription, and else of its remote items
  alone."
  [library feed-guid]
  (when-let [feed (shown library feed-guid)]
    (let [url (get-in library [:feeds feed-guid :url])]
      (cond->> (feed.search/feed-documents feed url)
        (not (profile/subscribed? (:profile library) feed-guid))
        (filter (comp vector? :id))))))

(defn index
  "A search index of the feeds of `library`, e.g. for a library read back
  without its :index."
  [library]
  (->> (keys (:feeds library))
       (mapcat #(documents library %))
       (feed.search/index)))

(defn- reindexed
  "The `library` with the feed `feed-guid` indexed again, when it keeps an
  index."
  [library feed-guid]
  (if-let [index (:index library)]
    (assoc library :index (apply search/add
                                 (feed.search/without-feed index feed-guid)
                                 (documents library feed-guid)))
    library))

(defn- tidied
  "The `library` with the feed `feed-guid` indexed again while a
  subscription or a remote item needs it, and else without the feed."
  [library feed-guid]
  (if (or (profile/subscribed? (:profile library) feed-guid)
          (seq (remote-guids (:profile library) feed-guid)))
    (reindexed library feed-guid)
    (cond-> (update library :feeds dissoc feed-guid)
      (:index library) (update :index feed.search/without-feed feed-guid))))

(defn- kept
  "The `library` with the feed `feed-guid` cut down to what `keep-fn` gives
  of it, once nothing else needs the whole feed."
  [library feed-guid keep-fn]
  (cond-> library
    (and keep-fn (get-in library [:feeds feed-guid :feed]))
    (update-in [:feeds feed-guid :feed] keep-fn)))

(defn- fetch-result
  "The result of a fetch that `x` stands for: `x` itself, or a map with `x`
  as the :feed when `x` is a parsed feed, which has namespaced keys."
  [x]
  (if (some #(and (keyword? %) (namespace %)) (keys x))
    {:feed x}
    x))

(defn- feed-of
  "The parsed feed of the `result` of a fetch of `url`, or else a throw of
  the diagnostic of the fetch, which says that there's no feed to `use`."
  [url result use]
  (let [{:keys [feed status error]} result]
    (if (map? feed)
      feed
      (let [failed (or error
                       (diagnostics/fetch-failure
                        {:url     url
                         :status  status
                         :message (str "No feed to " use ": " url
                                       " answered " status)}))]
        (throw (ex-info (:message failed) failed))))))

(defn subscribe
  "The `library` subscribed to the feed at `url`, with what was `fetched`
  and the settings in `opts`.

  The `fetched` is the result of dk.simongray.podcast/fetch! or a parsed
  feed, and the `opts` are as profile/configure takes them, besides :at.
  Without `fetched`, or with nil, the feed is fetched at the next refresh,
  and the subscription has the guid of the URL until then.

  It throws when `fetched` has no feed, e.g. after a fetch that failed,
  and when the feed declares the guid of another subscription, at another
  URL, since the feed would take that subscription over. The ex-data is
  the diagnostic of the fetch, or :fetch/guid-taken. To replace the other
  subscription, unsubscribe from it first."
  ([library url]
   (subscribe library url nil {}))
  ([library url fetched]
   (subscribe library url fetched {}))
  ([library url fetched opts]
   {:pre [(map? opts)]}
   (let [at        (time-of library opts)
         settings  (dissoc opts :at :now-fn)
         result    (some-> fetched fetch-result)
         feed      (when result
                     (feed-of url result "subscribe to"))
         feed-guid (guid/feed-guid feed url)
         details   {:url url :title (resolve/title feed)}
         cadence   (:cadence (:config library) {})
         with-feed #(-> %
                        (update :feeds feeds/add url result at cadence)
                        (reindexed feed-guid)
                        (kept feed-guid (:keep-fn (:config library))))]
     (when-let [taken (profile/takeover (:profile library) feed-guid url)]
       (throw (ex-info (:message taken) taken)))
     (-> library
         (update :profile profile/subscribe feed-guid details at)
         (cond->
           (seq settings) (update :profile profile/configure feed-guid
                                  settings at)
           feed           with-feed)))))

(defn unsubscribe
  "The `library` unsubscribed from the feed `feed-guid`, with `opts`. The
  feed and its search documents go, but for the episodes of its remote
  items, and the profile keeps a record of the subscription."
  ([library feed-guid]
   (unsubscribe library feed-guid {}))
  ([library feed-guid opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unsubscribe feed-guid (time-of library opts))
       (tidied feed-guid))))

(defn add-episode
  "The `library` with the episode `item-guid` of the feed at `url`, with
  what was `fetched` and the :at of `opts`, though it isn't subscribed to
  the feed.

  The `fetched` is the result of dk.simongray.podcast/fetch! or a parsed
  feed. The profile notes the episode as a remote item, so that every
  device fetches its feed, and the library lists it among its episodes.
  It throws when `fetched` has no feed, with the diagnostic of the fetch
  as the ex-data, and when the feed has no such episode."
  ([library url fetched item-guid]
   (add-episode library url fetched item-guid {}))
  ([library url fetched item-guid opts]
   {:pre [(map? opts)]}
   (let [at        (time-of library opts)
         result    (fetch-result fetched)
         feed      (feed-of url result "take the episode from")
         feed-guid (guid/feed-guid feed url)
         cadence   (:cadence (:config library) {})
         new?      (not (contains? (:feeds library) feed-guid))]
     (when-not (some #(= item-guid (guid/item-guid %)) (:rss/items feed))
       (throw (ex-info (str "No episode " item-guid " in the feed at " url)
                       {:url url :item-guid item-guid})))
     (-> library
         (update :profile profile/add-remote-item [feed-guid item-guid]
                 {:feed-url url} at)
         (cond-> new? (update :feeds feeds/add url result at cadence))
         (reindexed feed-guid)
         (cond-> new? (kept feed-guid (:keep-fn (:config library))))))))

(defn remove-episode
  "The `library` without the remote item `id`, with the :at of `opts`. The
  feed of the episode goes too, when no subscription or other remote item
  needs it, and the queue and the progress stay as they are."
  ([library id]
   (remove-episode library id {}))
  ([library [feed-guid :as id] opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/remove-remote-item id (time-of library opts))
       (tidied feed-guid))))

(defn subscriptions
  "The live subscriptions of `library` by guid, each with its :settings,
  defaults included, and its :feed once it's fetched."
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
  at `at`, applied to the profile, and kept with the ETag and
  Last-Modified of `fetched` until it's `seconds` old."
  [library url list at seconds
   {:keys [etag last-modified not-before] :as fetched}]
  (let [next-check (time/latest (time/later at seconds) not-before)
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

  The `opts` give the :etag and :last-modified of the fetch, the :title of
  the list and the :folder for its shows. The shows of the list become
  subscriptions."
  ([library url list]
   (follow-list library url list {}))
  ([library url list opts]
   {:pre [(map? opts)]}
   (when-not (map? list)
     (throw (ex-info "The list isn't a parsed OPML list" {:url url})))
   (let [at      (time-of library opts)
         details (select-keys opts [:title :folder])
         seconds (:list-seconds (options library opts))]
     (-> library
         (update :profile profile/follow-list url details at)
         (listed url list at seconds opts)))))

(defn unfollow-list
  "The `library` no longer following the list at `url`, with `opts`."
  ([library url]
   (unfollow-list library url {}))
  ([library url opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unfollow-list url (time-of library opts))
       (update :fetched-lists dissoc url))))

(defn- fetch-list!
  "Fetch the OPML list at `url` with `opts` and the ETag and Last-Modified
  in its library `entry`, in a future or a promise."
  [url {:keys [etag last-modified] :as entry}
   {:keys [credentials-of] :as opts}]
  (let [credentials (when credentials-of (credentials-of url))]
    (podcast/fetch-list! url
                         (cond-> (select-keys opts [:send :timeout-seconds
                                                    :max-bytes :user-agent
                                                    :allow-pred])
                           etag          (assoc :etag etag)
                           last-modified (assoc :last-modified last-modified)
                           credentials   (assoc :credentials credentials)))))

(defn- refresh-lists!
  "Fetch the lists of `library` that are due at `at` with `opts`, and
  apply them, in a future or a promise. The result is a map of the
  :library and the :report."
  [library at opts]
  (let [fetch! (fn [url]
                 (let [entry (get-in library [:fetched-lists url])]
                   (async/then (fetch-list! url entry opts) #(vector url %))))
        apply1 (fn [{:keys [library] :as acc} [url fetched]]
                 (let [{:keys [status list not-before]} fetched
                       error   (or (:error fetched)
                                   (when-not (#{200 304} status)
                                     (diagnostics/fetch-failure
                                      {:url url :status status})))
                       seconds (:list-seconds opts)
                       later   (time/latest (time/later at seconds)
                                            not-before)
                       path    [:library :fetched-lists url]]
                   (cond
                     error
                     (-> acc
                         (assoc-in (conj path :next-check) later)
                         (update :report conj (assoc error :list url)))

                     (= 304 status)
                     (-> acc
                         (assoc-in (conj path :fetched-at) at)
                         (assoc-in (conj path :next-check) later))

                     :else
                     (->> (listed library url list at seconds fetched)
                          (assoc acc :library)))))]
    (async/then (async/all fetch! (feeds/due (:fetched-lists library) at))
                (fn [results]
                  (reduce apply1 {:library library :report []} results)))))

(defn- pending
  "The feeds of the live subscriptions and remote items of `library` that
  it has no feed of yet, as feed entries to fetch for the first time. A
  subscription names the URL before a remote item does."
  [library]
  (let [{:keys [profile feeds]} library
        absent? #(not (contains? feeds %))
        remote  (for [[[feed-guid] {:keys [feed-url]}]
                      (profile/remote-items profile)
                      :when (absent? feed-guid)]
                  [feed-guid {:url feed-url}])
        subs    (for [[feed-guid {:keys [url]}] (profile/subscriptions profile)
                      :when (and url (absent? feed-guid))]
                  [feed-guid {:url url}])]
    (into {} (concat remote subs))))

(defn- first-fetched
  "The accumulator `acc` of a refresh with the feed of the new subscription
  `feed-guid`, fetched at `at` and cut down by `keep-fn`, under the guid
  that the feed declares.

  The subscription moves to that guid, with its settings and tags, and
  gets the title of the feed when it has none. A feed that would take
  another subscription over is left out with a report, and fetched next
  time."
  [acc feed-guid at keep-fn]
  (let [library  (:library acc)
        sub      (get (profile/subscriptions (:profile library)) feed-guid)
        entry    (get-in library [:feeds feed-guid])
        url      (:url sub)
        declared (guid/feed-guid (:feed entry) url)
        details  {:url url :title (resolve/title (:feed entry))}
        moved    #(-> % (dissoc feed-guid) (assoc declared entry))]
    (if-let [taken (profile/takeover (:profile library) declared url)]
      (-> acc
          (update-in [:library :feeds] dissoc feed-guid)
          (update :report conj taken))
      (-> acc
          (update-in [:library :feeds] moved)
          (update-in [:library :profile] profile/rekey feed-guid declared at)
          (cond-> (not (:title sub))
            (update-in [:library :profile] profile/relocate declared details))
          (update :library reindexed declared)
          (update :library kept declared keep-fn)
          (assoc-in [:guids feed-guid] declared)))))

(defn- applied
  "The `library` with the `result` of a refresh of its feeds applied to the
  profile at `at`, and the fresh feeds cut down by `keep-fn`, as a map of
  the :library, what was :added and :moved, and the :report of the feeds
  that would take another subscription over.

  A feed fetched for the first time is that of a new subscription, so its
  episodes aren't new ones, and neither are those of a feed of remote
  items. A feed that's still not fetched is left out, and fetched next
  time."
  [library {:keys [feeds changed moved] :as result} at keep-fn]
  (let [before   (:feeds library)
        library  (assoc library :feeds (into {} (filter (comp :feed val))
                                             feeds))
        noted    (fn [acc feed-guid]
                   (-> acc
                       (update :library reindexed feed-guid)
                       (update :library kept feed-guid keep-fn)))
        fresh    (fn [acc feed-guid]
                   (let [{:keys [feed url]} (get feeds feed-guid)
                         old-feed (get-in before [feed-guid :feed])
                         current  (:profile (:library acc))]
                     (cond
                       (not (profile/subscribed? current feed-guid))
                       (noted acc feed-guid)

                       old-feed
                       (let [{:keys [profile added]}
                             (profile/refresh current url old-feed feed at)]
                         (-> acc
                             (assoc-in [:library :profile] profile)
                             (noted feed-guid)
                             (update :added into added)))

                       :else
                       (first-fetched acc feed-guid at keep-fn))))
        relocate (fn [acc [feed-guid new-url]]
                   (let [now-guid (get-in acc [:guids feed-guid] feed-guid)
                         parsed   (get-in feeds [feed-guid :feed])
                         details  {:url new-url :title (resolve/title parsed)}]
                     (if (contains? (:feeds (:library acc)) now-guid)
                       (-> acc
                           (update-in [:library :profile]
                                      profile/relocate now-guid details)
                           (assoc-in [:moved now-guid] new-url))
                       acc)))
        result   (reduce fresh
                         {:library library :added [] :moved {} :report []}
                         changed)]
    (dissoc (reduce relocate result moved) :guids)))

(defn refresh!
  "Fetch and apply whatever `library` is due to fetch, with the `opts`
  below.

  It fetches the followed lists, the feeds of new subscriptions, and the
  feeds whose next check has come. It returns the library with the result
  under :refresh, in ClojureScript as a promise:

      {:at     #inst \"…\"
       :added  [[feed-guid item-guid] …]   ; the new episodes
       :moved  {feed-guid \"https://…\"}    ; the feeds that moved, and so
                                          ; their subscriptions
       :report [...]}                     ; the fetches that failed, and why

  Nothing is thrown for what the network or a server does. The `opts` are
  the config for this call, and :feed-guids, the feeds to fetch instead of
  those that are due, e.g. those that a Podping names."
  ([library]
   (refresh! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at         (time-of library opts)
         defaults   {:send client/send! :fetch podcast/fetch!}
         opts       (clojure.core/merge defaults (options library opts))
         feeds-opts (-> opts
                        (select-keys [:feed-guids :fetch :credentials-of
                                      :concurrency :cadence :send
                                      :timeout-seconds :max-bytes
                                      :user-agent :allow-pred])
                        (assoc :at at))]
     (async/then
      (refresh-lists! library at opts)
      (fn [{library :library report :report}]
        (let [unfetched  (pending library)
              feeds-opts (cond-> feeds-opts
                           (:feed-guids opts)
                           (update :feed-guids
                                   #(distinct (concat (keys unfetched) %))))]
          (async/then
           (feeds/refresh! (clojure.core/merge unfetched (:feeds library))
                           feeds-opts)
           (fn [step]
             (let [{:keys [library added moved] :as done}
                   (applied library step at (:keep-fn opts))

                   reports (-> []
                               (into report)
                               (into (:report step))
                               (into (:report done)))]
               (assoc library :refresh {:at     at
                                        :added  added
                                        :moved  moved
                                        :report reports}))))))))))

(defn episode-id
  "The id of the episode `item` of `feed` in `library`, [feed-guid
  item-guid]."
  [library feed item]
  (guid/episode-id feed (get (urls library) feed) item))

(defn- item-of
  "The entry of the feed `feed-guid` in `library` and its item
  `item-guid`, as a pair, or nil."
  [library [feed-guid item-guid]]
  (when-let [{:keys [feed] :as entry} (get-in library [:feeds feed-guid])]
    (when-let [item (some #(when (= item-guid (guid/item-guid %)) %)
                          (:rss/items feed))]
      [entry item])))

(defn show
  "The view of the show `feed-guid` of `library`, with the `opts` of
  app.show/view, or nil when the library has no such feed."
  ([library feed-guid]
   (show library feed-guid {}))
  ([library feed-guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds feed-guid])]
     (show/view feed (assoc opts :url url)))))

(defn fetch-linked-shows!
  "Fetch the shows that the show `feed-guid` of `library` links to, with
  the `opts` of app.show/fetch-linked-shows! over the config. It gives a
  map of the :podroll and the :publisher, in ClojureScript as a promise,
  or nil when the library has no such feed."
  ([library feed-guid]
   (fetch-linked-shows! library feed-guid {}))
  ([library feed-guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds feed-guid])]
     (let [unused #{:notes :text :episodes :seasons :links}
           view   (show/view feed {:url url :except unused})]
       (show/fetch-linked-shows! view (options library opts))))))

(defn episode
  "The view of the episode `id`, [feed-guid item-guid], of `library`, with
  the `opts` of app.episode/view over the config, e.g. its :languages, or
  nil when the library has no such episode. Unless the `opts` say, the
  :video? of the view is whether the listener watches the show."
  ([library id]
   (episode library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (let [video? (profile/prefers-video? (:profile library) (first id))]
       (episode/view feed item (clojure.core/merge {:video? video?}
                                                   (options library opts)
                                                   {:url url}))))))

(defn read-files!
  "Read the files of the episode `id` of `library`, with the `opts` of
  app.episode/read-files! over the config. It gives a map of the
  :chapters, the :transcript and the :media, in ClojureScript as a
  promise, or nil when the library has no such episode. The media file is
  the one that the episode's view chooses."
  ([library id]
   (read-files! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (let [video? (profile/prefers-video? (:profile library) (first id))
           opts   (clojure.core/merge {:video? video?} (options library opts))
           unused #{:notes :text :links}
           view   (episode/view feed item (assoc opts :url url :except unused))]
       (episode/read-files! view opts)))))

(defn fetch-remote-values!
  "Fetch the value blocks that the time splits of the episode `id` of
  `library` point at, with the `opts` of app.episode/fetch-remote-values!
  over the config. It gives a map of the :remote-values and the
  :remote-report, in ClojureScript as a promise, or nil when the library
  has no such episode."
  ([library id]
   (fetch-remote-values! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (let [unused #{:notes :text :chapters :links}
           view   (episode/view feed item {:url url :except unused})]
       (episode/fetch-remote-values! view (options library opts))))))

(defn episodes
  "The episodes of the shows of `library` that the criteria `opts` select,
  as [feed item] pairs, e.g.

      (episodes library {:where {:status :unplayed} :order :listening})

  The criteria are those of criteria/episodes. They read the profile of
  the library, and the time of its clock unless the `opts` give :at."
  ([library]
   (episodes library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (criteria/episodes (shown-feeds library)
                      (cond-> (assoc opts
                                     :profile (:profile library)
                                     :url     (urls library)
                                     :at      (time-of library opts))
                        (:index library) (assoc :index (:index library))))))

(defn search
  "The episodes and shows of `library` that match the query `q`, best
  first, with the `opts` below, or nil when the library keeps no index.

  Each result has its :feed, and an episode its :item. The words that
  matched with typos are in the metadata. The `opts` are those of
  dk.simongray.drop-in-search/query, whose :filter-pred gets the result
  with them, and :where, the criteria of criteria/where that an episode
  must meet, which leaves the shows out. The criteria read the profile of
  the library, and the time of its clock unless the `opts` give :at.

      (search library \"value for va\" {:where {:status :unplayed}})"
  ([library q]
   (search library q {}))
  ([library q opts]
   {:pre [(map? opts)]}
   (when-let [index (:index library)]
     (let [locate (feed.search/located (update-vals (:feeds library) :feed))
           ctx    (assoc (select-keys opts [:scale])
                         :profile (:profile library)
                         :url     (urls library)
                         :at      (time-of library opts)
                         :index   index)
           meets? (some-> (:where opts) (criteria/where ctx))
           tests  (cond-> []
                    (:filter-pred opts) (conj (:filter-pred opts))
                    meets?              (conj (fn [{:keys [feed item]}]
                                                (and item (meets? feed item)))))
           opts   (cond-> (dissoc opts :where :at :scale)
                    (seq tests) (assoc :filter-pred
                                       (comp (apply every-pred tests) locate)))
           found  (search/query index q opts)]
       (with-meta (mapv locate found) (meta found))))))

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
  `args` and the time of the clock, in that order. For a series of changes
  at one time, give a function of the profile and the time."
  [library f & args]
  (update library :profile #(apply f % (concat args [(time-of library {})]))))

(defn sync!
  "Sync `library` with the listener's other devices through `backends`,
  with the `opts` of sync/sync! except :cursors.

  It keeps the cursors for the next sync, and compacts the tombstones. It
  returns the library with the result under :sync, in ClojureScript as a
  promise:

      {:cursors  {…}         ; those of the stores, for the next sync
       :at       #inst \"…\"
       :clock    #inst \"…\"   ; the clock of the profile after the sync
       :failures 0           ; the syncs in a row in which a backend failed
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
  "The `library` with `result` merged in, a library that came from an
  earlier value of `library`, e.g. by a refresh or a sync.

  The profiles merge, so nothing that the listener did meanwhile is lost.
  A feed or a list stays only while the merged profile subscribes to it,
  has a remote item of it, or follows it, so one that the listener left
  on another device goes. Of two entries of one feed or list, the later
  fetch wins. The later :refresh and :sync are kept too, and the rest is
  that of `library`."
  [library result]
  (let [profile (profile/merge (:profile library) (:profile result))
        synced  (newer :at (:sync library) (:sync result))
        refresh (newer :at (:refresh library) (:refresh result))
        needed  (into (set (keys (profile/subscriptions profile)))
                      (map ffirst)
                      (profile/remote-items profile))
        feeds   (merged-entries (:feeds library) (:feeds result) needed)
        lists   (merged-entries (:fetched-lists library) (:fetched-lists result)
                                (profile/lists profile))
        same?   #(and (identical? (get-in library [:feeds % :feed])
                                  (get-in feeds [% :feed]))
                      (= (listing (:profile library) %) (listing profile %)))
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

(defn unsynced?
  "Whether the profile of `library` changed since its last sync, by the
  clock of the profile."
  [library]
  (not= (:clock (:sync library)) (:clock (:profile library))))

(defn next-sync
  "When `library` should sync again, with `opts`: right away before the
  first sync, and sooner when the profile changed since the last one."
  ([library]
   (next-sync library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [{:keys [at failures]} (:sync library)
         last-step (when at
                     {:at       at
                      :changed? (unsynced? library)
                      :failures failures})]
     (like-minded/next-sync last-step (time-of library opts)))))

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
   (sync/device-document (:profile library) (time-of library opts) opts)))

(def saved-name
  "The name of the document of a saved library."
  "library.edn")

;; the config can hold functions, so it isn't saved, and the search index
;; has documents of its own
(def saved-keys
  "The parts of a library that are saved by default: the profile, the
  feeds, the followed lists and the last sync, whose cursors fit the
  profile."
  [:profile :feeds :fetched-lists :sync])

(defn save!
  "Write `library` to `store` with the `opts` below, and give the names of
  the documents it put and deleted, as a map of :put and :delete, or a
  map of :error with :library/save-failed, in ClojureScript as a promise.

  It writes library.edn, with the parts of `library` that :keys in `opts`
  names, saved-keys by default. When the library keeps a search index, it
  writes the documents of the index that changed since the last save, and
  deletes those that the index no longer needs. One that it couldn't
  delete is left out of :delete for the next save to delete, and a store
  without :delete keeps them all. Give it a store of its own, e.g. over a
  folder, IndexedDB or the origin private file system, since it deletes
  the documents of any other index there."
  ([library store]
   (save! library store {}))
  ([library store {:keys [keys] :or {keys saved-keys} :as opts}]
   {:pre [(map? opts)]}
   (let [body    (binding [*print-length*         nil
                           *print-level*          nil
                           *print-namespace-maps* false]
                   (pr-str (select-keys library keys)))
         ;; only the documents that changed, so that a save after each
         ;; refresh takes little
         planned (fn [listed]
                   (if-let [index (:index library)]
                     (ciff/plan index (map :name listed))
                     {:put [] :delete []}))
         delete! (fn [name]
                   (async/attempt #((:delete store) name)))
         deleted (fn [names attempts]
                   (vec (for [[name {:keys [error]}] (map vector names attempts)
                              :when (nil? error)]
                          name)))
         saved   (fn [{:keys [put delete]}]
                   (async/then
                    (store/put-all! store (conj put {:name saved-name
                                                     :body body}))
                    (fn [names]
                      (let [delete (if (:delete store) delete [])]
                        (async/then (async/all (map delete! delete))
                                    (fn [attempts]
                                      {:put    names
                                       :delete (deleted delete attempts)}))))))]
     (async/then (async/attempt
                  #(if (:put store)
                     (async/then (if-let [list (:list store)] (list) [])
                                 (comp saved planned))
                     (throw (ex-info "The store is read only" {}))))
                 (fn [{:keys [value error]}]
                   (if error
                     {:error (diagnostics/describe
                              {:id     :library/save-failed
                               :reason (async/explain error)})}
                     value))))))

(defn- reconciled
  "The `library` with the documents of its index of the feeds it doesn't
  have taken out, and the feeds that its index doesn't have indexed, as
  after a save that stopped half way."
  [library]
  (let [indexed (feed.search/feed-guids (:index library))
        feeds   (set (keys (:feeds library)))]
    (as-> library library
      (reduce #(update %1 :index feed.search/without-feed %2)
              library
              (remove feeds indexed))
      (reduce reindexed library (remove indexed feeds)))))

(defn- with-saved
  "The `library` with the parts that `keys` names of the saved library, the
  :value of the `attempt` to fetch its document, and the report of what
  didn't read, as a pair."
  [library keys {:keys [value error] :as attempt}]
  (let [unreadable #(diagnostics/describe
                     {:id :library/unreadable-document :reason %})
        saved      (when (and value (not error))
                     (try
                       (edn/read-string (bytes/as (:body value) :text))
                       (catch #?(:clj Throwable :cljs :default) _
                         nil)))]
    (cond
      error        [library [(unreadable (async/explain error))]]
      (nil? value) [library []]
      (map? saved) [(clojure.core/merge library (select-keys saved keys)) []]
      :else        [library [(unreadable "It isn't a map in EDN")]])))

(defn- with-index
  "The `library` and its `report`, a pair, with the search index read from
  the :value of the `attempt` to fetch the documents of its store, or built
  from its feeds, with :library/index-rebuilt when they don't read."
  [[library report] {:keys [value error] :as attempt}]
  (let [read (cond
               error         {:error error}
               (empty? value) {:error nil}
               :else         (try
                               {:index (ciff/read-files value)}
                               (catch #?(:clj Exception :cljs :default) e
                                 {:error e})))]
    (cond
      (:index read)
      [(reconciled (assoc library :index (:index read))) report]

      ;; nothing saved yet
      (nil? (:error read))
      [(assoc library :index (index library)) report]

      :else
      [(assoc library :index (index library))
       (conj report (diagnostics/describe
                     {:id     :library/index-rebuilt
                      :reason (async/explain (:error read))}))])))

(defn restore!
  "The `library` with what was saved to `store`, read with the `opts`
  below, and with the result under :restore, in ClojureScript as a
  promise.

  The parts of library.edn that :keys in `opts` names, saved-keys by
  default, replace those of `library`, so give it a new library. When the
  library keeps a search index, it's read back, or built again from the
  feeds when its documents are missing or don't read. The result is a map
  of the :report of what didn't read:

      {:report [...]}  ; :library/unreadable-document, :library/index-rebuilt

  A store without the documents gives the library as it is."
  ([library store]
   (restore! library store {}))
  ([library store {:keys [keys] :or {keys saved-keys} :as opts}]
   {:pre [(map? opts)]}
   (let [indexed (fn [[library :as step]]
                   (if (:index library)
                     (async/then (async/attempt
                                  #(store/fetch-all! store
                                                     {:as     :bytes
                                                      :except #{saved-name}}))
                                 (partial with-index step))
                     step))
         done    (fn [[library report]]
                   (assoc library :restore {:report report}))]
     (async/then (async/attempt #((:fetch store) saved-name {}))
                 #(-> (with-saved library keys %)
                      indexed
                      (async/then done))))))

(defn plan
  "The episodes to download and the files to delete on this device with
  `files`, for the profile and feeds of `library`, with the `opts` of
  downloads/plan."
  ([library files]
   (plan library files {}))
  ([library files opts]
   {:pre [(map? opts)]}
   (downloads/plan (:profile library)
                   (shown-feeds library)
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
