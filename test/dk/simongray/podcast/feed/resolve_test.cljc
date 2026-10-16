(ns dk.simongray.podcast.feed.resolve-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def example
  (delay (rss/parse (fixture "feeds/namespace-example.xml"))))

(deftest text
  (is (= "A" (resolve/title {:rss/title "A" :itunes/title "B"})))
  (is (= "B" (resolve/title {:itunes/title "B"})))
  (is (= "<p>full</p>" (resolve/description {:content/encoded "<p>full</p>" :rss/description "short"})))
  (is (= "short" (resolve/description {:rss/description "short" :itunes/summary "s"})))
  (testing "authors, with names picked out of email fields"
    (is (= "John Doe" (resolve/author @example)))
    (is (= "Jane" (resolve/author {:dc/creator "Jane" :rss/author "a@b.c (Al)"})))
    (is (= "Al" (resolve/author {:rss/author "a@b.c (Al)"})))
    (is (= "ed@b.c" (resolve/author {:rss/managing-editor "ed@b.c"})))
    (is (= "Al" (resolve/author {:rss/author "  a@b.c(Al) "})))
    (is (= "Al (Jr)" (resolve/author {:rss/author "a@b.c (Al (Jr))"})))
    (is (= "Al (Jr)" (resolve/author {:rss/author "Al (Jr)"})) "no address, no name to pick out")
    (is (nil? (resolve/author {})))
    (let [ats (str (apply str (repeat 100000 "@")) " x")]
      (is (= ats (resolve/author {:rss/author ats})) "read in linear time")))
  (testing "links, permalinks included"
    (is (= "https://x/page" (resolve/link {:rss/link "https://x/page"})))
    (is (= "https://x/1" (resolve/link {:rss/guid {:content "https://x/1"}})))
    (is (nil? (resolve/link {:rss/guid {:content "https://x/1" :is-perma-link false}})))
    (is (nil? (resolve/link {:rss/guid {:content "abc"}}))))
  (testing "the URLs in show notes, as a listener can follow them"
    (is (= ["https://a.example/x" "javascript:alert(1)"
            "https://nytimes.com/audioapp" "https://www.goalhanger.com"
            "https://cdn.example/i.png"
            "https://show.example/ep/5" "https://show.example/notes"
            "https://show.example/ep/page.html"]
           (map #(resolve/notes-url "https://show.example/ep/" %)
                ["https://a.example/x" "javascript:alert(1)"
                 "nytimes.com/audioapp" " www.goalhanger.com"
                 "//cdn.example/i.png"
                 "5" "/notes"
                 "page.html"]))
        "an absolute URL as it is, html-pieces checks it")
    (is (= ["https://example.com" nil]
           (map #(resolve/notes-url nil %) ["example.com" "page.html"]))
        "a path with no page to resolve it against"))
  (is (= #inst "2020-10-09T04:30:38Z" (resolve/published @example)))
  (is (nil? (resolve/published {:rss/pub-date "soon"}))))

(deftest artwork
  (let [f @example
        items (:rss/items f)]
    (testing "the largest podcast:image meant as artwork wins"
      (is (= "https://example.com/images/show/pci_avatar-massive.png" (resolve/artwork f)))
      (is (= "https://example.com/images/ep3/pci_avatar-massive.png" (resolve/artwork (first items)))))
    (testing "falling back through the vocabularies, and to the feed"
      (is (= "https://example.com/ep0001/artMd.jpg" (resolve/artwork (nth items 2))))
      (is (= "https://example.com/images/show/pci_avatar-massive.png" (resolve/artwork f {})))
      (is (= "https://i/rss.jpg" (resolve/artwork {:rss/image {:url "https://i/rss.jpg"}})))
      (is (= "https://i/gp.jpg" (resolve/artwork {:googleplay/image {:href "https://i/gp.jpg"} :rss/image {:url "https://i/rss.jpg"}})))
      (is (nil? (resolve/artwork {}))))
    (testing "shape and purpose are read"
      (is (= "https://i/sq.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/wide.jpg" :aspect-ratio "16/9"}
                                                {:href "https://i/sq.jpg"}]})))
      (is (= "https://i/it.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/banner.jpg" :purpose "banner"}]
                               :itunes/image   {:href "https://i/it.jpg"}})))
      (is (= "https://i/300.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/big.jpg" :width "big"}
                                                {:href "https://i/300.jpg" :width 300}]}))
          "a width that did not read is no width")
      (is (= "https://i/sq.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/sq.mp4" :type "video/mp4" :purpose "artwork"}
                                                {:href "https://i/sq.jpg"}]}))
          "never a video"))
    (testing "the sizes of the artwork, for a srcset"
      (is (= [{:href "https://i/300.jpg" :width 300} {:href "https://i/3000.jpg" :width 3000}]
             (resolve/artwork-sizes {:podcast/images [{:href "https://i/3000.jpg" :width 3000}
                                                      {:href "https://i/banner.jpg" :width 1200 :purpose "banner"}
                                                      {:href "https://i/any.jpg"}
                                                      {:href "https://i/300.jpg" :width 300 :purpose "artwork"}]}))
          "narrowest first, and only the artwork whose width is known")
      (is (= [] (resolve/artwork-sizes {:itunes/image {:href "https://i/it.jpg"}}))))
    (is (= 6 (count (resolve/images f))))
    (is (= #{"artwork"} (:purpose (first (resolve/images f)))))
    (is (= "video/mp4" (:type (first (resolve/images {:podcast/images [{:href "https://i/a.mp4" :type "video/mp4"}]})))))))

