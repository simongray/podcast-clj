(ns ^:no-doc dk.simongray.podcast.format.media.ogg.impl
  "The pages, packets, headers and comments of an Ogg file of Opus or
  Vorbis, and its duration, chapters and artwork, which
  format.media.ogg/read assembles."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.image.impl :as image.impl]
            [dk.simongray.podcast.format.media.ogg :as-alias ogg]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.webcrypto.bytes :as bytes]))

;; RFC 3533, section 6: the CRC of a page has the generator polynomial
;; 0x04c11db7, unreflected, from zero
(def crc-table
  "The CRC of each byte, for checksum."
  (vec (for [i (range 256)]
         (reduce (fn [c _]
                   (bit-and 0xFFFFFFFF
                            (if (bit-test c 31)
                              (bit-xor (bit-shift-left c 1) 0x04C11DB7)
                              (bit-shift-left c 1))))
                 (bit-shift-left i 24)
                 (range 8)))))

(defn- checksum
  "The CRC of the page whose bytes are `b`, with its own CRC as zeros."
  [b]
  (let [n (bytes/length b)]
    (loop [i 0 c 0]
      (if (< i n)
        (let [x (if (<= 22 i 25) 0 (bytes/at b i))
              t (crc-table (bit-xor (unsigned-bit-shift-right c 24) x))]
          (recur (inc i) (bit-and 0xFFFFFFFF (bit-xor (bit-shift-left c 8) t))))
        (unsigned-bit-shift-right c 0)))))

(defn- granule
  "The granule position of the eight little-endian bytes at `p` in `view`,
  or nil for -1, which a page gives when no packet ends on it."
  [view p]
  (let [low  (view/u32le view p)
        high (view/u32le view (+ p 4))]
    (cond
      (= 0xFFFFFFFF low high) nil
      (< high 0x80000000)     (+ (* 4294967296 high) low)
      :else                   (+ (* 4294967296 (- high 4294967296)) low))))

