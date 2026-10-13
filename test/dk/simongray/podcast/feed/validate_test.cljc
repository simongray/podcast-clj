(ns dk.simongray.podcast.feed.validate-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def example
  (delay (fixture "feeds/namespace-example.xml")))

(defn- ids
  [diagnostics]
  (frequencies (map :id diagnostics)))

(deftest registry-checks
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">
                            <channel><title>T</title><link>https://t</link><description>d</description>
                              <podcast:guid>not-a-uuid</podcast:guid>
                              <podcast:medium>vinyl</podcast:medium>
                              <podcast:images srcset=\"https://a 1w\"/>
                              <podcast:value type=\"lightning\" method=\"keysend\"/>
                              <podcast:person role=\"Wizard\">Merlin</podcast:person>
                              <podcast:funding>no url</podcast:funding>
                              <itunes:type>weekly</itunes:type>
                              <item><title>E</title><enclosure url=\"https://a/1.mp3\" length=\"1\" type=\"audio/mpeg\"/>
                                <podcast:transcript url=\"http://a/t.vtt\" type=\"text/vtt\"/>
                                <podcast:alternateEnclosure type=\"audio/opus\"/>
                                <itunes:episode>seven</itunes:episode></item>
                            </channel></rss>")
        v (validate/validate f)]
    (is (= {:validate/invalid-guid 1 :validate/invalid-enum 2 :validate/deprecated 1
            :validate/missing-child 2 :validate/unknown-role 1 :validate/missing-attribute 1
            :validate/http-url 1 :validate/invalid-value 1}
           (ids v)))
    (is (not (validate/valid? f)))
    (is (= [:podcast/funding 0 :url] (:path (first (filter (comp #{:validate/missing-attribute} :id) v)))))
    (is (not-any? #(and (contains? % :replaced-by) (nil? (:replaced-by %))) v)
        "a deprecation names its replacement or says nothing")))

(deftest the-shapes-of-languages-emails-and-media-types
  (let [f (rss/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">
                        <channel><title>T</title><link>https://t</link><description>d</description>
                          <language>en_US</language>
                          <managingEditor>geo@herald.example (George Matesky)</managingEditor>
                          <webMaster>Karly Nimmo</webMaster>
                          <item><title>E</title><author>ann@example.org</author>
                            <enclosure url=\"https://a/1.mp3\" length=\"1\" type=\"audio/mpeg; codecs=mp3\"/>
                            <podcast:transcript url=\"https://a/t.vtt\" type=\"text/vtt\" language=\"en-US\"/>
                            <podcast:chapters url=\"https://a/c.json\" type=\"json\"/></item>
                        </channel></rss>")
        v (validate/validate f)]
    (is (= {:validate/invalid-language 1 :validate/invalid-email 1 :validate/invalid-media-type 1} (ids v)))
    (is (= [[[:rss/web-master] "Karly Nimmo" :info]
            [[:rss/language] "en_US" :warning]
            [[:rss/items 0 :podcast/chapters :type] "json" :warning]]
           (map (juxt :path :value :level) (sort-by :id v)))
        "an address with a name in parentheses, as RSS 2.0 writes it, and a media type with parameters pass"))
  (let [typed #(-> {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                    :rss/items [{:rss/title     "E"
                                 :rss/enclosure {:url "https://a/1.mp3" :length 1 :type "audio/mpeg"}
                                 :podcast/transcripts [{:url "https://a/t" :type %}]}]}
                   validate/validate
                   ids)]
    (is (= {} (typed "text/vtt; charset=utf-8")) "a transcript type of the list, with a charset")
    (is (= {:validate/invalid-enum 1} (typed "text/markdown")) "but not one off the list")
    (is (= {:validate/invalid-media-type 1} (typed "text/vtt;"))
        "a parameter left empty, which the grammar of HTTP doesn't take")
    (is (= {} (typed " text/vtt ")) "a type with spaces around it")))

(deftest languages-by-rfc-5646
  (let [problem (fn [language]
                  (->> {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                        :rss/language language}
                       validate/validate
                       (filter (comp #{:validate/invalid-language} :id))
                       (map (juxt :reason :subtag))
                       first))]
    (is (nil? (problem "da-DK")))
    (is (nil? (problem " en-us ")) "whatever the case, and with spaces around it")
    (is (= [:invalid-subtag "en_US"] (problem "en_US")))
    (is (= [:unexpected-subtag "US"] (problem "en-US-US")))
    (is (= [:empty-extension "a"] (problem "en-a")))
    (is (every? nil? (map problem ["en-GB" "da" "en-US"])))
    (is (= [[:unregistered-subtag "UK"] [:unregistered-subtag "xx"]] (map problem ["en-UK" "xx"]))
        "a region or a language that the registry doesn't have, e.g. the UK, which is GB")
    (is (= [:not-iso-639 nil] (problem "english")))
    (is (= [:not-iso-639 nil] (problem "i-klingon")) "a grandfathered tag has no language")))

(deftest a-publisher-needs-its-remote-item
  (let [feed (fn [publisher]
               {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                :podcast/publisher publisher})]
    (is (= {:validate/missing-child 1} (ids (validate/validate (feed {})))))
    (is (= {:validate/invalid-structure 1}
           (ids (validate/validate (feed {:remote-item {:feed-guid "g" :medium "podcast"}})))))
    (is (= {} (ids (validate/validate (feed {:remote-item {:feed-guid "g" :medium "publisher"}})))))))

(deftest unknown-requirements-are-refused
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (validate/validate {:rss/version "2.0"} {:requirements :bogus}))))

(deftest rss-basics
  (let [v (validate/validate (rss/parse "<rss version=\"0.91\"><channel><item><enclosure url=\"https://a\" length=\"1\" type=\"audio/mpeg\"/></item></channel></rss>"))]
    (is (= {:validate/rss-version 1 :validate/missing-element 4} (ids v))))
  (is (= {:validate/missing-enclosure 1}
         (ids (validate/validate (rss/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><item><title>E</title></item></channel></rss>"))))))

(deftest requirements
  (let [f (rss/parse @example)]
    (testing "the specification example against PSP-1"
      (let [v (validate/validate f {:requirements :psp-1})]
        (is (= 1 (:validate/required (ids v))))
        (is (= [:atom/links] (:path (first (filter (comp #{:validate/required} :id) v)))))
        (is (= [[[:rss/items] 3]]
               (map (juxt :path :count) (filter (comp #{:validate/recommended} :id) v)))
            "one for the duration that no item has")))
    (testing "and against Apple"
      (is (validate/valid? f {:requirements :apple}))
      (is (= [[:rss/items 0 :rss/guid]]
             (->> (validate/validate (update-in f [:rss/items 0] dissoc :rss/guid) {:requirements :apple})
                  (filter (comp #{:validate/required} :id))
                  (map :path)))
          "Apple requires the guid of an episode"))
    (testing "an enclosure without an absolute URL"
      (let [errors (fn [url]
                     (->> (validate/validate (assoc-in f [:rss/items 0 :rss/enclosure :url] url) {:requirements :apple})
                          (filter (comp #{:error} :level))
                          (map (juxt :id :path))))]
        (is (= [[:validate/missing-attribute [:rss/items 0 :rss/enclosure :url]]] (errors ""))
            "a blank URL is missing, and the requirement doesn't count it twice")
        (is (= [[:validate/required [:rss/items 0 :rss/enclosure]]] (errors "episode.mp3"))
            "a relative URL, which nothing can play")))
    (testing "a channel description that RSS 2.0 requires too"
      (is (= [[:validate/missing-element [:rss/description]]]
             (->> (validate/validate (dissoc f :rss/description) {:requirements :apple})
                  (filter (comp #{:error} :level))
                  (map (juxt :id :path))))
          "is one error, not two"))
    (testing "and against the requirements that an app gives"
      (let [own {:name    "A directory of our own"
                 :channel {:required [:podcast/guid :itunes/new-feed-url] :recommended [:podcast/locked]}
                 :item    {:required [:podcast/transcripts]}}
            v   (validate/validate f {:requirements own})]
        (is (= [[:itunes/new-feed-url]] (map :path (filter (comp #{:validate/required} :id) v)))
            "what the channel lacks")
        (is (nil? (:validate/recommended (ids v))))
        (is (= (validate/validate f {:requirements :apple})
               (validate/validate f {:requirements (:apple validate/requirements)}))
            "an entry of requirements, given as a map")))))

(deftest spotify-and-youtube
  (let [f       (rss/parse @example)
        missing (fn [feed who]
                  (->> (validate/validate feed {:requirements who})
                       (filter (comp #{:validate/required} :id))
                       (map (juxt :path :message))))]
    (testing "Spotify"
      (is (empty? (missing f :spotify)))
      (is (= [[[] "the channel has no <image> or <itunes:image>, which Spotify requires"]]
             (missing (dissoc f :rss/image :itunes/image) :spotify))
          "one of two elements will do")
      (is (empty? (missing (-> f
                               (update-in [:rss/items 0] dissoc :rss/title)
                               (assoc-in [:rss/items 0 :media/title] "Episode 3"))
                           :spotify))
          "and so will a media:title")
      (is (= [[[:rss/items 0] "the item has no <title> or <media:title>, which Spotify requires"]]
             (missing (update-in f [:rss/items 0] dissoc :rss/title) :spotify))))
    (testing "YouTube"
      (is (empty? (missing f :youtube)))
      (is (= [[:rss/items 0 :rss/title]]
             (map first (missing (assoc-in f [:rss/items 0 :rss/title] (apply str (repeat 101 "x")))
                                 :youtube)))
          "a title of more than 100 characters")
      (is (empty? (missing (assoc-in f [:rss/items 0 :rss/title] (apply str (repeat 100 "😀")))
                           :youtube))
          "counted as characters, not as UTF-16")
      (is (= [[:rss/items 0 :rss/title]]
             (map first (missing (assoc-in f [:rss/items 0 :rss/title] "A <b>bold</b> title") :youtube)))
          "nor < or >")
      (is (= [[:rss/items 0 :rss/enclosure]]
             (map first (missing (assoc-in f [:rss/items 0 :rss/enclosure :type] "video/mp4") :youtube)))
          "a video, since YouTube makes the video itself")
      (is (= [[]] (map first (missing (dissoc f :itunes/owner :rss/managing-editor) :youtube)))
          "an email to send the code to"))))

(deftest repeated-items
  (let [item  (fn [guid url]
                {:rss/title "E" :rss/guid guid :rss/enclosure {:url url :length 1 :type "audio/mpeg"}})
        f     {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
               :rss/items   [(item "a" "https://t/1.mp3") (item "a" "https://t/2.mp3")
                             (item "c" "https://t/1.mp3") (item "d" "https://t/4.mp3")]}
        found (fn [opts]
                (->> (validate/validate f opts)
                     (filter (comp #{:validate/duplicate-guid :validate/duplicate-enclosure} :id))
                     (map (juxt :id :level :path :first-path))))]
    (is (= [[:validate/duplicate-guid :error [:rss/items 1 :rss/guid] [:rss/items 0 :rss/guid]]
            [:validate/duplicate-enclosure :warning [:rss/items 2 :rss/enclosure :url]
             [:rss/items 0 :rss/enclosure :url]]]
           (found {}))
        "a guid is an error, an enclosure URL a warning, e.g. of a trailer, each naming the earlier item")
    (is (= [:error :error] (map second (found {:requirements :apple})))
        "both errors for Apple, which keeps only the first item")))

(deftest enclosure-attributes
  (let [found (fn [enclosure]
                (->> (validate/validate {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                                         :rss/items   [{:rss/title "E" :rss/enclosure enclosure}]})
                     (map (juxt :id :path))
                     set))
        path  (fn [k] [:rss/items 0 :rss/enclosure k])]
    (is (= #{[:validate/missing-attribute (path :length)] [:validate/missing-attribute (path :type)]}
           (found {:url "https://t/1.mp3"}))
        "RSS 2.0 requires a length and a type")
    (is (= #{[:validate/missing-attribute (path :length)] [:validate/missing-attribute (path :type)]}
           (set (map (juxt :id :path)
                     (validate/validate (rss/parse (str "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link>"
                                                        "<description>d</description><item><title>E</title>"
                                                        "<enclosure url=\"https://t/1.mp3\" length=\" \" type=\"\"/>"
                                                        "</item></channel></rss>"))))))
        "a blank one is missing")
    (is (= #{[:validate/unknown-length (path :length)]}
           (found {:url "https://t/1.mp3" :length 0 :type "audio/mpeg"}))
        "a length of 0 says it isn't known")
    (is (= #{[:validate/unknown-media-type (path :type)]}
           (found {:url "https://t/1.pdf" :length 1 :type "application/pdf"})))
    (is (= #{}
           (found {:url "https://t/1.m3u8" :length 1 :type "application/x-mpegURL"})
           (found {:url "https://t/1.mp4" :length 1 :type "Video/MP4; codecs=\"avc1\""})
           (found {:url "https://t/1.ogg" :length 1 :type "application/ogg"}))
        "an HLS playlist, a type in any case with parameters, and Ogg")))

(deftest apples-categories
  (let [found (fn [categories & [opts]]
                (->> (validate/validate {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                                         :itunes/categories categories}
                                        (or opts {}))
                     (map (juxt :id :path :replacement))))]
    (is (= [] (found [{:text "Arts" :categories [{:text "Books"}]} {:text "True Crime"}])))
    (is (= [[:validate/renamed-category [:itunes/categories 0 :text] ["Leisure"]]
            [:validate/renamed-category [:itunes/categories 0 :categories 0 :text] ["Leisure" "Games"]]]
           (found [{:text "Games & Hobbies" :categories [{:text "Other Games"} {:text "Video Games"}]}]))
        "a name from before 2019, with the one that stands for it now, and once for a subcategory that kept its name")
    (is (= [[:validate/escaped-category [:itunes/categories 0 :text] ["Society & Culture"]]]
           (found [{:text "Society &amp; Culture" :categories [{:text "Philosophy"}]}])))
    (is (= [[:validate/wrong-subcategory [:itunes/categories 0 :text] ["Religion & Spirituality" "Christianity"]]
            [:validate/wrong-subcategory [:itunes/categories 1 :categories 0 :text] ["Comedy" "Improv"]]
            [:validate/category-depth [:itunes/categories 1 :categories 1 :categories] nil]]
           (found [{:text "Christianity"}
                   {:text "Arts" :categories [{:text "Improv"} {:text "Books" :categories [{:text "Novels"}]}]}]))
        "a subcategory alone, one under the wrong category, and a third level")
    (is (= [[:validate/unknown-category [:itunes/categories 0 :text] nil]]
           (found [{:text "Sermon" :categories [{:text "Weekly"}]}]))
        "and once for a category that Apple doesn't list")
    (is (= [[:validate/unknown-category [:itunes/categories 0 :text] ["Education" "How To"]]
            [:validate/unknown-category [:itunes/categories 1 :categories 0 :text] ["Arts" "Food"]]]
           (found [{:text "How to"} {:text "Arts" :categories [{:text "food"}]}]))
        "with the name it has in another case")
    (testing "under the requirements of Apple and PSP-1"
      (let [levels (fn [requirements]
                     (->> (validate/validate {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                                              :itunes/categories [{:text "Sermon"} {:text "Health"}]}
                                             {:requirements requirements})
                          (filter (comp #{:validate/unknown-category :validate/renamed-category} :id))
                          (map (juxt :id :level))))]
        (is (= [[:validate/unknown-category :error] [:validate/renamed-category :warning]]
               (levels :apple) (levels :psp-1))
            "an unknown category is an error, while an old name still works")))
    (is (= [] (map :id (validate/validate {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                                           :podcast/medium "music" :itunes/categories [{:text "Rock"}]})))
        "the list is for podcasts, not for a feed of music")
    (is (= [] (found [{:text "Knitting"}] {:categories {"Knitting" #{}}}))
        "by a table of an app's own")))

(deftest messages-for-people
  (let [base {:rss/title "T" :rss/link "https://t" :rss/description "d"}
        item {:rss/title "E" :rss/guid "g" :rss/enclosure {:url "https://t/1.mp3" :length 1 :type "audio/mpeg"}}]
    (is (= [["the value isn't a valid duration" "1h"] ["the value isn't a valid duration" "2h"]]
           (->> (validate/validate (assoc base :rss/items [(assoc item :itunes/duration "1h")
                                                           (assoc item :rss/guid "h" :itunes/duration "2h")]))
                (filter (comp #{:validate/invalid-value} :id))
                (map (juxt :message :value))))
        "the same message for every value, which is a detail, so that summarize counts them as one")
    (testing "the requirements"
      (let [v (->> (validate/validate (assoc base :rss/items [(assoc item :rss/pub-date #inst "2026-10-01")
                                                              (assoc item :rss/guid "h")])
                                      {:requirements :apple})
                   (filter (comp #{:validate/recommended} :id)))]
        (is (= ["the item has no <pubDate>, which Apple Podcasts recommends" [:rss/items 1 :rss/pub-date]]
               ((juxt :message :path) (first (filter (comp #{[:rss/items 1 :rss/pub-date]} :path) v))))
            "name the element as the specs write it, at the path of the item")
        (is (= [[:rss/items] 2]
               ((juxt :path :count)
                (first (filter (comp #{"no item has <itunes:duration>, which Apple Podcasts recommends"} :message)
                               v))))
            "and a recommendation that no item meets once")))
    (is (= [] (filter (comp #{:validate/rss-version} :id) (validate/validate base)))
        "no version of rss in a feed built from data, which emit writes")
    (is (= ["0.91"] (map :value (filter (comp #{:validate/rss-version} :id)
                                        (validate/validate (assoc base :rss/version "0.91"))))))
    (is (= ["item 2 › enclosure › url" "every item" "itunes:category 1 › itunes:category 1 › text" "channel"]
           (map validate/location [[:rss/items 1 :rss/enclosure :url] [:rss/items]
                               [:itunes/categories 0 :categories 0 :text] []]))
        "a path as people read it")))

(deftest the-report-of-a-fetch
  (let [f (rss/parse "<rss version=\"2.0\"><channel><title>T</title><title>U</title><link>https://t</link><description>d</description></channel></rss>")]
    (is (= #{:feed/duplicate-element :validate/required :validate/recommended}
           (set (map :id (validate/report {:status 200 :feed f :report (rss/report f)} {:requirements :apple}))))
        "the report of the parse and the validation of the feed together"))
  (is (= [[:fetch/status :error]]
         (map (juxt :id :level) (validate/report {:status 404 :error {:id :fetch/status :level :warning :status 404}})))
      "and a feed that can't be read, which fails as a whole"))

(deftest unknown-elements-are-reported
  (let [f (rss/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><mystery/></channel></rss>")
        v (validate/validate f)]
    (is (= :mystery (:tag (first (filter (comp #{:validate/unknown-element} :id) v)))))))

(deftest an-extended-registry
  (let [elements (conj spec/elements {:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                                      :attrs {:scheme {:required true}} :content :decimal})
        xml      "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title><link>https://t</link><description>d</description><acme:rating>4.5</acme:rating></channel></rss>"]
    (testing "the validator checks a client's element by the client's registry"
      (is (= {:validate/missing-attribute 1}
             (ids (validate/validate (rss/parse xml {:registry elements}) {:registry elements})))))
    (testing "and reports it as unknown by the library's"
      (is (= {:validate/unknown-element 1} (ids (validate/validate (rss/parse xml))))))))

(deftest typos-wrong-types-and-nothing
  (let [base  {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"}
        found (fn [f] (set (map (juxt :id :path) (validate/validate f))))]
    (testing "a key the registry does not define: any on the channel, one in a namespace of the library elsewhere"
      (is (= #{[:validate/unknown-key [:rss/titel]] [:validate/unknown-key [:title]]
               [:validate/unknown-key [:rss/items 0 :itunes/autor]]}
             (found (assoc base :rss/titel "x" :title "merged"
                           :rss/items [{:rss/title "E" :itunes/autor "A" :foo "an attribute a parse kept"
                                        :rss/enclosure {:url "https://a/1.mp3" :length 1 :type "audio/mpeg"}}])))))
    (testing "a value that is not of its type, whatever it is instead of a string"
      (is (= #{[:validate/invalid-value [:rss/pub-date]] [:validate/invalid-value [:itunes/explicit]]}
             (found (assoc base :rss/pub-date 1700000000000 :itunes/explicit :no)))))
    (testing "a negative number, and one that is no number at all"
      (is (= #{[:validate/invalid-value [:rss/items 0 :rss/enclosure :length]]
               [:validate/invalid-value [:rss/items 0 :itunes/duration]]}
             (found (assoc base :rss/items [{:rss/title "E" :itunes/duration ##NaN
                                             :rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg" :length -5}}])))))
    (testing "nil is no value, as it is to emit"
      (is (= #{} (found (assoc base :rss/copyright nil :itunes/explicit nil))))
      (is (= #{[:validate/missing-element [:rss/title]]} (found (assoc base :rss/title nil)))))))

(deftest wrong-shapes-are-diagnosed
  (let [f {:rss/version     "2.0"
           :rss/title       {:content "T"}
           :rss/link        "https://t"
           :rss/description "d"
           :itunes/image    "https://t/a.png"
           :rss/items       ["not an item"
                             {:rss/title "E" :rss/guid "abc"
                              :rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}}]}
        v (validate/validate f {:requirements :psp-1})]
    (is (= 3 (:validate/wrong-shape (ids v))) "a map for a scalar, a string for a map, a string for an item")
    (is (= #{[:rss/title] [:itunes/image] [:rss/items 0]}
           (set (map :path (filter (comp #{:validate/wrong-shape} :id) v)))))
    (is (not (validate/valid? f)))
    (is (some (comp #{:validate/required} :id) v) "the requirements are still checked around the wrong shapes")
    (is (not-any? (comp #{:validate/wrong-shape} :id)
                  (validate/validate {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                                      :rss/items [{:rss/title "E" :rss/guid "abc" :podcast/season 2}]}))
        "the shorthand emit takes is not a wrong shape")))