(deftest posters
  (let [feed {:podcast/images [{:href "https://i/show-poster.jpg" :purpose "poster"}]}
        item {:podcast/images [{:href "https://i/sq.jpg" :purpose "artwork"}
                               {:href "https://i/1280.jpg" :purpose "poster social" :width 1280}
                               {:href "https://i/1920.jpg" :purpose "POSTER" :width 1920}
                               {:href "https://i/loop.mp4" :purpose "poster" :type "video/mp4" :width 3840}]}]
    (is (= "https://i/1920.jpg" (resolve/poster item)) "the largest picture whose purpose is poster")
    (is (= "https://i/show-poster.jpg" (resolve/poster feed {})) "the feed's when the item has none")
    (is (nil? (resolve/poster {:podcast/images [{:href "https://i/wide.jpg" :aspect-ratio "16/9"}]}))
        "no other image stands in for one")))

(deftest flags-and-numbers
  (is (false? (resolve/explicit? @example)))
  (is (true? (resolve/explicit? {:googleplay/explicit "yes"})))
  (is (false? (resolve/explicit? {:googleplay/explicit "clean"})))
  (is (nil? (resolve/explicit? {})))
  (is (true? (resolve/explicit? {:itunes/explicit true} {})))
  (is (false? (resolve/explicit? {:itunes/explicit true} {:itunes/explicit false})))
  (is (= 3793 (resolve/duration {:itunes/duration 3793})))
  (is (= 12 (resolve/duration {:itunes/duration "bad" :media/contents [{:duration 12}]})))
  (is (= 12 (resolve/duration {:media/group {:contents [{:duration 12}]}})))
  (is (nil? (resolve/duration {})))
  (testing "numbering prefers the podcast namespace"
    (is (= {:type "full" :season 1 :season-name "Podcasting 2.0" :episode 3}
           (resolve/numbering (first (:rss/items @example)))))
    (is (= {:type "bonus" :season 2 :episode 7}
           (resolve/numbering {:itunes/season 2 :itunes/episode 7 :itunes/episode-type "Bonus"})))
    (is (= {:type "full" :episode 204 :episode-display "Ch.3"}
           (resolve/numbering {:podcast/episode {:content 204 :display "Ch.3"} :itunes/episode 3})))
    (is (= {:type "full" :season 2 :episode 7}
           (resolve/numbering {:podcast/season {:content "Two"} :itunes/season 2
                               :podcast/episode {:content "7a"} :itunes/episode 7}))
        "a number that did not read leaves the other namespace to answer")
    (is (= {:type "full"} (resolve/numbering {})))))

(deftest media
  (let [item (first (:rss/items @example))
        all  (resolve/enclosures item)]
    (is (= 11 (count all)))
    (is (= {:url "https://example.com/file-03.mp3" :length 43200000 :type "audio/mpeg" :source :enclosure}
           (first all)))
    (is (= {:url "ipfs://someRandomMpegFile03" :source :alternate :type "audio/mpeg"
            :length 43200000 :bitrate 128000 :default true :title "Standard"}
           (nth all 2)))
    (is (= "https://example.com/file-03.mp3" (:url (resolve/enclosure item))))
    (is (= [{:type "sri" :value "sha384-ExVqijgYHm15PqQqdXfW95x+Rs6C+d6E/ICxyQOeFevnxNLR/wtJNrNYTjIysUBo"}]
           (distinct (keep :integrity all)))
        "each source of an alternate carries the integrity of its file")
    (testing "without an enclosure, the default alternate, then anything"
      (is (= "https://a/hi.opus"
             (:url (resolve/enclosure {:podcast/alternate-enclosures
                                       [{:type "audio/opus" :sources [{:uri "https://a/lo.opus"}]}
                                        {:type "audio/opus" :default true :sources [{:uri "https://a/hi.opus"}]}]}))))
      (is (= {:url "https://m/1.mp4" :type "video/mp4" :length 99 :source :media}
             (resolve/enclosure {:media/contents [{:url "https://m/1.mp4" :type "video/mp4" :file-size 99}]})))
      (is (nil? (resolve/enclosure {}))))
    (testing "no picture of a blog post, or other content that doesn't play"
      (is (nil? (resolve/enclosure {:media/contents [{:url "https://m/post.jpg" :medium "image"}]})))
      (is (nil? (resolve/enclosure {:media/contents [{:url "https://m/post.png" :type "image/png"}]})))
      (is (nil? (resolve/enclosure {:media/group {:contents [{:url "https://m/a.pdf" :medium "Document"}]}})))
      (is (= "https://m/1.mp3"
             (:url (resolve/enclosure {:media/contents [{:url "https://m/cover.jpg" :medium "image"}
                                                        {:url "https://m/1.mp3" :medium "audio"}]})))))
    (testing "transcripts by type preference"
      (is (= "https://example.com/episode1/transcript.vtt" (:url (resolve/transcript item))))
      (is (= "https://example.com/ep3/transcript.txt" (:url (resolve/transcript nil item {:types ["text/plain"]}))))
      (is (= "text/plain" (:type (resolve/transcript nil item {:types ["application/json"]}))))
      (is (nil? (resolve/transcript {})))
      (is (nil? (resolve/transcript {} {})))
      (is (= "vtt" (:url (resolve/transcript {:podcast/transcripts [{:url "txt" :type "text/plain"}
                                                                    {:url "vtt" :type "text/vtt; charset=utf-8"}]})))
          "whatever the parameters of a type, e.g. a charset"))
    (testing "transcripts by language, with the feed's language for unlabelled ones"
      (let [multilingual {:podcast/transcripts [{:url "en" :type "text/vtt" :language "en-US"}
                                                {:url "da" :type "text/vtt" :language "da"}
                                                {:url "none" :type "application/x-subrip"}]}]
        (is (= "en" (:url (resolve/transcript multilingual))))
        (is (= "da" (:url (resolve/transcript nil multilingual {:languages ["da" "en"]}))))
        (is (= "en" (:url (resolve/transcript nil multilingual {:languages ["EN"]}))))
        (is (= "none" (:url (resolve/transcript nil multilingual {:languages ["sv"]})))
            "an unlabelled file might be Swedish; a labelled one is certainly not")
        (is (= "none" (:url (resolve/transcript {:rss/language "sv-SE"} multilingual {:languages ["sv"]}))))
        (is (= "en" (:url (resolve/transcript {:rss/language "fr"} multilingual {:languages ["en"]}))))))
    (testing "transcripts with timings"
      (let [mixed {:podcast/transcripts [{:url "html" :type "text/html"}
                                         {:url "captions" :type "text/plain" :rel "captions"}
                                         {:url "vtt" :type "text/vtt"}]}]
        (is (= "vtt" (:url (resolve/transcript nil mixed {:timed? true}))))
        (is (= "captions" (:url (resolve/transcript nil mixed {:timed? true :types ["text/plain" "text/html"]}))))
        (is (= "html" (:url (resolve/transcript nil mixed {:types ["text/html"]}))))
        (is (= "srt" (:url (resolve/transcript {:podcast/transcripts [{:url "txt" :type "text/plain"}
                                                                      {:url "srt" :type "application/srt"}]})))
            "SRT by any of its names ranks above plain text")))
    (is (= "https://example.com/ep3_chapters.json" (resolve/chapters-url item)))
    (is (= [{:start-time 0 :title "Intro"}]
           (:chapters (resolve/embedded-chapters {:psc/chapters {:chapters [{:start "00:00:00.000" :title "Intro"}]}}))))
    (is (nil? (resolve/embedded-chapters {})))
    (testing "chapters inferred from the show notes"
      (is (= ["Intro" "Outro"]
             (map :title (:chapters (resolve/inferred-chapters {:rss/description "<p>00:00 Intro<br>45:00 Outro</p>"})))))
      (is (nil? (resolve/inferred-chapters item))))))

