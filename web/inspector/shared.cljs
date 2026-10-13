(ns inspector.shared
  "The library that the player's page and its web worker share in
  IndexedDB, and the Web Lock that one writer of it holds at a time.

  It sits apart from inspector.worker, so that the page requires it without
  pulling the worker's own module into the page.")

(def store-name
  "The IndexedDB database of the library that the page and the worker
  share."
  "inspector-library")

(def lock-name
  "The Web Lock that one writer of the shared library holds at a time."
  "inspector-library")

;; Web Locks API, W3C Working Draft of 24 September 2025, section 3.2.1
(defn locked
  "A promise of what `f` gives, called while this page or worker holds the
  Web Lock lock-name, or at once where the platform has no Web Locks."
  [f]
  (if-let [locks (some-> js/globalThis .-navigator .-locks)]
    (.request ^js locks lock-name (fn [_] (f)))
    (js/Promise.resolve (f))))
