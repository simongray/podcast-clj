(ns dk.simongray.podcast.http-test
  "The pure parts of the JVM HTTP layer: URL encoding and body decoding."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.http :as http])
  (:import [java.io ByteArrayOutputStream]
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
