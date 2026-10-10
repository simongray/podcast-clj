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
  rather than whole states. A push returns the next :cursor and a
  :report, and a :state when it learned more while writing. Both take the
  instant of the sync and never read a clock.

  The state is the profile, unless the options of the state say otherwise.
  Give the same ones to a backend, to sync! and to the functions of the
  documents:

  - :schema, the schema of a state of mind-meld, whose functions merge it
  - :crdt, the :merge, :compact and :problem functions of another CRDT, as
    default-crdt has them, which go over those of a :schema
  - :version, the version of the state's format, version by default
  - :kind, what state the documents hold, e.g. \"my-app/notes\", so that a
    document of another kind is left out
  - :codec, how a document is encoded, edn-codec by default"
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def version
  "The version of the profile's format that a document records. It changes
  only when the meaning of a key changes, since a reader merges the keys
  it doesn't know by their shape."
  1)

(def default-crdt
  "The functions of the CRDT that a sync merges when its options give no
  :schema or :crdt, those of the profile:

  - :merge, of two states
  - :compact, of a state at an instant with options, which drops the
    tombstones that every device has seen
  - :problem, of a state, which describes what breaks the rules that a
    merge relies on, or gives nil"
  {:merge   profile/merge
   :compact profile/compact
   :problem profile/problem})

