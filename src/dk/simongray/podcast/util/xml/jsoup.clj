(ns dk.simongray.podcast.util.xml.jsoup
  "A lenient XML parser for feeds that aren't well-formed at all, built on
  the XML mode of jsoup.

  Unquoted attributes, stray angle brackets in text, and unclosed or
  mismatched tags all give a tree as good as it gets, rather than an
  error. Names are kept exactly as written, so the result is the same raw
  Hiccup that dk.simongray.podcast.util.xml/parse-raw gives.

  The library jsoup is an optional dependency. When org.jsoup/jsoup is on
  the classpath, dk.simongray.podcast.feed.rss/parse falls back to
  parse-raw for documents that the strict parser rejects even after
  repair, and the report says so."
  (:require [dk.simongray.podcast.util.xml :as xml])
  (:import [java.io InputStream]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Document Element Node TextNode]
           [org.jsoup.parser Parser]))

(defn- attrs
  [^Element element]
  (into {} (for [^Attribute a (.attributes element)]
             [(.getKey a) (.getValue a)])))

(defn- node->raw
  "The raw Hiccup of the jsoup `node` at `depth`, which is 1 for the root,
  with elements and their text only. The depth is checked as
  xml/check-depth does."
  [^Node node depth]
  (condp instance? node
    TextNode (.getWholeText ^TextNode node)
    Element  (do (xml/check-depth depth)
                 (let [children (.childNodes ^Element node)
                       raw      (keep #(node->raw % (inc depth)) children)]
                   (into [(.tagName ^Element node) (attrs node)]
                         (xml/compact-text raw))))
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
  "The raw Hiccup of `xml`, a String or an InputStream whose charset jsoup
  detects, as dk.simongray.podcast.util.xml/parse-raw gives it, but read
  leniently.

  Since jsoup rejects nothing, it throws ::xml/malformed only when the
  document has no element at all, or nests deeper than xml/max-depth."
  [xml]
  (if-let [root (first (.children (document xml)))]
    (node->raw root 1)
    (throw (ex-info "Malformed XML: no root element" {:type ::xml/malformed}))))

(comment
  (parse-raw "<rss version=2.0><channel><title>A <3 B</title></channel></rss>")
  #_.)
