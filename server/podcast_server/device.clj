(ns podcast-server.device
  "The server as one of the listener's devices: a library that keeps the
  listener's feeds current, and the gpodder API, which names episodes by
  those feeds.

  Each writes a sync document of its own to the server's sync folder. The
  library syncs every minute, then fetches the feeds that are due and
  those that WebSub hubs pushed. The gpodder API syncs before and after
  each call.

  The library saves the slim part of each feed to its data folder, so that
  a restart doesn't fetch them all again, and keeps no feed in memory
  between its steps. A step loads the feeds that are due. What stays is a
  skeleton of each feed, the guid and the media of its items, which name
  the episodes for the gpodder API and the media files that the server
  streams.

  The gpodder API keeps its book in the data folder too: when each change
  arrived, the apps' devices and the URLs of their episodes. So after a
  restart, the apps get only what changed."
  (:require [clojure.edn :as edn]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.gpodder :as gpodder]
            [dk.simongray.podcast.gpodder.server :as gpodder-server]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.url :as url]
            [io.pedestal.log :as log]
            [podcast-server.hubs :as hubs])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def step-seconds
  "How often the library syncs and checks which feeds are due."
  60)

(defn make
  "The server's library, a device of the listener, with `config` over the
  defaults. Since it doesn't search, it keeps no search index."
  ([]
   (make {}))
  ([config]
   (library/make "podcast-server" {:name   "Podcast server"
                                   :config (merge {:search? false} config)})))

(def defaults
  "The options of the calls to the server's library: its feeds are
  fetched only from the public internet, and only the slim part of each
  is kept."
  {:allow-pred url/public?
   :keep-fn    feeds/slim})

;; The profile comes back from the sync documents, so it isn't saved, and
;; nor are the cursors of the last sync, which only fit the profile they
;; were made for
(def saved-keys
  "The parts of the library that the server saves between runs: what it
  knows of the feeds and the followed lists."
  [:feeds :fetched-lists])

(defn save!
  "Write the saved parts of `library` to `data-store`, e.g. a folder store,
  which writes a file whole or not at all, and log a failure."
  [library data-store]
  (let [{:keys [error] :as saved} (library/save! library
                                                 data-store
                                                 {:keys saved-keys})]
    (when error
      (log/warn :msg "The library couldn't be saved" :problem error))
    saved))

