(ns dk.simongray.podcast.store-test
  "The store contract: the memory store, fetching every document at once,
  two puts on one version, and the check of a store, of one that does
  everything and of those that can't list, delete, refuse a stale version
  or be reached."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.string :as str])
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.store :as store]
            [dk.simongray.podcast.util.bytes :as bytes]))

(deftest the-memory-store
  (let [{:keys [fetch put list delete]} (store/memory)]
    (is (nil? (fetch "a.edn" {})))
    (is (= {:version "1"} (put "a.edn" "one" {})))
    (is (= {:body "one" :version "1"} (fetch "a.edn" {})))
    (testing "a body as text or as bytes, whichever was put"
      (is (bytes/same? (bytes/encode "one")
                       (:body (fetch "a.edn" {:as :bytes}))))
      (put "c.ciff" (bytes/of [0 255 1]) {})
      (is (bytes/same? (bytes/of [0 255 1])
                       (:body (fetch "c.ciff" {:as :bytes}))))
      (delete "c.ciff"))
    (is (= {:version "2"} (put "a.edn" "two" {:if-version "1"})))
    (is (= {:conflict? true} (put "a.edn" "three" {:if-version "1"})) "a stale version is refused")
    (is (= {:conflict? true} (put "b.edn" "x" {:if-version "1"})) "so is a version for a document that is not there")
    (is (= {:version "1"} (put "b.edn" "x" {:if-version nil})) "nil is the version of no document yet")
    (is (= {:conflict? true} (put "b.edn" "y" {:if-version nil})))
    (is (= [{:name "a.edn" :version "2"} {:name "b.edn" :version "1"}] (list)))
    (delete "b.edn")
    (is (= ["a.edn"] (map :name (list))))))

#?(:clj
   (deftest fetching-every-document-at-once
     (let [s        (store/memory)
           started  (java.util.concurrent.CountDownLatch. 2)
           together (atom [])
           waiting  (update s :fetch
                            (fn [fetch]
                              (fn [name opts]
                                (.countDown started)
                                (swap! together conj
                                       (.await started 5 java.util.concurrent.TimeUnit/SECONDS))
                                (fetch name opts))))]
       ((:put s) "a.edn" "one" {})
       ((:put s) "b.edn" "two" {})
       ((:put s) "summary.txt" "two notes" {})
       (is (= [{:name "a.edn" :body "one" :version "1"}
               {:name "b.edn" :body "two" :version "1"}]
              (store/fetch-all! waiting {:except #{"summary.txt"}})))
       (is (= [true true] @together) "each fetch began before either ended"))))

#?(:clj
   (deftest two-puts-on-one-version
     (let [documents (atom {})
           {:keys [put]} (store/memory documents)
           winners   (for [_ (range 200)]
                       (do (reset! documents {"state.edn" {:body "x" :version "1"}})
                           (let [latch (java.util.concurrent.CountDownLatch. 1)
                                 race  #(future (.await latch) (put "state.edn" % {:if-version "1"}))
                                 puts  [(race "a") (race "b")]]
                             (.countDown latch)
                             (count (filter (comp :version deref) puts)))))]
       (is (every? #(= 1 %) winners) "one of the two, never both"))))

(defn- refusing
  "A store whose every call answers as a WebDAV server refusing the
  credentials would."
  []
  (let [refuse (fn [& _] (throw (ex-info "The server answered 401 to list" {:type :webdav/failed :status 401})))]
    {:name "refusing" :fetch refuse :put refuse :list refuse :delete refuse}))

(defn- unreachable
  "A store that cannot be listed."
  []
  {:name "gone" :fetch (constantly nil) :put identity :delete identity
   :list (fn [] (throw (ex-info "connection refused" {})))})

(defn- forgetful
  "A memory store that ignores a stale version."
  []
  (update (store/memory) :put (fn [put] (fn [name body _] (put name body {})))))

#?(:clj
   (defn- careless
     "A memory store that takes an :if-version of nil for none, and so writes
     over a document that a put expects not to be there."
     []
     (update (store/memory) :put (fn [put] (fn [name body opts] (put name body (cond-> opts (nil? (:if-version opts)) (dissoc :if-version))))))))

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
     (testing "a store that cannot list is tried for one shared document"
       (let [{:keys [ok? checks]} (store/check! (dissoc (store/memory) :list))]
         (is ok? (pr-str checks))
         (is (= [:store/reachable :store/authenticated :store/listing :store/put :store/conflicts :store/fetch :store/delete]
                (map :id checks)))
         (is (= [false] (map :ok? (filter #(= :store/listing (:id %)) checks)))))
       (let [{:keys [ok? checks]} (store/check! (dissoc (forgetful) :list))]
         (is (not ok?) "one shared document needs a stale version refused")
         (is (= [false] (map :ok? (filter #(= :store/conflicts (:id %)) checks)))))
       (let [{:keys [ok? checks]} (store/check! (dissoc (careless) :list))]
         (is (not ok?) "and a put refused that expects no document, while there is one")
         (is (str/includes? (:message (first (filter #(= :store/conflicts (:id %)) checks))) "expects no document"))))
     (testing "a store that cannot delete is usable, and keeps the probe"
       (let [documents            (atom {})
             {:keys [ok? checks]} (store/check! (dissoc (store/memory documents) :delete))]
         (is ok? (pr-str checks))
         (is (= [false] (map :ok? (filter #(= :store/delete (:id %)) checks))))
         (is (= [store/probe-name] (keys @documents)))))
     (testing "a store that can be read but not written"
       (let [documents            (atom {"phone.edn" {:body "{}" :version "1"}})
             {:keys [ok? checks]} (store/check! (dissoc (store/memory documents) :put))]
         (is ok? "a store that can be read")
         (is (= [:store/reachable :store/authenticated :store/listing :store/put] (map :id checks)))
         (is (false? (:ok? (last checks))))
         (is (= ["phone.edn"] (keys @documents)) "and nothing written")))
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
                    (store/check! (dissoc (store/memory) :list))))
           (.then (fn [{:keys [ok? checks]}]
                    (is ok? (pr-str checks))
                    (is (= [false] (map :ok? (filter #(= :store/listing (:id %)) checks))))
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
