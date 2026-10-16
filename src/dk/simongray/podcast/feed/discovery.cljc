(ns dk.simongray.podcast.feed.discovery
  "Finding the feeds of a show from what a listener types or pastes: the
  URL of a feed, a domain, the address of a web page or a link of an app.

  The candidates are found as the feed finders of RSS readers find them,
  and ranked with the podcasts first, and then by where they were found.
  Each feed comes once, however many places name it."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.feed.discovery.impl :as impl]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.bytes.ranges :as ranges]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.url :as url]))

(def found-by
  "Where a candidate feed can be found, best first:

  - :input, the address that was given is the feed
  - :known, a feed that the caller knows, such as a subscription
  - :app-link, the feed that a link of an app holds or names by an id
  - :link, a link element of the page
  - :link-header, the Link header of the page
  - :anchor, a link in the page that looks like a feed
  - :rule, a rule of the caller for the address
  - :guess, a common path on the domain of the page
  - :directory, a directory search, whose feed names the domain as its
    website"
  [:input :known :app-link :link :link-header :anchor :rule :guess :directory])

(def guessed-paths
  "The paths on the domain of a page that are tried when the page names no
  podcast feed. They're those of WordPress, of Libsyn and Ghost, of Jekyll
  and of Hugo."
  ["/feed/" "/rss" "/feed.xml" "/index.xml"])

(def default-limits
  "The limits of a search for feeds when its options don't set them:

  - :timeout-seconds, how long a request can take
  - :guess-timeout-seconds, how long a request for a guessed path can
    take, since a slow server is slow to say 404 too
  - :max-bytes, the most bytes of a page or a feed
  - :max-candidates, the most candidates of one kind to fetch, such as
    the links of a page"
  {:timeout-seconds       15
   :guess-timeout-seconds 8
   :max-bytes             (* 32 1024 1024)
   :max-candidates        8})

(defn read-input
  "Read the text `s` that a listener gives for a show, with the :apps of
  `opts`, into a map of what to fetch, or of an :error.

  The text can be a URL, a domain with or without a path, or a link of an
  app, whose table of apps the :apps replace. The
  schemes feed, itpc and pcast only mark a feed, so they're removed. The
  map has:

  - :url, the URL to fetch, without its fragment
  - :typed?, when the text had no scheme, so https was added and http can
    follow
  - :ids, when the text is a link of an app that names the show by an id
  - :guid, the podcast:guid of a fast-follow link"
  ([s]
   (read-input s {}))
  ([s opts]
   {:pre [(map? opts)]}
   ;; feed is a provisional scheme of IANA, and itpc and pcast are Apple's
   (let [feed-scheme  #"(?i)^(?:feed|itpc|pcast):(?://)?"
         ;; the fast-follow links of docs/tags/guid.md of the podcast
         ;; namespace
         fast-follow  #"#fastfollow-[a-z]+:([0-9a-fA-F-]{36})"
         text         (str/replace (str/trim (str s)) feed-scheme "")
         [url typed?] (impl/web-address text)
         ids          (links/link-ids (or url text) opts)
         guid         (some->> url (re-find fast-follow) second)]
     (cond
       (:feed-url ids)
       (read-input (:feed-url ids) opts)

       (or url ids)
       (into {} (filter val) {:url    (some-> url (str/split #"#" 2) first)
                              :typed? typed?
                              :ids    (not-empty ids)
                              :guid   guid})

       :else
       (diagnostics/failed {:id    :discovery/unreadable
                            :input (str s)})))))

;; RSS Autodiscovery, section 3, and the HTML standard, section 4.6.8.1
(defn feed-links
  "The feeds that the web page `s` names, in the order of the page. When
  the URL of the page is given as `base`, each href is made absolute.

  A feed is a map of :href, :type and :title. It comes from a link element
  with the rel alternate or feed and the type of an RSS or Atom feed. The
  type is without its parameters.

  The base element of the page replaces `base`, as in a browser. With a
  base, only an http or https URL is a feed. Without one, a relative href
  stays as it is."
  ([s]
   (feed-links s nil))
  ([s base]
   (let [nodes (impl/nodes-of s)
         base  (impl/page-base nodes base)]
     (into []
           (comp (filter (impl/tagged :link))
                 (keep #(impl/feed-link (second %) base)))
           nodes))))

;; RFC 8288, section 3
(defn header-links
  "The feeds that the Link header of the response `headers` names, made
  absolute against `base`, the URL of the response. They're read as the
  link elements of a page are."
  [headers base]
  (keep #(impl/feed-link % base) (headers/links headers)))

(defn feed-anchors
  "The addresses that the a elements of the web page `s` link to and that
  look like feeds, made absolute against `base`, the URL of the page.

  An address looks like a feed when it has the word rss, feed, feeds,
  atom or xml in it. Those on the domain of the page come first, in the
  order of the page, as in the feed finders of RSS readers."
  [s base]
  (let [nodes     (impl/nodes-of s)
        base      (impl/page-base nodes base)
        feed-word #"(?i)\b(?:rss|feeds?|atom|xml)\b"
        urls      (into []
                        (comp (filter (impl/tagged :a))
                              (keep (comp impl/text-of :href second))
                              (keep #(some-> (url/absolute-url base %)
                                             (str/split #"#" 2)
                                             first))
                              (filter (comp #{"http" "https"} url/scheme))
                              (remove #{base})
                              (filter #(re-find feed-word %))
                              (distinct))
                        nodes)
        here?     #(= (url/host %) (url/host base))]
    (concat (filter here? urls) (remove here? urls))))

(defn guesses
  "The URLs of the `paths` on the domain of the page at `url`, or of the
  common paths when none are given."
  ([url]
   (guesses url guessed-paths))
  ([url paths]
   (keep #(url/absolute-url url %) paths)))

(defn rule-urls
  "The URLs that the `rules` of the caller give for `url`.

  A rule is a pair of a regex and its replacement, as str/replace takes
  them. It gives a URL when the regex matches `url`, e.g. this rule gives
  the feed of a show from the address of its page on a host:

      [#\"^https://([a-z0-9-]+)\\.host\\.example/?$\"
       \"https://feeds.host.example/$1.xml\"]"
  [url rules]
  (for [[re replacement] rules
        :when (re-find re url)]
    (str/replace url re replacement)))

(defn candidate
  "The candidate of the parsed `feed` at `url`, found at the place `where`:
  a map of the :url, the :title, :found-by, :podcast? and the :feed."
  [url where feed]
  (let [title (resolve/title feed)]
    (cond-> {:url      url
             :found-by where
             :podcast? (impl/podcast? feed)
             :feed     feed}
      title (assoc :title title))))

(defn known-matches
  "The candidates among the `known` feeds that the web page `url` names.
  The `known` feeds are a map of each parsed feed to its URL.

  A feed is named by its URL, without the scheme, trailing slashes and
  case, or by its website."
  [url known]
  (let [k     #(str/lower-case (guid/guid-name %))
        named (k url)
        place (impl/site url)]
    (for [[feed feed-url] known
          :when (or (= named (k feed-url))
                    (some->> (resolve/link feed) (impl/on-website? place)))]
      (candidate feed-url :known feed))))

(defn- identities
  "What tells the feed of the candidate `c` from others: its URL and its
  self link, both without scheme and trailing slashes."
  [{:keys [url feed] :as c}]
  (let [self (some-> (resolve/self-url feed) guid/guid-name)]
    (disj (hash-set (guid/guid-name url) self) nil)))

(defn ranked
  "The `candidates` best first, with each feed once. The candidate
  function makes a candidate from a parsed feed.

  Podcasts come before other feeds, such as the feed of a blog or its
  comments. Then they're ordered by where they were found, the best place
  first, and otherwise they keep their order, which is that
  of the page. Two candidates are one feed when they share a URL or a
  self link, and the better one stays. The feeds of one show in two audio
  formats can share a podcast:guid, so they stay apart."
  [candidates]
  (let [rank  (zipmap found-by (range))
        order (juxt (complement :podcast?)
                    #(rank (:found-by %) (count found-by)))
        add   (fn [{:keys [seen] :as acc} c]
                (let [ids (identities c)]
                  (cond-> (update acc :seen into ids)
                    (not-any? seen ids) (update :feeds conj c))))]
    (->> candidates
         (sort-by order)
         (reduce add {:feeds [] :seen #{}})
         :feeds)))

(defn head?
  "Whether `feed` is the start of a feed that fetch-head! gave, rather than
  the whole feed."
  [feed]
  (boolean (::head? (meta feed))))

(defn fetch-head!
  "Fetch the start of the feed at `url` with the `opts` of find-feeds!, as
  far as the end of its first item, or of as many items as the :items of
  `opts` says, and give a map of the :url that it was found at and the
  :feed, or of an :error. Deferred.

  The start is enough to preview a show, or to check its guid before
  subscribing, and most often it's the first 64 KB of a feed of megabytes.
  With :items, it can also show the first episodes, e.g. a page of them
  before the whole feed arrives. RSS lets channel elements come after the
  items, so a start can lack some, and head? tells it from a whole feed.
  When the start doesn't read as a feed with a title, the whole feed is
  fetched, and a short feed comes whole too. The :credentials of `opts` go
  with the requests."
  ([url]
   (fetch-head! url {}))
  ([url opts]
   {:pre [(map? opts)]}
   (let [opts    (merge default-limits
                        {:send client/send! :allow-pred url/public?}
                        opts)
         moved   (volatile! nil)
         send    (fn [request]
                   (async/then ((:send opts) request)
                               (fn [response]
                                 (some->> (:permanent-url response)
                                          (vreset! moved))
                                 response)))
         headers (:headers (impl/document-request url opts))
         reading (assoc opts :send send :headers headers)]
     (async/then
      (ranges/read-url! url (partial impl/head-reading (:items opts 1)) reading)
      (fn [{:keys [text cut? error] :as result}]
        (let [read  (when text (impl/parsed text url opts))
              found (or @moved url)]
          (cond
            error
            {:url   url
             :error (diagnostics/fetch-failure
                     (assoc (select-keys error [:status :reason :type])
                            :url url))}

            (and cut? (resolve/title (:feed read)))
            {:url found :feed (vary-meta (:feed read) assoc ::head? true)}

            cut?
            (impl/fetch-feed! url opts)

            read
            (assoc read :url found)

            :else
            (diagnostics/failed
             (assoc (first (view/report result)) :url url)))))))))

(defn- fetch-candidates!
  "Fetch the candidates at the `urls` with `opts`, found where `where`
  says, and give the state `acc` of the search with them. Deferred.

  The `urls` can be a delay, which is forced only when `acc` has no
  podcast yet. At most :max-candidates are fetched, and none that was
  tried, each whole or, when `opts` are :heads?, its start alone. The
  failure of a place that claims a feed goes in the report."
  [acc where urls {:keys [max-candidates heads?] :as opts}]
  (let [urls    (when-not (impl/podcast-found? acc)
                  (into []
                        (comp (remove (:tried acc))
                              (distinct)
                              (take max-candidates))
                        (force urls)))
        fetch!  (if heads? fetch-head! impl/fetch-feed!)
        fetch   (fn [url]
                  (async/then (fetch! url (dissoc opts :credentials))
                              (fn [{:keys [feed error] :as read}]
                                (if feed
                                  {:candidate (candidate (:url read) where feed)}
                                  {:error error}))))]
    (if (empty? urls)
      acc
      (async/then (async/all fetch urls)
                  (fn [results]
                    (let [found  (keep :candidate results)
                          errors (keep :error results)]
                      (cond-> (-> acc
                                  (update :tried into urls)
                                  (update :candidates into found))
                        (#{:app-link :link :link-header :rule} where)
                        (update :report into errors))))))))

(defn- look-up!
  "The state `acc` of a search with the feeds of the shows that the
  `asked` lookups find, each a pair of a directory and its request, sent
  with `opts`. Deferred."
  [acc asked opts]
  (if (empty? asked)
    acc
    (async/then (impl/fetch-feed-urls! asked :lookup opts)
                (fn [{:keys [urls report]}]
                  (fetch-candidates! (update acc :report into report)
                                     :app-link
                                     urls
                                     opts)))))

(defn- search-directories!
  "The state `acc` of a search with the feeds that the directories of the
  :search of `opts` find by the name of the web page `body` at `page`.
  Deferred.

  A feed counts only when its website is on the domain of the page. When
  `acc` has a podcast already, no directory is asked."
  [acc body page {:keys [search max-candidates] :as opts}]
  (let [q (when-not (or (empty? search) (impl/podcast-found? acc))
            (impl/page-name body page))]
    (if (nil? q)
      acc
      (let [signed   (impl/signing opts)
            asked    (for [source (map directory/entry search)]
                       [source (directory/request source :search
                                                  {:q q :limit max-candidates}
                                                  signed)])
            domain   (url/absolute-url page "/")
            on-site? #(some->> (resolve/link (:feed %))
                               (impl/same-website? domain))
            most     (* max-candidates (count search))
            fresh    {:candidates [] :report [] :tried (:tried acc)}]
        (async/then
         (impl/fetch-feed-urls! asked :search opts)
         (fn [{:keys [urls report]}]
           (async/then
            (fetch-candidates! fresh :directory urls
                               (assoc opts :max-candidates most))
            (fn [found]
              (-> acc
                  (update :report into report)
                  (update :tried into (:tried found))
                  (update :candidates into
                          (filter on-site?)
                          (:candidates found)))))))))))

(defn- fetch-named-feeds!
  "The state `acc` of a search with the feeds that the web page `body` at
  `page` names, and those that it suggests, place by place, the best
  first. The page is the `response` to the request for `asked`, and the
  feeds are fetched with `opts`. Deferred.

  A place in the table can have limits of its own, which replace those of
  `opts`."
  [acc asked page body response {:keys [rules guessed-paths timeout-seconds
                                        guess-timeout-seconds]
                                 :as   opts}]
  (let [headers (:headers response)
        pages   (distinct [asked page])
        guess   {:timeout-seconds (min timeout-seconds guess-timeout-seconds)}
        places  [[:link        (delay (map :href (feed-links body page)))]
                 [:link-header (delay (map :href (header-links headers page)))]
                 [:anchor      (delay (feed-anchors body page))]
                 [:rule        (delay (mapcat #(rule-urls % rules) pages))]
                 [:guess       (delay (guesses page guessed-paths)) guess]]
        step    (fn [acc [where urls limits]]
                  (async/then acc #(fetch-candidates! % where urls
                                                      (merge opts limits))))]
    (async/then (reduce step (update acc :tried conj asked page) places)
                #(search-directories! % body page opts))))

(defn- fetch-page-feeds!
  "The state `acc` of a search with the feeds that the page at `url` is or
  names, fetched with `opts`, where `typed?` tells that the scheme of
  the URL was added. Deferred.

  When `acc` has a podcast already, the page isn't fetched."
  [acc url typed? opts]
  (if (or (nil? url) (impl/podcast-found? acc))
    acc
    (async/then
     (impl/ask-in-turn! (impl/addresses url typed?) opts)
     (fn [{asked :url response :value :as attempt}]
       (let [page (or (:url response) asked)
             body (str (:body response))]
         (if-let [e (impl/failure asked attempt)]
           (assoc acc :error e)
           (if (impl/feed-document? body)
             (let [{:keys [feed error] :as read}
                   (impl/read-feed asked attempt opts)]
               (cond-> acc
                 feed  (update :candidates conj
                               (candidate (:url read) :input feed))
                 error (assoc :error error)))
             (fetch-named-feeds! acc asked page body response opts))))))))

(defn- result
  "The result of a search with the state `acc` at the end, for the page at
  `url` and the podcast:guid `guid` of a fast-follow link.

  With a guid, only the feeds with that guid stay, if any have it. The
  :error is there when no feed is, and otherwise it goes in the report.
  When a feed in a format that can't be read was found, that is the error."
  [{:keys [known candidates report error] :as acc} url guid]
  (let [feeds     (ranked (concat known candidates))
        same      (filterv #(= guid (guid/feed-guid (:feed %) (:url %))) feeds)
        feeds     (if (and guid (seq same)) same feeds)
        other     (some #(when (= :fetch/unsupported-format (:id %)) %) report)
        report    (cond-> (vec report)
                    (and error (seq feeds)) (conj error))
        not-found (or error
                      other
                      (diagnostics/describe (cond-> {:id :discovery/not-found}
                                              url (assoc :url url))))]
    (cond-> {:feeds feeds}
      (seq report)   (assoc :report report)
      (empty? feeds) (assoc :error not-found))))

(defn find-feeds!
  "Find the feeds of a show from the text `s` that a listener types or
  pastes, with the `opts` below. It gives a map of the :feeds, best first,
  as a promise in ClojureScript.

  The text can be a feed URL, a domain, a web page or a link that an app
  shares. Each feed has its :url, :title, :found-by, :podcast? and the
  parsed :feed. Failures go in the :report, and the result has an :error
  when no feed is found.

  The `opts` are:

  - :known, the feeds you already have, as a map of each to its URL
  - :search, the directories to search by a page's name, none by default
  - :lookup, the directories that look up the id in an app's link
  - :index, the Podcast Index credentials, and :now-fn, the clock to sign with
  - :rules, your rules for a page's address, as rule-urls takes them
  - :guessed-paths and :apps, in place of guessed-paths and
    directory.links/apps
  - :send, the function that sends a request, client/send! by default
  - :allow-pred, a predicate of the URLs a request may go to, url/public? by
    default
  - :timeout-seconds, :guess-timeout-seconds, :max-bytes and
    :max-candidates, over default-limits
  - :heads?, to fetch only the start of each feed that a page names, as
    fetch-head! does, e.g. to show the candidates before the listener
    chooses one
  - :user-agent, and the options of rss/parse"
  ([s]
   (find-feeds! s {}))
  ([s opts]
   {:pre [(map? opts)]}
   (let [opts              (merge default-limits
                                  {:send          client/send!
                                   :allow-pred    url/public?
                                   :lookup        [:apple :podcastindex]
                                   :guessed-paths guessed-paths
                                   :now-fn        time/now}
                                  opts)
         input             (read-input s opts)
         [url credentials] (some-> (:url input) url/split-credentials)
         page-opts         (cond-> opts
                             credentials (assoc :credentials credentials))
         known             (when url
                             (vec (known-matches url (:known opts))))
         acc               {:known      known
                            :candidates []
                            :report     []
                            :tried      #{}}]
     (-> (if (:error input)
           (assoc acc :error (:error input))
           (look-up! acc (impl/lookups (:ids input) (:guid input) opts) opts))
         (async/then #(fetch-page-feeds! % url (:typed? input) page-opts))
         (async/then #(result % url (:guid input)))))))

(comment
  (read-input "podnews.net/rss")
  (read-input "feed:https://feeds.podcastindex.org/pc20.xml")
  (read-input "https://podcasts.apple.com/us/podcast/podcasting-2-0/id1584274529")
  (read-input "pktc://subscribe/feeds.podcastindex.org/pc20.xml")
  (read-input "the daily")

  (feed-links "<link rel=alternate type=application/rss+xml href=/feed.xml>"
              "https://a.example/")
  (feed-anchors "<a href=/rss>RSS</a><a href=/about>About</a>"
                "https://a.example/")
  (guesses "https://a.example/shows/x")
  (impl/page-name "<title>Home | The Show</title>" "https://show.example/")
  (impl/feed-document? "<?xml version=\"1.0\"?>\n<rss version=\"2.0\">")
  (impl/same-website? "https://Example.com" "http://www.example.com/podcast/")

  ;; the feeds of a show from its domain, best first
  (map (juxt :title :url :found-by) (:feeds (find-feeds! "freakshow.fm")))
  #_.)
