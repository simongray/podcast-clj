(ns dk.simongray.podcast.http-test
  "The HTTP layer: the pure parts on both platforms, and on the JVM URL
  encoding, body decoding, and fetching from servers on localhost."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.secret :as secret]
            [dk.simongray.podcast.test-support :refer [run #?(:clj with-file-server)]])
  #?(:clj (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
                   [java.io ByteArrayOutputStream]
                   [java.net InetSocketAddress]
                   [java.security MessageDigest]
                   [java.util.zip GZIPOutputStream])))

(deftest byte-ranges
  (is (= {:start 0 :end 1023 :total 146515} (http/content-range "bytes 0-1023/146515")))
  (is (= {:start 5 :end 9 :total nil} (http/content-range "bytes 5-9/*")))
  (is (nil? (http/content-range "bytes */1000")) "an unsatisfiable range names none"))

(deftest facts-of-an-answer
  (is (= {:status 206 :url "https://b/e.mp3" :type "audio/mpeg" :length 2 :range {:start 0 :end 1 :total 1000}
          :etag "\"1\"" :cors "*" :permanent-url "https://b/e.mp3"
          :redirects [{:status 301 :url "https://a/e.mp3" :cors "*"}]}
         (http/facts {:status        206
                      :url           "https://b/e.mp3"
                      :redirects     [[301 "https://a/e.mp3" {"access-control-allow-origin" ["*"]}]]
                      :permanent-url "https://b/e.mp3"
                      :content-range {:start 0 :end 1 :total 1000}
                      :headers       {"content-type"                ["Audio/MPEG; charset=binary"]
                                      "content-length"              ["2"]
                                      "etag"                        ["\"1\""]
                                      "access-control-allow-origin" ["*"]}})))
  (is (= {:status 404} (http/facts {:status 404 :headers {}})) "only what the answer tells")
  (is (= "*, *" (:cors (http/facts {:status 200 :headers {"access-control-allow-origin" ["*" "*"]}})))
      "two values, which a browser reads as one that allows no page")
  (is (= [200 "text/vtt" 12] ((juxt :status :type :length)
                              (http/facts {:status 200 :headers {"content-type" "text/vtt" "content-length" "12"}})))
      "headers as strings, as an app's own client can give them")
  (is (nil? (:length (http/facts {:status 200 :headers {"content-length" ["-1"]}}))))
  (is (nil? (http/media-type " ")) "no type"))

#?(:cljs
   (deftest fetching-by-range-with-fetch
     (async done
       (let [real        (.-fetch js/globalThis)
             pulls       (atom 0)
             whole       (js/ReadableStream. #js {:pull (fn [controller]
                                                          (if (< (swap! pulls inc) 100)
                                                            (.enqueue controller (js/Uint8Array. 65536))
                                                            (.close controller)))})
             answers     (atom [(js/Response. (js/Uint8Array. 10)
                                              #js {:status 206 :headers #js {"content-range" "bytes 5-14/1000"}})
                                (js/Response. whole
                                              #js {:status 200 :headers #js {"content-length" (str (* 99 65536))}})])
             fetch-bytes (http/fetcher "https://example.org/a.mp3" {})]
         ;; the global fetch, as the fetcher finds it
         (set! (.-fetch js/globalThis) (fn [_ _]
                                         (let [res (first @answers)]
                                           (swap! answers rest)
                                           (js/Promise.resolve res))))
         (-> (fetch-bytes 5 15)
             (.then (fn [{b :bytes :keys [start total]}]
                      (is (= [10 5 1000] [(.-length b) start total]) "a range")
                      (fetch-bytes 0 100000)))
             (.then (fn [{b :bytes :keys [start total]}]
                      (is (= [100000 0 (* 99 65536)] [(.-length b) start total]) "the start of the whole file")
                      (is (< @pulls 10) "the rest of the file left unread")))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) real)
                         (done))))))))

