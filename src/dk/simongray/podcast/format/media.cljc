(ns dk.simongray.podcast.format.media
  "What a media file says about itself, apart from the feed.

  A media file gives its duration, its chapters and its artwork. It's an
  MP3 file, an MP4 or M4A file, an Ogg file of Opus or Vorbis, or an AAC
  file of ADTS frames.

  A reading takes a view of the parts of the file at hand, as in
  dk.simongray.podcast.util.bytes.view, and gives what they say, or the
  ranges that it still needs. A fetch of those ranges most often takes one
  or two requests and a few hundred kilobytes.

  A host that inserts ads serves another file to each request, so the
  duration is that of the file that came.

  The readers are strict, and their namespaces name their quirks. The
  :except option leaves out the quirks that it names."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.media.aac :as aac]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.format.media.mp3 :as mp3]
            [dk.simongray.podcast.format.media.mp4 :as mp4]
            [dk.simongray.podcast.format.media.ogg :as ogg]
            [dk.simongray.podcast.util.bytes.ranges :as ranges]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

;; IANA Media Types: RFC 3003 for audio/mpeg, RFC 4337 for audio/mp4 and
;; video/mp4, RFC 5334 for audio/ogg, RFC 7845, section 9, for .opus; and
;; the spellings of Apple Podcasts, which PSP-1 lists beside them
(def types
  "What each media type of an enclosure says of its files: the :extension
  of a file name, and the :format that read finds in them, for those it
  reads."
  {"audio/mpeg"      {:extension "mp3" :format :mp3}
   "audio/mp4"       {:extension "m4a" :format :mp4}
   "audio/x-m4a"     {:extension "m4a" :format :mp4}
   "audio/m4a"       {:extension "m4a" :format :mp4}
   "video/mp4"       {:extension "mp4" :format :mp4}
   "video/m4v"       {:extension "m4v" :format :mp4}
   "video/x-m4v"     {:extension "m4v" :format :mp4}
   "video/quicktime" {:extension "mov" :format :mp4}
   "audio/aac"       {:extension "aac" :format :aac}
   "audio/ogg"       {:extension "ogg" :format :ogg}
   "audio/opus"      {:extension "opus" :format :ogg}
   "audio/flac"      {:extension "flac"}
   "audio/wav"       {:extension "wav"}
   "audio/x-wav"     {:extension "wav"}
   "audio/webm"      {:extension "webm"}
   "video/webm"      {:extension "webm"}})

(def ^:no-doc formats
  "The format that read finds in a file of each media type, for the types
  whose files it reads."
  (into {}
        (keep (fn [[type {:keys [format]}]]
                (when format
                  [type format])))
        types))

(def ^:no-doc signatures
  "How the bytes at the start of a file tell its format, in order, as pairs
  of the format and a predicate of the view and the place: an ftyp box for
  :mp4, a page for :ogg, an ADTS or an MPEG audio frame for :aac or :mp3,
  and an ID3 tag whose header doesn't read for :mp3."
  [[:mp4 #(= "ftyp" (view/code %1 (+ %2 4) 4))]
   [:ogg #(= "OggS" (view/code %1 %2 4))]
   [:aac aac/frame-header]
   [:mp3 mp3/frame-header]
   [:mp3 #(= "ID3" (view/code %1 %2 3))]])

(def ^:no-doc readers
  "The reader of each format, which gives the keys of read."
  {:mp3 mp3/read
   :mp4 mp4/read
   :ogg ogg/read
   :aac aac/read})

(defn- sniff
  "The format of the file whose start `view` holds, by signatures, or nil.
  After ID3 tags, the file is :aac when an ADTS frame follows them, and
  :mp3 otherwise."
  [view]
  (view/need view 0 12)
  (let [start (id3/skip view 0)
        total (or (:total view) ##Inf)]
    (cond
      (zero? start)
      (some (fn [[format matches?]] (when (matches? view 0) format)) signatures)

      (and (< start total) (aac/frame-header view start))
      :aac

      :else
      :mp3)))

(defn- with-image
  "The `chapter` with the image of its :artwork as the data URL of its
  :img, as a chapters document has it."
  [{:keys [artwork] :as chapter}]
  (cond-> (dissoc chapter :artwork)
    artwork (assoc :img (image/data-url (:type artwork) (:data artwork)))))

(defn- result
  "What the `file` of `format` says about itself, as its reader gave it:
  with the tags as one map, without the ID3 tags or the comments they came
  from, and with its chapters as a chapters document."
  [format {:keys [chapters] :as file}]
  (let [document {:version  chapters/version
                  :chapters (mapv with-image chapters)}]
    (cond-> (assoc (dissoc file :id3 :comments :chapters) :format format)
      (seq chapters) (assoc :chapters document))))

(defn- with-bitrate
  "The `file` with its :bitrate when its reader gave none: the bits of its
  media over its duration, when it has its :bytes, :media-start and
  :duration."
  [{:keys [bitrate bytes media-start duration] :as file}]
  (if (and (nil? bitrate)
           (number? bytes)
           (number? media-start)
           (number? duration)
           (pos? duration)
           (> bytes media-start))
    (assoc file :bitrate (long (/ (* 8 (- bytes media-start)) duration)))
    file))

(defn report
  "The diagnostics of the read `file`. Each diagnostic has its message."
  [file]
  (view/report file))

(defn read
  "What the media file whose parts `view` holds says about itself, read
  with `opts`. It's a map of the keys below, and for an MP4 file the
  :width, :height and :movie-first? that format.media.mp4/read gives, each
  absent when the file doesn't have it.

  - :format is :mp3, :mp4, :ogg or :aac
  - :codecs is the RFC 6381 codecs string of an MP4, Ogg or AAC file
  - :tags is a map of the :title, :artist, :album, :date, :genre and
    :description that the file gives in its ID3 tags, iTunes metadata or
    Vorbis comments, e.g. for an upload or a local file
  - :duration is in seconds
  - :bitrate is in bit/s, that of the media over the duration
  - :sample-rate and :channels of the audio, and for an MP3 or AAC file
    :vbr?, whether the bitrate varies, as its headers say
  - :chapters is a chapters document of dk.simongray.podcast.format.chapters,
    and the images of an MP3 file's chapters are data URLs, which a host
    serves as files with update-image-urls of that namespace
  - :artwork is a map of :type and :data
  - :bytes is the size of the file, when the view knows it
  - :media-start is where the audio or video starts, after the ID3 tags of
    an MP3 or AAC file, the boxes before the media data of an MP4 file, or
    the pages of the headers of an Ogg file. With :bytes, it's the :header
    and the :size of analytics/threshold.

  When the view lacks bytes, the result has the ranges that the reading
  needs under :need, as pairs of start and end, end excluded.

  The reading doesn't throw on the content of the file, and the report
  tells what it didn't read. The :except of `opts` is the ids of the quirks to
  leave out."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (let [unknown (view/diagnostic :file/unknown-format :warning)
         total   (:total view)]
     (view/reading #(with-bitrate
                      (cond-> (if-let [format (sniff view)]
                                (result format ((readers format) view opts))
                                (view/reported {} [unknown]))
                        (number? total) (assoc :bytes total)))))))

(defn read!
  "Read what the media file at `url` says about itself, fetched with
  `opts`. On the JVM it gives the result, and in ClojureScript a promise
  of it.

  The `opts` are these:

  - :fetch, a function as http/fetcher makes, to fetch your own way, or
    for a local file one that ranges/file-fetcher or ranges/blob-fetcher
    makes, when `url` only names the file in an error
  - :headers, :timeout-seconds and :send, as http/fetcher takes them
  - :allow-pred and :user-agent, as http/send! takes them
  - :except, as read takes it
  - the limits of ranges/read!, with ranges/default-limits for those that
    they don't set

  When the ranges don't come, or the file asks for more than the limits
  allow, the report has :file/incomplete. A server that ignores ranges
  sends a file from its start, so the bytes at an offset cost all the
  bytes before them.

  Nothing that the network or the server does throws. When a request
  fails, the result has a :file/unreachable diagnostic under :error."
  ([url]
   (read! url {}))
  ([url opts]
   (let [read-opts (select-keys opts [:except])]
     (ranges/read-url! url #(read % read-opts) opts))))

(comment
  ;; an MP3 file of 20 frames of 128 kbit/s, with no tag
  (def sample
    (let [frame (concat [0xFF 0xFB 0x90 0x00] (repeat 413 0))
          b     (bytes/of (apply concat (repeat 20 frame)))]
      (view/add (view/view) 0 b (bytes/length b))))

  (read sample)
  (map :id (report (read sample)))

  ;; a file that is no media file
  (report (read (view/add (view/view) 0
                          (bytes/encode "<html>not audio</html>") 22)))

  (read! "https://example.org/episode.mp3")
  #_.)
