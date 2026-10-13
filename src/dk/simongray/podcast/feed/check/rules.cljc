(ns dk.simongray.podcast.feed.check.rules
  "The rules that judge the facts of a check: those of the standards, and
  the requirements of a directory.

  The standards come first: HTTP as RFC 9110 has it, the attributes of
  RSS 2.0 and of the podcast namespace, and the CORS rules of the Fetch
  standard, which decide what a web player on another site can read. The
  rules are pure, so the facts of one check can be judged by several
  requirements, and no request goes out again."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-mime :as mime]))

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

(defn ^:no-doc why
  "The reason of the :error in the `facts`, if any."
  [facts]
  (get-in facts [:error :reason]))

(def requirements
  "The requirements that a check adds for a standard or a directory, by name.

  Each can give the size, type, colors and file extension of the artwork
  of an itunes:image, a :media-type-pred of the type that the server gives
  a media file, and the levels that it raises above those of the
  catalogue. They're the requirements of PSP-1, of Apple's A Podcaster's
  Guide to RSS, of Spotify's Podcast Delivery Specification 1.10, and of
  YouTube's Deliver podcasts using an RSS feed, as of October 2026.
  Spotify asks for square artwork of any size, and YouTube makes its
  videos from audio files.

  A map of your own can also give :max-feed-bytes, the largest feed that a
  directory takes. None of these give it, since no directory publishes
  one as of October 2026."
  (let [artwork {:sizes      [1400 3000]
                 :types      #{"image/jpeg" "image/png"}
                 :colors     #{:rgb :indexed}
                 :extension? true}]
    {:psp-1   {:name    "PSP-1 of the Podcast Standards Project"
               :artwork artwork}
     :apple   {:name    "Apple Podcasts"
               :artwork (assoc artwork :alpha? false :last-modified? true)
               :levels  {:check/ranges :error}}
     :spotify {:name            "Spotify"
               :artwork         {:square? true
                                 :types   #{"image/tiff" "image/png"
                                            "image/jpeg"}}
               :media-type-pred #(not (str/starts-with? % "image/"))}
     :youtube {:name            "YouTube"
               :media-type-pred #(str/starts-with? % "audio/")}}))

(defn ^:no-doc diagnostic
  "The diagnostic `id` about `file`, with the `details`, at the level of
  the catalogue unless the `chosen` requirements raise it."
  [{:keys [url path] :as file} chosen id & {:as details}]
  (diagnostics/masked
   (merge {:id    id
           :level (or (get-in chosen [:levels id])
                      (:level (diagnostics/by-id id)))
           :url   url
           :path  path}
          (into {} (remove (comp nil? val)) details))))

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
      [(diagnostic file chosen :check/unreachable
                   :reason (why answer) :type (:type error))]

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
  requirements, when it differs from the one that the feed declares, or
  the feed declares none."
  [file chosen served]
  (let [stated   (some-> (get-in file [:declared :type]) str str/trim not-empty)
        ;; a type that isn't a MIME type, e.g. mp3, as it's written
        declared (or (mime/essence stated) (some-> stated str/lower-case))]
    (cond
      (and declared served (not= declared served))
      [(diagnostic file chosen :check/type
                   :declared declared
                   :served   served)]

      (and (nil? declared) served)
      [(diagnostic file chosen :check/no-type :served served)])))

(defn- media-type-verdict
  "The diagnostic of a media `file` that the server gives the media type
  `served`, when the `chosen` requirements don't take a file of that type."
  [file chosen served]
  (let [pred (:media-type-pred chosen)]
    (when (and pred served (not (pred served)))
      [(diagnostic file chosen :check/media-type :type served)])))

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
  (let [type (or (mime/essence (:type declared)) (:type get) "")]
    (when (and (re-find #"^(?:audio|video)/" type)
               (not (ranged? get)))
      [(diagnostic file chosen :check/ranges :status (:status get))])))

(defn durations-differ?
  "Whether the `measured` duration of a media file or stream differs from
  the `declared` one by more than 10 seconds and 2 percent, both in
  seconds. It's false unless both are numbers."
  [declared measured]
  (and (number? declared)
       (number? measured)
       (> (abs (- declared measured))
          (max 10 (* 0.02 declared)))))

(defn ^:no-doc duration-verdict
  "The diagnostic of a media `file` or stream whose `measured` duration
  differs from the one that the feed declares by more than 10 seconds and
  2 percent, by the `chosen` requirements."
  [{:keys [declared] :as file} chosen measured]
  (let [stated (:duration declared)]
    (when (durations-differ? stated measured)
      [(diagnostic file chosen :check/duration
                   :declared stated
                   :measured measured)])))

(defn- codec-set
  "The codecs of the RFC 6381 string `s` as a set, in lower case, or nil."
  [s]
  (some->> (str/split (str/lower-case (str s)) #",")
           (map str/trim)
           (remove str/blank?)
           seq
           set))

(defn ^:no-doc rendition-verdicts
  "The diagnostics of a `file` whose declared codecs or height match none
  of the `found` renditions, the file itself or the variants of a stream,
  each a map of its :codecs and :height, by the `chosen` requirements."
  [{:keys [declared] :as file} chosen found]
  (let [codecs  (codec-set (:codecs declared))
        sets    (keep (comp codec-set :codecs) found)
        height  (:height declared)
        heights (keep :height found)]
    (concat
     (when (and codecs (seq sets) (not (some #{codecs} sets)))
       [(diagnostic file chosen :check/codecs
                    :declared (:codecs declared)
                    :found    (str/join " " (distinct (keep :codecs found))))])
     (when (and (number? height) (seq heights) (not (some #{height} heights)))
       [(diagnostic file chosen :check/height
                    :declared height
                    :found    (apply max heights))]))))

(defn- media-reading-verdicts
  "The diagnostics of what reading the media `file` found, `media`, against
  what the feed declares, by the `chosen` requirements."
  [{:keys [declared] :as file} chosen media]
  (let [expected (media/formats (mime/essence (:type declared)))
        found    (:format media)
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
       (duration-verdict file chosen (:duration media))
       (when (false? (:movie-first? media))
         [(diagnostic file chosen :check/slow-start)])
       (rendition-verdicts file chosen [media])
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
  [file chosen {:keys [head get media integrity unread-type] :as facts}]
  (if-not (ok? get)
    (reaching file chosen get)
    (let [head' (when (ok? head) head)]
      (concat
       (reaching file chosen get)
       (head-verdict file chosen head)
       (type-verdict file chosen (or (:type get) (:type head')))
       (media-type-verdict file chosen (or (:type get) (:type head')))
       (length-verdict file chosen get head')
       (ranges-verdict file chosen get)
       (media-reading-verdicts file chosen media)
       (when unread-type
         [(diagnostic file chosen :check/no-reader :type unread-type)])
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
  (let [{:keys [sizes square? types colors extension? alpha? last-modified?]}
        (:artwork chosen)

        [least most] sizes
        type         (or (:type image) (when (ok? head) (:type head)))
        ext          (extension url)]
    (concat
     (when (and (or sizes square?)
                width
                (not (and height
                          (= width height)
                          (or (nil? sizes)
                              (<= least width most)))))
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

(defn- stream-verdicts
  "The diagnostics of the HLS stream `file` from its `facts`, by the
  `chosen` requirements: how it reached the check, whether a web player
  can read it, and what its playlists say against what the feed declares.
  The duration counts only for a stream that has ended."
  [{:keys [url] :as file} chosen {:keys [head get stream] :as facts}]
  (if-not (ok? get)
    (reaching file chosen get)
    (concat
     (reaching file chosen get)
     (head-verdict file chosen head)
     (cors-verdict file chosen :check/cors url get)
     (cond
       (:error stream)
       [(diagnostic file chosen :check/unread :reason (why stream))]

       stream
       (concat
        (when (:ended? stream)
          (duration-verdict file chosen (:duration stream)))
        (rendition-verdicts file chosen (:variants stream))
        (passed-on file (:report stream)))))))

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

(defn- feed-type-verdict
  "The diagnostic of the feed `file` by the `chosen` requirements when the
  answers `head` and `get` serve it as a media type that isn't one of XML."
  [file chosen head get]
  (let [served (or (:type get) (when (ok? head) (:type head)))]
    (when (and served (not (mime/xml? served)))
      [(diagnostic file chosen :check/feed-type :served served)])))

(defn- feed-size-verdict
  "The diagnostic of the feed `file` when the answers `head` and `get` say
  that it's larger than the :max-feed-bytes of the `chosen` requirements."
  [file {:keys [max-feed-bytes] :as chosen} head get]
  (let [size (or (get-in get [:range :total])
                 (when (and (ok? head) (as-is? head)) (:length head)))]
    (when (and max-feed-bytes (number? size) (< max-feed-bytes size))
      [(diagnostic file chosen :check/feed-size
                   :bytes size :max-bytes max-feed-bytes)])))

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
     (feed-type-verdict file chosen head get)
     (feed-size-verdict file chosen head get)
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
   :stream     stream-verdicts
   :image      image-verdicts
   :transcript text-verdicts
   :chapters   text-verdicts
   :feed       feed-verdicts})

(defn verdicts
  "The diagnostics that the `facts` about `file` call for, by the
  standards and the `chosen` requirements, if any.

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
                               :reason (why facts)
                               :type (get-in facts [:error :type]))]

                  :else
                  (judge file chosen facts))]
     (mapv diagnostics/describe found))))

(comment
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
  #_.)
