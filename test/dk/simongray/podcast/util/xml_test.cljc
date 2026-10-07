(ns dk.simongray.podcast.util.xml-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.xml :as xml]))

(def sample
  "<?xml version=\"1.0\"?><!-- c --><rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"
   xmlns=\"http://www.w3.org/2005/Atom\" xmlns:acme=\"https://acme.example\" version=\"2.0\">
   <channel><title>T &amp; U</title><itunes:author>A</itunes:author><acme:thing acme:mode=\"on\"/>
   <description><![CDATA[<p>Hi</p>]]> tail</description></channel></rss>")

(def itunes
  "The namespace of the iTunes tags, which sample uses."
  "http://www.itunes.com/dtds/podcast-1.0.dtd")

(def undeclared
  "A prefix used without a declaration: fine for the non-namespace-aware JVM
  parser, an error for a browser's DOMParser, so only the JVM sees it."
  "<rss version=\"2.0\"><channel><foo:bar x=\"1\"/></channel></rss>")

(deftest raw-parsing
  (let [raw (xml/parse-raw sample)]
    (is (= "rss" (first raw)))
    (is (= {"xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"
            "xmlns" "http://www.w3.org/2005/Atom"
            "xmlns:acme" "https://acme.example"
            "version" "2.0"}
           (second raw)))
    (is (= ["title" {} "T & U"] (-> raw (nth 2) (nth 2))))
    (testing "CDATA and text merge, comments vanish"
      (is (= ["description" {} "<p>Hi</p> tail"] (-> raw (nth 2) (nth 5))))))
  (testing "a space between two elements on one line is kept, layout is not"
    (is (= ["p" {} ["b" {} "Hello"] " " ["i" {} "world"]] (xml/parse-raw "<p><b>Hello</b> <i>world</i></p>")))
    (is (= ["p" {} ["b" {} "Hello"] ["i" {} "world"]] (xml/parse-raw "<p>\n  <b>Hello</b>\n  <i>world</i>\n</p>")))))

(deftest namespace-resolution
  (let [tree (xml/parse sample {itunes :itunes})]
    (is (= [:rss {:version "2.0"}] (subvec tree 0 2)))
    (is (= [:itunes/author {} "A"] (-> tree (nth 2) (nth 3))) "by the alias of the table")
    (testing "unprefixed elements are RSS whatever the default namespace"
      (is (= [:title {} "T & U"] (-> tree (nth 2) (nth 2)))))
    (testing "unknown URIs keep their prefix as alias"
      (is (= [:acme/thing {:acme/mode "on"}] (-> tree (nth 2) (nth 4)))))
    (is (= #{{:id :xml/default-namespace :level :info :uri "http://www.w3.org/2005/Atom"}
             {:id :xml/unknown-namespace :level :info :prefix "acme" :uri "https://acme.example"}}
           (set (xml/report tree))))
    (is (= {:acme "https://acme.example"} (xml/foreign-namespaces tree))))
  (testing "without a table, every prefix keeps its own alias"
    (let [tree (xml/parse sample)]
      (is (= [:itunes/author {} "A"] (-> tree (nth 2) (nth 3))))
      (is (= {:itunes itunes :acme "https://acme.example"} (xml/foreign-namespaces tree))))))

