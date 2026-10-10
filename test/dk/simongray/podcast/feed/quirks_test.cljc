(ns dk.simongray.podcast.feed.quirks-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.quirks :as quirks]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.qname-hiccup :as xml]))

(def quirky
  (delay (fixture "feeds/quirky.xml")))

(deftest namespace-uris-with-long-runs-of-slashes
  (testing "a trailing run is dropped for the lookup, an inner one kept"
    (is (= :podcast (quirks/uri->alias "https://podcastindex.org/namespace/1.0///")))
    (let [slashes (apply str (repeat 100000 "/"))]
      (is (= (str "acme.example" slashes "ns") (quirks/uri-key (str "https://acme.example" slashes "ns"))))))
  (testing "a feed that uses such a URI for many names parses in linear time"
    (let [uri  (str "https://acme.example" (apply str (repeat 20000 "/")) "ns")
          feed (rss/parse (str "<rss version=\"2.0\" xmlns:acme=\"" uri "\"><channel><title>T</title>"
                                   (apply str (repeat 500 "<acme:x>1</acme:x>"))
                                   "</channel></rss>"))]
      (is (= 500 (count (:xml/unknown feed)))))))

(deftest leaving-normalizations-out
  (testing "normalize skips the rules it is told to, through parse-tree"
    (let [xml      "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><itunes:explicit>Yes</itunes:explicit></channel></rss>"
          explicit (fn [opts]
                     (let [tree (rss/parse-tree xml opts)]
                       [(xml/text (xml/element (xml/element tree :channel) :itunes/explicit))
                        (set (map :id (rss/report tree)))]))]
      (is (= ["true" #{:quirk/explicit-value}] (explicit {})))
      (is (= ["Yes" #{}] (explicit {:except #{:quirk/explicit-value}}))))))

(deftest namespace-variants
  (is (= :podcast (quirks/uri->alias "HTTP://podcastindex.org/namespace/1.0/")))
  (is (= :psc (quirks/uri->alias "http://podlove.org/simple-chapters/")))
  (is (= :media (quirks/uri->alias "http://www.rssboard.org/media-rss")))
  (is (nil? (quirks/uri->alias "https://acme.example"))))

(deftest srcset-as-a-browser-reads-it
  (let [images (fn [srcset]
                 (->> (rss/parse (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>"
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
  (let [f    (rss/parse @quirky)
        ids  (frequencies (map :id (rss/report f)))
        item (first (:rss/items f))]
    (testing "the document was repaired before it could be parsed"
      (is (= 1 (:repair/leading-junk ids)))
      (is (= 1 (:repair/undeclared-prefixes ids))))
    (testing "namespaces"
      (is (= 2 (:quirk/namespace-variant ids)))
      (is (= 1 (:quirk/rss-default-namespace ids)))
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
      (let [out (rss/emit f)]
        (is (re-find #"xmlns:acme=\"https://acme.example/rss-extensions\"" out))
        (is (= f (rss/parse out)))))
    (testing "the strict path refuses the document"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (rss/parse @quirky {:quirks? false}))))
    (testing "without the quirk, the Atom default namespace makes it no RSS"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (rss/parse @quirky {:except #{:quirk/rss-default-namespace}}))))))

(deftest default-namespaces-below-rss
  (let [f (rss/parse (str "<rss version=\"2.0\"><channel><title>T</title>"
                          "<link>https://a.example/</link>"
                          "<link xmlns=\"http://www.w3.org/2005/Atom\" rel=\"self\""
                          " href=\"https://a.example/feed.xml\"/>"
                          "<site xmlns=\"com-wordpress:feed-additions:1\">7</site>"
                          "</channel></rss>"))]
    (testing "an Atom link in the default namespace is an Atom link, not the RSS link"
      (is (= "https://a.example/" (:rss/link f)))
      (is (= [{:rel "self" :href "https://a.example/feed.xml"}] (:atom/links f))))
    (testing "an unknown one stays on its element, and is written back"
      (is (= [[:site {:xmlns "com-wordpress:feed-additions:1"} "7"]] (:xml/unknown f)))
      (is (re-find #"<site xmlns=\"com-wordpress:feed-additions:1\">7</site>" (rss/emit f))))))
