(ns dk.simongray.podcast.sync.gpodder.server
  "The gpodder API served over a profile, so that apps such as
  AntennaPod, Kasts and gPodder sync with a server of the listener.

  The server answers the paths of gpodder.net under /api/2. It's a device
  of podcast-clj, whose state holds what the apps send, with each entry
  written by the device of the app.

  The listener has one list of subscriptions, as in the GPodder Sync app
  for Nextcloud. A change counts from the time that it arrived here, so
  that an action that a device sends late still reaches the other
  devices."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.url :as url])
  #?(:clj (:import [java.util.concurrent.locks ReentrantLock])))

(def ^:no-doc session-seconds
  "How long a session lasts after its login: a day. A client of podcast-clj
  logs in at each step."
  86400)

(defn- new-session
  "The id of a new session, which nobody can guess."
  []
  #?(:clj  (str (random-uuid))
     ;; random-uuid asks Math.random there
     :cljs (if (exists? js/crypto)
             (.randomUUID js/crypto)
             (str (random-uuid)))))

(defn ^:no-doc live
  "The `sessions` that still last at the instant `at`, by id."
  [sessions at]
  (into {} (filter (fn [[_ {:keys [until]}]] (< (inst-ms at) until))) sessions))

(defn- login
  "The answer to a login in the call context `ctx`: a session for the
  user, set as a cookie.

  It's the session of the request when that still lasts, so that a client
  that logs in at each step keeps one session, or else a new one. The
  sessions that ended leave the book."
  [{:keys [book who session at] :as ctx}]
  (let [lasting (live (:sessions book) at)
        id      (if (= who (:user (get lasting session))) session (new-session))
        until   (+ (inst-ms at) (* 1000 session-seconds))
        entry   {:user who :until until}]
    {:status  200
     :body    ""
     :headers {"Set-Cookie" (str "sessionid=" id "; Path=/; HttpOnly")}
     :book    (assoc book :sessions (assoc lasting id entry))}))

(defn- logout
  "The answer to a logout in the call context `ctx`, which ends the
  session of the request."
  [{:keys [book session] :as ctx}]
  {:status 200 :body "" :book (update book :sessions dissoc session)})

(defn- devices
  "The answer with the devices that registered in the book of the call
  context `ctx`, each with the number of the listener's subscriptions,
  since they share one list."
  [{:keys [book state] :as ctx}]
  (let [n      (count (profile/subscriptions state))
        listed (fn [[id {:keys [caption type]}]]
                 {"id"            id
                  "caption"       (or caption id)
                  "type"          (or type "other")
                  "subscriptions" n})]
    {:status 200
     :body   (mapv listed (sort-by key (:devices book)))}))

