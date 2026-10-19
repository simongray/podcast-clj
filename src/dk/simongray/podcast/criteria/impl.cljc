(ns ^:no-doc dk.simongray.podcast.criteria.impl
  "The internals of dk.simongray.podcast.criteria: the tests that the
  criteria are made of, and the context that gives them each feed's guid
  once."
  (:require [clojure.string :as str]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.analysis :as analysis]
            [dk.simongray.drop-in-search.queries :as search.queries]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.langtag :as langtag]
            [dk.simongray.podcast.criteria :as-alias criteria]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.queue :as profile.queue]))

(defn- in?
  [expected actual]
  (if (set? expected) (contains? expected actual) (= expected actual)))

(defn- within?
  "Whether `x` is in the range from `lo` to `hi`, where a nil end is open.
  A nil `x` is in no range."
  [[lo hi] x]
  (boolean (and (some? x)
                (or (nil? lo) (<= lo x))
                (or (nil? hi) (<= x hi)))))

(defn- instants-within?
  "Whether the instant `t` is in the range of instants from `lo` to `hi`,
  where a nil end is open. A nil `t` is in no range."
  [[lo hi] t]
  (within? [(some-> lo inst-ms) (some-> hi inst-ms)] (some-> t inst-ms)))

;; Feeds write en_US for en-US too
(defn- language-match?
  "Whether the language range `r` matches the language tag `tag` by the
  basic filtering of RFC 4647."
  [r tag]
  (langtag/basic-match? (str r) (str/replace (str tag) "_" "-")))

(defn- mentions?
  "Whether the text `s` holds the text `q`, both folded, so that case and
  accents don't count."
  [q s]
  (boolean (some-> s analysis/fold (str/includes? (analysis/fold q)))))

(defn- category-names
  "The categories of `item` of `feed`: those of Apple Podcasts of its show,
  subcategories too, and those that the item or the feed name in RSS."
  [feed item]
  (concat (apply concat (resolve/categories feed))
          (keep :content (:rss/categories item))
          (keep :content (:rss/categories feed))))

;; memoize takes any arguments, so each call makes a seq of them and
;; hashes it, which costs a criterion of every episode a twentieth
(defn memoized
  "The function `f` of one argument, which works each answer out once."
  [f]
  (let [known (volatile! {})]
    (fn [x]
      (if-let [[_ answer] (find @known x)]
        answer
        (let [answer (f x)]
          (vswap! known assoc x answer)
          answer)))))

(defn- url-of
  "The URL of `feed` by the :url of the context, a string, or a function
  of the feed such as a map of feeds to URLs."
  [{:keys [url]} feed]
  (if (ifn? url)
    (url feed)
    url))

