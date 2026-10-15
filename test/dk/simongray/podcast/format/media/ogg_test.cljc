(ns dk.simongray.podcast.format.media.ogg-test
  "The reader of Ogg files, on files built here byte by byte: the pages and
  their CRC, the duration of Opus and of Vorbis, where the audio starts,
  the chapters and the artwork of the comments, and the streams and files
  that it can't read."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.media.ogg :as ogg]
            [dk.simongray.podcast.format.media.ogg.impl :as ogg.impl]
            [dk.simongray.podcast.test-support :refer [ascii view]]
            [dk.simongray.podcast.test-support.media
             :refer [about= jpeg opus-file opus-head opus-packet opus-tags packets-page picture-comment
                     vorbis-file]]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn- ids
  "The ids of the report of `r`."
  [r]
  (map :id (ogg/report r)))

(deftest opus-files
  (let [r (ogg/read (view (opus-file :pages 3)))]
    (is (= {:codecs "opus" :duration (- 3 (/ 312 48000.0))} (select-keys r [:codecs :duration]))
        "three seconds of packets less the pre-skip")
    (is (= {:sample-rate 48000 :channels 2} (select-keys r [:sample-rate :channels])) "at the 48 kHz of Opus")
    (is (= (count (concat (packets-page 2 0 1 0 [(opus-head 312)]) (packets-page 0 0 1 1 [(opus-tags)])))
           (:media-start r))
        "the audio starts after the page that the comment header finishes")
    (is (= [] (ids r))))
  (testing "audio that starts after zero, as a recording of a live stream does"
    (is (about= (- 3 (/ 312 48000.0)) (:duration (ogg/read (view (opus-file :start 4800000)))))))
  (testing "a first page of audio that ends before the samples of its packets"
    (let [file (concat (packets-page 2 0 1 0 [(opus-head 312)])
                       (packets-page 0 0 1 1 [(opus-tags)])
                       (packets-page 0 1000 1 2 (repeat 50 opus-packet))
                       (packets-page 4 49000 1 3 (repeat 50 opus-packet)))
          r    (ogg/read (view file))]
      (is (not (contains? r :duration)) "is invalid, RFC 7845, section 4.5")
      (is (= [{:id :file/malformed :part :duration}] (map #(select-keys % [:id :part]) (ogg/report r)))))
    (let [file (concat (packets-page 2 0 1 0 [(opus-head 312)])
                       (packets-page 0 0 1 1 [(opus-tags)])
                       (packets-page 4 40000 1 2 (repeat 50 opus-packet)))]
      (is (= (/ (- 40000 312) 48000.0) (:duration (ogg/read (view file))))
          "unless the page ends the stream, whose end the granule position trims"))))

(deftest vorbis-files
  (let [r (ogg/read (view (vorbis-file ["title=Episode 1" "ARTIST=A Host" "COMMENT=About it"] 1024 (+ 1024 441000))))]
    (is (= {:codecs "vorbis" :duration (/ 442024 44100.0) :sample-rate 44100 :channels 2}
           (select-keys r [:codecs :duration :sample-rate :channels])))
    (is (= {:title "Episode 1" :artist "A Host" :description "About it"} (:tags r))
        "the comments as tags, whatever the case of their names, and the comment as the description")
    (is (= [] (ids r))))
  (testing "audio that starts after zero, right to within a long block"
    (let [r (ogg/read (view (vorbis-file [] (+ 441000 1500) (+ 441000 1500 441000))))]
      (is (< (abs (- (:duration r) 10)) (/ 2048 44100.0))))))

(deftest chapters-and-artwork
  (testing "the chapters of Xiph's chapter extension, in the order of their numbers"
    (let [r (ogg/read (view (opus-file :comments ["CHAPTER002=00:01:30.500" "chapter002name=Talk"
                                                  "CHAPTER001=00:00:00.000" "CHAPTER001NAME=Intro"
                                                  "CHAPTER001URL=https://example.com/"
                                                  "CHAPTER003=soon"])))]
      (is (= [{:start-time 0 :title "Intro" :url "https://example.com/"} {:start-time 90.5 :title "Talk"}]
             (:chapters r)))
      (is (= [{:id :ogg/chapter-start :name "CHAPTER003" :value "soon"}]
             (map #(select-keys % [:id :name :value]) (ogg/report r)))
          "a chapter whose start isn't a time is left out")))
  (testing "the front cover before another picture, across the pages of a large comment header"
    (let [png [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]
          r   (ogg/read (view (opus-file :comments [(picture-comment 1 "image/png" png)
                                                    (picture-comment 3 "image/jpeg" (concat jpeg (repeat 150000 1)))])))]
      (is (= ["image/jpeg" "Cover" (+ 10 150000)]
             ((juxt :type :description (comp bytes/length :data)) (:artwork r))))
      (is (= [] (ids r)))))
  (testing "the type of a picture that a loose type declares, by the quirk"
    (let [file (opus-file :comments [(picture-comment 3 "JPG" jpeg)])]
      (is (= "image/jpeg" (:type (:artwork (ogg/read (view file))))))
      (is (= [:ogg/image-type] (ids (ogg/read (view file)))))
      (is (= "jpg" (:type (:artwork (ogg/read (view file) {:except #{:ogg/image-type}})))))))
  (is (nil? (:artwork (ogg/read (view (opus-file :comments [(picture-comment 3 "-->" (ascii "https://example.com/a.jpg"))])))))
      "a picture that links to an image")
  (testing "a picture that isn't base64 is left out"
    (let [r (ogg/read (view (opus-file :comments ["METADATA_BLOCK_PICTURE=not base64"])))]
      (is (nil? (:artwork r)))
      (is (= [{:id :file/malformed :part :artwork}] (map #(select-keys % [:id :part]) (ogg/report r)))))))

(deftest streams-and-pages
  (testing "the Opus stream among the streams of the file, with pages of another stream between its own"
    (let [other #(packets-page %1 0 9 %2 [(concat [0x80] (ascii "theora") (repeat 20 0))])
          file  (concat (other 2 0)
                        (packets-page 2 0 1 0 [(opus-head 312)])
                        (other 0 1)
                        (packets-page 0 0 1 1 [(opus-tags)])
                        (packets-page 4 48000 1 2 (repeat 50 opus-packet))
                        (other 4 2))]
      (is (= (/ (- 48000 312) 48000.0) (:duration (ogg/read (view file)))))))
  (is (= [:ogg/unknown-codec]
         (ids (ogg/read (view (packets-page 2 0 1 0 [(concat [0x7F] (ascii "FLAC") (repeat 20 0))])))))
      "a stream of FLAC")
  (testing "a stream with no audio after its headers"
    (is (= [:ogg/no-audio]
           (ids (ogg/read (view (concat (packets-page 2 0 1 0 [(opus-head 312)]) (packets-page 4 0 1 1 [(opus-tags)]))))))))
  (testing "a file whose last 64 KB hold no page of the stream, as when another stream follows it"
    (let [r (ogg/read (view (concat (opus-file) (repeat 70000 0))))]
      (is (not (contains? r :duration)))
      (is (= [:ogg/no-end] (ids r)))))
  (testing "a page whose CRC doesn't match is no page"
    (let [file (vec (opus-file))
          bad  (assoc file 60 (bit-xor 1 (file 60)))]
      (is (= [:file/malformed] (map :id (view/report (view/reading #(ogg/read (view bad))))))))))

(deftest reading-in-parts
  (testing "a view that lacks bytes gives the ranges that it needs: the pages, then the end of the file"
    (let [b    (bytes/of (opus-file :pages 400))
          head (view/add (view/view (bytes/length b)) 0 (bytes/slice b 0 300) (bytes/length b))]
      (is (= [[(- (bytes/length b) ogg.impl/max-page) (bytes/length b)]]
             (:need (view/reading #(ogg/read head))))))))
