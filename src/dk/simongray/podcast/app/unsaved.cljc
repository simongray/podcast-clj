(ns dk.simongray.podcast.app.unsaved
  "The last moves of a web app's player that the library's save may not
  have yet, kept in localStorage at once: the position of the episode that
  plays, and the episode that the listener played last.

  A save of the library takes a few steps, e.g. to IndexedDB, which a page
  that reloads or closes never finishes, while localStorage writes at
  once. At the next start, the library takes what was kept with the time
  it was kept at, so the profile still decides between it and the moves of
  other devices. The episode played last is kept only until a save has
  it, so that a later change of the queue, e.g. on another device, isn't
  undone.

  The keys in localStorage begin with the app's `prefix`, e.g.
  \"my-app.position\" for \"my-app\". Keeping and reading them is for
  ClojureScript only, and the functions of the library work on both
  platforms."
  (:require #?(:cljs [cljs.reader :as reader])
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.time :as time]))

#?(:cljs
   (defn ^:no-doc stored
     "The value that this browser keeps in localStorage under the key of
     the `prefix` and the `name`, as EDN, or nil."
     [prefix name]
     (try
       (some-> (.getItem js/localStorage (str prefix "." name)) reader/read-string)
       (catch :default _ nil))))

#?(:cljs
   (defn ^:no-doc store!
     "Keep the value `v` in localStorage under the key of the `prefix` and
     the `name`, as EDN, where the browser keeps anything."
     [prefix name v]
     (try
       (.setItem js/localStorage (str prefix "." name) (pr-str v))
       (catch :default _ nil))))

#?(:cljs
   (defn keep-position!
     "Keep the `position` and the `duration` of the episode `id`, in
     seconds, with the time, under the app's `prefix`."
     [prefix id position duration]
     (store! prefix "position" {:id id :position position :duration duration :at (js/Date.now)})))

#?(:cljs
   (defn kept-position
     "The last position that this browser kept under the app's `prefix`, as
     a map of the episode's :id, the :position and :duration in seconds,
     and the :at in milliseconds, or nil."
     [prefix]
     (stored prefix "position")))

#?(:cljs
   (defn keep-played!
     "Keep the episode `id` that the player starts, with the time, under the
     app's `prefix`, until a save of the library has it."
     [prefix id]
     (store! prefix "played" {:id id :at (js/Date.now)})))

#?(:cljs
   (defn kept-played
     "The episode that this browser played last under the app's `prefix`,
     as a map of its :id and the :at in milliseconds, while no save of the
     library has it, or nil."
     [prefix]
     (stored prefix "played")))

#?(:cljs
   (defn saved!
     "Note that a save of the library that began at `started`, in
     milliseconds, is done, which forgets the episode that this browser
     played under the app's `prefix` when the save has it."
     [prefix started]
     (when (some-> (kept-played prefix) :at (<= started))
       (try
         (.removeItem js/localStorage (str prefix ".played"))
         (catch :default _ nil)))))

(defn with-kept-position
  "The `library` told of the position that a page `kept`, from
  kept-position, at the time it was kept, unless the library hasn't that
  episode, or its profile has a later position, e.g. of another device.

  The profile orders a write of this device after every write that it has
  seen, whatever its time, so the times are compared here."
  [library {:keys [id position duration at] :as kept}]
  (let [updated (when (and library id)
                  (:updated-at (profile/episode-entry (:profile library) id)))]
    (if (and library id (number? position) (number? at)
             (library/playback library id)
             (or (not (inst? updated)) (> at (inst-ms updated))))
      (dissoc (library/step library [:progress id position duration] {:at (time/->inst at)})
              :effects)
      library)))

(defn with-kept-play
  "The `library` with the episode that a page `played` last, from
  kept-played, made current again at the time it was played, since no
  save had it, unless the library hasn't that episode, e.g. of a show that
  a save would have added, or has it current already."
  [library {:keys [id at] :as played}]
  (if (and library id (number? at)
           (library/playback library id)
           (not= id (some-> library :profile profile.queue/current)))
    (dissoc (library/step library [:play id] {:at (time/->inst at)}) :effects)
    library))

(defn restored
  "The `library` as a page starts: told of the episode that it `played`
  last and of the position that it `kept`, from kept-played and
  kept-position."
  [library played kept]
  (-> library
      (with-kept-play played)
      (with-kept-position kept)))
