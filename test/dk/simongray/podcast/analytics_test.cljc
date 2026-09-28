(ns dk.simongray.podcast.analytics-test
  "The downloads of episodes by the rules of the IAB: the bytes of one
  minute, the listeners of IPv6, the list of user agents of the OPAWG, the
  filters, and the worked examples of the guidelines 2.2 and of the draft
  2.3. Then OP3: its prefix on the URLs of a feed, and the answers of its
  API that Podcasting 2.0 got on 2026-09-28."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.analytics :as analytics]
            [dk.simongray.podcast.analytics.op3 :as op3]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.time :as time]))

(def opawg
  "The entries of the list of user agents of the OPAWG in the fixture, by
  file."
  (json/read-str (fixture "analytics/user-agents.json")))

(def user-agents
  "The fixture, read as the list."
  (analytics/user-agents (into {} (for [file [:bots :apps :libraries :browsers :devices :referrers]]
                                    [file (get opawg (name file))]))))

(defn- at
  "The instant that the ISO 8601 text `s` names."
  [s]
  (time/parse-iso8601 s))

(defn- request
  "A GET of the first episode by `user-agent` from 203.0.113.5 at `s`, with
  the keys of `more`."
  [user-agent s & {:as more}]
  (merge {:time (at s) :ip "203.0.113.5" :user-agent user-agent :method :get
          :url "https://cdn.example.com/episode-1.mp3" :status 200}
         more))

(def overcast
  "The user agent of Overcast on an iPhone."
  "Overcast/3.0 (+http://overcast.fm/; iOS podcast app)")

(deftest the-bytes-of-one-minute
  (is (= 1098334 (analytics/threshold {:size 60000000 :header 100000 :duration 3600}))
      "the header, and a sixtieth of the rest of an hour")
  (is (= 500000 (analytics/threshold {:size 500000 :duration 45})) "the whole file of a short episode")
  (is (= 500000 (analytics/threshold {:size 500000})) "the whole file when the duration is not known")
  (is (nil? (analytics/threshold {:duration 3600})))
  (is (= 1000 (analytics/threshold {:size 1000 :header 5000 :duration 3600})) "a header that is too large"))

(deftest the-listeners-of-an-address
  (is (= "203.0.113.5" (analytics/listener-ip "203.0.113.5")))
  (is (= ["2001:db8:1:2::/64" "2001:db8::/64" "2001:db8:abcd:12::/64" "::/64" "1::/64"]
         (map analytics/listener-ip ["2001:db8:1:2:3:4:5:6" "2001:db8::1" "[2001:DB8:abcd:12::1]"
                                     "0:0:0:0:1:2:3:4" "1:0:0:0:1:0:0:0"]))
      "the first 64 bits of an IPv6 address")
  (is (= "203.0.113.5" (analytics/listener-ip "::ffff:203.0.113.5")) "an IPv4 client of a server of IPv6")
  (is (= ["cbecf10d76b06a1a" "1::2::3" nil] (map analytics/listener-ip ["cbecf10d76b06a1a" "1::2::3" nil]))
      "a hash, or other text, as it is"))

(deftest the-user-agents-of-the-opawg
  (testing "each example names its own entry"
    (doseq [[file kind] [["bots" "bot"] ["apps" "app"] ["libraries" "library"] ["browsers" "browser"]]
            {:strs [name category examples]} (get-in opawg [file "entries"])
            example examples]
      (is (= [(if (= "bot" category) "bot-library" kind) name]
             ((juxt :agent-type :agent-name) (analytics/identify user-agents example)))
          example)))
  (is (= {:agent-type "app" :agent-name "Overcast" :device-type "mobile" :device-name "Apple iPhone"}
         (analytics/identify user-agents overcast)))
  (is (= {:agent-type "bot" :agent-name "Googlebot"} (analytics/identify user-agents "Googlebot-Image/1.0"))
      "a bot has no device")
  (is (= {:agent-type "unknown" :agent-name "Podcatcher/1.0 (Linux)"}
         (analytics/identify user-agents "Podcatcher/1.0 (Linux)"))
      "an agent that the list does not know is named by its header")
  (is (= {:agent-type "unknown"} (analytics/identify user-agents nil)))
  (is (= "Overcast" (:agent-name (analytics/identify user-agents "Overcast/3.0\r\n (+http://overcast.fm/)")))
      "a header without its line breaks")
  (let [safari "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"]
    (is (= {:agent-type "browser" :agent-name "Safari" :referrer-type "app" :referrer-name "Apple Podcasts"}
           (analytics/identify user-agents safari "https://podcasts.apple.com/us/podcast/id1584274529")))
    (is (= ["domain" "player.example.com"]
           ((juxt :referrer-type :referrer-name) (analytics/identify user-agents safari "https://player.example.com/embed/1")))
        "a referrer that the list does not know, by its domain")
    (is (not (contains? (analytics/identify user-agents overcast "https://podcasts.apple.com/") :referrer-name))
        "only the referrer of a browser"))
  (let [broken (analytics/user-agents {:bots {"entries" [{"name" "Broken" "pattern" "(bot"} {"name" "Fine" "pattern" "fine"}]}})]
    (is (= ["Fine"] (map :name (:bots broken))))
    (is (= [[:analytics/unreadable-pattern :bots "Broken"]]
           (map (juxt :id :file :name) (:podcast/report (meta broken))))
        "a pattern that does not compile is left out, and reported")))

