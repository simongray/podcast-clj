(ns dk.simongray.podcast.app.sync.store
  "Document stores, and backends over them. A store is a map of functions
  over named documents with version tokens:

      {:fetch  (fn [name])            ; {:body \"…\" :version \"…\"}, nil when absent
       :put    (fn [name body opts])  ; {:version \"…\"} when written; {:conflict? true} when
                                      ; opts carry an :if-version other than the store's,
                                      ; nil standing for no document yet
       :list   (fn [])                ; [{:name \"…\" :version \"…\"} …]; optional
       :delete (fn [name])            ; optional
       :name   \"Nextcloud\"}           ; for the UI and the report, never the credentials

  On the JVM each function returns its value and throws on failure; in
  ClojureScript each may return a value or a promise. A constructor closes
  over its credentials, so a store never carries them, and wrapping a store
  is updating a key. `memory` is a store over an atom, for tests and for an
  app that keeps the documents itself.

  `backend` turns any store into a backend for
  `dk.simongray.podcast.app.sync/sync!`. By default every device writes its
  own document, `<device-id>.edn`, and reads the others', so that no two
  writers ever meet and a folder any sync client mirrors is a complete
  backend; a store without `:list` holds one shared document instead, put
  on condition of the version fetched and merged again on a conflict.
  `check!` tries any store through this contract and reports, for a
  settings screen's test button. Every store namespace follows one
  convention: `store` for the store over a place, and `backend` for the
  backend in one call with the options of both."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.opml :as opml]))

(defn memory
  "A store over the atom `documents` (a fresh one when none is given), name
  to `{:body :version}`, the version counting the writes of the name."
  ([]
   (memory (atom {})))
  ([documents]
   {:name   "memory"
    :fetch  (fn [name] (get @documents name))
    :put    (fn [name body opts]
              ;; the version checked and the document written in one swap,
              ;; so that of two puts on the same version only one succeeds
              (let [[before after] (swap-vals! documents
                                               (fn [docs]
                                                 (let [current (:version (get docs name))]
                                                   (if (and (contains? opts :if-version) (not= (:if-version opts) current))
                                                     docs
                                                     (assoc docs name {:body    body
                                                                       :version (str (inc (or (some-> current parse-long) 0)))})))))]
                (if (identical? before after)
                  {:conflict? true}
                  {:version (:version (get after name))})))
    :list   (fn [] (vec (sort-by :name (for [[name {:keys [version]}] @documents] {:name name :version version}))))
    :delete (fn [name] (swap! documents dissoc name) nil)}))

(defn- describe
  [diagnostic]
  (diagnostics/describe (assoc diagnostic :level :warning)))

(defn document-name
  "The name of the document the device `device` writes."
  [device]
  (str device ".edn"))

(def opml-name
  "The name of the subscription list a backend built with `:opml?` writes
  beside the documents, for any podcast app to import."
  "subscriptions.opml")

(defn- pull-documents
  "The per-device pull: the documents in the store, the `.edn` files of
  its listing, that changed since the cursor `ctx` was taken, fetched at
  once and read at `at`, with this device's own noted when it changed
  under it. A document the listing gives no version is always fetched,
  and this device's own, when it has gone from the store, is written
  again by the push."
  [{:keys [list fetch]} state {:keys [versions] :as ctx} at]
  (async/then (list)
              (fn [listing]
                (let [own       (document-name (:device state))
                      documents (filter #(str/ends-with? (:name %) ".edn") listing)
                      stale     (remove #(and (some? (:version %)) (= (:version %) (get versions (:name %)))) documents)
                      gone?     (and (contains? versions own) (not-any? #(= own (:name %)) documents))]
                  (async/then (async/all (map (fn [{:keys [name]}]
                                                (async/attempt #(async/then (fetch name) (fn [doc] (some-> doc (assoc :name name))))))
                                              stale))
                              (fn [results]
                                (when-let [error (some :error results)]
                                  (throw error))
                                (let [fetched (keep :value results)
                                      {:keys [states report]} (sync/read-documents fetched at)]
                                  {:states states
                                   :ctx    (cond-> (assoc ctx :versions (-> (into {} (map (juxt :name :version)) documents)
                                                                            (into (map (juxt :name :version)) fetched)))
                                             gone? (dissoc :hash))
                                   :report (cond-> report
                                             (and (contains? versions own) (some #(and (= own (:name %)) (some? (:version %))) stale))
                                             (conj (describe {:id :sync/shared-device :name own :device (:device state)})))})))))))

(defn- mirror-opml
  "Put the subscription list of `state` beside the documents of `store` when
  `opts` ask for it and the list changed since the cursor `ctx`. The list
  is what `player/to-opml` gives: the shows with their folders and tags,
  and the lists that the listener follows.

  Returns the cursor, which remembers the list last mirrored."
  [{:keys [put] :as store} {:keys [opml?]} state ctx]
  (let [list    (when opml? (player/to-opml state))
        written (hash list)]
    (if (and opml? (not= written (:opml ctx)))
      (async/then (put opml-name (opml/emit list) {})
                  (fn [_] (assoc ctx :opml written)))
      ctx)))

(defn- push-document
  "The per-device push: this device's document written when the state
  changed since it was last written, and the documents of retired devices
  deleted."
  [{:keys [put delete]} opts state {:keys [versions] :as ctx} at]
  (let [own     (document-name (:device state))
        written (hash state)
        retired (filter #(contains? versions (document-name %)) (keys (:retired state)))]
    (async/then (when (not= written (:hash ctx))
                  (put own (sync/document state at (select-keys opts [:app])) {}))
                (fn [result]
                  (async/then (async/all (when delete (map #(delete (document-name %)) retired)))
                              (fn [_]
                                {:ctx (cond-> (assoc ctx
                                                     :hash written
                                                     :versions (reduce dissoc versions (map document-name retired)))
                                        result (assoc-in [:versions own] (:version result)))}))))))

(defn- pull-shared
  "The shared-document pull: the one document, read at `at`, when its version
  is not the one the cursor `ctx` remembers. A document that has gone from
  the store is written again by the push, and one of a newer format than
  this library writes is marked in the cursor, for the push to leave alone."
  [{:keys [fetch]} name {:keys [version] :as ctx} at]
  (async/then (fetch name)
              (fn [doc]
                (cond
                  (nil? doc)
                  {:states [] :ctx (cond-> ctx version (dissoc :version :hash)) :report []}

                  (= version (:version doc))
                  {:states [] :ctx ctx :report []}

                  :else
                  (let [{:keys [states report]} (sync/read-documents [(assoc doc :name name)] at)
                        newer? (some #(= :sync/newer-format (:id %)) report)]
                    {:states states
                     :ctx    (cond-> (assoc ctx :version (:version doc))
                               newer?       (assoc :newer? true)
                               (not newer?) (dissoc :newer?))
                     :report report})))))

(defn- push-shared
  "The shared-document push: `state` put on condition of the version the
  cursor `ctx` holds and, on a conflict, fetched, merged and put again, up
  to three more times; the merged state comes back for the caller. A
  document of a newer format is left alone, since writing over it would
  lose what this library cannot read."
  [{:keys [fetch put]} name opts state ctx at]
  (letfn [(put-conditionally [state {:keys [version] :as ctx} attempts]
            (async/then (put name (sync/document state at (select-keys opts [:app])) {:if-version version})
                        (fn [{:keys [conflict?] :as result}]
                          (cond
                            (not conflict?)  {:ctx (assoc ctx :version (:version result) :hash (hash state))}
                            (zero? attempts) {:ctx ctx :report [(describe {:id :sync/conflict :name name})]}
                            :else            (merge-and-retry state ctx attempts)))))
          (merge-and-retry [state ctx attempts]
            (async/then (fetch name)
                        (fn [doc]
                          (let [{:keys [states report]} (sync/read-documents (when doc [(assoc doc :name name)]) at)
                                merged (reduce player/merge state states)]
                            (if (some #(= :sync/newer-format (:id %)) report)
                              {:ctx (assoc ctx :version (:version doc) :newer? true) :report report}
                              (async/then (put-conditionally merged (assoc ctx :version (:version doc)) (dec attempts))
                                          (fn [next]
                                            (-> next
                                                (assoc :state merged)
                                                (update :report #(into (vec report) %))))))))))]
    (if (or (:newer? ctx) (= (hash state) (:hash ctx)))
      {:ctx ctx}
      (put-conditionally state ctx 3))))

(def probe-name
  "The document `check!` writes and removes to see that it can; not an
  `.edn`, so that a probe left behind is never read as a document."
  "podcast-sync-probe.txt")

(defn- check
  [id ok? message]
  {:id id :ok? (boolean ok?) :message message})

(defn- refused?
  "Whether the `answer` of a probe step failed for want of credentials, by
  the `:status` a store's error carries, as the WebDAV store's do."
  [answer]
  (contains? #{401 403} (:status (ex-data (:error answer)))))

(defn- reachability
  "The checks that the first `answer` of the store gives: reachable,
  authenticated and, when both, how many documents it holds.

  The answer is a listing when the store `lists?`, and otherwise a fetch of
  the probe, after which the listing check says that it cannot list."
  [answer lists?]
  (cond
    (refused? answer)
    [(check :store/reachable true "The store answered.")
     (check :store/authenticated false (str "The credentials were refused: " (async/explain (:error answer))))]

    (:error answer)
    [(check :store/reachable false (str "The store could not be reached: " (async/explain (:error answer))))]

    :else
    [(check :store/reachable true "The store answered.")
     (check :store/authenticated true "The credentials were accepted.")
     (if lists?
       (let [n (count (:value answer))]
         (check :store/listing true (str "The store holds " n " document" (when (not= 1 n) "s") ".")))
       (check :store/listing false "The store cannot list its documents, so it holds one shared document."))]))

(defn- writing
  "The check the `put` of the probe answers."
  [put]
  (cond
    (refused? put)                 (check :store/put false (str "The credentials allow reading but not writing: " (async/explain (:error put))))
    (:error put)                   (check :store/put false (str "A document could not be written: " (async/explain (:error put))))
    (nil? (:version (:value put))) (check :store/put false "A document was written but the store gave it no version.")
    :else                          (check :store/put true "A document can be written and gets a version.")))

(defn- refusing-stale-versions
  "The check that the put of the probe under a `stale` version answers.

  It is advisory for a store that can list, and what one `shared?` document
  needs of a store that cannot."
  [stale shared?]
  (let [refuses? (:conflict? (:value stale))]
    (check :store/conflicts refuses?
           (cond
             (and refuses? shared?) "A stale version is refused, so one shared document is safe."
             refuses?               "A stale version is refused, so one shared document would be safe too."
             shared?                "The store does not refuse a stale version, so one shared document can lose writes."
             :else                  "The store does not refuse a stale version; keep one document per device, which is the default."))))

(defn- reading-back
  "The check the `fetched` probe answers: the body as written, with the
  version of the last write, the `stale` put's when it went through."
  [{:keys [put stale fetched]}]
  (let [{:keys [body version]} (:value fetched)
        written (or (:version (:value stale)) (:version (:value put)))]
    (check :store/fetch (and (= "probe" body) (= version written))
           (cond
             (:error fetched)       (str "The document could not be read back: " (async/explain (:error fetched)))
             (not= "probe" body)    "The document read back is not what was written."
             (not= version written) "The document read back has another version than the one written."
             :else                  "A document reads back as written, with its version."))))

(defn- deleting
  "The check the `delete` of the probe answers, the probe `gone` after it."
  [{:keys [delete gone]}]
  (check :store/delete (and (not (:error delete)) (nil? (:value gone)))
         (cond
           (:error delete)       (str "The probe document could not be deleted: " (async/explain (:error delete)) "; remove " probe-name " by hand.")
           (some? (:value gone)) (str "The probe document is still there after deleting it; remove " probe-name " by hand.")
           :else                 "A document can be deleted.")))

(defn- assess
  "The checks, from the `answers` of the probe by step, for a store that
  `lists?` and `deletes?` or not."
  [{:keys [listing put stale fetched delete] :as answers} lists? deletes?]
  (cond-> (reachability listing lists?)
    put                          (conj (writing put))
    stale                        (conj (refusing-stale-versions stale (not lists?)))
    fetched                      (conj (reading-back answers))
    delete                       (conj (deleting answers))
    (and fetched (not deletes?)) (conj (check :store/delete false (str "The store cannot delete documents; remove " probe-name " by hand.")))))

(defn- probe
  "The answers to the probe `steps`, `[key thunk]` in order, by key, each
  `{:value}` or `{:error}`; nothing is tried after a listing or a put that
  failed."
  [steps]
  (reduce (fn [answers [k f]]
            (async/then answers
                        (fn [answers]
                          (if (some (comp :error answers) [:listing :put])
                            answers
                            (async/then (async/attempt f) #(assoc answers k %))))))
          {}
          steps))

(defn check!
  "Try `store` through its contract and report, for a settings screen's
  test button:

      {:ok?    true                       ; it works as `backend` uses it
       :checks [{:id :store/reachable :ok? true :message \"The store answered.\"}
                {:id :store/authenticated …} {:id :store/listing …}
                {:id :store/put …}          ; a probe document written, with a version
                {:id :store/conflicts …}    ; a stale version refused, or not
                {:id :store/fetch …}        ; the probe read back as written
                {:id :store/delete …}]}     ; the probe removed

  On the JVM the report is returned, in ClojureScript promised. The probe
  is `probe-name`, written and removed; nothing is written to a store that
  cannot be reached. A store without `:list` is reached with a fetch and
  tried for the one shared document `backend` gives it, which needs a
  stale version refused; one without `:delete` keeps the probe. A store's
  own errors tell the credentials apart from the rest when they carry a
  `:status`, as the WebDAV store's do."
  [{:keys [fetch put list delete]}]
  (async/then (probe (cond-> [[:listing (or list #(fetch probe-name))]
                              [:put     #(put probe-name "probe" {})]
                              [:stale   #(put probe-name "probe" {:if-version "\"no-such-version\""})]
                              [:fetched #(fetch probe-name)]]
                       delete (conj [:delete #(delete probe-name)]
                                    [:gone   #(fetch probe-name)])))
              (fn [answers]
                (let [checks   (assess answers (some? list) (some? delete))
                      required (cond-> #{:store/reachable :store/authenticated :store/put :store/fetch}
                                 (not list) (conj :store/conflicts)
                                 delete     (conj :store/delete))]
                  {:ok?    (every? :ok? (filter (comp required :id) checks))
                   :checks checks}))))

(defn backend
  "A backend for `dk.simongray.podcast.app.sync/sync!` over `store`. `opts`:

      {:strategy  :per-device    ; every device its own document, merged on read;
                                 ; the default when the store has :list
                  :shared        ; one document for all, put on condition of its
                                 ; version; the default when it has not
       :direction :both          ; :pull to read only, :push to write only
       :opml?     false          ; write subscriptions.opml beside the documents
       :app       \"my-app\"      ; named in the documents written
       :name      \"…\"            ; the store's name unless given; the backends of
                                 ; one sync need distinct names
       :document  \"podcast.edn\"} ; the shared document's name"
  ([store]
   (backend store {}))
  ([store {:keys [strategy direction document] :or {direction :both document "podcast.edn"} :as opts}]
   {:pre [(map? opts)]}
   (let [strategy (or strategy (if (:list store) :per-device :shared))
         pull?    (not= :push direction)
         push?    (not= :pull direction)]
     (when (and (= :per-device strategy) (not (:list store)))
       (throw (ex-info "A store without :list cannot hold a document per device" {:name (:name store)})))
     {:name (or (:name opts) (:name store))
      :pull (fn [state ctx at]
              (if pull?
                (case strategy
                  :per-device (pull-documents store state ctx at)
                  :shared     (pull-shared store document ctx at))
                {:states [] :ctx ctx :report []}))
      :push (fn [state ctx at]
              (cond
                (not push?)
                {:ctx ctx}

                (player/retired? state (:device state))
                {:ctx ctx :report [(describe {:id :sync/retired-device :device (:device state)})]}

                :else
                (async/then (case strategy
                              :per-device (push-document store opts state ctx at)
                              :shared     (push-shared store document opts state ctx at))
                            (fn [{:keys [report] :as pushed}]
                              (if (some #(= :sync/conflict (:id %)) report)
                                pushed
                                (async/then (mirror-opml store opts (:state pushed state) (:ctx pushed))
                                            #(assoc pushed :ctx %)))))))})))
