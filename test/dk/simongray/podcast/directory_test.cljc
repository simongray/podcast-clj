(ns dk.simongray.podcast.directory-test
  "The directories behind one shape: requests, the shows read from recorded
  answers, and several sources combined."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.resolve :as resolve]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def apple-search
  (fixture "directory/apple-search.json"))

(def apple-lookup
  (fixture "directory/apple-lookup.json"))

(def apple-charts
  (fixture "directory/apple-charts.json"))

(def fyyd-search
  (fixture "directory/fyyd-search.json"))

(def fyyd-podcast
  (fixture "directory/fyyd-podcast.json"))

(def fyyd-hot
  (fixture "directory/fyyd-hot.json"))

(def gpodder-search
  (fixture "directory/gpodder-search.json"))

(def gpodder-podcast
  (fixture "directory/gpodder-podcast.json"))

(def gpodder-toplist
  (fixture "directory/gpodder-toplist.json"))

(def index-search
  (fixture "index/search-byterm.json"))

(def pc20-url "https://feeds.podcastindex.org/pc20.xml")

(deftest requests
  (let [url (fn [source endpoint params & [opts]] (:url (directory/request source endpoint params (or opts {}))))]
    (is (= "https://itunes.apple.com/search?media=podcast&entity=podcast&term=podcasting%202.0&limit=5&country=dk"
           (url :apple :search {:q "podcasting 2.0" :limit 5 :country "dk"})))
    (is (= "https://itunes.apple.com/lookup?id=1584274529&entity=podcast" (url :apple :lookup {:id "1584274529"})))
    (is (= "https://itunes.apple.com/lookup?id=1584274529%2C1350835571&entity=podcast"
           (url :apple :lookup {:id ["1584274529" "1350835571"]}))
        "several at once")
    (is (= "https://rss.marketingtools.apple.com/api/v2/dk/podcasts/top/10/podcasts.json"
           (url :apple :trending {:country :dk :limit 10})))
    (is (= "https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/25/podcasts.json" (url :apple :trending {})))
    (is (= "https://api.fyyd.de/0.2/search/podcast?term=podcasting%202.0&count=5" (url :fyyd :search {:q "podcasting 2.0" :limit 5})))
    (is (= "https://api.fyyd.de/0.2/podcast?podcast_id=61003" (url :fyyd :lookup {:id 61003})))
    (is (= "https://api.fyyd.de/0.2/feature/podcast/hot?count=3" (url :fyyd :trending {:limit 3})))
    (is (= "https://gpodder.net/search.json?q=podcasting%202.0" (url :gpodder :search {:q "podcasting 2.0" :limit 5})))
    (is (= (str "https://gpodder.net/api/2/data/podcast.json?url=" "https%3A%2F%2Ffeeds.podcastindex.org%2Fpc20.xml")
           (url :gpodder :lookup {:id pc20-url})))
    (is (= "https://gpodder.net/toplist/3.json" (url :gpodder :trending {:limit 3})))
    (let [credentials {:credentials {:key "K" :secret "S"} :auth-date 1700000000}
          request     (directory/request :podcastindex :search {:q "podcasting 2.0" :limit 5} credentials)]
      (is (= "https://api.podcastindex.org/api/1.0/search/byterm?q=podcasting%202.0&max=5" (:url request)))
      (is (= "K" (get-in request [:headers "X-Auth-Key"])))
      (is (str/ends-with? (url :podcastindex :lookup {:id 920666} credentials) "podcasts/byfeedid?id=920666"))
      (is (str/ends-with? (url :podcastindex :lookup {:id "917393e3-1b1e-5cef-ace4-edaa54e1f810"} credentials)
                          "podcasts/byguid?guid=917393e3-1b1e-5cef-ace4-edaa54e1f810")))
    (is (= {"User-Agent" http/user-agent} (:headers (directory/request :fyyd :search {:q "x"}))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (directory/request :podcastindex :search {:q "x"})) "credentials wanted")
    (is (thrown? #?(:clj Exception :cljs js/Error) (directory/request :spotify :search {:q "x"})))
    (is (thrown? #?(:clj Exception :cljs js/Error) (directory/request :apple :episodes {:q "x"})))
    (is (thrown? #?(:clj Exception :cljs js/Error) (directory/request :apple :trending {:country "Denmark"})))))

(deftest another-service
  (let [mirror (assoc (:fyyd directory/sources) :id :mirror :name "A mirror" :root "https://fyyd.example.org/0.2")
        own    (assoc (:gpodder directory/sources) :root "https://podcasts.example.org/")
        index  (assoc (:podcastindex directory/sources) :id "index-2" :root "https://index.example.org/api/1.0/" :pages nil)
        charts (assoc (:apple directory/sources) :trending "https://charts.example.org/api/v2")]
    (testing "the requests go to the URLs of the entry"
      (is (= "https://fyyd.example.org/0.2/search/podcast?term=x&count=5"
             (:url (directory/request mirror :search {:q "x" :limit 5}))))
      (is (= "https://podcasts.example.org/toplist/3.json" (:url (directory/request own :trending {:limit 3}))))
      (is (= "https://index.example.org/api/1.0/podcasts/trending?max=3"
             (:url (directory/request index :trending {:limit 3} {:credentials {:key "K" :secret "S"} :auth-date 1}))))
      (is (= "https://charts.example.org/api/v2/dk/podcasts/top/10/podcasts.json"
             (:url (directory/request charts :trending {:country :dk :limit 10}))))
      (is (= "https://itunes.apple.com/lookup?id=1&entity=podcast" (:url (directory/request charts :lookup {:id 1})))
          "what the entry leaves as it was"))
    (testing "the shows name the source by the id of the entry, or else by its API"
      (is (= [:mirror] (distinct (map :directory/source (directory/response mirror :search fyyd-search)))))
      (is (= [:gpodder] (distinct (map :directory/source (directory/response own :search gpodder-search)))))
      (let [[show] (directory/response index :search index-search)]
        (is (= "index-2" (:directory/source show)))
        (is (nil? (:directory/url show)) "an entry without the pages of the shows")))
    (testing "and the entries of two services combine as two sources"
      (let [[show] (directory/combine (concat (directory/response :fyyd :search fyyd-search)
                                              (directory/response mirror :search fyyd-search)))]
        (is (= {:fyyd 61003 :mirror 61003} (:directory/sources show)))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (directory/request {:api :spotify :root "https://x/"} :search {:q "x"}))
        "an API that podcast-clj does not know")
    #?(:clj (testing "a source that fails is named by its id"
              (with-redefs [http/call! (fn [_] {:status 500 :body "down"})]
                (is (= [:mirror] (keys (:failed (directory/search! "x" {:sources [mirror]}))))))))))

(deftest apple
  (testing "search results"
    (let [shows (directory/response :apple :search apple-search)
          show  (first shows)]
      (is (= 2 (count shows)))
      (is (= "Podcasting Explained! 2.0: The Podcast" (:rss/title show)))
      (is (= "Steven Christianson" (:itunes/author show)))
      (is (= "https://anchor.fm/s/10eab8b28/podcast/rss" (resolve/self-url show)))
      (is (= (guid/from-url "https://anchor.fm/s/10eab8b28/podcast/rss") (:podcast/guid show)) "the guid the feed would get")
      (is (= [{:text "How To"} {:text "Education"}] (:itunes/categories show)) "Apple's umbrella genre left out")
      (is (false? (:itunes/explicit show)))
      (is (inst? (:rss/pub-date show)))
      (is (str/starts-with? (:href (:itunes/image show)) "https://is1-ssl.mzstatic.com/"))
      (is (= {:directory/source :apple :directory/id "1875035030" :directory/episode-count 10}
             (select-keys show [:directory/source :directory/id :directory/episode-count])))
      (is (str/starts-with? (:directory/url show) "https://podcasts.apple.com/"))
      (is (map? (:directory/raw show)))))
  (testing "a lookup"
    (let [[show :as shows] (directory/response :apple :lookup apple-lookup)]
      (is (= 1 (count shows)))
      (is (= "Podcasting 2.0" (:rss/title show)))
      (is (= pc20-url (resolve/self-url show)))
      (is (= "1584274529" (:directory/id show)))))
  (testing "the chart, which names no feed"
    (let [shows (directory/response :apple :trending apple-charts)
          show  (first shows)]
      (is (= [1 2 3] (map :directory/rank shows)))
      (is (= "Mørkeland" (:rss/title show)))
      (is (true? (:itunes/explicit show)) "Apple's misspelt Explict read as explicit")
      (is (= [{:text "True Crime"}] (:itunes/categories show)))
      (is (= "1350835571" (:directory/id show)))
      (is (nil? (resolve/self-url show)))
      (is (nil? (:podcast/guid show))))))

(deftest fyyd
  (testing "search results"
    (let [shows (directory/response :fyyd :search fyyd-search)
          show  (first shows)]
      (is (= 1 (count shows)))
      (is (= "Podcasting 2.0" (:rss/title show)))
      (is (= "Podcast Index LLC" (:itunes/author show)))
      (is (= pc20-url (resolve/self-url show)))
      (is (= (guid/from-url pc20-url) (:podcast/guid show)))
      (is (= "en" (:rss/language show)))
      (is (= "http://podcastindex.org" (:rss/link show)))
      (is (inst? (:rss/pub-date show)))
      (is (= [{:url "https://paypal.me/podcastindex"}] (:podcast/funding show)))
      (is (= {:directory/source :fyyd :directory/id 61003 :directory/episode-count 266
              :directory/url "https://fyyd.de/podcast/podcasting-2-0/0"}
             (select-keys show [:directory/source :directory/id :directory/episode-count :directory/url])))))
  (testing "a lookup and the hot list"
    (is (= ["Freakonomics Radio"] (map :rss/title (directory/response :fyyd :lookup fyyd-podcast))))
    (let [shows (directory/response :fyyd :trending fyyd-hot)]
      (is (= 3 (count shows)))
      (is (= [1 2 3] (map :directory/rank shows)))
      (is (every? resolve/self-url shows))))
  (is (= [] (directory/response :fyyd :search "{\"status\": 0, \"msg\": \"nope\"}")) "a failure is no shows"))

(deftest gpodder
  (testing "search results"
    (let [shows (directory/response :gpodder :search gpodder-search)
          show  (first shows)]
      (is (= 3 (count shows)))
      (is (= "La Buhardilla 2.0" (:rss/title show)))
      (is (= "http://feeds.feedburner.com/buhardillapodcast" (resolve/self-url show)))
      (is (= "http://feeds.feedburner.com/buhardillapodcast" (:directory/id show)) "the feed URL is the id")
      (is (= 32 (:directory/subscribers show)))
      (is (= "http://gpodder.net/podcast/la-buhardilla-20" (:directory/url show)))
      (is (= :gpodder (:directory/source show)))))
  (testing "a lookup and the toplist"
    (is (= ["Podcasting 2.0"] (map :rss/title (directory/response :gpodder :lookup gpodder-podcast))))
    (let [shows (directory/response :gpodder :trending gpodder-toplist)]
      (is (= ["Linux Outlaws" "This American Life"] (take 2 (map :rss/title shows))))
      (is (= [1 2 3] (map :directory/rank shows)))
      (is (= 3321 (:directory/subscribers (first shows)))))))

(deftest podcast-index
  (let [shows (directory/response :podcastindex :search index-search)
        show  (first shows)]
    (is (seq shows))
    (is (= :podcastindex (:directory/source show)))
    (is (= (:index/id show) (:directory/id show)))
    (is (= (:index/url show) (resolve/self-url show)))
    (is (= (str "https://podcastindex.org/podcast/" (:index/id show)) (:directory/url show)))
    (is (string? (:rss/title show)))
    (is (= [] (directory/response :podcastindex :search "{\"status\": \"false\", \"description\": \"no\"}")))))

#?(:clj
   (deftest statuses
     (testing "a refused key is a failure, named as one, not an empty answer"
       (with-redefs [http/call! (fn [_] {:status 401 :body "{\"status\":\"false\",\"description\":\"Authorization header value is not valid\"}"})]
         (is (= [:podcastindex] (keys (:failed (directory/search! "x" {:sources [:podcastindex] :credentials {:key "k" :secret "s"}})))))))
     (testing "and a 404 is no shows"
       (with-redefs [http/call! (fn [_] {:status 404 :body "Not Found"})]
         (is (= [] (directory/call! :fyyd :lookup {:id 1})))))))

(deftest combined
  (let [apple   (directory/response :apple :lookup apple-lookup)
        fyyd    (directory/response :fyyd :search fyyd-search)
        gpodder (directory/response :gpodder :lookup gpodder-podcast)
        others  (directory/response :apple :search apple-search)
        shows   (directory/combine (concat apple fyyd gpodder others))
        pc20    (first shows)]
    (testing "one entry per feed, the first source's record, every source named"
      (is (= 2 (count shows)) "Apple's search finds Podcasting 2.0 as well, and it joins the entry")
      (is (= "Podcasting 2.0" (:rss/title pc20)))
      (is (= :apple (:directory/source pc20)))
      (is (= {:apple "1584274529" :fyyd 61003 :gpodder pc20-url} (:directory/sources pc20)))
      (is (= "en" (:rss/language pc20)) "a field the first source lacked, filled from the next")
      (is (= "1584274529" (:directory/id pc20)) "but the first source's own keys stay")
      (is (= [{:apple "1875035030"}] (map :directory/sources (rest shows)))))
    (testing "two feeds of one show keep their own entries"
      (let [twin (assoc (first fyyd) :podcast/guid "another-guid" :directory/id 1)]
        (is (= 2 (count (directory/combine [(first fyyd) twin]))))))
    (testing "a chart entry without a feed matches by title and author and gains the feed"
      (let [chart {:rss/title "Podcasting 2.0" :itunes/author "Podcast Index LLC"
                   :directory/source :apple :directory/id "1584274529" :directory/rank 1}
            [show] (directory/combine [chart (first fyyd)])]
        (is (= pc20-url (resolve/self-url show)))
        (is (= 1 (:directory/rank show)))
        (is (= {:apple "1584274529" :fyyd 61003} (:directory/sources show)))))
    (testing "and meets its own lookup by the id they share, whatever the title"
      (let [chart  (first (directory/response :apple :trending apple-charts))
            lookup {:rss/title "MØRKELAND" :itunes/author "Camilla og Kristine"
                    :atom/links [{:rel "self" :href "https://example.com/morkeland.xml"}]
                    :podcast/guid (guid/from-url "https://example.com/morkeland.xml")
                    :directory/source :apple :directory/id "1350835571"}
            [show :as shows] (directory/combine [chart lookup])]
        (is (= 1 (count shows)))
        (is (= "Mørkeland" (:rss/title show)) "the chart's record first")
        (is (= 1 (:directory/rank show)))
        (is (= "https://example.com/morkeland.xml" (resolve/self-url show)))))))
