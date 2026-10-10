(ns dk.simongray.podcast.sync
  "Syncing the profile between a listener's devices, over any number of
  backends, through like-minded.

  The functions here are those of dk.simongray.like-minded and its
  document namespace, with the profile as the state: its CRDT, the version
  of its format and its kind. Their `opts` go over these defaults, and
  doc/sync.md explains the design. A backend can also write the
  listener's subscription list beside the documents, for any podcast app
  to import."
  (:require [dk.simongray.like-minded :as like-minded]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]))

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
  "The options of the state of a profile, which the `opts` of each
  function here go over."
  {:crdt default-crdt :version version :kind kind})

(def opml-name
  "The name of the subscription list that a backend with :opml? writes
  beside the documents, for any podcast app to import."
  "subscriptions.opml")

(def skew-tolerance
  "How far ahead of now a document can be written before the report says
  that its device's clock is ahead: a minute, in milliseconds."
  document/skew-tolerance)

(def default-cadence
  "How many seconds to wait before syncing again, as
  dk.simongray.like-minded/default-cadence has them."
  like-minded/default-cadence)

(defn document
  "The document of `state` written at `at` with `opts`, as an EDN string,
  as like-minded's document/encode writes it."
  ([state at]
   (document state at {}))
  ([state at opts]
   (document/encode state at (merge defaults opts))))

(defn read-document
  "The envelope in the document `s`, read with `opts`, as like-minded's
  document/decode reads it."
  ([s]
   (read-document s {}))
  ([s opts]
   (document/decode s (merge defaults opts))))

(defn read-documents
  "The states in the documents `fetched`, read at `at` with `opts`, as a
  map of :states and a :report, as like-minded's document/states reads
  them."
  ([fetched at]
   (read-documents fetched at {}))
  ([fetched at opts]
   (document/states fetched at (merge defaults opts))))

(defn reconcile
  "The `state` with the documents `fetched` merged in at `at` with `opts`,
  as a map of the :state and a :report, as like-minded/reconcile merges
  them."
  ([state fetched at]
   (reconcile state fetched at {}))
  ([state fetched at opts]
   (like-minded/reconcile state fetched at (merge defaults opts))))

(defn document-name
  "The name of the document that the device `device` writes."
  [device]
  (document/name-of device defaults))

(defn device-document
  "The document that the device of `state` writes at `at`, with the :app of
  `opts`, as a map of its :name and :body."
  ([state at]
   (device-document state at {}))
  ([state at opts]
   (document/for-device state at (merge defaults opts))))

(defn- subscription-list
  "The extra documents of a backend with :opml?, for the profile `state`:
  its subscription list, with the shows in their folders and with their
  tags, and the lists that the listener follows."
  [state]
  {opml-name (opml/emit (profile/to-opml state))})

(defn backend
  "A backend over `store`, a document store of like-minded, with `opts`, as
  like-minded/backend makes it, and these as well:

  - :opml?, whether to write subscriptions.opml beside the documents
  - :document, the name of the shared document, podcast.edn by default"
  ([store]
   (backend store {}))
  ([store {:keys [opml?] :as opts}]
   {:pre [(map? opts)]}
   (let [extra (:extra-documents-fn opts)]
     (like-minded/backend
      store
      (cond-> (merge defaults {:document "podcast.edn"} (dissoc opts :opml?))
        opml? (assoc :extra-documents-fn
                     (fn [state]
                       (merge (subscription-list state)
                              (when extra (extra state))))))))))

(defn next-sync
  "When to sync again at `at`, after the last sync `step`, with the waits
  of `cadence`, as like-minded/next-sync says."
  ([step at]
   (like-minded/next-sync step at))
  ([step at cadence]
   (like-minded/next-sync step at cadence)))

(defn sync!
  "Sync `state` through `backends` in one step with `opts`, as
  like-minded/sync! does, and give the :state, the :cursors and the
  :report, in ClojureScript as a promise."
  ([state backends]
   (sync! state backends {}))
  ([state backends opts]
   (like-minded/sync! state backends (merge defaults opts))))
