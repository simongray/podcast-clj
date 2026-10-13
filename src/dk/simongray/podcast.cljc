(ns dk.simongray.podcast
  "Fetching a podcast feed or an OPML list as data, in one call."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.websub :as websub]))

(def ^:no-doc feed-types
  "The Accept header of a request for a feed."
  "application/rss+xml, application/xml, text/xml, */*")

(def ^:no-doc list-types
  "The Accept header of a request for an OPML list."
  "text/x-opml, application/xml;q=0.9, */*;q=0.1")

(defn- with-credentials
  "The `url` without the credentials in it, and the `opts` with those as
  their :credentials unless they have their own, as a pair."
  [url opts]
  (let [[url in-url] (url/split-credentials url)]
    [url (cond-> opts
           (and in-url (not (:credentials opts)))
           (assoc :credentials in-url))]))

(defn- fetch-document!
  "Fetch the document at `url` with `opts`, asking for the `accept` types,
  and give the answer of the server. A 200 answer has what `parse-fn` gives
  of the response merged in, and a failed request or an answer that failed
  has the :error. Deferred.

  An answer that failed keeps no ETag or Last-Modified, which are those of
  the error page."
  [url accept parse-fn opts]
  (let [http-opts (->> (select-keys opts [:etag :last-modified :credentials
                                          :timeout-seconds :max-bytes
                                          :user-agent :allow-pred :send
                                          :max-wait-seconds :signal])
                       (merge {:send             client/send!
                               :accept           accept
                               :max-wait-seconds 3600}))]
    (async/then (async/attempt #(http/fetch! url http-opts))
                (fn [{response :value error :error}]
                  (let [answer (select-keys response [:status :url
                                                      :permanent-url
                                                      :etag :last-modified
                                                      :not-before])]
                    (cond
                      error
                      {:url   url
                       :error (diagnostics/fetch-failure
                               (assoc (http/failure error) :url url))}

                      (= 304 (:status response))
                      answer

                      (not= 200 (:status response))
                      (-> (dissoc answer :etag :last-modified)
                          (assoc :error (diagnostics/fetch-failure
                                         {:url    url
                                          :status (:status response)})))

                      :else
                      (merge answer (parse-fn response))))))))

(defn- parsed-page
  "The feed of the 200 `response` to a request for a page, parsed with
  `opts`: the :body, the :feed and its :websub hubs, or the :error."
  [response opts]
  (let [body                 (:body response)
        parse-opts           (assoc opts :url (:url response))
        {:keys [feed error]} (rss/try-parse (str body) parse-opts)
        ;; many podcast feeds name no rel=self, so their URL is the topic
        discovery-opts       {:links         (:atom/links feed)
                              :url-as-topic? true}
        hubs                 (when feed
                               (websub/discover response discovery-opts))]
    (cond-> {}
      body  (assoc :body body)
      feed  (assoc :feed feed)
      hubs  (assoc :websub hubs)
      error (assoc :error error))))

(defn- fetch-page!
  "Fetch the feed of one page at `url` and parse it with `opts`: the
  answer of the server, with the :feed or the :error. Deferred."
  [url opts]
  (fetch-document! url feed-types #(parsed-page % opts) opts))

(defn- fetch-later-pages!
  "Fetch the pages of a paged feed after `page`, the answer to a request
  for `asked`, with `opts`, whose :max-pages counts `page` too. Each page
  is the next of the one before, until a page repeats. Deferred."
  [asked page {:keys [max-pages] :or {max-pages 1} :as opts}]
  (let [next-of  (fn [{:keys [url feed seen]}]
                   (when-let [next (some->> feed
                                            resolve/next-page
                                            (url/absolute-url url))]
                     (when-not (seen next)
                       {:url next :seen (conj seen next)})))
        ;; the feed names its pages, and a redirect can move it to another
        ;; origin, so the credentials only go to the origin of asked
        opts-for (fn [url]
                   (cond-> (dissoc opts :etag :last-modified)
                     (not (url/same-origin? asked url))
                     (dissoc :credentials)))
        fetch    (fn [{:keys [url seen] :as k}]
                   (when k
                     (async/then (fetch-page! url (opts-for url))
                                 #(assoc % :url url :seen seen))))]
    (when (> max-pages 1)
      (async/iteration fetch
                       :initk (next-of (assoc page :seen #{(:url page)}))
                       :kf    next-of
                       :limit (dec max-pages)))))

(defn- joined
  "The answer `page` to a request for `asked` with the `pages` after it
  joined into its feed: the :feed, its :report, and the :new-feed-url when
  the feed declares a move. A page that can't be read gets a diagnostic in
  the report."
  [asked {first-page :feed :as page} pages]
  (let [failed  (first (remove :feed pages))
        failure (when failed
                  {:id     :fetch/page-failed
                   :level  :warning
                   :url    (:url failed)
                   :reason (or (:reason (:error failed))
                               (str "HTTP " (:status failed)))})
        feed    (cond-> (if (seq pages)
                          (feed/join-pages (cons first-page (keep :feed pages)))
                          first-page)
                  failure (vary-meta update :podcast/report conj failure))
        moved   (resolve/new-feed-url feed [asked (:url page)])]
    (cond-> (assoc page :feed feed :report (rss/report feed))
      moved (assoc :new-feed-url moved))))

(defn fetch!
  "Fetch the feed at `url` and parse it with the `opts` below. In
  ClojureScript, it gives a promise:

      {:status 200 :url \"…\"             ; the final URL, after redirects
       :permanent-url \"…\"               ; when redirects moved it for good
       :etag \"…\" :last-modified \"…\"    ; to send next time
       :not-before #inst \"…\"            ; when the server asks you to wait
       :body \"…\" :feed {…} :report [...] ; on a 200
       :new-feed-url \"…\"                ; when the feed declares a move
       :websub {:hubs [\"…\"] :topic \"…\"}  ; when the feed names a WebSub hub
       :error {…}}                      ; when the fetch failed

  The `opts` are those of http/fetch! and rss/parse, and:

  - :credentials, for a private feed, or else those in the `url`
  - :max-pages, the most pages of a paged feed to fetch and join
  - :max-wait-seconds, the latest :not-before, an hour by default, since a
    feed can change within the freshness that its server names

  Nothing is thrown for what the network, the server or the body does,
  and any status besides 200 comes back as :status. A status other than
  200 and 304 also has a :fetch/status :error, e.g. a 404."
  ([url]
   (fetch! url {}))
  ([url opts]
   {:pre [(map? opts)]}
   (let [[url opts] (with-credentials url opts)]
     (async/then (fetch-page! url opts)
                 (fn [{:keys [feed] :as page}]
                   (if feed
                     (async/then (fetch-later-pages! url page opts)
                                 #(joined url page %))
                     page))))))

(defn fetch-list!
  "Fetch the OPML list at `url` with the `opts` of http/fetch!, and parse
  it. The :credentials are those in the `url` by default. In
  ClojureScript, it gives a promise:

      {:status 200 :url \"…\"             ; the final URL, after redirects
       :permanent-url \"…\"               ; when redirects moved it for good
       :etag \"…\" :last-modified \"…\"    ; to send next time
       :not-before #inst \"…\"            ; when the server asks you to wait
       :list {…}                         ; on a 200
       :error {…}}                       ; when the fetch failed

  Nothing is thrown for what the network, the server or the body does,
  and any status besides 200 comes back as :status. A status other than
  200 and 304 also has a :fetch/status :error, e.g. a 404."
  ([url]
   (fetch-list! url {}))
  ([url opts]
   {:pre [(map? opts)]}
   (let [[url opts] (with-credentials url opts)
         parsed     #(opml/try-parse (str (:body %)) {:url (:url %)})]
     (fetch-document! url list-types parsed opts))))

#?(:clj
   (comment
     (def fetched (fetch! "https://feeds.podcastindex.org/pc20.xml"))
     (:status fetched)
     (:rss/title (:feed fetched))

     ;; a poll with the validators of the last answer, which gives a 304
     (:status (fetch! "https://feeds.podcastindex.org/pc20.xml"
                      (select-keys fetched [:etag :last-modified])))
     #_.))
