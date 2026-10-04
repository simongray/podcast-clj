(ns inspector.service
  "The reference web app: a feed inspector on Pedestal that uses nothing but
  the public API of the library. Start it with `clojure -M:web` or from the
  REPL with `(start!)`, then open http://localhost:8300/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.search :as search]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.file :as file]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.sync.gpodder.server :as gpodder-server]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.sync.webdav.server :as webdav-server]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.proxy :as proxy]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.lnaddress :as lnaddress]
            [dk.simongray.podcast.value.nwc :as nwc]
            [inspector.dav :as dav]
            [inspector.page :as page]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.interceptor :as interceptor]
            [replicant.string :as replicant]))

(def corpus-dir
  "Where the corpus feeds are, when they have been downloaded."
  (io/file "dev-resources/corpus"))

(defn corpus
  "The corpus feeds on disk as `[name url]`, the URL being the local route."
  []
  (->> (when (.isDirectory ^java.io.File corpus-dir) (.listFiles ^java.io.File corpus-dir))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".xml"))
       (map #(str/replace (.getName ^java.io.File %) #"\.xml$" ""))
       sort
       (map (fn [name] [name (str "corpus/" name)]))))

(defn- corpus-file
  "The file of the corpus feed `name`."
  ^java.io.File [name]
  (io/file corpus-dir (str name ".xml")))

