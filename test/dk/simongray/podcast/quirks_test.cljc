(ns dk.simongray.podcast.quirks-test
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.quirks :as quirks]
            [dk.simongray.podcast.xml :as xml]))

(def quirky
  (delay #?(:clj  (slurp (io/resource "feeds/quirky.xml"))
            :cljs (rc/inline "feeds/quirky.xml"))))

(deftest repairs
  (testing "each repair on its own"
    (is (= "<a/>" (quirks/strip-leading-junk "﻿\n junk <a/>")))
    (is (= "ab" (quirks/strip-control-characters "a\u0000\u0008b")))
    (is (= "&#160;x &amp; &amp;bogus; <![CDATA[&nbsp;]]>"
           (quirks/replace-html-entities "&nbsp;x &amp; &bogus; <![CDATA[&nbsp;]]>")))
    (is (= "a &amp; b &amp; &#38; <![CDATA[a & b]]>"
           (quirks/escape-bare-ampersands "a & b &amp; &#38; <![CDATA[a & b]]>")))
    (is (= ["itunes" "podcast" "x"] (sort (quirks/undeclared-prefixes "<rss><itunes:author/><podcast:guid/><x:y/></rss>"))))
    (is (re-find #"^<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">"
                 (quirks/declare-known-prefixes "<rss><itunes:author/></rss>"))))
  (testing "repair reports what it changed"
    (let [{:keys [xml report]} (quirks/repair "﻿<rss><channel><title>A & B &nbsp;</title></channel></rss>")]
      (is (= [:repair/leading-junk :repair/html-entities :repair/bare-ampersands] (map :id report)))
      (is (= "<rss><channel><title>A &amp; B &#160;</title></channel></rss>" xml)))))

(deftest entities
  (is (= "it's & … A  x &bogus;" (quirks/decode-entities "it&#39;s &amp; &hellip; &#x41; &nbsp;x &bogus;"))))

(deftest namespace-variants
  (is (= :podcast (quirks/uri->alias "HTTP://podcastindex.org/namespace/1.0/")))
  (is (= :psc (quirks/uri->alias "http://podlove.org/simple-chapters/")))
  (is (= :media (quirks/uri->alias "http://www.rssboard.org/media-rss")))
  (is (nil? (quirks/uri->alias "https://acme.example"))))

(deftest normalizations
  (let [tree (quirks/normalize
              (xml/parse
               "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel>
                  <itunes:image>https://x/y.jpg</itunes:image><itunes:explicit>Yes</itunes:explicit>
                  <podcast:images srcset=\"https://a/1.jpg 1500w, https://a/2.jpg 600w\"/>
                  <podcast:locked>false</podcast:locked>
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
             :quirk/yes-no-value :quirk/item-image :quirk/attribute-name}
           (set ids)))
    (let [f    (feed/parse tree)
          item (first (:rss/items f))]
      (is (= {:href "https://x/y.jpg"} (:itunes/image f)))
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
                   (podcast/parse @quirky false))))))
