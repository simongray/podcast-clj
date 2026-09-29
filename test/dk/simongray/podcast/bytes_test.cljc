(ns dk.simongray.podcast.bytes-test
  "The bytes that the encryption stands on: the join and the comparison of
  bytes, the HMAC by the test cases of RFC 4231, base64 and random bytes,
  on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.bytes :as bytes]))

(deftest bytes-one-after-another
  (is (= "00ff10" (bytes/hex (bytes/join [(bytes/from-hex "00ff") (bytes/of []) (bytes/from-hex "10")]))))
  (is (= "" (bytes/hex (bytes/join []))))
  (is (= 70000 (bytes/length (bytes/join (repeat 7 (bytes/of (repeat 10000 1))))))))

(deftest the-same-bytes
  (is (bytes/same? (bytes/from-hex "00ff10") (bytes/from-hex "00ff10")))
  (is (bytes/same? (bytes/of []) (bytes/of [])))
  (is (not (bytes/same? (bytes/from-hex "00ff10") (bytes/from-hex "00ff11"))))
  (is (not (bytes/same? (bytes/from-hex "10ff10") (bytes/from-hex "00ff10"))))
  (is (not (bytes/same? (bytes/from-hex "00ff") (bytes/from-hex "00ff10")))))

(deftest the-test-cases-of-rfc-4231
  (testing "test case 1"
    (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
           (bytes/hex (bytes/hmac-sha256 (bytes/of (repeat 20 0x0b)) (bytes/encode "Hi There"))))))
  (testing "test case 2"
    (is (= "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
           (bytes/hex (bytes/hmac-sha256 (bytes/encode "Jefe") (bytes/encode "what do ya want for nothing?"))))))
  (is (= 32 (bytes/length (bytes/hmac-sha256 (bytes/of (repeat 200 1)) (bytes/of []))))
      "a key of more bytes than a block of the hash, and an empty message"))

(deftest base64-text
  (doseq [hex ["" "00" "00ff" "00ff10" "fffefdfc" "0102030405060708090a"]]
    (is (= hex (bytes/hex (bytes/from-base64 (bytes/base64 (bytes/from-hex hex))))) hex))
  (is (= "414243" (bytes/hex (bytes/from-base64 "QUJD"))))
  (doseq [s [nil 5 "A" "QUJ" "QUJD=" "QU=D" "=QUJ" "QUJ#" "QU JD" "Atф=" "A===" "QUJD\n"]]
    (is (nil? (bytes/from-base64 s)) (pr-str s)))
  (testing "a long text reads in a short time"
    (let [b (bytes/of (repeat 300000 7))]
      (is (true? (bytes/same? b (bytes/from-base64 (bytes/base64 b))))))))

(deftest bytes-that-nobody-can-guess
  (is (= 32 (bytes/length (bytes/random! 32))))
  (is (= 0 (bytes/length (bytes/random! 0))))
  (is (= 20 (count (distinct (repeatedly 20 #(bytes/hex (bytes/random! 16))))))))
