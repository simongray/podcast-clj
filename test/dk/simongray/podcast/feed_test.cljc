(ns dk.simongray.podcast.feed-test
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.xml :as xml]))

(def example
  (delay #?(:clj  (slurp (io/resource "feeds/namespace-example.xml"))
            :cljs (rc/inline "feeds/namespace-example.xml"))))

(deftest namespace-example
  (let [f (podcast/parse @example)]
    (testing "the parse is clean"
      (is (= [] (podcast/report f))))
    (testing "scalars"
      (is (= "Podcasting 2.0 Namespace Example" (:rss/title f)))
      (is (= "en-US" (:rss/language f)))
      (is (= #inst "2020-10-09T04:30:38Z" (:rss/pub-date f)))
      (is (false? (:itunes/explicit f)))
      (is (= "podcast" (:podcast/medium f))))
    (testing "attributed elements are maps with :content"
      (is (= {:owner "podcastowner@example.com" :content true} (:podcast/locked f)))
      (is (= [{:url "https://example.com/donate" :content "Support the show!"}] (:podcast/funding f)))
      (is (= [{:content true} {:id "google" :content false} {:id "amazon" :content false}]
             (:podcast/blocks f))))
    (testing "containers"
      (is (= {:name "John Doe" :email "johndoe@example.com"} (:itunes/owner f)))
      (is (= [{:text "Technology"} {:text "News" :categories [{:text "Tech News"}]}]
             (:itunes/categories f)))
      (is (= 2 (count (get-in f [:podcast/podroll :remote-items]))))
      (is (= "publisher" (get-in f [:podcast/publisher :remote-item :medium])))
      (is (= 99 (get-in f [:podcast/values 0 :recipients 0 :split])))
      (is (= 5.0E-8 (get-in f [:podcast/values 0 :suggested]))))
    (testing "live items are items with status and times"
      (let [live (first (:podcast/live-items f))]
        (is (= "live" (:status live)))
        (is (= #inst "2021-09-26T13:30:00Z" (:start live)))
        (is (= "Podcasting 2.0 Live Show" (:rss/title live)))
        (is (= 2 (count (:podcast/content-links live))))))
    (testing "items"
      (let [item (first (:rss/items f))]
        (is (= 3 (count (:rss/items f))))
        (is (= {:is-perma-link true :content "https://example.com/ep0003"} (:rss/guid item)))
        (is (= 43200000 (get-in item [:rss/enclosure :length])))
        (is (= {:name "Podcasting 2.0" :content 1} (:podcast/season item)))
        (is (= {:content 3} (:podcast/episode item)))
        (is (= 2 (count (:podcast/transcripts item))))
        (is (= "es" (get-in item [:podcast/transcripts 1 :language])))
        (is (= 5 (count (:podcast/alternate-enclosures item))))
        (is (= {:type "sri" :value "sha384-ExVqijgYHm15PqQqdXfW95x+Rs6C+d6E/ICxyQOeFevnxNLR/wtJNrNYTjIysUBo"}
               (get-in item [:podcast/alternate-enclosures 4 :integrity])))
        (is (= [{:start-time 33.833 :duration 60}] (:podcast/soundbites item)))
        (is (= "cover art designer" (get-in item [:podcast/people 2 :role])))))))

(deftest round-trip
  (let [f (podcast/parse @example)]
    (is (= f (podcast/parse (podcast/emit f))))))

(deftest unknown-elements-survive
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title>
                           <acme:thing id=\"1\">x</acme:thing><item><title>E</title><mystery>?</mystery></item></channel></rss>")]
    (is (= [[:acme/thing {:id "1"} "x"]] (:xml/unknown f)))
    (is (= [[:mystery {} "?"]] (:xml/unknown (first (:rss/items f)))))
    (let [out (podcast/emit f)]
      (is (re-find #"xmlns:acme=\"https://acme.example\"" out))
      (is (re-find #"<acme:thing id=\"1\">x</acme:thing>" out))
      (is (re-find #"<mystery>\?</mystery>" out)))))

(deftest coercion-never-throws
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>T</title>
                           <item><title>E</title><itunes:episode>seven</itunes:episode><pubDate>soon</pubDate></item></channel></rss>")
        item (first (:rss/items f))]
    (is (= "seven" (:itunes/episode item)))
    (is (= "soon" (:rss/pub-date item)))
    (is (= #{[:rss/items 0 :itunes/episode] [:rss/items 0 :rss/pub-date]}
           (into #{} (map :path) (filter (comp #{:feed/invalid-value} :id) (podcast/report f)))))))

(deftest markup-where-text-belongs
  (let [f (podcast/parse "<rss version=\"2.0\"><channel><title>T</title>
                           <description><p>Hi <b>there</b></p></description>
                           <item><title>Ep <em>1</em></title></item></channel></rss>")]
    (testing "an XHTML description is kept as markup, other text is flattened"
      (is (= "<p>Hi <b>there</b></p>" (:rss/description f)))
      (is (= "Ep 1" (:rss/title (first (:rss/items f)))))
      (is (= [[:rss/description] [:rss/items 0 :rss/title]]
             (map :path (filter (comp #{:feed/markup-in-text} :id) (podcast/report f))))))
    (is (= f (podcast/parse (podcast/emit f))))))

(deftest duplicates-and-empties
  (let [f (podcast/parse "<rss version=\"2.0\"><channel><title>A</title><title>B</title><link></link></channel></rss>")]
    (is (= "A" (:rss/title f)))
    (is (not (contains? f :rss/link)))
    (is (= #{:feed/duplicate-element :feed/empty-element} (into #{} (map :id) (podcast/report f))))))

(deftest identities
  (is (= "https://example.com/ep0003"
         (feed/item-guid (first (:rss/items (podcast/parse @example))))))
  (is (= "https://a/1.mp3" (feed/item-guid {:rss/enclosure {:url "https://a/1.mp3"}})))
  (is (= "y0ur-gu1d-g035-h3r3" (feed/feed-guid (podcast/parse @example) "https://x")))
  (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (feed/feed-guid {} "https://podnews.net/rss"))))

(deftest diffing
  (let [item   (fn [id title] {:rss/guid {:content id} :rss/title title})
        before {:rss/title "Show" :rss/last-build-date #inst "2026-01-01T00:00:00Z"
                :rss/items [(item "1" "One") (item "2" "Two") (item "3" "Three") {:rss/title "no identity"}]}
        after  {:rss/title "Show" :rss/last-build-date #inst "2026-02-01T00:00:00Z"
                :rss/items [(item "4" "Four") (item "1" "One") (item "2" "Two, revised")]}
        d      (feed/diff before after)]
    (is (= [(item "4" "Four")] (:added d)))
    (is (= [(item "3" "Three")] (:removed d)))
    (is (= [{:before (item "2" "Two") :after (item "2" "Two, revised")}] (:changed d)))
    (is (= 1 (:unchanged d)))
    (testing "timestamps alone do not count as a channel change"
      (is (false? (:channel-changed? d)))
      (is (true? (:channel-changed? (feed/diff before (assoc after :rss/title "Renamed"))))))))

(deftest not-rss
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an RSS"
                        (feed/parse (xml/parse "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>")))))
