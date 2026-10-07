(ns dk.simongray.podcast.value.nwc
  "Nostr Wallet Connect, which NIP-47 defines: payments from the wallet of
  the listener, asked for over a relay of Nostr.

  The listener gets a connection URI from the wallet, and gives it to the
  app. The parse function reads the URI into a connection. The URI holds
  a secret key, and whoever has it can spend from the wallet. So an app
  keeps it as it keeps a password, and shows it to nobody. The connection
  hides the key with dk.simongray.podcast.util.secret, so a connection
  that's printed doesn't show it. The format function gives the text that
  an app stores.

  A command is data, a map of :method and :params. The pay-invoice
  command pays the invoice of a Lightning address, which
  value.lnaddress/request-invoice! gives. The pay-keysend command pays a
  node, with the records of value/records. The other commands of NIP-47
  are get-balance, get-info, make-invoice and lookup-invoice.

  The ask! function sends a command to the wallet and gives its answer.
  For an app that holds its own connections to relays, request-event
  writes the event of a command and answer reads the event of the
  wallet. The content of each event is encrypted with NIP-44. The
  encryption of NIP-04, which NIP-47 keeps for old wallets, isn't written
  here. NIP-04 has no check of a change, and its own text tells not to
  use it.

  NIP-47 has the commands of invoices. The command of keysend is in the
  extension NWC-04, in the repository nostr-wallet-connect/nwc. A wallet
  names the commands that it takes in its info event, which fetch-info!
  reads."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.crypto.nip44 :as nip44]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.nostr :as nostr]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.url :as url]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.bolt11 :as bolt11]
            [dk.simongray.podcast.value.payment :as value.payment]))

(def kinds
  "The kinds of the events of NIP-47, in its section Events: the info
  event of a wallet, the request of an app and the response of the
  wallet."
  {:info     13194
   :request  23194
   :response 23195})

(def default-limits
  "The limits of ask! and fetch-info! that their options don't set.

  A payment over Lightning can take a minute, so the wallet has
  :timeout-seconds to answer. The request of a command expires after that
  time."
  {:timeout-seconds 60})

(def ^:no-doc encryption
  "The name that NIP-47 gives the encryption of podcast-clj, in its
  section Encryption."
  "nip44_v2")

