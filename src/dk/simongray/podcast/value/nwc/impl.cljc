(ns ^:no-doc dk.simongray.podcast.value.nwc.impl
  "The internals of value.nwc: the secret key of a connection, the reading
  of a wallet's result into the keys of podcast-clj, and the fallback from
  one relay to the next."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.value.bolt11 :as bolt11]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def encryption
  "The name that NIP-47 gives the encryption of podcast-clj, in its
  section Encryption."
  "nip44_v2")

(defn secret-key
  "The secret key of the app in `connection`, as text. It's the one place
  where the functions here read the key."
  [connection]
  (secret/reveal (:secret connection)))

(def fields
  "The key and the type of each field of a result that NIP-47 defines. An
  amount is a number of millisatoshis, and a time is a number of
  seconds."
  {"preimage"         [:preimage :text]
   "fees_paid"        [:fees-msat :number]
   "balance"          [:msat :number]
   "amount"           [:msat :number]
   "type"             [:type :text]
   "state"            [:state :text]
   "invoice"          [:invoice :text]
   "description"      [:description :text]
   "description_hash" [:description-hash :text]
   "payment_hash"     [:payment-hash :text]
   "created_at"       [:created-at :time]
   "expires_at"       [:expires-at :time]
   "settled_at"       [:settled-at :time]
   "metadata"         [:metadata :object]
   "alias"            [:alias :text]
   "color"            [:color :text]
   "pubkey"           [:public-key :text]
   "network"          [:network :text]
   "block_height"     [:block-height :number]
   "block_hash"       [:block-hash :text]
   "methods"          [:methods :texts]
   "extensions"       [:extensions :texts]})

(defn- typed
  "The JSON value `v` as the value of a field of the type `type`, or nil
  for a value of another type."
  [type v]
  (case type
    :text   (when (string? v) v)
    :number (json/whole v)
    :time   (some-> (json/whole v) (* 1000) time/->inst)
    :object (when (map? v) v)
    :texts  (when (sequential? v) (vec (filter string? v)))))

(defn result
  "The result `raw` of a wallet in the keys of podcast-clj, by fields. An
  invoice whose expiry the wallet didn't give has that of the invoice
  itself."
  [raw]
  (let [read    (into {} (for [[k v] raw
                               :let  [[name type] (get fields k)
                                      value       (when name (typed type v))]
                               :when (some? value)]
                           [name value]))
        expires (when (and (:invoice read) (not (:expires-at read)))
                  (bolt11/expires-at (:invoice read)))]
    (cond-> read
      expires (assoc :expires-at expires))))

(defn ask-relays!
  "Send the request that `request-of` makes to each of `relays` in turn,
  with `opts` and the failures so far in `tried`. Give what `read` makes
  of the first answer, in a future or a promise.

  The next relay gets the request when a relay gives no connection, or
  when `read` gives nil for its response. The `read` function gives nil
  for a response that says that the request didn't reach the wallet. Each
  failure in `tried` has the :url, the :reason, and whether the relay
  :answered?. When no relay is left, the result has a diagnostic under
  :error.

  An exchange that failed after its event went out has :sent? in the data
  of its exception. The `read` function then gets a response without
  events, so the response is read as for a relay that has no answer."
  [opts request-of read relays tried]
  (if-let [[url & more] (seq relays)]
    (async/then
     (http/try-send! (:send opts) (request-of url) opts)
     (fn [{:keys [value error]}]
       (let [next! (fn [failed]
                     (let [tried (conj tried (assoc failed :url url))]
                       (ask-relays! opts request-of read more tried)))]
         (cond
           (:sent? (ex-data error))
           (read {:url url :events [] :sent? true})

           error
           (next! {:reason (async/explain error) :answered? false})

           :else
           (or (read value)
               (let [reason (or (:refused value)
                                (:closed value)
                                "The relay has no event")]
                 (next! {:reason    reason
                         :answered? (not (:timeout? value))})))))))
    (diagnostics/failed {:id :value/wallet-unreachable :relays tried})))
