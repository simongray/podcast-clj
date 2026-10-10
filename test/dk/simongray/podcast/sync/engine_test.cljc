(ns dk.simongray.podcast.sync.engine-test
  "The sync engine over a state of mind-meld rather than the profile:
  documents in their envelope and what reading them reports, two devices
  through a folder of documents and through one shared document, and a
  step through several backends with one failing. Also the options of a
  state: another CRDT, its kind, the version of its format, a codec and
  extra documents."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            #?(:clj [dk.simongray.mind-meld :as crdt])
            [dk.simongray.podcast.store :as store]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.test-support :refer [t2 t3 t4 failed note notes text with-notes
                                                       phone-notes laptop-notes #?@(:clj [same?])]]
            [dk.simongray.podcast.util.time :as time]))

(deftest documents
  (let [s   (phone-notes)
        doc (sync/document s t2 {:app "test"})]
    (is (str/starts-with? doc "{:version 1"))
    (is (= {:version 1 :device "phone" :written-at t2 :app "test" :state s} (sync/read-document doc with-notes)))
    (is (= s (:state (sync/read-document (sync/document s t2) with-notes))))
    (testing "what is not a document"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"decode" (sync/read-document "{:version 1 :state" with-notes)))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"decode" (sync/read-document (apply str (repeat 100000 "[")) with-notes))
          "not even when the reader runs out of stack")
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"sync document" (sync/read-document "[1 2 3]" with-notes)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (sync/read-document nil with-notes))))
    (testing "a document whose state can't be merged"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"can't be merged"
                            (sync/read-document (pr-str {:version 1 :state {:clock "now"}}) with-notes))))))

(deftest a-document-that-would-break-the-merge
  (let [fetched [{:name "phone.edn" :body (sync/document (phone-notes) t2)}
                 {:name "poison.edn" :body (pr-str {:version 1 :device "x" :written-at t2
                                                    :state {:notes {"x" {:text "No stamp"}}}})}]
        {:keys [state report]} (sync/reconcile {:device "tablet"} fetched t2 with-notes)]
    (is (= [[:sync/unreadable-document "poison.edn"]] (map (juxt :id :name) report)))
    (is (= "Buy eggs" (text state "eggs")) "the other documents are merged")))

