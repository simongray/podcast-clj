(ns dk.simongray.podcast
  "Podcast feeds as data: RSS 2.0, the iTunes namespace and the podcast
  namespace of Podcasting 2.0, parsed leniently and emitted by the book.

  This namespace is the front door; each step behind it is its own namespace
  and can be used alone:

      util.xml       XML as Hiccup, namespaces resolved by URI
      feed.quirks    repairs and normalizations for feeds that bend the standards
      feed.spec      the tag registry every interpreter reads
      feed           Hiccup to feed maps and back
      feed.validate  the registry-driven validator and its profiles

  `parse` runs the strict path first and falls back to repair only when the
  document is malformed, and everything the lenient path did is in the
  report. `parse`, `parse-tree`, `emit` and `fetch!` take `:registry` in
  their options, the tag registry to interpret, so that a vocabulary
  extended with a client's own elements (see
  `dk.simongray.podcast.feed.spec/registry`) reads and writes like the
  library's."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.quirks :as quirks]
            [dk.simongray.podcast.util.xml :as xml]
            #?@(:clj [[dk.simongray.podcast.feed.resolve :as resolve]
                      [dk.simongray.podcast.updates.websub :as websub]
                      [dk.simongray.podcast.util.async :as async]
                      [dk.simongray.podcast.util.http :as http]])))

(defn- malformed?
  [e]
  (= ::xml/malformed (:type (ex-data e))))

(def ^:no-doc lenient-parser
  "The raw parser to fall back on when a document is malformed even after
  repair, or nil.

  On the JVM it is `dk.simongray.podcast.util.xml.jsoup/parse-raw` when
  org.jsoup/jsoup is on the classpath, so adding jsoup to the dependencies
  is all it takes to enable it. ClojureScript has none."
  (delay #?(:clj  (try
                    (requiring-resolve 'dk.simongray.podcast.util.xml.jsoup/parse-raw)
                    (catch Exception _ nil))
            :cljs nil)))

(defn- held
  "`xml` in a form that can be read twice, first strictly and then for the
  repairs: on the JVM an InputStream as its bytes and a Reader as its text."
  [xml]
  #?(:clj  (cond
             (instance? java.io.InputStream xml) (.readAllBytes ^java.io.InputStream xml)
             (instance? java.io.Reader xml)      (slurp xml)
             :else                               xml)
     :cljs xml))

(defn- strict
  "Parse `xml`, as `held` gives it, strictly: `{:raw tree}`, or `{:error e}`
  when it is malformed. Bytes go to the parser as a stream, so that it
  honours the document's own encoding declaration."
  [xml]
  (try
    {:raw (xml/parse-raw #?(:clj  (if (bytes? xml) (java.io.ByteArrayInputStream. ^bytes xml) xml)
                            :cljs xml))}
    (catch #?(:clj Exception :cljs :default) e
      (if (malformed? e) {:error e} (throw e)))))

(defn- repaired
  "Repair the malformed `xml`, as `held` gives it, with `opts` as for
  `quirks/repair`, and parse it again: strictly when the repairs sufficed,
  leniently when a lenient parser is available and `:except` does not name
  `:repair/lenient-parser`, and otherwise by throwing the strict parser's
  error. Returns `{:raw tree :report [...]}`."
  [xml {:keys [except] :as opts}]
  (let [text                           #?(:clj  (if (bytes? xml) (http/decode {} xml) xml)
                                          :cljs xml)
        {repaired :xml report :report} (quirks/repair text opts)
        {:keys [raw error]}            (strict repaired)
        parse-leniently                (when-not (contains? except :repair/lenient-parser) @lenient-parser)]
    (cond
      raw             {:raw raw :report report}
      parse-leniently {:raw    (parse-leniently repaired)
                       :report (conj report {:id :repair/lenient-parser :level :warning})}
      :else           (throw error))))

