(ns dk.simongray.podcast.util.ring-test
  "The Ring requests that the handlers answer: their bodies within a
  limit, and their credentials."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.secret :as secret]))

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
