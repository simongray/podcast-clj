(ns ^:no-doc dk.simongray.podcast.app.episode.impl
  "The internals of app.episode: the parts of a view, the reading of an
  episode's chapters file, transcript and media file through a validating
  cache, and the search for the value blocks of remote items."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.hls :as hls]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]))

(defn chosen
  "The :enclosure and :transcript of `item` in `feed`, as `opts` select
  them with the options of resolve/enclosure and resolve/transcript."
  [feed item {:keys [languages timed? transcript-types] :as opts}]
  (let [media-opts      (select-keys opts [:languages :video? :types
                                           :max-bitrate :max-height
                                           :playable-pred])
        transcript-opts (cond-> {:languages languages :timed? timed?}
                          transcript-types (assoc :types transcript-types))]
    (into {} (remove (comp nil? val))
          {:enclosure  (resolve/enclosure item media-opts)
           :transcript (resolve/transcript feed item transcript-opts)})))

(defn- with-report
  "The `part` of a view with its `document`, and the diagnostics that
  `report-fn` gives for it as data, since metadata doesn't survive printing."
  [part document report-fn]
  (let [diagnostics (report-fn document)]
    (cond-> (assoc part :document document)
      (seq diagnostics) (assoc :report (vec diagnostics)))))

(defn own-chapters
  "The chapters in the feed itself for `item`: the embedded ones, or else
  those from the timestamps of `text`, its show notes as plain text, or
  nil."
  [item text]
  (if-let [embedded (resolve/embedded-chapters item)]
    (with-report {:source :embedded} embedded chapters/report)
    (when-let [inferred (some-> text str/split-lines chapters/from-lines)]
      (with-report {:source :inferred} inferred chapters/report))))

(defn- timed
  "The text `s` of show notes as Hiccup, with each time in it, e.g. 12:34,
  given to `f` with its seconds and its text, and replaced by what `f`
  gives, unless that's nil or the time is past the `duration` of the
  episode, when it's known."
  [s f duration]
  (if-not (str/includes? s ":")
    [s]
    (let [time? #(re-matches #"\d{1,2}:[0-5]\d:[0-5]\d|\d{1,3}:[0-5]\d" %)
          piece (fn [token]
                  (or (when (time? token)
                        (let [seconds (time/parse-clock token)]
                          (when (or (not (number? duration))
                                    (<= seconds duration))
                            (f seconds token))))
                      token))
          join  (fn [acc x]
                  (if (and (string? x) (string? (peek acc)))
                    (conj (pop acc) (str (peek acc) x))
                    (conj acc x)))]
      ;; runs of digits and colons, and the runs between them
      (reduce join [] (map piece (re-seq #"[\d:]+|[^\d:]+" s))))))

(defn with-times
  "The Hiccup `nodes` of show notes with the times in their text as `f`
  gives them, as timed does, but for the text of a link, for an episode of
  `duration`."
  [nodes f duration]
  (letfn [(node [x]
            (cond
              (string? x)
              (timed x f duration)

              (and (vector? x) (keyword? (first x)))
              (if (= "a" (name (first x)))
                [x]
                (let [n (if (map? (second x)) 2 1)]
                  [(into (subvec x 0 n) (mapcat node) (subvec x n))]))

              :else
              [x]))]
    (apply list (mapcat node nodes))))

(defn- unreachable
  "The diagnostic for the file at `url` that couldn't be fetched, because
  the request failed with `e` or the answer had the `status` of a failure."
  [url e status]
  (diagnostics/file-failure (assoc (if e (http/failure e) {:status status})
                                   :url url)))

(defn cache-name
  "The name of the document of a store that keeps the file at `url`, a
  hash of the URL."
  [url]
  (str "file-" (bytes/hex (hash/digest :sha-256 (bytes/encode url)))))

(defn- copy-of
  "The copy of the file at `url` in the `text` of a document of a cache, as
  a map of its :body, :etag and :last-modified, or nil when the text holds
  none."
  [url text]
  (let [copy (try
               (edn/read-string (str text))
               (catch #?(:clj Exception :cljs :default) _
                 nil))]
    (when (and (map? copy) (= url (:url copy)) (string? (:body copy)))
      copy)))

(defn- kept!
  "The copy of the file at `url` that the store `cache` keeps, as copy-of
  gives it, in a future or a promise. A store that fails keeps none."
  [cache url]
  (async/then (async/attempt #((:fetch cache) (cache-name url) {}))
              (fn [{:keys [value]}]
                (some->> (:body value) (copy-of url)))))

;; RFC 9111, section 5.2.2.5: no-store
(defn- keep!
  "Keep the `response` of a request for the file at `url` in the store
  `cache`, with its validators, unless it says no-store, in a future or a
  promise. A store that fails or can't be written keeps nothing."
  [cache url {:keys [headers body] :as response}]
  (let [no-store? (re-find #"(?i)(?:^|,)\s*no-store\s*(?:,|$)"
                           (str/join "," (get headers "cache-control")))
        etag      (headers/header headers "etag")
        modified  (headers/header headers "last-modified")
        copy      (cond-> {:url url :body (str body)}
                    etag     (assoc :etag etag)
                    modified (assoc :last-modified modified))]
    (when (and (:put cache) (not no-store?))
      (async/attempt #((:put cache) (cache-name url) (pr-str copy) {})))))

;; RFC 9110, sections 13.1.2 and 13.1.3: a copy is asked for again with
;; its validators, and a 304 says it's still the file
(defn- fetch-text!
  "Fetch the text of the file at `url` with `opts`, asking for the `accept`
  types, in a future or a promise. The text is the :value, or else a
  diagnostic is the :error.

  With a store as the :cache of `opts`, the copy that it keeps is asked
  for again with its validators, and given when the server says it's
  unchanged or the request fails, e.g. offline."
  [url accept {:keys [send cache] :as opts}]
  (async/then
   (if cache (kept! cache url) nil)
   (fn [copy]
     (let [asked (cond-> {"Accept" accept}
                   (:etag copy)          (assoc "If-None-Match" (:etag copy))
                   (:last-modified copy) (assoc "If-Modified-Since"
                                                (:last-modified copy)))]
       (async/then
        (http/try-send! send {:url url :headers asked} opts)
        (fn [{:keys [value error]}]
          (let [status (some-> (:status value) long)]
            (cond
              (and copy (or error (= 304 status)))
              {:value (:body copy)}

              error
              {:error (unreachable url error nil)}

              (not (<= 200 status 299))
              {:error (unreachable url nil status)}

              :else
              (async/then (when cache (keep! cache url value))
                          (fn [_] {:value (str (:body value))}))))))))))

(defn read-chapters-file!
  "Read the chapters file at `url` with `opts`, in a future or a promise.
  The result is a map of its :url, :document and :report, or an :error,
  with :file as the :source."
  [url opts]
  (let [accept "application/json+chapters, application/json;q=0.9, */*;q=0.1"
        part   {:source :file :url url}]
    (async/then (fetch-text! url accept opts)
                (fn [{:keys [value error]}]
                  (if error
                    (assoc part :error error)
                    (with-report part
                                 (chapters/parse-json value)
                                 chapters/report))))))

(defn read-transcript-file!
  "Read the transcript that the reference `ref` names with `opts`, adding
  its :document and :report, or an :error, in a future or a promise."
  [{:keys [url type] :as ref} opts]
  (async/then (fetch-text! url (str (or type "text/vtt") ", */*;q=0.1") opts)
              (fn [{:keys [value error]}]
                (if error
                  (assoc ref :error error)
                  (with-report ref
                               (transcript/parse value type)
                               transcript/report)))))

(defn read-media-file!
  "Read the media file at `url` with `opts`, adding its :url and its
  :report as data, or an :error, in a future or a promise."
  [url opts]
  (let [read-opts (select-keys opts [:send :allow-pred :timeout-seconds :headers
                                     :except :fetch])]
    (async/then (media/read! url read-opts)
                (fn [file]
                  (if (:error file)
                    {:url url :error (:error file)}
                    (let [diagnostics (media/report file)]
                      (cond-> (assoc file :url url)
                        (seq diagnostics)
                        (assoc :report (vec diagnostics)))))))))

(defn read-stream!
  "Read the HLS stream at `url` with `opts`, adding its :url, :format :hls
  and :video?, true when a variant is a video and false when all are
  audio, or an :error, in a future or a promise."
  [url opts]
  (let [read-opts (select-keys opts [:send :allow-pred :timeout-seconds
                                     :headers :max-bytes :user-agent])]
    (async/then (hls/read! url read-opts)
                (fn [{:keys [error variants] :as stream}]
                  (if error
                    {:url url :error error}
                    (let [kinds  (set (map resolve/video? variants))
                          video? (cond (contains? kinds true) true
                                       (= #{false} kinds)     false)]
                      (cond-> (assoc stream :url url :format :hls)
                        (some? video?) (assoc :video? video?))))))))

(defn best-chapters
  "The :chapters of a view once its files are read, from the first of the
  `file`, the embedded chapters of `own`, the `media` file, and `own`.

  The chapters carry the :error of a file that failed in their :report."
  [file own media]
  (let [failed (:error file)
        from   (fn [part]
                 (cond-> part
                   failed (update :report (fnil conj []) failed)))]
    (cond
      (:document file)
      file

      (= :embedded (:source own))
      (from own)

      (:chapters media)
      (from (with-report {:source :media :url (:url media)}
                         (:chapters media)
                         chapters/report))

      own
      (from own)

      failed
      file)))

(defn- unresolved
  "A map of the :error that the value block of the `remote` item of a time
  split couldn't be fetched, with the `details` of why that aren't nil."
  [remote details]
  (diagnostics/failed (into {:id :value/remote-unresolved :remote-item remote}
                            (remove (comp nil? val))
                            details)))

(defn- fetch-index-value!
  "Ask the Podcast Index with `opts` for the value block of the `remote`
  item of a time split, the item's own first and then the feed's, in a
  future or a promise. The result is a map of the :value, which is nil
  when the index has none, or of an :error."
  [remote {:keys [index] :as opts}]
  (async/then (podcastindex/fetch-remote-value! index remote opts)
              #(if (:error %) % {:value %})))

(defn- feed-answer
  "What the `attempt` of the fetch of the feed of the `remote` item says: a
  map of the :value block of the item that it names, or else of the feed,
  or of an :error."
  [{:keys [feed-url item-guid] :as remote} {fetched :value thrown :error}]
  (let [{:keys [feed status error]} fetched
        item (some #(when (= item-guid (guid/item-guid %)) %)
                   (:rss/items feed))]
    (cond
      item  {:value (resolve/value feed item)}
      feed  {:value (resolve/value feed)}
      :else (unresolved remote {:url    feed-url
                                :status status
                                :reason (if thrown
                                          (async/explain thrown)
                                          (:reason error))}))))

(defn- fetch-feed-value!
  "Fetch the feed of the `remote` item of a time split with the :fetch of
  `opts`, in a future or a promise, and give a map of the :value block of
  the item, or else of the feed, or of an :error."
  [remote {:keys [fetch] :as opts}]
  (let [fetch-opts (select-keys opts [:send :allow-pred :timeout-seconds
                                      :max-bytes :user-agent])]
    (async/then (async/attempt #(fetch (:feed-url remote) fetch-opts))
                (partial feed-answer remote))))

;; podcast:remoteItem: feedGuid is the preferred address, feedUrl a fallback
(defn find-remote-value!
  "Find the value block of the `remote` item of a time split with `opts`,
  in a future or a promise: from the Podcast Index when `opts` give the
  :index, and from the feed URL of the item when they don't or the index
  has no block. The result is a map of the :value, nil when neither has a
  block, or of an :error."
  [{:keys [feed-guid feed-url] :as remote} opts]
  (async/then (if (and (:index opts) feed-guid)
                (fetch-index-value! remote opts)
                (unresolved remote {}))
              (fn [{:keys [value] :as asked}]
                (if (or value (nil? feed-url))
                  asked
                  (fetch-feed-value! remote opts)))))

(defn once-per-url
  "The function `fetch` of a feed, made to fetch each URL once, also when
  several calls ask for it at the same time."
  [fetch]
  (let [fetched (atom {})]
    (fn [url opts]
      (-> (swap! fetched update url #(or % (delay (fetch url opts))))
          (get url)
          force))))

(defn gathered
  "The value blocks of the `remotes`, and the report of those that failed,
  from what was `found` for each of them, in the same order."
  [remotes found]
  (let [failed (keep :error found)
        values (for [[remote {:keys [value error]}] (zipmap remotes found)
                     :when (not error)]
                 [remote value])]
    (cond-> {}
      (seq remotes) (assoc :remote-values (into {} values))
      (seq failed)  (assoc :remote-report (vec failed)))))
