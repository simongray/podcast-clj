(ns dk.simongray.podcast.nostr.signer
  "Signers of events with the listener's Nostr key: a key that the app
  holds, a browser extension of NIP-07, and a remote signer of NIP-46.

  A signer is a map of two functions, each of which gives a value, or in
  ClojureScript a promise of one:

  - :public-key-fn, of no arguments, which gives the public key in
    hexadecimal, or a map with a diagnostic under :error
  - :sign-fn, of an event of NIP-01 without its id, public key and
    signature, which gives the signed event, or a map with a diagnostic
    under :error

  Sign with sign!, which checks that a signer signed what it was given.
  A secret key is a hidden text of dk.simongray.wary-fetch.secret, so it
  doesn't show in a log or at a REPL."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.crypto.nip44 :as nip44]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]))

(defn- unreadable
  "A map of the diagnostic of an answer of a signer that can't be read,
  for the `reason`."
  [reason]
  (diagnostics/failed {:id :nostr/signer-unreadable :reason reason}))

;; NIP-19, the name nsec of a secret key
(defn secret-key
  "The secret key that the text `s` names, as a hidden text, or nil for
  another text. The text is nsec1… of NIP-19 or hexadecimal text of 32
  bytes, and it can be hidden itself."
  [s]
  (let [text (some-> (secret/reveal s) str str/trim str/lower-case)
        hex  (if (some-> text (str/starts-with? "nsec1"))
               (let [{:keys [prefix] b :bytes} (bech32/decode text)]
                 (when (and (= "nsec" prefix) (= 32 (bytes/length b)))
                   (bytes/hex b)))
               text)]
    (when (and hex (secp256k1/public-key hex))
      (secret/hide hex))))

(defn nsec
  "The name nsec1… of NIP-19 of the hidden `secret-key`, as a hidden text,
  for the listener to keep, or to take to another app."
  [secret-key]
  (secret/hide (bech32/encode "nsec"
                              (bytes/from-hex (secret/reveal secret-key)))))

(defn public-key
  "The public key of the hidden `secret-key`, in hexadecimal, or nil when
  it isn't a secret key."
  [secret-key]
  (secp256k1/public-key (secret/reveal secret-key)))

(defn generate-key!
  "Generate a new secret key, from the generator of the platform for keys,
  as a hidden text."
  []
  (loop []
    (let [hex (bytes/hex (bytes/generate! 32))]
      (if (secp256k1/public-key hex)
        (secret/hide hex)
        (recur)))))

(defn local
  "A signer over the hidden `secret-key`, which signs on the device, with
  the `opts` below. It throws for a text that isn't a secret key.

  - :random-fn, a function that gives a number of random bytes, for the
    aux of each signature of BIP-340, from a secure source by default"
  ([secret-key]
   (local secret-key {}))
  ([secret-key {:keys [random-fn] :or {random-fn bytes/generate!} :as opts}]
   (let [public (public-key secret-key)]
     (when-not public
       (throw (ex-info "The key is no secret key of Nostr" {})))
     {:public-key-fn (constantly public)
      :sign-fn       (fn [event]
                       (nostr/signed event
                                     (secret/reveal secret-key)
                                     (bytes/hex (random-fn 32))))})))

#?(:cljs
   (defn nip07
     "A signer over the provider of NIP-07 that a browser extension gives
     the page as window.nostr, or the :provider of `opts`, or nil when the
     page has none. The extension asks the listener, and signs with a key
     that the page never sees."
     ([]
      (nip07 {}))
     ([{:keys [provider] :as opts}]
      (when-let [^js p (or provider (some-> js/globalThis .-nostr))]
        (let [refused #(diagnostics/failed {:id     :nostr/signer-refused
                                            :reason (str %)})]
          {:public-key-fn (fn []
                            (-> (.getPublicKey p)
                                (.then str)
                                (.catch refused)))
           :sign-fn       (fn [event]
                            (-> (.signEvent p (clj->js event))
                                (.then js->clj)
                                (.catch refused)))})))))

(def kind
  "The kind of the events of NIP-46, of the requests and of the answers."
  24133)

(def default-limits
  "The limits of a request to a remote signer that its options don't set.
  A remote signer can ask the listener first, so it has :timeout-seconds
  to answer."
  {:timeout-seconds 60})

