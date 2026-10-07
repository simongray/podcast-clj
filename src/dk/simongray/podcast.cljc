(ns dk.simongray.podcast
  "Fetching a podcast feed as data, in one call.

  The fetch! function sends a conditional request for the feed at a URL,
  parses the answer with feed.rss, and joins the later pages of a paged
  feed. It sends its requests with the :send of its options, and in
  ClojureScript it gives a promise.

  Parsing and writing a feed without fetching it is in feed.rss, and
  feed.discovery finds the feeds of a show from what a listener types."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.updates.websub :as websub]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.url :as url]))

(def ^:no-doc feed-types
  "The Accept header of a request for a feed."
  "application/rss+xml, application/xml, text/xml, */*")

(defn- parsed-page
  "The `answer` of fetch-page! to the 200 `response`, with the feed of its
  body parsed with `opts`: the :body, the :feed and its :websub hubs, or
  the :error."
  [answer response opts]
  (let [body                 (:body response)
        parse-opts           (assoc opts :url (:url response))
        {:keys [feed error]} (rss/try-parse (str body) parse-opts)
        hubs                 (when feed
                               (websub/discover response feed))]
    (cond-> answer
      body  (assoc :body body)
      feed  (assoc :feed feed)
      hubs  (assoc :websub hubs)
      error (assoc :error error))))

(defn- fetch-page!
  "Fetch the document at `url` and parse it with `opts`, as fetch! does
  for a feed of one page: the answer of the server, with the :feed or the
  :error. Deferred."
  [url opts]
  (let [http-opts (-> opts
                      (select-keys [:etag :last-modified :credentials
                                    :timeout-seconds :max-bytes :user-agent
                                    :allow? :send])
                      (assoc :accept feed-types))]
    (async/then (async/attempt #(http/fetch! url http-opts))
                (fn [{response :value error :error}]
                  (let [answer (select-keys response [:status :url
                                                      :permanent-url
                                                      :etag :last-modified
                                                      :not-before])]
                    (cond
                      error
                      {:url   url
                       :error (diagnostics/describe
                               {:id     :fetch/unreachable
                                :url    url
                                :reason (async/explain error)})}

                      (not= 200 (:status response))
                      answer

                      :else
                      (parsed-page answer response opts)))))))

(defn- fetch-later-pages!
  "The pages after `page` of a paged feed, the answer of fetch-page! to
  the URL `asked`, fetched with `opts`, whose :max-pages counts `page`
  too. Each page is the next of the one before, until a page repeats.
  Deferred.

  The feed names its pages, so the :credentials in `opts` only go to
  pages at the origin of `asked`, since a redirect can move the feed to
  another origin."
  [asked page {:keys [max-pages] :or {max-pages 1} :as opts}]
  (let [next-of  (fn [{:keys [url feed seen]}]
                   (when-let [next (some->> feed
                                            resolve/next-page
                                            (url/absolute-url url))]
                     (when-not (seen next)
                       {:url next :seen (conj seen next)})))
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
  "The answer `page` of fetch-page! with the `pages` after it joined into
  its feed, as fetch! gives it: the :feed, its :report, and the
  :new-feed-url when the feed declares a move. A page that can't be read
  gets a diagnostic in the report."
  [{first-page :feed :as page} pages]
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
        moved   (resolve/new-feed-url feed)]
    (cond-> (assoc page :feed feed :report (rss/report feed))
      moved (assoc :new-feed-url moved))))

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
  - :send, the function that sends each request, http/send! by default
  - the options of http/fetch! and rss/parse

  Nothing is thrown for what the network, the server or the body does.
  The result has an :error diagnostic when there's no answer or no
  feed, and any other status comes back as :status."
  ([url]
   (fetch! url {}))
  ([url opts]
   {:pre [(map? opts)]}
   (let [[url in-url] (url/split-credentials url)
         opts         (cond-> opts
                        (and in-url
                             (not (:credentials opts)))
                        (assoc :credentials in-url))]
     (async/then (fetch-page! url opts)
                 (fn [{:keys [feed] :as page}]
                   (if feed
                     (async/then (fetch-later-pages! url page opts)
                                 #(joined page %))
                     page))))))

#?(:clj
   (comment
     (def fetched (fetch! "https://feeds.podcastindex.org/pc20.xml"))
     (:status fetched)
     (:rss/title (:feed fetched))

     ;; a poll with the validators of the last answer, which gives a 304
     (:status (fetch! "https://feeds.podcastindex.org/pc20.xml"
                      (select-keys fetched [:etag :last-modified])))
     #_.))
