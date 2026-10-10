(ns dk.simongray.podcast.sync.engine-convergence-test
  "Devices that apply random operations to a state of mind-meld, with
  notes, an ordered queue and counts, and sync in random orders through a
  store end in the same state, one document per device or one shared
  document. Compaction then leaves no tombstone and changes nothing that a
  user sees. sync.convergence-test does the same for the profile."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.mind-meld.list :as crdt.list]
            [dk.simongray.podcast.store :as store]
            [dk.simongray.podcast.sync :as sync]))

(def schema
  "Notes by id, a queue with its moves, and the plays of each item by
  device, which only grow."
  {:notes       {:depth 1 :compact? true}
   :queue       {:depth 1 :compact? true :ranked? true}
   :queue-moves {:depth 1 :compact? true :ranked? true}
   :plays       {:depth 2 :count-key :n}})

(def opts
  "The options of a sync of the state."
  {:schema schema})

(def devices
  ["phone" "laptop" "car"])

(def instants
  (gen/fmap #(java.util.Date. (* 1000 (long %))) (gen/choose 0 5)))

(def ids
  (gen/elements ["a" "b" "c"]))

(def operation
  "A random operation on a state, as data, so that a failing case reads."
  (gen/one-of
   [(gen/tuple (gen/return :note) ids (gen/elements ["One" "Two"]) instants)
    (gen/tuple (gen/return :remove-note) ids instants)
    (gen/tuple (gen/return :append) ids instants)
    (gen/tuple (gen/return :move) ids (gen/choose 0 2) instants)
    (gen/tuple (gen/return :dequeue) ids instants)
    (gen/tuple (gen/return :play) ids (gen/choose 1 3) instants)
    (gen/tuple (gen/return :identify) (gen/elements ["Mine" "Yours"]) instants)]))

(defn- queue-entries
  "The queue of `state` with its moves."
  [state]
  (crdt.list/with-moves (:queue state) (:queue-moves state)))

(defn- queued?
  [state id]
  (crdt/live? (get (:queue state) id)))

(defn- removed
  [entry]
  (assoc entry :removed? true))

(defn- apply-operation
  [state [kind x y z]]
  (case kind
    :note        (crdt/write state [:notes x] (constantly {:text y}) z)
    :remove-note (crdt/write state [:notes x] removed y)
    :append      (crdt.list/append state [:queue] (queue-entries state) [x] y)
    :move        (if (queued? state x)
                   (crdt/write state
                               [:queue-moves x]
                               (constantly {:rank (crdt.list/rank-at (queue-entries state) x y)})
                               z)
                   state)
    :dequeue     (if (queued? state x)
                   (crdt/write-each state [[[:queue x] removed] [[:queue-moves x] removed]] y)
                   state)
    :play        (crdt/write state [:plays x (:device state)] #(update % :n (fnil + 0) y) z)
    :identify    (crdt/identify state {:name x} y)))

(def schedule
  "What happens next: a device syncs, or applies an operation."
  (gen/vector (gen/tuple (gen/choose 0 (dec (count devices)))
                         (gen/frequency [[3 (gen/return :sync)] [4 operation]]))
              0 20))

(defn run
  "The states of the devices, their own ids left out, after `steps` through
  a memory store with `strategy`, and three rounds in which every device
  syncs and nothing else happens: one for the entries to reach everyone,
  one for what every device acknowledged to reach everyone, and one for
  the compaction that follows. `sync-opts` are sync!'s."
  [strategy steps sync-opts]
  (let [documents (atom {})
        store     (cond-> (store/memory documents) (= :shared strategy) (dissoc :list))
        backend   (sync/backend store opts)
        ticks     (atom 100)
        now!      (fn [] (java.util.Date. (* 1000 (long (swap! ticks inc)))))
        world     (atom (mapv (fn [d] {:state {:device d} :cursors nil}) devices))
        sync-one! (fn [i]
                    (swap! world update i
                           (fn [{:keys [state cursors]}]
                             (let [r (sync/sync! state [backend] (merge opts sync-opts {:at (now!) :cursors cursors}))]
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

(defn- seen-by-the-user
  "What a state shows: the live notes, the queue in order, and the plays."
  [state]
  {:notes (into {} (filter (comp crdt/live? val)) (:notes state))
   :queue (mapv key (crdt.list/ranked (queue-entries state)))
   :plays (:plays state)})

(defn- tombstones
  "How many tombstones `state` holds in the collections that compact."
  [state]
  (count (filter :removed? (mapcat (comp vals state) [:notes :queue :queue-moves]))))

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
           (every? (fn [s] (= (seen-by-the-user s) (seen-by-the-user (crdt/compact schema s far {:keep-for 0}))))
                   states)))))