(deftest reading-documents
  (let [fetched [{:name "phone.edn" :body (sync/document (phone-notes) t2)}
                 {:name "laptop.edn" :body (sync/document (laptop-notes) t2)}
                 {:name "junk.edn" :body "<html>"}
                 {:name "future.edn" :body (pr-str {:version 99 :device "x" :written-at t2 :state {}})}
                 {:name "ahead.edn" :body (sync/document {:device "car"} t4)}]
        {:keys [states report]} (sync/read-documents fetched t2 with-notes)]
    (is (= 3 (count states)))
    (is (= [:sync/unreadable-document :sync/newer-format :sync/clock-skew] (map :id report)))
    (is (every? :message report) "every diagnostic is described")
    (is (= "junk.edn" (:name (first report))))
    (is (= {:name "ahead.edn" :device "car" :seconds 7200} (select-keys (nth report 2) [:name :device :seconds])))
    (testing "reconciled into a state"
      (let [{:keys [state report]} (sync/reconcile {:device "tablet"} fetched t2 with-notes)]
        (is (= 3 (count report)))
        (is (= "Buy six eggs" (text state "eggs")) "the later change wins")
        (is (= #{"milk" "eggs" "bread"} (set (keys (:notes state)))))
        (is (= "tablet" (:device state)))))))

(def counter
  "A CRDT of one count, which merges by the higher, for a sync of a state
  that isn't of mind-meld."
  {:merge   (fn [a b] (update a :n max (:n b 0)))
   :compact (fn [state _ _] state)
   :problem (fn [state] (when-not (number? (:n state)) "no count"))})

(deftest another-crdt
  (let [doc     #(sync/document {:device %1 :n %2} t2)
        fetched [{:name "a.edn" :body (doc "a" 3)}
                 {:name "b.edn" :body (doc "b" 5)}
                 {:name "c.edn" :body (pr-str {:version 1 :device "c" :written-at t2 :state {:device "c"}})}]
        {:keys [state report]} (sync/reconcile {:device "me" :n 1} fetched t2 {:crdt counter})]
    (is (= 5 (:n state)) "merged by the :merge of the CRDT")
    (is (= [[:sync/unreadable-document "c.edn"]] (map (juxt :id :name) report))
        "and read with its :problem")
    (testing "whose functions go over those of a schema"
      (let [mine    {:schema notes :crdt {:merge (fn [a _] a)}}
            fetched [{:name "laptop.edn" :body (sync/document (laptop-notes) t2)}]]
        (is (= "Buy eggs" (text (:state (sync/reconcile (phone-notes) fetched t2 mine)) "eggs"))
            "the laptop's later change is left out by the :merge of :crdt")))
    #?(:clj
       (let [shared   (store/memory)
             backend  #(sync/backend shared {:crdt counter})
             _        (sync/sync! {:device "a" :n 7} [(backend)] {:at t2 :crdt counter})
             {:keys [state report]} (sync/sync! {:device "b" :n 2} [(backend)] {:at t2 :crdt counter})]
         (is (= [] report))
         (is (= 7 (:n state)) "through the documents of a store")))))

(deftest kinds-of-state
  (let [opts    (assoc with-notes :kind "test/notes")
        doc     #(sync/document (note {:device %1} %1 "A note" t2) t2 %2)
        fetched [{:name "a.edn" :body (doc "a" opts)}
                 {:name "b.edn" :body (doc "b" {})}
                 {:name "c.edn" :body (doc "c" (assoc opts :kind "test/other"))}]]
    (is (= "test/notes" (:kind (sync/read-document (:body (first fetched)) opts))))
    (let [{:keys [state report]} (sync/reconcile {:device "me"} fetched t2 opts)]
      (is (= #{"a" "b"} (set (keys (:notes state)))) "a document without a kind is read by any device")
      (is (= [[:sync/other-kind "c.edn" "test/other"]] (map (juxt :id :name :kind) report)))
      (is (every? :message report)))
    (is (= 3 (count (:states (sync/read-documents fetched t2 with-notes))))
        "a device that names no kind reads every kind")
    #?(:clj
       (testing "a shared document of another kind is left as it is"
         (let [documents (atom {})
               s         (dissoc (store/memory documents) :list)
               theirs    (doc "c" (assoc opts :kind "test/other"))
               _         ((:put s) "notes.edn" theirs {})
               backend   #(sync/backend s (assoc opts :document "notes.edn"))
               r         (sync/sync! (note {:device "me"} "x" "Mine" t2) [(backend)] (assoc opts :at t2))]
           (is (= [:sync/other-kind] (map :id (:report r))))
           (is (= theirs (:body (@documents "notes.edn"))))
           (sync/sync! (:state r) [(backend)] (assoc opts :at t3 :cursors (:cursors r)))
           (is (= theirs (:body (@documents "notes.edn"))) "the next step too"))))))

(deftest versions-of-a-state
  (let [doc     #(sync/document (note {:device %1} %1 "A note" t2) t2 {:version %2})
        fetched [{:name "one.edn" :body (doc "one" 1)}
                 {:name "two.edn" :body (doc "two" 2)}
                 {:name "three.edn" :body (doc "three" 3)}]]
    (is (= 2 (:version (sync/read-document (:body (second fetched)) with-notes))))
    (is (= [:sync/newer-format :sync/newer-format]
           (map :id (:report (sync/read-documents fetched t2 with-notes))))
        "the default is version 1")
    (let [{:keys [states report]} (sync/read-documents fetched t2 (assoc with-notes :version 2))]
      (is (= ["one" "two"] (map :device states)) "a document of an older version is read")
      (is (= [[:sync/newer-format "three.edn" 3]] (map (juxt :id :name :version) report))))))

(def tagged-codec
  "A codec that writes documents with the extension .notes, as EDN after a
  line that names the format."
  {:extension ".notes"
   :encode    #(str "notes 1\n" (pr-str %))
   :decode    #(edn/read-string (second (str/split % #"\n" 2)))})

(deftest codecs
  (let [opts (assoc with-notes :codec tagged-codec)
        doc  (sync/document (phone-notes) t2 opts)]
    (is (str/starts-with? doc "notes 1\n{:version 1"))
    (is (= "phone" (:device (sync/read-document doc opts))))
    (is (= "phone.notes" (sync/document-name "phone" opts)))
    (is (= "phone.notes" (:name (sync/device-document {:device "phone"} t2 opts))))
    #?(:clj
       (let [documents (atom {})
             backend   #(sync/backend (store/memory documents) opts)
             _         ((:put (store/memory documents)) "laptop.edn" (sync/document {:device "laptop"} t2) {})
             phone     (sync/sync! (phone-notes) [(backend)] (assoc opts :at t2))
             laptop    (sync/sync! (laptop-notes) [(backend)] (assoc opts :at t3))]
         (is (= [] (:report phone)) "a document with another extension isn't a device's")
         (is (= #{"laptop.edn" "phone.notes" "laptop.notes"} (set (keys @documents))))
         (is (= #{"milk" "eggs" "bread"} (set (keys (:notes (:state laptop))))))
         (let [shared (atom {})]
           (sync/sync! {:device "car"} [(sync/backend (dissoc (store/memory shared) :list) opts)] (assoc opts :at t3))
           (is (= ["podcast.notes"] (keys @shared)) "the shared document takes the extension too"))))))

(deftest when-to-sync-next
  (let [at #inst "2026-01-01T00:00:00Z"
        s  (fn [n] (time/->inst (+ (inst-ms at) (* 1000 n))))]
    (is (= (s 900) (sync/next-sync {:at at} (s 1))) "idle: a quarter of an hour")
    (is (= (s 10) (sync/next-sync {:at at :changed? true} (s 1))) "soon after a change")
    (is (= (s 30) (sync/next-sync {:at at :failures 1} (s 1))) "backing off after a failure")
    (is (= (s 900) (sync/next-sync {:at at :failures 9} (s 1))) "and no further than the last wait")
    (is (= (s 900) (sync/next-sync {:at at :failures 1 :changed? true} (s 1) {:after-failure [900]})) "a failure counts before a change")
    (is (= (s 1000) (sync/next-sync {:at at} (s 1000))) "never before now")
    (is (= (s 5) (sync/next-sync {} (s 5))) "now, when there was no step yet")
    (is (= (s 60) (sync/next-sync {:at at} (s 1) {:idle 60})) "the cadence is the app's to set")))

#?(:clj
   (deftest a-folder-of-documents
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents) (assoc with-notes :app "test"))
           phone     (sync/sync! (phone-notes) [backend] (assoc with-notes :at t2))]
       (testing "the first device writes its document"
         (is (= #{"phone.edn"} (set (keys @documents))))
         (is (= (phone-notes) (:state phone)) "nothing to merge yet")
         (is (= [] (:report phone)))
         (is (nil? (failed phone)))
         (is (= "test" (:app (sync/read-document (:body (@documents "phone.edn")) with-notes)))))
       (let [laptop (sync/sync! (laptop-notes) [backend] (assoc with-notes :at t3))]
         (testing "the second reads the first's and writes its own, merged"
           (is (= "Buy six eggs" (text (:state laptop) "eggs")))
           (is (= #{"milk" "eggs" "bread"} (set (keys (:notes (:state laptop))))))
           (is (= #{"phone.edn" "laptop.edn"} (set (keys @documents))))
           (is (= (:state laptop) (:state (sync/read-document (:body (@documents "laptop.edn")) with-notes)))))
         (testing "the first catches up, and writes nothing more while nothing changes"
           (let [phone2  (sync/sync! (:state phone) [backend] (assoc with-notes :at t4 :cursors (:cursors phone)))
                 written (:version (@documents "phone.edn"))
                 phone3  (sync/sync! (:state phone2) [backend] (assoc with-notes :at t4 :cursors (:cursors phone2)))]
             (is (same? (:state laptop) (:state phone2)))
             (is (= "2" written) "written again with what it learned")
             (is (= written (:version (@documents "phone.edn"))))
             (is (= (:cursors phone2) (:cursors phone3)))
             (is (= {"laptop.edn" "1" "phone.edn" "2"} (:versions (get (:cursors phone3) "memory"))))))))))

#?(:clj
   (deftest a-step-at-the-time-of-the-clock
     (let [documents (atom {})
           before    (time/now)
           step      (sync/sync! (phone-notes) [(sync/backend (store/memory documents) with-notes)] with-notes)
           written   (:written-at (sync/read-document (:body (@documents "phone.edn")) with-notes))]
       (is (nil? (failed step)))
       (is (<= (inst-ms before) (inst-ms written)) "without :at, the step has the time of the clock"))))

#?(:clj
   (deftest extra-documents
     (let [documents (atom {})
           summary   (fn [state] {"summary.txt" (str (count (:notes state)) " notes")})
           opts      (assoc with-notes :extra-documents-fn summary)
           backend   (sync/backend (store/memory documents) opts)
           began     (sync/sync! (note {:device "phone"} "a" "A" t2) [backend] (assoc opts :at t2))
           same      (sync/sync! (:state began) [backend] (assoc opts :at t3 :cursors (:cursors began)))
           more      (sync/sync! (note (:state same) "b" "B" t3) [backend] (assoc opts :at t3 :cursors (:cursors same)))]
       (is (= [nil nil nil] (map failed [began same more])))
       (is (= "2 notes" (:body (@documents "summary.txt"))))
       (is (= "2" (:version (@documents "summary.txt"))) "written again only when it changed")
       (is (= {"phone.edn" "2"} (:versions (get (:cursors more) "memory")))
           "the cursor follows the documents of the devices, not the extra ones")
       (testing "with the extension of the documents of the devices"
         (let [clash (sync/backend (store/memory) (assoc with-notes :extra-documents-fn (constantly {"all.edn" "{}"})))]
           (is (str/includes? (str (failed (sync/sync! {:device "x"} [clash] (assoc with-notes :at t2))))
                              "extension")))))))

#?(:clj
   (deftest retiring-and-sharing-a-device
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents) with-notes)
           phone     (sync/sync! (phone-notes) [backend] (assoc with-notes :at t2))
           laptop    (sync/sync! (laptop-notes) [backend] (assoc with-notes :at t3))]
       (testing "a device that finds its own document changed under it"
         ((:put (store/memory documents)) "phone.edn" (sync/document {:device "phone"} t3) {})
         (let [{:keys [report]} (sync/sync! (:state phone) [backend] (assoc with-notes :at t4 :cursors (:cursors phone)))]
           (is (= [:sync/shared-device] (map :id report)))))
       (testing "a retired device's document is deleted, and it writes no more"
         (let [laptop2 (sync/sync! (crdt/retire (:state laptop) "phone" t4) [backend] (assoc with-notes :at t4 :cursors (:cursors laptop)))
               phone2  (sync/sync! (:state phone) [backend] (assoc with-notes :at t4 :cursors (:cursors phone)))]
           (is (= ["laptop.edn"] (keys @documents)))
           (is (nil? (failed laptop2)))
           (is (crdt/retired? (:state phone2) "phone") "it learns of its retirement")
           (is (= [:sync/retired-device] (map :id (:report phone2))))
           (is (= ["laptop.edn"] (keys @documents))))))))

#?(:clj
   (deftest a-state-that-the-others-cannot-merge
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents) with-notes)
           phone     (sync/sync! (phone-notes) [backend] (assoc with-notes :at t2))
           written   (:version (@documents "phone.edn"))
           _         (sync/sync! (laptop-notes) [backend] (assoc with-notes :at t3))
           broken    (assoc-in (:state phone) [:notes "x"] {:text "No stamp"})
           {:keys [state report] :as step} (sync/sync! broken [backend] (assoc with-notes :at t4 :cursors (:cursors phone)))]
       (is (= [[:sync/malformed-state :error]] (map (juxt :id :level) report)))
       (is (str/includes? (:error (first report)) "not stamped"))
       (is (nil? (failed step)))
       (is (= written (:version (@documents "phone.edn"))) "nothing written")
       (is (contains? (:devices state) "laptop") "but the laptop's state pulled in"))))

