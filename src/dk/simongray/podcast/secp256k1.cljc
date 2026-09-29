(ns dk.simongray.podcast.secp256k1
  "Schnorr signatures over the curve secp256k1, as BIP-340 defines them,
  on both platforms. Nostr signs each event with such a signature.

  `verify` checks a signature, `sign` makes one, and `public-key` gives
  the public key of a secret key. Each key, message and signature is
  hexadecimal text, which is how Nostr writes them.

  `verify` reads no secret key, so the time that it takes tells nothing
  that is secret. The time that `sign` and `public-key` take depends on
  the secret key.

  The numbers are BigInteger on the JVM and BigInt in JavaScript. A point
  of the curve is `[x y z]` in Jacobian coordinates, and nil is the point
  at infinity."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.bytes :as bytes])
  #?(:clj (:import [java.math BigInteger])))

(defn- big
  "The number that the hexadecimal text `s` names."
  [s]
  #?(:clj  (BigInteger. ^String s 16)
     :cljs (js/BigInt (str "0x" s))))

(def ^:no-doc p
  "The size of the field of secp256k1."
  (big "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f"))

(def ^:no-doc n
  "The order of the curve secp256k1."
  (big "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"))

(def ^:no-doc g
  "The generator of secp256k1, as a point."
  [(big "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
   (big "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8")
   (big "1")])

(def ^:no-doc zero
  "The number 0, as the platform keeps a number of any size."
  (big "0"))

(def ^:no-doc root-power
  "The power that gives a square root in the field: (p + 1) / 4."
  #?(:clj  (.shiftRight (.add ^BigInteger p BigInteger/ONE) 2)
     :cljs (/ (+ p (big "1")) (big "4"))))

(def ^:no-doc inverse-power
  "The power that gives an inverse in the field: p - 2."
  #?(:clj  (.subtract ^BigInteger p BigInteger/TWO)
     :cljs (- p (big "2"))))

(defn- tagged
  "The tagged hash of BIP-340 with the tag `tag`, for the bytes that the
  hexadecimal text `data` names, in hexadecimal."
  [tag data]
  (let [t (bytes/hex (bytes/sha256 (bytes/encode tag)))]
    (bytes/hex (bytes/sha256 (bytes/from-hex (str t t data))))))

(defn- hex32
  "The number `k` as hexadecimal text of 32 bytes."
  [k]
  (let [s #?(:clj  (.toString ^BigInteger k 16)
             :cljs (.toString k 16))]
    (str (str/join (repeat (- 64 (count s)) "0")) s)))

(defn- binary
  "The number `k` as a text of the characters 0 and 1."
  [k]
  #?(:clj  (.toString ^BigInteger k 2)
     :cljs (.toString k 2)))

(defn- add
  "The sum of `a` and `b` in the field."
  [a b]
  #?(:clj  (.mod (.add ^BigInteger a ^BigInteger b) p)
     :cljs (mod (+ a b) p)))

(defn- sub
  "The difference of `a` and `b` in the field."
  [a b]
  #?(:clj  (.mod (.subtract ^BigInteger a ^BigInteger b) p)
     :cljs (mod (- a b) p)))

(defn- mul
  "The product of `a` and `b` in the field."
  [a b]
  #?(:clj  (.mod (.multiply ^BigInteger a ^BigInteger b) p)
     :cljs (mod (* a b) p)))

(defn- bits
  "The bits of the number `k` as a text of `width` characters 0 and 1, the
  highest bit first."
  [k width]
  (let [s (binary k)]
    (str (str/join (repeat (- width (count s)) "0")) s)))

(defn- power
  "`a` to the power `e` in the field."
  [a e]
  #?(:clj  (.modPow ^BigInteger a ^BigInteger e p)
     :cljs (reduce (fn [acc bit]
                     (let [squared (mul acc acc)]
                       (if (= "1" bit) (mul squared a) squared)))
                   (big "1")
                   (binary e))))