(deftest enclosures-by-preference
  (let [item {:rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}
              :podcast/alternate-enclosures [{:type "audio/opus" :bitrate 32000 :sources [{:uri "https://a/lo.opus"}]}
                                             {:type "audio/opus" :bitrate 96000 :default true :sources [{:uri "https://a/hi.opus"}]}
                                             {:type "video/mp4" :bitrate 2000000 :sources [{:uri "https://a/1.mp4"}]}]}]
    (is (= "https://a/1.mp3" (:url (resolve/enclosure item))))
    (is (= "https://a/hi.opus" (:url (resolve/enclosure item {:types ["audio/opus"]}))))
    (is (= "https://a/lo.opus" (:url (resolve/enclosure item {:types ["audio/opus"] :max-bitrate 64000}))))
    (is (= "https://a/1.mp4" (:url (resolve/enclosure item {:types ["video/mp4" "audio/opus"]}))))
    (is (= "https://a/1.mp3" (:url (resolve/enclosure item {:types ["audio/flac"]})))))
  (let [item {:rss/enclosure {:url "https://a/en.mp3" :type "audio/mpeg"}
              :podcast/alternate-enclosures [{:type "audio/mpeg" :lang "da-DK" :sources [{:uri "https://a/da.mp3"}]}
                                             {:type "video/mp4" :height 1080 :sources [{:uri "https://a/1080.mp4"}]}
                                             {:type "video/mp4" :height 480 :sources [{:uri "https://a/480.mp4"}]}]}]
    (is (= "https://a/da.mp3" (:url (resolve/enclosure item {:languages ["da"]}))) "the language first")
    (is (= "https://a/en.mp3" (:url (resolve/enclosure item {:languages ["en"]})))
        "a file that names no language before one that doesn't match")
    (is (= "https://a/480.mp4" (:url (resolve/enclosure item {:types ["video/mp4"] :max-height 720}))))
    (is (= "https://a/1080.mp4" (:url (resolve/enclosure item {:types ["video/mp4"]}))))
    (testing "audio or video, as the listener prefers"
      (is (= "https://a/1080.mp4" (:url (resolve/enclosure item {:video? true}))))
      (is (= "https://a/480.mp4" (:url (resolve/enclosure item {:video? true :max-height 720}))))
      (is (= "https://a/en.mp3" (:url (resolve/enclosure item {:video? false}))))
      (is (= "https://a/da.mp3" (:url (resolve/enclosure item {:video? true :languages ["da"]})))
          "the language before the kind")
      (let [streamed {:rss/enclosure {:url "https://a/en.mp3" :type "audio/mpeg"}
                      :podcast/alternate-enclosures
                      [{:type "application/x-mpegURL" :sources [{:uri "https://a/m.m3u8"}]}]}]
        (is (= "https://a/m.m3u8" (:url (resolve/enclosure streamed {:video? true})))
            "a stream that says nothing counts as a video")
        (is (= "https://a/en.mp3" (:url (resolve/enclosure streamed {:video? false})))))
      (let [peertube {:rss/enclosure {:url "https://p/360.mp4" :type "video/mp4"}
                      :podcast/alternate-enclosures
                      [{:type "application/x-mpegURL" :sources [{:uri "https://p/master.m3u8"}]}]}]
        (is (= "https://p/360.mp4" (:url (resolve/enclosure peertube {:video? false})))
            "for audio, no stream that says nothing before a video file"))))
  (let [video-only {:rss/enclosure {:url "https://a/v.mp4" :type "video/mp4"}}]
    (is (= "https://a/v.mp4" (:url (resolve/enclosure video-only {:video? false})))
        "a preference, so a show of video alone still plays")))

