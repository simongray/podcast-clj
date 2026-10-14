(ns dk.simongray.podcast.profile.opml-test
  "The followed lists of a profile, and its subscriptions as OPML."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
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

(def overcast
  "An extended export of Overcast, with the episodes that the listener
  played, started and deleted, and a show that the listener left."
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<opml version=\"1.0\"><head><title>Overcast Podcast Subscriptions</title></head><body>
<outline text=\"playlists\"><outline type=\"podcast-playlist\" title=\"All Episodes\" smart=\"1\" sorting=\"chronological\"/></outline>
<outline text=\"feeds\">
<outline type=\"rss\" overcastId=\"1\" text=\"A\" title=\"A\" xmlUrl=\"https://a.example/rss\" subscribed=\"1\" notifications=\"0\">
<outline type=\"podcast-episode\" overcastId=\"11\" title=\"One\" enclosureUrl=\"https://a.example/1.mp3?t=9\" userUpdatedDate=\"2026-01-01T03:00:00-05:00\" played=\"1\"/>
<outline type=\"podcast-episode\" overcastId=\"12\" title=\"Two\" enclosureUrl=\"https://a.example/2.mp3\" progress=\"754\"/>
<outline type=\"podcast-episode\" overcastId=\"13\" title=\"Three\" enclosureUrl=\"https://a.example/3.mp3\" userDeleted=\"1\"/>
</outline>
<outline type=\"rss\" overcastId=\"2\" text=\"B\" title=\"B\" xmlUrl=\"https://b.example/rss\" subscribed=\"0\"/>
</outline></body></opml>")

(deftest overcasts-extended-export
  (let [list  (opml/parse overcast)
        a     (guid/from-url "https://a.example/rss")
        media {"https://a.example/1.mp3" "a1" "https://a.example/2.mp3" "a2" "https://a.example/3.mp3" "a3"}
        id-fn (fn [_ url] (some->> (get media (first (str/split url #"\?"))) (vector a)))
        s     (profile.opml/from-opml (profile/make "x") list t0)
        s'    (profile.opml/episodes-from-opml s list id-fn t1)]
    (is (= [["One" "1"] ["Two" nil] ["Three" nil]] (map (juxt :title :played) (:episodes (first (:subscriptions list))))))
    (is (= [a] (keys (profile/subscriptions s))) "without the show that the listener left")
    (is (= 2 (count (profile/subscriptions (profile.opml/from-opml (profile/make "x") list t0 {:unsubscribed? true})))))
    (testing "the episodes by their media"
      (is (profile/played? s' [a "a1"]))
      (is (= #inst "2026-01-01T08:00:00Z" (:played-at (profile/episode-entry s' [a "a1"]))) "at the time in the export")
      (is (= 754 (:position (profile/episode-entry s' [a "a2"]))))
      (is (true? (profile/hidden s' [a "a3"])) "a deleted one hidden")
      (is (= s' (profile.opml/episodes-from-opml s' list id-fn t2)) "and none that the profile has already"))
    (testing "and back, with the URLs of their media"
      (let [out (profile.opml/to-opml s' {:media-url-fn (fn [[_ i]] (str "https://a.example/" (subs i 1) ".mp3"))})]
        (is (= [["https://a.example/1.mp3" "1" nil nil] ["https://a.example/2.mp3" nil 754 nil] ["https://a.example/3.mp3" nil nil "1"]]
               (map (juxt :enclosure-url :played :progress :user-deleted) (:episodes (first (:subscriptions out))))))
        (is (= (profile/episode-entries s')
               (profile/episode-entries (profile.opml/episodes-from-opml s (opml/parse (opml/emit out)) id-fn t1)))
            "as another device reads them")))))

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

(deftest a-show-of-local-files-in-opml
  (let [g    "e6fbfb24-a72d-5894-9453-adfa5dae5740"
        s    (-> (profile/make "phone")
                 (profile/subscribe g {:url nil :title "Dune" :guid g} t0)
                 (profile/configure g {:folder "Books"} t0))
        list (profile.opml/to-opml s)
        back (profile.opml/from-opml (profile/make "laptop") (opml/parse (opml/emit list)) t1)]
    (is (= [] (:subscriptions list)))
    (is (= [{:text "Dune" :title "Dune" :guid g :path ["Books"] :created t0}] (:comments list))
        "a comment, which names the show for the listener and for other devices")
    (is (= {:url nil :title "Dune" :guid g} (select-keys (get (profile/subscriptions back) g) [:url :title :guid]))
        "a subscription without a URL, for a device with the same files to fill")
    (is (= {"Books" [g]} (profile/folders back)))
    (is (= (:subscriptions s) (:subscriptions (profile.opml/from-opml s (opml/parse (opml/emit list)) t1)))
        "nothing changes for a profile that has it")
    (is (empty? (profile/subscriptions (profile.opml/from-opml (profile/make "x") {:comments [{:text "Off" :xml-url "https://off/rss" :guid g}]} t1)))
        "a feed that was commented out stays out")))
