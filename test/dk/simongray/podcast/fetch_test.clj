(ns dk.simongray.podcast.fetch-test
  "The front door's fetch!: from a URL to a parsed feed in one step, with the
  conditional headers, the moved-feed hints, the pages of a paged feed, the
  credentials of a private one and the server's word on when to ask again,
  against a server on localhost."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(def feed-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">
   <channel><title>Blåbær</title><itunes:new-feed-url>https://new.example/feed.xml</itunes:new-feed-url>
   <item><title>E1</title><guid>1</guid></item></channel></rss>")

(defn- respond
  [^HttpExchange exchange status ^String body headers]
  (doseq [[k v] headers]
    (.add (.getResponseHeaders exchange) k v))
  (let [bytes (.getBytes body "UTF-8")]
    (if (zero? (alength bytes))
      (do (.sendResponseHeaders exchange status -1)
          (.close exchange))
      (do (.sendResponseHeaders exchange status (alength bytes))
          (with-open [out (.getResponseBody exchange)]
            (.write out bytes))))))

(defn- with-server
  "Call `f` with the base URL of a server on localhost that serves the feed
  with an ETag, answers 304 when that ETag comes back, and 404s elsewhere."
  [f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/feed.xml"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (if (= "\"v1\"" (.getFirst (.getRequestHeaders exchange) "If-None-Match"))
                          (respond exchange 304 "" {"ETag" "\"v1\""})
                          (respond exchange 200 feed-xml {"Content-Type" "application/rss+xml; charset=UTF-8"
                                                          "ETag"         "\"v1\""})))))
    (.createContext server "/gone"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond exchange 404 "nope" {}))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(deftest fetching-and-polling
  (with-server
    (fn [base]
      (let [fresh (podcast/fetch! (str base "/feed.xml"))]
        (testing "a fresh fetch parses the feed and hands back the validators"
          (is (= 200 (:status fresh)))
          (is (str/starts-with? (:body fresh) "<?xml") "the XML comes along, for a cache")
          (is (= "Blåbær" (:rss/title (:feed fresh))))
          (is (= [] (:report fresh)))
          (is (= "\"v1\"" (:etag fresh)))
          (is (= "https://new.example/feed.xml" (:new-feed-url fresh)))
          (is (str/ends-with? (:url fresh) "/feed.xml")))
        (testing "the validators handed back earn a 304 without a feed"
          (let [again (podcast/fetch! (str base "/feed.xml") {:etag (:etag fresh) :last-modified (:last-modified fresh)})]
            (is (= 304 (:status again)))
            (is (not (contains? again :feed)))))
        (testing "an error status comes back without a feed"
          (let [gone (podcast/fetch! (str base "/gone"))]
            (is (= 404 (:status gone)))
            (is (not (contains? gone :feed)))
            (is (not (contains? gone :error)) "a status is an answer")))))))

(defn- page-xml
  "A page of a paged feed with the items `guids`, linking to `next`."
  [next guids]
  (str "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\"><channel><title>Paged</title>"
       (when next (str "<atom:link rel=\"next\" href=\"" next "\"/>"))
       (apply str (for [g guids] (str "<item><title>E" g "</title><guid>" g "</guid></item>")))
       "</channel></rss>"))

