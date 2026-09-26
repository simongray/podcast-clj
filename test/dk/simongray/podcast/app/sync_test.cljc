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
            [dk.simongray.podcast.app.sync.store :as store]))

(def t0 #inst "2026-01-01T00:00:00Z")
(def t1 #inst "2026-01-01T01:00:00Z")
(def t2 #inst "2026-01-01T02:00:00Z")
(def t3 #inst "2026-01-01T03:00:00Z")
(def t4 #inst "2026-01-01T04:00:00Z")

(def a ["feed-a" "a1"])

(defn- phone-state
  []
  (-> (player/state "phone")
      (player/identify {:name "Phone"} t0)
      (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
      (player/progress a 100 3600 t1)))

(defn- laptop-state
  []
  (-> (player/state "laptop")
      (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t1)
      (player/progress a 900 3600 t2)))

#?(:clj
   (defn- same?
     [x y]
     (= (dissoc x :device) (dissoc y :device))))

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
           (is (= state (:state (sync/read-document (:body (@documents "state.edn"))))))))
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
