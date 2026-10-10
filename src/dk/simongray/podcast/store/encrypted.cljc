(ns dk.simongray.podcast.store.encrypted
  "Any document store, with its documents encrypted at rest.

  A server or a synced folder then holds only ciphertext, and nothing else
  changes. Names and versions pass through, and bodies are sealed on the
  way in and opened on the way out.

  A body is sealed with AES-256-GCM under a key derived from the passphrase
  by PBKDF2 with HMAC-SHA256. Each document is a small EDN envelope with
  the salt, IV and iteration count that open it, so there's no key file to
  lose:

      {:encrypted 1 :cipher \"AES-256-GCM\" :kdf \"PBKDF2-HMAC-SHA256\"
       :iterations 600000 :salt \"…\" :iv \"…\" :data \"…\"}

  A body that isn't an envelope comes back as it is, so you can turn on
  encryption over a folder that already holds plain documents. A
  passphrase that doesn't open a document makes that call of the store
  fail with ::locked.

  Since WebCrypto is asynchronous, the functions here and those of the
  store return promises in ClojureScript. Each platform opens the
  envelopes of the other."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [dk.simongray.podcast.crypto.aes-gcm :as aes-gcm]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def version
  "The version of the envelope."
  1)

(def default-iterations
  "The PBKDF2 iterations of a key, unless the options give others.

  It's what the OWASP Password Storage Cheat Sheet recommends for
  HMAC-SHA256 as of September 2026. On a phone, a key takes about half a
  second, once per salt."
  600000)

(def max-iterations
  "The most PBKDF2 iterations an envelope can ask for, ten times the
  default. A document that asks for more isn't taken for an envelope, since
  deriving its key would hold up a device for minutes."
  (* 10 default-iterations))

(defn- derive-key
  "The AES key for `passphrase` with `salt` over `iterations`. The
  passphrase is a string, or a hidden text of
  dk.simongray.wary-fetch.secret."
  [passphrase salt iterations]
  (aes-gcm/derive-key (secret/reveal passphrase) salt iterations))

(defn- locked
  [name cause]
  (ex-info (str "The passphrase does not open " (or name "the document"))
           {:type ::locked :name name}
           cause))

(defn- open
  "The plaintext bytes of `ciphertext` sealed with `key` under `iv`, in
  ClojureScript as a promise. With the wrong key, it throws ::locked for
  the document `name`."
  [key iv ciphertext name]
  #?(:clj  (try
             (aes-gcm/open key iv ciphertext)
             (catch Exception e
               (throw (locked name e))))
     :cljs (.catch (aes-gcm/open key iv ciphertext)
                   (fn [e] (throw (locked name e))))))

