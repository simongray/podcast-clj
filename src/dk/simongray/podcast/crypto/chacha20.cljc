(ns dk.simongray.podcast.crypto.chacha20
  "ChaCha20, the stream cipher of RFC 8439, on both platforms.

  The encrypt function gives the bytes of a text under a key and a nonce.
  The cipher makes a stream of bytes from the key, and each byte of the
  text goes with a byte of the stream by exclusive or. So encrypting the
  result gives the text again.

  The Web Crypto API of the browsers has no ChaCha20, so the arithmetic is
  here, for both platforms. A word is a number of 32 bits without a sign.

  The cipher keeps a text secret, but it doesn't tell when somebody
  changed the result. The namespace dk.simongray.podcast.crypto.nip44 adds
  a check of that."
  (:require [dk.simongray.podcast.util.bytes :as bytes]))

(def ^:no-doc constants
  "The four words that start the state: the text \"expand 32-byte k\", as
  section 2.3 of RFC 8439 gives it."
  [0x61707865 0x3320646e 0x79622d32 0x6b206574])

(def ^:no-doc rounds
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

(defn ^:no-doc block
  "The 64 bytes of the stream that the block `counter` gives, under the
  bytes `key` and `nonce`, as numbers. Section 2.3 of RFC 8439 defines the
  block function, with 20 rounds."
  [key nonce counter]
  (let [state (vec (concat constants (words key) [(word counter)] (words nonce)))
        mixed (nth (iterate #(reduce quarter % rounds) state) 10)]
    (for [w (map #(word (+ %1 %2)) mixed state)
          i (range 4)]
      (bit-and (unsigned-bit-shift-right w (* 8 i)) 0xff))))

(defn encrypt
  "The bytes `data` under the bytes `key` and `nonce` by `opts`, as bytes
  of the same number. Encrypting the result gives `data` again.

  The `key` has 32 bytes, and the `nonce` has 12 bytes, which no other
  text under the key has. Section 2.4 of RFC 8439 defines the algorithm.
  The :counter of `opts` is the number of the first block of the stream,
  0 by default. The AEAD of RFC 8439 starts its text at 1."
  ([key nonce data]
   (encrypt key nonce data {}))
  ([key nonce data {:keys [counter] :or {counter 0} :as opts}]
   {:pre [(map? opts)]}
   (when-not (= 32 (bytes/length key))
     (throw (ex-info "The key doesn't have 32 bytes" {:bytes (bytes/length key)})))
   (when-not (= 12 (bytes/length nonce))
     (throw (ex-info "The nonce doesn't have 12 bytes" {:bytes (bytes/length nonce)})))
   (let [stream (mapcat #(block key nonce (+ counter %)) (range))]
     (bytes/of (map #(bit-xor (bytes/at data %1) %2) (range (bytes/length data)) stream)))))

(comment
  (def sample-key
    (bytes/of (range 32)))

  (def sample-nonce
    (bytes/from-hex "000000000000004a00000000"))

  ;; the example of section 2.4.2 of RFC 8439
  (def sealed
    (encrypt sample-key sample-nonce (bytes/encode "Ladies and Gentlemen of the class of '99") {:counter 1}))

  (bytes/hex sealed)
  (bytes/decode (encrypt sample-key sample-nonce sealed {:counter 1}) 0 40 :utf8)
  #_.)
