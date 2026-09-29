(ns dk.simongray.podcast.app.player
  "What a podcast player keeps between plays, as plain data with pure
  operations over it, for any UI to build on.

  The state holds the play queue, the progress and the played mark of each
  episode, the subscriptions with their settings, and the devices of the
  listener. `merge` reconciles the states of the devices of one listener,
  which is the core of the sync.

  The state is one map:

      {:device        \"8f1c9e2a-…\"                 ; this device's id
       :clock         #inst \"…\"                    ; the latest instant written or merged
       :subscriptions {feed-guid {:url :title :added-at :removed?}}
       :settings      {feed-guid {:skip-intro {:value 20} ...}}
       :tags          {feed-guid {\"Tech\" {:removed? false}}}
       :lists         {url {:title :folder :removed?}}  ; OPML lists the listener follows
       :episodes      {[feed-guid item-guid] {:position :duration :played? :played-at}}
       :queue         {[feed-guid item-guid] {:rank \"V\" :removed? false}}
       :playlists     {name {[feed-guid item-guid] {:rank \"V\" :removed? false}}}
       :inbox         {[feed-guid item-guid] {:removed? false}}
       :stars         {[feed-guid item-guid] {:removed? false}}
       :bookmarks     {[feed-guid item-guid] {754 {:title :removed?}}}
       :clips         {[feed-guid item-guid] {754 {:duration :title :removed?}}}
       :ratings       {[feed-guid item-guid] {:rating 4 :worst 1 :best 5 :removed?}}
       :filters       {name {:criteria {:where {:status :unplayed}} :removed?}}
       :listened      {feed-guid {\"2026-09\" {device-id {:seconds 5400.0}}}}
       :preferences   {:skip-forward {:value 30} ...}
       :devices       {device-id {:name :platform ... :seen {device-id #inst \"…\"}}}
       :retired       {device-id {}}}

  Each write takes the instant `at` of the write, and reads no clock. It
  stamps what it changed with the `:device` of the state, and with `at` as
  `after` adjusts it.

  The state is a state-based CRDT. A stamped entry is a last-writer-wins
  register, and a map of entries is an add-and-remove set with
  tombstones. The clock is a max register, and `merge` is the join. The
  queue ranks its entries with `dk.simongray.podcast.rank`, so that what
  two devices queued while apart both survive. The registry entry of each
  device tells what the device merged. So `compact` can drop the
  tombstones that every device saw. doc/sync.md is the design.

  Nothing here stores data or touches a media element. The app owns
  those and calls in. `dk.simongray.podcast.app.sync` carries the state
  between devices.

  Identities are those of `dk.simongray.podcast.feed`: a feed by its
  `podcast:guid`, and an episode by `[feed-guid item-guid]`."
  (:refer-clojure :exclude [merge])
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.rank :as rank]
            [dk.simongray.podcast.time :as time]))

(defn state
  "A fresh player state for the device `device`: a stable id that tells one
  listener's devices apart when their states are merged, such as `(str
  (random-uuid))` generated once per installation and kept by the app."
  [device]
  {:device device :subscriptions {} :episodes {} :queue {}})

(defn- stamp
  "`entry` marked as changed at `at` on the device of `state`."
  [state entry at]
  (assoc entry :updated-at at :device (:device state)))

(defn- stamped?
  "Whether `x` is an entry a write stamped."
  [x]
  (and (map? x) (contains? x :updated-at)))

(defn- later-inst
  "The later of the instants `a` and `b`, either of which may be nil."
  [a b]
  (cond
    (nil? a)                        b
    (nil? b)                        a
    (>= (inst-ms a) (inst-ms b))    a
    :else                           b))

(defn after
  "The instant a write at `now` is stamped with: `now`, or the millisecond
  after the state's clock when that is at or past `now`, so that a device
  whose clock runs behind still writes later than what it has merged, the
  way a hybrid logical clock does. Every write applies this itself, `write`
  too, which an app uses for entries of its own, since it also moves the
  clock on. The instant is the platform's date whatever `now` is, so that
  a document prints it as `#inst`."
  [state now]
  (let [clock (:clock state)]
    (if (and clock (>= (inst-ms clock) (inst-ms now)))
      (time/->inst (inc (inst-ms clock)))
      (time/date now))))

(defn- write-each
  "`state` with every `[path f]` of `writes` applied, the entry at `path`
  replaced by `(f entry)` and stamped as changed on this device at `at` as
  `after` adjusts it, which then becomes the state's clock."
  [state writes at]
  (let [at (after state at)]
    (-> (reduce (fn [s [path f]] (update-in s path #(stamp state (f %) at))) state writes)
        (assoc :clock at))))

(defn write
  "`state` with the entry at `path` replaced by `(f entry)` and stamped as
  changed on this device at `at`, as `after` adjusts it, the clock moved on
  to that instant: how every operation here writes, and how an app writes
  entries of its own, so that two of them are never stamped alike and
  `merge` reconciles them like the rest."
  [state path f at]
  (write-each state [[path f]] at))

(defn episode-id
  "The identity of `item` in `feed`, fetched from `url`: the feed's guid and
  the item's."
  [feed url item]
  [(feed/feed-guid feed url) (feed/item-guid item)])

(def default-settings
  "The settings of a subscription until `configure` changes them.

  `:auto-queue?` tells whether new episodes join the queue. `:inbox?`
  tells whether they join the inbox when they do not join the queue.
  `:skip-intro` and `:skip-outro` are the seconds to skip at the start and
  at the end. `:folder` is the name of the folder that the show is in."
  {:auto-queue? false :inbox? false :skip-intro 0 :skip-outro 0 :folder nil})

(defn subscribed?
  "Whether `state` holds a live subscription to `feed-guid`."
  [state feed-guid]
  (let [sub (get-in state [:subscriptions feed-guid])]
    (boolean (and sub (not (:removed? sub))))))

(defn subscriptions
  "The live subscriptions of `state`, by feed guid; the tombstones of
  removed ones left out."
  [state]
  (into {} (remove (comp :removed? val)) (:subscriptions state)))

(defn ^:no-doc normal-url
  "`url` as a feed or a media file is known by, whatever its scheme, its
  case and its trailing slashes, and without the user name and the
  password that it holds."
  [url]
  (-> (str url) http/split-credentials first guid/guid-name str/lower-case))

(defn ^:no-doc by-url
  "The feed guids of the subscriptions of `state`, the removed ones too, by
  their URLs as `normal-url` gives them.

  Of the subscriptions with one URL, a live one names the feed before a
  removed one, and then the one that changed last."
  [state]
  (->> (:subscriptions state)
       (filter (comp :url val))
       (sort-by (fn [[g {:keys [removed? updated-at]}]]
                  [(if removed? 0 1) (or (some-> updated-at inst-ms) 0) (str g)]))
       (into {} (map (fn [[g e]] [(normal-url (:url e)) g])))))

(defn subscribe
  "Subscribe `state` to the feed `feed-guid` at `at`. The map gives the
  `:url` and the `:title` of the feed.

  The settings of an earlier subscription to the feed still hold. For a
  subscription that is new, `:added-at` gives another time that the
  listener subscribed, as an OPML file from another app can."
  [state feed-guid {:keys [url title added-at]} at]
  (write state [:subscriptions feed-guid]
         (fn [sub]
           (assoc (or sub {})
                  :url url :title title :removed? false
                  :added-at (if (subscribed? state feed-guid) (:added-at sub) (time/date (or added-at at)))))
         at))

(defn unsubscribe
  "Unsubscribe from `feed-guid` at `at`, leaving a tombstone, even where no
  subscription was known, so that the removal outlives an older addition on
  another device."
  [state feed-guid at]
  (write state [:subscriptions feed-guid] #(assoc (or % {}) :removed? true) at))

(defn- values
  "The `:value` of every entry of the map `entries`, by key."
  [entries]
  (into {} (map (fn [[k e]] [k (:value e)])) entries))

(defn settings
  "The settings of the subscription `feed-guid`, `default-settings` filled
  in."
  [state feed-guid]
  (clojure.core/merge default-settings (values (get-in state [:settings feed-guid]))))

(defn configure
  "Change the `settings` of the subscription `feed-guid` at `at`: the keys
  of `default-settings`, and `:rate`, a playback rate for this show over
  the listener's preferred one. Each setting is an entry of its own, so two
  devices changing different ones both have their way."
  [state feed-guid settings at]
  (if (get-in state [:subscriptions feed-guid])
    (write-each state (for [[k v] settings] [[:settings feed-guid k] (constantly {:value v})]) at)
    state))

(def default-preferences
  "How the listener likes to listen, until `prefer` says otherwise: the
  seconds a skip jumps forward and back, and the playback rate."
  {:skip-forward 30 :skip-back 15 :rate 1.0})

(defn preferences
  "The listener's preferences, `default-preferences` filled in."
  [state]
  (clojure.core/merge default-preferences (values (:preferences state))))

(defn prefer
  "Change the listener's `preferences` at `at`; the keys are those of
  `default-preferences`, each an entry of its own."
  [state preferences at]
  (write-each state (for [[k v] preferences] [[:preferences k] (constantly {:value v})]) at))

(defn rate
  "The playback rate for the feed `feed-guid`: its own setting, else the
  listener's preference. `rating` is what the listener thinks of an
  episode."
  [state feed-guid]
  (or (:rate (settings state feed-guid)) (:rate (preferences state))))

(defn folders
  "The live subscriptions of `state` by the folder of their settings, as
  `{folder [feed-guid ...]}`.

  The shows of each folder are in the order of their titles. The shows in
  no folder are under nil."
  [state]
  (reduce (fn [m [feed-guid _]] (update m (:folder (settings state feed-guid)) (fnil conj []) feed-guid))
          {}
          (sort-by (comp str :title val) (subscriptions state))))

(defn rename-folder
  "Move the shows of `state` in the folder `from` to the folder `to` at
  `at`. When `to` is nil, the shows go out of all folders."
  [state from to at]
  (if-let [feed-guids (get (folders state) from)]
    (write-each state (for [g feed-guids] [[:settings g :folder] (constantly {:value to})]) at)
    state))

(defn tags
  "The tags that the listener gave the show `feed-guid` in `state`, as a
  sorted set.

  A show has one folder, but it can have many tags. Two devices that add
  different tags at the same time both keep their tags."
  [state feed-guid]
  (into (sorted-set) (keep (fn [[t e]] (when-not (:removed? e) t))) (get-in state [:tags feed-guid])))

(defn tagged
  "The live subscriptions of `state` by tag, as `{tag [feed-guid ...]}`,
  each tag's shows in the order of their titles."
  [state]
  (reduce (fn [m [feed-guid _]] (reduce #(update %1 %2 (fnil conj []) feed-guid) m (tags state feed-guid)))
          {}
          (sort-by (comp str :title val) (subscriptions state))))

(defn tag
  "Give the show `feed-guid` the tag `t`, a string, at `at`, when `state`
  knows the subscription, as for `configure`."
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
  "Give the shows of `state` with the tag `from` the tag `to` in its place
  at `at`. When `to` is nil, take the tag off them."
  [state from to at]
  (if-let [feed-guids (get (tagged state) from)]
    (write-each state (for [g feed-guids
                            w (cond-> [[[:tags g from] #(assoc % :removed? true)]]
                                to (conj [[:tags g to] (constantly {:removed? false})]))]
                        w)
                at)
    state))

(defn filters
  "The saved filters of `state` by name: criteria for
  `dk.simongray.podcast.app.search/episodes`, such as `{:where {:status
  :unplayed} :order :newest}`."
  [state]
  (into {} (keep (fn [[name e]] (when-not (:removed? e) [name (:criteria e)]))) (:filters state)))

(defn save-filter
  "Save the `criteria` of `dk.simongray.podcast.app.search/episodes` in
  `state` under `name` at `at`, so that the other devices have the filter
  too.

  The criteria replace a filter of that name. `:player` and `:url` are
  left out, because they belong to the device. The criteria must be data,
  because the state is printed as EDN. So an order that is a function
  throws."
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
  "Describe this device in the registry of `state` at `at`: its `:name` for
  the listener, its `:platform`, or anything else an app wants to say about
  it, merged into what it said before. Every device describes only itself,
  so the entries never contend."
  [state description at]
  (write state [:devices (:device state)] #(clojure.core/merge % description) at))

(defn retire
  "Retire the device `device` at `at`, from any device: it is left out of
  `devices`, and a sync writes nothing for it and deletes its document. A
  retirement is a tombstone of its own, so that the device coming back
  cannot undo it by describing itself again."
  [state device at]
  (write state [:retired device] #(or % {}) at))

(defn retired?
  "Whether the device `device` has been retired."
  [state device]
  (contains? (:retired state) device))

(defn devices
  "The devices of `state` by id, as they described themselves with
  `identify`, the retired ones left out."
  [state]
  (into {} (remove (fn [[id _]] (retired? state id))) (:devices state)))

(defn- latest-by-author
  "The latest stamp by each device among the stamped entries of `state`,
  the registry left out, so that the registry's own updates never count as
  news to be acknowledged, which would keep two devices writing forever."
  [state]
  (letfn [(walk [m v]
            (cond
              (stamped? v) (update m (:device v) later-inst (:updated-at v))
              (map? v)     (reduce-kv (fn [m _ x] (walk m x)) m v)
              :else        m))]
    (reduce-kv (fn [m k v] (if (#{:device :clock :devices} k) m (walk m v))) {} state)))

(defn acknowledge
  "Note in the registry of `state` at `at` that this device has merged the
  whole states `others`: under `:seen` in its own entry, the latest instant
  each other device wrote anything now merged here, kept monotone, which is
  what `compact` judges by. Only whole states count, never a service's
  changes since a cursor, and the entry is written only when the knowledge
  grew, so that syncing alone never rewrites a document."
  [state others at]
  (let [me    (:device state)
        known (get-in state [:devices me :seen] {})
        seen  (dissoc (reduce (fn [m other] (merge-with later-inst m (latest-by-author other))) known others) me)]
    (if (and (= seen known) (get-in state [:devices me]))
      state
      (write state [:devices me] #(assoc (or % {}) :seen seen) at))))

(def default-keep
  "How long a tombstone is kept once every device has seen it: thirty days,
  in milliseconds, so that a device joining within that time with a state
  of its own still meets it."
  (* 30 24 60 60 1000))

(defn- seen-by-all?
  "Whether every live device other than the author has merged the entry `e`
  by its stamp, by the registry of `state`, this device counting as having
  merged what it holds; false when no such device exists, since a device
  alone gains nothing by forgetting and a device joining later could bring
  back what it forgot."
  [state {:keys [device updated-at]}]
  (let [t      (inst-ms updated-at)
        judges (remove (fn [[id _]] (= id device)) (devices state))]
    (and (seq judges)
         (every? (fn [[id entry]]
                   (or (= id (:device state))
                       (some-> (get-in entry [:seen device]) inst-ms (>= t))))
                 judges))))

(defn compact
  "Drop from `state` at `at` the tombstones that every live device merged,
  and that are older than the `:keep-for` of `opts`.

  The registry tells what each device merged (see `acknowledge`).
  `:keep-for` is in milliseconds (default `default-keep`). It is a buffer
  against a device that starts to sync with a state of its own.

  The tombstones are those of unsubscriptions, of lists that the listener
  follows no more, and of removals from the queue, the playlists and the
  inbox. They are also those of the tags, the stars, the bookmarks, the
  clips, the ratings and the saved filters that the listener took away.
  The played marks and the
  counts of listening stay, because the listener still sees them. A device
  that syncs no more holds back the compaction until it is retired (see
  `lagging`).

  The tombstones of the unsubscriptions stay while the listener follows a
  list. A list can name a show again at any time, and the tombstone tells
  `refresh-list` that the listener left the show."
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
  "The live devices other than this one that, by their registry entries,
  have not merged all that this one has: by id, the latest instant of
  another device's writing they have merged, nil when they have merged
  nothing of some device. Such a device holds `compact` back until it
  catches up or is retired."
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
  "How many seconds before the end of an episode count as having heard it."
  30)

(def listening-gap
  "How many seconds can pass between two reports of the position of an
  episode that are one stretch of listening. After a longer time, the
  listener paused, and `step` counts no listening for that time."
  60)

(defn episode
  "What `state` knows about the episode `id`, nil when nothing."
  [state id]
  (get-in state [:episodes id]))

(defn played?
  "Whether the episode `id` counts as played."
  [state id]
  (boolean (:played? (episode state id))))

(defn- heard-to-the-end?
  [position duration]
  (and (number? duration) (pos? duration) (number? position)
       (>= position (- duration played-threshold))))

(defn ^:no-doc out-of-inbox
  "The writes that take the episode `id` out of the inbox of `state`: a
  tombstone when it is there, else none."
  [state id]
  (let [e (get-in state [:inbox id])]
    (when (and e (not (:removed? e)))
      [[[:inbox id] #(assoc % :removed? true)]])))

(defn progress
  "Record in `state` that the listener is `position` seconds into the
  episode `id` at `at`, with its `duration` when that is known.

  An episode that the listener heard to within `played-threshold` seconds
  of its end counts as played, and it leaves the inbox.

  The entry keeps `at` as `:reported-at`, the time on the clock of the
  device. The stamp of the entry can be later than that time, so the
  counts of listening measure from `:reported-at`."
  ([state id position at]
   (progress state id position nil at))
  ([state id position duration at]
   (let [e (cond-> (assoc (or (episode state id) {}) :position position :reported-at (time/date at))
             duration (assoc :duration duration))
         e (if (and (not (:played? e)) (heard-to-the-end? position (:duration e)))
             (assoc e :played? true :played-at (time/date at))
             e)]
     (write-each state (into [[[:episodes id] (constantly e)]] (when (:played? e) (out-of-inbox state id))) at))))

(defn mark-played
  "Mark the episode `id` in `state` as played at `at`, which takes it out
  of the inbox."
  [state id at]
  (write-each state (into [[[:episodes id] #(assoc (or % {}) :played? true :played-at (time/date at))]]
                          (out-of-inbox state id))
              at))

(defn mark-unplayed
  "Mark the episode `id` in `state` as not played at `at`, with its
  position back at the start."
  [state id at]
  (write state [:episodes id]
         #(-> (or % {}) (assoc :played? false :position 0 :reported-at (time/date at)) (dissoc :played-at))
         at))

(defn skip
  "Move `seconds` forward, or back when negative, in the episode `id` at
  `at`, never before its start nor, when the duration is known, past its
  end. A duration of zero, or one that is not a finite number, as a player
  reports before it knows, is not known. The new position is in the state
  for the UI to seek to."
  [state id seconds at]
  (let [{:keys [position duration]} (episode state id)
        finite?  #(and (number? %) (== % %) (not (infinite? %)))
        target   (+ (if (finite? position) position 0) seconds)
        target   (max 0 (if (and (finite? duration) (pos? duration)) (min target duration) target))]
    (progress state id target at)))

(defn- heard
  "`state` with the seconds that the listener heard of the episode `id`,
  reported at `position` at `at`, added to the count of this device for the
  show and the month of `at` in UTC.

  The seconds are how far the position moved since the last report. They
  are three times the time since that report at most, because a move
  further than playback at three times the speed is a seek. Nothing counts
  when the last report is more than `listening-gap` seconds old, because
  the listener paused. The time is measured from the `:reported-at` of the
  episode, and from its stamp for an entry without one."
  [state id position at]
  (let [{p0 :position :keys [reported-at updated-at]} (episode state id)
        t0      (or reported-at updated-at)
        seconds (when (and (number? position) (number? p0) (inst? t0))
                  (let [moved (- position p0)
                        spent (/ (- (inst-ms at) (inst-ms t0)) 1000.0)]
                    (when (and (pos? moved) (pos? spent) (<= spent listening-gap))
                      (min moved (* 3 spent)))))]
    (if seconds
      (write state [:listened (first id) (subs (time/format-iso8601 at) 0 7) (:device state)]
             #(assoc (or % {}) :seconds (+ (or (:seconds %) 0) seconds))
             at)
      state)))

(defn listened
  "The seconds of each show that the listener heard, by feed guid and by
  month in UTC, as `{feed-guid {\"2026-09\" 5400.0}}`.

  The result adds up the counts of all the devices in `state`. Each device
  keeps a count of its own, which only grows. So no count is lost when two
  devices count at the same time. `step` counts at each `:progress`.

  For the counts to be right, the app reports the position each 30
  seconds, or more often, while an episode plays. See `listening-gap`."
  [state]
  (update-vals (:listened state)
               (fn [months] (update-vals months #(reduce + 0 (keep :seconds (vals %)))))))

(defn resume-position
  "Where to start playing the episode `id` of `feed-guid`: where the
  listener left it, or the feed's skip-intro seconds when it has not been
  started or was heard to the end."
  [state feed-guid id]
  (let [{:keys [position played?]} (episode state id)]
    (if (and (number? position) (pos? position) (not played?))
      position
      (:skip-intro (settings state feed-guid)))))

(defn outro-start
  "Where the outro of the episode `id` of `feed-guid` starts in `state`:
  the skip-outro seconds of the feed before the end.

  Nil when the duration is not known, when the feed skips no outro, or
  when the outro is as long as the episode."
  [state feed-guid id]
  (let [duration (:duration (episode state id))
        outro    (:skip-outro (settings state feed-guid))]
    (when (and (number? duration) (number? outro) (< 0 outro duration))
      (- duration outro))))

(defn history
  "The episode ids `state` knows about, most recently played or touched
  first: an episode heard again comes first again."
  [state]
  (->> (:episodes state)
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
  tombstone. So the removal outlives an older star on another device."
  [state id at]
  (if (starred? state id)
    (write state [:stars id] #(assoc % :removed? true) at)
    state))

(defn ^:no-doc second-of
  "The whole second a bookmark at `position` has, nil for a position that
  is not a number of seconds from the start."
  [position]
  (when (and (number? position) (<= 0 position) (not (infinite? position)))
    (long position)))

(defn bookmark
  "Bookmark the episode `id` in `state` at `position` seconds at `at`,
  with `title`, which can be nil.

  The whole second identifies the bookmark. So another bookmark at the
  same second replaces its title."
  [state id position title at]
  (if-let [s (second-of position)]
    (write state [:bookmarks id s] (constantly {:title title :removed? false}) at)
    state))

(defn ^:no-doc unmarked
  "`state` with a tombstone at `at` in place of the live entry of the
  collection `k` that the episode `id` has at `position` seconds. The
  collection holds its entries by episode and by whole second."
  [state k id position at]
  (let [s (second-of position)
        e (get-in state [k id s])]
    (if (and e (not (:removed? e)))
      (write state [k id s] #(assoc % :removed? true) at)
      state)))

(defn ^:no-doc marks
  "The live entries of the collection `k` of `state`, the latest written
  first. The collection holds its entries by episode and by whole second.

  Each is a map with the `:id` of its episode, its second under `at-key`,
  the keys `ks` of its entry, and `:updated-at`. With `id`, the result is
  the entries of that episode, in the order of their seconds."
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
  "The bookmarks of `state`, the latest written first. With `id`, the
  bookmarks of that episode, in the order of their positions.

  A bookmark is a map with `:id`, `:position` in whole seconds, `:title`
  and `:updated-at`."
  ([state]
   (marks state :bookmarks :position [:title]))
  ([state id]
   (marks state :bookmarks :position [:title] id)))

(defn clip
  "Keep a clip of the episode `id` in `state` at `at`, with `title`, which
  can be nil. The clip starts `start` seconds into the episode, and it
  lasts `duration` seconds.

  A clip has the shape of a podcast:soundbite. The whole second of its
  start identifies the clip. So another clip at the same second replaces
  its duration and its title. A start or a duration that is no number of
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
  "The clips of `state`, the latest written first. With `id`, the clips
  of that episode, in the order of their starts.

  A clip is a map with `:id`, `:start-time` in whole seconds, `:duration`
  in seconds, `:title` and `:updated-at`."
  ([state]
   (marks state :clips :start-time [:duration :title]))
  ([state id]
   (marks state :clips :start-time [:duration :title] id)))

(def rating-scale
  "The scale of the ratings, for a function that gets no other scale: from
  1, the worst rating, to 5, the best, as the stars of most apps.

  A scale is a map with `:worst` and `:best`, two different numbers, as a
  Rating of schema.org has them. An app with another scale gives it as
  `:scale` in the options. Examples are `{:worst 1 :best 100}`, and
  `{:worst 0 :best 1}` for a thumb down and a thumb up."
  {:worst 1 :best 5})

(defn ^:no-doc scale?
  "Whether `x` is a scale of ratings: a map whose `:worst` and `:best` are
  two different numbers."
  [x]
  (let [finite? #(and (number? %) (== % %) (not (infinite? %)))]
    (and (map? x)
         (finite? (:worst x))
         (finite? (:best x))
         (not (== (:worst x) (:best x))))))

(defn ^:no-doc checked-scale
  "The `scale` that an option gave, with its two keys alone. It throws
  when `scale` is no scale."
  [scale]
  (if (scale? scale)
    (select-keys scale [:worst :best])
    (throw (ex-info "A scale of ratings has a :worst and a :best, two different numbers" {:scale scale}))))

(defn ^:no-doc scale-of
  "The scale of the rating entry `e`, which is `rating-scale` for an entry
  that names none."
  [e]
  (clojure.core/merge rating-scale (select-keys e [:worst :best])))

(defn ^:no-doc rating?
  "Whether `n` is a rating of `scale`: a number from the worst to the best
  of the scale."
  [n {:keys [worst best] :as scale}]
  (and (scale? scale)
       (number? n)
       (<= (min worst best) n (max worst best))))

(defn ^:no-doc rescaled
  "The rating `n` of the scale `from` as a rating of the scale `to`: as far
  on the way from the worst to the best."
  [n from to]
  (if (= from to)
    n
    (+ (:worst to)
       (* (/ (- n (:worst from)) (double (- (:best from) (:worst from))))
          (- (:best to) (:worst to))))))

(defn rating
  "The rating that the listener gave the episode `id` in `state`, a
  number. Nil when the episode has no rating.

  The rating is in the scale that it has in the state. With `:scale` in
  `opts`, it is in that scale, as far on the way from the worst to the
  best. So a 4 of 1 to 5 is 75.25 of 1 to 100. `rated` gives each rating
  with its scale."
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
  "The episodes of `state` that have a rating, the latest rated first.

  Each is a map with `:id`, `:rating`, the `:worst` and the `:best` of
  its scale, and `:updated-at`. With `:scale` in `opts`, each rating is
  in that scale, as `rating` gives it."
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
  "Give the episode `id` in `state` the rating `n` at `at`. It takes the
  place of an earlier rating.

  `n` is a number from the worst to the best of `rating-scale`, which is
  1 to 5, or of the `:scale` in `opts`. The state keeps the scale with
  the rating, so another app reads the rating in its own scale. A number
  outside the scale changes nothing.

  `rate` is another thing: the speed of playback."
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
  "The live entries of `entries`, a map of ranked entries such as the
  queue, as `[id entry]` in order: by rank, then by stamp and device where
  two devices ranked at once."
  [entries]
  (sort-by (fn [[_ {:keys [rank updated-at device]}]] [rank (inst-ms updated-at) (str device)])
           (remove (comp :removed? val) entries)))

(defn queue
  "The episode ids queued in `state`, the current one first."
  [state]
  (mapv key (ranked (:queue state))))

(defn current
  "The episode id at the front of the queue, nil when it is empty."
  [state]
  (first (queue state)))

(defn queued?
  "Whether the episode `id` is in the queue."
  [state id]
  (let [e (get-in state [:queue id])]
    (boolean (and e (not (:removed? e))))))

(defn in-inbox?
  "Whether the episode `id` is in the inbox of `state`. `refresh` put it
  there, and the listener did not dismiss, queue or play it after that."
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
  "A rank for a place among the ranked `entries`: after the live entry
  `before` and before the live `after`, either nil at an end. Where
  several entries share a rank, the place is after all of them."
  [entries before after]
  (let [a (some->> before (get entries) :rank)
        b (when after
            (->> (drop-while #(not= after (key %)) (ranked entries))
                 (map (comp :rank val))
                 (filter #(or (nil? a) (pos? (compare % a))))
                 first))]
    (rank/between a b)))

(defn ^:no-doc last-rank
  "The greatest rank among the live `entries`, nil when there is none, found
  in one pass, so that appending many entries in a row sorts nothing."
  [entries]
  (reduce-kv (fn [r _ {:keys [rank removed?]}] (if (and (not removed?) (pos? (compare rank r))) rank r))
             nil
             entries))

(defn ^:no-doc rank-at
  "The rank that moves the entry `id` to position `index` among the ranked
  `entries`; an index that is not whole counts as the whole number below
  it, and NaN as 0, on both platforms."
  [entries id index]
  (let [others (vec (remove #{id} (map key (ranked entries))))
        index  (long (max 0 (min (if (== index index) index 0) (count others))))]
    (rank-between entries (get others (dec index)) (get others index))))

(defn ^:no-doc place
  "`state` with the episode `id` queued at `rank` at `at`, and out of the
  inbox."
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
  "Put the episode `id` right after the current episode of `state` at
  `at`.

  An episode that is in the queue moves there. With an empty queue, the
  episode goes to the front."
  [state id at]
  (if (= id (current state))
    state
    (let [[first-id second-id] (remove #{id} (queue state))]
      (place state id (rank-between (:queue state) first-id second-id) at))))

(defn dequeue
  "Take the episode `id` out of the queue at `at`, leaving a tombstone, so
  that the removal outlives an older addition on another device."
  [state id at]
  (if (queued? state id)
    (write state [:queue id] #(assoc % :removed? true) at)
    state))

(defn move
  "Move the queued episode `id` to position `index` of the queue of
  `state` at `at`.

  An index that is not whole counts as the whole number below it, and NaN
  counts as 0, on both platforms."
  [state id index at]
  (if-not (queued? state id)
    state
    (place state id (rank-at (:queue state) id index) at)))

(defn advance
  "Drop the current episode from the queue at `at`, so that the next one is
  current."
  [state at]
  (if-let [id (current state)]
    (dequeue state id at)
    state))

(defn playlist
  "The episode ids in the playlist `name` of `state`, in order.

  A playlist is a list that the listener keeps beside the queue, ranked
  as the queue is. To play it, the app puts its episodes in the queue."
  [state name]
  (mapv key (ranked (get-in state [:playlists name]))))

(defn playlists
  "The playlists of `state` by name, as `{name [episode-id ...]}`. A
  playlist without episodes is no playlist."
  [state]
  (into {} (keep (fn [[name entries]] (when-some [ids (not-empty (mapv key (ranked entries)))] [name ids])))
        (:playlists state)))

(defn ^:no-doc appended
  "The writes that put the episodes `ids` at the end of the ranked entries
  at `path` in `state`, in their order. The queue and each playlist are
  such entries. An episode that is there stays where it is.

  The ranks are those of `dk.simongray.podcast.rank/after`, so the ranks
  of many episodes are short."
  [state path ids]
  (let [entries (get-in state path)
        held?   (fn [id] (let [e (get entries id)] (and e (not (:removed? e)))))
        fresh   (remove held? (distinct ids))]
    (map (fn [id rank] [(conj path id) (constantly {:rank rank :removed? false})])
         fresh
         (rank/after (last-rank entries) (count fresh)))))

(defn ^:no-doc append
  "`state` with the episodes `ids` at the end of the ranked entries at
  `path` at `at`, as `appended` writes them. With no episode to write, the
  state is as it was."
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
  `state` at `at`, as `move` moves an episode in the queue."
  [state name id index at]
  (let [entries (get-in state [:playlists name])
        e       (get entries id)]
    (if (and e (not (:removed? e)))
      (write state [:playlists name id] (constantly {:rank (rank-at entries id index) :removed? false}) at)
      state)))

(defn rename-playlist
  "Move the episodes of the playlist `from` of `state` to the end of the
  playlist `to` at `at`, in their order. When `to` is nil, take them out,
  which deletes the playlist.

  A playlist `to` that holds episodes keeps them in their places, and an
  episode that both playlists hold stays where `to` has it."
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
  for `dk.simongray.podcast/emit`.

  Podcasting 2.0 apps that play list feeds can play it. It is a channel
  of the medium podcastL. Its remote items point to the episodes in
  order, each with the URL of its feed when the listener subscribes to
  that feed.

  `opts` give what RSS 2.0 and the namespace ask of a feed and the
  playlist does not know:

  - `:url`, where the feed is published, which gives its podcast:guid
  - `:link`, a page about the list, which is the URL by default
  - `:description`, which is the name by default
  - `:medium`, another medium, such as mixed

  Without a link or a URL, the channel lacks the link that RSS 2.0
  requires.

  The URL of a feed goes out without the user name and the password that
  it holds, because a list feed is for other people."
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
  `name` of `state` at `at`, in the order of the feed.

  An episode is a remote item of the feed with a feed guid and an item
  guid."
  [state name feed at]
  (append state
          [:playlists name]
          (for [{:keys [feed-guid item-guid]} (:podcast/remote-items feed)
                :when (and feed-guid item-guid)]
            [feed-guid item-guid])
          at))

(defn new-episodes
  "The ids of the items that the parse `after` of the feed at `url` has and
  the earlier parse `before` had not, oldest first: in the order the feed
  lists them when it lists its dated items oldest first, else in reverse.
  None without `before`, since a first parse finds a back catalogue, not new
  episodes."
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
  "Note in `state` a new parse `after` of the feed at `url`, which was
  `before`, at `at`. Returns `{:state ... :added [id ...]}`, with each new
  episode under `:added`.

  When the subscription says `:auto-queue?`, the new episodes join the end
  of the queue, oldest first. When it says `:inbox?` and not
  `:auto-queue?`, they join the inbox.

  The state knows an episode that is queued, dismissed or played. Such an
  episode stays as the listener left it, because another device can find
  it first."
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
  "The OPML lists that the listener follows in `state`, by URL, as `{url
  {:title ... :folder ...}}`.

  The folder is where the shows that join the list go, or nil for no
  folder."
  [state]
  (into {} (keep (fn [[url e]] (when-not (:removed? e) [url (select-keys e [:title :folder])])))
        (:lists state)))

(defn follow-list
  "Follow the OPML list at `url` in `state` at `at`. The map gives its
  `:title` and the `:folder` that its shows go into, and each can be nil.

  Such a list is one that someone keeps and changes, as with
  `type=\"include\"` in OPML. The app fetches the list, and gives each new
  parse to `refresh-list`. To follow the list again changes the title and
  the folder."
  [state url {:keys [title folder]} at]
  (write state [:lists url] (constantly {:title title :folder folder :removed? false}) at))

(defn unfollow-list
  "Stop following the OPML list at `url` in `state` at `at`, leaving a
  tombstone. The subscriptions that the list brought stay."
  [state url at]
  (if (contains? (lists state) url)
    (write state [:lists url] #(assoc % :removed? true) at)
    state))

(defn ^:no-doc outlines
  "The outlines of the OPML subscription `list` that name a feed by its URL,
  in the order of the list."
  [list]
  (filter :xml-url (:subscriptions list)))

(defn ^:no-doc named
  "The guid that names the feed of the OPML `outline`, for a state whose
  subscriptions `names` has by URL (see `by-url`).

  It is the guid of the subscription with the URL of the outline. Without
  such a subscription, it is the guid that the outline carries, else the
  guid of its URL. So a show that the state knows under one guid does not
  come in again under another."
  [names {:keys [xml-url guid]}]
  (or (get names (normal-url xml-url))
      (when (string? guid) (not-empty (str/trim guid)))
      (guid/from-url xml-url)))

(defn- joined
  "The result `acc` of `refresh-list`, with the show of `outline` subscribed
  to at `at`, in `folder` when that is not nil. A show that the list had
  before, in `was`, or that the state knows, changes nothing."
  [{:keys [state names] :as acc} {:keys [xml-url title text] :as outline} was folder at]
  (let [g (named names outline)]
    (if (or (was g) (get-in state [:subscriptions g]))
      acc
      (-> acc
          (assoc :state (cond-> (subscribe state g {:url xml-url :title (or title text)} at)
                          folder (configure g {:folder folder} at))
                 :names (assoc names (normal-url xml-url) g))
          (update :added conj g)))))

(defn refresh-list
  "Note in `state` a new parse `after` of the OPML list at `url`, which was
  `before`, at `at`. Returns `{:state ... :added [feed-guid ...] :left
  [feed-guid ...]}`.

  The state subscribes to the shows that joined the list, in the folder
  of the list when it has one. It does not subscribe to a show that it
  knows, as subscribed or unsubscribed. For a first parse, without
  `before`, each show joined. Nothing changes for a list that the listener
  does not follow.

  A show that left the list stays. `:left` has the shows that left the
  list and that the listener subscribes to, and the app decides about
  them.

  The state knows a show by the URL of its feed, whatever its scheme, its
  case and its trailing slashes, and then by its guid."
  [state url before after at]
  (if-let [{:keys [folder]} (get (lists state) url)]
    (let [names  (by-url state)
          guids  (fn [list] (map #(named names %) (outlines list)))
          was    (set (guids before))
          is     (set (guids after))
          result (reduce #(joined %1 %2 was folder at) {:state state :names names :added []} (outlines after))]
      {:state (:state result)
       :added (:added result)
       :left  (into [] (comp (distinct) (remove is) (filter #(subscribed? state %))) (guids before))})
    {:state state :added [] :left []}))

(defn to-opml
  "The live subscriptions of `state` as a subscription list for
  `dk.simongray.podcast.format.opml/emit`.

  The folders come first, by name, and then the shows in no folder. The
  shows of a folder are in the order of their titles. Each outline has the
  guid of its feed. It has the tags of the show as its categories, and the
  time that the listener subscribed as the time of its creation. The
  lists that the listener follows are inclusions, each in its folder.

  OPML separates the categories of an outline with commas. So `emit`
  writes a comma in a tag as `_`, and the tag comes back with `_` in it."
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
  "The names, in lower case, of the folder that some apps write around a
  whole subscription list.

  `from-opml` takes a folder with such a name for no folder, when the
  folder holds every feed of the list. The `:wrappers` in the options of
  `from-opml` replace the names."
  #{"feeds"})

(defn- wrapper
  "The folder around the whole OPML subscription `list`: the first folder of
  every feed, when `wrapper?` is true for its name in lower case. Nil when
  the list has none."
  [list wrapper?]
  (let [[folder :as firsts] (map (comp first :path) (outlines list))]
    (when (and folder (apply = firsts) (wrapper? (str/lower-case (str folder))))
      folder)))

(defn- imported
  "The running import `acc` of `from-opml`, with the feed of `outline`
  subscribed to at `at`, in `folder` when that is not nil, and with its
  categories as tags."
  [{:keys [state names]} {:keys [xml-url text title category created] :as outline} folder at]
  (let [g       (named names outline)
        known   (when (get names (normal-url xml-url)) (get-in state [:subscriptions g :url]))
        details {:url (or known xml-url) :title (or title text) :added-at (when (inst? created) created)}]
    {:names (assoc names (normal-url xml-url) g)
     :state (as-> (subscribe state g details at) state
              (if folder (configure state g {:folder folder} at) state)
              (reduce #(tag %1 g %2 at) state (when (sequential? category) category)))}))

(defn from-opml
  "Subscribe `state` at `at` to each feed of the OPML subscription `list`,
  which `dk.simongray.podcast.format.opml/parse` gives.

  A subscription has the title of the feed, where the outline gives one
  apart from its text. It is in the folder that holds its outline, the
  innermost where folders nest. It has the categories of the outline as
  tags. A new subscription takes the time that its outline was created as
  the time that the listener subscribed. The state follows the lists that
  the OPML includes, each with its folder, unless the listener follows
  them.

  A feed that `state` has a subscription to, by its URL, keeps the guid of
  that subscription. It also keeps the URL, with the user name and the
  password that the URL holds. Another feed gets the guid that its outline
  carries, or the guid of its URL.

  Some apps write one folder around the whole list. A folder that holds
  every feed is such a wrapper, and no folder, when `wrappers` has its
  name. The `:wrappers` of `opts` replace those names: a set of names in
  lower case, or a predicate of a name."
  ([state list at]
   (from-opml state list at {}))
  ([state list at opts]
   {:pre [(map? opts)]}
   (let [around (wrapper list (:wrappers opts wrappers))
         folder (fn [path] (last (if (and around (= around (first path))) (rest path) path)))
         state  (:state (reduce #(imported %1 %2 (folder (:path %2)) at)
                                {:state state :names (by-url state)}
                                (outlines list)))]
     (reduce (fn [state {:keys [url text path]}]
               (if (contains? (lists state) url)
                 state
                 (follow-list state url {:title text :folder (folder path)} at)))
             state
             (:includes list)))))

(defn- key-order
  "The order `printed` puts the keys of an entry in: keywords as `compare`
  puts them, and after them every other key by its printed form, so that
  any two keys compare."
  [a b]
  (if (and (keyword? a) (keyword? b))
    (compare a b)
    (compare [(if (keyword? a) 0 1) (pr-str a)] [(if (keyword? b) 0 1) (pr-str b)])))

(defn- in-order
  "The value `x` in the one form that `printed` prints: a map with its keys
  in order, a set with its members in order, and a whole number without a
  fraction. A value of another kind is as it is."
  [x]
  (cond
    (map? x)   (into (sorted-map-by key-order) x)
    (set? x)   (into (sorted-set-by key-order) x)
    (float? x) (or (json/whole x) x)
    :else      x))

(defn- printed
  "The entry `e` printed in the one form that `in-order` gives each value
  in it. The string is the same for the same entry on either platform."
  [e]
  (pr-str (walk/postwalk in-order e)))

(defn- later
  "The later of the stamped entries `a` and `b`; on the same instant the
  device decides, and failing that the content as printed, so that the
  choice is the same whichever side asks, on whichever platform. Equal
  entries, which two states that agree tie on everywhere, are not printed."
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
  "Of `a` and `b`, two counts of listening of one device, the count with
  more seconds. With the same seconds, it is the later of them. A count
  only grows, so the higher count holds the lower one."
  [a b]
  (let [seconds #(let [n (:seconds %)] (if (number? n) n 0))]
    (cond
      (> (seconds a) (seconds b)) a
      (< (seconds a) (seconds b)) b
      :else                       (later a b))))

(defn- join
  "The merge of the values `a` and `b` under one key of two states, by their
  shape: of two stamped entries, the one that `pick` takes; maps of
  anything else, key by key down to the entries; instants, the later;
  anything else stays `a`'s."
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
  "Reconcile the states `a` and `b` of the devices of one listener, key by
  key, by the shape of the value under each key.

  - A stamped entry is a last-writer-wins register. The later entry wins
    whole, also when it is a tombstone.
  - A map of entries merges entry by entry, down through maps of maps.
  - An instant is a max register.
  - Each other value is the value of `a`, as the device is.

  The merge is commutative, associative and idempotent, because it is the
  join of a state-based CRDT. So it is safe to apply it in any order and
  any number of times.

  A count of listening is a counter that only grows. Of two counts of one
  device, the merge takes the higher count, whatever their stamps are."
  [a b]
  (reduce (fn [m k] (assoc m k (join (if (= :listened k) higher later) (get a k) (get b k))))
          {:device (:device a)}
          (disj (into (set (keys a)) (keys b)) :device)))

(def ^:no-doc collections
  "The keys of a state that hold maps of entries, in the order that
  `problem` reads them. Each has the number of keys that name one entry:
  a bookmark is by episode and then by second, so it has two."
  [[:subscriptions 1] [:settings 2] [:tags 2] [:lists 1] [:episodes 1] [:queue 1]
   [:playlists 2] [:inbox 1] [:stars 1] [:bookmarks 2] [:clips 2] [:ratings 1]
   [:filters 1] [:listened 3] [:preferences 1] [:devices 1] [:retired 1]])

(defn- clock-problem
  "The clock of `state` when it is not an instant, described."
  [state]
  (when-some [clock (:clock state)]
    (when-not (inst? clock)
      (str "the clock is " (pr-str clock) ", not an instant"))))

(defn- entry-problem
  "The first value in `v`, at `path`, that is not a map, or that is an entry
  without a stamp, described. The entries are `depth` keys below `v`."
  [path depth v]
  (cond
    (not (map? v))     (str "the entry at " (pr-str path) " is not a map")
    (pos? depth)       (some (fn [[k x]] (entry-problem (conj path k) (dec depth) x)) v)
    (not (stamped? v)) (str "the entry at " (pr-str path) " is not stamped")))

(defn- collection-problem
  "The first collection of `state` that is not a map, described, or the
  first problem that `entry-problem` finds in one."
  [state]
  (some (fn [[k depth]]
          (when-some [entries (get state k)]
            (if (map? entries)
              (entry-problem [k] depth entries)
              (str k " is not a map"))))
        collections))

(defn- stamp-problem
  "The first stamped entry in `v`, at `path`, whose stamp is not an instant,
  described; nil when there is none."
  [path v]
  (cond
    (stamped? v) (when-not (inst? (:updated-at v))
                   (str "the entry at " (pr-str path) " is stamped " (pr-str (:updated-at v))
                        ", not an instant"))
    (map? v)     (some (fn [[k x]] (stamp-problem (conj path k) x)) v)
    :else        nil))

(defn- rank-problem
  "The first rank in the queue or in a playlist of `state` that is not a
  rank, described. `ranked` throws on such a rank."
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
  "The first device of `state` whose `:seen` is not a map of instants,
  described."
  [state]
  (some (fn [[id {:keys [seen]}]]
          (when (and (some? seen) (not (and (map? seen) (every? inst? (vals seen)))))
            (str "what device " (pr-str id) " has seen is not a map of instants")))
        (:devices state)))

(defn- key-problem
  "The first key of `state` that is of the wrong kind for its collection,
  described. A list is at a string, a bookmark and a clip are at a whole
  number of seconds, and a tag is a string."
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
  "The first rating of `state` that is no number of its scale, or the
  first clip that lasts no number of seconds, described. A tombstone has
  no such rule."
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
  "The first show of `state` with a count of listening that is not a number
  of seconds, described."
  [state]
  (some (fn [[feed-guid months]]
          (when-not (every? #(number? (:seconds % 0)) (mapcat vals (vals months)))
            (str "the listening of " (pr-str feed-guid) " is not counted in seconds")))
        (:listened state)))

(defn- criteria-problem
  "The first saved filter of `state` whose criteria are not a map,
  described."
  [state]
  (some (fn [[name {:keys [criteria]}]]
          (when-not (or (nil? criteria) (map? criteria))
            (str "the criteria of the filter " (pr-str name) " are not a map")))
        (:filters state)))

(def ^:no-doc checks
  "The checks of `problem`, in their order. Each is a function of a state
  that is a map. It describes the first problem of its kind, or it gives
  nil. A check relies on the checks before it."
  [clock-problem collection-problem #(stamp-problem [] %) rank-problem seen-problem
   key-problem mark-problem count-problem criteria-problem])

(defn problem
  "What in `state` breaks the rules that `merge` and the operations here
  rely on, described. Nil when nothing does.

  Another device wrote the state, and a merge sends its values to every
  device. There the first operation to meet such a value throws, or two
  devices merge to two different states. These are the rules:

  - The state and its collections are maps, and the clock is an instant.
  - Each entry of a collection has a stamp, and each stamp is an instant.
  - Each rank in the queue and in a playlist is a rank.
  - What a device has seen is a map of instants.
  - A list is at a URL, a tag is a string, and a bookmark and a clip are
    at a whole number of seconds.
  - A rating is a number of its scale, and a clip lasts a number of
    seconds.
  - A count of listening is a number of seconds.
  - The criteria of a saved filter are a map.

  The criteria of a later version of podcast-clj are no problem here.
  `dk.simongray.podcast.app.search/problem` tells whether this version
  knows them."
  [state]
  (if (map? state)
    (some #(% state) checks)
    "the state is not a map"))

(defn- changed-part
  "The part of `v` that is not in `was`, the same place in an earlier
  state: a stamped entry that differs, a map with what of its values does,
  nil when nothing does."
  [v was]
  (cond
    (stamped? v) (when (not= v was) v)
    (map? v)     (let [m (into {} (keep (fn [[k x]] (when-some [y (changed-part x (get was k))] [k y]))) v)]
                   (when (seq m) m))
    :else        nil))

(defn changes-since
  "The part of `state` that is not in `previous`, the state as it was when
  the changes were last taken, all of it when `previous` is nil: every
  entry written since, and every entry merged since from another device,
  however early its stamp, with the device and the clock, as a partial
  state that `merge` takes. What a sync pushes to a service that wants the
  changes rather than the state."
  [state previous]
  (into (select-keys state [:device :clock])
        (keep (fn [[k v]]
                (when-not (#{:device :clock} k)
                  (when-some [w (changed-part v (get previous k))]
                    [k w]))))
        state))

(defn ^:no-doc stepped
  "What `step` gives: `state`, the `effects`, and the sleep timer `sleep`
  when one runs."
  [state effects sleep]
  (cond-> {:state state :effects effects}
    sleep (assoc :sleep sleep)))

(defn ^:no-doc ended
  "What `step` gives when the episode `id` ended at `at`. The episode
  counts as played, and it leaves the queue when it was current. The
  effect is [:play next] when another episode is in the queue.

  With the sleep timer `sleep`, one episode less is left to play. When
  none is left, the effect is [:stop id], and the timer stops."
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
  "What `step` gives when the position of the episode `id` moved at `at`,
  from where `state` has it to where `state'` has it: `state'`, with the
  `effects` and then [:played id] when the episode now counts as played.
  When the position moved into the outro, [:stop id] takes the place of
  the `effects`, and the episode ends as `ended` ends it, with the sleep
  timer `sleep`, its effects last."
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
  "Advance `state` by an event of the player at `at`. Returns `{:state ...
  :effects [...]}`, where the effects tell the UI what to do next.

  The event is `[kind id & args]`:

      [:progress id position duration]  records the position, and the seconds heard
                                        since the last report for `listened`; effect
                                        [:played id] when it now counts as played
      [:ended id]                       marks it played and, when it was current,
                                        drops it from the queue; effect [:play next]
                                        when another episode is queued
      [:play id]                        makes it current at the front of the queue
      [:skip id amount]                 moves `amount` seconds, or the preferred amount
                                        for :forward or :back; effect [:seek id position],
                                        and [:played id] when it now counts as played
      [:chapter id direction doc]       moves to the next or the previous chapter of the
                                        chapters document `doc`, for :next or :previous,
                                        as `chapters/jump` has it; the effects of a skip,
                                        and none after the start of the last chapter

  A progress, a skip or a jump to a chapter can move the position from
  before `outro-start` to it or past it. It then ends the episode as
  [:ended id] does. The effect [:stop id] comes first, then [:played id]
  when the episode now counts as played, and no seek. The feed whose
  settings give the outro is the first part of `id`.

  `opts` can give `:sleep`, the sleep timer of this device. `{:episodes
  n}` stops the player when `n` more episodes ended, so 1 stops it at the
  end of this episode. The result then has the timer that is left under
  `:sleep`, for the next step. When the last of the episodes ends, the
  effect is [:stop id] in place of [:play next], and the result has no
  timer. The timer is of one device, so the state does not hold it.

  `opts` can also give `:restart`, the seconds of `chapters/jump` for a
  jump to the previous chapter."
  ([state event at]
   (step state event at {}))
  ([state [kind id & args] at {:keys [sleep] :as opts}]
   {:pre [(map? opts)]}
   (case kind
     :skip     (let [[amount] args
                     {:keys [skip-forward skip-back]} (preferences state)
                     seconds (case amount :forward skip-forward :back (- skip-back) amount)
                     state'  (skip state id seconds at)]
                 (moved state state' id [[:seek id (:position (episode state' id))]] at sleep))
     :chapter  (let [[direction doc] args
                     position (:position (episode state id))
                     from     (if (number? position) position 0)]
                 (if-let [target (chapters/jump doc from direction (select-keys opts [:restart]))]
                   (step state [:skip id (- target from)] at opts)
                   (stepped state [] sleep)))
     :progress (let [[position duration] args]
                 (moved state (-> state (heard id position at) (progress id position duration at)) id [] at sleep))
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
