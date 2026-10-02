(ns dk.simongray.podcast.app.player
  "What a podcast player keeps between plays, as plain data with pure
  functions over it.

  The state is one map:

      {:device        \"8f1c9e2a-…\"   ; this device's id
       :clock         #inst \"…\"      ; the latest stamp written or merged
       :subscriptions {feed-guid {:url :title :added-at :removed?}}
       :settings      {feed-guid {:skip-intro {:value 20} ...}}
       :tags          {feed-guid {\"Tech\" {:removed? false}}}
       :lists         {url {:title :folder :removed?}}  ; followed OPML lists
       :episodes      {id {:position :duration :reported-at}}
       :played        {id {:played? true :played-at}}
       :queue         {id {:rank \"V\" :removed? false}}
       :playlists     {name {id {:rank \"V\" :removed? false}}}
       :inbox         {id {:removed? false}}
       :stars         {id {:removed? false}}
       :bookmarks     {id {754 {:title :removed?}}}
       :clips         {id {754 {:duration :title :removed?}}}
       :ratings       {id {:rating 4 :worst 1 :best 5 :removed?}}
       :filters       {name {:criteria {:where {:status :unplayed}} :removed?}}
       :listened      {feed-guid {\"2026-09\" {device-id {:seconds 5400.0}}}}
       :preferences   {:skip-forward {:value 30} ...}
       :devices       {device-id {:name :platform ... :seen {…}}}
       :retired       {device-id {}}}

  Each write takes the time it happens at, and stamps what it changed with
  that time and the :device of the state. The state is a CRDT, so merge
  joins the states of a listener's devices, and compact drops the
  tombstones that every device has seen. A feed is identified by its
  podcast:guid, and an episode by its id, [feed-guid item-guid]."
  (:refer-clojure :exclude [merge])
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.rank :as rank]
            [dk.simongray.podcast.util.time :as time]))

(defn state
  "A fresh player state for the device `device`, a stable id that tells the
  listener's devices apart, made once per installation, e.g.
  (str (random-uuid))."
  [device]
  {:device device :subscriptions {} :episodes {} :queue {}})

(defn- stamp
  [state entry at]
  (assoc entry :updated-at at :device (:device state)))

(defn- stamped?
  [x]
  (and (map? x) (contains? x :updated-at)))

(defn- later-inst
  "The later of the instants `a` and `b`, either of which can be nil."
  [a b]
  (cond
    (nil? a)                        b
    (nil? b)                        a
    (>= (inst-ms a) (inst-ms b))    a
    :else                           b))

(defn after
  "The instant to stamp a write to `state` at `now` with: `now`, or the
  millisecond after the clock of the state when that's later. This keeps
  each write after what the device has merged."
  [state now]
  (let [clock (:clock state)]
    (if (and clock (>= (inst-ms clock) (inst-ms now)))
      (time/->inst (inc (inst-ms clock)))
      (time/date now))))

(defn- write-each
  "The `state` with each path and function of `writes` applied at `at` as
  write applies one, all with the same stamp, which becomes the state's
  clock. Within at-once, the stamp is that of at-once."
  [state writes at]
  (let [at (or (::at state) (after state at))]
    (-> (reduce (fn [s [path f]] (update-in s path #(stamp state (f %) at))) state writes)
        (assoc :clock at))))

(defn write
  "The `state` with the entry at `path` replaced by what `f` returns for
  it, stamped at `at`, or just after the state's clock when that's later.

  Every function here writes this way, and an app can write entries of its
  own with it, which merge then reconciles like the rest."
  [state path f at]
  (write-each state [[path f]] at))

(defn ^:no-doc at-once
  "The `state` after `f` with every write in `f` stamped at the same
  instant, as one write at `at` would be.

  The `f` takes the state. Without at-once, each write in a series is a
  millisecond after the one before it, so a long series moves the clock
  ahead of the time."
  [state at f]
  (if (contains? state ::at)
    (f state)
    (dissoc (f (assoc state ::at (after state at))) ::at)))

(defn episode-id
  "The id of `item` in the `feed` at `url`: [feed-guid item-guid]."
  [feed url item]
  [(feed/feed-guid feed url) (feed/item-guid item)])

(def default-settings
  "The settings of a subscription until configure changes them:

  - :auto-queue?, whether new episodes join the queue
  - :inbox?, whether they join the inbox instead
  - :skip-intro and :skip-outro, the seconds to skip at the start and the
    end
  - :folder, the folder that the show is in"
  {:auto-queue? false :inbox? false :skip-intro 0 :skip-outro 0 :folder nil})

(defn subscribed?
  "Whether `state` holds a live subscription to `feed-guid`."
  [state feed-guid]
  (let [sub (get-in state [:subscriptions feed-guid])]
    (boolean (and sub (not (:removed? sub))))))

(defn subscriptions
  "The live subscriptions of `state`, by feed guid."
  [state]
  (into {} (remove (comp :removed? val)) (:subscriptions state)))

(defn ^:no-doc normal-url
  "The `url` in lower case without credentials, scheme or trailing
  slashes, so that two forms of one feed or media URL are equal."
  [url]
  (-> (str url) http/split-credentials first guid/guid-name str/lower-case))

(defn ^:no-doc by-url
  "A map from the URL of each subscription of `state`, as normal-url gives
  it, to its feed guid, removed subscriptions included.

  Where subscriptions share a URL, a live one wins over a removed one, and
  then the one that changed last."
  [state]
  (->> (:subscriptions state)
       (filter (comp :url val))
       (sort-by (fn [[g {:keys [removed? updated-at]}]]
                  [(if removed? 0 1) (or (some-> updated-at inst-ms) 0) (str g)]))
       (into {} (map (fn [[g e]] [(normal-url (:url e)) g])))))

(defn subscribe
  "Subscribe `state` to the feed `feed-guid` at `at`, with the :url and
  :title of the feed in `details`.

  The settings of an earlier subscription to the feed still hold. For a new
  subscription, :added-at can give an earlier time of subscribing, e.g.
  from the OPML of another app."
  [state feed-guid {:keys [url title added-at] :as details} at]
  (write state [:subscriptions feed-guid]
         (fn [sub]
           (assoc (or sub {})
                  :url url :title title :removed? false
                  :added-at (if (subscribed? state feed-guid) (:added-at sub) (time/date (or added-at at)))))
         at))

(defn unsubscribe
  "Unsubscribe `state` from `feed-guid` at `at`, leaving a tombstone even
  where no subscription was known, so that it outlives an older
  subscription on another device."
  [state feed-guid at]
  (write state [:subscriptions feed-guid] #(assoc (or % {}) :removed? true) at))

(defn- values
  [entries]
  (into {} (map (fn [[k e]] [k (:value e)])) entries))

(declare tags)

(defn rekey
  "The `state` with the subscription `feed-guid` moved to `new-guid` at
  `at`, along with its settings and tags, leaving a tombstone.

  A list or another app names a show by the guid of its URL, but the feed
  can declare a guid of its own once it's fetched. Nothing changes when the
  guids are the same, or when there's no live subscription."
  [state feed-guid new-guid at]
  (if (or (= feed-guid new-guid) (not (subscribed? state feed-guid)))
    state
    (let [sub (get-in state [:subscriptions feed-guid])]
      (write-each state
                  (concat [[[:subscriptions new-guid] (constantly (select-keys sub [:url :title :added-at :removed?]))]
                           [[:subscriptions feed-guid] #(assoc % :removed? true)]]
                          (for [[k v] (values (get-in state [:settings feed-guid]))]
                            [[:settings new-guid k] (constantly {:value v})])
                          (for [t (tags state feed-guid)]
                            [[:tags new-guid t] (constantly {:removed? false})]))
                  at))))

(defn settings
  "The settings of the subscription `feed-guid` in `state`, over
  default-settings."
  [state feed-guid]
  (clojure.core/merge default-settings (values (get-in state [:settings feed-guid]))))

(defn configure
  "Change the `settings` of the subscription `feed-guid` in `state` at
  `at`.

  The settings are the keys of default-settings, and these:

  - :rate, the playback rate of this show
  - :downloads, its rule for dk.simongray.podcast.app.downloads

  Each is an entry of its own, so two devices that change different
  settings both keep their change."
  [state feed-guid settings at]
  (if (get-in state [:subscriptions feed-guid])
    (write-each state (for [[k v] settings] [[:settings feed-guid k] (constantly {:value v})]) at)
    state))

(def default-preferences
  "How the listener likes to listen until prefer changes it: the seconds
  that a skip jumps forward and back, and the playback rate."
  {:skip-forward 30 :skip-back 15 :rate 1.0})

(defn preferences
  "The listener's preferences in `state`, over default-preferences."
  [state]
  (clojure.core/merge default-preferences (values (:preferences state))))

(defn prefer
  "Change the listener's `preferences` in `state` at `at`, with the keys of
  default-preferences, and :downloads, the listener's rules for
  dk.simongray.podcast.app.downloads."
  [state preferences at]
  (write-each state (for [[k v] preferences] [[:preferences k] (constantly {:value v})]) at))

(defn rate
  "The playback rate for the feed `feed-guid` in `state`: its own setting,
  or else the listener's preference."
  [state feed-guid]
  (or (:rate (settings state feed-guid)) (:rate (preferences state))))

(defn folders
  "The feed guids of the live subscriptions of `state` by folder, in the
  order of their titles, with the shows in no folder under nil."
  [state]
  (reduce (fn [m [feed-guid _]] (update m (:folder (settings state feed-guid)) (fnil conj []) feed-guid))
          {}
          (sort-by (comp str :title val) (subscriptions state))))

(defn rename-folder
  "Move the shows of `state` in the folder `from` to the folder `to` at
  `at`, or out of all folders when `to` is nil."
  [state from to at]
  (if-let [feed-guids (get (folders state) from)]
    (write-each state (for [g feed-guids] [[:settings g :folder] (constantly {:value to})]) at)
    state))

(defn tags
  "The tags that the listener gave the show `feed-guid` in `state`, as a
  sorted set. Unlike folders, a show can have many tags."
  [state feed-guid]
  (into (sorted-set) (keep (fn [[t e]] (when-not (:removed? e) t))) (get-in state [:tags feed-guid])))

(defn tagged
  "The feed guids of the live subscriptions of `state` by tag, each tag's
  shows in the order of their titles."
  [state]
  (reduce (fn [m [feed-guid _]] (reduce #(update %1 %2 (fnil conj []) feed-guid) m (tags state feed-guid)))
          {}
          (sort-by (comp str :title val) (subscriptions state))))

(defn tag
  "Give the show `feed-guid` in `state` the tag `t`, a string, at `at`, when
  the state has the subscription."
  [state feed-guid t at]
  (if (and (get-in state [:subscriptions feed-guid]) (not (contains? (tags state feed-guid) t)))
    (write state [:tags feed-guid t] (constantly {:removed? false}) at)
    state))

(defn untag
  "Take the tag `t` off the show `feed-guid` in `state` at `at`, leaving a
  tombstone."
  [state feed-guid t at]
  (if (contains? (tags state feed-guid) t)
    (write state [:tags feed-guid t] #(assoc % :removed? true) at)
    state))

(defn rename-tag
  "Rename the tag `from` to `to` on the shows of `state` at `at`, or take it
  off them when `to` is nil."
  [state from to at]
  (if-let [feed-guids (get (tagged state) from)]
    (write-each state (for [g feed-guids
                            w (cond-> [[[:tags g from] #(assoc % :removed? true)]]
                                to (conj [[:tags g to] (constantly {:removed? false})]))]
                        w)
                at)
    state))

(defn filters
  "The saved filters of `state` by name, as criteria for
  dk.simongray.podcast.app.search/episodes."
  [state]
  (into {} (keep (fn [[name e]] (when-not (:removed? e) [name (:criteria e)]))) (:filters state)))

(defn save-filter
  "Save the `criteria` of app.search/episodes in `state` as the filter
  `name` at `at`, so that the other devices have it too.

  The criteria replace a filter of the same name, without :player and
  :url, which belong to the device. They must be data, since the state is
  printed as EDN, so an order that's a function throws."
  [state name criteria at]
  (let [criteria (dissoc criteria :player :url)]
    (when (some fn? (tree-seq coll? seq criteria))
      (throw (ex-info "A saved filter holds data only, not functions" {:name name :criteria criteria})))
    (write state [:filters name] (constantly {:criteria criteria :removed? false}) at)))

(defn remove-filter
  "Remove the saved filter `name` from `state` at `at`, leaving a
  tombstone."
  [state name at]
  (if (contains? (filters state) name)
    (write state [:filters name] #(assoc % :removed? true) at)
    state))

(defn identify
  "Describe this device in the registry of `state` at `at`, with a
  `description` such as its :name and :platform, merged into what it
  said before."
  [state description at]
  (write state [:devices (:device state)] #(clojure.core/merge % description) at))

(defn retire
  "Retire the device `device` from `state` at `at`, from any device.

  The device is no longer among the devices of the state. A sync writes
  nothing for it and deletes its document, and the device can't undo the
  retirement by describing itself again."
  [state device at]
  (write state [:retired device] #(or % {}) at))

(defn retired?
  "Whether `state` has retired the device `device`."
  [state device]
  (contains? (:retired state) device))

(defn devices
  "The devices of `state` by id, as they described themselves with
  identify, except the retired ones."
  [state]
  (into {} (remove (fn [[id _]] (retired? state id))) (:devices state)))

(defn- latest-by-author
  "The latest stamp of each device among the entries of `state`.

  The device registry is left out, or each acknowledgement would be news to
  acknowledge, and two devices would keep writing to each other forever."
  [state]
  (letfn [(walk [m v]
            (cond
              (stamped? v) (update m (:device v) later-inst (:updated-at v))
              (map? v)     (reduce-kv (fn [m _ x] (walk m x)) m v)
              :else        m))]
    (reduce-kv (fn [m k v] (if (#{:device :clock :devices} k) m (walk m v))) {} state)))

(defn acknowledge
  "Note in the registry of `state` at `at` that this device has merged the
  whole states `others`.

  Its entry keeps the latest stamp of each other device that's now merged
  here under :seen, which compact goes by. Only whole states count, never
  the changes that a service sends. The entry is only written when it
  grew, so that a sync alone never rewrites a document."
  [state others at]
  (let [me    (:device state)
        known (get-in state [:devices me :seen] {})
        seen  (dissoc (reduce (fn [m other] (merge-with later-inst m (latest-by-author other))) known others) me)]
    (if (and (= seen known) (get-in state [:devices me]))
      state
      (write state [:devices me] #(assoc (or % {}) :seen seen) at))))

(def default-keep
  "How long a tombstone is kept once every device has seen it, thirty days
  in milliseconds, for a device that joins with a state of its own."
  (* 30 24 60 60 1000))

(defn- seen-by-all?
  "Whether every live device of `state`, except the one that wrote the
  entry `e`, has merged it, by the device registry.

  This device counts as having merged what it holds. With no other device,
  it's false, since a lone device gains nothing by forgetting, and a device
  that joins later could bring back what it forgot."
  [state {:keys [device updated-at] :as e}]
  (let [t      (inst-ms updated-at)
        judges (remove (fn [[id _]] (= id device)) (devices state))]
    (and (seq judges)
         (every? (fn [[id entry]]
                   (or (= id (:device state))
                       (some-> (get-in entry [:seen device]) inst-ms (>= t))))
                 judges))))

(defn compact
  "Drop from `state` at `at` the tombstones that every live device has
  merged, once they're older than the :keep-for of `opts`.

  The :keep-for is in milliseconds, default-keep by default. Played
  marks and listening counts are never dropped, and the tombstones of
  unsubscriptions stay while the listener follows a list, since a list can
  name the show again. A device that stopped syncing holds the compaction
  back until it's retired (see lagging)."
  ([state at]
   (compact state at {}))
  ([state at {:keys [keep-for] :or {keep-for default-keep} :as opts}]
   {:pre [(map? opts)]}
   (let [follows?   (some (complement :removed?) (vals (:lists state)))
         droppable? (fn [e]
                      (and (:removed? e)
                           (<= (+ (inst-ms (:updated-at e)) keep-for) (inst-ms at))
                           (seen-by-all? state e)))
         sweep      (fn sweep [m]
                      (into {} (keep (fn [[k v]]
                                       (cond
                                         (stamped? v) (when-not (droppable? v) [k v])
                                         (map? v)     (when-some [w (not-empty (sweep v))] [k w])
                                         :else        [k v])))
                            m))]
     (reduce (fn [s k] (if (map? (get s k)) (update s k sweep) s))
             state
             (cond->> [:subscriptions :tags :lists :queue :playlists :inbox :stars :bookmarks :clips :ratings
                       :filters]
               follows? (remove #{:subscriptions}))))))

(defn lagging
  "The live devices of `state` that haven't merged everything this one has,
  as a map from id to the latest stamp that each merged, or nil.

  Such a device holds compact back until it catches up or is retired."
  [state]
  (let [me   (:device state)
        mine (assoc (get-in state [:devices me :seen] {}) me (get (latest-by-author state) me))]
    (into {}
          (for [[id entry] (devices state)
                :when (not= id me)
                :let [theirs (:seen entry {})
                      behind (for [[author latest] mine
                                   :when (and latest (not= author id))
                                   :let [t (get theirs author)]
                                   :when (or (nil? t) (< (inst-ms t) (inst-ms latest)))]
                               t)]
                :when (seq behind)]
            [id (when (every? some? behind) (apply min-key inst-ms behind))]))))

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

(declare stamp-ms)

(defn ^:no-doc played-mark
  "The played mark of the episode `id` in `state`: its entry under :played,
  or the mark that an older version kept in the episode's entry, whichever
  is later."
  [state id]
  (let [m (get-in state [:played id])
        e (get-in state [:episodes id])]
    (if (and (contains? e :played?) (> (stamp-ms e) (stamp-ms m)))
      (select-keys e [:played? :played-at :updated-at :device])
      m)))

(defn episode
  "The entry of the episode `id` in `state` with its played mark, or nil.

  The position and the mark are entries of their own, so that a report of
  the position from another device never undoes a mark. The entry has the
  stamp and the device of the later of the two."
  [state id]
  (let [e (get-in state [:episodes id])
        m (played-mark state id)]
    (when (or e m)
      (clojure.core/merge (dissoc e :played? :played-at)
                          (select-keys m [:played? :played-at])
                          (select-keys (if (> (stamp-ms m) (stamp-ms e)) m e) [:updated-at :device])))))

(defn episodes
  "The entries of the episodes of `state` by id, as episode gives them."
  [state]
  (into {}
        (map (fn [id] [id (episode state id)]))
        (distinct (concat (keys (:episodes state)) (keys (:played state))))))

(defn played?
  "Whether the episode `id` counts as played in `state`."
  [state id]
  (boolean (:played? (played-mark state id))))

(defn- heard-to-the-end?
  [position duration threshold]
  (and (number? duration) (pos? duration) (number? position)
       (>= position (- duration threshold))))

(defn ^:no-doc out-of-inbox
  "The writes that take the episode `id` out of the inbox of `state`, if
  it's there."
  [state id]
  (let [e (get-in state [:inbox id])]
    (when (and e (not (:removed? e)))
      [[[:inbox id] #(assoc % :removed? true)]])))

(defn ^:no-doc migrated
  "The writes that move a played mark that an older version kept in the
  entry of the episode `id` in `state` to an entry of its own, if it's
  the later mark."
  [state id]
  (let [e (get-in state [:episodes id])]
    (when (contains? e :played?)
      (cond-> [[[:episodes id] #(dissoc % :played? :played-at)]]
        (> (stamp-ms e) (stamp-ms (get-in state [:played id])))
        (conj [[:played id] (constantly (select-keys e [:played? :played-at]))])))))

(defn ^:no-doc marked-as
  "The writes that mark the episode `id` of `state` as `played?` at `at`.
  A played episode leaves the inbox."
  [state id played? at]
  (into [[[:played id] (constantly (if played? {:played? true :played-at (time/date at)} {:played? false}))]]
        (when played? (out-of-inbox state id))))

(defn progress
  "Record in `state` that the listener is `position` seconds into the
  episode `id` at `at`, with its `duration` if known and the `opts` below.

  An episode heard to within played-threshold seconds of its end counts
  as played and leaves the inbox. The `opts` can give another
  :played-threshold."
  ([state id position at]
   (progress state id position nil at {}))
  ([state id position duration at]
   (progress state id position duration at {}))
  ([state id position duration at opts]
   {:pre [(map? opts)]}
   (let [duration (or duration (:duration (episode state id)))
         done?    (and (not (played? state id))
                       (heard-to-the-end? position duration (:played-threshold opts played-threshold)))]
     (write-each state
                 (concat (migrated state id)
                         [[[:episodes id] #(cond-> (assoc (or % {}) :position position :reported-at (time/date at))
                                              duration (assoc :duration duration))]]
                         (when done? (marked-as state id true at)))
                 at))))

(defn mark-played
  "Mark the episode `id` in `state` as played at `at`, which takes it out
  of the inbox."
  [state id at]
  (write-each state (concat (migrated state id) (marked-as state id true at)) at))

(defn mark-unplayed
  "Mark the episode `id` in `state` as not played at `at`, with its
  position back at the start."
  [state id at]
  (write-each state
              (concat (migrated state id)
                      [[[:episodes id] #(assoc (or % {}) :position 0 :reported-at (time/date at))]]
                      (marked-as state id false at))
              at))

(defn skip
  "Move `seconds` forward, or back when negative, in the episode `id` of
  `state` at `at`, with the `opts` of progress. The position never goes
  before the start or past a known end.

  A duration of zero, or one that isn't a finite number, counts as
  unknown, since players report those before they know. The new position
  is in the state for the UI to seek to."
  ([state id seconds at]
   (skip state id seconds at {}))
  ([state id seconds at opts]
   {:pre [(map? opts)]}
   (let [{:keys [position duration]} (episode state id)
         finite?  #(and (number? %) (== % %) (not (infinite? %)))
         target   (+ (if (finite? position) position 0) seconds)
         target   (max 0 (if (and (finite? duration) (pos? duration)) (min target duration) target))]
     (progress state id target nil at opts))))

(defn- heard
  "The `state` with the seconds heard of the episode `id` up to `position`
  at `at` added to this device's count. Nothing counts when the last
  report is more than `gap` seconds old.

  An older report means that the listener paused. The seconds are how far
  the position moved since the last report, at most three times the time
  since then, as a faster move is a seek. The count is by show and month,
  in UTC, and the last report is the episode's :reported-at, or else its
  stamp."
  [state id position at gap]
  (let [{p0 :position :keys [reported-at updated-at]} (episode state id)
        t0      (or reported-at updated-at)
        seconds (when (and (number? position) (number? p0) (inst? t0))
                  (let [moved (- position p0)
                        spent (/ (- (inst-ms at) (inst-ms t0)) 1000.0)]
                    (when (and (pos? moved) (pos? spent) (<= spent gap))
                      (min moved (* 3 spent)))))]
    (if seconds
      (write state [:listened (first id) (subs (time/format-iso8601 at) 0 7) (:device state)]
             #(assoc (or % {}) :seconds (+ (or (:seconds %) 0) seconds))
             at)
      state)))

(defn listened
  "The seconds that the listener heard of each show in `state`, by feed
  guid and month in UTC, e.g. {feed-guid {\"2026-09\" 5400.0}}.

  The counts of all the devices add up. Since step counts at each
  :progress, report the position at least every 30 seconds while an
  episode plays (see listening-gap)."
  [state]
  (update-vals (:listened state)
               (fn [months] (update-vals months #(reduce + 0 (keep :seconds (vals %)))))))

(defn resume-position
  "Where to start playing the episode `id` of `feed-guid` in `state`: where
  the listener left it, or after the intro when it hasn't been started or
  was heard to the end."
  [state feed-guid id]
  (let [{:keys [position played?]} (episode state id)]
    (if (and (number? position) (pos? position) (not played?))
      position
      (:skip-intro (settings state feed-guid)))))

(defn outro-start
  "Where the outro of the episode `id` of `feed-guid` starts in `state`,
  its skip-outro seconds before the end.

  Nil when the duration is unknown, the feed skips no outro, or the outro
  is as long as the episode."
  [state feed-guid id]
  (let [duration (:duration (episode state id))
        outro    (:skip-outro (settings state feed-guid))]
    (when (and (number? duration) (number? outro) (< 0 outro duration))
      (- duration outro))))

(defn history
  "The episode ids in `state`, most recently played or touched first: an
  episode heard again comes first again."
  [state]
  (->> (episodes state)
       (sort-by (fn [[_ e]] (- (or (some-> (later-inst (:played-at e) (:updated-at e)) inst-ms) 0))))
       (map key)))

(defn ^:no-doc stamp-ms
  "The stamp of the entry `e` in milliseconds, 0 for an entry without one."
  [e]
  (or (some-> (:updated-at e) inst-ms) 0))

(defn ^:no-doc live-by-stamp
  "The keys of the live entries of the map `entries`, the latest written
  first."
  [entries]
  (->> entries
       (remove (comp :removed? val))
       (sort-by (fn [[_ e]] (- (stamp-ms e))))
       (mapv key)))

(defn starred?
  "Whether the episode `id` has a star in `state`."
  [state id]
  (let [e (get-in state [:stars id])]
    (boolean (and e (not (:removed? e))))))

(defn starred
  "The starred episode ids of `state`, the latest starred first."
  [state]
  (live-by-stamp (:stars state)))

(defn star
  "Star the episode `id` in `state` at `at`, unless it has a star."
  [state id at]
  (if (starred? state id)
    state
    (write state [:stars id] (constantly {:removed? false}) at)))

(defn unstar
  "Take the star off the episode `id` in `state` at `at`, leaving a
  tombstone, so that it outlives an older star on another device."
  [state id at]
  (if (starred? state id)
    (write state [:stars id] #(assoc % :removed? true) at)
    state))

(defn ^:no-doc second-of
  "The whole second of `position`, or nil when it isn't a number of
  seconds from the start."
  [position]
  (when (and (number? position) (<= 0 position) (not (infinite? position)))
    (long position)))

(defn bookmark
  "Bookmark the episode `id` in `state` at `position` seconds at `at`, with
  a `title`, which can be nil. Another bookmark at the same whole second
  replaces its title."
  [state id position title at]
  (if-let [s (second-of position)]
    (write state [:bookmarks id s] (constantly {:title title :removed? false}) at)
    state))

(defn ^:no-doc unmarked
  "The `state` with a tombstone at `at` in place of the live entry of the
  episode `id` at `position` seconds in the collection `k`, e.g.
  :bookmarks."
  [state k id position at]
  (let [s (second-of position)
        e (get-in state [k id s])]
    (if (and e (not (:removed? e)))
      (write state [k id s] #(assoc % :removed? true) at)
      state)))

(defn ^:no-doc marks
  "The live entries of the collection `k` of `state`, the latest written
  first, or those of the episode `id` in order of their seconds. Each is a
  map of its second under `at-key` and the keys `ks` of the entry.

  The collection holds entries by episode and whole second, e.g.
  :bookmarks. Each map also has the episode's :id and :updated-at."
  ([state k at-key ks]
   (->> (for [[id entries] (get state k)
              [s e] entries
              :when (not (:removed? e))]
          (clojure.core/merge {:id id at-key s}
                              (zipmap ks (map e ks))
                              {:updated-at (:updated-at e)}))
        (sort-by #(- (stamp-ms %)))
        vec))
  ([state k at-key ks id]
   (->> (marks {k (select-keys (get state k) [id])} k at-key ks)
        (sort-by at-key)
        vec)))

(defn unbookmark
  "Remove the bookmark of the episode `id` at `position` seconds from
  `state` at `at`, leaving a tombstone."
  [state id position at]
  (unmarked state :bookmarks id position at))

(defn bookmarks
  "The bookmarks of `state`, the latest written first, or those of the
  episode `id` in order of position.

  A bookmark is a map of :id, :position in whole seconds, :title and
  :updated-at."
  ([state]
   (marks state :bookmarks :position [:title]))
  ([state id]
   (marks state :bookmarks :position [:title] id)))

(defn clip
  "Keep a clip of the episode `id` in `state` at `at`, from `start` seconds
  for `duration` seconds, with a `title`, which can be nil.

  A clip has the shape of a podcast:soundbite. Another clip at the same
  whole second replaces it, and a start or duration that isn't a number of
  seconds keeps no clip."
  [state id start duration title at]
  (let [s (second-of start)]
    (if (and s (number? duration) (pos? duration) (not (infinite? duration)))
      (write state [:clips id s] (constantly {:duration duration :title title :removed? false}) at)
      state)))

(defn unclip
  "Remove the clip of the episode `id` that starts at `start` seconds
  from `state` at `at`, leaving a tombstone."
  [state id start at]
  (unmarked state :clips id start at))

(defn clips
  "The clips of `state`, the latest written first, or those of the episode
  `id` in order of start.

  A clip is a map of :id, :start-time in whole seconds, :duration in
  seconds, :title and :updated-at."
  ([state]
   (marks state :clips :start-time [:duration :title]))
  ([state id]
   (marks state :clips :start-time [:duration :title] id)))

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
  "The rating that the listener gave the episode `id` in `state`, or nil,
  in the scale of `opts` if it gives one.

  It's in the scale that it was given in. A :scale in `opts` converts it,
  e.g. a 4 of 1 to 5 is 75.25 of 1 to 100."
  ([state id]
   (rating state id {}))
  ([state id {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [removed?] n :rating :as e} (get-in state [:ratings id])]
     (when (and (number? n) (not removed?))
       (if scale
         (rescaled n (scale-of e) (checked-scale scale))
         n)))))

(defn rated
  "The episodes of `state` that have a rating, the latest rated first, in
  the scale of `opts` if it gives one.

  Each is a map of :id, :rating, the :worst and :best of its scale, and
  :updated-at. A :scale in `opts` converts the ratings as rating does."
  ([state]
   (rated state {}))
  ([state {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (mapv (fn [id]
           (let [e (get-in state [:ratings id])]
             (clojure.core/merge {:id id :rating (rating state id opts)}
                                 (if scale (checked-scale scale) (scale-of e))
                                 {:updated-at (:updated-at e)})))
         (live-by-stamp (:ratings state)))))

(defn rate-episode
  "Give the episode `id` in `state` the rating `n` at `at`, in the scale
  of `opts`, replacing any earlier rating.

  The rating must be within rating-scale, or the :scale in `opts`, or
  nothing changes. The state keeps the scale with the rating, so another
  app can read it in its own scale. Not to be confused with rate, the
  speed of playback."
  ([state id n at]
   (rate-episode state id n at {}))
  ([state id n at {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (let [scale (checked-scale (or scale rating-scale))
         e     (get-in state [:ratings id])
         same? #(and (not (:removed? e)) (number? (:rating e)) (== n (:rating e)) (= scale (scale-of e)))]
     (if (and (rating? n scale) (not (same?)))
       (write state [:ratings id] (constantly (assoc scale :rating n :removed? false)) at)
       state))))

(defn unrate-episode
  "Take the rating off the episode `id` in `state` at `at`, leaving a
  tombstone."
  [state id at]
  (if (rating state id)
    (write state [:ratings id] #(assoc % :removed? true) at)
    state))

(defn ^:no-doc ranked
  "The live `entries` of a ranked map such as the queue, as [id entry]
  pairs by rank, and by stamp and device when two ranks are equal."
  [entries]
  (sort-by (fn [[_ {:keys [rank updated-at device]}]] [rank (inst-ms updated-at) (str device)])
           (remove (comp :removed? val) entries)))

(defn queue
  "The episode ids queued in `state`, the current one first."
  [state]
  (mapv key (ranked (:queue state))))

(defn current
  "The episode id at the front of the queue of `state`, or nil when it's
  empty."
  [state]
  (first (queue state)))

(defn queued?
  "Whether the episode `id` is in the queue of `state`."
  [state id]
  (let [e (get-in state [:queue id])]
    (boolean (and e (not (:removed? e))))))

(defn in-inbox?
  "Whether the episode `id` is in the inbox of `state`, where refresh put
  it, and the listener hasn't dismissed, queued or played it since."
  [state id]
  (let [e (get-in state [:inbox id])]
    (boolean (and e (not (:removed? e)) (not (queued? state id)) (not (played? state id))))))

(defn inbox
  "The episode ids in the inbox of `state`, the latest to arrive first."
  [state]
  (filterv #(in-inbox? state %) (live-by-stamp (:inbox state))))

(defn dismiss
  "Take the episode `id` out of the inbox of `state` at `at`, leaving a
  tombstone."
  [state id at]
  (if-let [writes (out-of-inbox state id)]
    (write-each state writes at)
    state))

(defn ^:no-doc rank-between
  "A rank between the entries `before` and `after` of the ranked
  `entries`, either nil at an end, and after any entries that share the
  rank of `before`."
  [entries before after]
  (let [a (some->> before (get entries) :rank)
        b (when after
            (->> (drop-while #(not= after (key %)) (ranked entries))
                 (map (comp :rank val))
                 (filter #(or (nil? a) (pos? (compare % a))))
                 first))]
    (rank/between a b)))

(defn ^:no-doc last-rank
  "The greatest rank among the live `entries`, or nil, found in one pass
  so that appending many entries doesn't sort them each time."
  [entries]
  (reduce-kv (fn [r _ {:keys [rank removed?]}] (if (and (not removed?) (pos? (compare rank r))) rank r))
             nil
             entries))

(defn ^:no-doc rank-at
  "The rank that moves the entry `id` to `index` among the ranked
  `entries`. An index that isn't whole is rounded down, and NaN counts
  as 0."
  [entries id index]
  (let [others (vec (remove #{id} (map key (ranked entries))))
        index  (long (max 0 (min (if (== index index) index 0) (count others))))]
    (rank-between entries (get others (dec index)) (get others index))))

(defn ^:no-doc place
  "The `state` with the episode `id` queued at `rank` at `at`, and out of
  the inbox."
  [state id rank at]
  (write-each state (into [[[:queue id] (constantly {:rank rank :removed? false})]] (out-of-inbox state id)) at))

(defn enqueue
  "Put the episode `id` at the end of the queue of `state` at `at`, unless
  it is in the queue."
  [state id at]
  (if (queued? state id)
    state
    (place state id (rank/between (last-rank (:queue state)) nil) at)))

(defn play-next
  "Put the episode `id` right after the current episode of `state` at `at`,
  moving it there when it's queued, or to the front of an empty queue."
  [state id at]
  (if (= id (current state))
    state
    (let [[first-id second-id] (remove #{id} (queue state))]
      (place state id (rank-between (:queue state) first-id second-id) at))))

(defn dequeue
  "Take the episode `id` out of the queue of `state` at `at`, leaving a
  tombstone, so that it outlives an older addition on another device."
  [state id at]
  (if (queued? state id)
    (write state [:queue id] #(assoc % :removed? true) at)
    state))

(defn move
  "Move the queued episode `id` to position `index` of the queue of `state`
  at `at`. An index that isn't whole is rounded down, and NaN counts as 0."
  [state id index at]
  (if-not (queued? state id)
    state
    (place state id (rank-at (:queue state) id index) at)))

(defn advance
  "Drop the current episode from the queue of `state` at `at`, so that the
  next one is current."
  [state at]
  (if-let [id (current state)]
    (dequeue state id at)
    state))

(defn playlist
  "The episode ids in the playlist `name` of `state`, in order. A playlist
  is kept beside the queue, so to play it, queue its episodes."
  [state name]
  (mapv key (ranked (get-in state [:playlists name]))))

(defn playlists
  "The episode ids of each playlist of `state`, by name, leaving out the
  playlists without episodes."
  [state]
  (into {} (keep (fn [[name entries]] (when-some [ids (not-empty (mapv key (ranked entries)))] [name ids])))
        (:playlists state)))

(defn ^:no-doc appended
  "The writes that put the episodes `ids`, in order, at the end of the
  ranked entries at `path` in `state`, e.g. the queue or a playlist.

  An episode that's already there stays where it is. The ranks come from
  rank/after, which keeps them short for many episodes. They're in a block
  of the state's device, so that episodes that another device appends at
  the same time don't mix with them."
  [state path ids]
  (let [entries (get-in state path)
        held?   (fn [id] (let [e (get entries id)] (and e (not (:removed? e)))))
        fresh   (remove held? (distinct ids))]
    (map (fn [id rank] [(conj path id) (constantly {:rank rank :removed? false})])
         fresh
         (rank/after (last-rank entries) (count fresh) (:device state)))))

(defn ^:no-doc append
  "The `state` with the episodes `ids` at the end of the ranked entries at
  `path`, written at `at` as appended gives them."
  [state path ids at]
  (if-let [writes (seq (appended state path ids))]
    (write-each state writes at)
    state))

(defn add-to-playlist
  "Put the episode `id` at the end of the playlist `name` of `state` at
  `at`, unless the playlist holds it."
  [state name id at]
  (append state [:playlists name] [id] at))

(defn remove-from-playlist
  "Take the episode `id` out of the playlist `name` of `state` at `at`,
  leaving a tombstone."
  [state name id at]
  (let [e (get-in state [:playlists name id])]
    (if (and e (not (:removed? e)))
      (write state [:playlists name id] #(assoc % :removed? true) at)
      state)))

(defn move-in-playlist
  "Move the episode `id` to position `index` of the playlist `name` of
  `state` at `at`, as move moves an episode in the queue."
  [state name id index at]
  (let [entries (get-in state [:playlists name])
        e       (get entries id)]
    (if (and e (not (:removed? e)))
      (write state [:playlists name id] (constantly {:rank (rank-at entries id index) :removed? false}) at)
      state)))

(defn rename-playlist
  "Move the episodes of the playlist `from` of `state` to the end of the
  playlist `to` at `at`, or delete the playlist when `to` is nil.

  The episodes keep their order, and an episode that both playlists hold
  stays where `to` has it."
  [state from to at]
  (let [ids (playlist state from)]
    (if (or (empty? ids) (= from to))
      state
      (write-each state
                  (concat (for [id ids] [[:playlists from id] #(assoc % :removed? true)])
                          (when to (appended state [:playlists to] ids)))
                  at))))

(defn to-list-feed
  "The playlist `name` of `state` as a list feed of the podcast namespace,
  for dk.simongray.podcast/emit, with the `opts` below.

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
  ([state name]
   (to-list-feed state name {}))
  ([state name {:keys [url link description medium] :or {medium "podcastL"} :as opts}]
   {:pre [(map? opts)]}
   (let [subs (subscriptions state)]
     (cond-> {:rss/version          "2.0"
              :rss/title            name
              :rss/description      (or description name)
              :podcast/medium       medium
              :podcast/remote-items (vec (for [[feed-guid item-guid] (playlist state name)
                                               :let [[feed-url] (http/split-credentials (get-in subs [feed-guid :url]))]]
                                           (cond-> {:feed-guid feed-guid :item-guid item-guid}
                                             feed-url (assoc :feed-url feed-url))))}
       (or link url) (assoc :rss/link (or link url))
       url           (assoc :podcast/guid (guid/from-url url))))))

(defn from-list-feed
  "Add the episodes of the list feed `feed` to the end of the playlist
  `name` of `state` at `at`, in the order of the feed."
  [state name feed at]
  (append state
          [:playlists name]
          (for [{:keys [feed-guid item-guid]} (:podcast/remote-items feed)
                :when (and feed-guid item-guid)]
            [feed-guid item-guid])
          at))

(defn new-episodes
  "The ids of the items that the parse `after` of the feed at `url` has and
  the parse `before` hadn't, oldest first.

  Without `before`, there are none, since a first parse finds the back
  catalogue, not new episodes."
  [before after url]
  (if (nil? before)
    []
    (let [feed-guid     (feed/feed-guid after url)
          dates         (keep #(let [d (:rss/pub-date %)] (when (inst? d) (inst-ms d))) (:rss/items after))
          oldest-first? (< (or (first dates) 0) (or (last dates) 0))
          added         (:added (feed/diff before after))]
      (mapv #(vector feed-guid (feed/item-guid %))
            (if oldest-first? added (reverse added))))))

(defn refresh
  "Note in `state` at `at` a new parse `after` of the feed at `url`, which
  was `before`. The result is a map of the new :state and the episode ids
  it :added.

  The new episodes join the end of the queue with :auto-queue? in the
  settings of the subscription, or else the inbox with :inbox?. An
  episode that's already in the state stays as the listener left it,
  since another device can find it first."
  [state before after url at]
  (let [feed-guid (feed/feed-guid after url)
        added     (new-episodes before after url)
        {:keys [auto-queue? inbox?]} (settings state feed-guid)
        unknown   (remove #(or (played? state %) (contains? (:queue state) %) (contains? (:inbox state) %))
                          added)
        state     (cond
                    auto-queue? (append state [:queue] unknown at)
                    inbox?      (reduce #(write %1 [:inbox %2] (constantly {:removed? false}) at) state unknown)
                    :else       state)]
    {:state state :added added}))

(defn lists
  "The OPML lists that the listener follows in `state`, as a map from URL
  to :title and :folder. The folder is the one that the shows of the list
  go into."
  [state]
  (into {} (keep (fn [[url e]] (when-not (:removed? e) [url (select-keys e [:title :folder])])))
        (:lists state)))

(defn follow-list
  "Follow the OPML list at `url` in `state` at `at`, with the :title and
  the :folder for its shows in `details`, either of which can be nil.

  Fetch the list and give each new parse to refresh-list. Following a
  list again changes its title and folder."
  [state url {:keys [title folder] :as details} at]
  (write state [:lists url] (constantly {:title title :folder folder :removed? false}) at))

(defn unfollow-list
  "Stop following the OPML list at `url` in `state` at `at`, leaving a
  tombstone. The subscriptions that the list brought stay."
  [state url at]
  (if (contains? (lists state) url)
    (write state [:lists url] #(assoc % :removed? true) at)
    state))

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
  "The shows of the OPML `list` that join `state`, as pairs of feed guid
  and outline in the order of the list. The `names` are as by-url gives
  them, and a show that the list had before, in `was`, doesn't join."
  [state names was list]
  (:joining (reduce (fn [{:keys [names joined] :as acc} {:keys [xml-url] :as outline}]
                      (let [g (named names outline)]
                        (if (or (was g) (get-in state [:subscriptions g]) (joined g))
                          acc
                          (-> acc
                              (assoc-in [:names (normal-url xml-url)] g)
                              (update :joined conj g)
                              (update :joining conj [g outline])))))
                    {:names names :joined #{} :joining []}
                    (outlines list))))

(defn refresh-list
  "Note in `state` at `at` a new parse `after` of the OPML list at `url`,
  which was `before`. The result is a map of the new :state, the feed
  guids it :added, and those that :left.

  Shows that joined the list are subscribed to, in its folder, unless the
  state has them already by feed URL or guid. Without `before`, every
  show counts as joined. A show that left the list stays a subscription,
  so that you can ask the listener about it. Nothing changes for a list
  that the listener doesn't follow."
  [state url before after at]
  (if-let [{:keys [folder]} (get (lists state) url)]
    (let [names (by-url state)
          guids (fn [list] (map #(named names %) (outlines list)))
          is    (set (guids after))
          joins (joining state names (set (guids before)) after)
          join  (fn [state [g {:keys [xml-url title text]}]]
                  (cond-> (subscribe state g {:url xml-url :title (or title text)} at)
                    folder (configure g {:folder folder} at)))]
      {:state (at-once state at #(reduce join % joins))
       :added (mapv first joins)
       :left  (into [] (comp (distinct) (remove is) (filter #(subscribed? state %))) (guids before))})
    {:state state :added [] :left []}))

(defn to-opml
  "The live subscriptions of `state` as a subscription list for
  dk.simongray.podcast.format.opml/emit.

  The folders come first, then the shows in no folder, and the followed
  lists are includes. Each outline has the guid of its feed, the tags as
  categories, and the time of subscribing as its creation time. A comma in
  a tag is written as an underscore, since OPML separates categories with
  commas."
  [state]
  (let [subs (subscriptions state)
        ls   (lists state)]
    (cond-> {:title         "Subscriptions"
             :subscriptions (vec (for [[folder feed-guids] (sort-by (fn [[folder _]] [(nil? folder) (str folder)]) (folders state))
                                       feed-guid feed-guids
                                       :let [{:keys [url title added-at]} (get subs feed-guid)
                                             ts (tags state feed-guid)]]
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
  [{:keys [state names] :as acc} {:keys [xml-url text title category created] :as outline} folder at]
  (let [g       (named names outline)
        known   (when (get names (normal-url xml-url)) (get-in state [:subscriptions g :url]))
        details {:url (or known xml-url) :title (or title text) :added-at (when (inst? created) created)}]
    {:names (assoc names (normal-url xml-url) g)
     :state (as-> (subscribe state g details at) state
              (if folder (configure state g {:folder folder} at) state)
              (reduce #(tag %1 g %2 at) state (when (sequential? category) category)))}))

(defn from-opml
  "Subscribe `state` at `at` to each feed of the OPML subscription `list`
  from dk.simongray.podcast.format.opml/parse, with `opts`.

  Each subscription gets the title, the innermost folder, the categories as
  tags and the creation time of its outline. The state also follows the
  lists that the OPML includes, and a feed that the state has already
  keeps its guid and URL.

  A folder around every feed whose name is in wrappers counts as no
  folder. The :wrappers of `opts` replaces those names with a set of
  lower-case names or a predicate."
  ([state list at]
   (from-opml state list at {}))
  ([state list at opts]
   {:pre [(map? opts)]}
   (let [around  (wrapper list (:wrappers opts wrappers))
         folder  (fn [path] (last (if (and around (= around (first path))) (rest path) path)))
         feeds   (fn [state]
                   (:state (reduce #(imported %1 %2 (folder (:path %2)) at)
                                   {:state state :names (by-url state)}
                                   (outlines list))))
         include (fn [state {:keys [url text path]}]
                   (if (contains? (lists state) url)
                     state
                     (follow-list state url {:title text :folder (folder path)} at)))]
     (at-once state at #(reduce include (feeds %) (:includes list))))))

(defn- key-order
  "A comparison of the keys `a` and `b` of mixed types: keywords first, as
  compare orders them, then any other key by its printed form."
  [a b]
  (if (and (keyword? a) (keyword? b))
    (compare a b)
    (compare [(if (keyword? a) 0 1) (pr-str a)] [(if (keyword? b) 0 1) (pr-str b)])))

(defn- in-order
  "The value `x` in the form that printed prints, with maps and sets sorted
  by key-order, and a float with no fraction as a whole number."
  [x]
  (cond
    (map? x)   (into (sorted-map-by key-order) x)
    (set? x)   (into (sorted-set-by key-order) x)
    (float? x) (or (json/whole x) x)
    :else      x))

(defn- printed
  "The entry `e` printed with each value as in-order gives it, so that
  the string is the same in Clojure and ClojureScript."
  [e]
  (pr-str (walk/postwalk in-order e)))

(defn- later
  "The later of the stamped entries `a` and `b`.

  On the same instant, the greater device id wins, and then the greater
  printed entry, so that every device picks the same one on either
  platform. Equal entries aren't printed, since two states that agree tie
  on all of them."
  [a b]
  (let [ms (fn [x] (or (some-> (:updated-at x) inst-ms) 0))]
    (cond
      (nil? a)               b
      (nil? b)               a
      (> (ms a) (ms b))      a
      (< (ms a) (ms b))      b
      (= a b)                a
      :else                  (if (pos? (compare [(str (:device a)) (printed a)]
                                                [(str (:device b)) (printed b)]))
                               a
                               b))))

(defn- higher
  "Of the listening counts `a` and `b` of one device, the one with more
  seconds, or else the later. A count only grows, so the higher count
  includes the lower one."
  [a b]
  (let [seconds #(let [n (:seconds %)] (if (number? n) n 0))]
    (cond
      (> (seconds a) (seconds b)) a
      (< (seconds a) (seconds b)) b
      :else                       (later a b))))

(defn- join
  "The merge of the values `a` and `b` under one key of two states, by
  their shape, as merge describes it. Of two stamped entries, it's the one
  that `pick` returns."
  [pick a b]
  (cond
    (nil? a)                                                          b
    (nil? b)                                                          a
    (and (stamped? a) (stamped? b))                                   (pick a b)
    (and (map? a) (map? b) (not (stamped? a)) (not (stamped? b)))     (merge-with #(join pick %1 %2) a b)
    (and (map? a) (map? b))                                           (pick a b)
    (and (inst? a) (inst? b))                                         (later-inst a b)
    :else                                                             a))

(defn merge
  "Reconcile the states `a` and `b` of a listener's devices, key by key, by
  the shape of each value:

  - a stamped entry is a last-writer-wins register, and the later entry
    wins whole, even when it's a tombstone
  - a map of entries merges entry by entry, down through nested maps
  - an instant is a max register
  - any other value is that of `a`, which is why problem names one

  The merge is commutative, associative and idempotent, so it's safe to
  apply in any order and any number of times. Of two listening counts of
  one device, it keeps the higher."
  [a b]
  (reduce (fn [m k] (assoc m k (join (if (= :listened k) higher later) (get a k) (get b k))))
          {:device (:device a)}
          (disj (into (set (keys a)) (keys b)) :device)))

(def ^:no-doc collections
  "The keys of a state that hold maps of entries, in the order problem
  checks them. Each has the depth of its entries, e.g. 2 for bookmarks,
  which are by episode and second."
  [[:subscriptions 1] [:settings 2] [:tags 2] [:lists 1] [:episodes 1] [:played 1] [:queue 1]
   [:playlists 2] [:inbox 1] [:stars 1] [:bookmarks 2] [:clips 2] [:ratings 1]
   [:filters 1] [:listened 3] [:preferences 1] [:devices 1] [:retired 1]])

(defn- clock-problem
  [state]
  (when-some [clock (:clock state)]
    (when-not (inst? clock)
      (str "the clock is " (pr-str clock) ", not an instant"))))

(defn- entry-problem
  "A description of the first value in `v` at `path` that isn't a map, or
  of the first entry without a stamp. The entries are `depth` keys below
  `v`."
  [path depth v]
  (cond
    (not (map? v))     (str "the entry at " (pr-str path) " is not a map")
    (pos? depth)       (some (fn [[k x]] (entry-problem (conj path k) (dec depth) x)) v)
    (not (stamped? v)) (str "the entry at " (pr-str path) " is not stamped")))

(defn- collection-problem
  "A description of the first collection of `state` that isn't a map, or
  of the first problem entry-problem finds in one."
  [state]
  (some (fn [[k depth]]
          (when-some [entries (get state k)]
            (if (map? entries)
              (entry-problem [k] depth entries)
              (str k " is not a map"))))
        collections))

(defn- value-problem
  "A description of the first value in `v`, at `path`, that is neither a
  map nor in a stamped entry. Merge keeps each device's own such value, so
  the devices never agree on it."
  [path v]
  (cond
    (or (nil? v) (stamped? v)) nil
    (map? v)                   (some (fn [[k x]] (value-problem (conj path k) x)) v)
    :else                      (str "the value at " (pr-str path) " is not in a stamped entry")))

(defn- stamp-problem
  "A description of the first entry in `v`, at `path`, whose stamp isn't
  an instant."
  [path v]
  (cond
    (stamped? v) (when-not (inst? (:updated-at v))
                   (str "the entry at " (pr-str path) " is stamped " (pr-str (:updated-at v))
                        ", not an instant"))
    (map? v)     (some (fn [[k x]] (stamp-problem (conj path k) x)) v)
    :else        nil))

(defn- rank-problem
  "A description of the first rank in the queue or a playlist of `state`
  that isn't a rank, which would make ranked throw."
  [state]
  (some (fn [[where entries]]
          (some (fn [[id {:keys [rank]}]]
                  (when (and (some? rank) (not (rank/rank? rank)))
                    (str "the rank of " (pr-str id) " in " where " is " (pr-str rank) ", not a rank")))
                entries))
        (cons ["the queue" (:queue state)]
              (for [[name entries] (:playlists state)]
                [(str "the playlist " (pr-str name)) entries]))))

(defn- seen-problem
  [state]
  (some (fn [[id {:keys [seen]}]]
          (when (and (some? seen) (not (and (map? seen) (every? inst? (vals seen)))))
            (str "what device " (pr-str id) " has seen is not a map of instants")))
        (:devices state)))

(defn- key-problem
  "A description of the first key of `state` of the wrong type for its
  collection. Lists and tags have strings as keys, and bookmarks and clips
  whole seconds."
  [state]
  (or (some (fn [url]
              (when-not (string? url)
                (str "the list at " (pr-str url) " is not at a URL")))
            (keys (:lists state)))
      (some (fn [[what k]]
              (some (fn [[id entries]]
                      (when-not (every? #(and (integer? %) (<= 0 %)) (keys entries))
                        (str "a " what " of " (pr-str id) " is not at a whole number of seconds")))
                    (get state k)))
            [["bookmark" :bookmarks] ["clip" :clips]])
      (some (fn [[feed-guid ts]]
              (when-not (every? string? (keys ts))
                (str "a tag of " (pr-str feed-guid) " is not a string")))
            (:tags state))))

(defn- mark-problem
  "A description of the first live rating of `state` outside its scale,
  or of the first live clip whose duration isn't a number of seconds."
  [state]
  (or (some (fn [[id {:keys [rating removed?] :as e}]]
              (when-not (or removed? (rating? rating (scale-of e)))
                (str "the rating of " (pr-str id) " is " (pr-str rating)
                     ", not a number of the scale " (pr-str (scale-of e)))))
            (:ratings state))
      (some (fn [[id entries]]
              (some (fn [[start {:keys [duration removed?]}]]
                      (when-not (or removed? (and (number? duration) (pos? duration)))
                        (str "the clip of " (pr-str id) " at " start " lasts " (pr-str duration)
                             ", not a number of seconds")))
                    entries))
            (:clips state))))

(defn- count-problem
  [state]
  (some (fn [[feed-guid months]]
          (when-not (every? #(number? (:seconds % 0)) (mapcat vals (vals months)))
            (str "the listening of " (pr-str feed-guid) " is not counted in seconds")))
        (:listened state)))

(defn- criteria-problem
  [state]
  (some (fn [[name {:keys [criteria]}]]
          (when-not (or (nil? criteria) (map? criteria))
            (str "the criteria of the filter " (pr-str name) " are not a map")))
        (:filters state)))

(def ^:no-doc checks
  "The checks of problem, in order. Each takes a state that is a map and
  returns a description of the first problem of its kind, or nil, and
  relies on the checks before it."
  [clock-problem collection-problem #(value-problem [] (dissoc % :device :clock)) #(stamp-problem [] %)
   rank-problem seen-problem key-problem mark-problem count-problem criteria-problem])

(defn problem
  "A description of what in `state` breaks the rules that merge and the
  functions here rely on, or nil.

  A merge spreads the values of another device to every device, where a
  bad value makes a function throw, or two devices disagree. The rules:

  - The state and its collections are maps, and the clock is an instant.
  - Besides the device and the clock, the state holds only maps, down to
    stamped entries.
  - Each entry of a collection has a stamp, which is an instant.
  - Each rank in the queue and in a playlist is a rank.
  - What a device has seen is a map of instants.
  - A list is at a URL, a tag is a string, and a bookmark and a clip are
    at a whole number of seconds.
  - A rating is a number of its scale, and a clip lasts a number of
    seconds.
  - A listening count is a number of seconds.
  - The criteria of a saved filter are a map.

  Saved filters from a later version are fine here, and
  app.search/problem says whether this version understands them."
  [state]
  (if (map? state)
    (some #(% state) checks)
    "the state is not a map"))

(defn- changed-part
  "The part of `v` that isn't in `was`, the value at the same place in an
  earlier state, or nil when nothing changed."
  [v was]
  (cond
    (stamped? v) (when (not= v was) v)
    (map? v)     (let [m (into {} (keep (fn [[k x]] (when-some [y (changed-part x (get was k))] [k y]))) v)]
                   (when (seq m) m))
    :else        nil))

(defn changes-since
  "The part of `state` that isn't in `previous`, or all of it when
  `previous` is nil, as a partial state that merge takes.

  It's every entry written or merged since, however early its stamp, which
  is what a sync pushes to a service that wants changes rather than the
  whole state."
  [state previous]
  (into (select-keys state [:device :clock])
        (keep (fn [[k v]]
                (when-not (#{:device :clock} k)
                  (when-some [w (changed-part v (get previous k))]
                    [k w]))))
        state))

(defn ^:no-doc stepped
  "The result of step for `state`, its `effects`, and the sleep timer
  `sleep`, if one runs."
  [state effects sleep]
  (cond-> {:state state :effects effects}
    sleep (assoc :sleep sleep)))

(defn ^:no-doc ended
  "The result of step for `state` when the episode `id` ended at `at`,
  with the sleep timer `sleep`.

  The episode counts as played and leaves the queue if it was current,
  with the effect [:play next] when another episode is queued. With a
  sleep timer, one episode less is left to play, and when none is left,
  the effect is [:stop id] and the timer stops."
  [state id at sleep]
  (let [state'   (mark-played state id at)
        current? (= id (current state'))
        state'   (if current? (advance state' at) state')
        next     (when current? (current state'))
        left     (some-> (:episodes sleep) dec)]
    (if (and left (not (pos? left)))
      (stepped state' [[:stop id]] nil)
      (stepped state' (if next [[:play next]] []) (cond-> sleep left (assoc :episodes left))))))

(defn- moved
  "The result of step, with the `effects` and the sleep timer `sleep`,
  when the episode `id` moved at `at` from its position in `state` to
  that in `state'`.

  The result has `state'` and the `effects`, then [:played id] if the
  episode now counts as played. When the position moved into the outro,
  [:stop id] replaces the `effects`, and ended ends the episode with the
  sleep timer."
  [state state' id effects at sleep]
  (let [was       (played? state id)
        start     (outro-start state' (first id) id)
        in-outro? #(let [p (:position (episode % id))]
                     (and start (number? p) (>= p start)))]
    (if (and (in-outro? state') (not (in-outro? state)))
      (update (ended state' id at sleep) :effects
              #(into (cond-> [[:stop id]] (not was) (conj [:played id]))
                     (remove #{[:stop id]})
                     %))
      (stepped state'
               (cond-> effects (and (not was) (played? state' id)) (conj [:played id]))
               sleep))))

(defn step
  "Advance `state` by the player `event` at `at` with `opts`, returning a
  map of the new :state and the :effects, which say what the UI should do
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
  ([state event at]
   (step state event at {}))
  ([state [kind id & args :as event] at {:keys [sleep] :as opts}]
   {:pre [(map? opts)]}
   (case kind
     :skip     (let [[amount] args
                     {:keys [skip-forward skip-back]} (preferences state)
                     seconds (case amount :forward skip-forward :back (- skip-back) amount)
                     state'  (skip state id seconds at (select-keys opts [:played-threshold]))]
                 (moved state state' id [[:seek id (:position (episode state' id))]] at sleep))
     :chapter  (let [[direction doc] args
                     position (:position (episode state id))
                     from     (if (number? position) position 0)]
                 (if-let [target (chapters/jump doc from direction (select-keys opts [:restart]))]
                   (step state [:skip id (- target from)] at opts)
                   (stepped state [] sleep)))
     :progress (let [[position duration] args
                     state' (-> state
                                (heard id position at (:listening-gap opts listening-gap))
                                (progress id position duration at (select-keys opts [:played-threshold])))]
                 (moved state state' id [] at sleep))
     :ended    (ended state id at sleep)
     :play     (stepped (place state id (rank-between (:queue state) nil (first (remove #{id} (queue state)))) at)
                        []
                        sleep)
     (stepped state [] sleep))))

(comment
  (def sample
    (-> (state "laptop")
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

  ;; nil for a state that keeps the rules, and the first rule that it breaks
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
     (-> (state (str (random-uuid)))
         (identify {:name "laptop"} #inst "2026-01-01")
         (subscribe "feed-1" {:url "https://a/rss" :title "A"} #inst "2026-01-01")
         (enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
         (progress ["feed-1" "ep-1"] 120 3600 #inst "2026-01-01T02:00:00Z"))
     (after *1 #inst "2026-01-01")
     #_.))