(deftest what-the-rules-leave-out
  (let [reason #(analytics/drop-reason (merge (request overcast "2026-09-21T22:00:00Z") %))]
    (is (nil? (reason {})))
    (is (= [:malformed :malformed :malformed] (map reason [{:time nil} {:url nil} {:ip ""}])))
    (is (= [:method :method nil] (map reason [{:method :head} {:method nil} {:method "GET"}])))
    (is (= [nil nil nil :status :status] (map reason [{:status 206} {:status nil} {:status 0} {:status 304} {:status 404}]))
        "a prefix service sees no status, and Akamai logs 000 for a 206 that ended early")
    (is (= [:two-bytes :two-bytes :two-bytes nil nil]
           (map #(reason {:status 206 :range %}) ["bytes=0-1" "bytes=0-0" "bytes=-2" "bytes=0-2" "items=0-1"])))
    (is (= [:no-user-agent :no-user-agent] (map reason [{:user-agent nil} {:user-agent " "}])))
    (is (= [:watch :watch]
           (map reason [{:user-agent "atc/1.0 watchOS/7.1 model/Watch4,2 hwp/t8006 build/18R590 (6; dt:191)"}
                        {:user-agent "(null)/(null) watchOS/5.0.1 model/Watch3,3"}]))
        "the rule of the IAB for Apple Podcasts on a watch")
    (is (= [:bot :bot :bot nil]
           (map reason [{:user-agent "ExampleBot/1.0"} {:agent-type "bot-library"} {:agent-type "unknown" :user-agent "Web crawler"}
                        {:agent-type "app" :user-agent "Robotic Podcasts/1.0"}]))
        "an agent that says it is a bot, unless the list names it")
    (is (= :excluded (analytics/drop-reason (request overcast "2026-09-21T22:00:00Z")
                                            {:exclude? #(= "203.0.113.5" (:ip %))})))))

(deftest the-examples-of-the-iab
  (testing "2.2, scenario 1: ten requests from six user agents behind one address, in one day"
    (let [agents ["A/1" "B/1" "C/1" "D/1" "E/1" "F/1" "A/1" "B/1" "C/1" "A/1"]
          result (analytics/downloads (map-indexed (fn [i ua] (request ua (str "2026-09-21T10:0" i ":00Z"))) agents))]
      (is (= 6 (count (:downloads result))))
      (is (= #{["203.0.113.5" "A/1"] ["203.0.113.5" "B/1"] ["203.0.113.5" "C/1"]
               ["203.0.113.5" "D/1"] ["203.0.113.5" "E/1"] ["203.0.113.5" "F/1"]}
             (set (map :listener (:downloads result)))))
      (is (= {} (:dropped result)))))
  (testing "the draft 2.3: the windows of one listener, Monday 22:00 to Wednesday 23:15"
    (let [requests (map #(request overcast %) ["2026-09-21T22:00:00Z" "2026-09-22T03:00:00Z"
                                               "2026-09-22T22:15:00Z" "2026-09-23T23:15:00Z"])
          times    (fn [opts] (map #(time/format-iso8601 (:time %)) (:downloads (analytics/downloads requests opts))))]
      (is (= ["2026-09-21T22:00:00Z" "2026-09-22T03:00:00Z" "2026-09-23T23:15:00Z"] (times {}))
          "calendar days in UTC")
      (is (= ["2026-09-21T22:00:00Z" "2026-09-22T22:15:00Z" "2026-09-23T23:15:00Z"] (times {:window :rolling}))
          "24 hours from the first request")))
  (testing "a listener of IPv6 moves inside its /64"
    (is (= 1 (count (:downloads (analytics/downloads [(request overcast "2026-09-21T10:00:00Z" :ip "2001:db8:1:2::10")
                                                      (request overcast "2026-09-21T11:00:00Z" :ip "2001:db8:1:2::77")])))))))

(deftest a-minute-of-the-file
  (let [file      "https://cdn.example.com/episode-1.mp3"
        apple     "AppleCoreMedia/1.0.0.21A329 (iPhone; U; CPU OS 17_0 like Mac OS X; en_us)"
        ranged    (fn [s range bytes] (request apple s :status 206 :range range :bytes bytes))
        count-of  (fn [requests needed]
                    (let [result (analytics/downloads requests {:threshold {file needed}})]
                      [(count (:downloads result)) (:dropped result)]))]
    (is (= [1 {:two-bytes 1}]
           (count-of [(ranged "2026-09-21T10:00:00Z" "bytes=0-1" 2)
                      (ranged "2026-09-21T10:00:01Z" "bytes=0-" 400000)
                      (ranged "2026-09-21T10:00:09Z" "bytes=400000-" 700000)]
                     1000000))
        "the ranges of one listener add up")
    (is (= [0 {:short 2}]
           (count-of [(ranged "2026-09-21T10:00:01Z" "bytes=0-" 600000)
                      (ranged "2026-09-21T10:00:09Z" "bytes=0-" 600000)]
                     1000000))
        "a byte that two requests ask for counts once")
    (is (= [0 {:short 1}] (count-of [(ranged "2026-09-21T10:00:01Z" "bytes=0-" 999999)] 1000000))
        "the bytes that the server sent, not those asked for")
    (is (= [1 {}] (count-of [(request apple "2026-09-21T10:00:01Z")] 1000000))
        "a whole file, when the log does not tell how much was sent")
    (is (= [1 {}] (count-of [(ranged "2026-09-21T10:00:01Z" "bytes=0-99" nil)] nil))
        "any request counts for a file with no threshold")
    (is (= [1 {}] (count-of [(ranged "2026-09-21T10:00:01Z" "bytes=0-499999" nil)
                             (ranged "2026-09-21T10:00:02Z" "bytes=-500000" nil)]
                            1000000))
        "a range from the end of the file counts its bytes")))

(deftest the-downloads-name-their-agents
  (let [{:keys [downloads dropped]}
        (analytics/downloads [(request overcast "2026-09-21T10:00:00Z")
                              (request "Go-http-client/1.1" "2026-09-21T10:00:00Z")
                              (request "Pocket%20Casts/7.96.0.4 CFNetwork/3826.600.41 Darwin/24.6.0" "2026-09-21T10:00:00Z"
                                       :ip "198.51.100.7")
                              (request "AntennaPod/3.4.0" "2026-09-21T10:05:00Z" :ip "192.0.2.44")
                              (request "AntennaPod/3.4.0" "2026-09-21T10:06:00Z" :ip "192.0.2.44" :url "https://cdn.example.com/episode-1.mp3?_from=app")]
                             {:user-agents user-agents :exclude? #(= "198.51.100.7" (:ip %))})]
    (is (= [["Overcast" "Apple iPhone"] ["AntennaPod" "Android Phone"]] (map (juxt :agent-name :device-name) downloads))
        "the file of a request is its URL without the query")
    (is (= {:bot 1 :excluded 1} dropped) "Go-http-client is a library of bots")
    (is (= {"Overcast" 1 "AntennaPod" 1} (frequencies (map :agent-name downloads))))))

(def recorded
  "The answers of OP3 to the calls of the fixture, by URL."
  (json/read-str (fixture "analytics/op3.json")))

(defn- op3-send
  "A `:send` that answers as OP3 did, to the bearer token test-token, with
  each URL that it is asked for in `log`."
  [log]
  (fn [{:keys [url headers]}]
    (swap! log conj url)
    (let [{:strs [status body]} (if (= "Bearer test-token" (get headers "Authorization"))
                                  (get recorded url {"status" 404 "body" {"error" "not found"}})
                                  (get recorded "unauthorized"))]
      {:status status :url url :body (if (string? body) body (json/write-str body))})))

(deftest the-prefix-of-op3
  (is (= "https://op3.dev/e/cdn.example.com/uploads/episode-54.mp3"
         (op3/prefix "https://cdn.example.com/uploads/episode-54.mp3")))
  (is (= "https://op3.dev/e/http://cdn.example.com/episode-54.mp3" (op3/prefix "http://cdn.example.com/episode-54.mp3"))
      "OP3 needs the scheme of http only")
  (is (= "https://op3.dev/e,pg=917393e3-1b1e-5cef-ace4-edaa54e1f810/cdn.example.com/e.mp3?x=1&y=%2F"
         (op3/prefix "HTTPS://cdn.example.com/e.mp3?x=1&y=%2F" {:podcast-guid "917393E3-1B1E-5cef-ace4-edaa54e1f810"}))
      "the podcast guid in lower case, and the rest of the URL as it is")
  (is (= "https://op3.dev/e/cdn.example.com/e.mp3" (op3/prefix "https://cdn.example.com/e.mp3" {:podcast-guid "not a guid"})))
  (is (= "https://op3.dev/e/dts.podtrac.com/redirect.mp3/cdn.example.com/e.mp3"
         (op3/prefix "https://dts.podtrac.com/redirect.mp3/cdn.example.com/e.mp3"))
      "in front of the other prefixes")
  (doseq [url ["https://op3.dev/e/cdn.example.com/e.mp3" "https://dts.podtrac.com/redirect.mp3/op3.dev/e/cdn.example.com/e.mp3"
               "https://op3.dev/e,pg=917393e3-1b1e-5cef-ace4-edaa54e1f810/cdn.example.com/e.mp3"]]
    (is (= url (op3/prefix url)) "a URL that goes through OP3 already"))
  (is (every? nil? (map op3/prefix ["https://user:pw@cdn.example.com/e.mp3" "https://[2001:db8::1]/e.mp3"
                                    "https://cdn_example.com/e.mp3" "https://localhost/e.mp3" "https://media/e.mp3"
                                    "https://media.example/e.mp3" "https://cdn.example.com" "https://cdn.example.com/"
                                    "https://cdn.example.com:0/e.mp3" "ipfs://bafy/e.mp3" nil]))
      "no prefix where OP3 answers with an error")
  (is (= "https://staging.op3.dev/e/cdn.example.com/e.mp3"
         (op3/prefix "https://cdn.example.com/e.mp3" {:origin "https://staging.op3.dev"})))
  (let [feed (op3/with-prefix (podcast/parse (fixture "feeds/namespace-example.xml")))
        item (first (filter #(= "https://op3.dev/e/example.com/file-03.mp3" (get-in % [:rss/enclosure :url])) (:rss/items feed)))]
    (is (some? item) "the enclosure, without the guid of the example, which is no UUID")
    (is (= ["https://op3.dev/e/example.com/file-03.mp3" "ipfs://someRandomMpegFile03"]
           (map :uri (:sources (first (:podcast/alternate-enclosures item)))))
        "the sources of an alternate enclosure that OP3 takes")
    (is (str/includes? (podcast/emit feed) "url=\"https://op3.dev/e/example.com/file-02.mp3\""))))

(deftest the-api-of-op3
  (let [request (op3/request "test-token" :downloads {:show "fb3d03641442410e881acb0562ef27c6"
                                                      :start (at "2026-09-26T00:00:00Z") :end "2026-09-27" :bots? true})]
    (is (= "https://op3.dev/api/1/downloads/show/fb3d03641442410e881acb0562ef27c6?format=json&start=2026-09-26T00%3A00%3A00Z&end=2026-09-27&bots=include"
           (:url request)))
    (is (= "Bearer test-token" (get-in request [:headers "Authorization"])) "the token goes in a header, not in the URL"))
  (is (= ["https://op3.dev/api/1/shows/aHR0cHM6Ly9mZWVkcy5wb2RjYXN0aW5kZXgub3JnL3BjMjAueG1s"
          "https://op3.dev/api/1/queries/top-apps-for-show?podcastGuid=917393e3-1b1e-5cef-ace4-edaa54e1f810"
          "https://op3.dev/api/1/queries/show-download-counts?showUuid=a&showUuid=b"]
         [(:url (op3/request "t" :show {:show "https://feeds.podcastindex.org/pc20.xml"}))
          (:url (op3/request "t" :top-apps-for-show {:show "917393E3-1b1e-5cef-ace4-edaa54e1f810"}))
          (:url (op3/request "t" :show-download-counts {:show ["a" "b"]}))])
      "a feed by its URL in base64, a show by its guid, and several shows")
  (let [answer (fn [url endpoint] (op3/response endpoint (json/write-str (get-in recorded [url "body"]))))]
    (is (= {:show-uuid "fb3d03641442410e881acb0562ef27c6" :title "Podcasting 2.0"
            :podcast-guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"
            :stats-page-url "https://op3.dev/show/fb3d03641442410e881acb0562ef27c6"}
           (answer "https://op3.dev/api/1/shows/917393e3-1b1e-5cef-ace4-edaa54e1f810" :show)))
    (is (= [["PC20-272" "2026-09-25T19:15:48Z"] ["PC20-271" "2026-09-18T19:26:22Z"] ["PC20-270" "2026-09-11T19:19:25Z"]]
           (map (juxt :item-guid (comp time/format-iso8601 :pubdate))
                (:episodes (answer "https://op3.dev/api/1/shows/fb3d03641442410e881acb0562ef27c6?episodes=include" :show)))))
    (is (= {:days "111111111111111111111111111111" :monthly-downloads 7250 :weekly-downloads [580 1746 2164 2007]
            :weekly-avg-downloads 1624 :num-weeks 4}
           (get-in (answer "https://op3.dev/api/1/queries/show-download-counts?showUuid=fb3d03641442410e881acb0562ef27c6&showUuid=a18389b8a52d4112a782b32f40f73df6"
                           :show-download-counts)
                   [:show-download-counts "fb3d03641442410e881acb0562ef27c6"])))
    (is (= [{:item-guid "PC20-272" :downloads1 998 :downloads-all 1168}
            {:item-guid "PC20-271" :downloads1 1018 :downloads3 1295 :downloads7 1555 :downloads-all 1611}]
           (map #(dissoc % :title :pubdate)
                (take 2 (:episodes (answer "https://op3.dev/api/1/queries/episode-download-counts?showUuid=fb3d03641442410e881acb0562ef27c6&limit=3"
                                           :episode-download-counts)))))
        "a count of days only once the episode is that old")
    (is (= 2640 (get-in (answer "https://op3.dev/api/1/queries/top-apps-for-show?podcastGuid=917393e3-1b1e-5cef-ace4-edaa54e1f810"
                                :top-apps-for-show)
                        [:app-downloads "Overcast"])))))

(deftest calls-to-op3
  (let [log  (atom [])
        opts {:send (op3-send log)}
        show "fb3d03641442410e881acb0562ef27c6"]
    (run #(async/then
           (op3/downloads! "test-token" {:show show :start "2026-09-26" :end "2026-09-27" :limit 3} opts)
           (fn [{:keys [downloads continuation-token]}]
             (is (= 5 (count downloads)) "two pages")
             (is (nil? continuation-token) "the second page is the last")
             (is (= 2 (count @log)))
             (is (= {:time "2026-09-26T00:00:25.792Z" :agent-name "Overcast" :device-name "Apple iPhone"
                     :url "https://op3.dev/e/mp3s.nashownotes.com/PC20-272-2026-09-25-Final.mp3"}
                    (-> (first downloads)
                        (select-keys [:time :agent-name :device-name :url])
                        (update :time time/format-iso8601))))
             (is (every? (fn [d] (and (string? (:ip d)) (string? (:listener d)))) downloads)
                 "the hash of the address and the listener, with the keys of analytics/downloads")
             (is (= {"Overcast" 1 "Pocket Casts" 1 "Godcaster" 1 "stagefright" 1 "Podcast Addict" 1}
                    (frequencies (map :agent-name downloads))))))
         #(async/then
           (op3/downloads! "test-token" {:show show :start "2026-09-26" :end "2026-09-27" :limit 3} (assoc opts :max-pages 1))
           (fn [{:keys [downloads continuation-token] :as page}]
             (is (= 3 (count downloads)))
             (is (= "DHcEEyZ5U3eGd4tsvCJpAhbtvg1k8jRuqiG4nUpxK2Fu5zGt" continuation-token)
                 "the token of the next page, to read on from")
             (async/then (op3/downloads! "test-token" {:show show :start "2026-09-26" :end "2026-09-27" :limit 3
                                                       :continuation-token (:continuation-token page)}
                                         opts)
                         (fn [{:keys [downloads]}]
                           (is (= 2 (count downloads)))))))
         #(async/then
           (op3/call! "test-token" :show {:show "6d93d41e-194b-4bd8-9e35-4d52b0c14793"} opts)
           (fn [show]
             (is (nil? show) "a show that OP3 does not know")))
         #(async/then
           (async/attempt (fn [] (op3/call! "test-token" :downloads {:show show :limit 20001} opts)))
           (fn [{:keys [error]}]
             (is (= [::op3/failed 400 "Bad limit: 20001"] ((juxt :type :status :message) (ex-data error))))))
         #(async/then
           (async/attempt (fn [] (op3/call! "wrong-token" :show {:show show} opts)))
           (fn [{:keys [error]}]
             (is (= [::op3/failed 401 "unauthorized"] ((juxt :type :status :message) (ex-data error))))))
         #(async/then
           (async/attempt (fn [] (op3/call! "test-token" :show {:show show} {:send (fn [_] {:status 429 :body "slow down"})})))
           (fn [{:keys [error]}]
             (is (= [429 "slow down"] ((juxt :status :message) (ex-data error)))))))))
