(ns inspector.player-node-test
  "The browser player's shell on a DOM: mounted on jsdom under Node, it
  loads an episode the URL asks for, renders the bar and the queue page,
  keeps the library in its store and the titles in localStorage, catches
  up with what another tab saves, and a click on a skip button moves the
  audio element. Node only: the browser build runs the views' tests."
  (:require ["jsdom" :as jsdom]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing async]]
            [dk.simongray.like-minded.replica :as replica]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.transit :as transit]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.replica :as app.replica]
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
    ;; a browser's global object is its window, whose events play-nice
    ;; listens to, e.g. pagehide where Node has Web Locks, as from 24.5
    (doseq [k ["addEventListener" "removeEventListener" "dispatchEvent"]]
      (aset js/globalThis k (.bind (aget window k) window)))
    (set! (.-fetch js/globalThis)
          (fn [_]
            (js/Promise.resolve #js {:ok true :text (fn [] (js/Promise.resolve (pr-str episode)))})))
    dom))

(defn- buttons
  [container]
  (array-seq (.querySelectorAll container "button")))

(defn- later
  "A promise that settles after `ms` milliseconds."
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- mounted
  "The tests of the player mounted on the page, which keeps its library in
  `s`, a store in memory."
  [s]
  (let [bar   (js/document.getElementById "player-bar")
        app   (js/document.getElementById "app")
        audio (js/document.getElementById "player")]
    (testing "the episode the URL asked for is playing, on the bar and the page"
      (is (str/includes? (.-textContent bar) "First"))
      (is (str/includes? (.-textContent app) "Now playing"))
      (is (some? audio))
      (is (= "https://x/a.mp3" (.-src audio)))
      (is (= "/queue" (.-pathname js/location)) "the add parameters are gone from the URL"))
    (testing "the library is kept in the store as a replica of this browser, with the episode as a remote item for the other devices, and the titles in localStorage"
      (is (str/includes? (str (.getItem js/localStorage shell/storage-key)) "\"a\""))
      (is (some? ((:fetch s) "library.transit.json" {})))
      (is (library/shared? (:library @shell/app)))
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
    (testing "the browser has an id of its own and a name"
      (is (= (get-in @shell/app [:library :device]) (.getItem js/localStorage shell/device-key)))
      (is (string? (get-in @shell/app [:library :profile :devices (.getItem js/localStorage shell/device-key) :name]))))))

(defn- other-tab!
  "A promise of the tests of what another tab does: a replica of its own
  that saves into the store `s` and tells on the channel, and keeps its
  titles in localStorage."
  [s]
  (let [bar     (js/document.getElementById "player-bar")
        app     (js/document.getElementById "app")
        device  (get-in @shell/app [:library :device])
        channel (replica/channel (app.replica/channel-name s))
        titles  {["feed" "b"] (assoc episode :id ["feed" "b"] :title "From the other tab")}]
    (.dispatchEvent js/window (new (.-StorageEvent js/window) "storage"
                                   #js {:key shell/storage-key :newValue (pr-str {:titles titles})}))
    (-> (library/restore! (library/make device {:replica "9" :config {:codec transit/codec}}) s)
        (.then #(library/save! (library/update-profile % profile.queue/enqueue ["feed" "b"]) s))
        (.then (fn [_]
                 (replica/announce! channel {:saved "memory"})
                 (later 100)))
        (.then (fn []
                 (replica/close! channel)
                 (testing "what another tab saves is caught up with"
                   (is (str/includes? (.-textContent app) "From the other tab"))
                   (is (str/includes? (.-textContent bar) "First") "this tab's episode plays on"))
                 (testing "a click on Play now makes a queued episode the current one"
                   (.click (first (filter #(= "Play now" (.-textContent %)) (buttons app))))
                   (is (= ["feed" "b"] (profile.queue/current (get-in @shell/app [:library :profile]))))
                   (is (str/includes? (.-textContent bar) "From the other tab"))
                   (is (= [["feed" "b"] ["feed" "a"]] (profile.queue/queue (get-in @shell/app [:library :profile])))
                       "and the other waits")))))))

(deftest mounted-on-a-dom
  (async done
    ;; port 1, where nothing listens, so that the player's sync never
    ;; reaches the folder of an inspector running on this machine
    (install-dom! "http://localhost:1/queue?add=https%3A%2F%2Fx%2Frss&guid=a&mode=next")
    (let [s (store/memory)]
      (reset! shell/library-store s)
      (-> (shell/init)
          (.then #(later 100))
          (.then #(mounted s))
          (.then #(other-tab! s))
          (.catch #(is false (str %)))
          ;; closing the window stops the player's timer of syncs
          (.finally (fn []
                      (.close js/window)
                      (done)))))))

(deftest merging-into-the-page-state
  (let [t      #(time/->inst (+ (inst-ms #inst "2026-01-01T00:00:00Z") (* % 24 60 60 1000)))
        a      ["feed" "a"]
        b      ["feed" "b"]
        page   (fn [device id at] {:library (update (library/make device {:at (t 0)}) :profile profile.queue/enqueue id at) :titles {}})
        mine   (assoc-in (page "browser" a (t 0)) [:titles a] {:title "A"})
        other  (assoc-in (page "browser" b (t 1)) [:titles b] {:title "B"})]
    (testing "another tab's titles join this one's, and its library comes through the store"
      (let [merged (shell/with-other-tab mine other)]
        (is (= [a] (profile.queue/queue (get-in merged [:library :profile]))))
        (is (= {a {:title "A"} b {:title "B"}} (:titles merged)))))
    (testing "a sync step merges its result into the page's library, and gives the state of the step"
      (let [synced (shell/with-synced mine (assoc (:library other) :sync {:at (t 40) :report []}))]
        (is (= [a b] (profile.queue/queue (get-in synced [:library :profile]))) "what this tab did meanwhile stays")
        (is (= {:at (t 40) :report []} (get-in synced [:library :sync])))
        (is (false? (:syncing? synced)))
        (is (= {:at (t 40) :report []} (:synced synced)))))))
