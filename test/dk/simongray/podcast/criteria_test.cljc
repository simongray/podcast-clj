(ns dk.simongray.podcast.criteria-test
  "Selecting and ordering episodes by criteria as data."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def t0 #inst "2026-03-01T00:00:00Z")
(def t1 #inst "2026-03-02T00:00:00Z")

(def hand-built
  "A serial show with a trailer, two seasons and a bonus, hand-built."
  {:rss/title       "Test Show"
   :podcast/guid    "feed-guid"
   :itunes/type     "serial"
   :itunes/explicit true
   :podcast/people  [{:content "Host Person" :role "host"}]
   :rss/items
   [{:rss/title "Trailer" :rss/guid {:content "t"} :rss/pub-date #inst "2026-01-01T00:00:00Z"
     :itunes/episode-type "trailer" :itunes/duration 60}
    {:rss/title "Season one, episode one: Café" :rss/guid {:content "s1e1"}
     :rss/pub-date #inst "2026-01-08T00:00:00Z" :itunes/season 1 :itunes/episode 1 :itunes/duration 1800
     :rss/description "<p>Notes about coffee.</p>"
     :podcast/transcripts [{:url "https://x/t.vtt" :type "text/vtt"}]}
    {:rss/title "Season one, episode two" :rss/guid {:content "s1e2"}
     :rss/pub-date #inst "2026-01-15T00:00:00Z" :itunes/season 1 :itunes/episode 2 :itunes/duration 3600
     :itunes/explicit false
     :podcast/people [{:content "Guest Nørgaard"}]
     :rss/description "00:00 Intro\n10:00 Coffee\n20:00 Outro"}
    {:rss/title "Season two, episode one" :rss/guid {:content "s2e1"}
     :rss/pub-date #inst "2026-02-01T00:00:00Z" :itunes/season 2 :itunes/episode 1 :itunes/duration 900
     :podcast/chapters {:url "https://x/c.json" :type "application/json+chapters"}}
    {:rss/title "Bonus" :rss/guid {:content "b"} :rss/pub-date #inst "2026-02-05T00:00:00Z"
     :itunes/season 2 :itunes/episode-type "bonus"}]})

(def show
  "The show as a library keeps it, under the key feed-guid, since its own
  guid isn't a UUID."
  (guid/with-key hand-built "feed-guid"))

(def listened
  "A profile with one episode started and another finished."
  (-> (profile/make "phone")
      (profile/progress ["feed-guid" "s1e1"] 600 1800 t1)
      (profile/mark-played ["feed-guid" "s1e2"] t0)))

(defn- titles
  [opts]
  (mapv :rss/title (criteria/episodes show opts)))

(deftest criteria
  (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two" "Season two, episode one" "Bonus"]
         (titles {}))
      "the listening order of a serial show, by default")
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:season 1}})))
  (is (= ["Season one, episode one: Café" "Season one, episode two" "Season two, episode one"]
         (titles {:where {:type "full"}})))
  (is (= ["Trailer" "Bonus"] (titles {:where {:type #{"trailer" "bonus"}}})))
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:duration [1000 nil]}})))
  (is (= ["Trailer" "Season two, episode one"] (titles {:where {:duration [nil 900]}})) "no duration is no match")
  (is (= ["Season one, episode one: Café" "Season one, episode two" "Season two, episode one"]
         (titles {:where {:published [#inst "2026-01-08T00:00:00Z" #inst "2026-02-01T00:00:00Z"]}})))
  (is (= ["Season one, episode one: Café"] (titles {:where {:transcript? true}})))
  (is (= ["Season one, episode two" "Season two, episode one"] (titles {:where {:chapters? true}}))
      "timestamps in the notes count, and so does a linked file")
  (is (= ["Season one, episode two"] (titles {:where {:explicit? false}}))
      "the feed's flag is inherited unless the item says otherwise")
  (is (= [] (titles {:where {:explicit? false :show-explicit? false}}))
      "but the show's own flag holds for every episode")
  (is (= ["Season one, episode two"] (titles {:where {:person "norgaard"}})) "names are folded")
  (is (= ["Trailer" "Season one, episode one: Café" "Season two, episode one" "Bonus"]
         (titles {:where {:person "host"}}))
      "the feed's people, where the item names none")
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:text "coff"}}))
      "words in the title or the notes, the last as a prefix")
  (is (= ["Season one, episode one: Café"] (titles {:where {:text "cafe"}})))
  (is (= ["Season one, episode two"] (titles {:where {:text "coffee -notes"}}))
      "the text is a query")
  (is (= ["Trailer" "Season one, episode two"] (titles {:where {:text "intro | trailer"}})))
  (is (= ["Season one, episode one: Café"] (titles {:where {:status :in-progress} :profile listened})))
  (is (= ["Trailer" "Season two, episode one" "Bonus"] (titles {:where {:status :unplayed} :profile listened})))
  (is (= ["Season one, episode two"] (titles {:where {:status :played} :profile listened})))
  (is (= ["Season one, episode one: Café"] (titles {:where {:progress [60 nil]} :profile listened})))
  (is (= ["Trailer" "Season one, episode two" "Season two, episode one" "Bonus"]
         (titles {:where {:progress [nil 60]} :profile listened}))
      "an episode not started is 0 seconds into it")
  (is (= ["Season one, episode one: Café"] (titles {:where {:heard [t1 nil]} :profile listened}))
      "a mark of played isn't hearing it")
  (is (= [] (titles {:where {:heard [nil t0]} :profile listened})))
  (is (= ["Season one, episode one: Café" "Season one, episode two"]
         (titles {:where {:status #{:played :in-progress} :season 1} :profile listened})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (criteria/where {:colour "red"})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (criteria/where {:status :played}))
      ":status without a profile is a mistake, not an empty list")
  (testing "the stars and the inbox of the profile, and a saved filter"
    (let [marked (-> listened
                     (profile.marks/star ["feed-guid" "b"] t1)
                     (crdt/write [:inbox ["feed-guid" "t"]] (constantly {:removed? false}) t1)
                     (profile/save-filter "Starred" {:where {:starred? true}} t1))]
      (is (= ["Bonus"] (titles {:where {:starred? true} :profile marked})))
      (is (= ["Trailer"] (titles {:where {:inbox? true} :profile marked})))
      (is (= ["Bonus"] (titles (assoc (get (profile/filters marked) "Starred") :profile marked))))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:inbox\? needs the profile"
                            (criteria/where {:inbox? true})))))
  (testing "the ratings of the listener, as a range"
    (let [rated (-> listened
                    (profile.marks/rate-episode ["feed-guid" "b"] 5 t1)
                    (profile.marks/rate-episode ["feed-guid" "t"] 2 t1))]
      (is (= ["Bonus"] (titles {:where {:rating [4 nil]} :profile rated})))
      (is (= ["Trailer"] (titles {:where {:rating [nil 3]} :profile rated})))
      (is (= ["Trailer" "Bonus"] (titles {:where {:rating [1 5]} :profile rated}))
          "an episode with no rating is in no range")
      (is (= [] (titles {:where  {:rating [4 nil]}
                         :profile (profile.marks/unrate-episode rated ["feed-guid" "b"] #inst "2026-03-03T00:00:00Z")})))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:rating needs the profile"
                            (criteria/where {:rating [4 nil]})))))
  (testing "ratings in the scale of the app, and a saved filter that keeps its scale"
    (let [hundred {:worst 1 :best 100}
          rated   (-> listened
                      (profile.marks/rate-episode ["feed-guid" "b"] 90 t1 {:scale hundred})
                      (profile.marks/rate-episode ["feed-guid" "t"] 4 t1)
                      (profile/save-filter "Best" {:where {:rating [80 nil]} :scale hundred} t1))]
      (is (= ["Bonus"] (titles {:where {:rating [80 nil]} :scale hundred :profile rated}))
          "a 4 of 1 to 5 is 75.25 of 1 to 100")
      (is (= ["Trailer" "Bonus"] (titles {:where {:rating [4 nil]} :scale profile.marks/rating-scale :profile rated})))
      (is (= ["Trailer" "Bonus"] (titles {:where {:rating [4 nil]} :profile rated}))
          "without a scale, each rating is the number that the state has")
      (is (= ["Bonus"] (titles (assoc (get (profile/filters rated) "Best") :profile rated))))
      (is (nil? (criteria/problem (get (profile/filters rated) "Best"))))))
  (testing "one of several texts, and criteria that an item does not meet"
    (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two" "Bonus"]
           (titles {:where {:text #{"coffee" "trailer" "bonus"}}})))
    (is (= ["Season two, episode one"] (titles {:where {:not {:text #{"coffee" "trailer" "bonus"}}}})))
    (is (= ["Season one, episode one: Café"] (titles {:where {:season 1 :not {:duration [3000 nil]}}})))
    (is (= ["Trailer" "Season two, episode one" "Bonus"]
           (titles {:where {:not {:status #{:played :in-progress}}} :profile listened})))
    (is (= (titles {:where {:season 1}}) (titles {:where {:not {:not {:season 1}}}})))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:status needs the profile"
                          (criteria/where {:not {:status :played}})))
    (is (thrown? #?(:clj Exception :cljs js/Error) (criteria/where {:not {:colour "red"}}))))
  (testing "the age of an episode, in seconds before now"
    (let [now  #inst "2026-02-08T00:00:00Z"
          week (* 7 24 60 60)]
      (is (= ["Season two, episode one" "Bonus"] (titles {:where {:age [nil week]} :at now})))
      (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two"]
             (titles {:where {:age [(* 3 week) nil]} :at now})))
      (is (= (titles {}) (titles {:where {:age [0 week]} :at #inst "2025-01-01T00:00:00Z"}))
          "an episode with a later date has the age 0")
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:age needs the instant of now"
                            (criteria/where {:age [nil week]})))))
  (testing "the episodes that came out after the listener subscribed"
    (let [subscribed (profile/subscribe listened "feed-guid" {:url "https://x/feed" :title "Test Show"}
                                        #inst "2026-01-20T00:00:00Z")]
      (is (= ["Season two, episode one" "Bonus"] (titles {:where {:since-subscription? true} :profile subscribed})))
      (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two"]
             (titles {:where {:since-subscription? false} :profile subscribed})))
      (is (= [] (titles {:where {:since-subscription? true} :profile listened}))
          "without a subscription, no episode came out after it")
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:since-subscription\? needs the profile"
                            (criteria/where {:since-subscription? true}))))))

(def tagged
  "A serial music feed in en_US with three episodes, the first of which
  has a Danish file, a soundbite, a license, a location and AI content,
  and the second payments, comments and a chat."
  (rss/parse (fixture "feeds/tags.xml")))

(defn- tagged-titles
  [where]
  (mapv :rss/title (criteria/episodes tagged {:where where})))

(deftest criteria-of-the-tags
  (testing "the numbers of episodes"
    (is (= ["Two"] (tagged-titles {:episode 2})))
    (is (= ["One" "Three"] (tagged-titles {:episode #{1 3}}))))
  (testing "languages, as RFC 4647 filters them"
    (is (= ["One" "Two" "Three"] (tagged-titles {:language "en"})))
    (is (= ["One" "Two" "Three"] (tagged-titles {:language "EN-us"}))
        "the case doesn't count, nor an underscore for a hyphen")
    (is (= [] (tagged-titles {:language "en-gb"})))
    (is (= ["One"] (tagged-titles {:language "da"})) "a file in another language")
    (is (= [] (tagged-titles {:language "de"})))
    (is (= ["One" "Two" "Three"] (tagged-titles {:language #{"de" "*"}}))))
  (testing "categories, of Apple Podcasts and of RSS"
    (is (= ["One" "Two" "Three"] (tagged-titles {:category "tech news"})))
    (is (= ["One"] (tagged-titles {:category #{"Coffee" "Comedy"}})))
    (is (= [] (tagged-titles {:category "Comedy"}))))
  (testing "the show"
    (is (= ["One" "Two" "Three"] (tagged-titles {:medium "music"})))
    (is (= [] (tagged-titles {:medium #{"podcast" "audiobook"}})))
    (is (= ["One" "Two" "Three"] (tagged-titles {:serial? true :complete? true})))
    (is (= [] (tagged-titles {:serial? false})))
    (is (= ["One" "Two" "Three"]
           (tagged-titles {:publisher "003af0a0-6a45-55cf-b765-68e3d349551a"}))))
  (testing "people and places"
    (is (= ["One"] (tagged-titles {:author "bo"})))
    (is (= ["Two" "Three"] (tagged-titles {:author "ann"}))
        "the show's author for an episode without one")
    (is (= ["One"] (tagged-titles {:location "copenhagen"})))
    (is (= ["One"] (tagged-titles {:location "dk"})) "a country code"))
  (testing "what an episode has, its own or its show's"
    (is (= ["One"] (tagged-titles {:license "CC-BY-4.0"})))
    (is (= ["One"] (tagged-titles {:soundbites? true})))
    (is (= ["Two"] (tagged-titles {:value? true})))
    (is (= ["Two"] (tagged-titles {:comments? true})))
    (is (= ["Two"] (tagged-titles {:chat? true})))
    (is (= ["One" "Two" "Three"] (tagged-titles {:funding? true})))
    (is (= ["One"] (tagged-titles {:ai-content? true})))
    (is (= ["Two" "Three"] (tagged-titles {:ai-content? false}))
        "an episode that doesn't say counts as false")))

(deftest orders
  (is (= ["Bonus" "Season two, episode one" "Season one, episode two" "Season one, episode one: Café" "Trailer"]
         (titles {:order :newest})))
  (is (= (reverse (titles {:order :newest})) (titles {:order :oldest})))
  (is (= ["Trailer" "Season two, episode one" "Season one, episode one: Café" "Season one, episode two" "Bonus"]
         (titles {:order :shortest}))
      "the one without a duration last")
  (is (= ["Season one, episode two" "Season one, episode one: Café" "Season two, episode one" "Trailer" "Bonus"]
         (titles {:order :longest})))
  (is (= ["Bonus" "Season one, episode one: Café" "Season one, episode two" "Season two, episode one" "Trailer"]
         (titles {:order :title})))
  (is (= ["Season one, episode one: Café" "Season one, episode two" "Trailer" "Season two, episode one" "Bonus"]
         (titles {:order :recent :profile listened}))
      "last touched first, the untouched in feed order after")
  (is (= ["Bonus" "Trailer"] (titles {:order (fn [_ item] (count (:rss/title item))) :limit 2}))
      "a function is a key of its own; limit applies after ordering")
  (testing "the order that the listener chose for a show as its listening order"
    (let [chose #(-> (profile/make "phone")
                     (profile/subscribe "feed-guid" {:url "https://x/rss" :title "Test Show"} t0)
                     (profile/configure "feed-guid" {:order %} t0))]
      (is (= (titles {:order :newest}) (titles {:profile (chose :newest)})))
      (is (= (titles {}) (titles {:profile (chose nil)})))
      (is (= (titles {}) (titles {:profile (chose :random)})) "or of a later version, the show's own")))
  (is (thrown? #?(:clj Exception :cljs js/Error) (criteria/order :random)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (criteria/order :recent))))

(deftest hidden-episodes
  (let [subscribed (-> (profile/make "phone")
                       (profile/subscribe "feed-guid" {:url "https://x/rss" :title "Test Show"} t0))
        hidden     #(titles {:where {:hidden? true} :profile %})
        visible    #(titles {:where (criteria/visible nil) :profile %})]
    (is (= [] (hidden subscribed)))
    (is (= ["Trailer"] (hidden (profile/hide subscribed ["feed-guid" "t"] t1))) "as the listener hid it")
    (testing "by the :hide-where of the show, unless the listener unhid it"
      (let [rule (-> listened
                     (profile/subscribe "feed-guid" {:url "https://x/rss" :title "Test Show"} t0)
                     (profile/configure "feed-guid" {:hide-where {:status :played}} t0))]
        (is (= ["Season one, episode two"] (hidden rule)))
        (is (not (some #{"Season one, episode two"} (visible rule))))
        (is (= [] (hidden (profile/unhide rule ["feed-guid" "s1e2"] t1))))
        (is (= 5 (count (titles {:where {:hidden? #{true false}} :profile rule}))) "or either")
        (is (= [] (hidden (profile/configure rule "feed-guid" {:hide-where {:not {:hidden? true}}} t1)))
            "a rule that names :hidden? itself is left out")
        (is (= [] (hidden (profile/configure rule "feed-guid" {:hide-where {:mood :happy}} t1)))
            "and one of a later version")))
    (testing "the lists leave them out unless the criteria name :hidden?"
      (is (= {:status :played :hidden? false} (criteria/visible {:status :played})))
      (is (= {:not {:hidden? true}} (criteria/visible {:not {:hidden? true}}))))))

(deftest a-limit-with-criteria
  (testing "the first items of the selection, in each order"
    (doseq [order (conj (vec criteria/orders) nil)
            where [{} {:type "full"} {:duration [nil 1800]} {:not {:text #{"coffee"}}} {:status :unplayed}]
            limit [0 1 2 10]
            :let  [opts (cond-> {:where where :profile listened} order (assoc :order order))]]
      (is (= (vec (take limit (titles opts))) (titles (assoc opts :limit limit)))
          (pr-str (dissoc opts :profile) limit)))
    (is (= (take 3 (criteria/episodes [show show] {:where {:type "full"}}))
           (criteria/episodes [show show] {:where {:type "full"} :limit 3}))
        "across feeds"))
  (testing "the criteria read no more items than the limit needs"
    (let [read (atom 0)
          url  (fn [_] (swap! read inc) "https://x/feed.xml")]
      (is (= ["Bonus"] (titles {:where {:status :unplayed} :profile listened :url url :order :newest :limit 1})))
      (is (= 1 @read))))
  (testing "a key of the app gets only the items that the criteria select"
    (is (= ["Season two, episode one" "Trailer"]
           (titles {:where {:duration [nil 900]}
                    :order (fn [_ item] (- (:itunes/duration item)))
                    :limit 2}))
        "the item without a duration has no key")))

(deftest criteria-of-another-version
  (testing "what `episodes` takes is no problem"
    (doseq [criteria [{} {:where {:status :unplayed :season #{1 2}} :order :newest :limit 20} {:order :recent}
                      {:where {:age [nil 604800] :not {:text #{"rerun"} :not {:season 1}}}}]]
      (is (nil? (criteria/problem criteria)) (pr-str criteria))))
  (testing "what it throws for is one, so an app asks before it offers a saved filter"
    (doseq [[criteria found] [[{:where {:mood "calm" :status :played}} #"the criteria :mood are not known"]
                              [{:where {:season 1 :not {:mood "calm"}}} #"the criteria :mood are not known"]
                              [{:where {:not [:season]}} #":not are \[:season\], not a map"]
                              [{:where {:season 1} :order :random} #"the order :random is not known"]
                              [{:order :random} #"the order :random is not known"]
                              [{:where [:status]} #":where are \[:status\], not a map"]
                              [{:limit "many"} #"the limit is \"many\", not a number"]
                              [{:scale [1 5]} #"the scale is \[1 5\], not a scale of ratings"]
                              [[:where] #"the criteria are not a map"]]]
      (is (re-find found (str (criteria/problem criteria))) (pr-str criteria)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (titles {:where {:mood "calm"}})))))

(deftest across-feeds
  (let [other (guid/with-key {:rss/title "Other" :podcast/guid "other"
                              :rss/items [{:rss/title "Elsewhere" :rss/guid {:content "e"} :rss/pub-date #inst "2026-01-20T00:00:00Z"}]}
                             "other")
        pairs (criteria/episodes [show other] {:where {:type "full"} :order :newest})]
    (is (= ["Season two, episode one" "Elsewhere" "Season one, episode two" "Season one, episode one: Café"]
           (mapv (comp :rss/title second) pairs)))
    (is (= ["Test Show" "Other" "Test Show" "Test Show"] (mapv (comp :rss/title first) pairs)))
    (is (= 2 (count (criteria/episodes [show other] {:limit 2}))))
    (is (= ["Season one, episode one: Café"]
           (mapv (comp :rss/title second)
                 (criteria/episodes [show other] {:where {:status :in-progress} :profile listened
                                                  :url (fn [feed] (str "https://x/" (:podcast/guid feed)))})))
        "the url as a function of the feed, for the identities the profile uses")
    (is (= ["Other" "Test Show"] (distinct (mapv (comp :rss/title first) (criteria/episodes [other show]))))
        "in the listening order, each show keeps its episodes together, in the order the feeds come")))

(deftest criteria-of-the-show-the-queue-and-the-device
  (let [video  (assoc show :rss/items
                      [{:rss/title "Seen" :rss/guid {:content "v"}
                        :rss/enclosure {:url "https://x/v.mp4" :type "video/mp4"}}
                       {:rss/title "Heard" :rss/guid {:content "a"}
                        :rss/enclosure {:url "https://x/a.mp3" :type "audio/mpeg"}}])
        other  (guid/with-key {:rss/title "Other" :podcast/guid "other"
                               :rss/items [{:rss/title "Elsewhere" :rss/guid {:content "e"}}]}
                              "other")
        state  (-> listened
                   (profile/subscribe "feed-guid" {:url "https://x/feed" :title "Test Show"} t0)
                   (profile/subscribe "other" {:url "https://x/other" :title "Other"} t0)
                   (profile/configure "feed-guid" {:folder "News"} t0)
                   (profile/tag "other" "Daily" t0)
                   (profile.queue/enqueue ["other" "e"] t1))
        found  (fn [where & {:as opts}]
                 (mapv (comp :rss/title second)
                       (criteria/episodes [video other] (merge {:where where :profile state} opts))))]
    (is (= ["Elsewhere"] (found {:show "other"})))
    (is (= ["Seen" "Heard" "Elsewhere"] (found {:show #{"feed-guid" "other"}})))
    (is (= ["Seen" "Heard"] (found {:folder "News"})))
    (is (= ["Elsewhere"] (found {:folder nil})) "a show in no folder")
    (is (= ["Elsewhere"] (found {:tag #{"Daily" "Weekly"}})))
    (is (= ["Seen"] (found {:media :video})))
    (is (= ["Heard"] (found {:media :audio})))
    (is (= ["Elsewhere"] (found {:queued? true})))
    (testing "the state of the file on the device"
      (let [files {["feed-guid" "v"] {:at t0 :finished-at t0 :path "/v.mp4"}
                   ["feed-guid" "a"] {:at t0}
                   ["other" "e"]     {:at t0 :removed? true}}]
        (is (= ["Seen"] (found {:download :downloaded} :files files)))
        (is (= ["Heard"] (found {:download :downloading} :files files)))
        (is (= ["Elsewhere"] (found {:download :none} :files files)) "a file that went is none")
        (is (= ["Heard"] (found {:download :failed} :files (assoc-in files [["feed-guid" "a"] :failed-at] t1))))
        (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:download needs the files"
                              (criteria/where {:download :none})))))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:tag needs the profile"
                          (criteria/where {:tag "Daily"})))))
