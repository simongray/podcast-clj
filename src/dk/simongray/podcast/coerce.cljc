(ns dk.simongray.podcast.coerce
  "Coercion between the strings in a feed and the values in the model, by the
  value types the tag registry uses. `parse` never throws: a string that does
  not read as its type comes back as `::invalid`, and `format` turns a model
  value back into its canonical string."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time :as time]))

(def types
  "The value types a registry entry may give an attribute or node text.
  Types that are strings after all still get a distinct name so that the
  validator knows what to check."
  #{:text :html :url :email :mime :language :token :enum
    :integer :decimal :boolean :yes-no :rfc2822 :iso8601 :duration})

(defn- clean
  [s]
  (str/trim s))

(defn- read-boolean
  [s]
  (case (str/lower-case (clean s))
    "true"  true
    "false" false
    ::invalid))

(defn- read-yes-no
  [s]
  (case (str/lower-case (clean s))
    "yes" true
    "no"  false
    ::invalid))

(defn- read-integer
  [s]
  (let [s (clean s)]
    (if (re-matches #"[+-]?\d+" s)
      (parse-long s)
      ::invalid)))

(defn- read-decimal
  [s]
  (let [s (clean s)]
    (cond
      (re-matches #"[+-]?\d+" s)                       (parse-long s)
      (re-matches #"[+-]?\d*\.\d+(?:[eE][+-]?\d+)?" s) (time/whole->long (parse-double s))
      :else                                            ::invalid)))

(defn- or-invalid
  [x]
  (if (nil? x) ::invalid x))

(defn parse
  "Read the string `s` as a value of `type`; `::invalid` when it cannot be."
  [type s]
  (case type
    :integer  (read-integer s)
    :decimal  (read-decimal s)
    :boolean  (read-boolean s)
    :yes-no   (read-yes-no s)
    :rfc2822  (or-invalid (time/parse-date s))
    :iso8601  (or-invalid (or (time/parse-iso8601 s) (time/parse-rfc2822 s)))
    :duration (or-invalid (time/parse-duration s))
    :html     (clean s)
    (clean s)))

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

(defn format
  "Write the model value `v` of `type` in its canonical string form."
  [type v]
  (case type
    :boolean  (if v "true" "false")
    :yes-no   (if v "yes" "no")
    :rfc2822  (if (inst? v) (time/format-rfc2822 v) (str v))
    :iso8601  (if (inst? v) (time/format-iso8601 v) (str v))
    :duration (if (number? v) (plain-decimal v) (str v))
    :decimal  (if (number? v) (plain-decimal v) (str v))
    (str v)))
