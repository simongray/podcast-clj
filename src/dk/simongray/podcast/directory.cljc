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
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.time :as time]))

(def sources
  "The directories, by source, with their names and whether they need
  credentials."
  {:apple        {:name "Apple Podcasts" :credentials? false}
   :fyyd         {:name "fyyd"           :credentials? false}
   :gpodder      {:name "gpodder.net"    :credentials? false}
   :podcastindex {:name "Podcast Index"  :credentials? true}})

(def endpoints
  "What every source answers: `:search` takes `:q` and `:limit`, with
  `:country` for Apple and `:language` for the Podcast Index; `:lookup`
  takes the source's `:id` for the show, which for gpodder.net is the feed
  URL and for Apple may be several ids at once; `:trending` takes `:limit`
  and `:country` for Apple or `:language` for fyyd and the Podcast Index."
  #{:search :lookup :trending})

(def ^:no-doc user-agent
  "The User-Agent sent to the directories."
  "dk.simongray/podcast")

(defn- get-request
  [url]
  {:method :get :url url :headers {"User-Agent" user-agent}})

(defn- country-code
  "The two lower-case letters of `country`, \"us\" when none is given."
  [country]
  (let [code (str/lower-case (name (or country "us")))]
    (when-not (re-matches #"[a-z]{2}" code)
      (throw (ex-info "A country is two letters" {:country country})))
    code))

(defn- apple-request
  [endpoint {:keys [q id limit country]}]
  (case endpoint
    :search   (get-request (str "https://itunes.apple.com/search?"
                                (http/query-string{:media "podcast" :entity "podcast" :term q :limit limit
                                                     :country (some-> country name)})))
    :lookup   (get-request (str "https://itunes.apple.com/lookup?"
                                (http/query-string{:id (if (coll? id) (str/join "," id) id) :entity "podcast"})))
    :trending (get-request (str "https://rss.marketingtools.apple.com/api/v2/" (country-code country)
                                "/podcasts/top/" (long (or limit 25)) "/podcasts.json"))))

(defn- fyyd-request
  [endpoint {:keys [q id limit language]}]
  (let [base "https://api.fyyd.de/0.2/"]
    (case endpoint
      :search   (get-request (str base "search/podcast?" (http/query-string{:term q :count limit})))
      :lookup   (get-request (str base "podcast?" (http/query-string{:podcast_id id})))
      :trending (get-request (str base "feature/podcast/hot?" (http/query-string{:count limit :language language}))))))

(defn- gpodder-request
  [endpoint {:keys [q id limit]}]
  (case endpoint
    :search   (get-request (str "https://gpodder.net/search.json?" (http/query-string{:q q})))
    :lookup   (get-request (str "https://gpodder.net/api/2/data/podcast.json?" (http/query-string{:url id})))
    :trending (get-request (str "https://gpodder.net/toplist/" (long (or limit 25)) ".json"))))

(defn- podcastindex-request
  [endpoint {:keys [q id limit language]} credentials opts]
  (when-not credentials
    (throw (ex-info "The Podcast Index needs credentials under :credentials" {:source :podcastindex})))
  (case endpoint
    :search   (index/request credentials :search/by-term {:q q :max limit} opts)
    :lookup   (if (guid/guid? (str id))
                (index/request credentials :podcasts/by-guid {:guid id} opts)
                (index/request credentials :podcasts/by-feed-id {:id id} opts))
    :trending (index/request credentials :podcasts/trending {:max limit :lang language} opts)))

(defn request
  "The request map for `endpoint` at `source` with `params`, see
  `endpoints`: `{:method :get :url :headers}`. `opts` carry the
  `:credentials` of the Podcast Index, `{:key :secret}`, and may fix its
  `:auth-date` as `index/request` does."
  ([source endpoint params]
   (request source endpoint params {}))
  ([source endpoint params {:keys [credentials] :as opts}]
   {:pre [(map? opts)]}
   (when-not (contains? sources source)
     (throw (ex-info (str "A directory the library does not know: " source) {:source source})))
   (when-not (contains? endpoints endpoint)
     (throw (ex-info (str "An endpoint the directories do not answer: " endpoint) {:endpoint endpoint})))
   (case source
     :apple        (apple-request endpoint params)
     :fyyd         (fyyd-request endpoint params)
     :gpodder      (gpodder-request endpoint params)
     :podcastindex (podcastindex-request endpoint params credentials (select-keys opts [:auth-date])))))

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
                :directory/source        :apple
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
            :directory/source  :apple
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
                :directory/source        :fyyd
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
                :directory/source       :gpodder
                :directory/id           url
                :directory/url          mygpo_link
                :directory/subscribers  subscribers
                :directory/raw          r})
      (with-feed-url url)))

