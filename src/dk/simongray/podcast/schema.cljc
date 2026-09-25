(ns dk.simongray.podcast.schema
  "The feed model as Malli schemas, generated from the tag registry so that
  they can never drift from what `parse` produces and `emit` accepts.

  The schemas are plain data in Malli's vector syntax and Malli itself is not
  a dependency: hand `feed` (or `item`, or any tag's schema in `registry`) to
  `malli.core/schema` in your own project to validate, explain, generate or
  coerce with it.

      (m/validate schema/feed (podcast/parse xml))

  The schemas describe the model when every value coerced: an element the
  registry types as an integer is an `:int` here. `parse` never throws on a
  value that will not coerce and keeps it as a string instead, with a
  finding in the report, so validating a parsed feed against these schemas
  tells you exactly where a feed was forgiven. Enumerations are left as
  strings, since feeds vary their case and the validator judges them case
  insensitively."
  (:require [dk.simongray.podcast.spec :as spec]))

(def types
  "The Malli schema for each value type of the registry; strings otherwise."
  {:integer  :int
   :decimal  'number?
   :boolean  :boolean
   :yes-no   :boolean
   :rfc2822  'inst?
   :iso8601  'inst?
   :duration 'number?})

(defn- type-schema
  [type]
  (get types type :string))

(defn ref-key
  "The key an element's schema is registered under: its tag, namespaced
  `rss` when the tag has no namespace, so that `:title` becomes `:rss/title`."
  [tag]
  (if (namespace tag)
    tag
    (keyword "rss" (name tag))))

(def unknown
  "The schema of the Hiccup an element may keep for elements the registry
  does not know; never generated."
  [:vector {:gen/max 0} :any])

(def generated-max
  "How many of a repeatable element a generated feed holds at most. A bound
  on every vector keeps generation of a feed, with its items and their
  repeatable children, from growing multiplicatively with Malli's size
  parameter; validation ignores it."
  5)

(defn- attribute-entries
  "The map entries for the declared attributes of `entry`."
  [entry]
  (for [[k {:keys [type required]}] (:attrs entry)]
    [(spec/attr-key k) {:optional (not required)} (type-schema (or type :text))]))

(defn- child-entries
  "The map entries for the elements that may appear in the `context`."
  [context]
  (for [child (spec/entries-for context)
        :let  [schema [:ref (ref-key (:tag child))]]]
    [(spec/key-for child context)
     {:optional true}
     (if (= :many (spec/count-for child context))
       [:vector {:gen/max generated-max} schema]
       schema)]))

(defn entry-schema
  "The schema for one occurrence of the element `entry` describes: its
  coerced text when it is a scalar, otherwise a map of its attributes, its
  `:content` and its children."
  [entry]
  (let [content (spec/content-type entry)]
    (if (spec/map-shaped? entry)
      (-> [:map]
          (into (attribute-entries entry))
          (into (when (not= :none content)
                  [[:content {:optional true} (type-schema content)]]))
          (into (child-entries (spec/children-context entry)))
          (conj [:xml/unknown {:optional true} unknown]))
      (type-schema content))))

(def registry
  "Every element's schema by `ref-key`, plus `::feed` for the channel map
  that `parse` returns. Elements that appear under several parents share one
  schema, since their attributes and children do not depend on the parent."
  (-> (into {} (for [[tag [entry]] spec/by-tag]
                 [(ref-key tag) (entry-schema entry)]))
      (assoc ::feed (-> [:map [:rss/version {:optional true} :string]]
                        (into (child-entries :channel))
                        (conj [:xml/unknown {:optional true} unknown])))))

(def feed
  "The schema of a feed map, self-contained."
  [:schema {:registry registry} ::feed])

(def item
  "The schema of an item map, self-contained."
  [:schema {:registry registry} :rss/item])

(defn tag
  "The self-contained schema for the element `tag` describes, e.g.
  `(tag :podcast/value)`."
  [tag]
  [:schema {:registry registry} (ref-key tag)])

(comment
  (tag :podcast/transcript)
  ;; => [:schema {:registry ...} :podcast/transcript]
  (get registry :podcast/transcript)
  ;; => [:map [:url :string] [:type :string] [:language {:optional true} :string] ...]
  #_.)
