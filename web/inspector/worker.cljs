(ns inspector.worker
  "The refresh of the player's feeds in a web worker, so that the page
  stays responsive while the feeds are fetched and parsed, as
  dk.simongray.podcast.app.worker serves it.

  The worker puts the documents of the feeds that changed into the store
  that inspector.shared names, and answers with what it found, which the
  page applies to the library. A web worker has no DOMParser, so the
  worker parses with that of @xmldom/xmldom."
  (:require ["@xmldom/xmldom" :as xmldom]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.podcast.app.worker :as worker]
            [inspector.shared :as shared]))

(defn ^:export init
  "Answer the page's requests to fetch the feeds that are due."
  []
  (worker/serve! {:store      (indexeddb/store shared/store-name)
                  :config     (shared/config)
                  :dom-parser xmldom/DOMParser}))
