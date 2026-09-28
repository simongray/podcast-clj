(ns dk.simongray.podcast.value.lnaddress
  "Lightning addresses, `user@domain`, which a podcast:valueRecipient of
  type lnaddress names, and how to pay them. The requests ask the domain of
  an address, as LUD-16 and the podcast namespace define them, and the
  readers read the answers. The wallet of the app pays.

  LNURL-pay gives an invoice for an amount. `lnurlp-request` asks the
  domain for the terms of the address, and `terms` reads them. The terms
  are the callback, the amounts that it takes, and how long a comment can
  be.
  `invoice-request` asks the callback for an invoice, with a comment such
  as the one of `value.metadata`. `invoice` reads the answer, and checks
  that the invoice asks for the amount that was requested, as LUD-06
  requires.

  The keysend lookup gives a node instead. `keysend-request` asks for it,
  and `node` reads the answer into the keys of a recipient of type node.
  The app pays that recipient with keysend and the records of
  `value/records`.

  `invoice!` and `node!` send the requests themselves. A reader decides on
  the JSON of an answer, not on its status, as LUD-01 asks. Services answer
  an error with any status, and some answer the keysend lookup with a web
  page."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.value :as value]))

(def default-limits
  "The limits of `invoice!` and `node!` that their options do not set. They
  are the seconds that each request can take, and the size of an answer in
  bytes. The terms of an address can hold an image of about 136 kilobytes."
  {:timeout-seconds 10
   :max-bytes       (* 256 1024)})

(defn parse
  "The parts of the Lightning address `s`, `{:user :domain}`, in lower case.
  Nil when `s` is not one. A user name holds the letters a to z, the digits
  and `-_.+`, as LUD-16 allows.

  The domain is a name on the internet: not an address, not a name without
  a dot, and not a name that stays on a local network, such as localhost,
  .local, .internal or .home.arpa. A feed chooses the address, and so the
  host that the app asks."
  [s]
  (when (string? s)
    (when-let [[_ user domain] (re-matches #"([a-z0-9_.+-]+)@([^@\s/?#\\]+)" (str/lower-case (str/trim s)))]
      (let [host (http/host (str "https://" domain "/"))]
        (when (and host
                   (str/includes? host ".")
                   (not (re-matches #"[0-9.]+|\[.*\]" host))
                   (not (re-find #"(^|\.)(localhost|local|internal|home\.arpa)$" host)))
          {:user user :domain domain})))))

(defn- well-known
  "The URL of the well-known answer of `kind` about the parts of an
  address: http for an onion service, as LUD-01 has it, and else https."
  [{:keys [user domain]} kind]
  (str (if (str/ends-with? (first (str/split domain #":")) ".onion") "http" "https")
       "://" domain "/.well-known/" kind "/" user))

(defn lnurlp-request
  "The request for the terms on which the Lightning `address` takes a
  payment with LNURL-pay. Nil when `address` is not one."
  [address]
  (when-let [parts (parse address)]
    {:method :get :url (well-known parts "lnurlp") :headers {"Accept" "application/json"}}))

(defn keysend-request
  "The request for the node that pays the Lightning `address` with
  keysend. Nil when `address` is not one."
  [address]
  (when-let [parts (parse address)]
    {:method :get :url (well-known parts "keysend") :headers {"Accept" "application/json"}}))

(defn- answer
  "The JSON object in the body of `response`, whatever its status, or nil."
  [{:keys [body]}]
  (let [x (json/parse body)]
    (when (map? x)
      x)))

(defn- error
  "`{:error diagnostic}` for the diagnostic `d`."
  [d]
  {:error (diagnostics/describe d)})

(defn- refusal
  "The error of the JSON `raw` of an answer from `url` whose status is
  ERROR, with its reason; nil for another answer."
  [raw url]
  (when (= "ERROR" (get raw "status"))
    (error {:id :value/lnurl-error :url url :reason (json/text (get raw "reason"))})))

(defn- secure-url?
  "Whether `s` is an https URL, or an http URL of an onion service, as
  LUD-01 allows."
  [s]
  (let [[scheme host] (http/origin s)]
    (or (= "https" scheme)
        (and (= "http" scheme) (str/ends-with? host ".onion")))))

(defn- insecure
  "The error of an answer that came from `url` over plain http, where
  anyone on the way can change it; nil for an https URL, an onion service,
  or an answer that names no URL."
  [url]
  (when (and url (not (secure-url? url)))
    (error {:id :value/insecure :url url})))

(defn- whole-of
  "The whole number above zero that the JSON number `x` gives, rounded up
  for a lower bound and down for an upper, as `round` says."
  [x round]
  (when (and (number? x) (< 0 x 9e15))
    (long (if (= :up round) (Math/ceil x) (Math/floor x)))))

(defn terms
  "The terms on which a Lightning address takes a payment, from the
  `response` to its `lnurlp-request`, or `{:error diagnostic}`.

      {:callback       \"https://…\"  ; where to ask for an invoice
       :min-msat       1000
       :max-msat       100000000
       :comment-length 255          ; how long a comment can be, 0 for none
       :description    \"Pay Ann\"   ; the text of the metadata
       :identifier     \"ann@…\"     ; the address, as the metadata names it
       :metadata       \"[[…]]\"}    ; the metadata, as the service wrote it

  The callback is an https URL, or http for an onion service. It can be on
  another host than the address."
  [response]
  (let [raw (answer response)
        url (:url response)]
    (or (insecure url)
        (refusal raw url)
        (let [callback    (get raw "callback")
              low         (whole-of (get raw "minSendable") :up)
              high        (whole-of (get raw "maxSendable") :down)
              metadata    (get raw "metadata")
              entries     (let [x (when (string? metadata) (json/parse metadata))]
                            (when (sequential? x) x))
              entry       (fn [mime]
                            (some #(when (and (sequential? %) (= mime (first %)) (string? (second %))) (second %))
                                  entries))
              description (or (entry "text/long-desc") (entry "text/plain"))
              identifier  (or (entry "text/identifier") (entry "text/email"))]
          (if (and (= "payRequest" (get raw "tag")) (secure-url? callback) low high (<= low high))
            (cond-> {:callback       callback
                     :min-msat       low
                     :max-msat       high
                     :comment-length (or (whole-of (get raw "commentAllowed") :down) 0)}
              description        (assoc :description description)
              identifier         (assoc :identifier identifier)
              (string? metadata) (assoc :metadata metadata))
            (error {:id :value/malformed :url url :status (:status response)}))))))

(defn sendable?
  "Whether a Lightning address with `terms` takes a payment of `msat`
  millisatoshis."
  [{:keys [min-msat max-msat]} msat]
  (boolean (and (integer? msat) min-msat max-msat (<= min-msat msat max-msat))))

(defn invoice-request
  "The request to the callback of `terms` for an invoice of `msat`
  millisatoshis, with a `comment` when the terms take one.

  The comment is cut to the `:comment-length` of the terms, and left out
  when they take none. The amount and the comment follow the query that
  the callback has. Throws when the terms do not take `msat`, as
  `sendable?` tells."
  ([terms msat]
   (invoice-request terms msat nil))
  ([{:keys [callback comment-length] :as terms} msat comment]
   (when-not (sendable? terms msat)
     (throw (ex-info "The address does not take this amount"
                     {:type ::amount :msat msat :min-msat (:min-msat terms) :max-msat (:max-msat terms)})))
   (let [text (when (and (string? comment) (not (str/blank? comment)) (pos? (or comment-length 0)))
                (value/cut comment comment-length))]
     {:method  :get
      :url     (str callback (if (str/includes? callback "?") "&" "?")
                    (http/query-string {"amount" msat "comment" text}))
      :headers {"Accept" "application/json"}})))

(def ^:no-doc networks
  "The network of each prefix of a BOLT 11 invoice."
  {"bc" :bitcoin "tb" :testnet "tbs" :signet "bcrt" :regtest "sb" :simnet})

(def ^:no-doc multipliers
  "The millisatoshis in one of each unit of an amount in a BOLT 11 invoice,
  a fraction of a bitcoin."
  {"" 100000000000 "m" 100000000 "u" 100000 "n" 100})

(defn invoice-amount
  "The network and the amount of the BOLT 11 `invoice`, from the part
  before its data: `{:network :bitcoin :msat 250000}`, without `:msat` for
  an invoice of any amount. Nil for text that is no invoice.

  The amount is in millisatoshis. A pico amount must end in 0, as BOLT 11
  requires. This reads no signature: the wallet checks the invoice when it
  pays."
  [invoice]
  (when (and (string? invoice)
             (<= (count invoice) 7089)
             (or (= invoice (str/lower-case invoice)) (= invoice (str/upper-case invoice))))
    (let [s (str/lower-case invoice)]
      (when-let [[_ prefix digits unit] (re-matches #"ln(bcrt|bc|tbs|tb|sb)(?:([0-9]{1,16})([munp]?))?"
                                                    (subs s 0 (max 0 (or (str/last-index-of s "1") 0))))]
        (let [network (networks prefix)
              n       (some-> digits parse-long)]
          (cond
            (nil? n)     {:network network}
            (= "p" unit) (when (zero? (rem n 10))
                           {:network network :msat (quot n 10)})
            (<= n (quot 9007199254740991 (multipliers unit)))
            {:network network :msat (* n (multipliers unit))}))))))

(defn- success-action
  "The success action `a` of an answer to a request to `callback`, as
  LUD-09 and LUD-10 define it: nil for none, and ::refused for one of
  another kind, with a URL on another host than the callback, or with a
  ciphertext of another size. A message and a description are cut to the
  144 characters that LUD-09 allows."
  [a callback]
  (when (map? a)
    (let [text  #(json/text (get a %))
          shown #(some-> (text %) (value/cut 144))]
      (case (get a "tag")
        "message" {:tag "message" :message (shown "message")}
        "url"     (let [url (get a "url")]
                    (if (and (secure-url? url) (= (http/host url) (http/host callback)))
                      (cond-> {:tag "url" :url url}
                        (shown "description") (assoc :description (shown "description")))
                      ::refused))
        "aes"     (let [ciphertext (text "ciphertext")
                        iv         (text "iv")]
                    (if (and ciphertext iv (<= (count ciphertext) 4096) (= 24 (count iv)))
                      (cond-> {:tag "aes" :ciphertext ciphertext :iv iv}
                        (shown "description") (assoc :description (shown "description")))
                      ::refused))
        ::refused))))

(defn invoice
  "The invoice in the `response` to the `invoice-request` for `msat`
  millisatoshis from `terms`, or `{:error diagnostic}`.

      {:invoice        \"lnbc…\"  ; for the wallet to pay
       :msat           25000
       :network        :bitcoin
       :success-action {:tag \"message\" :message \"Thanks!\"}
       :verify         \"https://…\"}  ; tells whether it is paid, LUD-21

  An invoice that asks for another amount is an error, as LUD-06 requires.
  So is a success action of a kind that LUD-09 does not define, or with a
  URL on another host than the callback. Show the success action after the
  payment. An action of the tag aes holds `:ciphertext` and `:iv`, which
  the preimage of the payment decrypts."
  [{:keys [callback]} msat response]
  (let [raw (answer response)
        url (:url response)]
    (or (insecure url)
        (refusal raw url)
        (let [pr     (get raw "pr")
              amount (invoice-amount pr)
              action (success-action (get raw "successAction") callback)
              verify (get raw "verify")]
          (cond
            (nil? amount)         (error {:id :value/malformed :url url :status (:status response)})
            (not= msat (:msat amount))
            (error {:id :value/wrong-amount :url url :msat msat :invoice-msat (:msat amount)})

            (= ::refused action)  (error {:id :value/unknown-success-action :url url})
            :else                 (cond-> {:invoice pr :msat msat :network (:network amount)}
                                    action               (assoc :success-action action)
                                    (secure-url? verify) (assoc :verify verify)))))))

(defn node
  "The node that the `response` to a `keysend-request` names, as the keys
  of a recipient of type node, or `{:error diagnostic}`.

      {:type \"node\" :address \"02…\" :custom-key \"696969\" :custom-value \"1\"}

  Merged into a recipient of type lnaddress, it keeps the name, the split
  and the fee of the recipient. A service that holds many wallets on one
  node names the wallet with the custom key and value."
  [response]
  (let [raw    (answer response)
        url    (:url response)
        pubkey (get raw "pubkey")]
    (or (insecure url)
        (refusal raw url)
        (if (and (= "keysend" (get raw "tag")) (string? pubkey) (re-matches #"0[23][0-9a-fA-F]{64}" pubkey))
          (let [data  (first (filter map? (let [x (get raw "customData")] (if (sequential? x) x []))))
                k     (json/text (get data "customKey"))
                v     (json/text (get data "customValue"))]
            (cond-> {:type "node" :address (str/lower-case pubkey)}
              (and k v) (assoc :custom-key k :custom-value v)))
          (error {:id :value/no-keysend :url url :status (:status response)})))))

(defn- exchange!
  "The response to `request`, sent by the `:send` of `opts` within its
  limits, or `{:error diagnostic}` when it could not be sent; deferred."
  [request {:keys [send timeout-seconds max-bytes]}]
  (async/then (async/attempt #(send (assoc request :timeout-seconds timeout-seconds :max-bytes max-bytes)))
              (fn [{:keys [value error]}]
                (if error
                  {:error (diagnostics/describe {:id :value/unreachable :url (:url request) :reason (async/explain error)})}
                  value))))

(defn invoice!
  "An invoice from the Lightning `address` for `msat` millisatoshis, as
  `invoice` reads it, or `{:error diagnostic}`. On the JVM the map, in
  ClojureScript a promise of it.

  It sends the `lnurlp-request` and the `invoice-request`, with the
  `:comment` of `opts`. The comment is text, or a function of the terms of
  the address that gives the text. The function fits a comment to the
  `:comment-length` of the terms, as `value.metadata/payment-comment` does.

  The other options are `:send`, a function that sends a request map in
  place of `http/call-public!`, and the limits of `default-limits`. The
  domain of an address in a feed chooses the URLs that this fetches. So it
  sends nothing to the network of the app, unless the app gives
  `http/call!` as `:send`."
  ([address msat]
   (invoice! address msat {}))
  ([address msat opts]
   (let [opts (merge default-limits {:send http/call-public!} opts)]
     (async/then (lnurlp-request address)
                 (fn [request]
                   (if-not request
                     (error {:id :value/not-an-address :address (str address)})
                     (async/then (exchange! request opts)
                                 (fn [response]
                                   (let [offer (if (:error response) response (terms response))]
                                     (cond
                                       (:error offer)               offer
                                       (not (sendable? offer msat))
                                       (error {:id :value/amount-out-of-range :msat msat
                                               :min-msat (:min-msat offer) :max-msat (:max-msat offer)})

                                       :else
                                       (let [comment (:comment opts)
                                             text    (if (fn? comment) (comment offer) comment)]
                                         (async/then (exchange! (invoice-request offer msat text) opts)
                                                     #(if (:error %) % (invoice offer msat %))))))))))))))

(defn node!
  "The node that pays the Lightning `address` with keysend, as `node` reads
  it, or `{:error diagnostic}`. On the JVM the map, in ClojureScript a
  promise of it. `opts` are those of `invoice!`, but the comment."
  ([address]
   (node! address {}))
  ([address opts]
   (let [opts (merge default-limits {:send http/call-public!} opts)]
     (async/then (keysend-request address)
                 (fn [request]
                   (if-not request
                     (error {:id :value/not-an-address :address (str address)})
                     (async/then (exchange! request opts)
                                 #(if (:error %) % (node %)))))))))
