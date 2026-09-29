(ns dk.simongray.podcast.app.player-test
  "The player state: subscriptions, progress, the queue, the playlists and
  the inbox, stars, bookmarks, folders, tags, followed lists, saved filters
  and the counts of listening, refreshes, OPML and list feeds, merging and
  the reducer."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 t4 a]]
            [dk.simongray.podcast.time :as time]))

(def b ["feed-a" "a2"])
(def c ["feed-b" "b1"])

(deftest subscriptions
  (let [s (-> (player/state "phone")
              (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (player/configure "feed-a" {:auto-queue? true :skip-intro 20} t1))]
    (is (player/subscribed? s "feed-a"))
    (is (not (player/subscribed? s "feed-b")))
    (is (= (assoc player/default-settings :auto-queue? true :skip-intro 20) (player/settings s "feed-a")))
    (is (= player/default-settings (player/settings s "feed-b")))
    (testing "unsubscribing leaves a tombstone, resubscribing keeps the settings"
      (let [gone (player/unsubscribe s "feed-a" t2)
            back (player/subscribe gone "feed-a" {:url "https://a/rss" :title "A"} t3)]
        (is (not (player/subscribed? gone "feed-a")))
        (is (= {} (player/subscriptions gone)))
        (is (contains? (:subscriptions gone) "feed-a"))
        (is (player/subscribed? back "feed-a"))
        (is (= 20 (:skip-intro (player/settings back "feed-a"))))
        (is (= t3 (get-in back [:subscriptions "feed-a" :added-at])))))
    (is (= s (player/configure s "feed-b" {:rate 2.0} t1)) "settings for an unknown feed change nothing")))

(deftest progress-and-played
  (let [s (-> (player/state "phone")
              (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (player/configure "feed-a" {:skip-intro 15} t0)
              (player/progress a 600 3600 t1))]
    (is (= 600 (:position (player/episode s a))))
    (is (not (player/played? s a)))
    (is (= 600 (player/resume-position s "feed-a" a)))
    (is (= 15 (player/resume-position s "feed-a" b)) "an unstarted episode starts after the intro")
    (testing "within the threshold of the end counts as played"
      (let [done (player/progress s a 3580 t2)]
        (is (player/played? done a))
        (is (= t2 (:played-at (player/episode done a))))
        (is (= 15 (player/resume-position done "feed-a" a)) "a played episode starts over")))
    (testing "marks"
      (is (player/played? (player/mark-played s b t2) b))
      (let [again (player/mark-unplayed (player/mark-played s a t2) a t3)]
        (is (not (player/played? again a)))
        (is (= 0 (:position (player/episode again a))))))
    (is (= [b a] (player/history (-> s (player/progress b 10 t2)))))
    (is (= [a b] (player/history (-> s (player/mark-played a t1) (player/mark-played b t2) (player/progress a 5 t3))))
        "an episode heard again comes first again")))

(deftest skipping
  (let [s (-> (player/state "phone") (player/progress a 100 3600 t0))]
    (is (= 130 (:position (player/episode (player/skip s a 30 t1) a))))
    (is (= 85 (:position (player/episode (player/skip s a -15 t1) a))))
    (is (= 0 (:position (player/episode (player/skip s a -500 t1) a))) "never before the start")
    (is (= 3600 (:position (player/episode (player/skip s a 5000 t1) a))) "never past the end")
    (is (= 40 (:position (player/episode (player/skip (player/state "x") c 40 t1) c))) "an unknown episode starts at zero")
    (doseq [duration [0 ##NaN]]
      (is (= 130 (:position (player/episode (player/skip (player/progress s a 100 duration t0) a 30 t1) a)))
          (str "a duration of " duration " is not known, and no bound")))
    (is (= [[:seek a 3590] [:played a]] (:effects (player/step s [:skip a 3490] t1)))
        "a skip into the end marks it played, and says so")
    (testing "the preferred amounts, through the reducer, with the seek as the effect"
      (let [s (player/prefer s {:skip-forward 45} t0)]
        (is (= {:skip-forward 45 :skip-back 15 :rate 1.0} (player/preferences s)))
        (is (= [[:seek a 145]] (:effects (player/step s [:skip a :forward] t1))))
        (is (= [[:seek a 85]] (:effects (player/step s [:skip a :back] t1))))
        (is (= [[:seek a 110]] (:effects (player/step s [:skip a 10] t1))))))
    (testing "a feed's own rate overrides the listener's"
      (let [s (-> s
                  (player/prefer {:rate 1.5} t0)
                  (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                  (player/configure "feed-a" {:rate 1.0} t0))]
        (is (= 1.0 (player/rate s "feed-a")))
        (is (= 1.5 (player/rate s "feed-b")))))))

(deftest the-queue
  (let [s (-> (player/state "phone") (player/enqueue a t0) (player/enqueue b t0) (player/enqueue a t1))]
    (is (= [a b] (player/queue s)))
    (is (= a (player/current s)))
    (is (player/queued? s b))
    (is (= [a c b] (player/queue (player/play-next s c t1))))
    (is (= [a b] (player/queue (player/play-next s a t1))) "the current episode stays current")
    (is (= [c] (player/queue (player/play-next (player/state "phone") c t1))))
    (is (= [b] (player/queue (player/dequeue s a t1))))
    (is (= [b a] (player/queue (player/move s a 5 t1))) "an index past the end means the end")
    (is (= [b a c] (player/queue (player/enqueue (player/move s a 5 t1) c t1))) "a new episode goes after the last")
    (is (= [a c] (player/queue (player/enqueue (player/dequeue s b t1) c t1))) "and a removed one takes no place")
    (is (= [b a c] (player/queue (player/move (player/enqueue s c t1) a 1.9 t1)))
        "an index that is not whole counts as the whole number below it")
    (is (= [b a] (player/queue (player/move s b ##NaN t1))) "and NaN as 0, on both platforms")
    (is (= [b] (player/queue (player/advance s t1))))
    (is (= [] (player/queue (player/advance (player/state "x") t1))))
    (is (= t2 (get-in (player/advance s t2) [:queue a :updated-at])) "the current episode is left as a tombstone")
    (let [seen (:clock s)]
      (is (= (time/->inst (inc (inst-ms seen))) (get-in (player/advance s seen) [:queue a :updated-at]))
          "a write at an instant the state has seen is stamped the millisecond after"))))

(deftest the-queue-across-devices
  (let [shared (-> (player/state "phone") (player/enqueue a t0) (player/enqueue b t0))
        phone  (-> shared (player/advance t1) (player/enqueue c t2))
        laptop (-> (player/merge (player/state "laptop") shared) (player/enqueue ["feed-c" "c1"] t3))
        merged (player/merge phone laptop)]
    (is (= [b c ["feed-c" "c1"]] (player/queue merged))
        "the episode finished on the phone stays gone, and what both devices added survives, in order")
    (is (= (player/queue merged) (player/queue (player/merge laptop phone))))
    (testing "two devices appending at once tie on the rank, and the stamp orders them"
      (let [d      ["feed-d" "d1"]
            e      ["feed-e" "e1"]
            phone2 (player/enqueue phone d t3)
            laptop2 (player/enqueue (player/merge (player/state "laptop") phone) e t3)
            both   (player/merge phone2 laptop2)]
        (is (= [b c e d] (player/queue both)) "the laptop's stamp is the later, so its episode comes last")
        (is (= (get-in both [:queue d :rank]) (get-in both [:queue e :rank])))
        (is (= [b ["feed-x" "x"] c e d] (player/queue (player/play-next both ["feed-x" "x"] t3)))
            "a place between distinct ranks is found")
        (is (= [b c e d ["feed-x" "x"]] (player/queue (player/move (player/enqueue both ["feed-x" "x"] t3) ["feed-x" "x"] 3 t3)))
            "a place between two tied ranks is after both of them")))
    (testing "ranks stay short as a queue grows, at its end and at its front"
      (let [long   (reduce (fn [s i] (player/enqueue s ["f" (str i)] t0)) (player/state "x") (range 2000))
            played (reduce (fn [s i] (:state (player/step s [:play ["g" (str i)]] t0))) long (range 200))
            digits #(apply max (map (comp count :rank) (vals (:queue %))))]
        (is (= 2000 (count (player/queue long))))
        (is (= 3 (digits long)))
        (is (= (concat (for [i (range 199 -1 -1)] ["g" (str i)]) (for [i (range 2000)] ["f" (str i)]))
               (player/queue played)))
        (is (= 3 (digits played)))))))

(deftest preferences-and-settings-field-by-field
  (let [base   (player/subscribe (player/state "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
        phone  (-> base (player/prefer {:skip-forward 45} t1) (player/configure "feed-a" {:skip-intro 20} t1))
        laptop (-> (player/merge (player/state "laptop") base)
                   (player/prefer {:rate 1.5} t2)
                   (player/configure "feed-a" {:auto-queue? true} t2))
        merged (player/merge phone laptop)]
    (is (= {:skip-forward 45 :skip-back 15 :rate 1.5} (player/preferences merged))
        "preferences changed on different devices both survive")
    (is (= (assoc player/default-settings :auto-queue? true :skip-intro 20) (player/settings merged "feed-a"))
        "and so do a subscription's settings")
    (is (= 60 (:skip-forward (player/preferences (player/merge phone (player/prefer laptop {:skip-forward 60} t3)))))
        "the same field on both: the later wins")
    (is (= 20 (:skip-intro (player/settings (player/unsubscribe merged "feed-a" t3) "feed-a")))
        "settings outlive the subscription, for when it comes back")))

(deftest refreshing-a-feed
  (let [item   (fn [id] {:rss/guid {:content id} :rss/title id})
        before {:podcast/guid "feed-a" :rss/items [(item "a2") (item "a1")]}
        after  {:podcast/guid "feed-a" :rss/items [(item "a4") (item "a3") (item "a2") (item "a1")]}
        s      (-> (player/state "phone") (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0))]
    (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (player/new-episodes before after "https://a/rss")) "oldest first")
    (testing "oldest first by date also for a feed that lists them so, and none on a first parse"
      (let [dated (fn [id day] (assoc (item id) :rss/pub-date (time/->inst (* day 86400000))))
            after {:podcast/guid "feed-a" :rss/items [(dated "a1" 1) (dated "a2" 2) (dated "a3" 3) (dated "a4" 4)]}]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (player/new-episodes before after "https://a/rss")))
        (is (= [] (player/new-episodes nil after "https://a/rss")))))
    (testing "without auto-queue the new episodes are reported, not queued"
      (let [{:keys [state added]} (player/refresh s before after "https://a/rss" t1)]
        (is (= 2 (count added)))
        (is (= [] (player/queue state)))))
    (testing "with auto-queue they join the queue, oldest first"
      (let [{:keys [state]} (player/refresh (player/configure s "feed-a" {:auto-queue? true} t0) before after "https://a/rss" t1)]
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (player/queue state)))
        (is (= [] (player/inbox state)))
        (is (= state (:state (player/refresh state after after "https://a/rss" t2))) "a parse with no new episode changes nothing")))
    (testing "many new episodes join the queue with short ranks"
      (let [grown {:podcast/guid "feed-a" :rss/items (into (mapv #(item (str "n" %)) (range 3000 0 -1)) (:rss/items before))}
            s     (-> (player/configure s "feed-a" {:auto-queue? true} t0) (player/enqueue ["feed-b" "b1"] t0))
            {:keys [state added]} (player/refresh s before grown "https://a/rss" t1)]
        (is (= 3000 (count added)))
        (is (= (into [["feed-b" "b1"]] added) (player/queue state)) "after what the queue holds, in their order")
        (is (= 3 (apply max (map (comp count :rank) (vals (:queue state))))))))
    (testing "else, when the settings ask for it, the inbox, the newest first"
      (let [{:keys [state]} (player/refresh (player/configure s "feed-a" {:inbox? true} t0) before after "https://a/rss" t1)]
        (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (player/inbox state)))
        (is (= [] (player/queue state)))))
    (testing "an episode another device has handled stays as the listener left it"
      (let [phone  (-> (player/configure s "feed-a" {:auto-queue? true} t0)
                       (player/refresh before after "https://a/rss" t1)
                       :state
                       (player/mark-played ["feed-a" "a3"] t2)
                       (player/dequeue ["feed-a" "a3"] t2)
                       (player/dequeue ["feed-a" "a4"] t2))
            laptop (-> (player/merge (player/state "laptop") phone)
                       (player/refresh before after "https://a/rss" t3)
                       :state)]
        (is (= [] (player/queue laptop)) "neither the one played nor the one taken out comes back")
        (let [inbox  (-> (player/configure s "feed-a" {:inbox? true} t0)
                         (player/refresh before after "https://a/rss" t1)
                         :state
                         (player/dismiss ["feed-a" "a4"] t2))
              laptop (:state (player/refresh (player/merge (player/state "laptop") inbox) before after "https://a/rss" t3))]
          (is (= [["feed-a" "a3"]] (player/inbox laptop)) "nor the one dismissed from the inbox"))))))

(deftest the-inbox
  (let [c2 ["feed-b" "b2"]
        s  (-> (player/state "phone")
               (player/write [:inbox a] (constantly {:removed? false}) t0)
               (player/write [:inbox b] (constantly {:removed? false}) t0)
               (player/write [:inbox c] (constantly {:removed? false}) t0)
               (player/write [:inbox c2] (constantly {:removed? false}) t0))]
    (is (= [c2 c b a] (player/inbox s)) "the latest to arrive first")
    (testing "an episode leaves the inbox when it is dismissed, queued or played"
      (let [s' (-> s (player/dismiss a t1) (player/enqueue b t1) (player/mark-played c t1) (player/progress c2 3590 3600 t1))]
        (is (= [] (player/inbox s')))
        (is (every? #(:removed? (get-in s' [:inbox %])) [a b c c2]) "each with a tombstone, which compaction can drop")))
    (is (= [c2 c a] (player/inbox (player/play-next s b t1))))
    (testing "and an episode played on a device that never had it in the inbox is not there either"
      (let [laptop (player/mark-played (player/state "laptop") a t1)]
        (is (not (player/in-inbox? (player/merge s laptop) a)))))
    (is (= s (player/dismiss s ["feed-x" "none"] t1)) "dismissing what is not there changes nothing")))

(deftest stars-and-bookmarks
  (let [s (-> (player/state "phone") (player/star a t0) (player/star b t1))]
    (is (= [b a] (player/starred s)) "the latest starred first")
    (is (player/starred? s a))
    (is (= s (player/star s a t2)) "starring again changes nothing, so the order stays")
    (is (= [b] (player/starred (player/unstar s a t2))))
    (is (:removed? (get-in (player/unstar s a t2) [:stars a])) "a tombstone")
    (is (not (player/starred? (player/merge (player/unstar s a t2) (player/star (player/state "laptop") a t1)) a))
        "the later removal outlives the older star from another device"))
  (let [s (-> (player/state "phone")
              (player/bookmark a 754.8 "The good bit" t0)
              (player/bookmark a 30 nil t1)
              (player/bookmark b 5 "Other" t2))]
    (is (= [{:id a :position 30 :title nil :updated-at t1} {:id a :position 754 :title "The good bit" :updated-at t0}]
           (player/bookmarks s a))
        "an episode's bookmarks by position, in whole seconds")
    (is (= [b a a] (map :id (player/bookmarks s))) "all of them, the latest written first")
    (is (= "Renamed" (:title (second (player/bookmarks (player/bookmark s a 754 "Renamed" t3) a))))
        "the same second is the same bookmark")
    (is (= [30] (map :position (player/bookmarks (player/unbookmark s a 754.2 t3) a))))
    (doseq [bad [-1 ##NaN ##Inf "12"]]
      (is (= s (player/bookmark s a bad "x" t3)) (str "no bookmark at " (pr-str bad))))))

(deftest folders-of-shows
  (let [s (-> (player/state "phone")
              (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
              (player/subscribe "feed-c" {:url "https://c/rss" :title "C"} t0)
              (player/configure "feed-c" {:folder "News"} t0)
              (player/configure "feed-a" {:folder "News"} t0))]
    (is (= {"News" ["feed-a" "feed-c"] nil ["feed-b"]} (player/folders s)) "titles in order, the rest under nil")
    (is (= {"Nyheder" ["feed-a" "feed-c"] nil ["feed-b"]} (player/folders (player/rename-folder s "News" "Nyheder" t1))))
    (is (= {nil ["feed-a" "feed-b" "feed-c"]} (player/folders (player/rename-folder s "News" nil t1))) "nil empties the folder")
    (is (= s (player/rename-folder s "Sport" "Fodbold" t1)) "a folder without shows changes nothing")
    (testing "in OPML, as the folders it has, and back, with the tags and the time of subscribing"
      (let [s    (-> s (player/tag "feed-a" "Daily" t1) (player/tag "feed-a" "Danish" t1))
            list (player/to-opml s)
            back (player/from-opml (player/state "laptop") (opml/parse (opml/emit list)) t2)]
        (is (= [["feed-a" ["News"]] ["feed-c" ["News"]] ["feed-b" nil]] (map (juxt :guid :path) (:subscriptions list)))
            "the folders by name first, then the shows in none")
        (is (= ["Daily" "Danish"] (:category (first (:subscriptions list)))))
        (is (= (player/folders s) (player/folders back)))
        (is (= (player/tagged s) (player/tagged back)))
        (is (= t0 (get-in back [:subscriptions "feed-a" :added-at])) "subscribed when the other device subscribed")))
    (is (= {"Danish" ["x"]}
           (player/folders (player/from-opml (player/state "x") {:subscriptions [{:text "X" :xml-url "https://x/rss" :guid "x"
                                                                                   :path ["News" "Danish"]}]}
                                             t0)))
        "the innermost of nested folders")))

(deftest tags-of-shows
  (let [s (-> (player/state "phone")
              (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
              (player/tag "feed-b" "Tech" t0)
              (player/tag "feed-a" "Tech" t0)
              (player/tag "feed-a" "Daily" t0))]
    (is (= #{"Daily" "Tech"} (player/tags s "feed-a")))
    (is (= {"Tech" ["feed-a" "feed-b"] "Daily" ["feed-a"]} (player/tagged s)) "titles in order")
    (is (= s (player/tag s "feed-x" "Tech" t1)) "no tag for a show the state does not know")
    (is (= #{"Daily"} (player/tags (player/untag s "feed-a" "Tech" t1) "feed-a")))
    (is (= {"Technology" ["feed-a" "feed-b"] "Daily" ["feed-a"]} (player/tagged (player/rename-tag s "Tech" "Technology" t1))))
    (is (= {"Daily" ["feed-a"]} (player/tagged (player/rename-tag s "Tech" nil t1))) "nil takes the tag off")
    (is (= #{"Daily" "Tech" "Mine" "Yours"}
           (player/tags (player/merge (player/tag s "feed-a" "Mine" t1)
                                      (player/tag (player/merge (player/state "laptop") s) "feed-a" "Yours" t1))
                        "feed-a"))
        "tags two devices add at once both stay, unlike a folder")
    (is (= "a tag of \"feed-a\" is not a string"
           (player/problem (assoc-in s [:tags "feed-a" :tech] {:removed? false :updated-at t1 :device "x"}))))))

(deftest following-lists
  (let [url  "https://lists.example/picks.opml"
        g    #(guid/from-url %)
        v1   {:subscriptions [{:text "PC20" :xml-url "https://pc20/rss"} {:text "Mine" :xml-url "https://mine/rss"}]}
        v2   {:subscriptions [{:text "PC20" :xml-url "https://pc20/rss"}
                              {:text "A description" :title "New" :xml-url "https://new/rss"}]}
        s    (-> (player/state "phone")
                 (player/subscribe (g "https://mine/rss") {:url "https://mine/rss" :title "Mine"} t0)
                 (player/follow-list url {:title "Picks" :folder "Picks"} t0))
        r1   (player/refresh-list s url nil v1 t1)]
    (is (= {url {:title "Picks" :folder "Picks"}} (player/lists s)))
    (testing "a first parse subscribes to the shows the state does not know, into the list's folder"
      (is (= [(g "https://pc20/rss")] (:added r1)))
      (is (= {"Picks" [(g "https://pc20/rss")] nil [(g "https://mine/rss")]} (player/folders (:state r1)))
          "the listener's own show stays where it was"))
    (testing "later parses add the shows that join, and report the ones that leave"
      (let [unsubscribed (player/unsubscribe (:state r1) (g "https://pc20/rss") t2)
            {:keys [state added left]} (player/refresh-list unsubscribed url v1 v2 t3)]
        (is (= [(g "https://new/rss")] added))
        (is (= "New" (get-in state [:subscriptions (g "https://new/rss") :title])) "the feed's title, not the text")
        (is (= [(g "https://mine/rss")] left) "a show that left stays, for the app to decide")
        (is (player/subscribed? state (g "https://mine/rss")))
        (is (not (player/subscribed? state (g "https://pc20/rss"))) "a show the listener left stays left")))
    (is (= {:state s :added [] :left []} (player/refresh-list s "https://other/list.opml" nil v1 t1))
        "a list the listener does not follow changes nothing")
    (is (= {} (player/lists (player/unfollow-list s url t1))))
    (testing "the state knows a show by the URL of its feed, under whatever guid"
      (let [s     (-> (player/state "phone")
                      (player/subscribe "declared" {:url "https://pc20/rss" :title "PC20"} t0)
                      (player/subscribe "private" {:url "https://simon:secret@members.example/rss" :title "Members"} t0)
                      (player/follow-list url {:title "Picks"} t0))
            twice {:subscriptions [{:text "PC20" :xml-url "http://PC20/rss/"}
                                   {:text "Members" :xml-url "https://members.example/rss"}
                                   {:text "New" :xml-url "https://new/rss"}
                                   {:text "New, again" :xml-url "https://NEW/rss"}]}
            {:keys [state added left]} (player/refresh-list s url nil twice t1)]
        (is (= [(g "https://new/rss")] added) "and a show that the list names twice comes in once")
        (is (= #{"declared" "private" (g "https://new/rss")} (set (keys (player/subscriptions state)))))
        (is (= "https://simon:secret@members.example/rss" (get-in state [:subscriptions "private" :url]))
            "a private feed is the feed at its URL without the password, and keeps its own URL")
        (is (= ["declared" "private" (g "https://new/rss")]
               (:left (player/refresh-list state url twice {:subscriptions []} t2)))
            "a show that leaves has the guid that the state knows it by")
        (is (= [] left))))
    (testing "a show that the listener left stays left on a device that joins after the compaction"
      (let [day    (* 24 60 60 1000)
            far    (time/->inst (+ (inst-ms t4) (* 40 day)))
            phone  (-> (player/identify (:state r1) {:name "Phone"} t1)
                       (player/unsubscribe (g "https://pc20/rss") t2))
            laptop (-> (player/identify (player/state "laptop") {:name "Laptop"} t2)
                       (player/merge phone)
                       (player/acknowledge [phone] t3))
            phone  (player/compact (player/acknowledge (player/merge phone laptop) [laptop] t3) far)
            tablet (player/merge (player/state "tablet") phone)]
        (is (:removed? (get-in phone [:subscriptions (g "https://pc20/rss")]))
            "the tombstone stays while the listener follows a list")
        (is (= [] (:added (player/refresh-list tablet url nil v1 far))))
        (is (nil? (get-in (player/compact (player/unfollow-list phone url t4) far {:keep-for 0})
                          [:subscriptions (g "https://pc20/rss")]))
            "and it goes when the listener follows none")))
    (testing "in OPML, as an inclusion in the list's folder, and followed again from it"
      (let [xml (opml/emit (player/to-opml (:state r1)))]
        (is (re-find #"<outline text=\"Picks\">\s*<outline type=\"rss\"[^>]*/>\s*<outline type=\"include\" text=\"Picks\" url=\"https://lists.example/picks.opml\"/>" xml))
        (is (= {url {:title "Picks" :folder "Picks"}}
               (player/lists (player/from-opml (player/state "laptop") (opml/parse xml) t2))))))))

(deftest playlists-beside-the-queue
  (let [s (-> (player/state "phone")
              (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (player/add-to-playlist "Drive" a t0)
              (player/add-to-playlist "Drive" b t0)
              (player/add-to-playlist "Drive" c t0)
              (player/move-in-playlist "Drive" c 0 t1))]
    (is (= [c a b] (player/playlist s "Drive")))
    (is (= s (player/add-to-playlist s "Drive" a t2)) "an episode is in a playlist once")
    (is (= [c b] (player/playlist (player/remove-from-playlist s "Drive" a t2) "Drive")))
    (is (= {"Road trip" [c a b]} (player/playlists (player/rename-playlist s "Drive" "Road trip" t2))) "in the same order")
    (is (= {} (player/playlists (player/rename-playlist s "Drive" nil t2))) "nil deletes it")
    (is (= [] (player/queue s)) "the queue is its own")
    (testing "into a playlist that holds episodes"
      (let [d ["feed-b" "b2"]
            e ["feed-b" "b3"]
            s (-> s (player/add-to-playlist "Walk" d t1) (player/add-to-playlist "Walk" a t1) (player/add-to-playlist "Walk" e t1))]
        (is (= {"Walk" [d a e c b]} (player/playlists (player/rename-playlist s "Drive" "Walk" t2)))
            "at its end, in their order, and an episode that both hold stays where it was")
        (is (= s (player/rename-playlist s "Drive" "Drive" t2)) "a playlist renamed to its own name is as it was")))
    (testing "a private feed goes into a list feed without its credentials"
      (let [s (-> (player/state "phone")
                  (player/subscribe "feed-p" {:url "https://simon:secret@members.example/rss" :title "Members"} t0)
                  (player/add-to-playlist "Best" ["feed-p" "p1"] t0))]
        (is (= [{:feed-guid "feed-p" :item-guid "p1" :feed-url "https://members.example/rss"}]
               (:podcast/remote-items (player/to-list-feed s "Best"))))))
    (testing "a list feed that names an episode twice, and one that the playlist holds"
      (let [feed {:podcast/remote-items [{:feed-guid "feed-a" :item-guid "a2"} {:feed-guid "feed-c" :item-guid "c1"}
                                         {:feed-guid "feed-c" :item-guid "c1"} {:feed-guid "feed-c"}
                                         {:feed-guid "feed-c" :item-guid "c2"}]}
            s'   (player/from-list-feed s "Drive" feed t2)]
        (is (= [c a b ["feed-c" "c1"] ["feed-c" "c2"]] (player/playlist s' "Drive")))
        (is (= s' (player/from-list-feed s' "Drive" feed t3)) "and again, nothing changes")))
    (testing "a long list feed, and the same list feed again with more episodes"
      (let [feed  (fn [n] {:podcast/remote-items (vec (for [i (range n)] {:feed-guid "feed-l" :item-guid (str i)}))})
            long  (player/from-list-feed s "Drive" (feed 5000) t2)
            again (player/from-list-feed long "Drive" (feed 9000) t3)
            ranks #(map :rank (vals (get-in % [:playlists "Drive"])))]
        (is (= (into [c a b] (for [i (range 9000)] ["feed-l" (str i)])) (player/playlist again "Drive")))
        (is (= 5 (apply max (map count (ranks long))) (apply max (map count (ranks again))))
            "the ranks of many episodes are short")))
    (testing "as a list feed of the podcast namespace, and back"
      (let [feed (player/to-list-feed s "Drive" {:url "https://me.example/drive.xml"})]
        (is (= {:rss/version "2.0" :rss/title "Drive" :rss/description "Drive" :rss/link "https://me.example/drive.xml"
                :podcast/guid (guid/from-url "https://me.example/drive.xml") :podcast/medium "podcastL"
                :podcast/remote-items [{:feed-guid "feed-b" :item-guid "b1"}
                                       {:feed-guid "feed-a" :item-guid "a1" :feed-url "https://a/rss"}
                                       {:feed-guid "feed-a" :item-guid "a2" :feed-url "https://a/rss"}]}
               feed)
            "with the URL of each feed the listener subscribes to")
        (is (= [] (validate/validate feed)))
        (is (= [c a b] (player/playlist (player/from-list-feed (player/state "laptop") "Shared"
                                                               (podcast/parse (podcast/emit feed)) t2)
                                        "Shared")))))))

(deftest counting-listening
  (let [g  "feed-a"
        id [g "e1"]
        s0 (player/subscribe (player/state "phone") g {:url "https://a/rss" :title "A"} t0)
        at #(time/->inst (+ (inst-ms #inst "2026-09-30T23:59:50Z") (* 1000 %)))
        s1 (:state (player/step s0 [:progress id 100 3600] (at 0)))
        s2 (:state (player/step s1 [:progress id 110 3600] (at 10)))]
    (is (= {} (player/listened s1)) "a first report has nothing to count from")
    (is (= {g {"2026-10" 10}} (player/listened s2)) "the month of the report, in UTC")
    (is (< 14.9 (get-in (player/listened (:state (player/step s2 [:progress id 700 3600] (at 15)))) [g "2026-10"]) 25.1)
        "a seek counts no more than playback at three times the speed")
    (is (= (player/listened s2) (player/listened (:state (player/step s2 [:progress id 50 3600] (at 15)))))
        "nor does a move back")
    (testing "two devices counting at once lose nothing"
      (let [laptop (:state (player/step (player/merge (player/state "laptop") s1) [:progress id 130 3600] (at 30)))]
        (is (= {g {"2026-10" 40}} (player/listened (player/merge s2 laptop))))))
    (testing "a state whose clock is ahead of the clock of the device"
      (let [fast  (player/star (player/state "laptop") ["x" "y"] (at 7200))
            play  (fn [s] (reduce #(:state (player/step %1 [:progress id (* 10 %2) 3600] (at (* 10 %2)))) s (range 31)))
            ahead (play (player/merge s0 fast))]
        (is (= {g {"2026-10" 300}} (player/listened ahead) (player/listened (play s0)))
            "the time is measured from the last report, and not from its stamp")
        (is (pos? (compare (:updated-at (player/episode ahead id)) (:reported-at (player/episode ahead id)))))))
    (testing "a report after a pause"
      (let [paused (:state (player/step s2 [:progress id 1500 3600] (at 610)))]
        (is (= (player/listened s2) (player/listened paused))
            "a move of the position after more than a minute is no listening")
        (is (= {g {"2026-10" 15}} (player/listened (:state (player/step paused [:progress id 1505 3600] (at 615)))))
            "and the next report counts from there")
        (is (= {g {"2026-10" 70}} (player/listened (:state (player/step s2 [:progress id 170 3600] (at (+ 10 player/listening-gap))))))
            "a report at the end of the gap counts")))
    (testing "a device that is restored from a backup counts on from the higher count"
      (let [play     (fn [s from to start]
                       (reduce #(:state (player/step %1 [:progress id (+ from (* 10 %2)) 3600] (at (+ start (* 10 %2)))))
                               s
                               (range (inc (quot (- to from) 10)))))
            saved    (play s0 0 100 0)
            went-on  (play saved 100 1000 200)
            restored (play saved 100 130 5000)
            both     (player/merge restored went-on)]
        (is (= [{g {"2026-10" 1000}} {g {"2026-10" 130}}] (map player/listened [went-on restored])))
        (is (= {g {"2026-10" 1000}} (player/listened both) (player/listened (player/merge went-on restored)))
            "the count with the later stamp is the lower one here")
        (is (= {g {"2026-10" 1010}} (player/listened (:state (player/step both [:progress id 140 3600] (at 5040))))))))
    (is (= "the listening of \"feed-a\" is not counted in seconds"
           (player/problem (assoc-in s2 [:listened g "2026-10" "x"] {:seconds "many" :updated-at t1 :device "x"}))))))

(deftest saved-filters
  (let [s (-> (player/state "phone")
              (player/save-filter "Unplayed" {:where {:status :unplayed} :order :newest :player {:big :state} :url "u"} t0)
              (player/save-filter "Starred" {:where {:starred? true}} t0))]
    (is (= {"Unplayed" {:where {:status :unplayed} :order :newest} "Starred" {:where {:starred? true}}} (player/filters s))
        "without the player and the url, which belong to the device")
    (is (= {:where {:status :played}} (get (player/filters (player/save-filter s "Unplayed" {:where {:status :played}} t1)) "Unplayed"))
        "saved again under the same name, replaced")
    (is (= ["Starred"] (keys (player/filters (player/remove-filter s "Unplayed" t1)))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (player/save-filter s "Odd" {:order (fn [_ _] 0)} t1))
        "a function cannot be printed as EDN, and so cannot sync")))

(deftest compacting-what-the-listener-took-away
  (let [far    (time/->inst (+ (inst-ms t3) (* 31 24 60 60 1000)))
        phone  (-> (player/state "phone")
                   (player/star a t0) (player/unstar a t1)
                   (player/bookmark a 30 "Kept" t0) (player/bookmark a 60 "Gone" t0) (player/unbookmark a 60 t1)
                   (player/bookmark b 90 "Gone" t0) (player/unbookmark b 90 t1)
                   (player/write [:inbox b] (constantly {:removed? false}) t0) (player/dismiss b t1)
                   (player/save-filter "F" {} t0) (player/remove-filter "F" t1))
        laptop (player/acknowledge (player/merge (player/state "laptop") phone) [phone] t2)
        phone  (player/acknowledge (player/merge phone laptop) [laptop] t2)
        after  (player/compact phone far)]
    (is (= {:stars {} :inbox {} :filters {} :bookmarks {a {30 (get-in phone [:bookmarks a 30])}}}
           (select-keys after [:stars :inbox :filters :bookmarks]))
        "the tombstones go, and an episode left with no bookmark goes with them")
    (is (= (player/bookmarks phone) (player/bookmarks after)))))

(deftest opml-round-trip
  (let [s    (-> (player/state "phone")
                 (player/subscribe "917393e3-1b1e-5cef-ace4-edaa54e1f810" {:url "https://feeds.podcastindex.org/pc20.xml" :title "Podcasting 2.0"} t0)
                 (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                 (player/unsubscribe "feed-b" t1))
        list (player/to-opml s)
        xml  (opml/emit list)]
    (is (= [{:text "Podcasting 2.0" :title "Podcasting 2.0" :xml-url "https://feeds.podcastindex.org/pc20.xml"
             :guid "917393e3-1b1e-5cef-ace4-edaa54e1f810" :created t0}]
           (:subscriptions list)))
    (is (= (update-vals (player/subscriptions s) #(select-keys % [:url :title]))
           (update-vals (player/subscriptions (player/from-opml (player/state "laptop") (opml/parse xml) t2))
                        #(select-keys % [:url :title])))
        "the guid carried in the OPML identifies the feed on the other device")
    (testing "an outline without a guid gets the one its URL derives"
      (is (player/subscribed? (player/from-opml (player/state "x") {:subscriptions [{:text "PN" :xml-url "https://podnews.net/rss"}]} t0)
                              "9b024349-ccf0-5f69-a609-6b82873eab3c")))
    (testing "the feed's title before the text, which some apps fill with the description"
      (is (= "Podnews" (get-in (player/from-opml (player/state "x")
                                                 {:subscriptions [{:text "Daily news about podcasting" :title "Podnews"
                                                                   :xml-url "https://podnews.net/rss"}]}
                                                 t0)
                               [:subscriptions "9b024349-ccf0-5f69-a609-6b82873eab3c" :title]))))
    (testing "a folder around the whole list is a wrapper, not a folder, when its name is one that apps write"
      (let [around  (fn [folder]
                      (opml/parse (str "<opml version=\"1.0\"><head><title>Feeds</title></head><body><outline text=\"" folder "\">
                                          <outline type=\"rss\" text=\"A\" xmlUrl=\"https://a/rss\"/>
                                          <outline type=\"rss\" text=\"B\" xmlUrl=\"https://b/rss\"/></outline></body></opml>")))
            folders (fn [list opts] (keys (player/folders (player/from-opml (player/state "x") list t0 opts))))]
        (is (= [nil] (keys (player/folders (player/from-opml (player/state "x") (around "feeds") t0)))))
        (is (= [nil] (folders (around "Feeds") {})) "in any case")
        (is (= ["News"] (folders (around "News") {})) "a folder of the listener stays, also when it holds every show")
        (is (= ["feeds"] (folders (around "feeds") {:wrappers #{}})) "the names are an option")
        (is (= [nil] (folders (around "Podcasts") {:wrappers #{"podcasts"}})))
        (is (= [nil] (folders (around "News") {:wrappers (constantly true)})) "and so is a rule for any name")))
    (testing "the one folder of a listener, with a list in it, comes back as it went out"
      (let [s    (-> (player/state "phone")
                     (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                     (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                     (player/configure "feed-a" {:folder "News"} t0)
                     (player/configure "feed-b" {:folder "News"} t0)
                     (player/follow-list "https://l/picks.opml" {:title "Picks" :folder "News"} t0))
            back (player/from-opml (player/state "laptop") (opml/parse (opml/emit (player/to-opml s))) t1)]
        (is (= {"News" ["feed-a" "feed-b"]} (player/folders back)))
        (is (= (player/lists s) (player/lists back)))))
    (testing "a show that the state has by its URL keeps its guid"
      (let [s    (-> (player/state "phone")
                     (player/subscribe "declared" {:url "https://a.example/rss" :title "A"} t0)
                     (player/subscribe "private" {:url "https://simon:secret@members.example/rss" :title "Members"} t0))
            list {:subscriptions [{:text "A, again" :xml-url "HTTP://A.example/rss/"}
                                  {:text "A, from another app" :xml-url "https://a.example/rss" :guid "other"}
                                  {:text "Members" :xml-url "https://members.example/rss"}
                                  {:text "B" :xml-url "https://b.example/rss" :guid " "}]}
            back (player/from-opml s list t1)]
        (is (= #{"declared" "private" (guid/from-url "https://b.example/rss")} (set (keys (player/subscriptions back))))
            "whatever the scheme, the case and the trailing slash, and a blank guid is none")
        (is (= ["https://a.example/rss" "https://simon:secret@members.example/rss"]
               (map #(get-in back [:subscriptions % :url]) ["declared" "private"]))
            "and its URL, so a private feed keeps its password")))))

(deftest merging-two-devices
  (let [phone  (-> (player/state "phone")
                   (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                   (player/progress a 100 3600 t1)
                   (player/enqueue a t1)
                   (player/prefer {:skip-forward 45} t1))
        laptop (-> (player/state "laptop")
                   (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (player/unsubscribe "feed-b" t2)
                   (player/progress a 900 3600 t2)
                   (player/enqueue c t3)
                   (player/prefer {:skip-forward 60} t2))
        merged (player/merge phone laptop)]
    (is (= "phone" (:device merged)))
    (is (not (player/subscribed? merged "feed-b")) "the later removal beats the earlier addition")
    (is (= 900 (:position (player/episode merged a))) "the later position wins")
    (is (= [a c] (player/queue merged)) "what both devices queued survives, in the order it was queued")
    (is (= 60 (:skip-forward (player/preferences merged))) "the later preferences win")
    (testing "the same whichever side asks, and again"
      (is (= (dissoc merged :device) (dissoc (player/merge laptop phone) :device)))
      (is (= merged (player/merge merged laptop)))
      (is (= merged (player/merge merged merged))))
    (testing "a fresh device takes everything, the clock included"
      (let [fresh (player/merge (player/state "tablet") laptop)]
        (is (= [c] (player/queue fresh)))
        (is (= (:clock laptop) (:clock fresh)))
        (is (pos? (compare (:clock fresh) t3)) "the laptop's preferences, written last, were stamped after t3")
        (is (= "tablet" (:device fresh)))))))

(deftest merging-by-shape
  (let [note   (fn [s id text at] (assoc-in s [:bookmarks id] {:text text :updated-at at :device (:device s)}))
        phone  (-> (player/state "phone") (note "n1" "old" t0) (note "n2" "phone" t1) (assoc :theme "light"))
        laptop (-> (player/state "laptop") (note "n1" "new" t2) (assoc :theme "dark" :zoom 2))
        merged (player/merge phone laptop)]
    (is (= {"n1" "new" "n2" "phone"} (update-vals (:bookmarks merged) :text))
        "an app's own stamped entries merge like the library's")
    (is (= "light" (:theme merged)) "what is not stamped stays the local device's")
    (is (= 2 (:zoom merged)) "unless only the other device has it")
    (is (= (dissoc (player/merge laptop phone) :device :theme) (dissoc merged :device :theme)))))

(deftest what-another-device-may-not-send
  (testing "what the operations write passes"
    (is (nil? (player/problem (-> (player/state "phone")
                                  (player/subscribe "feed-a" {:url "https://a/rss"} t0)
                                  (player/configure "feed-a" {:skip-intro 20} t0)
                                  (player/progress a 100 3600 t1)
                                  (player/enqueue b t1)
                                  (player/acknowledge [(player/subscribe (player/state "laptop") "feed-b" {:url "https://b/rss"} t0)] t2))))))
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
                           [{:filters {"F" {:criteria [:where] :updated-at t0 :device "x"}}} #"criteria of the filter \"F\""]
                           [{:tags {"feed-a" "Tech"}} #"\[:tags \"feed-a\"\] is not a map"]]]
      (is (re-find found (str (player/problem state))) (pr-str state))))
  (testing "an entry without a stamp, which two devices merge to two different states"
    (let [here  {:device "a" :stars {a {:removed? false}}}
          there {:device "b" :stars {a {:removed? true}}}]
      (is (not= (dissoc (player/merge here there) :device) (dissoc (player/merge there here) :device)))
      (is (= "the entry at [:stars [\"feed-a\" \"a1\"]] is not stamped" (player/problem here)))
      (doseq [state [{:queue {a {}}} {:settings {"feed-a" {:folder {:value "News"}}}}
                     {:listened {"feed-a" {"2026-09" {"x" {:seconds 5}}}}} {:retired {"x" {}}}]]
        (is (re-find #"is not stamped" (str (player/problem state))) (pr-str state)))))
  (testing "criteria of a later version are no problem of the state"
    (is (nil? (player/problem {:filters {"F" {:criteria {:where {:rating 5}} :updated-at t0 :device "x"}}})))))

(deftest merging-entries-with-keys-of-any-kind
  (let [s (player/identify (player/state "phone") {:name "Phone" "custom" 1} t0)]
    (is (= s (player/merge s s)) "an entry ties with itself, and its keys still sort")))

(deftest a-tie-between-two-entries
  (let [saved  (fn [criteria] {:device "p" :filters {"F" {:criteria criteria :removed? false :updated-at t0 :device "p"}}})
        order  #(get-in (player/merge %1 %2) [:filters "F" :criteria :order])
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
        (is (== 1 (:rate (player/preferences (player/merge (rate 1.0) (rate 1.5))))))
        (is (== 1 (:rate (player/preferences (player/merge (rate 1.5) (rate 1.0))))))))))

(deftest devices-and-the-clock
  (let [s (-> (player/state "phone-id")
              (player/identify {:name "Simon's phone" :platform "ios"} t0)
              (player/progress a 100 3600 t1))]
    (is (= {"phone-id" {:name "Simon's phone" :platform "ios" :updated-at t0 :device "phone-id"}}
           (player/devices s)))
    (is (= t1 (:clock s)) "the clock is the latest instant written")
    (is (= t2 (player/after s t2)) "a later now is used as it is")
    (is (= (time/->inst (inc (inst-ms t1))) (player/after s t0)) "an earlier now becomes the millisecond after the clock")
    (is (= t2 (:clock (player/merge s (player/progress (player/state "x") b 5 t2)))) "and it follows what is merged")
    (let [s (-> s (player/write [:notes "x"] #(assoc % :text "one") t0) (player/write [:notes "y"] #(assoc % :text "two") t0))]
      (is (not= (get-in s [:notes "x" :updated-at]) (get-in s [:notes "y" :updated-at]))
          "two entries of the app's own, written through write, are never stamped alike")
      (is (= (get-in s [:notes "y" :updated-at]) (:clock s))))
    (testing "retiring a device from another"
      (let [laptop (player/retire (player/state "laptop-id") "phone-id" t2)
            merged (player/merge s laptop)]
        (is (player/retired? merged "phone-id"))
        (is (= {} (player/devices merged)))
        (is (= {} (player/devices (player/merge (player/identify merged {:name "Back"} t3) laptop)))
            "describing itself again does not undo it")))))

(deftest acknowledging-and-compacting
  (let [day    (* 24 60 60 1000)
        far    (time/->inst (+ (inst-ms t3) (* 31 day)))
        phone  (-> (player/state "phone")
                   (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                   (player/unsubscribe "feed-b" t1)
                   (player/enqueue a t1)
                   (player/dequeue a t2))
        laptop (player/acknowledge (player/merge (player/state "laptop") phone) [phone] t3)
        phone  (player/acknowledge (player/merge phone laptop) [laptop] t3)]
    (testing "acknowledging what another device wrote"
      (is (= {"phone" t2} (get-in laptop [:devices "laptop" :seen])) "the latest the phone wrote, outside the registry")
      (is (= t3 (get-in laptop [:devices "laptop" :updated-at])))
      (is (= laptop (player/acknowledge laptop [phone] t4)) "nothing new leaves the entry alone")
      (is (= laptop (player/acknowledge laptop [(player/identify phone {:name "P"} t4)] t4)) "the registry itself is never news")
      (is (= {} (get-in phone [:devices "phone" :seen])) "the laptop had written nothing of its own"))
    (testing "a tombstone goes once every other live device has seen it and it is old enough"
      (is (= 2 (count (:subscriptions phone))))
      (is (= (:subscriptions phone) (:subscriptions (player/compact phone t4))) "too fresh")
      (let [compacted (player/compact phone far)]
        (is (= ["feed-a"] (keys (:subscriptions compacted))))
        (is (= {} (:queue compacted)))
        (is (= (player/subscriptions phone) (player/subscriptions compacted)) "nothing the listener sees changed")
        (is (= (player/queue phone) (player/queue compacted))))
      (is (= (:subscriptions phone) (:subscriptions (player/compact phone far {:keep-for (* 40 day)}))) "the age is an option")
      (is (= {} (player/lagging phone)) "the laptop is up to date"))
    (testing "a device that has not caught up holds compaction back, until it is retired"
      (let [tablet (player/identify (player/state "tablet") {:name "Tablet"} t0)
            phone  (player/merge phone tablet)]
        (is (= 2 (count (:subscriptions (player/compact phone far)))))
        (is (= {"tablet" nil} (player/lagging phone)) "it has merged nothing")
        (is (= {"tablet" nil "laptop" t1} (player/lagging (assoc-in phone [:devices "laptop" :seen "phone"] t1)))
            "a device part of the way is named with how far it got")
        (is (= ["feed-a"] (keys (:subscriptions (player/compact (player/retire phone "tablet" t3) far)))))))))

(deftest the-changes-since-a-state
  (let [taken   (-> (player/state "phone")
                    (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                    (player/prefer {:rate 1.5} t1))
        s       (-> taken
                    (player/progress a 100 3600 t2)
                    (player/enqueue a t3))
        changes (player/changes-since s taken)]
    (is (= #{:device :clock :episodes :queue} (set (keys changes)))
        "what was written since the state was taken, with the device and the clock")
    (is (= (player/episode s a) (player/episode changes a)))
    (is (= s (player/merge s changes)) "a partial state merges into the whole without changing it")
    (let [laptop (player/subscribe (player/state "laptop") "feed-b" {:url "https://b/rss" :title "B"} t0)]
      (is (contains? (:subscriptions (player/changes-since (player/merge s laptop) taken)) "feed-b")
          "and what was merged in since, however early its stamp"))
    (is (= (dissoc s :device)
           (dissoc (player/merge (player/state "laptop") (player/changes-since s nil)) :device))
        "nil means everything")))

(deftest stepping
  (let [s (-> (player/state "phone") (player/enqueue a t0) (player/enqueue b t0))]
    (testing "progress reports when an episode becomes played"
      (let [{:keys [state effects]} (player/step s [:progress a 3590 3600] t1)]
        (is (player/played? state a))
        (is (= [[:played a]] effects))
        (is (= [] (:effects (player/step state [:progress a 3595 3600] t2))) "only once")))
    (testing "ending the current episode plays the next"
      (let [{:keys [state effects]} (player/step s [:ended a] t1)]
        (is (player/played? state a))
        (is (= [b] (player/queue state)))
        (is (= [[:play b]] effects))))
    (testing "ending an episode that is not current leaves the queue alone"
      (let [{:keys [state effects]} (player/step s [:ended c] t1)]
        (is (= [a b] (player/queue state)))
        (is (= [] effects))))
    (is (= [c a b] (player/queue (:state (player/step s [:play c] t1)))))
    (is (= {:state s :effects []} (player/step s [:volume 3] t1)) "unknown events change nothing")))

(deftest skipping-the-outro
  (let [s (-> (player/state "phone")
              (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (player/configure "feed-a" {:skip-outro 60} t0)
              (player/enqueue a t0)
              (player/enqueue b t0)
              (player/progress a 3500 3600 t0))]
    (is (= 3540 (player/outro-start s "feed-a" a)))
    (is (nil? (player/outro-start s "feed-a" b)) "not before the duration is known")
    (testing "a report in the outro ends the episode there"
      (let [{:keys [state effects]} (player/step s [:progress a 3545 3600] t1)]
        (is (= [[:stop a] [:played a] [:play b]] effects))
        (is (player/played? state a))
        (is (= [b] (player/queue state)))
        (is (= [] (:effects (player/step state [:progress a 3550 3600] t2))) "once: a late report changes nothing")))
    (testing "so does a skip into it, which then needs no seek"
      (is (= [[:stop a] [:played a] [:play b]] (:effects (player/step s [:skip a 45] t1))))
      (is (= [[:seek a 3530]] (:effects (player/step s [:skip a :forward] t1)))))
    (testing "an episode that counts as played already, and one heard again"
      (let [short (player/configure s "feed-a" {:skip-outro 20} t0)
            {:keys [state effects]} (player/step short [:progress a 3575 3600] t1)]
        (is (= [[:played a]] effects) "within the threshold of the end, but not yet in the outro")
        (is (= [[:stop a] [:play b]] (:effects (player/step state [:progress a 3585 3600] t2)))))
      (let [again (-> s (player/mark-played a t1) (player/progress a 20 t2))]
        (is (= [[:stop a] [:play b]] (:effects (player/step again [:progress a 3541 3600] t3))))))
    (testing "an episode that is not current only stops"
      (let [s (-> s
                  (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                  (player/configure "feed-b" {:skip-outro 60} t0))
            {:keys [state effects]} (player/step s [:progress c 3545 3600] t1)]
        (is (= [[:stop c] [:played c]] effects))
        (is (= [a b] (player/queue state)))))
    (testing "no outro, or one as long as the episode, ends nothing early"
      (is (= [[:played a]] (:effects (player/step (player/configure s "feed-a" {:skip-outro 0} t1) [:progress a 3590 3600] t2))))
      (is (= [[:played b]] (:effects (player/step (player/progress s b 10 50 t1) [:progress b 45 50] t2)))))))
