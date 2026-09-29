(ns dk.simongray.podcast.crypto.secp256k1-test
  "Schnorr signatures by the test vectors of BIP-340, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.test-support :refer [fixture secret-key]]
            [dk.simongray.podcast.util.secret :as secret]))

(def vectors
  "The test vectors of BIP-340, each as a map of the columns of the file."
  (let [[header & rows] (map #(str/split % #"," -1) (str/split-lines (fixture "secp256k1/bip340-test-vectors.csv")))]
    (mapv #(zipmap header %) rows)))

(deftest the-vectors-of-bip-340
  (is (= 19 (count vectors)))
  (doseq [{:strs [index message signature comment] secret "secret key" public "public key"
           aux "aux_rand" result "verification result"} vectors]
    (testing (str "vector " index " " comment)
      (is (= (= "TRUE" result) (secp256k1/verify public message signature)))
      (when (seq secret)
        (is (= (str/lower-case public) (secp256k1/public-key secret)))
        (is (= (str/lower-case signature) (secp256k1/sign secret message aux)))))))

(deftest a-signature-of-another-message
  (let [secret  (secret-key 3)
        public  (secp256k1/public-key secret)
        message (apply str (repeat 32 "ab"))
        aux     (apply str (repeat 32 "01"))
        sig     (secp256k1/sign secret message aux)]
    (is (= "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9" public))
    (is (secp256k1/verify public message sig))
    (is (secp256k1/verify (str/upper-case public) (str/upper-case message) (str/upper-case sig)) "in upper case")
    (is (not (secp256k1/verify public (str "ac" (subs message 2)) sig)))
    (is (not (secp256k1/verify (secp256k1/public-key (secret-key 4)) message sig)))
    (is (not (secp256k1/verify public message (str (subs sig 0 127) (if (str/ends-with? sig "0") "1" "0")))))
    (is (not= sig (secp256k1/sign secret message (apply str (repeat 32 "02")))) "other random data, another signature")
    (is (secp256k1/verify public message (secp256k1/sign secret message (apply str (repeat 32 "02")))))))

(deftest numbers-of-each-pattern
  (let [message (apply str (repeat 32 "ab"))
        aux     (apply str (repeat 32 "01"))
        highest "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140"]
    (testing "two times the generator"
      (is (= "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
             (secp256k1/public-key (secret-key 2)))))
    (testing "a number and the order less the number have one public key"
      (is (= (secp256k1/public-key (secret-key 1)) (secp256k1/public-key highest)))
      (is (= (secp256k1/public-key (secret-key 2))
             (secp256k1/public-key "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd036413f"))))
    (testing "a signature of a key with long runs of the same bit"
      (doseq [secret [highest
                      "8000000000000000000000000000000000000000000000000000000000000000"
                      "00000000000000000000000000000000ffffffffffffffffffffffffffffffff"
                      "5555555555555555555555555555555555555555555555555555555555555555"
                      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0"]]
        (let [public (secp256k1/public-key secret)
              sig    (secp256k1/sign secret message aux)]
          (is (secp256k1/verify public message sig) secret)
          (is (not (secp256k1/verify public (str message "00") sig)) secret))))))

(deftest the-point-of-a-public-key
  (is (secp256k1/public-key? (secp256k1/public-key (secret-key 3))))
  (is (secp256k1/public-key? (str/upper-case (secp256k1/public-key (secret-key 3)))))
  (doseq [x [nil 5 "" "03" (apply str (repeat 64 "f")) (apply str (repeat 64 "g"))
             "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"]]
    (is (not (secp256k1/public-key? x)) (pr-str x)))
  (testing "what two owners share"
    (let [ann (secret-key 3)
          bob (secret-key 4)]
      (is (= (secp256k1/shared-x ann (secp256k1/public-key bob))
             (secp256k1/shared-x bob (secp256k1/public-key ann))))
      (is (re-matches #"[0-9a-f]{64}" (secret/reveal (secp256k1/shared-x ann (secp256k1/public-key bob)))))
      (is (= "#<secret>" (pr-str (secp256k1/shared-x ann (secp256k1/public-key bob)))) "it is as secret as the key")
      (is (nil? (secp256k1/shared-x ann (apply str (repeat 64 "f")))) "a key that is no point of the curve")
      (is (nil? (secp256k1/shared-x "no key" (secp256k1/public-key bob)))))))

(deftest a-secret-key-that-is-hidden
  (let [secret  (secret-key 3)
        hidden  (secret/hide secret)
        message (apply str (repeat 32 "ab"))
        aux     (apply str (repeat 32 "01"))]
    (is (= (secp256k1/public-key secret) (secp256k1/public-key hidden)))
    (is (= (secp256k1/sign secret message aux) (secp256k1/sign hidden message aux)))
    (is (= (secp256k1/shared-x secret (secp256k1/public-key (secret-key 4)))
           (secp256k1/shared-x hidden (secp256k1/public-key (secret-key 4)))))
    (is (nil? (secp256k1/public-key (secret/hide "no key"))))))

(deftest what-is-no-key-and-no-signature
  (let [zero  (apply str (repeat 64 "0"))
        order "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"]
    (doseq [secret [nil "" "03" zero order (apply str (repeat 64 "f")) (apply str (repeat 64 "g")) 3]]
      (is (nil? (secp256k1/public-key secret)) (pr-str secret))
      (is (nil? (secp256k1/sign secret zero zero)) (pr-str secret)))
    (is (nil? (secp256k1/sign (secret-key 3) "abc" zero)) "a message with a half byte")
    (is (nil? (secp256k1/sign (secret-key 3) zero "00")) "random data of another size")
    (doseq [[public message signature] [[nil zero (str zero zero)]
                                        ["03" zero (str zero zero)]
                                        [(secp256k1/public-key (secret-key 3)) nil (str zero zero)]
                                        [(secp256k1/public-key (secret-key 3)) "xyz" (str zero zero)]
                                        [(secp256k1/public-key (secret-key 3)) zero zero]
                                        [(secp256k1/public-key (secret-key 3)) zero nil]
                                        [(secp256k1/public-key (secret-key 3)) zero (str zero zero)]]]
      (is (false? (secp256k1/verify public message signature)) (pr-str [public message signature])))))
