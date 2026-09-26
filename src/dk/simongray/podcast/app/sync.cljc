(ns dk.simongray.podcast.app.sync
  "Keeping one listener's devices in step: the player state of each device
  carried to the others and merged, over any backend that can hold a
  document or speak a sync service's language. The design is doc/sync.md;
  this namespace is the engine's pure core and the driver over backends.

  A document is a device's whole state in an envelope: `document` writes
  one and `read-document` reads it back, and `reconcile` merges what other
  devices wrote into a state, with a report on what it found. A backend is
  a map of two functions and a name:

      {:pull (fn [state ctx at])  ; {:states [state …] :ctx ctx' :report […]}
       :push (fn [state ctx at])  ; {:ctx ctx' :state state' :report […]}
       :name \"Nextcloud\"}

  `pull` returns the states of other devices to merge and its cursor for
  next time; `push` writes the merged state and may return a state of its
  own when it learned more while writing, as a shared document does when it
  changed under it. Both take the instant `at` of the step and never read a
  clock. `dk.simongray.podcast.app.sync.store` builds backends over document
  stores; a sync service is a backend directly. `sync!` drives any number
  of them in one step and returns the merged state with a cursor per
  backend, for the app to keep beside its state; on the JVM it blocks, in
  ClojureScript it returns a promise, and there each function of a backend
  may return a value or a promise."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.diagnostics :as diagnostics]))

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
  :state}`; a string that is not one throws `::malformed`."
  [s]
  (let [envelope (try
                   (when (string? s) (edn/read-string s))
                   (catch #?(:clj Exception :cljs :default) e
                     (throw (ex-info "The document is not EDN" {:type ::malformed} e))))]
    (if (and (map? envelope) (integer? (:version envelope)) (map? (:state envelope)))
      envelope
      (throw (ex-info "The document is not a sync document" {:type ::malformed})))))

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

(defn reconcile
  "`state` with the documents `fetched` (see `read-documents`) merged in at
  `at`: `{:state merged :report [...]}`."
  [state fetched at]
  (let [{:keys [states report]} (read-documents fetched at)]
    {:state (reduce player/merge state states) :report report}))

(defn- failure
  "What a backend that threw `e` is recorded as."
  [e]
  {::failure (or (ex-message e) (str e))})

(defn- pulled
  "What the pulls `results`, one per backend in order, a result or a
  `failure`, add up to: the `:state` with every pulled state merged in, the
  `:ok` backends paired with their results, the `:failed` ones with their
  messages, and the `:report`."
  [state backends results]
  (let [pairs (map vector backends results)
        ok    (remove (comp ::failure second) pairs)]
    {:state  (reduce player/merge state (mapcat (comp :states second) ok))
     :ok     ok
     :failed (into {} (for [[backend result] pairs :when (::failure result)] [(:name backend) (::failure result)]))
     :report (into [] (mapcat (comp :report second)) ok)}))

(defn- finished
  "The step's result once the pushes `results`, one per `:ok` backend, are
  in: the state with what a push learned merged in, the cursors by backend
  name over the last step's `ctx`, the failures of pull and push, and the
  report of both."
  [{:keys [state ok failed report]} ctx results]
  (let [triples (map (fn [[backend pull] push] [backend pull push]) ok results)
        failed  (into failed (for [[backend _ push] triples :when (::failure push)] [(:name backend) (::failure push)]))]
    (cond-> {:state  (reduce player/merge state (keep (fn [[_ _ push]] (:state push)) triples))
             :ctx    (reduce (fn [m [backend pull push]]
                               (assoc m (:name backend) (if (::failure push) (:ctx pull) (:ctx push))))
                             (or ctx {})
                             triples)
             :report (into report (mapcat (fn [[_ _ push]] (when-not (::failure push) (:report push)))) triples)}
      (seq failed) (assoc :failed failed))))

#?(:clj
   (defn sync!
     "One step of keeping `state` in step through `backends`, at `at`: pull
     from every backend, merge what they return into `state`, push the merged
     state to each, and return

         {:state  merged
          :ctx    {\"Nextcloud\" {…} …}   ; the cursors, by backend name, to pass back next time
          :failed {\"Dropbox\" \"…\"}     ; the backends that threw, with the message; absent when none did
          :report [...]}                ; what reading and writing found

     `opts` carry the `:ctx` of the last step. The backends are asked at
     once, each on its own future; one that fails in its pull is not pushed
     to and keeps its cursor, and one that fails in its push keeps the
     cursor of its pull. The state is never replaced, only merged into, so a
     failed step loses nothing."
     ([state backends at]
      (sync! state backends at {}))
     ([state backends at {:keys [ctx] :as opts}]
      {:pre [(map? opts)]}
      (let [attempt (fn [f] (try (f) (catch Exception e (failure e))))
            results (mapv deref (mapv (fn [{:keys [pull name]}] (future (attempt #(pull state (get ctx name) at)))) backends))
            so-far  (pulled state backends results)
            merged  (:state so-far)
            pushes  (mapv deref (mapv (fn [[{:keys [push]} pull]] (future (attempt #(push merged (:ctx pull) at)))) (:ok so-far)))]
        (finished so-far ctx pushes)))))

#?(:cljs
   (defn sync!
     "One step of keeping `state` in step through `backends`, at `at`: pull
     from every backend, merge what they return into `state`, push the merged
     state to each, and return a js/Promise of

         {:state  merged
          :ctx    {\"Dropbox\" {…} …}     ; the cursors, by backend name, to pass back next time
          :failed {\"CloudKit\" \"…\"}    ; the backends that threw or rejected, with the message
          :report [...]}                ; what reading and writing found

     `opts` carry the `:ctx` of the last step. Each function of a backend
     may return a value or a promise. A backend that fails in its pull is
     not pushed to and keeps its cursor, and one that fails in its push
     keeps the cursor of its pull; the state is never replaced, only merged
     into, so a failed step loses nothing."
     ([state backends at]
      (sync! state backends at {}))
     ([state backends at {:keys [ctx] :as opts}]
      {:pre [(map? opts)]}
      (let [attempt (fn [f] (-> (js/Promise.resolve nil) (.then (fn [_] (f))) (.catch (fn [e] (failure e)))))
            all     (fn [promises] (.then (js/Promise.all (into-array promises)) vec))]
        (-> (all (map (fn [{:keys [pull name]}] (attempt #(pull state (get ctx name) at))) backends))
            (.then (fn [results]
                     (let [so-far (pulled state backends results)
                           merged (:state so-far)]
                       (-> (all (map (fn [[{:keys [push]} pull]] (attempt #(push merged (:ctx pull) at))) (:ok so-far)))
                           (.then (fn [pushes] (finished so-far ctx pushes))))))))))))
