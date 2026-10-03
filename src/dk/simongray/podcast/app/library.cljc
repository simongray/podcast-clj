(ns dk.simongray.podcast.app.library
  "The subscriptions, feeds, queue and play progress of an app, in one map.

  Each part is a value of its own namespace, e.g. every function of
  app.profile takes (:profile library):

      {:device   \"…\"                ; the id of this device
       :profile  {…}                ; the profile, a CRDT that syncs
       :feeds    {guid {…}}         ; the feeds, as app.feeds keeps them
       :lists    {url {…}}          ; the followed OPML lists
       :index    {…}                ; the search index, with :search?
       :sync     {:ctx {…} …}       ; the cursors and report of the last sync
       :refresh  {:added […] …}     ; the new episodes, moves and report
       :effects  [[:seek id 784]]   ; the effects of the last step, to apply
       :sleep    {:episodes 1}      ; the sleep timer of this device, if set
       :settings {…}}               ; given once to make

  Keep the library in an atom: swap! it with the pure functions, and
  reset! it with refresh! and sync!, which fetch. Each returns the library
  along with what it did, under :effects, :refresh or :sync.
  The profile is a CRDT, so change it only with the functions of
  app.profile, never with assoc or dissoc.

  The settings are the options that the functions take, given once:

      {:fetch          f              ; in place of podcast/fetch!
       :send           f              ; in place of http/call!
       :allow?         http/public?   ; which URLs a request may go to
       :credentials-of f              ; the credentials of a private feed
       :index          {…}            ; the credentials of the Podcast Index
       :languages      [\"da\" \"en\"]    ; of the listener, for transcripts
       :search?        false          ; whether to keep the search index
       :cadence        {…}            ; as app.feeds takes it
       :parallel       8              ; how many feeds are fetched at once
       :now            f              ; the clock, util.time/now by default
       :timeout-seconds 15 :max-bytes … :user-agent …}

  Each function also takes the same keys as options, for one call.

  Each change is stamped with the time of the clock, or with the :at of
  the options, an instant. Give :at for an event that you handle later,
  e.g. in a background task, since it needs the time it happened."
  (:require [dk.simongray.podcast.app.downloads :as downloads]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.profile :as profile]
            [dk.simongray.podcast.app.search :as search]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]))

(def default-settings
  "The settings that a library has when make isn't given them. The
  :list-seconds is how long a fetched OPML list stays current."
  {:search?      false
   :list-seconds 86400})

(defn- options
  "The `opts` of a call over the settings of `library`."
  [library opts]
  (merge (:settings library) opts))

(defn ^:no-doc time-of
  "The instant of a call to `library` with `opts`: their :at, or else the
  time of the clock, the :now of the options or of the settings."
  [library opts]
  (or (:at opts) ((:now (options library opts) time/now))))

(defn make
  "A library for the device `device`, made with the `opts` below.

  The `device` is a stable id that tells the listener's devices apart,
  made once per installation. The `opts` describe the device for the other
  devices, such as its :name and :platform, and give the :settings."
  ([device]
   (make device {}))
  ([device {:keys [settings] :as opts}]
   {:pre [(map? opts)]}
   (let [description (dissoc opts :settings :at)
         settings    (merge default-settings settings)]
     {:device   device
      :profile  (cond-> (profile/make device)
                  (seq description) (crdt/identify description (time-of {:settings settings} opts)))
      :feeds    {}
      :lists    {}
      :index    (when (:search? settings) (search/index))
      :sync     {}
      :settings settings})))

(defn urls
  "A map from each feed of `library` to its URL, which search/episodes and
  downloads/plan take as :url."
  [library]
  (into {} (map (fn [[_ {:keys [feed url]}]] [feed url])) (:feeds library)))

