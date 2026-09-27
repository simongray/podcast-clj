(ns dk.simongray.podcast.directory.podcastindex
  "The Podcast Index API (https://podcastindex-org.github.io/docs-api/) as
  request maps and model maps.

  `request` builds `{:method :url :headers}` with the authentication
  computed for the instant given, for any HTTP client to send; `response`
  reads a response body into data shaped like the feed model, so that a
  feed or an episode from the index carries the same keys as one parsed
  from RSS, plus what only the index knows under `:index/*`. The key and
  secret never leave the caller's process, and nothing here reads a clock
  but `call!`, `remote-value!` and `remote-item!`, which send with the
  library's own HTTP client.

  The remote items that value time splits point at are resolved with
  `remote-value-requests`, the requests to try in order, or `remote-value`,
  which sends them with a function you supply."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.http :as http]))

(def base-url
  "The root of the API's URLs; `endpoints` are relative to it."
  "https://api.podcastindex.org/api/1.0/")

(def endpoints
  "API paths by endpoint keyword, as the API's OpenAPI description lists
  them. Each takes its parameters as a query."
  {:search/all                  "search"
   :search/by-term              "search/byterm"
   :search/by-title             "search/bytitle"
   :search/by-person            "search/byperson"
   :search/music-by-term        "search/music/byterm"
   :podcasts/by-feed-id         "podcasts/byfeedid"
   :podcasts/by-feed-url        "podcasts/byfeedurl"
   :podcasts/by-guid            "podcasts/byguid"
   :podcasts/batch-by-guid      "podcasts/batch/byguid"
   :podcasts/by-itunes-id       "podcasts/byitunesid"
   :podcasts/by-tag             "podcasts/bytag"
   :podcasts/by-medium          "podcasts/bymedium"
   :podcasts/trending           "podcasts/trending"
   :podcasts/dead               "podcasts/dead"
   :episodes/by-feed-id         "episodes/byfeedid"
   :episodes/by-feed-url        "episodes/byfeedurl"
   :episodes/by-podcast-guid    "episodes/bypodcastguid"
   :episodes/by-guid            "episodes/byguid"
   :episodes/by-id              "episodes/byid"
   :episodes/by-itunes-id       "episodes/byitunesid"
   :episodes/live               "episodes/live"
   :episodes/random             "episodes/random"
   :recent/episodes             "recent/episodes"
   :recent/feeds                "recent/feeds"
   :recent/new-feeds            "recent/newfeeds"
   :recent/new-value-feeds      "recent/newvaluefeeds"
   :recent/data                 "recent/data"
   :recent/soundbites           "recent/soundbites"
   :value/by-feed-id            "value/byfeedid"
   :value/by-feed-url           "value/byfeedurl"
   :value/by-podcast-guid       "value/bypodcastguid"
   :value/by-episode-guid       "value/byepisodeguid"
   :value/batch-by-episode-guid "value/batch/byepisodeguid"
   :categories/list             "categories/list"
   :stats/current               "stats/current"
   :hub/pub-notify              "hub/pubnotify"
   :add/by-feed-url             "add/byfeedurl"
   :add/by-itunes-id            "add/byitunesid"
   :lookup                      "lookup"})

(defn- now-seconds
  "The current Unix time in seconds, for the calls that send."
  []
  #?(:clj  (quot (System/currentTimeMillis) 1000)
     :cljs (quot (.now js/Date) 1000)))

(defn auth-headers
  "The headers that authenticate a request with `key` and `secret` at Unix
  time `seconds`: the date, the key and the SHA-1 of key, secret and date.
  The API requires a descriptive User-Agent; `:user-agent` in the
  credentials replaces the library's."
  [{:keys [key secret user-agent]} seconds]
  {"User-Agent"    (or user-agent http/user-agent)
   "X-Auth-Key"    key
   "X-Auth-Date"   (str seconds)
   "Authorization" (guid/sha1-hex (str key secret seconds))})

(defn request
  "The request map for `endpoint` (a key of `endpoints`) with query
  `params`, authenticated with `credentials` (`:key`, `:secret`, optional
  `:user-agent`) at the `:auth-date` in `opts`, Unix seconds, which the
  API accepts within a few minutes of its own clock; the caller reads the
  clock, as `call!` does."
  [credentials endpoint params {:keys [auth-date] :as opts}]
  {:pre [(map? opts)]}
  (when-not auth-date
    (throw (ex-info "A request is signed at an :auth-date, Unix seconds" {:endpoint endpoint})))
  (let [path (or (endpoints endpoint)
                 (throw (ex-info "Unknown Podcast Index endpoint" {:endpoint endpoint})))
        qs   (http/query-string params)]
    {:method  :get
     :url     (str base-url path (when (seq qs) (str "?" qs)))
     :headers (auth-headers credentials auth-date)}))

