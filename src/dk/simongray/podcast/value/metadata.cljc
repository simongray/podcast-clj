(ns dk.simongray.podcast.value.metadata
  "The RSS Payment Metadata of the podcast namespace: what a payment to a
  Lightning address is for. The comment of the payment tells it, and a URL
  that the comment names.

  A payment to a Lightning address carries only a short comment, which
  has the action, a URL and the start of the message:

      rss::payment::boost https://… Great episode!

  The URL answers with the header x-rss-payment, which holds the whole
  payment as JSON. The app that pays writes the comment with
  format-comment, and serves the header that format-header writes. The
  host that receives the payment reads the comment with parse-comment.
  The fetch! function gets the header, and parse-header reads it. A
  payment is the map of dk.simongray.podcast.value.

  The sender writes the metadata, and the page at the URL is public. So
  the metadata is a claim, which value/as-received puts next to what the
  node received."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.url :as url]
            [dk.simongray.podcast.value :as value]))

(def prefix
  "The start of the comment of a payment that names its metadata."
  "rss::payment::")

(def header-name
  "The header of the answer that holds the metadata of a payment."
  "x-rss-payment")

(def ^:no-doc record-keys
  "The name in the metadata of each key of a payment whose name there
  isn't just its snake_case."
  {:msat       "value_msat"
   :total-msat "value_msat_total"
   :usd        "value_usd"})

(defn record
  "The metadata of `payment` as a map with the string keys of the JSON,
  for dk.simongray.podcast.util.json/write-str.

  The keys of the payment become these:

  - :action becomes action, boost when it's absent
  - :msat and :total-msat become value_msat and value_msat_total, and
    :sats and :total-sats count in sats in their place
  - :usd becomes value_usd
  - an instant becomes ISO 8601 text, and any other key its snake_case

  Give the :timestamp of the payment, which the metadata holds."
  [{:keys [action] :as payment}]
  (let [[amount total] (value/amounts payment)
        own            #{:action :msat :total-msat :sats :total-sats}
        base           (value/json-record payment record-keys own)]
    (cond-> (assoc base "action" (name (or action :boost)))
      amount (assoc "value_msat" amount "value_msat_total" total))))

(defn format-header
  "The value of the header x-rss-payment for `payment`: its record as
  JSON, URI-encoded as encodeURIComponent does."
  [payment]
  (url/percent-encode (json/write-str (record payment))))

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
  as record writes it: JSON, URI-encoded, or as it is.

  The fields of the metadata become the keys of a payment, and the other
  fields keep their names. The report tells what the reader forgave, as
  value/parse-boostagram tells it. A header that holds no JSON object
  reads as no payment."
  [s]
  (let [text    (when (string? s) (str/trim s))
        json?   (and text (str/starts-with? text "{"))
        decoded (if json? text (some-> text url/percent-decode))
        raw     (value/json-object decoded)]
    (if raw
      (let [read          (value/read-fields raw fields)
            v             #(first (get read %))
            other         (value/other-fields raw fields)
            [action said] (value/read-action (v "action"))
            timestamp     (some-> (v "timestamp") time/parse-date)
            names         (set/map-invert record-keys)
            own           #{"action" "timestamp"}
            payment       (cond-> (value/payment-of read names own)
                            action    (assoc :action action)
                            timestamp (assoc :timestamp timestamp))
            unread-time?  (and (v "timestamp") (nil? timestamp))
            podcast?      (some v ["feed_guid" "feed_title" "remote_feed_guid"])
            no-time       {:id :value/unreadable :keys ["timestamp"]}
            found         (cond-> (value/field-diagnostics read other #{})
                            unread-time?   (conj no-time)
                            said           (conj said)
                            (not podcast?) (conj {:id :value/no-podcast}))]
        (with-meta (merge payment other)
          {:podcast/report (mapv diagnostics/describe found)}))
      (let [none (diagnostics/describe {:id :value/not-an-object})]
        (with-meta {} {:podcast/report [none]})))))

