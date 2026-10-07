(ns dk.simongray.podcast.feed.search-test
  "The search documents of episodes and shows, and an index of them."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.criteria-test :refer [show]]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.search :as search]))

(def example
  (delay (rss/parse (fixture "feeds/namespace-example.xml"))))

(deftest documents-from-the-model
  (let [feed (deref example)
        item (first (:rss/items feed))
        doc  (feed.search/item-document feed item {:url "https://example.com/feed.xml"})]
    (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"] (:id doc)) "the identity the profile uses")
    (is (= "Episode 3 - The Future" (get-in doc [:fields :title])))
    (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :show])))
    (is (= "A look into the future of podcasting and how we get to Podcasting 2.0!" (get-in doc [:fields :description])))
    (is (= "Adam Curry Dave Jones Becky Smith" (get-in doc [:fields :people])))
    (is (= {:title "Episode 3 - The Future" :show "Podcasting 2.0 Namespace Example"
            :guid "https://example.com/ep0003" :url "https://example.com/feed.xml"
            :published #inst "2020-10-09T04:30:38Z"}
           (:stored doc)))
    (is (= "Rain Dawn" (get-in (feed.search/item-document feed item {:chapters {:chapters [{:title "Rain"} {:title "Dawn"}]}})
                               [:fields :chapters]))
        "chapters handed in are indexed by title")
    (is (= "Al: Hello there" (get-in (feed.search/item-document feed item {:transcript {:segments [{:speaker "Al" :body "Hello there"}]}})
                                     [:fields :transcript])))
    (let [idx (feed.search/index (map #(feed.search/item-document feed % {:url "https://example.com/feed.xml"}) (:rss/items feed)))]
      (is (= 3 (count (:docs idx))))
      (is (= [["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"]] (mapv :id (search/query idx "futu"))))
      (is (= [["y0ur-gu1d-g035-h3r3" "https://example.com/ep0002"]] (mapv :id (search/query idx "\"the present\""))))
      (is (= "Episode 3 - The Future" (:title (:stored (first (search/query idx "future")))))))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"needs :url"
                          (feed.search/item-document (dissoc feed :podcast/guid) item))
        "a feed without an identity would give its episodes the ids of other shows")
    (let [doc (feed.search/feed-document feed)]
      (is (= "y0ur-gu1d-g035-h3r3" (:id doc)))
      (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :title])))
      (is (= {:title "Podcasting 2.0 Namespace Example" :author "John Doe"} (:stored doc))))))

(deftest the-podcast-index
  (let [idx (feed.search/index [{:id 1 :fields {:description "Clojure" :title "Other"}}
                                {:id 2 :fields {:title "Clojure" :description "Other"}}])]
    (is (= [2 1] (mapv :id (search/query idx "clojure"))) "a title weighs more than the notes")
    (is (= [1] (mapv :id (search/query idx "notes:clojure"))) "notes is a name of the description")
    (is (= idx (search/restore (edn/read-string (pr-str idx)))) "the defaults print with the index")))

(deftest the-shows-of-an-index
  (let [url   "https://example.com/show.xml"
        other (assoc show :podcast/guid "other-guid" :rss/title "Other Show")
        idx   (-> (feed.search/index)
                  (feed.search/with-feed show url)
                  (feed.search/with-feed other "https://example.com/other.xml"))
        ids   #(set (map :id (search/query % "coffee")))]
    (is (= (inc (count (:rss/items show))) (count (feed.search/feed-documents show url)))
        "the show and each of its episodes")
    (is (= #{"feed-guid" "other-guid"} (feed.search/feed-guids idx)))
    (is (= #{["feed-guid" "s1e1"] ["feed-guid" "s1e2"] ["other-guid" "s1e1"] ["other-guid" "s1e2"]}
           (ids idx)))
    (testing "a show indexed again replaces its documents"
      (let [renamed (assoc-in show [:rss/items 1 :rss/description] "<p>Notes about tea.</p>")]
        (is (= #{["feed-guid" "s1e2"] ["other-guid" "s1e1"] ["other-guid" "s1e2"]}
               (ids (feed.search/with-feed idx renamed url))))))
    (testing "a show taken out"
      (is (= #{"other-guid"} (feed.search/feed-guids (feed.search/without-feed idx "feed-guid")))))
    (testing "the results with their shows and episodes"
      (let [locate (feed.search/located {"feed-guid" show "other-guid" other})
            found  (map locate (search/query idx "coffee"))]
        (is (every? :feed found))
        (is (= #{"Season one, episode one: Café" "Season one, episode two"}
               (set (map (comp :rss/title :item) found))))
        (is (= "Other Show" (:rss/title (:feed (locate {:id "other-guid"})))) "a show's own result")))))
