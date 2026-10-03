(ns podcast-server.device
  "The server as one of the listener's devices: a library that keeps the
  listener's feeds current, and the gpodder API, which names episodes by
  those feeds.

  Each writes a sync document of its own to the server's sync folder. The
  library syncs every minute, then fetches the feeds that are due. The
  gpodder API syncs before and after each call, as
  dk.simongray.podcast.sync.gpodder.server/handler does."
  (:require [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.sync.gpodder.server :as gpodder-server]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]
            [io.pedestal.log :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def step-seconds
  "How often the library syncs and checks which feeds are due."
  60)

(defn make
  "The server's library, a device of the listener, with `config` over the
  defaults. Its feeds are fetched only from the public internet."
  ([]
   (make {}))
  ([config]
   (library/make "podcast-server" {:name   "Podcast server"
                                   :config (merge {:allow? http/public?} config)})))

(defn- changed?
  "Whether the profile of `library` changed since its last sync."
  [library]
  (not= (:clock (:profile library)) (get-in library [:sync :clock])))

(defn step!
  "Sync `lib`, an atom of the server's library, through `backend`, fetch
  the feeds that are due, and sync again when that changed the profile.
  Returns the library."
  [lib backend]
  (swap! lib library/merge (library/sync! @lib [backend]))
  (swap! lib library/merge (library/refresh! @lib))
  (if (changed? @lib)
    (swap! lib library/merge (library/sync! @lib [backend]))
    @lib))

(defn- log-problems!
  "Log the failures of the last sync and fetch of `library`."
  [library]
  (when-let [failed (get-in library [:sync :failed])]
    (log/warn :msg "The library couldn't sync" :failed failed))
  (doseq [problem (get-in library [:refresh :report])]
    (log/warn :msg "The library couldn't fetch a feed" :problem problem)))

(defn start!
  "Run step! on `lib`, an atom of the server's library, through the sync
  folder `store`, now and every step-seconds after. Returns the executor
  that runs it, for stop!."
  [lib store]
  (let [backend (store/backend store)
        step    (fn []
                  (try
                    (log-problems! (step! lib backend))
                    (catch Throwable e
                      (log/error :msg "A step of the library failed" :exception e))))]
    (doto (Executors/newSingleThreadScheduledExecutor)
      (.scheduleWithFixedDelay ^Runnable step 0 step-seconds TimeUnit/SECONDS))))

(defn stop!
  "Stop the steps that `executor` runs."
  [^ScheduledExecutorService executor]
  (.shutdownNow executor)
  nil)

(defn- identities
  "The ways to name the shows and episodes of the feeds in `lib`, an atom
  of a library, as a function of no arguments for the gpodder API. The
  feeds are indexed again only when they changed."
  [lib]
  (let [indexed (atom nil)]
    (fn []
      (let [feeds (:feeds @lib)]
        (:names (swap! indexed #(if (identical? feeds (:feeds %))
                                  %
                                  {:feeds feeds
                                   :names (gpodder/identities (into {} (map (juxt :url :feed)) (vals feeds)))})))))))

(defn gpodder-handler
  "The gpodder API for AntennaPod, Kasts and gPodder over the sync folder
  `store`, naming episodes by the feeds in `lib`, an atom of the server's
  library.

  It lets in any user name and password, since the server answers only
  its private network."
  [store lib]
  (let [backend (store/backend store)
        cursors (atom nil)]
    (gpodder-server/handler
     {:state       (atom (crdt/identify (profile/make "podcast-server-gpodder")
                                        {:name "Podcast server, for gpodder apps"}
                                        (time/now)))
      :authorized? (constantly true)
      :identities  (identities lib)
      :sync        (fn [state]
                     (let [result (sync/sync! state [backend] (time/now) {:cursors @cursors})]
                       (reset! cursors (:cursors result))
                       (:state result)))})))
