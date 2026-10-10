(ns dk.simongray.podcast.directory.podcastindex-test
  "The Podcast Index client: requests, responses mapped onto the model, and
  the resolution of the remote items value time splits point at."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def podcast-body
  (fixture "index/podcast-byguid.json"))

(def episode-body
  (fixture "index/episode-byguid.json"))

(def value-body
  (fixture "index/value-byepisodeguid.json"))

(def search-body
  (fixture "index/search-byterm.json"))

(def false-body
  (fixture "index/status-false.json"))

(def credentials
  {:key "K" :secret "S"})

(deftest requests
  (let [r (index/request credentials :search/by-term {:q "podcasting 2.0" :max 5 :fulltext true} {:auth-date 1700000000})]
    (is (= :get (:method r)))
    (is (= "https://api.podcastindex.org/api/1.0/search/byterm?q=podcasting%202.0&max=5&fulltext" (:url r)))
    (is (= {"User-Agent" client/user-agent "X-Auth-Key" "K" "X-Auth-Date" "1700000000"
            "Authorization" "2112f595eb505b9d54dc5eab2336b197daf80af4"}
           (:headers (http/revealed r))))
    (is (not (re-find #"2112f595" (pr-str r))) "a request that is printed does not show the hash"))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (index/request credentials :search/by-term {:q "x"} {}))
      "a request is signed at a time the caller gives, never one read here")
  (is (= "https://api.podcastindex.org/api/1.0/value/byepisodeguid?podcastguid=a94f&episodeguid=x%231"
         (:url (index/request credentials :value/by-episode-guid {:podcastguid "a94f" :episodeguid "x#1"} {:auth-date 1}))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (index/request credentials :nope {} {:auth-date 1})))
  (testing "another service with the same API"
    (is (= ["https://index.example.org/api/1.0/podcasts/byguid?guid=a94f"
            "https://index.example.org/api/1.0/podcasts/byguid?guid=a94f"]
           (for [root ["https://index.example.org/api/1.0" "https://index.example.org/api/1.0/"]]
             (:url (index/request credentials :podcasts/by-guid {:guid "a94f"} {:auth-date 1 :base-url root})))))
    (is (every? #(str/starts-with? (:url %) "https://index.example.org/api/1.0/value/")
                (index/remote-value-requests credentials {:feed-guid "a94f" :item-guid "x"}
                                             {:auth-date 1 :base-url "https://index.example.org/api/1.0/"})))))

(deftest categories-of-the-index
  (is (= 112 (count index/categories)))
  (is (= ["Arts" "Video-Games" "Society" "Culture" "Technology" "True Crime" "Cryptocurrency"]
         (map index/categories [1 52 77 78 102 103 112]))
      "by the ids of categories.json of the podcast namespace")
  (testing "a name or an id, and a name of Apple Podcasts as the names of its parts"
    (is (= ["Technology" "Society,Culture" "Society,Culture" "Video-Games" "Self-Improvement" "Technology"
            "Technology,News" "True Crime,News"]
           (map index/category-filter ["technology" "Society & Culture" "society, culture" "video games"
                                       "Self Improvement" 102 "102, 55" ["True Crime" "news"]]))))
  (is (nil? (index/category-filter nil)))
  (is (nil? (index/category-filter " ")))
  (testing "a name that the index does not know is no filter there, so it is a mistake here"
    (doseq [unknown ["Tech News" "Knitting" "Technology,Knitting" 999]]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"has no category"
                            (index/category-filter unknown))
          (pr-str unknown))))
  (is (= "Knitting" (index/category-filter "knitting" {:categories {1 "Knitting"}}))
      "the list of the index as it is now, from the call :categories/list")
  (is (str/ends-with? (:url (index/request credentials :podcasts/trending
                                           {:max 5 :cat (index/category-filter "Health & Fitness")}
                                           {:auth-date 1}))
                      "podcasts/trending?max=5&cat=Health%2CFitness")))

(deftest a-feed-from-the-index
  (let [{:keys [status description query feed]} (index/response podcast-body)]
    (is (true? status))
    (is (= "Found matching feed" description))
    (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (get query "guid")))
    (testing "the keys a parsed feed has"
      (is (= "Batman University" (:rss/title feed)))
      (is (= "https://www.theincomparable.com/batmanuniversity/" (:rss/link feed)))
      (is (= "en-us" (:rss/language feed)))
      (is (= "Tony Sindelar" (:itunes/author feed)))
      (is (= {:name "The Incomparable"} (:itunes/owner feed)))
      (is (= {:href "https://www.theincomparable.com/imgs/logos/logo-batmanuniversity-3x.jpg"} (:itunes/image feed)))
      (is (false? (:itunes/explicit feed)))
      (is (= "episodic" (:itunes/type feed)))
      (is (= [{:text "Tv"} {:text "Film"} {:text "Reviews"}] (:itunes/categories feed)))
      (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (:podcast/guid feed)))
      (is (= {:content true} (:podcast/locked feed)))
      (is (= [{:url "https://patreon.com/johnchidgey" :content "Pragmatic on Patreon"}] (:podcast/funding feed)))
      (is (= {:type "lightning" :method "keysend" :suggested 2.0E-7
              :recipients [{:name "podcaster" :type "node" :split 99
                            :address "03ae9f91a0cb8ff43840e3c322c4c61f019d8c1c3cea15a25cfc425ac605e61a4a"}
                           {:name "index" :type "node" :split 1 :fee true :custom-key "818818" :custom-value "abc"
                            :address "03ae9f91a0cb8ff43840e3c322c4c61f019d8c1c3cea15a25cfc425ac605e61a4a"}]}
             (first (:podcast/values feed)))))
    (testing "what only the index knows"
      (is (= 75075 (:index/id feed)))
      (is (= 19 (:index/episode-count feed)))
      (is (false? (:index/dead? feed)))
      (is (= "rss" (:index/format feed)))
      (is (= #inst "2021-02-15T13:00:44Z" (:index/last-update-time feed)))
      (is (= 1441923632 (:index/itunes-id feed)))
      (is (= "Batman University" (get-in feed [:index/raw "title"]))))
    (testing "the accessors work on it as on a parsed feed"
      (is (= "Batman University" (resolve/title feed)))
      (is (= "Tony Sindelar" (resolve/author feed)))
      (is (= "https://www.theincomparable.com/imgs/logos/logo-batmanuniversity-3x.jpg" (resolve/artwork feed)))
      (is (resolve/locked? feed))
      (is (= [["Tv"] ["Film"] ["Reviews"]] (resolve/categories feed))))))

(deftest an-episode-from-the-index
  (let [{:keys [status episode]} (index/response episode-body)]
    (is (true? status))
    (is (= "Episode 84 - The Big One" (:rss/title episode)))
    (is (= {:content "PC2084"} (:rss/guid episode)))
    (is (= #inst "2019-01-02T03:30:13Z" (:rss/pub-date episode)))
    (is (= {:url "https://mp3s.nashownotes.com/PC2084.mp3" :type "audio/mp3" :length 26385472} (:rss/enclosure episode)))
    (is (= 54 (:itunes/duration episode)))
    (is (false? (:itunes/explicit episode)))
    (is (= {:type "full" :season 3 :episode 84} (resolve/numbering episode)))
    (is (= {:url "https://studio.hypercatcher.com/chapters/podcast/pc20/84.json"} (:podcast/chapters episode)))
    (is (= [{:url "https://mp3s.nashownotes.com/NA-1385-Captions.srt" :type "application/srt"}
            {:url "https://mp3s.nashownotes.com/NA-1385-Captions.vtt" :type "text/vtt"}]
           (:podcast/transcripts episode)))
    (is (= "text/vtt" (:type (resolve/transcript episode))))
    (is (= [{:name "Adam Curry" :role "host" :group "cast" :href "https://curry.com" :img "https://example.com/adam.jpg"}
            {:name "Dave Jones" :role "host" :group "cast"}]
           (resolve/people episode)))
    (is (= [{:uri "https://podcastindex.social/@dave/105079274766075912" :protocol "activitypub"
             :account-id "@dave" :account-url "https://podcastindex.social/@dave" :priority 1}]
           (:podcast/social-interacts episode)))
    (is (= [{:start-time 1123 :duration 40 :content "Why the Podcast Namespace Matters"} {:start-time 2000 :duration 30}]
           (:podcast/soundbites episode)))
    (is (= [49 46 5] (map :split (:recipients (resolve/value episode)))))
    (is (= 920666 (:index/feed-id episode)))
    (is (= "Podcasting 2.0" (:index/feed-title episode)))
    (testing "the single legacy transcript field is used when the list is absent"
      (is (= [{:url "https://x/t.srt"}]
             (:podcast/transcripts (index/episode->model {"transcriptUrl" "https://x/t.srt"})))))
    (testing "a zero season, episode or duration means the index knows none, as seen live"
      (let [e (index/episode->model {"season" 0 "episode" 12 "duration" 0 "feedUrl" "https://f/rss"})]
        (is (= {:type "full" :episode 12} (resolve/numbering e)))
        (is (nil? (resolve/duration e)))
        (is (= "https://f/rss" (:index/feed-url e)))))))

(deftest lists-and-failures
  (let [{:keys [status count feeds]} (index/response search-body)]
    (is (true? status))
    (is (= 1 count))
    (is (= ["Podcasting 2.0"] (map :rss/title feeds)))
    (is (= #inst "2025-09-25T16:42:40Z" (:index/newest-item-published (first feeds)))))
  (let [{:keys [status description feeds feed]} (index/response false-body)]
    (is (false? status))
    (is (= "No feeds match this guid." description))
    (is (nil? feeds))
    (is (nil? feed))))

(deftest value-blocks-from-the-index
  (let [{:keys [value]} (index/response value-body)]
    (is (= {:type "lightning" :method "keysend" :suggested 2.0E-7
            :recipients [{:name "Artist" :type "node" :address "02aa" :split 90}
                         {:name "Label" :type "node" :address "02bb" :split 10}]}
           value))
    (is (= [["Artist" 90] ["Label" 10]]
           (map (juxt (comp :name :recipient) :amount) (value/payments (:recipients value) 100))))))

(deftest resolving-remote-items
  (let [remote    {:feed-guid "a94f5cc9-8c58-55fc-91fe-a324087a655b" :item-guid "https://podcastindex.org/podcast/4148683#1"}
        sent      (atom [])
        answering (fn [item-body feed-body]
                    (fn [{:keys [url]}]
                      (swap! sent conj url)
                      {:status 200 :body (if (str/includes? url "byepisodeguid") item-body feed-body)}))
        resolved  #(index/fetch-remote-value! credentials %1 {:send %2 :auth-date 1})]
    (testing "the item's own block is asked for first, then the feed's"
      (is (= ["https://api.podcastindex.org/api/1.0/value/byepisodeguid?podcastguid=a94f5cc9-8c58-55fc-91fe-a324087a655b&episodeguid=https%3A%2F%2Fpodcastindex.org%2Fpodcast%2F4148683%231"
              "https://api.podcastindex.org/api/1.0/value/bypodcastguid?guid=a94f5cc9-8c58-55fc-91fe-a324087a655b"]
             (map :url (index/remote-value-requests credentials remote {:auth-date 1}))))
      (is (= 1 (count (index/remote-value-requests credentials {:feed-guid "a94f"} {:auth-date 1})))))
    (run #(async/then (resolved remote (answering value-body false-body))
                      (fn [block]
                        (is (= ["Artist" "Label"] (map :name (:recipients block))) "the first answer with a block wins")
                        (is (= 1 (count @sent)))))
         #(do (reset! sent [])
              (async/then (resolved remote (answering false-body value-body))
                          (fn [block]
                            (is (= ["Artist" "Label"] (map :name (:recipients block))) "the feed's block when the item has none")
                            (is (= 2 (count @sent)))
                            (is (str/includes? (second @sent) "bypodcastguid")))))
         #(async/then (async/all [(resolved remote (constantly {:status 200 :body false-body}))
                                  (resolved remote (constantly {:status 401 :body "<html>Unauthorized</html>"}))
                                  (resolved remote (constantly {:status 500}))
                                  (resolved {} (constantly {:status 200 :body value-body}))])
                      (fn [blocks]
                        (is (= [nil nil nil nil] blocks) "nothing resolvable gives nil, whatever comes back"))))))

(deftest resolving-remote-items-to-feeds-and-episodes
  (let [remote {:feed-guid "9b024349-ccf0-5f69-a609-6b82873eab3c" :item-guid "PC2084"}
        send   (fn [{:keys [url]}]
                 {:status 200 :body (if (str/includes? url "episodes/byguid") episode-body podcast-body)})]
    (is (= ["https://api.podcastindex.org/api/1.0/episodes/byguid?guid=PC2084&podcastguid=9b024349-ccf0-5f69-a609-6b82873eab3c"
            "https://api.podcastindex.org/api/1.0/podcasts/byguid?guid=9b024349-ccf0-5f69-a609-6b82873eab3c"]
           (map :url (index/remote-item-requests credentials remote {:auth-date 1}))))
    (is (= 1 (count (index/remote-item-requests credentials {:feed-guid "x"} {:auth-date 1}))))
    (run #(async/then (index/fetch-remote-item! credentials remote {:send send :auth-date 1})
                      (fn [{:keys [feed episode]}]
                        (is (= "Batman University" (:rss/title feed)))
                        (is (= "Episode 84 - The Big One" (:rss/title episode)))))
         #(async/then (async/all [(index/fetch-remote-item! credentials remote {:send (constantly {:status 200 :body false-body}) :auth-date 1})
                                  (index/fetch-remote-item! credentials {} {:send (constantly {:status 200 :body podcast-body}) :auth-date 1})])
                      (fn [items]
                        (is (= [nil nil] items) "nothing that the index knows"))))))