#?(:clj
   (deftest compaction-through-a-folder
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents) with-notes)
           far       (time/->inst (+ (inst-ms t4) (* 31 24 60 60 1000)))
           phone     (crdt/write (phone-notes) [:notes "milk"] #(assoc % :removed? true) t2)
           p1        (sync/sync! phone [backend] (assoc with-notes :at t3))
           _         (sync/sync! (laptop-notes) [backend] (assoc with-notes :at t3))
           p2        (sync/sync! (:state p1) [backend] (assoc with-notes :at far :cursors (:cursors p1)))
           kept?     #(contains? (:notes (:state %)) "milk")]
       (is (kept? p1) "the tombstone travels")
       (is (= {"phone" t2} (get-in (sync/read-document (:body (@documents "laptop.edn")) with-notes)
                                   [:state :devices "laptop" :seen]))
           "the laptop acknowledges what it merged from the phone")
       (is (not (kept? p2)) "and the tombstone goes once the laptop has seen it")
       (is (kept? (sync/sync! (:state p1) [backend] (assoc with-notes :at far :cursors (:cursors p1) :compact false)))
           "unless compaction is turned off")
       (is (kept? (sync/sync! (:state p1) [backend] (assoc with-notes :at t4 :cursors (:cursors p1))))
           "or the tombstone is too fresh")
       (testing "a device alone forgets nothing, however often it syncs"
         (let [alone (sync/backend (store/memory) with-notes)
               s1    (sync/sync! phone [alone] (assoc with-notes :at far :compact {:keep-for 0}))
               s2    (sync/sync! (:state s1) [alone] (assoc with-notes :at far :cursors (:cursors s1) :compact {:keep-for 0}))]
           (is (kept? s2))))
       (testing "a partial pull is merged but acknowledges nothing"
         (let [service {:name "service"
                        :pull (fn [_ _ _] {:states [(laptop-notes)] :cursor {} :partial? true})
                        :push (fn [_ _ _] {:cursor {}})}
               r       (sync/sync! (phone-notes) [service] (assoc with-notes :at t3))]
           (is (= "Buy six eggs" (text (:state r) "eggs")))
           (is (nil? (get-in (:state r) [:devices "phone" :seen "laptop"]))))))))

