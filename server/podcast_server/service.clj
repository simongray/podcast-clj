(ns podcast-server.service
  "The self-hosted podcast server: the parts of a podcast app that must run
  on a server, for a client that runs in the browser.

  It serves:

  - /proxy, through which the client fetches feeds and the other files
    that sites don't let a page read
  - /media, through which the client saves the media files of the
    episodes of the listener's feeds
  - /dav/, a folder of sync documents over WebDAV
  - /api/2, the gpodder API, for AntennaPod and other apps of that API
  - the client's own files from a folder, so that the client and the
    server have the same origin

  The server is one of the listener's devices too, so that it keeps the
  listener's feeds current. Without a password, it checks no credentials,
  so it then starts only on localhost or on a private network, e.g. a
  tailnet."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.like-minded.store.webdav.server :as webdav-server]
            [dk.simongray.podcast.analytics.op3 :as op3]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch.proxy :as proxy]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources]
            [podcast-server.device :as device])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.util.concurrent Executors Semaphore TimeUnit])
  (:gen-class))

(def defaults
  "The settings of the server, which the given settings replace one by one:

  - :port, the port that it listens on
  - :host, the address that it listens on. Only this machine reaches
    localhost, e.g. through tailscale serve.
  - :names, the names that a request can address it by besides
    localhost, e.g. its name on the tailnet, with or without a port
  - :password, the password that every request must give, with any user
    name, or nil for none
  - :private-network?, whether only a private network reaches the server,
    e.g. through tailscale serve, so that it can do without a password
    while it listens beyond localhost, e.g. in Docker
  - :data-dir, the folder of its data, with the sync folder in it
  - :client-dir, the folder of the client's files, or nil for none
  - :max-sync-documents and :max-sync-bytes, the most documents that the
    sync folder holds and the most bytes that they add up to
  - :podcastindex-key and :podcastindex-secret, the credentials of an
    account of the Podcast Index, with which the proxy signs the client's
    requests to it, or nil
  - :op3-token, the token of an API key of OP3, which the proxy adds to
    the client's requests to its API, or nil"
  {:port                8400
   :host                "localhost"
   :names               #{}
   :password            nil
   :private-network?    false
   :data-dir            "data"
   :client-dir          nil
   :max-sync-documents  1000
   :max-sync-bytes      (* 512 1024 1024)
   :podcastindex-key    nil
   :podcastindex-secret nil
   :op3-token           nil})

(def environment
  "The environment variable of each setting. The names are separated by
  commas, and whether the network is private is true or false."
  {:port                "PODCAST_SERVER_PORT"
   :host                "PODCAST_SERVER_HOST"
   :names               "PODCAST_SERVER_NAMES"
   :password            "PODCAST_SERVER_PASSWORD"
   :private-network?    "PODCAST_SERVER_PRIVATE_NETWORK"
   :data-dir            "PODCAST_SERVER_DATA"
   :client-dir          "PODCAST_SERVER_CLIENT"
   :max-sync-documents  "PODCAST_SERVER_MAX_SYNC_DOCUMENTS"
   :max-sync-bytes      "PODCAST_SERVER_MAX_SYNC_BYTES"
   :podcastindex-key    "PODCAST_SERVER_PODCASTINDEX_KEY"
   :podcastindex-secret "PODCAST_SERVER_PODCASTINDEX_SECRET"
   :op3-token           "PODCAST_SERVER_OP3_TOKEN"})

