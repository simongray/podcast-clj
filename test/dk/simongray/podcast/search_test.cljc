(ns dk.simongray.podcast.search-test
  "Filtering and ordering by criteria as data, and the search documents of
  episodes and shows."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.search :as search]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.search :as full-text]))

(def example
  (delay (rss/parse (fixture "feeds/namespace-example.xml"))))

(def t0 #inst "2026-03-01T00:00:00Z")
(def t1 #inst "2026-03-02T00:00:00Z")

(def show
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

(def listened
  "A profile with one episode started and another finished."
  (-> (profile/make "phone")
      (profile/progress ["feed-guid" "s1e1"] 600 1800 t1)
      (profile/mark-played ["feed-guid" "s1e2"] t0)))

(defn- titles
  [opts]
  (mapv :rss/title (search/episodes show opts)))

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
  (is (= ["Season one, episode two"] (titles {:where {:person "norgaard"}})) "names are folded")
  (is (= ["Trailer" "Season one, episode one: Café" "Season two, episode one" "Bonus"]
         (titles {:where {:person "host"}}))
      "the feed's people, where the item names none")
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:text "coff"}}))
      "words in the title or the notes, the last as a prefix")
  (is (= ["Season one, episode one: Café"] (titles {:where {:text "cafe"}})))
  (is (= ["Season one, episode one: Café"] (titles {:where {:status :in-progress} :profile listened})))
  (is (= ["Trailer" "Season two, episode one" "Bonus"] (titles {:where {:status :unplayed} :profile listened})))
  (is (= ["Season one, episode two"] (titles {:where {:status :played} :profile listened})))
  (is (= ["Season one, episode one: Café" "Season one, episode two"]
         (titles {:where {:status #{:played :in-progress} :season 1} :profile listened})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/where {:colour "red"})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/where {:status :played}))
      ":status without a profile is a mistake, not an empty list")
  (testing "the stars and the inbox of the profile, and a saved filter"
    (let [marked (-> listened
                     (profile/star ["feed-guid" "b"] t1)
                     (crdt/write [:inbox ["feed-guid" "t"]] (constantly {:removed? false}) t1)
                     (profile/save-filter "Starred" {:where {:starred? true}} t1))]
      (is (= ["Bonus"] (titles {:where {:starred? true} :profile marked})))
      (is (= ["Trailer"] (titles {:where {:inbox? true} :profile marked})))
      (is (= ["Bonus"] (titles (assoc (get (profile/filters marked) "Starred") :profile marked))))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:inbox\? needs the profile"
                            (search/where {:inbox? true})))))
  (testing "the ratings of the listener, as a range"
    (let [rated (-> listened
                    (profile/rate-episode ["feed-guid" "b"] 5 t1)
                    (profile/rate-episode ["feed-guid" "t"] 2 t1))]
      (is (= ["Bonus"] (titles {:where {:rating [4 nil]} :profile rated})))
      (is (= ["Trailer"] (titles {:where {:rating [nil 3]} :profile rated})))
      (is (= ["Trailer" "Bonus"] (titles {:where {:rating [1 5]} :profile rated}))
          "an episode with no rating is in no range")
      (is (= [] (titles {:where  {:rating [4 nil]}
                         :profile (profile/unrate-episode rated ["feed-guid" "b"] #inst "2026-03-03T00:00:00Z")})))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:rating needs the profile"
                            (search/where {:rating [4 nil]})))))
  (testing "ratings in the scale of the app, and a saved filter that keeps its scale"
    (let [hundred {:worst 1 :best 100}
          rated   (-> listened
                      (profile/rate-episode ["feed-guid" "b"] 90 t1 {:scale hundred})
                      (profile/rate-episode ["feed-guid" "t"] 4 t1)
                      (profile/save-filter "Best" {:where {:rating [80 nil]} :scale hundred} t1))]
      (is (= ["Bonus"] (titles {:where {:rating [80 nil]} :scale hundred :profile rated}))
          "a 4 of 1 to 5 is 75.25 of 1 to 100")
      (is (= ["Trailer" "Bonus"] (titles {:where {:rating [4 nil]} :scale profile/rating-scale :profile rated})))
      (is (= ["Trailer" "Bonus"] (titles {:where {:rating [4 nil]} :profile rated}))
          "without a scale, each rating is the number that the state has")
      (is (= ["Bonus"] (titles (assoc (get (profile/filters rated) "Best") :profile rated))))
      (is (nil? (search/problem (get (profile/filters rated) "Best"))))))
  (testing "one of several texts, and criteria that an item does not meet"
    (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two" "Bonus"]
           (titles {:where {:text #{"coffee" "trailer" "bonus"}}})))
    (is (= ["Season two, episode one"] (titles {:where {:not {:text #{"coffee" "trailer" "bonus"}}}})))
    (is (= ["Season one, episode one: Café"] (titles {:where {:season 1 :not {:duration [3000 nil]}}})))
    (is (= ["Trailer" "Season two, episode one" "Bonus"]
           (titles {:where {:not {:status #{:played :in-progress}}} :profile listened})))
    (is (= (titles {:where {:season 1}}) (titles {:where {:not {:not {:season 1}}}})))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:status needs the profile"
                          (search/where {:not {:status :played}})))
    (is (thrown? #?(:clj Exception :cljs js/Error) (search/where {:not {:colour "red"}}))))
  (testing "the age of an episode, in seconds before now"
    (let [now  #inst "2026-02-08T00:00:00Z"
          week (* 7 24 60 60)]
      (is (= ["Season two, episode one" "Bonus"] (titles {:where {:age [nil week]} :at now})))
      (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two"]
             (titles {:where {:age [(* 3 week) nil]} :at now})))
      (is (= (titles {}) (titles {:where {:age [0 week]} :at #inst "2025-01-01T00:00:00Z"}))
          "an episode with a later date has the age 0")
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:age needs the instant of now"
                            (search/where {:age [nil week]})))))
  (testing "the episodes that came out after the listener subscribed"
    (let [subscribed (profile/subscribe listened "feed-guid" {:url "https://x/feed" :title "Test Show"}
                                        #inst "2026-01-20T00:00:00Z")]
      (is (= ["Season two, episode one" "Bonus"] (titles {:where {:since-subscription? true} :profile subscribed})))
      (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two"]
             (titles {:where {:since-subscription? false} :profile subscribed})))
      (is (= [] (titles {:where {:since-subscription? true} :profile listened}))
          "without a subscription, no episode came out after it")
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"^:since-subscription\? needs the profile"
                            (search/where {:since-subscription? true}))))))

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
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/order :random)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/order :recent))))

(deftest a-limit-with-criteria
  (testing "the first items of the selection, in each order"
    (doseq [order (conj (vec search/orders) nil)
            where [{} {:type "full"} {:duration [nil 1800]} {:not {:text #{"coffee"}}} {:status :unplayed}]
            limit [0 1 2 10]
            :let  [opts (cond-> {:where where :profile listened} order (assoc :order order))]]
      (is (= (vec (take limit (titles opts))) (titles (assoc opts :limit limit)))
          (pr-str (dissoc opts :profile) limit)))
    (is (= (take 3 (search/episodes [show show] {:where {:type "full"}}))
           (search/episodes [show show] {:where {:type "full"} :limit 3}))
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
      (is (nil? (search/problem criteria)) (pr-str criteria))))
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
      (is (re-find found (str (search/problem criteria))) (pr-str criteria)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (titles {:where {:mood "calm"}})))))

(deftest across-feeds
  (let [other {:rss/title "Other" :podcast/guid "other"
               :rss/items [{:rss/title "Elsewhere" :rss/guid {:content "e"} :rss/pub-date #inst "2026-01-20T00:00:00Z"}]}
        pairs (search/episodes [show other] {:where {:type "full"} :order :newest})]
    (is (= ["Season two, episode one" "Elsewhere" "Season one, episode two" "Season one, episode one: Café"]
           (mapv (comp :rss/title second) pairs)))
    (is (= ["Test Show" "Other" "Test Show" "Test Show"] (mapv (comp :rss/title first) pairs)))
    (is (= 2 (count (search/episodes [show other] {:limit 2}))))
    (is (= ["Season one, episode one: Café"]
           (mapv (comp :rss/title second)
                 (search/episodes [show other] {:where {:status :in-progress} :profile listened
                                                :url (fn [feed] (str "https://x/" (:podcast/guid feed)))})))
        "the url as a function of the feed, for the identities the profile uses")
    (is (= ["Other" "Test Show"] (distinct (mapv (comp :rss/title first) (search/episodes [other show]))))
        "in the listening order, each show keeps its episodes together, in the order the feeds come")))

(deftest documents-from-the-model
  (let [feed (deref example)
        item (first (:rss/items feed))
        doc  (search/item-document feed item {:url "https://example.com/feed.xml"})]
    (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"] (:id doc)) "the identity the profile uses")
    (is (= "Episode 3 - The Future" (get-in doc [:fields :title])))
    (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :show])))
    (is (= "A look into the future of podcasting and how we get to Podcasting 2.0!" (get-in doc [:fields :description])))
    (is (= "Adam Curry Dave Jones Becky Smith" (get-in doc [:fields :people])))
    (is (= {:title "Episode 3 - The Future" :show "Podcasting 2.0 Namespace Example"
            :guid "https://example.com/ep0003" :url "https://example.com/feed.xml"
            :published #inst "2020-10-09T04:30:38Z"}
           (:stored doc)))
    (is (= "Rain Dawn" (get-in (search/item-document feed item {:chapters {:chapters [{:title "Rain"} {:title "Dawn"}]}})
                               [:fields :chapters]))
        "chapters handed in are indexed by title")
    (is (= "Al: Hello there" (get-in (search/item-document feed item {:transcript {:segments [{:speaker "Al" :body "Hello there"}]}})
                                     [:fields :transcript])))
    (let [idx (search/index (map #(search/item-document feed % {:url "https://example.com/feed.xml"}) (:rss/items feed)))]
      (is (= 3 (count (:docs idx))))
      (is (= [["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"]] (mapv :id (full-text/query idx "futu"))))
      (is (= [["y0ur-gu1d-g035-h3r3" "https://example.com/ep0002"]] (mapv :id (full-text/query idx "\"the present\""))))
      (is (= "Episode 3 - The Future" (:title (:stored (first (full-text/query idx "future")))))))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"needs :url"
                          (search/item-document (dissoc feed :podcast/guid) item))
        "a feed without an identity would give its episodes the ids of other shows")
    (let [doc (search/feed-document feed)]
      (is (= "y0ur-gu1d-g035-h3r3" (:id doc)))
      (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :title])))
      (is (= {:title "Podcasting 2.0 Namespace Example" :author "John Doe"} (:stored doc))))))

(deftest the-podcast-index
  (let [idx (search/index [{:id 1 :fields {:description "Clojure" :title "Other"}}
                           {:id 2 :fields {:title "Clojure" :description "Other"}}])]
    (is (= [2 1] (mapv :id (full-text/query idx "clojure"))) "a title weighs more than the notes")
    (is (= [1] (mapv :id (full-text/query idx "notes:clojure"))) "notes is a name of the description")
    (is (= idx (full-text/restore (edn/read-string (pr-str idx)))) "the defaults print with the index")))
