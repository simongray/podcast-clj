(ns dk.simongray.podcast.player-test
  "The player state: subscriptions, progress, the queue, refreshes, OPML,
  merging and the reducer."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.opml :as opml]
            [dk.simongray.podcast.player :as player]))

(def t0 #inst "2026-01-01T00:00:00Z")
(def t1 #inst "2026-01-01T01:00:00Z")
(def t2 #inst "2026-01-01T02:00:00Z")
(def t3 #inst "2026-01-01T03:00:00Z")

(def a ["feed-a" "a1"])
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
    (is (= [b a] (player/history (-> s (player/progress b 10 t2)))))))

(deftest skipping
  (let [s (-> (player/state "phone") (player/progress a 100 3600 t0))]
    (is (= 130 (:position (player/episode (player/skip s a 30 t1) a))))
    (is (= 85 (:position (player/episode (player/skip s a -15 t1) a))))
    (is (= 0 (:position (player/episode (player/skip s a -500 t1) a))) "never before the start")
    (is (= 3600 (:position (player/episode (player/skip s a 5000 t1) a))) "never past the end")
    (is (= 40 (:position (player/episode (player/skip (player/state "x") c 40 t1) c))) "an unknown episode starts at zero")
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
    (is (= [b] (player/queue (player/advance s t1))))
    (is (= [] (player/queue (player/advance (player/state "x") t1))))
    (is (= t1 (get-in (player/advance s t1) [:queue :updated-at])))))

(deftest refreshing-a-feed
  (let [item   (fn [id] {:rss/guid {:content id} :rss/title id})
        before {:podcast/guid "feed-a" :rss/items [(item "a2") (item "a1")]}
        after  {:podcast/guid "feed-a" :rss/items [(item "a4") (item "a3") (item "a2") (item "a1")]}
        s      (-> (player/state "phone") (player/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0))]
    (is (= [["feed-a" "a3"] ["feed-a" "a4"]] (player/new-episodes before after "https://a/rss")) "oldest first")
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
    (is (= [c] (player/queue merged)) "the later queue wins whole")
    (is (= 60 (:skip-forward (player/preferences merged))) "the later preferences win")
    (testing "the same whichever side asks, and again"
      (is (= (dissoc merged :device) (dissoc (player/merge laptop phone) :device)))
      (is (= merged (player/merge merged laptop)))
      (is (= merged (player/merge merged merged))))))

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
