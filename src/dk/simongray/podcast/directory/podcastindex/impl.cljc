(ns ^:no-doc dk.simongray.podcast.directory.podcastindex.impl
  "The internals of directory.podcastindex: the signature of a request, and
  the mapping of the index's feed, episode and value objects onto the
  model."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]))

(defn dated
  "The `opts` with the time of their :now-fn, or else of the clock, as
  their :auth-date, unless they give one."
  [{:keys [auth-date now-fn] :or {now-fn time/now} :as opts}]
  (cond-> opts
    (not auth-date) (assoc :auth-date (time/unix-seconds (now-fn)))))

(defn auth-headers
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
                  "Authorization" (->> (str key (secret/reveal secret) seconds)
                                       bytes/encode
                                       (hash/digest :sha-1)
                                       bytes/hex
                                       secret/hide))))

(def auth-header-names
  "The names of the headers of auth-headers in lower case, which sender
  replaces."
  #{"user-agent" "x-auth-key" "x-auth-date" "authorization"})

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

(defn value->block
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

(def feed-fields
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

(defn feed->model
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

(def episode-fields
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
(defn batch-values
  "The value blocks of the answer `values` of a batch, by the id of each
  episode, its feed guid and its guid."
  [values]
  (into {}
        (for [v     values
              :when (map? v)
              :let  [feed-guid (or (get v "podcastGuid") (get v "podcastGUID"))
                     block     (if (map? (get v "value")) (get v "value") v)]]
          [[feed-guid (get v "guid")] (value->block block)])))
