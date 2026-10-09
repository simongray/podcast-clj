(ns dk.simongray.podcast.format.media.mp3
  "MP3 files: the ID3v2 tags at the start, and the MPEG audio frames after
  them.

  ISO/IEC 11172-3 and 13818-3 define the header of a frame, and the reader
  takes MPEG 2.5 too, which is in neither.

  The duration comes from the first of these that the file has:

  - the Xing or Info header, which LAME and most encoders write in the
    first frame
  - the VBRI header of the Fraunhofer encoder
  - the average bitrate of the first frames and the size of the file,
    which is exact for a constant bitrate

  The reading is strict: the audio starts right after the tags. The quirk
  :mp3/junk-before-audio skips bytes that are no frame, such as padding
  that the size of a tag leaves out. The :except option leaves out the
  quirks that it names, those of the tags too."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]))

(def ^:no-doc bitrates
  "The bitrates in kbit/s by MPEG version and layer, by bitrate index."
  {[1 1] [nil 32 64 96 128 160 192 224 256 288 320 352 384 416 448]
   [1 2] [nil 32 48 56 64 80 96 112 128 160 192 224 256 320 384]
   [1 3] [nil 32 40 48 56 64 80 96 112 128 160 192 224 256 320]
   [2 1] [nil 32 48 56 64 80 96 112 128 144 160 176 192 224 256]
   [2 2] [nil 8 16 24 32 40 48 56 64 80 96 112 128 144 160]
   [2 3] [nil 8 16 24 32 40 48 56 64 80 96 112 128 144 160]})

(def ^:no-doc sample-rates
  "The sample rates in Hz by MPEG version, by sample rate index."
  {1   [44100 48000 32000]
   2   [22050 24000 16000]
   2.5 [11025 12000 8000]})

(defn frame-header
  "The header of the MPEG audio frame at `p` in `view`, or nil when no
  valid header is there.

  The header is a map of :version, :layer, :bitrate, :sample-rate,
  :samples, :length, :mono? and :crc?. The bitrate is in bit/s, and the
  length of the frame is in bytes."
  [view p]
  (view/need view p (+ p 4))
  (let [b1 (view/u8 view (inc p))
        b2 (view/u8 view (+ p 2))
        b3 (view/u8 view (+ p 3))
        version (case (bit-and (bit-shift-right b1 3) 3) 0 2.5 2 2 3 1 nil)
        layer   (case (bit-and (bit-shift-right b1 1) 3) 1 3 2 2 3 1 nil)
        rate-i  (bit-and (bit-shift-right b2 2) 3)
        table   [(if (= 1 version) 1 2) layer]
        kbps    (when (and version layer)
                  (get (bitrates table) (bit-shift-right b2 4)))]
    (when (and (= 0xFF (view/u8 view p))
               (= 0xE0 (bit-and b1 0xE0))
               kbps
               (< rate-i 3))
      (let [rate    (get-in sample-rates [version rate-i])
            samples (case layer 1 384 2 1152 3 (if (= 1 version) 1152 576))
            padding (if (bit-test b2 1) 1 0)
            length  (if (= 1 layer)
                      (* 4 (+ (quot (* 12 kbps 1000) rate) padding))
                      (+ (quot (* (quot samples 8) kbps 1000) rate) padding))]
        {:version     version
         :layer       layer
         :bitrate     (* kbps 1000)
         :sample-rate rate
         :samples     samples
         :length      length
         :mono?       (= 3 (bit-shift-right b3 6))
         :crc?        (not (bit-test b1 0))}))))

(defn- frame-at?
  "The header of the frame at `p` in `view` when another frame follows it,
  as one follows a real frame, or when it ends the file. It's nil
  otherwise."
  [view p]
  (when-let [h (frame-header view p)]
    (let [next (+ p (:length h))
          total (:total view)]
      (when (or (and total (>= (+ next 4) total))
                (frame-header view next))
        h))))

(def ^:no-doc search-limit
  "How far past the tags the quirk looks for the first frame."
  (* 256 1024))

(defn- first-frame
  "The place and the header of the first frame at or after `start` in
  `view`, read in the context `ctx`, or nil when there's none. The frame
  is at `start` itself, or further on by the quirk."
  [ctx view start]
  (view/need view start (+ start 4096))
  (if-let [h (frame-at? view start)]
    [start h]
    (when-not (contains? (:except ctx) :mp3/junk-before-audio)
      (let [end (min (+ start search-limit) (or (:total view) ##Inf))]
        (view/need view start (+ end 4096))
        (loop [p (inc start)]
          (when (< (+ p 4) end)
            (if-let [h (and (= 0xFF (view/u8 view p))
                            (frame-at? view p))]
              (let [junk {:id      :mp3/junk-before-audio
                          :level   :info
                          :skipped (- p start)}]
                (vswap! (:report ctx) conj junk)
                [p h])
              (recur (inc p)))))))))

(defn- side-info
  "The size of the side information after the header `h` and its CRC, where
  the Xing and Info headers begin."
  [{:keys [version mono? crc?] :as h}]
  (+ (if (= 1 version) (if mono? 17 32) (if mono? 9 17)) (if crc? 2 0)))

(defn- frame-count
  "The number of frames that the Xing, Info or VBRI header gives in the
  first frame, at `p` in `view` with the header `h`, or nil when it has
  none."
  [view p h]
  (let [x (+ p 4 (side-info h))
        v (+ p 36)]
    (view/need view p (+ v 18))
    (cond
      (and (#{"Xing" "Info"} (view/code view x 4))
           (bit-test (view/u32 view (+ x 4)) 0))
      (view/u32 view (+ x 8))

      (= "VBRI" (view/code view v 4))
      (view/u32 view (+ v 14)))))

(def ^:no-doc estimate-window
  "How many bytes of frames from the first the estimate of the bitrate of a
  file without a Xing, Info or VBRI header averages."
  (* 128 1024))

(defn- average-bitrate
  "The average bitrate in bit/s of the frames in `view` from the first, at
  `p` with the header `h`, within estimate-window bytes. It's exact for a
  constant bitrate, and closer than the first frame for a variable one."
  [view p h]
  (let [end (min (+ p estimate-window) (or (:total view) ##Inf))]
    (view/need view p end)
    (loop [q p h h size 0 samples 0]
      (let [size    (+ size (:length h))
            samples (+ samples (:samples h))
            next    (+ q (:length h))
            h'      (when (<= (+ next 4) end)
                      (frame-header view next))]
        (if h'
          (recur next h' size samples)
          (/ (* 8.0 size (:sample-rate h)) samples))))))

(defn- duration
  "The duration in seconds of the audio in `view` whose first frame is at
  `p` with the header `h`, read in the context `ctx`.

  It's nil when there's no frame, or when no header counts the frames and
  the size of the file isn't known. The report of `ctx` tells when the
  duration is an estimate."
  [ctx view p h]
  (let [frames (when h (frame-count view p h))
        total  (:total view)]
    (cond
      ;; a header that counts no frames knows no duration
      (and frames (pos? frames))
      (/ (* frames (:samples h)) (double (:sample-rate h)))

      (and h total)
      (let [estimated {:id :mp3/estimated-duration :level :info}]
        (vswap! (:report ctx) conj estimated)
        (/ (* 8.0 (- total p)) (average-bitrate view p h))))))

(defn report
  "The diagnostics of the read `file`, with those of its tags. Each
  diagnostic has its message."
  [file]
  (mapv diagnostics/describe (view/report file)))

(defn read
  "The MP3 file in `view`, read with `opts`, as a map of :tags,
  :audio-start and :duration.

  - :tags are the ID3v2 tags
  - :audio-start is where the first frame starts
  - :duration is in seconds, or nil when the file has no frame, or has no
    Xing, Info or VBRI header and no known size

  The :except of `opts` is the ids of the quirks to leave out. When the
  view lacks bytes, the reading throws an exception that holds their
  ranges."
  ([view]
   (read view {}))
  ([view {:keys [except] :as opts}]
   {:pre [(map? opts)]}
   (let [tags        (id3/read-all view 0 opts)
         start       (or (:end (peek tags)) 0)
         ctx         {:except (set except) :report (volatile! [])}
         [p h]       (first-frame ctx view start)
         result      {:tags        tags
                      :audio-start p
                      :duration    (duration ctx view p h)}
         diagnostics (into (vec (mapcat id3/report tags)) @(:report ctx))]
     (view/reported result
                    (cond-> diagnostics
                      (nil? h) (conj {:id :mp3/no-audio :level :warning}))))))

(comment
  ;; one MPEG-1 Layer III frame of 128 kbit/s at 44.1 kHz: 417 bytes
  (def frame
    (concat [0xFF 0xFB 0x90 0x00] (repeat 413 0)))

  (def sample
    (id3/view-of (bytes/of (apply concat (repeat 20 frame)))))

  (frame-header sample 0)
  (read sample)
  (map :id (report (read sample)))

  ;; bytes that are no frame before the audio, with the quirk and without it
  (def padded
    (let [junk  (repeat 10 0)
          audio (apply concat (repeat 20 frame))]
      (id3/view-of (bytes/of (concat junk audio)))))

  (map :id (report (read padded)))
  (map :id (report (read padded {:except #{:mp3/junk-before-audio}})))
  #_.)