#?(:cljs
   (deftest probing-with-fetch
     (async done
       (let [real     (.-fetch js/globalThis)
             requests (atom [])
             agents   (atom #{})]
         ;; the global fetch, as call! finds it
         (set! (.-fetch js/globalThis)
               (fn [_ init]
                 (swap! requests conj [(.-method init) (or (aget (.-headers init) "Range") nil)])
                 (swap! agents conj (aget (.-headers init) "User-Agent"))
                 (js/Promise.resolve
                  (if (= "HEAD" (.-method init))
                    (js/Response. nil #js {:status 200 :headers #js {"content-type" "audio/mpeg" "content-length" "1000"}})
                    (js/Response. (js/Uint8Array. 2) #js {:status 206 :headers #js {"content-type" "audio/mpeg"
                                                                                     "content-range" "bytes 0-1/1000"}})))))
         (-> (http/probe! "https://a/e.mp3")
             (.then (fn [{:keys [head get]}]
                      (is (= [200 "audio/mpeg" 1000] ((juxt :status :type :length) head)))
                      (is (= [206 {:start 0 :end 1 :total 1000}] ((juxt :status :range) get)))
                      (is (= #{["HEAD" nil] ["GET" "bytes=0-1"]} (set @requests)))
                      (is (= #{http/user-agent} @agents) "outside a browser, each request names podcast-clj")))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) real)
                         (done))))))))

(deftest credentials-as-a-header
  (let [header (comp secret/reveal http/authorization)]
    (is (= "Basic dTpw" (header {:username "u" :password "p"})))
    (is (= "Bearer t" (header (fn [] {:token "t"}))) "a function is asked for the current ones")
    (is (nil? (http/authorization nil)))
    (testing "a password and a token that are hidden"
      (is (= "Basic dTpw" (header {:username "u" :password (secret/hide "p")})))
      (is (= "Bearer t" (header {:token (secret/hide "t")})))
      (is (= "Bearer t" (header (fn [] {:token (secret/hide "t")}))))
      (is (not (re-find #"hunter2" (pr-str {:username "u" :password (secret/hide "hunter2")})))))
    (testing "the header is a hidden text"
      (is (secret/hidden? (http/authorization {:username "u" :password "p"})))
      (is (= "#<secret>" (pr-str (http/authorization {:token "t"})))))))

(deftest a-request-for-another-client
  (let [request {:method  :post
                 :url     "https://example.org/x"
                 :headers {"Authorization" (http/authorization {:token "hunter2"})
                           "Cookie"        (secret/hide "sessionid=abc")
                           "Accept"        "*/*"}
                 :body    (secret/hide "hub.secret=s3cr3t")}]
    (is (not (re-find #"hunter2|sessionid|s3cr3t" (pr-str request))))
    (is (= {:method  :post
            :url     "https://example.org/x"
            :headers {"Authorization" "Bearer hunter2" "Cookie" "sessionid=abc" "Accept" "*/*"}
            :body    "hub.secret=s3cr3t"}
           (http/revealed request)))
    (is (= (http/revealed request) (http/revealed (http/revealed request))))
    (is (= {:url "https://example.org/x"} (http/revealed {:url "https://example.org/x"})) "a request without headers and a body")
    (testing "a redirect to another origin leaves the hidden headers behind too"
      (is (= {"Accept" "*/*"} (:headers (http/redirected request 307 "https://other.example/x"))))
      (is (= (:headers request) (:headers (http/redirected request 307 "https://example.org/y")))))))

(deftest credentials-in-a-url
  (is (= ["https://host.example:8443/feed.xml?x=1" {:username "simon@example.org" :password (secret/hide "p p")}]
         (http/split-credentials "https://simon%40example.org:p%20p@host.example:8443/feed.xml?x=1"))
      "decoded, and the URL without them")
  (let [[url credentials] (http/split-credentials "https://simon:hunter2@host.example/feed.xml")]
    (is (= "https://host.example/feed.xml" url))
    (is (= "hunter2" (secret/reveal (:password credentials))))
    (is (= "{:username \"simon\", :password #<secret>}" (pr-str credentials)) "the password does not show in print")
    (is (= "Basic c2ltb246aHVudGVyMg==" (secret/reveal (http/authorization credentials)))))
  (is (= ["https://host.example/" {:username "user" :password (secret/hide "")}] (http/split-credentials "https://user@host.example/")))
  (is (= ["https://host.example/a@b" nil] (http/split-credentials "https://host.example/a@b")) "an @ in the path is no user")
  (is (= ["https://host.example/feed.xml" nil] (http/split-credentials "https://host.example/feed.xml"))))

(deftest urls-made-absolute
  (let [page "https://a.example/show/feed.xml"]
    (testing "as a browser resolves them"
      (is (= "https://a.example/show/feed.xml?page=2" (http/absolute-url page "?page=2")))
      (is (= "https://a.example/show/page2.xml" (http/absolute-url page "page2.xml")))
      (is (= "https://a.example/feed.xml" (http/absolute-url page "/feed.xml")))
      (is (= "https://a.example/other/feed.xml" (http/absolute-url page "../other/feed.xml")))
      (is (= "https://cdn.example/x.xml" (http/absolute-url page "//cdn.example/x.xml")))
      (is (= "https://b.example/f.xml" (http/absolute-url page "https://b.example/f.xml"))))
    (testing "a base without a path, and a fragment alone"
      (is (= "https://a.example/feed.xml" (http/absolute-url "https://a.example" "feed.xml")))
      (is (= "https://a.example/show/feed.xml#x" (http/absolute-url (str page "#top") "#x"))))
    (testing "a path that goes above the root stays at the root"
      (is (= "https://a.example/x" (http/absolute-url page "../../../x")))
      (is (= "https://a.example/x" (http/absolute-url page "/../x")))
      (is (= "https://a.example/" (http/absolute-url page "../..")))
      (is (= "https://b.example/x" (http/absolute-url page "https://b.example/../x"))))
    (testing "no reference is the document itself, without its fragment"
      (is (= "https://a.example/show/feed.xml?page=2" (http/absolute-url (str page "?page=2#top") ""))))
    (testing "the spaces around a URL, and the line breaks in it"
      (is (= "https://b.example/f.xml" (http/absolute-url page " https://b.example/f.xml\n")))
      (is (= "https://a.example/feed.xml" (http/absolute-url (str " " page) "\n/feed.xml")))
      (is (= "https://a.example/show/x" (http/absolute-url page (str (apply str (repeat 100000 " ")) "x")))))
    (testing "a base that is no URL, or that holds no path"
      (is (every? nil? (map #(http/absolute-url % "x") ["not a url" "/only/a/path" "mailto:a@b.example" nil])))
      (is (nil? (http/absolute-url page nil))))))

(deftest when-to-ask-again
  (let [at #inst "2026-09-27T12:00:00Z"]
    (testing "Retry-After, in seconds or as a date"
      (is (= #inst "2026-09-27T12:02:00Z" (http/not-before {"retry-after" ["120"]} at)))
      (is (= #inst "2026-09-27T12:40:00Z" (http/not-before {"retry-after" "Sun, 27 Sep 2026 12:40:00 GMT"} at))))
    (testing "the freshness of Cache-Control, less the age, else Expires"
      (is (= #inst "2026-09-27T12:08:20Z" (http/not-before {"cache-control" ["public, max-age=600"] "age" ["100"]} at)))
      (is (= #inst "2026-09-27T12:30:00Z" (http/not-before {"expires" ["Sun, 27 Sep 2026 12:30:00 GMT"]} at))))
    (testing "a date counts from the Date of the response, whatever the clock or the zone of the server"
      (let [two-hours-ahead {"date" ["Sun, 27 Sep 2026 14:00:00 GMT"]}]
        (is (= #inst "2026-09-27T12:30:00Z" (http/not-before (assoc two-hours-ahead "expires" ["Sun, 27 Sep 2026 14:30:00 GMT"]) at)))
        (is (= #inst "2026-09-27T12:20:00Z" (http/not-before (assoc two-hours-ahead "retry-after" ["Sun, 27 Sep 2026 14:20:00 GMT"]) at)))
        (is (= #inst "2026-09-27T12:20:00Z"
               (http/not-before (assoc two-hours-ahead "expires" ["Sun, 27 Sep 2026 14:30:00 GMT"] "age" ["600"]) at))
            "less the age")))
    (testing "a Retry-After that does not read counts as none"
      (is (= #inst "2026-09-27T12:10:00Z" (http/not-before {"retry-after" ["soon"] "cache-control" ["max-age=600"]} at)))
      (is (nil? (http/not-before {"retry-after" ["soon"]} at))))
    (testing "the values of every Cache-Control header"
      (is (= #inst "2026-09-27T12:10:00Z" (http/not-before {"cache-control" ["public" "max-age=600"]} at)))
      (is (nil? (http/not-before {"cache-control" ["max-age=600" "no-store"]} at))))
    (testing "nothing when the headers forbid a cache or name no later time"
      (is (nil? (http/not-before {"cache-control" ["no-cache, max-age=600"]} at)))
      (is (nil? (http/not-before {"cache-control" ["s-maxage=600"]} at)) "a shared cache's age is not ours")
      (is (nil? (http/not-before {"expires" ["Sun, 27 Sep 2026 11:30:00 GMT"]} at)))
      (is (nil? (http/not-before {} at))))
    (is (= #inst "2026-09-27T13:00:00Z" (http/not-before {"retry-after" ["999999"]} at)))
    (is (= #inst "2026-09-27T13:00:00Z" (http/not-before {"cache-control" ["public, max-age=424481"] "age" ["180318"]} at))
        "at most an hour, since a host can publish within the freshness it names")))

#?(:clj
   (deftest urls-the-wild-hands-out
     (testing "spaces and non-ASCII letters are percent-encoded as UTF-8"
       (is (= "https://example.com/Crazy%20Cloud/crazy%20cloud.xml"
              (http/legal-url "https://example.com/Crazy Cloud/crazy cloud.xml")))
       (is (= "https://example.com/bl%C3%A5b%C3%A6r.xml"
              (http/legal-url "https://example.com/blåbær.xml")))
       (is (= "https://example.com/%F0%9F%8E%99.xml"
              (http/legal-url "https://example.com/🎙.xml"))))
     (testing "a legal URL comes back unchanged, escapes and all"
       (is (= "https://example.com/a%20b.xml?q=1&r[]=2#frag"
              (http/legal-url "https://example.com/a%20b.xml?q=1&r[]=2#frag"))))
     (testing "a bracket in the path, and a percent sign that starts no escape, which java.net.URI refuses"
       (doseq [[url legal] [["https://example.com/show[1]/feed.xml?r[]=2" "https://example.com/show%5B1%5D/feed.xml?r[]=2"]
                            ["https://example.com/100%.mp3" "https://example.com/100%25.mp3"]
                            ["https://example.com/a?x=100%" "https://example.com/a?x=100%25"]
                            ["http://[::1]:8080/a b" "http://[::1]:8080/a%20b"]]]
         (is (= legal (http/legal-url url)))
         (is (java.net.URI. (http/legal-url url)))))))

#?(:clj
   (defn- gzip
     ^bytes [^bytes body]
     (let [out (ByteArrayOutputStream.)]
       (with-open [z (GZIPOutputStream. out)]
         (.write z body))
       (.toByteArray out))))

#?(:clj
   (deftest decoding-bodies
     (let [xml   "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss><channel><title>Blåbær</title></channel></rss>"
           latin (.getBytes xml "ISO-8859-1")]
       (testing "the XML declaration names the charset when the headers do not"
         (is (= xml (http/decode {} latin))))
       (testing "the Content-Type header wins over the declaration"
         (is (not= xml (http/decode {"content-type" ["text/xml; charset=UTF-8"]} latin))))
       (testing "gzip is undone before decoding, however the header spells it"
         (is (= xml (http/decode {"content-encoding" ["gzip"]} (gzip latin))))
         (is (= xml (http/decode {"content-encoding" ["GZIP"]} (gzip latin)))))
       (testing "an unknown charset falls back to UTF-8"
         (is (= "<a>x</a>" (http/decode {"content-type" ["text/xml; charset=nonsense"]} (.getBytes "<a>x</a>" "UTF-8"))))))))

#?(:clj
   (defn- with-server
     "Call `f` with the base URL of a server on localhost answering `routes`, a
     map of path to `[status headers body-bytes]`."
     [routes f]
     (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
       (doseq [[path [status headers ^bytes body]] routes]
         (.createContext server path
                         (reify HttpHandler
                           (handle [_ exchange]
                             (doseq [[k v] headers]
                               (.add (.getResponseHeaders exchange) k v))
                             (.sendResponseHeaders exchange status (alength body))
                             (with-open [out (.getResponseBody exchange)]
                               (.write out body))))))
       (.start server)
       (try
         (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally
           (.stop server 0))))))

#?(:clj
   (deftest fetching-from-a-server
     (let [xml   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Blåbær</title></channel></rss>"
           bytes (.getBytes xml "UTF-8")
           big   (byte-array (* 2 1024 1024))]
       (with-server {"/feed.xml" [200 {"Content-Type" "application/rss+xml; charset=UTF-8" "ETag" "\"v1\""} bytes]
                     "/feed.gz"  [200 {"Content-Type" "application/rss+xml" "Content-Encoding" "gzip"} (gzip bytes)]
                     "/big.xml"  [200 {"Content-Type" "application/rss+xml"} big]
                     "/gone"     [404 {} (.getBytes "nope" "UTF-8")]}
         (fn [base]
           (testing "the body decoded by its charset, with the validator headers picked out"
             (let [{:keys [status body etag]} (http/fetch! (str base "/feed.xml"))]
               (is (= 200 status))
               (is (= xml body))
               (is (= "\"v1\"" etag))))
           (testing "gzip is undone"
             (is (= xml (:body (http/fetch! (str base "/feed.gz"))))))
           (testing "a body over :max-bytes is refused rather than read"
             (is (= ::http/too-large
                    (try (http/fetch! (str base "/big.xml") {:max-bytes 1024}) nil
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
             (is (= (* 2 1024 1024) (count (:body (http/fetch! (str base "/big.xml")))))))
           (testing "an error status still comes back as a map"
             (is (= 404 (:status (http/fetch! (str base "/gone")))))))))))

#?(:clj
   (defn- with-servers
     "Call `f` with the base URLs of two servers on localhost, two origins,
     that answer each request by `(handler base path exchange)` and note the
     path, the method and the Authorization header of each request in `seen`."
     [seen handler f]
     (let [servers (repeatedly 2 #(HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0))
           bases   (mapv #(str "http://127.0.0.1:" (.getPort (.getAddress ^HttpServer %))) servers)]
       (doseq [[^HttpServer server base] (map vector servers bases)]
         (.createContext server "/"
                         (reify HttpHandler
                           (handle [_ exchange]
                             (let [path (.getPath (.getRequestURI exchange))]
                               (swap! seen conj [base path (.getRequestMethod exchange)
                                                 (.getFirst (.getRequestHeaders exchange) "Authorization")])
                               (handler bases path exchange)))))
         (.start server))
       (try
         (f bases)
         (finally
           (doseq [^HttpServer server servers]
             (.stop server 0)))))))

#?(:clj
   (defn- redirect
     [^HttpExchange exchange status location]
     (.add (.getResponseHeaders exchange) "Location" location)
     (.sendResponseHeaders exchange status -1)
     (.close exchange)))

#?(:clj
   (deftest following-redirects
     (let [seen (atom [])]
       (with-servers seen
         (fn [[_ other] path ^HttpExchange exchange]
           (case path
             "/away"       (redirect exchange 302 (str other "/feed"))
             "/moved"      (redirect exchange 301 "/moved-again")
             "/moved-again" (redirect exchange 308 "/feed")
             "/for-now"    (redirect exchange 302 "/feed")
             "/then-for-now" (redirect exchange 301 "/for-now")
             "/see-other"  (redirect exchange 303 "/feed")
             "/loop"       (redirect exchange 302 "/loop")
             "/elsewhere"  (redirect exchange 302 "ftp://files.example/feed")
             (do (.sendResponseHeaders exchange 200 2)
                 (with-open [out (.getResponseBody exchange)]
                   (.write out (.getBytes "ok"))))))
         (fn [[base other]]
           (let [call (fn [path & {:as opts}]
                        (reset! seen [])
                        (http/call! (merge {:url (str base path) :headers {"Authorization" "Basic dTpw"}} opts)))]
             (testing "a redirect to another origin leaves the credentials behind"
               (let [r (call "/away")]
                 (is (= [200 (str other "/feed")] [(:status r) (:url r)]))
                 (is (= ["Basic dTpw" nil] (map last @seen)))
                 (is (= [[302 (str base "/away") (str other "/feed")]]
                        (map (fn [[status url headers]] [status url (first (get headers "location"))]) (:redirects r)))
                     "each redirect, with the headers of its answer")))
             (testing "a redirect to a URL of another scheme is not followed"
               (is (= [302 "ftp://files.example/feed"] ((juxt :status #(first (get-in % [:headers "location"])))
                                                        (call "/elsewhere")))))
             (testing "permanent redirects give the URL they lead to"
               (is (= (str base "/feed") (:permanent-url (call "/moved"))))
               (is (= ["Basic dTpw" "Basic dTpw" "Basic dTpw"] (map last @seen)) "the same origin keeps them")
               (is (nil? (:permanent-url (call "/for-now"))) "a temporary one gives none")
               (is (= (str base "/for-now") (:permanent-url (call "/then-for-now")))
                   "and ends the permanent part"))
             (testing "a 303 turns a POST into a GET"
               (call "/see-other" :method :post :body "x")
               (is (= ["POST" "GET"] (map #(nth % 2) @seen))))
             (testing "five redirects at most, and then the redirect itself"
               (is (= 302 (:status (call "/loop"))))
               (is (= 6 (count @seen))))))))))

#?(:clj
   (deftest a-body-sent-too-slowly
     (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
       (.createContext server "/"
                       (reify HttpHandler
                         (handle [_ exchange]
                           (.sendResponseHeaders exchange 200 0)
                           (let [out (.getResponseBody exchange)]
                             (try
                               (dotimes [_ 50]
                                 (.write out (.getBytes " "))
                                 (.flush out)
                                 (Thread/sleep 100))
                               (catch java.io.IOException _)
                               (finally
                                 (try (.close out) (catch java.io.IOException _))))))))
       (.start server)
       (try
         (let [started (System/currentTimeMillis)]
           (is (= ::http/timeout
                  (try (http/fetch! (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/") {:timeout-seconds 1}) nil
                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
           (is (< (- (System/currentTimeMillis) started) 3000) "given up at the deadline, not when the body ends"))
         (finally
           (.stop server 0))))))

#?(:clj
   (deftest bytes-of-a-media-file
     (let [b (byte-array (map unchecked-byte (range 1000)))]
       (with-file-server b {}
         (fn [url]
           (let [{:keys [status body content-range]} (http/call! {:url url :as :bytes :range [10 19]})]
             (is (= 206 status))
             (is (= {:start 10 :end 19 :total 1000} content-range))
             (is (= (range 10 20) (map #(bit-and % 0xFF) body))))
           (is (= 990 (count (:body (http/call! {:url url :as :bytes :range [10 nil]})))) "to the end")
           (testing "the digest of the whole file, streamed through"
             (is (= (seq (.digest (MessageDigest/getInstance "SHA-384") b))
                    (seq (get-in (http/digest! url ["SHA-384"]) [:digests "SHA-384"])))))))
       (with-file-server b {:ranges? false}
         (fn [url]
           (let [{:keys [status body content-range]} (http/call! {:url url :as :bytes :range [10 19]})]
             (is (= 200 status) "a server that sends the whole file instead")
             (is (nil? content-range))
             (is (= (range 20) (map #(bit-and % 0xFF) body)) "of which the start up to the range is read"))
           (is (= 1000 (count (:body (http/call! {:url url :as :bytes :range [10 nil]})))) "all of it for the rest"))))))

#?(:clj
   (deftest probing-a-file
     (let [b (byte-array (map unchecked-byte (range 1000)))]
       (with-file-server b {}
         (fn [url]
           (let [{:keys [head get]} (http/probe! url)]
             (is (= [200 1000] ((juxt :status :length) head)))
             (is (= [206 {:start 0 :end 1 :total 1000}] ((juxt :status :range) get))))))
       (with-file-server b {:ranges? false}
         (fn [url]
           (is (= [200 1000] ((juxt :status :length) (:get (http/probe! url))))
               "a server that sends the whole file, of which two bytes are read"))))
     (let [closed (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
       (is (= [:file/unreachable :file/unreachable]
              (map (comp :id :error) (vals (http/probe! (str "http://127.0.0.1:" closed "/nothing")))))
           "a port where no server listens, told by a diagnostic"))))

#?(:clj
   (deftest more-than-was-asked
     (let [b (byte-array (map unchecked-byte (range 1000)))]
       (with-server {"/a.mp3" [206 {"Content-Range" "bytes 0-999/1000"} b]}
         (fn [base]
           (let [{:keys [status body content-range]} (http/call! {:url (str base "/a.mp3") :as :bytes :range [0 1]})]
             (is (= [206 {:start 0 :end 999 :total 1000}] [status content-range]))
             (is (= [0 1] (map #(bit-and % 0xFF) body)) "a 206 of the whole file, of which the range is read")))))
     (testing "an answer that holds none of the file"
       (let [page (byte-array (* 2 1024 1024) (byte 120))]
         (with-server {"/gone.mp3" [404 {"Content-Type" "text/html"} page]}
           (fn [base]
             (let [{:keys [status body]} (http/call! {:url (str base "/gone.mp3") :range [0 1] :max-bytes 1024})]
               (is (= [404 "xx"] [status body]) "of a page of 2 MB, the two bytes that the range names")
               (is (= 404 (get-in (http/probe! (str base "/gone.mp3") {:max-bytes 1024}) [:get :status]))))))))))

#?(:clj
   (deftest an-algorithm-that-the-platform-lacks
     (let [asked (atom 0)]
       (with-server {"/a.mp3" [200 {} (byte-array 10)]}
         (fn [base]
           (is (thrown? java.security.NoSuchAlgorithmException
                        (http/digest! (str base "/a.mp3") ["SHA-9000"] {:allow? (fn [_] (swap! asked inc) true)})))
           (is (zero? @asked) "no request is sent"))))))

(deftest the-requests-of-a-fetcher
  (let [sent  (atom nil)
        send  (fn [request]
                (reset! sent request)
                {:status 206 :url (:url request) :headers {} :content-range {:start 5 :end 14 :total 1000}})
        fetch (http/fetcher "https://example.org/a.mp3"
                            {:send send :headers {"X-App" "a"} :timeout-seconds 5 :max-bytes 1000})]
    (run #(async/then (fetch 5 15)
                      (fn [{:keys [start total]}]
                        (is (= [5 1000] [start total]))
                        (is (= {:url     "https://example.org/a.mp3"
                                :headers {"X-App" "a"}
                                :as      :bytes
                                :range   [5 14]
                                :timeout-seconds 5
                                :max-bytes       1000}
                               @sent)
                            "the limits of time and size, which each request carries"))))))

#?(:clj
   (deftest fetching-past-the-end
     (with-file-server (byte-array 1000) {}
       (fn [url]
         (let [{:keys [bytes start total]} ((http/fetcher url {}) 2000 2100)]
           (is (= [0 2000 1000] [(alength ^bytes bytes) start total])
               "a 416 gives no bytes and the size of the file"))))))

(deftest a-fetcher-without-options
  (is (fn? (http/fetcher "https://example.org/a.mp3"))))

(deftest a-fetcher-of-a-file-that-is-gone
  (let [url   "https://example.org/a.mp3"
        fetch (http/fetcher url {:send (fn [request] {:status 404 :url (:url request) :headers {}})})]
    (run #(async/then (async/attempt (fn [] (fetch 0 10)))
                      (fn [{:keys [error]}]
                        (is (= {:type ::http/failed :status 404 :url url} (ex-data error))
                            "a call throws, with the type and the status"))))))

(deftest the-request-after-a-redirect
  (let [post {:method  :post
              :url     "https://a.example/x"
              :headers {"Authorization" "Basic x" "Cookie" "c=1" "Accept" "*/*"}
              :body    "{}"}
        there "https://a.example/y"]
    (testing "a 303, and a 301 or 302 to a POST, turn into a GET without a body"
      (is (= {:method :get :url there :headers (:headers post) :body nil} (http/redirected post 303 there)))
      (is (= [:get nil] ((juxt :method :body) (http/redirected post 301 there))))
      (is (= [:get nil] ((juxt :method :body) (http/redirected post 302 there))))
      (is (= [:put "{}"] ((juxt :method :body) (http/redirected (assoc post :method :put) 302 there)))))
    (testing "a 307 and a 308 keep the method and the body"
      (is (= [:post "{}"] ((juxt :method :body) (http/redirected post 307 there))))
      (is (= [:post "{}"] ((juxt :method :body) (http/redirected post 308 there)))))
    (is (= :head (:method (http/redirected (assoc post :method :head) 303 there))) "a HEAD stays a HEAD")
    (testing "credentials stay behind when the redirect leaves the origin"
      (is (= {"Accept" "*/*"} (:headers (http/redirected post 307 "https://b.example/x"))))
      (is (= {"Accept" "*/*"} (:headers (http/redirected post 307 "https://a.example:8443/x"))) "another port")
      (is (= {"Accept" "*/*"} (:headers (http/redirected post 307 "http://a.example/x"))) "another scheme")
      (is (= (:headers post) (:headers (http/redirected post 307 "https://A.example:443/x")))
          "the same origin, written another way"))
    (is (= 5 (:timeout-seconds (http/redirected (assoc post :timeout-seconds 5) 307 there)))
        "the rest of the request stays")))

(deftest urls-of-the-web
  (is (every? http/web-url? ["https://a.example/feed.xml" "HTTP://a.example" "https://user:pass@a.example/x"]))
  (is (not-any? http/web-url? ["https://" "ftp://a.example/x" "a.example/x" " https://a.example/" "" nil 5
                               "javascript:alert(1)"])))

(deftest parameters-of-a-query
  (is (= "https://a.example/feed.xml?page=2" (http/with-query "https://a.example/feed.xml" {:page 2})))
  (is (= "https://a.example/feed.xml?x=1&page=2&token=a%20b#top"
         (http/with-query "https://a.example/feed.xml?x=1#top" [[:page 2] [:token "a b"]]))
      "after the parameters that the URL has, and before its fragment")
  (is (= "https://a.example/feed.xml?page=2" (http/with-query "https://a.example/feed.xml?" {:page 2})))
  (is (= "https://a.example/feed.xml?x=1&page=2" (http/with-query "https://a.example/feed.xml?x=1&" {:page 2})))
  (is (= "https://a.example/feed.xml" (http/with-query "https://a.example/feed.xml" {:page nil}))
      "no parameters, no change")
  (is (= {"since" "1727000000" "podcast" "https://example.org/feed.xml" "q" "a b c" "flag" nil}
         (http/query-params "since=1727000000&podcast=https%3A%2F%2Fexample.org%2Ffeed.xml&q=a+b%20c&flag&=x"))
      "the parameters of a query, decoded"))

(deftest base64-of-urls
  (is (= "aHR0cHM6Ly9mZWVkcy5wb2RjYXN0aW5kZXgub3JnL3BjMjAueG1s"
         (http/base64url "https://feeds.podcastindex.org/pc20.xml")))
  (is (= "Pz8_Pj4-" (http/base64url "???>>>")) "with - and _ in the place of + and /")
  (is (= ["YQ" "YWI" "YWJj"] (map http/base64url ["a" "ab" "abc"])) "and without padding"))

(deftest links-of-a-header
  (is (= [{:href "https://hub.example.com/" :rel "hub"} {:href "http://example.com/feed" :rel "self"}]
         (http/links {"link" "<https://hub.example.com/>; rel=\"hub\", <http://example.com/feed>; rel=\"self\""})))
  (is (= [{:href "https://hub.example.com/?a=1,2" :rel "hub" :title "A, B; C"}
          {:href "/feed.xml" :rel "self alternate" :type "application/rss+xml"}]
         (http/links {"link" ["<https://hub.example.com/?a=1,2>; rel=hub; title=\"A, B; C\"; REL=other"
                              "</feed.xml>;rel=\"self alternate\";type=application/rss+xml"]}))
      "several headers, a comma and a semicolon inside a value, and the first value of a parameter")
  (is (= [{:href "x"}] (http/links {"link" "garbage, <>; rel, <x>; rel"})) "what is no link is left out")
  (is (= [] (http/links {})))
  (is (= [] (http/links {"link" (apply str (repeat 20000 "<"))})) "a long header of a stranger"))

(deftest the-bytes-of-a-ring-request
  (is (= 0 (bytes/length (http/request-bytes nil 10))))
  (is (= [5 nil] [(bytes/length (http/request-bytes "hello" 5)) (http/request-bytes "hello!" 5)]))
  (is (= 5 (bytes/length (http/request-bytes "Ærø" 5))) "the limit is in bytes")
  (is (nil? (http/request-bytes "Ærø" 4)))
  (is (= 3 (bytes/length (http/request-bytes (bytes/of [1 2 3]) 3))))
  #?(:clj (is (= [3 nil]
                 [(bytes/length (http/request-bytes (java.io.ByteArrayInputStream. (byte-array 3)) 3))
                  (http/request-bytes (java.io.ByteArrayInputStream. (byte-array 4)) 3)])
              "a stream, of which one byte more than the limit is read")))

(deftest the-body-of-a-ring-request
  (is (= "" (http/body-text nil 10)))
  (is (= "hello" (http/body-text "hello" 5)))
  (is (nil? (http/body-text "hello!" 5)) "more than the limit")
  #?(:clj (let [stream #(java.io.ByteArrayInputStream. (.getBytes ^String % "UTF-8"))]
            (is (= "Ærø" (http/body-text (stream "Ærø") 5)) "a stream, read as UTF-8, its limit in bytes")
            (is (nil? (http/body-text (stream "Ærø") 4))))))

(deftest hosts-on-the-public-internet
  (is (not-any? http/public? ["http://127.0.0.1/" "http://10.1.2.3/" "http://172.16.0.1/" "http://192.168.1.1/"
                              "http://169.254.169.254/latest/meta-data" "http://100.64.0.1/" "http://0.0.0.0/"
                              "http://224.0.0.1/" "http://[::1]/" "http://[fd00::1]/" "http://[fe80::1]/"
                              "http://[::ffff:127.0.0.1]/" "http://[64:ff9b::7f00:1]/" "http://[2002:a00:1::1]/"
                              "http://localhost:8080/" "not a url"])
      "loopback, private, link-local, shared, multicast and local addresses, and IPv6 that holds them")
  (is (every? http/public? ["http://8.8.8.8/" "https://[2606:4700:4700::1111]/"])))

#?(:cljs
   (deftest names-in-a-browser
     (is (not-any? http/public? ["http://localhost/" "https://printer.local/" "https://wiki.internal/"]))
     (is (not-any? http/public? ["http://localhost./" "https://nas.local./" "https://router.home.arpa/"
                                 "https://nas/" "https://intranet./" "http://127.0.0.1./"])
         "a name with a dot at its end, and a name without a dot, which is a name of the local network")
     (is (every? http/public? ["https://podcastindex.org/" "https://podcastindex.org./"])
         "a name that JavaScript does not look up")))

(deftest names-of-the-public-internet
  (is (every? http/public-name? ["podcastindex.org" "PodcastIndex.org." "a.b.example"]))
  (is (not-any? http/public-name? ["localhost" "localhost." "nas" "nas.local" "NAS.Local." "wiki.internal"
                                   "router.home.arpa" "x.localhost" "" nil])))

#?(:clj
   (deftest refusing-hosts
     (let [seen (atom [])
           kind #(try (%) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
       (with-servers seen
         (fn [[_ other] path ^HttpExchange exchange]
           (if (= "/away" path)
             (redirect exchange 302 (str other "/feed"))
             (do (.sendResponseHeaders exchange 200 2)
                 (with-open [out (.getResponseBody exchange)]
                   (.write out (.getBytes "ok"))))))
         (fn [[base _]]
           (testing "a redirect that `:allow?` does not take is not followed"
             (is (= ::http/refused (kind #(http/call! {:url (str base "/away") :allow? (fn [url] (.startsWith ^String url base))}))))
             (is (= 1 (count @seen)) "the first request alone"))
           (testing "`call-public!` sends nothing to the network of the app"
             (reset! seen [])
             (is (= ::http/refused (kind #(http/call-public! {:url (str base "/feed")}))))
             (is (empty? @seen))))))))

#?(:cljs
   (deftest refusing-hosts-in-a-browser
     (async done
       (-> (http/call-public! {:url "http://127.0.0.1:1/feed"})
           (.then (fn [_] (is false "sent")))
           (.catch (fn [e] (is (= ::http/refused (:type (ex-data e))))))
           (.finally done)))))
