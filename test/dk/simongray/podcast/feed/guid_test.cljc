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

(deftest the-key-of-a-library
  (let [declared {:podcast/guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"}
        keyed    (guid/with-key declared "9b024349-ccf0-5f69-a609-6b82873eab3c")]
    (is (guid/keyed? keyed))
    (is (not (guid/keyed? declared)) "a declared guid is no key of a library")
    (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (guid/feed-key keyed nil)) "which needs no URL")
    (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810" (guid/feed-key declared "https://example.org/feed")))))

(deftest episode-ids-as-strings
  (let [feed "917393e3-1b1e-5cef-ace4-edaa54e1f810"]
    (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810:ep0003" (guid/format-episode-id [feed "ep0003"]))
        "readable when the guids are")
    (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810:https%3A%2F%2Fexample.com%2Fep%201%3Fa%3D1"
           (guid/format-episode-id [feed "https://example.com/ep 1?a=1"]))
        "one string, with no slash or colon in it but the one between")
    (doseq [item ["ep0003" "https://example.com/ep 1?a=1" "a:b:c" "Æble/grød – 🍎" "" "%41" "tag:x,2026:/1"]]
      (is (= [feed item] (guid/parse-episode-id (guid/format-episode-id [feed item]))) (pr-str item)))
    (is (= [feed "ep 1"] (guid/parse-episode-id (str feed ":ep%201"))))
    (is (= [feed "a/b"] (guid/parse-episode-id (str feed ":a%2fb"))) "a lower-case escape too")
    (testing "nil for anything else"
      (doseq [s [nil "" "no-colon" "a:b:c" "a:b c" "a:%ZZ" "a:%C3" "a:é" 42]]
        (is (nil? (guid/parse-episode-id s)) (pr-str s)))
      (is (nil? (guid/format-episode-id [feed nil])))
      (is (nil? (guid/format-episode-id [feed 1]))))))

(deftest short-hashes-of-items
  (let [items (vec (for [i (range 3000)] {:rss/guid {:content (str "https://example.com/?p=" i)}}))
        item  (nth items 7)]
    (is (= "558b541" (guid/short-hash "https://example.com/?p=7")) "seven hex digits, as git has them")
    (is (= "558b541270ed6a0b200ad3407288854eabdd7a77" (guid/short-hash "https://example.com/?p=7" 40)))
    (is (= 3000 (count (set (map (comp guid/short-hash guid/item-guid) items))))
        "enough to tell thousands of items apart")
    (is (= [item] (guid/matching-items items "558b541")))
    (is (= [item] (guid/matching-items items "558B541270ED")) "in either case, and longer")
    (is (< 1 (count (guid/matching-items items "55"))) "every item that a short one matches")
    (is (= [{:rss/enclosure {:url "https://example.com/1.mp3"}}]
           (guid/matching-items [{:rss/enclosure {:url "https://example.com/1.mp3"}} {}]
                                (guid/short-hash "https://example.com/1.mp3")))
        "the guid of an item without one, and none of an item with neither")))
