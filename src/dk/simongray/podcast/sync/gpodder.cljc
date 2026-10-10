(ns dk.simongray.podcast.sync.gpodder
  "The gpodder API as a sync backend, for the subscriptions and episode
  actions that a gpodder server keeps.

  Such servers are gpodder.net, the GPodder Sync app for Nextcloud,
  oPodSync and PinePods. They keep the data of apps such as AntennaPod,
  Kasts and gPodder. The design is in doc/sync.md.

  The API names a show by its feed URL, and an episode by the URL of its
  media, where podcast-clj names them by guid. The requests and the
  answers of the API are in dk.simongray.podcast.sync.gpodder.api."
  (:require [clojure.string :as str]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync.gpodder.api :as gpodder.api]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(def default-limits
  "The limits of a backend when its options don't set them.

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

(defn- indexed
  "The `feed` at `url` with its guid, and the index of its items as a
  delay, made when a lookup first needs it.

  The index has the items by guid, and their guids by the URL of their
  media."
  [{:keys [url feed]}]
  {:url   url
   :guid  (guid/feed-guid feed url)
   :index (delay
            (let [with-guid (fn [item]
                              (when-let [g (guid/item-guid item)]
                                [g item]))
                  items     (keep with-guid (:rss/items feed))]
              {:items    (into {} items)
               :by-media (into {} (for [[g item] items
                                        {media :url} (resolve/enclosures item)]
                                    [(guid/normal-url media) g]))}))})

(defn identities
  "The ways to name the shows and episodes of the `feeds`, for the
  :identities-fn of a backend. The feeds are maps of the :url and the
  parsed :feed, and the ways are these:

      {:feed-guid-fn   (fn [url])                        ; the guid of the feed
       :feed-url-fn    (fn [feed-guid])                  ; the URL of the feed
       :episode-id-fn  (fn [podcast-url media-url guid]) ; the episode id
       :episode-url-fn (fn [[feed-guid item-guid]])}     ; the URL of its media

  Each function gives nil for a feed that isn't in `feeds`. An episode is
  found by its guid, since some servers keep the guid, or else by the URL
  of its media. When neither finds it, the episode gets the name that
  podcast-clj gives an episode that its feed leaves out. URLs match
  whatever their scheme, case and trailing slashes.

  The items of a feed are indexed when a lookup first names the feed, so
  keep the result while the app's feeds stay the same."
  [feeds]
  (let [known   (map indexed feeds)
        url-of  (comp guid/normal-url :url)
        by-url  (into {} (map (juxt url-of identity)) known)
        by-guid (into {} (map (juxt :guid identity)) known)
        at-url  #(by-url (guid/normal-url %))
        index   #(some-> % :index deref)]
    {:feed-guid-fn   #(:guid (at-url %))
     :feed-url-fn    #(:url (by-guid %))
     :episode-id-fn  (fn [podcast media guid]
                       (when-let [{g :guid :as found} (at-url podcast)]
                         (let [{:keys [items by-media]} (index found)]
                           [g (or (when (contains? items guid) guid)
                                  (by-media (guid/normal-url media))
                                  guid
                                  media)])))
     :episode-url-fn (fn [[feed-guid item-guid]]
                       (let [items (:items (index (by-guid feed-guid)))]
                         (when-let [item (get items item-guid)]
                           (or (:url (:rss/enclosure item))
                               (:url (resolve/enclosure item))))))}))

(defn ^:no-doc namer
  "The ways to name shows and episodes for `state` that `opts` give, by
  their own keys or by :identities-fn, with defaults for those they lack.

  A feed is named by the subscription of `state` with its URL. Without
  such a subscription, :feed-guid-fn names the feed, or else its URL does.

  An episode is named by :episode-id-fn. Without that option, the guid of
  its action names it, or else the URL of its media. With the option, an
  episode of a feed that the option doesn't know stays unnamed.

  The URL of the media of an episode is what :episode-url-fn gives, or
  without it, the guid of the item when that's a URL."
  [state {:keys [identities-fn] :as opts}]
  (let [ways       [:feed-guid-fn :feed-url-fn :episode-id-fn :episode-url-fn]
        {:keys [feed-guid-fn feed-url-fn episode-id-fn episode-url-fn]}
        (merge (when identities-fn (identities-fn)) (select-keys opts ways))

        subscribed (comp (profile/by-url state) guid/normal-url)
        ;; a new feed that declares a guid of its own keeps the guid of its
        ;; URL until profile/rekey moves the subscription, which app.library
        ;; does when it fetches the feed
        guid-of    #(or (subscribed %)
                        (when feed-guid-fn (feed-guid-fn %))
                        (guid/from-url %))]
    {:feed-guid-fn   guid-of
     :feed-url-fn    #(or (get-in state [:subscriptions % :url])
                          (when feed-url-fn (feed-url-fn %)))
     :episode-id-fn  (fn [podcast media item]
                       (if episode-id-fn
                         (some-> (episode-id-fn podcast media item)
                                 (update 0 #(or (subscribed podcast) %)))
                         [(guid-of podcast) (or item media)]))
     :episode-url-fn (fn [[_ item :as id]]
                       (if episode-url-fn
                         (episode-url-fn id)
                         (when (url/web-url? item) item)))}))

(defn ^:no-doc device-author
  "The author of the entries in a state for what the `device` of a gpodder
  server did. No device of podcast-clj has such a name, so a push never
  sends the entries back."
  [device]
  (str "gpodder:" device))

(defn ^:no-doc device-of
  "The device of a gpodder server that wrote an entry as `author`. A device
  of podcast-clj is its own name."
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
  (let [guid-of     (:feed-guid-fn names)
        device      (device-author author)
        subscribe   (fn [s url]
                      (let [g     (guid-of url)
                            known (get-in s [:subscriptions g])
                            show  {:url url :title (:title known) :added-at at}
                            stamp (if known at (time/->inst 0))
                            join  #(profile/subscribe % g show stamp)]
                        (cond-> s
                          (not (profile/subscribed? s g))
                          (crdt/as-device device join))))
        unsubscribe (fn [s url]
                      (let [g     (guid-of url)
                            leave #(profile/unsubscribe % g at)]
                        (cond-> s
                          (profile/subscribed? s g)
                          (crdt/as-device device leave))))]
    (as-> state s
      (reduce subscribe s add)
      (reduce unsubscribe s remove))))

(defn ^:no-doc apply-action
  "The `state` with the episode action `a` applied to the episode `id` at
  the time it names, by its own device or else by `author`.

  A \"play\" records a position, and a \"new\" marks the episode as not
  played. An action of another kind, or one no later than what `state`
  has of the episode, changes nothing, so that an action read back does
  no harm."
  [state id {:keys [action position total timestamp device] :as a} author]
  (let [stamp    (some-> (profile/episode-entry state id) :updated-at inst-ms)
        duration (when (and (number? total) (pos? total))
                   total)
        act      (case action
                   "play"
                   (when (number? position)
                     #(profile/progress % id position duration timestamp))

                   "new"
                   #(profile/mark-unplayed % id timestamp)

                   nil)]
    (if (and act timestamp (< (or stamp 0) (inst-ms timestamp)))
      (crdt/as-device state (device-author (or device author)) act)
      state)))

(defn- told-at
  "The instant of what the episode entry `e` tells, on the clock of the
  device that wrote it.

  It's the later of its last report and its mark as played, or else its
  stamp. The stamp isn't a time of the device: after a merge with a device
  whose clock is fast, each stamp is later than the clock of this device."
  [{:keys [reported-at played-at updated-at] :as e}]
  (or (some->> (filter inst? [reported-at played-at])
               seq
               (apply max-key inst-ms))
      updated-at))

(defn ^:no-doc entry-action
  "The episode action that tells the entry `e` of the episode `id`, or nil.
  The `device` sends it with the feed URL `podcast`, the media URL `media`
  and `opts`.

  A played episode is a play to its end, as AntennaPod writes it. An
  episode marked as not played is a \"new\", except on the first push, when
  `opts` have :first?. Positions are whole seconds, and a played episode
  of an unknown duration tells nothing. The time of the action is on the
  clock of the device that wrote the entry."
  ([id e podcast media device]
   (entry-action id e podcast media device {}))
  ([id e podcast media device {:keys [first?] :as opts}]
   (let [{:keys [position duration played?]} e
         whole (fn [x]
                 (when (and (number? x)
                            (== x x)
                            (not (infinite? x))
                            (not (neg? x)))
                   (long x)))
         at    (whole position)
         total (let [d (whole duration)]
                 (when (and d (pos? d))
                   d))
         new?  (and (false? played?) (contains? #{nil 0} at))
         act   (cond
                 (and played? total)
                 {"action"   "play"
                  "started"  total
                  "position" total
                  "total"    total}

                 played?
                 nil

                 new?
                 (when-not first?
                   {"action" "new"})

                 (and at total)
                 {"action"   "play"
                  "started"  at
                  "position" at
                  "total"    total}

                 at
                 {"action" "play" "position" at})]
     (when act
       ;; ISO 8601 to the second and without a zone, which every server reads
       (let [timestamp (subs (time/format-iso8601 (told-at e)) 0 19)]
         (merge act {"podcast"   podcast
                     "episode"   media
                     "guid"      (second id)
                     "device"    device
                     "timestamp" timestamp}))))))

(defn- ahead?
  "Whether the episode action `a` names a time that's further after the
  instant `at` than a clock can be ahead."
  [a at]
  (< document/skew-tolerance (- (inst-ms (:timestamp a)) (inst-ms at))))

(defn- ahead-report
  "A diagnostic for each device whose episode `actions` name a time after
  the pull at `at`, with `author` as the device of an action that names
  none."
  [actions author at]
  (for [[device named] (group-by #(or (:device %) author) actions)
        :let [latest (apply max (map (comp inst-ms :timestamp) named))]]
    (diagnostics/describe {:id      :gpodder/future-actions
                           :level   :warning
                           :device  device
                           :count   (count named)
                           :seconds (quot (- latest (inst-ms at)) 1000)})))

(defn- pulled
  "What a pull for `state` gives at `at`, from the subscription changes
  `subs` and the `episodes` actions since `cursor`, with the `opts` of the
  backend.

  They're applied to `state` as their devices made them, and the result
  is what changed, a partial state. The actions of episodes that the app
  doesn't know yet wait in the cursor, up to the last :max-pending. The
  `opts` give the ways to name, and the :name of the backend, which writes
  what names no device.

  An action that's ahead of `at` changes nothing, and the report names
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
        since   (time/->inst (min (inst-ms at)
                                  (* 1000 (or (:subscriptions cursor) told))))
        id-of   (fn [{:keys [podcast episode guid]}]
                  ((:episode-id-fn names) (local podcast) (local episode) guid))
        changes {:add    (map local (:add subs))
                 :remove (map local (:remove subs))}
        changed (apply-subscriptions state changes names name since)
        ;; a server tells an action again in the day before the cursor
        told-of (distinct (concat (:pending cursor) (:actions episodes)))
        whole?  (every-pred :podcast :episode :timestamp)
        actions (sort-by (comp inst-ms :timestamp) (filter whole? told-of))
        ahead   (filter #(ahead? % at) actions)
        timely  (remove #(ahead? % at) actions)
        step    (fn [[s unnamed] a]
                  (if-let [id (id-of a)]
                    [(apply-action s id a name) unnamed]
                    [s (conj unnamed a)]))
        [s unnamed] (reduce step [changed []] timely)
        pending (vec (take-last max-pending unnamed))
        more    (- (count pending) (count (:pending cursor)))
        broken  (count (remove whole? told-of))
        latest  (or (:timestamp episodes) (:episodes cursor))
        counted (fn [d n] (diagnostics/describe (assoc d :count n)))]
    {:states   [(crdt/changes-since s state)]
     :partial? true
     :cursor   (cond-> (-> cursor
                           (dissoc :pending)
                           (assoc :subscriptions told :episodes latest))
                 (seq pending) (assoc :pending pending))
     :report   (cond-> (vec (ahead-report ahead name at))
                 (pos? more)
                 (conj (counted {:id :gpodder/unknown-episodes :level :info}
                                more))

                 (pos? broken)
                 (conj (counted {:id :gpodder/malformed-actions :level :warning}
                                broken)))}))

(defn- caller
  "A function of a call and its parameters that sends it to the server at
  `root` with `opts`, and reads the answer, in a future or a promise."
  [root {:keys [send device caption type] :as opts}]
  (fn [call params]
    (let [registration (when (= :register call)
                         {:body {"caption" caption "type" type}})
          params       (merge (select-keys opts [:api :credentials])
                              {:device device}
                              registration
                              params)
          asked        (merge (gpodder.api/request call root params)
                              (select-keys opts [:timeout-seconds
                                                 :user-agent]))]
      (async/then ((or send client/send!) asked)
                  #(gpodder.api/response call %)))))

(defn- register!
  "Register the device of `opts` through `call!` in the `session`, and give
  `cursor` with it, in a future or a promise.

  Nothing is sent when `cursor` says that the device is registered, or when
  the API keeps no devices."
  [call! {:keys [api device] :as opts} session cursor]
  (if (or (not (:register (gpodder.api/paths api)))
          (= device (:registered cursor)))
    cursor
    ;; TODO: link the device to the account's others through
    ;; sync-devices, since gpodder.net keeps subscriptions per device
    (async/then (call! :register {:session session})
                (fn [_] (assoc cursor :registered device)))))

(defn- start!
  "Start a step with `call!` and `opts`, with the session in the atom
  `session`, and give `cursor`, in a future or a promise.

  On a server whose API has a login, it logs in and puts the new session
  in the atom. The login sends the session of the step before, which the
  server can keep. Then it registers the device."
  [call! {:keys [api] :as opts} session cursor]
  (if-not (:login (gpodder.api/paths api))
    (register! call! opts nil cursor)
    (async/then (async/attempt #(call! :login {:session @session}))
                (fn [{:keys [value error]}]
                  ;; a server without a login answers otherwise, and does
                  ;; without one
                  (when (= 401 (:status (ex-data error)))
                    (throw error))
                  (reset! session value)
                  (register! call! opts value cursor)))))

(defn- fetch-subscriptions!
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

                    (and (:register (gpodder.api/paths api))
                         (= 404 (:status (ex-data error))))
                    (async/then (call! :register {:session session})
                                (fn [_] (ask)))

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
            (async/then
             (call! :episodes {:since since :session session})
             (fn [{:keys [actions timestamp]}]
               (let [acc   (-> acc
                               (update :actions into actions)
                               (assoc :timestamp timestamp))
                     more? (and (<= page-size (count actions))
                                timestamp
                                (> timestamp since)
                                (< n max-pages))]
                 (if more?
                   (page acc timestamp (inc n))
                   acc)))))]
    (page {:actions []} since 1)))

(defn- pull!
  "Pull what changed for `state` since `cursor` at `at`, with `call!` in
  the `session` and `opts`, and give the result of the pull, in a future
  or a promise."
  [call! opts session state cursor at]
  (async/then
   (start! call! opts session cursor)
   (fn [cursor]
     (let [since    (or (:subscriptions cursor) 0)
           ;; a day back, since servers select actions by the time they
           ;; name, not the time they arrived, and so miss a late one
           day-back (max 0 (- (or (:episodes cursor) 0) 86400))
           changes  (delay (fetch-subscriptions! call! opts @session since))
           actions  (delay (episode-pages call! opts @session day-back))]
       (async/then (async/all force [changes actions])
                   (fn [[subs episodes]]
                     (pulled state cursor subs episodes opts at)))))))

(defn- pushing
  "What a push of `state` sends for `device`, given the stamps `pushed` of
  the last push and the `names` of the episodes.

  It's a map of the :subscriptions to send with :update-subscriptions, or
  nil when none changed, the episode :actions, and the ids of the changed
  episodes that stay :unnamed. Only what devices of podcast-clj wrote
  since the last push counts, this one or those in the registry of
  `state`, and never what came from a server."
  [state pushed names device]
  (let [ours?   #(or (= (:device %) (:device state))
                     (contains? (:devices state) (:device %)))
        changed (fn [k entries]
                  (for [[id e] entries
                        :let  [stamp (some-> (:updated-at e) inst-ms)]
                        :when (and stamp
                                   (not= stamp (get-in pushed [k id]))
                                   (ours? e))]
                    [id e]))
        public  #(first (url/split-credentials %))
        subs    (changed :subscriptions (:subscriptions state))
        urls    (fn [pred]
                  (distinct (for [[_ e] subs
                                  :when (and (pred e) (url/web-url? (:url e)))]
                              (public (:url e)))))
        adds    (vec (urls (complement :removed?)))
        drops   (if pushed (vec (remove (set adds) (urls :removed?))) [])
        entries (changed :episodes (profile/episode-entries state))
        told    (for [[[g _ :as id] e] entries
                      :let [podcast (some-> ((:feed-url-fn names) g) public)
                            media   ((:episode-url-fn names) id)]]
                  (if (and (url/web-url? podcast) (url/web-url? media))
                    {:action (entry-action id e podcast media device
                                           {:first? (nil? pushed)})}
                    {:unnamed id}))]
    {:subscriptions (when (or (seq adds) (seq drops))
                      {"add" adds "remove" drops})
     :actions       (keep :action told)
     :unnamed       (keep :unnamed told)}))

(defn- push!
  "Push what changed of `state` with `call!` in the `session` and `opts`,
  and give `cursor`, in a future or a promise.

  The cursor gets the stamps of this push and the URLs that the server
  rewrote. An episode that the push didn't name has no stamp in it, so the
  next push tries it again."
  [call! {:keys [device batch-size] :as opts} session state
   {:keys [pushed urls] :as cursor}]
  (let [{:keys [subscriptions actions unnamed]}
        (pushing state pushed (namer state opts) device)

        stamps (fn [entries]
                 (update-vals entries #(some-> (:updated-at %) inst-ms)))
        sent   (fn [rewrites call body]
                 (async/then
                  rewrites
                  (fn [rewrites]
                    (async/then (call! call {:body body :session @session})
                                #(into rewrites (:update-urls %))))))]
    (async/then
     (reduce #(sent %1 :update-episodes (vec %2))
             (if subscriptions
               (sent [] :update-subscriptions subscriptions)
               [])
             ;; gpodder.net takes a longer list in the background, and reports
             ;; no error of it
             (partition-all batch-size actions))
     (fn [rewrites]
       (let [episodes (apply dissoc
                             (stamps (profile/episode-entries state))
                             unnamed)
             moved    (for [[sent new] rewrites
                            :when (and new (not= sent new))]
                        [new sent])
             rejected (for [[sent new] rewrites
                            :when (nil? new)]
                        (diagnostics/describe {:id    :gpodder/rejected-url
                                               :level :warning
                                               :url   sent}))]
         {:cursor (assoc cursor
                         :pushed {:subscriptions (stamps (:subscriptions state))
                                  :episodes      episodes}
                         :urls   (into (or urls {}) moved))
          :report (cond-> (vec rejected)
                    (seq unnamed)
                    (conj (diagnostics/describe
                           {:id    :gpodder/unnamed-episodes
                            :level :info
                            :count (count unnamed)})))})))))

(defn backend
  "A sync backend over the gpodder API of the server at `root`, with the
  `opts` below:

  - :credentials, a :username and a :password, or a function that returns
    them
  - :api, :gpodder by default, :nextcloud, or a map of paths as in
    gpodder.api/apis
  - :device, the device's id on the server, the state's by default
  - :caption, the name that the server shows for the device, podcast-clj
    by default
  - :type, desktop, laptop, mobile, server, tablet, or other by default
  - :name, for the UI and the cursors, the host by default
  - :identities-fn, a function that makes the ways to name shows and
    episodes at each step, e.g. #(identities (vals @feeds))
  - :feed-guid-fn, :feed-url-fn, :episode-id-fn and :episode-url-fn, each
    way on its own, in place of :identities-fn
  - :page-size, :max-pages, :batch-size and :max-pending, over
    default-limits
  - :timeout-seconds, how long a request can take
  - :user-agent, as http/send! takes it
  - :send, a function that sends a request map, in place of client/send!

  A pull gives the subscription changes and episode actions since its
  cursors, as a partial state. A push sends what the devices of
  podcast-clj wrote since the last push, and doc/sync.md tells how each
  maps.

  On gpodder.net, each device has its own subscriptions, so link the
  devices of the account there, or give :device the id of the device of
  another app."
  ([root]
   (backend root {}))
  ([root {:keys [name device] :or {name (url/host root)} :as opts}]
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
  (device-author "phone")
  (device-of "gpodder:phone")
  #_.)

#?(:clj
   (comment
     ;; TODO: try the backend against a live gpodder.net and a Nextcloud
     (def gpodder-net
       (backend "https://gpodder.net"
                {:credentials {:username "…" :password "…"}}))
     #_.))
