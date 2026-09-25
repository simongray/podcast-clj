(ns dk.simongray.podcast.web.player
  "The inspector's player, in the browser. The player state of
  dk.simongray.podcast.player lives here, kept in localStorage; Replicant
  renders it; an audio element plays the current episode and reports back
  through `player/step`, whose effects seek the audio or start the next
  episode. The server only supplies episode data, at /episode.edn.

  The player sits in a bar on every page. Clicking a link fetches the next
  page and swaps only the content below the bar, so that playback survives
  moving between pages; the queue page is the detailed view of the same
  state.

  Built by `clojure -M:cljs compile player` and served at /js/player.js."
  (:require [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [dk.simongray.podcast.player :as player]
            [dk.simongray.podcast.time :as time]
            [replicant.dom :as replicant]))

(def storage-key
  "Where the state lives between visits."
  "dk.simongray.podcast.player")

(defonce ^{:doc "The whole state of the page: `:player` (the library's state),
  `:titles` by episode id, and what the audio element last reported."}
  app
  (atom nil))

(defn- now
  []
  (js/Date.))

(defn- audio-element
  []
  (js/document.getElementById "player"))

;;;; State

(defn- fresh
  []
  {:player (player/state "browser") :titles {}})

(defn- load-state
  []
  (or (try
        (some-> (.getItem js/localStorage storage-key) reader/read-string)
        (catch :default _ nil))
      (fresh)))

(defn- save!
  [state]
  (try
    (.setItem js/localStorage storage-key (pr-str (select-keys state [:player :titles])))
    (catch :default _ nil)))

(defn- perform!
  "Carry out the `effects` `player/step` returned: seek the audio element
  to where a skip landed, or start the next episode once it is rendered."
  [effects]
  (doseq [[kind _ arg] effects]
    (case kind
      :seek (when-let [el (audio-element)]
              (set! (.-currentTime el) arg))
      :play (js/setTimeout #(some-> (audio-element) .play (.catch (fn [_] nil))) 50)
      nil)))

(defn dispatch!
  "Run the player `event` through the library and perform its effects."
  [event]
  (let [{:keys [state effects]} (player/step (:player @app) event (now))]
    (swap! app assoc :player state)
    (perform! effects)))

(defn operate!
  "Apply the player operation `f` to the state, with `args` and the time."
  [f & args]
  (swap! app update :player #(apply f % (concat args [(now)]))))

(defn- skip!
  "Skip the episode `id` by `amount`, telling the library where the audio
  is first so that the skip starts from the truth."
  [id amount]
  (when-let [el (audio-element)]
    (dispatch! [:progress id (js/Math.floor (.-currentTime el)) (js/Math.floor (or (.-duration el) 0))]))
  (dispatch! [:skip id amount]))

(defn- time-update!
  "Show the audio's position, and record it every five seconds."
  [id el]
  (let [t (.-currentTime el) d (.-duration el)]
    (swap! app assoc :readout (str "at " (time/format-clock t) (when (pos? (or d 0)) (str " of " (time/format-clock d)))))
    (when (>= (js/Math.abs (- t (or (:reported @app) -10))) 5)
      (swap! app assoc :reported t)
      (dispatch! [:progress id (js/Math.floor t) (js/Math.floor (or d 0))]))))

;;;; Adding episodes: /queue?add=<feed url>&guid=<item guid>&mode=next|last

(defn- add-from-query!
  []
  (let [params (js/URLSearchParams. js/location.search)
        url    (.get params "add")
        guid   (.get params "guid")
        mode   (.get params "mode")]
    (when (and url guid)
      (js/history.replaceState nil "" "/queue")
      (-> (js/fetch (str "/episode.edn?url=" (js/encodeURIComponent url) "&guid=" (js/encodeURIComponent guid)))
          (.then #(if (.-ok %) (.text %) (throw (js/Error. (str "episode.edn answered " (.-status %))))))
          (.then (fn [edn]
                   (let [{:keys [id] :as episode} (reader/read-string edn)]
                     (swap! app assoc-in [:titles id] episode)
                     (operate! (if (= mode "next") player/play-next player/enqueue) id))))
          (.catch #(swap! app assoc :error (str %)))))))

;;;; Views

(defn- episode-link
  [titles id]
  (let [{:keys [title feed-title url guid]} (titles id)]
    (list [:a {:href (str "/item?url=" (js/encodeURIComponent url) "&guid=" (js/encodeURIComponent guid))}
           (or title (pr-str id))]
          " " [:span.muted feed-title])))

(defn- bar-view
  "The persistent player: the current episode, its audio element and the
  skip buttons; nothing when nothing is queued."
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
                         :on {:loadedmetadata (fn [e]
                                                (let [el (.-target e)]
                                                  (when (and (pos? position) (< position (.-duration el)))
                                                    (set! (.-currentTime el) position))))
                              :timeupdate (fn [e] (time-update! id (.-target e)))
                              :ended (fn [_] (dispatch! [:ended id]))}}]
         [:span.muted "No enclosure to play."])
       [:span.muted (or readout (str "at " (time/format-clock (or position 0))))]
       [:span
        [:button {:on {:click #(skip! id :back)}} (str "−" skip-back "s")]
        " "
        [:button {:on {:click #(skip! id :forward)}} (str "+" skip-forward "s")]
        " "
        [:button {:on {:click #(dispatch! [:ended id])}} "Ended"]]])))

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
           [:button {:on {:click #(operate! player/play-next id)}} "Play next"]
           " "
           [:button {:on {:click #(operate! player/dequeue id)}} "Remove"]])])]))

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
                          :on {:change (fn [e] (when-let [n (parse-long (.. e -target -value))]
                                                 (operate! player/prefer {k n})))}}]
                 " s"])]
    [:section
     [:h2 "Preferences"]
     [:p (field "Skip back" :skip-back skip-back) "   " (field "Skip forward" :skip-forward skip-forward)]]))

(defn- page-view
  "The queue page: the detailed view of the state the bar plays from."
  [{:keys [player titles error] :as state}]
  [:div
   [:p.muted "The player state of dk.simongray.podcast.player, running in this browser and kept in localStorage; Replicant renders it, the audio element in the bar reports to it, and its effects drive the audio."]
   (when error [:p.error error])
   (when-let [id (player/current player)]
     [:p "Now playing: " (episode-link titles id)])
   (up-next state)
   (history state)
   (preferences state)
   [:section
    [:h2 "State"]
    [:pre (with-out-str (pprint/pprint player))]]])

(defn- render!
  []
  (when-let [bar (js/document.getElementById "player-bar")]
    (replicant/render bar (bar-view @app)))
  (when-let [el (js/document.getElementById "app")]
    (replicant/render el (page-view @app))))

;;;; Navigation that keeps the player alive

(defn- same-origin?
  [href]
  (and (seq href)
       (= (.-origin (js/URL. href js/location.href)) js/location.origin)))

(defn- after-navigation!
  []
  (render!)
  (add-from-query!))

(defn- swap-content!
  "Fetch the page at `url` and put its content in place of the current
  one, leaving the player bar untouched, then handle what the new page
  asks for. A page without a content area, such as the raw EDN and XML
  views, is navigated to for real."
  [url push?]
  (-> (js/fetch url)
      (.then #(.text %))
      (.then (fn [html]
               (let [doc     (.parseFromString (js/DOMParser.) html "text/html")
                     content (.getElementById doc "content")
                     target  (js/document.getElementById "content")]
                 (if (and content target)
                   (do (set! (.-innerHTML target) (.-innerHTML content))
                       (set! js/document.title (.-title doc))
                       (when push? (js/history.pushState nil "" url))
                       (js/window.scrollTo 0 0)
                       (after-navigation!))
                   (set! js/location.href url)))))
      (.catch (fn [_] (set! js/location.href url)))))

(defn- plain-click?
  [e]
  (and (zero? (.-button e))
       (not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e)))))

(defn- intercept-navigation!
  "Turn same-origin link clicks and GET form submissions into content swaps,
  and the back and forward buttons too."
  []
  (.addEventListener js/document "click"
                     (fn [e]
                       (when-let [a (some-> (.-target e) (.closest "a[href]"))]
                         (let [href (.-href a)]
                           (when (and (plain-click? e)
                                      (not (.-defaultPrevented e))
                                      (not= "_blank" (.-target a))
                                      (not (.hasAttribute a "download"))
                                      (same-origin? href)
                                      (not (str/includes? href "#")))
                             (.preventDefault e)
                             (swap-content! href true))))))
  (.addEventListener js/document "submit"
                     (fn [e]
                       (let [form (.-target e)]
                         (when (and (= "get" (str/lower-case (or (.-method form) "get")))
                                    (same-origin? (.-action form)))
                           (.preventDefault e)
                           (let [action (js/URL. (.-action form))
                                 params (js/URLSearchParams. (js/FormData. form))]
                             (swap-content! (str (.-pathname action) "?" (.toString params)) true))))))
  (.addEventListener js/window "popstate" (fn [_] (swap-content! js/location.href false))))

(defn ^:export init
  "Mount the player: load the state, watch it, take over navigation, and
  handle an episode the URL asks to add."
  []
  (reset! app (load-state))
  (add-watch app ::render (fn [_ _ old new]
                            (when (not= (select-keys old [:player :titles]) (select-keys new [:player :titles]))
                              (save! new))
                            (render!)))
  (intercept-navigation!)
  (render!)
  (add-from-query!))
