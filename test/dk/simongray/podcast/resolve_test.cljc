(ns dk.simongray.podcast.resolve-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.resolve :as resolve]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def example
  (delay (podcast/parse (fixture "feeds/namespace-example.xml"))))

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
    (is (= "Al" (resolve/author {:rss/author "  a@b.c(Al) "})))
    (is (= "Al (Jr)" (resolve/author {:rss/author "a@b.c (Al (Jr))"})))
    (is (= "Al (Jr)" (resolve/author {:rss/author "Al (Jr)"})) "no address, no name to pick out")
    (is (nil? (resolve/author {})))
    (let [ats (str (apply str (repeat 100000 "@")) " x")]
      (is (= ats (resolve/author {:rss/author ats})) "read in linear time")))
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
                            :itunes/image {:href "https://i/it.jpg"}})))
      (is (= "https://i/300.jpg"
             (resolve/artwork {:podcast/images [{:href "https://i/big.jpg" :width "big"}
                                             {:href "https://i/300.jpg" :width 300}]}))
          "a width that did not read is no width"))
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
  (is (= 12 (resolve/duration {:media/group {:contents [{:duration 12}]}})))
  (is (nil? (resolve/duration {})))
  (testing "numbering prefers the podcast namespace"
    (is (= {:type "full" :season 1 :season-name "Podcasting 2.0" :episode 3}
           (resolve/numbering (first (:rss/items @example)))))
    (is (= {:type "bonus" :season 2 :episode 7}
           (resolve/numbering {:itunes/season 2 :itunes/episode 7 :itunes/episode-type "Bonus"})))
    (is (= {:type "full" :episode 204 :episode-display "Ch.3"}
           (resolve/numbering {:podcast/episode {:content 204 :display "Ch.3"} :itunes/episode 3})))
    (is (= {:type "full" :season 2 :episode 7}
           (resolve/numbering {:podcast/season {:content "Two"} :itunes/season 2
                               :podcast/episode {:content "7a"} :itunes/episode 7}))
        "a number that did not read leaves the other namespace to answer")
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
    (testing "transcripts by type preference"
      (is (= "https://example.com/episode1/transcript.vtt" (:url (resolve/transcript item))))
      (is (= "https://example.com/ep3/transcript.txt" (:url (resolve/transcript nil item {:types ["text/plain"]}))))
      (is (= "text/plain" (:type (resolve/transcript nil item {:types ["application/json"]}))))
      (is (nil? (resolve/transcript {})))
      (is (nil? (resolve/transcript {} {}))))
    (testing "transcripts by language, with the feed's language for unlabelled ones"
      (let [multilingual {:podcast/transcripts [{:url "en" :type "text/vtt" :language "en-US"}
                                                {:url "da" :type "text/vtt" :language "da"}
                                                {:url "none" :type "application/x-subrip"}]}]
        (is (= "en" (:url (resolve/transcript multilingual))))
        (is (= "da" (:url (resolve/transcript nil multilingual {:languages ["da" "en"]}))))
        (is (= "en" (:url (resolve/transcript nil multilingual {:languages ["EN"]}))))
        (is (= "none" (:url (resolve/transcript nil multilingual {:languages ["sv"]})))
            "an unlabelled file might be Swedish; a labelled one is certainly not")
        (is (= "none" (:url (resolve/transcript {:rss/language "sv-SE"} multilingual {:languages ["sv"]}))))
        (is (= "en" (:url (resolve/transcript {:rss/language "fr"} multilingual {:languages ["en"]}))))))
    (testing "transcripts with timings"
      (let [mixed {:podcast/transcripts [{:url "html" :type "text/html"}
                                         {:url "captions" :type "text/plain" :rel "captions"}
                                         {:url "vtt" :type "text/vtt"}]}]
        (is (= "vtt" (:url (resolve/transcript nil mixed {:timed? true}))))
        (is (= "captions" (:url (resolve/transcript nil mixed {:timed? true :types ["text/plain" "text/html"]}))))
        (is (= "html" (:url (resolve/transcript nil mixed {:types ["text/html"]}))))
        (is (= "srt" (:url (resolve/transcript {:podcast/transcripts [{:url "txt" :type "text/plain"}
                                                                      {:url "srt" :type "application/srt"}]})))
            "SRT by any of its names ranks above plain text")))
    (is (= "https://example.com/ep3_chapters.json" (resolve/chapters-url item)))
    (is (= [{:start-time 0 :title "Intro"}]
           (:chapters (resolve/embedded-chapters {:psc/chapters {:chapters [{:start "00:00:00.000" :title "Intro"}]}}))))
    (is (nil? (resolve/embedded-chapters {})))
    (testing "chapters inferred from the show notes"
      (is (= ["Intro" "Outro"]
             (map :title (:chapters (resolve/inferred-chapters {:rss/description "<p>00:00 Intro<br>45:00 Outro</p>"})))))
      (is (nil? (resolve/inferred-chapters item))))))

