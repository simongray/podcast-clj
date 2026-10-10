(ns dk.simongray.podcast.format.hls-test
  "HLS playlists read as data: the variants and renditions of a
  multivariant playlist, the duration of a media playlist, and read! over
  a network of fixed answers, redirects and failures included."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.hls :as hls]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.wary-fetch.async :as async]))

(def multivariant
  (str/join "\n"
            ["#EXTM3U"
             "#EXT-X-VERSION:6"
             "## a comment"
             (str "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\","
                  "LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"subs/en.m3u8\"")
             (str "#EXT-X-STREAM-INF:BANDWIDTH=1352370,AVERAGE-BANDWIDTH=831345,"
                  "CODECS=\"hvc1.1.6.L93.90,mp4a.40.2\",RESOLUTION=1280x720,"
                  "FRAME-RATE=29.970,SUBTITLES=\"subs\",CLOSED-CAPTIONS=NONE")
             "v0/playlist.m3u8"
             ""
             "#EXT-X-STREAM-INF:BANDWIDTH=192000,CODECS=\"mp4a.40.2\""
             "https://cdn.example/audio.m3u8"]))

(def media
  (str/join "\n"
            ["#EXTM3U"
             "#EXT-X-VERSION:3"
             "#EXT-X-TARGETDURATION:6"
             "#EXT-X-PLAYLIST-TYPE:VOD"
             "#EXTINF:6.006,"
             "seg0.ts"
             "#EXTINF:5.5,The end"
             "seg1.ts"
             "#EXT-X-ENDLIST"]))

(deftest reading-a-playlist
  (testing "the variants and renditions of a multivariant playlist"
    (is (= {:kind       :multivariant
            :version    6
            :variants   [{:url "https://a.example/show/v0/playlist.m3u8" :bandwidth 1352370
                          :average-bandwidth 831345 :codecs "hvc1.1.6.L93.90,mp4a.40.2"
                          :width 1280 :height 720 :frame-rate 29.97 :subtitles "subs"}
                         {:url "https://cdn.example/audio.m3u8" :bandwidth 192000 :codecs "mp4a.40.2"}]
            :renditions [{:type :subtitles :group-id "subs" :name "English" :language "en"
                          :default? true :autoselect? true :forced? false
                          :url "https://a.example/show/subs/en.m3u8"}]}
           (hls/parse multivariant "https://a.example/show/master.m3u8"))))
  (testing "the duration of a media playlist"
    (is (= {:kind :media :version 3 :target-duration 6 :segments 2 :duration 11.506
            :playlist-type "VOD" :ended? true}
           (hls/parse media))))
  (testing "no playlist"
    (is (nil? (hls/parse "<html></html>")))
    (is (nil? (hls/parse "")))
    (is (= "NONE, or a list" (get (hls/attributes "A=\"NONE, or a list\",B=1") "A"))
        "a quoted string holds its commas")))

(deftest knowing-a-playlist
  (is (hls/playlist? {:url "https://a/x" :type "application/vnd.apple.mpegurl"}))
  (is (hls/playlist? {:url "https://a/x" :type "Application/X-MpegURL; charset=utf-8"}))
  (is (hls/playlist? {:url "https://a/master.M3U8?token=1" :type "application.x-mpegURL"})
      "by its path, whatever the type says")
  (is (not (hls/playlist? {:url "https://a.m3u8.example/1.mp3" :type "audio/mpeg"}))))

(defn- network
  "A sender that answers each URL by `answers`, a map of URL to the part of
  a response that isn't a 200 at that URL, and fails for any other."
  [answers]
  (fn [{:keys [url]}]
    (if (contains? answers url)
      (merge {:status 200 :url url :headers {}} (get answers url))
      (throw (ex-info (str "No route to " url) {:url url})))))

(deftest reading-a-stream
  (let [master "https://a.example/ep.m3u8"
        moved  "https://hls.example/1/master.m3u8"]
    (run
     (fn []
       (async/then (hls/read! master {:send (network {master {:url moved :body multivariant}
                                                      "https://hls.example/1/v0/playlist.m3u8" {:body media}})})
                   (fn [stream]
                     (testing "the first variant's media playlist for the duration, after a redirect"
                       (is (= ["https://hls.example/1/v0/playlist.m3u8" 11.506 2 true]
                              [(:url (first (:variants stream))) (:duration stream) (:segments stream)
                               (:ended? stream)]))))))
     (fn []
       (async/then (hls/read! master {:send (network {master {:body multivariant}})})
                   (fn [stream]
                     (testing "a media playlist that fails leaves the duration out"
                       (is (= :multivariant (:kind stream)))
                       (is (nil? (:duration stream)))
                       (is (= [:file/unreachable] (map :id (:report stream))))))))
     (fn []
       (async/then (hls/read! master {:send (network {master {:body "<html></html>"}})})
                   (fn [{:keys [error]}]
                     (testing "a page that isn't a playlist"
                       (is (= [:file/malformed master] ((juxt :id :url) error)))))))
     (fn []
       (async/then (hls/read! master {:send (network {master {:status 404}})})
                   (fn [{:keys [error]}]
                     (testing "a playlist that isn't there"
                       (is (= [:file/unreachable 404] ((juxt :id :status) error))))))))))