(defn- podcastindex-show
  "A show from the feed map `m` that `index/response` read, its `:index/*`
  keys kept."
  [m]
  (-> (present (assoc m
                      :directory/source        :podcastindex
                      :directory/id            (:index/id m)
                      :directory/url           (some->> (:index/id m) (str "https://podcastindex.org/podcast/"))
                      :directory/episode-count (:index/episode-count m)
                      :directory/raw           (:index/raw m)))
      (with-feed-url (:index/url m))))

(defn- ranked
  "`shows` with their position in a chart under `:directory/rank`."
  [shows]
  (map-indexed (fn [i show] (assoc show :directory/rank (inc i))) shows))

(defn response
  "The shows in the JSON `body` that `source` answered for `endpoint`, a
  vector: one for a lookup, in chart order with `:directory/rank` for
  trending, empty when the body reports a failure. Throws only when the
  body is not JSON at all."
  [source endpoint body]
  (let [shows (if (= :podcastindex source)
                (let [{:keys [status feed feeds]} (index/response body)]
                  (when status
                    (map podcastindex-show (or feeds (when feed [feed])))))
                (let [raw (json/read-str body)]
                  (case source
                    :apple   (if (= :trending endpoint)
                               (map apple-chart-show (get-in raw ["feed" "results"]))
                               (map apple-show (filter #(= "podcast" (get % "kind")) (get raw "results"))))
                    :fyyd    (when (= 1 (get raw "status"))
                               (let [data (get raw "data")]
                                 (map fyyd-show (if (map? data) [data] data))))
                    :gpodder (map gpodder-show (if (map? raw) [raw] raw)))))]
    (vec (if (= :trending endpoint) (ranked shows) shows))))

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
     "Send `endpoint` with `params` to `source` with the library's HTTP
     client and read the shows it answers with; `opts` as for `request`,
     plus `:timeout-seconds` to wait for an answer (default 15, since a
     directory that hangs should cost little)."
     ([source endpoint params]
      (call! source endpoint params {}))
     ([source endpoint params opts]
      (let [req (assoc (request source endpoint params opts) :timeout-seconds (:timeout-seconds opts 15))]
        (response source endpoint (:body (http/call! req)))))))

#?(:clj
   (defn- completed!
     "The `shows` a `source` answered for `endpoint`, with what a chart
     leaves out fetched: Apple's names no feed, so its entries are looked
     up by id, all in one request, for `combine` to fold in."
     [source endpoint shows opts]
     (if (and (= :apple source) (= :trending endpoint) (seq shows))
       (concat shows (call! :apple :lookup {:id (keep :directory/id shows)} opts))
       shows)))

#?(:clj
   (defn- fan-out!
     "Ask every one of `sources` for `endpoint` with `params` at once and
     `combine` what they answer: `{:results [...] :failed {source message}}`,
     a source that fails costing nothing but its absence."
     [sources endpoint params opts]
     (let [answers (mapv (fn [source]
                           (future (try [source (completed! source endpoint (call! source endpoint params opts) opts)]
                                        (catch Exception e [source e]))))
                         sources)
           answers (map deref answers)
           failed  (into {} (for [[source answer] answers
                                  :when (instance? Throwable answer)]
                              [source (or (ex-message answer) (str answer))]))
           results (combine (mapcat (fn [[_ answer]] (when-not (instance? Throwable answer) answer)) answers))]
       (cond-> {:results results}
         (seq failed) (assoc :failed failed)))))

#?(:clj
   (defn search!
     "The shows matching the words `q` across the directories, as
     `{:results [...] :failed {...}}`: one entry per show, the ones most
     sources agree on first, and what any source that failed said. `opts`:

         {:sources         [:apple :fyyd]   ; default: every source without credentials,
                                            ; plus the Podcast Index when given them
          :limit           10               ; per source
          :country         \"dk\"             ; Apple's store
          :language        \"da\"             ; the Podcast Index's filter
          :credentials     {:key :secret}    ; for the Podcast Index
          :timeout-seconds 15}               ; to wait for each source"
     ([q]
      (search! q {}))
     ([q opts]
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