#?(:clj
   (deftest directions
     (let [documents (atom {})
           store     (store/memory documents)]
       (sync/sync! (laptop-notes) [(sync/backend store with-notes)] (assoc with-notes :at t3))
       (let [reader (sync/sync! (phone-notes) [(sync/backend store (assoc with-notes :direction :pull))] (assoc with-notes :at t4))]
         (is (= "Buy six eggs" (text (:state reader) "eggs")) "a pull-only backend is read")
         (is (= ["laptop.edn"] (keys @documents)) "and not written to"))
       (let [writer (sync/sync! (phone-notes) [(sync/backend store (assoc with-notes :direction :push))] (assoc with-notes :at t4))]
         (is (= (phone-notes) (:state writer)) "a push-only backend is not read")
         (is (= ["laptop.edn" "phone.edn"] (sort (keys @documents))) "but written to")))))

#?(:clj
   (deftest a-store-that-cannot-write
     (let [documents (atom {})
           store     (store/memory documents)
           read-only (dissoc store :put)]
       (sync/sync! (laptop-notes) [(sync/backend store with-notes)] (assoc with-notes :at t3))
       (let [reader (sync/sync! {:device "tablet"} [(sync/backend read-only with-notes)] (assoc with-notes :at t4))]
         (is (nil? (failed reader)))
         (is (= "Buy six eggs" (text (:state reader) "eggs")) "a backend over it only pulls")
         (is (= ["laptop.edn"] (keys @documents))))
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only be pulled"
                             (sync/backend read-only (assoc with-notes :direction :both)))))))

