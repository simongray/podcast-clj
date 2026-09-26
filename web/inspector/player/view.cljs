(ns inspector.player.view
  "The inspector's player as Hiccup: pure functions of the page state with
  event handlers as data, so that what the player shows is tested as data
  under Node and Replicant's dispatch performs the events.

  The state is `{:player … :titles {id episode} :readout \"…\" :error \"…\"}`,
  the player being dk.simongray.podcast.app.player's and an episode what
  /episode.edn serves. An event is a vector whose first element names it:
  `[:skip id :back]`, `[:ended id]`, `[:play-next id]`, `[:dequeue id]`,
  `[:prefer :skip-back]`, `[:sync]`, and from the audio element
  `[:audio/loaded id position]` and `[:audio/time id]`. The shell in
  inspector.player performs them."
  (:require [cljs.pprint :as pprint]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.time :as time]))

(defn item-href
  "The inspector's page for the `episode`."
  [{:keys [url guid]}]
  (str "/item?url=" (js/encodeURIComponent url) "&guid=" (js/encodeURIComponent guid)))

(defn- episode-link
  [titles id]
  (let [{:keys [title feed-title] :as episode} (titles id)]
    (list [:a {:href (item-href episode)} (or title (pr-str id))]
          " " [:span.muted feed-title])))

(defn bar
  "The persistent player: the current episode, its audio element and the
  skip buttons; nil when nothing is queued."
  [{:keys [player titles readout]}]
  (when-let [id (player/current player)]
    (let [{:keys [enclosure]} (titles id)
          {:keys [skip-forward skip-back]} (player/preferences player)
          position (player/resume-position player (first id) id)]
      [:div.bar
       [:span (episode-link titles id)]
       (if enclosure
         [:audio#player {:controls true :preload "metadata" :src enclosure
                         :replicant/key id
                         :on {:loadedmetadata [:audio/loaded id position]
                              :timeupdate     [:audio/time id]
                              :ended          [:ended id]}}]
         [:span.muted "No enclosure to play."])
       [:span.muted (or readout (str "at " (time/format-clock (or position 0))))]
       [:span
        [:button {:on {:click [:skip id :back]}} (str "−" skip-back "s")]
        " "
        [:button {:on {:click [:skip id :forward]}} (str "+" skip-forward "s")]
        " "
        [:button {:on {:click [:ended id]}} "Ended"]]])))

(defn- up-next
  [{:keys [player titles]}]
  (let [queue (rest (player/queue player))]
    [:section
     [:h2 "Up next"]
     (if (empty? queue)
       [:p.muted (if (player/current player)
                   "Nothing after this one. Open an item and add it."
                   "Nothing queued. Open an item and add it.")]
       [:ol
        (for [id queue]
          [:li {:replicant/key id}
           (episode-link titles id)
           (when (player/played? player id) [:span.badge "played"])
           " "
           [:button {:on {:click [:play-next id]}} "Play next"]
           " "
           [:button {:on {:click [:dequeue id]}} "Remove"]])])]))

(defn- history
  [{:keys [player titles]}]
  [:section
   [:h2 "History"]
   (if (empty? (:episodes player))
     [:p.muted "Nothing played yet."]
     [:ul.compact
      (for [id (player/history player)
            :let [{:keys [position played?]} (player/episode player id)]]
        [:li {:replicant/key id}
         (episode-link titles id)
         [:span.muted (str " · " (if played? "played" (str "at " (time/format-clock (or position 0)))))]])])])

(defn- preferences
  [{:keys [player]}]
  (let [{:keys [skip-forward skip-back]} (player/preferences player)
        field (fn [label k value]
                [:label label " "
                 [:input {:type "number" :min 1 :max 300 :value value :style {:width "4em"}
                          :on {:change [:prefer k]}}]
                 " s"])]
    [:section
     [:h2 "Preferences"]
     [:p (field "Skip back" :skip-back skip-back) "   " (field "Skip forward" :skip-forward skip-forward)]]))

(defn- devices
  "The listener's devices and the sync through the inspector's folder."
  [{:keys [player synced syncing?]}]
  (let [known (player/devices player)]
    [:section
     [:h2 "Devices"]
     [:p.muted "Every device writes its own document to the inspector's folder, served over WebDAV at /dav/, and reads the others'."]
     (if (empty? known)
       [:p.muted "No device has described itself yet."]
       [:ul.compact
        (for [[id {:keys [name]}] (sort-by (comp str :name val) known)]
          [:li {:replicant/key id}
           (or name id)
           (when (= id (:device player)) [:span.badge "this browser"])
           " " [:span.muted id]])])
     [:p
      [:button {:on {:click [:sync]} :disabled syncing?} (if syncing? "Syncing…" "Sync now")]
      (when synced
        [:span.muted
         (str " Synced at " (.toLocaleTimeString ^js (:at synced)))
         (when-let [failed (:failed synced)]
           (str "; failed: " (pr-str failed)))
         (when (seq (:report synced))
           (str "; " (count (:report synced)) " remark" (when (> (count (:report synced)) 1) "s")))])]
     (when (seq (:report synced))
       [:ul.compact
        (for [{:keys [id message]} (:report synced)]
          [:li {:replicant/key (str id)} [:code (str id)] " " message])])]))

(defn page
  "The queue page: the detailed view of the state the bar plays from."
  [{:keys [player titles error] :as state}]
  [:div
   [:p.muted "The player state of dk.simongray.podcast.app.player, running in this browser and kept in localStorage; Replicant renders it, the audio element in the bar reports to it, and its effects drive the audio."]
   (when error [:p.error error])
   (when-let [id (player/current player)]
     [:p "Now playing: " (episode-link titles id)])
   (up-next state)
   (history state)
   (preferences state)
   (devices state)
   [:section
    [:h2 "State"]
    [:pre (with-out-str (pprint/pprint player))]]])
