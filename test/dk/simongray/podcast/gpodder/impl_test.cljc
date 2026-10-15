(ns dk.simongray.podcast.gpodder.impl-test
  "The internals of gpodder: the time that an episode action tells."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.gpodder-test :refer [g-a phone t1 t2 t3 t4]]
            [dk.simongray.podcast.gpodder.impl :as gpodder.impl]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]))

(deftest the-time-of-an-action
  (let [id     [g-a "a1"]
        ;; a device with a fast clock, which moves the clock of the phone ahead
        fast   (profile.marks/star (profile/make "laptop") [g-a "a9"] t4)
        s      (-> (profile/merge (phone) fast) (profile/progress id 120 3600 t1))
        action #(gpodder.impl/entry-action id % "https://a.example/rss" "https://a.example/1.mp3" "phone")
        told   (fn [state] ((juxt #(get % "action") #(get % "timestamp")) (action (profile/episode-entry state id))))]
    (is (pos? (compare (:updated-at (profile/episode-entry s id)) t4)) "the stamp is after the clock of the device")
    (is (= ["play" "2026-01-01T01:00:00"] (told s)) "and the action names the time on that clock")
    (is (= ["play" "2026-01-01T02:00:00"] (told (profile/mark-played s id t2))) "the time of the mark as played")
    (is (= ["new" "2026-01-01T03:00:00"] (told (profile/mark-unplayed s id t3))))
    (is (= "2026-01-01T01:00:00" (get (action {:position 10 :updated-at t1 :device "phone"}) "timestamp"))
        "an entry that holds no time of the device tells its stamp")))
