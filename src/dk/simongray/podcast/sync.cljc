(ns dk.simongray.podcast.sync
  "Syncing the profile between a listener's devices, over any number of
  backends, through like-minded.

  The functions here are those of dk.simongray.like-minded, with the
  defaults of a profile: its CRDT, the version of its format, its kind and
  the name of a shared document. The options of each call go over them,
  and doc/sync.md explains the design. A backend can also write the
  listener's subscription list beside the documents, for any podcast app
  to import. For the documents themselves, give defaults to the functions
  of like-minded's document namespace."
  (:require [dk.simongray.like-minded :as like-minded]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.wary-fetch.async :as async]))

(def version
  "The version of the profile's format that a document records. It changes
  only when the meaning of a key changes, since a reader merges the keys
  it doesn't know by their shape."
  1)

(def kind
  "The kind of state that the documents of a profile hold."
  "podcast-clj/profile")

(def default-crdt
  "The functions of the CRDT of the profile:

  - :merge, of two states
  - :compact, of a state at an instant with options, which drops the
    tombstones that every device has seen
  - :problem, of a state, which describes what breaks the rules that a
    merge relies on, or gives nil"
  {:merge   profile/merge
   :compact profile/compact
   :problem profile/problem})

(def defaults
  "The options of a sync of the profile, which the options of each call
  here go over: its CRDT, version and kind, and podcast.edn as the name of
  a shared document."
  {:crdt     default-crdt
   :version  version
   :kind     kind
   :document "podcast.edn"})

(def opml-name
  "The name of the subscription list that a backend with :opml? writes
  beside the documents, for any podcast app to import."
  "subscriptions.opml")

(defn- with-subscription-list
  "The options `opts` of a backend, with the subscription list of the
  profile among the documents that their :extra-documents-fn gives. The
  list has the shows in their folders and with their tags, and the lists
  that the listener follows."
  [{:keys [extra-documents-fn] :as opts}]
  (assoc opts :extra-documents-fn
         (fn [state]
           (merge {opml-name (opml/emit (profile.opml/to-opml state))}
                  (when extra-documents-fn
                    (extra-documents-fn state))))))

(defn backend
  "A backend over `store`, a document store of like-minded, with `opts`, as
  like-minded/backend makes it, and :opml? as well, whether to write
  subscriptions.opml beside the documents."
  ([store]
   (backend store {}))
  ([store {:keys [opml?] :as opts}]
   {:pre [(map? opts)]}
   (like-minded/backend store (cond-> (merge defaults (dissoc opts :opml?))
                                opml? with-subscription-list))))

(defn sync!
  "Sync `state` through `backends` in one step with `opts`, as
  like-minded/sync! does, and give the :state, the :cursors, the :at, the
  :failures and the :report, in ClojureScript as a promise."
  ([state backends]
   (sync! state backends {}))
  ([state backends opts]
   (like-minded/sync! state backends (merge defaults opts))))

(defn stepper
  "A function of a state that syncs it through `backends` with `opts`, as
  sync! does, and gives the state that results, in ClojureScript as a
  promise. It keeps the cursors of each step for the next, e.g. as the
  :sync of a gpodder server, which calls it one step at a time."
  ([backends]
   (stepper backends {}))
  ([backends opts]
   (let [cursors (atom (:cursors opts))]
     (fn [state]
       (async/then (sync! state backends (assoc opts :cursors @cursors))
                   (fn [result]
                     (reset! cursors (:cursors result))
                     (:state result)))))))

(defn finish
  "The `state` as it is by now with the `step` of sync! merged in and
  compacted, as like-minded/finish does with `opts`."
  ([state step]
   (finish state step {}))
  ([state step opts]
   (like-minded/finish state step (merge defaults opts))))

(defn reconcile
  "The `state` with the documents `fetched` merged in at `at` with `opts`,
  as a map of the :state and a :report, as like-minded/reconcile merges
  them."
  ([state fetched at]
   (reconcile state fetched at {}))
  ([state fetched at opts]
   (like-minded/reconcile state fetched at (merge defaults opts))))

(defn device-document
  "The document that the device of `state` writes at `at`, with the :app of
  `opts`, as a map of its :name and :body."
  ([state at]
   (device-document state at {}))
  ([state at opts]
   (document/for-device state at (merge defaults opts))))