(deftest subtitles-of-media-rss
  (let [item {:podcast/transcripts [{:url "https://a/t.html" :type "text/html"}]
              :media/subtitles     [{:href "https://a/item.vtt" :type "text/vtt" :lang "en"}]
              :media/group         {:contents [{:url "https://a/1.mp4" :type "video/mp4"
                                                :subtitles [{:href "https://a/da.vtt" :type "text/vtt" :lang "da"}]}]}}]
    (is (= [{:url "https://a/t.html" :type "text/html"}
            {:url "https://a/da.vtt" :type "text/vtt" :language "da" :rel "captions" :source :media}
            {:url "https://a/item.vtt" :type "text/vtt" :language "en" :rel "captions" :source :media}]
           (resolve/transcripts item))
        "the podcast namespace, then the deepest subtitles first")
    (is (= "https://a/da.vtt" (:url (resolve/transcript nil item {:languages ["da"]}))))
    (is (= "https://a/da.vtt" (:url (resolve/transcript item))) "WebVTT before HTML")))

(deftest content-links
  (is (= [{:href "https://youtube.com/watch?v=1" :text "Watch on YouTube"} {:href "https://twitch.tv/x"}]
         (resolve/content-links {:podcast/content-links [{:href "https://youtube.com/watch?v=1" :content " Watch on YouTube "}
                                                         {:href "https://twitch.tv/x" :content ""}
                                                         {:content "no address"}]}))))

(deftest audio-or-video
  (let [item                  {:rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}
                               :podcast/alternate-enclosures
                               [{:type "video/mp4" :height 720 :sources [{:uri "https://a/720.mp4"}
                                                                         {:uri "https://a/720.torrent" :content-type "application/x-bittorrent"}]}
                                {:type "application/x-mpegURL" :length 0 :height 1080 :sources [{:uri "https://a/master.m3u8"}]}]}
        [mp3 mp4 torrent hls] (resolve/enclosures item)]
    (testing "a video by its type, its height or its Media RSS medium"
      (is (= [false true true true] (map resolve/video? [mp3 mp4 torrent hls])))
      (is (resolve/video? {:url "https://m/1" :medium "Video" :type "application/octet-stream"}))
      (is (not (resolve/video? {:url "https://m/1.mp4" :medium "audio" :type "video/mp4"})) "the medium first")
      (is (false? (resolve/video? {:url "https://p/a.m4a" :type "audio/mp4" :height 0})) "an audio type, whatever the height")
      (is (nil? (resolve/video? {:url "https://p/a" :type "application/octet-stream" :height 0})) "no height at 0"))
    (testing "the codecs of a stream, or nothing"
      (is (true? (resolve/video? {:url "https://a/m.m3u8" :type "application/vnd.apple.mpegurl"
                                  :codecs "mp4a.40.2, avc1.64001f"})))
      (is (false? (resolve/video? {:url "https://a/m.m3u8" :type "application/x-mpegURL" :codecs "mp4a.40.2"})))
      (is (nil? (resolve/video? {:url "https://a/m.m3u8" :type "application/x-mpegURL" :codecs "mp4a.40.2,xyz1"}))
          "a codec that isn't known leaves it open")
      (is (nil? (resolve/video? {:url "https://a/m.m3u8" :type "application/vnd.apple.mpegurl" :length 0}))
          "an HLS stream that says nothing"))
    (testing "the kinds of media of an episode"
      (is (= #{:audio :video} (resolve/media-kinds item)))
      (is (= #{:audio} (resolve/media-kinds {:rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}
                                             :podcast/alternate-enclosures
                                             [{:type "application/x-mpegURL" :sources [{:uri "https://a/m.m3u8"}]}]}))
          "a stream that says nothing isn't counted"))
    (testing "a file to download, not a stream, a torrent or an IPFS address"
      (is (= [true true false false] (map resolve/downloadable? [mp3 mp4 torrent hls])))
      (is (not (resolve/downloadable? {:url "ipfs://QmdwGqd3d2" :type "audio/mpeg"})))
      (is (not (resolve/downloadable? {:url "https://hls.example/pc20_236.m3u8?t=1" :type "application.x-mpegURL"}))
          "a playlist by its path, whatever the type says")
      (is (not (resolve/downloadable? {:url "https://a/stream" :type "application/mpegurl"})))
      (is (resolve/downloadable? {:url "https://a.m3u/1.mp3" :type "audio/mpeg"})))
    (testing "only what can be played"
      (is (= "https://a/1.mp3" (:url (resolve/enclosure item {:types ["video/mp4"] :playable-pred resolve/audio?}))))
      (is (= "https://a/720.mp4" (:url (resolve/enclosure item {:playable-pred resolve/video?}))))
      (is (nil? (resolve/enclosure (dissoc item :rss/enclosure) {:playable-pred resolve/audio?})))
      (is (nil? (resolve/enclosure {:rss/enclosure {:url "https://p/360.mp4" :type "video/mp4"}
                                    :podcast/alternate-enclosures
                                    [{:type "application/x-mpegURL" :sources [{:uri "https://p/m.m3u8"}]}]}
                                   {:playable-pred resolve/audio?}))
          "no stream that doesn't say, for audio alone"))))

