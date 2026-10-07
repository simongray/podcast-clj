(ns dk.simongray.crdt.list-test
  "Ordered collections in a CRDT state: appends that keep each device's
  batch together, moves that never undo a removal, and the check of the
  ranks by the schema."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.crdt :as crdt]
            [dk.simongray.crdt-test :refer [t0 t1 t2 t3]]
            [dk.simongray.crdt.list :as crdt.list]))

(deftest ordered-collections
  (let [phone  (crdt.list/append {:device "phone"} [:queue] {} ["a" "b" "c"] t0)
        laptop (crdt.list/append {:device "laptop"} [:queue] {} ["d" "e"] t1)
        both   (crdt/merge {} phone laptop)
        view   #(crdt.list/with-moves (:queue %) (:moves %))
        order  #(mapv key (crdt.list/ranked (view %)))
        moved  (crdt/write both [:moves "e"] (constantly {:rank (crdt.list/rank-at (view both) "e" 2)}) t2)]
    (is (= ["d" "e" "a" "b" "c"] (order both)) "each device's batch stays together")
    (is (= ["d" "a" "e" "b" "c"] (order moved)) "a move puts the entry at its new place")
    (is (= both (crdt.list/append both [:queue] (view both) ["a"] t2)) "an id that's there stays where it is")
    (is (= ["d" "e" "a" "b" "c" "f"]
           (order (crdt/write both [:queue "f"] (constantly {:rank (crdt.list/rank-at-end (view both))}) t2)))
        "a rank at the end")
    (testing "a move never brings back what another device removed"
      (let [removed (crdt/write both [:queue "e"] #(assoc % :removed? true) t2)]
        (is (= ["d" "a" "b" "c"] (order (crdt/merge {} removed (assoc moved :device "laptop")))))))
    (testing "an addition after a move puts the entry where the addition says"
      (let [again (crdt/write moved [:queue "e"] (constantly {:rank "z"}) t3)]
        (is (= ["d" "a" "b" "c" "e"] (order again)))))))

(deftest the-ranks-by-the-schema
  (let [schema {:queue     {:depth 1 :ranked? true}
                :playlists {:depth 2 :ranked? true}
                :notes     {:depth 1}}
        entry  #(hash-map :rank % :updated-at t0 :device "x")]
    (is (nil? (crdt.list/problem schema (crdt.list/append {:device "x"} [:queue] {} ["a"] t0))))
    (is (re-find #"\[:queue \"a\"\] is 5, not a rank" (crdt.list/problem schema {:queue {"a" (entry 5)}})))
    (is (re-find #"\[:playlists \"P\" \"a\"\]" (crdt.list/problem schema {:playlists {"P" {"a" (entry "V!")}}})))
    (is (nil? (crdt.list/problem schema {:notes {"a" (entry 5)}})) "only the collections marked :ranked?")))
