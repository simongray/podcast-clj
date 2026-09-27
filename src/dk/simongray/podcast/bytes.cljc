(ns dk.simongray.podcast.bytes
  "Bytes on both platforms, for the readers of media files and images: a
  byte array on the JVM and a Uint8Array in JavaScript, read as unsigned
  numbers, decoded as text and encoded as base64.

  A view holds the parts of a file that have arrived, each at its offset.
  A reader asks it for a range with `need`, or for several with
  `need-all`, before it reads them, and for the numbers and the text in
  them with `u8`, `u32` and `text`. A range that the view does not hold
  throws, and `missing` gives the ranges from the exception, so that the
  caller can fetch them and read again. A range past the end of the file
  throws too, and `truncated?` says so. `reading` turns what a reader
  throws into data, and `read!` fetches what it needs until it is done."
  (:require [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.util Arrays Base64])))

(defn length
  "The number of bytes in `b`."
  [b]
  #?(:clj  (alength ^bytes b)
     :cljs (.-length b)))

(defn of
  "The bytes `xs`, numbers from 0 to 255, as the platform keeps bytes."
  [xs]
  #?(:clj  (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (into-array xs))))

(defn at
  "The byte at `i` in the bytes `b`, from 0 to 255."
  [b i]
  #?(:clj  (bit-and (aget ^bytes b (int i)) 0xff)
     :cljs (aget b i)))

(defn copy
  "The bytes of `b` from `start` to `end`, end excluded, as new bytes."
  [b start end]
  #?(:clj  (Arrays/copyOfRange ^bytes b (int start) (int end))
     :cljs (.slice b start end)))

#?(:cljs
   (defn- binary-string
     "The bytes `b` as a string of one character for each byte, in pieces so
     that a large image never overflows the stack of `apply`."
     [b]
     (apply str (for [i (range 0 (alength b) 32768)]
                  (.apply js/String.fromCharCode nil (.subarray b i (min (alength b) (+ i 32768))))))))

(defn base64
  "The bytes `b` in base64, as RFC 4648 defines it and data URLs and SRI use
  it."
  [b]
  #?(:clj  (.encodeToString (Base64/getEncoder) ^bytes b)
     :cljs (js/btoa (binary-string b))))

(defn data-url
  "The bytes `b` of the MIME type `type` as a data URL, which a browser shows
  as an image source."
  [type b]
  (str "data:" type ";base64," (base64 b)))

(defn decode
  "The text of the bytes `b` from `start` to `end` in `encoding`: :latin1,
  :utf8, :utf16le or :utf16be. A byte order mark stays, and a sequence that
  is not valid becomes U+FFFD, on both platforms."
  [b start end encoding]
  #?(:clj  (String. ^bytes b (int start) (int (- end start))
                    (case encoding
                      :latin1  StandardCharsets/ISO_8859_1
                      :utf8    StandardCharsets/UTF_8
                      :utf16le StandardCharsets/UTF_16LE
                      :utf16be StandardCharsets/UTF_16BE))
     :cljs (if (= :latin1 encoding)
             (binary-string (.subarray b start end))
             (.decode (js/TextDecoder. (case encoding :utf8 "utf-8" :utf16le "utf-16le" :utf16be "utf-16be")
                                       #js {:ignoreBOM true})
                      (.subarray b start end)))))

