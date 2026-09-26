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

(defn time-split-at
  "The `valueTimeSplit` of `block` in effect `seconds` into playback, if any."
  [block seconds]
  (some (fn [{:keys [start-time duration] :as split}]
          (when (and (number? start-time) (number? duration)
                     (<= start-time seconds) (< seconds (+ start-time duration)))
            split))
        (:time-splits block)))

(defn recipients-at
  "Who is paid `seconds` into playback of media governed by `block`:

      {:recipients [...]            ; the recipients to pay, fees included
       :remote     {...}}           ; or, for a remote split, what to resolve

  Outside any time split, the block's own recipients. Inside a split that
  names recipients, the block's fee recipients plus the split's recipients.
  Inside a split that points at a remote item, the block's fee recipients
  as `:recipients` and the remote item, its percentage and its start offset
  under `:remote`, for the caller to look up through the Podcast Index."
  [block seconds]
  (let [recipients (:recipients block)
        fees       (filter fee? recipients)]
    (if-let [{:keys [remote-item remote-percentage remote-start-time] :as split}
             (time-split-at block seconds)]
      (if remote-item
        {:recipients (vec fees)
         :remote     {:remote-item remote-item
                      :percentage  (-> (or remote-percentage 100) (max 0) (min 100))
                      :start-time  (or remote-start-time 0)
                      :offset      (- seconds (:start-time split))}}
        {:recipients (into (vec fees) (:recipients split))})
      {:recipients (vec recipients)})))

(defn share-total
  "The sum of the splits of the non-fee `recipients`."
  [recipients]
  (transduce (comp (remove fee?) (map :split) (filter number?)) + 0 recipients))

(defn with-remote
  "Fold the `remote` value block a time split pointed at into `at`, the
  result of `recipients-at` for the `block` at that moment. Fees from both
  blocks stay off the top; the remaining amount goes to the remote block's
  recipients for the split's percentage and to the block's own non-fee
  recipients for the rest, each side divided by its own splits. The splits
  come back rescaled to whole shares that express exactly that. Without a
  remote block (it could not be resolved), the block's own recipients apply,
  as the specification tells apps to fall back."
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
                                    (assoc r :split (* (:split r 0) (- 100 percentage) total')))
                                  (for [r others]
                                    (assoc r :split (* (:split r 0) percentage (max total 1)))))
                          (remove #(and (not (fee? %)) (zero? (:split % 0))))
                          vec)}))
    at))

(defn payments
  "Divide `amount` (an integer count of the smallest unit, e.g. sats) among
  `recipients` the way the specification prescribes: each fee recipient
  takes its split as a percentage off the top, and the rest is shared among
  the others by their splits. Returns `[{:recipient r :amount n} ...]` with
  whole-unit amounts; a rounding remainder goes to the largest share."
  [recipients amount]
  (let [fees      (filter fee? recipients)
        shares    (remove fee? recipients)
        fee-paid  (for [r fees]
                    {:recipient r :amount (long (Math/floor (/ (* amount (:split r 0)) 100)))})
        remaining (- amount (transduce (map :amount) + 0 fee-paid))
        total     (share-total shares)
        paid      (for [r shares]
                    {:recipient r
                     :amount    (if (pos? total)
                                  (long (Math/floor (/ (* remaining (:split r 0)) total)))
                                  0)})
        leftover  (- remaining (transduce (map :amount) + 0 paid))
        largest   (when (seq paid) (apply max-key (comp :split :recipient) paid))]
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

;;;; The record a payment carries

(def ^:no-doc boostagram-keys
  "The TLV record's name for each key of a payment map that is not simply
  its snake_case."
  {:feed-guid "guid" :feed-url "url" :feed-id "feedID" :item-guid "episode_guid" :item-id "itemID"})

(defn boostagram
  "The bLIP-10 record an app attaches to a keysend payment (TLV type
  7629169), as a map with the record's own keys, ready for
  `dk.simongray.podcast.json/write-str`, from the `payment` map:

      {:podcast \"…\" :feed-guid \"…\" :feed-url \"…\" :feed-id 920666  ; the show
       :episode \"…\" :item-guid \"…\" :item-id 16795090                ; the episode
       :position 754                    ; seconds into it, written as ts and time
       :action :boost                   ; :boost, :stream or :auto; :boost when absent
       :sats 1000 :total-sats 5000      ; this recipient's share and the whole payment
       :name \"Host\"                   ; the recipient's name from the value block
       :app-name \"…\" :app-version \"…\" :sender-name \"…\" :sender-id \"…\" :message \"…\"
       :remote-feed-guid \"…\" :remote-item-guid \"…\"}  ; when a time split sent it elsewhere

  Any other key goes in as its snake_case name, so the rarer fields of the
  specification (uuid, boost_link, reply_address, speed, signature) need no
  special support. Nil values are left out."
  [{:keys [position action sats total-sats] :as payment}]
  (let [derived #{:position :action :sats :total-sats}
        record  (into {} (for [[k v] payment
                               :when (some? v)
                               :when (not (derived k))]
                           [(get boostagram-keys k (str/replace (name k) "-" "_")) v]))]
    (cond-> (assoc record "action" (name (or action :boost)))
      (number? position) (assoc "ts" (long (Math/floor position))
                                "time" (subs (time/format-timestamp position) 0 8))
      (number? sats)     (assoc "value_msat" (* 1000 (long sats))
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
