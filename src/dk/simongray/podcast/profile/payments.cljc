(ns dk.simongray.podcast.profile.payments
  "The record of the listener's payments in a profile, so that a payment
  that went to the wallet isn't sent again, on this device or another.

  Each payment to a recipient is an entry under its id, as
  value.recipients/pay! gives it, and the last write wins:

      {:status   :paid     ; or :sending, :failed or :skipped
       :sats     50
       :address  \"ann@example.org\" :type \"lnaddress\" :name \"Ann\"
       :preimage \"…\"  :proven? true  :invoice \"lnbc…\"
       :error    :value/wallet-silent  ; the id of the diagnostic
       :removed? false}

  Record a payment as :sending just before it goes to the wallet, and
  again with what the wallet answered. One that is still :sending after a
  crash, or on another device after a sync, may be paid, so ask the
  wallet rather than sending it again."
  (:require [dk.simongray.mind-meld :as crdt]))

(def ^:no-doc kept-keys
  "The keys of an entry of pay! that the record keeps as they are."
  [:status :sats :preimage :proven? :invoice :action :feed-guid :item-guid])

(defn- slim
  "What the record keeps of the `entry` of a payment: its kept-keys, the
  address, type and name of its recipient, and the id of its error."
  [{:keys [recipient error] :as entry}]
  (cond-> (assoc (select-keys entry kept-keys) :removed? false)
    (:address recipient) (assoc :address (:address recipient))
    (:type recipient)    (assoc :type (:type recipient))
    (:name recipient)    (assoc :name (:name recipient))
    error                (assoc :error (:id error))))

(defn record
  "Record the `entry` of a payment in `profile` at `at`, under its :id, as
  value.recipients/pay! gives it, to its :sending-fn and in its result.
  The entry can have the :action, :feed-guid and :item-guid of the
  payment too."
  [profile {:keys [id] :as entry} at]
  {:pre [(some? id)]}
  (crdt/write profile [:payments id] (constantly (slim entry)) at))

(defn payments
  "The recorded payments of `profile`, as a map of id to entry, without
  those forgotten."
  [profile]
  (into {} (filter (comp crdt/live? val)) (:payments profile)))

(defn unsettled
  "The ids of the payments of `profile` that went to the wallet with no
  answer recorded. Don't send them again, but ask the wallet whether it
  paid, e.g. with nwc/lookup-invoice, or tell the listener."
  [profile]
  (keep (fn [[id entry]]
          (when (= :sending (:status entry))
            id))
        (payments profile)))

(defn forget
  "Take the payments `ids` out of the record of `profile` at `at`, e.g.
  the old ones, leaving tombstones until every device has seen them."
  [profile ids at]
  (reduce #(crdt/remove %1 [:payments %2] at) profile ids))
