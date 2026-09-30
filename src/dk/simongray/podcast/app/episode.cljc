(ns dk.simongray.podcast.app.episode
  "One episode as a page shows it, in one map. `view` is what the feed
  says of the episode: the item, its properties as `feed.resolve` answers
  them, the show notes as Hiccup and as text, the chapters that the feed
  itself holds, and the links to the apps. `read-files!` reads what the
  files of the episode say, the chapters file, the transcript and the
  media file, for a page to merge into the view.

  A page reads one map. The functions below the view give the same
  answers, so an app drops down to them when a step is not what it wants.
  `(:enclosure view)` is what `resolve/enclosure` gives, and `(:document
  (:transcript view))` is what `transcript/parse` gives."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
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
  "The limits of `read-files!` that its options do not set: the seconds
  that a request can take, and the most bytes of a chapters file or a
  transcript."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn- chosen
  "The `:enclosure` and the `:transcript` of `item` in `show`, as `opts`
  select them: `:types` and `:max-bitrate` for the enclosure, and
  `:languages`, `:timed?` and `:transcript-types` for the transcript."
  [show item {:keys [languages timed? transcript-types] :as opts}]
  (into {} (remove (comp nil? val))
        {:enclosure  (resolve/enclosure item (select-keys opts [:types :max-bitrate]))
         :transcript (resolve/transcript show item (cond-> {:languages languages :timed? timed?}
                                                     transcript-types (assoc :types transcript-types)))}))

(defn- with-report
  "The part `part` of a view with its `:report`, the report of its
  `document` as data, because metadata does not survive printing."
  [part document report]
  (let [diagnostics (report document)]
    (cond-> (assoc part :document document)
      (seq diagnostics) (assoc :report (vec diagnostics)))))

(defn- own-chapters
  "The chapters that the feed itself holds for `item`: the embedded ones,
  else those read from the timestamps of `text`, the plain text of its
  show notes. Nil for none."
  [item text]
  (if-let [embedded (resolve/embedded-chapters item)]
    (with-report {:source :embedded} embedded chapters/report)
    (when-let [inferred (some-> text str/split-lines chapters/from-lines)]
      (with-report {:source :inferred} inferred chapters/report))))

(defn view
  "The episode `item` of the feed `show`, as a page shows it: the item with
  the properties of `resolve/properties` merged in, and these keys:

  - `:guid`, the guid of the item, and `:id`, its identity for
    `app.player`, when the feed has a guid or `opts` give the `:url` of
    the feed
  - `:notes`, the show notes as Hiccup, and `:text`, as plain text
  - `:chapters`, `{:source :document}`, the chapters that the feed itself
    holds: `:embedded`, else `:inferred` from the timestamps of the show
    notes. `:chapters-url` names a chapters file, which `read-files!`
    reads.
  - `:links`, the links to the episode in the apps, as `links/links`
    gives them

  `opts` select the `:enclosure` and the `:transcript` as
  `resolve/enclosure` and `resolve/transcript` do: `:types` and
  `:max-bitrate` for the enclosure, `:languages`, `:timed?` and
  `:transcript-types` for the transcript. `:url` is the URL of the feed,
  for a feed that does not name its own. `:except` is a set of the keys
  above that the view leaves out, and does not compute: a list of
  episodes needs no `:notes`, `:text`, `:chapters` or `:links`."
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
       feed-guid                 (assoc :id (player/episode-id show url item))
       (and notes (wanted? :notes)) (assoc :notes notes)
       (and text (wanted? :text))   (assoc :text text)
       own                       (assoc :chapters own)
       (wanted? :links)          (assoc :links (links/links (links/ids show item {:url url})))))))

(defn- unreachable
  "The diagnostic of the file at `url` that did not come: the request
  failed with `e`, or the answer had the `status` of a failure."
  [url e status]
  (if e
    (assoc (bytes/unreachable e) :url url)
    (diagnostics/describe {:id :file/unreachable :url url :status status})))

(defn- fetched!
  "The text of the file at `url`, asked for with the `accept` types, as
  `{:value text}` or `{:error diagnostic}`; deferred."
  [url accept {:keys [send] :as opts}]
  (async/then (http/sent! send {:url url :headers {"Accept" accept}} opts)
              (fn [{:keys [value error]}]
                (cond
                  error                             {:error (unreachable url error nil)}
                  (not (<= 200 (long (:status value)) 299)) {:error (unreachable url nil (:status value))}
                  :else                             {:value (str (:body value))}))))

