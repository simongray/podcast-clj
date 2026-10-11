(ns dk.simongray.podcast.profile
  "The listener's profile, which syncs between their devices, as plain
  data with pure functions over it.

  A profile is one map:

      {:device         \"8f1c9e2a-…\"   ; this device's id
       :clock          #inst \"…\"      ; the latest stamp written or merged
       :subscriptions  {feed-guid {:url :title :added-at :guid :removed?}}
       :remote-items   {id {:feed-url :removed?}}  ; episodes of other shows
       :settings       {feed-guid {:skip-intro {:value 20} ...}}
       :tags           {feed-guid {\"Tech\" {:removed? false}}}
       :lists          {url {:title :folder :removed?}}  ; followed OPML lists
       :episodes       {id {:position :duration :reported-at}}
       :played         {id {:played? true :played-at}}
       :queue          {id {:rank \"V\" :removed? false}}
       :queue-moves    {id {:rank \"W\" :removed? false}}  ; moved ranks
       :playlists      {name {id {:rank \"V\" :removed? false}}}
       :playlist-moves {name {id {:rank \"W\" :removed? false}}}
       :inbox          {id {:removed? false}}
       :stars          {id {:removed? false}}
       :bookmarks      {id {754 {:title :removed?}}}
       :clips          {id {754 {:duration :title :removed?}}}
       :ratings        {id {:rating 4 :worst 1 :best 5 :removed?}}
       :filters        {name {:criteria {:where {:status :unplayed}} :removed?}}
       :listened       {feed-guid {\"2026-09\" {device-id {:seconds 5400.0}}}}
       :preferences    {:skip-forward {:value 30} ...}
       :devices        {device-id {:name :platform ... :seen {…}}}
       :retired        {device-id {}}}

  A profile is a CRDT of dk.simongray.mind-meld: each write takes the time
  it happens at, and stamps what it changed with that time and the :device
  of the profile. A feed is identified by its key, the guid that its first
  fetch settled on, and an episode by its id, [feed-guid item-guid].

  This namespace has the subscriptions, their settings, the preferences
  and the progress of the episodes. The other parts have namespaces of
  their own:

  - profile.queue, the queue, the inbox and the playlists
  - profile.marks, the stars, bookmarks, clips and ratings
  - profile.opml, the followed lists, and the subscriptions as OPML
  - profile.player, the events of a player"
  (:refer-clojure :exclude [merge])
  (:require [dk.simongray.mind-meld :as crdt]
            [dk.simongray.mind-meld.list :as crdt.list]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.util.time :as time]))

(defn make
  "A fresh profile for the device `device`, a stable id that tells the
  listener's devices apart, made once per installation, e.g.
  (str (random-uuid))."
  [device]
  {:device device :subscriptions {} :episodes {} :queue {}})

(def ^:no-doc collections
  "The schema of a profile for dk.simongray.mind-meld: the :depth of the
  entries of each collection, whether its tombstones can be dropped, the
  key of the listening counts, which only grow, and which collections are
  ordered."
  {:subscriptions  {:depth 1 :compact? true}
   :remote-items   {:depth 1 :compact? true}
   :settings       {:depth 2}
   :tags           {:depth 2 :compact? true}
   :lists          {:depth 1 :compact? true}
   :episodes       {:depth 1}
   :played         {:depth 1}
   :queue          {:depth 1 :compact? true :ranked? true}
   :queue-moves    {:depth 1 :compact? true :ranked? true}
   :playlists      {:depth 2 :compact? true :ranked? true}
   :playlist-moves {:depth 2 :compact? true :ranked? true}
   :inbox          {:depth 1 :compact? true}
   :stars          {:depth 1 :compact? true}
   :bookmarks      {:depth 2 :compact? true}
   :clips          {:depth 2 :compact? true}
   :ratings        {:depth 1 :compact? true}
   :filters        {:depth 1 :compact? true}
   :listened       {:depth 3 :count-key :seconds}
   :preferences    {:depth 1}})

(def default-settings
  "The settings of a subscription until the listener changes them:

  - :auto-queue?, whether new episodes join the queue
  - :inbox?, whether they join the inbox instead
  - :skip-intro and :skip-outro, the seconds to skip at the start and the
    end
  - :folder, the folder that the show is in"
  {:auto-queue? false :inbox? false :skip-intro 0 :skip-outro 0 :folder nil})

(defn subscribed?
  "Whether `profile` holds a live subscription to `feed-guid`."
  [profile feed-guid]
  (let [sub (get-in profile [:subscriptions feed-guid])]
    (crdt/live? sub)))

(defn subscriptions
  "The live subscriptions of `profile`, by feed guid."
  [profile]
  (into {} (remove (comp :removed? val)) (:subscriptions profile)))

(defn ^:no-doc by-url
  "A map from the normalized URL of each subscription of `profile` to its
  feed guid, removed subscriptions included.

  Where subscriptions share a URL, a live one wins over a removed one, and
  then the one that changed last."
  [profile]
  (->> (:subscriptions profile)
       (filter (comp :url val))
       (sort-by (fn [[g {:keys [removed? updated-at]}]]
                  [(if removed? 0 1)
                   (or (some-> updated-at inst-ms) 0)
                   (str g)]))
       (into {} (map (fn [[g e]] [(guid/normal-url (:url e)) g])))))

(defn takeover
  "Check whether subscribing `profile` to the feed at `url`, under the guid
  `feed-guid` that the feed declares, would replace the subscription of
  another feed. It gives the diagnostic :fetch/guid-taken if so, and nil
  if not.

  A feed can declare any guid, so check a new feed before subscribing to
  it. The guid can be the key of a subscription, or the :guid that the
  feed of one gives itself now. Two forms of one URL, e.g. with http and
  https, count as the same feed."
  [profile feed-guid url]
  (let [[k sub] (or (when (subscribed? profile feed-guid)
                      [feed-guid (get-in profile [:subscriptions feed-guid])])
                    (first (filter (fn [[_ s]]
                                     (and (crdt/live? s)
                                          (= feed-guid (:guid s))))
                                   (:subscriptions profile))))
        other   (:url sub)]
    (when (and sub (not= (guid/normal-url other) (guid/normal-url url)))
      (diagnostics/describe {:id        :fetch/guid-taken
                             :url       url
                             :feed-guid k
                             :other-url other}))))

(defn subscribe
  "Subscribe `profile` to the feed `feed-guid` at `at`, with the :url and
  :title of the feed in `details`, and its :guid when the feed was fetched
  or a list named it, which settles the key.

  The settings of an earlier subscription to the feed still hold. For a new
  subscription, :added-at can give an earlier time of subscribing, e.g.
  from the OPML of another app."
  [profile feed-guid {:keys [url title added-at guid] :as details} at]
  (let [known? (subscribed? profile feed-guid)]
    (crdt/write profile [:subscriptions feed-guid]
                (fn [sub]
                  (cond-> (assoc sub
                                 :url      url
                                 :title    title
                                 :removed? false
                                 :added-at (if known?
                                             (:added-at sub)
                                             (time/date (or added-at at))))
                    guid (assoc :guid guid)))
                at)))

(defn unsubscribe
  "Unsubscribe `profile` from `feed-guid` at `at`, leaving a tombstone even
  where no subscription was known, so that it outlives an older
  subscription on another device."
  [profile feed-guid at]
  (crdt/write profile [:subscriptions feed-guid] #(assoc % :removed? true) at))

(defn ^:no-doc feed-author
  "The author of what the feed `feed-guid` brings about in a profile, such as
  its new episodes in the queue, as a device id that no real device has."
  [feed-guid]
  (str "feed:" feed-guid))

(defn- as-the-feed
  "The `profile` with the live subscription `feed-guid` changed by `f`, as
  a write of the feed itself."
  [profile feed-guid f]
  (let [sub   (get-in profile [:subscriptions feed-guid])
        ;; stamped just after the last write to the subscription, so that it
        ;; never undoes what the listener did on another device meanwhile,
        ;; e.g. an unsubscription, and two devices write the same
        write (fn [p]
                (let [after (time/->inst (inc (crdt/stamp-ms sub)))]
                  (crdt/write p [:subscriptions feed-guid]
                              (constantly (f sub))
                              after)))]
    (cond-> profile
      (crdt/live? sub) (crdt/as-device (feed-author feed-guid) write))))

(defn relocate
  "Note in `profile` that the feed `feed-guid` moved to the :url of
  `details`, with the :title it has there, e.g. after a permanent
  redirect. Nothing changes without a live subscription."
  [profile feed-guid {:keys [url title] :as details}]
  (as-the-feed profile feed-guid #(assoc % :url url :title title)))

(defn settle
  "Note in `profile` that the feed of the subscription `feed-guid` gives
  itself `guid`, which settles its key. Nothing changes without a live
  subscription, or when it notes that guid already.

  A first fetch moves a subscription without a :guid to the guid that its
  feed gives itself, and one with a :guid keeps its key. A later guid is
  an alias that takeover reads."
  [profile feed-guid guid]
  (if (= guid (get-in profile [:subscriptions feed-guid :guid]))
    profile
    (as-the-feed profile feed-guid #(assoc % :guid guid))))

(defn- values
  [entries]
  (into {} (map (fn [[k e]] [k (:value e)])) entries))

(declare tags)

(defn rekey
  "The `profile` with the subscription `feed-guid` moved to `new-guid` at
  `at`, along with its settings and tags, leaving a tombstone, e.g. when a
  feed declares a guid other than that of its URL.

  Nothing changes when the guids are the same, or when there's no live
  subscription."
  [profile feed-guid new-guid at]
  (if (or (= feed-guid new-guid) (not (subscribed? profile feed-guid)))
    profile
    (let [sub        (get-in profile [:subscriptions feed-guid])
          kept       (select-keys sub [:url :title :added-at :removed?])
          moved      [[[:subscriptions new-guid] (constantly kept)]
                      [[:subscriptions feed-guid] #(assoc % :removed? true)]]
          old        (values (get-in profile [:settings feed-guid]))
          configured (for [[k v] old]
                       [[:settings new-guid k] (constantly {:value v})])
          retagged   (for [t (tags profile feed-guid)]
                       [[:tags new-guid t] (constantly {:removed? false})])]
      (crdt/write-each profile (concat moved configured retagged) at))))

(defn rename-episode
  "The `profile` with the state of the episode `old-id` under `new-id` at
  `at`, e.g. after a host rewrote the guids of its feed.

  The places in the queue and the playlists, the inbox, the marks and a
  remote item move, leaving tombstones, while the progress and the played
  mark are copied, since they're never dropped. Nothing is written where
  `new-id` has an entry already, e.g. from a device that renamed it
  first."
  [profile old-id new-id at]
  (let [moves  (concat
                (for [c [:queue :queue-moves :inbox :stars :ratings
                         :remote-items]]
                  [[c old-id] [c new-id]])
                (for [c [:bookmarks :clips]
                      k (keys (get-in profile [c old-id]))]
                  [[c old-id k] [c new-id k]])
                (for [c    [:playlists :playlist-moves]
                      name (keys (get profile c))]
                  [[c name old-id] [c name new-id]]))
        copies (for [c [:episodes :played]]
                 [[c old-id] [c new-id]])
        copied (fn [[from to]]
                 (let [e (get-in profile from)]
                   (when (and e (nil? (get-in profile to)))
                     [[to (constantly e)]])))
        moved  (fn [[from :as paths]]
                 (when (crdt/live? (get-in profile from))
                   (conj (vec (copied paths))
                         [from #(assoc % :removed? true)])))
        writes (concat (mapcat moved moves) (mapcat copied copies))]
    (if (or (= old-id new-id) (empty? writes))
      profile
      (crdt/write-each profile writes at))))

;; podcast:remoteItem names an episode of another feed by its feedGuid,
;; itemGuid and feedUrl
(defn add-remote-item
  "The `profile` with the episode `id` of a show that it isn't subscribed
  to, whose feed is at the :feed-url of `details`, at `at`. It's an
  episode that the listener took from a search, a link or another show,
  e.g. to queue it."
  [profile id {:keys [feed-url] :as details} at]
  (crdt/write profile [:remote-items id]
              #(assoc % :feed-url feed-url :removed? false)
              at))

(defn remove-remote-item
  "The `profile` without the remote item `id` at `at`, leaving a
  tombstone."
  [profile id at]
  (crdt/write profile [:remote-items id] #(assoc % :removed? true) at))

(defn remote-items
  "The live remote items of `profile`, by episode id, each with the
  :feed-url of its show."
  [profile]
  (into {} (remove (comp :removed? val)) (:remote-items profile)))

(defn settings
  "The settings of the subscription `feed-guid` in `profile`, over the
  defaults."
  [profile feed-guid]
  (clojure.core/merge default-settings
                      (values (get-in profile [:settings feed-guid]))))

(defn configure
  "Change the `settings` of the subscription `feed-guid` in `profile` at
  `at`.

  The settings are the keys of default-settings, and these:

  - :rate, the playback rate of this show
  - :video?, whether to watch the video of its episodes that offer audio
    and video
  - :downloads, its rule for dk.simongray.podcast.downloads"
  [profile feed-guid settings at]
  (if (get-in profile [:subscriptions feed-guid])
    ;; each setting is an entry of its own, so two devices that change
    ;; different settings both keep their change
    (crdt/write-each profile
                     (for [[k v] settings]
                       [[:settings feed-guid k] (constantly {:value v})])
                     at)
    profile))

(def default-preferences
  "How the listener likes to listen until they change it: the seconds that
  a skip jumps forward and back, the playback rate, and :video?, whether
  to watch the video of an episode that offers audio and video."
  {:skip-forward 30 :skip-back 15 :rate 1.0 :video? false})

(defn preferences
  "The listener's preferences in `profile`, over the defaults."
  [profile]
  (clojure.core/merge default-preferences (values (:preferences profile))))

(defn prefer
  "Change the listener's `preferences` in `profile` at `at`, with the keys of
  default-preferences, and :downloads, the listener's rules for
  dk.simongray.podcast.downloads."
  [profile preferences at]
  (crdt/write-each profile
                   (for [[k v] preferences]
                     [[:preferences k] (constantly {:value v})])
                   at))

(defn playback-rate
  "The playback rate for the feed `feed-guid` in `profile`: its own setting,
  or else the listener's preference."
  [profile feed-guid]
  (or (:rate (settings profile feed-guid)) (:rate (preferences profile))))

(defn prefers-video?
  "Whether the listener watches the video of an episode of the feed
  `feed-guid` in `profile` that offers audio and video: its own setting,
  or else the listener's preference."
  [profile feed-guid]
  (let [own (:video? (settings profile feed-guid))]
    (if (some? own)
      own
      (:video? (preferences profile)))))

(defn folders
  "The feed guids of the live subscriptions of `profile` by folder, in the
  order of their titles, with the shows in no folder under nil."
  [profile]
  (let [add (fn [m [feed-guid _]]
              (let [folder (:folder (settings profile feed-guid))]
                (update m folder (fnil conj []) feed-guid)))]
    (reduce add {} (sort-by (comp str :title val) (subscriptions profile)))))

(defn rename-folder
  "Move the shows of `profile` in the folder `from` to the folder `to` at
  `at`, or out of all folders when `to` is nil."
  [profile from to at]
  (if-let [feed-guids (get (folders profile) from)]
    (crdt/write-each profile
                     (for [g feed-guids]
                       [[:settings g :folder] (constantly {:value to})])
                     at)
    profile))

(defn tags
  "The tags that the listener gave the show `feed-guid` in `profile`, as a
  sorted set. Unlike folders, a show can have many tags."
  [profile feed-guid]
  (into (sorted-set)
        (keep (fn [[t e]] (when-not (:removed? e) t)))
        (get-in profile [:tags feed-guid])))

(defn tagged
  "The feed guids of the live subscriptions of `profile` by tag, each tag's
  shows in the order of their titles."
  [profile]
  (let [add (fn [m [feed-guid _]]
              (reduce #(update %1 %2 (fnil conj []) feed-guid)
                      m
                      (tags profile feed-guid)))]
    (reduce add {} (sort-by (comp str :title val) (subscriptions profile)))))

(defn tag
  "Give the show `feed-guid` in `profile` the tag `t`, a string, at `at`, when
  the profile has the subscription."
  [profile feed-guid t at]
  (if (and (get-in profile [:subscriptions feed-guid])
           (not (contains? (tags profile feed-guid) t)))
    (crdt/write profile [:tags feed-guid t] (constantly {:removed? false}) at)
    profile))

(defn untag
  "Take the tag `t` off the show `feed-guid` in `profile` at `at`, leaving a
  tombstone."
  [profile feed-guid t at]
  (if (contains? (tags profile feed-guid) t)
    (crdt/write profile [:tags feed-guid t] #(assoc % :removed? true) at)
    profile))

(defn rename-tag
  "Rename the tag `from` to `to` on the shows of `profile` at `at`, or take it
  off them when `to` is nil."
  [profile from to at]
  (if-let [feed-guids (get (tagged profile) from)]
    (let [writes (fn [g]
                   (cond-> [[[:tags g from] #(assoc % :removed? true)]]
                     to (conj [[:tags g to] (constantly {:removed? false})])))]
      (crdt/write-each profile (mapcat writes feed-guids) at))
    profile))

(defn filters
  "The saved filters of `profile` by name, as criteria for
  dk.simongray.podcast.criteria/episodes."
  [profile]
  (into {}
        (keep (fn [[name e]]
                (when-not (:removed? e)
                  [name (:criteria e)])))
        (:filters profile)))

(defn save-filter
  "Save the `criteria` of criteria/episodes in `profile` as the filter
  `name` at `at`, so that the other devices have it too.

  The criteria replace a filter of the same name, without :profile and
  :url, which are the context of one search. They must be data, since
  the profile is printed as EDN, so an order that's a function throws."
  [profile name criteria at]
  (let [criteria (dissoc criteria :profile :url)]
    (when (some fn? (tree-seq coll? seq criteria))
      (throw (ex-info "A saved filter holds data only, not functions"
                      {:name name :criteria criteria})))
    (crdt/write profile [:filters name]
                (constantly {:criteria criteria :removed? false})
                at)))

(defn remove-filter
  "Remove the saved filter `name` from `profile` at `at`, leaving a
  tombstone."
  [profile name at]
  (if (contains? (filters profile) name)
    (crdt/write profile [:filters name] #(assoc % :removed? true) at)
    profile))

(defn compact
  "Drop from `profile` at `at` the tombstones that every live device has
  merged, once they're older than the :keep-for of `opts`, as
  dk.simongray.mind-meld/compact does.

  Played marks and listening counts are never dropped, and the tombstones
  of unsubscriptions stay while the listener follows a list."
  ([profile at]
   (compact profile at {}))
  ([profile at opts]
   ;; a list can name an unsubscribed show again
   (let [follows? (some crdt/live? (vals (:lists profile)))
         schema   (cond-> collections
                    follows? (assoc-in [:subscriptions :compact?] false))]
     (crdt/compact schema profile at opts))))

(def played-threshold
  "How many seconds before the end of an episode count as having heard it,
  by default."
  30)

(defn episode-entry
  "The entry of the episode `id` in `profile` with its played mark, or nil.
  It has the stamp and the device of the later of the two."
  [profile id]
  ;; the position and the mark are entries of their own, so that a report
  ;; of the position from another device never undoes a mark
  (let [e (get-in profile [:episodes id])
        m (get-in profile [:played id])]
    (when (or e m)
      (clojure.core/merge e
                          (select-keys m [:played? :played-at])
                          (select-keys (max-key crdt/stamp-ms m e)
                                       [:updated-at :device])))))

(defn episode-entries
  "The entries of the episodes of `profile` by id, each with its played
  mark."
  [profile]
  (let [ids (distinct (concat (keys (:episodes profile))
                              (keys (:played profile))))]
    (zipmap ids (map (partial episode-entry profile) ids))))

(defn played?
  "Whether the episode `id` counts as played in `profile`."
  [profile id]
  (boolean (:played? (get-in profile [:played id]))))

(defn- heard-to-the-end?
  [position duration threshold]
  (and (number? duration) (pos? duration) (number? position)
       (>= position (- duration threshold))))

(defn ^:no-doc out-of-inbox
  "The writes that take the episode `id` out of the inbox of `profile`, if
  it's there."
  [profile id]
  (let [e (get-in profile [:inbox id])]
    (when (crdt/live? e)
      [[[:inbox id] #(assoc % :removed? true)]])))

(defn ^:no-doc marked-as
  "The writes that mark the episode `id` of `profile` as `played?` at `at`.
  A played episode leaves the inbox."
  [profile id played? at]
  (let [mark (if played?
               {:played? true :played-at (time/date at)}
               {:played? false})]
    (into [[[:played id] (constantly mark)]]
          (when played?
            (out-of-inbox profile id)))))

(defn progress
  "Record in `profile` that the listener is `position` seconds into the
  episode `id` at `at`, with its `duration` if known. An episode heard to
  within the :played-threshold of `opts` of its end, in seconds, counts as
  played and leaves the inbox."
  ([profile id position at]
   (progress profile id position nil at {}))
  ([profile id position duration at]
   (progress profile id position duration at {}))
  ([profile id position duration at opts]
   {:pre [(map? opts)]}
   (let [duration  (or duration (:duration (episode-entry profile id)))
         threshold (:played-threshold opts played-threshold)
         done?     (and (not (played? profile id))
                        (heard-to-the-end? position duration threshold))
         reported  #(cond-> (assoc % :position    position
                                     :reported-at (time/date at))
                      duration (assoc :duration duration))]
     (crdt/write-each profile
                      (concat [[[:episodes id] reported]]
                              (when done? (marked-as profile id true at)))
                      at))))

(defn mark-played
  "Mark the episode `id` in `profile` as played at `at`, which takes it out
  of the inbox."
  [profile id at]
  (crdt/write-each profile (marked-as profile id true at) at))

(defn mark-unplayed
  "Mark the episode `id` in `profile` as not played at `at`, with its
  position back at the start."
  [profile id at]
  (let [restarted #(assoc % :position 0 :reported-at (time/date at))]
    (crdt/write-each profile
                     (concat [[[:episodes id] restarted]]
                             (marked-as profile id false at))
                     at)))

(defn skip
  "Move `seconds` forward, or back when negative, in the episode `id` of
  `profile` at `at`, with the `opts` of progress. The position never goes
  before the start or past a known end, and a duration of zero, or one
  that isn't a finite number, counts as unknown."
  ([profile id seconds at]
   (skip profile id seconds at {}))
  ([profile id seconds at opts]
   {:pre [(map? opts)]}
   (let [{:keys [position duration]} (episode-entry profile id)
         finite?  #(and (number? %) (== % %) (not (infinite? %)))
         target   (+ (if (finite? position) position 0) seconds)
         ;; players report such durations before they know
         known?   (and (finite? duration) (pos? duration))
         target   (max 0 (if known? (min target duration) target))]
     (progress profile id target nil at opts))))

(defn listened
  "The seconds that the listener heard of each show in `profile`, by feed
  guid and month in UTC, e.g. {feed-guid {\"2026-09\" 5400.0}}.

  The counts of all the devices add up. Report the position at least
  every 30 seconds while an episode plays, since a longer gap can count as
  a pause."
  [profile]
  (let [total (fn [devices]
                (reduce + 0 (keep :seconds (vals devices))))]
    (update-vals (:listened profile)
                 (fn [months] (update-vals months total)))))

(defn resume-position
  "Where to start playing the episode `id` of `feed-guid` in `profile`: where
  the listener left it, or after the intro when it hasn't been started or
  was heard to the end."
  [profile feed-guid id]
  (let [{:keys [position played?]} (episode-entry profile id)]
    (if (and (number? position) (pos? position) (not played?))
      position
      (:skip-intro (settings profile feed-guid)))))

(defn outro-start
  "Where the outro of the episode `id` of `feed-guid` starts in `profile`,
  its skip-outro seconds before the end.

  Nil when the duration is unknown, the feed skips no outro, or the outro
  is as long as the episode."
  [profile feed-guid id]
  (let [duration (:duration (episode-entry profile id))
        outro    (:skip-outro (settings profile feed-guid))]
    (when (and (number? duration) (number? outro) (< 0 outro duration))
      (- duration outro))))

(defn history
  "The episode ids in `profile`, most recently played or touched first: an
  episode heard again comes first again."
  [profile]
  (let [touched (fn [[_ e]]
                  (or (some-> (crdt/later-inst (:played-at e) (:updated-at e))
                              inst-ms)
                      0))]
    (->> (episode-entries profile)
         (sort-by #(- (touched %)))
         (map key))))

(defn merge
  "Reconcile the profiles `a` and `b` of a listener's devices, as
  dk.simongray.mind-meld/merge does: by the later entry, and of
  two listening counts of one device, by the higher."
  [a b]
  (crdt/merge collections a b))

(defn- key-problem
  "A description of the first key of `profile` of the wrong type for its
  collection. Lists and tags have strings as keys, and bookmarks and clips
  whole seconds."
  [profile]
  (let [whole-second? #(and (integer? %) (<= 0 %))]
    (or (some (fn [url]
                (when-not (string? url)
                  (str "the list at " (pr-str url) " is not at a URL")))
              (keys (:lists profile)))
        (some (fn [[what k]]
                (some (fn [[id entries]]
                        (when-not (every? whole-second? (keys entries))
                          (str "a " what " of " (pr-str id)
                               " is not at a whole number of seconds")))
                      (get profile k)))
              [["bookmark" :bookmarks] ["clip" :clips]])
        (some (fn [[feed-guid ts]]
                (when-not (every? string? (keys ts))
                  (str "a tag of " (pr-str feed-guid) " is not a string")))
              (:tags profile)))))

(defn- mark-problem
  "A description of the first live rating of `profile` outside its scale,
  or of the first live clip whose duration isn't a number of seconds."
  [profile]
  (or (some (fn [[id {:keys [rating removed?] :as e}]]
              (when-not (or removed?
                            (profile.marks/rating? rating
                                                   (profile.marks/scale-of e)))
                (str "the rating of " (pr-str id) " is " (pr-str rating)
                     ", not a number of the scale "
                     (pr-str (profile.marks/scale-of e)))))
            (:ratings profile))
      (some (fn [[id entries]]
              (some (fn [[start {:keys [duration removed?]}]]
                      (when-not (or removed?
                                    (and (number? duration) (pos? duration)))
                        (str "the clip of " (pr-str id) " at " start
                             " lasts " (pr-str duration)
                             ", not a number of seconds")))
                    entries))
            (:clips profile))))

(defn- count-problem
  [profile]
  (some (fn [[feed-guid months]]
          (let [counts (mapcat vals (vals months))]
            (when-not (every? #(number? (:seconds % 0)) counts)
              (str "the listening of " (pr-str feed-guid)
                   " is not counted in seconds"))))
        (:listened profile)))

(defn- criteria-problem
  [profile]
  (some (fn [[name {:keys [criteria]}]]
          (when-not (or (nil? criteria) (map? criteria))
            (str "the criteria of the filter " (pr-str name) " are not a map")))
        (:filters profile)))

(defn- remote-item-problem
  [profile]
  (some (fn [[id {:keys [feed-url removed?]}]]
          (when-not (or removed? (string? feed-url))
            (str "the remote item " (pr-str id) " names no feed URL")))
        (:remote-items profile)))

(def ^:no-doc checks
  "The checks of a profile after those of dk.simongray.mind-meld, in order.
  Each takes a profile that is a map and returns a description of the
  first problem of its kind, or nil, and relies on the checks before it."
  [key-problem mark-problem count-problem criteria-problem
   remote-item-problem])

;; a merge spreads the values of another device to every device, where a
;; bad value makes a function throw, or two devices disagree
(defn problem
  "A description of what in `profile` breaks the rules below, or nil.

  The rules are those of dk.simongray.mind-meld/problem, and:

  - Each rank in the queue and in a playlist is a rank.
  - A list is at a URL, a tag is a string, and a bookmark and a clip are
    at a whole number of seconds.
  - A rating is a number of its scale, and a clip lasts a number of
    seconds.
  - A listening count is a number of seconds.
  - The criteria of a saved filter are a map.
  - A remote item names the URL of its feed.

  Saved filters from a later version are fine here, even when this
  version doesn't understand them."
  [profile]
  (or (crdt/problem collections profile)
      (crdt.list/problem collections profile)
      (some #(% profile) checks)))

(comment
  (def sample
    (-> (make "laptop")
        (subscribe "feed-1"
                   {:url "https://example.org/feed.xml" :title "A show"}
                   #inst "2026-01-01")
        (configure "feed-1" {:folder "Tech" :skip-outro 30} #inst "2026-01-01")
        (tag "feed-1" "news" #inst "2026-01-01")
        (progress ["feed-1" "ep-1"] 120 3600 #inst "2026-01-01T02:00:00Z")))

  (folders sample)
  (tagged sample)
  (episode-entries sample)

  ;; nil for a profile that keeps the rules, and the first rule that it breaks
  (problem sample)
  (problem (assoc-in sample [:queue ["feed-1" "ep-1"] :rank] 5))
  #_.)

#?(:clj
   (comment
     (-> (make (str (random-uuid)))
         (crdt/identify {:name "laptop"} #inst "2026-01-01")
         (subscribe "feed-1" {:url "https://a/rss" :title "A"}
                    #inst "2026-01-01")
         (progress ["feed-1" "ep-1"] 120 3600 #inst "2026-01-01T02:00:00Z"))
     (crdt/after *1 #inst "2026-01-01")
     #_.))
