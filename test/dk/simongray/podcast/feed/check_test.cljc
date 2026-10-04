(ns dk.simongray.podcast.feed.check-test
  "The checks over the network: the files of a feed, the verdicts on facts
  given here, and on the JVM a whole check against a server on localhost
  with a problem of each kind."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.string :as str])
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.test-support :refer [ascii fixture run #?@(:clj [be with-server])]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.time :as time])
  #?(:clj (:import [java.security MessageDigest])))

(def example
  (delay (podcast/parse (fixture "feeds/namespace-example.xml"))))

(defn- ids
  "The ids of the diagnostics of `file` with `facts`, under the `chosen`
  requirements."
  ([file facts]
   (set (map :id (check/verdicts file facts))))
  ([file facts chosen]
   (set (map :id (check/verdicts file facts chosen)))))

(defn- failed
  "The facts of a request or a reading that failed for `reason`."
  [reason]
  {:error {:id :file/unreachable :level :warning :reason reason}})

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
         (map (juxt :id :reason) (check/verdicts media {:head (failed "refused") :get (failed "refused")})))
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
      (is (= [[:check/ranges :warning]] (map (juxt :id :level) (check/verdicts media ignored))))
      (is (= [[:check/ranges :error]] (map (juxt :id :level) (check/verdicts media ignored :apple)))))
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
                (check/verdicts media (assoc served :media (failed "The file answered 403: https://a/e.mp3")))))))
  (testing "the integrity"
    (is (= #{:check/integrity} (ids media (assoc served :integrity {:valid? false :algorithm "sha384"}))))
    (is (= #{} (ids media (assoc served :integrity {:valid? true :algorithm "sha384"}))))
    (is (= [[:check/unverified "A PGP signature needs the key of the publisher."]]
           (map (juxt :id :reason)
                (check/verdicts media
                                (assoc served :integrity {:valid? nil
                                                          :error  {:id     :file/unverifiable
                                                                   :reason "A PGP signature needs the key of the publisher."}})))))
    (is (= #{:check/unverified} (ids media (assoc served :integrity (assoc (failed "request timed out") :valid? nil))))))
  (is (every? :message (check/verdicts media (assoc served :head {:status 405}))) "every diagnostic with its message")
  (is (= [:rss/items 0 :rss/enclosure] (:path (first (check/verdicts media (assoc served :head {:status 405})))))))

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
             (set (map (juxt :id :level) (check/verdicts artwork small own))))
          "with a level of its own")
      (is (= #{:check/artwork-size}
             (ids artwork small (assoc-in (:apple check/requirements) [:artwork :sizes] [3000 6000])))
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
      (is (= #{} (ids transcript {:get (assoc answer :cors check/origin) :read read})) "the origin of the check allowed")
      (is (= #{} (ids transcript {:get (assoc answer :cors check/origin :url "https://cdn/t.vtt") :read read}))
          "a server that allows the origin it gets allows null too")
      (is (= #{:check/cors} (ids transcript {:get (assoc answer :cors "null") :read read})))
      (is (= #{} (ids transcript {:get (assoc answer :cors "null" :url "https://cdn/t.vtt") :read read}))))
    (testing "each redirect on the way"
      (let [moved (assoc answer :url "https://cdn/t.vtt")]
        (is (= [[:check/cors "https://a/t.vtt"]]
               (map (juxt :id :redirect) (check/verdicts transcript {:get (assoc moved :redirects [{:status 302 :url "https://a/t.vtt"}])
                                                                     :read read})))
            "a redirect that does not allow it")
        (is (= #{} (ids transcript {:get (assoc moved :redirects [{:status 302 :url "https://a/t.vtt" :cors "*"}]) :read read})))))
    (is (= #{} (ids transcript {:get (-> answer (dissoc :cors) (assoc :filtered? true)) :read read}))
        "an answer that a browser let through")
    (is (= #{:check/type} (ids transcript {:get (assoc answer :type "application/octet-stream") :read read})))
    (is (= #{:check/empty} (ids transcript {:get answer :read {:count 0 :report []}})))
    (is (= [[:check/unread "Unexpected end of input"]]
           (map (juxt :id :reason)
                (check/verdicts transcript
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
     #(async/then (check/check-files! feed {:send send :max-files 10 :allow? (constantly true)})
                  (fn [result]
                    (is (= 51 (count (:files result))) "every file that the feed names")
                    (is (= {nil 10 :max-files 41} (left result)))
                    (is (= [[:max-files 41]] (told result)) "one diagnostic counts the files that the check left out")
                    (is (= 20 (count @sent)))
                    (is (= #{[30 (:max-bytes check/default-limits) true]}
                           (set (map (juxt :timeout-seconds :max-bytes (comp some? :allow?)) @sent)))
                        "each request carries the limits and the guard")))
     #(let [ms (atom 0)]
        (async/then (check/check-files! feed {:send        send
                                       :concurrency 1
                                       :max-seconds 10
                                       :now         (fn [] (time/->inst (swap! ms + 1000)))})
                    (fn [result]
                      (is (= [[:max-seconds (get (left result) :max-seconds)]] (told result)))
                      (is (< 40 (get (left result) :max-seconds)) "no file starts after the time of the check")))))))

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
     #(async/then (check/check-files! feed {:send send :integrity? true :allow? (constantly true)})
                  (fn [result]
                    (let [whole (filter (fn [r] (and (not= :head (:method r)) (nil? (:range r)))) @sent)]
                      (is (= {:valid? true :algorithm "sha256"}
                             (get-in (first (:files result)) [:facts :integrity])))
                      (is (= [[alt :bytes true]] (map (juxt :url :as (comp some? :allow?)) whole))
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
                 check (fn [opts] (check/check-files! feed (merge {:url (str base "/feed.xml") :requirements :apple} opts)))
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
                     result (check {:integrity? true :allow? allow?})
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
               (let [result (check/check-files! {:rss/items []} {:url (str (str/replace base "http://" "http://user:pw@") "/feed.xml")})]
                 (is (= [(str base "/feed.xml")] (map :url (:files result))) "the URL without them")
                 (is (= #{"/feed.xml"} (set (keep #(when (:authorization %) (:path %)) @requests))))))))))))

#?(:cljs
   (deftest checking-a-feed-with-fetch
     (async done
       (let [real      (.-fetch js/globalThis)
             responses {"https://a/t.vtt" #(js/Response. "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n"
                                                         #js {:status 200 :headers #js {"content-type" "text/vtt"}})
                        "https://a/c.json" #(js/Response. "{\"version\":\"1.2.0\",\"chapters\":[{\"startTime\":0}]}"
                                                          #js {:status 200 :headers #js {"content-type" "application/json+chapters"
                                                                                         "access-control-allow-origin" "*"}})}
             feed      {:rss/items [{:podcast/transcripts [{:url "https://a/t.vtt" :type "text/vtt"}]
                                     :podcast/chapters    {:url "https://a/c.json" :type "application/json+chapters"}}]}]
         ;; the global fetch, as http/call! finds it
         (set! (.-fetch js/globalThis) (fn [url _] (js/Promise.resolve ((responses url)))))
         (-> (check/check-files! feed {:read? true})
             (.then (fn [{:keys [files report]}]
                      (is (= [:transcript :chapters] (map :kind files)))
                      (is (= [1 1] (map #(get-in % [:facts :read :count]) files)))
                      (is (= [[:check/cors "https://a/t.vtt"]] (map (juxt :id :url) report)))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) real)
                         (done))))))))
