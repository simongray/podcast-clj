(ns dk.simongray.podcast.util.bytes.ranges
  "The reading of a file by the ranges of its bytes that a reader needs,
  fetched in rounds within limits, for the readers of views.

  A reader of a view gives the ranges that it lacks, and read! fetches
  them and reads again, until the reader has what it needs. The bytes come
  from a fetch function: an http/fetcher for a file on the web, or a
  fetcher of a local file or a Blob."
  (:require #?(:clj [clojure.java.io :as io])
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async])
  #?(:clj (:import [java.nio ByteBuffer]
                   [java.nio.channels FileChannel]
                   [java.nio.file OpenOption StandardOpenOption])))

#?(:clj
   (defn file-fetcher
     "A function that fetches the bytes of the local file `f`, a path or a
     java.io.File, by range, as read! takes it, e.g. as the :fetch of
     format.media/read! for a downloaded episode."
     [f]
     (fn [start end]
       (with-open [channel (FileChannel/open (.toPath (io/file f))
                                             (into-array OpenOption
                                                         [StandardOpenOption/READ]))]
         (let [total  (.size channel)
               buffer (ByteBuffer/allocate (int (max 0 (- (min end total) start))))]
           (.position channel (long start))
           (while (and (.hasRemaining buffer) (pos? (.read channel buffer))))
           {:bytes (.array buffer) :start start :total total})))))

;; File API, the slice and arrayBuffer methods of a Blob, which a browser's
;; File is, and which Node's fs.openAsBlob gives of a file
#?(:cljs
   (defn blob-fetcher
     "A function that fetches the bytes of the Blob `blob` by range, as read!
     takes it, e.g. a File that the listener picked, as the :fetch of
     format.media/read!."
     [^js blob]
     (fn [start end]
       (.then (.arrayBuffer (.slice blob start end))
              (fn [buffer]
                {:bytes (js/Uint8Array. buffer)
                 :start start
                 :total (.-size blob)})))))

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
            (view/add v start b total))
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
                        (view/reported {} [{:id     :file/incomplete
                                            :level  :warning
                                            :ranges need}])

                        :else
                        (async/then (fetch-all fetch (:ranges plan) in-time?)
                                    (fn [parts]
                                      (if-let [e (:error parts)]
                                        {:error (diagnostics/file-failure
                                                 (http/failure e))}
                                        (step (with-parts v parts)
                                              (after-round state need plan
                                                           parts))))))))
         result   (step (view/view) {:round 0 :room (or max-bytes ##Inf)})]
     #?(:clj  result
        :cljs (js/Promise.resolve result)))))

(defn ^:no-doc read-url!
  "Read the file at `url` with the reader `read` and `opts`, and give the
  result of the reading, or in ClojureScript a promise of it.

  The bytes come by the :fetch of the opts, or by an http/fetcher of the
  opts, which sends with client/send! unless they give a :send. The opts
  also give the limits of the reading, with default-limits for those that
  they don't set. The :error of a file that didn't come names the `url`,
  with its secrets masked."
  [url read {:keys [fetch] :as opts}]
  (let [opts  (merge default-limits {:send client/send!} opts)
        fetch (or fetch (http/fetcher url opts))
        named #(diagnostics/describe (assoc % :url url))]
    (async/then (read! fetch read opts)
                #(cond-> % (:error %) (update :error named)))))

(comment
  ;; a reader that needs four bytes at two places, over a file of zeros
  (read! (fn [start end]
           {:bytes (bytes/of (repeat (- end start) 0))
            :start start
            :total 4000000})
         (fn [v] (view/reading #(+ (view/u32 v 0) (view/u32 v 3000000)))))
  #_.)
