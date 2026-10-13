(ns dk.simongray.podcast.util.bytes.view
  "Views of files whose bytes come in parts, for the readers of media files
  and images, on both platforms.

  A view holds the parts of a file that are there, each at its offset. A
  reader asks the view for the ranges that it needs before it reads them.
  A range that the view doesn't hold throws an exception that holds the
  ranges, so that the caller can fetch them and read again. A range past
  the end of the file throws too. The fetching is in util.bytes.ranges."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.wary-fetch.async :as async]))

(defn view
  "A view of a file of `total` bytes, which is nil when the size isn't
  known yet, holding no part of it."
  ([]
   (view nil))
  ([total]
   {:parts [] :total total}))

(defn add
  "The `view` with the bytes `b` that begin at `offset` in the file, in
  place of the parts that they cover. The `total` is the size of the
  file, or nil when the size isn't known."
  [view offset b total]
  (let [end      (+ offset (bytes/length b))
        covered? (fn [[o x]]
                   (and (<= offset o)
                        (<= (+ o (bytes/length x)) end)))]
    (cond-> view
      (pos? (bytes/length b))
      (update :parts #(conj (into [] (remove covered?) %) [offset b]))

      total
      (assoc :total total))))

(defn whole
  "A view of the file whose bytes are all in `b`, e.g. a file that the app
  has read, for a reader to read at once."
  [b]
  (add (view (bytes/length b)) 0 b (bytes/length b)))

(defn ^:no-doc part-at
  "The part of `view` that holds the byte at `p`, as a pair of its offset
  and its bytes."
  [view p]
  (some (fn [[o b]]
          (when (and (<= o p) (< p (+ o (bytes/length b))))
            [o b]))
        (:parts view)))

(defn held
  "How many bytes `view` holds from the start of the file on, without a
  gap."
  [view]
  (loop [p 0]
    (if-let [[o b] (part-at view p)]
      (recur (+ o (bytes/length b)))
      p)))

(defn ^:no-doc past-end
  "The exception of a read at `p`, past the end of the file."
  [p]
  (ex-info (str "Past the end of the file: " p) {::truncated true}))

(defn missing
  "The ranges that a read of a view missed, as pairs of a start and an
  end, when `e` is the exception it threw, or else nil."
  [e]
  (::missing (ex-data e)))

(defn truncated?
  "Whether `e` is the exception a read past the end of the file threw."
  [e]
  (boolean (::truncated (ex-data e))))

(defn ^:no-doc unless-broken
  "What `f` gives when called, or nil when it fails. A failure because the
  view lacks bytes or the file ends too soon is thrown again, so that a
  reader can fetch the bytes or report the end."
  [f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (when (or (missing e)
                (truncated? e))
        (throw e))
      nil)))

(defn- gaps
  "The ranges from `start` to `end` that `view` lacks, as pairs of a start
  and an end, with the end cut at the end of the file. It throws the
  exception of a read past the end when `start` lies past the end of the
  file."
  [view start end]
  (let [total (:total view)]
    (when (and total
               (< start end)
               (>= start total))
      (throw (past-end start)))
    (let [end (if total (min end total) end)]
      (loop [p start acc []]
        (if (>= p end)
          acc
          (if-let [[o b] (part-at view p)]
            (recur (+ o (bytes/length b)) acc)
            (let [next-part (some->> (:parts view)
                                     (keep (fn [[o _]] (when (< p o end) o)))
                                     seq
                                     (apply min))
                  to        (or next-part end)]
              (recur to (conj acc [p to])))))))))

(defn need-all
  "Nil when `view` holds the bytes of all the `ranges`, each a pair of a
  start and an end, end excluded.

  When the view lacks bytes, it throws an exception that holds each range
  that it lacks. So a caller can fetch them all at one time."
  [view ranges]
  (when-some [missing (seq (mapcat (fn [[start end]] (gaps view start end))
                                   ranges))]
    (throw (ex-info (str "Bytes missing from " (ffirst missing))
                    {::missing (vec missing)}))))

(defn need
  "Nil when `view` holds the bytes from `start` to `end`, end excluded.
  The end is cut at the end of the file when its size is known.

  When the view lacks bytes, it throws an exception that holds the range
  that it lacks. When `start` is past the end of the file, it throws the
  exception of a read past the end."
  [view start end]
  (need-all view [[start end]]))

(defn u8
  "The byte at `p` in `view`, from 0 to 255."
  [view p]
  (if-let [[o b] (part-at view p)]
    (bytes/at b (- p o))
    (do (need view p (inc p))
        (throw (past-end p)))))

(defn u16
  "The big-endian 16-bit number at `p` in `view`."
  [view p]
  (+ (* 256 (u8 view p)) (u8 view (inc p))))

(defn u24
  "The big-endian 24-bit number at `p` in `view`."
  [view p]
  (+ (* 65536 (u8 view p)) (u16 view (inc p))))

(defn u32
  "The big-endian 32-bit number at `p` in `view`."
  [view p]
  (+ (* 16777216 (u8 view p)) (u24 view (inc p))))

(defn u16le
  "The little-endian 16-bit number at `p` in `view`."
  [view p]
  (+ (u8 view p) (* 256 (u8 view (inc p)))))

(defn u24le
  "The little-endian 24-bit number at `p` in `view`."
  [view p]
  (+ (u16le view p) (* 65536 (u8 view (+ p 2)))))

(defn u32le
  "The little-endian 32-bit number at `p` in `view`."
  [view p]
  (+ (u16le view p) (* 65536 (u16le view (+ p 2)))))

(defn u64
  "The big-endian 64-bit number at `p` in `view`, exact up to 2^53 in
  JavaScript, and up to 2^63 on the JVM. Above that, it's the nearest
  floating-point number."
  [view p]
  (let [high (u32 view p)
        low  (u32 view (+ p 4))]
    (if (< high 0x80000000)
      (+ (* 4294967296 high) low)
      (+ (* 4294967296.0 high) low))))

(defn- copy-into!
  "Copy the bytes of `b` from `start` to `end` into the bytes `out`, from
  `at` on."
  [out at b start end]
  #?(:clj  (System/arraycopy b (int start) out (int at) (int (- end start)))
     :cljs (.set out (.subarray b start end) at)))