(deftest what-to-ask-the-browser
  (testing "audio, with what the feed declares"
    (is (= {:type :file :audio {:content-type "audio/mpeg" :bitrate 128000}}
           (resolve/decoding-configuration {:url "https://a/1.mp3" :type "audio/mpeg" :bitrate 128000})))
    (is (= {:type :file :audio {:content-type "audio/mp4"}}
           (resolve/decoding-configuration {:url "https://a/1.m4a" :type "audio/mp4"}))
        "without codecs, as most feeds give AAC")
    (is (= {:type :file :audio {:content-type "audio/mp4; codecs=\"mp4a.40.2\"" :bitrate 64000}}
           (resolve/decoding-configuration {:url "https://a/1.m4a" :type "audio/mp4" :codecs "mp4a.40.2" :bitrate 64000.0})))
    (is (= {:type :file :audio {:content-type "audio/ogg; codecs=\"opus\"" :channels "2" :samplerate 48000}}
           (resolve/decoding-configuration {:url "https://a/1.ogg" :type "audio/ogg" :codecs "opus" :channels 2 :samplingrate 48}))
        "with the sample rate of Media RSS in Hz")
    (is (= {:type :file :audio {:content-type "audio/mp4; codecs=\"mp4a.40.5\""}}
           (resolve/decoding-configuration {:url "https://a/1.m4a" :type "audio/mp4; codecs=\"mp4a.40.5\""}))
        "the codecs of the type")
    (is (= {:type :file :audio {:content-type "audio/mpeg"}}
           (resolve/decoding-configuration {:url "https://a/1.mp3" :type "audio/mpeg" :codecs "mp3"}))
        "but none for a type that implies its codec"))
  (testing "a type that browsers know by another name"
    (is (= {:type :file :audio {:content-type "audio/ogg; codecs=\"opus\"" :bitrate 12000}}
           (resolve/decoding-configuration {:url "https://a/1.opus" :type "audio/opus" :bitrate 12000}))
        "Ogg Opus, which feeds call by the RTP type")
    (is (= {:type :file :audio {:content-type "audio/mp4"}}
           (resolve/decoding-configuration {:url "https://a/1.m4a" :type "audio/x-m4a"})))
    (is (= {:type :file :audio {:content-type "audio/mp4; codecs=\"mp4a.40.2\""}}
           (resolve/decoding-configuration {:url "https://a/1.m4a" :type "Audio/M4A" :codecs "mp4a.40.2"}))
        "in any case, with the codec that the feed declares")
    (is (= {:type :file :audio {:content-type "audio/mpeg"}}
           (resolve/decoding-configuration {:url "https://a/1.mp3" :type "audio/mpeg3"}))))
  (testing "a video, with one codec of each kind"
    (is (= {:type  :file
            :video {:content-type "video/mp4; codecs=\"avc1.42E01E\""}
            :audio {:content-type "audio/mp4; codecs=\"mp4a.40.2\""}}
           (resolve/decoding-configuration {:url "https://a/720.mp4" :type "video/mp4" :codecs "avc1.42E01E, mp4a.40.2" :height 720})))
    (is (= {:type  :file
            :video {:content-type "video/mp4; codecs=\"avc1.64001F\"" :width 1280 :height 720 :bitrate 2500000 :framerate 30}}
           (resolve/decoding-configuration {:url "https://a/720.mp4" :type "video/mp4" :codecs "avc1.64001F"
                                            :width 1280 :height 720 :bitrate 2500000 :framerate 30}))
        "with its size, bitrate and frame rate")
    (is (= {:type :file :video {:content-type "video/mp4"}}
           (resolve/decoding-configuration {:url "https://a/720.mp4" :type "video/mp4" :width 1280 :height 720 :bitrate 2500000}))
        "but none of them without its frame rate, which Media Capabilities needs too")
    (is (= {:type :file :audio {:content-type "audio/mp4; codecs=\"mp4a.40.2\""}}
           (resolve/decoding-configuration {:url "https://m/1.mp4" :type "video/mp4" :medium "audio" :codecs "mp4a.40.2"}))
        "and audio under an audio type"))
  (testing "a stream that doesn't say what it holds, as a video"
    (is (= {:type :file :video {:content-type "application/x-mpegurl"}}
           (resolve/decoding-configuration {:url "https://a/m.m3u8" :type "application/x-mpegURL"}))))
  (testing "nothing for a type that says nothing of the format"
    (doseq [type [nil "application/octet-stream" "Binary/Octet-Stream" "None" "audio/"]]
      (is (nil? (resolve/decoding-configuration {:url "https://a/1.mp3" :type type})) (pr-str type)))))

(deftest people-and-inheritance
  (let [f    @example
        item (first (:rss/items f))]
    (is (= [{:name "Adam Curry" :role "host" :group "cast"
             :href "https://www.podchaser.com/creators/adam-curry-107ZzmWE5f"
             :img "https://example.com/images/adamcurry.jpg"}
            {:name "Dave Jones" :role "guest" :group "cast"
             :href "https://github.com/daveajones/" :img "https://example.com/images/davejones.jpg"}
            {:name "Becky Smith" :role "cover art designer" :group "visuals"
             :href "https://example.com/artist/beckysmith"}]
           (resolve/people item)))
    (testing "the item's people replace the feed's; an item without any inherits"
      (is (= ["Adam Curry" "Dave Jones" "Becky Smith"] (map :name (resolve/people f item))))
      (is (= ["Terry"] (map :name (resolve/people {:podcast/people [{:content "Terry"}]} {})))))
    (testing "inherited values"
      (is (= [{:url "https://example.com/donate" :content "Support the show!"}] (resolve/funding f item)))
      (is (= [{:url "https://x" :content "Give"}] (resolve/funding f {:podcast/funding [{:url "https://x" :content "Give"}]})))
      (is (= "my-podcast-license-v1" (:content (resolve/license f item))))
      (is (= "matrix" (:protocol (resolve/chat f item))))
      (is (= "Birmingham Civil Rights Museum" (:content (first (resolve/locations f item)))))
      (is (= "Austin, TX" (:content (first (resolve/locations f {}))))))
    (testing "value blocks"
      (is (= 49 (get-in (resolve/value f item) [:recipients 0 :split])))
      (is (= 99 (get-in (resolve/value f {}) [:recipients 0 :split])))
      (is (= 99 (get-in (resolve/value f) [:recipients 0 :split]))))))

