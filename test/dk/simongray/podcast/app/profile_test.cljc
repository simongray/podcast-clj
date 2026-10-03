(ns dk.simongray.podcast.app.profile-test
  "The profile: subscriptions, progress, the queue, the playlists and
  the inbox, stars, bookmarks, folders, tags, followed lists, saved filters
  and the counts of listening, refreshes, OPML and list feeds, merging and
  the reducer."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.profile :as profile]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 t4 a]]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.time :as time]))

(def b ["feed-a" "a2"])
(def c ["feed-b" "b1"])

(deftest subscriptions
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/configure "feed-a" {:auto-queue? true :skip-intro 20} t1))]
    (is (profile/subscribed? s "feed-a"))
    (is (not (profile/subscribed? s "feed-b")))
    (is (= (assoc profile/default-settings :auto-queue? true :skip-intro 20) (profile/settings s "feed-a")))
    (is (= profile/default-settings (profile/settings s "feed-b")))
    (testing "unsubscribing leaves a tombstone, resubscribing keeps the settings"
      (let [gone (profile/unsubscribe s "feed-a" t2)
            back (profile/subscribe gone "feed-a" {:url "https://a/rss" :title "A"} t3)]
        (is (not (profile/subscribed? gone "feed-a")))
        (is (= {} (profile/subscriptions gone)))
        (is (contains? (:subscriptions gone) "feed-a"))
        (is (profile/subscribed? back "feed-a"))
        (is (= 20 (:skip-intro (profile/settings back "feed-a"))))
        (is (= t3 (get-in back [:subscriptions "feed-a" :added-at])))))
    (is (= s (profile/configure s "feed-b" {:rate 2.0} t1)) "settings for an unknown feed change nothing")
    (testing "a subscription known by the guid of its URL moves to the guid that the feed declares"
      (let [tagged (profile/tag s "feed-a" "tech" t1)
            moved  (profile/rekey tagged "feed-a" "declared" t2)
            other  (-> (profile/make "laptop")
                       (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0))]
        (is (= ["declared"] (keys (profile/subscriptions moved))))
        (is (= {:url "https://a/rss" :title "A" :added-at t0} (select-keys (get (profile/subscriptions moved) "declared") [:url :title :added-at])))
        (is (not (profile/subscribed? moved "feed-a")))
        (is (contains? (:subscriptions moved) "feed-a") "a tombstone")
        (is (= (profile/settings tagged "feed-a") (profile/settings moved "declared")))
        (is (= #{"tech"} (profile/tags moved "declared")))
        (is (= "declared" (get (profile/by-url moved) (profile/normal-url "https://a/rss"))) "the live one names the URL")
        (is (= ["declared"] (keys (profile/subscriptions (profile/merge other moved)))) "the other device learns the move")
        (is (= s (profile/rekey s "feed-a" "feed-a" t2)) "the same guid changes nothing")
        (is (= s (profile/rekey s "feed-b" "declared" t2)) "no subscription, nothing to move")))))

(deftest progress-and-played
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/configure "feed-a" {:skip-intro 15} t0)
              (profile/progress a 600 3600 t1))]
    (is (= 600 (:position (profile/episode s a))))
    (is (not (profile/played? s a)))
    (is (= 600 (profile/resume-position s "feed-a" a)))
    (is (= 15 (profile/resume-position s "feed-a" b)) "an unstarted episode starts after the intro")
    (testing "within the threshold of the end counts as played"
      (let [done (profile/progress s a 3580 t2)]
        (is (profile/played? done a))
        (is (= t2 (:played-at (profile/episode done a))))
        (is (= 15 (profile/resume-position done "feed-a" a)) "a played episode starts over")))
    (testing "the threshold of the app"
      (is (not (profile/played? (profile/progress s a 3580 nil t2 {:played-threshold 10}) a)))
      (is (profile/played? (profile/progress s a 3590 nil t2 {:played-threshold 10}) a))
      (is (profile/played? (profile/progress s a 3300 nil t2 {:played-threshold 300}) a))
      (is (not (profile/played? (profile/skip s a 2980 t2 {:played-threshold 10}) a)))
      (is (profile/played? (profile/skip s a 2980 t2) a))
      (is (= [[:seek a 3580]] (:effects (profile/step s [:skip a 2980] t2 {:played-threshold 10}))))
      (is (= [] (:effects (profile/step s [:progress a 3580 3600] t2 {:played-threshold 10}))))
      (is (= [[:played a]] (:effects (profile/step s [:progress a 3580 3600] t2)))))
    (testing "marks"
      (is (profile/played? (profile/mark-played s b t2) b))
      (let [again (profile/mark-unplayed (profile/mark-played s a t2) a t3)]
        (is (not (profile/played? again a)))
        (is (= 0 (:position (profile/episode again a))))))
    (testing "a later report of the position from a device that hasn't seen the mark leaves the mark"
      (let [done   (profile/mark-played s a t2)
            behind (profile/progress (assoc s :device "laptop") a 700 3600 t3)
            both   (profile/merge done behind)]
        (is (profile/played? both a))
        (is (= 700 (:position (profile/episode both a))))
        (is (= {a (profile/episode both a)} (profile/episodes both)))))
    (testing "a mark that an older version kept in the entry of the episode"
      (let [old   (assoc-in s [:episodes a] {:position 3590 :played? true :played-at t1 :updated-at t1 :device "old"})
            moved (profile/progress old a 100 3600 t2)]
        (is (profile/played? old a))
        (is (= t1 (:played-at (profile/episode old a))))
        (is (profile/played? moved a) "moves to an entry of its own at the next write")
        (is (not (contains? (get-in moved [:episodes a]) :played?)))
        (is (= {:played? true :played-at t1} (select-keys (get-in moved [:played a]) [:played? :played-at])))))
    (is (= [b a] (profile/history (-> s (profile/progress b 10 t2)))))
    (is (= [a b] (profile/history (-> s (profile/mark-played a t1) (profile/mark-played b t2) (profile/progress a 5 t3))))
        "an episode heard again comes first again")))

