(ns dk.simongray.podcast.downloads-test
  "The plan of the downloads and the deletes of a device, by the rules of
  the listener."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.downloads :as downloads]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.queue :as profile.queue]))

(def day (* 24 60 60))

(defn- at
  "The instant `days` days after 1 September 2026."
  [days]
  #?(:clj  (java.util.Date. (long (+ (inst-ms #inst "2026-09-01T00:00:00Z") (* 1000 day days))))
     :cljs (js/Date. (+ (inst-ms #inst "2026-09-01T00:00:00Z") (* 1000 day days)))))

(defn- item
  "An episode `guid` that came out on the day `days`, with a file of
  `length` bytes."
  [guid title days length]
  {:rss/title       title
   :rss/guid        {:content guid}
   :rss/pub-date    (at days)
   :itunes/duration 1800
   :rss/enclosure   {:url (str "https://example.org/" guid ".mp3") :type "audio/mpeg" :length length}})

;; as a library keeps them, under keys of their own, since their guids
;; aren't UUIDs
(def show
  (guid/with-key {:podcast/guid "feed-a"
                  :rss/title    "A show"
                  :rss/items    [(item "a4" "Episode 4" 22 400)
                                 (item "a3" "Episode 3 (rerun)" 15 300)
                                 (item "a2" "Episode 2" 8 200)
                                 (item "a1" "Episode 1" 1 100)]}
                 "feed-a"))

(def other
  (guid/with-key {:podcast/guid "feed-b"
                  :rss/title    "Another show"
                  :rss/items    [(item "b2" "Second" 20 2000)
                                 (item "b1" "First" 10 1000)]}
                 "feed-b"))

(def now (at 28))

(def subscribed
  (-> (profile/make "phone")
      (profile/subscribe "feed-a" {:url "https://example.org/a.xml" :title "A show"} (at 0))
      (profile/subscribe "feed-b" {:url "https://example.org/b.xml" :title "Another show"} (at 0))))

(defn- with-rules
  "`state` with the rules `listener` of the listener, and the rule of each
  show of `shows`, a map by feed guid."
  [state listener shows]
  (reduce (fn [s [feed-guid rule]] (profile/configure s feed-guid {:downloads rule} (at 0)))
          (profile/prefer state {:downloads listener} (at 0))
          shows))

(defn- ids
  "The ids of the entries `entries` of a plan."
  [entries]
  (mapv :id entries))

(defn- plan
  ([state files]
   (plan state files {}))
  ([state files opts]
   (downloads/plan state [show other] files now opts)))

(deftest the-rules-of-a-listener
  (is (= downloads/default-rules (downloads/rules subscribed)))
  (is (= downloads/default-rule (downloads/rule (downloads/rules subscribed) "feed-a")))
  (let [state (with-rules subscribed
                          {:default {:played day} :max-episodes 50}
                          {"feed-a" {:download? true :episodes {:limit 2}}})
        rules (downloads/rules state)]
    (is (= (assoc downloads/default-rules
                  :default {:played day}
                  :max-episodes 50
                  :shows {"feed-a" {:download? true :episodes {:limit 2}}})
           rules))
    (is (= (assoc downloads/default-rule :download? true :played day :episodes {:order :newest :limit 2})
           (downloads/rule rules "feed-a"))
        "the rule of the show, over that of the listener, over the default")
    (is (= (assoc downloads/default-rule :played day) (downloads/rule rules "feed-b")))
    (is (nil? (downloads/problems rules)))
    (is (= rules (downloads/rules (edn/read-string (pr-str state)))) "the rules are data of the state")
    (testing "the rules of two devices merge as the settings do"
      (let [laptop (-> (profile/merge (profile/make "laptop") state)
                       (profile/configure "feed-b" {:downloads {:download? true}} (at 1)))]
        (is (= {"feed-a" {:download? true :episodes {:limit 2}} "feed-b" {:download? true}}
               (:shows (downloads/rules (profile/merge state laptop)))))))
    (is (= {} (:shows (downloads/rules (profile/unsubscribe (profile/unsubscribe state "feed-a" (at 2)) "feed-b" (at 2)))))
        "a show that the listener left has no rule")))

(deftest nothing-without-a-rule
  (is (= {:download [] :delete [] :waiting []} (plan subscribed {})))
  (is (= {:download [] :delete [] :waiting []}
         (plan (profile/mark-played subscribed ["feed-a" "a1"] (at 2)) {["feed-a" "a1"] {:bytes 100 :at (at 1)}}))))

(deftest the-episodes-that-a-rule-selects
  (testing "the newest episodes of a show"
    (let [state (with-rules subscribed {} {"feed-a" {:download? true :episodes {:limit 2}}})]
      (is (= [{:id ["feed-a" "a4"] :url "https://example.org/a4.mp3" :type "audio/mpeg" :bytes 400 :reason :rule}
              {:id ["feed-a" "a3"] :url "https://example.org/a3.mp3" :type "audio/mpeg" :bytes 300 :reason :rule}]
             (:download (plan state {}))))
      (is (= [["feed-a" "a3"]] (ids (:download (plan state {["feed-a" "a4"] {:bytes 400 :at (at 23)}}))))
          "the device has the newest")
      (is (= [["feed-a" "a3"]] (ids (:download (plan (profile/mark-played state ["feed-a" "a4"] (at 23)) {}))))
          "a played episode is one of the two, and no download")
      (is (= [["feed-a" "a3"]] (ids (:download (plan state {["feed-a" "a4"] {:removed? true}}))))
          "a file that went does not come back")))
  (testing "each episode, the newest first across the shows"
    (let [state (with-rules subscribed {:default {:download? true}} {})]
      (is (= [["feed-a" "a4"] ["feed-b" "b2"] ["feed-a" "a3"] ["feed-b" "b1"] ["feed-a" "a2"] ["feed-a" "a1"]]
             (ids (:download (plan state {})))))))
  (testing "words to exclude, a minimum duration and an age"
    (let [rule  (fn [where] {"feed-a" {:download? true :episodes {:where where}}})
          state #(with-rules subscribed {} (rule %))]
      (is (= [["feed-a" "a4"] ["feed-a" "a2"] ["feed-a" "a1"]]
             (ids (:download (plan (state {:not {:text #{"rerun" "repeat"}}}) {})))))
      (is (= [["feed-a" "a3"]] (ids (:download (plan (state {:text #{"rerun"}}) {})))))
      (is (= [] (ids (:download (plan (state {:duration [3600 nil]}) {})))))
      (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (ids (:download (plan (state {:age [nil (* 14 day)]}) {})))))))
  (testing "the episodes that came out after the listener subscribed"
    (let [late (-> (profile/make "phone")
                   (profile/subscribe "feed-a" {:url "https://example.org/a.xml" :title "A show"} (at 10))
                   (with-rules {} {"feed-a" {:download? true :episodes {:where {:since-subscription? true}}}}))]
      (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (ids (:download (plan late {})))))))
  (testing "the inbox, and an episode that the listener dismissed"
    (let [state (-> (with-rules subscribed {} {"feed-a" {:download? true :episodes {:where {:inbox? true}}}})
                    (crdt/write [:inbox ["feed-a" "a4"]] (constantly {:removed? false}) (at 22))
                    (crdt/write [:inbox ["feed-a" "a3"]] (constantly {:removed? false}) (at 15)))]
      (is (= [["feed-a" "a4"] ["feed-a" "a3"]] (ids (:download (plan state {})))))
      (is (= [["feed-a" "a3"]] (ids (:download (plan (profile.queue/dismiss state ["feed-a" "a4"] (at 23)) {}))))))
    (let [state (-> (with-rules subscribed {} {"feed-a" {:download? true :episodes {:limit 1}}})
                    (crdt/write [:inbox ["feed-a" "a4"]] (constantly {:removed? false}) (at 22))
                    (profile.queue/dismiss ["feed-a" "a4"] (at 23)))]
      (is (= [] (ids (:download (plan state {})))) "the listener did not want the newest")
      (is (= [["feed-a" "a4"]] (ids (:download (plan (profile.queue/enqueue state ["feed-a" "a4"] (at 24)) {}))))
          "and then put it in the queue")))
  (testing "a show in the listening order, for a show that a listener hears from the start"
    (let [serial (assoc show :itunes/type "serial")
          state  (-> (with-rules subscribed {}
                                 {"feed-a" {:download? true
                                            :episodes  {:where {:status #{:unplayed :in-progress}}
                                                        :order :listening
                                                        :limit 2}}})
                     (profile/mark-played ["feed-a" "a1"] (at 2)))]
      (is (= #{["feed-a" "a2"] ["feed-a" "a3"]}
             (set (ids (:download (downloads/plan state [serial] {} now)))))))))

(deftest the-front-of-the-queue
  (let [state (-> (with-rules subscribed {:queue 2} {})
                  (profile.queue/enqueue ["feed-b" "b1"] (at 11))
                  (profile.queue/enqueue ["feed-a" "a1"] (at 12))
                  (profile.queue/enqueue ["feed-a" "a2"] (at 13)))]
    (is (= [{:id ["feed-b" "b1"] :url "https://example.org/b1.mp3" :type "audio/mpeg" :bytes 1000 :reason :queue}
            {:id ["feed-a" "a1"] :url "https://example.org/a1.mp3" :type "audio/mpeg" :bytes 100 :reason :queue}]
           (:download (plan state {}))))
    (is (= [["feed-b" "b1"] ["feed-a" "a1"] ["feed-a" "a2"]]
           (ids (:download (plan state {} {:rules (assoc (downloads/rules state) :queue true)})))))
    (is (= [["feed-a" "a1"]] (ids (:download (plan state {["feed-b" "b1"] {:bytes 1000 :at (at 12)}})))))
    (testing "the queue comes before the rules of the shows, and an episode is there once"
      (let [both (assoc (downloads/rules state) :shows {"feed-a" {:download? true :episodes {:limit 1}}
                                                        "feed-b" {:download? true}})]
        (is (= [[["feed-b" "b1"] :queue] [["feed-a" "a1"] :queue] [["feed-a" "a4"] :rule] [["feed-b" "b2"] :rule]]
               (map (juxt :id :reason) (:download (plan state {} {:rules both})))))))
    (is (= [["feed-a" "a1"]]
           (ids (:download (downloads/plan state [show] {} now))))
        "an episode whose feed is not here has no file to download")))

(deftest the-files-that-go
  (let [files {["feed-a" "a1"] {:bytes 100 :at (at 2)}
               ["feed-a" "a2"] {:bytes 200 :at (at 9)}
               ["feed-a" "a4"] {:bytes 400 :at (at 23)}}
        heard (-> subscribed
                  (profile/mark-played ["feed-a" "a1"] (at 20))
                  (profile/progress ["feed-a" "a2"] 600 1800 (at 27)))]
    (testing "a played episode, after a time"
      (is (= [{:id ["feed-a" "a1"] :reason :played :bytes 100}]
             (:delete (plan (with-rules heard {:default {:played (* 7 day)}} {}) files))))
      (is (= [] (:delete (plan (with-rules heard {:default {:played (* 9 day)}} {}) files))))
      (is (= [["feed-a" "a1"]] (ids (:delete (plan (with-rules heard {:default {:played 0}} {}) files)))))
      (is (= [] (:delete (plan (with-rules heard {:default {:played 0}} {"feed-a" {:played nil}}) files)))
          "the rule of the show keeps the files"))
    (testing "an episode that the listener does not play"
      (is (= [{:id ["feed-a" "a4"] :reason :inactive :bytes 400}]
             (:delete (plan (with-rules heard {:default {:inactive (* 5 day)}} {}) files)))
          "the time starts again at a report of a position, and a played episode is not inactive")
      (is (= [["feed-a" "a2"] ["feed-a" "a4"]]
             (ids (:delete (plan (with-rules heard {:default {:inactive day}} {}) files))))))
    (testing "a file that the rule selects no more"
      (let [state (with-rules heard {} {"feed-a" {:download? true :episodes {:limit 2} :trim? true}})]
        (is (= [{:id ["feed-a" "a1"] :reason :trimmed :bytes 100} {:id ["feed-a" "a2"] :reason :trimmed :bytes 200}]
               (:delete (plan state files))))
        (is (= [["feed-a" "a3"]] (ids (:download (plan state files)))))
        (is (= [] (:delete (downloads/plan state [other] files now)))
            "without the feed of the show, nothing tells what the rule selects")))
    (testing "the files that stay"
      (let [state (-> (with-rules heard {:default {:played 0 :inactive 0 :trim? true :episodes {:limit 0}}} {})
                      (profile.marks/star ["feed-a" "a1"] (at 21))
                      (profile.queue/enqueue ["feed-a" "a4"] (at 24)))
            rules (downloads/rules state)
            keep  #(assoc-in rules [:default :keep] %)]
        (is (= [["feed-a" "a2"]] (ids (:delete (plan state files)))) "an episode with a star, and one in the queue")
        (is (= [["feed-a" "a1"] ["feed-a" "a2"] ["feed-a" "a4"]]
               (ids (:delete (plan state files {:rules (keep #{})})))))
        (is (= [["feed-a" "a1"] ["feed-a" "a4"]]
               (ids (:delete (plan state files {:rules (keep #{:in-progress})})))))
        (is (= [["feed-a" "a2"] ["feed-a" "a4"]]
               (ids (:delete (plan (profile.queue/add-to-playlist state "Trip" ["feed-a" "a1"] (at 25))
                                   files
                                   {:rules (keep #{:playlists})})))))
        (is (= [["feed-a" "a4"]]
               (ids (:delete (plan state
                                   (-> files
                                       (assoc-in [["feed-a" "a1"] :keep?] true)
                                       (assoc-in [["feed-a" "a2"] :removed?] true))
                                   {:rules (keep #{})}))))
            "a file that the app keeps, and a file that went")))
    (testing "a file of a show that the listener left, or that is no show of the state"
      (is (= [["feed-a" "a1"] ["feed-c" "c1"]]
             (ids (:delete (plan (-> (with-rules heard {:default {:played 0}} {})
                                     (profile/unsubscribe "feed-a" (at 26))
                                     (profile/mark-played ["feed-c" "c1"] (at 26)))
                                 (assoc files ["feed-c" "c1"] {:bytes 10 :at (at 25)})))))))))

(deftest the-room-on-the-device
  (let [state (-> (with-rules subscribed {:default {:download? true}} {})
                  (profile/mark-played ["feed-a" "a1"] (at 5))
                  (profile/mark-played ["feed-a" "a2"] (at 12)))
        files {["feed-a" "a1"] {:bytes 100 :at (at 2)}
               ["feed-a" "a2"] {:bytes 200 :at (at 9)}}
        rules #(merge (downloads/rules state) %)
        parts (fn [opts] (update-vals (plan state files opts) #(mapv (juxt :id :reason :limit) %)))]
    (testing "a limit on the number of files"
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-b" "b2"] :rule nil]]
              :delete   []
              :waiting  [[["feed-a" "a3"] :rule :max-episodes] [["feed-b" "b1"] :rule :max-episodes]]}
             (parts {:rules (rules {:max-episodes 4})})))
      (is (= [] (:download (parts {:rules (rules {:max-episodes 2})}))))
      (is (= [] (:download (parts {:rules (rules {:max-episodes 0})})))))
    (testing "a limit on the bytes, and a smaller file that has room"
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-a" "a3"] :rule nil]]
              :delete   []
              :waiting  [[["feed-b" "b2"] :rule :max-bytes] [["feed-b" "b1"] :rule :max-bytes]]}
             (parts {:rules (rules {:max-bytes 1000})}))))
    (testing "a limit on the bytes of one file, which no room makes up for"
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-a" "a3"] :rule nil] [["feed-b" "b1"] :rule nil]]
              :delete   []
              :waiting  [[["feed-b" "b2"] :rule :max-file-bytes]]}
             (parts {:rules (rules {:max-file-bytes 1000 :make-room? true})}))))
    (testing "played files that go to make room, the earliest played first"
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-a" "a3"] :rule nil]]
              :delete   [[["feed-a" "a1"] :room nil] [["feed-a" "a2"] :room nil]]
              :waiting  [[["feed-b" "b2"] :rule :max-bytes] [["feed-b" "b1"] :rule :max-bytes]]}
             (parts {:rules (rules {:max-bytes 750 :make-room? true})}))
          "400 has room, and 300 more need 250")
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-b" "b2"] :rule nil] [["feed-a" "a3"] :rule nil]]
              :delete   [[["feed-a" "a1"] :room nil] [["feed-a" "a2"] :room nil]]
              :waiting  [[["feed-b" "b1"] :rule :max-episodes]]}
             (parts {:rules (rules {:max-episodes 3 :make-room? true})}))
          "one file for each download, until no played file is left")
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-a" "a3"] :rule nil]]
              :delete   []
              :waiting  [[["feed-b" "b2"] :rule :max-bytes] [["feed-b" "b1"] :rule :max-bytes]]}
             (parts {:rules (rules {:max-bytes 1500 :make-room? true})}))
          "no file goes for a download that has no room after that")
      (is (= [[["feed-a" "a2"] :room nil]]
             (:delete (update-vals (plan (profile.marks/star state ["feed-a" "a1"] (at 6)) files
                                         {:rules (rules {:max-episodes 3 :make-room? true})})
                                   #(mapv (juxt :id :reason :limit) %))))
          "a file that the rule keeps makes no room"))
    (testing "a file that goes by its rule makes room too"
      (is (= {:download [[["feed-a" "a4"] :rule nil] [["feed-b" "b2"] :rule nil]
                         [["feed-a" "a3"] :rule nil] [["feed-b" "b1"] :rule nil]]
              :delete   [[["feed-a" "a1"] :played nil] [["feed-a" "a2"] :played nil]]
              :waiting  []}
             (parts {:rules (rules {:max-episodes 4 :default {:download? true :played 0}})}))))))

(deftest room-after-many-downloads
  (let [many  (guid/with-key {:podcast/guid "feed-a"
                              :rss/items    (mapv #(item (str "m" %) (str "Episode " %) 1 100) (range 30000))}
                             "feed-a")
        state (-> (with-rules subscribed {:default {:download? true} :max-bytes 2000100 :make-room? true} {})
                  (profile/mark-played ["feed-a" "old"] (at 5)))
        files {["feed-a" "old"] {:bytes 100 :at (at 2)}}
        plan  (downloads/plan state [many] files now)]
    (is (= 20001 (count (:download plan))) "20,000 files have room, and one more after the played file goes")
    (is (= [[["feed-a" "old"] :room]] (mapv (juxt :id :reason) (:delete plan))))
    (is (= 9999 (count (:waiting plan))))
    (is (= #{:max-bytes} (set (map :limit (:waiting plan)))))))

(deftest the-bytes-of-a-download
  (let [state   (with-rules subscribed {} {"feed-a" {:download? true :episodes {:limit 1}}})
        bare    (update-in show [:rss/items 0 :rss/enclosure] dissoc :length)
        entry   (fn [feed opts] (first (:download (downloads/plan state [feed] {} now opts))))
        formats (assoc-in show [:rss/items 0 :podcast/alternate-enclosures]
                          [{:type "audio/opus" :length 90 :bitrate 32000 :sources [{:uri "https://example.org/a4.opus"}]}])]
    (is (= 400 (:bytes (entry show {}))))
    (is (= (* 1800 (:audio downloads/bytes-per-second)) (:bytes (entry bare {}))) "the duration at 128 kbit/s")
    (is (= (* 1800 (:video downloads/bytes-per-second))
           (:bytes (entry (assoc-in bare [:rss/items 0 :rss/enclosure :type] "video/mp4") {})))
        "a video at 1 Mbit/s")
    (is (= (* 1800 4000)
           (:bytes (entry (update-in formats [:rss/items 0 :podcast/alternate-enclosures 0] dissoc :length)
                          {:enclosure {:types ["audio/opus"]}})))
        "at the bitrate of the file")
    (is (= 0 (:bytes (entry (update-in bare [:rss/items 0] dissoc :itunes/duration) {}))))
    (is (= 7 (:bytes (entry bare {:estimate (constantly 7)}))))
    (is (nil? (entry (assoc-in show [:rss/items 0 :rss/enclosure] {:url "https://example.org/a4.m3u8" :type "application/x-mpegURL"}) {}))
        "a stream is no download")
    (is (= {:id ["feed-a" "a4"] :url "https://example.org/a4.opus" :type "audio/opus" :bytes 90 :reason :rule}
           (entry formats {:enclosure {:types ["audio/opus"]}}))
        "the file that the app selects")
    (is (nil? (entry (update-in show [:rss/items 0] dissoc :rss/enclosure) {:rules {:shows {"feed-a" {:download? true :episodes {:limit 1}}}}}))
        "an item without a media file is no download")))

(deftest audio-or-video-as-the-listener-watches
  (let [video    (assoc-in show [:rss/items 0 :podcast/alternate-enclosures]
                           [{:type "video/mp4" :length 900 :sources [{:uri "https://example.org/a4.mp4"}]}])
        rule     {"feed-a" {:download? true :episodes {:limit 1}}}
        url      (fn [state opts] (:url (first (:download (downloads/plan state [video] {} now opts)))))
        watching (profile/prefer subscribed {:video? true} (at 0))]
    (is (= "https://example.org/a4.mp3" (url (with-rules subscribed {} rule) {})) "audio unless the listener watches")
    (is (= "https://example.org/a4.mp4" (url (with-rules watching {} rule) {})))
    (is (= "https://example.org/a4.mp3"
           (url (with-rules (profile/configure watching "feed-a" {:video? false} (at 0)) {} rule) {}))
        "the show's own choice")
    (is (= "https://example.org/a4.mp3" (url (with-rules watching {} rule) {:enclosure {:video? false}}))
        "the app's choice, e.g. on a mobile network")))

(deftest a-feed-without-a-guid
  (let [bare  (with-meta (dissoc show :podcast/guid) nil)
        url   "https://example.org/a.xml"
        guid  (first (guid/episode-id bare url (first (:rss/items bare))))
        rules {:shows {guid {:download? true :episodes {:limit 1}}}}]
    (is (= [[guid "a4"]] (ids (:download (downloads/plan subscribed [bare] {} now {:rules rules :url url})))))
    (is (= [[guid "a4"]]
           (ids (:download (downloads/plan subscribed [bare] {} now {:rules rules :url (constantly url)})))))
    (is (= [[guid "a4"]]
           (ids (:download (downloads/plan subscribed
                                           [(assoc bare :atom/links [{:rel "self" :href url}])]
                                           {}
                                           now
                                           {:rules rules}))))
        "the self link of the feed is its URL by default")
    (is (= [] (:download (downloads/plan subscribed [bare] {} now {:rules {:default {:download? true}}})))
        "a feed without an identity has no plan")
    (is (= [["feed-a" "a4"]] (ids (:download (downloads/plan subscribed show {} now {:rules (assoc-in rules [:shows "feed-a"] {:download? true :episodes {:limit 1}})}))))
        "one feed in the place of a collection")))

(deftest rules-of-another-version
  (testing "what `plan` takes is no problem"
    (doseq [rules [{} downloads/default-rules
                   {:queue true :max-bytes 0 :default {:keep #{}} :shows {"feed-a" {:download? true}}}
                   {:default {:episodes {:where {:age [nil 604800] :not {:text #{"rerun"}}} :order :oldest :limit 3}
                              :played 0 :inactive 1.5 :trim? true :keep downloads/keeps}}]]
      (is (nil? (downloads/problems rules)) (pr-str rules))))
  (testing "a problem is at its place"
    (doseq [[rules place found]
            [[[:queue] :rules #"the rules are \[:queue\], not a map"]
             [{:wifi? true} :wifi? #"the key :wifi\? is not known"]
             [{:queue "all"} :queue #"the value of :queue is \"all\""]
             [{:max-bytes -1} :max-bytes #"the value of :max-bytes is -1"]
             [{:max-episodes :few} :max-episodes #"the value of :max-episodes is :few"]
             [{:make-room? 1} :make-room? #"the value of :make-room\? is 1"]
             [{:shows [1]} :shows #"the value of :shows is \[1\]"]
             [{:default "none"} :default #"the value of :default is \"none\""]
             [{:default {:played "soon"}} :default #"the value of :played is \"soon\", not a number of seconds"]
             [{:shows {"feed-a" {:inactive -5}}} "feed-a" #"the value of :inactive is -5, not a number of seconds"]
             [{:shows {"feed-a" {:keep [:starred]}}} "feed-a" #"the value of :keep is \[:starred\], not a set of"]
             [{:shows {"feed-a" {:keep #{:loved}}}} "feed-a" #"the value of :keep is #\{:loved\}"]
             [{:shows {"feed-a" {:episodes {:where {:mood "calm"}}}}} "feed-a" #"the criteria :mood are not known"]
             [{:shows {"feed-a" {:episodes [:limit 3]}}} "feed-a" #"the value of :episodes is \[:limit 3\], not a map"]
             [{:shows {"feed-a" {:wifi? true}}} "feed-a" #"the keys :wifi\? are not known"]
             [{:shows {"feed-a" :all}} "feed-a" #"the rule is :all, not a map"]]]
      (is (= [place] (keys (downloads/problems rules))) (pr-str rules))
      (is (re-find found (str (get (downloads/problems rules) place))) (pr-str rules))))
  (testing "a show whose rule has a problem has no plan, and the other shows have theirs"
    (let [state (profile/mark-played subscribed ["feed-a" "a1"] (at 5))
          files {["feed-a" "a1"] {:bytes 100 :at (at 2)}}
          rules {:default {:download? true :played 0 :episodes {:limit 1}}
                 :shows   {"feed-a" {:later true}}}]
      (is (= {:download [["feed-b" "b2"]]
              :delete   []
              :waiting  []
              :problems {"feed-a" "the keys :later are not known"}}
             (update (plan state files {:rules rules}) :download ids)))
      (is (= [["feed-a" "a1"]] (ids (:delete (plan state files {:rules (assoc rules :shows {})})))))))
  (testing "with a problem in another place, the plan is empty"
    (let [state (with-rules subscribed {:default {:download? true} :later 5} {})]
      (is (= {:download [] :delete [] :waiting [] :problems {:later "the key :later is not known"}}
             (plan state {})))
      (is (= {:download [] :delete [] :waiting [] :problems {:rules "the rules are \"all\", not a map"}}
             (plan (profile/prefer subscribed {:downloads "all"} (at 0)) {}))))))

(deftest the-same-plan-for-the-same-input
  (let [state (-> (with-rules subscribed {:default {:download? true :played 0} :queue 1 :max-episodes 5} {})
                  (profile.queue/enqueue ["feed-b" "b1"] (at 11))
                  (profile/mark-played ["feed-a" "a1"] (at 5)))
        files {["feed-a" "a1"] {:bytes 100 :at (at 2)}}]
    (is (= (plan state files)
           (downloads/plan state [other show] files now)
           (downloads/plan (edn/read-string (pr-str state)) (list show other) (into (sorted-map) files) now))
        "the order of the feeds does not count")
    (is (= [["feed-b" "b1"] ["feed-a" "a4"] ["feed-b" "b2"] ["feed-a" "a3"] ["feed-a" "a2"]]
           (ids (:download (plan state files)))))))
