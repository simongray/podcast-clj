(ns dk.simongray.podcast.profile.player
  "The events of a player for a profile: what playing, skipping and
  reaching the end of an episode change in it, and the effects that tell
  the UI what to do next."
  (:require [dk.simongray.mind-meld :as crdt]
            [dk.simongray.mind-meld.list :as crdt.list]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.time :as time]))

(def listening-gap
  "How many seconds can pass between two reports of the position that are
  still one stretch of listening, by default."
  60)

(defn- heard
  "The `profile` with the seconds heard of the episode `id` up to `position`
  at `at` added to this device's count, by show and month in UTC. Nothing
  counts when the last report is more than `gap` seconds old."
  [profile id position at gap]
  (let [{p0 :position :keys [reported-at updated-at]}
        (profile/episode-entry profile id)

        t0      (or reported-at updated-at)
        ;; an older report means that the listener paused, and a move of
        ;; more than three times the time since then is a seek
        seconds (when (and (number? position) (number? p0) (inst? t0))
                  (let [moved (- position p0)
                        spent (/ (- (inst-ms at) (inst-ms t0)) 1000.0)]
                    (when (and (pos? moved) (pos? spent) (<= spent gap))
                      (min moved (* 3 spent)))))]
    (if seconds
      (let [month (subs (time/format-iso8601 at) 0 7)]
        (crdt/write profile [:listened (first id) month (:device profile)]
                    #(update % :seconds (fnil + 0) seconds)
                    at))
      profile)))

(defn ^:no-doc stepped
  "The result of a player event for `profile`, its `effects`, and the sleep
  timer `sleep`, if one runs."
  [profile effects sleep]
  (cond-> {:profile profile :effects effects}
    sleep (assoc :sleep sleep)))

