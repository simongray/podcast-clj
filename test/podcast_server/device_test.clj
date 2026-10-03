(ns podcast-server.device-test
  "The server as one of the listener's devices, on a temporary sync folder
  with a feed served by a stub: its library follows a phone's profile and
  fetches the feed, and the gpodder API names the phone's episode by it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.test-support :refer [with-temp-dir]]
            [dk.simongray.podcast.util.json :as json]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.device :as device]
            [podcast-server.service :as service]))

(def url "https://a.example/rss")

(def id
  [(guid/from-url url) "a1"])

(def feed
  (podcast/parse (str "<rss version=\"2.0\"><channel><title>A</title>"
                      "<item><title>One</title><guid>a1</guid>"
                      "<enclosure url=\"https://a.example/1.mp3\" type=\"audio/mpeg\" length=\"1\"/></item>"
                      "</channel></rss>")))

(defn- fetch
  "The feed at `u`, as podcast/fetch! gives it, without the network."
  [u _]
  {:status 200 :url u :feed feed})

(defn- phone-synced!
  "Sync a phone that subscribes to the feed and is ten minutes into its
  episode through `store`."
  [store]
  (sync/sync! (-> (profile/make "phone")
                  (profile/subscribe (first id) {:url url :title "A"} #inst "2026-01-01T01:00:00Z")
                  (profile/progress id 600 3600 #inst "2026-01-01T01:00:01Z"))
              [(store/backend store)]
              #inst "2026-01-01T01:00:02Z"))

(deftest the-library-follows-the-listener
  (with-temp-dir
    (fn [dir]
      (let [store (service/sync-store {:data-dir dir})]
        (phone-synced! store)
        (let [library (device/step! (atom (device/make {:fetch fetch})) (store/backend store))]
          (is (nil? (get-in library [:sync :failed])))
          (is (= "A" (get-in library [:feeds (first id) :feed :rss/title])) "the feed of the phone's subscription")
          (is (= 600 (:position (profile/episode-entry (:profile library) id))) "the phone's progress")
          (is (.isFile (io/file dir "sync" "podcast-server.edn")) "the library's own document"))))))

(deftest the-gpodder-api-names-episodes-by-the-feeds
  (with-temp-dir
    (fn [dir]
      (let [settings (assoc service/defaults :port 0 :data-dir dir)
            store    (service/sync-store settings)
            lib      (atom (device/make {:fetch fetch}))
            c        (service/connector settings store lib)
            actions  #(-> (response-for c :get "/api/2/episodes/simon.json?since=0"
                                        :headers {"authorization" "Basic c2ltb246eA=="})
                          :body str json/parse (get "actions")
                          (->> (map (juxt (fn [a] (get a "episode")) (fn [a] (get a "position"))))))]
        (phone-synced! store)
        (is (= [] (actions)) "no URL for the episode while the server has no feed")
        (device/step! lib (store/backend store))
        (is (= [["https://a.example/1.mp3" 600]] (actions)) "named by the feed that the library fetched")))))
