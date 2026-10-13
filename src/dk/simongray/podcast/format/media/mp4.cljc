(ns dk.simongray.podcast.format.media.mp4
  "MP4 and M4A files, whose boxes ISO/IEC 14496-12 defines.

  A reading gives the duration from the movie header, the chapters, the
  artwork, the codecs and size of the tracks that play, and whether the
  movie comes first. The chapters are those of a QuickTime chapter track,
  or else those of a Nero chpl box. The artwork is the covr item of the
  iTunes metadata.

  The movie box can come before the media data or after it, so the
  reading walks the boxes at the top level. A reader that has only the
  start of the file learns where to look next.

  A file written in fragments gives the duration of all its fragments in
  its mehd box. Without that box, the reading takes the end of the last
  fragment that the mfra index at the end of the file lists.

  The reading is strict. The quirk :mp4/zero-duration takes the duration
  of the longest track when the movie header gives none. The :except
  option leaves it out."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]))

(defn ^:no-doc box
  "The box whose header is at `p` in `view`, ending no later than `limit`,
  or nil when no box fits there. It's a map of :type, :start, :content
  and :end."
  [view p limit]
  (view/need view p (+ p 16))
  (let [size32        (view/u32 view p)
        [size header] (case size32
                        1 [(view/u64 view (+ p 8)) 16]
                        0 [(- limit p) 8]
                        [size32 8])]
    (when (and (>= size header)
               (<= (+ p size) limit))
      {:type    (view/code view (+ p 4) 4)
       :start   p
       :content (+ p header)
       :end     (+ p size)})))

(defn ^:no-doc children
  "The boxes in `view` from `p` to `end`."
  [view p end]
  (loop [p p acc []]
    (if-let [b (and (<= (+ p 8) end) (box view p end))]
      (recur (:end b) (conj acc b))
      acc)))

(defn ^:no-doc child
  "The first box of `type` in `view` inside the box `parent`, whose own
  content starts `skip` bytes in, past the version and flags of a full
  box."
  ([view parent type]
   (child view parent type 0))
  ([view {:keys [content end] :as parent} type skip]
   (some #(when (= type (:type %)) %) (children view (+ content skip) end))))

(defn ^:no-doc at-path
  "The box in `view` at the path of `types` under `parent`."
  [view parent & types]
  (reduce (fn [b t] (when b (child view b t))) parent types))

(defn- movie
  "The moov box of the file in `view`, found by walking the boxes at the
  top level from the start, or nil when there's none. It has :after-media?
  when an mdat box comes before it."
  [view]
  (let [limit (or (:total view) ##Inf)]
    (loop [p 0 media? false]
      (when (< (+ p 8) limit)
        (when-let [{:keys [type end] :as b} (box view p limit)]
          (if (= "moov" type)
            (cond-> b media? (assoc :after-media? true))
            (recur end (or media? (= "mdat" type)))))))))

(defn- held?
  "Whether `view` holds the `n` bytes at `p`, without asking for them."
  [view p n]
  (let [[o b] (view/part-at view p)]
    (boolean (and o (<= (+ p n) (+ o (bytes/length b)))))))

(defn ^:no-doc media-start
  "Where the media data of the file in `view` starts, the content of its
  first mdat box at the top level, as far as the bytes at hand go, or nil.
  It asks for no more bytes, since a reading that comes by the movie
  first may not have the box after it."
  [view]
  (let [limit (or (:total view) ##Inf)]
    (loop [p 0]
      (when (and (< (+ p 8) limit) (held? view p 16))
        (when-let [{:keys [type end content]} (box view p limit)]
          (if (= "mdat" type)
            content
            (recur end)))))))

(defn ^:no-doc flags
  "The flags of the full box `b` in `view`."
  [view {:keys [content] :as b}]
  (bit-and (view/u32 view content) 0xFFFFFF))

(defn ^:no-doc versioned
  "The number at `p` in `view`, in the full box `b`, 64 bits wide in
  version 1 and 32 in version 0."
  [view b p]
  (if (= 1 (view/u8 view (:content b)))
    (view/u64 view p)
    (view/u32 view p)))

(defn ^:no-doc timing
  "The timescale and the duration of the header box `b` in `view`, mvhd or
  mdhd, as a pair. The duration is nil when all its bits are ones, which
  is how a header gives a duration that it doesn't know."
  [view {:keys [content] :as b}]
  (let [wide? (= 1 (view/u8 view content))
        p     (+ content (if wide? 24 16))
        ones? (and (= 0xFFFFFFFF (view/u32 view p))
                   (or (not wide?)
                       (= 0xFFFFFFFF (view/u32 view (+ p 4)))))]
    [(view/u32 view (+ content (if wide? 20 12)))
     (when-not ones? (versioned view b p))]))

(defn- entries
  "The rows of the table `skip` bytes after the version and flags of the
  full box `b` in `view`, each `width` bytes long and read by `row-fn`
  from its offset. No more rows are read than the box holds."
  [view {:keys [content end] :as b} skip width row-fn]
  (let [listed (view/u32 view (+ content 4 skip))
        held   (quot (- end content 8 skip) width)
        n      (min listed held)]
    (mapv #(row-fn (+ content 8 skip (* % width))) (range n))))

;; ISO/IEC 14496-12, section 8.3.2: the width and height end the box, as
;; fixed-point numbers of 16.16 bits
(defn- dimensions
  "The width and height in pixels of the tkhd box `tkhd` in `view`, as a
  pair, or nil when the box has none."
  [view {:keys [content end] :as tkhd}]
  (let [p (+ content (if (= 1 (view/u8 view content)) 88 76))]
    (when (<= (+ p 8) end)
      (let [width  (quot (view/u32 view p) 65536)
            height (quot (view/u32 view (+ p 4)) 65536)]
        (when (and (pos? width) (pos? height))
          [width height])))))

;; ISO/IEC 14496-12, section 8.4.3: the handler type follows the version,
;; the flags and four bytes of zeros
(defn- handler
  "The handler type of the hdlr box `hdlr` in `view`, e.g. vide or soun."
  [view {:keys [content end] :as hdlr}]
  (when (<= (+ content 12) end)
    (view/code view (+ content 8) 4)))

(defn- track
  "What the reading needs of the trak box `t` in `view`: its id, timescale
  and duration, the tracks its chapters are in, its sample table, its
  handler, whether it's enabled, and its width and height."
  [view t]
  (let [tkhd  (child view t "tkhd")
        mdia  (child view t "mdia")
        chap  (at-path view t "tref" "chap")
        hdlr  (at-path view t "mdia" "hdlr")
        wide? (and tkhd (= 1 (view/u8 view (:content tkhd))))]
    {:id       (when tkhd
                 (view/u32 view (+ (:content tkhd) (if wide? 20 12))))
     :timing   (some->> (child view mdia "mdhd") (timing view))
     :chapters (when chap
                 (mapv #(view/u32 view %)
                       (range (:content chap) (- (:end chap) 3) 4)))
     :stbl     (at-path view mdia "minf" "stbl")
     :handler  (some->> hdlr (handler view))
     :enabled? (boolean (and tkhd (odd? (flags view tkhd))))
     :size     (some->> tkhd (dimensions view))}))

(def ^:no-doc max-samples
  "The most samples of a chapter track that the reading takes."
  10000)

(defn- per-chunk
  "How many samples each of `n` chunks holds, by the sample-to-chunk
  `runs`, in one pass. Each run is a pair of its first chunk and its
  samples per chunk, and the runs are in order."
  [runs n]
  (loop [i 1 runs (seq runs) per 0 acc (transient [])]
    (cond
      (> i n)
      (persistent! acc)

      (and runs (<= (ffirst runs) i))
      (recur i (next runs) (second (first runs)) acc)

      :else
      (recur (inc i) runs per (conj! acc per)))))

(defn- samples-of
  "The samples of a sample table in `view`, from its `stts`, `stsz`, `stsc`
  and `stco` boxes.

  Those are the time-to-sample, sample-size, sample-to-chunk and
  chunk-offset boxes. The tables are read in one pass, up to max-samples,
  and their counts need not agree."
  [view stts stsz stsc stco]
  (let [wide?   (= "co64" (:type stco))
        pair    #(vector (view/u32 view %) (view/u32 view (+ % 4)))
        offset  #(if wide? (view/u64 view %) (view/u32 view %))
        deltas  (into []
                      (comp (mapcat (fn [[n d]] (repeat n d)))
                            (take max-samples))
                      (entries view stts 0 8 pair))
        fixed   (view/u32 view (+ (:content stsz) 4))
        sizes   (if (pos? fixed)
                  (let [listed (view/u32 view (+ (:content stsz) 8))]
                    (vec (repeat (min max-samples listed) fixed)))
                  (entries view stsz 4 4 #(view/u32 view %)))
        runs    (entries view stsc 0 12 pair)
        offsets (entries view stco 0 (if wide? 8 4) offset)
        counts  (per-chunk runs (count offsets))
        n       (min max-samples (count sizes) (count deltas))]
    (loop [chunk 0 k 0 at 0 i 0 start 0 acc (transient [])]
      (cond
        (or (= i n) (= chunk (count offsets)))
        (persistent! acc)

        (>= k (counts chunk))
        (recur (inc chunk) 0 0 i start acc)

        :else
        (let [at     (if (zero? k) (offsets chunk) at)
              sample {:offset   at
                      :size     (sizes i)
                      :start    start
                      :duration (deltas i)}]
          (recur chunk (inc k) (+ at (sizes i)) (inc i) (+ start (deltas i))
                 (conj! acc sample)))))))

(defn- samples
  "The samples of the sample table `stbl` in `view`, or none when a table
  is missing. Each is a map of :offset, :size, :start and :duration, in
  the units of the track's timescale."
  [view stbl]
  (let [stts (child view stbl "stts")
        stsz (child view stbl "stsz")
        stsc (child view stbl "stsc")
        stco (or (child view stbl "stco") (child view stbl "co64"))]
    (if (and stts stsz stsc stco)
      (samples-of view stts stsz stsc stco)
      [])))

(defn- sample-text
  "The text of the QuickTime text sample `s` in `view`: its length, then
  the text in UTF-8, or in UTF-16 after a byte order mark."
  [view {:keys [offset size] :as s}]
  (when (>= size 2)
    (let [n (min (view/u16 view offset) (- size 2))
          p (+ offset 2)]
      (if (and (>= n 2)
               (= 0xFE (view/u8 view p))
               (= 0xFF (view/u8 view (inc p))))
        (view/text view (+ p 2) (+ p n) :utf16be)
        (view/text view p (+ p n) :utf8)))))

(defn- quicktime-chapters
  "The chapters in `view` of the chapter track that one of `tracks` names,
  or nil when none names one. Each is a map of :start-time, :end-time and
  :title. When the view lacks text samples, it throws for all at once."
  [view tracks]
  (when-let [chapter-track (some (fn [id]
                                   (some #(when (= id (:id %)) %) tracks))
                                 (mapcat :chapters tracks))]
    (let [[scale _] (:timing chapter-track)
          samples   (when (and scale (pos? scale) (:stbl chapter-track))
                      (samples view (:stbl chapter-track)))]
      ;; a sample can say that it is larger than its text, which two bytes
      ;; count: no more of it is needed
      (view/need-all view (for [{:keys [offset size]} samples]
                            [offset (+ offset (min size 65537))]))
      (seq (for [{:keys [start duration] :as s} samples]
             (let [title (sample-text view s)]
               (cond-> {:start-time (/ start (double scale))
                        :end-time   (/ (+ start duration) (double scale))}
                 (seq title) (assoc :title title))))))))

(defn- nero-chapters
  "The chapters of the Nero chpl box `b` in `view`, each a map of
  :start-time and :title. The box gives the start in units of 100
  nanoseconds."
  [view {:keys [content end] :as b}]
  (let [p (+ content 4 (if (pos? (view/u8 view content)) 4 0))
        n (view/u8 view p)]
    (loop [p (inc p) k 0 acc []]
      (if (or (= k n) (> (+ p 9) end))
        acc
        (let [length  (view/u8 view (+ p 8))
              title   (view/text view (+ p 9) (min end (+ p 9 length)) :utf8)
              chapter (cond-> {:start-time (/ (view/u64 view p) 1e7)}
                        (seq title) (assoc :title title))]
          (recur (+ p 9 length) (inc k) (conj acc chapter)))))))

(defn- movie-chapters
  "The chapters in `view` of the movie whose tracks are `tracks`: those of
  its QuickTime chapter track, or else those of the Nero chpl box in its
  `udta` box."
  [view tracks udta]
  (or (quicktime-chapters view tracks)
      (some->> (when udta (child view udta "chpl"))
               (nero-chapters view))))

(def ^:no-doc image-types
  "The MIME type of each type of data that a covr item names."
  {13 "image/jpeg"
   14 "image/png"
   27 "image/bmp"})

(defn- artwork
  "The artwork of the covr item of the iTunes metadata in the `udta` box in
  `view`, as a map of :type and :data, or nil when there's none.

  The meta box is a full box in ISO files and a plain one in QuickTime
  files, so its children start where the hdlr box is."
  [view udta]
  (when-let [meta (child view udta "meta")]
    (let [skip (if (= "hdlr" (view/code view (+ (:content meta) 4) 4)) 0 4)
          data (when-let [ilst (child view meta "ilst" skip)]
                 (at-path view ilst "covr" "data"))]
      (when data
        (let [image     (view/slice view (+ (:content data) 8) (:end data))
              data-type (bit-and (view/u32 view (:content data)) 0xFFFFFF)]
          {:type (or (get image-types data-type) (image/sniff image))
           :data image})))))

(defn- fragment-index
  "The mfra box at the end of the file in `view`, whose size the mfro box
  in the last 16 bytes gives. It's nil when there's none, or when the size
  of the file isn't known."
  [view]
  (when-let [total (:total view)]
    ;; the index and the last fragments most often lie in the last 64 KB
    (view/need view (max 0 (- total 65536)) total)
    (when (and (>= total 16)
               (= "mfro" (view/code view (- total 12) 4)))
      (let [start (- total (view/u32 view (- total 4)))
            mfra  (when (<= 0 start (- total 16))
                    (box view start total))]
        (when (= "mfra" (:type mfra))
          (view/need view start total)
          mfra)))))

(defn- fragment-offsets
  "The offsets of the moof boxes that the tfra boxes of the mfra box `mfra`
  in `view` list."
  [view mfra]
  (for [tfra  (children view (:content mfra) (:end mfra))
        :when (= "tfra" (:type tfra))
        :let  [c      (:content tfra)
               wide?  (= 1 (view/u8 view c))
               sizes  (view/u32 view (+ c 8))
               ;; the time and the offset, then the numbers of the traf, the
               ;; trun and the sample, each as wide as `sizes` says
               width  (+ (if wide? 16 8)
                         (inc (bit-and (bit-shift-right sizes 4) 3))
                         (inc (bit-and (bit-shift-right sizes 2) 3))
                         (inc (bit-and sizes 3)))
               listed (view/u32 view (+ c 12))
               held   (quot (- (:end tfra) c 16) width)
               n      (min listed held)]
        i     (range n)
        :let  [p (+ c 16 (* i width))]]
    (if wide?
      (view/u64 view (+ p 8))
      (view/u32 view (+ p 4)))))

(defn- default-durations
  "The default sample duration of each track by its id, as the trex boxes
  of the mvex box `mvex` in `view` give them."
  [view mvex]
  (into {} (for [trex  (children view (:content mvex) (:end mvex))
                 :when (= "trex" (:type trex))
                 :let  [c (:content trex)]]
             [(view/u32 view (+ c 4)) (view/u32 view (+ c 12))])))

(defn- run-duration
  "The duration of the samples of the trun box `trun` in `view`, each its
  own or else `default`."
  [view trun default]
  (let [f     (flags view trun)
        flag? #(pos? (bit-and f %))
        c     (:content trun)
        n     (view/u32 view (+ c 4))]
    (if-not (flag? 0x100)
      (* n default)
      (let [start (+ c 8 (if (flag? 0x001) 4 0) (if (flag? 0x004) 4 0))
            width (* 4 (count (filter flag? [0x100 0x200 0x400 0x800])))]
        (reduce + (map #(view/u32 view (+ start (* % width)))
                       (range (min n (quot (- (:end trun) start) width)))))))))

(defn- fragment-ends
  "Where each track ends in the moof box `moof` in `view`, by the
  `defaults` of the sample durations. Each is a pair of a track id and an
  end in the units of its timescale.

  The end is the decode time of the traf box plus the durations of the
  samples. A sample without its own duration has the default of the tfhd
  box, or else that of `defaults`. A traf box without a decode time is
  left out."
  [view moof defaults]
  (for [traf  (children view (:content moof) (:end moof))
        :when (= "traf" (:type traf))
        :let  [tfhd (child view traf "tfhd")
               tfdt (child view traf "tfdt")]
        :when (and tfhd tfdt)
        :let  [f       (flags view tfhd)
               flag?   #(pos? (bit-and f %))
               c       (:content tfhd)
               id      (view/u32 view (+ c 4))
               at      (+ c 8 (if (flag? 0x01) 8 0) (if (flag? 0x02) 4 0))
               default (if (flag? 0x08)
                         (view/u32 view at)
                         (get defaults id 0))
               boxes   (children view (:content traf) (:end traf))
               runs    (filter #(= "trun" (:type %)) boxes)]]
    [id (+ (versioned view tfdt (+ (:content tfdt) 4))
           (reduce + (map #(run-duration view % default) runs)))]))

(defn- fragmented-duration
  "The duration in seconds of the fragmented movie in `view` whose mvex box
  is `mvex`, by the `timescale` of the movie and its `tracks`. It's nil
  when the file gives none.

  As ISO/IEC 14496-12 has it, it's the fragment duration of the mehd box,
  in the timescale of the movie. Without a mehd box, it's the end of the
  last fragment that the index at the end of the file lists, in the
  timescale of its track."
  [view mvex timescale tracks]
  (or (when-let [mehd (child view mvex "mehd")]
        (let [d (versioned view mehd (+ (:content mehd) 4))]
          (when (and timescale (pos? timescale) (pos? d))
            (/ d (double timescale)))))
      (when-let [mfra (fragment-index view)]
        (when-let [offsets (->> (fragment-offsets view mfra)
                                (filter #(< % (:start mfra)))
                                seq)]
          (let [scales   (into {} (map (juxt :id (comp first :timing))) tracks)
                defaults (default-durations view mvex)
                latest   (apply max offsets)
                ends     (for [b        (children view latest (:start mfra))
                               :when    (= "moof" (:type b))
                               [id end] (fragment-ends view b defaults)
                               :let     [scale (scales id)]
                               :when    (and scale (pos? scale))]
                           (/ end (double scale)))]
            (some->> (seq ends) (apply max)))))))

(defn- longest-track
  "The duration in seconds of the longest of `tracks`, or nil when no track
  gives a duration."
  [tracks]
  (some->> tracks
           (keep (fn [{[s n] :timing}]
                   (when (and s n (pos? s) (pos? n))
                     (/ n (double s)))))
           seq
           (apply max)))

(defn- digits
  "The number `n` in `base`, 10 or 16, with upper-case letters, padded with
  zeros to `width` digits."
  [n base width]
  (let [chars "0123456789ABCDEF"
        s     (loop [n n acc ()]
                (if (< n base)
                  (apply str (nth chars n) acc)
                  (recur (quot n base) (cons (nth chars (rem n base)) acc))))]
    (str (apply str (repeat (- width (count s)) "0")) s)))

;; RFC 6381, section 3.3; ISO/IEC 14496-15, section 5.3.3.1: the profile,
;; the constraints and the level are bytes 1 to 3 of avcC
(defn- avc-codecs
  "The codecs string of an H.264 track whose sample entry is of `type`,
  from its avcC box `avcc` in `view`."
  [view type {:keys [content] :as avcc}]
  (apply str type "." (map #(digits (view/u8 view (+ content %)) 16 2)
                           [1 2 3])))

;; ISO/IEC 14496-15, annex E.3: the profile space and number, the profile
;; compatibility flags in reverse order, the tier and level, and the
;; constraint bytes but trailing zeros
(defn- hevc-codecs
  "The codecs string of an HEVC track whose sample entry is of `type`,
  from its hvcC box `hvcc` in `view`."
  [view type {:keys [content] :as hvcc}]
  (let [b        (view/u8 view (+ content 1))
        compat   (view/u32 view (+ content 2))
        reversed (reduce #(+ (* 2 %1) (if (bit-test compat %2) 1 0))
                         0 (range 32))
        flags    (->> (range 6)
                      (map #(view/u8 view (+ content 6 %)))
                      reverse
                      (drop-while zero?)
                      reverse)]
    (str/join "." (concat [type
                           (str (nth ["" "A" "B" "C"] (bit-shift-right b 6))
                                (bit-and b 0x1F))
                           (digits reversed 16 1)
                           (str (if (bit-test b 5) "H" "L")
                                (view/u8 view (+ content 12)))]
                          (map #(digits % 16 2) flags)))))

;; AV1 Codec ISO Media File Format Binding, sections 2.3 and 5
(defn- av1-codecs
  "The codecs string of an AV1 track, from its av1C box `av1c` in `view`."
  [view {:keys [content] :as av1c}]
  (let [b1    (view/u8 view (+ content 1))
        b2    (view/u8 view (+ content 2))
        depth (cond (not (bit-test b2 6)) 8
                    (bit-test b2 5)       12
                    :else                 10)]
    (str "av01." (bit-shift-right b1 5) "."
         (digits (bit-and b1 0x1F) 10 2) (if (bit-test b2 7) "H" "M") "."
         (digits depth 10 2))))

;; VP Codec ISO Media File Format Binding: vpcC and the codecs string
(defn- vp9-codecs
  "The codecs string of a VP9 track, from its vpcC box `vpcc` in `view`."
  [view {:keys [content] :as vpcc}]
  (str "vp09." (digits (view/u8 view (+ content 4)) 10 2)
       "." (digits (view/u8 view (+ content 5)) 10 2)
       "." (digits (bit-shift-right (view/u8 view (+ content 6)) 4) 10 2)))

;; ISO/IEC 14496-1, section 8.3.3: a size takes one to four bytes of seven
;; bits each
(defn- descriptor
  "The descriptor at `p` in `view`, as a map of its :tag and the :start and
  :end of its content."
  [view p]
  (loop [q (inc p) size 0 n 1]
    (let [b    (view/u8 view q)
          size (+ (* 128 size) (bit-and b 0x7F))]
      (if (and (bit-test b 7) (< n 4))
        (recur (inc q) size (inc n))
        {:tag (view/u8 view p) :start (inc q) :end (+ (inc q) size)}))))

;; RFC 6381, section 3.3: mp4a, the object type in hex, and for MPEG-4
;; audio the audio object type of ISO/IEC 14496-3, section 1.6.2.1
(defn- aac-codecs
  "The codecs string of an MPEG-4 audio track, from its esds box `esds` in
  `view`, or nil when its descriptors don't read."
  [view {:keys [content] :as esds}]
  (let [es     (descriptor view (+ content 4))
        flags  (view/u8 view (+ (:start es) 2))
        after  (cond-> (+ (:start es) 3)
                 (bit-test flags 7) (+ 2))
        after  (cond-> after
                 (bit-test flags 6) (+ 1 (view/u8 view after)))
        after  (cond-> after
                 (bit-test flags 5) (+ 2))
        config (descriptor view after)
        object (view/u8 view (:start config))
        info   (descriptor view (+ (:start config) 13))
        b0     (view/u8 view (:start info))
        audio  (if (= 31 (bit-shift-right b0 3))
                 (+ 32 (* 8 (bit-and b0 7))
                    (bit-shift-right (view/u8 view (inc (:start info))) 5))
                 (bit-shift-right b0 3))]
    (when (and (= 3 (:tag es)) (= 4 (:tag config)))
      (if (and (= 0x40 object) (= 5 (:tag info)))
        (str "mp4a.40." audio)
        (str "mp4a." (digits object 16 2))))))

;; ISO/IEC 14496-12, section 12.1.3 and 12.2.3: the boxes of a visual
;; sample entry start 78 bytes in, those of an audio one 28, and the sound
;; descriptions of QuickTime versions 1 and 2 are 16 and 36 bytes longer
(defn- codecs-of
  "The RFC 6381 codecs string of the track `t` in `view`, or the type of
  its sample entry when its configuration doesn't read, or nil."
  [view {:keys [stbl handler] :as t}]
  (when-let [{:keys [type content] :as entry}
             (when-let [stsd (when stbl (child view stbl "stsd"))]
               (box view (+ (:content stsd) 8) (:end stsd)))]
    (let [skip (if (= "vide" handler)
                 78
                 (case (view/u16 view (+ content 8)) 1 44 2 64 28))
          conf #(child view entry % skip)]
      (or (case type
            ("avc1" "avc3") (some->> (conf "avcC") (avc-codecs view type))
            ("hvc1" "hev1") (some->> (conf "hvcC") (hevc-codecs view type))
            "av01"          (some->> (conf "av1C") (av1-codecs view))
            "vp09"          (some->> (conf "vpcC") (vp9-codecs view))
            "mp4a"          (some->> (conf "esds") (aac-codecs view))
            nil)
          (str/lower-case type)))))

(defn- playing-tracks
  "The codecs, width and height of the tracks of `tracks` in `view` that
  play: the enabled video and audio tracks that hold no chapters, video
  first."
  [view tracks]
  (let [chapters (set (mapcat :chapters tracks))
        order    {"vide" 0 "soun" 1}
        playing  (->> tracks
                      (filter #(and (:enabled? %)
                                    (order (:handler %))
                                    (not (chapters (:id %)))))
                      (sort-by (comp order :handler)))
        codecs   (keep #(codecs-of view %) playing)
        [w h]    (some #(when (= "vide" (:handler %)) (:size %)) playing)]
    (cond-> {}
      (seq codecs) (assoc :codecs (str/join "," codecs))
      w            (assoc :width w :height h))))

(defn- read-movie
  "What the file in `view` whose moov box is `moov` says about itself, with
  the quirks of `except` left out."
  [view moov except]
  (view/need view (:start moov) (:end moov))
  (let [[scale d] (some->> (child view moov "mvhd") (timing view))
        boxes     (children view (:content moov) (:end moov))
        tracks    (mapv #(track view %) (filter #(= "trak" (:type %)) boxes))
        mvex      (child view moov "mvex")
        udta      (child view moov "udta")
        fragments (view/part :fragments
                             #(when mvex
                                (fragmented-duration view mvex scale tracks)))
        chapters  (view/part :chapters #(movie-chapters view tracks udta))
        art       (view/part :artwork #(when udta (artwork view udta)))
        played    (view/part :codecs #(playing-tracks view tracks))
        longest   (longest-track tracks)
        stated?   (and d (pos? d))
        longest?  (and (not mvex)
                       (not stated?)
                       longest
                       (not (contains? (set except) :mp4/zero-duration)))
        unread?   (and mvex (not (:value fragments)))
        duration  (cond mvex                             (:value fragments)
                        longest?                         longest
                        (and stated? scale (pos? scale)) (/ d (double scale)))]
    (view/reported
     (cond-> (merge {:movie-first? (not (:after-media? moov))}
                    (:value played))
       duration                (assoc :duration duration)
       (seq (:value chapters)) (assoc :chapters (vec (:value chapters)))
       (:value art)            (assoc :artwork (:value art)))
     (cond-> (into [] (mapcat :report) [fragments chapters art played])
       longest? (conj {:id :mp4/zero-duration :level :info})
       unread?  (conj {:id :mp4/fragments-unread :level :info})))))

(defn report
  "The diagnostics of the read `file`. Each diagnostic has its message."
  [file]
  (mapv diagnostics/describe (view/report file)))

(defn read
  "The MP4 file in `view`, read with `opts`, as a map of the keys below,
  each absent when the file doesn't have it.

  - :duration is in seconds
  - :chapters, each with :start-time, :end-time and :title
  - :artwork, a map of :type and :data
  - :codecs, the RFC 6381 codecs string of the tracks that play, e.g.
    \"avc1.64001F,mp4a.40.2\"
  - :width and :height of the video, in pixels
  - :movie-first?, whether the movie comes before the media data, so that
    a player can start before it has the end of the file
  - :media-start, where the media data starts, when the bytes at hand
    reach it

  The :except of `opts` is the ids of the quirks to leave out. When the
  view lacks bytes, the reading throws an exception that holds their
  ranges.

  The chapters, the artwork and the index of the fragments are parts that
  the rest can do without. A part that doesn't read is left out, and the
  report names it."
  ([view]
   (read view {}))
  ([view {:keys [except] :as opts}]
   {:pre [(map? opts)]}
   (if-let [moov (movie view)]
     (let [start (media-start view)]
       (cond-> (read-movie view moov except)
         start (assoc :media-start start)))
     (view/reported {} [{:id :mp4/no-movie :level :warning}]))))

(comment
  ;; a movie of 90 seconds: an ftyp box, then a moov box with its mvhd box
  (def sample-bytes
    (let [be4  (fn [n] (map #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))
          code (fn [s] (seq (bytes/encode s)))
          box  (fn [type content]
                 (concat (be4 (+ 8 (count content))) (code type) content))
          mvhd (box "mvhd" (concat (be4 0) (be4 0) (be4 0)
                                   (be4 1000) (be4 90000)
                                   (repeat 80 0)))]
      (bytes/of (concat (box "ftyp" (concat (code "M4A ") (be4 0)))
                        (box "moov" mvhd)))))

  (def sample
    (view/add (view/view) 0 sample-bytes (bytes/length sample-bytes)))

  (children sample 0 (:total sample))
  (read sample)
  (report (read sample))

  ;; the first 40 bytes alone: the reading tells the range that it needs
  (let [start (bytes/copy sample-bytes 0 40)]
    (view/reading #(read (view/add (view/view) 0 start nil))))
  #_.)
