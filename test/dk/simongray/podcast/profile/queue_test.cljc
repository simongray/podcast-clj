(ns dk.simongray.podcast.profile.queue-test
  "The queue, the inbox and the playlists of a profile, and the refreshes
  of feeds."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.mind-meld.list :as crdt.list]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.player :as profile.player]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 a b c]]
            [dk.simongray.podcast.util.time :as time]))

(deftest the-queue
  (let [s (-> (profile/make "phone") (profile.queue/enqueue a t0) (profile.queue/enqueue b t0) (profile.queue/enqueue a t1))]
    (is (= [a b] (profile.queue/queue s)))
    (is (= a (profile.queue/current s)))
    (is (profile.queue/queued? s b))
    (is (= [a c b] (profile.queue/queue (profile.queue/play-next s c t1))))
    (is (= [a b] (profile.queue/queue (profile.queue/play-next s a t1))) "the current episode stays current")
    (is (= [c] (profile.queue/queue (profile.queue/play-next (profile/make "phone") c t1))))
    (is (= [b] (profile.queue/queue (profile.queue/dequeue s a t1))))
    (is (= [b a] (profile.queue/queue (profile.queue/move s a 5 t1))) "an index past the end means the end")
    (is (= [b a c] (profile.queue/queue (profile.queue/enqueue (profile.queue/move s a 5 t1) c t1))) "a new episode goes after the last")
    (is (= [a c] (profile.queue/queue (profile.queue/enqueue (profile.queue/dequeue s b t1) c t1))) "and a removed one takes no place")
    (is (= [b a c] (profile.queue/queue (profile.queue/move (profile.queue/enqueue s c t1) a 1.9 t1)))
        "an index that is not whole counts as the whole number below it")
    (is (= [b a] (profile.queue/queue (profile.queue/move s b ##NaN t1))) "and NaN as 0, on both platforms")
    (is (= [b] (profile.queue/queue (profile.queue/advance s t1))))
    (is (= [] (profile.queue/queue (profile.queue/advance (profile/make "x") t1))))
    (is (= t2 (get-in (profile.queue/advance s t2) [:queue a :updated-at])) "the current episode is left as a tombstone")
    (let [seen (:clock s)]
      (is (= (time/->inst (inc (inst-ms seen))) (get-in (profile.queue/advance s seen) [:queue a :updated-at]))
          "a write at an instant the state has seen is stamped the millisecond after"))))

(deftest the-queue-across-devices
  (let [shared (-> (profile/make "phone") (profile.queue/enqueue a t0) (profile.queue/enqueue b t0))
        phone  (-> shared (profile.queue/advance t1) (profile.queue/enqueue c t2))
        laptop (-> (profile/merge (profile/make "laptop") shared) (profile.queue/enqueue ["feed-c" "c1"] t3))
        merged (profile/merge phone laptop)]
    (is (= [b c ["feed-c" "c1"]] (profile.queue/queue merged))
        "the episode finished on the phone stays gone, and what both devices added survives, in order")
    (is (= (profile.queue/queue merged) (profile.queue/queue (profile/merge laptop phone))))
    (testing "two devices appending at once tie on the rank, and the stamp orders them"
      (let [d      ["feed-d" "d1"]
            e      ["feed-e" "e1"]
            phone2 (profile.queue/enqueue phone d t3)
            laptop2 (profile.queue/enqueue (profile/merge (profile/make "laptop") phone) e t3)
            both   (profile/merge phone2 laptop2)]
        (is (= [b c e d] (profile.queue/queue both)) "the laptop's stamp is the later, so its episode comes last")
        (is (= (get-in both [:queue d :rank]) (get-in both [:queue e :rank])))
        (is (= [b ["feed-x" "x"] c e d] (profile.queue/queue (profile.queue/play-next both ["feed-x" "x"] t3)))
            "a place between distinct ranks is found")
        (is (= [b c e d ["feed-x" "x"]] (profile.queue/queue (profile.queue/move (profile.queue/enqueue both ["feed-x" "x"] t3) ["feed-x" "x"] 3 t3)))
            "a place between two tied ranks is after both of them")))
    (testing "two devices that append several episodes at once keep each batch together"
      (let [d       ["feed-d" "d1"]
            e       ["feed-d" "d2"]
            phone3  (crdt.list/append (profile/make "phone") [:queue] {} [a b c] t0)
            laptop3 (crdt.list/append (profile/make "laptop") [:queue] {} [d e] t1)]
        (is (= [d e a b c] (profile.queue/queue (profile/merge phone3 laptop3)) (profile.queue/queue (profile/merge laptop3 phone3))))))
    (testing "a move never brings back an episode that another device took out"
      (let [three   (-> (profile/make "phone") (profile.queue/enqueue a t0) (profile.queue/enqueue b t0) (profile.queue/enqueue c t0))
            phone4  (profile.queue/dequeue three b t1)
            laptop4 (profile.queue/move (assoc three :device "laptop") b 0 t2)]
        (is (= [b a c] (profile.queue/queue laptop4)) "until the devices merge")
        (is (= [a c] (profile.queue/queue (profile/merge phone4 laptop4)) (profile.queue/queue (profile/merge laptop4 phone4))))
        (is (= [a c] (profile.queue/queue (profile/merge phone4 (profile.queue/play-next (assoc three :device "laptop") b t2))))
            "nor does play-next of a queued episode")
        (is (= [b a c] (profile.queue/queue (profile/merge phone4 (:profile (profile.player/step laptop4 [:play b] t2)))))
            "but playing it puts it back")))
    (testing "ranks stay short as a queue grows, at its end and at its front"
      (let [long   (reduce (fn [s i] (profile.queue/enqueue s ["f" (str i)] t0)) (profile/make "x") (range 2000))
            played (reduce (fn [s i] (:profile (profile.player/step s [:play ["g" (str i)]] t0))) long (range 200))
            digits #(apply max (map (comp count :rank) (vals (:queue %))))]
        (is (= 2000 (count (profile.queue/queue long))))
        (is (= 3 (digits long)))
        (is (= (concat (for [i (range 199 -1 -1)] ["g" (str i)]) (for [i (range 2000)] ["f" (str i)]))
               (profile.queue/queue played)))
        (is (= 3 (digits played)))))))

(deftest refreshing-a-feed
  (let [item   (fn [id] {:rss/guid {:content id} :rss/title id})
        ;; as a library keeps it, under its key, since its guid isn't a UUID
        feed-a (fn [items] (guid/with-key {:podcast/guid "feed-a" :rss/items items} "feed-a"))
        before (feed-a [(item "a2") (item "a1")])
        after  (feed-a [(item "a4") (item "a3") (item "a2") (item "a1")])
        s      (-> (profile/make "phone") (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0))]
    (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (:added (feed/new-episodes "https://a/rss" before after))) "oldest first")
    (testing "oldest first by date also for a feed that lists them so, and none on a first parse"
      (let [dated (fn [id day] (assoc (item id) :rss/pub-date (time/->inst (* day 86400000))))
            after (feed-a [(dated "a1" 1) (dated "a2" 2) (dated "a3" 3) (dated "a4" 4)])]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (:added (feed/new-episodes "https://a/rss" before after))))
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]]
               (:added (feed/new-episodes "https://a/rss" before (feed-a [(dated "a2" 2) (dated "a3" 3) (dated "a4" 4) (dated "a1" 1)]))))
            "by the first two dates, since the items that a library keeps of a feed follow those it serves")
        (is (= {:added [] :backfilled [] :renamed []} (feed/new-episodes "https://a/rss" nil after)))))
    (testing "without auto-queue the new episodes are reported, not queued, and join the inbox"
      (let [{:keys [profile added]} (profile.queue/refresh s "https://a/rss" before after t1)]
        (is (= 2 (count added)))
        (is (= [] (profile.queue/queue profile)))
        (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (profile.queue/inbox profile)) "by default")
        (is (= [] (profile.queue/inbox (:profile (profile.queue/refresh (profile/configure s "feed-a" {:inbox? false} t0)
                                                                        "https://a/rss" before after t1))))
            "unless the show's settings say otherwise")))
    (testing "with auto-queue they join the queue, oldest first"
      (let [{:keys [profile]} (profile.queue/refresh (profile/configure s "feed-a" {:auto-queue? true} t0) "https://a/rss" before after t1)]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (profile.queue/queue profile)))
        (is (= [] (profile.queue/inbox profile)))
        (is (= profile (:profile (profile.queue/refresh profile "https://a/rss" after after t2))) "a parse with no new episode changes nothing")))
    (testing "many new episodes join the queue with short ranks"
      (let [grown (feed-a (into (mapv #(item (str "n" %)) (range 3000 0 -1)) (:rss/items before)))
            s     (-> (profile/configure s "feed-a" {:auto-queue? true} t0) (profile.queue/enqueue ["feed-b" "b1"] t0))
            {:keys [profile added]} (profile.queue/refresh s "https://a/rss" before grown t1)]
        (is (= 3000 (count added)))
        (is (= (into [["feed-b" "b1"]] added) (profile.queue/queue profile)) "after what the queue holds, in their order")
        (is (= 6 (apply max (map (comp count :rank) (vals (:queue profile)))))
            "three digits, after the three of the device's block")))
    (testing "else, when the settings ask for it, the inbox, the newest first"
      (let [{:keys [profile]} (profile.queue/refresh (profile/configure s "feed-a" {:inbox? true} t0) "https://a/rss" before after t1)]
        (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (profile.queue/inbox profile)))
        (is (= [] (profile.queue/queue profile)))))
    (testing "an episode another device has handled stays as the listener left it"
      (let [phone  (-> (profile/configure s "feed-a" {:auto-queue? true} t0)
                       (profile.queue/refresh "https://a/rss" before after t1)
                       :profile
                       (profile/mark-played ["feed-a" "a3"] t2)
                       (profile.queue/dequeue ["feed-a" "a3"] t2)
                       (profile.queue/dequeue ["feed-a" "a4"] t2))
            laptop (-> (profile/merge (profile/make "laptop") phone)
                       (profile.queue/refresh "https://a/rss" before after t3)
                       :profile)]
        (is (= [] (profile.queue/queue laptop)) "neither the one played nor the one taken out comes back")
        (let [inbox  (-> (profile/configure s "feed-a" {:inbox? true} t0)
                         (profile.queue/refresh "https://a/rss" before after t1)
                         :profile
                         (profile.queue/dismiss ["feed-a" "a4"] t2))
              laptop (:profile (profile.queue/refresh (profile/merge (profile/make "laptop") inbox) "https://a/rss" before after t3))]
          (is (= [["feed-a" "a3"]] (profile.queue/inbox laptop)) "nor the one dismissed from the inbox"))))
    (testing "nor when a device finds the episode before it learns what the listener did"
      (let [dated  (fn [id day] (assoc (item id) :rss/pub-date (time/->inst (* day 86400000))))
            after  (feed-a [(dated "a4" 4) (dated "a3" 3) (item "a2") (item "a1")])
            s      (profile/configure s "feed-a" {:auto-queue? true} t0)
            phone  (-> (:profile (profile.queue/refresh s "https://a/rss" before after t1))
                       (profile.queue/dequeue ["feed-a" "a3"] t2))
            laptop (:profile (profile.queue/refresh (profile/merge (profile/make "laptop") s) "https://a/rss" before after t3))]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (profile.queue/queue laptop)))
        (is (= #{"feed:feed-a"} (set (map :device (vals (:queue laptop))))) "written as the feed")
        (is (= [["feed-a" "a4"]] (profile.queue/queue (profile/merge laptop phone))))))
    (testing "nothing joins for a show that the listener left"
      (let [left (-> (profile/configure s "feed-a" {:auto-queue? true} t0) (profile/unsubscribe "feed-a" t1))]
        (is (= [] (profile.queue/queue (:profile (profile.queue/refresh left "https://a/rss" before after t2)))))))
    (testing "right after the current episode, oldest first"
      (let [queued (-> s (profile.queue/enqueue c t0) (profile.queue/enqueue ["feed-b" "b2"] t0)
                       (profile/configure "feed-a" {:auto-queue? :next} t0))]
        (is (= [c ["feed-a" "a3"] ["feed-a" "a4"] ["feed-b" "b2"]]
               (profile.queue/queue (:profile (profile.queue/refresh queued "https://a/rss" before after t1)))))
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]]
               (profile.queue/queue (:profile (profile.queue/refresh (profile/configure s "feed-a" {:auto-queue? :next} t0)
                                                                     "https://a/rss" before after t1))))
            "or at the front of an empty queue")
        (is (= [c ["feed-a" "a3"] ["feed-a" "a4"]]
               (profile.queue/queue (:profile (profile.queue/refresh (-> (profile.queue/enqueue s c t0)
                                                                         (profile/configure "feed-a" {:auto-queue? :next} t0))
                                                                     "https://a/rss" before after t1))))
            "or after the current one alone")))
    (testing "the newest of a most, with the others in the inbox"
      (let [capped (profile/configure s "feed-a" {:auto-queue? true :auto-queue-max 1 :inbox? true} t0)
            p      (:profile (profile.queue/refresh capped "https://a/rss" before after t1))]
        (is (= [["feed-a" "a4"]] (profile.queue/queue p)))
        (is (= [["feed-a" "a3"]] (profile.queue/inbox p)))
        (is (= [] (profile.queue/inbox (:profile (profile.queue/refresh (profile/configure s "feed-a" {:auto-queue? true :auto-queue-max 1 :inbox? false} t0)
                                                                        "https://a/rss" before after t1))))
            "or nowhere without :inbox?")))
    (testing "only those that meet the :join-pred, which go nowhere else"
      (let [both  (profile/configure s "feed-a" {:auto-queue? true :inbox? true} t0)
            a4?   (fn [_ item] (= "a4" (:rss/title item)))
            r     (profile.queue/refresh both "https://a/rss" before after t1 {:join-pred a4?})]
        (is (= [["feed-a" "a4"]] (profile.queue/queue (:profile r))))
        (is (= [] (profile.queue/inbox (:profile r))))
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (:added r)) "though both are new")))
    (testing "where :new-episodes sends them, whatever the settings say"
      (let [queued (profile/configure s "feed-a" {:auto-queue? true :inbox? false} t0)
            {:keys [profile]} (profile.queue/refresh queued "https://a/rss" before after t1
                                                     {:new-episodes :inbox})]
        (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (profile.queue/inbox profile)))
        (is (= [] (profile.queue/queue profile)))
        (is (= (profile/settings queued "feed-a") (profile/settings profile "feed-a"))
            "which stay as they were")
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]]
               (profile.queue/queue (:profile (profile.queue/refresh s "https://a/rss" before after t1
                                                                     {:new-episodes :queue})))))
        (is (= [] (profile.queue/inbox (:profile (profile.queue/refresh (profile/unsubscribe queued "feed-a" t1)
                                                                        "https://a/rss" before after t2
                                                                        {:new-episodes :inbox}))))
            "but nothing joins for a show that the listener left")))))

