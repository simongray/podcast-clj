(ns dk.simongray.podcast.profile
  "The listener's profile, which syncs between their devices, as plain
  data with pure functions over it.

  A profile is one map:

      {:device         \"8f1c9e2a-…\"   ; this device's id
       :clock          #inst \"…\"      ; the latest stamp written or merged
       :subscriptions  {feed-guid {:url :title :added-at :removed?}}
       :settings       {feed-guid {:skip-intro {:value 20} ...}}
       :tags           {feed-guid {\"Tech\" {:removed? false}}}
       :lists          {url {:title :folder :removed?}}  ; followed OPML lists
       :episodes       {id {:position :duration :reported-at}}
       :played         {id {:played? true :played-at}}
       :queue          {id {:rank \"V\" :removed? false}}
       :queue-moves    {id {:rank \"W\" :removed? false}}  ; where moves put them
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

  Each write takes the time it happens at, and stamps what it changed with
  that time and the :device of the profile. A profile is a CRDT of
  dk.simongray.podcast.util.crdt, so merge joins the profiles of a
  listener's devices, and compact drops the tombstones that every device
  has seen. A feed is identified by its
  podcast:guid, and an episode by its id, [feed-guid item-guid], as
  feed/episode-id gives it."
  (:refer-clojure :exclude [merge])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.rank :as rank]
            [dk.simongray.podcast.util.time :as time]))

(defn make
  "A fresh profile for the device `device`, a stable id that tells the
  listener's devices apart, made once per installation, e.g.
  (str (random-uuid))."
  [device]
  {:device device :subscriptions {} :episodes {} :queue {}})

(def ^:no-doc collections
  "The schema of a profile for dk.simongray.podcast.util.crdt: the
  :depth of the entries of each collection, whether compact drops its
  tombstones, and the key of the listening counts, which only grow."
  {:subscriptions  {:depth 1 :compact? true}
   :settings       {:depth 2}
   :tags           {:depth 2 :compact? true}
   :lists          {:depth 1 :compact? true}
   :episodes       {:depth 1}
   :played         {:depth 1}
   :queue          {:depth 1 :compact? true}
   :queue-moves    {:depth 1 :compact? true}
   :playlists      {:depth 2 :compact? true}
   :playlist-moves {:depth 2 :compact? true}
   :inbox          {:depth 1 :compact? true}
   :stars          {:depth 1 :compact? true}
   :bookmarks      {:depth 2 :compact? true}
   :clips          {:depth 2 :compact? true}
   :ratings        {:depth 1 :compact? true}
   :filters        {:depth 1 :compact? true}
   :listened       {:depth 3 :count :seconds}
   :preferences    {:depth 1}})

(def default-settings
  "The settings of a subscription until configure changes them:

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

(defn ^:no-doc normal-url
  "The `url` in lower case without credentials, scheme or trailing
  slashes, so that two forms of one feed or media URL are equal."
  [url]
  (-> (str url) http/split-credentials first guid/guid-name str/lower-case))

(defn ^:no-doc by-url
  "A map from the URL of each subscription of `profile`, as normal-url gives
  it, to its feed guid, removed subscriptions included.

  Where subscriptions share a URL, a live one wins over a removed one, and
  then the one that changed last."
  [profile]
  (->> (:subscriptions profile)
       (filter (comp :url val))
       (sort-by (fn [[g {:keys [removed? updated-at]}]]
                  [(if removed? 0 1) (or (some-> updated-at inst-ms) 0) (str g)]))
       (into {} (map (fn [[g e]] [(normal-url (:url e)) g])))))

(defn takeover
  "Check whether subscribing `profile` to the feed at `url`, under the guid
  `feed-guid` that the feed declares, would replace the subscription of
  another feed. It gives the diagnostic :fetch/guid-taken if so, and nil
  if not.

  A feed can declare any guid, so check a new feed with it before
  subscribe. Two forms of one URL, e.g. with http and https, count as the
  same feed."
  [profile feed-guid url]
  (when (subscribed? profile feed-guid)
    (let [other (get-in profile [:subscriptions feed-guid :url])]
      (when (not= (normal-url other) (normal-url url))
        (diagnostics/describe {:id :fetch/guid-taken :url url :feed-guid feed-guid :other-url other})))))

(defn subscribe
  "Subscribe `profile` to the feed `feed-guid` at `at`, with the :url and
  :title of the feed in `details`.

  The settings of an earlier subscription to the feed still hold. For a new
  subscription, :added-at can give an earlier time of subscribing, e.g.
  from the OPML of another app."
  [profile feed-guid {:keys [url title added-at] :as details} at]
  (crdt/write profile [:subscriptions feed-guid]
              (fn [sub]
                (assoc sub
                       :url url :title title :removed? false
                       :added-at (if (subscribed? profile feed-guid) (:added-at sub) (time/date (or added-at at)))))
              at))

(defn unsubscribe
  "Unsubscribe `profile` from `feed-guid` at `at`, leaving a tombstone even
  where no subscription was known, so that it outlives an older
  subscription on another device."
  [profile feed-guid at]
  (crdt/write profile [:subscriptions feed-guid] #(assoc % :removed? true) at))

(defn ^:no-doc feed-author
  "The author of what the feed `feed-guid` brings about in a profile, such as
  its new episodes in the queue, as crdt/as-device takes it. No device of
  podcast-clj has such a name."
  [feed-guid]
  (str "feed:" feed-guid))

(defn relocate
  "Note in `profile` that the feed `feed-guid` moved to the :url of
  `details`, with the :title it has there, e.g. after a permanent
  redirect.

  The feed is the author of the move, which is stamped just after the last
  write to the subscription. The move then never undoes what the listener
  did on another device meanwhile, e.g. an unsubscription. Nothing changes
  without a live subscription."
  [profile feed-guid {:keys [url title] :as details}]
  (let [sub (get-in profile [:subscriptions feed-guid])]
    (cond-> profile
      (crdt/live? sub) (crdt/as-device (feed-author feed-guid)
                                       #(crdt/write % [:subscriptions feed-guid]
                                                    (constantly (assoc sub :url url :title title))
                                                    (time/->inst (inc (crdt/stamp-ms sub))))))))

(defn- values
  [entries]
  (into {} (map (fn [[k e]] [k (:value e)])) entries))

(declare tags)

(defn rekey
  "The `profile` with the subscription `feed-guid` moved to `new-guid` at
  `at`, along with its settings and tags, leaving a tombstone.

  A list or another app names a show by the guid of its URL, but the feed
  can declare a guid of its own once it's fetched. Nothing changes when the
  guids are the same, or when there's no live subscription."
  [profile feed-guid new-guid at]
  (if (or (= feed-guid new-guid) (not (subscribed? profile feed-guid)))
    profile
    (let [sub (get-in profile [:subscriptions feed-guid])]
      (crdt/write-each profile
                       (concat [[[:subscriptions new-guid] (constantly (select-keys sub [:url :title :added-at :removed?]))]
                                [[:subscriptions feed-guid] #(assoc % :removed? true)]]
                               (for [[k v] (values (get-in profile [:settings feed-guid]))]
                                 [[:settings new-guid k] (constantly {:value v})])
                               (for [t (tags profile feed-guid)]
                                 [[:tags new-guid t] (constantly {:removed? false})]))
                       at))))

(defn settings
  "The settings of the subscription `feed-guid` in `profile`, over
  default-settings."
  [profile feed-guid]
  (clojure.core/merge default-settings (values (get-in profile [:settings feed-guid]))))

(defn configure
  "Change the `settings` of the subscription `feed-guid` in `profile` at
  `at`.

  The settings are the keys of default-settings, and these:

  - :rate, the playback rate of this show
  - :downloads, its rule for dk.simongray.podcast.downloads

  Each is an entry of its own, so two devices that change different
  settings both keep their change."
  [profile feed-guid settings at]
  (if (get-in profile [:subscriptions feed-guid])
    (crdt/write-each profile (for [[k v] settings] [[:settings feed-guid k] (constantly {:value v})]) at)
    profile))

(def default-preferences
  "How the listener likes to listen until prefer changes it: the seconds
  that a skip jumps forward and back, and the playback rate."
  {:skip-forward 30 :skip-back 15 :rate 1.0})

(defn preferences
  "The listener's preferences in `profile`, over default-preferences."
  [profile]
  (clojure.core/merge default-preferences (values (:preferences profile))))

(defn prefer
  "Change the listener's `preferences` in `profile` at `at`, with the keys of
  default-preferences, and :downloads, the listener's rules for
  dk.simongray.podcast.downloads."
  [profile preferences at]
  (crdt/write-each profile (for [[k v] preferences] [[:preferences k] (constantly {:value v})]) at))

(defn playback-rate
  "The playback rate for the feed `feed-guid` in `profile`: its own setting,
  or else the listener's preference."
  [profile feed-guid]
  (or (:rate (settings profile feed-guid)) (:rate (preferences profile))))

(defn folders
  "The feed guids of the live subscriptions of `profile` by folder, in the
  order of their titles, with the shows in no folder under nil."
  [profile]
  (reduce (fn [m [feed-guid _]] (update m (:folder (settings profile feed-guid)) (fnil conj []) feed-guid))
          {}
          (sort-by (comp str :title val) (subscriptions profile))))

(defn rename-folder
  "Move the shows of `profile` in the folder `from` to the folder `to` at
  `at`, or out of all folders when `to` is nil."
  [profile from to at]
  (if-let [feed-guids (get (folders profile) from)]
    (crdt/write-each profile (for [g feed-guids] [[:settings g :folder] (constantly {:value to})]) at)
    profile))

(defn tags
  "The tags that the listener gave the show `feed-guid` in `profile`, as a
  sorted set. Unlike folders, a show can have many tags."
  [profile feed-guid]
  (into (sorted-set) (keep (fn [[t e]] (when-not (:removed? e) t))) (get-in profile [:tags feed-guid])))

(defn tagged
  "The feed guids of the live subscriptions of `profile` by tag, each tag's
  shows in the order of their titles."
  [profile]
  (reduce (fn [m [feed-guid _]] (reduce #(update %1 %2 (fnil conj []) feed-guid) m (tags profile feed-guid)))
          {}
          (sort-by (comp str :title val) (subscriptions profile))))

(defn tag
  "Give the show `feed-guid` in `profile` the tag `t`, a string, at `at`, when
  the profile has the subscription."
  [profile feed-guid t at]
  (if (and (get-in profile [:subscriptions feed-guid]) (not (contains? (tags profile feed-guid) t)))
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
    (crdt/write-each profile (for [g feed-guids
                                   w (cond-> [[[:tags g from] #(assoc % :removed? true)]]
                                       to (conj [[:tags g to] (constantly {:removed? false})]))]
                               w)
                     at)
    profile))

(defn filters
  "The saved filters of `profile` by name, as criteria for
  dk.simongray.podcast.search/episodes."
  [profile]
  (into {} (keep (fn [[name e]] (when-not (:removed? e) [name (:criteria e)]))) (:filters profile)))

(defn save-filter
  "Save the `criteria` of search/episodes in `profile` as the filter
  `name` at `at`, so that the other devices have it too.

  The criteria replace a filter of the same name, without :profile and
  :url, which are the context of one search. They must be data, since
  the profile is printed as EDN, so an order that's a function throws."
  [profile name criteria at]
  (let [criteria (dissoc criteria :profile :url)]
    (when (some fn? (tree-seq coll? seq criteria))
      (throw (ex-info "A saved filter holds data only, not functions" {:name name :criteria criteria})))
    (crdt/write profile [:filters name] (constantly {:criteria criteria :removed? false}) at)))

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
  dk.simongray.podcast.util.crdt/compact does.

  Played marks and listening counts are never dropped, and the tombstones
  of unsubscriptions stay while the listener follows a list, since a list
  can name the show again."
  ([profile at]
   (compact profile at {}))
  ([profile at opts]
   (let [follows? (some crdt/live? (vals (:lists profile)))]
     (crdt/compact (cond-> collections follows? (assoc-in [:subscriptions :compact?] false)) profile at opts))))

(def played-threshold
  "How many seconds before the end of an episode count as having heard it,
  unless :played-threshold in the options of progress, skip or step says
  otherwise."
  30)

(def listening-gap
  "How many seconds can pass between two reports of the position that are
  still one stretch of listening, unless :listening-gap in the options of
  step says otherwise."
  60)

(defn ^:no-doc played-mark
  "The played mark of the episode `id` in `profile`: its entry under :played,
  or the mark that an older version kept in the episode's entry, whichever
  is later."
  [profile id]
  (let [m (get-in profile [:played id])
        e (get-in profile [:episodes id])]
    (if (and (contains? e :played?) (> (crdt/stamp-ms e) (crdt/stamp-ms m)))
      (select-keys e [:played? :played-at :updated-at :device])
      m)))

(defn episode-entry
  "The entry of the episode `id` in `profile` with its played mark, or nil.

  The position and the mark are entries of their own, so that a report of
  the position from another device never undoes a mark. The entry has the
  stamp and the device of the later of the two."
  [profile id]
  (let [e (get-in profile [:episodes id])
        m (played-mark profile id)]
    (when (or e m)
      (clojure.core/merge (dissoc e :played? :played-at)
                          (select-keys m [:played? :played-at])
                          (select-keys (max-key crdt/stamp-ms m e) [:updated-at :device])))))

(defn episode-entries
  "The entries of the episodes of `profile` by id, as episode-entry gives
  them."
  [profile]
  (let [ids (distinct (concat (keys (:episodes profile)) (keys (:played profile))))]
    (zipmap ids (map (partial episode-entry profile) ids))))

(defn played?
  "Whether the episode `id` counts as played in `profile`."
  [profile id]
  (boolean (:played? (played-mark profile id))))

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

(defn ^:no-doc migrated
  "The writes that move a played mark that an older version kept in the
  entry of the episode `id` in `profile` to an entry of its own, if it's
  the later mark."
  [profile id]
  (let [e (get-in profile [:episodes id])]
    (when (contains? e :played?)
      (cond-> [[[:episodes id] #(dissoc % :played? :played-at)]]
        (> (crdt/stamp-ms e) (crdt/stamp-ms (get-in profile [:played id])))
        (conj [[:played id] (constantly (select-keys e [:played? :played-at]))])))))

(defn ^:no-doc marked-as
  "The writes that mark the episode `id` of `profile` as `played?` at `at`.
  A played episode leaves the inbox."
  [profile id played? at]
  (into [[[:played id] (constantly (if played? {:played? true :played-at (time/date at)} {:played? false}))]]
        (when played? (out-of-inbox profile id))))

(defn progress
  "Record in `profile` that the listener is `position` seconds into the
  episode `id` at `at`, with its `duration` if known and the `opts` below.

  An episode heard to within played-threshold seconds of its end counts
  as played and leaves the inbox. The `opts` can give another
  :played-threshold."
  ([profile id position at]
   (progress profile id position nil at {}))
  ([profile id position duration at]
   (progress profile id position duration at {}))
  ([profile id position duration at opts]
   {:pre [(map? opts)]}
   (let [duration (or duration (:duration (episode-entry profile id)))
         done?    (and (not (played? profile id))
                       (heard-to-the-end? position duration (:played-threshold opts played-threshold)))]
     (crdt/write-each profile
                      (concat (migrated profile id)
                              [[[:episodes id] #(cond-> (assoc % :position position :reported-at (time/date at))
                                                   duration (assoc :duration duration))]]
                              (when done? (marked-as profile id true at)))
                      at))))

(defn mark-played
  "Mark the episode `id` in `profile` as played at `at`, which takes it out
  of the inbox."
  [profile id at]
  (crdt/write-each profile (concat (migrated profile id) (marked-as profile id true at)) at))

(defn mark-unplayed
  "Mark the episode `id` in `profile` as not played at `at`, with its
  position back at the start."
  [profile id at]
  (crdt/write-each profile
                   (concat (migrated profile id)
                           [[[:episodes id] #(assoc % :position 0 :reported-at (time/date at))]]
                           (marked-as profile id false at))
                   at))

(defn skip
  "Move `seconds` forward, or back when negative, in the episode `id` of
  `profile` at `at`, with the `opts` of progress. The position never goes
  before the start or past a known end.

  A duration of zero, or one that isn't a finite number, counts as
  unknown, since players report those before they know. The new position
  is in the profile for the UI to seek to."
  ([profile id seconds at]
   (skip profile id seconds at {}))
  ([profile id seconds at opts]
   {:pre [(map? opts)]}
   (let [{:keys [position duration]} (episode-entry profile id)
         finite?  #(and (number? %) (== % %) (not (infinite? %)))
         target   (+ (if (finite? position) position 0) seconds)
         target   (max 0 (if (and (finite? duration) (pos? duration)) (min target duration) target))]
     (progress profile id target nil at opts))))

(defn- heard
  "The `profile` with the seconds heard of the episode `id` up to `position`
  at `at` added to this device's count. Nothing counts when the last
  report is more than `gap` seconds old.

  An older report means that the listener paused. The seconds are how far
  the position moved since the last report, at most three times the time
  since then, as a faster move is a seek. The count is by show and month,
  in UTC, and the last report is the episode's :reported-at, or else its
  stamp."
  [profile id position at gap]
  (let [{p0 :position :keys [reported-at updated-at]} (episode-entry profile id)
        t0      (or reported-at updated-at)
        seconds (when (and (number? position) (number? p0) (inst? t0))
                  (let [moved (- position p0)
                        spent (/ (- (inst-ms at) (inst-ms t0)) 1000.0)]
                    (when (and (pos? moved) (pos? spent) (<= spent gap))
                      (min moved (* 3 spent)))))]
    (if seconds
      (crdt/write profile [:listened (first id) (subs (time/format-iso8601 at) 0 7) (:device profile)]
                  #(update % :seconds (fnil + 0) seconds)
                  at)
      profile)))

(defn listened
  "The seconds that the listener heard of each show in `profile`, by feed
  guid and month in UTC, e.g. {feed-guid {\"2026-09\" 5400.0}}.

  The counts of all the devices add up. Since step counts at each
  :progress, report the position at least every 30 seconds while an
  episode plays (see listening-gap)."
  [profile]
  (update-vals (:listened profile)
               (fn [months] (update-vals months #(reduce + 0 (keep :seconds (vals %)))))))

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
  (->> (episode-entries profile)
       (sort-by (fn [[_ e]] (- (or (some-> (crdt/later-inst (:played-at e) (:updated-at e)) inst-ms) 0))))
       (map key)))

(defn starred?
  "Whether the episode `id` has a star in `profile`."
  [profile id]
  (let [e (get-in profile [:stars id])]
    (crdt/live? e)))

(defn starred
  "The starred episode ids of `profile`, the latest starred first."
  [profile]
  (crdt/live-by-stamp (:stars profile)))

(defn star
  "Star the episode `id` in `profile` at `at`, unless it has a star."
  [profile id at]
  (if (starred? profile id)
    profile
    (crdt/write profile [:stars id] (constantly {:removed? false}) at)))

(defn unstar
  "Take the star off the episode `id` in `profile` at `at`, leaving a
  tombstone, so that it outlives an older star on another device."
  [profile id at]
  (if (starred? profile id)
    (crdt/write profile [:stars id] #(assoc % :removed? true) at)
    profile))

(defn ^:no-doc second-of
  "The whole second of `position`, or nil when it isn't a number of
  seconds from the start."
  [position]
  (when (and (number? position) (<= 0 position) (not (infinite? position)))
    (long position)))

(defn bookmark
  "Bookmark the episode `id` in `profile` at `position` seconds at `at`, with
  a `title` when it's given. Another bookmark at the same whole second
  replaces its title."
  ([profile id position at]
   (bookmark profile id position nil at))
  ([profile id position title at]
   (if-let [s (second-of position)]
     (crdt/write profile [:bookmarks id s] (constantly {:title title :removed? false}) at)
     profile)))

(defn ^:no-doc unmark
  "The `profile` with a tombstone at `at` in place of the live entry of the
  episode `id` at `position` seconds in the collection `k`, e.g.
  :bookmarks."
  [profile k id position at]
  (let [s (second-of position)
        e (get-in profile [k id s])]
    (if (crdt/live? e)
      (crdt/write profile [k id s] #(assoc % :removed? true) at)
      profile)))

(defn ^:no-doc marks
  "The live entries of the collection `k` of `profile`, the latest written
  first, or those of the episode `id` in order of their seconds. Each is a
  map of its second under `at-key` and the keys `ks` of the entry.

  The collection holds entries by episode and whole second, e.g.
  :bookmarks. Each map also has the episode's :id and :updated-at."
  ([profile k at-key ks]
   (->> (for [[id entries] (get profile k)
              [s e] entries
              :when (not (:removed? e))]
          (clojure.core/merge {:id id at-key s}
                              (zipmap ks (map e ks))
                              {:updated-at (:updated-at e)}))
        (sort-by #(- (crdt/stamp-ms %)))
        vec))
  ([profile k at-key ks id]
   (->> (marks {k (select-keys (get profile k) [id])} k at-key ks)
        (sort-by at-key)
        vec)))

(defn unbookmark
  "Remove the bookmark of the episode `id` at `position` seconds from
  `profile` at `at`, leaving a tombstone."
  [profile id position at]
  (unmark profile :bookmarks id position at))

(defn bookmarks
  "The bookmarks of `profile`, the latest written first, or those of the
  episode `id` in order of position.

  A bookmark is a map of :id, :position in whole seconds, :title and
  :updated-at."
  ([profile]
   (marks profile :bookmarks :position [:title]))
  ([profile id]
   (marks profile :bookmarks :position [:title] id)))

(defn clip
  "Keep a clip of the episode `id` in `profile` at `at`, from `start` seconds
  for `duration` seconds, with a `title` when it's given.

  A clip has the shape of a podcast:soundbite. Another clip at the same
  whole second replaces it, and a start or duration that isn't a number of
  seconds keeps no clip."
  ([profile id start duration at]
   (clip profile id start duration nil at))
  ([profile id start duration title at]
   (let [s (second-of start)]
     (if (and s (number? duration) (pos? duration) (not (infinite? duration)))
       (crdt/write profile [:clips id s] (constantly {:duration duration :title title :removed? false}) at)
       profile))))

(defn unclip
  "Remove the clip of the episode `id` that starts at `start` seconds
  from `profile` at `at`, leaving a tombstone."
  [profile id start at]
  (unmark profile :clips id start at))

(defn clips
  "The clips of `profile`, the latest written first, or those of the episode
  `id` in order of start.

  A clip is a map of :id, :start-time in whole seconds, :duration in
  seconds, :title and :updated-at."
  ([profile]
   (marks profile :clips :start-time [:duration :title]))
  ([profile id]
   (marks profile :clips :start-time [:duration :title] id)))

(def rating-scale
  "The scale of ratings when no other is given: from 1 for the worst to 5
  for the best, like the stars of most apps.

  A scale is a map of :worst and :best, two different numbers, as in a
  schema.org Rating. Give another as :scale in the options, e.g.
  {:worst 0 :best 1} for thumbs down and up."
  {:worst 1 :best 5})

(defn ^:no-doc scale?
  "Whether `x` is a rating scale, a map whose :worst and :best are two
  different numbers."
  [x]
  (let [finite? #(and (number? %) (== % %) (not (infinite? %)))]
    (and (map? x)
         (finite? (:worst x))
         (finite? (:best x))
         (not (== (:worst x) (:best x))))))

(defn ^:no-doc checked-scale
  "The :worst and :best of a `scale` from the options. It throws when that
  isn't a scale."
  [scale]
  (if (scale? scale)
    (select-keys scale [:worst :best])
    (throw (ex-info "A scale of ratings has a :worst and a :best, two different numbers" {:scale scale}))))

(defn ^:no-doc scale-of
  "The scale of the rating entry `e`, or rating-scale when it names none."
  [e]
  (clojure.core/merge rating-scale (select-keys e [:worst :best])))

(defn ^:no-doc rating?
  "Whether `n` is a number from the worst to the best of `scale`."
  [n {:keys [worst best] :as scale}]
  (and (scale? scale)
       (number? n)
       (<= (min worst best) n (max worst best))))

(defn ^:no-doc rescaled
  "The rating `n` of the scale `from` converted to the scale `to`, at the
  same point between the worst and the best."
  [n from to]
  (if (= from to)
    n
    (+ (:worst to)
       (* (/ (- n (:worst from)) (double (- (:best from) (:worst from))))
          (- (:best to) (:worst to))))))

(defn rating
  "The rating that the listener gave the episode `id` in `profile`, or nil,
  in the scale of `opts` if it gives one.

  It's in the scale that it was given in. A :scale in `opts` converts it,
  e.g. a 4 of 1 to 5 is 75.25 of 1 to 100."
  ([profile id]
   (rating profile id {}))
  ([profile id {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [removed?] n :rating :as e} (get-in profile [:ratings id])]
     (when (and (number? n) (not removed?))
       (if scale
         (rescaled n (scale-of e) (checked-scale scale))
         n)))))

(defn rated
  "The episodes of `profile` that have a rating, the latest rated first, in
  the scale of `opts` if it gives one.

  Each is a map of :id, :rating, the :worst and :best of its scale, and
  :updated-at. A :scale in `opts` converts the ratings as rating does."
  ([profile]
   (rated profile {}))
  ([profile {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (mapv (fn [id]
           (let [e (get-in profile [:ratings id])]
             (clojure.core/merge {:id id :rating (rating profile id opts)}
                                 (if scale (checked-scale scale) (scale-of e))
                                 {:updated-at (:updated-at e)})))
         (crdt/live-by-stamp (:ratings profile)))))

(defn rate-episode
  "Give the episode `id` in `profile` the rating `n` at `at`, in the scale
  of `opts`, replacing any earlier rating.

  The rating must be within rating-scale, or the :scale in `opts`, or
  nothing changes. The profile keeps the scale with the rating, so another
  app can read it in its own scale."
  ([profile id n at]
   (rate-episode profile id n at {}))
  ([profile id n at {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (let [scale (checked-scale (or scale rating-scale))
         e     (get-in profile [:ratings id])
         same? #(and (not (:removed? e)) (number? (:rating e)) (== n (:rating e)) (= scale (scale-of e)))]
     (if (and (rating? n scale) (not (same?)))
       (crdt/write profile [:ratings id] (constantly (assoc scale :rating n :removed? false)) at)
       profile))))

(defn unrate-episode
  "Take the rating off the episode `id` in `profile` at `at`, leaving a
  tombstone."
  [profile id at]
  (if (rating profile id)
    (crdt/write profile [:ratings id] #(assoc % :removed? true) at)
    profile))

(defn ^:no-doc queue-entries
  "The entries of the queue of `profile`, each at the place of its latest
  move, as dk.simongray.podcast.util.crdt/with-moves gives them."
  [profile]
  (crdt/with-moves (:queue profile) (:queue-moves profile)))

(defn ^:no-doc playlist-entries
  "The entries of the playlist `name` of `profile`, each at the place of its
  latest move."
  [profile name]
  (crdt/with-moves (get-in profile [:playlists name]) (get-in profile [:playlist-moves name])))

(defn ^:no-doc removed-from
  "The writes that take the entry at `path` out of a ranked list of
  `profile`, along with its move at `move-path` if it has one."
  [profile path move-path]
  (cond-> [[path #(assoc % :removed? true)]]
    (crdt/live? (get-in profile move-path)) (conj [move-path #(assoc % :removed? true)])))

(defn queue
  "The episode ids queued in `profile`, the current one first."
  [profile]
  (mapv key (crdt/ranked (queue-entries profile))))

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
  "Whether the episode `id` is in the inbox of `profile`, where refresh put
  it, and the listener hasn't dismissed, queued or played it since."
  [profile id]
  (let [e (get-in profile [:inbox id])]
    (and (crdt/live? e) (not (queued? profile id)) (not (played? profile id)))))

(defn inbox
  "The episode ids in the inbox of `profile`, the latest to arrive first."
  [profile]
  (filterv #(in-inbox? profile %) (crdt/live-by-stamp (:inbox profile))))

(defn dismiss
  "Take the episode `id` out of the inbox of `profile` at `at`, leaving a
  tombstone."
  [profile id at]
  (if-let [writes (out-of-inbox profile id)]
    (crdt/write-each profile writes at)
    profile))

(defn ^:no-doc place
  "The `profile` with the episode `id` queued at `rank` at `at`, and out of
  the inbox."
  [profile id rank at]
  (crdt/write-each profile (into [[[:queue id] (constantly {:rank rank :removed? false})]] (out-of-inbox profile id)) at))

(defn enqueue
  "Put the episode `id` at the end of the queue of `profile` at `at`, unless
  it is in the queue."
  [profile id at]
  (if (queued? profile id)
    profile
    (place profile id (rank/between (crdt/last-rank (queue-entries profile)) nil) at)))

(defn play-next
  "Put the episode `id` right after the current episode of `profile` at `at`,
  moving it there when it's queued, or to the front of an empty queue."
  [profile id at]
  (if (= id (current profile))
    profile
    (let [[first-id second-id] (remove #{id} (queue profile))
          rank (crdt/rank-between (queue-entries profile) first-id second-id)]
      (if (queued? profile id)
        (crdt/write profile [:queue-moves id] (constantly {:rank rank :removed? false}) at)
        (place profile id rank at)))))

(defn dequeue
  "Take the episode `id` out of the queue of `profile` at `at`, leaving a
  tombstone, so that it outlives an older addition on another device."
  [profile id at]
  (if (queued? profile id)
    (crdt/write-each profile (removed-from profile [:queue id] [:queue-moves id]) at)
    profile))

(defn move
  "Move the queued episode `id` to position `index` of the queue of `profile`
  at `at`. An index that isn't whole is rounded down, and NaN counts as 0.

  The place is an entry of its own, so the move never brings back an
  episode that another device took out of the queue meanwhile."
  [profile id index at]
  (if-not (queued? profile id)
    profile
    (crdt/write profile
                [:queue-moves id]
                (constantly {:rank (crdt/rank-at (queue-entries profile) id index) :removed? false})
                at)))

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
  (mapv key (crdt/ranked (playlist-entries profile name))))

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
  (crdt/append profile [:playlists name] (playlist-entries profile name) [id] at))

(defn remove-from-playlist
  "Take the episode `id` out of the playlist `name` of `profile` at `at`,
  leaving a tombstone."
  [profile name id at]
  (if (crdt/live? (get-in profile [:playlists name id]))
    (crdt/write-each profile (removed-from profile [:playlists name id] [:playlist-moves name id]) at)
    profile))

(defn move-in-playlist
  "Move the episode `id` to position `index` of the playlist `name` of
  `profile` at `at`, as move moves an episode in the queue."
  [profile name id index at]
  (let [entries (playlist-entries profile name)]
    (if (crdt/live? (get entries id))
      (crdt/write profile
                  [:playlist-moves name id]
                  (constantly {:rank (crdt/rank-at entries id index) :removed? false})
                  at)
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
      (crdt/write-each profile
                       (concat (mapcat #(removed-from profile [:playlists from %] [:playlist-moves from %]) ids)
                               (when to (crdt/appended profile [:playlists to] (playlist-entries profile to) ids)))
                       at))))

(defn to-list-feed
  "The playlist `name` of `profile` as a list feed of the podcast namespace,
  for dk.simongray.podcast.feed.rss/emit, with the `opts` below.

  It's a channel of the medium podcastL, whose remote items point to the
  episodes in order, so Podcasting 2.0 apps that play list feeds can play
  it. The `opts` give what a feed needs and a playlist lacks:

  - :url, where the feed is published, which gives its podcast:guid
  - :link, a page about the list, the URL by default, which RSS 2.0
    requires
  - :description, the name by default
  - :medium, another medium, such as mixed

  The URLs of the feeds go out without credentials, since a list feed is
  for other people."
  ([profile name]
   (to-list-feed profile name {}))
  ([profile name {:keys [url link description medium] :or {medium "podcastL"} :as opts}]
   {:pre [(map? opts)]}
   (let [subs (subscriptions profile)]
     (cond-> {:rss/version          "2.0"
              :rss/title            name
              :rss/description      (or description name)
              :podcast/medium       medium
              :podcast/remote-items (vec (for [[feed-guid item-guid] (playlist profile name)
                                               :let [[feed-url] (http/split-credentials (get-in subs [feed-guid :url]))]]
                                           (cond-> {:feed-guid feed-guid :item-guid item-guid}
                                             feed-url (assoc :feed-url feed-url))))}
       (or link url) (assoc :rss/link (or link url))
       url           (assoc :podcast/guid (guid/from-url url))))))

(defn from-list-feed
  "Add the episodes of the list feed `feed` to the end of the playlist
  `name` of `profile` at `at`, in the order of the feed."
  [profile name feed at]
  (crdt/append profile
               [:playlists name]
               (playlist-entries profile name)
               (for [{:keys [feed-guid item-guid]} (:podcast/remote-items feed)
                     :when (and feed-guid item-guid)]
                 [feed-guid item-guid])
               at))

(defn new-episodes
  "The ids of the items that the parse `after` of the feed at `url` has and
  the parse `before` hadn't, oldest first.

  Without `before`, there are none, since a first parse finds the back
  catalogue, not new episodes."
  [url before after]
  (if (nil? before)
    []
    (let [feed-guid     (feed/feed-guid after url)
          dates         (keep #(let [d (:rss/pub-date %)] (when (inst? d) (inst-ms d))) (:rss/items after))
          oldest-first? (< (or (first dates) 0) (or (last dates) 0))
          added         (:added (feed/diff before after))]
      (mapv #(vector feed-guid (feed/item-guid %))
            (if oldest-first? added (reverse added))))))

(defn- joined-at
  "When each of the new episodes `ids` of the parse `feed`, oldest first,
  joins a profile at `at`, by id.

  It's the episode's date of publication, but at the latest `at` for the
  newest episode and a millisecond earlier for each one before it, so
  that episodes without a date keep their order."
  [feed ids at]
  (let [dates (into {} (for [item (:rss/items feed)
                             :let [date (:rss/pub-date item)]
                             :when (inst? date)]
                         [(feed/item-guid item) date]))]
    (into {} (map-indexed (fn [i [_ item-guid :as id]]
                            (let [latest (time/->inst (- (inst-ms at) i))]
                              [id (min-key inst-ms (get dates item-guid latest) latest)]))
                          (reverse ids)))))

(defn refresh
  "Note in `profile` at `at` a new parse `after` of the feed at `url`, which
  was `before`. The result is a map of the new :profile and the episode ids
  it :added.

  The new episodes join the end of the queue with :auto-queue? in the
  settings of a live subscription, or else the inbox with :inbox?. An
  episode that's already in the profile stays as the listener left it,
  since another device can find it first. The feed is the author of what
  joins, at the episode's date of publication but never after `at`. A
  device that finds an episode late then never undoes what the listener
  did with it on another device."
  [profile url before after at]
  (let [feed-guid (feed/feed-guid after url)
        added     (new-episodes url before after)
        {:keys [auto-queue? inbox?]} (when (subscribed? profile feed-guid) (settings profile feed-guid))
        unknown   (remove #(or (played? profile %) (contains? (:queue profile) %) (contains? (:inbox profile) %))
                          added)
        writes    (cond
                    auto-queue? (crdt/appended profile [:queue] (queue-entries profile) unknown)
                    inbox?      (for [id unknown] [[:inbox id] (constantly {:removed? false})])
                    :else       [])
        stamps    (joined-at after unknown at)
        write     (fn [s [[_ id :as path] f]] (crdt/write s path f (stamps id)))]
    {:profile (crdt/as-device profile (feed-author feed-guid) #(reduce write % writes))
     :added added}))

(defn lists
  "The OPML lists that the listener follows in `profile`, as a map from URL
  to :title and :folder. The folder is the one that the shows of the list
  go into."
  [profile]
  (into {} (keep (fn [[url e]] (when-not (:removed? e) [url (select-keys e [:title :folder])])))
        (:lists profile)))

(defn follow-list
  "Follow the OPML list at `url` in `profile` at `at`, with the :title and
  the :folder for its shows in `details`, when they're given.

  Fetch the list and give each new parse to refresh-list. Following a
  list again changes its title and folder."
  ([profile url at]
   (follow-list profile url {} at))
  ([profile url {:keys [title folder] :as details} at]
   (crdt/write profile [:lists url] (constantly {:title title :folder folder :removed? false}) at)))

(defn unfollow-list
  "Stop following the OPML list at `url` in `profile` at `at`, leaving a
  tombstone. The subscriptions that the list brought stay."
  [profile url at]
  (if (contains? (lists profile) url)
    (crdt/write profile [:lists url] #(assoc % :removed? true) at)
    profile))

(defn ^:no-doc outlines
  "The outlines of the OPML subscription `list` that have a feed URL, in
  order."
  [list]
  (filter :xml-url (:subscriptions list)))

(defn ^:no-doc named
  "The feed guid of the OPML `outline`, given the `names` of the
  subscriptions by URL, as by-url gives them.

  It's the guid of the subscription at the outline's URL, or else the
  outline's own guid, or else the guid of its URL. This keeps a known show
  from coming in again under another guid."
  [names {:keys [xml-url guid] :as outline}]
  (or (get names (normal-url xml-url))
      (when (string? guid) (not-empty (str/trim guid)))
      (guid/from-url xml-url)))

(defn- joining
  "The shows of the OPML `list` that join `profile`, as pairs of feed guid
  and outline in the order of the list. The `names` are as by-url gives
  them, and a show that the list had before, in `was`, doesn't join."
  [profile names was list]
  (:joining (reduce (fn [{:keys [names joined] :as acc} {:keys [xml-url] :as outline}]
                      (let [g (named names outline)]
                        (if (or (was g) (get-in profile [:subscriptions g]) (joined g))
                          acc
                          (-> acc
                              (assoc-in [:names (normal-url xml-url)] g)
                              (update :joined conj g)
                              (update :joining conj [g outline])))))
                    {:names names :joined #{} :joining []}
                    (outlines list))))

(defn refresh-list
  "Note in `profile` at `at` a new parse `after` of the OPML list at `url`,
  which was `before`. The result is a map of the new :profile, the feed
  guids it :added, and those that :left.

  Shows that joined the list are subscribed to, in its folder, unless the
  profile has them already by feed URL or guid. Without `before`, every
  show counts as joined. A show that left the list stays a subscription,
  so that you can ask the listener about it. Nothing changes for a list
  that the listener doesn't follow."
  [profile url before after at]
  (if-let [{:keys [folder]} (get (lists profile) url)]
    (let [names (by-url profile)
          guids (fn [list] (map #(named names %) (outlines list)))
          is    (set (guids after))
          joins (joining profile names (set (guids before)) after)
          join  (fn [profile [g {:keys [xml-url title text]}]]
                  (cond-> (subscribe profile g {:url xml-url :title (or title text)} at)
                    folder (configure g {:folder folder} at)))]
      {:profile (crdt/at-once profile at #(reduce join % joins))
       :added (mapv first joins)
       :left  (into [] (comp (distinct) (remove is) (filter #(subscribed? profile %))) (guids before))})
    {:profile profile :added [] :left []}))

(defn to-opml
  "The live subscriptions of `profile` as a subscription list for
  dk.simongray.podcast.format.opml/emit.

  The folders come first, then the shows in no folder, and the followed
  lists are includes. Each outline has the guid of its feed, the tags as
  categories, and the time of subscribing as its creation time. A comma in
  a tag is written as an underscore, since OPML separates categories with
  commas."
  [profile]
  (let [subs (subscriptions profile)
        ls   (lists profile)]
    (cond-> {:title         "Subscriptions"
             :subscriptions (vec (for [[folder feed-guids] (sort-by (fn [[folder _]] [(nil? folder) (str folder)]) (folders profile))
                                       feed-guid feed-guids
                                       :let [{:keys [url title added-at]} (get subs feed-guid)
                                             ts (tags profile feed-guid)]]
                                   (cond-> {:text (or title url) :xml-url url :guid feed-guid}
                                     title            (assoc :title title)
                                     folder           (assoc :path [folder])
                                     (seq ts)         (assoc :category (vec ts))
                                     (inst? added-at) (assoc :created added-at))))}
      (seq ls) (assoc :includes (vec (for [[url {:keys [title folder]}] (sort-by key ls)]
                                       (cond-> {:url url}
                                         title  (assoc :text title)
                                         folder (assoc :path [folder]))))))))

;; Pocket Casts and Overcast write <outline text="feeds"> around the feeds.
;; Seen in September 2026, in an export file of each app:
;; https://github.com/razorgoto/podcast/blob/master/podcasts.opml
;; https://github.com/vinhnx/podcasts/blob/main/overcast.opml
(def wrappers
  "The lower-case names of the folder that some apps write around a whole
  subscription list. When such a folder holds every feed of the list,
  from-opml reads it as no folder."
  #{"feeds"})

(defn- wrapper
  "The folder that wraps every feed of the OPML subscription `list`, if
  `wrapper?` is true of its name in lower case."
  [list wrapper?]
  (let [[folder :as firsts] (map (comp first :path) (outlines list))]
    (when (and folder (apply = firsts) (wrapper? (str/lower-case (str folder))))
      folder)))

(defn- imported
  "The import `acc` of from-opml with the feed of `outline` subscribed
  to at `at`, in `folder` if any, and its categories as tags."
  [{:keys [profile names] :as acc} {:keys [xml-url text title category created] :as outline} folder at]
  (let [g       (named names outline)
        known   (when (get names (normal-url xml-url)) (get-in profile [:subscriptions g :url]))
        details {:url (or known xml-url) :title (or title text) :added-at (when (inst? created) created)}]
    {:names (assoc names (normal-url xml-url) g)
     :profile (as-> (subscribe profile g details at) profile
                (if folder (configure profile g {:folder folder} at) profile)
                (reduce #(tag %1 g %2 at) profile (when (sequential? category) category)))}))

(defn from-opml
  "Subscribe `profile` at `at` to each feed of the OPML subscription `list`
  from dk.simongray.podcast.format.opml/parse, with `opts`.

  Each subscription gets the title, the innermost folder, the categories as
  tags and the creation time of its outline. The profile also follows the
  lists that the OPML includes, and a feed that the profile has already
  keeps its guid and URL.

  A folder around every feed whose name is in wrappers counts as no
  folder. The :wrappers of `opts` replaces those names with a set of
  lower-case names or a predicate."
  ([profile list at]
   (from-opml profile list at {}))
  ([profile list at opts]
   {:pre [(map? opts)]}
   (let [around  (wrapper list (:wrappers opts wrappers))
         folder  (fn [path] (last (if (and around (= around (first path))) (rest path) path)))
         feeds   (fn [profile]
                   (:profile (reduce #(imported %1 %2 (folder (:path %2)) at)
                                     {:profile profile :names (by-url profile)}
                                     (outlines list))))
         include (fn [profile {:keys [url text path]}]
                   (if (contains? (lists profile) url)
                     profile
                     (follow-list profile url {:title text :folder (folder path)} at)))]
     (crdt/at-once profile at #(reduce include (feeds %) (:includes list))))))

(defn merge
  "Reconcile the profiles `a` and `b` of a listener's devices, as
  dk.simongray.podcast.util.crdt/merge does: by the later entry, and of
  two listening counts of one device, by the higher."
  [a b]
  (crdt/merge collections a b))

(defn- rank-problem
  "A description of the first rank in the queue or a playlist of `profile`
  that isn't a rank, which would make ranked throw."
  [profile]
  (some (fn [[where entries]]
          (some (fn [[id {:keys [rank]}]]
                  (when (and (some? rank) (not (rank/rank? rank)))
                    (str "the rank of " (pr-str id) " in " where " is " (pr-str rank) ", not a rank")))
                entries))
        (concat [["the queue" (:queue profile)] ["the moves of the queue" (:queue-moves profile)]]
                (for [[name entries] (:playlists profile)]
                  [(str "the playlist " (pr-str name)) entries])
                (for [[name entries] (:playlist-moves profile)]
                  [(str "the moves of the playlist " (pr-str name)) entries]))))

(defn- key-problem
  "A description of the first key of `profile` of the wrong type for its
  collection. Lists and tags have strings as keys, and bookmarks and clips
  whole seconds."
  [profile]
  (or (some (fn [url]
              (when-not (string? url)
                (str "the list at " (pr-str url) " is not at a URL")))
            (keys (:lists profile)))
      (some (fn [[what k]]
              (some (fn [[id entries]]
                      (when-not (every? #(and (integer? %) (<= 0 %)) (keys entries))
                        (str "a " what " of " (pr-str id) " is not at a whole number of seconds")))
                    (get profile k)))
            [["bookmark" :bookmarks] ["clip" :clips]])
      (some (fn [[feed-guid ts]]
              (when-not (every? string? (keys ts))
                (str "a tag of " (pr-str feed-guid) " is not a string")))
            (:tags profile))))

(defn- mark-problem
  "A description of the first live rating of `profile` outside its scale,
  or of the first live clip whose duration isn't a number of seconds."
  [profile]
  (or (some (fn [[id {:keys [rating removed?] :as e}]]
              (when-not (or removed? (rating? rating (scale-of e)))
                (str "the rating of " (pr-str id) " is " (pr-str rating)
                     ", not a number of the scale " (pr-str (scale-of e)))))
            (:ratings profile))
      (some (fn [[id entries]]
              (some (fn [[start {:keys [duration removed?]}]]
                      (when-not (or removed? (and (number? duration) (pos? duration)))
                        (str "the clip of " (pr-str id) " at " start " lasts " (pr-str duration)
                             ", not a number of seconds")))
                    entries))
            (:clips profile))))

(defn- count-problem
  [profile]
  (some (fn [[feed-guid months]]
          (when-not (every? #(number? (:seconds % 0)) (mapcat vals (vals months)))
            (str "the listening of " (pr-str feed-guid) " is not counted in seconds")))
        (:listened profile)))

(defn- criteria-problem
  [profile]
  (some (fn [[name {:keys [criteria]}]]
          (when-not (or (nil? criteria) (map? criteria))
            (str "the criteria of the filter " (pr-str name) " are not a map")))
        (:filters profile)))

(def ^:no-doc checks
  "The checks of problem after those of dk.simongray.podcast.util.crdt, in
  order. Each takes a profile that is a map and returns a description of the
  first problem of its kind, or nil, and relies on the checks before it."
  [rank-problem key-problem mark-problem count-problem criteria-problem])

(defn problem
  "A description of what in `profile` breaks the rules that merge and the
  functions here rely on, or nil.

  A merge spreads the values of another device to every device, where a
  bad value makes a function throw, or two devices disagree. The rules are
  those of dk.simongray.podcast.util.crdt/problem, and:

  - Each rank in the queue and in a playlist is a rank.
  - A list is at a URL, a tag is a string, and a bookmark and a clip are
    at a whole number of seconds.
  - A rating is a number of its scale, and a clip lasts a number of
    seconds.
  - A listening count is a number of seconds.
  - The criteria of a saved filter are a map.

  Saved filters from a later version are fine here, and
  search/problem says whether this version understands them."
  [profile]
  (or (crdt/problem collections profile)
      (some #(% profile) checks)))

(defn ^:no-doc stepped
  "The result of step for `profile`, its `effects`, and the sleep timer
  `sleep`, if one runs."
  [profile effects sleep]
  (cond-> {:profile profile :effects effects}
    sleep (assoc :sleep sleep)))

(defn ^:no-doc ended
  "The result of step for `profile` when the episode `id` ended at `at`,
  with the sleep timer `sleep`.

  The episode counts as played and leaves the queue if it was current,
  with the effect [:play next] when another episode is queued. With a
  sleep timer, one episode less is left to play, and when none is left,
  the effect is [:stop id] and the timer stops."
  [profile id at sleep]
  (let [profile' (mark-played profile id at)
        current? (= id (current profile'))
        profile' (if current? (advance profile' at) profile')
        next     (when current? (current profile'))
        left     (some-> (:episodes sleep) dec)]
    (if (and left (not (pos? left)))
      (stepped profile' [[:stop id]] nil)
      (stepped profile' (if next [[:play next]] []) (cond-> sleep left (assoc :episodes left))))))

(defn- moved
  "The result of step, with the `effects` and the sleep timer `sleep`,
  when the episode `id` moved at `at` from its position in `profile` to
  that in `profile'`.

  The result has `profile'` and the `effects`, then [:played id] if the
  episode now counts as played. When the position moved into the outro,
  [:stop id] replaces the `effects`, and ended ends the episode with the
  sleep timer."
  [profile profile' id effects at sleep]
  (let [was       (played? profile id)
        start     (outro-start profile' (first id) id)
        in-outro? #(let [p (:position (episode-entry % id))]
                     (and start (number? p) (>= p start)))]
    (if (and (in-outro? profile') (not (in-outro? profile)))
      (update (ended profile' id at sleep) :effects
              #(into (cond-> [[:stop id]] (not was) (conj [:played id]))
                     (remove #{[:stop id]})
                     %))
      (stepped profile'
               (cond-> effects (and (not was) (played? profile' id)) (conj [:played id]))
               sleep))))

(defn step
  "Advance `profile` by the player `event` at `at` with `opts`, returning a
  map of the new :profile and the :effects, which say what the UI should do
  next.

  The event is a vector of its kind, an episode id and any arguments:

  - [:progress id position duration] records the position, and counts the
    seconds heard for listened, with the effect [:played id] when the
    episode now counts as played.
  - [:ended id] marks it played and drops it from the queue when it was
    current, with the effect [:play next] when another episode is queued.
  - [:play id] makes it current, at the front of the queue.
  - [:skip id amount] moves that many seconds, or the preferred amount for
    :forward or :back, with the effect [:seek id position], and
    [:played id] when it now counts as played.
  - [:chapter id direction doc] jumps to the :next or :previous chapter of
    the chapters document, as chapters/jump does, with the effects of a
    skip.

  A progress, skip or jump that reaches the outro (see outro-start) ends
  the episode as [:ended id] does, with [:stop id] first and no seek. The
  `opts` are:

  - :sleep, the sleep timer of this device, e.g. {:episodes 1} stops at
    the end of this episode. The result has what's left of it under
    :sleep, and the last episode gives [:stop id] instead of [:play next].
  - :restart, the seconds of chapters/jump for a jump to the previous
    chapter
  - :played-threshold and :listening-gap, over the vars of those names"
  ([profile event at]
   (step profile event at {}))
  ([profile [kind id & args :as event] at {:keys [sleep] :as opts}]
   {:pre [(map? opts)]}
   (case kind
     :skip     (let [[amount] args
                     {:keys [skip-forward skip-back]} (preferences profile)
                     seconds  (case amount :forward skip-forward :back (- skip-back) amount)
                     profile' (skip profile id seconds at (select-keys opts [:played-threshold]))]
                 (moved profile profile' id [[:seek id (:position (episode-entry profile' id))]] at sleep))
     :chapter  (let [[direction doc] args
                     position (:position (episode-entry profile id))
                     from     (if (number? position) position 0)]
                 (if-let [target (chapters/jump doc from direction (select-keys opts [:restart]))]
                   (step profile [:skip id (- target from)] at opts)
                   (stepped profile [] sleep)))
     :progress (let [[position duration] args
                     profile' (-> profile
                                  (heard id position at (:listening-gap opts listening-gap))
                                  (progress id position duration at (select-keys opts [:played-threshold])))]
                 (moved profile profile' id [] at sleep))
     :ended    (ended profile id at sleep)
     :play     (stepped (place profile id (crdt/rank-between (queue-entries profile) nil (first (remove #{id} (queue profile)))) at)
                        []
                        sleep)
     (stepped profile [] sleep))))

(comment
  (def sample
    (-> (make "laptop")
        (subscribe "feed-1" {:url "https://example.org/feed.xml" :title "A show"} #inst "2026-01-01")
        (configure "feed-1" {:folder "Tech" :skip-outro 30} #inst "2026-01-01")
        (tag "feed-1" "news" #inst "2026-01-01")
        (enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
        (add-to-playlist "Road trip" ["feed-1" "ep-2"] #inst "2026-01-01T01:00:00Z")
        (star ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
        (bookmark ["feed-1" "ep-1"] 754 "The answer" #inst "2026-01-01T01:30:00Z")
        (clip ["feed-1" "ep-1"] 754 42.5 "The answer, in full" #inst "2026-01-01T01:31:00Z")
        (rate-episode ["feed-1" "ep-1"] 5 #inst "2026-01-01T01:32:00Z")))

  (folders sample)
  (tagged sample)
  (playlists sample)
  (bookmarks sample)
  (clips sample)
  (rated sample)

  ;; a thumb up, and the same rating in the scale of another app
  (def thumbs
    (rate-episode sample ["feed-1" "ep-2"] 1 #inst "2026-01-01T01:33:00Z" {:scale {:worst 0 :best 1}}))

  (rating thumbs ["feed-1" "ep-2"])
  (rating thumbs ["feed-1" "ep-2"] {:scale rating-scale})

  (to-opml sample)
  (to-list-feed sample "Road trip" {:url "https://example.org/road-trip.xml"})

  ;; nil for a profile that keeps the rules, and the first rule that it breaks
  (problem sample)
  (problem (assoc-in sample [:queue ["feed-1" "ep-1"] :rank] 5))

  ;; a report that moves the position into the outro ends the episode
  (step (progress sample ["feed-1" "ep-1"] 3500 3600 #inst "2026-01-01T02:00:00Z")
        [:progress ["feed-1" "ep-1"] 3580 3600]
        #inst "2026-01-01T02:00:20Z")

  ;; a jump to the next chapter, from 100 seconds into the episode
  (def doc
    {:chapters [{:start-time 0 :title "Intro"} {:start-time 90 :title "News"} {:start-time 600 :title "Talk"}]})

  (:effects (step (progress sample ["feed-1" "ep-1"] 100 3600 #inst "2026-01-01T02:00:00Z")
                  [:chapter ["feed-1" "ep-1"] :next doc]
                  #inst "2026-01-01T02:00:05Z"))

  ;; a sleep timer that stops the player at the end of this episode
  (step (enqueue sample ["feed-1" "ep-2"] #inst "2026-01-01T02:00:00Z")
        [:ended ["feed-1" "ep-1"]]
        #inst "2026-01-01T03:00:00Z"
        {:sleep {:episodes 1}})
  #_.)

#?(:clj
   (comment
     (-> (make (str (random-uuid)))
         (crdt/identify {:name "laptop"} #inst "2026-01-01")
         (subscribe "feed-1" {:url "https://a/rss" :title "A"} #inst "2026-01-01")
         (enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
         (progress ["feed-1" "ep-1"] 120 3600 #inst "2026-01-01T02:00:00Z"))
     (crdt/after *1 #inst "2026-01-01")
     #_.))
