(ns podcast-server.service
  "The self-hosted podcast server: the parts of a podcast app that must run
  on a server, for a client that runs in the browser.

  It serves:

  - /proxy, through which the client fetches feeds and the other files
    that sites don't let a page read
  - /dav/, a folder of sync documents over WebDAV
  - /api/2, the gpodder API, for AntennaPod and other apps of that API
  - the client's own files from a folder, so that the client and the
    server have the same origin

  The server is one of the listener's devices too, so that it keeps the
  listener's feeds current. Without a password, it checks no credentials,
  so reach it only through a private network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.like-minded.store.file :as file]
            [dk.simongray.like-minded.store.webdav.server :as webdav-server]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch.proxy :as proxy]
            [dk.simongray.wary-fetch.secret :as secret]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources]
            [podcast-server.device :as device])
  (:import [java.security MessageDigest]
           [java.util.concurrent Executors])
  (:gen-class))

(def defaults
  "The settings of the server, which the given settings replace one by one:

  - :port, the port that it listens on
  - :host, the address that it listens on. Only this machine reaches
    localhost, e.g. through tailscale serve.
  - :names, the names that a request can address it by besides
    localhost, e.g. its name on the tailnet
  - :password, the password that every request must give, with any user
    name, or nil for none
  - :data-dir, the folder of its data, with the sync folder in it
  - :client-dir, the folder of the client's files, or nil for none"
  {:port       8400
   :host       "localhost"
   :names      #{}
   :password   nil
   :data-dir   "data"
   :client-dir nil})

(def environment
  "The environment variable of each setting. The names are separated by
  commas."
  {:port       "PODCAST_SERVER_PORT"
   :host       "PODCAST_SERVER_HOST"
   :names      "PODCAST_SERVER_NAMES"
   :password   "PODCAST_SERVER_PASSWORD"
   :data-dir   "PODCAST_SERVER_DATA"
   :client-dir "PODCAST_SERVER_CLIENT"})

(defn- setting
  "The setting `k` from the value `v` of its environment variable `var`. A
  password has at least 16 characters, and it's kept as a hidden text."
  [k var v]
  ;; nothing slows down the guesses of an attacker
  (case k
    :port     (or (parse-long v)
                  (throw (ex-info (str var " is not a port number: " v)
                                  {var v})))
    :names    (->> (str/split v #",")
                   (map (comp str/lower-case str/trim))
                   (remove str/blank?)
                   set)
    :password (if (< (count v) 16)
                (throw (ex-info (str var " has fewer than 16 characters") {}))
                (secret/hide v))
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

(defn refused
  "The answer to a request that the server refuses, with `message`."
  [message]
  {:status  403
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    message})

(defn known-names
  "An interceptor that answers only requests to localhost or one of the
  `names`.

  A web page that points a name of its own at this server's address gets
  a 403, since without a password the server checks no credentials (DNS
  rebinding)."
  [names]
  (let [known (into #{"localhost" "127.0.0.1" "[::1]"} names)]
    (interceptor/interceptor
     {:name  ::known-names
      :enter (fn [{:keys [request] :as context}]
               (let [host (some-> (get-in request [:headers "host"])
                                  str/lower-case
                                  (str/replace #":\d+$" ""))]
                 (if (and host (not (known host)))
                   (let [message (str "The server doesn't answer to the name "
                                      host ".")]
                     (assoc context :response (refused message)))
                   context)))})))

;; Tailscale Serve deletes a Tailscale-Funnel-Request that a client sends,
;; and sets it on the requests that Funnel brings, as of October 2026
(def no-funnel
  "An interceptor that answers nothing that came through Tailscale Funnel
  from the public internet, for a server without a password."
  (interceptor/interceptor
   {:name  ::no-funnel
    :enter (fn [{:keys [request] :as context}]
             (if (get-in request [:headers "tailscale-funnel-request"])
               (let [message "The server doesn't answer the public internet."]
                 (assoc context :response (refused message)))
               context))}))

(defn password?
  "Whether `given` is the `password`, compared by their SHA-256 in constant
  time, so that the time of an answer tells nothing about the password.
  Either can be a hidden text."
  [password given]
  (let [digest #(bytes/sha256 (bytes/encode (str (secret/reveal %))))]
    (MessageDigest/isEqual ^bytes (digest password) ^bytes (digest given))))

;; RFC 7617, section 2
(defn login
  "An interceptor that lets in only requests with the `password` in their
  Basic credentials, whatever the user name.

  The gpodder API checks the credentials itself, since its apps log in
  once and then send a cookie."
  [password]
  (let [realm     "Basic realm=\"Podcast server\", charset=\"UTF-8\""
        challenge {:status  401
                   :headers {"WWW-Authenticate" realm
                             "Content-Type"     "text/plain; charset=utf-8"}
                   :body    "The server asks for its password."}]
    (interceptor/interceptor
     {:name  ::login
      :enter (fn [{:keys [request] :as context}]
               (let [authorization (get-in request [:headers "authorization"])
                     given         (:password (ring/credentials authorization))
                     gpodder?      (str/starts-with? (:uri request) "/api/2/")]
                 (if (or gpodder? (and given (password? password given)))
                   context
                   (assoc context :response challenge))))})))

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
                         (= "/dav/" uri)
                         (assoc request :uri "/dav" :path-info "/dav")

                         (str/ends-with? uri "/")
                         (let [index (str uri "index.html")]
                           (assoc request :uri index :path-info index))

                         :else
                         request))))}))

(defn sync-store
  "The store of the sync folder in the :data-dir of `settings`."
  [{:keys [data-dir] :as settings}]
  (file/store (io/file data-dir "sync")))

(defn routes
  "The routes of the proxy, the sync folder `sync-store` and the gpodder
  API, in Pedestal's set syntax. The gpodder API names episodes by the
  feeds in `lib`, an atom of the server's library, and lets in the apps
  that give the `password`, or any app when it's nil."
  [sync-store lib password]
  (let [dav         (webdav-server/handler sync-store "/dav/")
        authorized? (if password
                      (fn [_ given] (password? password given))
                      (constantly true))
        gpodder     (device/gpodder-handler sync-store lib authorized?)]
    #{["/proxy" :any (proxy/handler {:user-agent client/user-agent})
       :route-name ::proxy]
      ["/dav" :any dav :route-name ::dav-collection]
      ["/dav/*path" :any dav :route-name ::dav]
      ["/api/2/*path" :any gpodder :route-name ::gpodder]}))

