(ns dk.simongray.podcast.feed.check.rules-test
  "The rules of a check, on facts given here: the standards, and the
  requirements of a directory."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.feed.check.rules :as rules]))

(defn- ids
  "The ids of the diagnostics of `file` with `facts`, under the `chosen`
  requirements."
  ([file facts]
   (set (map :id (rules/verdicts file facts))))
  ([file facts chosen]
   (set (map :id (rules/verdicts file facts chosen)))))

(defn- failed
  "The facts of a request or a reading that failed for `reason`."
  [reason]
  {:error {:id :file/unreachable :level :warning :reason reason}})

(deftest verdicts-on-sources
  (let [torrent {:kind :media :url "https://a/v.torrent" :path [] :declared {:type "application/x-bittorrent"}}]
    (is (= #{} (ids torrent {:head {:status 200 :type "application/x-bittorrent"}
                             :get  {:status 200 :type "application/x-bittorrent" :length 900}}))
        "byte ranges matter to audio and video alone")))

(def media
  {:kind :media :url "https://a/e.mp3" :path [:rss/items 0 :rss/enclosure]
   :declared {:type "audio/mpeg" :length 1000 :duration 600}})

(def served
  "The answers of a server that does everything right to the requests of a
  check for `media`."
  {:head {:status 200 :url "https://a/e.mp3" :type "audio/mpeg" :length 1000}
   :get  {:status 206 :url "https://a/e.mp3" :type "audio/mpeg" :range {:start 0 :end 1 :total 1000}}})

