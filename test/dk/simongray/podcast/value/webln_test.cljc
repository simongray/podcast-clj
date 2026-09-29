(ns dk.simongray.podcast.value.webln-test
  "WebLN: the arguments of a keysend payment on both platforms, and in
  ClojureScript the calls of a provider, which the tests make of plain
  functions."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            #?(:cljs [dk.simongray.podcast.test-support :refer [invoice run]])
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.webln :as webln]))

(def recipient
  {:type "node" :name "Ann" :address (apply str "03" (repeat 64 "A")) :custom-key "696969" :custom-value "22" :split 100})

(def payment
  {:action :boost :msat 50500 :total-msat 101000 :message "Great episode" :app-name "My app"})

(deftest the-arguments-of-keysend
  (let [args (webln/keysend-args payment recipient)]
    (is (= {"destination" (apply str "03" (repeat 64 "a")) "amount" "50"}
           (dissoc args "customRecords")))
    (is (= #{"7629169" "696969"} (set (keys (get args "customRecords")))))
    (is (= "22" (get-in args ["customRecords" "696969"])))
    (is (= (value/boostagram (assoc payment :msat 50000 :recipient-name "Ann"))
           (json/read-str (get-in args ["customRecords" "7629169"])))
        "the record tells the whole sats that go out")
    (is (= [] (value/report args))))
  (is (= "50" (get (webln/keysend-args {:sats 50} recipient) "amount")))
  (is (= "1" (get (webln/keysend-args {:msat 1999} recipient) "amount")))
  (is (= [:value/custom-record]
         (map :id (value/report (webln/keysend-args payment (dissoc recipient :custom-value))))))
  (testing "what keysend does not pay"
    (doseq [[p r] [[{:msat 999} recipient]
                   [{:msat 0} recipient]
                   [{:message "no amount"} recipient]
                   [payment {:type "lnaddress" :address "ann@example.org"}]
                   [payment {:type "node" :address "03abc"}]
                   [payment {}]]]
      (is (nil? (webln/keysend-args p r)) (pr-str p r)))))

#?(:cljs
   (defn- provider
     "A WebLN provider that notes each call in `log`, and answers a call of
     the method `k` with `(answers k)`, a value or a function that throws."
     [log answers]
     (let [method (fn [k]
                    (fn [& args]
                      (swap! log conj (into [k] (map js->clj) args))
                      (let [x (get answers k)]
                        (js/Promise.resolve (if (fn? x) (x) (clj->js x))))))]
       #js {:enable      (method :enable)
            :getInfo     (method :getInfo)
            :keysend     (method :keysend)
            :sendPayment (method :sendPayment)})))

#?(:cljs
   (deftest the-calls-of-a-provider
     (let [log     (atom [])
           wallet  (provider log {:getInfo     {"node" {"alias" "Ann's wallet" "pubkey" "03ab"} "methods" ["keysend" "sendPayment"]}
                                  :keysend     {"preimage" "0123"}
                                  :sendPayment {"preimage" "4567"}})
           no      (provider log {:enable #(throw (js/Error. "User rejected"))})
           broke   (provider log {:keysend #(throw (js/Error. "Not enough funds"))})
           problem #(dissoc (:error %) :message)]
       (run (fn []
              (.then (webln/pay-keysend! payment recipient {:provider wallet})
                     (fn [read]
                       (is (= {:preimage "0123" :sats 50} read))
                       (is (= [[:enable] [:keysend (webln/keysend-args payment recipient)]] @log)
                           "the wallet asks the listener before the payment"))))
            (fn []
              (reset! log [])
              (.then (webln/pay-invoice! "lnbc500n1x" {:provider wallet})
                     (fn [read]
                       (is (= {:preimage "4567" :proven? false} read))
                       (is (= [[:enable] [:sendPayment "lnbc500n1x"]] @log)))))
            (fn []
              (let [preimage (apply str (repeat 64 "7"))
                    prover   (provider log {:sendPayment {"preimage" preimage}})]
                (.then (js/Promise.all #js [(webln/pay-invoice! (invoice preimage) {:provider prover})
                                            (webln/pay-invoice! (invoice (apply str (repeat 64 "8"))) {:provider prover})])
                       (fn [[proven other]]
                         (is (= {:preimage preimage :proven? true} proven) "the preimage is the proof of the invoice")
                         (is (= {:preimage preimage :proven? false} other) "the preimage of another invoice")))))
            (fn []
              (.then (webln/info! {:provider wallet})
                     #(is (= {:alias "Ann's wallet" :public-key "03ab" :methods ["keysend" "sendPayment"]} %))))
            (fn []
              (.then (webln/enable! {:provider wallet})
                     #(is (= {:enabled? true} %))))
            (fn []
              (reset! log [])
              (.then (webln/pay-keysend! payment recipient {:provider no})
                     (fn [read]
                       (is (= {:id :value/wallet-refused :level :warning :reason "User rejected"} (problem read)))
                       (is (= [[:enable]] @log) "nothing goes out when the listener says no"))))
            (fn []
              (.then (webln/pay-keysend! payment recipient {:provider broke})
                     #(is (= {:id :value/wallet-refused :level :warning :reason "Not enough funds"} (problem %)))))
            (fn []
              (.then (webln/pay-invoice! "lnbc500n1x" {:provider nil})
                     #(is (= {:id :value/no-provider :level :info} (problem %)))))
            (fn []
              (.then (webln/enable!)
                     #(is (= :value/no-provider (:id (:error %))) "Node has no provider")))
            (fn []
              (reset! log [])
              (.then (js/Promise.all
                      #js [(webln/pay-keysend! {:msat 999} recipient {:provider wallet})
                           (webln/pay-keysend! payment {:type "lnaddress" :address "ann@example.org"} {:provider wallet})
                           (webln/pay-keysend! payment (assoc recipient :custom-key "5") {:provider wallet})])
                     (fn [reads]
                       (is (= [:value/below-one-sat :value/not-a-node :value/custom-record]
                              (map (comp :id :error) reads)))
                       (is (= [] @log) "nothing goes out"))))))))
