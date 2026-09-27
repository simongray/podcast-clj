(ns dk.simongray.podcast.app.sync-file-test
  "A folder as a document store, on a temporary directory: the store's
  contract, and two devices kept in step through it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.file :as file]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 a same? with-temp-dir]]))

(deftest a-folder-store
  (with-temp-dir
    (fn [dir]
      (let [{:keys [fetch put list delete]} (file/store (io/file dir "sync"))]
        (is (= [] (list)) "a folder that is not there yet lists nothing")
        (is (nil? (fetch "a.edn")))
        (let [{:keys [version]} (put "a.edn" "one" {})]
          (is (string? version))
          (is (= {:body "one" :version version} (fetch "a.edn")))
          (is (= [{:name "a.edn" :version version}] (list)))
          (is (= {:conflict? true} (put "a.edn" "two" {:if-version "stale"})))
          (is (= {:conflict? true} (put "b.edn" "x" {:if-version version})) "a version for a file that is not there")
          (is (:version (put "a.edn" "two" {:if-version version})))
          (is (= "two" (:body (fetch "a.edn")))))
        (testing "what is not a document"
          (spit (io/file dir "sync" ".hidden") "x")
          (is (= ["a.edn"] (map :name (list))) "hidden files are not documents")
          (is (= [] (filter #(.contains (.getName ^java.io.File %) ".tmp") (.listFiles (io/file dir "sync"))))
              "no temporary file is left behind")
          (is (thrown? Exception (fetch "../a.edn")))
          (is (thrown? Exception (put ".hidden" "x" {}))))
        (delete "a.edn")
        (is (= [] (list)))))))

(deftest a-folder-checked-and-a-backend-in-one-call
  (with-temp-dir
    (fn [dir]
      (let [{:keys [ok? checks]} (store/check! (file/store (io/file dir "new")))]
        (is ok? (pr-str checks))
        (is (every? :ok? checks))
        (is (.isDirectory (io/file dir "new")) "the folder is there")
        (is (empty? (.list (io/file dir "new"))) "and the probe is gone"))
      (let [b (file/backend dir {:opml? true :name "Folder"})]
        (is (= "Folder" (:name b)))
        (is (nil? (:failed (sync/sync! (player/state "phone") [b] t1))))))))

(deftest two-devices-through-a-folder
  (with-temp-dir
    (fn [dir]
      (let [backend (store/backend (file/store dir) {:opml? true})
            phone   (sync/sync! (player/subscribe (player/state "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
                                [backend] t1)
            laptop  (sync/sync! (player/progress (player/state "laptop") a 60 3600 t1) [backend] t2)
            phone2  (sync/sync! (:state phone) [backend] t3 {:ctx (:ctx phone)})]
        (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"}
               (set (map #(.getName ^java.io.File %) (.listFiles (io/file dir))))))
        (is (nil? (:failed phone2)))
        (is (= [] (:report phone2)))
        (is (same? (:state laptop) (:state phone2)))
        (is (= (:state phone2) (:state (sync/read-document (slurp (io/file dir "phone.edn"))))))
        (is (= 60 (:position (player/episode (:state phone2) a))))))))
