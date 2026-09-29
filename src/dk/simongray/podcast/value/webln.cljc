(ns dk.simongray.podcast.value.webln
  "WebLN, the interface that a wallet gives a web page: payments from the
  wallet of the listener, in a browser.

  A browser extension or a wallet app puts its provider at
  `window.webln`. The page asks for the use of the provider, and the
  wallet asks the listener. Then the wallet asks the listener again for
  each payment, unless the listener gave the page a budget.

  `keysend-args` gives the arguments of a keysend payment as data, on
  both platforms. The functions with a name that ends in `!` call the
  provider, so they are in ClojureScript only. Each gives a promise of
  its result, or of `{:error diagnostic}`.

  No standards body defines WebLN. The guide at webln.guide defines its
  methods, and doc/services.md tells when podcast-clj read it."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.value :as value]
            #?(:cljs [dk.simongray.podcast.value.lnaddress :as lnaddress])))

(defn keysend-args
  "The arguments of `webln.keysend` for `payment` to `recipient`, as a map
  with the string keys of WebLN. Nil for a recipient that is no node, and
  for a payment below 1 sat.

      {\"destination\"   \"03…\"
       \"amount\"        \"50\"
       \"customRecords\" {\"7629169\" \"{\\\"action\\\":\\\"boost\\\",…}\" \"696969\" \"22\"}}

  The payment is the map of `dk.simongray.podcast.value`, and the
  recipient is a recipient of a value block, of the type node. WebLN
  counts in whole sats, so the amount is the whole sats of the payment,
  and the bLIP-10 record tells that amount. The custom records are those
  of `value/records`, as text. `value/report` of the arguments tells when
  they leave out the custom key or value of the recipient. Do not send the
  payment then."
  [payment {:keys [address] :as recipient}]
  (let [[msat] (value/amounts payment)
        sats   (when msat (quot msat 1000))]
    (when (and sats (pos? sats) (string? address) (re-matches #"(?i)0[23][0-9a-f]{64}" address))
      (let [records (value/records (-> payment (dissoc :sats) (assoc :msat (* 1000 sats))) recipient)]
        (with-meta {"destination"   (str/lower-case address)
                    "amount"        (str sats)
                    "customRecords" (into {} (map (fn [[k v]] [(str k) v])) records)}
          (meta records))))))

(defn ^:no-doc failure
  "`{:error diagnostic}` for the diagnostic `d`."
  [d]
  {:error (diagnostics/describe d)})

#?(:cljs
   (defn provider
     "The WebLN provider of the page, or nil when the page has none."
     []
     (some-> js/globalThis .-webln)))

#?(:cljs
   (defn- called!
     "A promise of what `read` gives for the result of `(f provider)`, after
     the listener allowed the use of the provider. `opts` can give the
     `:provider`. A provider that says no, and a listener who does, give
     `:value/wallet-refused`."
     [{given :provider :as opts} f read]
     (if-let [^js provider (if (contains? opts :provider) given (provider))]
       (-> (js/Promise.resolve nil)
           (.then (fn [_] (.enable provider)))
           (.then (fn [_] (f provider)))
           (.then (fn [result] (read (js->clj result))))
           (.catch (fn [e]
                     (failure {:id :value/wallet-refused :reason (str (or (some-> e .-message) e))}))))
       (js/Promise.resolve (failure {:id :value/no-provider})))))

#?(:cljs
   (defn enable!
     "Ask the listener for the use of the WebLN provider. A promise of
     `{:enabled? true}`, or of `{:error diagnostic}`: `:value/no-provider`
     for a page without a provider, and `:value/wallet-refused` when the
     listener said no.

     `opts` can give `:provider`, a provider in the place of that of the
     page. The other functions ask too, so an app needs `enable!` only to
     know the answer early."
     ([]
      (enable! {}))
     ([opts]
      {:pre [(map? opts)]}
      (called! opts (constantly nil) (constantly {:enabled? true})))))

#?(:cljs
   (defn info!
     "What the wallet of the listener tells of itself: a promise of
     `{:alias :public-key :methods}`, as far as the wallet gives them, or of
     `{:error diagnostic}`. `:methods` names the methods of WebLN that the
     wallet has, such as keysend. `opts` are those of `enable!`."
     ([]
      (info! {}))
     ([opts]
      {:pre [(map? opts)]}
      (called! opts
               (fn [^js provider] (.getInfo provider))
               (fn [{:strs [node methods]}]
                 (cond-> {}
                   (string? (get node "alias"))  (assoc :alias (get node "alias"))
                   (string? (get node "pubkey")) (assoc :public-key (get node "pubkey"))
                   (sequential? methods)         (assoc :methods (vec (filter string? methods)))))))))

#?(:cljs
   (defn pay-invoice!
     "Pay the BOLT 11 invoice `invoice` from the wallet of the listener. A
     promise of `{:preimage \"…\" :proven? true}`, or of `{:error
     diagnostic}`. `opts` are those of `enable!`.

     `:proven?` tells whether the preimage is the proof of the invoice, as
     `lnaddress/proves?` tells. When it is false, the wallet tells that it
     paid, and it gave no proof. Do not pay again then, because the wallet
     can have paid. `value.lnaddress/invoice!` gives the invoice of a
     Lightning address."
     ([invoice]
      (pay-invoice! invoice {}))
     ([invoice opts]
      {:pre [(map? opts) (string? invoice)]}
      (called! opts
               (fn [^js provider] (.sendPayment provider invoice))
               (fn [{:strs [preimage]}]
                 {:preimage preimage :proven? (lnaddress/proves? preimage invoice)})))))

#?(:cljs
   (defn pay-keysend!
     "Pay `payment` to `recipient` with keysend from the wallet of the
     listener, with the arguments of `keysend-args`. A promise of
     `{:preimage \"…\" :sats 50}`, or of `{:error diagnostic}`. `opts` are
     those of `enable!`.

     Nothing goes out for a payment below 1 sat, for a recipient that is no
     node, and for records that leave out the custom key or value of the
     recipient. The errors are `:value/below-one-sat`, `:value/not-a-node`
     and `:value/custom-record`."
     ([payment recipient]
      (pay-keysend! payment recipient {}))
     ([payment recipient opts]
      {:pre [(map? opts)]}
      (let [args    (keysend-args payment recipient)
            [msat]  (value/amounts payment)
            problem (cond
                      args                     (first (value/report args))
                      (and msat (< msat 1000)) (diagnostics/describe {:id :value/below-one-sat :msat msat})
                      :else                    (diagnostics/describe {:id :value/not-a-node :address (:address recipient)}))]
        (if problem
          (js/Promise.resolve {:error problem})
          (called! opts
                   (fn [^js provider] (.keysend provider (clj->js args)))
                   (fn [{:strs [preimage]}]
                     {:preimage preimage :sats (parse-long (get args "amount"))})))))))

(comment
  ;; 50,500 millisatoshis are 50 sats in WebLN
  (def args
    (keysend-args {:action :boost :msat 50500 :message "Great episode" :app-name "My app"}
                  {:type "node" :name "Ann" :address (apply str "03" (repeat 64 "a"))
                   :custom-key "696969" :custom-value "22"}))

  args
  (value/report args)

  ;; below 1 sat, and a Lightning address, which gets an invoice
  (keysend-args {:msat 999} {:type "node" :address (apply str "03" (repeat 64 "a"))})
  (keysend-args {:sats 50} {:type "lnaddress" :address "ann@example.org"})
  #_.)
