(ns dk.simongray.html.entities
  "The named character references of HTML, such as &amp; and &eacute;, by
  the table in section 13.5 of the HTML Standard.

  The table is the entities.json that WHATWG publishes with the standard,
  as of 2026-10-08, under CC BY 4.0. It's read when the namespace
  compiles, so ClojureScript carries it as one string."
  #?(:cljs (:require-macros [dk.simongray.html.entities :refer [table]]))
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   (defmacro ^:no-doc table
     "The names and code points of entities.json as one string, a line for
     each name: the name without its ampersand, a space, and the code
     points in hexadecimal, separated by commas."
     []
     (->> (json/read-str (slurp (io/resource "dk/simongray/html/entities.json")))
          (sort-by key)
          (map (fn [[k {:strs [codepoints]}]]
                 (str (subs k 1) " "
                      (str/join "," (map #(Long/toHexString %) codepoints)))))
          (str/join "\n"))))

(defn ^:no-doc code-point->string
  "The character of the code point `n` as a string."
  [n]
  #?(:clj  (String. (Character/toChars (int n)))
     :cljs (js/String.fromCodePoint n)))

(defn- characters
  "The characters of the code points in hexadecimal in `codes`, separated
  by commas."
  [codes]
  (->> (str/split codes #",")
       (map #(code-point->string #?(:clj  (Long/parseLong % 16)
                                     :cljs (js/parseInt % 16))))
       (apply str)))

(def references
  "The characters of each named character reference, by its name without
  the ampersand. A name ends in a semicolon, except for the legacy names
  that HTML also reads without one, so both \"amp;\" and \"amp\" give &."
  (into {}
        (map (fn [line]
               (let [space (str/index-of line " ")]
                 [(subs line 0 space) (characters (subs line (inc space)))])))
        (str/split-lines (table))))

(comment
  (references "eacute;")
  ;; => "é"
  (references "NotEqualTilde;")
  ;; => "≂̸"
  (count references)
  ;; => 2231
  #_.)
