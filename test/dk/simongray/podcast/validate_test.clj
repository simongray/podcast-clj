(ns dk.simongray.podcast.validate-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.validate :as validate]))

(defn- ids
  [findings]
  (frequencies (map :id findings)))

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
  (let [f (podcast/parse (slurp (io/resource "feeds/namespace-example.xml")))]
    (testing "the specification example against PSP-1"
      (let [v (validate/validate f :psp-1)]
        (is (= 1 (:validate/profile-required (ids v))))
        (is (= [:atom/links] (:path (first (filter (comp #{:validate/profile-required} :id) v)))))
        (is (= 3 (:validate/profile-recommended (ids v))))))
    (testing "and against Apple"
      (is (validate/valid? f :apple)))))

(deftest unknown-elements-are-reported
  (let [f (podcast/parse "<rss version=\"2.0\"><channel><title>T</title><link>https://t</link><description>d</description><mystery/></channel></rss>")
        v (validate/validate f)]
    (is (= :mystery (:tag (first (filter (comp #{:validate/unknown-element} :id) v)))))))
