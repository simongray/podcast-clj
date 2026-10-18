(ns inspector.player.view
  "The inspector's player as Hiccup: pure functions of the page state with
  event handlers as data for Replicant, so that what the player shows is
  tested as data under Node.

  The state holds the listener's profile and, for each episode id, what
  /episode.edn served for it:

      {:profile … :titles {id episode} :media {…} :error \"…\"}

  It also holds what the player follows of the media element:

  - :media, its state, as play-nice's media/watch! reports it, and
    :tracks, its text tracks, as tracks/watch! reports them
  - :answer, the browser's answer to playing it
  - :volume-locked?, whether the browser locks the volume
  - :transcript? and :caption-style, how the listener shows the transcript
  - :pip, :fullscreen and :remote, the states of its Picture-in-Picture,
    fullscreen and casting, and :device-answer, a refusal of one of them
  - :start, the position by episode id that a link asked to play from

  An episode that another device added comes through a sync as a remote
  item, without that data. An event is a vector whose first element names
  it:

      [:play id] [:skip id :back] [:ended id] [:sleep 1] [:play-next id]
      [:dequeue id] [:prefer :skip-back] [:sync]
      [:press {:action :play}] [:seek-at] [:rate feed-guid] [:volume]
      [:muted false] [:transcript true] [:caption-style]
      [:pip true] [:fullscreen] [:cast]
      [:folder/pick] [:folder/allow] [:folder/forget]
      [:document/save] [:document/load] [:device/retire id]

  A press is that of a button of the lock screen, with the details of its
  action, and the player presses the same handlers. The audio or video
  element raises [:media/mounted id source] and [:media/unmounted id] as
  it comes and goes, where the source is what media/source! loads, and
  [:ended id] when it has played to the end."
  (:require [cljs.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.play-nice.controls :as controls]
            [dk.simongray.play-nice.media :as media-element]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.time :as time]))

(defn item-href
  "The inspector's page for the `episode`."
  [{:keys [url guid] :as episode}]
  (str "/item?url=" (js/encodeURIComponent url)
       "&guid=" (js/encodeURIComponent guid)))

(defn episode-of
  "What the page `state` knows of the episode `id`: the data that
  /episode.edn served to this browser, or nil before it did."
  [{:keys [titles] :as state} id]
  (get titles id))

(defn- episode-link
  "The link to the page of the episode `id` that the page `state` knows,
  with the title of its show. An episode without data is its id."
  [state id]
  (if-let [{:keys [title feed-title] :as episode} (episode-of state id)]
    (list [:a {:href (item-href episode)} (or title (pr-str id))]
          " " [:span.muted feed-title])
    (list [:code (pr-str id)])))

(defn- readout
  "Where the media element is, by its `media` state, or the `position` to
  resume at before it reports."
  [{:keys [current-time duration] :as media} position]
  (str "at " (time/format-clock (or current-time position 0))
       (when (js/Number.isFinite duration)
         (str " of " (time/format-clock duration)))))

(defn- answer-note
  "What the browser's `answer` to playing the episode means to the
  listener, given the `media` state: it plays muted, it waits for a
  click, or it won't play here."
  [{:keys [refused waiting] :as answer} media]
  (cond
    (and (:muted answer) (:muted media))
    [:span "Playing muted, since the browser refused the sound."]

    (and (= :not-allowed refused) waiting)
    [:span.muted "The browser plays it at your next click or key press."]

    (= :not-allowed refused)
    [:span.muted "The browser won't let this page play it."]))

(def rates
  "The playback rates that the bar offers, besides that of the show."
  [0.75 1 1.25 1.5 1.75 2 2.5 3])

(defn- seek-bar
  "A seek bar for the `media` state, with the ARIA attributes of a slider,
  whose keys the player listens to, and which a pointer presses or drags."
  [{:keys [current-time duration] :as media}]
  (let [played (if (and (js/Number.isFinite duration) (pos? duration))
                 (/ (or current-time 0) duration)
                 0)]
    [:div.seek-bar
     (merge (controls/slider-attributes media)
            {:tabindex   0
             :aria-label "Position"
             :on         {:pointerdown [:seek-at]
                          :pointermove [:seek-at]
                          :pointerup   [:seek-at]}})
     [:div.played {:style {:width (str (* 100 played) "%")}}]]))