(defn utf8?
  "Whether the bytes `b` from `start` to `end` are UTF-8 with at least one
  character outside ASCII: text that a writer meant as UTF-8 when it says
  another encoding."
  [b start end]
  (loop [i start wide? false]
    (if (>= i end)
      wide?
      (let [x (at b i)
            n (cond (< x 0x80) 0 (< x 0xC2) -1 (< x 0xE0) 1 (< x 0xF0) 2 (< x 0xF5) 3 :else -1)]
        (cond
          (neg? n)          false
          (zero? n)         (recur (inc i) wide?)
          (> (+ i n 1) end) false
          (every? #(= 0x80 (bit-and (at b (+ i %)) 0xC0)) (range 1 (inc n)))
          (recur (+ i n 1) true)
          :else             false)))))

(def ^:no-doc image-signatures
  "The MIME type of an image by the bytes it begins with, nil standing for
  any byte."
  [["image/jpeg" [0xFF 0xD8 0xFF]]
   ["image/png" [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]]
   ["image/gif" [0x47 0x49 0x46 0x38]]
   ["image/webp" [0x52 0x49 0x46 0x46 nil nil nil nil 0x57 0x45 0x42 0x50]]])

(defn image-type
  "The MIME type of the image whose bytes `b` begin as a JPEG, PNG, GIF or
  WebP image begins; nil otherwise."
  [b]
  (some (fn [[type signature]]
          (when (and (>= (length b) (count signature))
                     (every? (fn [[i x]] (or (nil? x) (= x (at b i)))) (map-indexed vector signature)))
            type))
        image-signatures))

(defn view
  "A view of a file of `total` bytes, nil when that is not known yet,
  holding no part of it."
  ([]
   (view nil))
  ([total]
   {:parts [] :total total}))

(defn add
  "`view` with the bytes `b` that begin at `offset` in the file, in place
  of the parts that they cover, and the `total` size of the file when it is
  known."
  [view offset b total]
  (let [end (+ offset (length b))]
    (cond-> view
      (pos? (length b)) (update :parts (fn [parts]
                                         (conj (into [] (remove (fn [[o x]] (and (<= offset o) (<= (+ o (length x)) end))))
                                                     parts)
                                               [offset b])))
      total             (assoc :total total))))

(defn- part-at
  "The part `[offset bytes]` of `view` that holds the byte at `p`."
  [view p]
  (some (fn [[o b]] (when (and (<= o p) (< p (+ o (length b)))) [o b])) (:parts view)))

(defn- past-end
  "The exception of a read at `p`, past the end of the file."
  [p]
  (ex-info (str "Past the end of the file: " p) {::truncated true}))

(defn missing
  "The ranges `[[start end] ...]` that a read of a view missed, when `e` is
  the exception it threw, and nil otherwise."
  [e]
  (::missing (ex-data e)))

(defn truncated?
  "Whether `e` is the exception a read past the end of the file threw."
  [e]
  (boolean (::truncated (ex-data e))))

(defn- gaps
  "The ranges from `start` to `end` that `view` lacks, `[[from to] ...]`,
  the end cut at the end of the file. Throws `truncated?` when `start` lies
  past the end of the file."
  [view start end]
  (let [total (:total view)]
    (when (and total (< start end) (>= start total))
      (throw (past-end start)))
    (let [end (if total (min end total) end)]
      (loop [p start acc []]
        (if (>= p end)
          acc
          (if-let [[o b] (part-at view p)]
            (recur (+ o (length b)) acc)
            (let [to (or (some->> (:parts view) (keep (fn [[o _]] (when (< p o end) o))) seq (apply min)) end)]
              (recur to (conj acc [p to])))))))))

(defn need-all
  "Return nil when `view` holds the bytes of all the `ranges`, each `[start
  end]` as `need` takes them; throw `missing` with every range that it
  lacks otherwise, so that a caller can fetch them all at once."
  [view ranges]
  (when-some [missing (seq (mapcat (fn [[start end]] (gaps view start end)) ranges))]
    (throw (ex-info (str "Bytes missing from " (ffirst missing)) {::missing (vec missing)}))))

(defn need
  "Return nil when `view` holds the bytes from `start` to `end`, end
  excluded, the end cut at the end of the file when its size is known;
  throw `missing` with the range that it lacks otherwise, and `truncated?`
  when `start` lies past the end of the file."
  [view start end]
  (need-all view [[start end]]))

(defn u8
  "The byte at `p` in `view`, from 0 to 255."
  [view p]
  (if-let [[o b] (part-at view p)]
    (at b (- p o))
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
  JavaScript."
  [view p]
  (+ (* 4294967296 (u32 view p)) (u32 view (+ p 4))))

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
      (>= start end)                 (of [])
      (<= end (+ o (length b)))      (copy b (- start o) (- end o))
      :else
      (let [out #?(:clj (byte-array (- end start)) :cljs (js/Uint8Array. (- end start)))]
        (loop [p start]
          (if (< p end)
            (let [[o b] (part-at view p)
                  to    (min end (+ o (length b)))]
              (copy-into! out (- p start) b (- p o) (- to o))
              (recur to))
            out))))))

(defn text
  "The text of the bytes of `view` from `start` to `end` in `encoding`, as
  `decode` reads it."
  [view start end encoding]
  (let [b (slice view start end)]
    (decode b 0 (length b) encoding)))

(defn code
  "The `n` bytes of `view` at `p` as characters of ISO-8859-1, the form of
  the four-character codes of ID3 frames and MP4 boxes."
  [view p n]
  (text view p (+ p n) :latin1))

(defn reading
  "What `(f)`, a reading of a view, gives: its result; `{:need [[start end]
  ...]}`, the ranges that the view lacked, end excluded; or a report of
  `:file/truncated` when the reading went past the end of the file, and of
  `:file/malformed` when it failed otherwise, on the JVM also by
  overflowing the stack. Every diagnostic in the report of the result has
  its message."
  [f]
  (let [failed #(cond
                  (missing %)    {:need (missing %)}
                  (truncated? %) {:report [{:id :file/truncated :level :warning}]}
                  :else          {:report [{:id :file/malformed :level :warning :reason (async/explain %)}]})
        result (try
                 (f)
                 (catch #?(:clj Exception :cljs :default) e
                   (failed e))
                 #?(:clj (catch StackOverflowError e
                           (failed e))))]
    (cond-> result
      (:report result) (update :report #(mapv diagnostics/describe %)))))

(def ^:no-doc chunk-size
  "The least that `read!` fetches at a time, so that small reads come in
  one request."
  (* 64 1024))

(def ^:no-doc max-rounds
  "How many times `read!` fetches before it gives up."
  8)

(def ^:no-doc max-requests
  "How many requests `read!` sends in one round at most; the ranges that a
  reading needs beyond them come in the rounds after."
  16)

(defn- spans
  "The `ranges` that a reading needs, `[[start end] ...]`, in order, and
  those less than `chunk-size` apart joined, so that ranges near each
  other come in one request."
  [ranges]
  (reduce (fn [acc [start end]]
            (let [[s e] (peek acc)]
              (if (and e (< (- start e) chunk-size))
                (conj (pop acc) [s (max e end)])
                (conj acc [start end]))))
          []
          (sort ranges)))

(defn- fetch-all
  "The parts that `fetch` gives of the `ranges`, one request after another;
  deferred."
  [fetch ranges]
  (reduce (fn [parts [start end]]
            (async/then parts (fn [got] (async/then (fetch start end) #(conj got %)))))
          []
          ranges))

(defn read!
  "What the reader `read` gives of the file whose bytes `fetch` gets.
  `read` is a function of a view that gives its result, or `{:need
  [[start end] ...]}` as `reading` gives it; `fetch` is a function of a
  start and an end, end excluded, that gives `{:bytes :start :total}` or a
  deferred one, as `dk.simongray.podcast.http/fetcher` makes.

  The ranges that one reading needs come in one round, in 16 requests at
  most, one after another, each of 64 KB at least, and ranges near each
  other in one request. A single range comes with 64 KB more, where a
  reading most often goes next, and with twice as much more as the last
  time, up to 1 MB, when it is shorter than that and starts where the last
  ended, as a walk through the segments of a file asks. On the JVM the
  result, in ClojureScript a promise of it; ranges that have not come
  after 8 rounds give a report of `:file/incomplete`."
  [fetch read]
  (letfn [(step [v round previous [until ahead]]
            (let [{:keys [need] :as result} (read v)]
              (cond
                (nil? need)
                result

                (or (>= round max-rounds) (= need previous))
                {:report [(diagnostics/describe {:id :file/incomplete :level :warning :ranges need})]}

                :else
                (let [batch       (vec (take max-requests (spans need)))
                      [start end] (first batch)
                      ahead       (if (and until (< (- start until) chunk-size) (< (- end start) chunk-size))
                                    (min (* 2 ahead) (* 16 chunk-size))
                                    chunk-size)
                      ranges      (if (next batch)
                                    (mapv (fn [[s e]] [s (max e (+ s chunk-size))]) batch)
                                    [[start (+ end ahead)]])]
                  (async/then (fetch-all fetch ranges)
                              (fn [parts]
                                (step (reduce (fn [v {b :bytes :keys [start total]}]
                                                (cond-> v b (add start b total)))
                                              v parts)
                                      (inc round)
                                      need
                                      (when-not (next batch) [(second (first ranges)) ahead]))))))))]
    #?(:clj  (step (view) 0 nil nil)
       :cljs (js/Promise.resolve (step (view) 0 nil nil)))))
