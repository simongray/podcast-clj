(ns inspector.player
  "The inspector's player, in the browser. A library of
  dk.simongray.podcast.app.library lives here, kept in localStorage, with
  the listener's profile in it; inspector.player.view renders it through
  Replicant; an audio element plays the current episode and reports back
  through `library/step`, whose effects seek or stop the audio, or start
  the next episode.
  The server only supplies episode data, at /episode.edn.

  The player sits in a bar on every page. Clicking a link fetches the next
  page and swaps only the content below the bar, so that playback survives
  moving between pages; the queue page is the detailed view of the same
  state, and syncs it on request through the inspector's own WebDAV folder
  at /dav/, with `library/sync!` over the WebDAV store over fetch.

  Built by `clojure -M:cljs compile player` and served at /js/player.js."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.profile :as profile]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.time :as time]
            [inspector.player.view :as view]
            [replicant.dom :as replicant]))

(def storage-key
  "Where the state lives between visits."
  "dk.simongray.podcast.app.profile")

(defonce ^{:doc "The whole state of the page: `:library` (the library of the
  listener, with the profile under `:profile` and the sleep timer
  under `:sleep`), `:titles` by episode id, and what the audio element
  last reported. The profile holds the data of the episodes too,
  under `:inspector/episodes`, for the other devices."}
  app
  (atom nil))

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
  {:library (library/make (str (random-uuid)) {:name "browser"})
   :titles  {}})

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
  saved, merged in: the profiles joined as two devices' are, and the
  titles of both. The sync cursors stay this tab's."
  [state other]
  (-> state
      (update-in [:library :profile] profile/merge (get-in other [:library :profile]))
      (update :titles merge (:titles other))))

(defn with-synced
  "The page `state` with `library`, the library that `library/sync!` gave:
  it has what the listener did during the step merged in, the tombstones
  compacted, and the step under `:sync`, which the queue page shows."
  [state library]
  (assoc state :library library :syncing? false :synced (select-keys (:sync library) [:at :failed :report])))

(defn- save!
  [state]
  (try
    (.setItem js/localStorage storage-key (pr-str (select-keys state [:library :titles])))
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
  (-> (library/sync! (:library @app) [(backend)])
      (.then (fn [library] (swap! app with-synced library)))
      (.catch (fn [e]
                (swap! app assoc :syncing? false
                       :synced {:at (time/now) :failed {"inspector" (str e)} :report []})))))

(defn- perform!
  "Carry out the `effects` `profile/step` returned: seek the audio element
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
  "Run the player `event` through the library, and perform its effects."
  [event]
  (perform! (get-in (swap! app update :library library/step event) [:library :effects])))

(defn- timed
  "The `library` with its sleep timer set to `n` episodes, or without one
  when `n` is nil."
  [library n]
  (if n
    (assoc library :sleep {:episodes n})
    (dissoc library :sleep)))

(defn operate!
  "Apply `f`, a function of app.profile, to the profile of the library,
  with `args` and the time."
  [f & args]
  (swap! app update :library #(apply library/update-profile % f args)))

(defn- play!
  "Make the episode `id` the current one, at the front of the queue, and
  start the audio once it is rendered."
  [id]
  (dispatch! [:play id])
  (perform! [[:play id]]))

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
    :play         (play! (first args))
    :skip         (let [[id amount] args] (skip! id amount))
    :ended        (dispatch! event)
    :sleep        (swap! app update :library timed (first args))
    :play-next    (operate! profile/play-next (first args))
    :dequeue      (operate! profile/dequeue (first args))
    :prefer       (when-let [n (parse-long (.. e -target -value))]
                    (operate! profile/prefer {(first args) n}))
    :sync         (sync!)
    :audio/loaded (let [[_ position] args
                        el (.-target e)]
                    (when (and (pos? position) (< position (.-duration el)))
                      (set! (.-currentTime el) position)))
    :audio/time   (time-update! (first args) (.-target e))
    nil))

(defn- add!
  "Add the `episode` that /episode.edn served to the player, as `mode`
  says: \"now\" plays it, \"next\" puts it after the current episode, and
  any other mode puts it at the end of the queue.

  The data of the episode goes into the profile too, as an entry of the
  inspector. So a device that gets the queue through a sync gets what it
  needs to play the episode."
  [{:keys [id] :as episode} mode]
  (swap! app assoc-in [:titles id] episode)
  (operate! crdt/write [:inspector/episodes id] (constantly {:value episode}))
  (case mode
    "now"  (play! id)
    "next" (operate! profile/play-next id)
    (operate! profile/enqueue id)))

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
          (.then (fn [edn] (add! (reader/read-string edn) mode)))
          (.catch #(swap! app assoc :error (str %)))))))

(defn- shown
  "The page state as the view reads it: with the profile and the sleep
  timer of the library under `:profile` and `:sleep`."
  [state]
  (let [{:keys [profile sleep]} (:library state)]
    (assoc state :profile profile :sleep sleep)))

(defn- render!
  "Render the bar, and the queue page too when `page?`."
  [page?]
  (when-let [bar (js/document.getElementById "player-bar")]
    (replicant/render bar (view/bar (shown @app))))
  (when page?
    (when-let [el (js/document.getElementById "app")]
      (replicant/render el (view/page (shown @app))))))

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
                           (when-not (profile/problem (get-in other [:library :profile]))
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
                            (let [kept [:library :titles]]
                              (when (not= (select-keys old kept) (select-keys new kept))
                                (save! new)))
                            ;; the audio reports its time four times a second,
                            ;; and only the bar shows it
                            (render! (not= (dissoc old :readout :reported) (dissoc new :readout :reported)))))
  (intercept-navigation!)
  (listen-to-other-tabs!)
  (render! true)
  (add-from-query!))
