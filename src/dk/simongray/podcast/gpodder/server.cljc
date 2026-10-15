(ns dk.simongray.podcast.gpodder.server
  "The gpodder API served over a profile, so that apps such as
  AntennaPod, Kasts and gPodder sync with a server of the listener.

  The server answers the paths of gpodder.net under /api/2. It's a device
  of podcast-clj, whose state holds what the apps send, with each entry
  written by the device of the app.

  The listener has one list of subscriptions, as in the GPodder Sync app
  for Nextcloud. A change counts from the time that it arrived here, so
  that an action that a device sends late still reaches the other
  devices."
  (:require [dk.simongray.like-minded :as like-minded]
            [dk.simongray.podcast.gpodder.server.impl :as impl]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url])
  #?(:clj (:import [java.util.concurrent.locks ReentrantLock])))

(defn public-url?
  "Whether `s` is an http or https URL whose host is on the public internet
  by its text alone, without a lookup, since a push can name many. It's
  the default :allow-pred of handler."
  [s]
  (and (url/web-url? s)
       (let [h (url/host s)]
         (if-let [b (url/ip-address h)]
           (url/public-ip? b)
           (url/public-name? h)))))

(defn stepper
  "A :sync for handler, which syncs a profile through `backends` with
  `opts`, as like-minded/sync! does with profile/sync-options, and gives
  the profile that results, in ClojureScript as a promise. It keeps the
  cursors of each step for the next."
  ([backends]
   (stepper backends {}))
  ([backends opts]
   (let [cursors (atom (:cursors opts))]
     (fn [state]
       (async/then (like-minded/sync! state backends
                                      (merge profile/sync-options
                                             (assoc opts :cursors @cursors)))
                   (fn [result]
                     (reset! cursors (:cursors result))
                     (:state result)))))))

(defn handler
  "A Ring handler that serves the gpodder API over the profile in the
  atom :state of `opts`, for one listener:

  - :state, the atom, e.g. (atom (profile/make \"gpodder-server\"))
  - :authorized-pred, a function of a user and a password that says
    whether to let them in, and everyone it lets in shares the one state
  - :sync, a function of the state that brings it in step with the
    listener's devices, before and after each call
  - :identities-fn, as for gpodder/backend
  - :feed-guid-fn, :feed-url-fn, :episode-id-fn and :episode-url-fn, each
    way on its own, in place of :identities-fn
  - :now-fn, a function of no arguments that gives the time, the clock by
    default
  - :max-bytes, the most that a request can send, 10 MB by default
  - :allow-pred, a predicate of a feed URL that an app may subscribe to,
    by default public-url?, so that the URLs that the server's own device
    fetches are on the public internet
  - :max-additions, the most subscriptions that one push can add, 1000 by
    default, as a first sync of an app adds all of its own
  - :book, the book that an earlier handler gave :on-book-fn, e.g. read
    back at a restart, so that the apps don't get every change again
  - :on-book-fn, a function of the book that the handler calls after a
    call changed it, e.g. to save it

  The book is plain data: when each change arrived here, the devices that
  registered, and the URLs that the apps named episodes by.

  A request has Basic credentials or the session of a login, and the user
  in the path must be the one who logged in. A session lasts a day, and
  ends when the handler is made again, e.g. at a restart with a new
  password. Its cookie is Secure when the login came over https, by the
  scheme of the request or the X-Forwarded-Proto of a proxy. The calls run
  one at a time, as sync steps must, so :sync can be:

      (stepper [backend])

  A :sync gives a state, not a promise of one, so in ClojureScript, sync
  in the app and merge the result into the atom. The server merges what
  it writes with what the app put in the atom in between.

  For a site with many listeners, make a handler for each, with a state,
  an :authorized-pred and a :sync of its own, and pass each request to the
  handler of the user in its path, each with a book of its own. Keep each
  handler while the site runs, since it holds the sessions."
  [{:keys [state authorized-pred on-book-fn] sync! :sync :as opts}]
  (when-not (and (some? state) (fn? authorized-pred))
    (throw (ex-info (str "A handler needs the :state and the function "
                         ":authorized-pred")
                    {})))
  (let [book    (atom (assoc (impl/lasting (:book opts)) :sessions {}))
        told    (atom (impl/lasting @book))
        tell!   (fn []
                  (let [now (impl/lasting @book)]
                    (when-not (every? #(identical? (% now) (% @told))
                                      impl/lasting-keys)
                      (reset! told now)
                      (when on-book-fn
                        (on-book-fn now)))))
        settle! (fn [at]
                  (when sync!
                    (let [from @state]
                      (impl/put! state from (sync! from))))
                  (let [in-step @state]
                    (swap! book impl/settled in-step (inst-ms at))
                    in-step))
        env     (merge {:now-fn        time/now
                        :max-bytes     (* 10 1024 1024)
                        :allow-pred    public-url?
                        :max-additions 1000}
                       opts
                       {:book book :settle! settle!})
        one     #?(:clj  (let [lock (ReentrantLock.)]
                           (fn [f]
                             (.lock lock)
                             (try
                               (f)
                               (finally
                                 (.unlock lock)))))
                   :cljs (fn [f] (f)))]
    (fn [request]
      (one #(let [response (impl/respond env request)]
              (tell!)
              response)))))

(comment
  (#'impl/route "/api/2/subscriptions/ann/phone.json")
  (ring/credentials "Basic YW5uOnNlY3JldA==")
  (url/query-params
   "since=1727000000&podcast=https%3A%2F%2Fexample.org%2Ffeed.xml")

  ;; a server over a state with one subscription, and a request of an app
  (def serve
    (handler {:state           (atom (profile/subscribe
                                      (profile/make "gpodder-server")
                                      "feed-a"
                                      {:url   "https://example.org/feed.xml"
                                       :title "A show"}
                                      #inst "2026-09-29T12:00:00Z"))
              :authorized-pred (fn [user password]
                                 (= ["ann" "secret"] [user password]))}))

  (serve {:request-method :get
          :uri            "/api/2/subscriptions/ann/phone.json"
          :query-string   "since=0"
          :headers        {"authorization" "Basic YW5uOnNlY3JldA=="}})

  ;; no credentials: the challenge that gPodder waits for
  (serve {:request-method :get :uri "/api/2/devices/ann.json" :headers {}})
  #_.)
