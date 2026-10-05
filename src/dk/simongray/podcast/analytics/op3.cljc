(ns dk.simongray.podcast.analytics.op3
  "OP3, the Open Podcast Prefix Project: a free service that counts the
  downloads of episodes through a prefix of their URLs, and its API.

  A host puts the prefix of OP3 in front of the URL of each enclosure. OP3
  then counts each request, and redirects it to the file. The prefix and
  with-prefix functions write the prefix.

  The API gives the downloads of a show, the counts of the show and of its
  episodes, and the apps of the listeners. The request function builds a
  call of the API as a map, and response reads the answer. The call! and
  fetch-downloads! functions send the calls. Each call needs the token of
  an API key from https://op3.dev/api/keys.

  A download of OP3 has the keys of the downloads of
  dk.simongray.podcast.analytics/downloads where OP3 tells the same fact.
  They're the :time, the :url, the :listener, the agent, and the :ip,
  which OP3 keeps as a hash. OP3 counts its downloads by its own rules,
  which are close to those of the IAB."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.publish :as publish]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.xml :as xml]))

(def origin
  "The origin of OP3. The functions use it, unless their options give the
  origin of another instance of OP3 as :origin."
  "https://op3.dev")

(defn ^:no-doc instance
  "The origin of the instance of OP3 that `opts` name, or else origin,
  without a slash at its end."
  [opts]
  (str/replace (:origin opts origin) #"(^|[^/])/+$" "$1"))

(defn prefixed?
  "Whether the URL `url` goes through OP3, at the :origin of `opts`. Its
  prefix is in the URL before the query, also behind other prefixes."
  ([url]
   (prefixed? url {}))
  ([url opts]
   (let [host   (str/replace (instance opts) #"(?i)^https?://" "")
         site   (str/lower-case host)
         lower  (str/lower-case (first (str/split (str url) #"[?#]" 2)))
         starts [(str site "/e/") (str site "/e,")]
         found? #(or (str/starts-with? lower %)
                     (str/includes? lower (str "/" %)))]
     (boolean (some found? starts)))))

(defn accepts?
  "Whether OP3 takes the URL `url` behind its prefix, as its server did in
  September 2026. The prefix function asks this, unless its options give
  another predicate of a URL as :accepts?.

  OP3 answers a URL that it doesn't take with an error, so the episode
  behind the prefix doesn't play. It takes an http or https URL with a
  path and without a user name. It doesn't take a domain that's an IPv6
  address, that has no dot, or that RFC 2606 keeps for tests, such as
  example.test."
  [url]
  (let [web-url  #"(?i)https?://([a-z0-9.-]+)(?::(\d{1,5}))?/.+"
        reserved #{"test" "example" "invalid" "localhost"}]
    (boolean
     (when-let [[_ domain port] (re-matches web-url (str url))]
       (let [tld (peek (str/split (str/lower-case domain) #"\."))]
         (and (not (re-find #"^[.-]|-$|\.\." domain))
              (str/includes? domain ".")
              (not (contains? reserved tld))
              (or (nil? port) (<= 1 (parse-long port) 65535))))))))

(defn- target
  "The scheme of the http or https URL `url` and the rest of the URL, as a
  pair, e.g. https and cdn.example.com/episode.mp3. It's nil for another
  URL."
  [url]
  (when-let [[_ scheme tail] (re-matches #"(?i)(https?)://(.+)" (str url))]
    [(str/lower-case scheme) tail]))

(defn prefix
  "The URL `url` with the prefix of OP3 in front, by `opts`, so that OP3
  counts the requests for it. It's nil for a URL that OP3 doesn't take, as
  accepts? tells.

  Such a URL is https://op3.dev/e/cdn.example.com/episode.mp3. A URL that
  goes through OP3 already stays as it is. The `opts` are these:

  - :podcast-guid, the podcast:guid of the show, which helps OP3 find the
    show
  - :origin, for another instance of OP3
  - :accepts?, a predicate of a URL, when that instance takes other URLs"
  ([url]
   (prefix url {}))
  ([url {:keys [podcast-guid] :as opts}]
   (let [takes? (:accepts? opts accepts?)]
     (if (prefixed? url opts)
       url
       (when-let [[scheme tail] (when (takes? url) (target url))]
         (let [show (when (guid/guid? podcast-guid)
                      (str ",pg=" (str/lower-case podcast-guid)))]
           (str (instance opts) "/e" show
                "/" (when (= "http" scheme) "http://") tail)))))))

(defn with-prefix
  "The `feed` with the prefix of OP3 in front of the URL of each enclosure,
  and of each source of an alternate enclosure, by `opts`.

  OP3 counts the requests for both. The prefix function writes each
  prefix, where OP3 takes the URL. The :podcast-guid is that of the feed,
  unless `opts` give one."
  ([feed]
   (with-prefix feed {}))
  ([feed opts]
   (let [opts (merge {:podcast-guid (:podcast/guid feed)} opts)]
     (publish/update-media-urls feed #(or (prefix % opts) %)))))

(def calls
  "The calls of the API, by the names of OP3, with their parameters:

      :show                    {:show :episodes?}
      :downloads               {:show :start :end :episode :bots? :limit
                                :continuation-token}
      :show-download-counts    {:show}  ; one show UUID, or several
      :episode-download-counts {:show :limit}
      :top-apps-for-show       {:show}

  The :show parameter names a show by its show UUID. For :show and
  :top-apps-for-show, it can also be its podcast guid or the URL of its
  feed. The :start and :end are instants, or text that OP3 reads: a date
  such as 2026-09 or 2026-09-26, or a time before now, such as -24h."
  #{:show :downloads :show-download-counts :episode-download-counts
    :top-apps-for-show})

(defn- show-param
  "The query parameter of OP3 that names the show `id`: its show UUID, its
  podcast guid, or the URL of its feed."
  [id]
  (cond
    (guid/guid? id)    {"podcastGuid" (str/lower-case id)}
    (http/web-url? id) {"feedUrlBase64" (http/base64url id)}
    :else              {"showUuid" id}))

(defn- query
  "The query string of `params`, a map of OP3's names, whose values are
  text, numbers, instants or collections of them, with nil left out."
  [params]
  (let [text #(if (inst? %) (time/format-iso8601 %) %)]
    (str/join "&" (for [[k v] params
                        v     (if (coll? v) v [v])
                        :when (some? v)]
                    (str (http/percent-encode k) "="
                         (http/percent-encode (text v)))))))

(defn- endpoint
  "The path of the call `call` below the API of OP3, and its query
  parameters by the names of OP3, as a pair, for `params` as calls lists
  them."
  [call {:keys [show episodes? start end episode bots? limit
                continuation-token]}]
  (case call
    :show
    (let [id (if (http/web-url? show) (http/base64url show) show)]
      [(str "shows/" (http/percent-encode id))
       {"episodes" (when episodes? "include")}])

    :downloads
    [(str "downloads/show/" (http/percent-encode show))
     {"format"            "json"
      "start"             start
      "end"               end
      "episodeId"         episode
      "bots"              (when bots? "include")
      "limit"             limit
      "continuationToken" continuation-token}]

    :show-download-counts
    ["queries/show-download-counts" {"showUuid" show}]

    :episode-download-counts
    ["queries/episode-download-counts" {"showUuid" show "limit" limit}]

    :top-apps-for-show
    ["queries/top-apps-for-show" (show-param show)]

    (throw (ex-info (str "A call that the API of OP3 does not have: " call)
                    {:call call}))))

(defn request
  "The request map of the call `call` with `params`, as calls lists
  them, and the bearer `token` of an API key. The :origin of `opts` names
  another instance of OP3."
  ([token call params]
   (request token call params {}))
  ([token call params opts]
   (let [[path query-params] (endpoint call params)
         q                   (query query-params)]
     {:method  :get
      :url     (str (instance opts) "/api/1/" path (when (seq q) (str "?" q)))
      :headers {"Authorization" (http/authorization {:token token})
                "User-Agent"    http/user-agent}})))

(defn ^:no-doc record
  "The JSON object `m` with its fields as keywords in kebab-case, and its
  times as instants."
  [m]
  (let [timed (fn [k v]
                (if (and (#{:time :pubdate} k) (string? v))
                  (or (time/parse-iso8601 v) v)
                  v))]
    (into {} (map (fn [[k v]]
                    (let [k (keyword (xml/kebab k))]
                      [k (timed k v)])))
          m)))

(defn- download
  "The row `row` of the downloads of OP3 as a download, with the keys of
  dk.simongray.podcast.analytics/downloads where it tells the same fact."
  [row]
  (let [renames {:hashed-ip-address :ip :audience-id :listener}]
    (set/rename-keys (record row) renames)))

(defn response
  "The data in the JSON `body` of the answer to the call `call`. The
  fields of OP3 become keywords in kebab-case, and its times instants:

      :show                    {:show-uuid :title :podcast-guid :stats-page-url
                                :episodes [{:id :title :pubdate :item-guid}]}
      :downloads               {:downloads [download] :continuation-token}
      :show-download-counts    {:asof :show-download-counts {show-uuid counts}}
      :episode-download-counts {:show-uuid :show-title :episodes [counts] …}
      :top-apps-for-show       {:show-uuid :app-downloads {app downloads}}

  A download has the keys of dk.simongray.podcast.analytics/downloads
  where OP3 tells the same fact. So hashedIpAddress is :ip, and audienceId
  is the :listener. The keys of a map by show or by app stay text.

  It throws ::failed for a body that holds no JSON object."
  [call body]
  (let [raw    (json/object body)
        fields #(record (dissoc raw "queryTime"))
        {:strs [episodes rows continuationToken showDownloadCounts]} raw]
    (when-not raw
      (throw (ex-info "OP3 answered with no JSON object"
                      {:type ::failed :call call})))
    (case call
      :show
      (cond-> (record raw)
        episodes (assoc :episodes (mapv record episodes)))

      :downloads
      (cond-> {:downloads (mapv download rows)}
        continuationToken (assoc :continuation-token continuationToken))

      :show-download-counts
      (assoc (fields)
             :show-download-counts (update-vals showDownloadCounts record))

      :episode-download-counts
      (assoc (fields) :episodes (mapv record episodes))

      :top-apps-for-show
      (fields))))

(defn- message
  "What the answer `body` of OP3 says went wrong: the error or the message
  of its JSON, or the text."
  [body]
  (let [raw (json/object body)]
    (or (get raw "error")
        (get raw "message")
        (some-> body str str/trim not-empty))))

(defn call!
  "Send the call `call` with `params` and `opts` to OP3 with the bearer
  `token`, and read the answer with response. On the JVM it gives the
  data, and in ClojureScript a promise of it.

  It gives nil when OP3 has no such show or no data of it. Any other
  answer that isn't a success throws ::failed with its :status and
  :message, such as 401 for an unknown token or 429 when a caller asks too
  often. A success that holds no JSON object is ::failed too. The `opts`
  are these:

  - :origin, as request takes it
  - :timeout-seconds, for each request
  - :send, a function that sends a request map in place of http/send!"
  ([token call params]
   (call! token call params {}))
  ([token call params opts]
   (let [defaults {:send http/send! :timeout-seconds 30}
         {:keys [send timeout-seconds]} (merge defaults opts)
         req      (assoc (request token call params opts)
                         :timeout-seconds timeout-seconds)
         failed   (fn [status said]
                    (ex-info (str "OP3 answered " status ": " said)
                             {:type ::failed :status status :message said}))]
     (async/then
      (send req)
      (fn [{:keys [status body]}]
        (cond
          (= 404 status)
          nil

          (not (and status (<= 200 status 299)))
          (throw (failed status (message body)))

          (json/object body)
          (response call body)

          :else
          (throw (failed status "no JSON object"))))))))

(defn- unreachable
  "The page of downloads that the call for the page `from` gives when it
  fails with `error`: no downloads, and the :error as a diagnostic."
  [from error]
  {::from from
   :error (diagnostics/describe {:id     :analytics/unreachable
                                 :status (:status (ex-data error))
                                 :reason (async/explain error)})})

(defn- gathered
  "The result of fetch-downloads! for the `pages` that it fetched: the
  downloads of the pages before the one that failed, the continuation
  token of the page after them, and the :error of the page that failed."
  [pages]
  (let [{:keys [error] ::keys [from]} (first (filter :error pages))
        token     (if error from (:continuation-token (peek pages)))
        downloads (into [] (comp (remove :error) (mapcat :downloads)) pages)]
    (cond-> {:downloads downloads}
      token (assoc :continuation-token token)
      error (assoc :error error))))

(defn fetch-downloads!
  "Fetch the downloads of the show that `params` name, page after page,
  with the bearer `token` and `opts`. On the JVM it gives the result, and
  in ClojureScript a promise of it.

  The result is a map of the :downloads and the :continuation-token. The
  call :downloads gives the pages. A page holds at most :limit downloads,
  20,000 unless `params` say. The fetch stops after :max-pages pages, five
  unless `opts` say. When it stops before the end, the continuation token
  names the next page, and `params` with it read on from there. The other
  `opts` are as call! takes them.

  Nothing that OP3 or the network does throws. When the call for a page
  fails, the result has its :error, a diagnostic, with the downloads of
  the pages before that page. The continuation token then names the page
  that failed."
  ([token params]
   (fetch-downloads! token params {}))
  ([token params opts]
   (let [params     (merge {:limit 20000} params)
         page!      (fn [from]
                      (let [paged (cond-> params
                                    from (assoc :continuation-token from))]
                        (async/then
                         (async/attempt #(call! token :downloads paged opts))
                         (fn [{:keys [value error]}]
                           (if error (unreachable from error) value)))))
         next-token #(when-not (:error %) (:continuation-token %))
         pages      (async/iteration page!
                                     :kf    next-token
                                     :initk (:continuation-token params)
                                     :limit (:max-pages opts 5))]
     (async/then pages gathered))))

(comment
  (prefix "https://cdn.example.com/episode-54.mp3"
          {:podcast-guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"})
  (prefixed? "https://op3.dev/e/cdn.example.com/episode-54.mp3")
  (accepts? "https://localhost/episode-54.mp3")
  (with-prefix
   {:rss/items
    [{:rss/enclosure {:url "https://cdn.example.com/episode-54.mp3"}}]})

  (request "a-token" :downloads
           {:show "fb3d03641442410e881acb0562ef27c6" :start "-24h" :limit 10})
  (response :downloads
            (str "{\"rows\": [{\"time\": \"2026-09-26T08:15:02Z\", "
                 "\"hashedIpAddress\": \"abc\"}]}"))
  #_.)

#?(:clj
   (comment
     ;; the sample token of the API docs of OP3, for a try
     (def pc20 "917393e3-1b1e-5cef-ace4-edaa54e1f810")
     (def show-uuid "fb3d03641442410e881acb0562ef27c6")
     (call! "preview07ce" :show {:show pc20 :episodes? true})
     (call! "preview07ce" :top-apps-for-show {:show pc20})
     (fetch-downloads! "preview07ce" {:show show-uuid :start "-24h" :limit 10}
                       {:max-pages 2})
     #_.))
