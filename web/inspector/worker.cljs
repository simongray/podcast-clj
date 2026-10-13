(ns inspector.worker
  "The refresh of the player's feeds in a web worker, so that the page
  stays responsive while the feeds are fetched and parsed.

  When the page asks, the worker restores the library from the store that
  inspector.shared names, refreshes its feeds through the inspector's
  proxy, saves it, and posts what it found. The page saves before it asks,
  and restores and merges when the worker answers. Both write under the
  Web Lock of inspector.shared, so that one writes at a time.

  A web worker has no DOMParser, so the worker installs that of
  @xmldom/xmldom to parse the feeds with."
  (:require ["@xmldom/xmldom" :as xmldom]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.wary-fetch.proxy :as proxy]
            [inspector.shared :as shared]))

(defn- parser
  "An xmldom DOMParser that throws on the errors that a browser's parser
  reports as a parsererror element."
  []
  (xmldom/DOMParser. #js {:onError (fn [level message _context]
                                     (when-not (= level "warning")
                                       (throw (js/Error. message))))}))

(defn- refreshed!
  "A promise of the result of the refresh of the library of the `device`
  in the shared store, which is saved there."
  [device]
  (let [store (indexeddb/store shared/store-name)]
    (shared/locked
     #(-> (library/restore! (library/make device) store)
          (.then (fn [lib]
                   (library/refresh!
                    lib
                    {:send (proxy/sender (str js/location.origin "/proxy"))})))
          (.then (fn [lib]
                   (.then (library/save! lib store)
                          (fn [_] (:refresh lib)))))))))

(defn ^:export init
  "Install a DOMParser, and answer each message of the page, which names
  the :device of the library, with the :refresh result as EDN, or with
  the :error.

  The store doesn't keep the result, and the page needs its report, since
  the next refresh waits for the feeds that failed."
  []
  (when-not (exists? js/DOMParser)
    (set! (.-DOMParser js/globalThis) parser))
  (set! (.-onmessage js/self)
        (fn [^js e]
          (let [device (.. e -data -device)]
            (-> (refreshed! device)
                (.then #(js/postMessage #js {:refresh (pr-str %)}))
                (.catch (fn [error]
                          (js/postMessage
                           (clj->js {:error (str error)})))))))))
