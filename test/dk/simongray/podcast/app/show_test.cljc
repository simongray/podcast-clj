(ns dk.simongray.podcast.app.show-test
  "The view of a show: what the feed says, and what the network says,
  read through a stand-in for the sender."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.secret :as secret]))

(def feed
  (podcast/parse (fixture "feeds/namespace-example.xml")))

(def credentials
  {:key "K" :secret (secret/hide "S")})

(def publisher-feed
  "A publisher feed that lists the show of the example, and another."
  (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel>"
       "<title>Example Media</title><podcast:medium>publisher</podcast:medium>"
       "<podcast:guid>000000a0-aaa5-bbbf-b765-6333d449661a</podcast:guid>"
       "<podcast:remoteItem feedGuid=\"" (:podcast/guid feed) "\" feedUrl=\"https://example.com/feed.xml\"/>"
       "<podcast:remoteItem feedGuid=\"396d9ae0-da7e-5557-b894-b606231fa3ea\" feedUrl=\"https://feedurl.example.rss\"/>"
       "</channel></rss>"))

(defn- answering
  "A sender that answers by the first entry of `answers` whose pattern the
  URL matches, and throws for a URL that matches none, noting each URL
  in `log`."
  [answers log]
  (fn [{:keys [url]}]
    (swap! log conj url)
    (or (some (fn [[pattern answer]] (when (re-find pattern url) (assoc answer :headers {}))) answers)
        (throw (ex-info (str "No route to " url) {:url url})))))

(deftest what-the-feed-says
  (let [v (show/view feed)]
    (testing "the feed, its properties and the identity"
      (is (= "Podcasting 2.0 Namespace Example" (:title v)))
      (is (= (:rss/title v) (:title v)) "the raw fields stay")
      (is (= (:podcast/guid feed) (:guid v)))
      (is (= "podcast" (:medium v))))
    (testing "the description as Hiccup and as text"
      (is (= '([:p {} "This is a fake show that exists only as an example of the \"podcast\" namespace tag usage."]) (:notes v)))
      (is (str/starts-with? (:text v) "This is a fake show")))
    (testing "the episodes in listening order, and the seasons"
      (is (= ["Episode 3 - The Future" "Episode 2 - The Present" "Episode 1 - The Past"]
             (map :rss/title (:episodes v))))
      (is (= [1] (map :season (:seasons v)))))
    (testing "the podroll and the publisher, as the feed names them"
      (is (= ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "396d9ae0-da7e-5557-b894-b606231fa3ea"] (map :feed-guid (:podroll v))))
      (is (= "https://publisherexample.xml" (:feed-url (:publisher v)))))
    (testing "the links to the apps"
      (is (some #(= :podcastindex (:app %)) (:links v))))))

(deftest what-the-network-says
  (let [log     (atom [])
        answers [[#"podcasts/byguid\?guid=917393e3" {:status 200 :body (fixture "index/podcast-byguid.json")}]
                 [#"podcasts/byguid"                 {:status 200 :body (fixture "index/status-false.json")}]
                 [#"publisherexample"                {:status 200 :body publisher-feed}]]]
    (run
     (fn []
       (async/then (show/view! feed {:send (answering answers log) :index credentials :now (constantly #inst "2026-09-30T12:00:00Z")})
                   (fn [v]
                     (testing "the podroll as the index knows it"
                       (is (= [true false] (map (comp some? :show) (:podroll v))) "the second guid is unknown to the index")
                       (is (= "Batman University" (:rss/title (:show (first (:podroll v))))) "as the fixture of the index has it")
                       (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810" (:feed-guid (first (:podroll v)))) "the remote item stays"))
                     (testing "the publisher, confirmed"
                       (is (true? (:confirmed? (:publisher v))))
                       (is (= "Example Media" (:rss/title (:feed (:publisher v)))))
                       (is (= 2 (count (:shows (:publisher v)))))
                       (is (nil? (:error (:publisher v)))))
                     (is (= 3 (count @log)) "two lookups and the publisher feed"))))
     (fn []
       (async/then (show/view! feed {:send (answering [[#"publisherexample" {:status 404 :body ""}]] log)})
                   (fn [v]
                     (testing "without credentials the podroll is as the feed names it, and a publisher feed that is gone has :error"
                       (is (= [nil nil] (map :show (:podroll v))))
                       (is (= [:fetch/unreachable 404] ((juxt :id :status) (:error (:publisher v)))))
                       (is (nil? (:confirmed? (:publisher v))))))))
     (fn []
       (let [other (str/replace publisher-feed (:podcast/guid feed) "11111111-2222-3333-4444-555555555555")]
         (async/then (show/view! feed {:send (answering [[#"publisherexample" {:status 200 :body other}]] log)})
                     (fn [v]
                       (is (false? (:confirmed? (:publisher v))) "a publisher feed that does not list the show"))))))))
