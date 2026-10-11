(ns dk.simongray.podcast.feed-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.qname-hiccup :as xml]))

(def example
  (delay (fixture "feeds/namespace-example.xml")))

(deftest namespace-example
  (let [f (rss/parse @example)]
    (testing "the parse is clean"
      (is (= [] (rss/report f))))
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
  (let [f (rss/parse @example)]
    (is (= f (rss/parse (rss/emit f))))))

(deftest a-document-that-may-not-be-a-feed
  (is (= "T" (:rss/title (:feed (rss/try-parse "<rss version=\"2.0\"><channel><title>T</title></channel></rss>")))))
  (let [{:keys [feed error] :as result} (rss/try-parse "<html><body><p>Not a feed</p></body></html>"
                                                       {:url "https://a.example/"})]
    (is (= [:error] (keys result)) "the diagnostic, and nothing thrown")
    (is (nil? feed))
    (is (= [:fetch/malformed :warning "https://a.example/"] ((juxt :id :level :url) error)))
    (is (string? (:reason error))))
  (is (not (contains? (:error (rss/try-parse "")) :url)) "no URL unless the options give one")
  (testing "a feed in another format"
    (is (= [:fetch/unsupported-format "Atom"]
           ((juxt :id :format) (:error (rss/try-parse "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>T</title></feed>")))))
    (is (= "RSS 1.0" (:format (:error (rss/try-parse (str "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                                                          " xmlns=\"http://purl.org/rss/1.0/\"><channel/></rdf:RDF>"))))))
    (is (= :fetch/malformed (:id (:error (rss/try-parse "<feed><title>T</title></feed>"))))
        "a feed element outside the Atom namespace")))

(deftest unknown-elements-survive
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title>
                           <acme:thing id=\"1\">x</acme:thing><item><title>E</title><mystery>?</mystery></item></channel></rss>")]
    (is (= [[:acme/thing {:id "1"} "x"]] (:xml/unknown f)))
    (is (= [[:mystery {} "?"]] (:xml/unknown (first (:rss/items f)))))
    (let [out (rss/emit f)]
      (is (re-find #"xmlns:acme=\"https://acme.example\"" out))
      (is (re-find #"<acme:thing id=\"1\">x</acme:thing>" out))
      (is (re-find #"<mystery>\?</mystery>" out)))))

(deftest coercion-never-throws
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>T</title>
                           <item><title>E</title><itunes:episode>seven</itunes:episode><pubDate>soon</pubDate></item></channel></rss>")
        item (first (:rss/items f))]
    (is (= "seven" (:itunes/episode item)))
    (is (= "soon" (:rss/pub-date item)))
    (is (= #{[:rss/items 0 :itunes/episode] [:rss/items 0 :rss/pub-date]}
           (into #{} (map :path) (filter (comp #{:feed/invalid-value} :id) (rss/report f)))))))

(deftest markup-where-text-belongs
  (let [f (rss/parse "<rss version=\"2.0\"><channel><title>T</title>
                           <description><p>Hi <b>there</b></p></description>
                           <item><title>Ep <em>1</em></title></item></channel></rss>")]
    (testing "an XHTML description is kept as markup, other text is flattened"
      (is (= "<p>Hi <b>there</b></p>" (:rss/description f)))
      (is (= "Ep 1" (:rss/title (first (:rss/items f)))))
      (is (= [[:rss/description] [:rss/items 0 :rss/title]]
             (map :path (filter (comp #{:feed/markup-in-text} :id) (rss/report f))))))
    (is (= f (rss/parse (rss/emit f)))))
  (testing "a space between two inline elements is kept"
    (let [f (rss/parse "<rss version=\"2.0\"><channel><title>T</title>
                             <description><p><b>Hello</b> <i>world</i></p></description>
                             <item><title><b>Big</b> <i>News</i></title></item></channel></rss>")]
      (is (= "<p><b>Hello</b> <i>world</i></p>" (:rss/description f)))
      (is (= "Big News" (:rss/title (first (:rss/items f)))))
      (is (= f (rss/parse (rss/emit f)))))))

(deftest emitting-odd-values
  (let [f   {:rss/version "2.0" :rss/title "T" :rss/copyright nil :itunes/explicit :no
             :rss/items [{:rss/title "E" :explicit? false :images [{:href "x"}] :title "merged" :duration 3793
                          :rss/enclosure "https://a/1.mp3"}]}
        out (rss/emit f)]
    (is (re-find #"<itunes:explicit>no</itunes:explicit>" out) "not true, as any truthy value was")
    (is (not (re-find #"copyright" out)) "a nil value is no value")
    (is (re-find #"<item title=\"merged\">" out) "a string under an XML name is an attribute a parse could have kept")
    (is (not (re-find #"explicit\?|images|duration=|enclosure" out))
        "a key that makes no attribute, or a value that does not, is left out, as is an enclosure that is not a map")
    (is (= "E" (:rss/title (first (:rss/items (rss/parse out))))) "and the document reads")))

(deftest what-the-model-drops-is-reported
  (let [f   (rss/parse "<rss version=\"2.0\" xml:lang=\"en\"><channel foo=\"1\">stray text<title xml:lang=\"en\">T</title></channel></rss>")
        ids (set (map (juxt :id :path :tag :attributes) (rss/report f)))]
    (is (= "T" (:rss/title f)))
    (is (contains? ids [:feed/dropped-attributes [] :rss [:xml/lang]]))
    (is (contains? ids [:feed/dropped-attributes [] :channel [:foo]]))
    (is (contains? ids [:feed/dropped-attributes [:rss/title] nil [:xml/lang]]))
    (is (some (comp #{:feed/unexpected-text} :id) (rss/report f)))))

(deftest namespaces-given-to-emit-win
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><channel><title>T</title><dc:creator>C</dc:creator></channel></rss>")]
    (is (re-find #"xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" (rss/emit f)))
    (is (re-find #"xmlns:dc=\"http://purl.org/dc/terms/\"" (rss/emit f {:namespaces {:dc "http://purl.org/dc/terms/"}}))
        "over the URI of the library"))
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:dc=\"http://purl.org/dc/terms/\"><channel><title>T</title><dc:creator>C</dc:creator></channel></rss>")]
    (is (re-find #"xmlns:dc=\"http://purl.org/dc/terms/\"" (rss/emit f)) "as the one a feed declared does")))

#?(:clj
   (deftest an-undeclared-prefix-on-the-jvm
     (let [f   (rss/parse "<rss version=\"2.0\"><channel><title>T</title><feedburner:origLink>https://x/1</feedburner:origLink></channel></rss>")
           out (rss/emit f)]
       (is (= [[:feedburner/origLink {} "https://x/1"]] (:xml/unknown f)) "the parse keeps it for reading")
       (is (not (re-find #"feedburner" out)) "emit leaves it out, as XML cannot write it without a URI")
       (is (= "T" (:rss/title (rss/parse out)))))))

(deftest no-break-spaces-and-big-numbers
  (let [f     (rss/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>Show&#160;</title>
                               <item><title>E</title><guid>&#160;</guid><itunes:episode>5&#160;</itunes:episode>
                               <enclosure url=\"https://a/1.mp3\" length=\"1\" type=\"audio/mpeg\"/></item>
                               <item><title>F</title><itunes:episode>99999999999999999999</itunes:episode></item></channel></rss>")
        [a b] (:rss/items f)]
    (testing "a no-break space is white space, on the JVM as in JavaScript"
      (is (= "Show" (:rss/title f)))
      (is (= 5 (:itunes/episode a)))
      (is (= "https://a/1.mp3" (guid/item-guid a)) "a guid of white space alone is none"))
    (testing "a number too large for both platforms stays a string, and the report says so"
      (is (= "99999999999999999999" (:itunes/episode b)))
      (is (some #(= [:rss/items 1 :itunes/episode] (:path %)) (rss/report f))))))

(deftest duplicates-and-empties
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>A</title><title>B</title><link></link>
                           <item><title>E</title><guid isPermaLink=\"\">g</guid><enclosure url=\"https://a/1.mp3\" length=\"1\" type=\"\"/><itunes:image href=\"\"/></item></channel></rss>")
        item (first (:rss/items f))]
    (is (= "A" (:rss/title f)))
    (is (not (contains? f :rss/link)))
    (testing "a blank optional attribute is absent; a blank required one is kept for the validator"
      (is (= {:content "g"} (:rss/guid item)))
      (is (= {:url "https://a/1.mp3" :length 1 :type ""} (:rss/enclosure item)))
      (is (= {:href ""} (:itunes/image item))))
    (is (= #{:feed/duplicate-element :feed/empty-element :feed/empty-attribute}
           (into #{} (map :id) (rss/report f))))
    (is (= [[:title 1 "<title> occurs more than once, and only the first is kept"]]
           (->> (rss/report f)
                (filter (comp #{:feed/duplicate-element} :id))
                (map (juxt :tag :count :message))))
        "naming the element, with the number of the others")))

(deftest identities
  (is (= "https://example.com/ep0003"
         (guid/item-guid (first (:rss/items (rss/parse @example))))))
  (is (= "https://a/1.mp3" (guid/item-guid {:rss/enclosure {:url "https://a/1.mp3"}})))
  (is (= (guid/from-url "https://x") (guid/feed-guid (rss/parse @example) "https://x"))
      "a declared guid that isn't a UUID gives way to that of the URL")
  (is (= "917393e3-1b1e-5cef-ace4-edaa54e1f810"
         (guid/feed-guid {:podcast/guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"} "https://x")))
  (is (= ["k" "i"] (guid/episode-id (guid/with-key {:podcast/guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"} "k")
                                    "https://x" {:rss/guid {:content "i"}}))
      "the key that a library keeps the feed under, over what the feed says")
  (is (= "9b024349-ccf0-5f69-a609-6b82873eab3c" (guid/feed-guid {} "https://podnews.net/rss"))))

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
      (is (= {:added [] :removed [] :renamed [] :changed [] :unchanged 0} (:live-items (feed/diff {} {})))))
    (testing "timestamps and live items alone do not count as a channel change"
      (is (false? (:channel-changed? d)))
      (is (true? (:channel-changed? (feed/diff before (assoc after :rss/title "Renamed"))))))
    (testing "of the items that share an identity, the first counts"
      (let [twice {:rss/items [(item "1" "One") (item "1" "One again") (item "2" "Two")]}]
        (is (= {:added [] :removed [] :changed [] :unchanged 2}
               (select-keys (feed/diff twice twice) [:added :removed :changed :unchanged])))
        (is (= [(item "1" "One") (item "2" "Two")] (:added (feed/diff {} twice))))))))

(deftest renamed-items
  (let [item   (fn [id url title day]
                 {:rss/guid {:content id} :rss/enclosure {:url url} :rss/title title :rss/pub-date (time/->inst (* day 86400000))})
        guids  (fn [items] (mapv (comp :content :rss/guid) items))
        pairs  (fn [d] (mapv (fn [{:keys [before after]}] (guids [before after])) (:renamed d)))
        before {:rss/items [(item "a3" "https://e.com/3.mp3" "Three" 3)
                            (item "a2" "https://e.com/2.mp3" "Two" 2)
                            (item "a1" "https://e.com/1.mp3" "One" 1)]}
        after  {:rss/items [(item "b3" "https://e.com/3.mp3" "Three, revised" 3)
                            (item "b2" "https://op3.dev/e/e.com/2.mp3" " Two " 2.5)
                            (item "b1" "https://op3.dev/e/e.com/1.mp3" "One" 3.5)]}
        d      (feed/diff before after)]
    (is (= [["a3" "b3"] ["a2" "b2"]] (pairs d)) "by the enclosure URL, or else the title and a date a day apart at most")
    (is (= [["b1"] ["a1"]] [(guids (:added d)) (guids (:removed d))]) "and the rest added and removed")
    (testing "one item to one, since some feeds give two items one enclosure URL"
      (let [twice (assoc-in before [:rss/items 1 :rss/enclosure :url] "https://e.com/3.mp3")]
        (is (= [["a2" "b2"]] (pairs (feed/diff twice after))))
        (is (= [["a2" "b2"]] (pairs (feed/diff before (update after :rss/items conj (item "b4" "https://e.com/3.mp3" "Four" 4)))))
            "on either side")))))

(deftest joining-pages
  (let [item  (fn [id] {:rss/guid {:content id} :rss/title id})
        page  (fn [title ids report]
                (with-meta {:rss/title title :rss/items (mapv item ids)} {:podcast/report report}))
        feed  (feed/join-pages [(page "First" ["3" "3" "2"] [{:id :a}])
                                (page "Second" ["2" "1"] [{:id :b}])
                                (page "Third" [] [])])]
    (is (= "First" (:rss/title feed)) "the channel of the first page")
    (is (= ["3" "3" "2" "1"] (map :rss/title (:rss/items feed)))
        "an item that an earlier page holds is left out, and a page is left as it is")
    (is (= [{:id :a} {:id :b}] (feed/report feed)))
    (is (not (contains? (feed/join-pages [(with-meta {:rss/title "Empty"} {:podcast/report []})
                                          (page "Also empty" [] [])])
                        :rss/items))
        "no items, no key")
    (is (nil? (feed/join-pages [])) "no pages, no feed")
    (testing "the report names each item where the feed has it"
      (let [feed (feed/join-pages [(page "First" ["5" "4"] [{:id :a :path [:rss/items 1 :rss/pub-date]}
                                                          {:id :b :path [:rss/title]}])
                                   (page "Second" ["4" "3" "2"] [{:id :c :path [:rss/items 0 :rss/pub-date]}
                                                                {:id :d :path [:rss/items 2 :rss/enclosure :length]}
                                                                {:id :e :path [:rss/title]}
                                                                {:id :h :path []}
                                                                {:id :f}])
                                   (page "Third" ["1"] [{:id :g :path [:rss/items 0]}])])]
        (is (= [{:id :a :path [:rss/items 1 :rss/pub-date]}
                {:id :b :path [:rss/title]}
                {:id :d :path [:rss/items 3 :rss/enclosure :length]}
                {:id :f}
                {:id :g :path [:rss/items 4]}]
               (feed/report feed))
            "and it leaves out what is about an item or a channel that the feed does not hold")
        (is (= ["4" "2" "1"] (map #(:rss/title (get-in feed (take 2 (:path %)))) (filter (comp #{:a :d :g} :id) (feed/report feed)))))))))

(deftest the-elements-of-rfc-5005
  (let [xml  (str "<rss version=\"2.0\" xmlns:fh=\"http://purl.org/syndication/history/1.0\"><channel><title>T</title>"
                  "<fh:complete/><fh:archive a=\"1\">text</fh:archive></channel></rss>")
        feed (rss/parse xml)]
    (is (= {:fh/complete true :fh/archive true} (select-keys feed [:fh/complete :fh/archive]))
        "an element that is true because it is there")
    (is (= [:feed/dropped-attributes :feed/unexpected-text] (map :id (rss/report feed)))
        "what such an element holds is no part of it")
    (is (re-find #"<fh:complete/>\s*<fh:archive/>" (rss/emit feed)))
    (is (re-find #"xmlns:fh=\"http://purl.org/syndication/history/1.0\"" (rss/emit {:rss/title "T" :fh/archive true}))
        "a feed that was not parsed declares the namespace too")
    (is (not (re-find #"fh:" (rss/emit {:rss/title "T" :fh/complete false :fh/archive nil})))
        "false is an element that is not there")
    (let [invalid #(filter (comp #{:validate/invalid-value} :id) (validate/validate %))]
      (is (empty? (invalid feed)))
      (is (= [[:fh/complete]] (map :path (invalid (assoc feed :fh/complete false))))
          "and it is no value of the model, which has the key or has it not"))))

(deftest structured-attribute-values
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                           <podcast:updateFrequency rrule=\"FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261231T000000Z\" dtstart=\"2026-01-05T18:00:00Z\">Twice a week</podcast:updateFrequency>
                           <podcast:location geo=\"geo:30.2672,-97.7431;u=100\" osm=\"R113314#4\">Austin, TX</podcast:location>
                           <item><title>E</title><podcast:location geo=\"geo:nowhere\">Nowhere</podcast:location></item></channel></rss>")]
    (is (= {:freq "WEEKLY" :byday ["MO" "WE"] :until #inst "2026-12-31T00:00:00Z"}
           (:rrule (:podcast/update-frequency f))))
    (is (= {:lat 30.2672 :lon -97.7431 :uncertainty 100} (:geo (first (:podcast/locations f)))))
    (is (= {:type "R" :id 113314 :revision 4} (:osm (first (:podcast/locations f)))))
    (testing "a space after the comma, as seen in the wild, still reads"
      (is (= {:lat 47.423043 :lon 8.5670553}
             (get-in (rss/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                                      <podcast:location geo=\"geo:47.4230430, 8.5670553\">Zürich</podcast:location></channel></rss>")
                     [:podcast/locations 0 :geo]))))
    (testing "what does not read stays a string, with a diagnostic"
      (is (= "geo:nowhere" (:geo (first (:podcast/locations (first (:rss/items f)))))))
      (is (some #(= [:rss/items 0 :podcast/locations 0 :geo] (:path %)) (rss/report f))))
    (is (= f (rss/parse (rss/emit f))))))

(deftest not-rss
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an RSS"
                        (feed/parse (xml/parse "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>")))))

(deftest undeclared-attributes-survive
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>T</title>
                           <itunes:image href=\"https://a/i.jpg\" width=\"600\"/></channel></rss>")]
    (testing "an attribute the registry does not declare keeps its XML name and string value"
      (is (= {:href "https://a/i.jpg" :width "600"} (:itunes/image f))))
    (testing "and is written back, even when another element declares that name"
      (is (re-find #"width=\"600\"" (rss/emit f)))
      (is (= f (rss/parse (rss/emit f)))))))

(deftest invalid-values-are-written-back-as-they-came
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                           <podcast:locked>false</podcast:locked></channel></rss>"
                         {:quirks? false})]
    (is (= {:content "false"} (:podcast/locked f)))
    (is (re-find #"<podcast:locked>false</podcast:locked>" (rss/emit f)))
    (is (= f (rss/parse (rss/emit f) {:quirks? false})))))

(deftest media-rss-nesting
  (let [f    (rss/parse "<rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\"><channel><title>T</title>
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
    (is (= f (rss/parse (rss/emit f))))))

(deftest elements-the-wild-adds
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"
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
    (is (= f (rss/parse (rss/emit f))))))

(deftest rfc2822-fields-keep-whole-seconds
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>
                           <podcast:trailer url=\"https://a/t.mp3\" pubdate=\"2024-08-02T19:35:20.740Z\">Trailer</podcast:trailer></channel></rss>")]
    (testing "an ISO date with milliseconds in an RFC 2822 field loses only what the field cannot carry"
      (is (= #inst "2024-08-02T19:35:20.000Z" (get-in f [:podcast/trailers 0 :pubdate])))
      (is (= f (rss/parse (rss/emit f)))))))

(deftest namespaces-of-your-own
  (let [f   {:rss/version "2.0" :rss/title "T" :xml/unknown [[:acme/tracking {:id "42"}]]}
        out (rss/emit f {:namespaces {:acme "https://acme.example/rss-extensions"}})]
    (is (re-find #"xmlns:acme=\"https://acme.example/rss-extensions\"" out))
    (is (re-find #"<acme:tracking id=\"42\"/>" out))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (rss/emit f))
        "an alias no one declares is refused")))

(deftest a-registry-of-your-own
  (let [elements (into spec/elements [{:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                                       :attrs {:scheme {}} :content :decimal :doc "Acme's rating of the show."}
                                      {:tag :acme/hd :key :acme/hd? :parent #{:item} :count :one :content :yes-no}])
        xml      "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title>
                <acme:rating scheme=\"stars\">4.5</acme:rating>
                <item><title>E</title><acme:hd>true</acme:hd></item></channel></rss>"
        f        (rss/parse xml {:registry elements})
        item     (first (:rss/items f))]
    (testing "the elements are read by the extended registry, its quirks included"
      (is (= {:scheme "stars" :content 4.5} (:acme/rating f)))
      (is (true? (:acme/hd? item)))
      (is (some (comp #{:quirk/yes-no-value} :id) (rss/report f)))
      (is (empty? (:xml/unknown f)))
      (is (empty? (:xml/unknown item))))
    (testing "and written back by it, the namespace declared"
      (let [out (rss/emit f {:registry elements})]
        (is (re-find #"xmlns:acme=\"https://acme.example\"" out))
        (is (re-find #"<acme:rating scheme=\"stars\">4.5</acme:rating>" out))
        (is (re-find #"<acme:hd>yes</acme:hd>" out))
        (is (= f (rss/parse out {:registry elements})))))
    (testing "the library's own registry keeps them as unknown elements"
      (is (= [[:acme/rating {:scheme "stars"} "4.5"]] (:xml/unknown (rss/parse xml)))))
    (testing "an indexed registry serves as well as the entries, and a bad entry is refused"
      (is (= f (rss/parse xml {:registry (spec/registry elements)})))
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (spec/registry [{:tag :acme/rating}]))))))