#?(:clj
   (deftest undeclared-prefixes-survive-on-the-jvm
     (let [tree (xml/parse undeclared)]
       (is (= [:foo/bar {:x "1"}] (-> tree (nth 2) (nth 2))))
       (is (= [{:id :xml/undeclared-prefix :level :warning :prefix "foo"}]
              (xml/report tree)))
       (is (= {:foo nil} (xml/foreign-namespaces tree)) "recorded with no URI")
       (is (not (re-find #"foo" (xml/emit tree))) "so that emit leaves it out"))))

(deftest decoding-a-document
  (let [doc    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>Blåbær</t>"
        latin  (bytes/of (map #?(:clj int :cljs #(.charCodeAt % 0)) doc))
        utf-8  (bytes/encode (str/replace doc "ISO-8859-1" "UTF-8"))]
    (is (= doc (xml/decode latin)) "by the declaration")
    (is (= (str/replace doc "ISO-8859-1" "UTF-8") (xml/decode utf-8)))
    (is (= (str/replace doc "ISO-8859-1" "UTF-8") (xml/decode utf-8 "UTF-8")) "by the charset given")
    (is (= "<a/>" (xml/decode (bytes/of [0xef 0xbb 0xbf 0x3c 0x61 0x2f 0x3e]) "ISO-8859-1"))
        "by the byte order mark first, which is left out")
    (is (= "<a/>" (xml/decode (bytes/encode "<a/>") "nonsense")) "an unknown charset counts as UTF-8")))

(deftest helpers
  (let [el [:a {:x "1"} "t1" [:b {} "inner"] "t2" [:c {}] [:b {} "again"]]]
    (is (= :a (xml/tag el)))
    (is (= {:x "1"} (xml/attrs el)))
    (is (= "t1t2" (xml/text el)))
    (is (= 3 (count (xml/elements el))))
    (is (= 2 (count (xml/elements el :b))))
    (is (= [:b {} "inner"] (xml/element el :b))))
  (is (= "Show" (xml/trim "\u00A0 Show\t\u00A0")) "no-break spaces are white space on both platforms")
  (is (= "a b" (xml/trim "a b"))))

(deftest emitting
  (testing "declarations for the aliases used, foreign ones included"
    (let [out (xml/emit (xml/parse sample))]
      (is (re-find #"xmlns:acme=\"https://acme.example\"" out))
      (is (re-find #"xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"" out))
      (is (not (re-find #"xmlns=\"" out)))))
  (testing "escaping and CDATA"
    (is (re-find #"<title>T &amp; U</title>" (xml/emit [:rss {} [:title {} "T & U"]])))
    (is (re-find #"<!\[CDATA\[<p>Hi</p>\]\]>" (xml/emit [:rss {} [:d {} "<p>Hi</p>"]])))
    (is (re-find #"x=\"a &quot;q&quot; &amp; b\"" (xml/emit [:rss {:x "a \"q\" & b"}]))))
  (testing "an unknown alias without a URI is an error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (xml/emit [:rss {} [:foo/bar {}]]))))
  (testing "round trip"
    (let [tree (xml/parse "<rss version=\"2.0\"><channel><title>A</title><item><guid isPermaLink=\"false\">x</guid></item></channel></rss>")]
      (is (= tree (xml/parse (xml/emit tree))))))
  (testing "whitespace a parser would normalize goes out as character references"
    (let [tree [:rss {:x "a\nb\tc\rd"} [:t {} "line\r\nnext <b>"]]
          out  (xml/emit tree)]
      (is (re-find #"x=\"a&#10;b&#9;c&#13;d\"" out))
      (is (re-find #"<t>line&#13;\nnext &lt;b&gt;</t>" out))
      (is (= tree (xml/parse out)))))
  (testing "inner-xml escapes text for HTML, and never wraps it in CDATA"
    (is (= "Use &lt;pre&gt; &amp; <b>x &lt; y</b>"
           (xml/inner-xml [:description {} "Use <pre> & " [:b {} "x < y"]]))))
  (testing "an alias mapped to nil has no URI, and its elements and attributes are left out"
    (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss>\n  <t/>\n</rss>\n"
           (xml/emit [:rss {} [:t {:fb/x "1"}] [:fb/link {} "x"]] {:fb nil}))))
  (testing "mixed content is written as it is, so that a round trip keeps it"
    (let [tree (xml/parse "<rss><x>Hello <b>big</b> <i>world</i>!</x></rss>")]
      (is (= tree (xml/parse (xml/emit tree))))
      (is (= tree (xml/parse (xml/emit (xml/parse (xml/emit tree)))))))))

#?(:clj
   (deftest long-text-nodes-on-the-jvm
     (let [s (apply str (repeat 100001 "x"))]
       (is (= s (xml/text (xml/parse (str "<a>" s "</a>"))))))))

(deftest malformed
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Malformed XML"
                        (xml/parse "<rss><channel></rss>")))
  (is (= ::xml/malformed
         (try (xml/parse "nope")
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                (:type (ex-data e))))))
  (testing "markup or text after the root is an error, a comment is not"
    (doseq [s ["<rss/><b>Warning</b>" "<rss/>junk"]]
      (is (= ::xml/malformed
             (try (xml/parse s)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                    (:type (ex-data e)))))
          s))
    (is (= [:rss {}] (xml/parse "<rss/>\n<!-- served in 0.2 s -->\n"))))
  #?(:clj
     (testing "and so is an XML declaration the parser does not read"
       (is (= ::xml/malformed
              (try (xml/parse "<?xml version=\"2.0\"?><rss/>")
                   (catch clojure.lang.ExceptionInfo e
                     (:type (ex-data e)))))))))

(deftest bom
  (is (= [:a {}] (xml/parse "﻿<a/>"))))

(deftest nesting-depth
  (let [nested (fn [n] (str (apply str (repeat n "<a>")) (apply str (repeat n "</a>"))))
        depth  (fn depth [x] (if (vector? x) (inc (reduce max 0 (map depth (xml/elements x)))) 0))]
    (testing "a document nested as deep as max-depth is read"
      (is (= xml/max-depth (depth (xml/parse (nested xml/max-depth))))))
    (testing "a deeper one is malformed, before any walk of it can overflow the stack"
      (doseq [n [(inc xml/max-depth) 10000]]
        (is (= ::xml/malformed
               (try (xml/parse (nested n)) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                      (:type (ex-data e))))))))))
