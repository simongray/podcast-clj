(ns dk.simongray.podcast.app.player
  "What a podcast player keeps between plays, as plain data with pure
  operations over it, for any UI to build on: the play queue, listening
  progress and played marks per episode, subscriptions with their settings,
  the listener's devices, and a `merge` that reconciles the state of one
  listener's devices, which is the core of syncing.

  The state is one map:

      {:device        \"8f1c9e2a-…\"                 ; this device's id
       :clock         #inst \"…\"                    ; the latest instant written or merged
       :subscriptions {feed-guid {:url :title :added-at :removed?}}
       :settings      {feed-guid {:skip-intro {:value 20} ...}}
       :episodes      {[feed-guid item-guid] {:position :duration :played? :played-at}}
       :queue         {[feed-guid item-guid] {:rank \"V\" :removed? false}}
       :preferences   {:skip-forward {:value 30} ...}
       :devices       {device-id {:name :platform ... :seen {device-id #inst \"…\"}}}
       :retired       {device-id {}}}

  Every write takes the instant `at` it happens, never reads a clock, and
  stamps what it changed with the state's `:device` and with `at` as
  `after` adjusts it. The state is a state-based CRDT: a stamped entry is a
  last-writer-wins register, a map of them an add-and-remove set with
  tombstones, the clock a max register, and `merge` the join; the queue
  ranks its entries with `dk.simongray.podcast.rank`, so that what two
  devices queued while apart both survive, and each device's registry
  entry says what it has merged, which lets `compact` drop the tombstones
  everyone has seen. doc/sync.md is the design. Nothing here stores
  anything or touches a media element; the app owns those and calls in,
  and `dk.simongray.podcast.app.sync` carries the state between devices.

  Identities are those of `dk.simongray.podcast.feed`: a feed by its
  `podcast:guid`, an episode by `[feed-guid item-guid]`."
  (:refer-clojure :exclude [merge])
  (:require [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.guid :as guid]
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
  way a hybrid logical clock does. Every write applies this itself; an app
  calls it for entries of its own. The instant is the platform's date
  whatever `now` is, so that a document prints it as `#inst`."
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

(defn- write
  [state path f at]
  (write-each state [[path f]] at))

(defn episode-id
  "The identity of `item` in `feed`, fetched from `url`: the feed's guid and
  the item's."
  [feed url item]
  [(feed/feed-guid feed url) (feed/item-guid item)])

(def default-settings
  "What a subscription is set to until `configure` says otherwise: whether
  new episodes join the queue and the seconds to skip at the start and the
  end."
  {:auto-queue? false :skip-intro 0 :skip-outro 0})

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

(defn subscribe
  "Subscribe to the feed `feed-guid` at `url` with `title` at `at`; the
  settings of an earlier subscription to it still hold."
  [state feed-guid {:keys [url title]} at]
  (write state [:subscriptions feed-guid]
         (fn [sub]
           (assoc (or sub {})
                  :url url :title title :removed? false
                  :added-at (if (subscribed? state feed-guid) (:added-at sub) (time/date at))))
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
  listener's preference."
  [state feed-guid]
  (or (:rate (settings state feed-guid)) (:rate (preferences state))))

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
  "Drop from `state` at `at` the tombstones of unsubscriptions and of queue
  removals that every live device has merged, by the registry (see
  `acknowledge`), and that are older than `:keep-for` milliseconds in
  `opts` (default `default-keep`), a buffer against a device that starts
  syncing with a state of its own. The played marks stay, since the
  listener still sees them, and a device that has stopped syncing holds
  compaction back until it is retired (see `lagging`)."
  ([state at]
   (compact state at {}))
  ([state at {:keys [keep-for] :or {keep-for default-keep} :as opts}]
   {:pre [(map? opts)]}
   (let [droppable? (fn [e]
                      (and (:removed? e)
                           (<= (+ (inst-ms (:updated-at e)) keep-for) (inst-ms at))
                           (seen-by-all? state e)))
         sweep      (fn [entries] (into {} (remove (comp droppable? val)) entries))]
     (-> state
         (update :subscriptions sweep)
         (update :queue sweep)))))

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

(defn progress
  "Record that the listener is `position` seconds into the episode `id` at
  `at`, and its `duration` when known; an episode heard to within
  `played-threshold` seconds of its end counts as played."
  ([state id position at]
   (progress state id position nil at))
  ([state id position duration at]
   (write state [:episodes id]
          (fn [e]
            (let [e (cond-> (assoc (or e {}) :position position)
                      duration (assoc :duration duration))]
              (if (and (not (:played? e)) (heard-to-the-end? position (:duration e)))
                (assoc e :played? true :played-at (time/date at))
                e)))
          at)))

(defn mark-played
  "Mark the episode `id` as played at `at`."
  [state id at]
  (write state [:episodes id] #(assoc (or % {}) :played? true :played-at (time/date at)) at))

(defn mark-unplayed
  "Mark the episode `id` as not played at `at`, its position back at the
  start."
  [state id at]
  (write state [:episodes id] #(-> (or % {}) (assoc :played? false :position 0) (dissoc :played-at)) at))

(defn skip
  "Move `seconds` forward, or back when negative, in the episode `id` at
  `at`, never before its start nor, when the duration is known, past its
  end. The new position is in the state for the UI to seek to."
  [state id seconds at]
  (let [{:keys [position duration]} (episode state id)
        target (+ (or position 0) seconds)
        target (max 0 (if (number? duration) (min target duration) target))]
    (progress state id target at)))

(defn resume-position
  "Where to start playing the episode `id` of `feed-guid`: where the
  listener left it, or the feed's skip-intro seconds when it has not been
  started or was heard to the end."
  [state feed-guid id]
  (let [{:keys [position played?]} (episode state id)]
    (if (and (number? position) (pos? position) (not played?))
      position
      (:skip-intro (settings state feed-guid)))))

(defn history
  "The episode ids `state` knows about, most recently played or touched
  first."
  [state]
  (->> (:episodes state)
       (sort-by (fn [[_ e]] (- (inst-ms (or (:played-at e) (:updated-at e))))))
       (map key)))

(defn- queue-order
  "The live entries of the queue of `state`, `[id entry]`, in order: by
  rank, then by stamp and device where two devices ranked at once."
  [state]
  (sort-by (fn [[_ {:keys [rank updated-at device]}]] [rank (inst-ms updated-at) (str device)])
           (remove (comp :removed? val) (:queue state))))

(defn queue
  "The episode ids queued in `state`, the current one first."
  [state]
  (mapv key (queue-order state)))

(defn current
  "The episode id at the front of the queue, nil when it is empty."
  [state]
  (first (queue state)))

(defn queued?
  "Whether the episode `id` is in the queue."
  [state id]
  (let [e (get-in state [:queue id])]
    (boolean (and e (not (:removed? e))))))

(defn- rank-between
  "A rank for a place in the queue of `state`: after the queued episode
  `before` and before the queued `after`, either nil at an end of the
  queue. Where several entries share a rank, the place is after all of
  them."
  [state before after]
  (let [a (some->> before (get (:queue state)) :rank)
        b (when after
            (->> (drop-while #(not= after (key %)) (queue-order state))
                 (map (comp :rank val))
                 (filter #(or (nil? a) (pos? (compare % a))))
                 first))]
    (rank/between a b)))

(defn- place
  "`state` with the episode `id` queued at `rank` at `at`."
  [state id rank at]
  (write state [:queue id] (constantly {:rank rank :removed? false}) at))

(defn enqueue
  "Put the episode `id` at the end of the queue at `at`, unless it is queued
  already."
  [state id at]
  (if (queued? state id)
    state
    (place state id (rank-between state (peek (queue state)) nil) at)))

(defn play-next
  "Put the episode `id` right after the current episode at `at`, moving it
  there when it is queued already; at the front when the queue is empty."
  [state id at]
  (if (= id (current state))
    state
    (let [[first-id second-id] (remove #{id} (queue state))]
      (place state id (rank-between state first-id second-id) at))))

(defn dequeue
  "Take the episode `id` out of the queue at `at`, leaving a tombstone, so
  that the removal outlives an older addition on another device."
  [state id at]
  (if (queued? state id)
    (write state [:queue id] #(assoc % :removed? true) at)
    state))

(defn move
  "Move the queued episode `id` to position `index` of the queue at `at`."
  [state id index at]
  (if-not (queued? state id)
    state
    (let [others (vec (remove #{id} (queue state)))
          index  (max 0 (min index (count others)))]
      (place state id (rank-between state (get others (dec index)) (get others index)) at))))

(defn advance
  "Drop the current episode from the queue at `at`, so that the next one is
  current."
  [state at]
  (if-let [id (current state)]
    (dequeue state id at)
    state))

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
  "Note a fresh parse `after` of the feed at `url`, previously `before`, at
  `at`: its new episodes join the end of the queue, oldest first, when the
  subscription says `:auto-queue?`. Returns `{:state ... :added [id ...]}`."
  [state before after url at]
  (let [feed-guid (feed/feed-guid after url)
        added     (new-episodes before after url)
        state     (if (and (seq added) (:auto-queue? (settings state feed-guid)))
                    (reduce #(enqueue %1 %2 at) state added)
                    state)]
    {:state state :added added}))

(defn to-opml
  "The live subscriptions of `state` as a subscription list for
  `dk.simongray.podcast.format.opml/emit`, titles in order, each outline carrying
  the feed's guid."
  [state]
  {:title         "Subscriptions"
   :subscriptions (vec (for [[feed-guid {:keys [url title]}] (sort-by (comp str :title val) (subscriptions state))]
                         {:text (or title url) :xml-url url :guid feed-guid}))})

(defn from-opml
  "Subscribe at `at` to every feed of the OPML subscription `list` (see
  `dk.simongray.podcast.format.opml/parse`), each identified by the guid its
  outline carries or the one derived from its URL."
  [state list at]
  (reduce (fn [state {:keys [xml-url text guid]}]
            (if xml-url
              (subscribe state (or guid (guid/from-url xml-url)) {:url xml-url :title text} at)
              state))
          state
          (:subscriptions list)))

(defn- key-order
  "The order `printed` puts the keys of an entry in: keywords as `compare`
  puts them, and after them every other key by its printed form, so that
  any two keys compare."
  [a b]
  (if (and (keyword? a) (keyword? b))
    (compare a b)
    (compare [(if (keyword? a) 0 1) (pr-str a)] [(if (keyword? b) 0 1) (pr-str b)])))

(defn- printed
  "The entry `e` printed with its keys in order, the same string for the
  same entry on either platform."
  [e]
  (pr-str (into (sorted-map-by key-order) e)))

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

(defn- join
  "The merge of the values `a` and `b` under one key of two states, by their
  shape: stamped entries, the later; maps of anything else, key by key down
  to the entries; instants, the later; anything else stays `a`'s."
  [a b]
  (cond
    (nil? a)                                                          b
    (nil? b)                                                          a
    (and (stamped? a) (stamped? b))                                   (later a b)
    (and (map? a) (map? b) (not (stamped? a)) (not (stamped? b)))     (merge-with join a b)
    (and (map? a) (map? b))                                           (later a b)
    (and (inst? a) (inst? b))                                         (later-inst a b)
    :else                                                             a))

(defn merge
  "Reconcile the states `a` and `b` of one listener's devices, key by key
  and by the shape of what is under it: a stamped entry is a
  last-writer-wins register, the later winning whole, a tombstone included;
  a map of them merges entry by entry, down through maps of maps; an
  instant is a max register; anything else is taken from `a`, as is the
  device. Commutative, associative and idempotent, the join of a
  state-based CRDT, so safe to apply in any order and any number of times."
  [a b]
  (reduce (fn [m k] (assoc m k (join (get a k) (get b k))))
          {:device (:device a)}
          (disj (into (set (keys a)) (keys b)) :device)))

(def ^:no-doc collections
  "The keys of a state that hold maps of entries."
  [:subscriptions :settings :episodes :queue :preferences :devices :retired])

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

(defn problem
  "What in `state`, as another device wrote it, breaks what `merge` and the
  operations here rely on, described; nil when nothing does. Merged in,
  such a value would reach every device, and there the first operation to
  meet it would throw: a clock or a stamp that is not an instant, a
  collection or an entry that is not a map, a rank in the queue that is
  not a rank, or a device's record of what it has seen that is not a map
  of instants."
  [state]
  (or (when-not (map? state)
        "the state is not a map")
      (when-some [clock (:clock state)]
        (when-not (inst? clock)
          (str "the clock is " (pr-str clock) ", not an instant")))
      (some (fn [k]
              (when-some [entries (get state k)]
                (if (map? entries)
                  (some (fn [[id entry]]
                          (when-not (map? entry)
                            (str "the entry at " (pr-str [k id]) " is not a map")))
                        entries)
                  (str k " is not a map"))))
            collections)
      (stamp-problem [] state)
      (some (fn [[id {:keys [rank]}]]
              (when (and (some? rank) (not (rank/rank? rank)))
                (str "the rank of " (pr-str id) " in the queue is " (pr-str rank) ", not a rank")))
            (:queue state))
      (some (fn [[id {:keys [seen]}]]
              (when (and (some? seen) (not (and (map? seen) (every? inst? (vals seen)))))
                (str "what device " (pr-str id) " has seen is not a map of instants")))
            (:devices state))))

(defn- written-after
  "The part of `v` stamped after `since`: a stamped entry when it is, a map
  with what of its values is, nil when nothing; nil `since` keeps all."
  [v since]
  (cond
    (stamped? v) (when (or (nil? since) (> (inst-ms (:updated-at v)) (inst-ms since)))
                   v)
    (map? v)     (let [m (into {} (keep (fn [[k x]] (when-some [y (written-after x since)] [k y]))) v)]
                   (when (seq m) m))
    :else        nil))

(defn changes-since
  "The part of `state` written after the instant `since` (all of it when
  `since` is nil), by whichever device: the entries stamped later, with the
  device and the clock, as a partial state that `merge` takes. What a sync
  pushes to a service that wants the changes rather than the state."
  [state since]
  (into (select-keys state [:device :clock])
        (keep (fn [[k v]]
                (when-not (#{:device :clock} k)
                  (when-some [w (written-after v since)]
                    [k w]))))
        state))

(defn step
  "Advance `state` by the player `event` at `at`, returning `{:state ...
  :effects [...]}`, the effects being what the UI should do next:

      [:progress id position duration]  records the position; effect
                                        [:played id] when it now counts as played
      [:ended id]                       marks it played and, when it was current,
                                        drops it from the queue; effect [:play next]
                                        when another episode is queued
      [:play id]                        makes it current at the front of the queue
      [:skip id amount]                 moves `amount` seconds, or the preferred amount
                                        for :forward or :back; effect [:seek id position]"
  [state [kind id & args] at]
  (case kind
    :skip     (let [[amount] args
                    {:keys [skip-forward skip-back]} (preferences state)
                    seconds (case amount :forward skip-forward :back (- skip-back) amount)
                    state'  (skip state id seconds at)]
                {:state   state'
                 :effects [[:seek id (:position (episode state' id))]]})
    :progress (let [[position duration] args
                    was    (played? state id)
                    state' (progress state id position duration at)]
                {:state   state'
                 :effects (if (and (not was) (played? state' id)) [[:played id]] [])})
    :ended    (let [state'   (mark-played state id at)
                    current? (= id (current state'))
                    state'   (if current? (advance state' at) state')
                    next     (when current? (current state'))]
                {:state   state'
                 :effects (if next [[:play next]] [])})
    :play     {:state   (place state id (rank-between state nil (first (remove #{id} (queue state)))) at)
               :effects []}
    {:state state :effects []}))

#?(:clj
   (comment
     (-> (state (str (random-uuid)))
         (identify {:name "laptop"} #inst "2026-01-01")
         (subscribe "feed-1" {:url "https://a/rss" :title "A"} #inst "2026-01-01")
         (enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
         (progress ["feed-1" "ep-1"] 120 3600 #inst "2026-01-01T02:00:00Z"))
     (after *1 #inst "2026-01-01")
     #_.))
