(ns dk.simongray.podcast.format.media.aac-test
  "The reader of AAC files, on files built here byte by byte: the header of
  an ADTS frame, the duration of the whole file or estimated from its
  start, the codecs, and the ID3 tags before the frames."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.media.aac :as aac]
            [dk.simongray.podcast.test-support :refer [view]]
            [dk.simongray.podcast.test-support.media
             :refer [about= adts-frame audio-frame chap header-128k jpeg apic tag text]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]))

(deftest adts-frames
  (is (= {:mpeg 4 :profile 1 :sample-rate 44100 :channels 2 :samples 1024 :length 371 :crc? false :vbr? true}
         (aac/frame-header (view (adts-frame)) 0))
      "a buffer fullness of 0x7FF, which marks a variable bitrate")
  (is (= {:sample-rate 44100 :channels 2 :vbr? true}
         (select-keys (aac/read (view (apply concat (repeat 3 (adts-frame))))) [:sample-rate :channels :vbr?])))
  (is (nil? (aac/frame-header (view (concat header-128k [0 0 0])) 0)) "an MPEG audio frame, whose layer isn't 0")
  (testing "the duration of a file that the view holds whole, from the samples of every frame"
    (let [r (aac/read (view (apply concat (repeat 20 (adts-frame)))))]
      (is (= {:codecs "mp4a.40.2" :media-start 0 :duration (/ (* 20 1024) 44100.0)}
             (select-keys r [:codecs :media-start :duration])))
      (is (= [] (aac/report r)))))
  (testing "else estimated from its first frames and the size of the file"
    (let [b    (bytes/of (apply concat (repeat 1000 (adts-frame))))
          part (view/add (view/view (bytes/length b)) 0 (bytes/copy b 0 200000) (bytes/length b))
          r    (aac/read part)]
      (is (about= (/ (* 1000 1024) 44100.0) (:duration r)))
      (is (= [:aac/estimated-duration] (map :id (aac/report r))))))
  (is (= "mp4a.67" (:codecs (aac/read (view (apply concat (repeat 3 (adts-frame true))))))) "AAC-LC of MPEG-2")
  (is (= [:aac/no-audio] (map :id (aac/report (aac/read (view (repeat 3000 7))))))))

(deftest tags-before-the-frames
  (let [tagged (tag 3 (text 3 "TIT2" "T") (apic 3 "image/jpeg" 3 jpeg) (chap 3 "a" 0 5000 (text 3 "TIT2" "A")))
        r      (aac/read (view (concat tagged (apply concat (repeat 20 (adts-frame))))))]
    (is (= (count tagged) (:media-start r)))
    (is (= (/ (* 20 1024) 44100.0) (:duration r)))
    (is (= [{:start-time 0.0 :end-time 5.0 :title "A"}] (:chapters r)))
    (is (= "image/jpeg" (:type (:artwork r))))
    (is (= [] (aac/report r))))
  (testing "the length of a TLEN frame when the frames don't follow the tags"
    (let [r (aac/read (view (concat (tag 3 (text 3 "TLEN" "61000")) (audio-frame))))]
      (is (= 61.0 (:duration r)))
      (is (= [:aac/no-audio] (map :id (aac/report r)))))))
