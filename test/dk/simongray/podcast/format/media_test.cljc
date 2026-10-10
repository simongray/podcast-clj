(ns dk.simongray.podcast.format.media-test
  "The readers of media files, on files built here byte by byte: ID3v2 tags
  of each version with their chapters and pictures, MPEG audio frames with
  their Xing and VBRI headers, and MP4 boxes with a QuickTime chapter
  track, Nero chapters and artwork; each quirk with and without it. Also
  files built to hurt a reader, and on the JVM random bytes and mangled
  files, which a reader must answer with a report, never an exception."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.format.integrity :as integrity]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.format.media.mp3 :as mp3]
            [dk.simongray.podcast.format.media.mp4 :as mp4]
            [dk.simongray.podcast.test-support :refer [ascii be code fetch-from run view
                                                       #?(:clj with-file-server)]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.time :as time]
            #?(:clj [dk.simongray.wary-fetch :as http])
            [dk.simongray.wary-fetch.async :as async]))

(defn- syncsafe
  [x]
  (for [i [21 14 7 0]] (bit-and (quot x (long (Math/pow 2 i))) 0x7F)))

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
  (let [v (view/add (view/view 100) 10 (bytes/of [1 2 3 4 5 6 7 8]) 100)]
    (is (= 0x01020304 (view/u32 v 10)))
    (is (= 0x0102030405060708 (view/u64 v 10)))
    (is (= [[18 30]] (view/missing (try (view/need v 12 30) (catch #?(:clj Exception :cljs :default) e e)))))
    (is (= [[0 10] [18 20]] (view/missing (try (view/need-all v [[0 12] [15 20]]) (catch #?(:clj Exception :cljs :default) e e))))
        "every range it lacks, at once")
    (is (view/truncated? (try (view/need v 100 104) (catch #?(:clj Exception :cljs :default) e e))))
    (is (nil? (view/need (view/add v 90 (bytes/of (range 10)) 100) 95 104)) "the end cut at the end of the file")
    (is (= [[0 20]] (map (fn [[o b]] [o (bytes/length b)]) (:parts (view/add v 0 (bytes/of (range 20)) 100))))
        "a part in place of those it covers, as a server that ignores ranges sends the start again"))
  (testing "a slice across the parts of a view"
    (let [v       (-> (view/view 16) (view/add 0 (bytes/of (range 8)) 16) (view/add 8 (bytes/of (range 8 16)) 16))
          numbers (fn [b] (map #(bytes/at b %) (range (bytes/length b))))]
      (is (= (range 5 11) (numbers (view/slice v 5 11))))
      (is (view/truncated? (try (view/slice v 10 20) (catch #?(:clj Exception :cljs :default) e e)))
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

(defn- zeros
  "A fetch of a file of `total` zero bytes, as `view/read!` takes it,
  noting each range in `log`."
  [total log]
  (fn [start end]
    (swap! log conj [start end])
    (let [end (min end total)]
      {:bytes (bytes/of (repeat (- end start) 0)) :start start :total total})))

(deftest reading-by-rounds
  (let [spread (fn [v] (view/reading #(do (view/need-all v (for [i (range 40)] [(* i 100000) (+ (* i 100000) 10)]))
                                           {:read :spread})))
        near   (fn [v] (view/reading #(do (view/need-all v (for [i (range 100)] [(* i 1000) (+ (* i 1000) 10)]))
                                           {:read :near})))
        walk   (fn [v] (view/reading #(loop [p 0 k 0]
                                        (if (= k 10)
                                          {:read :walk}
                                          (do (view/need v p (+ p 4))
                                              (recur (+ p 65537) (inc k)))))))
        cases  [[spread 40 "40 ranges far apart, 16 requests a round"]
                [near 1 "100 ranges near each other, in one request"]
                [walk 4 "a walk through segments of 64 KB, which reads more ahead each round"]]
        run    (fn [[reader n message]]
                 (let [log (atom [])]
                   (async/then (view/read! (zeros 4000000 log) reader)
                               (fn [r]
                                 (is (:read r) message)
                                 (is (= n (count @log)) message)))))]
    #?(:clj  (run! run cases)
       :cljs (async done
               (-> (reduce (fn [p c] (.then p #(run c))) (js/Promise.resolve nil) cases)
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))

(defn- places
  "A reader that needs four bytes at each of the offsets `ps`, one after
  another."
  [ps]
  (fn [v]
    (view/reading #(do (doseq [p ps] (view/need v p (+ p 4)))
                       {:read ps}))))

(deftest the-limits-of-a-reading
  (let [reading (fn [fetch-of reader opts]
                  (let [log (atom [])]
                    (async/then (view/read! (fetch-of 4000000 log) reader opts)
                                (fn [r] [(or (:read r) (map :id (view/report r))) @log]))))
        whole   (fn [total log]
                  (fn [start end]
                    (swap! log conj [start end])
                    {:bytes (bytes/of (repeat (min end total) 0)) :start 0 :total total :whole? true}))
        ticking (fn []
                  (let [ms (atom 0)]
                    #(time/->inst (swap! ms + 25000))))]
    (run
     #(async/then (reading zeros (places [1000000 0]) {})
                  (fn [[read log]]
                    (is (= [[1000000 0] [[1000000 1065540] [0 65540]]] [read log])
                        "no further ahead after a jump back")))
     #(async/then (reading zeros (places [0 1000000 2000000]) {:max-rounds 2})
                  (fn [[report log]]
                    (is (= [[:file/incomplete] 2] [report (count log)]) "the rounds")))
     #(async/then (reading zeros (places [0 1000000 2000000]) {:max-seconds 60 :now-fn (ticking)})
                  (fn [[report log]]
                    (is (= [[:file/incomplete] [[0 65540]]] [report log])
                        "no request after the time of the reading, which the first answer used up")))
     #(async/then (reading whole (places [0 300000 500000]) {})
                  (fn [[read log]]
                    (is (= [[0 300000 500000] [[0 65540] [0 365540] [0 565540]]] [read log])
                        "a server that ignores ranges is asked once in a round, from the start")))
     #(async/then (reading whole (places [0 300000 500000]) {:max-bytes 400000})
                  (fn [[report log]]
                    (is (= [[:file/incomplete] [[0 65540] [0 334460]]] [report log])
                        "and each request costs all the bytes before its range"))))))

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
           (map #(dissoc % :img) chs))
        "in the order of the table of contents; what it leaves out comes last, out of it")
    (is (str/starts-with? (:img (first chs)) "data:image/jpeg;base64,"))
    (is (= [] (id3/report chs)))))

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
  (is (= [:mp3/no-audio] (map :id (mp3/report (mp3/read (view (repeat 3000 7))))))))

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

(defn- playing-trak
  "A track `id` of `handler` whose sample entry is `entry`, enabled unless
  `disabled?`, and `width` by `height` in its tkhd box."
  [id handler entry & {:keys [width height disabled?] :or {width 0 height 0}}]
  (box "trak"
       (box "tkhd" [0 0 0 (if disabled? 0 3)] (be 4 0) (be 4 0) (be 4 id) (repeat 60 0)
            (be 4 (* width 65536)) (be 4 (* height 65536)))
       (box "mdia"
            (box "mdhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 1000) (be 4 60000) [0 0 0 0])
            (box "hdlr" [0 0 0 0] (be 4 0) (ascii handler) (repeat 12 0) [0])
            (box "minf" (box "stbl" (box "stsd" [0 0 0 0] (be 4 1) entry))))))

(defn- video-entry
  "A visual sample entry of `type` with the configuration box `config`."
  [type config]
  (box type (repeat 78 0) config))

(def aac-entry
  (box "mp4a" (repeat 28 0)
       (box "esds" [0 0 0 0] [3 25 0 1 0] [4 17 0x40 0x15] (repeat 11 0) [5 2 0x12 0x10] [6 1 2])))

(defn- movie-file
  "An MP4 file with the `traks`, its movie before the media data, or after
  it when `last?`."
  [last? & traks]
  (let [moov (box "moov" (mvhd 1000 60000) (apply concat traks))
        mdat (box "mdat" (repeat 16 0))]
    (concat (box "ftyp" (ascii "isom") (be 4 0)) (if last? (concat mdat moov) (concat moov mdat)))))

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

;; the front

(deftest reading-media
  (is (= :mp3 (:format (media/read (view (concat (tag 3 (text 3 "TIT2" "T")) (xing-frame 10) (audio-frame)))))))
  (is (= :mp4 (:format (media/read (view (chapter-file ["A"] [1000] false))))))
  (is (= [:file/unknown-format] (map :id (media/report (media/read (view (ascii "<html><body>not audio</body></html>")))))))
  (testing "a view that lacks bytes gives the ranges it needs"
    (let [b    (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
          head (view/add (view/view (bytes/length b)) 0 (bytes/copy b 0 40) (bytes/length b))]
      (is (= [[45 61]] (:need (media/read head))) "the header of the box after the media data, which ends at 45")))
  (testing "the chapters of an MP3 file as a chapters document"
    (let [r (media/read (view (concat (tag 3 (ctoc 3 "t" ["a"]) (chap 3 "a" 0 5000 (text 3 "TIT2" "A")))
                                      (xing-frame 10) (audio-frame))))]
      (is (= {:version "1.2.0" :chapters [{:start-time 0.0 :end-time 5.0 :title "A"}]} (:chapters r)))))
  (is (every? :message (media/report (media/read (view (ascii "not audio at all"))))) "every diagnostic with its message"))

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
              (map :id (media/report (media/read! "https://example.org/a.mp3"
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
             (let [fetch (http/fetcher url {})
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
                    (integrity/verify! url {:type "sri" :value (str "sha256-nope sha384-" digest)})))
             (is (= {:valid? false :algorithm "sha384"}
                    (integrity/verify! url {:type "sri" :value "sha384-AAAA"})))))))))

;; files built to hurt a reader

(defn- id3-tag
  "An ID3v2.3 tag around the bytes of `frames`, and two audio frames."
  [frames]
  (let [n (count frames)]
    (-> (into (vec (ascii "ID3")) [3 0 0])
        (into (for [shift [21 14 7 0]] (bit-and (quot n (long (Math/pow 2 shift))) 0x7F)))
        (into frames)
        (into (audio-frame))
        (into (audio-frame)))))

(defn- frame-of
  "The bytes of an ID3v2.3 frame `id` with the bytes `body`."
  [id body]
  (-> (vec (ascii id)) (into (be 4 (count body))) (into [0 0]) (into body)))

(defn- nested-chaps
  "CHAP frames, each inside the one before, `depth` deep."
  [depth]
  (let [head (-> (vec (ascii "a")) (into [0]) (into (be 4 0)) (into (be 4 1000)) (into (be 4 0xFFFFFFFF)) (into (be 4 0xFFFFFFFF)))]
    (nth (iterate #(frame-of "CHAP" (into head %)) []) depth)))

(defn- ctoc-of
  "A CTOC frame `id` that lists `children`, top-level when `top?`."
  [id children top?]
  (frame-of "CTOC" (-> (vec (ascii id)) (into [0 (if top? 3 1) (count children)])
                       (into (mapcat #(concat (ascii %) [0])) children))))

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

(defn- chapter-track-file
  "An M4A file with a chapter track of `n` samples, whose sample table is
  the box `stbl`."
  [stbl n]
  (let [text (box "trak"
                  (box "tkhd" [0 0 0 7] (be 4 0) (be 4 0) (be 4 2) (repeat 60 0))
                  (box "mdia"
                       (box "mdhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 1000) (be 4 n) [0 0 0 0])
                       (box "hdlr" [0 0 0 0] (be 4 0) (ascii "text") (repeat 12 0) [0])
                       (box "minf" stbl)))]
    (concat (box "ftyp" (ascii "M4A ") (be 4 0))
            (box "moov" (mvhd 1000 n) (trak 1 1000 n "soun" (box "tref" (box "chap" (be 4 2)))) text))))

(deftest a-hurtful-chapter-track
  (let [n    50000
        stbl (box "stbl"
                  (box "stts" [0 0 0 0] (be 4 1) (be 4 n) (be 4 1))
                  (box "stsz" [0 0 0 0] (be 4 2) (be 4 n))
                  (box "stsc" [0 0 0 0] (be 4 n) (into [] (mapcat #(concat (be 4 (inc %)) (be 4 1) (be 4 1))) (range n)))
                  (box "stco" [0 0 0 0] (be 4 n) (into [] (mapcat (fn [_] (be 4 8))) (range n))))]
    (is (= mp4/max-samples (count (:chapters (mp4/read (view (chapter-track-file stbl n))))))
        "50000 chunks in as many runs, read in one pass, and no more chapters than the most")))

(deftest parts-that-do-not-read
  (let [reading (fn [xs] (let [r (media/read (view xs))]
                           [(:format r) (:duration r) (map (juxt :id :part) (media/report r))]))]
    (testing "a chapter track past the end of the file"
      (let [stbl (box "stbl"
                      (box "stts" [0 0 0 0] (be 4 1) (be 4 1) (be 4 1000))
                      (box "stsz" [0 0 0 0] (be 4 10) (be 4 1))
                      (box "stsc" [0 0 0 0] (be 4 1) (be 4 1) (be 4 1) (be 4 1))
                      (box "stco" [0 0 0 0] (be 4 1) (be 4 5000000)))]
        (is (= [:mp4 0.001 [[:file/truncated :chapters]]] (reading (chapter-track-file stbl 1)))
            "the format and the duration as they are, and the report names the part")))
    (testing "a Nero chapter at a time of all ones, more than the JVM counts to"
      (let [chpl (box "chpl" [1 0 0 0] (be 4 0) [1] (repeat 8 0xFF) [5] (ascii "Intro"))
            r    (media/read (view (concat (box "ftyp" (ascii "M4A ") (be 4 0))
                                           (box "moov" (mvhd 600 90000) (box "udta" chpl)))))]
        (is (= [:mp4 150.0 ["Intro"]] [(:format r) (:duration r) (map :title (:chapters (:chapters r)))]))))
    (testing "a tag that says it has an extended header, and has none"
      (is (= [:mp3 (/ (* 1000 1152) 44100.0) [[:id3/malformed nil]]]
             (reading (concat (ascii "ID3") [3 0 0x40 0 0 0 0] (xing-frame 1000) (audio-frame) (audio-frame))))))
    (testing "a Xing header that counts no frames knows no duration"
      (let [[format duration report] (reading (concat (xing-frame 0) (apply concat (repeat 20 (audio-frame)))))]
        (is (= [:mp3 [[:mp3/estimated-duration nil]]] [format report]))
        (is (about= (/ (* 21 1152) 44100.0) duration))))))

(defn- hollow
  "A fetch of a file of `total` bytes that holds the bytes `b` at its start
  and nothing that a reader can use after them, as `view/read!` takes it,
  noting each range in `log`."
  [b total log]
  (let [n (bytes/length b)]
    (fn [start end]
      (swap! log conj [start end])
      {:bytes (bytes/copy b (min start n) (min end n)) :start start :total total})))

(deftest sizes-that-lie
  (let [mb      (* 1024 1024)
        n       16
        samples (chapter-track-file
                 (box "stbl"
                      (box "stts" [0 0 0 0] (be 4 1) (be 4 n) (be 4 1000))
                      (box "stsz" [0 0 0 0] (be 4 (* 90 mb)) (be 4 n))
                      (box "stsc" [0 0 0 0] (be 4 1) (be 4 1) (be 4 1) (be 4 1))
                      (box "stco" [0 0 0 0] (be 4 n) (mapcat #(be 4 (* (inc %) 100 mb)) (range n))))
                 n)
        tag     (concat (ascii "ID3") [3 0 0] (syncsafe (* 200 mb)) (repeat 100 0))
        reading (fn [xs opts]
                  (let [log (atom [])]
                    (async/then (media/read! "https://example.org/a" (assoc opts :fetch (hollow (bytes/of xs) (* 3000 mb) log)))
                                (fn [r]
                                  {:report   (map :id (media/report r))
                                   :requests (count @log)
                                   :asked    (reduce + (map (fn [[start end]] (- end start)) @log))}))))]
    (run
     #(async/then (reading samples {})
                  (fn [{:keys [report asked]}]
                    (is (= [:file/incomplete] report))
                    (is (< asked (* 2 mb)) "of text samples that say they hold 90 MB each, the text alone")))
     #(async/then (reading tag {})
                  (fn [{:keys [report requests asked]}]
                    (is (= [:file/incomplete] report))
                    (is (= [1 true] [requests (< asked mb)]) "a tag of 200 MB is more than a reading fetches")))
     #(async/then (reading tag {:max-bytes (* 300 mb)})
                  (fn [{:keys [requests asked]}]
                    (is (= [2 true] [requests (< (* 200 mb) asked)]) "unless the app lets it")))
     #(async/then (reading tag {:max-bytes 10})
                  (fn [{:keys [report requests]}]
                    (is (= [[:file/incomplete] 0] [report requests]) "and with no room, no request"))))))

#?(:clj
   (def mangled
     "A file of each format that the readers know, and bytes changed in it."
     (gen/let [file    (gen/elements [(concat (tag 3 (ctoc 3 "toc" ["a"]) (chap 3 "a" 0 5000 (text 3 "TIT2" "A")))
                                              (xing-frame 10) (audio-frame))
                                      (concat (tag 4 (text 4 "TIT2" "B")) (audio-frame) (audio-frame))
                                      (chapter-file ["Intro" "Talk"] [60000 90500] true)
                                      (fragmented-file 120000 true true)])
               changes (gen/vector (gen/tuple gen/nat (gen/choose 0 255)) 1 8)
               cut     gen/nat]
       (let [xs (reduce (fn [v [i x]] (assoc v (mod i (count v)) x)) (vec file) changes)]
         (subvec xs 0 (max 0 (- (count xs) (mod cut 64))))))))

#?(:clj
   (defspec any-bytes-give-a-reading 300
     (prop/for-all [xs (gen/one-of [(gen/vector (gen/choose 0 255) 0 600) mangled])]
       (let [r (media/read (view xs))]
         (and (map? r) (or (:format r) (seq (media/report r))))))))

(deftest a-file-that-the-app-does-not-allow
  (run #(async/then (integrity/verify! "https://example.org/a.mp3" {:type "sri" :value "sha384-AAAA"}
                                       {:allow-pred (constantly false)})
                    (fn [{:keys [valid? error]}]
                      (is (nil? valid?))
                      (is (= [:file/unreachable "https://example.org/a.mp3"] ((juxt :id :url) error)))
                      (is (str/includes? (:reason error) "is not allowed")
                          "no request is sent for it, on either platform")))))

(deftest a-file-that-does-not-come
  (let [url     "https://example.org/a.mp3"
        down    (fn [_] (throw (ex-info "Connection refused" {})))
        missing (fn [request] {:status 404 :url (:url request) :headers {}})
        told    (juxt :id :url :status :reason)]
    (run
     #(async/then (media/read! url {:send down})
                  (fn [file]
                    (is (= [:error] (keys file)) "a result with the error alone, and nothing thrown")
                    (is (= [:file/unreachable url nil "Connection refused"] (told (:error file))))
                    (is (string? (:message (:error file))) "the diagnostic with its message")
                    (is (= [] (media/report file)))))
     #(async/then (media/read! url {:send missing})
                  (fn [file]
                    (is (= [:file/unreachable url 404 (str "The file answered 404: " url)] (told (:error file))))))
     #(async/then (media/read! url {:fetch (fn [_ _] (throw (ex-info "The disk is gone" {})))})
                  (fn [file]
                    (is (= [:file/unreachable url nil "The disk is gone"] (told (:error file)))
                        "the fetch of the app")))
     #(async/then (integrity/verify! url {:type "sri" :value "sha384-AAAA"} {:send down})
                  (fn [verdict]
                    (is (nil? (:valid? verdict)))
                    (is (= [:file/unreachable url nil "Connection refused"] (told (:error verdict)))))))))

(deftest a-file-that-the-app-sends-for
  (let [file   (bytes/of (ascii "hello"))
        sha256 "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ="
        sent   (atom [])
        send   (fn [request]
                 (swap! sent conj request)
                 {:status 200 :url (:url request) :headers {} :body file})
        verify (fn [value opts]
                 (integrity/verify! "https://example.org/a.mp3" {:type "sri" :value value} opts))]
    (run
     #(async/then (verify sha256 {:send send :allow-pred (constantly true) :headers {"Accept" "audio/*"}})
                  (fn [verdict]
                    (is (= {:valid? true :algorithm "sha256"} verdict))
                    (is (= [["https://example.org/a.mp3" :bytes 600 {"Accept" "audio/*"} true]]
                           (map (juxt :url :as :timeout-seconds :headers (comp some? :allow-pred)) @sent))
                        "one request for the whole file, through the sender of the app")))
     #(async/then (verify "sha256-AAAA" {:send send :timeout-seconds 5})
                  (fn [verdict]
                    (is (= {:valid? false :algorithm "sha256"} verdict))
                    (is (= 5 (:timeout-seconds (peek @sent))))))
     #(async/then (verify sha256 {:send (fn [request] {:status 404 :url (:url request) :headers {}})})
                  (fn [{:keys [valid? error]}]
                    (is (nil? valid?))
                    (is (= [:file/unreachable 404] ((juxt :id :status) error))))))))

(deftest subresource-integrity
  (is (= {:algorithm "sha384" :digests #{"abc="}} (integrity/sri "sha256-xyz= sha384-abc=")) "the strongest wins")
  (is (= {:algorithm "sha512" :digests #{"a" "b"}} (integrity/sri "sha512-a sha512-b?ct=audio sha1-c")))
  (is (nil? (integrity/sri "md5-abc sha1-def")) "no hash SRI knows")
  (let [unverified [nil :file/unverifiable :info "A PGP signature needs the key of the publisher."]
        told       (juxt :valid? (comp :id :error) (comp :level :error) (comp :reason :error))
        result     (integrity/verify! "https://example.org/a.mp3" {:type "pgp-signature" :value "…"})]
    #?(:clj  (is (= unverified (told result)))
       :cljs (async done
               (-> result
                   (.then #(is (= unverified (told %)) "a promise, as for any other integrity"))
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))
