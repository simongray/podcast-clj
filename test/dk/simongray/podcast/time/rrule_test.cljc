(ns dk.simongray.podcast.time.rrule-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.time.rrule :as rrule]))

(deftest reading-and-writing
  (testing "parts by lower-case key, case and the RRULE prefix forgiven"
    (is (= {:freq "WEEKLY" :interval 2 :byday ["MO" "WE"] :until #inst "2026-12-31T00:00:00Z"}
           (rrule/parse "RRULE:freq=weekly;INTERVAL=2;BYDAY=mo,WE;UNTIL=20261231")))
    (is (= {:freq "MONTHLY" :bymonthday [1 -1] :count 12}
           (rrule/parse "FREQ=MONTHLY;BYMONTHDAY=1,-1;COUNT=12")))
    (is (= {:freq "DAILY" :x-foo "bar"} (rrule/parse "FREQ=DAILY;X-FOO=bar"))))
  (testing "no FREQ, an unknown FREQ or a part that does not read is no rule"
    (is (nil? (rrule/parse "BYDAY=MO")))
    (is (nil? (rrule/parse "FREQ=SOMETIMES")))
    (is (nil? (rrule/parse "FREQ=WEEKLY;INTERVAL=two")))
    (is (nil? (rrule/parse "FREQ=WEEKLY;BYDAY=+MO")) "a sign needs an ordinal")
    (is (= {:freq "MONTHLY" :byday ["+1MO"]} (rrule/parse "FREQ=MONTHLY;BYDAY=+1MO"))))
  (testing "written back in a fixed order"
    (is (= "FREQ=WEEKLY;INTERVAL=2;UNTIL=20261231T000000Z;BYDAY=MO,WE"
           (rrule/format {:byday ["MO" "WE"] :until #inst "2026-12-31T00:00:00Z" :interval 2 :freq "WEEKLY"})))
    (is (= "FREQ=DAILY;X-FOO=bar" (rrule/format {:freq "DAILY" :x-foo "bar"})))))

(deftest evaluation
  (let [start #inst "2026-01-05T18:00:00Z"
        occ   (fn [s n] (vec (take n (rrule/occurrences (rrule/parse s) start))))]
    (testing "weekly, from a Monday"
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-01-07T18:00:00Z"
              #inst "2026-01-12T18:00:00Z" #inst "2026-01-14T18:00:00Z"]
             (occ "FREQ=WEEKLY;BYDAY=MO,WE" 4)))
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-01-19T18:00:00Z"] (occ "FREQ=WEEKLY;INTERVAL=2" 2))))
    (testing "monthly, by weekday ordinal and by day of month"
      (is (= [#inst "2026-01-30T18:00:00Z" #inst "2026-02-27T18:00:00Z"] (occ "FREQ=MONTHLY;BYDAY=-1FR" 2)))
      (is (= [#inst "2026-01-15T18:00:00Z" #inst "2026-02-01T18:00:00Z" #inst "2026-02-15T18:00:00Z"]
             (occ "FREQ=MONTHLY;BYMONTHDAY=1,15" 3)))
      (is (= [#inst "2026-01-31T18:00:00Z" #inst "2026-03-31T18:00:00Z"] (occ "FREQ=MONTHLY;BYMONTHDAY=31" 2)))
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-02-02T18:00:00Z"] (occ "FREQ=MONTHLY;BYDAY=+1MO" 2))))
    (testing "daily, by day of month"
      (is (= [#inst "2026-01-15T18:00:00Z" #inst "2026-02-01T18:00:00Z" #inst "2026-02-15T18:00:00Z"]
             (occ "FREQ=DAILY;BYMONTHDAY=1,15" 3)))
      (is (= [#inst "2026-01-31T18:00:00Z" #inst "2026-02-28T18:00:00Z"] (occ "FREQ=DAILY;BYMONTHDAY=-1" 2))))
    (testing "daily and yearly, count and until"
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2027-01-05T18:00:00Z"] (occ "FREQ=YEARLY" 2)))
      (is (= [#inst "2026-06-01T18:00:00Z" #inst "2027-06-07T18:00:00Z"] (occ "FREQ=YEARLY;BYMONTH=6;BYDAY=1MO" 2)))
      (is (= 3 (count (rrule/occurrences (rrule/parse "FREQ=DAILY;COUNT=3") start))))
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-01-06T18:00:00Z"]
             (vec (rrule/occurrences (rrule/parse "FREQ=DAILY;UNTIL=20260106T235959Z") start)))))
    (testing "what is not evaluated is nil, not wrong"
      (is (nil? (rrule/occurrences (rrule/parse "FREQ=HOURLY") start)))
      (is (nil? (rrule/occurrences (rrule/parse "FREQ=DAILY;BYHOUR=9") start)))
      (is (nil? (rrule/occurrences (rrule/parse "FREQ=WEEKLY;BYMONTHDAY=1") start)))
      (is (nil? (rrule/occurrences nil start))))
    (is (= #inst "2026-01-12T18:00:00Z"
           (rrule/next-occurrence (rrule/parse "FREQ=WEEKLY") start #inst "2026-01-05T18:00:00Z")))))

(deftest parts-out-of-range
  (testing "a part out of range is no rule"
    (is (nil? (rrule/parse "FREQ=MONTHLY;BYDAY=0FR")))
    (is (nil? (rrule/parse "FREQ=MONTHLY;BYMONTHDAY=99999999999999999999")))
    (is (nil? (rrule/parse "FREQ=DAILY;UNTIL=20261345")))
    (doseq [s ["FREQ=DAILY;INTERVAL=0" "FREQ=DAILY;COUNT=0" "FREQ=MONTHLY;BYMONTHDAY=0" "FREQ=MONTHLY;BYMONTHDAY=32"
               "FREQ=YEARLY;BYMONTH=13" "FREQ=DAILY;BYHOUR=24" "FREQ=YEARLY;BYWEEKNO=54" "FREQ=YEARLY;BYYEARDAY=-367"]]
      (is (nil? (rrule/parse s)) s))
    (is (= {:freq "MONTHLY" :bymonthday [-31 31]} (rrule/parse "FREQ=MONTHLY;BYMONTHDAY=-31,31")))
    (is (= {:freq "MONTHLY" :byday ["53MO" "-1FR"]} (rrule/parse "FREQ=MONTHLY;BYDAY=53MO,-1FR"))))
  (let [start #inst "2026-01-05T18:00:00Z"]
    (testing "an interval longer than the century read gives the start alone, without overflow"
      (doseq [freq ["DAILY" "WEEKLY" "MONTHLY" "YEARLY"]]
        (is (= [start] (vec (rrule/occurrences (rrule/parse (str "FREQ=" freq ";INTERVAL=1000000000")) start)))
            freq)))
    (is (nil? (rrule/occurrences {:freq "DAILY" :interval 0} start)) "an interval of 0 is no rule")))
