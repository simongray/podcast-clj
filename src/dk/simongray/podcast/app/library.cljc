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
       :files         {id {…}}         ; the downloaded files of this device
       :config        {…}}             ; the options below

  The profile is a CRDT, so never change it with assoc or dissoc. A feed
  keeps the episodes that a fetch no longer brings back, as app.feeds
  says, and the views and listings mark each with its :retained fact.
  With :max-items, the oldest of them go once a feed has more items, but
  never one that the profile holds anything for, e.g. progress, a played
  mark that isn't archived, a star or a place in the queue. The config
  holds the options that the functions take, given once:

      {:fetch          f              ; in place of podcast/fetch!
       :send           f              ; in place of client/send!
       :allow-pred     url/public?    ; which URLs a request may go to
       :credentials-of f              ; credentials of a private feed by URL
       :index          {…}            ; the credentials of the Podcast Index
       :languages      [\"da\" \"en\"]    ; of the listener, for transcripts
       :search?        true           ; whether to keep the search index
       :cadence        {…}            ; as app.feeds takes it
       :concurrency    8              ; how many feeds are fetched at once
       :max-pages      5              ; of a paged feed, at its first fetch
       :max-items      500            ; the most items of a feed, all by default
       :archive-after  31536000000    ; when played episodes are archived, in ms
       :only-newer?    false          ; older episodes that appear are new too
       :keep-fn        app.feeds/slim ; what to keep of a feed, all by default
       :local-file-fn  f              ; the downloaded file of an episode id
       :now-fn         f              ; the clock, util.time/now by default
       :timeout-seconds 15 :max-bytes … :user-agent …}

  Each function also takes the same keys as options, for one call, and
  :at, an instant to stamp each change with in place of the time of the
  clock."
  (:refer-clojure :exclude [merge])
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
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
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.downloads :as downloads]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.player :as profile.player]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def default-config
  "The config of a library when none is given. The :list-seconds is how
  long a fetched OPML list stays current, and the :offline-seconds how
  long next-refresh waits after a refresh that found the device offline."
  {:search?         true
   :list-seconds    86400
   :offline-seconds 300})

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
      :files         {}
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

(defn- with-fact
  "The `item` of the feeds `entry` with the fact that the entry keeps of
  it under :retained, when the feed no longer brings it back."
  [entry item]
  (if-let [fact (get-in entry [:retained (guid/item-guid item)])]
    (assoc item :retained fact)
    item))

(defn- identified
  "The feed of the feeds `entry`, with the ids of the directories that the
  entry keeps as its :directory/sources, from which the views link to the
  show in the apps."
  [{:keys [feed ids] :as entry}]
  (cond-> feed
    (seq ids) (assoc :directory/sources ids)))

(defn- marked
  "The feed of the feeds `entry`, with its directory ids, and with the fact
  of each item that it retains under the item's :retained."
  [{:keys [retained] :as entry}]
  (cond-> (identified entry)
    (seq retained) (update :rss/items (partial mapv #(with-fact entry %)))))

(defn- shown
  "The feed `feed-guid` of `library` as the library lists its episodes, or
  nil without the feed: whole for a subscription, and else with the items
  of the remote items alone, each item with its :retained fact."
  [library feed-guid]
  (when-let [feed (some-> (get-in library [:feeds feed-guid]) marked)]
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

(defn- held
  "The ids of the episodes that `profile` holds anything for: progress or
  a played mark, a place in the queue, the inbox or a playlist, a star, a
  bookmark, a clip, a rating, or a remote item."
  [profile]
  (-> (set (keys (profile/episode-entries profile)))
      (into (profile.queue/queue profile))
      (into (profile.queue/inbox profile))
      (into cat (vals (profile.queue/playlists profile)))
      (into (profile.marks/starred profile))
      (into (map :id) (profile.marks/bookmarks profile))
      (into (map :id) (profile.marks/clips profile))
      (into (map :id) (profile.marks/rated profile))
      (into (keys (profile/remote-items profile)))))

(defn- capped
  "The feeds `entry` of `feed-guid` with at most `max-items` items, as far
  as dropping the items it retains allows, the oldest first, but none that
  `profile` holds anything for."
  [entry feed-guid profile max-items]
  (let [{:keys [feed retained]} entry
        excess (- (count (:rss/items feed)) max-items)]
    (if (or (<= excess 0) (empty? retained))
      entry
      (let [ids      (held profile)
            dropped? (fn [item]
                       (let [g (guid/item-guid item)]
                         (and (contains? retained g)
                              (not (contains? ids [feed-guid g])))))
            dropped  (->> (:rss/items feed)
                          (filter dropped?)
                          (sort-by #(some-> (resolve/published %) inst-ms))
                          (take excess)
                          (into #{} (map guid/item-guid)))
            left     (apply dissoc retained dropped)]
        (if (empty? dropped)
          entry
          (cond-> (-> entry
                      (update-in [:feed :rss/items]
                                 #(into [] (remove (comp dropped
                                                         guid/item-guid))
                                        %))
                      (assoc :changed-at (:fetched-at entry)))
            (seq left)    (assoc :retained left)
            (empty? left) (dissoc :retained)))))))

(defn- kept
  "The `library` with the feed `feed-guid` cut down to the :max-items of
  `opts`, as far as capped allows, and to what their :keep-fn gives of it,
  once nothing else needs the whole feed."
  [library feed-guid {:keys [keep-fn max-items] :as opts}]
  (let [feed? (some? (get-in library [:feeds feed-guid :feed]))]
    (cond-> library
      (and max-items feed?)
      (update-in [:feeds feed-guid] capped feed-guid (:profile library)
                 max-items)

      (and keep-fn feed?)
      (update-in [:feeds feed-guid :feed] keep-fn))))

(defn- settled
  "The `library` with the feed `feed-guid` indexed again and cut down as
  the `opts` say, as after a fetch."
  [library feed-guid opts]
  (-> library
      (reindexed feed-guid)
      (kept feed-guid opts)))

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

(defn- key-of
  "The key that `library` keeps the feed that gives itself `feed-guid`
  under: that of the feeds entry whose :guid it is, or else `feed-guid`."
  [library feed-guid]
  (or (some (fn [[k {:keys [guid]}]]
              (when (= feed-guid guid) k))
            (:feeds library))
      feed-guid))

(defn subscribe
  "The `library` subscribed to the feed at `url`, with what was `fetched`
  and the settings in `opts`.

  The `fetched` is the result of dk.simongray.podcast/fetch! or a parsed
  feed, and the `opts` are as profile/configure takes them, besides :at.
  Without `fetched`, or with nil, the feed is fetched at the next refresh,
  and the subscription has the guid of the URL until then. The key of a
  subscription is the guid that its feed gives itself when it's first
  fetched, on any device, and stays when the feed gives itself another.

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
         image     (resolve/artwork feed)
         details   (cond-> {:url url :title (resolve/title feed)}
                     feed  (assoc :guid feed-guid)
                     image (assoc :image image))
         cadence   (:cadence (:config library) {})
         with-feed #(-> %
                        (update :feeds feeds/add url result at cadence)
                        (settled feed-guid (:config library)))]
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
         feed-guid (key-of library (guid/feed-guid feed url))
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
         (cond-> new? (kept feed-guid (:config library)))))))

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
        refreshed  #(:profile (profile.opml/refresh-list % url was list at))]
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
         (update :profile profile.opml/follow-list url details at)
         (listed url list at seconds opts)))))

