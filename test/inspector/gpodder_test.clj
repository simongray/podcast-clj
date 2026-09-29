(ns inspector.gpodder-test
  "The inspector's gpodder API, driven through Pedestal's interceptor chain
  on a temporary folder: an app of that API and a device of podcast-clj
  that syncs over WebDAV kept in step through the one folder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.test-support :refer [with-temp-dir]]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
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
    (with-redefs [http/call! through-the-inspector]
      (let [backend (store/backend (webdav/store "http://inspector/dav/"))
            browser (sync/sync! (player/state "browser") [backend] #inst "2026-01-01T02:00:00Z")
            s       (:state browser)]
        (is (nil? (:failed browser)))
        (is (player/subscribed? s g) "the other app's subscription, over WebDAV")
        (is (= 300 (:position (player/episode s [g "a1"]))))
        (let [s'      (player/progress s [g "a1"] 1200 3600 #inst "2026-01-01T03:00:00Z")
              browser (sync/sync! s' [backend] #inst "2026-01-01T03:00:00Z" {:ctx (:ctx browser)})]
          (is (nil? (:failed browser)))
          (is (= [["a1" 1200]]
                 (map (juxt #(get % "guid") #(get % "position"))
                      (get (second (app cookie :get "/api/2/episodes/simon.json?since=1767229200")) "actions")))
              "and the browser's progress, told to the other app"))))))
