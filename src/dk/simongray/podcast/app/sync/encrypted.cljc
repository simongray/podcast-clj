(ns dk.simongray.podcast.app.sync.encrypted
  "Any document store with its documents encrypted at rest, so that a
  Nextcloud, a Dropbox or the inspector's folder holds a listener's habits
  as ciphertext and nothing else changes: `store` wraps a store, names and
  versions pass through, bodies are sealed on the way in and opened on the
  way out, and `backend` is the one call with the options of both.

  A body is sealed with AES-256-GCM under a key derived from the passphrase
  by PBKDF2 with HMAC-SHA256, and stored as a small EDN envelope carrying
  its own salt, IV and iteration count, so that there is no key file to
  lose and a document tells how to open it:

      {:encrypted 1 :cipher \"AES-256-GCM\" :kdf \"PBKDF2-HMAC-SHA256\"
       :iterations 600000 :salt \"…\" :iv \"…\" :data \"…\"}

  A body that is not an envelope comes back as it is, so encryption can be
  turned on over a folder that already holds plain documents; a passphrase
  that does not open a document fails that store's call with `::locked`.
  The JVM uses javax.crypto and ClojureScript the platform's WebCrypto,
  which is asynchronous, so there `encrypt`, `decrypt` and the store's
  functions return promises, as the store contract allows. The two
  platforms open each other's envelopes."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [dk.simongray.podcast.app.sync.store :as store])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.security SecureRandom]
                   [java.util Base64]
                   [javax.crypto Cipher SecretKeyFactory]
                   [javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec])))

(def version
  "The version of the envelope."
  1)

(def default-iterations
  "The PBKDF2 iterations a key is derived with unless told otherwise: what
  OWASP recommends for HMAC-SHA256, about half a second on a phone once
  per salt."
  600000)

(defn- ->base64
  [bytes]
  #?(:clj  (.encodeToString (Base64/getEncoder) ^bytes bytes)
     :cljs (let [n     (.-length bytes)
                 parts (js/Array.)]
             (loop [i 0]
               (when (< i n)
                 (.push parts (.apply js/String.fromCharCode nil (.subarray bytes i (min n (+ i 0x8000)))))
                 (recur (+ i 0x8000))))
             (js/btoa (.join parts "")))))

(defn- <-base64
  [s]
  #?(:clj  (.decode (Base64/getDecoder) ^String s)
     :cljs (let [binary (js/atob s)
                 out    (js/Uint8Array. (.-length binary))]
             (dotimes [i (.-length binary)]
               (aset out i (.charCodeAt binary i)))
             out)))

(defn- random-bytes
  [n]
  #?(:clj  (let [b (byte-array n)] (.nextBytes (SecureRandom.) b) b)
     :cljs (.getRandomValues js/crypto (js/Uint8Array. n))))

(defn- utf8
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

(defn- from-utf8
  [bytes]
  #?(:clj  (String. ^bytes bytes StandardCharsets/UTF_8)
     :cljs (.decode (js/TextDecoder.) bytes)))

