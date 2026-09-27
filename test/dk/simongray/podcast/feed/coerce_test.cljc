(ns dk.simongray.podcast.feed.coerce-test
  "The value types: what reads, what does not, and how values write back."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.coerce :as coerce]))

(deftest parsing
  (testing "the coerced types read their strings"
    (is (= 7 (coerce/parse :integer " 7 ")))
    (is (= 7 (coerce/parse :decimal "7")))
    (is (= 0.5 (coerce/parse :decimal ".5")))
    (is (= 5.0E-8 (coerce/parse :decimal "0.00000005")))
    (is (true? (coerce/parse :boolean "TRUE")))
    (is (false? (coerce/parse :yes-no "no")))
    (is (= #inst "2026-01-01T10:00:00Z" (coerce/parse :rfc2822 "Thu, 01 Jan 2026 10:00:00 GMT")))
    (is (= #inst "2026-01-01T10:00:00Z" (coerce/parse :iso8601 "2026-01-01T10:00:00Z")))
    (is (= 3723 (coerce/parse :duration "1:02:03")))
    (is (= "WEEKLY" (:freq (coerce/parse :rrule "FREQ=WEEKLY;BYDAY=MO"))))
    (is (= {:lat 30.2672 :lon -97.7431 :uncertainty 350} (coerce/parse :geo "geo:30.2672,-97.7431;u=350")))
    (is (= {:type "R" :id 113314 :revision 3} (coerce/parse :osm "R113314#3"))))
  (testing "what does not read is marked, never thrown"
    (doseq [[type s] [[:integer "seven"] [:decimal "1,5"] [:boolean "yes"] [:yes-no "true"]
                      [:rfc2822 "yesterday"] [:duration "long"] [:geo "30,-97"] [:osm "X1"]]]
      (is (coerce/invalid? (coerce/parse type s)) (str type " " (pr-str s)))))
  (testing "a number beyond what both platforms hold exactly"
    (is (coerce/invalid? (coerce/parse :integer "99999999999999999999")))
    (is (coerce/invalid? (coerce/parse :integer "9007199254740993")) "on the JVM too, so that the platforms agree")
    (is (= 1.0E20 (coerce/parse :decimal "99999999999999999999")) "a decimal may be approximate")
    (is (coerce/invalid? (coerce/parse :decimal (str "1" (apply str (repeat 400 "0")) ".5"))))
    (is (coerce/invalid? (coerce/parse :geo (str "geo:1" (apply str (repeat 400 "0")) ".5,1"))))
    (is (coerce/invalid? (coerce/parse :osm "R99999999999999999999")))
    (is (coerce/invalid? (coerce/parse :osm "R0")) "OpenStreetMap numbers from 1"))
  (testing "what counts as a value of each type"
    (is (coerce/of-type? :integer 5))
    (is (not (coerce/of-type? :integer 5.5)))
    (is (not (coerce/of-type? :decimal ##NaN)))
    (is (not (coerce/of-type? :boolean :no)))
    (is (not (coerce/of-type? :rfc2822 1700000000000)))
    (is (coerce/of-type? :text "x"))
    (is (every? #(coerce/of-type? % (coerce/parse % "5")) [:integer :decimal :duration :text])))
  (testing "no-break spaces are white space, on the JVM as in JavaScript"
    (is (= 5 (coerce/parse :integer "5\u00A0")))
    (is (= "Show" (coerce/parse :text "\uFEFFShow\u00A0\u202F"))))
  (testing "the string types are trimmed and nothing more"
    (is (= "<p>Hi</p>" (coerce/parse :html " <p>Hi</p> ")))
    (is (= "x" (coerce/parse :enum " x ")))
    (is (= "a b" (coerce/parse :text "a b"))))
  (testing "the coerced types are the ones that leave strings behind"
    (is (every? coerce/types coerce/coerced-types))
    (is (not-any? coerce/coerced-types [:text :html :url :email :mime :language :enum]))))

(deftest formatting
  (is (= "true" (coerce/format :boolean true)))
  (is (= "no" (coerce/format :yes-no false)))
  (is (= "Thu, 01 Jan 2026 10:00:00 GMT" (coerce/format :rfc2822 #inst "2026-01-01T10:00:00Z")))
  (is (= "2026-01-01T10:00:00Z" (coerce/format :iso8601 #inst "2026-01-01T10:00:00Z")))
  (is (= "3723" (coerce/format :duration 3723)))
  (is (= "0.00000005" (coerce/format :decimal 5.0E-8)))
  (is (= "geo:30.2672,-97.7431;u=350" (coerce/format :geo {:lat 30.2672 :lon -97.7431 :uncertainty 350})))
  (is (= "R113314#3" (coerce/format :osm {:type "R" :id 113314 :revision 3})))
  (testing "a value kept as a string writes back as it came"
    (is (= "seven" (coerce/format :integer "seven")))
    (is (= "yesterday" (coerce/format :rfc2822 "yesterday"))))
  (testing "a value of another type is written as it is, a keyword by its name"
    (is (= "no" (coerce/format :boolean :no)) "not true, as any truthy value was")
    (is (= "5" (coerce/format :text 5)))))

(deftest plain-decimals
  (is (= "5" (coerce/plain-decimal 5)))
  (is (= "0.25" (coerce/plain-decimal 0.25)))
  (is (= "1.5" (coerce/plain-decimal 1.5)))
  (is (= "0.00000005" (coerce/plain-decimal 5.0E-8)))
  (is (= "100000000000" (coerce/plain-decimal 1.0E11))))
