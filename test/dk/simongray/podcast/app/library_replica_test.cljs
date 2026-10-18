(ns dk.simongray.podcast.app.library-replica-test
  "The library of a tab as a replica of like-minded with the library's
  kind: two tabs open one store, a save asked for while another waits its
  turn is that one, and the other tab catches up with it as it's told."
  (:require [clojure.test :refer [deftest is testing async]]
            [dk.simongray.like-minded.replica :as replica]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.profile.marks :as profile.marks]))

(defn- later
  "A promise that settles after `ms` milliseconds."
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest two-tabs-of-one-store
  (async done
    (let [s    (assoc (store/memory) :name "library-replica-test")
          kind (library/replica-kind {})
          one  (atom {})
          two  (atom {})
          open #(replica/open! {:state % :path [:library] :store s :device "phone" :kind kind})
          id   ["feed" "item"]]
      (-> (js/Promise.all [(open one) (open two)])
          (.then
           (fn [[r1 r2]]
             (testing "each tab is a replica of its own"
               (is (library/shared? (:library @one)))
               (is (not= (get-in @one [:library :profile :replica])
                         (get-in @two [:library :profile :replica]))))
             (swap! one update :library library/update-profile profile.marks/star id)
             (let [first-save (replica/save! r1)]
               (is (identical? first-save (replica/save! r1))
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
