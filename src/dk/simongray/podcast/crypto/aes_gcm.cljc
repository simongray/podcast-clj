(ns dk.simongray.podcast.crypto.aes-gcm
  "AES-256-GCM under a key derived from a passphrase, on both platforms.

  The JVM uses javax.crypto, and ClojureScript uses WebCrypto, which is
  asynchronous, so there each function gives a promise. Each platform
  opens what the other seals. The sources:

  - NIST SP 800-38D, the Galois/Counter Mode, here with an IV of 96 bits
    and a tag of 128 bits
  - RFC 8018, section 5.2, PBKDF2, here with HMAC-SHA256"
  (:require [dk.simongray.podcast.util.bytes :as bytes])
  #?(:clj (:import [javax.crypto Cipher SecretKeyFactory]
                   [javax.crypto.spec
                    GCMParameterSpec PBEKeySpec SecretKeySpec])))

(defn derive-key
  "The AES-256 key for the text `passphrase` with the bytes `salt`, over
  `iterations` of PBKDF2 with HMAC-SHA256, in ClojureScript as a promise."
  [passphrase salt iterations]
  #?(:clj  (let [factory (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
                 chars   (.toCharArray ^String passphrase)
                 spec    (PBEKeySpec. chars ^bytes salt (int iterations) 256)
                 derived (.getEncoded (.generateSecret factory spec))]
             (SecretKeySpec. derived "AES"))
     :cljs (let [subtle (.-subtle js/crypto)]
             (-> (.importKey subtle "raw" (bytes/encode passphrase)
                             #js {:name "PBKDF2"} false #js ["deriveKey"])
                 (.then (fn [base]
                          (.deriveKey subtle
                                      #js {:name       "PBKDF2"
                                           :salt       salt
                                           :iterations iterations
                                           :hash       "SHA-256"}
                                      base
                                      #js {:name "AES-GCM" :length 256}
                                      false
                                      #js ["encrypt" "decrypt"])))))))

(defn seal
  "The bytes `plaintext` sealed with `key` under the 12 bytes `iv`, as the
  ciphertext with its tag, in ClojureScript as a promise. An IV must never
  seal twice under one key."
  [key iv plaintext]
  #?(:clj  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
                 spec   (GCMParameterSpec. 128 ^bytes iv)]
             (.init cipher Cipher/ENCRYPT_MODE ^SecretKeySpec key spec)
             (.doFinal cipher ^bytes plaintext))
     :cljs (let [params #js {:name "AES-GCM" :iv iv}]
             (-> (.encrypt (.-subtle js/crypto) params key plaintext)
                 (.then #(js/Uint8Array. %))))))

(defn open
  "The plaintext of the bytes `ciphertext` sealed with `key` under `iv`, in
  ClojureScript as a promise. It throws, or rejects the promise, when the
  key is another or the ciphertext was changed."
  [key iv ciphertext]
  #?(:clj  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
                 spec   (GCMParameterSpec. 128 ^bytes iv)]
             (.init cipher Cipher/DECRYPT_MODE ^SecretKeySpec key spec)
             (.doFinal cipher ^bytes ciphertext))
     :cljs (let [params #js {:name "AES-GCM" :iv iv}]
             (-> (.decrypt (.-subtle js/crypto) params key ciphertext)
                 (.then #(js/Uint8Array. %))))))

#?(:clj
   (comment
     (def k
       (derive-key "correct horse battery staple" (bytes/generate! 16) 1000))
     (def iv (bytes/generate! 12))
     (bytes/as (open k iv (seal k iv (bytes/encode "hello"))) :text)
     #_.))
