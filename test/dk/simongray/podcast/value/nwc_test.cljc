(ns dk.simongray.podcast.value.nwc-test
  "Nostr Wallet Connect: the connection URI, the commands, the events of
  a request and an answer, and a command that goes to a wallet, on both
  platforms. The wallet of the tests is made of the functions of
  podcast-clj. On the JVM, a command also goes over a relay on localhost."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.crypto.nip44 :as nip44]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.test-support :refer [invoice run secret-key #?(:clj with-relay)]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.nostr :as nostr]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.nwc :as nwc]))

(def example
  "The connection URI of the example of NIP-47."
  (str "nostr+walletconnect://b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4"
       "?relay=wss%3A%2F%2Frelay.damus.io"
       "&secret=71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"))

(def wallet-secret (secret-key 9))
(def wallet (secp256k1/public-key wallet-secret))

(def connection
  "A connection to the wallet of the tests, over two relays."
  {:wallet wallet
   :relays ["wss://relay.one" "wss://relay.two"]
   :secret (secret/hide (secret-key 7))})

(def at #inst "2026-09-29T12:00:00Z")

(def hex-7 (apply str (repeat 64 "7")))
(def hex-8 (apply str (repeat 64 "8")))

(def recipient
  {:type "node" :name "Ann" :address (apply str "03" (repeat 64 "a")) :custom-key "696969" :custom-value "22" :split 100})

(deftest the-uri-of-a-connection
  (testing "the example of NIP-47"
    (is (= {:wallet "b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4"
            :relays ["wss://relay.damus.io"]
            :secret (secret/hide "71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c")}
           (nwc/parse example)))
    (is (nwc/connection? (nwc/parse example)))
    (is (= example (nwc/format (nwc/parse example)))))
  (testing "a connection that is printed does not show its secret"
    (let [parsed (nwc/parse example)]
      (doseq [written [(pr-str parsed) (str parsed) (pr-str [parsed]) (pr-str (ex-info "failed" {:connection parsed}))]]
        (is (not (re-find #"71a8|4f3c" written)))
        (is (str/includes? written "#<secret>")))
      (is (str/includes? (pr-str parsed) "b889ff5b") "the public key of the wallet shows")
      (is (secret/hidden? (:secret parsed)))))
  (testing "a connection that an app makes of its own parts, with a plain secret"
    (let [plain (assoc connection :secret (secret-key 7))]
      (is (nwc/connection? plain))
      (is (= (nwc/public-key connection) (nwc/public-key plain)))
      (is (= (nwc/format connection) (nwc/format plain)))))
  (testing "more than one relay, a Lightning address, and the forms that wallets write"
    (let [many (str "nostr+walletconnect:" (str/upper-case wallet)
                    "?relay=wss://relay.one/&relay=WSS%3A%2F%2FRelay.Two&relay=https://no.relay&relay=wss://relay.one"
                    "&lud16=ann%40example.org&secret=" (str/upper-case (secret-key 7)) "&other=1")]
      (is (= (assoc connection :lud16 "ann@example.org") (nwc/parse (str "  " many "\n"))))
      (is (= (assoc connection :lud16 "ann@example.org")
             (nwc/parse (nwc/format (assoc connection :lud16 "ann@example.org")))))))
  (testing "what is no connection"
    (doseq [uri [nil 5 "" "nostr+walletconnect://" (str/replace example "nostr+walletconnect" "https")
                 (str/replace example #"&secret=.*" "")
                 (str/replace example #"secret=.*" (str "secret=" (apply str (repeat 64 "0"))))
                 (str/replace example #"secret=.*" "secret=abc")
                 (str/replace example "relay=wss" "relay=https")
                 (str/replace example "b889" "b88")
                 (str "nostr:" example)]]
      (is (nil? (nwc/parse uri)) (pr-str uri)))
    (is (nil? (nwc/parse (str/replace example #"//[0-9a-f]{64}" (str "//" (apply str (repeat 64 "f"))))))
        "a wallet whose key is no point of the curve")
    (doseq [x [nil {} (dissoc connection :relays) (assoc connection :relays []) (assoc connection :relays ["https://x"])
               (assoc connection :secret "abc") (assoc connection :wallet "abc")
               (assoc connection :wallet (apply str (repeat 64 "f")))
               (update connection :wallet str/upper-case)]]
      (is (not (nwc/connection? x)) (pr-str (dissoc x :secret)))))
  (is (= (secp256k1/public-key (secret-key 7)) (nwc/public-key connection))))

(deftest the-commands
  (is (= {:method "pay_invoice" :params {"invoice" "lnbc500n1x"}} (nwc/pay-invoice "lnbc500n1x")))
  (is (= {:method "pay_invoice" :params {"invoice" "lnbc1x" "amount" 50000 "metadata" {"comment" "Thanks"}}}
         (nwc/pay-invoice "lnbc1x" {:msat 50000 :metadata {"comment" "Thanks"}})))
  (is (= {:method "make_invoice" :params {"amount" 50000 "description" "A boost" "expiry" 600}}
         (nwc/make-invoice 50000 {:description "A boost" :expiry 600})))
  (is (= {:method "lookup_invoice" :params {"payment_hash" "31af"}} (nwc/lookup-invoice {:payment-hash "31af"})))
  (is (= {:method "lookup_invoice" :params {"invoice" "lnbc1x"}} (nwc/lookup-invoice {:invoice "lnbc1x"})))
  (is (= {:method "get_balance" :params {}} (nwc/get-balance)))
  (is (= {:method "get_info" :params {}} (nwc/get-info)))
  (testing "a keysend payment, with the records of a boost"
    (let [payment {:action :boost :sats 50 :message "Great episode" :app-name "My app"}
          command (nwc/pay-keysend payment recipient)
          records (get-in command [:params "tlv_records"])
          text    (fn [hex] (let [b (bytes/from-hex hex)] (bytes/decode b 0 (bytes/length b) :utf8)))]
      (is (= {:method "pay_keysend" :params {"amount" 50000 "pubkey" (:address recipient)}}
             (update command :params dissoc "tlv_records")))
      (is (= [696969 7629169] (map #(get % "type") records)))
      (is (= "22" (text (get (first records) "value"))))
      (is (= (value/boostagram (assoc payment :recipient-name "Ann"))
             (json/read-str (text (get (second records) "value")))))
      (is (= [] (value/report command)))
      (is (= [:value/custom-record]
             (map :id (value/report (nwc/pay-keysend payment (assoc recipient :custom-key "7629169")))))
          "the records leave out a key that the payment cannot carry")))
  (testing "what keysend does not pay"
    (is (nil? (nwc/pay-keysend {:sats 50} {:type "lnaddress" :address "ann@example.org"})))
    (is (nil? (nwc/pay-keysend {:sats 50} {:type "node" :address "03abc"})))
    (is (nil? (nwc/pay-keysend {:message "no amount"} recipient)))
    (is (nil? (nwc/pay-keysend {:sats 0} recipient)))))

(defn- opened
  "The command in the request event `event`, as the wallet reads it."
  [event]
  (json/read-str (nip44/decrypt (get event "content") (nip44/conversation-key wallet-secret (get event "pubkey")))))

(defn- reply
  "The event of the wallet that answers the request event `event` with
  `body`."
  [event body]
  (nostr/signed {"kind"       23195
                 "created_at" 1790000001
                 "tags"       [["p" (get event "pubkey")] ["e" (get event "id")]]
                 "content"    (nip44/encrypt (json/write-str body)
                                             (nip44/conversation-key wallet-secret (get event "pubkey"))
                                             hex-8)}
                wallet-secret
                hex-7))

(deftest the-event-of-a-request
  (let [opts  {:at at :nonce hex-7 :aux hex-8}
        event (nwc/request-event connection (nwc/pay-invoice "lnbc500n1x") opts)]
    (is (nostr/verified? event))
    (is (= {"kind" 23194 "created_at" 1790683200 "pubkey" (nwc/public-key connection)
            "tags" [["encryption" "nip44_v2"] ["p" wallet]]}
           (select-keys event ["kind" "created_at" "pubkey" "tags"])))
    (is (= {"method" "pay_invoice" "params" {"invoice" "lnbc500n1x"}} (opened event)))
    (is (not (str/includes? (get event "content") "lnbc")) "the relay does not read the command")
    (is (= event (nwc/request-event connection (nwc/pay-invoice "lnbc500n1x") opts)))
    (is (= ["expiration" "1790683260"]
           (last (get (nwc/request-event connection (nwc/get-balance) (assoc opts :expires-at #inst "2026-09-29T12:01:00Z")) "tags"))))
    (is (nil? (nwc/request-event connection (nwc/pay-invoice (apply str (repeat 70000 "a"))) opts))
        "a command with more bytes than a payload holds")
    (doseq [[c command o] [[nil (nwc/get-balance) opts] [connection {} opts] [connection (nwc/get-balance) (dissoc opts :at)]
                           [connection (nwc/get-balance) (dissoc opts :nonce)]]]
      (is (thrown? #?(:clj Throwable :cljs js/Error) (nwc/request-event c command o))))))

(deftest the-event-of-an-answer
  (let [event (nwc/request-event connection (nwc/pay-invoice "lnbc500n1x") {:at at :nonce hex-7 :aux hex-8})
        id    (get event "id")]
    (testing "a result, in the keys of podcast-clj"
      (is (= {:method "pay_invoice" :request id :preimage "0123" :fees-msat 1000
              :result {"preimage" "0123" "fees_paid" 1000}}
             (nwc/answer connection (reply event {"result_type" "pay_invoice" "error" nil
                                                  "result"      {"preimage" "0123" "fees_paid" 1000}}))))
      (is (= {:method "get_balance" :request id :msat 10000 :result {"balance" 10000}}
             (nwc/answer connection (reply event {"result_type" "get_balance" "result" {"balance" 10000}}))))
      (is (= {:type "incoming" :state "settled" :invoice "lnbc1x" :payment-hash "31af" :msat 123 :fees-msat 0
              :created-at #inst "2026-09-29T12:00:00Z" :settled-at #inst "2026-09-29T12:00:05Z"
              :metadata {"comment" "Thanks"}}
             (dissoc (nwc/answer connection
                                 (reply event {"result_type" "lookup_invoice"
                                               "result"      {"type" "incoming" "state" "settled" "invoice" "lnbc1x"
                                                              "payment_hash" "31af" "amount" 123 "fees_paid" 0
                                                              "created_at" 1790683200 "settled_at" 1790683205
                                                              "expires_at" "soon" "preimage" 5
                                                              "metadata" {"comment" "Thanks"} "other" 1}}))
                     :method :request :result))
          "a field of another type is left out")
      (is (= {:alias "Ann's wallet" :network "mainnet" :block-height 900000 :methods ["pay_invoice" "get_info"]}
             (dissoc (nwc/answer connection
                                 (reply event {"result_type" "get_info"
                                               "result"      {"alias" "Ann's wallet" "network" "mainnet" "block_height" 900000
                                                              "methods" ["pay_invoice" "get_info"]}}))
                     :method :request :result))))
    (testing "an error of the wallet"
      (is (= {:id :value/wallet-refused :level :warning :code "INSUFFICIENT_BALANCE" :reason "The wallet is empty"
              :method "pay_invoice" :request id}
             (dissoc (:error (nwc/answer connection
                                         (reply event {"result_type" "pay_invoice" "result" nil
                                                       "error"       {"code" "INSUFFICIENT_BALANCE" "message" "The wallet is empty"}})))
                     :message))))
    (testing "an event that is no answer of the wallet"
      (let [good   (reply event {"result_type" "pay_invoice" "result" {"preimage" "0123"}})
            other  (nostr/signed (select-keys good ["kind" "created_at" "tags" "content"]) (secret-key 3) hex-7)
            reason #(:reason (:error (nwc/answer connection %)))]
        (is (= :value/wallet-unreadable (:id (:error (nwc/answer connection other)))))
        (is (= :error (:level (:error (nwc/answer connection other)))))
        (is (re-find #"not from the wallet" (reason other)))
        (is (re-find #"wrong id or a wrong signature" (reason (assoc good "content" (get other "content") "created_at" 5))))
        (is (re-find #"wrong id or a wrong signature" (reason nil)))
        (is (re-find #"no response of NIP-47"
                     (reason (nostr/signed (assoc (select-keys good ["created_at" "tags" "content"]) "kind" 1) wallet-secret hex-7))))
        (is (re-find #"not encrypted for this connection"
                     (reason (nostr/signed (assoc (select-keys good ["kind" "created_at" "tags"])
                                                  "content" (nip44/encrypt "{}" (nip44/conversation-key wallet-secret wallet) hex-8))
                                           wallet-secret hex-7))))
        (is (re-find #"not encrypted with NIP-44"
                     (reason (nostr/signed (assoc (select-keys good ["kind" "created_at" "tags"]) "content" "abc?iv=def")
                                           wallet-secret hex-7))))
        (is (re-find #"no JSON object" (reason (reply event [1 2]))))
        (is (re-find #"no result and no error" (reason (reply event {"result_type" "pay_invoice"}))))))))

(deftest the-info-of-a-wallet
  (is (= {:methods ["pay_invoice" "get_balance" "make_invoice"] :encryption ["nip44_v2" "nip04"] :extensions ["02" "04"]}
         (nwc/info {"content" " pay_invoice get_balance  make_invoice"
                    "tags"    [["encryption" "nip44_v2 nip04"] ["extensions" "02 04"]]})))
  (is (= {:methods ["pay_invoice"] :encryption ["nip04"] :extensions []}
         (nwc/info {"content" "pay_invoice" "tags" []}))
      "a wallet that names no encryption reads that of NIP-04 only")
  (let [info (nwc/info {"content" "pay_invoice get_balance" "tags" [["encryption" "nip44_v2"]]})]
    (is (nil? (nwc/unsupported info (nwc/get-balance))))
    (is (nil? (nwc/unsupported nil (nwc/get-balance))) "an app that does not know the wallet")
    (is (= {:id :value/wallet-unsupported :level :warning :method "pay_keysend" :methods ["pay_invoice" "get_balance"]}
           (dissoc (:error (nwc/unsupported info (nwc/pay-keysend {:sats 50} recipient))) :message)))
    (is (= {:id :value/wallet-unsupported :level :warning :encryption ["nip04"]}
           (dissoc (:error (nwc/unsupported (assoc info :encryption ["nip04"]) (nwc/get-balance))) :message)))))

(def info-event
  "The info event of the wallet of the tests."
  (nostr/signed {"kind" 13194 "created_at" 1780000000 "tags" [["encryption" "nip44_v2 nip04"] ["extensions" "04"]]
                 "content" "pay_invoice pay_keysend get_balance"}
                wallet-secret
                hex-7))

(defn- service
  "A send function that is the relays and the wallet of the tests. `log`
  gets each request. The relay at `url` behaves as `(relays url)` tells:

  - :down gives no connection
  - :closing refuses the subscription, so the event does not go out
  - :refusing takes no event
  - :silent takes the event, and has no answer
  - :dropping takes the event, and then the connection fails
  - :empty has no info event

  The wallet answers the command of an event with `(results command)`."
  [log relays results]
  (fn [{:keys [url event] :as request}]
    (swap! log conj request)
    (let [mode (get relays url :up)]
      (cond
        (= :down mode)
        (throw (ex-info (str "The relay gave no connection: " url) {:type ::nostr/failed :url url}))

        (nil? event)
        {:url url :complete? true :events (if (= :empty mode) [] [info-event])}

        (= :closing mode)
        {:url url :events [] :closed "auth-required: tell who you are"}

        (= :refusing mode)
        {:url url :events [] :sent? true :refused "blocked: not on the list"}

        (= :silent mode)
        {:url url :events [] :sent? true :accepted? true :timeout? true}

        (= :dropping mode)
        (throw (ex-info (str "The connection to the relay failed: " url) {:type ::nostr/failed :url url :sent? true}))

        :else
        {:url url :sent? true :accepted? true :events [(reply event (results (opened event)))]}))))

(def ask-opts
  "The clock and the random bytes of the tests."
  {:now    (constantly at)
   :random (fn [n] (bytes/of (repeat n 7)))})

(deftest a-command-for-the-wallet
  (let [paid    (fn [{:strs [method]}] {"result_type" method "result" {"preimage" "0123" "fees_paid" 1000}})
        log     (atom [])
        asked   (fn [relays results opts]
                  (reset! log [])
                  (nwc/ask! connection (nwc/pay-invoice "lnbc500n1x") (merge ask-opts {:send (service log relays results)} opts)))
        sent    (fn [] (map (juxt :url #(if (:event %) :event :info)) @log))
        problem (fn [read] (dissoc (:error read) :message))]
    (run (fn []
           (async/then (asked {} paid {})
                       (fn [read]
                         (testing "the info of the wallet, and then the command"
                           (is (= {:method "pay_invoice" :preimage "0123" :fees-msat 1000 :proven? false}
                                  (select-keys read [:method :preimage :fees-msat :proven?])))
                           (is (= [["wss://relay.one" :info] ["wss://relay.one" :event]] (sent)))
                           (let [{:keys [event filters timeout-seconds allow?]} (last @log)]
                             (is (= (get event "id") (:request read)))
                             (is (nostr/verified? event))
                             (is (= [{"kinds" [23195] "authors" [wallet] "#e" [(get event "id")]}] filters))
                             (is (= ["expiration" "1790683260"] (last (get event "tags"))) "the request expires after the time of the limits")
                             (is (= 60 timeout-seconds))
                             (is (= nostr/public? allow?)))))))
         (fn []
           (async/then (asked {} paid {:info (nwc/info info-event) :timeout-seconds 10 :allow? any?})
                       (fn [read]
                         (testing "an app that knows the wallet sends the command only"
                           (is (= "0123" (:preimage read)))
                           (is (= [["wss://relay.one" :event]] (sent)))
                           (is (= 10 (:timeout-seconds (last @log))))
                           (is (= any? (:allow? (last @log))))))))
         (fn []
           (async/then (asked {"wss://relay.one" :down} paid {})
                       (fn [read]
                         (testing "the next relay, when a relay gives no connection"
                           (is (= "0123" (:preimage read)))
                           (is (= [["wss://relay.one" :info] ["wss://relay.two" :info]
                                   ["wss://relay.one" :event] ["wss://relay.two" :event]]
                                  (sent)))))))
         (fn []
           (async/then (asked {"wss://relay.one" :refusing} paid {:info nil})
                       (fn [read]
                         (testing "the next relay, when a relay does not take the event"
                           (is (= "0123" (:preimage read)))
                           (is (= [["wss://relay.one" :event] ["wss://relay.two" :event]] (sent)))
                           (is (= (:event (first @log)) (:event (second @log))) "the same event, so the wallet gets one command")))))
         (fn []
           (async/then (asked {"wss://relay.one" :down "wss://relay.two" :refusing} paid {:info nil})
                       (fn [read]
                         (testing "no relay takes the request"
                           (is (= {:id :value/wallet-unreachable :level :warning
                                   :relays [{:url "wss://relay.one" :reason "The relay gave no connection: wss://relay.one" :answered? false}
                                            {:url "wss://relay.two" :reason "blocked: not on the list" :answered? true}]}
                                  (problem read)))))))
         (fn []
           (async/then (asked {"wss://relay.one" :closing} paid {:info nil})
                       (fn [read]
                         (testing "the next relay, when a relay refuses the subscription, so that the event did not go out"
                           (is (= "0123" (:preimage read)))
                           (is (= [["wss://relay.one" :event] ["wss://relay.two" :event]] (sent)))))))
         (fn []
           (async/then (asked {"wss://relay.one" :silent} paid {:info nil})
                       (fn [read]
                         (testing "a wallet that gives no answer in time"
                           (is (= {:id :value/wallet-silent :level :error :expires-at #inst "2026-09-29T12:01:00Z"}
                                  (dissoc (problem read) :request)))
                           (is (= (get (:event (first @log)) "id") (:request (:error read))))
                           (is (= [["wss://relay.one" :event]] (sent)) "the request went out, so no other relay gets it")))))
         (fn []
           (async/then (asked {"wss://relay.one" :dropping} paid {:info nil})
                       (fn [read]
                         (testing "a connection that fails after the event went out"
                           (is (= :value/wallet-silent (:id (:error read))))
                           (is (= [["wss://relay.one" :event]] (sent)) "the wallet can have the request, so no other relay gets it")))))
         (fn []
           (let [preimage (apply str (repeat 64 "7"))
                 results  (fn [{:strs [method]}] {"result_type" method "result" {"preimage" preimage}})
                 opts     (merge ask-opts {:send (service log {} results) :info nil})]
             (async/then (async/all [(nwc/ask! connection (nwc/pay-invoice (invoice preimage)) opts)
                                     (nwc/ask! connection (nwc/pay-invoice (invoice (apply str (repeat 64 "8")))) opts)
                                     (nwc/ask! connection (nwc/pay-keysend {:sats 50} recipient) opts)])
                         (fn [[proven other keysend]]
                           (testing "the proof of a payment"
                             (is (true? (:proven? proven)))
                             (is (false? (:proven? other)) "the preimage of another invoice")
                             (is (= preimage (:preimage other)) "the answer is there, so the app does not pay again")
                             (is (= preimage (:preimage keysend)))
                             (is (not (contains? keysend :proven?)) "a keysend payment has no proof"))))))
         (fn []
           (async/then (asked {} (fn [_] {"result_type" "pay_invoice" "result" {"fees_paid" 1000}}) {:info nil})
                       (fn [read]
                         (testing "the result of a payment without a preimage is no payment"
                           (is (= :value/wallet-unreadable (:id (:error read))))
                           (is (re-find #"no preimage" (:reason (:error read))))))))
         (fn []
           (reset! log [])
           (async/then (nwc/ask! connection
                                 (nwc/pay-keysend {:sats 50 :item-title (apply str (repeat 70000 "a"))} recipient)
                                 (merge ask-opts {:send (service log {} paid) :info nil}))
                       (fn [read]
                         (testing "a command that a feed made too large"
                           (is (= {:id :value/command-too-large :level :warning :method "pay_keysend"} (problem read)))
                           (is (empty? @log) "no request went out")))))
         (fn []
           (async/then (asked {} (fn [_] {"result_type" "pay_invoice" "result" nil
                                          "error"       {"code" "QUOTA_EXCEEDED" "message" "The budget of this month is spent"}})
                              {:info nil})
                       (fn [read]
                         (is (= {:id :value/wallet-refused :level :warning :code "QUOTA_EXCEEDED"
                                 :reason "The budget of this month is spent" :method "pay_invoice"}
                                (dissoc (problem read) :request))))))
         (fn []
           (async/then (asked {} (fn [_] {"result_type" "get_balance" "result" {"balance" 5}}) {:info nil})
                       (fn [read]
                         (testing "the answer to another command"
                           (is (= :value/wallet-unreadable (:id (:error read))))))))
         (fn []
           (async/then (asked {"wss://relay.one" :empty "wss://relay.two" :empty} paid {})
                       (fn [read]
                         (testing "a wallet without an info event gets the command"
                           (is (= "0123" (:preimage read)))
                           (is (= [:info :info :event] (map second (sent))))))))
         (fn []
           (async/then (nwc/fetch-info! connection (merge ask-opts {:send (service log {"wss://relay.one" :empty "wss://relay.two" :empty} paid)}))
                       (fn [read]
                         (is (= {:id :value/no-wallet-info :level :info} (select-keys (:error read) [:id :level]))))))
         (fn []
           (async/then (nwc/fetch-info! connection (merge ask-opts {:send (service log {"wss://relay.one" :down "wss://relay.two" :down} paid)}))
                       (fn [read]
                         (is (= :value/wallet-unreachable (:id (:error read)))))))
         (fn []
           (async/then (nwc/fetch-info! connection (merge ask-opts {:send (service log {"wss://relay.one" :empty} paid)}))
                       (fn [read]
                         (is (= {:methods ["pay_invoice" "pay_keysend" "get_balance"] :encryption ["nip44_v2" "nip04"] :extensions ["04"]}
                                read)
                             "the info of the second relay"))))
         (fn []
           (reset! log [])
           (async/then (nwc/ask! connection (nwc/make-invoice 1000) (merge ask-opts {:send (service log {} paid)}))
                       (fn [read]
                         (testing "a command that the wallet does not take"
                           (is (= {:id :value/wallet-unsupported :level :warning :method "make_invoice"
                                   :methods ["pay_invoice" "pay_keysend" "get_balance"]}
                                  (problem read)))
                           (is (= [:info] (map second (sent))) "no request went out"))))))))

#?(:clj
   (deftest a-command-over-a-relay
     (let [log    (atom [])
           eose   (json/write-str ["EOSE" nostr/subscription])
           answer (fn [x]
                    (cond
                      (and (vector? x) (= [13194] (get (first x) "kinds")))
                      [(json/write-str ["EVENT" nostr/subscription info-event]) eose]

                      (vector? x)
                      [eose]

                      :else
                      (let [{:strs [method params]} (opened x)]
                        [(json/write-str ["OK" (get x "id") true ""])
                         (json/write-str ["EVENT" nostr/subscription
                                          (reply x {"result_type" method
                                                    "result"      (case method
                                                                    "get_balance" {"balance" 21000}
                                                                    "pay_keysend" {"preimage" (get params "pubkey") "fees_paid" 0})})])])))]
       (with-relay answer log
         (fn [url]
           (let [connection (assoc connection :relays ["ws://127.0.0.1:1" url])
                 opts       {:allow? (constantly true) :timeout-seconds 5}
                 balance    (nwc/ask! connection (nwc/get-balance) opts)
                 paid       (nwc/ask! connection (nwc/pay-keysend {:sats 50 :message "Hi"} recipient) opts)]
             (is (= {:method "get_balance" :msat 21000}
                    (select-keys balance [:method :msat]))
                 (pr-str balance))
             (is (= {:method "pay_keysend" :preimage (:address recipient) :fees-msat 0}
                    (select-keys paid [:method :preimage :fees-msat]))
                 (pr-str paid))
             (is (= :value/wallet-unreachable
                    (:id (:error (nwc/ask! connection (nwc/get-balance) {:timeout-seconds 5}))))
                 "the relays on the own network get nothing, unless the app allows them")))))))
