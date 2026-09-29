(ns dk.simongray.podcast.analytics.op3
  "OP3, the Open Podcast Prefix Project: a free service that counts the
  downloads of episodes through a prefix of their URLs, and its API.

  A host puts the prefix of OP3 in front of the URL of each enclosure. OP3
  then counts each request, and redirects it to the file. `prefix` and
  `with-prefix` write the prefix. The API gives the downloads of a show,
  the counts of the show and of its episodes, and the apps of the
  listeners. `request` builds a call of the API as a map, and `response`
  reads the answer. `call!` and `downloads!` send the calls. Each call
  needs the token of an API key from https://op3.dev/api/keys.

  A download of OP3 has the keys of the downloads of
  `dk.simongray.podcast.analytics/downloads` where OP3 tells the same fact.
  These are the `:time`, the `:url`, the `:ip`, which OP3 keeps as a hash,
  the `:listener` and the agent. OP3 counts its downloads by its own rules,
  which are close to those of the IAB."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.xml :as xml]))

(def origin
  "The origin of OP3. The functions use it, unless their options give the
  origin of another instance of OP3 as `:origin`."
  "https://op3.dev")

(defn ^:no-doc instance
  "The origin of the instance of OP3 that `opts` name, else `origin`,
  without a slash at its end."
  [opts]
  (str/replace (:origin opts origin) #"(^|[^/])/+$" "$1"))

(defn prefixed?
  "Whether the URL `url` goes through OP3, at the `:origin` of `opts`: its
  prefix is in the URL before the query, also behind other prefixes."
  ([url]
   (prefixed? url {}))
  ([url opts]
   (let [site  (str/lower-case (str/replace (instance opts) #"(?i)^https?://" ""))
         lower (str/lower-case (first (str/split (str url) #"[?#]" 2)))]
     (boolean (some #(or (str/starts-with? lower %) (str/includes? lower (str "/" %)))
                    [(str site "/e/") (str site "/e,")])))))

(defn accepts?
  "Whether OP3 takes the URL `url` behind its prefix, as its server did in
  September 2026. `prefix` asks this, unless its options give another
  predicate of a URL as `:accepts?`.

  OP3 answers a URL that it does not take with an error, so the episode
  behind the prefix does not play. It takes an http or https URL with a
  path and without a user name. It does not take a domain that is an IPv6
  address, that has no dot, or that RFC 2606 keeps for tests, such as
  example.test."
  [url]
  (boolean
   (when-let [[_ domain port] (re-matches #"(?i)https?://([a-z0-9.-]+)(?::(\d{1,5}))?/.+"
                                          (str url))]
     (and (not (re-find #"^[.-]|-$|\.\." domain))
          (str/includes? domain ".")
          (not (contains? #{"test" "example" "invalid" "localhost"}
                          (peek (str/split (str/lower-case domain) #"\."))))
          (or (nil? port) (<= 1 (parse-long port) 65535))))))

(defn- target
  "The scheme of the http or https URL `url`, and the rest of the URL:
  `[\"https\" \"cdn.example.com/episode.mp3\"]`. Nil for another URL."
  [url]
  (when-let [[_ scheme tail] (re-matches #"(?i)(https?)://(.+)" (str url))]
    [(str/lower-case scheme) tail]))

(defn prefix
  "The URL `url` with the prefix of OP3 in front, so that OP3 counts the
  requests for it. Nil for a URL that OP3 does not take, as `accepts?`
  tells.

  Such a URL is https://op3.dev/e/cdn.example.com/episode.mp3. A URL that
  goes through OP3 already stays as it is.

  `opts` give the `:podcast-guid` of the show, which helps OP3 find the
  show. For another instance of OP3, they give its `:origin`, and the
  predicate `:accepts?` when that instance takes other URLs."
  ([url]
   (prefix url {}))
  ([url {:keys [podcast-guid] :as opts}]
   (if (prefixed? url opts)
     url
     (when-let [[scheme tail] (when ((:accepts? opts accepts?) url) (target url))]
       (str (instance opts) "/e"
            (when (guid/guid? podcast-guid) (str ",pg=" (str/lower-case podcast-guid)))
            "/" (when (= "http" scheme) "http://") tail)))))

(defn with-prefix
  "`feed` with the prefix of OP3 in front of the URL of each enclosure, and
  of each source of an alternate enclosure.

  OP3 counts the requests for both. `prefix` writes each prefix, where
  OP3 takes the URL. The `:podcast-guid` is that of the feed, unless
  `opts` give one."
  ([feed]
   (with-prefix feed {}))
  ([feed opts]
   (let [opts     (merge {:podcast-guid (:podcast/guid feed)} opts)
         prefixed (fn [url] (or (when (string? url) (prefix url opts)) url))
         sources  (fn [alternate] (cond-> alternate
                                    (:sources alternate) (update :sources (partial mapv #(cond-> % (:uri %) (update :uri prefixed))))))
         item     (fn [item] (cond-> item
                               (get-in item [:rss/enclosure :url]) (update-in [:rss/enclosure :url] prefixed)
                               (:podcast/alternate-enclosures item) (update :podcast/alternate-enclosures (partial mapv sources))))]
     (cond-> feed
       (:rss/items feed) (update :rss/items (partial mapv item))))))

(def endpoints
  "The calls of the API, by the names of OP3, with their parameters.
  `:show` names a show: by its show UUID, and for :show and
  :top-apps-for-show also by its podcast guid or the URL of its feed.

      :show                    {:show :episodes?}
      :downloads               {:show :start :end :episode :bots? :limit :continuation-token}
      :show-download-counts    {:show}  ; one show UUID, or several
      :episode-download-counts {:show :limit}
      :top-apps-for-show       {:show}

  `:start` and `:end` are instants, or text that OP3 reads: a date such as
  2026-09 or 2026-09-26, or a time before now, such as -24h."
  #{:show :downloads :show-download-counts :episode-download-counts :top-apps-for-show})

(defn ^:no-doc base64url
  "The URL `url` in the base64 of URLs, as OP3 takes the URL of a feed."
  [url]
  (-> (http/base64 url) (str/replace "+" "-") (str/replace "/" "_") (str/replace #"=+$" "")))

(defn- show-param
  "The query parameter of OP3 that names the show `id`: its show UUID, its
  podcast guid, or the URL of its feed."
  [id]
  (cond
    (guid/guid? id)    {"podcastGuid" (str/lower-case id)}
    (http/web-url? id) {"feedUrlBase64" (base64url id)}
    :else              {"showUuid" id}))

(defn- query
  "The query string of `params`, a map of OP3's names, whose values are
  text, numbers, instants or collections of them, with nil left out."
  [params]
  (str/join "&" (for [[k v] params
                      v (if (coll? v) v [v])
                      :when (some? v)]
                  (str (http/encode k) "=" (http/encode (if (inst? v) (time/format-iso8601 v) v))))))

(defn request
  "The request map of the call `endpoint` with `params`, see `endpoints`,
  with the bearer `token` of an API key. `opts` can give the `:origin` of
  another instance of OP3."
  ([token endpoint params]
   (request token endpoint params {}))
  ([token endpoint {:keys [show episodes? start end episode bots? limit continuation-token]} opts]
   (let [[path params] (case endpoint
                         :show                    [(str "shows/" (http/encode (if (http/web-url? show) (base64url show) show)))
                                                   {"episodes" (when episodes? "include")}]
                         :downloads               [(str "downloads/show/" (http/encode show))
                                                   {"format" "json" "start" start "end" end "episodeId" episode
                                                    "bots" (when bots? "include") "limit" limit
                                                    "continuationToken" continuation-token}]
                         :show-download-counts    ["queries/show-download-counts" {"showUuid" show}]
                         :episode-download-counts ["queries/episode-download-counts" {"showUuid" show "limit" limit}]
                         :top-apps-for-show       ["queries/top-apps-for-show" (show-param show)]
                         (throw (ex-info (str "A call that the API of OP3 does not have: " endpoint) {:endpoint endpoint})))
         q             (query params)]
     {:method  :get
      :url     (str (instance opts) "/api/1/" path (when (seq q) (str "?" q)))
      :headers {"Authorization" (http/authorization {:token token}) "User-Agent" http/user-agent}})))

(defn ^:no-doc record
  "The JSON object `m` with its fields as keywords in kebab-case, and its
  times as instants."
  [m]
  (into {} (map (fn [[k v]]
                  (let [k (keyword (xml/kebab k))]
                    [k (if (and (#{:time :pubdate} k) (string? v)) (or (time/parse-iso8601 v) v) v)])))
        m))

(defn- download
  "The row `row` of the downloads of OP3 as a download, with the keys of
  `dk.simongray.podcast.analytics/downloads` where it tells the same fact."
  [row]
  (set/rename-keys (record row) {:hashed-ip-address :ip :audience-id :listener}))

(defn response
  "The data in the JSON `body` of the answer to the call `endpoint`. The
  fields of OP3 become keywords in kebab-case, and its times instants:

      :show                    {:show-uuid :title :podcast-guid :stats-page-url
                                :episodes [{:id :title :pubdate :item-guid}]}
      :downloads               {:downloads [download] :continuation-token}
      :show-download-counts    {:asof :show-download-counts {show-uuid counts}}
      :episode-download-counts {:show-uuid :show-title :episodes [counts] …}
      :top-apps-for-show       {:show-uuid :app-downloads {app downloads}}

  A download has the keys of `dk.simongray.podcast.analytics/downloads`
  where OP3 tells the same fact. So hashedIpAddress is `:ip`, and
  audienceId is the `:listener`. The keys of a map by show or by app stay
  text.

  Throws `::failed` for a body that holds no JSON object."
  [endpoint body]
  (let [raw (json/object body)]
    (when-not raw
      (throw (ex-info "OP3 answered with no JSON object" {:type ::failed :endpoint endpoint})))
    (case endpoint
      :show                    (cond-> (record raw)
                                 (get raw "episodes") (assoc :episodes (mapv record (get raw "episodes"))))
      :downloads               (cond-> {:downloads (mapv download (get raw "rows"))}
                                 (get raw "continuationToken") (assoc :continuation-token (get raw "continuationToken")))
      :show-download-counts    (-> (record (dissoc raw "queryTime"))
                                   (assoc :show-download-counts (update-vals (get raw "showDownloadCounts") record)))
      :episode-download-counts (-> (record (dissoc raw "queryTime"))
                                   (assoc :episodes (mapv record (get raw "episodes"))))
      :top-apps-for-show       (record (dissoc raw "queryTime")))))

(defn- message
  "What the answer `body` of OP3 says went wrong: the error or the message
  of its JSON, or the text."
  [body]
  (let [raw (json/object body)]
    (or (get raw "error")
        (get raw "message")
        (some-> body str str/trim not-empty))))

(defn call!
  "Send the call `endpoint` with `params` to OP3 with the bearer `token`,
  and read the answer with `response`. On the JVM the data, in
  ClojureScript a promise of it.

  Nil when OP3 has no such show or no data of it. Throws `::failed` with
  the `:status` and the `:message` of any other answer that is not a
  success. Examples are 401 for a token that OP3 does not know, and 429
  when a caller asks too often. A success that holds no JSON object is
  `::failed` too. `opts` are the `:origin` of `request`, the
  `:timeout-seconds` of each request, and `:send`, a function that sends a
  request map in place of `http/call!`."
  ([token endpoint params]
   (call! token endpoint params {}))
  ([token endpoint params opts]
   (let [{:keys [send timeout-seconds]} (merge {:send http/call! :timeout-seconds 30} opts)
         failed (fn [status said]
                  (ex-info (str "OP3 answered " status ": " said) {:type ::failed :status status :message said}))]
     (async/then (send (assoc (request token endpoint params opts) :timeout-seconds timeout-seconds))
                 (fn [{:keys [status body]}]
                   (cond
                     (= 404 status)                         nil
                     (not (and status (<= 200 status 299))) (throw (failed status (message body)))
                     (json/object body)                     (response endpoint body)
                     :else                                  (throw (failed status "no JSON object"))))))))

(defn downloads!
  "The downloads of the show that `params` name, read page after page with
  the bearer `token`, as `{:downloads [...] :continuation-token}`.

  The call :downloads gives the pages. On the JVM it gives the downloads,
  and in ClojureScript a promise of them.

  A page holds at most `:limit` downloads, 20,000 unless `params` say. The
  fetch stops after `:max-pages` pages, five unless `opts` say. When it
  stops before the end, the continuation token names the next page, and
  `params` with it read on from there. `opts` as for `call!`.

  This throws nothing for what OP3 or the network does. When the call for
  a page fails, the result has the `:error` of it, a diagnostic, with the
  downloads of the pages before that page. The continuation token then
  names the page that failed."
  ([token params]
   (downloads! token params {}))
  ([token params opts]
   (let [params (merge {:limit 20000} params)
         page!  (fn [from]
                  (async/then (async/attempt #(call! token :downloads (cond-> params from (assoc :continuation-token from)) opts))
                              (fn [{:keys [value error]}]
                                (if error
                                  {::from from
                                   :error (diagnostics/describe {:id     :analytics/unreachable
                                                                 :status (:status (ex-data error))
                                                                 :reason (async/explain error)})}
                                  value))))
         pages  (async/iteration page!
                                 :kf    #(when-not (:error %) (:continuation-token %))
                                 :initk (:continuation-token params)
                                 :limit (:max-pages opts 5))]
     (async/then pages
                 (fn [pages]
                   (let [{:keys [error] ::keys [from]} (first (filter :error pages))
                         token (if error from (:continuation-token (peek pages)))]
                     (cond-> {:downloads (into [] (comp (remove :error) (mapcat :downloads)) pages)}
                       token (assoc :continuation-token token)
                       error (assoc :error error))))))))

(comment
  (prefix "https://cdn.example.com/episode-54.mp3" {:podcast-guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"})
  (prefixed? "https://op3.dev/e/cdn.example.com/episode-54.mp3")
  (accepts? "https://localhost/episode-54.mp3")
  (with-prefix {:rss/items [{:rss/enclosure {:url "https://cdn.example.com/episode-54.mp3"}}]})

  (request "a-token" :downloads {:show "fb3d03641442410e881acb0562ef27c6" :start "-24h" :limit 10})
  (response :downloads "{\"rows\": [{\"time\": \"2026-09-26T08:15:02Z\", \"hashedIpAddress\": \"abc\"}]}")
  #_.)

#?(:clj
   (comment
     ;; the sample token of the API docs of OP3, for a try
     (call! "preview07ce" :show {:show "917393e3-1b1e-5cef-ace4-edaa54e1f810" :episodes? true})
     (call! "preview07ce" :top-apps-for-show {:show "917393e3-1b1e-5cef-ace4-edaa54e1f810"})
     (downloads! "preview07ce" {:show "fb3d03641442410e881acb0562ef27c6" :start "-24h" :limit 10} {:max-pages 2})
     #_.))
