(ns dk.simongray.podcast.format.media
  "What a media file says about itself, apart from the feed.

  A media file gives its duration, its chapters and its artwork. It's an
  MP3 file, or an MP4 or M4A file.

  A reading takes a view of the parts of the file at hand, as in
  dk.simongray.podcast.util.bytes.view, and gives what they say, or the
  ranges that it still needs. A fetch of those ranges most often takes one
  or two requests and a few hundred kilobytes.

  A host that inserts ads serves another file to each request, so the
  duration is that of the file that came.

  The readers are strict, and their namespaces name their quirks. The
  :except option leaves out the quirks that it names."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.format.media.mp3 :as mp3]
            [dk.simongray.podcast.format.media.mp4 :as mp4]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.wary-fetch.async :as async]))

(defn- sniff
  "The format of the file whose start `view` holds, or nil: :mp3 for an ID3
  tag or an MPEG audio frame, and :mp4 for an ftyp box."
  [view]
  (view/need view 0 12)
  (cond
    (= "ID3" (view/code view 0 3))  :mp3
    (= "ftyp" (view/code view 4 4)) :mp4
    (mp3/frame-header view 0)       :mp3))

(defn- mp3-result
  "What the MP3 file in `view` says about itself, read with `opts`."
  [view opts]
  (let [file     (mp3/read view opts)
        frames   (mapcat :frames (:tags file))
        duration (or (:duration file) (id3/duration frames))
        chs      (id3/chapters frames opts)
        artwork  (id3/artwork frames)]
    (view/reported
     (cond-> {:format :mp3}
       duration  (assoc :duration duration)
       (seq chs) (assoc :chapters {:version chapters/version :chapters chs})
       artwork   (assoc :artwork artwork))
     (into (mp3/report file) (id3/report chs)))))

(defn- mp4-result
  "What the MP4 file in `view` says about itself, read with `opts`."
  [view opts]
  (let [{:keys [chapters] :as file} (mp4/read view opts)
        document {:version chapters/version :chapters chapters}]
    (cond-> (assoc (dissoc file :chapters) :format :mp4)
      chapters (assoc :chapters document))))

(defn report
  "The diagnostics of the read `file`. Each diagnostic has its message."
  [file]
  (mapv diagnostics/describe (view/report file)))

(defn read
  "What the media file whose parts `view` holds says about itself, read
  with `opts`. It's a map of :format, :duration, :chapters and :artwork,
  and for an MP4 file the :codecs, :width, :height and :fast-start? that
  format.media.mp4/read gives, each absent when the file doesn't have it.

  - :format is :mp3 or :mp4
  - :duration is in seconds
  - :chapters is a chapters document of dk.simongray.podcast.format.chapters
  - :artwork is a map of :type and :data

  When the view lacks bytes, the result has the ranges that the reading
  needs under :need, as pairs of start and end, end excluded.

  The reading doesn't throw on the content of the file, and the report
  tells what it didn't read. The :except of `opts` is the ids of the quirks to
  leave out."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (let [unknown {:id :file/unknown-format :level :warning}]
     (view/reading #(case (sniff view)
                      :mp3 (mp3-result view opts)
                      :mp4 (mp4-result view opts)
                      (view/reported {} [unknown]))))))

(defn read!
  "Read what the media file at `url` says about itself, fetched with
  `opts`. On the JVM it gives the result, and in ClojureScript a promise
  of it.

  The `opts` are these:

  - :fetch, a function as http/fetcher makes, to fetch your own way
  - :headers, :timeout-seconds and :send, as http/fetcher takes them
  - :allow-pred and :user-agent, as http/send! takes them
  - :except, as read takes it
  - the limits of view/read!, with view/default-limits for those that
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
     (async/then (view/read-url! url #(read % read-opts) opts)
                 #(cond-> % (:error %) (update :error diagnostics/describe))))))

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
