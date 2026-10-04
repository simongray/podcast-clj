(ns dk.simongray.podcast.value
  "Value for value: the arithmetic of podcast:value blocks, and the record
  of a payment.

  The functions here give the block that applies to an item, and who is
  paid at a moment of playback, with the time splits of valueTimeSplit.
  They divide an amount into shares and fees, and make no payment, which
  is up to you.

  Value blocks and recipients are the maps that dk.simongray.podcast.feed
  gives, e.g.:

      {:type        \"lightning\"
       :method      \"keysend\"
       :suggested   0.00000005
       :recipients  [{:type \"node\" :address \"…\" :split 99 :fee false}]
       :time-splits [...]}

  A payment is a map of what an app sends with a payment to one recipient.
  It has one shape for the bLIP-10 record of a keysend payment and for the
  RSS Payment Metadata of value.metadata:

      {:action         :boost    ; :boost, :stream or :auto
       :msat           25000     ; to this recipient
       :total-msat     100000    ; of the whole payment, before the splits
       :message        \"Great episode!\"
       :position       754       ; seconds into the episode
       :feed-guid      \"…\"  :feed-title \"…\"
       :item-guid      \"…\"  :item-title \"…\"
       :app-name       \"…\"  :sender-name \"…\"
       :recipient-name \"…\"}

  The boostagram function writes the bLIP-10 record of a keysend payment,
  and parse-boostagram reads a record that a node received. The texts of a
  payment are as its sender wrote them. Hiccup escapes them, but an app
  that writes HTML as text must escape them itself."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

(defn effective
  "The value block that applies to `item` in `feed`: the item's own when it
  declares one, or else the feed's. With `type`, only blocks of that type
  count, e.g. \"lightning\"."
  ([feed item]
   (or (first (:podcast/values item))
       (first (:podcast/values feed))))
  ([feed item type]
   (let [of-type #(filter (comp #{type} str/lower-case str :type) %)]
     (or (first (of-type (:podcast/values item)))
         (first (of-type (:podcast/values feed)))))))

(defn fee?
  "Whether `recipient` takes a fee off the top rather than a share."
  [recipient]
  (true? (:fee recipient)))

(defn ^:no-doc split-of
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
          (when (and (number? start-time) (number? duration)
                     (<= start-time seconds) (< seconds (+ start-time duration)))
            split))
        (:time-splits block)))

(declare with-remote)

;; podcast:valueTimeSplit: remotePercentage is 100 and remoteStartTime is 0
;; when the split has none
(defn recipients-at
  "Who is paid `seconds` into the playback of media under `block`, as a
  map of :recipients, with the value blocks of `remotes` folded in.

  Outside a time split, they're the recipients of the block. Inside one,
  they're its fee recipients and those of the split. Inside a split that
  points at a remote item, :remote holds the :remote-item, :percentage,
  :start-time and :offset. The `remotes` map each remote item to its value
  block, or to nil when it has none, and with-remote folds that block in.
  Without `remotes`, or when they lack the remote item, the recipients are
  the fee recipients alone."
  ([block seconds]
   (let [recipients (:recipients block)
         fees       (filter fee? recipients)]
     (if-let [{:keys [remote-item remote-percentage remote-start-time] :as split}
              (time-split-at block seconds)]
       (if remote-item
         {:recipients (vec fees)
          :remote     {:remote-item remote-item
                       :percentage  (-> (if (number? remote-percentage) remote-percentage 100)
                                        (max 0)
                                        (min 100))
                       :start-time  (if (number? remote-start-time) remote-start-time 0)
                       :offset      (- seconds (:start-time split))}}
         {:recipients (into (vec fees) (:recipients split))})
       {:recipients (vec recipients)})))
  ([block seconds remotes]
   (let [paid (recipients-at block seconds)
         item (:remote-item (:remote paid))]
     (if (contains? remotes item)
       (with-remote block paid (get remotes item))
       paid))))

(defn share-total
  "The sum of the splits of the non-fee `recipients`."
  [recipients]
  (transduce (comp (remove fee?) (map split-of)) + 0 recipients))

(defn with-remote
  "The recipients of `paid`, the result of recipients-at for `block`, with
  the `remote` value block that a time split pointed at folded in.

  The result is `paid` with :recipients that hold the fees of both blocks,
  and it keeps :remote, so that a payment can name the remote item. The
  non-fee recipients of the remote block get the percentage of the split,
  and those of the block get the rest. Their splits are rescaled to whole
  shares that express this. Without a `remote` block, the recipients of
  the block apply. Outside a remote split, `paid` stays as it is."
  [block paid remote]
  (if-let [{:keys [percentage]} (:remote paid)]
    (let [local  (remove fee? (:recipients block))
          others (remove fee? (:recipients remote))
          fees   (filter fee? (concat (:recipients block) (:recipients remote)))
          total  (share-total local)
          total' (share-total others)]
      (assoc paid :recipients
             (if (zero? total')
               (vec (:recipients block))
               (->> (concat fees
                            (for [r local]
                              (assoc r :split (* (split-of r) (- 100 percentage) total')))
                            (for [r others]
                              (assoc r :split (* (split-of r) percentage (max total 1)))))
                    (remove #(and (not (fee? %)) (zero? (:split %))))
                    vec))))
    paid))

(defn payments
  "The shares of `amount` that `recipients` get, as the podcast:value
  specification prescribes in its part Payment calculation. The `amount`
  is a whole number of the smallest unit, e.g. sats.

  Each fee recipient takes its split as a percentage off the top, and the
  others share the rest by their splits. The result is a vector of maps
  of the :recipient and its :amount in whole units. A rounding remainder
  goes to the largest share. So that no amount is negative:

  - a split that isn't a number counts as none
  - a fee takes at most all
  - the fees stop where the amount runs out"
  [recipients amount]
  (let [fees      (filter fee? recipients)
        shares    (remove fee? recipients)
        fee-paid  (second (reduce (fn [[left paid] r]
                                    (let [fee (/ (* amount (min 100 (split-of r))) 100)
                                          n   (min left (long (Math/floor fee)))]
                                      [(- left n) (conj paid {:recipient r :amount n})]))
                                  [amount []]
                                  fees))
        remaining (- amount (transduce (map :amount) + 0 fee-paid))
        total     (share-total shares)
        ;; *' on the JVM, where a large split times the amount can overflow a
        ;; long; a JavaScript number cannot
        paid      (for [r shares]
                    {:recipient r
                     :amount    (if (pos? total)
                                  (long (Math/floor (/ (#?(:clj *' :cljs *) remaining (split-of r)) total)))
                                  0)})
        leftover  (- remaining (transduce (map :amount) + 0 paid))
        largest   (when (seq paid)
                    (apply max-key #(split-of (:recipient %)) paid))]
    (vec (concat fee-paid
                 (map (fn [p]
                        (if (and (pos? leftover) (identical? p largest))
                          (update p :amount + leftover)
                          p))
                      paid)))))

(defn sats
  "The :suggested amount of `block`, given in BTC, as whole satoshis, or
  nil when it has none."
  [block]
  (when-let [btc (:suggested block)]
    (when (number? btc)
      (long (Math/round (* btc 100000000.0))))))

(def ^:no-doc boostagram-keys
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

(defn ^:no-doc cut
  "The start of the text `s`, at most `n` characters long as JavaScript
  counts them, in UTF-16, and not cut inside a pair of surrogates."
  [s n]
  (if (<= (count s) n)
    s
    (let [high? #(<= 0xD800 #?(:clj (int (.charAt ^String s %)) :cljs (.charCodeAt s %)) 0xDBFF)]
      (subs s 0 (if (and (pos? n) (high? (dec n))) (dec n) n)))))

(defn ^:no-doc amounts
  "The amount and the total of `payment` in millisatoshis, as a pair, or
  nil when it has no amount.

  The amount is its :msat, or else its :sats. The total is its
  :total-msat, or else its :total-sats, or else the amount itself."
  [{:keys [msat total-msat sats total-sats] :as payment}]
  (let [amount (cond (number? msat) (long msat) (number? sats) (* 1000 (long sats)))
        total  (cond (number? total-msat) (long total-msat) (number? total-sats) (* 1000 (long total-sats)) :else amount)]
    (when amount
      [amount total])))

(defn boostagram
  "The bLIP-10 record of `payment`, which an app attaches to a keysend
  payment as the custom record 7629169. It's a map with the string keys
  of the record, for dk.simongray.podcast.util.json/write-str.

  The keys of the payment become these:

  - :position, seconds into the episode, becomes ts and time
  - :action becomes action, boost when it's absent
  - :msat and :total-msat become value_msat and value_msat_total, and
    :sats and :total-sats count in sats in their place
  - :speed becomes text, as bLIP-10 writes it
  - the other keys of a payment get the names of the record, any other
    key its snake_case, and a string key stays as it is

  Nil values are left out."
  [{:keys [position action speed] :as payment}]
  (let [record         (json-record payment boostagram-keys #{:position :action :speed :msat :total-msat :sats :total-sats})
        clock          (time/format-timestamp position)
        [amount total] (amounts payment)]
    (cond-> (assoc record "action" (name (or action :boost)))
      clock         (assoc "ts" (max 0 (long (Math/floor position)))
                           "time" (subs clock 0 (- (count clock) 4)))
      (some? speed) (assoc "speed" (str speed))
      amount        (assoc "value_msat" amount "value_msat_total" total))))

(defn records
  "The custom records of a keysend payment of `payment` to `recipient`, as
  a map from the type of each record to its text.

  They're the bLIP-10 record under 7629169, with the name of the
  recipient, and the custom key and value of the recipient. A node that
  holds many wallets finds the wallet by these.

  The feed chooses the custom key. So the records leave out a key below
  65536, which BOLT 1 keeps for the Lightning protocol. They also leave
  out the type of the bLIP-10 record, and the type of the preimage of
  keysend, 5482373484.

  The report says when the records leave out the custom key or the custom
  value of the recipient. A payment with such records reaches the node,
  and not the wallet, so don't send it."
  [payment {recipient-name :name :keys [custom-key custom-value] :as recipient}]
  (let [k       (some-> custom-key str str/trim parse-long)
        custom? (and k (<= 65536 k) (not (contains? #{7629169 5482373484} k)) (some? custom-value))
        asked?  (or (some? custom-key) (some? custom-value))]
    (with-meta (cond-> {7629169 (json/write-str (boostagram (merge {:recipient-name recipient-name} payment)))}
                 custom? (assoc k (str custom-value)))
      {:podcast/report (if (and asked? (not custom?))
                         [(diagnostics/describe {:id :value/custom-record :custom-key custom-key :custom-value custom-value})]
                         [])})))

(def ^:no-doc boostagram-fields
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
  (let [n     (cond
                (number? v)                                        v
                (and (string? v) (re-matches #"\s*-?\d+\s*" v))     (parse-long (str/trim v))
                (and (string? v) (re-matches #"\s*-?\d*\.\d+\s*" v)) (parse-double (str/trim v)))
        text? (and (string? v) (not= :decimal type))]
    (cond
      (or (nil? v) (and (string? v) (str/blank? v))) [nil]
      (= :text type)                                  (if-some [s (json/text v)] [s] [nil :unreadable])
      (or (nil? n) (neg? n))                          [nil :unreadable]
      (and (#{:int :msat} type) (>= n 9e15))          [nil :unreadable]
      (== n (Math/floor n))                            [(if (#{:int :msat} type) (long n) n) (when text? :as-text)]
      (= :msat type)                                   [(Math/round (double n)) :rounded]
      (= :int type)                                    [nil :unreadable]
      :else                                            [n (when text? :as-text)])))

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
  of its value and how it was read, as typed reads it."
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
  (let [of      #(vec (sort (for [[k [_ how]] read :when (and (= % how) (not (contains? forgiven k)))] k)))
        as-text (of :as-text)
        rounded (of :rounded)
        unread  (of :unreadable)]
    (cond-> []
      (seq as-text) (conj {:id :value/text-for-number :keys as-text})
      (seq rounded) (conj {:id :value/rounded :keys rounded})
      (seq unread)  (conj {:id :value/unreadable :keys unread})
      (seq other)   (conj {:id :value/unknown-keys :keys (vec (sort (keys other)))}))))

(def ^:no-doc boostagram-aliases
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

(defn ^:no-doc unaliased
  "The JSON object `raw` with each field of another name in `aliases` under
  its own name, where `raw` has none. It gives the object and the aliases
  that it took, as a pair."
  [raw aliases]
  (reduce-kv (fn [[o taken :as acc] alias field]
               (if (and (contains? o alias) (not (contains? o field)))
                 [(-> o (dissoc alias) (assoc field (get o alias))) (conj taken alias)]
                 acc))
             [raw []]
             aliases))

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

(defn parse-boostagram
  "The payment that the bLIP-10 `record` describes, as boostagram takes
  it.

  The record is JSON text, or the UTF-8 bytes of that text, as a node
  gives the custom record 7629169. It can also be the JSON object as a map
  with string keys.

  The fields that bLIP-10 defines become the keys of a payment, and the
  other fields keep their names. The report tells what the reader forgave.
  Real apps write numbers as text, amounts with a fraction, and fields
  under other names. They write the guid of the episode as the itemID, and
  the action under another name. A record that isn't a JSON object reads
  as no payment.

  The record is what the sender claims, and as-received puts it next to
  what the node received."
  [record]
  (if-let [given (json-object record)]
    (let [[raw aliases] (unaliased given boostagram-aliases)
          read          (read-fields raw boostagram-fields)
          value         #(first (get read %))
          other         (other-fields raw boostagram-fields)
          [action said] (read-action (value "action"))
          position      (or (value "ts") (some-> (value "time") time/parse-clock))
          ;; Breez writes the guid of the episode as its itemID
          item-guid     (let [id (get raw "itemID")]
                          (when (and (string? id) (= :unreadable (second (get read "itemID"))))
                            (str/trim id)))
          names         (assoc (set/map-invert boostagram-keys)
                               "value_msat" :msat
                               "value_msat_total" :total-msat)
          payment       (cond-> (payment-of read names #{"ts" "time" "action"})
                          action    (assoc :action action)
                          position  (assoc :position position)
                          (and item-guid (nil? (value "episode_guid")))
                          (assoc :item-guid item-guid))]
      (with-meta (merge payment other)
        {:podcast/report (mapv diagnostics/describe
                               (cond-> (field-diagnostics read other (if item-guid #{"itemID"} #{}))
                                 (seq aliases) (conj {:id :value/field-alias :keys (vec (sort aliases))})
                                 item-guid     (conj {:id :value/guid-as-item-id})
                                 said          (conj said)
                                 (not-any? value ["guid" "podcast" "feedID" "url"])
                                 (conj {:id :value/no-podcast})))}))
    (with-meta {} {:podcast/report [(diagnostics/describe {:id :value/not-an-object})]})))

(defn report
  "The diagnostics recorded while reading `payment`."
  [payment]
  (:podcast/report (meta payment)))

(defn as-received
  "The `payment`, as its sender claims it, with the amount that the node of
  the recipient received, the :msat of `received`.

  The report tells where a claim differs from what the node knows: another
  amount, or another recipient than the :address of `received`. Anyone
  can send a record, or name metadata, with a payment of one satoshi, so
  what the node received is the fact."
  [payment {:keys [msat address] :as received}]
  (let [claimed (:msat payment)
        to      (:recipient-address payment)]
    (vary-meta (cond-> payment (some? msat) (assoc :msat msat))
               update :podcast/report (fnil into [])
               (map diagnostics/describe
                    (cond-> []
                      (and (some? msat) (some? claimed) (not= msat claimed))
                      (conj {:id :value/claimed-amount :msat msat :claimed claimed})

                      (and address to (not= (str/lower-case (str address)) (str/lower-case (str to))))
                      (conj {:id :value/claimed-recipient :address address :claimed to}))))))

(comment
  (def block {:type "lightning" :method "keysend" :suggested 0.00000015
              :recipients [{:name "Host" :type "node" :address "02d5" :split 50}
                           {:name "Co-Host" :type "node" :address "032f" :split 40}
                           {:name "Producer" :type "node" :address "03ae" :split 10}
                           {:name "Index" :type "node" :address "03ae" :split 1 :fee true}]
              :time-splits [{:start-time 60 :duration 237 :remote-percentage 95
                             :remote-item {:feed-guid "a94f" :item-guid "x"}}]})
  (payments (:recipients block) 100)
  (recipients-at block 100)
  (sats block)
  #_.)
