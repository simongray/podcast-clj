(ns inspector.service
  "The reference web app: a feed inspector on Pedestal that uses nothing but
  the public API of the library. Start it with `clojure -M:web` or from the
  REPL with `(start!)`, then open http://localhost:8300/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.search :as search]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.file :as file]
            [dk.simongray.podcast.app.sync.gpodder :as gpodder]
            [dk.simongray.podcast.app.sync.gpodder.server :as gpodder-server]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.app.sync.webdav.server :as webdav-server]
            [dk.simongray.podcast.check :as check]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.directory.podcastindex :as index]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.podping :as podping]
            [dk.simongray.podcast.resolve :as resolve]
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
  (->> (when (.isDirectory corpus-dir) (.listFiles corpus-dir))
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
                  :else                     (throw (ex-info (str "HTTP " status " for " url) {:status status}))))))))

(defn- load-feed
  "The parsed feed at `url`, or the corpus file when `url` names one, as
  `{:url :feed :report :status}`, parsed again only when it changed."
  [url]
  (if-let [[_ name] (re-matches #"corpus/([\w.-]+)" url)]
    (let [file     (corpus-file name)
          modified (.lastModified file)
          feed     (:value (cached [:corpus name]
                                   #(= modified (:modified %))
                                   (fn [_] {:modified modified :value (podcast/parse (slurp file))})))]
      {:url url :feed feed :report (podcast/report feed)})
    (let [{:keys [value status]} (fetch :feed url podcast/parse)]
      {:url url :feed value :report (podcast/report value) :status status})))

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
  `heading` names, about `subject` when anything."
  [heading subject e]
  (-> (html (page/error-page heading subject (or (ex-message e) (str e))))
      (assoc :status 502)))

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
          (failure "Could not inspect the feed" url e))))))

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
  against the `profile` parameter when it names one, its items filtered and
  ordered as the `q`, `season`, `type` and `order` parameters ask."
  [request]
  (with-feed request
    (fn [loaded]
      (let [profile (some-> (get-in request [:query-params :profile]) not-empty keyword)]
        (html (page/feed-page (assoc loaded
                                     :profile (when (contains? validate/profiles profile) profile)
                                     :selection (selection (:query-params request)))))))))

(defn- checked
  "The check over the network of `feed`, fetched from `url`, for its newest
  `items`, reading what the files hold, and their integrity when
  `integrity?`: reused for `cache-seconds` while the feed is the same, so
  that the page judges one check by each profile."
  [url feed items integrity?]
  (let [now (System/currentTimeMillis)]
    (:value (cached [:check url items integrity?]
                    #(and (identical? feed (:feed %)) (< (- now (:at %)) (* 1000 cache-seconds)))
                    (fn [_]
                      {:at    now
                       :feed  feed
                       :value (check/feed! feed {:url url :items items :read? true :integrity? integrity?})})))))

(defn check-page
  "The check over the network of the files that the feed named by the `url`
  query parameter links to, for its newest `items` (default 5, at most 50),
  against the `profile` parameter when it names one, reading what the files
  hold, and with the `integrity` parameter, checking each file that has a
  podcast:integrity against it."
  [request]
  (with-feed request
    (fn [{:keys [url feed]}]
      (let [{:keys [profile items integrity]} (:query-params request)
            profile    (some-> profile not-empty keyword)
            profile    (when (contains? check/profiles profile) profile)
            items      (or (some-> items parse-long (max 1) (min 50)) 5)
            integrity? (= "1" integrity)
            {:keys [files]} (checked url feed items integrity?)]
        (html (page/check-page {:url url :feed feed :profile profile :items items :integrity? integrity?
                                :result {:files files :report (check/report files profile)}}))))))

(defn feed-edn
  "The parsed feed as pretty-printed EDN."
  [request]
  (with-feed request
    (fn [{:keys [feed]}]
      (text "text/plain; charset=utf-8" (with-out-str (pprint/pprint feed))))))

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

(defn- companion
  "Fetch the companion file at `url` and read its body with `read`, as
  `{:url :status :parsed}` or `{:url :status :error}`; never throws. `kind`
  tells the readings of one URL apart."
  [kind url read]
  (try
    (let [{:keys [status value]} (fetch kind url read)]
      {:url url :status status :parsed value})
    (catch Exception e
      {:url url :status (:status (ex-data e)) :error (or (ex-message e) (str e))})))

(def index-credentials
  "Podcast Index API credentials from the environment, when the inspector
  is to resolve remote value splits: PODCASTINDEX_API_KEY and
  PODCASTINDEX_API_SECRET."
  (let [key    (System/getenv "PODCASTINDEX_API_KEY")
        secret (System/getenv "PODCASTINDEX_API_SECRET")]
    (when (and (seq key) (seq secret))
      {:key key :secret secret :user-agent "podcast-clj inspector"})))

(defn- remote-values
  "The value blocks the remote items of the time splits of `block` point
  at, by remote item, resolved through the index when credentials are set;
  a failure resolves to nil like a miss."
  [block]
  (when index-credentials
    (into {} (for [{:keys [remote-item]} (:time-splits block)
                   :when remote-item]
               [remote-item (try (index/remote-value! index-credentials remote-item)
                                 (catch Exception _ nil))]))))

