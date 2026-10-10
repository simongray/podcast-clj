(ns dk.simongray.podcast.util.bytes.view
  "Views of files whose bytes come in parts, for the readers of media files
  and images, on both platforms.

  A view holds the parts of a file that are there, each at its offset. A
  reader asks the view for the ranges that it needs before it reads them.
  A range that the view doesn't hold throws an exception that holds the
  ranges, so that the caller can fetch them and read again. A range past
  the end of the file throws too."
  (:require [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
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

(defn ^:no-doc part-at
  "The part of `view` that holds the byte at `p`, as a pair of its offset
  and its bytes."
  [view p]
  (some (fn [[o b]]
          (when (and (<= o p) (< p (+ o (bytes/length b))))
            [o b]))
        (:parts view)))

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
  (let [b (slice view start end)]
    (bytes/decode b 0 (bytes/length b) encoding)))

(defn code
  "The `n` bytes of `view` at `p` as characters of ISO-8859-1, the form of
  the four-character codes of ID3 frames and MP4 boxes."
  [view p n]
  (text view p (+ p n) :latin1))

(defn report
  "The diagnostics of `result`, which a reader of a view gave, as maps of
  an :id, a :level and details."
  [result]
  (vec (:podcast/report (meta result))))

(defn ^:no-doc reported
  "The `result` with the `diagnostics` as its report."
  [result diagnostics]
  (vary-meta result assoc :podcast/report (vec diagnostics)))

(defn ^:no-doc failure
  "The diagnostic of a reading that failed with `e`: :file/truncated when
  it went past the end of the file, or else :file/malformed."
  [e]
  (if (truncated? e)
    {:id :file/truncated :level :warning}
    {:id :file/malformed :level :warning :reason (async/explain e)}))

(defn ^:no-doc unreachable
  "The diagnostic of a file that didn't come, by the `failure` of its
  request, as http/failure gives it, with its :reason, :url and :status."
  [failure]
  (into {:id :file/unreachable :level :warning}
        (select-keys failure [:reason :url :status])))

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
    (try
      {:value (f)}
      (catch #?(:clj Exception :cljs :default) e
        (failed e))
      #?(:clj (catch StackOverflowError e
                (failed e))))))

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
    (try
      (f)
      (catch #?(:clj Exception :cljs :default) e
        (failed e))
      #?(:clj (catch StackOverflowError e
                (failed e))))))

(def ^:no-doc chunk-size
  "The least that a reading fetches at a time, so that small reads come in
  one request."
  (* 64 1024))

(def default-limits
  "The limits of a reading by ranges that its options don't set.

  One reading fetches :max-bytes of a file at most, 32 MB, and starts no
  request after :max-seconds. Nil sets no limit of bytes or of time. It
  fetches in :max-rounds rounds, of :max-requests requests each. The
  ranges that a reading needs beyond them come in the rounds after.

  The sizes in a file are numbers that its writer chose, so a file can ask
  a reader for more than it holds."
  {:max-bytes    (* 32 1024 1024)
   :max-seconds  60
   :max-rounds   8
   :max-requests 16})

(defn- spans
  "The `ranges` that a reading needs, in order, with those less than
  chunk-size apart joined, so that ranges near each other come in one
  request."
  [ranges]
  (reduce (fn [acc [start end]]
            (let [[s e] (peek acc)]
              (if (and e (< (- start e) chunk-size))
                (conj (pop acc) [s (max e end)])
                (conj acc [start end]))))
          []
          (sort ranges)))