(deftest skipping
  (let [s (-> (profile/make "phone") (profile/progress a 100 3600 t0))]
    (is (= 130 (:position (profile/episode (profile/skip s a 30 t1) a))))
    (is (= 85 (:position (profile/episode (profile/skip s a -15 t1) a))))
    (is (= 0 (:position (profile/episode (profile/skip s a -500 t1) a))) "never before the start")
    (is (= 3600 (:position (profile/episode (profile/skip s a 5000 t1) a))) "never past the end")
    (is (= 40 (:position (profile/episode (profile/skip (profile/make "x") c 40 t1) c))) "an unknown episode starts at zero")
    (doseq [duration [0 ##NaN]]
      (is (= 130 (:position (profile/episode (profile/skip (profile/progress s a 100 duration t0) a 30 t1) a)))
          (str "a duration of " duration " is not known, and no bound")))
    (is (= [[:seek a 3590] [:played a]] (:effects (profile/step s [:skip a 3490] t1)))
        "a skip into the end marks it played, and says so")
    (testing "the preferred amounts, through the reducer, with the seek as the effect"
      (let [s (profile/prefer s {:skip-forward 45} t0)]
        (is (= {:skip-forward 45 :skip-back 15 :rate 1.0} (profile/preferences s)))
        (is (= [[:seek a 145]] (:effects (profile/step s [:skip a :forward] t1))))
        (is (= [[:seek a 85]] (:effects (profile/step s [:skip a :back] t1))))
        (is (= [[:seek a 110]] (:effects (profile/step s [:skip a 10] t1))))))
    (testing "a feed's own rate overrides the listener's"
      (let [s (-> s
                  (profile/prefer {:rate 1.5} t0)
                  (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                  (profile/configure "feed-a" {:rate 1.0} t0))]
        (is (= 1.0 (profile/rate s "feed-a")))
        (is (= 1.5 (profile/rate s "feed-b")))))))

(deftest the-queue
  (let [s (-> (profile/make "phone") (profile/enqueue a t0) (profile/enqueue b t0) (profile/enqueue a t1))]
    (is (= [a b] (profile/queue s)))
    (is (= a (profile/current s)))
    (is (profile/queued? s b))
    (is (= [a c b] (profile/queue (profile/play-next s c t1))))
    (is (= [a b] (profile/queue (profile/play-next s a t1))) "the current episode stays current")
    (is (= [c] (profile/queue (profile/play-next (profile/make "phone") c t1))))
    (is (= [b] (profile/queue (profile/dequeue s a t1))))
    (is (= [b a] (profile/queue (profile/move s a 5 t1))) "an index past the end means the end")
    (is (= [b a c] (profile/queue (profile/enqueue (profile/move s a 5 t1) c t1))) "a new episode goes after the last")
    (is (= [a c] (profile/queue (profile/enqueue (profile/dequeue s b t1) c t1))) "and a removed one takes no place")
    (is (= [b a c] (profile/queue (profile/move (profile/enqueue s c t1) a 1.9 t1)))
        "an index that is not whole counts as the whole number below it")
    (is (= [b a] (profile/queue (profile/move s b ##NaN t1))) "and NaN as 0, on both platforms")
    (is (= [b] (profile/queue (profile/advance s t1))))
    (is (= [] (profile/queue (profile/advance (profile/make "x") t1))))
    (is (= t2 (get-in (profile/advance s t2) [:queue a :updated-at])) "the current episode is left as a tombstone")
    (let [seen (:clock s)]
      (is (= (time/->inst (inc (inst-ms seen))) (get-in (profile/advance s seen) [:queue a :updated-at]))
          "a write at an instant the state has seen is stamped the millisecond after"))))

(deftest the-queue-across-devices
  (let [shared (-> (profile/make "phone") (profile/enqueue a t0) (profile/enqueue b t0))
        phone  (-> shared (profile/advance t1) (profile/enqueue c t2))
        laptop (-> (profile/merge (profile/make "laptop") shared) (profile/enqueue ["feed-c" "c1"] t3))
        merged (profile/merge phone laptop)]
    (is (= [b c ["feed-c" "c1"]] (profile/queue merged))
        "the episode finished on the phone stays gone, and what both devices added survives, in order")
    (is (= (profile/queue merged) (profile/queue (profile/merge laptop phone))))
    (testing "two devices appending at once tie on the rank, and the stamp orders them"
      (let [d      ["feed-d" "d1"]
            e      ["feed-e" "e1"]
            phone2 (profile/enqueue phone d t3)
            laptop2 (profile/enqueue (profile/merge (profile/make "laptop") phone) e t3)
            both   (profile/merge phone2 laptop2)]
        (is (= [b c e d] (profile/queue both)) "the laptop's stamp is the later, so its episode comes last")
        (is (= (get-in both [:queue d :rank]) (get-in both [:queue e :rank])))
        (is (= [b ["feed-x" "x"] c e d] (profile/queue (profile/play-next both ["feed-x" "x"] t3)))
            "a place between distinct ranks is found")
        (is (= [b c e d ["feed-x" "x"]] (profile/queue (profile/move (profile/enqueue both ["feed-x" "x"] t3) ["feed-x" "x"] 3 t3)))
            "a place between two tied ranks is after both of them")))
    (testing "two devices that append several episodes at once keep each batch together"
      (let [d       ["feed-d" "d1"]
            e       ["feed-d" "d2"]
            phone3  (crdt/append (profile/make "phone") [:queue] {} [a b c] t0)
            laptop3 (crdt/append (profile/make "laptop") [:queue] {} [d e] t1)]
        (is (= [d e a b c] (profile/queue (profile/merge phone3 laptop3)) (profile/queue (profile/merge laptop3 phone3))))))
    (testing "a move never brings back an episode that another device took out"
      (let [three   (-> (profile/make "phone") (profile/enqueue a t0) (profile/enqueue b t0) (profile/enqueue c t0))
            phone4  (profile/dequeue three b t1)
            laptop4 (profile/move (assoc three :device "laptop") b 0 t2)]
        (is (= [b a c] (profile/queue laptop4)) "until the devices merge")
        (is (= [a c] (profile/queue (profile/merge phone4 laptop4)) (profile/queue (profile/merge laptop4 phone4))))
        (is (= [a c] (profile/queue (profile/merge phone4 (profile/play-next (assoc three :device "laptop") b t2))))
            "nor does play-next of a queued episode")
        (is (= [b a c] (profile/queue (profile/merge phone4 (:state (profile/step laptop4 [:play b] t2)))))
            "but playing it puts it back")))
    (testing "ranks stay short as a queue grows, at its end and at its front"
      (let [long   (reduce (fn [s i] (profile/enqueue s ["f" (str i)] t0)) (profile/make "x") (range 2000))
            played (reduce (fn [s i] (:state (profile/step s [:play ["g" (str i)]] t0))) long (range 200))
            digits #(apply max (map (comp count :rank) (vals (:queue %))))]
        (is (= 2000 (count (profile/queue long))))
        (is (= 3 (digits long)))
        (is (= (concat (for [i (range 199 -1 -1)] ["g" (str i)]) (for [i (range 2000)] ["f" (str i)]))
               (profile/queue played)))
        (is (= 3 (digits played)))))))

(deftest preferences-and-settings-field-by-field
  (let [base   (profile/subscribe (profile/make "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
        phone  (-> base (profile/prefer {:skip-forward 45} t1) (profile/configure "feed-a" {:skip-intro 20} t1))
        laptop (-> (profile/merge (profile/make "laptop") base)
                   (profile/prefer {:rate 1.5} t2)
                   (profile/configure "feed-a" {:auto-queue? true} t2))
        merged (profile/merge phone laptop)]
    (is (= {:skip-forward 45 :skip-back 15 :rate 1.5} (profile/preferences merged))
        "preferences changed on different devices both survive")
    (is (= (assoc profile/default-settings :auto-queue? true :skip-intro 20) (profile/settings merged "feed-a"))
        "and so do a subscription's settings")
    (is (= 60 (:skip-forward (profile/preferences (profile/merge phone (profile/prefer laptop {:skip-forward 60} t3)))))
        "the same field on both: the later wins")
    (is (= 20 (:skip-intro (profile/settings (profile/unsubscribe merged "feed-a" t3) "feed-a")))
        "settings outlive the subscription, for when it comes back")))

(deftest refreshing-a-feed
  (let [item   (fn [id] {:rss/guid {:content id} :rss/title id})
        before {:podcast/guid "feed-a" :rss/items [(item "a2") (item "a1")]}
        after  {:podcast/guid "feed-a" :rss/items [(item "a4") (item "a3") (item "a2") (item "a1")]}
        s      (-> (profile/make "phone") (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0))]
    (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (profile/new-episodes before after "https://a/rss")) "oldest first")
    (testing "oldest first by date also for a feed that lists them so, and none on a first parse"
      (let [dated (fn [id day] (assoc (item id) :rss/pub-date (time/->inst (* day 86400000))))
            after {:podcast/guid "feed-a" :rss/items [(dated "a1" 1) (dated "a2" 2) (dated "a3" 3) (dated "a4" 4)]}]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (profile/new-episodes before after "https://a/rss")))
        (is (= [] (profile/new-episodes nil after "https://a/rss")))))
    (testing "without auto-queue the new episodes are reported, not queued"
      (let [{:keys [state added]} (profile/refresh s before after "https://a/rss" t1)]
        (is (= 2 (count added)))
        (is (= [] (profile/queue state)))))
    (testing "with auto-queue they join the queue, oldest first"
      (let [{:keys [state]} (profile/refresh (profile/configure s "feed-a" {:auto-queue? true} t0) before after "https://a/rss" t1)]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (profile/queue state)))
        (is (= [] (profile/inbox state)))
        (is (= state (:state (profile/refresh state after after "https://a/rss" t2))) "a parse with no new episode changes nothing")))
    (testing "many new episodes join the queue with short ranks"
      (let [grown {:podcast/guid "feed-a" :rss/items (into (mapv #(item (str "n" %)) (range 3000 0 -1)) (:rss/items before))}
            s     (-> (profile/configure s "feed-a" {:auto-queue? true} t0) (profile/enqueue ["feed-b" "b1"] t0))
            {:keys [state added]} (profile/refresh s before grown "https://a/rss" t1)]
        (is (= 3000 (count added)))
        (is (= (into [["feed-b" "b1"]] added) (profile/queue state)) "after what the queue holds, in their order")
        (is (= 6 (apply max (map (comp count :rank) (vals (:queue state)))))
            "three digits, after the three of the device's block")))
    (testing "else, when the settings ask for it, the inbox, the newest first"
      (let [{:keys [state]} (profile/refresh (profile/configure s "feed-a" {:inbox? true} t0) before after "https://a/rss" t1)]
        (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (profile/inbox state)))
        (is (= [] (profile/queue state)))))
    (testing "an episode another device has handled stays as the listener left it"
      (let [phone  (-> (profile/configure s "feed-a" {:auto-queue? true} t0)
                       (profile/refresh before after "https://a/rss" t1)
                       :state
                       (profile/mark-played ["feed-a" "a3"] t2)
                       (profile/dequeue ["feed-a" "a3"] t2)
                       (profile/dequeue ["feed-a" "a4"] t2))
            laptop (-> (profile/merge (profile/make "laptop") phone)
                       (profile/refresh before after "https://a/rss" t3)
                       :state)]
        (is (= [] (profile/queue laptop)) "neither the one played nor the one taken out comes back")
        (let [inbox  (-> (profile/configure s "feed-a" {:inbox? true} t0)
                         (profile/refresh before after "https://a/rss" t1)
                         :state
                         (profile/dismiss ["feed-a" "a4"] t2))
              laptop (:state (profile/refresh (profile/merge (profile/make "laptop") inbox) before after "https://a/rss" t3))]
          (is (= [["feed-a" "a3"]] (profile/inbox laptop)) "nor the one dismissed from the inbox"))))))

(deftest the-inbox
  (let [c2 ["feed-b" "b2"]
        s  (-> (profile/make "phone")
               (crdt/write [:inbox a] (constantly {:removed? false}) t0)
               (crdt/write [:inbox b] (constantly {:removed? false}) t0)
               (crdt/write [:inbox c] (constantly {:removed? false}) t0)
               (crdt/write [:inbox c2] (constantly {:removed? false}) t0))]
    (is (= [c2 c b a] (profile/inbox s)) "the latest to arrive first")
    (testing "an episode leaves the inbox when it is dismissed, queued or played"
      (let [s' (-> s (profile/dismiss a t1) (profile/enqueue b t1) (profile/mark-played c t1) (profile/progress c2 3590 3600 t1))]
        (is (= [] (profile/inbox s')))
        (is (every? #(:removed? (get-in s' [:inbox %])) [a b c c2]) "each with a tombstone, which compaction can drop")))
    (is (= [c2 c a] (profile/inbox (profile/play-next s b t1))))
    (testing "and an episode played on a device that never had it in the inbox is not there either"
      (let [laptop (profile/mark-played (profile/make "laptop") a t1)]
        (is (not (profile/in-inbox? (profile/merge s laptop) a)))))
    (is (= s (profile/dismiss s ["feed-x" "none"] t1)) "dismissing what is not there changes nothing")))

(deftest stars-and-bookmarks
  (let [s (-> (profile/make "phone") (profile/star a t0) (profile/star b t1))]
    (is (= [b a] (profile/starred s)) "the latest starred first")
    (is (profile/starred? s a))
    (is (= s (profile/star s a t2)) "starring again changes nothing, so the order stays")
    (is (= [b] (profile/starred (profile/unstar s a t2))))
    (is (:removed? (get-in (profile/unstar s a t2) [:stars a])) "a tombstone")
    (is (not (profile/starred? (profile/merge (profile/unstar s a t2) (profile/star (profile/make "laptop") a t1)) a))
        "the later removal outlives the older star from another device"))
  (let [s (-> (profile/make "phone")
              (profile/bookmark a 754.8 "The good bit" t0)
              (profile/bookmark a 30 nil t1)
              (profile/bookmark b 5 "Other" t2))]
    (is (= [{:id a :position 30 :title nil :updated-at t1} {:id a :position 754 :title "The good bit" :updated-at t0}]
           (profile/bookmarks s a))
        "an episode's bookmarks by position, in whole seconds")
    (is (= [b a a] (map :id (profile/bookmarks s))) "all of them, the latest written first")
    (is (= "Renamed" (:title (second (profile/bookmarks (profile/bookmark s a 754 "Renamed" t3) a))))
        "the same second is the same bookmark")
    (is (= [30] (map :position (profile/bookmarks (profile/unbookmark s a 754.2 t3) a))))
    (doseq [bad [-1 ##NaN ##Inf "12"]]
      (is (= s (profile/bookmark s a bad "x" t3)) (str "no bookmark at " (pr-str bad))))))

(deftest clips-of-episodes
  (let [s (-> (profile/make "phone")
              (profile/clip a 754.8 42.5 "The good bit" t0)
              (profile/clip a 30 10 nil t1)
              (profile/clip b 5 20 "Other" t2))]
    (is (= [{:id a :start-time 30 :duration 10 :title nil :updated-at t1}
            {:id a :start-time 754 :duration 42.5 :title "The good bit" :updated-at t0}]
           (profile/clips s a))
        "the clips of an episode by their starts, in the shape of a soundbite")
    (is (= [b a a] (map :id (profile/clips s))) "all of them, the latest written first")
    (is (= [60 "Longer"] ((juxt :duration :title) (second (profile/clips (profile/clip s a 754 60 "Longer" t3) a))))
        "the same second is the same clip")
    (is (= [30] (map :start-time (profile/clips (profile/unclip s a 754.2 t3) a))))
    (is (:removed? (get-in (profile/unclip s a 754 t3) [:clips a 754])) "a tombstone")
    (is (= s (profile/unclip s a 99 t3)) "no clip there")
    (doseq [[start duration] [[-1 10] [##NaN 10] ["12" 10] [30 0] [30 -5] [30 ##NaN] [30 ##Inf] [30 "10"] [30 nil]]]
      (is (= s (profile/clip s a start duration "x" t3)) (str "no clip for " (pr-str [start duration]))))
    (let [merged (profile/merge s (-> (profile/make "laptop")
                                      (profile/clip a 30 15 "Mine" t2)
                                      (profile/clip a 90 5 "More" t2)))]
      (is (= [[30 15 "Mine"] [90 5 "More"] [754 42.5 "The good bit"]]
             (map (juxt :start-time :duration :title) (profile/clips merged a)))
          "the clips of two devices merge, and the later write of one clip wins"))))

(defn- near?
  "Whether the numbers `x` and `y` are the same but for rounding."
  [x y]
  (< (abs (- x y)) 1e-9))

(deftest ratings-of-episodes
  (let [s (-> (profile/make "phone") (profile/rate-episode a 4 t0) (profile/rate-episode b 2 t1))]
    (is (= {:worst 1 :best 5} profile/rating-scale) "the scale of a rating that names none")
    (is (= 4 (profile/rating s a)))
    (is (nil? (profile/rating s c)) "an episode with no rating")
    (is (= [{:id b :rating 2 :worst 1 :best 5 :updated-at t1} {:id a :rating 4 :worst 1 :best 5 :updated-at t0}]
           (profile/rated s))
        "the latest rated first, each with its scale")
    (is (= s (profile/rate-episode s a 4 t2)) "the same rating again changes nothing")
    (is (= s (profile/rate-episode s a 4.0 t2)) "in whatever form the number is")
    (is (= 5 (profile/rating (profile/rate-episode s a 5 t2) a)) "another rating takes its place")
    (is (= 4.5 (profile/rating (profile/rate-episode s a 4.5 t2) a)) "a number between two whole numbers")
    (doseq [bad [0 6 -1 5.5 ##NaN ##Inf "4" nil]]
      (is (= s (profile/rate-episode s a bad t2)) (str "no rating of " (pr-str bad))))
    (let [without (profile/unrate-episode s a t2)]
      (is (nil? (profile/rating without a)))
      (is (= [b] (map :id (profile/rated without))))
      (is (:removed? (get-in without [:ratings a])) "a tombstone")
      (is (= without (profile/unrate-episode without a t3)) "no rating to take off")
      (is (= 3 (profile/rating (profile/rate-episode without a 3 t3) a)) "a rating after the removal")
      (is (nil? (profile/rating (profile/merge without (profile/rate-episode (profile/make "laptop") a 1 t1)) a))
          "the later removal outlives the older rating from another device"))
    (is (= 2.0 (profile/rate (profile/prefer s {:rate 2.0} t2) "feed-a")) "the speed of playback is another thing")))

(deftest ratings-in-the-scale-of-the-app
  (let [hundred {:scale {:worst 1 :best 100}}
        thumbs  {:scale {:worst 0 :best 1}}
        grades  {:scale {:worst 6 :best 1}}
        s       (-> (profile/make "phone")
                    (profile/rate-episode a 4 t0)
                    (profile/rate-episode b 80 t1 hundred)
                    (profile/rate-episode c 1 t2 thumbs))]
    (testing "a rating as the state has it, with the scale that it came in"
      (is (= [4 80 1] (map #(profile/rating s %) [a b c])))
      (is (= [{:id c :rating 1 :worst 0 :best 1 :updated-at t2}
              {:id b :rating 80 :worst 1 :best 100 :updated-at t1}
              {:id a :rating 4 :worst 1 :best 5 :updated-at t0}]
             (profile/rated s)))
      (is (= {:rating 80 :worst 1 :best 100 :removed? false}
             (select-keys (get-in s [:ratings b]) [:rating :worst :best :removed?]))
          "the entry is a rating with its scale, as a Rating of schema.org"))
    (testing "a rating in the scale that the app asks for"
      (is (every? true? (map near? [75.25 80 100] (map #(profile/rating s % hundred) [a b c]))))
      (is (every? true? (map near? [0.75 (/ 79 99.0) 1] (map #(profile/rating s % thumbs) [a b c]))))
      (is (every? true? (map near? [4 (+ 1 (* 4 (/ 79 99.0))) 5]
                             (map #(profile/rating s % {:scale profile/rating-scale}) [a b c]))))
      (is (= 80 (profile/rating s b hundred)) "its own scale changes nothing of the number")
      (is (= [[c 100 1 100] [b 80 1 100] [a 75 1 100]]
             (map (juxt :id #(Math/round (double (:rating %))) :worst :best) (profile/rated s hundred)))))
    (testing "a scale whose best rating is the lower number"
      (let [graded (profile/rate-episode s a 2 t3 grades)]
        (is (= 2 (profile/rating graded a)))
        (is (near? 4.2 (profile/rating graded a {:scale profile/rating-scale})))
        (is (= s (profile/rate-episode s a 7 t3 grades)))))
    (testing "a number outside the scale of the app changes nothing"
      (doseq [[n opts] [[0 hundred] [101 hundred] [2 thumbs] [-1 thumbs] [5 thumbs]]]
        (is (= s (profile/rate-episode s a n t3 opts)) (pr-str [n opts]))))
    (testing "the same number in another scale is another rating"
      (is (= [4 1 10] ((juxt :rating :worst :best)
                       (get-in (profile/rate-episode s a 4 t3 {:scale {:worst 1 :best 10}}) [:ratings a])))))
    (testing "a scale that is none is a mistake of the app"
      (doseq [bad [[1 5] {:worst 1} {:worst 1 :best 1} {:worst "1" :best 5} {:worst 1 :best ##Inf} 5]]
        (is (thrown? #?(:clj Exception :cljs js/Error) (profile/rate-episode s a 3 t3 {:scale bad})) (pr-str bad))
        (is (thrown? #?(:clj Exception :cljs js/Error) (profile/rating s a {:scale bad})) (pr-str bad))))
    (testing "two devices with two scales"
      (let [laptop (profile/rate-episode (profile/make "laptop") a 90 t3 hundred)
            merged (profile/merge s laptop)]
        (is (= [90 1 100] ((juxt :rating :worst :best) (first (profile/rated merged))))
            "the later rating wins, with its scale")
        (is (nil? (profile/problem merged)))))))

(deftest folders-of-shows
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
              (profile/subscribe "feed-c" {:url "https://c/rss" :title "C"} t0)
              (profile/configure "feed-c" {:folder "News"} t0)
              (profile/configure "feed-a" {:folder "News"} t0))]
    (is (= {"News" ["feed-a" "feed-c"] nil ["feed-b"]} (profile/folders s)) "titles in order, the rest under nil")
    (is (= {"Nyheder" ["feed-a" "feed-c"] nil ["feed-b"]} (profile/folders (profile/rename-folder s "News" "Nyheder" t1))))
    (is (= {nil ["feed-a" "feed-b" "feed-c"]} (profile/folders (profile/rename-folder s "News" nil t1))) "nil empties the folder")
    (is (= s (profile/rename-folder s "Sport" "Fodbold" t1)) "a folder without shows changes nothing")
    (testing "in OPML, as the folders it has, and back, with the tags and the time of subscribing"
      (let [s    (-> s (profile/tag "feed-a" "Daily" t1) (profile/tag "feed-a" "Danish" t1))
            list (profile/to-opml s)
            back (profile/from-opml (profile/make "laptop") (opml/parse (opml/emit list)) t2)]
        (is (= [["feed-a" ["News"]] ["feed-c" ["News"]] ["feed-b" nil]] (map (juxt :guid :path) (:subscriptions list)))
            "the folders by name first, then the shows in none")
        (is (= ["Daily" "Danish"] (:category (first (:subscriptions list)))))
        (is (= (profile/folders s) (profile/folders back)))
        (is (= (profile/tagged s) (profile/tagged back)))
        (is (= t0 (get-in back [:subscriptions "feed-a" :added-at])) "subscribed when the other device subscribed")))
    (is (= {"Danish" ["x"]}
           (profile/folders (profile/from-opml (profile/make "x") {:subscriptions [{:text "X" :xml-url "https://x/rss" :guid "x"
                                                                                     :path ["News" "Danish"]}]}
                                               t0)))
        "the innermost of nested folders")))

(deftest tags-of-shows
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
              (profile/tag "feed-b" "Tech" t0)
              (profile/tag "feed-a" "Tech" t0)
              (profile/tag "feed-a" "Daily" t0))]
    (is (= #{"Daily" "Tech"} (profile/tags s "feed-a")))
    (is (= {"Tech" ["feed-a" "feed-b"] "Daily" ["feed-a"]} (profile/tagged s)) "titles in order")
    (is (= s (profile/tag s "feed-x" "Tech" t1)) "no tag for a show the state does not know")
    (is (= #{"Daily"} (profile/tags (profile/untag s "feed-a" "Tech" t1) "feed-a")))
    (is (= {"Technology" ["feed-a" "feed-b"] "Daily" ["feed-a"]} (profile/tagged (profile/rename-tag s "Tech" "Technology" t1))))
    (is (= {"Daily" ["feed-a"]} (profile/tagged (profile/rename-tag s "Tech" nil t1))) "nil takes the tag off")
    (is (= #{"Daily" "Tech" "Mine" "Yours"}
           (profile/tags (profile/merge (profile/tag s "feed-a" "Mine" t1)
                                        (profile/tag (profile/merge (profile/make "laptop") s) "feed-a" "Yours" t1))
                         "feed-a"))
        "tags two devices add at once both stay, unlike a folder")
    (is (= "a tag of \"feed-a\" is not a string"
           (profile/problem (assoc-in s [:tags "feed-a" :tech] {:removed? false :updated-at t1 :device "x"}))))))

(deftest following-lists
  (let [url  "https://lists.example/picks.opml"
        g    #(guid/from-url %)
        v1   {:subscriptions [{:text "PC20" :xml-url "https://pc20/rss"} {:text "Mine" :xml-url "https://mine/rss"}]}
        v2   {:subscriptions [{:text "PC20" :xml-url "https://pc20/rss"}
                              {:text "A description" :title "New" :xml-url "https://new/rss"}]}
        s    (-> (profile/make "phone")
                 (profile/subscribe (g "https://mine/rss") {:url "https://mine/rss" :title "Mine"} t0)
                 (profile/follow-list url {:title "Picks" :folder "Picks"} t0))
        r1   (profile/refresh-list s url nil v1 t1)]
    (is (= {url {:title "Picks" :folder "Picks"}} (profile/lists s)))
    (testing "a first parse subscribes to the shows the state does not know, into the list's folder"
      (is (= [(g "https://pc20/rss")] (:added r1)))
      (is (= t1 (:clock (:state r1))) "all at the same stamp")
      (is (= {"Picks" [(g "https://pc20/rss")] nil [(g "https://mine/rss")]} (profile/folders (:state r1)))
          "the listener's own show stays where it was"))
    (testing "later parses add the shows that join, and report the ones that leave"
      (let [unsubscribed (profile/unsubscribe (:state r1) (g "https://pc20/rss") t2)
            {:keys [state added left]} (profile/refresh-list unsubscribed url v1 v2 t3)]
        (is (= [(g "https://new/rss")] added))
        (is (= "New" (get-in state [:subscriptions (g "https://new/rss") :title])) "the feed's title, not the text")
        (is (= [(g "https://mine/rss")] left) "a show that left stays, for the app to decide")
        (is (profile/subscribed? state (g "https://mine/rss")))
        (is (not (profile/subscribed? state (g "https://pc20/rss"))) "a show the listener left stays left")))
    (is (= {:state s :added [] :left []} (profile/refresh-list s "https://other/list.opml" nil v1 t1))
        "a list the listener does not follow changes nothing")
    (is (= {} (profile/lists (profile/unfollow-list s url t1))))
    (testing "the state knows a show by the URL of its feed, under whatever guid"
      (let [s     (-> (profile/make "phone")
                      (profile/subscribe "declared" {:url "https://pc20/rss" :title "PC20"} t0)
                      (profile/subscribe "private" {:url "https://simon:secret@members.example/rss" :title "Members"} t0)
                      (profile/follow-list url {:title "Picks"} t0))
            twice {:subscriptions [{:text "PC20" :xml-url "http://PC20/rss/"}
                                   {:text "Members" :xml-url "https://members.example/rss"}
                                   {:text "New" :xml-url "https://new/rss"}
                                   {:text "New, again" :xml-url "https://NEW/rss"}]}
            {:keys [state added left]} (profile/refresh-list s url nil twice t1)]
        (is (= [(g "https://new/rss")] added) "and a show that the list names twice comes in once")
        (is (= #{"declared" "private" (g "https://new/rss")} (set (keys (profile/subscriptions state)))))
        (is (= "https://simon:secret@members.example/rss" (get-in state [:subscriptions "private" :url]))
            "a private feed is the feed at its URL without the password, and keeps its own URL")
        (is (= ["declared" "private" (g "https://new/rss")]
               (:left (profile/refresh-list state url twice {:subscriptions []} t2)))
            "a show that leaves has the guid that the state knows it by")
        (is (= [] left))))
    (testing "a show that the listener left stays left on a device that joins after the compaction"
      (let [day    (* 24 60 60 1000)
            far    (time/->inst (+ (inst-ms t4) (* 40 day)))
            phone  (-> (crdt/identify (:state r1) {:name "Phone"} t1)
                       (profile/unsubscribe (g "https://pc20/rss") t2))
            laptop (-> (crdt/identify (profile/make "laptop") {:name "Laptop"} t2)
                       (profile/merge phone)
                       (crdt/acknowledge [phone] t3))
            phone  (profile/compact (crdt/acknowledge (profile/merge phone laptop) [laptop] t3) far)
            tablet (profile/merge (profile/make "tablet") phone)]
        (is (:removed? (get-in phone [:subscriptions (g "https://pc20/rss")]))
            "the tombstone stays while the listener follows a list")
        (is (= [] (:added (profile/refresh-list tablet url nil v1 far))))
        (is (nil? (get-in (profile/compact (profile/unfollow-list phone url t4) far {:keep-for 0})
                          [:subscriptions (g "https://pc20/rss")]))
            "and it goes when the listener follows none")))
    (testing "in OPML, as an inclusion in the list's folder, and followed again from it"
      (let [xml (opml/emit (profile/to-opml (:state r1)))]
        (is (re-find #"<outline text=\"Picks\">\s*<outline type=\"rss\"[^>]*/>\s*<outline type=\"include\" text=\"Picks\" url=\"https://lists.example/picks.opml\"/>" xml))
        (is (= {url {:title "Picks" :folder "Picks"}}
               (profile/lists (profile/from-opml (profile/make "laptop") (opml/parse xml) t2))))))))

(deftest playlists-beside-the-queue
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/add-to-playlist "Drive" a t0)
              (profile/add-to-playlist "Drive" b t0)
              (profile/add-to-playlist "Drive" c t0)
              (profile/move-in-playlist "Drive" c 0 t1))]
    (is (= [c a b] (profile/playlist s "Drive")))
    (is (= s (profile/add-to-playlist s "Drive" a t2)) "an episode is in a playlist once")
    (is (= [c b] (profile/playlist (profile/remove-from-playlist s "Drive" a t2) "Drive")))
    (is (= {"Road trip" [c a b]} (profile/playlists (profile/rename-playlist s "Drive" "Road trip" t2))) "in the same order")
    (is (= {} (profile/playlists (profile/rename-playlist s "Drive" nil t2))) "nil deletes it")
    (is (= [c b] (profile/playlist (profile/merge (profile/remove-from-playlist s "Drive" a t2)
                                                  (profile/move-in-playlist (assoc s :device "laptop") "Drive" a 0 t3))
                                   "Drive"))
        "a move never brings back an episode that another device took out")
    (is (= [] (profile/queue s)) "the queue is its own")
    (testing "into a playlist that holds episodes"
      (let [d ["feed-b" "b2"]
            e ["feed-b" "b3"]
            s (-> s (profile/add-to-playlist "Walk" d t1) (profile/add-to-playlist "Walk" a t1) (profile/add-to-playlist "Walk" e t1))]
        (is (= {"Walk" [d a e c b]} (profile/playlists (profile/rename-playlist s "Drive" "Walk" t2)))
            "at its end, in their order, and an episode that both hold stays where it was")
        (is (= s (profile/rename-playlist s "Drive" "Drive" t2)) "a playlist renamed to its own name is as it was")))
    (testing "a private feed goes into a list feed without its credentials"
      (let [s (-> (profile/make "phone")
                  (profile/subscribe "feed-p" {:url "https://simon:secret@members.example/rss" :title "Members"} t0)
                  (profile/add-to-playlist "Best" ["feed-p" "p1"] t0))]
        (is (= [{:feed-guid "feed-p" :item-guid "p1" :feed-url "https://members.example/rss"}]
               (:podcast/remote-items (profile/to-list-feed s "Best"))))))
    (testing "a list feed that names an episode twice, and one that the playlist holds"
      (let [feed {:podcast/remote-items [{:feed-guid "feed-a" :item-guid "a2"} {:feed-guid "feed-c" :item-guid "c1"}
                                         {:feed-guid "feed-c" :item-guid "c1"} {:feed-guid "feed-c"}
                                         {:feed-guid "feed-c" :item-guid "c2"}]}
            s'   (profile/from-list-feed s "Drive" feed t2)]
        (is (= [c a b ["feed-c" "c1"] ["feed-c" "c2"]] (profile/playlist s' "Drive")))
        (is (= s' (profile/from-list-feed s' "Drive" feed t3)) "and again, nothing changes")))
    (testing "a long list feed, and the same list feed again with more episodes"
      (let [feed  (fn [n] {:podcast/remote-items (vec (for [i (range n)] {:feed-guid "feed-l" :item-guid (str i)}))})
            long  (profile/from-list-feed s "Drive" (feed 5000) t2)
            again (profile/from-list-feed long "Drive" (feed 9000) t3)
            ranks #(map :rank (vals (get-in % [:playlists "Drive"])))]
        (is (= (into [c a b] (for [i (range 9000)] ["feed-l" (str i)])) (profile/playlist again "Drive")))
        (is (= 8 (apply max (map count (ranks long))) (apply max (map count (ranks again))))
            "the ranks of many episodes are short: five digits after the three of the device's block")))
    (testing "as a list feed of the podcast namespace, and back"
      (let [feed (profile/to-list-feed s "Drive" {:url "https://me.example/drive.xml"})]
        (is (= {:rss/version "2.0" :rss/title "Drive" :rss/description "Drive" :rss/link "https://me.example/drive.xml"
                :podcast/guid (guid/from-url "https://me.example/drive.xml") :podcast/medium "podcastL"
                :podcast/remote-items [{:feed-guid "feed-b" :item-guid "b1"}
                                       {:feed-guid "feed-a" :item-guid "a1" :feed-url "https://a/rss"}
                                       {:feed-guid "feed-a" :item-guid "a2" :feed-url "https://a/rss"}]}
               feed)
            "with the URL of each feed the listener subscribes to")
        (is (= [] (validate/validate feed)))
        (is (= [c a b] (profile/playlist (profile/from-list-feed (profile/make "laptop") "Shared"
                                                                 (podcast/parse (podcast/emit feed)) t2)
                                         "Shared")))))))

(deftest counting-listening
  (let [g  "feed-a"
        id [g "e1"]
        s0 (profile/subscribe (profile/make "phone") g {:url "https://a/rss" :title "A"} t0)
        at #(time/->inst (+ (inst-ms #inst "2026-09-30T23:59:50Z") (* 1000 %)))
        s1 (:state (profile/step s0 [:progress id 100 3600] (at 0)))
        s2 (:state (profile/step s1 [:progress id 110 3600] (at 10)))]
    (is (= {} (profile/listened s1)) "a first report has nothing to count from")
    (is (= {g {"2026-10" 10}} (profile/listened s2)) "the month of the report, in UTC")
    (is (< 14.9 (get-in (profile/listened (:state (profile/step s2 [:progress id 700 3600] (at 15)))) [g "2026-10"]) 25.1)
        "a seek counts no more than playback at three times the speed")
    (is (= (profile/listened s2) (profile/listened (:state (profile/step s2 [:progress id 50 3600] (at 15)))))
        "nor does a move back")
    (testing "two devices counting at once lose nothing"
      (let [laptop (:state (profile/step (profile/merge (profile/make "laptop") s1) [:progress id 130 3600] (at 30)))]
        (is (= {g {"2026-10" 40}} (profile/listened (profile/merge s2 laptop))))))
    (testing "a state whose clock is ahead of the clock of the device"
      (let [fast  (profile/star (profile/make "laptop") ["x" "y"] (at 7200))
            play  (fn [s] (reduce #(:state (profile/step %1 [:progress id (* 10 %2) 3600] (at (* 10 %2)))) s (range 31)))
            ahead (play (profile/merge s0 fast))]
        (is (= {g {"2026-10" 300}} (profile/listened ahead) (profile/listened (play s0)))
            "the time is measured from the last report, and not from its stamp")
        (is (pos? (compare (:updated-at (profile/episode ahead id)) (:reported-at (profile/episode ahead id)))))))
    (testing "a report after a pause"
      (let [paused (:state (profile/step s2 [:progress id 1500 3600] (at 610)))]
        (is (= (profile/listened s2) (profile/listened paused))
            "a move of the position after more than a minute is no listening")
        (is (= {g {"2026-10" 15}} (profile/listened (:state (profile/step paused [:progress id 1505 3600] (at 615)))))
            "and the next report counts from there")
        (is (= {g {"2026-10" 70}} (profile/listened (:state (profile/step s2 [:progress id 170 3600] (at (+ 10 profile/listening-gap))))))
            "a report at the end of the gap counts")
        (is (= {g {"2026-10" 310}}
               (profile/listened (:state (profile/step s2 [:progress id 410 3600] (at 310) {:listening-gap 300}))))
            "the gap of the app")
        (is (= (profile/listened s2)
               (profile/listened (:state (profile/step s2 [:progress id 140 3600] (at 40) {:listening-gap 20})))))))
    (testing "a device that is restored from a backup counts on from the higher count"
      (let [play     (fn [s from to start]
                       (reduce #(:state (profile/step %1 [:progress id (+ from (* 10 %2)) 3600] (at (+ start (* 10 %2)))))
                               s
                               (range (inc (quot (- to from) 10)))))
            saved    (play s0 0 100 0)
            went-on  (play saved 100 1000 200)
            restored (play saved 100 130 5000)
            both     (profile/merge restored went-on)]
        (is (= [{g {"2026-10" 1000}} {g {"2026-10" 130}}] (map profile/listened [went-on restored])))
        (is (= {g {"2026-10" 1000}} (profile/listened both) (profile/listened (profile/merge went-on restored)))
            "the count with the later stamp is the lower one here")
        (is (= {g {"2026-10" 1010}} (profile/listened (:state (profile/step both [:progress id 140 3600] (at 5040))))))))
    (is (= "the listening of \"feed-a\" is not counted in seconds"
           (profile/problem (assoc-in s2 [:listened g "2026-10" "x"] {:seconds "many" :updated-at t1 :device "x"}))))))

(deftest saved-filters
  (let [s (-> (profile/make "phone")
              (profile/save-filter "Unplayed" {:where {:status :unplayed} :order :newest :profile {:big :state} :url "u"} t0)
              (profile/save-filter "Starred" {:where {:starred? true}} t0))]
    (is (= {"Unplayed" {:where {:status :unplayed} :order :newest} "Starred" {:where {:starred? true}}} (profile/filters s))
        "without the profile and the url, which are the context of one search")
    (is (= {:where {:status :played}} (get (profile/filters (profile/save-filter s "Unplayed" {:where {:status :played}} t1)) "Unplayed"))
        "saved again under the same name, replaced")
    (is (= ["Starred"] (keys (profile/filters (profile/remove-filter s "Unplayed" t1)))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (profile/save-filter s "Odd" {:order (fn [_ _] 0)} t1))
        "a function cannot be printed as EDN, and so cannot sync")))

(deftest compacting-what-the-listener-took-away
  (let [far    (time/->inst (+ (inst-ms t3) (* 31 24 60 60 1000)))
        phone  (-> (profile/make "phone")
                   (profile/star a t0) (profile/unstar a t1)
                   (profile/bookmark a 30 "Kept" t0) (profile/bookmark a 60 "Gone" t0) (profile/unbookmark a 60 t1)
                   (profile/bookmark b 90 "Gone" t0) (profile/unbookmark b 90 t1)
                   (crdt/write [:inbox b] (constantly {:removed? false}) t0) (profile/dismiss b t1)
                   (profile/save-filter "F" {} t0) (profile/remove-filter "F" t1)
                   (profile/clip a 30 10 "Kept" t0) (profile/clip b 60 10 "Gone" t0) (profile/unclip b 60 t1)
                   (profile/rate-episode a 4 t0) (profile/rate-episode b 2 t0) (profile/unrate-episode b t1))
        laptop (crdt/acknowledge (profile/merge (profile/make "laptop") phone) [phone] t2)
        phone  (crdt/acknowledge (profile/merge phone laptop) [laptop] t2)
        after  (profile/compact phone far)]
    (is (= {:stars {} :inbox {} :filters {} :bookmarks {a {30 (get-in phone [:bookmarks a 30])}}
            :clips {a {30 (get-in phone [:clips a 30])}} :ratings {a (get-in phone [:ratings a])}}
           (select-keys after [:stars :inbox :filters :bookmarks :clips :ratings]))
        "the tombstones go, and an episode left with no bookmark goes with them")
    (is (= (profile/bookmarks phone) (profile/bookmarks after)))
    (is (= (profile/clips phone) (profile/clips after)))
    (is (= (profile/rated phone) (profile/rated after)))))

(deftest opml-round-trip
  (let [s    (-> (profile/make "phone")
                 (profile/subscribe "917393e3-1b1e-5cef-ace4-edaa54e1f810" {:url "https://feeds.podcastindex.org/pc20.xml" :title "Podcasting 2.0"} t0)
                 (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                 (profile/unsubscribe "feed-b" t1))
        list (profile/to-opml s)
        xml  (opml/emit list)]
    (is (= [{:text "Podcasting 2.0" :title "Podcasting 2.0" :xml-url "https://feeds.podcastindex.org/pc20.xml"
             :guid "917393e3-1b1e-5cef-ace4-edaa54e1f810" :created t0}]
           (:subscriptions list)))
    (is (= (update-vals (profile/subscriptions s) #(select-keys % [:url :title]))
           (update-vals (profile/subscriptions (profile/from-opml (profile/make "laptop") (opml/parse xml) t2))
                        #(select-keys % [:url :title])))
        "the guid carried in the OPML identifies the feed on the other device")
    (testing "an outline without a guid gets the one its URL derives"
      (is (profile/subscribed? (profile/from-opml (profile/make "x") {:subscriptions [{:text "PN" :xml-url "https://podnews.net/rss"}]} t0)
                               "9b024349-ccf0-5f69-a609-6b82873eab3c")))
    (testing "the feed's title before the text, which some apps fill with the description"
      (is (= "Podnews" (get-in (profile/from-opml (profile/make "x")
                                                  {:subscriptions [{:text "Daily news about podcasting" :title "Podnews"
                                                                    :xml-url "https://podnews.net/rss"}]}
                                                  t0)
                               [:subscriptions "9b024349-ccf0-5f69-a609-6b82873eab3c" :title]))))
    (testing "a folder around the whole list is a wrapper, not a folder, when its name is one that apps write"
      (let [around  (fn [folder]
                      (opml/parse (str "<opml version=\"1.0\"><head><title>Feeds</title></head><body><outline text=\"" folder "\">
                                          <outline type=\"rss\" text=\"A\" xmlUrl=\"https://a/rss\"/>
                                          <outline type=\"rss\" text=\"B\" xmlUrl=\"https://b/rss\"/></outline></body></opml>")))
            folders (fn [list opts] (keys (profile/folders (profile/from-opml (profile/make "x") list t0 opts))))]
        (is (= [nil] (keys (profile/folders (profile/from-opml (profile/make "x") (around "feeds") t0)))))
        (is (= [nil] (folders (around "Feeds") {})) "in any case")
        (is (= ["News"] (folders (around "News") {})) "a folder of the listener stays, also when it holds every show")
        (is (= ["feeds"] (folders (around "feeds") {:wrappers #{}})) "the names are an option")
        (is (= [nil] (folders (around "Podcasts") {:wrappers #{"podcasts"}})))
        (is (= [nil] (folders (around "News") {:wrappers (constantly true)})) "and so is a rule for any name")))
    (testing "the one folder of a listener, with a list in it, comes back as it went out"
      (let [s    (-> (profile/make "phone")
                     (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                     (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                     (profile/configure "feed-a" {:folder "News"} t0)
                     (profile/configure "feed-b" {:folder "News"} t0)
                     (profile/follow-list "https://l/picks.opml" {:title "Picks" :folder "News"} t0))
            back (profile/from-opml (profile/make "laptop") (opml/parse (opml/emit (profile/to-opml s))) t1)]
        (is (= {"News" ["feed-a" "feed-b"]} (profile/folders back)))
        (is (= (profile/lists s) (profile/lists back)))))
    (testing "a show that the state has by its URL keeps its guid"
      (let [s    (-> (profile/make "phone")
                     (profile/subscribe "declared" {:url "https://a.example/rss" :title "A"} t0)
                     (profile/subscribe "private" {:url "https://simon:secret@members.example/rss" :title "Members"} t0))
            list {:subscriptions [{:text "A, again" :xml-url "HTTP://A.example/rss/"}
                                  {:text "A, from another app" :xml-url "https://a.example/rss" :guid "other"}
                                  {:text "Members" :xml-url "https://members.example/rss"}
                                  {:text "B" :xml-url "https://b.example/rss" :guid " "}]}
            back (profile/from-opml s list t1)]
        (is (= #{"declared" "private" (guid/from-url "https://b.example/rss")} (set (keys (profile/subscriptions back))))
            "whatever the scheme, the case and the trailing slash, and a blank guid is none")
        (is (= ["https://a.example/rss" "https://simon:secret@members.example/rss"]
               (map #(get-in back [:subscriptions % :url]) ["declared" "private"]))
            "and its URL, so a private feed keeps its password")))))

(deftest merging-two-devices
  (let [phone  (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                   (profile/progress a 100 3600 t1)
                   (profile/enqueue a t1)
                   (profile/prefer {:skip-forward 45} t1))
        laptop (-> (profile/make "laptop")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/unsubscribe "feed-b" t2)
                   (profile/progress a 900 3600 t2)
                   (profile/enqueue c t3)
                   (profile/prefer {:skip-forward 60} t2))
        merged (profile/merge phone laptop)]
    (is (= "phone" (:device merged)))
    (is (not (profile/subscribed? merged "feed-b")) "the later removal beats the earlier addition")
    (is (= 900 (:position (profile/episode merged a))) "the later position wins")
    (is (= [a c] (profile/queue merged)) "what both devices queued survives, in the order it was queued")
    (is (= 60 (:skip-forward (profile/preferences merged))) "the later preferences win")
    (testing "the same whichever side asks, and again"
      (is (= (dissoc merged :device) (dissoc (profile/merge laptop phone) :device)))
      (is (= merged (profile/merge merged laptop)))
      (is (= merged (profile/merge merged merged))))
    (testing "a fresh device takes everything, the clock included"
      (let [fresh (profile/merge (profile/make "tablet") laptop)]
        (is (= [c] (profile/queue fresh)))
        (is (= (:clock laptop) (:clock fresh)))
        (is (pos? (compare (:clock fresh) t3)) "the laptop's preferences, written last, were stamped after t3")
        (is (= "tablet" (:device fresh)))))))

(deftest merging-by-shape
  (let [note   (fn [s id text at] (assoc-in s [:bookmarks id] {:text text :updated-at at :device (:device s)}))
        phone  (-> (profile/make "phone") (note "n1" "old" t0) (note "n2" "phone" t1) (assoc :theme "light"))
        laptop (-> (profile/make "laptop") (note "n1" "new" t2) (assoc :theme "dark" :zoom 2))
        merged (profile/merge phone laptop)]
    (is (= {"n1" "new" "n2" "phone"} (update-vals (:bookmarks merged) :text))
        "an app's own stamped entries merge like the library's")
    (is (= "light" (:theme merged)) "what is not stamped stays the local device's")
    (is (= "the value at [:theme] is not in a stamped entry" (profile/problem (assoc (profile/make "phone") :theme "light")))
        "so problem names it")
    (is (= 2 (:zoom merged)) "unless only the other device has it")
    (is (= (dissoc (profile/merge laptop phone) :device :theme) (dissoc merged :device :theme)))))

(deftest what-another-device-may-not-send
  (testing "what the operations write passes"
    (is (nil? (profile/problem (-> (profile/make "phone")
                                   (profile/subscribe "feed-a" {:url "https://a/rss"} t0)
                                   (profile/configure "feed-a" {:skip-intro 20} t0)
                                   (profile/progress a 100 3600 t1)
                                   (profile/enqueue b t1)
                                   (profile/clip a 30 10 "A clip" t1)
                                   (profile/unclip a 30 t2)
                                   (profile/rate-episode a 4 t1)
                                   (profile/unrate-episode a t2)
                                   (profile/rate-episode b 50 t1 {:scale {:worst 1 :best 100}})
                                   (crdt/acknowledge [(profile/subscribe (profile/make "laptop") "feed-b" {:url "https://b/rss"} t0)] t2))))))
  (testing "what would make an operation throw on every device it reached"
    (doseq [[state found] [[{:clock "now"} #"clock"]
                           [{:episodes {a {:updated-at "soon" :device "x"}}} #"stamped \"soon\""]
                           [{:subscriptions [1 2 3]} #":subscriptions is not a map"]
                           [{:episodes {a "x"}} #"not a map"]
                           [{:queue {a {:rank "V!" :updated-at t0 :device "x"}}} #"not a rank"]
                           [{:playlists {"P" {a {:rank "V!" :updated-at t0 :device "x"}}}} #"the playlist \"P\""]
                           [{:devices {"x" {:seen {"y" 5} :updated-at t0 :device "x"}}} #"seen"]
                           [{:lists {"https://l/1.opml" {:updated-at t0 :device "x"} 5 {:updated-at t0 :device "x"}}}
                            #"the list at 5 is not at a URL"]
                           [{:bookmarks {a {754.5 {:title "x" :updated-at t0 :device "x"}}}} #"whole number of seconds"]
                           [{:bookmarks {a {-1 {:title "x" :updated-at t0 :device "x"}}}} #"whole number of seconds"]
                           [{:clips {a {30.5 {:duration 10 :updated-at t0 :device "x"}}}}
                            #"a clip of .* is not at a whole number of seconds"]
                           [{:clips {a {30 {:duration "long" :updated-at t0 :device "x"}}}}
                            #"lasts \"long\", not a number of seconds"]
                           [{:ratings {a {:rating 7 :updated-at t0 :device "x"}}}
                            #"is 7, not a number of the scale \{:worst 1, :best 5\}"]
                           [{:ratings {a {:rating "good" :updated-at t0 :device "x"}}} #"is \"good\""]
                           [{:ratings {a {:rating 101 :worst 1 :best 100 :updated-at t0 :device "x"}}}
                            #"is 101, not a number of the scale"]
                           [{:ratings {a {:rating 3 :worst 3 :best 3 :updated-at t0 :device "x"}}}
                            #"not a number of the scale"]
                           [{:filters {"F" {:criteria [:where] :updated-at t0 :device "x"}}} #"criteria of the filter \"F\""]
                           [{:tags {"feed-a" "Tech"}} #"\[:tags \"feed-a\"\] is not a map"]
                           [{:app/notes {"n1" "text"}} #"\[:app/notes \"n1\"\] is not in a stamped entry"]]]
      (is (re-find found (str (profile/problem state))) (pr-str state))))
  (testing "an entry without a stamp, which two devices merge to two different states"
    (let [here  {:device "a" :stars {a {:removed? false}}}
          there {:device "b" :stars {a {:removed? true}}}]
      (is (not= (dissoc (profile/merge here there) :device) (dissoc (profile/merge there here) :device)))
      (is (= "the entry at [:stars [\"feed-a\" \"a1\"]] is not stamped" (profile/problem here)))
      (doseq [state [{:queue {a {}}} {:settings {"feed-a" {:folder {:value "News"}}}}
                     {:listened {"feed-a" {"2026-09" {"x" {:seconds 5}}}}} {:retired {"x" {}}}]]
        (is (re-find #"is not stamped" (str (profile/problem state))) (pr-str state)))))
  (testing "criteria of a later version are no problem of the state"
    (is (nil? (profile/problem {:filters {"F" {:criteria {:where {:mood "calm"}} :updated-at t0 :device "x"}}})))))

(deftest merging-entries-with-keys-of-any-kind
  (let [s (crdt/identify (profile/make "phone") {:name "Phone" "custom" 1} t0)]
    (is (= s (profile/merge s s)) "an entry ties with itself, and its keys still sort")))

(deftest a-tie-between-two-entries
  (let [saved  (fn [criteria] {:device "p" :filters {"F" {:criteria criteria :removed? false :updated-at t0 :device "p"}}})
        order  #(get-in (profile/merge %1 %2) [:filters "F" :criteria :order])
        one    (array-map :order :newest :where (array-map :starred? true :status #{:played :unplayed :in-progress}))
        other  (array-map :where (array-map :status #{:unplayed :in-progress} :starred? false) :order :oldest)
        turned (fn [m] (into (array-map) (map (fn [[k v]] [k (if (map? v) (into (array-map) (reverse v)) v)])) (reverse m)))]
    (is (= (turned one) one) "the same criteria, with their keys in another order")
    (is (= (order (saved one) (saved other))
           (order (saved other) (saved one))
           (order (saved (turned one)) (saved (turned other)))
           (order (saved (turned other)) (saved one)))
        "the content decides, and not the order of the keys in a map of the entry")
    (testing "a whole number prints without a fraction on both platforms, so both pick the same entry"
      (let [rate (fn [v] {:device "p" :preferences {:rate {:value v :updated-at t0 :device "p"}}})]
        (is (== 1 (:rate (profile/preferences (profile/merge (rate 1.0) (rate 1.5))))))
        (is (== 1 (:rate (profile/preferences (profile/merge (rate 1.5) (rate 1.0))))))))))

(deftest devices-and-the-clock
  (let [s (-> (profile/make "phone-id")
              (crdt/identify {:name "Simon's phone" :platform "ios"} t0)
              (profile/progress a 100 3600 t1))]
    (is (= {"phone-id" {:name "Simon's phone" :platform "ios" :updated-at t0 :device "phone-id"}}
           (crdt/devices s)))
    (is (= t1 (:clock s)) "the clock is the latest instant written")
    (is (= t2 (crdt/after s t2)) "a later now is used as it is")
    (is (= (time/->inst (inc (inst-ms t1))) (crdt/after s t0)) "an earlier now becomes the millisecond after the clock")
    (is (= t2 (:clock (profile/merge s (profile/progress (profile/make "x") b 5 t2)))) "and it follows what is merged")
    (let [s (-> s (crdt/write [:notes "x"] #(assoc % :text "one") t0) (crdt/write [:notes "y"] #(assoc % :text "two") t0))]
      (is (not= (get-in s [:notes "x" :updated-at]) (get-in s [:notes "y" :updated-at]))
          "two entries of the app's own, written through write, are never stamped alike")
      (is (= (get-in s [:notes "y" :updated-at]) (:clock s))))
    (testing "an import of many shows stamps them all at once, so the clock stays at the time"
      (let [list {:subscriptions (vec (for [i (range 300)]
                                        {:xml-url (str "https://x/" i) :text (str i) :path ["News"] :category ["Tech"]}))}
            s    (profile/from-opml (profile/make "phone") list t1)]
        (is (= 300 (count (profile/subscriptions s))))
        (is (= t1 (:clock s)))
        (is (= #{t1} (set (map :updated-at (concat (vals (:subscriptions s)) (mapcat vals (vals (:tags s))))))))
        (is (nil? (profile/problem s)) "and leaves nothing of the import in the state")))
    (testing "retiring a device from another"
      (let [laptop (crdt/retire (profile/make "laptop-id") "phone-id" t2)
            merged (profile/merge s laptop)]
        (is (crdt/retired? merged "phone-id"))
        (is (= {} (crdt/devices merged)))
        (is (= {} (crdt/devices (profile/merge (crdt/identify merged {:name "Back"} t3) laptop)))
            "describing itself again does not undo it")))))

(deftest writing-as-another-device
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t1)
              (profile/unsubscribe "feed-a" t3))]
    (testing "a write at the other device's own time, under its name"
      (let [w (crdt/as-device s "gpodder:x" #(profile/subscribe % "feed-b" {:url "https://b/rss"} t2))]
        (is (= {:updated-at t2 :device "gpodder:x"} (select-keys (get-in w [:subscriptions "feed-b"]) [:updated-at :device])))
        (is (= "phone" (:device w)))
        (is (= t3 (:clock w)) "the clock stays at the later instant")
        (is (nil? (profile/problem w)))))
    (testing "an entry that's later than the write stays, as a merge keeps it"
      (is (= s (crdt/as-device s "gpodder:x" #(profile/subscribe % "feed-a" {:url "https://a/rss"} t2)))))
    (testing "the same as a merge of the other device's state"
      (is (= (profile/episode (crdt/as-device s "laptop" #(profile/progress % a 100 3600 t4)) a)
             (profile/episode (profile/merge s (profile/progress (profile/make "laptop") a 100 3600 t4)) a))))))

(deftest acknowledging-and-compacting
  (let [day    (* 24 60 60 1000)
        far    (time/->inst (+ (inst-ms t3) (* 31 day)))
        phone  (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                   (profile/unsubscribe "feed-b" t1)
                   (profile/enqueue a t1)
                   (profile/dequeue a t2))
        laptop (crdt/acknowledge (profile/merge (profile/make "laptop") phone) [phone] t3)
        phone  (crdt/acknowledge (profile/merge phone laptop) [laptop] t3)]
    (testing "acknowledging what another device wrote"
      (is (= {"phone" t2} (get-in laptop [:devices "laptop" :seen])) "the latest the phone wrote, outside the registry")
      (is (= t3 (get-in laptop [:devices "laptop" :updated-at])))
      (is (= laptop (crdt/acknowledge laptop [phone] t4)) "nothing new leaves the entry alone")
      (is (= laptop (crdt/acknowledge laptop [(crdt/identify phone {:name "P"} t4)] t4)) "the registry itself is never news")
      (is (= {} (get-in phone [:devices "phone" :seen])) "the laptop had written nothing of its own"))
    (testing "a tombstone goes once every other live device has seen it and it is old enough"
      (is (= 2 (count (:subscriptions phone))))
      (is (= (:subscriptions phone) (:subscriptions (profile/compact phone t4))) "too fresh")
      (let [compacted (profile/compact phone far)]
        (is (= ["feed-a"] (keys (:subscriptions compacted))))
        (is (= {} (:queue compacted)))
        (is (= (profile/subscriptions phone) (profile/subscriptions compacted)) "nothing the listener sees changed")
        (is (= (profile/queue phone) (profile/queue compacted))))
      (is (= (:subscriptions phone) (:subscriptions (profile/compact phone far {:keep-for (* 40 day)}))) "the age is an option")
      (is (= {} (crdt/lagging phone)) "the laptop is up to date"))
    (testing "a device that has not caught up holds compaction back, until it is retired"
      (let [tablet (crdt/identify (profile/make "tablet") {:name "Tablet"} t0)
            phone  (profile/merge phone tablet)]
        (is (= 2 (count (:subscriptions (profile/compact phone far)))))
        (is (= {"tablet" nil} (crdt/lagging phone)) "it has merged nothing")
        (is (= {"tablet" nil "laptop" t1} (crdt/lagging (assoc-in phone [:devices "laptop" :seen "phone"] t1)))
            "a device part of the way is named with how far it got")
        (is (= ["feed-a"] (keys (:subscriptions (profile/compact (crdt/retire phone "tablet" t3) far)))))))))

(deftest the-changes-since-a-state
  (let [taken   (-> (profile/make "phone")
                    (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                    (profile/prefer {:rate 1.5} t1))
        s       (-> taken
                    (profile/progress a 100 3600 t2)
                    (profile/enqueue a t3))
        changes (crdt/changes-since s taken)]
    (is (= #{:device :clock :episodes :queue} (set (keys changes)))
        "what was written since the state was taken, with the device and the clock")
    (is (= (profile/episode s a) (profile/episode changes a)))
    (is (= s (profile/merge s changes)) "a partial state merges into the whole without changing it")
    (let [laptop (profile/subscribe (profile/make "laptop") "feed-b" {:url "https://b/rss" :title "B"} t0)]
      (is (contains? (:subscriptions (crdt/changes-since (profile/merge s laptop) taken)) "feed-b")
          "and what was merged in since, however early its stamp"))
    (is (= (dissoc s :device)
           (dissoc (profile/merge (profile/make "laptop") (crdt/changes-since s nil)) :device))
        "nil means everything")))

(deftest stepping
  (let [s (-> (profile/make "phone") (profile/enqueue a t0) (profile/enqueue b t0))]
    (testing "progress reports when an episode becomes played"
      (let [{:keys [state effects]} (profile/step s [:progress a 3590 3600] t1)]
        (is (profile/played? state a))
        (is (= [[:played a]] effects))
        (is (= [] (:effects (profile/step state [:progress a 3595 3600] t2))) "only once")))
    (testing "ending the current episode plays the next"
      (let [{:keys [state effects]} (profile/step s [:ended a] t1)]
        (is (profile/played? state a))
        (is (= [b] (profile/queue state)))
        (is (= [[:play b]] effects))))
    (testing "ending an episode that is not current leaves the queue alone"
      (let [{:keys [state effects]} (profile/step s [:ended c] t1)]
        (is (= [a b] (profile/queue state)))
        (is (= [] effects))))
    (is (= [c a b] (profile/queue (:state (profile/step s [:play c] t1)))))
    (is (= {:state s :effects []} (profile/step s [:volume 3] t1)) "unknown events change nothing")))

(deftest skipping-the-outro
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/configure "feed-a" {:skip-outro 60} t0)
              (profile/enqueue a t0)
              (profile/enqueue b t0)
              (profile/progress a 3500 3600 t0))]
    (is (= 3540 (profile/outro-start s "feed-a" a)))
    (is (nil? (profile/outro-start s "feed-a" b)) "not before the duration is known")
    (testing "a report in the outro ends the episode there"
      (let [{:keys [state effects]} (profile/step s [:progress a 3545 3600] t1)]
        (is (= [[:stop a] [:played a] [:play b]] effects))
        (is (profile/played? state a))
        (is (= [b] (profile/queue state)))
        (is (= [] (:effects (profile/step state [:progress a 3550 3600] t2))) "once: a late report changes nothing")))
    (testing "so does a skip into it, which then needs no seek"
      (is (= [[:stop a] [:played a] [:play b]] (:effects (profile/step s [:skip a 45] t1))))
      (is (= [[:seek a 3530]] (:effects (profile/step s [:skip a :forward] t1)))))
    (testing "an episode that counts as played already, and one heard again"
      (let [short (profile/configure s "feed-a" {:skip-outro 20} t0)
            {:keys [state effects]} (profile/step short [:progress a 3575 3600] t1)]
        (is (= [[:played a]] effects) "within the threshold of the end, but not yet in the outro")
        (is (= [[:stop a] [:play b]] (:effects (profile/step state [:progress a 3585 3600] t2)))))
      (let [again (-> s (profile/mark-played a t1) (profile/progress a 20 t2))]
        (is (= [[:stop a] [:play b]] (:effects (profile/step again [:progress a 3541 3600] t3))))))
    (testing "an episode that is not current only stops"
      (let [s (-> s
                  (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                  (profile/configure "feed-b" {:skip-outro 60} t0))
            {:keys [state effects]} (profile/step s [:progress c 3545 3600] t1)]
        (is (= [[:stop c] [:played c]] effects))
        (is (= [a b] (profile/queue state)))))
    (testing "no outro, or one as long as the episode, ends nothing early"
      (is (= [[:played a]] (:effects (profile/step (profile/configure s "feed-a" {:skip-outro 0} t1) [:progress a 3590 3600] t2))))
      (is (= [[:played b]] (:effects (profile/step (profile/progress s b 10 50 t1) [:progress b 45 50] t2)))))))

(def chapters-doc
  "A chapters document with a chapter that is not in the table of contents."
  {:chapters [{:start-time 0 :title "Intro"}
              {:start-time 90 :title "News"}
              {:start-time 300 :title "Ad" :toc false}
              {:start-time 600 :title "Talk"}]})

(deftest moving-between-chapters
  (let [s      (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/enqueue a t0)
                   (profile/enqueue b t0)
                   (profile/progress a 100 3600 t0))
        jump   (fn [state direction] (profile/step state [:chapter a direction chapters-doc] t1))
        at     (fn [position] (profile/progress s a position 3600 t0))
        seek   (fn [position] [[:seek a position]])]
    (testing "to the next chapter of the table of contents"
      (let [{:keys [state effects]} (jump s :next)]
        (is (= (seek 600) effects) "the chapter outside the table of contents is no stop")
        (is (= 600 (:position (profile/episode state a))))))
    (testing "to the start of the chapter that plays, or to the chapter before it"
      (is (= (seek 90) (:effects (jump s :previous))))
      (is (= (seek 0) (:effects (jump (at 92) :previous))) "in the first seconds of a chapter")
      (is (= (seek 0) (:effects (profile/step s [:chapter a :previous chapters-doc] t1 {:restart 15})))
          "with the seconds that the app gives")
      (is (= (seek 90) (:effects (jump (at 601) :previous))))
      (is (= (seek 0) (:effects (jump (at 1) :previous)))))
    (testing "after the start of the last chapter, the next chapter is no jump"
      (is (= {:state (at 700) :effects []} (jump (at 700) :next))))
    (testing "an episode whose position the state does not have starts at 0"
      (is (= [[:seek b 90]] (:effects (profile/step s [:chapter b :next chapters-doc] t1)))))
    (testing "a document with no chapters"
      (is (= [] (:effects (profile/step s [:chapter a :next {}] t1))))
      (is (= (seek 0) (:effects (profile/step s [:chapter a :previous {}] t1)))))
    (testing "a jump into the outro ends the episode, as a skip does"
      (let [outro (profile/configure s "feed-a" {:skip-outro 60} t0)]
        (is (= [[:stop a] [:played a] [:play b]]
               (:effects (profile/step outro [:chapter a :next {:chapters [{:start-time 3570}]}] t1))))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (jump s :sideways)) "a direction that is none")))

(deftest the-sleep-timer
  (let [s     (-> (profile/make "phone") (profile/enqueue a t0) (profile/enqueue b t0))
        sleep (fn [n] {:sleep {:episodes n}})]
    (testing "at the end of this episode"
      (let [{:keys [state effects] :as result} (profile/step s [:ended a] t1 (sleep 1))]
        (is (= [[:stop a]] effects) "the player stops, and the next episode does not play")
        (is (= [b] (profile/queue state)) "the next episode is current, for the next time")
        (is (profile/played? state a))
        (is (not (contains? result :sleep)) "the timer ran out")))
    (testing "after some episodes"
      (let [first-end  (profile/step s [:ended a] t1 (sleep 2))
            second-end (profile/step (:state first-end) [:ended b] t2 (select-keys first-end [:sleep]))]
        (is (= [[[:play b]] {:episodes 1}] ((juxt :effects :sleep) first-end)))
        (is (= [[:stop b]] (:effects second-end)))
        (is (not (contains? second-end :sleep)))))
    (testing "each other event keeps the timer as it is"
      (doseq [event [[:progress a 100 3600] [:skip a 30] [:play b] [:chapter a :next chapters-doc] [:volume 3]]]
        (is (= {:episodes 2} (:sleep (profile/step s event t1 (sleep 2)))) (pr-str event))))
    (testing "without a timer, the result has none"
      (is (= #{:state :effects} (set (keys (profile/step s [:ended a] t1)))))
      (is (= #{:state :effects} (set (keys (profile/step s [:ended a] t1 {}))))))
    (testing "an episode that ends in its outro"
      (let [outro (-> s
                      (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                      (profile/configure "feed-a" {:skip-outro 60} t0)
                      (profile/progress a 3500 3600 t0))]
        (is (= [[:stop a] [:played a]] (:effects (profile/step outro [:progress a 3545 3600] t1 (sleep 1))))
            "one stop, and no next episode")
        (is (= [[[:stop a] [:played a] [:play b]] {:episodes 1}]
               ((juxt :effects :sleep) (profile/step outro [:progress a 3545 3600] t1 (sleep 2)))))))
    (testing "the last episode of the queue"
      (is (= [[:stop b]] (:effects (profile/step (:state (profile/step s [:ended a] t1)) [:ended b] t2 (sleep 1))))))
    (testing "the timer is no part of the state"
      (is (= (:state (profile/step s [:ended a] t1)) (:state (profile/step s [:ended a] t1 (sleep 3))))))))
