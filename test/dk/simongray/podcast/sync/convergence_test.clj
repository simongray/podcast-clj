(ns dk.simongray.podcast.sync.convergence-test
  "Devices that apply random operations and sync in random orders through
  a store end in the same state, one document per device or one shared
  document: the merge's properties carried through the engine."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.player :as profile.player]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.sync :as sync]))

(def devices
  ["phone" "laptop" "car"])

(def instants
  (gen/fmap #(java.util.Date. (* 1000 (long %))) (gen/choose 0 5)))

(def feed-guids
  (gen/elements ["feed-a" "feed-b"]))

(def episode-ids
  (gen/elements [["feed-a" "a1"] ["feed-b" "b1"]]))

(def operation
  "A random operation on a state, as data, so that a failing case reads."
  (gen/one-of
   [(gen/tuple (gen/return :subscribe) feed-guids instants)
    (gen/tuple (gen/return :unsubscribe) feed-guids instants)
    (gen/tuple (gen/return :progress) episode-ids (gen/choose 0 3600) instants)
    (gen/tuple (gen/return :enqueue) episode-ids instants)
    (gen/tuple (gen/return :play-next) episode-ids instants)
    (gen/tuple (gen/return :dequeue) episode-ids instants)
    (gen/tuple (gen/return :advance) instants)
    (gen/tuple (gen/return :prefer) (gen/elements [15 30 45]) instants)
    (gen/tuple (gen/return :configure) feed-guids (gen/elements [0 10 20]) instants)
    (gen/tuple (gen/return :identify) (gen/elements ["Mine" "Yours"]) instants)
    (gen/tuple (gen/return :star) episode-ids instants)
    (gen/tuple (gen/return :unstar) episode-ids instants)
    (gen/tuple (gen/return :bookmark) episode-ids (gen/elements [30 754]) instants)
    (gen/tuple (gen/return :unbookmark) episode-ids (gen/elements [30 754]) instants)
    (gen/tuple (gen/return :arrive) episode-ids instants)
    (gen/tuple (gen/return :dismiss) episode-ids instants)
    (gen/tuple (gen/return :folder) feed-guids (gen/elements [nil "News"]) instants)
    (gen/tuple (gen/return :tag) feed-guids (gen/elements ["Tech" "Daily"]) instants)
    (gen/tuple (gen/return :untag) feed-guids (gen/elements ["Tech" "Daily"]) instants)
    (gen/tuple (gen/return :follow-list) (gen/elements ["https://l/1.opml" "https://l/2.opml"]) (gen/elements [nil "Picks"]) instants)
    (gen/tuple (gen/return :unfollow-list) (gen/elements ["https://l/1.opml" "https://l/2.opml"]) instants)
    (gen/tuple (gen/return :save-filter) (gen/elements ["A" "B"]) instants)
    (gen/tuple (gen/return :remove-filter) (gen/elements ["A" "B"]) instants)
    (gen/tuple (gen/return :add-to-playlist) (gen/elements ["P" "Q"]) episode-ids instants)
    (gen/tuple (gen/return :remove-from-playlist) (gen/elements ["P" "Q"]) episode-ids instants)
    (gen/tuple (gen/return :rename-playlist) (gen/elements [nil "Q"]) instants)
    (gen/tuple (gen/return :listen) episode-ids (gen/choose 0 3600) instants)]))

(def feed-operation
  "A random operation of what a feed brings about, or a subscription with
  the setting that a refresh needs."
  (gen/one-of
   [(gen/tuple (gen/return :subscribe-with) feed-guids (gen/elements [:auto-queue? :inbox?]) instants)
    (gen/tuple (gen/return :refresh) feed-guids instants instants)
    (gen/tuple (gen/return :relocate) feed-guids (gen/elements ["rss" "moved"]))
    (gen/tuple (gen/return :settle) feed-guids (gen/elements ["a0000000-0000-5000-8000-000000000001"
                                                              "b0000000-0000-5000-8000-000000000002"]))]))

(defn- refreshed
  "The `state` after a refresh at `at` that finds the one episode of the
  feed `feed-guid`, published at `published`."
  [state feed-guid published at]
  (let [item   {:rss/guid {:content ({"feed-a" "a1" "feed-b" "b1"} feed-guid)} :rss/pub-date published}
        keyed  #(guid/with-key {:podcast/guid feed-guid :rss/items %} feed-guid)]
    (:profile (profile.queue/refresh state (str "https://" feed-guid "/rss") (keyed []) (keyed [item]) at))))

(defn- apply-operation
  [state [kind x y z]]
  (case kind
    :subscribe            (profile/subscribe state x {:url (str "https://" x "/rss") :title x} y)
    :unsubscribe          (profile/unsubscribe state x y)
    :progress             (profile/progress state x y 3600 z)
    :enqueue              (profile.queue/enqueue state x y)
    :play-next            (profile.queue/play-next state x y)
    :dequeue              (profile.queue/dequeue state x y)
    :advance              (profile.queue/advance state x)
    :prefer               (profile/prefer state {:skip-forward x} y)
    :configure            (profile/configure state x {:skip-intro y} z)
    :identify             (crdt/identify state {:name x} y)
    :star                 (profile.marks/star state x y)
    :unstar               (profile.marks/unstar state x y)
    :bookmark             (profile.marks/bookmark state x y "A bookmark" z)
    :unbookmark           (profile.marks/unbookmark state x y z)
    :arrive               (crdt/write state [:inbox x] (constantly {:removed? false}) y)
    :dismiss              (profile.queue/dismiss state x y)
    :folder               (profile/configure state x {:folder y} z)
    :tag                  (profile/tag state x y z)
    :untag                (profile/untag state x y z)
    :follow-list          (profile.opml/follow-list state x {:title x :folder y} z)
    :unfollow-list        (profile.opml/unfollow-list state x y)
    :save-filter          (profile/save-filter state x {:where {:starred? true}} y)
    :remove-filter        (profile/remove-filter state x y)
    :add-to-playlist      (profile.queue/add-to-playlist state x y z)
    :remove-from-playlist (profile.queue/remove-from-playlist state x y z)
    :rename-playlist      (profile.queue/rename-playlist state "P" x y)
    :listen               (:profile (profile.player/step state [:progress x y 3600] z))
    :subscribe-with       (-> (profile/subscribe state x {:url (str "https://" x "/rss") :title x} z)
                              (profile/configure x {y true} z))
    :refresh              (refreshed state x y z)
    :relocate             (profile/relocate state x {:url (str "https://" x "/" y) :title x})
    :settle               (profile/settle state x y)))

(def schedule
  "What happens next: a device syncs, or applies an operation."
  (gen/vector (gen/tuple (gen/choose 0 (dec (count devices)))
                         (gen/frequency [[3 (gen/return :sync)] [2 operation] [2 feed-operation]]))
              0 20))

(defn run
  "The states of the devices, their own ids left out, after `steps` through
  a memory store with `strategy`, and three rounds in which every device
  syncs and nothing else happens: one for the entries to reach everyone,
  one for what every device acknowledged to reach everyone, and one for
  the compaction that follows. `opts` are `sync!`'s."
  [strategy steps opts]
  (let [documents (atom {})
        store     (cond-> (store/memory documents) (= :shared strategy) (dissoc :list))
        backend   (sync/backend store)
        ticks     (atom 100)
        now!      (fn [] (java.util.Date. (* 1000 (long (swap! ticks inc)))))
        world     (atom (mapv (fn [d] {:state (profile/make d) :cursors nil}) devices))
        sync-one! (fn [i]
                    (swap! world update i
                           (fn [{:keys [state cursors]}]
                             (let [r (sync/sync! state [backend] (assoc opts :at (now!) :cursors cursors))]
                               (when (some #(= :sync/backend-failed (:id %)) (:report r))
                                 (throw (ex-info "a backend failed" r)))
                               {:state (:state r) :cursors (:cursors r)}))))]
    (doseq [[i action] steps]
      (if (= :sync action)
        (sync-one! i)
        (swap! world update-in [i :state] apply-operation action)))
    (dotimes [_ 3]
      (doseq [i (range (count devices))]
        (sync-one! i)))
    (map (comp #(dissoc % :device) :state) @world)))

(defn- seen-by-the-listener
  "What a state shows: the live subscriptions, their settings, folders and
  tags, the followed lists, the queue and the playlists in order, the
  inbox, the episodes, the stars, the bookmarks, the saved filters, the
  counts of listening and the preferences."
  [state]
  {:subscriptions (profile/subscriptions state)
   :lists         (profile.opml/lists state)
   :playlists     (profile.queue/playlists state)
   :listened      (profile/listened state)
   :settings      (:settings state)
   :folders       (profile/folders state)
   :tagged        (profile/tagged state)
   :queue         (profile.queue/queue state)
   :inbox         (profile.queue/inbox state)
   :episodes      (profile/episode-entries state)
   :starred       (profile.marks/starred state)
   :bookmarks     (profile.marks/bookmarks state)
   :filters       (profile/filters state)
   :preferences   (profile/preferences state)})

(defn- tombstones
  "How many tombstones `state` holds that a compaction drops. Those of the
  unsubscriptions stay while the listener follows a list, so they count
  only for a listener who follows none."
  [state]
  (let [flat (cond-> [:lists :queue :queue-moves :inbox :stars :filters]
               (empty? (profile.opml/lists state)) (conj :subscriptions))]
    (count (filter :removed? (concat (mapcat (comp vals state) flat)
                                     (mapcat vals (mapcat (comp vals state) [:bookmarks :tags :playlists :playlist-moves])))))))

(defspec devices-converge-through-a-folder-of-documents 100
  (prop/for-all [steps schedule]
    (apply = (run :per-device steps {}))))

(defspec devices-converge-through-one-shared-document 100
  (prop/for-all [steps schedule]
    (apply = (run :shared steps {}))))

(defspec compaction-leaves-no-tombstone-and-touches-nothing-else 100
  (prop/for-all [steps schedule]
    (let [states (run :per-device steps {:compact {:keep-for 0}})
          far    (java.util.Date. (* 1000 3600))]
      (and (apply = states)
           (every? zero? (map tombstones states))
           (every? (fn [s] (= (seen-by-the-listener s) (seen-by-the-listener (profile/compact s far {:keep-for 0})))) states)))))
