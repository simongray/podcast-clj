(ns dk.simongray.podcast.util.nostr-node-test
  "An exchange with a relay on localhost, over the WebSocket of the
  platform. Node only: the relay is a server of Node that speaks the
  frames of RFC 6455."
  (:require ["crypto" :as crypto]
            ["http" :as http]
            [clojure.test :refer [deftest is async]]
            [dk.simongray.podcast.test-support :refer [secret-key signed]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.nostr :as nostr]))

(defn- frame
  "The frame of a WebSocket for the text `s`, as a server sends it."
  [s]
  (let [payload (js/Buffer.from s "utf8")
        n       (.-length payload)
        header  (cond
                  (< n 126)   [0x81 n]
                  (< n 65536) [0x81 126 (quot n 256) (mod n 256)]
                  :else       (into [0x81 127] (map #(mod (quot n (js/Math.pow 256 %)) 256) (range 7 -1 -1))))]
    (js/Buffer.concat #js [(js/Buffer.from (clj->js header)) payload])))

(defn- frames
  "The frames of a client in the bytes `b`, each as `[opcode text]`. The
  frames of the tests are short, so each has its length in one byte or in
  two."
  [b]
  (loop [i 0 out []]
    (if (>= (+ i 6) (.-length b))
      out
      (let [opcode (bit-and (aget b i) 0x0f)
            short  (bit-and (aget b (inc i)) 0x7f)
            [n at] (if (= 126 short)
                     [(+ (* 256 (aget b (+ i 2))) (aget b (+ i 3))) (+ i 4)]
                     [short (+ i 2)])
            mask   (.subarray b at (+ at 4))
            data   (.subarray b (+ at 4) (+ at 4 n))
            text   (.toString (js/Buffer.from (.map data (fn [x j] (bit-xor x (aget mask (mod j 4)))))) "utf8")]
        (recur (+ at 4 n) (conj out [opcode text]))))))

(defn- with-relay
  "A promise of what `f` gives for the URL of a relay on localhost. The
  relay answers each REQ with the messages that `(answer filters)` gives,
  and each EVENT with those that `(answer event)` gives. `log` gets each
  message of the client."
  [answer log f]
  (js/Promise.
   (fn [resolve reject]
     (let [server  (http/createServer)
           sockets (atom [])]
       (.on server "upgrade"
            (fn [^js request ^js socket _]
              (let [key    (aget (.-headers request) "sec-websocket-key")
                    accept (-> (crypto/createHash "sha1")
                               (.update (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
                               (.digest "base64"))]
                (swap! sockets conj socket)
                (.on socket "error" (fn [_] nil))
                (.write socket (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                    "Sec-WebSocket-Accept: " accept "\r\n\r\n"))
                (.on socket "data"
                     (fn [chunk]
                       (doseq [[opcode text] (frames chunk)]
                         (case opcode
                           1 (let [[kind x & filters :as message] (json/read-str text)]
                               (swap! log conj message)
                               (doseq [m (case kind
                                           "REQ"   (answer (vec filters))
                                           "EVENT" (answer x)
                                           [])]
                                 (.write socket (frame m))))
                           8 (.end socket)
                           nil)))))))
       (.listen server 0 "127.0.0.1"
                (fn []
                  (-> (js/Promise.resolve (f (str "ws://127.0.0.1:" (.-port (.address server)))))
                      (.then resolve reject)
                      (.finally (fn []
                                  (run! #(.destroy %) @sockets)
                                  (.close server))))))))))

(deftest an-exchange-with-a-relay
  (async done
    (let [log    (atom [])
          ann    (secret-key 1)
          notes  (mapv #(signed ann {:at (+ 1790000000 %) :content (apply str (repeat (* 400 %) "word "))}) (range 1 4))
          event  #(json/write-str ["EVENT" nostr/subscription %])
          eose   (json/write-str ["EOSE" nostr/subscription])
          answer (fn [filters]
                   (case (get (first filters) "#t")
                     ["all"]    (conj (mapv event notes) (json/write-str ["NOTICE" "be kind"]) eose)
                     ["closed"] [(json/write-str ["CLOSED" nostr/subscription "auth-required: tell who you are"])]
                     ["silent"] [(event (first notes))]
                     [eose]))]
      (-> (with-relay answer log
            (fn [url]
              (-> (nostr/send! {:url url :filters [{"#t" ["all"]} {"kinds" [1]}]})
                  (.then (fn [response]
                           (is (= {:url url :complete? true :notices ["be kind"]} (dissoc response :events)))
                           (is (= notes (:events response)))
                           (is (every? nostr/verified? (:events response)))
                           (is (= ["REQ" nostr/subscription {"#t" ["all"]} {"kinds" [1]}] (first @log)))
                           (nostr/send! {:url (str url "/") :filters [{"#t" ["none"]}]})))
                  (.then (fn [response]
                           (is (= {:url url :events [] :complete? true} response))
                           (nostr/send! {:url url :filters [{"#t" ["closed"]}]})))
                  (.then (fn [response]
                           (is (= {:url url :events [] :closed "auth-required: tell who you are"} response))
                           (nostr/send! {:url url :filters [{"#t" ["all"]}] :max-events 2})))
                  (.then (fn [response]
                           (is (= [2 :max-events] [(count (:events response)) (:truncated response)]))
                           (nostr/send! {:url url :filters [{"#t" ["silent"]}] :timeout-seconds 1})))
                  (.then (fn [response]
                           (is (= {:url url :events [(first notes)] :timeout? true} response))
                           (is (some #{["CLOSE" nostr/subscription]} @log)))))))
          (.then (fn [_]
                   (-> (nostr/send! {:url "ws://127.0.0.1:1" :filters [{}] :timeout-seconds 2})
                       (.then #(is false (str "an answer of a relay that is not there: " (pr-str %)))
                              #(is (= ::nostr/failed (:type (ex-data %))))))))
          (.catch #(is false (str "rejected: " %)))
          (.finally done)))))

(deftest an-event-for-a-relay
  (async done
    (let [log     (atom [])
          ann     (secret-key 1)
          bob     (secret-key 2)
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
      (-> (with-relay answer log
            (fn [url]
              (let [{:keys [event filters] :as asked} (request url "pay")]
                (-> (nostr/send! asked)
                    (.then (fn [response]
                             (is (= {:url url :complete? true :sent? true :accepted? true :events [(reply event)]} response))
                             (is (= [(into ["REQ" nostr/subscription] filters) ["EVENT" event]] (take 2 @log))
                                 "the relay has the subscription before the event goes out")
                             (nostr/send! (request url "refuse"))))
                    (.then (fn [response]
                             (is (= {:url url :complete? true :sent? true :events [] :refused "blocked: not on the list"} response))
                             (nostr/send! (assoc (request url "silent") :timeout-seconds 1))))
                    (.then (fn [response]
                             (is (= {:url url :complete? true :sent? true :accepted? true :events [] :timeout? true} response))
                             (reset! log [])
                             (nostr/send! (request url "closed"))))
                    (.then (fn [response]
                             (is (= {:url url :events [] :closed "auth-required: tell who you are"} response))
                             (is (empty? (events)) "a relay that refuses the subscription gets no event")
                             (nostr/send! (assoc (request url "mute") :timeout-seconds 1))))
                    (.then (fn [response]
                             (is (= {:url url :events [] :timeout? true} response))
                             (is (empty? (events)) "a relay that does not tell that it has the subscription gets no event")
                             (nostr/send! (dissoc (request url "silent") :filters))))
                    (.then (fn [response]
                             (is (= {:url url :sent? true :accepted? true :events []} response))))))))
          (.catch #(is false (str "rejected: " %)))
          (.finally done)))))
