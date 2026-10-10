(ns dk.simongray.podcast.store
  "Document stores, which keep named documents with versions, e.g. the
  sync documents of a listener's devices or a saved library.

  A store is a map of functions over named documents with versions:

      {:fetch  (fn [name opts])       ; {:body \"…\" :version \"…\"}, or nil
       :put    (fn [name body opts])  ; {:version \"…\"}, or {:conflict? true}
       :list   (fn [])                ; [{:name \"…\" :version \"…\"}], optional
       :delete (fn [name])            ; optional
       :name   \"Nextcloud\"}           ; for the UI and the report

  A body is text or bytes, a byte array on the JVM and a Uint8Array in
  JavaScript. A fetch gives it as text, or as bytes with :as :bytes in its
  opts, whichever was put. A put is a conflict when its opts have an
  :if-version other than the store's, where nil means that the document
  must not exist yet. A store that can't be written, e.g. over the files
  of an input, has no :put.

  On the JVM, each function returns its value and throws when it fails. In
  ClojureScript, each can return a value or a promise. A store closes over
  its credentials, so they're never in the map, and you wrap a store by
  updating a key."
  (:require [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.wary-fetch.async :as async]))

;; TODO: Decide whether the stores become a library for local-first sync on
;; top of mind-meld. It could hold this store contract, the memory, folder,
;; IndexedDB and encrypted stores, and sync/backend, once the backend no
;; longer writes the OPML of a profile. The contract and those stores need
;; only util.bytes and the async and secret of wary-fetch, and WebDAV needs
;; wary-fetch and qname-hiccup, which are libraries now, so it could go too.

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
                (some-> (get @documents name)
                        (update :body bytes/as (:as opts))))
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

(defn put-all!
  "Put the `documents`, maps of :name and :body, into `store` one after the
  other, in order, and give their names, in ClojureScript as a promise."
  [store documents]
  (reduce (fn [names {:keys [name body]}]
            (async/then names
                        (fn [names]
                          (async/then ((:put store) name body {})
                                      (fn [_] (conj names name))))))
          []
          documents))

(defn fetch-all!
  "The documents of `store`, fetched with `opts`, as maps of :name and
  :body, in ClojureScript as a promise.

  The :except of `opts` names documents to leave out, and the other
  `opts` go to each fetch. The fetches are under way at once. A store
  without :list has none, and a document deleted since the listing is
  left out."
  ([store]
   (fetch-all! store {}))
  ([store {:keys [except] :or {except #{}} :as opts}]
   (let [fetch-opts (dissoc opts :except)
         fetch!     (fn [name]
                      (async/then ((:fetch store) name fetch-opts)
                                  #(some-> % (assoc :name name))))]
     (async/then (if-let [list (:list store)] (list) [])
                 (fn [listed]
                   (async/then
                    (async/all fetch! (remove except (map :name listed)))
                    #(filterv some? %)))))))

(def probe-name
  "The document that a check writes and removes, which isn't an .edn file,
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

      {:ok?    true                   ; it works as a sync backend uses it
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
  tried for the one shared document that a sync backend gives it, which
  needs a put refused both with a stale version and with an :if-version
  of nil while the document is there. A store without :delete keeps the
  probe. A store without :put is only reached, and is ok when it can be
  read. The store's errors tell refused credentials apart from other
  failures when they have a :status, as those of the WebDAV store do."
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
