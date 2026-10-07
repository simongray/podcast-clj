(ns dk.simongray.crdt.rank-test
  "Ranks: a rank between any two, before the first and after the last,
  always in order, never ending in a zero, and short at the ends however
  long the list grows."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.crdt.rank :as rank]))

(deftest between-two-ranks
  (is (= "V" (rank/between nil nil)) "the first rank, in the middle")
  (is (= "W" (rank/between "V" nil)))
  (is (= "U" (rank/between nil "V")))
  (is (= "VV" (rank/between "V" "W")))
  (is (= "VVV" (rank/between "VV" "VW")))
  (is (= "1" (rank/between nil "2")))
  (is (= "0zz" (rank/between nil "1")) "below the lowest digit, the ranks count down in two digits after a 0")
  (is (= "z01" (rank/between "y" nil)) "above the highest, they count in two digits after a z")
  (is (= "1" (rank/between "" "1V")) "a prefix of the upper rank")
  (testing "at an end, a rank is no longer than the numbers of its level"
    (is (= "Y" (rank/between "Xz5V" nil)) "the digits after the number do not count")
    (is (= "V" (rank/between nil "V5")) "and a rank without them is before it")
    (is (= ["zyz" "zz001"] (take 2 (drop 3780 (iterate #(rank/between % nil) "z01")))) "the next level has one more digit")
    (is (= ["01" "00zzz"] (take 2 (drop 3781 (iterate #(rank/between nil %) "0zz"))))))
  (testing "after and before a rank that does not count as they do, such as one of an earlier version"
    (is (= ["z01" "zz501" "zzV01"] (map #(rank/between % nil) ["z" "zz5" "zzV"])))
    (is (= ["0yz" "00yzz"] (map #(rank/between nil %) ["0z" "00z"]))))
  (testing "what leaves no room"
    (is (thrown? #?(:clj Exception :cljs js/Error) (rank/between "W" "V")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (rank/between "V" "V")))))

(deftest what-a-rank-is
  (is (every? rank/rank? ["V" "zz1" "00z"]))
  (is (not-any? rank/rank? ["" "0" "V0" "V!" nil 5]))
  (testing "between refuses what is not a rank, as another device may have written it"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Not a rank" (rank/between "V" "V!")))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Not a rank" (rank/between nil "0")))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Not a rank" (rank/between 5 nil))))
  (testing "and takes \"\" for the bottom"
    (is (= "V" (rank/between "" nil)))))

(defn- in-order?
  [ranks]
  (every? neg? (map compare ranks (rest ranks))))

(deftest the-ends-stay-short
  (let [appended  (vec (take 10000 (iterate #(rank/between % nil) (rank/between nil nil))))
        prepended (vec (take 10000 (iterate #(rank/between nil %) (rank/between nil nil))))]
    (is (in-order? appended))
    (is (in-order? (reverse prepended)))
    (is (every? rank/rank? (concat appended prepended)))
    (is (= [1 3 3 5] (map #(count (nth appended %)) [29 30 3810 9999]))
        "thirty ranks of one digit, and ten thousand appends give five digits")
    (is (= [1 3 3 5] (map #(count (nth prepended %)) [30 31 3811 9999])))
    (is (= 5 (apply max (map count (concat appended prepended)))))))

(deftest between-the-same-two-again-and-again
  (let [inserted (vec (take 100 (iterate #(rank/between "V" %) "W")))]
    (is (in-order? (reverse inserted)) "each between V and the last, so ever closer to V")
    (is (every? #(neg? (compare "V" %)) inserted))
    (is (not-any? #(str/ends-with? % "0") inserted))))

(deftest many-ranks-after-a-rank
  (is (= ["W" "X" "Y"] (rank/after "V" 3)))
  (is (= ["V" "W"] (rank/after nil 2) (rank/after "" 2)) "the first ranks of a list")
  (is (= [] (rank/after "V" 0)))
  (is (= ["y" "z01" "z02"] (rank/after "x" 3)))
  (is (= (take 500 (rest (iterate #(rank/between % nil) "V"))) (rank/after "V" 500))
      "each rank is what between gives after the rank before it")
  (testing "the ranks of many items are short, in order, and ranks"
    (let [many (vec (rank/after "W" 8000))
          more (vec (rank/after (peek many) 8000))]
      (is (= 8000 (count many) (count (distinct many))))
      (is (in-order? (concat ["W"] many more)))
      (is (every? rank/rank? (concat many more)))
      (is (= 5 (apply max (map count many)) (apply max (map count more)))
          "and the ranks of the next items are no longer")))
  (testing "what is not a rank"
    (doseq [x ["V0" "V!" "0" 5]]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Not a rank" (rank/after x 2)) (pr-str x))))
  (testing "with a tag, the ranks of two devices after the same rank stay in blocks of their own"
    (let [phone  (rank/after "V" 3 "phone")
          laptop (rank/after "V" 3 "laptop")]
      (is (= ["Wf8V" "Wf8W" "Wf8X"] phone) "the same on every platform")
      (is (= (concat laptop phone) (sort (concat phone laptop))))
      (is (in-order? (concat ["V"] laptop phone ["X"])) "after the rank, and before the next one")
      (is (every? rank/rank? (rank/after "x" 500 "phone")))
      (is (= ["W"] (rank/after "V" 1 "phone")) "one rank needs no block"))))

#?(:clj
   (def ranks
     "Ranks as `between` produces them: digits with no trailing zero."
     (gen/fmap (fn [[body last]] (str (apply str body) last))
               (gen/tuple (gen/vector (gen/elements rank/digits) 0 4)
                          (gen/elements (rest rank/digits))))))

#?(:clj
   (defspec a-rank-between-is-between 300
     (prop/for-all [x ranks y ranks]
       (let [[a b] (sort [x y])]
         (or (= a b)
             (let [m (rank/between a b)]
               (and (neg? (compare a m))
                    (neg? (compare m b))
                    (not (str/ends-with? m "0")))))))))

#?(:clj
   (def ends
     "Ranks as they are at the ends of a list, some with a z or a 0 in front
     as the levels of `between` have them, and some as an earlier version
     wrote them."
     (gen/fmap (fn [[marks rank]] (str (apply str marks) rank))
               (gen/tuple (gen/one-of [(gen/vector (gen/return "z") 0 4) (gen/vector (gen/return "0") 0 4)])
                          ranks))))

#?(:clj
   (defn- level
     "How many times the digit `mark` starts the rank `s`."
     [mark s]
     (count (take-while #{mark} s))))

#?(:clj
   (defspec the-ranks-after-a-rank-are-beyond-it-in-order 300
     (prop/for-all [a ends
                    n (gen/choose 0 300)]
       (let [after (rank/after a n)]
         (and (= n (count after))
              (in-order? (cons a after))
              (every? rank/rank? after)
              ;; three hundred ranks go one level up at most
              (every? #(<= (count %) (+ 3 (* 2 (level \z a)))) after))))))

#?(:clj
   (defspec a-rank-at-an-end-is-beyond-it 300
     (prop/for-all [a ends]
       (let [above (rank/between a nil)
             below (rank/between nil a)]
         (and (neg? (compare a above))
              (pos? (compare a below))
              (rank/rank? above)
              (rank/rank? below)
              (<= (count above) (+ 3 (* 2 (level \z a))))
              (<= (count below) (+ 3 (* 2 (level \0 a)))))))))
