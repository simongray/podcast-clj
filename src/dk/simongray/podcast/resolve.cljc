(ns dk.simongray.podcast.resolve
  "One answer where the vocabularies give several: the accessors a podcast
  client needs, each reading plain keys off a feed or an item (`x`). The
  two-argument forms take the `feed` and an `item` and let the item override
  or inherit what the channel declares. `properties` gathers every answer
  into one map and `sources` says which fields gave them."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.time.rrule :as rrule]
            [dk.simongray.podcast.value :as value]))

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
  "The name in an RSS email field written as \"address (Name)\", else `s`.
  The field is cut at its first parenthesis rather than matched whole: one
  pattern for all of it backtracks quadratically over a long address."
  [s]
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

(defn- purposes
  "The purpose tokens of the podcast:image `image`, lower-cased."
  [image]
  (into #{} (comp (map str/lower-case) (remove str/blank?))
        (str/split (str (:purpose image)) #"\s+")))

(defn images
  "Every image `x` offers, newest vocabulary first: maps with `:href`,
  `:source` (:podcast, :itunes, :googleplay, :rss or :media), `:purpose` (a
  set of tokens, empty when none is declared) for a podcast:image, and
  `:width`, `:height`, `:aspect-ratio` and `:alt` when declared."
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
         artworks   (filter #(and (= :podcast (:source %)) (artwork? %)) candidates)
         width      #(let [w (:width %)] (if (number? w) w 0))]
     (:href (or (first (sort-by #(- (width %)) artworks))
                (first (remove #(= :podcast (:source %)) candidates))))))
  ([feed item]
   (or (artwork item) (artwork feed))))

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

(defn txt
  "The free-form texts of `x` by purpose, from podcast:txt, as `{purpose
  [text ...]}`.

  Each purpose is in lower case, and the texts that name no purpose are
  under nil. An example is `{\"applepodcastsverify\" [\"05124\"] nil
  [\"abc\"]}`. An item does not inherit the texts of its feed, except
  through `ai-content?`."
  [x]
  (let [texts (filter #(let [s (:content %)] (and (string? s) (not (str/blank? s))))
                      (:podcast/txt x))]
    (update-vals (group-by #(some-> (:purpose %) str/lower-case) texts)
                 #(mapv :content %))))

(defn ai-content?
  "Whether AI-generated content went into `x`, as its podcast:txt with the
  purpose ai-content tells. Nil when it does not tell.

  With `feed` and `item`, the answer is that of the item. When the item
  has none, it is that of the feed, as for `explicit?`."
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
  or its media:group; nil when neither gives a number."
  [item]
  (let [d (:itunes/duration item)]
    (if (number? d)
      d
      (some #(let [d (:duration %)] (when (number? d) d))
            (media-contents item)))))

(defn numbering
  "The season and episode of `item`, from the podcast namespace first and
  iTunes second: `{:type \"full\" :season 2 :season-name \"…\" :episode 3
  :episode-display \"Ch.3\"}`, with `:type` always and the rest as declared.
  A number that did not read in one namespace leaves the other to answer."
  [item]
  (let [season  (:podcast/season item)
        episode (:podcast/episode item)
        s       (first (filter number? [(:content season) (:itunes/season item)]))
        e       (first (filter number? [(:content episode) (:itunes/episode item)]))]
    (cond-> {:type (str/lower-case (or (:itunes/episode-type item) "full"))}
      (number? s)        (assoc :season s)
      (:name season)     (assoc :season-name (:name season))
      (number? e)        (assoc :episode e)
      (:display episode) (assoc :episode-display (:display episode)))))

(defn enclosures
  "All that `item` offers to play, the most conventional first.

  The enclosure comes first, then each source of an alternateEnclosure,
  then the Media RSS contents of the item and of its media:group. Each is
  a map with `:url` and `:source`, which is :enclosure, :alternate or
  :media. It has what else the feed declares: `:type`, `:length`,
  `:bitrate`, `:title`, `:default`, and the `:integrity` of an alternate.
  dk.simongray.podcast.format.media/verify! checks the integrity."
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
   (for [{:keys [url file-size] :as content} (media-contents item)
         :when url]
     (cond-> (-> content (dissoc :file-size) (assoc :source :media))
       file-size (assoc :length file-size)))))

(defn- type-rank
  "The position of `type` among the preferred `types`, case ignored; after
  them all when it is not among them."
  [types type]
  (or (first (keep-indexed (fn [i wanted] (when (= (str/lower-case wanted) (str/lower-case (str type))) i))
                           types))
      (count types)))

(defn- source-rank
  "The order of `enclosures` sources when nothing else tells them apart."
  [source]
  (case source :enclosure 0 :alternate 1 2))

(defn enclosure
  "The one thing to play for `item`: the candidate of `enclosures` that best
  fits `opts`, types first, then bitrate, a candidate above `:max-bitrate`
  ranking last; among equals the enclosure first, then the default
  alternate, then the rest as `enclosures` lists them.

      {:types       [\"audio/opus\" \"audio/mpeg\"] ; most wanted first
       :max-bitrate 64000}                        ; bits per second"
  ([item]
   (enclosure item nil))
  ([item {:keys [types max-bitrate]}]
   (let [rank (fn [{:keys [type bitrate default is-default source]}]
                [(if types (type-rank types type) 0)
                 (if (and max-bitrate (number? bitrate) (> bitrate max-bitrate)) 1 0)
                 (source-rank source)
                 (if (or (true? default) (true? is-default)) 0 1)])]
     (first (sort-by rank (enclosures item))))))

(def ^:no-doc transcript-preference
  "Transcript types in the order a client is best served by them: WebVTT
  (timed, speakers, played by browsers), then SRT, JSON, HTML, plain text."
  ["text/vtt" "application/x-subrip" "application/srt" "text/srt"
   "application/json" "text/html" "text/plain"])

(def timed-transcript-types
  "Transcript types whose files carry timings as a rule."
  #{"text/vtt" "application/x-subrip" "application/srt" "text/srt" "application/json"})

(defn- language-tag
  "The primary subtag of the language `s`: \"en\" for \"en-US\"."
  [s]
  (first (str/split (str/lower-case (str s)) #"[-_]")))

(defn- language-rank
  "How well `language` serves the `languages` wanted, most wanted first:
  its position among them, or after them all when it matches none, and
  before the mismatches when it is unknown."
  [languages language]
  (cond
    (empty? languages) 0
    (nil? language)    (count languages)
    :else              (or (first (keep-indexed (fn [i wanted]
                                                  (when (= (language-tag wanted) (language-tag language)) i))
                                                languages))
                           (inc (count languages)))))

(defn- timed-transcript?
  "Whether the transcript reference carries timings: rel=captions, or a type
  that does as a rule."
  [{:keys [type rel]}]
  (or (= "captions" (some-> rel str/lower-case))
      (contains? timed-transcript-types (str/lower-case (str type)))))

(defn transcript
  "The transcript reference of `item` a client is best served by, given the
  `feed` (nil for none) and `opts`: language first, timings second, type
  third. A reference naming no language counts as in the feed's language,
  or without a feed ranks after the matches and before the mismatches.
  Something is always returned when the item has transcripts at all.

      {:types     [\"text/vtt\" ...] ; default transcript-preference
       :languages [\"da\" \"en\"]    ; most wanted first
       :timed?    true}              ; prefer rel=captions or a timed type"
  ([item]
   (transcript nil item nil))
  ([feed item]
   (transcript feed item nil))
  ([feed item {:keys [types languages timed?] :or {types transcript-preference}}]
   (let [default-language (:rss/language feed)
         rank             (fn [{:keys [type language] :as ref}]
                            [(language-rank languages (or language default-language))
                             (if (and timed? (not (timed-transcript? ref))) 1 0)
                             (type-rank types type)])]
     (first (sort-by rank (:podcast/transcripts item))))))

(defn chapters-url
  "The URL of the JSON chapters file `item` links to, if any."
  [item]
  (:url (:podcast/chapters item)))

(defn embedded-chapters
  "The Podlove chapters embedded in `item` as a chapters document, or nil."
  [item]
  (some-> (:psc/chapters item) chapters/from-psc))

(defn inferred-chapters
  "The chapters listed as timestamps in the show notes of `item`, as a
  chapters document, or nil; see `chapters/from-text`. What to fall back on
  when `chapters-url` and `embedded-chapters` give nothing."
  [item]
  (chapters/from-text (description item)))

(defn soundbites
  "The soundbites of `item`, in the order of the feed. They are the parts
  that its creator selected to preview or share it.

  A soundbite is a map with `:start-time` and `:duration` in seconds, and
  `:title` when it has one. The specification leaves the title of a
  soundbite without one to the app, such as the title of the item. A
  soundbite is left out when it has no start of zero seconds or more, or
  no duration above zero."
  [item]
  (for [{:keys [start-time duration content]} (:podcast/soundbites item)
        :when (and (number? start-time) (<= 0 start-time)
                   (number? duration) (pos? duration))]
    (cond-> {:start-time start-time :duration duration}
      (and (string? content) (not (str/blank? content))) (assoc :title content))))

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

(defn social-interacts
  "Where the public comments on `x` are: the root posts of the threads that
  its podcast:socialInteract elements name.

  Each is a map with `:protocol` in lower case and `:uri`. It has
  `:account-id`, `:account-url` and `:priority` when the feed declares
  them. The lowest priority number comes first, and the maps without a
  priority come last.

  When one of them has the protocol \"disabled\", the podcaster wants no
  public comments shown. The answer is then `[{:protocol \"disabled\"}]`.
  With `feed` and `item`, the answer is that of the item. When the item
  gives none, it is that of the feed."
  ([x]
   (let [interacts (for [{:keys [protocol uri] :as interact} (:podcast/social-interacts x)
                         :let  [protocol (some-> protocol str/lower-case)]
                         :when (or (= "disabled" protocol) (and protocol uri))]
                     (-> (select-keys interact [:uri :account-id :account-url :priority])
                         (assoc :protocol protocol)))]
     (if (some #(= "disabled" (:protocol %)) interacts)
       [{:protocol "disabled"}]
       (sort-by #(let [p (:priority %)] (if (number? p) p ##Inf)) interacts))))
  ([feed item]
   (let [own (social-interacts item)]
     (if (seq own) own (social-interacts feed)))))

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

(def ^:no-doc live-status-order
  "Live items in the order a listener cares about: on air, then upcoming,
  then over."
  {"live" 0 "pending" 1 "ended" 2})

(defn live-items
  "The live items of `feed`: on air first, then upcoming soonest first, then
  ended most recent first."
  [feed]
  (let [ms #(if (inst? %) (inst-ms %) 0)]
    (sort-by (fn [{:keys [status start]}]
               (let [rank (get live-status-order (some-> status str/lower-case) 3)]
                 [rank (if (= 2 rank) (- (ms start)) (ms start))]))
             (:podcast/live-items feed))))

(defn live?
  "Whether the live item `live-item` is on air at `at`: its status says so,
  or `at` falls between its start and end and it has not been declared
  ended."
  [{:keys [status start end] :as live-item} at]
  (let [status (some-> status str/lower-case)]
    (or (= "live" status)
        (and (not= "ended" status)
             (inst? start) (inst? at)
             (<= (inst-ms start) (inst-ms at))
             (or (not (inst? end)) (< (inst-ms at) (inst-ms end)))))))

(defn medium
  "What the content of `feed` is, lower-cased; \"podcast\" when unstated."
  [feed]
  (str/lower-case (or (:podcast/medium feed) "podcast")))

(defn list-feed?
  "Whether `feed` is a list of remote items rather than of its own: a
  medium ending in L, or mixed. `remote-items` gives the list."
  [feed]
  (let [m (medium feed)]
    (or (= m "mixed") (str/ends-with? m "l"))))

(defn complete?
  "Whether `feed` says it will publish no more episodes."
  [feed]
  (or (true? (:itunes/complete feed))
      (true? (:complete (:podcast/update-frequency feed)))))

(defn- dates
  "The instants of the items of `feed` dated no later than `now`, newest
  first, as milliseconds. An item dated after it is an episode scheduled
  ahead, or a mistake, and neither tells when the feed changes."
  [feed now]
  (->> (:rss/items feed)
       (keep :rss/pub-date)
       (filter inst?)
       (map inst-ms)
       (remove #(> % now))
       (sort >)))

(defn next-episode
  "When `feed` says its next episode is due after `at`: the first instant
  after `at` that the recurrence rule of podcast:updateFrequency produces,
  anchored at its dtstart or, failing that, at the date of the latest item
  published by `at`, or at `at`, on the days of UTC. Nil when the feed is
  complete, has no rule, or has one the library does not evaluate (see
  `rrule/occurrences`)."
  [feed at]
  (let [{:keys [rrule dtstart]} (:podcast/update-frequency feed)
        latest (some-> (first (dates feed (inst-ms at))) time/->inst)]
    (when (and (map? rrule) (not (complete? feed)))
      (rrule/next-occurrence rrule (or (when (inst? dtstart) dtstart) latest at) at))))

(def ^:no-doc syndication-periods
  "Milliseconds in each period sy:updatePeriod may name."
  {"hourly" 3600000 "daily" 86400000 "weekly" 604800000 "monthly" 2629800000 "yearly" 31557600000})

(defn- median
  [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (if (odd? n)
      (v (quot n 2))
      (quot (+ (v (dec (quot n 2))) (v (quot n 2))) 2))))

(defn- cadence
  "The median gap in milliseconds between the ten most recent items of
  `feed` dated no later than `now`; nil when fewer than two of them differ
  in date."
  [feed now]
  (let [recent (take 10 (dates feed now))
        gaps   (filter pos? (map - recent (rest recent)))]
    (when (seq gaps)
      (median gaps))))

(defn next-check
  "When to look at `feed` again for something new, judged at `at`. Nil when
  the feed is complete, or when nothing gives a time.

  The time is the first of these that the feed gives:

  - the next occurrence of its update-frequency rule
  - `at` plus its ttl
  - `at` plus its syndication period divided by its syndication frequency
  - the date of its latest item plus the median gap between its recent
    items, or `at` plus that gap when that time is past

  Items dated after `at` do not count. A feed with fewer than two dated
  items has no gap. The time is at most a year after `at`, the longest
  period that a feed can name.

  `opts` can give `:not-before`, the earliest instant that the server
  allows, such as the `:not-before` of `dk.simongray.podcast/fetch!`. The
  answer is then no earlier than that instant, also when the instant is
  more than a year after `at`. For a feed that gives no time, the answer
  is that instant."
  ([feed at]
   (next-check feed at {}))
  ([feed at {:keys [not-before] :as opts}]
   {:pre [(map? opts)]}
   (when-not (complete? feed)
     (let [now   (inst-ms at)
           ttl   (:rss/ttl feed)
           due   (or (some-> (next-episode feed at) inst-ms)
                     ;; in floating point, so that an absurd ttl cannot overflow
                     (when (and (number? ttl) (pos? ttl))
                       (+ now (* 60000.0 ttl)))
                     (when-let [period (syndication-periods (some-> (:sy/update-period feed) str/lower-case))]
                       (let [frequency (:sy/update-frequency feed)]
                         (+ now (quot period (if (and (number? frequency) (pos? frequency)) frequency 1)))))
                     (when-let [gap (cadence feed now)]
                       (let [due (+ (first (dates feed now)) gap)]
                         (if (> due now) due (+ now gap)))))
           due   (some-> due (min (+ now (syndication-periods "yearly"))))
           floor (some-> not-before inst-ms)]
       (when-let [t (if (and due floor) (max due floor) (or due floor))]
         (time/->inst (long t)))))))

(defn locked?
  "Whether `feed` forbids other platforms to import it."
  [feed]
  (true? (:content (:podcast/locked feed))))

(defn blocked?
  "Whether `x`, a feed or an item, asks `platform` not to show it, the
  platform being a keyword or one of the slugs of `podcast:block`, such as
  :apple, :google or :spotify. A podcast:block naming the platform decides
  first, then one naming none, and failing both the legacy itunes:block
  for Apple and googleplay:block for Google."
  [x platform]
  (let [slug    (str/lower-case (name platform))
        blocks  (:podcast/blocks x)
        named   (some #(when (= slug (some-> (:id %) str/lower-case)) %) blocks)
        general (some #(when (nil? (:id %)) %) blocks)]
    (cond
      named             (true? (:content named))
      general           (true? (:content general))
      (= slug "apple")  (true? (:itunes/block x))
      (= slug "google") (true? (:googleplay/block x))
      :else             false)))

(defn new-feed-url
  "Where `feed` says it has moved to, if anywhere."
  [feed]
  (or (:itunes/new-feed-url feed) (:googleplay/new-feed-url feed)))

(defn self-url
  "The canonical URL of `feed`, from its atom:link rel=self."
  [feed]
  (some #(when (= "self" (some-> (:rel %) str/lower-case)) (:href %))
        (:atom/links feed)))

(defn next-page
  "The URL of the page with the older items of `feed`, when the feed comes
  in pages as RFC 5005 defines them.

  It is the atom:link rel=next of a paged feed, or else the
  rel=prev-archive of an archived feed. It is as the feed writes it, so it
  can be relative to the URL of the page."
  [feed]
  (let [href (fn [rel] (some #(when (= rel (some-> (:rel %) str/lower-case)) (:href %))
                             (:atom/links feed)))]
    (or (href "next") (href "prev-archive"))))

(defn podroll
  "The shows that `feed` recommends, from its podcast:podroll, in the order
  of the feed.

  Each is a remote item with `:feed-guid`, and with `:feed-url` and
  `:title` when the feed declares them. An item that names no guid and no
  URL is left out. Where the guid and the URL lead to different feeds, the
  guid wins, because a guid outlives a URL.
  `directory.podcastindex/remote-item` looks up the guid."
  [feed]
  (filter #(or (:feed-guid %) (:feed-url %))
          (:remote-items (:podcast/podroll feed))))

(defn remote-items
  "The remote items of the channel of `feed`, in the order of the feed.

  They are the entries of a list feed, most often items of other feeds,
  or the shows of a publisher feed. Each is a map with `:feed-guid`. It
  has `:feed-url`, `:item-guid`, `:title` and `:medium` when the feed
  declares them. An item that names no guid and no URL is left out."
  [feed]
  (filter #(or (:feed-guid %) (:feed-url %))
          (:podcast/remote-items feed)))

(defn publisher
  "The publisher feed of `feed`, as a remote item with `:feed-guid`, and
  with `:feed-url` and `:title` when the feed declares them. Nil when no
  remote item names a guid or a URL.

  It is the remote item of the podcast:publisher of the feed. Some feeds
  write the publisher without that element, as a remote item of the
  channel with the medium publisher. So such an item is the answer when
  the feed has no podcast:publisher, and is no list feed and no publisher
  feed.

  The specification asks an app to trust the link only when the
  `remote-items` of the publisher feed list `feed`."
  [feed]
  (let [item (:remote-item (:podcast/publisher feed))]
    (cond
      (or (:feed-guid item) (:feed-url item))
      item

      (not (or (list-feed? feed) (= "publisher" (medium feed))))
      (some #(when (= "publisher" (some-> (:medium %) str/lower-case)) %)
            (remote-items feed)))))

(defn- category-paths
  "The paths of `categories` and of their subcategories, each below `prefix`."
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

(defn- serial?
  [feed]
  (= "serial" (some-> (:itunes/type feed) str/lower-case)))

(defn- date-ms
  [item]
  (let [d (:rss/pub-date item)]
    (if (inst? d) (inst-ms d) 0)))

(defn- serial-key
  "Where `item` goes in a serial show: season by season, a season's trailer
  first, then the episodes by number with a bonus right after the episode
  it belongs to, then the season's bonuses; a show trailer before every
  season, a show bonus after them all, and dates settle the rest."
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
  "Where `item` goes in an episodic show: trailers first, then newest first."
  [item]
  [(if (= "trailer" (:type (numbering item))) 0 1)
   (- (date-ms item))])

(defn listening-key
  "Where `item` falls in the listening order of `feed`, as a key for
  `sort-by`; `episodes` is the items of the feed sorted by it."
  [feed item]
  ((if (serial? feed) serial-key episodic-key) item))

(defn episodes
  "The items of `feed` in listening order, as Apple Podcasts presents them:
  newest first with the trailers ahead for an episodic show, the default;
  for a serial show (itunes:type), season by season and episode by episode
  from the first, a season's trailer at its head, a bonus right after the
  episode it belongs to or at the end of its season, a show trailer before
  everything and a show bonus after."
  [feed]
  (sort-by (partial listening-key feed) (:rss/items feed)))

(defn seasons
  "The items of `feed` grouped by season, in listening order:

      ({:season 2 :name \"The Sequel\" :items [...]} ...)

  Seasons come in the order `episodes` reaches them, so newest first for an
  episodic show and ascending for a serial one; items without a season form
  a group without one, where their first item falls."
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
  "`f`, an accessor of an item alone, as one of a feed and an item."
  [f]
  (fn [_ item] (f item)))

(def ^:no-doc show-properties
  "The accessors of a show by property, for `properties` and `sources`."
  {:title title :description description :author author :link link
   :published published :images images :artwork artwork :explicit? explicit?
   :txt txt :ai-content? ai-content? :people people :funding funding
   :license license :chat chat :social-interacts social-interacts
   :locations locations :value value :live-items live-items :medium medium
   :list-feed? list-feed? :complete? complete? :locked? locked?
   :new-feed-url new-feed-url :self-url self-url :podroll podroll
   :remote-items remote-items :publisher publisher :categories categories})

(def ^:no-doc episode-properties
  "The accessors of an episode by property, each of the feed and the item,
  inheriting as the two-argument forms do."
  (merge (update-vals {:title title :description description :author author
                       :link link :published published :images images
                       :duration duration :numbering numbering
                       :enclosures enclosures :enclosure enclosure
                       :chapters-url chapters-url :embedded-chapters embedded-chapters
                       :soundbites soundbites :txt txt}
                      of-item)
         {:artwork artwork :explicit? explicit? :ai-content? ai-content?
          :transcript transcript :people people :funding funding :license license
          :chat chat :social-interacts social-interacts :locations locations
          :value value}))

(defn- answer
  "The value of an accessor as a property: a sequence as a vector, nothing
  when it is nil or empty."
  [v]
  (cond (nil? v)  nil
        (seq? v)  (when (seq v) (vec v))
        (coll? v) (when (seq v) v)
        :else     v))

(defn- answers
  "The properties answered by the accessors of `table`, each called with
  `call`."
  [table call]
  (into {} (keep (fn [[k f]]
                   (when-some [v (answer (call f))]
                     [k v])))
        table))

(defn properties
  "Every property `resolve` answers, as one map with plain keys and the
  unanswered left out: for `feed` alone the show's, `:title` to
  `:categories`; for `feed` and `item` the episode's, inheriting from the
  feed as each accessor does. What needs a time or a platform, `next-check`,
  `live?` and `blocked?`, is asked on its own. A map merged so is one to
  read: `emit` would take its plain keys with string values for
  attributes.

      (merge item (properties feed item))
      ;; => {:rss/title \"…\" :title \"…\" :artwork \"…\" …}"
  ([feed]
   (answers show-properties #(% feed)))
  ([feed item]
   (answers episode-properties #(% feed item))))

(defn- explains?
  "Whether the accessor `f` gives `expected` from `args` alone."
  [f expected args]
  (= expected (answer (apply f args))))

(defn- from-defaults?
  "Whether `f` answers with nothing to read from: a default, which no
  field explains."
  [f arity]
  (some? (answer (apply f (repeat arity {})))))

(defn sources
  "Where the properties of `feed`, or of its `item`, were read from: for
  each property `properties` answers, the paths whose value alone gives the
  same answer, `[:rss/title]` for a show and `[:item :rss/title]` or
  `[:feed :itunes/explicit]` for an episode. Several paths mean several
  fields agree; a property put together from more than one field, or
  answered by a default, is left out."
  ([feed]
   (let [full (properties feed)]
     (into {} (for [[k f] show-properties
                    :when (and (contains? full k) (not (from-defaults? f 1)))
                    :let  [paths (vec (for [key (sort (keys feed))
                                            :when (explains? f (get full k) [(select-keys feed [key])])]
                                        [key]))]
                    :when (seq paths)]
                [k paths]))))
  ([feed item]
   (let [full (properties feed item)]
     (into {} (for [[k f] episode-properties
                    :when (and (contains? full k) (not (from-defaults? f 2)))
                    :let  [expected (get full k)
                           paths    (vec (concat (for [key (sort (keys item))
                                                       :when (explains? f expected [{} (select-keys item [key])])]
                                                   [:item key])
                                                 (for [key (sort (keys feed))
                                                       :when (explains? f expected [(select-keys feed [key]) {}])]
                                                   [:feed key])))]
                    :when (seq paths)]
                [k paths])))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast :as podcast])
     (def f (podcast/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (properties f (first (:rss/items f)))
     (sources f (first (:rss/items f)))
     (artwork f)
     (map :rss/title (episodes f))
     (next-check f (java.util.Date.))
     (map #(select-keys (numbering %) [:season :episode]) (:rss/items f))
     (people f (first (:rss/items f)))
     (categories f)
     (podroll f)
     (social-interacts f (first (:rss/items f)))
     #_.))
