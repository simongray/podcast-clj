(ns dk.simongray.podcast.directory.podcastindex
  "The Podcast Index API, https://podcastindex-org.github.io/docs-api/, as
  request maps and model maps.

  A request is a map of :method, :url and :headers that any HTTP client
  can send, with the authentication for the time it's given. A response
  reads into data shaped like the feed model, so a feed or an episode from
  the index has the same keys as one parsed from RSS, and what only the
  index knows is in the index namespace.

  The key and the secret never leave your process. Nothing here reads a
  clock but the functions that send."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]))

(def base-url
  "The root of the URLs of the API, which the paths of calls are below,
  unless the options give the root of another service with the same API
  as :base-url."
  "https://api.podcastindex.org/api/1.0/")

(def paths
  "The path of each call of the API by a keyword, as its OpenAPI
  description lists them. Each takes its parameters as a query, but those
  of batch-calls."
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

;; the OpenAPI description of the API: paths/podcasts/batch/byguid.yaml and
;; paths/value/batch/byepisodeguid.yaml
(def batch-calls
  "The calls of paths that take a JSON body in a POST rather than a query:
  :podcasts/batch-by-guid takes a vector of feed guids, and
  :value/batch-by-episode-guid a map of a feed guid to its episode guids."
  #{:podcasts/batch-by-guid :value/batch-by-episode-guid})

(def categories
  "The categories of the Podcast Index by id, from categories.json of the
  podcast namespace. They are the words of the categories of Apple
  Podcasts: Society & Culture is Society and Culture here.

  The call :categories/list gives the list as the index has it now."
  (zipmap (iterate inc 1)
          ["Arts" "Books" "Design" "Fashion" "Beauty" "Food" "Performing"
           "Visual" "Business" "Careers" "Entrepreneurship" "Investing"
           "Management" "Marketing" "Non-Profit" "Comedy" "Interviews"
           "Improv" "Stand-Up" "Education" "Courses" "How-To" "Language"
           "Learning" "Self-Improvement" "Fiction" "Drama" "History" "Health"
           "Fitness" "Alternative" "Medicine" "Mental" "Nutrition" "Sexuality"
           "Kids" "Family" "Parenting" "Pets" "Animals" "Stories" "Leisure"
           "Animation" "Manga" "Automotive" "Aviation" "Crafts" "Games"
           "Hobbies" "Home" "Garden" "Video-Games" "Music" "Commentary" "News"
           "Daily" "Entertainment" "Government" "Politics" "Buddhism"
           "Christianity" "Hinduism" "Islam" "Judaism" "Religion"
           "Spirituality" "Science" "Astronomy" "Chemistry" "Earth" "Life"
           "Mathematics" "Natural" "Nature" "Physics" "Social" "Society"
           "Culture" "Documentary" "Personal" "Journals" "Philosophy" "Places"
           "Travel" "Relationships" "Sports" "Baseball" "Basketball" "Cricket"
           "Fantasy" "Football" "Golf" "Hockey" "Rugby" "Running" "Soccer"
           "Swimming" "Tennis" "Volleyball" "Wilderness" "Wrestling"
           "Technology" "True Crime" "TV" "Film" "After-Shows" "Reviews"
           "Climate" "Weather" "Tabletop" "Role-Playing" "Cryptocurrency"]))

(defn category-filter
  "The value of the parameters cat and notcat for `category`, by the
  :categories of `opts`, or else the categories table. It's nil for no
  category.

  The `category` is a name or an id of a category, or a collection of
  them. A name can hold several names with commas or ampersands between
  them, so a name of Apple Podcasts such as Society & Culture reads as its
  two categories. The case of a name doesn't count, and a space reads as
  a hyphen where the index has one.

  For a name that it doesn't know, the index gives the whole chart and no
  error. So this throws for such a name."
  ([category]
   (category-filter category {}))
  ([category opts]
   (let [known   (:categories opts categories)
         spoken  #(str/lower-case (str/replace (str %) #"[\s-]+" " "))
         by-name (into {} (map (fn [[_ name]] [(spoken name) name])) known)
         parts   (->> (if (coll? category) category [category])
                      (mapcat #(str/split (str %) #"[,&]"))
                      (map str/trim)
                      (remove str/blank?))
         named   (fn [part]
                   (or (when (re-matches #"\d{1,6}" part)
                         (get known (parse-long part)))
                       (get by-name (spoken part))
                       (throw (ex-info (str "The Podcast Index has no category "
                                            (pr-str part))
                                       {:category category}))))]
     (not-empty (str/join "," (map named parts))))))

(defn- dated
  "The `opts` with the time of their :now-fn, or else of the clock, as
  their :auth-date, unless they give one."
  [{:keys [auth-date now-fn] :or {now-fn time/now} :as opts}]
  (cond-> opts
    (not auth-date) (assoc :auth-date (time/unix-seconds (now-fn)))))

(defn ^:no-doc auth-headers
  "The headers that authenticate a request with `credentials` at the Unix
  time `seconds`: the date, the key and the SHA-1 of the key, the secret
  and the date.

  The API requires a descriptive User-Agent, and the :user-agent of the
  credentials replaces that of podcast-clj. The secret can be a hidden
  text of dk.simongray.wary-fetch.secret. It's in no header, since the
  API gets the hash, which holds for some minutes. The hash is a hidden
  text, as each Authorization header is. Credentials without a secret give
  the User-Agent alone, for a request that sender signs on your server."
  [{:keys [key secret user-agent] :as credentials} seconds]
  (cond-> {"User-Agent" (or user-agent client/user-agent)}
    secret (assoc "X-Auth-Key"    key
                  "X-Auth-Date"   (str seconds)
                  "Authorization" (-> (str key (secret/reveal secret) seconds)
                                      bytes/encode
                                      bytes/sha1
                                      bytes/hex
                                      secret/hide))))

(defn request
  "The request map for `call`, a key of paths, with `params`, signed with
  `credentials` at the :auth-date of `opts`.

  The params are the query of the call, or the JSON body of one of
  batch-calls, which is a POST. The credentials are a :key and a :secret,
  and they can give a :user-agent. The :auth-date is in Unix seconds. The
  API takes a date within a few minutes of its own clock, so read the
  clock for it.

  The request goes to base-url, or to the :base-url of `opts`."
  [credentials call params {:keys [auth-date] :as opts}]
  {:pre [(map? opts)]}
  (when-not auth-date
    (throw (ex-info "A request is signed at an :auth-date, Unix seconds"
                    {:call call})))
  (let [path   (or (paths call)
                   (throw (ex-info "Unknown Podcast Index call" {:call call})))
        root   (url/without-trailing-slashes (:base-url opts base-url))
        batch? (contains? batch-calls call)
        qs     (when-not batch?
                 (url/query-string params))]
    (cond-> {:method  :get
             :url     (str root "/" path (when (seq qs) (str "?" qs)))
             :headers (auth-headers credentials auth-date)}
      batch? (-> (assoc :method :post
                        :body   (json/write-str params))
                 (assoc-in [:headers "Content-Type"] "application/json")))))

(def ^:no-doc auth-header-names
  "The names of the headers of auth-headers in lower case, which sender
  replaces."
  #{"user-agent" "x-auth-key" "x-auth-date" "authorization"})

(defn sender
  "A function to give as :send, which signs each request to the Podcast
  Index with `credentials` and sends it with the :send of `opts`.

  Give it to the proxy on your server, e.g. (proxy/handler {:send (sender
  credentials)}), so that the secret stays there, and give the page
  credentials without a :secret. The signature replaces any that the
  request has, at the time of the :now-fn of `opts`. A request to another
  URL than those below base-url, or the :base-url of `opts`, goes as it
  is. The :send is client/send! by default."
  ([credentials]
   (sender credentials {}))
  ([credentials {:keys [send now-fn]
                 :or   {send client/send! now-fn time/now}
                 :as   opts}]
   (let [root (str (url/without-trailing-slashes (:base-url opts base-url))
                   "/")
         sign (fn [headers]
                (into (auth-headers credentials (time/unix-seconds (now-fn)))
                      (remove #(auth-header-names (str/lower-case (key %))))
                      headers))]
     (fn [{:keys [url] :as request}]
       (send (cond-> request
               (str/starts-with? (str url) root) (update :headers sign)))))))

(defn- ->number
  [x]
  (cond
    (number? x) x
    (string? x) (or (parse-long x) (parse-double x))
    :else       nil))

(defn- ->boolean
  "The flag `x`, which the index writes as a boolean, as 0 and 1, or as a
  string."
  [x]
  (cond
    (boolean? x) x
    (number? x)  (not (zero? x))
    (string? x)  (contains? #{"true" "1" "yes"} (str/lower-case x))
    :else        nil))

(defn- ->inst
  "The instant of the Unix time `seconds` that the index gives, or nil when
  it's absent."
  [seconds]
  (when (and (number? seconds) (pos? seconds))
    (time/->inst (* 1000 seconds))))

(defn- ->positive
  "The number `x`, or nil when it isn't positive. The index writes 0 for a
  season, episode number or duration that it doesn't know."
  [x]
  (let [n (->number x)]
    (when (and n (pos? n)) n)))

(defn- pick
  "The model map of `m` by `fields`, without what `m` lacks or what
  converts to nil. Each field is a vector of an index key, a model key and
  perhaps a function that converts the value."
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
    name                     (assoc :name name)
    type                     (assoc :type type)
    address                  (assoc :address address)
    (some? (->number split)) (assoc :split (->number split))
    (->boolean fee)          (assoc :fee true)
    customKey                (assoc :custom-key (str customKey))
    customValue              (assoc :custom-value (str customValue))))

(defn ^:no-doc value->block
  "The value block of the index's value object `v`, which has a model and
  destinations, in the shape that podcast:value takes in the model. It's
  nil when `v` isn't one."
  [v]
  (when (map? v)
    (let [{:strs [model destinations]} v
          suggested  (->number (get model "suggested"))
          recipients (mapv destination->recipient destinations)]
      (cond-> {}
        (get model "type")   (assoc :type (get model "type"))
        (get model "method") (assoc :method (get model "method"))
        (some? suggested)    (assoc :suggested suggested)
        (seq destinations)   (assoc :recipients recipients)))))

(def ^:no-doc feed-fields
  "The scalar fields of the index's feed object and their model keys."
  [["title"             :rss/title]
   ["link"              :rss/link]
   ["description"       :rss/description]
   ["language"          :rss/language]
   ["generator"         :rss/generator]

   ["author"            :itunes/author]
   ["explicit"          :itunes/explicit ->boolean]
   ["itunesType"        :itunes/type]

   ["podcastGuid"       :podcast/guid]
   ["medium"            :podcast/medium]

   ["id"                :index/id]
   ["url"               :index/url]
   ["originalUrl"       :index/original-url]
   ["itunesId"          :index/itunes-id]
   ["episodeCount"      :index/episode-count]
   ["dead"              :index/dead? ->boolean]
   ["artwork"           :index/artwork]
   ["contentType"       :index/content-type]
   ["lastUpdateTime"    :index/last-update-time ->inst]
   ["lastCrawlTime"     :index/last-crawl-time ->inst]
   ["newestItemPubdate" :index/newest-item-published ->inst]
   ["lastHttpStatus"    :index/last-http-status]
   ["type"              :index/format #(case % 0 "rss" 1 "atom" nil)]])

(defn- funding->model
  [{:strs [url message]}]
  (cond-> {:url url}
    message (assoc :content message)))

(defn- categories->model
  "The itunes:category entries of the index's map of `categories`, in the
  order of their ids."
  [categories]
  (vec (for [[_ text] (sort-by (comp ->number key) categories)]
         {:text text})))

(defn ^:no-doc feed->model
  "The feed object `f` of the index as a feed map. It has the keys of a
  parsed feed where the index has the same information, keys in the index
  namespace for what only the index knows, and the original as :index/raw."
  [f]
  (let [{:strs [image ownerName locked value funding categories]} f
        locked? (boolean (->boolean locked))
        block   (value->block value)]
    (cond-> (assoc (pick feed-fields f) :index/raw f)
      image               (assoc :itunes/image {:href image})
      ownerName           (assoc :itunes/owner {:name ownerName})
      (some? locked)      (assoc :podcast/locked {:content locked?})
      block               (assoc :podcast/values [block])
      (get funding "url") (assoc :podcast/funding [(funding->model funding)])
      (seq categories)    (assoc :itunes/categories
                                 (categories->model categories)))))

(def ^:no-doc episode-fields
  "The scalar fields of the index's episode object and their model keys."
  [["title"         :rss/title]
   ["link"          :rss/link]
   ["description"   :rss/description]
   ["datePublished" :rss/pub-date ->inst]

   ["duration"      :itunes/duration ->positive]
   ["explicit"      :itunes/explicit ->boolean]
   ["episode"       :itunes/episode ->positive]
   ["season"        :itunes/season ->positive]
   ["episodeType"   :itunes/episode-type]

   ["id"            :index/id]
   ["feedId"        :index/feed-id]
   ["feedUrl"       :index/feed-url]
   ["feedTitle"     :index/feed-title]
   ["feedLanguage"  :index/feed-language]
   ["feedItunesId"  :index/feed-itunes-id]
   ["feedImage"     :index/feed-image]
   ["podcastGuid"   :index/podcast-guid]
   ["dateCrawled"   :index/date-crawled ->inst]])

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
    url                         (assoc :uri url)
    protocol                    (assoc :protocol protocol)
    accountId                   (assoc :account-id accountId)
    accountUrl                  (assoc :account-url accountUrl)
    (some? (->number priority)) (assoc :priority (->number priority))))

(defn- soundbite->model
  [{:strs [startTime duration title]}]
  (cond-> {}
    (some? (->number startTime)) (assoc :start-time (->number startTime))
    (some? (->number duration))  (assoc :duration (->number duration))
    (seq title)                  (assoc :content title)))

(defn- enclosure->model
  "The enclosure of the episode object `e` of the index."
  [{:strs [enclosureUrl enclosureType enclosureLength] :as e}]
  (let [length (->number enclosureLength)]
    (cond-> {:url enclosureUrl}
      enclosureType  (assoc :type enclosureType)
      (some? length) (assoc :length length))))

(defn- transcript->model
  [{:strs [url type]}]
  (cond-> {:url url}
    type (assoc :type type)))

(defn episode->model
  "The episode object `e` of the index as an item map. It has the keys of
  a parsed item where the index has the same information, keys in the
  index namespace for the rest, and the original as :index/raw."
  [e]
  (let [{:strs [guid enclosureUrl image chaptersUrl transcriptUrl transcripts
                persons socialInteract soundbite soundbites value]} e
        transcripts (or (seq transcripts)
                        (when transcriptUrl [{"url" transcriptUrl}]))
        soundbites  (or (seq soundbites) (when soundbite [soundbite]))
        block       (value->block value)]
    (cond-> (assoc (pick episode-fields e) :index/raw e)
      guid                 (assoc :rss/guid {:content guid})
      enclosureUrl         (assoc :rss/enclosure (enclosure->model e))
      image                (assoc :itunes/image {:href image})
      chaptersUrl          (assoc :podcast/chapters {:url chaptersUrl})
      transcripts          (assoc :podcast/transcripts
                                  (mapv transcript->model transcripts))
      (seq persons)        (assoc :podcast/people (mapv person->model persons))
      (seq socialInteract) (assoc :podcast/social-interacts
                                  (mapv social->model socialInteract))
      soundbites           (assoc :podcast/soundbites
                                  (mapv soundbite->model soundbites))
      block                (assoc :podcast/values [block]))))

;; the OpenAPI description has podcastGUID, and the model and destinations
;; beside it, but the API answered with podcastGuid, and with them under
;; value, on 11 October 2026
(defn- batch-values
  "The value blocks of the answer `values` of a batch, by the id of each
  episode, its feed guid and its guid."
  [values]
  (into {}
        (for [v     values
              :when (map? v)
              :let  [feed-guid (or (get v "podcastGuid") (get v "podcastGUID"))
                     block     (if (map? (get v "value")) (get v "value") v)]]
          [[feed-guid (get v "guid")] (value->block block)])))

(defn response
  "The data in the JSON `body` of an API response:

  - :status, as a boolean
  - :description, :count and :query, as given
  - feeds as :feed or :feeds, and episodes as :episode, :items or
    :live-items, in the shape of the model
  - a value block as :value, and the blocks of a batch as :values, a map
    of the id of each episode, its feed guid and its guid, to its block
  - the whole parsed body as :raw"
  [body]
  (let [raw (json/read-str body)
        {:strs [status feed feeds episode items liveItems value]} raw]
    (cond-> {:status (= "true" (str status)) :raw raw}
      (contains? raw "description") (assoc :description (get raw "description"))
      (contains? raw "count")       (assoc :count (get raw "count"))
      (contains? raw "query")       (assoc :query (get raw "query"))
      (map? feed)                   (assoc :feed (feed->model feed))
      (sequential? feeds)           (assoc :feeds (mapv feed->model feeds))
      (map? episode)                (assoc :episode (episode->model episode))
      (sequential? items)           (assoc :items (mapv episode->model items))
      (sequential? liveItems)       (assoc :live-items
                                           (mapv episode->model liveItems))
      (map? value)                  (assoc :value (value->block value))
      (sequential? value)           (assoc :values (batch-values value)))))

(defn remote-value-requests
  "The requests to try in order for the value block that `remote-item` of
  a time split points at, with `credentials` and `opts` as request takes
  them.

  The block of the item comes first, when the remote item names one, and
  then that of the feed."
  [credentials {:keys [feed-guid item-guid] :as remote-item} opts]
  (cond-> []
    (and feed-guid item-guid)
    (conj (request credentials :value/by-episode-guid
                   {:podcastguid feed-guid :episodeguid item-guid} opts))

    feed-guid
    (conj (request credentials :value/by-podcast-guid {:guid feed-guid} opts))))

(defn try-response
  "The data in the JSON `body` of an API response, as response reads it,
  or nil when it doesn't read."
  [body]
  (try
    (response body)
    (catch #?(:clj Exception :cljs :default) _
      nil)))

(defn- value-answer
  "What the `attempt` to send the `request` for the value block of
  `remote-item` says: a map of the :value, nil when the index has no
  block, or a map of an :error."
  [remote-item request {:keys [value error] :as attempt}]
  (let [{:keys [status body]} value
        ok?    (and (not error) (<= 200 status 299))
        answer (when ok?
                 (try-response (str body)))
        block  (:value answer)
        failed #(diagnostics/failed
                 (into {:id          :value/remote-unresolved
                        :remote-item remote-item
                        :url         (:url request)}
                       %))]
    (cond
      error
      (failed {:reason (async/explain error)})

      ;; the index doesn't take or know the guid
      (#{400 404} status)
      nil

      (not ok?)
      (failed {:status status})

      (nil? answer)
      (failed {:status status :reason "The answer isn't JSON."})

      (and (:status answer) (seq (:recipients block)))
      {:value block})))

(defn remote-item-requests
  "The requests to try in order for `remote-item`, with `credentials` and
  `opts` as request takes them. The episode comes first, when it's named,
  and then the feed.

  The `remote-item` is a podroll, publisher, list or time-split entry with
  a :feed-guid and perhaps an :item-guid."
  [credentials {:keys [feed-guid item-guid] :as remote-item} opts]
  (cond-> []
    (and feed-guid item-guid)
    (conj (request credentials :episodes/by-guid
                   {:guid item-guid :podcastguid feed-guid} opts))

    feed-guid
    (conj (request credentials :podcasts/by-guid {:guid feed-guid} opts))))

(defn- item-in
  "What the `responses` to the requests for a remote item say: a map of
  the :feed and the :episode, or nil when they name neither."
  [responses]
  (let [answers (for [{:keys [body]} responses
                      :when (string? body)]
                  (try-response body))]
    (not-empty
     (into {} (for [{:keys [status feed episode]} answers
                    :when status
                    [k v] [[:feed feed] [:episode episode]]
                    :when v]
                [k v])))))

(defn call!
  "Send `call` for `credentials` with its `params`, when it takes any, and
  read the answer, in ClojureScript in a promise. The `opts` are these:

  - :base-url, as request takes it
  - :auth-date, as request takes it, the time of :now-fn by default
  - :now-fn, the clock, time/now by default
  - :send, the function that sends the request, client/send! by default

  In a browser, the call works only where the CORS policy of the API
  allows it, or through a :send that goes through your own server."
  ([credentials call]
   (call! credentials call {} {}))
  ([credentials call params]
   (call! credentials call params {}))
  ([credentials call params {:keys [send] :or {send client/send!} :as opts}]
   (async/then (send (request credentials call params (dated opts)))
               (comp response :body))))

(defn fetch-remote-value!
  "Fetch the value block that `remote-item` of a time split points at for
  `credentials`, with the `opts` below. It gives the block, nil when the
  index has none, or a map with a diagnostic under :error, in
  ClojureScript in a promise.

  The requests go one by one, for the block of the item first, until one
  answers with a block or fails. An answer of 400 or 404 says that the
  index doesn't know the guid. Nothing that the network or the server
  does throws. The `opts` are those of call!, and the :allow-pred,
  :timeout-seconds, :max-bytes and :user-agent that http/try-send! takes."
  ([credentials remote-item]
   (fetch-remote-value! credentials remote-item {}))
  ([credentials remote-item {:keys [send] :or {send client/send!} :as opts}]
   (let [requests (remote-value-requests credentials remote-item (dated opts))
         answer!  (fn [request]
                    (async/then (http/try-send! send request opts)
                                (partial value-answer remote-item request)))]
     (async/then (async/some answer! requests)
                 #(if (:error %) % (:value %))))))

(defn fetch-remote-item!
  "Fetch what the remote `item` points at for `credentials`, with the
  `opts` of fetch-remote-value!, in ClojureScript in a promise.

  It's a map of the :feed and the :episode in the shape of the model, or
  nil when the index knows neither. The requests for the episode and the
  feed go at once. When neither gets an answer, it's a map with a
  :directory/source-failed diagnostic under :error."
  ([credentials item]
   (fetch-remote-item! credentials item {}))
  ([credentials item {:keys [send] :or {send client/send!} :as opts}]
   (let [requests (remote-item-requests credentials item (dated opts))]
     (async/then (async/all #(http/try-send! send % opts) requests)
                 (fn [attempts]
                   (if (and (seq attempts) (every? :error attempts))
                     (diagnostics/failed
                      {:id     :directory/source-failed
                       :source :podcastindex
                       :reason (async/explain (:error (first attempts)))})
                     (item-in (keep :value attempts))))))))

(comment
  (request {:key "K" :secret "S"} :search/by-term {:q "podcasting 2.0" :max 5}
           {:auth-date 1700000000})
  (remote-value-requests {:key "K" :secret "S"}
                         {:feed-guid "a94f" :item-guid "x"}
                         {:auth-date 1700000000})
  #_.)
