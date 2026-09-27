(ns dk.simongray.podcast.app.sync-test
  "The sync engine: documents in their envelope and what reading them
  reports, the memory store, two devices through a folder of documents and
  through one shared document, and a step through several backends with
  one failing."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.test-support :refer [t2 t3 t4 a phone-state laptop-state #?@(:clj [same?])]]
            [dk.simongray.podcast.time :as time]))

(deftest documents
  (let [s   (phone-state)
        doc (sync/document s t2 {:app "test"})]
    (is (str/starts-with? doc "{:version 1"))
    (is (= {:version 1 :device "phone" :written-at t2 :app "test" :state s} (sync/read-document doc)))
    (is (= s (:state (sync/read-document (sync/document s t2)))))
    (testing "what is not a document"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"EDN" (sync/read-document "{:version 1 :state")))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"sync document" (sync/read-document "[1 2 3]")))
      (is (thrown? #?(:clj Exception :cljs js/Error) (sync/read-document nil))))))

(deftest reading-documents
  (let [fetched [{:name "phone.edn" :body (sync/document (phone-state) t2)}
                 {:name "laptop.edn" :body (sync/document (laptop-state) t2)}
                 {:name "junk.edn" :body "<html>"}
                 {:name "future.edn" :body (pr-str {:version 99 :device "x" :written-at t2 :state {}})}
                 {:name "ahead.edn" :body (sync/document (player/state "car") t4)}]
        {:keys [states report]} (sync/read-documents fetched t2)]
    (is (= 3 (count states)))
    (is (= [:sync/unreadable-document :sync/newer-format :sync/clock-skew] (map :id report)))
    (is (every? :message report) "every diagnostic is described")
    (is (= "junk.edn" (:name (first report))))
    (is (= {:name "ahead.edn" :device "car" :seconds 7200} (select-keys (nth report 2) [:name :device :seconds])))
    (testing "reconciled into a state"
      (let [{:keys [state report]} (sync/reconcile (player/state "tablet") fetched t2)]
        (is (= 3 (count report)))
        (is (= 900 (:position (player/episode state a))))
        (is (= #{"feed-a" "feed-b"} (set (keys (player/subscriptions state)))))
        (is (= "tablet" (:device state)))))))

(deftest the-memory-store
  (let [{:keys [fetch put list delete]} (store/memory)]
    (is (nil? (fetch "a.edn")))
    (is (= {:version "1"} (put "a.edn" "one" {})))
    (is (= {:body "one" :version "1"} (fetch "a.edn")))
    (is (= {:version "2"} (put "a.edn" "two" {:if-version "1"})))
    (is (= {:conflict? true} (put "a.edn" "three" {:if-version "1"})) "a stale version is refused")
    (is (= {:conflict? true} (put "b.edn" "x" {:if-version "1"})) "so is a version for a document that is not there")
    (is (= {:version "1"} (put "b.edn" "x" {:if-version nil})) "nil is the version of no document yet")
    (is (= {:conflict? true} (put "b.edn" "y" {:if-version nil})))
    (is (= [{:name "a.edn" :version "2"} {:name "b.edn" :version "1"}] (list)))
    (delete "b.edn")
    (is (= ["a.edn"] (map :name (list))))))

(deftest when-to-sync-next
  (let [at #inst "2026-01-01T00:00:00Z"
        s  (fn [n] (time/->inst (+ (inst-ms at) (* 1000 n))))]
    (is (= (s 900) (sync/next-at {:at at} (s 1))) "idle: a quarter of an hour")
    (is (= (s 10) (sync/next-at {:at at :changed? true} (s 1))) "soon after a change")
    (is (= (s 30) (sync/next-at {:at at :failures 1} (s 1))) "backing off after a failure")
    (is (= (s 900) (sync/next-at {:at at :failures 9} (s 1))) "and no further than the last wait")
    (is (= (s 900) (sync/next-at {:at at :failures 1 :changed? true} (s 1) {:after-failure [900]})) "a failure counts before a change")
    (is (= (s 1000) (sync/next-at {:at at} (s 1000))) "never before now")
    (is (= (s 5) (sync/next-at {} (s 5))) "now, when there was no step yet")
    (is (= (s 60) (sync/next-at {:at at} (s 1) {:idle 60})) "the cadence is the app's to set")))

(defn- refusing
  "A store whose every call answers as a WebDAV server refusing the
  credentials would."
  []
  (let [refuse (fn [& _] (throw (ex-info "The server answered 401 to list" {:type :webdav/failed :status 401})))]
    {:name "refusing" :fetch refuse :put refuse :list refuse :delete refuse}))

(defn- unreachable
  "A store that cannot be listed."
  []
  {:name "gone" :fetch identity :put identity :delete identity
   :list (fn [] (throw (ex-info "connection refused" {})))})

(defn- forgetful
  "A memory store that ignores a stale version."
  []
  (update (store/memory) :put (fn [put] (fn [name body _] (put name body {})))))

#?(:clj
   (deftest checking-a-store
     (testing "a store that does everything"
       (let [{:keys [ok? checks]} (store/check! (store/memory))]
         (is ok?)
         (is (= [:store/reachable :store/authenticated :store/listing :store/put :store/conflicts :store/fetch :store/delete]
                (map :id checks)))
         (is (every? :ok? checks))
         (is (every? :message checks))
         (is (str/includes? (:message (nth checks 2)) "0 documents"))))
     (testing "the probe is gone afterwards, and the documents were never touched"
       (let [documents (atom {})
             s         (store/memory documents)]
         ((:put s) "phone.edn" "{}" {})
         (is (:ok? (store/check! s)))
         (is (= ["phone.edn"] (keys @documents)))))
     (testing "a store that ignores stale versions is usable, with a remark"
       (let [{:keys [ok? checks]} (store/check! (forgetful))]
         (is ok? (pr-str checks))
         (is (= [false] (map :ok? (filter #(= :store/conflicts (:id %)) checks))))))
     (testing "what cannot be reached, and what refuses the credentials"
       (let [{:keys [ok? checks]} (store/check! (unreachable))]
         (is (not ok?))
         (is (= [[:store/reachable false]] (map (juxt :id :ok?) checks)))
         (is (str/includes? (:message (first checks)) "connection refused")))
       (let [{:keys [ok? checks]} (store/check! (refusing))]
         (is (not ok?))
         (is (= [[:store/reachable true] [:store/authenticated false]] (map (juxt :id :ok?) checks)))))))

#?(:cljs
   (deftest checking-a-store-with-promises
     (async done
       (-> (store/check! (store/memory))
           (.then (fn [{:keys [ok? checks]}]
                    (is ok? (pr-str checks))
                    (is (= 7 (count checks)))
                    (store/check! (forgetful))))
           (.then (fn [{:keys [ok? checks]}]
                    (is ok? (pr-str checks))
                    (is (= [false] (map :ok? (filter #(= :store/conflicts (:id %)) checks))))
                    (store/check! (unreachable))))
           (.then (fn [{:keys [ok? checks]}]
                    (is (not ok?))
                    (is (= [[:store/reachable false]] (map (juxt :id :ok?) checks)))
                    (store/check! (refusing))))
           (.then (fn [{:keys [ok? checks]}]
                    (is (not ok?))
                    (is (= [[:store/reachable true] [:store/authenticated false]] (map (juxt :id :ok?) checks)))))
           (.catch (fn [e] (is false (str "rejected: " e))))
           (.finally done)))))

#?(:clj
   (deftest a-folder-of-documents
     (let [documents (atom {})
           backend   (store/backend (store/memory documents) {:opml? true :app "test"})
           phone     (sync/sync! (phone-state) [backend] t2)]
       (testing "the first device writes its document and the subscription list"
         (is (= #{"phone.edn" "subscriptions.opml"} (set (keys @documents))))
         (is (= (phone-state) (:state phone)) "nothing to merge yet")
         (is (= [] (:report phone)))
         (is (nil? (:failed phone)))
         (is (= "test" (:app (sync/read-document (:body (@documents "phone.edn"))))))
         (is (str/includes? (:body (@documents "subscriptions.opml")) "https://a/rss")))
       (let [laptop (sync/sync! (laptop-state) [backend] t3)]
         (testing "the second reads the first's and writes its own, merged"
           (is (= 900 (:position (player/episode (:state laptop) a))))
           (is (= #{"feed-a" "feed-b"} (set (keys (player/subscriptions (:state laptop))))))
           (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"} (set (keys @documents))))
           (is (= (:state laptop) (:state (sync/read-document (:body (@documents "laptop.edn"))))))
           (is (str/includes? (:body (@documents "subscriptions.opml")) "https://b/rss")))
         (testing "the first catches up, and writes nothing more while nothing changes"
           (let [phone2  (sync/sync! (:state phone) [backend] t4 {:ctx (:ctx phone)})
                 written (:version (@documents "phone.edn"))
                 phone3  (sync/sync! (:state phone2) [backend] t4 {:ctx (:ctx phone2)})]
             (is (same? (:state laptop) (:state phone2)))
             (is (= "2" written) "written again with what it learned")
             (is (= written (:version (@documents "phone.edn"))))
             (is (= (:ctx phone2) (:ctx phone3)))
             (is (= {"laptop.edn" "1" "phone.edn" "2"} (:versions (get (:ctx phone3) "memory")))
                 "the cursor follows the documents, not the subscription list")))))))

#?(:clj
   (deftest retiring-and-sharing-a-device
     (let [documents (atom {})
           backend   (store/backend (store/memory documents))
           phone     (sync/sync! (phone-state) [backend] t2)
           laptop    (sync/sync! (laptop-state) [backend] t3)]
       (testing "a device that finds its own document changed under it"
         ((:put (store/memory documents)) "phone.edn" (sync/document (player/state "phone") t3) {})
         (let [{:keys [report]} (sync/sync! (:state phone) [backend] t4 {:ctx (:ctx phone)})]
           (is (= [:sync/shared-device] (map :id report)))))
       (testing "a retired device's document is deleted, and it writes no more"
         (let [laptop2 (sync/sync! (player/retire (:state laptop) "phone" t4) [backend] t4 {:ctx (:ctx laptop)})
               phone2  (sync/sync! (:state phone) [backend] t4 {:ctx (:ctx phone)})]
           (is (= ["laptop.edn"] (keys @documents)))
           (is (nil? (:failed laptop2)))
           (is (player/retired? (:state phone2) "phone") "it learns of its retirement")
           (is (= [:sync/retired-device] (map :id (:report phone2))))
           (is (= ["laptop.edn"] (keys @documents))))))))

#?(:clj
   (deftest compaction-through-a-folder
     (let [documents (atom {})
           backend   (store/backend (store/memory documents))
           far       (time/->inst (+ (inst-ms t4) (* 31 24 60 60 1000)))
           phone     (player/unsubscribe (phone-state) "feed-a" t2)
           p1        (sync/sync! phone [backend] t3)
           _         (sync/sync! (laptop-state) [backend] t3)
           p2        (sync/sync! (:state p1) [backend] far {:ctx (:ctx p1)})]
       (is (contains? (:subscriptions (:state p1)) "feed-a") "the tombstone travels")
       (is (= {"phone" t2} (get-in (sync/read-document (:body (@documents "laptop.edn"))) [:state :devices "laptop" :seen]))
           "the laptop acknowledges what it merged from the phone")
       (is (not (contains? (:subscriptions (:state p2)) "feed-a")) "and the tombstone goes once the laptop has seen it")
       (is (contains? (:subscriptions (:state (sync/sync! (:state p1) [backend] far {:ctx (:ctx p1) :compact false}))) "feed-a")
           "unless compaction is turned off")
       (is (contains? (:subscriptions (:state (sync/sync! (:state p1) [backend] t4 {:ctx (:ctx p1)}))) "feed-a")
           "or the tombstone is too fresh")
       (testing "a device alone forgets nothing, however often it syncs"
         (let [alone (store/backend (store/memory))
               s1    (sync/sync! phone [alone] far {:compact {:keep-for 0}})
               s2    (sync/sync! (:state s1) [alone] far {:ctx (:ctx s1) :compact {:keep-for 0}})]
           (is (contains? (:subscriptions (:state s2)) "feed-a"))))
       (testing "a partial pull is merged but acknowledges nothing"
         (let [service {:name "service"
                        :pull (fn [_ _ _] {:states [(laptop-state)] :ctx {} :partial? true})
                        :push (fn [_ _ _] {:ctx {}})}
               r       (sync/sync! (phone-state) [service] t3)]
           (is (= 900 (:position (player/episode (:state r) a))))
           (is (nil? (get-in (:state r) [:devices "phone" :seen "laptop"]))))))))

#?(:clj
   (deftest directions
     (let [documents (atom {})
           store     (store/memory documents)]
       (sync/sync! (laptop-state) [(store/backend store)] t3)
       (let [reader (sync/sync! (phone-state) [(store/backend store {:direction :pull})] t4)]
         (is (= 900 (:position (player/episode (:state reader) a))) "a pull-only backend is read")
         (is (= ["laptop.edn"] (keys @documents)) "and not written to"))
       (let [writer (sync/sync! (phone-state) [(store/backend store {:direction :push})] t4)]
         (is (= (phone-state) (:state writer)) "a push-only backend is not read")
         (is (= ["laptop.edn" "phone.edn"] (sort (keys @documents))) "but written to")))))

#?(:clj
   (deftest one-shared-document
     (let [documents (atom {})
           shared    (dissoc (store/memory documents) :list)
           backend   (store/backend shared {:document "state.edn"})
           phone     (sync/sync! (phone-state) [backend] t2)
           laptop    (sync/sync! (laptop-state) [backend] t3)
           phone2    (sync/sync! (:state phone) [backend] t4 {:ctx (:ctx phone)})]
       (is (= ["state.edn"] (keys @documents)))
       (is (same? (:state laptop) (:state phone2)))
       (is (= {:version "2" :hash (hash (:state laptop))} (get (:ctx laptop) "memory")))
       (is (thrown? Exception (store/backend shared {:strategy :per-device})) "one document per device needs a listing")
       (testing "a document that changes between the fetch and the put is merged and put again"
         (let [car    (player/progress (player/state "car") a 1500 3600 t4)
               puts   (atom 0)
               sneaky (update shared :put (fn [put]
                                            (fn [name body opts]
                                              (when (= 1 (swap! puts inc))
                                                (put name (sync/document car t4) {}))
                                              (put name body opts))))
               {:keys [state report]} (sync/sync! (player/prefer (:state phone2) {:rate 2.0} t4)
                                                  [(store/backend sneaky {:document "state.edn"})]
                                                  t4
                                                  {:ctx (:ctx phone2)})]
           (is (= 2 @puts) "one refused, one after merging")
           (is (= [] report))
           (is (= 1500 (:position (player/episode state a))) "the car's progress came along")
           (is (= 2.0 (:rate (player/preferences state))))
           (is (same? state (:state (sync/read-document (:body (@documents "state.edn"))))))))
       (testing "a document that will not stop changing is given up on, and reported"
         (let [busy (assoc shared :put (fn [_ _ _] {:conflict? true}))
               {:keys [state report ctx]} (sync/sync! (player/prefer (:state phone2) {:rate 3.0} t4)
                                                      [(store/backend busy {:document "state.edn"})]
                                                      t4)]
           (is (= [:sync/conflict] (map :id report)))
           (is (= 1500 (:position (player/episode state a))) "what was fetched along the way is kept")
           (is (nil? (:hash (get ctx "memory"))) "nothing was written"))))))

#?(:clj
   (deftest several-backends-one-failing
     (let [good   (store/backend (store/memory) {:name "good"})
           broken {:name "broken"
                   :pull (fn [_ _ _] (throw (ex-info "connection refused" {})))
                   :push (fn [_ _ _] {:ctx {}})}
           result (sync/sync! (phone-state) [good broken] t2 {:ctx {"broken" {:token 1}}})]
       (is (= {"broken" "connection refused"} (:failed result)))
       (is (= {:token 1} (get (:ctx result) "broken")) "a backend that fails keeps its cursor")
       (is (contains? (:ctx result) "good"))
       (testing "a push that fails keeps the cursor of its pull, and what it pulled"
         (let [half {:name "half"
                     :pull (fn [_ _ _] {:states [(laptop-state)] :ctx {:pulled true} :report []})
                     :push (fn [_ _ _] (throw (ex-info "disk full" {})))}
               r    (sync/sync! (phone-state) [half] t2)]
           (is (= {"half" "disk full"} (:failed r)))
           (is (= {:pulled true} (get (:ctx r) "half")))
           (is (= 900 (:position (player/episode (:state r) a)))))))))

#?(:cljs
   (deftest syncing-with-promises
     (async done
       (let [documents (atom {})
             promising (update-vals (store/memory documents)
                                    #(if (fn? %) (fn [& args] (js/Promise.resolve (apply % args))) %))
             backend   (store/backend promising)]
         (-> (sync/sync! (phone-state) [backend] t2)
             (.then (fn [phone]
                      (is (= ["phone.edn"] (keys @documents)))
                      (is (nil? (:failed phone)))
                      (sync/sync! (laptop-state) [backend] t3)))
             (.then (fn [laptop]
                      (is (= 900 (:position (player/episode (:state laptop) a))))
                      (is (= #{"laptop.edn" "phone.edn"} (set (keys @documents))))
                      (is (= #{"feed-a" "feed-b"} (set (keys (player/subscriptions (:state laptop))))))
                      (sync/sync! (:state laptop) [{:name "broken" :pull (fn [_ _ _] (js/Promise.reject (js/Error. "offline"))) :push (fn [_ _ _] {:ctx {}})}] t4)))
             (.then (fn [r] (is (= {"broken" "offline"} (:failed r)))))
             (.catch (fn [e] (is false (str "the promise was rejected: " e))))
             (.finally done))))))
