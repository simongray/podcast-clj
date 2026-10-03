(ns podcast-server.device-test
  "The server as one of the listener's devices, on a temporary sync folder
  with a feed served by a stub: its library follows a phone's profile and
  fetches the feed, and the gpodder API names the phone's episode by it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.test-support :refer [fixture with-temp-dir]]
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
          (is (= (feeds/slim feed) (get-in library [:feeds (first id) :feed])) "the slim feed of the phone's subscription")
          (is (= 600 (:position (profile/episode-entry (:profile library) id))) "the phone's progress")
          (is (.isFile (io/file dir "sync" "podcast-server.edn")) "the library's own document"))))))

(deftest a-restart
  (with-temp-dir
    (fn [dir]
      (let [store   (service/sync-store {:data-dir dir})
            backend (store/backend store)
            saved   (io/file dir "library.edn")
            asked   (atom [])
            fetch   (fn [u _] (swap! asked conj u) {:status 200 :url u :feed feed :etag "\"v1\""})]
        (phone-synced! store)
        (device/save! (device/step! (atom (device/make {:fetch fetch})) backend) saved)
        (is (= [url] @asked))
        (reset! asked [])
        (let [library (device/step! (atom (device/restore! (device/make {:fetch fetch}) saved)) backend)]
          (is (= [] @asked) "no fetch, since the saved feed isn't due yet")
          (is (= "\"v1\"" (get-in library [:feeds (first id) :etag])))
          (is (= (feeds/slim feed) (get-in library [:feeds (first id) :feed])))
          (is (= 600 (:position (profile/episode-entry (:profile library) id))) "the profile, from the sync documents"))
        (spit saved "{:feeds")
        (is (= {} (:feeds (device/restore! (device/make) saved))) "a file that can't be read is left out")))))

(deftest a-saved-library-reads-back
  (with-temp-dir
    (fn [dir]
      (let [saved   (io/file dir "library.edn")
            show    (podcast/parse (fixture "feeds/namespace-example.xml"))
            library (library/subscribe (device/make) "https://example.com/feed.xml" show)]
        (device/save! library saved)
        (is (= (select-keys library device/saved-keys)
               (select-keys (device/restore! (device/make) saved) device/saved-keys)))))))

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
