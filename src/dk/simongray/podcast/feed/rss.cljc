(ns dk.simongray.podcast.feed.rss
  "Podcast feeds as data, parsed leniently from RSS and written back in
  canonical form.

  The feeds are RSS 2.0 with the iTunes namespace and the podcast namespace
  of Podcasting 2.0. The strict parse comes first, and the repairs only run
  when the document is malformed, with everything they did in the report."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.quirks :as quirks]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.qname-hiccup :as xml]))

(defn parse-tree
  "Parse the RSS document `xml` with `opts` into canonical Hiccup that has
  every diagnostic under :podcast/report in its metadata.

  The `xml` is a string or, on the JVM, an InputStream or a Reader. When
  :quirks? is false in `opts`, the document is parsed strictly, without
  repairs or normalisation. The option :registry is the tag registry that
  the quirks read, and :except a set of repair and quirk ids to leave
  out."
  ([xml]
   (parse-tree xml {}))
  ([xml {:keys [quirks?] :or {quirks? true} :as opts}]
   {:pre [(map? opts)]}
   (if-not quirks?
     (let [tree (xml/parse xml spec/uri->alias)]
       (vary-meta tree assoc :podcast/report (xml/report tree)))
     (let [raw-opts             (select-keys opts [:except])
           normalize-opts       (select-keys opts [:registry :except])
           {:keys [raw report]} (quirks/parse-raw xml raw-opts)
           stripped             (quirks/strip-default-namespace raw raw-opts)
           tree                 (-> (:raw stripped)
                                    (xml/resolve-namespaces quirks/uri->alias)
                                    (quirks/normalize normalize-opts))]
       (vary-meta tree assoc :podcast/report
                  (-> report
                      (into (:report stripped))
                      (into (quirks/namespace-variants raw))
                      (into (xml/report tree))
                      (into (quirks/report tree))))))))

(defn parse
  "Parse the RSS string `xml` into a feed map, with the `opts` of
  parse-tree. The report is in the metadata."
  ([xml]
   (parse xml {}))
  ([xml opts]
   {:pre [(map? opts)]}
   (let [tree   (parse-tree xml opts)
         feed   (feed/parse tree (select-keys opts [:registry]))
         report (into (vec (:podcast/report (meta tree)))
                      (feed/report feed))]
     (vary-meta feed assoc :podcast/report report))))

(defn try-parse
  "Parse the RSS string `xml` with the `opts` below, and give a map of the
  :feed, or of the :error when it's no feed.

  The error is the diagnostic :fetch/malformed with the :reason, for a
  document that came from somewhere else, such as a server. The `opts`
  are those of parse, and :url, the address of the document, which goes
  in the diagnostic.

  A platform without an XML parser isn't the fault of the document, so
  that error is still thrown."
  ([xml]
   (try-parse xml {}))
  ([xml {:keys [url] :as opts}]
   {:pre [(map? opts)]}
   (try
     {:feed (parse xml (select-keys opts [:quirks? :registry :except]))}
     (catch #?(:clj Exception :cljs :default) e
       (when (= ::xml/no-parser (:type (ex-data e)))
         (throw e))
       (let [malformed (cond-> {:id     :fetch/malformed
                                :reason (async/explain e)}
                         url (assoc :url url))]
         {:error (diagnostics/describe malformed)})))))

(defn report
  "The diagnostics of `feed`, or of a parsed tree. Each is a map
  of an :id, a :level and a :message, with details such as a :path into
  the model."
  [feed]
  (mapv diagnostics/describe (:podcast/report (meta feed))))

(defn emit
  "The XML document of `feed` in canonical form, with the `opts` below:

  - :registry, the tag registry to write it by
  - :namespaces, a map from alias to URI for the elements of namespaces
    that the feed wasn't parsed with"
  ([feed]
   (emit feed {}))
  ([feed opts]
   {:pre [(map? opts)]}
   (xml/emit (feed/emit feed opts))))

#?(:clj
   (comment
     (def f (parse (slurp "test/resources/feeds/namespace-example.xml")))
     (report f)
     (println (emit f))
     #_.))
