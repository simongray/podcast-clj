(ns dk.simongray.podcast.feed.lenient-parser-test
  "The fallback to the lenient parser of jsoup; JVM only, since jsoup is."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.qname-hiccup :as xml]))

(def broken
  (delay (slurp (io/resource "feeds/broken.xml"))))

(deftest the-broken-fixture
  (testing "the strict parser refuses it, repaired or not"
    (is (thrown? clojure.lang.ExceptionInfo (xml/parse @broken)))
    (is (thrown? clojure.lang.ExceptionInfo (rss/parse @broken {:quirks? false}))))
  (let [f     (rss/parse @broken)
        ids   (frequencies (map :id (rss/report f)))
        items (:rss/items f)]
    (testing "repairs ran first, then the lenient parser"
      (is (= 1 (:repair/bare-ampersands ids)))
      (is (= 1 (:repair/html-entities ids)))
      (is (= 1 (:repair/lenient-parser ids))))
    (testing "unless :except leaves it out"
      (is (= ::xml/malformed (try (rss/parse @broken {:except #{:repair/lenient-parser}})
                                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
    (testing "the model"
      (is (= "Broken & Proud <3" (:rss/title f)))
      (is (= "Someone … anyone" (:itunes/author f)))
      (is (= "<p>Notes with <b>markup</b> but no CDATA.</p>" (:rss/description f)))
      (is (= 1 (:feed/markup-in-text ids)))
      (is (= 2 (count items)))
      (is (= {:url "https://broken.example/1.mp3" :length 1234 :type "audio/mpeg"}
             (:rss/enclosure (first items))))
      (is (= #inst "2026-09-25T10:00:00Z" (:rss/pub-date (first items))))
      (is (= "2" (:content (:rss/guid (second items))))))
    (testing "what comes out is well-formed and reads back the same"
      (is (= f (rss/parse (rss/emit f) {:quirks? false}))))))