(defn client-routes
  "The routes of the files in `client-dir`, or none when it's nil. Each
  file has the type of its name, and a 304 when the browser has it."
  [client-dir]
  (if client-dir
    ;; read again on each request, since a build tool rewrites them
    (resources/file-routes {:file-root    (.getPath (io/file client-dir))
                            :cache?       false
                            :interceptors [(ring-middlewares/content-type)
                                           (ring-middlewares/not-modified)]})
    #{}))

(defn connector
  "A Jetty connector of the server with `settings`, over the sync folder
  `sync-store` and the server's library `lib`, an atom; not yet started."
  [{:keys [host port names password client-dir] :as settings} sync-store lib]
  (let [policy {:object-src "'none'" :script-src "'self' 'unsafe-eval'"}]
    (-> (conn/default-connector-map host port)
        (conn/with-interceptors
          [interceptors/log-request
           interceptors/not-found
           ;; scripts from this server only, with eval for the development
           ;; builds of shadow-cljs; art, media and the rest from any host
           (secure-headers/secure-headers
            {:content-security-policy-settings policy})
           (known-names names)
           (if password (login password) no-funnel)
           folders])
        (conn/with-routes (routes sync-store lib password)
          (client-routes client-dir))
        (jetty/create-connector nil))))

(defonce ^{:doc "The running server as its :connector, :library and the
  :steps that keep the library current, or nil when stopped."}
  server
  (atom nil))

(defn start!
  "Start the server with `settings` over the defaults, unless it runs."
  ([]
   (start! {}))
  ([settings]
   (when-not @server
     (let [{:keys [host port data-dir] :as settings} (merge defaults settings)
           sync-store (sync-store settings)
           ;; for the saved library, which can't go in the sync folder,
           ;; where every file is a sync document
           data-store (file/store data-dir)
           lib        (atom (device/restore! (device/make) data-store))
           running    (conn/start! (connector settings sync-store lib))]
       (reset! server {:connector running
                       :library   lib
                       :steps     (device/start! lib sync-store data-store)})
       (println (str "Podcast server on http://" host ":" port "/"))))
   @server))

(defn stop!
  "Stop the server."
  []
  (when-let [{:keys [connector steps]} @server]
    (device/stop! steps)
    (conn/stop! connector)
    (reset! server nil)))

(defn -main
  "Start the server with the settings of the environment, and keep the
  process alive."
  [& _]
  ;; every future of the process runs on a virtual thread, so the requests
  ;; that podcast-clj makes at the same time hold no platform threads while
  ;; they wait
  (set-agent-send-off-executor! (Executors/newVirtualThreadPerTaskExecutor))
  (start! (settings (System/getenv)))
  @(promise))

(comment
  (settings {"PODCAST_SERVER_PORT"   "8401"
             "PODCAST_SERVER_CLIENT" "../my-client/public"})
  (start!)
  (stop!)
  #_.)
