(ns dk.simongray.podcast.app.media-session
  "The Media Session of a browser for the episode that plays: what the lock
  screen and the notification show, and what their buttons and those of
  the headphones do, as player events for library/step.

  The functions are pure but for apply! and position!, which set the Media
  Session of the page in ClojureScript, and do nothing where the browser
  has none. A player of the episode `id` in the media element `audio`
  wires them up like this, with a dispatch! that runs an event through
  library/step and does its effects:

      (media-session/apply!
       (library/playback @lib id)
       (media-session/actions @lib id)
       (fn [details]
         (doseq [event (media-session/events @lib id (.-currentTime audio)
                                             details)]
           (dispatch! event))))

  The next and previous buttons go where the listener's preference
  :track-buttons says, as profile/default-preferences has it."
  (:require [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.time :as time]))

;; W3C Media Session, Working Draft of 5 June 2026, the MediaSessionAction
;; enum, MediaSessionActionDetails and MediaPositionState
(def seek-actions
  "The actions that move within the episode."
  #{"seekbackward" "seekforward" "seekto"})

(def track-actions
  "The actions of the next and previous buttons."
  #{"previoustrack" "nexttrack"})

(defn- track-buttons
  "The listener's choice in `library` of where the next and previous
  buttons go."
  [library]
  (:track-buttons (profile/preferences (:profile library))))

(defn- chapters-of
  "The chapters document of the episode `id` of `library`, with the `opts`
  of library/playback, or nil."
  [library id opts]
  (:document (:chapters (library/playback library id opts))))

(defn actions
  "The actions of the Media Session that do something for the episode `id`
  of `library`, with the `opts` of library/playback: the seeks, and the
  next and previous buttons, unless they go to chapters and the episode
  has none."
  ([library id]
   (actions library id {}))
  ([library id opts]
   (let [chapters? (seq (chapters/visible (chapters-of library id opts)))
         queue?    (#{:queue :chapters-first} (track-buttons library))]
     (cond-> seek-actions
       (or chapters? queue?) (into track-actions)))))

(defn- track-event
  "The player event of the `direction` button, :next or :previous, for the
  episode `id` at `position`, by where the buttons go, `mode`, and the
  chapters `doc` of the episode, or nil. A mode of a later version goes to
  chapters."
  [id position direction mode doc]
  (let [next?   (= :next direction)
        ;; as on most players, the previous button restarts what plays,
        ;; unless it has only just started
        start?  (< position chapters/restart)
        chapter (when (and doc (chapters/jump doc position direction))
                  [:chapter id direction doc])
        restart [:skip id (- position)]]
    (case mode
      :queue          (if (or next? start?) [direction id] restart)
      :chapters-first (cond
                        next?   (or chapter [:next id])
                        start?  [:previous id]
                        :else   (or chapter restart))
      chapter)))

(defn events
  "The player events of library/step that the Media Session action of
  `details` asks for, on the episode `id` of `library` at `position`
  seconds, with the `opts` of library/playback. The details are those of
  MediaSessionActionDetails as a map, e.g. {:action \"seekto\" :seekTime
  754}.

  The first event reports the position, and the next one moves:

  - a seek back or forward goes the :seekOffset of the details, or else
    the amount that the listener prefers
  - a seek to a time goes to its :seekTime
  - the next and previous buttons go to chapters or episodes of the
    queue, as the listener's :track-buttons say. The previous button
    restarts the episode or chapter, but goes to the one before in its
    first few seconds, and the next button does nothing after the last
    chapter, unless the buttons go to the queue once no chapter is left.

  Another action gives no events."
  ([library id position details]
   (events library id position details {}))
  ([library id position {:keys [action seekOffset seekTime] :as details} opts]
   (let [position (if (time/finite? position) position 0)
         doc      (chapters-of library id opts)
         track    #(track-event id position % (track-buttons library) doc)
         event    (case action
                    "seekbackward"  [:skip id (if (number? seekOffset)
                                                (- seekOffset)
                                                :back)]
                    "seekforward"   [:skip id (if (number? seekOffset)
                                                seekOffset
                                                :forward)]
                    "seekto"        (when (time/finite? seekTime)
                                      [:skip id (- seekTime position)])
                    "previoustrack" (track :previous)
                    "nexttrack"     (track :next)
                    nil)]
     (if event
       [[:progress id position nil] event]
       []))))

(defn position-state
  "The position state of the Media Session for a player `position` seconds
  into a `duration` at `rate`, as a map of the keys of MediaPositionState,
  or nil while the duration is unknown, since setPositionState throws
  without one. The position stays within the duration, and a rate that
  isn't a number other than 0 counts as 1."
  [duration rate position]
  (when (and (number? duration) (pos? duration))
    {:duration     duration
     :playbackRate (if (and (time/finite? rate) (not (zero? rate))) rate 1.0)
     :position     (min duration
                        (max 0 (if (time/finite? position) position 0)))}))

#?(:cljs
   (defn- media-session
     "The Media Session of the page, or nil where the browser has none."
     []
     (some-> js/globalThis .-navigator .-mediaSession)))

#?(:cljs
   (defn apply!
     "Show the `playback` of library/playback in the Media Session of the
     page, and have each of the `actions` call `handle-fn` with the details
     of the action as a map. The other seeks and track buttons go back to
     what the browser does. Nothing happens where the browser has no Media
     Session."
     [playback actions handle-fn]
     (when-let [^js session (media-session)]
       (set! (.-metadata session)
             (when-let [metadata (:metadata playback)]
               (js/MediaMetadata. (clj->js metadata))))
       (doseq [action (into seek-actions track-actions)]
         (let [handler (when (contains? actions action)
                         #(handle-fn (js->clj % :keywordize-keys true)))]
           ;; a browser throws for an action that it doesn't know
           (try
             (.setActionHandler session action handler)
             (catch :default _ nil)))))))

#?(:cljs
   (defn position!
     "Show in the Media Session of the page where the media element `el`
     is, e.g. at its loadedmetadata, play, pause, seeked and ratechange
     events. Nothing happens until its duration is known, or where the
     browser has no Media Session."
     [^js el]
     (when-let [^js session (media-session)]
       (when-let [state (position-state (.-duration el)
                                        (.-playbackRate el)
                                        (.-currentTime el))]
         (when (.-setPositionState session)
           (.setPositionState session (clj->js state)))))))
