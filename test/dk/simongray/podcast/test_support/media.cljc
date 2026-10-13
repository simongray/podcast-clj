(ns dk.simongray.podcast.test-support.media
  "The media files that the tests build byte by byte: ID3v2 tags with their
  frames, MPEG audio frames with a Xing header, and MP4 files with their
  boxes, chapters, fragments and tracks. Each builder gives a sequence of
  numbers from 0 to 255, for test-support/view."
  (:require [dk.simongray.podcast.test-support :refer [ascii be code]]))

(defn syncsafe
  "The four syncsafe bytes of the number `x`, as ID3 writes a size."
  [x]
  (for [i [21 14 7 0]] (bit-and (quot x (long (Math/pow 2 i))) 0x7F)))

(defn utf16be
  "The UTF-16BE bytes of `s`, which holds only characters of the BMP."
  [s]
  (mapcat #(be 2 (code %)) s))

(defn utf16le
  "The UTF-16LE bytes of `s`, which holds only characters of the BMP."
  [s]
  (mapcat #(reverse (be 2 (code %))) s))

(defn utf8
  "The UTF-8 bytes of `s`."
  [s]
  #?(:clj  (map #(bit-and % 0xFF) (.getBytes ^String s "UTF-8"))
     :cljs (array-seq (.encode (js/TextEncoder.) s))))

(defn about=
  "Whether the numbers `a` and `b` agree to a millionth."
  [a b]
  (< (abs (- a b)) 1e-6))

(def jpeg
  "The start of a JPEG image."
  [0xFF 0xD8 0xFF 0xE0 0 16 74 70 73 70])

;; ID3

(defn frame
  "A frame of `version` 3 or 4 with the `id` and the `body` bytes, its size
  written as `size-form` (:syncsafe or :plain) or by the version."
  ([version id body]
   (frame version id body (if (= 4 version) :syncsafe :plain)))
  ([_ id body size-form]
   (concat (ascii id) (if (= :syncsafe size-form) (syncsafe (count body)) (be 4 (count body))) [0 0] body)))

(defn tag
  "An ID3v2 tag of `version` around the `frames`."
  [version & frames]
  (let [body (apply concat frames)]
    (concat (ascii "ID3") [version 0 0] (syncsafe (count body)) body)))

(defn text
  "A text frame of `version` with the `id` and the ISO-8859-1 text `s`."
  [version id s]
  (frame version id (concat [0] (ascii s))))

(defn chap
  "A CHAP frame of `version` with the element `id`, from `start` to `end` in
  milliseconds, around the `subframes`."
  [version id start end & subframes]
  (frame version "CHAP" (concat (ascii id) [0] (be 4 start) (be 4 end) (be 4 0xFFFFFFFF) (be 4 0xFFFFFFFF)
                                (apply concat subframes))))

(defn ctoc
  "A top-level CTOC frame of `version` with the element `id`, which lists
  the `children`."
  [version id children]
  (frame version "CTOC" (concat (ascii id) [0 3 (count children)] (mapcat #(concat (ascii %) [0]) children))))

(defn apic
  "An APIC frame of `version` with a picture of the `mime` type and the
  `picture-type`, whose bytes are `data`."
  [version mime picture-type data]
  (frame version "APIC" (concat [0] (ascii mime) [0 picture-type] (ascii "Cover") [0] data)))

(defn id3-tag
  "An ID3v2.3 tag around the bytes of `frames`, and two audio frames."
  [frames]
  (let [n (count frames)
        audio (concat [0xFF 0xFB 0x90 0x00] (repeat 413 0))]
    (-> (into (vec (ascii "ID3")) [3 0 0])
        (into (for [shift [21 14 7 0]] (bit-and (quot n (long (Math/pow 2 shift))) 0x7F)))
        (into frames)
        (into audio)
        (into audio))))

(defn frame-of
  "The bytes of an ID3v2.3 frame `id` with the bytes `body`."
  [id body]
  (-> (vec (ascii id)) (into (be 4 (count body))) (into [0 0]) (into body)))

(defn nested-chaps
  "CHAP frames, each inside the one before, `depth` deep."
  [depth]
  (let [head (-> (vec (ascii "a")) (into [0]) (into (be 4 0)) (into (be 4 1000)) (into (be 4 0xFFFFFFFF)) (into (be 4 0xFFFFFFFF)))]
    (nth (iterate #(frame-of "CHAP" (into head %)) []) depth)))

(defn ctoc-of
  "A CTOC frame `id` that lists `children`, top-level when `top?`."
  [id children top?]
  (frame-of "CTOC" (-> (vec (ascii id)) (into [0 (if top? 3 1) (count children)])
                       (into (mapcat #(concat (ascii %) [0])) children))))

;; MPEG audio

(def header-128k
  "The header of an MPEG-1 Layer III frame of 128 kbit/s at 44.1 kHz, which
  is 417 bytes long."
  [0xFF 0xFB 0x90 0x00])

(defn audio-frame
  "An MPEG-1 Layer III frame of 417 bytes, which begins its body with the
  `content`."
  ([]
   (audio-frame []))
  ([content]
   (concat header-128k content (repeat (- 413 (count content)) 0))))

(defn xing-frame
  "A first frame whose Xing header counts `n` frames."
  [n]
  (audio-frame (concat (repeat 32 0) (ascii "Xing") [0 0 0 1] (be 4 n))))

;; MP4

(defn box
  "An MP4 box of `type` around the `content`."
  [type & content]
  (let [c (apply concat content)]
    (concat (be 4 (+ 8 (count c))) (ascii type) c)))

(defn mvhd
  "A movie header of the `timescale` and the `duration`."
  [timescale duration]
  (box "mvhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 timescale) (be 4 duration) (repeat 80 0)))

(defn trak
  "A track `id` of `handler` without samples, of the `timescale` and the
  `duration`, with the `extra` boxes, such as tref."
  [id timescale duration handler & extra]
  (box "trak"
       (box "tkhd" [0 0 0 7] (be 4 0) (be 4 0) (be 4 id) (repeat 60 0))
       (apply concat extra)
       (box "mdia"
            (box "mdhd" [0 0 0 0] (be 4 0) (be 4 0) (be 4 timescale) (be 4 duration) [0 0 0 0])
            (box "hdlr" [0 0 0 0] (be 4 0) (ascii handler) (repeat 12 0) [0])
            (box "minf" (box "stbl")))))

(defn chapter-file
  "An M4A file with a QuickTime chapter track whose text samples `titles`,
  each of the milliseconds in `durations`, lie in the media data; the
  movie before the media data, or after it when `movie-last?`, with the
  `extra` boxes in the movie."
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

(defn fragmented-file
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

(defn playing-trak
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

(defn video-entry
  "A visual sample entry of `type` with the configuration box `config`."
  [type config]
  (box type (repeat 78 0) config))

(def aac-entry
  "An audio sample entry of AAC-LC, mp4a.40.2."
  (box "mp4a" (repeat 28 0)
       (box "esds" [0 0 0 0] [3 25 0 1 0] [4 17 0x40 0x15] (repeat 11 0) [5 2 0x12 0x10] [6 1 2])))

(defn movie-file
  "An MP4 file with the `traks`, its movie before the media data, or after
  it when `last?`."
  [last? & traks]
  (let [moov (box "moov" (mvhd 1000 60000) (apply concat traks))
        mdat (box "mdat" (repeat 16 0))]
    (concat (box "ftyp" (ascii "isom") (be 4 0)) (if last? (concat mdat moov) (concat moov mdat)))))

(defn chapter-track-file
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
