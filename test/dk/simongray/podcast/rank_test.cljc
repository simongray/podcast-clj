(ns dk.simongray.podcast.rank-test
  "Ranks: a rank between any two, before the first and after the last,
  always in order, never ending in a zero, and short at the ends however
  long the list grows."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.rank :as rank]))

(deftest between-two-ranks
  (is (= "V" (rank/between nil nil)) "the first rank, in the middle")
  (is (= "W" (rank/between "V" nil)))
  (is (= "U" (rank/between nil "V")))
  (is (= "VV" (rank/between "V" "W")))
  (is (= "VVV" (rank/between "VV" "VW")))
  (is (= "1" (rank/between nil "2")))
  (is (= "0z" (rank/between nil "1")) "below the lowest digit, a digit is added")
  (is (= "z1" (rank/between "z" nil)) "above the highest digit, a digit is added")
  (is (= "1" (rank/between "" "1V")) "a prefix of the upper rank")
  (testing "what leaves no room"
    (is (thrown? #?(:clj Exception :cljs js/Error) (rank/between "W" "V")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (rank/between "V" "V")))))

(defn- in-order?
  [ranks]
  (every? neg? (map compare ranks (rest ranks))))

(deftest the-ends-stay-short
  (let [appended  (vec (take 300 (iterate #(rank/between % nil) (rank/between nil nil))))
        prepended (vec (take 300 (iterate #(rank/between nil %) (rank/between nil nil))))]
    (is (in-order? appended))
    (is (in-order? (reverse prepended)))
    (is (<= (count (peek appended)) 6) "three hundred appends, six digits at most")
    (is (<= (count (peek prepended)) 6))
    (is (not-any? #(str/ends-with? % "0") (concat appended prepended)))))

(deftest between-the-same-two-again-and-again
  (let [inserted (vec (take 100 (iterate #(rank/between "V" %) "W")))]
    (is (in-order? (reverse inserted)) "each between V and the last, so ever closer to V")
    (is (every? #(neg? (compare "V" %)) inserted))
    (is (not-any? #(str/ends-with? % "0") inserted))))

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
   (defspec a-rank-at-an-end-is-beyond-it 300
     (prop/for-all [a ranks]
       (and (neg? (compare a (rank/between a nil)))
            (pos? (compare a (rank/between nil a)))
            (not (str/ends-with? (rank/between a nil) "0"))
            (not (str/ends-with? (rank/between nil a) "0"))))))
