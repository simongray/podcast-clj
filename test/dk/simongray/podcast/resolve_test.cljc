(ns dk.simongray.podcast.resolve-test
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.resolve :as resolve]))

(def example
  (delay (podcast/parse #?(:clj  (slurp (io/resource "feeds/namespace-example.xml"))
                           :cljs (rc/inline "feeds/namespace-example.xml")))))

(deftest text
  (is (= "A" (resolve/title {:rss/title "A" :itunes/title "B"})))
  (is (= "B" (resolve/title {:itunes/title "B"})))
  (is (= "<p>full</p>" (resolve/description {:content/encoded "<p>full</p>" :rss/description "short"})))
  (is (= "short" (resolve/description {:rss/description "short" :itunes/summary "s"})))
  (testing "authors, with names picked out of email fields"
    (is (= "John Doe" (resolve/author @example)))
    (is (= "Jane" (resolve/author {:dc/creator "Jane" :rss/author "a@b.c (Al)"})))
    (is (= "Al" (resolve/author {:rss/author "a@b.c (Al)"})))
    (is (= "ed@b.c" (resolve/author {:rss/managing-editor "ed@b.c"})))
    (is (nil? (resolve/author {}))))
  (testing "links, permalinks included"
    (is (= "https://x/page" (resolve/link {:rss/link "https://x/page"})))
    (is (= "https://x/1" (resolve/link {:rss/guid {:content "https://x/1"}})))
    (is (nil? (resolve/link {:rss/guid {:content "https://x/1" :is-perma-link false}})))
    (is (nil? (resolve/link {:rss/guid {:content "abc"}}))))
  (is (= #inst "2020-10-09T04:30:38Z" (resolve/published @example)))
  (is (nil? (resolve/published {:rss/pub-date "soon"}))))

(deftest artwork
  (let [f @example
        items (:rss/items f)]
    (testing "the largest podcast:image meant as artwork wins"
      (is (= "https://example.com/images/show/pci_avatar-massive.png" (resolve/artwork f)))
      (is (= "https://example.com/images/ep3/pci_avatar-massive.png" (resolve/artwork (first items)))))
    (testing "falling back through the vocabularies, and to the feed"
      (is (= "https://example.com/ep0001/artMd.jpg" (resolve/artwork (nth items 2))))
      (is (= "https://example.com/images/show/pci_avatar-massive.png" (resolve/artwork f {})))
      (is (= "https://i/rss.jpg" (resolve/artwork {:rss/image {:url "https://i/rss.jpg"}})))
      (is (= "https://i/gp.jpg" (resolve/artwork {:googleplay/image {:href "https://i/gp.jpg"} :rss/image {:url "https://i/rss.jpg"}})))
      (is (nil? (resolve/artwork {}))))
    (testing "shape and purpose are read"
      (is (= "https://i/sq.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/wide.jpg" :aspect-ratio "16/9"}
                                             {:href "https://i/sq.jpg"}]})))
      (is (= "https://i/it.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/banner.jpg" :purpose "banner"}]
                            :itunes/image {:href "https://i/it.jpg"}}))))
    (is (= 6 (count (resolve/images f))))
    (is (= #{"artwork"} (:purpose (first (resolve/images f)))))))

(deftest flags-and-numbers
  (is (false? (resolve/explicit? @example)))
  (is (true? (resolve/explicit? {:googleplay/explicit "yes"})))
  (is (false? (resolve/explicit? {:googleplay/explicit "clean"})))
  (is (nil? (resolve/explicit? {})))
  (is (true? (resolve/explicit? {:itunes/explicit true} {})))
  (is (false? (resolve/explicit? {:itunes/explicit true} {:itunes/explicit false})))
  (is (= 3793 (resolve/duration {:itunes/duration 3793})))
  (is (= 12 (resolve/duration {:itunes/duration "bad" :media/contents [{:duration 12}]})))
  (is (nil? (resolve/duration {})))
  (testing "numbering prefers the podcast namespace"
    (is (= {:type "full" :season 1 :season-name "Podcasting 2.0" :episode 3}
           (resolve/numbering (first (:rss/items @example)))))
    (is (= {:type "bonus" :season 2 :episode 7}
           (resolve/numbering {:itunes/season 2 :itunes/episode 7 :itunes/episode-type "Bonus"})))
    (is (= {:type "full" :episode 204 :episode-display "Ch.3"}
           (resolve/numbering {:podcast/episode {:content 204 :display "Ch.3"} :itunes/episode 3})))
    (is (= {:type "full"} (resolve/numbering {})))))

(deftest media
  (let [item (first (:rss/items @example))
        all  (resolve/enclosures item)]
    (is (= 11 (count all)))
    (is (= {:url "https://example.com/file-03.mp3" :length 43200000 :type "audio/mpeg" :source :enclosure}
           (first all)))
    (is (= {:url "ipfs://someRandomMpegFile03" :source :alternate :type "audio/mpeg"
            :length 43200000 :bitrate 128000 :default true :title "Standard"}
           (nth all 2)))
    (is (= "https://example.com/file-03.mp3" (:url (resolve/enclosure item))))
    (testing "without an enclosure, the default alternate, then anything"
      (is (= "https://a/hi.opus"
             (:url (resolve/enclosure {:podcast/alternate-enclosures
                                    [{:type "audio/opus" :sources [{:uri "https://a/lo.opus"}]}
                                     {:type "audio/opus" :default true :sources [{:uri "https://a/hi.opus"}]}]}))))
      (is (= {:url "https://m/1.mp4" :type "video/mp4" :length 99 :source :media}
             (resolve/enclosure {:media/contents [{:url "https://m/1.mp4" :type "video/mp4" :file-size 99}]})))
      (is (nil? (resolve/enclosure {}))))
    (testing "transcripts by preference"
      (is (= "https://example.com/episode1/transcript.vtt" (:url (resolve/transcript item))))
      (is (= "https://example.com/ep3/transcript.txt" (:url (resolve/transcript item ["text/plain"]))))
      (is (= "text/plain" (:type (resolve/transcript item ["application/json"]))))
      (is (nil? (resolve/transcript {}))))
    (is (= "https://example.com/ep3_chapters.json" (resolve/chapters-url item)))
    (is (= [{:start-time 0 :title "Intro"}]
           (:chapters (resolve/embedded-chapters {:psc/chapters {:chapters [{:start "00:00:00.000" :title "Intro"}]}}))))
    (is (nil? (resolve/embedded-chapters {})))))

(deftest people-and-inheritance
  (let [f    @example
        item (first (:rss/items f))]
    (is (= [{:name "Adam Curry" :role "host" :group "cast"
             :href "https://www.podchaser.com/creators/adam-curry-107ZzmWE5f"
             :img "https://example.com/images/adamcurry.jpg"}
            {:name "Dave Jones" :role "guest" :group "cast"
             :href "https://github.com/daveajones/" :img "https://example.com/images/davejones.jpg"}
            {:name "Becky Smith" :role "cover art designer" :group "visuals"
             :href "https://example.com/artist/beckysmith"}]
           (resolve/people item)))
    (testing "the item's people replace the feed's; an item without any inherits"
      (is (= ["Adam Curry" "Dave Jones" "Becky Smith"] (map :name (resolve/people f item))))
      (is (= ["Terry"] (map :name (resolve/people {:podcast/people [{:content "Terry"}]} {})))))
    (testing "inherited values"
      (is (= [{:url "https://example.com/donate" :content "Support the show!"}] (resolve/funding f item)))
      (is (= [{:url "https://x" :content "Give"}] (resolve/funding f {:podcast/funding [{:url "https://x" :content "Give"}]})))
      (is (= "my-podcast-license-v1" (:content (resolve/license f item))))
      (is (= "matrix" (:protocol (resolve/chat f item))))
      (is (= "Birmingham Civil Rights Museum" (:content (first (resolve/locations f item)))))
      (is (= "Austin, TX" (:content (first (resolve/locations f {}))))))
    (testing "value blocks"
      (is (= 49 (get-in (resolve/value f item) [:recipients 0 :split])))
      (is (= 99 (get-in (resolve/value f {}) [:recipients 0 :split])))
      (is (= 99 (get-in (resolve/value f) [:recipients 0 :split]))))))

(deftest the-feed-as-a-whole
  (let [f @example]
    (is (= "podcast" (resolve/medium f)))
    (is (= "podcast" (resolve/medium {})))
    (is (not (resolve/list-feed? f)))
    (is (resolve/list-feed? {:podcast/medium "musicL"}))
    (is (resolve/list-feed? {:podcast/medium "mixed"}))
    (is (not (resolve/complete? f)))
    (is (resolve/complete? {:itunes/complete true}))
    (is (resolve/complete? {:podcast/update-frequency {:complete true :content "Done"}}))
    (is (resolve/locked? f))
    (is (not (resolve/locked? {})))
    (is (= "https://new/feed.xml" (resolve/new-feed-url {:itunes/new-feed-url "https://new/feed.xml"})))
    (is (= "https://a/rss" (resolve/self-url {:atom/links [{:href "https://hub" :rel "hub"} {:href "https://a/rss" :rel "Self"}]})))
    (is (nil? (resolve/self-url f)))
    (is (= [["Technology"] ["News" "Tech News"]] (resolve/categories f)))))
