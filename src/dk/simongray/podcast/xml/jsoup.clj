(ns dk.simongray.podcast.xml.jsoup
  "A lenient XML parser for feeds that are not well-formed at all, built on
  jsoup's XML mode: unquoted attributes, stray angle brackets in text,
  unclosed and mismatched tags all yield a best-effort tree instead of an
  error. Names are kept exactly as written, so the result is the same raw
  Hiccup `dk.simongray.podcast.xml/parse-raw` produces.

  jsoup is an optional dependency. When org.jsoup/jsoup is on the classpath,
  `dk.simongray.podcast/parse` falls back to `parse-raw` for documents the
  strict parser rejects even after repair, and says so in the report."
  (:require [dk.simongray.podcast.xml :as xml])
  (:import [java.io InputStream]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Document Element Node TextNode]
           [org.jsoup.parser Parser]))

(defn- attrs
  [^Element element]
  (into {} (for [^Attribute a (.attributes element)]
             [(.getKey a) (.getValue a)])))

(defn- node->raw
  "Raw Hiccup for the jsoup `node` at `depth`, 1 for the root: elements and
  their text, nothing else; see `xml/check-depth`."
  [^Node node depth]
  (condp instance? node
    TextNode (.getWholeText ^TextNode node)
    Element  (do (xml/check-depth depth)
                 (into [(.tagName ^Element node) (attrs node)]
                       (xml/compact-text (keep #(node->raw % (inc depth)) (.childNodes ^Element node)))))
    nil))

(defn- document
  ^Document [xml]
  (cond
    (string? xml)
    (Jsoup/parse ^String xml "" (Parser/xmlParser))

    (instance? InputStream xml)
    (Jsoup/parse ^InputStream xml nil "" (Parser/xmlParser))

    :else
    (throw (ex-info "Cannot parse XML from this value" {:type (type xml)}))))

(defn parse-raw
  "Parse `xml` (a String, or an InputStream whose charset jsoup detects) into
  raw Hiccup the way `dk.simongray.podcast.xml/parse-raw` does, but
  leniently: jsoup rejects nothing. Throws `::xml/malformed` only when the
  document has no element in it at all, or nests deeper than
  `xml/max-depth`."
  [xml]
  (if-let [root (first (.children (document xml)))]
    (node->raw root 1)
    (throw (ex-info "Malformed XML: no root element" {:type ::xml/malformed}))))

(comment
  (parse-raw "<rss version=2.0><channel><title>A <3 B</title></channel></rss>")
  #_.)