#?(:clj
   (deftest one-shared-document
     (let [documents (atom {})
           shared    (dissoc (store/memory documents) :list)
           opts      (assoc with-notes :document "state.edn")
           backend   (sync/backend shared opts)
           phone     (sync/sync! (phone-notes) [backend] (assoc with-notes :at t2))
           laptop    (sync/sync! (laptop-notes) [backend] (assoc with-notes :at t3))
           phone2    (sync/sync! (:state phone) [backend] (assoc with-notes :at t4 :cursors (:cursors phone)))]
       (is (= ["state.edn"] (keys @documents)))
       (is (same? (:state laptop) (:state phone2)))
       (is (= {:version "2" :hash (hash (:state laptop))} (get (:cursors laptop) "memory")))
       (is (thrown? Exception (sync/backend shared (assoc with-notes :strategy :per-device))) "one document per device needs a listing")
       (testing "a document that changes between the fetch and the put is merged and put again"
         (let [car    (note {:device "car"} "milk" "Buy oat milk" t4)
               puts   (atom 0)
               sneaky (update shared :put (fn [put]
                                            (fn [name body opts]
                                              (when (= 1 (swap! puts inc))
                                                (put name (sync/document car t4) {}))
                                              (put name body opts))))
               {:keys [state report]} (sync/sync! (note (:state phone2) "tea" "Buy tea" t4)
                                                  [(sync/backend sneaky opts)]
                                                  (assoc with-notes :at t4 :cursors (:cursors phone2)))]
           (is (= 2 @puts) "one refused, one after merging")
           (is (= [] report))
           (is (= "Buy oat milk" (text state "milk")) "the car's change came along")
           (is (= "Buy tea" (text state "tea")))
           (is (same? state (:state (sync/read-document (:body (@documents "state.edn")) with-notes))))))
       (testing "a document that will not stop changing is given up on, and reported"
         (let [busy (assoc shared :put (fn [_ _ _] {:conflict? true}))
               {:keys [state report cursors]} (sync/sync! (note (:state phone2) "jam" "Buy jam" t4)
                                                          [(sync/backend busy opts)]
                                                          (assoc with-notes :at t4))]
           (is (= [:sync/conflict] (map :id report)))
           (is (= "Buy oat milk" (text state "milk")) "what was fetched along the way is kept")
           (is (nil? (:hash (get cursors "memory"))) "nothing was written")))
       (testing "a retired device writes nothing"
         (let [documents (atom {})
               backend   (sync/backend (dissoc (store/memory documents) :list) opts)
               phone     (sync/sync! (phone-notes) [backend] (assoc with-notes :at t2))
               _         (sync/sync! (crdt/retire (laptop-notes) "phone" t3) [backend] (assoc with-notes :at t3))
               written   (:version (@documents "state.edn"))
               phone2    (sync/sync! (note (:state phone) "tea" "Buy tea" t4) [backend] (assoc with-notes :at t4 :cursors (:cursors phone)))]
           (is (crdt/retired? (:state phone2) "phone") "it learns of its retirement")
           (is (= [:sync/retired-device] (map :id (:report phone2))))
           (is (= written (:version (@documents "state.edn"))) "and the shared document stays as it was"))))))