(defn unfollow-list
  "The `library` no longer following the list at `url`, with `opts`."
  ([library url]
   (unfollow-list library url {}))
  ([library url opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile.opml/unfollow-list url (time-of library opts))
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
                 (let [{:keys [status list not-before error]} fetched
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
    (async/then (async/all fetch! (if (false? (:online? opts))
                                    []
                                    (feeds/due (:fetched-lists library) at)))
                (fn [results]
                  (reduce apply1 {:library library :report []} results)))))

(defn- offline-lists
  "The `library` with the followed lists of `before` whose fetch failed
  before a server answered, by the `report` of a refresh while the device
  was offline, and the report without those failures, as a pair."
  [library before report]
  (let [unconnected? #(and (:list %) (feeds/connection-failed? {:error %}))
        urls         (map :list (filter unconnected? report))
        restored     (fn [lib url]
                       (assoc-in lib [:fetched-lists url]
                                 (get-in before [:fetched-lists url])))]
    [(reduce restored library urls)
     (vec (remove unconnected? report))]))

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

(defn- with-guid
  "The accumulator `acc` of a refresh with the guid that the feed of the
  subscription `feed-guid` gives itself now noted on the subscription, and
  :fetch/guid-changed in the report when that isn't the guid it noted
  before, or the key of one that noted none."
  [acc feed-guid]
  (let [library            (:library acc)
        {:keys [guid url]} (get-in library [:feeds feed-guid])
        before             (get-in library
                                   [:profile :subscriptions feed-guid :guid]
                                   feed-guid)
        changed            (diagnostics/describe {:id        :fetch/guid-changed
                                                  :feed-guid feed-guid
                                                  :url       url
                                                  :old       before
                                                  :new       guid})]
    (cond-> acc
      guid
      (update-in [:library :profile] profile/settle feed-guid guid)

      (and guid (not= before guid))
      (update :report conj changed))))

(defn- first-fetched
  "The accumulator `acc` of a refresh with the feed of the new subscription
  `feed-guid`, fetched at `at` and cut down as the `opts` say.

  A subscription that no device settled yet, one without a :guid, moves to
  the guid that its feed gives itself, with its settings and tags, and one
  that a device settled keeps its key, so that the devices agree. The
  subscription notes the guid of its feed, and gets the title of the feed
  when it has none. A feed that would take another subscription over is
  left out with a report, and fetched next time."
  [acc feed-guid at opts]
  (let [library (:library acc)
        sub     (get (profile/subscriptions (:profile library)) feed-guid)
        entry   (get-in library [:feeds feed-guid])
        target  (if (:guid sub) feed-guid (or (:guid entry) feed-guid))
        details {:url   (:url sub)
                 :title (resolve/title (:feed entry))
                 :image (resolve/artwork (:feed entry))}
        keyed   (update entry :feed guid/with-key target)
        moved   #(-> % (dissoc feed-guid) (assoc target keyed))
        taken   (when (not= target feed-guid)
                  (profile/takeover (:profile library) target (:url sub)))]
    (if taken
      (-> acc
          (update-in [:library :feeds] dissoc feed-guid)
          (update :report conj taken))
      (-> acc
          (update-in [:library :feeds] moved)
          (update-in [:library :profile] profile/rekey feed-guid target at)
          (with-guid target)
          (cond-> (or (not (:title sub))
                      (and (:image details) (not (:image sub))))
            (update-in [:library :profile] profile/relocate target details))
          (update :library settled target opts)
          (assoc-in [:guids feed-guid] target)))))

(defn- applied
  "The `library` with the `result` of a refresh of its feeds applied to the
  profile at `at`, and the fresh feeds cut down as the `opts` say, as a map
  of the :library, what was :added, :backfilled, :renamed and :moved, and
  the :report of the feeds that would take another subscription over or
  give themselves another guid.

  A feed fetched for the first time is that of a new subscription, so its
  episodes aren't new ones, and neither are those of a feed of remote
  items. A feed that's still not fetched is left out, and fetched next
  time."
  [library {:keys [feeds changed moved] :as result} at opts]
  (let [before   (:feeds library)
        library  (assoc library :feeds (into {} (filter (comp :feed val))
                                             feeds))
        noted    (fn [acc feed-guid]
                   (update acc :library settled feed-guid opts))
        fresh    (fn [acc feed-guid]
                   (let [{:keys [feed url]} (get feeds feed-guid)
                         old-feed (get-in before [feed-guid :feed])
                         current  (:profile (:library acc))]
                     (cond
                       (not (profile/subscribed? current feed-guid))
                       (noted acc feed-guid)

                       old-feed
                       (let [{:keys [profile added backfilled renamed]}
                             (profile.queue/refresh current url old-feed feed
                                                    at opts)]
                         (-> acc
                             (assoc-in [:library :profile] profile)
                             (with-guid feed-guid)
                             (noted feed-guid)
                             (update :added into added)
                             (update :backfilled into backfilled)
                             (update :renamed into renamed)))

                       :else
                       (first-fetched acc feed-guid at opts))))
        relocate (fn [acc [feed-guid new-url]]
                   (let [now-guid (get-in acc [:guids feed-guid] feed-guid)
                         parsed   (get-in feeds [feed-guid :feed])
                         details  {:url   new-url
                                   :title (resolve/title parsed)
                                   :image (resolve/artwork parsed)}]
                     (if (contains? (:feeds (:library acc)) now-guid)
                       (-> acc
                           (update-in [:library :profile]
                                      profile/relocate now-guid details)
                           (assoc-in [:moved now-guid] new-url))
                       acc)))
        result   (reduce fresh
                         {:library    library
                          :added      []
                          :backfilled []
                          :renamed    []
                          :moved      {}
                          :report     []}
                         changed)]
    (dissoc (reduce relocate result moved) :guids)))

(defn- counted
  "How many feeds the `step` of feeds/refresh! checked, and of those, how
  many were unchanged, changed and failed, and how many it left due."
  [{:keys [checked changed failed left] :as step}]
  (let [n (count checked)]
    {:checked   n
     :unchanged (- n (count changed) (count failed))
     :changed   (count changed)
     :failed    (count failed)
     :left      (count left)}))

(defn refresh!
  "Fetch and apply whatever `library` is due to fetch, with the `opts`
  below.

  It fetches the followed lists, the feeds of new subscriptions, and the
  feeds whose next check has come. It returns the library with the result
  under :refresh, in ClojureScript as a promise:

      {:at         #inst \"…\"
       :added      [[feed-guid item-guid] …]  ; the new episodes
       :backfilled [[feed-guid item-guid] …]  ; older ones that appeared
       :renamed    [[old-id new-id] …]        ; episodes under a new guid,
                                              ; which took their state along
       :moved      {feed-guid \"https://…\"}   ; the feeds that moved, and so
                                              ; their subscriptions
       :counts     {:checked 120 :unchanged 104 :changed 14 :failed 2
                    :left 0}
       :seconds    4.2                        ; how long it took
       :report     [...]}                     ; the fetches that failed, and why

  Only the new episodes join the queue or the inbox. Nothing is thrown for
  what the network or a server does. The `opts` are the config for this
  call, and these:

  - :feed-guids, the feeds to fetch instead of those that are due, e.g.
    those that a Podping names
  - :online?, false when the device has no network, so that nothing is
    fetched
  - :max-feeds, :max-seconds and :on-feed-fn, to fetch fewer feeds or
    within a deadline, and to hear of each fetch as it ends, as
    app.feeds/refresh! takes them. The feeds left out stay due, and the
    :left of the :counts says how many there are.

  A refresh in which every fetch of a feed failed before a server
  answered, at more than one host, counts as offline too. Offline, no feed
  or list counts it as a failure, each stays due for the next refresh,
  and the report has :refresh/offline."
  ([library]
   (refresh! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at         (time-of library opts)
         defaults   {:send client/send! :fetch podcast/fetch!}
         opts       (clojure.core/merge defaults (options library opts))
         clock      (:now-fn opts time/now)
         started    (clock)
         feeds-opts (-> opts
                        (select-keys [:feed-guids :fetch :credentials-of
                                      :concurrency :cadence :max-pages :send
                                      :timeout-seconds :max-bytes
                                      :user-agent :allow-pred :online?
                                      :max-feeds :max-seconds :on-feed-fn
                                      :now-fn])
                        (assoc :at at))]
     (async/then
      (refresh-lists! library at opts)
      (fn [{listed :library report :report}]
        (let [unfetched  (pending listed)
              feeds-opts (cond-> feeds-opts
                           (:feed-guids opts)
                           (update :feed-guids
                                   #(distinct (concat (keys unfetched) %))))]
          (async/then
           (feeds/refresh! (clojure.core/merge unfetched (:feeds listed))
                           feeds-opts)
           (fn [step]
             (let [offline?        (some (comp #{:refresh/offline} :id)
                                         (:report step))
                   [listed report] (if offline?
                                     (offline-lists listed library report)
                                     [listed report])
                   {:keys [library] :as done} (applied listed step at opts)
                   reports         (-> []
                                       (into report)
                                       (into (:report step))
                                       (into (:report done)))
                   took            (- (inst-ms (clock)) (inst-ms started))]
               (assoc library :refresh (-> {:at at}
                                           (into (select-keys
                                                  done [:added :backfilled
                                                        :renamed :moved]))
                                           (assoc :counts  (counted step)
                                                  :seconds (/ took 1000.0)
                                                  :report  reports))))))))))))

(defn next-refresh
  "When `library` should refresh again, with `opts`: when the first of its
  feeds and followed lists is due, or nil when it has none to fetch. The
  result is never before the time of the call.

  The feed of a subscription that was never fetched is due at once, and a
  feed that's gone is never due. After a refresh that found the device
  offline, the next waits the :offline-seconds of the config."
  ([library]
   (next-refresh library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at       (time-of library opts)
         {:keys [offline-seconds]} (options library opts)
         {:keys [report] last-at :at} (:refresh library)
         check    #(or (:next-check %) at)
         checks   (concat (map check (remove :gone? (vals (:feeds library))))
                          (map check (vals (:fetched-lists library)))
                          (when (seq (pending library))
                            [at]))
         offline? (some (comp #{:refresh/offline} :id) report)]
     (when (seq checks)
       (time/latest at
                    (apply time/earliest checks)
                    (when offline?
                      (time/later last-at offline-seconds)))))))

(defn identify!
  "Ask the directories for the ids of the shows of `library` that have
  none yet, with `opts`, as directory/identify! does, and keep them in
  the entries of their feeds. The views of a show and its episodes then
  link to it in the apps that know it by those ids. It gives the library
  with the :report of the directories that failed under :identify, in
  ClojureScript as a promise.

  The Podcast Index is asked with the credentials of the :index of the
  config. The shows are asked about two at a time, since Apple limits how
  often it answers, and a show whose directory failed is asked again
  next time. The `opts` are the config for this call, and :feed-guids,
  the shows to ask about, ids or not."
  ([library]
   (identify! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [{:keys [index feed-guids] :as opts} (options library opts)
         asking (cond-> (select-keys opts [:send :timeout-seconds
                                           :user-agent :country])
                  index (assoc :credentials index))
         fed?   #(get-in library [:feeds % :feed])
         guids  (filter fed? (or feed-guids
                                 (remove #(get-in library [:feeds % :ids])
                                         (keys (:feeds library)))))
         ask!   (fn [feed-guid]
                  (let [{:keys [feed url]} (get-in library [:feeds feed-guid])]
                    (async/then (directory/identify! feed url asking)
                                #(vector feed-guid %))))
         known  (fn [lib [feed-guid {:keys [ids report]}]]
                  (cond-> lib
                    (empty? report) (assoc-in [:feeds feed-guid :ids] ids)))]
     (async/then (async/in-groups ask! 2 guids)
                 (fn [answers]
                   (assoc (reduce known library answers)
                          :identify {:at     (time-of library opts)
                                     :report (vec (mapcat (comp :report second)
                                                          answers))}))))))

(defn episode-id
  "The id of the episode `item` of `feed` in `library`, [feed-guid
  item-guid]."
  [library feed item]
  (guid/episode-id feed (get (urls library) feed) item))

(defn- item-of
  "The entry of the feed `feed-guid` in `library` and its item
  `item-guid`, with its :retained fact, as a pair, or nil."
  [library [feed-guid item-guid]]
  (when-let [{:keys [feed] :as entry} (get-in library [:feeds feed-guid])]
    (when-let [item (some #(when (= item-guid (guid/item-guid %)) %)
                          (:rss/items feed))]
      [entry (with-fact entry item)])))

(defn notifications
  "The new episodes that the last refresh of `library` found in the shows
  that announce theirs, by show, the show with the newest episode first:

      [{:feed-guid \"…\" :title \"The Daily\" :artwork \"https://…\"
        :episodes  [{:id [feed-guid item-guid] :title \"…\"
                     :published #inst \"…\"}]}]

  A show announces its new episodes when its :notify? setting says so,
  or else the listener's preference, as profile/notifies? says. An
  episode that the listener played already is left out."
  [library]
  (let [profile (:profile library)
        wanted? (fn [[feed-guid :as id]]
                  (and (profile/subscribed? profile feed-guid)
                       (profile/notifies? profile feed-guid)
                       (not (profile/played? profile id))))
        small   (fn [id]
                  (when-let [[_ item] (item-of library id)]
                    (let [title     (resolve/title item)
                          published (resolve/published item)]
                      (cond-> {:id id}
                        title     (assoc :title title)
                        published (assoc :published published)))))
        newest  #(- (or (some-> (:published %) inst-ms) 0))
        of-show (fn [[feed-guid ids]]
                  (let [feed     (get-in library [:feeds feed-guid :feed])
                        episodes (sort-by newest (keep small ids))
                        title    (resolve/title feed)
                        artwork  (resolve/artwork feed)]
                    (when (seq episodes)
                      (cond-> {:feed-guid feed-guid :episodes (vec episodes)}
                        title   (assoc :title title)
                        artwork (assoc :artwork artwork)))))]
    (->> (get-in library [:refresh :added])
         (filter wanted?)
         (group-by first)
         (keep of-show)
         (sort-by (comp newest first :episodes))
         vec)))

(defn show
  "The view of the show `feed-guid` of `library`, with the `opts` of
  app.show/view, or nil when the library has no such feed. Its episodes
  have their :retained facts."
  ([library feed-guid]
   (show library feed-guid {}))
  ([library feed-guid opts]
   (when-let [{:keys [url] :as entry} (get-in library [:feeds feed-guid])]
     (show/view (marked entry) (assoc opts :url url)))))

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
  :video? of the view is whether the listener watches the show. The view
  has the :retained fact of an episode that the feed no longer brings
  back."
  ([library id]
   (episode library id {}))
  ([library id opts]
   (when-let [[{:keys [url] :as entry} item] (item-of library id)]
     (let [video? (profile/prefers-video? (:profile library) (first id))]
       (episode/view (identified entry)
                     item
                     (clojure.core/merge {:video? video?}
                                         (options library opts)
                                         {:url url}))))))

(defn playback
  "What a player needs for the episode `id` of `library`, with the `opts`
  of episode over the config, or nil when the library has no such
  episode:

      {:id         [feed-guid item-guid]
       :src        \"https://…/7.mp3\"   ; the file to play
       :local?     false                ; whether it's a downloaded file
       :enclosure  {:url \"…\" :type \"audio/mpeg\" …}
       :position   754.0                ; where to start, in seconds
       :duration   3612.0               ; when it's known
       :rate       1.5
       :played?    false
       :title      \"…\" :show \"…\" :artwork \"https://…\"
       :poster     \"https://…\"         ; of a video, before it plays
       :chapters   {:source :embedded :document {…}}  ; in the feed itself
       :chapters-url \"https://…\"                    ; a file of chapters
       :transcript {:url \"…\" :type \"text/vtt\" …}   ; e.g. for captions
       :metadata   {…}}                 ; for the Media Session

  The file to play is the downloaded one, which the :local-file-fn of the
  options gives for the id, e.g. an object URL, or else the record of the
  files in the library, and otherwise the enclosure. With :read, what
  read-files! gave for the episode, its chapters and measured duration go
  over those of the feed. The keys without a value are left out, and
  app.episode/metadata gives the metadata."
  ([library id]
   (playback library id {}))
  ([library id {:keys [read] :as opts}]
   {:pre [(map? opts)]}
   (when-let [view (episode library id (assoc opts :except #{:notes :text
                                                             :links}))]
     (let [[feed-guid] id
           profile     (:profile library)
           feed        (get-in library [:feeds feed-guid :feed])
           view        (cond-> view
                         (:chapters read) (assoc :chapters (:chapters read)))
           local-file  (or (when-let [f (:local-file-fn (options library opts))]
                             (f id))
                           (downloads/local-file (:files library) id))
           src         (or local-file (:url (:enclosure view)))
           duration    (or (:duration (profile/episode-entry profile id))
                           (:duration read)
                           (:duration view))]
       (into {:id       id
              :local?   (some? local-file)
              :position (profile/resume-position profile feed-guid id
                                                 (time-of library opts))
              :rate     (profile/playback-rate profile feed-guid)
              :played?  (profile/played? profile id)
              :metadata (episode/metadata feed view)}
             (remove (comp nil? val))
             {:src          src
              :enclosure    (:enclosure view)
              :duration     duration
              :title        (:title view)
              :show         (resolve/title feed)
              :artwork      (:artwork view)
              :poster       (:poster view)
              :chapters     (:chapters view)
              :chapters-url (:chapters-url view)
              :transcript   (:transcript view)})))))

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

  The criteria are those of criteria/episodes. They read the profile and
  the downloaded files of the library, and the time of its clock unless
  the `opts` give :at. With :feed-guids, only the episodes of those shows
  are read, e.g. for the page of a show."
  ([library]
   (episodes library {}))
  ([library {:keys [feed-guids] :as opts}]
   {:pre [(map? opts)]}
   (criteria/episodes (if feed-guids
                        (keep #(shown library %) feed-guids)
                        (shown-feeds library))
                      (cond-> (assoc (dissoc opts :feed-guids)
                                     :profile (:profile library)
                                     :files   (:files library {})
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

(defn- next-of-show
  "The first episode of the show of the episode `id` in `library` that
  isn't played, in its listening order, but for `id` itself, or nil."
  [library [feed-guid :as id]]
  (let [profile (:profile library)]
    (->> (episodes library {:feed-guids [feed-guid] :order :listening})
         (map (fn [[feed item]] (episode-id library feed item)))
         (remove #(or (= id %) (profile/played? profile %)))
         first)))

(defn step
  "The `library` after the player `event`, with `opts`, both as
  profile.player/step takes them.

  It has the new profile, the :effects to apply, and what's left of
  the :sleep timer of this device. The `opts` go over the :sleep of
  the library. With the listener's :continuous?, an episode that ends
  with no other queued is followed by the first unplayed episode of its
  show in the listening order: the next of a serial show, and the newest
  of an episodic one."
  ([library event]
   (step library event {}))
  ([library event opts]
   {:pre [(map? opts)]}
   (let [at   (time-of library opts)
         opts (clojure.core/merge {:continue-fn #(next-of-show library %)}
                                  (select-keys library [:sleep])
                                  opts)
         {:keys [profile effects sleep]}
         (profile.player/step (:profile library) event at opts)]
     (cond-> (assoc (dissoc library :sleep) :profile profile :effects effects)
       sleep (assoc :sleep sleep)))))

(defn mark-show-played
  "The `library` with every episode of the show `feed-guid` marked as
  played with one stamp, with `opts`, e.g. for a show that the listener
  heard elsewhere. The time is that of the clock unless the `opts` give
  :at."
  ([library feed-guid]
   (mark-show-played library feed-guid {}))
  ([library feed-guid opts]
   {:pre [(map? opts)]}
   (let [feed (get-in library [:feeds feed-guid :feed])
         ids  (map #(episode-id library feed %) (:rss/items feed))]
     (update library :profile profile/mark-all-played ids
             (time-of library opts)))))

(defn update-profile
  "The `library` with its profile replaced by what `f` gives for it, the
  `args` and the time of the clock, in that order. For a series of changes
  at one time, give a function of the profile and the time."
  [library f & args]
  (update library :profile #(apply f % (concat args [(time-of library {})]))))

(defn- compacting
  "The :compact option of a sync of `library` with `opts`: false, or the
  options of profile/compact, with the :archive-after of `opts` or of the
  config."
  [library opts]
  (let [{:keys [compact archive-after]} (options library opts)]
    (if (false? compact)
      false
      (into (if archive-after {:archive-after archive-after} {}) compact))))

(defn sync!
  "Sync `library` with the listener's other devices through `backends`,
  with the `opts` of sync/sync! except :cursors and :failures.

  It keeps the cursors for the next sync, and compacts the profile as
  profile/compact does, with the :archive-after of the config. It returns
  the library with the result under :sync, in ClojureScript as a promise:

      {:cursors  {…}         ; those of the stores, for the next sync
       :at       #inst \"…\"
       :clock    #inst \"…\"   ; the clock of the profile after the sync
       :failures 0           ; the syncs in a row in which a backend failed
       :report   [...]}      ; with :sync/backend-failed for each that did"
  ([library backends]
   (sync! library backends {}))
  ([library backends opts]
   {:pre [(map? opts)]}
   (let [before    (:profile library)
         opts      (assoc opts :compact (compacting library opts))
         sync-opts (clojure.core/merge
                    opts
                    {:at (time-of library opts)}
                    (select-keys (:sync library) [:cursors :failures]))]
     (async/then (sync/sync! before backends sync-opts)
                 (fn [step]
                   (let [profile (sync/finish before step opts)]
                     (assoc library
                            :profile profile
                            :sync    (-> (dissoc step :state)
                                         (assoc :clock (:clock profile))))))))))

(defn- newer
  "Of `a` and `b`, two maps with an instant under `k`, the newer one, or
  `b` when `a` isn't newer."
  [k a b]
  (if (pos? (compare (k a) (k b))) a b))

(defn- merged-entries
  "The entries of `mine` and `theirs` whose key `keep?` holds for, and of
  two with one key, the one fetched later, with the directory ids of
  either."
  [mine theirs keep?]
  (let [later (fn [a b]
                (let [e   (newer :fetched-at a b)
                      ids (or (:ids e) (:ids a) (:ids b))]
                  (cond-> e ids (assoc :ids ids))))]
    (into {}
          (filter (comp keep? key))
          (merge-with later mine theirs))))

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
                                (profile.opml/lists profile))
        same?   #(and (identical? (get-in library [:feeds % :feed])
                                  (get-in feeds [% :feed]))
                      (= (listing (:profile library) %) (listing profile %)))
        guids   (into (set (keys feeds)) (keys (:feeds library)))
        changed (remove same? guids)
        compact (compacting library {})]
    (reduce reindexed
            (cond-> (assoc library
                           :profile       (cond-> profile
                                            (and compact
                                                 (:at synced)
                                                 (not= synced (:sync library)))
                                            (profile/compact (:at synced)
                                                             compact))
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
  feeds, the followed lists, the last sync, whose cursors fit the profile,
  and the downloaded files."
  [:profile :feeds :fetched-lists :sync :files])

(def version
  "The version of the shape of a saved library: 4 since a played episode
  can be in the archive alone, 3 since the settings of a subscription are
  one entry, 2 since each feed went to a document of its own. A saved
  library without one is version 1."
  4)

(def migrations
  "The migration of a saved library from each version to the next, by the
  version it takes, each a function of the saved map.

  Version 1 holds the feeds inline, and they stay so until the next save
  writes them out as documents. Version 2 holds an entry for each setting
  of a subscription. Version 3 has no archive, so it reads as it is."
  {1 (fn [saved]
       (cond-> saved
         (map? (:feeds saved))
         (update :feeds update-vals
                 #(cond-> %
                    (and (:feed %) (:fetched-at %) (not (:changed-at %)))
                    (assoc :changed-at (:fetched-at %))))))
   2 (fn [saved]
       (cond-> saved
         (map? (:profile saved)) (update :profile profile/upgrade)))
   3 identity})

(defn ^:no-doc document-name
  "The name of the document of the feed `feed-guid` that changed at the
  instant `changed-at`: feed-<id>-<milliseconds>.edn. The id is the guid
  when it's a UUID, and else its UUIDv5, since a guid can be any text and
  a document's name must be a plain file name in every store."
  [feed-guid changed-at]
  (let [id (if (guid/guid? feed-guid)
             (str/lower-case feed-guid)
             (guid/uuid-v5 guid/podcast-namespace (str feed-guid)))]
    (str "feed-" id "-" (inst-ms changed-at) ".edn")))

(defn- feed-document?
  "Whether `name` is that of the document of a feed."
  [name]
  (boolean (re-matches #"feed-[0-9a-f-]{36}-\d+\.edn" (str name))))

(defn- entry-document
  "The name of the document of the feeds entry `entry` of `feed-guid`, by
  when its feed changed, or was fetched for an entry that doesn't say."
  [feed-guid entry]
  (document-name feed-guid (or (:changed-at entry)
                               (:fetched-at entry)
                               (time/->inst 0))))

(defn- printed
  "The EDN of `x`, whole."
  [x]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-namespace-maps* false]
    (pr-str x)))

(defn- saved-map
  "The parts of `library` that `keys` names, as library.edn holds them:
  with the :version, and each feeds entry with the name of the document
  of its feed in place of the feed and its retained items."
  [library keys]
  (let [outlined (fn [[feed-guid entry]]
                   [feed-guid
                    (if (:feed entry)
                      (-> (dissoc entry :feed :retained)
                          (assoc :document (entry-document feed-guid entry)))
                      entry)])]
    (cond-> (assoc (select-keys library keys) :version version)
      (some #{:feeds} keys) (update :feeds #(into {} (map outlined) %)))))

(defn- feeds-plan
  "The documents of the `feeds` of a library to put into a store that
  holds the documents of the `names`, and the names to delete from it, as
  a map of :put and :delete.

  A feed's document is put when the store doesn't hold its name, and a
  feed document that no feed names is deleted. Without `names`, as of a
  store that can't list, it's every document and no deletes."
  [feeds names]
  (let [held   (set names)
        wanted (into {}
                     (for [[feed-guid entry] feeds
                           :when (:feed entry)]
                       [(entry-document feed-guid entry) entry]))]
    {:put    (for [[name entry] wanted
                   :when (not (contains? held name))]
               {:name name
                :body (printed (select-keys entry [:feed :retained]))})
     :delete (->> held
                  (filter #(and (feed-document? %) (not (contains? wanted %))))
                  sort
                  vec)}))

(defn- newer-format
  "The :library/newer-format diagnostic of the last restore of `library`,
  if any."
  [library]
  (some #(when (= :library/newer-format (:id %)) %)
        (get-in library [:restore :report])))

(defn save!
  "Write `library` to `store` with the `opts` below, and give the names of
  the documents it put and deleted, as a map of :put and :delete, or a
  map of :error, in ClojureScript as a promise.

  It writes library.edn, with the parts of `library` that :keys in `opts`
  names, saved-keys by default, and the version of their shape. Each feed
  goes to a document of its own, which is put when the feed changed since
  the last save, and the documents of the feeds that changed or went are
  deleted. When the library keeps a search index, it writes the documents
  of the index that changed since the last save, and deletes those that
  the index no longer needs. One that it couldn't delete is left out of
  :delete for the next save to delete, and a store without :delete keeps
  them all. Give it a store of its own, e.g. over a folder, IndexedDB or
  the origin private file system, since it deletes the documents of any
  other library there.

  The :error is :library/save-failed when the store failed, and
  :library/newer-format when `library` was restored from a library of a
  newer version, which it doesn't write over."
  ([library store]
   (save! library store {}))
  ([library store {:keys [keys] :or {keys saved-keys} :as opts}]
   {:pre [(map? opts)]}
   (let [refused (newer-format library)
         body    (printed (saved-map library keys))
         none    {:put [] :delete []}
         ;; only the documents that changed, so that a save after each
         ;; refresh takes little
         planned (fn [listed]
                   (let [names (map :name listed)
                         index (if-let [index (:index library)]
                                 (ciff/plan index names)
                                 none)
                         feeds (if (some #{:feeds} keys)
                                 (feeds-plan (:feeds library) names)
                                 none)]
                     {:put    (concat (:put feeds) (:put index))
                      :delete (into (:delete index) (:delete feeds))}))
         delete! (fn [name]
                   (async/attempt #((:delete store) name)))
         deleted (fn [names attempts]
                   (vec (for [[name {:keys [error]}] (map vector names attempts)
                              :when (nil? error)]
                          name)))
         ;; library.edn last, so that a save that stops half way leaves the
         ;; old one naming documents that are still there
         saved   (fn [{:keys [put delete]}]
                   (async/then
                    (store/put-all! store (concat put [{:name saved-name
                                                        :body body}]))
                    (fn [names]
                      (let [delete (if (:delete store) delete [])]
                        (async/then (async/all (map delete! delete))
                                    (fn [attempts]
                                      {:put    names
                                       :delete (deleted delete attempts)}))))))]
     (async/then (async/attempt
                  #(cond
                     refused     nil
                     (:put store) (async/then (if-let [list (:list store)]
                                                (list)
                                                [])
                                              (comp saved planned))
                     :else       (throw (ex-info "The store is read only" {}))))
                 (fn [{:keys [value error]}]
                   (cond
                     refused {:error refused}
                     error   (diagnostics/failed
                              {:id     :library/save-failed
                               :reason (async/explain error)})
                     :else   value))))))

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

(defn- read-map
  "The map that the EDN of the :body of `document` holds, or nil when it
  doesn't read as one."
  [document]
  (let [x (try
            (edn/read-string (bytes/as (:body document) :text))
            (catch #?(:clj Throwable :cljs :default) _
              nil))]
    (when (map? x)
      x)))

(defn- migrated
  "The saved library `saved`, of its :version or else of version 1,
  brought up to this version by the migrations."
  [saved]
  (loop [s saved]
    (let [v (:version s 1)]
      (if (< v version)
        (recur (assoc ((migrations v) s) :version (inc v)))
        s))))

(defn- with-saved
  "The `library` with the parts that `keys` names of the saved library, the
  :value of the `attempt` to fetch its document, brought up to this
  version, and the report of what didn't read, as a pair.

  A saved library of a newer version leaves the library as it is, and a
  saved profile that breaks the rules of profile/problem leaves its
  profile, with :library/newer-format or :library/malformed-profile in the
  report."
  [library keys {:keys [value error] :as attempt}]
  (let [unreadable #(diagnostics/describe
                     {:id :library/unreadable-document :name saved-name
                      :reason %})
        saved      (when-not error (read-map value))
        v          (:version saved 1)
        problem    (when (some #{:profile} keys)
                     (some-> (:profile saved) profile/problem))]
    (cond
      error              [library [(unreadable (async/explain error))]]
      (nil? value)       [library []]
      (nil? saved)       [library [(unreadable "It isn't a map in EDN")]]
      (not (pos-int? v)) [library [(unreadable "Its version is no number")]]

      (< version v)
      [library [(diagnostics/describe
                 {:id      :library/newer-format
                  :version v
                  :reason  (str "It's of version " v ", and this version "
                                "of podcast-clj reads up to " version)})]]

      problem
      [(clojure.core/merge library (dissoc (select-keys (migrated saved) keys)
                                           :profile))
       [(diagnostics/describe {:id     :library/malformed-profile
                               :reason (str problem)})]]

      :else
      [(clojure.core/merge library (select-keys (migrated saved) keys)) []])))

(defn- with-feeds
  "The `library` and its `report`, a pair, with the feed and the retained
  facts of each feeds entry that names a document read from `store`, in a
  future or a promise. Each feed has its key, as guid/with-key gives it.

  An entry whose document is missing or doesn't read keeps only its URL,
  so that the next refresh fetches it again, with
  :library/unreadable-document in the report."
  [[library report] store]
  (let [outlined (for [[feed-guid {:keys [document feed]}] (:feeds library)
                       :when (and document (not feed))]
                   [feed-guid document])
        loaded   (fn [{:keys [value error]}]
                   (let [{:keys [feed retained]} (when-not error
                                                   (read-map value))]
                     (cond
                       (map? feed)  (cond-> {:feed feed}
                                      (map? retained) (assoc :retained
                                                             retained))
                       error        {:reason (async/explain error)}
                       (nil? value) {:reason "It isn't in the store"}
                       :else        {:reason "It isn't a feed in EDN"})))
        fetch!   (fn [[feed-guid document]]
                   (async/then (async/attempt #((:fetch store) document {}))
                               #(vector feed-guid document (loaded %))))
        applied  (fn [[library report] [feed-guid document read]]
                   (if (:feed read)
                     [(update-in library [:feeds feed-guid]
                                 #(-> (clojure.core/merge % read)
                                      (dissoc :document)))
                      report]
                     [(update-in library [:feeds feed-guid] select-keys [:url])
                      (conj report (diagnostics/describe
                                    {:id        :library/unreadable-document
                                     :name      document
                                     :feed-guid feed-guid
                                     :reason    (:reason read)}))]))
        keyed    (fn [[feed-guid entry]]
                   [feed-guid (cond-> entry
                                (:feed entry)
                                (update :feed guid/with-key feed-guid))])]
    (async/then (async/all fetch! outlined)
                (fn [loaded]
                  (let [[library report] (reduce applied [library report]
                                                 loaded)]
                    [(update library :feeds #(into {} (map keyed) %))
                     report])))))

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
  default, replace those of `library`, so give it a new library, and each
  feed is read from its document. A library saved by an older version is
  brought up to this one. When the library keeps a search index, it's read
  back, or built again from the feeds when its documents are missing or
  don't read. The result is a map of the :report of what didn't read:

      {:report [...]}  ; :library/unreadable-document, :library/index-rebuilt,
                       ; :library/malformed-profile, :library/newer-format

  A store without the documents gives the library as it is, and so does a
  library saved by a newer version, which save! then won't write over."
  ([library store]
   (restore! library store {}))
  ([library store {:keys [keys] :or {keys saved-keys} :as opts}]
   {:pre [(map? opts)]}
   (let [not-index? #(or (= saved-name %) (feed-document? %))
         fetched    #(store/fetch-all! store {:as :bytes :except not-index?})
         indexed    (fn [[library :as step]]
                      (if (:index library)
                        (async/then (async/attempt fetched)
                                    (partial with-index step))
                        step))
         done       (fn [[library report]]
                      (assoc library :restore {:report report}))
         newer?     (fn [[_ report]]
                      (some (comp #{:library/newer-format} :id) report))]
     (async/then (async/attempt #((:fetch store) saved-name {}))
                 (fn [attempt]
                   (let [step (with-saved library keys attempt)]
                     (if (newer? step)
                       (done step)
                       (-> (with-feeds step store)
                           (async/then indexed)
                           (async/then done)))))))))

(defn plan
  "The episodes to download and the files to delete on this device with
  `files`, for the profile and feeds of `library`, with the `opts` of
  downloads/plan. Without `files`, it's the record of the files in the
  library."
  ([library]
   (plan library nil {}))
  ([library files]
   (plan library files {}))
  ([library files opts]
   {:pre [(map? opts)]}
   (downloads/plan (:profile library)
                   (shown-feeds library)
                   (or files (:files library))
                   (time-of library opts)
                   (assoc opts :url (urls library)))))

(defn start-download
  "The `library` with the download of `entry`, one of the :download of
  its plan, started at the time of the call, with `opts`, as
  downloads/start-download records it."
  ([library entry]
   (start-download library entry {}))
  ([library entry opts]
   {:pre [(map? opts)]}
   (update library :files downloads/start-download entry
           (time-of library opts))))

(defn finish-download
  "The `library` with the download of the episode `id` finished at the
  time of the call, with `opts`, and the :path and :bytes of the file in
  `details`, as downloads/finish-download records it."
  ([library id details]
   (finish-download library id details {}))
  ([library id details opts]
   {:pre [(map? opts)]}
   (update library :files downloads/finish-download id details
           (time-of library opts))))

(defn fail-download
  "The `library` with the download of the episode `id` failed at the time
  of the call, with `opts`, and the `details` of downloads/fail-download:
  the bytes on the device and the headers that came with them, and the
  error."
  ([library id details]
   (fail-download library id details {}))
  ([library id details opts]
   {:pre [(map? opts)]}
   (update library :files downloads/fail-download id details
           (time-of library opts))))

(defn remove-file
  "The `library` without the file of the episode `id`, deleted at the time
  of the call, with `opts`. The entry stays, so that no plan downloads it
  again."
  ([library id]
   (remove-file library id {}))
  ([library id opts]
   {:pre [(map? opts)]}
   (update library :files downloads/remove-file id (time-of library opts))))

(defn keep-file
  "The `library` with the file of the episode `id` kept whatever the rules
  say, or no longer kept when `keep?` is false."
  [library id keep?]
  (update library :files downloads/keep-file id keep?))

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
