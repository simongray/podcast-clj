(ns ^:no-doc dk.simongray.podcast.value.impl
  "The internals of value and its formats: the payment map's JSON fields,
  which the bLIP-10 record and the RSS Payment Metadata share, the checks
  that the wallets and the Lightning addresses share, and the split
  arithmetic and bLIP-10 tables of value. It was value.payment."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn amounts
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

(defn cut
  "The start of the text `s`, at most `n` characters long as JavaScript
  counts them, in UTF-16, and not cut inside a pair of surrogates."
  [s n]
  (if (<= (count s) n)
    s
    (let [code  #?(:clj  #(int (.charAt ^String s %))
                   :cljs #(.charCodeAt s %))
          high? #(<= 0xD800 (code %) 0xDBFF)]
      (subs s 0 (if (and (pos? n) (high? (dec n))) (dec n) n)))))

(defn node-key?
  "Whether `x` is the public key of a Lightning node: hexadecimal text of
  33 bytes that starts with 02 or 03."
  [x]
  (boolean (and (string? x) (re-matches #"(?i)0[23][0-9a-f]{64}" x))))

(defn- json-value
  "The value `v` as a value that JSON holds: a keyword as its name, an
  instant as ISO 8601 text, and anything else as it is."
  [v]
  (cond
    (keyword? v) (name v)
    (inst? v)    (time/format-iso8601 v)
    :else        v))

(defn json-record
  "The JSON object of `payment` in the `names` of a format, without the
  keys of `derived`, which the format writes its own way, and nil values.

  Each key has its name in `names`, or else its snake_case, and a string
  key stays as it is."
  [payment names derived]
  (into {} (for [[k v] payment
                 :when (and (some? v) (not (contains? derived k)))]
             [(if (string? k) k (get names k (str/replace (name k) "-" "_")))
              (json-value v)])))

(defn- typed
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

(defn json-object
  "The JSON object that `x` holds, or nil for anything else. It's a map as
  it is, or the object in JSON text or in its UTF-8 bytes, as a node gives
  a custom record."
  [x]
  (let [o (cond
            (map? x)           x
            (string? x)        (json/parse x)
            (bytes/bytes? x)   (json/parse (bytes/decode x)))]
    (when (map? o)
      o)))

(defn read-fields
  "The fields of the JSON object `raw` that `types` names, each as a pair
  of its value and how it was read."
  [raw types]
  (into {} (for [[k v] raw
                 :let  [type (get types k)]
                 :when type]
             [k (typed type v)])))

(defn other-fields
  "The fields of the JSON object `raw` that `types` doesn't name, except
  those with no value."
  [raw types]
  (into {} (remove (fn [[k v]] (or (contains? types k) (nil? v)))) raw))

(defn payment-of
  "The payment of the `read` fields, each under the key that `names` gives
  it, or else under its kebab-case. The fields of `own`, which a format
  reads its own way, are left out."
  [read names own]
  (into {} (for [[k [x]] read
                 :when (and (some? x) (not (contains? own k)))]
             [(get names k (keyword (str/replace k "_" "-"))) x])))

(defn field-diagnostics
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

(defn read-action
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

(defn fee?
  "Whether `recipient` takes a fee off the top rather than a share."
  [recipient]
  (true? (:fee recipient)))

(defn split-of
  "The split of `recipient` as a number no less than zero: zero when it
  gives none that's a number, as when \"12.5\" didn't read as a whole
  number."
  [recipient]
  (let [split (:split recipient)]
    (if (and (number? split) (pos? split)) split 0)))

(defn time-split-at
  "The valueTimeSplit of `block` in effect `seconds` into playback, if any."
  [block seconds]
  (some (fn [{:keys [start-time duration] :as split}]
          (when (and (number? start-time)
                     (number? duration)
                     (<= start-time seconds)
                     (< seconds (+ start-time duration)))
            split))
        (:time-splits block)))

(def boostagram-keys
  "The name in a bLIP-10 record of each key of a payment whose name there
  isn't just its snake_case."
  {:feed-guid      "guid"
   :feed-url       "url"
   :feed-id        "feedID"
   :feed-title     "podcast"
   :item-guid      "episode_guid"
   :item-id        "itemID"
   :item-title     "episode"
   :recipient-name "name"
   :id             "uuid"
   :group          "boost_uuid"})

(def boostagram-fields
  "The type of each field that bLIP-10 defines: :text, :int, :number, :msat
  for an amount, or :decimal for a number that the record writes as text."
  {"podcast"            :text
   "guid"               :text
   "feedID"             :int
   "url"                :text
   "episode"            :text
   "itemID"             :int
   "episode_guid"       :text
   "ts"                 :number
   "time"               :text
   "action"             :text
   "app_name"           :text
   "app_version"        :text
   "boost_link"         :text
   "message"            :text
   "name"               :text
   "sender_name"        :text
   "sender_id"          :text
   "signature"          :text
   "speed"              :decimal
   "boost_uuid"         :text
   "uuid"               :text
   "value_msat"         :msat
   "value_msat_total"   :msat
   "reply_address"      :text
   "reply_custom_key"   :int
   "reply_custom_value" :text
   "remote_feed_guid"   :text
   "remote_item_guid"   :text})

(def boostagram-aliases
  "The other names that apps give fields of a bLIP-10 record, and the name
  of each. They're the camelCase of Alby Hub, the names of RSS Payment
  Metadata that BoostMeBitch writes, and names for the position."
  {"feedId"         "feedID"
   "itemId"         "itemID"
   "feed_guid"      "guid"
   "feed_title"     "podcast"
   "item_guid"      "episode_guid"
   "item_title"     "episode"
   "recipient_name" "name"
   "group"          "boost_uuid"
   "position"       "ts"
   "time_seconds"   "ts"})

(defn unaliased
  "The JSON object `raw` with each field of another name in `aliases` under
  its own name, where `raw` has none. It gives the object and the aliases
  that it took, as a pair."
  [raw aliases]
  (reduce-kv (fn [[o taken :as acc] alias field]
               (if (and (contains? o alias) (not (contains? o field)))
                 (let [moved (-> o (dissoc alias) (assoc field (get o alias)))]
                   [moved (conj taken alias)])
                 acc))
             [raw []]
             aliases))
