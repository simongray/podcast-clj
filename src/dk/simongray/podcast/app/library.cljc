(ns dk.simongray.podcast.app.library
  "The library of a listener: everything that an app keeps, in one map.
  It holds the player state of `app.player`, the feeds of `app.feeds`,
  the OPML lists that the listener follows, the search index when the
  app searches, the cursors of the sync, and the settings that every step
  reads. An app saves the map as one value, and each part is what its
  own namespace gives, so `(:player library)` takes every function of
  `app.player`.

      {:device   \"…\"                ; the id of this device
       :player   {…}                ; the player state
       :feeds    {guid {…}}         ; the feeds, as app.feeds keeps them
       :lists    {url {…}}          ; the OPML lists, with the validators of their fetch
       :index    {…}                ; the search index, with :search? in the settings
       :sync     {:ctx {…} …}       ; the cursors of the last sync
       :settings {…}}               ; given once to `make`

  `subscribe` and `follow` put a fetched feed or list in. `refresh!`
  fetches what is due and applies it: the feeds to the player, the lists
  to the subscriptions, and the feeds of new subscriptions. `episodes`
  selects episodes across the shows, `show` and `episode` give the views
  of `app.show` and `app.episode`, `step` takes the events of a player,
  `sync!` keeps the devices in step, and `plan` gives the downloads.

  The settings are the options that the steps take, given once:

      {:fetch          f              ; fetches a feed, in the place of dk.simongray.podcast/fetch!
       :send           f              ; sends a request map, in the place of http/call!
       :allow?         http/public?   ; the guard of every request
       :credentials-of f              ; the credentials of a private feed, by its guid
       :index          {…}            ; the credentials of the Podcast Index
       :languages      [\"da\" \"en\"]    ; of the listener, for transcripts
       :search?        false          ; whether to keep the search index
       :cadence        {…}            ; of app.feeds
       :parallel       8              ; feeds fetched at once
       :timeout-seconds 15 :max-bytes … :user-agent …}

  Each step takes the same keys as options, for one call."
  (:require [dk.simongray.podcast.app.downloads :as downloads]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.search :as search]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]))

(def default-settings
  "The settings of a library that `make` is not given. `:list-seconds` is
  how long a fetched OPML list is current."
  {:search?      false
   :list-seconds 86400})

(defn make
  "A library for the device `device`, a stable id that tells the devices of
  a listener apart, made once per installation, at `now`. `opts` describe
  the device for the other devices, such as its `:name` and its
  `:platform`, and give the `:settings`."
  ([device now]
   (make device now {}))
  ([device now {:keys [settings] :as opts}]
   {:pre [(map? opts)]}
   (let [description (dissoc opts :settings)
         settings    (merge default-settings settings)]
     {:device   device
      :player   (cond-> (player/state device)
                  (seq description) (player/identify description now))
      :feeds    {}
      :lists    {}
      :index    (when (:search? settings) (search/index))
      :sync     {}
      :settings settings})))

(defn- options
  "The `opts` of a step over the settings of `library`."
  [library opts]
  (merge (:settings library) opts))

(defn urls
  "The URL of each feed of `library`, by the feed itself, as the `:url`
  function that `search/episodes` and `downloads/plan` take."
  [library]
  (into {} (map (fn [[_ {:keys [feed url]}]] [feed url])) (:feeds library)))

