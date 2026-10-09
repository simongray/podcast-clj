(ns dk.simongray.podcast.value
  "Value for value: the arithmetic of podcast:value blocks, and the record
  of a payment.

  The functions here give who is paid at a moment of playback, with the
  time splits of valueTimeSplit. They divide an amount into shares and
  fees, and make no payment, which is up to you.

  Value blocks and recipients are maps of the feed model, e.g.:

      {:type        \"lightning\"
       :method      \"keysend\"
       :suggested   0.00000005
       :recipients  [{:type \"node\" :address \"…\" :split 99 :fee false}]
       :time-splits [...]}

  A payment is a map of what an app sends with a payment to one recipient.
  It has one shape for the bLIP-10 record of a keysend payment and for the
  RSS Payment Metadata of the podcast namespace:

      {:action         :boost    ; :boost, :stream or :auto
       :msat           25000     ; to this recipient
       :total-msat     100000    ; of the whole payment, before the splits
       :message        \"Great episode!\"
       :position       754       ; seconds into the episode
       :feed-guid      \"…\"  :feed-title \"…\"
       :item-guid      \"…\"  :item-title \"…\"
       :app-name       \"…\"  :sender-name \"…\"
       :recipient-name \"…\"}

  The texts of a payment are as its sender wrote them. Hiccup escapes
  them, but an app that writes HTML as text must escape them itself."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.value.payment :as value.payment]))

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
          (when (and (number? start-time)
                     (number? duration)
                     (<= start-time seconds)
                     (< seconds (+ start-time duration)))
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
  block, or to nil when it has none, and that block is folded in.
  Without `remotes`, or when they lack the remote item, the recipients are
  the fee recipients alone."
  ([block seconds]
   (let [recipients (:recipients block)
         fees       (filter fee? recipients)
         split      (time-split-at block seconds)
         {:keys [remote-item remote-percentage remote-start-time]} split]
     (if split
       (if remote-item
         (let [percentage (if (number? remote-percentage) remote-percentage 100)
               start      (if (number? remote-start-time) remote-start-time 0)]
           {:recipients (vec fees)
            :remote     {:remote-item remote-item
                         :percentage  (-> percentage (max 0) (min 100))
                         :start-time  start
                         :offset      (- seconds (:start-time split))}})
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
  "The recipients of `paid`, who is paid at a moment under `block`, with
  the `remote` value block that a time split pointed at folded in.

  The result is `paid` with :recipients that hold the fees of both blocks,
  and it keeps :remote, so that a payment can name the remote item. The
  non-fee recipients of the remote block get the percentage of the split,
  and those of the block get the rest. Their splits are rescaled to whole
  shares that express this. Without a `remote` block, the recipients of
  the block apply. Outside a remote split, `paid` stays as it is."
  [block paid remote]
  (if-let [{:keys [percentage]} (:remote paid)]
    (let [local        (remove fee? (:recipients block))
          others       (remove fee? (:recipients remote))
          fees         (filter fee? (concat (:recipients block)
                                            (:recipients remote)))
          total        (share-total local)
          total'       (share-total others)
          local-share  #(* (split-of %) (- 100 percentage) total')
          remote-share #(* (split-of %) percentage (max total 1))]
      (assoc paid :recipients
             (if (zero? total')
               (vec (:recipients block))
               (->> (concat fees
                            (map #(assoc % :split (local-share %)) local)
                            (map #(assoc % :split (remote-share %)) others))
                    (remove #(and (not (fee? %)) (zero? (:split %))))
                    vec))))
    paid))

(defn- fee-payments
  "The payments of `amount` to the fee `recipients`: each takes its split
  as a percentage off the top, until the amount runs out."
  [recipients amount]
  (second (reduce (fn [[left paid] r]
                    (let [fee (/ (* amount (min 100 (split-of r))) 100)
                          n   (min left (long (Math/floor fee)))]
                      [(- left n) (conj paid {:recipient r :amount n})]))
                  [amount []]
                  recipients)))

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
  (let [fee-paid  (fee-payments (filter fee? recipients) amount)
        shares    (remove fee? recipients)
        remaining (- amount (transduce (map :amount) + 0 fee-paid))
        total     (share-total shares)
        ;; *' on the JVM, where a large split times the amount can overflow a
        ;; long; a JavaScript number cannot
        share     (fn [r]
                    (let [product (#?(:clj *' :cljs *) remaining (split-of r))]
                      (long (Math/floor (/ product total)))))
        paid      (for [r shares]
                    {:recipient r
                     :amount    (if (pos? total) (share r) 0)})
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

(defn boostagram
  "The bLIP-10 record of `payment`, which an app attaches to a keysend
  payment as the custom record 7629169. It's a map with the string keys
  of the record, to write as JSON.

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
  (let [own            #{:position :action :speed
                         :msat :total-msat :sats :total-sats}
        record         (value.payment/json-record payment
                                                  boostagram-keys
                                                  own)
        clock          (time/format-timestamp position)
        [amount total] (value.payment/amounts payment)]
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
  (let [k        (some-> custom-key str str/trim parse-long)
        reserved #{7629169 5482373484}
        custom?  (and k
                      (<= 65536 k)
                      (not (contains? reserved k))
                      (some? custom-value))
        asked?   (or (some? custom-key) (some? custom-value))
        named    (merge {:recipient-name recipient-name} payment)
        record   (json/write-str (boostagram named))
        missing  {:id           :value/custom-record
                  :custom-key   custom-key
                  :custom-value custom-value}]
    (with-meta (cond-> {7629169 record}
                 custom? (assoc k (str custom-value)))
      {:podcast/report (if (and asked? (not custom?))
                         [(diagnostics/describe missing)]
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
                 (let [moved (-> o (dissoc alias) (assoc field (get o alias)))]
                   [moved (conj taken alias)])
                 acc))
             [raw []]
             aliases))

(defn parse-boostagram
  "The payment that the bLIP-10 `record` describes.

  The record is JSON text, or the UTF-8 bytes of that text, as a node
  gives the custom record 7629169. It can also be the JSON object as a map
  with string keys.

  The fields that bLIP-10 defines become the keys of a payment, and the
  other fields keep their names. The report tells what the reader forgave.
  Real apps write numbers as text, amounts with a fraction, and fields
  under other names. They write the guid of the episode as the itemID, and
  the action under another name. A record that isn't a JSON object reads
  as no payment.

  The record is what the sender claims, not what the node received."
  [record]
  (if-let [given (value.payment/json-object record)]
    (let [[raw aliases] (unaliased given boostagram-aliases)
          read          (value.payment/read-fields raw boostagram-fields)
          value         #(first (get read %))
          other         (value.payment/other-fields raw boostagram-fields)
          [action said] (value.payment/read-action (value "action"))
          clock         (value "time")
          position      (or (value "ts") (some-> clock time/parse-clock))
          id            (get raw "itemID")
          ;; Breez writes the guid of the episode as its itemID
          item-guid     (when (and (string? id)
                                   (= :unreadable (second (get read "itemID"))))
                          (str/trim id))
          names         (assoc (set/map-invert boostagram-keys)
                               "value_msat" :msat
                               "value_msat_total" :total-msat)
          own           #{"ts" "time" "action"}
          payment       (cond-> (value.payment/payment-of read names own)
                          action    (assoc :action action)
                          position  (assoc :position position)
                          (and item-guid (nil? (value "episode_guid")))
                          (assoc :item-guid item-guid))
          forgiven      (if item-guid #{"itemID"} #{})
          podcast?      (some value ["guid" "podcast" "feedID" "url"])
          alias-note    {:id :value/field-alias :keys (vec (sort aliases))}
          diagnosed     (value.payment/field-diagnostics read other forgiven)
          found         (cond-> diagnosed
                          (seq aliases)  (conj alias-note)
                          item-guid      (conj {:id :value/guid-as-item-id})
                          said           (conj said)
                          (not podcast?) (conj {:id :value/no-podcast}))]
      (with-meta (merge payment other)
        {:podcast/report (mapv diagnostics/describe found)}))
    (let [none (diagnostics/describe {:id :value/not-an-object})]
      (with-meta {} {:podcast/report [none]}))))

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
  (let [claimed          (:msat payment)
        to               (:recipient-address payment)
        other-amount?    (and (some? msat) (some? claimed) (not= msat claimed))
        other-recipient? (and address
                              to
                              (not= (str/lower-case (str address))
                                    (str/lower-case (str to))))
        found            (cond-> []
                           other-amount?
                           (conj {:id      :value/claimed-amount
                                  :msat    msat
                                  :claimed claimed})

                           other-recipient?
                           (conj {:id      :value/claimed-recipient
                                  :address address
                                  :claimed to}))]
    (vary-meta (cond-> payment (some? msat) (assoc :msat msat))
               update :podcast/report (fnil into [])
               (map diagnostics/describe found))))

(comment
  (def recipients
    [{:name "Host" :type "node" :address "02d5" :split 50}
     {:name "Co-Host" :type "node" :address "032f" :split 40}
     {:name "Producer" :type "node" :address "03ae" :split 10}
     {:name "Index" :type "node" :address "03ae" :split 1 :fee true}])

  (def block
    {:type        "lightning"
     :method      "keysend"
     :suggested   0.00000015
     :recipients  recipients
     :time-splits [{:start-time        60
                    :duration          237
                    :remote-percentage 95
                    :remote-item       {:feed-guid "a94f" :item-guid "x"}}]})
  (payments (:recipients block) 100)
  (recipients-at block 100)
  (sats block)
  #_.)
