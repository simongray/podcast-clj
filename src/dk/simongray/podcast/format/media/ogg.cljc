(ns dk.simongray.podcast.format.media.ogg
  "Ogg files of Opus or Vorbis audio: the pages of RFC 3533, the headers of
  RFC 7845 and of the Vorbis I specification, and the comments after them.

  The duration is the granule position of the last page of the audio,
  which lies in the last 64 KB of the file, less the granule position
  where the audio starts. For Opus, the start is exact. For Vorbis, whose
  packets give their lengths only through the setup header, it's exact for
  audio that starts at zero, and right to within one block otherwise, e.g.
  for a recording of a live stream.

  The chapters come from the CHAPTERxxx comments of Xiph's chapter
  extension, and the artwork from a METADATA_BLOCK_PICTURE comment.

  The reading is strict: a page whose CRC doesn't match is no page. The
  quirk :ogg/image-type takes the type of a picture from its bytes when
  the type that it declares is loose. The :except option leaves out the
  quirks that it names."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.format.media.ogg.impl :as impl]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn report
  "The diagnostics of the read `file`. Each diagnostic has its message."
  [file]
  (view/report file))

(defn read
  "The Ogg file in `view`, read with `opts`, as a map of the keys below,
  each absent when the file doesn't have it, as mp4/read gives them.

  - :comments are the comments of the stream, as pairs of a name in upper
    case and a value, and :tags what they say, as named gives it
  - :codecs is \"opus\" or \"vorbis\"
  - :sample-rate and :channels, where Opus plays at 48 kHz
  - :media-start is where the first page of audio starts
  - :duration is in seconds
  - :chapters, each with :start-time, and with :title and :url when the
    comments give them
  - :artwork, a map of :type, :data and :description

  The file is read as its first Opus or Vorbis stream. The :except of
  `opts` is the ids of the quirks to leave out. When the view lacks bytes,
  the reading throws an exception that holds their ranges.

  The duration, the chapters and the artwork are parts that the rest can
  do without. A part that doesn't read is left out, and the report names
  it."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (when-not (impl/page view 0)
     (throw (ex-info "No Ogg page at the start of the file" {})))
   (if-let [[codec bos] (some #(when-let [c (impl/codec view %)] [c %])
                              (impl/first-pages view))]
     (impl/read-stream (view/context opts) view codec bos)
     (view/reported {} [(view/diagnostic :ogg/unknown-codec :warning)]))))

(comment
  ;; an Ogg Opus file of the two headers and one page of 50 packets of
  ;; 20 ms, each the TOC byte of CELT at 20 ms and one more byte: a second
  ;; less the pre-skip of 312 samples
  (def sample
    (let [le     (fn [n x] (map #(bit-and (bit-shift-right x (* 8 %)) 0xFF)
                                (range n)))
          ascii  #(map int %)
          crc    (fn [xs] (#'impl/checksum (bytes/of xs)))
          page   (fn [flags granule seq-no packets]
                   (let [lacing (mapcat #(concat (repeat (quot (count %) 255)
                                                         255)
                                                 [(mod (count %) 255)])
                                        packets)
                         head   (concat (ascii "OggS") [0 flags]
                                        (le 4 granule) (le 4 0)
                                        (le 4 1) (le 4 seq-no) (le 4 0)
                                        [(count lacing)] lacing)
                         xs     (vec (concat head (apply concat packets)))
                         c      (crc xs)]
                     (concat (take 22 xs) (le 4 c) (drop 26 xs))))
          head   (concat (ascii "OpusHead") [1 2] (le 2 312) (le 4 48000)
                         [0 0 0])
          tags   (concat (ascii "OpusTags") (le 4 0) (le 4 1)
                         (le 4 23) (ascii "CHAPTER001=00:00:00.500"))]
      (view/whole (bytes/of (concat (page 2 0 0 [head])
                                    (page 0 0 1 [tags])
                                    (page 4 48000 2
                                          (repeat 50 [0xF8 0])))))))

  (read sample)
  (map :id (report (read sample)))
  #_.)
