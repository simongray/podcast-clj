(ns dk.simongray.podcast.util.bytes-test
  "The bytes that the encryption stands on: the join and the comparison of
  bytes, the digests, the HMAC by the test cases of RFC 4231, base64 and
  random bytes, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.bytes :as bytes]))

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

(deftest the-digests
  (testing "SHA-1"
    (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (bytes/hex (bytes/sha1 (bytes/encode "abc")))))
    (is (= "da39a3ee5e6b4b0d3255bfef95601890afd80709" (bytes/hex (bytes/sha1 (bytes/of [])))))
    (is (= "924dff0812d0a21590dc8b4ab01f90138410c611" (bytes/hex (bytes/sha1 (bytes/encode "æøå"))))
        "the bytes of UTF-8")
    (is (= 20 (bytes/length (bytes/sha1 (bytes/of (repeat 1000 7)))))))
  (testing "SHA-256"
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (bytes/hex (bytes/sha256 (bytes/encode "abc")))))
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (bytes/hex (bytes/sha256 (bytes/of [])))))))

(deftest the-test-cases-of-rfc-4231
  (testing "test case 1"
    (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
           (bytes/hex (bytes/hmac :sha256 (bytes/of (repeat 20 0x0b)) (bytes/encode "Hi There"))))))
  (testing "test case 2"
    (is (= "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
           (bytes/hex (bytes/hmac :sha256 (bytes/encode "Jefe") (bytes/encode "what do ya want for nothing?"))))))
  (is (= 32 (bytes/length (bytes/hmac :sha256 (bytes/of (repeat 200 1)) (bytes/of []))))
      "a key of more bytes than a block of the hash, and an empty message")
  (testing "test case 1 by the other algorithms, and of RFC 2202 for SHA-1"
    (let [k (bytes/of (repeat 20 0x0b))
          m (bytes/encode "Hi There")]
      (is (= {:sha1   "b617318655057264e28bc0b6fb378c8ef146be00"
              :sha384 (str "afd03944d84895626b0825f4ab46907f15f9dadbe4101ec6"
                           "82aa034c7cebc59cfaea9ea9076ede7f4af152e8b2fa9cb6")
              :sha512 (str "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec278"
                           "7ad0b30545e17cdedaa833b7d6b8a702038b274eaea3f4e4"
                           "be9d914eeb61f1702e696c203a126854")}
             (into {} (for [a [:sha1 :sha384 :sha512]]
                        [a (bytes/hex (bytes/hmac a k m))])))))))

(deftest digests-by-algorithm
  (is (= {:sha384 (str "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded163"
                       "1a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7")
          :sha512 (str "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea2"
                       "0a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd"
                       "454d4423643ce80e2a9ac94fa54ca49f")}
         (into {} (for [a [:sha384 :sha512]]
                    [a (bytes/hex (bytes/digest a (bytes/encode "abc")))])))
      "the examples of FIPS 180-2"))

(deftest base64-of-urls
  (let [text #(bytes/base64url (bytes/encode %))]
    (is (= "aHR0cHM6Ly9mZWVkcy5wb2RjYXN0aW5kZXgub3JnL3BjMjAueG1s"
           (text "https://feeds.podcastindex.org/pc20.xml")))
    (is (= "Pz8_Pj4-" (text "???>>>")) "with - and _ in the place of + and /")
    (is (= ["YQ" "YWI" "YWJj"] (map text ["a" "ab" "abc"])) "and without padding"))
  (doseq [s ["" "a" "ab" "abc" "???>>>"]]
    (is (= s (let [b (bytes/from-base64url (bytes/base64url (bytes/encode s)))]
               (bytes/decode b 0 (bytes/length b) :utf8)))
        (pr-str s)))
  (is (= "616263" (bytes/hex (bytes/from-base64url "YWJj="))) "padding is fine too")
  (is (every? nil? (map bytes/from-base64url ["a+b" "a/b" "Y" "!" nil])) "what isn't base64url"))

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
  (is (= 32 (bytes/length (bytes/generate! 32))))
  (is (= 0 (bytes/length (bytes/generate! 0))))
  (is (= 20 (count (distinct (repeatedly 20 #(bytes/hex (bytes/generate! 16))))))))
