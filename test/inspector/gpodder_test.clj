(ns inspector.gpodder-test
  "The inspector's gpodder API, driven through Pedestal's interceptor chain
  on a temporary folder: an app of that API and a device of podcast-clj
  that syncs over WebDAV kept in step through the one folder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dk.simongray.like-minded.store.webdav :as webdav]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.test-support :refer [failed with-temp-dir]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch :as http]
            [inspector.service :as service]
            [io.pedestal.connector.test :refer [response-for]]))

(def ^:dynamic *dir* nil)

(def ^:dynamic *connector* nil)

(use-fixtures :each
  (fn [f]
    (with-temp-dir
      (fn [dir]
        (binding [*dir*       dir
                  *connector* (service/connector 0 {:sync-dir (io/file dir "sync")})]
          (f))))))

(defn- call
  [method path & {:keys [body headers]}]
  (apply response-for *connector* method path
         (concat (when body [:body body]) (when headers [:headers headers]))))

(defn- app
  "What the inspector answers the other app's `method` at `path` with the
  JSON of `body`, in its session `cookie`: `[status data]`."
  [cookie method path & [body]]
  (let [{:keys [status body]} (call method path
                                    :headers (cond-> {"Cookie" cookie}
                                               body (assoc "Content-Type" "application/json"))
                                    :body (when body (json/write-str body)))]
    [status (json/parse (str body))]))

(defn- through-the-inspector
  "The library's HTTP client stood in by the connector: a request map sent
  through Pedestal, its answer in the client's shape."
  [{:keys [method url headers body]}]
  (let [response (call method (str/replace url #"^https?://[^/]+" "") :body body :headers headers)]
    (update response :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %))))

(deftest another-app-and-a-browser-device
  (let [feed   "https://a.example/rss"
        g      (guid/from-url feed)
        login  (call :post "/api/2/auth/simon/login.json" :headers {"Authorization" "Basic c2ltb246eA=="})
        cookie (first (str/split (get-in login [:headers "Set-Cookie"]) #";"))]
    (is (= 200 (:status login)))
    (is (= 401 (:status (call :get "/api/2/devices/simon.json"))) "a request without credentials is challenged")
    (is (= 200 (first (app cookie :post "/api/2/devices/simon/antennapod.json" {"caption" "Phone" "type" "mobile"}))))
    (is (= 200 (first (app cookie :post "/api/2/subscriptions/simon/antennapod.json" {"add" [feed]}))))
    (is (= 200 (first (app cookie :post "/api/2/episodes/simon.json"
                           [{"podcast" feed "episode" "https://a.example/1.mp3" "guid" "a1" "action" "play"
                             "timestamp" "2026-01-01T01:00:00" "started" 0 "position" 300 "total" 3600
                             "device" "antennapod"}]))))
    (is (.isFile (io/file *dir* "sync" "inspector-gpodder.edn")) "the server writes its document to the folder")
    (with-redefs [http/send! through-the-inspector]
      (let [backend (sync/backend (webdav/store "http://inspector/dav/"))
            browser (sync/sync! (profile/make "browser") [backend] {:at #inst "2026-01-01T02:00:00Z"})
            s       (:state browser)]
        (is (nil? (failed browser)))
        (is (profile/subscribed? s g) "the other app's subscription, over WebDAV")
        (is (= 300 (:position (profile/episode-entry s [g "a1"]))))
        (let [s'      (profile/progress s [g "a1"] 1200 3600 #inst "2026-01-01T03:00:00Z")
              browser (sync/sync! s' [backend] {:at #inst "2026-01-01T03:00:00Z" :cursors (:cursors browser)})]
          (is (nil? (failed browser)))
          (is (= [["a1" 1200]]
                 (map (juxt #(get % "guid") #(get % "position"))
                      (get (second (app cookie :get "/api/2/episodes/simon.json?since=1767229200")) "actions")))
              "and the browser's progress, told to the other app"))))))

(deftest an-episode-that-a-player-added
  (let [feed   "https://b.example/rss"
        g      (guid/from-url feed)
        id     [g "b1"]
        login  (call :post "/api/2/auth/simon/login.json" :headers {"Authorization" "Basic c2ltb246eA=="})
        cookie (first (str/split (get-in login [:headers "Set-Cookie"]) #";"))]
    (with-redefs [http/send! through-the-inspector]
      (let [backend (sync/backend (webdav/store "http://inspector/dav/"))
            state   (-> (profile/make "browser")
                        (profile/add-remote-item id {:feed-url feed} #inst "2026-01-01T01:00:00Z")
                        (profile/progress id 600 3600 #inst "2026-01-01T01:00:01Z"))]
        (is (nil? (failed (sync/sync! state [backend] {:at #inst "2026-01-01T01:00:02Z"}))))))
    (let [rss    (str "<rss version=\"2.0\"><channel><title>B</title>"
                      "<item><title>One</title><guid>b1</guid>"
                      "<enclosure url=\"https://b.example/1.mp3\" length=\"1\" type=\"audio/mpeg\"/>"
                      "</item></channel></rss>")
          served (fn [{:keys [url] :as request}]
                   (if (= feed url)
                     {:status 200 :url url :headers {} :body rss}
                     (through-the-inspector request)))]
      (with-redefs [http/send! served]
        (is (= [["https://b.example/1.mp3" 600]]
               (map (juxt #(get % "episode") #(get % "position"))
                    (get (second (app cookie :get "/api/2/episodes/simon.json?since=0")) "actions")))
            "named by the feed of the remote item, which the inspector fetches")))))
