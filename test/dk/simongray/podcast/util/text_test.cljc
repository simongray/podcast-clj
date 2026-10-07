(ns dk.simongray.podcast.util.text-test
  "Folding, words, and the terms of a text by position, with the scripts
  that have no spaces between words in pairs of characters."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.text :as text]))

(deftest folding
  (is (= "cafe o strasse arhus" (text/fold "Café Ø Straße Århus")))
  (is (= "podcast film" (text/fold "Ｐｏｄｃａｓｔ ﬁlm"))
      "fullwidth letters and ligatures, by compatibility")
  (is (= "χαοσ" (text/fold "ΧΑΟΣ")) "final sigma is sigma")
  (is (= "istanbul lodz ss" (text/fold "İstanbul Łódź ẞ")))
  (is (= "ガ" (text/fold "ｶﾞ")) "halfwidth Katakana with its sound mark")
  (is (= "が" (text/fold "が")) "a kana keeps its sound mark")
  (is (= "한국어" (text/fold "한국어")) "Hangul syllables stay whole")
  (is (= "information" (text/fold "infor\u00ADmation")) "a soft hyphen")
  (is (= "" (text/fold nil))))

(deftest words
  (is (= ["Simon" "s" "Podcast" "2" "0" "comedy" "news"]
         (text/words "Simon's Podcast 2.0 comedy,news"))
      "punctuation splits words")
  (is (= ["AI" "メモリ管理" "abc" "今天"] (text/words "AIメモリ管理 abc今天"))
      "a change of script to or from one without spaces splits a word")
  (is (= ["ok"] (text/words "\u2764\uFE0F \uD83D\uDC68\u200D\uD83D\uDC69 ok"))
      "what folds to nothing is no word")
  (is (= [] (text/words nil))))

(deftest tokens
  (is (= ["podcasting" "2" "0" "the" "value" "for" "value" "era"]
         (text/tokens "Podcasting 2.0: the value-for-value era!")))
  (is (= ["爸爸" "爸不" "不在" "在家" "家的" "的中" "中秋" ["秋节" "节"]]
         (text/tokens "爸爸不在家的中秋节"))
      "the pairs of a run, and its last character with the last pair")
  (is (= ["ai" "メモ" "モリ" "リ管" ["管理" "理"]] (text/tokens "AIメモリ管理"))
      "pairs across the scripts of a run")
  (is (= [["天気" "気"]] (text/tokens "天気")))
  (is (= ["好" "ok"] (text/tokens "好 ok")) "a lone character")
  (is (= ["第" "13" "回"] (text/tokens "第13回")) "digits split a run")
  (is (= [["ガギ" "ギ"]] (text/tokens "ｶﾞｷﾞ")))
  (is (= ["학교" "교에" ["에서" "서"]] (text/tokens "학교에서")))
  (testing "Thai, Lao, Khmer and Myanmar, with the marks on their letters"
    (is (= ["ภา" "าษ" "ษา" "าไ" "ไท" ["ทย" "ย"]] (text/tokens "ภาษาไทย")))
    (is (= ["ยง่" "ง่า" ["าย" "ย"]] (text/tokens "ยง่าย")))
    (is (= 3 (count (filter vector? (text/tokens "ພາສາລາວ ភាសាខ្មែរ မြန်မာ"))))
        "a run each"))
  (is (= ["value" "for" "value"]
         (text/tokens "value\u00A0for\u3000value"))
      "every kind of white space")
  (is (= [] (text/tokens nil))))

(deftest spans
  (is (= [{:term "the" :start 0 :end 3 :position 0}
          {:term "cafe" :start 4 :end 8 :position 1}]
         (text/spans "The Café")))
  (is (= [{:term "中秋" :start 0 :end 2 :position 0}
          {:term "秋节" :start 1 :end 3 :position 1}
          {:term "节" :start 2 :end 3 :position 1}
          {:term "ok" :start 4 :end 6 :position 2}]
         (text/spans "中秋节 ok"))
      "overlapping pairs, and the last character at the last pair's position")
  (is (= [{:term "ガ" :start 0 :end 2 :position 0}] (text/spans "ｶﾞ"))
      "a character with its sound mark")
  (testing "the terms of spans are those of tokens"
    (doseq [s ["Podcasting 2.0: the era!" "爸爸不在家的中秋节 - A Mid" "ｶﾞｷﾞ 학교 ภาษา"]]
      (is (= (flatten (text/tokens s)) (map :term (text/spans s))) s))))

(deftest unspaced
  (is (text/unspaced? "中"))
  (is (text/unspaced? "ภา"))
  (is (not (text/unspaced? "a")))
  (is (not (text/unspaced? nil))))
