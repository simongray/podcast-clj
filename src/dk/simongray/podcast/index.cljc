(ns dk.simongray.podcast.index
  "The Podcast Index API (https://podcastindex-org.github.io/docs-api/) as
  request maps. `request` builds `{:method :url :headers}` with the
  authentication computed; any HTTP client can send it, and the key and
  secret never leave the caller's process. Responses are JSON; `response`
  reads a body into data."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.json :as json]))

(def base-url
  "https://api.podcastindex.org/api/1.0/")

(def user-agent
  "The default User-Agent; the API requires a descriptive one."
  "dk.simongray/podcast")

(def endpoints
  "API paths by endpoint keyword. Each takes its parameters as a query."
  {:search/by-term         "search/byterm"
   :search/by-title        "search/bytitle"
   :search/by-person       "search/byperson"
   :search/music-by-term   "search/music/byterm"
   :podcasts/by-feed-id    "podcasts/byfeedid"
   :podcasts/by-feed-url   "podcasts/byfeedurl"
   :podcasts/by-guid       "podcasts/byguid"
   :podcasts/by-itunes-id  "podcasts/byitunesid"
   :podcasts/by-tag        "podcasts/bytag"
   :podcasts/by-medium     "podcasts/bymedium"
   :podcasts/trending      "podcasts/trending"
   :podcasts/dead          "podcasts/dead"
   :episodes/by-feed-id    "episodes/byfeedid"
   :episodes/by-feed-url   "episodes/byfeedurl"
   :episodes/by-guid       "episodes/byguid"
   :episodes/by-id         "episodes/byid"
   :episodes/by-itunes-id  "episodes/byitunesid"
   :episodes/live          "episodes/live"
   :episodes/random        "episodes/random"
   :recent/episodes        "recent/episodes"
   :recent/feeds           "recent/feeds"
   :recent/new-feeds       "recent/newfeeds"
   :recent/data            "recent/data"
   :recent/soundbites      "recent/soundbites"
   :value/by-feed-id       "value/byfeedid"
   :value/by-feed-url      "value/byfeedurl"
   :value/by-guid          "value/byguid"
   :categories/list        "categories/list"
   :stats/current          "stats/current"
   :hub/pub-notify         "hub/pubnotify"
   :add/by-feed-url        "add/byfeedurl"
   :add/by-itunes-id       "add/byitunesid"
   :apple/lookup           "apple/lookup"})

(defn now-seconds
  "The current Unix time in seconds, as the auth date."
  []
  #?(:clj  (quot (System/currentTimeMillis) 1000)
     :cljs (quot (.now js/Date) 1000)))

(defn auth-headers
  "The headers that authenticate a request with `key` and `secret` at Unix
  time `seconds`: the date, the key and the SHA-1 of key, secret and date."
  [{:keys [key secret user-agent]} seconds]
  {"User-Agent"    (or user-agent dk.simongray.podcast.index/user-agent)
   "X-Auth-Key"    key
   "X-Auth-Date"   (str seconds)
   "Authorization" (guid/sha1-hex (str key secret seconds))})

(defn- encode
  "Percent-encode `s` for a query string, the same way on both platforms:
  spaces become %20, not +."
  [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
               (str/replace "+" "%20")
               (str/replace "*" "%2A")
               (str/replace "%7E" "~"))
     :cljs (js/encodeURIComponent (str s))))

(defn query-string
  "The query string for `params`, keys and values URL-encoded; booleans
  become the bare flags the API expects."
  [params]
  (str/join "&" (for [[k v] params
                      :when (some? v)]
                  (if (true? v)
                    (encode (name k))
                    (str (encode (name k)) "=" (encode v))))))

(defn request
  "The request map for `endpoint` (a key of `endpoints`) with query
  `params`, authenticated with `credentials` (`:key`, `:secret`, optional
  `:user-agent`) at `seconds` (default: now)."
  ([credentials endpoint params]
   (request credentials endpoint params (now-seconds)))
  ([credentials endpoint params seconds]
   (let [path (or (endpoints endpoint)
                  (throw (ex-info "Unknown Podcast Index endpoint" {:endpoint endpoint})))
         qs   (query-string params)]
     {:method  :get
      :url     (str base-url path (when (seq qs) (str "?" qs)))
      :headers (auth-headers credentials seconds)})))

(defn response
  "The data in the JSON `body` of an API response."
  [body]
  (json/read-str body))

(comment
  (request {:key "K" :secret "S"} :search/by-term {:q "podcasting 2.0" :max 5} 1700000000)
  #_.)
