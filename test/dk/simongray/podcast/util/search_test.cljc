(ns dk.simongray.podcast.util.search-test
  "The full-text index, the query language, the ranking by BM25F and the
  snippets of a text."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.search :as search]))

(def docs
  [{:id     1
    :stored {:title "Clojure…"}
    :fields {:title       "Clojure and the value of simplicity"
             :description "A talk about data."}}
   {:id     2
    :fields {:title       "Rust"
             :description (str "Systems programming, and a little Clojure "
                               "for the value for value era.")}}
   {:id     3
    :fields {:title "Gardening" :description "Tomatoes."}}])

(def idx
  (search/index docs {:boosts {:title 3}}))

(defn- ids
  ([q]
   (ids q {}))
  ([q opts]
   (mapv :id (search/query idx q opts))))

(deftest the-index
  (testing "every word must occur; a word in the title weighs more"
    (is (= [1 2] (ids "clojure")))
    (is (= [1 2] (ids "clojure value")))
    (is (= [] (ids "clojure tomatoes")))
    (is (= #{1 2 3} (set (ids "clojure tomatoes" {:operator :or}))))
    (is (= [] (ids "")))
    (is (= [] (ids "   "))))
  (testing "the last word as a prefix, unless the query ends in a space"
    (is (= [1 2] (ids "cloj")))
    (is (= [] (ids "cloj ")))
    (is (= [] (ids "cloj" {:prefix? false})))
    (is (= [2] (ids "systems cloj")) "only the last word")
    (is (= [] (ids "clo" {:min-prefix 4})) "too short a word to complete")
    (is (= [1 2] (ids "cloj" {:min-prefix 4}))))
  (testing "words in quotes must occur in a row"
    (is (= [2] (ids "\"value for value\"")))
    (is (= [1 2] (ids "value")))
    (is (= [1] (ids "\"value of\"")))
    (is (= [2] (ids "value-for-value")) "a word with punctuation in it")
    (is (= [2] (ids "value-for-va")) "the last of its words as a prefix"))
  (testing "what is stored comes back; limit and filter apply"
    (is (= {:title "Clojure…"} (:stored (first (search/query idx "clojure")))))
    (is (nil? (:stored (second (search/query idx "clojure")))))
    (is (= 1 (count (search/query idx "clojure" {:limit 1}))))
    (is (= [2] (ids "clojure" {:filter #(= 2 (:id %))}))))
  (testing "the ranking is an option"
    (is (= [2] (ids "clojure" {:boosts {:title 0 :description 1}}))
        "a field boosted by zero does not match")
    (is (= [2 1] (ids "clojure" {:rank (fn [r] (- (:id r)))}))
        "a rank function orders the results instead")
    (is (= (ids "clojure") (ids "clojure" {:k1 1.2 :b 0.75})) "the defaults")
    (is (not= (:score (first (search/query idx "clojure")))
              (:score (first (search/query idx "clojure" {:k1 2.0 :b 0.5})))))
    (is (= (take 1 (ids "clojure")) (ids "clojure" {:limit 1}))
        "a limit keeps the head of the full order"))
  (testing "documents replaced and removed"
    (let [idx (search/add idx {:id 1 :fields {:title "Haskell"}})]
      (is (= [2] (mapv :id (search/query idx "clojure"))))
      (is (= [1] (mapv :id (search/query idx "haskell"))))
      (is (= 3 (count (:docs idx))))
      (let [idx (search/remove idx 1)]
        (is (= 2 (count (:docs idx))))
        (is (= [] (search/query idx "haskell")))
        (is (= [2] (mapv :id (search/query idx "clojure")))))
      (is (= idx (search/remove idx :nobody)))))
  (testing "fields and queries given as terms are taken as is"
    (let [idx (search/index [{:id :a :fields {:body ["kaffe" "te"]}}])]
      (is (= [:a] (mapv :id (search/query idx ["kaffe"]))))
      (is (= [:a] (mapv :id (search/query idx "Kaffe"))))
      (is (= [] (search/query idx ["Kaffe"])) "no analysis, so no folding")))
  (testing "but the strings of a set or a list are analyzed each"
    (let [idx (search/index [{:id :a :fields {:keywords #{"Café" "Politics"}}}])]
      (is (= [:a] (mapv :id (search/query idx "cafe"))))))
  (testing "an index printed as EDN and read back"
    (let [thawed (edn/read-string (pr-str idx))]
      (is (= [1 2] (mapv :id (search/query thawed "cloj")))
          "its terms come back unsorted, and a prefix scans them")
      (is (sorted? (:terms (search/restore thawed))))
      (is (= [1 2] (mapv :id (search/query (search/restore thawed) "cloj"))))))
  (testing "every kind of white space is one, on both platforms"
    (is (= [2] (ids "value\u00A0for\u3000value"))
        "no-break and ideographic spaces"))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (search/add idx {:fields {:title "no id"}}))))

(deftest parsing
  (let [fields {"title" :title "notes" :description}
        parse  #(search/parse % {:fields fields})]
    (is (= {:and [{:term "value"} {:term "for"} {:term "va" :prefix? true}]}
           (parse "value for va")))
    (is (= {:and [{:phrase ["value" "for" "value"]} {:not {:term "rerun"}}]}
           (parse "\"value for value\" -rerun ")))
    (is (= {:and [{:term "pod" :prefix? true} {:term "network"}]}
           (parse "pod* network ")))
    (is (= {:or [{:and [{:term "a"} {:term "b"}]} {:term "c"}]}
           (parse "a b OR c ")) "AND binds tighter than OR")
    (is (= {:and [{:term "a"} {:or [{:term "b"} {:term "c"}]}]}
           (parse "a (b | c) ")))
    (is (= {:and [{:term "a"} {:not {:or [{:term "b"} {:term "c"}]}}]}
           (parse "a !(b OR c) ")))
    (is (= {:and [{:term "clojure" :field :title}
                  {:phrase ["x" "y"] :field :description}]}
           (parse "title:clojure notes=\"x y\"")))
    (is (= {:or [{:term "a" :field :title} {:term "b" :field :title}]}
           (parse "Title:(a | b)")) "field names are folded")
    (is (= {:phrase ["re" "invent"]} (parse "re:invent "))
        "a name that is no field is a word")
    (is (= {:and [{:term "part"} {:term "1"} {:term "intro"}]}
           (parse "Part 1 - Intro ")) "a dash on its own is no operator")
    (is (= {:and [{:term "a"} {:not {:phrase ["b" "c"]}}]}
           (parse "a -\"b c\"")) "a minus right before a phrase")
    (is (= {:and [{:term "at"} {:term "t"}]} (parse "AT&T ")))
    (is (= {:phrase ["天气" "气很" "很好"]} (parse "天气很好 "))
        "the pairs of a run of Chinese in a row")
    (is (= {:term "好" :prefix? true} (parse "好 "))
        "one character of Chinese is a prefix")
    (testing "nothing is an error"
      (is (= {:phrase ["value" "for"]} (parse "\"value for")))
      (is (nil? (parse "-")))
      (is (nil? (parse "NOT")))
      (is (= {:term "a"} (parse "a OR ")))
      (is (= {:term "a"} (parse ") a (")))
      (is (= {:term "a"} (parse "AND a ")))
      (is (nil? (parse ""))))
    (testing "the spellings and the operator between words are options"
      (is (= {:and [{:term "x"} {:term "y"}]}
             (search/parse "x OG y " {:syntax {:and #{"OG" "&"}}})))
      (is (= {:and [{:term "x"} {:term "og"} {:term "y"}]}
             (search/parse "x OG y ")))
      (is (= {:and [{:term "x"} {:not {:term "y"}}]}
             (search/parse "x IKKE y " {:syntax {:not #{"IKKE"}}})))
      (is (= {:or [{:term "a"} {:and [{:term "b"} {:term "c"}]}]}
             (search/parse "a b AND c " {:operator :or}))))))

(deftest operators
  (is (= [3 1 2] (ids "clojure | tomatoes")))
  (is (= [1] (ids "clojure -rust")))
  (is (= [1] (ids "clojure NOT rust")))
  (is (= [1] (ids "clojure !\"value for value\"")))
  (is (= [2] (ids "(rust | gardening) -tomatoes")))
  (is (= [] (ids "-clojure")) "only exclusions find nothing")
  (is (= [1] (ids "title:clojure")))
  (is (= [2] (ids "description:clojure")))
  (is (= #{2 3} (set (ids "title:(rust | gardening)"))))
  (is (= [2] (ids "description:clojure" {:boosts {:description 0}}))
      "a field that a query names counts although its boost is 0")
  (let [idx (search/index docs {:aliases {"notes" :description}})]
    (is (= [2] (mapv :id (search/query idx "notes:clojure"))))))

(deftest bm25f
  (let [idx   (search/index [{:id 1 :fields {:a "x y" :b "x y" :c "x y"}}
                             {:id 2 :fields {:a "x x" :b "y y" :c "x y"}}
                             {:id 3 :fields {:a "z z" :b "z z" :c "z z"}}])
        score (fn [q id]
                (some #(when (= id (:id %)) (:score %)) (search/query idx q)))]
    (is (< (Math/abs (- (score "x" 1) (score "x" 2))) 1e-9)
        "a word once in three fields counts as much as three times in two"))
  (let [idx   (search/index (concat (for [i (range 1 6)]
                                      {:id i :fields {:title "clojure"}})
                                    [{:id 6 :fields {:title "clojurians"}}]))
        score (fn [id]
                (some #(when (= id (:id %)) (:score %))
                      (search/query idx "cloj")))]
    (is (= (score 1) (score 6))
        "a prefix counts as one term, so the rarest completion doesn't win")))

(deftest one-text
  (let [text "A little Clojure for the value for value era"]
    (is (search/matches? text "clojure value"))
    (is (search/matches? text "\"value for value\" -rust"))
    (is (search/matches? text "rust | clojure"))
    (is (search/matches? text "clo") "the last word as a prefix")
    (is (not (search/matches? text "clo ")))
    (is (not (search/matches? text "clojure -little")))
    (is (not (search/matches? text "\"value for era\"")))
    (is (not (search/matches? text "-rust")) "a :not alone holds for nothing")
    (is (search/matches? text {:or [{:term "rust"} {:term "era"}]}))
    (is (search/matches? "今天天气很好" "天气"))))

(deftest scripts-without-spaces
  (let [idx (search/index [{:id 1 :fields {:title "[HSK 3] 爸爸不在家的中秋节"}}
                           {:id 2 :fields {:title "八百八的月饼礼盒值不值"}}
                           {:id 3 :fields {:title "第13回 本番データ消去、AIメモリ管理 ほか"}}
                           {:id 4 :fields {:title "今天天气很好 good"}}])
        ids (fn [q] (mapv :id (search/query idx q)))]
    (is (= [1] (ids "中秋")) "a word inside a sentence")
    (is (= [2] (ids "月饼")))
    (is (= [1] (ids "中秋节")) "the pairs of a longer word in a row")
    (is (= [3] (ids "データ")))
    (is (= [3] (ids "メモリ 管理")))
    (is (= [4] (ids "好")) "one character, the end of a run")
    (is (= [1] (ids "爸")) "one character, completed as a prefix")
    (is (= [4] (ids "\"天气很好 good\"")) "a phrase across a run and a word")
    (is (= [] (ids "\"很好天气\"")))))

(deftest limits-and-ties
  (let [idx (search/index (for [i (range 200)]
                            {:id i :fields {:title "same words"}}))]
    (is (= (range 10) (mapv :id (search/query idx "same" {:limit 10})))
        "equal scores in the order of numeric ids")
    (is (= 200 (count (search/query idx "same"
                                    {:limit #?(:clj  Long/MAX_VALUE
                                               :cljs js/Number.MAX_SAFE_INTEGER)}))))
    (is (= (range 150) (mapv :id (search/query idx "same" {:limit 150})))
        "a limit near the count sorts all")))

(deftest snippets
  (let [text "The quick brown fox jumps over the lazy dog, and the fox is quick."]
    (is (= [{:text "The quick brown "} {:text "fox" :match? true}
            {:text " jumps over the lazy dog, and the "} {:text "fox" :match? true}
            {:text " is quick."}]
           (search/snippet text "fox")))
    (is (= [{:text "…"} {:text "over the "} {:text "lazy" :match? true}
            {:text " dog, and the fox"} {:text "…"}]
           (search/snippet text "lazy" {:width 30}))
        "a window around the match, cut at word boundaries")
    (is (= (search/snippet text "fox") (search/snippet text "fo"))
        "the last word as a prefix")
    (is (= [{:text text}] (search/snippet text "cat"))
        "no match, the start of the text")
    (is (= [{:text "The quick brown fox jumps over the "} {:text "lazy" :match? true}
            {:text " dog, and the fox is quick."}]
           (search/snippet text "lazy -fox"))
        "a word under a :not is no match"))
  (is (= [{:text "Café"}] (search/snippet "Café" "tea")))
  (is (= [] (search/snippet nil "tea")) "no text, no parts")
  (is (= [{:text "Café" :match? true}] (search/snippet "Café" "cafe"))
      "folded like the index")
  (is (= [{:text "…"} {:text "supercalifragilistic" :match? true} {:text "…"}]
         (search/snippet "see supercalifragilistic word" "super" {:width 8}))
      "a match wider than the window is shown whole")
  (is (= [{:text "A little "} {:text "value for value" :match? true}
          {:text " era, and value"}]
         (search/snippet "A little value for value era, and value"
                         "\"value for value\""))
      "a phrase is one match")
  (is (= [{:text "…"} {:text "then "} {:text "rust" :match? true}
          {:text " and "} {:text "clojure" :match? true} {:text "…"}]
         (search/snippet "clojure alone here, and then rust and clojure together"
                         "clojure rust"
                         {:width 25}))
      "the window that shows the most of the query wins")
  (is (= [{:text "今天"} {:text "天气很好" :match? true} {:text " good"}]
         (search/snippet "今天天气很好 good" "天气很好"))
      "the pairs of characters are one match"))