#?(:clj
   (deftest several-backends-one-failing
     (let [good   (sync/backend (store/memory) (assoc with-notes :name "good"))
           broken {:name "broken"
                   :pull (fn [_ _ _] (throw (ex-info "connection refused" {})))
                   :push (fn [_ _ _] {:cursor {}})}
           result (sync/sync! (phone-notes) [good broken] (assoc with-notes :at t2 :cursors {"broken" {:token 1}}))]
       (is (= {"broken" "connection refused"} (failed result)))
       (is (= {:token 1} (get (:cursors result) "broken")) "a backend that fails keeps its cursor")
       (is (contains? (:cursors result) "good"))
       (testing "a push that fails keeps the cursor of its pull, and what it pulled"
         (let [half {:name "half"
                     :pull (fn [_ _ _] {:states [(laptop-notes)] :cursor {:pulled true} :report []})
                     :push (fn [_ _ _] (throw (ex-info "disk full" {})))}
               r    (sync/sync! (phone-notes) [half] (assoc with-notes :at t2))]
           (is (= {"half" "disk full"} (failed r)))
           (is (= {:pulled true} (get (:cursors r) "half")))
           (is (= "Buy six eggs" (text (:state r) "eggs"))))))))

#?(:clj
   (deftest backends-by-name
     (is (thrown-with-msg? Exception #"distinct names"
                           (sync/sync! (phone-notes)
                                       [(sync/backend (store/memory) with-notes) (sync/backend (store/memory) with-notes)]
                                       (assoc with-notes :at t2)))
         "two backends of one name would share a cursor")))

