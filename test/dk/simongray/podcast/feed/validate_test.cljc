(ns dk.simongray.podcast.feed.validate-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def example
  (delay (fixture "feeds/namespace-example.xml")))

(defn- ids
  [diagnostics]
  (frequencies (map :id diagnostics)))

(deftest registry-checks
  (let [f (podcast/parse "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">
                            <channel><title>T</title><link>https://t</link><description>d</description>
                              <podcast:guid>not-a-uuid</podcast:guid>
                              <podcast:medium>vinyl</podcast:medium>
                              <podcast:images srcset=\"https://a 1w\"/>
                              <podcast:value type=\"lightning\" method=\"keysend\"/>
                              <podcast:person role=\"Wizard\">Merlin</podcast:person>
                              <podcast:funding>no url</podcast:funding>
                              <itunes:type>weekly</itunes:type>
                              <item><title>E</title><enclosure url=\"https://a/1.mp3\" type=\"audio/mpeg\"/>
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
  (let [v (validate/validate (podcast/parse "<rss version=\"0.91\"><channel><item><enclosure url=\"https://a\"/></item></channel></rss>"))]
    (is (= {:validate/rss-version 1 :validate/missing-element 4} (ids v))))
  (is (= {:validate/missing-enclosure 1}
         (ids (validate/validate (podcast/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><item><title>E</title></item></channel></rss>"))))))

(deftest requirements
  (let [f (podcast/parse @example)]
    (testing "the specification example against PSP-1"
      (let [v (validate/validate f {:requirements :psp-1})]
        (is (= 1 (:validate/required (ids v))))
        (is (= [:atom/links] (:path (first (filter (comp #{:validate/required} :id) v)))))
        (is (= 3 (:validate/recommended (ids v))))))
    (testing "and against Apple"
      (is (validate/valid? f {:requirements :apple}))
      (is (= [[:rss/items 0 :rss/guid]]
             (->> (validate/validate (update-in f [:rss/items 0] dissoc :rss/guid) {:requirements :apple})
                  (filter (comp #{:validate/required} :id))
                  (map :path)))
          "Apple requires the guid of an episode"))
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

(deftest unknown-elements-are-reported
  (let [f (podcast/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><mystery/></channel></rss>")
        v (validate/validate f)]
    (is (= :mystery (:tag (first (filter (comp #{:validate/unknown-element} :id) v)))))))

(deftest an-extended-registry
  (let [elements (conj spec/elements {:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                                      :attrs {:scheme {:required true}} :content :decimal})
        xml      "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title><link>https://t</link><description>d</description><acme:rating>4.5</acme:rating></channel></rss>"]
    (testing "the validator checks a client's element by the client's registry"
      (is (= {:validate/missing-attribute 1}
             (ids (validate/validate (podcast/parse xml {:registry elements}) {:registry elements})))))
    (testing "and reports it as unknown by the library's"
      (is (= {:validate/unknown-element 1} (ids (validate/validate (podcast/parse xml))))))))

(deftest typos-wrong-types-and-nothing
  (let [base  {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"}
        found (fn [f] (set (map (juxt :id :path) (validate/validate f))))]
    (testing "a key the registry does not define: any on the channel, one in a namespace of the library elsewhere"
      (is (= #{[:validate/unknown-key [:rss/titel]] [:validate/unknown-key [:title]]
               [:validate/unknown-key [:rss/items 0 :itunes/autor]]}
             (found (assoc base :rss/titel "x" :title "merged"
                           :rss/items [{:rss/title "E" :itunes/autor "A" :foo "an attribute a parse kept"
                                        :rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}}])))))
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
