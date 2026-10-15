(ns dk.simongray.podcast.format.media.mp4
  "MP4 and M4A files, whose boxes ISO/IEC 14496-12 defines.

  A reading gives the duration from the movie header, the chapters, the
  artwork, the codecs and size of the tracks that play, and whether the
  movie comes first. The chapters are those of a QuickTime chapter track,
  or else those of a Nero chpl box. The titles of a chapter track can have
  a link each, and a track of images can name an image for each chapter,
  as in the enhanced podcasts of GarageBand. The artwork is the covr item
  of the iTunes metadata.

  The movie box can come before the media data or after it, so the
  reading walks the boxes at the top level. A reader that has only the
  start of the file learns where to look next.

  A file written in fragments gives the duration of all its fragments in
  its mehd box. Without that box, the reading takes the end of the last
  fragment that the mfra index at the end of the file lists.

  The reading is strict. The quirk :mp4/zero-duration takes the duration
  of the longest track when the movie header gives none. The :except
  option leaves it out."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.format.media.mp4.impl :as impl]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn report
  "The diagnostics of the read `file`. Each diagnostic has its message."
  [file]
  (view/report file))

(defn read
  "The MP4 file in `view`, read with `opts`, as a map of the keys below,
  each absent when the file doesn't have it.

  - :duration is in seconds, that of the tracks that play when a chapter
    track runs past them
  - :chapters, each with :start-time, :end-time and :title, and with :url
    and :artwork, a map of :type and :data, when the file has them
  - :artwork, a map of :type and :data
  - :codecs, the RFC 6381 codecs string of the tracks that play, e.g.
    \"avc1.64001F,mp4a.40.2\"
  - :width and :height of the video, in pixels
  - :channels and :sample-rate of the audio
  - :tags, the :title, :artist, :album, :date, :genre and :description of
    the iTunes metadata
  - :movie-first?, whether the movie comes before the media data, so that
    a player can start before it has the end of the file
  - :media-start, where the media data starts, and :bitrate, in bit/s,
    that of the media data over the duration, when the bytes at hand reach
    it, unless the file is in fragments

  The `opts` are these:

  - :except, the ids of the quirks to leave out
  - :chapter-images?, false to leave out the images of the chapters, which
    lie in the media data, so that the reading needs fewer bytes

  When the view lacks bytes, the reading throws an exception that holds
  their ranges.

  The chapters, their images, the artwork, the tags and the index of the
  fragments are parts that the rest can do without. A part that doesn't
  read is left out, and the report names it."
  ([view]
   (read view {}))
  ([view {:keys [chapter-images?] :or {chapter-images? true} :as opts}]
   {:pre [(map? opts)]}
   (if-let [moov (impl/movie view)]
     (let [{:keys [content end] :as data} (impl/media-data view)
           {:keys [duration] :as file}    (impl/read-movie view
                                                           moov
                                                           (view/context opts)
                                                           chapter-images?)
           ;; a file in fragments has its media in many mdat boxes
           whole?                         (nil? (impl/child view moov "mvex"))]
       (cond-> file
         data
         (assoc :media-start content)

         (and data whole? (number? duration) (pos? duration))
         (assoc :bitrate (long (/ (* 8 (- end content)) duration)))))
     (view/reported {} [(view/diagnostic :mp4/no-movie :warning)]))))

(comment
  ;; a movie of 90 seconds: an ftyp box, then a moov box with its mvhd box
  (def sample-bytes
    (let [be4  (fn [n] (map #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))
          code (fn [s] (seq (bytes/encode s)))
          box  (fn [type content]
                 (concat (be4 (+ 8 (count content))) (code type) content))
          mvhd (box "mvhd" (concat (be4 0) (be4 0) (be4 0)
                                   (be4 1000) (be4 90000)
                                   (repeat 80 0)))]
      (bytes/of (concat (box "ftyp" (concat (code "M4A ") (be4 0)))
                        (box "moov" mvhd)))))

  (def sample
    (view/add (view/view) 0 sample-bytes (bytes/length sample-bytes)))

  (impl/children sample 0 (:total sample))
  (read sample)
  (report (read sample))

  ;; the first 40 bytes alone: the reading tells the range that it needs
  (let [start (bytes/slice sample-bytes 0 40)]
    (view/reading #(read (view/add (view/view) 0 start nil))))
  #_.)
