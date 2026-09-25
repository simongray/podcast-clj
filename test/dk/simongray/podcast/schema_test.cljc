(ns dk.simongray.podcast.schema-test
  "The shape of the generated schemas; the tests that use Malli itself are
  JVM-only and live in schema-malli-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.schema :as schema]
            [dk.simongray.podcast.spec :as spec]))

(deftest registry-shape
  (testing "every element has a schema, under its tag namespaced rss when bare"
    (is (= :rss/title (schema/ref-key :title)))
    (is (= :podcast/transcript (schema/ref-key :podcast/transcript)))
    (is (every? #(contains? schema/registry (schema/ref-key (:tag %))) spec/tags))
    (is (contains? schema/registry ::schema/feed)))
  (testing "a scalar element is its coerced type"
    (is (= :string (get schema/registry :rss/title)))
    (is (= 'inst? (get schema/registry :rss/pubDate)))
    (is (= :int (get schema/registry :itunes/episode)))
    (is (= 'number? (get schema/registry :itunes/duration)))
    (is (= :boolean (get schema/registry :itunes/explicit))))
  (testing "an element with attributes or children is a map"
    (is (= [:map
            [:url {:optional false} :string]
            [:type {:optional false} :string]
            [:language {:optional true} :string]
            [:rel {:optional true} :string]
            [:xml/unknown {:optional true} schema/unknown]]
           (get schema/registry :podcast/transcript)))
    (is (= [:recipients {:optional true} [:vector {:gen/max 5} [:ref :podcast/valueRecipient]]]
           (nth (get schema/registry :podcast/value) 4)))
    (is (= [:content {:optional true} :boolean]
           (nth (get schema/registry :podcast/locked) 2))))
  (testing "the feed and item schemas are self-contained"
    (is (= [:schema {:registry schema/registry} ::schema/feed] schema/feed))
    (is (= [:schema {:registry schema/registry} :rss/item] schema/item))
    (is (= [:schema {:registry schema/registry} :podcast/value] (schema/tag :podcast/value)))))
