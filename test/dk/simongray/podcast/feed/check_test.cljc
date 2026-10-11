(ns dk.simongray.podcast.feed.check-test
  "The checks over the network: the files of a feed, and on the JVM a whole
  check against a server on localhost with a problem of each kind. The
  rules on facts given here are in rules-test."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.string :as str])
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [ascii fixture run #?@(:clj [be local? with-file-server with-server])]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async])
  #?(:clj (:import [java.security MessageDigest])))

(def example
  (delay (rss/parse (fixture "feeds/namespace-example.xml"))))

(deftest files-of-a-feed
  (let [files (check/files @example {:url "https://example.com/feed.xml" :max-items 1})]
    (is (= [:feed :media :image] (map :kind (take 3 files))) "the feed, its trailer and its images first")
    (is (= 1 (count (filter #(= "https://example.com/file-03.mp3" (:url %)) files)))
        "the enclosure once, though an alternate names it too")
    (is (not-any? #(re-find #"^ipfs:" (:url %)) files) "sources a check cannot fetch left out")
    (is (= {:type "audio/mpeg" :length 43200000} (:declared (first (filter #(= [:rss/items 0 :rss/enclosure] (:path %)) files)))))
    (is (= #{:feed :media :image :transcript :chapters} (set (map :kind files)))))
  (is (= 0 (count (check/files {:rss/items [{:rss/enclosure {:url "ftp://a/b.mp3"}}]}))))
  (testing "the sources of an alternate enclosure"
    (let [item  {:podcast/alternate-enclosures
                 [{:type "video/mp4" :length 1000 :integrity {:type "sri" :value "sha384-x"}
                   :sources [{:uri "https://a/v.mp4"}
                             {:uri "https://a/v.torrent" :content-type "application/x-bittorrent"}
                             {:uri "http://abc.onion/v.mp4"}
                             {:uri "http://abc.loki/v.mp4"}]}]}
          files (check/files {:rss/items [item]})]
      (is (= ["https://a/v.mp4" "https://a/v.torrent"] (map :url files)) "hosts of overlay networks left out")
      (is (= {:type "application/x-bittorrent"} (:declared (second files)))
          "a torrent has a type of its own, and not the length or integrity of the media file")))
  (testing "the video and the stream of an alternate enclosure"
    (let [item  {:itunes/duration 600
                 :podcast/alternate-enclosures
                 [{:type "video/mp4" :height 720 :codecs "avc1.64001F,mp4a.40.2"
                   :sources [{:uri "https://a/720.mp4" :content-type "video/mp4"}]}
                  {:type "application/x-mpegURL" :length 0 :rel "alternate"
                   :sources [{:uri "https://a/master.m3u8"}]}]}
          files (check/files {:rss/items [item]})]
      (is (= [:media :stream] (map :kind files)))
      (is (= {:type "video/mp4" :height 720 :codecs "avc1.64001F,mp4a.40.2" :duration 600} (:declared (first files)))
          "a source that repeats the type is the media file, with the duration of the item")
      (is (not (contains? (:declared (second files)) :duration)) "another rel is other content")))
  (testing "an image that several elements name, once with the sources of all"
    (is (= [{:kind :image :url "https://a/cover.jpg" :path [] :declared {:purpose #{} :sources #{:podcast :itunes :rss}}}]
           (check/files {:podcast/images [{:href "https://a/cover.jpg"}]
                         :itunes/image   {:href "https://a/cover.jpg"}
                         :rss/image      {:url "https://a/cover.jpg"}}))))
  (testing "the newest items"
    (let [feed {:rss/items [{:rss/pub-date #inst "2026-01-01" :rss/enclosure {:url "https://a/old.mp3"}}
                            {:rss/pub-date #inst "2026-03-01" :rss/enclosure {:url "https://a/new.mp3"}}]}]
      (is (= ["https://a/new.mp3"] (map :url (check/files feed {:max-items 1}))))
      (is (= 2 (count (check/files feed {:max-items :all})))))))

(deftest the-limits-of-a-check
  (let [feed  {:rss/items [{:rss/enclosure       {:url "https://a.example/e.mp3" :type "audio/mpeg"}
                            :podcast/transcripts (vec (for [i (range 50)]
                                                        {:url (str "https://a.example/t" i ".vtt") :type "text/vtt"}))}]}
        sent  (atom [])
        send  (fn [{:keys [url method] :as request}]
                (swap! sent conj request)
                {:status (if (= :head method) 200 206) :url url :headers {"content-type" ["text/vtt"]}})
        left  (fn [result] (frequencies (map (comp :unchecked :facts) (:files result))))
        told  (fn [result] (map (juxt :limit :count) (filter #(= :check/unchecked (:id %)) (:report result))))]
    (run
     #(async/then (check/check-files! feed {:send send :max-files 10 :allow-pred (constantly true)})
                  (fn [result]
                    (is (= 51 (count (:files result))) "every file that the feed names")
                    (is (= {nil 10 :max-files 41} (left result)))
                    (is (= [[:max-files 41]] (told result)) "one diagnostic counts the files that the check left out")
                    (is (= 20 (count @sent)))
                    (is (= #{[30 (:max-bytes check/default-limits) true]}
                           (set (map (juxt :timeout-seconds :max-bytes (comp some? :allow-pred)) @sent)))
                        "each request carries the limits and the guard")))
     #(let [ms (atom 0)]
        (async/then (check/check-files! feed {:send        send
                                       :concurrency 1
                                       :max-seconds 10
                                       :now-fn      (fn [] (time/->inst (swap! ms + 1000)))})
                    (fn [result]
                      (is (= [[:max-seconds (get (left result) :max-seconds)]] (told result)))
                      (is (< 40 (get (left result) :max-seconds)) "no file starts after the time of the check")))))))

(deftest a-file-whose-host-is-not-found
  (let [feed {:rss/items [{:rss/enclosure {:url "https://a.example/e.mp3" :type "audio/mpeg"}}]}
        send (fn [{:keys [url]}]
               (throw (ex-info (str "The host of " url " was not found") {:type ::http/unresolved :url url})))]
    (run #(async/then (check/check-files! feed {:send send :allow-pred (constantly true)})
                      (fn [{:keys [report]}]
                        (is (= [[:check/unreachable ::http/unresolved "The host of https://a.example/e.mp3 was not found"]]
                               (map (juxt :id :type :reason) report))
                            "says why, as the platform tells it"))))))

(deftest the-integrity-through-the-sender-of-the-app
  (let [file (bytes/of (ascii "hello"))
        alt  "https://a.example/alt.mp3"
        feed {:rss/items [{:podcast/alternate-enclosures
                           [{:type      "audio/mpeg"
                             :integrity {:type "sri" :value "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ="}
                             :sources   [{:uri alt}]}]}]}
        sent (atom [])
        send (fn [{:keys [url method range] :as request}]
               (swap! sent conj request)
               (cond
                 (= :head method) {:status 200 :url url :headers {}}
                 range            {:status 206 :url url :headers {} :body (bytes/of [104 101])
                                   :content-range {:start 0 :end 1 :total 5}}
                 :else            {:status 200 :url url :headers {} :body file}))]
    (run
     #(async/then (check/check-files! feed {:send send :integrity? true :allow-pred (constantly true)})
                  (fn [result]
                    (let [whole (filter (fn [r] (and (not= :head (:method r)) (nil? (:range r)))) @sent)]
                      (is (= {:valid? true :algorithm "sha256"}
                             (get-in (first (:files result)) [:facts :integrity])))
                      (is (= [[alt :bytes true]] (map (juxt :url :as (comp some? :allow-pred)) whole))
                          "the whole file came through the sender of the app, with the guard")
                      (is (nil? (:max-bytes (first whole)))
                          "the limit of bytes of the check is not the limit of a whole file")))))))

#?(:clj
   (defn- png
     "The bytes of a PNG image of `width` by `height` of the color type `kind`."
     [width height kind]
     (bytes/of (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]
                       (be 4 13) (ascii "IHDR") (be 4 width) (be 4 height) [8 kind 0 0 0] [0 0 0 0]
                       (be 4 0) (ascii "IDAT") [0 0 0 0]))))

#?(:clj
   (defn- mp3
     "The bytes of an MP3 file of 20 frames of 128 kbit/s at 44.1 kHz."
     []
     (bytes/of (apply concat (repeat 20 (concat [0xFF 0xFB 0x90 0x00] (repeat 413 0)))))))

#?(:clj
   (defn- sri
     "The SRI metadata of the SHA-384 digest of the bytes `b`."
     [^bytes b]
     (str "sha384-" (bytes/base64 (.digest (MessageDigest/getInstance "SHA-384") b)))))

#?(:clj
   (deftest checking-a-feed-over-http
     (let [requests (atom [])
           video    (bytes/of (range 200))]
       (with-server
         {"/feed.xml" {:body "<rss/>" :headers {"Content-Type" "application/rss+xml" "ETag" "\"1\""}}
          "/e.mp3"    {:body (mp3) :headers {"Content-Type" "application/octet-stream"} :ranges? false}
          "/old.mp3"  {:status 301 :headers {"Location" "/e.mp3"}}
          "/v.mp4"    {:body video :headers {"Content-Type" "video/mp4"} :head? false}
          "/art.jpg"  {:body (png 500 500 6) :headers {"Content-Type" "image/png"}}
          "/t.vtt"    {:body "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n" :headers {"Content-Type" "text/vtt"}}
          "/c.json"   {:body "{\"version\":\"1.2.0\",\"chapters\":[]}"
                       :headers {"Content-Type" "application/json+chapters" "Access-Control-Allow-Origin" "*"}}}
         #(swap! requests conj %)
         (fn [base]
           (let [feed  {:rss/title     "T"
                        :itunes/image  {:href (str base "/art.jpg")}
                        :rss/items     [{:rss/title           "E"
                                         :rss/enclosure       {:url (str base "/old.mp3") :type "audio/mpeg" :length 999}
                                         :podcast/transcripts [{:url (str base "/t.vtt") :type "text/vtt"}]
                                         :podcast/chapters    {:url (str base "/c.json") :type "application/json+chapters"}}
                                        {:rss/title                     "F"
                                         :rss/enclosure                 {:url (str base "/missing.mp3") :type "audio/mpeg"}
                                         :podcast/alternate-enclosures [{:type "video/mp4" :length 200
                                                                         :integrity {:type "sri" :value (sri video)}
                                                                         :sources [{:uri (str base "/v.mp4")}]}]}]}
                 check (fn [opts] (check/check-files! feed (merge {:url (str base "/feed.xml") :requirements :apple :allow-pred local?} opts)))
                 found (fn [result path] (set (map :id (filter #(= (str base path) (:url %)) (:report result)))))]
             (testing "two bytes of each file, and the header of the image"
               (let [result (check {})]
                 (is (= 7 (count (:files result))))
                 (is (= #{:check/feed-cors} (found result "/feed.xml")))
                 (is (= #{:check/moved :check/type :check/length :check/ranges} (found result "/old.mp3")))
                 (is (= #{:check/status} (found result "/missing.mp3")))
                 (is (= #{:check/head} (found result "/v.mp4")))
                 (is (= #{:check/artwork-size :check/artwork-alpha :check/artwork-extension :check/last-modified}
                        (found result "/art.jpg")))
                 (is (= #{:check/cors} (found result "/t.vtt")))
                 (is (= #{} (found result "/c.json")) "the chapters not read")
                 (is (every? #(or (= "HEAD" (:method %)) (:range %)) @requests) "no request for a whole file")
                 (is (every? #(= (contains? #{"/feed.xml" "/t.vtt" "/c.json"} (:path %)) (some? (:origin %))) @requests)
                     "an Origin only where pages of other sites read")))
             (reset! requests [])
             (testing "what the files hold, when asked"
               (let [result (check {:read? true :integrity? true})]
                 (is (= #{:check/empty} (found result "/c.json")))
                 (let [media (first (filter #(= (str base "/old.mp3") (:url %)) (:files result)))]
                   (is (= :mp3 (get-in media [:facts :media :format])) (pr-str (:facts media))))
                 (is (= {:valid? true :algorithm "sha384"}
                        (get-in (first (filter #(= (str base "/v.mp4") (:url %)) (:files result))) [:facts :integrity])))
                 (is (some #(and (= "/t.vtt" (:path %)) (= "GET" (:method %)) (nil? (:range %))) @requests)
                     "the transcript whole")))
             (reset! requests [])
             (testing "the URLs that the app allows, which the fetch of a whole file asks about too"
               (let [asked  (atom 0)
                     ;; the two requests of the probe, and not the one after them
                     allow? (fn [url] (or (not (str/ends-with? url "/v.mp4")) (<= (swap! asked inc) 2)))
                     result (check {:integrity? true :allow-pred allow?})
                     facts  (:facts (first (filter #(= (str base "/v.mp4") (:url %)) (:files result))))]
                 (is (= 3 @asked))
                 (is (= 206 (get-in facts [:get :status])) "the probe, which the app allowed")
                 (is (= :file/unreachable (get-in facts [:integrity :error :id])))
                 (is (str/includes? (get-in facts [:integrity :error :reason]) "is not allowed"))
                 (is (contains? (found result "/v.mp4") :check/unverified))
                 (is (= 1 (count (filter #(and (= "/v.mp4" (:path %)) (= "GET" (:method %))) @requests)))
                     "and no request for the whole file")))
             (reset! requests [])
             (testing "a private feed, whose credentials go to it alone"
               (let [result (check/check-files! {:rss/items []} {:url        (str (str/replace base "http://" "http://user:pw@") "/feed.xml")
                                                                 :allow-pred local?})]
                 (is (= [(str base "/feed.xml")] (map :url (:files result))) "the URL without them")
                 (is (= #{"/feed.xml"} (set (keep #(when (:authorization %) (:path %)) @requests))))))))))))

#?(:clj
   (deftest checking-a-stream-over-http
     (let [requests (atom [])
           master   (str "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,"
                         "CODECS=\"avc1.64001F,mp4a.40.2\"\n720/index.m3u8\n")
           media    (str "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                         (apply str (repeat 70 "#EXTINF:10,\nseg.ts\n")) "#EXT-X-ENDLIST\n")
           hls      {"Content-Type" "application/vnd.apple.mpegurl"}]
       (with-server
         {"/master.m3u8"    {:body master :headers (assoc hls "Access-Control-Allow-Origin" "*")}
          "/720/index.m3u8" {:body media :headers hls}
          "/closed.m3u8"    {:body master :headers hls}}
         #(swap! requests conj %)
         (fn [base]
           (let [item   (fn [path codecs]
                          {:rss/title "E" :itunes/duration 600
                           :rss/enclosure {:url (str base "/e.mp3") :type "audio/mpeg"}
                           :podcast/alternate-enclosures
                           [{:type "application/vnd.apple.mpegurl" :length 0 :height 720 :codecs codecs
                             :sources [{:uri (str base path)}]}]})
                 check  (fn [items]
                          (check/check-files! {:rss/title "T" :rss/items items} {:read? true :allow-pred local?}))
                 found  (fn [result path]
                          (set (map :id (filter #(= (str base path) (:url %)) (:report result)))))
                 result (check [(item "/master.m3u8" "avc1.64001F,mp4a.40.2")])
                 facts  (:facts (first (filter #(= :stream (:kind %)) (:files result))))]
             (testing "the playlists of a stream, read with the Origin of the check"
               (is (= [:multivariant 700.0 true] ((juxt :kind :duration :ended?) (:stream facts))))
               (is (= #{:check/duration} (found result "/master.m3u8")) "100 seconds longer than the feed says")
               (is (every? :origin (filter #(str/ends-with? (:path %) ".m3u8") @requests))))
             (testing "a playlist that web players can't read, of codecs that the feed doesn't name"
               (is (= #{:check/cors :check/duration :check/codecs}
                      (found (check [(item "/closed.m3u8" "hvc1.1.6.L93.B0,mp4a.40.2")]) "/closed.m3u8"))))))))))

#?(:clj
   (deftest a-video-of-the-type-psp-1-names
     (with-file-server (byte-array 100) {}
       (fn [url]
         (let [feed   {:rss/items [{:rss/title "E" :rss/enclosure {:url url :type "video/m4v" :length 100}}]}
               result (check/check-files! feed {:read? true :allow-pred local?})]
           (is (contains? (:facts (first (:files result))) :media)
               "is read, as one of Apple's video/x-m4v is"))))))

#?(:clj
   (deftest a-type-that-no-reader-knows
     (with-file-server (byte-array 100) {}
       (fn [url]
         (let [feed   {:rss/items [{:rss/title "E" :rss/enclosure {:url url :type "audio/x-wav" :length 100}}]}
               result (check/check-files! feed {:read? true :allow-pred local?})]
           (is (some (comp #{:check/no-reader} :id) (:report result))
               "says that it wasn't read, rather than nothing"))))))

#?(:cljs
   (deftest checking-a-feed-with-fetch
     (async done
       (let [real      (.-fetch js/globalThis)
             node?     http/node?
             responses {"https://a/t.vtt" #(js/Response. "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n"
                                                         #js {:status 200 :headers #js {"content-type" "text/vtt"}})
                        "https://a/c.json" #(js/Response. "{\"version\":\"1.2.0\",\"chapters\":[{\"startTime\":0}]}"
                                                          #js {:status 200 :headers #js {"content-type" "application/json+chapters"
                                                                                         "access-control-allow-origin" "*"}})}
             feed      {:rss/items [{:podcast/transcripts [{:url "https://a/t.vtt" :type "text/vtt"}]
                                     :podcast/chapters    {:url "https://a/c.json" :type "application/json+chapters"}}]}]
         ;; the global fetch, which sends outside Node
         (set! http/node? false)
         (set! (.-fetch js/globalThis) (fn [url _] (js/Promise.resolve ((responses url)))))
         (-> (check/check-files! feed {:read? true})
             (.then (fn [{:keys [files report]}]
                      (is (= [:transcript :chapters] (map :kind files)))
                      (is (= [1 1] (map #(get-in % [:facts :read :count]) files)))
                      (is (= [[:check/cors "https://a/t.vtt"]] (map (juxt :id :url) report)))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) real)
                         (set! http/node? node?)
                         (done))))))))
