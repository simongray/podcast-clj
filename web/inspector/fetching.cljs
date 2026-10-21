(ns inspector.fetching
  "The player's web worker, which fetches feeds for the page so that the
  parsing of a feed never holds up the page, and the functions of the
  page that ask it. It's one way to run library/fetch-due! off the page,
  which an app can do as it likes.

  In the worker, serve! answers the page with what it found, as data. For
  a refresh, that's what library/fetch-due! gives for what library/due
  says is due: the entries of the feeds that it fetched, and the documents
  of those that changed, which it puts in the library's store. It writes
  nothing else, so the page applies what the worker found to its library
  with library/refreshed. One refresh runs at a time across the tabs and
  workers of the site, through a Web Lock, and the worker of another
  answers that it's busy. A fetch gives back what podcast/fetch! gives,
  without the :body.

  On the page, client starts the worker, and fetch-due! and fetch! ask it.
  The answers that hold a feed travel in the :codec of the options, here
  that of dk.simongray.like-minded.transit, since EDN reads slower than
  the feed parses."
  (:require [cljs.reader :as reader]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.library :as library]))

;; The name that podcast-clj's app.worker gave it, before it moved here
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

(defn- found!
  "A promise of the answer to request `id`: what is `due` found with `opts`
  in the :store of `served`, with its :opts, as library/fetch-due! gives
  it, posting the progress of each fetch as it goes."
  [{:keys [store] :as served} id due opts]
  (let [progress (fn [{:keys [done total]}]
                   (post! {:id id :progress {:done done :total total}}))]
    (.then (library/fetch-due! due store (merge (:opts served) opts {:on-feed-fn progress}))
           (fn [found] {:id id :found found}))))

(defn- fetched!
  "A promise of the answer to request `id`: the feed at `url` fetched
  with `opts` through the :send and :lenient-parser of the :opts of
  `served`, in its :codec."
  [served id url opts]
  (let [codec (:codec (:opts served))]
    (.then (podcast/fetch! url (merge (select-keys (:opts served)
                                                   [:send :lenient-parser])
                                      opts))
           (fn [result]
             {:id id :fetched ((:encode codec) (dissoc result :body))}))))

(defn- answer!
  "Answer the `request` of the page through `served`."
  [served {:keys [id due fetch url opts]}]
  (-> (cond
        due   (when-free refresh-lock
                         #(found! served id due opts)
                         #(js/Promise.resolve {:id id :busy? true}))
        fetch (fetched! served id url opts))
      (.then post!)
      (.catch (fn [e] (post! {:id id :failed (str e)})))))

(defn serve!
  "Answer the requests of the page in this web worker, with the `served`
  map below.

  - :store, the store of the library that the page reads, e.g. of
    like-minded.store.indexeddb
  - :opts, the options of the library's calls, e.g. its :send and :codec"
  [served]
  (set! (.-onmessage js/self)
        (fn [^js e]
          (answer! served (reader/read-string (.-data e))))))

(defn client
  "A web worker of the script at `url`, which serve! answers, for the
  page, with the `opts` of the library's calls, of which only the :codec
  counts here."
  [url opts]
  (let [worker  (js/Worker. url)
        pending (atom {})
        codec   (:codec opts)
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

(defn fetch-due!
  "A promise of what the worker of `client` found of what is `due`, as
  library/due gives it, fetched with the `opts` of library/fetch-due! that
  are data, e.g. :head-check?, calling `on-progress` with the :done and
  :total of each fetch that ends.

  It gives a map of the :found, for library/refreshed, or of :busy? while
  another tab or worker is refreshing. It fails when the worker does.

      (.then (fetching/fetch-due! client (library/due lib) {} nil)
             #(when-let [found (:found %)]
                (swap! app update :library
                       (fn [lib] (:library (library/refreshed lib found opts))))))"
  [client due opts on-progress]
  (ask! client {:due due :opts opts} on-progress))

(defn fetch!
  "A promise of the feed at `url` fetched in the worker of `client`, with
  the `opts` of podcast/fetch! that are data, as podcast/fetch! gives it
  but without the :body. It fails when the worker does."
  [client url opts]
  (ask! client {:fetch true :url url :opts opts} nil))