(defn- documents
  "The search documents of the feed `feed` at `url`: the show and each of
  its items."
  [feed url]
  (into [(search/feed-document feed {:url url})]
        (map #(search/item-document feed % {:url url}))
        (:rss/items feed)))

(defn- unindexed
  "The index `index` without the documents of the feed `guid`."
  [index guid]
  (reduce search/remove index (filter #(or (= guid %) (and (vector? %) (= guid (first %)))) (keys (:docs index)))))

(defn- reindexed
  "`library` with the feed `guid` indexed again, when it keeps an index."
  [library guid]
  (if-let [index (:index library)]
    (let [{:keys [feed url]} (get-in library [:feeds guid])]
      (assoc library :index (reduce search/add (unindexed index guid) (when feed (documents feed url)))))
    library))

(defn subscribe
  "`library` subscribed to the feed that `result`, the result of
  `dk.simongray.podcast/fetch!` from `url`, gave at `now`: the feed kept
  as `app.feeds/add` keeps it, the subscription in the player state, and
  the feed indexed when the library searches. `opts` are the settings of
  the subscription, as `player/configure` takes them."
  ([library url result now]
   (subscribe library url result now {}))
  ([library url result now opts]
   {:pre [(map? (:feed result)) (map? opts)]}
   (let [feed (:feed result)
         guid (feed/feed-guid feed url)]
     (-> library
         (update :feeds feeds/add url result now (:cadence (:settings library) {}))
         (update :player player/subscribe guid {:url url :title (resolve/title feed)} now)
         (cond-> (seq opts) (update :player player/configure guid opts now))
         (reindexed guid)))))

(defn unsubscribe
  "`library` without the subscription to the feed `guid`, at `now`: the
  player state keeps the record, and the feed and its documents go."
  [library guid now]
  (-> library
      (update :player player/unsubscribe guid now)
      (update :feeds dissoc guid)
      (cond-> (:index library) (update :index unindexed guid))))

(defn subscriptions
  "The live subscriptions of `library`, by guid: each as `player/subscriptions`
  gives it, with its `:settings` as `player/settings` fills them in, and
  its `:feed` when it is fetched."
  [library]
  (let [player (:player library)]
    (into {} (map (fn [[guid sub]]
                    [guid (cond-> (assoc sub :settings (player/settings player guid))
                            (get-in library [:feeds guid]) (assoc :feed (get-in library [:feeds guid :feed])))]))
          (player/subscriptions player))))

(defn- listed
  "`library` with the parse `list` of the OPML list at `url`, fetched at
  `now` with the `etag` and the `last-modified` of the answer, applied:
  the entry of the list, and the shows that joined it subscribed, as
  `player/refresh-list` does it."
  [library url list now {:keys [etag last-modified]}]
  (let [seconds (:list-seconds (:settings library))
        entry   (cond-> {:list list :fetched-at now :next-check (time/->inst (+ (inst-ms now) (* 1000 seconds)))}
                  etag          (assoc :etag etag)
                  last-modified (assoc :last-modified last-modified))]
    (-> library
        (update :player #(:state (player/refresh-list % url (get-in library [:lists url :list]) list now)))
        (assoc-in [:lists url] entry))))

(defn follow
  "`library` following the OPML list at `url`, whose parse `list` came at
  `now`, with the `:etag` and the `:last-modified` of the fetch in `opts`,
  and the `:title` and the `:folder` of the list as `player/follow-list`
  takes them. The shows of the list become subscriptions, which
  `refresh!` fetches."
  ([library url list now]
   (follow library url list now {}))
  ([library url list now opts]
   {:pre [(map? list) (map? opts)]}
   (-> library
       (update :player player/follow-list url (select-keys opts [:title :folder]) now)
       (listed url list now opts))))

(defn unfollow
  "`library` no longer following the list at `url`, at `now`."
  [library url now]
  (-> library
      (update :player player/unfollow-list url now)
      (update :lists dissoc url)))

(defn- fetched-list!
  "The OPML list at `url` fetched with the validators of `entry`: `{:list
  :etag :last-modified}`, `{:unchanged? true}`, or `{:error}`; deferred."
  [url {:keys [etag last-modified]} {:keys [send] :as opts}]
  (let [headers (cond-> {"Accept" "text/x-opml, application/xml;q=0.9, */*;q=0.1"}
                  etag          (assoc "If-None-Match" etag)
                  last-modified (assoc "If-Modified-Since" last-modified))]
    (async/then (http/sent! send {:url url :headers headers} opts)
                (fn [{:keys [value error]}]
                  (let [status (:status value)
                        h      #(http/header (:headers value) %)]
                    (cond
                      error          {:error (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})}
                      (= 304 status) {:unchanged? true}
                      (not= 200 status) {:error (diagnostics/describe {:id :fetch/status :url url :status status})}
                      :else          (let [{:keys [list error]} (try {:list (opml/parse (str (:body value)))}
                                                                    (catch #?(:clj Exception :cljs :default) e {:error e}))]
                                       (if error
                                         {:error (diagnostics/describe {:id :fetch/malformed :url url :reason (async/explain error)})}
                                         (cond-> {:list list}
                                           (h "etag")          (assoc :etag (h "etag"))
                                           (h "last-modified") (assoc :last-modified (h "last-modified")))))))))))

(defn- lists-refreshed!
  "`library` with its lists that are due at `now` fetched and applied:
  `{:library :report}`; deferred."
  [library now opts]
  (let [due (for [[url entry] (:lists library)
                  :when (<= (inst-ms (:next-check entry)) (inst-ms now))]
              url)]
    (async/then (async/all (map (fn [url] (async/then (fetched-list! url (get-in library [:lists url]) opts) #(vector url %))) due))
                (fn [results]
                  (reduce (fn [{:keys [library] :as acc} [url {:keys [list unchanged? error] :as fetched}]]
                            (let [seconds (:list-seconds (:settings library))
                                  later   (time/->inst (+ (inst-ms now) (* 1000 seconds)))]
                              (cond
                                error      (-> acc
                                               (assoc-in [:library :lists url :next-check] later)
                                               (update :report conj (assoc error :list url)))
                                unchanged? (-> acc
                                               (assoc-in [:library :lists url :fetched-at] now)
                                               (assoc-in [:library :lists url :next-check] later))
                                :else      (assoc acc :library (listed library url list now fetched)))))
                          {:library library :report []}
                          results)))))

(defn- unfetched
  "The guids of the live subscriptions of `library` without a feed, with
  the URL of each."
  [library]
  (for [[guid {:keys [url]}] (player/subscriptions (:player library))
        :when (and url (not (contains? (:feeds library) guid)))]
    [guid url]))

(defn- known
  "`library` with the feed of `result`, fetched from `url` at `now` for the
  subscription `guid`, put in. A list or another app names a show by the
  guid of its URL, so when the feed declares a guid of its own, the
  subscription moves to it, as `player/rekey` moves it."
  [library guid url result now]
  (let [declared (feed/feed-guid (:feed result) url)]
    (-> library
        (update :player player/rekey guid declared now)
        (update :feeds feeds/add url result now (:cadence (:settings library) {}))
        (reindexed declared))))

(defn- fetched-new!
  "`library` with the feeds of its subscriptions that have none fetched
  and put in: `{:library :report}`; deferred. A feed that does not come
  is tried again at the next step."
  [library now {:keys [fetch credentials-of] :as opts}]
  (let [common (select-keys opts [:timeout-seconds :max-bytes :user-agent :allow?])
        fetch1 (fn [[guid url]]
                 (let [credentials (when credentials-of (credentials-of guid))]
                   (async/then (async/attempt #(fetch url (cond-> common credentials (assoc :credentials credentials))))
                               (fn [{:keys [value error]}]
                                 [guid url (if error
                                             {:error (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})}
                                             value)]))))]
    (async/then (async/all (map fetch1 (unfetched library)))
                (fn [results]
                  (reduce (fn [acc [guid url result]]
                            (cond
                              (:feed result)  (update acc :library known guid url result now)
                              (:error result) (update acc :report conj (assoc (:error result) :feed-guid guid))
                              :else           (update acc :report conj (diagnostics/describe {:id :fetch/status :url url :status (:status result) :feed-guid guid}))))
                          {:library library :report []}
                          results)))))

(defn- applied
  "`library` with the changes of `step`, a result of `app.feeds/refresh!`,
  applied to the player at `now`: `{:library :added :moved}`. The player
  learns each fresh feed, and moves each subscription whose feed moved."
  [library {:keys [feeds changed moved]} now]
  (let [before (:feeds library)
        library (assoc library :feeds feeds)
        result (reduce (fn [acc guid]
                         (let [{:keys [feed url]} (get feeds guid)
                               {:keys [state added]} (player/refresh (:player (:library acc)) (get-in before [guid :feed]) feed url now)]
                           (-> acc
                               (assoc-in [:library :player] state)
                               (update :library reindexed guid)
                               (update :added into added))))
                       {:library library :added []}
                       changed)]
    (reduce (fn [acc [guid new-url]]
              (let [title (resolve/title (get-in feeds [guid :feed]))]
                (update-in acc [:library :player] player/subscribe guid {:url new-url :title title} now)))
            (assoc result :moved moved)
            moved)))

(defn refresh!
  "Fetch what `library` is due to fetch at `now`, and apply it: the OPML
  lists, whose new shows become subscriptions; the feeds of the
  subscriptions that have none; and the feeds that are due, as
  `app.feeds/refresh!` fetches them, each fresh feed given to
  `player/refresh`. On the JVM the map, in ClojureScript a promise of
  it.

      {:library …
       :added   [[feed-guid item-guid] …]   ; the new episodes, as player/refresh found them
       :moved   {feed-guid \"https://…\"}    ; the feeds that moved, their subscriptions moved too
       :report  [...]}                     ; the fetches that failed, and why

  `refresh!` throws nothing for what the network or a server does. `opts`
  are the settings, for this call, and `:guids`, the feeds to fetch in
  the place of those that are due, as after a Podping:
  `(app.feeds/affected (:feeds library) pings)`."
  ([library now]
   (refresh! library now {}))
  ([library now opts]
   {:pre [(map? opts)]}
   (let [opts (merge {:send http/call!} (options library opts))]
     (async/then (lists-refreshed! library now opts)
                 (fn [{library :library report :report}]
                   (async/then (fetched-new! library now opts)
                               (fn [{library :library report-new :report}]
                                 (async/then (feeds/refresh! (:feeds library) now (select-keys opts [:guids :fetch :credentials-of :parallel :cadence :timeout-seconds :max-bytes :user-agent :allow?]))
                                             (fn [step]
                                               (-> (applied library step now)
                                                   (assoc :report (-> [] (into report) (into report-new) (into (:report step))))))))))))))

(defn- item-of
  "The feed and the item of `library` with the identity `id`, `[feed-guid
  item-guid]`, as `[entry item]`; nil when the library has neither."
  [library [feed-guid item-guid]]
  (when-let [{:keys [feed] :as entry} (get-in library [:feeds feed-guid])]
    (when-let [item (some #(when (= item-guid (feed/item-guid %)) %) (:rss/items feed))]
      [entry item])))

(defn show
  "The view of the show `guid` of `library`, as `app.show/view` gives it,
  with the `:url` of its feed; nil when the library has no such feed.
  `opts` are those of `app.show/view`."
  ([library guid]
   (show library guid {}))
  ([library guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds guid])]
     (show/view feed (assoc opts :url url)))))

(defn show!
  "The view of the show `guid` of `library` with what the network says,
  as `app.show/view!` gives it, with the settings of the library; nil
  when the library has no such feed. `opts` are those of `app.show/view!`,
  for this call."
  ([library guid]
   (show! library guid {}))
  ([library guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds guid])]
     (show/view! feed (assoc (options library opts) :url url)))))

(defn episode
  "The view of the episode `id` of `library`, `[feed-guid item-guid]`, as
  `app.episode/view` gives it, with the `:languages` of the settings; nil
  when the library has no such episode. `opts` are those of
  `app.episode/view`."
  ([library id]
   (episode library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (episode/view feed item (assoc (options library opts) :url url)))))

(defn episode!
  "The view of the episode `id` of `library` with what its files say, as
  `app.episode/view!` gives it, with the settings of the library; nil when
  the library has no such episode. `opts` are those of
  `app.episode/view!`, for this call."
  ([library id]
   (episode! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (episode/view! feed item (assoc (options library opts) :url url)))))

(defn episodes
  "The episodes of the shows of `library` that `opts` select, in the
  order they ask for, as `search/episodes` selects them over all the
  feeds: `[feed item]` pairs. The criteria read the player state of the
  library.

      (episodes library {:where {:status :unplayed} :order :listening :limit 20})"
  ([library]
   (episodes library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (search/episodes (map :feed (vals (:feeds library)))
                    (assoc opts :player (:player library) :url (urls library)))))

(defn search
  "The episodes and shows of `library` that match the query `q`, best
  first, as `search/query` finds them in the index of the library, which
  the settings keep with `:search? true`. Nil without an index. `opts` are
  those of `search/query`."
  ([library q]
   (search library q {}))
  ([library q opts]
   (some-> (:index library) (search/query q opts))))

(defn step
  "`library` after the player event `event` at `now`, as `player/step`
  takes it, with the `:effects` that the app must do, and the `:sleep`
  timer for the next step: `{:library :effects :sleep}`. `opts` are those
  of `player/step`."
  ([library event now]
   (step library event now {}))
  ([library event now opts]
   (let [{:keys [state] :as result} (player/step (:player library) event now opts)]
     (-> result
         (dissoc :state)
         (assoc :library (assoc library :player state))))))

(defn sync!
  "One step of keeping the devices of the listener in step through
  `backends`, at `now`, as `sync/sync!` does it: the cursors of the last
  step go with it, what the listener did during the step is merged into
  the result, the tombstones are compacted, and the cursors are kept for
  the next step. On the JVM the map, in ClojureScript a promise of it.

      {:library …
       :next-at #inst \"…\"   ; when to sync again, as sync/next-at says
       :failed  {…}          ; the backends that failed, when any did
       :report  [...]}

  `opts` are those of `sync/sync!`, but for `:ctx`, which the library
  keeps."
  ([library backends now]
   (sync! library backends now {}))
  ([library backends now opts]
   {:pre [(map? opts)]}
   (let [before (:player library)]
     (async/then (sync/sync! before backends now (assoc opts :ctx (get-in library [:sync :ctx])))
                 (fn [{:keys [state ctx failed report]}]
                   (let [player   (-> before (player/merge state) (player/compact now))
                         failures (if (seq failed) (inc (get-in library [:sync :failures] 0)) 0)
                         last     {:at now :changed? (not= (:clock before) (:clock player)) :failures failures}]
                     (cond-> {:library (assoc library
                                              :player player
                                              :sync {:ctx ctx :at now :clock (:clock player) :failures failures})
                              :next-at (sync/next-at last now)
                              :report  report}
                       (seq failed) (assoc :failed failed))))))))

(defn next-sync
  "When `library` is due to sync again, at `now`, as `sync/next-at` says:
  `now` before the first step, and sooner when the player state changed
  since the last step."
  [library now]
  (let [{:keys [at clock failures]} (:sync library)]
    (sync/next-at (when at {:at at :changed? (not= clock (:clock (:player library))) :failures failures}) now)))

(defn plan
  "The episodes to download and the files to delete on this device at
  `now`, as `downloads/plan` gives them for the player state and the
  feeds of `library`. `files` are the files of the device, and `opts` are
  those of `downloads/plan`."
  ([library files now]
   (plan library files now {}))
  ([library files now opts]
   {:pre [(map? opts)]}
   (downloads/plan (:player library) (map :feed (vals (:feeds library))) files now
                   (assoc opts :url (urls library)))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast :as podcast])
     (def now (java.util.Date.))
     (def url "https://feeds.podcastindex.org/pc20.xml")

     (def lib (-> (make "dev-laptop" now {:name "Laptop" :settings {:languages ["da" "en"]}})
                  (subscribe url (podcast/fetch! url {:max-pages 2}) now {:auto-queue? true})))

     (keys (subscriptions lib))
     (map (comp :title second) (episodes lib {:limit 3}))
     (select-keys (show lib (first (keys (subscriptions lib)))) [:title :author])

     ;; the new episodes since the last fetch, and the moves
     (select-keys (refresh! lib (java.util.Date.)) [:added :moved :report])

     ;; the newest episode, played a minute in
     (def id (let [[feed item] (first (episodes lib {:order :newest :limit 1}))]
               (player/episode-id feed (get (urls lib) feed) item)))
     (:effects (step lib [:progress id 60 3600] now))
     (select-keys (episode! lib id) [:title :duration :chapters])
     #_.))
