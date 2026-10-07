(ns inspector.player-view-test
  "The browser player's views as data: what the bar and the queue page
  show for a state, and the events their controls raise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.crdt :as crdt]
            [dk.simongray.podcast.profile :as profile]
            [inspector.player.view :as view]))

(def t0 #inst "2026-01-01T00:00:00Z")
(def a ["feed" "a"])
(def b ["feed" "b"])

(def titles
  {a {:title "First" :feed-title "Show" :url "https://x/rss" :guid "a" :enclosure "https://x/a.mp3"}
   b {:title "Second" :feed-title "Show" :url "https://x/rss" :guid "b" :enclosure "https://x/b.mp3"}})

(def state
  {:profile (-> (profile/make "browser")
                (profile/enqueue a t0)
                (profile/enqueue b t0)
                (profile/progress a 120 3600 t0))
   :titles  titles})

(defn- elements
  "Every element in `hiccup` with `tag`, in document order."
  [hiccup tag]
  (->> (tree-seq sequential? seq hiccup)
       (filter #(and (vector? %) (= tag (first %))))))

(defn- text
  "The text of `hiccup`, the strings in it joined."
  [hiccup]
  (str/join (filter string? (tree-seq sequential? seq hiccup))))

(deftest the-bar
  (is (nil? (view/bar {:profile (profile/make "browser") :titles {}})) "nothing queued, no bar")
  (let [bar     (view/bar state)
        audio   (first (elements bar :audio#player))
        buttons (elements bar :button)]
    (is (= "https://x/a.mp3" (:src (second audio))))
    (is (= {:loadedmetadata [:audio/loaded a 120] :timeupdate [:audio/time a] :pause [:audio/paused a] :ended [:ended a]}
           (:on (second audio)))
        "the audio element reports through events, the position to resume at included")
    (is (= [[:skip a :back] [:skip a :forward] [:ended a] [:dequeue a] [:sleep 1]]
           (map #(get-in % [1 :on :click]) buttons)))
    (is (= ["−15s" "+30s" "Ended" "Remove" "Stop after this episode"] (map #(nth % 2) buttons))
        "the listener's preferences on the buttons")
    (let [button (last (elements (view/bar (assoc state :sleep {:episodes 1})) :button))]
      (is (= [[:sleep nil] "Play on after this episode"] [(get-in button [1 :on :click]) (nth button 2)])
          "a timer that runs, and the button that stops it"))
    (is (str/includes? (text bar) "First"))
    (is (str/includes? (text bar) "at 2:00") "the resume position until the audio reports"))
  (testing "the readout the audio reported, and an episode without an enclosure"
    (is (str/includes? (text (view/bar (assoc state :readout "at 1:00 of 1:00:00"))) "at 1:00 of 1:00:00"))
    (let [bar (view/bar (assoc-in state [:titles a :enclosure] nil))]
      (is (empty? (elements bar :audio#player)))
      (is (str/includes? (text bar) "No enclosure to play."))))
  (testing "an episode that came through a sync"
    (let [bar (view/bar (update state :titles dissoc a))]
      (is (empty? (elements bar :audio#player)))
      (is (str/includes? (text bar) "This browser has no data of the episode."))
      (is (= [[:code "[\"feed\" \"a\"]"]] (elements bar :code)) "the id, and no link to a page")
      (is (empty? (elements bar :a)))
      (is (some #{[:dequeue a]} (map #(get-in % [1 :on :click]) (elements bar :button)))
          "the listener removes it"))
    (is (= (titles a) (view/episode-of state a)) "the data that /episode.edn served")
    (is (nil? (view/episode-of state ["feed" "z"])))))

(deftest the-page
  (let [page (view/page state)]
    (testing "now playing, up next with its controls, the history and the preferences"
      (is (str/includes? (text page) "Now playing: First"))
      (let [[item :as items] (elements page :li)]
        (is (= 2 (count items)) "the one up next, and the one in the history")
        (is (str/includes? (text item) "Second"))
        (is (= [[:play b] [:play-next b] [:dequeue b]] (map #(get-in % [1 :on :click]) (elements item :button))))
        (is (empty? (elements (second items) :button)) "the episode that plays has no button in the history"))
      (is (str/includes? (text page) "at 2:00") "the history says where the first one is")
      (let [heard   (update state :profile profile/progress b 60 3600 t0)
            entries (filter #(str/includes? (text %) "Second") (elements (view/page heard) :li))]
        (is (= [[:play b] [:play-next b] [:dequeue b] [:play b]]
               (mapcat (fn [li] (map #(get-in % [1 :on :click]) (elements li :button))) entries))
            "an episode of the history plays again"))
      (let [inputs (filter #(= "number" (get-in % [1 :type])) (elements page :input))]
        (is (= [15 30] (map #(get-in % [1 :value]) inputs)))
        (is (= [[:prefer :skip-back] [:prefer :skip-forward]] (map #(get-in % [1 :on :change]) inputs)))))
    (testing "the devices and the sync through the inspector's folder"
      (let [described (update state :profile crdt/identify {:name "This browser"} t0)
            devices   (view/page (assoc described :synced {:at     t0
                                                           :report [{:id :sync/backend-failed :backend "inspector" :reason "offline" :message "failed"}
                                                                    {:id :sync/clock-skew :message "ahead"}]}))]
        (is (str/includes? (text page) "No device has described itself yet."))
        (is (= [[:sync]] (map #(get-in % [1 :on :click]) (filter #(= "Sync now" (nth % 2)) (elements page :button)))))
        (is (str/includes? (text devices) "This browser"))
        (is (str/includes? (text devices) "failed: inspector: offline"))
        (is (str/includes? (text devices) ":sync/clock-skew ahead"))
        (let [phone  (crdt/identify (profile/make "phone") {:name "Phone"} t0)
              two    (view/page (update described :profile profile/merge phone))
              clicks (keep #(get-in % [1 :on :click]) (elements two :button))]
          (is (str/includes? (text two) "Phonebehind") "a device that hasn't merged this one's changes lags")
          (is (some #{[:device/retire "phone"]} clicks))
          (is (not-any? #{[:device/retire "browser"]} clicks) "this browser can't retire itself here"))))
    (testing "the sync folder: picked, to allow again, or by hand"
      (let [clicks (fn [s] (keep #(get-in % [1 :on :click]) (elements (view/page (merge state s)) :button)))
            folder {:name "Podcasts" :permission :granted}]
        (is (some #{[:folder/pick]} (clicks {:can-pick? true})))
        (is (some #{[:folder/forget]} (clicks {:can-pick? true :folder folder})))
        (is (some #{[:folder/allow]} (clicks {:can-pick? true :folder (assoc folder :permission :prompt)})))
        (is (not-any? #{[:folder/pick] [:folder/allow]} (clicks {:can-pick? false}))
            "a browser that can't pick a folder")
        (is (some #{[:document/save]} (clicks {:can-pick? false})) "but saves by hand")
        (is (= [[:document/load]] (map #(get-in % [1 :on :change])
                                       (filter #(get-in % [1 :webkitdirectory]) (elements page :input)))))))
    (testing "an empty state and an error"
      (let [empty-page (view/page {:profile (profile/make "browser") :titles {}})]
        (is (str/includes? (text empty-page) "Nothing queued."))
        (is (str/includes? (text empty-page) "Nothing played yet.")))
      (is (= [[:p.error "no such episode"]]
             (elements (view/page (assoc state :error "no such episode")) :p.error))))))
