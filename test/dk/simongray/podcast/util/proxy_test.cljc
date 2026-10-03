(ns dk.simongray.podcast.util.proxy-test
  "The proxy for the requests of a web page: what goes on to the other
  site, what comes back to the page, what it refuses, its failures as the
  exceptions of http/call!, and fetch! through it, with the HTTP between
  the page and the proxy played in memory."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.proxy :as proxy]
            [dk.simongray.podcast.util.secret :as secret]))

(def feed-xml
  "<rss version=\"2.0\"><channel><title>Blåbær</title><item><title>E1</title><guid>1</guid></item></channel></rss>")

(def mp3
  "The first bytes of a media file."
  (bytes/of [0x49 0x44 0x33 0x04]))

(def answers
  "What the other sites answer, by URL, as http/call! gives it."
  {"https://a.example/feed.xml" {:status        200
                                 :url           "https://b.example/feed.xml"
                                 :permanent-url "https://b.example/feed.xml"
                                 :headers       {"content-type" ["application/rss+xml; charset=ISO-8859-1"]
                                                 "etag"         ["\"v1\""]
                                                 "set-cookie"   ["session=theirs"]}
                                 :body          feed-xml}
   "https://a.example/ep.mp3"   {:status  206
                                 :url     "https://a.example/ep.mp3"
                                 :headers {"content-type" ["audio/mpeg"] "content-range" ["bytes 0-3/1000"]}
                                 :body    mp3}})

(defn- upstream
  "A stand-in for http/call! that answers from answers, notes each request
  in the atom `sent`, and throws as call! does for a URL it doesn't know."
  [sent]
  (fn [{:keys [url] :as request}]
    (swap! sent conj request)
    (or (answers url)
        (case url
          "https://slow.example/" (throw (ex-info "Timed out" {:type ::http/timeout :url url}))
          "https://big.example/"  (throw (ex-info "Too large" {:type ::http/too-large :url url}))
          (throw (http/refusal url))))))

(defn- server
  "The proxy with `send` as its :send: handler on the JVM, and in
  ClojureScript the same parts as handler joins them."
  [send]
  #?(:clj  (proxy/handler {:send send})
     :cljs (let [opts (merge proxy/defaults {:send send})]
             (fn [ring-request]
               (or (proxy/refusal ring-request)
                   (try
                     (proxy/ring-response (send (proxy/upstream-request ring-request opts)) opts)
                     (catch :default e
                       (proxy/error-response e))))))))

