(ns dk.simongray.podcast.value.lnaddress
  "Lightning addresses, which a podcast:valueRecipient of type lnaddress
  names, and how to pay them. An address has the form user@domain.

  The requests ask the domain of an address, as LUD-16 and the podcast
  namespace define them, and the readers read the answers. The wallet of
  the app pays.

  LNURL-pay gives an invoice for an amount. The lnurlp-request function
  asks the domain for the terms of the address, and terms reads them. The
  terms are the callback, the amounts that it takes, and how long a
  comment can be. The invoice-request function asks the callback for an
  invoice, with a comment such as the one of value.metadata. The invoice
  function reads the answer. It checks that the invoice asks for the
  amount that was requested, as LUD-06 requires.

  The keysend lookup gives a node instead. The keysend-request function
  asks for it, and node reads the answer into the keys of a recipient of
  type node. The app pays that recipient with keysend and the records of
  value/records.

  The request-invoice! and fetch-node! functions send the requests
  themselves. A reader goes by the JSON of an answer, not by its status,
  as LUD-01 asks. Services answer an error with any status, and some
  answer the keysend lookup with a web page."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value :as value]))

(def default-limits
  "The limits of request-invoice! and fetch-node! that their options don't
  set: the seconds that each request can take, and the size of an answer
  in bytes.

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
  (when (string? s)
    (when-let [[_ user domain] (re-matches #"([a-z0-9_.+-]*)@([^@\s/?#\\:]*[^@\s/?#\\:.])\.?" (str/lower-case (str/trim s)))]
      (let [host (http/host (str "https://" domain "/"))]
        (when (and host
                   (http/public-name? host)
                   (not (re-matches #"[0-9.]+" host)))
          {:user (if (= "" user) "_" user) :domain domain})))))

(defn ^:no-doc well-known
  "The URL of the well-known answer of `kind` about the `parts` of an
  address: http for an onion service, as LUD-01 has it, or else https."
  [{:keys [user domain] :as parts} kind]
  (str (if (str/ends-with? (first (str/split domain #":")) ".onion") "http" "https")
       "://" domain "/.well-known/" kind "/" user))

(defn lnurlp-request
  "The request for the terms on which the Lightning `address` takes a
  payment with LNURL-pay, or nil when `address` isn't one."
  [address]
  (when-let [parts (parse address)]
    {:method :get :url (well-known parts "lnurlp") :headers {"Accept" "application/json"}}))

(defn keysend-request
  "The request for the node that pays the Lightning `address` with
  keysend, or nil when `address` isn't one."
  [address]
  (when-let [parts (parse address)]
    {:method :get :url (well-known parts "keysend") :headers {"Accept" "application/json"}}))

(defn ^:no-doc failure
  "A map with the diagnostic `d` under :error."
  [d]
  {:error (diagnostics/describe d)})

(defn ^:no-doc refusal
  "The error of the JSON `raw` of an answer from `url` whose status is
  ERROR, with its reason, or nil for another answer."
  [raw url]
  (when (= "ERROR" (get raw "status"))
    (failure {:id :value/lnurl-error :url url :reason (json/text (get raw "reason"))})))

(defn ^:no-doc secure-url?
  "Whether `s` is an https URL, or an http URL of an onion service, as
  LUD-01 allows."
  [s]
  (let [[scheme host] (http/origin s)]
    (or (= "https" scheme)
        (and (= "http" scheme) (str/ends-with? host ".onion")))))

(defn ^:no-doc insecure
  "The error of an answer that came from `url` over plain http, where
  anyone on the way can change it. It's nil for an https URL, an onion
  service, or an answer that names no URL."
  [url]
  (when (and url (not (secure-url? url)))
    (failure {:id :value/insecure :url url})))

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
  (let [entries (when (string? metadata) (json/parse metadata))]
    (some #(when (and (sequential? %) (= mime (first %)) (string? (second %))) (second %))
          (when (sequential? entries) entries))))

(defn terms
  "The terms on which a Lightning address takes a payment, from the
  `response` to its lnurlp-request, or a map with a diagnostic under
  :error.

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
  (let [raw (json/object (:body response))
        url (:url response)]
    (or (insecure url)
        (refusal raw url)
        (let [callback    (get raw "callback")
              low         (whole-of (get raw "minSendable") :up)
              high        (whole-of (get raw "maxSendable") :down)
              metadata    (get raw "metadata")
              description (or (entry metadata "text/long-desc") (entry metadata "text/plain"))
              identifier  (or (entry metadata "text/identifier") (entry metadata "text/email"))]
          (if (and (= "payRequest" (get raw "tag")) (secure-url? callback) low high (<= low high))
            (cond-> {:callback       callback
                     :min-msat       low
                     :max-msat       high
                     :comment-length (or (whole-of (get raw "commentAllowed") :down) 0)}
              description        (assoc :description description)
              identifier         (assoc :identifier identifier)
              (string? metadata) (assoc :metadata metadata))
            (failure {:id :value/malformed :url url :status (:status response)}))))))

(defn sendable?
  "Whether a Lightning address with `terms` takes a payment of `msat`
  millisatoshis."
  [{:keys [min-msat max-msat] :as terms} msat]
  (boolean (and (integer? msat) min-msat max-msat (<= min-msat msat max-msat))))

;; LUD-12
(defn sent-comment
  "The comment that goes out with a request to the callback of `terms`:
  `comment`, cut to the :comment-length of the terms. It's nil when the
  terms take no comment, and for a comment that's blank or isn't text."
  [{:keys [comment-length] :as terms} comment]
  (when (and (string? comment) (not (str/blank? comment)) (pos? (or comment-length 0)))
    (value/cut comment comment-length)))

(defn invoice-request
  "The request to the callback of `terms` for an invoice of `msat`
  millisatoshis, with a `comment` when the terms take one.

  The comment is the text that sent-comment gives. The amount and the
  comment follow the query that the callback has. It throws when the
  terms don't take `msat`, as sendable? tells."
  ([terms msat]
   (invoice-request terms msat nil))
  ([{:keys [callback] :as terms} msat comment]
   (when-not (sendable? terms msat)
     (throw (ex-info "The address does not take this amount"
                     {:type ::amount :msat msat :min-msat (:min-msat terms) :max-msat (:max-msat terms)})))
   {:method  :get
    :url     (str callback (if (str/includes? callback "?") "&" "?")
                  (http/query-string {"amount" msat "comment" (sent-comment terms comment)}))
    :headers {"Accept" "application/json"}}))

(def ^:no-doc max-invoice-length
  "The most characters of an invoice that podcast-clj reads: the most
  digits that one QR code holds. BOLT 11 gives no limit, so the limit is a
  choice."
  7089)

(def ^:no-doc networks
  "The network of each prefix of a BOLT 11 invoice (part Human-Readable
  Part), and sb, which btcd has for its simnet."
  {"bc" :bitcoin "tb" :testnet "tbs" :signet "bcrt" :regtest "sb" :simnet})

(def ^:no-doc multipliers
  "The millisatoshis in one of each unit of an amount in a BOLT 11 invoice,
  a fraction of a bitcoin."
  {"" 100000000000 "m" 100000000 "u" 100000 "n" 100})

(defn invoice-amount
  "The network and the amount of the BOLT 11 `invoice` from the part
  before its data, as a map of :network and :msat, or nil for text that
  isn't an invoice. An invoice of any amount has no :msat.

  The amount is in millisatoshis. It starts with no zero, and a pico amount
  must end in 0, as BOLT 11 requires. After the amount come a 1 and the
  data, which are six or more characters of Bech32. This reads no
  signature, since the wallet checks the invoice when it pays."
  [invoice]
  (when (and (string? invoice)
             (<= (count invoice) max-invoice-length)
             (or (= invoice (str/lower-case invoice)) (= invoice (str/upper-case invoice))))
    (when-let [[_ prefix digits unit] (re-matches #"ln(bcrt|bc|tbs|tb|sb)(?:([1-9][0-9]{0,15})([munp]?))?1[02-9ac-hj-np-z]{6,}"
                                                  (str/lower-case invoice))]
      (let [network (networks prefix)]
        (cond
          (nil? digits) {:network network}
          ;; a tenth of the amount, from its digits, since JavaScript holds no
          ;; whole number of sixteen digits
          (= "p" unit)  (when (str/ends-with? digits "0")
                          {:network network :msat (parse-long (subs digits 0 (dec (count digits))))})
          :else         (let [n (parse-long digits)]
                          (when (and n (<= n (quot 9007199254740991 (multipliers unit))))
                            {:network network :msat (* n (multipliers unit))})))))))

(defn- tagged-fields
  "The tagged fields in the data `words` of an invoice, numbers of five
  bits, as pairs of a type and data in their order. It's nil for data
  that aren't those of an invoice.

  The section Data Part of BOLT 11 defines the data: a time of 7 numbers,
  the tagged fields, and a signature of 104 numbers. A field is a type,
  a length of two numbers and its data."
  [words]
  (let [end (- (count words) 104)]
    (loop [i 7 out []]
      (cond
        (= i end)       out
        (< end (+ i 3)) nil
        :else
        (let [n    (long (+ (* 32 (nth words (+ i 1))) (nth words (+ i 2))))
              next (+ i 3 n)]
          (when (<= next end)
            (recur next (conj out [(nth words i) (subvec words (+ i 3) next)]))))))))

(defn- whole-bytes
  "The whole bytes in the numbers of five bits `words`, the highest bit
  first, in hexadecimal."
  [words]
  (let [bits (for [w words, i [4 3 2 1 0]] (bit-and 1 (bit-shift-right w i)))]
    (bytes/hex (bytes/of (map #(reduce (fn [acc bit] (+ (* 2 acc) bit)) 0 %) (partition 8 bits))))))

(defn payment-hash
  "The payment hash of the BOLT 11 `invoice`, as hexadecimal text of 32
  bytes, or nil for a text that isn't an invoice with one payment hash.

  The hash is the tagged field p, with 52 numbers of five bits, as the
  section Tagged Fields of BOLT 11 defines it. The reader checks the
  checksum of the invoice. It reads no signature, because the wallet
  checks the invoice when it pays."
  [invoice]
  (when (invoice-amount invoice)
    (when-let [fields (some-> (bech32/decode-words invoice {:max-length max-invoice-length}) :words tagged-fields)]
      (let [hashes (filter #(= 1 (first %)) fields)]
        (when (and (= 1 (count hashes)) (= 52 (count (second (first hashes)))))
          (whole-bytes (second (first hashes))))))))

(defn proves?
  "Whether `preimage` is the proof that somebody paid the BOLT 11
  `invoice`: its SHA-256 is the payment hash of the invoice.

  A wallet gets the preimage from the recipient when the payment arrives,
  and no one can guess it. The `preimage` is hexadecimal text of 32
  bytes. It's false for another text, and for an invoice without a
  payment hash that payment-hash reads."
  [preimage invoice]
  (boolean (and (string? preimage)
                (re-matches #"[0-9a-fA-F]{64}" preimage)
                (when-let [hash (payment-hash invoice)]
                  (= hash (bytes/hex (bytes/sha256 (bytes/from-hex preimage))))))))

(defn- success-action
  "The success action `a` of an answer to a request to `callback`, as
  LUD-09 and LUD-10 define it, or nil for none.

  It's ::refused for an action of another kind, with a URL on another
  host than the callback, or with a ciphertext of another size. A message
  and a description are cut to the 144 characters that LUD-09 allows."
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
  "The invoice in the `response` to the invoice-request for `msat`
  millisatoshis from `terms`, or a map with a diagnostic under :error.

      {:invoice        \"lnbc…\"  ; for the wallet to pay
       :msat           25000
       :network        :bitcoin
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
              amount (invoice-amount pr)
              action (success-action (get raw "successAction") callback)
              verify (get raw "verify")]
          ;; LUD-06 has the wallet check the description hash too. It isn't
          ;; checked: the service writes both the terms and the invoice, and
          ;; some services hash other terms than they serve.
          (cond
            (nil? amount)         (failure {:id :value/malformed :url url :status (:status response)})
            (not= msat (:msat amount))
            (failure {:id :value/wrong-amount :url url :msat msat :invoice-msat (:msat amount)})

            (= ::refused action)  (failure {:id :value/unknown-success-action :url url})
            :else                 (cond-> {:invoice pr :msat msat :network (:network amount)}
                                    action               (assoc :success-action action)
                                    (secure-url? verify) (assoc :verify verify)))))))

(defn node
  "The node that the `response` to a keysend-request names, as the keys of
  a recipient of type node, or a map with a diagnostic under :error.

      {:type \"node\" :address \"02…\" :custom-key \"696969\" :custom-value \"1\"}

  Merged into a recipient of type lnaddress, it keeps the name, the split
  and the fee of the recipient. A service that holds many wallets on one
  node names the wallet with the custom key and value."
  [response]
  (let [raw    (json/object (:body response))
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
          (failure {:id :value/no-keysend :url url :status (:status response)})))))

(defn ^:no-doc send!
  "Send `request` by the :send of `opts` within its limits, and give the
  response, in a future or a promise. For a request that failed, it's a
  map with a diagnostic under :error."
  [request {:keys [send] :as opts}]
  (async/then (http/try-send! send request opts)
              (fn [{:keys [value] e :error}]
                (if e
                  (failure {:id :value/unreachable :url (:url request) :reason (async/explain e)})
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
                  (failure {:id :value/not-an-address :address (str address)})
                  (async/then (send! request opts)
                              #(if (:error %) % (read %)))))))

(defn- fetch-invoice!
  "Ask the callback of the terms `offer` for an invoice of `msat`
  millisatoshis with the comment `text` and `opts`, and give it as invoice
  reads it, in a future or a promise.

  The invoice has the :comment that went out, and a failure gives a map
  with a diagnostic under :error."
  [offer msat text opts]
  (let [sent (sent-comment offer text)]
    (async/then (send! (invoice-request offer msat text) opts)
                (fn [response]
                  (let [read (if (:error response) response (invoice offer msat response))]
                    (cond-> read
                      (and sent (not (:error read))) (assoc :comment sent)))))))

(defn request-invoice!
  "Request an invoice from the Lightning `address` for `msat` millisatoshis
  with `opts`, as invoice reads it. On the JVM it gives the invoice, and
  in ClojureScript a promise of it.

  A failure gives a map with a diagnostic under :error. This sends the
  lnurlp-request and the invoice-request, with the :comment of `opts`.
  The comment is text, or a function of the terms of the address that
  gives the text. The function fits a comment to the :comment-length of
  the terms, as value.metadata/payment-comment does. The invoice has the
  :comment that went out, as sent-comment cut it. An invoice without one
  went out with no comment, so the recipient doesn't learn what the
  payment is for.

  The other `opts` are these:

  - the limits of default-limits
  - :allow?, which URLs a request can go to
  - :send, a function that sends a request map in place of http/call!

  The domain of an address in a feed chooses the URLs that this fetches.
  So each request carries http/public? as its :allow?, and http/call!
  then sends nothing to a private network. To reach your own network,
  give (constantly true) as :allow?."
  ([address msat]
   (request-invoice! address msat {}))
  ([address msat {:keys [comment] :as opts}]
   (let [opts (merge default-limits {:send http/call! :allow? http/public?} opts)]
     (async/then (ask! (lnurlp-request address) address terms opts)
                 (fn [offer]
                   (cond
                     (:error offer)
                     offer

                     (not (sendable? offer msat))
                     (failure {:id       :value/amount-out-of-range
                               :msat     msat
                               :min-msat (:min-msat offer)
                               :max-msat (:max-msat offer)})

                     :else
                     (fetch-invoice! offer msat (if (fn? comment) (comment offer) comment) opts)))))))

(defn fetch-node!
  "Fetch the node that pays the Lightning `address` with keysend, with
  `opts`, as node reads it. On the JVM it gives the node, and in
  ClojureScript a promise of it.

  A failure gives a map with a diagnostic under :error. The `opts` are
  those of request-invoice!, except the comment."
  ([address]
   (fetch-node! address {}))
  ([address opts]
   (let [opts (merge default-limits {:send http/call! :allow? http/public?} opts)]
     (ask! (keysend-request address) address node opts))))

(comment
  (parse "Ann@Example.org")
  (parse "ann@localhost")
  (lnurlp-request "ann@example.org")

  ;; the terms of an address, from the answer of its service
  (def sample-terms
    (terms {:status 200
            :url    "https://example.org/.well-known/lnurlp/ann"
            :body   (json/write-str {"tag"            "payRequest"
                                     "callback"       "https://example.org/pay/ann"
                                     "minSendable"    1000
                                     "maxSendable"    100000000
                                     "commentAllowed" 40
                                     "metadata"       "[[\"text/plain\",\"Pay Ann\"]]"})}))

  (sendable? sample-terms 25000)
  (sent-comment sample-terms "A comment that is longer than the forty characters of the terms")
  (invoice-request sample-terms 25000 "Thanks!")

  ;; 250,000 millisatoshis, from the part of an invoice before its data
  (invoice-amount "lnbc2500n1pvjluezsp5zyg3zyg3zyg3zyg3")

  (node {:status 200
         :url    "https://example.org/.well-known/keysend/ann"
         :body   (json/write-str {"tag"        "keysend"
                                  "pubkey"     (apply str "02" (repeat 64 "a"))
                                  "customData" [{"customKey" "696969" "customValue" "1"}]})})

  (request-invoice! "ann@example.org" 25000 {:comment "Thanks!"})
  (fetch-node! "ann@example.org")
  #_.)
