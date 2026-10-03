(ns dk.simongray.podcast
  "Podcast feeds as data, parsed leniently and written back in canonical
  form.

  The feeds are RSS 2.0 with the iTunes namespace and the podcast namespace
  of Podcasting 2.0. The strict parse comes first, and the repairs only run
  when the document is malformed, with everything they did in the report.

  The fetch! function fetches and parses a feed, and find-feeds! finds the
  feeds of a show from what a listener types, such as a domain, the
  address of a web page or a link of an app. Both send their requests
  with the :send of their options, and in ClojureScript they give
  promises."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.quirks :as quirks]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.updates.websub :as websub]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.xml :as xml]))

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

(defn- read-page
  "The page at `url` of a paged feed in the `response` to its request,
  parsed with `opts`. It's a map of the :url and the :feed, or of the :url
  and the :failure, the reason that the page can't be read."
  [url {:keys [status body] :as response} opts]
  (if (and (= 200 status) body)
    (try
      {:url url :feed (parse body (select-keys opts [:quirks? :registry :except]))}
      (catch #?(:clj Exception :cljs :default) e
        {:url url :failure (async/explain e)}))
    {:url url :failure (str "HTTP " status)}))

(defn- page!
  "Fetch the page at `url` of a paged feed with `opts`, and read it as
  read-page does. Deferred."
  [url opts]
  (async/then (async/attempt #(http/fetch! url (select-keys opts [:credentials :timeout-seconds :max-bytes :user-agent :allow? :send])))
              (fn [{:keys [value error]}]
                (if error
                  {:url url :failure (async/explain error)}
                  (read-page url value opts)))))

(defn- later-pages
  "Up to `n` pages after `feed`, the page at `url` that the URL `asked` led
  to, fetched with `opts`. Each page is the next of the one before, until
  a page repeats. Deferred.

  The feed names its pages, so the :credentials in `opts` only go to
  pages at the origin of `asked`, since a redirect can move `url` to
  another origin."
  [asked url feed n opts]
  (let [next-of  (fn [url feed seen]
                   (when-let [next (some->> (resolve/next-page feed) (http/absolute-url url))]
                     (when-not (seen next)
                       {:url next :seen (conj seen next)})))
        opts-for (fn [page-url] (cond-> opts (not (http/same-origin? asked page-url)) (dissoc :credentials)))]
    (async/iteration (fn [k] (when k (async/then (page! (:url k) (opts-for (:url k))) #(assoc % :seen (:seen k)))))
                     :initk (next-of url feed #{url})
                     :kf    (fn [{:keys [url feed seen]}] (when feed (next-of url feed seen)))
                     :limit n)))

(defn ^:no-doc attempted
  "The result of calling `f` as the :value. When it throws, the :error is
  the diagnostic `id` about `url`, with the reason. A platform without an
  XML parser isn't the fault of the answer, so that error is thrown."
  [id url f]
  (try
    {:value (f)}
    (catch #?(:clj Exception :cljs :default) e
      (when (= ::xml/no-parser (:type (ex-data e)))
        (throw e))
      {:error (diagnostics/describe {:id id :url url :reason (async/explain e)})})))

(defn- joined
  "The feed `first-page` joined with the `pages` after it, as later-pages
  gives them. A page that can't be read gets a diagnostic in the report of
  the feed."
  [first-page pages]
  (let [failed (first (remove :feed pages))]
    (cond-> (if (seq pages)
              (feed/join-pages (cons first-page (keep :feed pages)))
              first-page)
      failed (vary-meta update :podcast/report conj
                        {:id :fetch/page-failed :level :warning
                         :url (:url failed) :reason (:failure failed)}))))

(defn- with-pages
  "The feed `first-page`, the page at `url` that the URL `asked` led to,
  joined with the pages after it, up to the :max-pages of `opts`.
  Deferred."
  [asked url first-page {:keys [max-pages] :or {max-pages 1} :as opts}]
  (async/then (when (> max-pages 1) (later-pages asked url first-page (dec max-pages) opts))
              #(joined first-page %)))

(defn- feed-result
  "What fetch! gives for the 200 `response` whose body is the page
  `first-page`, once that page is joined with the pages after it in
  `feed`: the body, the feed and its report, and any move or WebSub hubs."
  [{:keys [body] :as response} first-page feed]
  (let [moved (resolve/new-feed-url feed)
        hubs  (websub/discover response first-page)]
    (cond-> {:body body :feed feed :report (report feed)}
      moved (assoc :new-feed-url moved)
      hubs  (assoc :websub hubs))))

(defn- read-answer
  "What fetch! gives for the 200 `response` to the request for `asked`,
  parsed with `opts`, as feed-result gives it, or an :error for a body
  that isn't a feed. Deferred."
  [asked {:keys [url body] :as response} opts]
  (let [{first-page :value error :error}
        (attempted :fetch/malformed url #(parse (str body) (select-keys opts [:quirks? :registry :except])))]
    (if error
      (cond-> {:error error} body (assoc :body body))
      (async/then (with-pages asked url first-page opts) #(feed-result response first-page %)))))

(defn fetch!
  "Fetch the feed at `url` and parse it with the `opts` below, the one
  step that a poller repeats. In ClojureScript, it gives a promise:

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
  - :send, the function that sends each request, http/call! by default
  - the options of http/fetch! and parse

  Nothing is thrown for what the network, the server or the body does.
  The result has an :error diagnostic when there's no answer or no
  feed, and any other status comes back as :status."
  ([url]
   (fetch! url {}))
  ([url opts]
   {:pre [(map? opts)]}
   (let [[url in-url] (http/split-credentials url)
         opts         (cond-> opts (and in-url (not (:credentials opts))) (assoc :credentials in-url))]
     (async/then (async/attempt #(http/fetch! url (select-keys opts [:etag :last-modified :credentials :timeout-seconds :max-bytes :user-agent :allow? :send])))
                 (fn [{response :value error :error}]
                   (let [result (select-keys response [:status :url :permanent-url :etag :last-modified :not-before])]
                     (cond
                       error                      {:url url :error (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})}
                       (= 200 (:status response)) (async/then (read-answer url response opts) #(merge result %))
                       :else                      result)))))))

(defn- discovery-request
  "The request for the feed or the web page at `url`, with the :user-agent
  and the :credentials of `opts`."
  [url {:keys [user-agent credentials] :as opts}]
  {:url     url
   :headers (cond-> {"Accept" "application/rss+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.8, */*;q=0.1"}
              user-agent  (assoc "User-Agent" user-agent)
              credentials (assoc "Authorization" (http/authorization credentials)))})

(defn- failure
  "The diagnostic of the `attempt` at `url` when it gave no document: the
  request failed, or the answer has a status that isn't a success."
  [url {:keys [value error] :as attempt}]
  (cond
    error                                     (diagnostics/describe {:id :fetch/unreachable :url url :reason (async/explain error)})
    (not (<= 200 (long (:status value)) 299)) (diagnostics/describe {:id :fetch/status :url url :status (:status value)})))

(defn- read-feed
  "The candidate in the `attempt` at the feed at `url`, found where
  `where` says and parsed with `opts`, or the diagnostic as :error when
  the request failed or the answer isn't a feed. Deferred."
  [url where {response :value :as attempt} opts]
  (if-let [e (failure url attempt)]
    {:error e}
    (let [body (str (:body response))]
      (if-not (discovery/feed-document? body)
        {:error (diagnostics/describe {:id :fetch/malformed :url url :reason "The answer isn't a feed"})}
        (async/then (async/attempt #(parse body (select-keys opts [:quirks? :registry :except])))
                    (fn [{feed :value error :error}]
                      (if error
                        {:error (diagnostics/describe {:id :fetch/malformed :url url :reason (async/explain error)})}
                        {:candidate (discovery/candidate (or (:permanent-url response) url) where feed)})))))))

(defn- podcast-found?
  "Whether the state `acc` of find-feeds! has a podcast among its
  candidates, so that the places after are left alone."
  [acc]
  (some :podcast? (:candidates acc)))

(defn- with-candidates!
  "Fetch the candidates at the `urls` with `opts`, found where `where`
  says, and give the state `acc` of find-feeds! with them. Deferred.

  The `urls` can be a delay, which is forced only when `acc` has no
  podcast yet. At most :max-candidates are fetched, and none that was
  tried. The failure of a place that claims a feed goes in the report."
  [acc where urls {:keys [send max-candidates] :as opts}]
  (let [urls (when-not (podcast-found? acc)
               (into [] (comp (remove (:tried acc)) (distinct) (take max-candidates)) (force urls)))]
    (if (empty? urls)
      acc
      (async/then (async/all (fn [url]
                               (async/then (http/sent! send (discovery-request url (dissoc opts :credentials)) opts)
                                           #(read-feed url where % opts)))
                             urls)
                  (fn [results]
                    (cond-> (-> acc
                                (update :tried into urls)
                                (update :candidates into (keep :candidate) results))
                      (#{:app-link :link :link-header :rule} where)
                      (update :report into (keep :error) results)))))))

(defn- lookups
  "The lookups of the show that the `ids` of a link of an app name, or the
  podcast:guid `guid` of a fast-follow link, at the :lookup directories of
  `opts`. Each is a pair of the directory and its request.

  Apple takes its own id. The Podcast Index takes its own id or a guid,
  when `opts` give its :index credentials."
  [{:keys [apple-id podcastindex-id podcast-guid] :as ids} guid {:keys [lookup index now] :as opts}]
  (let [entries      (map directory/entry lookup)
        of-api       (fn [api] (first (filter #(= api (:api %)) entries)))
        apple        (of-api :apple)
        podcastindex (of-api :podcastindex)
        index-id     (or podcast-guid guid podcastindex-id)]
    (cond-> []
      (and apple apple-id)
      (conj [apple (directory/request apple :lookup {:id apple-id})])

      (and podcastindex index index-id)
      (conj [podcastindex (directory/request podcastindex :lookup {:id index-id}
                                             {:credentials index :auth-date (quot (inst-ms (now)) 1000)})]))))

(defn- feed-urls!
  "Send the requests of the `endpoint` of directories with `opts`, and give
  the feed URLs of the shows that they answer, as :urls, and the failures,
  as :report. Each of the `asked` is a pair of the directory and its
  request. Deferred."
  [asked endpoint {:keys [send] :as opts}]
  (async/then (async/all (fn [[source request]]
                           (async/then (http/sent! send request opts)
                                       (fn [{:keys [value] :as attempt}]
                                         (if-let [e (failure (:url request) attempt)]
                                           {:error e}
                                           {:urls (keep resolve/self-url
                                                        (try
                                                          (directory/response source endpoint (:body value))
                                                          (catch #?(:clj Exception :cljs :default) _
                                                            nil)))}))))
                         asked)
              (fn [answers]
                {:urls   (into [] (comp (mapcat :urls) (distinct)) answers)
                 :report (into [] (keep :error) answers)})))

(defn- looked-up!
  "The state `acc` of find-feeds! with the feeds of the shows that the
  `asked` lookups find, each a pair of a directory and its request, sent
  with `opts`. Deferred."
  [acc asked opts]
  (if (empty? asked)
    acc
    (async/then (feed-urls! asked :lookup opts)
                (fn [{:keys [urls report]}]
                  (with-candidates! (update acc :report into report) :app-link urls opts)))))

(defn- searched!
  "The state `acc` of find-feeds! with the feeds that the directories of
  the :search of `opts` find by the name of the web page `body` at `page`.
  Deferred.

  A feed counts only when its website is on the domain of the page. When
  `acc` has a podcast already, no directory is asked."
  [acc body page {:keys [search index now max-candidates] :as opts}]
  (let [q (when-not (or (empty? search) (podcast-found? acc))
            (discovery/page-name body page))]
    (if (nil? q)
      acc
      (let [signed {:credentials index :auth-date (quot (inst-ms (now)) 1000)}
            asked  (for [source (map directory/entry search)]
                     [source (directory/request source :search {:q q :limit max-candidates} signed)])
            domain (http/absolute-url page "/")]
        (async/then (feed-urls! asked :search opts)
                    (fn [{:keys [urls report]}]
                      (async/then (with-candidates! {:candidates [] :report [] :tried (:tried acc)}
                                                    :directory
                                                    urls
                                                    (assoc opts :max-candidates (* max-candidates (count search))))
                                  (fn [found]
                                    (-> acc
                                        (update :report into report)
                                        (update :tried into (:tried found))
                                        (update :candidates into
                                                (filter #(some->> (resolve/link (:feed %)) (discovery/same-website? domain)))
                                                (:candidates found)))))))))))

(defn- addresses
  "The URLs to try in turn for the page at `url`, while none answers:
  `url`, and when it was `typed?` without a scheme, the same with http
  and with www."
  [url typed?]
  (distinct (cond-> [url]
              typed? (conj (str/replace url #"^https:" "http:")
                           (str/replace url #"^https://(?!www\.)" "https://www.")))))

(defn- answered!
  "Send the requests for the `urls` in turn with `opts` until one answers,
  and give its attempt with the :url that it asked. Deferred.

  Only a connection that fails is followed by the others. A URL that
  isn't allowed isn't, and neither is a server that doesn't answer in
  time, since the others would wait for it as long."
  [[url & more :as urls] {:keys [send] :as opts}]
  (async/then (http/sent! send (discovery-request url opts) opts)
              (fn [{:keys [error] :as attempt}]
                (if (and error more (not= ::http/refused (:type (ex-data error))) (not (http/timeout? error)))
                  (answered! more opts)
                  (assoc attempt :url url)))))

(defn- page-feeds!
  "The state `acc` of find-feeds! with the feeds that the page at `url` is
  or names, fetched with `opts`, where `typed?` tells that the scheme of
  the URL was added. Deferred.

  When `acc` has a podcast already, the page isn't fetched. A place in
  the table can have limits of its own, which replace those of `opts`."
  [acc url typed? {:keys [rules guessed-paths timeout-seconds guess-timeout-seconds] :as opts}]
  (if (or (nil? url) (podcast-found? acc))
    acc
    (async/then
     (answered! (addresses url typed?) opts)
     (fn [{asked :url response :value :as attempt}]
       (let [page (or (:url response) asked)
             body (str (:body response))]
         (if-let [e (failure asked attempt)]
           (assoc acc :error e)
           (if (discovery/feed-document? body)
             (async/then (read-feed asked :input attempt opts)
                         (fn [{:keys [candidate error]}]
                           (cond-> acc
                             candidate (update :candidates conj candidate)
                             error     (assoc :error error))))
             (async/then
              (reduce (fn [acc [where urls limits]] (async/then acc #(with-candidates! % where urls (merge opts limits))))
                      (update acc :tried conj asked page)
                      [[:link        (delay (map :href (discovery/feed-links body page)))]
                       [:link-header (delay (map :href (discovery/header-links (:headers response) page)))]
                       [:anchor      (delay (discovery/feed-anchors body page))]
                       [:rule        (delay (mapcat #(discovery/rule-urls % rules) (distinct [asked page])))]
                       [:guess       (delay (discovery/guesses page guessed-paths))
                        {:timeout-seconds (min timeout-seconds guess-timeout-seconds)}]])
              #(searched! % body page opts)))))))))

(defn- result
  "What find-feeds! gives for its state `acc` at the end, for the page at
  `url` and the podcast:guid `guid` of a fast-follow link.

  With a guid, only the feeds with that guid stay, if any have it. The
  :error is there when no feed is, and otherwise it goes in the report."
  [{:keys [known candidates report error] :as acc} url guid]
  (let [feeds  (discovery/ranked (concat known candidates))
        same   (filterv #(= guid (feed/feed-guid (:feed %) (:url %))) feeds)
        feeds  (if (and guid (seq same)) same feeds)
        report (cond-> (vec report) (and error (seq feeds)) (conj error))]
    (cond-> {:feeds feeds}
      (seq report)   (assoc :report report)
      (empty? feeds) (assoc :error (or error (diagnostics/describe (cond-> {:id :discovery/not-found} url (assoc :url url))))))))

(defn find-feeds!
  "Find the feeds of a show from the text `s` that a listener types or
  pastes, with the `opts` below. It gives a map of the :feeds, best first,
  as a promise in ClojureScript.

  The text can be a feed URL, a domain, a web page or a link that an app
  shares. Each feed has its :url, :title, :found-by, :podcast? and the
  parsed :feed. Failures go in the :report, and the result has an :error
  when no feed is found.

  The `opts` are:

  - :known, the feeds you already have, as library/urls gives them
  - :search, the directories to search by a page's name, none by default
  - :lookup, the directories that look up the id in an app's link
  - :index, the Podcast Index credentials, and :now, the clock to sign with
  - :rules, your rules for a page's address, as feed.discovery/rule-urls
    takes them
  - :guessed-paths and :apps, in place of feed.discovery/guessed-paths and
    directory.links/apps
  - :send, the function that sends a request, http/call! by default
  - :allow?, a predicate of the URLs a request may go to, http/public? by
    default
  - :timeout-seconds, :guess-timeout-seconds, :max-bytes and
    :max-candidates, over feed.discovery/default-limits
  - :user-agent, and the options of parse"
  ([s]
   (find-feeds! s {}))
  ([s opts]
   {:pre [(map? opts)]}
   (let [opts                                (merge discovery/default-limits
                                                    {:send          http/call!
                                                     :allow?        http/public?
                                                     :lookup        [:apple :podcastindex]
                                                     :guessed-paths discovery/guessed-paths
                                                     :now           time/now}
                                                    opts)
         {:keys [url typed? ids guid error]} (discovery/read-input s opts)
         [url credentials]                   (if url (http/split-credentials url) [nil nil])
         acc                                 {:known      (when url (vec (discovery/known-matches url (:known opts))))
                                              :candidates []
                                              :report     []
                                              :tried      #{}}]
     (-> (if error
           (assoc acc :error error)
           (looked-up! acc (lookups ids guid opts) opts))
         (async/then #(page-feeds! % url typed? (cond-> opts credentials (assoc :credentials credentials))))
         (async/then #(result % url guid))))))

#?(:clj
   (comment
     (def f (parse (slurp "test/resources/feeds/namespace-example.xml")))
     (report f)
     (println (emit f))
     #_.))
