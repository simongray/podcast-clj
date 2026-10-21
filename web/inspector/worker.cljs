(ns inspector.worker
  "The refresh of the player's feeds in a web worker, so that the page
  stays responsive while the feeds are fetched and parsed, as
  inspector.fetching serves it.

  The worker puts the documents of the feeds that changed into the store
  that inspector.shared names, and answers with what it found, which the
  page applies to the library. A web worker has no DOMParser, so the
  worker parses with that of @xmldom/xmldom."
  (:require ["@xmldom/xmldom" :as xmldom]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [inspector.fetching :as fetching]
            [inspector.shared :as shared]))

(defn throwing
  "A function that makes a parser of the class `DOMParser`, e.g. that of
  xmldom, which throws on the errors that a browser's parser reports as a
  parsererror element."
  [DOMParser]
  (fn []
    (DOMParser. #js {:onError (fn [level message _context]
                                (when-not (= level "warning")
                                  (throw (js/Error. message))))})))

(defn ^:export init
  "Answer the page's requests to fetch the feeds that are due."
  []
  (when-not (exists? js/DOMParser)
    (set! (.-DOMParser js/globalThis) (throwing xmldom/DOMParser)))
  (fetching/serve! {:store (indexeddb/store shared/store-name)
                    :opts  (shared/opts)}))
