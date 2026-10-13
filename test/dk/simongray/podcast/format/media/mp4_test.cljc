(ns dk.simongray.podcast.format.media.mp4-test
  "The reader of MP4 files, on files built here byte by byte: a QuickTime
  chapter track, Nero chapters and artwork, movies in fragments, the
  codecs and the size of the tracks that play, and a chapter track built to
  hurt a reader."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.media.mp4 :as mp4]
            [dk.simongray.podcast.test-support :refer [ascii be view]]
            [dk.simongray.podcast.test-support.media
             :refer [aac-entry box chapter-file chapter-track-file fragmented-file jpeg movie-file mvhd playing-trak
                     trak video-entry]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]))

(deftest mp4-files
  (doseq [last? [false true]]
    (testing (if last? "the movie after the media data" "the movie before the media data")
      (let [r (mp4/read (view (chapter-file ["Intro" "Talk"] [60000 90500] last?)))]
        (is (= 150.5 (:duration r)))
        (is (= [{:start-time 0.0 :end-time 60.0 :title "Intro"} {:start-time 60.0 :end-time 150.5 :title "Talk"}]
               (:chapters r))))))
  (testing "Nero chapters, and the artwork of the iTunes metadata"
    (let [chpl  (box "chpl" [1 0 0 0] (be 4 0) [2]
                     (be 8 0) [5] (ascii "Intro")
                     (be 8 600000000) [4] (ascii "Talk"))
          meta  (box "meta" [0 0 0 0]
                     (box "hdlr" [0 0 0 0] (be 4 0) (ascii "mdir") (repeat 12 0) [0])
                     (box "ilst" (box "covr" (box "data" (be 4 13) (be 4 0) jpeg))))
          xs    (concat (box "ftyp" (ascii "M4A ") (be 4 0)) (box "moov" (mvhd 600 90000) (box "udta" chpl meta)))
          r     (mp4/read (view xs))]
      (is (= 150.0 (:duration r)))
      (is (= [{:start-time 0.0 :title "Intro"} {:start-time 60.0 :title "Talk"}] (:chapters r)))
      (is (= "image/jpeg" (:type (:artwork r))))))
  (testing "a movie header without a duration"
    (let [xs (concat (box "ftyp" (ascii "M4A ") (be 4 0))
                     (box "moov" (mvhd 1000 0) (trak 1 44100 (* 44100 90) "soun")))]
      (is (= 90.0 (:duration (mp4/read (view xs)))))
      (is (= [:mp4/zero-duration] (map :id (mp4/report (mp4/read (view xs))))))
      (is (not (contains? (mp4/read (view xs) {:except #{:mp4/zero-duration}}) :duration)))
      (is (= 90.0 (:duration (mp4/read (view (concat (box "ftyp" (ascii "M4A ") (be 4 0))
                                                     (box "moov" (mvhd 1000 0xFFFFFFFF) (trak 1 44100 (* 44100 90) "soun")))))))
          "a duration of all ones, which a header gives when it does not know")))
  (testing "a movie written in fragments"
    (let [file (fn [& {:keys [mehd index? per-sample?]}]
                 (fragmented-file mehd index? per-sample?))]
      (is (= 120.0 (:duration (mp4/read (view (file :mehd 120000))))) "the duration of all fragments in the mehd box")
      (is (= 3.0 (:duration (mp4/read (view (file :index? true))))) "the end of the last fragment that the index lists")
      (is (= 3.0 (:duration (mp4/read (view (file :index? true :per-sample? true))))) "with the durations of the samples")
      (let [r (mp4/read (view (file)))]
        (is (not (contains? r :duration)))
        (is (= [:mp4/fragments-unread] (map :id (mp4/report r))))))))

(deftest mp4-video-files
  (testing "the codecs and the size of the tracks that play, video first"
    (let [r (mp4/read (view (movie-file false
                                        (playing-trak 2 "soun" aac-entry)
                                        (playing-trak 1 "vide" (video-entry "avc1" (box "avcC" [1 0x64 0x00 0x1F 0xFF]))
                                                      :width 1280 :height 720))))]
      (is (= {:codecs "avc1.64001F,mp4a.40.2" :width 1280 :height 720 :movie-first? true}
             (select-keys r [:codecs :width :height :movie-first?])))))
  (testing "the codecs strings of HEVC, AV1, VP9 and the rest"
    (let [codecs #(:codecs (mp4/read (view (movie-file false (playing-trak 1 "vide" %)))))]
      (is (= "hvc1.1.6.L93.B0" (codecs (video-entry "hvc1" (box "hvcC" [1 0x01 0x60 0 0 0 0xB0 0 0 0 0 0 93])))))
      (is (= "av01.0.08M.08" (codecs (video-entry "av01" (box "av1C" [0x81 0x08 0x0C 0])))))
      (is (= "vp09.00.10.08" (codecs (video-entry "vp09" (box "vpcC" [1 0 0 0] [0 10 0x80 0])))))
      (is (= "avc1" (codecs (video-entry "avc1" []))) "the type alone without a configuration")
      (is (= "opus" (:codecs (mp4/read (view (movie-file false (playing-trak 1 "soun" (box "Opus" (repeat 28 0))))))))
          "the type of another codec, in lower case")))
  (testing "a movie after the media data starts late"
    (is (false? (:movie-first? (mp4/read (view (movie-file true (playing-trak 1 "soun" aac-entry))))))))
  (testing "where the media data starts, after the movie or before it"
    (let [first-movie (movie-file false (playing-trak 1 "soun" aac-entry))
          last-movie  (movie-file true (playing-trak 1 "soun" aac-entry))
          ;; the movie and no more, of a file whose size is known
          b           (bytes/of first-movie)
          moov-end    (- (bytes/length b) 24)
          head        (view/add (view/view (bytes/length b)) 0 (bytes/copy b 0 moov-end) (bytes/length b))]
      (is (= (- (count first-movie) 16) (:media-start (mp4/read (view first-movie)))))
      (is (= 24 (:media-start (mp4/read (view last-movie)))) "past the ftyp box and the header of the mdat box")
      (is (= (:duration (mp4/read (view first-movie))) (:duration (mp4/read head))))
      (is (not (contains? (mp4/read head) :media-start)) "nothing beyond the bytes at hand")))
  (testing "a disabled track and a track of chapter pictures don't play"
    (let [chapters (box "tref" (box "chap" (be 4 3)))
          audio    (box "trak"
                        (box "tkhd" [0 0 0 3] (be 4 0) (be 4 0) (be 4 1) (repeat 60 0))
                        chapters
                        (box "mdia"
                             (box "mdhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 1000) (be 4 60000) [0 0 0 0])
                             (box "hdlr" [0 0 0 0] (be 4 0) (ascii "soun") (repeat 12 0) [0])
                             (box "minf" (box "stbl" (box "stsd" [0 0 0 0] (be 4 1) aac-entry)))))
          r        (mp4/read (view (movie-file false
                                               audio
                                               (playing-trak 2 "vide" (video-entry "avc1" []) :width 640 :height 360 :disabled? true)
                                               (playing-trak 3 "vide" (video-entry "jpeg" []) :width 600 :height 600))))]
      (is (= "mp4a.40.2" (:codecs r)))
      (is (nil? (:height r))))))

(deftest a-hurtful-chapter-track
  (let [n    50000
        stbl (box "stbl"
                  (box "stts" [0 0 0 0] (be 4 1) (be 4 n) (be 4 1))
                  (box "stsz" [0 0 0 0] (be 4 2) (be 4 n))
                  (box "stsc" [0 0 0 0] (be 4 n) (into [] (mapcat #(concat (be 4 (inc %)) (be 4 1) (be 4 1))) (range n)))
                  (box "stco" [0 0 0 0] (be 4 n) (into [] (mapcat (fn [_] (be 4 8))) (range n))))]
    (is (= mp4/max-samples (count (:chapters (mp4/read (view (chapter-track-file stbl n))))))
        "50000 chunks in as many runs, read in one pass, and no more chapters than the most")))
