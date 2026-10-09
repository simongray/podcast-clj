(ns dk.simongray.podcast.value.payment
  "The payment map of dk.simongray.podcast.value and the JSON fields of its
  formats, the bLIP-10 record and the RSS Payment Metadata.

  A format names the type of each of its fields. A reader reads the fields
  of those types into a payment and diagnoses what it forgave, and a
  writer writes the keys of a payment under the names of the format."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

(defn ^:no-doc amounts
  "The amount and the total of `payment` in millisatoshis, as a pair, or
  nil when it has no amount.

  The amount is its :msat, or else its :sats. The total is its
  :total-msat, or else its :total-sats, or else the amount itself."
  [{:keys [msat total-msat sats total-sats] :as payment}]
  (let [amount (cond
                 (number? msat) (long msat)
                 (number? sats) (* 1000 (long sats)))
        total  (cond
                 (number? total-msat) (long total-msat)
                 (number? total-sats) (* 1000 (long total-sats))
                 :else                amount)]
    (when amount
      [amount total])))

(defn ^:no-doc cut
  "The start of the text `s`, at most `n` characters long as JavaScript
  counts them, in UTF-16, and not cut inside a pair of surrogates."
  [s n]
  (if (<= (count s) n)
    s
    (let [code  #?(:clj  #(int (.charAt ^String s %))
                   :cljs #(.charCodeAt s %))
          high? #(<= 0xD800 (code %) 0xDBFF)]
      (subs s 0 (if (and (pos? n) (high? (dec n))) (dec n) n)))))

(defn ^:no-doc json-value
  "The value `v` as a value that JSON holds: a keyword as its name, an
  instant as ISO 8601 text, and anything else as it is."
  [v]
  (cond
    (keyword? v) (name v)
    (inst? v)    (time/format-iso8601 v)
    :else        v))

(defn ^:no-doc json-record
  "The JSON object of `payment` in the `names` of a format, without the
  keys of `derived`, which the format writes its own way, and nil values.

  Each key has its name in `names`, or else its snake_case, and a string
  key stays as it is."
  [payment names derived]
  (into {} (for [[k v] payment
                 :when (and (some? v) (not (contains? derived k)))]
             [(if (string? k) k (get names k (str/replace (name k) "-" "_")))
              (json-value v)])))

(defn ^:no-doc typed
  "The JSON value `v` of a field of `type`, as a pair of the value and how
  it was read. The type is :text, :int, :msat, :number or :decimal.

  How it was read is nil, or one of these:

  - :as-text, a number that came as text where the format writes a number
  - :rounded, an amount in millisatoshis with a fraction
  - :unreadable, a value that isn't of the type

  Nil and blank text are no value. A number below zero isn't of its type,
  since the formats have numbers for amounts, positions and ids, and none
  of these is below zero."
  [type v]
  (let [integer #"\s*-?\d+\s*"
        decimal #"\s*-?\d*\.\d+\s*"
        n       (cond
                  (number? v)
                  v

                  (and (string? v) (re-matches integer v))
                  (parse-long (str/trim v))

                  (and (string? v) (re-matches decimal v))
                  (parse-double (str/trim v)))
        text?   (and (string? v) (not= :decimal type))
        whole?  (#{:int :msat} type)
        as-text (when text? :as-text)]
    (cond
      (or (nil? v) (and (string? v) (str/blank? v)))
      [nil]

      (= :text type)
      (if-some [s (json/text v)] [s] [nil :unreadable])

      (or (nil? n) (neg? n))
      [nil :unreadable]

      (and whole? (>= n 9e15))
      [nil :unreadable]

      (== n (Math/floor n))
      [(if whole? (long n) n) as-text]

      (= :msat type)
      [(Math/round (double n)) :rounded]

      (= :int type)
      [nil :unreadable]

      :else
      [n as-text])))

(defn ^:no-doc json-object
  "The JSON object that `x` holds, or nil for anything else. It's a map as
  it is, or the object in JSON text or in its UTF-8 bytes, as a node gives
  a custom record."
  [x]
  (let [o (cond
            (map? x)                                    x
            (string? x)                                 (json/parse x)
            #?(:clj (bytes? x) :cljs (instance? js/Uint8Array x))
            (json/parse (bytes/decode x 0 (bytes/length x) :utf8)))]
    (when (map? o)
      o)))

(defn ^:no-doc read-fields
  "The fields of the JSON object `raw` that `types` names, each as a pair
  of its value and how it was read."
  [raw types]
  (into {} (for [[k v] raw
                 :let  [type (get types k)]
                 :when type]
             [k (typed type v)])))

(defn ^:no-doc other-fields
  "The fields of the JSON object `raw` that `types` doesn't name, except
  those with no value."
  [raw types]
  (into {} (remove (fn [[k v]] (or (contains? types k) (nil? v)))) raw))

(defn ^:no-doc payment-of
  "The payment of the `read` fields, each under the key that `names` gives
  it, or else under its kebab-case. The fields of `own`, which a format
  reads its own way, are left out."
  [read names own]
  (into {} (for [[k [x]] read
                 :when (and (some? x) (not (contains? own k)))]
             [(get names k (keyword (str/replace k "_" "-"))) x])))

(defn ^:no-doc field-diagnostics
  "The diagnostics of the `read` fields and the `other` ones. They tell of
  numbers written as text, of values not of their type, except in the
  fields of `forgiven`, and of fields that the format doesn't define."
  [read other forgiven]
  (let [of      (fn [kind]
                  (vec (sort (for [[k [_ how]] read
                                   :when (and (= kind how)
                                              (not (contains? forgiven k)))]
                               k))))
        as-text (of :as-text)
        rounded (of :rounded)
        unread  (of :unreadable)
        unknown (vec (sort (keys other)))]
    (cond-> []
      (seq as-text) (conj {:id :value/text-for-number :keys as-text})
      (seq rounded) (conj {:id :value/rounded :keys rounded})
      (seq unread)  (conj {:id :value/unreadable :keys unread})
      (seq other)   (conj {:id :value/unknown-keys :keys unknown}))))

(defn ^:no-doc read-action
  "The action that the text `s` names, and a diagnostic, as a pair. The
  action is :boost, :stream or :auto, and :stream for the streaming that
  Podverse writes, with a diagnostic.

  Any other action counts as none, since the text comes from a stranger,
  and the diagnostic holds it."
  [s]
  (when-let [s (some-> s str/trim str/lower-case not-empty)]
    (case s
      ("boost" "stream" "auto") [(keyword s)]
      "streaming"               [:stream {:id :value/action-alias :action s}]
      [nil {:id :value/unknown-action :action s}])))
