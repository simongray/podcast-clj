(ns dk.simongray.podcast.value.recipients-test
  "The recipients of the value blocks of a feed: their problems, a check of
  their Lightning addresses against the recorded answers of the services,
  and paying them all with stand-ins for a wallet."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.test-support :refer [fixture invoice run]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value.recipients :as recipients]
            [dk.simongray.wary-fetch.async :as async]))

(def node-key
  (str "02" (apply str (repeat 64 "a"))))

(def block
  {:type       "lightning"
   :method     "keysend"
   :recipients [{:type "node" :address node-key :split 90 :name "Host"
                 :custom-key "696969" :custom-value "1"}
                {:type "lnaddress" :address "hello@getalby.com" :split 10}
                {:type "node" :address node-key :split 1 :fee true}]})

(deftest the-problems-of-a-value-block
  (is (= [] (recipients/problems {:podcast/values [block]})))
  (let [ids  (fn [feed] (map (juxt :id :path) (recipients/problems feed)))
        with #(assoc-in {:podcast/values [block]} %1 %2)]
    (is (= [[:value/no-recipients [:rss/items 0 :podcast/values 0]]]
           (ids {:rss/items [{:podcast/values [(assoc block :recipients [])]}]}))
        "in an item too")
    (is (= [[:value/recipient-address [:podcast/values 0 :recipients 0]]
            [:value/recipient-address [:podcast/values 0 :recipients 1]]]
           (ids (-> {:podcast/values [block]}
                    (assoc-in [:podcast/values 0 :recipients 0 :address] "02abc")
                    (assoc-in [:podcast/values 0 :recipients 1 :address] "hello@localhost")))))
    (is (= [:value/fees-over :value/no-shares]
           (map first (ids (with [:podcast/values 0 :recipients]
                                 [{:type "node" :address node-key :split 60 :fee true}
                                  {:type "node" :address node-key :split 41 :fee true
                                   :custom-key "696969" :custom-value "2"}])))))
    (is (= [[:value/same-recipient [:podcast/values 0]]]
           (ids (with [:podcast/values 0 :recipients 2]
                      {:type "node" :address (str/upper-case node-key) :split 5
                       :custom-key "696969" :custom-value "1"})))
        "the same wallet in another case")
    (is (= [[:value/empty-time-split [:podcast/values 0 :time-splits 0]]
            [:value/mixed-time-split [:podcast/values 0 :time-splits 1]]
            [:value/time-splits-overlap [:podcast/values 0 :time-splits 1]]]
           (ids (with [:podcast/values 0 :time-splits]
                      [{:start-time 10 :duration 60}
                       {:start-time 30 :duration 10 :remote-item {:feed-guid "g"}
                        :recipients (:recipients block)}
                       {:start-time 70 :duration 10 :remote-item {:feed-guid "g"}}]))))))

(def answers
  "The answers of six services about their own addresses, by URL."
  (json/read-str (fixture "value/lightning-addresses.json")))

(defn- serving
  "A :send that answers the well-known requests as the services did, and a
  callback with an invoice for 50 sats that `preimage` proves."
  [preimage]
  (fn [{:keys [url]}]
    (if (str/includes? url "/.well-known/")
      (let [{:strs [status body]} (get answers url {"status" 404 "body" "Not Found"})]
        {:status status :url url :body body})
      {:status 200 :url url :body (json/write-str {"pr" (invoice preimage)})})))

(deftest checking-the-addresses
  (run #(async/then
         (recipients/check! {:podcast/values [block]
                             :rss/items      [{:podcast/values
                                               [(update block :recipients conj
                                                        {:type "lnaddress" :split 1
                                                         :address "strike@strike.me"})]}]}
                            {:send (serving (apply str (repeat 64 "7")))})
         (fn [{:keys [addresses report]}]
           (is (= #{"hello@getalby.com" "strike@strike.me"} (set (keys addresses)))
               "each address once")
           (is (= 1000 (get-in addresses ["hello@getalby.com" :terms :min-msat])))
           (is (= "696969" (get-in addresses ["hello@getalby.com" :node :custom-key])))
           (is (= [[:value/no-keysend :info [:rss/items 0 :podcast/values 0 :recipients 3]]]
                  (map (juxt :id :level :path) report))
               "Strike names no node for keysend")))))

(deftest paying-the-recipients
  (let [preimage (apply str (repeat 64 "7"))
        sent     (atom [])
        opts     {:keysend-fn   (fn [payment _recipient]
                                  (swap! sent conj [:keysend (:sats payment) (:total-sats payment)])
                                  {:preimage "kept by the sender"})
                  :invoice-fn   (fn [invoice]
                                  (swap! sent conj [:invoice invoice])
                                  {:preimage preimage})
                  :invoice-opts {:send (serving preimage)}
                  :sending-fn   #(swap! sent conj [:sending (:sats %)])}]
    (run (fn []
           (async/then
            (recipients/pay! (:recipients block) 505 {:action :boost :group "b7e1"} opts)
            (fn [[fee host address]]
              (is (= ["b7e1/0" "b7e1/1" "b7e1/2"] (map :id [fee host address]))
                  "the id of each payment in its group")
              (is (= [[:paid 5] [:paid 450] [:paid 50 true]]
                     [((juxt :status :sats) fee)
                      ((juxt :status :sats) host)
                      ((juxt :status :sats :proven?) address)])
                  "the fee off the top, and the rest by the splits")
              (is (= [[:sending 5] [:keysend 5 505] [:sending 450] [:keysend 450 505]
                      [:sending 50] [:invoice (invoice preimage)]]
                     @sent)
                  "one at a time, each recorded before the wallet has it"))))
         (fn []
           (async/then
            (recipients/pay! [{:type "node" :address node-key :split 1 :custom-key "16" :custom-value "x"}
                              {:type "lnaddress" :address "hello@getalby.com" :split 1}
                              {:type "wallet" :address "x" :split 1}
                              {:type "node" :address node-key :split 0}]
                             3 {} (dissoc opts :invoice-fn))
            (fn [entries]
              (is (= [[:skipped :value/custom-record] [:skipped :value/no-method]
                      [:skipped :value/no-method] [:skipped nil]]
                     (map (juxt :status (comp :id :error)) entries))))))
         (fn []
           (async/then
            (recipients/pay! [{:type "node" :address node-key :split 1}] 10 {}
                             {:keysend-fn (fn [_ _] (throw (ex-info "The wallet is gone" {})))})
            (fn [[entry]]
              (is (= [:failed :value/wallet-unreadable "The wallet is gone"]
                     ((juxt :status (comp :id :error) (comp :reason :error)) entry)))))))))
