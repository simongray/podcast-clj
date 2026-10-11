(ns dk.simongray.podcast.feed.guid-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.guid :as guid]))

(deftest specification-examples
  (testing "the examples given in the podcast:guid specification"
    (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810" (guid/from-url "mp3s.nashownotes.com/pc20rss.xml")))
    (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (guid/from-url "podnews.net/rss"))))
  (testing "scheme and trailing slashes do not matter"
    (is (= (guid/from-url "podnews.net/rss")
           (guid/from-url "https://podnews.net/rss")
           (guid/from-url "http://podnews.net/rss/")
           (guid/from-url "  podnews.net/rss//  "))))
  (testing "a long run of slashes inside the URL is kept, and read in linear time"
    (let [slashes (apply str (repeat 100000 "/"))]
      (is (= (str "a" slashes "b") (guid/guid-name (str "https://a" slashes "b/")))))))

(deftest uuid-v5
  (testing "the example of RFC 9562, appendix A.4, in the DNS namespace"
    (is (= "2ed6657d-e927-568b-95e1-2665a8aea6a2"
           (guid/uuid-v5 "6ba7b810-9dad-11d1-80b4-00c04fd430c8" "www.example.com"))))
  (testing "the example of Python's uuid module"
    (is (= "886313e1-3b8a-5372-9b90-0c9aee199e5d"
           (guid/uuid-v5 "6ba7b810-9dad-11d1-80b4-00c04fd430c8" "python.org")))))

(deftest guid-predicate
  (is (guid/guid? "917393e3-1b1e-5cef-ace4-edaa54e1f810"))
  (is (not (guid/guid? "y0ur-gu1d-g035-h3r3")))
  (is (not (guid/guid? nil))))
