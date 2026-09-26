(ns dk.simongray.podcast.app.sync-convergence-test
  "Devices that apply random operations and sync in random orders through
  a store end in the same state, one document per device or one shared
  document: the merge's properties carried through the engine."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.store :as store]))

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
    (gen/tuple (gen/return :identify) (gen/elements ["Mine" "Yours"]) instants)]))

(defn- apply-operation
  [state [kind x y z]]
  (case kind
    :subscribe   (player/subscribe state x {:url (str "https://" x "/rss") :title x} y)
    :unsubscribe (player/unsubscribe state x y)
    :progress    (player/progress state x y 3600 z)
    :enqueue     (player/enqueue state x y)
    :play-next   (player/play-next state x y)
    :dequeue     (player/dequeue state x y)
    :advance     (player/advance state x)
    :prefer      (player/prefer state {:skip-forward x} y)
    :configure   (player/configure state x {:skip-intro y} z)
    :identify    (player/identify state {:name x} y)))

(def schedule
  "What happens next: a device syncs, or applies an operation."
  (gen/vector (gen/tuple (gen/choose 0 (dec (count devices)))
                         (gen/one-of [(gen/return :sync) operation]))
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
        backend   (store/backend store)
        ticks     (atom 100)
        now!      (fn [] (java.util.Date. (* 1000 (long (swap! ticks inc)))))
        world     (atom (mapv (fn [d] {:state (player/state d) :ctx nil}) devices))
        sync-one! (fn [i]
                    (swap! world update i
                           (fn [{:keys [state ctx]}]
                             (let [r (sync/sync! state [backend] (now!) (assoc opts :ctx ctx))]
                               (when (:failed r) (throw (ex-info "a backend failed" r)))
                               {:state (:state r) :ctx (:ctx r)}))))]
    (doseq [[i action] steps]
      (if (= :sync action)
        (sync-one! i)
        (swap! world update-in [i :state] apply-operation action)))
    (dotimes [_ 3]
      (doseq [i (range (count devices))]
        (sync-one! i)))
    (map (comp #(dissoc % :device) :state) @world)))

(defn- seen-by-the-listener
  "What a state shows: the live subscriptions and their settings, the queue
  in order, the episodes and the preferences."
  [state]
  {:subscriptions (player/subscriptions state)
   :settings      (:settings state)
   :queue         (player/queue state)
   :episodes      (:episodes state)
   :preferences   (player/preferences state)})

(defn- tombstones
  [state]
  (count (filter :removed? (concat (vals (:subscriptions state)) (vals (:queue state))))))

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
           (every? (fn [s] (= (seen-by-the-listener s) (seen-by-the-listener (player/compact s far {:keep-for 0})))) states)))))
