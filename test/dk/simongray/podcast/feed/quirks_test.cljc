(ns dk.simongray.podcast.feed.quirks-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.quirks :as quirks]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.podcast.util.xml :as xml]))

(def quirky
  (delay (fixture "feeds/quirky.xml")))

(deftest namespace-uris-with-long-runs-of-slashes
  (testing "a trailing run is dropped for the lookup, an inner one kept"
    (is (= :podcast (quirks/uri->alias "https://podcastindex.org/namespace/1.0///")))
    (let [slashes (apply str (repeat 100000 "/"))]
      (is (= (str "acme.example" slashes "ns") (quirks/uri-key (str "https://acme.example" slashes "ns"))))))
  (testing "a feed that uses such a URI for many names parses in linear time"
    (let [uri  (str "https://acme.example" (apply str (repeat 20000 "/")) "ns")
          feed (podcast/parse (str "<rss version=\"2.0\" xmlns:acme=\"" uri "\"><channel><title>T</title>"
                                   (apply str (repeat 500 "<acme:x>1</acme:x>"))
                                   "</channel></rss>"))]
      (is (= 500 (count (:xml/unknown feed)))))))

(deftest repairs
  (testing "each repair on its own"
    (is (= "<a/>" (quirks/strip-leading-junk "﻿\n junk <a/>")))
    (is (= "<?xml version=\"1.0\"?><rss/>"
           (quirks/strip-leading-junk "<br />\n<b>Warning</b>: x<br />\n<?xml version=\"1.0\"?><rss/>"))
        "stray markup from the server too")
    (is (= "<rss version=\"2.0\"/>" (quirks/strip-leading-junk "<b>Notice</b>: x\n<rss version=\"2.0\"/>")))
    (is (= "<rss></rss>" (quirks/strip-trailing-junk "<rss></rss>\n<br />\n<b>Warning</b>: x")))
    (is (= "<rss></rss>\n" (quirks/strip-trailing-junk "<rss></rss>\n")) "white space after the end is no junk")
    (is (= "ab" (quirks/strip-control-characters "a\u0000\u0008b")))
    (is (= "abc&#9;d <![CDATA[&#11;]]>" (quirks/strip-control-characters "a&#11;b&#x1F;c&#9;d <![CDATA[&#11;]]>"))
        "and references to them, outside CDATA")
    (is (= "&#160;x &amp; &amp;bogus; <![CDATA[&nbsp;]]>"
           (quirks/replace-html-entities "&nbsp;x &amp; &bogus; <![CDATA[&nbsp;]]>")))
    (is (= "a &amp; b &amp; &#38; <![CDATA[a & b]]>"
           (quirks/escape-bare-ampersands "a & b &amp; &#38; <![CDATA[a & b]]>")))
    (is (= ["itunes" "podcast" "x"] (sort (quirks/undeclared-prefixes "<rss><itunes:author/><podcast:guid/><x:y/></rss>"))))
    (is (= ["xlink"] (quirks/undeclared-prefixes "<a href=\"https://x\"\n xlink:title=\"t\">At 12:30</a>"))
        "a colon in a value or in text names no prefix")
    (is (= ["data-xmlns" "itunes"] (sort (quirks/undeclared-prefixes "<rss data-xmlns:itunes=\"x\"><itunes:author/></rss>")))
        "only an xmlns attribute declares one")
    (is (re-find #"^<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">"
                 (quirks/declare-known-prefixes "<rss><itunes:author/></rss>"))))
  (testing "repair reports what it changed"
    (let [{:keys [xml report]} (quirks/repair "﻿<rss><channel><title>A & B &nbsp;</title></channel></rss>")]
      (is (= [:repair/leading-junk :repair/html-entities :repair/bare-ampersands] (map :id report)))
      (is (= "<rss><channel><title>A &amp; B &#160;</title></channel></rss>" xml)))))

(deftest entities
  (is (= "it's & … A  x &bogus;" (xml/decode-entities "it&#39;s &amp; &hellip; &#x41; &nbsp;x &bogus;"))))

