(ns dk.simongray.podcast.directory
  "The podcast directories of the world behind one shape: Apple Podcasts,
  fyyd, gpodder.net and the Podcast Index, searched, looked up and their
  charts read, every answer keyed like a parsed feed.

  A request is a map any HTTP client can send; a response reads into
  shows, maps with the keys a parsed feed has where the directory carries
  the same information, `:rss/title`, `:itunes/author`, `:itunes/image`,
  the feed's URL as the `:atom/links` entry with rel self that
  `resolve/self-url` reads, `:podcast/guid` as declared or derived from
  that URL, and `:directory/*` for what only the directory knows: the
  `:source`, the show's `:id` there, the `:url` of its page, counts, and
  the record itself under `:raw`.

      (request :apple :search {:q \"podcasting 2.0\" :limit 5})
      ;; => {:method :get :url \"https://itunes.apple.com/search?…\" :headers {…}}
      (response :apple :search body)
      ;; => [{:rss/title \"Podcasting 2.0\" :itunes/author \"Podcast Index LLC\" … :directory/source :apple} …]

  Three endpoints every source answers as far as it can: `:search` shows
  by words, `:lookup` one show by the source's id, `:trending` the
  source's chart. Apple, fyyd and gpodder.net need no credentials; the
  Podcast Index takes its under `:credentials` in the options. On the JVM,
  `search!` and `trending!` ask several sources at once and `combine` the
  answers into one entry per show."
  (:require [clojure.string :as str]
            #?(:clj [dk.simongray.podcast.async :as async])
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.time :as time]))

(def sources
  "The directories that podcast-clj knows, by name. An entry tells the
  `:api` that the directory speaks, its `:name` for a listener, whether it
  needs credentials, and the URLs of the service.

  `request`, `response` and `call!` take a source by its name here, or as
  an entry of this shape. So an app reaches another service with the same
  API:

      (assoc (:fyyd sources) :id :mirror :root \"https://fyyd.example.org/0.2/\")

  The `:id` of such an entry names the source in the results. Without an
  `:id`, the name of its API does."
  {:apple        {:api          :apple
                  :name         "Apple Podcasts"
                  :credentials? false
                  :search       "https://itunes.apple.com/search"
                  :lookup       "https://itunes.apple.com/lookup"
                  :trending     "https://rss.marketingtools.apple.com/api/v2/"}
   :fyyd         {:api          :fyyd
                  :name         "fyyd"
                  :credentials? false
                  :root         "https://api.fyyd.de/0.2/"}
   :gpodder      {:api          :gpodder
                  :name         "gpodder.net"
                  :credentials? false
                  :root         "https://gpodder.net/"}
   :podcastindex {:api          :podcastindex
                  :name         "Podcast Index"
                  :credentials? true
                  :root         index/base-url
                  :pages        "https://podcastindex.org/podcast/"}})

(def endpoints
  "What every source answers: `:search` takes `:q` and `:limit`, with
  `:country` for Apple and `:language` for the Podcast Index; `:lookup`
  takes the source's `:id` for the show, which for gpodder.net is the feed
  URL and for Apple may be several ids at once; `:trending` takes `:limit`
  and `:country` for Apple or `:language` for fyyd and the Podcast Index."
  #{:search :lookup :trending})

(defn ^:no-doc source-id
  "What names `source` in the results: its name in `sources`, or the `:id`
  of an entry, else the name of its API."
  [source]
  (if (map? source)
    (:id source (:api source))
    source))

(defn ^:no-doc entry
  "The entry of `source`, a name in `sources` or an entry itself, with the
  `:id` that `source-id` gives it. Throws for a source whose API
  podcast-clj does not know."
  [source]
  (let [e (if (map? source) source (get sources source))]
    (when-not (contains? sources (:api e))
      (throw (ex-info (str "A directory the library does not know: " source) {:source source})))
    (assoc e :id (source-id source))))

(defn ^:no-doc below
  "The URL of `path` below the URL `root`, whether `root` ends with a slash
  or not."
  [root path]
  (str (str/replace (str root) #"(^|[^/])/+$" "$1") "/" path))

(defn- get-request
  [url]
  {:method :get :url url :headers {"User-Agent" http/user-agent}})

(defn- country-code
  "The two lower-case letters of `country`, \"us\" when none is given."
  [country]
  (let [code (str/lower-case (name (or country "us")))]
    (when-not (re-matches #"[a-z]{2}" code)
      (throw (ex-info "A country is two letters" {:country country})))
    code))

(defn- apple-request
  [{:keys [search lookup trending]} endpoint {:keys [q id limit country]}]
  (case endpoint
    :search   (get-request (str search "?"
                                (http/query-string {:media "podcast" :entity "podcast" :term q :limit limit
                                                     :country (some-> country name)})))
    :lookup   (get-request (str lookup "?"
                                (http/query-string {:id (if (coll? id) (str/join "," id) id) :entity "podcast"})))
    :trending (get-request
               (below trending
                      (str (country-code country) "/podcasts/top/" (long (or limit 25)) "/podcasts.json")))))

(defn- fyyd-request
  [{:keys [root]} endpoint {:keys [q id limit language]}]
  (get-request
   (below root (case endpoint
                 :search   (str "search/podcast?" (http/query-string {:term q :count limit}))
                 :lookup   (str "podcast?" (http/query-string {:podcast_id id}))
                 :trending (str "feature/podcast/hot?"
                                (http/query-string {:count limit :language language}))))))

(defn- gpodder-request
  [{:keys [root]} endpoint {:keys [q id limit]}]
  (get-request
   (below root (case endpoint
                 :search   (str "search.json?" (http/query-string {:q q}))
                 :lookup   (str "api/2/data/podcast.json?" (http/query-string {:url id}))
                 :trending (str "toplist/" (long (or limit 25)) ".json")))))

(defn- podcastindex-request
  [{:keys [root id]} endpoint {:keys [q limit language] show :id} credentials opts]
  (when-not credentials
    (throw (ex-info "The Podcast Index needs credentials under :credentials" {:source id})))
  (let [opts (cond-> opts root (assoc :base-url root))]
    (case endpoint
      :search   (index/request credentials :search/by-term {:q q :max limit} opts)
      :lookup   (if (guid/guid? (str show))
                  (index/request credentials :podcasts/by-guid {:guid show} opts)
                  (index/request credentials :podcasts/by-feed-id {:id show} opts))
      :trending (index/request credentials :podcasts/trending {:max limit :lang language} opts))))

(defn request
  "The request map for `endpoint` at `source` with `params`, as `{:method
  :get :url :headers}`. `endpoints` tells the endpoints and their
  parameters.

  `source` is a name in `sources`, or an entry of the shape of its
  entries. `opts` give the `:credentials` of the Podcast Index, `{:key
  :secret}`. They also give the `:auth-date` of the signature of its
  requests, in Unix seconds, which `call!` supplies (see
  `index/request`)."
  ([source endpoint params]
   (request source endpoint params {}))
  ([source endpoint params {:keys [credentials] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [api] :as e} (entry source)]
     (when-not (contains? endpoints endpoint)
       (throw (ex-info (str "An endpoint the directories do not answer: " endpoint) {:endpoint endpoint})))
     (case api
       :apple        (apple-request e endpoint params)
       :fyyd         (fyyd-request e endpoint params)
       :gpodder      (gpodder-request e endpoint params)
       :podcastindex (podcastindex-request e endpoint params credentials
                                           (select-keys opts [:auth-date]))))))

(defn- present
  "`m` without the entries that say nothing: nil, a blank string or an
  empty collection."
  [m]
  (into {} (remove (fn [[_ v]]
                     (or (nil? v)
                         (and (string? v) (str/blank? v))
                         (and (coll? v) (empty? v)))))
        m))

(defn- with-feed-url
  "`show` with `feed-url` as its self link and, unless it declares one, the
  guid the specification derives from the URL."
  [show feed-url]
  (if (str/blank? (str feed-url))
    show
    (cond-> (assoc show :atom/links [{:rel "self" :href feed-url}])
      (not (:podcast/guid show)) (assoc :podcast/guid (guid/from-url feed-url)))))

(defn- apple-explicit
  "Apple's advisory `rating` as the iTunes flag; its chart JSON spells
  Explicit without the second i."
  [rating]
  (case (some-> rating str/lower-case)
    ("explicit" "explict") true
    "clean"                false
    nil))

(defn- apple-show
  "A show from a search or lookup result `r` of Apple's API."
  [{:strs [collectionId collectionName artistName feedUrl artworkUrl600 artworkUrl100 collectionViewUrl
           genres releaseDate trackCount contentAdvisoryRating] :as r}]
  (-> (present {:rss/title               collectionName
                :itunes/author           artistName
                :itunes/image            (when-let [href (or artworkUrl600 artworkUrl100)] {:href href})
                :itunes/explicit         (apple-explicit contentAdvisoryRating)
                :itunes/categories       (vec (for [g genres :when (not= "Podcasts" g)] {:text g}))
                :rss/pub-date            (some-> releaseDate time/parse-iso8601)
                :directory/id            (some-> collectionId str)
                :directory/url           collectionViewUrl
                :directory/episode-count trackCount
                :directory/raw           r})
      (with-feed-url feedUrl)))

(defn- apple-chart-show
  "A show from an entry `r` of Apple's chart, which names no feed; `:lookup`
  by its id gives the rest."
  [{:strs [id name artistName artworkUrl100 url genres contentAdvisoryRating] :as r}]
  (present {:rss/title         name
            :itunes/author     artistName
            :itunes/image      (when artworkUrl100 {:href artworkUrl100})
            :itunes/explicit   (apple-explicit contentAdvisoryRating)
            :itunes/categories (vec (for [{:strs [name]} genres :when (not= "Podcasts" name)] {:text name}))
            :directory/id      id
            :directory/url     url
            :directory/raw     r}))

(defn- fyyd-show
  "A show from a podcast record `r` of fyyd's API."
  [{:strs [id title author description xmlURL htmlURL imgURL layoutImageURL language lastpub
           episode_count url_fyyd paymentURL] :as r}]
  (-> (present {:rss/title               title
                :itunes/author           author
                :rss/description         description
                :rss/link                htmlURL
                :rss/language            language
                :itunes/image            (when-let [href (or imgURL layoutImageURL)] {:href href})
                :rss/pub-date            (some-> lastpub time/parse-iso8601)
                :podcast/funding         (when-not (str/blank? (str paymentURL)) [{:url paymentURL}])
                :directory/id            id
                :directory/url           url_fyyd
                :directory/episode-count episode_count
                :directory/raw           r})
      (with-feed-url xmlURL)))

(defn- gpodder-show
  "A show from a podcast record `r` of gpodder.net, whose id is the feed
  URL itself."
  [{:strs [url title author description subscribers logo_url scaled_logo_url website mygpo_link] :as r}]
  (-> (present {:rss/title              title
                :itunes/author          author
                :rss/description        description
                :rss/link               website
                :itunes/image           (when-let [href (or logo_url scaled_logo_url)] {:href href})
                :directory/id           url
                :directory/url          mygpo_link
                :directory/subscribers  subscribers
                :directory/raw          r})
      (with-feed-url url)))

(defn- podcastindex-show
  "A show from the feed map `m` that `index/response` read, its `:index/*`
  keys kept. Its page is below `pages`, the URL of the pages of the shows."
  [m pages]
  (-> (present (assoc m
                      :directory/id            (:index/id m)
                      :directory/url           (when pages (some->> (:index/id m) (str pages)))
                      :directory/episode-count (:index/episode-count m)
                      :directory/raw           (:index/raw m)))
      (with-feed-url (:index/url m))))

(defn- ranked
  "`shows` with their position in a chart under `:directory/rank`."
  [shows]
  (map-indexed (fn [i show] (assoc show :directory/rank (inc i))) shows))

(defn response
  "The shows in the JSON `body` that `source` answered for `endpoint`, as
  a vector. Throws only when the body is no JSON.

  A lookup gives one show. The shows of trending are in the order of the
  chart, each with its `:directory/rank`. The vector is empty when the
  body tells of a failure. `source` is as `request` takes it, and each
  show names it under `:directory/source`."
  [source endpoint body]
  (let [{:keys [api id pages]} (entry source)
        shows (if (= :podcastindex api)
                (let [{:keys [status feed feeds]} (index/response body)]
                  (when status
                    (map #(podcastindex-show % pages) (or feeds (when feed [feed])))))
                (let [raw (json/read-str body)]
                  (case api
                    :apple   (if (= :trending endpoint)
                               (map apple-chart-show (get-in raw ["feed" "results"]))
                               (map apple-show (filter #(= "podcast" (get % "kind")) (get raw "results"))))
                    :fyyd    (when (= 1 (get raw "status"))
                               (let [data (get raw "data")]
                                 (map fyyd-show (if (map? data) [data] data))))
                    :gpodder (map gpodder-show (if (map? raw) [raw] raw)))))
        named (map #(assoc % :directory/source id) shows)]
    (vec (if (= :trending endpoint) (ranked named) named))))

(defn- name-of
  "The title and author of `show`, lower-cased: what tells shows apart when
  one of them names no feed."
  [show]
  [(str/lower-case (str (:rss/title show))) (str/lower-case (str (:itunes/author show)))])

(defn- own-keys
  "The entries of `show` that belong to the model rather than to the
  directory it came from."
  [show]
  (into {} (remove #(= "directory" (namespace (key %)))) show))

(defn- same-as
  "The position in `entries` of the show `show` is the same as: the one
  with its id at the same source, the one with its guid, declared or
  derived from the feed URL, or, when either of them has no guid, the one
  with its title and author. Two feeds of one show keep their own entries."
  [{:keys [entries by-id by-guid by-name]} show]
  (let [guid (:podcast/guid show)]
    (or (get by-id [(:directory/source show) (:directory/id show)])
        (when guid (get by-guid guid))
        (when-let [i (get by-name (name-of show))]
          (when (or (nil? guid) (nil? (:podcast/guid (entries i))))
            i)))))

(defn combine
  "One entry per show across `results` from several sources, in the order
  first found: the first source's record, with the fields it lacked filled
  from the later ones and `:directory/sources` naming every source that
  had the show, by its id there. A chart entry, which names no feed, meets
  its lookup by the id they share, or the same show found elsewhere by its
  title and author, and gains the feed."
  [results]
  (:entries
   (reduce (fn [{:keys [entries] :as state} show]
             (let [source-id [(:directory/source show) (:directory/id show)]
                   found     {(:directory/source show) (:directory/id show)}]
               (if-let [i (same-as state show)]
                 (let [merged (-> (merge (own-keys show) (entries i))
                                  (update :directory/sources merge found))]
                   (cond-> (-> state
                               (assoc-in [:entries i] merged)
                               (assoc-in [:by-id source-id] i))
                     (:podcast/guid merged) (assoc-in [:by-guid (:podcast/guid merged)] i)))
                 (let [i (count entries)]
                   (cond-> (-> state
                               (update :entries conj (assoc show :directory/sources found))
                               (assoc-in [:by-id source-id] i)
                               (assoc-in [:by-name (name-of show)] i))
                     (:podcast/guid show) (assoc-in [:by-guid (:podcast/guid show)] i))))))
           {:entries [] :by-id {} :by-guid {} :by-name {}}
           results)))

#?(:clj
   (defn- default-sources
     "Every source that needs no credentials, and the Podcast Index when
     `opts` carry `:credentials`."
     [{:keys [credentials]}]
     (cond-> [:apple :fyyd :gpodder] credentials (conj :podcastindex))))

#?(:clj
   (defn call!
     "Send `endpoint` with `params` to `source` with the HTTP client of
     podcast-clj, and read the shows in the answer.

     `opts` are those of `request`. They can also give `:timeout-seconds`,
     the time to wait for an answer. The default is 15, so that a
     directory that does not answer costs little.

     A 404 is no shows. Each other status that is not a success throws
     `::failed`, so that a refused key does not look like an empty
     answer."
     ([source endpoint params]
      (call! source endpoint params {}))
     ([source endpoint params opts]
      {:pre [(map? opts)]}
      (let [opts                  (cond-> opts (not (:auth-date opts)) (assoc :auth-date (quot (System/currentTimeMillis) 1000)))
            req                   (assoc (request source endpoint params opts) :timeout-seconds (:timeout-seconds opts 15))
            {:keys [status body]} (http/call! req)
            id                    (source-id source)]
        (cond
          (<= 200 status 299) (response source endpoint body)
          (= 404 status)      []
          :else               (throw (ex-info (str (name id) " answered " status)
                                              {:type ::failed :source id :status status})))))))

#?(:clj
   (defn- completed!
     "The `shows` a `source` answered for `endpoint`, with what a chart
     leaves out fetched: Apple's names no feed, so its entries are looked
     up by id, all in one request, for `combine` to fold in."
     [source endpoint shows opts]
     (if (and (= :apple (:api (entry source))) (= :trending endpoint) (seq shows))
       (concat shows (call! source :lookup {:id (keep :directory/id shows)} opts))
       shows)))

#?(:clj
   (defn ^:no-doc fan-out!
     "Ask every one of `sources` for `endpoint` with `params` at once and
     `combine` what they answer: `{:results [...] :failed {source message}}`,
     a source that fails costing nothing but its absence."
     [sources endpoint params opts]
     (let [answers (async/all (map (fn [source]
                                     (async/attempt #(completed! source endpoint (call! source endpoint params opts) opts)))
                                   sources))
           failed  (into {} (for [[source {:keys [error]}] (map vector sources answers) :when error]
                              [(source-id source) (async/explain error)]))]
       (cond-> {:results (combine (mapcat :value answers))}
         (seq failed) (assoc :failed failed)))))

#?(:clj
   (defn search!
     "The shows that match the words `q` across the directories, as
     `{:results [...] :failed {...}}`.

     The results have one entry for each show, and the shows that the
     most sources have come first. `:failed` has the message of each
     source that failed. `opts` are these:

         {:sources         [:apple :fyyd]   ; each a name in `sources` or an entry.
                                            ; default: every source without credentials,
                                            ; plus the Podcast Index when given them
          :limit           10               ; per source
          :country         \"dk\"             ; Apple's store
          :language        \"da\"             ; the Podcast Index's filter
          :credentials     {:key :secret}    ; for the Podcast Index
          :timeout-seconds 15}               ; to wait for each source"
     ([q]
      (search! q {}))
     ([q opts]
      {:pre [(map? opts)]}
      (let [sources (or (:sources opts) (default-sources opts))
            params  (assoc (select-keys opts [:limit :country :language]) :q q)
            answer  (fan-out! sources :search params opts)]
        (update answer :results #(vec (sort-by (fn [show] (- (count (:directory/sources show)))) %)))))))

#?(:clj
   (defn trending!
     "The charts of the directories combined, as `search!` returns them,
     in the order the sources list them; `opts` as for `search!`."
     ([]
      (trending! {}))
     ([opts]
      {:pre [(map? opts)]}
      (fan-out! (or (:sources opts) (default-sources opts))
                :trending
                (select-keys opts [:limit :country :language])
                opts))))

#?(:clj
   (comment
     (request :apple :search {:q "podcasting 2.0" :limit 3})
     (call! :fyyd :search {:q "podcasting 2.0" :limit 3})
     (:results (search! "podcasting 2.0" {:limit 3}))
     (map :rss/title (:results (trending! {:country "dk" :limit 5})))
     #_.))
