(ns dk.simongray.podcast
  "Podcast feeds as data: RSS 2.0, the iTunes namespace and the podcast
  namespace of Podcasting 2.0, parsed leniently and emitted by the book.

  This namespace is the front door; each step behind it is its own namespace
  and can be used alone:

      xml      XML as Hiccup, namespaces resolved by URI
      quirks   repairs and normalizations for feeds that bend the standards
      spec     the tag registry every interpreter reads
      feed     Hiccup to feed maps and back
      validate the registry-driven validator and its profiles

  `parse` runs the strict path first and falls back to repair only when the
  document is malformed. Everything the lenient path did is in the report."
  (:require [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.quirks :as quirks]
            [dk.simongray.podcast.xml :as xml]))

(defn- malformed?
  [e]
  (= ::xml/malformed (:type (ex-data e))))

(defn parse-tree
  "Parse the RSS `xml` string into canonical Hiccup. With `quirks?` (the
  default), namespace URI variants are recognized and a document the strict
  parser rejects is repaired and parsed again. The result carries every
  finding under `:podcast/report` in its metadata."
  ([xml]
   (parse-tree xml true))
  ([xml quirks?]
   (if-not quirks?
     (let [tree (xml/parse xml)]
       (vary-meta tree assoc :podcast/report (xml/report tree)))
     (let [raw               (try (xml/parse-raw xml)
                                  (catch #?(:clj Exception :cljs :default) e
                                    (if (malformed? e) ::malformed (throw e))))
           {raw :raw
            repaired :report} (if (= raw ::malformed)
                                (let [{:keys [xml report]} (quirks/repair xml)]
                                  {:raw (xml/parse-raw xml) :report report})
                                {:raw raw :report []})
           tree              (-> raw
                                 (xml/resolve-namespaces quirks/uri->alias)
                                 quirks/normalize)]
       (vary-meta tree assoc :podcast/report
                  (-> []
                      (into repaired)
                      (into (quirks/namespace-variants raw))
                      (into (xml/report tree))
                      (into (quirks/report tree))))))))

(defn parse
  "Parse the RSS `xml` string into a feed map; see `parse-tree` for the
  `quirks?` flag. The report is metadata, read with `report`."
  ([xml]
   (parse xml true))
  ([xml quirks?]
   (let [tree (parse-tree xml quirks?)
         feed (feed/parse tree)]
     (vary-meta feed assoc :podcast/report
                (into (vec (:podcast/report (meta tree)))
                      (remove (set (xml/report tree)))
                      (feed/report feed))))))

(defn report
  "The findings recorded while parsing `feed` (or a tree from `parse-tree`):
  repairs, namespace variants, quirks applied, and values that would not
  coerce. Each is a map with `:id` and `:level` at least."
  [feed]
  (:podcast/report (meta feed)))

(defn emit
  "The XML document for `feed`, canonical in every way the library knows how
  to be."
  [feed]
  (xml/emit (feed/emit feed)))

(comment
  (def f (parse (slurp "test/resources/feeds/namespace-example.xml")))
  (report f)
  (println (emit f))
  #_.)
