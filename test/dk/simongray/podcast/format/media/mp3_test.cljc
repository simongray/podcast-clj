(ns dk.simongray.podcast.format.media.mp3-test
  "The reader of MP3 files, on files built here byte by byte: the header of
  an MPEG audio frame, the duration from a Xing or VBRI header or from the
  frames and the size of the file, and bytes before the first frame."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.media.mp3 :as mp3]
            [dk.simongray.podcast.test-support :refer [ascii be view]]
            [dk.simongray.podcast.test-support.media
             :refer [about= audio-frame frame header-128k tag text xing-frame]]))

(deftest mpeg-frames
  (is (= {:version 1 :layer 3 :bitrate 128000 :sample-rate 44100 :samples 1152 :length 417 :mono? false :crc? false}
         (mp3/frame-header (view header-128k) 0)))
  (is (nil? (mp3/frame-header (view [0xFF 0xFB 0xF0 0x00]) 0)) "a bitrate index that is not one")
  (testing "the duration from a Xing header"
    (let [r (mp3/read (view (concat (tag 3 (text 3 "TIT2" "T")) (xing-frame 1000) (audio-frame) (audio-frame))))]
      (is (= (/ (* 1000 1152) 44100.0) (:duration r)))
      (is (= [] (mp3/report r)))))
  (testing "the duration from a VBRI header"
    (let [vbri (audio-frame (concat (repeat 32 0) (ascii "VBRI") [0 1 0 0 0 0] (be 4 0) (be 4 500)))]
      (is (= (/ (* 500 1152) 44100.0) (:duration (mp3/read (view (concat vbri (audio-frame)))))))))
  (testing "else estimated from the frames and the size of the file"
    (let [frames (apply concat (repeat 20 (audio-frame)))
          r      (mp3/read (view frames))]
      (is (about= (/ (* 20 1152) 44100.0) (:duration r)))
      (is (= [:mp3/estimated-duration] (map :id (mp3/report r))))))
  (testing "bytes between the tag and the first frame, such as padding the size of a tag leaves out"
    (let [xs (concat (tag 3 (text 3 "TIT2" "T")) (repeat 10 0) (xing-frame 1000) (audio-frame) (audio-frame))]
      (is (= [:mp3/junk-before-audio] (map :id (mp3/report (mp3/read (view xs))))))
      (is (about= (/ (* 1000 1152) 44100.0) (:duration (mp3/read (view xs)))))
      (is (= [:mp3/no-audio] (map :id (mp3/report (mp3/read (view xs) {:except #{:mp3/junk-before-audio}})))))))
  (is (= [:mp3/no-audio] (map :id (mp3/report (mp3/read (view (repeat 3000 7)))))))
  (testing "the sample rate and channels of the first frame, and whether its header says the bitrate varies"
    (let [info (audio-frame (concat (repeat 32 0) (ascii "Info") [0 0 0 1] (be 4 10)))
          read #(select-keys (mp3/read (view (concat % (audio-frame)))) [:sample-rate :channels :vbr?])]
      (is (= {:sample-rate 44100 :channels 2 :vbr? true} (read (xing-frame 10))))
      (is (= {:sample-rate 44100 :channels 2 :vbr? false} (read info)))
      (is (= {:sample-rate 44100 :channels 2} (read (audio-frame))) "or neither, without a header"))))

(deftest the-tags-as-one-map
  (let [comm (fn [description text]
               (frame 3 "COMM" (concat [0] (ascii "eng") (ascii description) [0] (ascii text))))
        r    (mp3/read (view (concat (tag 3 (text 3 "TIT2" "Episode 1") (text 3 "TPE1" "A Host") (text 3 "TYER" "2026")
                                          (comm "iTunNORM" " 0000") (comm "" "About the episode"))
                                     (audio-frame))))]
    (is (= {:title "Episode 1" :artist "A Host" :date "2026" :description "About the episode"} (:tags r))
        "but the comments of iTunes that have a description")
    (is (= 1 (count (:id3 r))) "and the tags themselves")))
