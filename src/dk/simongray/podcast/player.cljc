(ns dk.simongray.podcast.player
  "What a podcast player keeps between plays, as plain data with pure
  operations over it, for any UI to build on: the play queue, listening
  progress and played marks per episode, subscriptions with their settings,
  and a `merge` that reconciles the state of one listener's devices, which
  is the core of syncing.

  The state is one map:

      {:device        \"phone\"
       :subscriptions {feed-guid {:url :title :added-at :removed? :settings {...}}}
       :episodes      {[feed-guid item-guid] {:position :duration :played? :played-at}}
       :queue         {:items [[feed-guid item-guid] ...]}}

  Every write takes the instant `at` it happens, never reads a clock, and
  stamps what it changed with `at` and the state's `:device`, so that
  `merge` can let the latest entry win. Nothing here stores anything or
  touches a media element; the app owns those and calls in.

  Identities are those of `dk.simongray.podcast.feed`: a feed by its
  `podcast:guid`, an episode by `[feed-guid item-guid]`."
  (:refer-clojure :exclude [merge])
  (:require [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.guid :as guid]))

(defn state
  "A fresh player state for `device`, a name that tells one listener's
  devices apart when their states are merged."
  [device]
  {:device device :subscriptions {} :episodes {} :queue {:items []}})

(defn- stamp
  "`entry` marked as changed at `at` on the device of `state`."
  [state entry at]
  (assoc entry :updated-at at :device (:device state)))

(defn episode-id
  "The identity of `item` in `feed`, fetched from `url`: the feed's guid and
  the item's."
  [feed url item]
  [(feed/feed-guid feed url) (feed/item-guid item)])

;;;; Subscriptions

(def default-settings
  "What a subscription is set to until `configure` says otherwise: whether
  new episodes join the queue and the seconds to skip at the start and the
  end. A `:rate` set here overrides the listener's preferred one."
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
  "Subscribe to the feed `feed-guid` at `url` with `title` at `at`, keeping
  the settings of an earlier subscription to it."
  [state feed-guid {:keys [url title]} at]
  (update-in state [:subscriptions feed-guid]
             (fn [sub]
               (stamp state
                      (assoc (or sub {})
                             :url url :title title :removed? false
                             :added-at (if (subscribed? state feed-guid) (:added-at sub) at))
                      at))))

