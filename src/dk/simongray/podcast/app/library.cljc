(ns dk.simongray.podcast.app.library
  "The subscriptions, feeds, queue and play progress of an app, in one map.

  The parts of a library are:

      {:profile       {…}              ; the profile, a CRDT that syncs
       :feeds         {feed-guid {…}}  ; the feeds, as app.feeds keeps them
       :fetched-lists {url {…}}        ; the followed OPML lists, as fetched
       :index         {…}              ; the search index, with :search?
       :sync          {:cursors {…} …} ; what the next sync starts from
       :sleep         {:episodes 1}    ; the sleep timer of this device, if set
       :files         {id {…}}         ; the downloaded files of this device
       :config        {…}}             ; the options below

  The profile is a CRDT, so never change it with assoc or dissoc. Its
  :device is the id of this device. A feed keeps the episodes that a fetch
  no longer brings back, as app.feeds says, and the views and listings
  mark each with its :retained fact. With :max-items, the oldest of them
  go once a feed has more items, but never one that the profile holds
  anything for, e.g. progress, a played mark that isn't archived, a star
  or a place in the queue.

  A function that changes the library returns the new library. One that
  also tells what it did returns a map of the :library and what it tells,
  e.g. the :effects of step, or the new episodes and the :report of
  refresh!.

  The config holds the options that the functions take, given once, and
  only data, so that a library is a value that prints and compares:

      {:index          {…}            ; the credentials of the Podcast Index
       :languages      [\"da\" \"en\"]    ; of the listener, for transcripts
       :search?        true           ; whether to keep the search index
       :cadence        {…}            ; as app.feeds takes it
       :concurrency    8              ; how many feeds are fetched at once
       :max-pages      5              ; of a paged feed, at its first fetch
       :max-items      500            ; the most items of a feed, all by default
       :archive-after  31536000000    ; when played episodes are archived, in ms
       :only-newer?    false          ; older episodes that appear are new too
       :new-episodes   :inbox         ; where every show's new episodes go
       :extension      \".transit\"     ; of the documents, as make takes it
       :timeout-seconds 15 :max-bytes … :user-agent …}

  Each function also takes the same keys as options, for one call, and
  :at, an instant to stamp each change with in place of the time of the
  clock. These options are functions or hold them, so they stay out of
  the config, which make refuses with any of them, and you give them to
  each call, e.g. from a map of your own:

      {:fetch          f              ; in place of podcast/fetch!
       :send           f              ; in place of client/send!
       :allow-pred     url/public?    ; which URLs a request may go to
       :credentials-of f              ; credentials of a private feed by URL
       :keep-fn        app.feeds/slim ; what to keep of a feed, all by default
       :local-file-fn  f              ; the downloaded file of an episode id
       :cache          store          ; for companion files, see read-files!
       :codec          transit/codec  ; of the documents, EDN by default
       :lenient-parser f              ; for feeds the repairs can't fix
       :now-fn         f}             ; the clock, util.time/now by default

  The codec has to be of the :extension that make took, since the
  documents are named by it, and a call with another throws."
  (:refer-clojure :exclude [merge])
  (:require [clojure.string :as str]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.ciff :as ciff]
            [dk.simongray.like-minded :as like-minded]
            [dk.simongray.like-minded.document :as like-minded.document]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library.impl :as impl]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.downloads :as downloads]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.player :as profile.player]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.encoding :as encoding]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.websub :as websub]))

(def default-config
  "The config of a library when none is given. The :list-seconds is how
  long a fetched OPML list stays current, and the :offline-seconds how
  long next-refresh waits after a refresh that found the device offline."
  impl/default-config)

(defn make
  "A library for the device `device`, made with the `opts` below.

  The `device` is a stable id that tells the listener's devices apart,
  made once per installation. The `opts` describe the device for the other
  devices, such as its :name and :platform, and give the :config and the
  :codec of the documents, whose :extension the config keeps, EDN's by
  default. It throws for a config that holds one of the options that are
  functions, e.g. :send, since each call takes those instead."
  ([device]
   (make device {}))
  ([device {:keys [config codec] :as opts}]
   {:pre [(map? opts)]}
   (when-let [functions (seq (filter #(contains? config %) impl/function-keys))]
     (throw (ex-info (str "The config of a library holds data alone, so give "
                          (str/join ", " functions) " to each call instead")
                     {:keys (vec functions)})))
   (let [description (dissoc opts :config :codec :at)
         config      (clojure.core/merge default-config
                                         config
                                         {:extension (:extension (or codec impl/edn-codec))})
         described   (fn [p]
                       (let [at (impl/time-of {:config config} opts)]
                         (crdt/identify p description at)))]
     {:profile       (cond-> (profile/make device)
                       (seq description) described)
      :feeds         {}
      :fetched-lists {}
      :index         (when (:search? config) (feed.search/index))
      :sync          {}
      :files         {}
      :config        config})))

(defn- listing
  "How `profile` lists the episodes of the feed `feed-guid`: whether it's
  subscribed, and the item guids of its remote items, as a pair."
  [profile feed-guid]
  [(profile/subscribed? profile feed-guid)
   (impl/remote-guids profile feed-guid)])

(defn loaded?
  "Whether `library` holds the feed `feed-guid` in memory, rather than only
  in the document that a save wrote, as after restore! with :load or after
  unload!."
  [library feed-guid]
  (some? (get-in library [:feeds feed-guid :feed])))

(defn unloaded
  "The guids of the feeds that `library` keeps in their documents alone,
  for load! to read back."
  [library]
  (vec (for [[feed-guid entry] (:feeds library)
             :when (impl/unloaded? entry)]
         feed-guid)))

(defn urls
  "A map from each feed of `library`, with the episodes that it lists, to
  its URL."
  [library]
  (into {} (for [[feed-guid {:keys [url]}] (:feeds library)
                 :let  [feed (impl/shown library feed-guid)]
                 :when feed]
             [feed url])))

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
  feed, and the `opts` are as profile/configure takes them, besides :at
  and the options that hold functions, e.g. :keep-fn.
  Without `fetched`, or with nil, the feed is fetched at the next refresh,
  and the subscription has the guid of the URL until then. The start of a
  feed that discovery/fetch-head! gave counts as no feed, but for the
  title, the artwork and the guid that it declares. The key of a
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
   (let [at        (impl/time-of library opts)
         settings  (impl/settings opts)
         result    (some-> fetched impl/fetch-result)
         feed      (when result
                     (impl/feed-of url result "subscribe to"))
         head?     (discovery/head? feed)
         feed-guid (guid/feed-guid feed url)
         image     (resolve/artwork feed)
         settled?  (and feed
                        (or (not head?) (guid/guid? (:podcast/guid feed))))
         details   (cond-> {:url url :title (resolve/title feed)}
                     settled? (assoc :guid feed-guid)
                     image    (assoc :image image))
         cadence   (:cadence (impl/options library opts) {})
         with-feed #(-> %
                        (update :feeds feeds/add url result at cadence)
                        (impl/settled feed-guid (impl/options library opts)))]
     (when-let [taken (profile/takeover (:profile library) feed-guid url)]
       (throw (ex-info (:message taken) taken)))
     (-> library
         (update :profile profile/subscribe feed-guid details at)
         (cond->
           (seq settings)        (update :profile profile/configure feed-guid
                                         settings at)
           (and feed (not head?)) with-feed)))))

(defn subscribe-local
  "The `library` subscribed to the show of local files `feed`, which
  feed.local/feed built, with the settings in `opts`, besides :at and the
  options that hold functions.

  The feed stays on this device, as its files do, and no refresh fetches
  it, so subscribe again with the files as they are when they change. The
  profile syncs the subscription and the progress of the episodes, so a
  device with the same files, which make the same guids, shares them, and
  one without them lists the show by its title. An image that's a data URL
  stays out of the profile."
  ([library feed]
   (subscribe-local library feed {}))
  ([library feed opts]
   {:pre [(map? opts)]}
   (let [at        (impl/time-of library opts)
         settings  (impl/settings opts)
         feed-guid (:podcast/guid feed)
         image     (resolve/artwork feed)
         details   (cond-> {:url   nil
                            :title (resolve/title feed)
                            :guid  feed-guid}
                     (and image (not (str/starts-with? image "data:")))
                     (assoc :image image))
         old       (get-in library [:feeds feed-guid])
         keyed     (guid/with-key feed feed-guid)
         entry     {:local?     true
                    :guid       feed-guid
                    :feed       keyed
                    :changed-at (if (= keyed (:feed old))
                                  (:changed-at old at)
                                  at)
                    :fetched-at at}]
     (when-let [taken (profile/takeover (:profile library) feed-guid nil)]
       (throw (ex-info (:message taken) taken)))
     (-> library
         (update :profile profile/subscribe feed-guid details at)
         (cond->
           (seq settings) (update :profile profile/configure feed-guid
                                  settings at))
         (assoc-in [:feeds feed-guid] entry)
         (impl/settled feed-guid (impl/options library opts))))))

(defn unsubscribe
  "The `library` unsubscribed from the feed `feed-guid`, with `opts`. The
  feed and its search documents go, but for the episodes of its remote
  items, and the profile keeps a record of the subscription."
  ([library feed-guid]
   (unsubscribe library feed-guid {}))
  ([library feed-guid opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unsubscribe feed-guid
               (impl/time-of library opts))
       (impl/tidied feed-guid))))

(defn add-episode
  "The `library` with the episode `item-guid` of the feed at `url`, with
  what was `fetched` and the `opts`, though it isn't subscribed to the
  feed.

  The `fetched` is the result of dk.simongray.podcast/fetch! or a parsed
  feed. The profile notes the episode as a remote item, so that every
  device fetches its feed, and the library lists it among its episodes.
  It throws when `fetched` has no feed, with the diagnostic of the fetch
  as the ex-data, and when the feed has no such episode."
  ([library url fetched item-guid]
   (add-episode library url fetched item-guid {}))
  ([library url fetched item-guid opts]
   {:pre [(map? opts)]}
   (let [at        (impl/time-of library opts)
         result    (impl/fetch-result fetched)
         feed      (impl/feed-of url result "take the episode from")
         feed-guid (key-of library (guid/feed-guid feed url))
         cadence   (:cadence (impl/options library opts) {})
         ;; a feed held for remote items alone lacks the other episodes
         fresh?    (or (not (contains? (:feeds library) feed-guid))
                       (impl/alone? library feed-guid))]
     (when-not (some #(= item-guid (guid/item-guid %)) (:rss/items feed))
       (throw (ex-info (str "No episode " item-guid " in the feed at " url)
                       {:url url :item-guid item-guid})))
     (-> library
         (update :profile profile/add-remote-item [feed-guid item-guid]
                 {:feed-url url} at)
         (cond-> fresh? (update :feeds feeds/add url result at cadence))
         (impl/reindexed feed-guid)
         (cond-> fresh? (impl/kept feed-guid (impl/options library opts)))))))

(defn remove-episode
  "The `library` without the remote item `id`, with the :at of `opts`. The
  feed of the episode goes too, when no subscription or other remote item
  needs it, and the queue and the progress stay as they are."
  ([library id]
   (remove-episode library id {}))
  ([library [feed-guid :as id] opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/remove-remote-item id
               (impl/time-of library opts))
       (impl/tidied feed-guid))))

