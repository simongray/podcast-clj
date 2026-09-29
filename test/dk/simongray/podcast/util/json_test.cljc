(ns dk.simongray.podcast.util.json-test
  "The JSON adapter: what it reads, and what it refuses because a hostile
  server can send it to exhaust a reader."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.json :as json]))

(defn- sevens
  "A run of `n` digits."
  [n]
  (apply str (repeat n "7")))

(deftest reading-and-writing
  (let [data {"a" [1 2.5 "x" true nil] "b" {"c" "æ"}}]
    (is (= data (json/read-str "{\"a\": [1, 2.5, \"x\", true, null], \"b\": {\"c\": \"æ\"}}")))
    (is (= data (json/read-str (json/write-str data))))
    (is (= data (json/read-str (json/write-str data {:pretty? true})))))
  (is (= {"a" 1} (json/read-str (str (char 0xFEFF) "{\"a\": 1} \n")))
      "a byte order mark before the value, and white space after it")
  (is (nil? (json/parse "{\"a\": 1} x")) "data after the value")
  (is (nil? (json/parse "<html>"))))

(deftest text-that-exhausts-a-reader
  (testing "a value nested deeper than the stack reaches"
    (is (nil? (json/parse (str (apply str (repeat 100000 "[")) (apply str (repeat 100000 "]")))))))
  (testing "the digits in a string are text"
    (is (= {"content" (sevens 5000)} (json/read-str (str "{\"content\":\"" (sevens 5000) "\"}"))))
    (is (= [(str "a\"" (sevens 5000))] (json/read-str (str "[\"a\\\"" (sevens 5000) "\"]")))
        "after a quote that is a part of the string"))
  #?(:clj
     (testing "a number of more than a thousand digits"
       (is (= 1 (count (json/read-str (str "[" (sevens 1000) "]")))))
       (is (nil? (json/parse (str "[" (sevens 1001) "]"))))
       (is (nil? (json/parse (sevens 1001))) "at the end of the text")
       (is (nil? (json/parse (str "[0." (sevens 1001) "]"))) "after the point")
       (is (nil? (json/parse (str "{\"" (sevens 2000) "\\\\\":" (sevens 1001) "}")))
           "after a string that ends in a backslash"))))
