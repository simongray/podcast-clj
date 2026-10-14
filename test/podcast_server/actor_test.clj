(ns podcast-server.actor-test
  "The server's ActivityPub actor: the setting, its document and WebFinger
  without a password, its key kept over a restart, and the proxy's signed
  requests, which a stand-in for a fediverse server checks the way
  Mastodon does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.httpsig :as httpsig]
            [dk.simongray.httpsig.cavage :as cavage]
            [dk.simongray.podcast.test-support :refer [with-temp-dir]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch.secret :as secret]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.actor :as actor]
            [podcast-server.device :as device]
            [podcast-server.service :as service])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(def actor-url "https://podcasts.example/actor")

(def password "correct horse battery staple")

(defn- settings
  "The settings of a server with the actor over the folders in `dir`, and
  the `more` settings."
  [dir more]
  (merge service/defaults
         {:port 0 :data-dir (io/file dir "data") :actor actor-url}
         more))

(defn- connector
  "A connector of the server with `settings`."
  [settings]
  (service/connector settings (service/sync-store settings) (atom (device/make))))

(defn- get-public
  "The answer of the connector `c` to a GET of `path` by the actor's host,
  with the `headers` besides."
  ([c path]
   (get-public c path {}))
  ([c path headers]
   (response-for c :get path :headers (merge {"host" "podcasts.example"} headers))))

(deftest the-setting
  (is (= actor-url (:actor (service/settings {"PODCAST_SERVER_ACTOR" actor-url}))))
  (doseq [bad ["http://podcasts.example/actor" "https://podcasts.example/" "https://podcasts.example/a%20b"
               "https://podcasts.example/users/actor"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PODCAST_SERVER_ACTOR is not an https URL whose path is a name"
                          (service/settings {"PODCAST_SERVER_ACTOR" bad}))
        bad))
  (doseq [taken ["https://podcasts.example/proxy" "https://podcasts.example/dav" "https://podcasts.example/media"]]
    (is (thrown? clojure.lang.ExceptionInfo (service/settings {"PODCAST_SERVER_ACTOR" taken}))
        "a path of the server's own, which the actor would open to anyone")))

(deftest the-actor-in-public
  (with-temp-dir
    (fn [dir]
      (let [c   (connector (settings dir {:password (secret/hide password)}))
            r   (get-public c "/actor")
            doc (json/read-str (:body r))]
        (testing "the document, which a server fetches without a password"
          (is (= [200 "application/activity+json"] [(:status r) (get-in r [:headers "Content-Type"])]))
          (is (= [actor-url "Application" "actor" "https://podcasts.example/actor/inbox"]
                 (map doc ["id" "type" "preferredUsername" "inbox"])))
          (is (= {"id" "https://podcasts.example/actor#main-key" "owner" actor-url}
                 (dissoc (doc "publicKey") "publicKeyPem")))
          (is (str/starts-with? (get-in doc ["publicKey" "publicKeyPem"]) "-----BEGIN PUBLIC KEY-----")))
        (testing "WebFinger, which leads back to the document"
          (let [{:keys [status headers body]} (get-public c "/.well-known/webfinger?resource=acct:actor@podcasts.example")]
            (is (= [200 "application/jrd+json" "*"] [status (headers "Content-Type") (headers "Access-Control-Allow-Origin")]))
            (is (= {"subject" "acct:actor@podcasts.example"
                    "aliases" [actor-url]
                    "links"   [{"rel" "self" "type" "application/activity+json" "href" actor-url}]}
                   (json/read-str body))))
          (is (= 200 (:status (get-public c (str "/.well-known/webfinger?resource=" actor-url)))) "by the actor's URL too")
          (is (= 404 (:status (get-public c "/.well-known/webfinger?resource=acct:other@podcasts.example"))))
          (is (= 400 (:status (get-public c "/.well-known/webfinger"))) "RFC 7033, section 4.2"))
        (testing "while the rest still asks for the password"
          (is (= 401 (:status (get-public c "/dav/")))))
        (testing "and the key stays the same after a restart"
          (is (= (doc "publicKey")
                 ((json/read-str (:body (get-public (connector (settings dir {})) "/actor"))) "publicKey"))))))))

