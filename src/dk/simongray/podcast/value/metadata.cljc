(ns dk.simongray.podcast.value.metadata
  "The RSS Payment Metadata of the podcast namespace: what a payment to a
  Lightning address is for. The comment of the payment tells it, and a URL
  that the comment names.

  A payment to a Lightning address carries only a short comment. So the
  comment is `rss::payment::boost https://… Great episode!`: the action,
  a URL and the start of the message. The URL answers with the header
  x-rss-payment, which holds the whole payment as JSON. The app that pays
  writes the comment with `payment-comment`, and serves the header that
  `header` writes. The host that receives the payment reads the comment
  with `parse-comment`. `fetch!` gets the header, and `parse-header` reads
  it. A payment is the map of `dk.simongray.podcast.value`.

  The sender writes the metadata, and the page at the URL is public. So
  the metadata is a claim, which `value/as-received` puts next to what the
  node received."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.value :as value]))

(def prefix
  "The start of the comment of a payment that names its metadata."
  "rss::payment::")

(def header-name
  "The header of the answer that holds the metadata of a payment."
  "x-rss-payment")

(def ^:no-doc record-keys
  "The name in the metadata of each key of a payment that is not simply its
  snake_case."
  {:msat "value_msat" :total-msat "value_msat_total" :usd "value_usd"})

(defn record
  "The metadata of `payment` as a map with the string keys of the JSON, for
  `dk.simongray.podcast.util.json/write-str`.

  `:action` becomes action, boost when it is absent. `:msat` and
  `:total-msat` become value_msat and value_msat_total, and `:sats` and
  `:total-sats` count in sats in their place. `:usd` becomes value_usd, an
  instant ISO 8601 text, and any other key its snake_case. Give the
  `:timestamp` of the payment, which the metadata holds."
  [{:keys [action] :as payment}]
  (let [[amount total] (value/amounts payment)]
    (cond-> (assoc (value/json-record payment record-keys #{:action :msat :total-msat :sats :total-sats})
                   "action" (name (or action :boost)))
      amount (assoc "value_msat" amount "value_msat_total" total))))

(defn header
  "The value of the header x-rss-payment for `payment`: its `record` as
  JSON, URI-encoded as encodeURIComponent does."
  [payment]
  (http/encode (json/write-str (record payment))))

(def ^:no-doc fields
  "The type of each field that the metadata defines: :text, :msat for an
  amount, or :number."
  {"id"                    :text
   "group"                 :text
   "action"                :text
   "split"                 :number
   "message"               :text
   "app_name"              :text
   "app_version"           :text
   "sender_id"             :text
   "sender_name"           :text
   "recipient_name"        :text
   "recipient_address"     :text
   "value_msat"            :msat
   "value_msat_total"      :msat
   "value_usd"             :number
   "timestamp"             :text
   "position"              :number
   "feed_medium"           :text
   "feed_guid"             :text
   "feed_title"            :text
   "item_guid"             :text
   "item_title"            :text
   "publisher_guid"        :text
   "publisher_title"       :text
   "remote_feed_guid"      :text
   "remote_item_guid"      :text
   "remote_publisher_guid" :text})

(defn parse-header
  "The payment that the value `s` of the header x-rss-payment describes,
  as `record` writes it: JSON, URI-encoded, or as it is.

  The fields of the metadata become the keys of a payment, and the other
  fields keep their names. The report tells what the reader forgave, as
  `value/parse-boostagram` tells it. A header that holds no JSON object
  reads as no payment."
  [s]
  (let [text (when (string? s) (str/trim s))
        raw  (value/json-object (if (and text (str/starts-with? text "{")) text (some-> text http/percent-decode)))]
    (if raw
      (let [read          (value/read-fields raw fields)
            v             #(first (get read %))
            other         (value/other-fields raw fields)
            [action said] (value/read-action (v "action"))
            timestamp     (some-> (v "timestamp") time/parse-date)
            names         (set/map-invert record-keys)]
        (with-meta (merge (cond-> (value/payment-of read names #{"action" "timestamp"})
                            action    (assoc :action action)
                            timestamp (assoc :timestamp timestamp))
                          other)
          {:podcast/report (mapv diagnostics/describe
                                 (cond-> (value/field-diagnostics read other #{})
                                   (and (v "timestamp") (nil? timestamp))
                                   (conj {:id :value/unreadable :keys ["timestamp"]})

                                   said (conj said)

                                   (not-any? v ["feed_guid" "feed_title" "remote_feed_guid"])
                                   (conj {:id :value/no-podcast})))}))
      (with-meta {} {:podcast/report [(diagnostics/describe {:id :value/not-an-object})]}))))

(defn payment-comment
  "The comment of a payment to a Lightning address that names the metadata
  of `payment` at `url`. Nil when `url` is no http or https URL, or when
  the comment does not fit without the message.

  The comment is `rss::payment::boost https://… Great episode!`. With
  `max-length`, it has that many characters at most, as JavaScript counts
  them. Give the `:comment-length` of the terms of the address. The
  message of the payment is cut to fit, and it ends in an ellipsis when it
  is cut."
  ([payment url]
   (payment-comment payment url ##Inf))
  ([{:keys [action message]} url max-length]
   (when (http/origin url)
     (let [head (str prefix (name (or action :boost)) " " url)
           text (some-> message str/trim not-empty)
           full (cond-> head text (str " " text))]
       (cond
         (> (count head) max-length)       nil
         (<= (count full) max-length)      full
         ;; a space, a character of the message and the ellipsis
         (< max-length (+ (count head) 3)) head
         :else                             (str (str/trimr (value/cut full (dec max-length))) "…"))))))

(defn parse-comment
  "What the comment `s` of a payment tells about its metadata, as `{:action
  :url :message}`. Nil when `s` does not start with `rss::payment::`.

  The message is the start of the message of the payment, and the metadata
  at the URL holds all of it. Fountain wrote the text undefined in place
  of no message, so that text is no message."
  [s]
  (when-let [[_ action url message] (when (string? s)
                                      (re-matches #"(?s)rss::payment::(\S*)\s*(\S*)\s*(.*)" (str/trim s)))]
    (let [[action] (value/read-action action)
          message  (str/trim message)
          said?    (not (contains? #{"" "undefined" "null"} message))]
      (cond-> {}
        action            (assoc :action action)
        (http/origin url) (assoc :url url)
        said?             (assoc :message message)))))

(def default-limits
  "The limits of `fetch!` that its options do not set. They are the seconds
  that the request can take, and the size of an answer in bytes. The header
  holds the metadata, so the answer needs no body."
  {:timeout-seconds 10
   :max-bytes       (* 64 1024)})

(defn request
  "The request for the metadata at `url`. It asks for two bytes of the page,
  since the header holds the metadata."
  [url]
  {:method :get :url url :range [0 1]})

(defn fetch!
  "The payment that the metadata at `url` describes, as `parse-header`
  reads the header x-rss-payment of the answer, or `{:error diagnostic}`.
  On the JVM the payment, in ClojureScript a promise of it.

  Take `url` from `parse-comment`. `opts` are the limits of
  `default-limits`, `:allow?`, and `:send`, a function that sends a request
  map in place of `http/call!`. The sender of the payment chooses the URL.
  So the request carries `http/public?` as its `:allow?`, and `http/call!`
  then sends nothing to the network of the host. A host that must reach
  its own network gives `(constantly true)` as `:allow?`. A browser reads
  the header only when the server exposes it with CORS."
  ([url]
   (fetch! url {}))
  ([url opts]
   (let [opts (merge default-limits {:send http/call! :allow? http/public?} opts)]
     (async/then (when (http/origin url)
                   (http/sent! (:send opts) (request url) opts))
                 (fn [{:keys [value error]}]
                   (let [found (when (map? value) (http/header (:headers value) header-name))]
                     (cond
                       error {:error (diagnostics/describe {:id :value/unreachable :url url :reason (async/explain error)})}
                       found (parse-header found)
                       :else {:error (diagnostics/describe {:id :value/no-metadata :url url :status (:status value)})})))))))

(comment
  (def sample
    {:action     :boost
     :msat       25000
     :message    "Great episode!"
     :app-name   "An app"
     :feed-title "A show"
     :timestamp  #inst "2026-09-29T12:00:00Z"})

  ;; what the app that pays writes
  (record sample)
  (header sample)
  (payment-comment sample "https://example.org/p/1")
  (payment-comment sample "https://example.org/p/1" 50)

  ;; what the host that receives the payment reads
  (parse-comment "rss::payment::boost https://example.org/p/1 Great episode!")
  (parse-header (header sample))
  (value/report (parse-header "{\"action\": \"streaming\", \"value_msat\": \"1000\"}"))

  (fetch! "https://example.org/p/1")
  #_.)
