(ns dk.simongray.podcast.directory
  "Apple Podcasts, fyyd, gpodder.net and the Podcast Index behind one
  shape. You can search them, look shows up, read their charts and list
  episodes, and every answer is keyed like a parsed feed.

  A request is a map that any HTTP client can send, and a response reads
  into shows, which are maps with the keys of a parsed feed where the
  directory has the same information:

  - :rss/title, :itunes/author and :itunes/image
  - the URL of the feed as the :atom/links entry with rel self
  - :podcast/guid when the directory knows the one that the feed declares.
    A guid derived from the URL would be a guess, since a feed that
    moved keeps the guid of its first URL.
  - keys in the directory namespace for what only the directory knows,
    such as :directory/source, :directory/id and the record itself as
    :directory/raw

  Apple, fyyd and gpodder.net need no credentials, and the Podcast Index
  takes its under the :credentials option."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.impl :as impl]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def apple-genres
  "The ids of the genres of Apple Podcasts by name, as the genre service
  of Apple gave them on 29 September 2026. The names are the categories
  of itunes:category, of both levels.

  The entry of Apple in sources holds them as :genres, so you can give
  another table in an entry of your own."
  {"Arts" 1301
   "Food" 1306 "Design" 1402 "Performing Arts" 1405 "Visual Arts" 1406
   "Fashion & Beauty" 1459 "Books" 1482

   "Comedy" 1303
   "Improv" 1495 "Comedy Interviews" 1496 "Stand-Up" 1497

   "Education" 1304
   "Language Learning" 1498 "How To" 1499 "Self-Improvement" 1500
   "Courses" 1501

   "Kids & Family" 1305
   "Education for Kids" 1519 "Stories for Kids" 1520 "Parenting" 1521
   "Pets & Animals" 1522

   "TV & Film" 1309
   "TV Reviews" 1561 "After Shows" 1562 "Film Reviews" 1563
   "Film History" 1564 "Film Interviews" 1565

   "Music" 1310
   "Music Commentary" 1523 "Music History" 1524 "Music Interviews" 1525

   "Religion & Spirituality" 1314
   "Buddhism" 1438 "Christianity" 1439 "Islam" 1440 "Judaism" 1441
   "Spirituality" 1444 "Hinduism" 1463 "Religion" 1532

   "Technology" 1318

   "Business" 1321
   "Careers" 1410 "Investing" 1412 "Management" 1491 "Marketing" 1492
   "Entrepreneurship" 1493 "Non-Profit" 1494

   "Society & Culture" 1324
   "Personal Journals" 1302 "Places & Travel" 1320 "Philosophy" 1443
   "Documentary" 1543 "Relationships" 1544

   "Fiction" 1483
   "Drama" 1484 "Science Fiction" 1485 "Comedy Fiction" 1486

   "History" 1487

   "True Crime" 1488

   "News" 1489
   "Business News" 1490 "Daily News" 1526 "Politics" 1527 "Tech News" 1528
   "Sports News" 1529 "News Commentary" 1530 "Entertainment News" 1531

   "Leisure" 1502
   "Automotive" 1503 "Aviation" 1504 "Hobbies" 1505 "Crafts" 1506
   "Games" 1507 "Home & Garden" 1508 "Video Games" 1509
   "Animation & Manga" 1510

   "Government" 1511

   "Health & Fitness" 1512
   "Alternative Health" 1513 "Fitness" 1514 "Nutrition" 1515
   "Sexuality" 1516 "Mental Health" 1517 "Medicine" 1518

   "Science" 1533
   "Natural Sciences" 1534 "Social Sciences" 1535 "Mathematics" 1536
   "Nature" 1537 "Astronomy" 1538 "Chemistry" 1539 "Earth Sciences" 1540
   "Life Sciences" 1541 "Physics" 1542

   "Sports" 1545
   "Soccer" 1546 "Football" 1547 "Basketball" 1548 "Baseball" 1549
   "Hockey" 1550 "Running" 1551 "Rugby" 1552 "Golf" 1553 "Cricket" 1554
   "Wrestling" 1555 "Tennis" 1556 "Volleyball" 1557 "Swimming" 1558
   "Wilderness" 1559 "Fantasy Sports" 1560})

