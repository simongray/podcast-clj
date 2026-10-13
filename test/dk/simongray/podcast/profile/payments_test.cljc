(ns dk.simongray.podcast.profile.payments-test
  "The record of the listener's payments in a profile, across a sync."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.payments :as profile.payments]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2]]))

(def ann
  {:type "lnaddress" :address "ann@example.org" :name "Ann" :split 50})

(deftest the-record-of-payments
  (let [sending {:id "b7e1/0" :recipient ann :sats 50 :status :sending
                 :invoice "lnbc500n1…" :feed-guid "g"}
        phone   (profile.payments/record (profile/make "phone") sending t0)]
    (is (= {"b7e1/0" {:status :sending :sats 50 :invoice "lnbc500n1…" :feed-guid "g"
                      :address "ann@example.org" :type "lnaddress" :name "Ann" :removed? false
                      :updated-at t0 :device "phone"}}
           (profile.payments/payments phone))
        "what the record keeps, with its stamp")
    (is (= ["b7e1/0"] (profile.payments/unsettled phone)))
    (let [laptop (profile/merge (profile/make "laptop") phone)]
      (is (= ["b7e1/0"] (profile.payments/unsettled laptop))
          "another device knows not to send it again"))
    (let [paid (profile.payments/record phone (assoc sending :status :paid :preimage "77" :proven? true) t1)]
      (is (= [] (profile.payments/unsettled paid)))
      (is (= [:paid true] ((juxt :status :proven?) (get (profile.payments/payments paid) "b7e1/0"))))
      (is (= {} (profile.payments/payments (profile.payments/forget paid ["b7e1/0"] t2)))))
    (let [failed (profile.payments/record phone {:id "b7e1/1" :recipient ann :sats 5 :status :failed
                                                 :error {:id :value/wallet-refused :reason "No money"}}
                                          t1)]
      (is (= :value/wallet-refused (get-in (profile.payments/payments failed) ["b7e1/1" :error]))))))
