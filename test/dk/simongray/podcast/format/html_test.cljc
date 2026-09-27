(ns dk.simongray.podcast.format.html-test
  "Embedded HTML as Hiccup, sanitized, and rendered as text and Markdown."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.format.html :as html]
            #?(:clj [dk.simongray.podcast.test-support :refer [corpus-feeds]])))

(deftest parsing-the-way-a-browser-does
  (testing "elements, attributes and entities"
    (is (= [[:p {:class "x"} "Hello " [:b {} "world"] " & co"]]
           (html/parse "<P class=x>Hello <b>world</b> &amp; co</p>")))
    (is (= [[:a {:href "https://x/?a=1&b=2" :title "It's" :download true} "link"]]
           (html/parse "<a href=\"https://x/?a=1&amp;b=2\" title='It&#39;s' download>link</a>"))))
  (testing "void elements, with or without a slash"
    (is (= [[:p {} "one" [:br {}] "two"] [:hr {}] [:img {:src "i.png" :alt ""}]]
           (html/parse "<p>one<br/>two<hr><img src=i.png alt=\"\">"))))
  (testing "unclosed paragraphs and list items close where HTML says"
    (is (= [[:p {} "a"] [:p {} "b"] [:div {} "c"]]
           (html/parse "<p>a<p>b<div>c")))
    (is (= [[:ul {} [:li {} "one"] [:li {} "two" [:ul {} [:li {} "nested"]]] [:li {} "three"]]]
           (html/parse "<ul><li>one<li>two<ul><li>nested</ul><li>three</ul>"))))
  (testing "misnested inline elements close through the nearest match"
    (is (= [[:b {} [:i {} "x"]] " y"]
           (html/parse "<b><i>x</b> y</i>"))))
  (testing "comments, declarations and word-processor tags"
    (is (= [[:p {} "text"]]
           (html/parse "<!DOCTYPE html><!-- note --><p>text</p>")))
    (is (= [[:o:p {} "word"]] (html/parse "<o:p>word</o:p>"))))
  (testing "scripts keep their content as text"
    (is (= [[:script {} "if (a < b) { x() }"]]
           (html/parse "<script>if (a < b) { x() }</script>"))))
  (testing "text alone is text"
    (is (= ["just words"] (html/parse "just words")))
    (is (= [] (html/parse ""))))
  (testing "the result is a sequence, which every Hiccup renderer takes as a fragment"
    (is (seq? (html/parse "<p>a</p><p>b</p>")))
    (is (seq? (html/hiccup "plain")))))

(deftest sanitizing
  (let [dirty (str "<p onclick=\"evil()\" style=\"x\">Hi <a href=\"javascript:alert(1)\" target=\"_blank\">bad</a> "
                   "<a href=\"https://ok/\" rel=\"nofollow\" class=\"c\">good</a> <a href=\"/relative\">rel</a></p> "
                   "<script>alert(1)</script><iframe src=\"x\"></iframe><font color=red>plain</font> "
                   "<ul>\n  <li>one</li>\n  <li>two</li>\n</ul><img src=\"data:image/png;base64,AAAA\" alt=\"pic\">")]
    (is (= [[:p {} "Hi " [:a {} "bad"] " " [:a {:href "https://ok/" :rel "nofollow"} "good"] " " [:a {:href "/relative"} "rel"]]
            " " "plain" " "
            [:ul {} [:li {} "one"] [:li {} "two"]]
            [:img {:alt "pic"}]]
           (html/sanitize (html/parse dirty))))
    (is (= [[:dfn {:title "term"} "x"]]
           (html/sanitize (html/parse "<dfn title=\"term\" class=\"c\">x</dfn>"))))))

(deftest hiccup-for-a-client
  (testing "markup is parsed and sanitized"
    (is (= [[:p {} "Hi " [:b {} "there"]]] (html/hiccup "<p>Hi <b>there</b><script>x</script></p>"))))
  (testing "plain text becomes paragraphs with breaks"
    (is (= [[:p {} "Line one" [:br {}] "line two"] [:p {} "Second & last"]]
           (html/hiccup "Line one\nline two\n\n\nSecond &amp; last\n"))))
  (is (= [] (html/hiccup nil)))
  (is (= [] (html/hiccup "   "))))

(deftest rendering-text
  (let [notes "<h2>Show  notes</h2><p>Hello <a href=\"https://x/\">world</a>,<br>welcome.</p>
               <ul><li>one</li><li>two <b>bold</b></li></ul><ol><li>first</li><li>second</li></ol><p>Bye <img src=\"a.png\" alt=\"[art]\"></p>"]
    (is (= "Show notes\n\nHello world,\nwelcome.\n\n- one\n- two bold\n\n1. first\n2. second\n\nBye [art]"
           (html/text notes)))
    (is (str/includes? (html/text notes {:links? true}) "world (https://x/)"))
    (is (= "a\n\nb" (html/text [[:p {} "a"] [:p {} "b"]])))
    (is (= "keep   this" (html/text "<pre>keep   this</pre>")))
    (is (= "plain text" (html/text "plain text")))))

(deftest rendering-markdown
  (is (= "## Notes\n\nHello **world** and *you*, [link](https://x/)\\\nnext line.\n\n- one\n- two\n\n1. first\n2. second\n\n> quoted\\\n> lines\n\n---\n\n```\ncode block\n```\n\n![art](a.png) `x`"
         (html/markdown "<h2>Notes</h2><p>Hello <strong>world</strong> and <em>you</em>, <a href=\"https://x/\">link</a><br>next line.</p>
                         <ul><li>one</li><li>two</li></ul><ol><li>first</li><li>second</li></ol>
                         <blockquote>quoted<br>lines</blockquote><hr><pre>code block</pre><p><img src=\"a.png\" alt=\"art\"> <code>x</code></p>"))))

(deftest emitting-html
  (is (= "<p class=\"c\">a &amp; b<br><a href=\"https://x/?a=1&amp;b=2\">l</a></p><input disabled>"
         (html/emit [[:p {:class "c"} "a & b" [:br {}] [:a {:href "https://x/?a=1&b=2"} "l"]] [:input {:disabled true}]])))
  (testing "sanitized Hiccup survives a trip through HTML"
    (let [nodes (html/sanitize (html/parse "<p>Hi <a href=\"https://x/\">there</a><br>you</p><ul><li>one</li></ul>"))]
      (is (= nodes (html/parse (html/emit nodes)))))))

#?(:clj
   (deftest descriptions-in-the-corpus-render
     (testing "every description of every corpus feed, when the corpus is there"
       (doseq [{:keys [name feed]} @corpus-feeds
               item (take 20 (:rss/items feed))
               k    [:rss/description :content/encoded :itunes/summary]
               :let [s (get item k)]
               :when (and (string? s) (re-find #"\w" s))]
         (let [nodes (html/hiccup s)]
           (is (seq? nodes) name)
           (is (re-find #"\w" (html/text nodes)) (str name " " k))
           (is (string? (html/markdown nodes))))))))