(defn- over-http
  "A send that takes each request to the Ring handler `handler` as a
  browser and a Ring adapter would: the headers in lower case, with the
  Range of :range, and the body as UTF-8 text unless the request is for
  bytes."
  [handler]
  (fn [{:keys [method url headers range as]}]
    (let [lower    (fn [m f] (into {} (map (fn [[k v]] [(str/lower-case k) (f v)])) m))
          {:keys [status headers body]}
          (handler {:request-method (or method :get)
                    :query-string   (second (re-find #"\?(.*)$" url))
                    :headers        (cond-> (lower headers secret/reveal)
                                      range (assoc "range" (str "bytes=" (first range) "-" (second range))))})]
      {:status  status
       :url     url
       :headers (lower headers #(if (string? %) [%] (vec %)))
       :body    (when (and body (pos? (bytes/length body)))
                  (if (= :bytes as) body (bytes/decode body 0 (bytes/length body) :utf8)))})))

(deftest the-request-that-goes-on
  (let [ring    {:request-method :get
                 :query-string   "url=https%3A%2F%2Fa.example%2Fep.mp3&as=bytes"
                 :headers        {"accept"        "*/*"
                                  "if-none-match" "\"v1\""
                                  "range"         "bytes=0-3"
                                  "cookie"        "session=ours"
                                  "authorization" "Bearer ours"
                                  "user-agent"    "A browser"}}
        request (proxy/upstream-request ring proxy/defaults)]
    (is (= ["https://a.example/ep.mp3" :get :bytes [0 3]] ((juxt :url :method :as :range) request)))
    (is (= {"accept" "*/*" "if-none-match" "\"v1\"" "User-Agent" http/user-agent "Via" "1.1 podcast-clj"} (:headers request))
        "neither the page's cookies, its credentials nor its User-Agent go on")
    (is (= [http/public? (* 32 1024 1024)] ((juxt :allow? :max-bytes) request)))
    (is (nil? (:as (proxy/upstream-request (assoc ring :query-string "url=https%3A%2F%2Fa.example%2F") proxy/defaults)))
        "text, unless the page asks for bytes")
    (is (nil? (proxy/upstream-request (assoc ring :query-string "url=file%3A%2F%2F%2Fetc%2Fpasswd") proxy/defaults)))))

(deftest the-answer-that-comes-back
  (let [{:keys [status headers body]} (proxy/ring-response (answers "https://a.example/feed.xml") proxy/defaults)]
    (is (= 200 status))
    (is (= "application/rss+xml; charset=utf-8" (get headers "Content-Type")) "the text goes as UTF-8")
    (is (= feed-xml (bytes/decode body 0 (bytes/length body) :utf8)))
    (is (= ["\"v1\"" nil] [(get headers "etag") (get headers "set-cookie")]) "only the headers that it exposes")
    (is (= ["https://b.example/feed.xml" "https://b.example/feed.xml"]
           [(get headers "Content-Location") (get headers proxy/permanent-header)]))
    (is (= "default-src 'none'; sandbox" (get headers "Content-Security-Policy")) "a page that opens it runs no scripts"))
  (is (= mp3 (:body (proxy/ring-response (answers "https://a.example/ep.mp3") proxy/defaults))) "bytes go as they came"))

(deftest what-the-proxy-refuses
  (let [ring   {:request-method :get :query-string "url=https%3A%2F%2Fa.example%2Ffeed.xml" :headers {"sec-fetch-mode" "cors" "sec-fetch-site" "same-origin"}}
        status #(some-> (proxy/refusal %) :status)]
    (is (nil? (status ring)) "a request of a page of its own site")
    (is (nil? (status (assoc ring :request-method :head))))
    (is (= 405 (status (assoc ring :request-method :post))))
    (is (= 502 (status (assoc-in ring [:headers "via"] "1.1 cdn, 1.1 podcast-clj"))) "a request that the proxy sent itself")
    (is (= 403 (status (assoc-in ring [:headers "sec-fetch-mode"] "navigate"))) "a page that opens it")
    (is (= 403 (status (assoc ring :headers {"Sec-Fetch-Mode" "navigate"}))) "by any case of the header's name")
    (is (= 403 (status (assoc-in ring [:headers "sec-fetch-site"] "cross-site"))) "a page of another site")
    (is (= 400 (status (assoc ring :query-string "url=javascript%3Aalert(1)"))))
    (is (str/starts-with? (get-in (proxy/refusal (assoc ring :request-method :put)) [:headers "Proxy-Status"]) "podcast-clj; error="))))

(deftest failures-come-back-as-the-exceptions-of-call
  (let [sent (atom [])
        send (proxy/sender "https://app.example/proxy" {:send (over-http (server (upstream sent)))})
        kind (fn [url]
               (async/then (async/attempt #(send {:url url}))
                           (fn [{:keys [error]}] [(:type (ex-data error)) (http/timeout? error) (ex-message error)])))]
    (run #(async/then (async/all [(kind "https://private.example/") (kind "https://slow.example/") (kind "https://big.example/")])
                      (fn [[refused slow big]]
                        (is (= [::http/refused false "The request to https://private.example/ is not allowed"] refused))
                        (is (= [::http/timeout true "Timed out"] slow))
                        (is (= ::http/too-large (first big))))))))

(deftest fetching-a-feed-through-the-proxy
  (let [sent (atom [])
        send (proxy/sender "https://app.example/proxy" {:send (over-http (server (upstream sent)))})]
    (run #(async/then (podcast/fetch! "https://a.example/feed.xml" {:send send :credentials {:username "ann" :password "p"}})
                      (fn [{:keys [status url permanent-url etag feed]}]
                        (is (= [200 "https://b.example/feed.xml" "https://b.example/feed.xml" "\"v1\""] [status url permanent-url etag])
                            "the answer as http/call! gives it, with the URLs that the redirects led to")
                        (is (= "Blåbær" (:rss/title feed)))
                        (is (not (contains? (:headers (first @sent)) "authorization"))
                            "the credentials of a feed don't go on, unless :forward names them")))
         #(async/then (podcast/fetch! "https://private.example/feed.xml" {:send send})
                      (fn [{:keys [error]}]
                        (is (= :fetch/unreachable (:id error)))
                        (is (str/includes? (:reason error) "is not allowed") "a refusal of the proxy, as call! reports one")))
         #(do (reset! sent [])
              (async/then (send {:url "https://a.example/ep.mp3" :as :bytes :range [0 3]})
                          (fn [{:keys [status body]}]
                            (is (= [206 mp3] [status body]))
                            (is (= [:bytes [0 3]] ((juxt :as :range) (first @sent))) "the bytes and the range that the page asked for")))))))

(deftest requests-that-are-not-http
  (let [sent (atom [])
        send (proxy/sender "/proxy" {:send #(do (swap! sent conj %) {:events []})})]
    (send {:url "wss://relay.example" :filters [{"kinds" [1]}]})
    (is (= [{:url "wss://relay.example" :filters [{"kinds" [1]}]}] @sent) "go to the :send as they are")))
