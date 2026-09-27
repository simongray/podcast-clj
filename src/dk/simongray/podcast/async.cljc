(ns dk.simongray.podcast.async
  "Deferred values on both platforms, so that the sync and its stores are
  written once: a deferred value is a future on the JVM and a promise in
  ClojureScript, and `then`, `all` and `attempt` take a value or a deferred
  one alike.")

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
  that several attempts run at once and `all` waits for them."
  [f]
  #?(:clj  (future (try {:value (f)} (catch Exception e {:error e})))
     :cljs (-> (js/Promise.resolve nil)
               (.then (fn [_] (f)))
               (.then (fn [v] {:value v}))
               (.catch (fn [e] {:error e})))))

(defn explain
  "The message of the failure `e`, an exception or whatever else was
  thrown."
  [e]
  (or (ex-message e) (str e)))
