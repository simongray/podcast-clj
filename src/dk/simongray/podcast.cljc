(ns dk.simongray.podcast
  "Podcast feeds as data, parsed leniently and written back in canonical
  form.

  The feeds are RSS 2.0 with the iTunes namespace and the podcast namespace
  of Podcasting 2.0. The strict parse comes first, and the repairs only run
  when the document is malformed, with everything they did in the report."
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
  "The parser to fall back on when a document is still malformed after the
  repairs, or nil.

  On the JVM, it's util.xml.jsoup/parse-raw when org.jsoup/jsoup is on
  the classpath, so adding jsoup to the dependencies is all it takes.
  ClojureScript has none."
  (delay #?(:clj  (try
                    (requiring-resolve 'dk.simongray.podcast.util.xml.jsoup/parse-raw)
                    (catch Exception _ nil))
            :cljs nil)))

(defn- held
  "The `xml` in a form that can be read twice, first strictly and then for
  the repairs. On the JVM, an InputStream becomes bytes, and a Reader a
  string."
  [xml]
  #?(:clj  (cond
             (instance? java.io.InputStream xml) (.readAllBytes ^java.io.InputStream xml)
             (instance? java.io.Reader xml)      (slurp xml)
             :else                               xml)
     :cljs xml))

(defn- strict
  "Parse `xml`, as held gives it, strictly into a map with the :raw tree,
  or the :error when it's malformed. Bytes go to the parser as a stream, so
  that it honours the document's own encoding."
  [xml]
  (try
    {:raw (xml/parse-raw #?(:clj  (if (bytes? xml) (java.io.ByteArrayInputStream. ^bytes xml) xml)
                            :cljs xml))}
    (catch #?(:clj Exception :cljs :default) e
      (if (malformed? e) {:error e} (throw e)))))

(defn- repaired
  "Repair the malformed `xml`, as held gives it, with `opts`, and parse it
  again into a map of the :raw tree and its :report.

  The parse is strict when the repairs were enough, and lenient when
  there's a lenient parser and :except in `opts` doesn't name
  :repair/lenient-parser. Otherwise, it throws the error of the strict
  parser."
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
  "Parse the RSS string `xml` into a feed map, with the `opts` of
  parse-tree. The report is in the metadata, where the report function
  reads it."
  ([xml]
   (parse xml {}))
  ([xml opts]
   {:pre [(map? opts)]}
   (let [tree (parse-tree xml opts)
         feed (feed/parse tree (select-keys opts [:registry]))]
     (vary-meta feed assoc :podcast/report
                (into (vec (:podcast/report (meta tree))) (feed/report feed))))))

(defn report
  "The diagnostics of `feed`, or of a tree from parse-tree. Each is a map
  of an :id, a :level and a :message, with details such as a :path into
  the model."
  [feed]
  (mapv diagnostics/describe (:podcast/report (meta feed))))

(defn emit
  "The XML document of `feed` in canonical form, with the `opts` of
  dk.simongray.podcast.feed/emit.

  The `opts` are:

  - :registry, the tag registry to write it by
  - :namespaces, a map from alias to URI for the elements of namespaces
    that the feed wasn't parsed with"
  ([feed]
   (emit feed {}))
  ([feed opts]
   {:pre [(map? opts)]}
   (xml/emit (feed/emit feed opts))))

#?(:clj
   (defn- page!
     "Fetch and parse the page at `url` of a paged feed with `opts`, and
     give a map of its :url and :feed. When the page can't be read, the
     reason is the :failure instead of the :feed."
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
     "Up to `n` pages after `feed`, the page at `url` that the URL `asked`
     led to, fetched with `opts`. Each page is the next of the one before,
     until a page repeats.

     The feed names its pages, so the :credentials in `opts` only go to
     pages at the origin of `asked`, since a redirect can move `url` to
     another origin."
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
     "The result of calling `f` as the :value. When it throws, the :error is
     the diagnostic `id` about `url`, with the reason."
     [id url f]
     (try
       {:value (f)}
       (catch Exception e
         {:error (diagnostics/describe {:id id :url url :reason (async/explain e)})}))))

#?(:clj
   (defn- with-pages
     "The feed `first-page`, the page at `url` that the URL `asked` led to,
     joined with the pages after it, up to the :max-pages of `opts`.

     A later page that can't be read gets a diagnostic in the report of the
     feed."
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
     "What fetch! gives for the 200 `response` to the request for `asked`,
     parsed with `opts`. It's the body, the feed and its report, or an
     :error for a body that isn't a feed."
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
     "Fetch the feed at `url` and parse it with the `opts` below, the one
     step that a poller repeats:

         {:status 200 :url \"…\"             ; the final URL, after redirects
          :permanent-url \"…\"               ; when redirects moved it for good
          :etag \"…\" :last-modified \"…\"    ; to send next time
          :not-before #inst \"…\"            ; when the server asks you to wait
          :body \"…\" :feed {…} :report [...] ; on a 200
          :new-feed-url \"…\"                ; when the feed declares a move
          :websub {:hubs [\"…\"] :topic \"…\"}  ; when the feed names a WebSub hub
          :error {…}}                      ; when there's no answer or no feed

     The `opts` are:

     - :etag and :last-modified, from the last fetch
     - :credentials, for a private feed, or else those in the `url`
     - :max-pages, the most pages of a paged feed to fetch and join
     - the options of http/fetch! and parse

     Nothing is thrown for what the network, the server or the body does.
     The result has an :error diagnostic when there's no answer or no
     feed, and any other status comes back as :status."
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
  "Group `diagnostics` by id and message, most frequent first: one row per
  kind, with its :count and its first occurrence as :example."
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
