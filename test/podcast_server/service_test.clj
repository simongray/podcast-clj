(ns podcast-server.service-test
  "The server's routes, driven through Pedestal's interceptor chain on
  temporary folders: the settings, the names that it answers, the client's
  files, the proxy, and the library's own store check over its sync
  folder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.sync.webdav :as webdav]
            [dk.simongray.podcast.test-support :refer [with-temp-dir]]
            [dk.simongray.podcast.util.http :as http]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.service :as service]))

(defn- connector
  "A connector of the server over the folders in `dir`, with the client's
  files when `client?`."
  [dir client?]
  (let [client (io/file dir "client")]
    (when client?
      (.mkdirs client)
      (spit (io/file client "index.html") "<!doctype html><script src=\"/app.js\"></script>")
      (spit (io/file client "app.js") "console.log(1)"))
    (service/connector (assoc service/defaults
                              :port 0
                              :names #{"pi.example.ts.net"}
                              :data-dir (io/file dir "data")
                              :client-dir (when client? client)))))

(deftest the-settings
  (is (= service/defaults (service/settings {})))
  (is (= {:port 8401 :names #{"pi.example.ts.net" "other"} :client-dir "/client"}
         (select-keys (service/settings {"PODCAST_SERVER_PORT"   "8401"
                                         "PODCAST_SERVER_NAMES"  " Pi.example.ts.net, ,other"
                                         "PODCAST_SERVER_CLIENT" "/client"
                                         "PODCAST_SERVER_DATA"   ""})
                      [:port :names :client-dir]))
      "a blank variable keeps the default")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PODCAST_SERVER_PORT is not a port number"
                        (service/settings {"PODCAST_SERVER_PORT" "eighty"}))))

(deftest the-names-it-answers
  (with-temp-dir
    (fn [dir]
      (let [c (connector dir true)]
        (doseq [host ["localhost:8400" "127.0.0.1:8400" "[::1]:8400" "pi.example.ts.net" "PI.example.ts.net"]]
          (is (= 200 (:status (response-for c :get "/" :headers {"host" host}))) host))
        (let [{:keys [status body]} (response-for c :get "/dav/" :headers {"host" "attacker.example:8400"})]
          (is (= 403 status) "a page of another name that points at the server")
          (is (= "The server doesn't answer to the name attacker.example." body)))
        (is (= 403 (:status (response-for c :get "/" :headers {"host"                     "pi.example.ts.net"
                                                                "tailscale-funnel-request" "?1"})))
            "a request from the public internet")))))

(deftest the-client-files
  (with-temp-dir
    (fn [dir]
      (let [c (connector dir true)
            {:keys [status headers body]} (response-for c :get "/")]
        (is (= 200 status))
        (is (= "text/html" (get headers "Content-Type")) "the folder's index.html, typed by its name")
        (is (str/includes? body "app.js"))
        (is (= "script-src 'self' 'unsafe-eval'; object-src 'none'" (get headers "Content-Security-Policy")))
        (let [{:keys [status headers]} (response-for c :get "/app.js")]
          (is (= 200 status))
          (is (= "text/javascript" (get headers "Content-Type")))
          (is (= 304 (:status (response-for c :get "/app.js" :headers {"if-modified-since" (get headers "Last-Modified")})))))
        (is (= 404 (:status (response-for c :get "/missing.js"))))
        (is (= 404 (:status (response-for c :get "/sub/"))))
        (is (= 404 (:status (response-for (connector dir false) :get "/"))) "no client")))))

(deftest the-proxy
  (with-temp-dir
    (fn [dir]
      (let [c   (connector dir false)
            url (str "/proxy?url=" (java.net.URLEncoder/encode "https://example.org/feed.xml" "UTF-8"))]
        (is (= 400 (:status (response-for c :get "/proxy"))) "no url to fetch")
        (is (= 405 (:status (response-for c :post url))))
        (is (= 403 (:status (response-for c :get url :headers {"sec-fetch-site" "cross-site"})))
            "a page of another site")))))

(defn- through
  "The library's HTTP client stood in by the connector `c`: a request map
  sent through Pedestal, its answer in the client's shape."
  [c]
  (fn [{:keys [method url headers body]}]
    (let [path     (str/replace url #"^https?://[^/]+" "")
          response (apply response-for c method path (concat (when body [:body body]) (when headers [:headers headers])))]
      (update response :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %)))))

(deftest the-sync-folder
  (with-temp-dir
    (fn [dir]
      (with-redefs [http/call! (through (connector dir false))]
        (let [{:keys [ok? checks]} (store/check! (webdav/store "http://localhost:8400/dav/"))]
          (is ok?)
          (is (every? :ok? checks) (pr-str (remove :ok? checks))))
        (is (.isDirectory (io/file dir "data" "sync")) "the documents are in the sync folder of the data")))))
