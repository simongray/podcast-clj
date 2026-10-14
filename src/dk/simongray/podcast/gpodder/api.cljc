(ns dk.simongray.podcast.gpodder.api
  "The gpodder API as data: the request map of each call, and what the
  answer to it says, for any HTTP client to send and receive.

  The paths are those of gpodder.net, which oPodSync and other servers
  speak too, and those of the GPodder Sync app for Nextcloud, or a map of
  paths of a server's own."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]))

(def calls
  "The calls that a backend makes to a server."
  #{:login :register :subscriptions :update-subscriptions :episodes
    :update-episodes})

(def apis
  "The paths of the calls, by the API that a server speaks.

  In a path, {user} is the user name, and {device} is the id of the
  device. The API :gpodder is that of gpodder.net, which oPodSync and
  other servers speak too, and :nextcloud is that of the GPodder Sync app
  for Nextcloud. That app has no login and keeps no devices, so it has no
  path for :login or :register. The :api of the options is a name here,
  or a map of paths of this shape for a server with paths of its own."
  {:gpodder   {:login                "/api/2/auth/{user}/login.json"
               :register             "/api/2/devices/{user}/{device}.json"
               :subscriptions        "/api/2/subscriptions/{user}/{device}.json"
               :update-subscriptions "/api/2/subscriptions/{user}/{device}.json"
               :episodes             "/api/2/episodes/{user}.json"
               :update-episodes      "/api/2/episodes/{user}.json"}
   :nextcloud (let [app "/index.php/apps/gpoddersync"]
                {:subscriptions        (str app "/subscriptions")
                 :update-subscriptions (str app "/subscription_change/create")
                 :episodes             (str app "/episode_action")
                 :update-episodes      (str app "/episode_action/create")})})

(defn ^:no-doc paths
  "The paths of the calls of `api`, a name in apis or a map of paths. It
  throws for a name that apis doesn't have."
  [api]
  (or (if (map? api) api (get apis api))
      (throw (ex-info (str "An API that podcast-clj does not know: " api)
                      {:api api}))))

(defn- path
  "The path of `call` in the `api`, for the account `user` and the
  `device`."
  [api call user device]
  (let [template (or (get (paths api) call)
                     (throw (ex-info (str "The API has no call " call)
                                     {:api api :call call})))]
    (str/replace template
                 #"\{user\}|\{device\}"
                 #(url/percent-encode (case %
                                        "{user}"   user
                                        "{device}" device)))))

