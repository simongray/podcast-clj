(ns dk.simongray.podcast.profile.player-test
  "The events of a player for a profile, and the counts of listening."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.player :as profile.player]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 a b c]]
            [dk.simongray.podcast.util.time :as time]))

(deftest counting-listening
  (let [g  "feed-a"
        id [g "e1"]
        s0 (profile/subscribe (profile/make "phone") g {:url "https://a/rss" :title "A"} t0)
        at #(time/->inst (+ (inst-ms #inst "2026-09-30T23:59:50Z") (* 1000 %)))
        s1 (:profile (profile.player/step s0 [:progress id 100 3600] (at 0)))
        s2 (:profile (profile.player/step s1 [:progress id 110 3600] (at 10)))]
    (is (= {} (profile/listened s1)) "a first report has nothing to count from")
    (is (= {g {"2026-10" 10}} (profile/listened s2)) "the month of the report, in UTC")
    (is (< 14.9 (get-in (profile/listened (:profile (profile.player/step s2 [:progress id 700 3600] (at 15)))) [g "2026-10"]) 25.1)
        "a seek counts no more than playback at three times the speed")
    (is (= (profile/listened s2) (profile/listened (:profile (profile.player/step s2 [:progress id 50 3600] (at 15)))))
        "nor does a move back")
    (testing "two devices counting at once lose nothing"
      (let [laptop (:profile (profile.player/step (profile/merge (profile/make "laptop") s1) [:progress id 130 3600] (at 30)))]
        (is (= {g {"2026-10" 40}} (profile/listened (profile/merge s2 laptop))))))
    (testing "a state whose clock is ahead of the clock of the device"
      (let [fast  (profile.marks/star (profile/make "laptop") ["x" "y"] (at 7200))
            play  (fn [s] (reduce #(:profile (profile.player/step %1 [:progress id (* 10 %2) 3600] (at (* 10 %2)))) s (range 31)))
            ahead (play (profile/merge s0 fast))]
        (is (= {g {"2026-10" 300}} (profile/listened ahead) (profile/listened (play s0)))
            "the time is measured from the last report, and not from its stamp")
        (is (pos? (compare (:updated-at (profile/episode-entry ahead id)) (:reported-at (profile/episode-entry ahead id)))))))
    (testing "a report after a pause"
      (let [paused (:profile (profile.player/step s2 [:progress id 1500 3600] (at 610)))]
        (is (= (profile/listened s2) (profile/listened paused))
            "a move of the position after more than a minute is no listening")
        (is (= {g {"2026-10" 15}} (profile/listened (:profile (profile.player/step paused [:progress id 1505 3600] (at 615)))))
            "and the next report counts from there")
        (is (= {g {"2026-10" 70}} (profile/listened (:profile (profile.player/step s2 [:progress id 170 3600] (at (+ 10 profile.player/listening-gap))))))
            "a report at the end of the gap counts")
        (is (= {g {"2026-10" 310}}
               (profile/listened (:profile (profile.player/step s2 [:progress id 410 3600] (at 310) {:listening-gap 300}))))
            "the gap of the app")
        (is (= (profile/listened s2)
               (profile/listened (:profile (profile.player/step s2 [:progress id 140 3600] (at 40) {:listening-gap 20})))))))
    (testing "a device that is restored from a backup counts on from the higher count"
      (let [play     (fn [s from to start]
                       (reduce #(:profile (profile.player/step %1 [:progress id (+ from (* 10 %2)) 3600] (at (+ start (* 10 %2)))))
                               s
                               (range (inc (quot (- to from) 10)))))
            saved    (play s0 0 100 0)
            went-on  (play saved 100 1000 200)
            restored (play saved 100 130 5000)
            both     (profile/merge restored went-on)]
        (is (= [{g {"2026-10" 1000}} {g {"2026-10" 130}}] (map profile/listened [went-on restored])))
        (is (= {g {"2026-10" 1000}} (profile/listened both) (profile/listened (profile/merge went-on restored)))
            "the count with the later stamp is the lower one here")
        (is (= {g {"2026-10" 1010}} (profile/listened (:profile (profile.player/step both [:progress id 140 3600] (at 5040))))))))
    (is (= "the listening of \"feed-a\" is not counted in seconds"
           (profile/problem (assoc-in s2 [:listened g "2026-10" "x"] {:seconds "many" :updated-at t1 :device "x"}))))))

(deftest stepping
  (let [s (-> (profile/make "phone") (profile.queue/enqueue a t0) (profile.queue/enqueue b t0))]
    (testing "progress reports when an episode becomes played"
      (let [{:keys [profile effects]} (profile.player/step s [:progress a 3590 3600] t1)]
        (is (profile/played? profile a))
        (is (= [[:played a]] effects))
        (is (= [] (:effects (profile.player/step profile [:progress a 3595 3600] t2))) "only once")))
    (testing "ending the current episode plays the next"
      (let [{:keys [profile effects]} (profile.player/step s [:ended a] t1)]
        (is (profile/played? profile a))
        (is (= [b] (profile.queue/queue profile)))
        (is (= [[:play b]] effects))))
    (testing "ending an episode that is not current leaves the queue alone"
      (let [{:keys [profile effects]} (profile.player/step s [:ended c] t1)]
        (is (= [a b] (profile.queue/queue profile)))
        (is (= [[:nothing-next c]] effects))))
    (is (= [c a b] (profile.queue/queue (:profile (profile.player/step s [:play c] t1)))))
    (is (= {:profile s :effects []} (profile.player/step s [:volume 3] t1)) "unknown events change nothing")
    (testing "continuous play, when nothing else is queued"
      (let [on   (profile/prefer s {:continuous? true} t0)
            next {b c a c c b}
            opts {:continue-fn next}]
        (is (= [[:play b]] (:effects (profile.player/step on [:ended a] t1 opts))) "the queue first")
        (is (= [[:play b]] (:effects (profile.player/step on [:ended c] t1 opts))) "an episode outside the queue")
        (is (= [[:play c]] (:effects (profile.player/step (profile.queue/dequeue on a t1) [:ended b] t2 opts)))
            "the next of the show when the queue runs out")
        (is (= [[:nothing-next b]] (:effects (profile.player/step (profile.queue/dequeue s a t1) [:ended b] t2 opts)))
            "only with the listener's :continuous?")
        (is (= [[:stop b]] (:effects (profile.player/step (profile.queue/dequeue on a t1) [:ended b] t2
                                                          (assoc opts :sleep {:episodes 1}))))
            "and the sleep timer wins")))
    (testing "nothing next, when nothing else is queued and continuous play gives none"
      (let [alone (profile.queue/dequeue s a t1)
            on    (profile/prefer alone {:continuous? true} t1)]
        (let [{:keys [profile effects]} (profile.player/step alone [:ended b] t2)]
          (is (= [[:nothing-next b]] effects) "for the app to answer")
          (is (= [] (profile.queue/queue profile)) "with nothing made current"))
        (is (= [[:nothing-next b]] (:effects (profile.player/step on [:ended b] t2 {:continue-fn {}}))))
        (is (= [[:stop b]] (:effects (profile.player/step alone [:ended b] t2 {:sleep {:episodes 1}})))
            "but the sleep timer stops the player")))))

(deftest the-next-and-the-previous-episode
  (let [s (-> (profile/make "phone")
              (profile.queue/enqueue a t0)
              (profile.queue/enqueue b t0)
              (profile/progress c 600 3600 t0)
              (profile/progress a 100 3600 t1))]
    (testing "the next skips the current episode, which isn't played"
      (let [{:keys [profile effects]} (profile.player/step s [:next a] t2)]
        (is (= [[:play b]] effects))
        (is (= [b] (profile.queue/queue profile)))
        (is (not (profile/played? profile a)))
        (is (= 100 (:position (profile/episode-entry profile a))) "and it keeps its position")))
    (testing "from an episode outside the queue, the next is the current one"
      (is (= [[:play a]] (:effects (profile.player/step s [:next c] t2)))))
    (testing "with nothing else queued, nothing changes"
      (let [alone (profile.queue/dequeue s b t2)]
        (is (= {:profile alone :effects []} (profile.player/step alone [:next a] t3)))))
    (testing "the previous goes back to the episode heard before, which becomes current"
      (let [{:keys [profile effects]} (profile.player/step s [:previous a] t2)]
        (is (= [[:play c]] effects))
        (is (= [c a b] (profile.queue/queue profile)))))
    (testing "with nothing heard before, nothing changes"
      (let [fresh (profile.queue/enqueue (profile/make "phone") a t0)]
        (is (= {:profile fresh :effects []} (profile.player/step fresh [:previous a] t1)))))
    (testing "an episode that the app can't play is passed over"
      (let [three (profile.queue/enqueue s c t1)
            opts  {:can-play-pred #{a c}}]
        (is (= [[:play c]] (:effects (profile.player/step three [:next a] t2 opts))))
        (is (= [[:play c]] (:effects (profile.player/step three [:ended a] t2 opts))))
        (is (= [[:play c]] (:effects (profile.player/step three [:previous b] t2 {:can-play-pred #{c}}))))
        (is (= [] (:effects (profile.player/step s [:next a] t2 opts))) "and one alone changes nothing")))))

(deftest skipping-the-outro
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/configure "feed-a" {:skip-outro 60} t0)
              (profile.queue/enqueue a t0)
              (profile.queue/enqueue b t0)
              (profile/progress a 3500 3600 t0))]
    (is (= 3540 (profile/outro-start s "feed-a" a)))
    (is (nil? (profile/outro-start s "feed-a" b)) "not before the duration is known")
    (testing "a report in the outro ends the episode there"
      (let [{:keys [profile effects]} (profile.player/step s [:progress a 3545 3600] t1)]
        (is (= [[:stop a] [:played a] [:play b]] effects))
        (is (profile/played? profile a))
        (is (= [b] (profile.queue/queue profile)))
        (is (= [] (:effects (profile.player/step profile [:progress a 3550 3600] t2))) "once: a late report changes nothing")))
    (testing "so does a skip into it, which then needs no seek"
      (is (= [[:stop a] [:played a] [:play b]] (:effects (profile.player/step s [:skip a 45] t1))))
      (is (= [[:seek a 3530]] (:effects (profile.player/step s [:skip a :forward] t1)))))
    (testing "an episode that counts as played already, and one heard again"
      (let [short (profile/configure s "feed-a" {:skip-outro 20} t0)
            {:keys [profile effects]} (profile.player/step short [:progress a 3575 3600] t1)]
        (is (= [[:played a]] effects) "within the threshold of the end, but not yet in the outro")
        (is (= [[:stop a] [:play b]] (:effects (profile.player/step profile [:progress a 3585 3600] t2)))))
      (let [again (-> s (profile/mark-played a t1) (profile/progress a 20 t2))]
        (is (= [[:stop a] [:play b]] (:effects (profile.player/step again [:progress a 3541 3600] t3))))))
    (testing "an episode that is not current stops, with nothing next"
      (let [s (-> s
                  (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                  (profile/configure "feed-b" {:skip-outro 60} t0))
            {:keys [profile effects]} (profile.player/step s [:progress c 3545 3600] t1)]
        (is (= [[:stop c] [:played c] [:nothing-next c]] effects))
        (is (= [a b] (profile.queue/queue profile)))))
    (testing "the last of the queue, with nothing next"
      (let [alone (profile.queue/dequeue s b t0)]
        (is (= [[:stop a] [:played a] [:nothing-next a]] (:effects (profile.player/step alone [:progress a 3545 3600] t1))))))
    (testing "no outro, or one as long as the episode, ends nothing early"
      (is (= [[:played a]] (:effects (profile.player/step (profile/configure s "feed-a" {:skip-outro 0} t1) [:progress a 3590 3600] t2))))
      (is (= [[:played b]] (:effects (profile.player/step (profile/progress s b 10 50 t1) [:progress b 45 50] t2)))))))

(def chapters-doc
  "A chapters document with a chapter that is not in the table of contents."
  {:chapters [{:start-time 0 :title "Intro"}
              {:start-time 90 :title "News"}
              {:start-time 300 :title "Ad" :toc false}
              {:start-time 600 :title "Talk"}]})

(deftest moving-between-chapters
  (let [s      (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile.queue/enqueue a t0)
                   (profile.queue/enqueue b t0)
                   (profile/progress a 100 3600 t0))
        jump   (fn [profile direction] (profile.player/step profile [:chapter a direction chapters-doc] t1))
        at     (fn [position] (profile/progress s a position 3600 t0))
        seek   (fn [position] [[:seek a position]])]
    (testing "to the next chapter of the table of contents"
      (let [{:keys [profile effects]} (jump s :next)]
        (is (= (seek 600) effects) "the chapter outside the table of contents is no stop")
        (is (= 600 (:position (profile/episode-entry profile a))))))
    (testing "to the start of the chapter that plays, or to the chapter before it"
      (is (= (seek 90) (:effects (jump s :previous))))
      (is (= (seek 0) (:effects (jump (at 92) :previous))) "in the first seconds of a chapter")
      (is (= (seek 0) (:effects (profile.player/step s [:chapter a :previous chapters-doc] t1 {:restart 15})))
          "with the seconds that the app gives")
      (is (= (seek 90) (:effects (jump (at 601) :previous))))
      (is (= (seek 0) (:effects (jump (at 1) :previous)))))
    (testing "after the start of the last chapter, the next chapter is no jump"
      (is (= {:profile (at 700) :effects []} (jump (at 700) :next))))
    (testing "an episode whose position the state does not have starts at 0"
      (is (= [[:seek b 90]] (:effects (profile.player/step s [:chapter b :next chapters-doc] t1)))))
    (testing "a document with no chapters"
      (is (= [] (:effects (profile.player/step s [:chapter a :next {}] t1))))
      (is (= (seek 0) (:effects (profile.player/step s [:chapter a :previous {}] t1)))))
    (testing "a jump into the outro ends the episode, as a skip does"
      (let [outro (profile/configure s "feed-a" {:skip-outro 60} t0)]
        (is (= [[:stop a] [:played a] [:play b]]
               (:effects (profile.player/step outro [:chapter a :next {:chapters [{:start-time 3570}]}] t1))))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (jump s :sideways)) "a direction that is none")))

(deftest the-sleep-timer
  (let [s     (-> (profile/make "phone") (profile.queue/enqueue a t0) (profile.queue/enqueue b t0))
        sleep (fn [n] {:sleep {:episodes n}})]
    (testing "at the end of this episode"
      (let [{:keys [profile effects] :as result} (profile.player/step s [:ended a] t1 (sleep 1))]
        (is (= [[:stop a]] effects) "the player stops, and the next episode does not play")
        (is (= [b] (profile.queue/queue profile)) "the next episode is current, for the next time")
        (is (profile/played? profile a))
        (is (not (contains? result :sleep)) "the timer ran out")))
    (testing "after some episodes"
      (let [first-end  (profile.player/step s [:ended a] t1 (sleep 2))
            second-end (profile.player/step (:profile first-end) [:ended b] t2 (select-keys first-end [:sleep]))]
        (is (= [[[:play b]] {:episodes 1}] ((juxt :effects :sleep) first-end)))
        (is (= [[:stop b]] (:effects second-end)))
        (is (not (contains? second-end :sleep)))))
    (testing "each other event keeps the timer as it is"
      (doseq [event [[:progress a 100 3600] [:skip a 30] [:play b] [:chapter a :next chapters-doc] [:next a] [:previous a] [:volume 3]]]
        (is (= {:episodes 2} (:sleep (profile.player/step s event t1 (sleep 2)))) (pr-str event))))
    (testing "without a timer, the result has none"
      (is (= #{:profile :effects} (set (keys (profile.player/step s [:ended a] t1)))))
      (is (= #{:profile :effects} (set (keys (profile.player/step s [:ended a] t1 {}))))))
    (testing "an episode that ends in its outro"
      (let [outro (-> s
                      (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                      (profile/configure "feed-a" {:skip-outro 60} t0)
                      (profile/progress a 3500 3600 t0))]
        (is (= [[:stop a] [:played a]] (:effects (profile.player/step outro [:progress a 3545 3600] t1 (sleep 1))))
            "one stop, and no next episode")
        (is (= [[[:stop a] [:played a] [:play b]] {:episodes 1}]
               ((juxt :effects :sleep) (profile.player/step outro [:progress a 3545 3600] t1 (sleep 2)))))))
    (testing "the last episode of the queue"
      (is (= [[:stop b]] (:effects (profile.player/step (:profile (profile.player/step s [:ended a] t1)) [:ended b] t2 (sleep 1))))))
    (testing "the timer is no part of the state"
      (is (= (:profile (profile.player/step s [:ended a] t1)) (:profile (profile.player/step s [:ended a] t1 (sleep 3))))))))
