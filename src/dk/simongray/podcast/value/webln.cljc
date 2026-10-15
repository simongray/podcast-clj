(ns dk.simongray.podcast.value.webln
  "WebLN, the interface that a wallet gives a web page: payments from the
  wallet of the listener, in a browser.

  A browser extension or a wallet app puts its provider at window.webln.
  The page asks for the use of the provider, and the wallet asks the
  listener. Then the wallet asks the listener again for each payment,
  unless the listener gave the page a budget.

  The functions that call the provider are in ClojureScript only, and
  each gives a promise of its result, or of a map with a diagnostic under
  :error.

  No standards body defines WebLN. The guide at webln.guide defines its
  methods, and doc/services.md tells when podcast-clj read it."
  (:require [clojure.string :as str]
            #?(:cljs [dk.simongray.podcast.diagnostics :as diagnostics])
            [dk.simongray.podcast.value :as value]
            #?(:cljs [dk.simongray.podcast.value.bolt11 :as bolt11])
            [dk.simongray.podcast.value.impl :as value.impl]))

(defn keysend-args
  "The arguments of webln.keysend for `payment` to `recipient`, as a map
  with the string keys of WebLN, or nil for a recipient that isn't a node
  or a payment below 1 sat.

      {\"destination\"   \"03…\"
       \"amount\"        \"50\"
       \"customRecords\" {\"7629169\" \"{\\\"action\\\":\\\"boost\\\",…}\"
                        \"696969\"  \"22\"}}

  The payment is the map of dk.simongray.podcast.value, and the recipient
  is a recipient of a value block, of the type node. WebLN counts in whole
  sats, so the amount is the whole sats of the payment, and the bLIP-10
  record tells that amount. The custom records are those of the payment,
  as text. The report of the arguments says when they leave out the
  custom key or value of the recipient. Don't send the payment then."
  [payment {:keys [address] :as recipient}]
  (let [[msat] (value.impl/amounts payment)
        sats   (when msat (quot msat 1000))]
    (when (and sats (pos? sats) (value.impl/node-key? address))
      (let [whole   (-> payment (dissoc :sats) (assoc :msat (* 1000 sats)))
            records (value/records whole recipient)
            by-name (into {} (map (fn [[k v]] [(str k) v])) records)]
        (with-meta {"destination"   (str/lower-case address)
                    "amount"        (str sats)
                    "customRecords" by-name}
          (meta records))))))

#?(:cljs
   (defn provider
     "The WebLN provider of the page, or nil when the page has none."
     []
     (some-> js/globalThis .-webln)))

#?(:cljs
   (defn- call-provider!
     "Call the provider with `f`, after the listener allowed its use, and
     give a promise of what `read` makes of the result, by `opts`.

     The :provider of `opts` replaces that of the page. A provider that
     says no, and a listener who does, give :value/wallet-refused."
     [{given :provider :as opts} f read]
     (if-let [^js provider (if (contains? opts :provider) given (provider))]
       (-> (js/Promise.resolve nil)
           (.then (fn [_] (.enable provider)))
           (.then (fn [_] (f provider)))
           (.then (fn [result] (read (js->clj result))))
           (.catch (fn [e]
                     (let [reason (str (or (some-> e .-message) e))]
                       (diagnostics/failed {:id     :value/wallet-refused
                                            :reason reason})))))
       (js/Promise.resolve (diagnostics/failed {:id :value/no-provider})))))

#?(:cljs
   (defn enable!
     "Ask the listener for the use of the WebLN provider, with `opts`, and
     give a promise of a map with :enabled? true.

     For a page without a provider, the :error is :value/no-provider, and
     when the listener says no, it's :value/wallet-refused. The :provider
     of `opts` replaces that of the page. The other functions ask too, so
     call this only to know the answer early."
     ([]
      (enable! {}))
     ([opts]
      {:pre [(map? opts)]}
      (call-provider! opts (constantly nil) (constantly {:enabled? true})))))

#?(:cljs
   (defn get-info!
     "Ask the wallet of the listener about itself with `opts`, and give a
     promise of a map of its :alias, :public-key and :methods, as far as
     the wallet gives them, or of a map with a diagnostic under :error.

     The :methods are the methods of WebLN that the wallet has, such as
     keysend. The `opts` are those of enable!."
     ([]
      (get-info! {}))
     ([opts]
      {:pre [(map? opts)]}
      (call-provider! opts
                      (fn [^js provider] (.getInfo provider))
                      (fn [{:strs [node methods]}]
                        (let [node-alias (get node "alias")
                              public-key (get node "pubkey")
                              named      (when (sequential? methods)
                                           (vec (filter string? methods)))]
                          (cond-> {}
                            (string? node-alias) (assoc :alias node-alias)
                            (string? public-key) (assoc :public-key public-key)
                            named                (assoc :methods named))))))))

#?(:cljs
   (defn pay-invoice!
     "Pay the BOLT 11 `invoice` from the wallet of the listener, with
     `opts` as enable! takes them. It gives a promise of a map of the
     :preimage and :proven?, or of a map with a diagnostic under :error.

     The :proven? flag tells whether the preimage is the proof of the
     invoice. When it's false, the wallet says that it paid, and it gave
     no proof. Don't pay again then, since the wallet can have paid."
     ([invoice]
      (pay-invoice! invoice {}))
     ([invoice opts]
      {:pre [(map? opts)]}
      (when-not (string? invoice)
        (throw (ex-info "The invoice isn't a string" {})))
      (call-provider! opts
                      (fn [^js provider] (.sendPayment provider invoice))
                      (fn [{:strs [preimage]}]
                        {:preimage preimage
                         :proven?  (bolt11/proves? preimage invoice)})))))

#?(:cljs
   (defn pay-keysend!
     "Pay `payment` to `recipient` with keysend from the wallet of the
     listener, with `opts` as enable! takes them.

     It gives a promise of a map of the :preimage and the :sats, or of a
     map with a diagnostic under :error. Nothing goes out for a payment
     below 1 sat, for a recipient that isn't a node, or for records that
     leave out the custom key or value of the recipient. The errors are
     then :value/below-one-sat, :value/not-a-node and :value/custom-record."
     ([payment recipient]
      (pay-keysend! payment recipient {}))
     ([payment recipient opts]
      {:pre [(map? opts)]}
      (let [args    (keysend-args payment recipient)
            [msat]  (value.impl/amounts payment)
            problem (cond
                      args
                      (first (value/report args))

                      (and msat (< msat 1000))
                      (diagnostics/describe {:id   :value/below-one-sat
                                             :msat msat})

                      :else
                      (diagnostics/describe {:id      :value/not-a-node
                                             :address (:address recipient)}))]
        (if problem
          (js/Promise.resolve {:error problem})
          (call-provider! opts
                          (fn [^js provider] (.keysend provider (clj->js args)))
                          (fn [{:strs [preimage]}]
                            {:preimage preimage
                             :sats     (parse-long (get args "amount"))})))))))

(comment
  ;; 50,500 millisatoshis are 50 sats in WebLN
  (def node-address (apply str "03" (repeat 64 "a")))

  (def args
    (keysend-args {:action   :boost
                   :msat     50500
                   :message  "Great episode"
                   :app-name "My app"}
                  {:type         "node"
                   :name         "Ann"
                   :address      node-address
                   :custom-key   "696969"
                   :custom-value "22"}))

  args
  (value/report args)

  ;; below 1 sat, and a Lightning address, which gets an invoice
  (keysend-args {:msat 999} {:type "node" :address node-address})
  (keysend-args {:sats 50} {:type "lnaddress" :address "ann@example.org"})
  #_.)