(deftest the-actor-through-funnel
  (with-temp-dir
    (fn [dir]
      (let [c      (connector (settings dir {}))
            funnel {"tailscale-funnel-request" "?1"}]
        (is (= 200 (:status (get-public c "/actor" funnel))))
        (is (= 200 (:status (get-public c "/.well-known/webfinger?resource=acct:actor@podcasts.example" funnel))))
        (is (= 403 (:status (get-public c "/dav/" funnel)))
            "a server without a password answers nothing else from the public internet")))))

(defn- actor-of
  "The key of `keyid` after the checks that Mastodon makes of an actor it
  doesn't know, against the podcast server `c`: the type of the document,
  its id, type and inbox, the key's id, and WebFinger that leads back to
  the actor. Nil when one fails."
  [c keyid]
  (let [actor (first (str/split keyid #"#"))
        r     (get-public c "/actor")
        doc   (json/read-str (:body r))
        acct  (str "acct:" (doc "preferredUsername") "@podcasts.example")
        links ((json/read-str (:body (get-public c (str "/.well-known/webfinger?resource=" acct)))) "links")]
    (when (and (= "application/activity+json" (get-in r [:headers "Content-Type"]))
               (= actor (doc "id"))
               (doc "inbox")
               (= "Application" (doc "type"))
               (= keyid (get-in doc ["publicKey" "id"]))
               (= actor (some #(when (= "self" (% "rel")) (% "href")) links)))
      {:keyid keyid :pem (get-in doc ["publicKey" "publicKeyPem"])})))

(defn- with-fediverse-server
  "Call `f` with the origin of a stand-in for a fediverse server on
  localhost, which answers a GET signed by an actor that `key-fn` gives the
  key of, and else 401. Its paths under /strict take RFC 9421 alone, as
  Mastodon may, and the others draft-cavage. Each request goes into the
  atom `seen` as the path and whether it was signed."
  [key-fn seen f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        origin (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [^HttpExchange exchange exchange
                              headers (into {} (for [[k vs] (.getRequestHeaders exchange)]
                                                 [(str/lower-case k) (first vs)]))
                              path    (.getPath (.getRequestURI exchange))
                              message {:method :get :url (str origin path) :headers headers}
                              key-of  (fn [{:keys [keyid]}] (key-fn keyid))
                              result  (if (str/starts-with? path "/strict")
                                        (when (headers "signature-input") (httpsig/verify message key-of))
                                        (cavage/verify message key-of))
                              ok?     (and result (not (:error result)))
                              body    (.getBytes (if ok? "{\"type\":\"Note\"}" "unauthorized"))]
                          (swap! seen conj [path (contains? headers "signature")])
                          (.sendResponseHeaders exchange (if ok? 200 401) (alength body))
                          (doto (.getResponseBody exchange) (.write body) (.close))))))
    (.start server)
    (try
      (f origin)
      (finally
        (.stop server 0)))))

(deftest requests-signed-by-the-actor
  (with-temp-dir
    (fn [dir]
      (let [given (settings dir {})
            c     (connector given)
            send  (service/proxy-send given (actor/key! (service/data-store given) actor-url))
            seen  (atom [])]
        (with-fediverse-server
          #(actor-of c %)
          seen
          (fn [origin]
            (let [status (fn [path accept]
                           (:status (send {:method :get :url (str origin path) :headers {"Accept" accept}})))]
              (is (= 200 (status "/users/ann/statuses/1" "application/activity+json"))
                  "draft-cavage, which a server checks by the actor's document and WebFinger")
              (is (= 200 (status "/strict/users/ann/statuses/1" "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""))
                  "RFC 9421, once a server refuses draft-cavage")
              (is (= 401 (status "/feed.xml" "application/rss+xml")))
              (is (= [["/users/ann/statuses/1" true]
                      ["/strict/users/ann/statuses/1" true]
                      ["/strict/users/ann/statuses/1" true]
                      ["/feed.xml" false]]
                     @seen)
                  "a request for anything but an ActivityPub document goes out unsigned"))))))))