(defn slice
  "The bytes of `view` from `start` to `end`, end excluded, as new bytes,
  copied from each part that holds some of them."
  [view start end]
  (need view start end)
  (when (and (:total view) (> end (:total view)))
    (throw (past-end (:total view))))
  (let [[o b] (part-at view start)]
    (cond
      (>= start end)                  (bytes/of [])
      (<= end (+ o (bytes/length b))) (bytes/copy b (- start o) (- end o))
      :else
      (let [n   (- end start)
            out #?(:clj (byte-array n) :cljs (js/Uint8Array. n))]
        (loop [p start]
          (if (< p end)
            (let [[o b] (part-at view p)
                  to    (min end (+ o (bytes/length b)))]
              (copy-into! out (- p start) b (- p o) (- to o))
              (recur to))
            out))))))

(defn text
  "The text of the bytes of `view` from `start` to `end` in `encoding`:
  :latin1, :utf8, :utf16le or :utf16be."
  [view start end encoding]
  (bytes/decode (slice view start end) encoding))

(defn code
  "The `n` bytes of `view` at `p` as characters of ISO-8859-1, the form of
  the four-character codes of ID3 frames and MP4 boxes."
  [view p n]
  (text view p (+ p n) :latin1))

(defn report
  "The diagnostics of `result`, which a reader of a view gave, as maps of
  an :id, a :level, a :message and details."
  [result]
  (mapv diagnostics/describe (:podcast/report (meta result))))

(defn ^:no-doc reported
  "The `result` with the `diagnostics` as its report."
  [result diagnostics]
  (vary-meta result assoc :podcast/report (vec diagnostics)))

(defn ^:no-doc context
  "A reading context for the `opts` of a reader, which notes the
  diagnostics of a reading, and knows the quirks that the :except of
  `opts` leaves out."
  [{:keys [except] :as opts}]
  {:except (set except) :report (volatile! [])})

(defn ^:no-doc diagnostic
  "The diagnostic `id` at `level`, with the `details`."
  [id level & {:as details}]
  (merge {:id id :level level} details))

(defn ^:no-doc note!
  "Note the diagnostic `d` in the reading context `ctx`."
  [ctx d]
  (vswap! (:report ctx) conj d))

(defn ^:no-doc noted
  "The diagnostics that the reading context `ctx` noted, in order."
  [ctx]
  @(:report ctx))

(defn ^:no-doc quirk?
  "Whether the reading context `ctx` makes the quirk `id`, which its
  :except doesn't leave out."
  [ctx id]
  (not (contains? (:except ctx) id)))

(defn ^:no-doc failure
  "The diagnostic of a reading that failed with `e`: :file/truncated when
  it went past the end of the file, or else :file/malformed."
  [e]
  (if (truncated? e)
    {:id :file/truncated :level :warning}
    {:id :file/malformed :level :warning :reason (async/explain e)}))

(defn- guarded
  "What `f` gives when called, or what `failed` gives for the exception
  when the call fails. On the JVM, a call that overflows the stack fails
  too, as deep input can make it."
  [f failed]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (failed e))
    #?(:clj (catch StackOverflowError e
              (failed e)))))

(defn ^:no-doc part
  "What `f` gives when called, as a map of the :value, for the reading of
  the part `k` of a file that the rest of the file can do without, such
  as its artwork.

  When the part doesn't read, the map has a :report of one diagnostic,
  which names the :part. A reading that lacks bytes of the view throws on,
  so that the caller can fetch them."
  [k f]
  (let [failed (fn [e]
                 (if (missing e)
                   (throw e)
                   {:report [(assoc (failure e) :part k)]}))]
    (guarded (fn [] {:value (f)}) failed)))

(defn reading
  "What the function `f` gives, a reading of a view that takes no
  arguments.

  When the view lacks bytes, the result has the ranges that it lacks
  under :need, as pairs of a start and an end, end excluded. When the
  reading fails, the result is an empty map, and its report says why. The
  diagnostic is :file/truncated for a reading past the end of the file,
  and :file/malformed for any other failure. On the JVM, a reading that
  overflows the stack is such a failure."
  [f]
  (let [failed (fn [e]
                 (if (missing e)
                   {:need (missing e)}
                   (reported {} [(failure e)])))]
    (guarded f failed)))

(comment
  (def sample
    (bytes/of [0x49 0x44 0x33 4 0 0 0 0 0 28]))

  (code (add (view) 0 sample nil) 0 3)

  ;; a view of a file of 100 bytes that holds 10 of them, from offset 10
  (def some-bytes
    (add (view 100) 10 sample 100))

  (u8 some-bytes 10)
  (u32 some-bytes 16)

  ;; what a reading that lacks bytes gives, and a reading past the end
  (reading #(u32 some-bytes 30))
  (report (reading #(u32 some-bytes 100)))
  #_.)
