(ns inspector.player
  "The inspector's player in the browser: a library with the listener's
  profile, kept in localStorage, and the audio of the current episode.

  The server only supplies episode data, at /episode.edn. The player sits
  in a bar on every page. Clicking a link fetches the next page and swaps
  only the content below the bar, so that playback survives moving between
  pages.

  The player syncs through the inspector's WebDAV folder at /dav/, one tab
  at a time: when the page opens, when it's hidden, when it's due while
  nothing plays, and on request. Where the browser can pick a folder, it
  also syncs through one that the listener picks, whose handle is stored
  in IndexedDB for the next visit.

  The media element is play-nice's: it loads the episode at the position
  to resume at and the show's rate, reports its state, applies the
  effects of the library, and plays muted or at the next click where the
  browser refuses its sound. The episode that plays is on the lock screen,
  through the Media Session, and its buttons and those of the headphones
  go through the library as app.media-session has them. A tab that starts
  playing pauses the inspector's other tabs.

  Once the server has read the episode's files, at /episode-files.edn,
  its chapters go on the lock screen and into a track that tells the bar
  which one plays, and its transcript into captions. A link to an item
  page with a t, e.g. from a chapter's start, plays the episode from
  there.

  The feeds of the episodes are refreshed in a web worker when they're
  due, as inspector.worker describes."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.like-minded.store.webdav :as webdav]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.play-nice.controls :as controls]
            [dk.simongray.play-nice.display.fullscreen :as fullscreen]
            [dk.simongray.play-nice.display.picture-in-picture :as pip]
            [dk.simongray.play-nice.display.remote-playback :as remote]
            [dk.simongray.play-nice.display.wake-lock :as wake-lock]
            [dk.simongray.play-nice.media :as media]
            [dk.simongray.play-nice.media.capabilities :as capabilities]
            [dk.simongray.play-nice.media.captions :as captions]
            [dk.simongray.play-nice.media.fragment :as fragment]
            [dk.simongray.play-nice.media.tracks :as tracks]
            [dk.simongray.play-nice.page.autoplay :as autoplay]
            [dk.simongray.play-nice.page.tabs :as tabs]
            [dk.simongray.play-nice.session :as session]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.media-session :as media-session]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.time :as time]
            [inspector.player.view :as view]
            [inspector.shared :as shared]
            [replicant.dom :as replicant]))

(def storage-key
  "Where the state lives between visits."
  "dk.simongray.podcast.profile")

(def device-key
  "Where the id of this browser lives, apart from the state, so that a state
  that fails to save makes no new device at the next visit."
  "dk.simongray.podcast.device")

(defonce ^{:doc "The whole state of the page: `:library` (the library of the
  listener, with the profile under `:profile` and the sleep timer
  under `:sleep`), `:titles` by episode id, the state of the media
  element under `:media`, as media/watch! reports it, and the browser's
  `:answer` to playing it. The profile names the feed of each episode
  added here, as a remote item, so that the other devices can fetch its
  data."}
  app
  (atom nil))

(defn- audio-element
  []
  (js/document.getElementById "player"))

(defn- seconds
  "The whole seconds of the audio property `x`, nil when the element does
  not know it yet, as with the duration before the metadata has loaded."
  [x]
  (when (js/Number.isFinite x)
    (js/Math.floor x)))

(defn- stored
  "The string in localStorage under `k`, or nil."
  [k]
  (try (.getItem js/localStorage k) (catch :default _ nil)))

(defn- store!
  [k s]
  (try (.setItem js/localStorage k s) (catch :default _ nil)))

(defn- browser-name
  "A name for this browser that the listener can tell their devices apart
  by, e.g. \"Firefox on macOS\"."
  []
  (let [agent (str (some-> js/globalThis .-navigator .-userAgent))]
    (str (condp re-find agent
           #"Edg/"     "Edge"
           #"Firefox/" "Firefox"
           #"Chrome/"  "Chrome"
           #"Safari/"  "Safari"
           "A browser")
         (condp re-find agent
           #"iPhone|iPad" " on iOS"
           #"Android"     " on Android"
           #"Mac OS X"    " on macOS"
           #"Windows"     " on Windows"
           #"Linux"       " on Linux"
           ""))))

(defn- read-state
  "The state saved as the string `s`, or nil when there is none, when it
  can't be read, or when its profile has a problem."
  [s]
  (when-let [state (try (some-> s reader/read-string) (catch :default _ nil))]
    (when-not (profile/problem (get-in state [:library :profile]))
      state)))

(defn- load-state
  "The state of the last visit, or a fresh one under the id of this
  browser, which is kept from now on."
  []
  (let [state (read-state (stored storage-key))
        id    (or (stored device-key)
                  (get-in state [:library :device])
                  (str (random-uuid)))]
    (store! device-key id)
    (or state {:library (library/make id) :titles {}})))

