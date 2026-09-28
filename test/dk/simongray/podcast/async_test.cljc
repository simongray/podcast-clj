(ns dk.simongray.podcast.async-test
  "Deferred values on both platforms: a value or a future on the JVM, a
  value or a promise in ClojureScript, through the same functions, and an
  API that answers in pages read through them."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is #?(:cljs async)]]
            [dk.simongray.podcast.async :as deferred]
            [dk.simongray.podcast.test-support :refer [run]]))

(def pages
  "An API that answers in pages, as the example of clojure.core/iteration
  has it."
  {"a" {:items ["Rose" "Tulip"] :next-page "b"}
   "b" {:items ["Sunflower"] :next-page "c"}
   "c" {:items ["Orchid"]}})

(deftest pages-one-after-another
  (let [asked    (atom [])
        get-page (fn [id]
                   (swap! asked conj id)
                   (let [page (get pages id)]
                     #?(:clj (future page) :cljs (js/Promise.resolve page))))]
    (run #(deferred/then
           (deferred/all [(deferred/iteration get-page :initk "a" :vf :items :kf :next-page)
                          (deferred/iteration get-page :initk "a" :vf :items :kf :next-page :limit 2)
                          (deferred/iteration get-page :initk "a" :vf :items :kf :next-page :limit 0)
                          (deferred/iteration get-page :initk "x" :vf :items :kf :next-page)])
           (fn [[all two none nothing]]
             (is (= [["Rose" "Tulip"] ["Sunflower"] ["Orchid"]] all))
             (is (= [["Rose" "Tulip"] ["Sunflower"]] two) "no page is asked for past the limit")
             (is (= [] none))
             (is (= [] nothing) "a step that gives nothing ends the iteration")
             (is (= ["a" "a" "a" "b" "b" "c" "x"] (sort @asked))
                 "a limit of 0 asks for the first page and keeps nothing, as clojure.core/iteration does under (take 0)"))))))

#?(:clj
   (deftest values-and-futures
     (is (= 2 (deferred/then 1 inc)))
     (is (= 2 (deferred/then (future 1) inc)))
     (is (= [1 2 3] (deferred/all [1 (future 2) 3])))
     (is (= {:value 1} @(deferred/attempt (constantly 1))))
     (is (= "boom" (deferred/explain (:error @(deferred/attempt #(throw (ex-info "boom" {})))))))
     (is (= "deep" (deferred/explain (:error @(deferred/attempt #(throw (StackOverflowError. "deep"))))))
         "an error, as deeply nested input brings about, is a failure like any other")
     (let [started (System/currentTimeMillis)
           results (deferred/all (map (fn [n] (deferred/attempt #(do (Thread/sleep 200) n))) (range 4)))]
       (is (= [{:value 0} {:value 1} {:value 2} {:value 3}] results))
       (is (< (- (System/currentTimeMillis) started) 600) "the attempts ran at once"))))

#?(:cljs
   (deftest values-and-promises
     (async done
       (-> (deferred/then 1 inc)
           (.then (fn [v] (is (= 2 v)) (deferred/then (js/Promise.resolve 1) inc)))
           (.then (fn [v] (is (= 2 v)) (deferred/all [1 (js/Promise.resolve 2) 3])))
           (.then (fn [v] (is (= [1 2 3] v)) (deferred/attempt (constantly 1))))
           (.then (fn [v] (is (= {:value 1} v)) (deferred/attempt #(throw (ex-info "boom" {})))))
           (.then (fn [{:keys [error]}] (is (= "boom" (deferred/explain error)))
                    (deferred/attempt #(js/Promise.reject (js/Error. "nope")))))
           (.then (fn [{:keys [error]}] (is (= "nope" (deferred/explain error)))))
           (.catch (fn [e] (is false (str "rejected: " e))))
           (.finally done)))))
