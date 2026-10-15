(ns dk.simongray.podcast.value.recipients
  "The recipients of the value blocks of a feed: what's wrong with them,
  what their Lightning addresses answer, and paying them all at once.

  A host or a validator checks a feed with problems and check!, and an app
  pays a boost or a stretch of a stream with pay!, through the wallet of
  the listener."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.bolt11 :as bolt11]
            [dk.simongray.podcast.value.impl :as value.impl]
            [dk.simongray.podcast.value.lnaddress :as lnaddress]
            [dk.simongray.wary-fetch.async :as async]))

(defn- blocks
  "The value blocks of `feed`, of its channel and of its items, as pairs of
  a path into the feed and a block."
  [feed]
  (concat (map-indexed (fn [i block] [[:podcast/values i] block])
                       (:podcast/values feed))
          (for [[k item]  (map-indexed vector (:rss/items feed))
                [i block] (map-indexed vector (:podcast/values item))]
            [[:rss/items k :podcast/values i] block])))

(defn- recipients
  "The recipients of `feed`, of its value blocks and their time splits, as
  pairs of a path into the feed and a recipient."
  [feed]
  (for [[path block]     (blocks feed)
        [path' holder]   (cons [path block]
                               (map-indexed (fn [i split]
                                              [(conj path :time-splits i)
                                               split])
                                            (:time-splits block)))
        [j recipient]    (map-indexed vector (:recipients holder))]
    [(conj path' :recipients j) recipient]))

;; The podcast namespace, valueRecipient, as of October 2026
(defn- address-problem
  "The diagnostic of the `recipient` at `path` whose address doesn't have
  the form of its type, or nil."
  [path {:keys [type address] :as recipient}]
  (when (case (some-> type str str/lower-case)
          "node"      (not (value.impl/node-key? address))
          "lnaddress" (nil? (lnaddress/parse address))
          false)
    {:id :value/recipient-address :path path :type type :address address}))

(defn- same-key
  "What tells the wallet of `recipient` apart: its address in lower case,
  its custom key and its custom value."
  [{:keys [address custom-key custom-value] :as recipient}]
  [(some-> address str str/lower-case) custom-key custom-value])

;; The podcast namespace, value, the part Payment calculation: a fee is a
;; percentage, and the rest is shared by the splits
(defn- recipients-problems
  "The diagnostics of the `recipients` of a block or a time split at
  `path`."
  [recipients path]
  (let [fees  (transduce (comp (filter value.impl/fee?)
                               (map value.impl/split-of))
                         + 0 recipients)
        twice (->> (group-by same-key recipients)
                   vals
                   (filter #(< 1 (count %))))]
    (concat
     (keep-indexed #(address-problem (conj path :recipients %1) %2)
                   recipients)
     (when (< 100 fees)
       [{:id :value/fees-over :path path :percent fees}])
     (when (and (seq recipients)
                (zero? (value/share-total recipients)))
       [{:id :value/no-shares :path path}])
     (for [same twice]
       {:id      :value/same-recipient
        :path    path
        :address (:address (first same))}))))

(defn- overlaps
  "The diagnostics of the time splits of a block at `path` that start
  before the one before them ends, as `indexed`, pairs of an index and a
  split, has them."
  [indexed path]
  (let [timed  (filter (fn [[_ {:keys [start-time duration]}]]
                         (and (number? start-time) (number? duration)))
                       indexed)
        sorted (sort-by (comp :start-time second) timed)]
    (for [[[_ a] [j b]] (partition 2 1 sorted)
          :when (< (:start-time b) (+ (:start-time a) (:duration a)))]
      {:id :value/time-splits-overlap :path (conj path :time-splits j)})))

;; The podcast namespace, valueTimeSplit: one remote item or recipients
(defn- split-problems
  "The diagnostics of the time `splits` of a block at `path`."
  [splits path]
  (let [indexed (map-indexed vector splits)]
    (concat
     (for [[i {:keys [recipients remote-item]}] indexed
           :let [path' (conj path :time-splits i)]
           d     (cond
                   (and (empty? recipients) (nil? remote-item))
                   [{:id :value/empty-time-split :path path'}]

                   (and (seq recipients) remote-item)
                   [{:id :value/mixed-time-split :path path'}]

                   :else
                   (recipients-problems recipients path'))]
       d)
     (overlaps indexed path))))

(defn problems
  "The diagnostics of the value blocks of `feed`, of its channel and of its
  items, that would send a payment where the podcaster doesn't mean it to
  go. Each has the :path of the block, the time split or the recipient.

  They're those of these checks, which the validator leaves out, since
  they're about payments rather than the form of the feed:

  - a block without recipients
  - fees of more than 100 percent, or no recipient with a share
  - two recipients with the same wallet
  - an address that doesn't have the form of the type of its recipient
  - a time split without recipients or a remote item, or with both
  - time splits that overlap"
  [feed]
  (mapv diagnostics/describe
        (for [[path block] (blocks feed)
              d            (concat
                            (when (empty? (:recipients block))
                              [{:id :value/no-recipients :path path}])
                            (recipients-problems (:recipients block) path)
                            (split-problems (:time-splits block) path))]
          d)))

(defn- lnaddress?
  "Whether the `recipient` is of the type lnaddress."
  [recipient]
  (= "lnaddress" (some-> (:type recipient) str str/lower-case)))

(defn- checked
  "What the Lightning `address` at `path` answers to the requests for its
  terms and its node with `opts`, as a map of the :terms, the :node and the
  :report, in a future or a promise."
  [[address path] opts]
  (async/then
   (async/all force [(delay (lnaddress/fetch-terms! address opts))
                     (delay (lnaddress/fetch-node! address opts))])
   (fn [[terms node]]
     (let [placed #(assoc (:error %) :path path :address address)]
       {:address address
        :terms   (when-not (:error terms) terms)
        :node    (when-not (:error node) node)
        :report  (keep #(when (:error %) (placed %)) [terms node])}))))

(defn check!
  "Ask the domain of each Lightning address among the recipients of `feed`
  for its terms of LNURL-pay and its node for keysend, with `opts`, and
  give what they answered, in a future or a promise:

      {:addresses {\"ann@example.org\" {:terms {…} :node {…}}}
       :report    [...]}  ; what failed, with the :path of the recipient

  An address without a node for keysend is reported at the level info,
  since an app pays it with an invoice instead. The `opts` are those of
  lnaddress/fetch-terms!, and the addresses are asked once each, four at a
  time."
  ([feed]
   (check! feed {}))
  ([feed opts]
   {:pre [(map? opts)]}
   (let [found (->> (recipients feed)
                    (filter (comp lnaddress? second))
                    (reduce (fn [acc [path {:keys [address]}]]
                              (let [k (str/lower-case (str address))]
                                (cond-> acc
                                  (not (contains? acc k)) (assoc k path))))
                            {}))]
     (async/then (async/in-groups #(checked % opts) 4 found)
                 (fn [results]
                   {:addresses (into {}
                                     (map (juxt :address
                                                #(select-keys % [:terms
                                                                 :node])))
                                     results)
                    :report    (vec (mapcat :report results))})))))

(defn- paid
  "The entry of a payment of `sats` to `recipient` from the `answer` of the
  wallet: paid, with the preimage, or failed, with the error. With an
  `invoice`, the preimage proves the payment when it hashes to the payment
  hash of the invoice."
  [recipient sats answer invoice]
  (let [{:keys [error preimage]} answer]
    (cond-> {:recipient recipient :sats sats}
      error       (assoc :status :failed :error error)
      (not error) (assoc :status :paid :preimage preimage)
      (and (not error) invoice)
      (assoc :proven? (boolean (and preimage
                                    (bolt11/proves? preimage invoice)))))))

(defn- skipped
  "The entry of a payment of `sats` to `recipient` that wasn't sent, with
  the diagnostic `id` and its `details`, or none."
  [recipient sats & [id & {:as details}]]
  (cond-> {:recipient recipient :sats sats :status :skipped}
    id (assoc :error (diagnostics/describe (merge {:id id} details)))))

(defn- sending
  "The entry of the `payment` of `sats` to `recipient` just before it goes
  to the wallet, with the `invoice` that it pays, if any."
  [payment sats recipient invoice]
  (cond-> {:recipient recipient :sats sats :status :sending}
    (:id payment) (assoc :id (:id payment))
    invoice       (assoc :invoice invoice)))

(defn- answer-of
  "What the wallet answered, from the `attempt` of a function of the app
  that pays: its value, or an error when it threw or gave no map."
  [{:keys [value error] :as attempt}]
  (if (or error (not (map? value)))
    {:error (diagnostics/describe
             {:id     :value/wallet-unreadable
              :reason (if error (async/explain error) "no answer")})}
    value))

(defn- pay-node!
  "Pay the `payment` of `sats` to the node `recipient` with the :keysend-fn
  of `opts`, after a call of its :sending-fn."
  [payment sats recipient {:keys [keysend-fn sending-fn] :as opts}]
  (let [sent    (assoc payment :sats sats)
        missing (first (value/report (value/records sent recipient)))]
    (cond
      (nil? keysend-fn)
      (skipped recipient sats :value/no-method :type "node")

      missing
      (assoc (skipped recipient sats) :error missing)

      :else
      (do (when sending-fn
            (sending-fn (sending payment sats recipient nil)))
          (async/then (async/attempt #(keysend-fn sent recipient))
                      #(paid recipient sats (answer-of %) nil))))))

(defn- pay-address!
  "Pay the `payment` of `sats` to the Lightning address `recipient` with
  an invoice from its service and the :invoice-fn of `opts`, after a call
  of its :sending-fn with the invoice."
  [payment sats recipient {:keys [invoice-fn invoice-opts sending-fn]
                           :as   opts}]
  (if (nil? invoice-fn)
    (skipped recipient sats :value/no-method :type "lnaddress")
    (async/then
     (lnaddress/request-invoice! (:address recipient) (* 1000 sats)
                                 (or invoice-opts {}))
     (fn [{:keys [invoice error]}]
       (if error
         (assoc (skipped recipient sats) :status :failed :error error)
         (do (when sending-fn
               (sending-fn (sending payment sats recipient invoice)))
             (async/then (async/attempt #(invoice-fn invoice))
                         #(-> (paid recipient sats (answer-of %) invoice)
                              (assoc :invoice invoice)))))))))

(defn pay!
  "Pay `sats` to the `recipients` of a value block, divided as
  value/payments divides them, with the `payment` that goes with each,
  through the wallet of the listener, as the `opts` below say. It gives an
  entry for each recipient, in a future or a promise.

  - :keysend-fn, a function of a payment with its :sats and a recipient of
    the type node, which pays it and gives what the wallet answered, e.g.
    with nwc/ask! of nwc/pay-keysend, or webln/pay-keysend!
  - :invoice-fn, a function of an invoice, which pays it and gives what
    the wallet answered, e.g. webln/pay-invoice!
  - :invoice-opts, the options of lnaddress/request-invoice! for the
    recipients of the type lnaddress, e.g. a :comment
  - :sending-fn, a function called with each entry just before it goes to
    the wallet, so that you can record it, e.g. with profile.payments,
    and not send it again after a crash

  An entry has the :recipient, the :sats, and a :status of :paid, with
  the :preimage, :failed or :skipped, with the :error. A share of less
  than a sat is skipped, and so is a recipient without a way to pay its
  type, or one whose custom record wouldn't reach its wallet. A payment to
  an invoice is :proven? when the preimage proves it.

  The payments go one at a time, so the wallet sees no burst, and the
  :total-sats of each is the whole `sats`, as bLIP-10 has it. When the
  `payment` has a :group, the id of the boost or the stretch of a stream,
  the payment to each recipient has the :id of that group and its place,
  e.g. \"b7e1…/2\", as RSS Payment Metadata has it, and so does its entry."
  [recipients sats payment opts]
  (let [payment (merge {:total-sats sats} payment)
        group   (:group payment)
        one     (fn [i {:keys [recipient amount]}]
                  (let [payment (cond-> payment
                                  group (assoc :id (str group "/" i)))
                        entry   (cond
                                  (not (pos? amount))
                                  (skipped recipient amount)

                                  (lnaddress? recipient)
                                  (pay-address! payment amount recipient opts)

                                  (= "node" (some-> (:type recipient)
                                                    str
                                                    str/lower-case))
                                  (pay-node! payment amount recipient opts)

                                  :else
                                  (skipped recipient amount :value/no-method
                                           :type (:type recipient)))]
                    (async/then entry #(cond-> %
                                         group (assoc :id (:id payment))))))]
    (reduce (fn [acc [i share]]
              (async/then acc (fn [done] (async/then (one i share)
                                                     #(conj done %)))))
            (async/then [] identity)
            (map-indexed vector (value/payments recipients sats)))))
