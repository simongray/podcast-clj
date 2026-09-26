(ns inspector.service-test
  "The inspector's routes, driven through Pedestal's interceptor chain
  without a server, with feeds served by a stub instead of the network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.http :as http]
            [inspector.service :as service]
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
        (is (str/includes? body "ep0003"))
        (is (str/includes? body "item :rss/title") "the resolved properties with their sources")))
    (testing "no URL goes home; a feed that cannot be fetched is an error page"
      (is (= 302 (:status (get* "/feed"))))
      (let [{:keys [status body]} (get* "/feed" "url" "https://example.com/missing.xml")]
        (is (= 502 status))
        (is (str/includes? body "HTTP 404"))))))

(deftest search-and-selection
  (with-redefs [http/fetch! (fn [url & _] {:status 200 :url url :body example})]
    (testing "the feed page filters and orders its items by the library's criteria"
      (let [body (:body (get* "/feed" "url" "https://example.com/one.xml" "q" "future" "order" "oldest"))]
        (is (str/includes? body "Items (1 of 3)"))
        (is (str/includes? body "Episode 3 - The Future"))
        (is (not (str/includes? body "Episode 1 - The Past"))))
      (let [body (:body (get* "/feed" "url" "https://example.com/one.xml" "order" "oldest"))]
        (is (str/includes? body "Items (3)"))
        (is (< (str/index-of body "Episode 1 - The Past") (str/index-of body "Episode 3 - The Future")))))
    (testing "full-text search over one feed, with a snippet of the notes"
      (let [{:keys [status body]} (get* "/search" "url" "https://example.com/one.xml" "q" "future")]
        (is (= 200 status))
        (is (str/includes? body "Episode 3 - The Future"))
        (is (str/includes? body "<mark>future</mark>"))
        (is (not (str/includes? body "Episode 2 - The Present"))))
      (is (str/includes? (:body (get* "/search" "url" "https://example.com/one.xml" "q" "nothing-like-this"))
                         "Nothing matched")))
    (testing "search over the corpus answers whatever the corpus holds"
      (is (= 200 (:status (get* "/search" "q" "podcasting")))))))

(defn- directory-fixture
  "The recorded answer of the directory that `url` addresses."
  [url]
  (slurp (io/resource
          (cond
            (str/includes? url "itunes.apple.com/search")     "directory/apple-search.json"
            (str/includes? url "itunes.apple.com/lookup")     "directory/apple-lookup.json"
            (str/includes? url "rss.marketingtools.apple.com") "directory/apple-charts.json"
            (str/includes? url "api.fyyd.de/0.2/search")      "directory/fyyd-search.json"
            (str/includes? url "api.fyyd.de/0.2/feature")     "directory/fyyd-hot.json"
            (str/includes? url "gpodder.net/search")          "directory/gpodder-search.json"
            (str/includes? url "gpodder.net/toplist")         "directory/gpodder-toplist.json"
            (str/includes? url "api.podcastindex.org")        "index/search-byterm.json"))))

(deftest the-directories
  (with-redefs [http/call! (fn [{:keys [url]}] {:status 200 :url url :body (directory-fixture url)})]
    (testing "a search across the directories, combined, each result linking to its feed"
      (let [{:keys [status body]} (get* "/directory" "q" "podcasting 2.0")]
        (is (= 200 status))
        (is (str/includes? body "Podcasting 2.0"))
        (is (str/includes? body "fyyd"))
        (is (str/includes? body (str "/feed?url=" (java.net.URLEncoder/encode "https://feeds.podcastindex.org/pc20.xml" "UTF-8"))))))
    (testing "the charts without a query"
      (let [{:keys [status body]} (get* "/directory" "country" "dk")]
        (is (= 200 status))
        (is (str/includes? body "Mørkeland"))
        (is (str/includes? body "rank 1"))))))

(defn- hive-fixture
  "The recorded answer of a Hive node to the JSON-RPC `body`."
  [body]
  (slurp (io/resource
          (cond
            (str/includes? body "get_dynamic_global_properties") "podping/head-block.json"
            (str/includes? body "get_block_range")               "podping/blocks.json"
            (str/includes? body "get_ops_in_block")              "podping/operations.json"
            (str/includes? body "get_following")                 "podping/following.json"))))

(deftest the-podpings
  (with-redefs [http/call! (fn [{:keys [url body]}] {:status 200 :url url :body (hive-fixture body)})]
    (let [{:keys [status body]} (get* "/podping")]
      (is (= 200 status))
      (is (str/includes? body "podping.eee"))
      (is (not (str/includes? body "pp_podcast_update")) "the id is not shown, the medium and reason are")
      (is (str/includes? body "Head block 110254836"))
      (is (str/includes? body "writers podping follows")))))

(deftest the-player
  (with-redefs [http/fetch! (fn [url & _] {:status 200 :url url :body example})]
    (testing "every page carries the player bar and its script; the queue page the shell of the detailed view"
      (let [{:keys [status body]} (get* "/")]
        (is (= 200 status))
        (is (str/includes? body "id=\"player-bar\""))
        (is (str/includes? body "id=\"content\""))
        (is (str/includes? body "/js/player.js")))
      (let [{:keys [status body]} (get* "/queue")]
        (is (= 200 status))
        (is (str/includes? body "id=\"app\""))))
    (testing "the item page links into the player"
      (let [body (:body (get* "/item" "url" "https://example.com/one.xml" "guid" "https://example.com/ep0003"))]
        (is (str/includes? body "/queue?add="))
        (is (str/includes? body "mode=next"))))
    (testing "the episode endpoint answers with what the player needs, as EDN"
      (let [{:keys [status headers body]} (get* "/episode.edn" "url" "https://example.com/one.xml" "guid" "https://example.com/ep0003")
            episode (read-string body)]
        (is (= 200 status))
        (is (str/starts-with? (get headers "Content-Type") "application/edn"))
        (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"] (:id episode)))
        (is (string? (:enclosure episode)))
        (is (string? (:title episode))))
      (is (= 404 (:status (get* "/episode.edn" "url" "https://example.com/one.xml" "guid" "nope")))))
    (testing "the compiled player is served when it exists and explained when it does not"
      (let [{:keys [status body]} (get* "/js/player.js")]
        (is (contains? #{200 404} status))
        (when (= 404 status)
          (is (str/includes? body "compile player"))))
      (is (= 404 (:status (get* "/js/../deps.edn")))))))
