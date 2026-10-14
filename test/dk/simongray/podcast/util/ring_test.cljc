(ns dk.simongray.podcast.util.ring-test
  "The Ring requests that the handlers answer and their responses: the
  bodies within a limit, the credentials, the conditions and the hosts of
  the requests."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.webcrypto.bytes :as bytes]))

(deftest the-bytes-of-a-ring-request
  (is (= 0 (bytes/length (ring/body-bytes nil 10))))
  (is (= [5 nil] [(bytes/length (ring/body-bytes "hello" 5)) (ring/body-bytes "hello!" 5)]))
  (is (= 5 (bytes/length (ring/body-bytes "Ærø" 5))) "the limit is in bytes")
  (is (nil? (ring/body-bytes "Ærø" 4)))
  (is (= 3 (bytes/length (ring/body-bytes (bytes/of [1 2 3]) 3))))
  #?(:clj (is (= [3 nil]
                 [(bytes/length (ring/body-bytes (java.io.ByteArrayInputStream. (byte-array 3)) 3))
                  (ring/body-bytes (java.io.ByteArrayInputStream. (byte-array 4)) 3)])
              "a stream, of which one byte more than the limit is read")))

(deftest the-body-of-a-ring-request
  (is (= "" (ring/body-text nil 10)))
  (is (= "hello" (ring/body-text "hello" 5)))
  (is (nil? (ring/body-text "hello!" 5)) "more than the limit")
  #?(:clj (let [stream #(java.io.ByteArrayInputStream. (.getBytes ^String % "UTF-8"))]
            (is (= "Ærø" (ring/body-text (stream "Ærø") 5)) "a stream, read as UTF-8, its limit in bytes")
            (is (nil? (ring/body-text (stream "Ærø") 4))))))

(deftest credentials-of-a-header
  (let [read (fn [header] (some-> (ring/credentials header) (update-vals secret/reveal)))]
    (is (= {:username "ann" :password "secret"} (read "Basic YW5uOnNlY3JldA==")))
    (is (= {:username "søren" :password "a:b:ø"} (read (http/authorization {:username "søren" :password "a:b:ø"})))
        "what http/authorization writes, hidden, with UTF-8 and colons in the password")
    (is (= {:username "" :password "p"} (read (str "basic " (bytes/base64 (bytes/encode ":p")))))
        "any case of the scheme, and no user name")
    (is (= {:token "abc.def"} (read "Bearer abc.def")))
    (is (= "{:username \"ann\", :password #<secret>}" (pr-str (ring/credentials "Basic YW5uOnNlY3JldA=="))))
    (doseq [header [nil "" "Basic" "Basic !!!" "Basic YW5u" "Digest username=\"ann\""]]
      (is (nil? (ring/credentials header)) (pr-str header)))))

(deftest the-conditions-of-a-ring-request
  (let [etag    "\"v2\""
        changed #inst "2026-10-13T12:00:00.400Z"
        asked   (fn [headers] (ring/not-modified? headers etag changed))]
    (is (false? (asked {})) "no conditions")
    (is (asked {"if-none-match" "\"v1\", W/\"v2\""}) "a weak match in a list")
    (is (asked {"if-none-match" "*"}))
    (is (false? (asked {"if-none-match" "\"v1\""})))
    (is (false? (ring/not-modified? {"if-none-match" "\"a,b\""} "\"a\"" nil))
        "a comma within a tag")
    (is (asked {"if-modified-since" "Tue, 13 Oct 2026 12:00:00 GMT"})
        "a change within the same second")
    (is (false? (asked {"if-modified-since" "Tue, 13 Oct 2026 11:59:59 GMT"})))
    (is (false? (asked {"if-modified-since" "yesterday"})))
    (is (false? (asked {"if-none-match"     "\"v1\""
                        "if-modified-since" "Tue, 13 Oct 2026 12:00:00 GMT"}))
        "If-None-Match decides alone")))

(deftest the-host-of-a-ring-request
  (let [asked (fn [host names] (ring/addressed-to? {:headers {"host" host}} names))]
    (is (= "example.org" (ring/host-name "Example.ORG:8080")))
    (is (asked "LocalHost:3000" []) "the machine itself, in any case, with a port")
    (is (asked "[::1]:8300" []))
    (is (asked nil []) "no Host header, as HTTP/1.0 allows")
    (is (asked "pod.example.org" ["Pod.example.org:443"]) "one of the names")
    (is (not (asked "evil.example" ["pod.example.org"])) "a name of a web page's own")))

(deftest a-ring-response
  (is (= {:status 200 :headers {"Content-Type" "text/html"} :body "<p>"}
         (ring/response 200 "text/html" "<p>")))
  (is (= {:status  429
          :headers {"Content-Type" "text/plain; charset=utf-8" "Retry-After" "5"}
          :body    "Wait."}
         (ring/text-response 429 "Wait." {"Retry-After" "5"}))))