(defn request
  "The request map for `call`, one of calls, to the server at `root`, with
  the `opts` below:

  - :api, :gpodder by default, :nextcloud, or a map of paths as in apis
  - :credentials, a :username and a :password, or a function that returns
    them
  - :device, the device's id on the server
  - :since, the seconds since the epoch, for :subscriptions and :episodes
  - :body, the data to send as JSON
  - :session, the session of a :login

  Every call has the credentials as Basic authentication, and the session
  when there is one, since some servers want the one and others the other.
  The password and the session can be hidden texts of
  dk.simongray.wary-fetch.secret, and the request has them in its
  headers as hidden texts."
  [call root {:keys [api credentials device since body]
              :or   {api :gpodder}
              :as   opts}]
  (let [creds   (if (fn? credentials) (credentials) credentials)
        auth    (http/authorization creds)
        session (secret/reveal (:session opts))
        get?    (contains? #{:subscriptions :episodes} call)
        cookie  (when session
                  (secret/hide (str "sessionid=" session)))]
    (cond-> {:method  (if get? :get :post)
             :url     (str (url/without-trailing-slashes root)
                           (path api call (:username creds) device)
                           (when get? (str "?since=" (or since 0))))
             :headers (cond-> {"Accept" "application/json"}
                        auth         (assoc "Authorization" auth)
                        cookie       (assoc "Cookie" cookie)
                        (some? body) (assoc "Content-Type" "application/json"))}
      (some? body) (assoc :body (json/write-str body)))))

(defn- text
  "The string `v` trimmed, or nil for a blank one or anything else."
  [v]
  (when (string? v)
    (not-empty (str/trim v))))

(defn- seconds
  "The seconds `v`, or nil for none, for the negative number that a client
  writes for an unknown position, and for a number that no episode
  reaches."
  [v]
  (when (and (number? v) (<= 0 v) (< v 1e9))
    v))

(defn ^:no-doc epoch
  "The seconds since the epoch of the timestamp `v` of an answer, a whole
  number, or from some servers a string of digits. It's nil for anything
  else, and for a time that a JavaScript date can't hold."
  [v]
  (let [n (if (string? v)
            (some->> v str/trim (re-matches #"\d{1,13}") parse-long)
            (json/whole v))]
    (when (and n (<= 0 n) (<= n 8640000000000))
      n)))

(defn- instant
  "The instant of the action time `v`, in ISO 8601 and UTC without a zone,
  or seconds since the epoch."
  [v]
  (if (string? v)
    (time/parse-iso8601 v)
    (some-> (epoch v) (* 1000) time/->inst)))

(defn- listed
  [v]
  (if (sequential? v) v []))

(defn ^:no-doc read-action
  "The episode action `m`, as JSON reads it, or nil when it isn't a map.

  The keys are keywords, the action is in lower case, and the time is an
  instant. A field that doesn't read is nil."
  [m]
  (when (map? m)
    (let [lower  (comp #(some-> % str/lower-case) text)
          fields {"podcast"   text
                  "episode"   text
                  "guid"      text
                  "device"    text
                  "action"    lower
                  "timestamp" instant
                  "started"   seconds
                  "position"  seconds
                  "total"     seconds}]
      (into {} (for [[k f] fields]
                 [(keyword k) (f (get m k))])))))

(defn- session
  "The session id that the Set-Cookie of the answer `headers` sets."
  [headers]
  (let [v    (get headers "set-cookie")
        form #"(?:^|[;,]\s*)sessionid=([^;,\s]+)"]
    (some #(second (re-find form %))
          (if (string? v) [v] v))))

(defn response
  "What the `answer` to `call`, a map of :status, :headers and :body, says:

  - :login, the session id that the server set, or nil
  - :register, nil
  - :subscriptions, the URLs to :add and to :remove, and the :timestamp
  - :episodes, the :actions and the :timestamp
  - :update-subscriptions and :update-episodes, the :timestamp, and the
    :update-urls as pairs of the URL sent and the URL rewritten

  An action has keyword keys, the action in lower case and the time as an
  instant. A rewritten URL is nil when the server refused the URL that was
  sent. It throws ::failed with the :status when the status isn't a
  success, or when the answer isn't the JSON object that the call
  expects."
  [call {:keys [status headers body] :as answer}]
  (let [fail     (fn [why]
                   (throw (ex-info (str "The server answered " status " to "
                                        (name call) why)
                                   {:type ::failed :call call :status status})))
        data     (fn []
                   (let [v (json/parse (str body))]
                     (if (map? v)
                       v
                       (fail ", not with a JSON object"))))
        success? (and (integer? status) (<= 200 status) (< status 300))
        urls     (fn [m k]
                   (filterv url/web-url? (listed (get m k))))]
    (cond
      (= 401 status)
      (fail ": the credentials were refused")

      (not success?)
      (fail "")

      :else
      (case call
        :login
        (session headers)

        :register
        nil

        :subscriptions
        (let [m (data)]
          {:add       (urls m "add")
           :remove    (urls m "remove")
           :timestamp (epoch (get m "timestamp"))})

        :episodes
        (let [m (data)]
          {:actions   (into [] (keep read-action) (listed (get m "actions")))
           :timestamp (epoch (get m "timestamp"))})

        (:update-subscriptions :update-episodes)
        (let [m (if (str/blank? body) {} (data))]
          {:timestamp   (epoch (get m "timestamp"))
           :update-urls (vec (for [pair  (listed (get m "update_urls"))
                                   :let  [[sent new] (listed pair)]
                                   :when (string? sent)]
                               [sent (when (url/web-url? new) new)]))})))))

(comment
  (request :subscriptions "https://gpodder.net"
           {:credentials {:username "ann" :password "…"}
            :device      "phone"
            :since       1727000000})

  (response :subscriptions
            {:status 200
             :body   (str "{\"add\": [\"https://example.org/feed.xml\"], "
                          "\"remove\": [], \"timestamp\": 1727000100}")})

  ;; an episode action, as a server sends it
  (read-action {"podcast"   "https://example.org/feed.xml"
                "episode"   "https://example.org/1.mp3"
                "action"    "PLAY"
                "timestamp" "2026-09-29T12:00:00"
                "position"  120
                "total"     1800})
  #_.)
