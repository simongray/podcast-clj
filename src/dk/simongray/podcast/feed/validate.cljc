(ns dk.simongray.podcast.feed.validate
  "Validation of a feed map by the tag registry.

  The function validate walks a feed with a tag registry, that of the
  library unless the options give another. It reports what the
  specifications say is wrong:

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
  element doesn't take is a diagnostic like any other. The shorthand that
  feed/normalize expands is taken first."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.xml :as xml]))

(defn- diagnostic
  [id level path message & {:as details}]
  (merge {:id id :level level :path path :message message} details))

(defn- enum-ok?
  [values v]
  (let [v (str/lower-case (str v))]
    (boolean (some #(= v (str/lower-case %)) values))))

(defn- typed-diagnostics
  "The diagnostics of the value `v` of `type` at `path`, by the enumeration
  `values` and whether `https?` is expected.

  There's one for each of these:

  - a value that isn't of its type, as coerce/of-type? says
  - a negative number
  - a value outside the enumeration
  - an http URL where https is expected"
  [type values v path https?]
  (cond
    (and (contains? coerce/coerced-types type) (not (coerce/of-type? type v)))
    [(diagnostic :validate/invalid-value :error path
              (str "value " (pr-str v) " is not a valid " (name type)))]

    (and (#{:integer :decimal :duration} type) (neg? v))
    [(diagnostic :validate/invalid-value :error path
              (str "value " (pr-str v) " is below zero, as no quantity in a feed is"))]

    (and (= type :enum) values (not (enum-ok? values v)))
    [(diagnostic :validate/invalid-enum :warning path
              (str "value " (pr-str v) " is not one of " (str/join ", " (sort values))))]

    (and (= type :url) (string? v) (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" v)))
    [(diagnostic :validate/invalid-url :warning path (str "not an absolute URL: " (pr-str v)))]

    (and (= type :url) https? (string? v) (str/starts-with? (str/lower-case v) "http://"))
    [(diagnostic :validate/http-url :info path
              "the podcast namespace requires https for hypertext resources")]

    :else
    []))

(defn- attr-diagnostics
  "The diagnostics of the attributes of the map value `m` of `entry` at
  `path`."
  [entry m path]
  (let [https? (= "podcast" (namespace (:tag entry)))]
    (mapcat (fn [[k {:keys [type required values]}]]
              (let [k' (xml/attr-key k)]
                (if (contains? m k')
                  (typed-diagnostics (or type :text) values (get m k') (conj path k') https?)
                  (when required
                    [(diagnostic :validate/missing-attribute :error (conj path k')
                              (str "required attribute " (name k) " is missing"))]))))
            (:attrs entry))))

(defn- content-diagnostics
  [entry v path]
  (typed-diagnostics (spec/content-type entry) (:values entry) v path false))

(defn- count-children
  [m k]
  (count (get m k)))

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
                   "a valueTimeSplit holds either valueRecipients or exactly one remoteItem")])))

   :podcast/podroll
   (fn [v path]
     (when (zero? (count-children v :remote-items))
       [(diagnostic :validate/missing-child :error path "a podroll needs at least one remoteItem")]))

   :podcast/publisher
   (fn [v path]
     (if-let [remote (:remote-item v)]
       (when-not (= "publisher" (some-> (:medium remote) str/lower-case))
         [(diagnostic :validate/invalid-structure :error (conj path :remote-item)
                   "the remoteItem of a publisher element needs medium=publisher")])
       [(diagnostic :validate/missing-child :error path
                 "a publisher element needs exactly one remoteItem with medium=publisher")]))

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
                   (str "role " (pr-str role) " is not in the Podcast Taxonomy"))])))

   :podcast/guid
   (fn [v path]
     (when-not (guid/guid? v)
       [(diagnostic :validate/invalid-guid :warning path
                 (str "podcast:guid " (pr-str v) " is not a UUID"))]))})

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
  (let [known (cond-> (into #{:content :xml/unknown}
                            (concat (map #(spec/key-for % context) (spec/entries-for registry context))
                                    (map xml/attr-key (keys (:attrs entry)))))
                (nil? entry) (conj :rss/version))
        ours? (fn [k]
                (let [ns (namespace k)]
                  (or (= "rss" ns) (contains? xml/namespaces (keyword ns)))))]
    (for [k     (keys m)
          :when (and (not (contains? known k))
                     (or (nil? entry) (and (keyword? k) (namespace k) (ours? k))))]
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
        map-shaped? (spec/map-shaped? registry entry)]
    (cond
      (and map-shaped? (not (map? v)))
      [(diagnostic :validate/wrong-shape :error path
                   (str (xml/qname (:tag entry)) " takes a map of its attributes and children, not " (shape v)))]

      (and (not map-shaped?) (coll? v))
      [(diagnostic :validate/wrong-shape :error path
                   (str (xml/qname (:tag entry)) " takes a single value, not " (shape v)))]

      map-shaped?
      (concat (attr-diagnostics entry v path)
              (when (some? (:content v))
                (content-diagnostics entry (:content v) (conj path :content)))
              (children-diagnostics registry (spec/children-context entry) v path)
              (unknown-key-diagnostics registry (spec/children-context entry) entry v path)
              (when structural (structural v path)))

      :else
      (concat (content-diagnostics entry v path)
              (when structural (structural v path))))))

