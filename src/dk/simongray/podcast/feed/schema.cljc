(ns dk.simongray.podcast.feed.schema
  "The feed model as Malli schemas, made from the tag registry so that they
  never drift from what parse gives and emit takes.

  The schemas are plain data in the vector syntax of Malli, and Malli
  itself isn't a dependency. In your own project, give a schema to
  malli.core/schema, and validate, explain, generate or coerce with it:

      (m/validate schema/feed (podcast/parse xml))

  The schemas are feed and item, and what the function tag gives for any
  element. For a vocabulary extended with your own elements, give a
  :registry in the options of schemas, feed-schema and tag.

  The schemas describe the model when every value coerced. Validating a
  parsed feed against them tells you where parse forgave a value and kept
  it as a string. Enumerations stay strings, since feeds vary their case,
  and the validator ignores case when it judges them."
  (:require [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.xml :as xml]))

(def types
  "The Malli schema of each value type of the registry, which is a string
  for any other type."
  {:integer  :int
   :decimal  'number?
   :boolean  :boolean
   :yes-no   :boolean
   :presence [:= true]
   :rfc2822  'inst?
   :iso8601  'inst?
   :duration 'number?
   :rrule    [:map
              [:freq [:enum "SECONDLY" "MINUTELY" "HOURLY" "DAILY" "WEEKLY" "MONTHLY" "YEARLY"]]
              [:interval {:optional true} 'pos-int?]
              [:count {:optional true} 'pos-int?]
              [:until {:optional true} 'inst?]
              [:byday {:optional true} [:vector {:gen/max 3}
                                        [:re {:gen/elements ["MO" "TU" "WE" "TH" "FR" "SA" "SU" "1MO" "-1FR"]}
                                         #"^([+-]?(?:0?[1-9]|[1-4]\d|5[0-3]))?(MO|TU|WE|TH|FR|SA|SU)$"]]]
              [:bymonthday {:optional true} [:vector {:gen/max 3}
                                             [:or [:int {:min 1 :max 31}] [:int {:min -31 :max -1}]]]]
              [:bymonth {:optional true} [:vector {:gen/max 3} [:int {:min 1 :max 12}]]]
              [:wkst {:optional true} [:enum "MO" "TU" "WE" "TH" "FR" "SA" "SU"]]]
   :geo      [:map
              [:lat 'number?]
              [:lon 'number?]
              [:altitude {:optional true} 'number?]
              [:uncertainty {:optional true} 'number?]]
   :osm      [:map
              [:type [:enum "N" "W" "R"]]
              [:id 'pos-int?]
              [:revision {:optional true} 'pos-int?]]})

(defn- type-schema
  [type]
  (get types type :string))

(defn ref-key
  "The key that the schema of the element `tag` is registered under: the
  tag, in the namespace rss when it has none, e.g. :rss/title for :title."
  [tag]
  (if (namespace tag)
    tag
    (keyword "rss" (name tag))))

(def ^:no-doc unknown
  "The schema of the Hiccup that an element can keep for elements that the
  registry doesn't know, which is never generated."
  [:vector {:gen/max 0} :any])

(def ^:no-doc generated-max
  "How many of a repeatable element a generated feed holds at most, so that
  generation doesn't grow multiplicatively with the size parameter of
  Malli. Validation ignores it."
  5)

(defn- attribute-entries
  "The map entries of the declared attributes of `entry`."
  [entry]
  (for [[k {:keys [type required]}] (:attrs entry)]
    [(xml/attr-key k) {:optional (not required)} (type-schema (or type :text))]))

(defn- child-entries
  "The map entries of the elements of `registry` that can appear in the
  `context`."
  [registry context]
  (for [child (spec/entries-for registry context)
        :let  [schema [:ref (ref-key (:tag child))]]]
    [(spec/key-for child context)
     {:optional true}
     (if (= :many (spec/count-for child context))
       [:vector {:gen/max generated-max} schema]
       schema)]))

(defn ^:no-doc entry-schema
  "The schema of one occurrence of the element `entry` of `registry`. It's
  the coerced text of a scalar, or else a map of the attributes, the
  :content and the children."
  [registry entry]
  (let [content (spec/content-type entry)]
    (if (spec/map-shaped? registry entry)
      (-> [:map]
          (into (attribute-entries entry))
          (into (when (not= :none content)
                  [[:content {:optional true} (type-schema content)]]))
          (into (child-entries registry (spec/children-context entry)))
          (conj [:xml/unknown {:optional true} unknown]))
      (type-schema content))))

(defn schemas
  "The schema of every element by ref-key, and ::feed for the channel map
  that parse returns, as a Malli registry for the :registry of `opts`.

  The tag registry is spec/default by default. Elements that appear under
  several parents share one schema, since their attributes and children
  don't depend on the parent."
  ([]
   (schemas {}))
  ([{:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (let [registry (spec/registry registry)]
     (-> (into {} (for [[tag [entry]] (:by-tag registry)]
                    [(ref-key tag) (entry-schema registry entry)]))
         (assoc ::feed (-> [:map [:rss/version {:optional true} :string]]
                           (into (child-entries registry :channel))
                           (conj [:xml/unknown {:optional true} unknown])))))))

(defn feed-schema
  "The self-contained schema of a feed map, with the `opts` of schemas."
  ([]
   (feed-schema {}))
  ([opts]
   [:schema {:registry (schemas opts)} ::feed]))

(defn tag-schema
  "The self-contained schema of the element `tag`, e.g.
  (tag-schema :podcast/value), with the `opts` of schemas."
  ([tag]
   (tag-schema tag {}))
  ([tag opts]
   [:schema {:registry (schemas opts)} (ref-key tag)]))

(def feed
  "The self-contained schema of a feed map."
  (feed-schema))

(def item
  "The self-contained schema of an item map."
  (tag-schema :item))

(comment
  (tag-schema :podcast/transcript)
  ;; => [:schema {:registry ...} :podcast/transcript]
  (get (schemas) :podcast/transcript)
  ;; => [:map [:url :string] [:type :string] [:language {:optional true} :string] ...]
  #_.)
