(ns dk.simongray.podcast.sync.gpodder.api-test
  "The gpodder API as data: the calls as request maps, and the answers read
  as gpodder.net and the GPodder Sync app for Nextcloud write them."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.sync.gpodder.api :as gpodder.api]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.secret :as secret]))

(def root "https://gpodder.example.org")

(def creds {:username "simon" :password "app pass"})

(deftest requests
  (let [subs (gpodder.api/request :subscriptions root {:credentials creds :device "phone" :since 42})]
    (is (= [:get "https://gpodder.example.org/api/2/subscriptions/simon/phone.json?since=42"] ((juxt :method :url) subs)))
    (is (= "Basic c2ltb246YXBwIHBhc3M=" (get-in (http/revealed subs) [:headers "Authorization"])))
    (is (secret/hidden? (get-in subs [:headers "Authorization"])))
    (is (not (re-find #"c2ltb246" (pr-str subs))) "a request that is printed does not show the password")
    (is (nil? (get-in subs [:headers "User-Agent"])) "the sender gives the User-Agent"))
  (is (= "https://gpodder.example.org/api/2/episodes/simon.json?since=0"
         (:url (gpodder.api/request :episodes (str root "/") {:credentials creds})))
      "since 0 when none is given, and one slash after the root")
  (is (= [:post "https://gpodder.example.org/api/2/auth/simon/login.json"]
         ((juxt :method :url) (gpodder.api/request :login root {:credentials creds}))))
  (let [register (gpodder.api/request :register root {:credentials creds :device "phone 1" :body {"caption" "Phone"}})]
    (is (= "https://gpodder.example.org/api/2/devices/simon/phone%201.json" (:url register)))
    (is (= "{\"caption\":\"Phone\"}" (:body register)))
    (is (= "application/json" (get-in register [:headers "Content-Type"]))))
  (is (= "sessionid=abc"
         (get-in (http/revealed (gpodder.api/request :episodes root {:credentials creds :session "abc"})) [:headers "Cookie"])))
  (is (secret/hidden? (get-in (gpodder.api/request :episodes root {:credentials creds :session "abc"}) [:headers "Cookie"])))
  (is (= "Basic bGF0ZXI6eA=="
         (get-in (http/revealed (gpodder.api/request :episodes root {:credentials (constantly {:username "later" :password "x"})}))
                 [:headers "Authorization"]))
      "credentials may be a function")
  (testing "the paths of the GPodder Sync app for Nextcloud"
    (let [nc #(gpodder.api/request % "https://cloud.example.org" {:api :nextcloud :credentials creds :since 7})]
      (is (= [:get "https://cloud.example.org/index.php/apps/gpoddersync/subscriptions?since=7"] ((juxt :method :url) (nc :subscriptions))))
      (is (= [:post "https://cloud.example.org/index.php/apps/gpoddersync/subscription_change/create"] ((juxt :method :url) (nc :update-subscriptions))))
      (is (= [:get "https://cloud.example.org/index.php/apps/gpoddersync/episode_action?since=7"] ((juxt :method :url) (nc :episodes))))
      (is (= [:post "https://cloud.example.org/index.php/apps/gpoddersync/episode_action/create"] ((juxt :method :url) (nc :update-episodes))))
      (is (thrown? #?(:clj Exception :cljs js/Error) (nc :login)) "the app has no login")))
  (testing "the paths of a server with paths of its own"
    (let [paths {:subscriptions "/sync/{user}/devices/{device}/shows" :episodes "/sync/{user}/actions"}
          own   #(:url (gpodder.api/request % "https://sync.example.org/"
                                            {:api paths :credentials {:username "si mon" :password "x"} :device "my/phone"}))]
      (is (= "https://sync.example.org/sync/si%20mon/devices/my%2Fphone/shows?since=0" (own :subscriptions)))
      (is (= "https://sync.example.org/sync/si%20mon/actions?since=0" (own :episodes)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (own :update-episodes)) "a call that the map leaves out"))
    (is (= (:nextcloud gpodder.api/apis) (gpodder.api/paths :nextcloud)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (gpodder.api/request :episodes root {:api :opodsync :credentials creds}))
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
  (let [{:keys [actions timestamp]} (gpodder.api/response :episodes {:status 200 :body mygpo-actions})]
    (is (= 12347 timestamp))
    (is (= [["download" #inst "2009-12-12T09:00:00Z" nil nil] ["play" #inst "2009-12-12T09:05:21.125Z" 120 "foo-1"]]
           (map (juxt :action :timestamp :position :guid) actions))
        "a time without a zone is UTC"))
  (is (= [["play" #inst "2026-01-01T02:00:00Z" nil]]
         (map (juxt :action :timestamp :device) (:actions (gpodder.api/response :episodes {:status 200 :body nextcloud-actions}))))
      "a name in any case")
  (is (= {:add ["http://example.com/feed.rss"] :remove ["http://example.org/podcast.php"] :timestamp 12347}
         (gpodder.api/response :subscriptions {:status 200 :body "{\"add\": [\"http://example.com/feed.rss\"], \"remove\": [\"http://example.org/podcast.php\"], \"timestamp\": 12347}"})))
  (is (= {:timestamp 1337 :update-urls [["http://feeds2.example.com/feed.rss" "http://feeds.example.com/feed.rss"] ["foo" nil]]}
         (gpodder.api/response :update-subscriptions
                               {:status 200 :body "{\"timestamp\": 1337, \"update_urls\": [[\"http://feeds2.example.com/feed.rss\", \"http://feeds.example.com/feed.rss\"], [\"foo\", null]]}"}))
      "a URL refused as null")
  (is (= {:timestamp 1767232800 :update-urls []} (gpodder.api/response :update-episodes {:status 200 :body "{\"timestamp\": 1767232800}"}))
      "the Nextcloud app sends no update_urls")
  (is (nil? (gpodder.api/response :register {:status 200 :body ""})))
  (is (= "abc123" (gpodder.api/response :login {:status 200 :headers {"set-cookie" ["sessionid=abc123; expires=Tue, 28 Sep 2027 10:00:00 GMT; Path=/"]}})))
  (testing "a value of another type than the API has reads as none"
    (let [read (fn [call body] (gpodder.api/response call {:status 200 :body body}))]
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
  (let [failure (fn [answer] (try (gpodder.api/response :episodes answer) nil
                                  (catch #?(:clj Exception :cljs :default) e [(:status (ex-data e)) (ex-message e)])))]
    (is (= [401 "The server answered 401 to episodes: the credentials were refused"] (failure {:status 401 :body ""})))
    (is (= 500 (first (failure {:status 500 :body ""}))))
    (is (= 200 (first (failure {:status 200 :body "<html>a login page</html>"}))) "an answer that is not JSON")))
