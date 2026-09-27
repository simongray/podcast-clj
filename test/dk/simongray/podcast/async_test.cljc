(ns dk.simongray.podcast.async-test
  "Deferred values on both platforms: a value or a future on the JVM, a
  value or a promise in ClojureScript, through the same three functions."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is #?(:cljs async)]]
            [dk.simongray.podcast.async :as deferred]))

#?(:clj
   (deftest values-and-futures
     (is (= 2 (deferred/then 1 inc)))
     (is (= 2 (deferred/then (future 1) inc)))
     (is (= [1 2 3] (deferred/all [1 (future 2) 3])))
     (is (= {:value 1} @(deferred/attempt (constantly 1))))
     (is (= "boom" (deferred/explain (:error @(deferred/attempt #(throw (ex-info "boom" {})))))))
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