(defn- doubled
  "The point `point` added to itself."
  [[x y z :as point]]
  (when (and point (not= zero y))
    (let [a  (mul x x)
          b  (mul y y)
          c  (mul b b)
          xb (add x b)
          d  (let [t (sub (sub (mul xb xb) a) c)] (add t t))
          e  (add a (add a a))
          x3 (sub (mul e e) (add d d))
          c8 (let [c2 (add c c) c4 (add c2 c2)] (add c4 c4))]
      [x3
       (sub (mul e (sub d x3)) c8)
       (let [yz (mul y z)] (add yz yz))])))

(defn- added
  "The sum of the points `a` and `b`."
  [[x1 y1 z1 :as a] [x2 y2 z2 :as b]]
  (cond
    (nil? a) b
    (nil? b) a
    :else
    (let [z1z1 (mul z1 z1)
          z2z2 (mul z2 z2)
          u1   (mul x1 z2z2)
          u2   (mul x2 z1z1)
          s1   (mul y1 (mul z2 z2z2))
          s2   (mul y2 (mul z1 z1z1))
          h    (sub u2 u1)
          r    (sub s2 s1)]
      (cond
        (and (= zero h) (= zero r)) (doubled a)
        (= zero h)                  nil
        :else
        (let [hh  (mul h h)
              hhh (mul h hh)
              v   (mul u1 hh)
              x3  (sub (sub (mul r r) hhh) (add v v))]
          [x3
           (sub (mul r (sub v x3)) (mul s1 hhh))
           (mul (mul z1 z2) h)])))))

(defn- combined
  "The point `j` times the point `a` plus `k` times the point `b`, for the
  numbers `j` and `k` below the order of the curve. The two products share
  their doublings."
  [j a k b]
  (let [both (added a b)]
    (reduce (fn [acc [bit-a bit-b]]
              (let [acc (doubled acc)]
                (case [bit-a bit-b]
                  [\1 \1] (added acc both)
                  [\1 \0] (added acc a)
                  [\0 \1] (added acc b)
                  acc)))
            nil
            (map vector (bits j 256) (bits k 256)))))

(defn- affine
  "The coordinates `[x y]` of the point `point` on the curve. Nil for the
  point at infinity."
  [[x y z :as point]]
  (when point
    (let [inverse  (power z inverse-power)
          inverse2 (mul inverse inverse)]
      [(mul x inverse2) (mul y (mul inverse inverse2))])))

(defn- even-number?
  "Whether the number `k` is even."
  [k]
  (str/ends-with? (binary k) "0"))

(defn- lifted
  "The point of the curve with the x coordinate `x` and an even y
  coordinate, as the function lift_x of BIP-340 gives it. Nil when the
  curve has no such point."
  [x]
  (when (< x p)
    (let [c (add (mul x (mul x x)) (big "7"))
          y (power c root-power)]
      (when (= c (mul y y))
        [x (if (even-number? y) y (sub zero y)) (big "1")]))))

(defn- challenge
  "The number e of BIP-340 for the hexadecimal texts `r`, `public-key` and
  `message`: the tagged hash BIP0340/challenge of their bytes. The result
  is the number that is left when the order of the curve is taken off as
  often as it goes."
  [r public-key message]
  (let [e (big (tagged "BIP0340/challenge" (str r public-key message)))]
    #?(:clj  (.mod ^BigInteger e n)
       :cljs (mod e n))))

(defn- negated
  "The number that gives zero when it is added to `e`, by the order of the
  curve."
  [e]
  #?(:clj  (.mod (.subtract ^BigInteger n ^BigInteger e) n)
     :cljs (mod (- n e) n)))

