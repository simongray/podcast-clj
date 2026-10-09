(ns dk.simongray.podcast.util.entities
  "Character and entity references in the text of feeds, HTML's included,
  which XML alone doesn't know."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.entities :as html-entities]))

(def xml-entities
  "The five entities that section 4.6 of XML 1.0 predefines."
  #{"amp" "lt" "gt" "quot" "apos"})

(defn html-entity
  "The characters of the HTML entity `name`, e.g. \"nbsp\", or nil for a
  name that the table of html-pieces lacks."
  [name]
  (get html-entities/references (str name ";")))

(defn ^:no-doc character-references
  "The text `s` as numeric character references, one for each code point,
  e.g. &#160; for a no-break space."
  [s]
  #?(:clj  (apply str (map #(str "&#" % ";") (.toArray (.codePoints ^String s))))
     :cljs (apply str (map #(str "&#" (.codePointAt % 0) ";") (js/Array.from s)))))

(defn- code-point->str
  [n]
  #?(:clj  (String. (Character/toChars n))
     :cljs (js/String.fromCodePoint n)))

(defn ^:no-doc code-point
  "The code point of the numeric reference `ref`, e.g. #65 or #x41, or nil
  when it isn't one that a string can hold: zero, a surrogate, or past
  U+10FFFF."
  [ref]
  (let [n (if (re-find #"^#[xX]" ref)
            #?(:clj  (try
                       (Long/parseLong (subs ref 2) 16)
                       (catch Exception _ nil))
               :cljs (js/parseInt (subs ref 2) 16))
            (parse-long (subs ref 1)))]
    (when (and n
               (or (<= 1 n 0xD7FF)
                   (<= 0xE000 n 0x10FFFF)))
      n)))

(defn decode-entities
  "The text `s` with the character and entity references replaced by the
  characters that they stand for, HTML entities included. Unknown
  references, and numeric ones outside what a string can hold, are kept."
  [s]
  (str/replace s #"&(#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);"
               (fn [[whole ref]]
                 (cond
                   (str/starts-with? ref "#")
                   (if-let [n (code-point ref)]
                     (code-point->str n)
                     whole)

                   :else
                   (or (html-entity ref) whole)))))
