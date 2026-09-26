(ns dk.simongray.podcast.rank
  "Ranks: strings that sort where an item was put and always leave room for
  another between any two, so that an ordered list can be kept as entries
  that merge one by one, each carrying its rank, instead of as one value a
  merge takes whole. A rank is digits in base 62, `0-9A-Za-z`, read as a
  fraction with no trailing zero and compared as a string. `between` gives
  a rank between two, before the first or after the last; at the ends it
  moves the last digit by one while it can, so that a list appended to a
  thousand times keeps short ranks.

      (between nil nil)   ;=> \"V\"    the first rank
      (between \"V\" nil)   ;=> \"W\"    after it
      (between \"V\" \"W\")   ;=> \"VV\"   between the two
      (between nil \"V\")   ;=> \"U\"    before the first"
  (:require [clojure.string :as str]))

(def ^:no-doc digits
  "The digits of a rank, in order."
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(defn- digit
  [c]
  (str/index-of digits (str c)))

(defn- midpoint
  "A string of digits strictly between `a` and `b`, `a` below `b`, where
  \"\" is the bottom and a nil `b` the top, neither ending in a zero."
  [a b]
  (let [shared (if b
                 (count (take-while (fn [[x y]] (= x y)) (map vector (concat a (repeat \0)) b)))
                 0)]
    (if (pos? shared)
      (str (subs b 0 shared) (midpoint (subs a (min shared (count a))) (subs b shared)))
      (let [da (if (seq a) (digit (first a)) 0)
            db (if b (digit (first b)) (count digits))]
        (cond
          (> (- db da) 1)        (str (nth digits (quot (+ da db 1) 2)))
          (and b (> (count b) 1)) (subs b 0 1)
          :else                   (str (nth digits da) (midpoint (subs a (min 1 (count a))) nil)))))))

(defn between
  "A rank between `before` and `after`, either or both nil for the ends of
  the list; `before` must sort before `after`, and two equal ranks leave no
  room. After the last rank or before the first, the last digit moves by
  one while it can, so that the ranks at the ends grow by a digit only
  every sixty-one steps."
  [before after]
  (when (and before after (not (neg? (compare before after))))
    (throw (ex-info "The ranks are not in order, or are the same" {:before before :after after})))
  (cond
    (and before after) (midpoint before after)
    before             (let [d (digit (last before))]
                         (if (< d (dec (count digits)))
                           (str (subs before 0 (dec (count before))) (nth digits (inc d)))
                           (str before "1")))
    after              (let [d (digit (last after))]
                         (if (> d 1)
                           (str (subs after 0 (dec (count after))) (nth digits (dec d)))
                           (str (subs after 0 (dec (count after))) "0z")))
    :else              (midpoint "" nil)))

(comment
  (take 10 (iterate #(between % nil) (between nil nil)))
  (take 10 (iterate #(between nil %) (between nil nil)))
  (between "V" "W")
  #_.)
