(ns dk.simongray.podcast.web.service-test
  "The inspector's routes, driven through Pedestal's interceptor chain
  without a server, with feeds served by a stub instead of the network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.web.service :as service]
            [io.pedestal.connector.test :refer [response-for]]))

(def example
  (slurp (io/resource "feeds/namespace-example.xml")))

(def connector
  (delay (service/connector 0)))

(defn- get*
  "GET `path` with the query `params`, percent-encoded as a browser would
  send them, which Pedestal's test helper insists on."
  [path & {:as params}]
  (response-for @connector :get
                (str path (when (seq params)
                            (str "?" (str/join "&" (for [[k v] params]
                                                     (str k "=" (java.net.URLEncoder/encode (str v) "UTF-8")))))))))

(deftest the-pages
  (with-redefs [http/fetch! (fn [url & _]
                              (if (str/includes? url "missing")
                                {:status 404 :url url}
                                {:status 200 :url url :body example}))]
    (testing "home"
      (let [{:keys [status body]} (get* "/")]
        (is (= 200 status))
        (is (str/includes? body "Podcast feed inspector"))))
    (testing "a feed inspected, as data and as canonical XML"
      (let [{:keys [status body]} (get* "/feed" "url" "https://example.com/one.xml")]
        (is (= 200 status))
        (is (str/includes? body "Podcasting 2.0 Namespace Example")))
      (is (str/includes? (:body (get* "/feed.edn" "url" "https://example.com/one.xml")) ":rss/title"))
      (let [{:keys [status headers body]} (get* "/feed.xml" "url" "https://example.com/one.xml")]
        (is (= 200 status))
        (is (str/starts-with? (get headers "Content-Type") "application/rss+xml"))
        (is (str/includes? body "<podcast:guid>"))))
    (testing "an item"
      (let [{:keys [status body]} (get* "/item" "url" "https://example.com/one.xml" "guid" "https://example.com/ep0003")]
        (is (= 200 status))
        (is (str/includes? body "ep0003"))))
    (testing "no URL goes home; a feed that cannot be fetched is an error page"
      (is (= 302 (:status (get* "/feed"))))
      (let [{:keys [status body]} (get* "/feed" "url" "https://example.com/missing.xml")]
        (is (= 502 status))
        (is (str/includes? body "HTTP 404"))))))