(deftest other-feeds
  (let [f @example]
    (testing "the shows of the podroll, pointers to nowhere left out"
      (is (= ["https://feeds.podcastindex.org/pc20.xml" "https://feedurl.example.rss"]
             (map :feed-url (resolve/podroll f))))
      (is (= [{:feed-guid "g"}] (resolve/podroll {:podcast/podroll {:remote-items [{:title "Nowhere"} {:feed-guid "g"}]}})))
      (is (empty? (resolve/podroll {}))))
    (testing "the entries of a list feed"
      (let [playlist (rss/parse (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">"
                                         "<channel><title>Hits</title><podcast:medium>musicL</podcast:medium>"
                                         "<podcast:remoteItem feedGuid=\"f1\" itemGuid=\"i1\"/>"
                                         "<podcast:remoteItem itemGuid=\"i2\"/>"
                                         "<podcast:remoteItem feedGuid=\"f2\" itemGuid=\"i3\" medium=\"publisher\"/>"
                                         "</channel></rss>"))]
        (is (= [{:feed-guid "f1" :item-guid "i1"} {:feed-guid "f2" :item-guid "i3" :medium "publisher"}]
               (resolve/remote-items playlist)))
        (is (= 2 (count (:remote-items (resolve/properties playlist)))))
        (is (nil? (resolve/publisher playlist)) "an entry of a list is not its publisher")))
    (testing "the publisher feed"
      (is (= {:feed-guid "000000a0-aaa5-bbbf-b765-6333d449661a" :feed-url "https://publisherexample.xml"
              :medium "publisher"}
             (resolve/publisher f)))
      (is (nil? (resolve/publisher {:podcast/publisher {}})) "a publisher named in text, which the model drops")
      (is (nil? (resolve/publisher {})))
      (testing "or a remote item of the channel with the medium publisher, as some feeds write it"
        (let [album {:podcast/medium "music" :podcast/remote-items [{:feed-guid "artist" :medium "Publisher"}]}]
          (is (= {:feed-guid "artist" :medium "Publisher"} (resolve/publisher album)))
          (is (= [[:podcast/remote-items]] (:publisher (resolve/sources album))))
          (is (= "p" (:feed-guid (resolve/publisher (assoc album :podcast/publisher {:remote-item {:feed-guid "p"}}))))
              "the element wins")
          (is (nil? (resolve/publisher (assoc album :podcast/medium "publisher")))
              "in a publisher feed, a remote item is one of its shows"))))))

(deftest comments-soundbites-and-texts
  (let [item (first (:rss/items @example))]
    (testing "where the comments are, in order of priority"
      (is (= [["activitypub" 1 "@dave"] ["twitter" 2 "@podcastindexorg"]]
             (map (juxt :protocol :priority :account-id) (resolve/social-interacts item))))
      (let [mastodon {:protocol "ActivityPub" :uri "https://m/1" :priority 2 :pubDate "old"}
            bluesky  {:protocol "atproto" :uri "at://b/1"}
            twitter  {:protocol "twitter" :uri "https://t/1" :priority 1}]
        (is (= [{:protocol "twitter" :uri "https://t/1" :priority 1}
                {:protocol "activitypub" :uri "https://m/1" :priority 2}
                {:protocol "atproto" :uri "at://b/1"}]
               (resolve/social-interacts {:podcast/social-interacts [bluesky mastodon twitter]}))
            "those without a priority last, and only the known attributes")
        (is (empty? (resolve/social-interacts {:podcast/social-interacts [{:protocol "activitypub"}]}))
            "a thread needs its root post")
        (testing "and the wish for no public comments, which wins"
          (is (= [{:protocol "disabled"}]
                 (resolve/social-interacts {:podcast/social-interacts [mastodon {:protocol "Disabled"}]}))))
        (testing "an item's own, else the feed's"
          (is (= ["https://m/1"] (map :uri (resolve/social-interacts {:podcast/social-interacts [mastodon]} {}))))
          (is (= [{:protocol "disabled"}]
                 (resolve/social-interacts {:podcast/social-interacts [mastodon]}
                                           {:podcast/social-interacts [{:protocol "disabled"}]})))
          (is (= ["at://b/1"]
                 (map :uri (resolve/social-interacts {:podcast/social-interacts [{:protocol "disabled"}]}
                                                     {:podcast/social-interacts [bluesky]})))))))
    (testing "soundbites, those that make no sense left out"
      (is (= [{:start-time 33.833 :duration 60}] (resolve/soundbites item)))
      (is (= [{:start-time 1234.5 :duration 42.25 :title "Why the Podcast Namespace Matters"}
              {:start-time 73 :duration 60}]
             (resolve/soundbites {:podcast/soundbites [{:start-time 1234.5 :duration 42.25
                                                        :content "Why the Podcast Namespace Matters"}
                                                       {:start-time 73 :duration 60 :content " "}
                                                       {:start-time "abc" :duration 30}
                                                       {:start-time -1 :duration 30}
                                                       {:start-time 5 :duration 0}]}))))
    (testing "texts by purpose, and whether AI made the content"
      (let [feed {:podcast/txt [{:purpose "applepodcastsverify" :content "05124"}
                                {:purpose "AI-Content" :content "true"}
                                {:content "abc"}
                                {:content " "}]}]
        (is (= {"applepodcastsverify" ["05124"] "ai-content" ["true"] nil ["abc"]} (resolve/txt feed)))
        (is (= {} (resolve/txt {})))
        (is (true? (resolve/ai-content? feed)))
        (is (true? (resolve/ai-content? feed {})) "an item inherits the answer, as for explicit?")
        (is (false? (resolve/ai-content? feed {:podcast/txt [{:purpose "ai-content" :content "False"}]})))
        (is (nil? (resolve/ai-content? {:podcast/txt [{:purpose "ai-content" :content "maybe"}]})))))))

