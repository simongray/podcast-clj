(ns dk.simongray.podcast.bech32-test
  "Bech32 by the test vectors of BIP-173 and the examples of NIP-19, on
  both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.bech32 :as bech32]
            [dk.simongray.podcast.bytes :as bytes]))

(def valid
  "The valid texts of the section Test vectors of BIP-173."
  ["A12UEL5L"
   "a12uel5l"
   "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs"
   "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw"
   "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j"
   "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w"
   "?1ezyfcl"])

(def invalid
  "The texts of BIP-173 that are no Bech32, with the reason of each."
  {" 1nwldj5"   "a character of the prefix out of range"
   "\u007F1axkwrx"   "a character of the prefix out of range"
   "pzry9x0s0muk"    "no separator"
   "1pzry9x0s0muk"   "an empty prefix"
   "x1b4n0q5v"       "a character that is no data"
   "li1dgmt3"        "a checksum that is too short"
   "de1lg7wtÿ"  "a character of the checksum that is no data"
   "A1G7SGD8"        "a checksum of the prefix in upper case"
   "10a06t8"         "an empty prefix"
   "1qzzfhee"        "an empty prefix"
   "A12uEL5L"        "upper and lower case in one text"
   "an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx"
   "a text of more than 90 characters"})

(deftest the-vectors-of-bip-173
  (doseq [s valid]
    (is (some? (bech32/decode s)) s))
  (doseq [[s reason] invalid]
    (is (nil? (bech32/decode s)) reason))
  (is (= ["a" 0] ((juxt :prefix (comp bytes/length :bytes)) (bech32/decode "A12UEL5L")))
      "a prefix in lower case, and no data"))

(deftest the-names-of-nip-19
  (let [hex "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        text "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"]
    (is (= text (bech32/encode "npub" (bytes/from-hex hex))))
    (is (= text (bech32/encode "NPUB" (bytes/from-hex hex))) "in lower case")
    (is (= "npub" (:prefix (bech32/decode text))))
    (is (= hex (bytes/hex (:bytes (bech32/decode text)))))
    (is (= hex (bytes/hex (:bytes (bech32/decode (str/upper-case text))))) "a text in upper case")
    (is (nil? (bech32/decode (str (subs text 0 62) "7"))) "a wrong checksum")
    (is (nil? (bech32/decode (str/replace-first text "8" "7"))) "a wrong character")
    (is (= "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
           (bytes/hex (:bytes (bech32/decode "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg")))))))

(deftest texts-of-any-length
  (doseq [n [0 1 2 31 32 33 64 255 1000]]
    (let [b    (bytes/of (map #(mod (* 7 %) 256) (range n)))
          text (bech32/encode "test" b)]
      (is (= (bytes/hex b) (some-> (bech32/decode text {:max-length 5000}) :bytes bytes/hex)) (str n " bytes"))))
  (testing "the limit of the length"
    (let [text (bech32/encode "test" (bytes/of (range 100)))]
      (is (< 90 (count text)))
      (is (nil? (bech32/decode text)))
      (is (some? (bech32/decode text {:max-length (count text)})))
      (is (nil? (bech32/decode text {:max-length (dec (count text))})))))
  (doseq [x [nil 5 "" "1" :npub []]]
    (is (nil? (bech32/decode x)) (pr-str x))))

(deftest data-that-are-no-whole-bytes
  (testing "the numbers of five bits of a text, without its checksum"
    (is (= {:prefix "a" :words []} (bech32/words "A12UEL5L")))
    (is (= {:prefix "abcdef" :words (vec (range 32))}
           (bech32/words "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw")))
    (is (= "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw" (bech32/of-words "ABCDEF" (range 32)))))
  (testing "numbers that make no whole bytes have a text, and the text has no bytes"
    (let [text (bech32/of-words "test" [31 0 31])]
      (is (= {:prefix "test" :words [31 0 31]} (bech32/words text)))
      (is (nil? (bech32/decode text)))))
  (doseq [n [0 1 7 8 52 111 400]]
    (let [words (mapv #(mod (* 11 %) 32) (range n))
          text  (bech32/of-words "lnbc" words)]
      (is (= {:prefix "lnbc" :words words} (bech32/words text {:max-length 1000})) (str n " numbers"))))
  (doseq [x [nil 5 "" "1" "a12uel5m" "A12uEL5L"]]
    (is (nil? (bech32/words x)) (pr-str x))))
