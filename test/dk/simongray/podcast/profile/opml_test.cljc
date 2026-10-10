(ns dk.simongray.podcast.profile.opml-test
  "The followed lists of a profile, and its subscriptions as OPML."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 t4]]
            [dk.simongray.podcast.util.time :as time]))

(deftest following-lists
  (let [url  "https://lists.example/picks.opml"
        g    #(guid/from-url %)
        v1   {:subscriptions [{:text "PC20" :xml-url "https://pc20/rss"} {:text "Mine" :xml-url "https://mine/rss"}]}
        v2   {:subscriptions [{:text "PC20" :xml-url "https://pc20/rss"}
                              {:text "A description" :title "New" :xml-url "https://new/rss"}]}
        s    (-> (profile/make "phone")
                 (profile/subscribe (g "https://mine/rss") {:url "https://mine/rss" :title "Mine"} t0)
                 (profile.opml/follow-list url {:title "Picks" :folder "Picks"} t0))
        r1   (profile.opml/refresh-list s url nil v1 t1)]
    (is (= {url {:title "Picks" :folder "Picks"}} (profile.opml/lists s)))
    (testing "a first parse subscribes to the shows the state does not know, into the list's folder"
      (is (= [(g "https://pc20/rss")] (:added r1)))
      (is (= t1 (:clock (:profile r1))) "all at the same stamp")
      (is (= {"Picks" [(g "https://pc20/rss")] nil [(g "https://mine/rss")]} (profile/folders (:profile r1)))
          "the listener's own show stays where it was"))
    (testing "later parses add the shows that join, and report the ones that leave"
      (let [unsubscribed (profile/unsubscribe (:profile r1) (g "https://pc20/rss") t2)
            {:keys [profile added left]} (profile.opml/refresh-list unsubscribed url v1 v2 t3)]
        (is (= [(g "https://new/rss")] added))
        (is (= "New" (get-in profile [:subscriptions (g "https://new/rss") :title])) "the feed's title, not the text")
        (is (= [(g "https://mine/rss")] left) "a show that left stays, for the app to decide")
        (is (profile/subscribed? profile (g "https://mine/rss")))
        (is (not (profile/subscribed? profile (g "https://pc20/rss"))) "a show the listener left stays left")))
    (is (= {:profile s :added [] :left []} (profile.opml/refresh-list s "https://other/list.opml" nil v1 t1))
        "a list the listener does not follow changes nothing")
    (is (= {} (profile.opml/lists (profile.opml/unfollow-list s url t1))))
    (testing "the state knows a show by the URL of its feed, under whatever guid"
      (let [s     (-> (profile/make "phone")
                      (profile/subscribe "declared" {:url "https://pc20/rss" :title "PC20"} t0)
                      (profile/subscribe "private" {:url "https://simon:secret@members.example/rss" :title "Members"} t0)
                      (profile.opml/follow-list url {:title "Picks"} t0))
            twice {:subscriptions [{:text "PC20" :xml-url "http://PC20/rss/"}
                                   {:text "Members" :xml-url "https://members.example/rss"}
                                   {:text "New" :xml-url "https://new/rss"}
                                   {:text "New, again" :xml-url "https://NEW/rss"}]}
            {:keys [profile added left]} (profile.opml/refresh-list s url nil twice t1)]
        (is (= [(g "https://new/rss")] added) "and a show that the list names twice comes in once")
        (is (= #{"declared" "private" (g "https://new/rss")} (set (keys (profile/subscriptions profile)))))
        (is (= "https://simon:secret@members.example/rss" (get-in profile [:subscriptions "private" :url]))
            "a private feed is the feed at its URL without the password, and keeps its own URL")
        (is (= ["declared" "private" (g "https://new/rss")]
               (:left (profile.opml/refresh-list profile url twice {:subscriptions []} t2)))
            "a show that leaves has the guid that the state knows it by")
        (is (= [] left))))
    (testing "a show that the listener left stays left on a device that joins after the compaction"
      (let [day    (* 24 60 60 1000)
            far    (time/->inst (+ (inst-ms t4) (* 40 day)))
            phone  (-> (crdt/identify (:profile r1) {:name "Phone"} t1)
                       (profile/unsubscribe (g "https://pc20/rss") t2))
            laptop (-> (crdt/identify (profile/make "laptop") {:name "Laptop"} t2)
                       (profile/merge phone)
                       (crdt/acknowledge [phone] t3))
            phone  (profile/compact (crdt/acknowledge (profile/merge phone laptop) [laptop] t3) far)
            tablet (profile/merge (profile/make "tablet") phone)]
        (is (:removed? (get-in phone [:subscriptions (g "https://pc20/rss")]))
            "the tombstone stays while the listener follows a list")
        (is (= [] (:added (profile.opml/refresh-list tablet url nil v1 far))))
        (is (nil? (get-in (profile/compact (profile.opml/unfollow-list phone url t4) far {:keep-for 0})
                          [:subscriptions (g "https://pc20/rss")]))
            "and it goes when the listener follows none")))
    (testing "in OPML, as an inclusion in the list's folder, and followed again from it"
      (let [xml (opml/emit (profile.opml/to-opml (:profile r1)))]
        (is (re-find #"<outline text=\"Picks\">\s*<outline type=\"rss\"[^>]*/>\s*<outline type=\"include\" text=\"Picks\" url=\"https://lists.example/picks.opml\"/>" xml))
        (is (= {url {:title "Picks" :folder "Picks"}}
               (profile.opml/lists (profile.opml/from-opml (profile/make "laptop") (opml/parse xml) t2))))))))

(deftest opml-round-trip
  (let [s    (-> (profile/make "phone")
                 (profile/subscribe "917393e3-1b1e-5cef-ace4-edaa54e1f810" {:url "https://feeds.podcastindex.org/pc20.xml" :title "Podcasting 2.0"} t0)
                 (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t0)
                 (profile/unsubscribe "feed-b" t1))
        list (profile.opml/to-opml s)
        xml  (opml/emit list)]
    (is (= [{:text "Podcasting 2.0" :title "Podcasting 2.0" :xml-url "https://feeds.podcastindex.org/pc20.xml"
             :guid "917393e3-1b1e-5cef-ace4-edaa54e1f810" :created t0}]
           (:subscriptions list)))
    (is (= (update-vals (profile/subscriptions s) #(select-keys % [:url :title]))
           (update-vals (profile/subscriptions (profile.opml/from-opml (profile/make "laptop") (opml/parse xml) t2))
                        #(select-keys % [:url :title])))
        "the guid carried in the OPML identifies the feed on the other device")
    (testing "an outline without a guid gets the one its URL derives"
      (is (profile/subscribed? (profile.opml/from-opml (profile/make "x") {:subscriptions [{:text "PN" :xml-url "https://podnews.net/rss"}]} t0)
                               "9b024349-ccf0-5f69-a609-6b82873eab3c")))
    (testing "the feed's title before the text, which some apps fill with the description"
      (is (= "Podnews" (get-in (profile.opml/from-opml (profile/make "x")
                                                       {:subscriptions [{:text "Daily news about podcasting" :title "Podnews"
                                                                         :xml-url "https://podnews.net/rss"}]}
                                                       t0)
                               [:subscriptions "9b024349-ccf0-5f69-a609-6b82873eab3c" :title]))))
    (testing "a folder around the whole list is a wrapper, not a folder, when its name is one that apps write"
      (let [around  (fn [folder]
                      (opml/parse (str "<opml version=\"1.0\"><head><title>Feeds</title></head><body><outline text=\"" folder "\">
                                          <outline type=\"rss\" text=\"A\" xmlUrl=\"https://a/rss\"/>
                                          <outline type=\"rss\" text=\"B\" xmlUrl=\"https://b/rss\"/></outline></body></opml>")))
            folders (fn [list opts] (keys (profile/folders (profile.opml/from-opml (profile/make "x") list t0 opts))))]
        (is (= [nil] (keys (profile/folders (profile.opml/from-opml (profile/make "x") (around "feeds") t0)))))
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
                     (profile.opml/follow-list "https://l/picks.opml" {:title "Picks" :folder "News"} t0))
            back (profile.opml/from-opml (profile/make "laptop") (opml/parse (opml/emit (profile.opml/to-opml s))) t1)]
        (is (= {"News" ["feed-a" "feed-b"]} (profile/folders back)))
        (is (= (profile.opml/lists s) (profile.opml/lists back)))))
    (testing "a show that the state has by its URL keeps its guid"
      (let [s    (-> (profile/make "phone")
                     (profile/subscribe "declared" {:url "https://a.example/rss" :title "A"} t0)
                     (profile/subscribe "private" {:url "https://simon:secret@members.example/rss" :title "Members"} t0))
            list {:subscriptions [{:text "A, again" :xml-url "HTTP://A.example/rss/"}
                                  {:text "A, from another app" :xml-url "https://a.example/rss" :guid "other"}
                                  {:text "Members" :xml-url "https://members.example/rss"}
                                  {:text "B" :xml-url "https://b.example/rss" :guid " "}]}
            back (profile.opml/from-opml s list t1)]
        (is (= #{"declared" "private" (guid/from-url "https://b.example/rss")} (set (keys (profile/subscriptions back))))
            "whatever the scheme, the case and the trailing slash, and a blank guid is none")
        (is (= ["https://a.example/rss" "https://simon:secret@members.example/rss"]
               (map #(get-in back [:subscriptions % :url]) ["declared" "private"]))
            "and its URL, so a private feed keeps its password")))))