(deftest the-feed-as-a-whole
  (let [f @example]
    (is (= "podcast" (resolve/medium f)))
    (is (= "podcast" (resolve/medium {})))
    (is (not (resolve/list-feed? f)))
    (is (resolve/list-feed? {:podcast/medium "musicL"}))
    (is (resolve/list-feed? {:podcast/medium "mixed"}))
    (is (not (resolve/complete? f)))
    (is (resolve/complete? {:itunes/complete true}))
    (is (resolve/complete? {:podcast/update-frequency {:complete true :content "Done"}}))
    (is (resolve/locked? f))
    (is (not (resolve/locked? {})))
    (is (= "https://new/feed.xml" (resolve/new-feed-url {:itunes/new-feed-url "https://new/feed.xml"})))
    (is (nil? (resolve/new-feed-url {:itunes/new-feed-url "https://a/rss"
                                     :atom/links          [{:href "http://a/rss/" :rel "self"}]}))
        "a feed that names itself by its self link, in another form")
    (is (nil? (resolve/new-feed-url {:itunes/new-feed-url "https://a/rss"} ["https://old/rss" "https://A/rss"]))
        "or by a URL that it was fetched from")
    (is (= "https://new/rss" (resolve/new-feed-url {:itunes/new-feed-url "https://new/rss"} ["https://a/rss"])))
    (is (= "https://a/rss" (resolve/self-url {:atom/links [{:href "https://hub" :rel "hub"} {:href "https://a/rss" :rel "Self"}]})))
    (is (nil? (resolve/self-url f)))
    (is (= ["https://hub" "https://hub2"]
           (resolve/hubs {:atom/links [{:href "https://hub" :rel "hub"} {:href "https://a/rss" :rel "self"}
                                       {:href "https://hub2" :rel "HUB"}]}))
        "the WebSub hubs of a feed, in its order")
    (is (empty? (resolve/hubs f)))
    (is (= "?page=2" (resolve/next-page {:atom/links [{:href "https://a/rss" :rel "self"} {:href "?page=2" :rel "Next"}]}))
        "the next page of a paged feed, as the feed writes it")
    (is (= "/2025.xml" (resolve/next-page {:atom/links [{:href "/2025.xml" :rel "prev-archive"}]})) "or of an archived one")
    (is (nil? (resolve/next-page f)))
    (is (= [["Technology"] ["News" "Tech News"]] (resolve/categories f)))))

(deftest renditions-in-a-media-group
  (let [item {:media/group {:contents [{:url "https://a/1.mp4" :type "video/mp4" :is-default true}
                                       {:url "https://a/1.webm" :type "video/webm"}]}}]
    (is (= ["https://a/1.mp4" "https://a/1.webm"] (map :url (resolve/enclosures item))))
    (is (= "https://a/1.mp4" (:url (resolve/enclosure item)))))
  (let [item {:media/group {:contents [{:url "https://a/hi.mp3" :bitrate 128} {:url "https://a/lo.mp3" :bitrate 48}]}}]
    (is (= [128000 48000] (map :bitrate (resolve/enclosures item))) "Media RSS gives kilobits per second")
    (is (= "https://a/lo.mp3" (:url (resolve/enclosure item {:max-bitrate 64000}))))))

