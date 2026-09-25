(ns dk.simongray.podcast.jsoup-test
  "The lenient jsoup adapter and the fallback to it; JVM only, since jsoup is."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.xml :as xml]
            [dk.simongray.podcast.xml.jsoup :as jsoup]))

(def broken
  (delay (slurp (io/resource "feeds/broken.xml"))))

(deftest lenient-raw-parsing
  (let [raw (jsoup/parse-raw "<rss version=2.0 xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">
                               <channel><title>A <3 B</title><itunes:author>X</itunes:author>
                               <enclosure url=https://a/1.mp3 type=audio/mpeg /></channel></rss>")]
    (is (= "rss" (first raw)))
    (is (= {"version" "2.0" "xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"} (second raw)))
    (is (= ["title" {} "A <3 B"] (-> raw (nth 2) (nth 2))))
    (is (= ["itunes:author" {} "X"] (-> raw (nth 2) (nth 3))))
    (is (= ["enclosure" {"url" "https://a/1.mp3" "type" "audio/mpeg"}] (-> raw (nth 2) (nth 4)))))
  (testing "mismatched and unclosed tags still give a tree"
    (is (= ["a" {} ["b" {} "x" ["c" {} "y"]]] (jsoup/parse-raw "<a><b>x<c>y</b></a>"))))
  (testing "no element at all is still an error"
    (is (= ::xml/malformed (try (jsoup/parse-raw "just text")
                                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))

(deftest the-broken-fixture
  (testing "the strict parser refuses it, repaired or not"
    (is (thrown? clojure.lang.ExceptionInfo (xml/parse @broken)))
    (is (thrown? clojure.lang.ExceptionInfo (podcast/parse @broken {:quirks? false}))))
  (let [f     (podcast/parse @broken)
        ids   (frequencies (map :id (podcast/report f)))
        items (:rss/items f)]
    (testing "repairs ran first, then the lenient parser"
      (is (= 1 (:repair/bare-ampersands ids)))
      (is (= 1 (:repair/html-entities ids)))
      (is (= 1 (:repair/lenient-parser ids))))
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
      (is (= f (podcast/parse (podcast/emit f) {:quirks? false}))))))