(defonce ^{:doc "Recent readings by key, fetched feeds and companion files and
  the corpus feeds read from disk, each with the time it was last used."}
  cache
  (atom {}))

(def cache-seconds
  "How long a fetch is reused before the server is asked whether it changed."
  300)

(def cache-limit
  "How many readings the cache keeps; the least recently used goes first."
  16)

(defn- cached
  "The entry under `k` in the cache while `fresh?` holds for it, or else the
  one `make` makes of it, or of nil when there is none, kept in its place."
  [k fresh? make]
  (let [old   (get @cache k)
        entry (assoc (if (and old (fresh? old)) old (make old)) :used (System/nanoTime))]
    (swap! cache (fn [c]
                   (let [c (assoc c k entry)]
                     (if (> (count c) cache-limit)
                       (into {} (take cache-limit) (sort-by (comp :used val) > c))
                       c))))
    entry))

(defn- fetch
  "What `read` makes of the body at `url`, in `{:url :status :value}` with
  the final URL and the status; `kind` tells the readings of one URL apart.
  A fetch is reused for `cache-seconds`, and after that for as long as the
  server answers that the body has not changed."
  [kind url read]
  (let [now (System/currentTimeMillis)]
    (cached [kind url]
            #(< (- now (:at %)) (* 1000 cache-seconds))
            (fn [old]
              (let [{:keys [status body] :as response} (http/fetch! url (select-keys old [:etag :last-modified]))]
                (cond
                  (and old (= 304 status))  (assoc old :at now)
                  (and (= 200 status) body) (-> (select-keys response [:url :status :etag :last-modified])
                                                (assoc :at now :value (read body)))
                  (= 200 status)            (throw (ex-info (str url " answered with an empty body") {:status status}))
                  :else                     (throw (ex-info (str "HTTP " status " for " url) {:status status}))))))))

(defn- load-feed
  "The parsed feed at `url`, or the corpus file when `url` names one, as
  `{:url :feed-url :feed :report :status}`, parsed again only when it
  changed. The :feed-url is where the feed lives, which names it: `url`,
  or the self link of a corpus file, whose `url` is only its route here."
  [url]
  (if-let [[_ name] (re-matches #"corpus/([\w.-]+)" url)]
    (let [file     (corpus-file name)
          _        (when-not (.exists file)
                     (throw (ex-info (str "No feed " name " in the corpus") {::status 404})))
          modified (.lastModified file)
          feed     (:value (cached [:corpus name]
                                   #(= modified (:modified %))
                                   (fn [_] {:modified modified :value (podcast/parse (slurp file))})))]
      {:url url :feed-url (or (resolve/self-url feed) url) :feed feed :report (podcast/report feed)})
    (let [{:keys [value status]} (fetch :feed url podcast/parse)]
      {:url url :feed-url url :feed value :report (podcast/report value) :status status})))

(defn- html
  [hiccup]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   ;; Replicant 2026.06.2 and later write a double quote as &#39;, which is
   ;; an apostrophe. They write an apostrophe as &apos; and an ampersand as
   ;; &amp;, so every &#39; stands for a double quote.
   :body    (str "<!DOCTYPE html>\n" (str/replace (replicant/render hiccup) "&#39;" "&quot;"))})

(defn- text
  [content-type s]
  {:status 200 :headers {"Content-Type" content-type} :body s})

(defn- failure
  "The error page, with the status 502, for the failure `e` of what
  `heading` names, about `subject` when anything, and with the hiccup
  `more`, e.g. what to try instead."
  ([heading subject e]
   (failure heading subject e nil))
  ([heading subject e more]
   (-> (html (page/error-page heading subject (or (ex-message e) (str e)) more))
       (assoc :status (::status (ex-data e) 502)))))

(defn- item-in
  "The item of `feed` whose guid is `guid`, or nil, also for a blank
  `guid`, which would name an item without a guid or an enclosure."
  [feed guid]
  (when-not (str/blank? guid)
    (some #(when (= guid (feed/item-guid %)) %) (:rss/items feed))))

(defn- with-feed
  "Call `f` with the loaded feed for the `url` query parameter of `request`,
  rendering an error page when anything fails, a stack overflow included."
  [request f]
  (let [url (get-in request [:query-params :url])]
    (if (str/blank? url)
      {:status 302 :headers {"Location" "/"}}
      (try
        (f (load-feed url))
        (catch Throwable e
          (failure "Could not inspect the feed" url e
                   [:p "If this is the address of a website or a page, "
                    [:a {:href (str "/find?q=" (http/percent-encode url))} "find the feeds that it names"] "."]))))))

(defn home
  "The landing page: the URL form and the corpus."
  [_]
  (html (page/home (corpus))))

(defn- selection
  "What the feed page's filter form asks for, from the query `params`: the
  criteria and the order of `search/episodes`, an order the page does not
  offer left out."
  [{:keys [q season type order]}]
  (let [where (cond-> {}
                (not (str/blank? q))                         (assoc :text q)
                (some-> season parse-long)                   (assoc :season (parse-long season))
                (contains? #{"full" "trailer" "bonus"} type) (assoc :type type))
        order (some-> order not-empty keyword)]
    (cond-> {:where where}
      (some #{order} page/orders) (assoc :order order))))

(defn feed-page
  "The inspection of the feed named by the `url` query parameter, validated
  against the requirements that the `requirements` parameter names, if
  any, its items filtered and ordered as the `q`, `season`, `type` and
  `order` parameters ask."
  [request]
  (with-feed request
    (fn [loaded]
      (let [requirements (some-> (get-in request [:query-params :requirements]) not-empty keyword)]
        (html (page/feed-page (assoc loaded
                                     :requirements (when (contains? validate/requirements requirements) requirements)
                                     :selection (selection (:query-params request)))))))))

(defn- checked
  "The check over the network of `feed`, fetched from `url`, for its newest
  `items`, reading what the files hold, and their integrity when
  `integrity?`: reused for `cache-seconds` while the feed is the same, so
  that the page judges one check by each choice of requirements."
  [url feed items integrity?]
  (let [now (System/currentTimeMillis)]
    (:value (cached [:check url items integrity?]
                    #(and (identical? feed (:feed %)) (< (- now (:at %)) (* 1000 cache-seconds)))
                    (fn [_]
                      {:at    now
                       :feed  feed
                       :value (check/check-files! feed {:url url :max-items items :read? true :integrity? integrity?})})))))

(defn check-page
  "The check over the network of the files that the feed named by the `url`
  query parameter links to, for its newest `items` (default 5, at most 50),
  against the requirements that the `requirements` parameter names, if
  any, reading what the files hold, and with the `integrity` parameter,
  checking each file that has a podcast:integrity against it."
  [request]
  (with-feed request
    (fn [{:keys [url feed-url feed]}]
      (let [{:keys [requirements items integrity]} (:query-params request)
            requirements (some-> requirements not-empty keyword)
            requirements (when (contains? check/requirements requirements) requirements)
            items        (or (some-> items parse-long (max 1) (min 50)) 5)
            integrity?   (= "1" integrity)
            {:keys [files]} (checked feed-url feed items integrity?)]
        (html (page/check-page {:url url :feed feed :requirements requirements :items items :integrity? integrity?
                                :result {:files files :report (check/report files requirements)}}))))))

(defn feed-edn
  "The parsed feed as EDN, pretty-printed for a feed of up to a hundred
  items, since that takes about two seconds per hundred, and else on one
  line, which an editor can format."
  [request]
  (with-feed request
    (fn [{:keys [feed]}]
      (text "text/plain; charset=utf-8"
            (if (<= (count (:rss/items feed)) 100)
              (with-out-str (pprint/pprint feed))
              (str ";; on one line, since pretty-printing more than a hundred items takes too long\n"
                   (pr-str feed)))))))

(defn feed-xml
  "The feed written back out canonically."
  [request]
  (with-feed request
    (fn [{:keys [feed]}]
      (text "application/rss+xml; charset=utf-8" (podcast/emit feed)))))

(defn corpus-page
  "The inspection of a corpus feed, by its file name."
  [request]
  (feed-page (assoc-in request [:query-params :url]
                       (str "corpus/" (get-in request [:path-params :name])))))

(def index-credentials
  "Podcast Index API credentials from the environment, when the inspector
  is to resolve remote value splits: PODCASTINDEX_API_KEY and
  PODCASTINDEX_API_SECRET. The secret is a hidden text, so the value of
  this var does not show it at a REPL."
  (let [key    (System/getenv "PODCASTINDEX_API_KEY")
        secret (System/getenv "PODCASTINDEX_API_SECRET")]
    (when (and (seq key) (seq secret))
      {:key key :secret (secret/hide secret) :user-agent "podcast-clj inspector"})))

(defn- known!
  "Keep `feed`, which find-feeds! fetched from `url`, as if the inspector
  had fetched it, so that its page doesn't fetch it again."
  [url feed]
  (cached [:feed url] (constantly false)
          (fn [_] {:url url :status 200 :value feed :at (System/currentTimeMillis)})))

(defn find-page
  "The feeds that podcast/find-feeds! finds for the text in the `q`
  parameter: a feed URL, a website, a page or a link of an app.

  When a page names no podcast, the directories are searched by its name,
  for a feed that names the page's website as its own. The page of the
  feed follows at once when the text is the feed, or names one podcast
  alone. Words that name nothing go to the directory search."
  [request]
  (let [q (str/trim (str (get-in request [:query-params :q])))]
    (if (str/blank? q)
      {:status 302 :headers {"Location" "/"}}
      (try
        (let [opts                            (cond-> {:search [:apple :fyyd :gpodder]}
                                                index-credentials (-> (assoc :index index-credentials)
                                                                      (update :search conj :podcastindex)))
              {:keys [feeds error] :as found} (podcast/find-feeds! q opts)
              best                            (first feeds)]
          (cond
            (= :discovery/unreadable (:id error))
            {:status 302 :headers {"Location" (str "/directory?q=" (http/percent-encode q))}}

            (or (= :input (:found-by best)) (= 1 (count (filter :podcast? feeds))))
            (do (known! (:url best) (:feed best))
                {:status 302 :headers {"Location" (str "/feed?url=" (http/percent-encode (:url best)))}})

            :else
            (html (page/find-page (assoc found :q q)))))
        (catch Throwable e
          (failure "Could not find the feeds" q e))))))

(defn- episode-view
  "The view of `item` in `feed` at `url`, with what its files say merged
  in, as `episode/view` and `episode/read-files!` give it; kept for
  `cache-seconds`, since the files are fetched to make it."
  [feed url item]
  (let [now (System/currentTimeMillis)]
    (:value (cached [:episode url (feed/item-guid item)]
                    #(< (- now (:at %)) (* 1000 cache-seconds))
                    (fn [_]
                      (let [view (episode/view feed item {:url url})]
                        {:at now :value (merge view (episode/read-files! view))}))))))

(defn- remotes
  "The value blocks that the time splits of the episode `view` point at, as
  `episode/fetch-remote-values!` fetches them, and whether the index can
  be asked."
  [view]
  (assoc (episode/fetch-remote-values! view {:index index-credentials})
         :index? (some? index-credentials)))

(defn item-page
  "One item of the feed named by `url`, found by the `guid` parameter, with
  its chapters, transcript, media file and value block fetched and
  rendered."
  [request]
  (with-feed request
    (fn [{:keys [feed feed-url] :as loaded}]
      (let [guid (get-in request [:query-params :guid])
            item (item-in feed guid)]
        (if item
          (let [view (episode-view feed feed-url item)]
            (html (page/item-page (assoc loaded :item item :view view :remotes (remotes view)))))
          (-> (html (page/error-page "No such item in the feed" (:url loaded) (str "No item with guid " guid)))
              (assoc :status 404)))))))

(def appview
  "The AppView that the comments page reads AT Protocol threads from. It is
  the public AppView of Bluesky, which answers without an account.
  podcast-clj names no AppView, because each app chooses its own."
  "https://public.api.bsky.app")

(defn- thread-of
  "The thread of comments below `interact`.

  Each visit reads for ten seconds at most, so that the page comes soon.
  The inspector keeps a thread for `cache-seconds`. While the thread is
  incomplete, or a read of it failed, the next visit reads on."
  [interact]
  (let [now      (System/currentTimeMillis)
        fresh?   #(< (- now (:at %)) (* 1000 cache-seconds))
        read-on? #(some #{:comments/incomplete :comments/unreachable :comments/malformed} (map :id (:report (:value %))))]
    (:value (cached [:comments interact]
                    #(and (fresh? %) (not (read-on? %)))
                    (fn [old]
                      (let [old (when (and old (fresh? old)) old)]
                        {:at    (or (:at old) now)
                         :value (comments/fetch! (or (:value old) interact) {:appview appview :max-seconds 10})}))))))

(defn comments-page
  "The comments page of the item that the `guid` parameter names, in the
  feed that the `url` parameter names, for `request`.

  It shows the thread that `comments/thread` picks. When the feed names
  no thread that podcast-clj reads, it shows the notes about the episode
  on Nostr, unless the podcaster wants no comments shown. With the
  `hidden` parameter, it also shows the replies that the author of the
  root post hid."
  [request]
  (with-feed request
    (fn [{:keys [url feed]}]
      (let [{:keys [guid hidden]} (:query-params request)
            item      (item-in feed guid)
            interacts (when item (resolve/social-interacts feed item))
            thread    (when item
                        (or (comments/thread feed item {:appview appview})
                            (when-not (= "disabled" (:protocol (first interacts)))
                              (nostr-comments/about feed item))))]
        (if item
          (html (page/comments-page {:url       url
                                     :item      item
                                     :interacts interacts
                                     :hidden?   (= "1" hidden)
                                     :thread    (some-> thread thread-of)}))
          (-> (html (page/error-page "No such item in the feed" url (str "No item with guid " guid)))
              (assoc :status 404)))))))

(defn- interact-of
  "The thread below the post that the address `s` names, as the interact
  `{:protocol :uri}` that `comments/fetch!` reads. Nil for a text that is
  no address of a post.

  A person has the address of a web page, so the inspector takes the page
  of a post at bsky.app and at njump.me too. These are the pages of two
  web apps that the inspector chose."
  [s]
  (let [s (str/trim (str s))
        [_ profile rkey] (re-matches #"https://bsky\.app/profile/([^/\s?#]+)/post/([^/\s?#]+).*" s)
        [_ note]         (re-matches #"https://njump\.me/((?:note|nevent)1[a-z0-9]+).*" s)
        nostr            {:protocol "nostr" :uri (if note (str "nostr:" note) s)}]
    (cond
      profile                       {:protocol "atproto" :uri (str "at://" profile "/app.bsky.feed.post/" rkey)}
      (str/starts-with? s "at://")  {:protocol "atproto" :uri s}
      (comments/readable? nostr)    nostr
      (re-find #"^https?://\S+$" s) {:protocol "activitypub" :uri s})))

(defn thread-page
  "The page of the comments below the post that the `uri` parameter names,
  for `request`. With the `hidden` parameter, it also shows the replies
  that the author of the root post hid."
  [request]
  (let [{:keys [uri hidden]} (:query-params request)]
    (html (page/thread-page {:address uri
                             :hidden? (= "1" hidden)
                             :thread  (some-> (interact-of uri) thread-of)}))))

(def wallet-opts
  "The options of the requests to a wallet. The inspector asks each relay
  that a connection names, also a relay on the own network, and waits
  half a minute for the wallet."
  {:allow? (constantly true) :timeout-seconds 30})

(def app-name
  "The name of the inspector in the record of a payment."
  "podcast-clj inspector")

(defn- asked
  "What the wallet of `connection` answers to `command`. Nil when the
  `info` of the wallet does not name the method of the command."
  [connection info command]
  (when (or (:error info) (some #{(:method command)} (:methods info)))
    (nwc/ask! connection command (assoc wallet-opts :info (when-not (:error info) info)))))

(defn- paid
  "What the wallet of `connection` answers to `payment` for `recipient`:
  a keysend payment for a node, and the payment of an invoice for a
  Lightning address."
  [connection info payment {:keys [type address] :as recipient}]
  (let [opts    (assoc wallet-opts :info (when-not (:error info) info))
        keysend (when (= "node" type) (nwc/pay-keysend payment recipient))
        invoice (when (= "lnaddress" type)
                  (lnaddress/request-invoice! address (* 1000 (:sats payment)) {:comment (:message payment)}))]
    (cond
      (seq (value/report keysend)) {:error (first (value/report keysend))}
      keysend                      (nwc/ask! connection keysend opts)
      (:error invoice)             invoice
      invoice                      (nwc/ask! connection (nwc/pay-invoice (:invoice invoice)) opts)
      :else                        {:error {:level :warning :message "The inspector pays a node and a Lightning address only."}})))

(defn- boosted
  "The payments of a boost of `sats` with `message` for `item` of `feed`
  at `url`, from the wallet of `connection`: the share of each recipient
  of the value block, with the answer of the wallet."
  [connection info {:keys [feed item feed-url]} sats message]
  (let [payment {:action     :boost
                 :total-sats sats
                 :message    (not-empty message)
                 :app-name   app-name
                 :feed-url   feed-url
                 :feed-guid  (feed/feed-guid feed feed-url)
                 :feed-title (resolve/title feed)
                 :item-guid  (feed/item-guid item)
                 :item-title (resolve/title item)}]
    (vec (for [{:keys [recipient amount]} (value/payments (:recipients (resolve/value feed item)) sats)]
           {:recipient recipient
            :sats      amount
            :answer    (when (pos? amount)
                         (paid connection info (assoc payment :sats amount) recipient))}))))

(defn- episode-of
  "The episode that the parameters `url` and `guid` name, as `{:url
  :feed-url :feed :item}`. Nil when they name none."
  [url guid]
  (when-not (str/blank? url)
    (let [{:keys [feed feed-url]} (load-feed url)]
      (when-let [item (item-in feed guid)]
        {:url url :feed-url feed-url :feed feed :item item}))))

(defonce ^{:doc "The tokens of the wallet forms that the inspector gave, and
  that no post used. A post that pays needs such a token, so that a form
  pays once, also when the browser sends it again."}
  wallet-tokens
  (atom #{}))

(defn- new-token!
  "A token for a wallet form. The inspector keeps 500 tokens at most."
  []
  (let [token (bytes/hex (bytes/generate! 16))]
    (swap! wallet-tokens #(conj (if (< (count %) 500) % #{}) token))
    token))

(defn- used-token!
  "Whether `token` is a token that no post used. It is used after this."
  [token]
  (contains? (first (swap-vals! wallet-tokens disj token)) token))

(defn wallet-page
  "The page of the wallet, for `request`. With the parameters `url` and
  `guid`, its form pays a boost for that episode."
  [request]
  (let [{:keys [url guid]} (:query-params request)]
    (try
      (let [episode (episode-of url guid)]
        (if (and (not (str/blank? url)) (nil? episode))
          (-> (html (page/error-page "No such item in the feed" url (str "No item with guid " guid)))
              (assoc :status 404))
          (html (page/wallet-page {:episode episode :token (new-token!)}))))
      (catch Throwable e
        (failure "Could not inspect the feed" url e)))))

(defn wallet-asked
  "The page of the wallet with what the wallet answers, for `request`,
  the post of the form. The form has the connection URI of Nostr Wallet
  Connect as `uri`. With `url`, `guid` and `sats`, the wallet pays a boost
  for the episode.

  The URI holds a secret key. The inspector uses it for this request,
  keeps it nowhere, and gives the page no part of it. A post that pays
  needs the token of its form, and a token pays once."
  [request]
  (let [{:keys [uri url guid sats message token]} (:form-params request)
        connection (nwc/parse uri)
        sats       (some-> sats str/trim parse-long)
        pays?      (boolean (and connection sats (pos? sats)))
        fresh?     (and pays? (used-token! token))]
    (try
      (let [episode (episode-of url guid)
            info    (some-> connection (nwc/fetch-info! wallet-opts))
            boost   (when (and fresh? episode)
                      {:sats sats :payments (boosted connection info episode sats message)})]
        (html (page/wallet-page
               (cond-> {:episode episode :token (new-token!)}
                 (nil? connection) (assoc :problem "The text is no connection URI of Nostr Wallet Connect.")
                 (and pays? (not fresh?))
                 (assoc :problem (str "The inspector paid nothing. This form was sent before, or it is old. "
                                      "Give the amount again in the form of this page."))
                 boost             (assoc :boost boost)
                 connection        (assoc :connection (dissoc connection :secret)
                                          :app        (nwc/public-key connection)
                                          :info       info
                                          :wallet     (asked connection info (nwc/get-info))
                                          :balance    (asked connection info (nwc/get-balance)))))))
      (catch Throwable e
        (failure "Could not ask the wallet" nil e)))))

(defonce ^{:doc "The corpus feeds parsed and indexed for the search page, nil
  until the first search makes them."}
  corpus-search
  (atom nil))

(defn- corpus-index
  "The corpus feeds by URL and their index, made by the first search and
  kept; a failure is not kept, so that the next search tries again."
  []
  (or @corpus-search
      (reset! corpus-search
              (let [feeds (into {} (for [[name url] (corpus)] [url (podcast/parse (slurp (corpus-file name)))]))]
                {:feeds feeds
                 :index (search/index (for [[url feed] feeds
                                            item       (:rss/items feed)]
                                        (search/item-document feed item {:url url})))}))))

(defn- with-items
  "The search `results` with the feed and item each came from, looked up in
  `feeds` by url, so that the page can show a snippet of the notes."
  [results feeds]
  (for [{:keys [stored] :as result} results
        :let [feed (get feeds (:url stored))
              item (some #(when (= (:guid stored) (feed/item-guid %)) %) (:rss/items feed))]]
    (assoc result :feed feed :item item)))

(defn search-page
  "The items matching the `q` parameter: those of the feed named by `url`,
  or of the whole corpus when there is no `url`."
  [request]
  (let [q      (str (get-in request [:query-params :q]))
        url    (get-in request [:query-params :url])
        render (fn [index feeds url]
                 (html (page/search-page {:q       q
                                          :url     url
                                          :results (with-items (search/query index q {:limit page/result-limit}) feeds)})))]
    (if (str/blank? url)
      (try
        (let [{:keys [index feeds]} (corpus-index)]
          (render index feeds nil))
        (catch Throwable e
          (failure "Could not search the corpus" nil e)))
      (with-feed request
        (fn [{:keys [feed url]}]
          ;; the index of a big feed takes a second to make, so it is kept
          ;; for as long as the feed it was made of
          (let [index (:value (cached [:index url]
                                      #(identical? feed (:feed %))
                                      (fn [_] {:feed  feed
                                               :value (search/index (map #(search/item-document feed % {:url url})
                                                                         (:rss/items feed)))})))]
            (render index {url feed} url)))))))

(defn directory-page
  "The shows the directories answer for the `q` parameter, or their charts
  for the `country` parameter when there is no query, the Podcast Index
  among them when its credentials are set. The `category` parameter
  gives the charts of one category."
  [request]
  (let [{:keys [q country category]} (:query-params request)
        opts (cond-> {:limit 10}
               (not (str/blank? country))  (assoc :country country)
               index-credentials           (assoc :credentials index-credentials)
               (not (str/blank? category)) (assoc :category category))]
    (try
      (let [answer (if (str/blank? q)
                     (directory/fetch-chart! opts)
                     (directory/search! q opts))]
        (html (page/directory-page (assoc answer :q q :country (:country opts) :category (:category opts)))))
      (catch Throwable e
        (failure "Could not ask the directories" (if (str/blank? q) (:country opts) q) e)))))

(defn podping-page
  "The podpings of the last minute or so of the Hive chain: twenty blocks
  before the head, read live, verified against the writers podping
  follows."
  [_]
  (try
    (let [head (:block (podping/head-block (podping/call! :head-block)))
          step (podping/poll! (- head 20) {:max-blocks 20})]
      (html (page/podping-page (assoc step :head head))))
    (catch Throwable e
      (failure "Could not read the Hive chain" nil e))))

(defn queue-page
  "The shell the browser player mounts into; the player itself is
  inspector.player, compiled by `clojure -M:cljs compile
  player`."
  [_]
  (html (page/queue-page)))

(defn episode-edn
  "The item named by the `url` and `guid` parameters as the EDN the browser
  player needs: its identity, titles, enclosure and duration."
  [request]
  (with-feed request
    (fn [{:keys [feed url feed-url]}]
      (let [guid (get-in request [:query-params :guid])
            item (item-in feed guid)]
        (if item
          (text "application/edn; charset=utf-8"
                (pr-str {:id         (feed/episode-id feed feed-url item)
                         :title      (resolve/title item)
                         :feed-title (resolve/title feed)
                         :enclosure  (:url (resolve/enclosure item))
                         :duration   (resolve/duration item)
                         :url        url
                         :guid       guid}))
          {:status 404 :headers {"Content-Type" "text/plain"} :body (str "No item with guid " guid)})))))

(def player-js-dir
  "Where shadow-cljs puts the compiled browser player."
  (io/file "target/web/public/js"))

(defn js-file
  "A file of the compiled browser player, with the time it was built, so
  that a browser fetches it again only after a build; or a note on how to
  compile the player."
  [request]
  (let [path  (str (get-in request [:path-params :path]))
        file  (io/file player-js-dir path)
        built (when (and (not (str/includes? path "..")) (.isFile file))
                (time/format-rfc2822 (java.util.Date. (* 1000 (quot (.lastModified file) 1000)))))]
    (cond
      (nil? built)
      {:status  404
       :headers {"Content-Type" "text/plain"}
       :body    (if (.isFile (io/file player-js-dir "player.js"))
                  (str "No file " path " in the player")
                  "The browser player is not compiled; run: clojure -M:cljs compile player")}

      (= built (get-in request [:headers "if-modified-since"]))
      {:status 304 :headers {"Last-Modified" built} :body ""}

      :else
      {:status  200
       :headers {"Content-Type"  (if (str/ends-with? path ".map") "application/json" "application/javascript; charset=utf-8")
                 "Last-Modified" built}
       :body    file})))

(def sync-dir
  "The folder the inspector serves over WebDAV at /dav/, for the browser
  player and any other device to keep in step through: INSPECTOR_SYNC_DIR in
  the environment, else dev-resources/sync, which `clean` leaves alone."
  (io/file (or (System/getenv "INSPECTOR_SYNC_DIR") "dev-resources/sync")))

(defn- cached-feeds
  "The feeds the inspector has fetched and keeps, by URL."
  []
  (into {} (for [[[kind url] {:keys [value]}] @cache
                 :when (and (= :feed kind) (map? value))]
             [url value])))

(defn- added-here
  "The ways to name the episodes that the players of the inspector added to
  `state`, by the data that they wrote under :inspector/episodes: the URL
  of an episode's media, and the episode of a media URL or a guid of its
  feed."
  [state]
  (let [episodes (keep :value (vals (:inspector/episodes state)))]
    {:episode-url (fn [id] (get-in state [:inspector/episodes id :value :enclosure]))
     :episode-id  (fn [podcast media guid]
                    (some (fn [{:keys [id url enclosure] :as episode}]
                            (when (or (= media enclosure) (and guid (= guid (:guid episode)) (= podcast url)))
                              id))
                          episodes))}))

(defn- either
  "The ways to name shows and episodes of `a`, each of which falls back on
  that of `b` when it names nothing."
  [a b]
  (merge-with (fn [f g] (fn [& args] (or (apply f args) (apply g args)))) a b))

(defn gpodder-handler
  "The gpodder API over the listener's state in `sync-store`, for AntennaPod,
  Kasts and gPodder: the server is a device of its own, which reads the
  other devices' documents before it answers and writes its own after a
  change. It names the shows and episodes of the feeds the inspector has
  fetched lately, and the episodes that the players added, whose data are
  in the state. It lets in any user name and password, since the inspector
  listens on localhost alone."
  [sync-store]
  (let [cursors (atom nil)
        backend (store/backend sync-store)
        state   (atom (profile/make "inspector-gpodder"))]
    (gpodder-server/handler
     {:state       state
      :authorized? (constantly true)
      :identities  #(either (gpodder/identities (cached-feeds)) (added-here @state))
      :sync        (fn [state]
                     (let [result (sync/sync! state [backend] {:cursors @cursors})]
                       (reset! cursors (:cursors result))
                       (:state result)))})))

(defn routes
  "The inspector's routes, in Pedestal's set syntax, the WebDAV ones and the
  gpodder API serving `sync-store` (default the folder store over
  `sync-dir`), and the proxy for the requests of a page."
  ([]
   (routes (file/store sync-dir)))
  ([sync-store]
   #{["/" :get home :route-name :home]
     ["/feed" :get feed-page :route-name :feed]
     ["/feed.edn" :get feed-edn :route-name :feed-edn]
     ["/feed.xml" :get feed-xml :route-name :feed-xml]
     ["/check" :get check-page :route-name :check]
     ["/item" :get item-page :route-name :item]
     ["/comments" :get comments-page :route-name :comments]
     ["/thread" :get thread-page :route-name :thread]
     ["/find" :get find-page :route-name :find]
     ["/wallet" :get wallet-page :route-name :wallet]
     ["/wallet" :post wallet-asked :route-name :wallet-asked]
     ["/search" :get search-page :route-name :search]
     ["/directory" :get directory-page :route-name :directory]
     ["/podping" :get podping-page :route-name :podping]
     ["/episode.edn" :get episode-edn :route-name :episode-edn]
     ["/queue" :get queue-page :route-name :queue]
     ["/js/*path" :get js-file :route-name :js]
     ["/dav" :any (webdav-server/handler sync-store "/dav/") :route-name :dav-collection]
     ["/dav/*path" :any (webdav-server/handler sync-store "/dav/") :route-name :dav]
     ["/api/2/*path" :any (gpodder-handler sync-store) :route-name :gpodder]
     ["/proxy" :any (proxy/handler) :route-name :proxy]
     ["/corpus/:name" :get corpus-page :route-name :corpus]}))

(def unread-bodies
  "An interceptor ahead of Pedestal's own that keeps the body of a request
  to the gpodder API unread, since the body-params interceptor would parse
  its JSON before the handler sees it."
  (interceptor/interceptor
   {:name  ::unread-bodies
    :enter (fn [context]
             (update context :request
                     (fn [{:keys [path-info uri] :as request}]
                       (if (str/starts-with? (str (or path-info uri)) "/api/2/")
                         (-> request (update :headers dissoc "content-type") (dissoc :content-type))
                         request))))}))

(def single-params
  "An interceptor that keeps the first value of a query or form parameter
  given more than once, so that every handler reads a string."
  (interceptor/interceptor
   {:name  ::single-params
    :enter (fn [context]
             (reduce (fn [context k]
                       (cond-> context
                         (get-in context [:request k])
                         (update-in [:request k] update-vals #(if (vector? %) (first %) %))))
                     context
                     [:query-params :form-params]))}))

(def local-only
  "An interceptor that answers only requests addressed to localhost, since
  the inspector lets anyone sync and pay through it: a web page that points
  a name of its own at 127.0.0.1 gets a 403 (DNS rebinding)."
  (interceptor/interceptor
   {:name  ::local-only
    :enter (fn [context]
             (let [host (some-> (get-in context [:request :headers "host"]) str/lower-case (str/replace #":\d+$" ""))]
               (if (or (nil? host) (#{"localhost" "127.0.0.1" "[::1]"} host))
                 context
                 (assoc context :response {:status  403
                                           :headers {"Content-Type" "text/plain"}
                                           :body    "The inspector answers requests to localhost only."}))))}))

(defonce ^{:doc "The running connector, nil when stopped."}
  server
  (atom nil))

(defn connector
  "A Jetty connector serving the inspector on `port`, not yet started; tests
  drive its interceptor chain directly. `opts` may name another `:sync-dir`
  to serve over WebDAV."
  ([port]
   (connector port {}))
  ([port {:keys [sync-dir] :or {sync-dir sync-dir}}]
   (-> (conn/default-connector-map port)
       ;; Pedestal's default policy forbids every script tag; the browser
       ;; player is served from this origin, and its development build loads
       ;; its modules through eval.
       (conn/with-default-interceptors
         :secure-headers {:content-security-policy-settings
                          (secure-headers/content-security-policy-header
                           {:object-src "'none'" :script-src "'self' 'unsafe-eval'"})})
       ;; ahead of the default interceptors, so that a put's body reaches
       ;; the WebDAV handler unread
       (update :interceptors #(into [local-only (dav/requests "/dav/") unread-bodies] %))
       (conn/with-interceptor single-params)
       (conn/with-routes (routes (file/store sync-dir)))
       (jetty/create-connector nil))))

(defn start!
  "Start the inspector on `:port` (default 8300); idempotent."
  ([]
   (start! {}))
  ([{:keys [port] :or {port 8300}}]
   (when-not @server
     (reset! server (conn/start! (connector port)))
     (println (str "Inspector running on http://localhost:" port "/")))
   @server))

(defn stop!
  "Stop the inspector."
  []
  (when-let [s @server]
    (conn/stop! s)
    (reset! server nil)))

(defn -main
  "Start the inspector and keep the process alive."
  [& _]
  (start!)
  @(promise))

(comment
  (start!)
  (stop!)
  #_.)