(defn- ->number
  [x]
  (cond
    (number? x) x
    (string? x) (or (parse-long x) (parse-double x))
    :else       nil))

(defn- ->boolean
  "The index writes flags as booleans, as 0 and 1, and as strings."
  [x]
  (cond
    (boolean? x) x
    (number? x)  (not (zero? x))
    (string? x)  (contains? #{"true" "1" "yes"} (str/lower-case x))
    :else        nil))

(defn- ->inst
  "The instant of the Unix time `seconds` the index gives, nil when absent."
  [seconds]
  (when (and (number? seconds) (pos? seconds))
    (time/->inst (* 1000 seconds))))

(defn- ->positive
  "The number `x`, or nil: the index writes 0 for a season, episode number
  or duration it does not know."
  [x]
  (let [n (->number x)]
    (when (and n (pos? n)) n)))

(defn- pick
  "The model map for `m` by `fields`, each `[index-key model-key convert]`,
  leaving out what `m` lacks or what converts to nil."
  [fields m]
  (reduce (fn [acc [k model-key convert]]
            (let [v (get m k)
                  v (if convert (convert v) v)]
              (if (some? v) (assoc acc model-key v) acc)))
          {}
          fields))

(defn- destination->recipient
  [{:strs [name address type split fee customKey customValue]}]
  (cond-> {}
    name                    (assoc :name name)
    type                    (assoc :type type)
    address                 (assoc :address address)
    (some? (->number split)) (assoc :split (->number split))
    (->boolean fee)         (assoc :fee true)
    customKey               (assoc :custom-key (str customKey))
    customValue             (assoc :custom-value (str customValue))))

(defn value->block
  "The value block for the index's value object `v`, `{\"model\" {...}
  \"destinations\" [...]}`, in the shape `podcast:value` takes in the model;
  nil when `v` is not one."
  [v]
  (when (map? v)
    (let [{:strs [model destinations]} v
          suggested (->number (get model "suggested"))]
      (cond-> {}
        (get model "type")   (assoc :type (get model "type"))
        (get model "method") (assoc :method (get model "method"))
        (some? suggested)    (assoc :suggested suggested)
        (seq destinations)   (assoc :recipients (mapv destination->recipient destinations))))))

(def ^:no-doc feed-fields
  "The scalar fields of the index's feed object and their model keys."
  [["title" :rss/title] ["link" :rss/link] ["description" :rss/description]
   ["language" :rss/language] ["generator" :rss/generator]
   ["author" :itunes/author] ["explicit" :itunes/explicit ->boolean] ["itunesType" :itunes/type]
   ["podcastGuid" :podcast/guid] ["medium" :podcast/medium]
   ["id" :index/id] ["url" :index/url] ["originalUrl" :index/original-url]
   ["itunesId" :index/itunes-id] ["episodeCount" :index/episode-count]
   ["dead" :index/dead? ->boolean] ["artwork" :index/artwork] ["contentType" :index/content-type]
   ["lastUpdateTime" :index/last-update-time ->inst] ["lastCrawlTime" :index/last-crawl-time ->inst]
   ["newestItemPubdate" :index/newest-item-published ->inst] ["lastHttpStatus" :index/last-http-status]
   ["type" :index/format #(case % 0 "rss" 1 "atom" nil)]])

(defn feed->model
  "The feed object `f` of the index as a feed map: the keys a parsed feed
  has where the index carries the same information, `:index/*` for what only
  the index knows, and the original under `:index/raw`."
  [f]
  (let [{:strs [image ownerName locked value funding categories]} f]
    (cond-> (assoc (pick feed-fields f) :index/raw f)
      image              (assoc :itunes/image {:href image})
      ownerName          (assoc :itunes/owner {:name ownerName})
      (some? locked)     (assoc :podcast/locked {:content (boolean (->boolean locked))})
      (value->block value) (assoc :podcast/values [(value->block value)])
      (get funding "url") (assoc :podcast/funding [(cond-> {:url (get funding "url")}
                                                     (get funding "message") (assoc :content (get funding "message")))])
      (seq categories)   (assoc :itunes/categories (vec (for [[_ text] (sort-by (comp ->number key) categories)]
                                                           {:text text}))))))

(def ^:no-doc episode-fields
  "The scalar fields of the index's episode object and their model keys."
  [["title" :rss/title] ["link" :rss/link] ["description" :rss/description]
   ["datePublished" :rss/pub-date ->inst]
   ["duration" :itunes/duration ->positive] ["explicit" :itunes/explicit ->boolean]
   ["episode" :itunes/episode ->positive] ["season" :itunes/season ->positive] ["episodeType" :itunes/episode-type]
   ["id" :index/id] ["feedId" :index/feed-id] ["feedUrl" :index/feed-url] ["feedTitle" :index/feed-title]
   ["feedLanguage" :index/feed-language] ["feedItunesId" :index/feed-itunes-id] ["feedImage" :index/feed-image]
   ["podcastGuid" :index/podcast-guid] ["dateCrawled" :index/date-crawled ->inst]])

(defn- person->model
  [{:strs [name role group href img]}]
  (cond-> {:content name}
    role  (assoc :role role)
    group (assoc :group group)
    href  (assoc :href href)
    img   (assoc :img img)))

(defn- social->model
  [{:strs [url protocol accountId accountUrl priority]}]
  (cond-> {}
    url        (assoc :uri url)
    protocol   (assoc :protocol protocol)
    accountId  (assoc :account-id accountId)
    accountUrl (assoc :account-url accountUrl)
    (some? (->number priority)) (assoc :priority (->number priority))))

(defn- soundbite->model
  [{:strs [startTime duration title]}]
  (cond-> {}
    (some? (->number startTime)) (assoc :start-time (->number startTime))
    (some? (->number duration))  (assoc :duration (->number duration))
    (seq title)                  (assoc :content title)))

(defn episode->model
  "The episode object `e` of the index as an item map, keyed like a parsed
  item where the index carries the same information, `:index/*` for the
  rest, and the original under `:index/raw`."
  [e]
  (let [{:strs [guid enclosureUrl enclosureType enclosureLength image chaptersUrl transcriptUrl
                transcripts persons socialInteract soundbite soundbites value]} e
        transcripts (or (seq transcripts)
                        (when transcriptUrl [{"url" transcriptUrl}]))
        soundbites  (or (seq soundbites) (when soundbite [soundbite]))]
    (cond-> (assoc (pick episode-fields e) :index/raw e)
      guid             (assoc :rss/guid {:content guid})
      enclosureUrl     (assoc :rss/enclosure (cond-> {:url enclosureUrl}
                                                enclosureType (assoc :type enclosureType)
                                                (some? (->number enclosureLength)) (assoc :length (->number enclosureLength))))
      image            (assoc :itunes/image {:href image})
      chaptersUrl      (assoc :podcast/chapters {:url chaptersUrl})
      transcripts      (assoc :podcast/transcripts (vec (for [{:strs [url type]} transcripts]
                                                          (cond-> {:url url} type (assoc :type type)))))
      (seq persons)    (assoc :podcast/people (mapv person->model persons))
      (seq socialInteract) (assoc :podcast/social-interacts (mapv social->model socialInteract))
      soundbites       (assoc :podcast/soundbites (mapv soundbite->model soundbites))
      (value->block value) (assoc :podcast/values [(value->block value)]))))

(defn response
  "The data in the JSON `body` of an API response: `:status` as a boolean,
  `:description`, `:count` and `:query` as given, feeds under `:feed` or
  `:feeds` and episodes under `:episode`, `:items` or `:live-items` in the
  shape of the model, a value block under `:value`, and the whole parsed body
  under `:raw`."
  [body]
  (let [raw (json/read-str body)]
    (cond-> {:status (= "true" (str (get raw "status"))) :raw raw}
      (contains? raw "description") (assoc :description (get raw "description"))
      (contains? raw "count")       (assoc :count (get raw "count"))
      (contains? raw "query")       (assoc :query (get raw "query"))
      (map? (get raw "feed"))       (assoc :feed (feed->model (get raw "feed")))
      (sequential? (get raw "feeds")) (assoc :feeds (mapv feed->model (get raw "feeds")))
      (map? (get raw "episode"))    (assoc :episode (episode->model (get raw "episode")))
      (sequential? (get raw "items")) (assoc :items (mapv episode->model (get raw "items")))
      (sequential? (get raw "liveItems")) (assoc :live-items (mapv episode->model (get raw "liveItems")))
      (map? (get raw "value"))      (assoc :value (value->block (get raw "value"))))))

(defn remote-value-requests
  "The requests that resolve the value block the `remote-item` of a time
  split points at, to try in order: the item's own block when the remote
  item names one, then the feed's. `opts` are those of `request`."
  [credentials {:keys [feed-guid item-guid]} opts]
  (cond-> []
    (and feed-guid item-guid)
    (conj (request credentials :value/by-episode-guid
                   {:podcastguid feed-guid :episodeguid item-guid} opts))

    feed-guid
    (conj (request credentials :value/by-podcast-guid {:guid feed-guid} opts))))

(defn- block-in
  "The value block in the response `body`, when it answers with one."
  [body]
  (when (string? body)
    (let [{:keys [status value]} (try (response body)
                                      (catch #?(:clj Exception :cljs :default) _ nil))]
      (when (and status (seq (:recipients value)))
        value))))

(defn remote-value
  "The value block the `remote-item` of a time split points at: the requests
  of `remote-value-requests`, with `opts` as for `request`, sent one by one
  with `send`, a function from a request map to a response map with the
  `:body` string, until one answers with a block. Nil when none does.
  `send` is synchronous, so in ClojureScript use the requests directly
  instead."
  [credentials remote-item send opts]
  (some #(block-in (:body (send %))) (remote-value-requests credentials remote-item opts)))

(defn remote-item-requests
  "The requests that resolve `remote-item`, a podroll, publisher, list or
  time-split entry with a `:feed-guid` and perhaps an `:item-guid`: the
  episode when it names one, then the feed. `opts` are those of `request`."
  [credentials {:keys [feed-guid item-guid]} opts]
  (cond-> []
    (and feed-guid item-guid)
    (conj (request credentials :episodes/by-guid {:guid item-guid :podcastguid feed-guid} opts))

    feed-guid
    (conj (request credentials :podcasts/by-guid {:guid feed-guid} opts))))

(defn remote-item
  "What `remote-item` points at, as `{:feed ... :episode ...}` in the shape
  of the model, by sending the requests of `remote-item-requests` with
  `send` and `opts` (see `remote-value`). Nil when the index knows
  neither."
  [credentials remote-item send opts]
  (let [answers (for [req  (remote-item-requests credentials remote-item opts)
                      :let [body (:body (send req))]
                      :when (string? body)]
                  (try (response body) (catch #?(:clj Exception :cljs :default) _ nil)))]
    (not-empty
     (into {} (for [{:keys [status feed episode]} answers
                    :when status
                    [k v] [[:feed feed] [:episode episode]]
                    :when v]
                [k v])))))

#?(:clj
   (defn call!
     "Send `endpoint` with `params` for `credentials`, signed now, with the
     library's HTTP client, and read the answer with `response`."
     [credentials endpoint params]
     (-> (request credentials endpoint params {:auth-date (now-seconds)}) http/call! :body response)))

#?(:cljs
   (defn call!
     "Send `endpoint` with `params` for `credentials`, signed now, with the
     platform's `fetch`, and read the answer with `response`. Returns a
     js/Promise of the result; in a browser the API's CORS policy decides
     whether the call is allowed at all."
     [credentials endpoint params]
     (let [{:keys [url headers]} (request credentials endpoint params {:auth-date (now-seconds)})]
       (-> (js/fetch url (clj->js {:headers headers}))
           (.then (fn [res] (.text res)))
           (.then response)))))

#?(:clj
   (defn remote-value!
     "`remote-value` with the library's HTTP client, signed now."
     [credentials remote-item]
     (remote-value credentials remote-item http/call! {:auth-date (now-seconds)})))

#?(:clj
   (defn remote-item!
     "`remote-item` with the library's HTTP client, signed now."
     [credentials remote-item]
     (remote-item credentials remote-item http/call! {:auth-date (now-seconds)})))

(comment
  (request {:key "K" :secret "S"} :search/by-term {:q "podcasting 2.0" :max 5} {:auth-date 1700000000})
  (remote-value-requests {:key "K" :secret "S"} {:feed-guid "a94f" :item-guid "x"} {:auth-date 1700000000})
  #_.)