(defn ^:no-doc ended
  "The result of a player event for `profile` when the episode `id` ended
  at `at`, with the sleep timer `sleep`.

  The episode counts as played and leaves the queue if it was current,
  with the effect [:play next] when another episode is queued. With a
  sleep timer, one episode less is left to play, and when none is left,
  the effect is [:stop id] and the timer stops."
  [profile id at sleep]
  (let [profile' (profile/mark-played profile id at)
        current? (= id (profile.queue/current profile'))
        profile' (if current? (profile.queue/advance profile' at) profile')
        next     (when current? (profile.queue/current profile'))
        left     (some-> (:episodes sleep) dec)]
    (if (and left (not (pos? left)))
      (stepped profile' [[:stop id]] nil)
      (stepped profile'
               (if next [[:play next]] [])
               (cond-> sleep left (assoc :episodes left))))))

(defn- moved
  "The result of a player event, with the `effects` and the sleep timer
  `sleep`, when the episode `id` moved at `at` from its position in
  `profile` to that in `profile'`.

  The result has `profile'` and the `effects`, then [:played id] if the
  episode now counts as played. When the position moved into the outro,
  the episode ends, with [:stop id] in place of the `effects`."
  [profile profile' id effects at sleep]
  (let [was       (profile/played? profile id)
        start     (profile/outro-start profile' (first id) id)
        in-outro? #(let [p (:position (profile/episode-entry % id))]
                     (and start (number? p) (>= p start)))]
    (if (and (in-outro? profile') (not (in-outro? profile)))
      (update (ended profile' id at sleep) :effects
              #(into (cond-> [[:stop id]] (not was) (conj [:played id]))
                     (remove #{[:stop id]})
                     %))
      (stepped profile'
               (cond-> effects
                 (and (not was) (profile/played? profile' id))
                 (conj [:played id]))
               sleep))))

(defn step
  "Advance `profile` by the player `event` at `at` with `opts`, returning a
  map of the new :profile and the :effects, which say what the UI should do
  next.

  The event is a vector of its kind, an episode id and any arguments:

  - [:progress id position duration] records the position, and counts the
    seconds heard, with the effect [:played id] when the episode now
    counts as played.
  - [:ended id] marks it played and drops it from the queue when it was
    current, with the effect [:play next] when another episode is queued.
  - [:play id] makes it current, at the front of the queue.
  - [:skip id amount] moves that many seconds, or the preferred amount for
    :forward or :back, with the effect [:seek id position], and
    [:played id] when it now counts as played.
  - [:chapter id direction doc] jumps to the :next or :previous chapter of
    the chapters document, with the effects of a skip.

  A progress, skip or jump that reaches the outro ends the episode as
  [:ended id] does, with [:stop id] first and no seek. The `opts` are:

  - :sleep, the sleep timer of this device, e.g. {:episodes 1} stops at
    the end of this episode. The result has what's left of it under
    :sleep, and the last episode gives [:stop id] instead of [:play next].
  - :restart, the seconds into a chapter in which a jump back goes to the
    chapter before
  - :played-threshold, how many seconds before the end count as having
    heard an episode
  - :listening-gap, how many seconds can pass between two reports of the
    position in one stretch of listening"
  ([profile event at]
   (step profile event at {}))
  ([profile [kind id & args :as event] at {:keys [sleep] :as opts}]
   {:pre [(map? opts)]}
   (case kind
     :skip
     (let [[amount] args
           {:keys [skip-forward skip-back]} (profile/preferences profile)
           seconds  (case amount
                      :forward skip-forward
                      :back    (- skip-back)
                      amount)
           played   (select-keys opts [:played-threshold])
           profile' (profile/skip profile id seconds at played)
           position (:position (profile/episode-entry profile' id))]
       (moved profile profile' id [[:seek id position]] at sleep))

     :chapter
     (let [[direction doc] args
           position (:position (profile/episode-entry profile id))
           from     (if (number? position) position 0)
           restart  (select-keys opts [:restart])]
       (if-let [target (chapters/jump doc from direction restart)]
         (step profile [:skip id (- target from)] at opts)
         (stepped profile [] sleep)))

     :progress
     (let [[position duration] args
           gap      (:listening-gap opts listening-gap)
           played   (select-keys opts [:played-threshold])
           profile' (-> profile
                        (heard id position at gap)
                        (profile/progress id position duration at played))]
       (moved profile profile' id [] at sleep))

     :ended
     (ended profile id at sleep)

     :play
     (let [others  (remove #{id} (profile.queue/queue profile))
           entries (profile.queue/queue-entries profile)
           rank    (crdt.list/rank-between entries nil (first others))]
       (stepped (profile.queue/place profile id rank at) [] sleep))

     (stepped profile [] sleep))))

(comment
  (def sample
    (-> (profile/make "laptop")
        (profile/subscribe "feed-1"
                           {:url "https://example.org/feed.xml" :title "A show"}
                           #inst "2026-01-01")
        (profile/configure "feed-1" {:skip-outro 30} #inst "2026-01-01")
        (profile.queue/enqueue ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")))

  ;; a report that moves the position into the outro ends the episode
  (step (profile/progress sample ["feed-1" "ep-1"] 3500 3600
                          #inst "2026-01-01T02:00:00Z")
        [:progress ["feed-1" "ep-1"] 3580 3600]
        #inst "2026-01-01T02:00:20Z")

  ;; a jump to the next chapter, from 100 seconds into the episode
  (def doc
    {:chapters [{:start-time 0 :title "Intro"}
                {:start-time 90 :title "News"}
                {:start-time 600 :title "Talk"}]})

  (:effects (step (profile/progress sample ["feed-1" "ep-1"] 100 3600
                                    #inst "2026-01-01T02:00:00Z")
                  [:chapter ["feed-1" "ep-1"] :next doc]
                  #inst "2026-01-01T02:00:05Z"))

  ;; a sleep timer that stops the player at the end of this episode
  (step (profile.queue/enqueue sample ["feed-1" "ep-2"]
                               #inst "2026-01-01T02:00:00Z")
        [:ended ["feed-1" "ep-1"]]
        #inst "2026-01-01T03:00:00Z"
        {:sleep {:episodes 1}})
  #_.)
