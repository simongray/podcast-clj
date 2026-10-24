(ns ^:no-doc dk.simongray.podcast.app.library.impl
  "The internals of app.library: the options and clock of a call, the
  episodes a library lists, the kept items, the search index upkeep, the
  refresh and push machinery, the recommendation helpers, and the saved
  format and its migrations."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.analysis :as analysis]
            [dk.simongray.drop-in-search.ciff :as ciff]
            [dk.simongray.drop-in-search.segment :as segment]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.impl :as profile.impl]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]))

(def default-config
  "The config of a library when none is given, as app.library/default-config
  describes it."
  {:search?         true
   :list-seconds    86400
   :offline-seconds 300})

(defn options
  "The `opts` of a call over the config of `library` and the defaults."
  [library opts]
  (clojure.core/merge default-config (:config library) opts))

(def function-keys
  "The options of a call that are functions or hold them, which stay out
  of the config, as app.library says."
  [:fetch :send :allow-pred :credentials-of :keep-fn :local-file-fn :cache
   :codec :lenient-parser :now-fn])

(defn settings
  "The settings of a subscription in the `opts` of a call that subscribes:
  all of them but :at and the options that hold functions."
  [opts]
  (apply dissoc opts :at function-keys))

(defn time-of
  "The instant of a call to `library` with `opts`: their :at, or else the
  time of the clock, their :now-fn."
  [library opts]
  (or (:at opts) ((:now-fn (options library opts) time/now))))

(defn remote-guids
  "The item guids of the remote items of `profile` in the feed
  `feed-guid`."
  [profile feed-guid]
  (into #{}
        (keep (fn [[[g item-guid] _]]
                (when (= feed-guid g)
                  item-guid)))
        (profile/remote-items profile)))

(defn without-episodes-of
  "The `profile` with the episodes of the feed `feed-guid` out of its queue
  and its inbox at `at`, but for its remote items."
  [profile feed-guid at]
  (let [remote (remote-guids profile feed-guid)
        of?    (fn [[g item-guid]]
                 (and (= feed-guid g) (not (contains? remote item-guid))))]
    (-> profile
        (profile.queue/dequeue-all (filter of? (profile.queue/queue profile)) at)
        (profile.queue/dismiss-all (filter of? (profile.queue/inbox profile)) at))))

(defn- with-fact
  "The `item` of the feeds `entry` with the fact that the entry keeps of
  it under :retained, when the feed no longer brings it back."
  [entry item]
  (if-let [fact (get-in entry [:retained (guid/item-guid item)])]
    (assoc item :retained fact)
    item))

(defn identified
  "The feed of the feeds `entry`, with the ids of the directories that the
  entry keeps as its :directory/sources, from which the views link to the
  show in the apps."
  [{:keys [feed ids] :as entry}]
  (cond-> feed
    (seq ids) (assoc :directory/sources ids)))

