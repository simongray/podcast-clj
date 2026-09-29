(ns dk.simongray.podcast.check
  "Checks over the network of what a feed links to: whether its media
  files, images, transcripts and chapters reach a listener as the feed
  says.

  `files` lists what a check fetches. `feed!` fetches it, and gives the
  facts about each file with the diagnostics that they call for.

  The checks follow the standards first: HTTP as RFC 9110 has it, the
  attributes of RSS 2.0 and of the podcast namespace, and the CORS rules of
  the Fetch standard. The CORS rules decide what a web player on another
  site can read. A profile adds the rules of a directory, as
  `dk.simongray.podcast.feed.validate` does: `:apple` for Apple Podcasts.

  A check moves as few bytes as it can. It moves two bytes of each file,
  which `dk.simongray.podcast.http/probe!` learns the facts of a file
  from, and the header of each image. When the options ask it to read, it
  reads what the media reader needs of each media file, and each
  transcript and chapters file whole.

  In a browser, fetch hides most headers of an answer from another site.
  It also fails without a reason where CORS does not allow the page to
  read. So a check sees most on the JVM or in Node."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.resolve :as resolve]))

(def ^:no-doc overlays
  "The top-level domains of overlay networks, whose hosts only their own
  clients reach: Tor's, which RFC 7686 sets aside, I2P's and Lokinet's."
  #{"onion" "i2p" "loki"})

(defn ^:no-doc web-url?
  "Whether `url` is an http or https URL of a host that any client can
  reach, not one of an overlay network."
  [url]
  (and (http/web-url? url)
       (not (contains? overlays (peek (str/split (http/host url) #"\."))))))

(defn ^:no-doc present
  "The entries of the map `m` whose values are not nil."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn- newest
  "The `n` newest items of `feed`, all of them when `n` is :all, each with
  its index in the feed."
  [feed n]
  (cond->> (sort-by (fn [[_ item]] (- (or (some-> (resolve/published item) inst-ms) 0)))
                    (map-indexed vector (:rss/items feed)))
    (not= :all n) (take n)))

(defn ^:no-doc image-files
  "The images of `x`, the channel or the item at `path`."
  [x path]
  (for [{:keys [href source] :as image} (resolve/images x)
        :when (web-url? href)]
    {:kind :image :url href :path path
     :declared (assoc (select-keys image [:purpose :width :height :aspect-ratio]) :sources #{source})}))

(defn- alternate-files
  "The media files of the alternate enclosures of `item`, under `path`.

  A source with a type of its own, such as a torrent, is not the media
  file. So the length and the integrity of its enclosure are not its own."
  [item path]
  (for [[j {:keys [sources type length integrity]}] (map-indexed vector (:podcast/alternate-enclosures item))
        {:keys [uri content-type]} sources
        :when (web-url? uri)]
    {:kind :media :url uri :path (conj path :podcast/alternate-enclosures j)
     :declared (if content-type
                 {:type content-type}
                 (present {:type type :length length :integrity integrity}))}))

(defn- item-files
  "The files of the item `item` at the index `i` of the feed."
  [[i item]]
  (let [path                      [:rss/items i]
        {:keys [url type length]} (:rss/enclosure item)
        chapters                  (:podcast/chapters item)]
    (concat
     (when (web-url? url)
       [{:kind :media :url url :path (conj path :rss/enclosure)
         :declared (present {:type type :length length :duration (resolve/duration item)})}])
     (alternate-files item path)
     (for [[j {:keys [url type]}] (map-indexed vector (:podcast/transcripts item))
           :when (web-url? url)]
       {:kind :transcript :url url :path (conj path :podcast/transcripts j) :declared (present {:type type})})
     (when (web-url? (:url chapters))
       [{:kind :chapters :url (:url chapters) :path (conj path :podcast/chapters)
         :declared (present {:type (:type chapters)})}])
     (image-files item path))))

(defn- once
  "The `files` with each URL once, as the first names it; an image that
  several elements name has the `:sources` of all of them, so that the
  rules of each apply."
  [files]
  (:kept (reduce (fn [{:keys [at kept] :as acc} {:keys [url kind] :as file}]
                   (if-let [i (at url)]
                     (cond-> acc
                       (= :image kind (:kind (kept i)))
                       (update-in [:kept i :declared :sources] into (get-in file [:declared :sources])))
                     {:at (assoc at url (count kept)) :kept (conj kept file)}))
                 {:at {} :kept []}
                 files)))

(def default-limits
  "The limits of `feed!` that its options do not set.

  A check looks at the files of the `:items` newest items, `:max-files`
  files at most, and at `:concurrency` files at a time. It starts no file
  after `:max-seconds`. Each request takes `:timeout-seconds` at most. A
  check reads `:max-bytes` of a file at most, but for its integrity.

  A feed names the files of a check, so a feed can name thousands."
  {:items           10
   :max-files       500
   :max-seconds     600
   :concurrency     4
   :timeout-seconds 30
   :max-bytes       (* 32 1024 1024)})

(defn files
  "The files that a check of `feed` fetches, each as a map.

  A file has its `:kind`, its `:url`, the `:path` in the feed of the
  element that names it, and what the feed `:declared` of it. The kinds
  are :feed, :media, :image, :transcript and :chapters. What the feed
  declares depends on the kind:

  - of a media file: the type, the length and the duration, and the
    integrity of an alternate enclosure
  - of an image: the `:sources` (:itunes, :podcast, :rss and the like),
    and the purpose and the size that its podcast:image gives
  - of a transcript or of chapters: the type

  A file that several elements name is checked one time, as the first
  element names it. `opts` can give the `:url` of the feed, which is
  checked too. They can give `:items`, the number of the newest items
  whose files to check (default 10, or :all)."
  ([feed]
   (files feed {}))
  ([feed opts]
   (let [{:keys [url items]} (merge default-limits opts)]
     (once (concat (when (web-url? url)
                     [{:kind :feed :url url :path [] :declared {}}])
                   (for [[j {:keys [url type length]}] (map-indexed vector (:podcast/trailers feed))
                         :when (web-url? url)]
                     {:kind :media :url url :path [:podcast/trailers j] :declared (present {:type type :length length})})
                   (image-files feed [])
                   (mapcat item-files (newest feed items)))))))

(def ^:no-doc origin
  "The origin that a check names in its requests for the files that pages
  of other sites read, to learn whether a server lets them: a name that no
  site can have, by RFC 6761."
  "https://check.invalid")

(defn ^:no-doc ok?
  "Whether the `answer` has a status of success."
  [{:keys [status]}]
  (and (integer? status) (<= 200 status) (< status 300)))

(defn ^:no-doc attempted
  "The value of the `attempt` of a reading, or its `:error`."
  [{:keys [value error]}]
  (if error {:error (async/explain error)} value))

(defn ^:no-doc read-facts
  "The facts of what a reader gave: the value of the `attempt`, with the
  diagnostics that `report` reads from it as data, or its `:error`. The
  facts of a check are data that an app keeps, so they hold the report."
  [attempt report]
  (let [{:keys [error] :as value} (attempted attempt)]
    (cond-> value
      (not error) (assoc :report (report value)))))

(defn ^:no-doc head!
  "The facts of the answer to a HEAD request for `url` with `headers`;
  deferred."
  [send url headers]
  (async/then (http/sent! send {:method :head :url url :headers headers}) http/sent-facts))

(defn ^:no-doc probed!
  "The facts that `http/probe!` gives of `url`, asked with `headers`, which
  tell all that a check needs of most files, from two bytes of each;
  deferred."
  [send url headers]
  (http/probe! url {:send send :headers headers}))

(def ^:no-doc formats
  "The format that the media reader finds in a file of each declared type;
  the reader knows no other."
  {"audio/mpeg" :mp3 "audio/mp4" :mp4 "audio/x-m4a" :mp4 "audio/m4a" :mp4
   "video/mp4" :mp4 "video/x-m4v" :mp4 "video/quicktime" :mp4})

(defn- time-left
  "The seconds that the check with `opts` has left, one at least. Nil when
  the check has no limit of time."
  [{:keys [now] ::keys [deadline]}]
  (when deadline
    (max 1 (quot (- deadline (inst-ms (now))) 1000))))

(defn ^:no-doc reading-opts
  "The options of a reading by byte ranges in the check with `opts`: the
  limits of the check that a reading has too, and `send` to send with."
  [send opts]
  (assoc (select-keys opts [:timeout-seconds :max-bytes :now]) :send send))

(defn- whole-opts
  "The options of the fetch of a whole file for its integrity, in the
  check with `opts`. A whole file takes longer than a part of it, so the
  fetch takes the time that the check has left. It goes through the
  `:send` of the app when the app gave one."
  [{::keys [own-send] :as opts}]
  (let [left (time-left opts)]
    (cond-> (select-keys opts [:allow?])
      left     (assoc :timeout-seconds left)
      own-send (assoc :send own-send))))

(defn- probe-media!
  "The facts of the media file `file`, as `probed!` gives them, and, as
  `opts` ask, what the file says about itself and whether it has its
  integrity; deferred."
  [send {:keys [url declared]} {:keys [read? integrity?] :as opts}]
  (async/then
   (probed! send url {})
   (fn [{:keys [head get] :as facts}]
     (let [target (or (:url get) (:url head) url)
           stated (http/media-type (:type declared))
           came?  (contains? #{200 206} (:status get))]
       (async/then
        (async/all [(when (and read? came? (or (nil? stated) (contains? formats stated)))
                      (async/attempt #(media/read! target (reading-opts send opts))))
                    (when (and integrity? came? (:integrity declared))
                      (async/attempt #(media/verify! target (:integrity declared) (whole-opts opts))))])
        (fn [[read verified]]
          (cond-> facts
            read     (assoc :media (-> (read-facts read media/report)
                                       (select-keys [:format :duration :report :error])))
            verified (assoc :integrity (attempted verified)))))))))

(defn- probe-image!
  "The facts of the image `file`: the answer to a HEAD request and the
  header of the image, whose reading by range tells the rest; deferred."
  [send {:keys [url]} opts]
  (async/then (head! send url {})
              (fn [head]
                (let [target (if (ok? head) (:url head url) url)]
                  (async/then (async/attempt #(image/read! target (reading-opts send opts)))
                              (fn [read] {:head head :image (read-facts read image/report)}))))))

(defn- reading
  "What the transcript or chapters file of `kind` holds, read from its
  `body` as its declared `type`: `{:count :report}`, how many segments or
  chapters, and the report of the reader; `{:error}` when the reader
  fails."
  [kind body type]
  (try
    (if (= :transcript kind)
      (let [t (transcript/parse body type)]
        {:count (count (:segments t)) :report (transcript/report t)})
      (let [doc (chapters/parse-json body)]
        {:count (count (:chapters doc)) :report (chapters/report doc)}))
    (catch #?(:clj Exception :cljs :default) e
      {:error (async/explain e)})))

(defn- probe-text!
  "The facts of the transcript or chapters `file`, as `probed!` gives
  them, or, when `opts` ask to `:read?` it, the answers to a HEAD request
  and to a GET of the whole file, and what the file holds; deferred. The
  requests name the origin of the check, since web players read these."
  [send {:keys [kind url declared]} {:keys [read?]}]
  (let [headers {"Origin" origin}]
    (if-not read?
      (probed! send url headers)
      (async/then (async/all [(head! send url headers)
                              (http/sent! send {:url url :headers headers})])
                  (fn [[head attempt]]
                    (let [got (http/sent-facts attempt)]
                      (cond-> {:head head :get got}
                        (ok? got) (assoc :read (reading kind (or (get-in attempt [:value :body]) "") (:type declared))))))))))

(defn- probe-feed!
  "The facts of the feed at the URL of `file`, as `probed!` gives them, the
  requests naming the origin of the check and sent with the
  `::authorization` of a private feed: the caller has the feed, so none of
  it is fetched again; deferred."
  [send {:keys [url]} opts]
  (probed! send url (cond-> {"Origin" origin}
                      (::authorization opts) (assoc "Authorization" (::authorization opts)))))

(def ^:no-doc probes
  "The function that gets the facts of a file, by the kind of the file."
  {:media      probe-media!
   :image      probe-image!
   :transcript probe-text!
   :chapters   probe-text!
   :feed       probe-feed!})

(defn- probe!
  "The facts of `file`, by its kind, from the answers that `send` gives;
  deferred, and on a thread of its own on the JVM."
  [send file opts]
  (async/then (async/attempt #((probes (:kind file)) send file opts))
              attempted))

(defn- in-lanes
  "The facts of `files`, in their order, from `n` lanes that each probe the
  next file when their last is done, so that `n` files at most are under
  way and no slow file holds up the others; deferred. On the JVM a lane is
  a loop on a thread of its own, since a probe there gives its value at
  once, and in ClojureScript a chain of promises.

  A lane takes no file after the `::deadline` of `opts`. A file that no
  lane took is `:unchecked`."
  [send files {:keys [now] ::keys [deadline] :as opts} n]
  (let [queue (atom (map-indexed vector files))
        next! (fn []
                (when (or (nil? deadline) (< (inst-ms (now)) deadline))
                  (first (first (swap-vals! queue rest)))))
        lane  #?(:clj  (fn [_]
                         (loop [done []]
                           (if-let [[i file] (next!)]
                             (recur (conj done [i (probe! send file opts)]))
                             done)))
                 :cljs (fn lane [done]
                         (if-let [[i file] (next!)]
                           (async/then (probe! send file opts) #(lane (conj done [i %])))
                           done)))]
    (async/then (async/all (repeatedly n #(async/attempt (fn [] (lane [])))))
                (fn [lanes]
                  (let [by-index (into {} (mapcat :value) lanes)
                        waiting  (into {} (map (fn [[i _]] [i {:unchecked :max-seconds}])) @queue)]
                    (mapv #(or (by-index %) (waiting %) {:error "The file was not checked."})
                          (range (count files))))))))

(def profiles
  "The rules that a check adds for a standard or a directory, by the
  profile names of `dk.simongray.podcast.feed.validate`.

  A profile gives the size, the type, the colors and the file extension
  of the artwork of an itunes:image. It also gives the levels that it
  raises above the levels of the catalogue.

  They are the rules of September 2026. `verdicts`, `report` and `feed!`
  take a profile by its name here, or as a map of rules of this shape."
  {:psp-1 {:name    "Podcast Standards Project PSP-1"
           :artwork {:sizes [1400 3000] :types #{"image/jpeg" "image/png"} :colors #{:rgb :indexed}
                     :extension? true}}
   :apple {:name    "Apple Podcasts"
           :artwork {:sizes [1400 3000] :types #{"image/jpeg" "image/png"} :colors #{:rgb :indexed}
                     :extension? true :alpha? false :last-modified? true}
           :levels  {:check/ranges :error}}})

(defn ^:no-doc diagnostic
  "The diagnostic `id` about `file`, with the `details`, at the level of
  the catalogue unless the rules of the `profile` raise it."
  [{:keys [url path]} profile id & {:as details}]
  (merge {:id    id
          :level (or (get-in profile [:levels id]) (:level (diagnostics/by-id id)))
          :url   url
          :path  path}
         (present details)))

(defn ^:no-doc reaching
  "The diagnostics of how the `answer` to a request for `file` reached it,
  or failed to: nil when it came without a detour worth telling. A
  redirect that the client did not follow is a downgrade when it leaves
  https for http, one too many after five, and otherwise its status."
  [file profile {:keys [error status location redirects permanent-url] answered :url :as answer}]
  (let [redirect? (and (integer? status) (= 3 (quot status 100)))]
    (cond
      error
      [(diagnostic file profile :check/unreachable :reason error)]

      (and redirect? location
           (str/starts-with? (str/lower-case (str answered)) "https:")
           (str/starts-with? (str/lower-case location) "http:"))
      [(diagnostic file profile :check/downgrade :location location)]

      (and redirect? (<= 5 (count redirects)))
      [(diagnostic file profile :check/redirects :location location)]

      (not (ok? answer))
      [(diagnostic file profile :check/status :status status)]

      permanent-url
      [(diagnostic file profile :check/moved :moved-to permanent-url)])))

(defn ^:no-doc head-verdict
  "The diagnostic of a HEAD request for `file` that did not succeed, when
  the facts hold one."
  [file profile head]
  (when (and head (not (ok? head)))
    [(diagnostic file profile :check/head :status (:status head) :reason (:error head))]))

(defn ^:no-doc passed-on
  "The diagnostics of the report of a reader of `file` that warn of more
  than a detail."
  [file report]
  (for [d report
        :when (#{:warning :error} (:level d))]
    (merge d (select-keys file [:url :path]))))

(defn ^:no-doc type-verdict
  "The diagnostic of a media type that the server gives for `file` other
  than the one the feed declares."
  [file profile served]
  (let [declared (http/media-type (get-in file [:declared :type]))]
    (when (and declared served (not= declared served))
      [(diagnostic file profile :check/type :declared declared :served served)])))

(defn- as-is?
  "Whether the `answer` sends the file as it is, not in a content coding
  such as gzip, which changes its length."
  [answer]
  (contains? #{nil "identity"} (some-> (:encoding answer) str/trim str/lower-case)))

(defn- length-verdict
  "The diagnostic of a media `file` whose size, as the answers `get` and
  `head` give it, differs from the length that the feed declares. A length
  of 0 says that the feed does not know it, as the RSS Profile has it."
  [{:keys [declared] :as file} profile get head]
  (let [length (:length declared)
        served (or (get-in get [:range :total])
                   (when (and (= 200 (:status get)) (as-is? get)) (:length get))
                   (when (as-is? head) (:length head)))]
    (when (and (number? length) (pos? length) (number? served) (not= length served))
      [(diagnostic file profile :check/length :declared length :served served)])))

(defn- ranged?
  "Whether the `answer` to a request for the first two bytes of a file is
  those bytes alone: a 206 from the start of the file. A browser hides the
  Content-Range of an answer from another site unless the server exposes
  it, so there a 206 alone counts."
  [{:keys [status range filtered?]}]
  (and (= 206 status) (or (= 0 (:start range)) (and filtered? (nil? range)))))

(defn- ranges-verdict
  "The diagnostic of an audio or video `file` whose server does not answer
  a request for a byte range, `get`, with that range."
  [{:keys [declared] :as file} profile get]
  (when (and (re-find #"^(?:audio|video)/" (or (http/media-type (:type declared)) (:type get) ""))
             (not (ranged? get)))
    [(diagnostic file profile :check/ranges :status (:status get))]))

(defn- media-reading-verdicts
  "The diagnostics of what the reading of the media `file` found, `media`,
  against what the feed declares."
  [{:keys [declared] :as file} profile media]
  (let [expected (formats (http/media-type (:type declared)))
        stated   (:duration declared)
        measured (:duration media)
        unknown? (some #(= :file/unknown-format (:id %)) (:report media))]
    (cond
      (nil? media)
      nil

      (:error media)
      [(diagnostic file profile :check/unread :reason (:error media))]

      :else
      (concat
       (when (and expected (if (:format media) (not= expected (:format media)) unknown?))
         [(diagnostic file profile :check/format :declared (:type declared) :format (:format media))])
       (when (and (number? stated) (number? measured) (> (abs (- stated measured)) (max 10 (* 0.02 stated))))
         [(diagnostic file profile :check/duration :declared stated :measured measured)])
       ;; a format that the reader does not know is :check/format already
       (passed-on file (remove #(= :file/unknown-format (:id %)) (:report media)))))))

(defn- integrity-verdict
  "The diagnostic of the check of the media `file` against its
  podcast:integrity, `integrity`: a file that does not match it, or one
  that could not be checked."
  [file profile {:keys [valid? algorithm reason error] :as integrity}]
  (cond
    (nil? integrity) nil
    (false? valid?)  [(diagnostic file profile :check/integrity :algorithm algorithm)]
    (not valid?)     [(diagnostic file profile :check/unverified :reason (or error reason))]))

(defn- media-verdicts
  "The diagnostics of the media `file` from its `facts`."
  [file profile {:keys [head get media integrity]}]
  (if-not (ok? get)
    (reaching file profile get)
    (let [head' (when (ok? head) head)]
      (concat
       (reaching file profile get)
       (head-verdict file profile head)
       (type-verdict file profile (or (:type get) (:type head')))
       (length-verdict file profile get head')
       (ranges-verdict file profile get)
       (media-reading-verdicts file profile media)
       (integrity-verdict file profile integrity)))))

(defn- ratio
  "The aspect ratio that the podcast:image attribute `s` gives, such as 1/1
  or 1.91, as a number; nil when it does not read."
  [s]
  ;; trimmed first: two runs of white space that one run can feed make a
  ;; pattern try every split of it
  (when-let [[_ a b] (re-matches #"(\d+(?:\.\d+)?)(?:\s*[/:]\s*(\d+(?:\.\d+)?))?"
                                 (str/trim (str s)))]
    (let [a (parse-double a) b (if b (parse-double b) 1.0)]
      (when (pos? b) (/ a b)))))

(defn- shown
  "The header `image` with the width and height of the image as a viewer
  shows it: swapped when its EXIF orientation turns it a quarter."
  [{:keys [width height orientation] :as image}]
  (cond-> image
    (contains? #{5 6 7 8} orientation) (assoc :width height :height width)))

(defn- size-verdict
  "The diagnostic of an image `file` whose size in pixels differs from what
  its podcast:image declares. The podcast namespace takes a podcast:image
  without a size as square, but the model gives the images of the
  deprecated podcast:images as podcast:image elements too, and those make
  no such claim, so only a declared size counts."
  [{:keys [declared] :as file} profile {:keys [width height]}]
  (let [{w :width h :height aspect :aspect-ratio} declared
        stated (or (ratio aspect)
                   (when (and (number? w) (number? h) (pos? h)) (/ w (double h))))]
    (when (and (number? width) (number? height) (pos? height)
               (or (and (number? w) (not= w width))
                   (and (number? h) (not= h height))
                   (and stated (> (abs (- stated (/ width (double height)))) (* 0.01 stated)))))
      [(diagnostic file profile :check/image-size
                   :declared (select-keys declared [:width :height :aspect-ratio])
                   :actual {:width width :height height})])))

(defn- extension
  "The file extension of the path of `url`, lower-case; nil when it has
  none. The path is what follows the host, up to a query, which can hold
  another URL."
  [url]
  (some->> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*([^?#]*)" (str url))
           second
           (re-find #"\.([A-Za-z0-9]{1,5})$")
           second
           str/lower-case))

(defn- decodable?
  "Whether apps and browsers decode the image with the header `image`: any
  but a JPEG in a coding process other than baseline, extended and
  progressive, which libjpeg and browsers do not decode, or with more than
  8 bits a sample."
  [{:keys [type process bits]}]
  (or (not= "image/jpeg" type)
      (nil? process)
      (and (contains? #{:baseline :extended :progressive} process) (= 8 bits))))

(def ^:no-doc extension-types
  "The MIME type of the image that each file extension of artwork names."
  {"jpg" "image/jpeg" "jpeg" "image/jpeg" "png" "image/png"})

(defn- artwork-verdicts
  "The diagnostics of the image `file`, an itunes:image, by the artwork
  rules of the `profile`, from the answer to a HEAD request, `head`, and
  the header of the image as it is shown, `image`: its type as its bytes
  or else the server give it."
  [{:keys [url] :as file} profile head {:keys [width height color] :as image}]
  (let [{:keys [sizes types colors extension? alpha? last-modified?]} (:artwork profile)
        [least most] sizes
        type (or (:type image) (when (ok? head) (:type head)))
        ext  (extension url)]
    (concat
     (when (and sizes width (not (and height (= width height) (<= least width) (<= width most))))
       [(diagnostic file profile :check/artwork-size :width width :height height)])
     (when (and types type (not (and (types type) (decodable? image))))
       [(diagnostic file profile :check/artwork-type :type type :process (:process image) :bits (:bits image))])
     (when (and colors color (not (colors color)))
       [(diagnostic file profile :check/artwork-color :color color)])
     (when (and (false? alpha?) (:alpha? image))
       [(diagnostic file profile :check/artwork-alpha)])
     (when (and extension? ext type (not= type (extension-types ext)))
       [(diagnostic file profile :check/artwork-extension :extension ext :type type)])
     (when (and last-modified? (ok? head) (not (:last-modified head)))
       [(diagnostic file profile :check/last-modified)]))))

(defn- image-verdicts
  "The diagnostics of the image `file` from its `facts`."
  [{{:keys [sources]} :declared :as file} profile {:keys [head image]}]
  (cond
    (and (:error image) (not (ok? head)))
    (reaching file profile head)

    (:error image)
    [(diagnostic file profile :check/unread :reason (:error image))]

    :else
    (let [image (shown image)]
      (concat
       (when (ok? head) (reaching file profile head))
       (head-verdict file profile head)
       (passed-on file (:report image))
       (when (and (ok? head) (:type head) (:type image) (not= (:type head) (:type image)))
         [(diagnostic file profile :check/image-type :served (:type head) :actual (:type image))])
       (when (contains? sources :podcast)
         (size-verdict file profile image))
       (when (contains? sources :itunes)
         (artwork-verdicts file profile head image))))))

(defn- barrier
  "The first answer on the way to the `answer` to a request for `url`, the
  redirects and the answer itself, that does not let a page of another
  site read on, by the CORS rules of the Fetch standard, which a browser
  applies to each; nil when none stops it. An answer lets the page read
  when it allows any origin, or the origin of the check, which no site has,
  so that the server allows whatever origin it gets. After a redirect to
  another origin a browser names the origin null, which such a server
  allows too, as does an answer that allows null there. In a browser, an
  answer that came is one that CORS let through."
  [url {:keys [redirects filtered?] :as answer}]
  (when-not filtered?
    (loop [[a & more] (conj (vec redirects) answer)
           tainted?   false]
      (when a
        (let [tainted? (or tainted? (not (http/same-origin? url (:url a))))
              cors     (:cors a)]
          (if (or (= "*" cors) (= origin cors) (and tainted? (= "null" cors)))
            (recur more tainted?)
            a))))))

(defn ^:no-doc cors-verdict
  "The diagnostic `id` of the answer `get` to a request for `url`, when a
  page of another site may not read it: `:allowed` names the origins that
  the answer that stops it allows, and `:at` the redirect that it is."
  [file profile id url get]
  (when-let [a (barrier url get)]
    [(diagnostic file profile id :allowed (:cors a) :at (when-not (identical? a get) (:url a)))]))

(defn- text-verdicts
  "The diagnostics of the transcript or chapters `file` from its `facts`."
  [{:keys [url] :as file} profile {:keys [head get read]}]
  (if-not (ok? get)
    (reaching file profile get)
    (concat
     (reaching file profile get)
     (head-verdict file profile head)
     (type-verdict file profile (:type get))
     (cors-verdict file profile :check/cors url get)
     (cond
       (:error read)                    [(diagnostic file profile :check/unread :reason (:error read))]
       (and read (zero? (:count read))) [(diagnostic file profile :check/empty)])
     (passed-on file (:report read)))))

(defn- feed-verdicts
  "The diagnostics of the feed `file` from its `facts`. A server need not
  send the validators in a 206, so those of the answer to HEAD count too,
  and a browser hides the ETag of an answer from another site."
  [{:keys [url] :as file} profile {:keys [head get]}]
  (if-not (ok? get)
    (reaching file profile get)
    (concat
     (reaching file profile get)
     (head-verdict file profile head)
     (cors-verdict file profile :check/feed-cors url get)
     (when-not (or (:etag get) (:last-modified get) (:filtered? get)
                   (and (ok? head) (or (:etag head) (:last-modified head))))
       [(diagnostic file profile :check/validators)]))))

(def ^:no-doc judges
  "The function that gives the diagnostics of a file from its facts, by the
  kind of the file."
  {:media      media-verdicts
   :image      image-verdicts
   :transcript text-verdicts
   :chapters   text-verdicts
   :feed       feed-verdicts})

(defn verdicts
  "The diagnostics that the `facts` about `file` call for. `files` gives
  the file, and `feed!` gives the facts.

  The rules are those of the standards, and those of the `profile` when
  there is one. Each diagnostic has the `:url` of the file and the `:path`
  of the element that names it.

  `profile` is a name in `profiles`, or a map of rules of the shape of its
  entries."
  [{:keys [kind] :as file} facts profile]
  (let [rules (if (map? profile) profile (get profiles profile))
        judge (get judges kind (constantly []))]
    (->> (cond
           (:unchecked facts) []
           (:error facts)     [(diagnostic file rules :check/unreachable :reason (:error facts))]
           :else              (judge file rules facts))
         (mapv diagnostics/describe))))

(defn report
  "The diagnostics of the checked `files`, by the rules of the `profile`.

  Each file has its `:facts`, as `feed!` gives them, and `profile` is
  what `verdicts` takes. So several profiles can judge one check, and no
  request goes out again.

  The files that a limit of the check left out have no diagnostics of
  their own. One `:check/unchecked` counts them for each limit."
  [files profile]
  (into (vec (mapcat #(verdicts % (:facts %) profile) files))
        (for [[limit n] (frequencies (keep (comp :unchecked :facts) files))]
          (diagnostics/describe {:id :check/unchecked :level :warning :limit limit :count n}))))

(defn feed!
  "Check over the network the files that `feed` links to, as `files` lists
  them. On the JVM it gives the result, and in ClojureScript a promise of
  it.

  The result is `{:files [...] :report [...]}`. Each file has the `:facts`
  that its answers gave. The report has the diagnostics of all the files,
  from `report`. `opts` are these, with `default-limits` for the limits:

      {:url         \"https://…\" ; the URL of the feed, which is checked too
       :credentials {…}         ; of a private feed, as http/fetch! takes them
       :items       10          ; the newest items whose files to check, or :all
       :profile     :apple      ; a profile of `profiles` whose rules to add,
                                ; or a map of rules
       :read?       false       ; read what the files hold: the format and duration
                                ; of media files, transcripts and chapters whole
       :integrity?  false       ; fetch each file that has a podcast:integrity whole
       :max-files   500         ; check no more files than this
       :max-seconds 600         ; start no file after this long
       :concurrency 4           ; how many files to check at a time
       :timeout-seconds 30      ; how long each request can take
       :max-bytes   33554432    ; how much of a file to read, but for its integrity
       :allow?      f           ; which URLs a request can go to, as for http/call!
       :send        f           ; how to send the requests, in place of http/call!
       :now         f}          ; the time, the clock by default

  The credentials go with the requests for the feed alone. So do a user
  name and a password in its URL, and the result names the URL without
  them.

  Unless `opts` ask it to read, a check moves two bytes of each file, as
  `http/probe!` does, and the header of each image. It reads a media file
  by byte ranges, and only as far as its duration. The byte ranges of
  media files and images come by `http/fetcher`.

  Each request carries the `:timeout-seconds`, the `:max-bytes` and the
  `:allow?`. A server that checks feeds for strangers gives `http/public?`
  as `:allow?`.

  For the integrity of a file, a check fetches the whole file, as
  `dk.simongray.podcast.format.media/verify!` does. That request carries
  the `:allow?`, and it takes the time that the check has left. It goes
  through the `:send` of `opts`. Without a `:send`, the file streams
  through `http/digest!` on the JVM.

  A file that a limit left out has the facts `{:unchecked limit}`, and the
  report counts such files with `:check/unchecked`."
  ([feed]
   (feed! feed {}))
  ([feed opts]
   (let [own-send     (:send opts)
         {:keys [url credentials profile concurrency send max-files max-seconds now] :as opts}
         (merge default-limits
                {:send http/call! :now #?(:clj #(java.util.Date.) :cljs #(js/Date.))}
                opts)
         [url in-url] (http/split-credentials url)
         auth         (http/authorization (or credentials in-url))
         opts         (cond-> (assoc opts :url url)
                        own-send    (assoc ::own-send own-send)
                        auth        (assoc ::authorization auth)
                        max-seconds (assoc ::deadline (+ (inst-ms (now)) (* 1000 max-seconds))))
         found        (files feed opts)
         [near far]   (if max-files (split-at max-files found) [found nil])]
     (async/then (in-lanes #(send (http/limited % opts)) near opts (max 1 concurrency))
                 (fn [facts]
                   (let [checked (into (mapv #(assoc %1 :facts %2) near facts)
                                       (map #(assoc % :facts {:unchecked :max-files}))
                                       far)]
                     {:files  checked
                      :report (report checked profile)}))))))

(comment
  (def feed
    {:rss/title "A show"
     :rss/items [{:rss/title           "Episode 1"
                  :rss/pub-date        #inst "2026-09-01"
                  :rss/enclosure       {:url "https://example.org/1.mp3" :type "audio/mpeg" :length 1000}
                  :podcast/transcripts [{:url "https://example.org/1.vtt" :type "text/vtt"}]}]})

  (files feed {:url "https://example.org/feed.xml"})

  ;; what the facts of a file call for, with no request
  (def media-file
    {:kind :media :url "https://example.org/1.mp3" :path [:rss/items 0 :rss/enclosure]
     :declared {:type "audio/mpeg" :length 1000}})

  (def served
    {:head {:status 200 :type "audio/mpeg" :length 1200}
     :get  {:status 200 :type "audio/mpeg" :length 1200}})

  (map :id (verdicts media-file served nil))
  (map (juxt :id :level) (verdicts media-file served :apple))
  (map :id (report [(assoc media-file :facts {:unchecked :max-files})] nil))

  ;; a check that sends no request: the app sends in its own way
  (feed! feed {:send (fn [request] {:status 404 :url (:url request) :headers {}})})
  #_.)
