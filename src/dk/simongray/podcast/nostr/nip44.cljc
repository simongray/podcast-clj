(ns dk.simongray.podcast.nostr.nip44
  "NIP-44, the encrypted payloads of Nostr, in its version 2, on both
  platforms. Nostr Wallet Connect sends its requests and its answers in
  such payloads.

  Two owners of keys share a conversation key, under which each payload
  between them is encrypted. A payload is base64 text: a version, a
  nonce, the encrypted text and a check of the two.

  The text is encrypted with ChaCha20 (RFC 8439), which is here since the
  Web Crypto API of the browsers lacks it, and the check is an HMAC with
  SHA-256 (RFC 2104). The keys of one payload come from the conversation
  key and the nonce by HKDF (RFC 5869). The text gets padding, so that the
  payload tells little of its length.

  NIP-44 asks that a payload is in a signed event, and that a reader
  checks the signature before it reads the payload."
  (:require [dk.simongray.podcast.nostr.secp256k1 :as secp256k1]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.webcrypto :as webcrypto]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]))

(def default-limits
  "The limits of a payload that the options don't set.

  The :max-bytes is the most bytes of a text, as UTF-8. NIP-44 allows
  2^32 - 1 bytes, and asks each reader for a limit of its own. The limit
  here is a choice: the most bytes that a payload holds with a length of
  two bytes."
  {:max-bytes 65535})

(def ^:no-doc version
  "The version of the payloads that podcast-clj writes and reads."
  2)

(def ^:no-doc salt
  "The salt of the conversation key, as the section Encryption of NIP-44
  gives it."
  "nip44-v2")