;; NIP-46, the section Direct connection initiated by remote-signer
(defn parse
  "The connection that the bunker:// URI `uri` of NIP-46 names, or nil for
  another text.

      {:remote-signer \"fa98…\"          ; the public key of the signer
       :relays        [\"wss://relay.example\"]
       :secret        #<secret>}       ; for the first connect, if any

  Add the hidden secret key of the app as :client-key, e.g. from
  generate-key!, and keep it, since the remote signer knows the app by
  its public key. The secret of the URI works for one connect only."
  [uri]
  (let [form  #"(?i)bunker://([0-9a-f]{64})/?\??(.*)"
        found (when (string? uri) (re-matches form (str/trim uri)))]
    (when-let [[_ remote query] found]
      (let [params (url/query-pairs query)
            named  (fn [k] (keep #(when (= k (first %)) (second %)) params))
            relays (vec (distinct (keep nostr/relay-url (named "relay"))))
            token  (first (named "secret"))]
        (when (and (seq relays) (secp256k1/public-key? remote))
          (cond-> {:remote-signer (str/lower-case remote) :relays relays}
            (seq token) (assoc :secret (secret/hide token))))))))

(defn- check-connection
  "Throw when `connection` lacks what a request needs."
  [{:keys [remote-signer relays client-key] :as connection}]
  (when-not (and (secp256k1/public-key? remote-signer)
                 (seq relays)
                 (public-key client-key))
    (throw (ex-info (str "A connection needs the :remote-signer, :relays and"
                         " :client-key")
                    {}))))

;; NIP-46, the section Request Events
(defn request-event
  "The event that asks the remote signer of `connection` to do `method`
  with the texts `params`, made from `opts`, or nil for a request with
  more bytes than a payload of NIP-44 holds.

  It's a signed event of the kind 24133 from the :client-key, with the
  request encrypted for the remote signer. The `opts` have what makes the
  event, since nothing here reads a clock or makes random bytes:

  - :id, the id of the request, which its answer names
  - :at, the instant of the request
  - :nonce, hexadecimal text of 32 random bytes, for the encryption
  - :aux, the same for the signature"
  [{:keys [remote-signer client-key] :as connection} method params
   {:keys [id at nonce aux] :as opts}]
  (check-connection connection)
  (let [app     (secret/reveal client-key)
        shared  (nip44/conversation-key app remote-signer)
        payload (json/write-str {"id"     id
                                 "method" method
                                 "params" (mapv str params)})]
    (when-let [content (nip44/encrypt payload shared nonce)]
      (nostr/signed {"kind"       kind
                     "created_at" (time/unix-seconds at)
                     "tags"       [["p" remote-signer]]
                     "content"    content}
                    app
                    aux))))

;; NIP-46, the sections Response Events and Auth Challenges
(defn answer
  "What the `event` of the remote signer of `connection` answers to the
  request `id`: a map of the :result, which is a text, or a map with a
  diagnostic under :error. It's nil for the answer to another request.

  A signer that wants the listener to approve the request on a web page
  first answers with :nostr/signer-approval and the :url of the page."
  [{:keys [remote-signer client-key] :as connection} event id]
  (let [verified? (nostr/verified? event)
        theirs?   (and verified?
                       (= kind (get event "kind"))
                       (= remote-signer (get event "pubkey")))
        content   (when theirs?
                    (let [shared (nip44/conversation-key
                                  (secret/reveal client-key) remote-signer)]
                      (nip44/decrypt (get event "content") shared)))
        body      (some-> content json/object)
        {:strs [result error]} body]
    (cond
      (not verified?)
      (unreadable "The event has a wrong id or a wrong signature")

      (not theirs?)
      (unreadable "The event is no answer of the remote signer")

      (nil? body)
      (unreadable "The content is not a JSON object for this connection")

      (not= id (get body "id"))
      nil

      (= "auth_url" result)
      (if (url/web-url? error)
        (diagnostics/failed {:id :nostr/signer-approval :url error})
        (unreadable "The page to approve the request on is no web page"))

      (and (string? error) (not (str/blank? error)))
      (diagnostics/failed {:id :nostr/signer-refused :reason error})

      (string? result)
      {:result result}

      :else
      (unreadable "The answer has no result and no error"))))

(defn- answered
  "What the `response` of a relay answers to the request `id` over
  `connection`. It's nil when the request didn't reach the signer through
  this relay, so that another relay can take it."
  [connection id {:keys [events refused sent?] :as response}]
  (let [found (first (keep #(answer connection % id) events))]
    (cond
      found
      found

      (or refused (not sent?))
      nil

      :else
      (diagnostics/failed {:id :nostr/signer-silent :request id}))))

(defn- ask-relays!
  "Send the request that `request-of` makes to each of `relays` in turn,
  with `opts`, and give what `read` makes of the first answer, in a future
  or a promise. The next relay gets the request only when the last didn't
  take it, so the signer gets it once."
  [opts request-of read relays tried]
  (if-let [[url & more] (seq relays)]
    (async/then
     (http/try-send! (:send opts) (request-of url) opts)
     (fn [{:keys [value error]}]
       (let [next! #(ask-relays! opts request-of read more
                                 (conj tried {:url url :reason %}))]
         (cond
           (:sent? (ex-data error))
           (read {:url url :events [] :sent? true})

           error
           (next! (async/explain error))

           :else
           (or (read value)
               (next! (or (:refused value) (:closed value)
                          "The relay didn't take the request")))))))
    (diagnostics/failed {:id :nostr/signer-unreachable :relays tried})))

(defn ask!
  "Ask the remote signer of `connection` to do `method` with the texts
  `params`, with `opts`, and give its answer, a map of the :result, or a
  map with a diagnostic under :error. On the JVM it gives the answer, and
  in ClojureScript a promise of it.

      (ask! connection \"get_public_key\" [])
      ;; => {:result \"3bf0…\"}

  The request goes to the first relay of the connection, and to the next
  only when a relay didn't take it. The `opts` are these:

  - :timeout-seconds, in place of that of default-limits
  - :send, a function that sends a request map to a relay, in place of
    nostr/send!
  - :allow-pred, a predicate of the URL of a relay, nostr/public? by
    default
  - :now-fn, a function that gives the instant of now, the clock by default
  - :random-fn, a function that gives a number of random bytes, from a
    secure source by default"
  ([connection method params]
   (ask! connection method params {}))
  ([{:keys [remote-signer relays client-key] :as connection} method params
    opts]
   {:pre [(map? opts)]}
   (check-connection connection)
   (let [{:keys [now-fn random-fn] :as opts}
         (merge default-limits
                {:send       nostr/send!
                 :allow-pred nostr/public?
                 :now-fn     time/now
                 :random-fn  bytes/generate!}
                opts)
         id      (bytes/hex (random-fn 16))
         at      (now-fn)
         event   (request-event connection method params
                                {:id    id
                                 :at    at
                                 :nonce (bytes/hex (random-fn 32))
                                 :aux   (bytes/hex (random-fn 32))})
         filters [{"kinds"   [kind]
                   "authors" [remote-signer]
                   "#p"      [(public-key client-key)]
                   "since"   (- (time/unix-seconds at) 10)}]]
     (if (nil? event)
       (diagnostics/failed {:id :nostr/request-too-large :method method})
       (ask-relays! opts
                    (fn [url] {:url url :event event :filters filters})
                    #(answered connection id %)
                    relays
                    [])))))

;; NIP-46, the method connect: the remote signer, the secret and the
;; permissions, with no text after the last one given
(defn connect!
  "Connect to the remote signer of `connection` with `opts`, and give the
  public key of the listener as :public-key, or a map with a diagnostic
  under :error. On the JVM it gives the key, and in ClojureScript a
  promise of it.

  The request has the :secret of the connection, and the :perms of
  `opts`, the permissions that the app asks for, e.g.
  \"sign_event:1111,sign_event:7\". The other `opts` are those of ask!."
  ([connection]
   (connect! connection {}))
  ([{:keys [remote-signer] :as connection} {:keys [perms] :as opts}]
   (let [token  (secret/reveal (:secret connection))
         params (->> [remote-signer (or token "") (or perms "")]
                     reverse
                     (drop-while #{""})
                     reverse)]
     (async/then
      (ask! connection "connect" params opts)
      (fn [{:keys [error result] :as connected}]
        (cond
          error
          connected

          (not (contains? #{"ack" token} result))
          (unreadable "The remote signer didn't take the connection")

          :else
          (async/then
           (ask! connection "get_public_key" [] opts)
           (fn [{:keys [error result] :as asked}]
             (cond
               error
               asked

               (secp256k1/public-key? result)
               {:public-key (str/lower-case result)}

               :else
               (unreadable "The answer is no public key"))))))))))

(defn remote
  "A signer over the remote signer of `connection`, with the `opts` of
  ask!. The :public-key of `opts` is the listener's, as connect! gave it,
  so that no request asks for it again."
  ([connection]
   (remote connection {}))
  ([connection {:keys [public-key] :as opts}]
   (check-connection connection)
   {:public-key-fn (fn []
                     (or public-key
                         (async/then (ask! connection "get_public_key" [] opts)
                                     #(if (:error %) % (:result %)))))
    :sign-fn       (fn [event]
                     (async/then
                      (ask! connection "sign_event" [(json/write-str event)]
                            opts)
                      (fn [{:keys [error result] :as asked}]
                        (if error
                          asked
                          (or (json/object result)
                              (unreadable "The signed event is no JSON"))))))}))

(defn sign!
  "Sign `event` with `signer`, and give the signed event, or a map with a
  diagnostic under :error. On the JVM it gives the event, and in
  ClojureScript a promise of it.

  The signed event must verify, and have the kind, the time, the tags and
  the content of `event`, so that a signer can't sign another event in
  its place."
  [{:keys [sign-fn] :as signer} event]
  (let [event (merge {"tags" [] "content" ""} event)
        parts #(select-keys % ["kind" "created_at" "tags" "content"])]
    (async/then
     (async/attempt #(sign-fn event))
     (fn [{:keys [value error]}]
       (cond
         error
         (unreadable (async/explain error))

         (:error value)
         value

         (and (map? value)
              (nostr/verified? value)
              (= (parts event) (parts value)))
         value

         :else
         (unreadable "The signed event isn't the event that was asked"))))))
