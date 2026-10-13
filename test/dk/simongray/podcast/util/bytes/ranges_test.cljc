(ns dk.simongray.podcast.util.bytes.ranges-test
  "The reading of a file by the ranges that a reader needs: the rounds and
  the requests that the ranges make, and the limits of rounds, time and
  bytes, also with a server that ignores ranges."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is #?(:cljs async)]]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.ranges :as ranges]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(defn- zeros
  "A fetch of a file of `total` zero bytes, as `ranges/read!` takes it,
  noting each range in `log`."
  [total log]
  (fn [start end]
    (swap! log conj [start end])
    (let [end (min end total)]
      {:bytes (bytes/of (repeat (- end start) 0)) :start start :total total})))

(deftest reading-by-rounds
  (let [spread (fn [v] (view/reading #(do (view/need-all v (for [i (range 40)] [(* i 100000) (+ (* i 100000) 10)]))
                                          {:read :spread})))
        near   (fn [v] (view/reading #(do (view/need-all v (for [i (range 100)] [(* i 1000) (+ (* i 1000) 10)]))
                                          {:read :near})))
        walk   (fn [v] (view/reading #(loop [p 0 k 0]
                                        (if (= k 10)
                                          {:read :walk}
                                          (do (view/need v p (+ p 4))
                                              (recur (+ p 65537) (inc k)))))))
        cases  [[spread 40 "40 ranges far apart, 16 requests a round"]
                [near 1 "100 ranges near each other, in one request"]
                [walk 4 "a walk through segments of 64 KB, which reads more ahead each round"]]
        run    (fn [[reader n message]]
                 (let [log (atom [])]
                   (async/then (ranges/read! (zeros 4000000 log) reader)
                               (fn [r]
                                 (is (:read r) message)
                                 (is (= n (count @log)) message)))))]
    #?(:clj  (run! run cases)
       :cljs (async done
               (-> (reduce (fn [p c] (.then p #(run c))) (js/Promise.resolve nil) cases)
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))

(defn- places
  "A reader that needs four bytes at each of the offsets `ps`, one after
  another."
  [ps]
  (fn [v]
    (view/reading #(do (doseq [p ps] (view/need v p (+ p 4)))
                       {:read ps}))))

(deftest the-limits-of-a-reading
  (let [reading (fn [fetch-of reader opts]
                  (let [log (atom [])]
                    (async/then (ranges/read! (fetch-of 4000000 log) reader opts)
                                (fn [r] [(or (:read r) (map :id (view/report r))) @log]))))
        whole   (fn [total log]
                  (fn [start end]
                    (swap! log conj [start end])
                    {:bytes (bytes/of (repeat (min end total) 0)) :start 0 :total total :whole? true}))
        ticking (fn []
                  (let [ms (atom 0)]
                    #(time/->inst (swap! ms + 25000))))]
    (run
     #(async/then (reading zeros (places [1000000 0]) {})
                  (fn [[read log]]
                    (is (= [[1000000 0] [[1000000 1065540] [0 65540]]] [read log])
                        "no further ahead after a jump back")))
     #(async/then (reading zeros (places [0 1000000 2000000]) {:max-rounds 2})
                  (fn [[report log]]
                    (is (= [[:file/incomplete] 2] [report (count log)]) "the rounds")))
     #(async/then (reading zeros (places [0 1000000 2000000]) {:max-seconds 60 :now-fn (ticking)})
                  (fn [[report log]]
                    (is (= [[:file/incomplete] [[0 65540]]] [report log])
                        "no request after the time of the reading, which the first answer used up")))
     #(async/then (reading whole (places [0 300000 500000]) {})
                  (fn [[read log]]
                    (is (= [[0 300000 500000] [[0 65540] [0 365540] [0 565540]]] [read log])
                        "a server that ignores ranges is asked once in a round, from the start")))
     #(async/then (reading whole (places [0 300000 500000]) {:max-bytes 400000})
                  (fn [[report log]]
                    (is (= [[:file/incomplete] [[0 65540] [0 334460]]] [report log])
                        "and each request costs all the bytes before its range"))))))
