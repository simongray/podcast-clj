(ns ^:no-doc dk.simongray.podcast.value.lnaddress.impl
  "The internals of value.lnaddress: the well-known URLs of LUD-16, the
  checks that every answer passes, the readers of the metadata, payer data
  and success actions inside an answer, and the sending of one request."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value.impl :as value.impl]
            [dk.simongray.podcast.value.lnaddress :as-alias lnaddress]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(defn well-known
  "The URL of the well-known answer of `kind` about the `parts` of an
  address: http for an onion service, as LUD-01 has it, or else https."
  [{:keys [user domain] :as parts} kind]
  (let [host   (first (str/split domain #":"))
        scheme (if (str/ends-with? host ".onion") "http" "https")]
    (str scheme "://" domain "/.well-known/" kind "/" user)))

(defn refusal
  "The error of the JSON `raw` of an answer from `url` whose status is
  ERROR, with its reason, or nil for another answer."
  [raw url]
  (when (= "ERROR" (get raw "status"))
    (diagnostics/failed {:id     :value/lnurl-error
                         :url    url
                         :reason (json/text (get raw "reason"))})))

(defn secure-url?
  "Whether `s` is an https URL, or an http URL of an onion service, as
  LUD-01 allows."
  [s]
  (let [[scheme host] (url/origin s)]
    (or (= "https" scheme)
        (and (= "http" scheme) (str/ends-with? host ".onion")))))

(defn insecure
  "The error of an answer that came from `url` over plain http, where
  anyone on the way can change it. It's nil for an https URL, an onion
  service, or an answer that names no URL."
  [url]
  (when (and url (not (secure-url? url)))
    (diagnostics/failed {:id :value/insecure :url url})))

(defn entry
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
(def payer-fields
  "The details of a payer that a service can take, by their name in
  LUD-18."
  {"name" :name "pubkey" :pubkey "identifier" :identifier "email" :email})

(defn payer-data
  "The details of the payer that the `raw` payerData of a service takes,
  as a map of each to whether it's :mandatory?, or nil."
  [raw]
  (when (map? raw)
    (not-empty (into {}
                     (for [[field k] payer-fields
                           :let  [spec (get raw field)]
                           :when (map? spec)]
                       [k {:mandatory? (true? (get spec "mandatory"))}])))))

;; LUD-12
(defn sent-comment
  "The comment that goes out with a request to the callback of `terms`:
  `comment`, cut to the :comment-length of the terms. It's nil when the
  terms take no comment, and for a comment that's blank or isn't text."
  [{:keys [comment-length] :as terms} comment]
  (when (and (string? comment)
             (not (str/blank? comment))
             (pos? (or comment-length 0)))
    (value.impl/cut comment comment-length)))

;; LUD-18: the payerdata of the request is JSON of the details
(defn sent-payer
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

(defn success-action
  "The success action `a` of an answer to a request to `callback`, as
  LUD-09 and LUD-10 define it, or nil for none.

  It's ::lnaddress/refused for an action of another kind, with a URL on another
  host than the callback, or with a ciphertext of another size. A message
  and a description are cut to the 144 characters that LUD-09 allows."
  [a callback]
  (when (map? a)
    (let [text      #(json/text (get a %))
          shown     #(some-> (text %) (value.impl/cut 144))
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
            ::lnaddress/refused))

        "aes"
        (let [ciphertext (text "ciphertext")
              iv         (text "iv")]
          (if (and ciphertext
                   iv
                   (<= (count ciphertext) 4096)
                   (= 24 (count iv)))
            (described {:tag "aes" :ciphertext ciphertext :iv iv})
            ::lnaddress/refused))

        ::lnaddress/refused))))

(defn send!
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

(defn ask!
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
