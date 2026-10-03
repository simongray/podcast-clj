(ns dk.simongray.podcast.app.feeds-test
  "The feeds that an app keeps: adding a fetched feed, which feeds are
  due, the result of each kind of fetch applied, and the step that
  fetches the due feeds through a stand-in for `fetch!`."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.time :as time]))

(def url "https://example.com/feed.xml")

(def show
  (podcast/parse (fixture "feeds/namespace-example.xml")))

(def guid (:podcast/guid show))

(def now #inst "2026-09-30T12:00:00Z")

(defn- at
  "`seconds` after `now`."
  [seconds]
  (time/->inst (+ (inst-ms now) (* 1000 seconds))))

(def fresh
  "The result of a fetch that gave the feed."
  {:status 200 :url url :feed show :etag "\"v1\"" :last-modified "Tue, 29 Sep 2026 10:00:00 GMT"})

(def feeds
  (feeds/add {} url fresh now))

(deftest adding-a-feed
  (let [entry (get feeds guid)]
    (is (= [url "\"v1\"" 0 200 now] ((juxt :url :etag :failures :status :fetched-at) entry)))
    (is (= show (:feed entry)))
    (is (= (at 86400) (:next-check entry)) "the gap between the episodes of the example feed is a day")
    (is (= [] (feeds/due feeds now)))
    (is (= [guid] (feeds/due feeds (at 86400))))
    (let [silent (assoc fresh :feed (assoc show :rss/items []))]
      (is (= (at 86400) (:next-check (get (feeds/add {} url silent now) guid))) "a feed that gives no time: a day")
      (is (= [] (feeds/due (feeds/add {} url silent now {:silent 3600}) (at 3599))))
      (is (= [guid] (feeds/due (feeds/add {} url silent now {:silent 3600}) (at 3600))) "the cadence given"))
    (is (= [guid] (feeds/due {guid {:url url :feed show}} now)) "an entry without a next check is due")
    (is (thrown? #?(:clj Throwable :cljs js/Error) (feeds/add {} url {:status 404 :url url} now)) "no feed to add")))

(defn same-for-slim?
  "Whether the slim `feed` at `url` gives what the whole one gives: the
  next check at `at`, the new episodes since `before`, and the names of
  the gpodder API."
  [feed before url at]
  (let [thin  (feeds/slim feed)
        names (gpodder/identities {url feed})
        slim  (gpodder/identities {url thin})]
    (and (= (resolve/next-check feed at) (resolve/next-check thin at))
         (= (profile/new-episodes before feed url) (profile/new-episodes (feeds/slim before) feed url))
         (every? (fn [item]
                   (let [id    (feed/episode-id feed url item)
                         media (:url (resolve/enclosure item))]
                     (and (= ((:episode-url names) id) ((:episode-url slim) id))
                          (= ((:episode-id names) url media nil) ((:episode-id slim) url media nil)))))
                 (:rss/items feed)))))

(deftest what-slim-keeps
  (let [before (update show :rss/items #(vec (rest %)))]
    (is (seq (profile/new-episodes before show url)) "the example has a new episode to find")
    (is (< (count (pr-str (feeds/slim show))) (quot (count (pr-str show)) 2)))
    (doseq [[what f] [["the example" show]
                      ["a ttl" (assoc show :rss/ttl 60)]
                      ["a syndication period" (assoc show :sy/update-period "hourly" :sy/update-frequency 2)]
                      ["a complete feed" (assoc show :itunes/complete true)]
                      ["an update frequency" (assoc show :podcast/update-frequency {:content "Weekly" :rrule {:freq "WEEKLY"}})]
                      ["no items" (assoc show :rss/items [])]]]
      (testing what
        (is (same-for-slim? f before url now))
        (is (same-for-slim? f before url (at (* 365 86400))) "a year later")))))

(deftest a-complete-feed
  (let [complete (assoc show :itunes/complete true)
        fs       (feeds/add {} url (assoc fresh :feed complete) now)]
    (is (= (at 604800) (:next-check (get fs guid))) "a week")))

(deftest the-results-of-a-fetch
  (testing "a fresh feed"
    (let [newer (assoc show :rss/title "Newer")
          {:keys [changed moved report] :as r} (feeds/refresh feeds guid (assoc fresh :feed newer :etag "\"v2\"") (at 3600))]
      (is (= [guid] changed))
      (is (nil? moved))
      (is (nil? report))
      (is (= ["Newer" "\"v2\"" (at 3600) (at (+ 3600 86400)) 0 200]
             ((juxt (comp :rss/title :feed) :etag :fetched-at :next-check :failures :status) (get-in r [:feeds guid]))))))
  (testing "a 304 keeps the feed, and sets the next check, no earlier than the server allows"
    (let [r (feeds/refresh feeds guid {:status 304 :url url :not-before (at 90000)} (at 3600))]
      (is (nil? (:changed r)))
      (is (= [show "\"v1\"" 304 (at 90000)] ((juxt :feed :etag :status :next-check) (get-in r [:feeds guid]))))))
  (testing "a fetch that got no answer"
    (let [error {:id :fetch/unreachable :url url :reason "no route"}
          r     (feeds/refresh feeds guid {:url url :error error} (at 3600))]
      (is (nil? (:changed r)))
      (is (= [(assoc error :feed-guid guid)] (:report r)))
      (is (= [show 1 nil (at (+ 3600 900))] ((juxt :feed :failures :status :next-check) (get-in r [:feeds guid]))) "the first wait")))
  (testing "a status that is not a success"
    (let [r (feeds/refresh feeds guid {:status 404 :url url} (at 3600))]
      (is (= [[:fetch/status 404 guid]] (map (juxt :id :status :feed-guid) (:report r))))
      (is (string? (:message (first (:report r)))) "described")
      (is (= [1 404] ((juxt :failures :status) (get-in r [:feeds guid]))))))
  (testing "failures in a row wait longer, up to the last wait, and the server's word wins when later"
    (let [gone  {:status 503 :url url}
          step  (fn [[fs t]] [(:feeds (feeds/refresh fs guid gone t)) (time/->inst (+ (inst-ms t) 100000))])
          waits (->> (iterate step [feeds now]) (drop 1) (take 6)
                     (map (fn [[fs t]] (quot (- (inst-ms (:next-check (get fs guid))) (- (inst-ms t) 100000)) 1000))))]
      (is (= [900 3600 14400 86400 86400 86400] waits))
      (is (= (at 999999) (:next-check (get-in (feeds/refresh feeds guid (assoc gone :not-before (at 999999)) now) [:feeds guid]))))))
  (testing "a fresh feed after failures starts the count again"
    (let [failed (:feeds (feeds/refresh feeds guid {:status 500 :url url} now))]
      (is (= 0 (:failures (get-in (feeds/refresh failed guid fresh (at 60)) [:feeds guid]))))))
  (testing "a feed that moved"
    (let [r (feeds/refresh feeds guid (assoc fresh :permanent-url "https://example.com/moved.xml") now)]
      (is (= {guid "https://example.com/moved.xml"} (:moved r)))
      (is (= "https://example.com/moved.xml" (:url (get-in r [:feeds guid])))))
    (let [r (feeds/refresh feeds guid (assoc fresh :new-feed-url "https://example.org/new.xml") now)]
      (is (= {guid "https://example.org/new.xml"} (:moved r)) "the feed declares it"))
    (is (nil? (:moved (feeds/refresh feeds guid (assoc fresh :url "https://cdn.example.com/feed.xml") now)))
        "a redirect that is not for good changes nothing"))
  (testing "a guid that is not there"
    (is (thrown? #?(:clj Throwable :cljs js/Error) (feeds/refresh feeds "nope" fresh now)))))

(deftest podping
  (let [other (feeds/add feeds "https://example.org/other.xml" (assoc fresh :feed (assoc show :podcast/guid "b1")) now)]
    (is (= ["b1"] (feeds/affected other [{:iris ["https://example.org/other.xml"]}])))
    (is (= [] (feeds/affected other [{:iris ["https://example.net/none.xml"]}])))))

(deftest the-step
  (let [three  (-> feeds
                   (feeds/add "https://example.org/b.xml" (assoc fresh :feed (assoc show :podcast/guid "b1")) now)
                   (feeds/add "https://example.org/c.xml" (assoc fresh :feed (assoc show :podcast/guid "c1")) now))
        later  (at 86400)
        asked  (atom [])
        fetch  (fn [url opts]
                 (swap! asked conj [url (select-keys opts [:etag :last-modified :credentials :allow?])])
                 (case url
                   "https://example.com/feed.xml" {:status 304 :url url}
                   "https://example.org/b.xml"    (assoc fresh :url url :feed (assoc show :podcast/guid "b1" :rss/title "B2"))
                   (throw (ex-info "refused" {:url url}))))]
    (run
     (fn []
       (async/then (feeds/refresh! three later {:fetch fetch :credentials-of {"https://example.org/c.xml" {:username "ann"}} :allow? :any})
                   (fn [{:keys [feeds changed moved report]}]
                     (testing "each due feed fetched with its validators, and the options of every fetch"
                       (is (= 3 (count @asked)))
                       (is (= #{[url {:etag "\"v1\"" :last-modified "Tue, 29 Sep 2026 10:00:00 GMT" :allow? :any}]
                                ["https://example.org/c.xml" {:etag "\"v1\"" :last-modified "Tue, 29 Sep 2026 10:00:00 GMT" :credentials {:username "ann"} :allow? :any}]}
                              (set (remove #(str/includes? (first %) "/b.xml") @asked)))))
                     (testing "the changes of all the fetches"
                       (is (= ["b1"] changed))
                       (is (= {} moved))
                       (is (= [[:fetch/unreachable "c1"]] (map (juxt :id :feed-guid) report)))
                       (is (= "B2" (:rss/title (:feed (get feeds "b1")))))
                       (is (= [304 1] [(:status (get feeds guid)) (:failures (get feeds "c1"))]))))))
     (fn []
       (reset! asked [])
       (async/then (feeds/refresh! three now {:fetch fetch :guids ["b1"]})
                   (fn [{:keys [changed]}]
                     (testing "only the guids given, whatever their next check"
                       (is (= ["b1"] changed))
                       (is (= 1 (count @asked)))))))
     (fn []
       (reset! asked [])
       (async/then (feeds/refresh! three later {:fetch fetch :parallel 1})
                   (fn [_]
                     (is (= 3 (count @asked)) "one at a time reaches them all")))))))

(deftest the-default-fetch
  (let [asked (atom [])
        send  (fn [{:keys [url headers]}]
                (swap! asked conj [url (get headers "If-None-Match")])
                {:status 304 :url url})]
    (run #(async/then (feeds/refresh! feeds (at 86400) {:send send})
                      (fn [{:keys [feeds changed report]}]
                        (is (= [[url "\"v1\""]] @asked) "fetch! of the root namespace, through the :send")
                        (is (= [[] [] 304] [changed report (:status (get feeds guid))])))))))

#?(:clj
   (deftest fetching-at-once
     (let [many  (into {} (for [i (range 8)] [(str "g" i) {:url (str "https://example.org/" i ".xml")}]))
           slow  (fn [url _] (Thread/sleep 200) {:status 304 :url url})
           timed (fn [parallel]
                   (let [started (System/currentTimeMillis)]
                     (feeds/refresh! many now {:fetch slow :guids (keys many) :parallel parallel})
                     (- (System/currentTimeMillis) started)))]
       (is (< (timed 8) 800) "the feeds of one group are fetched at once")
       (is (<= 800 (timed 2)) "and the groups one after the other"))))
