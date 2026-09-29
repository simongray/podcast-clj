(ns dk.simongray.podcast.nostr-test
  "Nostr: the id and the signature of an event, the names of NIP-19, the
  filters and the messages of a relay, and the steps of an exchange, on
  both platforms; and on the JVM, an exchange with a relay on localhost."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.bech32 :as bech32]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.secp256k1 :as secp256k1]
            [dk.simongray.podcast.test-support :refer [fixture run secret-key signed]])
  #?(:clj (:import [java.io InputStream OutputStream]
                   [java.net InetAddress ServerSocket Socket]
                   [java.security MessageDigest]
                   [java.util Base64])))

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
           (nostr/serialized note)))
    (is (= (get note "id") (bytes/hex (bytes/sha256 (bytes/encode (nostr/serialized note))))))
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
        (is (= content (apply str (map :text (parts content [])))))))))

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
        (is (= 2 (count (:events answer))))))))

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
                              (nostr/call! request)
                              false
                              (catch Exception e
                                (= ::nostr/refused (:type (ex-data e)))))
                      :cljs (-> (nostr/call! request)
                                (.then (constantly false))
                                (.catch #(= ::nostr/refused (:type (ex-data %)))))))
        check    (fn [request]
                   (fn []
                     #?(:clj  (is (refused? request) (pr-str request))
                        :cljs (.then (refused? request) #(is % (pr-str request))))))]
    (run (check {:url "https://example.org" :filters [{}]})
         (check {:url nil :filters [{}]})
         (check {:url "wss://127.0.0.1:1" :filters [{}] :allow? nostr/public?})
         (check {:url "wss://relay.example" :filters [{}] :allow? (constantly false)}))))

#?(:clj
   (defn- frame
     "The frame of a WebSocket for the text `s`, as a server sends it."
     ^bytes [^String s]
     (let [payload (.getBytes s "UTF-8")
           n       (alength payload)
           header  (cond
                     (< n 126)   [0x81 n]
                     (< n 65536) [0x81 126 (quot n 256) (mod n 256)]
                     :else       (into [0x81 127] (map #(mod (quot n (long (Math/pow 256 %))) 256) (range 7 -1 -1))))]
       (byte-array (concat (map unchecked-byte header) payload)))))

#?(:clj
   (defn- read-frame
     "The next frame of the client on `in`, as `[opcode text]`. Nil at the
     end of the stream."
     [^InputStream in]
     (let [b0 (.read in)
           b1 (.read in)]
       (when-not (or (neg? b0) (neg? b1))
         (let [short (bit-and b1 0x7f)
               n     (case short
                       126 (+ (* 256 (.read in)) (.read in))
                       127 (reduce (fn [acc _] (+ (* 256 acc) (.read in))) 0 (range 8))
                       short)
               mask  (vec (repeatedly 4 #(.read in)))
               data  (.readNBytes in (int n))]
           [(bit-and b0 0x0f)
            (String. (byte-array (map-indexed (fn [i b] (unchecked-byte (bit-xor (bit-and b 0xff) (mask (mod i 4))))) data))
                     "UTF-8")])))))

#?(:clj
   (defn- handshake
     "Reads the request of a client on `in`, and answers on `out` as a
     server of WebSockets answers, by RFC 6455."
     [^InputStream in ^OutputStream out]
     (let [request (loop [text ""]
                     (if (str/ends-with? text "\r\n\r\n")
                       text
                       (recur (str text (char (.read in))))))
           key     (second (re-find #"(?i)sec-websocket-key: *(\S+)" request))
           accept  (.encodeToString (Base64/getEncoder)
                                    (.digest (MessageDigest/getInstance "SHA-1")
                                             (.getBytes (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11") "UTF-8")))]
       (.write out (.getBytes (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                   "Sec-WebSocket-Accept: " accept "\r\n\r\n")
                              "UTF-8"))
       (.flush out))))

#?(:clj
   (defn- with-relay
     "Call `f` with the URL of a relay on localhost. The relay answers each
     REQ with the messages that `(answer filters)` gives, and `log` gets
     each message of the client."
     [answer log f]
     (let [server (ServerSocket. 0 5 (InetAddress/getByName "127.0.0.1"))
           serve  (fn [^Socket socket]
                    (let [in  (.getInputStream socket)
                          out (.getOutputStream socket)]
                      (handshake in out)
                      (loop []
                        (when-let [[opcode text] (read-frame in)]
                          (when (= 1 opcode)
                            (swap! log conj (json/read-str text))
                            (let [[kind _ & filters] (json/read-str text)]
                              (when (= "REQ" kind)
                                (doseq [message (answer (vec filters))]
                                  (.write out (frame message)))
                                (.flush out)))
                            (recur))))))
           served (future
                    (while (not (.isClosed server))
                      ;; a client that has enough leaves while the relay still sends
                      (try
                        (with-open [socket (.accept server)]
                          (serve socket))
                        (catch Exception _
                          nil))))]
       (try
         (f (str "ws://127.0.0.1:" (.getLocalPort server)))
         (finally
           (.close server)
           (future-cancel served))))))

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
             (let [response (nostr/call! {:url url :filters [{"#t" ["all"]} {"kinds" [1]}]})]
               (is (= {:url url :complete? true :notices ["be kind"]} (dissoc response :events)))
               (is (= notes (:events response)))
               (is (every? nostr/verified? (:events response)))
               ;; the relay reads the last message of the client some time after the call
               (loop [tries 50]
                 (when (and (pos? tries) (< (count @log) 2))
                   (Thread/sleep 20)
                   (recur (dec tries))))
               (is (= [["REQ" nostr/subscription {"#t" ["all"]} {"kinds" [1]}] ["CLOSE" nostr/subscription]] @log))))
           (is (= {:url url :events [] :complete? true} (nostr/call! {:url (str url "/") :filters [{"#t" ["none"]}]})))
           (is (= {:url url :events [] :closed "auth-required: tell who you are"}
                  (nostr/call! {:url url :filters [{"#t" ["closed"]}]})))
           (testing "the limits of a request"
             (is (= {:count 3 :truncated :max-events}
                    (let [response (nostr/call! {:url url :filters [{"#t" ["all"]}] :max-events 3})]
                      {:count (count (:events response)) :truncated (:truncated response)})))
             (is (= :max-bytes (:truncated (nostr/call! {:url url :filters [{"#t" ["all"]}] :max-bytes 2000})))))
           (testing "a relay that sends an event, and then nothing"
             (let [response (nostr/call! {:url url :filters [{"#t" ["silent"]}] :timeout-seconds 1})]
               (is (= {:url url :events [(first notes)] :timeout? true} response))))))
       (testing "a relay that is not there"
         (is (= ::nostr/failed
                (try
                  (nostr/call! {:url "ws://127.0.0.1:1" :filters [{}] :timeout-seconds 2})
                  (catch Exception e
                    (:type (ex-data e))))))))))