(deftest live-items-in-order
  (let [pending {:status "pending" :start #inst "2026-10-01T18:00:00Z" :rss/title "Next week"}
        live    {:status "live" :start #inst "2026-09-25T18:00:00Z" :rss/title "Now"}
        ended   {:status "ended" :start #inst "2026-09-18T18:00:00Z" :end #inst "2026-09-18T19:00:00Z"
                 :rss/title "Last week"}
        older   {:status "ended" :start #inst "2026-09-11T18:00:00Z" :rss/title "Older"}
        feed    {:podcast/live-items [older pending ended live]}]
    (is (= ["Now" "Next week" "Last week" "Older"] (map :rss/title (resolve/live-items feed))))
    (is (= [] (resolve/live-items {})))
    (testing "on air by status, or by the clock when the status lags"
      (is (resolve/live? live #inst "2026-01-01T00:00:00Z"))
      (is (resolve/live? pending #inst "2026-10-01T18:30:00Z"))
      (is (not (resolve/live? pending #inst "2026-10-01T17:00:00Z")))
      (is (not (resolve/live? ended #inst "2026-09-18T18:30:00Z"))))))

(deftest blocking
  (is (resolve/blocked? {:itunes/block true} :apple))
  (is (not (resolve/blocked? {:itunes/block true} :spotify)))
  (is (resolve/blocked? {:googleplay/block true} "google"))
  (is (resolve/blocked? {:podcast/blocks [{:content true}]} :spotify))
  (is (not (resolve/blocked? {:podcast/blocks [{:content true} {:id "google" :content false}]} :google)))
  (is (resolve/blocked? {:podcast/blocks [{:id "Amazon" :content true}]} :amazon))
  (is (not (resolve/blocked? {:podcast/blocks [{:id "amazon" :content true}]} :apple)))
  (is (not (resolve/blocked? {} :apple)))
  (testing "the specification example blocks everyone but Google and Amazon"
    (is (resolve/blocked? @example :spotify))
    (is (not (resolve/blocked? @example :google)))
    (is (not (resolve/blocked? @example :amazon)))))

(deftest listening-order
  (let [ep   (fn [title date & {:keys [season episode type]}]
               (cond-> {:rss/title title :rss/pub-date date}
                 season  (assoc :itunes/season season)
                 episode (assoc :itunes/episode episode)
                 type    (assoc :itunes/episode-type type)))
        show {:rss/items [(ep "E1" #inst "2026-01-01T00:00:00Z" :episode 1)
                          (ep "E2" #inst "2026-01-08T00:00:00Z" :episode 2)
                          (ep "Trailer" #inst "2025-12-20T00:00:00Z" :type "trailer")
                          (ep "E3" #inst "2026-01-15T00:00:00Z" :episode 3)]}]
    (testing "episodic: newest first, the trailer ahead"
      (is (= ["Trailer" "E3" "E2" "E1"] (map :rss/title (resolve/episodes show))))
      (is (= [{:items (resolve/episodes show)}] (resolve/seasons show))))
    (testing "an episode that the feed lists twice, once, as it first comes"
      (let [twice (-> show
                      (update :rss/items (partial mapv #(assoc % :rss/guid {:content (:rss/title %)})))
                      (update :rss/items conj (assoc (ep "E2 again" #inst "2026-02-01T00:00:00Z")
                                                     :rss/guid {:content "E2"})))]
        (is (= ["Trailer" "E3" "E2" "E1"] (map :rss/title (resolve/episodes twice))))))
    (testing "serial: by season and number, trailers and bonuses where Apple puts them"
      (let [serial {:itunes/type "serial"
                    :rss/items [(ep "S2E1" #inst "2026-03-01T00:00:00Z" :season 2 :episode 1)
                                (ep "S1E2" #inst "2026-01-08T00:00:00Z" :season 1 :episode 2)
                                (assoc (ep "S1E1" #inst "2026-01-01T00:00:00Z" :episode 1)
                                       :podcast/season {:content 1 :name "First"})
                                (ep "S1 trailer" #inst "2025-12-20T00:00:00Z" :season 1 :type "trailer")
                                (ep "S1E1 bonus" #inst "2026-01-03T00:00:00Z" :season 1 :episode 1 :type "bonus")
                                (ep "Show trailer" #inst "2025-12-01T00:00:00Z" :type "trailer")
                                (ep "Show bonus" #inst "2026-04-01T00:00:00Z" :type "bonus")
                                (ep "S2 bonus" #inst "2026-03-05T00:00:00Z" :season 2 :type "bonus")]}]
        (is (= ["Show trailer" "S1 trailer" "S1E1" "S1E1 bonus" "S1E2" "S2E1" "S2 bonus" "Show bonus"]
               (map :rss/title (resolve/episodes serial))))
        (is (= [nil 1 2] (map :season (resolve/seasons serial))))
        (is (= "First" (:name (second (resolve/seasons serial)))))
        (is (= ["Show trailer" "Show bonus"] (map :rss/title (:items (first (resolve/seasons serial))))))))))

(deftest everything-at-once
  (let [feed {:rss/title       "Show"
              :itunes/explicit true
              :itunes/image    {:href "https://s/art.png"}
              :podcast/people  [{:content "Host"}]
              :podcast/txt     [{:purpose "ai-content" :content "true"}]}
        item {:rss/title          "Ep"
              :itunes/title       "Ep"
              :rss/pub-date       #inst "2026-01-01T00:00:00Z"
              :itunes/duration    60
              :podcast/soundbites [{:start-time 5 :duration 20}]}
        show (resolve/properties feed)
        ep   (resolve/properties feed item)]
    (testing "every answer in one map, plain keys, nothing unanswered"
      (is (= {:title "Show" :artwork "https://s/art.png" :explicit? true
              :people [{:name "Host" :role "host" :group "cast"}]
              :images [{:href "https://s/art.png" :source :itunes}]
              :txt {"ai-content" ["true"]} :ai-content? true
              :medium "podcast" :list-feed? false :complete? false :locked? false}
             show))
      (is (= {:title "Ep" :published #inst "2026-01-01T00:00:00Z" :duration 60
              :numbering {:type "full"} :artwork "https://s/art.png" :explicit? true
              :people [{:name "Host" :role "host" :group "cast"}]
              :soundbites [{:start-time 5 :duration 20}] :ai-content? true}
             ep)
          "inheriting from the feed as the accessors do")
      (is (= "Ep" (:title (merge item ep))))
      (is (= "Ep" (:rss/title (merge item ep))) "merged beside the raw fields"))
    (testing "and where each answer came from"
      (is (= {:title       [[:rss/title]]
              :artwork     [[:itunes/image]]
              :explicit?   [[:itunes/explicit]]
              :people      [[:podcast/people]]
              :images      [[:itunes/image]]
              :txt         [[:podcast/txt]]
              :ai-content? [[:podcast/txt]]}
             (resolve/sources feed))
          "defaults name no source")
      (is (= {:title       [[:item :itunes/title] [:item :rss/title]]
              :published   [[:item :rss/pub-date]]
              :duration    [[:item :itunes/duration]]
              :soundbites  [[:item :podcast/soundbites]]
              :artwork     [[:feed :itunes/image]]
              :explicit?   [[:feed :itunes/explicit]]
              :ai-content? [[:feed :podcast/txt]]
              :people      [[:feed :podcast/people]]}
             (resolve/sources feed item))
          "two fields that agree are both named; the feed's where the item inherits"))
    (testing "on a real feed"
      (let [f (deref example)
            i (first (:rss/items f))]
        (is (= "Podcasting 2.0 Namespace Example" (:title (resolve/properties f))))
        (is (every? (complement namespace) (keys (resolve/properties f i))))
        (is (= [[:item :rss/title]] (:title (resolve/sources f i))))
        (is (nil? (:numbering (resolve/sources f i))) "a composite names no source")
        (is (= {:podroll [[:podcast/podroll]] :publisher [[:podcast/publisher]]}
               (select-keys (resolve/sources f) [:podroll :publisher])))
        (is (= ["activitypub" "twitter"] (map :protocol (:social-interacts (resolve/properties f i)))))))))
