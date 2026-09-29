(ns dk.simongray.podcast.nip44-test
  "The encrypted payloads of NIP-44 by the test vectors of its authors, on
  both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.nip44 :as nip44]
            [dk.simongray.podcast.secp256k1 :as secp256k1]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def vectors
  "A part of the test vectors of NIP-44, version 2. The file tells where
  they are from."
  (get (json/read-str (fixture "nostr/nip44-vectors.json")) "v2"))

(defn- sha256
  "The SHA-256 of the text `s`, in hexadecimal."
  [s]
  (bytes/hex (bytes/sha256 (bytes/encode s))))

(deftest the-key-of-a-conversation
  (doseq [{:strs [sec1 pub2 conversation_key note]} (get-in vectors ["valid" "get_conversation_key"])]
    (is (= conversation_key (nip44/conversation-key sec1 pub2)) (str note)))
  (doseq [{:strs [sec1 pub2 note]} (get-in vectors ["invalid" "get_conversation_key"])]
    (is (nil? (nip44/conversation-key sec1 pub2)) note))
  (testing "the two owners get one key"
    (let [ann "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a"
          bob "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d"]
      (is (= "3e2b52a63be47d34fe0a80e34e73d436d6963bc8f39827f327057a9986c20a45"
             (nip44/conversation-key ann (secp256k1/public-key bob))
             (nip44/conversation-key bob (secp256k1/public-key ann))
             (nip44/conversation-key (str/upper-case bob) (str/upper-case (secp256k1/public-key ann)))))))
  (doseq [[secret public] [[nil nil] ["01" "02"] [5 5] ["" ""]]]
    (is (nil? (nip44/conversation-key secret public)) (pr-str secret))))

(deftest the-keys-of-a-payload
  (let [{:strs [conversation_key keys]} (get-in vectors ["valid" "get_message_keys"])]
    (is (= 5 (count keys)))
    (doseq [{:strs [nonce chacha_key chacha_nonce hmac_key]} keys]
      (is (= {:cipher-key chacha_key :cipher-nonce chacha_nonce :check-key hmac_key}
             (update-vals (nip44/message-keys conversation_key nonce) bytes/hex))
          nonce))))

(deftest the-length-with-padding
  (doseq [[n padded] (get-in vectors ["valid" "calc_padded_len"])]
    (is (= padded (nip44/padded-length n)) n))
  (is (= [32 32 32 64 81920] (map nip44/padded-length [1 2 31 63 65537]))))

(deftest payloads-that-the-key-opens
  (doseq [{:strs [sec1 sec2 conversation_key nonce plaintext payload]} (get-in vectors ["valid" "encrypt_decrypt"])]
    (testing plaintext
      (is (= conversation_key (nip44/conversation-key sec1 (secp256k1/public-key sec2))))
      (is (= payload (nip44/encrypt plaintext conversation_key nonce)))
      (is (= plaintext (nip44/decrypt payload (nip44/conversation-key sec2 (secp256k1/public-key sec1)))))
      (is (= 2 (nip44/payload-version payload))))))

(deftest long-texts
  (doseq [k ["encrypt_decrypt_long_msg" "extended_length"]
          {:strs [conversation_key nonce pattern repeat plaintext_sha256 payload_sha256]} (get-in vectors ["valid" k])]
    (testing (str repeat " times " pattern)
      (let [text    (apply str (clojure.core/repeat repeat pattern))
            opts    {:max-bytes 100000}
            payload (nip44/encrypt text conversation_key nonce opts)]
        (is (= plaintext_sha256 (sha256 text)))
        (is (= payload_sha256 (sha256 payload)))
        (is (true? (= text (nip44/decrypt payload conversation_key opts))))
        (when (< 65535 (bytes/length (bytes/encode text)))
          (is (nil? (nip44/encrypt text conversation_key nonce)) "above the limit that the options do not set")
          (is (nil? (nip44/decrypt payload conversation_key))))))))

(deftest payloads-that-the-key-does-not-open
  (doseq [{:strs [conversation_key payload note]} (get-in vectors ["invalid" "decrypt"])]
    (is (nil? (nip44/decrypt payload conversation_key)) note))
  (testing "the version of a payload"
    (let [by-note (into {} (map (juxt #(get % "note") #(get % "payload"))) (get-in vectors ["invalid" "decrypt"]))]
      (is (nil? (nip44/payload-version (by-note "unknown encryption version"))) "a form that comes after version 2")
      (is (= 0 (nip44/payload-version (by-note "unknown encryption version 0"))))
      (is (= 2 (nip44/payload-version (by-note "invalid MAC"))))
      (is (nil? (nip44/payload-version "")))
      (is (nil? (nip44/payload-version nil)))))
  (testing "a payload that somebody changed, and the key of another conversation"
    (let [key     "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"
          other   "3e2b52a63be47d34fe0a80e34e73d436d6963bc8f39827f327057a9986c20a45"
          payload (nip44/encrypt "Pay 50 sats" key (apply str (repeat 64 "7")))
          changed (fn [i] (str (subs payload 0 i) (if (= \A (nth payload i)) "B" "A") (subs payload (inc i))))]
      (is (= "Pay 50 sats" (nip44/decrypt payload key)))
      (is (nil? (nip44/decrypt payload other)))
      (doseq [i [2 40 60 100 (- (count payload) 3)]]
        (is (nil? (nip44/decrypt (changed i) key)) (str "the character " i)))
      (doseq [x [nil 5 "" "abc" (str payload payload)]]
        (is (nil? (nip44/decrypt x key)) (pr-str x))))))

(deftest texts-that-have-no-payload
  (let [key   "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"
        nonce (apply str (repeat 64 "0"))]
    (is (nil? (nip44/encrypt "" key nonce)) "a text without a byte")
    (is (nil? (nip44/encrypt (apply str (repeat 65536 "a")) key nonce)))
    (is (some? (nip44/encrypt (apply str (repeat 65535 "a")) key nonce)))
    (is (nil? (nip44/encrypt "abc" key nonce {:max-bytes 2})))
    (doseq [[k n] [["abc" nonce] [key "abc"] [nil nonce] [key nil]]]
      (is (thrown? #?(:clj Throwable :cljs js/Error) (nip44/encrypt "a" k n)) (pr-str [k n])))))