(deftest the-bound-on-new-episodes
  (let [dated  (fn [id day] {:rss/guid {:content id} :rss/title id :rss/pub-date (time/->inst (* day 86400000))})
        feed-a (fn [items] (guid/with-key {:podcast/guid "feed-a" :rss/items items} "feed-a"))
        before (feed-a [(dated "a2" 2) (dated "a1" 1)])
        after  (feed-a [{:rss/guid {:content "undated"}} (dated "a3" 3) (dated "a0" 0) (dated "a2" 2) (dated "a1" 1)])
        s      (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/configure "feed-a" {:auto-queue? true} t0))
        r      (profile.queue/refresh s "https://a/rss" before after t1)]
    (is (= {:added      [["feed-a" "a3"] ["feed-a" "undated"]]
            :backfilled [["feed-a" "a0"]]
            :renamed    []}
           (dissoc r :profile))
        "an episode older than the newest one known isn't new, and one without a date is")
    (is (= [["feed-a" "a3"] ["feed-a" "undated"]] (profile.queue/queue (:profile r))) "so only the new ones join the queue")
    (is (= [["feed-a" "a0"] ["feed-a" "a3"] ["feed-a" "undated"]]
           (:added (feed/new-episodes "https://a/rss" before after {:only-newer? false})))
        "unless every episode that appears should count as new")))

