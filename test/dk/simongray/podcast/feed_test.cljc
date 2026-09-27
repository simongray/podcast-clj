(ns dk.simongray.podcast.feed-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.podcast.xml :as xml]))

(def example
  (delay (fixture "feeds/namespace-example.xml")))

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
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>A</title><title>B</title><link></link>
                           <item><title>E</title><enclosure url=\"https://a/1.mp3\" type=\"\"/><itunes:image href=\"\"/></item></channel></rss>")
        item (first (:rss/items f))]
    (is (= "A" (:rss/title f)))
    (is (not (contains? f :rss/link)))
    (testing "a blank optional attribute is absent; a blank required one is kept for the validator"
      (is (= {:url "https://a/1.mp3"} (:rss/enclosure item)))
      (is (= {:href ""} (:itunes/image item))))
    (is (= #{:feed/duplicate-element :feed/empty-element :feed/empty-attribute}
           (into #{} (map :id) (podcast/report f))))))

(deftest identities
  (is (= "https://example.com/ep0003"
         (feed/item-guid (first (:rss/items (podcast/parse @example))))))
  (is (= "https://a/1.mp3" (feed/item-guid {:rss/enclosure {:url "https://a/1.mp3"}})))
  (is (= "y0ur-gu1d-g035-h3r3" (feed/feed-guid (podcast/parse @example) "https://x")))
  (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (feed/feed-guid {} "https://podnews.net/rss"))))

(deftest diffing
  (let [item   (fn [id title] {:rss/guid {:content id} :rss/title title})
        live   (fn [status] {:rss/guid {:content "L"} :status status :rss/title "Live show"})
        before {:rss/title "Show" :rss/last-build-date #inst "2026-01-01T00:00:00Z"
                :rss/items [(item "1" "One") (item "2" "Two") (item "3" "Three") {:rss/title "no identity"}]
                :podcast/live-items [(live "pending")]}
        after  {:rss/title "Show" :rss/last-build-date #inst "2026-02-01T00:00:00Z"
                :rss/items [(item "4" "Four") (item "1" "One") (item "2" "Two, revised")]
                :podcast/live-items [(live "live")]}
        d      (feed/diff before after)]
    (is (= [(item "4" "Four")] (:added d)))
    (is (= [(item "3" "Three")] (:removed d)))
    (is (= [{:before (item "2" "Two") :after (item "2" "Two, revised")}] (:changed d)))
    (is (= 1 (:unchanged d)))
    (testing "live items are compared on their own"
      (is (= [{:before (live "pending") :after (live "live")}] (:changed (:live-items d))))
      (is (= {:added [] :removed [] :changed [] :unchanged 0} (:live-items (feed/diff {} {})))))
    (testing "timestamps and live items alone do not count as a channel change"
      (is (false? (:channel-changed? d)))
      (is (true? (:channel-changed? (feed/diff before (assoc after :rss/title "Renamed"))))))))

(deftest summarizing-diagnostics
  (let [diagnostics [{:id :a :level :warning :message "m" :path [:rss/items 0]}
                  {:id :a :level :warning :message "m" :path [:rss/items 1]}
                  {:id :b :level :info :tag :x}]
        summary  (podcast/summarize diagnostics)]
    (is (= [[:a 2] [:b 1]] (map (juxt :id :count) summary)))
    (is (= {:id :a :level :warning :message "m" :path [:rss/items 0]} (:example (first summary))))
    (is (= [] (podcast/summarize [])))))

(deftest structured-attribute-values
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                           <podcast:updateFrequency rrule=\"FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261231T000000Z\" dtstart=\"2026-01-05T18:00:00Z\">Twice a week</podcast:updateFrequency>
                           <podcast:location geo=\"geo:30.2672,-97.7431;u=100\" osm=\"R113314#4\">Austin, TX</podcast:location>
                           <item><title>E</title><podcast:location geo=\"geo:nowhere\">Nowhere</podcast:location></item></channel></rss>")]
    (is (= {:freq "WEEKLY" :byday ["MO" "WE"] :until #inst "2026-12-31T00:00:00Z"}
           (:rrule (:podcast/update-frequency f))))
    (is (= {:lat 30.2672 :lon -97.7431 :uncertainty 100} (:geo (first (:podcast/locations f)))))
    (is (= {:type "R" :id 113314 :revision 4} (:osm (first (:podcast/locations f)))))
    (testing "a space after the comma, as seen in the wild, still reads"
      (is (= {:lat 47.423043 :lon 8.5670553}
             (get-in (podcast/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                                      <podcast:location geo=\"geo:47.4230430, 8.5670553\">Zürich</podcast:location></channel></rss>")
                     [:podcast/locations 0 :geo]))))
    (testing "what does not read stays a string, with a diagnostic"
      (is (= "geo:nowhere" (:geo (first (:podcast/locations (first (:rss/items f)))))))
      (is (some #(= [:rss/items 0 :podcast/locations 0 :geo] (:path %)) (podcast/report f))))
    (is (= f (podcast/parse (podcast/emit f))))))

(deftest not-rss
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an RSS"
                        (feed/parse (xml/parse "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>")))))

(deftest undeclared-attributes-survive
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>T</title>
                           <itunes:image href=\"https://a/i.jpg\" width=\"600\"/></channel></rss>")]
    (testing "an attribute the registry does not declare keeps its XML name and string value"
      (is (= {:href "https://a/i.jpg" :width "600"} (:itunes/image f))))
    (testing "and is written back, even when another element declares that name"
      (is (re-find #"width=\"600\"" (podcast/emit f)))
      (is (= f (podcast/parse (podcast/emit f)))))))