(defn- rate-select
  "A choice of the playback rate for the show `feed-guid`, at `x`."
  [feed-guid x]
  [:label "Speed "
   [:select {:on {:change [:rate feed-guid]}}
    (for [r (sort (distinct (conj rates x)))]
      [:option {:replicant/key r :value (str r) :selected (= r x)}
       (str r "×")])]])

(defn- volume-controls
  "A mute button for the `media` state, and a volume slider on the cubic
  curve, unless the browser locks the volume, as `locked?` says, or
  hasn't said yet."
  [{:keys [muted] :as media} locked?]
  [:span.controls
   [:button {:on {:click [:muted (not muted)]}} (if muted "Unmute" "Mute")]
   (when (false? locked?)
     [:input {:type       "range"
              :min        0
              :max        1
              :step       0.01
              :aria-label "Volume"
              :value      (media-element/level (merge {:volume 1} media)
                                               {:curve :cubic})
              :on         {:input [:volume]}}])])

(defn playable
  "What the browser's `info` from play-nice's decoding-info! says of a
  file, in a few words."
  [{:keys [supported smooth can-play-type] :as info}]
  (cond
    (and supported smooth)      "plays, smoothly"
    supported                   "plays"
    (= :probably can-play-type) "probably plays"
    (= :maybe can-play-type)    "may play"
    :else                       "won't play"))

(def caption-styles
  "The styles of captions that the bar offers, by name, of which the first
  keeps the browser's own."
  {"The browser's own" nil
   "Large"             {:font-scale 1.5}
   "Yellow"            {:color :yellow :background-opacity 0.6}
   "Outlined"          {:background-opacity 0 :edge :uniform}})

