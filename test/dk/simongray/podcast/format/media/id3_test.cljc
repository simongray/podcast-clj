(ns dk.simongray.podcast.format.media.id3-test
  "What the ID3v2 tags at the start of a file say about it, as id3-clj
  reads them: the tags, the duration, the artwork and the chapters in
  seconds, and tags built to hurt a reader. The reading of the frames
  themselves is tested in id3-clj."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.test-support :refer [ascii be view]]
            [dk.simongray.podcast.test-support.media
             :refer [apic chap ctoc ctoc-of frame id3-tag jpeg nested-chaps syncsafe tag text]]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn- front
  "What the tags at the start of the bytes `xs` say, read with `opts`."
  ([xs]
   (front xs {}))
  ([xs opts]
   (id3/read-front (view xs) opts)))

(deftest what-the-tags-say
  (doseq [version [3 4]]
    (testing (str "ID3v2." version)
      (let [t (front (tag version
                          (text version "TIT2" "An episode")
                          (text version "TPE1" "Host")
                          (apic version "image/png" 1 [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])
                          (apic version "image/jpeg" 3 jpeg)
                          (text version "TLEN" "3600500")
                          [0 0 0 0 0 0 0 0]))]
        (is (= {:title "An episode" :artist "Host"} (:tags t)))
        (is (= 3600.5 (:duration t)) "TLEN in milliseconds")
        (is (= "image/jpeg" (:type (:artwork t))) "the front cover before the icon")
        (is (= [version] (map :version (:id3 t))))
        (is (= [] (view/report t))))))
  (testing "ID3v2.2, whose PIC names a format of three letters"
    (let [frame2 (fn [id body] (concat (ascii id) (be 3 (count body)) body))
          t      (front (concat (ascii "ID3") [2 0 0]
                                (syncsafe (+ 6 11 6 5 1 (count jpeg)))
                                (frame2 "TT2" (concat [0] (ascii "Old tag   ")))
                                (frame2 "PIC" (concat [0] (ascii "JPG") [3 0] jpeg))))]
      (is (= ["Old tag" "image/jpeg"] [(:title (:tags t)) (:type (:artwork t))]))))
  (testing "a picture that names its type loosely, by its bytes"
    (let [t (front (tag 3 (apic 3 "JPG" 3 jpeg)))]
      (is (= "image/jpeg" (:type (:artwork t))))
      (is (= [:id3/image-type] (map :id (view/report t))))
      (is (every? :message (view/report t)) "each diagnostic with its message")))
  (testing "a second tag right after the first"
    (let [xs (concat (tag 4 (text 4 "TIT2" "New")) (tag 3 (text 3 "TALB" "Old")))
          t  (front xs)]
      (is (= [4 3] (map :version (:id3 t))))
      (is (= {:title "New" :album "Old"} (:tags t)))
      (is (= (count xs) (:end t)) "the tags end after both")
      (is (= [:id3/second-tag] (map :id (view/report t))))
      (is (= [4] (map :version (:id3 (front xs {:except #{:id3/second-tag}})))))))
  (is (= {:id3 [] :tags {} :end 0} (front (concat (ascii "RIFF") (repeat 10 0)))) "no tag"))

(deftest the-chapters-of-the-tags
  (let [t   (front (tag 3
                        (ctoc 3 "toc" ["ch1" "ch0"])
                        (chap 3 "ch0" 0 60000 (text 3 "TIT2" "Intro"))
                        (chap 3 "ch1" 60000 150500
                              (text 3 "TIT2" "The interview")
                              (frame 3 "WXXX" (concat [0 0] (ascii " https://example.org ")))
                              (apic 3 "image/jpeg" 0 jpeg))
                        (chap 3 "ch2" 150500 160000 (text 3 "TIT2" "Hidden"))))
        chs (:chapters t)]
    (is (= [{:start-time 60.0 :end-time 150.5 :title "The interview" :url "https://example.org"}
            {:start-time 0.0 :end-time 60.0 :title "Intro"}
            {:start-time 150.5 :end-time 160.0 :title "Hidden" :toc false}]
           (map #(dissoc % :artwork) chs))
        "in seconds and the order of the table of contents; what it leaves out comes last, out of it")
    (is (= ["image/jpeg" (bytes/hex (bytes/of jpeg))]
           ((juxt :type (comp bytes/hex :data)) (:artwork (first chs))))))
  (testing "a chapter that ends before it starts"
    (let [t (front (tag 3 (chap 3 "c" 216000 190041)))]
      (is (= [{:start-time 216.0}] (:chapters t)))
      (is (= [:id3/chapter-end] (map :id (view/report t))))
      (is (= [{:start-time 216.0 :end-time 190.041}]
             (:chapters (front (tag 3 (chap 3 "c" 216000 190041)) {:except #{:id3/chapter-end}}))))))
  (testing "the image of a chapter, which format.media gives as a data URL that a host writes as a file"
    (let [file                (media/read (view (concat (tag 3
                                                             (ctoc 3 "toc" ["ch0"])
                                                             (chap 3 "ch0" 0 60000 (apic 3 "image/jpeg" 0 jpeg)))
                                                        [0xFF 0xFB 0x90 0x00] (repeat 413 0))))
          {:keys [type data]} (image/from-data-url (:img (first (:chapters (:chapters file)))))]
      (is (= "image/jpeg" type))
      (is (= (bytes/hex (bytes/of jpeg)) (bytes/hex data))))))

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
