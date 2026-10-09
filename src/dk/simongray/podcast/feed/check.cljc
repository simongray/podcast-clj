(ns dk.simongray.podcast.feed.check
  "Checks over the network of what a feed links to: whether its media
  files, images, transcripts and chapters reach a listener as the feed
  says.

  The function files lists what a check fetches, and check-files! fetches
  it, with the facts about each file and the diagnostics that they call
  for.

  The checks follow the standards first: HTTP as RFC 9110 has it, the
  attributes of RSS 2.0 and of the podcast namespace, and the CORS rules
  of the Fetch standard. The CORS rules decide what a web player on
  another site can read. The :requirements option adds those of a
  directory, as in dk.simongray.podcast.feed.validate, e.g. :apple for
  Apple Podcasts.

  A check moves as few bytes as it can. It moves two bytes of each file,
  from which dk.simongray.podcast.util.http/probe! learns the facts of the
  file, and the header of each image. When the options ask it to read, it
  reads what the media reader needs of each media file, and each
  transcript and chapters file whole.

  In a browser, fetch hides most headers of an answer from another site,
  and it fails without a reason where CORS doesn't allow the page to read.
  So a check sees most on the JVM or in Node."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.integrity :as integrity]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.url :as url]))

(def ^:no-doc overlays
  "The top-level domains of overlay networks, whose hosts only their own
  clients reach: that of Tor, which RFC 7686 sets aside, and those of I2P
  and Lokinet."
  #{"onion" "i2p" "loki"})

(defn ^:no-doc checkable?
  "Whether a check fetches `url`: an http or https URL of a host that any
  client can reach, not one of an overlay network."
  [url]
  (and (url/web-url? url)
       (let [tld (peek (str/split (url/host url) #"\."))]
         (not (contains? overlays tld)))))

(defn ^:no-doc present
  "The entries of the map `m` whose values aren't nil."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn- newest
  "The `n` newest items of `feed`, or all of them when `n` is :all, each
  with its index in the feed."
  [feed n]
  (let [indexed (map-indexed vector (:rss/items feed))
        order   (fn [[_ item]]
                  (- (or (some-> (resolve/published item) inst-ms) 0)))]
    (cond->> (sort-by order indexed)
      (not= :all n) (take n))))

(defn ^:no-doc image-files
  "The images of `x`, the channel or the item at `path`."
  [x path]
  (for [{:keys [href source] :as image} (resolve/images x)
        :when (checkable? href)]
    {:kind     :image
     :url      href
     :path     path
     :declared (-> (select-keys image [:purpose :width :height :aspect-ratio])
                   (assoc :sources #{source}))}))

(defn- alternate-files
  "The media files of the alternate enclosures of `item`, under `path`.

  A source with a type of its own, such as a torrent, isn't the media file,
  so the length and integrity of its enclosure aren't its own."
  [item path]
  (let [alternates (map-indexed vector (:podcast/alternate-enclosures item))]
    (for [[j {:keys [sources type length integrity]}] alternates
          {:keys [uri content-type]}                 sources
          :when (checkable? uri)]
      {:kind     :media
       :url      uri
       :path     (conj path :podcast/alternate-enclosures j)
       :declared (if content-type
                   {:type content-type}
                   (present {:type      type
                             :length    length
                             :integrity integrity}))})))

(defn- item-files
  "The files of the `item` at the index `i` of the feed."
  [[i item]]
  (let [path      [:rss/items i]
        enclosure (:rss/enclosure item)
        chapters  (:podcast/chapters item)]
    (concat
     (when (checkable? (:url enclosure))
       [{:kind     :media
         :url      (:url enclosure)
         :path     (conj path :rss/enclosure)
         :declared (present {:type     (:type enclosure)
                             :length   (:length enclosure)
                             :duration (resolve/duration item)})}])
     (alternate-files item path)
     (for [[j {:keys [url type]}] (map-indexed vector
                                               (:podcast/transcripts item))
           :when (checkable? url)]
       {:kind     :transcript
        :url      url
        :path     (conj path :podcast/transcripts j)
        :declared (present {:type type})})
     (when (checkable? (:url chapters))
       [{:kind     :chapters
         :url      (:url chapters)
         :path     (conj path :podcast/chapters)
         :declared (present {:type (:type chapters)})}])
     (image-files item path))))

(defn- once
  "The `files` with each URL once, as the first names it.

  An image that several elements name has the :sources of all of them, so
  that the rules of each apply."
  [files]
  (let [add (fn [{:keys [index kept] :as acc} {:keys [url kind] :as file}]
              (if-let [i (index url)]
                (cond-> acc
                  (= :image kind (:kind (kept i)))
                  (update-in [:kept i :declared :sources]
                             into
                             (get-in file [:declared :sources])))
                {:index (assoc index url (count kept))
                 :kept  (conj kept file)}))]
    (:kept (reduce add {:index {} :kept []} files))))

(def default-limits
  "The limits of check-files! when its options don't set them:

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
  :media, :image, :transcript and :chapters. What the feed declares
  depends on the kind:

  - of a media file, the type, length and duration, and the integrity of
    an alternate enclosure
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
     (once (concat (when (checkable? url)
                     [{:kind :feed :url url :path [] :declared {}}])
                   (for [[j {:keys [url type length]}] trailers
                         :when (checkable? url)]
                     {:kind     :media
                      :url      url
                      :path     [:podcast/trailers j]
                      :declared (present {:type type :length length})})
                   (image-files feed [])
                   (mapcat item-files (newest feed max-items)))))))

(def ^:no-doc origin
  "The origin that a check names in its requests for the files that pages
  of other sites read, to learn whether a server lets them. It's a name
  that no site can have, by RFC 6761."
  "https://check.invalid")

(defn ^:no-doc ok?
  "Whether the `answer` has a status of success."
  [{:keys [status] :as answer}]
  (and (integer? status)
       (<= 200 status)
       (< status 300)))

(defn ^:no-doc attempted
  "The value of the `attempt` of a reading, or a map with a diagnostic as
  the :error for an attempt that failed."
  [{:keys [value error] :as attempt}]
  (if error
    {:error (view/unreachable error)}
    value))

(defn ^:no-doc why
  "The reason of the :error in the `facts`, if any."
  [facts]
  (get-in facts [:error :reason]))

(defn ^:no-doc read-facts
  "The facts of what a reader gave: the value of the `attempt` with the
  diagnostics that `report-fn` reads from it, as data, or its :error.

  The facts of a check are data that an app keeps, so they hold the
  report."
  [attempt report-fn]
  (let [{:keys [error] :as value} (attempted attempt)]
    (cond-> value
      (not error) (assoc :report (report-fn value)))))

(defn ^:no-doc head!
  "Send a HEAD request for `url` with `headers` through `send`, and give
  the facts of the answer, in a future or a promise."
  [send url headers]
  (let [request {:method :head :url url :headers headers}]
    (async/then (http/try-send! send request) http/sent-facts)))

(defn ^:no-doc probe-url!
  "Probe `url` with `headers` through `send` as http/probe! does, and give
  the facts, in a future or a promise. They tell all that a check needs
  of most files, from two bytes of each."
  [send url headers]
  (http/probe! url {:send send :headers headers}))

(def ^:no-doc formats
  "The format that the media reader finds in a file of each declared type.
  The reader knows no other."
  {"audio/mpeg"      :mp3
   "audio/mp4"       :mp4
   "audio/x-m4a"     :mp4
   "audio/m4a"       :mp4
   "video/mp4"       :mp4
   "video/x-m4v"     :mp4
   "video/quicktime" :mp4})

(defn- time-left
  "The seconds that the check with `opts` has left, one at least, or nil
  when the check has no time limit."
  [{:keys [now-fn] ::keys [deadline] :as opts}]
  (when deadline
    (max 1 (quot (- deadline (inst-ms (now-fn))) 1000))))

(defn ^:no-doc reading-opts
  "The options of a reading by byte ranges in the check with `opts`: the
  limits of the check that apply to a reading, and `send` to send with."
  [send opts]
  (assoc (select-keys opts [:timeout-seconds :max-bytes :now-fn]) :send send))

(defn- whole-opts
  "The options of the fetch of a whole file for its integrity, in the
  check with `opts`.

  A whole file takes longer than a part of it, so the fetch takes the time
  that the check has left. It goes through the :send of the app, if any."
  [{::keys [own-send] :as opts}]
  (let [left (time-left opts)]
    (cond-> (select-keys opts [:allow-pred])
      left     (assoc :timeout-seconds left)
      own-send (assoc :send own-send))))

(defn- probe-media!
  "Probe the media `file` through `send` with `opts`, and give its facts,
  in a future or a promise.

  They're those that probe-url! gives, and as `opts` ask, what the file says
  about itself and whether it has its integrity."
  [send {:keys [url declared] :as file} {:keys [read? integrity?] :as opts}]
  (async/then
   (probe-url! send url {})
   (fn [{:keys [head get] :as facts}]
     (let [target     (or (:url get) (:url head) url)
           stated     (http/media-type (:type declared))
           came?      (contains? #{200 206} (:status get))
           readable?  (or (nil? stated) (contains? formats stated))
           read-job   (when (and read? came? readable?)
                        (async/attempt
                         #(media/read! target (reading-opts send opts))))
           verify-job (when (and integrity? came? (:integrity declared))
                        (async/attempt
                         #(integrity/verify! target (:integrity declared)
                                             (whole-opts opts))))]
       (async/then
        (async/all [read-job verify-job])
        (fn [[read verified]]
          (cond-> facts
            read
            (assoc :media (-> (read-facts read media/report)
                              (select-keys [:format :duration :report :error])))

            verified
            (assoc :integrity (attempted verified)))))))))

(defn- probe-image!
  "Probe the image `file` through `send` with `opts`, and give its facts,
  in a future or a promise. They're the answer to a HEAD request, and the
  header of the image, read by range."
  [send {:keys [url] :as file} opts]
  (async/then (head! send url {})
              (fn [head]
                (let [target (if (ok? head) (:url head url) url)
                      read!  #(image/read! target (reading-opts send opts))]
                  (async/then (async/attempt read!)
                              (fn [read]
                                {:head  head
                                 :image (read-facts read image/report)}))))))

(defn- reading
  "What the transcript or chapters file of `kind` holds, read from its
  `body` as its declared `type`.

  It's a map of the :count of segments or chapters and the :report of the
  reader, or of a diagnostic as the :error when the reader fails."
  [kind body type]
  (try
    (if (= :transcript kind)
      (let [t (transcript/parse body type)]
        {:count (count (:segments t)) :report (transcript/report t)})
      (let [doc (chapters/parse-json body)]
        {:count (count (:chapters doc)) :report (chapters/report doc)}))
    (catch #?(:clj Exception :cljs :default) e
      {:error (diagnostics/describe (view/failure e))})))

(defn- probe-text!
  "Probe the transcript or chapters `file` through `send` with `opts`, and
  give its facts, in a future or a promise.

  They're those that probe-url! gives. When `opts` ask to :read? the file,
  they're the answers to a HEAD request and to a GET of the whole file,
  one after the other as in a probe, and what the file holds. The
  requests name the origin of the check, since web players read these
  files."
  [send {:keys [kind url declared] :as file} {:keys [read?] :as opts}]
  (let [headers {"Origin" origin}]
    (if-not read?
      (probe-url! send url headers)
      (async/then
       (head! send url headers)
       (fn [head]
         (async/then
          (http/try-send! send {:url url :headers headers})
          (fn [attempt]
            (let [got  (http/sent-facts attempt)
                  body (or (get-in attempt [:value :body]) "")]
              (cond-> {:head head :get got}
                (ok? got)
                (assoc :read (reading kind body (:type declared))))))))))))

(defn- probe-feed!
  "Probe the feed at the URL of `file` through `send` with `opts`, and give
  its facts as probe-url! does, in a future or a promise.

  The requests name the origin of the check, and have the ::authorization
  of `opts` for a private feed. The caller has the feed, so none of it is
  fetched again."
  [send {:keys [url] :as file} opts]
  (let [auth    (::authorization opts)
        headers (cond-> {"Origin" origin}
                  auth (assoc "Authorization" auth))]
    (probe-url! send url headers)))

(def ^:no-doc probes
  "The function that gets the facts of a file, by the kind of the file."
  {:media      probe-media!
   :image      probe-image!
   :transcript probe-text!
   :chapters   probe-text!
   :feed       probe-feed!})

(defn- probe!
  "Probe `file` by its kind through `send` with `opts`, and give its facts,
  in a future or a promise. On the JVM, the probe runs on a thread of its
  own."
  [send file opts]
  (async/then (async/attempt #((probes (:kind file)) send file opts))
              attempted))

(defn- gathered
  "The facts of `files` in their order, from what the `lanes` of in-lanes
  gave, with the files still in the `queue` :unchecked."
  [files queue lanes]
  (let [by-index (into {} (mapcat :value) lanes)
        waiting  (into {}
                       (map (fn [[i _]] [i {:unchecked :max-seconds}]))
                       queue)
        missed   (ex-info "The file was not checked." {})]
    (mapv #(or (by-index %)
               (waiting %)
               (attempted {:error missed}))
          (range (count files)))))

(defn- in-lanes
  "The facts of `files` in their order, sent with `send` and `opts` from
  `n` lanes, in a future or a promise.

  Each lane probes the next file when its last one is done, so that at
  most `n` files are under way and no slow file holds up the others. On
  the JVM, a lane is a loop on a thread of its own, since a probe there
  gives its value at once. In ClojureScript, it's a chain of promises.

  A lane takes no file after the ::deadline of `opts`, and a file that no
  lane took is :unchecked."
  [send files {:keys [now-fn] ::keys [deadline] :as opts} n]
  (let [queue (atom (map-indexed vector files))
        next! (fn []
                (when (or (nil? deadline) (< (inst-ms (now-fn)) deadline))
                  (first (first (swap-vals! queue rest)))))
        lane  #?(:clj  (fn [_]
                         (loop [done []]
                           (if-let [[i file] (next!)]
                             (recur (conj done [i (probe! send file opts)]))
                             done)))
                 :cljs (fn lane [done]
                         (if-let [[i file] (next!)]
                           (async/then (probe! send file opts)
                                       #(lane (conj done [i %])))
                           done)))]
    (async/then (async/all (repeatedly n #(async/attempt (fn [] (lane [])))))
                #(gathered files @queue %))))

(def requirements
  "The requirements that a check adds for a standard or a directory, by
  the names that dk.simongray.podcast.feed.validate/requirements uses.

  Each gives the size, type, colors and file extension of the artwork of
  an itunes:image, and the levels that it raises above those of the
  catalogue. They're the requirements of PSP-1, and of Apple's A
  Podcaster's Guide to RSS, as of September 2026. The functions verdicts,
  report and check-files! take them by their name here, or as a map of
  this shape."
  (let [artwork {:sizes      [1400 3000]
                 :types      #{"image/jpeg" "image/png"}
                 :colors     #{:rgb :indexed}
                 :extension? true}]
    {:psp-1 {:name    "Podcast Standards Project PSP-1"
             :artwork artwork}
     :apple {:name    "Apple Podcasts"
             :artwork (assoc artwork :alpha? false :last-modified? true)
             :levels  {:check/ranges :error}}}))

(defn ^:no-doc diagnostic
  "The diagnostic `id` about `file`, with the `details`, at the level of
  the catalogue unless the `chosen` requirements raise it."
  [{:keys [url path] :as file} chosen id & {:as details}]
  (merge {:id    id
          :level (or (get-in chosen [:levels id])
                     (:level (diagnostics/by-id id)))
          :url   url
          :path  path}
         (present details)))

(defn ^:no-doc reaching
  "The diagnostics of how the `answer` to a request for `file` reached it,
  or failed to, by the `chosen` requirements.

  It's nil when the answer came without a detour worth telling. A
  redirect that the client didn't follow is a downgrade when it leaves
  https for http, one too many after five, and otherwise its status."
  [file chosen {:keys    [error status location redirects permanent-url]
                answered :url
                :as      answer}]
  (let [redirect? (and (integer? status)
                       (= 3 (quot status 100)))]
    (cond
      error
      [(diagnostic file chosen :check/unreachable :reason (why answer))]

      (and redirect?
           location
           (str/starts-with? (str/lower-case (str answered)) "https:")
           (str/starts-with? (str/lower-case location) "http:"))
      [(diagnostic file chosen :check/downgrade :location location)]

      (and redirect?
           (<= 5 (count redirects)))
      [(diagnostic file chosen :check/redirects :location location)]

      (not (ok? answer))
      [(diagnostic file chosen :check/status :status status)]

      permanent-url
      [(diagnostic file chosen :check/moved :moved-to permanent-url)])))

(defn ^:no-doc head-verdict
  "The diagnostic of the answer `head` to a HEAD request for `file`, by
  the `chosen` requirements, when the request didn't succeed."
  [file chosen head]
  (when (and head (not (ok? head)))
    [(diagnostic file chosen :check/head
                 :status (:status head)
                 :reason (why head))]))

(defn ^:no-doc passed-on
  "The diagnostics in the `report` of a reader of `file` that warn of more
  than a detail."
  [file report]
  (for [d report
        :when (#{:warning :error} (:level d))]
    (merge d (select-keys file [:url :path]))))

(defn ^:no-doc type-verdict
  "The diagnostic of the media type `served` for `file`, by the `chosen`
  requirements, when it differs from the one that the feed declares."
  [file chosen served]
  (let [declared (http/media-type (get-in file [:declared :type]))]
    (when (and declared
               served
               (not= declared served))
      [(diagnostic file chosen :check/type
                   :declared declared
                   :served   served)])))

(defn- as-is?
  "Whether the `answer` sends the file as it is, not in a content coding
  such as gzip, which changes its length."
  [answer]
  (let [coding (some-> (:encoding answer) str/trim str/lower-case)]
    (contains? #{nil "identity"} coding)))

(defn- length-verdict
  "The diagnostic of a media `file` whose size, as the answers `get` and
  `head` give it, differs from the length that the feed declares, by
  the `chosen` requirements.

  A length of 0 says that the feed doesn't know the length, as the RSS
  Best Practices Profile, section 4.1.1.20.5, has it."
  [{:keys [declared] :as file} chosen get head]
  (let [length (:length declared)
        served (or (get-in get [:range :total])
                   (when (and (= 200 (:status get)) (as-is? get))
                     (:length get))
                   (when (as-is? head)
                     (:length head)))]
    (when (and (number? length)
               (pos? length)
               (number? served)
               (not= length served))
      [(diagnostic file chosen :check/length
                   :declared length
                   :served   served)])))

(defn- ranged?
  "Whether the `answer` to a request for the first two bytes of a file is
  those bytes alone: a 206 from the start of the file.

  A browser hides the Content-Range of an answer from another site unless
  the server exposes it, so there a 206 alone counts."
  [{:keys [status range filtered?] :as answer}]
  (and (= 206 status)
       (or (= 0 (:start range))
           (and filtered? (nil? range)))))

(defn- ranges-verdict
  "The diagnostic of an audio or video `file` whose server doesn't answer
  a request for a byte range, `get`, with that range, by the `chosen`
  requirements."
  [{:keys [declared] :as file} chosen get]
  (let [type (or (http/media-type (:type declared)) (:type get) "")]
    (when (and (re-find #"^(?:audio|video)/" type)
               (not (ranged? get)))
      [(diagnostic file chosen :check/ranges :status (:status get))])))

(defn- media-reading-verdicts
  "The diagnostics of what reading the media `file` found, `media`, against
  what the feed declares, by the `chosen` requirements."
  [{:keys [declared] :as file} chosen media]
  (let [expected (formats (http/media-type (:type declared)))
        found    (:format media)
        stated   (:duration declared)
        measured (:duration media)
        report   (:report media)
        unknown? #(= :file/unknown-format (:id %))]
    (cond
      (nil? media)
      nil

      (:error media)
      [(diagnostic file chosen :check/unread :reason (why media))]

      :else
      (concat
       (when (and expected
                  (if found
                    (not= expected found)
                    (some unknown? report)))
         [(diagnostic file chosen :check/format
                      :declared (:type declared)
                      :format   found)])
       (when (and (number? stated)
                  (number? measured)
                  (> (abs (- stated measured))
                     (max 10 (* 0.02 stated))))
         [(diagnostic file chosen :check/duration
                      :declared stated
                      :measured measured)])
       ;; a format that the reader does not know is :check/format already
       (passed-on file (remove unknown? report))))))

(defn- integrity-verdict
  "The diagnostic of the check of the media `file` against its
  podcast:integrity, `integrity`, by the `chosen` requirements. It's for a
  file that doesn't match it, or one that couldn't be checked."
  [file chosen {:keys [valid? algorithm] :as integrity}]
  (cond
    (nil? integrity)
    nil

    (false? valid?)
    [(diagnostic file chosen :check/integrity :algorithm algorithm)]

    (not valid?)
    [(diagnostic file chosen :check/unverified :reason (why integrity))]))

(defn- media-verdicts
  "The diagnostics of the media `file` from its `facts`, by
  the `chosen` requirements."
  [file chosen {:keys [head get media integrity] :as facts}]
  (if-not (ok? get)
    (reaching file chosen get)
    (let [head' (when (ok? head) head)]
      (concat
       (reaching file chosen get)
       (head-verdict file chosen head)
       (type-verdict file chosen (or (:type get) (:type head')))
       (length-verdict file chosen get head')
       (ranges-verdict file chosen get)
       (media-reading-verdicts file chosen media)
       (integrity-verdict file chosen integrity)))))

(defn- ratio
  "The aspect ratio that the podcast:image attribute `s` gives, such as 1/1
  or 1.91, as a number, or nil when it doesn't read."
  [s]
  ;; trimmed first: two runs of white space that one run can feed make a
  ;; pattern try every split of it
  (when-let [[_ a b] (re-matches
                      #"(\d+(?:\.\d+)?)(?:\s*[/:]\s*(\d+(?:\.\d+)?))?"
                      (str/trim (str s)))]
    (let [a (parse-double a)
          b (if b (parse-double b) 1.0)]
      (when (pos? b) (/ a b)))))

(defn- shown
  "The header `image` with the width and height of the image as a viewer
  shows it, swapped when its EXIF orientation turns it a quarter."
  [{:keys [width height orientation] :as image}]
  (cond-> image
    (contains? #{5 6 7 8} orientation) (assoc :width height :height width)))

(defn- size-verdict
  "The diagnostic of an image `file` whose size in pixels, from the header
  `image`, differs from what its podcast:image declares, by
  the `chosen` requirements.

  The podcast namespace takes a podcast:image without a size as square.
  But the model gives the images of the deprecated podcast:images as
  podcast:image elements too, and those make no such claim, so only a
  declared size counts."
  [{:keys [declared] :as file} chosen {:keys [width height] :as image}]
  (let [{w :width h :height aspect :aspect-ratio} declared
        stated (or (ratio aspect)
                   (when (and (number? w)
                              (number? h)
                              (pos? h))
                     (/ w (double h))))
        actual (when (and (number? width)
                          (number? height)
                          (pos? height))
                 (/ width (double height)))]
    (when (and actual
               (or (and (number? w) (not= w width))
                   (and (number? h) (not= h height))
                   (and stated
                        (> (abs (- stated actual)) (* 0.01 stated)))))
      [(diagnostic file chosen :check/image-size
                   :declared (select-keys declared
                                          [:width :height :aspect-ratio])
                   :actual   {:width width :height height})])))

(defn- extension
  "The file extension of the path of `url`, in lower case, or nil when it
  has none. The path is what follows the host, up to a query, which can
  hold another URL."
  [url]
  (some->> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*([^?#]*)" (str url))
           second
           (re-find #"\.([A-Za-z0-9]{1,5})$")
           second
           str/lower-case))

(defn- decodable?
  "Whether apps and browsers decode the image with the header `image`.

  They decode any image but a JPEG that libjpeg and browsers don't decode.
  That's one with more than 8 bits a sample, or in a coding process other
  than baseline, extended and progressive."
  [{:keys [type process bits] :as image}]
  (or (not= "image/jpeg" type)
      (nil? process)
      (and (contains? #{:baseline :extended :progressive} process)
           (= 8 bits))))

(def ^:no-doc extension-types
  "The MIME type of the image that each file extension of artwork names."
  {"jpg" "image/jpeg" "jpeg" "image/jpeg" "png" "image/png"})

(defn- artwork-verdicts
  "The diagnostics of the image `file`, an itunes:image, by the artwork of
  the `chosen` requirements. They come from the answer `head` to a HEAD
  request and the header `image` as it's shown.

  The type of the image is the one that its bytes give, or else the one
  that the server gives."
  [{:keys [url] :as file} chosen head {:keys [width height color] :as image}]
  (let [{:keys [sizes types colors extension? alpha? last-modified?]}
        (:artwork chosen)

        [least most] sizes
        type         (or (:type image) (when (ok? head) (:type head)))
        ext          (extension url)]
    (concat
     (when (and sizes
                width
                (not (and height
                          (= width height)
                          (<= least width)
                          (<= width most))))
       [(diagnostic file chosen :check/artwork-size
                    :width  width
                    :height height)])
     (when (and types
                type
                (not (and (types type) (decodable? image))))
       [(diagnostic file chosen :check/artwork-type
                    :type    type
                    :process (:process image)
                    :bits    (:bits image))])
     (when (and colors
                color
                (not (colors color)))
       [(diagnostic file chosen :check/artwork-color :color color)])
     (when (and (false? alpha?) (:alpha? image))
       [(diagnostic file chosen :check/artwork-alpha)])
     (when (and extension?
                ext
                type
                (not= type (extension-types ext)))
       [(diagnostic file chosen :check/artwork-extension
                    :extension ext
                    :type      type)])
     (when (and last-modified?
                (ok? head)
                (not (:last-modified head)))
       [(diagnostic file chosen :check/last-modified)]))))

(defn- image-verdicts
  "The diagnostics of the image `file` from its `facts`, by
  the `chosen` requirements."
  [{{:keys [sources]} :declared :as file} chosen {:keys [head image] :as facts}]
  (cond
    (and (:error image) (not (ok? head)))
    (reaching file chosen head)

    (:error image)
    [(diagnostic file chosen :check/unread :reason (why image))]

    :else
    (let [image (shown image)]
      (concat
       (when (ok? head)
         (reaching file chosen head))
       (head-verdict file chosen head)
       (passed-on file (:report image))
       (when (and (ok? head)
                  (:type head)
                  (:type image)
                  (not= (:type head) (:type image)))
         [(diagnostic file chosen :check/image-type
                      :served (:type head)
                      :actual (:type image))])
       (when (contains? sources :podcast)
         (size-verdict file chosen image))
       (when (contains? sources :itunes)
         (artwork-verdicts file chosen head image))))))

(defn- barrier
  "The first answer on the way to the `answer` to a request for `url` that
  doesn't let a page of another site read on. It's nil when none stops it.

  The way is the redirects and the answer itself, and a browser applies
  the CORS check of the Fetch standard to each. An answer lets the page
  read when it allows any origin, or the origin of the check. No site has
  that origin, so a server that allows it allows whatever origin it gets.

  After a redirect to another origin, a browser names the origin null,
  which such a server allows too, and so does an answer that allows null
  there. In a browser, an answer that came is one that CORS let through."
  [url {:keys [redirects filtered?] :as answer}]
  (when-not filtered?
    (loop [[a & more] (conj (vec redirects) answer)
           tainted?   false]
      (when a
        (let [tainted? (or tainted?
                           (not (url/same-origin? url (:url a))))
              cors     (:cors a)]
          (if (or (= "*" cors)
                  (= origin cors)
                  (and tainted? (= "null" cors)))
            (recur more tainted?)
            a))))))

(defn ^:no-doc cors-verdict
  "The diagnostic `id` of `file`, by the `chosen` requirements, when a page
  of another site can't read the answer `get` to a request for `url`.

  The :allowed of the diagnostic names the origins that the answer that
  stops it allows, and the :redirect names the redirect that it is."
  [file chosen id url get]
  (when-let [a (barrier url get)]
    (let [redirect (when-not (identical? a get)
                     (:url a))]
      [(diagnostic file chosen id :allowed (:cors a) :redirect redirect)])))

(defn- text-verdicts
  "The diagnostics of the transcript or chapters `file` from its `facts`,
  by the `chosen` requirements."
  [{:keys [url] :as file} chosen {:keys [head get read] :as facts}]
  (if-not (ok? get)
    (reaching file chosen get)
    (concat
     (reaching file chosen get)
     (head-verdict file chosen head)
     (type-verdict file chosen (:type get))
     (cors-verdict file chosen :check/cors url get)
     (cond
       (:error read)
       [(diagnostic file chosen :check/unread :reason (why read))]

       (and read (zero? (:count read)))
       [(diagnostic file chosen :check/empty)])
     (passed-on file (:report read)))))

(defn- feed-verdicts
  "The diagnostics of the feed `file` from its `facts`, by
  the `chosen` requirements.

  A server needn't send the ETag and Last-Modified in a 206, so those of
  the answer to HEAD count too. A browser hides the ETag of an answer from
  another site."
  [{:keys [url] :as file} chosen {:keys [head get] :as facts}]
  (if-not (ok? get)
    (reaching file chosen get)
    (concat
     (reaching file chosen get)
     (head-verdict file chosen head)
     (cors-verdict file chosen :check/feed-cors url get)
     (when-not (or (:etag get)
                   (:last-modified get)
                   (:filtered? get)
                   (and (ok? head)
                        (or (:etag head) (:last-modified head))))
       [(diagnostic file chosen :check/validators)]))))

(def ^:no-doc judges
  "The function that gives the diagnostics of a file from its facts, by the
  kind of the file."
  {:media      media-verdicts
   :image      image-verdicts
   :transcript text-verdicts
   :chapters   text-verdicts
   :feed       feed-verdicts})

(defn verdicts
  "The diagnostics that the `facts` about `file` call for, by the
  standards and the `chosen` requirements, if any.

  The function files gives the file, and check-files! gives the facts.
  Each diagnostic has the :url of the file and the :path of the element
  that names it. The `chosen` requirements are a name in requirements, or
  a map of the shape of its entries."
  ([file facts]
   (verdicts file facts nil))
  ([{:keys [kind] :as file} facts chosen]
   (let [chosen (if (map? chosen) chosen (get requirements chosen))
         judge  (get judges kind (constantly []))
         found  (cond
                  (:unchecked facts)
                  []

                  (:error facts)
                  [(diagnostic file chosen :check/unreachable
                               :reason (why facts))]

                  :else
                  (judge file chosen facts))]
     (mapv diagnostics/describe found))))

(defn report
  "The diagnostics of the checked `files`, by the standards and the
  `chosen` requirements, if any.

  Each file has its :facts, as check-files! gives them, and the `chosen`
  requirements are what verdicts takes. So several requirements can judge
  one check, and no request goes out again. The files that a limit of the
  check left out have no diagnostics of their own, and one
  :check/unchecked counts them for each limit."
  ([files]
   (report files nil))
  ([files chosen]
   (let [unchecked (frequencies (keep (comp :unchecked :facts) files))]
     (into (vec (mapcat #(verdicts % (:facts %) chosen) files))
           (for [[limit n] unchecked]
             (diagnostics/describe {:id    :check/unchecked
                                    :level :warning
                                    :limit limit
                                    :count n}))))))

(defn check-files!
  "Check over the network the files that `feed` links to, as files lists
  them, with the `opts` below. In ClojureScript, the result is a promise.

  The result is a map of the :files and the :report. Each file has the
  :facts that its answers gave, and the report has the diagnostics of all
  the files, from report. The `opts` are these, with default-limits for
  the limits:

  - :url, the URL of the feed, which is checked too
  - :credentials, of a private feed, as http/fetch! takes them
  - :max-items, how many of the newest items to check the files of, or :all
  - :requirements, a name in requirements whose checks to add, or a map
    of the shape of its entries
  - :read?, whether to read what the files hold: the format and duration
    of media files, and transcripts and chapters whole
  - :integrity?, whether to fetch whole each file with a podcast:integrity
  - :max-files, :max-seconds, :concurrency, :timeout-seconds and
    :max-bytes, the limits
  - :allow-pred, which URLs a request can go to, as for http/send!
  - :send, how to send the requests, in place of http/send!
  - :now-fn, a function that gives the time, the clock by default

  The credentials go only with the requests for the feed, and so do a
  user name and password in its URL. The result names the URL without
  them.

  Unless `opts` ask it to read, a check moves two bytes of each file, as
  http/probe! does, and the header of each image. It reads a media file by
  byte ranges, and only as far as its duration. The byte ranges of media
  files and images come through http/fetcher.

  Each request has the :timeout-seconds, the :max-bytes and the :allow-pred.
  A server that checks feeds for strangers gives url/public? as :allow-pred.

  For the integrity of a file, a check fetches the whole file, as
  dk.simongray.podcast.format.integrity/verify! does. That request has the
  :allow-pred, and it takes the time that the check has left. It goes through
  the :send of `opts`, or without one, the file streams through
  http/digest! on the JVM.

  A file that a limit left out has the limit under :unchecked as its
  facts, and the report counts such files with :check/unchecked."
  ([feed]
   (check-files! feed {}))
  ([feed opts]
   (let [own-send     (:send opts)
         {:keys [url credentials requirements concurrency send max-files
                 max-seconds now-fn]
          :as   opts}
         (merge default-limits {:send http/send! :now-fn time/now} opts)

         [url in-url] (url/split-credentials url)
         auth         (http/authorization (or credentials in-url))
         deadline     (when max-seconds
                        (+ (inst-ms (now-fn)) (* 1000 max-seconds)))
         opts         (cond-> (assoc opts :url url)
                        own-send (assoc ::own-send own-send)
                        auth     (assoc ::authorization auth)
                        deadline (assoc ::deadline deadline))
         found        (files feed opts)
         [near far]   (if max-files (split-at max-files found) [found nil])
         send-limited #(send (http/limited % opts))]
     (async/then (in-lanes send-limited near opts (max 1 concurrency))
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

  ;; what the facts of a file call for, with no request
  (def media-file
    {:kind     :media
     :url      "https://example.org/1.mp3"
     :path     [:rss/items 0 :rss/enclosure]
     :declared {:type "audio/mpeg" :length 1000}})

  (def served
    {:head {:status 200 :type "audio/mpeg" :length 1200}
     :get  {:status 200 :type "audio/mpeg" :length 1200}})

  (map :id (verdicts media-file served))
  (map (juxt :id :level) (verdicts media-file served :apple))
  (map :id (report [(assoc media-file :facts {:unchecked :max-files})]))

  ;; a check that sends no request: the app sends in its own way
  (check-files! feed {:send (fn [request]
                              {:status 404 :url (:url request) :headers {}})})
  #_.)