(defn- chapters-file!
  "The chapters file at `url`, read: `{:source :file :url :document
  :report}`, or with `:error` when it did not come; deferred."
  [url opts]
  (async/then (fetched! url "application/json+chapters, application/json;q=0.9, */*;q=0.1" opts)
              (fn [{:keys [value error]}]
                (if error
                  {:source :file :url url :error error}
                  (with-report {:source :file :url url} (chapters/parse-json value) chapters/report)))))

(defn- transcript-file!
  "The transcript `ref` of the view, read: the reference with its
  `:document` and its `:report`, or with `:error` when it did not come;
  deferred."
  [{:keys [url type] :as ref} opts]
  (async/then (fetched! url (str (or type "text/vtt") ", */*;q=0.1") opts)
              (fn [{:keys [value error]}]
                (if error
                  (assoc ref :error error)
                  (with-report ref (transcript/parse value type) transcript/report)))))

(defn- media-file!
  "The media file at `url`, read by `media/read!`: what it says of itself,
  with the `:url` and its `:report` as data, or with `:error`; deferred."
  [url opts]
  (async/then (media/read! url (select-keys opts [:send :allow? :timeout-seconds :headers :except]))
              (fn [file]
                (if (:error file)
                  {:url url :error (:error file)}
                  (let [diagnostics (media/report file)]
                    (cond-> (assoc file :url url)
                      (seq diagnostics) (assoc :report (vec diagnostics))))))))

(defn- best-chapters
  "The `:chapters` of a view after its files came: the `file` when it
  read, else what the feed holds under `own` when embedded, else the
  chapters of the `media` file, else `own`. The chapters that came carry
  the `:error` of a file that did not, in their `:report`."
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
  "Read the files of the episode `view`: what they say, for a page to
  merge into the view. On the JVM the map, in ClojureScript a promise
  of it.

  - `:chapters` is `{:source :url :document :report}`: the chapters file
    when it came, else the embedded chapters, else the chapters of the
    media file, else those inferred from the show notes. `:source` tells
    which. When the file did not come, the report of the chapters that
    did has its `:file/unreachable`, and `:chapters` has `:error` when
    nothing else holds chapters.
  - `:transcript` is the reference that `view` chose, with its
    `:document` as `transcript/parse` reads it and its `:report`, or with
    `:error` when the file did not come.
  - `:media` is what `media/read!` gives of the media file: its
    `:duration`, its `:artwork` and its `:report`, or `:error`. The
    `:duration` is then that of the file, which is what a listener gets,
    and can differ from what the feed says.

  The step throws nothing for what the network or a server does. Each
  part that did not come has `:error`, a diagnostic with the `:reason`.
  `report` gathers the reports of all the parts.

  The `opts` are those of the requests: `:send`, a function that sends
  a request map in the place of `http/call!`, `:allow?`, a predicate of
  a URL, `:headers`, and the `:timeout-seconds` and `:max-bytes` of
  `default-limits`. In `:except`, `:chapters`, `:transcript` and
  `:media` leave those files unread: a page that needs no duration of
  the file and no artwork from it gives `#{:media}`."
  ([view]
   (read-files! view {}))
  ([view {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts    (merge default-limits {:send http/call!} opts)
         wanted? (complement except)
         own     (:chapters view)
         url     (:url (:enclosure view))]
     (async/then (async/all [(when-let [chapters-url (and (wanted? :chapters) (:chapters-url view))] (chapters-file! chapters-url opts))
                             (when-let [ref (and (wanted? :transcript) (:transcript view))] (transcript-file! ref opts))
                             (when (and (wanted? :media) url) (media-file! url opts))])
                 (fn [[file transcript media]]
                   (let [chapters (best-chapters file own media)]
                     (cond-> {}
                       chapters          (assoc :chapters chapters)
                       transcript        (assoc :transcript transcript)
                       media             (assoc :media media)
                       (:duration media) (assoc :duration (:duration media)))))))))

(defn report
  "The diagnostics of all the parts of `files`, as `read-files!` gives
  them or a page has them merged in, each with the `:part` that it is
  about: `:chapters`, `:transcript` or `:media`."
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
