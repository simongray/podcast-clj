(ns dk.simongray.podcast.app.media-session
  "The Media Session of a browser for the episode that plays: what the lock
  screen and the notification show, and what their buttons and those of
  the headphones do, as player events for library/step.

  The functions are pure. With the session namespace of the play-nice
  library, a player of the episode `id` in the media element `audio` wires
  them up like this, with a step! that runs an event through library/step
  and does its effects:

      (session/metadata! (:metadata (library/playback @lib id)))
      (session/actions!
       (zipmap (media-session/actions @lib id)
               (repeat (fn [details]
                         (run! step! (media-session/events
                                      @lib id (.-currentTime audio)
                                      details))))))

  The next and previous buttons go where the listener's preference
  :track-buttons says, as profile/default-preferences has it."
  (:require [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.time :as time]))

;; W3C Media Session, Working Draft of 5 June 2026, the MediaSessionAction
;; enum and MediaSessionActionDetails
(def seek-actions
  "The actions that move within the episode."
  #{:seekbackward :seekforward :seekto})

(def track-actions
  "The actions of the next and previous buttons."
  #{:previoustrack :nexttrack})

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
  MediaSessionActionDetails as a map in kebab-case, with the action as a
  keyword, e.g. {:action :seekto :seek-time 754}.

  The first event reports the position, and the next one moves:

  - a seek back or forward goes the :seek-offset of the details, or else
    the amount that the listener prefers
  - a seek to a time goes to its :seek-time
  - the next and previous buttons go to chapters or episodes of the
    queue, as the listener's :track-buttons say. The previous button
    restarts the episode or chapter, but goes to the one before in its
    first few seconds, and the next button does nothing after the last
    chapter, unless the buttons go to the queue once no chapter is left.

  Another action gives no events."
  ([library id position details]
   (events library id position details {}))
  ([library id position {:keys [action seek-offset seek-time] :as details}
    opts]
   (let [position (if (time/finite? position) position 0)
         doc      (chapters-of library id opts)
         track    #(track-event id position % (track-buttons library) doc)
         event    (case action
                    :seekbackward  [:skip id (if (number? seek-offset)
                                               (- seek-offset)
                                               :back)]
                    :seekforward   [:skip id (if (number? seek-offset)
                                               seek-offset
                                               :forward)]
                    :seekto        (when (time/finite? seek-time)
                                     [:skip id (- seek-time position)])
                    :previoustrack (track :previous)
                    :nexttrack     (track :next)
                    nil)]
     (if event
       [[:progress id position nil] event]
       []))))