(defn remove-played-episodes
  "The `library` without the remote items that the listener played, with
  the :at of `opts`, e.g. to tidy up the episodes that the listener took
  from other shows.

  They leave the queue and the playlists too, and their feeds go when
  nothing else needs them. One that the listener starred, bookmarked,
  clipped or rated stays."
  ([library]
   (remove-played-episodes library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [profile (:profile library)
         marked  (-> (set (profile.marks/starred profile))
                     (into (map :id) (profile.marks/bookmarks profile))
                     (into (map :id) (profile.marks/clips profile))
                     (into (map :id) (profile.marks/rated profile)))
         ids     (filter #(and (profile/played? profile %) (not (marked %)))
                         (keys (profile/remote-items profile)))
         lists   (keys (profile.queue/playlists profile))
         at      (impl/time-of library opts)
         gone    (fn [p id]
                   (as-> p p
                     (profile/remove-remote-item p id at)
                     (profile.queue/dequeue p id at)
                     (reduce #(profile.queue/remove-from-playlist %1 %2 id at)
                             p
                             lists)))]
     (if (empty? ids)
       library
       (reduce impl/tidied
               (update library :profile crdt/at-once at
                       #(reduce gone % ids))
               (distinct (map first ids)))))))

;; Overcast's extended export, as of October 2026
(defn episodes-from-opml
  "The `library` with the progress, the played marks and the deleted
  episodes of the OPML subscription `list`, as Overcast's extended export
  writes them, at the :at of `opts`, as profile.opml/episodes-from-opml
  applies them.

  An episode is found by the URL of its media among the feeds that the
  library has, so subscribe to the list and refresh first. Its feed is
  found by the URL in the list."
  ([library list]
   (episodes-from-opml library list {}))
  ([library list opts]
   {:pre [(map? opts)]}
   (let [by-url (profile/by-url (:profile library))
         ids    (impl/media-ids library)
         bare   #(first (str/split % #"[?#]" 2))
         id-fn  (fn [xml-url media-url]
                  (when-let [feed-guid (by-url (guid/normal-url xml-url))]
                    (let [found (ids feed-guid)]
                      (or (found (guid/normal-url media-url))
                          (found (guid/normal-url (bare media-url)))))))]
     (update library :profile profile.opml/episodes-from-opml list id-fn
             (impl/time-of library opts)))))

(defn to-opml
  "The subscriptions of `library` as an OPML subscription list, as
  profile.opml/to-opml gives it, with the episodes that the listener
  played, started or hid, as Overcast's extended export writes them, by
  the media of the feeds that the library has."
  [library]
  (let [items (memoize
               (fn [feed-guid]
                 (into {}
                       (map (juxt guid/item-guid identity))
                       (get-in library [:feeds feed-guid :feed :rss/items]))))
        media (fn [[feed-guid item-guid]]
                (when-let [item (get (items feed-guid) item-guid)]
                  (or (:url (:rss/enclosure item))
                      (:url (resolve/enclosure item)))))]
    (profile.opml/to-opml (:profile library) {:media-url-fn media})))

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
   (let [at      (impl/time-of library opts)
         details (select-keys opts [:title :folder])
         seconds (:list-seconds (impl/options library opts))]
     (-> library
         (update :profile profile.opml/follow-list url details at)
         (impl/listed url list at seconds opts)))))

(defn unfollow-list
  "The `library` no longer following the list at `url`, with `opts`."
  ([library url]
   (unfollow-list library url {}))
  ([library url opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile.opml/unfollow-list url
               (impl/time-of library opts))
       (update :fetched-lists dissoc url))))

