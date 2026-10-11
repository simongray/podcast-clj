(ns podcast-server.service-test
  "The server's routes, driven through Pedestal's interceptor chain on
  temporary folders: the settings, the names that it answers, the client's
  files, the proxy, and the library's own store check over its sync
  folder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.store.webdav :as webdav]
            [dk.simongray.podcast.test-support :refer [with-temp-dir]]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.secret :as secret]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.device :as device]
            [podcast-server.service :as service]))

(defn- connector
  "A connector of the server over the folders in `dir`, with the client's
  files when `client?` and the `more` settings, whose waits after a wrong
  password go by the milliseconds of `clock`, by default a clock whose
  every tick passes any wait."
  ([dir client?]
   (connector dir client? {}))
  ([dir client? more]
   (connector dir client? more (let [t (atom 0)] #(swap! t + 120000))))
  ([dir client? more clock]
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
                           more)
           guesses  service/wrong-guesses]
       (with-redefs [service/wrong-guesses (fn [_] (guesses clock))]
         (service/connector settings (service/sync-store settings) (atom (device/make))))))))

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
                        (service/settings {"PODCAST_SERVER_PORT" "eighty"})))
  (is (= [5 1000] ((juxt :max-sync-documents :max-sync-bytes)
                   (service/settings {"PODCAST_SERVER_MAX_SYNC_DOCUMENTS" "5" "PODCAST_SERVER_MAX_SYNC_BYTES" "1000"}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PODCAST_SERVER_MAX_SYNC_BYTES is not a number"
                        (service/settings {"PODCAST_SERVER_MAX_SYNC_BYTES" "1 GB"}))))

(deftest an-open-server
  (is (not (service/open? service/defaults)) "no password, on localhost")
  (is (service/open? (assoc service/defaults :host "0.0.0.0")) "no password, e.g. behind Caddy")
  (is (not (service/open? (assoc service/defaults :host "0.0.0.0" :password (secret/hide password)))))
  (is (not (service/open? (service/settings {"PODCAST_SERVER_HOST"            "0.0.0.0"
                                             "PODCAST_SERVER_PRIVATE_NETWORK" "true"})))
      "no password, in Docker behind tailscale serve")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PODCAST_SERVER_PRIVATE_NETWORK is neither true nor false: yes"
                        (service/settings {"PODCAST_SERVER_PRIVATE_NETWORK" "yes"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The server needs a password to listen on 0.0.0.0"
                        (service/start! {:host "0.0.0.0"}))))

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
            "a request from the public internet"))
      (is (= 200 (:status (response-for (connector dir true {:names #{"pi.example.ts.net:8443"}})
                                        :get "/" :headers {"host" "pi.example.ts.net:8443"})))
          "a name given with its port"))))

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
        (spit (io/file dir "client" ".env") "SECRET=1")
        (is (= 404 (:status (response-for c :get "/.env"))) "no dotfile")
        (is (= 404 (:status (response-for c :get "/%2Eenv"))) "nor one with an escaped dot")
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

(deftest writes-of-other-sites
  (with-temp-dir
    (fn [dir]
      (let [c (connector dir true)]
        (is (= 403 (:status (response-for c :put "/dav/a.edn" :body "{}" :headers {"sec-fetch-site" "cross-site"}))))
        (is (= 403 (:status (response-for c :post "/api/2/subscriptions/simon/phone.json" :body "{\"add\": []}"
                                          :headers {"sec-fetch-site" "same-site" "content-type" "text/plain"})))
            "a form of another site that posts to the gpodder API")
        (is (= 201 (:status (response-for c :put "/dav/a.edn" :body "{}" :headers {"sec-fetch-site" "same-origin"})))
            "the server's own page")
        (is (= 204 (:status (response-for c :put "/dav/a.edn" :body "{}"))) "an app that isn't a browser")
        (is (= 200 (:status (response-for c :get "/" :headers {"sec-fetch-site" "cross-site"})))
            "a link of another site")))))

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
      (with-redefs [http/send! (through (connector dir false))]
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
          (with-redefs [http/send! (through c)]
            (is (:ok? (store/check! (webdav/store "http://localhost:8400/dav/" {:credentials {:username "simon" :password password}}))))
            (is (not (:ok? (store/check! (webdav/store "http://localhost:8400/dav/")))) "without the credentials")))
        (is (= 400 (:status (response-for c :put (str "/dav/" (apply str (repeat 300 "a")) ".edn") :headers mine :body "{}")))
            "a name too long for the folder, not an exception whose log holds the request")))))

