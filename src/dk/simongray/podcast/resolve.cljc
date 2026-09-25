(ns dk.simongray.podcast.resolve
  "One answer where the vocabularies give several: the accessors a podcast
  client needs, with the precedence rules written down once.

  Every function reads plain keys off a feed or an item (`x`) and derives
  its answer on the spot; nothing is stored. The two-argument forms take the
  `feed` and an `item` and let the item override or inherit what the channel
  declares the way the specifications say: people replace, value blocks
  override, artwork and funding fall back."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.chapters :as chapters]
            [dk.simongray.podcast.value :as value]))

;;;; Text

(defn title
  "The title of `x`: the RSS title, or the iTunes one when only that exists."
  [x]
  (or (:rss/title x) (:itunes/title x)))

(defn description
  "The fullest description of `x`, HTML allowed: content:encoded, then the
  RSS description, then the iTunes or Google Play summary."
  [x]
  (or (:content/encoded x)
      (:rss/description x)
      (:itunes/summary x)
      (:googleplay/description x)))

(defn- email-name
  "The name in an RSS email field written as \"address (Name)\", else `s`."
  [s]
  (if-let [[_ name] (re-matches #"\s*\S+@\S+\s*\((.+)\)\s*" (str s))]
    name
    s))

(defn author
  "Who made `x`: the iTunes author, the Dublin Core creator, the Google Play
  author, or the RSS author or managing editor, with the name picked out of
  an \"address (Name)\" field."
  [x]
  (some-> (or (:itunes/author x)
              (:dc/creator x)
              (:googleplay/author x)
              (:rss/author x)
              (:rss/managing-editor x))
          email-name))

(defn link
  "The web page for `x`: its link, or its guid when that is a permalink."
  [x]
  (or (:rss/link x)
      (let [{:keys [content is-perma-link]} (:rss/guid x)]
        (when (and (not (false? is-perma-link))
                   (re-find #"^https?://" (str content)))
          content))))

(defn published
  "When `x` was published, as an instant; nil when it gave no readable date."
  [x]
  (let [d (:rss/pub-date x)]
    (when (inst? d) d)))

;;;; Artwork

(defn- purposes
  "The purpose tokens of the podcast:image `image`, lower-cased."
  [image]
  (into #{} (comp (map str/lower-case) (remove str/blank?))
        (str/split (str (:purpose image)) #"\s+")))

(defn images
  "Every image `x` offers, newest vocabulary first: maps with `:href`,
  `:source` (:podcast, :itunes, :googleplay, :rss or :media) and, when
  declared, `:width`, `:height`, `:aspect-ratio`, `:alt` and `:purpose` (a set
  of tokens)."
  [x]
  (concat
   (for [{:keys [href width height aspect-ratio alt] :as image} (:podcast/images x)
         :when href]
     (cond-> {:href href :source :podcast :purpose (purposes image)}
       width        (assoc :width width)
       height       (assoc :height height)
       aspect-ratio (assoc :aspect-ratio aspect-ratio)
       alt          (assoc :alt alt)))
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
  [{:keys [aspect-ratio width height]}]
  (cond
    aspect-ratio       (contains? #{"1/1" "1" "1:1"} (str/replace aspect-ratio #"\s" ""))
    (and width height) (= width height)
    :else              true))

(defn- artwork?
  "Whether the podcast:image `image` is meant as artwork: said so, or has no
  stated purpose and is not another shape."
  [image]
  (or (contains? (:purpose image) "artwork")
      (and (empty? (:purpose image)) (square? image))))

(defn artwork
  "The URL of the square artwork for `x`: the largest podcast:image meant as
  artwork, else the iTunes image, the Google Play image, the RSS image or a
  media thumbnail. With `feed` and `item`, the item's own or else the feed's."
  ([x]
   (let [candidates (images x)
         artworks   (filter #(and (= :podcast (:source %)) (artwork? %)) candidates)]
     (:href (or (first (sort-by #(- (or (:width %) 0)) artworks))
                (first (remove #(= :podcast (:source %)) candidates))))))
  ([feed item]
   (or (artwork item) (artwork feed))))

;;;; Flags and numbers

(defn explicit?
  "Whether `x` is marked explicit: the iTunes flag, else Google Play's
  yes/no/clean; nil when unstated. With `feed` and `item`, the item's flag
  or, when it has none, the feed's."
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

(defn duration
  "The duration of `item` in seconds, from iTunes or Media RSS; nil when
  neither gives a number."
  [item]
  (let [d (:itunes/duration item)]
    (if (number? d)
      d
      (some #(let [d (:duration %)] (when (number? d) d)) (:media/contents item)))))

(defn numbering
  "The season and episode of `item`, from the podcast namespace first and
  iTunes second: `{:type \"full\" :season 2 :season-name \"…\" :episode 3
  :episode-display \"Ch.3\"}`, with `:type` always and the rest as declared."
  [item]
  (let [season  (:podcast/season item)
        episode (:podcast/episode item)
        s       (or (:content season) (:itunes/season item))
        e       (or (:content episode) (:itunes/episode item))]
    (cond-> {:type (str/lower-case (or (:itunes/episode-type item) "full"))}
      (number? s)        (assoc :season s)
      (:name season)     (assoc :season-name (:name season))
      (number? e)        (assoc :episode e)
      (:display episode) (assoc :episode-display (:display episode)))))

;;;; Media

(defn enclosures
  "Everything `item` offers to play, most conventional first: the enclosure,
  then every alternateEnclosure source, then Media RSS contents, as maps
  with `:url`, `:source` (:enclosure, :alternate or :media) and whatever
  else was declared: `:type`, `:length`, `:bitrate`, `:title`, `:default`."
  [item]
  (concat
   (when-let [{:keys [url] :as enclosure} (:rss/enclosure item)]
     (when url
       [(assoc enclosure :source :enclosure)]))
   (for [{:keys [sources] :as alternate} (:podcast/alternate-enclosures item)
         {:keys [uri content-type]}      sources
         :when uri]
     (cond-> (-> alternate
                 (dissoc :sources :integrity)
                 (assoc :url uri :source :alternate))
       content-type (assoc :transport content-type)))
   (for [{:keys [url file-size] :as content} (:media/contents item)
         :when url]
     (cond-> (-> content (dissoc :file-size) (assoc :source :media))
       file-size (assoc :length file-size)))))

(defn enclosure
  "The one thing to play for `item`: its enclosure, else the alternate
  marked default, else the first alternate or media content."
  [item]
  (let [all (enclosures item)]
    (or (first (filter #(= :enclosure (:source %)) all))
        (first (filter #(true? (:default %)) all))
        (first all))))

(def transcript-preference
  "Transcript types in the order a client is best served by them: WebVTT
  (timed, speakers, played by browsers), then SRT, JSON, HTML, plain text."
  ["text/vtt" "application/x-subrip" "application/json" "text/html" "text/plain"])

(defn transcript
  "The transcript of `item` to use: the first type in `preference` (by
  default `transcript-preference`) that the item offers, else its first."
  ([item]
   (transcript item transcript-preference))
  ([item preference]
   (let [refs    (:podcast/transcripts item)
         by-type (group-by (comp str/lower-case str :type) refs)]
     (or (some #(first (by-type %)) preference)
         (first refs)))))

(defn chapters-url
  "The URL of the JSON chapters file `item` links to, if any."
  [item]
  (:url (:podcast/chapters item)))

(defn embedded-chapters
  "The Podlove chapters embedded in `item` as a chapters document, or nil."
  [item]
  (some-> (:psc/chapters item) chapters/from-psc))

;;;; People and the rest the item may inherit

(defn people
  "The people credited on `x`, roles and groups lower-cased with the
  specification's defaults filled in: `{:name :role :group :img :href}`.
  With `feed` and `item`, the item's people replace the feed's entirely when
  the item names any, as the specification says."
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
  "The value of `k` on `item` when it declares one, else the feed's."
  [k feed item]
  (let [v (get item k)]
    (if (or (nil? v) (and (coll? v) (empty? v)))
      (get feed k)
      v)))

(defn funding
  "The funding links of `x`; with `feed` and `item`, the item's or the feed's."
  ([x] (:podcast/funding x))
  ([feed item] (inherited :podcast/funding feed item)))

(defn license
  "The license of `x`; with `feed` and `item`, the item's or the feed's."
  ([x] (:podcast/license x))
  ([feed item] (inherited :podcast/license feed item)))

(defn chat
  "The chat of `x`; with `feed` and `item`, the item's or the feed's."
  ([x] (:podcast/chat x))
  ([feed item] (inherited :podcast/chat feed item)))

(defn locations
  "The locations of `x`; with `feed` and `item`, the item's or the feed's."
  ([x] (:podcast/locations x))
  ([feed item] (inherited :podcast/locations feed item)))

(defn value
  "The value block of `x`; with `feed` and `item`, the block that applies to
  the item, which is its own when it declares one. See
  dk.simongray.podcast.value for the arithmetic."
  ([x] (first (:podcast/values x)))
  ([feed item] (value/effective feed item)))

;;;; The feed as a whole

(defn medium
  "What the content of `feed` is, lower-cased; \"podcast\" when unstated."
  [feed]
  (str/lower-case (or (:podcast/medium feed) "podcast")))

(defn list-feed?
  "Whether `feed` is a list of remote items rather than of its own: a
  medium ending in L, or mixed."
  [feed]
  (let [m (medium feed)]
    (or (= m "mixed") (str/ends-with? m "l"))))

(defn complete?
  "Whether `feed` says it will publish no more episodes."
  [feed]
  (boolean (or (true? (:itunes/complete feed))
               (true? (:complete (:podcast/update-frequency feed))))))

(defn locked?
  "Whether `feed` forbids other platforms to import it."
  [feed]
  (true? (:content (:podcast/locked feed))))

(defn new-feed-url
  "Where `feed` says it has moved to, if anywhere."
  [feed]
  (or (:itunes/new-feed-url feed) (:googleplay/new-feed-url feed)))

(defn self-url
  "The canonical URL of `feed`, from its atom:link rel=self."
  [feed]
  (some #(when (= "self" (some-> (:rel %) str/lower-case)) (:href %))
        (:atom/links feed)))

(defn- category-paths
  [prefix categories]
  (mapcat (fn [{:keys [text categories]}]
            (let [path (conj prefix text)]
              (if (seq categories)
                (category-paths path categories)
                [path])))
          categories))

(defn categories
  "The Apple categories of `feed` as paths, subcategory after category:
  `[[\"News\" \"Tech News\"] [\"Technology\"]]`."
  [feed]
  (vec (category-paths [] (:itunes/categories feed))))

(comment
  (require '[dk.simongray.podcast :as podcast])
  (def f (podcast/parse (slurp "test/resources/feeds/namespace-example.xml")))
  (artwork f)
  (map #(select-keys (numbering %) [:season :episode]) (:rss/items f))
  (people f (first (:rss/items f)))
  (categories f)
  #_.)
