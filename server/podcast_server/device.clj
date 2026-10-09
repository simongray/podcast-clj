(ns podcast-server.device
  "The server as one of the listener's devices: a library that keeps the
  listener's feeds current, and the gpodder API, which names episodes by
  those feeds.

  Each writes a sync document of its own to the server's sync folder. The
  library syncs every minute, then fetches the feeds that are due. The
  gpodder API syncs before and after each call, as
  dk.simongray.podcast.sync.gpodder.server/handler does.

  The library keeps only the slim part of each feed, and saves what it
  knows of the feeds to a file, so that a restart doesn't fetch them all
  again."
  (:require [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.sync.gpodder.server :as gpodder-server]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.url :as url]
            [io.pedestal.log :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def step-seconds
  "How often the library syncs and checks which feeds are due."
  60)

(defn make
  "The server's library, a device of the listener, with `config` over the
  defaults. Its feeds are fetched only from the public internet, only the
  slim part of each is kept, and since it doesn't search, it keeps no
  search index."
  ([]
   (make {}))
  ([config]
   (let [config (merge {:allow-pred url/public?
                        :keep-fn    feeds/slim
                        :search?    false}
                       config)]
     (library/make "podcast-server" {:name "Podcast server" :config config}))))

(def saved-keys
  "The parts of the library that the server saves between runs: what it
  knows of the feeds and the followed lists. The profile comes back from
  the sync documents, so it isn't saved, and nor are the cursors of the
  last sync, which only fit the profile they were made for."
  [:feeds :fetched-lists])

(defn save!
  "Write the saved-keys of `library` to `data-store`, e.g. a folder store,
  which writes a file whole or not at all, as library/save! does, and log
  a failure."
  [library data-store]
  (let [{:keys [error] :as saved} (library/save! library
                                                 data-store
                                                 {:keys saved-keys})]
    (when error
      (log/warn :msg "The library couldn't be saved" :problem error))
    saved))

(defn restore!
  "The `library` with what save! wrote to `data-store`, as library/restore!
  reads it, and what didn't read logged."
  [library data-store]
  (let [restored (library/restore! library data-store {:keys saved-keys})]
    (doseq [problem (get-in restored [:restore :report])]
      (log/warn :msg (str "The saved library couldn't be read, so its "
                          "feeds are fetched again")
                :problem problem))
    restored))

(defn step!
  "Sync `lib`, an atom of the server's library, through `backend`, fetch
  the feeds that are due, and sync again when that changed the profile.
  Returns the library."
  [lib backend]
  (swap! lib library/merge (library/sync! @lib [backend]))
  (swap! lib library/merge (library/refresh! @lib))
  (if (library/unsynced? @lib)
    (swap! lib library/merge (library/sync! @lib [backend]))
    @lib))

(defn- log-problems!
  "Log the failures of the last sync and fetch of `library`."
  [library]
  (doseq [problem (get-in library [:sync :report])
          :when   (= :sync/backend-failed (:id problem))]
    (log/warn :msg "The library couldn't sync" :problem problem))
  (doseq [problem (get-in library [:refresh :report])]
    (log/warn :msg "The library couldn't fetch a feed" :problem problem)))

(defn start!
  "Run step! on `lib`, an atom of the server's library, through the sync
  folder `sync-store` now and every step-seconds after, and save! it to
  `data-store` when its feeds change. Returns the executor that runs it,
  for stop!."
  [lib sync-store data-store]
  (let [backend (sync/backend sync-store)
        step    (fn []
                  (try
                    (let [before (select-keys @lib saved-keys)
                          after  (step! lib backend)]
                      (log-problems! after)
                      (when (not= before (select-keys after saved-keys))
                        (save! after data-store)))
                    ;; the executor runs no more steps after one that throws
                    (catch Throwable e
                      (log/error :msg "A step of the library failed"
                                 :exception e))))]
    (doto (Executors/newSingleThreadScheduledExecutor)
      (.scheduleWithFixedDelay ^Runnable step 0 step-seconds
                               TimeUnit/SECONDS))))

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
  (let [indexed (atom nil)
        index   (fn [feeds]
                  {:feeds feeds :names (gpodder/identities (vals feeds))})]
    (fn []
      (let [feeds (:feeds @lib)]
        (:names (swap! indexed #(if (identical? feeds (:feeds %))
                                  %
                                  (index feeds))))))))

(defn gpodder-handler
  "The gpodder API for AntennaPod, Kasts and gPodder over the sync folder
  `sync-store`, naming episodes by the feeds in `lib`, an atom of the
  server's library. It lets in the apps that `authorized-pred`, a function
  of a user name and a password, says yes to."
  [sync-store lib authorized-pred]
  (let [backend (sync/backend sync-store)
        cursors (atom nil)
        state   (atom (crdt/identify (profile/make "podcast-server-gpodder")
                                     {:name "Podcast server, for gpodder apps"}
                                     (time/now)))
        sync!   (fn [state]
                  (let [result (sync/sync! state [backend] {:cursors @cursors})]
                    (reset! cursors (:cursors result))
                    (:state result)))]
    (gpodder-server/handler
     {:state           state
      :authorized-pred authorized-pred
      :identities-fn   (identities lib)
      :sync            sync!})))
