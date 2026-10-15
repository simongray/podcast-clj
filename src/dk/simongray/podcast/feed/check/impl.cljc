(ns ^:no-doc dk.simongray.podcast.feed.check.impl
  "The internals of feed.check: which files of a feed a check fetches, and
  the facts of each, gathered over the network for check-files!. Replaces
  feed.check.facts."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.check.rules.impl :as rules.impl]
            [dk.simongray.podcast.feed.integrity :as integrity]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.hls :as hls]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-mime :as mime]))

(def overlays
  "The top-level domains of overlay networks, whose hosts only their own
  clients reach: that of Tor, which RFC 7686 sets aside, and those of I2P
  and Lokinet."
  #{"onion" "i2p" "loki"})

(defn checkable?
  "Whether a check fetches `url`: an http or https URL of a host that any
  client can reach, not one of an overlay network."
  [url]
  (and (url/web-url? url)
       (let [tld (peek (str/split (url/host url) #"\."))]
         (not (contains? overlays tld)))))

(defn present
  "The entries of the map `m` whose values aren't nil."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn newest
  "The `n` newest items of `feed`, or all of them when `n` is :all, each
  with its index in the feed."
  [feed n]
  (let [indexed (map-indexed vector (:rss/items feed))
        order   (fn [[_ item]]
                  (- (or (some-> (resolve/published item) inst-ms) 0)))]
    (cond->> (sort-by order indexed)
      (not= :all n) (take n))))

(defn image-files
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
  "The media files and streams of the alternate enclosures of `item`, under
  `path`.

  A source with a type of its own, such as a torrent, isn't the media file,
  so the length and integrity of its enclosure aren't its own. A source
  that repeats the type of its enclosure is the media file. An
  alternate with no rel or the rel default holds the content of the
  enclosure, by the podcast namespace, so it has the duration of the item."
  [item path]
  (let [alternates (map-indexed vector (:podcast/alternate-enclosures item))
        duration   (resolve/duration item)]
    (for [[j {:keys [sources type length integrity height codecs rel]}]
          alternates
          :let  [same? (contains? #{nil "default"} (some-> rel str/lower-case))]
          {:keys [uri content-type]} sources
          :let  [own-type? (and content-type
                                (not= (mime/essence content-type)
                                      (mime/essence type)))]
          :when (checkable? uri)]
      {:kind     (if (hls/playlist? {:url uri :type (or content-type type)})
                   :stream
                   :media)
       :url      uri
       :path     (conj path :podcast/alternate-enclosures j)
       :declared (if own-type?
                   {:type content-type}
                   (present {:type      type
                             :length    length
                             :integrity integrity
                             :height    height
                             :codecs    codecs
                             :duration  (when same? duration)}))})))

(defn item-files
  "The files of the `item` at the index `i` of the feed."
  [[i item]]
  (let [path      [:rss/items i]
        enclosure (:rss/enclosure item)
        chapters  (:podcast/chapters item)]
    (concat
     (when (checkable? (:url enclosure))
       [{:kind     (if (hls/playlist? enclosure) :stream :media)
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

(defn once
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

(defn- attempted
  "The value of the `attempt` of a reading, or a map with a diagnostic as
  the :error for an attempt that failed."
  [{:keys [value error] :as attempt}]
  (if error
    {:error (diagnostics/file-failure (http/failure error))}
    value))

(defn- diagnosed
  "The `facts` of an answer, with the failure under their :error, if any,
  as a diagnostic."
  [facts]
  (cond-> facts
    (:error facts) (update :error diagnostics/file-failure)))

(defn- read-facts
  "The facts of what a reader gave: the value of the `attempt` with the
  diagnostics that `report-fn` reads from it, as data, or its :error.

  The facts of a check are data that an app keeps, so they hold the
  report."
  [attempt report-fn]
  (let [{:keys [error] :as value} (attempted attempt)]
    (cond-> value
      (not error) (assoc :report (report-fn value)))))

(defn- sent-facts
  "The facts of the answer to an `attempt` to send, or a map with a
  diagnostic as the :error for an attempt that failed."
  [attempt]
  (let [{:keys [error] :as value} (attempted attempt)]
    (if error value (http/facts value))))

(defn- head!
  "Send a HEAD request for `url` with `headers` through `send`, and give
  the facts of the answer, in a future or a promise."
  [send url headers]
  (let [request {:method :head :url url :headers headers}]
    (async/then (http/try-send! send request) sent-facts)))

(defn- probe-url!
  "Probe `url` with `headers` through `send`, and give the facts, in a
  future or a promise. They tell all that a check needs of most files,
  from two bytes of each."
  [send url headers]
  (async/then (http/probe! url {:send send :headers headers})
              #(update-vals % diagnosed)))

(defn- time-left
  "The seconds that the check with `opts` has left, one at least, or nil
  when the check has no time limit."
  [{:keys [now-fn] ::keys [deadline] :as opts}]
  (when deadline
    (max 1 (quot (- deadline (inst-ms (now-fn))) 1000))))

(defn- reading-opts
  "The options of a reading by byte ranges in the check with `opts`: the
  limits of the check that apply to a reading, and `send` to send with."
  [send opts]
  (assoc (select-keys opts [:timeout-seconds :max-bytes :now-fn]) :send send))

(defn- whole-opts
  "The options of the fetch of a whole file for its integrity, in the
  check with `opts`: the :allow-pred, the time that the check has left,
  and the :send of the app, if any."
  [{::keys [own-send] :as opts}]
  ;; a whole file takes longer than a part of it
  (let [left (time-left opts)]
    (cond-> (select-keys opts [:allow-pred])
      left     (assoc :timeout-seconds left)
      own-send (assoc :send own-send))))

(defn- probe-media!
  "Probe the media `file` through `send` with `opts`, and give its facts,
  in a future or a promise.

  They're the facts of a probe, and as `opts` ask, what the file says
  about itself and whether it has its integrity."
  [send {:keys [url declared] :as file} {:keys [read? integrity?] :as opts}]
  (async/then
   (probe-url! send url {})
   (fn [{:keys [head get] :as facts}]
     (let [target     (or (:url get) (:url head) url)
           stated     (mime/essence (:type declared))
           came?      (contains? #{200 206} (:status get))
           readable?  (or (nil? stated) (contains? media/formats stated))
           ;; the check keeps no chapters, so it fetches none of their images
           read-opts  (assoc (reading-opts send opts) :chapter-images? false)
           read-job   (when (and read? came? readable?)
                        (async/attempt #(media/read! target read-opts)))
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
                              (select-keys [:format :duration :codecs :width
                                            :height :movie-first? :report
                                            :error])))

            ;; a reading was asked, but none knows the type
            (and read? came? (not readable?))
            (assoc :unread-type stated)

            verified
            (assoc :integrity (attempted verified)))))))))

(defn- probe-stream!
  "Probe the HLS stream `file` through `send` with `opts`, and give its
  facts, in a future or a promise.

  They're the facts of a probe, and when `opts` ask to :read? the stream,
  what its playlists say. The requests name the origin of the check, since
  web players read playlists."
  [send {:keys [url] :as file} {:keys [read?] :as opts}]
  (let [headers {"Origin" rules.impl/origin}]
    (async/then
     (probe-url! send url headers)
     (fn [{:keys [get] :as facts}]
       (if (and read? (rules.impl/ok? get))
         (async/then
          (async/attempt #(hls/read! (or (:url get) url)
                                     (assoc (reading-opts send opts)
                                            :headers headers)))
          (fn [attempt]
            (assoc facts :stream (-> (attempted attempt)
                                     (select-keys [:kind :duration :ended?
                                                   :variants :report
                                                   :error])))))
         facts)))))

(defn- probe-image!
  "Probe the image `file` through `send` with `opts`, and give its facts,
  in a future or a promise. They're the answer to a HEAD request, and the
  header of the image, read by range."
  [send {:keys [url] :as file} opts]
  (async/then (head! send url {})
              (fn [head]
                (let [target (if (rules.impl/ok? head) (:url head url) url)
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
      (diagnostics/failed {:id     :file/malformed
                           :reason (async/explain e)}))))

(defn- probe-text!
  "Probe the transcript or chapters `file` through `send` with `opts`, and
  give its facts, in a future or a promise.

  They're the facts of a probe. When `opts` ask to :read? the file,
  they're the answers to a HEAD request and to a GET of the whole file,
  one after the other as in a probe, and what the file holds. The
  requests name the origin of the check, since web players read these
  files."
  [send {:keys [kind url declared] :as file} {:keys [read?] :as opts}]
  (let [headers {"Origin" rules.impl/origin}]
    (if-not read?
      (probe-url! send url headers)
      (async/then
       (head! send url headers)
       (fn [head]
         (async/then
          (http/try-send! send {:url url :headers headers})
          (fn [attempt]
            (let [got  (sent-facts attempt)
                  body (or (get-in attempt [:value :body]) "")]
              (cond-> {:head head :get got}
                (rules.impl/ok? got)
                (assoc :read (reading kind body (:type declared))))))))))))

(defn- probe-feed!
  "Probe the feed at the URL of `file` through `send` with `opts`, and give
  its facts, in a future or a promise.

  The requests name the origin of the check, and have the ::authorization
  of `opts` for a private feed. The caller has the feed, so none of it is
  fetched again."
  [send {:keys [url] :as file} opts]
  (let [auth    (::authorization opts)
        headers (cond-> {"Origin" rules.impl/origin}
                  auth (assoc "Authorization" auth))]
    (probe-url! send url headers)))

(def probes
  "The function that gets the facts of a file, by the kind of the file."
  {:media      probe-media!
   :stream     probe-stream!
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
  "The facts of `files` in their order, from what the `lanes` gave, with
  the files still in the `queue` :unchecked."
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

(defn gather!
  "Gather the facts of `files` in their order, sending with `send` and
  `opts` from `n` lanes, and give them in a future or a promise.

  Each lane probes the next file when its last one is done, so that at
  most `n` files are under way and no slow file holds up the others. A
  lane takes no file after the ::deadline of `opts`, and a file that no
  lane took is :unchecked."
  [send files {:keys [now-fn] ::keys [deadline] :as opts} n]
  (let [queue (atom (map-indexed vector files))
        next! (fn []
                (when (or (nil? deadline) (< (inst-ms (now-fn)) deadline))
                  (first (first (swap-vals! queue rest)))))
        ;; on the JVM, a lane is a loop on a thread of its own, since a
        ;; probe there gives its value at once, and in ClojureScript a chain
        ;; of promises
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