;; TODO: Spotify, Podchaser, Listen Notes and Taddy as sources, each with
;; its requests, its answers read into shows and its credentials. Spotify
;; needs a step that gets its client-credentials token, and its ids belong
;; with the others in directory.links.
(def sources
  "The directories that podcast-clj knows, by name. An entry has the :api
  that the directory speaks, its :name for a listener, whether it needs
  credentials, the :calls that it answers, and the URLs of the service.

  A source is given by its name here, or as an entry of this shape. So
  you can reach another service with the same API:

      (assoc (:fyyd sources)
             :id :mirror :root \"https://fyyd.example.org/0.2/\")

  The :id of such an entry names the source in the results. Without an
  :id, the name of its API does."
  {:apple        {:api          :apple
                  :name         "Apple Podcasts"
                  :credentials? false
                  :calls        #{:search :lookup :trending :episodes}
                  :search       "https://itunes.apple.com/search"
                  :lookup       "https://itunes.apple.com/lookup"
                  :trending     "https://rss.marketingtools.apple.com/api/v2/"
                  :genre-charts "https://itunes.apple.com/"
                  :genres       apple-genres
                  :genre-names  (str "https://itunes.apple.com/WebObjects/"
                                     "MZStoreServices.woa/ws/genres")}
   :fyyd         {:api          :fyyd
                  :name         "fyyd"
                  :credentials? false
                  :calls        #{:search :lookup :trending :episodes :recent}
                  :root         "https://api.fyyd.de/0.2/"}
   :gpodder      {:api          :gpodder
                  :name         "gpodder.net"
                  :credentials? false
                  :calls        #{:search :lookup :trending}
                  :root         "https://gpodder.net/"}
   :podcastindex {:api          :podcastindex
                  :name         "Podcast Index"
                  :credentials? true
                  :calls        #{:search :lookup :trending :episodes
                                  :by-person :recent :live}
                  :root         index/base-url
                  :pages        "https://podcastindex.org/podcast/"}})

