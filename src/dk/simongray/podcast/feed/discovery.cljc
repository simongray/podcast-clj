(ns dk.simongray.podcast.feed.discovery
  "Finding the feeds of a show from what a listener types or pastes: the
  URL of a feed, a domain, the address of a web page or a link of an app.

  The find-feeds! function does it in one call, and it's the only one
  that sends requests. The other functions read and rank for it, as the
  feed finders of RSS readers do:

  - read-input reads the text as a URL, with https when it has no scheme
  - a link of an app holds a feed URL, or an id to look up
  - a web page names its feeds in its link elements, which feed-links
    reads, and some pages in their Link header too
  - when a page names no podcast feed, its links that look like feeds and
    a few common paths are tried
  - a directory search by the name of the page is a last resort, which
    the caller has to ask for

  The candidates are ranked with the podcasts first, and then by where
  they were found, as found-by orders the places. Each feed comes once,
  however many places name it."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]))

(def found-by
  "Where a candidate feed can be found, best first, as ranked orders the
  places:

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
  and of Hugo, and the :guessed-paths of find-feeds! replace them."
  ["/feed/" "/rss" "/feed.xml" "/index.xml"])

(def default-limits
  "The limits of find-feeds! when its options don't set them:

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

(defn- scheme-of
  "The scheme of the text `s` in lower case, or nil when it has none.

  As in the address bar of Chromium, a name with a dot, or with a port
  after it, is a host, so example.com:8080 and localhost:8000 have no
  scheme."
  [s]
  (when-let [[_ scheme after] (re-find #"^([a-zA-Z][a-zA-Z0-9+.-]*):(.?)" s)]
    (when-not (or (str/includes? scheme ".") (re-matches #"[0-9]" after))
      (str/lower-case scheme))))

(defn- web-address
  "The text `s` as an http or https URL, and whether https was added
  because it had no scheme. Nil when it can't be one: text without a
  scheme must have a dot or a port in its domain, and no spaces."
  [s]
  (let [scheme (scheme-of s)
        url    (str "https://" (str/replace s #"^/+" ""))]
    (cond
      (#{"http" "https"} scheme)            (when (http/web-url? s) [s false])
      (or scheme (re-find #"\s" s))         nil
      (re-find #"^https://[^/?#]*[.:]" url) (when (http/web-url? url) [url true]))))

(defn read-input
  "Read the text `s` that a listener gives for a show, with the :apps of
  `opts`, into a map of what to fetch, or of an :error.

  The text can be a URL, a domain with or without a path, or a link of an
  app as links/link-ids reads it, whose table the :apps replace. The
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
   (let [text         (str/replace (str/trim (str s)) #"(?i)^(?:feed|itpc|pcast):(?://)?" "")
         [url typed?] (web-address text)
         ids          (links/link-ids (or url text) opts)
         ;; the fast-follow links of docs/tags/guid.md of the podcast namespace
         guid         (some->> url (re-find #"#fastfollow-[a-z]+:([0-9a-fA-F-]{36})") second)]
     (cond
       (:feed-url ids) (read-input (:feed-url ids) opts)
       (or url ids)    (into {} (filter val) {:url    (some-> url (str/split #"#" 2) first)
                                              :typed? typed?
                                              :ids    (not-empty ids)
                                              :guid   guid})
       :else           {:error (diagnostics/describe {:id :discovery/unreadable :input (str s)})}))))

(defn- nodes-of
  "The nodes of the web page `s`, its elements and texts at any depth."
  [s]
  (tree-seq vector? #(drop 2 %) (into [:root {}] (html/parse s))))

(defn- tagged
  "A predicate of the nodes of a page that are elements with the `tag`."
  [tag]
  #(and (vector? %) (= tag (first %))))

(defn- page-base
  "The URL that the links of a page with the `nodes` are relative to, for a
  page at `url`, which can be nil. It's the href of the first base element
  that has one, when that gives an http or https URL, or else `url`."
  [nodes url]
  (let [href (some (comp html/text-of :href second) (filter (tagged :base) nodes))
        base (when href (if url (http/absolute-url url href) href))]
    (if (#{"http" "https"} (html/scheme base)) base url)))

(defn- feed-link
  "The feed that a link with the attributes `attrs` names, made absolute
  against `base` when there is one, or nil when the link names no feed."
  [{:keys [rel type href title] :as attrs} base]
  (let [rels (set (str/split (str/lower-case (str rel)) #"\s+"))
        type (http/media-type (html/text-of type))
        href (when-let [h (html/text-of href)] (if base (http/absolute-url base h) h))]
    (when (and (or (rels "alternate") (rels "feed"))
               (#{"application/rss+xml" "application/atom+xml"} type)
               href
               (contains? (if base #{"http" "https"} #{nil "http" "https"}) (html/scheme href)))
      (cond-> {:href href :type type}
        (html/text-of title) (assoc :title title)))))

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
   (let [nodes (nodes-of s)
         base  (page-base nodes base)]
     (into [] (comp (filter (tagged :link)) (keep #(feed-link (second %) base))) nodes))))

;; RFC 8288, section 3
(defn header-links
  "The feeds that the Link header of the response `headers` names, made
  absolute against `base`, the URL of the response. They're read as
  feed-links reads the link elements of a page."
  [headers base]
  (keep #(feed-link % base) (http/links headers)))

(defn feed-anchors
  "The addresses that the a elements of the web page `s` link to and that
  look like feeds, made absolute against `base`, the URL of the page.

  An address looks like a feed when it has the word rss, feed, feeds,
  atom or xml in it. Those on the domain of the page come first, in the
  order of the page, as in the feed finders of RSS readers."
  [s base]
  (let [nodes (nodes-of s)
        base  (page-base nodes base)
        urls  (into [] (comp (filter (tagged :a))
                             (keep (comp html/text-of :href second))
                             (keep #(some-> (http/absolute-url base %) (str/split #"#" 2) first))
                             (filter (comp #{"http" "https"} html/scheme))
                             (remove #{base})
                             (filter #(re-find #"(?i)\b(?:rss|feeds?|atom|xml)\b" %))
                             (distinct))
                    nodes)
        here? #(= (http/host %) (http/host base))]
    (concat (filter here? urls) (remove here? urls))))

(defn guesses
  "The URLs of the `paths` on the domain of the page at `url`, which are
  guessed-paths unless given."
  ([url]
   (guesses url guessed-paths))
  ([url paths]
   (keep #(http/absolute-url url %) paths)))

(defn rule-urls
  "The URLs that the `rules` of the caller give for `url`.

  A rule is a pair of a regex and its replacement, as str/replace takes
  them. It gives a URL when the regex matches `url`, e.g. this rule gives
  the feed of a show from the address of its page on a host:

      [#\"^https://([a-z0-9-]+)\\.host\\.example/?$\" \"https://feeds.host.example/$1.xml\"]"
  [url rules]
  (for [[re replacement] rules
        :when (re-find re url)]
    (str/replace url re replacement)))

(defn- site
  "The domain of the web page `url` in lower case and without a leading
  www., and its path without trailing slashes. Nil for no web page."
  [url]
  (when-let [host (http/host url)]
    (let [path (or (second (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:/*[^/?#]*([^?#]*)" (str url))) "")]
      [(str/replace host #"^www\." "")
       ;; a match may start only where a run of slashes starts
       (str/replace path #"(^|[^/])/+$" "$1")])))

(defn page-name
  "The name that the web page `s` at `url` gives itself, to search a
  directory by.

  A home page is named by its og:site_name, and another page by its
  og:title or title without the name of the site, since a show can have
  a page on the site of a network. Of a title such as Home | The Show,
  it's the longest part."
  [s url]
  (let [nodes   (nodes-of s)
        meta-of (fn [property]
                  (some #(when (= property (str/lower-case (str (:property (second %)))))
                           (some-> (html/text-of (:content (second %))) str/trim))
                        (filter (tagged :meta) nodes)))
        title   (or (meta-of "og:title")
                    (some #(html/text-of (apply str (filter string? (drop 2 %)))) (filter (tagged :title) nodes)))
        named   (meta-of "og:site_name")
        parts   (remove #(= (str/lower-case %) (str/lower-case (str named)))
                        (some-> title str/trim (str/split #"\s+[|:–—-]\s+")))
        longest (some->> (seq parts) (apply max-key count) str/trim)]
    (not-empty (if (= "" (second (site url)))
                 (or named longest)
                 (or longest named)))))

(defn- first-element
  "Where the first element of the text `s` starts, after the white space,
  processing instructions, comments and declarations before it, if it
  starts before `n`."
  [^String s n]
  (loop [i 0]
    (cond
      (>= i n)                              nil
      (re-matches #"\s" (subs s i (inc i))) (recur (inc i))
      (.startsWith s "<!--" i)              (when-let [end (str/index-of s "-->" (+ i 4))] (recur (+ (long end) 3)))
      (.startsWith s "<?" i)                (when-let [end (str/index-of s "?>" (+ i 2))] (recur (+ (long end) 2)))
      (.startsWith s "<!" i)                (when-let [end (str/index-of s ">" (+ i 2))] (recur (inc (long end))))
      :else                                 i)))

(defn feed-document?
  "Whether the text `s` is a feed rather than a web page, by its first
  element, as the feed finders of RSS readers sniff it. The element must
  start in the first 4 KB."
  [s]
  (let [s (str s)
        i (first-element s (min 4096 (count s)))]
    (boolean (and i (re-find #"^<(?:rss|rdf:RDF|feed)[\s/>]" (subs s i (min (count s) (+ i 9))))))))

(defn- on-website?
  "Whether the page at `place`, its domain and path as site gives them, is
  on the website `link`."
  [[domain path :as place] link]
  (let [[link-domain link-path] (site link)]
    (boolean (and domain
                  (= domain link-domain)
                  (or (= "" path) (= path link-path))))))

(defn same-website?
  "Whether the web page `url` is on the website `link`: the same domain, in
  any case and with or without www. When `url` has a path, `link` must
  have the same path too, without trailing slashes."
  [url link]
  (on-website? (site url) link))

(defn podcast?
  "Whether `feed` is a podcast: an item has an enclosure, or the channel
  has an element of the iTunes or the podcast namespace."
  [feed]
  (boolean (or (some :rss/enclosure (:rss/items feed))
               (some #(#{"itunes" "podcast"} (namespace %)) (keys feed)))))

(defn candidate
  "The candidate of the parsed `feed` at `url`, found where `where` says,
  as found-by names the places: a map of the :url, the :title, :found-by,
  :podcast? and the :feed."
  [url where feed]
  (cond-> {:url url :found-by where :podcast? (podcast? feed) :feed feed}
    (resolve/title feed) (assoc :title (resolve/title feed))))

(defn known-matches
  "The candidates among the `known` feeds that the web page `url` names.
  The `known` feeds are a map of each parsed feed to its URL, as
  library/urls gives them.

  A feed is named by its URL, without the scheme, trailing slashes and
  case, or by its website, as same-website? tells."
  [url known]
  (let [k     #(str/lower-case (guid/guid-name %))
        named (k url)
        place (site url)]
    (for [[feed feed-url] known
          :when (or (= named (k feed-url))
                    (some->> (resolve/link feed) (on-website? place)))]
      (candidate feed-url :known feed))))

(defn- identities
  "What tells the feed of the candidate `c` from others: its URL and its
  self link, both without scheme and trailing slashes."
  [{:keys [url feed] :as c}]
  (disj (hash-set (guid/guid-name url) (some-> (resolve/self-url feed) guid/guid-name)) nil))

(defn ranked
  "The `candidates` best first, with each feed once.

  Podcasts come before other feeds, such as the feed of a blog or its
  comments. Then they're ordered by where they were found, as found-by
  orders the places, and otherwise they keep their order, which is that
  of the page. Two candidates are one feed when they share a URL or a
  self link, and the better one stays. The feeds of one show in two audio
  formats can share a podcast:guid, so they stay apart."
  [candidates]
  (let [rank (zipmap found-by (range))]
    (->> candidates
         (sort-by (juxt (complement :podcast?) #(rank (:found-by %) (count found-by))))
         (reduce (fn [{:keys [seen] :as acc} c]
                   (let [ids (identities c)]
                     (cond-> (update acc :seen into ids)
                       (not-any? seen ids) (update :feeds conj c))))
                 {:feeds [] :seen #{}})
         :feeds)))

(defn- document-request
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
      (if-not (feed-document? body)
        {:error (diagnostics/describe {:id :fetch/malformed :url url :reason "The answer isn't a feed"})}
        (async/then (async/attempt #(rss/parse body (select-keys opts [:quirks? :registry :except])))
                    (fn [{feed :value error :error}]
                      (if error
                        {:error (diagnostics/describe {:id :fetch/malformed :url url :reason (async/explain error)})}
                        {:candidate (candidate (or (:permanent-url response) url) where feed)})))))))

(defn- podcast-found?
  "Whether the state `acc` of find-feeds! has a podcast among its
  candidates, so that the places after are left alone."
  [acc]
  (some :podcast? (:candidates acc)))

(defn- fetch-candidates!
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
                               (async/then (http/try-send! send (document-request url (dissoc opts :credentials)) opts)
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

(defn- fetch-feed-urls!
  "Send the requests of the `call` of directories with `opts`, and give
  the feed URLs of the shows that they answer, as :urls, and the failures,
  as :report. Each of the `asked` is a pair of the directory and its
  request. Deferred."
  [asked call {:keys [send] :as opts}]
  (async/then (async/all (fn [[source request]]
                           (async/then (http/try-send! send request opts)
                                       (fn [{:keys [value] :as attempt}]
                                         (if-let [e (failure (:url request) attempt)]
                                           {:error e}
                                           {:urls (keep resolve/self-url
                                                        (try
                                                          (directory/response source call (:body value))
                                                          (catch #?(:clj Exception :cljs :default) _
                                                            nil)))}))))
                         asked)
              (fn [answers]
                {:urls   (into [] (comp (mapcat :urls) (distinct)) answers)
                 :report (into [] (keep :error) answers)})))

(defn- look-up!
  "The state `acc` of find-feeds! with the feeds of the shows that the
  `asked` lookups find, each a pair of a directory and its request, sent
  with `opts`. Deferred."
  [acc asked opts]
  (if (empty? asked)
    acc
    (async/then (fetch-feed-urls! asked :lookup opts)
                (fn [{:keys [urls report]}]
                  (fetch-candidates! (update acc :report into report) :app-link urls opts)))))

(defn- search-directories!
  "The state `acc` of find-feeds! with the feeds that the directories of
  the :search of `opts` find by the name of the web page `body` at `page`.
  Deferred.

  A feed counts only when its website is on the domain of the page. When
  `acc` has a podcast already, no directory is asked."
  [acc body page {:keys [search index now max-candidates] :as opts}]
  (let [q (when-not (or (empty? search) (podcast-found? acc))
            (page-name body page))]
    (if (nil? q)
      acc
      (let [signed {:credentials index :auth-date (quot (inst-ms (now)) 1000)}
            asked  (for [source (map directory/entry search)]
                     [source (directory/request source :search {:q q :limit max-candidates} signed)])
            domain (http/absolute-url page "/")]
        (async/then (fetch-feed-urls! asked :search opts)
                    (fn [{:keys [urls report]}]
                      (async/then (fetch-candidates! {:candidates [] :report [] :tried (:tried acc)}
                                                     :directory
                                                     urls
                                                     (assoc opts :max-candidates (* max-candidates (count search))))
                                  (fn [found]
                                    (-> acc
                                        (update :report into report)
                                        (update :tried into (:tried found))
                                        (update :candidates into
                                                (filter #(some->> (resolve/link (:feed %)) (same-website? domain)))
                                                (:candidates found)))))))))))

(defn- addresses
  "The URLs to try in turn for the page at `url`, while none answers:
  `url`, and when it was `typed?` without a scheme, the same with http
  and with www."
  [url typed?]
  (distinct (cond-> [url]
              typed? (conj (str/replace url #"^https:" "http:")
                           (str/replace url #"^https://(?!www\.)" "https://www.")))))

(defn- ask-in-turn!
  "Send the requests for the `urls` in turn with `opts` until one answers,
  and give its attempt with the :url that it asked. Deferred.

  Only a connection that fails is followed by the others. A URL that
  isn't allowed isn't, and neither is a server that doesn't answer in
  time, since the others would wait for it as long."
  [[url & more :as urls] {:keys [send] :as opts}]
  (async/then (http/try-send! send (document-request url opts) opts)
              (fn [{:keys [error] :as attempt}]
                (if (and error more (not= ::http/refused (:type (ex-data error))) (not (http/timeout? error)))
                  (ask-in-turn! more opts)
                  (assoc attempt :url url)))))

(defn- fetch-page-feeds!
  "The state `acc` of find-feeds! with the feeds that the page at `url` is
  or names, fetched with `opts`, where `typed?` tells that the scheme of
  the URL was added. Deferred.

  When `acc` has a podcast already, the page isn't fetched. A place in
  the table can have limits of its own, which replace those of `opts`."
  [acc url typed? {:keys [rules guessed-paths timeout-seconds guess-timeout-seconds] :as opts}]
  (if (or (nil? url) (podcast-found? acc))
    acc
    (async/then
     (ask-in-turn! (addresses url typed?) opts)
     (fn [{asked :url response :value :as attempt}]
       (let [page (or (:url response) asked)
             body (str (:body response))]
         (if-let [e (failure asked attempt)]
           (assoc acc :error e)
           (if (feed-document? body)
             (async/then (read-feed asked :input attempt opts)
                         (fn [{:keys [candidate error]}]
                           (cond-> acc
                             candidate (update :candidates conj candidate)
                             error     (assoc :error error))))
             (async/then
              (reduce (fn [acc [where urls limits]] (async/then acc #(fetch-candidates! % where urls (merge opts limits))))
                      (update acc :tried conj asked page)
                      [[:link        (delay (map :href (feed-links body page)))]
                       [:link-header (delay (map :href (header-links (:headers response) page)))]
                       [:anchor      (delay (feed-anchors body page))]
                       [:rule        (delay (mapcat #(rule-urls % rules) (distinct [asked page])))]
                       [:guess       (delay (guesses page guessed-paths))
                        {:timeout-seconds (min timeout-seconds guess-timeout-seconds)}]])
              #(search-directories! % body page opts)))))))))

(defn- result
  "What find-feeds! gives for its state `acc` at the end, for the page at
  `url` and the podcast:guid `guid` of a fast-follow link.

  With a guid, only the feeds with that guid stay, if any have it. The
  :error is there when no feed is, and otherwise it goes in the report."
  [{:keys [known candidates report error] :as acc} url guid]
  (let [feeds  (ranked (concat known candidates))
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
  - :rules, your rules for a page's address, as rule-urls takes them
  - :guessed-paths and :apps, in place of guessed-paths and
    directory.links/apps
  - :send, the function that sends a request, http/send! by default
  - :allow?, a predicate of the URLs a request may go to, http/public? by
    default
  - :timeout-seconds, :guess-timeout-seconds, :max-bytes and
    :max-candidates, over default-limits
  - :user-agent, and the options of rss/parse"
  ([s]
   (find-feeds! s {}))
  ([s opts]
   {:pre [(map? opts)]}
   (let [opts                                (merge default-limits
                                                    {:send          http/send!
                                                     :allow?        http/public?
                                                     :lookup        [:apple :podcastindex]
                                                     :guessed-paths guessed-paths
                                                     :now           time/now}
                                                    opts)
         {:keys [url typed? ids guid error]} (read-input s opts)
         [url credentials]                   (if url (http/split-credentials url) [nil nil])
         acc                                 {:known      (when url (vec (known-matches url (:known opts))))
                                              :candidates []
                                              :report     []
                                              :tried      #{}}]
     (-> (if error
           (assoc acc :error error)
           (look-up! acc (lookups ids guid opts) opts))
         (async/then #(fetch-page-feeds! % url typed? (cond-> opts credentials (assoc :credentials credentials))))
         (async/then #(result % url guid))))))

(comment
  (read-input "podnews.net/rss")
  (read-input "feed:https://feeds.podcastindex.org/pc20.xml")
  (read-input "https://podcasts.apple.com/us/podcast/podcasting-2-0/id1584274529")
  (read-input "pktc://subscribe/feeds.podcastindex.org/pc20.xml")
  (read-input "the daily")

  (feed-links "<link rel=alternate type=application/rss+xml href=/feed.xml>" "https://a.example/")
  (feed-anchors "<a href=/rss>RSS</a><a href=/about>About</a>" "https://a.example/")
  (guesses "https://a.example/shows/x")
  (page-name "<title>Home | The Show</title>" "https://show.example/")
  (feed-document? "<?xml version=\"1.0\"?>\n<rss version=\"2.0\">")
  (same-website? "https://Example.com" "http://www.example.com/podcast/")

  ;; the feeds of a show from its domain, best first
  (map (juxt :title :url :found-by) (:feeds (find-feeds! "freakshow.fm")))
  #_.)