(defn marked
  "The feed of the feeds `entry`, with its directory ids, and with the fact
  of each item that it retains under the item's :retained."
  [{:keys [retained] :as entry}]
  (cond-> (identified entry)
    (seq retained) (update :rss/items (partial mapv #(with-fact entry %)))))

(defn shown
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

(defn shown-feeds
  "The feeds of `library` with the episodes that it lists."
  [library]
  (keep #(shown library %) (keys (:feeds library))))

(defn unloaded?
  "Whether the feeds `entry` names the document of its feed rather than
  holding the feed."
  [entry]
  (boolean (and (:document entry) (not (:feed entry)))))

(defn- documents
  "The search documents of the feed `feed-guid` of `library`: of the show
  and its episodes for a subscription, and else of its remote items
  alone, with the runs of words that the show repeats as the :repeated of
  their metadata."
  [library feed-guid]
  (when-let [feed (shown library feed-guid)]
    (let [url  (get-in library [:feeds feed-guid :url])
          docs (feed.search/feed-documents feed url)]
      (cond->> docs
        (not (profile/subscribed? (:profile library) feed-guid))
        (into (with-meta [] (meta docs)) (filter (comp vector? :id)))))))

(defn indexed
  "The `library` with a search index of its feeds, e.g. for a library read
  back without its :index, and the runs of words that each show repeats
  in the entry of its feed."
  [library]
  (let [docs (into {} (keep #(some->> (documents library %) (vector %)))
                   (keys (:feeds library)))]
    (-> (reduce-kv (fn [library feed-guid ds]
                     (assoc-in library [:feeds feed-guid :repeated]
                               (:repeated (meta ds))))
                   library
                   docs)
        (assoc :index (feed.search/index (mapcat val docs))))))

(defn reindexed
  "The `library` with the feed `feed-guid` indexed again, when it keeps an
  index, and the runs of words that its show repeats in the entry of its
  feed. A feed that isn't loaded keeps what the index has of it."
  [library feed-guid]
  (let [index (:index library)]
    (if (and index (not (unloaded? (get-in library [:feeds feed-guid]))))
      (let [docs (documents library feed-guid)]
        (cond-> (assoc library :index (apply search/add
                                             (feed.search/without-feed index feed-guid)
                                             docs))
          docs (assoc-in [:feeds feed-guid :repeated] (:repeated (meta docs)))))
      library)))

(defn alone?
  "Whether `library` holds the feed `feed-guid` for its remote items
  alone, without a subscription."
  [library feed-guid]
  (and (contains? (:feeds library) feed-guid)
       (not (profile/subscribed? (:profile library) feed-guid))))

(defn- named
  "The feeds `entry` of `feed-guid` with only the items that the remote
  items of `profile` name, and their retained facts."
  [entry feed-guid profile]
  (let [guids  (remote-guids profile feed-guid)
        named? #(contains? guids (guid/item-guid %))]
    (cond-> entry
      (:feed entry)     (update-in [:feed :rss/items] #(filterv named? %))
      (:retained entry) (update :retained select-keys guids))))

(defn tidied
  "The `library` with the feed `feed-guid` indexed again while a
  subscription or a remote item needs it, and else without the feed. A
  feed held for remote items alone keeps the items they name."
  [library feed-guid]
  (let [profile (:profile library)]
    (cond
      (profile/subscribed? profile feed-guid)
      (reindexed library feed-guid)

      (seq (remote-guids profile feed-guid))
      (cond-> (reindexed library feed-guid)
        (contains? (:feeds library) feed-guid)
        (update-in [:feeds feed-guid] named feed-guid profile))

      :else
      (cond-> (update library :feeds dissoc feed-guid)
        (:index library) (update :index feed.search/without-feed feed-guid)))))

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

(defn kept
  "The `library` with the feed `feed-guid` cut down to the :max-items of
  `opts`, as far as capped allows, and to what their :keep-fn gives of it,
  once nothing else needs the whole feed. A feed held for remote items
  alone keeps the items they name."
  [library feed-guid {:keys [keep-fn max-items] :as opts}]
  (let [feed? (some? (get-in library [:feeds feed-guid :feed]))]
    (cond-> library
      (and feed? (alone? library feed-guid))
      (update-in [:feeds feed-guid] named feed-guid (:profile library))

      (and max-items feed?)
      (update-in [:feeds feed-guid] capped feed-guid (:profile library)
                 max-items)

      (and keep-fn feed?)
      (update-in [:feeds feed-guid :feed] keep-fn))))

(defn settled
  "The `library` with the feed `feed-guid` indexed again and cut down as
  the `opts` say, as after a fetch."
  [library feed-guid opts]
  (-> library
      (reindexed feed-guid)
      (kept feed-guid opts)))

(defn fetch-result
  "The result of a fetch that `x` stands for: `x` itself, or a map with `x`
  as the :feed when `x` is a parsed feed, which has namespaced keys."
  [x]
  (if (some #(and (keyword? %) (namespace %)) (keys x))
    {:feed x}
    x))

(defn feed-of
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

(defn media-ids
  "A function of the guid of a feed of `library` that gives the ids of its
  episodes by the URL of each of their media files, as guid/normal-url
  writes it, with and without its query, worked out once for each feed."
  [library]
  (memoize
   (fn [feed-guid]
     (let [bare  #(first (str/split % #"[?#]" 2))
           items (get-in library [:feeds feed-guid :feed :rss/items])]
       (into {}
             (for [item         items
                   :let         [item-guid (guid/item-guid item)]
                   :when        item-guid
                   {media :url} (resolve/enclosures item)
                   :when        (string? media)
                   k            (distinct [media (bare media)])]
               [(guid/normal-url k) [feed-guid item-guid]]))))))

(defn listed
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

(defn due-lists
  "The entries of the followed lists of `library` that are due at `at`,
  by URL, or none when the :online? of `opts` is false."
  [library at opts]
  (if (false? (:online? opts))
    {}
    (select-keys (:fetched-lists library)
                 (feeds/due (:fetched-lists library) at))))

(defn fetch-lists!
  "Fetch the lists of the `entries`, by URL, with `opts` and the ETag and
  Last-Modified of each, in a future or a promise of a vector of pairs of
  a URL and what the fetch gave."
  [entries opts]
  (async/all (fn [[url entry]]
               (async/then (fetch-list! url entry opts) #(vector url %)))
             entries))

(defn lists-applied
  "The `library` with the `results` of fetch-lists! applied at `at`, with
  the :list-seconds of `opts`, as a map of the :library and the :report."
  [library results at opts]
  (let [apply1 (fn [{:keys [library] :as acc} [url fetched]]
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
    (reduce apply1 {:library library :report []} results)))

(defn refresh-lists!
  "Fetch the lists of `library` that are due at `at` with `opts`, and
  apply them, in a future or a promise. The result is a map of the
  :library and the :report."
  [library at opts]
  (async/then (fetch-lists! (due-lists library at opts) opts)
              #(lists-applied library % at opts)))

(defn offline-lists
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

(defn pending
  "The feeds of the live subscriptions and remote items of `library` that
  it has no feed of yet, or holds for remote items alone but without an
  item that one names, e.g. one that came by sync, as feed entries to
  fetch whole. A subscription names the URL before a remote item does.

  A feed that was never fetched keeps the failures of its fetches and
  when it's due again, which its entry holds."
  [library]
  (let [{:keys [profile feeds]} library
        lacking? (fn [feed-guid]
                   (let [{:keys [feed]} (get feeds feed-guid)
                         items           (into #{}
                                               (map guid/item-guid)
                                               (:rss/items feed))]
                     (or (nil? feed)
                         (and (alone? library feed-guid)
                              (not-every? items
                                          (remote-guids profile feed-guid))))))
        failed  (fn [feed-guid]
                  (let [entry (get feeds feed-guid)]
                    (when-not (:feed entry)
                      (select-keys entry [:failures :next-check]))))
        remote  (for [[[feed-guid] {:keys [feed-url]}]
                      (profile/remote-items profile)
                      :when (lacking? feed-guid)]
                  [feed-guid (into {:url (get-in feeds [feed-guid :url] feed-url)}
                                   (failed feed-guid))])
        subs    (for [[feed-guid {:keys [url]}] (profile/subscriptions profile)
                      :when (and url (not (contains? feeds feed-guid)))]
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

(defn- join-pred
  "The predicate of a feed and an item that a new episode of the show
  `feed-guid` of `library`, at `url`, meets to join the queue or the inbox
  at `at`, by the :join-where of its settings in `profile`, or nil.
  Criteria of a later version that this one doesn't know select every
  episode."
  [library profile feed-guid url at]
  (let [where (:join-where (profile/settings profile feed-guid))]
    (when (and where (nil? (criteria/problem {:where where})))
      (criteria/where where {:profile profile
                             :url     url
                             :at      at
                             :files   (:files library {})}))))

(defn applied
  "The `library` with the `result` of a refresh of its feeds applied to the
  profile at `at`, and the fresh feeds cut down as the `opts` say, as a map
  of the :library, what was :added, :backfilled, :renamed and :moved, and
  the :report of the feeds that would take another subscription over or
  give themselves another guid.

  A feed fetched for the first time is that of a new subscription, so its
  episodes aren't new ones, and neither are those of a feed of remote
  items. A feed that's still not fetched is left out, and fetched next
  time, but for one whose fetch failed, which keeps its failures and when
  it's due again. A feed that isn't loaded stays in its document."
  [library {:keys [feeds changed moved] :as result} at opts]
  (let [before   (:feeds library)
        kept?    #(or (:feed %) (unloaded? %) (:failures %))
        library  (assoc library :feeds (into {} (filter (comp kept? val))
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
                       (let [join (join-pred library current feed-guid url at)
                             {:keys [profile added backfilled renamed]}
                             (profile.queue/refresh current url old-feed feed
                                                    at
                                                    (cond-> opts
                                                      join (assoc :join-pred
                                                                  join)))]
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

(defn counted
  "How many feeds the `step` of feeds/refresh! checked, and of those, how
  many were unchanged, changed and failed, and how many it left due."
  [{:keys [checked changed failed left] :as step}]
  (let [n (count checked)]
    {:checked   n
     :unchanged (- n (count changed) (count failed))
     :changed   (count changed)
     :failed    (count failed)
     :left      (count left)}))

(declare codec with-feeds)

(defn loaded-for-refresh
  "The `step` of a refresh at `at`, a map of the :library and the :report,
  with the feeds that the refresh fetches loaded from the :store of
  `opts`, when the library doesn't hold them. Without a store, the report
  has :library/not-loaded for those that are due."
  [{:keys [library report] :as step} at {:keys [store feed-guids] :as opts}]
  (let [due    (set (or feed-guids (feeds/due (:feeds library) at)))
        asleep (filter #(and (contains? due %)
                             (unloaded? (get-in library [:feeds %])))
                       (keys (:feeds library)))]
    (cond
      (empty? asleep)
      step

      store
      (async/then (with-feeds [library report] store (set asleep) (codec library opts))
                  (fn [[library report]]
                    {:library library :report report}))

      :else
      (update step :report conj
              (diagnostics/describe {:id         :library/not-loaded
                                     :feed-guids (vec asleep)})))))

(defn pushable
  "The hubs and topic of each feed in `library` that a WebSub hub can push,
  by guid: the feeds of the listener's shows that name a usable hub and
  aren't gone."
  [library]
  (let [profile (:profile library)]
    (into {}
          (keep (fn [[feed-guid {:keys [websub gone?]}]]
                  (let [{:keys [hubs topic]} websub]
                    (when (and (url/web-url? (first hubs))
                               (url/web-url? topic)
                               (not gone?)
                               (profile/subscribed? profile feed-guid))
                      [feed-guid websub]))))
          (:feeds library))))

(defn item-of
  "The entry of the feed `feed-guid` in `library` and its item
  `item-guid`, an episode or a live item, with its :retained fact, as a
  pair, or nil."
  [library [feed-guid item-guid]]
  (when-let [{:keys [feed] :as entry} (get-in library [:feeds feed-guid])]
    (when-let [item (some #(when (= item-guid (guid/item-guid %)) %)
                          (concat (:rss/items feed)
                                  (:podcast/live-items feed)))]
      [entry (with-fact entry item)])))

(defn where-pred
  "The predicate of a feed and one of its items for the `criteria` of
  criteria/where, which read the profile of `library`, whose feeds have
  the `urls` of library/urls, the :at of the `opts` or the time of its
  clock, and their :scale."
  [library urls criteria opts]
  (criteria/where criteria (assoc (select-keys opts [:scale])
                                  :profile (:profile library)
                                  :url     urls
                                  :at      (time-of library opts)
                                  :index   (:index library))))

(defn index-opts
  "The `opts` of a query of the index of `library`, whose feeds have the
  `urls` of library/urls, with their :where, the criteria of
  criteria/where that an episode meets, and :filter-pred made one
  :filter-pred of a result with the :feed and :item that `locate` gives
  it. The criteria leave the shows out."
  [library urls locate opts]
  (let [meets? (when-let [criteria (:where opts)]
                 (where-pred library urls criteria opts))
        tests  (cond-> []
                 (:filter-pred opts) (conj (:filter-pred opts))
                 meets?              (conj (fn [{:keys [feed item]}]
                                             (and item (meets? feed item)))))]
    (cond-> (dissoc opts :where :at :scale)
      (seq tests) (assoc :filter-pred
                         (comp (apply every-pred tests) locate)))))

(defn seed
  "The episode `id` of `library` as a map of its :id, :feed, :item and
  search :document, or nil when the library doesn't hold it."
  [library id]
  (when-let [[{:keys [feed url repeated]} item] (item-of library id)]
    {:id       id
     :feed     feed
     :item     item
     :document (feed.search/item-document feed item {:url      url
                                                     :repeated repeated})}))

(def topic-fields
  "The fields of the search document of an episode that say what it's
  about, which recommend reads: not the show's name, nor its people, which
  every episode of a show shares."
  #{:title :description :keywords :chapters :transcript})

;; CLDR's names of the months and days, which both Java and Intl hold, in
;; the language of a show, since a date in its notes, e.g. "Mon 22 Sep",
;; is written in it
(defn- calendar-names*
  [tag]
  #?(:clj  (let [symbols (java.text.DateFormatSymbols/getInstance
                          (java.util.Locale/forLanguageTag tag))]
             (concat (.getMonths symbols) (.getShortMonths symbols)
                     (.getWeekdays symbols) (.getShortWeekdays symbols)))
     :cljs (let [named  (fn [k style d]
                          (.format (js/Intl.DateTimeFormat. tag (js-obj k style)) d))
                 months (map #(js/Date. 2000 % 1) (range 12))
                 days   (map #(js/Date. 2000 0 (+ 2 %)) (range 7))]
             ;; realised inside the try, which a lazy seq would leave
             (try
               (vec (concat (map #(named "month" "long" %) months)
                            (map #(named "month" "short" %) months)
                            (map #(named "weekday" "long" %) days)
                            (map #(named "weekday" "short" %) days)))
               ;; a tag that Intl doesn't take
               (catch :default _ nil)))))

(def calendar-names
  "The names of the months and the days of the week in the language `tag`,
  long and short, e.g. \"September\" and \"Sep\" in English."
  (memoize (fn [tag] (into #{} (remove str/blank?) (calendar-names* tag)))))

;; A show's hosts are named in the notes of every episode, e.g. "Adam &
;; Dave", and its numbers are its episodes' and dates, so they link it to
;; other shows by chance: to any Adam, or any episode 561. A guest of an
;; episode isn't left out, since the guest's other episodes are like it.
(defn chance-words
  "The words of the episodes `seeds`, as seed gives them, that link them to
  other episodes by chance rather than by what they're about:

  - the numbers in their documents, e.g. of episodes and dates
  - the names of the months and days in the languages of their shows,
    which their dates hold
  - the handles that they mention, e.g. the js of @js
  - the words of the names of the people and the authors of their shows"
  [seeds]
  (into #{}
        (mapcat (fn [{:keys [feed item document]}]
                  (let [texts (filter string? (vals (:fields document)))]
                    (concat (mapcat #(re-seq #"\d+" %) texts)
                            (calendar-names (or (not-empty (resolve/language-tag
                                                            (:rss/language feed)))
                                                "en"))
                            (mapcat #(map second (re-seq #"@([^\s@,:;!?()\[\]<>\"']+)" %))
                                    texts)
                            (mapcat #(re-seq #"[^\s&,.]+" (str %))
                                    (concat (map :name (resolve/people feed))
                                            [(resolve/author feed)
                                             (resolve/author item)]))))))
        seeds))

;; The positions are sorted by when they were reported, and only the
;; entries of the first are made, since making them all takes seconds for
;; a listener who has heard thousands of episodes
(defn heard-last
  "The ids of the `n` episodes of `library` that the listener heard last,
  the last first, of those that the library holds and the profile hasn't
  forgotten, and that `pred` of a feed and an item takes."
  [library n pred]
  (let [profile (:profile library)
        ;; the last first, and equal times by id, the same everywhere
        later   (fn [[a x] [b y]]
                  (let [c (compare y x)]
                    (if (zero? c) (compare a b) c)))
        takes?  (fn [id]
                  (when-let [[{:keys [feed]} item] (item-of library id)]
                    (pred feed item)))]
    (->> (:episodes profile)
         (keep (fn [[id e]]
                 (when-let [at (and (crdt/live? e) (:reported-at e))]
                   [id (inst-ms at)])))
         (sort later)
         (map first)
         (filter #(and (profile/episode-entry profile %) (takes? %)))
         (take n))))

(defn languages-of
  "The primary subtags of the languages of the episodes `seeds`, as seed
  gives them, e.g. #{\"en\" \"da\"}."
  [seeds]
  (into #{}
        (comp (mapcat (fn [{:keys [feed item]}]
                        (resolve/languages feed item)))
              (map resolve/language-tag))
        seeds))

(defn shows-in
  "The feed guids of the shows of `library` whose feeds declare one of the
  `languages`, primary subtags as languages-of gives them."
  [library languages]
  (into #{}
        (keep (fn [[feed-guid {:keys [feed]}]]
                (when (contains? languages
                                 (resolve/language-tag (:rss/language feed)))
                  feed-guid)))
        (:feeds library)))

;; Ad text and function words are in many of a language's episodes, e.g.
;; adswizz in 31% of the English ones of Apple's charts and vil in 17% of
;; the Danish, and topics in few, e.g. ukraine in 1.2% and rss in 4.7%.
;; The share of the shows that use a word can't tell them apart, since many
;; shows cover a topic such as Ukraine.
(def common-share
  "The share of the episodes in a language that a word may be in, at most,
  to say what an episode is about."
  0.08)

(def least-common
  "The fewest episodes that common-share caps a word at, since a share of
  the episodes of a small library is too few to tell by."
  10)

;; The index holds the episodes of the shows that aren't loaded too, and
;; only the remote items of a show held for them, so its documents are
;; what a word's count of episodes is out of. A show that isn't loaded
;; counts in any language, since its feed isn't there to say which.
(defn common-cap
  "The most episodes in the index of `library` that a word may be in, as
  more-like-this takes its :max-doc-freq: common-share of the episodes of
  the `shows`, a set of feed guids as shows-in gives them, and of those
  not loaded, or of all of them when `shows` is nil."
  [library shows]
  (let [counted? (if shows
                   #(or (contains? shows %)
                        (unloaded? (get-in library [:feeds %])))
                   (constantly true))
        episodes (->> (search/ids (:index library))
                      (filter vector?)
                      (map first)
                      frequencies
                      (keep (fn [[feed-guid n]] (when (counted? feed-guid) n)))
                      (reduce + 0))]
    (max least-common (long (* common-share episodes)))))

(defn held-terms
  "The `terms` of a query of more-like-this, pairs of a term and its
  weight, the best first, that the `fields` of the search `document` hold,
  as the terms alone."
  [terms document fields]
  (let [held (into #{}
                   (comp (mapcat #(analysis/tokens (get-in document [:fields %])))
                         (mapcat #(if (vector? %) % [%])))
                   fields)]
    (into [] (comp (map first) (filter held)) terms)))

;; The tests of a result by its id come before those that locate its feed
;; and item, since locating builds a map of the items of each feed, which
;; for a rare language means every feed of the library
(defn id-pred
  "A predicate of a result of recommend by its id, from the episodes
  `seeds`, as seed gives them, the shows `in-languages` of their
  languages, as shows-in gives them, and the `opts` of recommend: an
  episode, of another show unless :same-show?, and of one of those shows,
  unless :same-language? is false, the criteria of :where name a
  :language, or `in-languages` is nil."
  [seeds in-languages {:keys [same-show? same-language? where]
                       :or   {same-language? true}}]
  (let [shows   (into #{} (map (comp first :id)) seeds)
        allowed (when (and same-language? (not (contains? where :language)))
                  in-languages)]
    (fn [{:keys [id]}]
      (and (vector? id)
           (or same-show? (not (contains? shows (first id))))
           (or (nil? allowed) (contains? allowed (first id)))))))

(defn compacting
  "The :compact option of a sync of `library` with `opts`: false, or the
  options of profile/compact, with the :archive-after of `opts` or of the
  config."
  [library opts]
  (let [{:keys [compact archive-after]} (options library opts)]
    (if (false? compact)
      false
      (into (if archive-after {:archive-after archive-after} {}) compact))))

(defn newer
  "Of `a` and `b`, two maps with an instant under `k`, the newer one, or
  `b` when `a` isn't newer."
  [k a b]
  (if (pos? (compare (k a) (k b))) a b))

(defn merged-entries
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

(defn printed
  "The EDN of `x`, whole."
  [x]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-namespace-maps* false]
    (pr-str x)))

(def edn-codec
  "The codec of the documents of a saved library in EDN, the default: a map
  of the :extension of their names, and the functions that :encode a map
  as the body of a document and :decode it."
  {:extension ".edn"
   :encode    printed
   :decode    edn/read-string})

(defn codec
  "The codec of the documents of `library` with `opts`: their :codec, or
  else EDN's. It throws when the codec isn't of the :extension of the
  config, since the library's documents are named by it."
  [library opts]
  (let [{:keys [codec extension]} (options library opts)
        codec (or codec edn-codec)]
    (when (and extension (not= extension (:extension codec)))
      (throw (ex-info (str "The documents of this library end in " extension
                           ", so give the call the :codec of that extension")
                      {:extension extension :codec (:extension codec)})))
    codec))

(defn naming
  "What names the documents of `library`: a map of the :extension of its
  config, as a codec has it, which is EDN's by default."
  [library]
  {:extension (get-in library [:config :extension] (:extension edn-codec))})

(defn codec-of
  "The codec of the document `name`, by its extension: the `codec` of the
  library, or else EDN's."
  [codec name]
  (if (str/ends-with? (str name) (:extension codec))
    codec
    edn-codec))

(def saved-name
  "The name of the document of a library saved in EDN."
  "library.edn")

(defn library-name
  "The name of the document of a library saved with `codec`."
  [codec]
  (str "library" (:extension codec)))

(defn library-document?
  "Whether `name` is that of the document of a saved library, in any
  codec."
  [name]
  (boolean (re-matches #"library\.[a-z.]+" (str name))))

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
         (map? (:profile saved)) (update :profile profile.impl/upgrade)))
   3 identity})

(defn document-name
  "The name of the document of the feed `feed-guid` that changed at the
  instant `changed-at`, with `codec`, EDN's by default:
  feed-<id>-<milliseconds>.edn. The id is the guid when it's a UUID, and
  else its UUIDv5, since a guid can be any text and a document's name must
  be a plain file name in every store."
  ([feed-guid changed-at]
   (document-name feed-guid changed-at edn-codec))
  ([feed-guid changed-at codec]
   (let [id (if (guid/guid? feed-guid)
              (str/lower-case feed-guid)
              (guid/uuid-v5 guid/podcast-namespace (str feed-guid)))]
     (str "feed-" id "-" (inst-ms changed-at) (:extension codec)))))

(defn feed-document?
  "Whether `name` is that of the document of a feed, in any codec."
  [name]
  (boolean (re-matches #"feed-[0-9a-f-]{36}-\d+\.[a-z.]+" (str name))))

(defn entry-document
  "The name of the document of the feeds entry `entry` of `feed-guid` with
  `codec`, by when its feed changed, or was fetched for an entry that
  doesn't say."
  [feed-guid entry codec]
  (document-name feed-guid
                 (or (:changed-at entry) (:fetched-at entry) (time/->inst 0))
                 codec))

(def feed-keys
  "The keys of a feeds entry that the document of its feed holds, rather
  than the library's own: the feed, the facts of the items that it
  retains, and the runs of words that its show repeats, which its search
  documents leave out."
  [:feed :retained :repeated])

(defn without-feed-keys
  "The feeds `entry` without its feed-keys, as the library's own document
  holds it."
  [entry]
  (apply dissoc entry feed-keys))

(defn feed-body
  "The document of the feeds `entry` with `codec`, of its feed-keys."
  [entry codec]
  ((:encode codec) (select-keys entry feed-keys)))

(defn saved-map
  "The parts of `library` that `keys` names, as its saved document with
  `codec` holds them: with the :version, and each feeds entry with the
  name of the document of its feed in place of its feed-keys."
  [library keys codec]
  (let [outlined (fn [[feed-guid entry]]
                   [feed-guid
                    (if (:feed entry)
                      (-> (without-feed-keys entry)
                          (assoc :document (entry-document feed-guid entry codec)))
                      entry)])]
    (cond-> (assoc (select-keys library keys) :version version)
      (some #{:feeds} keys) (update :feeds #(into {} (map outlined) %)))))

(defn feeds-plan
  "The documents of the `feeds` of a library to put with `codec` into a
  store that holds the documents of the `names`, and the names to delete
  from it, as a map of :put and :delete.

  A feed's document is put when the store doesn't hold its name, and a
  feed document that no feed names is deleted, that of another codec too.
  Without `names`, as of a store that can't list, it's every document and
  no deletes."
  [feeds names codec]
  (let [held   (set names)
        wanted (into {}
                     (for [[feed-guid entry] feeds
                           :when (:feed entry)]
                       [(entry-document feed-guid entry codec) entry]))
        asleep (into #{}
                     (keep #(when (unloaded? %) (:document %)))
                     (vals feeds))]
    {:put    (for [[name entry] wanted
                   :when (not (contains? held name))]
               {:name name
                :body (feed-body entry codec)})
     :delete (->> held
                  (filter #(and (feed-document? %)
                                (not (contains? wanted %))
                                (not (contains? asleep %))))
                  sort
                  vec)}))

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

(defn read-map
  "The map that the :body of `document` holds, as text or as the bytes of
  UTF-8, decoded with `codec`, or nil when it doesn't read as one."
  [{:keys [body] :as document} codec]
  (let [x (try
            ((:decode codec) (if (string? body) body (bytes/decode body)))
            (catch #?(:clj Throwable :cljs :default) _
              nil))]
    (when (map? x)
      x)))

;; Measured in Chrome on a parsed feed of 20 MB: 1152 ms to read its EDN,
;; 38 ms to read its Transit, and 346 ms to parse its XML again.
(def edn-slow?
  "Whether EDN reads slowly on this platform, as it does in ClojureScript."
  #?(:clj false :cljs true))

(defn slow-format
  "The :library/slow-format diagnostic of a restore of `library` with
  `opts` that read documents in EDN, in ClojureScript, or nil."
  [library opts]
  (when (and edn-slow? (= edn-codec (codec library opts)))
    (diagnostics/describe
     {:id     :library/slow-format
      :reason (str "ClojureScript reads EDN about 30 times slower than "
                   "Transit, slower than it parses the feeds again: give "
                   "the config of the library the :codec of "
                   "dk.simongray.like-minded.transit")})))

(defn- migrated
  "The saved library `saved`, of its :version or else of version 1,
  brought up to this version by the migrations."
  [saved]
  (loop [s saved]
    (let [v (:version s 1)]
      (if (< v version)
        (recur (assoc ((migrations v) s) :version (inc v)))
        s))))

(defn- parts
  "The parts of the saved map `saved` that `keys` names, brought up to this
  version, without the report of the last sync, which earlier versions
  kept with its state."
  [saved keys]
  (let [m (select-keys (migrated saved) keys)]
    (cond-> m
      (:sync m) (update :sync dissoc :report))))

(defn with-saved-map
  "The `library` with the parts that `keys` names of `saved`, the map that
  the document `name` of a saved library holds, or nil when it doesn't
  read as one, brought up to this version, and the report of what didn't
  read, as a pair, as with-saved gives them.

  A saved library of a newer version leaves the library as it is, with
  the diagnostic under :newer-format, so that nothing writes it over."
  [library keys name saved]
  (let [unreadable #(diagnostics/describe
                     {:id :library/unreadable-document :name name
                      :reason %})
        v          (:version saved 1)
        problem    (when (some #{:profile} keys)
                     (some-> (:profile saved) profile/problem))
        library    (dissoc library :newer-format)]
    (cond
      (nil? saved)       [library [(unreadable "It doesn't read as a map")]]
      (not (pos-int? v)) [library [(unreadable "Its version is no number")]]

      (< version v)
      (let [newer (diagnostics/describe
                   {:id      :library/newer-format
                    :version v
                    :reason  (str "It's of version " v ", and this version "
                                  "of podcast-clj reads up to " version)})]
        [(assoc library :newer-format newer) [newer]])

      problem
      [(clojure.core/merge library (dissoc (parts saved keys) :profile))
       [(diagnostics/describe {:id     :library/malformed-profile
                               :reason (str problem)})]]

      :else
      [(clojure.core/merge library (parts saved keys)) []])))

(defn with-saved
  "The `library` with the parts that `keys` names of the saved library, the
  :value of the `attempt` to fetch its document `name`, brought up to this
  version, and the report of what didn't read, as a pair. The document is
  read with `codec`, or EDN's, by its extension.

  A saved library of a newer version leaves the library as it is, and a
  saved profile that breaks the rules of profile/problem leaves its
  profile, with :library/newer-format or :library/malformed-profile in the
  report."
  [library keys name {:keys [value error] :as attempt} codec]
  (cond
    error        [library [(diagnostics/describe
                            {:id     :library/unreadable-document
                             :name   name
                             :reason (async/explain error)})]]
    (nil? value) [library []]
    :else        (with-saved-map library keys name
                   (read-map value (codec-of codec name)))))

(defn segment-names
  "The names of the documents of the segments of the search `index`."
  [index]
  (mapv segment/segment-name (:segments (search/restore index))))

(defn index-files
  "The files of the search `index` that a store with the documents of the
  `names` lacks, as maps of :name and :body: the segments that it doesn't
  hold, and the manifest last, as ciff/plan gives them."
  [index names]
  (:put (ciff/plan index names)))

(defn manifest
  "The manifest of the search `index`, as the text that index-files gives
  for it."
  [index]
  (:body (peek (index-files index (segment-names index)))))

(defn manifest-segments
  "The names of the segments that the `manifest` of a search index lists,
  as text or the bytes of UTF-8, or none when it doesn't read."
  [manifest]
  (let [m (try
            (edn/read-string (if (string? manifest) manifest (bytes/decode manifest)))
            (catch #?(:clj Exception :cljs :default) _
              nil))]
    (if (and (map? m) (sequential? (:segments m)))
      (into [] (keep :name) (:segments m))
      [])))

(defn index-document?
  "Whether `name` is that of a document of a search index: neither the
  library's own nor one of a feed."
  [name]
  (not (or (library-document? name) (feed-document? name))))

(defn read-feed
  "What the `attempt` to fetch the `document` of a feed read, with the
  `codec` of a library or EDN's, by its extension: a map of the :feed, its
  :retained facts and the runs of words that its show :repeated, or of
  the :reason that it doesn't read."
  [codec document {:keys [value error]}]
  (let [{:keys [feed retained repeated]} (when-not error
                                           (read-map value (codec-of codec document)))]
    (cond
      (map? feed)  (cond-> {:feed feed}
                     (map? retained) (assoc :retained retained)
                     (set? repeated) (assoc :repeated repeated))
      error        {:reason (async/explain error)}
      (nil? value) {:reason "It isn't in the store"}
      :else        {:reason "It doesn't read as a feed"})))

(defn with-read-feeds
  "The `library` and its `report`, a pair, with each of the `reads`,
  triples of a feed guid, the name of its document and what read-feed gave
  for it, in the entry of its feed. Each feed has its key, as
  guid/with-key gives it.

  An entry whose document didn't read keeps only its URL, so that the
  next refresh fetches it again, with :library/unreadable-document in the
  report."
  [[library report] reads]
  (let [applied (fn [[library report] [feed-guid document read]]
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
        ;; a feed keyed already stays as it is, e.g. one kept in memory
        keyed   (fn [[feed-guid {:keys [feed] :as entry}]]
                  [feed-guid (cond-> entry
                               (and feed (not= feed-guid (::guid/key (meta feed))))
                               (update :feed guid/with-key feed-guid))])
        [library report] (reduce applied [library report] reads)]
    [(update library :feeds #(into {} (map keyed) %))
     report]))

(defn with-feeds
  "The `library` and its `report`, a pair, with the feed and the retained
  facts of each feeds entry that names a document read from `store` with
  `codec`, of the feeds that `wanted?` holds for, in a future or a
  promise, as with-read-feeds has them."
  [[library report] store wanted? codec]
  (let [outlined (for [[feed-guid {:keys [document] :as entry}] (:feeds library)
                       :when (and (unloaded? entry) (wanted? feed-guid))]
                   [feed-guid document])
        fetch!   (fn [[feed-guid document]]
                   (async/then (async/attempt #((:fetch store) document {}))
                               #(vector feed-guid document
                                        (read-feed codec document %))))]
    (async/then (async/all fetch! outlined)
                #(with-read-feeds [library report] %))))

(defn with-index
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
      [(indexed library) report]

      :else
      [(indexed library)
       (conj report (diagnostics/describe
                     {:id     :library/index-rebuilt
                      :reason (async/explain (:error read))}))])))
