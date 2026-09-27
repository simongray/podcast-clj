(ns dk.simongray.podcast.format.media-test
  "The readers of media files, on files built here byte by byte: ID3v2 tags
  of each version with their chapters and pictures, MPEG audio frames with
  their Xing and VBRI headers, and MP4 boxes with a QuickTime chapter
  track, Nero chapters and artwork; each quirk with and without it."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.format.id3 :as id3]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.mp3 :as mp3]
            [dk.simongray.podcast.format.mp4 :as mp4]
            #?(:clj [dk.simongray.podcast.test-support :refer [with-file-server]])))

(defn- be
  "The `n` bytes of the number `x`, big-endian."
  [n x]
  (for [i (range (dec n) -1 -1)]
    (mod (quot x (long (Math/pow 256 i))) 256)))

(defn- syncsafe
  [x]
  (for [i [21 14 7 0]] (bit-and (quot x (long (Math/pow 2 i))) 0x7F)))

(defn- code
  "The code of the character `c`, on both platforms."
  [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn- ascii
  [s]
  (map code s))

(defn- utf16be
  "The UTF-16BE bytes of `s`, which holds only characters of the BMP."
  [s]
  (mapcat #(be 2 (code %)) s))

(defn- utf16le
  [s]
  (mapcat #(reverse (be 2 (code %))) s))

(defn- about=
  "Whether the numbers `a` and `b` agree to a millionth."
  [a b]
  (< (abs (- a b)) 1e-6))

(defn- utf8
  [s]
  #?(:clj  (map #(bit-and % 0xFF) (.getBytes ^String s "UTF-8"))
     :cljs (array-seq (.encode (js/TextEncoder.) s))))

(defn- view
  "A view of all of the bytes `xs`."
  [xs]
  (let [b (bytes/of xs)]
    (bytes/add (bytes/view (bytes/length b)) 0 b (bytes/length b))))

(def jpeg
  "The start of a JPEG image."
  [0xFF 0xD8 0xFF 0xE0 0 16 74 70 73 70])

;; ID3

(defn- frame
  "A frame of `version` 3 or 4 with the `body` bytes, its size written as
  `size-form` (:syncsafe or :plain) or by the version."
  ([version id body]
   (frame version id body (if (= 4 version) :syncsafe :plain)))
  ([_ id body size-form]
   (concat (ascii id) (if (= :syncsafe size-form) (syncsafe (count body)) (be 4 (count body))) [0 0] body)))

(defn- tag
  [version & frames]
  (let [body (apply concat frames)]
    (concat (ascii "ID3") [version 0 0] (syncsafe (count body)) body)))

(defn- text
  [version id s]
  (frame version id (concat [0] (ascii s))))

(defn- chap
  [version id start end & subframes]
  (frame version "CHAP" (concat (ascii id) [0] (be 4 start) (be 4 end) (be 4 0xFFFFFFFF) (be 4 0xFFFFFFFF)
                                (apply concat subframes))))

(defn- ctoc
  [version id children]
  (frame version "CTOC" (concat (ascii id) [0 3 (count children)] (mapcat #(concat (ascii %) [0]) children))))

(defn- apic
  [version mime picture-type data]
  (frame version "APIC" (concat [0] (ascii mime) [0 picture-type] (ascii "Cover") [0] data)))

(deftest bytes-on-both-platforms
  (let [v (bytes/add (bytes/view 100) 10 (bytes/of [1 2 3 4 5 6 7 8]) 100)]
    (is (= 0x01020304 (bytes/u32 v 10)))
    (is (= 0x0102030405060708 (bytes/u64 v 10)))
    (is (= [[18 30]] (bytes/missing (try (bytes/need v 12 30) (catch #?(:clj Exception :cljs :default) e e)))))
    (is (= [[0 10] [18 20]] (bytes/missing (try (bytes/need-all v [[0 12] [15 20]]) (catch #?(:clj Exception :cljs :default) e e))))
        "every range it lacks, at once")
    (is (bytes/truncated? (try (bytes/need v 100 104) (catch #?(:clj Exception :cljs :default) e e))))
    (is (nil? (bytes/need (bytes/add v 90 (bytes/of (range 10)) 100) 95 104)) "the end cut at the end of the file"))
  (testing "a slice across the parts of a view"
    (let [v       (-> (bytes/view 16) (bytes/add 0 (bytes/of (range 8)) 16) (bytes/add 8 (bytes/of (range 8 16)) 16))
          numbers (fn [b] (map #(bytes/at b %) (range (bytes/length b))))]
      (is (= (range 5 11) (numbers (bytes/slice v 5 11))))
      (is (bytes/truncated? (try (bytes/slice v 10 20) (catch #?(:clj Exception :cljs :default) e e)))
          "past the end of the file")))
  (is (= "Ærø" (bytes/decode (bytes/of [0xC6 0x72 0xF8]) 0 3 :latin1)))
  (is (= "Ærø" (bytes/decode (bytes/of (utf8 "Ærø")) 0 5 :utf8)))
  (is (= "Ærø" (bytes/decode (bytes/of (utf16be "Ærø")) 0 6 :utf16be)))
  (is (bytes/utf8? (bytes/of (utf8 "Ærø")) 0 5))
  (is (not (bytes/utf8? (bytes/of [0xC6 0x72 0xF8]) 0 3)))
  (is (not (bytes/utf8? (bytes/of (ascii "plain")) 0 5)) "ASCII says nothing about the encoding")
  (is (= "AQID" (bytes/base64 (bytes/of [1 2 3]))))
  (is (= "image/jpeg" (bytes/image-type (bytes/of jpeg))))
  (is (nil? (bytes/image-type (bytes/of [1 2 3 4])))))

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
        (is (= [] (:report t)))
        (is (= ["An episode" "Host"] (map :text (take 2 (:frames t)))) "ISO-8859-1, and UTF-16 by its byte order mark")
        (is (= "image/jpeg" (:type (id3/artwork (:frames t)))) "the front cover before the icon")
        (is (= 3600.5 (id3/length (:frames t)))))))
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
      (is (= [:id3/malformed] (map :id (:report t)))))))

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
           (map #(dissoc % :img) chs))
        "in the order of the table of contents; what it leaves out comes last, out of it")
    (is (str/starts-with? (:img (first chs)) "data:image/jpeg;base64,"))
    (is (= [] (id3/chapters-report chs)))))

(deftest id3-quirks
  (testing "a 2.4 frame whose size is a plain number, as some writers put it"
    (let [big (concat (range 0 256) (range 0 256))
          xs  (tag 4 (frame 4 "PRIV" big :plain) (text 4 "TIT2" "After"))]
      (is (= "After" (:text (second (:frames (id3/read (view xs) 0))))))
      (is (= [:id3/frame-size] (map :id (:report (id3/read (view xs) 0)))))
      (is (= [:id3/padding-junk] (map :id (:report (id3/read (view xs) 0 {:except #{:id3/frame-size}}))))
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
      (is (= [:id3/utf16-bom] (map :id (:report (id3/read (view xs) 0)))))
      (is (not= "Hi" (:text (first (:frames (id3/read (view xs) 0 {:except #{:id3/utf16-bom}})))))
          "big-endian, as Unicode says")))
  (testing "a picture type that is loose"
    (doseq [declared ["image/jpg" "JPG" ""]]
      (let [xs (tag 3 (apic 3 declared 3 jpeg))]
        (is (= "image/jpeg" (:type (id3/artwork (:frames (id3/read (view xs) 0))))) declared)
        (is (= [:id3/image-type] (map :id (:report (id3/read (view xs) 0))))))))
  (testing "a chapter that ends before it starts"
    (let [frames (:frames (id3/read (view (tag 3 (chap 3 "c" 216000 190041))) 0))]
      (is (= [{:start-time 216.0}] (id3/chapters frames)))
      (is (= [:id3/chapter-end] (map :id (id3/chapters-report (id3/chapters frames)))))
      (is (= [{:start-time 216.0 :end-time 190.041}] (id3/chapters frames {:except #{:id3/chapter-end}})))))
  (testing "a second tag right after the first"
    (let [xs (concat (tag 4 (text 4 "TIT2" "New")) (tag 3 (text 3 "TALB" "Old")))]
      (is (= [4 3] (map :version (id3/read-all (view xs) 0))))
      (is (= [:id3/second-tag] (mapcat #(map :id (:report %)) (id3/read-all (view xs) 0))))
      (is (= [4] (map :version (id3/read-all (view xs) 0 {:except #{:id3/second-tag}})))))))

;; MPEG audio

(def header-128k
  "The header of an MPEG-1 Layer III frame of 128 kbit/s at 44.1 kHz, which
  is 417 bytes long."
  [0xFF 0xFB 0x90 0x00])

(defn- audio-frame
  ([]
   (audio-frame []))
  ([content]
   (concat header-128k content (repeat (- 413 (count content)) 0))))

(defn- xing-frame
  "A first frame whose Xing header counts `n` frames."
  [n]
  (audio-frame (concat (repeat 32 0) (ascii "Xing") [0 0 0 1] (be 4 n))))

(deftest mpeg-frames
  (is (= {:version 1 :layer 3 :bitrate 128000 :sample-rate 44100 :samples 1152 :length 417 :mono? false :crc? false}
         (mp3/frame-header (view header-128k) 0)))
  (is (nil? (mp3/frame-header (view [0xFF 0xFB 0xF0 0x00]) 0)) "a bitrate index that is not one")
  (testing "the duration from a Xing header"
    (let [r (mp3/read (view (concat (tag 3 (text 3 "TIT2" "T")) (xing-frame 1000) (audio-frame) (audio-frame))))]
      (is (= (/ (* 1000 1152) 44100.0) (:duration r)))
      (is (= [] (:report r)))))
  (testing "the duration from a VBRI header"
    (let [vbri (audio-frame (concat (repeat 32 0) (ascii "VBRI") [0 1 0 0 0 0] (be 4 0) (be 4 500)))]
      (is (= (/ (* 500 1152) 44100.0) (:duration (mp3/read (view (concat vbri (audio-frame)))))))))
  (testing "else estimated from the frames and the size of the file"
    (let [frames (apply concat (repeat 20 (audio-frame)))
          r      (mp3/read (view frames))]
      (is (about= (/ (* 20 1152) 44100.0) (:duration r)))
      (is (= [:mp3/estimated-duration] (map :id (:report r))))))
  (testing "bytes between the tag and the first frame, such as padding the size of a tag leaves out"
    (let [xs (concat (tag 3 (text 3 "TIT2" "T")) (repeat 10 0) (xing-frame 1000) (audio-frame) (audio-frame))]
      (is (= [:mp3/junk-before-audio] (map :id (:report (mp3/read (view xs))))))
      (is (about= (/ (* 1000 1152) 44100.0) (:duration (mp3/read (view xs)))))
      (is (= [:mp3/no-audio] (map :id (:report (mp3/read (view xs) {:except #{:mp3/junk-before-audio}})))))))
  (is (= [:mp3/no-audio] (map :id (:report (mp3/read (view (repeat 3000 7))))))))

;; MP4

(defn- box
  [type & content]
  (let [c (apply concat content)]
    (concat (be 4 (+ 8 (count c))) (ascii type) c)))

(defn- mvhd
  [timescale duration]
  (box "mvhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 timescale) (be 4 duration) (repeat 80 0)))

(defn- trak
  "A track without samples, with the `extra` boxes, such as tref."
  [id timescale duration handler & extra]
  (box "trak"
       (box "tkhd" [0 0 0 7] (be 4 0) (be 4 0) (be 4 id) (repeat 60 0))
       (apply concat extra)
       (box "mdia"
            (box "mdhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 timescale) (be 4 duration) [0 0 0 0])
            (box "hdlr" [0 0 0 0] (be 4 0) (ascii handler) (repeat 12 0) [0])
            (box "minf" (box "stbl")))))

(defn- chapter-file
  "An M4A file with a QuickTime chapter track whose text samples `titles`,
  each of the milliseconds in `durations`, lie in the media data; the
  movie before the media data, or after it when `movie-last?`."
  [titles durations movie-last? & extra]
  (let [samples  (map #(let [t (utf8 %)] (concat (be 2 (count t)) t)) titles)
        movie    (fn [offset]
                   (box "moov"
                        (mvhd 1000 (reduce + durations))
                        (trak 1 44100 (* 44100 (quot (reduce + durations) 1000)) "soun" (box "tref" (box "chap" (be 4 2))))
                        (box "trak"
                             (box "tkhd" [0 0 0 7] (be 4 0) (be 4 0) (be 4 2) (repeat 60 0))
                             (box "mdia"
                                  (box "mdhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 1000) (be 4 (reduce + durations)) [0 0 0 0])
                                  (box "hdlr" [0 0 0 0] (be 4 0) (ascii "text") (repeat 12 0) [0])
                                  (box "minf"
                                       (box "stbl"
                                            (box "stts" [0 0 0 0] (be 4 (count durations))
                                                 (mapcat #(concat (be 4 1) (be 4 %)) durations))
                                            (box "stsz" [0 0 0 0] (be 4 0) (be 4 (count samples))
                                                 (mapcat #(be 4 (count %)) samples))
                                            (box "stsc" [0 0 0 0] (be 4 1) (be 4 1) (be 4 (count samples)) (be 4 1))
                                            (box "stco" [0 0 0 0] (be 4 1) (be 4 offset))))))
                        (apply concat extra)))
        ftyp     (box "ftyp" (ascii "M4A ") (be 4 0) (ascii "M4A isom"))
        mdat     (box "mdat" (apply concat samples))
        moov-len (count (movie 0))]
    (if movie-last?
      (concat ftyp mdat (movie (+ (count ftyp) 8)))
      (concat ftyp (movie (+ (count ftyp) moov-len 8)) mdat))))

(defn- fragmented-file
  "An M4A file written in three fragments of ten samples of 100 ms each,
  with the duration of all of them in an mehd box when `mehd` gives it, and
  an index of them at the end when `index?`; each sample has its own
  duration when `per-sample?`, and the default of its fragment otherwise,
  which differs from the default of the movie."
  [mehd index? per-sample?]
  (let [head      (concat (box "ftyp" (ascii "M4A ") (be 4 0))
                          (box "moov"
                               (mvhd 1000 0)
                               (trak 1 1000 0 "soun")
                               (box "mvex"
                                    (when mehd (box "mehd" [0 0 0 0] (be 4 mehd)))
                                    (box "trex" [0 0 0 0] (be 4 1) (be 4 1) (be 4 7) (be 4 0) (be 4 0)))))
        fragment  (fn [i]
                    (concat (box "moof"
                                 (box "mfhd" [0 0 0 0] (be 4 (inc i)))
                                 (box "traf"
                                      (if per-sample?
                                        (box "tfhd" [0 0 0 0] (be 4 1))
                                        (box "tfhd" [0 0 0 0x09] (be 4 1) (be 8 0) (be 4 100)))
                                      (box "tfdt" [1 0 0 0] (be 8 (* i 1000)))
                                      (if per-sample?
                                        (box "trun" [0 0 0x03 0x01] (be 4 10) (be 4 0)
                                             (mapcat (fn [_] (concat (be 4 100) (be 4 4))) (range 10)))
                                        (box "trun" [0 0 0 0] (be 4 10)))))
                            (box "mdat" (repeat 40 0))))
        fragments (map fragment (range 3))
        offsets   (reductions + (count head) (map count fragments))
        tfra      (box "tfra" [1 0 0 0] (be 4 1) (be 4 0) (be 4 3)
                       (mapcat (fn [i offset] (concat (be 8 (* i 1000)) (be 8 offset) [1 1 1])) (range 3) offsets))]
    (concat head
            (apply concat fragments)
            (when index?
              (box "mfra" tfra (box "mfro" [0 0 0 0] (be 4 (+ 8 (count tfra) 16))))))))

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
      (is (= [:mp4/zero-duration] (map :id (:report (mp4/read (view xs))))))
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
        (is (= [:mp4/fragments-unread] (map :id (:report r))))))))

;; the front

(deftest reading-media
  (is (= :mp3 (:format (media/read (view (concat (tag 3 (text 3 "TIT2" "T")) (xing-frame 10) (audio-frame)))))))
  (is (= :mp4 (:format (media/read (view (chapter-file ["A"] [1000] false))))))
  (is (= [:file/unknown-format] (map :id (:report (media/read (view (ascii "<html><body>not audio</body></html>")))))))
  (testing "a view that lacks bytes gives the ranges it needs"
    (let [b    (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
          head (bytes/add (bytes/view (bytes/length b)) 0 (bytes/copy b 0 40) (bytes/length b))]
      (is (= [[45 61]] (:need (media/read head))) "the header of the box after the media data, which ends at 45")))
  (testing "the chapters of an MP3 file as a chapters document"
    (let [r (media/read (view (concat (tag 3 (ctoc 3 "t" ["a"]) (chap 3 "a" 0 5000 (text 3 "TIT2" "A")))
                                      (xing-frame 10) (audio-frame))))]
      (is (= {:version "1.2.0" :chapters [{:start-time 0.0 :end-time 5.0 :title "A"}]} (:chapters r)))))
  (is (every? :message (:report (media/read (view (ascii "not audio at all"))))) "every diagnostic with its message"))

(defn- fetch-from
  "A fetch over the bytes `b`, as `read!` takes it, noting each range in `log`."
  [b log]
  (fn [start end]
    (swap! log conj [start end])
    (let [end (min end (bytes/length b))]
      {:bytes (bytes/copy b start end) :start start :total (bytes/length b)})))

#?(:clj
   (deftest reading-media-in-rounds
     (let [b   (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
           log (atom [])
           r   (media/read! "https://example.org/a.m4a" {:fetch (fetch-from b log)})]
       (is (= 150.5 (:duration r)))
       (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))
       (is (<= (count @log) 3) (pr-str @log)))
     (let [log (atom [])]
       (is (= [:file/incomplete]
              (map :id (:report (media/read! "https://example.org/a.mp3"
                                             {:fetch (fn [start end] (swap! log conj [start end]) {:bytes (bytes/of []) :start start})}))))
           "a range that never comes"))))

#?(:cljs
   (deftest reading-media-in-rounds
     (async done
       (let [b   (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
             log (atom [])]
         (-> (media/read! "https://example.org/a.m4a" {:fetch (fetch-from b log)})
             (.then (fn [r]
                      (is (= 150.5 (:duration r)))
                      (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally done))))))

#?(:cljs
   (deftest fetching-with-fetch
     (async done
       (let [real        (.-fetch js/globalThis)
             pulls       (atom 0)
             whole       (js/ReadableStream. #js {:pull (fn [controller]
                                                          (if (< (swap! pulls inc) 100)
                                                            (.enqueue controller (js/Uint8Array. 65536))
                                                            (.close controller)))})
             answers     (atom [(js/Response. (js/Uint8Array. 10)
                                              #js {:status 206 :headers #js {"content-range" "bytes 5-14/1000"}})
                                (js/Response. whole
                                              #js {:status 200 :headers #js {"content-length" (str (* 99 65536))}})])
             fetch-bytes (media/fetcher "https://example.org/a.mp3" {})]
         ;; the global fetch, as the fetcher finds it
         (set! (.-fetch js/globalThis) (fn [_ _]
                                         (let [res (first @answers)]
                                           (swap! answers rest)
                                           (js/Promise.resolve res))))
         (-> (fetch-bytes 5 15)
             (.then (fn [{b :bytes :keys [start total]}]
                      (is (= [10 5 1000] [(bytes/length b) start total]) "a range")
                      (fetch-bytes 0 100000)))
             (.then (fn [{b :bytes :keys [start total]}]
                      (is (= [100000 0 (* 99 65536)] [(bytes/length b) start total]) "the start of the whole file")
                      (is (< @pulls 10) "the rest of the file left unread")))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) real)
                         (done))))))))

#?(:clj
   (deftest media-over-http
     (let [b   (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
           log (atom [])]
       (with-file-server b {:log log}
         (fn [url]
           (let [r (media/read! url)]
             (is (= 150.5 (:duration r)))
             (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))
             (is (every? some? @log) "every request for a range")))))
     (testing "a server that ignores ranges, of whose file the start is read"
       (let [b     (bytes/of (concat (tag 3 (text 3 "TIT2" "T")) (apply concat (repeat 2500 (audio-frame)))))
             sizes (atom [])]
         (with-file-server b {:ranges? false}
           (fn [url]
             (let [fetch (media/fetcher url {})
                   r     (media/read! url {:fetch (fn [start end]
                                                    (let [part (fetch start end)]
                                                      (swap! sizes conj (bytes/length (:bytes part)))
                                                      part))})]
               (is (< (abs (- (/ (* 2500 1152) 44100.0) (:duration r))) 0.01) "estimated from the size of the whole")
               (is (every? #(< % (quot (bytes/length b) 4)) @sizes) (pr-str @sizes)))))))
     (testing "the integrity of a file, as SRI gives it"
       (let [b      (bytes/of (range 256))
             digest (bytes/base64 (.digest (java.security.MessageDigest/getInstance "SHA-384") ^bytes b))]
         (with-file-server b {}
           (fn [url]
             (is (= {:valid? true :algorithm "sha384"}
                    (media/verify! url {:type "sri" :value (str "sha256-nope sha384-" digest)})))
             (is (= {:valid? false :algorithm "sha384"}
                    (media/verify! url {:type "sri" :value "sha384-AAAA"})))))))))

(deftest subresource-integrity
  (is (= {:algorithm "sha384" :digests #{"abc="}} (media/sri "sha256-xyz= sha384-abc=")) "the strongest wins")
  (is (= {:algorithm "sha512" :digests #{"a" "b"}} (media/sri "sha512-a sha512-b?ct=audio sha1-c")))
  (is (nil? (media/sri "md5-abc sha1-def")) "no hash SRI knows")
  (let [unverified {:valid? nil :reason "A PGP signature needs the publisher's key."}
        result     (media/verify! "https://example.org/a.mp3" {:type "pgp-signature" :value "…"})]
    #?(:clj  (is (= unverified result))
       :cljs (async done
               (-> result
                   (.then #(is (= unverified %) "a promise, as for any other integrity"))
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))
