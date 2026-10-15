(ns dk.simongray.podcast.profile-test
  "The profile: subscriptions, remote items, progress, settings and
  preferences, folders, tags and saved filters, merging, compacting and the
  clock."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.impl :as profile.impl]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.player :as profile.player]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 t4 a b c]]
            [dk.simongray.podcast.util.time :as time]))

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
    (is (= s (profile/configure s "feed-b" {:playback-rate 2.0} t1)) "settings for an unknown feed change nothing")
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
        (is (= "declared" (get (profile/by-url moved) (guid/normal-url "https://a/rss"))) "the live one names the URL")
        (is (= ["declared"] (keys (profile/subscriptions (profile/merge other moved)))) "the other device learns the move")
        (is (= s (profile/rekey s "feed-a" "feed-a" t2)) "the same guid changes nothing")
        (is (= s (profile/rekey s "feed-b" "declared" t2)) "no subscription, nothing to move")))
    (testing "a feed that moved takes its subscription along, but never back from an unsubscription"
      (let [moved  (profile/relocate s "feed-a" {:url "https://b/rss" :title "B"})
            laptop (-> (profile/merge (profile/make "laptop") s) (profile/unsubscribe "feed-a" t2))]
        (is (= {:url "https://b/rss" :title "B" :added-at t0} (select-keys (get (profile/subscriptions moved) "feed-a") [:url :title :added-at])))
        (is (= "https://b/rss" (get-in (profile/merge (profile/make "laptop") moved) [:subscriptions "feed-a" :url])) "the other device learns the move")
        (is (not (profile/subscribed? (profile/merge laptop moved) "feed-a")) "the unsubscription on the other device stays")
        (is (= (:clock s) (:clock moved)) "the clock stays")
        (is (= laptop (profile/relocate laptop "feed-a" {:url "https://b/rss" :title "B"})) "no subscription, nothing to move")))
    (testing "a feed at another URL that declares the guid would take the subscription over"
      (is (= [:fetch/guid-taken "https://evil/rss" "feed-a" "https://a/rss"]
             ((juxt :id :url :feed-guid :other-url) (profile/takeover s "feed-a" "https://evil/rss"))))
      (is (nil? (profile/takeover s "feed-a" "http://a/rss/")) "another form of the same URL")
      (is (nil? (profile/takeover s "feed-b" "https://evil/rss")) "a guid without a subscription")
      (is (nil? (profile/takeover (profile/unsubscribe s "feed-a" t2) "feed-a" "https://evil/rss")) "or with a removed one"))))

