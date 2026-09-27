(ns dk.simongray.podcast.app.sync
  "Keeping one listener's devices in step: the player state of each device
  carried to the others and merged, over any backend that can hold a
  document or speak a sync service's language; doc/sync.md is the design.

  `document` writes a device's whole state in an envelope and
  `read-document` reads it back; `reconcile` merges what other devices
  wrote into a state, with a report on what it found. A backend is a map
  of two functions and a name:

      {:pull (fn [state ctx at])  ; {:states [state …] :ctx ctx' :report […] :partial? false}
       :push (fn [state ctx at])  ; {:ctx ctx' :state state' :report […]}
       :name \"Nextcloud\"}

  `pull` returns the states of other devices to merge and its cursor for
  next time, `:partial?` when they are changes since a cursor rather than
  whole states; `push` writes the merged state and may return a state of
  its own when it learned more while writing. Both take the instant `at`
  of the step and never read a clock. `dk.simongray.podcast.app.sync.store`
  builds backends over document stores; a sync service is a backend
  directly. `sync!` drives any number of them in one step."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.time :as time]))

(def version
  "The version of the document format, changed only when the meaning of a
  key changes; keys a reader does not know merge by their shape, so growth
  needs no new version."
  1)

(defn document
  "The document for `state` written at `at`: the state in an envelope naming
  the format version, the device and, when `opts` say which `:app` wrote
  it, the app, as an EDN string."
  ([state at]
   (document state at {}))
  ([state at {:keys [app] :as opts}]
   {:pre [(map? opts)]}
   (binding [*print-length* nil
             *print-level*  nil]
     (pr-str (cond-> {:version version :device (:device state) :written-at at :state state}
               app (assoc :app app))))))

(defn read-document
  "The envelope in the EDN string `s`, `{:version :device :written-at :app
  :state}`; a string that is not one throws `::malformed`, and so does one
  of this format version whose state has a value the player cannot take,
  as `player/problem` finds it."
  [s]
  (let [envelope (try
                   (when (string? s) (edn/read-string s))
                   ;; Throwable on the JVM for the StackOverflowError that
                   ;; deeply nested EDN brings about
                   (catch #?(:clj Throwable :cljs :default) e
                     (throw (ex-info "The document is not EDN" {:type ::malformed} e))))]
    (when-not (and (map? envelope) (integer? (:version envelope)) (map? (:state envelope)))
      (throw (ex-info "The document is not a sync document" {:type ::malformed})))
    (when-let [problem (and (<= (:version envelope) version) (player/problem (:state envelope)))]
      (throw (ex-info (str "The document holds a state the player cannot take: " problem)
                      {:type ::malformed})))
    envelope))

(def skew-tolerance
  "How far ahead of now a document may have been written before its writer's
  clock is reported: a minute, in milliseconds."
  60000)

(defn- read-one
  "`acc` with the document `{:name :body}` read at `at`: its state added, or
  what kept it out reported."
  [acc {:keys [name body]} at]
  (let [envelope (try
                   (read-document body)
                   (catch #?(:clj Exception :cljs :default) e
                     {:error (or (ex-message e) (str e))}))
        written  (:written-at envelope)
        ahead    (if (inst? written) (- (inst-ms written) (inst-ms at)) 0)
        report   (fn [acc diagnostic] (update acc :report conj (diagnostics/describe diagnostic)))]
    (cond
      (:error envelope)
      (report acc {:id :sync/unreadable-document :level :warning :name name :error (:error envelope)})

      (> (:version envelope) version)
      (report acc {:id :sync/newer-format :level :warning :name name :version (:version envelope)})

      :else
      (cond-> (update acc :states conj (:state envelope))
        (> ahead skew-tolerance)
        (report {:id :sync/clock-skew :level :warning :name name :device (:device envelope)
                 :seconds (quot ahead 1000)})))))

(defn read-documents
  "The states in the documents `fetched`, each `{:name :body}`, read at the
  instant `at`: `{:states [...] :report [...]}`, the report naming a body
  that would not read (`:sync/unreadable-document`), an envelope from a
  newer library, left out (`:sync/newer-format`), and a device whose clock
  runs ahead of this one's (`:sync/clock-skew`)."
  [fetched at]
  (reduce #(read-one %1 %2 at) {:states [] :report []} fetched))

(defn- settle
  "`state` with the whole states `states` acknowledged at `at` and, unless
  `compact` is false, compacted, `compact` being `player/compact`'s options."
  [state states at compact]
  (let [acknowledged (player/acknowledge state states at)]
    (if (false? compact)
      acknowledged
      (player/compact acknowledged at (or compact {})))))

(defn reconcile
  "`state` with the documents `fetched` (see `read-documents`) merged in at
  `at`, what they told acknowledged in the registry and the tombstones every
  device has seen compacted away: `{:state merged :report [...]}`.
  `:compact` in `opts` is false to leave the tombstones, or the options of
  `player/compact`."
  ([state fetched at]
   (reconcile state fetched at {}))
  ([state fetched at {:keys [compact] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [states report]} (read-documents fetched at)]
     {:state  (settle (reduce player/merge state states) states at compact)
      :report report})))

(def default-cadence
  "How long to wait before syncing again, in seconds: after a change to the
  local state, when nothing changed, and after each failure in a row, the
  last wait repeated."
  {:after-change  10
   :idle          900
   :after-failure [30 60 300 900]})

(defn next-at
  "When to sync next, at the instant `now`: the `last` step's `:at` plus the
  wait `cadence` (default `default-cadence`) gives for its outcome, whether
  the local state has `:changed?` since it and how many `:failures` in a
  row it ended with; never before `now`, and `now` when there was no step
  yet. The library schedules nothing; an app's timer asks this and fires."
  ([last now]
   (next-at last now {}))
  ([{:keys [at changed? failures]} now cadence]
   {:pre [(map? cadence)]}
   (let [{:keys [after-change idle after-failure]} (merge default-cadence cadence)
         failures (or failures 0)
         wait     (cond
                    (pos? failures) (nth after-failure (min (dec failures) (dec (count after-failure))))
                    changed?        after-change
                    :else           idle)
         due      (if at (time/->inst (+ (inst-ms at) (* 1000 wait))) now)]
     (if (< (inst-ms due) (inst-ms now)) now due))))

(defn- pulled
  "What the pulls `results`, one per backend in order, each `{:value}` or
  `{:error}`, add up to: the `:state` with every pulled state merged in,
  those of whole documents acknowledged and the tombstones compacted at
  `at`, the `:ok` backends paired with their results, the `:failed` ones
  with their messages, and the `:report`. A backend whose states are
  partial, a service's changes since a cursor, says so with `:partial?`
  and has nothing acknowledged."
  [state backends results at compact]
  (let [ok     (for [[backend {:keys [value error]}] (map vector backends results) :when (not error)]
                 [backend value])
        states (mapcat (comp :states second) ok)
        whole  (mapcat (fn [[_ result]] (when-not (:partial? result) (:states result))) ok)]
    {:state  (settle (reduce player/merge state states) whole at compact)
     :ok     (vec ok)
     :failed (into {} (for [[backend {:keys [error]}] (map vector backends results) :when error]
                        [(:name backend) (async/explain error)]))
     :report (into [] (mapcat (comp :report second)) ok)}))

(defn- finished
  "The step's result once the pushes `results`, one per `:ok` backend, are
  in: the state with what a push learned merged in, acknowledged and
  compacted, the cursors by backend name over the last step's `ctx`, the
  failures of pull and push, and the report of both."
  [{:keys [state ok failed report]} ctx results at compact]
  (let [pushes  (map (fn [[backend pull] {:keys [value error]}] [backend pull value error]) ok results)
        learned (keep (fn [[_ _ push]] (:state push)) pushes)
        failed  (into failed (for [[backend _ _ error] pushes :when error] [(:name backend) (async/explain error)]))]
    (cond-> {:state  (if (seq learned)
                       (settle (reduce player/merge state learned) learned at compact)
                       state)
             :ctx    (reduce (fn [m [backend pull push error]]
                               (assoc m (:name backend) (if error (:ctx pull) (:ctx push))))
                             (or ctx {})
                             pushes)
             :report (into report (mapcat (fn [[_ _ push]] (:report push))) pushes)}
      (seq failed) (assoc :failed failed))))

(defn sync!
  "One step of keeping `state` in step through `backends`, at `at`: pull
  from every backend at once, merge what they return into `state`, push the
  merged state to each, and return

      {:state  merged
       :ctx    {\"Nextcloud\" {…} …}   ; the cursors, by backend name, to pass back next time
       :failed {\"Dropbox\" \"…\"}     ; the backends that failed, with the message; absent when none did
       :report [...]}                ; what reading and writing found

  On the JVM the result is returned, in ClojureScript promised, and there
  each function of a backend may return a value or a promise. `opts` carry
  the `:ctx` of the last step, and `:compact`: false to leave the
  tombstones every device has seen in place, or the options of
  `player/compact`, which otherwise drops them. A backend that fails in
  its pull is not pushed to and keeps its cursor, one that fails in its
  push keeps the cursor of its pull, and the state is never replaced, only
  merged into, so a failed step loses nothing."
  ([state backends at]
   (sync! state backends at {}))
  ([state backends at {:keys [ctx compact] :as opts}]
   {:pre [(map? opts)]}
   (async/then (async/all (map (fn [{:keys [pull name]}] (async/attempt #(pull state (get ctx name) at))) backends))
               (fn [results]
                 (let [so-far (pulled state backends results at compact)
                       merged (:state so-far)]
                   (async/then (async/all (map (fn [[{:keys [push]} pull]] (async/attempt #(push merged (:ctx pull) at)))
                                               (:ok so-far)))
                               (fn [pushes] (finished so-far ctx pushes at compact))))))))
