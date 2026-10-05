(ns dk.simongray.podcast.sync
  "Syncing the profile between a listener's devices, over any number of
  backends.

  A backend is a map of two functions and a name, and doc/sync.md explains
  the design:

      {:pull (fn [state cursor at])  ; the states of the other devices
       :push (fn [state cursor at])  ; writes the merged state
       :name \"Nextcloud\"}

  A pull returns a map of the :states to merge, the next :cursor and a
  :report. It has :partial? when the states are changes since the cursor
  rather than whole states. A push returns the next :cursor and a :report, and
  a :state when it learned more while writing. Both take the instant of
  the step and never read a clock.

  Each step of sync! pulls from every backend, merges, and pushes the
  result. Build a backend over a document store with
  dk.simongray.podcast.sync.store. For a backend of your own, document
  writes a device's whole state as a document, read-document reads it
  back, and reconcile merges what the other devices wrote."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.time :as time]))

(def version
  "The version of the document format, which changes only when the meaning
  of a key changes, since a reader merges the keys it doesn't know by their
  shape."
  1)

(defn document
  "The document of `state` written at `at` with `opts`, as an EDN string.

  It's an envelope with the format version, the device, the state, and
  the :app of `opts`, if any."
  ([state at]
   (document state at {}))
  ([state at {:keys [app] :as opts}]
   {:pre [(map? opts)]}
   (let [envelope (cond-> {:version    version
                           :device     (:device state)
                           :written-at (time/date at)
                           :state      state}
                    app (assoc :app app))]
     (binding [*print-length* nil
               *print-level*  nil]
       (pr-str envelope)))))

(defn read-document
  "The envelope in the EDN string `s`, a map of :version, :device,
  :written-at, :app and :state.

  It throws ::malformed when `s` isn't an envelope, or when it's of this
  format version and profile/problem finds a problem in its state."
  [s]
  (let [envelope (try
                   (when (string? s)
                     (edn/read-string s))
                   ;; Throwable on the JVM for the StackOverflowError that
                   ;; deeply nested EDN brings about
                   (catch #?(:clj Throwable :cljs :default) e
                     (throw (ex-info "The document is not EDN"
                                     {:type ::malformed}
                                     e))))]
    (when-not (and (map? envelope)
                   (integer? (:version envelope))
                   (map? (:state envelope)))
      (throw (ex-info "The document is not a sync document"
                      {:type ::malformed})))
    (when-let [problem (and (<= (:version envelope) version)
                            (profile/problem (:state envelope)))]
      (throw (ex-info (str "The document holds a profile that can't be "
                           "merged: " problem)
                      {:type ::malformed})))
    envelope))

(def skew-tolerance
  "How far ahead of now a document can be written before the report says
  that its device's clock is ahead: a minute, in milliseconds."
  60000)

(defn- read-one
  "The `acc` of read-documents with the document `doc` read at `at`: its
  state added, or a diagnostic of what kept it out."
  [acc {:keys [name body] :as doc} at]
  (let [envelope (try
                   (read-document body)
                   (catch #?(:clj Exception :cljs :default) e
                     {:error (or (ex-message e) (str e))}))
        written  (:written-at envelope)
        ahead    (if (inst? written)
                   (- (inst-ms written) (inst-ms at))
                   0)
        report   (fn [acc diagnostic]
                   (update acc :report conj
                           (diagnostics/describe diagnostic)))]
    (cond
      (:error envelope)
      (report acc {:id    :sync/unreadable-document
                   :level :warning
                   :name  name
                   :error (:error envelope)})

      (> (:version envelope) version)
      (report acc {:id      :sync/newer-format
                   :level   :warning
                   :name    name
                   :version (:version envelope)})

      :else
      (cond-> (update acc :states conj (:state envelope))
        (> ahead skew-tolerance)
        (report {:id      :sync/clock-skew
                 :level   :warning
                 :name    name
                 :device  (:device envelope)
                 :seconds (quot ahead 1000)})))))

(defn read-documents
  "The states in the documents `fetched`, read at `at`, as a map of :states
  and a :report.

  Each document is a map of :name and :body. The report has a diagnostic
  for each of these:

  - :sync/unreadable-document, a body that doesn't read
  - :sync/newer-format, a document from a newer version of the library,
    which is left out
  - :sync/clock-skew, a device whose clock is ahead of this one"
  [fetched at]
  (reduce #(read-one %1 %2 at) {:states [] :report []} fetched))

(defn- settle
  "The `state` with the whole `states` acknowledged at `at`, and compacted
  with the options `compact` of profile/compact unless `compact` is false."
  [state states at compact]
  (let [acknowledged (crdt/acknowledge state states at)]
    (if (false? compact)
      acknowledged
      (profile/compact acknowledged at (or compact {})))))

(defn reconcile
  "The `state` with the documents `fetched` merged in at `at` with `opts`,
  as a map of the :state and a :report.

  The documents are as read-documents takes them. What they tell is
  acknowledged in the device registry, and the tombstones that every
  device has seen are compacted away. The :compact of `opts` is false to
  keep the tombstones, or else the options of profile/compact."
  ([state fetched at]
   (reconcile state fetched at {}))
  ([state fetched at {:keys [compact] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [states report]} (read-documents fetched at)]
     {:state  (settle (reduce profile/merge state states) states at compact)
      :report report})))

(def default-cadence
  "How many seconds to wait before syncing again:

  - :after-change, after a change to the local state
  - :idle, when nothing changed
  - :after-failure, after each failure in a row, repeating the last wait"
  {:after-change  10
   :idle          900
   :after-failure [30 60 300 900]})

(defn next-sync
  "When to sync again at `at`, after the last sync `step`, with the waits
  of `cadence`.

  The wait depends on whether the local state has :changed? since the
  step, and on how many :failures in a row it ended with. The result is
  never before `at`, and without a step, it's `at`. The `cadence`
  defaults to default-cadence. The library schedules nothing, so run the
  sync from a timer of your own."
  ([step at]
   (next-sync step at {}))
  ([{:keys [changed? failures] synced-at :at :as step} at cadence]
   {:pre [(map? cadence)]}
   (let [{:keys [after-change idle after-failure]}
         (merge default-cadence cadence)

         failures (or failures 0)
         wait     (cond
                    (pos? failures)
                    (nth after-failure
                         (min (dec failures) (dec (count after-failure))))

                    changed?
                    after-change

                    :else
                    idle)
         due      (if synced-at
                    (time/->inst (+ (inst-ms synced-at) (* 1000 wait)))
                    at)]
     (if (< (inst-ms due) (inst-ms at))
       at
       due))))

(defn- failure
  "The diagnostic of the `backend` whose pull or push failed with `error`."
  [backend error]
  (diagnostics/describe {:id      :sync/backend-failed
                         :backend (:name backend)
                         :reason  (async/explain error)}))

(defn- pulled
  "What the pull `results` of the `backends` add up to, merged into `state`
  at `at` and compacted with `compact`.

  The results are one per backend, in order, each with a :value or an
  :error. The sum is a map of these:

  - :state, with every pulled state merged in, the whole states
    acknowledged, and the tombstones compacted
  - :ok, the backends that didn't fail, each paired with its result
  - :report, the reports of the pulls, and a diagnostic for each backend
    that failed

  A backend whose states are partial, the changes of a service since a
  cursor, says so with :partial?, and none of them are acknowledged."
  [state backends results at compact]
  (let [answers (map vector backends results)
        ok      (for [[backend {:keys [value error]}] answers
                      :when (not error)]
                  [backend value])
        failed  (for [[backend {:keys [error]}] answers
                      :when error]
                  (failure backend error))
        states  (mapcat (comp :states second) ok)
        whole   (mapcat (fn [[_ result]]
                          (when-not (:partial? result)
                            (:states result)))
                        ok)]
    {:state  (settle (reduce profile/merge state states) whole at compact)
     :ok     (vec ok)
     :report (-> []
                 (into (mapcat (comp :report second)) ok)
                 (into failed))}))

(defn- finished
  "The result of a step from the pulls `so-far`, the push `results` and the
  last `cursors`, settled at `at` with `compact`.

  There is one push result per backend that didn't fail. The state has
  what a push learned merged in, acknowledged and compacted, and the new
  cursors are by backend name. The report is that of both the pulls and
  the pushes, with a diagnostic for each backend whose push failed."
  [{:keys [state ok report] :as so-far} cursors results at compact]
  (let [pushes  (map (fn [[backend pull] {:keys [value error]}]
                       [backend pull value error])
                     ok
                     results)
        learned (keep (fn [[_ _ push]] (:state push)) pushes)
        cursor  (fn [m [backend pull push error]]
                  (assoc m (:name backend) (:cursor (if error pull push))))
        failed  (for [[backend _ _ error] pushes
                      :when error]
                  (failure backend error))]
    {:state   (if (seq learned)
                (settle (reduce profile/merge state learned) learned at compact)
                state)
     :cursors (reduce cursor (or cursors {}) pushes)
     :report  (-> report
                  (into (mapcat (fn [[_ _ push]] (:report push))) pushes)
                  (into failed))}))

(defn sync!
  "Sync `state` through `backends` in one step with the `opts` below: pull
  from every backend at once, merge, and push the merged state to each.

  It returns this, in ClojureScript as a promise:

      {:state   merged
       :cursors {\"Nextcloud\" {…} …}  ; by backend name
       :report  [...]}               ; what reading and writing found

  In ClojureScript, each function of a backend can return a value or a
  promise. The `opts` are:

  - :at, the instant of the step, the time of the clock by default
  - :cursors, those of the last step
  - :compact, false to keep the tombstones that every device has seen, or
    else the options of profile/compact

  A backend whose pull fails isn't pushed to and keeps its cursor, and one
  whose push fails keeps the cursor of its pull. The report has a
  :sync/backend-failed diagnostic for each, with its :backend and the
  :reason. The state is only ever merged into, so a failed step loses
  nothing. A state in which profile/problem finds a problem isn't pushed,
  since the other devices would leave it out, and the report has
  :sync/malformed-state.

  Run one step at a time, since a step that overlaps another can write an
  older state after a newer one. What the listener did during a step
  isn't in its :state, so don't put the result in place of the state.
  Merge it into the state as it is by then, with profile/merge. Then
  compact as the step did, since the merge brings back the tombstones
  that the step dropped.

  The backends need distinct names, since the cursors are kept by name,
  and the same name twice throws."
  ([state backends]
   (sync! state backends {}))
  ([state backends {:keys [at cursors compact] :as opts}]
   {:pre [(map? opts)]}
   (when-let [twice (seq (for [[name n] (frequencies (map :name backends))
                               :when (< 1 n)]
                           name))]
     (throw (ex-info (str "The backends of one sync need distinct names, "
                          "and these are not: " (pr-str twice))
                     {:names twice})))
   (let [at    (or at (time/now))
         pull! (fn [{:keys [pull name]}]
                 (async/attempt #(pull state (get cursors name) at)))]
     (async/then
      (async/all (map pull! backends))
      (fn [results]
        (let [so-far    (pulled state backends results at compact)
              merged    (:state so-far)
              problem   (profile/problem merged)
              push!     (fn [[{:keys [push]} pull]]
                          (if problem
                            {:cursor (:cursor pull)}
                            (push merged (:cursor pull) at)))
              attempt!  (fn [ok]
                          (async/attempt #(push! ok)))
              malformed {:id :sync/malformed-state :error problem}
              finish    (fn [pushes]
                          (cond-> (finished so-far cursors pushes at compact)
                            problem
                            (update :report conj
                                    (diagnostics/describe malformed))))]
          (async/then (async/all (map attempt! (:ok so-far)))
                      finish)))))))
