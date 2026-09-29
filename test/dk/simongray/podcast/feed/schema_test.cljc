(ns dk.simongray.podcast.feed.schema-test
  "The shape of the generated schemas; the tests that use Malli itself are
  JVM-only and live in schema-malli-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.feed.schema :as schema]
            [dk.simongray.podcast.feed.spec :as spec]))

(def schemas
  (schema/schemas))

(deftest registry-shape
  (testing "every element has a schema, under its tag namespaced rss when bare"
    (is (= :rss/title (schema/ref-key :title)))
    (is (= :podcast/transcript (schema/ref-key :podcast/transcript)))
    (is (every? #(contains? schemas (schema/ref-key (:tag %))) spec/tags))
    (is (contains? schemas ::schema/feed)))
  (testing "every coerced type has a schema, and only those"
    (is (= coerce/coerced-types (set (keys schema/types)))))
  (testing "a scalar element is its coerced type"
    (is (= :string (get schemas :rss/title)))
    (is (= 'inst? (get schemas :rss/pubDate)))
    (is (= :int (get schemas :itunes/episode)))
    (is (= 'number? (get schemas :itunes/duration)))
    (is (= :boolean (get schemas :itunes/explicit))))
  (testing "an element with attributes or children is a map"
    (is (= [:map
            [:url {:optional false} :string]
            [:type {:optional false} :string]
            [:language {:optional true} :string]
            [:rel {:optional true} :string]
            [:xml/unknown {:optional true} schema/unknown]]
           (get schemas :podcast/transcript)))
    (is (= [:recipients {:optional true} [:vector {:gen/max 5} [:ref :podcast/valueRecipient]]]
           (nth (get schemas :podcast/value) 4)))
    (is (= [:content {:optional true} :boolean]
           (nth (get schemas :podcast/locked) 2))))
  (testing "the feed and item schemas are self-contained"
    (is (= [:schema {:registry schemas} ::schema/feed] schema/feed))
    (is (= [:schema {:registry schemas} :rss/item] schema/item))
    (is (= [:schema {:registry schemas} :podcast/value] (schema/tag :podcast/value)))))

(deftest an-extended-registry
  (let [tags     (conj spec/tags {:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                                  :attrs {:scheme {}} :content :decimal})
        extended (schema/schemas {:registry tags})]
    (is (= [:map [:scheme {:optional true} :string] [:content {:optional true} 'number?]
            [:xml/unknown {:optional true} schema/unknown]]
           (get extended :acme/rating)))
    (is (some #{[:acme/rating {:optional true} [:ref :acme/rating]]} (get extended ::schema/feed)))
    (is (= [:schema {:registry extended} ::schema/feed] (schema/feed-schema {:registry tags})))
    (is (= [:schema {:registry extended} :acme/rating] (schema/tag :acme/rating {:registry tags})))
    (is (not (contains? schemas :acme/rating)))))
