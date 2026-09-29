(ns dk.simongray.podcast.crypto.secp256k1
  "Schnorr signatures over the curve secp256k1, as BIP-340 defines them,
  on both platforms. Nostr signs each event with such a signature.

  `verify` checks a signature, `sign` makes one, and `public-key` gives
  the public key of a secret key. `shared-x` gives what two owners of keys
  share, which is the start of an encrypted exchange. Each key, message
  and signature is hexadecimal text, which is how Nostr writes them. A
  secret key can be a hidden text of `dk.simongray.podcast.util.secret`.

  `verify` reads no secret key, so the time that it takes tells nothing
  that is secret. The time that `sign`, `public-key` and `shared-x` take
  depends on the secret key.

  The numbers are BigInteger on the JVM and BigInt in JavaScript. A point
  of the curve is `[x y z]` in Jacobian coordinates, and nil is the point
  at infinity.

  A product of a number and a point is a row of doublings and additions.
  The number is read as digits with signs, which need few additions, and
  the multiples of the generator are computed once. No standard defines
  this, so the widths of the digits are a choice."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.secret :as secret])
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

(def ^:no-doc one
  "The number 1, as the platform keeps a number of any size."
  (big "1"))

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
  "The sum of `a` and `b` in the field, for two numbers of the field. The
  sum is below two times the size of the field, so one subtraction brings
  it back, which costs less than a division."
  [a b]
  #?(:clj  (let [sum (.add ^BigInteger a ^BigInteger b)]
             (if (neg? (.compareTo sum ^BigInteger p)) sum (.subtract sum ^BigInteger p)))
     :cljs (let [sum (+ a b)]
             (if (< sum p) sum (- sum p)))))

(defn- sub
  "The difference of `a` and `b` in the field, for two numbers of the
  field."
  [a b]
  #?(:clj  (let [difference (.subtract ^BigInteger a ^BigInteger b)]
             (if (neg? (.signum difference)) (.add difference ^BigInteger p) difference))
     :cljs (let [difference (- a b)]
             (if (neg? difference) (+ difference p) difference))))

(def ^:no-doc low-bits
  "The number 2^256 - 1, which keeps the 256 lowest bits of a number."
  (big (str/join (repeat 64 "f"))))

(def ^:no-doc overflow
  "What 2^256 is in the field: 2^32 + 977. SEC 2 gives the size of the
  field as 2^256 less this number, in its section 2.4.1."
  (big "1000003d1"))

(def ^:no-doc shift
  "The number 256, as the platform shifts a number of any size."
  #?(:clj 256 :cljs (js/BigInt 256)))

(defn- folded
  "A number that is `k` in the field, and smaller: the 256 lowest bits of
  `k`, plus its higher bits times `overflow`."
  [k]
  #?(:clj  (.add (.and ^BigInteger k ^BigInteger low-bits)
                 (.multiply (.shiftRight ^BigInteger k (int shift)) ^BigInteger overflow))
     :cljs (+ (bit-and k low-bits) (* (bit-shift-right k shift) overflow))))

(defn- mul
  "The product of `a` and `b` in the field, for two numbers of the field.
  Two folds bring the product below two times the size of the field, which
  costs less than a division."
  [a b]
  (let [product #?(:clj (.multiply ^BigInteger a ^BigInteger b) :cljs (* a b))
        small   (folded (folded product))]
    #?(:clj  (if (neg? (.compareTo ^BigInteger small ^BigInteger p)) small (.subtract ^BigInteger small ^BigInteger p))
       :cljs (if (< small p) small (- small p)))))

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
  "The sum of the points `a` and `b`. The sum costs less when the z
  coordinate of `b` is 1."
  [[x1 y1 z1 :as a] [x2 y2 z2 :as b]]
  (cond
    (nil? a) b
    (nil? b) a
    :else
    (let [flat?   (= one z2)
          z1z1    (mul z1 z1)
          [u1 s1] (if flat?
                    [x1 y1]
                    (let [z2z2 (mul z2 z2)]
                      [(mul x1 z2z2) (mul y1 (mul z2 z2z2))]))
          u2      (mul x2 z1z1)
          s2      (mul y2 (mul z1 z1z1))
          h       (sub u2 u1)
          r       (sub s2 s1)]
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
           (mul (if flat? z1 (mul z1 z2)) h)])))))

(defn- negative
  "The point that gives the point at infinity when it is added to
  `point`."
  [[x y z]]
  [x (sub zero y) z])

