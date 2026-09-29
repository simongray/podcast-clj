(ns dk.simongray.podcast.app.sync.gpodder.server
  "The gpodder API served over a player state, so that the apps of that
  API sync with a server of the listener. Such apps are AntennaPod, Kasts
  and gPodder. `handler` is a Ring handler for the paths of gpodder.net
  under /api/2.

  The server is a device of podcast-clj. Its state holds what the apps
  send, and the device of the app writes each entry. A `:step` that the
  app gives keeps that state in step with the other devices of the
  listener.

  One listener has one list of subscriptions, as the GPodder Sync app for
  Nextcloud keeps it. A change counts from the time that it arrived here,
  so that an action that a device sends late still reaches the other
  devices."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync.gpodder :as gpodder]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.util Base64]
                   [java.util.concurrent.locks ReentrantLock])))

(def ^:no-doc session-seconds
  "How long a session lasts after its login: a day. A client of podcast-clj
  logs in at each step."
  86400)

(defn- new-session
  "The id of a new session, which nobody can guess."
  []
  #?(:clj  (str (random-uuid))
     ;; random-uuid asks Math.random there
     :cljs (if (exists? js/crypto) (.randomUUID js/crypto) (str (random-uuid)))))

(defn ^:no-doc live
  "The `sessions` that last at the instant `at`, by id."
  [sessions at]
  (into {} (filter (fn [[_ {:keys [until]}]] (< (inst-ms at) until))) sessions))

(defn- login
  "A session for the user `who`, set as a cookie. It is the `session` of the
  request when that lasts, so that a client that logs in at each step keeps
  one session, and else a new one. The sessions that ended leave the
  `book`."
  [{:keys [book who session at]}]
  (let [lasting (live (:sessions book) at)
        id      (if (= who (:user (get lasting session))) session (new-session))
        until   (+ (inst-ms at) (* 1000 session-seconds))]
    {:status  200
     :body    ""
     :headers {"Set-Cookie" (str "sessionid=" id "; Path=/; HttpOnly")}
     :book    (assoc book :sessions (assoc lasting id {:user who :until until}))}))

(defn- logout
  "The end of the `session` of the request."
  [{:keys [book session]}]
  {:status 200 :body "" :book (update book :sessions dissoc session)})

(defn- devices
  "The devices that registered in the `book`, each with the listener's
  number of subscriptions in `state`, since they share one list."
  [{:keys [book state]}]
  (let [n (count (player/subscriptions state))]
    {:status 200
     :body   (vec (for [[id {:keys [caption type]}] (sort-by key (:devices book))]
                    {"id" id "caption" (or caption id) "type" (or type "other") "subscriptions" n}))}))