(defn- key?
  "Whether `x` is hexadecimal text of 32 bytes, plain or hidden, as a
  conversation key and a nonce are."
  [x]
  (let [text (secret/reveal x)]
    (boolean (and (string? text)
                  (re-matches #"[0-9a-fA-F]{64}" text)))))

(defn- check-key
  "Throw unless `x` is a key, with `what` it is in the message."
  [x what]
  ;; an assertion wouldn't do, since a release build of ClojureScript
  ;; leaves assertions out
  (when-not (key? x)
    (throw (ex-info (str "The " what " isn't hexadecimal text of 32 bytes")
                    {}))))

(defn conversation-key
  "The key that the owner of `secret-key` shares with the owner of
  `public-key`, as hexadecimal text of 32 bytes. It's nil for a text that
  isn't a key, and for a public key that isn't a point of the curve.

  The owner of the other secret key gets the same key with the public key
  of this one. The key is for each payload between the two, so an app
  computes it once. It's as secret as the secret key, so it's a hidden
  text of dk.simongray.wary-fetch.secret.

  The section Encryption of NIP-44 defines it: the x coordinate of the
  point that the two keys share on secp256k1, extracted by HKDF with the
  salt nip44-v2."
  [secret-key public-key]
  (when-let [x (secp256k1/shared-x secret-key public-key)]
    (let [material (bytes/from-hex (secret/reveal x))
          key      (hash/hkdf-extract :sha-256 (bytes/encode salt) material)]
      (secret/hide (bytes/hex key)))))

(defn ^:no-doc message-keys
  "The keys of one payload from `conversation-key` and `nonce`, which are
  hexadecimal text of 32 bytes, as a map of bytes:

  - :cipher-key and :cipher-nonce, of ChaCha20
  - :check-key, of the HMAC

  They're 76 bytes that HKDF expands."
  [conversation-key nonce]
  (let [key (bytes/from-hex (secret/reveal conversation-key))
        b   (hash/hkdf-expand :sha-256 key (bytes/from-hex nonce) 76)]
    {:cipher-key   (bytes/slice b 0 32)
     :cipher-nonce (bytes/slice b 32 44)
     :check-key    (bytes/slice b 44 76)}))

(defn ^:no-doc padded-length
  "The bytes of a text of `n` bytes with its padding, as the function
  calc_padded_len of NIP-44 computes it. It's 32 bytes at least, and then
  a multiple of a chunk that grows with the text."
  [n]
  (if (<= n 32)
    32
    (let [power (first (drop-while #(< % n) (iterate #(* 2 %) 1)))
          chunk (if (<= power 256) 32 (quot power 8))]
      (* chunk (inc (quot (dec n) chunk))))))

(defn- number-bytes
  "The number `x` as `n` bytes, the highest byte first."
  [x n]
  (bytes/of (for [i (range (dec n) -1 -1)]
              (mod (quot x (reduce * (repeat i 256))) 256))))

(defn- number-of
  "The number that the bytes of `b` from `start` to `end` give, the
  highest byte first."
  [b start end]
  (reduce #(+ (* 256 %1) (bytes/at b %2)) 0 (range start end)))

(defn- padded
  "The bytes `text` with their length before them and zeros after them,
  as the function pad of NIP-44 writes them. The length has two bytes. A
  text of 65,536 bytes or more has two zeros and a length of four bytes."
  [text]
  (let [n (bytes/length text)]
    (bytes/concat (if (< n 65536)
                    (number-bytes n 2)
                    (bytes/concat (bytes/of [0 0]) (number-bytes n 4)))
                  text
                  (bytes/of (repeat (- (padded-length n) n) 0)))))

(defn- unpadded
  "The text in the padded bytes `b`, as bytes, or nil when the bytes don't
  have the form of a padded text of their length."
  [b]
  (when (<= 6 (bytes/length b))
    (let [short  (number-of b 0 2)
          long?  (zero? short)
          n      (if long? (number-of b 2 6) short)
          start  (if long? 6 2)]
      (when (and (pos? n)
                 (= long? (<= 65536 n))
                 (= (bytes/length b) (+ start (padded-length n))))
        (bytes/slice b start (+ start n))))))

(defn- payload-length
  "The characters of a payload with a text of `n` bytes at most."
  [n]
  (let [raw (+ 65 (if (< n 65536) 2 6) (padded-length n))]
    (* 4 (quot (+ raw 2) 3))))

(defn- check
  "The check of the bytes `sealed` with the bytes `nonce` before them,
  under the bytes `key`, as the function hmac_aad of NIP-44 computes it."
  [key nonce sealed]
  (hash/hmac :sha-256 key (bytes/concat nonce sealed)))

;; ChaCha20, the stream cipher of RFC 8439. A word is a number of 32 bits
;; without a sign. The cipher keeps a text secret, but doesn't tell when
;; somebody changed it, which is what the check above is for.

(def ^:no-doc chacha20-constants
  "The four words that start the state of ChaCha20: the text \"expand
  32-byte k\", as section 2.3 of RFC 8439 gives it."
  [0x61707865 0x3320646e 0x79622d32 0x6b206574])

(def ^:no-doc chacha20-rounds
  "The places of the words of each quarter round of a double round, as
  section 2.3 of RFC 8439 gives them: the four columns of the state, and
  then its four diagonals."
  [[0 4 8 12] [1 5 9 13] [2 6 10 14] [3 7 11 15]
   [0 5 10 15] [1 6 11 12] [2 7 8 13] [3 4 9 14]])

(defn- word
  "The 32 lowest bits of the number `x`, as a number without a sign."
  [x]
  #?(:clj  (bit-and (long x) 0xffffffff)
     :cljs (unsigned-bit-shift-right x 0)))

(defn- rotated
  "The word `x` with its bits moved `n` places to the left, and around
  its end."
  [x n]
  (word (bit-or (bit-shift-left x n) (unsigned-bit-shift-right x (- 32 n)))))

(defn- quarter
  "The state `s`, a vector of 16 words, after a quarter round on the words
  at the places `a`, `b`, `c` and `d`. Section 2.1 of RFC 8439 defines
  the quarter round."
  [s [a b c d]]
  (let [mixed (fn [s x y z n]
                (let [sum (word (+ (s x) (s y)))]
                  (assoc s x sum z (rotated (bit-xor (s z) sum) n))))]
    (-> s
        (mixed a b d 16)
        (mixed c d b 12)
        (mixed a b d 8)
        (mixed c d b 7))))

(defn- words
  "The words of the bytes `b`, four bytes to a word, the lowest byte
  first."
  [b]
  (for [i (range 0 (bytes/length b) 4)]
    (+ (bytes/at b i)
       (* 0x100 (bytes/at b (+ i 1)))
       (* 0x10000 (bytes/at b (+ i 2)))
       (* 0x1000000 (bytes/at b (+ i 3))))))

(defn ^:no-doc chacha20-block
  "The 64 bytes of the stream of ChaCha20 that the block `counter` gives,
  under the bytes `key` and `nonce`, as numbers. Section 2.3 of RFC 8439
  defines the block function, with 20 rounds."
  [key nonce counter]
  (let [state (vec (concat chacha20-constants
                           (words key)
                           [(word counter)]
                           (words nonce)))
        mixed (nth (iterate #(reduce quarter % chacha20-rounds) state) 10)]
    (for [w (map #(word (+ %1 %2)) mixed state)
          i (range 4)]
      (bit-and (unsigned-bit-shift-right w (* 8 i)) 0xff))))

(defn ^:no-doc chacha20
  "The bytes `data` under the bytes `key` and `nonce` by ChaCha20, with
  `opts`, as bytes of the same number. Encrypting the result gives `data`
  again.

  The `key` has 32 bytes, and the `nonce` has 12 bytes, which no other
  text under the key has. Section 2.4 of RFC 8439 defines the algorithm.
  The :counter of `opts` is the number of the first block of the stream,
  0 by default. The AEAD of RFC 8439 starts its text at 1."
  ([key nonce data]
   (chacha20 key nonce data {}))
  ([key nonce data {:keys [counter] :or {counter 0} :as opts}]
   {:pre [(map? opts)]}
   (when-not (= 32 (bytes/length key))
     (throw (ex-info "The key doesn't have 32 bytes"
                     {:bytes (bytes/length key)})))
   (when-not (= 12 (bytes/length nonce))
     (throw (ex-info "The nonce doesn't have 12 bytes"
                     {:bytes (bytes/length nonce)})))
   (let [stream (mapcat #(chacha20-block key nonce (+ counter %)) (range))
         xored  (map #(bit-xor (bytes/at data %1) %2)
                     (range (bytes/length data))
                     stream)]
     (bytes/of xored))))

(defn encrypt
  "The payload of the text `plaintext` under `conversation-key`, with
  `nonce` and `opts`. The key and the nonce are hexadecimal text of 32
  bytes.

  It's nil for a text without a byte, and for a text with more bytes than
  the :max-bytes of `opts` or of default-limits. The nonce is 32 random
  bytes that nobody can guess, new for each payload. The section
  Encryption of NIP-44 defines the steps."
  ([plaintext conversation-key nonce]
   (encrypt plaintext conversation-key nonce {}))
  ([plaintext conversation-key nonce opts]
   {:pre [(map? opts)]}
   (when-not (string? plaintext)
     (throw (ex-info "The plaintext isn't a string" {})))
   (check-key conversation-key "conversation key")
   (check-key nonce "nonce")
   (let [text (bytes/encode plaintext)]
     (when (<= 1 (bytes/length text) (:max-bytes (merge default-limits opts)))
       (let [{:keys [cipher-key cipher-nonce check-key]}
             (message-keys conversation-key nonce)

             nonce  (bytes/from-hex nonce)
             sealed (chacha20 cipher-key cipher-nonce (padded text))
             parts  [(bytes/of [version])
                     nonce
                     sealed
                     (check check-key nonce sealed)]]
         (bytes/base64 (apply bytes/concat parts)))))))

(defn payload-version
  "The version of the `payload`, a number, or nil for a text that isn't a
  payload of NIP-44. It's nil too for a payload in a form after version 2,
  which starts with a number sign.

  This namespace reads version 2. With another version, an app can tell
  the user that the other side has a later version of NIP-44."
  [payload]
  (when (and (string? payload)
             (<= 4 (count payload)))
    (some-> (bytes/from-base64 (subs payload 0 4)) (bytes/at 0))))

(defn decrypt
  "The text of the `payload` under `conversation-key`, with `opts`, or nil
  for a payload that the key doesn't open:

  - a payload of another version
  - a payload that somebody changed
  - a payload of another key
  - a payload with a text of more bytes than the :max-bytes of `opts` or
    of default-limits

  The section Decryption of NIP-44 defines the steps. The check of the
  payload comes before the text is read, and takes the same time for each
  wrong check."
  ([payload conversation-key]
   (decrypt payload conversation-key {}))
  ([payload conversation-key opts]
   {:pre [(map? opts)]}
   (check-key conversation-key "conversation key")
   (let [most (payload-length (:max-bytes (merge default-limits opts)))
         b    (when (and (string? payload)
                         (<= 132 (count payload) most))
                (bytes/from-base64 payload))
         n    (if b (bytes/length b) 0)]
     (when (and (<= 99 n)
                (= version (bytes/at b 0)))
       (let [nonce  (bytes/slice b 1 33)
             sealed (bytes/slice b 33 (- n 32))
             mac    (bytes/slice b (- n 32) n)
             {:keys [cipher-key cipher-nonce check-key]}
             (message-keys conversation-key (bytes/hex nonce))]
         (when (bytes/same? (check check-key nonce sealed) mac)
           (let [opened (chacha20 cipher-key cipher-nonce sealed)]
             (when-let [text (unpadded opened)]
               (bytes/decode text)))))))))

(comment
  ;; the secret keys 1 and 2, and the public key of each
  (def ann "0000000000000000000000000000000000000000000000000000000000000001")
  (def bob "0000000000000000000000000000000000000000000000000000000000000002")

  ;; the two get the same key
  (conversation-key ann (secp256k1/public-key bob))
  (conversation-key bob (secp256k1/public-key ann))

  ;; the example of NIP-44
  (def payload
    (encrypt
     "a"
     "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"
     "0000000000000000000000000000000000000000000000000000000000000001"))

  (payload-version payload)
  (decrypt payload
           "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d")

  ;; a new nonce for each payload
  (encrypt "a"
           (conversation-key ann (secp256k1/public-key bob))
           (bytes/hex (webcrypto/random-bytes 32)))
  #_.)