(defn- lacing
  "The lacing values of the page at `p` in `view`, its segment table."
  [view p]
  (let [n (view/u8 view (+ p 26))]
    (view/need view p (+ p 27 n))
    (mapv #(view/u8 view (+ p 27 %)) (range n))))

;; RFC 3533, section 6
(defn page
  "The Ogg page at `p` in `view`, or nil when no page begins there or its
  CRC doesn't match.

  The page is a map of its :start and :end, where its :body begins, its
  :lacing values, its :flags, its :granule position, nil for -1, and the
  :serial number of its stream."
  [view p]
  (view/need view p (+ p 27))
  (when (and (= "OggS" (view/code view p 4))
             (zero? (view/u8 view (+ p 4))))
    (let [lacing (lacing view p)
          body   (+ p 27 (count lacing))
          end    (+ body (reduce + lacing))]
      (when (= (view/u32le view (+ p 22))
               (checksum (view/slice view p end)))
        {:start   p
         :end     end
         :body    body
         :lacing  lacing
         :flags   (view/u8 view (+ p 5))
         :granule (granule view (+ p 6))
         :serial  (view/u32le view (+ p 14))}))))

;; RFC 3533, section 5: a lacing value under 255 ends a packet, and a page
;; whose flag 0x01 is set goes on with the packet of the page before
(defn- packets-of
  "The packets that end on the `page`, after the `pending` ranges of a
  packet that the pages before it began, as a pair of the packets, each a
  vector of ranges, and the ranges of the packet that goes on after it."
  [{:keys [body lacing flags] :as page} pending]
  (loop [[n & more :as ns] (seq lacing)
         q       body
         packets []
         parts   (cond (not (bit-test flags 0)) []
                       pending                  pending
                       :else                    ::ogg/headless)]
    (if ns
      (let [parts (cond-> parts (vector? parts) (conj [q (+ q n)]))
            end?  (< n 255)]
        (recur more
               (+ q n)
               (cond-> packets (and end? (vector? parts)) (conj parts))
               (if end? [] parts)))
      [packets (when (vector? parts) (not-empty parts))])))

(defn- stream-pages
  "The pages of the stream `serial` in `view` from the page at `p` on, each
  with the :packets that end on it, lazily. The pages of other streams are
  skipped."
  [view p serial]
  (letfn [(from [p pending]
            (lazy-seq
             (when (< p (or (:total view) ##Inf))
               (let [pg (or (page view p)
                            (throw (ex-info (str "No Ogg page at " p) {})))]
                 (if (= serial (:serial pg))
                   (let [[packets pending] (packets-of pg pending)]
                     (cons (assoc pg :packets packets)
                           (from (:end pg) pending)))
                   (from (:end pg) pending))))))]
    (from p nil)))

(defn- packet-bytes
  "The bytes of the `packet` in `view`, whose ranges are its parts."
  [view packet]
  (apply bytes/concat (map (fn [[start end]] (view/slice view start end))
                           packet)))

;; RFC 3533, section 4: the first pages of a file begin its streams
(defn first-pages
  "The pages at the start of the file in `view` that begin its streams."
  [view]
  (loop [p 0 acc []]
    (let [pg (when (< p (or (:total view) ##Inf))
               (page view p))]
      (if (and pg (bit-test (:flags pg) 1))
        (recur (:end pg) (conj acc pg))
        acc))))

;; RFC 7845, section 5.1, and Vorbis I, section 4.2.1
(defn codec
  "The codec of the stream that the first packet of `pg` in `view` begins,
  :opus or :vorbis, or nil for another."
  [view pg]
  (when-let [[[start end]] (ffirst (packets-of pg nil))]
    (cond
      (and (>= (- end start) 8)
           (= "OpusHead" (view/code view start 8)))
      :opus

      (and (>= (- end start) 7)
           (= 1 (view/u8 view start))
           (= "vorbis" (view/code view (inc start) 6)))
      :vorbis)))

(defn- malformed
  "The exception of a `header` that doesn't read."
  [header]
  (ex-info (str "The " header " header doesn't read") {}))

;; RFC 7845, section 5.1: a version up to 15 is one that a reader of
;; version 1 reads
(defn- opus-head
  "The pre-skip in samples and the channels of the Opus identification
  header `b`, which plays at 48 kHz."
  [b]
  (let [v (view/whole b)]
    (when (or (< (bytes/length b) 19)
              (> (view/u8 v 8) 15)
              (zero? (view/u8 v 9)))
      (throw (malformed "OpusHead")))
    {:pre-skip    (view/u16le v 10)
     :channels    (view/u8 v 9)
     :sample-rate 48000}))

;; Vorbis I, section 4.2.2
(defn- vorbis-head
  "The sample rate, the channels and the long block of the Vorbis
  identification header `b`, the most samples that a packet can add."
  [b]
  (let [v      (view/whole b)
        long?  (>= (bytes/length b) 30)
        rate   (when long? (view/u32le v 12))
        blocks (when long? (view/u8 v 28))
        short  (some-> blocks (bit-and 0x0F))
        long   (some-> blocks (bit-shift-right 4))]
    (when-not (and long?
                   (zero? (view/u32le v 7))
                   (pos? (view/u8 v 11))
                   (pos? rate)
                   (<= 6 short long 13)
                   (bit-test (view/u8 v 29) 0))
      (throw (malformed "Vorbis identification")))
    {:sample-rate rate
     :channels    (view/u8 v 11)
     :long-block  (bit-shift-left 1 long)}))

;; Vorbis I, section 5.2, and RFC 7845, section 5.2: field names are
;; ASCII, and case doesn't matter
(defn- comments
  "The comments of the comment header `b` of `codec`, as pairs of the name
  in upper case and the value."
  [b codec]
  (let [v     (view/whole b)
        start (if (= :opus codec) 8 7)
        magic (if (= :opus codec)
                (= "OpusTags" (view/code v 0 8))
                (and (= 3 (view/u8 v 0)) (= "vorbis" (view/code v 1 6))))
        at    (+ start 4 (view/u32le v start))]
    (when-not magic
      (throw (malformed "comment")))
    (loop [q (+ at 4) n (view/u32le v at) acc []]
      (if (pos? n)
        (let [end (+ q 4 (view/u32le v q))
              s   (view/text v (+ q 4) end :utf8)
              i   (str/index-of s "=")]
          (recur end
                 (dec n)
                 (cond-> acc
                   i (conj [(str/upper-case (subs s 0 i)) (subs s (inc i))]))))
        acc))))

(defn- first-values
  "The value of the first comment of each name in `comments`."
  [comments]
  (reduce (fn [m [k v]] (if (contains? m k) m (assoc m k v))) {} comments))

;; Vorbis I, section 5.2.2: the field names that it proposes
(defn named
  "The tags of the `comments` of a stream as one map of :title, :artist,
  :album, :date, :genre and :description, each absent when the comments
  don't have it. The description is the comment when it has none."
  [comments]
  (let [values (first-values comments)
        text   #(some-> (some values %&) str/trim not-empty)
        named  {:title       (text "TITLE")
                :artist      (text "ARTIST")
                :album       (text "ALBUM")
                :date        (text "DATE")
                :genre       (text "GENRE")
                :description (text "DESCRIPTION" "COMMENT")}]
    (into {} (filter val) named)))

;; Xiph's chapter extension of VorbisComment, revision 15985 of 2015: a
;; chapter is CHAPTERxxx, its start as HH:MM:SS.SSS, with CHAPTERxxxNAME
;; and CHAPTERxxxURL
(defn- chapters
  "The chapters of the `comments`, read in the context `ctx`, in the order
  of their numbers."
  [ctx comments]
  (let [values  (first-values comments)
        numbers (->> comments
                     (keep #(second (re-matches #"CHAPTER(\d{3})" (first %))))
                     distinct
                     sort)]
    (into []
          (keep (fn [n]
                  (let [k     (str "CHAPTER" n)
                        start (time/parse-clock (values k))
                        title (values (str k "NAME"))
                        url   (values (str k "URL"))]
                    (if start
                      (cond-> {:start-time start}
                        title (assoc :title title)
                        url   (assoc :url url))
                      (do (view/note! ctx (view/diagnostic :ogg/chapter-start
                                                           :warning
                                                           :name k
                                                           :value (values k)))
                          nil)))))
          numbers)))

(defn- image-type
  "The MIME type of a picture, as `declared` in its block, or by the quirk
  from the image `data`, read in the context `ctx`."
  [ctx declared data]
  (if-let [sniffed (and (image.impl/loose? declared)
                        (view/quirk? ctx :ogg/image-type)
                        (image/sniff data))]
    (do (view/note! ctx (view/diagnostic :ogg/image-type :info
                                         :declared declared))
        sniffed)
    (str/lower-case (str/trim declared))))

;; RFC 9639, section 8.8: a picture block, which a METADATA_BLOCK_PICTURE
;; comment holds in base64, as Xiph's VorbisComment page of 2016 has it
(defn- picture
  "The picture of the comment value `s`, read in the context `ctx`, as a
  map of :picture-type, :type, :description and :data, or nil when it
  links to a picture."
  [ctx s]
  (let [b         (or (bytes/from-base64 s)
                      (throw (malformed "METADATA_BLOCK_PICTURE")))
        v         (view/whole b)
        type-end  (+ 8 (view/u32 v 4))
        declared  (view/text v 8 type-end :latin1)
        text-end  (+ type-end 4 (view/u32 v type-end))
        data-from (+ text-end 20)
        data-end  (+ data-from (view/u32 v (+ text-end 16)))
        data      (view/slice v data-from data-end)]
    (when-not (= "-->" declared)
      {:picture-type (view/u32 v 0)
       :type         (image-type ctx declared data)
       :description  (view/text v (+ type-end 4) text-end :utf8)
       :data         data})))

(defn- artwork
  "The picture of the `comments`, read in the context `ctx`, as a map of
  :type, :data and :description, or nil when they hold none. The front
  cover comes before each other picture."
  [ctx comments]
  (let [pictures (->> comments
                      (keep (fn [[k v]]
                              (when (= "METADATA_BLOCK_PICTURE" k)
                                (picture ctx v))))
                      vec)
        front    (first (filter #(= 3 (:picture-type %)) pictures))]
    (some-> (or front (first pictures))
            (select-keys [:type :data :description]))))

(def max-page
  "The size of the largest Ogg page, RFC 3533, section 6, so the last page
  of a file begins within as many bytes of its end."
  65307)

(defn- last-granule
  "The granule position of the last page of the stream `serial` that ends
  a packet, among the last max-page bytes of `view`, or nil when they hold
  none or the size of the file isn't known."
  [view serial]
  (when-let [total (:total view)]
    (let [from  (max 0 (- total max-page))
          page? #(try (page view %)
                      (catch #?(:clj Exception :cljs :default) e
                        (when (view/missing e) (throw e))
                        nil))]
      (view/need view from total)
      (loop [q (- total 27)]
        (when (>= q from)
          (let [pg (when (= 0x4F (view/u8 view q))
                     (page? q))]
            (if (and pg (= serial (:serial pg)) (:granule pg))
              (:granule pg)
              (recur (dec q)))))))))

;; RFC 6716, section 3.1: the TOC byte gives the size of the frames in a
;; packet, here in samples at 48 kHz, and their number
(defn- opus-samples
  "The number of samples of the Opus packet whose bytes are `b`."
  [b]
  (when (zero? (bytes/length b))
    (throw (malformed "Opus packet")))
  (let [toc    (bytes/at b 0)
        config (bit-shift-right toc 3)
        size   (cond
                 (< config 12) ([480 960 1920 2880] (bit-and config 3))
                 (< config 16) ([480 960] (bit-and config 1))
                 :else         ([120 240 480 960] (bit-and config 3)))
        frames (case (bit-and toc 3)
                 0 1
                 (1 2) 2
                 3 (if (> (bytes/length b) 1)
                     (bit-and (bytes/at b 1) 0x3F)
                     (throw (malformed "Opus packet"))))]
    (* size frames)))

;; RFC 7845, section 4.5: a stream whose first page of audio ends before
;; the samples of its packets is invalid, unless the page ends the stream.
;; Vorbis I, section A.2: when its audio starts after zero, the first page
;; of audio holds two packets.
(defn- start
  "The granule position where the audio starts that `first-page` begins,
  in `view`, with its `packets` of the stream of `codec` and its `head`."
  [view codec head {:keys [granule flags] :as first-page} packets]
  (let [samples (if (= :opus codec)
                  (reduce + (map #(opus-samples (packet-bytes view %)) packets))
                  (* (:long-block head) (/ (dec (count packets)) 2)))
        start   (- granule samples)]
    (cond
      (or (= :vorbis codec) (bit-test flags 2)) (max 0 start)
      (neg? start) (throw (ex-info "The first page of audio ends too soon" {}))
      :else start)))

;; RFC 7845, section 4.3, and Vorbis I, section A.2: a granule position
;; counts the samples per channel, at 48 kHz for Opus
(defn- duration
  "The duration in seconds of the stream `serial` of `codec` in `view`,
  whose identification header is `head`, and whose `audio` packets come
  as pairs of the page they end on and the packet, read in the context
  `ctx`."
  [ctx view codec serial head audio]
  (let [[first-page packet] (first audio)
        on-first            (drop-while #(not= packet %) (:packets first-page))
        end                 (when first-page
                              (last-granule view serial))
        rate                (if (= :opus codec) 48000 (:sample-rate head))]
    (cond
      (nil? first-page)
      (view/note! ctx (view/diagnostic :ogg/no-audio :warning))

      (nil? end)
      (view/note! ctx (view/diagnostic :ogg/no-end :info))

      :else
      (let [samples (- end
                       (start view codec head first-page on-first)
                       (:pre-skip head 0))]
        (when (neg? samples)
          (throw (ex-info "The last page of audio ends before its start" {})))
        (/ samples (double rate))))))

(defn read-stream
  "What the stream of `codec` that the page `bos` begins in `view` says
  about the file, read in the context `ctx`."
  [ctx view codec {:keys [serial] :as bos}]
  (let [n        (if (= :opus codec) 2 3)
        packets  (for [pg     (stream-pages view (:start bos) serial)
                       packet (:packets pg)]
                   [pg packet])
        headers  (vec (take n packets))
        _        (when (< (count headers) n)
                   (throw (malformed "missing")))
        head     ((if (= :opus codec) opus-head vorbis-head)
                  (packet-bytes view (second (headers 0))))
        tags     (comments (packet-bytes view (second (headers 1))) codec)
        seconds  (view/part :duration
                            #(duration ctx view codec serial head
                                       (drop n packets)))
        chs      (view/part :chapters #(chapters ctx tags))
        art      (view/part :artwork #(artwork ctx tags))
        duration (:value seconds)]
    (view/reported
     (cond-> {:comments    tags
              :tags        (named tags)
              :codecs      (name codec)
              :sample-rate (:sample-rate head)
              :channels    (:channels head)
              :media-start (:end (first (peek headers)))}
       (number? duration)     (assoc :duration duration)
       (seq (:value chs))     (assoc :chapters (:value chs))
       (:value art)           (assoc :artwork (:value art)))
     (into (view/noted ctx) (mapcat :report) [seconds chs art]))))
