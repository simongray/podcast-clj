(ns podcast-server.device-test
  "The server as one of the listener's devices, on a temporary sync folder
  with a feed served by a stub: its library follows a phone's profile and
  fetches the feed, and the gpodder API names the phone's episode by it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.library.impl :as library.impl]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.test-support :refer [failed fixture sync! with-temp-dir]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.secret :as secret]
            [io.pedestal.connector.test :refer [response-for]]
            [podcast-server.device :as device]
            [podcast-server.service :as service]))

(def url "https://a.example/rss")

(def id
  [(guid/from-url url) "a1"])

(def feed
  (rss/parse (str "<rss version=\"2.0\"><channel><title>A</title>"
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
  (sync! (-> (profile/make "phone")
             (profile/subscribe (first id) {:url url :title "A"} #inst "2026-01-01T01:00:00Z")
             (profile/progress id 600 3600 #inst "2026-01-01T01:00:01Z"))
         [(library/backend store)]
         {:at #inst "2026-01-01T01:00:02Z"}))

(deftest the-library-follows-the-listener
  (with-temp-dir
    (fn [dir]
      (let [store (service/sync-store {:data-dir dir})]
        (phone-synced! store)
        (let [library (device/step! (atom (device/make {:fetch fetch})) (library/backend store))]
          (is (nil? (failed (:sync library))))
          (is (= (feeds/slim feed) (get-in library [:feeds (first id) :feed])) "the slim feed of the phone's subscription")
          (is (= 600 (:position (profile/episode-entry (:profile library) id))) "the phone's progress")
          (is (.isFile (io/file dir "sync" "podcast-server.edn")) "the library's own document"))))))

(deftest a-restart
  (with-temp-dir
    (fn [dir]
      (let [store   (service/sync-store {:data-dir dir})
            backend (library/backend store)
            data    (folder/store dir)
            asked   (atom [])
            fetch   (fn [u _] (swap! asked conj u) {:status 200 :url u :feed feed :etag "\"v1\""})]
        (phone-synced! store)
        (device/save! (device/step! (atom (device/make {:fetch fetch})) backend) data)
        (is (= [url] @asked))
        (reset! asked [])
        (let [lib     (atom (device/restore! (device/make {:fetch fetch :store data}) data))
              library (device/step! lib backend)]
          (is (= [] @asked) "no fetch, since the saved feed isn't due yet")
          (is (= "\"v1\"" (get-in library [:feeds (first id) :etag])))
          (is (not (library/loaded? library (first id))) "the feed stays in its document")
          (is (= [{:rss/guid {:content "a1"} :rss/enclosure {:url "https://a.example/1.mp3" :type "audio/mpeg" :length 1}}]
                 (get-in library [::device/known (first id) :feed :rss/items]))
              "and the server keeps its skeleton")
          (is (= (feeds/slim feed) (get-in (library/load! library data [(first id)]) [:feeds (first id) :feed])))
          (is (= 600 (:position (profile/episode-entry (:profile library) id))) "the profile, from the sync documents"))
        (spit (io/file dir library.impl/saved-name) "{:feeds")
        (is (= {} (:feeds (device/restore! (device/make) data))) "a file that can't be read is left out")))))

(deftest a-saved-library-reads-back
  (with-temp-dir
    (fn [dir]
      (let [data    (folder/store dir)
            show    (rss/parse (fixture "feeds/namespace-example.xml"))
            library (library/subscribe (device/make) "https://example.com/feed.xml" show)]
        (device/save! library data)
        (let [restored (device/restore! (device/make) data)]
          (is (= (select-keys library device/saved-keys)
                 (select-keys (library/load! restored data :all) device/saved-keys)))
          (is (= 3 (count (get-in restored [::device/known (first (keys (:feeds library))) :feed :rss/items])))
              "the skeleton of the feed, read once"))))))

(deftest the-gpodder-api-names-episodes-by-the-feeds
  (with-temp-dir
    (fn [dir]
      (let [settings    (assoc service/defaults :port 0 :data-dir dir)
            store       (service/sync-store settings)
            lib         (atom (device/make {:fetch fetch}))
            c           (service/connector settings store lib)
            credentials {"authorization" (secret/reveal (http/authorization {:username "simon" :password "x"}))}
            answer      #(:body (response-for c :get "/api/2/episodes/simon.json?since=0" :headers credentials))
            actions     (fn []
                          (map (juxt #(get % "episode") #(get % "position"))
                               (get (json/parse (str (answer))) "actions")))]
        (phone-synced! store)
        (is (= [] (actions)) "no URL for the episode while the server has no feed")
        (device/step! lib (library/backend store))
        (is (= [["https://a.example/1.mp3" 600]] (actions)) "named by the feed that the library fetched")
        (device/unload! lib (folder/store dir))
        (is (not (library/loaded? @lib (first id))))
        (is (= [["https://a.example/1.mp3" 600]] (actions)) "and by its skeleton once the feed is let go")))))

(deftest the-gpodder-api-after-a-restart
  (with-temp-dir
    (fn [dir]
      (let [settings    (assoc service/defaults :port 0 :data-dir dir)
            store       (service/sync-store settings)
            started     #(service/connector settings store (atom (device/make {:fetch fetch})))
            credentials {"authorization" (secret/reveal (http/authorization {:username "simon" :password "x"}))}
            devices     #(json/parse (str (:body (response-for % :get "/api/2/devices/simon.json" :headers credentials))))]
        (response-for (started) :post "/api/2/devices/simon/phone.json"
                      :body (json/write-str {"caption" "My phone" "type" "mobile"}) :headers credentials)
        (is (.isFile (io/file dir device/gpodder-book-name)) "the book, in the data folder")
        (is (= [{"id" "phone" "caption" "My phone" "type" "mobile" "subscriptions" 0}] (devices (started)))
            "a device that registered before the restart")
        (spit (io/file dir device/gpodder-book-name) "{:index")
        (is (= [] (devices (started))) "a book that can't be read is left out")))))