(defn format-comment
  "The comment of a payment to a Lightning address that names the metadata
  of `payment` at `url`, at most `max-length` characters long, as
  JavaScript counts them.

  The comment has the form that the docstring of this namespace shows.
  Give the :comment-length of the terms of the address as `max-length`.
  The message of the payment is cut to fit, and it ends in an ellipsis
  when it's cut. The comment is nil when `url` isn't an http or https
  URL, or when the comment doesn't fit without the message."
  ([payment url]
   (format-comment payment url ##Inf))
  ([{:keys [action message] :as payment} url max-length]
   (when (url/origin url)
     (let [head (str prefix (name (or action :boost)) " " url)
           text (some-> message str/trim not-empty)
           full (cond-> head text (str " " text))]
       (cond
         (> (count head) max-length)
         nil

         (<= (count full) max-length)
         full

         ;; a space, a character of the message and the ellipsis
         (< max-length (+ (count head) 3))
         head

         :else
         (str (str/trimr (value/cut full (dec max-length))) "…"))))))

(defn parse-comment
  "What the comment `s` of a payment tells about its metadata, as a map of
  :action, :url and :message, or nil when `s` doesn't start with prefix.

  The message is the start of the message of the payment, and the metadata
  at the URL holds all of it. Fountain wrote the text undefined in place
  of no message, so that text counts as none."
  [s]
  (let [form  #"(?s)rss::payment::(\S*)\s*(\S*)\s*(.*)"
        found (when (string? s) (re-matches form (str/trim s)))]
    (when-let [[_ action url message] found]
      (let [[action] (value/read-action action)
            message  (str/trim message)
            said?    (not (contains? #{"" "undefined" "null"} message))]
        (cond-> {}
          action            (assoc :action action)
          (url/origin url) (assoc :url url)
          said?             (assoc :message message))))))

(def default-limits
  "The limits of fetch! that its options don't set: the seconds that the
  request can take, and the size of an answer in bytes. The header holds
  the metadata, so the answer needs no body."
  {:timeout-seconds 10
   :max-bytes       (* 64 1024)})

(defn request
  "The request for the metadata at `url`. It asks for two bytes of the page,
  since the header holds the metadata."
  [url]
  {:method :get :url url :range [0 1]})

(defn fetch!
  "Fetch the metadata at `url` with `opts`, and read the header
  x-rss-payment of the answer as parse-header does. On the JVM it gives
  the payment, and in ClojureScript a promise of it.

  Take `url` from parse-comment. When the metadata doesn't come, the
  result has a diagnostic under :error. The `opts` are these:

  - the limits of default-limits
  - :allow?, which URLs the request can go to
  - :send, a function that sends a request map in place of http/send!

  The sender of the payment chooses the URL. So the request carries
  url/public? as its :allow?, and http/send! then sends nothing to a
  private network. To reach your own network, give (constantly true) as
  :allow?. A browser reads the header only when the server exposes it
  with CORS."
  ([url]
   (fetch! url {}))
  ([url opts]
   (let [defaults {:send http/send! :allow? url/public?}
         opts     (merge default-limits defaults opts)
         failure  (fn [d] {:error (diagnostics/describe d)})]
     (async/then
      (when (url/origin url)
        (http/try-send! (:send opts) (request url) opts))
      (fn [{:keys [value error]}]
        (let [found (when (map? value)
                      (http/header (:headers value) header-name))]
          (cond
            error
            (failure {:id     :value/unreachable
                      :url    url
                      :reason (async/explain error)})

            found
            (parse-header found)

            :else
            (failure {:id     :value/no-metadata
                      :url    url
                      :status (:status value)}))))))))

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
  (format-header sample)
  (format-comment sample "https://example.org/p/1")
  (format-comment sample "https://example.org/p/1" 50)

  ;; what the host that receives the payment reads
  (parse-comment "rss::payment::boost https://example.org/p/1 Great episode!")
  (parse-header (format-header sample))
  (value/report
   (parse-header "{\"action\": \"streaming\", \"value_msat\": \"1000\"}"))

  (fetch! "https://example.org/p/1")
  #_.)
