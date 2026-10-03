(ns inspector.player.view
  "The inspector's player as Hiccup: pure functions of the page state with
  event handlers as data, so that what the player shows is tested as data
  under Node and Replicant's dispatch performs the events.

  The state is `{:profile … :titles {id episode} :readout \"…\" :error \"…\"}`,
  the profile being dk.simongray.podcast.app.profile's and an episode what
  /episode.edn serves. The profile also holds the episodes that the
  devices of the listener added, under `:inspector/episodes`, so that a
  sync brings their data too. An event is a vector whose first element
  names it: `[:play id]`, `[:skip id :back]`, `[:ended id]`, `[:sleep 1]`,
  `[:play-next id]`, `[:dequeue id]`, `[:prefer :skip-back]`, `[:sync]`,
  and from the audio element `[:audio/loaded id position]` and
  `[:audio/time id]`. The shell in inspector.player performs them."
  (:require [cljs.pprint :as pprint]
            [dk.simongray.podcast.app.profile :as profile]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.time :as time]))

(defn item-href
  "The inspector's page for the `episode`."
  [{:keys [url guid]}]
  (str "/item?url=" (js/encodeURIComponent url) "&guid=" (js/encodeURIComponent guid)))

(defn episode-of
  "What the page `state` knows of the episode `id`: the data that
  /episode.edn served to this browser, or else the data that a device of
  the listener wrote into the profile. Nil when no device told."
  [{:keys [profile titles]} id]
  (or (get titles id)
      (:value (get-in profile [:inspector/episodes id]))))

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
         enclosure [:audio#player {:controls true :preload "metadata" :src enclosure
                                   :replicant/key id
                                   :on {:loadedmetadata [:audio/loaded id position]
                                        :timeupdate     [:audio/time id]
                                        :ended          [:ended id]}}]
         episode   [:span.muted "No enclosure to play."]
         :else     [:span.muted "This browser has no data of the episode. Remove it, or open its item and play it."])
       [:span.muted (or readout (str "at " (time/format-clock (or position 0))))]
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
   (if (empty? (profile/episodes profile))
     [:p.muted "Nothing played yet."]
     [:ul.compact
      (for [id (profile/history profile)
            :let [{:keys [position played?]} (profile/episode profile id)]]
        [:li {:replicant/key id}
         (episode-link state id)
         [:span.muted (str " · " (if played? "played" (str "at " (time/format-clock (or position 0)))))]
         (when (and (episode-of state id) (not= id (profile/current profile)))
           (list " " [:button {:on {:click [:play id]}} "Play now"]))])])])

(defn- preferences
  [{:keys [profile]}]
  (let [{:keys [skip-forward skip-back]} (profile/preferences profile)
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
  [{:keys [profile synced syncing?]}]
  (let [known (crdt/devices profile)]
    [:section
     [:h2 "Devices"]
     [:p.muted "Every device writes its own document to the inspector's folder, served over WebDAV at /dav/, and reads the others'."]
     (if (empty? known)
       [:p.muted "No device has described itself yet."]
       [:ul.compact
        (for [[id {:keys [name]}] (sort-by (comp str :name val) known)]
          [:li {:replicant/key id}
           (or name id)
           (when (= id (:device profile)) [:span.badge "this browser"])
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
  [{:keys [profile error] :as state}]
  [:div
   [:p.muted "The listener's profile (dk.simongray.podcast.app.profile), running in this browser and kept in localStorage; Replicant renders it, the audio element in the bar reports to it, and its effects drive the audio."]
   (when error [:p.error error])
   (when-let [id (profile/current profile)]
     [:p "Now playing: " (episode-link state id)])
   (up-next state)
   (history state)
   (preferences state)
   (devices state)
   [:section
    [:h2 "State"]
    [:pre (with-out-str (pprint/pprint profile))]]])