(deftest verdicts-on-media
  (is (= #{} (ids media served)) "a file served as its feed says")
  (is (= [] (check/report [(assoc media :facts served)])) "in the report of its check too")
  (is (= [] (check/report [(assoc media :facts served)] :apple)) "and by the requirements of a directory")
  (is (= [[:check/unreachable "refused"]]
         (map (juxt :id :reason) (rules/verdicts media {:head (failed "refused") :get (failed "refused")})))
      "with the reason of the diagnostic in the facts")
  (is (= #{:check/status} (ids media (assoc served :get {:status 404 :url "https://a/e.mp3"}))))
  (is (= #{:check/status} (ids media (assoc served :get {:status "206" :url "https://a/e.mp3"})))
      "a status that is no number, as an app's own client can give it")
  (testing "a redirect that was not followed"
    (is (= #{:check/downgrade} (ids media (assoc served :get {:status 302 :url "https://a/e.mp3" :location "http://a/e.mp3"}))))
    (is (= #{:check/status} (ids media (assoc served :get {:status 302 :url "http://a/e.mp3" :location "http://b/e.mp3"})))
        "from http to http is no downgrade")
    (is (= #{:check/redirects} (ids media (assoc served :get {:status 302 :url "https://z/e.mp3" :location "https://y/e.mp3"
                                                              :redirects (vec (repeat 5 {:status 302 :url "https://x/e.mp3"}))}))))
    (is (= #{:check/status} (ids media (assoc served :get {:status 302 :url "https://z/e.mp3"})))
        "one without a Location"))
  (is (= #{:check/moved} (ids media (update served :get assoc :permanent-url "https://a/e.mp3"
                                            :redirects [{:status 301 :url "https://a/old.mp3"}]))))
  (is (= #{} (ids media (update served :get assoc :redirects [{:status 302 :url "https://a/e.mp3"}
                                                              {:status 301 :url "https://track/e.mp3"}])))
      "a permanent redirect after a temporary one does not move the URL of the feed")
  (is (= #{:check/head} (ids media (assoc served :head {:status 405}))))
  (is (= #{:check/head} (ids media (assoc served :head {:status 404 :type "text/html" :length 50})))
      "an answer to HEAD that failed tells nothing of the file")
  (is (= #{:check/type} (ids media (assoc-in served [:get :type] "application/octet-stream"))))
  (is (= [[:check/no-type "audio/mpeg"]]
         (map (juxt :id :served) (rules/verdicts (update media :declared dissoc :type) served)))
      "a type the feed doesn't declare, which the server names")
  (testing "the length"
    (is (= #{:check/length} (ids media (assoc-in served [:get :range :total] 2000))))
    (is (= #{} (ids (assoc-in media [:declared :length] 0) (assoc-in served [:get :range :total] 2000)))
        "a length of 0 is a size not known, as the RSS Profile has it")
    (is (= #{} (ids (assoc-in media [:declared :length] "1000 bytes") (assoc-in served [:get :range :total] 2000)))
        "a length that did not read as a number")
    (is (= #{} (ids media (-> served
                              (assoc-in [:get :range :total] nil)
                              (assoc :head {:status 200 :type "audio/mpeg" :length 500 :encoding "gzip"}))))
        "the length of the file in a content coding"))
  (testing "byte ranges, which Apple Podcasts requires"
    (let [ignored (assoc served :get {:status 200 :url "https://a/e.mp3" :type "audio/mpeg" :length 1000})]
      (is (= [[:check/ranges :warning]] (map (juxt :id :level) (rules/verdicts media ignored))))
      (is (= [[:check/ranges :error]] (map (juxt :id :level) (rules/verdicts media ignored :apple)))))
    (is (= #{} (ids media (assoc served :get {:status 206 :url "https://a/e.mp3" :type "audio/mpeg" :filtered? true})))
        "a browser hides the Content-Range of an answer from another site"))
  (testing "what the file says about itself"
    (is (= #{:check/format} (ids media (assoc served :media {:format :mp4 :duration 600 :report []}))))
    (is (= #{:check/format} (ids media (assoc served :media {:report [{:id :file/unknown-format :level :warning}]}))))
    (is (= #{:file/truncated} (ids media (assoc served :media {:report [{:id :file/truncated :level :warning}]})))
        "a file of the declared format that breaks off")
    (is (= #{:check/duration} (ids media (assoc served :media {:format :mp3 :duration 700 :report []}))))
    (is (= #{} (ids media (assoc served :media {:format :mp3 :duration 605 :report []}))) "within 10 seconds")
    (is (= [[:check/unread "The file answered 403: https://a/e.mp3"]]
           (map (juxt :id :reason)
                (rules/verdicts media (assoc served :media (failed "The file answered 403: https://a/e.mp3")))))))
  (testing "the video of an MP4 file"
    (let [video  (assoc media :declared {:type "video/mp4" :codecs "avc1.64001F,mp4a.40.2" :height 720})
          answer (-> served (assoc-in [:get :type] "video/mp4") (assoc-in [:head :type] "video/mp4"))
          read   #(assoc answer :media (merge {:format :mp4 :report [] :movie-first? true
                                               :codecs "avc1.64001F,mp4a.40.2" :height 720}
                                              %))]
      (is (= #{} (ids video (read {}))))
      (is (= #{} (ids video (read {:codecs "mp4a.40.2, AVC1.64001f"}))) "in any order and case")
      (is (= #{:check/codecs} (ids video (read {:codecs "hvc1.1.6.L93.90,mp4a.40.2"}))))
      (is (= #{:check/height} (ids video (read {:height 1080}))))
      (is (= #{:check/slow-start} (ids video (read {:movie-first? false}))))))
  (testing "the integrity"
    (is (= #{:check/integrity} (ids media (assoc served :integrity {:valid? false :algorithm "sha384"}))))
    (is (= #{} (ids media (assoc served :integrity {:valid? true :algorithm "sha384"}))))
    (is (= [[:check/unverified "A PGP signature needs the key of the publisher."]]
           (map (juxt :id :reason)
                (rules/verdicts media
                                (assoc served :integrity {:valid? nil
                                                          :error  {:id     :file/unverifiable
                                                                   :reason "A PGP signature needs the key of the publisher."}})))))
    (is (= #{:check/unverified} (ids media (assoc served :integrity (assoc (failed "request timed out") :valid? nil))))))
  (is (every? :message (rules/verdicts media (assoc served :head {:status 405}))) "every diagnostic with its message")
  (is (= [:rss/items 0 :rss/enclosure] (:path (first (rules/verdicts media (assoc served :head {:status 405})))))))

(def stream
  {:kind :stream :url "https://a/master.m3u8" :path [:rss/items 0 :podcast/alternate-enclosures 1]
   :declared {:type "application/x-mpegURL" :height 720 :codecs "avc1.64001F,mp4a.40.2" :duration 600}})

(def streamed
  "The answers to the requests of a check for `stream`, and what its
  playlists say."
  {:head   {:status 200 :url "https://a/master.m3u8" :type "application/vnd.apple.mpegurl" :cors "*"}
   :get    {:status 206 :url "https://a/master.m3u8" :type "application/vnd.apple.mpegurl" :cors "*"}
   :stream {:kind     :multivariant :duration 601.5 :ended? true
            :variants [{:height 720 :codecs "avc1.64001F,mp4a.40.2"} {:height 360 :codecs "avc1.64001E,mp4a.40.2"}]}})

(deftest verdicts-on-streams
  (is (= #{} (ids stream streamed)) "a stream as its feed says, that web players can read")
  (is (= #{:check/status} (ids stream (assoc streamed :get {:status 404 :url "https://a/master.m3u8"}))))
  (is (= #{:check/cors} (ids stream (update streamed :get dissoc :cors))) "a playlist that a web player can't read")
  (is (= #{:check/unread} (ids stream (assoc streamed :stream (failed "It isn't an HLS playlist")))))
  (is (= #{:check/duration} (ids stream (assoc-in streamed [:stream :duration] 700))))
  (is (= #{} (ids stream (update streamed :stream assoc :duration 700 :ended? false)))
      "a stream that hasn't ended")
  (is (= #{:check/codecs} (ids (assoc-in stream [:declared :codecs] "hvc1.1.6.L93.B0,mp4a.40.2") streamed)))
  (is (= #{:check/height} (ids (assoc-in stream [:declared :height] 1080) streamed)))
  (is (= #{} (ids (assoc-in stream [:declared :height] 360) streamed)) "the height of any variant"))

(def artwork
  {:kind :image :url "https://a/cover.jpg" :path [] :declared {:sources #{:itunes}}})

(def square-jpeg
  {:head  {:status 200 :url "https://a/cover.jpg" :type "image/jpeg" :last-modified "Sat, 26 Sep 2026 10:00:00 GMT"}
   :image {:type "image/jpeg" :width 3000 :height 3000 :color :rgb :alpha? false :process :baseline :bits 8}})

(deftest verdicts-on-images
  (is (= #{} (ids artwork square-jpeg :apple)) "artwork as Apple Podcasts wants it")
  (is (= #{:check/image-type} (ids artwork (assoc-in square-jpeg [:head :type] "image/jpg"))))
  (is (= #{:check/unread} (ids artwork (assoc square-jpeg :image (failed "The file answered 403: https://a/cover.jpg")))))
  (is (= #{:check/unreachable} (ids artwork {:head (failed "refused") :image (failed "refused")})))
  (is (= #{:file/malformed} (ids artwork (assoc square-jpeg :image {:type "image/jpeg" :report [{:id :file/malformed :level :warning}]}) :apple))
      "an image that does not read has no size to judge")
  (testing "the artwork requirements, which only the chosen requirements add"
    (let [png-art (assoc square-jpeg :image {:type "image/png" :width 1200 :height 1000 :color :grayscale :alpha? true})]
      (is (= #{:check/image-type} (ids artwork png-art)))
      (is (= #{:check/image-type :check/artwork-size :check/artwork-color :check/artwork-extension}
             (ids artwork png-art :psp-1)))
      (is (= #{:check/image-type :check/artwork-size :check/artwork-color :check/artwork-extension :check/artwork-alpha}
             (ids artwork png-art :apple)))
      (is (= #{:check/image-type :check/artwork-size :check/artwork-color :check/artwork-extension :check/artwork-alpha}
             (ids (assoc-in artwork [:declared :sources] #{:podcast :itunes}) png-art :apple))
          "an itunes:image that a podcast:image names too")
      (is (= #{:check/last-modified} (ids artwork (update square-jpeg :head dissoc :last-modified) :apple)))
      (is (= #{} (ids (assoc artwork :url "https://cdn/cover?src=https://x/a.png") square-jpeg :apple))
          "the extension of the path, not of a URL in the query")
      (is (= #{} (ids (assoc artwork :url "https://art.example.com?id=1") square-jpeg :apple))
          "nor of the host")
      (is (= #{:check/artwork-size} (ids artwork (update square-jpeg :image dissoc :height) :apple))
          "a height that the image gives only after its data")
      (is (= #{:check/artwork-type :check/artwork-extension}
             (ids (assoc artwork :url "https://a/cover.avif") {:head (assoc (:head square-jpeg) :type "image/avif")
                                                               :image {:type "image/avif"}} :apple))
          "a format whose header is not read, by its type alone")
      (is (= #{:check/artwork-type} (ids artwork (assoc-in square-jpeg [:image :process] :lossless) :apple)))
      (is (= #{:check/artwork-type} (ids artwork (update square-jpeg :image assoc :process :extended :bits 12) :apple))
          "JPEGs that browsers do not decode")))
  (testing "the requirements of a directory that the app gives"
    (let [own   {:name    "A directory of our own"
                 :artwork {:sizes [3000 6000] :types #{"image/webp"}}
                 :levels  {:check/artwork-type :warning}}
          small (assoc-in (assoc-in square-jpeg [:image :width] 1400) [:image :height] 1400)]
      (is (= #{} (ids artwork small :apple)))
      (is (= #{[:check/artwork-size :error] [:check/artwork-type :warning]}
             (set (map (juxt :id :level) (rules/verdicts artwork small own))))
          "with a level of its own")
      (is (= #{:check/artwork-size}
             (ids artwork small (assoc-in (:apple rules/requirements) [:artwork :sizes] [3000 6000])))
          "the requirements of Apple Podcasts with another size")
      (is (= #{} (ids artwork small :no-such-requirements)) "a name that requirements does not have adds nothing")))
  (testing "a podcast:image against its attributes"
    (let [image {:kind :image :url "https://a/i.webp" :path [] :declared {:sources #{:podcast}}}
          facts (fn [w h] {:head {:status 200 :type "image/webp"} :image {:type "image/webp" :width w :height h :color :rgb}})
          wide  (assoc image :declared {:sources #{:podcast} :width 1200 :height 630})]
      (is (= #{} (ids image (facts 512 512))))
      (is (= #{} (ids image (facts 1200 630)))
          "no size declared, as the images of the deprecated podcast:images come")
      (is (= #{} (ids (assoc image :declared {:sources #{:podcast} :width 1200 :height 630 :aspect-ratio "1.9"}) (facts 1200 630))))
      (is (= #{:check/image-size} (ids (assoc image :declared {:sources #{:podcast} :width 3000 :aspect-ratio "1/1"}) (facts 1400 1400))))
      (is (= #{:check/image-size} (ids wide (facts 630 1200))))
      (is (= #{} (ids wide (assoc-in (facts 630 1200) [:image :orientation] 6)))
          "an image that EXIF turns a quarter, as a viewer shows it")
      (let [spaces (apply str (repeat 200000 " "))
            sized  (fn [ratio] (assoc image :declared {:sources #{:podcast} :aspect-ratio ratio}))]
        (is (= #{:check/image-size} (ids (sized (str spaces "16" spaces ":" spaces "9" spaces)) (facts 1400 1400)))
            "the white space in a ratio, however much")
        (is (= #{} (ids (sized (str "1" spaces "x")) (facts 1200 630)))
            "a ratio that does not read claims nothing, and takes no time"))))
  (is (= #{:file/unknown-format} (ids artwork (assoc square-jpeg :image {:report [{:id :file/unknown-format :level :warning}]})))))

(deftest verdicts-on-transcripts-chapters-and-feeds
  (let [transcript {:kind :transcript :url "https://a/t.vtt" :path [:rss/items 0 :podcast/transcripts 0] :declared {:type "text/vtt"}}
        answer     {:status 200 :url "https://a/t.vtt" :type "text/vtt" :cors "*"}
        read       {:count 10 :report []}]
    (is (= #{} (ids transcript {:get answer :read read})))
    (is (= #{:check/cors} (ids transcript {:get (dissoc answer :cors) :read read})))
    (is (= #{:check/cors} (ids transcript {:get (assoc answer :cors "*, *") :read read})) "two values allow none")
    (testing "the origin, and the null that a browser names after a redirect to another origin"
      (is (= #{} (ids transcript {:get (assoc answer :cors rules/origin) :read read})) "the origin of the check allowed")
      (is (= #{} (ids transcript {:get (assoc answer :cors rules/origin :url "https://cdn/t.vtt") :read read}))
          "a server that allows the origin it gets allows null too")
      (is (= #{:check/cors} (ids transcript {:get (assoc answer :cors "null") :read read})))
      (is (= #{} (ids transcript {:get (assoc answer :cors "null" :url "https://cdn/t.vtt") :read read}))))
    (testing "each redirect on the way"
      (let [moved (assoc answer :url "https://cdn/t.vtt")]
        (is (= [[:check/cors "https://a/t.vtt"]]
               (map (juxt :id :redirect) (rules/verdicts transcript {:get (assoc moved :redirects [{:status 302 :url "https://a/t.vtt"}])
                                                                     :read read})))
            "a redirect that does not allow it")
        (is (= #{} (ids transcript {:get (assoc moved :redirects [{:status 302 :url "https://a/t.vtt" :cors "*"}]) :read read})))))
    (is (= #{} (ids transcript {:get (-> answer (dissoc :cors) (assoc :filtered? true)) :read read}))
        "an answer that a browser let through")
    (is (= #{:check/type} (ids transcript {:get (assoc answer :type "application/octet-stream") :read read})))
    (is (= #{:check/empty} (ids transcript {:get answer :read {:count 0 :report []}})))
    (is (= [[:check/unread "Unexpected end of input"]]
           (map (juxt :id :reason)
                (rules/verdicts transcript
                                {:get answer :read {:error {:id :file/malformed :reason "Unexpected end of input"}}}))))
    (is (= #{:check/head} (ids transcript {:head {:status 405} :get answer}))))
  (let [feed {:kind :feed :url "https://a/feed.xml" :path [] :declared {}}]
    (is (= #{} (ids feed {:get {:status 200 :url "https://a/feed.xml" :cors "*" :etag "\"1\""}})))
    (is (= #{:check/feed-cors :check/validators} (ids feed {:get {:status 200 :url "https://a/feed.xml"}})))
    (is (= #{} (ids feed {:head {:status 200 :etag "\"1\""} :get {:status 206 :url "https://a/feed.xml" :cors "*"}}))
        "the validators of the answer to HEAD, which a 206 need not repeat")
    (is (= #{} (ids feed {:get {:status 200 :url "https://a/feed.xml" :filtered? true}}))
        "a browser hides the ETag")
    (is (= #{:check/head} (ids feed {:head {:status 501} :get {:status 206 :url "https://a/feed.xml" :cors "*" :etag "\"1\""}})))))