(defn- secret
  "The number of the secret key `secret-key`, hexadecimal text of 32
  bytes. Nil for a text that is no secret key of the curve."
  [secret-key]
  (when (and (string? secret-key) (re-matches #"[0-9a-fA-F]{64}" secret-key))
    (let [d (big secret-key)]
      (when (and (not= zero d) (< d n))
        d))))

(defn- multiplied
  "The coordinates `[x y]` of `k` times the generator of the curve."
  [k]
  (affine (combined k g zero g)))

(defn- xor
  "The bytes of the hexadecimal texts `a` and `b`, each byte of `a` with
  the byte of `b` at its place by exclusive or, in hexadecimal."
  [a b]
  (let [[a b] (map bytes/from-hex [a b])]
    (bytes/hex (bytes/of (map #(bit-xor (bytes/at a %) (bytes/at b %)) (range (bytes/length a)))))))

(defn verify
  "Whether `signature` is the signature of the message `message` by the
  owner of `public-key`. The three are hexadecimal text: a signature of
  64 bytes, a message of any length and a public key of 32 bytes. False
  for other values.

  The check is the algorithm Verify of BIP-340, in its section
  Verification. A public key is the x coordinate of a point with an even
  y coordinate."
  [public-key message signature]
  (boolean
   (when (and (string? public-key) (re-matches #"[0-9a-fA-F]{64}" public-key)
              (string? signature) (re-matches #"[0-9a-fA-F]{128}" signature)
              (some? (bytes/from-hex message)))
     (let [[public-key message signature] (map str/lower-case [public-key message signature])
           point (lifted (big public-key))
           r     (big (subs signature 0 64))
           s     (big (subs signature 64))]
       (when (and point (< r p) (< s n))
         (let [e     (challenge (subs signature 0 64) public-key message)
               [x y] (affine (combined s g (negated e) point))]
           (and (some? x) (even-number? y) (= x r))))))))

(defn public-key
  "The public key of the secret key `secret-key`, both hexadecimal text of
  32 bytes. Nil for a text that is no secret key.

  The section Public Key Generation of BIP-340 defines it: the x
  coordinate of the secret number times the generator."
  [secret-key]
  (when-let [d (secret secret-key)]
    (hex32 (first (multiplied d)))))

(defn sign
  "The signature of the message `message` with the secret key
  `secret-key`, as hexadecimal text of 64 bytes. Nil for a text that is
  no secret key.

  `message` is hexadecimal text of any length. `aux` is hexadecimal text
  of 32 bytes that nobody can guess, new for each signature. The
  algorithm is Sign of BIP-340, in its section Default Signing, which
  also checks the signature before it gives it.

  The time that `sign` takes depends on the secret key. So sign only
  where no stranger measures that time."
  [secret-key message aux]
  (when-let [d (secret secret-key)]
    (when (and (some? (bytes/from-hex message))
               (string? aux) (re-matches #"[0-9a-fA-F]{64}" aux))
      (let [[px py] (multiplied d)
            d       (if (even-number? py) d (negated d))
            t       (xor (hex32 d) (tagged "BIP0340/aux" aux))
            nonce   (big (tagged "BIP0340/nonce" (str t (hex32 px) message)))
            k       #?(:clj  (.mod ^BigInteger nonce n)
                       :cljs (mod nonce n))]
        (when (not= zero k)
          (let [[rx ry]   (multiplied k)
                k         (if (even-number? ry) k (negated k))
                e         (challenge (hex32 rx) (hex32 px) message)
                s         #?(:clj  (.mod (.add ^BigInteger k (.multiply ^BigInteger e ^BigInteger d)) n)
                             :cljs (mod (+ k (* e d)) n))
                signature (str (hex32 rx) (hex32 s))]
            (when (verify (hex32 px) message signature)
              signature)))))))

(comment
  ;; the first test vector of BIP-340
  (verify "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
          "0000000000000000000000000000000000000000000000000000000000000000"
          (str "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215"
               "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"))

  ;; another message gives false
  (verify "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
          "0000000000000000000000000000000000000000000000000000000000000001"
          (str "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215"
               "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"))
  #_.)
