(ns inspector.player-view-test
  "The browser player's views as data: what the bar and the queue page
  show for a state, and the events their controls raise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [inspector.player.view :as view]))

(def t0 #inst "2026-01-01T00:00:00Z")
(def a ["feed" "a"])
(def b ["feed" "b"])

(def titles
  {a {:title "First" :feed-title "Show" :url "https://x/rss" :guid "a" :enclosure "https://x/a.mp3"}
   b {:title "Second" :feed-title "Show" :url "https://x/rss" :guid "b" :enclosure "https://x/b.mp3"}})

(def state
  {:profile (-> (profile/make "browser")
                (profile.queue/enqueue a t0)
                (profile.queue/enqueue b t0)
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
    (is (= [:media/mounted a {:src "https://x/a.mp3" :position 120 :playback-rate 1.0}]
           (:replicant/on-mount (second audio)))
        "the audio element loads the episode when it's mounted, at the position to resume at and the show's rate")
    (is (= [:media/unmounted a] (:replicant/on-unmount (second audio))))
    (is (= {:ended [:ended a]} (:on (second audio))) "the end, which the library is told of")
    (is (nil? (:src (second audio))) "media/source! sets the source, so that the position and rate come with it")
    (is (= [[:press {:action :play}] [:muted true] [:skip a :back] [:skip a :forward] [:ended a] [:dequeue a] [:sleep 1]]
           (map #(get-in % [1 :on :click]) buttons)))
    (is (= ["Play" "Mute" "−15s" "+30s" "Ended" "Remove" "Stop after this episode"] (map #(nth % 2) buttons))
        "the listener's preferences on the buttons")
    (let [button (last (elements (view/bar (assoc state :sleep {:episodes 1})) :button))]
      (is (= [[:sleep nil] "Play on after this episode"] [(get-in button [1 :on :click]) (nth button 2)])
          "a timer that runs, and the button that stops it"))
    (is (str/includes? (text bar) "First"))
    (is (str/includes? (text bar) "at 2:00") "the resume position until the audio reports"))
  (testing "a video in a video element, with the same events"
    (let [bar   (view/bar (assoc-in state [:titles a :video?] true))
          video (first (elements bar :video#player))]
      (is (empty? (elements bar :audio#player)))
      (is (= [:media/mounted a {:src "https://x/a.mp3" :position 120 :playback-rate 1.0}]
             (get-in video [1 :replicant/on-mount])))))
  (testing "the browser's answer to playing it"
    (let [bar (view/bar (assoc state :answer {:playing true :muted true} :media {:muted true}))]
      (is (str/includes? (text bar) "Playing muted"))
      (is (some #{[:muted false]} (map #(get-in % [1 :on :click]) (elements bar :button)))))
    (is (not (str/includes? (text (view/bar (assoc state :answer {:playing true :muted true} :media {:muted false})))
                            "Playing muted"))
        "an element unmuted by its own controls")
    (is (str/includes? (text (view/bar (assoc state :answer {:refused :not-allowed :waiting true}))) "next click")
        "while the player waits for it")
    (is (str/includes? (text (view/bar (assoc state :answer {:refused :not-allowed}))) "won't let this page play")
        "and once the browser has refused the second try too"))
  (testing "the controls of the media element, which press the lock screen's handlers"
    (let [media {:paused false :current-time 750 :duration 3000 :playback-rate 1.5 :volume 0.125 :muted false}
          bar   (view/bar (assoc state :media media :volume-locked? false))
          [_ attrs [_ played]] (first (elements bar :div.seek-bar))]
      (is (some #{[:press {:action :pause}]} (map #(get-in % [1 :on :click]) (elements bar :button))) "pause while it plays")
      (is (= {:role "slider" :aria-valuemin 0 :aria-valuemax 3000 :aria-valuenow 750 :aria-label "Position" :tabindex 0}
             (select-keys attrs [:role :aria-valuemin :aria-valuemax :aria-valuenow :aria-label :tabindex])))
      (is (= {:pointerdown [:seek-at] :pointermove [:seek-at] :pointerup [:seek-at]} (:on attrs))
          "pressed and dragged")
      (is (= "25%" (get-in played [:style :width])))
      (is (= [:rate "feed"] (get-in (first (elements bar :select)) [1 :on :change])))
      (is (= ["1.5"] (for [[_ {:keys [value selected]}] (elements bar :option) :when selected] value))
          "the rate it plays at")
      (is (= 0.5 (:value (second (first (elements bar :input))))) "the volume slider on the cubic curve"))
    (is (some #{"1.1"} (map #(get-in % [1 :value]) (elements (view/bar (assoc state :media {:playback-rate 1.1})) :option)))
        "a rate of the show that the bar doesn't offer")
    (is (empty? (elements (view/bar (assoc state :volume-locked? true)) :input)) "no volume slider where the browser locks the volume")
    (is (empty? (elements (view/bar state) :input)) "nor before the browser has said"))
  (testing "the chapter that plays and the transcript, from the tracks of the media element"
    (let [tracks [{:kind :chapters :mode :hidden :active-cues [{:start-time 95 :end-time 1830 :text "News"}]}
                  {:kind :captions :mode :hidden :language "en" :active-cues [{:start-time 100 :end-time 104 :text "Adam: Hello"}]}]
          bar    (view/bar (assoc state :tracks tracks))
          shown  (view/bar (assoc state :tracks tracks :transcript? true))]
      (is (str/includes? (text bar) "News"))
      (is (some #{[:transcript true]} (map #(get-in % [1 :on :click]) (elements bar :button))))
      (is (not (str/includes? (text bar) "Adam: Hello")) "hidden until the listener asks")
      (is (some #{[:transcript false]} (map #(get-in % [1 :on :click]) (elements shown :button))))
      (is (= [[:div.cue {:aria-live "polite"} "Adam: Hello"]] (elements shown :div.cue)) "the line that plays, for audio")
      (is (empty? (elements shown :select.captions)))
      (let [video (view/bar (-> state
                                (assoc :tracks tracks :transcript? true :caption-style "Large")
                                (assoc-in [:titles a :video?] true)))]
        (is (empty? (elements video :div.cue)) "a video shows its captions itself")
        (is (= ["Large"] (for [[_ {:keys [selected]} name] (elements video :option) :when selected
                               :when (contains? view/caption-styles name)]
                           name))
            "the style of the captions")))
    (is (empty? (filter #(= "Transcript" (nth % 2)) (elements (view/bar state) :button))) "no transcript, no button"))
  (testing "the devices: Picture-in-Picture and fullscreen for a video, and casting"
    (let [clicks #(map (fn [b] (get-in b [1 :on :click])) (elements % :button))
          video  (-> state
                     (assoc-in [:titles a :video?] true)
                     (assoc :pip {:picture-in-picture false} :remote {:availability :unknown :state :disconnected}))]
      (is (= [[:pip true] [:fullscreen] [:cast]] (filter (comp #{:pip :fullscreen :cast} first) (clicks (view/bar video)))))
      (is (some #{[:pip false]} (clicks (view/bar (assoc video :pip {:picture-in-picture true :width 320 :height 180})))))
      (is (empty? (filter (comp #{:pip :fullscreen} first) (clicks (view/bar (assoc state :remote {:availability :available})))))
          "neither for audio")
      (is (some #{"Casting"} (map #(nth % 2) (elements (view/bar (assoc state :remote {:availability :available :state :connected})) :button))))
      (is (empty? (filter (comp #{:cast} first) (clicks (view/bar (assoc state :remote {:availability :unavailable})))))
          "no casting without devices")
      (is (str/includes? (text (view/bar (assoc state :device-answer {:what "Casting" :refused :no-devices}))) "Casting refused: no devices"))
      (is (not (str/includes? (text (view/bar (assoc state :device-answer {:what "Casting" :refused :dismissed}))) "refused"))
          "a closed picker is no refusal")))
  (testing "a link's start, over the position to resume at"
    (is (= 95 (get-in (first (elements (view/bar (assoc state :start {a 95})) :audio#player))
                      [1 :replicant/on-mount 2 :position]))))
  (testing "the readout the media element reported, and an episode without an enclosure"
    (is (str/includes? (text (view/bar (assoc state :media {:current-time 60 :duration 3600}))) "at 1:00 of 1:00:00"))
    (is (not (str/includes? (text (view/bar (assoc state :media {:current-time 60 :duration ##Inf}))) "at 1:00 of"))
        "a live stream, which has no end")
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

(deftest what-the-browser-plays
  (is (= ["plays, smoothly" "plays" "probably plays" "may play" "won't play"]
         (map view/playable [{:supported true :smooth true}
                             {:supported true :smooth false}
                             {:supported false :can-play-type :probably}
                             {:supported false :can-play-type :maybe}
                             {:supported false :can-play-type nil}]))))

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