(defn refresh!
  "Fetch and apply whatever `library` is due to fetch, with the `opts`
  below.

  It fetches the followed lists, the feeds of new subscriptions and remote
  items, and the feeds of subscriptions whose next check has come. A feed
  held for remote items alone isn't fetched again, unless :feed-guids
  names it. It returns the new library and what the refresh found, in
  ClojureScript as a promise:

      {:library    {…}
       :at         #inst \"…\"
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
  - :max-feeds, :max-seconds, :on-feed-fn and :signal, to fetch fewer
    feeds or within a deadline, to hear of each fetch as it ends, and to
    stop, as app.feeds/refresh! takes them. The feeds left out stay due,
    and the :left of the :counts says how many there are.
  - :head-check?, true to read only the start of a feed whose server can't
    answer 304, and fetch it whole only when its newest episodes changed,
    as app.feeds/refresh! takes it
  - :store, the store that the library is saved to, from which it loads
    the feeds that it fetches and doesn't hold, as load! does. Without
    it, such a feed isn't fetched, and the report has :library/not-loaded.

  A refresh in which every fetch of a feed failed before a server
  answered, at more than one host, counts as offline too. Offline, no feed
  or list counts it as a failure, each stays due for the next refresh,
  and the report has :refresh/offline."
  ([library]
   (refresh! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at         (impl/time-of library opts)
         defaults   {:send client/send! :fetch podcast/fetch!}
         opts       (clojure.core/merge defaults (impl/options library opts))
         clock      (:now-fn opts time/now)
         started    (clock)
         feeds-opts (-> opts
                        (select-keys [:feed-guids :fetch :fetch-head :head-check?
                                      :credentials-of
                                      :concurrency :cadence :max-pages :send
                                      :timeout-seconds :max-bytes
                                      :user-agent :allow-pred :online?
                                      :max-feeds :max-seconds :on-feed-fn
                                      :now-fn :signal :lenient-parser])
                        (assoc :at at))]
     (async/then
      (async/then (impl/refresh-lists! library at opts)
                  #(impl/loaded-for-refresh % at opts))
      (fn [{listed :library report :report}]
        (let [unfetched  (impl/pending listed)
              ;; a feed held for remote items alone isn't checked again, and
              ;; one that isn't loaded can't be merged with what it held
              held       (remove (set (concat (:feed-guids opts)
                                              (keys unfetched)))
                                 (filter #(impl/alone? listed %)
                                         (keys (:feeds listed))))
              asleep     (set (unloaded listed))
              held       (into (vec held) asleep)
              feeds-opts (cond-> feeds-opts
                           (:feed-guids opts)
                           (update :feed-guids
                                   #(->> (concat (keys unfetched) %)
                                         (remove asleep)
                                         distinct)))]
          (async/then
           ;; a pending feed is fetched whole, without the validators of
           ;; the part of it that the library holds
           (feeds/refresh! (clojure.core/merge (apply dissoc (:feeds listed)
                                                      held)
                                               unfetched)
                           feeds-opts)
           (fn [step]
             (let [step            (update step :feeds clojure.core/merge
                                           (select-keys (:feeds listed) held))
                   offline?        (some (comp #{:refresh/offline} :id)
                                         (:report step))
                   [listed report] (if offline?
                                     (impl/offline-lists listed library report)
                                     [listed report])
                   {:keys [library] :as done} (impl/applied listed step at opts)
                   reports         (-> []
                                       (into report)
                                       (into (:report step))
                                       (into (:report done)))
                   took            (- (inst-ms (clock)) (inst-ms started))]
               (-> {:library library :at at}
                   (into (select-keys done [:added :backfilled :renamed :moved]))
                   (assoc :counts  (impl/counted step)
                          :seconds (/ took 1000.0)
                          :report  reports)))))))))))

(defn next-refresh
  "When `library` should refresh again, with `opts`: when the first of its
  feeds and followed lists is due, or nil when it has none to fetch. The
  result is never before the time of the call.

  The feed of a subscription that was never fetched is due at once, unless
  its fetch failed. Then it waits as its entry says, e.g. the first wait
  of the :after-failure of the cadence after one failure, as a feed that
  was fetched does. A feed that's gone, of local files or held for remote
  items alone is never due. After a refresh that found the device
  offline, the next waits the :offline-seconds of the config, when the
  :after of `opts` is what that refresh gave."
  ([library]
   (next-refresh library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at       (impl/time-of library opts)
         {:keys [offline-seconds]} (impl/options library opts)
         {:keys [report] last-at :at} (:after opts)
         check    #(or (:next-check %) at)
         checked? (fn [[feed-guid entry]]
                    (not (or (:gone? entry)
                             (:local? entry)
                             (impl/alone? library feed-guid))))
         checks   (concat (map (comp check val)
                               (filter checked? (:feeds library)))
                          (map check (vals (:fetched-lists library)))
                          (map check (vals (impl/pending library))))
         offline? (some (comp #{:refresh/offline} :id) report)]
     (when (seq checks)
       (time/latest at
                    (apply time/earliest checks)
                    (when offline?
                      (time/later last-at offline-seconds)))))))

(defn due
  "What a refresh of `library` with the `opts` of refresh! fetches, as data
  for fetch-due! in another context, e.g. a web worker.

  It's a map of the :at of the refresh, the :feed-guids to fetch, the
  earliest due first, the entries of their :feeds, and the entries of the
  followed :lists that are due, each by URL. The entries hold neither the
  feeds nor the lists, but name the document of each feed in the store.
  The lists of a list that the refresh follows are fetched by the next,
  and a feed that the library holds in its document alone isn't due until
  load! reads it."
  ([library]
   (due library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at        (impl/time-of library opts)
         opts      (impl/options library opts)
         naming    (impl/naming library)
         unfetched (impl/pending library)
         asked     (:feed-guids opts)
         held      (into (set (unloaded library))
                         (remove (set (concat asked (keys unfetched)))
                                 (filter #(impl/alone? library %)
                                         (keys (:feeds library)))))
         feeds     (clojure.core/merge (apply dissoc (:feeds library) held)
                                       unfetched)
         wanted    (into []
                         (filter #(and (contains? feeds %)
                                       (not (:local? (get feeds %)))))
                         (if asked
                           (distinct (concat (keys unfetched) asked))
                           (feeds/due feeds at)))
         outlined  (fn [feed-guid]
                     (let [entry (get feeds feed-guid)]
                       (cond-> entry
                         (:feed entry)
                         (-> (impl/without-feed-keys)
                             (assoc :document (impl/entry-document feed-guid entry naming))))))]
     {:at         at
      :feed-guids wanted
      :feeds      (into {} (map (juxt identity outlined)) wanted)
      :lists      (update-vals (impl/due-lists library at opts) #(dissoc % :list))})))

(defn fetch-due!
  "Fetch what a refresh is `due` to fetch, as due gives it, with the
  documents of its feeds in `store` and the `opts` of refresh! and the
  config, and put the documents of the feeds that changed into `store`.
  It gives what the refresh found, for refreshed, in ClojureScript as a
  promise:

      {:at        #inst \"…\"
       :from      {feed-guid {:document \"…\" :fetched-at #inst \"…\"}}
       :feeds     {feed-guid {…}}       ; the entries of the feeds fetched
       :documents {\"feed-….edn\" \"…\"}  ; of the feeds that changed
       :changed   [feed-guid …] :moved {feed-guid \"https://…\"}
       :lists     [[url fetched] …]
       :counts    {…} :seconds 4.2 :report [...]}

  The entries name their documents rather than hold their feeds, as those
  of due do, and a feed that failed keeps its failures. The documents hold
  each feed as it was fetched, before the cuts of the library's
  :keep-fn, :max-items and remote items, which refreshed makes."
  ([due store]
   (fetch-due! due store {}))
  ([{:keys [at feed-guids feeds lists] :as due} store opts]
   {:pre [(map? opts)]}
   (let [opts       (clojure.core/merge impl/default-config
                                        {:send client/send! :fetch podcast/fetch!}
                                        opts)
         codec      (impl/codec {} opts)
         clock      (:now-fn opts time/now)
         started    (clock)
         feeds-opts (-> opts
                        (select-keys [:fetch :fetch-head :head-check?
                                      :credentials-of
                                      :concurrency :cadence :max-pages :send
                                      :timeout-seconds :max-bytes
                                      :user-agent :allow-pred :online?
                                      :max-feeds :max-seconds :on-feed-fn
                                      :now-fn :signal :lenient-parser])
                        (assoc :at at :feed-guids feed-guids))
         from       (update-vals feeds #(select-keys % [:document :fetched-at]))
         outlined   (fn [feed-guid entry]
                      (cond-> entry
                        (:feed entry)
                        (-> (impl/without-feed-keys)
                            (assoc :document (impl/entry-document feed-guid entry codec)))))]
     (async/then
      (impl/with-feeds [{:feeds feeds} []] store (set feed-guids) codec)
      (fn [[{loaded :feeds} report]]
        (async/then
         (async/all [(impl/fetch-lists! lists opts)
                     (feeds/refresh! loaded feeds-opts)])
         (fn [[lists step]]
           (let [fetched   (into {}
                                 (for [feed-guid (:checked step)]
                                   [feed-guid (get-in step [:feeds feed-guid])]))
                 documents (into {}
                                 (for [[feed-guid entry] fetched
                                       :when (:feed entry)
                                       :let  [name (impl/entry-document feed-guid entry codec)]
                                       :when (not= name (get-in from [feed-guid :document]))]
                                   [name (impl/feed-body entry codec)]))]
             (async/then
              (store/put-all! store (for [[name body] documents]
                                      {:name name :body body}))
              (fn [_]
                {:at        at
                 :from      from
                 :feeds     (into {} (map (fn [[k e]] [k (outlined k e)])) fetched)
                 :documents documents
                 :changed   (:changed step)
                 :moved     (:moved step)
                 :lists     lists
                 :counts    (impl/counted step)
                 :seconds   (/ (- (inst-ms (clock)) (inst-ms started)) 1000.0)
                 :report    (into report (:report step))}))))))))))

(defn refreshed
  "The `library` with what a refresh `found`, as fetch-due! gives it,
  applied at its time with `opts`, and what the refresh found, as refresh!
  gives them.

  An entry that `found` holds replaces the library's own only while the
  library still has the entry that the refresh was due with, so that a
  feed that changed meanwhile, e.g. by a subscription in another tab,
  stays as it is, and so does a list that the listener no longer follows.
  The feeds that changed are read from the documents that `found` holds,
  with the :codec of `opts`."
  ([library found]
   (refreshed library found {}))
  ([library {:keys [at from feeds documents changed moved lists counts seconds report]
             :as   found}
    opts]
   {:pre [(map? opts)]}
   (let [opts     (clojure.core/merge {:send client/send! :fetch podcast/fetch!}
                                      (impl/options library opts))
         codec    (impl/codec library opts)
         now-from (fn [feed-guid]
                    (let [entry (get-in library [:feeds feed-guid])]
                      (cond-> (select-keys entry [:document :fetched-at])
                        (:feed entry)
                        (assoc :document (impl/entry-document feed-guid entry
                                                              (impl/naming library))))))
         read     (fn [feed-guid {:keys [document] :as entry}]
                    (let [own (get-in library [:feeds feed-guid])]
                      (cond
                        (contains? documents document)
                        (let [read (impl/read-feed codec document
                                                   {:value {:body (get documents document)}})]
                          (when (:feed read)
                            (-> (dissoc entry :document)
                                (clojure.core/merge read)
                                (update :feed guid/with-key feed-guid))))

                        (and document (:feed own))
                        (-> (dissoc entry :document)
                            (clojure.core/merge (select-keys own impl/feed-keys)))

                        :else
                        entry)))
         taken    (into {}
                        (keep (fn [[feed-guid entry]]
                                (when (= (get from feed-guid) (now-from feed-guid))
                                  (some->> (read feed-guid entry) (vector feed-guid)))))
                        feeds)
         followed (filter #(contains? (:fetched-lists library) (first %)) lists)
         listed   (impl/lists-applied library followed at opts)
         offline? (some (comp #{:refresh/offline} :id) report)
         [listed lists-report] (if offline?
                                 (impl/offline-lists (:library listed) library
                                                     (:report listed))
                                 [(:library listed) (:report listed)])
         {lib :library :as done}
         (impl/applied listed
                       {:feeds   (clojure.core/merge (:feeds listed) taken)
                        :changed (filterv taken changed)
                        :moved   (select-keys moved (keys taken))}
                       at
                       opts)]
     (-> {:library lib :at at}
         (into (select-keys done [:added :backfilled :renamed :moved]))
         (assoc :counts  counts
                :seconds seconds
                :report  (-> []
                             (into lists-report)
                             (into report)
                             (into (:report done))))))))

;; WebSub 5.1: a subscriber can subscribe at any of the hubs of a topic
(defn hub-requests
  "The requests to send the WebSub hubs of the feeds in `library`, by feed
  guid, given the `subscriptions` that websub-clj keeps by feed guid, with
  callback URLs from `callback-fn` and `opts`:

  - a subscription at the first hub of each show's feed, when the show has
    no subscription, or one that ended or is at another hub or topic
  - an unsubscription for each show that the listener left, or whose feed
    names no hub or is gone
  - the renewals and retries that websub/due returns

  The `callback-fn` takes a feed guid and its subscription, which is nil
  for a new one, and returns a new callback URL, as in websub/due. A new
  subscription gets a secret from websub/token. Give each request to
  websub/asked before websub/call! sends it. The `opts` are those of
  websub/due, :at, and :allow-pred, which url/public? is by default, since
  the feeds choose the hubs."
  ([library subscriptions callback-fn]
   (hub-requests library subscriptions callback-fn {}))
  ([library subscriptions callback-fn opts]
   {:pre [(map? opts)]}
   (let [at       (impl/time-of library opts)
         opts     (dissoc opts :at)
         wanted   (impl/pushable library)
         leaving? (fn [feed-guid]
                    (= :unsubscribe
                       (get-in subscriptions [feed-guid :pending :mode])))
         ask      (fn [[feed-guid {:keys [hubs topic]}]]
                    (let [{:keys [state hub pending] :as s}
                          (get subscriptions feed-guid)]
                      (when (and (not pending)
                                 (or (nil? s)
                                     (= :ended state)
                                     (not= topic (:topic s))
                                     (not (some #{hub} hubs))))
                        [feed-guid
                         (websub/request :subscribe (first hubs) topic
                                         (callback-fn feed-guid s)
                                         (assoc opts
                                                :secret (websub/token)))])))
         leave    (fn [[feed-guid {:keys [state hub topic callback]}]]
                    (when (and (not (contains? wanted feed-guid))
                               (#{:active :asked} state)
                               (not (leaving? feed-guid)))
                      [feed-guid
                       (websub/request :unsubscribe hub topic callback
                                       (dissoc opts :secret :lease-seconds))]))
         due      (filter (fn [[feed-guid]]
                            (or (contains? wanted feed-guid)
                                (leaving? feed-guid)))
                          (websub/due subscriptions at callback-fn opts))
         ;; the feeds choose the hubs, so no request goes to a private
         ;; network unless the call says so
         guarded  (fn [[feed-guid request]]
                    [feed-guid (assoc request :allow-pred
                                      (:allow-pred opts url/public?))])]
     (into {}
           (map guarded)
           (concat due (keep ask wanted) (keep leave subscriptions))))))

(defn pushed
  "The `library` after a WebSub hub pushed the feed `feed-guid` in
  `delivery`, the map that :deliver! of websub/handler receives, with
  `opts`, and what the push brought, as refresh! gives them.

  The body is parsed as fetch! parses a feed. The new episodes join the
  queue or the inbox as after a refresh. Since a hub can push only what
  changed, a push removes no episode (see app.feeds/pushed). When a push
  can't be applied, the feed is due at once, so the next refresh fetches
  it, and the report says why. A push of a feed that the library doesn't
  have changes nothing."
  ([library feed-guid delivery]
   (pushed library feed-guid delivery {}))
  ([library feed-guid {:keys [body content-type] :as delivery} opts]
   {:pre [(map? opts)]}
   (let [at        (impl/time-of library opts)
         opts      (impl/options library opts)
         entry     (get-in library [:feeds feed-guid])
         parsed    (when (:feed entry)
                     (rss/try-parse (encoding/decode body {"content-type"
                                                           content-type})
                                    (-> (select-keys opts [:lenient-parser])
                                        (assoc :url (:url entry)))))
         error     (:error parsed)
         reason    (cond
                     (impl/unloaded? entry)
                     "the library holds the feed only in its document"

                     (nil? (:feed entry))
                     "the feed hasn't been fetched yet"

                     error
                     (or (:reason error) (:message error)))
         refreshed (fn [done step]
                     (-> {:library (:library done) :at at}
                         (into (select-keys done [:added :backfilled :renamed
                                                  :moved]))
                         (assoc :counts (impl/counted step)
                                :report (into (vec (:report step))
                                              (:report done)))))]
     (cond
       (or (nil? entry) (:local? entry))
       {:library library :at at :report []}

       reason
       {:library (assoc-in library [:feeds feed-guid :next-check] at)
        :at      at
        :report  [(diagnostics/describe
                   {:id        :library/push-not-applied
                    :feed-guid feed-guid
                    :url       (:url entry)
                    :reason    reason})]}

       :else
       (let [step (-> (feeds/pushed (:feeds library) feed-guid (:feed parsed)
                                    at (:cadence opts {}))
                      (assoc :checked [feed-guid]))]
         (refreshed (impl/applied library step at opts) step))))))

(defn identify!
  "Ask the directories for the ids of the shows of `library` that have
  none yet, with `opts`, as directory/identify! does, and keep them in
  the entries of their feeds. The views of a show and its episodes then
  link to it in the apps that know it by those ids. It gives a map of the
  new :library, the :at of the call and the :report of the directories
  that failed, in ClojureScript as a promise.

  The Podcast Index is asked with the credentials of the :index of the
  config. The shows are asked about two at a time, since Apple limits how
  often it answers, and a show whose directory failed is asked again
  next time. A show of local files is never asked about, since no
  directory lists it. The `opts` are the config for this call, and
  :feed-guids, the shows to ask about, ids or not."
  ([library]
   (identify! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [{:keys [index feed-guids] :as opts} (impl/options library opts)
         asking (cond-> (select-keys opts [:send :timeout-seconds
                                           :user-agent :country])
                  index (assoc :credentials index))
         fed?   #(let [{:keys [feed local?]} (get-in library [:feeds %])]
                   (and feed (not local?)))
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
                   {:library (reduce known library answers)
                    :at      (impl/time-of library opts)
                    :report  (vec (mapcat (comp :report second) answers))})))))

(defn episode-id
  "The id of the episode `item` of `feed` in `library`, [feed-guid
  item-guid]."
  [library feed item]
  ;; the feeds of the library have their keys, and the URLs of the others
  ;; cost a pass over every feed, each hashed as a key
  (guid/episode-id feed
                   (when-not (guid/keyed? feed) (get (urls library) feed))
                   item))

;; The podcast namespace, podcast:liveItem: a status of pending, live or
;; ended, and a start and an end
(defn live-items
  "The live items of the subscriptions of `library`, as [feed item] pairs,
  at the :at of `opts` or the time of its clock: those on air first, then
  those to come, the soonest first, then those over, the latest first.

  A live item plays by its id, [feed-guid item-guid], as an episode does,
  e.g. with playback."
  ([library]
   (live-items library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [at        (impl/time-of library opts)
         ms        #(if (inst? %) (inst-ms %) 0)
         status    #(some-> (:status %) str/lower-case)
         upcoming? (fn [{:keys [start] :as item}]
                     (and (not= "ended" (status item))
                          (if (inst? start)
                            (> (ms start) (inst-ms at))
                            (= "pending" (status item)))))
         order     (fn [[_ {:keys [start] :as item}]]
                     (cond
                       (resolve/live? item at) [0 (ms start)]
                       (upcoming? item)        [1 (ms start)]
                       :else                   [2 (- (ms start))]))]
     (->> (for [[feed-guid {:keys [feed]}] (:feeds library)
                :when (profile/subscribed? (:profile library) feed-guid)
                item  (:podcast/live-items feed)]
            [feed item])
          (sort-by order)
          vec))))

(defn notifications
  "The new episodes that a refresh found in the shows that announce theirs,
  by show, the show with the newest episode first, of what the refresh
  gave, `refreshed`, a map of the :library and the :added, as refresh!
  gives it:

      [{:feed-guid \"…\" :title \"The Daily\" :artwork \"https://…\"
        :episodes  [{:id [feed-guid item-guid] :title \"…\"
                     :published #inst \"…\"}]}]

  A show announces its new episodes when its :notify? setting says so,
  or else the listener's preference, as profile/notifies? says. An
  episode that the listener played already is left out."
  [{:keys [library added] :as refreshed}]
  (let [profile (:profile library)
        wanted? (fn [[feed-guid :as id]]
                  (and (profile/subscribed? profile feed-guid)
                       (profile/notifies? profile feed-guid)
                       (not (profile/played? profile id))))
        small   (fn [id]
                  (when-let [[_ item] (impl/item-of library id)]
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
    (->> added
         (filter wanted?)
         (group-by first)
         (keep of-show)
         (sort-by (comp newest first :episodes))
         vec)))

(defn show
  "The view of the show `feed-guid` of `library`, with the `opts` of
  app.show/view, or nil when the library has no such feed. Its episodes
  have their :retained facts.

  The :title is the listener's :custom-title of the show, and the
  :episodes are in the :order that the listener chose for it, as
  profile/default-settings has them.

  A feed that the library doesn't hold in memory gives only its :url,
  its :title and its :artwork, as the profile knows them, and
  :unloaded? true. Read it back with load!."
  ([library feed-guid]
   (show library feed-guid {}))
  ([library feed-guid opts]
   (when-let [{:keys [url] :as entry} (get-in library [:feeds feed-guid])]
     (let [profile (:profile library)
           feed    (impl/marked entry)
           own     (profile/settings profile feed-guid)
           image   (get-in profile [:subscriptions feed-guid :image])]
       (if (impl/unloaded? entry)
         (cond-> {:url url :unloaded? true}
           (profile/title profile feed-guid)
           (assoc :title (profile/title profile feed-guid))

           image
           (assoc :artwork image))
         (let [view (show/view feed (assoc opts :url url))]
           (cond-> view
             (:custom-title own)
             (assoc :title (:custom-title own))

             (and (:order own) (contains? view :episodes))
             (assoc :episodes (criteria/episodes feed {:profile profile
                                                       :url     url})))))))))

(defn shows
  "The subscriptions of `library` in the listener's order, as profile/shows
  gives it, each as a map for a screen of shows:

      {:feed-guid \"…\"
       :title     \"…\"           ; the listener's own title, or the feed's
       :artwork   \"https://…\"
       :newest    #inst \"…\"     ; when the newest episode came out
       :counts    {:episodes 40 :unplayed 12 :in-progress 1 :played 27
                   :inbox 2 :queued 1}}

  The counts are of the episodes that the library keeps of each show, and
  of those of its episodes in the inbox and the queue. A show whose feed
  isn't fetched yet counts only the inbox and the queue, and so does one
  whose feed isn't loaded, which has :unloaded? true."
  [library]
  (let [profile (:profile library)
        inbox   (frequencies (map first (profile.queue/inbox profile)))
        queued  (frequencies (map first (profile.queue/queue profile)))
        summary
        (fn [feed-guid]
          (let [feed     (some-> (get-in library [:feeds feed-guid])
                                 impl/marked)
                items    (some-> feed :rss/items resolve/distinct-items)
                status   #(profile/status
                           (profile/episode-entry
                            profile [feed-guid (guid/item-guid %)]))
                statuses (frequencies (map status items))
                dates    (seq (keep resolve/published items))
                title    (profile/title profile feed-guid)
                artwork  (or (some-> feed resolve/artwork)
                             (get-in profile [:subscriptions feed-guid :image]))
                counts   (cond-> {:inbox  (get inbox feed-guid 0)
                                  :queued (get queued feed-guid 0)}
                           feed (assoc :episodes    (count items)
                                       :unplayed    (get statuses :unplayed 0)
                                       :in-progress (get statuses
                                                         :in-progress 0)
                                       :played      (get statuses :played 0)))]
            (cond-> {:feed-guid feed-guid :counts counts}
              title   (assoc :title title)
              artwork (assoc :artwork artwork)
              dates   (assoc :newest (apply max-key inst-ms dates))
              (impl/unloaded? (get-in library [:feeds feed-guid]))
              (assoc :unloaded? true))))]
    (mapv summary (profile/shows profile))))

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
       (show/fetch-linked-shows! view (impl/options library opts))))))

(defn episode
  "The view of the episode `id`, [feed-guid item-guid], of `library`, with
  the `opts` of app.episode/view over the config, e.g. its :languages, or
  nil when the library has no such episode, or doesn't hold its feed in
  memory, as loaded? tells. Unless the `opts` say, the
  :video? of the view is whether the listener watches the show. The view
  has the :retained fact of an episode that the feed no longer brings
  back."
  ([library id]
   (episode library id {}))
  ([library id opts]
   (when-let [[{:keys [url] :as entry} item] (impl/item-of library id)]
     (let [video? (profile/prefers-video? (:profile library) (first id))]
       (episode/view (impl/identified entry)
                     item
                     (clojure.core/merge {:video? video?}
                                         (impl/options library opts)
                                         {:url url}))))))

(defn playback
  "What a player needs for the episode `id` of `library`, with the `opts`
  of episode over the config, or nil when the library has no such
  episode:

      {:id         [feed-guid item-guid]
       :src        \"https://…/7.mp3\"   ; the file to play
       :local?     false                ; whether the file is on the device
       :enclosure  {:url \"…\" :type \"audio/mpeg\" …}
       :position   754.0                ; where to start, in seconds
       :duration   3612.0               ; when it's known
       :playback-rate 1.5               ; the rate of the show
       :played?    false
       :title      \"…\" :show \"…\" :artwork \"https://…\"
       :poster     \"https://…\"         ; of a video, before it plays
       :chapters   {:source :embedded :document {…}}  ; in the feed itself
       :chapters-url \"https://…\"                    ; a file of chapters
       :transcript {:url \"…\" :type \"text/vtt\" …}   ; e.g. for captions
       :metadata   {…}}                 ; for the Media Session

  The file to play is the downloaded one, which the :local-file-fn of the
  options gives for the id, e.g. an object URL, or else the record of the
  files in the library, and otherwise the enclosure, which is on the
  device for a show of local files. With :read, what read-files! gave for
  the episode, its chapters and measured duration go over those of the
  feed. The keys without a value are left out, and app.episode/metadata
  gives the metadata.

  The :src, :position and :playback-rate are the keys that media/source!
  of the play-nice library loads, so it takes the map as it is."
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
           local-file  (or (when-let [f (:local-file-fn
                                         (impl/options library opts))]
                             (f id))
                           (downloads/local-file (:files library) id))
           src         (or local-file (:url (:enclosure view)))
           duration    (or (:duration (profile/episode-entry profile id))
                           (:duration read)
                           (:duration view))]
       (into {:id            id
              :local?        (or (some? local-file)
                                 (true? (get-in library [:feeds feed-guid :local?])))
              :position      (profile/resume-position profile feed-guid id
                                                      (impl/time-of library opts))
              :playback-rate (profile/playback-rate profile feed-guid)
              :played?       (profile/played? profile id)
              :metadata      (episode/metadata feed view)}
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

(defn listed
  "The row of the episode `id` of `library` in a list of episodes, e.g. of
  the results of recommend, as queue describes it, with the `opts` of
  episode, or nil when the library doesn't hold its feed in memory."
  ([library id]
   (listed library id {}))
  ([library [feed-guid :as id] opts]
   (when-let [view (episode library id (assoc opts :except #{:notes :text :links :chapters}))]
     (let [entry    (profile/episode-entry (:profile library) id)
           item     (second (impl/item-of library id))
           position (or (:position entry) 0)
           duration (or (:duration entry) (:duration view))]
       (into {:id id :position position}
             (remove (comp nil? val))
             {:title      (:title view)
              :show       (resolve/title (get-in library [:feeds feed-guid :feed]))
              :artwork    (:artwork view)
              :published  (resolve/published item)
              :duration   duration
              :remaining  (when (number? duration)
                            (max 0 (- duration position)))
              :supplement (feed/supplement item)})))))

(defn queue
  "The queue of `library`, the current episode first, for a page of it, with
  the `opts` of episode: a map of its :episodes and the seconds :remaining
  of all of them that it knows the duration of, e.g.

      {:episodes  [{:id        [feed-guid item-guid]
                    :title     \"…\" :show \"…\" :artwork \"https://…\"
                    :published #inst \"…\"  ; when it gave a date
                    :duration  3600       ; when it's known
                    :position  754        ; where the listener left it
                    :remaining 2846       ; when the duration is known
                    :supplement \"https://…\"} ; when feed/supplemented took it
                   …]
       :remaining 10800}

  An episode whose feed the library doesn't hold in memory is left out,
  as loaded? tells."
  ([library]
   (queue library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [episodes (into [] (keep #(listed library % opts))
                        (profile.queue/queue (:profile library)))]
     {:episodes  episodes
      :remaining (reduce + 0 (keep :remaining episodes))})))

(defn inbox
  "The inbox of `library`, the newest episode first, for a page of it, with
  the `opts` of episode: a map of its :episodes, each as queue gives them.

  They're in the order of their dates, rather than of their arrival, so
  that an episode put back with undismiss takes its place again. An
  episode whose feed the library doesn't hold in memory is left out, as
  loaded? tells."
  ([library]
   (inbox library {}))
  ([library opts]
   {:pre [(map? opts)]}
   {:episodes (->> (profile.queue/inbox (:profile library))
                   (keep #(listed library % opts))
                   ;; stable, so the episodes of a date keep their arrival
                   (sort-by :published #(compare %2 %1))
                   vec)}))

(defn read-files!
  "Read the files of the episode `id` of `library`, with the `opts` of
  app.episode/read-files! over the config. It gives a map of the
  :chapters, the :transcript and the :media, in ClojureScript as a
  promise, or nil when the library has no such episode. The media file is
  the one that the episode's view chooses."
  ([library id]
   (read-files! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (impl/item-of library id)]
     (let [video? (profile/prefers-video? (:profile library) (first id))
           opts   (clojure.core/merge {:video? video?}
                                      (impl/options library opts))
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
   (when-let [[{:keys [feed url]} item] (impl/item-of library id)]
     (let [unused #{:notes :text :chapters :links}
           view   (episode/view feed item {:url url :except unused})]
       (episode/fetch-remote-values! view (impl/options library opts))))))

(defn episodes
  "The episodes of the shows of `library` that the criteria `opts` select,
  as [feed item] pairs, e.g.

      (episodes library {:where {:status :unplayed} :order :listening})

  The criteria are those of criteria/episodes. They read the profile and
  the downloaded files of the library, and the time of its clock unless
  the `opts` give :at. The hidden episodes are left out unless the
  criteria name :hidden?. With :feed-guids, only the episodes of those
  shows are read, e.g. for the page of a show."
  ([library]
   (episodes library {}))
  ([library {:keys [feed-guids] :as opts}]
   {:pre [(map? opts)]}
   (criteria/episodes (if feed-guids
                        (keep #(impl/shown library %) feed-guids)
                        (impl/shown-feeds library))
                      (cond-> (assoc (dissoc opts :feed-guids)
                                     :where   (criteria/visible (:where opts))
                                     :profile (:profile library)
                                     :files   (:files library {})
                                     :url     (urls library)
                                     :at      (impl/time-of library opts))
                        (:index library) (assoc :index (:index library))))))

(defn any-meets?
  "Whether any of the episodes `ids` of `library` meets the `criteria` of
  criteria/where, with the `opts` below, e.g. whether one is explicit or
  of an explicit show:

      (any-meets? library ids {:explicit? true})
      (any-meets? library ids {:show-explicit? true})

  The criteria read the profile of the library, and the time of its clock
  unless the `opts` give :at, with their :scale. With :feeds, as recommend
  takes them, `ids` can name the episodes of shows that the library
  doesn't hold whole, e.g. a show found in a search."
  ([library ids criteria]
   (any-meets? library ids criteria {}))
  ([library ids criteria {:keys [feeds] :as opts}]
   {:pre [(map? opts)]}
   (let [holding (cond-> library
                   feeds (update :feeds clojure.core/merge feeds))
         pairs   (keep #(impl/item-of holding %) ids)
         urls    (into {} (map (fn [[{:keys [feed url]}]] [feed url])) pairs)
         meets?  (impl/where-pred holding urls criteria opts)]
     (boolean (some (fn [[{:keys [feed]} item]] (meets? feed item)) pairs)))))

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
           found  (->> (impl/index-opts library (urls library) locate opts)
                       (search/query index q))]
       (with-meta (mapv locate found) (meta found))))))

(defn recommend
  "The episodes of `library` most like the episodes `ids`, or like those
  that the listener heard last, best first, with the `opts` below, or nil
  when the library keeps no index.

  Each result has its :feed and :item, as in search, and the :terms of the
  query that it holds, the best first, e.g. to tell the listener why it
  came up. The metadata of the results has the :terms of the query, as
  more-like-this of dk.simongray.drop-in-search gives them, pairs of a
  term and its weight. The `opts` are those of more-like-this, and these:

  - :limit, the most episodes, 10 by default
  - :where, the criteria of criteria/where that an episode meets,
    {:status :unplayed} by default, and the hidden ones are left out
    unless the criteria name :hidden?
  - :same-show?, true to take episodes of the shows of `ids` too
  - :same-language?, false to take the episodes of shows in any language,
    rather than of the shows whose feeds declare a language of the
    episodes of `ids`, when they declare one
  - :max-per-show, the most episodes of one show, 2 by default, or nil
  - :recent, the number of the episodes that the listener heard last to
    start from without `ids`, 10 by default, where the last weighs the
    most
  - :from, the criteria of criteria/where that the episodes to start
    from meet, whether `ids` names them or the listener heard them last,
    e.g. {:not {:explicit? true}}
  - :feeds, the shows that the library doesn't hold whole, whose episodes
    `ids` and :unlike can name, e.g. a show found in a search, as a map of
    the feed guid of each to a map of its parsed :feed and its :url
  - :unlike, ids of episodes whose words count against those of `ids`,
    e.g. the ones that the listener rated low
  - :weight-fn, a function of an id of `ids` or :unlike that gives how
    much it weighs, 1 by default

  The words are those of the titles, notes, keywords and chapters, but
  not the shows' names and people, which every episode of a show shares,
  and they have two letters or more. By default:

  - a word is one that the episodes of `ids` use twice, or once in a
    title, which counts three times, or in keywords or chapters, which
    count twice, as :min-term-freq has it
  - a result holds two of the words at least, as :min-match has it
  - a word in more than 8% of the episodes in the languages of the
    episodes of `ids`, or of all episodes when they name none, is left
    out, e.g. ad text and function words, as :max-doc-freq has it, and so
    is one that more than 40% of the shows use, as the :group-fn of
    more-like-this is the show

  Left out too are the words that link the episodes of `ids` to others by
  chance: their numbers, e.g. the 561 of \"#561: TonIO\" or a date, and the
  names of their shows' hosts and authors, e.g. the \"Adam & Dave\" of
  every episode's notes. So is the text that a show repeats in its notes,
  e.g. its sponsor reads and credits, as feed.search/repeated-runs finds
  it. The criteria read the profile of the library, and the time of its
  clock unless the `opts` give :at.

      (recommend library [id] {:limit 5})
      (recommend library nil {:where {:status   :unplayed
                                      :duration [nil 1800]}})"
  ([library]
   (recommend library nil {}))
  ([library ids]
   (recommend library ids {}))
  ([library ids {:keys [same-show? max-per-show recent from feeds unlike
                        weight-fn]
                 :or   {max-per-show 2 recent 10}
                 :as   opts}]
   {:pre [(map? opts)]}
   (when-let [index (:index library)]
     (let [feed-urls (urls library)
           from?     (if from
                       (impl/where-pred library feed-urls from opts)
                       (constantly true))
           recent?   (nil? ids)
           ids       (if recent? (impl/heard-last library recent from?) ids)
           ;; in place of the library's own entry of a show, which may hold
           ;; only the episode that the listener played of it
           holding   (cond-> library
                       feeds (update :feeds clojure.core/merge
                                     (update-vals feeds #(assoc % :repeated
                                                                (feed.search/repeated-runs
                                                                 (:feed %))))))
           seeds     (->> ids
                          (keep #(impl/seed holding %))
                          (filter #(from? (:feed %) (:item %))))
           languages (impl/languages-of seeds)
           shows     (when (seq languages) (impl/shows-in library languages))
           by-id     (impl/id-pred seeds shows opts)
           ;; the last heard weighs as many as there are, and the first 1
           ranks     (zipmap ids (range (count ids) 0 -1))
           weight-of (cond
                       weight-fn weight-fn
                       recent?   #(get ranks % 1)
                       :else     (constantly 1))
           locate    (feed.search/located (update-vals (:feeds library)
                                                       :feed))
           opts      (-> opts
                         (dissoc :same-show? :same-language? :max-per-show
                                 :recent :from :feeds :unlike :weight-fn)
                         (assoc :where       (criteria/visible
                                              (:where opts {:status :unplayed}))
                                :unlike      (keep #(:document
                                                     (impl/seed holding %))
                                                   unlike)
                                :weight-fn   (comp weight-of :id)
                                :same-group? (boolean same-show?))
                         (update :stop-words concat (impl/chance-words seeds))
                         (update :fields #(or % impl/topic-fields))
                         (update :min-word-length #(or % 2))
                         (update :min-term-freq #(or % 2))
                         (update :min-match #(or % 2))
                         (update :max-doc-freq #(or % (impl/common-cap library shows)))
                         (update :limit #(or % 10))
                         (update :group-fn #(or % feed.search/feed-guid-of))
                         (update :max-per-group #(or % max-per-show))
                         (->> (impl/index-opts library feed-urls locate))
                         (update :filter-pred #(if % (every-pred by-id %) by-id)))
           found     (search/more-like-this index (map :document seeds) opts)
           terms     (:terms (meta found))
           held      (fn [{:keys [id]}]
                       (impl/held-terms terms (:document (impl/seed library id))
                                        (:fields opts)))]
       (with-meta (mapv #(assoc (locate %) :terms (held %)) found)
         (meta found))))))

(defn snippet
  "A window of the text of the `result` that search gave for the query `q`
  in `library`, around the words that match, with the `opts` of
  feed.search/snippet, or nil when no field of the result matches or the
  library keeps no index.

      (snippet library result \"value for va\")
      ;; => {:field :description
      ;;     :parts [{:text \"…on \"} {:text \"value\" :match? true} …]}"
  ([library result q]
   (snippet library result q {}))
  ([library result q opts]
   {:pre [(map? opts)]}
   (when-let [index (:index library)]
     (let [feed-guid (feed.search/feed-guid-of (:id result))]
       (feed.search/snippet index result q
                            (assoc opts :repeated (get-in library [:feeds feed-guid
                                                                   :repeated])))))))

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
  profile.player/step takes them, and the effects to apply, as a map of
  the :library and the :effects.

  The library has the new profile, and what's left of the :sleep timer of
  this device. The `opts` go over the :sleep of the library. With the
  listener's :continuous?, an episode that ends with no other queued is
  followed by the first unplayed episode of its show in the listening
  order: the next of a serial show, and the newest of an episodic one.
  When that gives none, the effects have [:nothing-next id], e.g. for an
  app to play the first of recommend's episodes like it, with the event
  [:play id]."
  ([library event]
   (step library event {}))
  ([library event opts]
   {:pre [(map? opts)]}
   (let [at   (impl/time-of library opts)
         opts (clojure.core/merge {:continue-fn #(next-of-show library %)}
                                  (select-keys library [:sleep])
                                  opts)
         {:keys [profile effects sleep]}
         (profile.player/step (:profile library) event at opts)]
     {:library (cond-> (assoc (dissoc library :sleep) :profile profile)
                 sleep (assoc :sleep sleep))
      :effects effects})))

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
             (impl/time-of library opts)))))

(defn update-profile
  "The `library` with its profile replaced by what `f` gives for it, the
  `args` and the time of the clock, in that order. For a series of changes
  at one time, give a function of the profile and the time."
  [library f & args]
  (update library :profile
          #(apply f % (concat args [(impl/time-of library {})]))))

(def opml-name
  "The name of the subscription list that a backend with :opml? writes
  beside the documents, for any podcast app to import."
  "subscriptions.opml")

(defn- with-subscription-list
  "The options `opts` of a backend, with the subscription list of the
  profile among the documents that their :extra-documents-fn gives. The
  list has the shows in their folders and with their tags, and the lists
  that the listener follows."
  [{:keys [extra-documents-fn] :as opts}]
  (assoc opts :extra-documents-fn
         (fn [state]
           (clojure.core/merge
            {opml-name (opml/emit (profile.opml/to-opml state))}
            (when extra-documents-fn
              (extra-documents-fn state))))))

(defn backend
  "A backend that syncs the profiles of libraries through `store`, a
  document store of dk.simongray.like-minded, with `opts`.

  It's like-minded/backend with profile/sync-options under the `opts`,
  which can also have :opml?, true to write subscriptions.opml beside
  the documents."
  ([store]
   (backend store {}))
  ([store {:keys [opml?] :as opts}]
   {:pre [(map? opts)]}
   (like-minded/backend store
                        (cond-> (clojure.core/merge profile/sync-options
                                                    (dissoc opts :opml?))
                          opml? with-subscription-list))))

(defn sync!
  "Sync `library` with the listener's other devices through `backends`,
  with the `opts` of like-minded/sync! except :cursors and :failures.

  It compacts the profile as profile/compact does, with the :archive-after
  of the config, and keeps what the next sync starts from under the :sync
  of the library:

      {:cursors  {…}         ; those of the stores, for the next sync
       :at       #inst \"…\"
       :clock    #inst \"…\"   ; the clock of the profile after the sync
       :failures 0}          ; the syncs in a row in which a backend failed

  It returns a map of the new :library and the :report, with
  :sync/backend-failed for each backend that failed, in ClojureScript as a
  promise."
  ([library backends]
   (sync! library backends {}))
  ([library backends opts]
   {:pre [(map? opts)]}
   (let [before    (:profile library)
         opts      (assoc opts :compact (impl/compacting library opts))
         sync-opts (clojure.core/merge
                    profile/sync-options
                    opts
                    {:at (impl/time-of library opts)}
                    (select-keys (:sync library) [:cursors :failures]))]
     (async/then (like-minded/sync! before backends sync-opts)
                 (fn [step]
                   (let [profile (like-minded/finish
                                  before step
                                  (clojure.core/merge profile/sync-options
                                                      opts))]
                     {:library (assoc library
                                      :profile profile
                                      :sync    (-> (dissoc step :state :report)
                                                   (assoc :clock (:clock profile))))
                      :report  (:report step)}))))))

(defn leave!
  "Retire this device of `library` from the listener's sync through
  `backends`, with the `opts` of like-minded/leave! except :cursors and
  :failures, e.g. when the listener turns sync off.

  It writes the retirement through each backend once more, so that the
  other devices stop waiting for this one before they compact. It returns
  the library with the retired profile and the :report, as sync! does, in
  ClojureScript as a promise. A retired device can't sync again, so to
  sync later, give the library a new device with with-device."
  ([library backends]
   (leave! library backends {}))
  ([library backends opts]
   {:pre [(map? opts)]}
   (let [sync-opts (clojure.core/merge
                    profile/sync-options
                    opts
                    {:at (impl/time-of library opts)}
                    (select-keys (:sync library) [:cursors :failures]))]
     (async/then (like-minded/leave! (:profile library) backends sync-opts)
                 (fn [{:keys [state] :as step}]
                   {:library (assoc library
                                    :profile state
                                    :sync    (-> (dissoc step :state :report)
                                                 (assoc :clock (:clock state))))
                    :report  (:report step)})))))

(defn with-device
  "The `library` as that of the new `device`, described for the other
  devices by the `opts` that make takes, e.g. to sync again after leave!.

  The profile keeps what it holds, and the next sync starts afresh, as the
  first sync of a new device does."
  ([library device]
   (with-device library device {}))
  ([library device opts]
   {:pre [(map? opts)]}
   (let [description (dissoc opts :config :at)
         at          (impl/time-of library opts)]
     (cond-> (-> library
                 (assoc :sync {})
                 (assoc-in [:profile :device] device))
       (seq description) (update :profile crdt/identify description at)))))

(defn merge
  "The `library` with `result` merged in, a library that came from an
  earlier value of `library`, e.g. the :library of what a refresh or a
  sync gave.

  The profiles merge, so nothing that the listener did meanwhile is lost.
  A feed or a list stays only while the merged profile subscribes to it,
  has a remote item of it, or follows it, so one that the listener left
  on another device goes. Of two entries of one feed or list, the later
  fetch wins. The :sync of the later sync is kept too, and the rest is
  that of `library`."
  [library result]
  (let [profile (profile/merge (:profile library) (:profile result))
        synced  (impl/newer :at (:sync library) (:sync result))
        needed  (into (set (keys (profile/subscriptions profile)))
                      (map ffirst)
                      (profile/remote-items profile))
        feeds   (impl/merged-entries (:feeds library) (:feeds result) needed)
        lists   (impl/merged-entries (:fetched-lists library)
                                     (:fetched-lists result)
                                     (profile.opml/lists profile))
        same?   #(and (identical? (get-in library [:feeds % :feed])
                                  (get-in feeds [% :feed]))
                      (= (listing (:profile library) %) (listing profile %)))
        guids   (into (set (keys feeds)) (keys (:feeds library)))
        changed (remove same? guids)
        compact (impl/compacting library {})]
    (reduce impl/reindexed
            (cond-> (assoc library
                           :profile       (cond-> profile
                                            (and compact
                                                 (:at synced)
                                                 (not= synced (:sync library)))
                                            (profile/compact (:at synced)
                                                             compact))
                           :feeds         feeds
                           :fetched-lists lists)
              synced (assoc :sync synced))
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
     (like-minded/next-sync last-step (impl/time-of library opts)))))

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
   (like-minded.document/for-device (:profile library)
                                    (impl/time-of library opts)
                                    (clojure.core/merge profile/sync-options
                                                        opts))))

;; the config belongs to the app that makes the library, so it isn't
;; saved, and the search index has documents of its own
(def saved-keys
  "The parts of a library that are saved by default: the profile, the
  feeds, the followed lists, what the next sync starts from, whose cursors
  fit the profile, and the downloaded files."
  [:profile :feeds :fetched-lists :sync :files])

(defn- saved-attempt
  "The attempt to fetch the document of the library saved in `store` with
  `codec`, or in EDN by an earlier version, as a map of its :name, and its
  :value or :error, in ClojureScript as a promise."
  [store codec]
  (let [own   (impl/library-name codec)
        tried (fn [name]
                (async/then (async/attempt #((:fetch store) name {}))
                            #(assoc % :name name)))]
    (async/then (tried own)
                (fn [{:keys [value error] :as attempt}]
                  (if (or value error (= own impl/saved-name))
                    attempt
                    (tried impl/saved-name))))))

(defn- written!
  "Write `library` to `store` as save! does, with the parts that `keys`
  names, and give the map that save! gives, but for the :error."
  [library store keys opts]
  (let [codec   (impl/codec library opts)
        name    (impl/library-name codec)
        body    ((:encode codec) (impl/saved-map library keys codec))
        none    {:put [] :delete []}
        ;; only the documents that changed, so that a save after each
        ;; refresh takes little, and the library of another codec goes
        planned (fn [listed]
                  (let [names (map :name listed)
                        index (if-let [index (:index library)]
                                (ciff/plan index names)
                                none)
                        feeds (if (some #{:feeds} keys)
                                (impl/feeds-plan (:feeds library) names codec)
                                none)
                        older (filter #(and (impl/library-document? %) (not= name %))
                                      names)]
                    {:put    (concat (:put feeds) (:put index))
                     :delete (-> (:delete index) (into (:delete feeds)) (into older))}))
        delete! (fn [name]
                  (async/attempt #((:delete store) name)))
        deleted (fn [names attempts]
                  (vec (for [[name {:keys [error]}] (map vector names attempts)
                             :when (nil? error)]
                         name)))
        ;; the library last, so that a save that stops half way leaves the
        ;; old one naming documents that are still there
        saved   (fn [{:keys [put delete]}]
                  (async/then
                   (store/put-all! store put)
                   (fn [names]
                     (async/then
                      ((:put store) name body {})
                      (fn [_]
                        (let [delete (if (:delete store) delete [])]
                          (async/then (async/all (map delete! delete))
                                      (fn [attempts]
                                        {:put    (conj names name)
                                         :delete (deleted delete attempts)}))))))))]
    (if (:put store)
      (async/then (if-let [list (:list store)] (list) [])
                  (comp saved planned))
      (throw (ex-info "The store is read only" {})))))

(defn save!
  "Write `library` to `store` with the `opts` below, and give the names of
  the documents it put and deleted, as a map of :put and :delete, or a
  map of :error, in ClojureScript as a promise.

  It writes the library's own document, library.edn in the :codec of
  `opts`, EDN by default, with the parts of `library` that :keys in `opts`
  names, saved-keys by default, and the version of their shape. Each feed
  goes to a document of its own, which is put when the feed changed since
  the last save, and the documents of the feeds that changed or went are
  deleted, and the library of another codec too. When the library keeps a
  search index, it writes the documents of the index that changed since
  the last save, and deletes those that the index no longer needs. One
  that it couldn't delete is left out of :delete for the next save to
  delete, and a store without :delete keeps them all. Give it a store of
  its own, e.g. over a folder, IndexedDB or the origin private file
  system, since it deletes the documents of any other library there.

  It writes over what the store holds, so where several tabs or workers
  share the store, write the library with write! instead.

  The :error is :library/save-failed when the store failed, and
  :library/newer-format when `library` was restored from a library of a
  newer version, which it doesn't write over."
  ([library store]
   (save! library store {}))
  ([library store {:keys [keys] :or {keys saved-keys} :as opts}]
   {:pre [(map? opts)]}
   (let [refused (:newer-format library)]
     (async/then
      (async/attempt #(when-not refused (written! library store keys opts)))
      (fn [{:keys [value error]}]
        (cond
          refused {:error refused}
          error   (diagnostics/failed
                   {:id     :library/save-failed
                    :reason (async/explain error)})
          :else   value))))))

(defn restore!
  "The `library` with what was saved to `store`, read with the `opts`
  below, and the report of what didn't read, as a map of the :library and
  the :report, in ClojureScript as a promise.

  The parts of the library's document that :keys in `opts` names,
  saved-keys by default, replace those of `library`, so give it a new
  library, and each feed is read from its document. The documents are read
  with the :codec of `opts`, or in EDN, by their extensions, so that a
  library saved in EDN reads, and the next save writes it anew. A library
  saved by an older version is brought up to this one. When the library
  keeps a search index, it's read back, or built again from the feeds
  when its documents are missing or don't read. The report can have
  :library/unreadable-document, :library/index-rebuilt,
  :library/malformed-profile, :library/newer-format and
  :library/slow-format.

  A store without the documents gives the library as it is, and so does a
  library saved by a newer version, which then has the diagnostic under
  :newer-format, so that save! and write! won't write over it. A library
  read in ClojureScript with the codec of EDN has :library/slow-format,
  since that reads about 30 times slower than the codec of
  dk.simongray.like-minded.transit.

  The :load of `opts` says which feeds to read: :all by default, :none, or
  the guids of some. The others stay in their documents until load!
  reads them, e.g. the feeds of the shows on the screen, and the views
  mark them as not loaded."
  ([library store]
   (restore! library store {}))
  ([library store {:keys [keys load] :or {keys saved-keys load :all}
                   :as   opts}]
   {:pre [(map? opts)]}
   (let [wanted?    (case load
                      :all  (constantly true)
                      :none (constantly false)
                      (set load))
         not-index? #(or (impl/library-document? %) (impl/feed-document? %))
         fetched    #(store/fetch-all! store {:as :bytes :except not-index?})
         indexed    (fn [[library :as step]]
                      (if (:index library)
                        (async/then (async/attempt fetched)
                                    (partial impl/with-index step))
                        step))
         codec      (impl/codec library opts)
         slow       (impl/slow-format library opts)
         done       (fn [{:keys [value]} [library report]]
                      {:library library
                       :report  (cond-> report
                                  (and value slow) (conj slow))})]
     (async/then (saved-attempt store codec)
                 (fn [{:keys [name] :as attempt}]
                   (let [step (impl/with-saved library keys name attempt codec)]
                     (if (:newer-format (first step))
                       (done attempt step)
                       (-> (impl/with-feeds step store wanted? codec)
                           (async/then indexed)
                           (async/then (partial done attempt))))))))))

(defn load!
  "The `library` with the feeds `feed-guids` read back from their
  documents in `store` with the :codec of `opts`, those that it doesn't
  hold in memory, and the report of what didn't read, as a map of the
  :library and the :report, in ClojureScript as a promise. With :all for
  `feed-guids`, it's every feed.

  A feed whose document is missing or doesn't read keeps only its URL, so
  that the next refresh fetches it again, with
  :library/unreadable-document in the report."
  ([library store feed-guids]
   (load! library store feed-guids {}))
  ([library store feed-guids opts]
   {:pre [(map? opts)]}
   (let [wanted? (if (= :all feed-guids) (constantly true) (set feed-guids))]
     (async/then (impl/with-feeds [library []] store wanted? (impl/codec library opts))
                 (fn [[library report]]
                   {:library library :report report})))))

(defn unload!
  "The `library` without the feeds `feed-guids` in memory, each written to
  its document in `store` first, in ClojureScript as a promise. With :all
  for `feed-guids`, it's every feed.

  The entry of each feed keeps the name of its document, and load! or a
  refresh with the store reads it back. The views show the feed as not
  loaded meanwhile, e.g. on a server, which needs no feed between its
  refreshes, or on a phone, which needs only those on the screen.

  It gives a map of the new :library and the :report, in ClojureScript as
  a promise. The documents are written with the :codec of `opts`. When
  the store fails, the feeds stay in memory, and the report has
  :library/save-failed."
  ([library store feed-guids]
   (unload! library store feed-guids {}))
  ([library store feed-guids opts]
   {:pre [(map? opts)]}
   (let [wanted?  (if (= :all feed-guids) (constantly true) (set feed-guids))
         codec    (impl/codec library opts)
         chosen   (for [[feed-guid entry] (:feeds library)
                        :when (and (:feed entry) (wanted? feed-guid))]
                    [feed-guid entry (impl/entry-document feed-guid entry codec)])
         outlined (fn [library [feed-guid _ name]]
                    (update-in library [:feeds feed-guid]
                               #(-> (impl/without-feed-keys %)
                                    (assoc :document name))))]
     (async/then
      (async/attempt
       #(store/put-all! store (for [[_ entry name] chosen]
                                {:name name :body (impl/feed-body entry codec)})))
      (fn [{:keys [error]}]
        (if error
          {:library library
           :report  [(diagnostics/describe
                      {:id     :library/save-failed
                       :reason (async/explain error)})]}
          {:library (reduce outlined library chosen) :report []}))))))

(defn documents
  "The documents of `library` as a map of their names to their bodies, in
  the :codec of `opts`, but for those that the :except of `opts` names,
  e.g. those that a store holds already.

  They're the library's own document, which holds the parts that
  saved-keys names and the manifest of the search index, the document of
  each feed that it holds in memory, and the segments of the index. The
  library's own document is always among them, since every change of the
  library changes it. A feed's document and a segment are named by what
  they hold, so a store that holds one never needs it again."
  ([library]
   (documents library {}))
  ([library {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [codec  (impl/codec library opts)
         except (set except)
         files  (some-> (:index library) (impl/index-files except))
         saved  (cond-> (impl/saved-map library saved-keys codec)
                  files (assoc :index (:body (peek files))))]
     (-> (into {}
               (for [[feed-guid entry] (:feeds library)
                     :when (:feed entry)
                     :let  [name (impl/entry-document feed-guid entry codec)]
                     :when (not (contains? except name))]
                 [name (impl/feed-body entry codec)]))
         (into (map (juxt :name :body)) (some-> files pop))
         (assoc (impl/library-name codec) ((:encode codec) saved))))))

(defn document-names
  "The names of the documents of `library`, as documents gives them, and of
  those of the feeds that it holds in their documents alone."
  [library]
  (let [naming (impl/naming library)]
    (-> #{(impl/library-name naming)}
        (into (keep (fn [[feed-guid entry]]
                      (if (:feed entry)
                        (impl/entry-document feed-guid entry naming)
                        (:document entry))))
              (:feeds library))
        (into (some-> (:index library) impl/segment-names)))))

(defn- needs
  "The names of the documents that from-saved reads for `library` from the
  `saved` map of its own document, and that `docs`, a map of names to
  bodies, lack: those of the feeds that `library` doesn't hold in memory as
  they are, and those of the search index, unless its manifest is the one
  of the index of `library`."
  [library saved docs]
  (let [naming (impl/naming library)
        same?  (fn [feed-guid document]
                 (let [own (get-in library [:feeds feed-guid])]
                   (and (:feed own)
                        (= document (impl/entry-document feed-guid own naming)))))
        feeds  (for [[feed-guid {:keys [document]}] (:feeds saved)
                     :when (and document (not (same? feed-guid document)))]
                 document)
        index  (when-let [index (:index library)]
                 (let [manifest (or (:index saved) (get docs ciff/manifest-name))]
                   (cond
                     (nil? manifest)
                     [ciff/manifest-name]

                     (not= manifest (impl/manifest index))
                     (impl/manifest-segments manifest))))]
    (vec (remove #(contains? docs %) (concat feeds index)))))

(defn- from-saved
  "The `library` with what the documents `docs` hold, read with `codec`,
  and the report of what didn't read, as from-documents gives them, where
  `saved` is the map that the library's own document `name` among them
  holds, or nil when it doesn't read."
  [library docs name saved codec]
  (let [kept  (fn [[feed-guid {:keys [document] :as entry}]]
                (let [own (get-in library [:feeds feed-guid])]
                  [feed-guid
                   (if (and document
                            (:feed own)
                            (= document (impl/entry-document feed-guid own codec)))
                     (-> (dissoc entry :document)
                         (clojure.core/merge (select-keys own impl/feed-keys)))
                     entry)]))
        read  (fn [[feed-guid {:keys [document] :as entry}]]
                (when (impl/unloaded? entry)
                  [feed-guid document
                   (impl/read-feed codec document
                                   {:value (when-let [body (get docs document)]
                                             {:body body})})]))
        index (fn [[lib _ :as step]]
                (let [manifest (:index saved)]
                  (cond
                    (nil? (:index lib))
                    step

                    (and manifest (= manifest (impl/manifest (:index lib))))
                    step

                    :else
                    (impl/with-index
                     step
                     {:value (if manifest
                               (cons {:name ciff/manifest-name :body manifest}
                                     (for [n (impl/manifest-segments manifest)
                                           :when (contains? docs n)]
                                       {:name n :body (get docs n)}))
                               (for [[n body] docs
                                     :when (impl/index-document? n)]
                                 {:name n :body body}))}))))
        [lib report] (impl/with-saved-map library saved-keys name saved)]
    (if (:newer-format lib)
      {:library lib :report report}
      (let [lib          (update lib :feeds #(into {} (map kept) %))
            [lib report] (-> [lib report]
                             (impl/with-read-feeds (keep read (:feeds lib)))
                             index)]
        {:library lib :report report}))))

(defn from-documents
  "The `library` with what the documents `docs` hold, a map of their names
  to their bodies, e.g. those that read! fetches, and the report of what
  didn't read, as a map of the :library and the :report. The library has
  the parts of its own document that saved-keys names, each feed from its
  document, and the search index.

  It reads the documents in the :codec of `opts`, e.g. that of
  dk.simongray.like-minded.transit, of any version that restore! reads,
  among them those that save! wrote. A feed that `library` holds in memory
  with the same document stays as it is, and so does the index when its
  manifest is the same, so give it the library as it is to read only what
  changed. The report is as restore! has it, and a document that `docs`
  lack doesn't read. Without the library's own document, it's `library`
  as it is."
  ([library docs]
   (from-documents library docs {}))
  ([library docs opts]
   {:pre [(map? opts)]}
   (let [codec (impl/codec library opts)
         name  (impl/library-name codec)]
     (if (contains? docs name)
       (from-saved library docs name (impl/read-map {:body (get docs name)} codec) codec)
       {:library library :report []}))))

(defn read!
  "The library in `store`, read onto `library` with from-documents and the
  `opts`, the version of its own document and the report of what didn't
  read, as a map of the :library, the :version and the :report, in
  ClojureScript as a promise.

  It fetches the library's own document, in the :codec of `opts`, and then
  the documents that it names and `library` doesn't hold, so give it the
  library as it is to read only what changed. It takes no lock, so it
  starts over when a document is missing because another context wrote the
  store meanwhile. A store without the library's document gives `library`
  as it is, with no version. When the store fails to give the library's
  own document, it fails too, so that nothing writes over what it holds."
  ([library store]
   (read! library store {}))
  ([library store opts]
   {:pre [(map? opts)]}
   (let [codec    (impl/codec library opts)
         own      (impl/library-name codec)
         fetch!   (fn [name]
                    (let [opts (if (impl/index-document? name) {:as :bytes} {})]
                      (async/then (async/attempt #((:fetch store) name opts))
                                  (fn [{:keys [value]}] [name value]))))
         own!     (fn []
                    (async/then ((:fetch store) own {})
                                #(when % [own %])))
         rounds   (fn rounds [saved docs tried]
                    (let [wanted (remove tried (needs library saved docs))]
                      (if (empty? wanted)
                        docs
                        (async/then (async/all fetch! wanted)
                                    (fn [fetched]
                                      (rounds saved
                                              (into docs (for [[name doc] fetched
                                                               :when doc]
                                                           [name (:body doc)]))
                                              (into tried wanted)))))))]
     (letfn [(attempt [tries]
               (async/then
                (own!)
                (fn [[name {:keys [body version]} :as found]]
                  (if-not found
                    {:library library :version nil :report []}
                    (let [saved (impl/read-map {:body body} codec)
                          docs  {name body}]
                      (async/then
                       (rounds saved docs #{name})
                       (fn [docs]
                         (let [done #(assoc (from-saved library docs name saved codec)
                                            :version version)]
                           (if (and (seq (needs library saved docs)) (pos? tries))
                             (async/then ((:fetch store) name {})
                                         #(if (= version (:version %))
                                            (done)
                                            (attempt (dec tries))))
                             (done))))))))))]
       (attempt 3)))))

(defn write!
  "Write `library` to `store` with the `opts` below, and give the :version
  of its own document and the names of the documents that it put and
  deleted, as a map of :version, :put and :delete, in ClojureScript as a
  promise. It gives a map of :conflict? and :put when the library's own
  document isn't of the :if-version, and a map of :error otherwise, as
  save! does.

  - :codec, that of the documents
  - :if-version, the version of the library's own document in the store
    to write over, as read! gives it, or nil when there's to be none yet.
    Without it, the document is written whatever its version.
  - :before, the library that `library` comes from, as read! gave it or
    write! wrote it, whose documents go once `library` no longer has them

  It puts the documents of documents that the store lacks, and the
  library's own document last, on condition of the :if-version. Then it
  deletes the documents of :before that `library` doesn't have, and the
  manifest that save! writes beside the library's own document, which now
  holds it. It deletes nothing else, so it never deletes what another
  context put for a write still under way.

  It takes no lock and reads nothing but the names in the store, so on a
  conflict, read! the store again and change what it gives, as swap! does
  with an atom, which durable-swap! does."
  ([library store]
   (write! library store {}))
  ([library store {:keys [before] :as opts}]
   {:pre [(map? opts)]}
   (let [refused (:newer-format library)
         codec   (impl/codec library opts)
         own     (impl/library-name codec)
         delete! (fn [name]
                   (async/then (async/attempt #((:delete store) name))
                               #(when-not (:error %) name)))
         stale   (fn [held]
                   (let [now (document-names library)]
                     (->> (concat (remove now (some-> before document-names))
                                  [ciff/manifest-name])
                          (filter (set held))
                          distinct)))
         written (fn [held]
                   (let [docs (documents library (assoc opts :except (set held)))
                         puts (for [[name body] (sort-by key (dissoc docs own))]
                                {:name name :body body})]
                     (async/then
                      (store/put-all! store puts)
                      (fn [put]
                        (async/then
                         ((:put store) own (get docs own) (select-keys opts [:if-version]))
                         (fn [{:keys [conflict? version]}]
                           (if conflict?
                             {:conflict? true :put put}
                             (async/then (if (:delete store)
                                           (async/all delete! (stale held))
                                           [])
                                         (fn [deleted]
                                           {:version version
                                            :put     (conj put own)
                                            :delete  (filterv some? deleted)})))))))))]
     (async/then
      (async/attempt
       #(cond
          refused         nil
          (:put store)    (async/then (if-let [list (:list store)] (list) [])
                                      (fn [listed] (written (map :name listed))))
          :else           (throw (ex-info "The store is read only" {}))))
      (fn [{:keys [value error]}]
        (cond
          refused {:error refused}
          error   (diagnostics/failed {:id     :library/save-failed
                                       :reason (async/explain error)})
          :else   value))))))

(defn durable-swap!
  "Write `f` of the library that `known` holds to `store` with the `opts`
  of write!, on condition of the version that `known` holds, and `f` of
  the stored library again whenever another context wrote first, as swap!
  does with an atom. It gives the library and the version written, as a
  map of the :library and the :version, or a map of the :error of a write
  or a read that failed, in ClojureScript as a promise.

  The `known` is a map of the :library and its :version, as read! gives
  it and this gives it back. Since `f` can run more than once, give it
  the time of its change, e.g. as the :at of step, so that it stamps the
  same time each time."
  ([store known f]
   (durable-swap! store known f {}))
  ([store {:keys [library version] :as known} f opts]
   {:pre [(map? opts)]}
   (let [changed (f library)]
     (async/then
      (write! changed store (assoc opts :if-version version :before library))
      (fn [{:keys [conflict? error] :as written}]
        (cond
          error     written
          conflict? (async/then (async/attempt #(read! library store opts))
                                (fn [{:keys [value error]}]
                                  (if error
                                    (diagnostics/failed {:id     :library/save-failed
                                                         :reason (async/explain error)})
                                    (durable-swap! store value f opts))))
          :else     {:library changed :version (:version written)}))))))

(defn plan
  "The episodes to download and the files to delete on this device with
  `files`, for the profile and feeds of `library`, with the `opts` of
  downloads/plan. Without `files`, it's the record of the files in the
  library.

  It plans with the feeds that the library holds in memory. The files of
  the episodes of a feed that it doesn't hold are kept, so load! every
  feed first for a plan of them all. A show of local files has nothing to
  download."
  ([library]
   (plan library nil {}))
  ([library files]
   (plan library files {}))
  ([library files opts]
   {:pre [(map? opts)]}
   (let [asleep (set (unloaded library))
         kept   #(if (contains? asleep (first (key %)))
                   [(key %) (assoc (val %) :keep? true)]
                   %)]
     (downloads/plan (:profile library)
                     (impl/shown-feeds (update library :feeds
                                               #(into {}
                                                      (remove (comp :local? val))
                                                      %)))
                     (into {} (map kept) (or files (:files library)))
                     (impl/time-of library opts)
                     (assoc opts :url (urls library))))))

(defn start-download
  "The `library` with the download of `entry`, one of the :download of
  its plan, started at the time of the call, with `opts`, as
  downloads/start-download records it."
  ([library entry]
   (start-download library entry {}))
  ([library entry opts]
   {:pre [(map? opts)]}
   (update library :files downloads/start-download entry
           (impl/time-of library opts))))

(defn finish-download
  "The `library` with the download of the episode `id` finished at the
  time of the call, with `opts`, and the :path and :bytes of the file in
  `details`, as downloads/finish-download records it."
  ([library id details]
   (finish-download library id details {}))
  ([library id details opts]
   {:pre [(map? opts)]}
   (update library :files downloads/finish-download id details
           (impl/time-of library opts))))

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
           (impl/time-of library opts))))

(defn remove-file
  "The `library` without the file of the episode `id`, deleted at the time
  of the call, with `opts`. The entry stays, so that no plan downloads it
  again."
  ([library id]
   (remove-file library id {}))
  ([library id opts]
   {:pre [(map? opts)]}
   (update library :files downloads/remove-file id
           (impl/time-of library opts))))

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
     (dissoc (refresh! library) :library)

     ;; the newest episode, played a minute in
     (def id
       (let [[feed item] (first (episodes library {:order :newest :limit 1}))]
         (episode-id library feed item)))
     (:effects (step library [:progress id 60 3600]))
     (select-keys (clojure.core/merge (episode library id)
                                      (read-files! library id))
                  [:title :duration :chapters])
     #_.))
