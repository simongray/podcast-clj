(ns dk.simongray.podcast.feed.resolve
  "One answer where the vocabularies give several: the accessors that a
  podcast app needs.

  Each reads plain keys of a feed or an item. A form with two arguments
  takes the feed and an item, and the item overrides or inherits what the
  channel declares."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.hls :as hls]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-mime :as mime]))

(defn title
  "The title of `x`: the RSS title, or else the iTunes one."
  [x]
  (or (:rss/title x)
      (:itunes/title x)))

(defn description
  "The fullest description of `x`, HTML allowed: content:encoded, then the
  RSS description, then the summary of iTunes or Google Play."
  [x]
  (or (:content/encoded x)
      (:rss/description x)
      (:itunes/summary x)
      (:googleplay/description x)))

(defn- email-name
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

(defn author
  "Who made `x`, with the name picked out of an \"address (Name)\" field.

  It's the iTunes author, the Dublin Core creator, the Google Play author,
  or else the RSS author or managing editor."
  [x]
  (some-> (or (:itunes/author x)
              (:dc/creator x)
              (:googleplay/author x)
              (:rss/author x)
              (:rss/managing-editor x))
          email-name))

(defn link
  "The web page of `x`: its link, or its guid when that's a permalink."
  [x]
  (or (:rss/link x)
      (let [{:keys [content is-perma-link]} (:rss/guid x)]
        (when (and (not (false? is-perma-link))
                   (re-find #"^https?://" (str content)))
          content))))

(def ^:no-doc domain-pattern
  "A domain with an optional port: labels of letters, digits and hyphens
  between dots, and a top-level domain of letters."
  (let [label "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"]
    (re-pattern (str "(?i)(?:" label "\\.)+[a-z]{2,24}(?::[0-9]{1,5})?"))))

(def ^:no-doc file-extension
  "The extension of a file at the end of a name, which a top-level domain
  of letters could be mistaken for."
  (re-pattern (str "(?i)\\.(?:html?|php|aspx?|jsp|cgi|xml|json|rss|txt"
                   "|pdf|mp3|m4a|mp4|jpe?g|png|gif)(?::|$)")))

(defn- domain-first?
  "Whether the URL `s`, which has no scheme, starts with a domain, as in
  example.com/page, rather than a path: its first part is a domain and
  doesn't end in the extension of a file."
  [s]
  (let [head (first (str/split s #"[/?#]" 2))]
    (boolean (and (re-matches domain-pattern head)
                  (not (re-find file-extension head))))))

;; Feeds often write a link as a domain without https://, which a browser
;; would read as a path on the page that shows it
(defn notes-url
  "The URL `s` of a link or an image in show notes, as a listener can
  follow it from `base`, the web page of the show or the episode, or nil.

  An absolute URL stays as it is. A URL that starts with a domain, such as
  example.com/page, or with //, gets https in front, and any other is made
  absolute against `base`."
  [base s]
  (let [t (str/trim (str s))]
    (cond
      (url/scheme t)              s
      (str/starts-with? t "//")   (str "https:" t)
      (domain-first? t)           (str "https://" t)
      base                        (url/absolute-url base t))))

(defn published
  "When `x` was published, as an instant, or nil when it gave no readable
  date."
  [x]
  (let [d (:rss/pub-date x)]
    (when (inst? d) d)))

(defn- purposes
  "The purpose tokens of the podcast:image `image`, in lower case."
  [image]
  (into #{} (comp (map str/lower-case) (remove str/blank?))
        (str/split (str (:purpose image)) #"\s+")))

(defn images
  "Every image that `x` offers, the newest vocabulary first.

  Each is a map with the :href and the :source, one of :podcast, :itunes,
  :googleplay, :rss and :media. A podcast:image also has its :purpose, a
  set of tokens, which is empty when none is declared. The :width,
  :height, :aspect-ratio, :alt and :type are there when declared, e.g.
  video/mp4 for a video."
  [x]
  (concat
   (for [image (:podcast/images x)
         :let  [{:keys [href width height aspect-ratio alt type]} image]
         :when href]
     (cond-> {:href href :source :podcast :purpose (purposes image)}
       width        (assoc :width width)
       height       (assoc :height height)
       aspect-ratio (assoc :aspect-ratio aspect-ratio)
       alt          (assoc :alt alt)
       type         (assoc :type type)))
   (when-let [href (:href (:itunes/image x))]
     [{:href href :source :itunes}])
   (when-let [href (:href (:googleplay/image x))]
     [{:href href :source :googleplay}])
   (when-let [url (:url (:rss/image x))]
     [{:href url :source :rss}])
   (for [{:keys [url width height]} (:media/thumbnails x)
         :when url]
     (cond-> {:href url :source :media}
       width  (assoc :width width)
       height (assoc :height height)))))

(defn- square?
  "Whether `image` is square, or says nothing about its shape."
  [{:keys [aspect-ratio width height] :as image}]
  (cond
    aspect-ratio       (contains? #{"1/1" "1" "1:1"}
                                  (str/replace aspect-ratio #"\s" ""))
    (and width height) (= width height)
    :else              true))

(defn ^:no-doc picture?
  "Whether the `image`, one of those that images gives, is a picture rather
  than a video: its type is an image type, or it has none."
  [{:keys [type]}]
  (or (nil? type)
      (str/starts-with? (str/lower-case type) "image/")))

(defn- artwork?
  "Whether the podcast:image `image` is meant as artwork: it's a picture,
  and it says so, or it has no stated purpose and isn't another shape."
  [image]
  (and (picture? image)
       (or (contains? (:purpose image) "artwork")
           (and (empty? (:purpose image))
                (square? image)))))

(defn- largest
  "The widest of the `images`, the first of those as wide, or nil for none.
  An image without a width counts as the narrowest."
  [images]
  (let [width #(let [w (:width %)] (if (number? w) w 0))]
    (first (sort-by #(- (width %)) images))))

(defn artwork
  "The URL of the square artwork of `x`, or of an `item` of `feed`, with
  the item's own before the feed's.

  It's the largest podcast:image meant as artwork, or else the iTunes
  image, the Google Play image, the RSS image or a media thumbnail."
  ([x]
   (let [candidates (images x)
         podcast?   #(= :podcast (:source %))]
     (:href (or (largest (filter (every-pred podcast? artwork?) candidates))
                (first (remove podcast? candidates))))))
  ([feed item]
   (or (artwork item) (artwork feed))))

;; podcast namespace, image: the purpose poster
(defn poster
  "The URL of the poster of `x`, or of an `item` of `feed`, with the item's
  own before the feed's: the picture that a player shows before a video
  plays, or nil.

  It's the largest podcast:image whose purpose is poster."
  ([x]
   (let [poster? #(and (contains? (:purpose %) "poster") (picture? %))]
     (:href (largest (filter poster? (images x))))))
  ([feed item]
   (or (poster item) (poster feed))))

(defn explicit?
  "Whether `x`, or an `item` of `feed`, is marked explicit, or nil when
  that's unstated.

  The iTunes flag comes first, and then the yes, no or clean of Google
  Play. An item without a flag has that of its feed."
  ([x]
   (let [flag (:itunes/explicit x)]
     (if (boolean? flag)
       flag
       (case (some-> (:googleplay/explicit x) str/lower-case)
         "yes"          true
         ("no" "clean") false
         nil))))
  ([feed item]
   (let [flag (explicit? item)]
     (if (some? flag) flag (explicit? feed)))))

(defn txt
  "The free-form texts of `x` by purpose, from podcast:txt, e.g.:

      {\"applepodcastsverify\" [\"05124\"] nil [\"abc\"]}

  Each purpose is in lower case, and the texts that name no purpose are
  under nil. An item doesn't inherit the texts of its feed."
  [x]
  (let [text?   #(let [s (:content %)]
                   (and (string? s)
                        (not (str/blank? s))))
        texts   (filter text? (:podcast/txt x))
        purpose #(some-> (:purpose %) str/lower-case)]
    (update-vals (group-by purpose texts)
                 #(mapv :content %))))

(defn ai-content?
  "Whether AI-generated content went into `x`, or an `item` of `feed`, by
  its podcast:txt with the purpose ai-content. It's nil when that doesn't
  tell.

  An item without an answer has that of its feed."
  ([x]
   (case (some-> (first (get (txt x) "ai-content")) str/lower-case)
     "true"  true
     "false" false
     nil))
  ([feed item]
   (let [flag (ai-content? item)]
     (if (some? flag) flag (ai-content? feed)))))

(defn- media-contents
  "The Media RSS contents of `item`, in the item and in its media:group."
  [item]
  (concat (:media/contents item) (:contents (:media/group item))))

(defn duration
  "The duration of `item` in seconds, from iTunes or Media RSS, in the item
  or its media:group, or nil when neither gives a number."
  [item]
  (let [d (:itunes/duration item)]
    (if (number? d)
      d
      (some #(let [d (:duration %)] (when (number? d) d))
            (media-contents item)))))

(defn numbering
  "The season and episode of `item`, from the podcast namespace first and
  iTunes second, e.g.:

      {:type \"full\" :season 2 :season-name \"…\" :episode 3
       :episode-display \"Ch.3\"}

  The :type is always there, and the rest as declared. A number that
  didn't read in one namespace leaves the other to answer."
  [item]
  (let [season  (:podcast/season item)
        episode (:podcast/episode item)
        s       (first (filter number? [(:content season)
                                        (:itunes/season item)]))
        e       (first (filter number? [(:content episode)
                                        (:itunes/episode item)]))
        type    (str/lower-case (or (:itunes/episode-type item) "full"))]
    (cond-> {:type type}
      (number? s)        (assoc :season s)
      (:name season)     (assoc :season-name (:name season))
      (number? e)        (assoc :episode e)
      (:display episode) (assoc :episode-display (:display episode)))))

;; Media RSS 1.5.1 (2009), media:content: the medium is image, audio,
;; video, document or executable
(defn- playable?
  "Whether the Media RSS `content` can be played, which an image, a
  document or an executable can't, by its medium or its type."
  [{:keys [medium type]}]
  (not (or (#{"image" "document" "executable"} (some-> medium str/lower-case))
           (some-> type str/lower-case (str/starts-with? "image/")))))

(defn enclosures
  "All that `item` offers to play, the most conventional first.

  The enclosure comes first, then each source of an alternateEnclosure,
  then the Media RSS contents of the item and of its media:group that
  aren't images, documents or executables, e.g. the picture of a blog
  post. Each is a map with the :url and the :source, which is :enclosure,
  :alternate or :media.

  It has what else the feed declares, e.g. the :type, :length, :bitrate
  in bits per second, :title and :default. An alternate also has its
  :integrity, and the contentType of its source as :transport, e.g.
  application/x-bittorrent for a torrent."
  [item]
  (concat
   (when-let [{:keys [url] :as enclosure} (:rss/enclosure item)]
     (when url
       [(assoc enclosure :source :enclosure)]))
   (for [{:keys [sources] :as alternate} (:podcast/alternate-enclosures item)
         {:keys [uri content-type]}      sources
         :when uri]
     (cond-> (-> alternate
                 (dissoc :sources)
                 (assoc :url uri :source :alternate))
       content-type (assoc :transport content-type)))
   ;; Media RSS 1.5.1, media:content: the bitrate is in kilobits per second
   (for [{:keys [url file-size bitrate] :as content} (media-contents item)
         :when (and url (playable? content))]
     (cond-> (-> content (dissoc :file-size) (assoc :source :media))
       file-size         (assoc :length file-size)
       (number? bitrate) (assoc :bitrate (* 1000 bitrate))))))

;; RFC 6381, section 3.3; the sample entries of the MP4 Registration
;; Authority, and the codecs of WebM
(def ^:no-doc codec-kinds
  "The kind of media of each codec that the first part of an RFC 6381
  codecs string names, in lower case."
  (merge (zipmap ["avc1" "avc3" "hvc1" "hev1" "dvh1" "dvhe" "dva1" "dvav"
                  "av01" "vp08" "vp09" "vp8" "vp9" "vvc1" "vvi1" "mp4v"]
                 (repeat :video))
         (zipmap ["mp4a" "opus" "flac" "alac" "ac-3" "ec-3" "ac-4" "mha1"
                  "mhm1" "mp3" "vorbis"]
                 (repeat :audio))))

(defn- codec-list
  "The codecs of the RFC 6381 codecs string `s`, trimmed."
  [s]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split (str s) #",")))

(defn- codec-kind
  "The kind of media of the `codec`, :audio or :video, or nil when it isn't
  known."
  [codec]
  (codec-kinds (first (str/split (str/lower-case codec) #"\."))))

(defn video?
  "Whether the `enclosure` is a video, or nil when nothing tells.

  The `enclosure` is one of those that enclosures gives, or any map of
  the same keys, e.g. a variant of an HLS stream or what format.media
  reads of a file. Its Media RSS medium tells first, then an audio or
  video type, then the codecs, and then a height above 0, which only a
  video has. A stream such as HLS often tells nothing."
  [{:keys [medium type codecs height]}]
  (let [kind  (some-> type str/lower-case (str/split #"/") first)
        kinds (when codecs
                (set (map codec-kind (codec-list codecs))))]
    (cond
      medium                               (= "video" (str/lower-case medium))
      (= "video" kind)                     true
      (= "audio" kind)                     false
      (contains? kinds :video)             true
      (= #{:audio} kinds)                  false
      (and (number? height) (pos? height)) true)))

(defn audio?
  "Whether the `enclosure` is audio, or nil when nothing tells, as video?
  tells it."
  [enclosure]
  (some-> (video? enclosure) not))

(defn media-kinds
  "The kinds of media that `item` offers, a set of :audio and :video, from
  those of its enclosures whose kind is told."
  [item]
  (into #{}
        (keep #(case (video? %) true :video false :audio nil))
        (enclosures item)))

(def ^:no-doc single-codec-types
  "The media types, in lower case, that imply their codec, and so take no
  codecs parameter."
  #{"audio/mpeg" "audio/mp3" "audio/aac" "audio/flac" "audio/x-flac"})

;; RFC 2046, section 4.5.1: the type of data whose format isn't known
(def ^:no-doc unknown-types
  "The media types, in lower case, that say nothing of the format of a
  file."
  #{"application/octet-stream" "binary/octet-stream"})

(defn- format-type
  "The media type that the `type` attribute names, in lower case and without
  its parameters, or nil when it says nothing of the format: there's none,
  it isn't a type and a subtype, or it's one of unknown-types."
  [type]
  (let [essence (mime/essence type)]
    (when-not (unknown-types essence)
      essence)))

(defn- content-type
  "The media type `essence` as a content type of the `kind`, :audio or
  :video, with the `codec`, if any, as its codecs parameter."
  [essence kind codec]
  (let [[top sub] (str/split essence #"/" 2)
        top       (if (#{"audio" "video"} top) (name kind) top)]
    (cond-> (str top "/" sub)
      (and codec (not (single-codec-types essence)))
      (str "; codecs=\"" codec "\""))))

(defn- positive?
  "Whether `x` is a number above 0."
  [x]
  (and (number? x) (pos? x)))

;; Media Capabilities, Working Draft of 9 June 2026: a video needs its
;; size, bitrate and frame rate (2.1.4), the samplerate of audio is in Hz
;; (2.1.8), and a container type names a single codec (2.3.3)
(defn decoding-configuration
  "The MediaDecodingConfiguration of the `enclosure`, one of those that
  enclosures gives, as a map in kebab-case with keyword enum values, e.g.

      {:type :file :audio {:content-type \"audio/mpeg\" :bitrate 128000}}

  It has :video for a video, and for an enclosure whose kind video?
  doesn't tell, and else :audio. A video has the codec of its audio, if
  the codecs name one, as :audio under an audio type. Each content type
  names one codec, of the codecs of the enclosure or of its type, and the
  rest is what the feed declares: the bitrate, the channels and sample
  rate of audio, and the width, height and frame rate of a video, which
  are left out unless the video has them all.

  It's nil when the type says nothing of the format: there's none, or
  it's e.g. application/octet-stream. Such a file is almost always an
  MP3, so a :playable-pred of enclosure can count it as playable."
  [{:keys [type bitrate width height framerate channels samplingrate]
    :as   enclosure}]
  (when-let [essence (format-type type)]
    (let [as-video? (not (false? (video? enclosure)))
          codecs    (codec-list (or (:codecs enclosure)
                                    (second (re-find #"(?i)codecs=\"?([^\";]+)"
                                                     (str type)))))
          of-kind   #(first (filter (comp #{%} codec-kind) codecs))
          own       (or (of-kind (if as-video? :video :audio))
                        (first (remove codec-kind codecs)))
          rate      (when (positive? bitrate) (Math/round (double bitrate)))]
      (if as-video?
        (cond-> {:type  :file
                 :video (cond-> {:content-type (content-type essence :video
                                                             own)}
                          (every? positive? [width height rate framerate])
                          (assoc :width width :height height :bitrate rate
                                 :framerate framerate))}
          (of-kind :audio)
          (assoc :audio {:content-type (content-type essence :audio
                                                     (of-kind :audio))}))
        {:type  :file
         :audio (cond-> {:content-type (content-type essence :audio own)}
                  rate                    (assoc :bitrate rate)
                  (positive? channels)    (assoc :channels (str channels))
                  (positive? samplingrate)
                  (assoc :samplerate
                         (Math/round (* 1000 (double samplingrate)))))}))))

;; ISO/IEC 23009-1; the podcast namespace, source
(def ^:no-doc manifest-types
  "The types of a file that says where the media is rather than holds it,
  in lower case, besides an HLS playlist: a DASH manifest and a torrent."
  #{"application/dash+xml" "application/x-bittorrent"})

(defn downloadable?
  "Whether the `enclosure`, one of those that enclosures gives, is one file
  that a request over HTTP fetches whole, rather than the playlist of a
  stream such as HLS, a torrent or an IPFS address."
  [{:keys [url type transport] :as enclosure}]
  (let [served (some-> (or transport type) str/lower-case)]
    (boolean (and (url/web-url? url)
                  (not (manifest-types served))
                  (not (hls/playlist? enclosure))))))

(defn- language-tag
  "The primary subtag of the language `s` (RFC 5646, section 2.2.1), e.g.
  \"en\" for \"en-US\"."
  [s]
  (first (str/split (str/lower-case (str s)) #"[-_]")))

(defn- language-rank
  "How well `language` serves the `languages` wanted, most wanted first, as
  its position among them.

  A language that matches none comes after them all, and an unknown
  language comes before those that don't match."
  [languages language]
  (let [tag     (language-tag language)
        matches (keep-indexed (fn [i wanted]
                                (when (= tag (language-tag wanted)) i))
                              languages)]
    (cond
      (empty? languages) 0
      (nil? language)    (count languages)
      :else              (or (first matches)
                             (inc (count languages))))))

(defn- type-rank
  "The position of `type` among the preferred `types`, ignoring case and
  parameters such as a charset, or after them all when it isn't among
  them."
  [types type]
  (let [type (mime/essence type)]
    (or (first (keep-indexed (fn [i wanted]
                               (when (= type (str/lower-case wanted)) i))
                             types))
        (count types))))

(defn- source-rank
  "The order of the `source` of enclosures, when nothing else tells them
  apart."
  [source]
  (case source :enclosure 0 :alternate 1 2))

(defn enclosure
  "The one thing to play for `item`: of all that it offers, the one that
  best fits the `opts` below, or nil when none can be played.

  - :languages, the wanted languages, most wanted first, e.g. [\"da\" \"en\"]
  - :video?, true to prefer video and false to prefer audio, for an item
    that offers both
  - :types, the wanted types, most wanted first, e.g.
    [\"audio/opus\" \"audio/mpeg\"]
  - :max-bitrate, in bits per second, e.g. 64000
  - :max-height, in pixels, e.g. 720
  - :playable-pred, a predicate of the enclosures that can be played,
    e.g. audio? for audio alone

  The languages count first, where a candidate that names none ranks after
  the matches and before the mismatches. The kind that :video? prefers
  counts next, where a candidate whose kind isn't told counts as a video,
  since most such candidates are HLS streams of video. Then come the
  types, then the bitrate and the height, where a candidate above the
  :max-bitrate or the :max-height ranks last. Among equals, the enclosure
  comes first, then the default alternate, then the rest, the most
  conventional first."
  ([item]
   (enclosure item nil))
  ([item {:keys [languages types max-bitrate max-height playable-pred]
          prefer-video? :video?
          :or   {playable-pred any?}
          :as   opts}]
   (let [above? (fn [limit n] (and limit (number? n) (> n limit)))
         rank   (fn [{:keys [lang type bitrate height default is-default
                             source]
                      :as   candidate}]
                  (let [over?    (or (above? max-bitrate bitrate)
                                     (above? max-height height))
                        as-video (not (false? (video? candidate)))
                        default? (or (true? default)
                                     (true? is-default))]
                    [(language-rank languages lang)
                     (if (or (nil? prefer-video?)
                             (= prefer-video? as-video))
                       0
                       1)
                     (if types (type-rank types type) 0)
                     (if over? 1 0)
                     (source-rank source)
                     (if default? 0 1)]))]
     (first (sort-by rank (filter playable-pred (enclosures item)))))))

(def ^:no-doc transcript-preference
  "The transcript types in the order that an app is best served by them:
  WebVTT, which has timings and speakers and plays in browsers, then SRT,
  JSON, HTML and plain text."
  ["text/vtt" "application/x-subrip" "application/srt" "text/srt"
   "application/json" "text/html" "text/plain"])

(def ^:no-doc timed-transcript-types
  "The transcript types whose files have timings as a rule."
  #{"text/vtt" "application/x-subrip" "application/srt" "text/srt"
    "application/json"})

(defn- timed-transcript?
  "Whether the transcript reference `ref` has timings: rel=captions, or a
  type that has them as a rule."
  [{:keys [type rel] :as ref}]
  (or (= "captions" (some-> rel str/lower-case))
      (contains? timed-transcript-types (mime/essence type))))

;; Media RSS 1.5.1, sections 5 and 5.22: deeper subtitles come first
(defn transcripts
  "Every transcript and caption file that `item` links to: those of the
  podcast namespace, then the Media RSS subtitles of its media contents,
  its media:group and the item itself.

  A Media RSS subtitle is a map of the :url, the :type and :language when
  declared, the :rel captions, since it's a subtitle or caption file, and
  the :source :media."
  [item]
  (concat
   (:podcast/transcripts item)
   (for [{:keys [href type lang]} (concat (mapcat :subtitles
                                                  (media-contents item))
                                          (:subtitles (:media/group item))
                                          (:media/subtitles item))
         :when href]
     (cond-> {:url href :rel "captions" :source :media}
       type (assoc :type type)
       lang (assoc :language lang)))))

(defn transcript
  "The transcript reference of `item` that an app is best served by, given
  the `feed`, which can be nil, and the `opts` below. It's one of those
  that transcripts gives.

  - :types, the wanted types, by default WebVTT, SRT, JSON, HTML and plain
    text
  - :languages, the wanted languages, most wanted first, e.g. [\"da\" \"en\"]
  - :timed?, whether to prefer rel=captions or a timed type

  The language counts first, the timings second, and the type third. A
  reference that names no language counts as in the feed's language. Without
  a feed, it ranks after the matches and before the mismatches. The result
  is never nil when the item has transcripts."
  ([item]
   (transcript nil item nil))
  ([feed item]
   (transcript feed item nil))
  ([feed item {:keys [types languages timed?]
               :or   {types transcript-preference}
               :as   opts}]
   (let [default-language (:rss/language feed)
         rank             (fn [{:keys [type language] :as ref}]
                            (let [language (or language default-language)
                                  untimed? (and timed?
                                                (not (timed-transcript? ref)))]
                              [(language-rank languages language)
                               (if untimed? 1 0)
                               (type-rank types type)]))]
     (first (sort-by rank (transcripts item))))))

(defn chapters-url
  "The URL of the JSON chapters file that `item` links to, if any."
  [item]
  (:url (:podcast/chapters item)))

(defn embedded-chapters
  "The Podlove chapters embedded in `item` as a chapters document, or nil."
  [item]
  (some-> (:psc/chapters item) chapters/from-psc))

(defn inferred-chapters
  "The chapters listed as timestamps in the show notes of `item`, as a
  chapters document, or nil. They're what to fall back on when the item
  has no other chapters."
  [item]
  (chapters/from-text (description item)))

(defn soundbites
  "The soundbites of `item` in the order of the feed, the parts that its
  creator selected to preview or share it.

  A soundbite is a map with the :start-time and :duration in seconds, and
  the :title when it has one. The podcast namespace leaves the title of a
  soundbite without one to the app, e.g. the title of the item. A
  soundbite is left out when it has no start of zero seconds or more, or
  no duration above zero."
  [item]
  (for [{:keys [start-time duration content]} (:podcast/soundbites item)
        :when (and (number? start-time)
                   (<= 0 start-time)
                   (number? duration)
                   (pos? duration))]
    (cond-> {:start-time start-time :duration duration}
      (and (string? content)
           (not (str/blank? content)))
      (assoc :title content))))

;; podcast namespace, contentLink
(defn content-links
  "The places outside the feed where the content of `item` can be found,
  e.g. a live stream or the video on a platform, in the order of the feed.

  Each is a map with the :href, and the :text that tells the listener
  where it points, when the feed gives one."
  [item]
  (for [{:keys [href content]} (:podcast/content-links item)
        :when href]
    (cond-> {:href href}
      (and (string? content)
           (not (str/blank? content)))
      (assoc :text (str/trim content)))))

(defn people
  "The people credited on `x`, or on an `item` of `feed`, as maps of :name,
  :role, :group, :img and :href.

  The roles and groups are in lower case, with the defaults of
  podcast:person filled in. The people of an item replace those of its
  feed entirely when the item names any, as podcast:person says."
  ([x]
   (for [{:keys [content role group img href]} (:podcast/people x)]
     (cond-> {:name  content
              :role  (str/lower-case (or role "host"))
              :group (str/lower-case (or group "cast"))}
       img  (assoc :img img)
       href (assoc :href href))))
  ([feed item]
   (if (seq (:podcast/people item))
     (people item)
     (people feed))))

(defn- inherited
  "The value of `k` on `item` when it declares one, or else that of `feed`."
  [k feed item]
  (let [v (get item k)]
    (if (or (nil? v)
            (and (coll? v) (empty? v)))
      (get feed k)
      v)))

(defn funding
  "The funding links of `x`, or of an `item` of `feed`, which are the
  item's own or else the feed's."
  ([x] (:podcast/funding x))
  ([feed item] (inherited :podcast/funding feed item)))

(defn license
  "The license of `x`, or of an `item` of `feed`, which is the item's own
  or else the feed's."
  ([x] (:podcast/license x))
  ([feed item] (inherited :podcast/license feed item)))

(defn chat
  "The chat of `x`, or of an `item` of `feed`, which is the item's own or
  else the feed's."
  ([x] (:podcast/chat x))
  ([feed item] (inherited :podcast/chat feed item)))

(defn- social-interact
  "The podcast:socialInteract `interact` with its protocol in lower case, or
  nil when it names no protocol and URI, and isn't the protocol disabled."
  [{:keys [protocol uri] :as interact}]
  (let [protocol (some-> protocol str/lower-case)]
    (when (or (= "disabled" protocol)
              (and protocol uri))
      (-> (select-keys interact [:uri :account-id :account-url :priority])
          (assoc :protocol protocol)))))

(defn social-interacts
  "Where the public comments on `x`, or on an `item` of `feed`, are: the
  root posts of the threads that its podcast:socialInteract elements name.

  Each is a map with the :protocol in lower case and the :uri, and the
  :account-id, :account-url and :priority when the feed declares them. The
  lowest priority number comes first, and those without a priority come
  last.

  When one of them has the protocol \"disabled\", the podcaster wants no
  public comments shown, and the answer is [{:protocol \"disabled\"}]. An
  item without an answer has that of its feed."
  ([x]
   (let [interacts (keep social-interact (:podcast/social-interacts x))
         priority  #(let [p (:priority %)] (if (number? p) p ##Inf))]
     (if (some #(= "disabled" (:protocol %)) interacts)
       [{:protocol "disabled"}]
       (sort-by priority interacts))))
  ([feed item]
   (let [own (social-interacts item)]
     (if (seq own) own (social-interacts feed)))))

(defn locations
  "The locations of `x`, or of an `item` of `feed`, which are the item's
  own or else the feed's."
  ([x] (:podcast/locations x))
  ([feed item] (inherited :podcast/locations feed item)))

(defn value
  "The value block of `x`, or the one that applies to an `item` of `feed`,
  which is the item's own when it declares one. With `type`, only blocks
  of that type count, e.g. \"lightning\"."
  ([x] (first (:podcast/values x)))
  ([feed item] (first (inherited :podcast/values feed item)))
  ([feed item type]
   (let [of-type #(filter (comp #{type} str/lower-case str :type) %)]
     (or (first (of-type (:podcast/values item)))
         (first (of-type (:podcast/values feed)))))))

(def ^:no-doc live-status-order
  "Live items in the order that a listener cares about: on air, then
  upcoming, then over."
  {"live" 0 "pending" 1 "ended" 2})

(defn live-items
  "The live items of `feed`: on air first, then upcoming, the soonest
  first, then ended, the most recent first."
  [feed]
  (let [ms    #(if (inst? %) (inst-ms %) 0)
        order (fn [{:keys [status start]}]
                (let [status (some-> status str/lower-case)
                      rank   (get live-status-order status 3)]
                  [rank (if (= 2 rank) (- (ms start)) (ms start))]))]
    (sort-by order (:podcast/live-items feed))))

(defn live?
  "Whether the `live-item` is on air at `at`: its status says so, or the
  time falls between its start and end, and it hasn't been declared ended."
  [{:keys [status start end] :as live-item} at]
  (let [status (some-> status str/lower-case)]
    (or (= "live" status)
        (and (not= "ended" status)
             (inst? start)
             (inst? at)
             (<= (inst-ms start) (inst-ms at))
             (or (not (inst? end))
                 (< (inst-ms at) (inst-ms end)))))))

(defn medium
  "What the content of `feed` is, in lower case, or \"podcast\" when that's
  unstated."
  [feed]
  (str/lower-case (or (:podcast/medium feed) "podcast")))

(defn list-feed?
  "Whether `feed` is a list of remote items rather than of its own: a
  medium that ends in L, or mixed."
  [feed]
  (let [m (medium feed)]
    (or (= m "mixed")
        (str/ends-with? m "l"))))

(defn complete?
  "Whether `feed` says it will publish no more episodes."
  [feed]
  (or (true? (:itunes/complete feed))
      (true? (:complete (:podcast/update-frequency feed)))))

(defn locked?
  "Whether `feed` forbids other platforms to import it."
  [feed]
  (true? (:content (:podcast/locked feed))))

(defn blocked?
  "Whether `x`, a feed or an item, asks `platform` not to show it.

  The platform is a keyword or one of the slugs of podcast:block, e.g.
  :apple, :google or :spotify. A podcast:block that names the platform
  decides first, then one that names none, and else the legacy
  itunes:block for Apple and googleplay:block for Google."
  [x platform]
  (let [slug    (str/lower-case (name platform))
        blocks  (:podcast/blocks x)
        names?  #(= slug (some-> (:id %) str/lower-case))
        named   (some #(when (names? %) %) blocks)
        general (some #(when (nil? (:id %)) %) blocks)]
    (cond
      named             (true? (:content named))
      general           (true? (:content general))
      (= slug "apple")  (true? (:itunes/block x))
      (= slug "google") (true? (:googleplay/block x))
      :else             false)))

(defn ^:no-doc atom-links
  "The atom:links of `feed` with the link relation `rel`, given in lower
  case, in the order of the feed."
  [feed rel]
  (filter #(= rel (some-> (:rel %) str/lower-case))
          (:atom/links feed)))

(defn self-url
  "The canonical URL of `feed`, from its atom:link rel=self."
  [feed]
  (some :href (atom-links feed "self")))

(defn new-feed-url
  "Where `feed` says that it has moved to, if anywhere, judged by the
  `urls` that it was fetched from.

  A feed that names itself, by its self link or by one of the `urls`,
  hasn't moved, as every feed of Podbean and Substack names itself."
  ([feed]
   (new-feed-url feed []))
  ([feed urls]
   (let [declared (or (:itunes/new-feed-url feed)
                      (:googleplay/new-feed-url feed))
         own      (into #{}
                        (comp (remove nil?) (map guid/normal-url))
                        (cons (self-url feed) urls))]
     (when-not (or (nil? declared) (own (guid/normal-url declared)))
       declared))))

(defn hubs
  "The URLs of the WebSub hubs of `feed`, from its atom:links with
  rel=hub, in the order of the feed."
  [feed]
  (keep :href (atom-links feed "hub")))

(defn next-page
  "The URL of the page with the older items of `feed`, when the feed comes
  in pages as RFC 5005, sections 3 and 4, defines them.

  It's the atom:link rel=next of a paged feed, or else the
  rel=prev-archive of an archived feed. It's as the feed writes it, so it
  can be relative to the URL of the page."
  [feed]
  (let [href #(some :href (atom-links feed %))]
    (or (href "next")
        (href "prev-archive"))))

(defn podroll
  "The shows that `feed` recommends, from its podcast:podroll, in the order
  of the feed.

  Each is a remote item with the :feed-guid, and the :feed-url and :title
  when the feed declares them. An item that names no guid and no URL is
  left out. Where the guid and the URL lead to different feeds, the guid
  wins, since a guid outlives a URL."
  [feed]
  (filter #(or (:feed-guid %) (:feed-url %))
          (:remote-items (:podcast/podroll feed))))

(defn remote-items
  "The remote items of the channel of `feed`, in the order of the feed.

  They're the entries of a list feed, most often items of other feeds, or
  the shows of a publisher feed. Each is a map with the :feed-guid, and
  the :feed-url, :item-guid, :title and :medium when the feed declares
  them. An item that names no guid and no URL is left out."
  [feed]
  (filter #(or (:feed-guid %) (:feed-url %))
          (:podcast/remote-items feed)))

(defn publisher
  "The publisher feed of `feed`, as a remote item, or nil when no remote
  item names a guid or a URL.

  The remote item has the :feed-guid, and the :feed-url and :title when
  the feed declares them. It's the remote item of the podcast:publisher of
  the feed. Some feeds write the publisher without that element, as a
  remote item of the channel with the medium publisher. Such an item is
  the answer when the feed has no podcast:publisher, and is neither a list
  feed nor a publisher feed.

  The podcast namespace, in The Publisher Medium, asks for links both
  ways, so an app trusts the link only when the remote items of the
  publisher feed list `feed`."
  [feed]
  (let [item (:remote-item (:podcast/publisher feed))]
    (cond
      (or (:feed-guid item) (:feed-url item))
      item

      (not (or (list-feed? feed)
               (= "publisher" (medium feed))))
      (some #(when (= "publisher" (some-> (:medium %) str/lower-case)) %)
            (remote-items feed)))))

(defn- category-paths
  "The paths of `categories` and of their subcategories, each below
  `prefix`."
  [prefix categories]
  (mapcat (fn [{:keys [text categories]}]
            (let [path (conj prefix text)]
              (if (seq categories)
                (category-paths path categories)
                [path])))
          categories))

(defn categories
  "The Apple categories of `feed` as paths, subcategory after category,
  e.g.:

      [[\"News\" \"Tech News\"] [\"Technology\"]]"
  [feed]
  (vec (category-paths [] (:itunes/categories feed))))

(defn- serial?
  [feed]
  (= "serial" (some-> (:itunes/type feed) str/lower-case)))

(defn- date-ms
  [item]
  (let [d (:rss/pub-date item)]
    (if (inst? d) (inst-ms d) 0)))

(defn- serial-key
  "Where `item` goes in a serial show, as a sort key.

  The show goes season by season, and a season has its trailer first, then
  the episodes by number, then its bonuses. A bonus that belongs to an
  episode comes right after it. A trailer of the show comes before every
  season, a bonus of the show after them all, and dates settle the rest."
  [item]
  (let [{:keys [type season episode]} (numbering item)
        trailer? (= "trailer" type)
        bonus?   (= "bonus" type)]
    [(cond (and bonus? (nil? season)) ##Inf
           :else                      (or season 0))
     (if trailer? 0 1)
     (if trailer? 0 (or episode ##Inf))
     (if bonus? 1 0)
     (date-ms item)]))

(defn- episodic-key
  "Where `item` goes in an episodic show, as a sort key: trailers first,
  then newest first."
  [item]
  [(if (= "trailer" (:type (numbering item))) 0 1)
   (- (date-ms item))])

(defn listening-key
  "Where `item` falls in the listening order of `feed`, as a key for
  sort-by."
  [feed item]
  ((if (serial? feed) serial-key episodic-key) item))

;; Apple, A Podcaster's Guide to RSS: itunes:type and itunes:episodeType
(defn distinct-items
  "The `items` without those that an item before them has the identity of,
  as guid/item-guid gives it, e.g. an episode that a feed lists twice.
  Items without an identity all stay."
  [items]
  (let [seen (volatile! #{})]
    (filterv (fn [item]
               (let [id (guid/item-guid item)]
                 (or (nil? id)
                     (when-not (contains? @seen id)
                       (vswap! seen conj id)
                       true))))
             items)))

(defn episodes
  "The items of `feed` in listening order, as Apple Podcasts presents them,
  each once, as distinct-items keeps them.

  An episodic show, the default, has the newest first, with the trailers
  ahead. A serial show, as itunes:type says, goes season by season and
  episode by episode from the first. The trailer of a season comes at its
  head, and a bonus right after the episode that it belongs to, or at the
  end of its season. A trailer of the show comes before everything, and a
  bonus of the show after."
  [feed]
  (sort-by (partial listening-key feed) (distinct-items (:rss/items feed))))

(defn seasons
  "The items of `feed` grouped by season, in listening order:

      ({:season 2 :name \"The Sequel\" :items [...]} ...)

  Seasons come in the order that listening reaches them, so newest first
  for an episodic show, and ascending for a serial one. Items without a
  season form a group without one, where their first item falls."
  [feed]
  (let [ordered (episodes feed)
        season  #(:season (numbering %))
        groups  (group-by season ordered)]
    (for [s     (distinct (map season ordered))
          :let  [items (groups s)
                 name  (some (comp :season-name numbering) items)]]
      (cond-> {:items items}
        s    (assoc :season s)
        name (assoc :name name)))))

(defn- of-item
  "The accessor `f` of an item alone, made an accessor of a feed and an
  item."
  [f]
  (fn [_ item] (f item)))

(def ^:no-doc show-properties
  "The accessors of a show by property."
  {:title title :description description :author author :link link
   :published published :images images :artwork artwork :explicit? explicit?
   :txt txt :ai-content? ai-content? :people people :funding funding
   :license license :chat chat :social-interacts social-interacts
   :locations locations :value value :live-items live-items :medium medium
   :list-feed? list-feed? :complete? complete? :locked? locked?
   :new-feed-url new-feed-url :self-url self-url :hubs hubs :podroll podroll
   :remote-items remote-items :publisher publisher :categories categories})

(def ^:no-doc episode-properties
  "The accessors of an episode by property, each of the feed and the item,
  inheriting as the forms with two arguments do."
  (let [of-items {:title title :description description :author author
                  :link link :published published :images images
                  :duration duration :numbering numbering
                  :enclosures enclosures :enclosure enclosure
                  :media-kinds media-kinds :chapters-url chapters-url
                  :embedded-chapters embedded-chapters
                  :soundbites soundbites :content-links content-links
                  :txt txt}
        of-both  {:artwork artwork :poster poster :explicit? explicit?
                  :ai-content? ai-content? :transcript transcript
                  :people people :funding funding :license license
                  :chat chat :social-interacts social-interacts
                  :locations locations :value value}]
    (merge (update-vals of-items of-item) of-both)))

(defn- answer
  "The value `v` of an accessor as a property: a sequence as a vector, and
  nothing when it's nil or empty."
  [v]
  (cond (nil? v)  nil
        (seq? v)  (when (seq v) (vec v))
        (coll? v) (when (seq v) v)
        :else     v))

(defn- answers
  "The properties that the accessors of `table` answer, each called with
  `call`."
  [table call]
  (into {} (keep (fn [[k f]]
                   (when-some [v (answer (call f))]
                     [k v])))
        table))

(defn properties
  "Every property that the accessors answer for `feed`, or for its `item`,
  as one map with plain keys, without those that have no answer.

  For a feed alone, the properties are those of the show, :title to
  :categories. For a feed and an item, they're those of the episode,
  inheriting from the feed as each accessor does. What needs a time or a
  platform is left out, e.g. the next check.

      (merge item (properties feed item))
      ;; => {:rss/title \"…\" :title \"…\" :artwork \"…\" …}

  A map merged like this is for reading, since writing it as a feed would
  take its plain keys with string values as attributes."
  ([feed]
   (answers show-properties #(% feed)))
  ([feed item]
   (answers episode-properties #(% feed item))))

(defn- explains?
  "Whether the accessor `f` gives `expected` from `args` alone."
  [f expected args]
  (= expected (answer (apply f args))))

(defn- from-defaults?
  "Whether `f`, given `arity` empty maps, answers with nothing to read
  from: a default, which no field explains."
  [f arity]
  (some? (answer (apply f (repeat arity {})))))

(defn- explaining-paths
  "The paths into `m` of the keys whose entry alone makes the accessor `f`
  give `expected`, in the order of the keys. A path is `prefix` and the
  key, and `args-fn` makes the arguments of `f` of the map of the entry."
  [f expected m prefix args-fn]
  (for [k     (sort (keys m))
        :when (explains? f expected (args-fn (select-keys m [k])))]
    (conj prefix k)))

(defn- paths-by-property
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

(defn sources
  "Where the properties of `feed`, or of its `item`, were read from.

  For each property that the accessors answer, it's the paths whose value
  alone gives the same answer, e.g. [:rss/title] for a show, and
  [:item :rss/title] or [:feed :itunes/explicit] for an episode. Several
  paths mean that several fields agree. A property put together from more
  than one field, or answered by a default, is left out."
  ([feed]
   (paths-by-property show-properties (properties feed) 1
                      (fn [f expected]
                        (explaining-paths f expected feed [] vector))))
  ([feed item]
   (paths-by-property episode-properties (properties feed item) 2
                      (fn [f expected]
                        (let [paths (partial explaining-paths f expected)]
                          (concat (paths item [:item] #(vector {} %))
                                  (paths feed [:feed] #(vector % {}))))))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast.feed.rss :as rss])
     (def f (rss/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (properties f (first (:rss/items f)))
     (sources f (first (:rss/items f)))
     (artwork f)
     (map :rss/title (episodes f))
     (map #(select-keys (numbering %) [:season :episode]) (:rss/items f))
     (people f (first (:rss/items f)))
     (categories f)
     (podroll f)
     (social-interacts f (first (:rss/items f)))
     #_.))
