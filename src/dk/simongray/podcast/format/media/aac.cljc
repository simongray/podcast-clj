(ns dk.simongray.podcast.format.media.aac
  "AAC files: the ADTS frames of ISO/IEC 14496-3, Annex 1.A, with the ID3v2
  tags that often come before them.

  The duration comes from the first of these that the file has:

  - the samples of every frame, when the view holds the whole file
  - the average length of the first frames and the size of the file
  - the TLEN frame of a tag

  The reading is strict: the audio starts right after the tags. The
  :except option leaves out the quirks of the tags that it names."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]))

;; ISO/IEC 14496-3, Annex 1.A: the sampling_frequency_index of ADTS, of
;; which 13 and 14 are reserved, and 15 is not allowed
(def ^:no-doc sample-rates
  "The sample rates in Hz by sampling frequency index."
  [96000 88200 64000 48000 44100 32000 24000 22050 16000 12000 11025 8000
   7350])

;; ISO/IEC 14496-3, Annex 1.A: adts_fixed_header and adts_variable_header,
;; 56 bits, with a CRC of 16 bits after them when protection_absent is 0
(defn frame-header
  "The header of the ADTS frame at `p` in `view`, or nil when no valid
  header is there.

  The header is a map of :mpeg, which is 2 or 4, :profile, :sample-rate,
  :channels, :samples, :length and :crc?. The length of the frame is in
  bytes, with the header."
  [view p]
  (view/need view p (+ p 7))
  (let [b1     (view/u8 view (inc p))
        b2     (view/u8 view (+ p 2))
        b3     (view/u8 view (+ p 3))
        rate-i (bit-and (bit-shift-right b2 2) 0x0F)
        crc?   (not (bit-test b1 0))
        length (+ (* 2048 (bit-and b3 3))
                  (* 8 (view/u8 view (+ p 4)))
                  (bit-shift-right (view/u8 view (+ p 5)) 5))]
    (when (and (= 0xFF (view/u8 view p))
               (= 0xF0 (bit-and b1 0xF6))
               (< rate-i 13)
               (>= length (if crc? 9 7)))
      {:mpeg        (if (bit-test b1 3) 2 4)
       :profile     (bit-shift-right b2 6)
       :sample-rate (sample-rates rate-i)
       :channels    (+ (* 4 (bit-and b2 1)) (bit-shift-right b3 6))
       :samples     (* 1024 (inc (bit-and (view/u8 view (+ p 6)) 3)))
       :length      length
       :crc?        crc?})))

(defn- frame-at?
  "The header of the frame at `p` in `view` when another frame follows it,
  as one follows a real frame, or when it ends the file. It's nil
  otherwise."
  [view p]
  (when-let [h (frame-header view p)]
    (let [next  (+ p (:length h))
          total (:total view)]
      (when (or (and total (>= (+ next 7) total))
                (frame-header view next))
        h))))

(def ^:no-doc estimate-window
  "How many bytes of frames from the first the estimate of the duration of
  a file that the view doesn't hold whole averages."
  (* 128 1024))

(defn- counted
  "The bytes and the samples of the frames in `view` from the first, at `p`
  with the header `h`, up to `end`, as a pair."
  [view p h end]
  (loop [q p h h size 0 samples 0]
    (let [size    (+ size (:length h))
          samples (+ samples (:samples h))
          next    (+ q (:length h))
          h'      (when (<= (+ next 7) end)
                    (frame-header view next))]
      (if h'
        (recur next h' size samples)
        [size samples]))))

(defn- duration
  "The duration in seconds of the audio in `view` whose first frame is at
  `p` with the header `h`, read in the context `ctx`, or nil when the size
  of the file isn't known. The report of `ctx` tells when the duration is
  an estimate."
  [ctx view p h]
  (when-let [total (:total view)]
    (let [end  (min (+ p estimate-window) total)
          rate (double (:sample-rate h))]
      (view/need view p end)
      (if (= total (view/held view))
        (/ (second (counted view p h total)) rate)
        (let [[size samples] (counted view p h end)]
          (view/note! ctx (view/diagnostic :aac/estimated-duration :info))
          (/ (* (- total p) samples) (double size) rate))))))

;; RFC 6381, section 3.3: mp4a.40 and the audio object type for MPEG-4
;; audio, and the object type indications 0x66 to 0x68 for MPEG-2 AAC
(defn- codecs
  "The RFC 6381 codecs string of the frames with the header `h`, or nil
  for a profile that MPEG-2 doesn't define."
  [{:keys [mpeg profile] :as h}]
  (if (= 4 mpeg)
    (str "mp4a.40." (inc profile))
    (get ["mp4a.66" "mp4a.67" "mp4a.68"] profile)))

(defn report
  "The diagnostics of the read `file`, with those of its tags. Each
  diagnostic has its message."
  [file]
  (view/report file))

(defn read
  "The AAC file in `view`, read with `opts`, as a map of the keys below,
  each absent when the file doesn't have it, as mp4/read gives them.

  - :tags are the ID3v2 tags
  - :codecs is the RFC 6381 codecs string, e.g. \"mp4a.40.2\"
  - :media-start is where the first frame starts
  - :duration is in seconds
  - :chapters, from the CHAP and CTOC frames, as id3/chapters gives them
  - :artwork, a map of :type, :data and :description

  The :except of `opts` is the ids of the quirks to leave out. When the
  view lacks bytes, the reading throws an exception that holds their
  ranges."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (let [front    (id3/read-front view opts)
         ctx      (view/context opts)
         p        (:end front)
         h        (frame-at? view p)
         seconds  (or (when h (duration ctx view p h)) (:duration front))
         codecs   (some-> h codecs)
         no-audio (view/diagnostic :aac/no-audio :warning)]
     (view/reported (cond-> (dissoc front :end :duration)
                      h       (assoc :media-start p)
                      codecs  (assoc :codecs codecs)
                      seconds (assoc :duration seconds))
                    (cond-> (into (view/report front) (view/noted ctx))
                      (nil? h) (conj no-audio))))))

(comment
  ;; an ADTS frame of AAC-LC at 44.1 kHz in stereo, 371 bytes long
  (def frame
    (concat [0xFF 0xF1 0x50 0x80 0x2E 0x7F 0xFC] (repeat 364 0)))

  (def sample
    (view/whole (bytes/of (apply concat (repeat 20 frame)))))

  (frame-header sample 0)
  (read sample)
  (map :id (report (read sample)))
  #_.)