(deftest a-renamed-episode
  (let [item   (fn [id url title day]
                 {:rss/guid {:content id} :rss/enclosure {:url url} :rss/title title :rss/pub-date (time/->inst (* day 86400000))})
        feed-a (fn [items] (guid/with-key {:podcast/guid "feed-a" :rss/items items} "feed-a"))
        before (feed-a [(item "a2" "https://a/2.mp3" "Two" 2) (item "a1" "https://a/1.mp3" "One" 1)])
        ;; a new host: new guids, and the media of one behind a prefix
        after  (feed-a [(item "b2" "https://a/2.mp3" "Two" 2) (item "b1" "https://op3.dev/e/a/1.mp3" "One" 1)])
        [a1 a2 b1 b2] (map #(vector "feed-a" %) ["a1" "a2" "b1" "b2"])
        s      (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile.queue/enqueue a2 t0)
                   (profile/progress a1 754 3600 t0)
                   (profile/mark-played a1 t0))
        r      (profile.queue/refresh s "https://a/rss" before after t1)]
    (is (= {:added [] :backfilled [] :renamed [[a2 b2] [a1 b1]]} (dissoc r :profile))
        "by the enclosure URL, or else by the title and the date")
    (testing "takes the state of the episode along"
      (let [p (:profile r)]
        (is (= [b2] (profile.queue/queue p)))
        (is (= [754 true] ((juxt :position :played?) (profile/episode-entry p b1))))
        (is (= [754 true] ((juxt :position :played?) (profile/episode-entry p a1))) "and leaves what's never dropped")
        (is (= #{"phone"} (set (map :device [(get-in p [:queue b2]) (get-in p [:episodes b1])]))) "written by this device")))
    (testing "two devices that find the same rename agree, and the later progress wins"
      (let [phone  (-> (:profile r) (profile/progress b1 1200 3600 t2))
            laptop (-> (profile/merge (profile/make "laptop") s)
                       (profile/merge phone)
                       (profile.queue/refresh "https://a/rss" before after t3)
                       :profile)]
        (is (= 1200 (:position (profile/episode-entry laptop b1))) "a device that renames later doesn't undo it")
        (is (= (dissoc (profile/merge laptop phone) :device) (dissoc (profile/merge phone laptop) :device)))
        (is (= [b2] (profile.queue/queue (profile/merge laptop phone))))))))

(deftest the-inbox
  (let [c2 ["feed-b" "b2"]
        s  (-> (profile/make "phone")
               (crdt/write [:inbox a] (constantly {:removed? false}) t0)
               (crdt/write [:inbox b] (constantly {:removed? false}) t0)
               (crdt/write [:inbox c] (constantly {:removed? false}) t0)
               (crdt/write [:inbox c2] (constantly {:removed? false}) t0))]
    (is (= [c2 c b a] (profile.queue/inbox s)) "the latest to arrive first")
    (testing "an episode leaves the inbox when it is dismissed, queued or played"
      (let [s' (-> s (profile.queue/dismiss a t1) (profile.queue/enqueue b t1) (profile/mark-played c t1) (profile/progress c2 3590 3600 t1))]
        (is (= [] (profile.queue/inbox s')))
        (is (every? #(:removed? (get-in s' [:inbox %])) [a b c c2]) "each with a tombstone, which compaction can drop")))
    (is (= [c2 c a] (profile.queue/inbox (profile.queue/play-next s b t1))))
    (testing "and an episode played on a device that never had it in the inbox is not there either"
      (let [laptop (profile/mark-played (profile/make "laptop") a t1)]
        (is (not (profile.queue/in-inbox? (profile/merge s laptop) a)))))
    (is (= s (profile.queue/dismiss s ["feed-x" "none"] t1)) "dismissing what is not there changes nothing")
    (testing "many at once, with one stamp"
      (let [s' (profile.queue/dismiss-all s (profile.queue/inbox s) t1)]
        (is (= [] (profile.queue/inbox s')))
        (is (= #{t1} (set (map :updated-at (vals (:inbox s'))))))
        (is (identical? s (profile.queue/dismiss-all s [["feed-x" "none"]] t1)) "and nothing for none")))
    (testing "an episode dismissed by mistake can be put back, on every device"
      (let [s'     (profile.queue/dismiss s a t1)
            back   (profile.queue/undismiss s' a t2)
            laptop (profile/merge (profile/make "laptop") back)]
        (is (= [a c2 c b] (profile.queue/inbox back)) "as one that arrives when it's put back")
        (is (= (profile.queue/inbox back) (profile.queue/inbox laptop)))
        (is (identical? s (profile.queue/undismiss s a t2)) "and one that wasn't dismissed stays")
        (is (identical? s' (profile.queue/undismiss s' ["feed-x" "none"] t2)))))
    (testing "and many at once, with one stamp"
      (let [back (profile.queue/undismiss-all (profile.queue/dismiss-all s [a b] t1) [a b] t2)]
        (is (= #{a b c c2} (set (profile.queue/inbox back))))
        (is (= #{t2} (set (map #(:updated-at (get-in back [:inbox %])) [a b]))))))))

(deftest many-at-once-in-the-queue
  (let [s (-> (profile/make "phone")
              (crdt/write [:inbox b] (constantly {:removed? false}) t0)
              (profile.queue/enqueue a t0))
        q (profile.queue/enqueue-all s [b c a] t1)]
    (is (= [a b c] (profile.queue/queue q)) "in order, after what it holds, and once")
    (is (= #{t1} (set (map :updated-at (vals (dissoc (:queue q) a))))) "with one stamp")
    (is (= [] (profile.queue/inbox q)) "out of the inbox")
    (is (= [] (profile.queue/queue (profile.queue/dequeue-all q (profile.queue/queue q) t2))) "and cleared")
    (is (identical? q (profile.queue/dequeue-all q [["feed-x" "none"]] t2)))))

(deftest playlists-beside-the-queue
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile.queue/add-to-playlist "Drive" a t0)
              (profile.queue/add-to-playlist "Drive" b t0)
              (profile.queue/add-to-playlist "Drive" c t0)
              (profile.queue/move-in-playlist "Drive" c 0 t1))]
    (is (= [c a b] (profile.queue/playlist s "Drive")))
    (is (= s (profile.queue/add-to-playlist s "Drive" a t2)) "an episode is in a playlist once")
    (is (= [c b] (profile.queue/playlist (profile.queue/remove-from-playlist s "Drive" a t2) "Drive")))
    (is (= {"Road trip" [c a b]} (profile.queue/playlists (profile.queue/rename-playlist s "Drive" "Road trip" t2))) "in the same order")
    (is (= {} (profile.queue/playlists (profile.queue/rename-playlist s "Drive" nil t2))) "nil deletes it")
    (is (= [c b] (profile.queue/playlist (profile/merge (profile.queue/remove-from-playlist s "Drive" a t2)
                                                        (profile.queue/move-in-playlist (assoc s :device "laptop") "Drive" a 0 t3))
                                         "Drive"))
        "a move never brings back an episode that another device took out")
    (is (= [] (profile.queue/queue s)) "the queue is its own")
    (testing "into a playlist that holds episodes"
      (let [d ["feed-b" "b2"]
            e ["feed-b" "b3"]
            s (-> s (profile.queue/add-to-playlist "Walk" d t1) (profile.queue/add-to-playlist "Walk" a t1) (profile.queue/add-to-playlist "Walk" e t1))]
        (is (= {"Walk" [d a e c b]} (profile.queue/playlists (profile.queue/rename-playlist s "Drive" "Walk" t2)))
            "at its end, in their order, and an episode that both hold stays where it was")
        (is (= s (profile.queue/rename-playlist s "Drive" "Drive" t2)) "a playlist renamed to its own name is as it was")))
    (testing "a private feed goes into a list feed without its credentials"
      (let [s (-> (profile/make "phone")
                  (profile/subscribe "feed-p" {:url "https://simon:secret@members.example/rss" :title "Members"} t0)
                  (profile.queue/add-to-playlist "Best" ["feed-p" "p1"] t0))]
        (is (= [{:feed-guid "feed-p" :item-guid "p1" :feed-url "https://members.example/rss"}]
               (:podcast/remote-items (profile.queue/to-list-feed s "Best"))))))
    (testing "a list feed that names an episode twice, and one that the playlist holds"
      (let [feed {:podcast/remote-items [{:feed-guid "feed-a" :item-guid "a2"} {:feed-guid "feed-c" :item-guid "c1"}
                                         {:feed-guid "feed-c" :item-guid "c1"} {:feed-guid "feed-c"}
                                         {:feed-guid "feed-c" :item-guid "c2"}]}
            s'   (profile.queue/from-list-feed s "Drive" feed t2)]
        (is (= [c a b ["feed-c" "c1"] ["feed-c" "c2"]] (profile.queue/playlist s' "Drive")))
        (is (= s' (profile.queue/from-list-feed s' "Drive" feed t3)) "and again, nothing changes")))
    (testing "a long list feed, and the same list feed again with more episodes"
      (let [feed  (fn [n] {:podcast/remote-items (vec (for [i (range n)] {:feed-guid "feed-l" :item-guid (str i)}))})
            long  (profile.queue/from-list-feed s "Drive" (feed 5000) t2)
            again (profile.queue/from-list-feed long "Drive" (feed 9000) t3)
            ranks #(map :rank (vals (get-in % [:playlists "Drive"])))]
        (is (= (into [c a b] (for [i (range 9000)] ["feed-l" (str i)])) (profile.queue/playlist again "Drive")))
        (is (= 8 (apply max (map count (ranks long))) (apply max (map count (ranks again))))
            "the ranks of many episodes are short: five digits after the three of the device's block")))
    (testing "as a list feed of the podcast namespace, and back"
      (let [feed (profile.queue/to-list-feed s "Drive" {:url "https://me.example/drive.xml"})]
        (is (= {:rss/version "2.0" :rss/title "Drive" :rss/description "Drive" :rss/link "https://me.example/drive.xml"
                :podcast/guid (guid/from-url "https://me.example/drive.xml") :podcast/medium "podcastL"
                :podcast/remote-items [{:feed-guid "feed-b" :item-guid "b1"}
                                       {:feed-guid "feed-a" :item-guid "a1" :feed-url "https://a/rss"}
                                       {:feed-guid "feed-a" :item-guid "a2" :feed-url "https://a/rss"}]}
               feed)
            "with the URL of each feed the listener subscribes to")
        (is (= [] (validate/validate feed)))
        (is (= [c a b] (profile.queue/playlist (profile.queue/from-list-feed (profile/make "laptop") "Shared"
                                                                             (rss/parse (rss/emit feed)) t2)
                                               "Shared")))))))
