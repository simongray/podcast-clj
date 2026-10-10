(ns dk.simongray.podcast.crypto.aes-gcm-test
  "AES-256-GCM under a key from PBKDF2, on both platforms: a round trip, a
  key that doesn't open, a changed ciphertext, and on the JVM the PBKDF2
  example of RFC 7914."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.crypto.aes-gcm :as aes-gcm]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.wary-fetch.async :as async]))

(def salt (bytes/of (range 16)))

(def iv (bytes/of (range 12)))

(defn- opened
  "The text of `sealed` opened with the key of `passphrase`, or the
  :error, deferred."
  [passphrase sealed]
  (async/then (aes-gcm/derive-key passphrase salt 1000)
              (fn [k]
                (async/then (async/attempt #(aes-gcm/open k iv sealed))
                            (fn [{:keys [value error]}]
                              (if error
                                {:error error}
                                (bytes/as value :text)))))))

(deftest sealing-and-opening
  (let [sealed (atom nil)]
    (run #(async/then (aes-gcm/derive-key "hunter2" salt 1000)
                      (fn [k]
                        (async/then (aes-gcm/seal k iv (bytes/encode "a secret"))
                                    (partial reset! sealed))))
         #(async/then (opened "hunter2" @sealed)
                      (fn [text]
                        (is (= "a secret" text))
                        (is (= 24 (bytes/length @sealed)) "the text and a tag of 16 bytes")))
         #(async/then (opened "hunter3" @sealed)
                      (fn [result]
                        (is (:error result) "another key doesn't open it")))
         #(let [changed (bytes/copy @sealed 0 24)]
            (aset changed 0 (unchecked-byte (bit-xor 1 (bytes/at changed 0))))
            (async/then (opened "hunter2" changed)
                        (fn [result]
                          (is (:error result) "nor does a changed ciphertext")))))))

#?(:clj
   (deftest the-pbkdf2-example-of-rfc-7914
     (is (= "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
            (bytes/hex (.getEncoded ^javax.crypto.spec.SecretKeySpec
                                    (aes-gcm/derive-key "passwd" (bytes/encode "salt") 1))))
         "section 11, the first 32 bytes")))
