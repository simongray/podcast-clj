(ns dk.simongray.podcast.app.unsaved-test
  "The last moves of the player that a save of the library didn't have
  before a reload: the episode played last, and the position. The
  namespace is deprecated, and tested while it's there."
  {:clj-kondo/config '{:linters {:deprecated-namespace {:level :off}}}}
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.unsaved :as unsaved]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]))

(def url "https://example.org/feed.xml")

(def feed
  "A feed of two episodes."
  {:rss/title "A Show"
   :rss/items [{:rss/title     "One"
                :rss/guid      {:content "one"}
                :rss/enclosure {:url "https://example.org/1.mp3" :type "audio/mpeg"}}
               {:rss/title     "Two"
                :rss/guid      {:content "two"}
                :rss/enclosure {:url "https://example.org/2.mp3" :type "audio/mpeg"}}]})

(def now
  "The time of the page's moves, in milliseconds."
  1792000000000)

(deftest the-episode-played-last
  (let [lib     (-> (library/make "test")
                    (library/add-episode url feed "one")
                    (library/add-episode url feed "two"))
        feed-id (first (keys (:feeds lib)))
        one     [feed-id "one"]
        two     [feed-id "two"]
        played  (dissoc (library/step lib [:play one]) :effects)
        current #(some-> % :profile profile.queue/current)]
    (is (= one (current played)))
    (is (= two (current (unsaved/with-kept-play played {:id two :at now})))
        "the episode that the page played last, which no save had")
    (is (= one (current (unsaved/with-kept-play played nil))) "nothing kept")
    (is (= one (current (unsaved/with-kept-play played {:id [feed-id "three"] :at now})))
        "nor an episode that the library hasn't")))

(deftest the-position
  (let [lib      (library/add-episode (library/make "test") url feed "one")
        id       [(first (keys (:feeds lib))) "one"]
        position #(:position (profile/episode-entry (:profile %) id))]
    (is (= 636 (position (unsaved/with-kept-position lib {:id id :position 636 :duration 3600 :at now})))
        "the position that the page kept as it went")
    (is (= lib (unsaved/with-kept-position lib nil)) "nothing kept")
    (is (= lib (unsaved/with-kept-position lib {:id ["other" "x"] :position 5 :at now}))
        "nor an episode that the library hasn't")
    (let [later (unsaved/with-kept-position lib {:id id :position 900 :duration 3600 :at (+ now 1000)})]
      (is (= 900 (position (unsaved/with-kept-position later {:id id :position 636 :at now})))
          "a later position stays"))))

#?(:cljs
   (deftest kept-in-local-storage
     (let [prefix (str "podcast-clj-test-" (random-uuid))]
       (if-not (exists? js/localStorage)
         (is (nil? (unsaved/kept-position prefix)) "nothing, where the platform keeps nothing")
         (do (unsaved/keep-position! prefix ["f" "e"] 60 3600)
             (is (= {:id ["f" "e"] :position 60 :duration 3600}
                    (dissoc (unsaved/kept-position prefix) :at)))
             (unsaved/keep-played! prefix ["f" "e"])
             (unsaved/saved! prefix (- (js/Date.now) 60000))
             (is (= ["f" "e"] (:id (unsaved/kept-played prefix)))
                 "the episode played last, until a save that began after the play")
             (unsaved/saved! prefix (+ (js/Date.now) 1))
             (is (nil? (unsaved/kept-played prefix)))
             (.removeItem js/localStorage (str prefix ".position")))))))

(deftest a-start
  (let [lib (-> (library/make "test")
                (library/add-episode url feed "one")
                (library/add-episode url feed "two"))
        two [(first (keys (:feeds lib))) "two"]
        lib (unsaved/restored lib {:id two :at now} {:id two :position 60 :duration 3600 :at now})]
    (is (= two (profile.queue/current (:profile lib))) "the episode played last")
    (is (= 60 (:position (profile/episode-entry (:profile lib) two))) "where it was")))
