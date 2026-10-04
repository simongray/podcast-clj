(ns podcast-server.service-test
  "The server's routes, driven through Pedestal's interceptor chain on
  temporary folders: the settings, the names that it answers, the client's
  files, the proxy, and the library's own store check over its sync
  folder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.sync.webdav :as webdav]
            [dk.simongray.podcast.test-support :refer [with-temp-dir]]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.secret :as secret]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.device :as device]
            [podcast-server.service :as service]))

(defn- connector
  "A connector of the server over the folders in `dir`, with the client's
  files when `client?` and the `more` settings."
  ([dir client?]
   (connector dir client? {}))
  ([dir client? more]
   (let [client (io/file dir "client")]
     (when client?
       (.mkdirs client)
       (spit (io/file client "index.html") "<!doctype html><script src=\"/app.js\"></script>")
       (spit (io/file client "app.js") "console.log(1)"))
     (let [settings (merge service/defaults
                           {:port       0
                            :names      #{"pi.example.ts.net"}
                            :data-dir   (io/file dir "data")
                            :client-dir (when client? client)}
                           more)]
       (service/connector settings (service/sync-store settings) (atom (device/make)))))))

(def password
  "correct horse battery staple")

(defn- basic
  "The headers of Basic credentials of `user` and `pw`."
  [user pw]
  {"authorization" (secret/reveal (http/authorization {:username user :password pw}))})

(deftest the-settings
  (is (= service/defaults (service/settings {})))
  (let [{given :password} (service/settings {"PODCAST_SERVER_PASSWORD" password})]
    (is (= "#<secret>" (pr-str given)) "a password that doesn't print")
    (is (service/password? given password))
    (is (not (service/password? given "correct horse battery stapler"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PODCAST_SERVER_PASSWORD has fewer than 16 characters"
                        (service/settings {"PODCAST_SERVER_PASSWORD" "hunter2"})))
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
  (fn [request]
    ;; with the header names in lower case, as a real server gives them
    (let [{:keys [method url headers body]} (http/revealed request)
          path     (str/replace url #"^https?://[^/]+" "")
          headers  (update-keys headers str/lower-case)
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

(deftest the-login
  (with-temp-dir
    (fn [dir]
      (let [c    (connector dir true {:password (secret/hide password)})
            mine (basic "simon" password)]
        (let [{:keys [status headers]} (response-for c :get "/")]
          (is (= 401 status) "no credentials")
          (is (= "Basic realm=\"Podcast server\", charset=\"UTF-8\"" (get headers "WWW-Authenticate"))))
        (is (= 401 (:status (response-for c :get "/" :headers (basic "simon" "correct horse battery stapler")))))
        (is (= 200 (:status (response-for c :get "/" :headers (basic "anyone" password)))) "any user name")
        (is (= 401 (:status (response-for c :get "/proxy"))))
        (is (= 207 (:status (response-for c :propfind "/dav/" :headers mine))))
        (is (= 200 (:status (response-for c :get "/" :headers (assoc mine "tailscale-funnel-request" "?1"))))
            "Funnel, now that the server asks for a password")
        (is (= 403 (:status (response-for c :get "/" :headers (assoc mine "host" "attacker.example")))) "but only its own names")
        (testing "the gpodder API checks the password itself, and then takes the cookie of its session"
          (is (= 401 (:status (response-for c :post "/api/2/auth/simon/login.json"
                                            :headers (basic "simon" "correct horse battery stapler")))))
          (let [login  (response-for c :post "/api/2/auth/simon/login.json" :headers mine)
                cookie (first (str/split (get-in login [:headers "Set-Cookie"]) #";"))]
            (is (= 200 (:status login)))
            (is (= 200 (:status (response-for c :get "/api/2/devices/simon.json" :headers {"cookie" cookie}))))))
        (testing "the library's own WebDAV client"
          (with-redefs [http/call! (through c)]
            (is (:ok? (store/check! (webdav/store "http://localhost:8400/dav/" {:credentials {:username "simon" :password password}}))))
            (is (not (:ok? (store/check! (webdav/store "http://localhost:8400/dav/")))) "without the credentials")))))))
