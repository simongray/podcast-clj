(ns dk.simongray.podcast.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.time :as time]))

(deftest civil-arithmetic
  (is (= 0 (time/days-from-civil 1970 1 1)))
  (is (= [1970 1 1] (time/civil-from-days 0)))
  (is (= [2000 2 29] (time/civil-from-days (time/days-from-civil 2000 2 29))))
  (is (= 20721 (time/days-from-civil 2026 9 25))))

(deftest rfc2822
  (testing "the canonical form"
    (is (= #inst "2026-09-25T16:42:40Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 16:42:40 +0000")))
    (is (= #inst "2026-09-25T16:42:40Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 16:42:40 GMT")))
    (is (time/rfc2822-strict? "Fri, 25 Sep 2026 16:42:40 +0000")))
  (testing "offsets and zone names"
    (is (= #inst "2026-09-25T01:00:00Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 03:00:00 +0200")))
    (is (= #inst "2026-07-31T18:02:23Z" (time/parse-rfc2822 "Fri, 31 Jul 2026 10:02:23 PST")))
    (is (= #inst "2021-04-01T13:00:00Z" (time/parse-rfc2822 "Thu, 01 Apr 2021 08:00:00 EST")))
    (is (not (time/rfc2822-strict? "Fri, 31 Jul 2026 10:02:23 PST"))))
  (testing "the deviations feeds are known for"
    (is (= #inst "2026-09-25T01:00:00Z" (time/parse-rfc2822 "25 Sep 26 03:00 +0200")))
    (is (= #inst "2026-09-25T00:00:00Z" (time/parse-rfc2822 "Friday, 25 September 2026")))
    (is (= #inst "2026-09-25T16:42:40Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 16:42:40"))))
  (testing "garbage"
    (is (nil? (time/parse-rfc2822 "yesterday")))
    (is (nil? (time/parse-rfc2822 "")))))

(deftest iso8601
  (is (= #inst "2021-09-26T13:30:00Z" (time/parse-iso8601 "2021-09-26T07:30:00.000-0600")))
  (is (= #inst "2021-09-26T13:30:00.250Z" (time/parse-iso8601 "2021-09-26T13:30:00.25Z")))
  (is (= #inst "2023-08-28T00:00:00Z" (time/parse-iso8601 "2023-08-28")))
  (is (= #inst "2023-08-28T00:00:00Z" (time/parse-date "2023-08-28")))
  (is (nil? (time/parse-iso8601 "2023-8-28"))))

(deftest formatting
  (is (= "Fri, 25 Sep 2026 01:00:00 GMT" (time/format-rfc2822 #inst "2026-09-25T01:00:00Z")))
  (is (= "Thu, 01 Jan 1970 00:00:00 GMT" (time/format-rfc2822 #inst "1970-01-01T00:00:00Z")))
  (is (= "2021-09-26T13:30:00Z" (time/format-iso8601 #inst "2021-09-26T13:30:00Z")))
  (is (= "2021-09-26T13:30:00.250Z" (time/format-iso8601 #inst "2021-09-26T13:30:00.250Z")))
  (testing "round trips"
    (doseq [s ["Fri, 25 Sep 2026 16:42:40 GMT" "Mon, 29 Feb 2016 23:59:59 GMT"]]
      (is (= s (time/format-rfc2822 (time/parse-rfc2822 s)))))))

(deftest durations
  (is (= 3793 (time/parse-duration "3793")))
  (is (= 3793.5 (time/parse-duration "3793.5")))
  (is (= 7997 (time/parse-duration "02:13:17")))
  (is (= 1635 (time/parse-duration "27:15")))
  (is (= 5259 (time/parse-duration " 1:27:39 ")))
  (is (nil? (time/parse-duration "abc")))
  (is (nil? (time/parse-duration nil)))
  (is (= 2.76 (time/parse-clock "00:00:02,760")))
  (is (= "1:03:13" (time/format-clock 3793)))
  (is (= "0:45" (time/format-clock 45)))
  (is (= "00:00:02,760" (time/format-timestamp 2.76 ",")))
  (is (= "01:03:13.500" (time/format-timestamp 3793.5))))

(deftest recurrence-rules
  (testing "parts by lower-case key, case and the RRULE prefix forgiven"
    (is (= {:freq "WEEKLY" :interval 2 :byday ["MO" "WE"] :until #inst "2026-12-31T00:00:00Z"}
           (time/parse-rrule "RRULE:freq=weekly;INTERVAL=2;BYDAY=mo,WE;UNTIL=20261231")))
    (is (= {:freq "MONTHLY" :bymonthday [1 -1] :count 12}
           (time/parse-rrule "FREQ=MONTHLY;BYMONTHDAY=1,-1;COUNT=12")))
    (is (= {:freq "DAILY" :x-foo "bar"} (time/parse-rrule "FREQ=DAILY;X-FOO=bar"))))
  (testing "no FREQ, an unknown FREQ or a part that does not read is no rule"
    (is (nil? (time/parse-rrule "BYDAY=MO")))
    (is (nil? (time/parse-rrule "FREQ=SOMETIMES")))
    (is (nil? (time/parse-rrule "FREQ=WEEKLY;INTERVAL=two"))))
  (testing "written back in the RFC's order"
    (is (= "FREQ=WEEKLY;INTERVAL=2;UNTIL=20261231T000000Z;BYDAY=MO,WE"
           (time/format-rrule {:byday ["MO" "WE"] :until #inst "2026-12-31T00:00:00Z" :interval 2 :freq "WEEKLY"})))
    (is (= "FREQ=DAILY;X-FOO=bar" (time/format-rrule {:freq "DAILY" :x-foo "bar"})))))

(deftest recurrence-evaluation
  (let [start #inst "2026-01-05T18:00:00Z"
        occ   (fn [s n] (vec (take n (time/occurrences (time/parse-rrule s) start))))]
    (testing "weekly, from a Monday"
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-01-07T18:00:00Z"
              #inst "2026-01-12T18:00:00Z" #inst "2026-01-14T18:00:00Z"]
             (occ "FREQ=WEEKLY;BYDAY=MO,WE" 4)))
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-01-19T18:00:00Z"] (occ "FREQ=WEEKLY;INTERVAL=2" 2))))
    (testing "monthly, by weekday ordinal and by day of month"
      (is (= [#inst "2026-01-30T18:00:00Z" #inst "2026-02-27T18:00:00Z"] (occ "FREQ=MONTHLY;BYDAY=-1FR" 2)))
      (is (= [#inst "2026-01-15T18:00:00Z" #inst "2026-02-01T18:00:00Z" #inst "2026-02-15T18:00:00Z"]
             (occ "FREQ=MONTHLY;BYMONTHDAY=1,15" 3)))
      (is (= [#inst "2026-01-31T18:00:00Z" #inst "2026-03-31T18:00:00Z"] (occ "FREQ=MONTHLY;BYMONTHDAY=31" 2))))
    (testing "daily and yearly, count and until"
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2027-01-05T18:00:00Z"] (occ "FREQ=YEARLY" 2)))
      (is (= [#inst "2026-06-01T18:00:00Z" #inst "2027-06-07T18:00:00Z"] (occ "FREQ=YEARLY;BYMONTH=6;BYDAY=1MO" 2)))
      (is (= 3 (count (time/occurrences (time/parse-rrule "FREQ=DAILY;COUNT=3") start))))
      (is (= [#inst "2026-01-05T18:00:00Z" #inst "2026-01-06T18:00:00Z"]
             (vec (time/occurrences (time/parse-rrule "FREQ=DAILY;UNTIL=20260106T235959Z") start)))))
    (testing "what is not evaluated is nil, not wrong"
      (is (nil? (time/occurrences (time/parse-rrule "FREQ=HOURLY") start)))
      (is (nil? (time/occurrences (time/parse-rrule "FREQ=DAILY;BYHOUR=9") start)))
      (is (nil? (time/occurrences nil start))))
    (is (= #inst "2026-01-12T18:00:00Z"
           (time/next-occurrence (time/parse-rrule "FREQ=WEEKLY") start #inst "2026-01-05T18:00:00Z")))))
