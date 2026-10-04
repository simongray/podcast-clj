(ns dk.simongray.podcast.sync.gpodder
  "The gpodder API as a sync backend, for the subscriptions and episode
  actions that a gpodder server keeps.

  Such servers are gpodder.net, the GPodder Sync app for Nextcloud,
  oPodSync and PinePods. They keep the data of apps such as AntennaPod,
  Kasts and gPodder. The design is in doc/sync.md.

  The function backend is the one call that an app makes. The API names a
  show by its feed URL, and an episode by the URL of its media. Since
  podcast-clj names them by guid, identities gives the ways to name them
  from the app's parsed feeds. The function request builds each call as a
  request map, and response reads the answer, so that the tests run on
  recorded answers."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.time :as time]))

(def calls
  "The calls that a backend makes to a server, whose paths are in apis."
  #{:login :register :subscriptions :update-subscriptions :episodes :update-episodes})

(def apis
  "The paths of the calls, by the API that a server speaks.

  In a path, {user} is the user name, and {device} is the id of the
  device. The API :gpodder is that of gpodder.net, which oPodSync and
  other servers speak too, and :nextcloud is that of the GPodder Sync app
  for Nextcloud. That app has no login and keeps no devices, so it has no
  path for :login or :register. The :api in the options of request and
  backend is a name here. For a server with paths of its own, it's a map
  of paths of this shape."
  {:gpodder   {:login                "/api/2/auth/{user}/login.json"
               :register             "/api/2/devices/{user}/{device}.json"
               :subscriptions        "/api/2/subscriptions/{user}/{device}.json"
               :update-subscriptions "/api/2/subscriptions/{user}/{device}.json"
               :episodes             "/api/2/episodes/{user}.json"
               :update-episodes      "/api/2/episodes/{user}.json"}
   :nextcloud {:subscriptions        "/index.php/apps/gpoddersync/subscriptions"
               :update-subscriptions "/index.php/apps/gpoddersync/subscription_change/create"
               :episodes             "/index.php/apps/gpoddersync/episode_action"
               :update-episodes      "/index.php/apps/gpoddersync/episode_action/create"}})

(def default-limits
  "The limits of backend when its options don't set them.

  A pull asks for one more page of episode actions when a page holds
  :page-size actions, and it reads :max-pages pages at most. A push sends
  :batch-size episode actions in each request. The page and the batch are
  those of gpodder.net, and another server can have others. The cursor
  keeps the last :max-pending actions that name a feed that the app
  doesn't know."
  {:page-size   1000
   :max-pages   10
   :batch-size  100
   :max-pending 500})

(defn ^:no-doc paths
  "The paths of the calls of `api`, a name in apis or a map of paths. It
  throws for a name that apis doesn't have."
  [api]
  (or (if (map? api) api (get apis api))
      (throw (ex-info (str "An API that podcast-clj does not know: " api) {:api api}))))

(defn- path
  "The path of `call` in the `api`, for the account `user` and the
  `device`."
  [api call user device]
  (let [template (or (get (paths api) call)
                     (throw (ex-info (str "The API has no call " call) {:api api :call call})))]
    (str/replace template
                 #"\{user\}|\{device\}"
                 #(http/percent-encode (case % "{user}" user "{device}" device)))))

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
  dk.simongray.podcast.util.secret, and the request has them in its
  headers as hidden texts."
  [call root {:keys [api credentials device since body] :or {api :gpodder} :as opts}]
  (let [creds   (if (fn? credentials) (credentials) credentials)
        auth    (http/authorization creds)
        session (secret/reveal (:session opts))
        get?    (contains? #{:subscriptions :episodes} call)]
    (cond-> {:method  (if get? :get :post)
             :url     (str (str/replace root #"(^|[^/])/+$" "$1")
                           (path api call (:username creds) device)
                           (when get? (str "?since=" (or since 0))))
             :headers (cond-> {"User-Agent" http/user-agent "Accept" "application/json"}
                        auth         (assoc "Authorization" auth)
                        session      (assoc "Cookie" (secret/hide (str "sessionid=" session)))
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
  or seconds since the epoch as epoch reads them."
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
    (into {} (for [[k f] {"podcast" text "episode" text "guid" text "device" text
                          "action"  (comp #(some-> % str/lower-case) text)
                          "timestamp" instant "started" seconds "position" seconds "total" seconds}]
               [(keyword k) (f (get m k))]))))

(defn- session
  "The session id that the Set-Cookie of the answer `headers` sets."
  [headers]
  (let [v (get headers "set-cookie")]
    (some #(second (re-find #"(?:^|[;,]\s*)sessionid=([^;,\s]+)" %)) (if (string? v) [v] v))))

(defn response
  "What the `answer` to `call`, a map of :status, :headers and :body, says:

  - :login, the session id that the server set, or nil
  - :register, nil
  - :subscriptions, the URLs to :add and to :remove, and the :timestamp
  - :episodes, the :actions and the :timestamp
  - :update-subscriptions and :update-episodes, the :timestamp, and the
    :update-urls as pairs of the URL sent and the URL rewritten

  An action is as read-action reads it. A rewritten URL is nil when the
  server refused the URL that was sent. It throws ::failed with the
  :status when the status isn't a success, or when the answer isn't the
  JSON object that the call expects."
  [call {:keys [status headers body] :as answer}]
  (let [fail (fn [why]
               (throw (ex-info (str "The server answered " status " to " (name call) why)
                               {:type ::failed :call call :status status})))
        data (fn []
               (let [v (json/parse (str body))]
                 (if (map? v) v (fail ", not with a JSON object"))))]
    (cond
      (= 401 status)                                               (fail ": the credentials were refused")
      (not (and (integer? status) (<= 200 status) (< status 300))) (fail "")
      :else
      (case call
        :login         (session headers)
        :register      nil
        :subscriptions (let [m (data)]
                         {:add       (filterv http/web-url? (listed (get m "add")))
                          :remove    (filterv http/web-url? (listed (get m "remove")))
                          :timestamp (epoch (get m "timestamp"))})
        :episodes      (let [m (data)]
                         {:actions   (into [] (keep read-action) (listed (get m "actions")))
                          :timestamp (epoch (get m "timestamp"))})
        (:update-subscriptions
         :update-episodes) (let [m (if (str/blank? body) {} (data))]
                             {:timestamp   (epoch (get m "timestamp"))
                              :update-urls (vec (for [pair  (listed (get m "update_urls"))
                                                      :let  [[sent new] (listed pair)]
                                                      :when (string? sent)]
                                                  [sent (when (http/web-url? new) new)]))})))))

(defn- indexed
  "The `feed` at `url` with its guid, and the index of its items as a
  delay, made when a lookup first needs it.

  The index has the items by guid, and their guids by the URL of their
  media."
  [[url feed]]
  {:url   url
   :guid  (feed/feed-guid feed url)
   :index (delay
            (let [items (keep (fn [item] (when-let [g (feed/item-guid item)] [g item])) (:rss/items feed))]
              {:items    (into {} items)
               :by-media (into {} (for [[g item] items
                                        {media :url} (resolve/enclosures item)]
                                    [(profile/normal-url media) g]))}))})

(defn identities
  "The ways to name the shows and episodes of `feeds`, the app's parsed
  feeds by URL, as backend takes them under :identities:

      {:feed-guid   (fn [url])                         ; the guid of the feed
       :feed-url    (fn [feed-guid])                   ; the URL of the feed
       :episode-id  (fn [podcast-url media-url guid])  ; the episode id
       :episode-url (fn [[feed-guid item-guid]])}      ; the URL of its media

  Each function gives nil for a feed that isn't in `feeds`. An episode is
  found by its guid, since some servers keep the guid, or else by the URL
  of its media. When neither finds it, the episode gets the name that
  podcast-clj gives an episode that its feed leaves out. URLs match
  whatever their scheme, case and trailing slashes.

  The items of a feed are indexed when a lookup first names the feed, so
  keep the result while the app's feeds stay the same."
  [feeds]
  (let [known   (map indexed feeds)
        by-url  (into {} (map (juxt (comp profile/normal-url :url) identity)) known)
        by-guid (into {} (map (juxt :guid identity)) known)
        index   #(some-> % :index deref)]
    {:feed-guid   #(:guid (by-url (profile/normal-url %)))
     :feed-url    #(:url (by-guid %))
     :episode-id  (fn [podcast media guid]
                    (when-let [{g :guid :as known} (by-url (profile/normal-url podcast))]
                      (let [{:keys [items by-media]} (index known)]
                        [g (or (when (contains? items guid) guid) (by-media (profile/normal-url media)) guid media)])))
     :episode-url (fn [[feed-guid item-guid]]
                    (when-let [item (get (:items (index (by-guid feed-guid))) item-guid)]
                      (or (:url (:rss/enclosure item)) (:url (resolve/enclosure item)))))}))

(defn ^:no-doc namer
  "The ways to name shows and episodes for `state` that `opts` give, by
  their own keys or by :identities, with defaults for those they lack.

  A feed is named by the subscription of `state` with its URL, as
  dk.simongray.podcast.profile/by-url finds it. Without such a
  subscription, :feed-guid names the feed, or else its URL does.

  An episode is named by :episode-id. Without that option, the guid of its
  action names it, or else the URL of its media. With the option, an
  episode of a feed that the option doesn't know stays unnamed.

  The URL of the media of an episode is what :episode-url gives, or
  without it, the guid of the item when that's a URL."
  [state {:keys [identities] :as opts}]
  (let [{:keys [feed-guid feed-url episode-id episode-url]}
        (merge (when identities (identities)) (select-keys opts [:feed-guid :feed-url :episode-id :episode-url]))
        subscribed (comp (profile/by-url state) profile/normal-url)
        ;; a new feed that declares a guid of its own keeps the guid of its
        ;; URL until profile/rekey moves the subscription, which app.library
        ;; does when it fetches the feed
        guid-of    #(or (subscribed %) (when feed-guid (feed-guid %)) (guid/from-url %))]
    {:feed-guid   guid-of
     :feed-url    #(or (get-in state [:subscriptions % :url]) (when feed-url (feed-url %)))
     :episode-id  (fn [podcast media item]
                    (if episode-id
                      (some-> (episode-id podcast media item) (update 0 #(or (subscribed podcast) %)))
                      [(guid-of podcast) (or item media)]))
     :episode-url (fn [[_ item :as id]]
                    (if episode-url
                      (episode-url id)
                      (when (http/web-url? item) item)))}))

(defn ^:no-doc writer
  "The author of the entries in a state for what the `device` of a gpodder
  server did. No device of podcast-clj has such a name, so a push never
  sends the entries back."
  [device]
  (str "gpodder:" device))

(defn ^:no-doc device-of
  "The device of a gpodder server that wrote an entry as `author`, the
  inverse of writer. A device of podcast-clj is its own name."
  [author]
  (str/replace-first (str author) #"^gpodder:" ""))

(defn ^:no-doc apply-subscriptions
  "The `state` with the subscription `changes` that the device `author` made
  at `at`: the URLs to :add and to :remove, with their guids from `names`.

  A feed that's already as the change says is left alone. A server gives
  the URL of a show but no title. When `state` doesn't know the show, its
  entry is stamped at the start of time, so that the entry of a device
  that knows the show wins over it."
  [state {:keys [add remove] :as changes} names author at]
  (let [guid-of     (:feed-guid names)
        subscribe   (fn [s url]
                      (let [g     (guid-of url)
                            known (get-in s [:subscriptions g])
                            show  {:url url :title (:title known) :added-at at}
                            stamp (if known at (time/->inst 0))]
                        (cond-> s
                          (not (profile/subscribed? s g))
                          (crdt/as-device (writer author) #(profile/subscribe % g show stamp)))))
        unsubscribe (fn [s url]
                      (let [g (guid-of url)]
                        (cond-> s
                          (profile/subscribed? s g) (crdt/as-device (writer author) #(profile/unsubscribe % g at)))))]
    (as-> state s
      (reduce subscribe s add)
      (reduce unsubscribe s remove))))

(defn ^:no-doc apply-action
  "The `state` with the episode action `a` applied to the episode `id` at
  the time it names, by its own device or else by `author`.

  A \"play\" records a position as profile/progress does, and a \"new\" marks
  the episode as not played, as profile/mark-unplayed does. An action of
  another kind, or one no later than what `state` has of the episode,
  changes nothing, so that an action read back does no harm."
  [state id {:keys [action position total timestamp device] :as a} author]
  (let [stamp (some-> (profile/episode-entry state id) :updated-at inst-ms)
        act   (case action
                "play" (when (number? position)
                         #(profile/progress % id position (when (and (number? total) (pos? total)) total) timestamp))
                "new"  #(profile/mark-unplayed % id timestamp)
                nil)]
    (if (and act timestamp (< (or stamp 0) (inst-ms timestamp)))
      (crdt/as-device state (writer (or device author)) act)
      state)))

(defn- told-at
  "The instant of what the episode entry `e` tells, on the clock of the
  device that wrote it.

  It's the later of its last report and its mark as played, or else its
  stamp. The stamp isn't a time of the device: after a merge with a device
  whose clock is fast, each stamp is later than the clock of this device."
  [{:keys [reported-at played-at updated-at] :as e}]
  (or (some->> (filter inst? [reported-at played-at]) seq (apply max-key inst-ms))
      updated-at))

(defn ^:no-doc entry-action
  "The episode action that tells the entry `e` of the episode `id`, or nil.
  The `device` sends it with the feed URL `podcast`, the media URL `media`
  and `opts`.

  A played episode is a play to its end, as AntennaPod writes it. An
  episode marked as not played is a \"new\", except on the first push, when
  `opts` have :first?. Positions are whole seconds, and a played episode
  of an unknown duration tells nothing. The time of the action is that of
  the device, as told-at gives it."
  ([id e podcast media device]
   (entry-action id e podcast media device {}))
  ([id e podcast media device {:keys [first?] :as opts}]
   (let [{:keys [position duration played?]} e
         whole (fn [x] (when (and (number? x) (== x x) (not (infinite? x)) (not (neg? x))) (long x)))
         at    (whole position)
         total (let [d (whole duration)] (when (and d (pos? d)) d))
         act   (cond
                 (and played? total)                     {"action" "play" "started" total "position" total "total" total}
                 played?                                 nil
                 (and (false? played?) (contains? #{nil 0} at)) (when-not first? {"action" "new"})
                 (and at total)                          {"action" "play" "started" at "position" at "total" total}
                 at                                      {"action" "play" "position" at})]
     (when act
       ;; ISO 8601 to the second and without a zone, which every server reads
       (merge act {"podcast" podcast "episode" media "guid" (second id) "device" device
                   "timestamp" (subs (time/format-iso8601 (told-at e)) 0 19)})))))

(defn- ahead?
  "Whether the episode action `a` names a time that's more than
  sync/skew-tolerance after the instant `at`."
  [a at]
  (< sync/skew-tolerance (- (inst-ms (:timestamp a)) (inst-ms at))))

(defn- ahead-report
  "A diagnostic for each device whose episode `actions` name a time after
  the pull at `at`, with `author` as the device of an action that names
  none."
  [actions author at]
  (for [[device named] (group-by #(or (:device %) author) actions)
        :let [latest (apply max (map (comp inst-ms :timestamp) named))]]
    (diagnostics/describe {:id :sync/future-actions :level :warning :device device
                           :count (count named) :seconds (quot (- latest (inst-ms at)) 1000)})))

(defn- pulled
  "What a pull for `state` gives at `at`, from the subscription changes
  `subs` and the `episodes` actions since `cursor`, with the `opts` of the
  backend.

  They're applied to `state` as their devices made them, and the result
  is what changed, a partial state. The actions of episodes that the app
  doesn't know yet wait in the cursor, up to the last :max-pending. The
  `opts` give the ways to name, and the :name of the backend, which writes
  what names no device.

  An action that's ahead? of `at` changes nothing, and the report names
  its device. A change to the subscriptions counts as made at `at` at the
  latest, whatever time the server gives. An action that names no feed,
  media or readable time is left out, and the report counts such actions."
  [state cursor subs episodes {:keys [name max-pending] :as opts} at]
  (let [names   (namer state opts)
        local   #(get (:urls cursor) % %)
        told    (or (:timestamp subs) (quot (inst-ms at) 1000))
        ;; the API tells no time of a change to the subscriptions: one counts
        ;; as made when the pull before saw none, and at the first pull as
        ;; made then, so that the first sync joins two lists
        since   (time/->inst (min (inst-ms at) (* 1000 (or (:subscriptions cursor) told))))
        id-of   (fn [{:keys [podcast episode guid]}] ((:episode-id names) (local podcast) (local episode) guid))
        changed (apply-subscriptions state {:add (map local (:add subs)) :remove (map local (:remove subs))}
                                     names name since)
        ;; a server tells an action again in the day before the cursor
        told-of (distinct (concat (:pending cursor) (:actions episodes)))
        whole?  (every-pred :podcast :episode :timestamp)
        actions (sort-by (comp inst-ms :timestamp) (filter whole? told-of))
        [s unnamed] (reduce (fn [[s unnamed] a]
                              (if-let [id (id-of a)]
                                [(apply-action s id a name) unnamed]
                                [s (conj unnamed a)]))
                            [changed []]
                            (remove #(ahead? % at) actions))
        pending (vec (take-last max-pending unnamed))
        more    (- (count pending) (count (:pending cursor)))
        broken  (count (remove whole? told-of))
        counted (fn [d n] (diagnostics/describe (assoc d :count n)))]
    {:states   [(crdt/changes-since s state)]
     :partial? true
     :cursor   (cond-> (-> cursor
                           (dissoc :pending)
                           (assoc :subscriptions told :episodes (or (:timestamp episodes) (:episodes cursor))))
                 (seq pending) (assoc :pending pending))
     :report   (cond-> (vec (ahead-report (filter #(ahead? % at) actions) name at))
                 (pos? more)   (conj (counted {:id :sync/unknown-episodes :level :info} more))
                 (pos? broken) (conj (counted {:id :sync/malformed-actions :level :warning} broken)))}))

(defn- caller
  "A function of a call and its parameters that sends it to the server at
  `root` with `opts`, and reads the answer, in a future or a promise."
  [root {:keys [send timeout-seconds device caption type] :as opts}]
  (fn [call params]
    (async/then ((or send http/call!)
                 (cond-> (request call root (merge (select-keys opts [:api :credentials])
                                                   {:device device}
                                                   (when (= :register call) {:body {"caption" caption "type" type}})
                                                   params))
                   timeout-seconds (assoc :timeout-seconds timeout-seconds)))
                #(response call %))))

(defn- registered!
  "Register the device of `opts` through `call!` in the `session`, and give
  `cursor` with it, in a future or a promise.

  Nothing is sent when `cursor` says that the device is registered, or when
  the API keeps no devices."
  [call! {:keys [api device] :as opts} session cursor]
  (if (or (not (:register (paths api))) (= device (:registered cursor)))
    cursor
    ;; TODO: link the device to the account's others through
    ;; sync-devices, since gpodder.net keeps subscriptions per device
    (async/then (call! :register {:session session}) (fn [_] (assoc cursor :registered device)))))

(defn- start!
  "Start a step with `call!` and `opts`, with the session in the atom
  `session`, and give `cursor`, in a future or a promise.

  On a server whose API has a login, it logs in and puts the new session
  in the atom. The login sends the session of the step before, which the
  server can keep. Then it registers the device as registered! does."
  [call! {:keys [api] :as opts} session cursor]
  (if-not (:login (paths api))
    (registered! call! opts nil cursor)
    (async/then (async/attempt #(call! :login {:session @session}))
                (fn [{:keys [value error]}]
                  ;; a server without a login answers otherwise, and does without one
                  (when (= 401 (:status (ex-data error)))
                    (throw error))
                  (reset! session value)
                  (registered! call! opts value cursor)))))

(defn- subscriptions!
  "Ask with `call!` in the `session` for the changes to the subscriptions
  since `since`, by the API of `opts`, in a future or a promise.

  When the server no longer knows the device, as gpodder.net can answer,
  the device is registered again and the server asked once more."
  [call! {:keys [api] :as opts} session since]
  (let [ask #(call! :subscriptions {:since since :session session})]
    (async/then (async/attempt ask)
                (fn [{:keys [value error]}]
                  (cond
                    (not error)
                    value

                    (and (:register (paths api)) (= 404 (:status (ex-data error))))
                    (async/then (call! :register {:session session}) (fn [_] (ask)))

                    :else
                    (throw error))))))

(defn- episode-pages
  "The episode actions since `since`, asked with `call!` in the `session`
  by the limits in `opts`, in a future or a promise.

  Another page is asked for while a page holds :page-size actions, up to
  :max-pages pages. The result is a map of the :actions and the
  :timestamp."
  [call! {:keys [page-size max-pages] :as opts} session since]
  (letfn [(page [acc since n]
            (async/then (call! :episodes {:since since :session session})
                        (fn [{:keys [actions timestamp]}]
                          (let [acc (-> acc (update :actions into actions) (assoc :timestamp timestamp))]
                            (if (and (<= page-size (count actions)) timestamp (> timestamp since) (< n max-pages))
                              (page acc timestamp (inc n))
                              acc)))))]
    (page {:actions []} since 1)))

(defn- pull!
  "Pull what changed for `state` since `cursor` at `at`, with `call!` in
  the `session` and `opts`. The result is as pulled gives it, in a future
  or a promise."
  [call! opts session state cursor at]
  (async/then (start! call! opts session cursor)
              (fn [cursor]
                (async/then (async/all force [(delay (subscriptions! call! opts @session (or (:subscriptions cursor) 0)))
                                              ;; a day back, since servers select actions by the time they
                                              ;; name, not the time they arrived, and so miss a late one
                                              (delay (episode-pages call! opts @session (max 0 (- (or (:episodes cursor) 0) 86400))))])
                            (fn [[subs episodes]] (pulled state cursor subs episodes opts at))))))

(defn- pushing
  "What a push of `state` sends for `device`, given the stamps `pushed` of
  the last push and the `names` of the episodes.

  It's a map of the :subscriptions to send with :update-subscriptions, or
  nil when none changed, the episode :actions, and the ids of the changed
  episodes that stay :unnamed. Only what devices of podcast-clj wrote
  since the last push counts, this one or those in the registry of
  `state`, and never what came from a server."
  [state pushed names device]
  (let [ours?   #(or (= (:device %) (:device state)) (contains? (:devices state) (:device %)))
        changed (fn [k entries]
                  (for [[id e] entries
                        :let  [stamp (some-> (:updated-at e) inst-ms)]
                        :when (and stamp (not= stamp (get-in pushed [k id])) (ours? e))]
                    [id e]))
        public  #(first (http/split-credentials %))
        urls    (fn [pred]
                  (distinct (for [[_ e] (changed :subscriptions (:subscriptions state))
                                  :when (and (pred e) (http/web-url? (:url e)))]
                              (public (:url e)))))
        adds    (vec (urls (complement :removed?)))
        drops   (if pushed (vec (remove (set adds) (urls :removed?))) [])
        told    (for [[[g _ :as id] e] (changed :episodes (profile/episode-entries state))
                      :let [podcast (some-> ((:feed-url names) g) public)
                            media   ((:episode-url names) id)]]
                  (if (and (http/web-url? podcast) (http/web-url? media))
                    {:action (entry-action id e podcast media device {:first? (nil? pushed)})}
                    {:unnamed id}))]
    {:subscriptions (when (or (seq adds) (seq drops)) {"add" adds "remove" drops})
     :actions       (keep :action told)
     :unnamed       (keep :unnamed told)}))

(defn- push!
  "Push what pushing gives of `state` with `call!` in the `session` and
  `opts`, and give `cursor`, in a future or a promise.

  The cursor gets the stamps of this push and the URLs that the server
  rewrote. An episode that the push didn't name has no stamp in it, so the
  next push tries it again."
  [call! {:keys [device batch-size] :as opts} session state {:keys [pushed urls] :as cursor}]
  (let [{:keys [subscriptions actions unnamed]} (pushing state pushed (namer state opts) device)
        stamps (fn [entries] (update-vals entries #(some-> (:updated-at %) inst-ms)))
        sent   (fn [rewrites call body]
                 (async/then rewrites
                             (fn [rewrites]
                               (async/then (call! call {:body body :session @session})
                                           #(into rewrites (:update-urls %))))))]
    (async/then
     (reduce #(sent %1 :update-episodes (vec %2))
             (if subscriptions (sent [] :update-subscriptions subscriptions) [])
             ;; gpodder.net takes a longer list in the background, and reports
             ;; no error of it
             (partition-all batch-size actions))
     (fn [rewrites]
       {:cursor (assoc cursor
                          :pushed {:subscriptions (stamps (:subscriptions state))
                                   :episodes      (apply dissoc (stamps (profile/episode-entries state)) unnamed)}
                          :urls   (into (or urls {}) (for [[sent new] rewrites :when (and new (not= sent new))] [new sent])))
        :report (cond-> (vec (for [[sent new] rewrites :when (nil? new)]
                               (diagnostics/describe {:id :sync/rejected-url :level :warning :url sent})))
                  (seq unnamed)
                  (conj (diagnostics/describe {:id :sync/unnamed-episodes :level :info :count (count unnamed)})))}))))

(defn backend
  "A backend for dk.simongray.podcast.sync/sync! over the gpodder API
  of the server at `root`, with the `opts` below:

  - :credentials, a :username and a :password, or a function that returns
    them
  - :api, :gpodder by default, :nextcloud, or a map of paths as in apis
  - :device, the device's id on the server, the state's by default
  - :caption, the name that the server shows for the device, podcast-clj
    by default
  - :type, desktop, laptop, mobile, server, tablet, or other by default
  - :name, for the UI and the cursors, the host by default
  - :identities, a function that makes the ways to name shows and
    episodes at each step, e.g. #(identities @feeds)
  - :feed-guid, :feed-url, :episode-id and :episode-url, each way on its
    own, in place of :identities
  - :page-size, :max-pages, :batch-size and :max-pending, over
    default-limits
  - :timeout-seconds, how long a request can take
  - :send, a function that sends a request map, in place of http/call!

  A pull gives the subscription changes and episode actions since its
  cursors, as a partial state. A push sends what the devices of
  podcast-clj wrote since the last push, and doc/sync.md tells how each
  maps.

  On gpodder.net, each device has its own subscriptions, so link the
  devices of the account there, or give :device the id of the device of
  another app."
  ([root]
   (backend root {}))
  ([root {:keys [name device] :or {name (http/host root)} :as opts}]
   {:pre [(map? opts)]}
   (let [session     (atom nil)
         opts        (merge {:api :gpodder :caption "podcast-clj" :type "other"}
                            default-limits
                            opts
                            {:name name})
         with-device #(assoc opts :device (or device (:device %)))]
     {:name name
      :pull (fn [state cursor at]
              (let [opts (with-device state)]
                (pull! (caller root opts) opts session state cursor at)))
      :push (fn [state cursor _]
              (let [opts (with-device state)]
                (push! (caller root opts) opts session state cursor)))})))

(comment
  (request :subscriptions "https://gpodder.net"
           {:credentials {:username "ann" :password "…"} :device "phone" :since 1727000000})

  (response :subscriptions
            {:status 200
             :body   "{\"add\": [\"https://example.org/feed.xml\"], \"remove\": [], \"timestamp\": 1727000100}"})

  ;; an episode action, as a server sends it
  (read-action {"podcast"   "https://example.org/feed.xml"
                "episode"   "https://example.org/1.mp3"
                "action"    "PLAY"
                "timestamp" "2026-09-29T12:00:00"
                "position"  120
                "total"     1800})

  (writer "phone")
  (device-of "gpodder:phone")
  #_.)

#?(:clj
   (comment
     ;; TODO: try the backend against a live gpodder.net and a Nextcloud
     (def gpodder-net
       (backend "https://gpodder.net" {:credentials {:username "…" :password "…"}}))
     #_.))
