(ns dk.simongray.podcast.app.episode
  "Everything an episode page needs, in one map."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]))

(def default-limits
  "The limits of the requests for the files and value blocks of an episode
  when their options don't set them: how many seconds a request can take,
  and the most bytes of a chapters file, a transcript or a feed."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn- chosen
  "The :enclosure and :transcript of `item` in `feed`, as `opts` select
  them with the options of resolve/enclosure and resolve/transcript."
  [feed item {:keys [languages timed? transcript-types] :as opts}]
  (let [media-opts      (select-keys opts [:types :max-bitrate])
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

(defn- own-chapters
  "The chapters in the feed itself for `item`: the embedded ones, or else
  those from the timestamps of `text`, its show notes as plain text, or
  nil."
  [item text]
  (if-let [embedded (resolve/embedded-chapters item)]
    (with-report {:source :embedded} embedded chapters/report)
    (when-let [inferred (some-> text str/split-lines chapters/from-lines)]
      (with-report {:source :inferred} inferred chapters/report))))

(defn view
  "The episode `item` of `feed` as a page shows it, with the `opts` below:
  the item with the properties of resolve/properties merged in, and these
  keys:

  - :guid, the guid of the item
  - :id, its id for the profile, when the feed has a guid or `opts` give
    the :url of the feed
  - :notes, the show notes as Hiccup, and :text, as plain text
  - :chapters, the :source and :document of the chapters in the feed
    itself, where the source is :embedded, or else :inferred from the
    timestamps in the show notes. A chapters file is named by
    :chapters-url.
  - :links, links to the episode in other apps

  The `opts` are:

  - :types and :max-bitrate, to select the :enclosure as resolve/enclosure
    does
  - :languages, :timed? and :transcript-types, to select the :transcript
    as resolve/transcript does
  - :url, the URL of the feed, for a feed that doesn't name its own
  - :notes-url-fn, a function of the web page of the episode and a URL
    in its show notes, which gives the URL to keep or nil, by default the
    URL made absolute
  - :except, a set of the keys above to leave out"
  ([feed item]
   (view feed item {}))
  ([feed item {:keys [url notes-url-fn except]
               :or   {notes-url-fn resolve/notes-url except #{}}
               :as   opts}]
   {:pre [(map? opts)]}
   (let [wanted?   (complement except)
         url       (or url (resolve/self-url feed))
         feed-guid (guid/feed-guid feed url)
         read?     (or (wanted? :notes) (wanted? :text) (wanted? :chapters))
         page      (or (resolve/link item) (resolve/link feed))
         notes     (when read?
                     (some-> (resolve/description item)
                             (html/hiccup {:url-fn #(notes-url-fn page %)})))
         text      (when (and notes (or (wanted? :text) (wanted? :chapters)))
                     (html/text notes))
         own       (when (wanted? :chapters) (own-chapters item text))]
     (cond-> (merge item
                    (resolve/properties feed item)
                    (chosen feed item opts)
                    {:guid (guid/item-guid item)})
       feed-guid
       (assoc :id (guid/episode-id feed url item))

       (and notes (wanted? :notes))
       (assoc :notes notes)

       (and text (wanted? :text))
       (assoc :text text)

       own
       (assoc :chapters own)

       (wanted? :links)
       (assoc :links (links/links (links/ids feed item {:url url})))))))

(defn- unreachable
  "The diagnostic for the file at `url` that couldn't be fetched, because
  the request failed with `e` or the answer had the `status` of a failure."
  [url e status]
  (if e
    (diagnostics/describe (assoc (view/unreachable (http/failure e)) :url url))
    (diagnostics/describe {:id :file/unreachable :url url :status status})))

(defn- fetch-text!
  "Fetch the text of the file at `url` with `opts`, asking for the `accept`
  types, in a future or a promise. The text is the :value, or else a
  diagnostic is the :error."
  [url accept {:keys [send] :as opts}]
  (async/then (http/try-send! send {:url url :headers {"Accept" accept}} opts)
              (fn [{:keys [value error]}]
                (cond
                  error
                  {:error (unreachable url error nil)}

                  (not (<= 200 (long (:status value)) 299))
                  {:error (unreachable url nil (:status value))}

                  :else
                  {:value (str (:body value))}))))

(defn- read-chapters-file!
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

(defn- read-transcript-file!
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

(defn- read-media-file!
  "Read the media file at `url` with `opts`, adding its :url and its
  :report as data, or an :error, in a future or a promise."
  [url opts]
  (let [read-opts (select-keys opts [:send :allow-pred :timeout-seconds :headers
                                     :except])]
    (async/then (media/read! url read-opts)
                (fn [file]
                  (if (:error file)
                    {:url url :error (:error file)}
                    (let [diagnostics (media/report file)]
                      (cond-> (assoc file :url url)
                        (seq diagnostics)
                        (assoc :report (vec diagnostics)))))))))

(defn- best-chapters
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

(defn read-files!
  "Read the files of the episode `view` with the `opts` below. Returns a
  map, in ClojureScript as a promise:

  - :chapters has the :source, :url, :document and :report of the
    chapters. They come from the first place that has them: the chapters
    file, the embedded chapters, the media file, or the timestamps in the
    show notes.
  - :transcript is the reference that `view` chose, with its :document
    and :report.
  - :media is the :duration, :artwork and :report of the media file.
  - :duration is that of the media file.

  Nothing is thrown for what the network or a server does, and a part
  that couldn't be read has an :error. The `opts` are:

  - :send, :allow-pred and :headers, as for any request
  - :timeout-seconds and :max-bytes, over default-limits
  - :except, a set of the parts to leave unread"
  ([view]
   (read-files! view {}))
  ([view {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts            (merge default-limits {:send client/send!} opts)
         wanted?         (complement except)
         own             (:chapters view)
         url             (:url (:enclosure view))
         chapters-file   (delay
                           (when-let [chapters-url (and (wanted? :chapters)
                                                        (:chapters-url view))]
                             (read-chapters-file! chapters-url opts)))
         transcript-file (delay
                           (when-let [ref (and (wanted? :transcript)
                                               (:transcript view))]
                             (read-transcript-file! ref opts)))
         media-file      (delay
                           (when (and (wanted? :media) url)
                             (read-media-file! url opts)))
         files           [chapters-file transcript-file media-file]]
     (async/then (async/all force files)
                 (fn [[file transcript media]]
                   (let [chapters (best-chapters file own media)
                         duration (:duration media)]
                     (cond-> {}
                       chapters   (assoc :chapters chapters)
                       transcript (assoc :transcript transcript)
                       media      (assoc :media media)
                       duration   (assoc :duration duration))))))))

(defn report
  "The diagnostics of all the parts of the read `files`, each with the
  :part that it's about: :chapters, :transcript or :media."
  [files]
  (vec (for [part [:chapters :transcript :media]
             :let [{:keys [report error]} (get files part)]
             diagnostic (cond-> (vec report) error (conj error))]
         (assoc diagnostic :part part))))

(defn- unresolved
  "A map of the :error that the value block of the `remote` item of a time
  split couldn't be fetched, with the `details` of why that aren't nil."
  [remote details]
  {:error (diagnostics/describe
           (into {:id :value/remote-unresolved :remote-item remote}
                 (remove (comp nil? val))
                 details))})

(defn- index-answer
  "What the `attempt` of the request to `url` for the value block of the
  `remote` item says: a map of the :value, nil when the Podcast Index has
  no block, or a map of an :error."
  [remote url {:keys [value error]}]
  (let [{:keys [status body]} value
        ok?    (and (not error) (<= 200 status 299))
        answer (when ok?
                 (try (podcastindex/response (str body))
                      (catch #?(:clj Exception :cljs :default) _ nil)))
        block  (:value answer)
        failed #(unresolved remote (assoc % :url url))]
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

      :else
      (when (and (:status answer) (seq (:recipients block)))
        {:value block}))))

(defn- fetch-index-value!
  "Ask the Podcast Index with `opts` for the value block of the `remote`
  item of a time split, the item's own first and then the feed's, in a
  future or a promise. The result is a map of the :value, nil when the
  index has none, or of an :error."
  [remote {:keys [index send now-fn] :as opts}]
  (let [requests (podcastindex/remote-value-requests
                  index remote {:auth-date (quot (inst-ms (now-fn)) 1000)})
        answer!  (fn [request]
                   (async/then (http/try-send! send request opts)
                               (partial index-answer remote (:url request))))]
    (async/then (async/some answer! requests) #(or % {:value nil}))))

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
(defn- find-remote-value!
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

(defn- once-per-url
  "The function `fetch` of a feed, made to fetch each URL once, also when
  several calls ask for it at the same time."
  [fetch]
  (let [fetched (atom {})]
    (fn [url opts]
      (-> (swap! fetched update url #(or % (delay (fetch url opts))))
          (get url)
          force))))

(defn- gathered
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

(defn fetch-remote-values!
  "Fetch the value blocks that the time splits of the episode `view` point
  at, with the `opts` below. Returns a map, in ClojureScript as a promise:

  - :remote-values, the value block of each remote item, or nil when it
    has none
  - :remote-report, a diagnostic for each remote item whose block
    couldn't be fetched, which :remote-values leaves out

  Each block comes from the Podcast Index when `opts` give its
  credentials. When they don't, or the index has no block, it comes from
  the feed URL of the remote item, if it names one. Fetch the blocks when
  the episode starts rather than at each payment, and again for those in
  the report. Nothing is thrown for what the network or a server does.
  The `opts` are:

  - :send and :allow-pred, as for any request
  - :timeout-seconds and :max-bytes, over default-limits
  - :index, the credentials of the Podcast Index, and :now-fn, a function of
    the current time to sign the requests with
  - :fetch, in place of dk.simongray.podcast/fetch!. Each remote feed is
    fetched once, though several songs of an album name it."
  ([view]
   (fetch-remote-values! view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (let [opts    (-> (merge default-limits
                            {:send   client/send!
                             :now-fn time/now
                             :fetch  podcast/fetch!}
                            opts)
                     (update :fetch once-per-url))
         remotes (distinct (keep :remote-item (:time-splits (:value view))))]
     (async/then (async/all #(find-remote-value! % opts) remotes)
                 (partial gathered remotes)))))

(comment
  (def show (:feed (podcast/fetch! "https://feeds.podcastindex.org/pc20.xml")))
  (def item (first (resolve/episodes show)))

  (select-keys (view show item)
               [:title :published :duration :enclosure :transcript
                :chapters-url :links])
  (:source (:chapters (view show item)))

  ;; the files of the episode, read, and the page
  (def v (view show item {:languages ["da" "en"]}))
  (def files (read-files! v))
  (:source (:chapters files))
  (count (:segments (:document (:transcript files))))
  (:duration (:media files))
  (report files)
  (keys (merge v files))

  ;; the value blocks of the songs of a mix tape, which its time splits name
  (def mixtape
    (:feed (podcast/fetch! "https://mikesmixtape.com/mikesmixtaperss.xml")))
  (def songs
    (view mixtape (first (resolve/episodes mixtape))
          {:except #{:notes :text :chapters :links}}))
  (def remotes
    (fetch-remote-values! songs {:index {:key "…" :secret "…"}}))
  (count (:remote-values remotes))
  (:remote-report remotes)
  #_.)
