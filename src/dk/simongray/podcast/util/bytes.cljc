(ns dk.simongray.podcast.util.bytes
  "Bytes on both platforms: a byte array on the JVM, and a Uint8Array in
  JavaScript."
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt.Hmac])
            #?(:cljs [goog.crypt.Sha1])
            #?(:cljs [goog.crypt.Sha256])
            #?(:cljs [goog.crypt.Sha384])
            #?(:cljs [goog.crypt.Sha512]))
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

(def ^:no-doc digest-names
  "The name of each algorithm of digest in the MessageDigest of the JVM."
  {:sha1   "SHA-1"
   :sha256 "SHA-256"
   :sha384 "SHA-384"
   :sha512 "SHA-512"})

#?(:cljs
   (defn- hasher
     "A hasher of the Closure Library for `algorithm`, and the block size of
     its HMAC, as a pair."
     [algorithm]
     (case algorithm
       :sha1   [(goog.crypt.Sha1.) 64]
       :sha256 [(goog.crypt.Sha256.) 64]
       :sha384 [(goog.crypt.Sha384.) 128]
       :sha512 [(goog.crypt.Sha512.) 128])))

;; FIPS 180-4
(defn digest
  "The digest of the bytes `b` by `algorithm`, :sha1, :sha256, :sha384 or
  :sha512, as bytes."
  [algorithm b]
  #?(:clj  (.digest (MessageDigest/getInstance (digest-names algorithm))
                    ^bytes b)
     :cljs (let [[h] (hasher algorithm)]
             (.update h b)
             (js/Uint8Array.from (.digest h)))))

(defn sha1
  "The SHA-1 digest of the bytes `b`, as 20 bytes.

  An attacker can make two inputs with one SHA-1 digest. So the digest is
  here only for what asks for it: the UUID of version 5 (RFC 4122, section
  4.3), which the podcast:guid is, and the requests of the Podcast Index."
  [b]
  (digest :sha1 b))

(defn sha256
  "The SHA-256 digest of the bytes `b`, as 32 bytes."
  [b]
  (digest :sha256 b))

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

;; RFC 2104
(defn hmac
  "The HMAC of the bytes `message` with the bytes `key` by `algorithm`,
  :sha1, :sha256, :sha384 or :sha512, as bytes."
  [algorithm key message]
  #?(:clj  (let [name (str "Hmac" (str/replace (digest-names algorithm)
                                               "-" ""))]
             (.doFinal (doto (Mac/getInstance name)
                         (.init (SecretKeySpec. ^bytes key name)))
                       ^bytes message))
     :cljs (let [[h block] (hasher algorithm)
                 mac       (goog.crypt.Hmac. h (js/Array.from key) block)]
             (js/Uint8Array.from (.getHmac mac (js/Array.from message))))))

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

;; RFC 4648, section 5
(defn base64url
  "The bytes `b` in the base64 of URLs, without padding."
  [b]
  (-> (base64 b)
      (str/escape {\+ "-" \/ "_"})
      (str/replace #"=+$" "")))

;; RFC 4648, section 5
(defn from-base64url
  "The bytes that the base64url text `s` names, with its padding or
  without it, or nil for a text that isn't base64url."
  [s]
  (when (and (string? s)
             (re-matches #"[A-Za-z0-9_-]*={0,2}" s))
    (let [b64 (str/escape (str/replace s #"=+$" "") {\- "+" \_ "/"})]
      (from-base64 (str b64 (subs "===" 0 (mod (- (count b64)) 4)))))))

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
  "The text `s` as the bytes of its UTF-8."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

;; RFC 3986, section 2.1
(defn- percent-decoded
  "The bytes of the text `s` in UTF-8, with each byte that it
  percent-encodes decoded."
  [s]
  (join (for [piece (re-seq #"%[0-9A-Fa-f]{2}|[^%]+|%" s)]
          (if (and (= 3 (count piece))
                   (str/starts-with? piece "%"))
            (from-hex (subs piece 1))
            (encode piece)))))

;; WHATWG Infra, section 7, as of 5 October 2026
(defn- from-forgiving-base64
  "The bytes that the base64 in the bytes `b` names, or nil. It may have
  spaces and lack its padding, as a data URL may."
  [b]
  (let [s (-> (decode b 0 (length b) :latin1)
              (str/replace #"[\t\n\f\r ]" "")
              (str/replace #"={1,2}$" ""))]
    (from-base64 (str s (subs "===" 0 (mod (- (count s)) 4))))))

;; RFC 2397, section 3
(defn from-data-url
  "The MIME type and the bytes of the data URL `s` as a map of :type and
  :data, or nil for a text that isn't a data URL. A data URL without a
  type is text/plain;charset=US-ASCII."
  [s]
  (let [comma            (when (string? s)
                           (str/index-of s ","))
        [_ type base64?] (when comma
                           (re-matches #"(?i)^data:(.*?)(;base64)?$"
                                       (subs s 0 comma)))
        data             (when type
                           (cond-> (percent-decoded (subs s (inc comma)))
                             base64? from-forgiving-base64))]
    (when data
      {:type (cond
               (str/blank? type)           "text/plain;charset=US-ASCII"
               (str/starts-with? type ";") (str "text/plain" type)
               :else                       type)
       :data data})))

(defn as
  "The text or bytes `x` as `kind` asks for it: bytes for :bytes, and else
  text, with bytes decoded as UTF-8, e.g. for the :as of a request."
  [x kind]
  (cond
    (= :bytes kind) (if (string? x) (encode x) x)
    (string? x)     x
    :else           (decode x 0 (length x) :utf8)))

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

(comment
  (def sample
    (of [0x49 0x44 0x33 4 0 0 0 0 0 28]))

  (base64 sample)
  (base64url sample)
  (decode (encode "Ærø") 0 5 :utf8)
  #_.)
