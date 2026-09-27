(ns dk.simongray.podcast.feed.schema
  "The feed model as Malli schemas, generated from the tag registry so that
  they can never drift from what `parse` produces and `emit` accepts.

  The schemas are plain data in Malli's vector syntax and Malli itself is not
  a dependency: hand `feed`, `item` or any tag's schema from `tag` to
  `malli.core/schema` in your own project to validate, explain, generate or
  coerce with it, and pass `:registry` in the options of `schemas`,
  `feed-schema` and `tag` for a vocabulary extended with your own elements.

      (m/validate schema/feed (podcast/parse xml))

  The schemas describe the model when every value coerced, so validating a
  parsed feed against them tells you where `parse` forgave a value and kept
  it as a string. Enumerations are left as strings, since feeds vary their
  case and the validator judges them case insensitively."
  (:require [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

(def types
  "The Malli schema for each value type of the registry; strings otherwise."
  {:integer  :int
   :decimal  'number?
   :boolean  :boolean
   :yes-no   :boolean
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
  "The key an element's schema is registered under: its tag, namespaced
  `rss` when the tag has no namespace, so that `:title` becomes `:rss/title`."
  [tag]
  (if (namespace tag)
    tag
    (keyword "rss" (name tag))))

(def ^:no-doc unknown
  "The schema of the Hiccup an element may keep for elements the registry
  does not know; never generated."
  [:vector {:gen/max 0} :any])

(def ^:no-doc generated-max
  "How many of a repeatable element a generated feed holds at most, so that
  generation does not grow multiplicatively with Malli's size parameter;
  validation ignores it."
  5)

(defn- attribute-entries
  "The map entries for the declared attributes of `entry`."
  [entry]
  (for [[k {:keys [type required]}] (:attrs entry)]
    [(xml/attr-key k) {:optional (not required)} (type-schema (or type :text))]))

(defn- child-entries
  "The map entries for the elements of `registry` that may appear in the
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
  "The schema for one occurrence of the element `entry` of `registry`
  describes: its coerced text when it is a scalar, otherwise a map of its
  attributes, its `:content` and its children."
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
  "Every element's schema by `ref-key`, plus `::feed` for the channel map
  that `parse` returns, as a Malli registry, for the tag registry in `opts`
  (`:registry`, default `spec/default`). Elements that appear under several
  parents share one schema, since their attributes and children do not
  depend on the parent."
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
  "The self-contained schema of a feed map, with `opts` as for `schemas`."
  ([]
   (feed-schema {}))
  ([opts]
   [:schema {:registry (schemas opts)} ::feed]))

(defn tag
  "The self-contained schema for the element tagged `k`, e.g. `(tag
  :podcast/value)`, with `opts` as for `schemas`."
  ([k]
   (tag k {}))
  ([k opts]
   [:schema {:registry (schemas opts)} (ref-key k)]))

(def feed
  "The schema of a feed map, self-contained."
  (feed-schema))

(def item
  "The schema of an item map, self-contained."
  (tag :item))

(comment
  (tag :podcast/transcript)
  ;; => [:schema {:registry ...} :podcast/transcript]
  (get (schemas) :podcast/transcript)
  ;; => [:map [:url :string] [:type :string] [:language {:optional true} :string] ...]
  #_.)
