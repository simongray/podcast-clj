(ns dk.simongray.podcast.feed.check
  "Checks over the network of what a feed links to: whether its media
  files, images, transcripts and chapters reach a listener as the feed
  says.

  The checks follow the standards first: HTTP as RFC 9110 has it, the
  attributes of RSS 2.0 and of the podcast namespace, and the CORS rules
  of the Fetch standard. The CORS rules decide what a web player on
  another site can read. The :requirements option adds those of a
  directory, e.g. :apple for Apple Podcasts.

  A check gathers the facts of each file over the network, and judges
  them by the rules of dk.simongray.podcast.feed.check.rules, which can
  judge the same facts again without a request.

  In a browser, fetch hides most headers of an answer from another site,
  and it fails without a reason where CORS doesn't allow the page to read.
  So a check sees most on the JVM or in Node."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.check.impl :as impl]
            [dk.simongray.podcast.feed.check.rules :as rules]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(def default-limits
  "The limits of a check when its options don't set them:

  - :max-items, how many of the newest items to check the files of
  - :max-files, the most files to check, since a feed can name thousands
  - :max-seconds, after which no file is started
  - :concurrency, how many files to check at a time
  - :timeout-seconds, the longest that a request can take
  - :max-bytes, the most to read of a file, except for its integrity"
  {:max-items       10
   :max-files       500
   :max-seconds     600
   :concurrency     4
   :timeout-seconds 30
   :max-bytes       (* 32 1024 1024)})

(defn files
  "The files that a check of `feed` fetches, each as a map, with the `opts`
  below.

  A file has its :kind, its :url, the :path in the feed of the element
  that names it, and what the feed :declared of it. The kinds are :feed,
  :media, :stream for the playlist of an HLS stream, :image, :transcript
  and :chapters. What the feed declares depends on the kind:

  - of a media file or a stream, the type, length and duration, and the
    integrity, height and codecs of an alternate enclosure
  - of an image, the :sources, such as :itunes, :podcast or :rss, and the
    purpose and size that its podcast:image gives
  - of a transcript or chapters, the type

  A file that several elements name is checked once, as the first element
  names it. The `opts` are:

  - :url, the URL of the feed, which is checked too
  - :max-items, how many of the newest items to check the files of, 10
    by default, or :all"
  ([feed]
   (files feed {}))
  ([feed opts]
   (let [{:keys [url max-items]} (merge default-limits opts)
         trailers                (map-indexed vector (:podcast/trailers feed))]
     (impl/once
      (concat (when (impl/checkable? url)
                [{:kind :feed :url url :path [] :declared {}}])
              (for [[j {:keys [url type length]}] trailers
                    :when (impl/checkable? url)]
                {:kind     :media
                 :url      url
                 :path     [:podcast/trailers j]
                 :declared (impl/present {:type type :length length})})
              (impl/image-files feed [])
              (mapcat impl/item-files (impl/newest feed max-items)))))))

(defn report
  "The diagnostics of the checked `files`, by the standards and the
  `chosen` requirements, if any.

  Each file has its :facts, and the `chosen` requirements are a name in
  rules/requirements, or a map of the shape of its entries. So several
  requirements can judge one check, and no request goes out again. The
  files that a limit of the check left out have no diagnostics of their
  own, and one :check/unchecked counts them for each limit."
  ([files]
   (report files nil))
  ([files chosen]
   (let [unchecked (frequencies (keep (comp :unchecked :facts) files))]
     (into (vec (mapcat #(rules/verdicts % (:facts %) chosen) files))
           (for [[limit n] unchecked]
             (diagnostics/describe {:id    :check/unchecked
                                    :level :warning
                                    :limit limit
                                    :count n}))))))

(defn check-files!
  "Check over the network the files that `feed` links to, with the `opts`
  below. In ClojureScript, the result is a promise.

  The result is a map of the :files and the :report. Each file has the
  :facts that its answers gave, and the report has the diagnostics of all
  the files. The `opts` are these, with default-limits for the limits:

  - :url, the URL of the feed, which is checked too
  - :credentials, of a private feed, as http/fetch! takes them
  - :max-items, how many of the newest items to check the files of, or :all
  - :requirements, a name in rules/requirements whose checks to add, or a
    map of the shape of its entries
  - :read?, whether to read what the files hold: the format, duration,
    codecs and size of media files, the playlists of HLS streams, and
    transcripts and chapters whole
  - :integrity?, whether to fetch whole each file with a podcast:integrity
  - :max-files, :max-seconds, :concurrency, :timeout-seconds and
    :max-bytes, the limits
  - :allow-pred, which URLs a request can go to, as for http/send!, and
    url/public? by default
  - :user-agent, as http/send! takes it
  - :send, how to send the requests, in place of client/send!
  - :now-fn, a function that gives the time, the clock by default

  The credentials go only with the requests for the feed, and so do a
  user name and password in its URL. The result names the URL without
  them.

  Unless `opts` ask it to read, a check moves two bytes of each file and
  the header of each image. It reads a media file by byte ranges, and only
  as far as its duration.

  Each request has the :timeout-seconds, the :max-bytes and the :allow-pred.

  For the integrity of a file, a check fetches the whole file, with the
  :allow-pred and the time that the check has left. Without a :send on the
  JVM, the file streams through the digest and none of it is kept.

  A file that a limit left out has the limit under :unchecked as its
  facts, and the report counts such files with :check/unchecked."
  ([feed]
   (check-files! feed {}))
  ([feed opts]
   (let [own-send     (:send opts)
         {:keys [url credentials requirements concurrency send max-files
                 max-seconds now-fn]
          :as   opts}
         (merge default-limits {:send client/send! :now-fn time/now} opts)

         [url in-url] (url/split-credentials url)
         auth         (http/authorization (or credentials in-url))
         deadline     (when max-seconds
                        (+ (inst-ms (now-fn)) (* 1000 max-seconds)))
         opts         (cond-> (assoc opts :url url)
                        own-send (assoc ::impl/own-send own-send)
                        auth     (assoc ::impl/authorization auth)
                        deadline (assoc ::impl/deadline deadline))
         found        (files feed opts)
         [near far]   (if max-files (split-at max-files found) [found nil])
         send-limited #(send (http/with-options % opts))]
     (async/then (impl/gather! send-limited near opts (max 1 concurrency))
                 (fn [facts]
                   (let [probed  (mapv #(assoc %1 :facts %2) near facts)
                         skipped (map #(assoc % :facts {:unchecked :max-files})
                                      far)
                         checked (into probed skipped)]
                     {:files  checked
                      :report (report checked requirements)}))))))

(comment
  (def feed
    {:rss/title "A show"
     :rss/items [{:rss/title           "Episode 1"
                  :rss/pub-date        #inst "2026-09-01"
                  :rss/enclosure       {:url    "https://example.org/1.mp3"
                                        :type   "audio/mpeg"
                                        :length 1000}
                  :podcast/transcripts [{:url  "https://example.org/1.vtt"
                                         :type "text/vtt"}]}]})

  (files feed {:url "https://example.org/feed.xml"})

  ;; the files that a limit left out, counted once for the limit
  (map :id (report [{:kind  :media
                     :url   "https://example.org/1.mp3"
                     :path  [:rss/items 0 :rss/enclosure]
                     :facts {:unchecked :max-files}}]))

  ;; a check that sends no request: the app sends in its own way
  (check-files! feed {:send (fn [request]
                              {:status 404 :url (:url request) :headers {}})})
  #_.)