(defn parse-tree
  "Parse the RSS document `xml`, a string or, on the JVM, an InputStream or a
  Reader, into canonical Hiccup, normalized by
  `dk.simongray.podcast.feed.quirks/normalize`. With `:quirks?` in `opts` (the
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
     (let [xml                  (held xml)
           {:keys [raw]}        (strict xml)
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
   (defn- page!
     "The page at `url` of a feed that comes in pages, fetched and parsed as
     `opts` say: `{:url :feed}`, or `{:url :failure}` with the reason it
     could not be read."
     [url opts]
     (try
       (let [{:keys [status body]} (http/fetch! url (select-keys opts [:credentials :timeout-seconds :max-bytes :user-agent :allow?]))]
         (if (and (= 200 status) body)
           {:url url :feed (parse body (select-keys opts [:quirks? :registry :except]))}
           {:url url :failure (str "HTTP " status)}))
       (catch Exception e
         {:url url :failure (or (ex-message e) (str e))}))))

#?(:clj
   (defn- later-pages
     "The pages after `feed`, the page at `url`, as `page!` reads them: each
     the page that the one before names as the next (see
     `resolve/next-page`), `n` at most, a page read before ending them.

     The feed names the pages, so the `:credentials` in `opts` go only to
     the pages at the origin of `asked`, the URL that the app gave them
     for. After a redirect, `url` can be at another origin."
     [asked url feed n opts]
     (let [next-of  (fn [url feed seen]
                      (when-let [next (some->> (resolve/next-page feed) (http/absolute-url url))]
                        (when-not (seen next)
                          {:url next :seen (conj seen next)})))
           opts-for (fn [page-url] (cond-> opts (not (http/same-origin? asked page-url)) (dissoc :credentials)))]
       (into []
             (take n)
             (iteration (fn [k] (when k (assoc (page! (:url k) (opts-for (:url k))) :seen (:seen k))))
                        :initk (next-of url feed #{url})
                        :kf    (fn [{:keys [url feed seen]}] (when feed (next-of url feed seen))))))))

#?(:clj
   (defn ^:no-doc attempted
     "What `(f)` gives, as `{:value v}`. When it throws, the result is
     `{:error diagnostic}`: the diagnostic `id` about `url`, with the
     reason."
     [id url f]
     (try
       {:value (f)}
       (catch Exception e
         {:error (diagnostics/describe {:id id :url url :reason (async/explain e)})}))))

#?(:clj
   (defn- with-pages
     "The feed `first-page`, the page at `url`, joined with the pages after
     it, `:max-pages` of `opts` at most. `asked` is the URL that the app
     gave. When a later page does not read, the report of the feed tells
     so."
     [asked url first-page {:keys [max-pages] :or {max-pages 1} :as opts}]
     (let [pages  (when (> max-pages 1) (later-pages asked url first-page (dec max-pages) opts))
           failed (first (remove :feed pages))]
       (cond-> (if (seq pages)
                 (feed/join-pages (cons first-page (keep :feed pages)))
                 first-page)
         failed (vary-meta update :podcast/report conj
                           {:id :fetch/page-failed :level :warning
                            :url (:url failed) :reason (:failure failed)})))))

#?(:clj
   (defn- read-answer
     "What `fetch!` gives of the `response` of 200 to the request for
     `asked`. It is the body, the feed and its report, or the `:error` of
     a body that is no feed."
     [asked {:keys [url body] :as response} opts]
     (let [{first-page :value error :error}
           (attempted :fetch/malformed url #(parse (str body) (select-keys opts [:quirks? :registry :except])))]
       (if error
         (cond-> {:error error} body (assoc :body body))
         (let [feed  (with-pages asked url first-page opts)
               moved (resolve/new-feed-url feed)
               hubs  (websub/discover response first-page)]
           (cond-> {:body body :feed feed :report (report feed)}
             moved (assoc :new-feed-url moved)
             hubs  (assoc :websub hubs)))))))

#?(:clj
   (defn fetch!
     "Fetch the feed at `url` and parse it, the one step that a poller
     repeats:

         {:status 200 :url \"…\"             ; the final URL, after redirects
          :permanent-url \"…\"               ; when redirects moved it for good
          :etag \"…\" :last-modified \"…\"    ; to hand back next time
          :not-before #inst \"…\"            ; when the server says to wait
          :body \"…\" :feed {…} :report [...] ; on a 200: the XML, the feed, its report
          :new-feed-url \"…\"                ; when the feed declares it has moved
          :websub {:hubs [\"…\"] :topic \"…\"}  ; when the feed names a WebSub hub
          :error {…}}                      ; when the fetch got no answer or no feed

     `opts` can give these:

     - the `:etag` and the `:last-modified` of the last fetch. A server
       answers them with a 304 when nothing changed.
     - the `:credentials` of a private feed
     - `:timeout-seconds`, `:max-bytes`, `:user-agent` and `:allow?`, as
       `http/fetch!` takes them
     - the options of `parse`

     A user name and a password in `url` are the credentials when `opts`
     give none, and no URL in the result holds them. A server that fetches
     feeds for strangers gives `{:allow? http/public?}`, which sends
     nothing to its own network.

     `fetch!` throws nothing for what the network, the server or the body
     does. When the request fails, the result is `{:url :error}`, and the
     `:error` is the diagnostic `:fetch/unreachable`. When the body of a
     200 is no feed, the result has the diagnostic `:fetch/malformed` as
     its `:error`, and no feed. Each other status is an answer, so the
     result has the `:status` and no `:error`.

     A feed can come in pages, and each page names the next (see
     `resolve/next-page`). With `:max-pages` above 1, `fetch!` fetches that
     many pages at most, and joins them into one feed (see
     `feed/join-pages`). This is for the first fetch of a feed with a long
     past. `:body` stays the XML of the first page. A later page that does
     not read ends the pages, and the report tells so."
     ([url]
      (fetch! url {}))
     ([url opts]
      {:pre [(map? opts)]}
      (let [[url in-url] (http/split-credentials url)
            opts         (cond-> opts (and in-url (not (:credentials opts))) (assoc :credentials in-url))
            {response :value error :error}
            (attempted :fetch/unreachable url
                       #(http/fetch! url (select-keys opts [:etag :last-modified :credentials :timeout-seconds :max-bytes :user-agent :allow?])))
            result       (select-keys response [:status :url :permanent-url :etag :last-modified :not-before])]
        (cond
          error                      {:url url :error error}
          (= 200 (:status response)) (merge result (read-answer url response opts))
          :else                      result)))))

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