(deftest enclosures-by-preference
  (let [item {:rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}
              :podcast/alternate-enclosures [{:type "audio/opus" :bitrate 32000 :sources [{:uri "https://a/lo.opus"}]}
                                             {:type "audio/opus" :bitrate 96000 :default true :sources [{:uri "https://a/hi.opus"}]}
                                             {:type "video/mp4" :bitrate 2000000 :sources [{:uri "https://a/1.mp4"}]}]}]
    (is (= "https://a/1.mp3" (:url (resolve/enclosure item))))
    (is (= "https://a/hi.opus" (:url (resolve/enclosure item {:types ["audio/opus"]}))))
    (is (= "https://a/lo.opus" (:url (resolve/enclosure item {:types ["audio/opus"] :max-bitrate 64000}))))
    (is (= "https://a/1.mp4" (:url (resolve/enclosure item {:types ["video/mp4" "audio/opus"]}))))
    (is (= "https://a/1.mp3" (:url (resolve/enclosure item {:types ["audio/flac"]}))))))

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

(deftest renditions-in-a-media-group
  (let [item {:media/group {:contents [{:url "https://a/1.mp4" :type "video/mp4" :is-default true}
                                       {:url "https://a/1.webm" :type "video/webm"}]}}]
    (is (= ["https://a/1.mp4" "https://a/1.webm"] (map :url (resolve/enclosures item))))
    (is (= "https://a/1.mp4" (:url (resolve/enclosure item))))))

