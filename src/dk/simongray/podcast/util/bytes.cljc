(ns dk.simongray.podcast.util.bytes
  "Bytes on both platforms, for the readers of media files and images.

  Bytes are a byte array on the JVM, and a Uint8Array in JavaScript. The
  functions read them as unsigned numbers, decode them as text, and
  encode them as base64.

  A view holds the parts of a file that are there, each at its offset. A
  reader asks the view for a range with need, or for several ranges with
  need-all, before it reads them. It reads the numbers and the text in
  them with u8, u32 and text.

  A range that the view doesn't hold throws. The missing function gives
  the ranges from the exception, so that the caller can fetch them and
  read again. A range past the end of the file throws too, and truncated?
  tells so. The reading function turns what a reader throws into data.
  The read! function fetches what a reader needs until the reading is
  done, and report gives the diagnostics of a reading."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.time :as time]
            #?(:cljs [goog.crypt.Hmac])
            #?(:cljs [goog.crypt.Sha1])
            #?(:cljs [goog.crypt.Sha256]))
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [java.nio.charset StandardCharsets]
                   [java.security MessageDigest SecureRandom]
                   [java.util Arrays Base64]
                   [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec])))

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
   (defn ^:no-doc binary-string
     "The bytes `b` as a string of one character for each byte, in pieces so
     that a large image never overflows the stack of `apply`."
     [b]
     (let [n (alength b)]
       (apply str (for [i (range 0 n 32768)]
                    (.apply js/String.fromCharCode nil
                            (.subarray b i (min n (+ i 32768)))))))))

(defn hex
  "The bytes `b` in hexadecimal, two characters in lower case for each
  byte."
  [b]
  (let [digits "0123456789abcdef"
        pair   (fn [i]
                 (let [x (at b i)]
                   [(nth digits (bit-shift-right x 4))
                    (nth digits (bit-and x 15))]))]
    (apply str (mapcat pair (range (length b))))))

(defn from-hex
  "The bytes that the hexadecimal text `s` names, in upper or lower case,
  or nil for a text that isn't hexadecimal, or that has a half byte."
  [s]
  (when (and (string? s)
             (even? (count s))
             (re-matches #"[0-9a-fA-F]*" s))
    (let [byte-at (fn [i]
                    (#?(:clj Integer/parseInt :cljs js/parseInt)
                     (subs s i (+ i 2))
                     16))]
      (of (map byte-at (range 0 (count s) 2))))))

(defn sha1
  "The SHA-1 digest of the bytes `b`, as 20 bytes.

  An attacker can make two inputs with one SHA-1 digest. So the digest is
  here only for what asks for it: the UUID of version 5 (RFC 4122, section
  4.3), which the podcast:guid is, and the requests of the Podcast Index."
  [b]
  #?(:clj  (.digest (MessageDigest/getInstance "SHA-1") ^bytes b)
     :cljs (let [h (goog.crypt.Sha1.)]
             (.update h b)
             (js/Uint8Array.from (.digest h)))))

(defn sha256
  "The SHA-256 digest of the bytes `b`, as 32 bytes."
  [b]
  #?(:clj  (.digest (MessageDigest/getInstance "SHA-256") ^bytes b)
     :cljs (let [h (goog.crypt.Sha256.)]
             (.update h b)
             (js/Uint8Array.from (.digest h)))))

