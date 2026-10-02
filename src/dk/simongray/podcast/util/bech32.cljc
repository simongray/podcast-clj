(ns dk.simongray.podcast.util.bech32
  "Bech32, the encoding of BIP-173, on both platforms.

  A Bech32 text has a prefix that a person reads, the separator 1, and
  data in an alphabet of 32 characters. The last six characters of the
  data are a checksum. The names of Nostr are Bech32, as NIP-19 defines
  them.

  The decode function reads a text and encode writes one. The data are
  bytes, and the section Segwit address format of BIP-173 tells how eight
  bits go into characters of five bits. For data that aren't whole bytes,
  words and of-words read and write the data as numbers of five bits.
  Bech32m, the checksum of BIP-350, isn't here."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]))

(def ^:no-doc charset
  "The 32 characters of the data, in the order of their values."
  "qpzry9x8gf2tvdw0s3jn54khce6mua7l")

(def ^:no-doc values
  "The value of each character of charset."
  (zipmap charset (range)))

(def ^:no-doc generators
  "The five generators of the checksum of BIP-173."
  [0x3b6a57b2 0x26508e6d 0x1ea119fa 0x3d4233dd 0x2a1462b3])

(def max-length
  "The most characters of a text that decode reads, which is the limit of
  BIP-173. The option :max-length gives another limit."
  90)

(defn- polymod
  "The checksum of `xs`, numbers of five bits, as the function
  bech32_polymod of BIP-173 computes it."
  [xs]
  (reduce (fn [sum v]
            (let [top (bit-shift-right sum 25)
                  sum (bit-xor (bit-shift-left (bit-and sum 0x1ffffff) 5) v)]
              (reduce (fn [sum i] (if (bit-test top i) (bit-xor sum (nth generators i)) sum))
                      sum
                      (range 5))))
          1
          xs))

(defn- expanded
  "The values of the prefix `prefix` that go into the checksum."
  [prefix]
  (let [codes (map #?(:clj int :cljs #(.charCodeAt % 0)) prefix)]
    (concat (map #(bit-shift-right % 5) codes) [0] (map #(bit-and % 31) codes))))

(defn- regrouped
  "The numbers `xs` of `from` bits each as numbers of `to` bits each. With
  `pad?`, the bits that are left over make one more number. Without it,
  they must be fewer than `from` and all zero, or the result is nil."
  [xs from to pad?]
  (let [mask (dec (bit-shift-left 1 to))]
    (loop [xs xs acc 0 bits 0 out []]
      (if-let [[x & more] (seq xs)]
        (let [acc  (bit-or (bit-shift-left acc from) x)
              bits (+ bits from)
              n    (quot bits to)
              out  (into out (map #(bit-and (bit-shift-right acc (- bits (* to (inc %)))) mask)) (range n))
              bits (- bits (* n to))]
          (recur more (bit-and acc (dec (bit-shift-left 1 bits))) bits out))
        (cond
          (and pad? (pos? bits))           (conj out (bit-and (bit-shift-left acc (- to bits)) mask))
          pad?                             out
          (and (< bits from) (zero? acc))  out)))))

(defn words
  "The prefix and the data of the Bech32 text `s`, read with `opts`, as a
  map of :prefix and :words. It's nil for a text that isn't Bech32, or
  that has a wrong checksum.

  The data are numbers of five bits, one for each character, without the
  checksum, and the prefix is in lower case. The :max-length of `opts` is
  the most characters of the text, in place of max-length. The data of a
  BOLT 11 invoice aren't whole bytes, so a reader of invoices takes the
  numbers."
  ([s]
   (words s {}))
  ([s opts]
   {:pre [(map? opts)]}
   (when (and (string? s)
              (<= 8 (count s) (:max-length opts max-length))
              (or (= s (str/lower-case s)) (= s (str/upper-case s))))
     (let [s      (str/lower-case s)
           split  (str/last-index-of s "1")
           prefix (when split (subs s 0 split))
           data   (when split (map values (subs s (inc split))))]
       (when (and (seq prefix)
                  (re-matches #"[\x21-\x7e]+" prefix)
                  (<= 6 (count data))
                  (every? some? data)
                  (= 1 (polymod (concat (expanded prefix) data))))
         {:prefix prefix :words (vec (drop-last 6 data))})))))

(defn of-words
  "The Bech32 text of the prefix `prefix` and the numbers of five bits
  `words`, in lower case, with its checksum."
  [prefix words]
  (let [prefix (str/lower-case prefix)
        sum    (bit-xor 1 (polymod (concat (expanded prefix) words [0 0 0 0 0 0])))
        check  (map #(bit-and (bit-shift-right sum (* 5 (- 5 %))) 31) (range 6))]
    (str prefix "1" (apply str (map #(nth charset %) (concat words check))))))

(defn decode
  "The prefix and the data of the Bech32 text `s`, read with `opts`, as a
  map of :prefix and :bytes. It's nil for a text that isn't Bech32, that
  has a wrong checksum, or whose data aren't whole bytes.

  The prefix is in lower case. The :max-length of `opts` is the most
  characters of the text, in place of max-length."
  ([s]
   (decode s {}))
  ([s opts]
   {:pre [(map? opts)]}
   (when-let [{:keys [prefix words]} (words s opts)]
     (when-let [octets (regrouped words 5 8 false)]
       {:prefix prefix :bytes (bytes/of octets)}))))

(defn encode
  "The Bech32 text of the prefix `prefix` and the bytes `b`, in lower
  case."
  [prefix b]
  (of-words prefix (regrouped (map #(bytes/at b %) (range (bytes/length b))) 8 5 true)))

(comment
  ;; the example of NIP-19: a public key of Nostr
  (def key-bytes
    (bytes/from-hex "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"))

  (encode "npub" key-bytes)
  (bytes/hex (:bytes (decode "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")))

  ;; a text with a wrong character gives nil
  (decode "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w7")
  #_.)
