(ns dk.simongray.podcast.app.worker
  "A web worker that refreshes the library and fetches feeds, so that the
  parsing of a feed never holds up the page, with the functions of the
  page that ask it.

  In the worker, serve! answers the page. A refresh restores the library
  from the store that the page shares, refreshes it and saves it as a
  replica of the device, which tells the page on the channel of the
  store. A fetch gives back what podcast/fetch! gives, without the :body.
  One refresh runs at a time across the tabs and workers of the site,
  through a Web Lock, and the worker of another answers that it's busy.

  On the page, client starts the worker, and refresh! and fetch! ask it.
  refresh! saves the replica of like-minded.replica/open! first, catches
  it up after, and puts in the :refresh, which the store doesn't keep. The answers
  that hold a feed travel in the codec of the config, so give both sides
  the codec of dk.simongray.like-minded.transit, since EDN reads slower
  than the feed parses.

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
  `served`, refreshed with `opts` and saved, which tells the page on the
  channel of the config, posting the progress of request `id` as it goes."
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

(defn- throwing
  "A function that makes a parser of the class `DOMParser`, e.g. that of
  @xmldom/xmldom, which throws on the errors that a browser's parser
  reports as a parsererror element."
  [DOMParser]
  (fn []
    (DOMParser. #js {:onError (fn [level message _context]
                                (when-not (= level "warning")
                                  (throw (js/Error. message))))})))

(defn serve!
  "Answer the requests of the page in this web worker, with the `served`
  map below.

  - :store, the store of the library that the page shares, e.g. of
    like-minded.store.indexeddb
  - :config, the config of the library, e.g. with its :send and :codec
  - :dom-parser, a DOMParser class for a worker without one, e.g. that of
    @xmldom/xmldom, which throws on what a browser reports as a
    parsererror"
  [{:keys [store dom-parser] :as served}]
  (when (and dom-parser (not (exists? js/DOMParser)))
    (set! (.-DOMParser js/globalThis) (throwing dom-parser)))
  ;; the library's saves tell the replicas of the pages on a channel of the
  ;; store, as like-minded.replica's do
  (let [served (assoc-in served [:config :channel]
                         (replica/channel (replica/channel-name store)))]
    (set! (.-onmessage js/self)
          (fn [^js e]
            (answer! served (reader/read-string (.-data e)))))))

(defn client
  "A web worker of the script at `url`, which serve! answers, for the
  page, with the `config` of the library, of which only the :codec
  counts here."
  [url config]
  (let [worker  (js/Worker. url)
        pending (atom {})
        codec   (library.impl/codec {:config config} {})
        settle! (fn [id f]
                  (when-let [{:keys [resolve reject]} (get @pending id)]
                    (swap! pending dissoc id)
                    (try
                      (resolve (f))
                      (catch :default e
                        (reject e)))))]
    (.addEventListener worker "message"
                       (fn [^js e]
                         (let [{:keys [id progress fetched failed] :as answer}
                               (reader/read-string (.-data e))]
                           (cond
                             progress (some-> (get-in @pending [id :on-progress])
                                              (apply [progress]))
                             failed   (settle! id #(throw (js/Error. failed)))
                             fetched  (settle! id #((:decode codec) fetched))
                             :else    (settle! id #(dissoc answer :id))))))
    ;; a worker that fails to load or throws answers nothing
    (.addEventListener worker "error"
                       (fn [^js e]
                         (doseq [[id _] @pending]
                           (settle! id #(throw (js/Error. (or (.-message e)
                                                              "The worker failed")))))))
    {:worker worker :pending pending :next (atom 0)}))

(defn- ask!
  "A promise of the answer of the worker of `client` to `request`, with
  `on-progress` called with each report of its progress, which fails when
  the worker does."
  [{:keys [^js worker pending next]} request on-progress]
  (let [id (swap! next inc)]
    (js/Promise.
     (fn [resolve reject]
       (swap! pending assoc id {:resolve resolve :reject reject :on-progress on-progress})
       (.postMessage worker (pr-str (assoc request :id id)))))))

(defn refresh!
  "A promise of the refresh of the library of the `replica` of
  like-minded.replica/open! in the worker of `client`, with the `opts` of
  library/refresh! that are data, e.g. :feed-guids and :head-check?,
  calling `on-progress` with the :done and :total of each fetch that
  ends.

  It saves the replica first, so that the worker reads what it has, and
  catches it up with what the worker saved after, with the :refresh put
  into its library, since next-refresh reads which feeds failed from it.
  It gives a map of the :refresh and any :error of the worker's save, or
  of :busy? when another tab or worker is refreshing, whose save the
  replica catches up with as it's told. It fails when the worker does."
  ([client replica opts]
   (refresh! client replica opts nil))
  ([client {:keys [state path] :as replica} opts on-progress]
   (-> (replica/save! replica)
       (.then #(ask! client
                     {:refresh true
                      :device  (:device (replica/state-of replica))
                      :opts    opts}
                     on-progress))
       (.then (fn [{:keys [refresh] :as answer}]
                (.then (replica/catch-up! replica)
                       (fn [_]
                         (when refresh
                           (swap! state update-in path assoc :refresh refresh))
                         answer)))))))

(defn fetch!
  "A promise of the feed at `url` fetched in the worker of `client`, with
  the `opts` of podcast/fetch! that are data, as podcast/fetch! gives it
  but without the :body. It fails when the worker does."
  [client url opts]
  (ask! client {:fetch true :url url :opts opts} nil))
