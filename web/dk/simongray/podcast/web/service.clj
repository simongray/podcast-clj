(ns dk.simongray.podcast.web.service
  "The reference web app: a feed inspector on Pedestal that uses nothing but
  the public API of the library. Start it with `clojure -X:web` or from the
  REPL with `(start!)`, then open http://localhost:8300/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.web.page :as page]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [replicant.string :as replicant])
  (:gen-class))

(def corpus-dir
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

(defonce cache
  (atom {}))

(def cache-seconds
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
  [_]
  (html (page/home (corpus))))

(defn feed-page
  [request]
  (with-feed request
    (fn [loaded]
      (let [profile (some-> (get-in request [:query-params :profile]) not-empty keyword)]
        (html (page/feed-page (assoc loaded :profile profile)))))))

(defn feed-edn
  [request]
  (with-feed request
    (fn [{:keys [feed]}]
      (text "text/plain; charset=utf-8" (with-out-str (pprint/pprint feed))))))

(defn feed-xml
  [request]
  (with-feed request
    (fn [{:keys [feed]}]
      (text "application/rss+xml; charset=utf-8" (podcast/emit feed)))))

(defn corpus-page
  [request]
  (feed-page (assoc-in request [:query-params :url]
                       (str "corpus/" (get-in request [:path-params :name])))))

(defn routes
  []
  #{["/" :get home :route-name :home]
    ["/feed" :get feed-page :route-name :feed]
    ["/feed.edn" :get feed-edn :route-name :feed-edn]
    ["/feed.xml" :get feed-xml :route-name :feed-xml]
    ["/corpus/:name" :get corpus-page :route-name :corpus]})

;;;; Lifecycle

(defonce server
  (atom nil))

(defn connector
  "A Jetty connector serving the inspector on `port`."
  [port]
  (-> (conn/default-connector-map port)
      (conn/with-default-interceptors)
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
  [& _]
  (start!)
  @(promise))

(comment
  (start!)
  (stop!)
  #_.)
