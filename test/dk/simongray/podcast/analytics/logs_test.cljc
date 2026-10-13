(ns dk.simongray.podcast.analytics.logs-test
  "The lines of server logs as requests: the combined format of Apache and
  nginx with the fields that hosts add, and the extended format of the W3C
  in the shapes that IIS and CDNs write it, counted as downloads."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.analytics :as analytics]
            [dk.simongray.podcast.analytics.logs :as logs]))

(def overcast
  "The user agent of Overcast."
  "Overcast/3.0 (+http://overcast.fm/; iOS podcast app)")

(def combined
  "Lines of the combined format, the last two with the X-Forwarded-For and
  the Range that nginx can add."
  [(str "203.0.113.5 - - [26/Sep/2026:08:15:02 +0200] \"GET /episodes/54.mp3?ref=feed HTTP/1.1\" 206 1048576"
        " \"https://example.com/show\" \"" overcast "\"")
   ""
   "198.51.100.21 - frank [26/Sep/2026:08:15:05 -0700] \"HEAD /54.mp3 HTTP/1.0\" 200 -"
   "a line of another log"
   "198.51.100.22 - - [26/Sep/2026:08:15:06 +0000] \"\\x16\\x03\\x01\\x02\\x00\\x01\" 400 157 \"-\" \"-\""
   (str "10.0.0.7 - - [26/Sep/2026:08:15:03 +0000] \"GET /54.mp3 HTTP/2.0\" 206 2 \"-\""
        " \"AppleCoreMedia/1.0.0.21G93 (iPhone; U; CPU OS 17_6 like Mac OS X; da_dk)\" \"198.51.100.9, 10.0.0.1\" \"bytes=0-1\"\r")
   (str "10.0.0.7 - - [26/Sep/2026:08:15:04 +0000] \"GET /54.mp3 HTTP/2.0\" 200 512 \"-\""
        " \"Podcast \\x22App\\x22 \\xE2\\x80\\x99s \\\"own\\\" agent\" \"-\" \"-\"")])

