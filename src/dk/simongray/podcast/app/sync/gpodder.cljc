(ns dk.simongray.podcast.app.sync.gpodder
  "The gpodder API as a sync backend: the subscriptions and the episode
  actions that gpodder.net, the GPodder Sync app for Nextcloud, oPodSync,
  PinePods and other servers keep for AntennaPod, Kasts and gPodder;
  doc/sync.md is the design.

  `backend` is the one call an app makes. The API names a show by its feed
  URL and an episode by the URL of its media, and podcast-clj names them by
  guid, so `identities` gives the ways to name them over the app's parsed
  feeds. `request` builds each call as a request map and `response` reads
  the answer, so that the tests run on recorded answers."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.resolve :as resolve]
            [dk.simongray.podcast.time :as time]))

(def calls
  "What a backend asks a server; the GPodder Sync app for Nextcloud has no
  `:login` and no `:register`."
  #{:login :register :subscriptions :update-subscriptions :episodes :update-episodes})

(defn- path
  "The path of `call` in the `api`, :gpodder or :nextcloud, for the account
  `user` and the `device`."
  [api call user device]
  (let [u (http/encode user)
        d (http/encode device)]
    (case [api call]
      [:gpodder :login]                  (str "/api/2/auth/" u "/login.json")
      [:gpodder :register]               (str "/api/2/devices/" u "/" d ".json")
      ([:gpodder :subscriptions]
       [:gpodder :update-subscriptions]) (str "/api/2/subscriptions/" u "/" d ".json")
      ([:gpodder :episodes]
       [:gpodder :update-episodes])      (str "/api/2/episodes/" u ".json")
      [:nextcloud :subscriptions]        "/index.php/apps/gpoddersync/subscriptions"
      [:nextcloud :update-subscriptions] "/index.php/apps/gpoddersync/subscription_change/create"
      [:nextcloud :episodes]             "/index.php/apps/gpoddersync/episode_action"
      [:nextcloud :update-episodes]      "/index.php/apps/gpoddersync/episode_action/create"
      (throw (ex-info (str "The " (name api) " API has no call " call) {:api api :call call})))))

(defn request
  "The request map for `call`, one of `calls`, to the server at `root`, as
  `opts` say:

      {:api         :gpodder   ; or :nextcloud, the GPodder Sync app's paths
       :credentials {:username \"simon\" :password \"…\"}  ; or a function returning them
       :device      \"…\"       ; the device's id on the server
       :since       1727000000  ; for :subscriptions and :episodes, in seconds
       :body        …           ; the data to send, as JSON
       :session     \"…\"}      ; the session of a :login

  Every call carries the credentials as Basic authentication, and the
  session when there is one: some servers want the one, others the other."
  [call root {:keys [api credentials device since body session] :or {api :gpodder}}]
  (let [creds (if (fn? credentials) (credentials) credentials)
        auth  (http/authorization creds)
        get?  (contains? #{:subscriptions :episodes} call)]
    (cond-> {:method  (if get? :get :post)
             :url     (str (str/replace root #"(^|[^/])/+$" "$1")
                           (path api call (:username creds) device)
                           (when get? (str "?since=" (or since 0))))
             :headers (cond-> {"User-Agent" http/user-agent "Accept" "application/json"}
                        auth         (assoc "Authorization" auth)
                        session      (assoc "Cookie" (str "sessionid=" session))
                        (some? body) (assoc "Content-Type" "application/json"))}
      (some? body) (assoc :body (json/write-str body)))))

(defn ^:no-doc web-url?
  "Whether `s` is an http or https URL."
  [s]
  (boolean (and (string? s) (re-find #"(?i)^https?://" s))))

(defn- text
  "The string `v` trimmed; nil for a blank one or anything else."
  [v]
  (when (string? v)
    (not-empty (str/trim v))))

(defn- seconds
  "The seconds `v`; nil for none, or for the negative number a client writes
  for a position it does not know."
  [v]
  (when (and (number? v) (not (neg? v)))
    v))

(defn- instant
  "The instant of the action time `v`: ISO 8601, without a zone in UTC, or
  seconds since the epoch."
  [v]
  (cond
    (string? v) (time/parse-iso8601 v)
    (number? v) (time/->inst (* 1000 (long v)))))

(defn ^:no-doc read-action
  "The episode action `m`, as JSON reads it, with keyword keys, its name in
  lower case and its time an instant; nil for anything but a map."
  [m]
  (when (map? m)
    (into {} (for [[k f] {"podcast" text "episode" text "guid" text "device" text
                          "action"  (comp #(some-> % str/lower-case) text)
                          "timestamp" instant "started" seconds "position" seconds "total" seconds}]
               [(keyword k) (f (get m k))]))))

(defn- epoch
  "The seconds since the epoch of the timestamp `v` of an answer: a number,
  or from some servers a string of digits."
  [v]
  (cond
    (number? v) (long v)
    (string? v) (some->> v str/trim (re-matches #"\d{1,12}") parse-long)))

(defn- session
  "The session id that the Set-Cookie of the answer `headers` sets."
  [headers]
  (let [v (get headers "set-cookie")]
    (some #(second (re-find #"(?:^|[;,]\s*)sessionid=([^;,\s]+)" %)) (if (string? v) [v] v))))

(defn response
  "What the answer `{:status :headers :body}` to `call` says:

      :login                 the session id the server set, nil when none
      :register              nil
      :subscriptions         {:add [url …] :remove [url …] :timestamp seconds}
      :episodes              {:actions [action …] :timestamp seconds}
      :update-subscriptions  {:timestamp seconds :update-urls [[sent rewritten] …]}
      :update-episodes       the same

  An action is as `read-action` reads it, and a rewritten URL is nil when
  the server refused the one sent. A status that is not a success throws
  `::failed` with the `:status`, and so does an answer that is not the JSON
  object the call expects."
  [call {:keys [status headers body]}]
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
                         {:add       (filterv web-url? (get m "add"))
                          :remove    (filterv web-url? (get m "remove"))
                          :timestamp (epoch (get m "timestamp"))})
        :episodes      (let [m (data)]
                         {:actions   (into [] (keep read-action) (get m "actions"))
                          :timestamp (epoch (get m "timestamp"))})
        (:update-subscriptions
         :update-episodes) (let [m (if (str/blank? body) {} (data))]
                             {:timestamp   (epoch (get m "timestamp"))
                              :update-urls (vec (for [[sent new] (get m "update_urls")
                                                      :when (string? sent)]
                                                  [sent (when (web-url? new) new)]))})))))

(defn ^:no-doc normal-url
  "`url` as a feed or a media file is known by, whatever its scheme, its case
  and its trailing slashes."
  [url]
  (-> (str url)
      str/trim
      (str/replace #"(?i)^https?://" "")
      ;; a match may start only where a run of slashes starts: /+$ alone is
      ;; tried again from every slash of a long run, quadratically
      (str/replace #"(^|[^/])/+$" "$1")
      str/lower-case))

(defn- indexed
  "The feed `feed` at `url`, with its guid and the guids of its items, by
  themselves and by the URLs of their media."
  [[url feed]]
  (let [items (keep (fn [item] (when-let [g (feed/item-guid item)] [g item])) (:rss/items feed))]
    {:url      url
     :guid     (feed/feed-guid feed url)
     :items    (into {} items)
     :by-media (into {} (for [[g item] items
                              {media :url} (resolve/enclosures item)]
                          [(normal-url media) g]))}))

(defn identities
  "The ways to name the shows and episodes of `feeds`, the app's parsed feeds
  by URL, as `backend` takes them under `:identities`:

      {:feed-guid   (fn [url])                        ; the guid of the feed at `url`
       :feed-url    (fn [feed-guid])                  ; the URL of that feed
       :episode-id  (fn [podcast-url media-url guid]) ; [feed-guid item-guid]
       :episode-url (fn [[feed-guid item-guid]])}     ; the URL of its media

  Each gives nil for a feed that `feeds` do not hold. An episode is found by
  its guid, which some servers keep, else by the URL of its media, and when
  neither finds it, it is named as podcast-clj names one that its feed
  leaves out. URLs match whatever their scheme, case and trailing slashes."
  [feeds]
  (let [known   (map indexed feeds)
        by-url  (into {} (map (juxt (comp normal-url :url) identity)) known)
        by-guid (into {} (map (juxt :guid identity)) known)]
    {:feed-guid   #(:guid (by-url (normal-url %)))
     :feed-url    #(:url (by-guid %))
     :episode-id  (fn [podcast media guid]
                    (when-let [{g :guid :keys [items by-media]} (by-url (normal-url podcast))]
                      [g (or (when (contains? items guid) guid) (by-media (normal-url media)) guid media)]))
     :episode-url (fn [[feed-guid item-guid]]
                    (when-let [item (get-in (by-guid feed-guid) [:items item-guid])]
                      (or (:url (:rss/enclosure item)) (:url (resolve/enclosure item)))))}))

(defn ^:no-doc namer
  "The ways to name shows and episodes for `state` that `opts` give, by
  their own keys or by `:identities`, with defaults for those they lack.

  A feed is named by the subscription of `state` with its URL, else as
  `:feed-guid` or its URL names it. An episode is named by `:episode-id`,
  and without one by the guid of its action, else the URL of its media;
  with one, an episode of a feed that it does not know stays unnamed."
  [{:keys [identities] :as opts} state]
  (let [{:keys [feed-guid feed-url episode-id episode-url]}
        (merge (when identities (identities)) (select-keys opts [:feed-guid :feed-url :episode-id :episode-url]))
        subscribed (into {} (for [[g {:keys [url]}] (:subscriptions state) :when url] [(normal-url url) g]))
        ;; TODO: a new feed that declares a guid of its own keeps the guid of
        ;; its URL, until the app re-keys its subscription
        guid-of    #(or (subscribed (normal-url %)) (when feed-guid (feed-guid %)) (guid/from-url %))]
    {:feed-guid   guid-of
     :feed-url    #(or (get-in state [:subscriptions % :url]) (when feed-url (feed-url %)))
     :episode-id  (fn [podcast media item]
                    (if episode-id
                      (some-> (episode-id podcast media item) (update 0 #(or (subscribed (normal-url podcast)) %)))
                      [(guid-of podcast) (or item media)]))
     :episode-url (fn [[_ item :as id]]
                    (or (when episode-url (episode-url id)) (when (web-url? item) item)))}))

(defn ^:no-doc written-by
  "`(f state)` as the device `author` writes it, at the instants that `f`
  gives, with the device of `state` and the later of the two clocks."
  [state author f]
  (let [written (f (-> state (assoc :device author) (dissoc :clock)))
        clocks  (keep :clock [state written])]
    (cond-> (assoc written :device (:device state))
      (seq clocks) (assoc :clock (apply max-key inst-ms clocks)))))

(defn ^:no-doc apply-subscriptions
  "`state` with the feed URLs `add` subscribed to and `remove` unsubscribed
  from, as the device `author` did at `at`; `names` gives each URL's guid.

  A feed that already is as the change says is left alone."
  [state {:keys [add remove]} names author at]
  (let [guid-of     (:feed-guid names)
        subscribe   (fn [s url]
                      (let [g (guid-of url)]
                        (cond-> s
                          (not (player/subscribed? s g))
                          (written-by author #(player/subscribe % g {:url url :title (get-in % [:subscriptions g :title])} at)))))
        unsubscribe (fn [s url]
                      (let [g (guid-of url)]
                        (cond-> s
                          (player/subscribed? s g) (written-by author #(player/unsubscribe % g at)))))]
    (as-> state s
      (reduce subscribe s add)
      (reduce unsubscribe s remove))))

(defn ^:no-doc apply-action
  "`state` with the episode action `a` applied to the episode `id` as its
  device, else `author`, did it at the time it names.

  A play records a position as `player/progress` does, and `new` marks the
  episode not played as `player/mark-unplayed` does. An action of another
  kind, or one no later than what `state` holds of the episode, changes
  nothing, so that an action read back does no harm."
  [state id {:keys [action position total timestamp device]} author]
  (let [stamp (some-> (get-in state [:episodes id :updated-at]) inst-ms)
        act   (case action
                "play" (when (number? position)
                         #(player/progress % id position (when (and (number? total) (pos? total)) total) timestamp))
                "new"  #(player/mark-unplayed % id timestamp)
                nil)]
    (if (and act timestamp (< (or stamp 0) (inst-ms timestamp)))
      (written-by state (or device author) act)
      state)))

(defn ^:no-doc entry-action
  "The episode action that tells the entry `e` of the episode `id`, sent by
  `device` with the feed URL `podcast` and the media URL `media`; nil when
  no action tells it.

  A played episode is a play to its end, as AntennaPod writes it, and an
  episode marked not played is `new`, but not on a `first?` push. Positions
  are whole seconds, and a played episode of an unknown duration tells
  nothing."
  [id e podcast media device first?]
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
                  "timestamp" (subs (time/format-iso8601 (:updated-at e)) 0 19)}))))

(defn- pulled
  "What a pull of `state` gives when the server told the subscription
  changes `subs` and the `episodes` actions since the cursor `ctx`.

  They are applied to `state` as their devices did them, and the result is
  what changed, a partial state; the actions whose episodes the app does not
  know yet wait in the cursor, the last 500. `opts` give the ways to name
  and the backend's `:name`, which writes what names no device."
  [state ctx subs episodes {:keys [name] :as opts} at]
  (let [names   (namer opts state)
        local   #(get (:urls ctx) % %)
        told    (or (:timestamp subs) (quot (inst-ms at) 1000))
        ;; the API tells no time of a change to the subscriptions: one counts
        ;; as made when the pull before saw none, and at the first pull as
        ;; made then, so that the first sync joins two lists
        since   (time/->inst (* 1000 (or (:subscriptions ctx) told)))
        id-of   (fn [{:keys [podcast episode guid]}] ((:episode-id names) (local podcast) (local episode) guid))
        changed (apply-subscriptions state {:add (map local (:add subs)) :remove (map local (:remove subs))}
                                     names name since)
        [s unnamed] (reduce (fn [[s unnamed] a]
                              (if-let [id (id-of a)]
                                [(apply-action s id a name) unnamed]
                                [s (conj unnamed a)]))
                            [changed []]
                            (->> (concat (:pending ctx) (:actions episodes))
                                 (filter (every-pred :podcast :episode :timestamp))
                                 (sort-by (comp inst-ms :timestamp))))
        pending (vec (take-last 500 unnamed))
        waiting (count (:pending ctx))]
    {:states   [(player/changes-since s state)]
     :partial? true
     :ctx      (cond-> (-> ctx
                           (dissoc :pending)
                           (assoc :subscriptions told :episodes (or (:timestamp episodes) (:episodes ctx))))
                 (seq pending) (assoc :pending pending))
     :report   (when (> (count pending) waiting)
                 [(diagnostics/describe {:id :sync/unknown-episodes :level :info :count (- (count pending) waiting)})])}))

(defn- caller
  "A function of a call and its parameters that sends it to the server at
  `root` as `opts` say, and reads the answer; deferred."
  [root {:keys [send timeout-seconds device caption type] :as opts}]
  (fn [call params]
    (async/then ((or send http/call!)
                 (cond-> (request call root (merge (select-keys opts [:api :credentials])
                                                   {:device device}
                                                   (when (= :register call) {:body {"caption" caption "type" type}})
                                                   params))
                   timeout-seconds (assoc :timeout-seconds timeout-seconds)))
                #(response call %))))

(defn- start!
  "The cursor `ctx` once a step started with `call!`: logged in on a server of
  the :gpodder API, the session in the atom `session`, and the device
  registered unless `ctx` says it is; deferred."
  [call! {:keys [api device]} session ctx]
  (if (= :nextcloud api)
    ctx
    (async/then (async/attempt #(call! :login {}))
                (fn [{:keys [value error]}]
                  ;; a server without a login answers otherwise, and does without one
                  (when (= 401 (:status (ex-data error)))
                    (throw error))
                  (reset! session value)
                  (if (= device (:registered ctx))
                    ctx
                    ;; TODO: link the device to the account's others through
                    ;; sync-devices, since gpodder.net keeps subscriptions per device
                    (async/then (call! :register {:session value}) (fn [_] (assoc ctx :registered device))))))))

(defn- subscriptions!
  "The changes to the subscriptions since `since`, asked with `call!`; when
  the server no longer knows the device, as gpodder.net answers, registered
  again and asked once more; deferred."
  [call! {:keys [api]} session since]
  (let [ask #(call! :subscriptions {:since since :session session})]
    (async/then (async/attempt ask)
                (fn [{:keys [value error]}]
                  (cond
                    (not error)                                              value
                    (and (= :gpodder api) (= 404 (:status (ex-data error)))) (async/then (call! :register {:session session})
                                                                                        (fn [_] (ask)))
                    :else                                                    (throw error))))))

(defn- episode-pages
  "The episode actions since `since`, asked with `call!` a page at a time
  while pages come full, ten at most: `{:actions [...] :timestamp seconds}`;
  deferred. gpodder.net answers with a thousand actions at most."
  [call! session since]
  (letfn [(page [acc since n]
            (async/then (call! :episodes {:since since :session session})
                        (fn [{:keys [actions timestamp]}]
                          (let [acc (-> acc (update :actions into actions) (assoc :timestamp timestamp))]
                            (if (and (<= 1000 (count actions)) timestamp (> timestamp since) (< n 10))
                              (page acc timestamp (inc n))
                              acc)))))]
    (page {:actions []} since 1)))

(defn- pull!
  "The pull of the backend: what changed since the cursors of `ctx`, asked
  with `call!`, as `pulled` gives it for `state`; deferred."
  [call! opts session state ctx at]
  (async/then (start! call! opts session ctx)
              (fn [ctx]
                (async/then (async/all [(subscriptions! call! opts @session (or (:subscriptions ctx) 0))
                                        ;; a day back, since servers select actions by the time they
                                        ;; name, not the time they arrived, and so miss a late one
                                        (episode-pages call! @session (max 0 (- (or (:episodes ctx) 0) 86400)))])
                            (fn [[subs episodes]] (pulled state ctx subs episodes opts at))))))

(defn- pushing
  "What a push of `state` sends for `device`: the body of
  `:update-subscriptions`, nil when none changed, the episode `:actions`,
  and how many changed episodes stay `:unnamed`.

  Only what devices of podcast-clj wrote since the stamps `pushed` of the
  last push counts, this one or those in the registry of `state`, never
  what came from a server; `names` names the episodes."
  [state pushed names device]
  (let [ours?   #(or (= (:device %) (:device state)) (contains? (:devices state) (:device %)))
        changed (fn [k]
                  (for [[id e] (get state k)
                        :let  [stamp (some-> (:updated-at e) inst-ms)]
                        :when (and stamp (not= stamp (get-in pushed [k id])) (ours? e))]
                    [id e]))
        public  #(first (http/split-credentials %))
        urls    (fn [pred]
                  (distinct (for [[_ e] (changed :subscriptions)
                                  :when (and (pred e) (web-url? (:url e)))]
                              (public (:url e)))))
        adds    (vec (urls (complement :removed?)))
        drops   (if pushed (vec (remove (set adds) (urls :removed?))) [])
        named   (for [[[g _ :as id] e] (changed :episodes)
                      :let [podcast (some-> ((:feed-url names) g) public)
                            media   ((:episode-url names) id)]]
                  (if (and (web-url? podcast) (web-url? media))
                    (entry-action id e podcast media device (nil? pushed))
                    ::unnamed))]
    {:subscriptions (when (or (seq adds) (seq drops)) {"add" adds "remove" drops})
     :actions       (remove #{::unnamed nil} named)
     :unnamed       (count (filter #{::unnamed} named))}))

(defn- push!
  "The push of the backend: what `pushing` gives of `state`, sent with
  `call!`, and the cursor `ctx` with the stamps of this push and the URLs
  the server rewrote; deferred."
  [call! {:keys [device] :as opts} session state {:keys [pushed urls] :as ctx}]
  (let [{:keys [subscriptions actions unnamed]} (pushing state pushed (namer opts state) device)
        sent (fn [rewrites call body]
               (async/then rewrites
                           (fn [rewrites]
                             (async/then (call! call {:body body :session @session})
                                         #(into rewrites (:update-urls %))))))]
    (async/then
     (reduce #(sent %1 :update-episodes (vec %2))
             (if subscriptions (sent [] :update-subscriptions subscriptions) [])
             ;; gpodder.net takes more than 100 actions in the background, and
             ;; reports no error of them
             (partition-all 100 actions))
     (fn [rewrites]
       {:ctx    (assoc ctx
                       :pushed (into {} (for [k [:subscriptions :episodes]]
                                          [k (update-vals (get state k) #(some-> (:updated-at %) inst-ms))]))
                       :urls   (into (or urls {}) (for [[sent new] rewrites :when (and new (not= sent new))] [new sent])))
        :report (cond-> (vec (for [[sent new] rewrites :when (nil? new)]
                               (diagnostics/describe {:id :sync/rejected-url :level :warning :url sent})))
                  (pos? unnamed) (conj (diagnostics/describe {:id :sync/unnamed-episodes :level :info :count unnamed})))}))))

(defn- host
  "The host of the URL `root`."
  [root]
  (second (re-find #"(?i)^[a-z][a-z0-9+.-]*://(?:[^/@]*@)?([^/:]+)" (str root))))

(defn backend
  "A backend for `dk.simongray.podcast.app.sync/sync!` over the gpodder API
  of the server at `root`, as `opts` say:

      {:credentials {:username \"simon\" :password \"…\"}  ; or a function returning them
       :api         :gpodder      ; or :nextcloud, for the GPodder Sync app
       :device      \"…\"         ; the device's id on the server; the state's by default
       :caption     \"podcast-clj\" ; the name the server shows for it
       :type        \"other\"     ; desktop, laptop, mobile, server, tablet or other
       :name        \"…\"         ; for the UI and the cursors; the host by default
       :identities  #(identities @feeds)  ; the ways to name shows and episodes, made at
                                           ; each step; or each by its own key:
       :feed-guid   f :feed-url f :episode-id f :episode-url f
       :timeout-seconds 30
       :send        f}          ; how to send a request map, in place of http/call!

  A pull gives the subscription changes and the episode actions since its
  cursors as a partial state, and a push sends what devices of podcast-clj
  wrote since the last push; doc/sync.md tells how each maps. On
  gpodder.net each device has its own subscriptions, so link the devices
  of the account there, or give `:device` the id of another app's device."
  ([root]
   (backend root {}))
  ([root {:keys [name device] :or {name (host root)} :as opts}]
   {:pre [(map? opts)]}
   (let [session     (atom nil)
         opts        (merge {:api :gpodder :caption "podcast-clj" :type "other"} opts {:name name})
         with-device #(assoc opts :device (or device (:device %)))]
     {:name name
      :pull (fn [state ctx at]
              (let [opts (with-device state)]
                (pull! (caller root opts) opts session state ctx at)))
      :push (fn [state ctx _]
              (let [opts (with-device state)]
                (push! (caller root opts) opts session state ctx)))})))

#?(:clj
   (comment
     ;; TODO: try the backend against a live gpodder.net and a Nextcloud
     (def b (backend "https://gpodder.net" {:credentials {:username "…" :password "…"}}))
     #_.))