(defn- fetch-all
  "The parts that `fetch` gives of the `ranges`, one request after
  another, and none once `in-time-pred` says no, in a future or a promise.

  A part without bytes is left out. When a request fails, the result is a
  map of the :error, and no request goes out after it."
  [fetch ranges in-time-pred]
  (reduce (fn [parts [start end]]
            (async/then
             parts
             (fn [got]
               (if (or (:error got) (not (in-time-pred)))
                 got
                 (let [fetched #(async/then (fetch start end) identity)]
                   (async/then (async/attempt fetched)
                               (fn [{:keys [value error]}]
                                 (cond
                                   error          {:error error}
                                   (:bytes value) (conj got value)
                                   :else          got))))))))
          []
          ranges))

(defn- with-parts
  "The `view` with the `parts` that a round fetched."
  [view parts]
  (reduce (fn [v {b :bytes :keys [start total]}]
            (add v start b total))
          view
          parts))

(defn- size
  "The number of bytes in the `ranges`."
  [ranges]
  (reduce + (map (fn [[start end]] (- end start)) ranges)))

(defn- widened
  "The `ranges` with the end of each moved to what `end-fn` gives for the
  range, as far as the bytes that this adds fit in `spare`."
  [ranges end-fn spare]
  (first (reduce (fn [[acc spare] [start end :as range]]
                   (let [more (min spare (max 0 (- (end-fn range) end)))]
                     [(conj acc [start (+ end more)]) (- spare more)]))
                 [[] spare]
                 ranges)))

(defn- ahead
  "How far past the end of `span`, the one range of a round, the round
  reads, given the `walk` of the round before. It's chunk-size, where a
  reading most often goes next.

  It's twice as far as in the round before, up to 1 MB, when the range is
  short and starts where that round ended. A walk through the segments of
  a file asks for such ranges. The `walk` is a pair of where the round
  before ended and how far ahead it read."
  [[start end :as span] [until before :as walk]]
  (if (and until
           (<= until start)
           (< (- start until) chunk-size)
           (< (- end start) chunk-size))
    (min (* 2 before) (* 16 chunk-size))
    chunk-size))

(defn- round-of
  "The round that fetches the ranges `need` of a reading within `limits`
  and the bytes of `room`, after the `walk` of the round before, or nil.
  With `whole?`, the server sends a file from its start.

  The round is a map of the :ranges to fetch, and, for a round of one
  range, the :walk of where it ends and how far ahead it reads. It's nil
  when `need` holds more bytes than `room`, those that the reading can
  still fetch.

  When the server sends whole files, each request costs all the bytes
  before its range. The round then asks once, from the start of the file
  to an eighth further than the reading needs."
  [need {:keys [max-requests] :as limits} room walk whole?]
  (when-let [wanted (seq (take max-requests (spans need)))]
    (let [far    (apply max (map second wanted))
          batch  (if whole? [[0 far]] wanted)
          spare  (- room (size batch))
          more   (cond
                   whole?       (max chunk-size (quot far 8))
                   (next batch) nil
                   :else        (ahead (first batch) walk))
          ranges (widened batch
                          (if more
                            (fn [[_ end]] (+ end more))
                            (fn [[start end]] (max end (+ start chunk-size))))
                          spare)]
      (when-not (neg? spare)
        {:ranges ranges
         :walk   (when (and more (not whole?))
                   [(second (first ranges)) more])}))))

(defn- after-round
  "The state of a reading after a round that fetched the `parts` that
  `plan` asked for, for a reading that needed `need` in the `state` before it."
  [{:keys [round room whole?] :as state} need plan parts]
  {:round    (inc round)
   :previous need
   :room     (- room (reduce + (map (comp bytes/length :bytes) parts)))
   :walk     (:walk plan)
   :whole?   (or whole? (some? (some :whole? parts)))})

(defn read!
  "Read the file whose bytes `fetch` gets with the reader `read`, within
  the limits of `opts`. On the JVM it gives what the reader gives, and in
  ClojureScript a promise of it.

  The `read` function takes a view. It gives its result, or the ranges
  that it needs under :need, as pairs of a start and an end. The `fetch`
  function takes a start and an end, end excluded. It gives a map of the
  :bytes, :start and :total, or a future or a promise of one. Its answer
  is :whole? when it holds the file from its start, whatever start was
  asked. A server that ignores ranges gives such an answer.

  The ranges that one reading needs come in one round, in 16 requests at
  most, one after another. Each request asks for 64 KB or more, and ranges
  near each other come in one request.

  The `opts` give the limits, with default-limits for the limits that
  they don't set, and :now-fn, a function that gives the time, the clock by
  default. When a limit stops the reading, or when the ranges didn't
  come, the result is an empty map, and its report has :file/incomplete.
  A reading fetches nothing of ranges that hold more bytes than it can
  still fetch.

  Nothing that `fetch` does throws. When `fetch` fails, the result has a
  :file/unreachable diagnostic under :error."
  ([fetch read]
   (read! fetch read {}))
  ([fetch read opts]
   (let [{:keys [max-bytes max-seconds max-rounds now-fn] :as limits}
         (merge default-limits {:now-fn time/now} opts)
         deadline (when max-seconds (+ (inst-ms (now-fn)) (* 1000 max-seconds)))
         in-time? #(or (nil? deadline) (< (inst-ms (now-fn)) deadline))
         step     (fn step [v {:keys [round previous room walk whole?]
                               :as   state}]
                    (let [{:keys [need] :as result} (read v)
                          more? (and need
                                     (< round max-rounds)
                                     (not= need previous)
                                     (in-time?))
                          plan  (when more?
                                  (round-of need limits room walk whole?))]
                      (cond
                        (nil? need)
                        result

                        (nil? plan)
                        (reported {} [{:id     :file/incomplete
                                       :level  :warning
                                       :ranges need}])

                        :else
                        (async/then (fetch-all fetch (:ranges plan) in-time?)
                                    (fn [parts]
                                      (if-let [e (:error parts)]
                                        {:error (unreachable (http/failure e))}
                                        (step (with-parts v parts)
                                              (after-round state need plan
                                                           parts))))))))
         result   (step (view) {:round 0 :room (or max-bytes ##Inf)})]
     #?(:clj  result
        :cljs (js/Promise.resolve result)))))

(defn ^:no-doc read-url!
  "Read the file at `url` with the reader `read` and `opts`, and give the
  result of the reading, or in ClojureScript a promise of it.

  The bytes come by the :fetch of the opts, or by an http/fetcher of the
  opts, which sends with client/send! unless they give a :send. The opts
  also give the limits of the reading, with default-limits for those that
  they don't set. The :error of a file that didn't come names the `url`."
  [url read {:keys [fetch] :as opts}]
  (let [opts  (merge default-limits {:send client/send!} opts)
        fetch (or fetch (http/fetcher url opts))]
    (async/then (read! fetch read opts)
                #(cond-> % (:error %) (assoc-in [:error :url] url)))))

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

  ;; a reader that needs four bytes at two places, over a file of zeros
  (read! (fn [start end]
           {:bytes (bytes/of (repeat (- end start) 0))
            :start start
            :total 4000000})
         (fn [v] (reading #(+ (u32 v 0) (u32 v 3000000)))))
  #_.)