(defn join
  "The bytes of each of `bs`, one after another, as new bytes."
  [bs]
  #?(:clj  (let [out (ByteArrayOutputStream.)]
             (doseq [^bytes b bs]
               (.write out b 0 (alength b)))
             (.toByteArray out))
     :cljs (let [out (js/Uint8Array. (reduce + (map #(.-length %) bs)))]
             (reduce (fn [at b] (.set out b at) (+ at (.-length b))) 0 bs)
             out)))

(defn same?
  "Whether the bytes `a` and `b` are the same, in a time that doesn't tell
  where they differ."
  [a b]
  #?(:clj  (MessageDigest/isEqual ^bytes a ^bytes b)
     :cljs (and (= (.-length a) (.-length b))
                (let [diffs (map #(bit-xor (aget a %) (aget b %))
                                 (range (.-length a)))]
                  (zero? (reduce bit-or 0 diffs))))))

(defn hmac-sha256
  "The HMAC of the bytes `message` with the bytes `key`, by SHA-256, as 32
  bytes. RFC 2104 defines the HMAC."
  [key message]
  #?(:clj  (.doFinal (doto (Mac/getInstance "HmacSHA256")
                       (.init (SecretKeySpec. ^bytes key "HmacSHA256")))
                     ^bytes message)
     :cljs (let [hmac (goog.crypt.Hmac. (goog.crypt.Sha256.)
                                        (js/Array.from key)
                                        64)]
             (js/Uint8Array.from (.getHmac hmac (js/Array.from message))))))

(defn generate!
  "Generate `n` bytes that nobody can guess, from the generator of the
  platform for keys."
  [n]
  #?(:clj  (let [b (byte-array n)]
             (.nextBytes (SecureRandom.) b)
             b)
     :cljs (.getRandomValues js/crypto (js/Uint8Array. n))))

(defn base64
  "The bytes `b` in base64, as RFC 4648, section 4, defines it and data URLs
  and SRI use it."
  [b]
  #?(:clj  (.encodeToString (Base64/getEncoder) ^bytes b)
     :cljs (js/btoa (binary-string b))))

(defn from-base64
  "The bytes that the base64 text `s` names, with its padding, as RFC 4648,
  section 4, defines it, or nil for a text that isn't base64."
  [s]
  (when (and (string? s)
             (zero? (mod (count s) 4))
             (re-matches #"[A-Za-z0-9+/]*={0,2}" s))
    (try
      #?(:clj  (.decode (Base64/getDecoder) ^String s)
         :cljs (js/Uint8Array.from (js/atob s) #(.charCodeAt % 0)))
      (catch #?(:clj IllegalArgumentException :cljs :default) _
        nil))))

;; RFC 2397, section 3
(defn data-url
  "The bytes `b` of the MIME type `type` as a data URL, which a browser shows
  as an image source."
  [type b]
  (str "data:" type ";base64," (base64 b)))

(defn decode
  "The text of the bytes `b` from `start` to `end` in `encoding`: :latin1,
  :utf8, :utf16le or :utf16be. A byte order mark stays, and a sequence that
  isn't valid becomes U+FFFD, on both platforms."
  [b start end encoding]
  #?(:clj  (String. ^bytes b (int start) (int (- end start))
                    (case encoding
                      :latin1  StandardCharsets/ISO_8859_1
                      :utf8    StandardCharsets/UTF_8
                      :utf16le StandardCharsets/UTF_16LE
                      :utf16be StandardCharsets/UTF_16BE))
     :cljs (if (= :latin1 encoding)
             (binary-string (.subarray b start end))
             (let [label   (case encoding
                             :utf8    "utf-8"
                             :utf16le "utf-16le"
                             :utf16be "utf-16be")
                   decoder (js/TextDecoder. label #js {:ignoreBOM true})]
               (.decode decoder (.subarray b start end))))))

(defn encode
  "The text `s` as the bytes of its UTF-8, which decode reads back with
  :utf8."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

;; RFC 3629, section 3
(defn utf8?
  "Whether the bytes `b` from `start` to `end` are UTF-8 with one or more
  characters outside ASCII.

  Such bytes are text that a writer meant as UTF-8, also when the writer
  names another encoding."
  [b start end]
  (let [continued? (fn [i n]
                     (every? #(= 0x80 (bit-and (at b (+ i %)) 0xC0))
                             (range 1 (inc n))))]
    (loop [i start wide? false]
      (if (>= i end)
        wide?
        (let [x (at b i)
              ;; how many bytes follow the first of a character, or -1 for
              ;; a byte that starts none
              n (cond
                  (< x 0x80) 0
                  (< x 0xC2) -1
                  (< x 0xE0) 1
                  (< x 0xF0) 2
                  (< x 0xF5) 3
                  :else      -1)]
          (cond
            (neg? n)          false
            (zero? n)         (recur (inc i) wide?)
            (> (+ i n 1) end) false
            (continued? i n)  (recur (+ i n 1) true)
            :else             false))))))

;; WHATWG MIME Sniffing Standard, section 6.1
(def ^:no-doc image-signatures
  "The MIME type of an image by the bytes it begins with, nil standing for
  any byte."
  [["image/jpeg" [0xFF 0xD8 0xFF]]
   ["image/png" [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]]
   ["image/gif" [0x47 0x49 0x46 0x38]]
   ["image/webp" [0x52 0x49 0x46 0x46 nil nil nil nil 0x57 0x45 0x42 0x50]]])

(defn image-type
  "The MIME type of the image whose bytes `b` begin as a JPEG, PNG, GIF or
  WebP image begins, or nil for other bytes."
  [b]
  (some (fn [[type signature]]
          (when (and (>= (length b) (count signature))
                     (every? (fn [[i x]] (or (nil? x) (= x (at b i))))
                             (map-indexed vector signature)))
            type))
        image-signatures))

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
  (let [end      (+ offset (length b))
        covered? (fn [[o x]]
                   (and (<= offset o)
                        (<= (+ o (length x)) end)))]
    (cond-> view
      (pos? (length b)) (update :parts #(conj (into [] (remove covered?) %)
                                              [offset b]))
      total             (assoc :total total))))

(defn ^:no-doc part-at
  "The part of `view` that holds the byte at `p`, as a pair of its offset
  and its bytes."
  [view p]
  (some (fn [[o b]]
          (when (and (<= o p) (< p (+ o (length b))))
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
  and an end, with the end cut at the end of the file. It throws an
  exception that's truncated? when `start` lies past the end of the file."
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
            (recur (+ o (length b)) acc)
            (let [next-part (some->> (:parts view)
                                     (keep (fn [[o _]] (when (< p o end) o)))
                                     seq
                                     (apply min))
                  to        (or next-part end)]
              (recur to (conj acc [p to])))))))))

