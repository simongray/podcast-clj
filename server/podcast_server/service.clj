(ns podcast-server.service
  "The self-hosted podcast server: the parts of a podcast app that must run
  on a server, for a client that runs in the browser.

  It serves:

  - /proxy, the proxy of dk.simongray.podcast.util.proxy, through which
    the client fetches feeds and the other files that sites don't let a
    page read
  - /dav/, a folder of sync documents over WebDAV, as a store for the
    client's library/sync!
  - the client's own files from a folder, so that the client and the
    server have the same origin

  It checks no credentials. Reach it only through a private network, e.g.
  with tailscale serve, and give it the name that it has there. It answers
  no other names, and nothing that Tailscale Funnel brings from the public
  internet. Start it with `clojure -M:server` and the settings in the
  environment, or from the REPL with `(start!)`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.podcast.sync.file :as file]
            [dk.simongray.podcast.sync.webdav.server :as webdav-server]
            [dk.simongray.podcast.util.proxy :as proxy]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources])
  (:gen-class))

(def defaults
  "The settings of the server, which the given settings replace one by one:

  - :port, the port that it listens on
  - :host, the address that it listens on. Only this machine reaches
    localhost, e.g. through tailscale serve.
  - :names, the names that a request can address it by besides
    localhost, e.g. its name on the tailnet
  - :data-dir, the folder of its data, with the sync folder in it
  - :client-dir, the folder of the client's files, or nil for none"
  {:port       8400
   :host       "localhost"
   :names      #{}
   :data-dir   "data"
   :client-dir nil})

(def environment
  "The environment variable of each setting. The names are separated by
  commas."
  {:port       "PODCAST_SERVER_PORT"
   :host       "PODCAST_SERVER_HOST"
   :names      "PODCAST_SERVER_NAMES"
   :data-dir   "PODCAST_SERVER_DATA"
   :client-dir "PODCAST_SERVER_CLIENT"})

(defn- setting
  "The setting `k` from the value `v` of its environment variable `var`."
  [k var v]
  (case k
    :port  (or (parse-long v) (throw (ex-info (str var " is not a port number: " v) {var v})))
    :names (set (remove str/blank? (map (comp str/lower-case str/trim) (str/split v #","))))
    v))

(defn settings
  "The settings that the environment variables in `env` give, over the
  defaults."
  [env]
  (into defaults
        (for [[k var] environment
              :let [v (get env var)]
              :when (seq v)]
          [k (setting k var v)])))

(def policy
  "The Content-Security-Policy of the answers. Scripts come from this
  server only, with eval for the development builds of shadow-cljs.
  Images, media and connections can come from anywhere, since a podcast
  client shows the art and plays the files of every host."
  {:object-src "'none'" :script-src "'self' 'unsafe-eval'"})

(defn- refused
  "The answer to a request that the server refuses, with `message`."
  [message]
  {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body message})

;; Tailscale Serve deletes a Tailscale-Funnel-Request that a client sends,
;; and sets it on the requests that Funnel brings, as of October 2026
(defn private-only
  "An interceptor that answers only requests to localhost or one of the
  `names`, and none that came through Tailscale Funnel.

  A web page that points a name of its own at this server's address gets
  a 403, since the server checks no credentials (DNS rebinding)."
  [names]
  (let [known (into #{"localhost" "127.0.0.1" "[::1]"} names)]
    (interceptor/interceptor
     {:name  ::private-only
      :enter (fn [{:keys [request] :as context}]
               (let [{:strs [host tailscale-funnel-request]} (:headers request)
                     name (some-> host str/lower-case (str/replace #":\d+$" ""))]
                 (cond
                   tailscale-funnel-request
                   (assoc context :response (refused "The server doesn't answer the public internet."))

                   (and name (not (known name)))
                   (assoc context :response (refused (str "The server doesn't answer to the name " name ".")))

                   :else
                   context)))})))

(def folders
  "An interceptor ahead of the router for the paths that end in a slash.

  The sync folder goes to the route of /dav, since the router doesn't
  match a trailing slash. Any other folder goes to its index.html, so
  that the type of the answer comes from the name of the file."
  (interceptor/interceptor
   {:name  ::folders
    :enter (fn [context]
             (update context :request
                     (fn [{:keys [uri] :as request}]
                       (cond
                         (= "/dav/" uri)          (assoc request :uri "/dav" :path-info "/dav")
                         (str/ends-with? uri "/") (let [index (str uri "index.html")]
                                                    (assoc request :uri index :path-info index))
                         :else                    request))))}))

(defn routes
  "The routes of the proxy and the sync folder in the `data-dir` of the
  settings, in Pedestal's set syntax."
  [{:keys [data-dir] :as settings}]
  (let [dav (webdav-server/handler (file/store (io/file data-dir "sync")) "/dav/")]
    #{["/proxy" :any (proxy/handler) :route-name ::proxy]
      ["/dav" :any dav :route-name ::dav-collection]
      ["/dav/*path" :any dav :route-name ::dav]}))

(defn client-routes
  "The routes of the files in `client-dir`, or none when it's nil. Each
  file has the type of its name, and a 304 when the browser has it."
  [client-dir]
  (if client-dir
    ;; read again on each request, since a build tool rewrites them
    (resources/file-routes {:file-root    (.getPath (io/file client-dir))
                            :cache?       false
                            :interceptors [(ring-middlewares/content-type) (ring-middlewares/not-modified)]})
    #{}))

(defn connector
  "A Jetty connector of the server with `settings`, not yet started."
  [{:keys [host port names client-dir] :as settings}]
  (-> (conn/default-connector-map host port)
      (conn/with-interceptors [interceptors/log-request
                               interceptors/not-found
                               (secure-headers/secure-headers {:content-security-policy-settings policy})
                               (private-only names)
                               folders])
      (conn/with-routes (routes settings) (client-routes client-dir))
      (jetty/create-connector nil)))

(defonce ^{:doc "The running connector, nil when stopped."}
  server
  (atom nil))

(defn start!
  "Start the server with `settings` over the defaults, unless it runs."
  ([]
   (start! {}))
  ([settings]
   (when-not @server
     (let [{:keys [host port] :as settings} (merge defaults settings)]
       (reset! server (conn/start! (connector settings)))
       (println (str "Podcast server on http://" host ":" port "/"))))
   @server))

(defn stop!
  "Stop the server."
  []
  (when-let [s @server]
    (conn/stop! s)
    (reset! server nil)))

(defn -main
  "Start the server with the settings of the environment, and keep the
  process alive."
  [& _]
  (start! (settings (System/getenv)))
  @(promise))

(comment
  (settings {"PODCAST_SERVER_PORT" "8401" "PODCAST_SERVER_CLIENT" "../my-client/public"})
  (start!)
  (stop!)
  #_.)
