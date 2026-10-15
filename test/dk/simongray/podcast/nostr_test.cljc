(ns dk.simongray.podcast.nostr-test
  "Nostr: the id and the signature of an event, the names of NIP-19, the
  filters and the messages of a relay, and the steps of an exchange, on
  both platforms; and on the JVM, an exchange with a relay on localhost."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.nostr.impl :as nostr.impl]
            [dk.simongray.podcast.nostr.secp256k1 :as secp256k1]
            [dk.simongray.podcast.test-support :refer [fixture run secret-key signed #?(:clj with-relay)]]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]))

(def fountain-note
  "A note about an episode, as the app Fountain wrote it."
  (json/read-str (fixture "nostr/fountain-note.json")))

(def ann (secret-key 1))
(def bob (secret-key 2))

(def note
  "A note of Ann, with the characters that NIP-01 escapes, and others."
  (signed ann {:kind    1
               :at      1790000000
               :tags    [["t" "podcast"] ["e" (apply str (repeat 64 "a")) "wss://relay.example" "root"]]
               :content "A \"quote\", a \\ and a tab\t.\nA new line, a bell \u0007, a slash / and Blåbærgrød 🎧"}))

(deftest the-id-and-the-signature-of-an-event
  (testing "an event that another app signed"
    (is (nostr/event? fountain-note))
    (is (= (get fountain-note "id") (nostr/event-id fountain-note)))
    (is (nostr/verified? fountain-note)))
  (testing "the text that the id is the SHA-256 of"
    (is (= (str "[0,\"" (secp256k1/public-key ann) "\",1790000000,1,"
                "[[\"t\",\"podcast\"],[\"e\",\"" (apply str (repeat 64 "a")) "\",\"wss://relay.example\",\"root\"]],"
                "\"A \\\"quote\\\", a \\\\ and a tab\\t.\\nA new line, a bell \u0007, a slash / and Blåbærgrød 🎧\"]")
           (nostr.impl/serialized note)))
    (is (= (get note "id") (bytes/hex (hash/digest :sha-256 (bytes/encode (nostr.impl/serialized note))))))
    (is (nostr/verified? note))
    (is (nostr/verified? (json/read-str (json/write-str note))) "after a trip through JSON"))
  (testing "an event that is not what its author signed"
    (doseq [[change event] {"another content"   (assoc note "content" "Buy my coins")
                            "another time"      (update note "created_at" inc)
                            "another kind"      (assoc note "kind" 1111)
                            "another tag"       (assoc-in note ["tags" 0 1] "spam")
                            "another author"    (assoc note "pubkey" (secp256k1/public-key bob))
                            "another id"        (assoc note "id" (get fountain-note "id"))
                            "another signature" (assoc note "sig" (get fountain-note "sig"))}]
      (is (nostr/event? event) change)
      (is (not (nostr/verified? event)) change)))
  (testing "what has no form of an event"
    (doseq [[change event] {"no content"            (dissoc note "content")
                            "an id in upper case"   (update note "id" str/upper-case)
                            "a short id"            (update note "id" subs 1)
                            "a time with a part"    (assoc note "created_at" 1790000000.5)
                            "a time below zero"     (assoc note "created_at" -1)
                            "a kind above 65535"    (assoc note "kind" 65536)
                            "tags that are a map"   (assoc note "tags" {})
                            "a tag that is a text"  (assoc note "tags" ["t"])
                            "a tag without a name"  (assoc note "tags" [[]])
                            "a tag with a number"   (assoc note "tags" [["t" 5]])
                            "a short signature"     (update note "sig" subs 2)
                            "a text"                "event"
                            "nothing"               nil}]
      (is (not (nostr/event? event)) change)
      (is (not (nostr/verified? event)) change))))

(deftest the-names-of-nip-19
  (let [key "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        id  "5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36"]
    (testing "the examples of NIP-19 and NIP-21"
      (is (= {:type :npub :public-key key}
             (nostr/decode "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")))
      (is (= "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
             (nostr/encode {:type :npub :public-key key})))
      (is (= {:type :nprofile :public-key key :relays ["wss://r.x.com" "wss://djbas.sadkb.com"]}
             (nostr/decode (str "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4"
                                "mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"))))
      (is (= (str "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4"
                  "mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p")
             (nostr/encode {:type :nprofile :public-key key :relays ["wss://r.x.com" "wss://djbas.sadkb.com"]})))
      (is (= {:type   :nevent
              :id     "b9f5441e45ca39179320e0031cfb18e34078673dcc3d3e3a3b3a981760aa5696"
              :relays ["wss://nostr-relay.untethr.me" "wss://nostr-pub.wellorder.net"]}
             (nostr/decode (str "nostr:nevent1qqstna2yrezu5wghjvswqqculvvwxsrcvu7uc0f78gan4xqhvz49d9spr3mhxue69uhkummnw3ez6un9d3"
                                "shjtn4de6x2argwghx6egpr4mhxue69uhkummnw3ez6ur4vgh8wetvd3hhyer9wghxuet5nxnepm"))))
      (is (nil? (nostr/decode "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"))
          "podcast-clj reads no secret key"))
    (testing "each name, there and back"
      (doseq [x [{:type :npub :public-key key}
                 {:type :note :id id}
                 {:type :nprofile :public-key key :relays []}
                 {:type :nevent :id id :relays []}
                 {:type :nevent :id id :relays ["wss://relay.example"] :author key :kind 1111}
                 {:type :naddr :identifier "an-article" :relays ["wss://relay.example"] :author key :kind 30023}
                 {:type :naddr :identifier "" :relays [] :author key :kind 10002}]]
        (is (= x (nostr/decode (nostr/encode x))) (pr-str x))
        (is (= x (nostr/decode (nostr/uri x))) (pr-str x))
        (is (= x (nostr/decode (str "  NOSTR:" (nostr/encode x) "\n"))) (pr-str x))
        (is (str/starts-with? (nostr/uri x) (str "nostr:" (name (:type x)) "1")))))
    (testing "what names nothing"
      (doseq [x [{:type :npub :public-key "abc"} {:type :note :id nil} {:type :nevent} {:type :nsec :secret-key key}
                 {:type :naddr :identifier "x" :author key} {:type :naddr :identifier "x" :kind 1} {}]]
        (is (nil? (nostr/encode x)) (pr-str x))
        (is (nil? (nostr/uri x)) (pr-str x)))
      (doseq [s [nil "" "npub1" "nostr:" "hello" id (str "note1" id) 5
                 (bech32/encode "npub" (bytes/of (range 31)))
                 (bech32/encode "nevent" (bytes/of (concat [0 31] (range 31))))
                 (bech32/encode "nevent" (bytes/of (concat [0 32] (range 31))))
                 (bech32/encode "nprofile" (bytes/of [1 3 119 115 115]))
                 (bech32/encode "lnurl" (bytes/of (range 32)))]]
        (is (nil? (nostr/decode s)) (pr-str s))))
    (testing "a relay with a long URL is left out, and a name has 5,000 characters at most"
      (let [long-url (str "wss://relay.example/" (apply str (repeat 300 "a")))]
        (is (= {:type :nevent :id id :relays ["wss://relay.example"]}
               (nostr/decode (nostr/encode {:type :nevent :id id :relays [long-url "wss://relay.example"]})))))
      (let [relays (mapv #(str "wss://relay" % ".example/" (apply str (repeat 200 "a"))) (range 30))
            text   (nostr/encode {:type :nevent :id id :relays relays})]
        (is (< 5000 (count text)))
        (is (nil? (nostr/decode text)))))))

(deftest the-parts-of-a-content
  (let [key     "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        id      "5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36"
        profile (nostr/encode {:type :nprofile :public-key key :relays ["wss://relay.example"]})
        event   (nostr/encode {:type :nevent :id id :kind 9735})
        article (nostr/encode {:type :naddr :identifier "an-article" :author key :kind 30023})
        parts   #(nostr/parts {"content" %1 "tags" (vec %2)})]
    (is (= [{:type :text :text "Is this "}
            {:type :mention :public-key key :uri "nostr:npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"}
            {:type :text :text " approved? 🤣 "}
            {:type :hashtag :tag "40HPW"}
            {:type :text :text " #other\n\n"}
            {:type :link :url "https://fountain.fm/episode/x"}
            {:type :text :text "\n\n"}
            {:type :reference :target {:type :nevent :id id :kind 9735 :relays []} :uri (str "nostr:" event)}]
           (parts (str "Is this nostr:npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6 approved? 🤣 #40HPW #other\n\n"
                       "https://fountain.fm/episode/x\n\nnostr:" event)
                  [["t" "40hpw"]])))
    (is (= [{:type :mention :public-key key :relays ["wss://relay.example"] :uri (str "nostr:" profile)}
            {:type :text :text ", see "}
            {:type :reference
             :target {:type :naddr :identifier "an-article" :author key :kind 30023 :relays []}
             :uri    (str "nostr:" article)}
            {:type :text :text " ("}
            {:type :link :url "https://example.org/a_(b)?c=d#e"}
            {:type :text :text ")."}]
           (parts (str "nostr:" profile ", see nostr:" article " (https://example.org/a_(b)?c=d#e).") [])))
    (testing "what is text"
      (doseq [text ["" "Plain text" "nostr:npub1abc" "nostr:nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
                    (str "nostr:" (subs profile 0 40)) "#tag without a tag" "ftp://example.org" "npub180cvv07 alone"
                    "javascript:alert(1)" "a\nb\n"]]
        (is (= (if (= "" text) [] [{:type :text :text text}]) (parts text [["t" "other"]])) text))
      (is (= [] (nostr/parts {})))
      (is (= [] (nostr/parts {"content" nil}))))
    (testing "a hashtag in the letters of a language"
      (is (= [{:type :hashtag :tag "Blåbærgrød"} {:type :text :text " "} {:type :hashtag :tag "日本語"}]
             (parts "#Blåbærgrød #日本語" [["t" "blåbærgrød"] ["t" "日本語"]]))))
    (testing "a long content reads in a short time"
      (let [content (apply str (repeat 20000 "nostr:npub1 https:// # "))]
        (is (= content (apply str (map :text (parts content []))))))
      (let [content (apply str (repeat 100000 "#x "))]
        (is (true? (= [{:type :text :text content}] (parts content [["t" "other"]])))
            "a word after a number sign that is no hashtag of the event"))
      (let [content (apply str (repeat 30000 "https://example.org/a, "))]
        (is (= 60000 (count (parts content []))))
        (is (= #{{:type :link :url "https://example.org/a"} {:type :text :text ", "}} (set (parts content []))))))))

(deftest the-filters-of-a-relay
  (let [event (signed ann {:kind 1111
                           :at   1790000000
                           :tags [["I" "podcast:item:guid:abc"] ["K" "podcast:item:guid"]
                                  ["e" (apply str (repeat 64 "b")) "wss://relay.example"] ["k" "1111"]
                                  ["e" (apply str (repeat 64 "c"))] ["t"]]})
        id    (get event "id")
        key   (get event "pubkey")]
    (is (= ["podcast:item:guid:abc"] (nostr/tag-values event "I")))
    (is (= [(apply str (repeat 64 "b")) (apply str (repeat 64 "c"))] (nostr/tag-values event "e")))
    (is (= [] (nostr/tag-values event "t") (nostr/tag-values event "p")))
    (doseq [[filter passes?] [[{} true]
                              [{"ids" [id]} true]
                              [{"ids" [(apply str (repeat 64 "0")) id]} true]
                              [{"ids" [(apply str (repeat 64 "0"))]} false]
                              [{"authors" [key]} true]
                              [{"authors" [(secp256k1/public-key bob)]} false]
                              [{"kinds" [1 1111]} true]
                              [{"kinds" [1]} false]
                              [{"#I" ["podcast:item:guid:abc"]} true]
                              [{"#i" ["podcast:item:guid:abc"]} false]
                              [{"#e" [(apply str (repeat 64 "c")) "x"]} true]
                              [{"#e" ["wss://relay.example"]} false]
                              [{"#p" [key]} false]
                              [{"since" 1790000000 "until" 1790000000} true]
                              [{"since" 1790000001} false]
                              [{"until" 1789999999} false]
                              [{"limit" 0 "search" "podcast"} true]
                              [{"kinds" [1111] "#I" ["podcast:item:guid:abc"] "authors" [key] "limit" 10} true]
                              [{"kinds" [1111] "#I" ["podcast:item:guid:xyz"] "authors" [key]} false]]]
      (is (= passes? (nostr/matches? filter event)) (pr-str filter)))))

(deftest the-messages-of-a-relay
  (is (= ["REQ" "podcast-clj" {"kinds" [1111] "#I" ["podcast:item:guid:abc"] "limit" 100} {"ids" ["x"]}]
         (json/read-str (nostr/request-message nostr/subscription
                                               [{"kinds" [1111] "#I" ["podcast:item:guid:abc"] "limit" 100}
                                                {"ids" ["x"]}]))))
  (is (= ["CLOSE" "podcast-clj"] (json/read-str (nostr/close-message nostr/subscription))))
  (is (= {:type :event :subscription "s" :event note} (nostr/read-message (json/write-str ["EVENT" "s" note]))))
  (is (= {:type :eose :subscription "s"} (nostr/read-message "[\"EOSE\",\"s\"]")))
  (is (= {:type :closed :subscription "s" :message "auth-required: tell who you are"}
         (nostr/read-message "[\"CLOSED\",\"s\",\"auth-required: tell who you are\"]")))
  (is (= {:type :notice :message "slow down"} (nostr/read-message "[\"NOTICE\",\"slow down\"]")))
  (is (= {:type :ok :id "abc" :accepted? false :message "blocked: no"}
         (nostr/read-message "[\"OK\",\"abc\",false,\"blocked: no\"]")))
  (is (= {:type :auth :challenge "xyz"} (nostr/read-message "[\"AUTH\",\"xyz\"]")))
  (doseq [text ["" "EOSE" "{}" "[]" "[5]" "[\"EOSE\"]" "[\"EVENT\",\"s\",\"text\"]" "[\"EVENT\",5,{}]"
                "[\"HELLO\",\"s\"]" "[\"EOSE\",\"s\"" nil]]
    (is (nil? (nostr/read-message text)) (pr-str text))))

(deftest the-steps-of-an-exchange
  (let [event  #(json/write-str ["EVENT" nostr/subscription (assoc note "content" (str %))])
        limits nostr/default-limits
        read   (fn [texts limits] (reduce #(nostr/received %1 %2 limits) {} texts))]
    (testing "events, and then the end of the stored events"
      (let [answer (read [(event 1) "[\"NOTICE\",\"hello\"]" (event 2) (str "[\"EOSE\",\"" nostr/subscription "\"]")] limits)]
        (is (= ["1" "2"] (map #(get % "content") (:events answer))))
        (is (= {:complete? true :done? true :notices ["hello"]} (select-keys answer [:complete? :done? :notices :closed])))
        (is (= answer (nostr/received answer (event 3) limits)) "no message counts after the end")
        (is (= {:url "wss://relay.example" :events (:events answer) :complete? true :notices ["hello"]}
               (nostr/response "wss://relay.example" answer)))))
    (is (= {:url "wss://relay.example" :events []} (nostr/response "wss://relay.example" {})))
    (testing "a relay that closes the subscription"
      (is (= {:done? true :closed "restricted: members only"}
             (select-keys (read [(str "[\"CLOSED\",\"" nostr/subscription "\",\"restricted: members only\"]")] limits)
                          [:done? :closed :events]))))
    (testing "a relay that asks who the reader is"
      (is (= {:auth? true} (select-keys (read ["[\"AUTH\",\"challenge\"]"] limits) [:auth? :done?]))))
    (testing "the messages of another subscription, and texts that are no message"
      (is (= {}
             (dissoc (read ["[\"EOSE\",\"other\"]" "[\"CLOSED\",\"other\",\"error: x\"]" "hello" "[\"OK\",\"a\",true,\"\"]"]
                           limits)
                     :bytes)))
      (is (nil? (:events (read [(json/write-str ["EVENT" "other" note])] limits)))))
    (testing "the limits"
      (let [answer (read (map event (range 5)) (assoc limits :max-events 2))]
        (is (= {:done? true :truncated :max-events} (select-keys answer [:done? :truncated :complete?])))
        (is (= 2 (count (:events answer)))))
      (let [answer (read (map event (range 5)) (assoc limits :max-bytes (* 2 (count (event 1)))))]
        (is (= {:done? true :truncated :max-bytes} (select-keys answer [:done? :truncated :complete?])))
        (is (= 2 (count (:events answer))))))
    (testing "an exchange that gives the relay an event, and waits for the answer to it"
      (let [limits (assoc limits :published "abc" :until :event)
            eose   (str "[\"EOSE\",\"" nostr/subscription "\"]")
            keys   [:done? :complete? :accepted? :refused]]
        (is (= {:complete? true :accepted? true}
               (select-keys (read [eose "[\"OK\",\"abc\",true,\"\"]"] limits) keys))
            "the end of the stored events is not the end of the exchange")
        (is (= {:done? true :complete? true :accepted? true}
               (select-keys (read [eose "[\"OK\",\"abc\",true,\"\"]" (event 1) (event 2)] limits) keys)))
        (is (= ["1"] (map #(get % "content") (:events (read [(event 1) (event 2)] limits))))
            "the first event ends the exchange")
        (is (= {:done? true :refused "blocked: no"}
               (select-keys (read ["[\"OK\",\"abc\",false,\"blocked: no\"]" (event 1)] limits) keys)))
        (is (= {} (select-keys (read ["[\"OK\",\"other\",false,\"blocked: no\"]"] limits) keys))
            "the answer to another event")))
    (testing "an exchange that gives the relay an event, and no more"
      (let [limits (assoc limits :published "abc" :until :ok)]
        (is (= {:done? true :accepted? true}
               (select-keys (read ["[\"OK\",\"abc\",true,\"\"]"] limits) [:done? :accepted? :refused])))
        (is (= {:done? true :refused "invalid: no"}
               (select-keys (read ["[\"OK\",\"abc\",false,\"invalid: no\"]"] limits) [:done? :accepted? :refused])))))))

(deftest an-event-that-the-app-writes
  (let [aux   (apply str (repeat 64 "5"))
        event (nostr/signed {"kind" 1 "created_at" 1790000000 "tags" [["t" "podcast"]] "content" "Hi"} ann aux)]
    (is (nostr/verified? event))
    (is (= (secp256k1/public-key ann) (get event "pubkey")))
    (is (= {"kind" 1 "created_at" 1790000000 "tags" [["t" "podcast"]] "content" "Hi"}
           (select-keys event ["kind" "created_at" "tags" "content"])))
    (is (= event (nostr/signed (assoc event "pubkey" "another" "id" "another" "sig" "another") ann aux))
        "the key, the id and the signature are those of the owner")
    (is (nostr/verified? (nostr/signed {"kind" 23194 "created_at" 5} bob aux)) "without tags and without content")
    (is (= [] (get (nostr/signed {"kind" 23194 "created_at" 5} bob aux) "tags")))
    (is (not= (get event "sig") (get (nostr/signed event ann (apply str (repeat 64 "6"))) "sig")))
    (is (nil? (nostr/signed {"kind" 1 "created_at" 5} "no key" aux)))
    (is (= ["EVENT" event] (json/read-str (nostr/event-message event))))))

(deftest the-url-of-a-relay
  (doseq [[s url] {"wss://relay.example"              "wss://relay.example"
                   "  WSS://Relay.Example/  "         "wss://relay.example"
                   "ws://relay.example:7777/path/"    "ws://relay.example:7777/path"
                   "wss://relay.example/Path?x=1"     "wss://relay.example/Path?x=1"
                   "wss://relay.example?x=1"          "wss://relay.example?x=1"
                   "https://relay.example"            nil
                   "wss://"                           nil
                   "wss:// relay.example"             nil
                   "relay.example"                    nil
                   ""                                 nil}]
    (is (= url (nostr/relay-url s)) (pr-str s)))
  (is (nil? (nostr/relay-url nil)))
  (doseq [[url public?] {"wss://127.0.0.1"          false
                         "ws://localhost:7777"      false
                         "wss://192.168.1.10/relay" false
                         "wss://[::1]"              false
                         "wss://relay.internal"     false
                         "https://example.org"      false
                         "relay"                    false}]
    (is (= public? (nostr/public? url)) url)))

(deftest a-request-that-is-refused
  (let [refused? (fn [request]
                   #?(:clj  (try
                              (nostr/send! request)
                              false
                              (catch Exception e
                                (= ::nostr/refused (:type (ex-data e)))))
                      :cljs (-> (nostr/send! request)
                                (.then (constantly false))
                                (.catch #(= ::nostr/refused (:type (ex-data %)))))))
        check    (fn [request]
                   (fn []
                     #?(:clj  (is (refused? request) (pr-str request))
                        :cljs (.then (refused? request) #(is % (pr-str request))))))]
    (run (check {:url "https://example.org" :filters [{}]})
         (check {:url nil :filters [{}]})
         (check {:url "wss://127.0.0.1:1" :filters [{}]
                 :allow-pred nostr/public?})
         (check {:url "wss://relay.example" :filters [{}]
                 :allow-pred (constantly false)}))))

#?(:clj
   (deftest an-exchange-with-a-relay
     (let [log      (atom [])
           notes    (mapv #(signed ann {:at (+ 1790000000 %) :content (apply str (repeat (* 40 %) "word "))}) (range 1 40))
           event    #(json/write-str ["EVENT" nostr/subscription %])
           eose     (json/write-str ["EOSE" nostr/subscription])
           answer   (fn [filters]
                      (case (get (first filters) "#t")
                        ["all"]    (conj (mapv event notes) (json/write-str ["NOTICE" "be kind"]) eose)
                        ["none"]   [eose]
                        ["closed"] [(json/write-str ["CLOSED" nostr/subscription "auth-required: tell who you are"])]
                        ["silent"] [(event (first notes))]
                        []))]
       (with-relay answer log
         (fn [url]
           (testing "the events of a relay, the long ones too, until the relay has no more"
             (let [response (nostr/send! {:url url :filters [{"#t" ["all"]} {"kinds" [1]}]})]
               (is (= {:url url :complete? true :notices ["be kind"]} (dissoc response :events)))
               (is (= notes (:events response)))
               (is (every? nostr/verified? (:events response)))
               ;; the relay reads the last message of the client some time after the call
               (loop [tries 50]
                 (when (and (pos? tries) (< (count @log) 2))
                   (Thread/sleep 20)
                   (recur (dec tries))))
               (is (= [["REQ" nostr/subscription {"#t" ["all"]} {"kinds" [1]}] ["CLOSE" nostr/subscription]] @log))))
           (is (= {:url url :events [] :complete? true} (nostr/send! {:url (str url "/") :filters [{"#t" ["none"]}]})))
           (is (= {:url url :events [] :closed "auth-required: tell who you are"}
                  (nostr/send! {:url url :filters [{"#t" ["closed"]}]})))
           (testing "the limits of a request"
             (is (= {:count 3 :truncated :max-events}
                    (let [response (nostr/send! {:url url :filters [{"#t" ["all"]}] :max-events 3})]
                      {:count (count (:events response)) :truncated (:truncated response)})))
             (is (= :max-bytes (:truncated (nostr/send! {:url url :filters [{"#t" ["all"]}] :max-bytes 2000})))))
           (testing "a relay that sends an event, and then nothing"
             (let [response (nostr/send! {:url url :filters [{"#t" ["silent"]}] :timeout-seconds 1})]
               (is (= {:url url :events [(first notes)] :timeout? true} response))))))
       (testing "a relay that is not there"
         (is (= ::nostr/failed
                (try
                  (nostr/send! {:url "ws://127.0.0.1:1" :filters [{}] :timeout-seconds 2})
                  (catch Exception e
                    (:type (ex-data e))))))))))

#?(:clj
   (deftest an-event-for-a-relay
     (let [log     (atom [])
           ok      (fn [event accepted? reason] (json/write-str ["OK" (get event "id") accepted? reason]))
           eose    (json/write-str ["EOSE" nostr/subscription])
           reply   (fn [event] (signed bob {:kind 23195 :at 1790000001 :tags [["e" (get event "id")]] :content "done"}))
           closed  (json/write-str ["CLOSED" nostr/subscription "auth-required: tell who you are"])
           answer  (fn [x]
                     (cond
                       (and (vector? x) (= ["closed"] (get (first x) "#t"))) [closed]
                       (and (vector? x) (= ["mute"] (get (first x) "#t")))   []
                       (vector? x)                                           [eose]
                       (= "refuse" (get x "content"))                        [(ok x false "blocked: not on the list")]
                       (= "silent" (get x "content"))                        [(ok x true "")]
                       :else [(ok x true "") (json/write-str ["EVENT" nostr/subscription (reply x)])]))
           request (fn [url content]
                     (let [event (signed ann {:kind 23194 :at 1790000000 :content content})]
                       {:url             url
                        :event           event
                        :filters         [{"kinds" [23195] "#e" [(get event "id")] "#t" [content]}]
                        :timeout-seconds 2}))
           events  (fn [] (filter #(= "EVENT" (first %)) @log))]
       (with-relay answer log
         (fn [url]
           (testing "the answer to the event, which comes after the end of the stored events"
             (let [{:keys [event filters] :as asked} (request url "pay")
                   response (nostr/send! asked)]
               (is (= {:url url :complete? true :sent? true :accepted? true :events [(reply event)]} response))
               (is (= [(into ["REQ" nostr/subscription] filters) ["EVENT" event]] (take 2 @log))
                   "the relay has the subscription before the event goes out")))
           (testing "a relay that refuses the event"
             (is (= {:url url :complete? true :sent? true :events [] :refused "blocked: not on the list"}
                    (nostr/send! (request url "refuse")))))
           (testing "a relay that takes the event, and then has no answer"
             (is (= {:url url :complete? true :sent? true :accepted? true :events [] :timeout? true}
                    (nostr/send! (assoc (request url "silent") :timeout-seconds 1)))))
           (testing "a relay that refuses the subscription gets no event"
             (reset! log [])
             (is (= {:url url :events [] :closed "auth-required: tell who you are"}
                    (nostr/send! (request url "closed"))))
             (is (empty? (events))))
           (testing "a relay that does not tell that it has the subscription gets no event"
             (reset! log [])
             (is (= {:url url :events [] :timeout? true}
                    (nostr/send! (assoc (request url "mute") :timeout-seconds 1))))
             (is (empty? (events))))
           (testing "an event without filters: it goes out at once, and the exchange ends when the relay takes it"
             (is (= {:url url :sent? true :accepted? true :events []}
                    (nostr/send! (dissoc (request url "silent") :filters))))
             (is (= {:url url :sent? true :events [] :refused "blocked: not on the list"}
                    (nostr/send! (dissoc (request url "refuse") :filters))))))))))
