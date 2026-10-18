(ns ^:no-doc dk.simongray.podcast.feed.resolve.impl
  "The internals of feed.resolve: the tables and helpers that its accessors
  pick an answer with, from image shapes and codecs to the ranking of
  transcripts and the search for where a property came from."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.whatwg-mime :as mime]))

(defn text
  "The string `s` without the space around it, or nil when it's blank or
  isn't a string."
  [s]
  (when (string? s)
    (not-empty (str/trim s))))

(defn email-name
  "The name in the RSS email field `s`, written as \"address (Name)\", or
  else `s`."
  [s]
  ;; cut at the first parenthesis rather than matched whole, since one
  ;; pattern for all of it backtracks quadratically over a long address
  (let [t    (str/trim (str s))
        open (str/index-of t "(")
        end  (dec (count t))]
    (if (and open
             (< (inc open) end)
             (str/ends-with? t ")")
             (re-find #"^\S+@\S+$" (str/trimr (subs t 0 open))))
      (subs t (inc open) end)
      s)))

(def domain-pattern
  "A domain with an optional port: labels of letters, digits and hyphens
  between dots, and a top-level domain of letters."
  (let [label "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"]
    (re-pattern (str "(?i)(?:" label "\\.)+[a-z]{2,24}(?::[0-9]{1,5})?"))))

(def file-extension
  "The extension of a file at the end of a name, which a top-level domain
  of letters could be mistaken for."
  (re-pattern (str "(?i)\\.(?:html?|php|aspx?|jsp|cgi|xml|json|rss|txt"
                   "|pdf|mp3|m4a|mp4|jpe?g|png|gif)(?::|$)")))

(defn domain-first?
  "Whether the URL `s`, which has no scheme, starts with a domain, as in
  example.com/page, rather than a path: its first part is a domain and
  doesn't end in the extension of a file."
  [s]
  (let [head (first (str/split s #"[/?#]" 2))]
    (boolean (and (re-matches domain-pattern head)
                  (not (re-find file-extension head))))))

(defn- square?
  "Whether `image` is square, or says nothing about its shape."
  [{:keys [aspect-ratio width height] :as image}]
  (cond
    aspect-ratio       (contains? #{"1/1" "1" "1:1"}
                                  (str/replace aspect-ratio #"\s" ""))
    (and width height) (= width height)
    :else              true))

(defn picture?
  "Whether the `image`, one of those that images gives, is a picture rather
  than a video: its type is an image type, or it has none."
  [{:keys [type]}]
  (or (nil? type)
      (str/starts-with? (str/lower-case type) "image/")))

(defn artwork?
  "Whether the podcast:image `image` is meant as artwork: it's a picture,
  and it says so, or it has no stated purpose and isn't another shape."
  [image]
  (and (picture? image)
       (or (contains? (:purpose image) "artwork")
           (and (empty? (:purpose image))
                (square? image)))))

(defn largest
  "The widest of the `images`, the first of those as wide, or nil for none.
  An image without a width counts as the narrowest."
  [images]
  (let [width #(let [w (:width %)] (if (number? w) w 0))]
    (first (sort-by #(- (width %)) images))))

(defn media-contents
  "The Media RSS contents of `item`, in the item and in its media:group."
  [item]
  (concat (:media/contents item) (:contents (:media/group item))))

;; RFC 6381, section 3.3; the sample entries of the MP4 Registration
;; Authority, and the codecs of WebM
(def codec-kinds
  "The kind of media of each codec that the first part of an RFC 6381
  codecs string names, in lower case."
  (merge (zipmap ["avc1" "avc3" "hvc1" "hev1" "dvh1" "dvhe" "dva1" "dvav"
                  "av01" "vp08" "vp09" "vp8" "vp9" "vvc1" "vvi1" "mp4v"]
                 (repeat :video))
         (zipmap ["mp4a" "opus" "flac" "alac" "ac-3" "ec-3" "ac-4" "mha1"
                  "mhm1" "mp3" "vorbis"]
                 (repeat :audio))))

(defn codec-list
  "The codecs of the RFC 6381 codecs string `s`, trimmed."
  [s]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split (str s) #",")))

(defn codec-kind
  "The kind of media of the `codec`, :audio or :video, or nil when it isn't
  known."
  [codec]
  (codec-kinds (first (str/split (str/lower-case codec) #"\."))))

(def single-codec-types
  "The media types, in lower case, that imply their codec, and so take no
  codecs parameter."
  #{"audio/mpeg" "audio/mp3" "audio/aac" "audio/flac" "audio/x-flac"})

;; RFC 2046, section 4.5.1: the type of data whose format isn't known
(def unknown-types
  "The media types, in lower case, that say nothing of the format of a
  file."
  #{"application/octet-stream" "binary/octet-stream"})

(defn format-type
  "The media type that the `type` attribute names, in lower case and without
  its parameters, or nil when it says nothing of the format: there's none,
  it isn't a type and a subtype, or it's one of unknown-types."
  [type]
  (let [essence (mime/essence type)]
    (when-not (unknown-types essence)
      essence)))

;; RFC 7587 registers audio/opus for RTP alone, while an .opus file is Ogg
;; (RFC 7845), and the others are servers' own names for MP4 and MP3.
;; Chrome 155, Firefox 157 and WebKit 27.2 answered better for the names
;; on the right, and the corpus's files under each alias were of its format.
(def format-aliases
  "The media types, in lower case, that feeds give a format by and that
  browsers don't know it by, each to the type they do know it by and the
  codec that the alias implies, if any."
  {"audio/opus"  ["audio/ogg" "opus"]
   "audio/m4a"   ["audio/mp4" nil]
   "audio/x-m4a" ["audio/mp4" nil]
   "audio/mpeg3" ["audio/mpeg" nil]})

(defn content-type
  "The media type `essence` as a content type of the `kind`, :audio or
  :video, with the `codec`, if any, as its codecs parameter."
  [essence kind codec]
  (let [[top sub] (str/split essence #"/" 2)
        top       (if (#{"audio" "video"} top) (name kind) top)]
    (cond-> (str top "/" sub)
      (and codec (not (single-codec-types essence)))
      (str "; codecs=\"" codec "\""))))

;; ISO/IEC 23009-1; the podcast namespace, source
(def manifest-types
  "The types of a file that says where the media is rather than holds it,
  in lower case, besides an HLS playlist: a DASH manifest and a torrent."
  #{"application/dash+xml" "application/x-bittorrent"})

(defn type-rank
  "The position of `type` among the preferred `types`, ignoring case and
  parameters such as a charset, or after them all when it isn't among
  them."
  [types type]
  (let [type (mime/essence type)]
    (or (first (keep-indexed (fn [i wanted]
                               (when (= type (str/lower-case wanted)) i))
                             types))
        (count types))))

(def timed-transcript-types
  "The transcript types whose files have timings as a rule."
  #{"text/vtt" "application/x-subrip" "application/srt" "text/srt"
    "application/json"})

(defn timed-transcript?
  "Whether the transcript reference `ref` has timings: rel=captions, or a
  type that has them as a rule."
  [{:keys [type rel] :as ref}]
  (or (= "captions" (some-> rel str/lower-case))
      (contains? timed-transcript-types (mime/essence type))))

(defn inherited
  "The value of `k` on `item` when it declares one, or else that of `feed`."
  [k feed item]
  (let [v (get item k)]
    (if (or (nil? v)
            (and (coll? v) (empty? v)))
      (get feed k)
      v)))

;; podcast namespace, license: the node value is the identifier of a
;; license of its list, licenses.json, or the name of a custom license,
;; which then needs the url of its terms
(defn with-terms
  "The podcast:license `license` with the :name, :version and :url of the
  license of spec/licenses that it names, its own url first, or as it is."
  [{:keys [content] :as license}]
  (let [known (some->> (text content) str/lower-case (get spec/licenses))]
    (cond->> license
      known (merge known))))

(defn social-interact
  "The podcast:socialInteract `interact` with its protocol in lower case, or
  nil when it names no protocol and URI, and isn't the protocol disabled."
  [{:keys [protocol uri] :as interact}]
  (let [protocol (some-> protocol str/lower-case)]
    (when (or (= "disabled" protocol)
              (and protocol uri))
      (-> (select-keys interact [:uri :account-id :account-url :priority])
          (assoc :protocol protocol)))))

(def live-status-order
  "Live items in the order that a listener cares about: on air, then
  upcoming, then over."
  {"live" 0 "pending" 1 "ended" 2})

(defn atom-links
  "The atom:links of `feed` with the link relation `rel`, given in lower
  case, in the order of the feed."
  [feed rel]
  (filter #(= rel (some-> (:rel %) str/lower-case))
          (:atom/links feed)))

(defn category-paths
  "The paths of `categories` and of their subcategories, each below
  `prefix`."
  [prefix categories]
  (mapcat (fn [{:keys [text categories]}]
            (let [path (conj prefix text)]
              (if (seq categories)
                (category-paths path categories)
                [path])))
          categories))

(defn- answer
  "The value `v` of an accessor as a property: a sequence as a vector, and
  nothing when it's nil or empty."
  [v]
  (cond (nil? v)  nil
        (seq? v)  (when (seq v) (vec v))
        (coll? v) (when (seq v) v)
        :else     v))

(defn answers
  "The properties that the accessors of `table` answer, each called with
  `call`."
  [table call]
  (into {} (keep (fn [[k f]]
                   (when-some [v (answer (call f))]
                     [k v])))
        table))

(defn- explains?
  "Whether the accessor `f` gives `expected` from `args` alone."
  [f expected args]
  (= expected (answer (apply f args))))

(defn- from-defaults?
  "Whether `f`, given `arity` empty maps, answers with nothing to read
  from: a default, which no field explains."
  [f arity]
  (some? (answer (apply f (repeat arity {})))))

(defn explaining-paths
  "The paths into `m` of the keys whose entry alone makes the accessor `f`
  give `expected`, in the order of the keys. A path is `prefix` and the
  key, and `args-fn` makes the arguments of `f` of the map of the entry."
  [f expected m prefix args-fn]
  (for [k     (sort (keys m))
        :when (explains? f expected (args-fn (select-keys m [k])))]
    (conj prefix k)))

(defn paths-by-property
  "The paths of each property in the answers `full` of the accessors of
  `table`, as `paths-of` gives them for the accessor and its answer.

  A property that the accessor answers from `arity` empty maps, a default,
  is left out, and so is one without paths."
  [table full arity paths-of]
  (into {} (for [[k f] table
                 :when (and (contains? full k)
                            (not (from-defaults? f arity)))
                 :let  [paths (vec (paths-of f (get full k)))]
                 :when (seq paths)]
             [k paths])))
