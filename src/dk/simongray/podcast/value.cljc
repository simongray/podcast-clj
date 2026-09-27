(ns dk.simongray.podcast.value
  "Value for value: the arithmetic of `podcast:value` blocks. Which block
  applies to an item, who is paid at a given moment of playback once
  `valueTimeSplit`s are taken into account, and how an amount divides into
  shares and fees. No payments are made here; that is a client's business.

  Value blocks and recipients are the maps `dk.simongray.podcast.feed`
  produces: `{:type \"lightning\" :method \"keysend\" :suggested 0.00000005
  :recipients [{:type \"node\" :address \"…\" :split 99 :fee false}]
  :time-splits [...]}`. `boostagram` writes the record an app attaches to
  the payment it then makes."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time :as time]))

(defn effective
  "The value block that applies to `item` in `feed`: the item's own when it
  declares one, else the feed's. With `type`, only blocks of that type
  (e.g. \"lightning\") count."
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
  gives none that is a number, as when \"12.5\" did not read as a whole
  number."
  [recipient]
  (let [split (:split recipient)]
    (if (and (number? split) (pos? split)) split 0)))

(defn time-split-at
  "The `valueTimeSplit` of `block` in effect `seconds` into playback, if any."
  [block seconds]
  (some (fn [{:keys [start-time duration] :as split}]
          (when (and (number? start-time) (number? duration)
                     (<= start-time seconds) (< seconds (+ start-time duration)))
            split))
        (:time-splits block)))

(defn recipients-at
  "Who is paid `seconds` into playback of media governed by `block`, as
  `{:recipients [...]}`: the block's own recipients, or inside a time split
  its fee recipients plus the split's. Inside a split that points at a
  remote item, the fee recipients alone, with `{:remote-item :percentage
  :start-time :offset}` under `:remote` for the caller to resolve."
  [block seconds]
  (let [recipients (:recipients block)
        fees       (filter fee? recipients)]
    (if-let [{:keys [remote-item remote-percentage remote-start-time] :as split}
             (time-split-at block seconds)]
      (if remote-item
        {:recipients (vec fees)
         :remote     {:remote-item remote-item
                      :percentage  (-> (if (number? remote-percentage) remote-percentage 100) (max 0) (min 100))
                      :start-time  (if (number? remote-start-time) remote-start-time 0)
                      :offset      (- seconds (:start-time split))}}
        {:recipients (into (vec fees) (:recipients split))})
      {:recipients (vec recipients)})))

(defn share-total
  "The sum of the splits of the non-fee `recipients`."
  [recipients]
  (transduce (comp (remove fee?) (map split-of)) + 0 recipients))

(defn with-remote
  "Fold the `remote` value block a time split pointed at into `at`, the
  result of `recipients-at` for `block` at that moment: `{:recipients
  [...]}` with the fees of both blocks, the remote block's non-fee
  recipients for the split's percentage and the block's own for the rest,
  their splits rescaled to whole shares that express that. Without a
  `remote` block the block's own recipients apply; outside a remote split
  `at` is returned as it is."
  [block at remote]
  (if-let [{:keys [percentage]} (:remote at)]
    (let [local  (remove fee? (:recipients block))
          others (remove fee? (:recipients remote))
          fees   (filter fee? (concat (:recipients block) (:recipients remote)))
          total  (share-total local)
          total' (share-total others)]
      (if (zero? total')
        {:recipients (vec (:recipients block))}
        {:recipients (->> (concat fees
                                  (for [r local]
                                    (assoc r :split (* (split-of r) (- 100 percentage) total')))
                                  (for [r others]
                                    (assoc r :split (* (split-of r) percentage (max total 1)))))
                          (remove #(and (not (fee? %)) (zero? (:split %))))
                          vec)}))
    at))

(defn payments
  "Divide `amount` (an integer count of the smallest unit, e.g. sats) among
  `recipients` the way the specification prescribes: each fee recipient
  takes its split as a percentage off the top, and the rest is shared among
  the others by their splits. Returns `[{:recipient r :amount n} ...]` with
  whole-unit amounts; a rounding remainder goes to the largest share. A
  split that is not a number counts as none, a fee takes at most all, and
  the fees stop where the amount runs out, so that no amount is negative."
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
  "The `:suggested` amount of `block`, given in BTC, as whole satoshis; nil
  when it has none."
  [block]
  (when-let [btc (:suggested block)]
    (when (number? btc)
      (long (Math/round (* btc 100000000.0))))))

(def ^:no-doc boostagram-keys
  "The TLV record's name for each key of a payment map that is not simply
  its snake_case."
  {:feed-guid "guid" :feed-url "url" :feed-id "feedID" :item-guid "episode_guid" :item-id "itemID"})

(defn boostagram
  "The bLIP-10 record an app attaches to a keysend payment (TLV type
  7629169) for the `payment` map, as a map with the record's own string keys
  ready for `dk.simongray.podcast.json/write-str`: `:position` (seconds into
  the episode) becomes ts and time, `:action` (:boost, :stream or :auto,
  :boost when absent) action, `:sats` and `:total-sats` value_msat and
  value_msat_total, `:feed-guid`, `:feed-url`, `:feed-id`, `:item-guid` and
  `:item-id` the record's names for them, and any other key its snake_case
  name. Nil values are left out."
  [{:keys [position action sats total-sats] :as payment}]
  (let [derived #{:position :action :sats :total-sats}
        record  (into {} (for [[k v] payment
                               :when (some? v)
                               :when (not (derived k))]
                           [(get boostagram-keys k (str/replace (name k) "-" "_")) v]))
        clock   (time/format-timestamp position)]
    (cond-> (assoc record "action" (name (or action :boost)))
      clock          (assoc "ts" (max 0 (long (Math/floor position)))
                            "time" (subs clock 0 (- (count clock) 4)))
      (number? sats) (assoc "value_msat" (* 1000 (long sats))
                            "value_msat_total" (* 1000 (long (or total-sats sats)))))))

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
