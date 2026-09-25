(ns dk.simongray.podcast.validate
  "Registry-driven validation of a feed map.

  `validate` walks a feed with the tag registry and reports what the
  specifications say is wrong: missing required attributes, values outside an
  enumeration, values that would not coerce to their type, deprecated
  elements, structural rules such as an alternate enclosure without a
  source, and elements the registry does not know. A *profile* adds the
  required and recommended elements of a directory or standard on top:
  `:psp-1` for the Podcast Standards Project baseline, `:apple` for Apple
  Podcasts.

  Findings have the same shape as the parse report: `:id`, `:level`
  (`:error`, `:warning` or `:info`), `:path` into the feed and `:message`."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.spec :as spec]))

(def coerced-types
  "The value types whose model values are not strings, so that a string
  where one is expected means the value did not coerce."
  #{:integer :decimal :boolean :yes-no :rfc2822 :iso8601 :duration :rrule :geo :osm})

(defn- finding
  [id level path message & {:as extra}]
  (merge {:id id :level level :path path :message message} extra))

(defn- tag-name
  "The XML-style name of the keyword `k`: itunes:duration for
  :itunes/duration."
  [k]
  (if (namespace k)
    (str (namespace k) ":" (name k))
    (name k)))

(defn- enum-ok?
  [values v]
  (contains? (into #{} (map str/lower-case) values) (str/lower-case (str v))))

(defn- typed-findings
  "Findings for the value `v` of `type` at `path`: not coerced, outside the
  enumeration `values`, or an http URL where https is expected."
  [type values v path https?]
  (cond
    (and (contains? coerced-types type) (string? v))
    [(finding :validate/invalid-value :error path
              (str "value " (pr-str v) " is not a valid " (name type)))]

    (and (= type :enum) values (not (enum-ok? values v)))
    [(finding :validate/invalid-enum :warning path
              (str "value " (pr-str v) " is not one of " (str/join ", " (sort values))))]

    (and (= type :url) (string? v) (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" v)))
    [(finding :validate/invalid-url :warning path (str "not an absolute URL: " (pr-str v)))]

    (and (= type :url) https? (string? v) (str/starts-with? (str/lower-case v) "http://"))
    [(finding :validate/http-url :info path
              "the podcast namespace requires https for hypertext resources")]

    :else
    []))

(defn- attr-findings
  "Findings for the attributes of the map value `m` of `entry`."
  [entry m path]
  (let [https? (= "podcast" (namespace (:tag entry)))]
    (mapcat (fn [[k {:keys [type required values]}]]
              (let [k' (spec/attr-key k)]
                (if (contains? m k')
                  (typed-findings (or type :text) values (get m k') (conj path k') https?)
                  (when required
                    [(finding :validate/missing-attribute :error (conj path k')
                              (str "required attribute " (name k) " is missing"))]))))
            (:attrs entry))))

(defn- content-findings
  [entry v path]
  (typed-findings (spec/content-type entry) (:values entry) v path false))

;;;; Structural rules beyond what the registry expresses

(defn- count-children
  [m k]
  (count (get m k)))

(def structural-rules
  "Checks per tag on the model value of an element, each returning findings."
  {:podcast/alternateEnclosure
   (fn [v path]
     (when (zero? (count-children v :sources))
       [(finding :validate/missing-child :error path
                 "an alternateEnclosure needs at least one source")]))

   :podcast/value
   (fn [v path]
     (when (zero? (count-children v :recipients))
       [(finding :validate/missing-child :error path
                 "a value block needs at least one valueRecipient")]))

   :podcast/valueTimeSplit
   (fn [v path]
     (let [recipients (count-children v :recipients)
           remote     (if (:remote-item v) 1 0)]
       (when-not (or (and (pos? recipients) (zero? remote))
                     (and (zero? recipients) (= 1 remote)))
         [(finding :validate/invalid-structure :error path
                   "a valueTimeSplit holds either valueRecipients or exactly one remoteItem")])))

   :podcast/podroll
   (fn [v path]
     (when (zero? (count-children v :remote-items))
       [(finding :validate/missing-child :error path "a podroll needs at least one remoteItem")]))

   :podcast/publisher
   (fn [v path]
     (when-not (:remote-item v)
       [(finding :validate/missing-child :error path
                 "a publisher element needs exactly one remoteItem with medium=publisher")]))

   :podcast/liveItem
   (fn [v path]
     (when (zero? (count-children v :podcast/content-links))
       [(finding :validate/missing-child :error path
                 "a liveItem must have a contentLink as a fallback")]))

   :podcast/person
   (fn [v path]
     (when-let [role (:role v)]
       (when-not (contains? spec/roles (str/lower-case role))
         [(finding :validate/unknown-role :info (conj path :role)
                   (str "role " (pr-str role) " is not in the Podcast Taxonomy"))])))

   :podcast/guid
   (fn [v path]
     (when-not (guid/guid? v)
       [(finding :validate/invalid-guid :warning path
                 (str "podcast:guid " (pr-str v) " is not a UUID"))]))})

;;;; The walk

(declare children-findings)

(defn- value-findings
  "Findings for the model value `v` of `entry`, its attributes, content,
  children and structure."
  [entry v path]
  (let [structural (get structural-rules (:tag entry))]
    (if (spec/map-shaped? entry)
      (concat (attr-findings entry v path)
              (when (contains? v :content)
                (content-findings entry (:content v) (conj path :content)))
              (children-findings (spec/children-context entry) v path)
              (when structural (structural v path)))
      (concat (content-findings entry v path)
              (when structural (structural v path))))))

(defn- deprecated-finding
  [entry path]
  (when-let [d (:deprecated entry)]
    [(finding :validate/deprecated :info path
              (str (tag-name (:tag entry)) " is deprecated"
                   (when (keyword? d) (str "; use " (tag-name d))))
              :replaced-by (when (keyword? d) d))]))

(defn- children-findings
  "Findings for every registry entry present in the model map `m` of an
  element in the `parent` context, and for its unknown elements."
  [parent m path]
  (concat
   (for [entry (spec/entries-for parent)
         :let  [k (spec/key-for entry parent)]
         :when (contains? m k)
         [i v] (if (= :many (spec/count-for entry parent))
                 (map-indexed vector (get m k))
                 [[nil (get m k)]])
         :let  [path' (cond-> (conj path k) i (conj i))]
         f     (concat (deprecated-finding entry path')
                       (value-findings entry v path'))]
     f)
   (for [element (:xml/unknown m)]
     (finding :validate/unknown-element :info (conj path :xml/unknown)
              (str "unknown element " (pr-str (first element)))
              :tag (first element)))))

;;;; RSS 2.0 basics

(defn- rss-findings
  [feed]
  (concat
   (when-not (= "2.0" (:rss/version feed))
     [(finding :validate/rss-version :warning [:rss/version]
               (str "rss version is " (pr-str (:rss/version feed)) ", expected 2.0"))])
   (for [k [:rss/title :rss/link :rss/description]
         :when (not (contains? feed k))]
     (finding :validate/missing-element :error [k]
              (str "RSS 2.0 requires a channel " (name k))))
   (for [[i item] (map-indexed vector (:rss/items feed))
         :when (not (or (:rss/title item) (:rss/description item)))]
     (finding :validate/missing-element :error [:rss/items i]
              "RSS 2.0 requires an item to have a title or a description"))
   (for [[i item] (map-indexed vector (:rss/items feed))
         :when (not (:rss/enclosure item))]
     (finding :validate/missing-enclosure :warning [:rss/items i :rss/enclosure]
              "the item has no enclosure, so there is nothing to play"))))

;;;; Profiles

(def profiles
  "Required and recommended elements per profile, at channel and item level.
  A requirement is a model key, or a vector of a key and a predicate on its
  value with a description."
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

(defn- requirement-findings
  [level id m requirements path]
  (for [req requirements
        :let [[k pred label] (if (vector? req) req [req nil (tag-name req)])]
        :when (not (and (contains? m k) (or (nil? pred) (pred (get m k)))))]
    (finding id level (conj path k) (str label " is " (if (= :error level) "required" "recommended")))))

(defn- profile-findings
  [feed profile]
  (let [{:keys [channel item]} (profiles profile)]
    (concat
     (requirement-findings :error :validate/profile-required feed (:required channel) [])
     (requirement-findings :warning :validate/profile-recommended feed (:recommended channel) [])
     (mapcat (fn [[i it]]
               (concat
                (requirement-findings :error :validate/profile-required it (:required item) [:rss/items i])
                (requirement-findings :warning :validate/profile-recommended it (:recommended item) [:rss/items i])))
             (map-indexed vector (:rss/items feed))))))

(defn validate
  "The findings for `feed`: registry checks, RSS 2.0 basics and, when a
  `profile` from `profiles` is given, that profile's requirements."
  ([feed]
   (validate feed nil))
  ([feed profile]
   (vec (concat (rss-findings feed)
                (children-findings :channel feed [])
                (when profile
                  (profile-findings feed profile))))))

(defn valid?
  "Whether `validate` finds no errors in `feed`, under `profile` if given."
  ([feed]
   (valid? feed nil))
  ([feed profile]
   (not-any? (comp #{:error} :level) (validate feed profile))))

(comment
  (validate {:rss/version "2.0" :rss/title "T"
             :podcast/values [{:type "lightning" :method "keysend"}]}
            :psp-1)
  #_.)
