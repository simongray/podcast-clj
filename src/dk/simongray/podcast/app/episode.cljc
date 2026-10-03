(ns dk.simongray.podcast.app.episode
  "Everything an episode page needs, in one map.

  Use view for what the feed says about the episode, and merge in
  read-files! for what its chapters file, transcript and media file say."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.profile :as profile]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]))

(def default-limits
  "The limits of read-files! when its options don't set them: how many
  seconds a request can take, and the most bytes of a chapters file or a
  transcript."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn- chosen
  "The :enclosure and :transcript of `item` in `show`, as `opts` select
  them with the options of resolve/enclosure and resolve/transcript."
  [show item {:keys [languages timed? transcript-types] :as opts}]
  (into {} (remove (comp nil? val))
        {:enclosure  (resolve/enclosure item (select-keys opts [:types :max-bitrate]))
         :transcript (resolve/transcript show item (cond-> {:languages languages :timed? timed?}
                                                     transcript-types (assoc :types transcript-types)))}))

(defn- with-report
  "The `part` of a view with its `document`, and the diagnostics that
  `report` gives for it as data, since metadata doesn't survive printing."
  [part document report]
  (let [diagnostics (report document)]
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
  "The episode `item` of the feed `show` as a page shows it, with the
  `opts` below: the item with the properties of resolve/properties merged
  in, and these keys:

  - :guid, the guid of the item
  - :id, its id for app.profile, when the feed has a guid or `opts` give
    the :url of the feed
  - :notes, the show notes as Hiccup, and :text, as plain text
  - :chapters, the :source and :document of the chapters in the feed
    itself, where the source is :embedded, or else :inferred from the
    timestamps in the show notes. A chapters file is named by
    :chapters-url, and read-files! reads it.
  - :links, links to the episode in other apps, as links/links gives them

  The `opts` are:

  - :types and :max-bitrate, to select the :enclosure as resolve/enclosure
    does
  - :languages, :timed? and :transcript-types, to select the :transcript
    as resolve/transcript does
  - :url, the URL of the feed, for a feed that doesn't name its own
  - :except, a set of the keys above to leave out"
  ([show item]
   (view show item {}))
  ([show item {:keys [url except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [wanted?     (complement except)
         url         (or url (resolve/self-url show))
         feed-guid   (feed/feed-guid show url)
         notes       (when (and (or (wanted? :notes) (wanted? :text) (wanted? :chapters))
                                (resolve/description item))
                       (html/hiccup (resolve/description item)))
         text        (when (and notes (or (wanted? :text) (wanted? :chapters)))
                       (html/text notes))
         own         (when (wanted? :chapters) (own-chapters item text))]
     (cond-> (merge item
                    (resolve/properties show item)
                    (chosen show item opts)
                    {:guid (feed/item-guid item)})
       feed-guid                 (assoc :id (profile/episode-id show url item))
       (and notes (wanted? :notes)) (assoc :notes notes)
       (and text (wanted? :text))   (assoc :text text)
       own                       (assoc :chapters own)
       (wanted? :links)          (assoc :links (links/links (links/ids show item {:url url})))))))

(defn- unreachable
  "The diagnostic for the file at `url` that couldn't be fetched, because
  the request failed with `e` or the answer had the `status` of a failure."
  [url e status]
  (if e
    (assoc (bytes/unreachable e) :url url)
    (diagnostics/describe {:id :file/unreachable :url url :status status})))

(defn- fetched!
  "Fetch the text of the file at `url` with `opts`, asking for the `accept`
  types, in a future or a promise. The text is the :value, or else a
  diagnostic is the :error."
  [url accept {:keys [send] :as opts}]
  (async/then (http/sent! send {:url url :headers {"Accept" accept}} opts)
              (fn [{:keys [value error]}]
                (cond
                  error                             {:error (unreachable url error nil)}
                  (not (<= 200 (long (:status value)) 299)) {:error (unreachable url nil (:status value))}
                  :else                             {:value (str (:body value))}))))

(defn- chapters-file!
  "Read the chapters file at `url` with `opts`, in a future or a promise.
  The result is a map of its :url, :document and :report, or an :error,
  with :file as the :source."
  [url opts]
  (async/then (fetched! url "application/json+chapters, application/json;q=0.9, */*;q=0.1" opts)
              (fn [{:keys [value error]}]
                (if error
                  {:source :file :url url :error error}
                  (with-report {:source :file :url url} (chapters/parse-json value) chapters/report)))))

(defn- transcript-file!
  "Read the transcript that the reference `ref` names with `opts`, adding
  its :document and :report, or an :error, in a future or a promise."
  [{:keys [url type] :as ref} opts]
  (async/then (fetched! url (str (or type "text/vtt") ", */*;q=0.1") opts)
              (fn [{:keys [value error]}]
                (if error
                  (assoc ref :error error)
                  (with-report ref (transcript/parse value type) transcript/report)))))

(defn- media-file!
  "Read the media file at `url` with media/read! and `opts`, adding its
  :url and its :report as data, or an :error, in a future or a promise."
  [url opts]
  (async/then (media/read! url (select-keys opts [:send :allow? :timeout-seconds :headers :except]))
              (fn [file]
                (if (:error file)
                  {:url url :error (:error file)}
                  (let [diagnostics (media/report file)]
                    (cond-> (assoc file :url url)
                      (seq diagnostics) (assoc :report (vec diagnostics))))))))

(defn- best-chapters
  "The :chapters of a view once its files are read, from the first of the
  `file`, the embedded chapters of `own`, the `media` file, and `own`.

  The chapters carry the :error of a file that failed in their :report."
  [file own media]
  (let [failed (:error file)
        from   (fn [part] (cond-> part failed (update :report (fnil conj []) failed)))]
    (cond
      (:document file)                          file
      (= :embedded (:source own))               (from own)
      (:chapters media)                         (from (with-report {:source :media :url (:url media)} (:chapters media) chapters/report))
      own                                       (from own)
      failed                                    file)))

(defn read-files!
  "Read the files of the episode `view` with the `opts` below, for a page
  to merge into the view. Returns a map, in ClojureScript as a promise:

  - :chapters has the :source, :url, :document and :report of the
    chapters. They come from the first place that has them: the chapters
    file, the embedded chapters, the media file, or the timestamps in the
    show notes.
  - :transcript is the reference that `view` chose, with its :document
    and :report.
  - :media is the :duration, :artwork and :report of the media file, as
    media/read! reads them, and the :duration of the page becomes that of
    the file.

  Nothing is thrown for what the network or a server does, and a part
  that couldn't be read has an :error. The report function gathers the
  reports of all the parts. The `opts` are:

  - :send, :allow? and :headers, as for any request
  - :timeout-seconds and :max-bytes, over default-limits
  - :except, a set of the parts to leave unread"
  ([view]
   (read-files! view {}))
  ([view {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts    (merge default-limits {:send http/call!} opts)
         wanted? (complement except)
         own     (:chapters view)
         url     (:url (:enclosure view))]
     (async/then (async/all force [(delay (when-let [chapters-url (and (wanted? :chapters) (:chapters-url view))] (chapters-file! chapters-url opts)))
                                   (delay (when-let [ref (and (wanted? :transcript) (:transcript view))] (transcript-file! ref opts)))
                                   (delay (when (and (wanted? :media) url) (media-file! url opts)))])
                 (fn [[file transcript media]]
                   (let [chapters (best-chapters file own media)]
                     (cond-> {}
                       chapters          (assoc :chapters chapters)
                       transcript        (assoc :transcript transcript)
                       media             (assoc :media media)
                       (:duration media) (assoc :duration (:duration media)))))))))

(defn report
  "The diagnostics of all the parts of `files`, as read-files! gives them,
  each with the :part that it's about: :chapters, :transcript or :media."
  [files]
  (vec (for [part [:chapters :transcript :media]
             :let [{:keys [report error]} (get files part)]
             diagnostic (cond-> (vec report) error (conj error))]
         (assoc diagnostic :part part))))

(comment
  (require '[dk.simongray.podcast :as podcast])
  (def show (:feed (podcast/fetch! "https://feeds.podcastindex.org/pc20.xml")))
  (def item (first (resolve/episodes show)))

  (select-keys (view show item) [:title :published :duration :enclosure :transcript :chapters-url :links])
  (:source (:chapters (view show item)))

  ;; the files of the episode, read, and the page
  (def v (view show item {:languages ["da" "en"]}))
  (def files (read-files! v))
  (:source (:chapters files))
  (count (:segments (:document (:transcript files))))
  (:duration (:media files))
  (report files)
  (keys (merge v files))
  #_.)
