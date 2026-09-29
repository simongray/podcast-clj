(ns dk.simongray.podcast.app.downloads
  "The episodes to download and the files to delete on a device, by the
  rules of the listener.

  `plan` is a pure function of the player state, the feeds, the files of
  the device and the rules. It reads no clock and no network. The app
  downloads and deletes the files. The app also decides when it does so,
  such as on Wi-Fi only.

  No standard defines such rules, so the rules here are a choice. They
  are the kinds of rule that podcast apps have, and doc/services.md lists
  those: a number of episodes, words to include or to exclude, a minimum
  duration, a limit on the space, and the delete of played episodes.

  A rule selects its episodes with the options of
  `dk.simongray.podcast.app.search/episodes`, as a saved filter does:

      {:download? true
       :episodes  {:where {:duration [600 nil] :not {:text #{\"rerun\"}}}
                   :limit 3}
       :played    86400}

  The rules are data. `rules` reads them from the player state, where
  they sync with the rest of the state. A device with rules of its own
  gives them to `plan` as `:rules`.

  The app keeps the list of its files, because the files are those of one
  device. An entry stays in the list when its file goes, so `plan` does
  not download an episode again."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.search :as search]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.resolve :as resolve]))

(def keeps
  "What the `:keep` of a rule can name. The file of such an episode stays
  on the device:

  - `:starred`, an episode with a star
  - `:queued`, an episode in the queue
  - `:playlists`, an episode in a playlist
  - `:in-progress`, an episode that the listener started and did not end"
  #{:starred :queued :playlists :in-progress})

(def default-rule
  "The rule of a show, until the listener gives another.

  - `:download?` tells whether `plan` downloads episodes of the show.
  - `:episodes` selects the episodes, with the `:where`, the `:order`, the
    `:limit` and the `:scale` of `search/episodes`.
  - `:played` is the number of seconds that the file of a played episode
    stays after the play. 0 deletes it at once, and nil keeps it.
  - `:inactive` is the number of seconds that the file of an episode
    stays while the listener does not play the episode. The time starts
    at the download, and again at each report of a position.
  - `:trim?` tells whether a file goes when `:episodes` selects its
    episode no more.
  - `:keep` names the episodes whose files stay, as a set of `keeps`."
  {:download? false
   :episodes  {:order :newest}
   :played    nil
   :inactive  nil
   :trim?     false
   :keep      #{:starred :queued}})

(def default-rules
  "The rules of a listener, until the listener gives others.

  - `:default` is the rule of a show without a rule of its own. Its keys
    are those of `default-rule`.
  - `:shows` has the rules of the shows, by feed guid.
  - `:queue` is the number of episodes at the front of the queue that
    `plan` downloads. True is each episode of the queue.
  - `:max-episodes` is the highest number of files on the device.
  - `:max-bytes` is the highest number of bytes that the files take.
  - `:make-room?` tells whether the files of played episodes go when a
    download has no room."
  {:default {} :shows {} :queue nil :max-episodes nil :max-bytes nil :make-room? false})

(def bytes-per-second
  "The bytes of one second of an episode whose feed gives no length:
  audio at 128 kbit/s. The option `:estimate` of `plan` gives another
  estimate."
  16000)

(defn rules
  "The rules that `state` holds, over `default-rules`: the preference
  `:downloads` of the listener, with the setting `:downloads` of each
  show as the rule of the show.

  `player/prefer` and `player/configure` write them, so they sync with
  the rest of the state."
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
  the `:default` of the rules, over `default-rule`."
  [rules feed-guid]
  (let [part   #(when (map? %) %)
        merged (merge default-rule (part (:default rules)) (part (get (part (:shows rules)) feed-guid)))]
    (cond-> merged
      (map? (:episodes merged)) (update :episodes #(merge (:episodes default-rule) %)))))

(defn- amount?
  "Whether `x` is a number that is not below zero."
  [x]
  (and (number? x) (== x x) (not (neg? x))))

(defn- rule-problem
  "What in the rule `rule` this version of podcast-clj does not know,
  described. Nil when it knows all of it."
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
  "The test of the value of each key of the rules."
  {:queue       #(or (nil? %) (boolean? %) (amount? %))
   :max-episodes #(or (nil? %) (amount? %))
   :max-bytes    #(or (nil? %) (amount? %))
   :make-room?   #(or (nil? %) (boolean? %))
   :default      #(or (nil? %) (map? %))
   :shows        #(or (nil? %) (map? %))})

(defn problems
  "What in `rules` this version of podcast-clj does not know, described,
  by its place. A place is a key of the rules, or the feed guid of a
  show. Nil when it knows all of it.

  The rules come from a device of the listener, and that device can have
  a later version of podcast-clj. `plan` does nothing for a show whose
  rule has a problem. With a problem in another place, it does nothing
  at all."
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
  "The bytes that the file of `item` takes, for an item whose feed gives
  no length: its duration at `bytes-per-second`. 0 for an item without a
  duration."
  [item]
  (long (* bytes-per-second (or (resolve/duration item) 0))))

(defn- held
  "The ids of the episodes of `state` that each name of `keeps` holds, by
  name."
  [state]
  {:starred     (set (player/starred state))
   :queued      (set (player/queue state))
   :playlists   (into #{} cat (vals (player/playlists state)))
   :in-progress (into #{}
                      (keep (fn [[id {:keys [position played?]}]]
                              (when (and (not played?) (number? position) (pos? position)) id)))
                      (:episodes state))})

(defn- context
  "What the steps of `plan` read. It has the context of `search/where`:
  the player state as `:player`, the instant `:at` and the `:url` of a
  feed. It also has the `:files` of the device, the `:rules` with their
  `:problems`, and what `held` gives as `:held`."
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
  "The feed guid of `feed`, by the `:url` of `ctx`."
  [{:keys [url]} feed]
  (feed/feed-guid feed (if (fn? url) (url feed) url)))

(defn- selections
  "The ids of the episodes that the rule of each show of `feeds` selects,
  in the order of the rule, by feed guid. A feed without an identity has
  none, and a show whose rule has a problem has none. A show whose rule
  does not download and does not trim needs none."
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
  "The items of `feeds`, by the id of the episode."
  [ctx feeds]
  (into {}
        (for [feed feeds
              :let [feed-guid (guid-of ctx feed)]
              :when feed-guid
              item (:rss/items feed)
              :let [item-guid (feed/item-guid item)]
              :when item-guid]
          [[feed-guid item-guid] item])))

(defn- free?
  "Whether the rules of `ctx` can delete the file `file` of the episode
  `id`. They cannot when the file is gone, when the app keeps it, when the
  rule of its show has a problem, or when the `:keep` of the rule holds
  the episode."
  [{:keys [rules problems held]} id file]
  (not (or (:removed? file)
           (:keep? file)
           (contains? problems (first id))
           (some #(contains? (get held %) id) (:keep (rule rules (first id)))))))

(defn- instants
  "The instants among `xs` in milliseconds, nil when there is none."
  [xs]
  (seq (map inst-ms (filter inst? xs))))

(defn- played-ms
  "When the listener played the episode with the player entry `e`, in
  milliseconds: the time of the play, or else the stamp of the entry."
  [{:keys [played-at updated-at]}]
  (first (instants [played-at updated-at])))

(defn- expired
  "Why the file `file` of the episode `id` goes, by the rule of its show
  in `ctx`: :played, :inactive or :trimmed. Nil when the file stays.

  `selected` is the set of the ids that the rule selects. It is nil for a
  show whose feed `plan` did not get."
  [{:keys [player rules at]} selected id file]
  (let [{:keys [played inactive trim?]} (rule rules (first id))
        {:keys [played? reported-at updated-at] :as e} (player/episode player id)
        past?  (fn [from seconds] (and from seconds (<= (+ from (* 1000 seconds)) (inst-ms at))))
        active (some->> (instants [(:at file) reported-at updated-at]) (apply max))]
    (cond
      (and played? (past? (played-ms e) played))         :played
      (and (not played?) (past? active inactive))        :inactive
      (and trim? selected (not (contains? selected id))) :trimmed)))

(defn- gone
  "The entry of a plan for the file `file` of the episode `id`, which goes
  for the reason `reason`."
  [id file reason]
  (cond-> {:id id :reason reason}
    (:bytes file) (assoc :bytes (:bytes file))))

(defn- deletes
  "The files of `ctx` that go by the rules of their shows, in the order of
  their ids. `selected` has the ids that each rule selects, by feed guid."
  [{:keys [files] :as ctx} selected]
  (let [selected (update-vals selected set)]
    (for [[id file] (sort-by (comp pr-str key) files)
          :when (free? ctx id file)
          :let [reason (expired ctx (get selected (first id)) id file)]
          :when reason]
      (gone id file reason))))

(defn- spare
  "The files of `files` that can go to make room for a download: those of
  the played episodes that the rules of `ctx` can delete, the earliest
  played first."
  [{:keys [player] :as ctx} files]
  (->> files
       (filter (fn [[id file]] (and (player/played? player id) (free? ctx id file))))
       (sort-by (fn [[id _]] [(or (played-ms (player/episode player id)) 0) (pr-str id)]))
       (map (fn [[id file]] (gone id file :room)))))

(defn- dismissed?
  "Whether the listener took the episode `id` out of the inbox of `state`
  and did not put it in the queue."
  [state id]
  (boolean (and (:removed? (get-in state [:inbox id])) (not (player/queued? state id)))))

(defn- wanted?
  "Whether `plan` can download the episode `id`. It cannot when the device
  has the file or had it before, or when the listener played or dismissed
  the episode."
  [{:keys [player files]} id]
  (not (or (contains? files id) (player/played? player id) (dismissed? player id))))

(defn- download
  "The entry of a plan for the download of the episode `id`, the item
  `item`, for the reason `reason`. Nil for an item without a media file.

  The file is that of `resolve/enclosure` with the `:enclosure` of `ctx`
  as its options. The bytes are the length that the feed gives, or else
  what the `:estimate` of `ctx` gives for the item."
  [ctx id item reason]
  (when-let [{:keys [url type length]} (some-> item (resolve/enclosure (:enclosure ctx)))]
    (cond-> {:id     id
             :url    url
             :bytes  (if (and (number? length) (pos? length)) length ((:estimate ctx estimate) item))
             :reason reason}
      type (assoc :type type))))

(defn- front
  "The ids of the episodes at the front of the queue of `state` that the
  value `queue` of the rules names."
  [state queue]
  (cond
    (true? queue)   (player/queue state)
    (number? queue) (vec (take queue (player/queue state)))
    :else           []))

(defn- downloads
  "The downloads that the rules of `ctx` ask for, the most wanted first:
  the front of the queue in its order, and then the episodes that the
  rules of the shows select, the newest first.

  `selected` has the ids that each rule selects, by feed guid, and `items`
  has the items of the feeds by id."
  [{:keys [player rules] :as ctx} selected items]
  (let [queued (front player (:queue rules))
        newest (fn [id] [(- (or (some-> (get items id) resolve/published inst-ms) 0)) (pr-str id)])
        chosen (->> selected
                    (filter (fn [[feed-guid _]] (:download? (rule rules feed-guid))))
                    (mapcat val)
                    (remove (set queued))
                    distinct
                    (sort-by newest))]
    (concat (keep #(when (wanted? ctx %) (download ctx % (get items %) :queue)) queued)
            (keep #(when (wanted? ctx %) (download ctx % (get items %) :rule)) chosen))))

(defn- over
  "How far the files of `total` and one more file of `bytes` bytes are over
  the limits of `rules`, as a map with `:episodes` and `:bytes`. `total`
  is such a map. A number is 0 when the files are within the limit."
  [{:keys [max-episodes max-bytes]} total bytes]
  {:episodes (if max-episodes (max 0 (- (inc (:episodes total)) max-episodes)) 0)
   :bytes    (if max-bytes (max 0 (- (+ (:bytes total) bytes) max-bytes)) 0)})

(defn- room
  "The files at the start of `spare` that free what `need` asks, a map of
  `over`, and no more files than that. Nil when all of `spare` frees too
  little."
  [spare {:keys [episodes bytes]}]
  (let [freed (reductions + 0 (map #(:bytes % 0) spare))]
    (some (fn [[n sum]] (when (and (>= n episodes) (>= sum bytes)) (take n spare)))
          (map vector (range) freed))))

(defn- placed
  "The plan `plan` with the download `entry` in it, by the limits of
  `rules`. With room for the file, the entry is under `:download`. With
  `:make-room?`, files of `:spare` go until the file has room. Else the
  entry is under `:waiting`, with the limit that stops it as `:limit`."
  [rules {:keys [total spare] :as plan} entry]
  (let [need  (over rules total (:bytes entry))
        clear (cond
                (every? zero? (vals need)) []
                (:make-room? rules)        (room spare need))]
    (if clear
      (-> plan
          (update :download conj entry)
          (update :delete into clear)
          (assoc :spare (drop (count clear) spare)
                 :total {:episodes (- (inc (:episodes total)) (count clear))
                         :bytes    (- (+ (:bytes total) (:bytes entry))
                                      (reduce + (map #(:bytes % 0) clear)))}))
      (update plan :waiting conj
              (assoc entry :limit (if (pos? (:episodes need)) :max-episodes :max-bytes))))))

(defn- planned
  "The plan for the context `ctx` of `plan` and the feeds `feeds`."
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
    (-> (reduce #(placed rules %1 %2) start (downloads ctx selected (catalogue ctx feeds)))
        (dissoc :spare :total))))

(defn plan
  "The episodes to download and the files to delete on a device at `at`,
  by the rules of the listener in `state`. `feeds` are the parsed feeds
  of the shows, or one feed, and `files` are the files of the device.

      {:download [{:id [feed-guid item-guid] :url \"…\" :type \"audio/mpeg\"
                   :bytes 48211004 :reason :rule}]
       :delete   [{:id [feed-guid item-guid] :bytes 51093220 :reason :played}]
       :waiting  [{:id [feed-guid item-guid] :url \"…\" :bytes 912004113
                   :reason :rule :limit :max-bytes}]}

  `files` has an entry for each file that the device has, downloads or
  had before, by the id of the episode:

      {[feed-guid item-guid] {:bytes 51093220 :at #inst \"2026-09-01\"}}

  `:at` is the time of the download. `:keep? true` is a file that no rule
  deletes, such as a file that the listener asked for. `:removed? true`
  is a file that went. `plan` downloads no episode that has an entry, so
  the app keeps the entry of a file that went.

  The downloads are in the order of their weight. The front of the queue
  comes first, as `:queue` of the rules names it, with the reason :queue.
  Then come the episodes that the rules of the shows select, the newest
  first, with the reason :rule. `plan` downloads no episode that the
  listener played or dismissed.

  A file goes for one of these reasons:

  - `:played`, the listener played the episode `:played` seconds before
  - `:inactive`, the listener did not play it for `:inactive` seconds
  - `:trimmed`, the rule selects the episode no more, and has `:trim?`
  - `:room`, a download needs the room, and the rules have `:make-room?`

  A download without room within `:max-episodes` and `:max-bytes` is
  under `:waiting`. The bytes of a download are the length that the feed
  gives, which can be wrong.

  The rule of a show reads the feed of the show. For a show without a
  feed in `feeds`, the rules delete by `:played` and `:inactive` only.

  `opts`:

  - `:rules`, the rules in the place of those that `rules` reads from
    the state
  - `:url`, the URL of a feed that declares no podcast:guid, as a string
    or a function of the feed. The default is the self link of the feed.
  - `:enclosure`, the options of `resolve/enclosure`, which selects the
    file of an episode
  - `:estimate`, a function of an item that gives the bytes of a file
    whose feed gives no length. The default is `estimate`.
  - `:scale`, the scale of the criterion `:rating`

  With a problem in the rules, as `problems` tells, the result has the
  problems under `:problems`."
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
