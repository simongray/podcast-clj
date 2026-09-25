(ns dk.simongray.podcast.http-test
  "The JVM HTTP layer: URL encoding and body decoding on their own, and
  fetching from a server on localhost."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.http :as http])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.net InetSocketAddress]
           [java.util.zip GZIPOutputStream]))

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
           (http/legal-url "https://example.com/a%20b.xml?q=1&r[]=2#frag")))))

(defn- gzip
  ^bytes [^bytes body]
  (let [out (ByteArrayOutputStream.)]
    (with-open [z (GZIPOutputStream. out)]
      (.write z body))
    (.toByteArray out)))

(deftest decoding-bodies
  (let [xml   "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss><channel><title>Blåbær</title></channel></rss>"
        latin (.getBytes xml "ISO-8859-1")]
    (testing "the XML declaration names the charset when the headers do not"
      (is (= xml (http/decode {} latin))))
    (testing "the Content-Type header wins over the declaration"
      (is (not= xml (http/decode {"content-type" ["text/xml; charset=UTF-8"]} latin))))
    (testing "gzip is undone before decoding"
      (is (= xml (http/decode {"content-encoding" ["gzip"]} (gzip latin)))))
    (testing "an unknown charset falls back to UTF-8"
      (is (= "<a>x</a>" (http/decode {"content-type" ["text/xml; charset=nonsense"]} (.getBytes "<a>x</a>" "UTF-8")))))))

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
        (.stop server 0)))))

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
          (is (= 404 (:status (http/fetch! (str base "/gone"))))))))))
