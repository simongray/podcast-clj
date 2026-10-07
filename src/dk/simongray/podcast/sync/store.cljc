(ns dk.simongray.podcast.sync.store
  "Document stores, and backends over them.

  A store is a map of functions over named documents with versions:

      {:fetch  (fn [name opts])       ; {:body \"…\" :version \"…\"}, or nil
       :put    (fn [name body opts])  ; {:version \"…\"}, or {:conflict? true}
       :list   (fn [])                ; [{:name \"…\" :version \"…\"}], optional
       :delete (fn [name])            ; optional
       :name   \"Nextcloud\"}           ; for the UI and the report

  A body is text or bytes, a byte array on the JVM and a Uint8Array in
  JavaScript. A fetch gives it as text, or as bytes with :as :bytes in its
  opts, as util.http/send! does, whichever was put, and body-as turns one
  into the other. A put is a conflict when its opts have an :if-version
  other than the store's, where nil means that the document must not
  exist yet. A store that can't be written, e.g. over the files of an
  input, has no :put.

  On the JVM, each function returns its value and throws when it fails. In
  ClojureScript, each can return a value or a promise. A store closes over
  its credentials, so they're never in the map, and you wrap a store by
  updating a key. The function memory gives a store over an atom, for
  tests or for an app that keeps the documents itself.

  The function backend turns any store into a backend for
  dk.simongray.podcast.sync/sync!. By default, every device writes its
  own document, named after its device id, and reads those of the others.
  So no two devices write the same document, and a folder that any sync
  client mirrors is a complete backend. A store without :list holds one
  shared document instead, which is put on condition of the version that
  was fetched, and merged again on a conflict. A backend over a store
  without :put only pulls.

  Test a store with check!, e.g. from a button in the settings."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.crdt :as crdt]))

;; TODO: Decide where the stores live when util.crdt becomes a library of
;; its own. A library for local-first sync could hold util.crdt, the store
;; contract, the memory, folder, IndexedDB and encrypted stores, and
;; backend, once backend no longer calls profile and OPML. Those stores
;; need little of util.async, util.bytes and util.secret, but WebDAV needs
;; util.http and util.xml, so it would stay here. See the TODO in
;; util.crdt.

(defn body-as
  "The `body` of a document, text or bytes, as the fetch with `opts` asks
  for it: bytes with :as :bytes, and else text, of UTF-8."
  ([body]
   (body-as body {}))
  ([body opts]
   (cond
     (= :bytes (:as opts)) (if (string? body) (bytes/encode body) body)
     (string? body)        body
     :else                 (bytes/decode body 0 (bytes/length body) :utf8))))

(defn memory
  "A store over the atom `documents`, or a fresh one, which holds the :body
  and :version of each document by name, the version counting its writes."
  ([]
   (memory (atom {})))
  ([documents]
   (let [written (fn [docs name body opts]
                   (let [current (:version (get docs name))
                         stale?  (and (contains? opts :if-version)
                                      (not= (:if-version opts) current))]
                     (if stale?
                       docs
                       (let [n (or (some-> current parse-long) 0)]
                         (assoc docs name {:body    body
                                           :version (str (inc n))})))))]
     {:name   "memory"
      :fetch  (fn [name opts]
                (some-> (get @documents name) (update :body body-as opts)))
      :put    (fn [name body opts]
                ;; the version checked and the document written in one swap,
                ;; so that of two puts on the same version only one succeeds
                (let [[before after] (swap-vals! documents
                                                 written name body opts)]
                  (if (identical? before after)
                    {:conflict? true}
                    {:version (:version (get after name))})))
      :list   (fn []
                (vec (sort-by :name (for [[name {:keys [version]}] @documents]
                                      {:name name :version version}))))
      :delete (fn [name]
                (swap! documents dissoc name)
                nil)})))

(defn- describe
  [diagnostic]
  (diagnostics/describe (assoc diagnostic :level :warning)))

(defn document-name
  "The name of the document that the device `device` writes."
  [device]
  (str device ".edn"))

(def opml-name
  "The name of the subscription list that a backend with :opml? writes
  beside the documents, for any podcast app to import."
  "subscriptions.opml")

(defn- pull-documents
  "The per-device pull from `store` for `state` at `at`: the documents that
  changed since `cursor`, fetched at once and read.

  The documents are the .edn files of the listing, and one without a
  version there is always fetched. The report notes when this device's own
  document changed under it, and when the document is gone from the store,
  the push writes it again."
  [{:keys [list fetch] :as store} state {:keys [versions] :as cursor} at]
  (async/then
   (list)
   (fn [listing]
     (let [own       (document-name (:device state))
           own?      #(= own (:name %))
           edn?      #(str/ends-with? (:name %) ".edn")
           seen?     #(and (some? (:version %))
                           (= (:version %) (get versions (:name %))))
           documents (filter edn? listing)
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
                {:keys [states report]} (sync/read-documents fetched at)
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

(defn- mirror-opml
  "Put the subscription list of `state` beside the documents of `store`,
  when `opts` ask for it and the list changed since `cursor`.

  The list is what profile/to-opml gives: the shows with their folders and
  tags, and the lists that the listener follows. Returns the cursor, which
  remembers the list last mirrored."
  [{:keys [put] :as store} {:keys [opml?] :as opts} state cursor]
  (let [list    (when opml? (profile/to-opml state))
        written (hash list)]
    (if (and opml? (not= written (:opml cursor)))
      (async/then (put opml-name (opml/emit list) {})
                  (fn [_] (assoc cursor :opml written)))
      cursor)))

(defn- push-document
  "The per-device push of `state` to `store` at `at`, with the :app of
  `opts` and `cursor`.

  It writes this device's document when the state changed since the
  cursor, and deletes the documents of retired devices."
  [{:keys [put delete] :as store} opts state {:keys [versions] :as cursor} at]
  (let [own      (document-name (:device state))
        written  (hash state)
        listed?  #(contains? versions (document-name %))
        retired  (filter listed? (keys (:retired state)))
        delete!  #(delete (document-name %))
        app      (select-keys opts [:app])]
    (async/then
     (when (not= written (:hash cursor))
       (put own (sync/document state at app) {}))
     (fn [result]
       (async/then
        (async/all (when delete
                     (map delete! retired)))
        (fn [_]
          (let [left (reduce dissoc versions (map document-name retired))]
            {:cursor (cond-> (assoc cursor :hash written :versions left)
                       result
                       (assoc-in [:versions own] (:version result)))})))))))

(defn- pull-shared
  "The shared pull from `store`: the document `name`, read at `at`, when
  its version isn't the one in `cursor`.

  When the document is gone from the store, the push writes it again. A
  document of a newer format is marked in the cursor, so that the push
  leaves it alone."
  [{:keys [fetch] :as store} name {:keys [version] :as cursor} at]
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
                  (let [named  [(assoc doc :name name)]
                        {:keys [states report]} (sync/read-documents named at)
                        newer? (some #(= :sync/newer-format (:id %)) report)]
                    {:states states
                     :cursor (cond-> (assoc cursor :version (:version doc))
                               newer?       (assoc :newer? true)
                               (not newer?) (dissoc :newer?))
                     :report report})))))

(defn- push-shared
  "The shared push of `state` to the document `name` in `store` at `at`,
  with the :app of `opts`, on condition of the version in `cursor`.

  On a conflict, the document is fetched, merged and put again, up to
  three more times. The merged state comes back for the caller. A
  document of a newer format is left alone, since writing over it would
  lose what this library can't read."
  [{:keys [fetch put] :as store} name opts state cursor at]
  (letfn [(put-conditionally [state {:keys [version] :as cursor} attempts]
            (let [document (sync/document state at (select-keys opts [:app]))]
              (async/then
               (put name document {:if-version version})
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
                     {:keys [states report]} (sync/read-documents named at)
                     merged (reduce profile/merge state states)
                     newer? (some #(= :sync/newer-format (:id %)) report)
                     cursor (assoc cursor :version (:version doc))]
                 (if newer?
                   {:cursor (assoc cursor :newer? true) :report report}
                   (async/then
                    (put-conditionally merged cursor (dec attempts))
                    (fn [retried]
                      (-> retried
                          (assoc :state merged)
                          (update :report #(into (vec report) %))))))))))]
    (if (or (:newer? cursor) (= (hash state) (:hash cursor)))
      {:cursor cursor}
      (put-conditionally state cursor 3))))

(def probe-name
  "The document that check! writes and removes, which isn't an .edn file,
  so that a probe left behind is never read as a document."
  "podcast-sync-probe.txt")

(defn- check
  [id ok? message]
  {:id id :ok? (boolean ok?) :message message})

(defn- refused?
  "Whether the `answer` of a probe step failed for want of credentials, by
  the :status of the store's error, as the WebDAV store gives it."
  [answer]
  (contains? #{401 403} (:status (ex-data (:error answer)))))

(defn- reachability
  "The checks that the first `answer` of a store gives, a listing if it
  `lists?`: whether it's reachable and authenticated, and if so, how many
  documents it holds.

  Without a listing, the answer is a fetch of the probe, and the listing
  check says that the store can't list."
  [answer lists?]
  (cond
    (refused? answer)
    [(check :store/reachable true "The store answered.")
     (check :store/authenticated false
            (str "The credentials were refused: "
                 (async/explain (:error answer))))]

    (:error answer)
    [(check :store/reachable false
            (str "The store could not be reached: "
                 (async/explain (:error answer))))]

    :else
    [(check :store/reachable true "The store answered.")
     (check :store/authenticated true "The credentials were accepted.")
     (if lists?
       (let [n      (count (:value answer))
             plural (when (not= 1 n) "s")]
         (check :store/listing true
                (str "The store holds " n " document" plural ".")))
       (check :store/listing false
              (str "The store cannot list its documents, so it holds one "
                   "shared document.")))]))

(defn- writing
  "The check that the answer `put` to the put of the probe gives."
  [put]
  (cond
    (refused? put)
    (check :store/put false
           (str "The credentials allow reading but not writing: "
                (async/explain (:error put))))

    (:error put)
    (check :store/put false
           (str "A document could not be written: "
                (async/explain (:error put))))

    (nil? (:version (:value put)))
    (check :store/put false
           "A document was written but the store gave it no version.")

    :else
    (check :store/put true "A document can be written and gets a version.")))

(defn- refusing-stale-versions
  "The check that the answers `stale` and `taken` give, to puts of the
  probe with a stale version and with none, for a `shared?` document or
  not.

  A put with no version expects no document, and the probe is there. The
  check is advisory for a store that can list, and a store that can't
  needs it for its one shared document."
  [stale taken shared?]
  (let [refuses? (every? (comp :conflict? :value) [stale taken])
        missed   (if (:conflict? (:value stale))
                   "a put that expects no document"
                   "a stale version")]
    (check :store/conflicts refuses?
           (cond
             (and refuses? shared?)
             "Stale writes are refused, so one shared document is safe."

             refuses?
             (str "Stale writes are refused, so one shared document would "
                  "be safe too.")

             shared?
             (str "The store does not refuse " missed
                  ", so one shared document can lose writes.")

             :else
             (str "The store does not refuse " missed
                  "; keep one document per device, which is the default.")))))

(defn- reading-back
  "The check that the fetch of the probe in `answers` gives. It expects the
  body as written, with the version of the last put that went through."
  [{:keys [put stale taken fetched] :as answers}]
  (let [{:keys [body version]} (:value fetched)
        written (some (comp :version :value) [taken stale put])]
    (check :store/fetch (and (= "probe" body) (= version written))
           (cond
             (:error fetched)
             (str "The document could not be read back: "
                  (async/explain (:error fetched)))

             (not= "probe" body)
             "The document read back is not what was written."

             (not= version written)
             "The document read back has another version than the one written."

             :else
             "A document reads back as written, with its version."))))

(defn- deleting
  "The check that the delete of the probe in `answers` gives, with the
  probe gone after it."
  [{:keys [delete gone] :as answers}]
  (check :store/delete (and (not (:error delete)) (nil? (:value gone)))
         (cond
           (:error delete)
           (str "The probe document could not be deleted: "
                (async/explain (:error delete))
                "; remove " probe-name " by hand.")

           (some? (:value gone))
           (str "The probe document is still there after deleting it; remove "
                probe-name " by hand.")

           :else
           "A document can be deleted.")))

(defn- assess
  "The checks from the `answers` of the probe, by step, for a store that
  `lists?`, `writes?` and `deletes?`, or not."
  [{:keys [listing put stale taken fetched delete] :as answers}
   lists? writes? deletes?]
  (let [kept (str "The store cannot delete documents; remove " probe-name
                  " by hand.")]
    (cond-> (reachability listing lists?)
      (not writes?)
      (conj (check :store/put false
                   "The store is read only, so a backend only pulls from it."))

      put
      (conj (writing put))

      stale
      (conj (refusing-stale-versions stale taken (not lists?)))

      fetched
      (conj (reading-back answers))

      delete
      (conj (deleting answers))

      (and fetched (not deletes?))
      (conj (check :store/delete false kept)))))

(defn- probe
  "The answers to the probe `steps`, pairs of a key and a thunk, by key,
  each with a :value or an :error. The steps run in order, and none runs
  after a listing or a put that failed."
  [steps]
  (let [failed? (fn [answers]
                  (some (comp :error answers) [:listing :put]))
        step    (fn [answers [k f]]
                  (async/then answers
                              (fn [answers]
                                (if (failed? answers)
                                  answers
                                  (async/then (async/attempt f)
                                              #(assoc answers k %))))))]
    (reduce step {} steps)))

(defn check!
  "Try `store` through its contract, e.g. from a button in the settings,
  and report what works:

      {:ok?    true                   ; it works as backend uses it
       :checks [{:id :store/reachable :ok? true :message \"…\"}
                {:id :store/authenticated …}
                {:id :store/listing …}
                {:id :store/put …}        ; a probe written, with a version
                {:id :store/conflicts …}  ; stale writes refused, or not
                {:id :store/fetch …}      ; the probe read back as written
                {:id :store/delete …}]}   ; the probe removed

  The report is a promise in ClojureScript. The probe document is
  probe-name, written and removed, and nothing is written to a store that
  can't be reached. A store without :list is reached with a fetch, and
  tried for the one shared document that backend gives it, which needs a
  put refused both with a stale version and with an :if-version of nil
  while the document is there. A store without :delete keeps the probe. A
  store without :put is only reached, and is ok when it can be read. The
  store's errors tell refused credentials apart from other failures when
  they have a :status, as those of the WebDAV store do."
  [{:keys [fetch put list delete] :as store}]
  (let [stale    {:if-version "\"no-such-version\""}
        taken    {:if-version nil}
        steps    (cond-> [[:listing (or list #(fetch probe-name {}))]]
                   put
                   (conj [:put     #(put probe-name "probe" {})]
                         [:stale   #(put probe-name "probe" stale)]
                         [:taken   #(put probe-name "probe" taken)]
                         [:fetched #(fetch probe-name {})])

                   (and put delete)
                   (conj [:delete #(delete probe-name)]
                         [:gone   #(fetch probe-name {})]))
        required (cond-> #{:store/reachable :store/authenticated}
                   put                  (conj :store/put :store/fetch)
                   (and put (not list)) (conj :store/conflicts)
                   (and put delete)     (conj :store/delete))]
    (async/then (probe steps)
                (fn [answers]
                  (let [checks (assess answers
                                       (some? list)
                                       (some? put)
                                       (some? delete))]
                    {:ok?    (every? :ok? (filter (comp required :id) checks))
                     :checks checks})))))

(defn backend
  "A backend for dk.simongray.podcast.sync/sync! over `store`, with the
  `opts` below:

  - :strategy, :per-device for a document per device, or :shared for one
    document for all
  - :direction, :pull to read only, :push to write only, or :both
  - :opml?, whether to write subscriptions.opml beside the documents
  - :app, the name of the app in the documents written
  - :name, the store's name by default, and distinct for each backend of
    one sync
  - :document, the name of the shared document, podcast.edn by default

  The :strategy is :per-device when the store has :list, and else
  :shared. The :direction is :both when the store has :put, and else
  :pull. Documents per device are merged on read, and a shared document
  is put on condition of its version."
  ([store]
   (backend store {}))
  ([store {:keys [strategy direction document]
           :or   {document "podcast.edn"}
           :as   opts}]
   {:pre [(map? opts)]}
   (let [strategy  (or strategy (if (:list store) :per-device :shared))
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
                  :per-device (pull-documents store state cursor at)
                  :shared     (pull-shared store document cursor at))
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
                        (mirror-opml store opts latest (:cursor pushed))
                        #(assoc pushed :cursor %))))))))})))
