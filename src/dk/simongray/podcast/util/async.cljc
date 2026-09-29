(ns dk.simongray.podcast.util.async
  "Deferred values on both platforms, so that the sync and its stores are
  written once: a deferred value is a future on the JVM and a promise in
  ClojureScript, and `then`, `all`, `attempt` and `iteration` take a value
  or a deferred one alike."
  (:refer-clojure :exclude [iteration])
  (:require [clojure.string :as str]))

(defn then
  "`f` of `x` once `x` is a value: at once when it is one, and when `x`, a
  future on the JVM or a promise in ClojureScript, has its value."
  [x f]
  #?(:clj  (f (if (future? x) @x x))
     :cljs (.then (js/Promise.resolve x) f)))

(defn all
  "The values of `xs`, each a value or a deferred one, as a vector: on the
  JVM at once, every future under way before the first is waited for; in
  ClojureScript a promise of it."
  [xs]
  #?(:clj  (mapv #(if (future? %) @% %) (vec xs))
     :cljs (.then (js/Promise.all (into-array xs)) vec)))

(defn attempt
  "`(f)` as `{:value v}`, or `{:error e}` when it threw or, in
  ClojureScript, rejected; deferred, on a future of its own on the JVM, so
  that several attempts run at once and `all` waits for them. On the JVM an
  error counts too, such as the StackOverflowError of deeply nested input,
  as anything thrown does in ClojureScript."
  [f]
  #?(:clj  (future (try {:value (f)} (catch Throwable e {:error e})))
     :cljs (-> (js/Promise.resolve nil)
               (.then (fn [_] (f)))
               (.then (fn [v] {:value v}))
               (.catch (fn [e] {:error e})))))

(defn iteration
  "The values that `clojure.core/iteration` gives for `step!` and the
  options `:somef`, `:vf`, `:kf` and `:initk`, as a vector of at most
  `:limit` values. On the JVM the vector, in ClojureScript a promise of it.

  `step!` gives a value or a deferred one. So both platforms read an API
  that answers in pages as the example of `clojure.core/iteration` does:

      (iteration get-page! :initk \"a\" :vf :items :kf :next-page :limit 10)"
  [step! & {:keys [somef vf kf initk limit] :or {somef some? vf identity kf identity limit ##Inf}}]
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
  "The message of the failure `e`, with the messages of its causes that add
  to it. `e` is an exception, or another value that was thrown.

  The client of the JDK and the fetch of Node give the reason that a
  connection failed in a cause. An example is `java.net.ConnectException:
  java.nio.channels.UnresolvedAddressException`."
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
