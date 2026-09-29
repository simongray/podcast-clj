(ns dk.simongray.podcast.format.mp4
  "MP4 and M4A files, whose boxes ISO/IEC 14496-12 defines: `read` gives the
  duration from the movie header, the chapters of a QuickTime chapter track
  or, failing one, of a Nero chpl box, and the artwork of the covr item of
  the iTunes metadata. The movie box can come before the media data or
  after it, so the reading walks the boxes at the top level, and a reader
  that has only the start of the file learns where to look next (see
  dk.simongray.podcast.bytes/missing). A file written in fragments gives
  the duration of all its fragments in its mehd box, or else the reading
  takes the end of the last fragment that the mfra index at the end of the
  file lists.

  The reading is strict. The quirk `:mp4/zero-duration` takes the duration
  of the longest track when the movie header gives none, and `:except` in
  the options leaves it out."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.bytes :as bytes]))

(defn- box
  "The box whose header is at `p` in `view`, ending no later than `limit`:
  `{:type :start :content :end}`; nil when no box fits there."
  [view p limit]
  (bytes/need view p (+ p 16))
  (let [size32        (bytes/u32 view p)
        [size header] (case size32
                        1 [(bytes/u64 view (+ p 8)) 16]
                        0 [(- limit p) 8]
                        [size32 8])]
    (when (and (>= size header) (<= (+ p size) limit))
      {:type (bytes/code view (+ p 4) 4) :start p :content (+ p header) :end (+ p size)})))

(defn- children
  "The boxes in `view` from `p` to `end`."
  [view p end]
  (loop [p p acc []]
    (if-let [b (and (<= (+ p 8) end) (box view p end))]
      (recur (:end b) (conj acc b))
      acc)))

(defn- child
  "The first box of `type` inside the box `parent`, whose own content starts
  `skip` bytes in, past the version and flags of a full box."
  ([view parent type]
   (child view parent type 0))
  ([view {:keys [content end]} type skip]
   (some #(when (= type (:type %)) %) (children view (+ content skip) end))))

(defn- at-path
  "The box at the path of `types` under `parent`."
  [view parent & types]
  (reduce (fn [b t] (when b (child view b t))) parent types))

(defn- movie
  "The moov box of the file in `view`, found by walking the boxes at the
  top level from the start; nil when there is none."
  [view]
  (let [limit (or (:total view) ##Inf)]
    (loop [p 0]
      (when (< (+ p 8) limit)
        (when-let [b (box view p limit)]
          (if (= "moov" (:type b)) b (recur (:end b))))))))

(defn- flags
  "The flags of the full box `b`."
  [view {:keys [content]}]
  (bit-and (bytes/u32 view content) 0xFFFFFF))

(defn- versioned
  "The number at `p` in the full box `b`, 64 bits wide in version 1 and 32
  in version 0."
  [view b p]
  (if (= 1 (bytes/u8 view (:content b)))
    (bytes/u64 view p)
    (bytes/u32 view p)))

(defn- timing
  "`[timescale duration]` of the header box `b`, mvhd or mdhd, by its
  version; the duration nil when all its bits are ones, as a header gives
  a duration that it does not know."
  [view {:keys [content] :as b}]
  (let [wide? (= 1 (bytes/u8 view content))
        p     (+ content (if wide? 24 16))
        ones? (and (= 0xFFFFFFFF (bytes/u32 view p))
                   (or (not wide?) (= 0xFFFFFFFF (bytes/u32 view (+ p 4)))))]
    [(bytes/u32 view (+ content (if wide? 20 12)))
     (when-not ones? (versioned view b p))]))

(defn- entries
  "The rows of the table in the full box `b`, whose count follows the
  version and flags after `skip` more bytes, each row `width` bytes long and
  read by `row` from its offset; no more rows than the box holds."
  [view {:keys [content end]} skip width row]
  (let [n (min (bytes/u32 view (+ content 4 skip)) (quot (- end content 8 skip) width))]
    (mapv #(row (+ content 8 skip (* % width))) (range n))))

(defn- track
  "What the reading needs of the trak box `t`: its id, timescale and
  duration, the tracks its chapters are in, and its sample table."
  [view t]
  (let [tkhd (child view t "tkhd")
        mdia (child view t "mdia")
        chap (at-path view t "tref" "chap")]
    {:id       (when tkhd (bytes/u32 view (+ (:content tkhd) (if (= 1 (bytes/u8 view (:content tkhd))) 20 12))))
     :timing   (some->> (child view mdia "mdhd") (timing view))
     :chapters (when chap (mapv #(bytes/u32 view %) (range (:content chap) (- (:end chap) 3) 4)))
     :stbl     (at-path view mdia "minf" "stbl")}))

(def ^:no-doc max-samples
  "The most samples of a chapter track that the reading takes."
  10000)

(defn- per-chunk
  "How many samples each of `n` chunks holds, as the sample-to-chunk `runs`
  `[[first-chunk samples] ...]`, in the order of their first chunks, give
  it, in one pass over both."
  [runs n]
  (loop [i 1 runs (seq runs) per 0 acc (transient [])]
    (cond
      (> i n)                         (persistent! acc)
      (and runs (<= (ffirst runs) i)) (recur i (next runs) (second (first runs)) acc)
      :else                           (recur (inc i) runs per (conj! acc per)))))

(defn- samples-of
  "The samples of the sample table whose time-to-sample, sample-size,
  sample-to-chunk and chunk-offset boxes are `stts`, `stsz`, `stsc` and
  `stco`, as `samples` gives them: at most `max-samples`, in one pass over
  the tables, whose counts need not agree."
  [view stts stsz stsc stco]
  (let [wide?   (= "co64" (:type stco))
        deltas  (into [] (comp (mapcat (fn [[n d]] (repeat n d))) (take max-samples))
                      (entries view stts 0 8 #(vector (bytes/u32 view %) (bytes/u32 view (+ % 4)))))
        fixed   (bytes/u32 view (+ (:content stsz) 4))
        sizes   (if (pos? fixed)
                  (vec (repeat (min max-samples (bytes/u32 view (+ (:content stsz) 8))) fixed))
                  (entries view stsz 4 4 #(bytes/u32 view %)))
        runs    (entries view stsc 0 12 #(vector (bytes/u32 view %) (bytes/u32 view (+ % 4))))
        offsets (entries view stco 0 (if wide? 8 4) #(if wide? (bytes/u64 view %) (bytes/u32 view %)))
        counts  (per-chunk runs (count offsets))
        n       (min max-samples (count sizes) (count deltas))]
    (loop [chunk 0 k 0 at 0 i 0 start 0 acc (transient [])]
      (cond
        (or (= i n) (= chunk (count offsets)))
        (persistent! acc)

        (>= k (counts chunk))
        (recur (inc chunk) 0 0 i start acc)

        :else
        (let [at (if (zero? k) (offsets chunk) at)]
          (recur chunk (inc k) (+ at (sizes i)) (inc i) (+ start (deltas i))
                 (conj! acc {:offset at :size (sizes i) :start start :duration (deltas i)})))))))

(defn- samples
  "The samples of the sample table `stbl`, `[{:offset :size :start :duration}]`
  in the units of the track's timescale; none when a table is missing."
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
  [view {:keys [offset size]}]
  (when (>= size 2)
    (let [n (min (bytes/u16 view offset) (- size 2))
          p (+ offset 2)]
      (if (and (>= n 2) (= 0xFE (bytes/u8 view p)) (= 0xFF (bytes/u8 view (inc p))))
        (bytes/text view (+ p 2) (+ p n) :utf16be)
        (bytes/text view p (+ p n) :utf8)))))

(defn- quicktime-chapters
  "The chapters of the chapter track that a track of `tracks` names, as
  `[{:start-time :end-time :title}]`; nil when no track names one. Throws
  for the text samples that the view lacks, all at once."
  [view tracks]
  (when-let [chapter-track (some (fn [id] (some #(when (= id (:id %)) %) tracks))
                                 (mapcat :chapters tracks))]
    (let [[scale _] (:timing chapter-track)
          samples   (when (and scale (pos? scale) (:stbl chapter-track))
                      (samples view (:stbl chapter-track)))]
      ;; a sample can say that it is larger than its text, which two bytes
      ;; count: no more of it is needed
      (bytes/need-all view (for [{:keys [offset size]} samples]
                             [offset (+ offset (min size 65537))]))
      (seq (for [s samples]
             (let [title (sample-text view s)]
               (cond-> {:start-time (/ (:start s) (double scale))
                        :end-time   (/ (+ (:start s) (:duration s)) (double scale))}
                 (seq title) (assoc :title title))))))))

(defn- nero-chapters
  "The chapters of the Nero chpl box `b`, `[{:start-time :title}]`, the
  start in units of 100 nanoseconds."
  [view {:keys [content end]}]
  (let [p (+ content 4 (if (pos? (bytes/u8 view content)) 4 0))
        n (bytes/u8 view p)]
    (loop [p (inc p) k 0 acc []]
      (if (or (= k n) (> (+ p 9) end))
        acc
        (let [length (bytes/u8 view (+ p 8))
              title  (bytes/text view (+ p 9) (min end (+ p 9 length)) :utf8)]
          (recur (+ p 9 length) (inc k)
                 (conj acc (cond-> {:start-time (/ (bytes/u64 view p) 1e7)}
                             (seq title) (assoc :title title)))))))))

(defn- artwork
  "The artwork of the covr item of the iTunes metadata in the udta box `udta`,
  `{:type :data}`; nil when there is none. The meta box is a full box in
  ISO files and a plain one in QuickTime files, so its children start where
  the hdlr box is."
  [view udta]
  (when-let [meta (child view udta "meta")]
    (let [skip (if (= "hdlr" (bytes/code view (+ (:content meta) 4) 4)) 0 4)
          data (when-let [ilst (child view meta "ilst" skip)]
                 (at-path view ilst "covr" "data"))]
      (when data
        (let [image (bytes/slice view (+ (:content data) 8) (:end data))]
          {:type (or (get {13 "image/jpeg" 14 "image/png" 27 "image/bmp"} (bit-and (bytes/u32 view (:content data)) 0xFFFFFF))
                     (bytes/image-type image))
           :data image})))))

(defn- fragment-index
  "The mfra box at the end of the file in `view`, whose size the mfro box
  in the last 16 bytes gives; nil when there is none, or when the size of
  the file is not known."
  [view]
  (when-let [total (:total view)]
    ;; the index and the last fragments most often lie in the last 64 KB
    (bytes/need view (max 0 (- total 65536)) total)
    (when (and (>= total 16) (= "mfro" (bytes/code view (- total 12) 4)))
      (let [start (- total (bytes/u32 view (- total 4)))
            mfra  (when (<= 0 start (- total 16)) (box view start total))]
        (when (= "mfra" (:type mfra))
          (bytes/need view start total)
          mfra)))))

(defn- fragment-offsets
  "The offsets of the moof boxes that the tfra boxes of the mfra box `mfra`
  list."
  [view mfra]
  (for [tfra  (children view (:content mfra) (:end mfra))
        :when (= "tfra" (:type tfra))
        :let  [c      (:content tfra)
               wide?  (= 1 (bytes/u8 view c))
               sizes  (bytes/u32 view (+ c 8))
               ;; the time and the offset, then the numbers of the traf, the
               ;; trun and the sample, each as wide as `sizes` says
               width  (+ (if wide? 16 8) 3
                         (bit-and (bit-shift-right sizes 4) 3) (bit-and (bit-shift-right sizes 2) 3) (bit-and sizes 3))
               n      (min (bytes/u32 view (+ c 12)) (quot (- (:end tfra) c 16) width))]
        i     (range n)
        :let  [p (+ c 16 (* i width))]]
    (if wide? (bytes/u64 view (+ p 8)) (bytes/u32 view (+ p 4)))))

(defn- default-durations
  "The default sample duration of each track, by its id, that the trex
  boxes of the mvex box `mvex` give."
  [view mvex]
  (into {} (for [trex  (children view (:content mvex) (:end mvex))
                 :when (= "trex" (:type trex))]
             [(bytes/u32 view (+ (:content trex) 4)) (bytes/u32 view (+ (:content trex) 12))])))

(defn- run-duration
  "The duration of the samples of the trun box `trun`, each its own or else
  `default`."
  [view trun default]
  (let [f (flags view trun)
        c (:content trun)
        n (bytes/u32 view (+ c 4))]
    (if (zero? (bit-and f 0x100))
      (* n default)
      (let [start (+ c 8 (if (pos? (bit-and f 0x001)) 4 0) (if (pos? (bit-and f 0x004)) 4 0))
            width (* 4 (count (filter #(pos? (bit-and f %)) [0x100 0x200 0x400 0x800])))]
        (reduce + (map #(bytes/u32 view (+ start (* % width)))
                       (range (min n (quot (- (:end trun) start) width)))))))))

(defn- fragment-ends
  "Where each track ends in the moof box `moof`, `[[track-id end] ...]` in
  the units of its timescale: the decode time of its traf box, and the
  durations of the samples, the default of the tfhd box or else of
  `defaults` for a sample without its own; a traf box without a decode
  time is left out."
  [view moof defaults]
  (for [traf  (children view (:content moof) (:end moof))
        :when (= "traf" (:type traf))
        :let  [tfhd (child view traf "tfhd")
               tfdt (child view traf "tfdt")]
        :when (and tfhd tfdt)
        :let  [f       (flags view tfhd)
               id      (bytes/u32 view (+ (:content tfhd) 4))
               default (if (pos? (bit-and f 0x08))
                         (bytes/u32 view (+ (:content tfhd) 8 (if (pos? (bit-and f 0x01)) 8 0) (if (pos? (bit-and f 0x02)) 4 0)))
                         (get defaults id 0))
               runs    (filter #(= "trun" (:type %)) (children view (:content traf) (:end traf)))]]
    [id (+ (versioned view tfdt (+ (:content tfdt) 4))
           (reduce + (map #(run-duration view % default) runs)))]))

(defn- fragmented-duration
  "The duration in seconds of the movie written in fragments whose mvex box
  is `mvex`, as ISO/IEC 14496-12 gives it: the fragment duration of the
  mehd box, in the `timescale` of the movie; else the end of the last
  fragment that the index at the end of the file lists, in the timescale
  of its track in `tracks`; nil when the file gives neither."
  [view mvex timescale tracks]
  (or (when-let [mehd (child view mvex "mehd")]
        (let [d (versioned view mehd (+ (:content mehd) 4))]
          (when (and timescale (pos? timescale) (pos? d))
            (/ d (double timescale)))))
      (when-let [mfra (fragment-index view)]
        (when-let [offsets (seq (filter #(< % (:start mfra)) (fragment-offsets view mfra)))]
          (let [scales   (into {} (map (juxt :id (comp first :timing))) tracks)
                defaults (default-durations view mvex)
                ends     (for [b        (children view (apply max offsets) (:start mfra))
                               :when    (= "moof" (:type b))
                               [id end] (fragment-ends view b defaults)
                               :let     [scale (scales id)]
                               :when    (and scale (pos? scale))]
                           (/ end (double scale)))]
            (some->> (seq ends) (apply max)))))))

(defn read
  "The MP4 file in `view`: `{:duration :chapters :artwork}`, the duration in
  seconds, the chapters with `:start-time`, `:end-time` and `:title`, and
  the artwork as `{:type :data}`, each left out when the file has none;
  with the diagnostics of the reading under `:report`. `opts` may give
  `:except`, the ids of the quirks to leave out. The reading throws for
  bytes the view lacks (see `dk.simongray.podcast.bytes/missing`)."
  ([view]
   (read view {}))
  ([view {:keys [except] :as opts}]
   {:pre [(map? opts)]}
   (if-let [moov (movie view)]
     (let [_          (bytes/need view (:start moov) (:end moov))
           [scale d]  (some->> (child view moov "mvhd") (timing view))
           tracks     (mapv #(track view %) (filter #(= "trak" (:type %)) (children view (:content moov) (:end moov))))
           mvex       (child view moov "mvex")
           fragmented (when mvex (fragmented-duration view mvex scale tracks))
           longest    (some->> tracks
                               (keep (fn [{[s n] :timing}] (when (and s n (pos? s) (pos? n)) (/ n (double s)))))
                               seq (apply max))
           longest?   (and (not mvex) (not (and d (pos? d))) longest
                           (not (contains? (set except) :mp4/zero-duration)))
           duration   (cond mvex                                fragmented
                            longest?                            longest
                            (and d (pos? d) scale (pos? scale)) (/ d (double scale)))
           udta       (child view moov "udta")
           chapters   (or (quicktime-chapters view tracks)
                          (some->> (when udta (child view udta "chpl")) (nero-chapters view)))
           art        (when udta (artwork view udta))]
       (cond-> {:report (cond-> []
                          longest?                    (conj {:id :mp4/zero-duration :level :info})
                          (and mvex (not fragmented)) (conj {:id :mp4/fragments-unread :level :info}))}
         duration       (assoc :duration duration)
         (seq chapters) (assoc :chapters (vec chapters))
         art            (assoc :artwork art)))
     {:report [{:id :mp4/no-movie :level :warning}]})))