(defn need-all
  "Nil when `view` holds the bytes of all the `ranges`, each a pair of a
  start and an end as need takes them.

  When the view lacks bytes, it throws an exception from which missing
  gives each range that it lacks. So a caller can fetch them all at one
  time."
  [view ranges]
  (when-some [missing (seq (mapcat (fn [[start end]] (gaps view start end))
                                   ranges))]
    (throw (ex-info (str "Bytes missing from " (ffirst missing))
                    {::missing (vec missing)}))))

(defn need
  "Nil when `view` holds the bytes from `start` to `end`, end excluded.
  The end is cut at the end of the file when its size is known.

  When the view lacks bytes, it throws an exception from which missing
  gives the range that it lacks. When `start` is past the end of the
  file, the exception is truncated?."
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
      (>= start end)                 (of [])
      (<= end (+ o (length b)))      (copy b (- start o) (- end o))
      :else
      (let [n   (- end start)
            out #?(:clj (byte-array n) :cljs (js/Uint8Array. n))]
        (loop [p start]
          (if (< p end)
            (let [[o b] (part-at view p)
                  to    (min end (+ o (length b)))]
              (copy-into! out (- p start) b (- p o) (- to o))
              (recur to))
            out))))))

(defn text
  "The text of the bytes of `view` from `start` to `end` in `encoding`, as
  decode reads it."
  [view start end encoding]
  (let [b (slice view start end)]
    (decode b 0 (length b) encoding)))

(defn code
  "The `n` bytes of `view` at `p` as characters of ISO-8859-1, the form of
  the four-character codes of ID3 frames and MP4 boxes."
  [view p n]
  (text view p (+ p n) :latin1))

