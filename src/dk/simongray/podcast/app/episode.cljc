(ns dk.simongray.podcast.app.episode
  "Everything an episode page needs, in one map."
  (:require [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.episode.impl :as impl]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.feed.check.rules :as rules]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.hls :as hls]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def default-limits
  "The limits of the requests for the files and value blocks of an episode
  when their options don't set them: how many seconds a request can take,
  and the most bytes of a chapters file, a transcript or a feed."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

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

  - :languages, the wanted languages for both the :enclosure and the
    :transcript
  - :video?, :types, :max-bitrate, :max-height and :playable-pred, to
    select the :enclosure as resolve/enclosure does
  - :timed? and :transcript-types, to select the :transcript as
    resolve/transcript does
  - :url, the URL of the feed, for a feed that doesn't name its own
  - :notes-url-fn, a function of the web page of the episode and a URL
    in its show notes, which gives the URL to keep or nil, by default the
    URL made absolute
  - :timestamp-fn, a function of the seconds and the text of a time in
    the show notes, e.g. 12:34, which gives the Hiccup in its place, e.g.
    a link that plays from there, or nil to leave the text. A time in a
    link, or past the end of the episode, stays text.
  - :except, a set of the keys above to leave out"
  ([feed item]
   (view feed item {}))
  ([feed item {:keys [url notes-url-fn timestamp-fn except]
               :or   {notes-url-fn resolve/notes-url except #{}}
               :as   opts}]
   {:pre [(map? opts)]}
   (let [wanted?   (complement except)
         url       (or url (resolve/self-url feed))
         feed-guid (guid/feed-key feed url)
         read?     (or (wanted? :notes) (wanted? :text) (wanted? :chapters))
         page      (or (resolve/link item) (resolve/link feed))
         notes     (when read?
                     (some-> (resolve/description item)
                             (html/hiccup {:url-fn #(notes-url-fn page %)})))
         text      (when (and notes (or (wanted? :text) (wanted? :chapters)))
                     (html/text notes))
         own       (when (wanted? :chapters) (impl/own-chapters item text))]
     (cond-> (merge item
                    (resolve/properties feed item)
                    (impl/chosen feed item opts)
                    {:guid (guid/item-guid item)})
       feed-guid
       (assoc :id (guid/episode-id feed url item))

       (and notes (wanted? :notes))
       (assoc :notes (cond-> notes
                       timestamp-fn (impl/with-times timestamp-fn
                                      (resolve/duration item))))

       (and text (wanted? :text))
       (assoc :text text)

       own
       (assoc :chapters own)

       (wanted? :links)
       (assoc :links (links/links (links/ids feed item {:url url})))))))

;; W3C Media Session, Working Draft of 5 June 2026, the MediaMetadataInit
;; and ChapterInformationInit dictionaries
(defn metadata
  "The metadata of the episode `view` of the show `feed` for the Media
  Session API, as a map of the keys of MediaMetadataInit in kebab-case,
  e.g. for the session namespace of the play-nice library:

      (session/metadata! (metadata feed view))

  The artist is the author of the show, or else its title, since the
  author of an episode is often an email address, and the album is the
  title of the show. The chapters are those of the view, so for those of
  a chapters file, give the view the :chapters that read-files! gives."
  [feed view]
  (let [show    (resolve/title feed)
        image   #(when % [{:src %}])
        chapter (fn [{:keys [title start-time img]}]
                  (cond-> {:title (or title "") :start-time start-time}
                    img (assoc :artwork (image img))))]
    {:title        (or (:title view) "")
     :artist       (or (resolve/author feed) show "")
     :album        (or show "")
     :artwork      (or (image (:artwork view)) [])
     :chapter-info (mapv chapter
                         (chapters/visible (:document (:chapters view))))}))

(defn read-files!
  "Read the files of the episode `view` with the `opts` below. Returns a
  map, in ClojureScript as a promise:

  - :chapters has the :source, :url, :document and :report of the
    chapters. They come from the first place that has them: the chapters
    file, the embedded chapters, the media file, or the timestamps in the
    show notes.
  - :transcript is the reference that `view` chose, with its :document
    and :report.
  - :media is the :duration, :artwork and :report of the media file. For
    an HLS stream, it's what format.hls/read! gives, with :video?. The
    report has :file/duration when the duration differs from the one that
    the feed declares, so that a position may not carry over to another
    file of the episode.
  - :duration is that of the media file or the stream.
  - :bytes is the size of the media file, e.g. for a feed that gives the
    wrong length.

  Nothing is thrown for what the network or a server does, and a part
  that couldn't be read has an :error. The `opts` are:

  - :send, :allow-pred and :headers, as for any request
  - :fetch, a function that reads the media file by its ranges in place
    of a request, as format.media/read! takes it, e.g. the file fetcher of
    util.bytes.ranges for a downloaded file or one of a show of local files
  - :timeout-seconds and :max-bytes, over default-limits
  - :except, a set of the parts to leave unread
  - :cache, a document store of like-minded that keeps the chapters files
    and the transcripts, asked for again with their validators, and read
    when a request fails, e.g. offline"
  ([view]
   (read-files! view {}))
  ([view {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts            (merge default-limits {:send client/send!} opts)
         wanted?         (complement except)
         own             (:chapters view)
         enclosure       (:enclosure view)
         url             (:url enclosure)
         chapters-file   (delay
                           (when-let [chapters-url (and (wanted? :chapters)
                                                        (:chapters-url view))]
                             (impl/read-chapters-file! chapters-url opts)))
         transcript-file (delay
                           (when-let [ref (and (wanted? :transcript)
                                               (:transcript view))]
                             (impl/read-transcript-file! ref opts)))
         media-file      (delay
                           (when (and (wanted? :media) url)
                             (if (hls/playlist? enclosure)
                               (impl/read-stream! url opts)
                               (impl/read-media-file! url opts))))
         files           [chapters-file transcript-file media-file]]
     (async/then (async/all force files)
                 (fn [[file transcript media]]
                   (let [chapters (impl/best-chapters file own media)
                         duration (:duration media)
                         stated   (:duration view)
                         whole?   (or (not= :hls (:format media))
                                      (:ended? media))
                         other    {:id       :file/duration
                                   :declared stated
                                   :measured duration}
                         media    (cond-> media
                                    (and whole?
                                         (rules/durations-differ? stated
                                                                  duration))
                                    (update :report (fnil conj [])
                                            (diagnostics/describe other)))]
                     (cond-> {}
                       chapters       (assoc :chapters chapters)
                       transcript     (assoc :transcript transcript)
                       media          (assoc :media media)
                       duration       (assoc :duration duration)
                       (:bytes media) (assoc :bytes (:bytes media)))))))))

(defn report
  "The diagnostics of all the parts of the read `files`, each with the
  :part that it's about: :chapters, :transcript or :media."
  [files]
  (vec (for [part [:chapters :transcript :media]
             :let [{:keys [report error]} (get files part)]
             diagnostic (cond-> (vec report) error (conj error))]
         (assoc diagnostic :part part))))

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
                     (update :fetch impl/once-per-url))
         remotes (distinct (keep :remote-item (:time-splits (:value view))))]
     (async/then (async/all #(impl/find-remote-value! % opts) remotes)
                 (partial impl/gathered remotes)))))

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
