(ns dk.simongray.podcast.app.worker
  "A web worker that refreshes the library and fetches feeds, so that the
  parsing of a feed never holds up the page, with the functions of the
  page that ask it.

  In the worker, serve! answers the page. A refresh restores the library
  from the store that the page shares, refreshes it and saves it as a
  replica of the device, which tells the page on the channel of the
  config, and the page catches up with app.library/read-saved! and
  rebase. The worker gives back the :refresh, which the store doesn't
  keep. A fetch gives back what podcast/fetch! gives, without the :body.
  One refresh runs at a time across the tabs and workers of the site,
  through a Web Lock, and a second answers that it's busy.

  On the page, client starts the worker, and refresh! and fetch! ask it.
  The answers that hold a feed travel in the codec of the config, so give
  both sides the codec of dk.simongray.like-minded.transit, since EDN
  reads slower than the feed parses.

  Build the worker as a module of its own, e.g. with :web-worker true in
  shadow-cljs, whose init calls serve!. A web worker has no DOMParser, so
  give serve! one, e.g. that of @xmldom/xmldom."
  (:require [cljs.reader :as reader]
            [dk.simongray.like-minded.replica :as replica]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.library.impl :as library.impl]))

(def refresh-lock
  "The Web Lock that the refresh of a worker holds, so that one runs at a
  time across the tabs and workers of the site."
  "dk.simongray.podcast.app.worker/refresh")

;; Web Locks API, W3C Working Draft of 24 September 2025, section 3.2.1:
;; with ifAvailable, the callback gets null when another holds the lock.
(defn- when-free
  "A promise of what `f` gives while this worker holds the Web Lock
  `name`, or of what `busy` gives when another holds it."
  [name f busy]
  (if-let [^js locks (some-> js/globalThis .-navigator .-locks)]
    (.request locks name #js {:ifAvailable true}
              (fn [lock] (if lock (f) (busy))))
    (js/Promise.resolve (f))))

(defn- post!
  "Post the `message` to the page, as EDN."
  [message]
  (js/postMessage (pr-str message)))

(defonce ^{:doc "The lease of the replica of each device that this worker
  writes as, a promise of what like-minded.replica/lease! gives, by id."}
  leases
  (atom {}))

(defn- lease!
  "A promise of the lease of the replica of `device` that this worker
  writes as, taken at the first request for it."
  [device]
  (or (get @leases device)
      (get (swap! leases assoc device (replica/lease! device)) device)))

(defn- refreshed!
  "A promise of the :refresh of the library of `device` in the :store of
  `served`, refreshed with `opts` and saved, posting the progress of
  request `id` as it goes."
  [{:keys [store config]} id device opts]
  (.then (lease! device)
         (fn [{:keys [name]}]
           (-> (library/restore! (library/make device {:replica name :config config})
                                 store)
               (.then (fn [lib]
                        (library/refresh!
                         lib
                         (assoc opts :on-feed-fn
                                (fn [{:keys [done total]}]
                                  (post! {:id id :progress {:done done :total total}}))))))
               (.then (fn [lib]
                        (.then (library/save! lib store)
                               (fn [{:keys [error]}]
                                 {:id id :refresh (:refresh lib) :error error}))))))))

(defn- fetched!
  "A promise of the answer to request `id`: the feed at `url` fetched
  with `opts` through the :send of the config of `served`, in its codec."
  [{:keys [config]} id url opts]
  (let [codec (library.impl/codec {:config config} {})]
    (.then (podcast/fetch! url (merge (select-keys config [:send]) opts))
           (fn [result]
             {:id id :fetched ((:encode codec) (dissoc result :body))}))))

(defn- answer!
  "Answer the `request` of the page through `served`."
  [served {:keys [id refresh fetch device url opts]}]
  (-> (cond
        refresh (when-free refresh-lock
                           #(refreshed! served id device opts)
                           #(do {:id id :busy? true}))
        fetch   (fetched! served id url opts))
      (.then post!)
      (.catch (fn [e] (post! {:id id :failed (str e)})))))

(defn serve!
  "Answer the requests of the page in this web worker, with the `served`
  map below.

  - :store, the store of the library that the page shares, e.g. of
    like-minded.store.indexeddb
  - :config, the config of the library, e.g. with its :send, its :codec
    and a :channel of like-minded.replica, on which a save tells the page
  - :parser, a function that makes a DOMParser, for a worker without one,
    e.g. #(xmldom/DOMParser.)"
  [{:keys [parser] :as served}]
  (when (and parser (not (exists? js/DOMParser)))
    (set! (.-DOMParser js/globalThis) parser))
  (set! (.-onmessage js/self)
        (fn [^js e]
          (answer! served (reader/read-string (.-data e))))))

(defn client
  "A web worker of the script at `url`, which serve! answers, for the
  page, with the `config` of the library, of which only the :codec
  counts here."
  [url config]
  (let [worker  (js/Worker. url)
        pending (atom {})
        codec   (library.impl/codec {:config config} {})
        settle! (fn [id f]
                  (when-let [{:keys [resolve]} (get @pending id)]
                    (swap! pending dissoc id)
                    (resolve (f))))]
    (.addEventListener worker "message"
                       (fn [^js e]
                         (let [{:keys [id progress fetched] :as answer}
                               (reader/read-string (.-data e))]
                           (cond
                             progress (some-> (get-in @pending [id :on-progress])
                                              (apply [progress]))
                             fetched  (settle! id #((:decode codec) fetched))
                             :else    (settle! id #(dissoc answer :id))))))
    ;; a worker that fails to load or throws answers nothing
    (.addEventListener worker "error"
                       (fn [^js e]
                         (doseq [[id _] @pending]
                           (settle! id #(do {:failed (or (.-message e) "The worker failed")})))))
    {:worker worker :pending pending :next (atom 0)}))

(defn- ask!
  "A promise of the answer of the worker of `client` to `request`, with
  `on-progress` called with each report of its progress."
  [{:keys [^js worker pending next]} request on-progress]
  (let [id (swap! next inc)]
    (js/Promise.
     (fn [resolve _]
       (swap! pending assoc id {:resolve resolve :on-progress on-progress})
       (.postMessage worker (pr-str (assoc request :id id)))))))

(defn refresh!
  "A promise of the refresh of the library of `device` in the worker of
  `client`, with the `opts` of library/refresh! that are data, e.g.
  :feed-guids and :head-check?, calling `on-progress` with the :done and
  :total of each fetch that ends.

  It gives a map of the :refresh, which the store doesn't keep, and any
  :error of the save, or of :busy? when another tab or worker is
  refreshing, or of :failed. Catch up with the library that the worker
  saved through the channel, and put the :refresh into it."
  ([client device opts]
   (refresh! client device opts nil))
  ([client device opts on-progress]
   (ask! client {:refresh true :device device :opts opts} on-progress)))

(defn fetch!
  "A promise of the feed at `url` fetched in the worker of `client`, with
  the `opts` of podcast/fetch! that are data, as podcast/fetch! gives it
  but without the :body, or a map of :failed."
  [client url opts]
  (ask! client {:fetch true :url url :opts opts} nil))
