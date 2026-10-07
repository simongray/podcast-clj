(ns dk.simongray.podcast.hidden-secrets-test
  "Each function that takes a secret takes a hidden text too, and gives
  what it gives for the plain text, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.analytics.op3 :as op3]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.feed.publish :as publish]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.store.encrypted :as encrypted]
            [dk.simongray.podcast.store.webdav :as webdav]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.test-support :refer [run secret-key]]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.updates.websub :as websub]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.secret :as secret]))

(def password "hunter2-and-more")

(def hidden (secret/hide password))

(deftest requests-with-a-hidden-secret
  (testing "the Podcast Index"
    (is (= (index/request {:key "K" :secret password} :search/by-term {:q "x"} {:auth-date 1700000000})
           (index/request {:key "K" :secret hidden} :search/by-term {:q "x"} {:auth-date 1700000000}))))
  (testing "gpodder, with a password and a session"
    (is (= (gpodder/request :episodes "https://gpodder.example"
                            {:credentials {:username "ann" :password password} :device "phone" :session "abc"})
           (gpodder/request :episodes "https://gpodder.example"
                            {:credentials {:username "ann" :password hidden} :device "phone" :session (secret/hide "abc")}))))
  (testing "WebDAV"
    (is (= (webdav/request :list "https://dav.example/podcasts/" nil {:credentials {:username "ann" :password password}})
           (webdav/request :list "https://dav.example/podcasts/" nil {:credentials {:username "ann" :password hidden}})))
    (is (= (webdav/request :list "https://dav.example/podcasts/" nil {:credentials {:token password}})
           (webdav/request :list "https://dav.example/podcasts/" nil {:credentials (constantly {:token hidden})}))))
  (testing "OP3"
    (is (= (op3/request password :downloads {:show "fb3d03641442410e881acb0562ef27c6"})
           (op3/request hidden :downloads {:show "fb3d03641442410e881acb0562ef27c6"}))))
  (testing "Podping"
    (is (= (podping/notify-request password "https://example.org/feed.xml")
           (podping/notify-request hidden "https://example.org/feed.xml"))))
  (testing "WebSub"
    (let [subscribe #(websub/request :subscribe "https://hub.example/" "https://example.org/feed.xml"
                                     "https://app.example/callback/1" {:secret %})
          body      (bytes/encode "<rss/>")]
      (is (= (subscribe password) (subscribe hidden)))
      (is (str/includes? (:body (http/revealed (subscribe hidden))) "hub.secret=hunter2-and-more") "the hub gets the text")
      (is (thrown? #?(:clj Exception :cljs js/Error) (subscribe (secret/hide (apply str (repeat 200 "a"))))))
      (is (= (websub/signature "sha256" password body) (websub/signature "sha256" hidden body)))
      (is (websub/signed? hidden (websub/signature "sha256" password body) body))
      (is (not (websub/signed? (secret/hide "another") (websub/signature "sha256" password body) body)))))
  (testing "the private feed of a subscriber"
    (let [show {:rss/title "T"
                :rss/items [{:rss/title "E" :rss/enclosure {:url "https://example.org/1.mp3" :type "audio/mpeg" :length 1}}]}]
      (is (= (publish/for-subscriber show password) (publish/for-subscriber show hidden)))))
  (testing "an event of Nostr"
    (let [event {"kind" 1 "created_at" 1790000000 "content" "Hi"}
          aux   (apply str (repeat 64 "5"))]
      (is (= (nostr/signed event (secret-key 3) aux) (nostr/signed event (secret/hide (secret-key 3)) aux)))
      (is (nostr/verified? (nostr/signed event (secret/hide (secret-key 3)) aux))))))

(deftest requests-in-print
  (doseq [[what request] {"a WebDAV store"    (webdav/request :list "https://dav.example/podcasts/" nil
                                                              {:credentials {:username "ann" :password password}})
                          "gpodder"           (gpodder/request :episodes "https://gpodder.example"
                                                               {:credentials {:username "ann" :password password}
                                                                :session     "hunter2-session"})
                          "OP3"               (op3/request password :downloads {:show "fb3d03641442410e881acb0562ef27c6"})
                          "Podping"           (podping/notify-request password "https://example.org/feed.xml")
                          "the Podcast Index" (index/request {:key "K" :secret password} :search/by-term {:q "x"}
                                                             {:auth-date 1700000000})
                          "WebSub"            (websub/request :subscribe "https://hub.example/" "https://example.org/feed.xml"
                                                              "https://app.example/callback/1" {:secret password})}]
    (testing what
      (let [written (pr-str request)
            sent    (pr-str (http/revealed request))]
        (is (str/includes? written "#<secret>"))
        (is (not (re-find #"hunter2|aHVudGVyMi|YW5uOmh1bnRlcjI" written)) "no secret, as text or in base64")
        (is (not (str/includes? sent "#<secret>")) "the request that goes out has its texts")))))

(deftest options-in-print
  (doseq [opts [{:credentials {:username "ann" :password hidden}}
                {:credentials {:token hidden}}
                {:key "K" :secret hidden}
                {:secret hidden :lease-seconds 864000}]]
    (is (not (str/includes? (pr-str opts) "hunter2")) (pr-str opts))
    (is (str/includes? (pr-str opts) "#<secret>"))))

(deftest a-hidden-passphrase
  (run (fn []
         (async/then (encrypted/encrypt "{:queue []}" hidden {:iterations 1000})
                     (fn [sealed]
                       (is (encrypted/envelope sealed))
                       (async/then (async/all [(encrypted/decrypt sealed hidden) (encrypted/decrypt sealed password)])
                                   (fn [opened]
                                     (is (= ["{:queue []}" "{:queue []}"] opened)))))))
       (fn []
         (async/then (encrypted/encrypt "{:queue []}" password {:iterations 1000})
                     (fn [sealed]
                       (async/then (encrypted/decrypt sealed hidden)
                                   #(is (= "{:queue []}" %))))))))
