(ns dk.simongray.podcast.app.replica
  "The library of one tab or worker of a web app, as a replica of the
  device, in an atom of its state: opened from the store that the tabs and
  workers share, saved there and caught up with what the others save.

  open! leases the replica a name, restores the library into the atom, and
  gives a replica to save! and catch-up! with. A save catches up with what
  the others saved first, and tells them on the channel of the store, and
  each catches up as it hears that, and in a page also when the page is
  visible again, since a hidden page can miss what they tell. The saves
  and catch-ups of a store take turns, and a save asked for while another
  waits its turn is that one."
  (:require [dk.simongray.like-minded.replica :as like-minded.replica]
            [dk.simongray.podcast.app.library :as library]))

(defn channel-name
  "The name of the channels on which the tabs and workers that share
  `store` tell each other that they saved the library, by the store's
  name."
  [store]
  (str "dk.simongray.podcast.app.replica/" (:name store)))

(defonce ^{:doc "The channel of each store in this context, by the name of
  the channel, for the saves of with-channel."}
  channels
  (atom {}))

(defn with-channel
  "The `config` of a library that shares `store` but is no replica of
  open!, e.g. that of a refresh in a worker, with a channel of the store in
  this context as its :channel, on which its save! tells the others."
  [config store]
  (let [k (channel-name store)]
    (assoc config :channel (or (get @channels k)
                               (get (swap! channels assoc k
                                           (like-minded.replica/channel k))
                                    k)))))

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

(defn library-of
  "The library of the `replica` as it is now."
  [{:keys [state path]}]
  (get-in @state path))

(defn save!
  "Save the library of the `replica` to its store, once the saves and
  catch-ups before it are done, as a promise of what library/save!
  gives. The library as it is by then takes what the save caught up with
  from the other tabs and workers. A save asked for while another waits
  its turn is that one, since it writes the library as it is when it
  starts."
  [{:keys [state path store waiting] :as replica}]
  (or @waiting
      (reset! waiting
              (in-turn! store
                        (fn []
                          (reset! waiting nil)
                          (when-let [given (library-of replica)]
                            (.then (library/save! given store)
                                   (fn [{written :library :as result}]
                                     (when written
                                       (swap! state update-in path
                                              library/rebase given written))
                                     result))))))))

(defn catch-up!
  "Catch the library of the `replica` up with what another tab or worker
  saved in its store since this one last read or wrote it, once the saves
  and catch-ups before it are done, as a promise of the library that the
  other saved, or nil."
  [{:keys [state path store] :as replica}]
  (in-turn! store
            (fn []
              (when-let [lib (library-of replica)]
                (.then (library/read-saved! lib store)
                       (fn [stored]
                         (when stored
                           (swap! state update-in path
                                  #(library/rebase % (:stored %) stored)))
                         stored))))))

;; Page Lifecycle: a page that is hidden or frozen, or in the back/forward
;; cache, gets no message from a BroadcastChannel, so it catches up when
;; it's visible again, or shown from the cache.
;; https://developer.chrome.com/docs/web-platform/page-lifecycle-api
(defn- follow-page!
  "Catch the `replica` up whenever the page is visible again or shown from
  the back/forward cache, where there's a page, and give a function that
  stops it."
  [replica]
  (if (exists? js/document)
    (let [visible (fn [_]
                    (when (= "visible" (.-visibilityState js/document))
                      (catch-up! replica)))
          shown   (fn [^js e]
                    (when (.-persisted e)
                      (catch-up! replica)))]
      (.addEventListener js/document "visibilitychange" visible)
      (.addEventListener js/window "pageshow" shown)
      (fn []
        (.removeEventListener js/document "visibilitychange" visible)
        (.removeEventListener js/window "pageshow" shown)))
    (constantly nil)))

(defn open!
  "A promise of the replica of the library of `device` in `store`: the
  library that the store holds, or a new one, restored with the :load of
  `opts` and put at `path` in the atom `state`, as a replica of the device
  that this context leases, with the `config` of the library.

  The replica is a map to give save! and catch-up!, with a :close! that
  stops its catching up and gives back the lease. It catches up whenever
  another tab or worker of `store` tells that it saved, and in a page also
  when the page is visible again. Its library tells the others on a
  channel of its own, the :channel of the config."
  [{:keys [state path store device config] :as opts}]
  (.then
   (like-minded.replica/lease! device)
   (fn [{:keys [name release!]}]
     (let [channel (like-minded.replica/channel (channel-name store))]
       (.then
        (library/restore! (library/make device {:replica name
                                                :config  (assoc config :channel channel)})
                          store
                          (select-keys opts [:load]))
        (fn [lib]
          (swap! state assoc-in path lib)
          (let [replica  {:state   state
                          :path    path
                          :store   store
                          :waiting (atom nil)}
                stop!    (like-minded.replica/listen! channel
                                                      (fn [_] (catch-up! replica)))
                unfollow (follow-page! replica)]
            (assoc replica :close! (fn []
                                     (stop!)
                                     (unfollow)
                                     (like-minded.replica/close! channel)
                                     (release!))))))))))
