(ns dk.simongray.podcast.search
  "Finding episodes, by filtering and ordering the items of feeds with
  criteria given as data, or by full-text search.

  The criteria read the model through feed.resolve, so each means what the
  accessor means:

      (episodes feed {:where {:season 2 :type \"full\" :duration [600 nil]}
                      :order :newest})

  For full-text search, item-document and feed-document make the
  documents of episodes and shows, and index makes an index of them for
  util.text.search, which ranks a match in a title above one in the
  notes."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.text :as text]
            [dk.simongray.podcast.util.text.search :as text.search]))

(defn- notes
  [x]
  (some-> (resolve/description x) html/text))

(defn- names
  [feed item]
  (let [people (if item
                 (resolve/people feed item)
                 (resolve/people feed))]
    (str/join " " (map :name people))))

(defn- present
  "The entries of `m` whose value isn't blank or empty."
  [m]
  (let [filled? (fn [[_ v]]
                  (if (string? v)
                    (not (str/blank? v))
                    (seq v)))]
    (into {} (filter filled?) m)))

(defn item-document
  "The search document of `item` in `feed`, with the `opts` below.

  Its fields are :title, :show, :description, :people, :keywords,
  :chapters and :transcript. A result has the title, show, guid,
  publication date, duration and URL. The `opts` are:

  - :url, where the feed was fetched, which a feed without a podcast:guid
    needs, or it throws
  - :chapters, the parsed chapters file, instead of the embedded ones
  - :transcript, the parsed transcript"
  ([feed item]
   (item-document feed item {}))
  ([feed item {:keys [url chapters transcript] :as opts}]
   {:pre [(map? opts)]}
   (let [chapters (or chapters (resolve/embedded-chapters item))
         titles   (keep :title (:chapters chapters))
         title    (resolve/title item)
         show     (resolve/title feed)
         id       (feed/episode-id feed url item)]
     (when (nil? (first id))
       (throw (ex-info (str "A feed without a podcast:guid needs :url, or its "
                            "episodes share ids with those of other shows")
                       {:title show})))
     {:id     id
      :fields (present {:title       title
                        :show        show
                        :description (notes item)
                        :people      (names feed item)
                        :keywords    (:itunes/keywords item)
                        :chapters    (str/join " " titles)
                        :transcript  (some-> transcript transcript/text)})
      :stored (into {} (filter (comp some? val))
                    {:title     title
                     :show      show
                     :guid      (feed/item-guid item)
                     :url       url
                     :published (resolve/published item)
                     :duration  (resolve/duration item)})})))

