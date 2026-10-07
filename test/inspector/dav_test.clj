(ns inspector.dav-test
  "The inspector's folder over WebDAV, driven through Pedestal's interceptor
  chain on a temporary directory: the methods, the ETags and the
  preconditions, and the library's own WebDAV store syncing two devices
  through it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.webdav :as webdav]
            [dk.simongray.podcast.test-support :refer [t1 t2 t3 t4 a failed same? with-temp-dir]]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.http :as http]
            [inspector.service :as service]
            [io.pedestal.connector.test :refer [response-for]]))

(def ^:dynamic *dir* nil)

(def ^:dynamic *connector* nil)

(use-fixtures :each
  (fn [f]
    (with-temp-dir
      (fn [dir]
        (binding [*dir*       dir
                  *connector* (service/connector 0 {:sync-dir (io/file dir "sync")})]
          (f))))))

(defn- call
  [method path & {:keys [body headers]}]
  (apply response-for *connector* method path
         (concat (when body [:body body]) (when headers [:headers headers]))))

(deftest the-methods
  (testing "what the folder can do"
    (let [{:keys [status headers]} (call :options "/dav/")]
      (is (= 200 status))
      (is (= "1" (get headers "DAV")))
      (is (str/includes? (get headers "Allow") "PROPFIND"))))
  (testing "an empty folder, addressed with and without its slash"
    (doseq [path ["/dav/" "/dav"]]
      (let [{:keys [status body]} (call :propfind path)]
        (is (= 207 status))
        (is (str/includes? body "<d:collection/>"))
        (is (not (str/includes? body "getetag")) "nothing in it yet"))))
  (testing "a document put, listed, fetched and deleted"
    (let [{:keys [status headers]} (call :put "/dav/phone.edn" :body "{:version 1 :state {}}")
          etag (get headers "ETag")]
      (is (= 201 status))
      (is (re-matches #"\"\d{4}-\d{2}-\d{2}T[\d:.]+Z-\d+\"" etag) "the folder store's version, quoted")
      (let [{:keys [status body headers]} (call :get "/dav/phone.edn")]
        (is (= 200 status))
        (is (= "{:version 1 :state {}}" body))
        (is (= etag (get headers "ETag")))
        (is (str/starts-with? (get headers "Content-Type") "application/edn")))
      (is (= "" (:body (call :head "/dav/phone.edn"))))
      (let [{:keys [status body]} (call :propfind "/dav/")]
        (is (= 207 status))
        (is (str/includes? body "<d:href>/dav/phone.edn</d:href>"))
        (is (str/includes? body (str "<d:getetag>" (str/escape etag {\" "&quot;"}) "</d:getetag>"))))
      (is (= [{:name "phone.edn" :version etag}] (webdav/response :list (update (call :propfind "/dav/") :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %))))
          "the library's own reader agrees")
      (testing "the preconditions"
        (is (= 412 (:status (call :put "/dav/phone.edn" :body "x" :headers {"If-Match" "\"stale\""}))))
        (is (= 412 (:status (call :put "/dav/phone.edn" :body "x" :headers {"If-None-Match" "*"}))))
        (is (= 204 (:status (call :put "/dav/phone.edn" :body "{:version 1 :state {:a 1}}" :headers {"If-Match" etag}))))
        (is (= 201 (:status (call :put "/dav/laptop.edn" :body "{}" :headers {"If-None-Match" "*"})))))
      (is (= 204 (:status (call :delete "/dav/laptop.edn"))))
      (is (= 404 (:status (call :delete "/dav/laptop.edn"))))
      (is (= 404 (:status (call :get "/dav/laptop.edn"))))
      (is (= 404 (:status (call :propfind "/dav/laptop.edn"))))))
  (testing "what is refused"
    (is (= 405 (:status (call :get "/dav/"))))
    (is (= 405 (:status (call :put "/dav/" :body "x"))))
    (is (= 405 (:status (call :lock "/dav/phone.edn"))))
    (is (= 400 (:status (call :put "/dav/.hidden" :body "x"))) "a hidden file is not a document")
    (is (= 405 (:status (call :mkcol "/dav/"))) "the collection is there")
    (is (= 405 (:status (call :mkcol "/dav/sub"))) "no collections within the folder")))

(defn- through-the-inspector
  "The library's HTTP client stood in by the connector: a request map sent
  through Pedestal, its answer in the client's shape."
  [{:keys [method url headers body]}]
  (let [path     (str/replace url #"^https?://[^/]+" "")
        response (call method path :body body :headers headers)]
    (update response :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %))))

(deftest two-devices-through-the-inspector
  (with-redefs [http/send! through-the-inspector]
    (let [backend (sync/backend (webdav/store "http://inspector/dav/") {:opml? true :app "test"})
          phone   (sync/sync! (-> (profile/make "phone")
                                  (crdt/identify {:name "Phone"} t1)
                                  (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t1))
                              [backend] {:at t2})
          laptop  (sync/sync! (profile/progress (profile/make "laptop") a 60 3600 t2) [backend] {:at t3})
          phone2  (sync/sync! (:state phone) [backend] {:at t4 :cursors (:cursors phone)})]
      (is (nil? (failed phone)))
      (is (nil? (failed laptop)))
      (is (= [] (:report phone2)))
      (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"}
             (set (map #(.getName ^java.io.File %) (.listFiles (io/file *dir* "sync"))))))
      (is (same? (:state laptop) (:state phone2)))
      (is (= 60 (:position (profile/episode-entry (:state phone2) a))))
      (is (= "test" (:app (sync/read-document (slurp (io/file *dir* "sync" "laptop.edn")))))))))
