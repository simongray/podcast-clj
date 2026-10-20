(ns inspector.shared
  "What the player's page and its web worker share: the IndexedDB database
  of the library, and the options of the library's calls in either.

  It sits apart from inspector.worker, so that the page requires it without
  pulling the worker's own module into the page."
  (:require [dk.simongray.like-minded.transit :as transit]
            [dk.simongray.wary-fetch.proxy :as proxy]))

(def store-name
  "The IndexedDB database of the library that the page and the worker
  share."
  "inspector-library")

(defn opts
  "The options of the library's calls in the page or the worker: its
  requests go through the inspector's proxy on this origin, and its
  documents are in Transit."
  []
  {:send  (proxy/sender (str js/location.origin "/proxy"))
   :codec transit/codec})
