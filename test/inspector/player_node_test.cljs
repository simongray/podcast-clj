(ns inspector.player-node-test
  "The browser player's shell on a DOM: mounted on jsdom under Node, it
  loads an episode the URL asks for, renders the bar and the queue page,
  keeps the state in localStorage, and a click on a skip button moves the
  audio element. Node only: the browser build runs the views' tests."
  (:require ["jsdom" :as jsdom]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing async]]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.util.time :as time]
            [inspector.player :as shell]))

(def page-html
  "The inspector's page skeleton the player mounts into."
  "<!DOCTYPE html><html><head><title>Queue</title></head><body><div id=\"player-bar\"></div><main id=\"content\"><div id=\"app\"></div></main></body></html>")

(def episode
  "What /episode.edn answers for the episode the URL adds."
  {:id         ["feed" "a"]
   :title      "First"
   :feed-title "Show"
   :enclosure  "https://x/a.mp3"
   :duration   3600
   :url        "https://x/rss"
   :guid       "a"})

(defn- install-dom!
  "A jsdom window at `url`, its DOM installed as the globals the player and
  Replicant reach for."
  [url]
  (let [dom    (jsdom/JSDOM. page-html #js {:url url :pretendToBeVisual true})
        window (.-window dom)]
    (doseq [k ["window" "document" "localStorage" "location" "history" "URL" "URLSearchParams"
               "DOMParser" "FormData" "requestAnimationFrame" "Node" "HTMLElement" "Event"]]
      (aset js/globalThis k (aget window k)))
    (set! (.-fetch js/globalThis)
          (fn [_]
            (js/Promise.resolve #js {:ok true :text (fn [] (js/Promise.resolve (pr-str episode)))})))
    dom))

(defn- buttons
  [container]
  (array-seq (.querySelectorAll container "button")))

(deftest mounted-on-a-dom
  (async done
    ;; port 1, where nothing listens, so that the player's sync never
    ;; reaches the folder of an inspector running on this machine
    (install-dom! "http://localhost:1/queue?add=https%3A%2F%2Fx%2Frss&guid=a&mode=next")
    (shell/init)
    (js/setTimeout
     (fn []
       (let [bar   (js/document.getElementById "player-bar")
             app   (js/document.getElementById "app")
             audio (js/document.getElementById "player")]
         (testing "the episode the URL asked for is playing, on the bar and the page"
           (is (str/includes? (.-textContent bar) "First"))
           (is (str/includes? (.-textContent app) "Now playing"))
           (is (some? audio))
           (is (= "https://x/a.mp3" (.-src audio)))
           (is (= "/queue" (.-pathname js/location)) "the add parameters are gone from the URL"))
         (testing "the state is kept in localStorage, with the episode as a remote item for the other devices"
           (is (str/includes? (str (.getItem js/localStorage shell/storage-key)) "\"a\""))
           (is (= "https://x/rss"
                  (:feed-url (get (profile/remote-items (get-in @shell/app [:library :profile])) ["feed" "a"]))))
           (is (nil? (profile/problem (get-in @shell/app [:library :profile])))))
         (testing "the audio element's reports show on the bar"
           (set! (.-currentTime audio) 100)
           (.dispatchEvent audio (js/Event. "timeupdate"))
           (is (str/includes? (.-textContent bar) "at 1:40")))
         (testing "a click on the skip button moves the audio by the preference"
           (.click (first (filter #(str/starts-with? (.-textContent %) "+") (buttons bar))))
           (is (= 130 (.-currentTime audio)))
           (.click (first (filter #(str/starts-with? (.-textContent %) "−") (buttons bar))))
           (is (= 115 (.-currentTime audio))))
         (testing "the page reflects the position and the change of a preference"
           (let [input (.querySelector app "input[type=number]")]
             (set! (.-value input) "20")
             (.dispatchEvent input (js/Event. "change" #js {:bubbles true}))
             (is (str/includes? (.-textContent bar) "−20s"))))
         (testing "what another tab saves is merged in"
           (let [other {:library (update (:library @shell/app) :profile profile.queue/enqueue ["feed" "b"] (js/Date.))
                        :titles  {["feed" "b"] (assoc episode :id ["feed" "b"] :title "From the other tab")}}]
             (.dispatchEvent js/window (new (.-StorageEvent js/window) "storage"
                                            #js {:key shell/storage-key :newValue (pr-str other)}))
             (is (str/includes? (.-textContent app) "From the other tab"))
             (is (str/includes? (.-textContent bar) "First") "this tab's episode plays on")))
         (testing "a click on Play now makes a queued episode the current one"
           (.click (first (filter #(= "Play now" (.-textContent %)) (buttons app))))
           (is (= ["feed" "b"] (profile.queue/current (get-in @shell/app [:library :profile]))))
           (is (str/includes? (.-textContent bar) "From the other tab"))
           (is (= [["feed" "b"] ["feed" "a"]] (profile.queue/queue (get-in @shell/app [:library :profile]))) "and the other waits"))
         (testing "the browser has an id of its own and a name"
           (is (= (get-in @shell/app [:library :device]) (.getItem js/localStorage shell/device-key)))
           (is (string? (get-in @shell/app [:library :profile :devices (.getItem js/localStorage shell/device-key) :name])))))
       ;; closing the window stops the player's timer of syncs
       (.close js/window)
       (done))
     100)))

(deftest merging-into-the-page-state
  (let [t      #(time/->inst (+ (inst-ms #inst "2026-01-01T00:00:00Z") (* % 24 60 60 1000)))
        a      ["feed" "a"]
        b      ["feed" "b"]
        page   (fn [device id at] {:library (update (library/make device {:at (t 0)}) :profile profile.queue/enqueue id at) :titles {}})
        mine   (assoc-in (page "browser" a (t 0)) [:titles a] {:title "A"})
        other  (assoc-in (page "browser" b (t 1)) [:titles b] {:title "B"})]
    (testing "another tab's state joins this one's, titles and all"
      (let [merged (shell/with-other-tab mine other)]
        (is (= [a b] (profile.queue/queue (get-in merged [:library :profile]))))
        (is (= {a {:title "A"} b {:title "B"}} (:titles merged)))))
    (testing "and the cursors of the other tab's later sync, so the next step knows what it wrote"
      (let [step   {:at (t 40) :cursors {"inspector" {:versions {"browser.edn" "7"}}} :report []}
            merged (shell/with-other-tab mine (assoc-in other [:library :sync] step))]
        (is (= step (get-in merged [:library :sync])))))
    (testing "a sync step merges its result into the page's library, and gives the state of the step"
      (let [synced (shell/with-synced mine (assoc (:library other) :sync {:at (t 40) :report []}))]
        (is (= [a b] (profile.queue/queue (get-in synced [:library :profile]))) "what this tab did meanwhile stays")
        (is (= {:at (t 40) :report []} (get-in synced [:library :sync])))
        (is (false? (:syncing? synced)))
        (is (= {:at (t 40) :report []} (:synced synced)))))))