(deftest calling-through-a-send
  (let [sent (atom nil)
        send (fn [{:keys [url headers] :as request}]
               (reset! sent request)
               (is (re-matches #"[0-9a-f]{40}" (secret/reveal (get headers "Authorization"))) "signed")
               {:status 200 :url url :body (if (str/includes? url "/value/") value-body podcast-body)})]
    (run #(async/then (index/call! credentials :podcasts/by-guid {:guid "9b024349"} {:send send})
                      (fn [{:keys [status feed]}]
                        (is (true? status))
                        (is (= "Batman University" (:rss/title feed)))
                        (is (not= "1" (get-in @sent [:headers "X-Auth-Date"])) "signed at the time of the clock")))
         #(async/then (index/call! credentials :podcasts/by-guid {:guid "9b024349"} {:send send :auth-date 1})
                      (fn [_]
                        (is (= "1" (get-in @sent [:headers "X-Auth-Date"])) "or at the :auth-date of the options")))
         #(async/then (index/fetch-remote-value! credentials {:feed-guid "a94f"} {:send send})
                      (fn [block]
                        (is (= ["Artist" "Label"] (map :name (:recipients block))))))
         #(async/then (index/fetch-remote-item! credentials {:feed-guid "9b024349"} {:send send})
                      (fn [{:keys [feed]}]
                        (is (= "Batman University" (:rss/title feed))))))))
