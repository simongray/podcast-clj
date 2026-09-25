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

#?(:clj
   (def lenient-parser
     "The raw parser to fall back on when a document is malformed even after
     repair: `dk.simongray.podcast.xml.jsoup/parse-raw` when org.jsoup/jsoup
     is on the classpath, nil otherwise. Adding jsoup to the dependencies is
     all it takes to enable it."
     (delay (try
              (requiring-resolve 'dk.simongray.podcast.xml.jsoup/parse-raw)
              (catch Exception _ nil)))))

(defn- lenient
  "The lenient raw parser of the platform, or nil when it has none."
  []
  #?(:clj @lenient-parser :cljs nil))

(defn- strict
  "Parse `xml` strictly: `{:raw tree}`, or `{:error e}` when it is malformed."
  [xml]
  (try
    {:raw (xml/parse-raw xml)}
    (catch #?(:clj Exception :cljs :default) e
      (if (malformed? e) {:error e} (throw e)))))

(defn- repaired
  "Repair the malformed `xml` and parse it again: strictly when the repairs
  sufficed, leniently when a lenient parser is available, and otherwise by
  throwing the strict parser's error. Returns `{:raw tree :report [...]}`."
  [xml]
  (let [{repaired :xml report :report} (quirks/repair xml)
        {:keys [raw error]}            (strict repaired)]
    (cond
      raw       {:raw raw :report report}
      (lenient) {:raw    ((lenient) repaired)
                 :report (conj report {:id :repair/lenient-parser :level :warning})}
      :else     (throw error))))

(defn parse-tree
  "Parse the RSS `xml` string into canonical Hiccup. With `:quirks?` in
  `opts` (the default), namespace URI variants are recognized and a document
  the strict parser rejects is repaired and parsed again, leniently as a
  last resort when the platform has a lenient parser; `{:quirks? false}` is
  the strict path alone. The result carries every diagnostic under
  `:podcast/report` in its metadata."
  ([xml]
   (parse-tree xml {}))
  ([xml {:keys [quirks?] :or {quirks? true} :as opts}]
   {:pre [(map? opts)]}
   (if-not quirks?
     (let [tree (xml/parse xml)]
       (vary-meta tree assoc :podcast/report (xml/report tree)))
     (let [{:keys [raw]}        (strict xml)
           {:keys [raw report]} (if raw {:raw raw :report []} (repaired xml))
           tree                 (-> raw
                                    (xml/resolve-namespaces quirks/uri->alias)
                                    quirks/normalize)]
       (vary-meta tree assoc :podcast/report
                  (-> []
                      (into report)
                      (into (quirks/namespace-variants raw))
                      (into (xml/report tree))
                      (into (quirks/report tree))))))))

(defn parse
  "Parse the RSS `xml` string into a feed map; `opts` are those of
  `parse-tree`. The report is metadata, read with `report`."
  ([xml]
   (parse xml {}))
  ([xml opts]
   (let [tree (parse-tree xml opts)
         feed (feed/parse tree)]
     (vary-meta feed assoc :podcast/report
                (into (vec (:podcast/report (meta tree)))
                      (remove (set (xml/report tree)))
                      (feed/report feed))))))

(defn report
  "The diagnostics recorded while parsing `feed` (or a tree from `parse-tree`):
  repairs, namespace variants, quirks applied, and values that would not
  coerce. Each is a map with `:id` and `:level` at least."
  [feed]
  (:podcast/report (meta feed)))

(defn emit
  "The XML document for `feed`, canonical in every way the library knows how
  to be."
  [feed]
  (xml/emit (feed/emit feed)))

(defn summarize
  "Group `diagnostics`, from `report` or from `dk.simongray.podcast.validate`,
  by id and message: one map per kind with the `:id`, `:level` and
  `:message` of its first occurrence, that occurrence as `:example`, and
  the `:count`, most frequent first. A recommendation repeated for each of
  three thousand items becomes one row."
  [diagnostics]
  (->> (group-by (juxt :id :message) diagnostics)
       (map (fn [[_ [example :as group]]]
              (-> (select-keys example [:id :level :message])
                  (assoc :count (count group) :example example))))
       (sort-by (fn [{:keys [count id]}] [(- count) (str id)]))))

#?(:clj
   (comment
     (def f (parse (slurp "test/resources/feeds/namespace-example.xml")))
     (report f)
     (println (emit f))
     #_.))