(defn- schema-crdt
  "The functions of the CRDT of mind-meld for the states of `schema`."
  [schema]
  {:merge   #(crdt/merge schema %1 %2)
   :compact #(crdt/compact schema %1 %2 %3)
   :problem #(crdt/problem schema %)})

(defn- crdt-of
  "The functions of the CRDT of `opts`: those of their :crdt, over those of
  their :schema, over those of the profile."
  [{:keys [schema crdt] :as opts}]
  (merge default-crdt (some-> schema schema-crdt) crdt))

(def edn-codec
  "The codec of documents in EDN, the default. A codec is a map of the
  :extension of the names of the documents, and the functions that
  :encode an envelope as the body of a document and :decode it.

  An app that keeps values of its own in its state, e.g. with tagged
  literals, gives a codec of its own."
  {:extension ".edn"
   :encode    (fn [envelope]
                (binding [*print-length* nil
                          *print-level*  nil]
                  (pr-str envelope)))
   :decode    (fn [s]
                (when (string? s)
                  (edn/read-string s)))})

(defn document
  "The document of `state` written at `at` with the options of the state
  `opts`, encoded by their codec.

  It's an envelope with the version of the state's format, the device,
  the state, and the :app and :kind of `opts`, if any."
  ([state at]
   (document state at {}))
  ([state at {:keys [app kind codec] :or {codec edn-codec} :as opts}]
   {:pre [(map? opts)]}
   (let [envelope (cond-> {:version    (:version opts version)
                           :device     (:device state)
                           :written-at (time/date at)
                           :state      state}
                    app  (assoc :app app)
                    kind (assoc :kind kind))]
     ((:encode codec) envelope))))

(defn- same-kind?
  "Whether the `envelope` holds the kind of state of the options `opts`,
  which it does unless both name a :kind and the kinds differ."
  [envelope opts]
  (or (nil? (:kind envelope))
      (nil? (:kind opts))
      (= (:kind envelope) (:kind opts))))

(defn read-document
  "The envelope in the document `s`, a map of :version, :device,
  :written-at, :app, :kind and :state, decoded with the options of the
  state `opts`.

  It throws ::malformed when `s` isn't an envelope. It throws so too for an
  envelope of the kind of state of `opts`, in their version of the format
  or an older one, when the :problem of their CRDT finds a problem in its
  state."
  ([s]
   (read-document s {}))
  ([s {:keys [codec] :or {codec edn-codec} :as opts}]
   (let [envelope (try
                    ((:decode codec) s)
                    ;; Throwable on the JVM for the StackOverflowError that
                    ;; deeply nested EDN brings about
                    (catch #?(:clj Throwable :cljs :default) e
                      (throw (ex-info "The document does not decode"
                                      {:type ::malformed}
                                      e))))]
     (when-not (and (map? envelope)
                    (integer? (:version envelope))
                    (map? (:state envelope)))
       (throw (ex-info "The document is not a sync document"
                       {:type ::malformed})))
     (when-let [problem (and (same-kind? envelope opts)
                             (<= (:version envelope) (:version opts version))
                             ((:problem (crdt-of opts)) (:state envelope)))]
       (throw (ex-info (str "The document holds a state that can't be "
                            "merged: " problem)
                       {:type ::malformed})))
     envelope)))

(def skew-tolerance
  "How far ahead of now a document can be written before the report says
  that its device's clock is ahead: a minute, in milliseconds."
  60000)

(defn- read-one
  "The `acc` of a reading of documents with the document `doc` read at
  `at` with `opts`: its state added, or a diagnostic of what kept it out."
  [acc {:keys [name body] :as doc} at opts]
  (let [envelope (try
                   (read-document body opts)
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

      (not (same-kind? envelope opts))
      (report acc {:id    :sync/other-kind
                   :level :warning
                   :name  name
                   :kind  (:kind envelope)})

      (> (:version envelope) (:version opts version))
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
  "The states in the documents `fetched`, read at `at` with the options of
  the state `opts`, as a map of :states and a :report.

  Each document is a map of :name and :body. The report has a diagnostic
  for each of these:

  - :sync/unreadable-document, a body that doesn't read
  - :sync/other-kind, a document of another kind of state, which is left
    out
  - :sync/newer-format, a document in a newer version of the state's
    format, which is left out
  - :sync/clock-skew, a device whose clock is ahead of this one"
  ([fetched at]
   (read-documents fetched at {}))
  ([fetched at opts]
   (reduce #(read-one %1 %2 at opts) {:states [] :report []} fetched)))

(defn- with-states
  "The `state` with the `states` merged in by the :merge of the CRDT of
  `opts`."
  [state states opts]
  (reduce (:merge (crdt-of opts)) state states))

(defn- settle
  "The `state` with the whole `states` acknowledged at `at`, and compacted
  by the CRDT of `opts` with their :compact as options, unless that's
  false."
  [state states at {:keys [compact] :as opts}]
  (let [acknowledged (crdt/acknowledge state states at)]
    (if (false? compact)
      acknowledged
      ((:compact (crdt-of opts)) acknowledged at (or compact {})))))

(defn reconcile
  "The `state` with the documents `fetched` merged in at `at` with `opts`,
  as a map of the :state and a :report.

  The documents are maps of :name and :body. What they tell is
  acknowledged in the device registry, and the tombstones that every
  device has seen are compacted away. The `opts` are the options of the
  state, and :compact, false to keep the tombstones, or else the options
  of the compact function of the CRDT."
  ([state fetched at]
   (reconcile state fetched at {}))
  ([state fetched at opts]
   {:pre [(map? opts)]}
   (let [{:keys [states report]} (read-documents fetched at opts)]
     {:state  (settle (with-states state states opts) states at opts)
      :report report})))

(defn- describe
  [diagnostic]
  (diagnostics/describe (assoc diagnostic :level :warning)))

(defn document-name
  "The name of the document that the device `device` writes, with the
  extension of the codec of the options of the state `opts`."
  ([device]
   (document-name device {}))
  ([device {:keys [codec] :or {codec edn-codec} :as opts}]
   (str device (:extension codec))))

(defn device-document
  "The document that the device of `state` writes at `at`, with the options
  of the state `opts` and their :app, as a map of its :name and :body."
  ([state at]
   (device-document state at {}))
  ([state at opts]
   (let [opts (select-keys opts [:app :kind :version :codec])]
     {:name (document-name (:device state) opts)
      :body (document state at opts)})))

(def opml-name
  "The name of the subscription list that a backend with :opml? writes
  beside the documents, for any podcast app to import."
  "subscriptions.opml")

(defn- device-document?
  "Whether the listed document `doc` is one that a device writes, by the
  extension of the codec of the options of the state `opts`."
  [doc {:keys [codec] :or {codec edn-codec} :as opts}]
  (str/ends-with? (:name doc) (:extension codec)))

(defn- pull-documents
  "The per-device pull from `store` for `state` at `at`: the documents that
  changed since `cursor`, fetched at once and read with the options of the
  state `opts`.

  The documents are those of the listing with the extension of the codec,
  and one without a version there is always fetched. The report notes
  when this device's own document changed under it, and when the
  document is gone from the store, the push writes it again."
  [{:keys [list fetch] :as store} opts state {:keys [versions] :as cursor} at]
  (async/then
   (list)
   (fn [listing]
     (let [own       (document-name (:device state) opts)
           own?      #(= own (:name %))
           seen?     #(and (some? (:version %))
                           (= (:version %) (get versions (:name %))))
           documents (filter #(device-document? % opts) listing)
           stale     (remove seen? documents)
           known?    (contains? versions own)
           gone?     (and known?
                          (not-any? own? documents))
           named     (fn [name doc]
                       (some-> doc (assoc :name name)))
           fetch!    (fn [{:keys [name]}]
                       (async/attempt
                        #(async/then (fetch name {}) (partial named name))))]
       (async/then
        (async/all (map fetch! stale))
        (fn [results]
          (when-let [error (some :error results)]
            (throw error))
          (let [fetched  (keep :value results)
                {:keys [states report]} (read-documents fetched at opts)
                version  (juxt :name :version)
                listed   (-> (into {} (map version) documents)
                             (into (map version) fetched))
                changed? (and known?
                              (some #(and (own? %) (some? (:version %)))
                                    stale))
                shared   {:id     :sync/shared-device
                          :name   own
                          :device (:device state)}]
            {:states states
             :cursor (cond-> (assoc cursor :versions listed)
                       gone? (dissoc :hash))
             :report (cond-> report
                       changed? (conj (describe shared)))})))))))

(defn- extra-documents
  "The documents that the options `opts` of a backend ask it to write
  beside those of the devices, for `state`, as a map of name to body.

  They're those of the :extra-documents-fn of `opts`, and with :opml?, the
  subscription list of the profile, which has the shows with their
  folders and tags, and the lists that the listener follows."
  [state {:keys [extra-documents-fn opml? codec] :or {codec edn-codec}
          :as   opts}]
  (let [documents (merge (when opml?
                           {opml-name (opml/emit (profile/to-opml state))})
                         (when extra-documents-fn
                           (extra-documents-fn state)))]
    (when-let [name (some #(when (str/ends-with? % (:extension codec)) %)
                          (keys documents))]
      (throw (ex-info (str "An extra document can't have the extension of "
                           "the documents of the devices: " name)
                      {:name name})))
    documents))

(defn- put-extras
  "Put the extra documents that `opts` ask for beside the documents of
  `store`, for `state`, each when it changed since `cursor`. Returns the
  cursor, which remembers a hash of each document last put."
  [{:keys [put] :as store} opts state cursor]
  (let [changed (for [[name body] (extra-documents state opts)
                      :when (not= (hash body) (get-in cursor [:extras name]))]
                  [name body])
        put!    (fn [cursor [name body]]
                  (async/then cursor
                              (fn [c]
                                (async/then (put name body {})
                                            (fn [_]
                                              (assoc-in c [:extras name]
                                                        (hash body)))))))]
    (reduce put! cursor changed)))

(defn- push-document
  "The per-device push of `state` to `store` at `at`, with the options of
  the state `opts` and their :app, and `cursor`.

  It writes this device's document when the state changed since the
  cursor, and deletes the documents of retired devices."
  [{:keys [put delete] :as store} opts state {:keys [versions] :as cursor} at]
  (let [own      (device-document state at opts)
        written  (hash state)
        named    #(document-name % opts)
        listed?  #(contains? versions (named %))
        retired  (filter listed? (keys (:retired state)))
        delete!  #(delete (named %))]
    (async/then
     (when (not= written (:hash cursor))
       (put (:name own) (:body own) {}))
     (fn [result]
       (async/then
        (when delete
          (async/all delete! retired))
        (fn [_]
          (let [left (reduce dissoc versions (map named retired))]
            {:cursor (cond-> (assoc cursor :hash written :versions left)
                       result
                       (assoc-in [:versions (:name own)]
                                 (:version result)))})))))))

(defn- hands-off
  "The marks for the cursor of a shared document whose reading gave
  `report`, when this device must leave the document alone: :newer? for
  one of a newer format, and :other-kind? for one of another kind of
  state. Writing over either would lose what this device can't read."
  [report]
  (let [ids (set (map :id report))]
    (cond-> {}
      (ids :sync/newer-format) (assoc :newer? true)
      (ids :sync/other-kind)   (assoc :other-kind? true))))

(defn- pull-shared
  "The shared pull from `store`: the document `name`, read at `at` with the
  options of the state `opts`, when its version isn't the one in `cursor`.

  When the document is gone from the store, the push writes it again. A
  document that this device must leave alone is marked in the cursor, so
  that the push doesn't write it."
  [{:keys [fetch] :as store} name opts {:keys [version] :as cursor} at]
  (async/then (fetch name {})
              (fn [doc]
                (cond
                  (nil? doc)
                  {:states []
                   :cursor (cond-> cursor version (dissoc :version :hash))
                   :report []}

                  (= version (:version doc))
                  {:states [] :cursor cursor :report []}

                  :else
                  (let [named [(assoc doc :name name)]
                        {:keys [states report]} (read-documents named at opts)]
                    {:states states
                     :cursor (-> cursor
                                 (assoc :version (:version doc))
                                 (dissoc :newer? :other-kind?)
                                 (merge (hands-off report)))
                     :report report})))))

(defn- push-shared
  "The shared push of `state` to the document `name` in `store` at `at`,
  with the options of the state `opts` and their :app, on condition of the
  version in `cursor`.

  On a conflict, the document is fetched, merged and put again, up to
  three more times. The merged state comes back for the caller. A
  document of a newer format or of another kind is left alone."
  [{:keys [fetch put] :as store} name opts state cursor at]
  (letfn [(put-conditionally [state {:keys [version] :as cursor} attempts]
            (let [written (document state at opts)]
              (async/then
               (put name written {:if-version version})
               (fn [{:keys [conflict?] :as result}]
                 (cond
                   (not conflict?)
                   {:cursor (assoc cursor
                                   :version (:version result)
                                   :hash    (hash state))}

                   (zero? attempts)
                   {:cursor cursor
                    :report [(describe {:id :sync/conflict :name name})]}

                   :else
                   (merge-and-retry state cursor attempts))))))
          (merge-and-retry [state cursor attempts]
            (async/then
             (fetch name {})
             (fn [doc]
               (let [named  (when doc [(assoc doc :name name)])
                     {:keys [states report]} (read-documents named at opts)
                     merged (with-states state states opts)
                     marks  (hands-off report)
                     cursor (assoc cursor :version (:version doc))]
                 (if (seq marks)
                   {:cursor (merge cursor marks) :report report}
                   (async/then
                    (put-conditionally merged cursor (dec attempts))
                    (fn [retried]
                      (-> retried
                          (assoc :state merged)
                          (update :report #(into (vec report) %))))))))))]
    (if (or (:newer? cursor)
            (:other-kind? cursor)
            (= (hash state) (:hash cursor)))
      {:cursor cursor}
      (put-conditionally state cursor 3))))

(defn backend
  "A backend over `store`, a document store of dk.simongray.podcast.store,
  with the options of the state and the other `opts` below:

  - :strategy, :per-device for a document per device, or :shared for one
    document for all
  - :direction, :pull to read only, :push to write only, or :both
  - :app, the name of the app in the documents written
  - :name, the store's name by default, and distinct for each backend of
    one sync
  - :document, the name of the shared document, podcast and the extension
    of the codec by default
  - :extra-documents-fn, a function of the state that gives documents to
    write beside those of the devices, as a map of name to body, e.g. for
    other apps to read
  - :opml?, whether to write subscriptions.opml beside the documents, for
    the profile

  The :strategy is :per-device when the store has :list, and else
  :shared. With a document per device, every device writes its own,
  named after its device id, and reads those of the others. So no two
  devices write the same document, and a folder that any sync client
  mirrors is a complete backend. One shared document is put on condition
  of the version that was fetched, and merged again on a conflict. The
  :direction is :both when the store has :put, and else :pull.

  An extra document is written when it changed, and can't have the
  extension of the codec, since it would be read as a device's."
  ([store]
   (backend store {}))
  ([store {:keys [strategy direction codec] :or {codec edn-codec} :as opts}]
   {:pre [(map? opts)]}
   (let [document  (:document opts (str "podcast" (:extension codec)))
         strategy  (or strategy (if (:list store) :per-device :shared))
         direction (or direction (if (:put store) :both :pull))
         pull?     (not= :push direction)
         push?     (not= :pull direction)]
     (when (and (= :per-device strategy) (not (:list store)))
       (throw (ex-info "A store without :list cannot hold a document per device"
                       {:name (:name store)})))
     (when (and push? (not (:put store)))
       (throw (ex-info "A store without :put can only be pulled from"
                       {:name (:name store)})))
     {:name (or (:name opts) (:name store))
      :pull (fn [state cursor at]
              (if pull?
                (case strategy
                  :per-device (pull-documents store opts state cursor at)
                  :shared     (pull-shared store document opts cursor at))
                {:states [] :cursor cursor :report []}))
      :push (fn [state cursor at]
              (cond
                (not push?)
                {:cursor cursor}

                (crdt/retired? state (:device state))
                {:cursor cursor
                 :report [(describe {:id     :sync/retired-device
                                     :device (:device state)})]}

                :else
                (async/then
                 (case strategy
                   :per-device
                   (push-document store opts state cursor at)

                   :shared
                   (push-shared store document opts state cursor at))
                 (fn [{:keys [report] :as pushed}]
                   (if (some #(= :sync/conflict (:id %)) report)
                     pushed
                     (let [latest (:state pushed state)]
                       (async/then
                        (put-extras store opts latest (:cursor pushed))
                        #(assoc pushed :cursor %))))))))})))

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
  never before `at`, and without a step, it's `at`. The `cadence` goes
  over the defaults. The library schedules nothing, so run the sync from
  a timer of your own."
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
         due      (when synced-at
                    (time/later synced-at wait))]
     (time/latest due at))))

(defn- failure
  "The diagnostic of the `backend` whose pull or push failed with `error`."
  [backend error]
  (diagnostics/describe {:id      :sync/backend-failed
                         :backend (:name backend)
                         :reason  (async/explain error)}))

(defn- pulled
  "What the pull `results` of the `backends` add up to, merged into `state`
  at `at` and compacted, by the CRDT of `opts`.

  The results are one per backend, in order, each with a :value or an
  :error. The sum is a map of these:

  - :state, with every pulled state merged in, the whole states
    acknowledged, and the tombstones compacted
  - :ok, the backends that didn't fail, each paired with its result
  - :report, the reports of the pulls, and a diagnostic for each backend
    that failed

  A backend whose states are partial, the changes of a service since a
  cursor, says so with :partial?, and none of them are acknowledged."
  [state backends results at opts]
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
    {:state  (settle (with-states state states opts) whole at opts)
     :ok     (vec ok)
     :report (-> []
                 (into (mapcat (comp :report second)) ok)
                 (into failed))}))

(defn- finished
  "The result of a step from the pulls `so-far`, the push `results` and the
  last `cursors`, settled at `at` by the CRDT of `opts`.

  There is one push result per backend that didn't fail. The state has
  what a push learned merged in, acknowledged and compacted, and the new
  cursors are by backend name. The report is that of both the pulls and
  the pushes, with a diagnostic for each backend whose push failed."
  [{:keys [state ok report] :as so-far} cursors results at opts]
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
                (settle (with-states state learned opts) learned at opts)
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
  promise. The `opts` are the options of the state, whose CRDT merges,
  and these:

  - :at, the instant of the step, the time of the clock by default
  - :cursors, those of the last step
  - :compact, false to keep the tombstones that every device has seen, or
    else the options of the compact function of the CRDT

  A backend whose pull fails isn't pushed to and keeps its cursor, and one
  whose push fails keeps the cursor of its pull. The report has a
  :sync/backend-failed diagnostic for each, with its :backend and the
  :reason. The state is only ever merged into, so a failed step loses
  nothing. A state in which the :problem of the CRDT finds a problem isn't
  pushed, since the other devices would leave it out, and the report has
  :sync/malformed-state.

  Run one step at a time, since a step that overlaps another can write an
  older state after a newer one. What the listener did during a step
  isn't in its :state, so don't put the result in place of the state.
  Merge it into the state as it is by then, and compact as the step did,
  since the merge brings back the tombstones that the step dropped.

  The backends need distinct names, since the cursors are kept by name,
  and the same name twice throws."
  ([state backends]
   (sync! state backends {}))
  ([state backends {:keys [at cursors] :as opts}]
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
        (let [so-far    (pulled state backends results at opts)
              merged    (:state so-far)
              problem   ((:problem (crdt-of opts)) merged)
              push!     (fn [[{:keys [push]} pull]]
                          (if problem
                            {:cursor (:cursor pull)}
                            (push merged (:cursor pull) at)))
              attempt!  (fn [ok]
                          (async/attempt #(push! ok)))
              malformed {:id :sync/malformed-state :error problem}
              finish    (fn [pushes]
                          (cond-> (finished so-far cursors pushes at opts)
                            problem
                            (update :report conj
                                    (diagnostics/describe malformed))))]
          (async/then (async/all (map attempt! (:ok so-far)))
                      finish)))))))