(defn- register
  "The answer that registers the device of the call context `ctx` in its
  book, with the caption and type of the JSON body."
  [{:keys [book device json] :as ctx}]
  (let [{:strs [caption type]} (when (map? json) json)
        types #{"desktop" "laptop" "mobile" "server" "tablet" "other"}]
    (if (and type (not (types type)))
      {:status 400 :body "Unknown device type"}
      {:status 200
       :body   ""
       :book   (update-in book [:devices device] merge
                          (cond-> {}
                            (string? caption) (assoc :caption caption)
                            type              (assoc :type type)))})))

(defn- subscriptions
  "The answer with the feeds added to and removed from the subscriptions
  since the :since-ms of the call context `ctx`, by URL and by the time
  they arrived.

  A device that has seen nothing yet gets no removed ones, and the device
  counts as registered once it asks. A state can hold one URL under two
  guids, and when one of them is live, the URL isn't removed."
  [{:keys [state book device since-ms seconds] :as ctx}]
  (if-not since-ms
    {:status 400 :body "The parameter since is missing or not a number"}
    (let [public  #(first (url/split-credentials (:url %)))
          known   (filter (comp url/web-url? :url val) (:subscriptions state))
          arrived #(second (get-in book [:index [:subscriptions %]] [0 0]))
          urls    (fn [pred]
                    (distinct (for [[g e] known
                                    :when (and (pred e)
                                               (>= (arrived g) since-ms))]
                                (public e))))
          live?   (into #{}
                        (comp (map val)
                              (remove :removed?)
                              (map (comp guid/normal-url public)))
                        known)
          gone    (remove (comp live? guid/normal-url) (urls :removed?))]
      {:status 200
       :body   {"add"       (vec (urls (complement :removed?)))
                "remove"    (if (zero? since-ms) [] (vec gone))
                "timestamp" seconds}
       :book   (update-in book [:devices device] #(or % {}))})))

(defn- change-subscriptions
  "The answer that applies the subscription changes of the JSON body in
  the call context `ctx` to its state, as the device made them when the
  call came.

  The body is a JSON object with the URLs to \"add\" and to \"remove\"."
  [{:keys [state book names device json at seconds] :as ctx}]
  (let [{:strs [add remove]} (when (map? json) json)
        urls? #(or (nil? %) (and (sequential? %) (every? string? %)))]
    (cond
      (not (and (map? json) (urls? add) (urls? remove)))
      {:status 400
       :body   "The body is not {\"add\": [url …], \"remove\": [url …]}"}

      (some (set add) remove)
      {:status 400
       :body   "The same URL cannot be added and removed at the same time"}

      :else
      {:status 200
       :body   {"timestamp" seconds "update_urls" []}
       :state  (gpodder/apply-subscriptions state {:add add :remove remove}
                                            names device at)
       :book   (update-in book [:devices device] #(or % {}))})))

(defn- episode-actions
  "The answer with the episode actions that tell what changed in the
  episodes of the call context `ctx` since its :since-ms, by the time it
  arrived.

  Each action has the URLs that its episode arrived with, or else those
  that the names give, and the parameters can ask for one podcast and one
  device."
  [{:keys [state book names since-ms params seconds] :as ctx}]
  (let [{:strs [podcast device]} params
        arrived #(second (get-in book [:index [:episodes %]] [0 0]))
        urls-of (fn [[g _ :as id]]
                  (let [[p m] (get-in book [:urls id])]
                    [(or p (some-> ((:feed-url-fn names) g)
                                   url/split-credentials
                                   first))
                     (or m ((:episode-url-fn names) id))]))
        actions (for [[id e] (profile/episode-entries state)
                      :let  [by (gpodder/device-of (:device e))]
                      :when (and (>= (arrived id) (or since-ms 0))
                                 (or (nil? device) (= device by)))
                      :let  [[p m] (urls-of id)]
                      :when (and (url/web-url? p)
                                 (url/web-url? m)
                                 (or (nil? podcast) (= podcast p)))
                      :let  [a (gpodder/entry-action id e p m by)]
                      :when a]
                  a)]
    {:status 200
     :body   {"actions"   (vec actions)
              "timestamp" seconds}}))

(defn- apply-episode-actions
  "The answer that applies the episode actions of the JSON body in the
  call context `ctx` to its state.

  Only the actions later than what the state has change it, and the book
  keeps the URLs that each action named its episode by. An action without
  a time counts as made when it arrived, and so does an action that names
  a later time. An action with a time that doesn't read tells nothing of
  when it was made, so it's left out. An episode that the names don't
  know is named as podcast-clj names an episode that it knows nothing
  more of."
  [{:keys [state book names json at seconds] :as ctx}]
  (if-not (and (sequential? json) (every? map? json))
    {:status 400 :body "The body is not a list of episode actions"}
    (let [arrived (fn [t] (if (and t (< (inst-ms t) (inst-ms at))) t at))
          timed?  (fn [raw a] (or (:timestamp a) (nil? (get raw "timestamp"))))
          linked? #(and (url/web-url? (:podcast %))
                        (url/web-url? (:episode %)))
          actions (->> json
                       (keep (fn [raw]
                               (let [a (gpodder/read-action raw)]
                                 (when (timed? raw a) a))))
                       (filter linked?)
                       (map #(update % :timestamp arrived))
                       (sort-by (comp inst-ms :timestamp)))
          id-of   (fn [{:keys [podcast episode guid]}]
                    (or ((:episode-id-fn names) podcast episode guid)
                        [((:feed-guid-fn names) podcast) (or guid episode)]))
          step    #(gpodder/apply-action %1 (id-of %2) %2 "app")
          urls    (juxt id-of (juxt :podcast :episode))]
      {:status 200
       :body   {"timestamp" seconds "update_urls" []}
       :state  (reduce step state actions)
       :book   (update book :urls into (map urls) actions)})))

(def ^:no-doc routes
  "The paths that the server answers: the pattern, the names of its parts,
  and the function that answers each method."
  [[#"/api/2/auth/([^/]+)/login\.json" [:user]
    {:post login}]
   [#"/api/2/auth/([^/]+)/logout\.json" [:user]
    {:post logout}]
   [#"/api/2/devices/([^/]+)\.json" [:user]
    {:get devices}]
   [#"/api/2/devices/([^/]+)/([^/]+)\.json" [:user :device]
    {:post register :put register}]
   [#"/api/2/subscriptions/([^/]+)/([^/]+)\.json" [:user :device]
    {:get subscriptions :post change-subscriptions}]
   [#"/api/2/episodes/([^/]+)\.json" [:user]
    {:get episode-actions :post apply-episode-actions}]])

(defn- route
  "The decoded :parts of `path` and the :methods that answer it, from
  routes. It's nil for a path that the server doesn't answer."
  [path]
  (some (fn [[pattern names methods]]
          (when-let [[_ & parts] (re-matches pattern (str path))]
            {:parts   (zipmap names (map url/percent-decode parts))
             :methods methods}))
        routes))

(defn ^:no-doc put!
  "Put the state `s`, made from the state `from` of the atom `state`, in
  the atom. When the app changed the atom in between, `s` is merged with
  what the atom holds, so that nothing is lost."
  [state from s]
  (swap! state #(if (identical? % from) s (profile/merge s %))))

(defn ^:no-doc ring
  "The Ring response of `status` with `body` and the headers `more`. A
  body that isn't a string goes out as JSON."
  ([status body]
   (ring status body nil))
  ([status body more]
   (let [type (if (string? body)
                "text/plain; charset=utf-8"
                "application/json; charset=utf-8")]
     {:status  status
      :headers (merge {"Content-Type" type} more)
      :body    (if (string? body) body (json/write-str body))})))

(defn- asker
  "Who sends a request with `headers` at `at` to the server `env`, as a map
  of :who and :session.

  The user is that of the session that its cookie names, or else that of
  its Basic credentials, when the server lets them in."
  [{:keys [book authorized-pred] :as env} headers at]
  (let [session (some->> (get headers "cookie")
                         (re-find #"(?:^|;)\s*sessionid=([^;\s]+)")
                         second)
        basic   (fn []
                  (when-let [{:keys [username password]}
                             (http/credentials (get headers "authorization"))]
                    (when (and username
                               (authorized-pred username
                                                (secret/reveal password)))
                      username)))]
    {:session session
     :who     (or (:user (get (live (:sessions @book) at) session))
                  (basic))}))

(defn- call
  "The context of a call of routes for `request`, which came at `at`, over
  the `state` of the server `env`.

  It has the state, the book, the names, the time, the parameters and the
  JSON of the body."
  [{:keys [book max-bytes] :as env} request state at]
  (let [params (url/query-params (:query-string request))]
    {:state    state
     :book     @book
     :names    (gpodder/namer state env)
     :at       at
     :seconds  (quot (inst-ms at) 1000)
     :params   params
     :since-ms (some->> (get params "since")
                        (re-matches #"\d{1,12}")
                        parse-long
                        (* 1000))
     :json     (some-> (http/body-text (:body request) max-bytes) json/parse)}))

(defn- answer!
  "Answer `request` from `who` at `at` with `answer`, a function of
  routes, on the server `env`, and give the Ring response.

  The call runs once the state is in step, and the state is brought in
  step again after the call changed it."
  [{:keys [state book settle!] :as env} request answer who at]
  (let [before (settle! at)
        result (answer (merge who (call env request before at)))]
    (some->> (:book result) (reset! book))
    (when-let [s (:state result)]
      (put! state before s)
      (settle! at))
    (ring (:status result) (:body result) (:headers result))))

(defn- respond
  "The Ring response of the server `env` to `request`: the call that its
  path and method name, answered for the user who logged in."
  [{:keys [now-fn] :as env} request]
  (let [lower                   (comp str/lower-case name)
        headers                 (update-keys (:headers request) lower)
        {:keys [parts methods]} (route (or (:path-info request) (:uri request)))
        answer                  (get methods (:request-method request))
        at                      (now-fn)
        {:keys [who] :as from}  (asker env headers at)]
    (cond
      (nil? methods)
      (ring 404 "Not Found")

      ;; the challenge that gPodder waits for before it sends its credentials
      (nil? who)
      (ring 401 "Unauthorized"
            {"WWW-Authenticate" "Basic realm=\"podcast-clj\""})

      (not= (str/lower-case who) (str/lower-case (:user parts)))
      (ring 400 "The user in the path is not the one who logged in")

      (nil? answer)
      (ring 405 "Method Not Allowed")

      :else
      (answer! env request answer (merge parts from) at))))

(defn- arrived
  "The `index` of when each entry of the subscriptions and episodes of
  `state` arrived, with the entries whose stamp changed noted as arrived
  at `at-ms`.

  The index maps [collection key] to [stamp arrived], in milliseconds."
  [index state at-ms]
  (let [collections [[:subscriptions (:subscriptions state)]
                     [:episodes (profile/episode-entries state)]]
        stamped     (for [[k entries] collections
                          [id e]      entries]
                      [k id e])
        note        (fn [index [k id e]]
                      (let [stamp (some-> (:updated-at e) inst-ms)]
                        (cond-> index
                          (not= stamp (first (get index [k id])))
                          (assoc [k id] [stamp at-ms]))))]
    (reduce note index stamped)))

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

  A request has Basic credentials or the session of a login, and the user
  in the path must be the one who logged in. A session lasts a day. The
  calls run one at a time, as sync steps must, so :sync can be:

      (let [cursors (atom nil)]
        (fn [state]
          (let [result (sync/sync! state [backend] {:cursors @cursors})]
            (reset! cursors (:cursors result))
            (:state result))))

  A :sync gives a state, not a promise of one, so in ClojureScript, sync
  in the app and merge the result into the atom. The server merges what
  it writes with what the app put in the atom in between.

  For a site with many listeners, make a handler for each, with a state,
  an :authorized-pred and a :sync of its own, and pass each request to the
  handler of the user in its path. Keep each handler while the site runs,
  since it holds the sessions and the times that the changes arrived."
  [{:keys [state authorized-pred] sync! :sync :as opts}]
  (when-not (and (some? state) (fn? authorized-pred))
    (throw (ex-info (str "A handler needs the :state and the function "
                         ":authorized-pred")
                    {})))
  ;; TODO: keep the arrival times, the sessions and the devices in the store,
  ;; so that they outlive a restart
  (let [book    (atom {:index {} :sessions {} :devices {} :urls {}})
        settle! (fn [at]
                  (when sync!
                    (let [from @state]
                      (put! state from (sync! from))))
                  (let [settled @state]
                    (swap! book update :index arrived settled (inst-ms at))
                    settled))
        env     (merge {:now-fn    time/now
                        :max-bytes (* 10 1024 1024)}
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
      (one #(respond env request)))))

(comment
  (route "/api/2/subscriptions/ann/phone.json")
  (http/credentials "Basic YW5uOnNlY3JldA==")
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
