(ns dk.simongray.podcast.feed.publish-test
  "What a host does to a feed before serving it: the pages of a long feed,
  the URLs of its files, and the private feed of a subscriber."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.feed.publish :as publish]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def long-feed
  "A feed of seven items, the newest first, with links of its own."
  {:rss/title       "Long"
   :rss/link        "https://a.example/"
   :rss/description "A feed with a long past."
   :fh/complete     true
   :atom/links      [{:rel "self" :href "https://a.example/feed.xml" :type "application/rss+xml"}
                     {:rel "hub" :href "https://hub.example/"}
                     {:rel "Next" :href "https://old.example/2"}]
   :rss/items       (vec (for [i (range 7 0 -1)]
                           {:rss/title     (str "E" i)
                            :rss/guid      {:content (str i)}
                            :rss/enclosure {:url (str "https://media.example/" i ".mp3") :type "audio/mpeg" :length 10}}))})

(defn- shape
  "The URL, the titles of the items and the links of each document."
  [documents]
  (mapv (fn [{:keys [url feed]}]
          [url (mapv :rss/title (:rss/items feed)) (mapv (juxt :rel :href) (:atom/links feed))])
        documents))

(deftest the-pages-of-a-feed
  (testing "a paged feed"
    (is (= [["https://a.example/feed.xml" ["E7" "E6" "E5"]
             [["self" "https://a.example/feed.xml"]
              ["first" "https://a.example/feed.xml"]
              ["last" "https://a.example/feed.xml?page=3"]
              ["next" "https://a.example/feed.xml?page=2"]
              ["hub" "https://hub.example/"]]]
            ["https://a.example/feed.xml?page=2" ["E4" "E3" "E2"]
             [["self" "https://a.example/feed.xml?page=2"]
              ["first" "https://a.example/feed.xml"]
              ["last" "https://a.example/feed.xml?page=3"]
              ["previous" "https://a.example/feed.xml"]
              ["next" "https://a.example/feed.xml?page=3"]
              ["hub" "https://hub.example/"]]]
            ["https://a.example/feed.xml?page=3" ["E1"]
             [["self" "https://a.example/feed.xml?page=3"]
              ["first" "https://a.example/feed.xml"]
              ["last" "https://a.example/feed.xml?page=3"]
              ["previous" "https://a.example/feed.xml?page=2"]
              ["hub" "https://hub.example/"]]]]
           (shape (publish/pages long-feed {:page-size 3})))
        "the links of RFC 5005 take the place of those that the feed had, and the other links stay")
    (let [documents (publish/pages long-feed {:page-size 3})]
      (is (every? #(= "Long" (:rss/title (:feed %))) documents) "each page has the channel")
      (is (not-any? #(contains? (:feed %) :fh/complete) documents) "a page is not the whole feed")
      (is (every? #(= #{"application/rss+xml"} (set (map :type (take 3 (:atom/links (:feed %)))))) documents)
          "the media type of the self link of the feed")
      (is (= (:rss/items long-feed) (:rss/items (feed/join-pages (map :feed documents))))
          "the pages join to the feed again")))
  (testing "an archived feed"
    (is (= [["https://a.example/feed.xml" ["E7" "E6" "E5"]
             [["self" "https://a.example/feed.xml"]
              ["prev-archive" "https://a.example/feed.xml?archive=2"]
              ["hub" "https://hub.example/"]]]
            ["https://a.example/feed.xml?archive=2" ["E6" "E5" "E4"]
             [["self" "https://a.example/feed.xml?archive=2"]
              ["current" "https://a.example/feed.xml"]
              ["prev-archive" "https://a.example/feed.xml?archive=1"]
              ["hub" "https://hub.example/"]]]
            ["https://a.example/feed.xml?archive=1" ["E3" "E2" "E1"]
             [["self" "https://a.example/feed.xml?archive=1"]
              ["current" "https://a.example/feed.xml"]
              ["next-archive" "https://a.example/feed.xml?archive=2"]
              ["hub" "https://hub.example/"]]]]
           (shape (publish/pages long-feed {:page-size 3 :kind :archived}))))
    (let [documents (publish/pages long-feed {:page-size 3 :kind :archived})]
      (is (= [nil true true] (map (comp :fh/archive :feed) documents)) "an archive says that it is one")
      (is (= (:rss/items long-feed) (:rss/items (feed/join-pages (map :feed documents))))))
    (testing "an archive does not change when the feed gets an item"
      (let [archives (fn [feed] (into {} (map (juxt :url (comp :rss/items :feed))) (rest (publish/pages feed {:page-size 3 :kind :archived}))))
            grown    (update long-feed :rss/items #(into [{:rss/title "E8" :rss/guid {:content "8"}}] %))]
        (is (= (archives long-feed) (archives grown)))
        (is (= 3 (count (archives (update grown :rss/items #(into [{:rss/title "E10"} {:rss/title "E9"}] %)))))
            "ten items: three archives of three, and the newest item in the subscription document alone")))
    (testing "a feed that fits one document has no archive"
      (is (= [["https://a.example/feed.xml" ["E7" "E6" "E5" "E4" "E3" "E2" "E1"]
               [["self" "https://a.example/feed.xml"] ["hub" "https://hub.example/"]]]]
             (shape (publish/pages long-feed {:page-size 7 :kind :archived}))))))
  (testing "the URLs of the host"
    (is (= ["https://b.example/show.xml" "https://b.example/show/2.xml" "https://b.example/show/3.xml"]
           (map :url (publish/pages long-feed {:page-size   3
                                               :url         "https://b.example/show.xml"
                                               :page-url-fn #(str "https://b.example/show/" % ".xml")}))))
    (is (= ["https://a.example/feed.xml" "https://a.example/archive/1.xml"]
           (map :url (publish/pages long-feed {:page-size 6 :kind :archived
                                               :page-url-fn #(str "https://a.example/archive/" % ".xml")})))))
  (testing "the default size, and a feed without items"
    (is (= 100 publish/page-size))
    (is (= [1 100 100 50] [(count (publish/pages long-feed))
                           (count (:rss/items (:feed (first (publish/pages (assoc long-feed :rss/items (vec (repeat 250 {}))))))))
                           (count (:rss/items (:feed (second (publish/pages (assoc long-feed :rss/items (vec (repeat 250 {}))))))))
                           (count (:rss/items (:feed (last (publish/pages (assoc long-feed :rss/items (vec (repeat 250 {}))))))))]))
    (is (= [["https://a.example/feed.xml" [] [["self" "https://a.example/feed.xml"]
                                              ["first" "https://a.example/feed.xml"]
                                              ["last" "https://a.example/feed.xml"]
                                              ["hub" "https://hub.example/"]]]]
           (shape (publish/pages (dissoc long-feed :rss/items) {:page-size 3})))))
  (testing "what is a mistake of the host"
    (doseq [[what f] {"no URL"          #(publish/pages (dissoc long-feed :atom/links))
                      "a size of 0"     #(publish/pages long-feed {:page-size 0})
                      "a size of 2.5"   #(publish/pages long-feed {:page-size 2.5})
                      "a kind that is none" #(publish/pages long-feed {:kind :chunked})}]
      (is (thrown? #?(:clj Exception :cljs js/Error) (f)) what))))

(deftest the-urls-of-the-media
  (let [feed (assoc-in long-feed [:rss/items 0 :podcast/alternate-enclosures]
                       [{:type "audio/opus" :sources [{:uri "https://media.example/7.opus"} {:uri "ipfs://x"}]}
                        {:type "audio/aac"}])
        up   (publish/update-media-urls feed #(str % "#changed"))]
    (is (= "https://media.example/7.mp3#changed" (get-in up [:rss/items 0 :rss/enclosure :url])))
    (is (= ["https://media.example/7.opus#changed" "ipfs://x#changed"]
           (map :uri (get-in up [:rss/items 0 :podcast/alternate-enclosures 0 :sources]))))
    (is (= {:type "audio/aac"} (get-in up [:rss/items 0 :podcast/alternate-enclosures 1])))
    (is (= (:atom/links feed) (:atom/links up)) "the links of the feed stay")
    (is (= {:rss/title "No items"} (publish/update-media-urls {:rss/title "No items"} str)))
    (is (= 5 (get-in (publish/update-media-urls {:rss/items [{:rss/enclosure {:url 5}}]} str)
                     [:rss/items 0 :rss/enclosure :url]))
        "a URL that is no text stays as it is")))

(deftest the-urls-of-every-file
  (let [feed  (rss/parse (fixture "feeds/namespace-example.xml"))
        files (check/files feed {:max-items :all})
        up    (publish/update-file-urls feed #(str % "#copy"))]
    (is (= #{:media :image :transcript :chapters} (set (map :kind files))))
    (is (= (set (map #(str (:url %) "#copy") files))
           (set (map :url (check/files up {:max-items :all}))))
        "each file that a check fetches")
    (is (= (:atom/links feed) (:atom/links up)) "the links of the feed stay")
    (is (= (:podcast/value feed) (:podcast/value up)))
    (is (= {:rss/title "No items"} (publish/update-file-urls {:rss/title "No items"} str)))
    (is (= {:url 5} (get-in (publish/update-file-urls {:rss/items [{:podcast/chapters {:url 5}}]} str)
                            [:rss/items 0 :podcast/chapters]))
        "a URL that is no text stays as it is")))

(deftest the-feed-of-one-subscriber
  (let [feed    (assoc-in long-feed [:rss/items 0 :podcast/alternate-enclosures]
                          [{:type "audio/opus" :sources [{:uri "https://media.example/7.opus"} {:uri "ipfs://x"}]}])
        private (publish/for-subscriber feed "s3cr3t")]
    (testing "the token on the media files and on the documents of the feed"
      (is (= "https://media.example/7.mp3?token=s3cr3t" (get-in private [:rss/items 0 :rss/enclosure :url])))
      (is (= ["https://media.example/7.opus?token=s3cr3t" "ipfs://x"]
             (map :uri (get-in private [:rss/items 0 :podcast/alternate-enclosures 0 :sources])))
          "a URL that is no http URL gets none")
      (is (= [["self" "https://a.example/feed.xml?token=s3cr3t"] ["Next" "https://old.example/2?token=s3cr3t"]]
             (map (juxt :rel :href) (:atom/links private)))))
    (testing "the token on the other files"
      (let [feed    (-> feed
                        (assoc :itunes/image {:href "https://a.example/cover.jpg"})
                        (update-in [:rss/items 0] assoc
                                   :podcast/transcripts [{:url "https://a.example/7.vtt" :type "text/vtt"}]
                                   :podcast/chapters {:url "https://a.example/7.json"}))
            private (publish/for-subscriber feed "s3cr3t")]
        (is (= ["https://a.example/cover.jpg?token=s3cr3t"
                "https://a.example/7.vtt?token=s3cr3t"
                "https://a.example/7.json?token=s3cr3t"]
               [(get-in private [:itunes/image :href])
                (get-in private [:rss/items 0 :podcast/transcripts 0 :url])
                (get-in private [:rss/items 0 :podcast/chapters :url])]))))
    (testing "a feed that is closed to the public"
      (is (= {:podcast/blocks [{:content true}] :itunes/block true :googleplay/block true}
             (select-keys private [:podcast/blocks :itunes/block :googleplay/block])))
      (is (not-any? #(= "hub" (:rel %)) (:atom/links private)) "and that names no hub"))
    (testing "the pages of a private feed have the token too"
      (is (= ["https://a.example/feed.xml?token=s3cr3t"
              "https://a.example/feed.xml?token=s3cr3t&page=2"
              "https://a.example/feed.xml?token=s3cr3t&page=3"]
             (map :url (publish/pages private {:page-size 3})))))
    (testing "the choices of the host"
      (let [open (publish/for-subscriber feed "s3cr3t" {:token-param "key"
                                                        :own-pred    #(re-find #"^https://media\.example/" %)
                                                        :block?      false})
            path (publish/for-subscriber feed "abc" {:with-token-fn (fn [url token]
                                                                      (str/replace-first url ".example/" (str ".example/" token "/")))})]
        (is (= "https://media.example/7.mp3?key=s3cr3t" (get-in open [:rss/items 0 :rss/enclosure :url])))
        (is (= (:atom/links feed) (:atom/links open)) "the links that are not the host's, and the hub")
        (is (not (contains? open :itunes/block)))
        (is (= "https://media.example/abc/7.mp3" (get-in path [:rss/items 0 :rss/enclosure :url])))
        (is (= "https://a.example/abc/feed.xml" (:href (first (:atom/links path)))))
        (is (= "token" publish/token-param))))))

(def plain
  "A feed as a producer writes it from plain data."
  {:rss/title       "A show"
   :rss/link        "https://example.org/"
   :rss/description "About things."
   :rss/items       [{:rss/title     "One"
                      :rss/enclosure {:url "https://media.example.org/1.mp3"}}
                     {:rss/title     "Two"
                      :rss/guid      "two"
                      :rss/enclosure {:url    "https://media.example.org/2.M4A?x=1"
                                      :length 0}}]})

(def at #inst "2026-10-13T12:00:00Z")

(deftest the-defaults-of-a-feed
  (let [read  {"https://media.example.org/1.mp3" {:bytes 1234 :duration 61.6}}
        done  (publish/with-defaults plain {:url "https://example.org/feed.xml" :at at :media-fn read})
        [one two] (:rss/items done)]
    (is (= [{:rel "self" :href "https://example.org/feed.xml" :type "application/rss+xml"}] (:atom/links done)))
    (is (= "bab2ba12-9757-5da6-a770-0e70e6bcf214" (:podcast/guid done)) "the podcast:guid of the URL")
    (is (= [at "podcast-clj"] [(:rss/last-build-date done) (:rss/generator done)]))
    (is (= {:content "https://media.example.org/1.mp3" :is-perma-link false} (:rss/guid one))
        "the enclosure URL as the guid of an item without one")
    (is (= [{:url "https://media.example.org/1.mp3" :type "audio/mpeg" :length 1234} 62]
           [(:rss/enclosure one) (:itunes/duration one)])
        "from what media/read gave")
    (is (= ["two" "audio/mp4" 0] [(:rss/guid two) (get-in two [:rss/enclosure :type]) (get-in two [:rss/enclosure :length])])
        "the type by an extension in any case, and nothing that the app didn't read")
    (is (re-find #"<guid isPermaLink=\"false\">https://media.example.org/1.mp3</guid>" (rss/emit done)))
    (is (= done (publish/with-defaults done {:url "https://other.example/feed.xml" :generator "x"}))
        "nothing that the feed has is replaced")))

(deftest a-show-that-moves
  (let [old (-> (publish/with-defaults plain {:url "https://example.org/feed.xml" :at at})
                (dissoc :podcast/guid)
                (update :atom/links conj {:rel "hub" :href "https://hub.example.org/"} {:rel "next" :href "https://example.org/2"}
                        {:rel "payment" :href "https://example.org/pay"})
                (assoc :itunes/new-feed-url "https://example.org/feed.xml"))
        new (publish/imported old #(str/replace % "media.example.org" "cdn.new.example"))]
    (testing "the import"
      (is (= ["https://cdn.new.example/1.mp3" "https://cdn.new.example/2.M4A?x=1"]
             (map (comp :url :rss/enclosure) (:rss/items new))))
      (is (= [{:content "https://media.example.org/1.mp3" :is-perma-link false} "two"]
             (map :rss/guid (:rss/items new)))
          "the guids from before the rewrite")
      (is (= "bab2ba12-9757-5da6-a770-0e70e6bcf214" (:podcast/guid new)) "the podcast:guid of the old URL")
      (is (= [{:rel "payment" :href "https://example.org/pay"}] (:atom/links new)) "the old host's links gone")
      (is (nil? (:itunes/new-feed-url new)))
      (is (= ::publish/locked
             (try (publish/imported (assoc old :podcast/locked {:content true :owner "host@example.org"}) identity)
                  (catch #?(:clj Exception :cljs :default) e (:type (ex-data e)))))
          "but not a locked feed"))
    (testing "the move"
      (let [served (-> new
                       (publish/with-defaults {:url "https://new.example/feed.xml"})
                       (publish/moved "https://new.example/feed.xml"))]
        (is (= ["https://new.example/feed.xml" "bab2ba12-9757-5da6-a770-0e70e6bcf214"]
               [(:itunes/new-feed-url served) (:podcast/guid served)]))
        (is (nil? (:googleplay/new-feed-url (publish/moved (assoc old :googleplay/new-feed-url "x") "y"))))))))

(deftest serving-a-feed
  (let [feed   (-> (publish/with-defaults plain {:url "https://example.org/feed.xml" :at at})
                   (update :atom/links conj {:rel "hub" :href "https://hub.example.org/"}))
        ask    (fn [headers] (publish/response {:request-method :get :headers headers} feed))
        {:keys [status headers body]} (ask {})
        etag   (get headers "ETag")]
    (is (= 200 status))
    (is (= (rss/emit feed) body))
    (is (re-matches #"\"[0-9a-f]{32}\"" etag))
    (is (= {"Content-Type"  "application/rss+xml; charset=utf-8"
            "Cache-Control" "max-age=300"
            "Last-Modified" "Tue, 13 Oct 2026 12:00:00 GMT"
            "Link"          "<https://hub.example.org/>; rel=\"hub\", <https://example.org/feed.xml>; rel=\"self\""}
           (dissoc headers "ETag")))
    (is (= [304 nil] ((juxt :status :body) (ask {"if-none-match" (str "W/" etag)}))))
    (is (= 304 (:status (ask {"if-modified-since" "Tue, 13 Oct 2026 12:00:00 GMT"}))))
    (is (not (contains? (:headers (ask {"if-none-match" etag})) "Content-Type")))
    (is (not= etag (get-in (publish/response {:headers {}} (assoc feed :rss/title "B")) [:headers "ETag"]))
        "a new tag for a change")
    (let [cached (publish/response {:headers {}} {:rss/title "x"} {:body "<rss/>" :max-age 60})]
      (is (= ["<rss/>" "max-age=60" nil]
             [(:body cached) (get-in cached [:headers "Cache-Control"]) (get-in cached [:headers "Last-Modified"])])
          "the XML of a cache, and a feed without dates"))))
