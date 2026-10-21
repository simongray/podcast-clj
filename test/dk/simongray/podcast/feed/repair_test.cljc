(ns dk.simongray.podcast.feed.repair-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.repair :as repair]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.util.entities :as entities]
            [dk.simongray.qname-hiccup :as xml]))

(deftest repairs
  (testing "each repair on its own"
    (is (= "<a/>" (repair/strip-leading-junk "﻿\n junk <a/>")))
    (is (= "<?xml version=\"1.0\"?><rss/>"
           (repair/strip-leading-junk "<br />\n<b>Warning</b>: x<br />\n<?xml version=\"1.0\"?><rss/>"))
        "stray markup from the server too")
    (is (= "<rss version=\"2.0\"/>" (repair/strip-leading-junk "<b>Notice</b>: x\n<rss version=\"2.0\"/>")))
    (is (= "<rss></rss>" (repair/strip-trailing-junk "<rss></rss>\n<br />\n<b>Warning</b>: x")))
    (is (= "<rss></rss>\n" (repair/strip-trailing-junk "<rss></rss>\n")) "white space after the end is no junk")
    (is (= "ab" (repair/strip-control-characters "a\u0000\u0008b")))
    (is (= "abc&#9;d <![CDATA[&#11;]]>" (repair/strip-control-characters "a&#11;b&#x1F;c&#9;d <![CDATA[&#11;]]>"))
        "and references to them, outside CDATA")
    (is (= "&#160;x &amp; &amp;bogus; <![CDATA[&nbsp;]]>"
           (repair/replace-html-entities "&nbsp;x &amp; &bogus; <![CDATA[&nbsp;]]>")))
    (is (= "a &amp; b &amp; &#38; <![CDATA[a & b]]>"
           (repair/escape-bare-ampersands "a & b &amp; &#38; <![CDATA[a & b]]>")))
    (is (= ["itunes" "podcast" "x"] (sort (repair/undeclared-prefixes "<rss><itunes:author/><podcast:guid/><x:y/></rss>"))))
    (is (= ["xlink"] (repair/undeclared-prefixes "<a href=\"https://x\"\n xlink:title=\"t\">At 12:30</a>"))
        "a colon in a value or in text names no prefix")
    (is (= ["data-xmlns" "itunes"] (sort (repair/undeclared-prefixes "<rss data-xmlns:itunes=\"x\"><itunes:author/></rss>")))
        "only an xmlns attribute declares one")
    (is (re-find #"^<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">"
                 (repair/declare-known-prefixes "<rss><itunes:author/></rss>"))))
  (testing "repair reports what it changed"
    (let [{:keys [xml report]} (repair/repair "﻿<rss><channel><title>A & B &nbsp;</title></channel></rss>")]
      (is (= [:repair/leading-junk :repair/html-entities :repair/bare-ampersands] (map :id report)))
      (is (= "<rss><channel><title>A &amp; B &#160;</title></channel></rss>" xml)))))

(deftest entities
  (is (= "it's & … A \u00A0x &bogus;" (entities/decode-entities "it&#39;s &amp; &hellip; &#x41; &nbsp;x &bogus;"))))

(deftest junk-around-the-document
  (let [read (fn [xml]
               (let [f (rss/parse xml)]
                 [(:rss/title f) (mapv :id (rss/report f))]))]
    (is (= ["T" [:repair/leading-junk]]
           (read "<br />\n<b>Warning</b>: x in <b>feed.php</b><br />\n<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title></channel></rss>")))
    (is (= ["T" [:repair/leading-junk]]
           (read "<b>Notice</b>: x<br />\n<rss version=\"2.0\"><channel><title>T</title></channel></rss>")))
    (is (= ["T" [:repair/trailing-junk]]
           (read "<rss version=\"2.0\"><channel><title>T</title></channel></rss>\n<br />\n<b>Warning</b>: x")))
    (is (= ["T" []] (read "<rss version=\"2.0\"><channel><title>T</title></channel></rss>\n<!-- served in 0.2 s -->\n"))
        "a comment after the end is no junk")
    ;; the xmldom of the Node tests accepts the reference, which the JVM and
    ;; browsers refuse
    #?(:clj
       (is (= ["AB" [:repair/control-characters]] (read "<rss version=\"2.0\"><channel><title>A&#11;B</title></channel></rss>")))))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an RSS"
                        (rss/parse "<guid/>"))
      "a document that is one empty guid is no feed, and no quirk removes it"))

#?(:clj
   (deftest repairing-a-stream-or-a-reader
     (let [xml "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss version=\"2.0\"><channel><title>Café &nbsp;</title></channel></rss>"]
       (is (= "Café" (:rss/title (rss/parse (java.io.ByteArrayInputStream. (.getBytes xml "ISO-8859-1")))))
           "read once, in the encoding the document declares, and repaired")
       (is (= "Café" (:rss/title (rss/parse (java.io.StringReader. xml))))))))

(deftest parsing-around-the-repairs
  (testing "a well-formed document is parsed strictly, with nothing to report"
    (is (= {:raw ["rss" {} ["channel" {} ["title" {} "A"]]] :report []}
           (repair/parse-raw "<rss><channel><title>A</title></channel></rss>"))))
  (let [malformed "<b>Notice</b>: x<br />\n<rss><channel><title>A</title></channel></rss>"]
    (testing "a malformed one is repaired first, and the report names the repairs"
      (is (= {:raw ["rss" {} ["channel" {} ["title" {} "A"]]] :report [{:id :repair/leading-junk :level :warning}]}
             (repair/parse-raw malformed))))
    (testing "unless :except leaves out the repair that it needs"
      (is (= ::xml/malformed
             (try (repair/parse-raw malformed {:except #{:repair/leading-junk :repair/lenient-parser}})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                    (:type (ex-data e)))))))))

(deftest a-lenient-parser-of-the-app
  (let [mismatched "<rss><channel><title>A</title><item><title>One</item></channel></rss>"
        lenient    (fn [s]
                     (is (= mismatched s) "the text after the repairs")
                     ["rss" {} ["channel" {} ["title" {} "A"] ["item" {} ["title" {} "One"]]]])]
    (let [f (rss/parse mismatched {:lenient-parser lenient})]
      (is (= ["A" "One"] [(:rss/title f) (:rss/title (first (:rss/items f)))]))
      (is (some #{:repair/lenient-parser} (map :id (rss/report f)))))
    (is (= ::xml/malformed
           (try (repair/parse-raw mismatched {:lenient-parser lenient
                                              :except         #{:repair/lenient-parser}})
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:type (ex-data e)))))
        "unless :except leaves it out")))

(deftest numeric-references-a-string-cannot-hold
  (is (= "&#1114112; &#x110000; &#99999999999999999999; A"
         (entities/decode-entities "&#1114112; &#x110000; &#99999999999999999999; &#65;")))
  (is (= "&#0; &#xD800; &#xDFFF; A ퟿ \uE000"
         (entities/decode-entities "&#0; &#xD800; &#xDFFF; &#X41; &#xD7FF; &#xE000;"))
      "zero and a surrogate are no characters, and a capital X reads as in HTML"))

(deftest leaving-repairs-out
  (is (= [:repair/bare-ampersands]
         (map :id (:report (repair/repair "junk\n<rss><channel><title>A & B</title></channel></rss>"
                                          {:except #{:repair/leading-junk}}))))))
