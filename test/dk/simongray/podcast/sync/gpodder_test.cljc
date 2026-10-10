(ns dk.simongray.podcast.sync.gpodder-test
  "The gpodder API: the calls as request maps, the answers read as
  gpodder.net and the GPodder Sync app for Nextcloud write them, the names
  of shows and episodes over parsed feeds, the server's rules, and devices
  of podcast-clj and another app kept in step through the server, on both
  platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.sync.gpodder.server :as server]
            [dk.simongray.podcast.test-support :refer [failed fixture run]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def root "https://gpodder.example.org")

(def creds {:username "simon" :password "app pass"})

(deftest requests
  (let [subs (gpodder/request :subscriptions root {:credentials creds :device "phone" :since 42})]
    (is (= [:get "https://gpodder.example.org/api/2/subscriptions/simon/phone.json?since=42"] ((juxt :method :url) subs)))
    (is (= "Basic c2ltb246YXBwIHBhc3M=" (get-in (http/revealed subs) [:headers "Authorization"])))
    (is (secret/hidden? (get-in subs [:headers "Authorization"])))
    (is (not (re-find #"c2ltb246" (pr-str subs))) "a request that is printed does not show the password")
    (is (nil? (get-in subs [:headers "User-Agent"])) "the sender gives the User-Agent"))
  (is (= "https://gpodder.example.org/api/2/episodes/simon.json?since=0"
         (:url (gpodder/request :episodes (str root "/") {:credentials creds})))
      "since 0 when none is given, and one slash after the root")
  (is (= [:post "https://gpodder.example.org/api/2/auth/simon/login.json"]
         ((juxt :method :url) (gpodder/request :login root {:credentials creds}))))
  (let [register (gpodder/request :register root {:credentials creds :device "phone 1" :body {"caption" "Phone"}})]
    (is (= "https://gpodder.example.org/api/2/devices/simon/phone%201.json" (:url register)))
    (is (= "{\"caption\":\"Phone\"}" (:body register)))
    (is (= "application/json" (get-in register [:headers "Content-Type"]))))
  (is (= "sessionid=abc"
         (get-in (http/revealed (gpodder/request :episodes root {:credentials creds :session "abc"})) [:headers "Cookie"])))
  (is (secret/hidden? (get-in (gpodder/request :episodes root {:credentials creds :session "abc"}) [:headers "Cookie"])))
  (is (= "Basic bGF0ZXI6eA=="
         (get-in (http/revealed (gpodder/request :episodes root {:credentials (constantly {:username "later" :password "x"})}))
                 [:headers "Authorization"]))
      "credentials may be a function")
  (testing "the paths of the GPodder Sync app for Nextcloud"
    (let [nc #(gpodder/request % "https://cloud.example.org" {:api :nextcloud :credentials creds :since 7})]
      (is (= [:get "https://cloud.example.org/index.php/apps/gpoddersync/subscriptions?since=7"] ((juxt :method :url) (nc :subscriptions))))
      (is (= [:post "https://cloud.example.org/index.php/apps/gpoddersync/subscription_change/create"] ((juxt :method :url) (nc :update-subscriptions))))
      (is (= [:get "https://cloud.example.org/index.php/apps/gpoddersync/episode_action?since=7"] ((juxt :method :url) (nc :episodes))))
      (is (= [:post "https://cloud.example.org/index.php/apps/gpoddersync/episode_action/create"] ((juxt :method :url) (nc :update-episodes))))
      (is (thrown? #?(:clj Exception :cljs js/Error) (nc :login)) "the app has no login")))
  (testing "the paths of a server with paths of its own"
    (let [paths {:subscriptions "/sync/{user}/devices/{device}/shows" :episodes "/sync/{user}/actions"}
          own   #(:url (gpodder/request % "https://sync.example.org/"
                                        {:api paths :credentials {:username "si mon" :password "x"} :device "my/phone"}))]
      (is (= "https://sync.example.org/sync/si%20mon/devices/my%2Fphone/shows?since=0" (own :subscriptions)))
      (is (= "https://sync.example.org/sync/si%20mon/actions?since=0" (own :episodes)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (own :update-episodes)) "a call that the map leaves out"))
    (is (= (:nextcloud gpodder/apis) (gpodder/paths :nextcloud)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (gpodder/request :episodes root {:api :opodsync :credentials creds}))
        "a name that apis does not have")))

(def mygpo-actions
  "Episode actions as gpodder.net writes them: a time without a zone,
  sometimes with a fraction, a guid that can be null, and positions that
  are null but for a play."
  "{\"actions\": [
     {\"podcast\": \"http://example.com/feed.rss\", \"episode\": \"http://example.com/files/s01e20.mp3\",
      \"guid\": null, \"action\": \"download\", \"timestamp\": \"2009-12-12T09:00:00\", \"device\": \"gpodder_abcdef123\",
      \"started\": null, \"position\": null, \"total\": null},
     {\"podcast\": \"http://example.org/podcast.php\", \"episode\": \"http://ftp.example.org/foo.ogg\",
      \"guid\": \"foo-1\", \"action\": \"play\", \"timestamp\": \"2009-12-12T09:05:21.125000\", \"device\": \"n900\",
      \"started\": 15, \"position\": 120, \"total\": 500}],
    \"timestamp\": 12347}")

(def nextcloud-actions
  "Episode actions as the GPodder Sync app for Nextcloud writes them: the
  name in upper case, a zone of +00:00, and no device."
  "{\"actions\": [{\"podcast\": \"https://a/rss\", \"episode\": \"https://a/1.mp3\", \"guid\": \"a1\", \"action\": \"PLAY\",
                   \"timestamp\": \"2026-01-01T02:00:00+00:00\", \"started\": 0, \"position\": 3590, \"total\": 3600}],
    \"timestamp\": 1767232800}")

(deftest responses
  (let [{:keys [actions timestamp]} (gpodder/response :episodes {:status 200 :body mygpo-actions})]
    (is (= 12347 timestamp))
    (is (= [["download" #inst "2009-12-12T09:00:00Z" nil nil] ["play" #inst "2009-12-12T09:05:21.125Z" 120 "foo-1"]]
           (map (juxt :action :timestamp :position :guid) actions))
        "a time without a zone is UTC"))
  (is (= [["play" #inst "2026-01-01T02:00:00Z" nil]]
         (map (juxt :action :timestamp :device) (:actions (gpodder/response :episodes {:status 200 :body nextcloud-actions}))))
      "a name in any case")
  (is (= {:add ["http://example.com/feed.rss"] :remove ["http://example.org/podcast.php"] :timestamp 12347}
         (gpodder/response :subscriptions {:status 200 :body "{\"add\": [\"http://example.com/feed.rss\"], \"remove\": [\"http://example.org/podcast.php\"], \"timestamp\": 12347}"})))
  (is (= {:timestamp 1337 :update-urls [["http://feeds2.example.com/feed.rss" "http://feeds.example.com/feed.rss"] ["foo" nil]]}
         (gpodder/response :update-subscriptions
                           {:status 200 :body "{\"timestamp\": 1337, \"update_urls\": [[\"http://feeds2.example.com/feed.rss\", \"http://feeds.example.com/feed.rss\"], [\"foo\", null]]}"}))
      "a URL refused as null")
  (is (= {:timestamp 1767232800 :update-urls []} (gpodder/response :update-episodes {:status 200 :body "{\"timestamp\": 1767232800}"}))
      "the Nextcloud app sends no update_urls")
  (is (nil? (gpodder/response :register {:status 200 :body ""})))
  (is (= "abc123" (gpodder/response :login {:status 200 :headers {"set-cookie" ["sessionid=abc123; expires=Tue, 28 Sep 2027 10:00:00 GMT; Path=/"]}})))
  (testing "a value of another type than the API has reads as none"
    (let [read (fn [call body] (gpodder/response call {:status 200 :body body}))]
      (is (= {:actions [] :timestamp 5} (read :episodes "{\"actions\": 5, \"timestamp\": \"5\"}")))
      (is (= {:add [] :remove [] :timestamp nil}
             (read :subscriptions "{\"add\": 5, \"remove\": {\"a\": 1}, \"timestamp\": 1e30}")))
      (is (= {:timestamp nil :update-urls [["a" nil] ["b" "https://b.example/rss"]]}
             (read :update-subscriptions
                   "{\"timestamp\": -5, \"update_urls\": [5, [\"a\"], [6, \"x\"], [\"b\", \"https://b.example/rss\"]]}")))
      (is (= [{:action "play" :timestamp nil :position nil :total 3600}]
             (map #(select-keys % [:action :timestamp :position :total])
                  (:actions (read :episodes "{\"actions\": [{\"action\": \"play\", \"timestamp\": 1e30, \"position\": 1e30, \"total\": 3600}]}"))))
          "a time and a position that no clock and no episode reach")))
  (let [failure (fn [answer] (try (gpodder/response :episodes answer) nil
                                  (catch #?(:clj Exception :cljs :default) e [(:status (ex-data e)) (ex-message e)])))]
    (is (= [401 "The server answered 401 to episodes: the credentials were refused"] (failure {:status 401 :body ""})))
    (is (= 500 (first (failure {:status 500 :body ""}))))
    (is (= 200 (first (failure {:status 200 :body "<html>a login page</html>"}))) "an answer that is not JSON")))

(def example
  (delay (rss/parse (fixture "feeds/namespace-example.xml"))))

(deftest names-over-parsed-feeds
  (let [{:keys [feed-guid-fn feed-url-fn episode-id-fn episode-url-fn]} (gpodder/identities [{:url "https://example.com/feed.xml" :feed @example}])]
    (is (= "y0ur-gu1d-g035-h3r3" (feed-guid-fn "https://example.com/feed.xml")) "the feed's declared guid")
    (is (= "y0ur-gu1d-g035-h3r3" (feed-guid-fn "http://EXAMPLE.com/feed.xml/")) "whatever the scheme, case and slashes")
    (is (nil? (feed-guid-fn "https://elsewhere.example/feed.xml")))
    (is (= "https://example.com/feed.xml" (feed-url-fn "y0ur-gu1d-g035-h3r3")))
    (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0002"]
           (episode-id-fn "https://example.com/feed.xml" "https://example.com/file-02.mp3" nil))
        "by the URL of its media")
    (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0001"]
           (episode-id-fn "https://example.com/feed.xml" "https://cdn.example/other.mp3" "https://example.com/ep0001"))
        "by its guid")
    (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/gone.mp3"]
           (episode-id-fn "https://example.com/feed.xml" "https://example.com/gone.mp3" nil))
        "an episode the feed leaves out, as podcast-clj names it")
    (is (nil? (episode-id-fn "https://elsewhere.example/feed.xml" "https://elsewhere.example/1.mp3" nil)))
    (is (= "https://example.com/file-03.mp3" (episode-url-fn ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"])))
    (is (nil? (episode-url-fn ["y0ur-gu1d-g035-h3r3" "no-such-item"])))))

(defn- ring-send
  "A send, as `http/send!` takes a request map, to the Ring `handler`: the
  answer with its header names in lower case and each value in a vector,
  as the client's are. It reads the hidden headers of the request, as a
  client does that is not `http/send!`."
  [handler]
  (fn [request]
    (let [{:keys [method url headers body]} (http/revealed request)
          [_ path q] (re-matches #"https?://[^/]+([^?]*)(?:\?(.*))?" url)
          answer     (handler {:request-method method :uri path :query-string q :body body
                               :headers (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers)})]
      (update answer :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %)))))

(defn- serve
  "A server over a fresh state for the user simon, with the password `app
  pass`, whose time is `clock`, an atom of the instant: `[state send]`."
  [clock]
  (let [state (atom (profile/make "server"))]
    [state (ring-send (server/handler {:state           state
                                       :authorized-pred (fn [user password] (= [user password] ["simon" "app pass"]))
                                       :now-fn          #(deref clock)}))]))

(defn- ask
  "What the server behind `send` answers `method` at `path` with the JSON of
  `body`, as the other app asks with `headers`: `[status data]`."
  ([send method path]
   (ask send method path nil {}))
  ([send method path body]
   (ask send method path body {}))
  ([send method path body headers]
   (let [{:keys [status body]} (send (cond-> {:method method :url (str root path)
                                              :headers (merge {"Authorization" "Basic c2ltb246YXBwIHBhc3M="} headers)}
                                       (some? body) (assoc :body (json/write-str body))))]
     [status (json/parse (str body))])))

(def feed-a "https://a.example/rss")

(def g-a (guid/from-url feed-a))

(deftest the-server
  (let [clock      (atom #inst "2026-01-01T00:00:00Z")
        [_ send]   (serve clock)
        at-seconds #(quot (inst-ms @clock) 1000)]
    (testing "credentials"
      (let [{:keys [status headers]} (send {:method :get :url (str root "/api/2/devices/simon.json") :headers {}})]
        (is (= 401 status))
        (is (= ["Basic realm=\"podcast-clj\""] (get headers "www-authenticate")) "a challenge, which gPodder waits for"))
      (is (= 401 (first (ask send :get "/api/2/devices/simon.json" nil {"Authorization" "Basic c2ltb246bm9wZQ=="}))) "a wrong password")
      (is (= 400 (first (ask send :get "/api/2/devices/other.json"))) "another user's path")
      (let [login  (send {:method :post :url (str root "/api/2/auth/simon/login.json") :headers {"Authorization" "Basic c2ltb246YXBwIHBhc3M="}})
            cookie (first (str/split (first (get-in login [:headers "set-cookie"])) #";"))]
        (is (= 200 (:status login)))
        (is (= 200 (:status (send {:method :get :url (str root "/api/2/devices/simon.json") :headers {"Cookie" cookie}})))
            "the session of a login, as AntennaPod keeps it")))
    (testing "devices"
      (is (= 200 (first (ask send :post "/api/2/devices/simon/phone.json" {"caption" "My phone" "type" "mobile"}))))
      (is (= 400 (first (ask send :post "/api/2/devices/simon/phone.json" {"type" "toaster"}))))
      (is (= [{"id" "phone" "caption" "My phone" "type" "mobile" "subscriptions" 0}] (second (ask send :get "/api/2/devices/simon.json")))))
    (testing "subscriptions"
      (is (= [200 {"timestamp" (at-seconds) "update_urls" []}]
             (ask send :post "/api/2/subscriptions/simon/phone.json" {"add" [feed-a "https://b.example/rss"]})))
      (is (= 400 (first (ask send :post "/api/2/subscriptions/simon/phone.json" {"add" [feed-a] "remove" [feed-a]}))))
      (is (= 400 (first (ask send :post "/api/2/subscriptions/simon/phone.json" ["not" "an" "object"]))))
      (is (= 400 (first (ask send :get "/api/2/subscriptions/simon/phone.json"))) "since is required")
      (reset! clock #inst "2026-01-01T01:00:00Z")
      (ask send :post "/api/2/subscriptions/simon/laptop.json" {"remove" ["https://b.example/rss"]})
      (is (= {"add" [feed-a] "remove" [] "timestamp" (at-seconds)}
             (second (ask send :get "/api/2/subscriptions/simon/tablet.json?since=0")))
          "a device that has seen nothing gets the list, and no removal")
      (is (= {"add" [] "remove" ["https://b.example/rss"] "timestamp" (at-seconds)}
             (second (ask send :get "/api/2/subscriptions/simon/phone.json?since=1767229200")))
          "what arrived since, with one list for every device"))
    (testing "episode actions"
      (reset! clock #inst "2026-01-01T02:00:00Z")
      (let [late {"podcast" feed-a "episode" "https://a.example/1.mp3" "guid" "a1" "action" "play"
                  "timestamp" "2025-12-31T12:00:00" "started" 0 "position" 60 "total" 3600 "device" "phone"}]
        (is (= 200 (first (ask send :post "/api/2/episodes/simon.json" [late {"podcast" feed-a "action" "play"}]))))
        (is (= [{"podcast" feed-a "episode" "https://a.example/1.mp3" "guid" "a1" "device" "phone" "action" "play"
                 "timestamp" "2025-12-31T12:00:00" "started" 60 "position" 60 "total" 3600}]
               (get (second (ask send :get "/api/2/episodes/simon.json?since=1767232800")) "actions"))
            "an action sent late reaches a device that asked after the time it names")
        (is (empty? (get (second (ask send :get "/api/2/episodes/simon.json?since=1767236400")) "actions")))
        (is (= 1 (count (get (second (ask send :get (str "/api/2/episodes/simon.json?since=0&podcast=" feed-a))) "actions"))))
        (is (empty? (get (second (ask send :get "/api/2/episodes/simon.json?since=0&device=laptop")) "actions")))
        (is (= 400 (first (ask send :post "/api/2/episodes/simon.json" {"not" "a list"}))))))
    (is (= 404 (first (ask send :get "/api/2/lists/simon.json"))))
    (is (= 405 (first (ask send :delete "/api/2/devices/simon.json"))))))

(def t1 #inst "2026-01-01T01:00:00Z")
(def t2 #inst "2026-01-01T02:00:00Z")
(def t3 #inst "2026-01-01T03:00:00Z")
(def t4 #inst "2026-01-01T04:00:00Z")

(deftest the-rules-of-the-server
  (let [clock        (atom t1)
        [state send] (serve clock)
        play         (fn [item time position]
                       (cond-> {"podcast" feed-a "episode" (str "https://a.example/" item ".mp3") "guid" item
                                "action" "play" "position" position "total" 3600 "device" "phone"}
                         time (assoc "timestamp" time)))
        heard        (fn [item] ((juxt :position :updated-at) (profile/episode-entry @state ["declared-guid" item])))]
    (testing "one URL under two guids, of which one is live"
      (ask send :get "/api/2/subscriptions/simon/phone.json?since=0")
      (reset! clock t2)
      (swap! state #(-> %
                        (profile/subscribe "guid-of-url" {:url feed-a :title "A"} t1)
                        (profile/subscribe "declared-guid" {:url "http://A.example/rss/" :title "A"} t1)
                        (profile/unsubscribe "guid-of-url" t1)))
      (is (= {"add" ["http://A.example/rss/"] "remove" []}
             (select-keys (second (ask send :get "/api/2/subscriptions/simon/phone.json?since=1767229200")) ["add" "remove"]))
          "is not removed"))
    (testing "the time of an action"
      (reset! clock t3)
      (ask send :post "/api/2/episodes/simon.json" [(play "a1" "2026-01-01T02:30:00" 500)])
      (is (= 200 (first (ask send :post "/api/2/episodes/simon.json"
                             [(play "a1" "21/09/2020 10:00" 7) (play "a1" 1e30 8) (play "a2" nil 9)]))))
      (is (= [500 #inst "2026-01-01T02:30:00Z"] (heard "a1"))
          "a time that does not read tells nothing of when, so the action is left out")
      (is (= [9 t3] (heard "a2")) "an action without a time counts as made when it arrived"))))

(deftest the-sessions-of-the-server
  (let [clock      (atom t1)
        [_ send]   (serve clock)
        login      (fn [headers]
                     (let [answer (send {:method :post :url (str root "/api/2/auth/simon/login.json") :headers headers})]
                       [(:status answer) (some-> (first (get-in answer [:headers "set-cookie"])) (str/split #";") first)]))
        basic      {"Authorization" "Basic c2ltb246YXBwIHBhc3M="}
        [_ cookie] (login basic)]
    (is (= [200 cookie] (login {"Cookie" cookie})) "a login that carries its session keeps it")
    (is (not= cookie (second (login basic))) "and one without it gets another")
    (reset! clock (time/->inst (+ (inst-ms t1) (* 1000 server/session-seconds))))
    (is (= 401 (first (login {"Cookie" cookie}))) "a session ends after a day")))

(deftest what-the-app-writes-during-a-call
  (let [state (atom (profile/make "server"))
        ;; the app subscribes to B while a step runs, and the step gives the
        ;; state as it took it
        sync! (fn [s]
                (swap! state profile/subscribe "b" {:url "https://b.example/rss" :title "B"} t1)
                s)
        send  (ring-send (server/handler {:state state :authorized-pred (constantly true) :sync sync! :now-fn (constantly t2)}))]
    (ask send :post "/api/2/subscriptions/simon/phone.json" {"add" [feed-a]})
    (is (= #{feed-a "https://b.example/rss"} (set (map :url (vals (profile/subscriptions @state)))))
        "stays, with what the call wrote")))

(defn- phone
  "A phone of podcast-clj subscribed to feed A, 100 seconds into its first
  episode, whose item guid is a1."
  []
  (-> (profile/make "phone")
      (profile/subscribe g-a {:url feed-a :title "A"} t1)
      (profile/progress [g-a "a1"] 100 3600 t1)))

(def media
  "The media of each episode of feed A, as the app knows them."
  {"a1" "https://a.example/1.mp3" "a2" "https://a.example/2.mp3"})

(defn- backend
  [send & {:as opts}]
  (gpodder/backend root (merge {:credentials    creds :send send
                                :episode-url-fn (fn [[g item]] (when (= g g-a) (media item)))}
                               opts)))

(deftest names-over-the-subscriptions
  (let [state (-> (profile/make "phone")
                  (profile/subscribe "guid-of-url" {:url feed-a :title "A"} t1)
                  (profile/unsubscribe "guid-of-url" t2)
                  (profile/subscribe "declared-guid" {:url "http://A.example/rss/" :title "A"} t3))
        named (fn [state] ((:feed-guid-fn (gpodder/namer state {})) feed-a))]
    (is (= ["declared-guid" "declared-guid"]
           [(named state) (named (update state :subscriptions #(into (array-map) (reverse %))))])
        "of two subscriptions with one URL the live one, whatever their order")
    (is (= "later" (named (profile/subscribe state "later" {:url feed-a :title "A"} t4)))
        "and of two live ones, the one that changed last")
    (is (= "gpodder.example" (:name (gpodder/backend "https://simon:p@ss@gpodder.example:8443/" {})))
        "the name of a backend is the domain of its server")))

(deftest a-show-that-a-server-tells
  (let [clock    (atom t3)
        [_ send] (serve clock)
        laptop   (profile/subscribe (profile/make "laptop") g-a {:url feed-a :title "The Show"} t1)]
    (ask send :post "/api/2/subscriptions/simon/antennapod.json" {"add" [feed-a]})
    (run
     #(async/then
       (sync/sync! (profile/make "phone") [(backend send)] {:at t3})
       (fn [{s :state}]
         (is (= [feed-a nil t3] ((juxt :url :title :added-at) (get-in s [:subscriptions g-a])))
             "the server tells the URL of a show, and no title")
         (is (= ["The Show" t1 "laptop"]
                ((juxt :title :added-at :device) (get-in (profile/merge laptop s) [:subscriptions g-a])))
             "so the entry of a device that knows the show wins"))))))

(deftest a-server-needs-its-state-and-its-check
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (server/handler {:state (atom {})}))
      "no handler without :authorized-pred, also in a release build"))

(deftest another-app-through-the-server
  (let [clock    (atom t2)
        [state send] (serve clock)]
    (run
     #(async/then
       (sync/sync! (phone) [(backend send)] {:at t2})
       (fn [first-step]
         (is (nil? (failed first-step)) (pr-str (failed first-step)))
         (is (= #{feed-a} (set (map :url (vals (profile/subscriptions @state))))))
         (is (= 100 (:position (get-in @state [:episodes [g-a "a1"]]))) "the progress, written by the phone")
         (is (= "gpodder:phone" (:device (get-in @state [:episodes [g-a "a1"]])))
             "in a name that no device of podcast-clj has")
         (reset! clock t3)
         ;; the other app hears the first episode to its end and subscribes to B
         (ask send :post "/api/2/subscriptions/simon/antennapod.json" {"add" ["https://b.example/rss"]})
         (ask send :post "/api/2/episodes/simon.json"
              [{"podcast" feed-a "episode" "https://a.example/1.mp3" "guid" "a1" "action" "play"
                "timestamp" "2026-01-01T02:30:00" "started" 3500 "position" 3600 "total" 3600 "device" "antennapod"}])
         (async/then
          (sync/sync! (:state first-step) [(backend send)] {:at t3 :cursors (:cursors first-step)})
          (fn [{s :state :as second-step}]
            (is (nil? (failed second-step)))
            (is (profile/played? s [g-a "a1"]) "played to its end on the other app")
            (is (= "gpodder:antennapod" (:device (profile/episode-entry s [g-a "a1"]))))
            (is (= #{feed-a "https://b.example/rss"} (set (map :url (vals (profile/subscriptions s))))))
            (is (= 2 (count (profile/subscriptions @state))) "and nothing sent back twice")
            (async/then
             (sync/sync! s [(backend send)] {:at t4 :cursors (:cursors second-step)})
             (fn [third-step]
               (is (= s (:state third-step)) "a step with nothing new changes nothing"))))))))))

(deftest what-the-phone-sends
  (let [clock        (atom t2)
        [state send] (serve clock)
        known        (atom media)
        b            (backend send :episode-url-fn (fn [[g item]] (when (= g g-a) (@known item))))
        s            (-> (phone)
                         (profile/subscribe (guid/from-url "https://private.example/rss")
                                            {:url "https://simon:secret@private.example/rss" :title "P"} t1)
                         (profile/progress [g-a "a3"] 50 3600 t1)
                         (profile/progress [g-a "https://a.example/?p=4"] 70 3600 t1)
                         (profile/progress [g-a "a2"] 10 1800 t1)
                         (profile/mark-played [g-a "a2"] t1))
        told         (fn []
                       (let [[_ {actions "actions"}] (ask send :get "/api/2/episodes/simon.json?since=0")]
                         (set (map (fn [{:strs [episode position total]}] [episode position total]) actions))))]
    (run
     #(async/then
       (sync/sync! s [b] {:at t2})
       (fn [{:keys [report cursors] :as step}]
         (is (nil? (failed step)))
         (is (contains? (set (map :url (vals (profile/subscriptions @state)))) "https://private.example/rss")
             "a URL without the credentials it held")
         (is (= [[:gpodder/unnamed-episodes 2]] (map (juxt :id :count) report))
             "the episodes whose media the app does not know, also one whose guid is a URL")
         (is (profile/played? @state [g-a "a2"]) "a played episode as a play to its end")
         (is (= #{["https://a.example/1.mp3" 100 3600] ["https://a.example/2.mp3" 1800 1800]} (told)))
         (swap! known assoc "a3" "https://a.example/3.mp3")
         (async/then
          (sync/sync! s [b] {:at t3 :cursors cursors})
          (fn [{:keys [report]}]
            (is (= [[:gpodder/unnamed-episodes 1]] (map (juxt :id :count) report)))
            (is (contains? (told) ["https://a.example/3.mp3" 50 3600])
                "an episode goes out when the app knows its media"))))))))

(deftest the-time-of-an-action
  (let [id     [g-a "a1"]
        ;; a device with a fast clock, which moves the clock of the phone ahead
        fast   (profile/star (profile/make "laptop") [g-a "a9"] t4)
        s      (-> (profile/merge (phone) fast) (profile/progress id 120 3600 t1))
        action #(gpodder/entry-action id % "https://a.example/rss" "https://a.example/1.mp3" "phone")
        told   (fn [state] ((juxt #(get % "action") #(get % "timestamp")) (action (profile/episode-entry state id))))]
    (is (pos? (compare (:updated-at (profile/episode-entry s id)) t4)) "the stamp is after the clock of the device")
    (is (= ["play" "2026-01-01T01:00:00"] (told s)) "and the action names the time on that clock")
    (is (= ["play" "2026-01-01T02:00:00"] (told (profile/mark-played s id t2))) "the time of the mark as played")
    (is (= ["new" "2026-01-01T03:00:00"] (told (profile/mark-unplayed s id t3))))
    (is (= "2026-01-01T01:00:00" (get (action {:position 10 :updated-at t1 :device "phone"}) "timestamp"))
        "an entry that holds no time of the device tells its stamp")))

(deftest what-came-from-a-server-stays
  (let [clock    (atom t2)
        [_ send] (serve clock)
        posts    (atom [])
        send'    (fn [{:keys [method url body] :as request}]
                   (when (and (= :post method) (str/includes? url "/episodes/"))
                     (swap! posts into (json/parse body)))
                   (send request))
        ;; the laptop of the listener is a device of podcast-clj that the
        ;; phone knows, and an app sends an action in its name
        s        (profile/merge (phone) (crdt/identify (profile/make "laptop") {:name "Laptop"} t1))]
    (ask send :post "/api/2/episodes/simon.json"
         [{"podcast" feed-a "episode" "https://a.example/2.mp3" "guid" "a2" "action" "play"
           "timestamp" "2026-01-01T01:30:00" "position" 300 "total" 1800 "device" "laptop"}])
    (run
     #(async/then
       (sync/sync! s [(backend send')] {:at t2})
       (fn [{s :state :keys [cursors]}]
         (is (= [300 "gpodder:laptop"] ((juxt :position :device) (profile/episode-entry s [g-a "a2"]))))
         (reset! posts [])
         (async/then
          (sync/sync! s [(backend send')] {:at t3 :cursors cursors})
          (fn [{s :state}]
            (is (= [] @posts) "no push sends it back")
            (is (nil? (get-in (crdt/acknowledge (profile/make "tablet") [s] t3) [:devices "tablet" :seen "laptop"]))
                "and it is not what the laptop wrote"))))))))

(deftest episodes-the-app-does-not-know-yet
  (let [clock        (atom t2)
        [_ send]     (serve clock)
        knows        (atom #{g-a})
        backend      #(backend send :episode-id-fn (fn [podcast media item]
                                                      (let [g (guid/from-url podcast)]
                                                        (when (@knows g) [g (or item media)]))))
        b-play       {"podcast" "https://b.example/rss" "episode" "https://b.example/1.mp3" "guid" "b1" "action" "play"
                      "timestamp" "2026-01-01T01:30:00" "started" 0 "position" 700 "total" 3600 "device" "antennapod"}]
    (ask send :post "/api/2/episodes/simon.json" [b-play])
    (run
     #(async/then
       (sync/sync! (phone) [(backend)] {:at t2})
       (fn [first-step]
         (is (= [[:gpodder/unknown-episodes 1]] (map (juxt :id :count) (:report first-step))))
         (is (nil? (profile/episode-entry (:state first-step) [(guid/from-url "https://b.example/rss") "b1"])))
         (async/then
          (sync/sync! (:state first-step) [(backend)] {:at t3 :cursors (:cursors first-step)})
          (fn [second-step]
            (is (= 1 (count (:pending (get (:cursors second-step) "gpodder.example.org"))))
                "the server tells the action again, and it waits once")
            (is (empty? (:report second-step)) "and the report told of it before")
            (swap! knows conj (guid/from-url "https://b.example/rss"))
            (async/then
             (sync/sync! (:state second-step) [(backend)] {:at t4 :cursors (:cursors second-step)})
             (fn [third-step]
               (is (= 700 (:position (profile/episode-entry (:state third-step) [(guid/from-url "https://b.example/rss") "b1"])))
                   "once the app knows the feed, the action that waited")
               (is (empty? (:report third-step)))
               (is (nil? (:pending (get (:cursors third-step) "gpodder.example.org")))))))))))))

(deftest times-after-the-pull
  (let [play    (fn [device time position]
                  {"podcast" feed-a "episode" "https://a.example/1.mp3" "guid" "a1" "action" "play"
                   "timestamp" time "position" position "total" 3600 "device" device})
        ;; a server whose clock is years ahead, with the actions of a tablet
        ;; that writes milliseconds for seconds and of a watch that is ten
        ;; minutes fast
        answers {:subscriptions {"add" ["https://b.example/rss"] "remove" [] "timestamp" 1790000005}
                 :episodes      {"actions"   [(play "tablet" 1790000005000 500)
                                              (play "watch" "2026-01-01T02:10:00" 600)
                                              (play "watch" "2026-01-01T02:00:30" 700)
                                              (play "radio" "yesterday" 800)
                                              (dissoc (play "radio" "2026-01-01T01:00:00" 900) "episode")]
                                 "timestamp" 1790000005}
                 :update        {"timestamp" 1790000005 "update_urls" []}}
        send    (fn [{:keys [method url]}]
                  {:status  200
                   :headers {}
                   :body    (json/write-str (answers (cond
                                                       (= :post method)                      :update
                                                       (str/includes? url "/subscriptions/") :subscriptions
                                                       :else                                 :episodes)))})
        b       (backend send)
        g-b     (guid/from-url "https://b.example/rss")
        heard   #((juxt :position :updated-at :device) (profile/episode-entry % [g-a "a1"]))
        soon?   #(<= (inst-ms (:clock %1)) (+ (inst-ms %2) document/skew-tolerance))]
    (run
     #(async/then
       (sync/sync! (phone) [b] {:at t2})
       (fn [{s :state :keys [report cursors]}]
         (is (= [700 #inst "2026-01-01T02:00:30Z" "gpodder:watch"] (heard s))
             "an action within a minute of the pull counts")
         (is (= #{[:gpodder/future-actions "tablet" 1] [:gpodder/future-actions "watch" 1] [:gpodder/malformed-actions nil 2]}
                (set (map (juxt :id :device :count) report)))
             "the others change nothing, and the report names their devices and counts what does not read")
         (is (= t2 (:added-at (get-in s [:subscriptions g-b])))
             "a change to the subscriptions counts as made at the pull")
         (is (soon? s t2) "so the clock of the state stays with the clock of the device")
         (async/then
          (sync/sync! s [b] {:at t3 :cursors cursors})
          (fn [{s :state :keys [report]}]
            (is (= [600 #inst "2026-01-01T02:10:00Z" "gpodder:watch"] (heard s)) "an action whose time has come")
            (is (= #{[:gpodder/future-actions "tablet"] [:gpodder/malformed-actions nil]}
                   (set (map (juxt :id :device) report))))
            (is (soon? s t3)))))))))

(deftest an-action-that-arrives-before-its-time
  (let [clock        (atom t2)
        [state send] (serve clock)]
    (ask send :post "/api/2/episodes/simon.json"
         [{"podcast" feed-a "episode" "https://a.example/1.mp3" "guid" "a1" "action" "play"
           "timestamp" 1790000005000 "position" 500 "total" 3600 "device" "tablet"}])
    (is (= [500 t2 "gpodder:tablet"] ((juxt :position :updated-at :device) (profile/episode-entry @state [g-a "a1"])))
        "it counts as made when it arrived")
    (is (= t2 (:clock @state)))))

(deftest the-first-sync-joins-two-lists
  (let [clock        (atom t2)
        [state send] (serve clock)
        s            (-> (phone)
                         (profile/subscribe (guid/from-url "https://b.example/rss") {:url "https://b.example/rss" :title "B"} t1)
                         (profile/unsubscribe (guid/from-url "https://b.example/rss") t1))]
    (ask send :post "/api/2/subscriptions/simon/antennapod.json" {"add" ["https://b.example/rss"]})
    (run
     #(async/then
       (sync/sync! s [(backend send)] {:at t2})
       (fn [{s :state}]
         (is (profile/subscribed? s (guid/from-url "https://b.example/rss"))
             "a show the other app has, which this device left long ago")
         (is (= 2 (count (profile/subscriptions @state))) "and still on the server"))))))

(defn- nextcloud
  "A send in the shape of the GPodder Sync app for Nextcloud, over the server
  behind `send`: its paths, the name of an action in upper case, a zone of
  +00:00 and no update_urls."
  [send]
  (fn [{:keys [url] :as request}]
    (let [[_ base path q] (re-matches #"(https?://[^/]+)/index\.php/apps/gpoddersync/([^?]+)(?:\?(.*))?" url)
          api            (case path
                           "subscriptions"               "/api/2/subscriptions/simon/nextcloud.json"
                           "subscription_change/create"  "/api/2/subscriptions/simon/nextcloud.json"
                           "episode_action"              "/api/2/episodes/simon.json"
                           "episode_action/create"       "/api/2/episodes/simon.json")
          answer         (send (assoc request :url (str base api (when q (str "?" q)))))
          data           (json/parse (str (:body answer)))]
      (assoc answer :body (json/write-str
                           (cond-> (dissoc data "update_urls")
                             (get data "actions")
                             (update "actions" (fn [actions]
                                                 (mapv #(-> %
                                                            (update "action" str/upper-case)
                                                            (update "timestamp" str "+00:00")
                                                            (dissoc "device"))
                                                       actions)))))))))

(deftest through-the-nextcloud-app
  (let [clock    (atom t2)
        [state send] (serve clock)
        b        (backend (nextcloud send) :api :nextcloud :name "cloud")]
    (ask send :post "/api/2/episodes/simon.json"
         [{"podcast" feed-a "episode" "https://a.example/1.mp3" "guid" "a1" "action" "play"
           "timestamp" "2026-01-01T01:30:00" "started" 0 "position" 1800 "total" 3600 "device" "antennapod"}])
    (run
     #(async/then
       (sync/sync! (phone) [b] {:at t2})
       (fn [{s :state :as step}]
         (is (nil? (failed step)) (pr-str (failed step)))
         (is (= 1800 (:position (profile/episode-entry s [g-a "a1"]))) "a PLAY of the other app, later than the phone's")
         (is (= "gpodder:cloud" (:device (profile/episode-entry s [g-a "a1"])))
             "an action without a device, written in the name of the backend")
         (is (= #{feed-a} (set (map :url (vals (profile/subscriptions @state)))))))))))

(deftest a-server-with-paths-and-limits-of-its-own
  (let [calls   (atom [])
        action  (fn [n] {"podcast" feed-a "episode" (str "https://a.example/" n ".mp3") "guid" (str "a" n) "action" "play"
                         "timestamp" "2026-01-01T01:30:00" "position" n "total" 3600 "device" "other"})
        ;; a server that gives two actions to a page, and whose time moves on
        ;; with each page
        page    (atom 0)
        send    (fn [{:keys [method url body]}]
                  (let [path (second (re-find #"^https://sync\.example\.org(/[^?]*)" url))]
                    (swap! calls conj [method path (some-> body json/parse count)])
                    {:status  200
                     :headers {}
                     :body    (json/write-str
                               (case [method path]
                                 [:get "/sync/simon/shows"]   {"add" [] "remove" [] "timestamp" 10}
                                 [:get "/sync/simon/actions"] (let [n (swap! page inc)]
                                                                {"actions"   [(action (* 2 n)) (action (inc (* 2 n)))]
                                                                 "timestamp" (+ 1767230000 n)})
                                 {"timestamp" 20 "update_urls" []}))}))
        own     (gpodder/backend "https://sync.example.org"
                                 {:credentials    creds :send send :name "own"
                                  :api            {:subscriptions        "/sync/{user}/shows"
                                                   :update-subscriptions "/sync/{user}/shows"
                                                   :episodes             "/sync/{user}/actions"
                                                   :update-episodes      "/sync/{user}/actions"}
                                  :episode-url-fn (fn [[_ item]] (str "https://a.example/" item ".mp3"))
                                  :page-size      2
                                  :max-pages      3
                                  :batch-size     1})
        state   (-> (phone)
                    (profile/progress [g-a "b1"] 10 3600 t1)
                    (profile/progress [g-a "b2"] 20 3600 t1))]
    (run
     #(async/then
       (sync/sync! state [own] {:at t2})
       (fn [{s :state :as step}]
         (is (nil? (failed step)) (pr-str (failed step)))
         (testing "no login and no device, since the map has no path for them"
           (is (= #{"/sync/simon/shows" "/sync/simon/actions"} (set (map second @calls)))))
         (testing "a full page asks for the next, three pages at most"
           (is (= 3 (count (filter #{[:get "/sync/simon/actions" nil]} @calls))))
           (is (= [2 3 4 5 6 7]
                  (sort (keep (fn [n] (:position (profile/episode-entry s [g-a (str "a" n)]))) (range 2 10))))))
         (testing "one action to a request of the push"
           (is (= [[:post "/sync/simon/shows" 2] [:post "/sync/simon/actions" 1] [:post "/sync/simon/actions" 1]
                   [:post "/sync/simon/actions" 1]]
                  (filter (comp #{:post} first) @calls)))))))))

(deftest a-device-the-server-forgot
  (let [clock    (atom t2)
        [_ send] (serve clock)
        forgot   (atom true)
        calls    (atom [])
        send'    (fn [{:keys [url] :as request}]
                   (swap! calls conj (second (re-find #"/api/2/([a-z]+)" url)))
                   (if (and @forgot (str/includes? url "/subscriptions/"))
                     (do (reset! forgot false) {:status 404 :headers {} :body ""})
                     (send request)))]
    (run
     #(async/then
       (sync/sync! (phone) [(backend send' :name "cloud")] {:at t2 :cursors {"cloud" {:registered "phone" :subscriptions 5}}})
       (fn [step]
         (is (nil? (failed step)))
         (is (= ["subscriptions" "devices" "subscriptions"] (take 3 (filter #{"subscriptions" "devices"} @calls)))
             "registered again and asked once more"))))))

(deftest a-url-the-server-refused
  (let [clock    (atom t2)
        [_ send] (serve clock)
        send'    (fn [{:keys [method url] :as request}]
                   (let [answer (send request)]
                     (if (and (= :post method) (str/includes? url "/subscriptions/"))
                       (assoc answer :body (json/write-str {"timestamp" 1 "update_urls" [[feed-a nil]]}))
                       answer)))]
    (run
     #(async/then
       (sync/sync! (phone) [(backend send')] {:at t2})
       (fn [{:keys [report]}]
         (is (= [[:gpodder/rejected-url feed-a]] (map (juxt :id :url) report))))))))
