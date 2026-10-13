(ns dk.simongray.podcast.value.lnaddress
  "Lightning addresses, which a podcast:valueRecipient of type lnaddress
  names, and how to pay them. An address has the form user@domain.

  The requests ask the domain of an address, as LUD-16 and the podcast
  namespace define them, and the readers read the answers. The wallet of
  the app pays.

  LNURL-pay gives an invoice for an amount. The domain gives the terms of
  the address: the callback, the amounts that it takes, and how long a
  comment can be. The callback gives the invoice, which must ask for the
  amount that was requested, as LUD-06 requires. The keysend lookup gives
  a node instead, to pay with keysend.

  A reader goes by the JSON of an answer, not by its status, as LUD-01
  asks. Services answer an error with any status, and some answer the
  keysend lookup with a web page."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value.bolt11 :as bolt11]
            [dk.simongray.podcast.value.payment :as value.payment]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(def default-limits
  "The limits of the requests to a Lightning address that their options
  don't set: the seconds that each request can take, and the size of an
  answer in bytes.

  The terms of an address can hold an image of about 136 kilobytes, by
  LUD-06."
  {:timeout-seconds 10
   :max-bytes       (* 256 1024)})

(defn parse
  "The parts of the Lightning address `s` as a map of :user and :domain, in
  lower case, or nil when `s` isn't one.

  A user name holds the letters a to z, the digits, and the characters
  minus, underscore, dot and plus, as LUD-16 allows. An address of
  @domain alone is short for the default identifier of the domain, with
  the user name underscore, as LUD-16 has it.

  The domain is a name on the public internet. It isn't an IP address,
  and it has a dot in it. It isn't a name that stays on a local network,
  such as localhost, .local, .internal or .home.arpa. It has no port, and
  the dot that can end a name is left out. A feed chooses the address,
  and so the domain that the app asks."
  [s]
  (let [form  #"([a-z0-9_.+-]*)@([^@\s/?#\\:]*[^@\s/?#\\:.])\.?"
        found (when (string? s)
                (re-matches form (str/lower-case (str/trim s))))]
    (when-let [[_ user domain] found]
      (let [host (url/host (str "https://" domain "/"))]
        (when (and host (url/public-name? host))
          {:user (if (= "" user) "_" user) :domain domain})))))

(defn ^:no-doc well-known
  "The URL of the well-known answer of `kind` about the `parts` of an
  address: http for an onion service, as LUD-01 has it, or else https."
  [{:keys [user domain] :as parts} kind]
  (let [host   (first (str/split domain #":"))
        scheme (if (str/ends-with? host ".onion") "http" "https")]
    (str scheme "://" domain "/.well-known/" kind "/" user)))

(defn lnurlp-request
  "The request for the terms on which the Lightning `address` takes a
  payment with LNURL-pay, or nil when `address` isn't one."
  [address]
  (when-let [parts (parse address)]
    {:method  :get
     :url     (well-known parts "lnurlp")
     :headers {"Accept" "application/json"}}))

(defn keysend-request
  "The request for the node that pays the Lightning `address` with
  keysend, or nil when `address` isn't one."
  [address]
  (when-let [parts (parse address)]
    {:method  :get
     :url     (well-known parts "keysend")
     :headers {"Accept" "application/json"}}))

(defn ^:no-doc refusal
  "The error of the JSON `raw` of an answer from `url` whose status is
  ERROR, with its reason, or nil for another answer."
  [raw url]
  (when (= "ERROR" (get raw "status"))
    (diagnostics/failed {:id     :value/lnurl-error
                         :url    url
                         :reason (json/text (get raw "reason"))})))

(defn ^:no-doc secure-url?
  "Whether `s` is an https URL, or an http URL of an onion service, as
  LUD-01 allows."
  [s]
  (let [[scheme host] (url/origin s)]
    (or (= "https" scheme)
        (and (= "http" scheme) (str/ends-with? host ".onion")))))

(defn ^:no-doc insecure
  "The error of an answer that came from `url` over plain http, where
  anyone on the way can change it. It's nil for an https URL, an onion
  service, or an answer that names no URL."
  [url]
  (when (and url (not (secure-url? url)))
    (diagnostics/failed {:id :value/insecure :url url})))

(defn- whole-of
  "The whole number above zero that the JSON number `x` gives, rounded up
  for a lower bound and down for an upper, as `round` says."
  [x round]
  (when (and (number? x) (< 0 x 9e15))
    (long (if (= :up round) (Math/ceil x) (Math/floor x)))))

(defn- entry
  "The text of the first entry of the type `mime` in the `metadata` of
  LNURL-pay, or nil when it has none. The metadata is the JSON of a list
  of pairs of a type and a text."
  [metadata mime]
  (let [entries (when (string? metadata) (json/parse metadata))
        text-of #(when (and (sequential? %)
                            (= mime (first %))
                            (string? (second %)))
                   (second %))]
    (some text-of (when (sequential? entries) entries))))

;; LUD-18: the details of the payer that a service takes, but auth, which
;; LNURL-auth signs
(def ^:no-doc payer-fields
  "The details of a payer that a service can take, by their name in
  LUD-18."
  {"name" :name "pubkey" :pubkey "identifier" :identifier "email" :email})

(defn- payer-data
  "The details of the payer that the `raw` payerData of a service takes,
  as a map of each to whether it's :mandatory?, or nil."
  [raw]
  (when (map? raw)
    (not-empty (into {}
                     (for [[field k] payer-fields
                           :let  [spec (get raw field)]
                           :when (map? spec)]
                       [k {:mandatory? (true? (get spec "mandatory"))}])))))

(defn terms
  "The terms on which a Lightning address takes a payment, from the
  `response` to the request for its terms, or a map with a diagnostic
  under :error.

      {:callback       \"https://…\"  ; where to ask for an invoice
       :min-msat       1000
       :max-msat       100000000
       :comment-length 255          ; how long a comment can be, 0 for none
       :description    \"Pay Ann\"   ; the text of the metadata
       :identifier     \"ann@…\"     ; the address, as the metadata names it
       :metadata       \"[[…]]\"     ; the metadata, as the service wrote it
       :payer-data     {:name {:mandatory? false}}}  ; LUD-18

  The callback is an https URL, or http for an onion service. It can be on
  another host than the address. The :payer-data names the details of the
  payer that the service takes: :name, :pubkey, :identifier and :email."
  [response]
  (let [raw (json/object (:body response))
        url (:url response)]
    (or (insecure url)
        (refusal raw url)
        (let [callback    (get raw "callback")
              low         (whole-of (get raw "minSendable") :up)
              high        (whole-of (get raw "maxSendable") :down)
              comments    (whole-of (get raw "commentAllowed") :down)
              metadata    (get raw "metadata")
              description (or (entry metadata "text/long-desc")
                              (entry metadata "text/plain"))
              identifier  (or (entry metadata "text/identifier")
                              (entry metadata "text/email"))
              payer       (payer-data (get raw "payerData"))
              valid?      (and (= "payRequest" (get raw "tag"))
                               (secure-url? callback)
                               low
                               high
                               (<= low high))]
          (if valid?
            (cond-> {:callback       callback
                     :min-msat       low
                     :max-msat       high
                     :comment-length (or comments 0)}
              description        (assoc :description description)
              identifier         (assoc :identifier identifier)
              (string? metadata) (assoc :metadata metadata)
              payer              (assoc :payer-data payer))
            (diagnostics/failed {:id     :value/malformed
                                 :url    url
                                 :status (:status response)}))))))

(defn sendable?
  "Whether a Lightning address with `terms` takes a payment of `msat`
  millisatoshis."
  [{:keys [min-msat max-msat] :as terms} msat]
  (boolean (and (integer? msat)
                min-msat
                max-msat
                (<= min-msat msat max-msat))))

;; LUD-12
(defn ^:no-doc sent-comment
  "The comment that goes out with a request to the callback of `terms`:
  `comment`, cut to the :comment-length of the terms. It's nil when the
  terms take no comment, and for a comment that's blank or isn't text."
  [{:keys [comment-length] :as terms} comment]
  (when (and (string? comment)
             (not (str/blank? comment))
             (pos? (or comment-length 0)))
    (value.payment/cut comment comment-length)))

;; LUD-18: the payerdata of the request is JSON of the details
(defn ^:no-doc sent-payer
  "The JSON of the details of the `payer` that go out with a request to the
  callback of `terms`: those that the terms take, as text, or nil for
  none."
  [{:keys [payer-data] :as terms} payer]
  (let [sent (into {}
                   (for [[field k] payer-fields
                         :let  [v (get payer k)]
                         :when (and (contains? payer-data k) (string? v))]
                     [field v]))]
    (when (seq sent)
      (json/write-str sent))))

(defn invoice-request
  "The request to the callback of `terms` for an invoice of `msat`
  millisatoshis, with a `comment` when the terms take one, and the details
  of the `payer` that they take, as LUD-18 has it.

  The comment is cut to fit the terms. The `payer` is a map of :name,
  :pubkey, :identifier and :email, e.g. {:name \"Ann\"}, and those that
  the :payer-data of the terms doesn't name stay out. The amount, the
  comment and the details follow the query that the callback has. It
  throws when the terms don't take `msat`."
  ([terms msat]
   (invoice-request terms msat nil nil))
  ([terms msat comment]
   (invoice-request terms msat comment nil))
  ([{:keys [callback] :as terms} msat comment payer]
   (when-not (sendable? terms msat)
     (throw (ex-info "The address does not take this amount"
                     {:type     ::amount
                      :msat     msat
                      :min-msat (:min-msat terms)
                      :max-msat (:max-msat terms)})))
   (let [query (url/query-string {"amount"    msat
                                  "comment"   (sent-comment terms comment)
                                  "payerdata" (sent-payer terms payer)})]
     {:method  :get
      :url     (str callback (if (str/includes? callback "?") "&" "?") query)
      :headers {"Accept" "application/json"}})))

(defn- success-action
  "The success action `a` of an answer to a request to `callback`, as
  LUD-09 and LUD-10 define it, or nil for none.

  It's ::refused for an action of another kind, with a URL on another
  host than the callback, or with a ciphertext of another size. A message
  and a description are cut to the 144 characters that LUD-09 allows."
  [a callback]
  (when (map? a)
    (let [text      #(json/text (get a %))
          shown     #(some-> (text %) (value.payment/cut 144))
          described #(cond-> %
                       (shown "description")
                       (assoc :description (shown "description")))]
      (case (get a "tag")
        "message"
        {:tag "message" :message (shown "message")}

        "url"
        (let [url (get a "url")]
          (if (and (secure-url? url)
                   (= (url/host url) (url/host callback)))
            (described {:tag "url" :url url})
            ::refused))

        "aes"
        (let [ciphertext (text "ciphertext")
              iv         (text "iv")]
          (if (and ciphertext
                   iv
                   (<= (count ciphertext) 4096)
                   (= 24 (count iv)))
            (described {:tag "aes" :ciphertext ciphertext :iv iv})
            ::refused))

        ::refused))))

(defn invoice
  "The invoice in the `response` to the request for an invoice of `msat`
  millisatoshis from `terms`, or a map with a diagnostic under :error.

      {:invoice        \"lnbc…\"  ; for the wallet to pay
       :msat           25000
       :network        :bitcoin
       :expires-at     #inst \"…\" ; after which no wallet pays it
       :success-action {:tag \"message\" :message \"Thanks!\"}
       :verify         \"https://…\"}  ; tells whether it is paid, LUD-21

  An invoice that asks for another amount is an error, as LUD-06 requires.
  So is a success action of a kind that LUD-09 doesn't define, or with a
  URL on another host than the callback. Show the success action after the
  payment. An action of the tag aes holds :ciphertext and :iv, which the
  preimage of the payment decrypts."
  [{:keys [callback] :as terms} msat response]
  (let [raw (json/object (:body response))
        url (:url response)]
    (or (insecure url)
        (refusal raw url)
        (let [pr     (get raw "pr")
              amount (bolt11/invoice-amount pr)
              action (success-action (get raw "successAction") callback)
              verify (get raw "verify")]
          ;; LUD-06 has the wallet check the description hash too. It isn't
          ;; checked: the service writes both the terms and the invoice, and
          ;; some services hash other terms than they serve.
          (cond
            (nil? amount)
            (diagnostics/failed {:id     :value/malformed
                                 :url    url
                                 :status (:status response)})

            (not= msat (:msat amount))
            (diagnostics/failed {:id           :value/wrong-amount
                                 :url          url
                                 :msat         msat
                                 :invoice-msat (:msat amount)})

            (= ::refused action)
            (diagnostics/failed {:id :value/unknown-success-action :url url})

            :else
            (let [expires (bolt11/expires-at pr)]
              (cond-> {:invoice pr :msat msat :network (:network amount)}
                expires              (assoc :expires-at expires)
                action               (assoc :success-action action)
                (secure-url? verify) (assoc :verify verify))))))))

;; LUD-21
(defn verify-request
  "The request to the service of a Lightning address for whether the
  `invoice` that it gave is paid, to its :verify URL, or nil when it has
  none."
  [{:keys [verify] :as invoice}]
  (when verify
    {:method  :get
     :url     verify
     :headers {"Accept" "application/json"}}))

(defn verification
  "What the `response` to the verify-request of `invoice` tells, or a map
  with a diagnostic under :error: {:settled? false} while it's unpaid, and
  {:settled? true :preimage \"…\"} once it's paid.

  A payment counts only with the preimage that proves it, by
  bolt11/proves?, since the service writes the answer. A service that says
  that the invoice is paid without that proof is :value/unproven."
  [{pr :invoice :as invoice} response]
  (let [raw      (json/object (:body response))
        url      (:url response)
        settled  (get raw "settled")
        preimage (json/text (get raw "preimage"))]
    (or (insecure url)
        (refusal raw url)
        (cond
          (not (boolean? settled))
          (diagnostics/failed {:id     :value/malformed
                               :url    url
                               :status (:status response)})

          (not settled)
          {:settled? false}

          (bolt11/proves? preimage pr)
          {:settled? true :preimage preimage}

          :else
          (diagnostics/failed {:id :value/unproven :url url})))))

(defn node
  "The node that the `response` to the keysend lookup names, as the keys of
  a recipient of type node, or a map with a diagnostic under :error.

      {:type \"node\" :address \"02…\" :custom-key \"696969\"
       :custom-value \"1\"}

  Merged into a recipient of type lnaddress, it keeps the name, the split
  and the fee of the recipient. A service that holds many wallets on one
  node names the wallet with the custom key and value."
  [response]
  (let [raw    (json/object (:body response))
        url    (:url response)
        pubkey (get raw "pubkey")
        node?  (and (= "keysend" (get raw "tag"))
                    (value.payment/node-key? pubkey))
        listed (get raw "customData")]
    (or (insecure url)
        (refusal raw url)
        (if node?
          (let [data (first (filter map? (if (sequential? listed) listed [])))
                k    (json/text (get data "customKey"))
                v    (json/text (get data "customValue"))]
            (cond-> {:type "node" :address (str/lower-case pubkey)}
              (and k v) (assoc :custom-key k :custom-value v)))
          (diagnostics/failed {:id     :value/no-keysend
                               :url    url
                               :status (:status response)})))))

(defn ^:no-doc send!
  "Send `request` by the :send of `opts` within its limits, and give the
  response, in a future or a promise. For a request that failed, it's a
  map with a diagnostic under :error."
  [request {:keys [send] :as opts}]
  (async/then (http/try-send! send request opts)
              (fn [{:keys [value] e :error}]
                (if e
                  (diagnostics/failed {:id     :value/unreachable
                                       :url    (:url request)
                                       :reason (async/explain e)})
                  value))))

(defn ^:no-doc ask!
  "Send `request` for the Lightning `address` with `opts`, and give what
  `read` makes of the response, in a future or a promise.

  A failure gives a map with a diagnostic under :error. So does a nil
  request, which means that `address` isn't a Lightning address."
  [request address read opts]
  (async/then request
              (fn [request]
                (if-not request
                  (diagnostics/failed {:id      :value/not-an-address
                                       :address (str address)})
                  (async/then (send! request opts)
                              #(if (:error %) % (read %)))))))

(defn- fetch-invoice!
  "Ask the callback of the terms `offer` for an invoice of `msat`
  millisatoshis with the comment `text`, the details of the `payer` and
  `opts`, and give the invoice, in a future or a promise.

  The invoice has the :comment that went out, and a failure gives a map
  with a diagnostic under :error."
  [offer msat text payer opts]
  (let [sent (sent-comment offer text)]
    (async/then (send! (invoice-request offer msat text payer) opts)
                (fn [response]
                  (let [read (if (:error response)
                               response
                               (invoice offer msat response))]
                    (cond-> read
                      (and sent (not (:error read))) (assoc :comment sent)))))))

(defn request-invoice!
  "Request an invoice from the Lightning `address` for `msat` millisatoshis
  with `opts`. On the JVM it gives the invoice, and in ClojureScript a
  promise of it.

  A failure gives a map with a diagnostic under :error. This asks for the
  terms of the address and then for the invoice, with the :comment of
  `opts`. The comment is text, or a function of the terms that gives the
  text, e.g. to fit a comment to their :comment-length. The invoice has
  the :comment that went out, as it was cut. An invoice without one went
  out with no comment, so the recipient doesn't learn what the payment is
  for. The :payer of `opts` is the details of the payer, as
  invoice-request takes them, or a function of the terms that gives them.

  The other `opts` are these:

  - the limits of default-limits
  - :allow-pred, which URLs a request can go to
  - :send, a function that sends a request map in place of client/send!

  The domain of an address in a feed chooses the URLs that this fetches.
  So each request carries a guard as its :allow-pred, which lets nothing
  go to a private network. To reach your own network, give (constantly
  true) as :allow-pred."
  ([address msat]
   (request-invoice! address msat {}))
  ([address msat {:keys [comment payer] :as opts}]
   (let [defaults {:send client/send! :allow-pred url/public?}
         opts     (merge default-limits defaults opts)]
     (async/then (ask! (lnurlp-request address) address terms opts)
                 (fn [offer]
                   (cond
                     (:error offer)
                     offer

                     (not (sendable? offer msat))
                     (diagnostics/failed {:id       :value/amount-out-of-range
                                          :msat     msat
                                          :min-msat (:min-msat offer)
                                          :max-msat (:max-msat offer)})

                     :else
                     (let [text (if (fn? comment) (comment offer) comment)
                           who  (if (fn? payer) (payer offer) payer)]
                       (fetch-invoice! offer msat text who opts))))))))

(defn verify!
  "Ask the service of a Lightning address whether the `invoice` that
  request-invoice! gave is paid, with the `opts` of request-invoice!, and
  give what verification makes of the answer. On the JVM it gives the
  answer, and in ClojureScript a promise of it.

  A failure gives a map with a diagnostic under :error, and so does an
  invoice without a :verify URL, :value/unverifiable."
  ([invoice]
   (verify! invoice {}))
  ([invoice opts]
   (let [defaults {:send client/send! :allow-pred url/public?}
         opts     (merge default-limits defaults opts)]
     (async/then (verify-request invoice)
                 (fn [request]
                   (if request
                     (async/then (send! request opts)
                                 #(if (:error %) % (verification invoice %)))
                     (diagnostics/failed {:id :value/unverifiable})))))))

(defn fetch-terms!
  "Fetch the terms on which the Lightning `address` takes a payment, with
  `opts`. On the JVM it gives the terms, and in ClojureScript a promise of
  them.

  A failure gives a map with a diagnostic under :error. The `opts` are
  those of request-invoice!, except the comment and the payer."
  ([address]
   (fetch-terms! address {}))
  ([address opts]
   (let [defaults {:send client/send! :allow-pred url/public?}
         opts     (merge default-limits defaults opts)]
     (ask! (lnurlp-request address) address terms opts))))

(defn fetch-node!
  "Fetch the node that pays the Lightning `address` with keysend, with
  `opts`. On the JVM it gives the node, and in ClojureScript a promise of
  it.

  A failure gives a map with a diagnostic under :error. The `opts` are
  those of request-invoice!, except the comment."
  ([address]
   (fetch-node! address {}))
  ([address opts]
   (let [defaults {:send client/send! :allow-pred url/public?}
         opts     (merge default-limits defaults opts)]
     (ask! (keysend-request address) address node opts))))

(comment
  (parse "Ann@Example.org")
  (parse "ann@localhost")
  (lnurlp-request "ann@example.org")

  ;; the terms of an address, from the answer of its service
  (def sample-terms
    (terms {:status 200
            :url    "https://example.org/.well-known/lnurlp/ann"
            :body   (json/write-str
                     {"tag"            "payRequest"
                      "callback"       "https://example.org/pay/ann"
                      "minSendable"    1000
                      "maxSendable"    100000000
                      "commentAllowed" 40
                      "metadata"       "[[\"text/plain\",\"Pay Ann\"]]"})}))

  (sendable? sample-terms 25000)
  (sent-comment sample-terms
                (str "A comment that is longer than the forty characters"
                     " of the terms"))
  (invoice-request sample-terms 25000 "Thanks!")

  (node {:status 200
         :url    "https://example.org/.well-known/keysend/ann"
         :body   (json/write-str {"tag"        "keysend"
                                  "pubkey"     (apply str "02" (repeat 64 "a"))
                                  "customData" [{"customKey"   "696969"
                                                 "customValue" "1"}]})})

  (request-invoice! "ann@example.org" 25000 {:comment "Thanks!"})
  (fetch-node! "ann@example.org")
  #_.)
