(ns dk.simongray.podcast.criteria
  "Criteria that select and order episodes, given as data, e.g. for a
  saved filter or a download rule."
  (:require [dk.simongray.drop-in-search.analysis :as analysis]
            [dk.simongray.podcast.criteria.impl :as impl]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]))

(defn where
  "The predicate of a feed and one of its items for `criteria` in the
  context `ctx`. The criteria are a map whose entries must all hold, and
  these are about the episode:

  - :season and :episode, a number or a set of them
  - :type, \"full\", \"trailer\" or \"bonus\", or a set of them
  - :duration, a range of seconds, e.g. [600 nil], nil for an open end
  - :published, a range of instants
  - :age, a range of seconds since it came out, e.g. [nil 604800]
  - :text, a query that the title and notes must match, in the language
    of dk.simongray.drop-in-search.queries/parse, e.g. \"coffee -decaf\".
    A set of queries matches when any of them does.
  - :person, someone credited, and :author, the episode's author or else
    its show's
  - :language, a language range of RFC 4647, e.g. \"en\" for any English,
    or a set of them, that the show's language matches, or the language
    of a file that the episode offers
  - :category, a category or subcategory of Apple Podcasts, or one that
    the feed names in RSS, or a set of them
  - :location, a place that it's about or was made in, by its name or its
    country code
  - :license, the license of its content, e.g. \"cc-by-4.0\", or a set of
    them
  - :media, :audio or :video, or a set of them, which it offers
  - :transcript?, :chapters?, :soundbites? and :explicit?, where chapters
    count when linked, embedded or listed in the notes
  - :value?, :comments?, :chat? and :funding?, whether it takes payments
    by a value block, has public comments, a chat or funding links, its
    own or its show's
  - :ai-content?, whether its feed says that AI made some of it

  These are about the show:

  - :show, the feed guid of a show, or a set of them
  - :medium, what the show is, e.g. \"podcast\", \"music\" or
    \"audiobook\", or a set of them
  - :serial? and :complete?, whether its episodes are meant to be heard in
    order, and whether it says it will publish no more of them
  - :show-explicit?, whether the show itself is marked explicit, whatever
    its episodes say, e.g. to leave out the episodes of an explicit show
    that marks some of them clean
  - :publisher, the feed guid of the show's publisher, or a set of them

  These are about the listener:

  - :status, :unplayed, :in-progress or :played, or a set of them
  - :progress, a range of the seconds into it where the listener is, 0
    for an episode not started
  - :heard, a range of instants when the listener last heard it
  - :starred? and :inbox?
  - :rating, a range of the listener's ratings, which no unrated episode
    is in
  - :since-subscription?, whether it came out after the listener
    subscribed
  - :folder and :tag, the folder or a tag that the listener gave its show,
    or a set of them, where a folder of nil is none
  - :queued?, whether it's in the queue
  - :download, the state of its file on the device, :downloaded,
    :downloading, :failed or :none, or a set of them
  - :hidden?, whether the listener hid it, or else the :hide-where of its
    show does, or #{true false} for either

  And :not holds criteria that the item doesn't meet, e.g.
  {:text #{\"rerun\"}}. An empty map takes every item.

  The criteria about the listener need the profile as :profile in `ctx`,
  and :download needs the :files of the device, as
  dk.simongray.podcast.downloads keeps them. For feeds without a
  podcast:guid, the criteria also need the :url, a string or a function
  of the feed. The :age needs the current time as :at, and with :scale,
  the :rating is in that scale. With an index of the documents of the
  items as :index, the :text reads it instead of each item's notes."
  ([criteria]
   (where criteria {}))
  ([criteria ctx]
   (impl/where criteria ctx)))

(defn visible
  "The criteria `where` with {:hidden? false}, unless they name :hidden?
  themselves, for the lists that leave out what's hidden."
  [where]
  (if (impl/names? where :hidden?)
    where
    (assoc (or where {}) :hidden? false)))

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
  (let [{:keys [played-at updated-at]} (impl/entry ctx feed item)]
    (some-> (or played-at updated-at) inst-ms)))

(declare order)

