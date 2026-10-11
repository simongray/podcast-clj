(ns dk.simongray.podcast.format.hls
  "HLS playlists, the streams that feeds offer as alternate enclosures:
  what a multivariant playlist says of its variants and renditions, and
  what a media playlist says of its duration.

  A multivariant playlist reads as a map like this, with the URLs made
  absolute against the playlist's own:

      {:kind       :multivariant
       :version    6
       :variants   [{:url \"https://…/720p.m3u8\" :bandwidth 1352370
                     :average-bandwidth 831345
                     :codecs \"hvc1.1.6.L93.90,mp4a.40.2\"
                     :width 1280 :height 720 :frame-rate 29.97
                     :subtitles \"subs\"}]
       :renditions [{:type :subtitles :group-id \"subs\" :name \"English\"
                     :language \"en\" :default? true :autoselect? true
                     :url \"https://…\"}]}

  A media playlist reads as one like this:

      {:kind :media :target-duration 6 :segments 816 :duration 4895.9
       :playlist-type \"VOD\" :ended? true}

  The reading follows RFC 8216 and never throws on content."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.url :as url]))

;; RFC 8216, section 4, and the x- forms that feeds use
(def types
  "The media types of an HLS playlist, in lower case."
  #{"application/vnd.apple.mpegurl" "application/x-mpegurl"
    "application/mpegurl" "audio/mpegurl" "audio/x-mpegurl"})

;; RFC 8216, section 4: by the path or by the type
(defn playlist?
  "Whether the `enclosure`, or any map of a :url and a :type, is an HLS
  playlist: by its type, or by a path that ends in .m3u8 or .m3u."
  [{:keys [url type] :as enclosure}]
  (boolean (or (contains? types (headers/media-type type))
               (some->> url str (re-find #"(?i)\.m3u8?(?:[?#]|$)")))))

;; RFC 8216, section 4.2: a quoted string can hold commas
(defn ^:no-doc attributes
  "The attribute list `s` of a tag as a map of each name to its value as
  written, without the quotes of a quoted string."
  [s]
  (into {}
        (for [[_ k v] (re-seq #"([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)" (str s))]
          [k (if-let [[_ inner] (re-matches #"\"([^\"]*)\"" v)] inner v)])))

(defn- number
  "The number that the text `s` holds, or nil."
  [s]
  (when (string? s)
    (or (parse-long s) (parse-double s))))

(defn- text
  "The text `s`, or nil when it's blank or NONE."
  [s]
  (when-not (or (str/blank? s) (= "NONE" s))
    s))

;; RFC 8216, section 4.3.4.2
(def ^:no-doc variant-attributes
  "The attributes of an EXT-X-STREAM-INF by the key of a variant, each
  with the function that reads its value."
  {:bandwidth         ["BANDWIDTH" number]
   :average-bandwidth ["AVERAGE-BANDWIDTH" number]
   :codecs            ["CODECS" text]
   :frame-rate        ["FRAME-RATE" number]
   :audio             ["AUDIO" text]
   :video             ["VIDEO" text]
   :subtitles         ["SUBTITLES" text]
   :closed-captions   ["CLOSED-CAPTIONS" text]})

;; RFC 8216, section 4.3.4.1
(def ^:no-doc rendition-attributes
  "The attributes of an EXT-X-MEDIA by the key of a rendition, each with
  the function that reads its value."
  {:type        ["TYPE" #(some-> % text str/lower-case keyword)]
   :group-id    ["GROUP-ID" text]
   :name        ["NAME" text]
   :language    ["LANGUAGE" text]
   :channels    ["CHANNELS" text]
   :instream-id ["INSTREAM-ID" text]
   :default?    ["DEFAULT" #(= "YES" %)]
   :autoselect? ["AUTOSELECT" #(= "YES" %)]
   :forced?     ["FORCED" #(= "YES" %)]})

(defn- read-attributes
  "The values of the attribute list `attrs` by the keys of `table`, those
  that read."
  [table attrs]
  (into {}
        (for [[k [name f]] table
              :let  [v (f (get attrs name))]
              :when (some? v)]
          [k v])))

(defn- variant
  "The variant stream of the EXT-X-STREAM-INF `attrs`."
  [attrs]
  (let [[width height] (some->> (get attrs "RESOLUTION")
                                (re-matches #"(\d+)x(\d+)")
                                rest
                                (map parse-long))]
    (cond-> (read-attributes variant-attributes attrs)
      width (assoc :width width :height height))))

(defn- rendition
  "The rendition of the EXT-X-MEDIA `attrs` in a playlist at `base`."
  [attrs base]
  (cond-> (read-attributes rendition-attributes attrs)
    (text (get attrs "URI")) (assoc :url (or (url/absolute-url
                                               base (get attrs "URI"))
                                              (get attrs "URI")))))

;; RFC 8216, section 4.3.2.1: EXTINF:<duration>,[<title>]
(defn- segment-duration
  "The duration in seconds of a segment, from the `value` of its EXTINF."
  [value]
  (or (number (first (str/split (str value) #","))) 0))

(defn- tag
  "The name and the value of the tag `line`, e.g. [\"EXTINF\" \"9.0,\"],
  or nil for a comment."
  [line]
  (some-> (re-matches #"#(EXT[A-Z0-9-]*)(?::(.*))?" line) rest vec))

(defn- step
  "The state `acc` of a reading of a playlist at `base` after its `line`.
  A URI line after an EXT-X-STREAM-INF is the URI of that variant, and
  RFC 8216, section 4.1, makes a relative URI relative to the playlist."
  [base acc line]
  (if (str/starts-with? line "#")
    (let [[name value] (tag line)]
      (case name
        "EXT-X-VERSION"        (assoc acc :version (number value))
        "EXT-X-STREAM-INF"     (assoc acc :pending (variant (attributes value)))
        "EXT-X-MEDIA"          (update acc :renditions conj
                                       (rendition (attributes value) base))
        "EXTINF"               (-> acc
                                   (update :segments inc)
                                   (update :duration +
                                           (segment-duration value)))
        "EXT-X-TARGETDURATION" (assoc acc :target-duration (number value))
        "EXT-X-PLAYLIST-TYPE"  (assoc acc :playlist-type value)
        "EXT-X-ENDLIST"        (assoc acc :ended? true)
        acc))
    (if-let [pending (:pending acc)]
      (-> acc
          (update :variants conj
                  (assoc pending :url (or (url/absolute-url base line) line)))
          (dissoc :pending))
      acc)))

;; RFC 8216, section 4.3.1.1: EXTM3U is the first line
(defn parse
  "The HLS playlist `s`, with its URIs made absolute against `url`, the
  playlist's own, as a map whose :kind is :multivariant or :media, or nil
  when `s` isn't a playlist."
  ([s]
   (parse s nil))
  ([s url]
   (let [lines (->> (str/split-lines (str s))
                    (map str/trim)
                    (remove str/blank?))
         start {:variants [] :renditions [] :segments 0 :duration 0}]
     (when (= "#EXTM3U" (first lines))
       (let [state (reduce (partial step url) start (rest lines))]
         (if (seq (:variants state))
           (-> (select-keys state [:version :variants :renditions])
               (assoc :kind :multivariant))
           (cond-> (-> (select-keys state [:version :target-duration :segments
                                           :playlist-type :ended?])
                       (assoc :kind :media))
             (pos? (:segments state))
             (assoc :duration (/ (Math/round (* 1000.0 (:duration state)))
                                 1000.0)))))))))

(defn- fetch-playlist!
  "Fetch and parse the playlist at `url` with `opts`, in a future or a
  promise: the playlist, or a map of the :error. Its URIs are relative to
  where the playlist came from, after any redirect."
  [url {:keys [send] :or {send client/send!} :as opts}]
  (let [accept  "application/vnd.apple.mpegurl, */*;q=0.1"
        request {:url url :headers (merge {"Accept" accept} (:headers opts))}]
    (async/then
     (http/try-send! send request opts)
     (fn [{:keys [value error]}]
       (let [status (:status value)
             failed (cond
                      error
                      (assoc (view/unreachable (http/failure error)) :url url)

                      (not (<= 200 (long status) 299))
                      {:id :file/unreachable :url url :status status})
             parsed (when-not failed
                      (parse (str (:body value)) (or (:url value) url)))]
         (cond
           failed (diagnostics/failed failed)
           parsed parsed
           :else  (diagnostics/failed
                   {:id     :file/malformed
                    :url    url
                    :reason "It isn't an HLS playlist"})))))))

(defn read!
  "Read the HLS playlist at `url` with `opts`, and give what parse gives,
  on the JVM at once and in ClojureScript as a promise.

  For a multivariant playlist, the media playlist of its first variant
  is read too, for the :duration, :segments and :ended? of the stream. A
  failure there leaves those out, with the diagnostic under :report.

  The `opts` are :send, :allow-pred, :headers, :timeout-seconds,
  :max-bytes and :user-agent, as for any request. Nothing that the network or the server
  does throws: a failed request gives :file/unreachable under :error, and
  a text that isn't a playlist gives :file/malformed."
  ([url]
   (read! url {}))
  ([url opts]
   (async/then
    (fetch-playlist! url opts)
    (fn [{:keys [kind variants] :as playlist}]
      (if-let [first-url (and (= :multivariant kind)
                              (:url (first variants)))]
        (async/then
         (fetch-playlist! first-url opts)
         (fn [{:keys [error] :as media}]
           (if error
             (assoc playlist :report [error])
             (merge playlist
                    (select-keys media [:duration :segments :ended?])))))
        playlist)))))

(comment
  (parse (str "#EXTM3U\n#EXT-X-VERSION:6\n"
              "#EXT-X-STREAM-INF:BANDWIDTH=1352370,AVERAGE-BANDWIDTH=831345,"
              "CODECS=\"hvc1.1.6.L93.90,mp4a.40.2\",RESOLUTION=1280x720\n"
              "720p/index.m3u8\n")
         "https://example.com/show/master.m3u8")
  ;; => {:kind :multivariant :version 6 :renditions []
  ;;     :variants [{:url "https://example.com/show/720p/index.m3u8" …}]}

  (read! (str "https://www.macgeekgab.com/media/cachefly/MGG-Videos/"
              "MGG1162-trimmed/master.m3u8"))
  #_.)
