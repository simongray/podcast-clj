(ns inspector.dav-test
  "The inspector's folder over WebDAV, driven through Pedestal's interceptor
  chain on a temporary directory: the methods, the ETags and the
  preconditions, and the library's own WebDAV store syncing two devices
  through it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            [dk.simongray.podcast.http :as http]
            [inspector.service :as service]
            [io.pedestal.connector.test :refer [response-for]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *dir* nil)

(def ^:dynamic *connector* nil)

(use-fixtures :each
  (fn [f]
    (let [dir (.toFile (Files/createTempDirectory "inspector-dav" (make-array FileAttribute 0)))]
      (try
        (binding [*dir*       dir
                  *connector* (service/connector 0 {:sync-dir (io/file dir "sync")})]
          (f))
        (finally
          (doseq [file (reverse (file-seq dir))]
            (io/delete-file file true)))))))

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
      (is (re-matches #"\"\d+-\d+\"" etag) "the folder store's version, quoted")
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
    (is (= 201 (:status (call :mkcol "/dav/"))))
    (is (= 405 (:status (call :mkcol "/dav/sub"))) "no collections within the folder")))

(defn- through-the-inspector
  "The library's HTTP client stood in by the connector: a request map sent
  through Pedestal, its answer in the client's shape."
  [{:keys [method url headers body]}]
  (let [path     (str/replace url #"^https?://[^/]+" "")
        response (call method path :body body :headers headers)]
    (update response :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %))))

(deftest two-devices-through-the-inspector
  (with-redefs [http/call! through-the-inspector]
    (let [backend (store/backend (webdav/store "http://inspector/dav/") {:opml? true :app "test"})
          phone   (sync/sync! (-> (player/state "phone")
                                  (player/identify {:name "Phone"} #inst "2026-01-01T01:00:00Z")
                                  (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} #inst "2026-01-01T01:00:00Z"))
                              [backend] #inst "2026-01-01T02:00:00Z")
          laptop  (sync/sync! (player/progress (player/state "laptop") ["feed-a" "a1"] 60 3600 #inst "2026-01-01T02:00:00Z")
                              [backend] #inst "2026-01-01T03:00:00Z")
          phone2  (sync/sync! (:state phone) [backend] #inst "2026-01-01T04:00:00Z" {:ctx (:ctx phone)})]
      (is (nil? (:failed phone)))
      (is (nil? (:failed laptop)))
      (is (= [] (:report phone2)))
      (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"}
             (set (map #(.getName ^java.io.File %) (.listFiles (io/file *dir* "sync"))))))
      (is (= (dissoc (:state laptop) :device :devices :clock) (dissoc (:state phone2) :device :devices :clock)))
      (is (= 60 (:position (player/episode (:state phone2) ["feed-a" "a1"]))))
      (is (= "test" (:app (sync/read-document (slurp (io/file *dir* "sync" "laptop.edn")))))))))