(defn- documents
  "The search documents of the show and each item of `feed` at `url`."
  [feed url]
  (into [(search/feed-document feed {:url url})]
        (map #(search/item-document feed % {:url url}))
        (:rss/items feed)))

(defn- unindexed
  "The `index` without the documents of the feed `guid`."
  [index guid]
  (reduce search/remove index (filter #(or (= guid %) (and (vector? %) (= guid (first %)))) (keys (:docs index)))))

(defn- reindexed
  "The `library` with the feed `guid` indexed again, when it keeps an
  index."
  [library guid]
  (if-let [index (:index library)]
    (let [{:keys [feed url]} (get-in library [:feeds guid])]
      (assoc library :index (reduce search/add (unindexed index guid) (when feed (documents feed url)))))
    library))

(defn- fetch-result
  "The result of a fetch that the `x` given to subscribe stands for. It's
  `x` itself, or a map with `x` as the :feed when `x` is a parsed feed,
  which has namespaced keys."
  [x]
  (if (some #(and (keyword? %) (namespace %)) (keys x))
    {:feed x}
    x))

(defn subscribe
  "The `library` subscribed to the feed at `url`, with what was `fetched`
  and the settings in `opts`.

  The `fetched` is the result of dk.simongray.podcast/fetch! or a parsed
  feed, and the `opts` are as profile/configure takes them, besides :at."
  ([library url fetched]
   (subscribe library url fetched {}))
  ([library url fetched opts]
   {:pre [(map? (:feed (fetch-result fetched))) (map? opts)]}
   (let [now      (time-of library opts)
         settings (dissoc opts :at :now)
         result   (fetch-result fetched)
         feed     (:feed result)
         guid     (feed/feed-guid feed url)]
     (-> library
         (update :feeds feeds/add url result now (:cadence (:settings library) {}))
         (update :profile profile/subscribe guid {:url url :title (resolve/title feed)} now)
         (cond-> (seq settings) (update :profile profile/configure guid settings now))
         (reindexed guid)))))

(defn unsubscribe
  "The `library` unsubscribed from the feed `guid`, with `opts`. The feed
  and its search documents go, and the profile keeps a record of the
  subscription."
  ([library guid]
   (unsubscribe library guid {}))
  ([library guid opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unsubscribe guid (time-of library opts))
       (update :feeds dissoc guid)
       (cond-> (:index library) (update :index unindexed guid)))))

(defn subscriptions
  "The live subscriptions of `library` by guid, each as
  profile/subscriptions gives it, with its :settings filled in by
  profile/settings, and its :feed once it's fetched."
  [library]
  (let [profile (:profile library)]
    (into {} (map (fn [[guid sub]]
                    [guid (cond-> (assoc sub :settings (profile/settings profile guid))
                            (get-in library [:feeds guid]) (assoc :feed (get-in library [:feeds guid :feed])))]))
          (profile/subscriptions profile))))

(defn- listed
  "The `library` with the parse `list` of the OPML list at `url`, fetched
  at `now`, applied as profile/refresh-list does, and kept with the ETag
  and Last-Modified of `fetched`."
  [library url list now {:keys [etag last-modified] :as fetched}]
  (let [seconds (:list-seconds (:settings library))
        entry   (cond-> {:list list :fetched-at now :next-check (time/->inst (+ (inst-ms now) (* 1000 seconds)))}
                  etag          (assoc :etag etag)
                  last-modified (assoc :last-modified last-modified))]
    (-> library
        (update :profile #(:state (profile/refresh-list % url (get-in library [:lists url :list]) list now)))
        (assoc-in [:lists url] entry))))

(defn follow
  "The `library` following the OPML list at `url`, whose parse is `list`,
  with the `opts` below.

  The `opts` give the :etag and :last-modified of the fetch, and the
  :title and :folder of the list as profile/follow-list takes them. The
  shows of the list become subscriptions, which refresh! fetches."
  ([library url list]
   (follow library url list {}))
  ([library url list opts]
   {:pre [(map? list) (map? opts)]}
   (let [now (time-of library opts)]
     (-> library
         (update :profile profile/follow-list url (select-keys opts [:title :folder]) now)
         (listed url list now opts)))))

(defn unfollow
  "The `library` no longer following the list at `url`, with `opts`."
  ([library url]
   (unfollow library url {}))
  ([library url opts]
   {:pre [(map? opts)]}
   (-> library
       (update :profile profile/unfollow-list url (time-of library opts))
       (update :lists dissoc url))))

(defn- fetched-list!
  "Fetch the OPML list at `url` with `opts` and the ETag and Last-Modified
  in its library `entry`, in a future or a promise.

  The result has the :list, :etag and :last-modified, or :unchanged? when
  the list didn't change, or an :error."
  [url {:keys [etag last-modified] :as entry} {:keys [send] :as opts}]
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
  "Fetch the lists of `library` that are due at `now` with `opts`, and
  apply them, in a future or a promise. The result is a map of the
  :library and the :report."
  [library now opts]
  (let [due (for [[url entry] (:lists library)
                  :when (<= (inst-ms (:next-check entry)) (inst-ms now))]
              url)]
    (async/then (async/all (fn [url] (async/then (fetched-list! url (get-in library [:lists url]) opts) #(vector url %))) due)
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
  "The guids and URLs of the live subscriptions of `library` that have no
  feed yet."
  [library]
  (for [[guid {:keys [url]}] (profile/subscriptions (:profile library))
        :when (and url (not (contains? (:feeds library) guid)))]
    [guid url]))

(defn- known
  "The `library` with the feed of `result`, fetched from `url` at `now` for
  the subscription `guid`, which moves to the guid that the feed declares,
  as profile/rekey does."
  [library guid url result now]
  (let [declared (feed/feed-guid (:feed result) url)]
    (-> library
        (update :profile profile/rekey guid declared now)
        (update :feeds feeds/add url result now (:cadence (:settings library) {}))
        (reindexed declared))))

(defn- fetched-new!
  "Fetch the feeds of the new subscriptions of `library` at `now` with
  `opts`, and add them, in a future or a promise.

  The result is a map of the :library and the :report. The :parallel of
  `opts` feeds are fetched at a time, as app.feeds/refresh! fetches them.
  A feed that couldn't be fetched is tried again next time."
  [library now {:keys [fetch credentials-of parallel] :or {parallel feeds/default-parallel} :as opts}]
  (let [common (select-keys opts [:timeout-seconds :max-bytes :user-agent :allow?])
        fetch1 (fn [[guid url]]
                 (let [credentials (when credentials-of (credentials-of guid))]
                   (async/then (async/attempt #(fetch url (cond-> common credentials (assoc :credentials credentials))))
                               (fn [{:keys [value error]}]
                                 [guid url (if error
                                             {:error (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})}
                                             value)]))))]
    (async/then (async/in-groups fetch1 parallel (unfetched library))
                (fn [results]
                  (reduce (fn [acc [guid url result]]
                            (cond
                              (:feed result)  (update acc :library known guid url result now)
                              (:error result) (update acc :report conj (assoc (:error result) :feed-guid guid))
                              :else           (update acc :report conj (diagnostics/describe {:id :fetch/status :url url :status (:status result) :feed-guid guid}))))
                          {:library library :report []}
                          results)))))

(defn- applied
  "The `library` with the `result` of app.feeds/refresh! applied to the
  profile at `now`, as a map of the :library and what was :added and
  :moved."
  [library {:keys [feeds changed moved] :as result} now]
  (let [before (:feeds library)
        library (assoc library :feeds feeds)
        result (reduce (fn [acc guid]
                         (let [{:keys [feed url]} (get feeds guid)
                               {:keys [state added]} (profile/refresh (:profile (:library acc)) (get-in before [guid :feed]) feed url now)]
                           (-> acc
                               (assoc-in [:library :profile] state)
                               (update :library reindexed guid)
                               (update :added into added))))
                       {:library library :added []}
                       changed)]
    (reduce (fn [acc [guid new-url]]
              (let [title (resolve/title (get-in feeds [guid :feed]))]
                (update-in acc [:library :profile] profile/subscribe guid {:url new-url :title title} now)))
            (assoc result :moved moved)
            moved)))

(defn refresh!
  "Fetch and apply whatever `library` is due to fetch, with the `opts`
  below.

  It fetches the followed lists, the feeds of new subscriptions, and the
  feeds whose next check has come. Returns the library with the step under
  :refresh, in ClojureScript as a promise.

      {:at     #inst \"…\"
       :added  [[feed-guid item-guid] …]   ; the new episodes
       :moved  {feed-guid \"https://…\"}    ; the feeds that moved, and so
                                          ; their subscriptions
       :report [...]}                     ; the fetches that failed, and why

  Nothing is thrown for what the network or a server does. The `opts` are
  settings for this call, and :guids, the feeds to fetch instead of those
  that are due, e.g. after a Podping:

      (app.feeds/affected (:feeds library) pings)"
  ([library]
   (refresh! library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [now  (time-of library opts)
         opts (merge {:send http/call!} (options library opts))]
     (async/then (lists-refreshed! library now opts)
                 (fn [{library :library report :report}]
                   (async/then (fetched-new! library now opts)
                               (fn [{library :library report-new :report}]
                                 (async/then (feeds/refresh! (:feeds library) now (select-keys opts [:guids :fetch :credentials-of :parallel :cadence :timeout-seconds :max-bytes :user-agent :allow?]))
                                             (fn [step]
                                               (let [{:keys [library added moved]} (applied library step now)]
                                                 (assoc library :refresh {:at     now
                                                                          :added  added
                                                                          :moved  moved
                                                                          :report (-> [] (into report) (into report-new) (into (:report step)))})))))))))))

(defn episode-id
  "The id of the episode `item` of `feed` in `library`, [feed-guid
  item-guid], as profile/episode-id gives it with the URL of the feed."
  [library feed item]
  (profile/episode-id feed (get (urls library) feed) item))

(defn- item-of
  "The entry of the feed `feed-guid` in `library` and its item
  `item-guid`, as a pair, or nil."
  [library [feed-guid item-guid]]
  (when-let [{:keys [feed] :as entry} (get-in library [:feeds feed-guid])]
    (when-let [item (some #(when (= item-guid (feed/item-guid %)) %) (:rss/items feed))]
      [entry item])))

(defn show
  "The view of the show `guid` of `library`, as app.show/view gives it
  with the `opts` it takes, or nil when the library has no such feed."
  ([library guid]
   (show library guid {}))
  ([library guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds guid])]
     (show/view feed (assoc opts :url url)))))

(defn fetch-linked-shows!
  "Fetch the shows that the show `guid` of `library` links to, with `opts`
  for this call, for a page to merge into the view that show gives.

  The result is that of app.show/fetch-linked-shows! with the settings of
  the library, or nil when the library has no such feed. The `opts` are
  those of app.show/fetch-linked-shows!."
  ([library guid]
   (fetch-linked-shows! library guid {}))
  ([library guid opts]
   (when-let [{:keys [feed url]} (get-in library [:feeds guid])]
     (show/fetch-linked-shows! (show/view feed {:url url :except #{:notes :text :episodes :seasons :links}})
                               (options library opts)))))

(defn episode
  "The view of the episode `id` of `library`, as app.episode/view gives it
  with `opts`.

  The `id` is [feed-guid item-guid], and the view uses the :languages
  of the settings. Returns nil when the library has no such episode."
  ([library id]
   (episode library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (episode/view feed item (assoc (options library opts) :url url)))))

(defn read-files!
  "Read the files of the episode `id` of `library`, with `opts` for this
  call, for a page to merge into the view that episode gives.

  The result is that of app.episode/read-files! with the settings of the
  library, or nil when the library has no such episode. The `opts` are
  those of app.episode/read-files!."
  ([library id]
   (read-files! library id {}))
  ([library id opts]
   (when-let [[{:keys [feed url]} item] (item-of library id)]
     (episode/read-files! (episode/view feed item (assoc (options library opts) :url url :except #{:notes :text :links}))
                          (options library opts)))))

(defn episodes
  "The episodes of the shows of `library` that `opts` select, as [feed
  item] pairs.

  The selection and the order are those of search/episodes across all
  the feeds. The criteria read the profile of the library, and the
  time of its clock unless the `opts` give :at.

      (episodes library {:where {:status :unplayed} :order :listening})"
  ([library]
   (episodes library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (search/episodes (map :feed (vals (:feeds library)))
                    (assoc opts :profile (:profile library) :url (urls library) :at (time-of library opts)))))

(defn search
  "The episodes and shows of `library` that match the query `q`, best
  first, with the `opts` of search/query.

  The library has an index when its settings have :search?, and
  without one, this returns nil."
  ([library q]
   (search library q {}))
  ([library q opts]
   (some-> (:index library) (search/query q opts))))

(defn step
  "The `library` after the player `event`, with `opts`, both as
  profile/step takes them.

  It has the new profile, the :effects to apply, and what's left of
  the :sleep timer of this device. The `opts` go over the :sleep of
  the library."
  ([library event]
   (step library event {}))
  ([library event opts]
   {:pre [(map? opts)]}
   (let [now  (time-of library opts)
         opts (merge (select-keys library [:sleep]) opts)
         {:keys [state effects sleep]} (profile/step (:profile library) event now opts)]
     (cond-> (assoc (dissoc library :sleep) :profile state :effects effects)
       sleep (assoc :sleep sleep)))))

(defn update-profile
  "The `library` with its profile replaced by what `f` gives for it, the
  `args` and the time of the clock, in that order.

  It suits most functions of app.profile, e.g. (update-profile library
  profile/enqueue id). For a series of changes at one time, give a
  function of the profile and the time:

      (update-profile library (fn [state now]
                                (-> state
                                    (profile/tag guid \"Daily\" now)
                                    (profile/star id now))))"
  [library f & args]
  (update library :profile #(apply f % (concat args [(time-of library {})]))))

(defn sync!
  "Sync `library` with the listener's other devices through `backends`,
  with the `opts` of sync/sync! except :ctx.

  It syncs as sync/sync! does, keeps the cursors for the next sync, and
  compacts the tombstones. Returns the library with the step under
  :sync, in ClojureScript as a promise.

      {:ctx      {…}         ; the cursors of the stores, for the next step
       :at       #inst \"…\"
       :clock    #inst \"…\"   ; the clock of the profile after the step
       :failures 0           ; the steps in a row that failed
       :failed   {…}         ; the backends that failed, when any did
       :report   [...]}"
  ([library backends]
   (sync! library backends {}))
  ([library backends opts]
   {:pre [(map? opts)]}
   (let [now    (time-of library opts)
         before (:profile library)]
     (async/then (sync/sync! before backends now (assoc opts :ctx (get-in library [:sync :ctx])))
                 (fn [{:keys [state ctx failed report]}]
                   (let [profile  (-> before (profile/merge state) (profile/compact now))
                         failures (if (seq failed) (inc (get-in library [:sync :failures] 0)) 0)]
                     (assoc library
                            :profile profile
                            :sync (cond-> {:ctx ctx :at now :clock (:clock profile) :failures failures :report report}
                                    (seq failed) (assoc :failed failed)))))))))

(defn next-sync
  "When `library` should sync again, with `opts`, as sync/next-at says:
  right away before the first step, and sooner when the profile changed
  since the last step."
  ([library]
   (next-sync library {}))
  ([library opts]
   {:pre [(map? opts)]}
   (let [{:keys [at clock failures]} (:sync library)]
     (sync/next-at (when at {:at at :changed? (not= clock (:clock (:profile library))) :failures failures})
                   (time-of library opts)))))

(defn plan
  "The episodes to download and the files to delete on this device with
  `files`. It's what downloads/plan gives for the profile and feeds of
  `library`, with the `opts`."
  ([library files]
   (plan library files {}))
  ([library files opts]
   {:pre [(map? opts)]}
   (downloads/plan (:profile library) (map :feed (vals (:feeds library))) files (time-of library opts)
                   (assoc opts :url (urls library)))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast :as podcast])
     (def url "https://feeds.podcastindex.org/pc20.xml")

     (def lib (-> (make "dev-laptop" {:name "Laptop" :settings {:languages ["da" "en"]}})
                  (subscribe url (podcast/fetch! url {:max-pages 2}) {:auto-queue? true})))

     (keys (subscriptions lib))
     (map (comp :title second) (episodes lib {:limit 3}))
     (select-keys (show lib (first (keys (subscriptions lib)))) [:title :author])

     ;; the new episodes since the last fetch, and the moves
     (:refresh (refresh! lib))

     ;; the newest episode, played a minute in
     (def id (let [[feed item] (first (episodes lib {:order :newest :limit 1}))]
               (episode-id lib feed item)))
     (:effects (step lib [:progress id 60 3600]))
     (select-keys (merge (episode lib id) (read-files! lib id)) [:title :duration :chapters])
     #_.))