(defn report
  "The diagnostics of `result`, which a reader of a view gave. Each
  diagnostic has its message."
  [result]
  (mapv diagnostics/describe (:podcast/report (meta result))))

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
  "The diagnostic of a file that didn't come, since the request for it
  failed with `e`. It has the :url and the :status that `e` holds."
  [e]
  (diagnostics/describe
   (into {:id :file/unreachable :reason (async/explain e)}
         (select-keys (ex-data e) [:url :status]))))

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
  arguments. The report function reads its diagnostics.

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
  "The least that read! fetches at a time, so that small reads come in one
  request."
  (* 64 1024))

(def default-limits
  "The limits of read! that its options don't set.

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
  another, and none once `in-time?` says no, in a future or a promise.

  A part without bytes is left out. When a request fails, the result is a
  map of the :error, and no request goes out after it."
  [fetch ranges in-time?]
  (reduce (fn [parts [start end]]
            (async/then
             parts
             (fn [got]
               (if (or (:error got) (not (in-time?)))
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
  "The `ranges` with the end of each moved to what `wide` gives for the
  range, as far as the bytes that this adds fit in `spare`."
  [ranges wide spare]
  (first (reduce (fn [[acc spare] [start end :as range]]
                   (let [more (min spare (max 0 (- (wide range) end)))]
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
  "The state of read! after a round that fetched the `parts` that `plan`
  asked for, for a reading that needed `need` in the `state` before it."
  [{:keys [round room whole?] :as state} need plan parts]
  {:round    (inc round)
   :previous need
   :room     (- room (reduce + (map (comp length :bytes) parts)))
   :walk     (:walk plan)
   :whole?   (or whole? (some? (some :whole? parts)))})

(defn read!
  "Read the file whose bytes `fetch` gets with the reader `read`, within
  the limits of `opts`. On the JVM it gives what the reader gives, and in
  ClojureScript a promise of it.

  The `read` function takes a view. It gives its result, or the ranges
  that it needs under :need, as reading gives them. The `fetch` function
  takes a start and an end, end excluded. It gives a map of the :bytes,
  :start and :total, or a future or a promise of one, as
  dk.simongray.podcast.util.http/fetcher makes. Its answer is :whole?
  when it holds the file from its start, whatever start was asked. A
  server that ignores ranges gives such an answer.

  The ranges that one reading needs come in one round, in 16 requests at
  most, one after another. Each request asks for 64 KB or more, and ranges
  near each other come in one request. A single range comes with 64 KB
  more, where a reading most often goes next. When a short range starts
  where the last range ended, it comes with twice as much more as the last
  time, up to 1 MB. A walk through the segments of a file asks for such
  ranges.

  The `opts` give the limits, with default-limits for the limits that
  they don't set, and :now, a function that gives the time, the clock by
  default. When a limit stops the reading, or when the ranges didn't
  come, the result is an empty map, and its report has :file/incomplete.
  A reading fetches nothing of ranges that hold more bytes than it can
  still fetch.

  Nothing that `fetch` does throws. When `fetch` fails, the result has a
  :file/unreachable diagnostic under :error."
  ([fetch read]
   (read! fetch read {}))
  ([fetch read opts]
   (let [{:keys [max-bytes max-seconds max-rounds now] :as limits}
         (merge default-limits {:now time/now} opts)
         deadline (when max-seconds (+ (inst-ms (now)) (* 1000 max-seconds)))
         in-time? #(or (nil? deadline) (< (inst-ms (now)) deadline))
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
                                        {:error (unreachable e)}
                                        (step (with-parts v parts)
                                              (after-round state need plan
                                                           parts))))))))
         result   (step (view) {:round 0 :room (or max-bytes ##Inf)})]
     #?(:clj  result
        :cljs (js/Promise.resolve result)))))

(comment
  (def sample
    (of [0x49 0x44 0x33 4 0 0 0 0 0 28]))

  (base64 sample)
  (code (add (view) 0 sample nil) 0 3)
  (decode (encode "Ærø") 0 5 :utf8)

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
           {:bytes (of (repeat (- end start) 0)) :start start :total 4000000})
         (fn [v] (reading #(+ (u32 v 0) (u32 v 3000000)))))
  #_.)