(defn with-feed-guids
  "The context `ctx` with a function of a feed that gives its guid and
  works each one out once, since that of a feed without a podcast:guid is
  a hash of its URL."
  [ctx]
  (cond-> ctx
    (not (::criteria/feed-guid ctx))
    (assoc ::criteria/feed-guid (memoized #(guid/feed-key % (url-of ctx %))))))

(defn episode-id
  "The episode id of `item` in `feed`, with the feed URL from `ctx`."
  [ctx feed item]
  (if-let [guid-of (::criteria/feed-guid ctx)]
    [(guid-of feed) (guid/item-guid item)]
    (guid/episode-id feed (url-of ctx feed) item)))

(defn entry
  "The entry of `item` in `feed`, from the profile in `ctx`."
  [ctx feed item]
  (profile/episode-entry (:profile ctx) (episode-id ctx feed item)))

(defn- has-chapters?
  "Whether `item` links, embeds or lists chapters."
  [item]
  (boolean (or (resolve/chapters-url item)
               (resolve/embedded-chapters item)
               (resolve/inferred-chapters item))))

(defn- mentioned
  "The predicate of the criterion :text for `v`, a query or a set of
  queries. It's true when the title and notes of an item match any of
  them, as queries of dk.simongray.drop-in-search."
  [v]
  (let [queries (mapv search.queries/parse (if (set? v) v [v]))]
    (fn [_ item]
      (let [notes (some-> (resolve/description item) html/text)
            text  (str (resolve/title item) "\n" notes)]
        (boolean (some #(search.queries/matches? text %) queries))))))

(defn- indexed
  "The predicate of the criterion :text for `v`, a query or a set of
  queries, with an index of the documents of the episodes under :index in
  `ctx`. It's true when an item's title and notes match any of them."
  [v ctx]
  (let [index  (:index ctx)
        query  #(search/query index % {:fields #{:title :description}
                                       :boosts {:title 1 :description 1}})
        found  (into #{}
                     (comp (mapcat query) (map :id))
                     (if (set? v) v [v]))]
    (fn [feed item]
      (contains? found (episode-id ctx feed item)))))

(defn- age
  "The seconds from the publication of `item` to `at`, at least 0, or nil
  when the item has no date."
  [item at]
  (when-let [published (resolve/published item)]
    (max 0 (/ (- (inst-ms at) (inst-ms published)) 1000.0))))

(defn- since-subscription?
  "Whether `item` of `feed` came out after the listener subscribed to the
  feed, by the profile in `ctx`."
  [ctx feed item]
  (let [[feed-guid] (episode-id ctx feed item)
        added       (get-in ctx [:profile :subscriptions feed-guid :added-at])
        published   (resolve/published item)]
    (boolean (and (inst? added)
                  published
                  (> (inst-ms published) (inst-ms added))))))

(defn- download-state
  "The state of the file of the episode `id` in the `files` of a device,
  the record that dk.simongray.podcast.downloads keeps: :downloaded,
  :downloading, :failed or :none."
  [files id]
  (let [{:keys [finished-at failed-at removed?] :as file} (get files id)]
    (cond
      (or (nil? file) removed?) :none
      finished-at               :downloaded
      failed-at                 :failed
      :else                     :downloading)))

(defn- of-show
  "The predicate of a criterion about the show of an episode, for `v`, a
  value or a set of them, and `f`, a function of the profile in `ctx` and
  a feed guid that gives the values of a show."
  [v ctx f]
  (fn [feed item]
    (let [[feed-guid] (episode-id ctx feed item)]
      (boolean (some #(in? v %) (f (:profile ctx) feed-guid))))))

(declare where where-problem)

(defn names?
  "Whether the criteria `where`, or those under its :not, name `k`."
  [where k]
  (boolean (and (map? where)
                (or (contains? where k)
                    (names? (:not where) k)))))

(defn- hide-rule
  "The predicate of a feed and an item of the :hide-where of the show
  `feed-guid` in the profile of `ctx`, or nil without one, or for one that
  this version doesn't know, names :hidden? itself, or lacks the context
  it needs."
  [ctx feed-guid]
  (let [rule (:hide-where (profile/settings (:profile ctx) feed-guid))]
    (when (and (map? rule)
               (not (names? rule :hidden?))
               (nil? (where-problem rule :where)))
      (try
        (where rule ctx)
        (catch #?(:clj Exception :cljs :default) _
          nil)))))

(defn- hidden?
  "Whether the listener hid `item` of `feed`, or else the :hide-where of its
  show does, by the profile in `ctx`, with the `rules` of each show by
  feed guid."
  [ctx rules feed item]
  (let [[feed-guid :as id] (episode-id ctx feed item)
        own                (profile/hidden (:profile ctx) id)]
    (if (some? own)
      own
      (boolean (when-let [rule (rules feed-guid)]
                 (rule feed item))))))

(def criterion
  "The test of each criterion, a function of the criterion's value and the
  context that returns a predicate of a feed and an item."
  {:season      (fn [v _]
                  (fn [_ item] (in? v (:season (resolve/numbering item)))))
   :episode     (fn [v _]
                  (fn [_ item] (in? v (:episode (resolve/numbering item)))))
   :type        (fn [v _]
                  (fn [_ item] (in? v (:type (resolve/numbering item)))))
   :duration    (fn [v _]
                  (fn [_ item] (within? v (resolve/duration item))))
   :published   (fn [v _]
                  (fn [_ item] (instants-within? v (resolve/published item))))
   :age         (fn [v ctx]
                  (fn [_ item] (within? v (age item (:at ctx)))))
   :transcript? (fn [v _]
                  (fn [feed item]
                    (= (boolean v) (some? (resolve/transcript feed item)))))
   :chapters?   (fn [v _]
                  (fn [_ item] (= (boolean v) (has-chapters? item))))
   :explicit?   (fn [v _]
                  (fn [feed item]
                    (= (boolean v) (boolean (resolve/explicit? feed item)))))
   :person      (fn [v _]
                  (fn [feed item]
                    (boolean (some #(mentions? v (:name %))
                                   (resolve/people feed item)))))
   :author      (fn [v _]
                  (fn [feed item]
                    (mentions? v (or (resolve/author item)
                                     (resolve/author feed)))))
   :location    (fn [v _]
                  (let [place? (fn [{:keys [content country]}]
                                 (or (mentions? v content)
                                     (= (str/lower-case (str v))
                                        (some-> country str/lower-case))))]
                    (fn [feed item]
                      (boolean (some place? (resolve/locations feed item))))))
   :language    (fn [v _]
                  (let [ranges (if (set? v) v #{v})
                        match? (fn [tag]
                                 (some #(language-match? % tag) ranges))]
                    (fn [feed item]
                      (boolean (some match? (resolve/languages feed item))))))
   :category    (fn [v _]
                  (let [wanted (into #{}
                                     (map analysis/fold)
                                     (if (set? v) v #{v}))]
                    (fn [feed item]
                      (boolean (some #(contains? wanted (analysis/fold %))
                                     (category-names feed item))))))
   :medium      (fn [v _]
                  (fn [feed _] (in? v (resolve/medium feed))))
   :serial?     (fn [v _]
                  (fn [feed _] (= (boolean v) (resolve/serial? feed))))
   :complete?   (fn [v _]
                  (fn [feed _] (= (boolean v) (resolve/complete? feed))))
   :show-explicit? (fn [v _]
                     (fn [feed _] (= (boolean v) (boolean (resolve/explicit? feed)))))
   :publisher   (fn [v _]
                  (fn [feed _]
                    (in? v (:feed-guid (resolve/publisher feed)))))
   :license     (fn [v _]
                  (let [wanted (into #{}
                                     (map str/lower-case)
                                     (if (set? v) v #{v}))]
                    (fn [feed item]
                      (contains? wanted
                                 (some-> (resolve/license feed item)
                                         :content
                                         str/lower-case)))))
   :soundbites? (fn [v _]
                  (fn [_ item]
                    (= (boolean v) (boolean (seq (resolve/soundbites item))))))
   :value?      (fn [v _]
                  (fn [feed item]
                    (= (boolean v) (some? (resolve/value feed item)))))
   :comments?   (fn [v _]
                  (fn [feed item]
                    (= (boolean v)
                       (boolean (seq (resolve/social-interacts feed item))))))
   :chat?       (fn [v _]
                  (fn [feed item]
                    (= (boolean v) (some? (resolve/chat feed item)))))
   :funding?    (fn [v _]
                  (fn [feed item]
                    (= (boolean v)
                       (boolean (seq (resolve/funding feed item))))))
   :ai-content? (fn [v _]
                  (fn [feed item]
                    (= (boolean v)
                       (boolean (resolve/ai-content? feed item)))))
   :text        (fn [v ctx]
                  (if (:index ctx)
                    (indexed v ctx)
                    (mentioned v)))
   :not         (fn [v ctx] (complement (where v ctx)))
   :status      (fn [v ctx]
                  (fn [feed item]
                    (in? v (profile/status (entry ctx feed item)))))
   :progress    (fn [v ctx]
                  (fn [feed item]
                    (within? v (or (:position (entry ctx feed item)) 0))))
   :heard       (fn [v ctx]
                  (fn [feed item]
                    (let [e (entry ctx feed item)]
                      (instants-within? v (:reported-at e)))))
   :starred?    (fn [v ctx]
                  (fn [feed item]
                    (let [id (episode-id ctx feed item)]
                      (= (boolean v)
                         (profile.marks/starred? (:profile ctx) id)))))
   :inbox?      (fn [v ctx]
                  (fn [feed item]
                    (let [id (episode-id ctx feed item)]
                      (= (boolean v)
                         (profile.queue/in-inbox? (:profile ctx) id)))))
   :rating      (fn [v ctx]
                  (let [opts (select-keys ctx [:scale])]
                    (fn [feed item]
                      (let [id (episode-id ctx feed item)]
                        (within? v (profile.marks/rating (:profile ctx)
                                                         id
                                                         opts))))))
   :since-subscription?
   (fn [v ctx]
     (fn [feed item]
       (= (boolean v) (since-subscription? ctx feed item))))
   :show        (fn [v ctx]
                  (of-show v ctx (fn [_ feed-guid] [feed-guid])))
   :folder      (fn [v ctx]
                  (of-show v ctx (fn [profile feed-guid]
                                   [(:folder (profile/settings profile
                                                               feed-guid))])))
   :tag         (fn [v ctx]
                  (of-show v ctx profile/tags))
   :media       (fn [v _]
                  (fn [_ item]
                    (boolean (some #(in? v %) (resolve/media-kinds item)))))
   :queued?     (fn [v ctx]
                  (fn [feed item]
                    (let [id (episode-id ctx feed item)]
                      (= (boolean v)
                         (profile.queue/queued? (:profile ctx) id)))))
   :hidden?     (fn [v ctx]
                  (let [rules (memoized #(hide-rule ctx %))
                        v     (if (set? v) v (boolean v))]
                    (fn [feed item]
                      (in? v (hidden? ctx rules feed item)))))
   :download    (fn [v ctx]
                  (fn [feed item]
                    (let [id (episode-id ctx feed item)]
                      (in? v (download-state (:files ctx) id)))))})

(defn where
  "The predicate of a feed and an item for `criteria` in the context
  `ctx`, as dk.simongray.podcast.criteria/where documents it."
  [criteria ctx]
  (let [unknown  (filter #(not (contains? criterion %)) (keys criteria))
        personal #{:status :progress :heard :starred? :inbox? :rating
                   :since-subscription? :folder :tag :queued? :hidden?}]
    (when (seq unknown)
      (throw (ex-info (str "Criteria the library does not know: "
                           (str/join ", " unknown))
                      {:criteria (vec unknown)})))
    (when-let [k (and (not (:profile ctx))
                      (some personal (keys criteria)))]
      (throw (ex-info (str k " needs the profile under :profile")
                      {:criteria criteria})))
    (when (and (contains? criteria :age)
               (not (inst? (:at ctx))))
      (throw (ex-info ":age needs the instant of now under :at"
                      {:criteria criteria})))
    (when (and (contains? criteria :download)
               (not (map? (:files ctx))))
      (throw (ex-info ":download needs the files of the device under :files"
                      {:criteria criteria})))
    (let [ctx   (with-feed-guids ctx)
          tests (mapv (fn [[k v]] ((criterion k) v ctx)) criteria)]
      (fn [feed item]
        (every? #(% feed item) tests)))))

(defn sorted-by
  "The items of `coll` sorted by `f` as sort-by sorts them, but with one
  call of `f` per item rather than two per comparison."
  [f coll]
  (map second (sort-by first (map (juxt f identity) coll))))

(defn where-problem
  "A description of what this version of podcast-clj doesn't know in the
  criteria `where` at `place`, including those under :not."
  [where place]
  (let [unknown (when (map? where)
                  (filter #(not (contains? criterion %)) (keys where)))]
    (cond
      (nil? where)
      nil

      (not (map? where))
      (str "the criteria under " place " are " (pr-str where) ", not a map")

      (seq unknown)
      (str "the criteria " (str/join ", " (map pr-str unknown))
           " are not known")

      :else
      (where-problem (:not where) :not))))