(defn- digits
  "The number `k` below 2^256 as digits with signs, the lowest first, for
  a window of `width` bits. A digit is 0 or an odd number, above and below
  0, that is smaller than 2^(`width` - 1). After a digit that is not 0
  come `width` - 1 digits that are 0.

  This is the non-adjacent form with a window. Of 9 digits of 8 bits, 1 is
  not 0, and only such a digit costs an addition."
  [k width]
  (let [width (long width)
        low   (mapv #(if (= \1 %) 1 0) (reverse (bits k 256)))
        full  (bit-shift-left 1 width)
        bit   #(get low % 0)
        ;; the number that the bits from `i` show in the window
        shown (fn [i] (reduce + (map #(bit-shift-left (bit (+ i %)) %) (range width))))]
    (loop [i 0 carry 0 out []]
      (let [lowest (long (+ (bit i) carry))]
        (cond
          (and (<= 256 i) (zero? carry))
          out

          (even? lowest)
          (recur (inc i) (quot lowest 2) (conj out 0))

          :else
          (let [v      (long (+ (shown i) carry))
                above? (< (quot full 2) v)]
            (recur (+ i width)
                   (if above? 1 0)
                   (into (conj out (if above? (- v full) v)) (repeat (dec width) 0)))))))))

(defn- multiples
  "The odd multiples of the point `point` for digits of `width` bits, as
  a vector: the point times 1, 3, 5 and so on."
  [point width]
  (let [twice (doubled point)]
    (vec (take (bit-shift-left 1 (- width 2)) (iterate #(added % twice) point)))))

(defn- summed
  "The sum of the products of `terms`. Each term is the digits of a
  number, as `digits` gives them, and the odd multiples of a point, as
  `multiples` gives them. The products share their doublings."
  [terms]
  (reduce (fn [acc i]
            (reduce (fn [acc [ds points]]
                      (let [d (get ds i 0)]
                        (cond
                          (zero? d) acc
                          (pos? d)  (added acc (nth points (quot d 2)))
                          :else     (added acc (negative (nth points (quot (- d) 2)))))))
                    (doubled acc)
                    terms))
          nil
          (range (dec (apply max (map (comp count first) terms))) -1 -1)))

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

(defn- secret-number
  "The number of the secret key `secret-key`, hexadecimal text of 32
  bytes, plain or hidden. Nil for a text that is no secret key of the
  curve."
  [secret-key]
  (let [text (secret/reveal secret-key)]
    (when (and (string? text) (re-matches #"[0-9a-fA-F]{64}" text))
      (let [d (big text)]
        (when (and (not= zero d) (< d n))
          d)))))

(def ^:no-doc widths
  "The bits of a window of `digits`, for a product with the generator and
  for a product with another point. The multiples of the generator are
  computed once, so its window is wide. Those of another point are
  computed for each product."
  {:generator 8 :point 5})

(def ^:no-doc generator-multiples
  "The odd multiples of the generator, each with the z coordinate 1, in a
  delay. The first product with the generator computes them."
  (delay (mapv #(conj (affine %) one) (multiples g (:generator widths)))))

(defn- by-generator
  "The term of `summed` for `k` times the generator of the curve."
  [k]
  [(digits k (:generator widths)) @generator-multiples])

(defn- by-point
  "The term of `summed` for `k` times the point `point`."
  [k point]
  [(digits k (:point widths)) (multiples point (:point widths))])

(defn- multiplied
  "The coordinates `[x y]` of `k` times the generator of the curve."
  [k]
  (affine (summed [(by-generator k)])))

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
               [x y] (affine (summed [(by-generator s) (by-point (negated e) point)]))]
           (and (some? x) (even-number? y) (= x r))))))))

(defn public-key
  "The public key of the secret key `secret-key`, both hexadecimal text of
  32 bytes. Nil for a text that is no secret key.

  The section Public Key Generation of BIP-340 defines it: the x
  coordinate of the secret number times the generator."
  [secret-key]
  (when-let [d (secret-number secret-key)]
    (hex32 (first (multiplied d)))))

(defn public-key?
  "Whether `x` is a public key of BIP-340: hexadecimal text of 32 bytes
  that is the x coordinate of a point of the curve."
  [x]
  (boolean (and (string? x)
                (re-matches #"[0-9a-fA-F]{64}" x)
                (lifted (big (str/lower-case x))))))

(defn shared-x
  "The x coordinate of the point that the owner of `secret-key` shares
  with the owner of `public-key`, as hexadecimal text of 32 bytes. Nil
  for a text that is no key, and for a public key that is no point of the
  curve.

  The point is the secret number times the point of the public key, which
  is the key agreement of Diffie and Hellman on the curve. The two owners
  get the same point, each from the own secret key and the public key of
  the other. NIP-44 uses the x coordinate as it is, without a hash.

  The result is as secret as the secret key, so it is a hidden text of
  `dk.simongray.podcast.util.secret`. The time that `shared-x` takes depends on
  the secret key."
  [secret-key public-key]
  (when-let [d (secret-number secret-key)]
    (when (public-key? public-key)
      (let [point (lifted (big (str/lower-case public-key)))]
        (some-> (affine (summed [(by-point d point)])) first hex32 secret/hide)))))

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
  (when-let [d (secret-number secret-key)]
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