(defn- register
  "The `device` registered in the `book` with the caption and the type that
  the `json` body gives."
  [{:keys [book device json]}]
  (let [{:strs [caption type]} (when (map? json) json)]
    (if (and type (not (#{"desktop" "laptop" "mobile" "server" "tablet" "other"} type)))
      {:status 400 :body "Unknown device type"}
      {:status 200
       :body   ""
       :book   (update-in book [:devices device] merge
                          (cond-> {} (string? caption) (assoc :caption caption) type (assoc :type type)))})))

(defn- subscriptions
  "The feeds added to and removed from the subscriptions of `state` since
  `since`, by URL and by the time they arrived in the `book`.

  A device that has seen nothing yet gets no removed ones, and the `device`
  counts as registered once it asks. A state can hold one URL under two
  guids. When one of them is live, the URL is not removed."
  [{:keys [state book device since seconds]}]
  (if-not since
    {:status 400 :body "The parameter since is missing or not a number"}
    (let [public #(first (http/split-credentials (:url %)))
          known  (filter (comp http/web-url? :url val) (:subscriptions state))
          urls   (fn [pred]
                   (distinct (for [[g e] known
                                   :when (and (pred e)
                                              (>= (second (get-in book [:index [:subscriptions g]] [0 0])) since))]
                               (public e))))
          live?  (into #{} (comp (map val) (remove :removed?) (map (comp player/normal-url public))) known)
          gone   (remove (comp live? player/normal-url) (urls :removed?))]
      {:status 200
       :body   {"add"       (vec (urls (complement :removed?)))
                "remove"    (if (zero? since) [] (vec gone))
                "timestamp" seconds}
       :book   (update-in book [:devices device] #(or % {}))})))

(defn- change-subscriptions
  "`state` with the changes of the `json` body, `{\"add\" [url …] \"remove\"
  [url …]}`, as the `device` made them at `at`."
  [{:keys [state book names device json at seconds]}]
  (let [{:strs [add remove]} (when (map? json) json)
        urls? #(or (nil? %) (and (sequential? %) (every? string? %)))]
    (cond
      (not (and (map? json) (urls? add) (urls? remove)))
      {:status 400 :body "The body is not {\"add\": [url …], \"remove\": [url …]}"}

      (some (set add) remove)
      {:status 400 :body "The same URL cannot be added and removed at the same time"}

      :else
      {:status 200
       :body   {"timestamp" seconds "update_urls" []}
       :state  (gpodder/apply-subscriptions state {:add add :remove remove} names device at)
       :book   (update-in book [:devices device] #(or % {}))})))

(defn- episode-actions
  "The episode actions that tell what changed in the episodes of `state`
  since `since`, by the time it arrived in the `book`.

  Each carries the URLs that its episode arrived with, else those that
  `names` give, and the `params` can ask for one podcast and one device."
  [{:keys [state book names since params seconds]}]
  (let [{:strs [podcast device]} params]
    {:status 200
     :body   {"actions"   (vec (for [[[g _ :as id] e] (:episodes state)
                                     :let  [by (gpodder/device-of (:device e))]
                                     :when (and (>= (second (get-in book [:index [:episodes id]] [0 0])) (or since 0))
                                                (or (nil? device) (= device by)))
                                     :let  [[p m] (get-in book [:urls id])
                                            p     (or p (some-> ((:feed-url names) g) http/split-credentials first))
                                            m     (or m ((:episode-url names) id))]
                                     :when (and (http/web-url? p) (http/web-url? m) (or (nil? podcast) (= podcast p)))
                                     :let  [a (gpodder/entry-action id e p m by)]
                                     :when a]
                                 a))
              "timestamp" seconds}}))

(defn- apply-episode-actions
  "`state` with the episode actions of the `json` body applied, those later
  than what it holds, and the `book` with the URLs each named its episode by.

  An action without a time counts as made at `at`, when it arrived, and so
  does an action that names a later time. An action with a time that does
  not read tells nothing of when it was made, so it is left out. An episode
  that `names` do not know is named as podcast-clj names one it knows
  nothing more of."
  [{:keys [state book names json at seconds]}]
  (if-not (and (sequential? json) (every? map? json))
    {:status 400 :body "The body is not a list of episode actions"}
    (let [arrived (fn [t] (if (and t (< (inst-ms t) (inst-ms at))) t at))
          timed?  (fn [raw a] (or (:timestamp a) (nil? (get raw "timestamp"))))
          actions (->> json
                       (keep (fn [raw]
                               (let [a (gpodder/read-action raw)]
                                 (when (timed? raw a) a))))
                       (filter #(and (http/web-url? (:podcast %)) (http/web-url? (:episode %))))
                       (map #(update % :timestamp arrived))
                       (sort-by (comp inst-ms :timestamp)))
          id-of   (fn [{:keys [podcast episode guid]}]
                    (or ((:episode-id names) podcast episode guid)
                        [((:feed-guid names) podcast) (or guid episode)]))]
      {:status 200
       :body   {"timestamp" seconds "update_urls" []}
       :state  (reduce #(gpodder/apply-action %1 (id-of %2) %2 "app") state actions)
       :book   (update book :urls into (map (juxt id-of (juxt :podcast :episode))) actions)})))

(def ^:no-doc routes
  "The paths the server answers: the pattern, the names of its parts, and
  what answers each method."
  [[#"/api/2/auth/([^/]+)/login\.json"            [:user]         {:post login}]
   [#"/api/2/auth/([^/]+)/logout\.json"           [:user]         {:post logout}]
   [#"/api/2/devices/([^/]+)\.json"               [:user]         {:get devices}]
   [#"/api/2/devices/([^/]+)/([^/]+)\.json"       [:user :device] {:post register :put register}]
   [#"/api/2/subscriptions/([^/]+)/([^/]+)\.json" [:user :device] {:get subscriptions :post change-subscriptions}]
   [#"/api/2/episodes/([^/]+)\.json"              [:user]         {:get episode-actions :post apply-episode-actions}]])

(defn- route
  "The `:parts` of the path `path`, decoded, and the `:methods` that answer
  it, by `routes`; nil for a path the server does not answer."
  [path]
  (some (fn [[pattern names methods]]
          (when-let [[_ & parts] (re-matches pattern (str path))]
            {:parts (zipmap names (map http/percent-decode parts)) :methods methods}))
        routes))

(defn- basic
  "The user name and the password of the Basic `authorization`, `[user
  password]`; nil when it names none."
  [authorization]
  (when-let [[_ encoded] (some->> authorization (re-matches #"(?i)\s*basic\s+([A-Za-z0-9+/=]+)\s*"))]
    (let [decoded (try
                    #?(:clj  (String. (.decode (Base64/getDecoder) ^String encoded) StandardCharsets/UTF_8)
                       :cljs (js/decodeURIComponent (js/escape (js/atob encoded))))
                    (catch #?(:clj Exception :cljs :default) _
                      nil))]
      (when-let [i (some-> decoded (str/index-of ":"))]
        [(subs decoded 0 i) (subs decoded (inc i))]))))

(defn ^:no-doc put!
  "Put the state `s`, which was made from the state `from` of the atom
  `state`, in the atom. When the app changed the atom in between, `s` is
  merged with what the atom holds, so that nothing is lost."
  [state from s]
  (swap! state #(if (identical? % from) s (player/merge s %))))

(defn ^:no-doc ring
  "The Ring response of `status` with `body` and the headers `more`. A
  body that is no text goes out as JSON."
  ([status body]
   (ring status body nil))
  ([status body more]
   (let [type (if (string? body) "text/plain; charset=utf-8" "application/json; charset=utf-8")]
     {:status  status
      :headers (merge {"Content-Type" type} more)
      :body    (if (string? body) body (json/write-str body))})))

(defn- asker
  "Who sends a request with `headers` at `at`, as `{:who :session}`: the
  user of the session that its cookie names, or else the user of its
  Basic credentials, when the server `env` lets them in."
  [{:keys [book authorized?]} headers at]
  (let [session (some->> (get headers "cookie") (re-find #"(?:^|;)\s*sessionid=([^;\s]+)") second)]
    {:session session
     :who     (or (:user (get (live (:sessions @book) at) session))
                  (when-let [[user password] (basic (get headers "authorization"))]
                    (when (authorized? user password) user)))}))

(defn- call
  "What a call of `routes` takes of `request`, which came at `at`, over
  the `state` of the server `env`: the state, the book and the names, the
  time, the parameters, and the JSON of the body."
  [{:keys [book max-bytes] :as env} request state at]
  (let [params (http/query-params (:query-string request))]
    {:state   state
     :book    @book
     :names   (gpodder/namer env state)
     :at      at
     :seconds (quot (inst-ms at) 1000)
     :params  params
     :since   (some->> (get params "since") (re-matches #"\d{1,12}") parse-long (* 1000))
     :json    (some-> (http/body-text (:body request) max-bytes) json/parse)}))

(defn- answered!
  "The Ring response of `answer`, a call of `routes`, to `request`, which
  `who` sent at `at`. The call runs once the state is in step, and the
  state is in step again after the call changed it."
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
  [{:keys [now] :as env} request]
  (let [headers                 (update-keys (:headers request) (comp str/lower-case name))
        {:keys [parts methods]} (route (or (:path-info request) (:uri request)))
        answer                  (get methods (:request-method request))
        at                      (now)
        {:keys [who] :as from}  (asker env headers at)]
    (cond
      (nil? methods) (ring 404 "Not Found")
      ;; the challenge that gPodder waits for before it sends its credentials
      (nil? who)     (ring 401 "Unauthorized" {"WWW-Authenticate" "Basic realm=\"podcast-clj\""})
      (not= (str/lower-case who) (str/lower-case (:user parts)))
      (ring 400 "The user in the path is not the one who logged in")
      (nil? answer)  (ring 405 "Method Not Allowed")
      :else          (answered! env request answer (merge parts from) at))))

(defn- arrived
  "The `index` of when each entry of the subscriptions and the episodes of
  `state` arrived, `[stamp arrived]` in milliseconds by `[collection key]`,
  with the entries whose stamp changed noted as arrived at `now`."
  [index state now]
  (reduce (fn [index [k id e]]
            (let [stamp (some-> (:updated-at e) inst-ms)]
              (cond-> index
                (not= stamp (first (get index [k id]))) (assoc [k id] [stamp now]))))
          index
          (for [k      [:subscriptions :episodes]
                [id e] (get state k)]
            [k id e])))

(defn handler
  "A Ring handler that serves the gpodder API over the player state in the
  atom `:state`, as `opts` say:

      {:state       (atom (player/state \"gpodder-server\"))
       :authorized? (fn [user password] …)  ; whether to let them in
       :step        (fn [state] state')      ; keeps the state in step with the listener's
                                              ; devices, before and after each call
       :identities  #(gpodder/identities @feeds)  ; as for gpodder/backend, or each by its key:
       :feed-guid f :feed-url f :episode-id f :episode-url f
       :now         (fn [] …)                ; the time, the clock by default
       :max-bytes   10485760}                ; the most a request may send

  A request carries Basic credentials or the session of a login, and the
  user in the path must be the one who logged in. A session lasts a day.
  The calls run one at a time, as sync steps must, so `:step` can be:

      (let [ctx (atom nil)]
        (fn [state]
          (let [step (sync/sync! state [backend] (java.util.Date.) {:ctx @ctx})]
            (reset! ctx (:ctx step))
            (:state step))))

  A `:step` gives a state, not a promise of one. In ClojureScript, where
  `sync!` gives a promise, the app syncs on its own and merges the result
  into the atom. The server merges what it writes with what the app put in
  the atom in between."
  [{:keys [state authorized? step] :as opts}]
  {:pre [(some? state) (fn? authorized?)]}
  ;; TODO: keep the arrival times, the sessions and the devices in the store,
  ;; so that they outlive a restart
  (let [book (atom {:index {} :sessions {} :devices {} :urls {}})
        env  (merge {:now       #?(:clj #(java.util.Date.) :cljs #(js/Date.))
                     :max-bytes (* 10 1024 1024)}
                    opts
                    {:book    book
                     :settle! (fn [at]
                                (when step
                                  (let [from @state]
                                    (put! state from (step from))))
                                (let [settled @state]
                                  (swap! book update :index arrived settled (inst-ms at))
                                  settled))})
        one  #?(:clj  (let [lock (ReentrantLock.)]
                        (fn [f] (.lock lock) (try (f) (finally (.unlock lock)))))
                :cljs (fn [f] (f)))]
    (fn [request]
      (one #(respond env request)))))

(comment
  (route "/api/2/subscriptions/ann/phone.json")
  (basic "Basic YW5uOnNlY3JldA==")
  (http/query-params "since=1727000000&podcast=https%3A%2F%2Fexample.org%2Ffeed.xml")

  ;; a server over a state with one subscription, and a request of an app
  (def serve
    (handler {:state       (atom (player/subscribe (player/state "gpodder-server")
                                                   "feed-a"
                                                   {:url "https://example.org/feed.xml" :title "A show"}
                                                   #inst "2026-09-29T12:00:00Z"))
              :authorized? (fn [user password] (= ["ann" "secret"] [user password]))}))

  (serve {:request-method :get
          :uri            "/api/2/subscriptions/ann/phone.json"
          :query-string   "since=0"
          :headers        {"authorization" "Basic YW5uOnNlY3JldA=="}})

  ;; no credentials: the challenge that gPodder waits for
  (serve {:request-method :get :uri "/api/2/devices/ann.json" :headers {}})
  #_.)
