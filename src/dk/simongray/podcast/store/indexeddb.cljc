(ns dk.simongray.podcast.store.indexeddb
  "A document store in the browser's IndexedDB, for what an app keeps on
  the device, e.g. its library and its search index.

  The documents of a store are in a database of its own, by name. A body
  is kept as it was put, text or bytes, and a version counts the writes of
  a document. A put with :if-version checks the version and writes in one
  transaction, and a listing reads the versions without the bodies.

  A browser may clear the storage of a site when the device runs out of
  room, unless the site has navigator.storage.persist(), so keep there
  what can be made again. The store is in ClojureScript only, and each of
  its functions gives a promise. A put that the browser has no room for
  fails with ::full.

  The store follows the Indexed Database API 3.0 of the W3C,
  https://www.w3.org/TR/IndexedDB/"
  #?(:cljs (:require [dk.simongray.podcast.util.bytes :as bytes])))

#?(:cljs
   (defn- failure
     "The error `e` of IndexedDB as an ex-info with a :type when the listener
     can do something about it, or else `e`."
     [e]
     (if (= "QuotaExceededError" (some-> e .-name))
       (ex-info "The browser has no more room for the site's storage"
                {:type ::full}
                e)
       e)))

;; Indexed Database API 3.0, sections 5.1 and 5.7: a database opened at a
;; new version is upgraded, which is when its object stores are made;
;; section 2.1.1: an open connection blocks the upgrade or deletion that
;; another asks for with a versionchange, until it closes
#?(:cljs
   (defn- opened
     "A promise of the database named `name`, with the object stores of
     the bodies and the versions of the documents, which calls `on-close`
     when it closes or fails to open.

     It closes when another connection asks to delete or upgrade it, or
     when the browser clears it."
     [name on-close]
     (js/Promise.
      (fn [resolve reject]
        (let [request (.open js/indexedDB name 1)]
          (set! (.-onupgradeneeded request)
                (fn [_]
                  (let [db (.-result request)]
                    (.createObjectStore db "bodies")
                    (.createObjectStore db "versions"))))
          (set! (.-onsuccess request)
                (fn [_]
                  (let [db (.-result request)]
                    (set! (.-onversionchange db) #(do (.close db) (on-close)))
                    (set! (.-onclose db) on-close)
                    (resolve db))))
          (set! (.-onerror request)
                (fn [_]
                  (on-close)
                  (reject (failure (.-error request))))))))))

;; Indexed Database API 3.0, section 2.7.1: a transaction completes once it
;; has committed, and a failed request aborts it
#?(:cljs
   (defn- transact!
     "A promise of what `step` gives, a function of the object stores of
     the bodies and the versions and of a function that keeps a result, in
     a transaction of `mode`, \"readonly\" or \"readwrite\", on the
     promise of a database `db`, once the transaction completes."
     [db mode step]
     (.then db
            (fn [d]
              (js/Promise.
               (fn [resolve reject]
                 (let [tx     (.transaction d #js ["bodies" "versions"] mode)
                       result (volatile! nil)
                       failed #(reject (failure (.-error tx)))]
                   (set! (.-oncomplete tx) #(resolve @result))
                   (set! (.-onerror tx) failed)
                   (set! (.-onabort tx) failed)
                   (step (.objectStore tx "bodies")
                         (.objectStore tx "versions")
                         #(vreset! result %)))))))))

#?(:cljs
   (defn- on-success
     "Call `f` with the result of the IndexedDB `request` when it succeeds."
     [request f]
     (set! (.-onsuccess request) #(f (.-result request)))))

#?(:cljs
   (defn- fetch-step
     "The step of a fetch of the document `name` with `opts`: its body and
     version, or nil when it isn't there."
     [name opts]
     (fn [bodies versions done]
       (on-success (.get versions name)
                   (fn [version]
                     (when version
                       (on-success (.get bodies name)
                                   (fn [body]
                                     (done {:body    (bytes/as body (:as opts))
                                            :version version})))))))))

#?(:cljs
   (defn- put-step
     "The step of a put of `body` as the document `name` with `opts`: its
     new version, or a conflict with the :if-version of `opts`."
     [name body opts]
     (fn [bodies versions done]
       (on-success (.get versions name)
                   (fn [current]
                     (if (and (contains? opts :if-version)
                              (not= (:if-version opts) current))
                       (done {:conflict? true})
                       (let [n       (or (some-> current parse-long) 0)
                             version (str (inc n))]
                         (.put bodies body name)
                         (.put versions version name)
                         (done {:version version}))))))))

#?(:cljs
   (defn- list-step
     "The step of a listing: the names and versions of the documents."
     []
     (fn [_ versions done]
       (on-success (.getAllKeys versions)
                   (fn [names]
                     (on-success (.getAll versions)
                                 (fn [vs]
                                   (done (mapv (fn [name version]
                                                 {:name name :version version})
                                               names
                                               vs)))))))))

#?(:cljs
   (defn store
     "The store in the IndexedDB database named `name`, which is made on the
     first call. Each app names a database of its own, since the
     databases of one site are shared by its pages.

     The store keeps the database open between calls, and opens it again
     after it closed, e.g. for another tab that deletes it."
     [name]
     (let [open   (atom nil)
           closed #(reset! open nil)
           db     #(or @open (reset! open (opened name closed)))]
       {:name   name
        :fetch  (fn [doc-name opts]
                  (transact! (db) "readonly" (fetch-step doc-name opts)))
        :put    (fn [doc-name body opts]
                  (transact! (db) "readwrite" (put-step doc-name body opts)))
        ;; the names come in order, as sections 2.4 and 5.12 have it
        :list   (fn []
                  (transact! (db) "readonly" (list-step)))
        :delete (fn [doc-name]
                  (transact! (db)
                             "readwrite"
                             (fn [bodies versions done]
                               (.delete bodies doc-name)
                               (.delete versions doc-name)
                               (done nil))))})))
