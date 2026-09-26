(ns dk.simongray.podcast.fetch-test
  "The front door's fetch!: from a URL to a parsed feed in one step, with the
  conditional headers and the moved-feed hint, against a server on
  localhost."
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
            (is (not (contains? gone :feed)))))))))