(defn- companions
  "The chapters and the chosen transcript of `item`, fetched and parsed,
  and the remote value blocks its time splits point at when the index can
  be asked."
  [feed item]
  (let [transcript-ref (resolve/transcript item)
        block          (resolve/value feed item)]
    (cond-> {}
      (seq (:time-splits block))
      (assoc :remote-values (remote-values block) :index? (some? index-credentials))
      (resolve/chapters-url item)
      (assoc :chapters (companion :chapters (resolve/chapters-url item) chapters/parse-json))

      (resolve/embedded-chapters item)
      (assoc :embedded (resolve/embedded-chapters item))

      (and (not (resolve/chapters-url item))
           (not (resolve/embedded-chapters item))
           (resolve/inferred-chapters item))
      (assoc :inferred (resolve/inferred-chapters item))

      transcript-ref
      (assoc :transcript
             (-> (companion [:transcript (:type transcript-ref)] (:url transcript-ref)
                            #(transcript/parse % (:type transcript-ref)))
                 (assoc :ref transcript-ref))))))

(defn item-page
  "One item of the feed named by `url`, found by the `guid` parameter, with
  its chapters, transcript and value block fetched and rendered."
  [request]
  (with-feed request
    (fn [{:keys [feed] :as loaded}]
      (let [guid (get-in request [:query-params :guid])
            item (some #(when (= guid (feed/item-guid %)) %) (:rss/items feed))]
        (if item
          (html (page/item-page (assoc loaded :item item :companions (companions feed item))))
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
  incomplete, the next visit reads on."
  [interact]
  (let [now      (System/currentTimeMillis)
        fresh?   #(< (- now (:at %)) (* 1000 cache-seconds))
        read-on? #(some #{:comments/incomplete} (map :id (:report (:value %))))]
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
            item      (some #(when (= guid (feed/item-guid %)) %) (:rss/items feed))
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
                                          :results (with-items (search/query index q {:limit 50}) feeds)})))]
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
        opts (cond-> {:limit 10 :country (if (str/blank? country) "us" country)}
               index-credentials         (assoc :credentials index-credentials)
               (not (str/blank? category)) (assoc :category category))]
    (try
      (let [answer (if (str/blank? q)
                     (directory/trending! opts)
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
    (let [head (:block (podping/head-block (podping/call! :head-block {} {})))
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
    (fn [{:keys [feed url]}]
      (let [guid (get-in request [:query-params :guid])
            item (some #(when (= guid (feed/item-guid %)) %) (:rss/items feed))]
        (if item
          (text "application/edn; charset=utf-8"
                (pr-str {:id         (player/episode-id feed url item)
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
  "A file of the compiled browser player, or a note on how to compile it."
  [request]
  (let [path (str (get-in request [:path-params :path]))
        file (io/file player-js-dir path)]
    (if (and (not (str/includes? path "..")) (.isFile file))
      {:status 200 :headers {"Content-Type" "application/javascript; charset=utf-8"} :body file}
      {:status  404
       :headers {"Content-Type" "text/plain"}
       :body    "The browser player is not compiled; run: clojure -M:cljs compile player"})))

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

(defn gpodder-handler
  "The gpodder API over the listener's state in `sync-store`, for AntennaPod,
  Kasts and gPodder: the server is a device of its own, which reads the
  other devices' documents before it answers and writes its own after a
  change, and names the shows and episodes of the feeds the inspector has
  fetched. It lets in any user name and password, since the inspector
  listens on localhost alone."
  [sync-store]
  (let [ctx     (atom nil)
        backend (store/backend sync-store)]
    (gpodder-server/handler
     {:state       (atom (player/state "inspector-gpodder"))
      :authorized? (constantly true)
      :identities  #(gpodder/identities (cached-feeds))
      :step        (fn [state]
                     (let [step (sync/sync! state [backend] (java.util.Date.) {:ctx @ctx})]
                       (reset! ctx (:ctx step))
                       (:state step)))})))

(defn routes
  "The inspector's routes, in Pedestal's set syntax, the WebDAV ones and the
  gpodder API serving `sync-store` (default the folder store over
  `sync-dir`)."
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
     ["/search" :get search-page :route-name :search]
     ["/directory" :get directory-page :route-name :directory]
     ["/podping" :get podping-page :route-name :podping]
     ["/episode.edn" :get episode-edn :route-name :episode-edn]
     ["/queue" :get queue-page :route-name :queue]
     ["/js/*path" :get js-file :route-name :js]
     ["/dav" :any (webdav-server/handler sync-store "/dav/") :route-name :dav-collection]
     ["/dav/*path" :any (webdav-server/handler sync-store "/dav/") :route-name :dav]
     ["/api/2/*path" :any (gpodder-handler sync-store) :route-name :gpodder]
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
  "An interceptor that keeps the first value of a query parameter given more
  than once, so that every handler reads a string."
  (interceptor/interceptor
   {:name  ::single-params
    :enter (fn [context]
             (update-in context [:request :query-params] update-vals #(if (vector? %) (first %) %)))}))

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
       (update :interceptors #(into [(dav/requests "/dav/") unread-bodies] %))
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