(deftest invalid-values-are-written-back-as-they-came
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                           <podcast:locked>false</podcast:locked></channel></rss>"
                         {:quirks? false})]
    (is (= {:content "false"} (:podcast/locked f)))
    (is (re-find #"<podcast:locked>false</podcast:locked>" (podcast/emit f)))
    (is (= f (podcast/parse (podcast/emit f) {:quirks? false})))))

(deftest media-rss-nesting
  (let [f    (podcast/parse "<rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\"><channel><title>T</title>
                              <item><title>E</title>
                                <media:content url=\"https://a/1.mp3\" type=\"audio/mpeg\">
                                  <media:player url=\"https://a/p\"/>
                                  <media:title type=\"plain\">Ep</media:title>
                                </media:content>
                                <media:group>
                                  <media:content url=\"https://a/1.mp4\" type=\"video/mp4\" isDefault=\"true\"/>
                                  <media:content url=\"https://a/1.webm\" type=\"video/webm\"/>
                                  <media:thumbnail url=\"https://a/t.jpg\"/>
                                </media:group>
                                <media:credit role=\"author\">Ann</media:credit>
                              </item></channel></rss>")
        item (first (:rss/items f))]
    (testing "optional elements inside a media object get plain keys"
      (is (= {:url "https://a/p"} (get-in item [:media/contents 0 :player])))
      (is (= {:type "plain" :content "Ep"} (get-in item [:media/contents 0 :title]))))
    (testing "a group holds renditions"
      (is (= ["https://a/1.mp4" "https://a/1.webm"] (map :url (get-in item [:media/group :contents]))))
      (is (= [{:url "https://a/t.jpg"}] (get-in item [:media/group :thumbnails]))))
    (is (= [{:role "author" :content "Ann"}] (:media/credits item)))
    (is (empty? (mapcat :xml/unknown (cons item (:media/contents item)))))
    (is (= f (podcast/parse (podcast/emit f))))))

(deftest elements-the-wild-adds
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"
                                xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"
                                xmlns:spotify=\"http://www.spotify.com/ns/rss\"><channel><title>T</title>
                           <author>Radio Semilla</author>
                           <content:encoded><![CDATA[<p>About</p>]]></content:encoded>
                           <itunes:applepodcastsverify>d33a9400-27ed-11f1-ad48-cd4166db9095</itunes:applepodcastsverify>
                           <spotify:limit recentCount=\"25\"/>
                           <item><title>E</title><author>host@example.com</author></item></channel></rss>")]
    (is (= "Radio Semilla" (:rss/author f)))
    (is (= "<p>About</p>" (:content/encoded f)))
    (is (= "d33a9400-27ed-11f1-ad48-cd4166db9095" (:itunes/applepodcastsverify f)))
    (is (= {:recent-count 25} (:spotify/limit f)))
    (is (= "host@example.com" (:rss/author (first (:rss/items f)))))
    (is (empty? (:xml/unknown f)))
    (is (= f (podcast/parse (podcast/emit f))))))

(deftest rfc2822-fields-keep-whole-seconds
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                           <podcast:trailer url=\"https://a/t.mp3\" pubdate=\"2024-08-02T19:35:20.740Z\">Trailer</podcast:trailer></channel></rss>")]
    (testing "an ISO date with milliseconds in an RFC 2822 field loses only what the field cannot carry"
      (is (= #inst "2024-08-02T19:35:20.000Z" (get-in f [:podcast/trailers 0 :pubdate])))
      (is (= f (podcast/parse (podcast/emit f)))))))

(deftest namespaces-of-your-own
  (let [f   {:rss/version "2.0" :rss/title "T" :xml/unknown [[:acme/tracking {:id "42"}]]}
        out (podcast/emit f {:namespaces {:acme "https://acme.example/rss-extensions"}})]
    (is (re-find #"xmlns:acme=\"https://acme.example/rss-extensions\"" out))
    (is (re-find #"<acme:tracking id=\"42\"/>" out))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (podcast/emit f))
        "an alias no one declares is refused")))

(deftest a-registry-of-your-own
  (let [tags (into spec/tags [{:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                               :attrs {:scheme {}} :content :decimal :doc "Acme's rating of the show."}
                              {:tag :acme/hd :key :acme/hd? :parent #{:item} :count :one :content :yes-no}])
        xml  "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title>
                <acme:rating scheme=\"stars\">4.5</acme:rating>
                <item><title>E</title><acme:hd>true</acme:hd></item></channel></rss>"
        f    (podcast/parse xml {:registry tags})
        item (first (:rss/items f))]
    (testing "the elements are read by the extended registry, its quirks included"
      (is (= {:scheme "stars" :content 4.5} (:acme/rating f)))
      (is (true? (:acme/hd? item)))
      (is (some (comp #{:quirk/yes-no-value} :id) (podcast/report f)))
      (is (empty? (:xml/unknown f)))
      (is (empty? (:xml/unknown item))))
    (testing "and written back by it, the namespace declared"
      (let [out (podcast/emit f {:registry tags})]
        (is (re-find #"xmlns:acme=\"https://acme.example\"" out))
        (is (re-find #"<acme:rating scheme=\"stars\">4.5</acme:rating>" out))
        (is (re-find #"<acme:hd>yes</acme:hd>" out))
        (is (= f (podcast/parse out {:registry tags})))))
    (testing "the library's own registry keeps them as unknown elements"
      (is (= [[:acme/rating {:scheme "stars"} "4.5"]] (:xml/unknown (podcast/parse xml)))))
    (testing "an indexed registry serves as well as the entries, and a bad entry is refused"
      (is (= f (podcast/parse xml {:registry (spec/registry tags)})))
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (spec/registry [{:tag :acme/rating}]))))))
