(ns dk.simongray.podcast.feed.coerce
  "Coercion between the strings in a feed and the values in the model, by the
  value types the tag registry uses. `parse` never throws: a string that does
  not read as its type comes back as `::invalid`, and `format` turns a model
  value back into its canonical string."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.time.rrule :as rrule]
            [dk.simongray.podcast.util.xml :as xml]))

(def coerced-types
  "The value types whose model values are not strings, so that a string
  where one is expected means the value did not coerce."
  #{:integer :decimal :boolean :yes-no :presence :rfc2822 :iso8601 :duration :rrule :geo :osm})

(def types
  "The value types a registry entry may give an attribute or node text:
  `coerced-types` and the types that stay strings but tell the validator
  what to check."
  (into #{:text :html :url :email :mime :language :enum} coerced-types))

(defn- read-boolean
  [s]
  (case (str/lower-case s)
    "true"  true
    "false" false
    ::invalid))

(defn- read-yes-no
  [s]
  (case (str/lower-case s)
    "yes" true
    "no"  false
    ::invalid))

(defn- read-integer
  "The whole number written as `s`, when both platforms hold it exactly; a
  larger one does not read, on the JVM either, so that the two agree."
  [s]
  (let [n (when (re-matches #"[+-]?\d+" s) (parse-long s))]
    (if (and n (<= (abs n) time/max-whole))
      n
      ::invalid)))

(defn- read-decimal
  "The number written as `s`, a whole one too large for `read-integer` as a
  double; one too large for a double does not read."
  [s]
  (let [n (cond
            (re-matches #"[+-]?\d+" s)                       (let [n (read-integer s)]
                                                               (if (= ::invalid n) (parse-double s) n))
            (re-matches #"[+-]?\d*\.\d+(?:[eE][+-]?\d+)?" s) (time/whole->long (parse-double s)))]
    (if (and n (not (infinite? n)))
      n
      ::invalid)))

(defn- read-geo
  "A geo URI (RFC 5870, section 3.3),
  \"geo:30.2672,-97.7431[,altitude][;u=uncertainty]\", as a map; nil when a
  part does not read."
  [s]
  (when-let [[_ lat lon alt u] (re-matches #"(?i)\s*geo:([+-]?\d+(?:\.\d+)?),\s*([+-]?\d+(?:\.\d+)?)(?:,\s*([+-]?\d+(?:\.\d+)?))?(?:;u=(\d+(?:\.\d+)?))?\s*"
                                           (str s))]
    (let [m (cond-> {:lat (read-decimal lat) :lon (read-decimal lon)}
              alt (assoc :altitude (read-decimal alt))
              u   (assoc :uncertainty (read-decimal u)))]
      (when-not (some #{::invalid} (vals m))
        m))))

(defn- read-osm
  "The osm of a podcast:location, \"R113314\" or \"W5013364#3\", as a map; nil
  when a number in it does not read or is 0, which OpenStreetMap never
  gives."
  [s]
  (when-let [[_ type id revision] (re-matches #"\s*([NWR])(\d+)(?:#(\d+))?\s*" (str s))]
    (let [m (cond-> {:type type :id (read-integer id)}
              revision (assoc :revision (read-integer revision)))]
      (when (every? #(and (number? %) (pos? %)) (vals (dissoc m :type)))
        m))))

(defn- or-invalid
  [x]
  (if (nil? x) ::invalid x))

(defn parse
  "Read the string `s` as a value of `type`; `::invalid` when it cannot be.
  The white space around `s` goes first, no-break spaces included, which
  the JVM would otherwise keep and JavaScript not.

  An element of the type :presence has no text. It is true because it is
  there, whatever `s` is."
  [type s]
  (let [s (xml/trim s)]
    (case type
      :integer  (read-integer s)
      :decimal  (read-decimal s)
      :boolean  (read-boolean s)
      :yes-no   (read-yes-no s)
      :presence true
      :rfc2822  (or-invalid (some-> (time/parse-date s) time/floor-seconds))
      :iso8601  (or-invalid (or (time/parse-iso8601 s) (time/parse-rfc2822 s)))
      :duration (or-invalid (time/parse-duration s))
      :rrule    (or-invalid (rrule/parse s))
      :geo      (or-invalid (read-geo s))
      :osm      (or-invalid (read-osm s))
      s)))

(defn invalid?
  "Whether `x` is the marker `parse` returns for an unreadable value."
  [x]
  (= x ::invalid))

(defn- finite?
  [n]
  (and (number? n) (== n n) (not (infinite? n))))

(defn of-type?
  "Whether `v` is a model value of `type`, of the kind `parse` makes of a
  string that reads: a whole number both platforms hold exactly, a finite
  number, a boolean, an instant, the map of a rule, a geo URI or an OSM
  reference, and for the types that stay strings, a string. The value of
  the type :presence is true, and a feed without the element has no value
  for it."
  [type v]
  (case type
    :integer             (and (integer? v) (<= (abs v) time/max-whole))
    (:decimal :duration) (finite? v)
    (:boolean :yes-no)   (boolean? v)
    :presence            (true? v)
    (:rfc2822 :iso8601)  (inst? v)
    :rrule               (and (map? v) (contains? rrule/freq-values (:freq v)))
    :geo                 (and (map? v) (finite? (:lat v)) (finite? (:lon v)))
    :osm                 (and (map? v) (string? (:type v)) (integer? (:id v)))
    (string? v)))

(defn plain-decimal
  "Write the number `n` without an exponent, as XML consumers expect:
  5.0E-8 becomes \"0.00000005\". A number that is not finite is written as
  Clojure prints it."
  [n]
  (cond
    (integer? n)
    (str n)

    (not #?(:clj (Double/isFinite (double n)) :cljs (js/isFinite n)))
    (str n)

    :else
    #?(:clj  (let [s (.toPlainString (BigDecimal/valueOf (double n)))]
               (if (str/includes? s ".")
                 (str/replace s #"\.?0+$" "")
                 s))
       :cljs (let [s (str n)]
               (if (re-find #"[eE]" s)
                 (str/replace (.toFixed n 12) #"\.?0+$" "")
                 s)))))

(defn- format-geo
  [{:keys [lat lon altitude uncertainty]}]
  (str "geo:" (plain-decimal lat) "," (plain-decimal lon)
       (when (some? altitude) (str "," (plain-decimal altitude)))
       (when (some? uncertainty) (str ";u=" (plain-decimal uncertainty)))))

(defn- format-osm
  [{:keys [type id revision]}]
  (str type id (when (some? revision) (str "#" revision))))

(defn format
  "Write the model value `v` of `type` in its canonical string form. A value
  that is not of its type, which `validate` reports, is written as it is,
  a keyword by its name, so that `:no` for a boolean stays no."
  [type v]
  (if-not (of-type? type v)
    (if (keyword? v) (name v) (str v))
    (case type
      :boolean             (if v "true" "false")
      :yes-no              (if v "yes" "no")
      :rfc2822             (time/format-rfc2822 v)
      :iso8601             (time/format-iso8601 v)
      (:duration :decimal) (plain-decimal v)
      :rrule               (rrule/format v)
      :geo                 (format-geo v)
      :osm                 (format-osm v)
      (str v))))