(deftest live-items-in-order
  (let [pending {:status "pending" :start #inst "2026-10-01T18:00:00Z" :rss/title "Next week"}
        live    {:status "live" :start #inst "2026-09-25T18:00:00Z" :rss/title "Now"}
        ended   {:status "ended" :start #inst "2026-09-18T18:00:00Z" :end #inst "2026-09-18T19:00:00Z"
                 :rss/title "Last week"}
        older   {:status "ended" :start #inst "2026-09-11T18:00:00Z" :rss/title "Older"}
        feed    {:podcast/live-items [older pending ended live]}]
    (is (= ["Now" "Next week" "Last week" "Older"] (map :rss/title (resolve/live-items feed))))
    (is (= [] (resolve/live-items {})))
    (testing "on air by status, or by the clock when the status lags"
      (is (resolve/live? live #inst "2026-01-01T00:00:00Z"))
      (is (resolve/live? pending #inst "2026-10-01T18:30:00Z"))
      (is (not (resolve/live? pending #inst "2026-10-01T17:00:00Z")))
      (is (not (resolve/live? ended #inst "2026-09-18T18:30:00Z"))))))

(deftest the-next-episode
  (let [feed {:podcast/update-frequency {:rrule {:freq "WEEKLY" :byday ["MO"]}
                                         :dtstart #inst "2026-01-05T18:00:00Z" :content "Mondays"}}]
    (is (= #inst "2026-01-12T18:00:00Z" (resolve/next-episode feed #inst "2026-01-06T00:00:00Z")))
    (is (nil? (resolve/next-episode (assoc-in feed [:podcast/update-frequency :complete] true) #inst "2026-01-06T00:00:00Z")))
    (is (nil? (resolve/next-episode {} #inst "2026-01-06T00:00:00Z")))
    (testing "without dtstart, the latest item anchors the rule"
      (is (= #inst "2026-01-19T09:00:00Z"
             (resolve/next-episode {:podcast/update-frequency {:rrule {:freq "WEEKLY"}}
                                    :rss/items [{:rss/pub-date #inst "2026-01-12T09:00:00Z"}
                                                {:rss/pub-date #inst "2026-01-05T09:00:00Z"}]}
                                   #inst "2026-01-12T10:00:00Z")))
      (is (= #inst "2026-01-19T09:00:00Z"
             (resolve/next-episode {:podcast/update-frequency {:rrule {:freq "WEEKLY"}}
                                    :rss/items [{:rss/pub-date #inst "2062-01-12T09:00:00Z"}
                                                {:rss/pub-date #inst "2026-01-12T09:00:00Z"}]}
                                   #inst "2026-01-12T10:00:00Z"))
          "but not an item dated after the moment asked about"))))

(deftest when-to-check-again
  (let [at     #inst "2026-03-01T12:00:00Z"
        weekly (fn [& dates] {:rss/items (map #(hash-map :rss/pub-date %) dates)})]
    (is (= #inst "2026-03-01T12:30:00Z" (resolve/next-check {:rss/ttl 30} at)))
    (is (= #inst "2026-03-02T00:00:00Z" (resolve/next-check {:sy/update-period "daily" :sy/update-frequency 2} at)))
    (testing "the rule wins over everything"
      (is (= #inst "2026-03-02T18:00:00Z"
             (resolve/next-check {:rss/ttl 30
                                  :podcast/update-frequency {:rrule {:freq "WEEKLY" :byday ["MO"]}
                                                             :dtstart #inst "2026-01-05T18:00:00Z"}}
                                 at))))
    (testing "the cadence of recent items, and a late show checked at its cadence"
      (is (= #inst "2026-03-05T09:00:00Z"
             (resolve/next-check (weekly #inst "2026-02-26T09:00:00Z" #inst "2026-02-19T09:00:00Z" #inst "2026-02-12T09:00:00Z") at)))
      (is (= #inst "2026-03-08T12:00:00Z"
             (resolve/next-check (weekly #inst "2026-02-22T09:00:00Z" #inst "2026-02-15T09:00:00Z" #inst "2026-02-08T09:00:00Z") at))))
    (testing "an item dated in the future, a scheduled episode or a mistake, does not count"
      (is (= #inst "2026-03-05T09:00:00Z"
             (resolve/next-check (weekly #inst "2062-02-26T09:00:00Z" #inst "2026-02-26T09:00:00Z"
                                         #inst "2026-02-19T09:00:00Z" #inst "2026-02-12T09:00:00Z")
                                 at))))
    (testing "no answer lies more than a year ahead, however large the ttl or the gap"
      (is (= #inst "2027-03-01T18:00:00Z" (resolve/next-check {:rss/ttl 8999999999999999} at)))
      (is (= #inst "2027-03-01T18:00:00Z" (resolve/next-check (weekly #inst "2026-02-26T09:00:00Z" #inst "2016-02-26T09:00:00Z") at))))
    (is (nil? (resolve/next-check (weekly #inst "2026-02-22T09:00:00Z") at)))
    (is (nil? (resolve/next-check {:rss/ttl 30 :itunes/complete true} at)))))

(deftest blocking
  (is (resolve/blocked? {:itunes/block true} :apple))
  (is (not (resolve/blocked? {:itunes/block true} :spotify)))
  (is (resolve/blocked? {:googleplay/block true} "google"))
  (is (resolve/blocked? {:podcast/blocks [{:content true}]} :spotify))
  (is (not (resolve/blocked? {:podcast/blocks [{:content true} {:id "google" :content false}]} :google)))
  (is (resolve/blocked? {:podcast/blocks [{:id "Amazon" :content true}]} :amazon))
  (is (not (resolve/blocked? {:podcast/blocks [{:id "amazon" :content true}]} :apple)))
  (is (not (resolve/blocked? {} :apple)))
  (testing "the specification example blocks everyone but Google and Amazon"
    (is (resolve/blocked? @example :spotify))
    (is (not (resolve/blocked? @example :google)))
    (is (not (resolve/blocked? @example :amazon)))))

(deftest listening-order
  (let [ep   (fn [title date & {:keys [season episode type]}]
               (cond-> {:rss/title title :rss/pub-date date}
                 season  (assoc :itunes/season season)
                 episode (assoc :itunes/episode episode)
                 type    (assoc :itunes/episode-type type)))
        show {:rss/items [(ep "E1" #inst "2026-01-01T00:00:00Z" :episode 1)
                          (ep "E2" #inst "2026-01-08T00:00:00Z" :episode 2)
                          (ep "Trailer" #inst "2025-12-20T00:00:00Z" :type "trailer")
                          (ep "E3" #inst "2026-01-15T00:00:00Z" :episode 3)]}]
    (testing "episodic: newest first, the trailer ahead"
      (is (= ["Trailer" "E3" "E2" "E1"] (map :rss/title (resolve/episodes show))))
      (is (= [{:items (resolve/episodes show)}] (resolve/seasons show))))
    (testing "serial: by season and number, trailers and bonuses where Apple puts them"
      (let [serial {:itunes/type "serial"
                    :rss/items [(ep "S2E1" #inst "2026-03-01T00:00:00Z" :season 2 :episode 1)
                                (ep "S1E2" #inst "2026-01-08T00:00:00Z" :season 1 :episode 2)
                                (assoc (ep "S1E1" #inst "2026-01-01T00:00:00Z" :episode 1)
                                       :podcast/season {:content 1 :name "First"})
                                (ep "S1 trailer" #inst "2025-12-20T00:00:00Z" :season 1 :type "trailer")
                                (ep "S1E1 bonus" #inst "2026-01-03T00:00:00Z" :season 1 :episode 1 :type "bonus")
                                (ep "Show trailer" #inst "2025-12-01T00:00:00Z" :type "trailer")
                                (ep "Show bonus" #inst "2026-04-01T00:00:00Z" :type "bonus")
                                (ep "S2 bonus" #inst "2026-03-05T00:00:00Z" :season 2 :type "bonus")]}]
        (is (= ["Show trailer" "S1 trailer" "S1E1" "S1E1 bonus" "S1E2" "S2E1" "S2 bonus" "Show bonus"]
               (map :rss/title (resolve/episodes serial))))
        (is (= [nil 1 2] (map :season (resolve/seasons serial))))
        (is (= "First" (:name (second (resolve/seasons serial)))))
        (is (= ["Show trailer" "Show bonus"] (map :rss/title (:items (first (resolve/seasons serial))))))))))

(deftest everything-at-once
  (let [feed {:rss/title       "Show"
              :itunes/explicit true
              :itunes/image    {:href "https://s/art.png"}
              :podcast/people  [{:content "Host"}]}
        item {:rss/title       "Ep"
              :itunes/title    "Ep"
              :rss/pub-date    #inst "2026-01-01T00:00:00Z"
              :itunes/duration 60}
        show (resolve/properties feed)
        ep   (resolve/properties feed item)]
    (testing "every answer in one map, plain keys, nothing unanswered"
      (is (= {:title "Show" :artwork "https://s/art.png" :explicit? true
              :people [{:name "Host" :role "host" :group "cast"}]
              :images [{:href "https://s/art.png" :source :itunes}]
              :medium "podcast" :list-feed? false :complete? false :locked? false}
             show))
      (is (= {:title "Ep" :published #inst "2026-01-01T00:00:00Z" :duration 60
              :numbering {:type "full"} :artwork "https://s/art.png" :explicit? true
              :people [{:name "Host" :role "host" :group "cast"}]}
             ep)
          "inheriting from the feed as the accessors do")
      (is (= "Ep" (:title (merge item ep))))
      (is (= "Ep" (:rss/title (merge item ep))) "merged beside the raw fields"))
    (testing "and where each answer came from"
      (is (= {:title    [[:rss/title]]
              :artwork  [[:itunes/image]]
              :explicit? [[:itunes/explicit]]
              :people   [[:podcast/people]]
              :images   [[:itunes/image]]}
             (resolve/sources feed))
          "defaults name no source")
      (is (= {:title     [[:item :itunes/title] [:item :rss/title]]
              :published [[:item :rss/pub-date]]
              :duration  [[:item :itunes/duration]]
              :artwork   [[:feed :itunes/image]]
              :explicit? [[:feed :itunes/explicit]]
              :people    [[:feed :podcast/people]]}
             (resolve/sources feed item))
          "two fields that agree are both named; the feed's where the item inherits"))
    (testing "on a real feed"
      (let [f (deref example)
            i (first (:rss/items f))]
        (is (= "Podcasting 2.0 Namespace Example" (:title (resolve/properties f))))
        (is (every? (complement namespace) (keys (resolve/properties f i))))
        (is (= [[:item :rss/title]] (:title (resolve/sources f i))))
        (is (nil? (:numbering (resolve/sources f i))) "a composite names no source")))))
