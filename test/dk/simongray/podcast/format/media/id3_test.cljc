(ns dk.simongray.podcast.format.media.id3-test
  "The reader of ID3v2 tags, on tags built here byte by byte: each version
  with its frames, chapters and pictures, each quirk with and without it,
  and tags built to hurt a reader."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.test-support :refer [ascii be view]]
            [dk.simongray.podcast.test-support.media
             :refer [apic chap ctoc ctoc-of frame id3-tag jpeg nested-chaps syncsafe tag text utf16le utf8]]
            [dk.simongray.podcast.util.bytes :as bytes]))

(deftest id3-tags
  (doseq [version [3 4]]
    (testing (str "ID3v2." version)
      (let [t (id3/read (view (tag version
                                   (text version "TIT2" "An episode")
                                   (frame version "TPE1" (concat [1 0xFF 0xFE] (utf16le "Host")))
                                   (apic version "image/png" 1 [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])
                                   (apic version "image/jpeg" 3 jpeg)
                                   (text version "TLEN" "3600500")
                                   [0 0 0 0 0 0 0 0]))
                        0)]
        (is (= version (:version t)))
        (is (= [] (id3/report t)))
        (is (= #{:version :start :end :frames} (set (keys t))) "the report is in the metadata")
        (is (= ["An episode" "Host"] (map :text (take 2 (:frames t)))) "ISO-8859-1, and UTF-16 by its byte order mark")
        (is (= "image/jpeg" (:type (id3/artwork (:frames t)))) "the front cover before the icon")
        (is (= 3600.5 (id3/duration (:frames t)))))))
  (testing "ID3v2.2, with three-letter ids and a picture format"
    (let [frame2 (fn [id body] (concat (ascii id) (be 3 (count body)) body))
          t      (id3/read (view (concat (ascii "ID3") [2 0 0]
                                         (syncsafe (+ 6 11 6 5 1 (count jpeg)))
                                         (frame2 "TT2" (concat [0] (ascii "Old tag   ")))
                                         (frame2 "PIC" (concat [0] (ascii "JPG") [3 0] jpeg))))
                           0)]
      (is (= "Old tag" (str/trim (:text (first (:frames t))))))
      (is (= "image/jpeg" (:type (id3/artwork (:frames t)))))))
  (testing "unsynchronisation undone, over the whole of a 2.3 tag"
    (let [body (text 3 "TIT2" "A")
          t    (id3/read (view (concat (ascii "ID3") [3 0 0x80] (syncsafe (+ (count body) 2)) body [0xFF 0x00])) 0)]
      (is (= "A" (:text (first (:frames t)))))))
  (is (nil? (id3/read (view (concat (ascii "RIFF") (repeat 10 0))) 0)) "no tag, no frames")
  (testing "a frame too short for its kind is left out, and the rest read"
    (let [t (id3/read (view (tag 3 (frame 3 "CHAP" [0x61 0 1]) (text 3 "TIT2" "Kept"))) 0)]
      (is (= ["Kept"] (map :text (:frames t))))
      (is (= [:id3/malformed] (map :id (id3/report t))))
      (is (every? :message (id3/report t)) "every diagnostic with its message"))))

(deftest id3-chapters
  (let [frames (:frames (id3/read (view (tag 3
                                             (ctoc 3 "toc" ["ch1" "ch0"])
                                             (chap 3 "ch0" 0 60000 (text 3 "TIT2" "Intro"))
                                             (chap 3 "ch1" 60000 150500
                                                   (text 3 "TIT2" "The interview")
                                                   (frame 3 "WXXX" (concat [0 0] (ascii "https://example.org")))
                                                   (apic 3 "image/jpeg" 0 jpeg))
                                             (chap 3 "ch2" 150500 160000 (text 3 "TIT2" "Hidden"))))
                                  0))
        chs    (id3/chapters frames)]
    (is (= [{:start-time 60.0 :end-time 150.5 :title "The interview" :url "https://example.org"}
            {:start-time 0.0 :end-time 60.0 :title "Intro"}
            {:start-time 150.5 :end-time 160.0 :title "Hidden" :toc false}]
           (map #(dissoc % :artwork) chs))
        "in the order of the table of contents; what it leaves out comes last, out of it")
    (is (= "image/jpeg" (:type (:artwork (first chs)))))
    (is (= (bytes/hex (bytes/of jpeg)) (bytes/hex (:data (:artwork (first chs))))))
    (is (= [] (id3/report chs)))
    (testing "the image of a chapter, which format.media gives as a data URL that a host writes as a file"
      (let [file                (media/read (view (concat (tag 3
                                                               (ctoc 3 "toc" ["ch0"])
                                                               (chap 3 "ch0" 0 60000 (apic 3 "image/jpeg" 0 jpeg)))
                                                          [0xFF 0xFB 0x90 0x00] (repeat 413 0))))
            {:keys [type data]} (bytes/from-data-url (:img (first (:chapters (:chapters file)))))]
        (is (= "image/jpeg" type))
        (is (= (bytes/hex (bytes/of jpeg)) (bytes/hex data)))))))

(deftest id3-quirks
  (testing "a 2.4 frame whose size is a plain number, as some writers put it"
    (let [big (concat (range 0 256) (range 0 256))
          xs  (tag 4 (frame 4 "PRIV" big :plain) (text 4 "TIT2" "After"))]
      (is (= "After" (:text (second (:frames (id3/read (view xs) 0))))))
      (is (= [:id3/frame-size] (map :id (id3/report (id3/read (view xs) 0)))))
      (is (= [:id3/padding-junk] (map :id (id3/report (id3/read (view xs) 0 {:except #{:id3/frame-size}}))))
          "without the quirk, the frames end where the size leads, which seems padding with junk in it")))
  (testing "text declared ISO-8859-1 whose bytes are UTF-8"
    (let [xs (tag 3 (frame 3 "TIT2" (concat [0] (utf8 "Ærø"))))]
      (is (= "Ærø" (:text (first (:frames (id3/read (view xs) 0))))))
      (is (= (bytes/decode (bytes/of (utf8 "Ærø")) 0 5 :latin1)
             (:text (first (:frames (id3/read (view xs) 0 {:except #{:id3/utf8-latin1}})))))
          "without the quirk, the bytes read as declared")))
  (testing "UTF-16 without its byte order mark"
    (let [xs (tag 3 (frame 3 "TIT2" (concat [1] (utf16le "Hi"))))]
      (is (= "Hi" (:text (first (:frames (id3/read (view xs) 0))))))
      (is (= [:id3/utf16-bom] (map :id (id3/report (id3/read (view xs) 0)))))
      (is (not= "Hi" (:text (first (:frames (id3/read (view xs) 0 {:except #{:id3/utf16-bom}})))))
          "big-endian, as Unicode says")))
  (testing "a text encoding that ID3 does not define"
    (let [xs (tag 3 (frame 3 "TIT2" (concat [9] (ascii "Hi"))) (text 3 "TALB" "Kept"))]
      (is (= ["Hi" "Kept"] (map :text (:frames (id3/read (view xs) 0)))) "read as ISO-8859-1")
      (is (= [[:id3/text-encoding 9]] (map (juxt :id :encoding) (id3/report (id3/read (view xs) 0)))))
      (let [strict (id3/read (view xs) 0 {:except #{:id3/text-encoding}})]
        (is (= ["Kept"] (map :text (:frames strict))) "without the quirk, the frame is left out")
        (is (= [:id3/malformed] (map :id (id3/report strict)))))))
  (testing "a picture type that is loose"
    (doseq [declared ["image/jpg" "JPG" ""]]
      (let [xs (tag 3 (apic 3 declared 3 jpeg))]
        (is (= "image/jpeg" (:type (id3/artwork (:frames (id3/read (view xs) 0))))) declared)
        (is (= [:id3/image-type] (map :id (id3/report (id3/read (view xs) 0))))))))
  (testing "a chapter that ends before it starts"
    (let [frames (:frames (id3/read (view (tag 3 (chap 3 "c" 216000 190041))) 0))]
      (is (= [{:start-time 216.0}] (id3/chapters frames)))
      (is (= [:id3/chapter-end] (map :id (id3/report (id3/chapters frames)))))
      (is (= [{:start-time 216.0 :end-time 190.041}] (id3/chapters frames {:except #{:id3/chapter-end}})))))
  (testing "a second tag right after the first"
    (let [xs (concat (tag 4 (text 4 "TIT2" "New")) (tag 3 (text 3 "TALB" "Old")))]
      (is (= [4 3] (map :version (id3/read-all (view xs) 0))))
      (is (= [:id3/second-tag] (mapcat #(map :id (id3/report %)) (id3/read-all (view xs) 0))))
      (is (= [4] (map :version (id3/read-all (view xs) 0 {:except #{:id3/second-tag}})))))))

(deftest hurtful-tags
  (testing "chapters inside chapters inside chapters, 2000 deep"
    (is (= :mp3 (:format (media/read (view (id3-tag (nested-chaps 2000))))))))
  (testing "a chain of 20000 tables of contents, each listing the next"
    (let [tables (into [] (mapcat #(ctoc-of (str "t" %) [(str "t" (inc %))] (zero? %))) (range 20000))]
      (is (= :mp3 (:format (media/read (view (id3-tag (into tables (nested-chaps 1))))))))))
  (testing "tables that list the same tables, 2^40 ways through them"
    (let [level  (fn [i] (concat (ctoc-of (str "a" i) [(str "b" i) (str "c" i)] (zero? i))
                                 (ctoc-of (str "b" i) [(str "a" (inc i))] false)
                                 (ctoc-of (str "c" i) [(str "a" (inc i))] false)))
          tables (into [] (mapcat level) (range 40))]
      (is (= :mp3 (:format (media/read (view (id3-tag tables)))))))))
