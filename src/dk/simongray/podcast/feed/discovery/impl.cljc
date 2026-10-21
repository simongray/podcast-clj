(ns ^:no-doc dk.simongray.podcast.feed.discovery.impl
  "The internals of feed.discovery: reading text and pages, the requests
  for pages and feeds, and the directory lookups and searches of
  find-feeds!."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.qname-hiccup :as xml]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-mime :as mime]))

(defn- scheme-of
  "The scheme of the text `s` in lower case, or nil when it has none.

  As in the address bar of Chromium, a name with a dot, or with a port
  after it, is a host, so example.com:8080 and localhost:8000 have no
  scheme."
  [s]
  (when-let [[_ scheme after] (re-find #"^([a-zA-Z][a-zA-Z0-9+.-]*):(.?)" s)]
    (when-not (or (str/includes? scheme ".")
                  (re-matches #"[0-9]" after))
      (str/lower-case scheme))))

(defn web-address
  "The text `s` as an http or https URL, and whether https was added
  because it had no scheme. Nil when it can't be one: text without a
  scheme must have a dot or a port in its domain, and no spaces."
  [s]
  (let [scheme (scheme-of s)
        url    (str "https://" (str/replace s #"^/+" ""))]
    (cond
      (#{"http" "https"} scheme)
      (when (url/web-url? s) [s false])

      (or scheme (re-find #"\s" s))
      nil

      (re-find #"^https://[^/?#]*[.:]" url)
      (when (url/web-url? url) [url true]))))

(defn nodes-of
  "The nodes of the web page `s`, its elements and texts at any depth."
  [s]
  (tree-seq vector? #(drop 2 %) (into [:root {}] (html/parse s))))

(defn tagged
  "A predicate of the nodes of a page that are elements with the `tag`."
  [tag]
  #(and (vector? %) (= tag (first %))))

(defn text-of
  "The text `s` when it holds more than ASCII whitespace, or else nil."
  [s]
  (when (and (string? s) (re-find #"[^\t\n\f\r ]" s))
    s))

(defn page-base
  "The URL that the links of a page with the `nodes` are relative to, for a
  page at `url`, which can be nil. It's the href of the first base element
  that has one, when that gives an http or https URL, or else `url`."
  [nodes url]
  (let [bases (filter (tagged :base) nodes)
        href  (some (comp text-of :href second) bases)
        base  (when href
                (if url (url/absolute-url url href) href))]
    (if (#{"http" "https"} (url/scheme base)) base url)))

(defn feed-link
  "The feed that a link with the attributes `attrs` names, made absolute
  against `base` when there is one, or nil when the link names no feed."
  [{:keys [rel type href title] :as attrs} base]
  (let [rels    (set (str/split (str/lower-case (str rel)) #"\s+"))
        type    (mime/essence (text-of type))
        href    (when-let [h (text-of href)]
                  (if base (url/absolute-url base h) h))
        schemes (if base #{"http" "https"} #{nil "http" "https"})]
    (when (and (or (rels "alternate") (rels "feed"))
               (#{"application/rss+xml" "application/atom+xml"} type)
               href
               (contains? schemes (url/scheme href)))
      (cond-> {:href href :type type}
        (text-of title) (assoc :title title)))))

(defn site
  "The domain of the web page `url` in lower case and without a leading
  www., and its path without trailing slashes. Nil for no web page."
  [url]
  (when-let [host (url/host url)]
    (let [[_ path] (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:/*[^/?#]*([^?#]*)"
                            (str url))
          path     (or path "")]
      [(str/replace host #"^www\." "")
       (url/without-trailing-slashes path)])))

(defn page-name
  "The name that the web page `s` at `url` gives itself, to search a
  directory by.

  A home page is named by its og:site_name, and another page by its
  og:title or title without the name of the site, since a show can have
  a page on the site of a network. Of a title such as Home | The Show,
  it's the longest part."
  [s url]
  (let [nodes   (nodes-of s)
        metas   (filter (tagged :meta) nodes)
        meta-of (fn [property]
                  (some (fn [[_ attrs]]
                          (let [p (str/lower-case (str (:property attrs)))]
                            (when (= property p)
                              (some-> (text-of (:content attrs))
                                      str/trim))))
                        metas))
        inner   #(text-of (apply str (filter string? (drop 2 %))))
        title   (or (meta-of "og:title")
                    (some inner (filter (tagged :title) nodes)))
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
      (>= i n)
      nil

      (re-matches #"\s" (subs s i (inc i)))
      (recur (inc i))

      (.startsWith s "<!--" i)
      (when-let [end (str/index-of s "-->" (+ i 4))]
        (recur (+ (long end) 3)))

      (.startsWith s "<?" i)
      (when-let [end (str/index-of s "?>" (+ i 2))]
        (recur (+ (long end) 2)))

      (.startsWith s "<!" i)
      (when-let [end (str/index-of s ">" (+ i 2))]
        (recur (inc (long end))))

      :else
      i)))

(defn feed-document?
  "Whether the text `s` is a feed rather than a web page, by its first
  element, as the feed finders of RSS readers sniff it. The element must
  start in the first 4 KB."
  [s]
  (let [s    (str s)
        n    (count s)
        i    (first-element s (min 4096 n))
        head (when i
               (subs s i (min n (+ i 9))))]
    (boolean (and head
                  (re-find #"^<(?:rss|rdf:RDF|feed)[\s/>]" head)))))

(defn on-website?
  "Whether the page at `place`, its domain and path, is on the website
  `link`."
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

(defn document-request
  "The request for the feed or the web page at `url`, with the :user-agent
  and the :credentials of `opts`."
  [url {:keys [user-agent credentials] :as opts}]
  (let [accept (str "application/rss+xml, application/xml;q=0.9,"
                    " text/xml;q=0.9, text/html;q=0.8, */*;q=0.1")]
    {:url     url
     :headers (cond-> {"Accept" accept}
                user-agent  (assoc "User-Agent" user-agent)
                credentials (assoc "Authorization"
                                   (http/authorization credentials)))}))

(defn failure
  "The diagnostic of the `attempt` at `url` when it gave no document: the
  request failed, or the answer has a status that isn't a success."
  [url {:keys [value error] :as attempt}]
  (cond
    error
    (diagnostics/fetch-failure (assoc (http/failure error) :url url))

    (not (<= 200 (long (:status value)) 299))
    (diagnostics/fetch-failure {:url url :status (:status value)})))

(defn parsed
  "The feed in the text `s` from `url`, parsed with the options of
  rss/parse in `opts`, as a map of the :feed, or of the diagnostic as
  :error when it isn't a feed. A platform without an XML parser throws."
  [s url opts]
  (if (feed-document? s)
    (rss/try-parse s (-> (select-keys opts [:quirks? :registry :except
                                            :lenient-parser])
                         (assoc :url url)))
    (diagnostics/failed {:id     :fetch/malformed
                         :url    url
                         :reason "The answer isn't a feed"})))

(defn read-feed
  "The feed in the `attempt` at `url`, parsed with `opts`, as a map of the
  :url that it was found at and the :feed, or of the diagnostic as :error
  when the request failed or the answer isn't a feed."
  [url {response :value :as attempt} opts]
  (if-let [e (failure url attempt)]
    {:error e}
    (assoc (parsed (str (:body response)) url opts)
           :url (or (:permanent-url response) url))))

(defn fetch-feed!
  "Fetch the whole feed at `url` with `opts`, and give it as read-feed
  does. Deferred."
  [url {:keys [send] :as opts}]
  (async/then (http/try-send! send (document-request url opts) opts)
              #(read-feed url % opts)))

(defn- item-end
  "Where the end tag of the `n`th item in the `text` ends, or nil when the
  text has fewer."
  [text n]
  (loop [from 0
         n    n]
    (when-let [i (str/index-of text "</item" from)]
      (when-let [j (str/index-of text ">" i)]
        (let [end? (str/blank? (subs text (+ i 6) j))]
          (cond
            (and end? (== 1 n)) (inc j)
            end?                (recur (inc j) (dec n))
            :else               (recur (inc j) n)))))))

(defn head-reading
  "A reading of the view `v` of a feed: the text of the feed up to the end
  of its `n`th item and then closed, as :text and :cut?, or the bytes that
  it needs to find that end. A feed that the view holds whole is read
  whole, and so is a feed of fewer items, or in an encoding such as
  UTF-16, where the end tag can't be found before decoding."
  [n v]
  (view/reading
   #(let [held (view/held v)]
      (if (= held (:total v))
        {:text (xml/decode (view/slice v 0 held))}
        (if-let [end (item-end (view/text v 0 held :latin1) n)]
          {:text (str (xml/decode (view/slice v 0 end)) "</channel></rss>")
           :cut? true}
          (view/need v held (inc held)))))))

(defn podcast-found?
  "Whether the state `acc` of a search has a podcast among its candidates,
  so that the places after are left alone."
  [acc]
  (some :podcast? (:candidates acc)))

(defn signing
  "The options that sign a request to the Podcast Index with the :index
  credentials of `opts`, at the time of their clock."
  [{:keys [index now-fn] :as opts}]
  {:credentials index
   :auth-date   (time/unix-seconds (now-fn))})

(defn lookups
  "The lookups of the show that the `ids` of a link of an app name, or the
  podcast:guid `guid` of a fast-follow link, at the :lookup directories of
  `opts`. Each is a pair of the directory and its request.

  Apple takes its own id. The Podcast Index takes its own id or a guid,
  when `opts` give its :index credentials."
  [{:keys [apple-id podcastindex-id podcast-guid] :as ids}
   guid
   {:keys [lookup index] :as opts}]
  (let [entries      (map directory/entry lookup)
        of-api       (fn [api] (first (filter #(= api (:api %)) entries)))
        apple        (of-api :apple)
        podcastindex (of-api :podcastindex)
        index-id     (or podcast-guid guid podcastindex-id)]
    (cond-> []
      (and apple apple-id)
      (conj [apple (directory/request apple :lookup {:id apple-id})])

      (and podcastindex index index-id)
      (conj [podcastindex (directory/request podcastindex :lookup
                                             {:id index-id}
                                             (signing opts))]))))

(defn fetch-feed-urls!
  "Send the requests of the `call` of directories with `opts`, and give
  the feed URLs of the shows that they answer, as :urls, and the failures,
  as :report. Each of the `asked` is a pair of the directory and its
  request. Deferred."
  [asked call {:keys [send] :as opts}]
  (let [feed-urls (fn [source body]
                    (keep resolve/self-url
                          (try
                            (directory/response source call body)
                            (catch #?(:clj Exception :cljs :default) _
                              nil))))
        ask       (fn [[source request]]
                    (async/then
                     (http/try-send! send request opts)
                     (fn [{:keys [value] :as attempt}]
                       (if-let [e (failure (:url request) attempt)]
                         {:error e}
                         {:urls (feed-urls source (:body value))}))))]
    (async/then (async/all ask asked)
                (fn [answers]
                  {:urls   (into [] (comp (mapcat :urls) (distinct)) answers)
                   :report (into [] (keep :error) answers)}))))

(defn addresses
  "The URLs to try in turn for the page at `url`, while none answers:
  `url`, and when it was `typed?` without a scheme, the same with http
  and with www."
  [url typed?]
  (let [plain (str/replace url #"^https:" "http:")
        www   (str/replace url #"^https://(?!www\.)" "https://www.")]
    (distinct (cond-> [url]
                typed? (conj plain www)))))

(defn ask-in-turn!
  "Send the requests for the `urls` in turn with `opts` until one answers,
  and give its attempt with the :url that it asked. Deferred.

  Only a connection that fails is followed by the others. A URL that
  isn't allowed isn't, and neither is a server that doesn't answer in
  time, since the others would wait for it as long."
  [[url & more :as urls] {:keys [send] :as opts}]
  (async/then (http/try-send! send (document-request url opts) opts)
              (fn [{:keys [error] :as attempt}]
                (if (and error
                         more
                         (not= ::http/refused (:type (ex-data error)))
                         (not (http/timeout? error)))
                  (ask-in-turn! more opts)
                  (assoc attempt :url url)))))