(deftest junk-around-the-document
  (let [read (fn [xml]
               (let [f (podcast/parse xml)]
                 [(:rss/title f) (mapv :id (podcast/report f))]))]
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
                        (podcast/parse "<guid/>"))
      "a document that is one empty guid is no feed, and no quirk removes it"))

#?(:clj
   (deftest repairing-a-stream-or-a-reader
     (let [xml "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss version=\"2.0\"><channel><title>Café &nbsp;</title></channel></rss>"]
       (is (= "Café" (:rss/title (podcast/parse (java.io.ByteArrayInputStream. (.getBytes xml "ISO-8859-1")))))
           "read once, in the encoding the document declares, and repaired")
       (is (= "Café" (:rss/title (podcast/parse (java.io.StringReader. xml))))))))

(deftest numeric-references-a-string-cannot-hold
  (is (= "&#1114112; &#x110000; &#99999999999999999999; A"
         (xml/decode-entities "&#1114112; &#x110000; &#99999999999999999999; &#65;")))
  (is (= "&#0; &#xD800; &#xDFFF; A ퟿ "
         (xml/decode-entities "&#0; &#xD800; &#xDFFF; &#X41; &#xD7FF; &#xE000;"))
      "zero and a surrogate are no characters, and a capital X reads as in HTML"))