(defn- active-text
  "The text of the active cue of the first of the `tracks` of `kind`, e.g.
  the title of the chapter that plays."
  [tracks kind]
  (some #(when (= kind (:kind %)) (:text (first (:active-cues %)))) tracks))

(defn- transcript-controls
  "A button that shows or hides the transcript as `shown?` says, and for a
  `video?` a choice of the style of its captions, at `chosen`."
  [shown? video? chosen]
  (list
   [:button {:on {:click [:transcript (not shown?)]}}
    (if shown? "Hide transcript" "Transcript")]
   (when (and shown? video?)
     [:label "Captions "
      [:select {:on {:change [:caption-style]}}
       (for [name (keys caption-styles)]
         [:option {:replicant/key name
                   :selected      (= name (or chosen "The browser's own"))}
          name])]])))

(defn- device-controls
  "Buttons for a `video?` to enter or leave Picture-in-Picture, while the
  `pip` state says it can, and to go fullscreen, and a button that casts
  the media element unless its `remote` playback has no devices."
  [video? pip {:keys [availability state] :as remote}]
  (list
   (when (and video? pip)
     (let [in? (:picture-in-picture pip)]
       [:button {:on {:click [:pip (not in?)]}}
        (if in? "Leave Picture-in-Picture" "Picture-in-Picture")]))
   (when video?
     [:button {:on {:click [:fullscreen]}} "Fullscreen"])
   (when (and remote (not= :unavailable availability))
     [:button {:on {:click [:cast]}}
      (case state
        :connected  "Casting"
        :connecting "Connecting…"
        "Cast")])))

(defn- device-note
  "What the browser refused of the device that the listener asked for, by
  its `answer`, unless the listener closed the picker."
  [{:keys [what refused] :as answer}]
  (when (and refused (not= :dismissed refused))
    [:span.muted (str what " refused: " (str/replace (name refused) "-" " "))]))

(defn bar
  "The persistent player of the page `state`: the current episode, its
  chapter, its audio or video element and its controls, its transcript,
  the skip buttons and the sleep timer, or nil when nothing is queued.

  The media element loads the episode when it's mounted, at the position
  to resume at, or the :start that a link asked for, and the rate of the
  show. An episode that came through a sync from a device that told no
  data of it has nothing to play. The bar tells so, and the listener
  removes it."
  [{:keys [profile media answer sleep volume-locked? tracks transcript?
           caption-style pip remote device-answer]
    :as   state}]
  (when-let [id (profile.queue/current profile)]
    (let [{:keys [enclosure video?] :as episode} (episode-of state id)
          {:keys [skip-forward skip-back]} (profile/preferences profile)
          [feed-guid] id
          position (profile/resume-position profile feed-guid id)
          rate     (profile/playback-rate profile feed-guid)
          paused   (:paused media true)
          chapter  (active-text tracks :chapters)]
      [:div.bar
       [:span (episode-link state id)
        (when (seq chapter) (list " · " [:span.muted chapter]))]
       (cond
         enclosure
         (list
          [(if video? :video#player :audio#player)
           {:preload              "metadata"
            :replicant/key        id
            :replicant/on-mount   [:media/mounted id
                                   {:src           enclosure
                                    :position      (get-in state [:start id]
                                                           position)
                                    :playback-rate rate}]
            :replicant/on-unmount [:media/unmounted id]
            :on                   {:ended [:ended id]}}]
          [:span.controls.transport
           [:button {:on {:click [:press {:action (if paused :play :pause)}]}}
            (if paused "Play" "Pause")]
           (seek-bar media)
           [:span.muted.readout (readout media position)]]
          (rate-select feed-guid (:playback-rate media rate))
          (volume-controls media volume-locked?)
          (device-controls video? pip remote)
          (when (some #(= :captions (:kind %)) tracks)
            (transcript-controls transcript? video? caption-style)))

         episode
         [:span.muted "No enclosure to play."]

         :else
         [:span.muted (str "This browser has no data of the episode. Remove "
                           "it, or open its item and play it.")])
       (answer-note answer media)
       (device-note device-answer)
       [:span
        [:button {:on {:click [:skip id :back]}} (str "−" skip-back "s")]
        " "
        [:button {:on {:click [:skip id :forward]}} (str "+" skip-forward "s")]
        " "
        [:button {:on {:click [:ended id]}} "Ended"]
        " "
        [:button {:on {:click [:dequeue id]}} "Remove"]
        " "
        [:button {:on {:click [:sleep (when-not sleep 1)]}}
         (if sleep "Play on after this episode" "Stop after this episode")]]
       ;; a video shows its transcript as captions
       (when (and enclosure transcript? (not video?))
         [:div.cue {:aria-live "polite"} (active-text tracks :captions)])])))

(defn- up-next
  [{:keys [profile] :as state}]
  (let [queue (rest (profile.queue/queue profile))]
    [:section
     [:h2 "Up next"]
     (if (empty? queue)
       [:p.muted (if (profile.queue/current profile)
                   "Nothing after this one. Open an item and add it."
                   "Nothing queued. Open an item and add it.")]
       [:ol
        (for [id queue]
          [:li {:replicant/key id}
           (episode-link state id)
           (when (profile/played? profile id) [:span.badge "played"])
           " "
           [:button {:on {:click [:play id]}} "Play now"]
           " "
           [:button {:on {:click [:play-next id]}} "Play next"]
           " "
           [:button {:on {:click [:dequeue id]}} "Remove"]])])]))

(defn- history
  [{:keys [profile] :as state}]
  [:section
   [:h2 "History"]
   (if (empty? (profile/episode-entries profile))
     [:p.muted "Nothing played yet."]
     [:ul.compact
      (for [id (profile/history profile)
            :let [{:keys [position played?]}
                  (profile/episode-entry profile id)

                  where (if played?
                          "played"
                          (str "at " (time/format-clock (or position 0))))]]
        [:li {:replicant/key id}
         (episode-link state id)
         [:span.muted (str " · " where)]
         (when (and (episode-of state id)
                    (not= id (profile.queue/current profile)))
           (list " " [:button {:on {:click [:play id]}} "Play now"]))])])])

(defn- preferences
  [{:keys [profile]}]
  (let [{:keys [skip-forward skip-back]} (profile/preferences profile)
        field (fn [label k value]
                [:label label " "
                 [:input {:type  "number"
                          :min   1
                          :max   300
                          :value value
                          :style {:width "4em"}
                          :on    {:change [:prefer k]}}]
                 " s"])]
    [:section
     [:h2 "Preferences"]
     [:p
      (field "Skip back" :skip-back skip-back)
      "   "
      (field "Skip forward" :skip-forward skip-forward)]]))

(defn- devices
  "The listener's devices and the sync through the inspector's folder, in
  the page `state`. A device that lags behind can be retired, since it
  holds back the removal of tombstones."
  [{:keys [profile synced syncing?] :as state}]
  (let [known   (crdt/devices profile)
        lagging (crdt/lagging profile)]
    [:section
     [:h2 "Devices"]
     [:p.muted (str "Every device writes its own document to the "
                    "inspector's folder, served over WebDAV at /dav/, and "
                    "reads the others'. This browser syncs when the page "
                    "opens, when it's hidden, and when it's due while "
                    "nothing plays.")]
     (if (empty? known)
       [:p.muted "No device has described itself yet."]
       [:ul.compact
        (for [[id {:keys [name]}] (sort-by (comp str :name val) known)]
          [:li {:replicant/key id}
           (or name id)
           (when (= id (:device profile)) [:span.badge "this browser"])
           (when (contains? lagging id) [:span.badge "behind"])
           " " [:span.muted id]
           (when (not= id (:device profile))
             (list " "
                   [:button {:on {:click [:device/retire id]}} "Retire"]))])])
     [:p
      [:button {:on {:click [:sync]} :disabled syncing?}
       (if syncing? "Syncing…" "Sync now")]
      (when synced
        (let [report  (:report synced)
              failed  (seq (filter #(= :sync/backend-failed (:id %)) report))
              reasons (for [{:keys [backend reason]} failed]
                        (str backend ": " reason))
              n       (count report)]
          [:span.muted
           (str " Synced at " (.toLocaleTimeString ^js (:at synced)))
           (when failed
             (str "; failed: " (str/join ", " reasons)))
           (when (seq report)
             (str "; " n " remark" (when (> n 1) "s")))]))]
     (when (seq (:report synced))
       [:ul.compact
        (for [{:keys [id message]} (:report synced)]
          [:li {:replicant/key (str id)} [:code (str id)] " " message])])
     (when-let [{:keys [at error]} (:refreshed state)]
       (let [{:keys [added report]} (get-in state [:library :refresh])
             n (count added)]
         (list
          [:p.muted
           (str "A web worker refreshed the feeds at "
                (.toLocaleTimeString ^js at)
                (if error
                  (str ", and failed: " error)
                  (str ", and found " n " new episode" (when (not= 1 n) "s")))
                ".")]
          (when (and (not error) (seq report))
            [:ul.compact
             (for [{:keys [id url message]} report]
               [:li {:replicant/key (str id url)}
                [:code (str id)] " " (when url [:span.muted url " "])
                message])]))))]))

(defn- sync-folder
  "The sync through a folder that a sync client mirrors, in the page
  `state`: a folder that the browser keeps, where it can pick one, and by
  hand in every browser."
  [{:keys [folder can-pick?] :as state}]
  (let [{:keys [name permission]} folder]
    [:section
     [:h2 "Sync folder"]
     [:p.muted (str "A folder that a sync client mirrors, e.g. Dropbox or "
                    "Syncthing, keeps the devices in step too: each device "
                    "writes its own document into it.")]
     (cond
       (not can-pick?)
       [:p.muted (str "This browser can't keep a folder, so sync through "
                      "one by hand.")]

       (nil? folder)
       [:p [:button {:on {:click [:folder/pick]}} "Choose a sync folder…"]]

       (= :granted permission)
       [:p "Syncing through " [:code name] " too. "
        [:button {:on {:click [:folder/forget]}} "Stop"]]

       :else
       [:p "The browser needs your permission for " [:code name] " again. "
        [:button {:on {:click [:folder/allow]}} "Allow"] " "
        [:button {:on {:click [:folder/forget]}} "Stop"]])
     [:p "By hand: "
      [:button {:on {:click [:document/save]}} "Save this browser's document"]
      " into the folder, and load the folder to read the others' documents: "
      [:input {:type            "file"
               :webkitdirectory true
               :on              {:change [:document/load]}}]]]))

(defn page
  "The queue page: the detailed view of the page `state`, which the bar
  plays from."
  [{:keys [profile error] :as state}]
  [:div
   [:p.muted (str "The listener's profile (dk.simongray.podcast.profile), "
                  "running in this browser and kept in IndexedDB, which "
                  "its tabs share; Replicant renders it, the audio element "
                  "in the bar reports to it, and its effects drive the audio.")]
   (when error [:p.error error])
   (when-let [id (profile.queue/current profile)]
     [:p "Now playing: " (episode-link state id)])
   (up-next state)
   (history state)
   (preferences state)
   (devices state)
   (sync-folder state)
   [:section
    [:h2 "State"]
    [:pre (with-out-str (pprint/pprint profile))]]])
