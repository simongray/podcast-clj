(ns dk.simongray.podcast.guid-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.guid :as guid]))

(deftest specification-examples
  (testing "the examples given in the podcast:guid specification"
    (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810" (guid/feed-guid "mp3s.nashownotes.com/pc20rss.xml")))
    (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (guid/feed-guid "podnews.net/rss"))))
  (testing "scheme and trailing slashes do not matter"
    (is (= (guid/feed-guid "podnews.net/rss")
           (guid/feed-guid "https://podnews.net/rss")
           (guid/feed-guid "http://podnews.net/rss/")
           (guid/feed-guid "  podnews.net/rss//  ")))))

(deftest sha1
  (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (guid/sha1-hex "abc")))
  (is (= "da39a3ee5e6b4b0d3255bfef95601890afd80709" (guid/sha1-hex ""))))

(deftest uuid-v5
  (testing "RFC 4122 appendix example for the DNS namespace"
    (is (= "886313e1-3b8a-5372-9b90-0c9aee199e5d"
           (guid/uuid-v5 "6ba7b810-9dad-11d1-80b4-00c04fd430c8" "python.org")))))

(deftest guid-predicate
  (is (guid/guid? "917393e3-1b1e-5cef-ace4-edaa54e1f810"))
  (is (not (guid/guid? "y0ur-gu1d-g035-h3r3")))
  (is (not (guid/guid? nil))))
