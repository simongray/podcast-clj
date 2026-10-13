(ns dk.simongray.podcast.nostr.signer-test
  "The signers of the listener's events: a key that the app holds, and a
  remote signer of NIP-46 that the tests play over a stand-in for the
  relays."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.crypto.nip44 :as nip44]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.nostr.signer :as signer]
            [dk.simongray.podcast.test-support :refer [run secret-key]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def ann (secret-key 1))
(def ann-key (secp256k1/public-key ann))

(def note
  {"kind" 1 "created_at" 1790000000 "tags" [] "content" "Hello"})

(deftest keys-of-the-app
  (let [k (signer/secret-key ann)]
    (is (= "#<secret>" (pr-str k) (pr-str (signer/nsec k))) "no key shows")
    (is (= k (signer/secret-key (signer/nsec k))) "an nsec reads back")
    (is (= k (signer/secret-key (str "  " (secret/reveal (signer/nsec k)) " ")))))
  (is (every? nil? (map signer/secret-key ["npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
                                           (apply str (repeat 64 "0")) "x" nil])))
  (is (some? (signer/public-key (signer/generate-key!)))))

(deftest a-local-signer
  (let [s (signer/local (signer/secret-key ann) {:random-fn #(bytes/of (repeat % 9))})]
    (is (= ann-key ((:public-key-fn s))))
    (run #(async/then (signer/sign! s note)
                      (fn [signed]
                        (is (nostr/verified? signed))
                        (is (= [ann-key "Hello"] [(get signed "pubkey") (get signed "content")])))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (signer/local "nope")))))

(deftest a-signer-that-signs-something-else
  (let [other {:sign-fn (fn [e] (nostr/signed (assoc e "content" "Bye") ann (apply str (repeat 64 "1"))))}
        throws {:sign-fn (fn [_] (throw (ex-info "The extension is gone" {})))}]
    (run #(async/then (signer/sign! other note)
                      (fn [{:keys [error]}]
                        (is (= :nostr/signer-unreadable (:id error)))))
         #(async/then (signer/sign! throws note)
                      (fn [{:keys [error]}]
                        (is (= [:nostr/signer-unreadable "The extension is gone"] ((juxt :id :reason) error))))))))

#?(:cljs
   (deftest a-browser-extension
     (let [aux      (apply str (repeat 64 "2"))
           provider #js {:getPublicKey (fn [] (js/Promise.resolve ann-key))
                         :signEvent    (fn [e]
                                         (js/Promise.resolve
                                          (clj->js (nostr/signed (js->clj e) ann aux))))}
           s        (signer/nip07 {:provider provider})]
       (is (nil? (signer/nip07)) "no extension in Node")
       (run #(async/then ((:public-key-fn s)) (fn [k] (is (= ann-key k))))
            #(async/then (signer/sign! s note) (fn [signed] (is (nostr/verified? signed))))))))

(def remote-secret (secret-key 2))
(def remote-key (secp256k1/public-key remote-secret))
(def client-secret (secret-key 3))

(def bunker
  (str "bunker://" remote-key "?relay=wss%3A%2F%2Fa.relay.example&relay=wss://b.relay.example&secret=once"))

(defn- playing
  "A :send that plays a remote signer of NIP-46 at the relay b, which signs
  with the key of ann and answers with `answer-fn` of the method and the
  params, or with a result of its own. The relay a takes no request. Each
  request is added to `log`."
  [log answer-fn]
  (fn [{:keys [url event]}]
    (swap! log conj url)
    (if (= "wss://a.relay.example" url)
      {:url url :events [] :refused "blocked: no requests here"}
      (let [shared  (nip44/conversation-key remote-secret (get event "pubkey"))
            request (json/object (nip44/decrypt (get event "content") shared))
            {:strs [id method params]} request
            body    (or (answer-fn method params)
                        {"result" (case method
                                    "connect"        "ack"
                                    "get_public_key" ann-key
                                    "sign_event"     (-> (json/object (first params))
                                                         (nostr/signed ann (apply str (repeat 64 "5")))
                                                         json/write-str))})
            content (nip44/encrypt (json/write-str (assoc body "id" id)) shared (apply str (repeat 64 "6")))
            answer  (nostr/signed {"kind" 24133 "created_at" 1790000000
                                   "tags" [["p" (get event "pubkey")]] "content" content}
                                  remote-secret
                                  (apply str (repeat 64 "4")))]
        {:url url :events [answer] :sent? true :complete? true}))))

(deftest a-remote-signer
  (let [connection (assoc (signer/parse bunker) :client-key (signer/secret-key client-secret))
        log        (atom [])
        opts       {:send (playing log (constantly nil))}]
    (is (= {:remote-signer remote-key
            :relays        ["wss://a.relay.example" "wss://b.relay.example"]
            :secret        (secret/hide "once")}
           (signer/parse bunker)))
    (is (nil? (signer/parse "nostr+walletconnect://x")))
    (run #(async/then (signer/connect! connection opts)
                      (fn [connected]
                        (is (= {:public-key ann-key} connected))
                        (is (= ["wss://a.relay.example" "wss://b.relay.example"] (take 2 @log))
                            "the next relay when the first doesn't take the request")))
         #(async/then (signer/sign! (signer/remote connection (assoc opts :public-key ann-key)) note)
                      (fn [signed]
                        (is (nostr/verified? signed))
                        (is (= ann-key (get signed "pubkey")))))
         (fn []
           (let [approval {:send (playing (atom []) (fn [_ _] {"result" "auth_url" "error" "https://signer.example/approve/1"}))}]
             (async/then (signer/sign! (signer/remote connection approval) note)
                         (fn [{:keys [error]}]
                           (is (= [:nostr/signer-approval "https://signer.example/approve/1"] ((juxt :id :url) error)))))))
         (fn []
           (let [refusing {:send (playing (atom []) (fn [_ _] {"error" "Not allowed"}))}]
             (async/then (signer/sign! (signer/remote connection refusing) note)
                         (fn [{:keys [error]}]
                           (is (= [:nostr/signer-refused "Not allowed"] ((juxt :id :reason) error)))))))
         (fn []
           (let [silent {:send (fn [{:keys [url]}] {:url url :events [] :sent? true :timeout? true})}]
             (async/then (signer/ask! connection "ping" [] silent)
                         (fn [{:keys [error]}]
                           (is (= :nostr/signer-silent (:id error)) "a request that went out, and no answer")))))
         (fn []
           (let [none {:send (fn [{:keys [url]}] {:url url :events [] :refused "blocked"})}]
             (async/then (signer/ask! connection "ping" [] none)
                         (fn [{:keys [error]}]
                           (is (= [:nostr/signer-unreachable 2] ((juxt :id (comp count :relays)) error))))))))))
