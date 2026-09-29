(ns inspector.player-node-test
  "The browser player's shell on a DOM: mounted on jsdom under Node, it
  loads an episode the URL asks for, renders the bar and the queue page,
  keeps the state in localStorage, and a click on a skip button moves the
  audio element. Node only: the browser build runs the views' tests."
  (:require ["jsdom" :as jsdom]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing async]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.time :as time]
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
    (install-dom! "http://localhost:8300/queue?add=https%3A%2F%2Fx%2Frss&guid=a&mode=next")
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
         (testing "the state is kept in localStorage, with the data of the episode for the other devices"
           (is (str/includes? (str (.getItem js/localStorage shell/storage-key)) "\"a\""))
           (is (= "https://x/a.mp3"
                  (get-in (:player @shell/app) [:inspector/episodes ["feed" "a"] :value :enclosure])))
           (is (nil? (player/problem (:player @shell/app)))))
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
           (let [other {:player (player/enqueue (:player @shell/app) ["feed" "b"] (js/Date.))
                        :titles {["feed" "b"] (assoc episode :id ["feed" "b"] :title "From the other tab")}}]
             (.dispatchEvent js/window (new (.-StorageEvent js/window) "storage"
                                            #js {:key shell/storage-key :newValue (pr-str other)}))
             (is (str/includes? (.-textContent app) "From the other tab"))
             (is (str/includes? (.-textContent bar) "First") "this tab's episode plays on")))
         (testing "a click on Play now makes a queued episode the current one"
           (.click (first (filter #(= "Play now" (.-textContent %)) (buttons app))))
           (is (= ["feed" "b"] (player/current (:player @shell/app))))
           (is (str/includes? (.-textContent bar) "From the other tab"))
           (is (= [["feed" "b"] ["feed" "a"]] (player/queue (:player @shell/app))) "and the other waits")))
       (done))
     100)))

(deftest merging-into-the-page-state
  (let [t      #(time/->inst (+ (inst-ms #inst "2026-01-01T00:00:00Z") (* % 24 60 60 1000)))
        a      ["feed" "a"]
        b      ["feed" "b"]
        mine   {:player (player/enqueue (player/state "browser") a (t 0)) :titles {a {:title "A"}}}
        other  {:player (player/enqueue (player/state "browser") b (t 1)) :titles {b {:title "B"}}}]
    (testing "another tab's state joins this one's, titles and all"
      (let [merged (shell/with-other-tab mine other)]
        (is (= [a b] (player/queue (:player merged))))
        (is (= {a {:title "A"} b {:title "B"}} (:titles merged)))))
    (testing "a sync step keeps what the listener did during it, and the tombstones it dropped stay dropped"
      (let [phone  (-> (player/state "phone") (player/enqueue a (t 1)) (player/dequeue a (t 2)))
            laptop (player/acknowledge (player/merge (player/state "laptop") phone) [phone] (t 3))
            phone  (player/acknowledge (player/merge phone laptop) [laptop] (t 3))
            result {:state (player/compact phone (t 40)) :ctx {"inspector" {}} :report []}
            synced (shell/with-synced {:player (player/enqueue phone b (t 40)) :titles {}} result (t 40))]
        (is (= [b] (player/queue (:player synced))))
        (is (not (contains? (:queue (:player synced)) a)))
        (is (= {"inspector" {}} (:sync-ctx synced)))))))
