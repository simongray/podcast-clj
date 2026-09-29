(ns dk.simongray.podcast.feed.validate
  "Registry-driven validation of a feed map.

  `validate` walks a feed with the tag registry, the library's or the one
  its options name, and reports what the specifications say is wrong:
  missing required attributes, values outside an enumeration, values that
  would not coerce to their type, deprecated elements, structural rules such
  as an alternate enclosure without a source, and elements the registry does
  not know. A *profile* named in the options adds the required and
  recommended elements of a directory or standard on top: `:psp-1` for the
  Podcast Standards Project baseline, `:apple` for Apple Podcasts.

  Diagnostics have the same shape as the parse report: `:id`, `:level`
  (`:error`, `:warning` or `:info`), `:path` into the feed and `:message`.
  Validation never throws on a value: a shape the element does not take
  is a diagnostic like any other, and the shorthand `feed/normalize`
  expands is taken first."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

(defn- diagnostic
  [id level path message & {:as extra}]
  (merge {:id id :level level :path path :message message} extra))

(defn- enum-ok?
  [values v]
  (let [v (str/lower-case (str v))]
    (boolean (some #(= v (str/lower-case %)) values))))

(defn- typed-diagnostics
  "Diagnostics for the value `v` of `type` at `path`: not of its type (see
  `coerce/of-type?`), a negative number, outside the enumeration `values`,
  or an http URL where https is expected."
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
  "Diagnostics for the attributes of the map value `m` of `entry`."
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
  "Checks per tag on the model value of an element, each returning diagnostics."
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
  "Diagnostics for the keys of the model map `m` that `registry` defines
  neither as a child in the `context` nor as an attribute of `entry`, nil
  for the channel: typos such as :rss/titel. On the channel, where a parse
  keeps no attributes, every such key counts; elsewhere only one in a
  namespace of the library, since any other can be an attribute that a
  parse kept."
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
  "Diagnostics for the model value `v` of `entry` in `registry`: the shape
  the element does not take, or else its attributes, content, children
  and structure."
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
  "Diagnostics for every entry of `registry` present in the model map `m` of
  an element in the `parent` context, and for its unknown elements. A nil
  value is no value, as it is to `emit`."
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

(def profiles
  "Required and recommended elements per profile, at channel and item level.
  A requirement is a model key, or a vector of a key and a predicate on its
  value with a description.

  They are the requirements of September 2026. `validate` takes a profile
  by its name here, or as a map of requirements of this shape."
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
           :item    {:required    [:rss/title :rss/enclosure]
                     :recommended [:rss/guid :rss/pub-date :rss/description :itunes/duration
                                   :rss/link :itunes/image :itunes/explicit]}}})

(defn- requirement-diagnostics
  [level id m requirements path]
  (for [req requirements
        :when (map? m)
        :let [[k pred label] (if (vector? req) req [req nil (xml/qname req)])]
        :when (not (and (some? (get m k)) (or (nil? pred) (pred (get m k)))))]
    (diagnostic id level (conj path k) (str label " is " (if (= :error level) "required" "recommended")))))

(defn- profile-diagnostics
  [feed profile]
  (let [{:keys [channel item]} (if (map? profile) profile (profiles profile))]
    (concat
     (requirement-diagnostics :error :validate/profile-required feed (:required channel) [])
     (requirement-diagnostics :warning :validate/profile-recommended feed (:recommended channel) [])
     (mapcat (fn [[i it]]
               (concat
                (requirement-diagnostics :error :validate/profile-required it (:required item) [:rss/items i])
                (requirement-diagnostics :warning :validate/profile-recommended it (:recommended item) [:rss/items i])))
             (map-indexed vector (:rss/items feed))))))

(defn validate
  "The diagnostics for `feed`: the checks of the registry, the basics of
  RSS 2.0 and, with a `:profile` in `opts`, the requirements of that
  profile.

  `:registry` is the tag registry to check against, `spec/default` by
  default. The feed is normalized first, so the shorthand that `emit`
  takes passes here too.

  The `:profile` is a name in `profiles`, or a map of requirements of the
  shape of its entries."
  ([feed]
   (validate feed {}))
  ([feed {:keys [profile registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (when (and profile (not (map? profile)) (not (contains? profiles profile)))
     (throw (ex-info (str "Unknown profile " profile) {:profile profile :profiles (set (keys profiles))})))
   (let [registry (spec/registry registry)
         feed     (feed/normalize feed {:registry registry})]
     (vec (concat (rss-diagnostics feed)
                  (children-diagnostics registry :channel feed [])
                  (unknown-key-diagnostics registry :channel nil feed [])
                  (when profile
                    (profile-diagnostics feed profile)))))))

(defn valid?
  "Whether `validate` finds no errors in `feed`, with `opts` as for
  `validate`."
  ([feed]
   (valid? feed {}))
  ([feed opts]
   (not-any? (comp #{:error} :level) (validate feed opts))))

(comment
  (validate {:rss/version "2.0" :rss/title "T"
             :podcast/values [{:type "lightning" :method "keysend"}]}
            {:profile :psp-1})
  #_.)
