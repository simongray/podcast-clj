(ns dk.simongray.podcast.feed.coerce
  "Coercion between the strings in a feed and the values in the model, by the
  value types the tag registry uses. `parse` never throws: a string that does
  not read as its type comes back as `::invalid`, and `format` turns a model
  value back into its canonical string."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time.rrule :as rrule]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.xml :as xml]))

(def coerced-types
  "The value types whose model values are not strings, so that a string
  where one is expected means the value did not coerce."
  #{:integer :decimal :boolean :yes-no :rfc2822 :iso8601 :duration :rrule :geo :osm})

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
  "A geo URI, \"geo:30.2672,-97.7431[,altitude][;u=uncertainty]\", as a map;
  nil when a part does not read."
  [s]
  (when-let [[_ lat lon alt u] (re-matches #"(?i)\s*geo:([+-]?\d+(?:\.\d+)?),\s*([+-]?\d+(?:\.\d+)?)(?:,\s*([+-]?\d+(?:\.\d+)?))?(?:;u=(\d+(?:\.\d+)?))?\s*"
                                           (str s))]
    (let [m (cond-> {:lat (read-decimal lat) :lon (read-decimal lon)}
              alt (assoc :altitude (read-decimal alt))
              u   (assoc :uncertainty (read-decimal u)))]
      (when-not (some #{::invalid} (vals m))
        m))))

(defn- read-osm
  "An OpenStreetMap reference, \"R113314\" or \"W5013364#3\", as a map; nil
  when a number in it does not read."
  [s]
  (when-let [[_ type id revision] (re-matches #"\s*([NWR])(\d+)(?:#(\d+))?\s*" (str s))]
    (let [m (cond-> {:type type :id (read-integer id)}
              revision (assoc :revision (read-integer revision)))]
      (when-not (some #{::invalid} (vals m))
        m))))

(defn- or-invalid
  [x]
  (if (nil? x) ::invalid x))

(defn parse
  "Read the string `s` as a value of `type`; `::invalid` when it cannot be.
  The white space around `s` goes first, no-break spaces included, which
  the JVM would otherwise keep and JavaScript not."
  [type s]
  (let [s (xml/trim s)]
    (case type
      :integer  (read-integer s)
      :decimal  (read-decimal s)
      :boolean  (read-boolean s)
      :yes-no   (read-yes-no s)
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
  "Write the model value `v` of `type` in its canonical string form."
  [type v]
  (case type
    :boolean  (if (string? v) v (if v "true" "false"))
    :yes-no   (if (string? v) v (if v "yes" "no"))
    :rfc2822  (if (inst? v) (time/format-rfc2822 v) (str v))
    :iso8601  (if (inst? v) (time/format-iso8601 v) (str v))
    :duration (if (number? v) (plain-decimal v) (str v))
    :decimal  (if (number? v) (plain-decimal v) (str v))
    :rrule    (if (map? v) (rrule/format v) (str v))
    :geo      (if (map? v) (format-geo v) (str v))
    :osm      (if (map? v) (format-osm v) (str v))
    (str v)))