#?(:clj
   (deftest documents-a-store-lost-or-cannot-version
     (testing "a listing without versions still leads to the documents"
       (let [mem (store/memory)
             b   (sync/backend (update mem :list (fn [list] #(mapv (fn [d] (assoc d :version nil)) (list)))) with-notes)]
         (sync/sync! (laptop-notes) [b] (assoc with-notes :at t2))
         (is (= "Buy bread" (text (:state (sync/sync! {:device "tablet"} [b] (assoc with-notes :at t3))) "bread")))))
     (testing "this device's own document, gone from the store, is written again"
       (let [documents (atom {})
             b         (sync/backend (store/memory documents) with-notes)
             r         (sync/sync! (phone-notes) [b] (assoc with-notes :at t2))]
         (reset! documents {})
         (sync/sync! (:state r) [b] (assoc with-notes :at t3 :cursors (:cursors r)))
         (is (= ["phone.edn"] (keys @documents)))))
     (testing "and so is the shared document"
       (let [documents (atom {})
             b         (sync/backend (dissoc (store/memory documents) :list) (assoc with-notes :document "state.edn"))
             r         (sync/sync! (phone-notes) [b] (assoc with-notes :at t2))]
         (reset! documents {})
         (sync/sync! (:state r) [b] (assoc with-notes :at t3 :cursors (:cursors r)))
         (is (= ["state.edn"] (keys @documents)))))
     (testing "a shared document of a newer format is left as it is"
       (let [documents (atom {})
             s         (dissoc (store/memory documents) :list)
             b         #(sync/backend s (assoc with-notes :document "state.edn"))
             newer     (pr-str {:version 99 :device "future" :written-at t2 :state {}})
             _         ((:put s) "state.edn" newer {})
             r         (sync/sync! (phone-notes) [(b)] (assoc with-notes :at t2))]
         (is (= [:sync/newer-format] (map :id (:report r))))
         (is (= newer (:body (@documents "state.edn"))))
         (sync/sync! (:state r) [(b)] (assoc with-notes :at t3 :cursors (:cursors r)))
         (is (= newer (:body (@documents "state.edn"))) "the next step too")))))

#?(:clj
   (deftest instants-of-any-kind
     (let [now (java.time.Instant/parse "2026-01-01T02:00:00Z")
           s   (note {:device "phone"} "milk" "Buy milk" now)]
       (is (= s (:state (sync/read-document (sync/document s now) with-notes)))
           "a java.time.Instant is kept as a date, which a document prints as #inst"))))

#?(:cljs
   (deftest syncing-with-promises
     (async done
       (let [documents (atom {})
             promising (update-vals (store/memory documents)
                                    #(if (fn? %) (fn [& args] (js/Promise.resolve (apply % args))) %))
             backend   (sync/backend promising with-notes)]
         (-> (sync/sync! (phone-notes) [backend] (assoc with-notes :at t2))
             (.then (fn [phone]
                      (is (= ["phone.edn"] (keys @documents)))
                      (is (nil? (failed phone)))
                      (sync/sync! (laptop-notes) [backend] (assoc with-notes :at t3))))
             (.then (fn [laptop]
                      (is (= "Buy six eggs" (text (:state laptop) "eggs")))
                      (is (= #{"laptop.edn" "phone.edn"} (set (keys @documents))))
                      (is (= #{"milk" "eggs" "bread"} (set (keys (:notes (:state laptop))))))
                      (sync/sync! (:state laptop) [{:name "broken" :pull (fn [_ _ _] (js/Promise.reject (js/Error. "offline"))) :push (fn [_ _ _] {:cursor {}})}] (assoc with-notes :at t4))))
             (.then (fn [r] (is (= {"broken" "offline"} (failed r)))))
             (.catch (fn [e] (is false (str "the promise was rejected: " e))))
             (.finally done))))))
