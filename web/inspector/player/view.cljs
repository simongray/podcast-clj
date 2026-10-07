(ns inspector.player.view
  "The inspector's player as Hiccup: pure functions of the page state with
  event handlers as data, so that what the player shows is tested as data
  under Node and Replicant's dispatch performs the events.

  The state is `{:profile … :titles {id episode} :readout \"…\" :error \"…\"}`,
  the profile being dk.simongray.podcast.profile's and an episode what
  /episode.edn serves. An episode that another device added comes through
  a sync as a remote item, whose data the shell fetches. An event is a
  vector whose first element names it: `[:play id]`, `[:skip id :back]`,
  `[:ended id]`, `[:sleep 1]`,
  `[:play-next id]`, `[:dequeue id]`, `[:prefer :skip-back]`, `[:sync]`,
  `[:folder/pick]`, `[:folder/allow]`, `[:folder/forget]`,
  `[:document/save]`, `[:document/load]`, `[:device/retire id]`, and from
  the audio element `[:audio/loaded id position]`, `[:audio/time id]` and
  `[:audio/paused id]`.
  The shell in inspector.player performs them."
  (:require [cljs.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.crdt :as crdt]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.time :as time]))

(defn item-href
  "The inspector's page for the `episode`."
  [{:keys [url guid] :as episode}]
  (str "/item?url=" (js/encodeURIComponent url)
       "&guid=" (js/encodeURIComponent guid)))

(defn episode-of
  "What the page `state` knows of the episode `id`: the data that
  /episode.edn served to this browser, or nil before it did."
  [{:keys [titles]} id]
  (get titles id))

(defn- episode-link
  "The link to the page of the episode `id`, with the title of its show.
  An episode without data is its id."
  [state id]
  (if-let [{:keys [title feed-title] :as episode} (episode-of state id)]
    (list [:a {:href (item-href episode)} (or title (pr-str id))]
          " " [:span.muted feed-title])
    (list [:code (pr-str id)])))

(defn bar
  "The persistent player: the current episode, its audio element, the
  skip buttons and the sleep timer; nil when nothing is queued.

  An episode that came through a sync from a device that told no data of
  it has nothing to play. The bar tells so, and the listener removes it."
  [{:keys [profile readout sleep] :as state}]
  (when-let [id (profile/current profile)]
    (let [{:keys [enclosure] :as episode} (episode-of state id)
          {:keys [skip-forward skip-back]} (profile/preferences profile)
          position (profile/resume-position profile (first id) id)]
      [:div.bar
       [:span (episode-link state id)]
       (cond
         enclosure
         [:audio#player {:controls true :preload "metadata" :src enclosure
                         :replicant/key id
                         :on {:loadedmetadata [:audio/loaded id position]
                              :timeupdate     [:audio/time id]
                              :pause          [:audio/paused id]
                              :ended          [:ended id]}}]

         episode
         [:span.muted "No enclosure to play."]

         :else
         [:span.muted (str "This browser has no data of the episode. Remove "
                           "it, or open its item and play it.")])
       [:span.muted (or readout
                        (str "at " (time/format-clock (or position 0))))]
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
         (if sleep "Play on after this episode" "Stop after this episode")]]])))

(defn- up-next
  [{:keys [profile] :as state}]
  (let [queue (rest (profile/queue profile))]
    [:section
     [:h2 "Up next"]
     (if (empty? queue)
       [:p.muted (if (profile/current profile)
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
                    (not= id (profile/current profile)))
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
  "The listener's devices and the sync through the inspector's folder. A
  device that lags behind can be retired, since it holds back the removal
  of tombstones."
  [{:keys [profile synced syncing?]}]
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
          [:li {:replicant/key (str id)} [:code (str id)] " " message])])]))

(defn- sync-folder
  "The sync through a folder that a sync client mirrors: a folder that the
  browser keeps, where it can pick one, and by hand in every browser."
  [{:keys [folder can-pick?]}]
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
  "The queue page: the detailed view of the state the bar plays from."
  [{:keys [profile error] :as state}]
  [:div
   [:p.muted (str "The listener's profile (dk.simongray.podcast.profile), "
                  "running in this browser and kept in localStorage; "
                  "Replicant renders it, the audio element in the bar "
                  "reports to it, and its effects drive the audio.")]
   (when error [:p.error error])
   (when-let [id (profile/current profile)]
     [:p "Now playing: " (episode-link state id)])
   (up-next state)
   (history state)
   (preferences state)
   (devices state)
   (sync-folder state)
   [:section
    [:h2 "State"]
    [:pre (with-out-str (pprint/pprint profile))]]])
