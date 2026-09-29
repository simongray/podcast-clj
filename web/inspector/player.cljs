(ns inspector.player
  "The inspector's player, in the browser. The player state of
  dk.simongray.podcast.app.player lives here, kept in localStorage;
  inspector.player.view renders it through Replicant; an
  audio element plays the current episode and reports back through
  `player/step`, whose effects seek or stop the audio, or start the next
  episode.
  The server only supplies episode data, at /episode.edn.

  The player sits in a bar on every page. Clicking a link fetches the next
  page and swaps only the content below the bar, so that playback survives
  moving between pages; the queue page is the detailed view of the same
  state, and syncs it on request through the inspector's own WebDAV folder
  at /dav/, with the library's WebDAV store over fetch.

  Built by `clojure -M:cljs compile player` and served at /js/player.js."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            [dk.simongray.podcast.time :as time]
            [inspector.player.view :as view]
            [replicant.dom :as replicant]))

(def storage-key
  "Where the state lives between visits."
  "dk.simongray.podcast.app.player")

(defonce ^{:doc "The whole state of the page: `:player` (the library's state),
  `:titles` by episode id, the sleep timer under `:sleep`, and what the
  audio element last reported."}
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
  "A state for this browser, under an id of its own, so that its documents
  never meet another device's."
  []
  {:player (-> (player/state (str (random-uuid)))
               (player/identify {:name "browser"} (now)))
   :titles {}})

(defn- read-state
  "The state saved as the string `s`; nil when there is none, or it does not
  read."
  [s]
  (try
    (some-> s reader/read-string)
    (catch :default _ nil)))

(defn- load-state
  []
  (or (try
        (read-state (.getItem js/localStorage storage-key))
        (catch :default _ nil))
      (fresh)))

(defn with-other-tab
  "The page `state` with `other`, the state another tab of this browser
  saved, merged in: the player states joined as two devices' are, and the
  titles of both. The sync cursors stay this tab's."
  [state other]
  (-> state
      (update :player player/merge (:player other))
      (update :titles merge (:titles other))))

(defn with-synced
  "The page `state` with the `result` of a sync step at `at`: its player
  state merged into the one the page has now, which can hold changes from
  during the step, and compacted as the step compacted, since the merge
  brings back the tombstones the step dropped."
  [state {:keys [ctx failed report] :as result} at]
  (-> state
      (update :player #(player/compact (player/merge % (:state result)) at))
      (assoc :sync-ctx ctx :syncing? false :synced {:at at :failed failed :report report})))

(defn- save!
  [state]
  (try
    (.setItem js/localStorage storage-key (pr-str (select-keys state [:player :titles :sync-ctx])))
    (catch :default _ nil)))

(defn- backend
  "The inspector's WebDAV folder on this origin, as a backend."
  []
  (store/backend (webdav/store (str js/location.origin "/dav/") {:name "inspector"})
                 {:opml? true :app "inspector"}))

(defn sync!
  "One step of keeping this browser in step with the other devices through
  the inspector's folder; the result is shown on the queue page."
  []
  (swap! app assoc :syncing? true)
  (-> (sync/sync! (:player @app) [(backend)] (now) {:ctx (:sync-ctx @app)})
      (.then (fn [result] (swap! app with-synced result (now))))
      (.catch (fn [e]
                (swap! app assoc :syncing? false
                       :synced {:at (now) :failed {"inspector" (str e)} :report []})))))

(defn- perform!
  "Carry out the `effects` `player/step` returned: seek the audio element
  to where a skip landed, stop it where the outro of the show starts, or
  start the next episode once it is rendered."
  [effects]
  (doseq [[kind _ arg] effects]
    (case kind
      :seek (when-let [el (audio-element)]
              (set! (.-currentTime el) arg))
      :stop (some-> (audio-element) .pause)
      :play (js/setTimeout #(some-> (audio-element) .play (.catch (fn [_] nil))) 50)
      nil)))

(defn dispatch!
  "Run the player `event` through the library, with the sleep timer of the
  page, and perform its effects. The timer that is left is the timer of
  the page from then on."
  [event]
  (let [{:keys [state effects sleep]} (player/step (:player @app) event (now) (select-keys @app [:sleep]))]
    (swap! app assoc :player state :sleep sleep)
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
    :sleep        (swap! app assoc :sleep (when-let [n (first args)] {:episodes n}))
    :play-next    (operate! player/play-next (first args))
    :dequeue      (operate! player/dequeue (first args))
    :prefer       (when-let [n (parse-long (.. e -target -value))]
                    (operate! player/prefer {(first args) n}))
    :sync         (sync!)
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
  "Render the bar, and the queue page too when `page?`."
  [page?]
  (when-let [bar (js/document.getElementById "player-bar")]
    (replicant/render bar (view/bar @app)))
  (when page?
    (when-let [el (js/document.getElementById "app")]
      (replicant/render el (view/page @app)))))

(defn- same-origin?
  [href]
  (and (seq href)
       (= (.-origin (js/URL. href js/location.href)) js/location.origin)))

(defn- raw-view?
  "Whether `href` leads to a raw view, such as the model as EDN or the
  round-tripped XML, which has no content area to swap in."
  [href]
  (boolean (re-find #"\.(edn|xml)$" (.-pathname (js/URL. href js/location.href)))))

(defn- after-navigation!
  []
  (render! true)
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
                   ;; the queue page's root goes with the content, and
                   ;; Replicant forgets it only when told
                   (do (some-> (js/document.getElementById "app") replicant/unmount)
                       (set! (.-innerHTML target) (.-innerHTML content))
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
                                      (not (raw-view? href))
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

(defn- listen-to-other-tabs!
  "Merge in the state another tab of this browser saves, so that neither
  tab's saves overwrite the other's changes."
  []
  (.addEventListener js/window "storage"
                     (fn [e]
                       (when (= storage-key (.-key e))
                         (when-let [other (read-state (.-newValue e))]
                           (when-not (player/problem (:player other))
                             (swap! app with-other-tab other)))))))

(defn ^:export init
  "Mount the player: load the state, watch it, route the view's events to
  `handle!`, take over navigation and the other tabs' saves, and handle an
  episode the URL asks to add."
  []
  (reset! app (load-state))
  (save! @app)
  (replicant/set-dispatch! (fn [{:replicant/keys [dom-event]} event] (handle! event dom-event)))
  (add-watch app ::render (fn [_ _ old new]
                            (let [kept [:player :titles :sync-ctx]]
                              (when (not= (select-keys old kept) (select-keys new kept))
                                (save! new)))
                            ;; the audio reports its time four times a second,
                            ;; and only the bar shows it
                            (render! (not= (dissoc old :readout :reported) (dissoc new :readout :reported)))))
  (intercept-navigation!)
  (listen-to-other-tabs!)
  (render! true)
  (add-from-query!))
