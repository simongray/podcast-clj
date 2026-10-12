(ns dk.simongray.podcast.app.media-session-test
  "The Media Session of the episode that plays: its actions, the player
  events of each, the position state, and the session of a browser."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.media-session :as media-session]
            [dk.simongray.podcast.feed.guid :as feed.guid]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def now #inst "2026-09-30T12:00:00Z")

(def url "https://example.com/feed.xml")

(def show
  (rss/parse (fixture "feeds/namespace-example.xml")))

(def guid (feed.guid/feed-guid show url))

(def id
  "The episode that plays, whose chapters are in a file."
  [guid "https://example.com/ep0003"])

(def other
  "The episode after it in the queue."
  [guid "https://example.com/ep0002"])

(def files
  "What read-files! can give for the episode: three chapters, one of them
  out of the table of contents."
  {:chapters {:source   :file
              :document {:chapters [{:start-time 0 :title "Intro"}
                                    {:start-time 60 :title "Ads" :toc false}
                                    {:start-time 94 :title "Talk"}]}}})

(def lib
  (-> (library/make "phone" {:config {:search? false :now-fn (constantly now)}})
      (library/subscribe url {:status 200 :url url :feed show})
      (library/update-profile profile.queue/enqueue id)
      (library/update-profile profile.queue/enqueue other)))

(def heard
  "The library after the listener heard the other episode, then this one."
  (-> lib
      (library/update-profile profile/progress other 600)
      (library/update-profile profile/progress id 1)))

(defn- buttons
  "The library `l` with the listener's :track-buttons set to `mode`."
  [l mode]
  (library/update-profile l profile/prefer {:track-buttons mode}))

(defn- press
  "The effects of the action of `details` on the episode that plays in
  `l`, at `position`, with the `opts` of library/playback."
  [l position details opts]
  (:effects (reduce library/step
                    l
                    (media-session/events l id position details opts))))

(deftest the-actions-of-an-episode
  (let [all (into media-session/seek-actions media-session/track-actions)]
    (is (= media-session/seek-actions (media-session/actions lib id))
        "no chapters, so no track buttons")
    (is (= all (media-session/actions lib id {:read files})))
    (is (= all (media-session/actions (buttons lib :queue) id)))))

(deftest seeking
  (is (= [[:progress id 754 nil] [:skip id :forward]]
         (media-session/events lib id 754 {:action "seekforward"})))
  (is (= [[:seek id 739]] (press lib 754 {:action "seekbackward"} {})) "the listener's 15 seconds")
  (is (= [[:seek id 744]] (press lib 754 {:action "seekbackward" :seekOffset 10} {})))
  (is (= [[:seek id 1200]] (press lib 754 {:action "seekto" :seekTime 1200} {})))
  (is (= [[:progress id 0 nil] [:skip id 30]]
         (media-session/events lib id ##NaN {:action "seekforward" :seekOffset 30}))
      "a position that isn't known yet")
  (is (= [] (media-session/events lib id 754 {:action "play"}))
      "the browser plays and pauses the element itself"))

(deftest the-track-buttons
  (let [chapters {:read files}]
    (testing "chapters, by default"
      (is (= [[:seek id 94]] (press lib 30 {:action "nexttrack"} chapters)))
      (is (= [] (media-session/events lib id 120 {:action "nexttrack"} chapters))
          "nothing after the last chapter")
      (is (= [[:seek id 94]] (press lib 120 {:action "previoustrack"} chapters))
          "the start of the chapter that plays")
      (is (= [[:seek id 0]] (press lib 95 {:action "previoustrack"} chapters))
          "or the one before, in its first seconds"))
    (testing "the episodes of the queue"
      (let [l (buttons heard :queue)]
        (is (= [[:play other]] (press l 120 {:action "nexttrack"} chapters)))
        (is (= [[:seek id 0]] (press l 120 {:action "previoustrack"} chapters)) "the start of the episode")
        (is (= [[:play other]] (press l 1 {:action "previoustrack"} {}))
            "or the one heard before, in its first seconds")))
    (testing "chapters, then the queue"
      (let [l (buttons heard :chapters-first)]
        (is (= [[:seek id 94]] (press l 30 {:action "nexttrack"} chapters)))
        (is (= [[:play other]] (press l 120 {:action "nexttrack"} chapters)) "after the last chapter")
        (is (= [[:seek id 94]] (press l 120 {:action "previoustrack"} chapters)))
        (is (= [[:play other]] (press l 1 {:action "previoustrack"} chapters)) "in the first seconds")
        (is (= [[:play other]] (press l 120 {:action "nexttrack"} {})) "and the queue alone without chapters")))
    (testing "a choice of a later version goes to chapters"
      (is (= [[:seek id 94]] (press (buttons lib :somewhere) 30 {:action "nexttrack"} chapters))))))

(deftest the-position-state
  (is (= {:duration 3600 :playbackRate 1.5 :position 754} (media-session/position-state 3600 1.5 754)))
  (is (nil? (media-session/position-state ##NaN 1.0 0)) "until the duration is known")
  (is (= {:duration 3600 :playbackRate 1.0 :position 3600} (media-session/position-state 3600 0 4000))
      "a rate of 0 and a position past the end, which setPositionState refuses")
  (is (= 0 (:position (media-session/position-state 3600 1.0 ##NaN))))
  (is (= {:duration ##Inf :playbackRate 1.0 :position 754} (media-session/position-state ##Inf 1.0 754))
      "a live stream"))

#?(:cljs
   (deftest the-session-of-the-page
     (let [^js session (some-> js/globalThis .-navigator .-mediaSession)
           playback    (library/playback lib id {:read files})
           applied     (media-session/apply! playback
                                             (media-session/actions lib id {:read files})
                                             identity)]
       (if-not session
         (is (nil? applied) "nothing where the browser has none")
         (let [^js metadata (.-metadata session)
               audio        (js/document.createElement "audio")]
           (is (= ["Episode 3 - The Future" "John Doe" "Podcasting 2.0 Namespace Example"]
                  [(.-title metadata) (.-artist metadata) (.-album metadata)]))
           (is (= "https://example.com/images/ep3/pci_avatar-massive.png"
                  (.-src (aget (.-artwork metadata) 0))))
           (when-let [chapters (.-chapterInfo metadata)]
             (is (= ["Intro" "Talk"] (mapv #(.-title ^js %) chapters))))
           (is (nil? (media-session/position! audio)) "an element without a duration yet")
           (media-session/apply! nil #{} identity)
           (is (nil? (.-metadata session)) "and no episode clears it"))))))
