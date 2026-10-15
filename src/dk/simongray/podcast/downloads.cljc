(ns dk.simongray.podcast.downloads
  "The episodes to download and the files to delete on a device, by the
  listener's rules.

  Downloading, deleting and choosing when are up to you. The rules are the
  kinds that podcast apps have, and a rule selects its episodes with the
  options of criteria/episodes, e.g.:

      {:download? true
       :episodes  {:where {:duration [600 nil] :not {:text #{\"rerun\"}}}
                   :limit 3}
       :played    86400}

  The rules sync in the profile, and a device can have rules of its own.
  The record of the files on a device, which the plan reads, is kept by
  start-download, finish-download, remove-file and keep-file, and
  app.library holds it and saves it."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.downloads.impl :as impl]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]
            [dk.simongray.whatwg-mime :as mime]))

(def keeps
  "What the :keep of a rule can name, whose files stay on the device:

  - :starred, an episode with a star
  - :queued, an episode in the queue
  - :playlists, an episode in a playlist
  - :in-progress, an episode that the listener started and didn't end"
  #{:starred :queued :playlists :in-progress})

(def default-rule
  "The rule of a show until the listener gives another:

  - :download?, whether the episodes of the show are downloaded
  - :episodes, the :where, :order, :limit and :scale of criteria/episodes
    that select them
  - :played, the seconds that the file of a played episode stays, where 0
    deletes it at once and nil keeps it
  - :inactive, the seconds that a file stays while its episode isn't
    played, counted from the download or the last reported position
  - :trim?, whether a file goes when :episodes no longer selects it
  - :keep, a set of the names in keeps whose files stay"
  {:download? false
   :episodes  {:order :newest}
   :played    nil
   :inactive  nil
   :trim?     false
   :keep      #{:starred :queued}})

(def default-rules
  "The rules of a listener until the listener gives others:

  - :default, the rule of a show without one, with the keys of
    default-rule
  - :shows, the rules of the shows by feed guid
  - :queue, how many episodes at the front of the queue to download, or
    true for all of them
  - :filters, the saved filters of the profile whose episodes to download
    across all shows, as a map from the name of each to the most of its
    episodes, or nil for as many as its own :limit
  - :max-episodes and :max-bytes, the most files and bytes on the device
  - :max-file-bytes, the most bytes of one file, e.g. to keep a video of
    several gigabytes from a phone
  - :make-room?, whether the files of played episodes go when a download
    needs room
  - :delete-unsubscribed?, whether the files of a show go once the
    listener unsubscribes from it, but for those that the rules keep and
    the episodes added on their own"
  {:default              {}
   :shows                {}
   :queue                nil
   :filters              {}
   :max-episodes         nil
   :max-bytes            nil
   :max-file-bytes       nil
   :make-room?           false
   :delete-unsubscribed? true})

(def bytes-per-second
  "The bytes of one second of an episode whose feed gives neither its
  length nor its bitrate, for :audio at 128 kbit/s and :video at 1 Mbit/s,
  unless the :estimate-fn of the options says otherwise."
  {:audio 16000 :video 125000})

(def retry-waits
  "How many seconds the plan waits before it lists a failed download
  again, by how many times in a row it failed, repeating the last wait:
  none after the first failure, so that a download that broke off goes on
  at the next plan, and longer after each one that follows."
  [0 900 3600 14400 86400])

(defn rules
  "The rules that `profile` holds, over default-rules: the listener's
  preference :downloads, with the setting :downloads of each show as its
  rule."
  [profile]
  (let [listener (:downloads (profile/preferences profile))
        rule-of  #(:downloads (profile/settings profile %))
        shows    (into {} (for [feed-guid (keys (profile/subscriptions profile))
                                :let  [rule (rule-of feed-guid)]
                                :when (some? rule)]
                            [feed-guid rule]))]
    (if (or (nil? listener) (map? listener))
      (assoc (merge default-rules listener) :shows shows)
      listener)))

(defn rule
  "The rule of the show `feed-guid` in `rules`: the rule of the show, over
  the :default of the rules, over default-rule."
  [rules feed-guid]
  (let [part    #(when (map? %) %)
        default (part (:default rules))
        own     (part (get (part (:shows rules)) feed-guid))
        merged  (merge default-rule default own)]
    (cond-> merged
      (map? (:episodes merged))
      (update :episodes #(merge (:episodes default-rule) %)))))

(defn- rule-problem
  "A description of what this version of podcast-clj doesn't know in
  `rule`, or nil."
  [{:keys [episodes played inactive] :as rule}]
  (let [unknown (remove #(contains? default-rule %) (keys rule))
        kept    (:keep rule)]
    (or (when (seq unknown)
          (str "the keys " (str/join ", " (map pr-str unknown))
               " are not known"))
        (when-not (or (nil? episodes) (map? episodes))
          (str "the value of :episodes is " (pr-str episodes) ", not a map"))
        (criteria/problem (or episodes {}))
        (when-not (or (nil? played) (impl/amount? played))
          (str "the value of :played is " (pr-str played)
               ", not a number of seconds"))
        (when-not (or (nil? inactive) (impl/amount? inactive))
          (str "the value of :inactive is " (pr-str inactive)
               ", not a number of seconds"))
        (when-not (or (nil? kept) (and (set? kept) (every? keeps kept)))
          (str "the value of :keep is " (pr-str kept) ", not a set of "
               (str/join ", " (sort keeps)))))))

(defn- show-problem
  "A description of what this version of podcast-clj doesn't know in
  `own`, the rule of the show `feed-guid` in `rules`, or nil."
  [rules feed-guid own]
  (if (map? own)
    (rule-problem (rule rules feed-guid))
    (str "the rule is " (pr-str own) ", not a map")))

(defn problems
  "A description of what in `rules` this version of podcast-clj doesn't
  know, by place, or nil.

  A place is a key of the rules, or the feed guid of a show. The rules can
  come from a device with a later version. For a show whose rule has a
  problem, a plan does nothing, and for a problem elsewhere, it does
  nothing at all."
  [rules]
  (if-not (map? rules)
    {:rules (str "the rules are " (pr-str rules) ", not a map")}
    (let [shows (when (map? (:shows rules)) (:shows rules))]
      (not-empty
       (into {}
             (concat
              (for [k     (keys rules)
                    :when (not (contains? default-rules k))]
                [k (str "the key " (pr-str k) " is not known")])
              (for [[k ok?] impl/checks
                    :let  [v (get rules k)]
                    :when (not (ok? v))]
                [k (str "the value of " (pr-str k) " is " (pr-str v))])
              (when-let [problem (rule-problem (rule rules nil))]
                [[:default problem]])
              (for [[feed-guid own] shows
                    :let  [problem (show-problem rules feed-guid own)]
                    :when problem]
                [feed-guid problem])))))))

(defn estimate
  "The bytes that the `enclosure` of `item` takes when its feed gives no
  length: the duration at the bitrate of the enclosure, or else at
  bytes-per-second, and 0 without a duration."
  [item {:keys [bitrate] :as enclosure}]
  (let [kind (if (resolve/video? enclosure) :video :audio)
        rate (if (and (number? bitrate) (pos? bitrate))
               (/ bitrate 8)
               (bytes-per-second kind))]
    (long (* rate (or (resolve/duration item) 0)))))

(defn- context
  "The context of a plan, from `profile`, the device's `files`, the
  instant `at` and the `opts` of the plan.

  It has what the criteria need: the profile as :profile, the instant :at
  and the :url of a feed. It also has the :files, the :rules with their
  :problems, the :held episodes, the :rule-fn that gives the rule of a
  show by its feed guid, and the :estimate-fn."
  [profile files at opts]
  (let [rules (:rules opts (rules profile))]
    (merge {:retry-waits retry-waits}
           (select-keys opts [:scale :enclosure :retry-waits])
           {:profile     profile
            :at          at
            :url         (:url opts resolve/self-url)
            :files       (or files {})
            :rules       (if (map? rules) (merge default-rules rules) rules)
            :problems    (problems rules)
            :held        (impl/held profile)
            :rule-fn     (fn [feed-guid] (rule rules feed-guid))
            :estimate-fn (:estimate-fn opts estimate)})))

(defn resume-headers
  "The headers of a request for the rest of the partial `file`, from the
  bytes it has to the end, or nil when it can't go on: when it has no
  bytes, or no strong validator that tells whether the file is still the
  same, as headers/if-range of wary-fetch chooses it, e.g.

      {\"Range\" \"bytes=1048576-\" \"If-Range\" \"\\\"5f3a\\\"\"}"
  [file]
  (impl/resume-headers file))

(defn on-device
  "The entries of the `files` of a device that are on it, by episode id:
  the finished files, the downloads that run, and the bytes of those that
  broke off."
  [files]
  (impl/on-device files))

(defn plan
  "The plan at `at` for a device with `files`: the episodes of `feeds` to
  download and the files to delete, by the rules in `profile` and `opts`.

  The `feeds` are the parsed feeds of the shows, or one feed, and `files`
  are the files on the device by episode id:

      {id {:bytes 51093220 :at #inst \"2026-09-01\"}}

  The :at of a file is when it was downloaded. No rule deletes a file with
  :keep?, and a file that's gone has :removed?. Keep that entry, since no
  episode with an entry is downloaded again, but for one that failed,
  which comes again after the :retry-waits of `opts`, retry-waits by
  default. One that broke off comes with the :received bytes and the
  :headers that ask for the rest, when they can, as resume-headers says.
  The functions start-download, finish-download, fail-download,
  remove-file and keep-file keep such a record. The result is:

      {:download [{:id id :url \"…\" :bytes 48211004 :reason :rule …}]
       :delete   [{:id id :bytes 51093220 :reason :played}]
       :waiting  [{:id id :url \"…\" :bytes 912004113 :limit :max-bytes …}]}

  The downloads come in this order, with these reasons:

  - :queue, the front of the queue
  - :filter, what the saved filters in the rules select, across all
    shows
  - :rule, what the rules of the shows select, newest first

  Played or dismissed episodes aren't downloaded, and downloads without
  room within :max-episodes and :max-bytes wait, as do files above
  :max-file-bytes. A file is deleted for one of these reasons:

  - :unsubscribed, the listener left the show, and the rules have
    :delete-unsubscribed?
  - :played, the episode was played :played seconds ago
  - :inactive, it wasn't played for :inactive seconds
  - :trimmed, the rule no longer selects it, and has :trim?
  - :room, a download needs the room, and the rules have :make-room?

  The `opts` are:

  - :rules, instead of the rules in the profile
  - :url, the URL of a feed without a podcast:guid, as a string or a
    function of the feed
  - :enclosure, the options of resolve/enclosure, which selects the file
    of an episode among those that resolve/downloadable? takes. Its
    :video? is profile/prefers-video? for the show unless they give one.
  - :estimate-fn, a function of an item and its enclosure that gives the
    bytes of a file whose feed gives no length, estimate by default
  - :scale, the scale of the criterion :rating
  - :retry-waits, the seconds before a failed download comes again, as
    retry-waits has them

  When the rules have a problem, the result describes it under
  :problems."
  ([profile feeds files at]
   (plan profile feeds files at {}))
  ([profile feeds files at opts]
   {:pre [(map? opts)]}
   (let [{:keys [rules problems] :as ctx} (context profile files at opts)
         shows   (when (map? rules) (:shows rules))
         global? (some #(not (contains? shows %)) (keys problems))]
     (cond-> (if global?
               {:download [] :delete [] :waiting []}
               (impl/planned ctx (if (map? feeds) [feeds] feeds)))
       problems (assoc :problems problems)))))

(defn start-download
  "The `files` of a device with the download of `entry`, one of the
  :download of a plan, started at `at`.

  The entry of the file keeps the :url, the :type and the :bytes that the
  plan expects, and the time as :at. A file that the listener asked to
  keep stays kept, and the bytes of a download that broke off stay when
  the plan entry goes on from them, with :received."
  [files {:keys [id received] :as entry} at]
  (update files id
          #(cond-> (-> %
                       (dissoc :removed? :removed-at :finished-at :path
                               :failed-at :error)
                       (merge (select-keys entry [:url :type :bytes]))
                       (assoc :at at))
             (not received) (dissoc :received :etag :last-modified :date))))

(defn finish-download
  "The `files` of a device with the download of the episode `id` finished
  at `at`, with the :path and the :bytes of the file in `details`. The
  path is whatever the platform reads the file by, e.g. a file path or
  the key of a cached response."
  [files id {:keys [path bytes] :as details} at]
  (update files id #(cond-> (-> %
                                (dissoc :failed-at :failures :error :received
                                        :etag :last-modified :date)
                                (assoc :finished-at at))
                      (nil? (:at %)) (assoc :at at)
                      path           (assoc :path path)
                      bytes          (assoc :bytes bytes))))

(defn fail-download
  "The `files` of a device with the download of the episode `id` failed at
  `at`, with the `details` below. The plan lists it again once the wait
  of retry-waits after the failure has passed.

  - :received, the bytes of the file that are on the device, from which
    the next download can go on, or 0 for none. Without it, the bytes of
    the last try stay.
  - :etag, :last-modified and :date, the headers of the answer that gave
    the bytes, which tell whether the file is still the same
  - :error, a diagnostic of why it failed"
  [files id {:keys [received] :as details} at]
  (let [none? (and (number? received) (zero? received))]
    (update files id
            #(cond-> (-> (dissoc % :error)
                         (merge (select-keys details [:received :etag
                                                      :last-modified :date
                                                      :error]))
                         (assoc :failed-at at)
                         (update :failures (fnil inc 0)))
               none? (dissoc :received :etag :last-modified :date)))))

(defn remove-file
  "The `files` of a device without the file of the episode `id`, deleted
  at `at`. Its entry stays with :removed?, so that no plan downloads it
  again."
  [files id at]
  (if (contains? files id)
    (update files id #(-> (dissoc % :path :finished-at :received :etag
                                  :last-modified :date :failed-at :failures
                                  :error)
                          (assoc :removed? true :removed-at at)))
    files))

(defn keep-file
  "The `files` of a device with the file of the episode `id` kept whatever
  the rules say, or no longer kept when `keep?` is false."
  [files id keep?]
  (cond
    (not (contains? files id)) files
    keep?                      (assoc-in files [id :keep?] true)
    :else                      (update files id dissoc :keep?)))

(defn local-file
  "The path of the downloaded file of the episode `id` in the `files` of a
  device, or nil until a download of it has finished."
  [files id]
  (let [{:keys [path finished-at removed?]} (get files id)]
    (when (and path finished-at (not removed?))
      path)))

(defn usage
  "How many of the `files` of a device are on it, and their bytes, by feed
  guid, e.g. {feed-guid {:episodes 3 :bytes 151000000}}. A download that
  runs counts with the bytes that the plan expects, and one that broke off
  with the bytes it received."
  [files]
  (reduce-kv (fn [acc [feed-guid] file]
               (-> acc
                   (update-in [feed-guid :episodes] (fnil inc 0))
                   (update-in [feed-guid :bytes] (fnil + 0) (impl/size file))))
             {}
             (on-device files)))

(defn- extension
  "The extension of the file of `url`, in lower case, or nil."
  [url]
  (some->> (:path (url/parts url))
           (re-find #"\.([A-Za-z0-9]{1,5})$")
           second
           str/lower-case))

;; POSIX.1-2017, section 3.282, the portable filename character set
(defn file-name
  "A name for the file of the download `entry` of a plan, made from the
  `title` of its episode, that every file system takes, e.g.
  Episode-274-Vibe-Cloning-3fa2b9c1.mp3.

  The name keeps the letters and digits of the title without their
  accents, and a hyphen for any other run of characters, up to 80 of
  them. Eight hex digits of the episode's id come next, so that two
  episodes with one title don't share a name, and then the extension of
  the media type of the entry, or else of its URL."
  [{:keys [id type url] :as entry} title]
  (let [words  (-> (impl/plain (str title))
                   (str/replace #"[^A-Za-z0-9]+" "-")
                   (str/replace #"^-+" ""))
        words  (-> (subs words 0 (min 80 (count words)))
                   (str/replace #"-+$" ""))
        digest (subs (bytes/hex (hash/digest :sha-256
                                             (bytes/encode (pr-str id))))
                     0 8)
        ending (or (:extension (get media/types
                                    (mime/essence type)))
                   (extension url))]
    (str (when (seq words) (str words "-"))
         digest
         (when ending (str "." ending)))))

;; RFC 9110, sections 14.4, 15.3.7 and 15.5.17
(defn resume-result
  "What the `response` to a request with the resume-headers of the partial
  `file` means for the bytes of the file that the device has, or nil for
  an answer that isn't a success, which leaves them as they are:

  - :append, a 206 from the end of the bytes, whose body goes after them
  - :replace, a 200 with the whole file, which changed or came whole, so
    the body replaces them
  - :complete, a 416 that gives the size of the file as that of the
    bytes, which were whole already
  - :restart, another 206 or 416, so the bytes go, and the file downloads
    again from its start

  The response is a map of :status and :headers, with the names of the
  headers in lower case, as wary-fetch gives them."
  [{:keys [received] :as file} {:keys [status headers] :as response}]
  (let [value (headers/header headers "content-range")]
    (case (long (or status 0))
      206 (if (= received (:start (headers/content-range value)))
            :append
            :restart)
      200 :replace
      416 (if (= received (headers/complete-length value))
            :complete
            :restart)
      nil)))

(comment
  (def show
    {:podcast/guid "feed-1"
     :rss/items    [{:rss/title     "Episode 3"
                     :rss/guid      {:content "ep-3"}
                     :rss/pub-date  #inst "2026-09-22"
                     :rss/enclosure {:url    "https://example.org/3.mp3"
                                     :type   "audio/mpeg"
                                     :length 48211004}}
                    {:rss/title     "Episode 2 (rerun)"
                     :rss/guid      {:content "ep-2"}
                     :rss/pub-date  #inst "2026-09-15"
                     :rss/enclosure {:url    "https://example.org/2.mp3"
                                     :type   "audio/mpeg"
                                     :length 51093220}}
                    {:rss/title     "Episode 1"
                     :rss/guid      {:content "ep-1"}
                     :rss/pub-date  #inst "2026-09-08"
                     :rss/enclosure {:url    "https://example.org/1.mp3"
                                     :type   "audio/mpeg"
                                     :length 50117331}}]})

  ;; the newest two episodes without a rerun, and a played file goes after
  ;; a day
  (def profile
    (-> (profile/make "phone")
        (profile/subscribe "feed-1"
                           {:url "https://example.org/feed.xml" :title "A show"}
                           #inst "2026-09-01")
        (profile/prefer {:downloads {:default      {:played 86400}
                                     :max-episodes 50}}
                        #inst "2026-09-01")
        (profile/configure "feed-1"
                           {:downloads
                            {:download? true
                             :episodes  {:where {:not {:text #{"rerun"}}}
                                         :limit 2}}}
                           #inst "2026-09-01")
        (profile/mark-played ["feed-1" "ep-1"] #inst "2026-09-20")))

  (rules profile)
  (rule (rules profile) "feed-1")
  (problems (rules profile))

  (plan profile [show]
        {["feed-1" "ep-1"] {:bytes 50117331 :at #inst "2026-09-09"}}
        #inst "2026-09-29")

  ;; rules of this device in the place of those of the profile
  (plan profile [show] {} #inst "2026-09-29"
        {:rules (assoc (rules profile) :queue 3 :max-bytes 60000000)})
  #_.)
