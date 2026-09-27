(ns dk.simongray.podcast.http-test
  "The HTTP layer: the pure parts on both platforms, and on the JVM URL
  encoding, body decoding, and fetching from servers on localhost."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.http :as http]
            #?(:clj [dk.simongray.podcast.test-support :refer [with-file-server]]))
  #?(:clj (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
                   [java.io ByteArrayOutputStream]
                   [java.net InetSocketAddress]
                   [java.security MessageDigest]
                   [java.util.zip GZIPOutputStream])))

(deftest byte-ranges
  (is (= {:start 0 :end 1023 :total 146515} (http/content-range "bytes 0-1023/146515")))
  (is (= {:start 5 :end 9 :total nil} (http/content-range "bytes 5-9/*")))
  (is (nil? (http/content-range "bytes */1000")) "an unsatisfiable range names none"))

(deftest credentials-as-a-header
  (is (= "Basic dTpw" (http/authorization {:username "u" :password "p"})))
  (is (= "Bearer t" (http/authorization (fn [] {:token "t"}))) "a function is asked for the current ones")
  (is (nil? (http/authorization nil))))

(deftest credentials-in-a-url
  (is (= ["https://host.example:8443/feed.xml?x=1" {:username "simon@example.org" :password "p p"}]
         (http/split-credentials "https://simon%40example.org:p%20p@host.example:8443/feed.xml?x=1"))
      "decoded, and the URL without them")
  (is (= ["https://host.example/" {:username "user" :password ""}] (http/split-credentials "https://user@host.example/")))
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
      (is (= "https://a.example/show/feed.xml#x" (http/absolute-url (str page "#top") "#x"))))))

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
                 (is (= ["Basic dTpw" nil] (map last @seen)))))
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
