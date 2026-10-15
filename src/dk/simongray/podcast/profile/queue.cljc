(ns dk.simongray.podcast.profile.queue
  "The queue, the inbox and the playlists of a profile, and what the new
  episodes of a feed do to them.

  The queue and the playlists are ordered collections of mind-meld, so the
  devices agree on their order, and a move never brings back an episode
  that another device took out. A playlist is kept beside the queue, and
  goes out as a list feed of the podcast namespace."
  (:require [dk.simongray.mind-meld :as crdt]
            [dk.simongray.mind-meld.list :as crdt.list]
            [dk.simongray.mind-meld.rank :as rank]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.impl :as profile.impl]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.url :as url]))

(defn ^:no-doc queue-entries
  "The entries of the queue of `profile`, each at the place of its latest
  move."
  [profile]
  (crdt.list/with-moves (:queue profile) (:queue-moves profile)))

(defn ^:no-doc playlist-entries
  "The entries of the playlist `name` of `profile`, each at the place of its
  latest move."
  [profile name]
  (crdt.list/with-moves (get-in profile [:playlists name])
                   (get-in profile [:playlist-moves name])))

(defn ^:no-doc removed-from
  "The writes that take the entry at `path` out of a ranked list of
  `profile`, along with its move at `move-path` if it has one."
  [profile path move-path]
  (cond-> [[path #(assoc % :removed? true)]]
    (crdt/live? (get-in profile move-path))
    (conj [move-path #(assoc % :removed? true)])))

(defn queue
  "The episode ids queued in `profile`, the current one first."
  [profile]
  (mapv key (crdt.list/ranked (queue-entries profile))))

(defn current
  "The episode id at the front of the queue of `profile`, or nil when it's
  empty."
  [profile]
  (first (queue profile)))

(defn queued?
  "Whether the episode `id` is in the queue of `profile`."
  [profile id]
  (let [e (get-in profile [:queue id])]
    (crdt/live? e)))

(defn in-inbox?
  "Whether the episode `id` is in the inbox of `profile`, and the listener
  hasn't dismissed, queued or played it since it arrived."
  [profile id]
  (let [e (get-in profile [:inbox id])]
    (and (crdt/live? e)
         (not (queued? profile id))
         (not (profile/played? profile id)))))

(defn inbox
  "The episode ids in the inbox of `profile`, the latest to arrive first."
  [profile]
  (filterv #(in-inbox? profile %) (crdt/live-by-stamp (:inbox profile))))

(defn dismiss
  "Take the episode `id` out of the inbox of `profile` at `at`, leaving a
  tombstone."
  [profile id at]
  (if-let [writes (profile.impl/out-of-inbox profile id)]
    (crdt/write-each profile writes at)
    profile))

(defn dismiss-all
  "Take the episodes `ids` out of the inbox of `profile` at `at` with one
  stamp, e.g. all of the inbox, leaving tombstones."
  [profile ids at]
  (profile.impl/written profile
                        (mapcat #(profile.impl/out-of-inbox profile %) ids)
                        at))

(defn ^:no-doc place
  "The `profile` with the episode `id` queued at `rank` at `at`, and out of
  the inbox."
  [profile id rank at]
  (let [queued [[:queue id] (constantly {:rank rank :removed? false})]
        out    (profile.impl/out-of-inbox profile id)]
    (crdt/write-each profile (into [queued] out) at)))

(defn enqueue
  "Put the episode `id` at the end of the queue of `profile` at `at`, unless
  it is in the queue."
  [profile id at]
  (if (queued? profile id)
    profile
    (let [rank (crdt.list/rank-at-end (queue-entries profile))]
      (place profile id rank at))))

(defn enqueue-all
  "Put the episodes `ids` in order at the end of the queue of `profile` at
  `at` with one stamp, but for those that it holds, and take them out of
  the inbox."
  [profile ids at]
  (let [queued (crdt.list/appended profile [:queue] (queue-entries profile)
                                   ids)
        out    (mapcat #(profile.impl/out-of-inbox profile %)
                       (map (comp last first) queued))]
    (profile.impl/written profile (concat queued out) at)))

(defn play-next
  "Put the episode `id` right after the current episode of `profile` at `at`,
  moving it there when it's queued, or to the front of an empty queue."
  [profile id at]
  (if (= id (current profile))
    profile
    (let [[first-id second-id] (remove #{id} (queue profile))
          rank (crdt.list/rank-between (queue-entries profile)
                                       first-id
                                       second-id)]
      (if (queued? profile id)
        (crdt/write profile [:queue-moves id]
                    (constantly {:rank rank :removed? false})
                    at)
        (place profile id rank at)))))

(defn dequeue
  "Take the episode `id` out of the queue of `profile` at `at`, leaving a
  tombstone, so that it outlives an older addition on another device."
  [profile id at]
  (if (queued? profile id)
    (crdt/write-each profile
                     (removed-from profile [:queue id] [:queue-moves id])
                     at)
    profile))

(defn dequeue-all
  "Take the episodes `ids` out of the queue of `profile` at `at` with one
  stamp, leaving tombstones, e.g. (queue profile) to clear it."
  [profile ids at]
  (profile.impl/written profile
                        (mapcat #(removed-from profile [:queue %]
                                               [:queue-moves %])
                                (filter #(queued? profile %) ids))
                        at))

(defn move
  "Move the queued episode `id` to position `index` of the queue of `profile`
  at `at`. An index that isn't whole is rounded down, and NaN counts as 0."
  [profile id index at]
  (if-not (queued? profile id)
    profile
    (let [rank (crdt.list/rank-at (queue-entries profile) id index)]
      ;; the place is an entry of its own, so the move never brings back an
      ;; episode that another device took out of the queue meanwhile
      (crdt/write profile
                  [:queue-moves id]
                  (constantly {:rank rank :removed? false})
                  at))))

(defn advance
  "Drop the current episode from the queue of `profile` at `at`, so that the
  next one is current."
  [profile at]
  (if-let [id (current profile)]
    (dequeue profile id at)
    profile))

(defn playlist
  "The episode ids in the playlist `name` of `profile`, in order. A playlist
  is kept beside the queue, so to play it, queue its episodes."
  [profile name]
  (mapv key (crdt.list/ranked (playlist-entries profile name))))

(defn playlists
  "The episode ids of each playlist of `profile`, by name, leaving out the
  playlists without episodes."
  [profile]
  (into {} (for [name  (keys (:playlists profile))
                 :let  [ids (playlist profile name)]
                 :when (seq ids)]
             [name ids])))

(defn add-to-playlist
  "Put the episode `id` at the end of the playlist `name` of `profile` at
  `at`, unless the playlist holds it."
  [profile name id at]
  (crdt.list/append profile
                    [:playlists name]
                    (playlist-entries profile name)
                    [id]
                    at))

(defn remove-from-playlist
  "Take the episode `id` out of the playlist `name` of `profile` at `at`,
  leaving a tombstone."
  [profile name id at]
  (if (crdt/live? (get-in profile [:playlists name id]))
    (crdt/write-each profile
                     (removed-from profile
                                   [:playlists name id]
                                   [:playlist-moves name id])
                     at)
    profile))

(defn move-in-playlist
  "Move the episode `id` to position `index` of the playlist `name` of
  `profile` at `at`."
  [profile name id index at]
  (let [entries (playlist-entries profile name)]
    (if (crdt/live? (get entries id))
      (let [rank (crdt.list/rank-at entries id index)]
        (crdt/write profile
                    [:playlist-moves name id]
                    (constantly {:rank rank :removed? false})
                    at))
      profile)))

(defn rename-playlist
  "Move the episodes of the playlist `from` of `profile` to the end of the
  playlist `to` at `at`, or delete the playlist when `to` is nil.

  The episodes keep their order, and an episode that both playlists hold
  stays where `to` has it."
  [profile from to at]
  (let [ids (playlist profile from)]
    (if (or (empty? ids) (= from to))
      profile
      (let [removed  (mapcat #(removed-from profile
                                            [:playlists from %]
                                            [:playlist-moves from %])
                             ids)
            appended (when to
                       (crdt.list/appended profile
                                           [:playlists to]
                                           (playlist-entries profile to)
                                           ids))]
        (crdt/write-each profile (concat removed appended) at)))))

(defn to-list-feed
  "The playlist `name` of `profile` as a list feed of the podcast namespace,
  with the `opts` below.

  It's a channel of the medium podcastL, whose remote items point to the
  episodes in order. The `opts` give what a feed needs and a playlist
  lacks:

  - :url, where the feed is published, which gives its podcast:guid
  - :link, a page about the list, the URL by default, which RSS 2.0
    requires
  - :description, the name by default
  - :medium, another medium, such as mixed

  The URLs of the feeds go out without credentials."
  ([profile name]
   (to-list-feed profile name {}))
  ([profile name {:keys [url link description medium]
                  :or   {medium "podcastL"}
                  :as   opts}]
   {:pre [(map? opts)]}
   (let [subs        (profile/subscriptions profile)
         remote-item (fn [[feed-guid item-guid]]
                       (let [subscribed (get-in subs [feed-guid :url])
                             [feed-url] (url/split-credentials subscribed)]
                         (cond-> {:feed-guid feed-guid :item-guid item-guid}
                           feed-url (assoc :feed-url feed-url))))]
     (cond-> {:rss/version          "2.0"
              :rss/title            name
              :rss/description      (or description name)
              :podcast/medium       medium
              :podcast/remote-items (mapv remote-item (playlist profile name))}
       (or link url) (assoc :rss/link (or link url))
       url           (assoc :podcast/guid (guid/from-url url))))))

(defn from-list-feed
  "Add the episodes of the list feed `feed` to the end of the playlist
  `name` of `profile` at `at`, in the order of the feed."
  [profile name feed at]
  (crdt.list/append profile
                    [:playlists name]
                    (playlist-entries profile name)
                    (for [{:keys [feed-guid item-guid]}
                          (:podcast/remote-items feed)
                          :when (and feed-guid item-guid)]
                      [feed-guid item-guid])
                    at))

(defn- joined-at
  "When each of the new episodes `ids` of the parse `feed`, oldest first,
  joins a profile at `at`, by id: the episode's date of publication, but
  at the latest `at` for the newest episode and a millisecond earlier for
  each one before it."
  [feed ids at]
  (let [dates (into {} (for [item (:rss/items feed)
                             :let [date (:rss/pub-date item)]
                             :when (inst? date)]
                         [(guid/item-guid item) date]))]
    (into {} (map-indexed (fn [i [_ item-guid :as id]]
                            ;; a millisecond apart, so that episodes
                            ;; without a date keep their order
                            (let [latest (time/->inst (- (inst-ms at) i))
                                  date   (get dates item-guid latest)]
                              [id (min-key inst-ms date latest)]))
                          (reverse ids)))))

(defn- after-current
  "The writes that put the `ids` in order right after the current episode
  of `profile`, or at the front of an empty queue, but for those that it
  holds."
  [profile ids]
  (let [entries (queue-entries profile)
        fresh   (remove #(crdt/live? (get entries %)) (distinct ids))
        [a b]   (take 2 (queue profile))
        start   (crdt.list/rank-between entries a b)
        end     (let [r (some-> b entries :rank)]
                  (when (and r (pos? (compare r start))) r))
        ranks   (reductions (fn [prev _] (rank/between prev end))
                            start
                            (rest fresh))]
    (map (fn [id r]
           [[:queue id] (constantly {:rank r :removed? false})])
         fresh
         ranks)))

(defn- joining
  "The writes that put the new episodes `ids` of a feed, oldest first, into
  the queue or the inbox of `profile`, by the `settings` of its
  subscription, as refresh describes."
  [profile ids {:keys [auto-queue? auto-queue-max inbox?] :as settings}]
  (let [n        (count ids)
        cut      (if (and auto-queue? (nat-int? auto-queue-max))
                   (max 0 (- n auto-queue-max))
                   (if auto-queue? 0 n))
        [left queued] (split-at cut ids)]
    (concat (case auto-queue?
              (nil false) []
              :next       (after-current profile queued)
              (crdt.list/appended profile [:queue] (queue-entries profile)
                                  queued))
            (when inbox?
              (for [id left]
                [[:inbox id] (constantly {:removed? false})])))))

(defn refresh
  "Note in `profile` at `at` a new parse `after` of the feed at `url`, which
  was `before`, with the `opts` of feed/new-episodes and :join-pred. The
  result is a map of the new :profile and the episode ids that
  feed/new-episodes gives: those :added, :backfilled and :renamed.

  The new episodes join the queue with :auto-queue? in the settings of a
  live subscription, at its end or right after the current episode, the
  newest :auto-queue-max of them when it's set. The others join the inbox
  with :inbox?. With :join-pred, a predicate of the feed and an item, e.g.
  of criteria/where, only the episodes that meet it join either. An
  episode that's already in the profile stays as the listener left it. A
  renamed episode takes its state along, as profile/rename-episode moves
  it, written by this device for the listener."
  ([profile url before after at]
   (refresh profile url before after at {}))
  ([profile url before after at {:keys [join-pred] :as opts}]
   {:pre [(map? opts)]}
   (let [feed-guid (guid/feed-key after url)
         {:keys [added backfilled renamed]}
         (feed/new-episodes url before after opts)

         settings  (when (profile/subscribed? profile feed-guid)
                     (profile/settings profile feed-guid))

         ;; another device can find an episode first
         known?    #(or (profile/played? profile %)
                        (contains? (:queue profile) %)
                        (contains? (:inbox profile) %))
         items     (when join-pred
                     (into {}
                           (map (juxt guid/item-guid identity))
                           (:rss/items after)))
         joins?    (fn [[_ item-guid]]
                     (or (nil? join-pred)
                         (join-pred after (get items item-guid))))
         unknown   (filter joins? (remove known? added))
         writes    (joining profile unknown settings)
         ;; the feed is the author of what joins, at the episode's date of
         ;; publication but never after at, so that a device that finds an
         ;; episode late never undoes what the listener did with it
         stamps    (joined-at after unknown at)
         write     (fn [s [[_ id :as path] f]]
                     (crdt/write s path f (stamps id)))
         author    (profile.impl/feed-author feed-guid)
         joined    (crdt/as-device profile author #(reduce write % writes))
         rename    (fn [p [old-id new-id]]
                     (profile/rename-episode p old-id new-id at))]
     {:profile    (reduce rename joined renamed)
      :added      added
      :backfilled backfilled
      :renamed    renamed})))

(comment
  (def sample
    (-> (profile/make "laptop")
        (enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
        (add-to-playlist "Road trip" ["feed-1" "ep-2"]
                         #inst "2026-01-01T01:00:00Z")))

  (queue sample)
  (playlists sample)
  (to-list-feed sample "Road trip" {:url "https://example.org/road-trip.xml"})
  #_.)
