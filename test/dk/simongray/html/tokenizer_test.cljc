(ns dk.simongray.html.tokenizer-test
  "The tokenizer on what the tests of html5lib leave out, which run without
  a tree builder: the raw text after the start tags of HTML content."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.html.tokenizer :as tokenizer]))

(defn- start
  [name]
  {:type :start-tag :name name :attrs {} :self-closing? false})

(defn- end
  [name]
  {:type :end-tag :name name})

(deftest raw-text-after-its-start-tag
  (is (= [(start "script") "if (a<b) x = '</p>'" (end "script") "done"]
         (tokenizer/tokens "<script>if (a<b) x = '</p>'</script>done"))
      "a script runs to its own end tag")
  (is (= [(start "title") "Fish & <b>chips</b>" (end "title")]
         (tokenizer/tokens "<title>Fish &amp; <b>chips</b></title>"))
      "a title decodes references but reads no tags")
  (is (= [(start "style") "a &amp; b" (end "style") "x"]
         (tokenizer/tokens "<style>a &amp; b</STYLE >x"))
      "a style decodes nothing, and its end tag can have any case and space")
  (is (= [(start "plaintext") "</plaintext><b>"]
         (tokenizer/tokens "<plaintext></plaintext><b>"))
      "plaintext never ends")
  (is (= [(start "script") (start "b")]
         (tokenizer/tokens "<script><b>" {:text-states {}}))
      "without text states, the tokens of a tag follow"))

(deftest references-in-text-and-attributes
  (is (= [{:type :start-tag :name "a" :attrs {"href" "?a=1&copy=2&b"} :self-closing? false}
          "© ¬it; ∉"]
         (tokenizer/tokens "<a href='?a=1&copy=2&amp;b'>&copy &notit; &notin;"))
      "a legacy name before = stays text in an attribute, and the longest name wins"))
