(ns dk.simongray.podcast.crypto.nip44
  "NIP-44, the encrypted payloads of Nostr, in its version 2, on both
  platforms. Nostr Wallet Connect sends its requests and its answers in
  such payloads.

  Two owners of keys share a conversation key, which conversation-key
  gives to each of them. The encrypt function writes a payload under that
  key, and decrypt reads one. A payload is base64 text: a version, a
  nonce, the encrypted text and a check of the two.

  The text is encrypted with ChaCha20 (RFC 8439), and the check is an
  HMAC with SHA-256 (RFC 2104). The keys of one payload come from the
  conversation key and the nonce by HKDF (RFC 5869). The text gets
  padding, so that the payload tells little of its length.

  NIP-44 asks that a payload is in a signed event, and that a reader
  checks the signature before it reads the payload, as
  dk.simongray.podcast.util.nostr/verified? does."
  (:require [dk.simongray.podcast.crypto.chacha20 :as chacha20]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.secret :as secret]))

(def default-limits
  "The limits of encrypt and decrypt that their options don't set.

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

(defn- extracted
  "The key that HKDF extracts from the bytes `material` with the bytes
  `salt`, as section 2.2 of RFC 5869 defines it."
  [salt material]
  (bytes/hmac-sha256 salt material))

(defn- expanded
  "The `n` bytes that HKDF expands from the bytes `key` for the bytes
  `info`, as section 2.3 of RFC 5869 defines it."
  [key info n]
  (loop [i 1 last (bytes/of []) out []]
    (if (<= n (* 32 (count out)))
      (bytes/copy (bytes/join out) 0 n)
      (let [input (bytes/join [last info (bytes/of [i])])
            part  (bytes/hmac-sha256 key input)]
        (recur (inc i) part (conj out part))))))

(defn- key?
  "Whether `x` is hexadecimal text of 32 bytes, plain or hidden, as a
  conversation key and a nonce are."
  [x]
  (let [text (secret/reveal x)]
    (boolean (and (string? text)
                  (re-matches #"[0-9a-fA-F]{64}" text)))))

(defn- check-key
  "Throw unless `x` is a key, as key? tells, with `what` it is in the
  message. An assertion wouldn't do, since a release build of
  ClojureScript leaves assertions out."
  [x what]
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
  text of dk.simongray.podcast.util.secret. The encrypt and decrypt
  functions take it hidden or plain.

  The section Encryption of NIP-44 defines it: the x coordinate that
  secp256k1/shared-x gives, extracted by HKDF with the salt nip44-v2."
  [secret-key public-key]
  (when-let [x (secp256k1/shared-x secret-key public-key)]
    (let [material (bytes/from-hex (secret/reveal x))
          key      (extracted (bytes/encode salt) material)]
      (secret/hide (bytes/hex key)))))

(defn ^:no-doc message-keys
  "The keys of one payload from `conversation-key` and `nonce`, which are
  hexadecimal text of 32 bytes, as a map of bytes:

  - :cipher-key and :cipher-nonce, of ChaCha20
  - :check-key, of the HMAC

  They're 76 bytes that HKDF expands."
  [conversation-key nonce]
  (let [key (bytes/from-hex (secret/reveal conversation-key))
        b   (expanded key (bytes/from-hex nonce) 76)]
    {:cipher-key   (bytes/copy b 0 32)
     :cipher-nonce (bytes/copy b 32 44)
     :check-key    (bytes/copy b 44 76)}))

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
    (bytes/join [(if (< n 65536)
                   (number-bytes n 2)
                   (bytes/join [(bytes/of [0 0]) (number-bytes n 4)]))
                 text
                 (bytes/of (repeat (- (padded-length n) n) 0))])))

(defn- unpadded
  "The text in the bytes `b`, which padded wrote, as bytes, or nil when the
  bytes don't have the form that padded gives a text of their length."
  [b]
  (when (<= 6 (bytes/length b))
    (let [short  (number-of b 0 2)
          long?  (zero? short)
          n      (if long? (number-of b 2 6) short)
          start  (if long? 6 2)]
      (when (and (pos? n)
                 (= long? (<= 65536 n))
                 (= (bytes/length b) (+ start (padded-length n))))
        (bytes/copy b start (+ start n))))))

(defn- payload-length
  "The characters of a payload with a text of `n` bytes at most."
  [n]
  (let [raw (+ 65 (if (< n 65536) 2 6) (padded-length n))]
    (* 4 (quot (+ raw 2) 3))))

(defn- check
  "The check of the bytes `sealed` with the bytes `nonce` before them,
  under the bytes `key`, as the function hmac_aad of NIP-44 computes it."
  [key nonce sealed]
  (bytes/hmac-sha256 key (bytes/join [nonce sealed])))

(defn encrypt
  "The payload of the text `plaintext` under `conversation-key`, with
  `nonce` and `opts`. The key and the nonce are hexadecimal text of 32
  bytes.

  It's nil for a text without a byte, and for a text with more bytes than
  the :max-bytes of `opts` or of default-limits. The nonce is 32 bytes
  that nobody can guess, new for each payload, e.g. from
  (bytes/hex (bytes/generate! 32)). The section Encryption of NIP-44
  defines the steps."
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
             sealed (chacha20/encrypt cipher-key cipher-nonce (padded text))
             parts  [(bytes/of [version])
                     nonce
                     sealed
                     (check check-key nonce sealed)]]
         (bytes/base64 (bytes/join parts)))))))

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
       (let [nonce  (bytes/copy b 1 33)
             sealed (bytes/copy b 33 (- n 32))
             mac    (bytes/copy b (- n 32) n)
             {:keys [cipher-key cipher-nonce check-key]}
             (message-keys conversation-key (bytes/hex nonce))]
         (when (bytes/same? (check check-key nonce sealed) mac)
           (let [opened (chacha20/encrypt cipher-key cipher-nonce sealed)]
             (when-let [text (unpadded opened)]
               (bytes/decode text 0 (bytes/length text) :utf8)))))))))

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
           (bytes/hex (bytes/generate! 32)))
  #_.)
