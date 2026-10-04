(ns dk.simongray.podcast.updates.websub-test
  "WebSub: the hubs of a feed, the requests of a subscriber and of a
  publisher, the signature of a hub, and the callback handler with the
  three calls that a hub makes."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.updates.websub :as websub]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]))

(def hub "https://hub.example.org/")
(def topic "https://example.org/feed.xml")
(def callback "https://app.example.net/websub/4f1c9e2a")
(def secret "a text that nobody can guess")

(deftest the-hubs-of-a-feed
  (let [linked {:atom/links [{:rel "hub" :href hub}
                             {:rel "Hub" :href "https://other.example.org/"}
                             {:rel "self" :href topic}]}]
    (testing "from the Link headers first, as WebSub asks"
      (is (= {:hubs ["https://header.example.org/"] :topic "https://example.org/feed.json"}
             (websub/discover {:url     topic
                               :headers {"link" "<https://header.example.org/>; rel=\"hub\", </feed.json>; rel=\"self\""}}
                              linked))
          "a relative URL counts from the URL of the response"))
    (testing "from the feed, when the headers name no hub"
      (is (= {:hubs [hub "https://other.example.org/"] :topic topic}
             (websub/discover {:url "https://example.org/feed" :headers {}} linked)))
      (is (= {:hubs [hub] :topic topic}
             (websub/discover {:url topic :headers {"link" "<https://example.org/>; rel=\"alternate\""}}
                              {:atom/links [{:rel "hub" :href hub}]}))
          "the URL of the response is the topic of a feed without a self link"))
    (testing "several relation types in one link"
      (is (= {:hubs [hub] :topic topic}
             (websub/discover {:url     "https://example.org/feed"
                               :headers {"link" (str "<" hub ">; rel=\"alternate HUB\", <" topic ">; rel=\"self canonical\"")}}
                              {}))))
    (is (nil? (websub/discover {:url topic :headers {}} {:atom/links [{:rel "self" :href topic}]})) "no hub")
    (is (nil? (websub/discover {} {})))))

(deftest the-requests-of-a-subscriber
  (let [r (websub/request :subscribe hub topic callback {:lease-seconds 864000 :secret secret})]
    (is (= [:post hub "application/x-www-form-urlencoded"]
           [(:method r) (:url r) (get-in r [:headers "Content-Type"])]))
    (is (= {"hub.callback"      callback
            "hub.mode"          "subscribe"
            "hub.topic"         topic
            "hub.lease_seconds" "864000"
            "hub.secret"        secret}
           (http/query-params (:body (http/revealed r)))))
    (is (not (str/includes? (pr-str r) secret)) "a request that is printed does not show the secret")
    (is (string? (:body (websub/request :subscribe hub topic callback {:lease-seconds 864000})))
        "a request without a secret has a body of text"))
  (is (= {"hub.callback" callback "hub.mode" "unsubscribe" "hub.topic" topic}
         (http/query-params (:body (websub/request :unsubscribe hub topic callback))))
      "the fields without a value are left out")
  (testing "what is a mistake of the app"
    (doseq [[what f] {"a mode that is none"           #(websub/request :renew hub topic callback)
                      "a callback that is no URL"     #(websub/request :subscribe hub topic "callback")
                      "a secret for a hub over http"  #(websub/request :subscribe "http://hub.example.org/" topic callback
                                                                       {:secret secret})
                      "a secret of 200 bytes"         #(websub/request :subscribe hub topic callback
                                                                       {:secret (apply str (repeat 200 "a"))})
                      "a secret that is empty"        #(websub/request :subscribe hub topic callback {:secret ""})
                      "a topic that is no URL"        #(websub/publish-request hub "feed.xml")}]
      (is (thrown? #?(:clj Exception :cljs js/Error) (f)) what)))
  (is (= {"hub.mode" "publish" "hub.url" topic}
         (http/query-params (:body (websub/publish-request hub topic))))
      "the request of a publisher")
  (is (= {"mode" "ping" "key" "k" "feed" topic}
         (http/query-params (:body (websub/publish-request hub topic {:publish-form {:fields    {"mode" "ping" "key" "k"}
                                                                                   :url-field "feed"}}))))
      "in the form of another hub"))

(deftest sending-to-a-hub
  (let [sent    (atom [])
        answers (fn [status body]
                  (fn [request]
                    (swap! sent conj request)
                    {:status status :url (:url request) :headers {} :body body}))
        request (websub/request :subscribe hub topic callback)]
    (run
     #(async/then (websub/call! request {:send (answers 202 "") :timeout-seconds 5})
                  (fn [answer]
                    (is (= {:status 202 :url hub} answer))
                    (is (= [5 "subscribe"]
                           [(:timeout-seconds (first @sent))
                            (get (http/query-params (:body (first @sent))) "hub.mode")])
                        "the request, with the limits of the app")))
     #(async/then (async/attempt (fn [] (websub/call! request {:send (answers 422 "The topic is not known.")})))
                  (fn [{:keys [error]}]
                    (is (= {:type ::websub/failed :status 422 :message "The topic is not known."}
                           (ex-data error))
                        "a call throws, with what the hub said"))))))

(deftest the-signature-of-a-hub
  (let [body (bytes/encode "what do ya want for nothing?")]
    (testing "the test cases of RFC 2202 and RFC 4231"
      (is (= "sha1=effcdf6ae5eb2fa2d27416d5f184df9c259a7c79" (websub/signature "sha1" "Jefe" body)))
      (is (= "sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
             (websub/signature "sha256" "Jefe" body)))
      (is (= (str "sha384=af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47"
                  "e42ec3736322445e8e2240ca5e69e2c78b3239ecfab21649")
             (websub/signature "sha384" "Jefe" body)))
      (is (= (str "sha512=164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
                  "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737")
             (websub/signature "sha512" "Jefe" body))))
    (is (nil? (websub/signature "md5" "Jefe" body)) "a method that WebSub does not name")
    (testing "a header that is the signature"
      (is (websub/signed? "Jefe" (websub/signature "sha256" "Jefe" body) body))
      (is (websub/signed? "Jefe" (str/upper-case (websub/signature "sha1" "Jefe" body)) body)
          "in whatever case the hub writes it")
      (is (websub/signed? "Jefe" (str " " (websub/signature "sha512" "Jefe" body) " ") body)))
    (testing "a header that is not"
      (doseq [header [nil "" "sha256" "sha256=" "sha256=xyz" "md5=abc"
                      (websub/signature "sha256" "another" body)
                      (websub/signature "sha256" "Jefe" (bytes/encode "another body"))
                      (str (websub/signature "sha256" "Jefe" body) "00")]]
        (is (not (websub/signed? "Jefe" header body)) (pr-str header))))
    (is (not (websub/signed? "Jefe" (websub/signature "sha1" "Jefe" body) body #{"sha256" "sha512"}))
        "a method that the app does not take")))

(defn- query
  "The query string of the call of a hub with `params`."
  [params]
  (http/query-string (merge {"hub.topic" topic "hub.challenge" "abc-123_DEF.456" "hub.lease_seconds" 864000}
                            params)))

(deftest the-callback-of-a-subscriber
  (let [came  (atom [])
        feed  (bytes/encode "<rss version=\"2.0\"><channel><title>A show</title></channel></rss>")
        serve (websub/handler
               {:wanted?  (fn [facts] (swap! came conj [:wanted (dissoc facts :request)]) (= topic (:topic facts)))
                :deliver! (fn [facts] (swap! came conj [:feed (update (dissoc facts :request) :body bytes/length)]))
                :denied!  (fn [facts] (swap! came conj [:denied (dissoc facts :request)]))
                :secret   secret})
        get!  (fn [params] (serve {:request-method :get :query-string (query params)}))
        post! (fn [headers body] (serve {:request-method :post :headers headers :body body}))
        safe  {"Content-Type" "application/octet-stream" "X-Content-Type-Options" "nosniff"}]
    (testing "the hub makes sure that the subscriber asked"
      (is (= {:status 200 :headers safe :body "abc-123_DEF.456"} (get! {"hub.mode" "subscribe"}))
          "the challenge, in a media type that no browser reads as a page")
      (is (= [:wanted {:mode :subscribe :topic topic :lease-seconds 864000}] (peek @came)))
      (is (= 200 (:status (get! {"hub.mode" "unsubscribe"}))))
      (is (= :unsubscribe (get-in (peek @came) [1 :mode])))
      (is (= {:status 404 :headers safe :body ""}
             (get! {"hub.mode" "subscribe" "hub.topic" "https://example.org/other.xml"}))
          "a topic that the subscriber did not ask for"))
    (testing "a challenge that a subscriber does not give back"
      (reset! came [])
      (doseq [challenge ["<script>alert(1)</script>" "" "a b" (apply str (repeat 513 "a"))]]
        (is (= 404 (:status (get! {"hub.mode" "subscribe" "hub.challenge" challenge}))) (pr-str challenge)))
      (is (= 404 (:status (get! {"hub.mode" "renew"}))))
      (is (= 404 (:status (serve {:request-method :get}))))
      (is (= [] @came) "and the app is not asked"))
    (testing "the hub denies a subscription"
      (is (= 200 (:status (get! {"hub.mode" "denied" "hub.reason" "The feed is private."}))))
      (is (= [:denied {:topic topic :reason "The feed is private."}] (peek @came))))
    (testing "the hub sends the feed, with the signature of the secret"
      (reset! came [])
      (is (= {:status 200 :headers safe :body ""}
             (post! {"x-hub-signature" (websub/signature "sha256" secret feed)
                     "content-type"    "application/rss+xml"
                     "link"            (str "<" hub ">; rel=\"hub\", <" topic ">; rel=\"self\"")}
                    feed)))
      (is (= [[:feed {:body 65 :content-type "application/rss+xml" :topic topic :hubs [hub]}]] @came))
      (is (= 200 (:status (post! {"x-hub-signature" (websub/signature "sha1" secret feed)} (bytes/decode feed 0 65 :utf8))))
          "a body that the server read as text")
      (is (= 2 (count @came))))
    (testing "a feed that the app does not get, with the same answer"
      (reset! came [])
      (doseq [headers [{}
                       {"x-hub-signature" "sha256=00"}
                       {"x-hub-signature" (websub/signature "sha256" "a guess" feed)}]]
        (is (= {:status 200 :headers safe :body ""} (post! headers feed)) (pr-str headers)))
      (is (= [] @came)))
    (testing "a subscription without a secret, and the limits"
      (let [open  (websub/handler {:wanted? (constantly true) :deliver! #(swap! came conj [:open (:topic %)])})
            small (websub/handler {:wanted? (constantly true) :deliver! #(swap! came conj [:small %]) :max-bytes 64
                                   :max-challenge 5})]
        (is (= 200 (:status (open {:request-method :post :headers {} :body feed}))))
        (is (= [[:open nil]] @came) "each feed that comes, and no topic without a Link header")
        (is (= 413 (:status (small {:request-method :post :headers {} :body feed}))))
        (is (= 404 (:status (small {:request-method :get :query-string (query {"hub.mode" "subscribe"})}))))
        (is (= 1 (count @came)))))
    (is (= 405 (:status (serve {:request-method :put}))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (websub/handler {:wanted? (constantly true)}))
        "a handler needs the function that gets the feed")))