(defn feed-document
  "The search document of the show `feed` itself, by its guid, with the
  :url in `opts` where the feed was fetched, for a feed without a
  podcast:guid.

  Its fields are :title, :description, :author, :people and :categories."
  ([feed]
   (feed-document feed {}))
  ([feed {:keys [url] :as opts}]
   {:pre [(map? opts)]}
   (let [title      (resolve/title feed)
         author     (resolve/author feed)
         categories (map #(str/join " " %) (resolve/categories feed))]
     {:id     (feed/feed-guid feed url)
      :fields (present {:title       title
                        :description (notes feed)
                        :author      author
                        :people      (names feed nil)
                        :categories  (str/join " " categories)})
      :stored (into {} (filter (comp some? val))
                    {:title title :author author :url url})})))

(def boosts
  "How much a match in each field weighs in a query, e.g. a word in a
  title counts three times a word in the notes."
  {:title 3 :show 2 :author 2 :people 2 :chapters 2 :keywords 2
   :categories 1 :description 1 :transcript 1})

(def aliases
  "Other names that a query can give the fields of the documents, e.g.
  notes:clojure for a word in the description."
  {"notes" :description})

(defn index
  "An index of the search documents `docs` for util.text.search, with boosts
  and aliases as the defaults of its queries, or an empty one to add to."
  ([]
   (index []))
  ([docs]
   (text.search/index docs {:boosts boosts :aliases aliases})))

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

(defn- url-of
  "The URL of `feed` by the :url of the context, a string, or a function
  of the feed such as a map of feeds to URLs."
  [{:keys [url]} feed]
  (if (ifn? url)
    (url feed)
    url))

(defn- with-feed-guids
  "The context `ctx` with a function of a feed that gives its guid and
  works each one out once, since that of a feed without a podcast:guid is
  a hash of its URL."
  [ctx]
  (if (::feed-guid ctx)
    ctx
    (let [known (volatile! {})
          guid  (fn [feed]
                  (or (get @known feed)
                      (let [guid (feed/feed-guid feed (url-of ctx feed))]
                        (vswap! known assoc feed guid)
                        guid)))]
      (assoc ctx ::feed-guid guid))))

(defn ^:no-doc episode-id
  "The episode id of `item` in `feed`, with the feed URL from `ctx`."
  [ctx feed item]
  (if-let [guid (::feed-guid ctx)]
    [(guid feed) (feed/item-guid item)]
    (feed/episode-id feed (url-of ctx feed) item)))

(defn ^:no-doc entry
  "The entry of `item` in `feed`, from the profile in `ctx`."
  [ctx feed item]
  (profile/episode-entry (:profile ctx) (episode-id ctx feed item)))

(defn- status
  "The status of the episode with the entry `e` of a profile: :unplayed,
  :in-progress or :played."
  [{:keys [position played?] :as e}]
  (cond played?                                  :played
        (and (number? position) (pos? position)) :in-progress
        :else                                    :unplayed))

(defn- has-chapters?
  "Whether `item` links, embeds or lists chapters."
  [item]
  (boolean (or (resolve/chapters-url item)
               (resolve/embedded-chapters item)
               (resolve/inferred-chapters item))))

(defn- mentioned
  "The predicate of the criterion :text for `v`, a query or a set of
  queries. It's true when the title and notes of an item match any of
  them, as util.text.search/matches? reads them."
  [v]
  (let [queries (mapv text.search/parse (if (set? v) v [v]))]
    (fn [_ item]
      (let [text (str (resolve/title item) "\n" (notes item))]
        (boolean (some #(text.search/matches? text %) queries))))))

(defn- indexed
  "The predicate of the criterion :text for `v`, a query or a set of
  queries, with the index of the documents of the items under :index in
  `ctx`. It's true when an item's title and notes match any of them."
  [v ctx]
  (let [index  (:index ctx)
        only   (-> (zipmap (keys (:fields index)) (repeat 0))
                   (assoc :title 1 :description 1))
        query  #(text.search/query index % {:boosts only})
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

(declare where)

(def ^:no-doc criterion
  "The test of each criterion of where, a function of the criterion's
  value and the context that returns a predicate of a feed and an item."
  {:season      (fn [v _]
                  (fn [_ item] (in? v (:season (resolve/numbering item)))))
   :type        (fn [v _]
                  (fn [_ item] (in? v (:type (resolve/numbering item)))))
   :duration    (fn [v _]
                  (fn [_ item] (within? v (resolve/duration item))))
   :published   (fn [[lo hi] _]
                  (fn [_ item]
                    (within? [(some-> lo inst-ms) (some-> hi inst-ms)]
                             (some-> (resolve/published item) inst-ms))))
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
                  (let [q (text/fold v)]
                    (fn [feed item]
                      (boolean (some #(str/includes? (text/fold (:name %)) q)
                                     (resolve/people feed item))))))
   :text        (fn [v ctx]
                  (if (:index ctx)
                    (indexed v ctx)
                    (mentioned v)))
   :not         (fn [v ctx] (complement (where v ctx)))
   :status      (fn [v ctx]
                  (fn [feed item] (in? v (status (entry ctx feed item)))))
   :starred?    (fn [v ctx]
                  (fn [feed item]
                    (let [id (episode-id ctx feed item)]
                      (= (boolean v) (profile/starred? (:profile ctx) id)))))
   :inbox?      (fn [v ctx]
                  (fn [feed item]
                    (let [id (episode-id ctx feed item)]
                      (= (boolean v) (profile/in-inbox? (:profile ctx) id)))))
   :rating      (fn [v ctx]
                  (let [opts (select-keys ctx [:scale])]
                    (fn [feed item]
                      (let [id (episode-id ctx feed item)]
                        (within? v (profile/rating (:profile ctx) id opts))))))
   :since-subscription?
   (fn [v ctx]
     (fn [feed item]
       (= (boolean v) (since-subscription? ctx feed item))))})

(defn where
  "The predicate of a feed and one of its items for `criteria` in the
  context `ctx`. The criteria are a map whose entries must all hold:

  - :season, a season or a set of them, as resolve/numbering gives it
  - :type, \"full\", \"trailer\" or \"bonus\", or a set of them
  - :duration, a range of seconds, e.g. [600 nil], nil for an open end
  - :published, a range of instants
  - :age, a range of seconds since it came out, e.g. [nil 604800]
  - :transcript?, :chapters? and :explicit?, where chapters count when
    linked, embedded or listed in the notes
  - :person, someone credited
  - :text, a query that the title and notes must match, in the language
    of util.text.search/parse, e.g. \"coffee -decaf\". A set of queries
    matches when any of them does.
  - :not, criteria that the item doesn't meet, e.g. {:text #{\"rerun\"}}
  - :status, :unplayed, :in-progress or :played, or a set of them
  - :starred? and :inbox?
  - :rating, a range of the listener's ratings, which no unrated episode
    is in
  - :since-subscription?, whether it came out after the listener
    subscribed

  An empty map takes every item. The criteria about the listener need the
  profile as :profile in `ctx`. For feeds without a podcast:guid, they
  also need the :url, a string or a function of the feed. The :age needs
  the current time as :at, and with :scale, the :rating is in that scale.
  With an index of the items' documents as :index, the :text reads it
  instead of each item's notes."
  ([criteria]
   (where criteria {}))
  ([criteria ctx]
   (let [unknown  (filter #(not (contains? criterion %)) (keys criteria))
         personal #{:status :starred? :inbox? :rating :since-subscription?}]
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
     (let [ctx   (with-feed-guids ctx)
           tests (mapv (fn [[k v]] ((criterion k) v ctx)) criteria)]
       (fn [feed item]
         (every? #(% feed item) tests))))))

(def orders
  "The orders that order takes, for a UI to offer."
  #{:listening :newest :oldest :shortest :longest :title :recent})

(defn- published-ms
  [item]
  (some-> (resolve/published item) inst-ms))

(defn- last-heard-ms
  "When the listener last touched `item` of `feed`, in milliseconds, from
  the profile in `ctx`."
  [ctx feed item]
  (let [{:keys [played-at updated-at]} (entry ctx feed item)]
    (some-> (or played-at updated-at) inst-ms)))

(defn order
  "The sort key of a feed and one of its items for the order `k` and the
  context `ctx`, for sort-by. When `k` is a function of a feed and an
  item, it's `k` itself.

  - :listening, as resolve/episodes orders a show
  - :newest and :oldest, by publication
  - :shortest and :longest, by duration
  - :title, alphabetically
  - :recent, by when the listener last touched the episode, which needs
    :profile in `ctx`

  Items without the property sort last."
  ([k]
   (order k {}))
  ([k ctx]
   (when (and (= :recent k) (not (:profile ctx)))
     (throw (ex-info ":recent needs the profile under :profile" {:order k})))
   (case k
     :listening resolve/listening-key
     :newest    (fn [_ item] (- (or (published-ms item) ##-Inf)))
     :oldest    (fn [_ item] (or (published-ms item) ##Inf))
     :shortest  (fn [_ item] (or (resolve/duration item) ##Inf))
     :longest   (fn [_ item] (- (or (resolve/duration item) ##-Inf)))
     :title     (fn [_ item]
                  (if-let [title (resolve/title item)]
                    [0 (text/fold title)]
                    [1 ""]))
     :recent    (let [ctx (with-feed-guids ctx)]
                  (fn [feed item]
                    (- (or (last-heard-ms ctx feed item) ##-Inf))))
     (if (fn? k)
       k
       (throw (ex-info (str "An order the library does not know: " k)
                       {:order k}))))))

(defn ^:no-doc sorted-by
  "The items of `coll` sorted by `f` as sort-by sorts them, but with one
  call of `f` per item rather than two per comparison."
  [f coll]
  (map second (sort-by first (map (juxt f identity) coll))))

(defn episodes
  "The items of `feed` that `opts` select, in the order they ask for:

  - :where, criteria as where takes them, or else every item
  - :order, as order takes it, :listening by default
  - :limit, the most items
  - :profile, :url, :at, :scale and :index, the context that where and
    order need

  For a collection of feeds, the result is [feed item] pairs in one order,
  e.g. for an inbox, with :url as a function of the feed. With a :limit
  and one of the orders, the criteria only read as many items as the
  limit needs."
  ([feed]
   (episodes feed {}))
  ([feed {:keys [limit] :as opts}]
   {:pre [(map? opts)]}
   (let [ctx       (-> (select-keys opts [:profile :url :scale :at :index])
                       (with-feed-guids))
         pred      (where (or (:where opts) {}) ctx)
         k         (or (:order opts) :listening)
         key       (order k ctx)
         feeds     (if (map? feed) [feed] feed)
         sort-key  (if (= :listening k)
                     (fn [[i f item]] [i (key f item)])
                     (fn [[_ f item]] (key f item)))
         meets?    (fn [[_ f item]] (pred f item))
         all       (for [[i f] (map-indexed vector feeds), item (:rss/items f)]
                     [i f item])
         ;; a key of the app can need what the criteria select, so the
         ;; order comes first with the keys of the library only
         chosen    (if (and limit (keyword? k))
                     (into []
                           (comp (filter meets?) (take limit))
                           (sorted-by sort-key all))
                     (cond->> (sorted-by sort-key (filter meets? all))
                       limit (take limit)))
         pairs     (map rest chosen)]
     (vec (if (map? feed) (map second pairs) (map vec pairs))))))

(defn- where-problem
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

(defn problem
  "A description of what in the saved filter `criteria` this version of
  podcast-clj doesn't know, or nil.

  A saved filter can come from a device with a later version, and
  episodes throws for criteria that it doesn't know, so check here before
  offering the filter."
  [criteria]
  (let [{:keys [where order limit scale]} (when (map? criteria) criteria)
        in-where (where-problem where :where)]
    (cond
      (not (map? criteria))
      "the criteria are not a map"

      in-where
      in-where

      (not (or (nil? order) (orders order)))
      (str "the order " (pr-str order) " is not known")

      (not (or (nil? limit) (number? limit)))
      (str "the limit is " (pr-str limit) ", not a number")

      (not (or (nil? scale) (profile/scale? scale)))
      (str "the scale is " (pr-str scale) ", not a scale of ratings"))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast.feed.rss :as rss])
     (def f (rss/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (map :rss/title (episodes f {:order :oldest}))
     (def idx
       (let [url "https://example.com/feed.xml"]
         (index (map #(item-document f % {:url url}) (:rss/items f)))))
     (text.search/query idx "futu")
     (text.search/query idx "title:future -present")
     (def first-notes (html/text (resolve/description (first (:rss/items f)))))
     (text.search/snippet first-notes "future")
     #_.))
