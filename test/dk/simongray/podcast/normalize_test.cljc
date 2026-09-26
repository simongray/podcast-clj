(ns dk.simongray.podcast.normalize-test
  "The shorthand a producer may write, expanded to the model's shape."
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.validate :as validate]))

(def example
  (delay (podcast/parse #?(:clj  (slurp (io/resource "feeds/namespace-example.xml"))
                           :cljs (rc/inline "feeds/namespace-example.xml")))))

(def shorthand
  "A feed as a producer might write it: bare values and single values."
  {:rss/version     "2.0"
   :rss/title       "T"
   :rss/link        "https://t"
   :rss/description "d"
   :podcast/guid    "917393e3-1b1e-5cef-ace4-edaa54e1f810"
   :podcast/locked  true
   :rss/items       {:rss/title           "E"
                     :rss/guid            "abc"
                     :podcast/season      2
                     :podcast/transcripts {:url "https://t/t.vtt" :type "text/vtt"}
                     :podcast/people      {:content "Al" :role "host"}}})

(deftest the-shorthand-a-producer-writes
  (let [full (feed/normalize shorthand)
        item (first (:rss/items full))]
    (testing "bare values and single values take the model's shape"
      (is (= {:content "abc"} (:rss/guid item)))
      (is (= {:content 2} (:podcast/season item)))
      (is (= {:content true} (:podcast/locked full)))
      (is (= [{:url "https://t/t.vtt" :type "text/vtt"}] (:podcast/transcripts item)))
      (is (= [{:content "Al" :role "host"}] (:podcast/people item)))
      (is (= "T" (:rss/title full)) "scalars stay scalars")
      (is (= full (feed/normalize full)) "the model's shape is left alone"))
    (testing "what parse returns is unchanged"
      (is (= @example (feed/normalize @example))))
    (testing "emit writes the shorthand as the model, and validate takes it"
      (let [xml (podcast/emit shorthand)]
        (is (re-find #"<guid>abc</guid>" xml))
        (is (re-find #"<podcast:season>2</podcast:season>" xml))
        (is (re-find #"<podcast:locked>yes</podcast:locked>" xml))
        (is (re-find #"<podcast:transcript url=\"https://t/t.vtt\" type=\"text/vtt\"/>" xml))
        (is (re-find #"<podcast:person role=\"host\">Al</podcast:person>" xml))
        (is (= full (podcast/parse xml)) "and what it writes reads back as the normalized model"))
      (is (not-any? (comp #{:validate/wrong-shape} :id) (validate/validate shorthand {:profile :psp-1}))))))
