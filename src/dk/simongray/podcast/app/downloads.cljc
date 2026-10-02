(ns dk.simongray.podcast.app.downloads
  "The episodes to download and the files to delete on a device, by the
  listener's rules.

  Since plan is a pure function, downloading, deleting and choosing when
  are up to you. The rules are the kinds that podcast apps have, and a
  rule selects its episodes with the options of app.search/episodes, as a
  saved filter does:

      {:download? true
       :episodes  {:where {:duration [600 nil] :not {:text #{\"rerun\"}}}
                   :limit 3}
       :played    86400}

  The rules sync in the player state, and a device with rules of its own
  gives them to plan as :rules."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.search :as search]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]))

(def keeps
  "What the :keep of a rule can name, whose files stay on the device:

  - :starred, an episode with a star
  - :queued, an episode in the queue
  - :playlists, an episode in a playlist
  - :in-progress, an episode that the listener started and didn't end"
  #{:starred :queued :playlists :in-progress})

(def default-rule
  "The rule of a show until the listener gives another:

  - :download?, whether plan downloads the episodes of the show
  - :episodes, the :where, :order, :limit and :scale of search/episodes
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
  {:default {} :shows {} :queue nil :max-episodes nil :max-bytes nil :make-room? false})

(def bytes-per-second
  "The bytes of one second of an episode whose feed gives no length, audio
  at 128 kbit/s, unless :estimate in the options of plan says otherwise."
  16000)

(defn rules
  "The rules that `state` holds, over default-rules: the listener's
  preference :downloads, with the setting :downloads of each show as its
  rule, as player/prefer and player/configure write them."
  [state]
  (let [listener (:downloads (player/preferences state))
        shows    (into {} (for [feed-guid (keys (player/subscriptions state))
                                :let [rule (:downloads (player/settings state feed-guid))]
                                :when (some? rule)]
                            [feed-guid rule]))]
    (if (or (nil? listener) (map? listener))
      (assoc (merge default-rules listener) :shows shows)
      listener)))

(defn rule
  "The rule of the show `feed-guid` in `rules`: the rule of the show, over
  the :default of the rules, over default-rule."
  [rules feed-guid]
  (let [part   #(when (map? %) %)
        merged (merge default-rule (part (:default rules)) (part (get (part (:shows rules)) feed-guid)))]
    (cond-> merged
      (map? (:episodes merged)) (update :episodes #(merge (:episodes default-rule) %)))))

(defn- amount?
  [x]
  (and (number? x) (== x x) (not (neg? x))))

(defn- rule-problem
  "A description of what this version of podcast-clj doesn't know in
  `rule`, or nil."
  [{:keys [episodes played inactive] :as rule}]
  (let [unknown (remove #(contains? default-rule %) (keys rule))
        kept    (:keep rule)]
    (cond
      (seq unknown)                              (str "the keys " (str/join ", " (map pr-str unknown)) " are not known")
      (not (or (nil? episodes) (map? episodes))) (str "the value of :episodes is " (pr-str episodes) ", not a map")
      (search/problem (or episodes {}))          (search/problem (or episodes {}))
      (not (or (nil? played) (amount? played)))  (str "the value of :played is " (pr-str played)
                                                      ", not a number of seconds")
      (not (or (nil? inactive) (amount? inactive)))
      (str "the value of :inactive is " (pr-str inactive) ", not a number of seconds")
      (not (or (nil? kept) (and (set? kept) (every? keeps kept))))
      (str "the value of :keep is " (pr-str kept) ", not a set of " (str/join ", " (sort keeps))))))

(def ^:no-doc checks
  "The tests of the values of the rules, by key."
  {:queue       #(or (nil? %) (boolean? %) (amount? %))
   :max-episodes #(or (nil? %) (amount? %))
   :max-bytes    #(or (nil? %) (amount? %))
   :make-room?   #(or (nil? %) (boolean? %))
   :default      #(or (nil? %) (map? %))
   :shows        #(or (nil? %) (map? %))})

(defn problems
  "A description of what in `rules` this version of podcast-clj doesn't
  know, by place, or nil.

  A place is a key of the rules, or the feed guid of a show. The rules can
  come from a device with a later version, so plan does nothing for a
  show whose rule has a problem, and nothing at all for a problem
  elsewhere."
  [rules]
  (if-not (map? rules)
    {:rules (str "the rules are " (pr-str rules) ", not a map")}
    (not-empty
     (into {}
           (concat
            (for [k (keys rules) :when (not (contains? default-rules k))]
              [k (str "the key " (pr-str k) " is not known")])
            (for [[k ok?] checks :when (not (ok? (get rules k)))]
              [k (str "the value of " (pr-str k) " is " (pr-str (get rules k)))])
            (when-let [problem (rule-problem (rule rules nil))]
              [[:default problem]])
            (for [[feed-guid own] (when (map? (:shows rules)) (:shows rules))
                  :let [problem (if (map? own)
                                  (rule-problem (rule rules feed-guid))
                                  (str "the rule is " (pr-str own) ", not a map"))]
                  :when problem]
              [feed-guid problem]))))))

(defn estimate
  "The bytes that the file of `item` takes when its feed gives no length:
  its duration at bytes-per-second, or 0 without a duration."
  [item]
  (long (* bytes-per-second (or (resolve/duration item) 0))))

(defn- held
  "The episode ids of `state` under each name in keeps, e.g. the starred
  ones under :starred."
  [state]
  {:starred     (set (player/starred state))
   :queued      (set (player/queue state))
   :playlists   (into #{} cat (vals (player/playlists state)))
   :in-progress (into #{}
                      (keep (fn [[id {:keys [position played?]}]]
                              (when (and (not played?) (number? position) (pos? position)) id)))
                      (:episodes state))})

(defn- context
  "The map that plan passes to its helpers, from the player `state`, the
  device's `files`, the instant `at` and the `opts` of plan.

  It has what search/where needs: the player state as :player, the
  instant :at and the :url of a feed. It also has the :files, the :rules
  with their :problems, and the :held episodes."
  [state files at opts]
  (let [rules (:rules opts (rules state))]
    (merge (select-keys opts [:scale :enclosure :estimate])
           {:player   state
            :at       at
            :url      (:url opts resolve/self-url)
            :files    (or files {})
            :rules    (if (map? rules) (merge default-rules rules) rules)
            :problems (problems rules)
            :held     (held state)})))

(defn- guid-of
  [{:keys [url]} feed]
  (feed/feed-guid feed (if (fn? url) (url feed) url)))

(defn- selections
  "The ids of the episodes that the rule in `ctx` of each show of `feeds`
  selects, in order, by feed guid.

  Shows without a feed guid are left out, and so are those whose rule has
  a problem or neither downloads nor trims."
  [{:keys [rules problems] :as ctx} feeds]
  (into {}
        (for [feed feeds
              :let [feed-guid (guid-of ctx feed)
                    {:keys [download? trim? episodes]} (rule rules feed-guid)]
              :when (and feed-guid (or download? trim?) (not (contains? problems feed-guid)))
              :let [criteria (select-keys episodes [:where :order :limit :scale])]]
          [feed-guid (mapv #(vector feed-guid (feed/item-guid %))
                           (search/episodes feed (merge ctx criteria)))])))

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
                :let [item-guid (feed/item-guid item)]
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
           (some #(contains? (get held %) id) (:keep (rule rules (first id)))))))

(defn- instants
  [xs]
  (seq (map inst-ms (filter inst? xs))))

(defn- played-ms
  "When the listener played the episode of the player entry `e`, in
  milliseconds: its :played-at, or else its stamp."
  [{:keys [played-at updated-at] :as e}]
  (first (instants [played-at updated-at])))

(defn- expired
  "The reason that the show's rule in `ctx` deletes the `file` of the
  episode `id`, given the ids it `selected`, or nil to keep the file.

  The reason is :played, :inactive or :trimmed. The `selected` ids are nil
  for a show whose feed wasn't given to plan."
  [{:keys [player rules at] :as ctx} selected id file]
  (let [{:keys [played inactive trim?]} (rule rules (first id))
        {:keys [played? reported-at updated-at] :as e} (player/episode player id)
        past?  (fn [from seconds] (and from seconds (<= (+ from (* 1000 seconds)) (inst-ms at))))
        active (some->> (instants [(:at file) reported-at updated-at]) (apply max))]
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
  in order of id, given the ids that each rule selects in `selected`, by
  feed guid."
  [{:keys [files] :as ctx} selected]
  (let [selected (update-vals selected set)]
    (for [[id file] (search/sorted-by (comp pr-str key) files)
          :when (free? ctx id file)
          :let [reason (expired ctx (get selected (first id)) id file)]
          :when reason]
      (gone id file reason))))

(defn- spare
  "The plan entries for the `files` that can go to make room for a
  download: those of played episodes that the rules of `ctx` allow
  deleting, the earliest played first."
  [{:keys [player] :as ctx} files]
  (->> files
       (filter (fn [[id file]] (and (player/played? player id) (free? ctx id file))))
       (search/sorted-by (fn [[id _]] [(or (played-ms (player/episode player id)) 0) (pr-str id)]))
       (mapv (fn [[id file]] (gone id file :room)))))

(defn- dismissed?
  "Whether the listener took the episode `id` out of the inbox of `state`
  without queueing it."
  [state id]
  (boolean (and (:removed? (get-in state [:inbox id])) (not (player/queued? state id)))))

(defn- wanted?
  "Whether to download the episode `id`, by the player and files of
  `ctx`: not when the device has or had the file, or the listener played
  or dismissed the episode."
  [{:keys [player files] :as ctx} id]
  (not (or (contains? files id) (player/played? player id) (dismissed? player id))))

(defn- download
  "The plan entry for downloading the `item` of the episode `id` for
  `reason`, with the options in `ctx`, or nil without a media file.

  The file is the one resolve/enclosure selects with the :enclosure of
  `ctx` as options. The bytes are the length in the feed, or else what the
  :estimate of `ctx` gives for the item."
  [ctx id item reason]
  (when-let [{:keys [url type length]} (some-> item (resolve/enclosure (:enclosure ctx)))]
    (cond-> {:id     id
             :url    url
             :bytes  (if (and (number? length) (pos? length)) length ((:estimate ctx estimate) item))
             :reason reason}
      type (assoc :type type))))

(defn- front
  "The episode ids at the front of the queue of `state` that the rules'
  `queue` value asks for: all, a number of them, or none."
  [state queue]
  (cond
    (true? queue)   (player/queue state)
    (number? queue) (vec (take queue (player/queue state)))
    :else           []))

(defn- downloads
  "The downloads that the rules of `ctx` ask for, the most wanted first,
  given the `selected` ids by feed guid and the `feeds` of the episodes.

  These are the front of the queue, in order, and then the episodes that
  the shows' rules select, the newest first."
  [{:keys [player rules] :as ctx} selected feeds]
  (let [queued (front player (:queue rules))
        chosen (->> selected
                    (filter (fn [[feed-guid _]] (:download? (rule rules feed-guid))))
                    (mapcat val)
                    (remove (set queued))
                    distinct)
        items  (catalogue ctx feeds (concat queued chosen))
        newest (fn [id] [(- (or (some-> (get items id) resolve/published inst-ms) 0)) (pr-str id)])]
    (concat (keep #(when (wanted? ctx %) (download ctx % (get items %) :queue)) queued)
            (keep #(when (wanted? ctx %) (download ctx % (get items %) :rule))
                  (search/sorted-by newest chosen)))))

(defn- over
  "How far `total` and one more file of `bytes` go over the limits of
  `rules`, as a map of :episodes and :bytes, each 0 within its limit. The
  `total` is a map of the same shape."
  [{:keys [max-episodes max-bytes] :as rules} total bytes]
  {:episodes (if max-episodes (max 0 (- (inc (:episodes total)) max-episodes)) 0)
   :bytes    (if max-bytes (max 0 (- (+ (:bytes total) bytes) max-bytes)) 0)})

(defn- room
  "The fewest files from the start of `spare` that free the `need`, as
  over gives it, or nil when all of `spare` isn't enough."
  [spare {:keys [episodes bytes] :as need}]
  (let [freed (reductions + 0 (map #(:bytes % 0) spare))]
    (some (fn [[n sum]] (when (and (>= n episodes) (>= sum bytes)) (take n spare)))
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
      (update plan :waiting conj
              (assoc entry :limit (if (pos? (:episodes need)) :max-episodes :max-bytes))))))

(defn- planned
  "The result of plan for the context `ctx` and the `feeds`."
  [{:keys [files rules] :as ctx} feeds]
  (let [selected (selections ctx feeds)
        going    (vec (deletes ctx selected))
        staying  (apply dissoc (into {} (remove (comp :removed? val)) files) (map :id going))
        start    {:download []
                  :delete   going
                  :waiting  []
                  :spare    (spare ctx staying)
                  :total    {:episodes (count staying)
                             :bytes    (reduce + (map #(:bytes % 0) (vals staying)))}}]
    (-> (reduce #(placed rules %1 %2) start (downloads ctx selected feeds))
        (dissoc :spare :total))))

(defn plan
  "The plan at `at` for a device with `files`: the episodes of `feeds` to
  download and the files to delete, by the rules in `state` and `opts`.

  The `feeds` are the parsed feeds of the shows, or one feed, and `files`
  are the files on the device by episode id:

      {id {:bytes 51093220 :at #inst \"2026-09-01\"}}

  The :at of a file is when it was downloaded. No rule deletes a file with
  :keep?, and a file that's gone has :removed?. Keep that entry, since no
  episode with an entry is downloaded again. The result is:

      {:download [{:id id :url \"…\" :bytes 48211004 :reason :rule …}]
       :delete   [{:id id :bytes 51093220 :reason :played}]
       :waiting  [{:id id :url \"…\" :bytes 912004113 :limit :max-bytes …}]}

  The downloads come in order: the front of the queue first, with the
  reason :queue, then what the rules of the shows select, newest first,
  with the reason :rule. Played or dismissed episodes aren't downloaded,
  and downloads without room within :max-episodes and :max-bytes wait. A
  file is deleted for one of these reasons:

  - :played, the episode was played :played seconds ago
  - :inactive, it wasn't played for :inactive seconds
  - :trimmed, the rule no longer selects it, and has :trim?
  - :room, a download needs the room, and the rules have :make-room?

  The `opts` are:

  - :rules, instead of the rules in the state
  - :url, the URL of a feed without a podcast:guid, as a string or a
    function of the feed
  - :enclosure, the options of resolve/enclosure, which selects the file
    of an episode
  - :estimate, a function of an item that gives the bytes of a file whose
    feed gives no length
  - :scale, the scale of the criterion :rating

  When the rules have a problem, the result describes it under
  :problems."
  ([state feeds files at]
   (plan state feeds files at {}))
  ([state feeds files at opts]
   {:pre [(map? opts)]}
   (let [{:keys [rules problems] :as ctx} (context state files at opts)
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
                     :rss/enclosure {:url "https://example.org/3.mp3" :type "audio/mpeg" :length 48211004}}
                    {:rss/title     "Episode 2 (rerun)"
                     :rss/guid      {:content "ep-2"}
                     :rss/pub-date  #inst "2026-09-15"
                     :rss/enclosure {:url "https://example.org/2.mp3" :type "audio/mpeg" :length 51093220}}
                    {:rss/title     "Episode 1"
                     :rss/guid      {:content "ep-1"}
                     :rss/pub-date  #inst "2026-09-08"
                     :rss/enclosure {:url "https://example.org/1.mp3" :type "audio/mpeg" :length 50117331}}]})

  ;; the newest two episodes without a rerun, and a played file goes after a day
  (def state
    (-> (player/state "phone")
        (player/subscribe "feed-1" {:url "https://example.org/feed.xml" :title "A show"} #inst "2026-09-01")
        (player/prefer {:downloads {:default {:played 86400} :max-episodes 50}} #inst "2026-09-01")
        (player/configure "feed-1"
                          {:downloads {:download? true
                                       :episodes  {:where {:not {:text #{"rerun"}}} :limit 2}}}
                          #inst "2026-09-01")
        (player/mark-played ["feed-1" "ep-1"] #inst "2026-09-20")))

  (rules state)
  (rule (rules state) "feed-1")
  (problems (rules state))

  (plan state [show] {["feed-1" "ep-1"] {:bytes 50117331 :at #inst "2026-09-09"}} #inst "2026-09-29")

  ;; rules of this device in the place of those of the state
  (plan state [show] {} #inst "2026-09-29"
        {:rules (assoc (rules state) :queue 3 :max-bytes 60000000)})
  #_.)
