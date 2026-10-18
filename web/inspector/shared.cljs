(ns inspector.shared
  "What the player's page and its web worker share: the IndexedDB database
  of the library, and the config of the library in either.

  It sits apart from inspector.worker, so that the page requires it without
  pulling the worker's own module into the page."
  (:require [dk.simongray.like-minded.replica :as replica]
            [dk.simongray.like-minded.transit :as transit]
            [dk.simongray.wary-fetch.proxy :as proxy]))

(def store-name
  "The IndexedDB database of the library that the page and the worker
  share, and the name of the channel on which they tell each other that
  they saved it."
  "inspector-library")

(defn config
  "The config of the library in the page or the worker: its requests go
  through the inspector's proxy, its documents are in Transit, and a save
  tells the others on a channel of this context."
  []
  {:send    (proxy/sender (str js/location.origin "/proxy"))
   :codec   transit/codec
   :channel (replica/channel store-name)})
