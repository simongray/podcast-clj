(ns dk.simongray.crdt.rank
  "Ranks: strings that sort where an item was put, and always leave room
  for another between any two.

  A rank is digits in base 62, from 0 to 9, A to Z and a to z. It's read
  as a fraction with no trailing zero, and compared as a string. The
  between function gives a rank between two, before the first or after
  the last:

      (between nil nil)   ;=> \"V\"    the first rank
      (between \"V\" nil)   ;=> \"W\"    after it
      (between \"V\" \"W\")   ;=> \"VV\"   between the two
      (between nil \"V\")   ;=> \"U\"    before the first

  At an end of a list, the ranks count, so they stay short however long
  the list grows. The after function gives the ranks of many items that
  go to the end of a list at one time:

      (after \"V\" 3)       ;=> (\"W\" \"X\" \"Y\")

  This is fractional indexing as Wallace describes it in Realtime Editing
  of Ordered Sequences (Figma, 2017), except that the ranks count at the
  ends: https://www.figma.com/blog/realtime-editing-of-ordered-sequences/"
  (:require [clojure.string :as str]))

(def ^:no-doc digits
  "The digits of a rank, in order."
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(defn- digit
  [c]
  (str/index-of digits (str c)))

(defn rank?
  "Whether `x` is a rank: a string of digits that doesn't end in a zero."
  [x]
  (boolean (and (string? x)
                (re-find #"^[0-9A-Za-z]*[1-9A-Za-z]$" x))))

(defn- midpoint
  "A string of digits strictly between `a` and `b`, `a` below `b`, where
  \"\" is the bottom and a nil `b` the top, neither ending in a zero."
  [a b]
  (let [padded (concat a (repeat \0))
        shared (if b
                 (count (take-while (fn [[x y]] (= x y))
                                    (map vector padded b)))
                 0)]
    (if (pos? shared)
      (str (subs b 0 shared)
           (midpoint (subs a (min shared (count a))) (subs b shared)))
      (let [da (if (seq a) (digit (first a)) 0)
            db (if b (digit (first b)) (count digits))]
        (cond
          (> (- db da) 1)
          (str (nth digits (quot (+ da db 1) 2)))

          (and b (> (count b) 1))
          (subs b 0 1)

          :else
          (str (nth digits da)
               (midpoint (subs a (min 1 (count a))) nil)))))))

;; At an end of a list, the ranks count in levels. After the last rank, a
;; rank of level k is k times z, and then a number of k + 1 digits that
;; does not start with z: V to y, then z01 to zyz, then zz001 to zzyzz.
;; Before the first rank, 0 is in the place of z, and the numbers count
;; down. Each level has 62 times the room of the level before it.

(defn ^:no-doc numbered
  "The first `width` digits of the string `s` as a vector of numbers, with
  a zero for each digit that `s` lacks."
  [s width]
  (vec (take width (concat (map digit s) (repeat 0)))))

(defn ^:no-doc plus
  "The digits `ds` of a number, as a vector, with `step` added to the
  number. The step is 1 or -1."
  [ds step]
  (loop [ds ds
         i  (dec (count ds))]
    (let [d (+ (ds i) step)]
      (cond
        (neg? d)             (recur (assoc ds i (dec (count digits))) (dec i))
        (= d (count digits)) (recur (assoc ds i 0) (dec i))
        :else                (assoc ds i d)))))

(defn ^:no-doc written
  "The digits `ds` of a number as a string, without the zeros at the end."
  [ds]
  (let [length (count (drop-while zero? (rseq ds)))]
    (apply str (map #(nth digits %) (take length ds)))))

(defn ^:no-doc above
  "The rank after the rank `s`, the last of a list. It's the next number
  of the level of `s`, or the first number of the next level when `s` has
  the last number of its level."
  [s]
  (let [level (count (take-while #(= \z %) s))
        lead  (subs s 0 level)
        up    (plus (numbered (subs s level) (inc level)) 1)]
    (if (= (dec (count digits)) (first up))
      (str lead "z" (written (conj (vec (repeat (inc level) 0)) 1)))
      (str lead (written up)))))

(defn ^:no-doc below
  "The rank before the rank `s`, the first of a list. It's the number
  before it in the level of `s`, or the last number of the next level
  when `s` has the first number of its level."
  [s]
  (let [level (count (take-while #(= \0 %) s))
        lead  (subs s 0 level)
        width (inc level)
        down  (numbered (subs s level) width)]
    (cond
      ;; the number without the digits after it is below the rank
      (< width (- (count s) level))
      (str lead (written down))

      (= down (into [1] (repeat level 0)))
      (str lead "0" (apply str (repeat (inc width) "z")))

      :else
      (str lead (written (plus down -1))))))

(defn between
  "A rank between `before` and `after`, where nil stands for an end of the
  list. The rank `before` must sort before `after`, since two equal ranks
  leave no room, and anything that isn't a rank throws.

  After the last rank and before the first, the ranks count: W is after V,
  and U is before it. The 30 ranks from V to y have one digit. The next
  3,781 ranks have three digits at most, from z01. The 234,483 ranks after
  them have five at most, so a list that grows at an end keeps short
  ranks."
  [before after]
  (when-not (and (or (nil? before) (= "" before) (rank? before))
                 (or (nil? after) (rank? after)))
    (throw (ex-info "Not a rank" {:before before :after after})))
  (when (and before
             after
             (not (neg? (compare before after))))
    (throw (ex-info "The ranks are not in order, or are the same"
                    {:before before :after after})))
  (cond
    (and before after) (midpoint before after)
    (seq before)       (above before)
    after              (below after)
    :else              (midpoint "" nil)))

(defn- tag-digits
  "Two digits from the string `tag`, the same on every platform."
  [tag]
  (let [base (count digits)
        code #?(:clj int :cljs #(.charCodeAt % 0))
        n    (reduce (fn [h c] (mod (+ (* 31 h) (code c)) (* base base)))
                     0
                     tag)]
    (str (nth digits (quot n base)) (nth digits (mod n base)))))

;; The tag keeps two batches clear of the interleaving anomaly of Kleppmann
;; et al. 2019, section 2: https://doi.org/10.1145/3301419.3323972
(defn after
  "The `n` ranks that come after the rank `before`, in order, in a block of
  their own for the `tag` if given. With nil for `before`, they're the
  first `n` ranks of a list. Anything that isn't a rank throws.

  Each rank is what between gives after the rank before it. So the ranks
  of many items are short: 8,000 ranks have five digits at most.

      (after \"V\" 3)   ;=> (\"W\" \"X\" \"Y\")
      (after nil 2)    ;=> (\"V\" \"W\")
      (after \"x\" 3)   ;=> (\"y\" \"z01\" \"z02\")

  Items that two devices add after the same rank at the same time get the
  same ranks, so they mix. With a `tag`, such as the device's id, the
  ranks share a block after `before`, named by two digits of the tag, so
  each device's items stay together. One rank needs no block.

      (after \"V\" 3 \"phone\")   ;=> (\"Wf8V\" \"Wf8W\" \"Wf8X\")"
  ([before n]
   (when-not (or (nil? before) (= "" before) (rank? before))
     (throw (ex-info "Not a rank" {:before before})))
   (->> (or (not-empty before) (below (midpoint "" nil)))
        (iterate above)
        rest
        (take n)))
  ([before n tag]
   (if (< n 2)
     (after before n)
     (let [block (str (between before nil) (tag-digits (str tag)))]
       (map (partial str block) (after nil n))))))

(comment
  (take 40 (iterate #(between % nil) (between nil nil)))
  (take 40 (iterate #(between nil %) (between nil nil)))
  (between "V" "W")
  (after nil 10)
  (frequencies (map count (after "W" 8000)))
  #_.)
