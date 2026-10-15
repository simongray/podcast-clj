(ns dk.simongray.podcast.profile.marks-test
  "The stars, bookmarks, clips and ratings of a profile."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 a b c]]))

(deftest stars-and-bookmarks
  (let [s (-> (profile/make "phone") (profile.marks/star a t0) (profile.marks/star b t1))]
    (is (= [b a] (profile.marks/starred s)) "the latest starred first")
    (is (profile.marks/starred? s a))
    (is (= s (profile.marks/star s a t2)) "starring again changes nothing, so the order stays")
    (is (= [b] (profile.marks/starred (profile.marks/unstar s a t2))))
    (is (:removed? (get-in (profile.marks/unstar s a t2) [:stars a])) "a tombstone")
    (is (not (profile.marks/starred? (profile/merge (profile.marks/unstar s a t2) (profile.marks/star (profile/make "laptop") a t1)) a))
        "the later removal outlives the older star from another device"))
  (let [s (-> (profile/make "phone")
              (profile.marks/bookmark a 754.8 "The good bit" t0)
              (profile.marks/bookmark a 30 t1)
              (profile.marks/bookmark b 5 "Other" t2))]
    (is (= [{:id a :position 30 :title nil :updated-at t1} {:id a :position 754 :title "The good bit" :updated-at t0}]
           (profile.marks/bookmarks s a))
        "an episode's bookmarks by position, in whole seconds")
    (is (= [b a a] (map :id (profile.marks/bookmarks s))) "all of them, the latest written first")
    (is (= "Renamed" (:title (second (profile.marks/bookmarks (profile.marks/bookmark s a 754 "Renamed" t3) a))))
        "the same second is the same bookmark")
    (is (= [30] (map :position (profile.marks/bookmarks (profile.marks/unbookmark s a 754.2 t3) a))))
    (doseq [bad [-1 ##NaN ##Inf "12"]]
      (is (= s (profile.marks/bookmark s a bad "x" t3)) (str "no bookmark at " (pr-str bad))))))

(deftest clips-of-episodes
  (let [s (-> (profile/make "phone")
              (profile.marks/clip a 754.8 42.5 "The good bit" t0)
              (profile.marks/clip a 30 10 t1)
              (profile.marks/clip b 5 20 "Other" t2))]
    (is (= [{:id a :start-time 30 :duration 10 :title nil :updated-at t1}
            {:id a :start-time 754 :duration 42.5 :title "The good bit" :updated-at t0}]
           (profile.marks/clips s a))
        "the clips of an episode by their starts, in the shape of a soundbite")
    (is (= [b a a] (map :id (profile.marks/clips s))) "all of them, the latest written first")
    (is (= [60 "Longer"] ((juxt :duration :title) (second (profile.marks/clips (profile.marks/clip s a 754 60 "Longer" t3) a))))
        "the same second is the same clip")
    (is (= [30] (map :start-time (profile.marks/clips (profile.marks/unclip s a 754.2 t3) a))))
    (is (:removed? (get-in (profile.marks/unclip s a 754 t3) [:clips a 754])) "a tombstone")
    (is (= s (profile.marks/unclip s a 99 t3)) "no clip there")
    (doseq [[start duration] [[-1 10] [##NaN 10] ["12" 10] [30 0] [30 -5] [30 ##NaN] [30 ##Inf] [30 "10"] [30 nil]]]
      (is (= s (profile.marks/clip s a start duration "x" t3)) (str "no clip for " (pr-str [start duration]))))
    (let [merged (profile/merge s (-> (profile/make "laptop")
                                      (profile.marks/clip a 30 15 "Mine" t2)
                                      (profile.marks/clip a 90 5 "More" t2)))]
      (is (= [[30 15 "Mine"] [90 5 "More"] [754 42.5 "The good bit"]]
             (map (juxt :start-time :duration :title) (profile.marks/clips merged a)))
          "the clips of two devices merge, and the later write of one clip wins"))))

(defn- near?
  "Whether the numbers `x` and `y` are the same but for rounding."
  [x y]
  (< (abs (- x y)) 1e-9))

(deftest ratings-of-episodes
  (let [s (-> (profile/make "phone") (profile.marks/rate-episode a 4 t0) (profile.marks/rate-episode b 2 t1))]
    (is (= {:worst 1 :best 5} profile.marks/rating-scale) "the scale of a rating that names none")
    (is (= 4 (profile.marks/rating s a)))
    (is (nil? (profile.marks/rating s c)) "an episode with no rating")
    (is (= [{:id b :rating 2 :worst 1 :best 5 :updated-at t1} {:id a :rating 4 :worst 1 :best 5 :updated-at t0}]
           (profile.marks/rated s))
        "the latest rated first, each with its scale")
    (is (= s (profile.marks/rate-episode s a 4 t2)) "the same rating again changes nothing")
    (is (= s (profile.marks/rate-episode s a 4.0 t2)) "in whatever form the number is")
    (is (= 5 (profile.marks/rating (profile.marks/rate-episode s a 5 t2) a)) "another rating takes its place")
    (is (= 4.5 (profile.marks/rating (profile.marks/rate-episode s a 4.5 t2) a)) "a number between two whole numbers")
    (doseq [bad [0 6 -1 5.5 ##NaN ##Inf "4" nil]]
      (is (= s (profile.marks/rate-episode s a bad t2)) (str "no rating of " (pr-str bad))))
    (let [without (profile.marks/unrate-episode s a t2)]
      (is (nil? (profile.marks/rating without a)))
      (is (= [b] (map :id (profile.marks/rated without))))
      (is (:removed? (get-in without [:ratings a])) "a tombstone")
      (is (= without (profile.marks/unrate-episode without a t3)) "no rating to take off")
      (is (= 3 (profile.marks/rating (profile.marks/rate-episode without a 3 t3) a)) "a rating after the removal")
      (is (nil? (profile.marks/rating (profile/merge without (profile.marks/rate-episode (profile/make "laptop") a 1 t1)) a))
          "the later removal outlives the older rating from another device"))
    (is (= 2.0 (profile/playback-rate (profile/prefer s {:playback-rate 2.0} t2) "feed-a")) "the speed of playback is another thing")))

(deftest ratings-in-the-scale-of-the-app
  (let [hundred {:scale {:worst 1 :best 100}}
        thumbs  {:scale {:worst 0 :best 1}}
        grades  {:scale {:worst 6 :best 1}}
        s       (-> (profile/make "phone")
                    (profile.marks/rate-episode a 4 t0)
                    (profile.marks/rate-episode b 80 t1 hundred)
                    (profile.marks/rate-episode c 1 t2 thumbs))]
    (testing "a rating as the state has it, with the scale that it came in"
      (is (= [4 80 1] (map #(profile.marks/rating s %) [a b c])))
      (is (= [{:id c :rating 1 :worst 0 :best 1 :updated-at t2}
              {:id b :rating 80 :worst 1 :best 100 :updated-at t1}
              {:id a :rating 4 :worst 1 :best 5 :updated-at t0}]
             (profile.marks/rated s)))
      (is (= {:rating 80 :worst 1 :best 100 :removed? false}
             (select-keys (get-in s [:ratings b]) [:rating :worst :best :removed?]))
          "the entry is a rating with its scale, as a Rating of schema.org"))
    (testing "a rating in the scale that the app asks for"
      (is (every? true? (map near? [75.25 80 100] (map #(profile.marks/rating s % hundred) [a b c]))))
      (is (every? true? (map near? [0.75 (/ 79 99.0) 1] (map #(profile.marks/rating s % thumbs) [a b c]))))
      (is (every? true? (map near? [4 (+ 1 (* 4 (/ 79 99.0))) 5]
                             (map #(profile.marks/rating s % {:scale profile.marks/rating-scale}) [a b c]))))
      (is (= 80 (profile.marks/rating s b hundred)) "its own scale changes nothing of the number")
      (is (= [[c 100 1 100] [b 80 1 100] [a 75 1 100]]
             (map (juxt :id #(Math/round (double (:rating %))) :worst :best) (profile.marks/rated s hundred)))))
    (testing "a scale whose best rating is the lower number"
      (let [graded (profile.marks/rate-episode s a 2 t3 grades)]
        (is (= 2 (profile.marks/rating graded a)))
        (is (near? 4.2 (profile.marks/rating graded a {:scale profile.marks/rating-scale})))
        (is (= s (profile.marks/rate-episode s a 7 t3 grades)))))
    (testing "a number outside the scale of the app changes nothing"
      (doseq [[n opts] [[0 hundred] [101 hundred] [2 thumbs] [-1 thumbs] [5 thumbs]]]
        (is (= s (profile.marks/rate-episode s a n t3 opts)) (pr-str [n opts]))))
    (testing "the same number in another scale is another rating"
      (is (= [4 1 10] ((juxt :rating :worst :best)
                       (get-in (profile.marks/rate-episode s a 4 t3 {:scale {:worst 1 :best 10}}) [:ratings a])))))
    (testing "a scale that is none is a mistake of the app"
      (doseq [bad [[1 5] {:worst 1} {:worst 1 :best 1} {:worst "1" :best 5} {:worst 1 :best ##Inf} 5]]
        (is (thrown? #?(:clj Exception :cljs js/Error) (profile.marks/rate-episode s a 3 t3 {:scale bad})) (pr-str bad))
        (is (thrown? #?(:clj Exception :cljs js/Error) (profile.marks/rating s a {:scale bad})) (pr-str bad))))
    (testing "two devices with two scales"
      (let [laptop (profile.marks/rate-episode (profile/make "laptop") a 90 t3 hundred)
            merged (profile/merge s laptop)]
        (is (= [90 1 100] ((juxt :rating :worst :best) (first (profile.marks/rated merged))))
            "the later rating wins, with its scale")
        (is (nil? (profile/problem merged)))))))
