(ns dk.simongray.podcast.index-test
  "The Podcast Index client: requests, responses mapped onto the model, and
  the resolution of the remote items value time splits point at."
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            [clojure.string :as str]
            #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [clojure.test :refer [deftest is testing async]])
            [dk.simongray.podcast.index :as index]
            [dk.simongray.podcast.resolve :as resolve]
            [dk.simongray.podcast.value :as value]))

(def podcast-body
  #?(:clj  (slurp (io/resource "index/podcast-byguid.json"))
     :cljs (rc/inline "index/podcast-byguid.json")))

(def episode-body
  #?(:clj  (slurp (io/resource "index/episode-byguid.json"))
     :cljs (rc/inline "index/episode-byguid.json")))

(def value-body
  #?(:clj  (slurp (io/resource "index/value-byepisodeguid.json"))
     :cljs (rc/inline "index/value-byepisodeguid.json")))

(def search-body
  #?(:clj  (slurp (io/resource "index/search-byterm.json"))
     :cljs (rc/inline "index/search-byterm.json")))

(def false-body
  #?(:clj  (slurp (io/resource "index/status-false.json"))
     :cljs (rc/inline "index/status-false.json")))

(def credentials
  {:key "K" :secret "S"})

(deftest requests
  (let [r (index/request credentials :search/by-term {:q "podcasting 2.0" :max 5 :fulltext true} 1700000000)]
    (is (= :get (:method r)))
    (is (= "https://api.podcastindex.org/api/1.0/search/byterm?q=podcasting%202.0&max=5&fulltext" (:url r)))
    (is (= {"User-Agent" "dk.simongray/podcast" "X-Auth-Key" "K" "X-Auth-Date" "1700000000"
            "Authorization" "2112f595eb505b9d54dc5eab2336b197daf80af4"}
           (:headers r))))
  (is (= "https://api.podcastindex.org/api/1.0/value/byepisodeguid?podcastguid=a94f&episodeguid=x%231"
         (:url (index/request credentials :value/by-episode-guid {:podcastguid "a94f" :episodeguid "x#1"} 1))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (index/request credentials :nope {}))))

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
  (let [remote {:feed-guid "a94f5cc9-8c58-55fc-91fe-a324087a655b" :item-guid "https://podcastindex.org/podcast/4148683#1"}
        urls   (map :url (index/remote-value-requests credentials remote 1))]
    (testing "the item's own block is asked for first, then the feed's"
      (is (= ["https://api.podcastindex.org/api/1.0/value/byepisodeguid?podcastguid=a94f5cc9-8c58-55fc-91fe-a324087a655b&episodeguid=https%3A%2F%2Fpodcastindex.org%2Fpodcast%2F4148683%231"
              "https://api.podcastindex.org/api/1.0/value/bypodcastguid?guid=a94f5cc9-8c58-55fc-91fe-a324087a655b"]
             urls))
      (is (= 1 (count (index/remote-value-requests credentials {:feed-guid "a94f"} 1)))))
    (testing "sending with a stub, the first answer with a block wins"
      (let [sent (atom [])
            send (fn [{:keys [url]}]
                   (swap! sent conj url)
                   {:status 200 :body (if (str/includes? url "byepisodeguid") value-body false-body)})]
        (is (= ["Artist" "Label"] (map :name (:recipients (index/remote-value credentials remote send)))))
        (is (= 1 (count @sent)))))
    (testing "falling back to the feed's block when the item has none"
      (let [sent (atom [])
            send (fn [{:keys [url]}]
                   (swap! sent conj url)
                   {:status 200 :body (if (str/includes? url "byepisodeguid") false-body value-body)})]
        (is (= ["Artist" "Label"] (map :name (:recipients (index/remote-value credentials remote send)))))
        (is (= 2 (count @sent)))
        (is (str/includes? (second @sent) "bypodcastguid"))))
    (testing "nothing resolvable gives nil, whatever comes back"
      (is (nil? (index/remote-value credentials remote (constantly {:status 200 :body false-body}))))
      (is (nil? (index/remote-value credentials remote (constantly {:status 401 :body "<html>Unauthorized</html>"}))))
      (is (nil? (index/remote-value credentials remote (constantly {:status 500}))))
      (is (nil? (index/remote-value credentials {} (constantly {:status 200 :body value-body})))))))

(deftest resolving-remote-items-to-feeds-and-episodes
  (let [remote {:feed-guid "9b024349-ccf0-5f69-a609-6b82873eab3c" :item-guid "PC2084"}]
    (is (= ["https://api.podcastindex.org/api/1.0/episodes/byguid?guid=PC2084&podcastguid=9b024349-ccf0-5f69-a609-6b82873eab3c"
            "https://api.podcastindex.org/api/1.0/podcasts/byguid?guid=9b024349-ccf0-5f69-a609-6b82873eab3c"]
           (map :url (index/remote-item-requests credentials remote 1))))
    (is (= 1 (count (index/remote-item-requests credentials {:feed-guid "x"} 1))))
    (let [send (fn [{:keys [url]}]
                 {:status 200 :body (if (str/includes? url "episodes/byguid") episode-body podcast-body)})
          {:keys [feed episode]} (index/remote-item credentials remote send)]
      (is (= "Batman University" (:rss/title feed)))
      (is (= "Episode 84 - The Big One" (:rss/title episode))))
    (is (nil? (index/remote-item credentials remote (constantly {:status 200 :body false-body}))))
    (is (nil? (index/remote-item credentials {} (constantly {:status 200 :body podcast-body}))))))

#?(:cljs
   (deftest calling-with-fetch
     (async done
       (let [original (.-fetch js/globalThis)]
         (set! (.-fetch js/globalThis)
               (fn [url _opts]
                 (is (str/starts-with? url "https://api.podcastindex.org/api/1.0/podcasts/byguid?guid="))
                 (js/Promise.resolve #js {:status 200 :text (fn [] (js/Promise.resolve podcast-body))})))
         (-> (index/call! credentials :podcasts/by-guid {:guid "9b024349"})
             (.then (fn [{:keys [status feed]}]
                      (is (true? status))
                      (is (= "Batman University" (:rss/title feed)))))
             (.catch (fn [e] (is (nil? e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) original)
                         (done))))))))
