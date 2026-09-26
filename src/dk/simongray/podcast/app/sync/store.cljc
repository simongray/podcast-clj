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
  app that keeps the documents itself; `dk.simongray.podcast.app.sync.file`
  is a folder on disk.

  `backend` turns any store into a backend for
  `dk.simongray.podcast.app.sync/sync!`. By default every device writes its
  own document, `<device-id>.edn`, and reads the others', so that no two
  writers ever meet and a folder any sync client mirrors is a complete
  backend; a store without `:list` holds one shared document instead, put
  on condition of the version fetched and merged again on a conflict.
  `check!` tries any store through this contract and reports, for a
  settings screen's test button.

  Every store namespace follows one convention, so that an app treats them
  alike: `store` for the store over a place, and `backend` for the backend
  in one call with the options of both. Where the places are, the URL a
  server gives a collection or the folder a sync client keeps, is a fact
  about the servers and the clients that changes with them, so the guide
  lists the current ones rather than the library."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
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
              (let [current (:version (get @documents name))]
                (if (and (contains? opts :if-version) (not= (:if-version opts) current))
                  {:conflict? true}
                  (let [version (str (inc (or (some-> current parse-long) 0)))]
                    (swap! documents assoc name {:body body :version version})
                    {:version version}))))
    :list   (fn [] (vec (sort-by :name (for [[name {:keys [version]}] @documents] {:name name :version version}))))
    :delete (fn [name] (swap! documents dissoc name) nil)}))

(defn ^:no-doc then
  "`f` of `x` once `x` is a value: at once on the JVM, and in ClojureScript
  when `x`, a value or a promise, has resolved. What lets a store be written
  once for both platforms."
  [x f]
  #?(:clj  (f x)
     :cljs (.then (js/Promise.resolve x) f)))