(def calls
  "The calls of the sources, with their parameters. Every source answers
  the first three with shows:

  - :search takes :q and :limit, with :country for Apple
  - :lookup takes the :id of the show at the source, which for
    gpodder.net is the feed URL and for Apple can be several ids at once.
    The Podcast Index also takes the :url of the feed in its place, and a
    podcast:guid as the :id.
  - :trending takes :limit, with :country for Apple and :language for
    fyyd and the Podcast Index. A source that can take neither of those
    that are given throws, since its chart would be of the wrong shows.

  The others give episodes, and the :calls of an entry of sources say
  which it answers. A source throws for one that it doesn't answer.

  - :episodes takes the :id of the show, or the :url of its feed at the
    Podcast Index, and :limit, for its newest episodes
  - :by-person takes :q, a name, and :limit, for the episodes that name
    the person as a podcast:person or in their text
  - :recent takes :limit, for the newest episodes of any show
  - :live takes :limit, for the live items that are on air

  The :trending call also takes :category, for the chart of one
  category. It's a name of the categories of Apple Podcasts, such as
  Technology or Society & Culture. A map gives each source a category of
  its own, by the id of the source. Each source reads the category in its
  own way:

  - Apple takes a name or an id of the :genres of its entry.
  - The Podcast Index takes a name or an id of its categories, or several.
    It has the top categories of Apple, and words of its own for the rest.
  - The category is a tag of gpodder.net, in lower case.
  - The chart of fyyd has no categories, so asking for one throws."
  #{:search :lookup :trending :episodes :by-person :recent :live})

(defn entry
  "The entry of `source`, a name in sources or an entry itself, with the
  :id that names it in the results. It throws for a source whose API
  podcast-clj doesn't know."
  [source]
  (let [e (if (map? source) source (get sources source))]
    (when-not (contains? sources (:api e))
      (throw (ex-info (str "A directory the library does not know: " source)
                      {:source source})))
    (assoc e :id (impl/source-id source))))

(defn request
  "The request map for `call` at `source` with `params` and `opts`, a
  map of :method, :url and :headers. See calls for each call and
  its parameters.

  The `source` is a name in sources, or an entry of the shape of its
  entries. The `opts` are for the Podcast Index:

  - :credentials, a map of :key and :secret
  - :auth-date, the time in Unix seconds that signs a request"
  ([source call params]
   (request source call params {}))
  ([source call params {:keys [credentials] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [api] :as e} (entry source)
         params              (cond-> params
                               (contains? params :category)
                               (update :category #(impl/category-for e %)))]
     (when-not (contains? calls call)
       (throw (ex-info (str "A call the directories do not answer: " call)
                       {:call call})))
     (when-not (contains? (or (:calls e) (get-in sources [api :calls])) call)
       (throw (ex-info (str (:name e (name (:id e))) " has no call " call)
                       {:source (:id e) :call call})))
     (when (= :trending call)
       (impl/check-region e params))
     (case api
       :apple        (impl/apple-request e call params)
       :fyyd         (impl/fyyd-request e call params)
       :gpodder      (impl/gpodder-request e call params)
       :podcastindex (impl/podcastindex-request
                      e call params credentials
                      (select-keys opts [:auth-date]))))))

(defn- ranked
  "The `shows` with their place in a chart as :directory/rank."
  [shows]
  (map-indexed (fn [i show] (assoc show :directory/rank (inc i))) shows))

(defn response
  "The shows in the JSON `body` that `source` answered for `call`, or the
  episodes for a call of episode-calls, as a vector. It throws only when
  the body isn't JSON.

  A lookup gives one show. The shows of trending are in the order of the
  chart, each with its :directory/rank. The vector is empty when the body
  tells of a failure. The `source` is a name in sources or an entry, and
  each show names it as :directory/source.

  An episode has the keys of a parsed item, its :directory/id and
  :directory/source, and the show that it's an episode of as
  :directory/show, as much as the source tells of the show."
  [source call body]
  (let [{:keys [api id pages]} (entry source)
        named #(assoc % :directory/source id)]
    (if (contains? impl/episode-calls call)
      (mapv #(-> % named (update :directory/show named))
            (impl/episodes api body))
      (let [shows (if (= :podcastindex api)
                    (impl/index-shows body pages)
                    (let [raw (json/read-str body)]
                      (case api
                        :apple   (impl/apple-shows raw call)
                        :fyyd    (when (= 1 (get raw "status"))
                                   (map impl/fyyd-show
                                        (impl/records (get raw "data"))))
                        :gpodder (map impl/gpodder-show
                                      (impl/records raw)))))
            shows (map named shows)]
        (vec (if (= :trending call) (ranked shows) shows))))))

(defn combine
  "One entry per show across `results` from several sources, in the order
  they were first found.

  An entry is the record of the first source, with the fields it lacked
  filled from the later ones. Its :directory/sources names every source
  that had the show, by its id there. A chart entry names no feed. It
  gains one when it meets its lookup by the id they share, or the same
  show from elsewhere by its title and author."
  [results]
  (let [state {:entries [] :by-id {} :by-guid {} :by-name {}}]
    (:entries (reduce impl/with-show state results))))

(defn call!
  "Send `call` with `params` and `opts` to `source`, and read the shows or
  the episodes in the answer, in ClojureScript in a promise.

  The `opts` are those of request, and these:

  - :send, the function that sends the request, client/send! by default
  - :timeout-seconds, the time to wait for an answer. It's 15 by default,
    so a directory that doesn't answer costs little.
  - :user-agent, as http/send! takes it

  A 404 gives none. Any other status that isn't a success throws with the
  :type ::failed, so a refused key doesn't look like an empty answer.
  There are at most the :limit of `params`, since gpodder.net takes no
  limit."
  ([source call params]
   (call! source call params {}))
  ([source call {:keys [limit] :as params}
    {:keys [send] :or {send client/send!} :as opts}]
   {:pre [(map? opts)]}
   (let [auth-date (time/unix-seconds (time/now))
         opts      (cond-> opts
                     (not (:auth-date opts)) (assoc :auth-date auth-date))
         timeout   (:timeout-seconds opts 15)
         req       (-> (request source call params opts)
                       (merge (select-keys opts [:user-agent]))
                       (assoc :timeout-seconds timeout))
         id        (impl/source-id source)]
     (async/then
      (send req)
      (fn [{:keys [status body]}]
        (cond
          (<= 200 status 299)
          (cond->> (response source call body)
            limit (into [] (take limit)))

          (= 404 status)
          []

          :else
          (throw (ex-info (str (name id) " answered " status)
                          {:type ::failed :source id :status status}))))))))

(defn- complete!
  "Complete the `shows` that `source` answered for `call` with what a
  chart leaves out, fetched with `opts`. Apple's chart names no feed, so
  its entries are looked up by id in one request. Deferred."
  [source call shows opts]
  (if (and (= :apple (:api (entry source)))
           (= :trending call)
           (seq shows))
    (let [ids (keep :directory/id shows)]
      (async/then (call! source :lookup {:id ids} opts)
                  #(concat shows %)))
    shows))

(defn- combined
  "The shows that `sources` answered, combined as :results, and a
  diagnostic for each source that failed under :report. The `answers` are
  attempts, maps of a :value or an :error, one for each of `sources`."
  [sources answers]
  (let [report (for [[source {:keys [error]}] (map vector sources answers)
                     :when error]
                 (diagnostics/describe {:id     :directory/source-failed
                                        :source (impl/source-id source)
                                        :reason (async/explain error)}))]
    (cond-> {:results (combine (mapcat :value answers))}
      (seq report) (assoc :report (vec report)))))

(defn- fan-out!
  "Ask each of `sources` for `call` with `params` and `opts` at once,
  and combine what they answer, in ClojureScript in a promise.

  The result is a map of the :results and, under :report, a diagnostic
  for each source that failed. A source that fails costs nothing but its
  absence."
  [sources call params opts]
  (let [ask! (fn [source]
               (async/attempt
                #(async/then (call! source call params opts)
                             (fn [shows]
                               (complete! source call shows opts)))))]
    (async/then (async/all (map ask! sources))
                #(combined sources %))))

(defn- most-sources-first
  "The `shows` as a vector, those that the most sources have first."
  [shows]
  (vec (sort-by #(- (count (:directory/sources %))) shows)))

(defn search!
  "Search the directories for the shows that match the words `q`, with
  `opts`. It gives a map of the :results and, when a source failed, the
  :report, in ClojureScript in a promise.

  The results have one entry for each show, and the shows that the most
  sources have come first. The report has a :directory/source-failed
  diagnostic for each source that failed, with its :source and the
  :reason. The `opts` are these:

  - :sources, each a name in sources or an entry. By default, they're
    the sources without credentials, and the Podcast Index with
    :credentials.
  - :limit, the number of shows from each source
  - :country, the store of Apple, e.g. \"dk\"
  - :credentials, the :key and :secret of the Podcast Index
  - :timeout-seconds, the time to wait for each source
  - :send, the function that sends each request, client/send! by default"
  ([q]
   (search! q {}))
  ([q opts]
   {:pre [(map? opts)]}
   (let [sources (or (:sources opts) (impl/default-sources opts))
         params  (assoc (select-keys opts [:limit :country]) :q q)]
     (async/then (fan-out! sources :search params opts)
                 #(update % :results most-sources-first)))))

(defn fetch-chart!
  "Fetch the charts of the directories with `opts`, and combine them into
  one entry for each show. The shows are in the order that the sources
  list them.

  The `opts` are those of search!, and these:

  - :language, the language of the shows in the charts of fyyd and the
    Podcast Index, e.g. \"da\"
  - :category, for the charts of one category, as calls tells

  A source that has no chart of the category, the :country or the
  :language is in the :report, with the reason."
  ([]
   (fetch-chart! {}))
  ([opts]
   {:pre [(map? opts)]}
   (fan-out! (or (:sources opts) (impl/default-sources opts))
             :trending
             (select-keys opts [:limit :country :language :category])
             opts)))

(defn- apple-id
  "The Apple id `n` that the Podcast Index gives a show, as Apple writes
  it, or nil when the index knows none."
  [n]
  (when (and (number? n) (pos? n))
    (str (long n))))

(defn identify!
  "Ask the directories for the ids of the show of `feed` at `url`, with
  `opts`, as a map of the :ids by source, e.g. {:podcastindex 920666
  :apple \"1584274529\"}, in ClojureScript as a promise.

  The Podcast Index is asked when the `opts` have its :credentials, by the
  podcast:guid of the feed or else by `url`, and its answer often has the
  Apple id too. Otherwise Apple is asked by the title of the show, and its
  answer counts only for a show at `url`, since shows share titles. A
  source that fails is in the :report, and its id is left out. The `opts`
  are those of call!, and :country for Apple."
  ([feed url]
   (identify! feed url {}))
  ([feed url {:keys [credentials country] :as opts}]
   {:pre [(map? opts)]}
   (let [declared (:podcast/guid feed)
         lookup   (if (guid/guid? (str declared)) {:id declared} {:url url})
         title    (resolve/title feed)
         here     (guid/normal-url url)
         same?    #(= here (some-> (resolve/self-url %) guid/normal-url))
         attempt  (fn [source call params]
                    (async/attempt #(call! source call params opts)))
         failed   (fn [report source error]
                    (cond-> report
                      error (conj (diagnostics/describe
                                   {:id     :directory/source-failed
                                    :source source
                                    :reason (async/explain error)}))))
         result   (fn [ids report]
                    (cond-> {:ids ids} (seq report) (assoc :report report)))]
     (async/then
      (if credentials
        (attempt :podcastindex :lookup lookup)
        {:value []})
      (fn [{:keys [value error]}]
        (let [show   (first value)
              ids    (into {}
                           (remove (comp nil? val))
                           {:podcastindex (:index/id show)
                            :apple        (apple-id (:index/itunes-id show))})
              report (failed [] :podcastindex error)
              params (cond-> {:q title :limit 10}
                       country (assoc :country country))]
          (if (or (:apple ids) (not title))
            (result ids report)
            (async/then
             (attempt :apple :search params)
             (fn [{:keys [value error]}]
               (let [found (some #(when (same? %) (:directory/id %)) value)]
                 (result (cond-> ids found (assoc :apple found))
                         (failed report :apple error))))))))))))

(defn category-names-request
  "The request to Apple's genre service for the names of the categories of
  Apple Podcasts in `language`, as the store of `country` has them, with
  `opts`. Both are two letters, e.g. \"da\" and \"dk\".

  The service is at the :genre-names of Apple's entry in sources, or of
  the :source of `opts`. Apple doesn't document it."
  ([country language]
   (category-names-request country language {}))
  ([country language {:keys [source] :or {source :apple} :as opts}]
   {:pre [(map? opts)]}
   (when-not (re-matches #"[a-z]{2}" (str language))
     (throw (ex-info "A language is two letters" {:language language})))
   (impl/get-request (str (:genre-names (entry source))
                          "?id=" impl/podcasts-genre
                          "&cc=" (impl/country-code country)
                          "&l=" language))))

(defn category-names
  "The localised names of the categories of Apple Podcasts in `body`, the
  JSON that the genre service answered, with `opts`. It's a map from the
  English name that itunes:category uses to the localised one, e.g.
  {\"Society & Culture\" \"Samfund og kultur\"}.

  The English names are the :genres of Apple's entry in sources, or of the
  :source of `opts`. A category missing from either is left out, so show
  its English name instead."
  ([body]
   (category-names body {}))
  ([body {:keys [source] :or {source :apple} :as opts}]
   {:pre [(map? opts)]}
   (let [names (impl/genre-names (json/read-str body))]
     (into {}
           (keep (fn [[english id]]
                   (when-let [local (not-empty (get names id))]
                     [english local])))
           (:genres (entry source))))))

(defn fetch-category-names!
  "Fetch the localised names of the categories of Apple Podcasts in
  `language`, as the store of `country` has them, with `opts`. It returns
  a map with the :names that category-names returns, in ClojureScript as a
  promise. Keep the names, since they rarely change.

  The `opts` are those of category-names-request and call!. Nothing that
  the network or Apple does throws. When the names don't arrive, the
  result has the diagnostic :directory/source-failed under :error."
  ([country language]
   (fetch-category-names! country language {}))
  ([country language {:keys [send source]
                      :or   {send client/send! source :apple}
                      :as   opts}]
   {:pre [(map? opts)]}
   (let [req     (-> (category-names-request country language opts)
                     (merge (select-keys opts [:user-agent]))
                     (assoc :timeout-seconds (:timeout-seconds opts 15)))
         names   (fn [{:keys [status body]}]
                   (when-not (<= 200 status 299)
                     (throw (ex-info (str "Apple answered " status)
                                     {:status status})))
                   (category-names body opts))
         failure (fn [error]
                   (diagnostics/failed {:id     :directory/source-failed
                                        :source (impl/source-id source)
                                        :reason (async/explain error)}))]
     (async/then (async/attempt #(async/then (send req) names))
                 (fn [{:keys [value error]}]
                   (if error
                     (failure error)
                     {:names value}))))))

#?(:clj
   (comment
     (category-names-request "dk" "da")
     (:names (fetch-category-names! "dk" "da"))
     (request :apple :search {:q "podcasting 2.0" :limit 3})
     (call! :fyyd :search {:q "podcasting 2.0" :limit 3})
     (:results (search! "podcasting 2.0" {:limit 3}))
     (map :rss/title (:results (fetch-chart! {:country "dk" :limit 5})))
     #_.))