(deftest remote-items-of-other-shows
  (let [id    ["feed-x" "x1"]
        added (profile/add-remote-item (profile/make "phone") id {:feed-url "https://x/rss"} t0)
        other (profile/make "laptop")]
    (is (= {id "https://x/rss"} (update-vals (profile/remote-items added) :feed-url)))
    (is (= {} (profile/subscriptions added)) "no subscription")
    (is (= {id "https://x/rss"} (update-vals (profile/remote-items (profile/merge other added)) :feed-url))
        "another device learns of it")
    (let [removed (profile/remove-remote-item added id t1)]
      (is (= {} (profile/remote-items removed)))
      (is (contains? (:remote-items removed) id) "a tombstone")
      (is (= {} (profile/remote-items (profile/merge added removed))) "the later write wins"))
    (is (nil? (profile/problem added)))
    (is (re-find #"remote item" (str (profile/problem (assoc-in added [:remote-items id :feed-url] 5)))))))

(deftest progress-and-played
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
              (profile/configure "feed-a" {:skip-intro 15} t0)
              (profile/progress a 600 3600 t1))]
    (is (= 600 (:position (profile/episode-entry s a))))
    (is (not (profile/played? s a)))
    (is (= 600 (profile/resume-position s "feed-a" a)))
    (is (= 15 (profile/resume-position s "feed-a" b)) "an unstarted episode starts after the intro")
    (testing "within the threshold of the end counts as played"
      (let [done (profile/progress s a 3580 t2)]
        (is (profile/played? done a))
        (is (= t2 (:played-at (profile/episode-entry done a))))
        (is (= 15 (profile/resume-position done "feed-a" a)) "a played episode starts over")))
    (testing "the threshold of the app"
      (is (not (profile/played? (profile/progress s a 3580 nil t2 {:played-threshold 10}) a)))
      (is (profile/played? (profile/progress s a 3590 nil t2 {:played-threshold 10}) a))
      (is (profile/played? (profile/progress s a 3300 nil t2 {:played-threshold 300}) a))
      (is (not (profile/played? (profile/skip s a 2980 t2 {:played-threshold 10}) a)))
      (is (profile/played? (profile/skip s a 2980 t2) a))
      (is (= [[:seek a 3580]] (:effects (profile.player/step s [:skip a 2980] t2 {:played-threshold 10}))))
      (is (= [] (:effects (profile.player/step s [:progress a 3580 3600] t2 {:played-threshold 10}))))
      (is (= [[:played a]] (:effects (profile.player/step s [:progress a 3580 3600] t2)))))
    (testing "marks"
      (is (profile/played? (profile/mark-played s b t2) b))
      (let [again (profile/mark-unplayed (profile/mark-played s a t2) a t3)]
        (is (not (profile/played? again a)))
        (is (= 0 (:position (profile/episode-entry again a))))))
    (testing "a later report of the position from a device that hasn't seen the mark leaves the mark"
      (let [done   (profile/mark-played s a t2)
            behind (profile/progress (assoc s :device "laptop") a 700 3600 t3)
            both   (profile/merge done behind)]
        (is (profile/played? both a))
        (is (= 700 (:position (profile/episode-entry both a))))
        (is (= {a (profile/episode-entry both a)} (profile/episode-entries both)))))
    (is (= [b a] (profile/history (-> s (profile/progress b 10 t2)))))
    (is (= [a b] (profile/history (-> s (profile/mark-played a t1) (profile/mark-played b t2) (profile/progress a 5 t3))))
        "an episode heard again comes first again")))

(deftest forgetting-an-episode
  (let [s      (-> (profile/make "phone")
                   (profile/progress a 600 1800 t0)
                   (profile/mark-played b t0))
        forgot (-> s (profile/forget a t1) (profile/forget b t1))]
    (is (= [b a] (profile/history s)))
    (is (= [] (profile/history forgot)) "out of the history")
    (is (nil? (profile/episode-entry forgot a)))
    (is (not (profile/played? forgot b)))
    (is (= 0 (profile/resume-position forgot "feed-a" a)))
    (testing "on another device, which can't bring it back with an older report"
      (let [laptop (-> (profile/make "laptop") (profile/progress a 900 1800 t0))]
        (is (= [] (profile/history (profile/merge forgot laptop))))))
    (testing "until the listener plays it again"
      (let [again (profile/progress forgot a 30 1800 t2)]
        (is (= [a] (profile/history again)))
        (is (= 30 (:position (profile/episode-entry again a))))
        (is (not (profile/played? again a)))
        (is (profile/played? (profile/mark-played forgot b t2) b))))
    (testing "an archived episode too, and compaction doesn't archive it again"
      (let [old      (profile/compact s t4 {:archive-after 1000})
            archived (profile/forget old b t4)]
        (is (profile/played? old b))
        (is (not (profile/played? archived b)))
        (is (nil? (profile/episode-entry archived b)))
        (is (not (profile/played? (profile/compact archived (time/later t4 3600) {:archive-after 1000}) b)))))
    (is (nil? (profile/problem forgot)))))

(deftest hiding-episodes
  (let [s (profile/make "phone")]
    (is (nil? (profile/hidden s a)) "neither hidden nor shown, so its show decides")
    (is (true? (profile/hidden (profile/hide s a t0) a)))
    (is (false? (profile/hidden (-> s (profile/hide a t0) (profile/unhide a t1)) a)))
    (is (= [true true] (map #(profile/hidden (profile/hide-all s [a b] t0) %) [a b])))
    (is (true? (profile/hidden (profile/merge (profile/make "laptop") (profile/hide s a t0)) a)) "on every device")))

(deftest skipping
  (let [s (-> (profile/make "phone") (profile/progress a 100 3600 t0))]
    (is (= 130 (:position (profile/episode-entry (profile/skip s a 30 t1) a))))
    (is (= 85 (:position (profile/episode-entry (profile/skip s a -15 t1) a))))
    (is (= 0 (:position (profile/episode-entry (profile/skip s a -500 t1) a))) "never before the start")
    (is (= 3600 (:position (profile/episode-entry (profile/skip s a 5000 t1) a))) "never past the end")
    (is (= 40 (:position (profile/episode-entry (profile/skip (profile/make "x") c 40 t1) c))) "an unknown episode starts at zero")
    (doseq [duration [0 ##NaN]]
      (is (= 130 (:position (profile/episode-entry (profile/skip (profile/progress s a 100 duration t0) a 30 t1) a)))
          (str "a duration of " duration " is not known, and no bound")))
    (is (= [[:seek a 3590] [:played a]] (:effects (profile.player/step s [:skip a 3490] t1)))
        "a skip into the end marks it played, and says so")
    (testing "the preferred amounts, through the reducer, with the seek as the effect"
      (let [s (profile/prefer s {:skip-forward 45} t0)]
        (is (= (assoc profile/default-preferences :skip-forward 45) (profile/preferences s)))
        (is (= [[:seek a 145]] (:effects (profile.player/step s [:skip a :forward] t1))))
        (is (= [[:seek a 85]] (:effects (profile.player/step s [:skip a :back] t1))))
        (is (= [[:seek a 110]] (:effects (profile.player/step s [:skip a 10] t1))))))
    (testing "a feed's own rate overrides the listener's"
      (let [s (-> s
                  (profile/prefer {:playback-rate 1.5} t0)
                  (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                  (profile/configure "feed-a" {:playback-rate 1.0} t0))]
        (is (= 1.0 (profile/playback-rate s "feed-a")))
        (is (= 1.5 (profile/playback-rate s "feed-b")))))
    (testing "a feed's own choice of video overrides the listener's"
      (let [s (-> s
                  (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                  (profile/configure "feed-a" {:video? true} t0))]
        (is (false? (profile/prefers-video? s "feed-b")) "audio unless the listener says")
        (is (true? (profile/prefers-video? s "feed-a")))
        (is (false? (profile/prefers-video? (-> s
                                                (profile/prefer {:video? true} t1)
                                                (profile/configure "feed-a" {:video? false} t1))
                                            "feed-a"))
            "a show's own false stands")))
    (testing "a show's own choice of notifications overrides the listener's"
      (let [s (-> s
                  (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                  (profile/configure "feed-a" {:notify? true} t0))]
        (is (false? (profile/notifies? s "feed-b")) "silent unless the listener says")
        (is (true? (profile/notifies? s "feed-a")))
        (is (false? (profile/notifies? (-> s
                                           (profile/prefer {:notify? true} t1)
                                           (profile/configure "feed-a" {:notify? false} t1))
                                       "feed-a"))
            "a show's own false stands")))))

(deftest what-a-listener-sets-once
  (testing "the artwork of a subscription, for a device that hasn't fetched its feed"
    (let [s (profile/subscribe (profile/make "phone") "feed-a" {:url "https://a/rss" :title "A" :image "https://a/art.jpg"} t0)]
      (is (= "https://a/art.jpg" (get-in (profile/subscriptions s) ["feed-a" :image])))
      (is (= "https://b/art.jpg" (get-in (profile/relocate s "feed-a" {:url "https://b/rss" :title "B" :image "https://b/art.jpg"})
                                         [:subscriptions "feed-a" :image])))
      (is (= "https://a/art.jpg" (get-in (profile/relocate s "feed-a" {:url "https://b/rss" :title "B"}) [:subscriptions "feed-a" :image]))
          "and kept when the feed names none")
      (is (= "https://a/art.jpg" (get-in (profile/rekey s "feed-a" "declared" t1) [:subscriptions "declared" :image]))
          "and when the subscription moves")))
  (testing "the settings of a new subscription"
    (let [s (profile/prefer (profile/make "phone") {:subscription-defaults {:auto-queue? true :skip-intro 10}} t0)
          a (profile/subscribe s "feed-a" {:url "https://a/rss" :title "A"} t1)]
      (is (= [true 10] ((juxt :auto-queue? :skip-intro) (profile/settings a "feed-a"))))
      (is (= #{(:clock a)} (set (map :updated-at [(get-in a [:subscriptions "feed-a"]) (get-in a [:settings "feed-a"])])))
          "with one stamp")
      (is (= profile/default-settings
             (profile/settings (-> (profile/subscribe (profile/make "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
                                   (profile/prefer {:subscription-defaults {:auto-queue? true}} t1))
                               "feed-a"))
          "but not those the listener has already")
      (let [back (-> (profile/configure a "feed-a" {:skip-intro 30} t2)
                     (profile/unsubscribe "feed-a" t3)
                     (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t4))]
        (is (= 30 (:skip-intro (profile/settings back "feed-a"))) "nor a show that had settings before"))))
  (testing "a rewind after a pause"
    (let [s       (profile/progress (profile/make "phone") a 600 3600 t0)
          after   #(time/->inst (+ (inst-ms t0) (* 1000 %)))
          resumed #(profile/resume-position %1 "feed-a" a (after %2))]
      (is (= 600 (resumed s 30)) "none after a short pause")
      (is (= 597 (resumed s 120)))
      (is (= 590 (resumed s 7200)))
      (is (= 570 (resumed s 200000)))
      (is (= 600 (profile/resume-position s "feed-a" a)) "none without the time")
      (is (= 600 (resumed (profile/prefer s {:rewinds []} t0) 200000)) "nor without rewinds")
      (is (= 0 (resumed (profile/progress s a 5 3600 t0) 200000)) "never before the start")))
  (testing "marks of many episodes at once"
    (let [s      (-> (profile/make "phone")
                     (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0))
          played (profile/mark-all-played s [a b c] t1)]
      (is (every? #(profile/played? played %) [a b c]))
      (is (= 1 (count (set (map :updated-at (vals (:played played)))))) "with one stamp")
      (is (not-any? #(profile/played? (profile/mark-all-unplayed played [a b] t2) %) [a b]))
      (is (identical? s (profile/mark-all-played s [] t1)) "and nothing for none, not even the clock"))))

(deftest preferences-and-settings-field-by-field
  (let [base   (profile/subscribe (profile/make "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
        phone  (-> base (profile/prefer {:skip-forward 45} t1) (profile/configure "feed-a" {:skip-intro 20} t1))
        laptop (-> (profile/merge (profile/make "laptop") base)
                   (profile/prefer {:playback-rate 1.5} t2)
                   (profile/configure "feed-a" {:auto-queue? true} t2))
        merged (profile/merge phone laptop)]
    (is (= (assoc profile/default-preferences :skip-forward 45 :playback-rate 1.5) (profile/preferences merged))
        "preferences changed on different devices both survive")
    (is (= (assoc profile/default-settings :auto-queue? true :skip-intro 20) (profile/settings merged "feed-a"))
        "and so do a subscription's settings")
    (is (= 60 (:skip-forward (profile/preferences (profile/merge phone (profile/prefer laptop {:skip-forward 60} t3)))))
        "the same field on both: the later wins")
    (is (= 20 (:skip-intro (profile/settings (profile/unsubscribe merged "feed-a" t3) "feed-a")))
        "settings outlive the subscription, for when it comes back")))

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
            list (profile.opml/to-opml s)
            back (profile.opml/from-opml (profile/make "laptop") (opml/parse (opml/emit list)) t2)]
        (is (= [["feed-a" ["News"]] ["feed-c" ["News"]] ["feed-b" nil]] (map (juxt :guid :path) (:subscriptions list)))
            "the folders by name first, then the shows in none")
        (is (= ["Daily" "Danish"] (:category (first (:subscriptions list)))))
        (is (= (profile/folders s) (profile/folders back)))
        (is (= (profile/tagged s) (profile/tagged back)))
        (is (= t0 (get-in back [:subscriptions "feed-a" :added-at])) "subscribed when the other device subscribed")))
    (is (= {"Danish" ["x"]}
           (profile/folders (profile.opml/from-opml (profile/make "x") {:subscriptions [{:text "X" :xml-url "https://x/rss" :guid "x"
                                                                                         :path ["News" "Danish"]}]}
                                                    t0)))
        "the innermost of nested folders")))

(deftest the-order-and-titles-of-shows
  (let [s (-> (profile/make "phone")
              (profile/subscribe "feed-a" {:url "https://a/rss" :title "banana"} t0)
              (profile/subscribe "feed-b" {:url "https://b/rss" :title "Apple"} t0)
              (profile/subscribe "feed-c" {:url "https://c/rss" :title "cherry"} t0))]
    (is (= ["feed-b" "feed-a" "feed-c"] (profile/shows s)) "by title, whatever the case")
    (testing "the listener's own title, which a move of the feed leaves be"
      (let [own (-> (profile/configure s "feed-c" {:custom-title "Aardvark"} t1)
                    (profile/relocate "feed-c" {:url "https://c2/rss" :title "Cherry 2"}))]
        (is (= "Aardvark" (profile/title own "feed-c")))
        (is (= "Apple" (profile/title own "feed-b")) "or the title of the feed")
        (is (= ["feed-c" "feed-b" "feed-a"] (profile/shows own)))
        (is (= [["Aardvark" "Cherry 2"] ["Apple" "Apple"] ["banana" "banana"]]
               (map (juxt :text :title) (:subscriptions (profile.opml/to-opml own))))
            "in OPML as the text of the outline, in the listener's order")))
    (testing "a move places every show, so that the others keep their places"
      (let [moved (profile/move-show s "feed-c" 0 t1)]
        (is (= ["feed-c" "feed-b" "feed-a"] (profile/shows moved)))
        (is (= ["feed-b" "feed-c" "feed-a"] (profile/shows (profile/move-show moved "feed-b" 0 t2))))
        (is (= ["feed-b" "feed-a" "feed-c"] (profile/shows (profile/move-show moved "feed-c" 9 t2))) "an index past the end")
        (is (= ["feed-c" "feed-b" "feed-a" "feed-d"]
               (profile/shows (profile/subscribe moved "feed-d" {:url "https://d/rss" :title "Date"} t2)))
            "a new show comes after the placed ones")
        (is (= ["feed-b" "feed-a"] (profile/shows (profile/unsubscribe moved "feed-c" t2))))
        (is (= {nil ["feed-c" "feed-b" "feed-a"]} (profile/folders moved)) "the folders keep the order")
        (is (= moved (profile/move-show moved "feed-x" 0 t2)) "a show the profile doesn't hold stays out")
        (is (nil? (profile/problem moved)))
        (testing "on another device, and under the guid that a feed declares"
          (let [laptop (profile/merge (profile/make "laptop") moved)]
            (is (= ["feed-c" "feed-b" "feed-a"] (profile/shows laptop)))
            (is (= ["declared" "feed-b" "feed-a"] (profile/shows (profile/rekey laptop "feed-c" "declared" t2))))))))))

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
                   (profile.marks/star a t0) (profile.marks/unstar a t1)
                   (profile.marks/bookmark a 30 "Kept" t0) (profile.marks/bookmark a 60 "Gone" t0) (profile.marks/unbookmark a 60 t1)
                   (profile.marks/bookmark b 90 "Gone" t0) (profile.marks/unbookmark b 90 t1)
                   (crdt/write [:inbox b] (constantly {:removed? false}) t0) (profile.queue/dismiss b t1)
                   (profile/save-filter "F" {} t0) (profile/remove-filter "F" t1)
                   (profile.marks/clip a 30 10 "Kept" t0) (profile.marks/clip b 60 10 "Gone" t0) (profile.marks/unclip b 60 t1)
                   (profile.marks/rate-episode a 4 t0) (profile.marks/rate-episode b 2 t0) (profile.marks/unrate-episode b t1))
        laptop (crdt/acknowledge (profile/merge (profile/make "laptop") phone) [phone] t2)
        phone  (crdt/acknowledge (profile/merge phone laptop) [laptop] t2)
        after  (profile/compact phone far)]
    (is (= {:stars {} :inbox {} :filters {} :bookmarks {a {30 (get-in phone [:bookmarks a 30])}}
            :clips {a {30 (get-in phone [:clips a 30])}} :ratings {a (get-in phone [:ratings a])}}
           (select-keys after [:stars :inbox :filters :bookmarks :clips :ratings]))
        "the tombstones go, and an episode left with no bookmark goes with them")
    (is (= (profile.marks/bookmarks phone) (profile.marks/bookmarks after)))
    (is (= (profile.marks/clips phone) (profile.marks/clips after)))
    (is (= (profile.marks/rated phone) (profile.marks/rated after)))))

(deftest merging-two-devices
  (let [phone  (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                   (profile/progress a 100 3600 t1)
                   (profile.queue/enqueue a t1)
                   (profile/prefer {:skip-forward 45} t1))
        laptop (-> (profile/make "laptop")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/unsubscribe "feed-b" t2)
                   (profile/progress a 900 3600 t2)
                   (profile.queue/enqueue c t3)
                   (profile/prefer {:skip-forward 60} t2))
        merged (profile/merge phone laptop)]
    (is (= "phone" (:device merged)))
    (is (not (profile/subscribed? merged "feed-b")) "the later removal beats the earlier addition")
    (is (= 900 (:position (profile/episode-entry merged a))) "the later position wins")
    (is (= [a c] (profile.queue/queue merged)) "what both devices queued survives, in the order it was queued")
    (is (= 60 (:skip-forward (profile/preferences merged))) "the later preferences win")
    (testing "the same whichever side asks, and again"
      (is (= (dissoc merged :device) (dissoc (profile/merge laptop phone) :device)))
      (is (= merged (profile/merge merged laptop)))
      (is (= merged (profile/merge merged merged))))
    (testing "a fresh device takes everything, the clock included"
      (let [fresh (profile/merge (profile/make "tablet") laptop)]
        (is (= [c] (profile.queue/queue fresh)))
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
                                   (profile.queue/enqueue b t1)
                                   (profile.marks/clip a 30 10 "A clip" t1)
                                   (profile.marks/unclip a 30 t2)
                                   (profile.marks/rate-episode a 4 t1)
                                   (profile.marks/unrate-episode a t2)
                                   (profile.marks/rate-episode b 50 t1 {:scale {:worst 1 :best 100}})
                                   (crdt/acknowledge [(profile/subscribe (profile/make "laptop") "feed-b" {:url "https://b/rss"} t0)] t2))))))
  (testing "what would make an operation throw on every device it reached"
    (doseq [[profile found] [[{:clock "now"} #"clock"]
                             [{:episodes {a {:updated-at "soon" :device "x"}}} #"stamped \"soon\""]
                             [{:subscriptions [1 2 3]} #":subscriptions is not a map"]
                             [{:episodes {a "x"}} #"not a map"]
                             [{:queue {a {:rank "V!" :updated-at t0 :device "x"}}} #"not a rank"]
                             [{:playlists {"P" {a {:rank "V!" :updated-at t0 :device "x"}}}} #"\[:playlists \"P\""]
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
                             [{:archive {"feed-a" {"x" {:items ["a1"] :n 1 :updated-at t0 :device "x"}}}}
                              #"the archive of \"feed-a\" is not a set"]
                             [{:archive {"feed-a" {"x" {:items #{"a1"} :n 9 :updated-at t0 :device "x"}}}}
                              #"with its count"]
                             [{:tags {"feed-a" "Tech"}} #"\[:tags \"feed-a\"\] is not a map"]
                             [{:app/notes {"n1" "text"}} #"\[:app/notes \"n1\"\] is not in a stamped entry"]]]
      (is (re-find found (str (profile/problem profile))) (pr-str profile))))
  (testing "an entry without a stamp, which two devices merge to two different states"
    (let [here  {:device "a" :stars {a {:removed? false}}}
          there {:device "b" :stars {a {:removed? true}}}]
      (is (not= (dissoc (profile/merge here there) :device) (dissoc (profile/merge there here) :device)))
      (is (= "the entry at [:stars [\"feed-a\" \"a1\"]] is not stamped" (profile/problem here)))
      (doseq [profile [{:queue {a {}}} {:settings {"feed-a" {:folder {:value "News"}}}}
                       {:listened {"feed-a" {"2026-09" {"x" {:seconds 5}}}}} {:retired {"x" {}}}]]
        (is (re-find #"is not stamped" (str (profile/problem profile))) (pr-str profile)))))
  (testing "criteria of a later version are no problem of the state"
    (is (nil? (profile/problem {:filters {"F" {:criteria {:where {:mood "calm"}} :updated-at t0 :device "x"}}})))))

(deftest settings-of-an-earlier-version
  (let [old {:device        "p"
             :subscriptions {"feed-a" {:url "https://a/rss" :updated-at t0 :device "p"}}
             :settings      {"feed-a" {:skip-intro {:value 20 :updated-at t0 :device "p"}
                                       :folder     {:value "News" :updated-at t1 :device "q"}
                                       :inbox?     {:value true :removed? true :updated-at t1 :device "p"}}}}
        up  (profile.impl/upgrade old)]
    (is (nil? (profile/problem up)))
    (is (= (assoc profile/default-settings :skip-intro 20 :folder "News") (profile/settings up "feed-a"))
        "an entry for each setting, as one entry of the subscription")
    (is (= [t0 t1] (map #(get-in up [:settings "feed-a" :stamps % :updated-at]) [:skip-intro :folder]))
        "in which each setting keeps its stamp")
    (is (= up (profile.impl/upgrade up)) "and a profile of this version stays as it is")))

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
      (let [rate (fn [v] {:device "p" :preferences {:playback-rate {:value v :updated-at t0 :device "p"}}})]
        (is (== 1 (:playback-rate (profile/preferences (profile/merge (rate 1.0) (rate 1.5))))))
        (is (== 1 (:playback-rate (profile/preferences (profile/merge (rate 1.5) (rate 1.0))))))))))

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
            s    (profile.opml/from-opml (profile/make "phone") list t1)]
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
      (is (= (profile/episode-entry (crdt/as-device s "laptop" #(profile/progress % a 100 3600 t4)) a)
             (profile/episode-entry (profile/merge s (profile/progress (profile/make "laptop") a 100 3600 t4)) a))))))

(deftest acknowledging-and-compacting
  (let [day    (* 24 60 60 1000)
        far    (time/->inst (+ (inst-ms t3) (* 31 day)))
        phone  (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                   (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                   (profile/unsubscribe "feed-b" t1)
                   (profile.queue/enqueue a t1)
                   (profile.queue/dequeue a t2))
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
        (is (= (profile.queue/queue phone) (profile.queue/queue compacted))))
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

(deftest archiving-played-episodes
  (let [year   (* 365 24 60 60 1000)
        later  (time/->inst (+ (inst-ms t2) year))
        phone  (-> (profile/make "phone")
                   (profile/progress a 3590 3600 t0)
                   (profile/mark-played b t0)
                   (profile/progress c 100 3600 t0))
        moved  (profile/compact phone later)
        laptop (assoc phone :device "laptop")]
    (testing "a played episode that nothing changed for a year moves to the archive of its show"
      (is (= {"phone" {:items #{"a1" "a2"} :n 2 :updated-at later :device "phone"}} (get-in moved [:archive "feed-a"])))
      (is (= {} (:played moved)))
      (is (= [c] (keys (:episodes moved))) "an episode that isn't played stays")
      (is (every? #(profile/played? moved %) [a b]))
      (is (= {:played? true :updated-at later} (profile/episode-entry moved a)) "a mark with no position or time of its own")
      (is (= [c] (keys (profile/episode-entries moved))) "and the history keeps to the year")
      (is (= moved (profile/compact moved later)))
      (is (nil? (profile/problem moved))))
    (testing "the time is an option"
      (is (= phone (profile/compact phone later {:archive-after (* 2 year)}))))
    (testing "a device that hasn't seen the move brings back what the archive says too"
      (let [both (profile/merge moved laptop)]
        (is (every? #(profile/played? both %) [a b]))
        (is (= moved (profile/compact both later)))))
    (testing "an unplay from a device that hasn't seen the move wins, now and later"
      (let [both (profile/merge moved (profile/mark-unplayed laptop a later))]
        (is (not (profile/played? both a)))
        (is (not (profile/played? (profile/compact both (time/->inst (+ (inst-ms later) year))) a)))))
    (testing "an archived episode heard again has its position, and is archived again a year later"
      (let [again (profile/progress moved a 600 3600 later)]
        (is (= {:position 600 :played? true} (select-keys (profile/episode-entry again a) [:position :played?])))
        (is (= 600 (:position (profile/episode-entry (profile/compact again later) a))))
        (is (= (dissoc moved :clock)
               (dissoc (profile/compact again (time/->inst (+ (inst-ms later) year 1000))) :clock)))))
    (testing "two devices that archive an episode both hold it, and one that knows the other's archive adds nothing"
      (let [laptop' (profile/compact laptop later)
            both    (profile/merge moved laptop')]
        (is (= #{"phone" "laptop"} (set (keys (get-in both [:archive "feed-a"])))))
        (is (every? #(profile/played? both %) [a b]))
        (is (= (:archive moved) (:archive (profile/compact (profile/merge laptop moved) later))))))
    (testing "a new guid of an archived episode is played too"
      (let [renamed (profile/rename-episode moved a ["feed-a" "a1-new"] later)]
        (is (profile/played? renamed ["feed-a" "a1-new"]))
        (is (= renamed (profile/rename-episode renamed a ["feed-a" "a1-new"] later)))))))

(deftest the-changes-since-a-state
  (let [taken   (-> (profile/make "phone")
                    (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
                    (profile/prefer {:playback-rate 1.5} t1))
        s       (-> taken
                    (profile/progress a 100 3600 t2)
                    (profile.queue/enqueue a t3))
        changes (crdt/changes-since s taken)]
    (is (= #{:device :clock :episodes :queue} (set (keys changes)))
        "what was written since the state was taken, with the device and the clock")
    (is (= (profile/episode-entry s a) (profile/episode-entry changes a)))
    (is (= s (profile/merge s changes)) "a partial state merges into the whole without changing it")
    (let [laptop (profile/subscribe (profile/make "laptop") "feed-b" {:url "https://b/rss" :title "B"} t0)]
      (is (contains? (:subscriptions (crdt/changes-since (profile/merge s laptop) taken)) "feed-b")
          "and what was merged in since, however early its stamp"))
    (is (= (dissoc s :device)
           (dissoc (profile/merge (profile/make "laptop") (crdt/changes-since s nil)) :device))
        "nil means everything")))
