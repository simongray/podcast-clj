(ns inspector.library
  "The listener's library in the inspector's player, kept in IndexedDB and
  shared by the player's tabs and its web worker.

  The library's document in the store is the one library of the browser,
  which every tab changes as it would an atom. A change is a function of
  the library that gives the new library, or a map of the :library and
  what else it tells, as library/step does, which the tab applies to the
  library on its page at once.
  A save writes the changes that wait with library/write!, on condition
  of the version of the document that the tab knows, and applies them
  again to the stored library when another tab wrote first, as swap!
  does. Then it tells the other tabs the new version on a
  BroadcastChannel, so that they read it, as a page does when it's shown
  again. The worker writes only the documents of the feeds that it
  fetched, as dk.simongray.podcast.app.worker has it."
  (:require [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.like-minded.transit :as transit]
            [dk.simongray.podcast.app.library :as library]
            [inspector.shared :as shared]))

(defonce ^{:doc "The store of the library that the tabs and the worker
  share, made at its first use: the IndexedDB database of inspector.shared,
  unless something else is put here first, e.g. a store in memory for a
  test."}
  library-store
  (atom nil))

(defn the-store
  "The store of the library that the tabs and the worker share."
  []
  (or @library-store
      (reset! library-store (indexeddb/store shared/store-name))))

(def document-name
  "The name of the library's own document in the store, as podcast-clj
  names it in the codec of the options of inspector.shared."
  (str "library" (:extension transit/codec)))

(defonce ^{:doc "The library as this tab last read it from the store or
  wrote it there, and the version of its document, as library/read! gives
  them, once open! has read it."}
  known
  (atom nil))

(defonce ^{:doc "The changes that this tab applied to the library on its
  page and hasn't written yet, in order, each a function of the library."}
  waiting
  (atom []))

(defonce ^{:doc "The promise of the last write or read of this tab, and
  the save that waits its turn after it, if any."}
  turns
  (atom {:last (js/Promise.resolve nil) :next nil}))

(defonce ^{:doc "The BroadcastChannel of the versions that the tabs write,
  opened at its first use, or nil where the browser has none."}
  channel
  (delay (when (exists? js/BroadcastChannel)
           (js/BroadcastChannel. shared/store-name))))

(defn library-of
  "The library that a change gave, `given`: the library itself, or the
  :library of a map such as library/step gives."
  [given]
  (if (contains? given :library) (:library given) given))

(defn applied-all
  "The `library` with the changes `fs` applied in order. A change that
  throws on this library, e.g. a subscription that another tab made
  first, is left out."
  [library fs]
  (reduce (fn [lib f]
            (try
              (library-of (f lib))
              (catch :default e
                (js/console.warn "A change didn't apply to the stored library" e)
                lib)))
          library
          fs))

(defn change!
  "Apply `f`, a change, to the library of the page `state`, an atom, and
  keep it to write at the next save!. Give `f` the time of the listener's
  action, e.g. as the :at of library/step, so that it stamps the same time
  when it's applied again to what another tab wrote. It gives what `f`
  gave, e.g. the :effects of a step beside the library."
  [state f]
  (swap! waiting conj f)
  (let [given (f (:library @state))]
    (swap! state assoc :library (library-of given))
    given))

(defn durable-swap!
  "A promise of `f` of the library that `known` holds, as library/read!
  gives it, written to `store` with the options of inspector.shared on
  condition of the version that `known` holds, and of `f` of the stored
  library again while another tab wrote first. It gives the library and
  the version written, as read! gives them, or the :error of the write."
  [store {:keys [library version]} f]
  (let [changed (f library)
        opts    (shared/opts)]
    (.then (library/write! changed store (assoc opts :if-version version :before library))
           (fn [{:keys [conflict? error] :as written}]
             (cond
               error     written
               conflict? (.then (library/read! library store opts) #(durable-swap! store % f))
               :else     {:library changed :version (:version written)})))))

(defn in-turn!
  "A promise of what `f` gives, called once the writes and reads of this
  tab before it are done."
  [f]
  (let [p (.then (:last @turns) (fn [_] (f)))]
    (swap! turns assoc :last (.catch p (constantly nil)))
    p))

(defn announce!
  "Tell the other tabs that this one wrote the `version` of the library."
  [version]
  (some-> ^js @channel (.postMessage (pr-str {:version version}))))

(defn written!
  "A promise of the write of the changes that wait for the page `state`,
  an atom, with durable-swap!, or of nil when none wait. Where another tab
  wrote first, the page shows the stored library with the changes applied
  again, and those that came meanwhile on top. A write that fails keeps
  the changes waiting for the next."
  [state]
  (let [fs @waiting
        {:keys [library] :as k} @known]
    (if (or (empty? fs) (nil? k))
      (js/Promise.resolve nil)
      (let [shown (:library @state)
            kept! #(swap! waiting (fn [later] (into fs later)))]
        (reset! waiting [])
        (-> (durable-swap! (the-store) k #(if (identical? % library) shown (applied-all % fs)))
            (.then (fn [{:keys [error] :as written}]
                     (if error
                       (kept!)
                       (do (reset! known written)
                           (when-not (identical? (:library written) shown)
                             (swap! state assoc :library
                                    (applied-all (:library written) @waiting)))
                           (announce! (:version written))))
                     written))
            (.catch (fn [e]
                      (kept!)
                      {:error {:id :library/save-failed :reason (ex-message e)}})))))))

(defn save!
  "A promise of the write of the changes that wait for the page `state`,
  an atom, once the writes and reads before it are done, as written!
  gives it. A save asked for while another waits its turn is that one."
  [state]
  (or (:next @turns)
      (let [p (in-turn! #(do (swap! turns assoc :next nil)
                             (written! state)))]
        (swap! turns assoc :next p)
        p)))

(defn stored-version
  "A promise of the version of the library's document in the store, from
  its listing, which reads no bodies."
  []
  (.then (js/Promise.resolve ((:list (the-store))))
         (fn [listed]
           (some #(when (= document-name (:name %)) (:version %)) listed))))

(defn caught-up!
  "A promise of the library that another tab wrote, read into the page
  `state`, an atom, with the changes that wait applied to it, once the
  writes and reads before are done, when the store holds another version
  than this tab knows. It reads only what changed."
  [state]
  (in-turn!
   (fn []
     (when-let [{:keys [library version]} @known]
       (.then (stored-version)
              (fn [stored]
                (when (not= stored version)
                  (.then (library/read! library (the-store) (shared/opts))
                         (fn [read]
                           (reset! known read)
                           (swap! state assoc :library
                                  (applied-all (:library read) @waiting))
                           read)))))))))

;; HTML Living Standard, 9.5 Broadcasting to other browsing contexts, and
;; Page Lifecycle: a hidden or frozen page, or one in the back/forward
;; cache, gets no message, so it reads what it missed when it's shown again
;; https://developer.chrome.com/docs/web-platform/page-lifecycle-api
(defn listen!
  "Read what the other tabs write into the page `state`, an atom, as they
  tell it, and when the page is visible again or shown from the
  back/forward cache."
  [state]
  (some-> ^js @channel
          (.addEventListener "message" (fn [_] (caught-up! state))))
  (.addEventListener js/document "visibilitychange"
                     #(when (= "visible" js/document.visibilityState)
                        (caught-up! state)))
  (.addEventListener js/window "pageshow"
                     #(when (.-persisted %)
                        (caught-up! state))))

(defn open!
  "A promise of the library of the device `device` as the last write left
  it, or of a new one, in the :library of the page `state`, an atom, which
  then reads what the other tabs write. It gives the library and the
  version of its document, as library/read! gives them, with no version
  when the store held none."
  [state device]
  (.then (library/read! (library/make device {:codec transit/codec}) (the-store) (shared/opts))
         (fn [{:keys [library] :as read}]
           (reset! known read)
           (swap! state assoc :library (applied-all library @waiting))
           (listen! state)
           read)))