(defn- own-order
  "The sort key of the order that the listener chose for the show of a
  feed, by its settings in the profile of `ctx`, as a function of the
  feed, or nil for the listening order of the show."
  [ctx]
  (let [ctx (impl/with-feed-guids ctx)]
    (impl/memoized
     (fn [feed]
       (let [k (:order (profile/settings (:profile ctx)
                                         ((::feed-guid ctx) feed)))]
         ;; an order of a later version reads as the listening order
         (when (and (contains? orders k) (not= :listening k))
           (order k ctx)))))))

(defn order
  "The sort key of a feed and one of its items for the order `k` and the
  context `ctx`, for sort-by. When `k` is a function of a feed and an
  item, it's `k` itself.

  - :listening, the listening order of a show, or the :order that the
    listener chose for it, with :profile in `ctx`
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
     :listening (if (:profile ctx)
                  (let [own (own-order ctx)]
                    (fn [feed item]
                      (if-let [key (own feed)]
                        (key feed item)
                        (resolve/listening-key feed item))))
                  resolve/listening-key)
     :newest    (fn [_ item] (- (or (published-ms item) ##-Inf)))
     :oldest    (fn [_ item] (or (published-ms item) ##Inf))
     :shortest  (fn [_ item] (or (resolve/duration item) ##Inf))
     :longest   (fn [_ item] (- (or (resolve/duration item) ##-Inf)))
     :title     (fn [_ item]
                  (if-let [title (resolve/title item)]
                    [0 (analysis/fold title)]
                    [1 ""]))
     :recent    (let [ctx (impl/with-feed-guids ctx)]
                  (fn [feed item]
                    (- (or (last-heard-ms ctx feed item) ##-Inf))))
     (if (fn? k)
       k
       (throw (ex-info (str "An order the library does not know: " k)
                       {:order k}))))))

(defn episodes
  "The items of `feed` that `opts` select, in the order they ask for:

  - :where, criteria as where takes them, or else every item
  - :order, as order takes it, :listening by default
  - :limit, the most items
  - :profile, :url, :at, :scale, :index and :files, the context of the
    criteria and the order

  For a collection of feeds, the result is [feed item] pairs in one order,
  e.g. for an inbox, with :url as a function of the feed. With a :limit
  and one of the orders, the criteria only read as many items as the
  limit needs."
  ([feed]
   (episodes feed {}))
  ([feed {:keys [limit] :as opts}]
   {:pre [(map? opts)]}
   (let [ctx       (-> (select-keys opts [:profile :url :scale :at :index
                                          :files])
                       (impl/with-feed-guids))
         pred      (where (or (:where opts) {}) ctx)
         k         (or (:order opts) :listening)
         key       (order k ctx)
         feeds     (if (map? feed) [feed] feed)
         sort-key  (if (= :listening k)
                     (fn [[i f item]] [i (key f item)])
                     (fn [[_ f item]] (key f item)))
         meets?    (fn [[_ f item]] (pred f item))
         all       (for [[i f] (map-indexed vector feeds)
                         item  (resolve/distinct-items (:rss/items f))]
                     [i f item])
         ;; a key of the app can need what the criteria select, so the
         ;; order comes first with the keys of the library only
         chosen    (if (and limit (keyword? k))
                     (into []
                           (comp (filter meets?) (take limit))
                           (impl/sorted-by sort-key all))
                     (cond->> (impl/sorted-by sort-key (filter meets? all))
                       limit (take limit)))
         pairs     (map rest chosen)]
     (vec (if (map? feed) (map second pairs) (map vec pairs))))))

(defn problem
  "A description of what in the saved filter `criteria` this version of
  podcast-clj doesn't know, or nil.

  A saved filter can come from a device with a later version, and
  criteria that this version doesn't know throw, so check a filter before
  offering it."
  [criteria]
  (let [{:keys [where order limit scale]} (when (map? criteria) criteria)
        in-where (impl/where-problem where :where)]
    (cond
      (not (map? criteria))
      "the criteria are not a map"

      in-where
      in-where

      (not (or (nil? order) (orders order)))
      (str "the order " (pr-str order) " is not known")

      (not (or (nil? limit) (number? limit)))
      (str "the limit is " (pr-str limit) ", not a number")

      (not (or (nil? scale) (profile.marks/scale? scale)))
      (str "the scale is " (pr-str scale) ", not a scale of ratings"))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast.feed.rss :as rss])
     (def f (rss/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (map :rss/title (episodes f {:order :oldest}))
     (map :rss/title (episodes f {:where {:text "future"}}))
     (problem {:where {:mood :happy}})
     #_.))