(defn with-other-tab
  "The page `state` with `other`, the state another tab of this browser
  saved, merged in: the libraries with their sync cursors, and the titles
  of both."
  [state other]
  (-> state
      (update :library library/merge (:library other))
      (update :titles merge (:titles other))))

(defn with-synced
  "The page `state` with `library`, the result of a sync, merged into its
  own, and the time and report of the sync under :synced for the queue
  page."
  [state library]
  (-> state
      (update :library library/merge library)
      (assoc :syncing? false
             :synced   (select-keys (:sync library) [:at :report]))))

(defn- save!
  [state]
  (store! storage-key (pr-str (select-keys state [:library :titles]))))

(defn- backend
  "The inspector's WebDAV folder on this origin, as a backend."
  []
  (let [dav (webdav/store (str js/location.origin "/dav/") {:name "inspector"})]
    (library/backend dav {:opml? true :app "inspector"})))

(defn- backends
  "The backends of a sync: the inspector's folder, and the sync folder of
  this browser while the browser lets it be used."
  []
  (let [{:keys [handle permission]} (:folder @app)]
    (cond-> [(backend)]
      (= :granted permission)
      (conj (library/backend (folder/store handle) {:name  "sync folder"
                                               :opml? true
                                               :app   "inspector"})))))

(defn- handles!
  "A promise of the result of `f` on the inspector's store of handles in
  IndexedDB, in a transaction of `mode`. A handle can't go in
  localStorage, but IndexedDB keeps it."
  [mode f]
  (let [opened (fn [resolve reject]
                 (let [request (.open js/indexedDB "inspector" 1)]
                   (set! (.-onupgradeneeded request)
                         #(.createObjectStore (.-result request) "handles"))
                   (set! (.-onsuccess request) #(resolve (.-result request)))
                   (set! (.-onerror request) #(reject (.-error request)))))
        asked  (fn [db]
                 (js/Promise.
                  (fn [resolve reject]
                    (let [handles (.objectStore (.transaction db "handles" mode)
                                                "handles")
                          request (f handles)]
                      (set! (.-onsuccess request) #(resolve (.-result request)))
                      (set! (.-onerror request)
                            #(reject (.-error request)))))))]
    (-> (js/Promise. opened)
        (.then asked))))

(defn- use-folder!
  "Sync through the folder of the handle `h` from now on, as far as the
  browser's `permission` for it allows."
  [h permission]
  (swap! app assoc :folder {:handle h :name (.-name h) :permission permission}))

(defn- check-folder!
  "Ask the browser again for its permission for the sync folder, which can
  lapse, so that the page offers to renew it."
  []
  (when-let [h (get-in @app [:folder :handle])]
    (.then (folder/query-permission! h) #(use-folder! h %))))

(defn- one-at-a-time
  "Run `f`, which gives a promise, while no other tab of this browser runs
  it, where the browser has Web Locks."
  [f]
  (if (exists? js/navigator.locks)
    (.request js/navigator.locks "inspector-sync" (fn [_] (f)))
    (f)))

(defn- episode-query
  "The address at `path` of the episode `guid` of the feed at `url`, e.g.
  of /episode.edn."
  [path url guid]
  (str path "?url=" (js/encodeURIComponent url)
       "&guid=" (js/encodeURIComponent guid)))

(defn- feed-url-of
  "The URL of the feed of the episode `id` that `profile` names: that of
  its remote item, or of the subscription to its show, or nil."
  [profile [feed-guid :as id]]
  (or (:feed-url (get (profile/remote-items profile) id))
      (:url (get (profile/subscriptions profile) feed-guid))))

(defonce ^{:doc "The episodes whose data this page asked /episode.edn for."}
  asked
  (atom #{}))

(defn- fetch-unknown!
  "Fetch the data of the queued episodes that this browser has none of from
  /episode.edn, by the URL of the feed that the profile names, once for
  each episode."
  []
  (let [{:keys [library titles]} @app
        profile (:profile library)]
    (doseq [[_ item-guid :as id] (profile.queue/queue profile)
            :let  [url (feed-url-of profile id)]
            :when (and url
                       (not (contains? titles id))
                       (not (contains? @asked id)))]
      (swap! asked conj id)
      (-> (js/fetch (episode-query "/episode.edn" url item-guid))
          (.then #(when (.-ok %) (.text %)))
          (.then #(when %
                    (swap! app assoc-in [:titles id] (reader/read-string %))))
          (.catch (fn [_] nil))))))

(defn sync!
  "Sync this browser with the other devices through `backends`, by default
  the inspector's folder and the sync folder of this browser, and show the
  result on the queue page.

  The tabs of this browser share its device id, so they sync one at a
  time, and each step starts from what the last one saved."
  ([]
   (sync! (backends)))
  ([backends]
   (swap! app assoc :syncing? true)
   (-> (one-at-a-time
        (fn []
          (when-let [other (read-state (stored storage-key))]
            (swap! app with-other-tab other))
          (.then (library/sync! (:library @app) backends)
                 #(swap! app with-synced %))))
       (.catch (fn [e]
                 (let [message "The sync through the inspector failed."
                       failed  {:id      :library/backend-failed
                                :backend "inspector"
                                :reason  (str e)
                                :message message}]
                   (swap! app assoc
                          :syncing? false
                          :synced   {:at (time/now) :report [failed]}))))
       (.then (fn [_] (check-folder!)))
       (.then (fn [_] (fetch-unknown!))))))

(defn- playing?
  []
  (some-> (audio-element) .-paused not))

(defn- sync-when-due!
  "Sync when it's due, unless an episode plays, since its position changes
  every few seconds then."
  []
  (when (and (not (:syncing? @app))
             (not (playing?))
             (<= (inst-ms (library/next-sync (:library @app)))
                 (js/Date.now)))
    (sync!)))

(defn- restore-folder!
  "Take up the sync folder of the last visit, if there was one."
  []
  (-> (handles! "readonly" #(.get % "sync-folder"))
      (.then (fn [h]
               (when h
                 (.then (folder/query-permission! h) #(use-folder! h %)))))
      (.catch (fn [_] nil))))

(defn- pick-folder!
  "Ask the listener for a sync folder, keep it for the next visit, and sync
  through it."
  []
  (-> (js/showDirectoryPicker #js {:id "podcast-sync" :mode "readwrite"})
      (.then (fn [h]
               (.then (handles! "readwrite" #(.put % h "sync-folder"))
                      (fn [_]
                        (use-folder! h :granted)
                        (sync!)))))
      (.catch (fn [e]
                (when-not (= "AbortError" (.-name e))
                  (swap! app assoc :error (str e)))))))

(defn- allow-folder!
  "Ask the listener to let the page use the sync folder again, and sync
  through it if they do. The browser only asks during a click."
  []
  (let [h (get-in @app [:folder :handle])]
    (.then (folder/request-permission! h)
           (fn [permission]
             (use-folder! h permission)
             (when (= :granted permission)
               (sync!))))))

(defn- forget-folder!
  []
  (swap! app dissoc :folder)
  (handles! "readwrite" #(.delete % "sync-folder")))

(defn- save-document!
  "Download this browser's sync document, for the listener to put into a
  sync folder."
  []
  (let [{:keys [name body]} (library/document (:library @app)
                                              {:app "inspector"})
        blob (js/Blob. #js [body] #js {:type "application/edn"})
        url  (js/URL.createObjectURL blob)
        a    (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) name)
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 60000)))

(defn- load-folder!
  "Sync with the documents among the files of the `input` that the listener
  loaded a folder with, and empty it, so the same folder loads again."
  [input]
  (let [files (js/Array.from (.-files input))]
    (set! (.-value input) "")
    (sync! [(library/backend (folder/store files) {:name "loaded folder"})])))

(defn- start!
  "Play the media element, muted where the browser refuses its sound, and
  keep the browser's answer. Where it refuses even that, try once more at
  the listener's next click or key press, when `retry?`."
  [retry?]
  (when-let [el (audio-element)]
    (.then (media/play! el {:muted-fallback true})
           (fn [answer]
             (swap! app assoc :answer answer)
             (when (and retry? (= :not-allowed (:refused answer)))
               (autoplay/on-activation! #(start! false)))))))

(defn- media-effects
  "The `effects` of a player event as those of media/apply!: a seek to
  where a skip landed, and a pause where the outro of the show starts."
  [effects]
  (keep (fn [[kind _ arg]]
          (case kind
            :seek [:seek arg]
            :stop [:pause]
            nil))
        effects))

(defn- perform!
  "Carry out the `effects` of a player event on the media element, and
  start the next episode once it is rendered."
  [effects]
  (some-> (audio-element) (media/apply! (media-effects effects)))
  (when (some (comp #{:play} first) effects)
    (js/setTimeout #(start! true) 50)))

(defn dispatch!
  "Run the player `event` through the library, and perform its effects."
  [event]
  (let [state (swap! app update :library library/step event)]
    (perform! (get-in state [:library :effects]))))

(defonce ^{:doc "The function that hands the Media Session back to the
  browser, while it follows the audio element of an episode."}
  detach-session
  (atom nil))

(defonce ^{:doc "The handlers of the lock screen's buttons for the episode
  that plays, by action, which the bar's keys and buttons press too."}
  handlers
  (atom {}))

(defn- on-lock-screen!
  "Put the episode `id`, which the audio element `el` plays, on the lock
  screen, with buttons that go through the library."
  [id el]
  ;; the library of the player holds no feeds, so the metadata is what
  ;; /episode.edn served rather than what library/playback gives
  (let [{:keys [title feed-title artwork]} (get-in @app [:titles id])
        press (fn [details]
                (run! dispatch! (media-session/events (:library @app) id
                                                      (.-currentTime el)
                                                      details)))]
    (reset! detach-session (session/attach! el))
    (session/metadata! (cond-> {:title  (or title "")
                                :artist (or feed-title "")
                                :album  (or feed-title "")}
                         artwork (assoc :artwork [{:src artwork}])))
    (reset! handlers (merge (session/element-actions el)
                            (zipmap (media-session/actions (:library @app) id)
                                    (repeat press))))
    (session/actions! @handlers)))

(defn- off-lock-screen!
  "Take the episode off the lock screen, once nothing is queued."
  []
  (when-let [detach! @detach-session]
    (reset! detach-session nil)
    (reset! handlers {})
    (detach!)
    (session/metadata! nil)))

(defn- press!
  "Press the handler of the action of `details`, as a button of the lock
  screen would, e.g. {:action :seekto :seek-time 754}."
  [{:keys [action] :as details}]
  (when-let [f (get @handlers action)]
    (f details)))

(defn- seek-at!
  "Seek to where the click `e` landed on the seek bar."
  [^js e]
  (let [rect     (.getBoundingClientRect (.-currentTarget e))
        duration (get-in @app [:media :duration])]
    (when (and (js/Number.isFinite duration) (pos? (.-width rect)))
      (press! {:action    :seekto
               :seek-time (* duration (/ (- (.-clientX e) (.-left rect))
                                         (.-width rect)))}))))
(defn- timed
  "The `library` with its sleep timer set to `n` episodes, or without one
  when `n` is nil."
  [library n]
  (if n
    (assoc library :sleep {:episodes n})
    (dissoc library :sleep)))

(defn operate!
  "Apply `f`, a function of profile, to the profile of the library,
  with `args` and the time."
  [f & args]
  (swap! app update :library #(apply library/update-profile % f args)))

(defn- keep-rate
  "The `profile` with the playback rate `x` kept for the show `feed-guid`
  at `at`, or as the listener's preference where they don't subscribe to
  it."
  [profile feed-guid x at]
  (if (profile/subscribed? profile feed-guid)
    (profile/configure profile feed-guid {:playback-rate x} at)
    (profile/prefer profile {:playback-rate x} at)))

(defn- change-rate!
  "Play at the rate that the change `e` of the select chose, and keep it
  for the show `feed-guid`."
  [feed-guid ^js e]
  (when-let [el (audio-element)]
    (let [{:keys [playback-rate refused]}
          (media/rate! el (parse-double (.. e -target -value)))]
      (when-not refused
        (operate! keep-rate feed-guid playback-rate)))))

(defn- play!
  "Make the episode `id` the current one, at the front of the queue, and
  start the audio once it is rendered."
  [id]
  (dispatch! [:play id])
  (perform! [[:play id]]))

(defn- progress!
  "Tell the library where the media element of the episode `id` is, by
  its `state`."
  [id {:keys [current-time duration] :as state}]
  (swap! app assoc :reported current-time)
  (dispatch! [:progress id (or (seconds current-time) 0) (seconds duration)]))

(defn- skip!
  "Skip the episode `id` by `amount`, from where the media element is,
  which the library is told first."
  [id amount]
  (some->> (audio-element) media/state (progress! id))
  (dispatch! [:skip id amount]))

(defn- reported!
  "Keep the `state` of the media element of the episode `id`, which
  media/watch! reports, and tell the library where it is: at a pause,
  after a seek, and every five seconds of playing. It syncs at a pause,
  since nothing plays then."
  [id {:keys [paused seeking current-time] :as state}]
  (let [{old :media :keys [reported]} @app]
    (swap! app assoc :media state)
    (cond
      (and paused (false? (:paused old)))
      (do (progress! id state)
          (sync!))

      (or (and (:seeking old) (not seeking))
          (and (not paused)
               (>= (js/Math.abs (- current-time (or reported -10))) 5)))
      (progress! id state))))

(defonce ^{:doc "The function that lets the screen go dark again, while
  the transcript of an audio episode is shown."}
  release-screen
  (atom nil))

(defn- release-screen!
  []
  (when-let [release! @release-screen]
    (reset! release-screen nil)
    (release!)))

(defn- video?
  [^js el]
  (= "VIDEO" (.-tagName el)))

(defn- show-transcript!
  "Show the transcript of the episode that plays when `on?`, and hide it
  otherwise: as the captions of a video, or for audio as a line of the
  bar, which keeps the screen on while it plays."
  [on?]
  (swap! app assoc :transcript? on?)
  (release-screen!)
  (when-let [el (audio-element)]
    (if (video? el)
      (let [language (some #(when (= :captions (:kind %)) (:language %))
                           (tracks/text-tracks el))]
        (tracks/show! el {:kinds     [:captions]
                          :languages (if (and on? language) [language] [])}))
      (when on?
        (reset! release-screen (wake-lock/hold-while-playing! el))))))

(defonce ^{:doc "The function that takes the listener's style off the
  captions of the video that plays."}
  unstyle-captions
  (atom nil))

(defn- style-captions!
  "Style the captions of the video that plays in the style of `name`, one
  of view/caption-styles, or leave them in the browser's own."
  [name]
  (swap! app assoc :caption-style name)
  (when-let [unstyle! @unstyle-captions]
    (reset! unstyle-captions nil)
    (unstyle!))
  (let [style (get view/caption-styles name)
        el    (audio-element)]
    (when (and style el (video? el))
      (reset! unstyle-captions (captions/style! el style)))))

(defn- duration-of
  "The seconds of the episode `id`: those of its media element `el` once it
  knows them, or else those that its feed declares."
  [id ^js el]
  (let [d (.-duration el)]
    (if (js/Number.isFinite d)
      d
      (get-in @app [:titles id :duration]))))

(defn- add-files!
  "Add what the files of the episode `id` hold to its media element `el`,
  as /episode-files.edn serves it: the :metadata with the chapters on the
  lock screen, the chapters as a track, and the :transcript as captions,
  shown or hidden as the listener left them."
  [id el {:keys [metadata transcript] :as files}]
  (when (identical? el (audio-element))
    (session/metadata! metadata)
    (when-let [chapters (seq (:chapter-info metadata))]
      (tracks/add! el {:kind  :chapters
                       :label "Chapters"
                       :cues  (tracks/chapter-cues chapters
                                                   (duration-of id el))}))
    (when transcript
      (tracks/add! el (assoc transcript :kind :captions :label "Transcript"))
      (show-transcript! (:transcript? @app))
      (style-captions! (:caption-style @app)))))

(defn- load-files!
  "Fetch what the files of the episode `id` hold from /episode-files.edn,
  and add it to its media element `el`."
  [id el]
  (when-let [{:keys [url guid]} (get-in @app [:titles id])]
    (-> (js/fetch (episode-query "/episode-files.edn" url guid))
        (.then #(when (.-ok %) (.text %)))
        (.then #(some->> % reader/read-string (add-files! id el)))
        (.catch #(js/console.warn "The episode's files didn't load:" %)))))

(defn- device!
  "Ask the browser for the device that the view's `event` names, for the
  media element: [:pip true] enters Picture-in-Picture and [:pip false]
  leaves it, [:fullscreen] puts a video fullscreen, and [:cast] shows the
  picker of remote devices. A refusal is kept for the bar to show."
  [[kind on? :as event]]
  (when-let [el (audio-element)]
    (let [[what answer]
          (case kind
            :pip        ["Picture-in-Picture"
                         (if on? (pip/enter! el) (pip/exit! el))]
            :fullscreen ["Fullscreen"
                         (fullscreen/enter! el {:video       el
                                                :orientation :landscape})]
            :cast       ["Casting" (remote/prompt! el)])]
      (.then answer #(swap! app assoc :device-answer
                            (when (:refused %) (assoc % :what what)))))))

(def followed
  "The keys of the page state that belong to the media element that plays,
  which a new one starts without."
  [:answer :media :reported :tracks :pip :fullscreen :remote :device-answer])

(defn- follow!
  "Follow the media element `el` of the episode `id`, and return a function
  that stops it: keep its state, its tracks and those of its devices,
  press its handlers at the bar's keys, and let it pause when another tab
  plays."
  [id el]
  (let [bar      (js/document.getElementById "player-bar")
        seek-bar (some-> bar (.querySelector ".seek-bar"))
        keep!    (fn [k] #(swap! app assoc k %))]
    (juxt (media/watch! el (partial reported! id)
                        {:events (conj media/events :timeupdate)})
          (tracks/watch! el (keep! :tracks))
          (if (pip/available? el)
            (pip/watch! el (keep! :pip))
            (constantly nil))
          (fullscreen/watch! el (keep! :fullscreen) {:video el})
          (remote/watch! el (keep! :remote))
          ;; a key on the seek bar is its own, before the bar's
          (controls/listen! seek-bar el @handlers
                            {:keymap controls/seek-bar-keys})
          (controls/listen! bar el @handlers)
          (tabs/attach! el "podcast-inspector"))))

(defonce ^{:doc "The functions that stop following the media element of
  each episode id, while the bar shows it."}
  detach-element
  (atom {}))

(defn- mount!
  "Load the `source` of the episode `id` into the media element `el` that
  the bar has rendered, put it on the lock screen, and follow it. Its
  chapters and transcript come later."
  [id source el]
  (media/source! el source)
  (swap! app #(-> (apply dissoc % followed)
                  (update :start dissoc id)))
  (on-lock-screen! id el)
  (load-files! id el)
  (swap! detach-element assoc id (follow! id el)))

(defn- unmount!
  "Stop following the media element of the episode `id`, which the bar no
  longer shows."
  [id]
  (release-screen!)
  (when-let [detach! (get @detach-element id)]
    (swap! detach-element dissoc id)
    (detach!)))

(defn handle!
  "Perform the view's `event`, given the DOM event `e` that raised it or
  the `node` that a life-cycle hook raised it on. The skips and the queue
  operations go to the library, the bar's controls press the lock
  screen's handlers or set the media element, and its mount loads it."
  [[kind & args :as event] e node]
  (case kind
    :play          (play! (first args))
    :skip          (let [[id amount] args] (skip! id amount))
    :ended         (dispatch! event)
    :sleep         (swap! app update :library timed (first args))
    :play-next     (operate! profile.queue/play-next (first args))
    :dequeue       (operate! profile.queue/dequeue (first args))
    :prefer        (when-let [n (parse-long (.. e -target -value))]
                     (operate! profile/prefer {(first args) n}))
    :sync          (sync!)
    :folder/pick   (pick-folder!)
    :folder/allow  (allow-folder!)
    :folder/forget (forget-folder!)
    :device/retire (when (js/confirm (str "Retire this device? Its document "
                                          "goes, and it can't sync under its "
                                          "id again."))
                     (operate! crdt/retire (first args)))
    :document/save (save-document!)
    :document/load (load-folder! (.-target e))
    ;; after the render, since the state that mount! keeps would render
    ;; again inside it
    :media/mounted   (let [[id source] args]
                       (js/queueMicrotask #(mount! id source node)))
    :media/unmounted (unmount! (first args))
    :press           (press! (first args))
    :seek-at         (seek-at! e)
    :rate            (change-rate! (first args) e)
    :volume          (some-> (audio-element)
                             (media/volume! (parse-double (.. e -target -value))
                                            {:curve :cubic}))
    :muted           (some-> (audio-element) (media/muted! (first args)))
    :transcript      (show-transcript! (first args))
    :caption-style   (style-captions! (.. e -target -value))
    (:pip :fullscreen :cast) (device! event)
    nil))

(defn- add!
  "Add the `episode` that /episode.edn served to the player, as `mode`
  says: \"now\" plays it, \"next\" puts it after the current episode, and
  any other mode puts it at the end of the queue.

  The episode goes into the profile as a remote item with the URL of its
  feed. So a device that gets the queue through a sync can fetch what it
  needs to play the episode."
  [{:keys [id url] :as episode} mode]
  (swap! app assoc-in [:titles id] episode)
  (operate! profile/add-remote-item id {:feed-url url})
  (case mode
    "now"  (play! id)
    "next" (operate! profile.queue/play-next id)
    (operate! profile.queue/enqueue id)))

(defn- fetch-episode!
  "A promise of what /episode.edn serves for the episode `guid` of the
  feed at `url`, which rejects where it answers with an error."
  [url guid]
  (-> (js/fetch (episode-query "/episode.edn" url guid))
      (.then (fn [response]
               (if (.-ok response)
                 (.text response)
                 (throw (js/Error. (str "episode.edn answered "
                                        (.-status response)))))))
      (.then reader/read-string)))

(defn- add-from-query!
  []
  (let [params (js/URLSearchParams. js/location.search)
        url    (.get params "add")
        guid   (.get params "guid")
        mode   (.get params "mode")]
    (when (and url guid)
      (js/history.replaceState nil "" "/queue")
      (-> (fetch-episode! url guid)
          (.then #(add! % mode))
          (.catch #(swap! app assoc :error (str %)))))))

(defn- play-at!
  "Play the `episode` that /episode.edn served from `t` seconds: at once
  where it's the one that plays, and otherwise as one added now."
  [{:keys [id] :as episode} t]
  (if (and (= id (profile.queue/current (get-in @app [:library :profile])))
           (audio-element))
    (do (press! {:action :seekto :seek-time t})
        (start! true))
    (do (swap! app assoc-in [:start id] t)
        (add! episode "now"))))

(defn- play-from-link!
  "Play the episode of an item page from where the t of its address points,
  e.g. /item?url=…&guid=…&t=95 from the start of a chapter, and take the t
  off the address, so that going back doesn't play it again."
  []
  (when-let [t (and (= "/item" js/location.pathname)
                    (fragment/link-time js/location.href))]
    (let [params (js/URLSearchParams. js/location.search)]
      (.delete params "t")
      (js/history.replaceState nil "" (str "/item?" params))
      (-> (fetch-episode! (.get params "url") (.get params "guid"))
          (.then #(play-at! % t))
          (.catch #(swap! app assoc :error (str %)))))))

(defn- shown
  "The page `state` as the view reads it: with the profile and the sleep
  timer of the library under :profile and :sleep."
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
  (let [path (.-pathname (js/URL. href js/location.href))]
    (boolean (re-find #"\.(edn|xml)$" path))))

;; The server answers 202 while it makes the library that it recommends
;; from, and the page asks again a few seconds later, unless it's gone
(defn- fill-recommended!
  "Fetch the episodes like the one of an item page into its element of
  them, from the address that the element names, once the page is shown."
  []
  (when-let [el (js/document.getElementById "recommended")]
    (let [shown? #(identical? el (js/document.getElementById "recommended"))
          fill!  #(when (shown?) (set! (.-innerHTML el) %))]
      (-> (js/fetch (.getAttribute el "data-src"))
          (.then (fn [response]
                   (if (.-ok response)
                     (.then (.text response)
                            (fn [html]
                              (fill! html)
                              (when (= 202 (.-status response))
                                (js/setTimeout #(when (shown?)
                                                  (fill-recommended!))
                                               3000))))
                     (fill! (str "<p class=\"muted\">Could not recommend "
                                 "episodes: HTTP " (.-status response)
                                 "</p>")))))
          (.catch (fn [_]
                    (fill! (str "<p class=\"muted\">Could not reach the "
                                "inspector to recommend episodes.</p>"))))))))

(defn- fill-playable!
  "Ask the browser whether it plays each file of an item page, by the
  configuration in the data-configuration of its element, and say so in
  the element."
  []
  (let [els           (array-seq (js/document.querySelectorAll
                                  "[data-configuration]"))
        configuration #(reader/read-string (.getAttribute % "data-configuration"))]
    (when (seq els)
      (.then (capabilities/decoding-infos! (map configuration els))
             (fn [infos]
               (doseq [^js el els]
                 (set! (.-textContent el) (view/playable
                                           (infos (configuration el))))
                 (.remove (.-classList el) "muted")))))))

(defn- after-navigation!
  []
  (render! true)
  (add-from-query!)
  (play-from-link!)
  (fill-recommended!)
  (fill-playable!))

(defn- swap-content!
  "Fetch the page at `url` and put its content in place of the current
  one, with a new entry in the history when `push?`.

  The player bar stays untouched, and what the new page asks for is
  handled. The history gets the address that a redirect led to. A page
  without a content area, such as the raw EDN and XML views, is
  navigated to for real."
  [url push?]
  (-> (js/fetch url)
      (.then (fn [response]
               (.then (.text response) #(vector (.-url response) %))))
      (.then (fn [[final html]]
               (let [doc     (.parseFromString (js/DOMParser.) html "text/html")
                     content (.getElementById doc "content")
                     target  (js/document.getElementById "content")]
                 (if (and content target)
                   ;; the queue page's root goes with the content, and
                   ;; Replicant forgets it only when told
                   (do (some-> (js/document.getElementById "app")
                               replicant/unmount)
                       (set! (.-innerHTML target) (.-innerHTML content))
                       (set! js/document.title (.-title doc))
                       (when push?
                         (let [address (or (not-empty final) url)]
                           (js/history.pushState nil "" address)))
                       (js/window.scrollTo 0 0)
                       (after-navigation!))
                   (set! js/location.href url)))))
      (.catch (fn [_] (set! js/location.href url)))))

(defn- plain-click?
  [e]
  (and (zero? (.-button e))
       (not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e)))))

(defn- follow-link!
  "Swap in the page that the click `e` on a link of this origin leads to,
  in place of the browser's navigation."
  [e]
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
        (swap-content! href true)))))

(defn- submit-form!
  "Swap in the page that the submission `e` of a GET form of this origin
  asks for, in place of the browser's navigation."
  [e]
  (let [form (.-target e)]
    (when (and (= "get" (str/lower-case (or (.-method form) "get")))
               (same-origin? (.-action form)))
      (.preventDefault e)
      (let [action (js/URL. (.-action form))
            params (js/URLSearchParams. (js/FormData. form))]
        (swap-content! (str (.-pathname action) "?" (.toString params))
                       true)))))

(defn- intercept-navigation!
  "Turn same-origin link clicks and GET form submissions into content swaps,
  and the back and forward buttons too."
  []
  (.addEventListener js/document "click" follow-link!)
  (.addEventListener js/document "submit" submit-form!)
  (.addEventListener js/window "popstate"
                     (fn [_] (swap-content! js/location.href false))))

(defn- listen-to-other-tabs!
  "Merge in the state another tab of this browser saves, so that neither
  tab's saves overwrite the other's changes."
  []
  (.addEventListener js/window "storage"
                     (fn [e]
                       (when (= storage-key (.-key e))
                         (when-let [other (read-state (.-newValue e))]
                           (swap! app with-other-tab other))))))

(defn- sync-on-its-own!
  "Sync now, when the page is hidden, and when it's due."
  []
  (sync!)
  (.addEventListener js/document "visibilitychange"
                     #(when (= "hidden" (.-visibilityState js/document))
                        (sync!)))
  (js/window.setInterval sync-when-due! 30000))

(defn- refreshed!
  "Restore the library that the worker saved, and merge it into the page's
  with the result of the refresh, by the event `e` of the worker's answer.
  The time of the answer and any error go under :refreshed."
  [^js e]
  (let [{:keys [error refresh]} (js->clj (.-data e) :keywordize-keys true)
        store  (indexeddb/store shared/store-name)
        device (get-in @app [:library :device])
        done   #(assoc % :refreshing? false :refreshed %2)]
    (if error
      (swap! app done {:at (time/now) :error error})
      (.then (shared/locked #(library/restore! (library/make device) store))
             (fn [restored]
               (swap! app #(-> %
                               (update :library library/merge restored)
                               (assoc-in [:library :refresh]
                                         (reader/read-string refresh))
                               (done {:at (time/now)}))))))))

(defn- worker-failed!
  "Note the error event `e` of the worker, which failed to load or threw,
  so that the next refresh that's due asks again."
  [^js e]
  (swap! app assoc
         :refreshing? false
         :refreshed   {:at (time/now) :error (or (.-message e) "no message")}))

(defonce ^{:doc "The web worker that refreshes the feeds of the library,
  made on first use, or nil where the browser has none."}
  refresher
  (delay (when (exists? js/Worker)
           (doto (js/Worker. "/js/worker.js")
             (.addEventListener "message" refreshed!)
             (.addEventListener "error" worker-failed!)))))

(defn- refresh-when-due!
  "Ask the worker to refresh the feeds, once the library has one due:
  save the library to the store that the worker shares first."
  []
  (when-let [^js w (when (and (not (:refreshing? @app))
                              (some-> (library/next-refresh (:library @app))
                                      inst-ms
                                      (<= (js/Date.now))))
                     @refresher)]
    (swap! app assoc :refreshing? true)
    (let [store  (indexeddb/store shared/store-name)
          device (get-in @app [:library :device])]
      (.then (shared/locked #(library/save! (:library @app) store))
             (fn [_] (.postMessage w #js {:device device}))))))

(defn- refresh-on-its-own!
  "Refresh the feeds in the worker now, and whenever one is due."
  []
  (refresh-when-due!)
  (js/window.setInterval refresh-when-due! 30000))

(defn ^:export init
  "Mount the player:

  - load the state, and name this browser for the other devices
  - watch the state, and handle the view's events
  - take over navigation and the other tabs' saves
  - take up the sync folder of the last visit
  - add an episode that the URL asks for, or play one from its t
  - fetch the episodes like the one of an item page, and ask the browser
    whether it plays the page's files
  - sync and refresh on its own"
  []
  (reset! app (assoc (load-state) :can-pick? (exists? js/showDirectoryPicker)))
  (let [{:keys [device profile]} (:library @app)]
    (when (not= (browser-name) (get-in profile [:devices device :name]))
      (operate! crdt/identify {:name (browser-name)})))
  (save! @app)
  (.then (media/volume-locked!) #(swap! app assoc :volume-locked? %))
  (replicant/set-dispatch! (fn [{:replicant/keys [dom-event node]} event]
                             (handle! event dom-event node)))
  (add-watch app ::render
             (fn [_ _ old new]
               (let [kept [:library :titles]]
                 (when (not= (select-keys old kept) (select-keys new kept))
                   (save! new)))
               (when-not (profile.queue/current
                          (get-in new [:library :profile]))
                 (off-lock-screen!))
               ;; the media element reports its time four times a second,
               ;; and its tracks at each cue, and only the bar shows them
               (render! (not= (dissoc old :media :reported :tracks)
                              (dissoc new :media :reported :tracks)))))
  (intercept-navigation!)
  (listen-to-other-tabs!)
  (render! true)
  (restore-folder!)
  (add-from-query!)
  (play-from-link!)
  (fill-recommended!)
  (fill-playable!)
  (sync-on-its-own!)
  (refresh-on-its-own!))