(deftest no-password-in-a-log
  (let [{:keys [request]} ((:enter service/hidden-credentials) {:request {:headers (basic "simon" password)}})]
    (is (= "{:headers {\"authorization\" #<secret>}}" (pr-str request)))))

(deftest a-new-password
  (with-temp-dir
    (fn [dir]
      (let [login  (response-for (connector dir false {:password (secret/hide password)})
                                 :post "/api/2/auth/simon/login.json" :headers (basic "simon" password))
            cookie (first (str/split (get-in login [:headers "Set-Cookie"]) #";"))
            after  (connector dir false {:password (secret/hide "a new password, as long")})]
        (is (= 401 (:status (response-for after :get "/api/2/devices/simon.json" :headers {"cookie" cookie})))
            "ends the sessions, at the restart that sets it")))))

(deftest waits-after-wrong-guesses
  (with-temp-dir
    (fn [dir]
      (let [t     (atom 0)
            c     (connector dir true {:password (secret/hide password)} #(deref t))
            wrong (basic "simon" "correct horse battery stapler")
            mine  (basic "simon" password)
            ask   #(response-for c :get "/" :headers %)]
        (is (= 401 (:status (ask wrong))))
        (is (= [429 "1"] ((juxt :status #(get-in % [:headers "Retry-After"])) (ask mine)))
            "for a second, when even the right password waits")
        (reset! t 1000)
        (is (= 401 (:status (ask wrong))))
        (reset! t 2500)
        (is (= 429 (:status (ask mine))) "twice as long after the second")
        (reset! t 3000)
        (is (= 200 (:status (ask mine))))
        (is (= 401 (:status (ask wrong))))
        (reset! t 4000)
        (is (= 200 (:status (ask mine))) "a count started again by the right password")
        (is (= 401 (:status (response-for c :post "/api/2/auth/simon/login.json" :headers wrong))))
        (is (= 429 (:status (ask mine))) "a wrong guess at the gpodder API counts too")
        (is (= 60000 (:until (get (nth (iterate #(#'service/waits % :k 0) {}) 12) :k)))
            "a minute at most")))))

(deftest the-address-of-a-client
  (let [address #'service/client-address]
    (is (= [203 0 113 9] (address {:remote-addr "203.0.113.9" :headers {"x-forwarded-for" "10.0.0.1"}}))
        "the one that connected, whatever it says")
    (is (= [198 51 100 7] (address {:remote-addr "127.0.0.1" :headers {"x-forwarded-for" "1.2.3.4, 198.51.100.7"}}))
        "or behind the proxy in front, the one that the proxy saw")
    (is (= (address {:remote-addr "2001:db8:1:2::1"}) (address {:remote-addr "[2001:db8:1:2:ffff::9]"}))
        "an IPv6 client by its /64")))

(deftest a-quota-on-the-sync-folder
  (with-temp-dir
    (fn [dir]
      (let [c   (connector dir false {:max-sync-documents 2 :max-sync-bytes 100})
            put #(:status (response-for c :put (str "/dav/" %1) :body %2 :headers {"content-length" (str (count %2))}))]
        (is (= [201 201] [(put "a.edn" "{:a 1}") (put "b.edn" "{:b 1}")]))
        (is (= 507 (put "c.edn" "{}")) "a third document")
        (is (= 204 (put "a.edn" "{:a 2}")) "but a document replaced")
        (is (= 507 (put "a.edn" (apply str (repeat 95 "x")))) "nor more bytes than the folder holds")))))

(deftest requests-at-once
  (let [started (java.util.concurrent.CountDownLatch. 1)
        gate    (promise)
        handler (service/at-most 1 0 (fn [_] (.countDown started) @gate {:status 200}))
        first   (future (handler {}))]
    (.await started)
    (is (= [503 "5"] ((juxt :status #(get-in % [:headers "Retry-After"])) (handler {}))) "one more than it takes")
    (deliver gate true)
    (is (= 200 (:status @first)))
    (is (= 200 (:status (handler {}))) "and its turn again, once the first is done")))
