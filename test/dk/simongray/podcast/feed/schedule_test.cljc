(ns dk.simongray.podcast.feed.schedule-test
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.schedule :as schedule]))

(deftest the-next-episode
  (let [feed {:podcast/update-frequency {:rrule {:freq "WEEKLY" :byday ["MO"]}
                                         :dtstart #inst "2026-01-05T18:00:00Z" :content "Mondays"}}]
    (is (= #inst "2026-01-12T18:00:00Z" (schedule/next-episode feed #inst "2026-01-06T00:00:00Z")))
    (is (nil? (schedule/next-episode (assoc-in feed [:podcast/update-frequency :complete] true) #inst "2026-01-06T00:00:00Z")))
    (is (nil? (schedule/next-episode {} #inst "2026-01-06T00:00:00Z")))
    (testing "without dtstart, the latest item anchors the rule"
      (is (= #inst "2026-01-19T09:00:00Z"
             (schedule/next-episode {:podcast/update-frequency {:rrule {:freq "WEEKLY"}}
                                     :rss/items [{:rss/pub-date #inst "2026-01-12T09:00:00Z"}
                                                 {:rss/pub-date #inst "2026-01-05T09:00:00Z"}]}
                                    #inst "2026-01-12T10:00:00Z")))
      (is (= #inst "2026-01-19T09:00:00Z"
             (schedule/next-episode {:podcast/update-frequency {:rrule {:freq "WEEKLY"}}
                                     :rss/items [{:rss/pub-date #inst "2062-01-12T09:00:00Z"}
                                                 {:rss/pub-date #inst "2026-01-12T09:00:00Z"}]}
                                    #inst "2026-01-12T10:00:00Z"))
          "but not an item dated after the moment asked about"))))

(deftest when-to-check-again
  (let [at     #inst "2026-03-01T12:00:00Z"
        weekly (fn [& dates] {:rss/items (map #(hash-map :rss/pub-date %) dates)})]
    (is (= #inst "2026-03-01T12:30:00Z" (schedule/next-check {:rss/ttl 30} at)))
    (is (= #inst "2026-03-02T00:00:00Z" (schedule/next-check {:sy/update-period "daily" :sy/update-frequency 2} at)))
    (testing "the rule wins over everything"
      (is (= #inst "2026-03-02T18:00:00Z"
             (schedule/next-check {:rss/ttl 30
                                   :podcast/update-frequency {:rrule {:freq "WEEKLY" :byday ["MO"]}
                                                              :dtstart #inst "2026-01-05T18:00:00Z"}}
                                  at))))
    (testing "the cadence of recent items, and a late show checked at its cadence"
      (is (= #inst "2026-03-05T09:00:00Z"
             (schedule/next-check (weekly #inst "2026-02-26T09:00:00Z" #inst "2026-02-19T09:00:00Z" #inst "2026-02-12T09:00:00Z") at)))
      (is (= #inst "2026-03-08T12:00:00Z"
             (schedule/next-check (weekly #inst "2026-02-22T09:00:00Z" #inst "2026-02-15T09:00:00Z" #inst "2026-02-08T09:00:00Z") at))))
    (testing "an item dated in the future, a scheduled episode or a mistake, does not count"
      (is (= #inst "2026-03-05T09:00:00Z"
             (schedule/next-check (weekly #inst "2062-02-26T09:00:00Z" #inst "2026-02-26T09:00:00Z"
                                          #inst "2026-02-19T09:00:00Z" #inst "2026-02-12T09:00:00Z")
                                  at))))
    (testing "no answer lies more than a year ahead, however large the ttl or the gap"
      (is (= #inst "2027-03-01T18:00:00Z" (schedule/next-check {:rss/ttl 8999999999999999} at)))
      (is (= #inst "2027-03-01T18:00:00Z" (schedule/next-check (weekly #inst "2026-02-26T09:00:00Z" #inst "2016-02-26T09:00:00Z") at))))
    (is (nil? (schedule/next-check (weekly #inst "2026-02-22T09:00:00Z") at)))
    (is (nil? (schedule/next-check {:rss/ttl 30 :itunes/complete true} at)))
    (testing "no earlier than the server allows"
      (let [later #inst "2026-03-01T14:00:00Z"]
        (is (= later (schedule/next-check {:rss/ttl 30} at {:not-before later})))
        (is (= #inst "2026-03-01T12:30:00Z" (schedule/next-check {:rss/ttl 30} at {:not-before #inst "2026-03-01T12:10:00Z"})))
        (is (= later (schedule/next-check {} at {:not-before later})) "the server's word is a basis of its own")
        (is (nil? (schedule/next-check {:itunes/complete true} at {:not-before later})))))))