(def ^:no-doc base64-form
  "Text in base64 with its padding."
  #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

(defn- base64?
  [x]
  (boolean (and (string? x)
                (seq x)
                (re-find base64-form x))))

(defn envelope
  "The envelope in the string `s`, or nil for anything else, a plain
  document included.

  An envelope is a map of :encrypted, :cipher, :kdf, :iterations, :salt,
  :iv and :data. The last three are in base64, and the iterations are at
  least one and at most max-iterations."
  [s]
  (when (and (string? s) (str/starts-with? s "{:encrypted"))
    (let [m (try
              (edn/read-string s)
              (catch #?(:clj Throwable :cljs :default) _
                nil))]
      (when (and (map? m)
                 (integer? (:encrypted m))
                 (every? base64? ((juxt :salt :iv :data) m))
                 (integer? (:iterations m))
                 (<= 1 (:iterations m) max-iterations))
        m))))

(defn- seal-with
  "The envelope string for `body`, text or bytes, sealed with a fresh IV
  under the key that `key-for` derives for the `salt` bytes over
  `iterations`."
  [key-for salt iterations body]
  (let [iv (bytes/generate! 12)]
    (async/then (key-for salt iterations)
                (fn [key]
                  (async/then (aes-gcm/seal key iv (bytes/as body :bytes))
                              (fn [data]
                                (pr-str {:encrypted  version
                                         :cipher     "AES-256-GCM"
                                         :kdf        "PBKDF2-HMAC-SHA256"
                                         :iterations iterations
                                         :salt       (bytes/base64 salt)
                                         :iv         (bytes/base64 iv)
                                         :data       (bytes/base64 data)})))))))

(defn- open-with
  "The body in the envelope string `s` of the document `name`, opened with
  the key that `key-for` derives for it, as text, or as bytes when `as` is
  :bytes. A string that isn't an envelope comes back as it is."
  [key-for name s as]
  (if-let [{:keys [iterations salt iv data]} (envelope s)]
    (async/then (key-for (bytes/from-base64 salt) iterations)
                (fn [key]
                  (async/then (open key
                                    (bytes/from-base64 iv)
                                    (bytes/from-base64 data)
                                    name)
                              #(bytes/as % as))))
    (bytes/as s as)))

(defn encrypt
  "The envelope string for `body` under `passphrase`, with a fresh salt and
  IV and the :iterations of `opts`, in ClojureScript as a promise. The
  iterations default to default-iterations."
  ([body passphrase]
   (encrypt body passphrase {}))
  ([body passphrase {:keys [iterations]
                     :or   {iterations default-iterations}
                     :as   opts}]
   {:pre [(map? opts)]}
   (let [salt (bytes/generate! 16)]
     (seal-with #(derive-key passphrase %1 %2) salt iterations body))))

(defn decrypt
  "The body in the envelope string `s` under `passphrase`, or `s` itself
  when it isn't an envelope, in ClojureScript as a promise.

  A passphrase that doesn't open it throws ::locked, or in ClojureScript,
  rejects the promise with it."
  [s passphrase]
  (open-with #(derive-key passphrase %1 %2) nil s nil))

(defn store
  "The store `inner` with its documents encrypted at rest under
  `passphrase`, with the `opts` below.

  The `passphrase` is a string or a function of no arguments that returns
  one. The string can be a hidden text of
  dk.simongray.wary-fetch.secret. The `opts` are:

  - :iterations, the PBKDF2 iterations of the key, default-iterations by
    default
  - :except, the names of documents to leave in the clear, e.g.
    subscriptions.opml, a subscription list that other apps should read

  A key is derived once for each salt and iteration count, and cached.
  Each store seals with a salt of its own, and opens any salt that a
  document has. It writes, lists and deletes only when `inner` does.

  When the passphrase is a function, a document that the cached keys
  don't open empties the cache. The document is then tried once more with
  what the function gives, so that a corrected passphrase is used from
  then on."
  ([inner passphrase]
   (store inner passphrase {}))
  ([inner passphrase {:keys [iterations except]
                      :or   {iterations default-iterations except #{}}
                      :as   opts}]
   {:pre [(map? opts)]}
   (when (nil? passphrase)
     (throw (ex-info "An encrypting store needs a passphrase" {})))
   (let [salt    (bytes/generate! 16)
         keys    (atom {})
         pass    (fn [] (if (fn? passphrase) (passphrase) passphrase))
         key-for (fn [salt iterations]
                   (let [k [(bytes/base64 salt) iterations]]
                     (or (get @keys k)
                         (let [key (derive-key (pass) salt iterations)]
                           (swap! keys assoc k key)
                           key))))
         open    (fn [name doc as]
                   (async/then (open-with key-for name (:body doc) as)
                               #(assoc doc :body %)))
         retried (fn [name doc as {:keys [value error]}]
                   (cond
                     (not error)
                     value

                     (fn? passphrase)
                     (do (reset! keys {})
                         (open name doc as))

                     :else
                     (throw error)))
         opened  (fn [name doc as]
                   (cond
                     (nil? doc)
                     nil

                     (except name)
                     doc

                     :else
                     (async/then (async/attempt #(open name doc as))
                                 #(retried name doc as %))))]
     (cond-> {:name  (:name inner)
              ;; an envelope is text, and a document left in the clear is
              ;; fetched as asked
              :fetch (fn [name opts]
                       (async/then ((:fetch inner) name (if (except name)
                                                          opts
                                                          {}))
                                   #(opened name % (:as opts))))}
       (:put inner)
       (assoc :put (fn [name body params]
                     (if (except name)
                       ((:put inner) name body params)
                       (async/then (seal-with key-for salt iterations body)
                                   (fn [sealed]
                                     ((:put inner) name sealed params))))))

       (:list inner)   (assoc :list (:list inner))
       (:delete inner) (assoc :delete (:delete inner))))))

#?(:clj
   (comment
     (def sealed
       (encrypt "{:version 1 :state {}}" "correct horse battery staple"
                {:iterations 1000}))
     (decrypt sealed "correct horse battery staple")
     (require '[dk.simongray.podcast.store :as store])
     (store/check! (store (store/memory) "hunter2" {:iterations 1000}))
     #_.))
