(ns dk.simongray.podcast.app.replica-test
  "The library of a tab as a replica: two tabs open one store, a save asked
  for while another waits its turn is that one, and the other tab catches
  up with it as it's told."
  (:require [clojure.test :refer [deftest is testing async]]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.replica :as replica]
            [dk.simongray.podcast.profile.marks :as profile.marks]))

(defn- later
  "A promise that settles after `ms` milliseconds."
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest two-tabs-of-one-store
  (async done
    (let [s    (assoc (store/memory) :name "replica-test")
          one  (atom {})
          two  (atom {})
          open #(replica/open! {:state % :path [:library] :store s :device "phone" :config {}})
          id   ["feed" "item"]]
      (-> (js/Promise.all [(open one) (open two)])
          (.then
           (fn [[r1 r2]]
             (testing "each tab is a replica of its own, with a channel of the store"
               (is (not= (get-in @one [:library :profile :replica])
                         (get-in @two [:library :profile :replica])))
               (is (= (replica/channel-name s) (get-in @one [:library :config :channel :name]))))
             (swap! one update :library library/update-profile profile.marks/star id)
             (let [first-save  (replica/save! r1)
                   second-save (replica/save! r1)]
               (is (identical? first-save second-save)
                   "a save asked for while another waits its turn is that one")
               (-> first-save
                   (.then (fn [{:keys [library]}]
                            (is (some? (get-in library [:stored :version])))
                            (later 100)))
                   (.then (fn []
                            (testing "the other tab catches up as it's told"
                              (is (= [id] (profile.marks/starred (get-in @two [:library :profile])))))
                            ((:close! r1))
                            ((:close! r2))))))))
          (.catch #(is false (str %)))
          (.finally done)))))
