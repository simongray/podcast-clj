(ns ^:no-doc dk.simongray.podcast.directory.impl
  "The internals of directory: the requests and answers of each directory's
  API, the steps of request and response, and how combine tells shows
  apart."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.url :as url]))

(def episode-calls
  "The calls whose answers are episodes rather than shows."
  #{:episodes :by-person :recent :live})

(defn source-id
  "What names `source` in the results: its name in sources, or the :id of
  an entry, or else the name of its API."
  [source]
  (if (map? source)
    (:id source (:api source))
    source))

(defn- below
  "The URL of `path` below the URL `root`, whether `root` ends with a slash
  or not."
  [root path]
  (str (url/without-trailing-slashes root) "/" path))

(defn get-request
  "The request map of a GET of `url`."
  [url]
  {:method :get :url url})

(defn country-code
  "The two lower-case letters of `country`, \"us\" when none is given."
  [country]
  (let [code (str/lower-case (name (or country "us")))]
    (when-not (re-matches #"[a-z]{2}" code)
      (throw (ex-info "A country is two letters" {:country country})))
    code))

(defn category-for
  "The category that the source with the entry `e` gets of `category`, the
  category of a chart. It's the value under the id of the source when
  `category` is a map, or else `category`.

  It throws for a map that names no category for the source, since the
  source would then get the chart of all categories."
  [{:keys [id] :as e} category]
  (cond
    (not (map? category))
    category

    (contains? category id)
    (get category id)

    :else
    (throw (ex-info (str "The category names no category for " (name id))
                    {:source id :category category}))))

(defn- apple-genre
  "The id of the genre of Apple for `category`, a name of `genres` or an
  id. It throws for a name that isn't in `genres`."
  [genres category]
  (let [s     (str/trim (str category))
        named (into {}
                    (map (fn [[name id]] [(str/lower-case name) id]))
                    genres)]
    (or (when (re-matches #"\d{1,9}" s) s)
        (get named (str/lower-case s))
        (throw (ex-info (str "Apple Podcasts has no genre " (pr-str category))
                        {:category category})))))

(defn- apple-chart
  "The URL of the chart of Apple for the source entry `e`, by the country,
  the limit of shows and the category in `params`.

  The chart of a category is a feed of the older kind of Apple, below the
  :genre-charts URL of the entry, which takes the id of a genre."
  [{:keys [trending genre-charts genres] :as e}
   {:keys [limit country category] :as params}]
  (let [limit (long (or limit 25))
        code  (country-code country)]
    (if category
      (below genre-charts
             (str code "/rss/toppodcasts/limit=" limit
                  "/genre=" (apple-genre genres category) "/json"))
      (below trending
             (str code "/podcasts/top/" limit "/podcasts.json")))))

(defn apple-request
  "The request for `call` with `params` to the source with the entry `e`."
  [{:keys [search lookup] :as e} call {:keys [q id limit country] :as params}]
  (case call
    :search   (let [query {:media   "podcast"
                           :entity  "podcast"
                           :term    q
                           :limit   limit
                           :country (some-> country name)}]
                (get-request (str search "?" (url/query-string query))))
    :lookup   (let [ids   (if (coll? id) (str/join "," id) id)
                    query {:id ids :entity "podcast"}]
                (get-request (str lookup "?" (url/query-string query))))
    :episodes (let [query {:id id :entity "podcastEpisode" :limit limit}]
                (get-request (str lookup "?" (url/query-string query))))
    :trending (get-request (apple-chart e params))))

(defn fyyd-request
  "The request for `call` with `params` to the source with the entry `e`."
  [{:keys [root] :as e} call
   {:keys [q id limit language category] :as params}]
  (when (and category (= :trending call))
    (throw (ex-info "fyyd has no chart by category" {:category category})))
  (let [path (case call
               :search   (str "search/podcast?"
                              (url/query-string {:term q :count limit}))
               :lookup   (str "podcast?" (url/query-string {:podcast_id id}))
               :episodes (str "podcast/episodes?"
                              (url/query-string {:podcast_id id :count limit}))
               :recent   (str "episode/latest?"
                              (url/query-string {:count limit}))
               :trending (str "feature/podcast/hot?"
                              (url/query-string {:count    limit
                                                 :language language})))]
    (get-request (below root path))))

(defn gpodder-request
  "The request for `call` with `params` to the source with the entry `e`."
  [{:keys [root] :as e} call {:keys [q id limit category] :as params}]
  (let [path (case call
               :search   (str "search.json?" (url/query-string {:q q}))
               :lookup   (str "api/2/data/podcast.json?"
                              (url/query-string {:url id}))
               :trending (if category
                           (let [tag (str/lower-case (str/trim (str category)))]
                             (str "api/2/tag/" (url/percent-encode tag)
                                  "/" (long (or limit 25)) ".json"))
                           (str "toplist/" (long (or limit 25)) ".json")))]
    (get-request (below root path))))

(defn podcastindex-request
  "The request for `call` with `params` to the source with the entry `e`,
  signed with `credentials` at the :auth-date of `opts`. It throws without
  credentials."
  [{:keys [root id] :as e} call
   {:keys [q limit language category url] show :id :as params}
   credentials opts]
  (when-not credentials
    (throw (ex-info "The Podcast Index needs credentials under :credentials"
                    {:source id})))
  (let [opts          (cond-> opts root (assoc :base-url root))
        index-request #(index/request credentials %1 %2 opts)]
    (case call
      :search    (index-request :search/by-term {:q q :max limit})
      :lookup    (cond
                   (guid/guid? (str show))
                   (index-request :podcasts/by-guid {:guid show})

                   (and (nil? show) url)
                   (index-request :podcasts/by-feed-url {:url url})

                   :else
                   (index-request :podcasts/by-feed-id {:id show}))
      :episodes  (cond
                   (guid/guid? (str show))
                   (index-request :episodes/by-podcast-guid
                                  {:guid show :max limit})

                   (and (nil? show) url)
                   (index-request :episodes/by-feed-url {:url url :max limit})

                   :else
                   (index-request :episodes/by-feed-id {:id show :max limit}))
      :by-person (index-request :search/by-person {:q q :max limit})
      :recent    (index-request :recent/episodes {:max limit})
      :live      (index-request :episodes/live {:max limit})
      :trending  (let [cat (some-> category index/category-filter)]
                   (index-request :podcasts/trending
                                  {:max limit :lang language :cat cat})))))

(defn check-region
  "Throw when `params` ask the source with the entry `e` for a chart of a
  :country or :language that it can't narrow its chart to, since a chart
  of the wrong shows is worse than none."
  [e params]
  (let [narrows (case (:api e)
                  :apple                #{:country}
                  (:fyyd :podcastindex) #{:language}
                  #{})
        asked   (filter params [:country :language])]
    (when-let [k (when (not-any? narrows asked) (first asked))]
      (let [source (:name e (name (:id e)))]
        (throw (ex-info (str source " has no chart by " (name k))
                        {:source (:id e) k (get params k)}))))))

(defn- present
  "The map `m` without the entries that say nothing: nil, a blank string or
  an empty collection."
  [m]
  (into {} (remove (fn [[_ v]]
                     (or (nil? v)
                         (and (string? v) (str/blank? v))
                         (and (coll? v) (empty? v)))))
        m))

(defn- with-feed-url
  "The `show` with `feed-url` as its self link."
  [show feed-url]
  (cond-> show
    (not (str/blank? (str feed-url)))
    (assoc :atom/links [{:rel "self" :href feed-url}])))

(defn records
  "The records in the JSON value `x`, which holds one record as a map or
  several in a sequence."
  [x]
  (if (map? x) [x] x))

(defn- apple-explicit
  "The iTunes explicit flag of Apple's advisory `rating`. The chart JSON of
  Apple spells Explicit without the second i."
  [rating]
  (case (some-> rating str/lower-case)
    ("explicit" "explict") true
    "clean"                false
    nil))

;; Apple's image CDN serves an artwork at any size that its path names, and
;; as WebP: for one artwork, 4 kB at 64 px and 23 kB at 512 against 83 kB
;; for the JPEG at 600 (measured 2026-10-26). It's how the CDN behaves
;; rather than a documented API.
;; https://feeding.cloud.geek.nz/posts/extracting-album-covers-from-the-itunes-store/
;; https://bendodson.com/weblog/2013/02/19/itunes-artwork-finder
(defn- apple-sizes
  "The sizes of Apple's artwork as hrefs by width: the 100 and 600 pixels
  that the result gives as `artworkUrl100` and `artworkUrl600`, and WebP
  sizes beside them when the URL of 600 names its size in its path."
  [artworkUrl100 artworkUrl600]
  (let [suffix "/600x600bb.jpg"
        base   (when (some-> artworkUrl600 (str/ends-with? suffix))
                 (subs artworkUrl600 0 (- (count artworkUrl600) (dec (count suffix)))))]
    (into (sorted-map)
          (concat (keep (fn [[href width]] (when href [width href]))
                        [[artworkUrl100 100] [artworkUrl600 600]])
                  (when base
                    (for [width [64 128 256 512]]
                      [width (str base width "x" width "bb.webp")]))))))

(defn- apple-show
  "The show of the result `r` of a search or lookup at Apple. Apple gives
  the artwork at 100 and at 600 pixels, and its CDN serves other sizes,
  which become podcast:images with their widths, so that an app can load
  the size that fits."
  [{:strs [collectionId collectionName artistName feedUrl artworkUrl600
           artworkUrl100 collectionViewUrl genres releaseDate trackCount
           contentAdvisoryRating]
    :as   r}]
  (let [artwork    (or artworkUrl600 artworkUrl100)
        sizes      (for [[width href] (apple-sizes artworkUrl100 artworkUrl600)]
                     {:href href :width width :height width :purpose "artwork"})
        explicit   (apple-explicit contentAdvisoryRating)
        categories (for [g genres :when (not= "Podcasts" g)] {:text g})
        published  (some-> releaseDate time/parse-iso8601)]
    (-> (present {:rss/title               collectionName
                  :itunes/author           artistName
                  :itunes/image            (when artwork {:href artwork})
                  :podcast/images          (not-empty (vec sizes))
                  :itunes/explicit         explicit
                  :itunes/categories       (vec categories)
                  :rss/pub-date            published
                  :directory/id            (some-> collectionId str)
                  :directory/url           collectionViewUrl
                  :directory/episode-count trackCount
                  :directory/raw           r})
        (with-feed-url feedUrl))))

(defn- apple-chart-show
  "The show of the entry `r` of Apple's chart, which names no feed. A
  lookup by its id gives the rest."
  [{:strs [id name artistName artworkUrl100 url genres contentAdvisoryRating]
    :as   r}]
  (let [categories (for [{:strs [name]} genres :when (not= "Podcasts" name)]
                     {:text name})]
    (present {:rss/title         name
              :itunes/author     artistName
              :itunes/image      (when artworkUrl100 {:href artworkUrl100})
              :itunes/explicit   (apple-explicit contentAdvisoryRating)
              :itunes/categories (vec categories)
              :directory/id      id
              :directory/url     url
              :directory/raw     r})))

(defn- apple-genre-show
  "The show of the entry `r` of the chart of a genre of Apple, which has
  the form of the older feeds of Apple. Like the other charts of Apple,
  it names no feed."
  [r]
  (let [first-of #(if (map? %) % (first %))
        label    #(get (first-of (get r %)) "label")
        image    (get (last (records (get r "im:image"))) "label")
        link     (first-of (get r "link"))
        category (get-in r ["category" "attributes" "label"])]
    (present {:rss/title         (label "im:name")
              :itunes/author     (label "im:artist")
              :rss/description   (label "summary")
              :itunes/image      (when image {:href image})
              :itunes/categories (if category [{:text category}] [])
              :directory/id      (get-in r ["id" "attributes" "im:id"])
              :directory/url     (get-in link ["attributes" "href"])
              :directory/raw     r})))

(defn fyyd-show
  "The show of the podcast record `r` of fyyd's API."
  [{:strs [id title author description xmlURL htmlURL imgURL layoutImageURL
           language lastpub episode_count url_fyyd paymentURL]
    :as   r}]
  (let [artwork (or imgURL layoutImageURL)
        funded? (not (str/blank? (str paymentURL)))]
    (-> (present {:rss/title               title
                  :itunes/author           author
                  :rss/description         description
                  :rss/link                htmlURL
                  :rss/language            language
                  :itunes/image            (when artwork {:href artwork})
                  :rss/pub-date            (some-> lastpub time/parse-iso8601)
                  :podcast/funding         (when funded? [{:url paymentURL}])
                  :directory/id            id
                  :directory/url           url_fyyd
                  :directory/episode-count episode_count
                  :directory/raw           r})
        (with-feed-url xmlURL))))

(defn gpodder-show
  "The show of the podcast record `r` of gpodder.net, whose id is the feed
  URL itself."
  [{:strs [url title author description subscribers logo_url scaled_logo_url
           website mygpo_link]
    :as   r}]
  (let [artwork (or logo_url scaled_logo_url)]
    (-> (present {:rss/title             title
                  :itunes/author         author
                  :rss/description       description
                  :rss/link              website
                  :itunes/image          (when artwork {:href artwork})
                  :directory/id          url
                  :directory/url         mygpo_link
                  :directory/subscribers subscribers
                  :directory/raw         r})
        (with-feed-url url))))

(defn- podcastindex-show
  "The show of the feed map `m` of the Podcast Index, with its keys in the
  index namespace kept. Its page is below `pages`, the URL of the
  pages of the shows."
  [m pages]
  (let [id   (:index/id m)
        page (when (and pages id) (str pages id))]
    (-> (present (assoc m
                        :directory/id            id
                        :directory/url           page
                        :directory/episode-count (:index/episode-count m)
                        :directory/raw           (:index/raw m)))
        (with-feed-url (:index/url m)))))

(defn index-shows
  "The shows in the JSON `body` of the Podcast Index, whose pages are
  below `pages`, or none when the body tells of a failure."
  [body pages]
  (let [{:keys [status feed feeds]} (index/response body)]
    (when status
      (map #(podcastindex-show % pages) (or feeds (when feed [feed]))))))

(defn apple-shows
  "The shows in the JSON value `raw` that Apple answered for `call`. A
  chart holds the entries of one of the two kinds of charts of Apple."
  [raw call]
  (if (= :trending call)
    (concat (map apple-chart-show (get-in raw ["feed" "results"]))
            (map apple-genre-show (records (get-in raw ["feed" "entry"]))))
    (->> (get raw "results")
         (filter #(= "podcast" (get % "kind")))
         (map apple-show))))

(defn- apple-episode
  "The episode of the result `r` of a lookup of episodes at Apple, with
  the show that it's an episode of as :directory/show."
  [{:strs [trackId trackName description episodeGuid episodeUrl releaseDate
           trackTimeMillis artworkUrl600 trackViewUrl collectionId
           collectionName feedUrl]
    :as   r}]
  (let [show (-> (present {:rss/title    collectionName
                           :directory/id (some-> collectionId str)})
                 (with-feed-url feedUrl))]
    (present {:rss/title       trackName
              :rss/description description
              :rss/guid        (when episodeGuid {:content episodeGuid})
              :rss/enclosure   (when episodeUrl {:url episodeUrl})
              :rss/pub-date    (some-> releaseDate time/parse-iso8601)
              :itunes/duration (when (number? trackTimeMillis)
                                 (quot (long trackTimeMillis) 1000))
              :itunes/image    (when artworkUrl600 {:href artworkUrl600})
              :directory/id    (some-> trackId str)
              :directory/url   trackViewUrl
              :directory/show  show
              :directory/raw   r})))

(defn- apple-episodes
  "The episodes in the JSON value `raw` of a lookup of episodes at Apple,
  each with the show that the answer describes too."
  [raw]
  (let [results (get raw "results")
        show    (some #(when (= "podcast" (get % "kind")) (apple-show %))
                      results)]
    (for [r     results
          :when (= "podcastEpisode" (get r "wrapperType"))]
      (cond-> (apple-episode r)
        show (assoc :directory/show show)))))

(defn- positive
  "The number `n` when it's above zero, or nil."
  [n]
  (when (and (number? n) (pos? n))
    n))

(defn- fyyd-episode
  "The episode of the record `e` of fyyd, of the `show`, or else of a show
  known by the id that the record names."
  [{:strs [id title guid url enclosure content_type pubdate duration imgURL
           description num_season num_episode url_fyyd podcast_id]
    :as   e}
   show]
  (present {:rss/title       title
            :rss/description description
            :rss/link        url
            :rss/guid        (when-not (str/blank? (str guid)) {:content guid})
            :rss/enclosure   (when enclosure
                               (cond-> {:url enclosure}
                                 content_type (assoc :type content_type)))
            :rss/pub-date    (some-> pubdate time/parse-iso8601)
            :itunes/duration (positive duration)
            :itunes/image    (when imgURL {:href imgURL})
            :itunes/season   (positive num_season)
            :itunes/episode  (positive num_episode)
            :directory/id    id
            :directory/url   url_fyyd
            :directory/show  (or show {:directory/id podcast_id})
            :directory/raw   e}))

(defn- fyyd-episodes
  "The episodes in the JSON value `raw` of fyyd: those of one show, which
  the answer describes too, or the newest of any show."
  [raw]
  (let [data (get raw "data")]
    (when (= 1 (get raw "status"))
      (if (map? data)
        (let [show (fyyd-show (dissoc data "episodes"))]
          (map #(fyyd-episode % show) (get data "episodes")))
        (map #(fyyd-episode % nil) data)))))

(defn- index-episode
  "The episode map `m` of the Podcast Index, with the show that it names
  as :directory/show."
  [m]
  (let [show (-> (present {:rss/title    (:index/feed-title m)
                           :podcast/guid (:index/podcast-guid m)
                           :itunes/image (some->> (:index/feed-image m)
                                                  (hash-map :href))
                           :directory/id (:index/feed-id m)})
                 (with-feed-url (:index/feed-url m)))]
    (present (assoc m
                    :directory/id   (:index/id m)
                    :directory/show show
                    :directory/raw  (:index/raw m)))))

(defn episodes
  "The episodes in the JSON `body` that a source with the `api` answered,
  or none when the body tells of a failure."
  [api body]
  (case api
    :podcastindex (let [{:keys [status items live-items]} (index/response body)]
                    (when status
                      (map index-episode (concat items live-items))))
    :apple        (apple-episodes (json/read-str body))
    :fyyd         (fyyd-episodes (json/read-str body))))

(defn- name-of
  "The title and author of `show`, lower-cased: what tells shows apart when
  one of them names no feed."
  [show]
  [(str/lower-case (str (:rss/title show)))
   (str/lower-case (str (:itunes/author show)))])

(defn- own-keys
  "The entries of `show` that belong to the model rather than to the
  directory it came from."
  [show]
  (into {} (remove #(= "directory" (namespace (key %)))) show))

(defn- guids
  "The guids that identify the feed of `show`: the one it declares, and
  the one that the podcast namespace derives from its URL."
  [show]
  (let [derived (some-> (resolve/self-url show) guid/from-url)]
    (remove nil? [(:podcast/guid show) derived])))

(defn- indexed
  "The `state` with the entry at `i` found by each guid of `show`."
  [state show i]
  (update state :by-guid into (map #(vector % i)) (guids show)))

(defn- same-as
  "The position in the entries of `state` of the entry that `show` is the
  same as, or nil. It's the first entry that has:

  - the id of `show` at the same source
  - a guid of its feed, declared or derived from the feed URL
  - its title and author, when either of them has no guid

  Two feeds of one show keep their own entries."
  [{:keys [entries by-id by-guid by-name] :as state} show]
  (or (get by-id [(:directory/source show) (:directory/id show)])
      (some by-guid (guids show))
      (when-let [i (get by-name (name-of show))]
        (when (or (empty? (guids show))
                  (empty? (guids (entries i))))
          i))))

(defn with-show
  "The `state` of a combination with `show` in it: merged into the entry
  that it's the same as, or else as a new entry."
  [{:keys [entries] :as state} show]
  (let [id-key [(:directory/source show) (:directory/id show)]
        found  {(:directory/source show) (:directory/id show)}]
    (if-let [i (same-as state show)]
      (let [merged (-> (merge (own-keys show) (entries i))
                       (update :directory/sources merge found))]
        (-> state
            (assoc-in [:entries i] merged)
            (assoc-in [:by-id id-key] i)
            (indexed merged i)))
      (let [i     (count entries)
            added (assoc show :directory/sources found)]
        (-> state
            (update :entries conj added)
            (assoc-in [:by-id id-key] i)
            (assoc-in [:by-name (name-of show)] i)
            (indexed show i))))))

(defn default-sources
  "Every source that needs no credentials, and the Podcast Index when
  `opts` have :credentials."
  [{:keys [credentials] :as opts}]
  (cond-> [:apple :fyyd :gpodder] credentials (conj :podcastindex)))

(def podcasts-genre
  "The id of the genre Podcasts of Apple, whose subgenres are the
  categories."
  26)

(defn genre-names
  "The names of the genres in the map `genres` of an answer of Apple's genre
  service, and those of their subgenres, as a map by id."
  [genres]
  (into {}
        (mapcat (fn [{:strs [id name subgenres]}]
                  (cons [(parse-long (str id)) name]
                        (genre-names subgenres))))
        (vals genres)))
