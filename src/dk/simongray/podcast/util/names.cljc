(ns dk.simongray.podcast.util.names
  "The keys of podcast-clj's maps, from the camelCase names of XML and
  JSON."
  (:require [clojure.string :as str]))

(defn kebab
  "The camelCase XML name `s` in kebab-case, e.g. startTime as start-time."
  [s]
  ;; most names are in lower case already, and need no pattern
  (if (= s (str/lower-case s))
    s
    (-> s
        (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
        str/lower-case)))

(defn camel
  "The kebab-case name `s` in camelCase, e.g. start-time as startTime, the
  inverse of kebab."
  [s]
  (str/replace s #"-([a-z0-9])" #(str/upper-case (second %))))

(defn attr-key
  "The model key of the XML attribute named by the keyword `k`:
  kebab-cased when it has no namespace, or as it is otherwise."
  [k]
  (if (namespace k)
    k
    (keyword (kebab (name k)))))
