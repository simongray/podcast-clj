(ns dk.simongray.podcast.downloads
  "The episodes to download and the files to delete on a device, by the
  listener's rules.

  Downloading, deleting and choosing when are up to you. The rules are the
  kinds that podcast apps have, and a rule selects its episodes with the
  options of criteria/episodes, e.g.:

      {:download? true
       :episodes  {:where {:duration [600 nil] :not {:text #{\"rerun\"}}}
                   :limit 3}
       :played    86400}

  The rules sync in the profile, and a device can have rules of its own."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]))

(def keeps
  "What the :keep of a rule can name, whose files stay on the device:

  - :starred, an episode with a star
  - :queued, an episode in the queue
  - :playlists, an episode in a playlist
  - :in-progress, an episode that the listener started and didn't end"
  #{:starred :queued :playlists :in-progress})

(def default-rule
  "The rule of a show until the listener gives another:

  - :download?, whether the episodes of the show are downloaded
  - :episodes, the :where, :order, :limit and :scale of criteria/episodes
    that select them
  - :played, the seconds that the file of a played episode stays, where 0
    deletes it at once and nil keeps it
  - :inactive, the seconds that a file stays while its episode isn't
    played, counted from the download or the last reported position
  - :trim?, whether a file goes when :episodes no longer selects it
  - :keep, a set of the names in keeps whose files stay"
  {:download? false
   :episodes  {:order :newest}
   :played    nil
   :inactive  nil
   :trim?     false
   :keep      #{:starred :queued}})

(def default-rules
  "The rules of a listener until the listener gives others:

  - :default, the rule of a show without one, with the keys of
    default-rule
  - :shows, the rules of the shows by feed guid
  - :queue, how many episodes at the front of the queue to download, or
    true for all of them
  - :max-episodes and :max-bytes, the most files and bytes on the device
  - :make-room?, whether the files of played episodes go when a download
    needs room"
  {:default      {}
   :shows        {}
   :queue        nil
   :max-episodes nil
   :max-bytes    nil
   :make-room?   false})

(def bytes-per-second
  "The bytes of one second of an episode whose feed gives neither its
  length nor its bitrate, for :audio at 128 kbit/s and :video at 1 Mbit/s,
  unless the :estimate of the options says otherwise."
  {:audio 16000 :video 125000})

(defn rules
  "The rules that `profile` holds, over default-rules: the listener's
  preference :downloads, with the setting :downloads of each show as its
  rule."
  [profile]
  (let [listener (:downloads (profile/preferences profile))
        rule-of  #(:downloads (profile/settings profile %))
        shows    (into {} (for [feed-guid (keys (profile/subscriptions profile))
                                :let  [rule (rule-of feed-guid)]
                                :when (some? rule)]
                            [feed-guid rule]))]
    (if (or (nil? listener) (map? listener))
      (assoc (merge default-rules listener) :shows shows)
      listener)))

(defn rule
  "The rule of the show `feed-guid` in `rules`: the rule of the show, over
  the :default of the rules, over default-rule."
  [rules feed-guid]
  (let [part    #(when (map? %) %)
        default (part (:default rules))
        own     (part (get (part (:shows rules)) feed-guid))
        merged  (merge default-rule default own)]
    (cond-> merged
      (map? (:episodes merged))
      (update :episodes #(merge (:episodes default-rule) %)))))

(defn- amount?
  [x]
  (and (number? x)
       (== x x)
       (not (neg? x))))

(defn- rule-problem
  "A description of what this version of podcast-clj doesn't know in
  `rule`, or nil."
  [{:keys [episodes played inactive] :as rule}]
  (let [unknown (remove #(contains? default-rule %) (keys rule))
        kept    (:keep rule)]
    (cond
      (seq unknown)
      (str "the keys " (str/join ", " (map pr-str unknown)) " are not known")

      (not (or (nil? episodes) (map? episodes)))
      (str "the value of :episodes is " (pr-str episodes) ", not a map")

      (criteria/problem (or episodes {}))
      (criteria/problem (or episodes {}))

      (not (or (nil? played) (amount? played)))
      (str "the value of :played is " (pr-str played)
           ", not a number of seconds")

      (not (or (nil? inactive) (amount? inactive)))
      (str "the value of :inactive is " (pr-str inactive)
           ", not a number of seconds")

      (not (or (nil? kept) (and (set? kept) (every? keeps kept))))
      (str "the value of :keep is " (pr-str kept) ", not a set of "
           (str/join ", " (sort keeps))))))

(def ^:no-doc checks
  "The tests of the values of the rules, by key."
  {:queue        #(or (nil? %) (boolean? %) (amount? %))
   :max-episodes #(or (nil? %) (amount? %))
   :max-bytes    #(or (nil? %) (amount? %))
   :make-room?   #(or (nil? %) (boolean? %))
   :default      #(or (nil? %) (map? %))
   :shows        #(or (nil? %) (map? %))})

(defn- show-problem
  "A description of what this version of podcast-clj doesn't know in
  `own`, the rule of the show `feed-guid` in `rules`, or nil."
  [rules feed-guid own]
  (if (map? own)
    (rule-problem (rule rules feed-guid))
    (str "the rule is " (pr-str own) ", not a map")))

(defn problems
  "A description of what in `rules` this version of podcast-clj doesn't
  know, by place, or nil.

  A place is a key of the rules, or the feed guid of a show. The rules can
  come from a device with a later version. For a show whose rule has a
  problem, a plan does nothing, and for a problem elsewhere, it does
  nothing at all."
  [rules]
  (if-not (map? rules)
    {:rules (str "the rules are " (pr-str rules) ", not a map")}
    (let [shows (when (map? (:shows rules)) (:shows rules))]
      (not-empty
       (into {}
             (concat
              (for [k     (keys rules)
                    :when (not (contains? default-rules k))]
                [k (str "the key " (pr-str k) " is not known")])
              (for [[k ok?] checks
                    :let  [v (get rules k)]
                    :when (not (ok? v))]
                [k (str "the value of " (pr-str k) " is " (pr-str v))])
              (when-let [problem (rule-problem (rule rules nil))]
                [[:default problem]])
              (for [[feed-guid own] shows
                    :let  [problem (show-problem rules feed-guid own)]
                    :when problem]
                [feed-guid problem])))))))

(defn estimate
  "The bytes that the `enclosure` of `item` takes when its feed gives no
  length: the duration at the bitrate of the enclosure, or else at
  bytes-per-second, and 0 without a duration."
  [item {:keys [bitrate] :as enclosure}]
  (let [kind (if (resolve/video? enclosure) :video :audio)
        rate (if (and (number? bitrate) (pos? bitrate))
               (/ bitrate 8)
               (bytes-per-second kind))]
    (long (* rate (or (resolve/duration item) 0)))))

(defn- held
  "The episode ids of `profile` under each name in keeps, e.g. the starred
  ones under :starred."
  [profile]
  (let [started? (fn [[_ {:keys [position played?]}]]
                   (and (not played?)
                        (number? position)
                        (pos? position)))]
    {:starred     (set (profile/starred profile))
     :queued      (set (profile/queue profile))
     :playlists   (into #{} cat (vals (profile/playlists profile)))
     :in-progress (into #{}
                        (comp (filter started?) (map first))
                        (profile/episode-entries profile))}))

(defn- context
  "The context of a plan, from `profile`, the device's `files`, the
  instant `at` and the `opts` of the plan.

  It has what the criteria need: the profile as :profile, the instant :at
  and the :url of a feed. It also has the :files, the :rules
  with their :problems, and the :held episodes."
  [profile files at opts]
  (let [rules (:rules opts (rules profile))]
    (merge (select-keys opts [:scale :enclosure :estimate])
           {:profile  profile
            :at       at
            :url      (:url opts resolve/self-url)
            :files    (or files {})
            :rules    (if (map? rules) (merge default-rules rules) rules)
            :problems (problems rules)
            :held     (held profile)})))

(defn- guid-of
  [{:keys [url]} feed]
  (guid/feed-guid feed (if (ifn? url) (url feed) url)))

(defn- selections
  "The ids of the episodes that the rule in `ctx` of each show of `feeds`
  selects, in order, by feed guid.

  Shows without a feed guid are left out, and so are those whose rule has
  a problem or neither downloads nor trims."
  [{:keys [rules problems] :as ctx} feeds]
  (let [criteria #(select-keys % [:where :order :limit :scale])]
    (into {}
          (for [feed feeds
                :let  [feed-guid (guid-of ctx feed)
                       own       (rule rules feed-guid)]
                :when (and feed-guid
                           (or (:download? own) (:trim? own))
                           (not (contains? problems feed-guid)))
                :let  [options (merge ctx (criteria (:episodes own)))
                       found   (criteria/episodes feed options)]]
            [feed-guid (mapv #(vector feed-guid (guid/item-guid %)) found)]))))

(defn- catalogue
  "The items of `feeds` with the episode `ids`, by id, with the :url of
  `ctx` for feeds without a guid."
  [ctx feeds ids]
  (let [wanted (update-vals (group-by first ids) #(into #{} (map second) %))]
    (into {}
          (for [feed feeds
                :let [feed-guid  (guid-of ctx feed)
                      item-guids (get wanted feed-guid)]
                :when (and feed-guid item-guids)
                item (:rss/items feed)
                :let [item-guid (guid/item-guid item)]
                :when (and item-guid (contains? item-guids item-guid))]
            [[feed-guid item-guid] item]))))

(defn- free?
  "Whether the rules of `ctx` allow deleting the `file` of the episode
  `id`. They don't when the file is gone or has :keep?, when the show's
  rule has a problem, or when its :keep covers the episode."
  [{:keys [rules problems held] :as ctx} id file]
  (not (or (:removed? file)
           (:keep? file)
           (contains? problems (first id))
           (let [kept (:keep (rule rules (first id)))]
             (some #(contains? (get held %) id) kept)))))

(defn- instants
  [xs]
  (seq (map inst-ms (filter inst? xs))))

(defn- played-ms
  "When the listener played the episode of the entry `e` of a profile, in
  milliseconds: its :played-at, or else its stamp."
  [{:keys [played-at updated-at] :as e}]
  (first (instants [played-at updated-at])))

(defn- expired
  "The reason that the show's rule in `ctx` deletes the `file` of the
  episode `id`, given the ids it `selected`, or nil to keep the file.

  The reason is :played, :inactive or :trimmed. The `selected` ids are nil
  for a show whose feed wasn't given."
  [{:keys [profile rules at] :as ctx} selected id file]
  (let [{:keys [played inactive trim?]} (rule rules (first id))
        e      (profile/episode-entry profile id)
        {:keys [played? reported-at updated-at]} e
        past?  (fn [from seconds]
                 (and from
                      seconds
                      (<= (+ from (* 1000 seconds)) (inst-ms at))))
        active (some->> (instants [(:at file) reported-at updated-at])
                        (apply max))]
    (cond
      (and played? (past? (played-ms e) played))         :played
      (and (not played?) (past? active inactive))        :inactive
      (and trim? selected (not (contains? selected id))) :trimmed)))

(defn- gone
  "The plan entry for deleting the `file` of the episode `id`, for
  `reason`."
  [id file reason]
  (cond-> {:id id :reason reason}
    (:bytes file) (assoc :bytes (:bytes file))))

(defn- deletes
  "The plan entries for the files of `ctx` that their shows' rules delete,
  in order of id. The `selected` ids are those that each rule selects, by
  feed guid."
  [{:keys [files] :as ctx} selected]
  (let [selected (update-vals selected set)]
    (for [[id file] (criteria/sorted-by (comp pr-str key) files)
          :when (free? ctx id file)
          :let [reason (expired ctx (get selected (first id)) id file)]
          :when reason]
      (gone id file reason))))

(defn- spare
  "The plan entries for the `files` that can go to make room for a
  download, the earliest played first. They're the files of played
  episodes that the rules of `ctx` allow deleting."
  [{:keys [profile] :as ctx} files]
  (let [spare?   (fn [[id file]]
                   (and (profile/played? profile id)
                        (free? ctx id file)))
        earliest (fn [[id _]]
                   (let [e (profile/episode-entry profile id)]
                     [(or (played-ms e) 0) (pr-str id)]))]
    (->> files
         (filter spare?)
         (criteria/sorted-by earliest)
         (mapv (fn [[id file]] (gone id file :room))))))

(defn- dismissed?
  "Whether the listener took the episode `id` out of the inbox of `profile`
  without queueing it."
  [profile id]
  (boolean (and (:removed? (get-in profile [:inbox id]))
                (not (profile/queued? profile id)))))

(defn- wanted?
  "Whether to download the episode `id`, by the profile and files of `ctx`.
  It's false when the device has or had the file, or when the listener
  played or dismissed the episode."
  [{:keys [profile files] :as ctx} id]
  (not (or (contains? files id)
           (profile/played? profile id)
           (dismissed? profile id))))

(defn- download
  "The plan entry for downloading the `item` of the episode `id` for
  `reason`, with the options in `ctx`, or nil without a media file.

  The file is the enclosure that the :enclosure of `ctx` selects among
  those that can be downloaded. The bytes are the length in the feed, or
  else what the :estimate of `ctx` gives for the item and the enclosure."
  [ctx id item reason]
  (let [opts      (update (:enclosure ctx) :playable-pred
                          #(every-pred resolve/downloadable? (or % any?)))
        enclosure (some-> item (resolve/enclosure opts))
        estimator (:estimate ctx estimate)]
    (when-let [{:keys [url type length]} enclosure]
      (cond-> {:id     id
               :url    url
               :bytes  (if (and (number? length) (pos? length))
                         length
                         (estimator item enclosure))
               :reason reason}
        type (assoc :type type)))))

(defn- front
  "The episode ids at the front of the queue of `profile` that the rules'
  `queue` value asks for: all, a number of them, or none."
  [profile queue]
  (cond
    (true? queue)   (profile/queue profile)
    (number? queue) (vec (take queue (profile/queue profile)))
    :else           []))

(defn- downloads
  "The downloads that the rules of `ctx` ask for, the most wanted first,
  given the `selected` ids by feed guid and the `feeds` of the episodes.

  These are the front of the queue, in order, and then the episodes that
  the shows' rules select, the newest first."
  [{:keys [profile rules] :as ctx} selected feeds]
  (let [queued     (front profile (:queue rules))
        downloads? (fn [[feed-guid _]] (:download? (rule rules feed-guid)))
        chosen     (->> selected
                        (filter downloads?)
                        (mapcat val)
                        (remove (set queued))
                        distinct)
        items      (catalogue ctx feeds (concat queued chosen))
        newest     (fn [id]
                     (let [ms (some-> (get items id) resolve/published inst-ms)]
                       [(- (or ms 0)) (pr-str id)]))
        entry      (fn [reason id]
                     (when (wanted? ctx id)
                       (download ctx id (get items id) reason)))]
    (concat (keep #(entry :queue %) queued)
            (keep #(entry :rule %) (criteria/sorted-by newest chosen)))))

(defn- over
  "How far `total` and one more file of `bytes` go over the limits of
  `rules`, as a map of :episodes and :bytes, each 0 within its limit. The
  `total` is a map of the same shape."
  [{:keys [max-episodes max-bytes] :as rules} total bytes]
  {:episodes (if max-episodes
               (max 0 (- (inc (:episodes total)) max-episodes))
               0)
   :bytes    (if max-bytes
               (max 0 (- (+ (:bytes total) bytes) max-bytes))
               0)})

(defn- room
  "The fewest files from the start of `spare` that free the `need`, a map
  of :episodes and :bytes, or nil when all of `spare` isn't enough."
  [spare {:keys [episodes bytes] :as need}]
  (let [freed (reductions + 0 (map #(:bytes % 0) spare))]
    (some (fn [[n sum]]
            (when (and (>= n episodes) (>= sum bytes))
              (take n spare)))
          (map vector (range) freed))))

(defn- placed
  "The `plan` with the download `entry` added within the limits of
  `rules`.

  With room for the file, the entry goes under :download. With
  :make-room?, files from :spare move to :delete until it fits. Otherwise
  the entry goes under :waiting, with the limit that stopped it as
  :limit."
  [rules {:keys [total spare] :as plan} entry]
  (let [need  (over rules total (:bytes entry))
        clear (cond
                (every? zero? (vals need)) []
                (:make-room? rules)        (room spare need))]
    (if clear
      (-> plan
          (update :download conj entry)
          (update :delete into clear)
          (assoc :spare (subvec spare (count clear))
                 :total {:episodes (- (inc (:episodes total)) (count clear))
                         :bytes    (- (+ (:bytes total) (:bytes entry))
                                      (reduce + (map #(:bytes % 0) clear)))}))
      (let [limit (if (pos? (:episodes need)) :max-episodes :max-bytes)]
        (update plan :waiting conj (assoc entry :limit limit))))))

(defn- planned
  "The plan for the context `ctx` and the `feeds`."
  [{:keys [files rules] :as ctx} feeds]
  (let [selected  (selections ctx feeds)
        going     (vec (deletes ctx selected))
        on-device (into {} (remove (comp :removed? val)) files)
        staying   (apply dissoc on-device (map :id going))
        total     {:episodes (count staying)
                   :bytes    (reduce + (map #(:bytes % 0) (vals staying)))}
        start     {:download []
                   :delete   going
                   :waiting  []
                   :spare    (spare ctx staying)
                   :total    total}]
    (-> (reduce #(placed rules %1 %2) start (downloads ctx selected feeds))
        (dissoc :spare :total))))

(defn plan
  "The plan at `at` for a device with `files`: the episodes of `feeds` to
  download and the files to delete, by the rules in `profile` and `opts`.

  The `feeds` are the parsed feeds of the shows, or one feed, and `files`
  are the files on the device by episode id:

      {id {:bytes 51093220 :at #inst \"2026-09-01\"}}

  The :at of a file is when it was downloaded. No rule deletes a file with
  :keep?, and a file that's gone has :removed?. Keep that entry, since no
  episode with an entry is downloaded again. The result is:

      {:download [{:id id :url \"…\" :bytes 48211004 :reason :rule …}]
       :delete   [{:id id :bytes 51093220 :reason :played}]
       :waiting  [{:id id :url \"…\" :bytes 912004113 :limit :max-bytes …}]}

  The downloads come in this order, with these reasons:

  - :queue, the front of the queue
  - :rule, what the rules of the shows select, newest first

  Played or dismissed episodes aren't downloaded, and downloads without
  room within :max-episodes and :max-bytes wait. A file is deleted for one
  of these reasons:

  - :played, the episode was played :played seconds ago
  - :inactive, it wasn't played for :inactive seconds
  - :trimmed, the rule no longer selects it, and has :trim?
  - :room, a download needs the room, and the rules have :make-room?

  The `opts` are:

  - :rules, instead of the rules in the profile
  - :url, the URL of a feed without a podcast:guid, as a string or a
    function of the feed
  - :enclosure, the options of resolve/enclosure, which selects the file
    of an episode among those that resolve/downloadable? takes
  - :estimate, a function of an item and its enclosure that gives the
    bytes of a file whose feed gives no length
  - :scale, the scale of the criterion :rating

  When the rules have a problem, the result describes it under
  :problems."
  ([profile feeds files at]
   (plan profile feeds files at {}))
  ([profile feeds files at opts]
   {:pre [(map? opts)]}
   (let [{:keys [rules problems] :as ctx} (context profile files at opts)
         shows   (when (map? rules) (:shows rules))
         global? (some #(not (contains? shows %)) (keys problems))]
     (cond-> (if global?
               {:download [] :delete [] :waiting []}
               (planned ctx (if (map? feeds) [feeds] feeds)))
       problems (assoc :problems problems)))))

(comment
  (def show
    {:podcast/guid "feed-1"
     :rss/items    [{:rss/title     "Episode 3"
                     :rss/guid      {:content "ep-3"}
                     :rss/pub-date  #inst "2026-09-22"
                     :rss/enclosure {:url    "https://example.org/3.mp3"
                                     :type   "audio/mpeg"
                                     :length 48211004}}
                    {:rss/title     "Episode 2 (rerun)"
                     :rss/guid      {:content "ep-2"}
                     :rss/pub-date  #inst "2026-09-15"
                     :rss/enclosure {:url    "https://example.org/2.mp3"
                                     :type   "audio/mpeg"
                                     :length 51093220}}
                    {:rss/title     "Episode 1"
                     :rss/guid      {:content "ep-1"}
                     :rss/pub-date  #inst "2026-09-08"
                     :rss/enclosure {:url    "https://example.org/1.mp3"
                                     :type   "audio/mpeg"
                                     :length 50117331}}]})

  ;; the newest two episodes without a rerun, and a played file goes after
  ;; a day
  (def profile
    (-> (profile/make "phone")
        (profile/subscribe "feed-1"
                           {:url "https://example.org/feed.xml" :title "A show"}
                           #inst "2026-09-01")
        (profile/prefer {:downloads {:default      {:played 86400}
                                     :max-episodes 50}}
                        #inst "2026-09-01")
        (profile/configure "feed-1"
                           {:downloads
                            {:download? true
                             :episodes  {:where {:not {:text #{"rerun"}}}
                                         :limit 2}}}
                           #inst "2026-09-01")
        (profile/mark-played ["feed-1" "ep-1"] #inst "2026-09-20")))

  (rules profile)
  (rule (rules profile) "feed-1")
  (problems (rules profile))

  (plan profile [show]
        {["feed-1" "ep-1"] {:bytes 50117331 :at #inst "2026-09-09"}}
        #inst "2026-09-29")

  ;; rules of this device in the place of those of the profile
  (plan profile [show] {} #inst "2026-09-29"
        {:rules (assoc (rules profile) :queue 3 :max-bytes 60000000)})
  #_.)
