(ns dk.simongray.podcast.web.player
  "The inspector's player, in the browser. The player state of
  dk.simongray.podcast.player lives here, kept in localStorage;
  dk.simongray.podcast.web.player.view renders it through Replicant; an
  audio element plays the current episode and reports back through
  `player/step`, whose effects seek the audio or start the next episode.
  The server only supplies episode data, at /episode.edn.

  The player sits in a bar on every page. Clicking a link fetches the next
  page and swaps only the content below the bar, so that playback survives
  moving between pages; the queue page is the detailed view of the same
  state.

  Built by `clojure -M:cljs compile player` and served at /js/player.js."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [dk.simongray.podcast.player :as player]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.web.player.view :as view]
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

(defn- seconds
  "The whole seconds of the audio property `x`, nil when the element does
  not know it yet, as with the duration before the metadata has loaded."
  [x]
  (when (js/isFinite x)
    (js/Math.floor x)))

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
    (dispatch! [:progress id (or (seconds (.-currentTime el)) 0) (seconds (.-duration el))]))
  (dispatch! [:skip id amount]))

(defn- time-update!
  "Show the audio's position, and record it every five seconds."
  [id el]
  (let [t (.-currentTime el)
        d (seconds (.-duration el))]
    (swap! app assoc :readout (str "at " (time/format-clock t) (when d (str " of " (time/format-clock d)))))
    (when (>= (js/Math.abs (- t (or (:reported @app) -10))) 5)
      (swap! app assoc :reported t)
      (dispatch! [:progress id (or (seconds t) 0) d]))))

(defn handle!
  "Perform the view's `event`, given the DOM event `e` that raised it when
  there is one: the skips and the queue operations go to the library, the
  audio element's reports are read off it."
  [[kind & args :as event] e]
  (case kind
    :skip         (let [[id amount] args] (skip! id amount))
    :ended        (dispatch! event)
    :play-next    (operate! player/play-next (first args))
    :dequeue      (operate! player/dequeue (first args))
    :prefer       (when-let [n (parse-long (.. e -target -value))]
                    (operate! player/prefer {(first args) n}))
    :audio/loaded (let [[_ position] args
                        el (.-target e)]
                    (when (and (pos? position) (< position (.-duration el)))
                      (set! (.-currentTime el) position)))
    :audio/time   (time-update! (first args) (.-target e))
    nil))

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

(defn- render!
  []
  (when-let [bar (js/document.getElementById "player-bar")]
    (replicant/render bar (view/bar @app)))
  (when-let [el (js/document.getElementById "app")]
    (replicant/render el (view/page @app))))

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
  "Mount the player: load the state, watch it, route the view's events to
  `handle!`, take over navigation, and handle an episode the URL asks to
  add."
  []
  (reset! app (load-state))
  (replicant/set-dispatch! (fn [{:replicant/keys [dom-event]} event] (handle! event dom-event)))
  (add-watch app ::render (fn [_ _ old new]
                            (when (not= (select-keys old [:player :titles]) (select-keys new [:player :titles]))
                              (save! new))
                            (render!)))
  (intercept-navigation!)
  (render!)
  (add-from-query!))
