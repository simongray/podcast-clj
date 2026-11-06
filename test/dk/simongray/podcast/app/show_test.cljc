(ns dk.simongray.podcast.app.show-test
  "The view of a show: what the feed says, and what the network says,
  read through a stand-in for the sender."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [example-guid fixture ranges run]]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.webcrypto.bytes :as bytes]))

(def feed
  (assoc (rss/parse (fixture "feeds/namespace-example.xml")) :podcast/guid example-guid))

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
  URL matches, as bytes when the request asks for them, and throws for a
  URL that matches none, noting each URL in `log`."
  [answers log]
  (fn [{:keys [url as]}]
    (swap! log conj url)
    (or (some (fn [[pattern answer]]
                (when (re-find pattern url)
                  (cond-> (assoc answer :headers {})
                    (= :bytes as) (update :body bytes/encode))))
              answers)
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
      (is (str/starts-with? (:text v) "This is a fake show"))
      (is (= '([:a {:href "https://show.example/about"} "about"])
             (:notes (show/view (assoc feed
                                       :rss/link "https://show.example/"
                                       :rss/description "<a href=\"/about\">about</a>"))))
          "with its links made absolute against the web page of the show")
      (is (= '([:h3 {} "Hosts"] [:h4 {} "Guests"])
             (:notes (show/view (assoc feed :rss/description "<h1>Hosts</h1><h2>Guests</h2>")
                                {:heading-level 3})))
          "with its headings under those of the page")
      (is (= '([:a {:href "https://x.example/" :target "_blank"} "x"])
             (:notes (show/view (assoc feed :rss/description "<a href=\"https://x.example/\">x</a>")
                                {:link-fn (fn [[tag attrs & children]]
                                            (into [tag (assoc attrs :target "_blank")] children))})))
          "with its links as the app makes them"))
    (testing "the episodes in listening order, and the seasons"
      (is (= ["Episode 3 - The Future" "Episode 2 - The Present" "Episode 1 - The Past"]
             (map :rss/title (:episodes v))))
      (is (nil? (:rss/items v)) "in place of the items")
      (is (= 3 (count (:rss/items (show/view feed {:except #{:episodes}})))))
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
       (async/then (show/fetch-linked-shows! (show/view feed) {:send (answering answers log) :index credentials :now-fn (constantly #inst "2026-09-30T12:00:00Z")})
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
       (async/then (show/fetch-linked-shows! (show/view feed) {:send (answering [[#"publisherexample" {:status 404 :body ""}]
                                                                                  [#"pc20" {:status 200 :body (fixture "feeds/namespace-example.xml")}]
                                                                                  [#"feedurl" {:status 404 :body ""}]]
                                                                                 log)})
                   (fn [v]
                     (testing "without credentials, the podroll comes from the feeds at its URLs"
                       (is (= "Podcasting 2.0 Namespace Example" (:rss/title (:show (first (:podroll v))))))
                       (is (not (discovery/head? (:show (first (:podroll v))))) "a short feed, which comes whole")
                       (is (= [:fetch/status 404] ((juxt :id :status) (:error (second (:podroll v)))))))
                     (testing "and a publisher feed that is gone has :error"
                       (is (= [:fetch/status 404] ((juxt :id :status) (:error (:publisher v)))))
                       (is (= (:message (diagnostics/describe {:id :fetch/status}))
                              (:message (:error (:publisher v))))
                           "with the message of its id")
                       (is (nil? (:confirmed? (:publisher v))))))))
     (fn []
       (let [other (str/replace publisher-feed (:podcast/guid feed) "11111111-2222-3333-4444-555555555555")]
         (async/then (show/fetch-linked-shows! (show/view feed) {:send (answering [[#"publisherexample" {:status 200 :body other}]] log)})
                     (fn [v]
                       (is (false? (:confirmed? (:publisher v))) "a publisher feed that does not list the show"))))))))

(deftest the-head-of-a-feed-in-a-podroll
  (let [items (apply str (repeat 2000 "<item><title>An episode</title><guid>g</guid></item>"))
        xml   #(str "<rss version=\"2.0\"><channel><title>Recommended</title><description>" % "</description>"
                    items "</channel></rss>")
        view  {:podroll [{:feed-guid "f" :feed-url "https://example.org/feed.xml"}]}
        short (atom [])
        long  (atom [])]
    (run
     (fn []
       (async/then (show/fetch-linked-shows! view {:send (ranges (xml "A show.") short)})
                   (fn [v]
                     (testing "the show, read from the first 64 KB of a feed of more"
                       (is (= "Recommended" (:rss/title (:show (first (:podroll v))))))
                       (is (= 1 (count (:rss/items (:show (first (:podroll v)))))) "as far as its first item")
                       (is (discovery/head? (:show (first (:podroll v)))))
                       (is (= [[0 65536]] @short) "one request, which read! widens to 64 KB")))))
     (fn []
       (async/then (show/fetch-linked-shows! view {:send (ranges (xml (apply str (repeat 70000 "x"))) long)})
                   (fn [v]
                     (testing "and from more, when the channel goes on past them"
                       (is (= "Recommended" (:rss/title (:show (first (:podroll v))))))
                       (is (= 2 (count @long))))))))))

(defn- place
  "The place in the `items` by the `entries`, a map of their guids to their
  entries in a profile."
  [items entries]
  (show/place items (comp entries :content :rss/guid)))

(deftest the-place-in-a-serial
  (let [item    (fn [g more] (merge {:rss/guid {:content g} :itunes/duration 1800} more))
        items   [(item "t" {:itunes/episode-type "trailer" :itunes/duration 60})
                 (item "1" {})
                 (item "2" {})
                 (item "b" {:itunes/episode-type "bonus" :itunes/duration 600})
                 (item "3" {:itunes/duration 3600})]
        entries {"1" {:played? true} "2" {:position 600}}]
    (is (= {:total    3
            :heard    1
            :left     4800
            :unknown  0
            :started? true
            :seasons  1
            :next     (items 2)
            :season   {:number 2 :total 3 :heard 1 :left 4800 :unknown 0}}
           (place items entries))
        "the first full episode not yet heard, and the time left from where it was left")
    (is (= {:total    3
            :heard    0
            :left     7200
            :unknown  0
            :started? false
            :seasons  1
            :next     (items 1)
            :season   {:number 1 :total 3 :heard 0 :left 7200 :unknown 0}}
           (place items {}))
        "the first, before any is heard, with neither the trailer nor the bonus")
    (is (= {:total 3 :heard 3 :left 0 :unknown 0 :started? true :seasons 1}
           (place items (zipmap ["1" "2" "3"] (repeat {:played? true}))))
        "no next once all are heard")
    (is (= 2 (:number (:season (place items {"1" {:played? true} "3" {:played? true}}))))
        "an earlier one that was skipped comes first")
    (is (= 6600 (:left (place items {"1" {:duration 1200}})))
        "the length that the player measured over the feed's")
    (is (= {:left 5400 :unknown 1}
           (select-keys (place (update-in items [1] dissoc :itunes/duration) {}) [:left :unknown]))
        "the lengths that are missing counted apart")
    (is (false? (:started? (place items {"t" {:played? true}}))) "a trailer doesn't start the show")
    (is (nil? (place [(first items)] {})) "and no place without full episodes"))
  (let [item    (fn [g season]
                  {:rss/guid {:content g} :itunes/duration 1800 :itunes/season season})
        trailer (assoc (item "t2" 2) :itunes/episode-type "trailer")
        items   [(item "a1" 1) (item "a2" 1) trailer (item "b1" 2) (item "b2" 2) (item "b3" 2)]
        heard   (fn [ms] {:played? true :updated-at (time/->inst ms)})
        at      (fn [entries] (:season (place items entries)))]
    (is (= {:season 2 :number 2 :total 3 :heard 1 :left 3600 :unknown 0}
           (at {"b1" (heard 2000)}))
        "the season that the listener chose, with the counts of that season")
    (is (= 2 (:seasons (place items {}))) "of the seasons there are")
    (is (= (items 4) (:next (place items {"b1" (heard 2000)}))))
    (is (= 2 (:number (at {"b1" (heard 1000) "a1" (heard 2000)})))
        "and of the one heard last, when they've heard from several")
    (is (= {:season 2 :number 1} (select-keys (at {"a1" (heard 1000) "a2" (heard 2000)}) [:season :number]))
        "the next season, once that one is heard")
    (is (= {:season 1 :number 1}
           (select-keys (at (zipmap ["b1" "b2" "b3"] (repeat (heard 2000)))) [:season :number]))
        "or else an earlier one")
    (is (= {:season 2 :number 1} (select-keys (at {"a1" (heard 1000) "t2" (heard 2000)}) [:season :number]))
        "and a season's trailer leads to that season")
    (is (= "The Sequel"
           (:name (:season (place [{:rss/guid {:content "x"} :podcast/season {:content 1 :name "The Sequel"}}] {}))))
        "with the name that the feed gives it")))