(defn- skeleton
  "What the server keeps of the feed of `entry` while it isn't loaded: the
  URL, and the guid and the media of each item."
  [{:keys [url feed] :as entry}]
  (let [kept [:rss/guid :rss/enclosure :podcast/alternate-enclosures
              :media/contents :media/group]]
    {:url  url
     :feed {:rss/items (mapv #(select-keys % kept) (:rss/items feed))}}))

(defn remembered
  "The `library` with the skeleton of each feed that it holds in memory
  under ::known, kept by the guid of the feed, and without those of the
  feeds that it no longer has."
  [library]
  (let [feeds (:feeds library)]
    (assoc library ::known
           (into (select-keys (::known library) (keys feeds))
                 (for [[feed-guid entry] feeds
                       :when (:feed entry)]
                   [feed-guid (skeleton entry)])))))

(defn restore!
  "The `library` with what was saved to `data-store`, and what didn't read
  logged. Its feeds stay in their documents, and each is read once for its
  skeleton."
  [library data-store]
  (let [{restored :library :keys [report]}
        (library/restore! library data-store {:keys saved-keys :load :none})

        known (fn [lib feed-guid]
                (let [read  (:library (library/load! lib data-store [feed-guid]))
                      entry (get-in read [:feeds feed-guid])]
                  (cond-> lib
                    (:feed entry)
                    (assoc-in [::known feed-guid] (skeleton entry)))))]
    (doseq [problem report]
      (log/warn :msg (str "The saved library couldn't be read, so its "
                          "feeds are fetched again")
                :problem problem))
    (reduce known (assoc restored ::known {}) (library/unloaded restored))))

(defn- synced!
  "Sync `lib`, an atom of the server's library, through `backend`, and log
  the backends that failed."
  [lib backend]
  (let [{:keys [library report]} (library/sync! @lib [backend])]
    (swap! lib library/merge library)
    (doseq [problem report
            :when   (= :sync/backend-failed (:id problem))]
      (log/warn :msg "The library couldn't sync" :problem problem))))

(defn step!
  "Sync `lib`, an atom of the server's library, through `backend`, fetch
  the feeds that are due, or those that the :feed-guids of `opts` name,
  and sync again when that changed the profile. The `opts` go over the
  defaults, e.g. with the :store of its data folder, from which the feeds
  that are due are loaded. It logs the feeds that it couldn't fetch, and
  returns the library, which holds the feeds that it fetched, and the
  skeletons of all of them."
  ([lib backend]
   (step! lib backend {}))
  ([lib backend opts]
   (synced! lib backend)
   (let [{:keys [library report]} (library/refresh! @lib (merge defaults opts))]
     (swap! lib library/merge library)
     (doseq [problem report]
       (log/warn :msg "The library couldn't fetch a feed" :problem problem)))
   (when (library/unsynced? @lib)
     (synced! lib backend))
   (swap! lib remembered)))

(defn unload!
  "Let the feeds of `lib`, an atom of the server's library, go from memory,
  each written to its document in `data-store` first, and log a failure."
  [lib data-store]
  (let [{:keys [library report]} (library/unload! @lib data-store :all)]
    (doseq [problem report]
      (log/warn :msg "The feeds couldn't be let go" :problem problem))
    (swap! lib library/merge library)))

(defn start!
  "Run a step of `lib`, an atom of the server's library, through the sync
  folder `sync-store` now and every step-seconds after, and save it to
  `data-store` when its feeds change. With `hubs`, each step also fetches
  the feeds that their hubs pushed, and then sends the hubs what the shows
  need. Returns the executor that runs the steps."
  ([lib sync-store data-store]
   (start! lib sync-store data-store nil))
  ([lib sync-store data-store hubs]
   (let [backend (library/backend sync-store)
         run     #(step! lib backend (assoc % :store data-store))
         step    (fn []
                   (try
                     (let [before (select-keys @lib saved-keys)
                           pushed (some-> hubs hubs/pushed!)]
                       (run {})
                       (when (seq pushed)
                         (run {:feed-guids (vec pushed)}))
                       (when hubs
                         (hubs/ask! @lib hubs))
                       (when (not= before (select-keys @lib saved-keys))
                         (save! @lib data-store))
                       (unload! lib data-store))
                     ;; the executor runs no more steps after one that throws
                     (catch Throwable e
                       (log/error :msg "A step of the library failed"
                                  :exception e))))]
     (doto (Executors/newSingleThreadScheduledExecutor)
       (.scheduleWithFixedDelay ^Runnable step 0 step-seconds
                                TimeUnit/SECONDS)))))

(defn stop!
  "Stop the steps that `executor` runs."
  [^ScheduledExecutorService executor]
  (.shutdownNow executor)
  nil)

(defn- identities
  "The ways to name the shows and episodes of the feeds in `lib`, an atom
  of a library, by their skeletons, as a function of no arguments for the
  gpodder API. The feeds are indexed again only when they changed."
  [lib]
  (let [indexed (atom nil)
        index   (fn [known]
                  {:known known
                   :names (gpodder/identities
                           (for [[feed-guid skeleton] known]
                             (assoc skeleton :guid feed-guid)))})]
    (fn []
      (let [known (::known @lib)]
        (:names (swap! indexed #(if (identical? known (:known %))
                                  %
                                  (index known))))))))

(def gpodder-book-name
  "The document of the data folder that holds the book of the gpodder API,
  so that after a restart the apps get only what changed."
  "gpodder-book.edn")

(defn- saved-book
  "The book of the gpodder API that `data-store` holds, or nil when it has
  none or it doesn't read, which is logged."
  [data-store]
  (try
    (some-> ((:fetch data-store) gpodder-book-name {}) :body edn/read-string)
    (catch Exception e
      (log/warn :msg "The book of the gpodder API didn't read" :exception e)
      nil)))

(defn- save-book!
  "Write the `book` of the gpodder API to `data-store`, and log a failure."
  [data-store book]
  (try
    ((:put data-store) gpodder-book-name
                       (binding [*print-length* nil
                                 *print-level*  nil]
                         (pr-str book))
                       {})
    (catch Exception e
      (log/warn :msg "The book of the gpodder API couldn't be saved"
                :exception e))))

(defn gpodder-handler
  "The gpodder API for AntennaPod, Kasts and gPodder over the sync folder
  `sync-store`, with its book in `data-store`, naming episodes by the
  feeds in `lib`, an atom of the server's library. It lets in the apps
  that `authorized-pred`, a function of a user name and a password, says
  yes to."
  [sync-store data-store lib authorized-pred]
  (let [state (atom (crdt/identify (profile/make "podcast-server-gpodder")
                                   {:name "Podcast server, for gpodder apps"}
                                   (time/now)))]
    (gpodder-server/handler
     {:state           state
      :authorized-pred authorized-pred
      :identities-fn   (identities lib)
      :sync            (gpodder-server/stepper [(library/backend sync-store)])
      :book            (saved-book data-store)
      :on-book-fn      #(save-book! data-store %)})))
