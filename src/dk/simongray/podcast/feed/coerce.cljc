(ns dk.simongray.podcast.feed.coerce
  "Coercion between the strings of a feed and the values of the model, by
  the value types of the tag registry."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.time.rrule :as rrule]
            [dk.simongray.qname-hiccup :as xml]))

(def coerced-types
  "The value types whose model values aren't strings, so that a string
  where one is expected means that the value didn't coerce."
  #{:integer :decimal :boolean :yes-no :presence
    :rfc2822 :iso8601 :duration :rrule :geo :osm})

(def types
  "The value types that a registry entry can give an attribute or node
  text: the coerced types, and the types that stay strings but say what a
  valid value looks like."
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
  "The whole number written as `s`, when both platforms hold it exactly. A
  larger one doesn't read, on the JVM either, so that the two agree."
  [s]
  (let [n (when (re-matches #"[+-]?\d+" s)
            (parse-long s))]
    (if (and n (<= (abs n) time/max-whole))
      n
      ::invalid)))

(defn- read-decimal
  "The number written as `s`, with a whole one too large to read exactly
  as a double. One too large for a double doesn't read."
  [s]
  (let [n (cond
            (re-matches #"[+-]?\d+" s)
            (let [n (read-integer s)]
              (if (= ::invalid n)
                (parse-double s)
                n))

            (re-matches #"[+-]?\d*\.\d+(?:[eE][+-]?\d+)?" s)
            (time/whole->long (parse-double s)))]
    (if (and n (not (infinite? n)))
      n
      ::invalid)))

(def ^:no-doc geo-pattern
  "The pattern of a geo URI (RFC 5870, section 3.3), with the latitude,
  the longitude, the altitude and the uncertainty as its groups."
  (let [n "[+-]?\\d+(?:\\.\\d+)?"]
    (re-pattern (str "(?i)\\s*geo:(" n "),\\s*(" n ")(?:,\\s*(" n "))?"
                     "(?:;u=(\\d+(?:\\.\\d+)?))?\\s*"))))

(defn- read-geo
  "The geo URI `s` (RFC 5870, section 3.3) as a map, or nil when a part
  doesn't read.

  A geo URI looks like \"geo:30.2672,-97.7431\", with an optional altitude
  after another comma and an uncertainty after ;u=."
  [s]
  (when-let [[_ lat lon alt u] (re-matches geo-pattern (str s))]
    (let [m (cond-> {:lat (read-decimal lat) :lon (read-decimal lon)}
              alt (assoc :altitude (read-decimal alt))
              u   (assoc :uncertainty (read-decimal u)))]
      (when-not (some #{::invalid} (vals m))
        m))))

(defn- read-osm
  "The osm `s` of a podcast:location, e.g. \"R113314\" or \"W5013364#3\", as a
  map. It's nil when a number in it doesn't read or is 0, which
  OpenStreetMap never gives."
  [s]
  (when-let [[_ type id revision] (re-matches #"\s*([NWR])(\d+)(?:#(\d+))?\s*"
                                              (str s))]
    (let [m (cond-> {:type type :id (read-integer id)}
              revision (assoc :revision (read-integer revision)))]
      (when (every? #(and (number? %) (pos? %)) (vals (dissoc m :type)))
        m))))

(defn- or-invalid
  [x]
  (if (nil? x) ::invalid x))

(defn parse
  "Read the string `s` as a value of `type`, or as ::invalid when it can't
  be read.

  The whitespace around `s` goes first, no-break spaces included, which
  the JVM would otherwise keep and JavaScript not. An element of the type
  :presence has no text, and it's true because it's there, whatever `s`
  is."
  [type s]
  (let [s (xml/trim s)]
    (case type
      :integer  (read-integer s)
      :decimal  (read-decimal s)
      :boolean  (read-boolean s)
      :yes-no   (read-yes-no s)
      :presence true
      :rfc2822  (or-invalid (some-> (time/parse-date s) time/floor-seconds))
      :iso8601  (or-invalid (time/parse-date s))
      :duration (or-invalid (time/parse-duration s))
      :rrule    (or-invalid (rrule/parse s))
      :geo      (or-invalid (read-geo s))
      :osm      (or-invalid (read-osm s))
      s)))

(defn invalid?
  "Whether `x` is the marker of an unreadable value."
  [x]
  (= x ::invalid))

(defn of-type?
  "Whether `v` is a model value of `type`, of the kind that a string of
  that type reads as. By type, that's:

  - :integer, a whole number that both platforms hold exactly
  - :decimal and :duration, a finite number
  - :boolean and :yes-no, a boolean
  - :presence, true, since a feed without the element has no value for it
  - :rfc2822 and :iso8601, an instant
  - :rrule, the map of a rule
  - :geo and :osm, the map of a geo URI or an OSM reference
  - any other type, a string"
  [type v]
  (case type
    :integer             (and (integer? v) (<= (abs v) time/max-whole))
    (:decimal :duration) (time/finite? v)
    (:boolean :yes-no)   (boolean? v)
    :presence            (true? v)
    (:rfc2822 :iso8601)  (inst? v)
    :rrule               (and (map? v) (contains? rrule/freq-values (:freq v)))
    :geo                 (and (map? v)
                              (time/finite? (:lat v))
                              (time/finite? (:lon v)))
    :osm                 (and (map? v)
                              (string? (:type v))
                              (integer? (:id v)))
    (string? v)))

(defn plain-decimal
  "The number `n` written without an exponent, as XML consumers expect,
  e.g. 5.0E-8 as \"0.00000005\". A number that isn't finite is written as
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
       (when (some? altitude)
         (str "," (plain-decimal altitude)))
       (when (some? uncertainty)
         (str ";u=" (plain-decimal uncertainty)))))

(defn- format-osm
  [{:keys [type id revision]}]
  (str type id (when (some? revision) (str "#" revision))))

(defn format
  "The model value `v` of `type`, written in its canonical string form.

  A value that isn't of its type is written as it is. A keyword is written
  by its name, so that :no for a boolean stays no."
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
