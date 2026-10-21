(ns dk.simongray.podcast.feed.search-test
  "The search documents of episodes and shows, and an index of them."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.podcast.criteria-test :refer [show]]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.test-support :refer [example-guid fixture]]))

(def example
  (delay (assoc (rss/parse (fixture "feeds/namespace-example.xml")) :podcast/guid example-guid)))

(deftest documents-from-the-model
  (let [feed (deref example)
        item (first (:rss/items feed))
        doc  (feed.search/item-document feed item {:url "https://example.com/feed.xml"})]
    (is (= [example-guid "https://example.com/ep0003"] (:id doc)) "the identity the profile uses")
    (is (= "Episode 3 - The Future" (get-in doc [:fields :title])))
    (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :show])))
    (is (= "A look into the future of podcasting and how we get to Podcasting 2.0!" (get-in doc [:fields :description])))
    (is (= "Adam Curry Dave Jones Becky Smith" (get-in doc [:fields :people])))
    (is (= {:title "Episode 3 - The Future" :show "Podcasting 2.0 Namespace Example"
            :published #inst "2020-10-09T04:30:38Z"}
           (:stored doc))
        "what a list of results shows, and no more, since the id names the item")
    (is (= "Rain Dawn" (get-in (feed.search/item-document feed item {:chapters {:chapters [{:title "Rain"} {:title "Dawn"}]}})
                               [:fields :chapters]))
        "chapters handed in are indexed by title")
    (is (= "Al: Hello there" (get-in (feed.search/item-document feed item {:transcript {:segments [{:speaker "Al" :body "Hello there"}]}})
                                     [:fields :transcript])))
    (let [idx (feed.search/index (map #(feed.search/item-document feed % {:url "https://example.com/feed.xml"}) (:rss/items feed)))]
      (is (= 3 (count (:docs idx))))
      (is (= [[example-guid "https://example.com/ep0003"]] (mapv :id (search/query idx "futu"))))
      (is (= [[example-guid "https://example.com/ep0002"]] (mapv :id (search/query idx "\"the present\""))))
      (is (= "Episode 3 - The Future" (:title (:stored (first (search/query idx "future")))))))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"needs :url"
                          (feed.search/item-document (dissoc feed :podcast/guid) item))
        "a feed without an identity would give its episodes the ids of other shows")
    (let [doc (feed.search/feed-document feed)]
      (is (= example-guid (:id doc)))
      (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :title])))
      (is (= {:title "Podcasting 2.0 Namespace Example" :author "John Doe"} (:stored doc))))))

(defn show-of
  "A show whose episodes have the `notes`, one each."
  [notes]
  (rss/parse
   (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">"
        "<channel><title>Show</title><podcast:guid>" example-guid "</podcast:guid>"
        (apply str (map-indexed (fn [i text]
                                  (str "<item><title>Episode " i "</title><guid>" i
                                       "</guid><description>" text "</description></item>"))
                                notes))
        "</channel></rss>")))

(defn cut-notes
  "The notes of the episodes of `feed` without the text that it repeats."
  [feed]
  (let [repeated (feed.search/repeated-runs feed)]
    (mapv #(get-in (feed.search/item-document feed % {:repeated repeated})
                   [:fields :description])
          (:rss/items feed))))

(def sponsor
  "A sponsor read of thirteen words."
  "This episode is brought to you by Acme, the maker of fine anvils.")

(deftest a-show-s-repeated-text
  (let [feed (show-of [(str "Roasting coffee at home. " sponsor)
                       (str "Grinders compared. " sponsor
                            " Thanks to everyone who wrote in about grinders.")
                       (str sponsor " Brewing tea. Thanks to everyone who wrote in about grinders.")
                       (str "Espresso. " sponsor " Find us wherever you get your podcasts.")
                       "Nothing else. Find us wherever you get your podcasts."
                       "Decaf. Find us wherever you get your podcasts."])]
    (testing "the runs of eight words that three episodes or more share leave their notes"
      (is (= ["Roasting coffee at home.  ."
              "Grinders compared.  . Thanks to everyone who wrote in about grinders."
              " . Brewing tea. Thanks to everyone who wrote in about grinders."
              "Espresso.  . Find us wherever you get your podcasts."
              "Nothing else. Find us wherever you get your podcasts."
              "Decaf. Find us wherever you get your podcasts."]
             (cut-notes feed))
          "but not eight words that two share, nor seven that four share"))
    (testing "the same hashes on the JVM and in JavaScript, since a library saves them"
      (is (= #{-1962026436 -1913001699 -1450304407 -294801470 -113900375 555266410}
             (feed.search/repeated-runs feed))
          "the six runs of the sponsor read"))
    (testing "the documents of a show leave the same text out, and name it"
      (let [docs (feed.search/feed-documents feed "https://example.com/feed.xml")]
        (is (= (cut-notes feed) (map #(get-in % [:fields :description]) (rest docs))))
        (is (= (feed.search/repeated-runs feed) (:repeated (meta docs))))))
    (testing "a show's notes as they are without the runs"
      (is (= (str "Roasting coffee at home. " sponsor)
             (get-in (feed.search/item-document feed (first (:rss/items feed)))
                     [:fields :description])))))
  (testing "in any language, also one written without spaces between words"
    (is (= ["Kaffe.  ." "Te.  ." " . Vand."]
           (cut-notes (show-of (for [text ["Kaffe. %s" "Te. %s" "%s Vand."]]
                                 (str/replace text "%s" "Denne episode er støttet af Kaffe og Kage, Danmarks bedste bageri."))))))
    (is (= ["咖啡的历史。 " "茶的历史。 " " 。水的历史"]
           (cut-notes (show-of ["咖啡的历史。本期节目由咖啡公司赞助播出"
                                "茶的历史。本期节目由咖啡公司赞助播出"
                                "本期节目由咖啡公司赞助播出。水的历史"]))))))

(deftest the-podcast-index
  (let [idx (feed.search/index [{:id 1 :fields {:description "Clojure" :title "Other"}}
                                {:id 2 :fields {:title "Clojure" :description "Other"}}])]
    (is (= [2 1] (mapv :id (search/query idx "clojure"))) "a title weighs more than the notes")
    (is (= [1] (mapv :id (search/query idx "notes:clojure"))) "notes is a name of the description")
    (is (= idx (search/restore (edn/read-string (pr-str idx)))) "the defaults print with the index"))
  (let [idx (feed.search/index [{:id 1 :fields {:description "the future of podcasting"}}
                                {:id 2 :fields {:title "the future of podcasting"}}])]
    (is (= [1] (mapv :id (search/query idx "\"podcasting future\"")))
        "a phrase finds its words anywhere in the notes, which are indexed without positions, and in order elsewhere")))

(deftest the-shows-of-an-index
  (let [url   "https://example.com/show.xml"
        other (guid/with-key (assoc show :rss/title "Other Show") "other-guid")
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
