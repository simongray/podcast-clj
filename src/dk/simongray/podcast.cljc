(ns dk.simongray.podcast
  "Podcast feeds as data: RSS 2.0, the iTunes namespace and the podcast
  namespace of Podcasting 2.0, parsed leniently and emitted by the book.

  This namespace is the front door; each step behind it is its own namespace
  and can be used alone:

      xml            XML as Hiccup, namespaces resolved by URI
      quirks         repairs and normalizations for feeds that bend the standards
      spec           the tag registry every interpreter reads
      feed           Hiccup to feed maps and back
      feed.validate  the registry-driven validator and its profiles

  `parse` runs the strict path first and falls back to repair only when the
  document is malformed, and everything the lenient path did is in the
  report. `parse`, `parse-tree`, `emit` and `fetch!` take `:registry` in
  their options, the tag registry to interpret, so that a vocabulary
  extended with a client's own elements (see
  `dk.simongray.podcast.spec/registry`) reads and writes like the library's."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.quirks :as quirks]
            [dk.simongray.podcast.xml :as xml]
            #?@(:clj [[dk.simongray.podcast.http :as http]
                      [dk.simongray.podcast.resolve :as resolve]])))

(defn- malformed?
  [e]
  (= ::xml/malformed (:type (ex-data e))))

(def ^:no-doc lenient-parser
  "The raw parser to fall back on when a document is malformed even after
  repair, or nil.

  On the JVM it is `dk.simongray.podcast.xml.jsoup/parse-raw` when
  org.jsoup/jsoup is on the classpath, so adding jsoup to the dependencies
  is all it takes to enable it. ClojureScript has none."
  (delay #?(:clj  (try
                    (requiring-resolve 'dk.simongray.podcast.xml.jsoup/parse-raw)
                    (catch Exception _ nil))
            :cljs nil)))

(defn- strict
  "Parse `xml` strictly: `{:raw tree}`, or `{:error e}` when it is malformed."
  [xml]
  (try
    {:raw (xml/parse-raw xml)}
    (catch #?(:clj Exception :cljs :default) e
      (if (malformed? e) {:error e} (throw e)))))

(defn- repaired
  "Repair the malformed `xml`, with `opts` as for `quirks/repair`, and parse
  it again: strictly when the repairs sufficed, leniently when a lenient
  parser is available, and otherwise by throwing the strict parser's error.
  Returns `{:raw tree :report [...]}`."
  [xml opts]
  (let [{repaired :xml report :report} (quirks/repair xml opts)
        {:keys [raw error]}            (strict repaired)
        parse-leniently                @lenient-parser]
    (cond
      raw             {:raw raw :report report}
      parse-leniently {:raw    (parse-leniently repaired)
                       :report (conj report {:id :repair/lenient-parser :level :warning})}
      :else           (throw error))))

(defn parse-tree
  "Parse the RSS `xml` string into canonical Hiccup, normalized by
  `dk.simongray.podcast.quirks/normalize`. With `:quirks?` in `opts` (the
  default), namespace URI variants are recognized and a document the strict
  parser rejects is repaired and parsed again, leniently as a last resort
  when the platform has a lenient parser; `{:quirks? false}` is the strict
  path alone, neither repaired nor normalized. `:registry` is the tag
  registry the quirks read (default `spec/default`) and `:except` a set of
  repair and quirk ids to leave out. The result carries every diagnostic
  under `:podcast/report` in its metadata."
  ([xml]
   (parse-tree xml {}))
  ([xml {:keys [quirks?] :or {quirks? true} :as opts}]
   {:pre [(map? opts)]}
   (if-not quirks?
     (let [tree (xml/parse xml)]
       (vary-meta tree assoc :podcast/report (xml/report tree)))
     (let [{:keys [raw]}        (strict xml)
           {:keys [raw report]} (if raw {:raw raw :report []} (repaired xml (select-keys opts [:except])))
           tree                 (-> raw
                                    (xml/resolve-namespaces quirks/uri->alias)
                                    (quirks/normalize (select-keys opts [:registry :except])))]
       (vary-meta tree assoc :podcast/report
                  (-> []
                      (into report)
                      (into (quirks/namespace-variants raw))
                      (into (xml/report tree))
                      (into (quirks/report tree))))))))

(defn parse
  "Parse the RSS `xml` string into a feed map; `opts` are those of
  `parse-tree`, and `:registry` the tag registry to read it by (default
  `spec/default`). The report is metadata, read with `report`: what parsing
  the tree found, then what mapping it onto the model found."
  ([xml]
   (parse xml {}))
  ([xml opts]
   {:pre [(map? opts)]}
   (let [tree (parse-tree xml opts)
         feed (feed/parse tree (select-keys opts [:registry]))]
     (vary-meta feed assoc :podcast/report
                (into (vec (:podcast/report (meta tree))) (feed/report feed))))))

(defn report
  "The diagnostics recorded while parsing `feed` (or a tree from `parse-tree`):
  repairs, namespace variants, quirks applied, and values that would not
  coerce. Each is a map with `:id`, `:level` and `:message`, the message
  from the diagnostics catalogue, plus the specifics: a `:path` into the
  model, the `:value` that would not read, the `:tag` a quirk rewrote."
  [feed]
  (mapv diagnostics/describe (:podcast/report (meta feed))))

(defn emit
  "The XML document for `feed`, canonical in every way the library knows how
  to be. `opts` are those of `dk.simongray.podcast.feed/emit`: `:registry`,
  the tag registry to write it by, and `:namespaces`, alias to URI for the
  elements of namespaces the feed was not parsed with."
  ([feed]
   (emit feed {}))
  ([feed opts]
   {:pre [(map? opts)]}
   (xml/emit (feed/emit feed opts))))

#?(:clj
   (defn fetch!
     "Fetch the feed at `url` and parse it, the one step a poller repeats:

         {:status 200 :url \"…\"           ; the final URL, after redirects
          :etag \"…\" :last-modified \"…\"  ; to hand back next time
          :body \"…\" :feed {…} :report [...]  ; on a 200: the XML, the feed, its report
          :new-feed-url \"…\"}             ; when the feed declares it has moved

     `opts` may carry the `:etag` and `:last-modified` of the last fetch,
     which a server answers with a 304 when nothing changed, the
     `:timeout-seconds`, `:max-bytes` and `:user-agent` of `http/fetch!`,
     and the options of `parse`. It throws as `http/fetch!` and `parse` do."
     ([url]
      (fetch! url {}))
     ([url opts]
      {:pre [(map? opts)]}
      (let [{:keys [status body] :as response}
            (http/fetch! url (select-keys opts [:etag :last-modified :timeout-seconds :max-bytes :user-agent]))
            result (select-keys response [:status :url :etag :last-modified])]
        (if (and (= 200 status) body)
          (let [feed  (parse body (select-keys opts [:quirks? :registry :except]))
                moved (resolve/new-feed-url feed)]
            (cond-> (assoc result :body body :feed feed :report (report feed))
              moved (assoc :new-feed-url moved)))
          result)))))

(defn summarize
  "Group `diagnostics`, from `report` or from `dk.simongray.podcast.feed.validate`,
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
