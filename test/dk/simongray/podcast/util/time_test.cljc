(ns dk.simongray.podcast.util.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.time :as time]))

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

(deftest spaces-before-and-after-the-zone
  (testing "still read"
    (is (= #inst "2026-09-25T14:42:40Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 16:42:40   +0200  ")))
    (is (= #inst "2026-09-25T16:42:40Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 16:42:40GMT")))
    (is (= #inst "2021-09-26T13:30:00Z" (time/parse-iso8601 "2021-09-26T07:30:00 -06:00"))))
  (testing "a long run of them that leads nowhere is given up in linear time, not quadratic"
    (let [spaces (apply str (repeat 100000 " "))]
      (is (nil? (time/parse-rfc2822 (str "01 Jan 2020" spaces "!"))))
      (is (nil? (time/parse-iso8601 (str "2020-01-01T10:00" spaces "!")))))))

(deftest dates-that-do-not-exist
  (testing "a day or a time off the calendar is no date, not the one it would roll over to"
    (is (nil? (time/parse-iso8601 "2021-13-45T25:61:61Z")))
    (is (nil? (time/parse-rfc2822 "Wed, 31 Feb 2021 10:00:00 +0000")))
    (is (not (time/rfc2822-strict? "Wed, 31 Feb 2021 10:00:00 +0000")))
    (is (nil? (time/parse-rfc2822 "Fri, 25 Sep 2026 10:00:00 +2500"))))
  (testing "the end of a day and a leap second are on it"
    (is (= #inst "2026-09-26T00:00:00Z" (time/parse-iso8601 "2026-09-25T24:00:00Z")))
    (is (= #inst "2017-01-01T00:00:00Z" (time/parse-iso8601 "2016-12-31T23:59:60Z"))))
  (testing "a zone the table lacks reads as UTC, so that the date is kept"
    (is (= #inst "2026-09-25T20:00:00Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 10:00:00 HST")))
    (is (= #inst "2026-09-25T10:00:00Z" (time/parse-rfc2822 "Fri, 25 Sep 2026 10:00:00 XYZ")))
    (is (nil? (time/parse-rfc2822 "Fri, 25 Sep 2026 10:00 PM")) "but AM and PM are no zones")))

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
  (is (= "01:03:13.500" (time/format-timestamp 3793.5)))
  (is (nil? (time/parse-duration (str "1" (apply str (repeat 400 "0")) ".5"))) "too large to hold")
  (testing "odd seconds"
    (is (= "-1:05" (time/format-clock -65)))
    (is (= "00:00:00.000" (time/format-timestamp -1.5)) "the timestamp formats have no negative times")
    (doseq [x [nil ##NaN ##Inf 1e19 "12"]]
      (is (nil? (time/format-clock x)))
      (is (nil? (time/format-timestamp x))))))
