(ns dk.simongray.podcast.testing-test
  "The stand-ins for the network that the tests of an app use: the sender
  over a map, with its answers to validators, and the XML of a feed."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.testing :as testing]))

(def url "https://example.com/feed.xml")

(deftest a-sender-over-a-map
  (let [log  (atom [])
        send (testing/sender {url {:body    "<rss/>"
                                   :headers {"ETag"          "\"v1\""
                                             "Last-Modified" "Thu, 01 Jan 2026 06:00:00 GMT"}}}
                             {:log log})
        ask  #(:status (send {:url url :headers %}))]
    (is (= {:status 200 :url url :body "<rss/>"} (select-keys (send {:url url}) [:status :url :body])))
    (is (= ["\"v1\""] (get-in (send {:url url}) [:headers "etag"])) "with the headers by lower-case name")
    (is (= 304 (ask {"If-None-Match" "\"v1\""})) "unchanged by its ETag")
    (is (= 200 (ask {"If-None-Match" "\"v0\""})))
    (is (= 304 (ask {"If-Modified-Since" "Thu, 01 Jan 2026 06:00:00 GMT"})) "or by its Last-Modified")
    (is (= 200 (ask {"If-None-Match" "\"v0\"" "If-Modified-Since" "Thu, 01 Jan 2026 06:00:00 GMT"}))
        "which an If-None-Match overrules")
    (is (= 404 (:status (send {:url "https://example.com/other.xml"}))))
    (is (= 7 (count @log)) "each request noted")))

(deftest the-xml-of-a-feed
  (let [feed (rss/parse (testing/feed {:title "Tea & Biscuits" :episodes 4 :days 1}))
        [newest :as items] (:rss/items feed)]
    (is (= "Tea & Biscuits" (:rss/title feed)))
    (is (= (guid/from-url url) (:podcast/guid feed)) "the guid of its URL")
    (is (= ["Episode 4" "Episode 3" "Episode 2" "Episode 1"] (map :rss/title items)) "the newest first")
    (is (= #inst "2026-01-04T06:00:00Z" (resolve/published newest)))
    (is (= 1800 (resolve/duration newest)))
    (is (= "https://example.com/4.mp3" (:url (resolve/enclosure newest))))
    (is (= "f" (:podcast/guid (rss/parse (testing/feed {:guid "f"})))))))
