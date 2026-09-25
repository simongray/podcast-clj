(ns dk.simongray.podcast.web.service
  "The reference web app: a feed inspector on Pedestal that uses nothing but
  the public API of the library. Start it with `clojure -X:web` or from the
  REPL with `(start!)`, then open http://localhost:8300/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.chapters :as chapters]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.index :as index]
            [dk.simongray.podcast.player :as player]
            [dk.simongray.podcast.resolve :as resolve]
            [dk.simongray.podcast.transcript :as transcript]
            [dk.simongray.podcast.web.page :as page]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.secure-headers :as secure-headers]
            [replicant.string :as replicant])
  (:gen-class))

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

;;;; Fetching, with a small cache so that the views of one feed share a parse

(defonce ^{:doc "Fetched bodies by URL, with the time they were fetched."}
  cache
  (atom {}))

(def cache-seconds
  "How long a fetched body is reused before it is fetched again."
  300)

(defn- fetch
  "The body of the feed at `url`, from the cache when it is fresh."
  [url]
  (let [now (System/currentTimeMillis)
        hit (get @cache url)]
    (if (and hit (< (- now (:at hit)) (* 1000 cache-seconds)))
      hit
      (let [{:keys [status body] :as response} (http/fetch! url)
            entry {:at now :status status :body body :url (:url response)}]
        (when-not (and (= 200 status) body)
          (throw (ex-info (str "HTTP " status " for " url) {:status status})))
        (swap! cache assoc url entry)
        entry))))

(defn- load-feed
  "The parsed feed at `url`, or the corpus file when `url` names one, as
  `{:url :feed :report :status}`."
  [url]
  (if-let [[_ name] (re-matches #"corpus/([\w.-]+)" url)]
    (let [feed (podcast/parse (slurp (io/file corpus-dir (str name ".xml"))))]
      {:url url :feed feed :report (podcast/report feed)})
    (let [{:keys [body status]} (fetch url)
          feed (podcast/parse body)]
      {:url url :feed feed :report (podcast/report feed) :status status})))

;;;; Responses

(defn- html
  [hiccup]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "<!DOCTYPE html>\n" (replicant/render hiccup))})

(defn- text
  [content-type s]
  {:status 200 :headers {"Content-Type" content-type} :body s})

(defn- with-feed
  "Call `f` with the loaded feed for the `url` query parameter of `request`,
  rendering an error page when loading fails."
  [request f]
  (let [url (get-in request [:query-params :url])]
    (if (str/blank? url)
      {:status 302 :headers {"Location" "/"}}
      (try
        (f (load-feed url))
        (catch Exception e
          (-> (html (page/error-page url (or (ex-message e) (str e))))
              (assoc :status 502)))))))

(defn home
  "The landing page: the URL form and the corpus."
  [_]
  (html (page/home (corpus))))

(defn feed-page
  "The inspection of the feed named by the `url` query parameter, validated
  against the `profile` parameter when given."
  [request]
  (with-feed request
    (fn [loaded]
      (let [profile (some-> (get-in request [:query-params :profile]) not-empty keyword)]
        (html (page/feed-page (assoc loaded :profile profile)))))))

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

;;;; One item and its companion files

(defn- companion
  "Fetch the companion file at `url` and parse its body with `parse`, as
  `{:url :status :parsed}` or `{:url :status :error}`; never throws."
  [url parse]
  (try
    (let [{:keys [status body]} (fetch url)]
      {:url url :status status :parsed (parse body)})
    (catch Exception e
      {:url url :status (:status (ex-data e)) :error (or (ex-message e) (str e))})))

(def index-credentials
  "Podcast Index API credentials from the environment, when the inspector
  is to resolve remote value splits: PODCASTINDEX_API_KEY and
  PODCASTINDEX_API_SECRET."
  (let [key    (System/getenv "PODCASTINDEX_API_KEY")
        secret (System/getenv "PODCASTINDEX_API_SECRET")]
    (when (and (seq key) (seq secret))
      {:key key :secret secret :user-agent "dk.simongray/podcast inspector"})))

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
      (assoc :chapters (companion (resolve/chapters-url item) chapters/parse-json))

      (resolve/embedded-chapters item)
      (assoc :embedded (resolve/embedded-chapters item))

      transcript-ref
      (assoc :transcript
             (-> (companion (:url transcript-ref) #(transcript/parse % (:type transcript-ref)))
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
          (-> (html (page/error-page (:url loaded) (str "No item with guid " guid)))
              (assoc :status 404)))))))

;;;; The browser player: a shell page, the episode data it asks for, and its script

(defn queue-page
  "The shell the browser player mounts into; the player itself is
  dk.simongray.podcast.web.player, compiled by `clojure -M:cljs compile
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

(defn routes
  "The inspector's routes, in Pedestal's set syntax."
  []
  #{["/" :get home :route-name :home]
    ["/feed" :get feed-page :route-name :feed]
    ["/feed.edn" :get feed-edn :route-name :feed-edn]
    ["/feed.xml" :get feed-xml :route-name :feed-xml]
    ["/item" :get item-page :route-name :item]
    ["/episode.edn" :get episode-edn :route-name :episode-edn]
    ["/queue" :get queue-page :route-name :queue]
    ["/js/*path" :get js-file :route-name :js]
    ["/corpus/:name" :get corpus-page :route-name :corpus]})

;;;; Lifecycle

(defonce ^{:doc "The running connector, nil when stopped."}
  server
  (atom nil))

(defn connector
  "A Jetty connector serving the inspector on `port`, not yet started; tests
  drive its interceptor chain directly."
  [port]
  (-> (conn/default-connector-map port)
      ;; Pedestal's default policy forbids every script tag; the browser
      ;; player is served from this origin, and its development build loads
      ;; its modules through eval.
      (conn/with-default-interceptors
        :secure-headers {:content-security-policy-settings
                         (secure-headers/content-security-policy-header
                          {:object-src "'none'" :script-src "'self' 'unsafe-eval'"})})
      (conn/with-routes (routes))
      (jetty/create-connector nil)))

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