(defn- derive-key
  "The AES key for `passphrase` with `salt` over `iterations` of PBKDF2 with
  HMAC-SHA256; a promise of one in ClojureScript."
  [passphrase salt iterations]
  #?(:clj  (let [factory (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
                 spec    (PBEKeySpec. (.toCharArray ^String passphrase) ^bytes salt (int iterations) 256)]
             (SecretKeySpec. (.getEncoded (.generateSecret factory spec)) "AES"))
     :cljs (let [subtle (.-subtle js/crypto)]
             (-> (.importKey subtle "raw" (utf8 passphrase) #js {:name "PBKDF2"} false #js ["deriveKey"])
                 (.then (fn [base]
                          (.deriveKey subtle
                                      #js {:name "PBKDF2" :salt salt :iterations iterations :hash "SHA-256"}
                                      base
                                      #js {:name "AES-GCM" :length 256}
                                      false
                                      #js ["encrypt" "decrypt"])))))))

(defn- seal
  "`plaintext` bytes sealed with `key` under `iv`: the ciphertext with its
  tag; a promise of it in ClojureScript."
  [key iv plaintext]
  #?(:clj  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
             (.init cipher Cipher/ENCRYPT_MODE ^SecretKeySpec key (GCMParameterSpec. 128 ^bytes iv))
             (.doFinal cipher ^bytes plaintext))
     :cljs (-> (.encrypt (.-subtle js/crypto) #js {:name "AES-GCM" :iv iv} key plaintext)
               (.then #(js/Uint8Array. %)))))

(defn- locked
  [name cause]
  (ex-info (str "The passphrase does not open " (or name "the document"))
           {:type ::locked :name name}
           cause))

(defn- open
  "The plaintext bytes of `ciphertext` sealed with `key` under `iv`, or
  `::locked` when the key is not the one; a promise in ClojureScript."
  [key iv ciphertext name]
  #?(:clj  (try
             (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
               (.init cipher Cipher/DECRYPT_MODE ^SecretKeySpec key (GCMParameterSpec. 128 ^bytes iv))
               (.doFinal cipher ^bytes ciphertext))
             (catch Exception e
               (throw (locked name e))))
     :cljs (-> (.decrypt (.-subtle js/crypto) #js {:name "AES-GCM" :iv iv} key ciphertext)
               (.then #(js/Uint8Array. %))
               (.catch (fn [e] (throw (locked name e)))))))

(defn envelope
  "The envelope in the string `s`, when it is one: `{:encrypted :cipher :kdf
  :iterations :salt :iv :data}`; nil for anything else, a plain document
  included."
  [s]
  (when (and (string? s) (str/starts-with? s "{:encrypted"))
    (let [m (try (edn/read-string s) (catch #?(:clj Exception :cljs :default) _ nil))]
      (when (and (map? m) (integer? (:encrypted m)) (string? (:salt m)) (string? (:iv m)) (string? (:data m)))
        m))))

(defn- seal-body
  "The envelope string for `body` under `key`, derived from `salt` over
  `iterations`."
  [key salt iterations body]
  (let [iv (random-bytes 12)]
    (store/then (seal key iv (utf8 body))
                (fn [data]
                  (pr-str {:encrypted  version
                           :cipher     "AES-256-GCM"
                           :kdf        "PBKDF2-HMAC-SHA256"
                           :iterations iterations
                           :salt       (->base64 salt)
                           :iv         (->base64 iv)
                           :data       (->base64 data)})))))

(defn encrypt
  "The envelope string for `body` under `passphrase`, with a fresh salt and
  IV; `opts` may set the `:iterations` (default `default-iterations`). A
  promise of it in ClojureScript."
  ([passphrase body]
   (encrypt passphrase body {}))
  ([passphrase body {:keys [iterations] :or {iterations default-iterations} :as opts}]
   {:pre [(map? opts)]}
   (let [salt (random-bytes 16)]
     (store/then (derive-key passphrase salt iterations)
                 (fn [key] (seal-body key salt iterations body))))))

(defn decrypt
  "The body in the envelope string `s` under `passphrase`, or `s` itself when
  it is not an envelope; a passphrase that does not open it throws
  `::locked`. A promise in ClojureScript, rejected when locked."
  [passphrase s]
  (if-let [{:keys [iterations salt iv data]} (envelope s)]
    (store/then (derive-key passphrase (<-base64 salt) iterations)
                (fn [key] (store/then (open key (<-base64 iv) (<-base64 data) nil) from-utf8)))
    s))

(defn store
  "The store `inner` with its documents encrypted at rest under the
  `:passphrase` in `opts`, a string or a function of no arguments returning
  one. `:iterations` (default `default-iterations`) derives the key, once
  per salt and cached; each store instance seals with a salt of its own,
  and opens whatever salt a document carries. `:except` names documents
  left in the clear, such as `dk.simongray.podcast.app.sync.store/opml-name`
  for a subscription list other apps should read. Listing and deleting are
  `inner`'s as they are."
  [inner {:keys [passphrase iterations except] :or {iterations default-iterations except #{}} :as opts}]
  {:pre [(map? opts)]}
  (let [salt    (random-bytes 16)
        keys    (atom {})
        pass    (fn [] (if (fn? passphrase) (passphrase) passphrase))
        key-for (fn [salt-bytes salt-b64 iters]
                  (or (get @keys salt-b64)
                      (let [key (derive-key (pass) salt-bytes iters)]
                        (swap! keys assoc salt-b64 key)
                        key)))
        seal!   (fn [body]
                  (store/then (key-for salt (->base64 salt) iterations)
                              (fn [key] (seal-body key salt iterations body))))
        open!   (fn [name body]
                  (if-let [{:keys [iterations] :as env} (envelope body)]
                    (store/then (key-for (<-base64 (:salt env)) (:salt env) iterations)
                                (fn [key] (store/then (open key (<-base64 (:iv env)) (<-base64 (:data env)) name) from-utf8)))
                    body))]
    (cond-> {:name  (:name inner)
             :fetch (fn [name]
                      (store/then ((:fetch inner) name)
                                  (fn [doc]
                                    (cond
                                      (nil? doc)    nil
                                      (except name) doc
                                      :else         (store/then (open! name (:body doc)) #(assoc doc :body %))))))
             :put   (fn [name body params]
                      (if (except name)
                        ((:put inner) name body params)
                        (store/then (seal! body) (fn [sealed] ((:put inner) name sealed params)))))}
      (:list inner)   (assoc :list (:list inner))
      (:delete inner) (assoc :delete (:delete inner)))))

(defn backend
  "A backend for `dk.simongray.podcast.app.sync/sync!` over `inner` with its
  documents encrypted, in one call: `opts` are those of `store`, the
  `:passphrase`, `:iterations` and `:except`, and those of
  `dk.simongray.podcast.app.sync.store/backend`."
  [inner opts]
  {:pre [(map? opts)]}
  (store/backend (store inner (select-keys opts [:passphrase :iterations :except]))
                 (select-keys opts [:opml? :app :direction :strategy :document :name])))

#?(:clj
   (comment
     (def sealed (encrypt "correct horse battery staple" "{:version 1 :state {}}" {:iterations 1000}))
     (decrypt "correct horse battery staple" sealed)
     (store/check! (store (store/memory) {:passphrase "hunter2" :iterations 1000}))
     #_.))
