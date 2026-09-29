(ns dk.simongray.podcast.chacha20-test
  "ChaCha20 by the examples of RFC 8439, on both platforms, and on the JVM
  against the cipher of the JDK."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.chacha20 :as chacha20])
  #?(:clj (:import [java.util Arrays]
                   [javax.crypto Cipher]
                   [javax.crypto.spec ChaCha20ParameterSpec SecretKeySpec])))

(def sample-key
  "The key of the examples: the bytes 0 to 31."
  (bytes/of (range 32)))

(def sunscreen
  "The text of the example of section 2.4.2."
  "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")

(deftest the-examples-of-rfc-8439
  (testing "the block of section 2.3.2"
    (is (= (str "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e"
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e")
           (bytes/hex (bytes/of (chacha20/block sample-key (bytes/from-hex "000000090000004a00000000") 1))))))
  (testing "the text of section 2.4.2, whose stream starts at the block 1"
    (let [nonce  (bytes/from-hex "000000000000004a00000000")
          sealed (chacha20/encrypt sample-key nonce (bytes/encode sunscreen) {:counter 1})]
      (is (= (str "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b"
                  "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8"
                  "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736"
                  "5af90bbf74a35be6b40b8eedf2785e42874d")
             (bytes/hex sealed)))
      (is (= sunscreen (bytes/decode (chacha20/encrypt sample-key nonce sealed {:counter 1}) 0 (bytes/length sealed) :utf8))
          "the cipher is its own inverse")
      (is (not= (bytes/hex sealed) (bytes/hex (chacha20/encrypt sample-key nonce (bytes/encode sunscreen))))
          "the stream starts at the block 0, unless the options tell another"))))

(deftest texts-of-each-length
  (let [nonce (bytes/from-hex "000000000000004a00000000")]
    (doseq [n [0 1 63 64 65 128 300]]
      (let [text   (bytes/of (map #(mod (* 7 %) 256) (range n)))
            sealed (chacha20/encrypt sample-key nonce text)]
        (is (= n (bytes/length sealed)) n)
        (is (= (bytes/hex text) (bytes/hex (chacha20/encrypt sample-key nonce sealed))) n)))
    (is (= (bytes/hex (bytes/copy (chacha20/encrypt sample-key nonce (bytes/of (repeat 130 0))) 64 128))
           (bytes/hex (chacha20/encrypt sample-key nonce (bytes/of (repeat 64 0)) {:counter 1})))
        "the second block of a stream is the first block of the stream that starts at 1")))

(deftest a-key-and-a-nonce-of-another-size
  (let [nonce (bytes/from-hex "000000000000004a00000000")]
    (is (thrown? #?(:clj Throwable :cljs js/Error) (chacha20/encrypt (bytes/of (range 16)) nonce (bytes/of [1]))))
    (is (thrown? #?(:clj Throwable :cljs js/Error) (chacha20/encrypt sample-key (bytes/of (range 8)) (bytes/of [1]))))))

#?(:clj
   (deftest the-cipher-of-the-jdk
     (let [jdk (fn [^bytes key ^bytes nonce counter ^bytes data]
                 (.doFinal (doto (Cipher/getInstance "ChaCha20")
                             (.init Cipher/ENCRYPT_MODE (SecretKeySpec. key "ChaCha20") (ChaCha20ParameterSpec. nonce (int counter))))
                           data))]
       (doseq [i (range 60)]
         (let [key     (bytes/random! 32)
               nonce   (bytes/random! 12)
               data    (bytes/random! (* 11 i))
               counter (* 1000 i)]
           (is (Arrays/equals ^bytes (jdk key nonce counter data)
                              ^bytes (chacha20/encrypt key nonce data {:counter counter}))
               (str "a text of " (* 11 i) " bytes")))))))
