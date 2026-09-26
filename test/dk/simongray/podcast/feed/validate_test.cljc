(ns dk.simongray.podcast.feed.validate-test
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.feed.validate :as validate]))

(def example
  (delay #?(:clj  (slurp (io/resource "feeds/namespace-example.xml"))
            :cljs (rc/inline "feeds/namespace-example.xml"))))

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
    (is (= [:podcast/funding 0 :url] (:path (first (filter (comp #{:validate/missing-attribute} :id) v)))))))

(deftest rss-basics
  (let [v (validate/validate (podcast/parse "<rss version=\"0.91\"><channel><item><enclosure url=\"https://a\"/></item></channel></rss>"))]
    (is (= {:validate/rss-version 1 :validate/missing-element 4} (ids v))))
  (is (= {:validate/missing-enclosure 1}
         (ids (validate/validate (podcast/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><item><title>E</title></item></channel></rss>"))))))

(deftest profiles
  (let [f (podcast/parse @example)]
    (testing "the specification example against PSP-1"
      (let [v (validate/validate f {:profile :psp-1})]
        (is (= 1 (:validate/profile-required (ids v))))
        (is (= [:atom/links] (:path (first (filter (comp #{:validate/profile-required} :id) v)))))
        (is (= 3 (:validate/profile-recommended (ids v))))))
    (testing "and against Apple"
      (is (validate/valid? f {:profile :apple})))))

(deftest unknown-elements-are-reported
  (let [f (podcast/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><mystery/></channel></rss>")
        v (validate/validate f)]
    (is (= :mystery (:tag (first (filter (comp #{:validate/unknown-element} :id) v)))))))

(deftest an-extended-registry
  (let [tags (conj spec/tags {:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                              :attrs {:scheme {:required true}} :content :decimal})
        xml  "<rss version=\"2.0\" xmlns:acme=\"https://acme.example\"><channel><title>T</title><link>https://t</link><description>d</description><acme:rating>4.5</acme:rating></channel></rss>"]
    (testing "the validator checks a client's element by the client's registry"
      (is (= {:validate/missing-attribute 1}
             (ids (validate/validate (podcast/parse xml {:registry tags}) {:registry tags})))))
    (testing "and reports it as unknown by the library's"
      (is (= {:validate/unknown-element 1} (ids (validate/validate (podcast/parse xml))))))))

(deftest wrong-shapes-are-diagnosed
  (let [f {:rss/version     "2.0"
           :rss/title       {:content "T"}
           :rss/link        "https://t"
           :rss/description "d"
           :itunes/image    "https://t/a.png"
           :rss/items       ["not an item"
                             {:rss/title "E" :rss/guid "abc"
                              :rss/enclosure {:url "https://a/1.mp3" :type "audio/mpeg"}}]}
        v (validate/validate f {:profile :psp-1})]
    (is (= 3 (:validate/wrong-shape (ids v))) "a map for a scalar, a string for a map, a string for an item")
    (is (= #{[:rss/title] [:itunes/image] [:rss/items 0]}
           (set (map :path (filter (comp #{:validate/wrong-shape} :id) v)))))
    (is (not (validate/valid? f)))
    (is (some (comp #{:validate/profile-required} :id) v) "the profile is still checked around the wrong shapes")
    (is (not-any? (comp #{:validate/wrong-shape} :id)
                  (validate/validate {:rss/version "2.0" :rss/title "T" :rss/link "https://t" :rss/description "d"
                                      :rss/items [{:rss/title "E" :rss/guid "abc" :podcast/season 2}]}))
        "the shorthand emit takes is not a wrong shape")))