(defn- key-text?
  "Whether `x` is hexadecimal text of 32 bytes in lower case, as the
  events of Nostr have a key."
  [x]
  (boolean (and (string? x) (re-matches #"[0-9a-f]{64}" x))))

(defn- query
  "The parameters of the query string `s`, decoded, as pairs of a name and
  a value in their order. A name can come more than once."
  [s]
  (for [part  (str/split (str s) #"&")
        :let  [[k v] (map url/percent-decode (str/split part #"=" 2))]
        :when (and (seq k) (some? v))]
    [k v]))

(defn parse
  "The connection that the URI `uri` of Nostr Wallet Connect names, or nil
  for a text that isn't such a URI.

      {:wallet \"b889…\"                 ; the public key of the wallet service
       :relays [\"wss://relay.example\"] ; where the wallet service listens
       :secret #<secret>               ; the secret key of the app, hidden
       :lud16  \"ann@example.org\"}      ; the Lightning address of the listener

  The section Nostr Wallet Connect URI of NIP-47 defines the URI. It has
  the scheme nostr+walletconnect, one relay or more, and a secret. The
  relays are as nostr/relay-url writes them, and a relay that isn't the
  URL of a relay is left out.

  The secret is for this connection only. Whoever has it can spend from
  the wallet, within the budget that the wallet gave the connection. It's
  a hidden text of secret/hide, which doesn't show in a log or at a REPL.
  The reader doesn't read a printed connection, so an app stores the text
  of uri.

  The URI is a claim of whoever wrote it. The :lud16 is where that person
  wants payments to go, so an app asks the listener before it uses the
  address."
  [uri]
  (let [form  #"(?i)nostr\+walletconnect:(?://)?([0-9a-f]{64})/?\?(.*)"
        found (when (string? uri) (re-matches form (str/trim uri)))]
    (when-let [[_ wallet params] found]
      (let [params (query params)
            named  (fn [k] (map second (filter #(= k (first %)) params)))
            relays (vec (distinct (keep nostr/relay-url (named "relay"))))
            secret (some-> (first (named "secret")) str/lower-case)
            lud16  (first (named "lud16"))]
        (when (and (seq relays)
                   (secp256k1/public-key secret)
                   (secp256k1/public-key? wallet))
          (cond-> {:wallet (str/lower-case wallet)
                   :relays relays
                   :secret (secret/hide secret)}
            (seq lud16) (assoc :lud16 lud16)))))))

(defn ^:no-doc secret-key
  "The secret key of the app in `connection`, as text. It's the one place
  where the functions here read the key."
  [connection]
  (secret/reveal (:secret connection)))

(defn connection?
  "Whether `x` is a connection, as parse gives one. Its keys are in lower
  case, its wallet is a point of the curve, and its secret is a secret
  key. Each of its relays is the URL of a relay.

  The secret is a hidden text of secret/hide. A plain text is a secret
  too, for an app that makes a connection of its own parts. But such a
  connection shows its secret when it's printed."
  [x]
  (boolean (and (map? x)
                (key-text? (:wallet x))
                (key-text? (secret-key x))
                (secp256k1/public-key? (:wallet x))
                (some? (secp256k1/public-key (secret-key x)))
                (vector? (:relays x))
                (seq (:relays x))
                (every? #(= % (nostr/relay-url %)) (:relays x)))))

(defn- check-connection
  "Throw unless `connection` is one, as connection? tells. An assertion
  wouldn't do, since a release build of ClojureScript leaves assertions
  out."
  [connection]
  (when-not (connection? connection)
    (throw (ex-info "The connection isn't one that parse gives" {}))))

(defn format
  "The URI of Nostr Wallet Connect for `connection`, a map as parse gives
  one. A wallet service gives an app such a URI, and an app stores
  it.

  The text has the secret of the connection. Keep it where the app keeps
  passwords."
  [{:keys [wallet relays lud16] :as connection}]
  (let [relay-params (map #(url/query-string {"relay" %}) relays)
        own-params   (url/query-string {"secret" (secret-key connection)
                                        "lud16"  lud16})]
    (str "nostr+walletconnect://" wallet "?"
         (str/join "&" (concat relay-params [own-params])))))

(defn public-key
  "The public key of the app in `connection`, by which the wallet knows
  the connection. It's the public key of the secret of the connection."
  [connection]
  (secp256k1/public-key (secret-key connection)))

(defn pay-invoice
  "The command that pays the BOLT 11 `invoice` by `opts`, as the section
  pay_invoice of NIP-47 defines it. The `opts` are these:

  - :msat, the amount for an invoice that names none
  - :metadata, a map that the wallet keeps with the payment. NWC-06 names
    keys of such metadata, such as \"comment\"."
  ([invoice]
   (pay-invoice invoice {}))
  ([invoice {:keys [msat metadata] :as opts}]
   {:pre [(map? opts)]}
   (when-not (string? invoice)
     (throw (ex-info "The invoice isn't a string" {})))
   {:method "pay_invoice"
    :params (cond-> {"invoice" invoice}
              msat     (assoc "amount" msat)
              metadata (assoc "metadata" metadata))}))

(defn- node-key?
  "Whether `x` is the public key of a Lightning node: hexadecimal text of
  33 bytes that starts with 02 or 03."
  [x]
  (boolean (and (string? x) (re-matches #"(?i)0[23][0-9a-f]{64}" x))))

(defn pay-keysend
  "The command that pays `payment` to `recipient` with keysend, as NWC-04
  defines it, or nil for a recipient that isn't a node or a payment
  without an amount.

  The payment is the map of dk.simongray.podcast.value, and the recipient
  is a recipient of a value block, of the type node. The command has the
  custom records of value/records: the bLIP-10 record, and the custom key
  and value of the recipient. The report of the command says when the
  records leave out the custom key or value. Don't send the command then."
  [payment {:keys [address] :as recipient}]
  (let [[msat]  (value.payment/amounts payment)
        records (value/records payment recipient)
        tlv     (fn [[type text]]
                  {"type" type "value" (bytes/hex (bytes/encode text))})]
    (when (and msat (pos? msat) (node-key? address))
      (with-meta {:method "pay_keysend"
                  :params {"amount"      msat
                           "pubkey"      (str/lower-case address)
                           "tlv_records" (mapv tlv (sort-by key records))}}
        (meta records)))))

(defn make-invoice
  "The command that makes an invoice for `msat` millisatoshis in the
  wallet by `opts`, as the section make_invoice of NIP-47 defines it.

  The `opts` can give :description, :description-hash, :expiry in
  seconds, and :metadata."
  ([msat]
   (make-invoice msat {}))
  ([msat {:keys [description description-hash expiry metadata] :as opts}]
   {:pre [(map? opts)]}
   (when-not (number? msat)
     (throw (ex-info "The amount isn't a number of millisatoshis"
                     {:msat msat})))
   {:method "make_invoice"
    :params (cond-> {"amount" msat}
              description      (assoc "description" description)
              description-hash (assoc "description_hash" description-hash)
              expiry           (assoc "expiry" expiry)
              metadata         (assoc "metadata" metadata))}))

(defn lookup-invoice
  "The command that asks the wallet for the state of the payment `x`, as
  the section lookup_invoice of NIP-47 defines it. The `x` names the
  payment by its :invoice or by its :payment-hash."
  [{:keys [invoice payment-hash] :as x}]
  (when-not (or (string? invoice) (string? payment-hash))
    (throw (ex-info "A payment is named by its :invoice or its :payment-hash"
                    {})))
  {:method "lookup_invoice"
   :params (cond-> {}
             payment-hash (assoc "payment_hash" payment-hash)
             invoice      (assoc "invoice" invoice))})

(defn get-balance
  "The command that asks the wallet for its balance."
  []
  {:method "get_balance" :params {}})

(defn get-info
  "The command that asks the wallet for its name, its network and the
  commands that it takes from this connection."
  []
  {:method "get_info" :params {}})

(defn- seconds
  "The instant `at` as the seconds of a Nostr event."
  [at]
  (quot (inst-ms at) 1000))

(defn request-event
  "The event that asks the wallet of `connection` to do `command`, made
  from `opts`, or nil for a command with more bytes than a payload of
  NIP-44 holds.

  It's a signed event of the kind 23194, with the command in its content,
  encrypted for the wallet. The section Request and Response Events of
  NIP-47 defines it. The `opts` have what makes the event, since nothing
  here reads a clock or makes random bytes:

  - :at, the instant of the request
  - :nonce, hexadecimal text of 32 bytes that nobody can guess, for the
    encryption
  - :aux, the same for the signature
  - :expires-at, the instant after which the wallet doesn't do the
    command, which is optional

  Use new bytes for each request, such as (bytes/hex (bytes/generate!
  32))."
  [{:keys [wallet] :as connection}
   {:keys [method params] :as command}
   {:keys [at nonce aux expires-at] :as opts}]
  (check-connection connection)
  (when-let [wrong (cond
                     (not (string? method))
                     "The command has no :method"

                     (not (map? params))
                     "The :params of the command aren't a map"

                     (not (inst? at))
                     "The request needs its instant under :at")]
    (throw (ex-info wrong {})))
  (let [secret  (secret-key connection)
        shared  (nip44/conversation-key secret wallet)
        payload (json/write-str {"method" method "params" params})]
    (when-let [content (nip44/encrypt payload shared nonce)]
      (let [tags (cond-> [["encryption" encryption] ["p" wallet]]
                   expires-at (conj ["expiration" (str (seconds expires-at))]))]
        (nostr/signed {"kind"       (:request kinds)
                       "created_at" (seconds at)
                       "tags"       tags
                       "content"    content}
                      secret
                      aux)))))

(defn ^:no-doc failure
  "A map with the diagnostic `d` under :error."
  [d]
  {:error (diagnostics/describe d)})

(def ^:no-doc fields
  "The key and the type of each field of a result that NIP-47 defines. An
  amount is a number of millisatoshis, and a time is a number of
  seconds."
  {"preimage"         [:preimage :text]
   "fees_paid"        [:fees-msat :number]
   "balance"          [:msat :number]
   "amount"           [:msat :number]
   "type"             [:type :text]
   "state"            [:state :text]
   "invoice"          [:invoice :text]
   "description"      [:description :text]
   "description_hash" [:description-hash :text]
   "payment_hash"     [:payment-hash :text]
   "created_at"       [:created-at :time]
   "expires_at"       [:expires-at :time]
   "settled_at"       [:settled-at :time]
   "metadata"         [:metadata :object]
   "alias"            [:alias :text]
   "color"            [:color :text]
   "pubkey"           [:public-key :text]
   "network"          [:network :text]
   "block_height"     [:block-height :number]
   "block_hash"       [:block-hash :text]
   "methods"          [:methods :texts]
   "extensions"       [:extensions :texts]})

(defn- typed
  "The JSON value `v` as the value of a field of the type `type`, or nil
  for a value of another type."
  [type v]
  (case type
    :text   (when (string? v) v)
    :number (json/whole v)
    :time   (some-> (json/whole v) (* 1000) time/->inst)
    :object (when (map? v) v)
    :texts  (when (sequential? v) (vec (filter string? v)))))

(defn- result
  "The result `raw` of a wallet in the keys of podcast-clj, by fields."
  [raw]
  (into {} (for [[k v] raw
                 :let  [[name type] (get fields k)
                        value       (when name (typed type v))]
                 :when (some? value)]
             [name value])))

(defn answer
  "What the `event` of a wallet answers over `connection`, or a map with a
  diagnostic under :error. The section Request and Response Events of
  NIP-47 defines the event, of the kind 23195.

      {:method   \"pay_invoice\"
       :request  \"5c83…\"          ; the id of the request that it answers
       :preimage \"0123…\"  :fees-msat 1000
       :result   {\"preimage\" \"0123…\" \"fees_paid\" 1000}}

  The answer has the fields of the result in the keys of podcast-clj, and
  the :result as the wallet gave it. An amount is in millisatoshis, under
  :msat or :fees-msat, and a time is an instant.

  An error of the wallet is :value/wallet-refused, with the :code of
  NIP-47 and the :message of the wallet. An event that isn't the signed
  event of the wallet for this connection is :value/wallet-unreadable. So
  is the result of a payment without a preimage, since a wallet gets the
  preimage when it pays.

  The texts of an answer are as the wallet wrote them. Hiccup escapes
  them, but an app that writes HTML as text must escape them itself. This
  doesn't know the invoice of a payment, so it doesn't check the
  preimage, but ask! does."
  [{:keys [wallet] :as connection} event]
  (check-connection connection)
  (let [unreadable #(failure {:id :value/wallet-unreadable :reason %})
        verified?  (nostr/verified? event)
        content    (when (and verified? (= wallet (get event "pubkey")))
                     (let [app-key (secret-key connection)
                           shared  (nip44/conversation-key app-key wallet)]
                       (nip44/decrypt (get event "content") shared)))
        body       (some-> content json/object)
        method     (json/text (get body "result_type"))
        request    (first (nostr/tag-values event "e"))
        payment?   (contains? #{"pay_invoice" "pay_keysend"} method)]
    (cond
      (not verified?)
      (unreadable "The event has a wrong id or a wrong signature")

      (not= (:response kinds) (get event "kind"))
      (unreadable "The event is no response of NIP-47")

      (not= wallet (get event "pubkey"))
      (unreadable "The event is not from the wallet of the connection")

      (nil? content)
      (unreadable (if (= 2 (nip44/payload-version (get event "content")))
                    "The content is not encrypted for this connection"
                    "The content is not encrypted with NIP-44 version 2"))

      (nil? body)
      (unreadable "The content is no JSON object")

      (map? (get body "error"))
      (let [{:strs [code message]} (get body "error")]
        (failure (cond-> {:id     :value/wallet-refused
                          :code   (json/text code)
                          :reason (json/text message)}
                   method  (assoc :method method)
                   request (assoc :request request))))

      (not (map? (get body "result")))
      (unreadable "The answer has no result and no error")

      (and payment?
           (str/blank? (str (json/text (get-in body ["result" "preimage"])))))
      (unreadable "The result of a payment has no preimage")

      :else
      (cond-> (assoc (result (get body "result"))
                     :method method
                     :result (get body "result"))
        request (assoc :request request)))))

(defn info-request
  "The request that asks the relay at `url` for the info event of the
  wallet of `connection`, for nostr/send!."
  [{:keys [wallet] :as connection} url]
  {:url url :filters [{"kinds" [(:info kinds)] "authors" [wallet] "limit" 1}]})

(defn info
  "What the info `event` of a wallet tells, as the section Info Event of
  NIP-47 defines it:

      {:methods    [\"pay_invoice\" \"get_balance\"] ; the commands it takes
       :encryption [\"nip44_v2\" \"nip04\"]          ; the encryptions it reads
       :extensions [\"02\" \"04\"]}                  ; its extensions of NWC

  A wallet that names no encryption reads that of NIP-04 only, as the
  section Encryption of NIP-47 tells."
  [event]
  (let [words #(vec (remove str/blank? (str/split (str %) #"\s+")))
        tag   #(first (nostr/tag-values event %))]
    {:methods    (words (get event "content"))
     :encryption (if-let [named (tag "encryption")] (words named) ["nip04"])
     :extensions (words (tag "extensions"))}))

(defn unsupported
  "Why the wallet with the `info` doesn't take `command` from podcast-clj,
  as a map with a diagnostic under :error. It's nil when the wallet takes
  the command, and when `info` is nil, since the wallet is unknown then.

  The diagnostic is :value/wallet-unsupported. The wallet doesn't name
  the method of the command, or it doesn't read NIP-44 version 2."
  [info {:keys [method] :as command}]
  (when info
    (cond
      (not (some #{encryption} (:encryption info)))
      (failure {:id :value/wallet-unsupported :encryption (:encryption info)})

      (not (some #{method} (:methods info)))
      (failure {:id      :value/wallet-unsupported
                :method  method
                :methods (:methods info)}))))

(defn- ask-relays!
  "Send the request that `request-of` makes to each of `relays` in turn,
  with `opts` and the failures so far in `tried`. Give what `read` makes
  of the first answer, in a future or a promise.

  The next relay gets the request when a relay gives no connection, or
  when `read` gives nil for its response. The `read` function gives nil
  for a response that says that the request didn't reach the wallet. Each
  failure in `tried` has the :url, the :reason, and whether the relay
  :answered?. When no relay is left, the result has a diagnostic under
  :error.

  An exchange that failed after its event went out has :sent? in the data
  of its exception. The `read` function then gets a response without
  events, so the response is read as for a relay that has no answer."
  [opts request-of read relays tried]
  (if-let [[url & more] (seq relays)]
    (async/then
     (http/try-send! (:send opts) (request-of url) opts)
     (fn [{:keys [value error]}]
       (let [next! (fn [failed]
                     (let [tried (conj tried (assoc failed :url url))]
                       (ask-relays! opts request-of read more tried)))]
         (cond
           (:sent? (ex-data error))
           (read {:url url :events [] :sent? true})

           error
           (next! {:reason (async/explain error) :answered? false})

           :else
           (or (read value)
               (let [reason (or (:refused value)
                                (:closed value)
                                "The relay has no event")]
                 (next! {:reason    reason
                         :answered? (not (:timeout? value))})))))))
    (failure {:id :value/wallet-unreachable :relays tried})))

(defn- prepared
  "The `opts` of ask! and fetch-info! over their defaults: the limits, the
  send function, the guard of the relays, the clock and the random bytes."
  [opts]
  (merge default-limits
         {:send   nostr/send!
          :allow? nostr/public?
          :now    time/now
          :random bytes/generate!}
         opts))

(defn fetch-info!
  "Fetch what the wallet of `connection` tells of itself with `opts`, as
  info reads its info event. On the JVM it gives the info, and in
  ClojureScript a promise of it.

  A wallet gives its info event to its relays, and they keep it. When no
  relay has the event, the :error is :value/no-wallet-info, of the level
  info. When no relay answers, it's :value/wallet-unreachable.

  The `opts` are those of ask!. Fetch the info once for each connection,
  and give it to ask! as :info."
  ([connection]
   (fetch-info! connection {}))
  ([{:keys [wallet relays] :as connection} opts]
   {:pre [(map? opts)]}
   (check-connection connection)
   (let [opts  (prepared opts)
         known (fn [{:keys [events]}]
                 (some->> events
                          (filter #(and (= wallet (get % "pubkey"))
                                        (= (:info kinds) (get % "kind"))
                                        (nostr/verified? %)))
                          first
                          info))]
     (async/then (ask-relays! opts #(info-request connection %) known relays [])
                 (fn [{:keys [error] :as read}]
                   (if (some :answered? (:relays error))
                     (failure {:id     :value/no-wallet-info
                               :relays (:relays error)})
                     read))))))

(defn- answered
  "What the `response` of a relay tells of the request `event` with the
  `command` over `connection`, as answer reads it. The request expires at
  `expires-at`, and a failure gives a map with a diagnostic under :error.

  It's nil when the request didn't reach the wallet through this relay:
  the event didn't go out, or the relay refused it. Only then can another
  relay get the event. In any other case without an answer, the wallet
  can have the request, and the error is :value/wallet-silent.

  The answer to the payment of an invoice has :proven?, which tells
  whether its preimage is the proof of that invoice."
  [connection {:keys [method params] :as command} event expires-at
   {:keys [events refused sent?] :as response}]
  (let [read (some-> (first events) (->> (answer connection)))
        id   (get event "id")]
    (cond
      (and (empty? events) (or refused (not sent?)))
      nil

      (empty? events)
      (failure {:id :value/wallet-silent :request id :expires-at expires-at})

      (:error read)
      read

      (or (not= method (:method read))
          (not= id (:request read)))
      (failure {:id     :value/wallet-unreadable
                :reason "The answer is the answer to another request"})

      (= "pay_invoice" method)
      (let [proof (bolt11/proves? (:preimage read) (get params "invoice"))]
        (assoc read :proven? proof))

      :else
      read)))

(defn- send-command!
  "Send `command` over `connection` with the prepared `opts`, given the
  `info` that the wallet gave, and give the answer as ask! gives it."
  [{:keys [wallet relays] :as connection} command
   {:keys [now random timeout-seconds] :as opts} info]
  (let [info    (when-not (= :value/no-wallet-info (:id (:error info)))
                  info)
        at      (now)
        expires (time/->inst (+ (inst-ms at) (* 1000 timeout-seconds)))
        event   (when-not (:error info)
                  (request-event connection command
                                 {:at         at
                                  :nonce      (bytes/hex (random 32))
                                  :aux        (bytes/hex (random 32))
                                  :expires-at expires}))
        filters [{"kinds"   [(:response kinds)]
                  "authors" [wallet]
                  "#e"      [(get event "id")]}]
        asking  (fn [url] {:url url :event event :filters filters})
        read    #(answered connection command event expires %)]
    (cond
      (:error info)
      info

      (unsupported info command)
      (unsupported info command)

      (nil? event)
      (failure {:id :value/command-too-large :method (:method command)})

      :else
      (ask-relays! opts asking read relays []))))

(defn ask!
  "Ask the wallet of `connection` to do `command` with `opts`, and give its
  answer as answer reads it, or a map with a diagnostic under :error. On
  the JVM it gives the answer, and in ClojureScript a promise of it.

      (ask! connection (pay-invoice \"lnbc500n1…\"))
      ;; => {:method \"pay_invoice\" :preimage \"0123…\" :proven? true …}

  The answer to pay-invoice has :proven?. It's true when the preimage is
  the proof of the invoice, as bolt11/proves? tells. When it's false,
  the wallet says that it paid, and it gave no proof. Don't pay again
  then, since the wallet can have paid, and tell the listener, who knows
  the wallet. A keysend payment has no such proof, since the wallet of
  the sender makes its preimage.

  The request goes to the first relay of the connection. The next relay
  gets it only when the event didn't leave the app, or when the relay
  refused the event. So the wallet gets a command once. The request
  expires after :timeout-seconds, and a wallet doesn't do a command that
  expired. A relay can give the wallet the same event again, and the
  expiry is the end of that too.

  When the wallet gives no answer in time, the error is
  :value/wallet-silent. The request went out, so the wallet can do the
  command until :expires-at. Don't send a payment again before that time,
  and some time more, since the clocks of the app and the wallet differ.
  After it, lookup-invoice tells whether the wallet paid an invoice.

  A command with more bytes than a request holds gives
  :value/command-too-large, and no request goes out. A feed chooses the
  titles in the record of a payment, so a feed can make a command large.

  The `opts` are these:

  - :info, what fetch-info! gave for the connection. Without it, this
    asks the relays for the info event first. A wallet that doesn't take
    the command gives :value/wallet-unsupported, and no request goes out.
  - :timeout-seconds, in place of that of default-limits
  - :send, a function that sends a request map to a relay, in place of
    nostr/send!. Its response has :sent? true when the event of the
    request went out, as the response of nostr/send! has. Without
    :sent?, the next relay gets the event.
  - :allow?, a predicate of the URL of a relay. It's nostr/public? by
    default, so nothing goes to a private network. For a wallet on your
    own network, give (constantly true).
  - :now, a function that gives the instant of now, the clock by default
  - :random, a function that gives a number of random bytes,
    bytes/generate! by default"
  ([connection command]
   (ask! connection command {}))
  ([connection command opts]
   {:pre [(map? opts)]}
   (check-connection connection)
   (when-not (string? (:method command))
     (throw (ex-info "The command has no :method" {})))
   (let [opts  (prepared opts)
         known (if (contains? opts :info)
                 (:info opts)
                 (fetch-info! connection opts))]
     (async/then known #(send-command! connection command opts %)))))

(comment
  ;; the example of NIP-47
  (def connection
    (parse
     (str "nostr+walletconnect://"
          "b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4"
          "?relay=wss%3A%2F%2Frelay.damus.io"
          "&secret="
          "71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c")))

  (dissoc connection :secret)
  (public-key connection)

  (pay-invoice "lnbc500n1…")
  (get-balance)

  ;; a keysend payment of 50 sats, with the record of a boost
  (def command
    (pay-keysend {:action   :boost
                  :sats     50
                  :message  "Great episode"
                  :app-name "My app"}
                 {:type         "node"
                  :name         "Ann"
                  :address      (apply str "03" (repeat 64 "a"))
                  :custom-key   "696969"
                  :custom-value "22"}))

  (value/report command)

  ;; the event of a command: the relay sees the kind and the tags, and not
  ;; the command
  (-> (request-event connection
                     (get-balance)
                     {:at    #inst "2026-09-29T12:00:00Z"
                      :nonce (bytes/hex (bytes/generate! 32))
                      :aux   (bytes/hex (bytes/generate! 32))})
      (select-keys ["kind" "tags" "pubkey"]))

  (info {"content" "pay_invoice get_balance"
         "tags"    [["encryption" "nip44_v2 nip04"]]})
  (unsupported (info {"content" "get_balance" "tags" []}) (get-balance))
  #_.)
