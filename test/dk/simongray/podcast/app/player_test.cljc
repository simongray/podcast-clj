(ns dk.simongray.podcast.app.player-test
  "The player state: subscriptions, progress, the queue, refreshes, OPML,
  merging and the reducer."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.format.opml :as opml]
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
    (is (= {:auto-queue? true :skip-intro 20 :skip-outro 0} (player/settings s "feed-a")))
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
    (testing "ranks stay short as a queue grows"
      (let [long (reduce (fn [s i] (player/enqueue s ["f" (str i)] t0)) (player/state "x") (range 300))]
        (is (= 300 (count (player/queue long))))
        (is (<= (apply max (map (comp count :rank) (vals (:queue long)))) 6))))))

(deftest preferences-and-settings-field-by-field
  (let [base   (player/subscribe (player/state "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
        phone  (-> base (player/prefer {:skip-forward 45} t1) (player/configure "feed-a" {:skip-intro 20} t1))
        laptop (-> (player/merge (player/state "laptop") base)
                   (player/prefer {:rate 1.5} t2)
                   (player/configure "feed-a" {:auto-queue? true} t2))
        merged (player/merge phone laptop)]
    (is (= {:skip-forward 45 :skip-back 15 :rate 1.5} (player/preferences merged))
        "preferences changed on different devices both survive")
    (is (= {:auto-queue? true :skip-intro 20 :skip-outro 0} (player/settings merged "feed-a"))
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
        (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (player/queue state)))))))

(deftest opml-round-trip
  (let [s    (-> (player/state "phone")
                 (player/subscribe "917393e3-1b1e-5cef-ace4-edaa54e1f810" {:url "https://feeds.podcastindex.org/pc20.xml" :title "Podcasting 2.0"} t0)
                 (player/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                 (player/unsubscribe "feed-b" t1))
        list (player/to-opml s)
        xml  (opml/emit list)]
    (is (= [{:text "Podcasting 2.0" :xml-url "https://feeds.podcastindex.org/pc20.xml" :guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"}]
           (:subscriptions list)))
    (is (= (update-vals (player/subscriptions s) #(select-keys % [:url :title]))
           (update-vals (player/subscriptions (player/from-opml (player/state "laptop") (opml/parse xml) t2))
                        #(select-keys % [:url :title])))
        "the guid carried in the OPML identifies the feed on the other device")
    (testing "an outline without a guid gets the one its URL derives"
      (is (player/subscribed? (player/from-opml (player/state "x") {:subscriptions [{:text "PN" :xml-url "https://podnews.net/rss"}]} t0)
                              "9b024349-ccf0-5f69-a609-6b82873eab3c")))))

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
                           [{:devices {"x" {:seen {"y" 5} :updated-at t0 :device "x"}}} #"seen"]]]
      (is (re-find found (str (player/problem state))) (pr-str state)))))

(deftest merging-entries-with-keys-of-any-kind
  (let [s (player/identify (player/state "phone") {:name "Phone" "custom" 1} t0)]
    (is (= s (player/merge s s)) "an entry ties with itself, and its keys still sort")))

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