(deftest the-combined-format
  (let [[apache common unread handshake nginx escaped :as read]
        (logs/requests combined {:extra [:forwarded-for :range]})]
    (is (= 6 (count read)) "blank lines give nothing")
    (is (= {:time       #inst "2026-09-26T06:15:02Z"
            :ip         "203.0.113.5"
            :method     :get
            :url        "/episodes/54.mp3?ref=feed"
            :status     206
            :bytes      1048576
            :referer    "https://example.com/show"
            :user-agent overcast}
           apache)
        "the time in UTC")
    (is (= {:time #inst "2026-09-26T15:15:05Z" :ip "198.51.100.21" :method :head :url "/54.mp3" :status 200 :bytes 0}
           common)
        "the common format, without a referer and a user agent, and a dash for no bytes")
    (is (= [:analytics/unreadable-line 4 :warning] ((juxt :id :line :level) (:error unread))))
    (is (= {:time #inst "2026-09-26T08:15:06Z" :ip "198.51.100.22" :status 400 :bytes 157} handshake)
        "a request line that isn't one, which counts as no download")
    (is (= ["198.51.100.9" "bytes=0-1" #inst "2026-09-26T08:15:03Z"] ((juxt :ip :range :time) nginx))
        "the first address of X-Forwarded-For, the Range, and no carriage return")
    (is (= ["10.0.0.7" "Podcast \"App\" ’s \"own\" agent"] ((juxt :ip :user-agent) escaped))
        "the escapes of nginx and Apache, with UTF-8 in bytes")
    (is (nil? (:range escaped))))
  (is (= "https://cdn.example.com/episodes/54.mp3?ref=feed"
         (:url (first (logs/requests (take 1 combined) {:base-url "https://cdn.example.com/"})))))
  (is (= [:analytics/unreadable-line] (map (comp :id :error) (logs/requests ["[26/Sep/2026:08:15:02 +0000]"])))))

(def iis
  "A log of IIS, which writes the spaces of its values as plus signs."
  ["#Software: Microsoft Internet Information Services 10.0"
   "#Version: 1.0"
   "#Date: 2026-09-26 08:00:00"
   (str "#Fields: date time s-ip cs-method cs-uri-stem cs-uri-query s-port cs-username c-ip cs(User-Agent)"
        " cs(Referer) sc-status sc-substatus sc-win32-status sc-bytes time-taken")
   (str "2026-09-26 08:15:02 192.0.2.1 GET /54.mp3 - 443 - 203.0.113.5 Overcast/3.0+(+http://overcast.fm/;+iOS+podcast+app)"
        " - 200 0 0 43200000 15")
   "2026-09-26 08:15:03 192.0.2.1 GET /54.mp3"])

(def cdn
  "A log of the W3C format apart by tabs, with values percent-encoded, as
  CloudFront writes it."
  ["#Version: 1.0"
   (str "#Fields: date time x-edge-location sc-bytes c-ip cs-method cs(Host) cs-uri-stem sc-status cs(Referer)"
        " cs(User-Agent) cs-uri-query cs(Cookie) x-edge-result-type x-forwarded-for")
   (str "2026-09-26\t08:15:02.250\tCPH50-C1\t1048576\t203.0.113.5\tGET\td1.cloudfront.net\t/54.mp3\t206\t-\t"
        "Overcast/3.0%20(+http://overcast.fm/;%20iOS%20podcast%20app)\tsrc=feed\t-\tHit\t-")
   (str "2026-09-26\t08:15:03\tCPH50-C1\t512\t10.0.0.1\tGET\td1.cloudfront.net\t/54.mp3\t200\t-\t"
        "AppleCoreMedia/1.0.0.21G93\t-\t-\tMiss\t198.51.100.9,%2010.0.0.1")])

(deftest the-extended-format-of-the-w3c
  (let [[read short] (logs/requests iis {:base-url "https://cdn.example.com/"})]
    (is (= {:time       #inst "2026-09-26T08:15:02Z"
            :ip         "203.0.113.5"
            :method     :get
            :url        "https://cdn.example.com/54.mp3"
            :status     200
            :bytes      43200000
            :user-agent "Overcast/3.0+(+http://overcast.fm/;+iOS+podcast+app)"}
           read)
        "the fields that the #Fields line names, by any name in any order")
    (is (= [:analytics/unreadable-line 6] ((juxt :id :line) (:error short))) "a line that lacks fields"))
  (let [[edge forwarded] (logs/requests cdn)]
    (is (= {:time       #inst "2026-09-26T08:15:02.250Z"
            :ip         "203.0.113.5"
            :method     :get
            :url        "/54.mp3?src=feed"
            :status     206
            :bytes      1048576
            :user-agent overcast}
           edge))
    (is (= "198.51.100.9" (:ip forwarded)) "the first address of X-Forwarded-For"))
  (testing "quoted values, and lines before the fields"
    (is (= [:analytics/unreadable-line "Mozilla/5.0 (Macintosh)"]
           (map #(or (:id (:error %)) (:user-agent %))
                (logs/requests ["#Version: 1.0"
                                "2026-09-26 08:15:02 203.0.113.5"
                                "#Fields: date time c-ip cs(User-Agent)"
                                "2026-09-26 08:15:02 203.0.113.5 \"Mozilla/5.0 (Macintosh)\""]))))
    (is (= [:get] (map :method (logs/requests ["#Fields: date time c-ip cs-method cs-uri" "2026-09-26 08:15:02 203.0.113.5 GET /1.mp3"]
                                              {:format :w3c})))
        "a log without a directive before its #Fields")))

(deftest downloads-from-a-log
  (let [line   #(str "203.0.113.5 - - [26/Sep/2026:08:15:0" %1 " +0000] \"" %2 " /54.mp3 HTTP/1.1\" 206 600000"
                     " \"-\" \"" overcast "\" \"" %3 "\"")
        lines  [(line 2 "GET" "bytes=0-") (line 8 "GET" "bytes=600000-") (line 9 "HEAD" "-") "a line of another log"]
        opts   {:base-url "https://cdn.example.com/" :extra [:range]}
        result (analytics/downloads (logs/requests lines opts)
                                    {:threshold {"https://cdn.example.com/54.mp3" 1000000}})]
    (is (= ["https://cdn.example.com/54.mp3"] (map :url (:downloads result))) "two requests of one listener, which hold enough")
    (is (= {:method 1 :malformed 1} (select-keys (:dropped result) [:method :malformed]))
        "a line that doesn't read is malformed")))
