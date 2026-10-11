(ns dk.simongray.podcast.feed.validate
  "Validation of a feed map by the tag registry.

  A validation reports what the specifications say is wrong:

  - a required attribute that's missing
  - a value outside an enumeration, or one that doesn't coerce to its type
  - a deprecated element
  - a broken structure, e.g. an alternate enclosure without a source
  - an element that the registry doesn't know

  The :requirements option adds the required and recommended elements of
  a directory or a standard: :psp-1 for the baseline of the Podcast
  Standards Project, and :apple for Apple Podcasts.

  A diagnostic has the shape of those of the parse report: an :id, a
  :level of :error, :warning or :info, a :path into the feed, and a
  :message. Validation never throws on a value, since a shape that the
  element doesn't take is a diagnostic like any other."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.names :as names]
            [dk.simongray.qname-hiccup :as xml]))

(defn- diagnostic
  [id level path message & {:as details}]
  (merge {:id id :level level :path path :message message} details))

(defn- enum-ok?
  [values v]
  (let [v (str/lower-case (str v))]
    (boolean (some #(= v (str/lower-case %)) values))))

;; RFC 3986, section 3.1
(defn- absolute-url?
  "Whether `v` is a URL that starts with its scheme."
  [v]
  (and (string? v)
       (boolean (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" v))))

(defn- typed-diagnostics
  "The diagnostics of the value `v` of `type` at `path`, by the enumeration
  `values` and whether `https?` is expected.

  There's one for each of these:

  - a value that isn't of its type
  - a negative number
  - a value outside the enumeration
  - an http URL where https is expected"
  [type values v path https?]
  (cond
    (and (contains? coerce/coerced-types type)
         (not (coerce/of-type? type v)))
    [(diagnostic :validate/invalid-value :error path
                 (str "value " (pr-str v) " is not a valid " (name type)))]

    (and (#{:integer :decimal :duration} type)
         (neg? v))
    [(diagnostic :validate/invalid-value :error path
                 (str "value " (pr-str v)
                      " is below zero, as no quantity in a feed is"))]

    (and (= type :enum)
         values
         (not (enum-ok? values v)))
    [(diagnostic :validate/invalid-enum :warning path
                 (str "value " (pr-str v) " is not one of "
                      (str/join ", " (sort values))))]

    (and (= type :url)
         (string? v)
         (not (absolute-url? v)))
    [(diagnostic :validate/invalid-url :warning path
                 (str "not an absolute URL: " (pr-str v)))]

    (and (= type :url)
         https?
         (string? v)
         (str/starts-with? (str/lower-case v) "http://"))
    [(diagnostic :validate/http-url :info path
                 (str "the podcast namespace requires https for hypertext"
                      " resources"))]

    :else
    []))

(defn- attr-diagnostics
  "The diagnostics of the attributes of the map value `m` of `entry` at
  `path`. A required attribute that's blank is missing, e.g. url=\"\"."
  [entry m path]
  (let [https? (= "podcast" (namespace (:tag entry)))]
    (mapcat (fn [[k {:keys [type required values]}]]
              (let [k'     (names/attr-key k)
                    path'  (conj path k')
                    type   (or type :text)
                    v      (get m k')
                    blank? (and (string? v) (str/blank? v))]
                (if (and (contains? m k')
                         (not (and required blank?)))
                  (typed-diagnostics type values v path' https?)
                  (when required
                    [(diagnostic :validate/missing-attribute :error path'
                                 (str "required attribute " (name k)
                                      " is missing"))]))))
            (:attrs entry))))

(defn- content-diagnostics
  [entry v path]
  (typed-diagnostics (spec/content-type entry) (:values entry) v path false))

(defn- count-children
  [m k]
  (count (get m k)))

;; RFC 8216, section 4: the media types of an HLS playlist
(def ^:no-doc playlist-types
  "The media types of a playlist of audio or video, which an enclosure can
  name for a stream."
  #{"application/vnd.apple.mpegurl" "application/x-mpegurl"})

(defn- media-type?
  "Whether the media type `s` is of audio, of video or of a playlist of
  either, whatever its case and parameters."
  [s]
  (let [t (-> (str/split s #";" 2) first str/trim str/lower-case)]
    (or (str/starts-with? t "audio/")
        (str/starts-with? t "video/")
        (contains? playlist-types t))))

(def structural-rules
  "Checks by tag of the model value of an element, each a function of the
  value and its path that returns diagnostics."
  {:podcast/alternateEnclosure
   (fn [v path]
     (when (zero? (count-children v :sources))
       [(diagnostic :validate/missing-child :error path
                    "an alternateEnclosure needs at least one source")]))

   :podcast/value
   (fn [v path]
     (when (zero? (count-children v :recipients))
       [(diagnostic :validate/missing-child :error path
                    "a value block needs at least one valueRecipient")]))

   :podcast/valueTimeSplit
   (fn [v path]
     (let [recipients (count-children v :recipients)
           remote     (if (:remote-item v) 1 0)]
       (when-not (or (and (pos? recipients) (zero? remote))
                     (and (zero? recipients) (= 1 remote)))
         [(diagnostic :validate/invalid-structure :error path
                      (str "a valueTimeSplit holds either valueRecipients"
                           " or exactly one remoteItem"))])))

   :podcast/podroll
   (fn [v path]
     (when (zero? (count-children v :remote-items))
       [(diagnostic :validate/missing-child :error path
                    "a podroll needs at least one remoteItem")]))

   :podcast/publisher
   (fn [v path]
     (if-let [remote (:remote-item v)]
       (when-not (= "publisher" (some-> (:medium remote) str/lower-case))
         [(diagnostic :validate/invalid-structure :error
                      (conj path :remote-item)
                      (str "the remoteItem of a publisher element needs"
                           " medium=publisher"))])
       [(diagnostic :validate/missing-child :error path
                    (str "a publisher element needs exactly one remoteItem"
                         " with medium=publisher"))]))

   :podcast/liveItem
   (fn [v path]
     (when (zero? (count-children v :podcast/content-links))
       [(diagnostic :validate/missing-child :error path
                    "a liveItem must have a contentLink as a fallback")]))

   :podcast/person
   (fn [v path]
     (when-let [role (:role v)]
       (when-not (contains? spec/roles (str/lower-case role))
         [(diagnostic :validate/unknown-role :info (conj path :role)
                      (str "role " (pr-str role)
                           " is not in the Podcast Taxonomy"))])))

   :podcast/guid
   (fn [v path]
     (when-not (guid/guid? v)
       [(diagnostic :validate/invalid-guid :warning path
                    (str "podcast:guid " (pr-str v) " is not a UUID, so "
                         "apps and the Podcast Index use the one derived "
                         "from the URL"))]))

   ;; RSS 2.0, enclosure; RSS Best Practices Profile, 4.1.1.20.5: a length
   ;; of 0 says that the length isn't known
   :enclosure
   (fn [{:keys [type length]} path]
     (concat
      (when (and (string? type)
                 (not (str/blank? type))
                 (not (media-type? type)))
        [(diagnostic :validate/unknown-media-type :warning (conj path :type)
                     "the enclosure is neither audio nor video"
                     :value type)])
      (when (= 0 length)
        [(diagnostic :validate/unknown-length :info (conj path :length)
                     "the enclosure's length is 0, which says it isn't known")])))})

(declare children-diagnostics)

(defn- unknown-key-diagnostics
  "The diagnostics of the keys of the model map `m` at `path` that
  `registry` doesn't define, as a child in the `context` or as an
  attribute of `entry`. The `entry` is nil for the channel.

  They catch typos such as :rss/titel. On the channel, where a parse keeps
  no attributes, every such key counts. Elsewhere, only a key in a
  namespace of the library counts, since any other can be an attribute
  that a parse kept."
  [registry context entry m path]
  (let [child-keys (map #(spec/key-for % context)
                        (spec/entries-for registry context))
        attr-keys  (map names/attr-key (keys (:attrs entry)))
        known      (cond-> (into #{:content :xml/unknown}
                                 (concat child-keys attr-keys))
                     (nil? entry) (conj :rss/version))
        ours?      (fn [k]
                     (let [ns (namespace k)]
                       (or (= "rss" ns)
                           (contains? spec/namespaces (keyword ns)))))]
    (for [k     (keys m)
          :when (and (not (contains? known k))
                     (or (nil? entry)
                         (and (keyword? k)
                              (namespace k)
                              (ours? k))))]
      (diagnostic :validate/unknown-key :warning (conj path k)
                  (str "unknown key " (pr-str k))))))

(defn- shape
  "What kind of value `v` is, for a message."
  [v]
  (cond (nil? v)        "nothing"
        (string? v)     "a string"
        (map? v)        "a map"
        (sequential? v) "a sequence"
        (number? v)     "a number"
        (boolean? v)    "a boolean"
        :else           (pr-str v)))

(defn- value-diagnostics
  "The diagnostics of the model value `v` of `entry` in `registry` at
  `path`. They're of a shape that the element doesn't take, or else of its
  attributes, content, children and structure."
  [registry entry v path]
  (let [structural  (get structural-rules (:tag entry))
        map-shaped? (spec/map-shaped? registry entry)
        context     (spec/children-context entry)]
    (cond
      (and map-shaped? (not (map? v)))
      [(diagnostic :validate/wrong-shape :error path
                   (str (xml/qname (:tag entry))
                        " takes a map of its attributes and children, not "
                        (shape v)))]

      (and (not map-shaped?) (coll? v))
      [(diagnostic :validate/wrong-shape :error path
                   (str (xml/qname (:tag entry))
                        " takes a single value, not " (shape v)))]

      map-shaped?
      (concat (attr-diagnostics entry v path)
              (when (some? (:content v))
                (content-diagnostics entry (:content v) (conj path :content)))
              (children-diagnostics registry context v path)
              (unknown-key-diagnostics registry context entry v path)
              (when structural (structural v path)))

      :else
      (concat (content-diagnostics entry v path)
              (when structural (structural v path))))))

(defn- deprecated-diagnostic
  [entry path]
  (when-let [d (:deprecated entry)]
    (let [instead (when (keyword? d)
                    (str "; use " (xml/qname d)))
          message (str (xml/qname (:tag entry)) " is deprecated" instead)]
      [(cond-> (diagnostic :validate/deprecated :info path message)
         (keyword? d) (assoc :replaced-by d))])))

(defn- children-diagnostics
  "The diagnostics of every entry of `registry` in the model map `m` at
  `path` of an element in the `parent` context, and of its unknown
  elements. A nil value is no value."
  [registry parent m path]
  (concat
   (for [entry (spec/entries-for registry parent)
         :let  [k (spec/key-for entry parent)]
         [i v] (if (= :many (spec/count-for entry parent))
                 (map-indexed vector (get m k))
                 [[nil (get m k)]])
         :when (some? v)
         :let  [path' (cond-> (conj path k) i (conj i))]
         f     (concat (deprecated-diagnostic entry path')
                       (value-diagnostics registry entry v path'))]
     f)
   (for [element (:xml/unknown m)]
     (diagnostic :validate/unknown-element :info (conj path :xml/unknown)
                 (str "unknown element " (pr-str (first element)))
                 :tag (first element)))))

(defn- rss-diagnostics
  [feed]
  (let [items (map-indexed vector (:rss/items feed))]
    (concat
     (when-not (= "2.0" (:rss/version feed))
       [(diagnostic :validate/rss-version :warning [:rss/version]
                    (str "rss version is " (pr-str (:rss/version feed))
                         ", expected 2.0"))])
     (for [k [:rss/title :rss/link :rss/description]
           :when (nil? (get feed k))]
       (diagnostic :validate/missing-element :error [k]
                   (str "RSS 2.0 requires a channel " (name k))))
     (for [[i item] items
           :when (not (or (:rss/title item)
                          (:rss/description item)))]
       (diagnostic :validate/missing-element :error [:rss/items i]
                   "RSS 2.0 requires an item to have a title or a description"))
     (for [[i item] items
           :when (not (:rss/enclosure item))]
       (diagnostic :validate/missing-enclosure :warning
                   [:rss/items i :rss/enclosure]
                   "the item has no enclosure, so there is nothing to play")))))

(defn- duplicates
  "The diagnostics `id` at the `level` with the `message` of the items of
  `feed` that have the text at `k`, a path into an item, of an earlier
  item, each at the path `at` in its item, with the :value and the
  :first-path of the earlier item."
  [feed id level k at message]
  (->> (map-indexed vector (:rss/items feed))
       (reduce (fn [[seen found] [i item]]
                 (let [v    (get-in item k)
                       path (into [:rss/items i] at)]
                   (cond
                     (not (and (string? v) (not (str/blank? v))))
                     [seen found]

                     (contains? seen v)
                     [seen (conj found (diagnostic id level path message
                                                   :value v
                                                   :first-path (seen v)))]

                     :else
                     [(assoc seen v path) found])))
               [{} []])
       second))

;; RSS 2.0, guid; Apple's A Podcaster's Guide to RSS keeps the first item of
;; an enclosure URL that two share
(defn- item-diagnostics
  "The diagnostics of the items of `feed` that repeat the guid or the
  enclosure URL of an earlier item."
  [feed]
  (concat (duplicates feed :validate/duplicate-guid :error
                      [:rss/guid :content] [:rss/guid]
                      "the item has the guid of an earlier item")
          (duplicates feed :validate/duplicate-enclosure :warning
                      [:rss/enclosure :url] [:rss/enclosure :url]
                      "the item has the enclosure URL of an earlier item")))

(defn- unescaped
  "The category name `s` with an ampersand that was escaped twice, as
  &amp;, written as one."
  [s]
  (str/replace s "&amp;" "&"))

(defn- category-diagnostic
  "The diagnostic `id` of the category at `path` whose name is `s`, with
  the path of names that stands for it now as its `replacement`."
  ([id path s]
   (category-diagnostic id path s nil))
  ([id path s replacement]
   (let [message (case id
                   :validate/renamed-category
                   "Apple Podcasts renamed or dropped the category in 2019"

                   :validate/escaped-category
                   "the ampersand of the category is escaped twice"

                   :validate/wrong-subcategory
                   "the subcategory belongs under another category"

                   :validate/unknown-category
                   "Apple Podcasts doesn't list the category")]
     (cond-> (diagnostic id :warning (conj path :text) message :value s)
       replacement (assoc :replacement replacement)))))

(defn- parent-of
  "The category in `categories` that has the subcategory `s`, or nil."
  [categories s]
  (some (fn [[c subs]] (when (contains? subs s) c)) categories))

(defn- in-other-case
  "The path of names in `categories` of the category or subcategory that's
  `s` in another case, looked for first among the `names`, or nil."
  [categories names s]
  (let [s (str/lower-case s)]
    (when-let [n (some #(when (= s (str/lower-case %)) %)
                       (concat names (keys categories) (mapcat val categories)))]
      (if (contains? categories n)
        [n]
        [(parent-of categories n) n]))))

(defn- top-category
  "The diagnostic of the category `c` at `path`, by the tables `categories`
  and `renamed` of spec, or nil when Apple lists it, with the name that
  stands for `c` now, as a pair. The name is nil when nothing does."
  [categories renamed c path]
  (let [parent (partial parent-of categories)]
    (cond
      (contains? categories c)
      [nil c]

      (renamed [c])
      [(category-diagnostic :validate/renamed-category path c (renamed [c]))
       (first (renamed [c]))]

      (contains? categories (unescaped c))
      [(category-diagnostic :validate/escaped-category path c [(unescaped c)])
       (unescaped c)]

      (parent c)
      [(category-diagnostic :validate/wrong-subcategory path c [(parent c) c])
       nil]

      :else
      [(category-diagnostic :validate/unknown-category path c
                            (in-other-case categories nil c))
       nil])))

(defn- subcategory
  "The diagnostic of the subcategory `s` at `path` of the category `c`,
  whose name is `now` today, by the tables `categories` and `renamed` of
  spec, or nil when Apple lists it there."
  [categories renamed c now s path]
  (let [parent (partial parent-of categories)]
    (cond
      (contains? (categories now) s)
      nil

      (renamed [c s])
      (category-diagnostic :validate/renamed-category path s (renamed [c s]))

      (contains? (categories now) (unescaped s))
      (category-diagnostic :validate/escaped-category path s
                           [now (unescaped s)])

      (parent s)
      (category-diagnostic :validate/wrong-subcategory path s [(parent s) s])

      :else
      (category-diagnostic :validate/unknown-category path s
                           (in-other-case categories (categories now) s)))))

;; Apple's A Podcaster's Guide to RSS: a category and at most one
;; subcategory, from Apple's list, which PSP-1 requires too. The list is
;; for podcasts, so it doesn't judge a feed of music or another medium.
(defn- category-diagnostics
  "The diagnostics of the itunes:categories of `feed`, by the tables
  `categories` and `renamed` of spec, when its medium is podcast."
  [feed categories renamed]
  (when (contains? #{nil "podcast"}
                   (some-> (:podcast/medium feed) str/trim str/lower-case))
    (for [[i {c :text subs :categories}] (map-indexed vector
                                                      (:itunes/categories feed))
          :when (string? c)
          :let  [path      [:itunes/categories i]
                 [top now] (top-category categories renamed c path)]
          d     (cons top
                      (for [[j {s :text deeper :categories}] (map-indexed vector
                                                                         subs)
                            :let [path' (conj path :categories j)]
                            d     [(when (and now (string? s))
                                     (subcategory categories renamed c now s
                                                  path'))
                                   (when (seq deeper)
                                     (diagnostic :validate/category-depth
                                                 :warning
                                                 (conj path' :categories)
                                                 (str "a subcategory holds"
                                                      " another, which Apple"
                                                      " Podcasts doesn't"
                                                      " take")))]]
                        d))
          :when d]
      d)))

(def requirements
  "The required and recommended elements of the channel and of an item,
  for each standard or directory by name, and the levels that it raises
  above those of the catalogue.

  A requirement is a model key, or a vector of a key, a predicate of its
  value, and a description. They're the requirements of PSP-1, and of
  Apple's A Podcaster's Guide to RSS, as of September 2026. Both require
  a category from Apple's list, which a name from before 2019 still is,
  and Apple keeps only the first of the items that share an enclosure
  URL."
  (let [self-link  [:atom/links
                    #(some (comp #{"self"} :rel) %)
                    "atom:link rel=self"]
        enclosure  [:rss/enclosure
                    (comp absolute-url? :url)
                    "rss:enclosure with an absolute url"]
        categories {:validate/unknown-category  :error
                    :validate/escaped-category  :error
                    :validate/wrong-subcategory :error
                    :validate/category-depth    :error}]
    {:psp-1 {:name    "Podcast Standards Project PSP-1"
             :channel {:required    [self-link :rss/title :rss/description
                                     :rss/link :rss/language :itunes/categories
                                     :itunes/explicit :itunes/image]
                       :recommended [:podcast/locked :podcast/guid
                                     :itunes/author]}
             :item    {:required    [:rss/title enclosure :rss/guid]
                       :recommended [:rss/link :rss/pub-date :rss/description
                                     :itunes/duration :itunes/image
                                     :itunes/explicit :podcast/transcripts]}
             :levels  categories}
     :apple {:name    "Apple Podcasts"
             :channel {:required    [:rss/title :rss/description :itunes/image
                                     :rss/language :itunes/categories
                                     :itunes/explicit]
                       :recommended [:itunes/author :rss/link]}
             :item    {:required    [:rss/title enclosure :rss/guid]
                       :recommended [:rss/pub-date :rss/description
                                     :itunes/duration :rss/link :itunes/image
                                     :itunes/explicit]}
             :levels  (assoc categories
                             :validate/duplicate-enclosure :error)}}))

(defn- missing-diagnostics
  [level id m reqs path]
  (let [wanted (if (= :error level) "required" "recommended")]
    (for [req   reqs
          :when (map? m)
          :let  [[k pred label] (if (vector? req)
                                  req
                                  [req nil (xml/qname req)])
                 v              (get m k)]
          :when (not (and (some? v)
                          (or (nil? pred) (pred v))))]
      (diagnostic id level (conj path k) (str label " is " wanted)))))

(defn- requirements-diagnostics
  [feed chosen]
  (let [{:keys [channel item]} (if (map? chosen) chosen (requirements chosen))
        missing (fn [m reqs path]
                  (concat (missing-diagnostics :error :validate/required
                                               m (:required reqs) path)
                          (missing-diagnostics :warning :validate/recommended
                                               m (:recommended reqs) path)))]
    (concat (missing feed channel [])
            (mapcat (fn [[i it]]
                      (missing it item [:rss/items i]))
                    (map-indexed vector (:rss/items feed))))))

(defn- reported?
  "Whether `found`, a set of the paths that have an error, already has one
  at `path` or below it."
  [found path]
  (some #(= path (take (count path) %)) found))

(defn validate
  "The diagnostics of `feed` with the `opts` below: the checks of the
  registry, the basics of RSS 2.0, and the requirements that `opts` name.

  - :registry, the tag registry to check against, spec/default by default
  - :requirements, a name in requirements, or a map of the shape of its
    entries
  - :categories and :renamed-categories, the tables of spec that the
    categories of a podcast are checked by

  The feed is normalized first, so the shorthand that a producer can write
  passes too. The chosen requirements can raise the level of a
  diagnostic, e.g. a repeated enclosure URL is an error for Apple. A
  requirement that an error of the other checks already covers isn't
  reported again, so one fix counts once."
  ([feed]
   (validate feed {}))
  ([feed {chosen :requirements
          :keys  [registry categories renamed-categories]
          :or    {registry           spec/default
                  categories         spec/categories
                  renamed-categories spec/renamed-categories}
          :as    opts}]
   {:pre [(map? opts)]}
   (when (and chosen
              (not (map? chosen))
              (not (contains? requirements chosen)))
     (throw (ex-info (str "Unknown requirements " chosen)
                     {:requirements chosen
                      :known        (set (keys requirements))})))
   (let [registry (spec/registry registry)
         feed     (feed/normalize feed {:registry registry})
         levels   (:levels (if (map? chosen) chosen (get requirements chosen)))
         raised   #(assoc % :level (get levels (:id %) (:level %)))
         checked  (mapv raised
                        (concat (rss-diagnostics feed)
                                (item-diagnostics feed)
                                (category-diagnostics feed categories
                                                      renamed-categories)
                                (children-diagnostics registry :channel feed [])
                                (unknown-key-diagnostics registry :channel nil
                                                         feed [])))
         errors   (into #{} (comp (filter (comp #{:error} :level)) (map :path))
                        checked)]
     (into checked
           (when chosen
             (remove #(reported? errors (:path %))
                     (requirements-diagnostics feed chosen)))))))

(defn valid?
  "Whether `feed` has no errors, with the `opts` of validate."
  ([feed]
   (valid? feed {}))
  ([feed opts]
   (not-any? (comp #{:error} :level) (validate feed opts))))

(comment
  (validate {:rss/version "2.0" :rss/title "T"
             :podcast/values [{:type "lightning" :method "keysend"}]}
            {:requirements :psp-1})
  #_.)