(defn- deprecated-diagnostic
  [entry path]
  (when-let [d (:deprecated entry)]
    [(cond-> (diagnostic :validate/deprecated :info path
                         (str (xml/qname (:tag entry)) " is deprecated"
                              (when (keyword? d) (str "; use " (xml/qname d)))))
       (keyword? d) (assoc :replaced-by d))]))

(defn- children-diagnostics
  "The diagnostics of every entry of `registry` in the model map `m` at
  `path` of an element in the `parent` context, and of its unknown
  elements. A nil value is no value, as it is to emit."
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
  (concat
   (when-not (= "2.0" (:rss/version feed))
     [(diagnostic :validate/rss-version :warning [:rss/version]
               (str "rss version is " (pr-str (:rss/version feed)) ", expected 2.0"))])
   (for [k [:rss/title :rss/link :rss/description]
         :when (nil? (get feed k))]
     (diagnostic :validate/missing-element :error [k]
              (str "RSS 2.0 requires a channel " (name k))))
   (for [[i item] (map-indexed vector (:rss/items feed))
         :when (not (or (:rss/title item) (:rss/description item)))]
     (diagnostic :validate/missing-element :error [:rss/items i]
              "RSS 2.0 requires an item to have a title or a description"))
   (for [[i item] (map-indexed vector (:rss/items feed))
         :when (not (:rss/enclosure item))]
     (diagnostic :validate/missing-enclosure :warning [:rss/items i :rss/enclosure]
              "the item has no enclosure, so there is nothing to play"))))

(def requirements
  "The required and recommended elements of the channel and of an item,
  for each standard or directory by name.

  A requirement is a model key, or a vector of a key, a predicate of its
  value, and a description. They're the requirements of PSP-1, and of
  Apple's A Podcaster's Guide to RSS, as of September 2026. The function
  validate takes them by their name here, or as a map of this shape."
  {:psp-1 {:name    "Podcast Standards Project PSP-1"
           :channel {:required    [[:atom/links #(some (comp #{"self"} :rel) %) "atom:link rel=self"]
                                   :rss/title :rss/description :rss/link :rss/language
                                   :itunes/categories :itunes/explicit :itunes/image]
                     :recommended [:podcast/locked :podcast/guid :itunes/author]}
           :item    {:required    [:rss/title :rss/enclosure :rss/guid]
                     :recommended [:rss/link :rss/pub-date :rss/description :itunes/duration
                                   :itunes/image :itunes/explicit :podcast/transcripts]}}
   :apple {:name    "Apple Podcasts"
           :channel {:required    [:rss/title :rss/description :itunes/image :rss/language
                                   :itunes/categories :itunes/explicit]
                     :recommended [:itunes/author :rss/link]}
           :item    {:required    [:rss/title :rss/enclosure :rss/guid]
                     :recommended [:rss/pub-date :rss/description :itunes/duration
                                   :rss/link :itunes/image :itunes/explicit]}}})

(defn- missing-diagnostics
  [level id m reqs path]
  (for [req reqs
        :when (map? m)
        :let [[k pred label] (if (vector? req) req [req nil (xml/qname req)])]
        :when (not (and (some? (get m k)) (or (nil? pred) (pred (get m k)))))]
    (diagnostic id level (conj path k) (str label " is " (if (= :error level) "required" "recommended")))))

(defn- requirements-diagnostics
  [feed chosen]
  (let [{:keys [channel item]} (if (map? chosen) chosen (requirements chosen))]
    (concat
     (missing-diagnostics :error :validate/required feed (:required channel) [])
     (missing-diagnostics :warning :validate/recommended feed (:recommended channel) [])
     (mapcat (fn [[i it]]
               (concat
                (missing-diagnostics :error :validate/required it (:required item) [:rss/items i])
                (missing-diagnostics :warning :validate/recommended it (:recommended item) [:rss/items i])))
             (map-indexed vector (:rss/items feed))))))

(defn validate
  "The diagnostics of `feed` with the `opts` below: the checks of the
  registry, the basics of RSS 2.0, and the requirements that `opts` name.

  - :registry, the tag registry to check against, spec/default by default
  - :requirements, a name in requirements, or a map of the shape of its
    entries

  The feed is normalized first, so the shorthand that emit takes passes
  here too."
  ([feed]
   (validate feed {}))
  ([feed {chosen :requirements :keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (when (and chosen (not (map? chosen)) (not (contains? requirements chosen)))
     (throw (ex-info (str "Unknown requirements " chosen) {:requirements chosen :known (set (keys requirements))})))
   (let [registry (spec/registry registry)
         feed     (feed/normalize feed {:registry registry})]
     (vec (concat (rss-diagnostics feed)
                  (children-diagnostics registry :channel feed [])
                  (unknown-key-diagnostics registry :channel nil feed [])
                  (when chosen
                    (requirements-diagnostics feed chosen)))))))

(defn valid?
  "Whether validate finds no errors in `feed`, with the `opts` of
  validate."
  ([feed]
   (valid? feed {}))
  ([feed opts]
   (not-any? (comp #{:error} :level) (validate feed opts))))

(comment
  (validate {:rss/version "2.0" :rss/title "T"
             :podcast/values [{:type "lightning" :method "keysend"}]}
            {:requirements :psp-1})
  #_.)