(deftest leaving-rules-out
  (testing "repair skips the repairs it is told to"
    (is (= [:repair/bare-ampersands]
           (map :id (:report (quirks/repair "junk\n<rss><channel><title>A & B</title></channel></rss>"
                                            {:except #{:repair/leading-junk}}))))))
  (testing "and normalize the rules, through parse-tree"
    (let [xml      "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><itunes:explicit>Yes</itunes:explicit></channel></rss>"
          explicit (fn [opts]
                     (let [tree (podcast/parse-tree xml opts)]
                       [(xml/text (xml/element (xml/element tree :channel) :itunes/explicit))
                        (set (map :id (podcast/report tree)))]))]
      (is (= ["true" #{:quirk/explicit-value}] (explicit {})))
      (is (= ["Yes" #{}] (explicit {:except #{:quirk/explicit-value}}))))))

(deftest namespace-variants
  (is (= :podcast (quirks/uri->alias "HTTP://podcastindex.org/namespace/1.0/")))
  (is (= :psc (quirks/uri->alias "http://podlove.org/simple-chapters/")))
  (is (= :media (quirks/uri->alias "http://www.rssboard.org/media-rss")))
  (is (nil? (quirks/uri->alias "https://acme.example"))))

(deftest srcset-as-a-browser-reads-it
  (let [images (fn [srcset]
                 (->> (podcast/parse (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>"
                                          "<podcast:images srcset=\"" srcset "\"/></channel></rss>"))
                      :podcast/images
                      (mapv (juxt :href :width))))]
    (is (= [["https://res.cloudinary.com/x/w_300,h_300/a.jpg" 300] ["https://x/b.jpg" 1500] ["https://x/c.jpg" 600]]
           (images "https://res.cloudinary.com/x/w_300,h_300/a.jpg 300w, https://x/b.jpg 1500w,https://x/c.jpg 600w"))
        "commas inside a URL, and after a descriptor without a space")
    (is (= [["https://x/d.jpg" nil] ["https://x/e.jpg" nil]] (images "https://x/d.jpg,, https://x/e.jpg")))
    (let [many (apply str (repeat 20000 "https://x/a.jpg 1w, "))]
      (is (= 20000 (count (images many))) "in linear time"))))

(deftest normalizations
  (let [tree (quirks/normalize
              (xml/parse
               "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel>
                  <itunes:image>https://x/y.jpg</itunes:image><itunes:explicit>Yes</itunes:explicit>
                  <podcast:images srcset=\"https://a/1.jpg 1500w, https://a/2.jpg 600w\"/>
                  <podcast:locked>false</podcast:locked>
                  <podcast:category text=\"Singer/Songwriter\"><podcast:category text=\"Alternative Folk\"/></podcast:category>
                  <item><itunes:duration>1:02:03</itunes:duration><guid></guid>
                    <pubDate>Fri, 31 Jul 2026 10:02:23 PST</pubDate>
                    <enclosure url=\"https://a/1.mp3\" length=\"\" type=\"audio/mpeg\"/>
                    <image url=\"https://a/i.jpg\" title=\"Ep\"/>
                    <podcast:chapters href=\"https://a/c.json\" type=\"application/json+chapters\"/>
                    <podcast:transcript url=\"https://a/t.srt\" type=\"srt\"/></item></channel></rss>"))
        ids  (map :id (quirks/report tree))]
    (is (= #{:quirk/itunes-image-text :quirk/explicit-value :quirk/deprecated-images
             :quirk/duration-format :quirk/empty-guid :quirk/date-format
             :quirk/enclosure-length :quirk/transcript-type
             :quirk/yes-no-value :quirk/item-image :quirk/attribute-name
             :quirk/podcast-category}
           (set ids)))
    (let [f    (feed/parse tree)
          item (first (:rss/items f))]
      (is (= {:href "https://x/y.jpg"} (:itunes/image f)))
      (is (= [{:text "Singer/Songwriter" :categories [{:text "Alternative Folk"}]}] (:itunes/categories f)))
      (is (empty? (:xml/unknown f)))
      (is (true? (:itunes/explicit f)))
      (is (= [{:href "https://a/1.jpg" :width 1500} {:href "https://a/2.jpg" :width 600}] (:podcast/images f)))
      (is (= {:content false} (:podcast/locked f)))
      (is (not (contains? item :rss/guid)))
      (is (= {:url "https://a/1.mp3" :type "audio/mpeg"} (:rss/enclosure item)))
      (is (= {:href "https://a/i.jpg"} (:itunes/image item)))
      (is (empty? (:xml/unknown item)))
      (is (= {:url "https://a/c.json" :type "application/json+chapters"} (:podcast/chapters item)))
      (is (= "application/x-subrip" (get-in item [:podcast/transcripts 0 :type]))))))

(deftest the-quirky-fixture
  (let [f    (podcast/parse @quirky)
        ids  (frequencies (map :id (podcast/report f)))
        item (first (:rss/items f))]
    (testing "the document was repaired before it could be parsed"
      (is (= 1 (:repair/leading-junk ids)))
      (is (= 1 (:repair/undeclared-prefixes ids))))
    (testing "namespaces"
      (is (= 2 (:quirk/namespace-variant ids)))
      (is (= 1 (:xml/default-namespace ids)))
      (is (= 1 (:xml/unknown-namespace ids))))
    (testing "the model came out canonical"
      (is (= "Quirky & Co" (:rss/title f)))
      (is (= 1 (:feed/duplicate-element ids)))
      (is (= {:href "https://quirky.example/art.jpg"} (:itunes/image f)))
      (is (true? (:itunes/explicit f)))
      (is (= {:content true} (:podcast/locked f)))
      (is (= 2 (count (:podcast/images f))))
      (is (= [[:acme/tracking {:id "42"}]] (:xml/unknown f)))
      (is (= 1 (:quirk/item-image ids)))
      (is (= {:href "https://quirky.example/ep1.jpg"} (:itunes/image item)))
      (is (= 3723 (:itunes/duration item)))
      (is (= #inst "2026-09-25T01:00:00Z" (:rss/pub-date item)))
      (is (nil? (:rss/guid item)))
      (is (= "application/x-subrip" (get-in item [:podcast/transcripts 0 :type])))
      (is (= 2 (count (get-in item [:psc/chapters :chapters]))))
      (is (= 5.0E-8 (get-in item [:podcast/values 0 :suggested]))))
    (testing "the round trip keeps the foreign namespace and loses nothing"
      (let [out (podcast/emit f)]
        (is (re-find #"xmlns:acme=\"https://acme.example/rss-extensions\"" out))
        (is (= f (podcast/parse out)))))
    (testing "the strict path refuses the document"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (podcast/parse @quirky {:quirks? false}))))))
