(ns dk.simongray.podcast.profile.opml
  "The OPML of a profile: the subscription lists that the listener follows,
  and the subscriptions as an OPML subscription list, out and in.

  A followed list subscribes the listener to the shows that join it, in
  its folder. The subscription lists are the maps of
  dk.simongray.podcast.format.opml, which reads and writes the OPML
  itself."
  (:require [clojure.string :as str]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.profile :as profile]))

(defn lists
  "The OPML lists that the listener follows in `profile`, as a map from URL
  to :title and :folder. The folder is the one that the shows of the list
  go into."
  [profile]
  (into {}
        (keep (fn [[url e]]
                (when-not (:removed? e)
                  [url (select-keys e [:title :folder])])))
        (:lists profile)))

(defn follow-list
  "Follow the OPML list at `url` in `profile` at `at`, with the :title and
  the :folder for its shows in `details`, when they're given. Following a
  list again changes its title and folder."
  ([profile url at]
   (follow-list profile url {} at))
  ([profile url {:keys [title folder] :as details} at]
   (crdt/write profile [:lists url]
               (constantly {:title title :folder folder :removed? false})
               at)))

(defn unfollow-list
  "Stop following the OPML list at `url` in `profile` at `at`, leaving a
  tombstone. The subscriptions that the list brought stay."
  [profile url at]
  (if (contains? (lists profile) url)
    (crdt/write profile [:lists url] #(assoc % :removed? true) at)
    profile))

(defn ^:no-doc outlines
  "The outlines of the OPML subscription `list` that have a feed URL, in
  order."
  [list]
  (filter :xml-url (:subscriptions list)))

(defn ^:no-doc named
  "The feed guid of the OPML `outline`, given the `names`, the feed guids
  of the subscriptions by normalized URL.

  It's the guid of the subscription at the outline's URL, or else the
  outline's own guid, or else the guid of its URL."
  [names {:keys [xml-url guid] :as outline}]
  ;; a known show doesn't come in again under another guid
  (or (get names (guid/normal-url xml-url))
      (when (string? guid) (not-empty (str/trim guid)))
      (guid/from-url xml-url)))

(defn- joining
  "The shows of the OPML `list` that join `profile`, as pairs of feed guid
  and outline in the order of the list. The `names` are the feed guids of
  the subscriptions by normalized URL, and a show that the list had
  before, in `was`, doesn't join."
  [profile names was list]
  (let [join (fn [{:keys [names joined] :as acc} {:keys [xml-url] :as outline}]
               (let [g (named names outline)]
                 (if (or (was g)
                         (get-in profile [:subscriptions g])
                         (joined g))
                   acc
                   (-> acc
                       (assoc-in [:names (guid/normal-url xml-url)] g)
                       (update :joined conj g)
                       (update :joining conj [g outline])))))]
    (:joining (reduce join
                      {:names names :joined #{} :joining []}
                      (outlines list)))))

(defn refresh-list
  "Note in `profile` at `at` a new parse `after` of the OPML list at `url`,
  which was `before`. The result is a map of the new :profile, the feed
  guids it :added, and those that :left.

  Shows that joined the list are subscribed to, in its folder, unless the
  profile has them already by feed URL or guid. Without `before`, every
  show counts as joined. A show that left the list stays a subscription,
  so that you can ask the listener about it. Nothing changes for a list
  that the listener doesn't follow."
  [profile url before after at]
  (if-let [{:keys [folder]} (get (lists profile) url)]
    (let [names (profile/by-url profile)
          guids (fn [list] (map #(named names %) (outlines list)))
          is    (set (guids after))
          joins (joining profile names (set (guids before)) after)
          join  (fn [profile [g {:keys [xml-url title text]}]]
                  (let [show {:url xml-url :title (or title text)}]
                    (cond-> (profile/subscribe profile g show at)
                      folder (profile/configure g {:folder folder} at))))]
      {:profile (crdt/at-once profile at #(reduce join % joins))
       :added   (mapv first joins)
       :left    (into []
                      (comp (distinct)
                            (remove is)
                            (filter #(profile/subscribed? profile %)))
                      (guids before))})
    {:profile profile :added [] :left []}))

(defn to-opml
  "The live subscriptions of `profile` as an OPML subscription list.

  The folders come first, then the shows in no folder, and the followed
  lists are includes. Each outline has the guid of its feed, the tags as
  categories, and the time of subscribing as its creation time."
  [profile]
  (let [subs    (profile/subscriptions profile)
        ls      (lists profile)
        ordered (sort-by (fn [[folder _]] [(nil? folder) (str folder)])
                         (profile/folders profile))
        outline (fn [folder feed-guid]
                  (let [{:keys [url title added-at]} (get subs feed-guid)
                        ts (profile/tags profile feed-guid)]
                    (cond-> {:text (or title url) :xml-url url :guid feed-guid}
                      title            (assoc :title title)
                      folder           (assoc :path [folder])
                      (seq ts)         (assoc :category (vec ts))
                      (inst? added-at) (assoc :created added-at))))
        include (fn [[url {:keys [title folder]}]]
                  (cond-> {:url url}
                    title  (assoc :text title)
                    folder (assoc :path [folder])))]
    (cond-> {:title         "Subscriptions"
             :subscriptions (vec (for [[folder feed-guids] ordered
                                       feed-guid           feed-guids]
                                   (outline folder feed-guid)))}
      (seq ls) (assoc :includes (mapv include (sort-by key ls))))))

;; Pocket Casts and Overcast write <outline text="feeds"> around the feeds.
;; Seen in September 2026, in an export file of each app:
;; https://github.com/razorgoto/podcast/blob/master/podcasts.opml
;; https://github.com/vinhnx/podcasts/blob/main/overcast.opml
(def wrappers
  "The lower-case names of the folder that some apps write around a whole
  subscription list. When such a folder holds every feed of the list, it
  counts as no folder."
  #{"feeds"})

(defn- wrapper
  "The folder that wraps every feed of the OPML subscription `list`, if
  `wrappers` has its name in lower case."
  [list wrappers]
  (let [[folder :as firsts] (map (comp first :path) (outlines list))]
    (when (and folder (apply = firsts) (wrappers (str/lower-case (str folder))))
      folder)))

(defn- imported
  "The import `acc` with the feed of `outline` subscribed to at `at`, in
  `folder` if any, and its categories as tags."
  [{:keys [profile names] :as acc}
   {:keys [xml-url text title category created] :as outline}
   folder
   at]
  (let [g          (named names outline)
        known      (when (get names (guid/normal-url xml-url))
                     (get-in profile [:subscriptions g :url]))
        details    {:url      (or known xml-url)
                    :title    (or title text)
                    :added-at (when (inst? created) created)}
        categories (when (sequential? category) category)]
    {:names   (assoc names (guid/normal-url xml-url) g)
     :profile (as-> (profile/subscribe profile g details at) profile
                (if folder
                  (profile/configure profile g {:folder folder} at)
                  profile)
                (reduce #(profile/tag %1 g %2 at) profile categories))}))

(defn from-opml
  "Subscribe `profile` at `at` to each feed of the OPML subscription `list`,
  with `opts`.

  Each subscription gets the title, the innermost folder, the categories as
  tags and the creation time of its outline. The profile also follows the
  lists that the OPML includes, and a feed that the profile has already
  keeps its guid and URL.

  A folder around every feed counts as no folder when its name in lower
  case is one of the :wrappers of `opts`, a set or a predicate, by default
  \"feeds\"."
  ([profile list at]
   (from-opml profile list at {}))
  ([profile list at opts]
   {:pre [(map? opts)]}
   (let [around  (wrapper list (:wrappers opts wrappers))
         folder  (fn [path]
                   (last (if (and around (= around (first path)))
                           (rest path)
                           path)))
         feeds   (fn [profile]
                   (:profile (reduce #(imported %1 %2 (folder (:path %2)) at)
                                     {:profile profile
                                      :names   (profile/by-url profile)}
                                     (outlines list))))
         include (fn [profile {:keys [url text path]}]
                   (if (contains? (lists profile) url)
                     profile
                     (let [details {:title text :folder (folder path)}]
                       (follow-list profile url details at))))]
     (crdt/at-once profile at #(reduce include (feeds %) (:includes list))))))

(comment
  (def sample
    (-> (profile/make "laptop")
        (profile/subscribe "feed-1"
                           {:url "https://example.org/feed.xml" :title "A show"}
                           #inst "2026-01-01")
        (profile/configure "feed-1" {:folder "Tech"} #inst "2026-01-01")
        (follow-list "https://example.org/list.opml" {:title "A list"}
                     #inst "2026-01-01")))

  (to-opml sample)
  #_.)
