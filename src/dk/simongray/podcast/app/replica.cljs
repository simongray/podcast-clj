(ns dk.simongray.podcast.app.replica
  "The library of one tab or worker of a web app, as a replica of the
  device, in an atom of the page's state: opened from the store that the
  tabs and workers share, saved there and caught up with what the others
  save, one save or catch-up at a time.

  The library lives at a `path` in the atom `state`, e.g. [:library].
  open! leases the replica a name and restores the library. save! writes
  it, after catching up with what the others saved, and tells them on the
  :channel of the config, and listen! catches it up when they tell. Catch
  it up too when the page comes back from being hidden, since a hidden page
  can miss what they tell."
  (:require [dk.simongray.like-minded.replica :as like-minded.replica]
            [dk.simongray.podcast.app.library :as library]))

(defn open!
  "A promise of the library of `device` that `store` holds, restored with
  the `opts` of library/restore!, or a new one, as a replica of the
  device that this context leases, with the `config` of the library."
  ([store device config]
   (open! store device config {}))
  ([store device config opts]
   (.then (like-minded.replica/lease! device)
          (fn [{:keys [name]}]
            (library/restore! (library/make device {:replica name :config config})
                              store
                              opts)))))

(defonce ^{:doc "The promise of the last save or catch-up of each store, by
  its name, which the next one waits for."}
  turns
  (atom {}))

(defn- in-turn!
  "A promise of what `f` gives, called once the saves and catch-ups of
  `store` before it are done."
  [store f]
  (let [k (:name store)
        p (.then (get @turns k (js/Promise.resolve nil))
                 (fn [_] (f))
                 (fn [_] (f)))]
    (swap! turns assoc k (.catch p (constantly nil)))
    p))

(defn save!
  "Save the library at `path` in the atom `state` to `store`, once the
  saves and catch-ups before it are done, as a promise of what
  library/save! gives. The library as it is by then takes what the save
  caught up with from the other tabs and workers."
  [state path store]
  (in-turn! store
            (fn []
              (when-let [given (get-in @state path)]
                (.then (library/save! given store)
                       (fn [{written :library :as result}]
                         (when written
                           (swap! state update-in path library/rebase given written))
                         result))))))

(defn catch-up!
  "Catch the library at `path` in the atom `state` up with what another
  tab or worker saved in `store` since this one last read or wrote it,
  once the saves and catch-ups before it are done, as a promise of the
  library that the other saved, or nil."
  [state path store]
  (in-turn! store
            (fn []
              (when-let [lib (get-in @state path)]
                (.then (library/read-saved! lib store)
                       (fn [stored]
                         (when stored
                           (swap! state update-in path
                                  #(library/rebase % (:stored %) stored)))
                         stored))))))

(defn listen!
  "Catch the library at `path` in the atom `state` up with `store` whenever
  another tab or worker tells `channel` that it saved, the :channel of the
  config of the library, and give a function that stops it."
  [state path store channel]
  (like-minded.replica/listen! channel (fn [_] (catch-up! state path store))))
