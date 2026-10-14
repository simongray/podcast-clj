(ns podcast-server.hubs-test
  "The server's WebSub hubs: asking them for what its shows need, their
  checks and pushes at the callback URLs without a password, the
  subscriptions kept over a restart, and a pushed feed fetched at the next
  step."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.feed.guid :as feed.guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [fixture with-temp-dir]]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.websub :as websub]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.device :as device]
            [podcast-server.hubs :as hubs]
            [podcast-server.service :as service]))

(def feed-url "https://example.com/feed.xml")

(def show
  (rss/parse (fixture "feeds/namespace-example.xml")))

(def guid (feed.guid/feed-guid show feed-url))

(def public-url "https://podcasts.example")

(defn- library-with-a-hub
  "A library of the server, with the `config`, subscribed to a show whose
  feed names a hub."
  [config]
  (library/subscribe (device/make config)
                     feed-url
                     {:status 200 :url feed-url :feed show
                      :websub {:hubs ["https://hub.example.org/"] :topic feed-url}}))

(defn- settings
  "The settings of a server at the public URL over the folders in `dir`,
  and the `more` settings."
  [dir more]
  (merge service/defaults
         {:port 0 :data-dir (io/file dir "data") :public-url public-url}
         more))

(defn- call
  "The answer of the connector `c` to a request of `method` for `uri` by
  the public host, with the `headers` and the `body`."
  ([c method uri]
   (call c method uri {} nil))
  ([c method uri headers body]
   (response-for c method uri
                 :headers (merge {"host" "podcasts.example"} headers)
                 :body body)))

(deftest the-hubs-and-their-callbacks
  (with-temp-dir
    (fn [dir]
      (let [given (settings dir {:password (secret/hide "correct horse battery staple")})
            hubs  (service/hubs-of given)
            lib   (atom (library-with-a-hub {}))
            c     (service/connector given (service/sync-store given) lib hubs)
            sent  (atom [])
            ask!  #(hubs/ask! @lib hubs {:send (fn [request]
                                                 (swap! sent conj request)
                                                 {:status 202 :headers {} :body ""})})
            _     (ask!)
            {:keys [callback secret]} (get-in @(:subscriptions hubs) [guid :pending])
            uri   (str/replace-first callback public-url "")
            push  (fn [body signature]
                    (:status (call c :post uri
                                   (cond-> {"content-type" "application/rss+xml"}
                                     signature (assoc "x-hub-signature" signature))
                                   body)))]
        (testing "a subscription for the show whose feed names a hub, at a callback of the public URL"
          (is (= ["https://hub.example.org/"] (map :url @sent)))
          (is (str/starts-with? callback "https://podcasts.example/websub/")))
        (testing "which the hub checks without the password"
          (let [query (url/query-string [["hub.mode" "subscribe"] ["hub.topic" feed-url]
                                         ["hub.challenge" "c1"] ["hub.lease_seconds" "86400"]])]
            (is (= "c1" (:body (call c :get (str uri "?" query))))))
          (is (= :active (get-in @(:subscriptions hubs) [guid :state])))
          (ask!)
          (is (= 1 (count @sent)) "and then nothing more until the renewal"))
        (testing "kept over a restart, with its secret"
          (let [again (hubs/make (service/data-store given) public-url)]
            (is (= (get @(:subscriptions hubs) guid) (get @(:subscriptions again) guid)))))
        (testing "a push with the hub's signature names the feed to fetch"
          (let [body "<rss version=\"2.0\"><channel><title>T</title></channel></rss>"]
            (is (= 200 (push body (websub/signature "sha256" secret (bytes/encode body)))))
            (is (= #{guid} (hubs/pushed! hubs)))
            (is (= #{} (hubs/pushed! hubs)) "once")
            (is (= 403 (push body nil)) "and one without it is refused")
            (is (= #{} (hubs/pushed! hubs)))))
        (testing "while the rest still asks for the password"
          (is (= 401 (:status (call c :get "/dav/")))))))))

(deftest the-callbacks-through-funnel
  (with-temp-dir
    (fn [dir]
      (let [given  (settings dir {})
            c      (service/connector given (service/sync-store given) (atom (device/make)))
            funnel {"tailscale-funnel-request" "?1"}]
        (is (= 410 (:status (call c :post "/websub/unknown" funnel "")))
            "a server without a password lets a hub in, and answers 410 for a callback that it doesn't know")
        (is (= 403 (:status (call c :get "/dav/" funnel nil))))))))

(deftest a-pushed-feed-fetched-at-the-next-step
  (with-temp-dir
    (fn [dir]
      (let [given   (settings dir {})
            fetched (atom [])
            fetch   (fn [u _] (swap! fetched conj u) {:status 304 :url u})
            lib     (atom (library-with-a-hub {:fetch fetch}))]
        (device/step! lib (library/backend (service/sync-store given)))
        (is (= [] @fetched) "a feed that isn't due")
        (device/step! lib (library/backend (service/sync-store given)) {:feed-guids [guid]})
        (is (= [feed-url] @fetched) "is fetched once a hub pushed it")))))