(defn- setting
  "The setting `k` from the value `v` of its environment variable `var`. A
  password has at least 16 characters, and it's kept as a hidden text, as
  the secrets of the Podcast Index and OP3 are."
  [k var v]
  ;; long, since the waits after wrong guesses slow an attacker, but only
  ;; down to a guess a minute
  (case k
    :port             (or (parse-long v)
                          (throw (ex-info (str var " is not a port number: " v)
                                          {var v})))
    :names            (->> (str/split v #",")
                           (map (comp str/lower-case str/trim))
                           (remove str/blank?)
                           set)
    :password         (if (< (count v) 16)
                        (throw (ex-info (str var " has fewer than 16 "
                                             "characters")
                                        {}))
                        (secret/hide v))
    :private-network? (case (str/lower-case v)
                        "true"  true
                        "false" false
                        (throw (ex-info (str var " is neither true nor "
                                             "false: " v)
                                        {var v})))

    (:max-sync-documents :max-sync-bytes)
    (or (some-> (re-matches #"\d{1,15}" v) parse-long)
        (throw (ex-info (str var " is not a number: " v) {var v})))

    (:podcastindex-secret :op3-token)
    (secret/hide v)

    v))

(defn settings
  "The settings that the environment variables in `env` give, over the
  defaults. The key and the secret of the Podcast Index come together."
  [env]
  (let [given (into defaults
                    (for [[k var] environment
                          :let [v (get env var)]
                          :when (seq v)]
                      [k (setting k var v)]))]
    (when-not (= (some? (:podcastindex-key given))
                 (some? (:podcastindex-secret given)))
      (throw (ex-info (str "PODCAST_SERVER_PODCASTINDEX_KEY and "
                           "PODCAST_SERVER_PODCASTINDEX_SECRET come together")
                      {})))
    given))

(defn refused
  "The answer to a request that the server refuses, with `message`."
  [message]
  {:status  403
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    message})

(def hidden-credentials
  "An interceptor that hides the Authorization header of a request, so that
  no log shows the password, e.g. Pedestal's log of an uncaught exception,
  which holds the whole request."
  (interceptor/interceptor
   {:name  ::hidden-credentials
    :enter (fn [context]
             (if (get-in context [:request :headers "authorization"])
               (update-in context [:request :headers "authorization"]
                          secret/hide)
               context))}))

(defn- without-port
  "The `host` of a Host header or of a server name, without its port."
  [host]
  (str/replace host #":\d+$" ""))

(defn known-names
  "An interceptor that answers only requests to localhost or one of the
  `names`, with or without a port.

  A web page that points a name of its own at this server's address gets
  a 403, since without a password the server checks no credentials (DNS
  rebinding). The check is against web pages: a client that isn't a
  browser can send any name, or none, as HTTP/1.0 allows."
  [names]
  (let [known (into #{"localhost" "127.0.0.1" "[::1]"}
                    (map without-port)
                    names)]
    (interceptor/interceptor
     {:name  ::known-names
      :enter (fn [{:keys [request] :as context}]
               (let [host (some-> (get-in request [:headers "host"])
                                  str/lower-case
                                  without-port)]
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

;; Fetch Metadata Request Headers, W3C Working Draft of 2021-03-29
(def same-origin-writes
  "An interceptor that refuses a write from a page of another origin, e.g.
  a form of another site that posts to the gpodder API with the
  credentials that the browser keeps. Apps that aren't browsers send no
  Sec-Fetch-Site, so they write as before."
  (interceptor/interceptor
   {:name  ::same-origin-writes
    :enter (fn [{{:keys [headers request-method]} :request :as context}]
             (let [site (get headers "sec-fetch-site")]
               (if (and (#{"cross-site" "same-site"} site)
                        (not (#{:get :head :options} request-method)))
                 (let [message "The server takes no writes from other sites."]
                   (assoc context :response (refused message)))
                 context)))}))

;; RFC 6797, sections 6.1 and 7.2: over https alone, and for this name alone
(def transport-security
  "An interceptor that tells a browser to come back over https alone for a
  year, on each answer to a request that came over https, here or through
  a proxy in front. It goes on no answer over plain HTTP, and it covers no
  name below the server's own, which a proxy in front can add."
  (interceptor/interceptor
   {:name  ::transport-security
    :leave (fn [{:keys [request response] :as context}]
             (cond-> context
               (and response (ring/https? request))
               (assoc-in [:response :headers "Strict-Transport-Security"]
                         "max-age=31536000")))}))

(defn password?
  "Whether `given` is the `password`, compared by their SHA-256 in constant
  time, so that the time of an answer tells nothing about the password.
  Either can be a hidden text."
  [password given]
  (let [digest #(bytes/sha256 (bytes/encode (str (secret/reveal %))))]
    (MessageDigest/isEqual ^bytes (digest password) ^bytes (digest given))))

(defn- client-address
  "The address of the client of `request`: the one that the proxy in front
  names in X-Forwarded-For when the request came from this machine, e.g.
  through Caddy or tailscale serve, or else the one that connected. An
  IPv6 address counts by its first 64 bits, since one client can hold all
  the addresses of a /64."
  [{:keys [remote-addr headers] :as request}]
  (let [from      (url/ip-address (str remote-addr))
        local?    (or (= 127 (first from))
                      (= (conj (vec (repeat 15 0)) 1) from))
        forwarded (some-> (get headers "x-forwarded-for")
                          (str/split #",")
                          last
                          str/trim)
        address   (if (and local? forwarded) forwarded remote-addr)
        b         (url/ip-address (str address))]
    (if (= 16 (count b))
      (subvec b 0 8)
      (or b address))))

(defn- waits
  "The `book` of the clients that guessed wrong, by address, with a wrong
  guess of the client `k` at the instant `now-ms` added.

  The wait is a second after the first wrong guess and twice as long
  after each one more, up to a minute. A client that hasn't guessed for
  ten minutes leaves the book."
  [book k now-ms]
  (let [n      (inc (get-in book [k :count] 0))
        wait   (min 60 (bit-shift-left 1 (min 6 (dec n))))
        recent (fn [[_ {:keys [until]}]] (< (- now-ms until) 600000))]
    (-> (into {} (filter recent) book)
        (assoc k {:count n :until (+ now-ms (* 1000 wait))}))))

(defn wrong-guesses
  "An interceptor that makes a client wait after each wrong password,
  judged by the clock `now-ms-fn`, a function of no arguments that gives
  the milliseconds.

  A client gets a 429 with a Retry-After until its wait is over, whatever
  it sends, so that guesses come at most once a minute. The wait is short,
  so that a household behind one address with someone guessing among them
  isn't kept out for long. The right password ends the waits of its
  client."
  [now-ms-fn]
  (let [book (atom {})]
    (interceptor/interceptor
     {:name  ::wrong-guesses
      :enter (fn [{:keys [request] :as context}]
               (let [until (get-in @book [(client-address request) :until] 0)
                     wait  (- until (now-ms-fn))]
                 (if (pos? wait)
                   (assoc context :response
                          {:status  429
                           :headers {"Retry-After"  (str (quot (+ wait 999)
                                                               1000))
                                     "Content-Type" "text/plain; charset=utf-8"}
                           :body    "Wait before you try the password again."})
                   context)))
      :leave (fn [{:keys [request response] :as context}]
               (let [given? (get-in request [:headers "authorization"])
                     status (:status response)
                     k      (client-address request)]
                 (cond
                   (and given? (= 401 status))
                   (swap! book waits k (now-ms-fn))

                   (and given? status (< status 400))
                   (swap! book dissoc k))
                 context))})))

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

(defn sync-dir
  "The sync folder in the :data-dir of `settings`."
  [{:keys [data-dir] :as settings}]
  (io/file data-dir "sync"))

(defn sync-store
  "The store of the sync folder of `settings`."
  [settings]
  (folder/store (sync-dir settings)))

(def max-document-bytes
  "The most bytes that a document of the sync folder can hold."
  (* 32 1024 1024))

;; RFC 4918, section 11.5
(defn within-quota
  "The WebDAV `handler` of the sync folder `dir`, which refuses a put that
  would take the folder past `max-documents` or `max-bytes` with a 507.

  A put without a Content-Length counts as the most that a document can
  hold, so that leaving it out passes no quota."
  [dir max-documents max-bytes handler]
  (fn [{:keys [request-method uri headers] :as request}]
    (let [doc-name (url/percent-decode (str/replace uri #"^/dav/?" ""))]
      (if (or (not= :put request-method) (str/blank? doc-name))
        (handler request)
        (let [document? #(and (.isFile ^File %)
                              (not (str/starts-with? (.getName ^File %) ".")))
              files     (filter document? (.listFiles (io/file dir)))
              others    (remove #(= doc-name (.getName ^File %)) files)
              size      (or (some->> (get headers "content-length")
                                     (re-matches #"\d{1,15}")
                                     parse-long)
                            max-document-bytes)
              total     (reduce + size (map #(.length ^File %) others))
              full      #(assoc (refused %) :status 507)]
          (cond
            (and (= (count others) (count files))
                 (<= max-documents (count files)))
            (full (str "The sync folder holds no more than " max-documents
                       " documents."))

            (< max-bytes total)
            (full (str "The sync folder holds no more than " max-bytes
                       " bytes."))

            :else
            (handler request)))))))

(defn at-most
  "The Ring `handler` answering at most `n` requests at once, e.g. so that
  the proxy holds no more than `n` bodies in memory. A request that waits
  `seconds` for its turn gets a 503 with a Retry-After."
  [n seconds handler]
  (let [turns (Semaphore. n true)]
    (fn [request]
      (if (.tryAcquire turns (long seconds) TimeUnit/SECONDS)
        (try
          (handler request)
          (finally
            (.release turns)))
        {:status  503
         :headers {"Retry-After"  "5"
                   "Content-Type" "text/plain; charset=utf-8"}
         :body    "The server is busy. Try again in a few seconds."}))))

(defn proxy-send
  "The :send of the proxy, which signs the requests to the Podcast Index
  and to OP3 with the credentials of `settings`, where they give them."
  [{:keys [podcastindex-key podcastindex-secret op3-token] :as settings}]
  (let [index {:key podcastindex-key :secret podcastindex-secret}]
    (cond-> client/send!
      op3-token
      (as-> send (op3/sender op3-token {:send send}))

      podcastindex-key
      (as-> send (podcastindex/sender index {:send send})))))

(defn media-urls
  "The URLs of the media files of the episodes of the feeds in `library`,
  as resolve/enclosures gives them."
  [library]
  (into #{}
        (for [[_ {:keys [feed]}] (:feeds library)
              item               (:rss/items feed)
              {:keys [url]}      (resolve/enclosures item)
              :when              url]
          url)))

(defn media-handler
  "A Ring handler that streams the media file at the url parameter as
  proxy/stream-handler does, when it's a file of an episode of the feeds in
  `lib`, an atom of the server's library, and refuses any other URL.

  So a page can save an episode, which a host rarely lets a page read,
  while the server streams nothing else for whoever passes its login. The
  `opts` are those of proxy/stream-handler."
  ([lib]
   (media-handler lib {}))
  ([lib opts]
   (let [stream (proxy/stream-handler
                 (merge {:user-agent client/user-agent} opts))
         cache  (atom nil)
         known  (fn [library]
                  (let [[feeds urls] @cache]
                    (if (identical? feeds (:feeds library))
                      urls
                      (second (reset! cache [(:feeds library)
                                             (media-urls library)])))))]
     (fn [request]
       (let [url (get (url/query-params (:query-string request)) "url")]
         (if (contains? (known @lib) url)
           (stream request)
           (refused (str "The server streams the media files of the "
                         "episodes of its feeds alone."))))))))

(defn routes
  "The routes of the proxy, the media files, the sync folder `sync-store`
  and the gpodder API, in Pedestal's set syntax. The gpodder API names
  episodes by the feeds in `lib`, an atom of the server's library, and
  the media files are those of its episodes.

  The :password of `settings` lets in the apps of the gpodder API, or any
  app when it's nil, and the sync folder holds no more than their
  :max-sync-documents and :max-sync-bytes. The proxy fetches four files at
  once, of 32 MB at most, so that a heap of 256 MB holds them, and signs
  with proxy-send. The media files pass through as they arrive."
  [sync-store lib {:keys [password max-sync-documents max-sync-bytes]
                   :as   settings}]
  (let [dav         (within-quota (sync-dir settings)
                                  max-sync-documents
                                  max-sync-bytes
                                  (webdav-server/handler
                                   sync-store "/dav/"
                                   {:max-bytes max-document-bytes}))
        authorized? (if password
                      (fn [_ given] (password? password given))
                      (constantly true))
        gpodder     (device/gpodder-handler sync-store lib authorized?)
        proxy       (at-most 4 30 (proxy/handler {:user-agent client/user-agent
                                                  :send (proxy-send settings)}))]
    #{["/proxy" :any proxy :route-name ::proxy]
      ["/media" :any (media-handler lib) :route-name ::media]
      ["/dav" :any dav :route-name ::dav-collection]
      ["/dav/*path" :any dav :route-name ::dav]
      ["/api/2/*path" :any gpodder :route-name ::gpodder]}))

(def no-dotfiles
  "An interceptor that answers 404 for a file or folder whose name starts
  with a dot, e.g. a .env or a .git that a build left in the client's
  folder."
  (interceptor/interceptor
   {:name  ::no-dotfiles
    :enter (fn [{:keys [request] :as context}]
             (if (re-find #"(?i)/(\.|%2e)" (:uri request))
               (assoc context :response {:status  404
                                         :headers {}
                                         :body    "Not Found"})
               context))}))

(defn client-routes
  "The routes of the files in `client-dir`, or none when it's nil. Each
  file has the type of its name, and a 304 when the browser has it. A
  dotfile isn't served."
  [client-dir]
  (if client-dir
    ;; read again on each request, since a build tool rewrites them
    (resources/file-routes {:file-root    (.getPath (io/file client-dir))
                            :cache?       false
                            :interceptors [no-dotfiles
                                           (ring-middlewares/content-type)
                                           (ring-middlewares/not-modified)]})
    #{}))

(defn connector
  "A Jetty connector of the server with `settings`, over the sync folder
  `sync-store` and the server's library `lib`, an atom; not yet started."
  [{:keys [host port names password client-dir] :as settings} sync-store lib]
  (let [policy {:object-src "'none'" :script-src "'self' 'unsafe-eval'"}
        guard  (if password
                 [(wrong-guesses #(System/currentTimeMillis)) (login password)]
                 [no-funnel])]
    (-> (conn/default-connector-map host port)
        (conn/with-interceptors
          (-> [hidden-credentials
               interceptors/log-request
               interceptors/not-found
               ;; scripts from this server only, with eval for the
               ;; development builds of shadow-cljs; art, media and the rest
               ;; from any host; and no HSTS on plain HTTP
               (secure-headers/secure-headers
                {:content-security-policy-settings policy
                 :hsts-settings                    nil})
               transport-security
               (known-names names)
               same-origin-writes]
              (into guard)
              (conj folders)))
        (conn/with-routes (routes sync-store lib settings)
          (client-routes client-dir))
        (jetty/create-connector nil))))

(defonce ^{:doc "The running server as its :connector, :library and the
  :steps that keep the library current, or nil when stopped."}
  server
  (atom nil))

(defn open?
  "Whether a server with `settings` would let in anyone who reaches it: it
  has no password and listens beyond localhost, e.g. behind Caddy, and not
  on a private network."
  [{:keys [host password private-network?] :as settings}]
  (not (or password
           private-network?
           (#{"localhost" "127.0.0.1" "::1"} host))))

(defn start!
  "Start the server with `settings` over the defaults, unless it runs. A
  server without a password starts only on localhost or on a private
  network."
  ([]
   (start! {}))
  ([settings]
   (let [{:keys [host port data-dir] :as settings} (merge defaults settings)]
     (when (open? settings)
       (throw (ex-info (str "The server needs a password to listen on " host
                            ", e.g. from PODCAST_SERVER_PASSWORD, unless "
                            "only a private network reaches it, as "
                            "PODCAST_SERVER_PRIVATE_NETWORK=true says")
                       {:host host})))
     (when-not @server
       (let [sync-store (sync-store settings)
             ;; for the saved library, which can't go in the sync folder,
             ;; where every file is a sync document
             data-store (folder/store data-dir)
             lib        (atom (device/restore! (device/make) data-store))
             running    (conn/start! (connector settings sync-store lib))]
         (reset! server {:connector running
                         :library   lib
                         :steps     (device/start! lib sync-store data-store)})
         (println (str "Podcast server on http://" host ":" port "/"))))
     @server)))

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
