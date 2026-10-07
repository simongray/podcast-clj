(ns dk.simongray.crdt-test
  "The CRDT of plain maps on a schema of its own: writes and the clock,
  merge by the later entry and by the higher count, compaction and the
  checks by the schema."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.crdt :as crdt]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3]]
            [dk.simongray.podcast.util.time :as time]))

(def schema
  {:notes {:depth 1 :compact? true}
   :plays {:depth 2 :count :n}})

(deftest writing-and-the-clock
  (let [s (crdt/write {:device "phone"} [:notes "n1"] (constantly {:text "x"}) t1)]
    (is (= {:text "x" :updated-at t1 :device "phone"} (get-in s [:notes "n1"])))
    (is (= t1 (:clock s)))
    (is (= (crdt/after s t0) (get-in (crdt/write s [:notes "n2"] (constantly {}) t0) [:notes "n2" :updated-at]))
        "an earlier time is stamped just after the clock")
    (testing "at once, many writes share one stamp"
      (let [many (crdt/at-once s t2 (fn [s] (reduce #(crdt/write %1 [:notes %2] (constantly {}) t2) s (range 100))))]
        (is (= #{t2} (set (map :updated-at (vals (dissoc (:notes many) "n1"))))))
        (is (nil? (crdt/problem schema many)))))
    (testing "a live entry and a tombstone"
      (is (crdt/live? (get-in s [:notes "n1"])))
      (is (not (crdt/live? (get-in (crdt/write s [:notes "n1"] #(assoc % :removed? true) t2) [:notes "n1"]))))
      (is (not (crdt/live? nil))))))

(deftest merging-by-the-schema
  (let [phone  (-> {:device "phone"}
                   (crdt/write [:notes "n1"] (constantly {:text "old"}) t0)
                   (crdt/write [:plays "song" "phone"] (constantly {:n 9}) t1))
        laptop (-> {:device "laptop"}
                   (crdt/write [:notes "n1"] (constantly {:text "new"}) t1)
                   (crdt/write [:plays "song" "phone"] (constantly {:n 5}) t2))
        merged (crdt/merge schema phone laptop)]
    (is (= "new" (get-in merged [:notes "n1" :text])) "the later entry")
    (is (= 9 (get-in merged [:plays "song" "phone" :n])) "the higher count, though it's the earlier entry")
    (is (= 5 (get-in (crdt/merge {} phone laptop) [:plays "song" "phone" :n])) "without a :count, the later entry")
    (is (= "phone" (:device merged)))
    (is (= (dissoc merged :device) (dissoc (crdt/merge schema laptop phone) :device)))
    (testing "two entries of one device at one instant: the same winner on every platform"
      (let [entry (fn [v] {:device "x" :notes {"n1" {:v v :updated-at t0 :device "x"}}})
            v     #(get-in (crdt/merge schema %1 %2) [:notes "n1" :v])]
        ;; the JVM prints 1.5E-4 and JavaScript 0.00015, so the printed floats
        ;; once ordered these two differently
        (is (= 1.55 (v (entry 1.5e-4) (entry 1.55)) (v (entry 1.55) (entry 1.5e-4))))))))

(deftest compacting-by-the-schema
  (let [day    (* 24 60 60 1000)
        far    (time/->inst (+ (inst-ms t3) (* 31 day)))
        phone  (-> {:device "phone"}
                   (crdt/identify {:name "Phone"} t0)
                   (crdt/write [:notes "n1"] (constantly {:removed? true}) t1)
                   (crdt/write [:drafts "d1"] (constantly {:removed? true}) t1)
                   (crdt/as-device "service:x" #(crdt/write % [:notes "n2"] (constantly {:removed? true}) t1)))
        laptop (-> (crdt/identify {:device "laptop"} {:name "Laptop"} t0)
                   (as-> s (crdt/merge schema s phone))
                   (crdt/acknowledge [phone] t2))
        phone  (crdt/acknowledge (crdt/merge schema phone laptop) [laptop] t3)
        later  (crdt/compact schema phone far)]
    (is (nil? (get-in later [:notes "n1"])) "a tombstone that every device has seen goes")
    (is (some? (get-in later [:drafts "d1"])) "a collection that the schema doesn't compact keeps its tombstones")
    (is (some? (get-in later [:notes "n2"]))
        "so does an author outside the registry, whose stamps say nothing of what the devices have seen")
    (is (= phone (crdt/compact schema phone t3)) "a young tombstone stays")
    (is (= {} (crdt/lagging phone)))))

(deftest what-another-device-may-not-send
  (is (nil? (crdt/problem schema {:device "x" :clock t0 :notes {"n1" {:updated-at t0 :device "x"}}})))
  (doseq [[state found] [[{:clock "now"} #"clock"]
                         [{:notes [1]} #":notes is not a map"]
                         [{:notes {"n1" {:text "x"}}} #"not stamped"]
                         [{:plays {"song" {:n 5 :updated-at t0 :device "x"}}} #"\[:plays \"song\" :n\] is not a map"]
                         [{:theme "dark"} #"\[:theme\] is not in a stamped entry"]
                         [{:devices {"x" {:seen {"y" 5} :updated-at t0 :device "x"}}} #"seen"]
                         [{:retired {"x" {}}} #"not stamped"]]]
    (is (re-find found (str (crdt/problem schema state))) (pr-str state)))
  (is (= "the state is not a map" (crdt/problem schema [1 2]))))

