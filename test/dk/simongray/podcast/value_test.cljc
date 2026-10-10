(ns dk.simongray.podcast.value-test
  "The records of payments and the payment of Lightning addresses: the
  bLIP-10 records that Podverse, Castamatic, Fountain and Breez write, read
  into one shape with what the reader forgave in the report, and the round
  trip of a payment through its record; the answers that Alby, Fountain,
  Wallet of Satoshi, Strike, Primal and Coinos gave about their own
  addresses, the invoices of BOLT 11, and the RSS Payment Metadata; and on
  the JVM, random records, which the reader reads without throwing."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.test-support :refer [fixture invoice run]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.bolt11 :as bolt11]
            [dk.simongray.podcast.value.lnaddress :as lnaddress]
            [dk.simongray.podcast.value.metadata :as metadata]
            [dk.simongray.podcast.value.payment :as value.payment]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(def records
  "The records of the examples of bLIP-10, by app and action."
  (json/read-str (fixture "value/blip-0010.json")))

(defn- summary
  "The keys of `payment` that tell the apps apart, and the ids of its
  report."
  [payment]
  [(select-keys payment [:action :msat :total-msat :position :item-id :item-guid :feed-id :speed])
   (mapv :id (value/report payment))])

(deftest the-records-that-apps-write
  (is (= [{:action :boost :position 33 :feed-id 920666} []]
         (summary (value/parse-boostagram (get records "spec-simple")))))
  (is (= [{:action :boost :total-msat 100000 :position 15 :item-id 14934154309 :feed-id 6015671}
          [:value/text-for-number]]
         (summary (value/parse-boostagram (get records "fountain-boost"))))
      "Fountain writes the itemID as text")
  (is (= [{:action :boost :total-msat 100000 :position 24 :feed-id 6015671
           :item-guid "12b4df54-af38-4c53-8099-82f9caacdcd5"}
          [:value/guid-as-item-id]]
         (summary (value/parse-boostagram (get records "breez-boost"))))
      "Breez writes the guid of the episode as its itemID")
  (is (= [{:action :stream :total-msat 100000 :position 315 :feed-id 6015671 :speed 1
           :item-guid "12b4df54-af38-4c53-8099-82f9caacdcd5"}
          [:value/unknown-keys :value/action-alias]]
         (summary (value/parse-boostagram (get records "podverse-stream"))))
      "Podverse writes streaming, and a pubkey that bLIP-10 does not define")
  (is (= "podverse-pubkey" (get (value/parse-boostagram (get records "podverse-stream")) "pubkey"))
      "a field that the format does not define keeps its name")
  (is (= [{:action :boost :msat 95049 :total-msat 100000 :position 0 :feed-id 6015671
           :item-guid "12b4df54-af38-4c53-8099-82f9caacdcd5"}
          []]
         (summary (value/parse-boostagram (get records "castamatic-boost")))))
  (let [fountain (value/parse-boostagram (get records "fountain-stream"))]
    (is (not (contains? fountain :message)) "a message of null is no message")
    (is (= {:app-name "Fountain" :feed-title "Test Podcast Anchor" :sender-name "@moritz_conshax"
            :recipient-name "Alby Test User PUT"}
           (select-keys fountain [:app-name :feed-title :sender-name :recipient-name]))))
  (is (not (contains? (value/parse-boostagram (get records "breez-stream")) :sender-name))
      "a blank name is no name"))

(deftest a-payment-through-its-record
  (let [payment {:action :boost :msat 25000 :total-msat 100000 :message "Tak for en god episode 🎙️"
                 :position 754 :speed 1.5 :feed-guid "917393e3" :feed-title "Podcasting 2.0"
                 :feed-id 920666 :item-guid "PC2084" :item-title "Episode 84" :item-id 12
                 :app-name "podcast-clj" :sender-name "Ann" :recipient-name "Host" :id "u1" :group "g1"
                 :boost-link "https://example.com/e/84" :reply-address "ann@example.com"
                 :reply-custom-key 696969 :reply-custom-value "abc"}
        record  (value/boostagram payment)]
    (is (= {"guid" "917393e3" "podcast" "Podcasting 2.0" "episode_guid" "PC2084" "episode" "Episode 84"
            "name" "Host" "uuid" "u1" "boost_uuid" "g1" "value_msat" 25000 "value_msat_total" 100000
            "ts" 754 "time" "00:12:34" "speed" "1.5"}
           (select-keys record ["guid" "podcast" "episode_guid" "episode" "name" "uuid" "boost_uuid"
                                "value_msat" "value_msat_total" "ts" "time" "speed"])))
    (is (= payment (value/parse-boostagram (json/write-str record))))
    (is (= payment (value/parse-boostagram (bytes/encode (json/write-str record))))
        "the UTF-8 bytes that a node gives")
    (is (= [] (value/report (value/parse-boostagram record)))))
  (testing "sats in place of millisatoshis"
    (is (= {"action" "stream" "value_msat" 10000 "value_msat_total" 10000} (value/boostagram {:action :stream :sats 10})))
    (is (= 1500 (get (value/boostagram {:msat 1500 :sats 2}) "value_msat")) "millisatoshis win"))
  (testing "values that JSON holds"
    (is (= "2026-09-28T12:00:00Z" (get (value/boostagram {:sent #inst "2026-09-28T12:00:00Z"}) "sent")))
    (is (= "x" (get (value/boostagram {"pubkey" "x"}) "pubkey")) "a string key stays as it is")))

(deftest records-that-read-in-part
  (is (= [{} [:value/not-an-object]] (summary (value/parse-boostagram "not json"))))
  (is (= [{} [:value/not-an-object]] (summary (value/parse-boostagram "[1, 2]"))))
  (is (= [{} [:value/not-an-object]] (summary (value/parse-boostagram 42))))
  (is (= [{:action :boost} [:value/unreadable]]
         (summary (value/parse-boostagram {"action" "boost" "podcast" "P" "feedID" "abc" "ts" {"a" 1}}))))
  (is (= {:keys ["feedID" "ts"]} (select-keys (first (value/report (value/parse-boostagram {"podcast" "P" "feedID" "abc" "ts" {}})))
                                              [:keys])))
  (is (= 62 (:position (value/parse-boostagram {"podcast" "P" "time" "00:01:02"}))) "time without ts")
  (is (= 12 (:position (value/parse-boostagram {"podcast" "P" "ts" 12 "time" "00:01:02"}))) "ts before time")
  (is (= [{} [:value/unknown-action]] (summary (value/parse-boostagram {"action" "Zap" "podcast" "P"})))
      "the text of a stranger becomes no keyword")
  (is (= "zap" (:action (first (value/report (value/parse-boostagram {"action" "Zap" "podcast" "P"}))))))
  (is (= [{:action :boost} [:value/no-podcast]] (summary (value/parse-boostagram {"action" "boost" "message" "hi"}))))
  (is (= {:feed-title "42"} (value/parse-boostagram {"podcast" 42})) "a number counts as text")
  (let [aliased (value/parse-boostagram {"feedId" "920666" "item_title" "Episode 84" "position" 33 "podcast" "P"})]
    (is (= {:feed-id 920666 :item-title "Episode 84" :position 33 :feed-title "P"} aliased)
        "the camelCase of Alby Hub, and the names of metadata that BoostMeBitch writes")
    (is (= [:value/text-for-number :value/field-alias] (map :id (value/report aliased))))
    (is (= ["feedId" "item_title" "position"] (:keys (second (value/report aliased))))))
  (let [rounded (value/parse-boostagram {"podcast" "P" "value_msat" 94999.9999 "value_msat_total" "100000.4"})]
    (is (= [95000 100000] ((juxt :msat :total-msat) rounded)))
    (is (= [:value/rounded] (map :id (value/report rounded)))))
  (let [below (value/parse-boostagram {"podcast" "P" "value_msat" -5 "value_msat_total" "-100" "ts" -3 "feedID" -1})]
    (is (= {:feed-title "P"} below) "an amount, a position or an id below zero is none")
    (is (= [{:id :value/unreadable :keys ["feedID" "ts" "value_msat" "value_msat_total"]}]
           (map #(select-keys % [:id :keys]) (value/report below))))))

(deftest what-the-node-received
  (let [claimed (value/parse-boostagram {"podcast" "P" "value_msat" 25000})]
    (is (= 1000 (:msat (value/as-received claimed {:msat 1000}))) "the amount of the node")
    (is (= [:value/claimed-amount] (map :id (value/report (value/as-received claimed {:msat 1000})))))
    (is (= [] (value/report (value/as-received claimed {:msat 25000})))))
  (let [claimed {:msat 25000 :recipient-address "Ann@Example.com"}]
    (is (= [] (value/report (value/as-received claimed {:msat 25000 :address "ann@example.com"}))))
    (is (= [:value/claimed-recipient] (map :id (value/report (value/as-received claimed {:address "bob@example.com"})))))))

(deftest custom-records-of-keysend
  (let [recipient {:name "Fountain" :type "node" :address "03b6" :split 50
                   :custom-key "906608" :custom-value "01YKnttU2V1QKFwiQqJYYS"}
        records   (value/records {:action :boost :msat 25000 :feed-guid "g"} recipient)]
    (is (= #{7629169 906608} (set (keys records))))
    (is (= "01YKnttU2V1QKFwiQqJYYS" (get records 906608)))
    (is (= {"name" "Fountain" "guid" "g" "action" "boost" "value_msat" 25000 "value_msat_total" 25000}
           (json/read-str (get records 7629169)))
        "the record names the recipient"))
  (is (= [7629169] (keys (value/records {:msat 1000} {:name "A" :custom-key "not a number" :custom-value "1"}))))
  (testing "a custom key that a feed chooses cannot take the place of a record of the protocol"
    (is (= "boost" (get (json/read-str (get (value/records {:msat 1000} {:custom-key "7629169" :custom-value "x"}) 7629169))
                        "action")))
    (is (= [7629169] (keys (value/records {:msat 1000} {:custom-key "5482373484" :custom-value "x"}))))
    (is (= [7629169] (keys (value/records {:msat 1000} {:custom-key "16" :custom-value "x"})))))
  (testing "the report tells when the records leave out the wallet of the recipient"
    (is (= [[] []]
           (map #(value/report (value/records {:msat 1000} %))
                [{:name "A" :custom-key "906608" :custom-value "w"} {:name "A"}])))
    (doseq [recipient [{:custom-key "65535" :custom-value "w"} {:custom-key "0xDD4B0" :custom-value "w"}
                       {:custom-key "7629169" :custom-value "w"} {:custom-key "906608"} {:custom-value "w"}]]
      (is (= [[:value/custom-record :error]]
             (map (juxt :id :level) (value/report (value/records {:msat 1000} recipient))))
          (pr-str recipient)))))

(def answers
  "The answers of six services about their own addresses, by URL, as they
  gave them on 2026-09-28, with their pages of errors cut short."
  (json/read-str (fixture "value/lightning-addresses.json")))

(defn- recorded
  "The recorded answer to `request`, and 404 for a URL that no service
  answered."
  [{:keys [url]}]
  (let [{:strs [status body]} (get answers url {"status" 404 "body" "Not Found"})]
    {:status status :url url :body body}))

(defn- terms-of
  "The terms of the Lightning `address`, as its service answered."
  [address]
  (lnaddress/terms (recorded (lnaddress/lnurlp-request address))))

(def invoice-25
  "An invoice of 25,000 millisatoshis: 250 nano-bitcoin."
  "lnbc250n1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq")

(defn- serving
  "A `:send` that answers the well-known requests as the services did, and
  a callback with the JSON of `answer`, with each URL it is asked for in
  `log`."
  [answer log]
  (fn [{:keys [url] :as request}]
    (swap! log conj url)
    (if (str/includes? url "/.well-known/")
      (recorded request)
      {:status 200 :url url :body (json/write-str answer)})))

(deftest lightning-addresses
  (testing "the parts of an address"
    (is (= {:user "hello" :domain "getalby.com"} (lnaddress/parse " Hello@GetAlby.com ")))
    (is (= {:user "ann+podcast" :domain "bücher.example"} (lnaddress/parse "ann+podcast@bücher.example")))
    (is (= {:user "_" :domain "getalby.com"} (lnaddress/parse "@getalby.com"))
        "the default identifier of a domain, as LUD-16 has it")
    (is (= "https://getalby.com/.well-known/lnurlp/_" (:url (lnaddress/lnurlp-request "@getalby.com"))))
    (is (every? nil? (map lnaddress/parse ["ann@" "ann@a/b" "an n@a.example" "ann@a@b" "@" nil 42])))
    (is (every? nil? (map lnaddress/parse ["ann@127.0.0.1" "ann@169.254.169.254" "ann@[::1]" "ann@localhost"
                                           "ann@nas.local" "ann@wiki.internal" "ann@router.home.arpa" "ann@intranet"]))
        "a domain on the internet, since the feed chooses the host that the app asks")
    (is (every? nil? (map lnaddress/parse ["ann@localhost." "ann@nas.local." "ann@intranet." "ann@127.0.0.1."
                                           "ann@example.com:22" "ann@example.com:443" "ann@example.com.."]))
        "also with a dot at its end, and no port, which a name of the internet does not have")
    (is (= {:user "ann" :domain "example.com"} (lnaddress/parse "ann@Example.com."))
        "the dot that can end a name is left out"))
  (is (= "https://getalby.com/.well-known/lnurlp/hello" (:url (lnaddress/lnurlp-request "hello@getalby.com"))))
  (is (= "https://getalby.com/.well-known/keysend/hello" (:url (lnaddress/keysend-request "hello@getalby.com"))))
  (is (= "http://abc.onion/.well-known/lnurlp/ann" (:url (lnaddress/lnurlp-request "ann@abc.onion")))
      "an onion service answers http, as LUD-01 allows")
  (testing "the terms that six services give"
    (is (= {:min-msat 1000 :max-msat 10000000000 :comment-length 255 :description "Sats for Alby"
            :identifier "hello@getalby.com" :callback "https://getalby.com/lnurlp/hello/callback"}
           (dissoc (terms-of "hello@getalby.com") :metadata)))
    (is (= [500 nil] ((juxt :comment-length :identifier) (terms-of "fountain@fountain.fm")))
        "Fountain leaves out the identifier that LUD-16 asks for")
    (is (= "https://livingroomofsatoshi.com/api/v1/lnurl/payreq/e22243ec-40de-4777-a817-324037f8ff12"
           (:callback (terms-of "walletofsatoshi@walletofsatoshi.com")))
        "Wallet of Satoshi sends no status, and a callback on another host")
    (is (every? :callback (map terms-of ["strike@strike.me" "primal@primal.net" "coinos@coinos.io"]))))
  (testing "a user who is not there"
    (is (= [:value/lnurl-error "invalid username"] ((juxt :id :reason) (:error (terms-of "zz-nobody@fountain.fm")))))
    (is (= [:value/malformed 500] ((juxt :id :status) (:error (terms-of "zz-nobody@coinos.io")))) "Coinos answers text")
    (is (= [:value/malformed 404] ((juxt :id :status) (:error (terms-of "zz-nobody@getalby.com")))) "Alby answers a web page"))
  (testing "the node for keysend"
    (is (= {:type "node" :address "036cba6deb6f2006099ee24c5990227f4119ae9658591d56980149ae8acc8ddc7d"
            :custom-key "696969" :custom-value "22"}
           (lnaddress/node (recorded (lnaddress/keysend-request "hello@getalby.com")))))
    (is (= [:value/no-keysend :value/no-keysend :value/no-keysend :value/no-keysend]
           (for [address ["walletofsatoshi@walletofsatoshi.com" "strike@strike.me" "primal@primal.net" "coinos@coinos.io"]]
             (:id (:error (lnaddress/node (recorded (lnaddress/keysend-request address)))))))
      "Primal answers the lookup with its web app, and status 200")))

(deftest invoices
  (testing "the amount of an invoice, from the examples of BOLT 11"
    (is (= [{:network :bitcoin}
            {:network :bitcoin :msat 250000000}
            {:network :testnet :msat 2000000000}
            {:network :bitcoin :msat 967878534}
            nil nil nil]
           (map bolt11/invoice-amount ["lnbc1pvjluezsp5zyg3" "lnbc2500u1pvjluez" "LNTB20M1PVJLUEZ" "lnbc9678785340p1pwmna7l"
                                       "lnbc2500000001p1pvjluez" "lnBC20m1pvjluez" "an invoice"])))
    (is (every? nil? (map bolt11/invoice-amount ["lnbc250n1pvjluez <b>junk</b>" "lnbc250n1pvjluez\n" "lnbc250n1pvjlbez"
                                                 "lnbc250n1" "lnbc250n1pvjlu" "lnbc0250n1pvjluez" " lnbc250n1pvjluez"]))
        "the data is six or more characters of Bech32 and nothing else, and no amount starts with a zero")
    (is (= [{:network :bitcoin :msat 999999999999999} nil]
           (map bolt11/invoice-amount ["lnbc9999999999999990p1pvjluez" "lnbc9999999999999999n1pvjluez"]))
        "an amount of sixteen digits is an amount, or no invoice, and never an invoice of any amount"))
  (testing "the payment hash of an invoice, from the examples of BOLT 11"
    (let [donation (str "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq"
                        "dpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdya"
                        "fkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql")
          coffee   (str "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq"
                        "dq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09"
                        "sam30g4vgpfna3rh")
          hash     "0001020304050607080900010203040506070809000102030405060708090102"]
      (is (= [hash hash hash] (map bolt11/payment-hash [donation coffee (str/upper-case coffee)])))
      (is (nil? (bolt11/payment-hash (str/replace-first coffee "zyg3" "zyg4"))) "a character that the checksum does not take")
      (is (nil? (bolt11/payment-hash (str donation "q"))))
      (doseq [x [nil 5 "" "an invoice" "lnbc250n1pvjluez" "lnbc1pvjluezsp5zyg3" invoice-25]]
        (is (nil? (bolt11/payment-hash x)) (pr-str x)))))
  (testing "the proof of a payment"
    (let [preimage (apply str (repeat 64 "7"))
          paid     (invoice preimage)]
      (is (= {:network :bitcoin :msat 50000} (bolt11/invoice-amount paid)))
      (is (= (bytes/hex (bytes/sha256 (bytes/from-hex preimage))) (bolt11/payment-hash paid)))
      (is (true? (bolt11/proves? preimage paid)))
      (is (true? (bolt11/proves? (str/upper-case preimage) (str/upper-case paid))))
      (doseq [[x reason] {(apply str (repeat 64 "8")) "another preimage"
                          (subs preimage 2)           "a preimage of 31 bytes"
                          ""                          "no preimage"
                          nil                         "no preimage"
                          "0123"                      "a short text"}]
        (is (false? (bolt11/proves? x paid)) reason))
      (is (false? (bolt11/proves? preimage "lnbc500n1x")) "a text that is no invoice")
      (is (false? (bolt11/proves? preimage (invoice (apply str (repeat 64 "8"))))) "another invoice")))
  (let [coinos (terms-of "coinos@coinos.io")]
    (is (= "https://names.coinos.io/lnurlp/coinos/cb?domain=coinos.io&amount=25000&comment=Tak%20%F0%9F%8E%99%EF%B8%8F"
           (:url (lnaddress/invoice-request coinos 25000 "Tak 🎙️")))
        "the amount and the comment follow the query of the callback")
    (is (= "https://names.coinos.io/lnurlp/coinos/cb?domain=coinos.io&amount=25000&comment=ab"
           (:url (lnaddress/invoice-request (assoc coinos :comment-length 2) 25000 "abc"))))
    (is (= "https://names.coinos.io/lnurlp/coinos/cb?domain=coinos.io&amount=25000"
           (:url (lnaddress/invoice-request (assoc coinos :comment-length 0) 25000 "abc")))
        "no comment where the terms take none")
    (is (thrown? #?(:clj Exception :cljs :default) (lnaddress/invoice-request coinos 999)))
    (is (= [true false false] (map #(lnaddress/sendable? coinos %) [1000 999 1000.5]))))
  (let [alby   (terms-of "hello@getalby.com")
        answer #(lnaddress/invoice alby 25000 {:status 200 :body (json/write-str (merge {"pr" invoice-25 "routes" []} %))})]
    (is (= {:invoice invoice-25 :msat 25000 :network :bitcoin} (answer {})))
    (is (= :value/wrong-amount (:id (:error (lnaddress/invoice alby 26000 {:body (json/write-str {"pr" invoice-25})})))))
    (is (= [:value/wrong-amount nil] ((juxt :id :invoice-msat) (:error (answer {"pr" "lnbc1pvjluez" "status" "OK"}))))
        "an invoice of any amount is not one of the amount requested")
    (is (= :value/malformed (:id (:error (answer {"pr" "an invoice"})))))
    (is (= {:tag "message" :message "Thanks!"} (:success-action (answer {"successAction" {"tag" "message" "message" "Thanks!"}}))))
    (is (= {:tag "url" :url "https://getalby.com/thanks"} (:success-action (answer {"successAction" {"tag" "url" "url" "https://getalby.com/thanks"}}))))
    (is (= {:tag "aes" :ciphertext "abc" :iv "AAAAAAAAAAAAAAAAAAAAAA=="}
           (:success-action (answer {"successAction" {"tag" "aes" "ciphertext" "abc" "iv" "AAAAAAAAAAAAAAAAAAAAAA=="}}))))
    (is (= [:value/unknown-success-action :value/unknown-success-action :value/unknown-success-action]
           (map #(:id (:error (answer {"successAction" %})))
                [{"tag" "url" "url" "https://another.example/thanks"} {"tag" "sound"} {"tag" "aes" "ciphertext" "abc" "iv" "short"}]))
        "a success action on another host, of another kind, or with an iv of another size stops the payment")
    (is (= 144 (count (:message (:success-action (answer {"successAction" {"tag" "message" "message" (apply str (repeat 5000 "a"))}})))))
        "a message is cut to the 144 characters of LUD-09")
    (let [plain {:url "http://getalby.com/lnurlp/hello/callback" :status 200 :body (json/write-str {"pr" invoice-25})}]
      (is (= [:value/insecure "http://getalby.com/lnurlp/hello/callback"]
             ((juxt :id :url) (:error (lnaddress/invoice alby 25000 plain))))
          "an answer that came over plain http, where anyone can change the invoice"))
    (is (= [:value/lnurl-error "Too many requests"]
           ((juxt :id :reason) (:error (answer {"status" "ERROR" "reason" "Too many requests"})))))
    (is (= "https://getalby.com/verify/x" (:verify (answer {"verify" "https://getalby.com/verify/x"}))))))

(deftest paying-an-address
  (run (fn []
         (let [log (atom [])]
           (async/then
            (lnaddress/request-invoice! "hello@getalby.com" 25000 {:send (serving {"pr" invoice-25} log) :comment "Great show"})
            (fn [invoice]
              (is (= invoice-25 (:invoice invoice)))
              (is (= "Great show" (:comment invoice)) "the comment that went out")
              (is (= ["https://getalby.com/.well-known/lnurlp/hello"
                      "https://getalby.com/lnurlp/hello/callback?amount=25000&comment=Great%20show"]
                     @log))))))
       (fn []
         (let [log     (atom [])
               payment {:action :boost :message "Great episode"}
               fitted  #(metadata/format-comment payment "https://app.example/payments/1" %)
               short   (fn [{:keys [url] :as request}]
                         (if (str/includes? url "/.well-known/")
                           (update (recorded request) :body #(json/write-str (assoc (json/read-str %) "commentAllowed" 40)))
                           ((serving {"pr" invoice-25} log) request)))]
           (async/then
            (async/all [(lnaddress/request-invoice! "hello@getalby.com" 25000 {:send short :comment (comp fitted :comment-length)})
                        (lnaddress/request-invoice! "hello@getalby.com" 25000 {:send short :comment (apply str (repeat 50 "a"))})])
            (fn [[none cut]]
              (is (= invoice-25 (:invoice none)))
              (is (not (contains? none :comment))
                  "a comment that does not fit gives no comment, and the invoice shows that none went out")
              (is (= (apply str (repeat 40 "a")) (:comment cut)) "the comment as the terms cut it")
              (is (= #{"https://getalby.com/lnurlp/hello/callback?amount=25000"
                       (str "https://getalby.com/lnurlp/hello/callback?amount=25000&comment=" (apply str (repeat 40 "a")))}
                     (set @log)))))))
       (fn []
         (let [log (atom [])]
           (async/then
            (lnaddress/request-invoice! "hello@getalby.com" 500 {:send (serving {"pr" invoice-25} log)})
            (fn [{:keys [error]}]
              (is (= [:value/amount-out-of-range 1000] ((juxt :id :min-msat) error)))
              (is (= 1 (count @log)) "no invoice is asked for")))))
       #(async/then
         (lnaddress/request-invoice! "hello@getalby.com" 25000 {:send (fn [_] (throw (ex-info "Connection refused" {})))})
         (fn [{:keys [error]}]
           (is (= [:value/unreachable "Connection refused"] ((juxt :id :reason) error)))))
       #(async/then
         (lnaddress/request-invoice! "not an address" 25000 {:send (fn [_] (throw (ex-info "sent" {})))})
         (fn [{:keys [error]}]
           (is (= :value/not-an-address (:id error)))))
       #(async/then
         (lnaddress/fetch-node! "fountain@fountain.fm" {:send recorded})
         (fn [node]
           (is (= ["03b6f613e88bd874177c28c6ad83b3baba43c4c656f56be1f8df84669556054b79" "906608"]
                  ((juxt :address :custom-key) node)))))
       (fn []
         (let [asked (atom [])
               send  (fn [request] (swap! asked conj request) (recorded request))]
           (async/then
            (async/all [(lnaddress/fetch-node! "fountain@fountain.fm" {:send send})
                        (lnaddress/fetch-node! "fountain@fountain.fm" {:send send :allow-pred any? :max-bytes 1000})])
            (fn [_]
              (is (= #{[10 262144 url/public?] [10 1000 any?]}
                     (set (map (juxt :timeout-seconds :max-bytes :allow-pred) @asked)))
                  "each request carries the limits and the guard, which a :send gets too")))))))

(def example-metadata
  "The metadata of the example of the RSS Payment Metadata, with the
  content ids blank."
  {"id" "DBBhBwUise1bcfoHRCC3" "group" "EoL2Qp52RMOSiF1rmGGs" "action" "boost" "split" 0.25
   "message" "Great episode!" "app_name" "Fountain" "app_version" "1.3.10" "sender_id" "IIaS9X2JuH75yvrRcFoL"
   "sender_name" "oscar@fountain.fm" "recipient_name" "merryoscar@primal.net"
   "recipient_address" "merryoscar@primal.net" "value_msat" 25000 "value_msat_total" 100000
   "value_usd" 0.025797 "timestamp" "2025-11-05T15:09:10.174Z" "position" 120 "feed_medium" ""
   "feed_guid" "" "feed_title" "" "item_guid" "" "item_title" "" "publisher_guid" "" "publisher_title" ""
   "remote_feed_guid" "" "remote_item_guid" "" "remote_publisher_guid" ""})

(deftest payment-metadata
  (let [payment (metadata/parse-header (url/percent-encode (json/write-str example-metadata)))]
    (is (= {:id "DBBhBwUise1bcfoHRCC3" :group "EoL2Qp52RMOSiF1rmGGs" :action :boost :split 0.25
            :message "Great episode!" :app-name "Fountain" :app-version "1.3.10" :sender-id "IIaS9X2JuH75yvrRcFoL"
            :sender-name "oscar@fountain.fm" :recipient-name "merryoscar@primal.net"
            :recipient-address "merryoscar@primal.net" :msat 25000 :total-msat 100000 :usd 0.025797
            :timestamp #inst "2025-11-05T15:09:10.174Z" :position 120}
           payment)
        "blank ids are no ids")
    (is (= [:value/no-podcast] (map :id (value/report payment))))
    (is (= payment (metadata/parse-header (json/write-str example-metadata))) "JSON that is not encoded")
    (is (= payment (metadata/parse-header (metadata/format-header payment))) "a round trip"))
  (is (= {"action" "stream" "value_msat" 10000 "value_msat_total" 10000 "timestamp" "2026-09-28T12:00:00Z"}
         (metadata/record {:action :stream :sats 10 :timestamp #inst "2026-09-28T12:00:00Z"})))
  (is (= [{} [:value/not-an-object]] ((juxt identity #(map :id (value/report %))) (metadata/parse-header "%7B"))))
  (testing "the comment of a payment"
    (let [url "https://fountain.fm/show/2JCkApiUwyBj2eOG7JJI?payment=DBBhBwUise1bcfoHRCC3"]
      (is (= (str "rss::payment::boost " url " Great episode!") (metadata/format-comment {:message "Great episode!"} url)))
      (is (= (str "rss::payment::stream " url) (metadata/format-comment {:action :stream :message " "} url 200)))
      (is (= (str "rss::payment::boost " url " Grea…") (metadata/format-comment {:message "Great episode!"} url 100))
          "a message that is cut ends in an ellipsis")
      (is (= (str "rss::payment::boost " url) (metadata/format-comment {:message "Great episode!"} url 96))
          "no room for a character of the message and the ellipsis")
      (is (nil? (metadata/format-comment {:message "Hi"} url 90)) "no room for the URL")
      (is (nil? (metadata/format-comment {:message "Hi"} "ftp://example.com/p" 200)))
      (is (= {:action :boost :url url :message "Great episode!"}
             (metadata/parse-comment (str "rss::payment::boost " url " Great episode!"))))
      (is (= {:action :stream :url url} (metadata/parse-comment (str "rss::payment::stream " url))))
      (is (nil? (metadata/parse-comment "Great episode!")))
      (is (= {:action :stream :url url} (metadata/parse-comment (str "rss::payment::stream " url " undefined")))
          "Fountain wrote undefined for no message")
      (is (= [{:action :boost :url url :message "hi"} {:action :boost :url url :message "hi"} nil nil nil nil]
             (map metadata/parse-comment [(str (char 0x2003) "rss::payment::boost " url " hi" (char 0x3000))
                                          (str "\n rss::payment::boost " url " hi ")
                                          (str "a rss::payment::boost " url)
                                          (str (char 0x2003))
                                          ""
                                          42]))
          "white space of any kind around the comment, which the payer writes")))
  (is (= "ab" (value.payment/cut "ab🎙️" 3)) "a cut leaves no half of a pair of surrogates"))

(deftest fetching-metadata
  (let [url     "https://boostbox.example/boost/01K5"
        payment {:action :boost :msat 25000 :total-msat 100000 :message "Tak" :feed-guid "g"
                 :timestamp #inst "2026-09-28T12:00:00Z"}
        asked   (atom [])
        send    (fn [header]
                  (fn [request]
                    (swap! asked conj request)
                    {:status 200 :url (:url request) :headers (if header {"x-rss-payment" [header]} {}) :body "<p>"}))]
    (run #(async/then
           (metadata/fetch! url {:send (send (metadata/format-header payment))})
           (fn [found]
             (is (= payment found))
             (is (= [0 1] (:range (first @asked))) "two bytes of the page")
             (is (= [10 65536 url/public?] ((juxt :timeout-seconds :max-bytes :allow-pred) (first @asked)))
                 "with the limits and the guard, which a :send gets too")))
         #(async/then
           (metadata/fetch! url
                            {:send (send nil) :max-bytes 1000 :allow-pred any?})
           (fn [_]
             (is (= [1000 any?] ((juxt :max-bytes :allow-pred) (last @asked))) "as the options set them")))
         #(async/then
           (metadata/fetch! "http://127.0.0.1:1/payment")
           (fn [{:keys [error]}]
             (is (= :value/unreachable (:id error)))
             (is (str/includes? (:reason error) "is not allowed") "no request to the network of the host")))
         #(async/then
           (metadata/fetch! url {:send (send nil)})
           (fn [{:keys [error]}]
             (is (= [:value/no-metadata 200] ((juxt :id :status) error)))))
         #(async/then
           (metadata/fetch! nil {:send (send nil)})
           (fn [{:keys [error]}]
             (is (= :value/no-metadata (:id error)))))
         #(async/then
           (metadata/fetch! url {:send (fn [_] (throw (ex-info "Connection refused" {})))})
           (fn [{:keys [error]}]
             (is (= :value/unreachable (:id error))))))))

#?(:clj
   (defspec any-record-reads 300
     (prop/for-all [record (gen/map (gen/one-of [(gen/elements (keys value/boostagram-fields)) gen/string-ascii])
                                    (gen/one-of [gen/string gen/large-integer (gen/double* {:infinite? false :NaN? false})
                                                 gen/boolean (gen/return nil) (gen/vector gen/small-integer)
                                                 (gen/map gen/string-ascii gen/small-integer)
                                                 (gen/fmap str gen/large-integer)]))]
       (let [payment (value/parse-boostagram record)]
         (and (map? payment)
              (vector? (value/report payment))
              (map? (value/boostagram payment)))))))
