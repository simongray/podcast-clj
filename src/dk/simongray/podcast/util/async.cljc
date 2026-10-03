(ns dk.simongray.podcast.util.async
  "Futures on the JVM and promises in ClojureScript behind one set of
  functions, so that code that waits for answers is written once.

  The then, all, some, attempt and iteration functions take a value, a
  future or a promise alike, and in-groups makes calls a group at a time."
  (:refer-clojure :exclude [iteration some])
  (:require [clojure.string :as str]))

(defn then
  "The result of `f` on `x` once `x` is a value: at once when it's one, or
  when `x`, a future on the JVM or a promise in ClojureScript, has its
  value."
  [x f]
  #?(:clj  (f (if (future? x) @x x))
     :cljs (.then (js/Promise.resolve x) f)))

(declare attempt)

(defn all
  "The values of `xs`, each a value, a future or a promise, as a vector,
  or with `f`, the results of `f` on each of `xs`. On the JVM, it gives
  the vector once every future has its value, and the futures run at
  once. In ClojureScript, it gives a promise of it.

  With `f`, every call is under way before any is waited for. On the JVM
  each call runs on a future of its own, since a call that uses then
  waits for its own futures. So (all force [(delay (a!)) (delay (b!))])
  makes two different calls at once."
  ([xs]
   #?(:clj  (mapv #(if (future? %) @% %) (vec xs))
      :cljs (.then (js/Promise.all (into-array xs)) vec)))
  ([f xs]
   #?(:clj  (->> (all (map (fn [x] (attempt #(f x))) xs))
                 (map (fn [{:keys [value error]}] (if error (throw error) value)))
                 all)
      :cljs (all (map f xs)))))

(defn in-groups
  "The results of `f` on each of `xs` as a vector, `n` calls at a time, as
  all makes the calls of one group, in a future or a promise."
  [f n xs]
  (reduce (fn [acc group]
            (then acc (fn [done] (then (all f group) #(into done %)))))
          []
          (partition-all n xs)))

(defn some
  "The first logical true result of `f` on the `xs`, or nil, as
  clojure.core/some gives it. On the JVM it gives the result, and in
  ClojureScript a promise of it.

  Each call of `f` gives a value, a future or a promise, and the calls go
  one at a time, so none is made after the first true result."
  [f xs]
  (reduce (fn [found x] (then found #(or % (f x))))
          ;; a promise in ClojureScript, even when there are no xs
          (then nil identity)
          xs))

(defn attempt
  "The result of calling `f` as a map of its :value, in a future or a
  promise. When `f` throws, or in ClojureScript rejects, the map has the
  :error instead.

  On the JVM, each attempt runs on a future of its own, so several run at
  once and all waits for them. An error counts too, such as the
  StackOverflowError of deeply nested input, as anything thrown does in
  ClojureScript."
  [f]
  #?(:clj  (future (try {:value (f)} (catch Throwable e {:error e})))
     :cljs (-> (js/Promise.resolve nil)
               (.then (fn [_] (f)))
               (.then (fn [v] {:value v}))
               (.catch (fn [e] {:error e})))))

(defn iteration
  "The values that clojure.core/iteration gives for `step!` and `opts`, as
  a vector of at most :limit values. On the JVM it gives the vector, and
  in ClojureScript a promise of it.

  The `opts` are the :somef, :vf, :kf and :initk of
  clojure.core/iteration, and :limit. The `step!` function gives a value,
  a future or a promise. So both platforms read an API that answers in
  pages as the example of clojure.core/iteration does:

      (iteration get-page! :initk \"a\" :vf :items :kf :next-page :limit 10)"
  [step! & {:keys [somef vf kf initk limit] :or {somef some? vf identity kf identity limit ##Inf} :as opts}]
  #?(:clj  (into []
                 (take limit)
                 (clojure.core/iteration #(let [x (step! %)] (if (future? x) @x x))
                                         :somef somef :vf vf :kf kf :initk initk))
     :cljs (letfn [(more [values ret]
                     (if (or (not (somef ret)) (>= (count values) limit))
                       values
                       (let [values (conj values (vf ret))
                             k      (when (< (count values) limit) (kf ret))]
                         (if (some? k)
                           (.then (js/Promise.resolve (step! k)) #(more values %))
                           values))))]
             (.then (js/Promise.resolve (step! initk)) #(more [] %)))))

(defn explain
  "The message of the failure `e`, with the messages of its causes that
  add to it. The `e` is an exception, or another value that was thrown.

  The client of the JDK and the fetch of Node give the reason that a
  connection failed in a cause, e.g. a java.net.ConnectException with the
  cause java.nio.channels.UnresolvedAddressException."
  [e]
  (->> (iterate #?(:clj ex-cause :cljs #(some-> % .-cause)) e)
       (take-while some?)
       (take 4)
       (map #(or (ex-message %) (str %)))
       dedupe
       (str/join ": ")))

(comment
  ;; a value, and a deferred value
  (then 1 inc)
  (then (attempt #(/ 6 3)) :value)
  (all [1 (attempt #(throw (ex-info "down" {})))])

  ;; an API that answers in pages, read to its end
  (iteration {nil {:items [1 2] :next "b"} "b" {:items [3]}}
             :vf :items
             :kf :next)

  (explain (ex-info "The request failed" {} (ex-info "Connection refused" {})))
  #_.)
