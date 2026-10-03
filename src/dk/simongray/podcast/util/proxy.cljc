(ns dk.simongray.podcast.util.proxy
  "A proxy on your own server for the requests of a web page, since most
  servers don't let a page of another site read their answers.

  On the server, handler is a Ring handler for a route of your own, e.g.
  /proxy?url=https://example.org/feed.xml. In the browser, sender gives a
  function to use as :send, which sends each request through that route
  and gives the answer as http/call! would give it:

      ;; on the server, e.g. as a route of Pedestal
      [\"/proxy\" :any (proxy/handler) :route-name ::proxy]

      ;; in the browser
      (podcast/fetch! url {:send (proxy/sender \"/proxy\")})

  The handler is built from upstream-request, ring-response, error-response
  and refusal, and sender from proxied and unproxied, so you can build
  your own from the same parts, e.g. to add a cache or a signature.

  The proxy fetches any public URL for whoever can reach it, so put it
  behind your login. Its answers come from other sites but have the
  origin of yours, so it refuses to be opened as a page, and no answer
  runs scripts when a browser opens it anyway."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]))

(def defaults
  "The options of handler, which its own options replace one by one:

  - :allow?, the URLs that it fetches, those on the public internet
  - :send, the function that sends a request, http/call!
  - :forward, the request headers that go on to the other site
  - :expose, the response headers that come back to the page
  - :user-agent, the User-Agent of each request
  - :max-bytes, the most that a body can hold, 32 MB, since the proxy
    holds each body in memory

  Each request also has the :timeout-seconds of the options, or else that
  of http/call!. The page's own cookies and credentials never go on,
  unless :forward names them."
  {:allow?     http/public?
   :send       http/call!
   :forward    #{"accept" "if-modified-since" "if-none-match" "range"}
   :expose     #{"age" "cache-control" "content-range" "content-type" "etag" "expires" "last-modified" "link"
                 "retry-after"}
   :user-agent http/user-agent
   :max-bytes  (* 32 1024 1024)})

(def ^:no-doc via
  "The Via of each request that the proxy sends, as RFC 9110, section
  7.6.3, has it, so that it can tell a request of its own and refuse to
  fetch itself."
  "1.1 podcast-clj")

(def ^:no-doc permanent-header
  "The response header that names the URL that redirects moved the
  request to for good, since HTTP has no header for it. The name has no
  X-, as RFC 6648 asks, and is one of podcast-clj's own."
  "Podcast-Clj-Permanent-Location")

;; RFC 9209, section 2.3
(def ^:no-doc errors
  "The error of the Proxy-Status of RFC 9209, and the status of the
  answer, for each type of exception of http/call! that the proxy answers
  with. Any other failure is ::failed."
  {::http/refused   ["http_request_denied" 403]
   ::http/timeout   ["http_response_timeout" 504]
   ::http/too-large ["http_response_body_size" 502]
   ::failed         ["destination_unavailable" 503]})

(def ^:no-doc safety
  "The headers of every answer of the proxy, which comes from another site
  but has the origin of yours: a browser that opens it runs no scripts,
  guesses no other type, and lets no other site embed it."
  {"Content-Security-Policy"      "default-src 'none'; sandbox"
   "X-Content-Type-Options"       "nosniff"
   "Cross-Origin-Resource-Policy" "same-origin"})

(defn- target
  "The parameters of the `ring-request` by name: the url to fetch, and as,
  which is bytes for a body of bytes."
  [ring-request]
  (http/query-params (:query-string ring-request)))

(defn- header-names
  "The headers of the `ring-request` by their names in lower case, since
  not every adapter gives them so."
  [ring-request]
  (update-keys (:headers ring-request) str/lower-case))

;; RFC 9209, section 2, with the details as a string of RFC 8941, section 3.3.3
(defn- answer
  "The answer of the proxy with `status` when it doesn't send the request:
  the `error` of RFC 9209, and the `details` as text."
  [status error details]
  {:status  status
   :headers (assoc safety
                   "Content-Type" "text/plain; charset=utf-8"
                   "Proxy-Status" (str "podcast-clj; error=" error "; details=\""
                                       (-> details
                                           (str/replace #"[\\\"]" #(str "\\" %))
                                           (str/replace #"[^\x20-\x7E]" "?"))
                                       "\""))
   :body    (bytes/encode details)})

;; Fetch Metadata Request Headers, W3C Working Draft of 2021-03-29
(defn refusal
  "The Ring response that refuses `ring-request`, or nil when the proxy
  sends it on. The proxy refuses:

  - a method other than GET and HEAD
  - a request that it sent itself, as its Via tells
  - a page that opens it as a document, or a page of another site, as the
    Sec-Fetch-Mode and Sec-Fetch-Site of the browser tell
  - a request without an http or https URL as its url parameter"
  [{:keys [request-method] :as ring-request}]
  (let [headers (header-names ring-request)]
    (cond
      (not (#{:get :head} request-method))
      (assoc-in (answer 405 "http_request_denied" "The proxy answers GET and HEAD.") [:headers "Allow"] "GET, HEAD")

      (str/includes? (str (get headers "via")) via)
      (answer 502 "proxy_loop_detected" "The proxy doesn't fetch itself.")

      (or (= "navigate" (get headers "sec-fetch-mode")) (= "cross-site" (get headers "sec-fetch-site")))
      (answer 403 "http_request_denied" "The proxy answers the requests of the pages of its own site, and isn't a page itself.")

      (not (http/web-url? (get (target ring-request) "url")))
      (answer 400 "http_request_error" "The url parameter names no http or https URL."))))

(defn upstream-request
  "The request that the proxy sends for `ring-request`, with `opts` as
  defaults has them, or nil when the url parameter names no http or https
  URL.

  The request has the headers of the :forward of `opts`, the Via of the
  proxy, and the range of the Range header, so that http/call! reads no
  more than it asks for. Its body is bytes when the as parameter is bytes,
  and else text."
  [{:keys [request-method] :as ring-request} {:keys [forward user-agent] :as opts}]
  (let [{:strs [url as]} (target ring-request)
        headers          (header-names ring-request)
        [asked :as asks] (when (contains? forward "range") (http/byte-ranges (get headers "range")))]
    (when (http/web-url? url)
      (cond-> (assoc (select-keys opts [:allow? :timeout-seconds :max-bytes])
                     :method  request-method
                     :url     url
                     :headers (cond-> (assoc (select-keys headers (disj forward "range")) "Via" via)
                                user-agent (assoc "User-Agent" user-agent)))
        (= "bytes" as)     (assoc :as :bytes)
        (= 1 (count asks)) (assoc :range asked)))))

(defn ring-response
  "The Ring response that gives the page the `response` of http/call!, with
  the headers of the :expose of `opts`.

  A text body goes as UTF-8, since a page reads the answers of fetch in
  it. The URL that answered, after redirects, is the Content-Location
  (RFC 9110, section 8.7). The URL that permanent redirects led to is the
  Podcast-Clj-Permanent-Location, which unproxied reads."
  [{:keys [status url permanent-url headers body] :as response} {:keys [expose] :as opts}]
  (let [type    (when (contains? expose "content-type") (http/header headers "content-type"))
        text    (when (string? body) (http/media-type type))
        exposed (for [h (disj expose "content-type")
                      :let [v (http/joined headers h)]
                      :when v]
                  [h v])]
    {:status  status
     ;; under this name, since a Ring adapter such as Pedestal's adds its own
     ;; Content-Type to a body of bytes otherwise
     :headers (cond-> (into safety exposed)
                type          (assoc "Content-Type" (if text (str text "; charset=utf-8") type))
                url           (assoc "Content-Location" url)
                permanent-url (assoc permanent-header permanent-url))
     :body    (if (string? body) (bytes/encode body) body)}))

(defn error-response
  "The Ring response for `e`, the exception of the :send of the proxy, with
  the Proxy-Status of RFC 9209 that unproxied turns back into the same
  exception."
  [e]
  (let [kind           (cond
                         (http/timeout? e)                      ::http/timeout
                         (contains? errors (:type (ex-data e))) (:type (ex-data e))
                         :else                                  ::failed)
        [error status] (errors kind)]
    (answer status error (async/explain e))))

#?(:clj
   (defn handler
     "A Ring handler that sends each GET and HEAD request on to the URL of
     its url parameter, with `opts` over defaults, and answers with what
     came back.

     It answers as refusal, ring-response and error-response tell. Mount it
     with any router, e.g. Pedestal, ring-jetty or http-kit, behind your
     login."
     ([]
      (handler {}))
     ([opts]
      {:pre [(map? opts)]}
      (let [opts (merge defaults opts)]
        (fn [ring-request]
          (or (refusal ring-request)
              (try
                (ring-response ((:send opts) (upstream-request ring-request opts)) opts)
                (catch Exception e
                  (error-response e)))))))))

(defn proxied
  "The `request` sent through the proxy at `base`: to `base`, with the URL
  of the request as the url parameter, and without its :allow?, which the
  proxy applies itself."
  [base {:keys [url as] :as request}]
  (-> request
      (dissoc :allow?)
      (assoc :url (http/with-query base {:url url :as (when (= :bytes as) "bytes")}))))

(defn- failure
  "The exception of http/call! for `request`, which the proxy failed to
  send, as the `proxy-status` of its answer tells. Nil when it tells of no
  failure of this proxy."
  [request proxy-status]
  (when-let [[_ error] (re-find #"podcast-clj; error=([a-z_]+)" proxy-status)]
    (let [details (some-> (re-find #"details=\"((?:[^\"\\]|\\.)*)\"" proxy-status) second (str/replace #"\\(.)" "$1"))]
      (ex-info (or details (str "The proxy failed to send the request to " (:url request)))
               {:type (or (some (fn [[kind [e]]] (when (= e error) kind)) errors) ::failed)
                :url  (:url request)}))))

(defn unproxied
  "The `response` of the proxy to `request`, as http/call! would give it
  for `request` itself.

  The response has the :url that answered, after redirects, and the
  :permanent-url that permanent redirects led to. When the proxy failed to
  send the request, this throws the exception that http/call! would have
  thrown, with the same :type."
  [request {:keys [headers] :as response}]
  (if-let [e (some->> (http/joined headers "proxy-status") (failure request))]
    (throw e)
    (let [moved (http/header headers permanent-header)]
      (cond-> (assoc response :url (or (http/header headers "content-location") (:url request)))
        moved (assoc :permanent-url moved)))))

(defn sender
  "A function to give as :send, which sends each request through the
  proxy at `base`, with `opts`, and gives what http/call! would give for it.

  The `base` is the URL of the route of the proxy, e.g. /proxy in a page.
  Each request goes with the :send of `opts`, http/call! by default. A
  request that isn't http or https, e.g. for a Nostr relay, which a page
  reaches by itself, goes to that :send as it is."
  ([base]
   (sender base {}))
  ([base {:keys [send] :or {send http/call!} :as opts}]
   (fn [request]
     (if (http/web-url? (:url request))
       (async/then (send (proxied base request)) #(unproxied request %))
       (send request)))))

#?(:clj
   (comment
     (def server (handler))
     (def response (server {:request-method :get
                            :query-string   "url=https%3A%2F%2Ffeeds.podcastindex.org%2Fpc20.xml"
                            :headers        {"accept" "application/rss+xml"}}))
     (select-keys response [:status :headers])

     ;; a page that opens the proxy is refused
     (:status (server {:request-method :get :query-string "url=https%3A%2F%2Fexample.org%2F" :headers {"sec-fetch-mode" "navigate"}}))
     #_.))