(defn- with-routes
  "Call `f` with the base URL of a server on localhost that answers each
  request by `(routes path-and-query exchange)`."
  [routes f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [uri (.getRequestURI exchange)]
                          (routes (str (.getPath uri) (some->> (.getQuery uri) (str "?"))) exchange)))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(deftest pages-credentials-and-hints
  (with-routes
    (fn [path ^HttpExchange exchange]
      (let [auth (.getFirst (.getRequestHeaders exchange) "Authorization")]
        (case path
          "/feed.xml"        (respond exchange 200 (page-xml "?page=2" [9 8 7]) {"Cache-Control" "max-age=600"})
          "/feed.xml?page=2" (respond exchange 200 (page-xml "/feed.xml?page=3" [7 6 5]) {})
          "/feed.xml?page=3" (respond exchange 200 (page-xml "/feed.xml" [4]) {})
          "/broken.xml"      (respond exchange 200 (page-xml "/missing.xml" [1]) {})
          "/private.xml"     (if (= "Basic dTpw" auth)
                               (respond exchange 200 (page-xml nil [1]) {})
                               (respond exchange 401 "" {"WWW-Authenticate" "Basic"}))
          "/busy.xml"        (respond exchange 429 "" {"Retry-After" "120"})
          "/old.xml"         (respond exchange 301 "" {"Location" "/feed.xml"})
          (respond exchange 404 "nope" {}))))
    (fn [base]
      (let [titles #(map :rss/title (:rss/items (:feed %)))]
        (testing "one page unless asked for more"
          (is (= ["E9" "E8" "E7"] (titles (podcast/fetch! (str base "/feed.xml"))))))
        (testing "the pages joined, each item once, and a page named before ends them"
          (is (= ["E9" "E8" "E7" "E6" "E5" "E4"] (titles (podcast/fetch! (str base "/feed.xml") {:max-pages 10}))))
          (is (= ["E9" "E8" "E7" "E6" "E5"] (titles (podcast/fetch! (str base "/feed.xml") {:max-pages 2})))))
        (testing "a page that does not read ends the pages, and the report says so"
          (let [r (podcast/fetch! (str base "/broken.xml") {:max-pages 5})]
            (is (= ["E1"] (titles r)))
            (is (= [[:fetch/page-failed (str base "/missing.xml") "HTTP 404"]]
                   (map (juxt :id :url :reason) (:report r))))))
        (testing "the credentials of a private feed"
          (is (= 401 (:status (podcast/fetch! (str base "/private.xml")))))
          (is (= ["E1"] (titles (podcast/fetch! (str base "/private.xml") {:credentials {:username "u" :password "p"}})))))
        (testing "the server's hints on when to ask again"
          (let [before (System/currentTimeMillis)
                busy   (podcast/fetch! (str base "/busy.xml"))]
            (is (= 429 (:status busy)))
            (is (<= (+ before 120000) (inst-ms (:not-before busy)) (+ (System/currentTimeMillis) 120000))))
          (is (inst? (:not-before (podcast/fetch! (str base "/feed.xml")))) "Cache-Control: max-age"))
        (testing "a permanent redirect gives the URL to keep"
          (let [old (podcast/fetch! (str base "/old.xml"))]
            (is (= (str base "/feed.xml") (:url old) (:permanent-url old)))))))))

(deftest credentials-in-the-url
  (let [seen (atom [])
        note (fn [where ^HttpExchange exchange]
               (swap! seen conj [where (.getFirst (.getRequestHeaders exchange) "Authorization")]))]
    (with-routes
      (fn [_ exchange]
        (note :other exchange)
        (respond exchange 200 (page-xml nil [1]) {}))
      (fn [other]
        (with-routes
          (fn [path ^HttpExchange exchange]
            (note path exchange)
            (cond
              (not= "Basic c2ltb25AZXhhbXBsZS5vcmc6cCBw" (.getFirst (.getRequestHeaders exchange) "Authorization"))
              (respond exchange 401 "" {})
              (= "/private.xml" path) (respond exchange 200 (page-xml "/page2.xml" [3]) {})
              :else                   (respond exchange 200 (page-xml (str other "/page3.xml") [2]) {})))
          (fn [base]
            (let [url (str/replace base "http://" "http://simon%40example.org:p%20p@")
                  r   (podcast/fetch! (str url "/private.xml") {:max-pages 5})]
              (is (= ["E3" "E2" "E1"] (map :rss/title (:rss/items (:feed r)))))
              (is (= (str base "/private.xml") (:url r)) "no URL in the result holds them")
              (is (= [["/private.xml" "Basic c2ltb25AZXhhbXBsZS5vcmc6cCBw"]
                      ["/page2.xml" "Basic c2ltb25AZXhhbXBsZS5vcmc6cCBw"]
                      [:other nil]]
                     @seen)
                  "the pages of the same origin get them, and a page elsewhere does not"))))))))

(deftest credentials-after-a-redirect
  (let [seen (atom [])
        note (fn [where ^HttpExchange exchange]
               (swap! seen conj [where (.getFirst (.getRequestHeaders exchange) "Authorization")]))]
    (with-routes
      (fn [path ^HttpExchange exchange]
        (note [:host path] exchange)
        (respond exchange 200 (page-xml (when (= "/feed.xml" path) "/page2.xml") [2]) {}))
      (fn [host]
        (with-routes
          (fn [path ^HttpExchange exchange]
            (note [:private path] exchange)
            (respond exchange 302 "" {"Location" (str host "/feed.xml")}))
          (fn [private]
            (let [r (podcast/fetch! (str private "/feed.xml") {:credentials {:username "u" :password "p"} :max-pages 5})]
              (is (= (str host "/feed.xml") (:url r)))
              (is (= [[[:private "/feed.xml"] "Basic dTpw"]
                      [[:host "/feed.xml"] nil]
                      [[:host "/page2.xml"] nil]]
                     @seen)
                  "the feed is at another origin, where no page gets them"))))))))

(deftest a-fetch-that-fails
  (testing "a port where no server listens"
    (let [closed (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
          url    (str "http://127.0.0.1:" closed "/feed.xml")
          r      (podcast/fetch! (str "http://u:p@127.0.0.1:" closed "/feed.xml"))]
      (is (= [:url :error] (keys r)) "a result with the error, and nothing thrown")
      (is (= url (:url r)) "no URL in the result holds the credentials")
      (is (= [:fetch/unreachable :warning url] ((juxt :id :level :url) (:error r))))
      (is (string? (:reason (:error r))))
      (is (string? (:message (:error r))) "the diagnostic with its message")))
  (with-routes
    (fn [path ^HttpExchange exchange]
      (case path
        "/page.html" (respond exchange 200 "<html><body><p>Not a feed</p></body></html>" {"ETag" "\"p\""})
        "/data.json" (respond exchange 200 "{\"feed\": false}" {})
        "/atom.xml"  (respond exchange 200 "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>A</title></feed>" {})
        "/empty.xml" (respond exchange 200 "" {})
        (respond exchange 200 feed-xml {})))
    (fn [base]
      (testing "a URL that the app does not allow"
        (let [r (podcast/fetch! (str base "/feed.xml") {:allow? (constantly false)})]
          (is (= [:url :error] (keys r)))
          (is (= :fetch/unreachable (:id (:error r))))
          (is (str/includes? (:reason (:error r)) "is not allowed"))))
      (testing "an answer that is too large"
        (is (= :fetch/unreachable (:id (:error (podcast/fetch! (str base "/feed.xml") {:max-bytes 100}))))))
      (testing "a body that is no feed"
        (doseq [path ["/page.html" "/data.json" "/atom.xml" "/empty.xml"]
                :let [r (podcast/fetch! (str base path))]]
          (is (= [200 :fetch/malformed (str base path)] [(:status r) (:id (:error r)) (:url (:error r))]) path)
          (is (not (contains? r :feed)) path)
          (is (string? (:reason (:error r))) path))
        (let [r (podcast/fetch! (str base "/page.html"))]
          (is (= "\"p\"" (:etag r)) "the validators of the answer")
          (is (str/starts-with? (:body r) "<html>") "and the body, where an app can look for the feed"))))))