(defn unsubscribe
  "Unsubscribe from `feed-guid` at `at`, leaving a tombstone, even where no
  subscription was known, so that the removal outlives an older addition on
  another device."
  [state feed-guid at]
  (update-in state [:subscriptions feed-guid]
             #(stamp state (assoc (or % {}) :removed? true) at)))

(defn settings
  "The settings of the subscription `feed-guid`, `default-settings` filled
  in."
  [state feed-guid]
  (clojure.core/merge default-settings (get-in state [:subscriptions feed-guid :settings])))

(defn configure
  "Change the `settings` of the subscription `feed-guid` at `at`; the keys
  are those of `default-settings`."
  [state feed-guid settings at]
  (if (get-in state [:subscriptions feed-guid])
    (update-in state [:subscriptions feed-guid]
               #(stamp state (update % :settings clojure.core/merge settings) at))
    state))

;;;; Listener preferences

(def default-preferences
  "How the listener likes to listen, until `prefer` says otherwise: the
  seconds a skip jumps forward and back, and the playback rate."
  {:skip-forward 30 :skip-back 15 :rate 1.0})

(defn preferences
  "The listener's preferences, `default-preferences` filled in."
  [state]
  (clojure.core/merge default-preferences (dissoc (:preferences state) :updated-at :device)))

(defn prefer
  "Change the listener's `preferences` at `at`; the keys are those of
  `default-preferences`."
  [state preferences at]
  (assoc state :preferences
         (stamp state (clojure.core/merge (dissoc (:preferences state) :updated-at :device) preferences) at)))

(defn rate
  "The playback rate for the feed `feed-guid`: its own setting, else the
  listener's preference."
  [state feed-guid]
  (or (:rate (settings state feed-guid)) (:rate (preferences state))))

;;;; Episodes

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
   (update-in state [:episodes id]
              (fn [e]
                (let [e (cond-> (assoc (or e {}) :position position)
                          duration (assoc :duration duration))
                      e (if (and (not (:played? e)) (heard-to-the-end? position (:duration e)))
                          (assoc e :played? true :played-at at)
                          e)]
                  (stamp state e at))))))

(defn mark-played
  "Mark the episode `id` as played at `at`."
  [state id at]
  (update-in state [:episodes id] #(stamp state (assoc (or % {}) :played? true :played-at at) at)))

(defn mark-unplayed
  "Mark the episode `id` as not played at `at`, its position back at the
  start."
  [state id at]
  (update-in state [:episodes id]
             #(stamp state (-> (or % {}) (assoc :played? false :position 0) (dissoc :played-at)) at)))

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

;;;; The queue

(defn queue
  "The episode ids queued in `state`, the current one first."
  [state]
  (get-in state [:queue :items] []))

(defn current
  "The episode id at the front of the queue, nil when it is empty."
  [state]
  (first (queue state)))

(defn queued?
  "Whether the episode `id` is in the queue."
  [state id]
  (boolean (some #{id} (queue state))))

(defn- set-queue
  [state items at]
  (assoc state :queue (stamp state {:items (vec items)} at)))

(defn enqueue
  "Put the episode `id` at the end of the queue at `at`, unless it is queued
  already."
  [state id at]
  (if (queued? state id)
    state
    (set-queue state (conj (queue state) id) at)))

(defn play-next
  "Put the episode `id` right after the current episode at `at`, moving it
  there when it is queued already; at the front when the queue is empty."
  [state id at]
  (if (= id (current state))
    state
    (let [items (vec (remove #{id} (queue state)))
          [head tail] (split-at (min 1 (count items)) items)]
      (set-queue state (concat head [id] tail) at))))

(defn dequeue
  "Take the episode `id` out of the queue at `at`."
  [state id at]
  (if (queued? state id)
    (set-queue state (remove #{id} (queue state)) at)
    state))

(defn move
  "Move the queued episode `id` to position `index` of the queue at `at`."
  [state id index at]
  (if-not (queued? state id)
    state
    (let [items (vec (remove #{id} (queue state)))
          index (max 0 (min index (count items)))]
      (set-queue state (concat (subvec items 0 index) [id] (subvec items index)) at))))

(defn advance
  "Drop the current episode from the queue at `at`, so that the next one is
  current."
  [state at]
  (if (seq (queue state))
    (set-queue state (rest (queue state)) at)
    state))

;;;; New episodes

(defn new-episodes
  "The ids of the items that the parse `after` of the feed at `url` has and
  the earlier parse `before` had not, oldest first."
  [before after url]
  (let [feed-guid (feed/feed-guid after url)]
    (->> (:added (feed/diff before after))
         (map #(vector feed-guid (feed/item-guid %)))
         reverse
         vec)))

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

;;;; OPML

(defn to-opml
  "The live subscriptions of `state` as a subscription list for
  `dk.simongray.podcast.opml/emit`, titles in order, each outline carrying
  the feed's guid."
  [state]
  {:title         "Subscriptions"
   :subscriptions (vec (for [[feed-guid {:keys [url title]}] (sort-by (comp str :title val) (subscriptions state))]
                         {:text (or title url) :xml-url url :guid feed-guid}))})

(defn from-opml
  "Subscribe at `at` to every feed of the OPML subscription `list` (see
  `dk.simongray.podcast.opml/parse`), each identified by the guid its
  outline carries or the one derived from its URL."
  [state list at]
  (reduce (fn [state {:keys [xml-url text guid]}]
            (if xml-url
              (subscribe state (or guid (guid/from-url xml-url)) {:url xml-url :title text} at)
              state))
          state
          (:subscriptions list)))

;;;; Merging the states of several devices

(defn- later
  "The later of the stamped entries `a` and `b`; on the same instant the
  device name decides, and failing that the content, so that the choice is
  the same whichever side asks."
  [a b]
  (let [ms (fn [x] (or (some-> (:updated-at x) inst-ms) 0))]
    (cond
      (nil? a)               b
      (nil? b)               a
      (> (ms a) (ms b))      a
      (< (ms a) (ms b))      b
      :else                  (if (pos? (compare [(str (:device a)) (hash a)]
                                                [(str (:device b)) (hash b)]))
                               a
                               b))))

(defn merge
  "Reconcile the states `a` and `b` of one listener's devices: for every
  subscription and every episode the later entry wins, and so does the
  later queue, so that a removal carried as a tombstone beats an older
  addition. Commutative, associative and idempotent, hence safe to apply in
  any order and any number of times. The result belongs to `a`'s device."
  [a b]
  (cond-> {:device        (:device a)
           :subscriptions (merge-with later (:subscriptions a) (:subscriptions b))
           :episodes      (merge-with later (:episodes a) (:episodes b))
           :queue         (later (:queue a) (:queue b))}
    (or (:preferences a) (:preferences b))
    (assoc :preferences (later (:preferences a) (:preferences b)))))

;;;; Driving the state from a player

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
    :play     {:state   (set-queue state (into [id] (remove #{id} (queue state))) at)
               :effects []}
    {:state state :effects []}))

#?(:clj
   (comment
     (-> (state "laptop")
         (subscribe "feed-1" {:url "https://a/rss" :title "A"} #inst "2026-01-01")
         (enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
         (progress ["feed-1" "ep-1"] 120 3600 #inst "2026-01-01T02:00:00Z"))
     #_.))