(defn ^:no-doc all
  "The values of `xs`, each of which may be a promise in ClojureScript."
  [xs]
  #?(:clj  (vec xs)
     :cljs (.then (js/Promise.all (into-array xs)) vec)))

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
  its listing, that changed since the cursor `ctx` was taken, read at `at`,
  with this device's own noted when it changed under it."
  [{:keys [list fetch]} state {:keys [versions] :as ctx} at]
  (then (list)
        (fn [listing]
          (let [own       (document-name (:device state))
                documents (filter #(str/ends-with? (:name %) ".edn") listing)
                stale     (remove #(= (:version %) (get versions (:name %))) documents)]
            (then (all (map (fn [{:keys [name]}] (then (fetch name) #(some-> % (assoc :name name)))) stale))
                  (fn [fetched]
                    (let [fetched (remove nil? fetched)
                          {:keys [states report]} (sync/read-documents fetched at)]
                      {:states states
                       :ctx    (assoc ctx :versions (-> (into {} (map (juxt :name :version)) documents)
                                                        (into (map (juxt :name :version)) fetched)))
                       :report (cond-> report
                                 (and (contains? versions own) (some #(= own (:name %)) stale))
                                 (conj (describe {:id :sync/shared-device :name own :device (:device state)})))})))))))

(defn- push-document
  "The per-device push: this device's document written when the state
  changed since it was last written, the subscription list beside it when
  asked and the subscriptions changed, and the documents of retired devices
  deleted."
  [{:keys [put delete]} {:keys [opml?] :as opts} state {:keys [versions] :as ctx} at]
  (let [device   (:device state)
        own      (document-name device)
        written  (hash state)
        subs     (hash (player/subscriptions state))
        retired  (filter #(contains? versions (document-name %)) (keys (:retired state)))]
    (if (player/retired? state device)
      {:ctx ctx :report [(describe {:id :sync/retired-device :device device})]}
      (then (when (not= written (:hash ctx))
              (put own (sync/document state at (select-keys opts [:app])) {}))
            (fn [result]
              (then (when (and opml? (not= subs (:opml ctx)))
                      (put opml-name (opml/emit (player/to-opml state)) {}))
                    (fn [_]
                      (then (all (when delete (map #(delete (document-name %)) retired)))
                            (fn [_]
                              {:ctx (cond-> (assoc ctx
                                                   :hash written
                                                   :versions (reduce dissoc versions (map document-name retired)))
                                      result (assoc-in [:versions own] (:version result))
                                      opml?  (assoc :opml subs))})))))))))

(defn- pull-shared
  "The shared-document pull: the one document, read at `at`, when its version
  is not the one the cursor `ctx` remembers."
  [{:keys [fetch]} name {:keys [version] :as ctx} at]
  (then (fetch name)
        (fn [doc]
          (if (or (nil? doc) (= version (:version doc)))
            {:states [] :ctx ctx :report []}
            (let [{:keys [states report]} (sync/read-documents [(assoc doc :name name)] at)]
              {:states states :ctx (assoc ctx :version (:version doc)) :report report})))))

(defn- push-shared
  "The shared-document push: `state` put on condition of the version the
  cursor `ctx` holds, and on a conflict fetched, merged and put again, up to
  `attempts` more times; the merged state comes back for the caller."
  [{:keys [fetch put] :as store} name opts state {:keys [version] :as ctx} at attempts]
  (let [written (hash state)]
    (if (= written (:hash ctx))
      {:ctx ctx}
      (then (put name (sync/document state at (select-keys opts [:app])) {:if-version version})
            (fn [{:keys [conflict?] :as result}]
              (cond
                (not conflict?)
                {:ctx (assoc ctx :version (:version result) :hash written)}

                (zero? attempts)
                {:ctx ctx :report [(describe {:id :sync/conflict :name name})]}

                :else
                (then (fetch name)
                      (fn [doc]
                        (let [{:keys [states report]} (sync/read-documents (when doc [(assoc doc :name name)]) at)
                              merged (reduce player/merge state states)]
                          (then (push-shared store name opts merged (assoc ctx :version (:version doc)) at (dec attempts))
                                (fn [next]
                                  (-> next
                                      (assoc :state merged)
                                      (update :report #(into (vec report) %))))))))))))))

(def probe-name
  "The document `check!` writes and removes to see that it can; not an
  `.edn`, so that a probe left behind is never read as a document."
  "podcast-sync-probe.txt")

(defn- attempt
  "`(f)` as `{:value v}`, or `{:error e}` when it threw or, in
  ClojureScript, rejected."
  [f]
  #?(:clj  (try {:value (f)} (catch Exception e {:error e}))
     :cljs (-> (js/Promise.resolve nil)
               (.then (fn [_] (f)))
               (.then (fn [v] {:value v}))
               (.catch (fn [e] {:error e})))))

(defn- explain
  [e]
  (or (ex-message e) (str e)))

(def ^:no-doc required-checks
  "The checks that must pass for a store to hold one document per device."
  #{:store/reachable :store/authenticated :store/put :store/fetch :store/delete})

(defn- assess
  "The checks, from what the probe of a store found."
  [{:keys [listing put stale fetched delete gone]}]
  (let [check    (fn [id ok? message] {:id id :ok? (boolean ok?) :message message})
        status   (fn [answer] (:status (ex-data (:error answer))))
        refused? (fn [answer] (contains? #{401 403} (status answer)))
        version  (:version (:value put))]
    (cond
      (refused? listing)
      [(check :store/reachable true "The store answered.")
       (check :store/authenticated false (str "The credentials were refused: " (explain (:error listing))))]

      (:error listing)
      [(check :store/reachable false (str "The store could not be reached: " (explain (:error listing))))]

      :else
      (let [documents (:value listing)]
        (cond-> [(check :store/reachable true "The store answered.")
                 (check :store/authenticated true "The credentials were accepted.")
                 (check :store/listing true (str "The store holds " (count documents) " document" (when (not= 1 (count documents)) "s") "."))]
          put
          (conj (cond
                  (refused? put)  (check :store/put false (str "The credentials allow reading but not writing: " (explain (:error put))))
                  (:error put)    (check :store/put false (str "A document could not be written: " (explain (:error put))))
                  (nil? version)  (check :store/put false "A document was written but the store gave it no version.")
                  :else           (check :store/put true "A document can be written and gets a version.")))
          stale
          (conj (check :store/conflicts (:conflict? (:value stale))
                       (if (:conflict? (:value stale))
                         "A stale version is refused, so one shared document would be safe too."
                         "The store does not refuse a stale version; keep one document per device, which is the default.")))
          fetched
          (conj (let [{:keys [body version]} (:value fetched)
                      written (or (:version (:value stale)) (:version (:value put)))]
                  (check :store/fetch (and (= "probe" body) (= version written))
                         (cond
                           (:error fetched)      (str "The document could not be read back: " (explain (:error fetched)))
                           (not= "probe" body)   "The document read back is not what was written."
                           (not= version written) "The document read back has another version than the one written."
                           :else                 "A document reads back as written, with its version."))))
          delete
          (conj (check :store/delete (and (not (:error delete)) (nil? (:value gone)))
                       (cond
                         (:error delete)      (str "The probe document could not be deleted: " (explain (:error delete)) "; remove " probe-name " by hand.")
                         (some? (:value gone)) (str "The probe document is still there after deleting it; remove " probe-name " by hand.")
                         :else                 "A document can be deleted."))))))))

(defn check!
  "Try `store` through its contract and report, for a settings screen's
  test button:

      {:ok?    true                       ; it can hold one document per device
       :checks [{:id :store/reachable :ok? true :message \"The store answered.\"}
                {:id :store/authenticated …} {:id :store/listing …}
                {:id :store/put …}          ; a probe document written, with a version
                {:id :store/conflicts …}    ; a stale version refused, or not: advisory
                {:id :store/fetch …}        ; the probe read back as written
                {:id :store/delete …}]}     ; the probe removed

  On the JVM the report is returned, in ClojureScript promised. The probe
  is `probe-name`, written and removed; a store that cannot be listed is
  left as it is. A store's own errors tell the credentials apart from the
  rest when they carry a `:status`, as the WebDAV store's do."
  [{:keys [fetch put list delete]}]
  (then (attempt list)
        (fn [listing]
          (if (:error listing)
            (let [checks (assess {:listing listing})]
              {:ok? false :checks checks})
            (then (attempt #(put probe-name "probe" {}))
                  (fn [written]
                    (if (:error written)
                      {:ok? false :checks (assess {:listing listing :put written})}
                      (then (attempt #(put probe-name "probe" {:if-version "\"no-such-version\""}))
                            (fn [stale]
                              (then (attempt #(fetch probe-name))
                                    (fn [fetched]
                                      (then (attempt #(delete probe-name))
                                            (fn [deleted]
                                              (then (attempt #(fetch probe-name))
                                                    (fn [gone]
                                                      (let [checks (assess {:listing listing :put written :stale stale
                                                                            :fetched fetched :delete deleted :gone gone})]
                                                        {:ok?    (every? :ok? (filter #(required-checks (:id %)) checks))
                                                         :checks checks}))))))))))))))))

(defn backend
  "A backend for `dk.simongray.podcast.app.sync/sync!` over `store`. `opts`:

      {:strategy  :per-device    ; every device its own document, merged on read;
                                 ; the default when the store has :list
                  :shared        ; one document for all, put on condition of its
                                 ; version; the default when it has not
       :direction :both          ; :pull to read only, :push to write only
       :opml?     false          ; write subscriptions.opml beside the documents
       :app       \"inspector\"   ; named in the documents written
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
              (if push?
                (case strategy
                  :per-device (push-document store opts state ctx at)
                  :shared     (push-shared store document opts state ctx at 3))
                {:ctx ctx}))})))
