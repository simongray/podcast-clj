(ns dk.simongray.podcast.http
  "Requests as maps and, on the JVM, the client that sends them: fetching
  with java.net.http, redirects followed, gzip accepted, conditional
  requests honoured, and the body decoded by the charset the server or the
  document declares.

  A request is `{:method :url :headers :body}`, GET unless `:method` is
  `:post`, the shape the directories and podping build and any HTTP client
  can send; `query-string` encodes the parameters of one the same way on
  both platforms. Responses are plain maps; the `:url` of a response is its
  final URL after redirects."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time :as time])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream IOException]
                   [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder
                                  HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers
                                  HttpTimeoutException]
                   [java.nio.charset Charset StandardCharsets]
                   [java.time Duration]
                   [java.util.concurrent ScheduledFuture ScheduledThreadPoolExecutor ThreadFactory TimeUnit]
                   [java.util.zip GZIPInputStream])))

(def user-agent
  "The User-Agent sent with every request unless the caller sets one."
  "podcast-clj (+https://github.com/simongray/podcast-clj)")

(defn encode
  "Percent-encode `s` for a query string, the same way on both platforms:
  spaces become %20, not +, and letters, digits and `-_.!~*'()` stay as they
  are."
  [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
               (str/replace "+" "%20")
               (str/replace "%21" "!")
               (str/replace "%27" "'")
               (str/replace "%28" "(")
               (str/replace "%29" ")")
               (str/replace "%7E" "~"))
     :cljs (js/encodeURIComponent (str s))))

(defn percent-decode
  "The string `s` with its percent-encoding undone, the inverse of `encode`:
  a plus sign stays a plus sign, and a string with an escape that does not
  decode comes back as it is rather than throwing."
  [s]
  #?(:clj  (try
             (java.net.URLDecoder/decode (str/replace (str s) "+" "%2B") "UTF-8")
             (catch IllegalArgumentException _
               (str s)))
     :cljs (try
             (js/decodeURIComponent (str s))
             (catch :default _
               (str s)))))

(defn query-string
  "The query string for `params`, keys and values URL-encoded and nil
  values left out; `true` becomes the bare flag some APIs take."
  [params]
  (str/join "&" (for [[k v] params
                      :when (some? v)]
                  (if (true? v)
                    (encode (name k))
                    (str (encode (name k)) "=" (encode v))))))

(defn- base64
  "The UTF-8 bytes of `s` in base64."
  [s]
  #?(:clj  (.encodeToString (java.util.Base64/getEncoder) (.getBytes ^String s "UTF-8"))
     :cljs (js/btoa (js/unescape (js/encodeURIComponent s)))))

(defn authorization
  "The Authorization header for `credentials`, a map or a function of no
  arguments that returns one: `{:username :password}` for basic
  authentication, `{:token}` for a bearer token; nil for none."
  [credentials]
  (let [{:keys [username password token]} (if (fn? credentials) (credentials) credentials)]
    (cond
      token    (str "Bearer " token)
      username (str "Basic " (base64 (str username ":" password)))
      :else    nil)))

(defn not-before
  "The earliest instant to ask again, by the response `headers` that arrived
  at `at`: the `Retry-After`, in seconds or as a date; else the end of the
  freshness that `Cache-Control: max-age` gives, less the `Age`; else the
  `Expires`. A date counts from the `Date` of the response, as RFC 9111
  says, so that neither the clock of the server nor a wrong time zone
  matters; without a `Date`, it counts from `at`. At most an hour after
  `at`: a feed host can give its feed a freshness of days and still publish
  within them, and a server set up wrong must not hold the polling back.
  Nil when the headers name no later time, or forbid a cache. `headers`
  maps lower-case names to a value or a vector of values, as the responses
  of `call!` do."
  [headers at]
  (let [value   (fn [k] (let [v (get headers k)] (if (sequential? v) (first v) v)))
        now     (inst-ms at)
        seconds (fn [s] (some->> s str/trim (re-matches #"\d{1,10}") parse-long))
        date    (fn [s] (some-> s time/parse-rfc2822 inst-ms))
        served  (date (value "date"))
        ;; a time on the clock of the server, as a time on ours
        ours    (fn [t] (if served (+ now (- t served)) t))
        age     (* 1000 (or (seconds (value "age")) 0))
        cache   (some-> (value "cache-control") str/lower-case)
        retry   (value "retry-after")
        until   (if retry
                  (or (some->> (seconds retry) (* 1000) (+ now)) (some-> (date retry) ours))
                  (when-not (some->> cache (re-find #"no-cache|no-store"))
                    (if-let [max-age (some->> cache (re-find #"max-age=(\d{1,10})") second parse-long)]
                      (- (+ now (* 1000 max-age)) age)
                      (some-> (date (value "expires")) ours (- (if served age 0))))))]
    (when (and until (> until now))
      (time/->inst (min until (+ now (* 60 60 1000)))))))

(defn split-credentials
  "The URL `url` without the user name and password that it holds, as in
  `https://user:pass@host/feed.xml`, and those as credentials for
  `authorization`: `[url credentials]`, the credentials nil when it holds
  none. An app can use it when it adds a feed, to keep the password apart
  from the address, since an address travels: into OPML, sync documents
  and messages."
  [url]
  (let [[_ scheme authority tail] (re-matches #"(?s)([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#]*)(.*)" (str url))
        at                        (some-> authority (str/last-index-of "@"))]
    (if at
      (let [[user password] (str/split (subs authority 0 at) #":" 2)]
        [(str scheme (subs authority (inc at)) tail)
         (when-not (str/blank? user)
           {:username (percent-decode user) :password (percent-decode (or password ""))})])
      [url nil])))

#?(:clj
   (defonce ^{:no-doc true
              :doc    "The shared HTTP client, built on first use: a 15 second
  connect timeout, and redirects left to `call!`, which follows them."}
     client
     (delay (-> (HttpClient/newBuilder)
                (.followRedirects HttpClient$Redirect/NEVER)
                (.connectTimeout (Duration/ofSeconds 15))
                (.build)))))

#?(:clj
   (defn- header
     "The first value of `header-name` in the response headers map `headers`."
     [headers header-name]
     (first (get headers (str/lower-case header-name)))))

#?(:clj
   (defn- response->map
     [^HttpResponse response ^bytes body]
     {:status  (.statusCode response)
      :url     (str (.uri response))
      :headers (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)]))
                     (.map (.headers response)))
      :body    body}))

(def ^:no-doc default-max-bytes
  "How much of a response body `call!` reads before giving up, unless told
  otherwise: 100 MB."
  (* 100 1024 1024))

#?(:clj
   (defn- read-bounded
     "Read `in` to its end into a byte array, throwing `::too-large` once more
     than `max-bytes` have arrived; nil `max-bytes` reads everything."
     ^bytes [^InputStream in max-bytes url]
     (let [out    (ByteArrayOutputStream.)
           buffer (byte-array 65536)]
       (loop [total 0]
         (let [n (.read in buffer)]
           (if (neg? n)
             (.toByteArray out)
             (let [total (+ total n)]
               (when (and max-bytes (> total max-bytes))
                 (throw (ex-info (str "Response body exceeds " max-bytes " bytes: " url)
                                 {:type ::too-large :url url :max-bytes max-bytes})))
               (.write out buffer 0 n)
               (recur total))))))))

#?(:clj
   (defonce ^{:no-doc true
              :doc    "The daemon thread that closes the body of a response still
  being read when its time is up, built on first use."}
     watchdog
     (delay (doto (ScheduledThreadPoolExecutor.
                   1 (reify ThreadFactory
                       (newThread [_ runnable]
                         (doto (Thread. runnable "podcast-clj http watchdog")
                           (.setDaemon true)))))
              (.setRemoveOnCancelPolicy true)))))

#?(:clj
   (defn- read-within
     "Read `in` as `read-bounded` does, but only until the instant `deadline`
     in epoch milliseconds, when the stream is closed and `::timeout`
     thrown, so that a server sending its body slowly holds no thread for
     longer than that."
     ^bytes [^InputStream in deadline max-bytes url]
     (let [late? (atom false)
           task  (.schedule ^ScheduledThreadPoolExecutor @watchdog
                            ^Runnable (fn [] (reset! late? true) (.close in))
                            (long (max 0 (- deadline (System/currentTimeMillis))))
                            TimeUnit/MILLISECONDS)
           late  #(ex-info (str "The response did not arrive in time: " url) {:type ::timeout :url url})]
       (try
         (let [bytes (read-bounded in max-bytes url)]
           ;; a stream closed under a read can end as if the body had ended
           (if @late? (throw (late)) bytes))
         (catch IOException e
           (throw (if @late? (late) e)))
         (finally
           (.cancel ^ScheduledFuture task false))))))

#?(:clj
   (defn- gunzip
     "Inflate the gzipped `body`, reading at most `max-bytes` of the result."
     ^bytes [^bytes body max-bytes url]
     (with-open [in (GZIPInputStream. (ByteArrayInputStream. body))]
       (read-bounded in max-bytes url))))

#?(:clj
   (defn- declared-charset
     "The charset a response declares, in its Content-Type header or, failing
     that, in the XML declaration at the start of `body`; UTF-8 otherwise."
     [headers ^bytes body]
     (let [content-type (or (header headers "content-type") "")
           head         (String. body 0 (min 200 (alength body)) StandardCharsets/ISO_8859_1)
           name         (or (second (re-find #"(?i)charset=\"?([\w.:-]+)" content-type))
                            (second (re-find #"(?i)<\?xml[^>]*encoding=[\"']([\w.:-]+)" head))
                            "UTF-8")]
       (try
         (Charset/forName name)
         (catch Exception _
           StandardCharsets/UTF_8)))))

#?(:clj
   (defn decode
     "The `body` bytes of the response with `headers` as a string, gunzipped
     when the response says so, to at most `max-bytes` (nil for no limit), and
     decoded by `declared-charset`, a byte order mark dropped, as a browser
     drops it."
     ([headers body]
      (decode headers body nil))
     ([headers ^bytes body max-bytes]
      (let [gzip? (= "gzip" (some-> (header headers "content-encoding") str/trim str/lower-case))
            body  (if gzip? (gunzip body max-bytes nil) body)
            s     (String. body ^Charset (declared-charset headers body))]
        (cond-> s (str/starts-with? s "\uFEFF") (subs 1))))))

(def ^:no-doc legal-url-characters
  "The ASCII characters a URI may contain as they are: RFC 3986's unreserved
  and reserved sets, plus the percent sign of an escape."
  (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%"))

#?(:clj
   (defn- encode-illegal
     "The part `s` of a URL with the characters a URI may not contain
     percent-encoded as UTF-8, and those of the set `also`, and a percent
     sign that starts no escape."
     [^String s also]
     (let [sb     (StringBuilder.)
           bytes  (.getBytes s StandardCharsets/UTF_8)
           n      (alength bytes)
           digit? (fn [i] (and (< i n) (let [b (aget bytes i)] (or (<= 48 b 57) (<= 65 b 70) (<= 97 b 102)))))]
       (dotimes [i n]
         (let [b (bit-and (aget bytes i) 0xFF)
               c (char b)]
           (cond
             (and (= \% c) (not (and (digit? (inc i)) (digit? (+ i 2))))) (.append sb "%25")
             (and (< b 128) (legal-url-characters c) (not (also c)))      (.append sb c)
             :else                                                         (.append sb (format "%%%02X" b)))))
       (str sb))))

#?(:clj
   (defn legal-url
     "`url` with every character a URI may not contain, such as a space or a
     non-ASCII letter, percent-encoded as UTF-8, and so is a bracket in the
     path, where java.net.URI takes none, and a percent sign that starts no
     escape. An escape is left as it is, so an already encoded URL comes
     back unchanged."
     [^String url]
     (let [[_ head path tail] (re-matches #"(?s)([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*)?([^?#]*)(.*)" url)]
       (str (encode-illegal (str head) #{}) (encode-illegal path #{\[ \]}) (encode-illegal tail #{})))))

(defn absolute-url
  "`url` made absolute against `base`, the URL of the document it is in, as
  a browser does it: a path, a path from the root, a query alone or a URL
  without a scheme resolves against `base`, and an absolute URL stays as it
  is. Nil when either does not read as a URL."
  [base url]
  #?(:clj  (try
             (let [base (legal-url (str base))
                   url  (legal-url (str url))
                   b    (URI. base)]
               ;; java.net.URI resolves by RFC 2396, which differs from browsers
               ;; on a query or a fragment alone and on a base with no path
               (cond
                 (str/starts-with? url "#") (str (first (str/split base #"#" 2)) url)
                 (str/starts-with? url "?") (str (first (str/split base #"[?#]" 2)) url)
                 :else                      (str (.resolve (if (and (.getRawAuthority b) (str/blank? (.getRawPath b)))
                                                             (URI. (str (.getScheme b) "://" (.getRawAuthority b) "/"))
                                                             b)
                                                           (URI. url)))))
             (catch Exception _
               nil))
     :cljs (try
             (.-href (js/URL. (str url) (str base)))
             (catch :default _
               nil))))

#?(:clj
   (defn- java-request
     "The java.net.http request of `method` to `uri`, with `headers` and the
     `body` string, answered within `timeout-ms` milliseconds or given up."
     ^HttpRequest [^URI uri method headers body timeout-ms]
     (let [builder (-> (HttpRequest/newBuilder uri)
                       (.timeout (Duration/ofMillis timeout-ms))
                       (.header "Accept-Encoding" "gzip"))
           builder (reduce-kv (fn [^HttpRequest$Builder b k v] (.header b (str k) (str v))) builder headers)]
       (-> (if (or (nil? method) (= :get method))
             (.GET ^HttpRequest$Builder builder)
             (.method ^HttpRequest$Builder builder
                      (str/upper-case (name method))
                      (if (some? body)
                        (HttpRequest$BodyPublishers/ofString (str body))
                        (HttpRequest$BodyPublishers/noBody))))
           (.build)))))

#?(:clj
   (defn- origin
     "The scheme, host and port of `uri`, the port of the scheme when it
     names none."
     [^URI uri]
     (let [scheme (some-> (.getScheme uri) str/lower-case)]
       [scheme
        (some-> (.getHost uri) str/lower-case)
        (if (neg? (.getPort uri)) (get {"http" 80 "https" 443} scheme) (.getPort uri))])))

#?(:clj
   (defn ^:no-doc same-origin?
     "Whether the URLs `a` and `b` have the same scheme, host and port."
     [a b]
     (try
       (= (origin (URI. (legal-url a))) (origin (URI. (legal-url b))))
       (catch Exception _
         false))))

#?(:clj
   (defn- redirect-target
     "Where the redirect `response` to `uri` leads; nil when it names no
     place that reads as a URL, or leaves https for http, which the client of
     the JDK refuses as well."
     [^URI uri ^HttpResponse response]
     (when-let [location (.orElse (.firstValue (.headers response) "location") nil)]
       (when-let [target (some-> (absolute-url (str uri) location) (URI.))]
         (when-not (and (= "https" (.getScheme uri)) (= "http" (.getScheme target)))
           target)))))

#?(:clj
   (defn- send-following!
     "Send `method` to `url` with `headers` and `body`, and follow the
     redirects, five at most, until the instant `deadline` in epoch
     milliseconds. A 303, or a 301 or 302 to a POST, turns into a GET, as
     browsers and the JDK do. Credentials stay behind when a redirect leaves
     the origin, because the JDK would send them on. Returns the last
     response, and the redirects before it as `[status url]`."
     [url method headers body deadline]
     (loop [uri (URI. (legal-url url)) method method headers headers body body chain []]
       (let [left (- deadline (System/currentTimeMillis))]
         (when-not (pos? left)
           (throw (HttpTimeoutException. (str "request timed out: " uri))))
         (let [response (.send ^HttpClient @client (java-request uri method headers body left)
                               (HttpResponse$BodyHandlers/ofInputStream))
               status   (.statusCode response)
               target   (when (and (#{301 302 303 307 308} status) (< (count chain) 5))
                          (redirect-target uri response))]
           (if-not target
             [response chain]
             (let [get? (or (= 303 status) (and (#{301 302} status) (= :post method)))]
               (.close ^InputStream (.body response))
               (recur target
                      (if (and get? (not= :head method)) :get method)
                      (cond->> headers
                        (not= (origin uri) (origin target))
                        (into {} (remove (fn [[k _]] (#{"authorization" "cookie" "proxy-authorization"}
                                                     (str/lower-case (str k)))))))
                      (when-not get? body)
                      (conj chain [status (str uri)])))))))))

#?(:clj
   (defn- permanent-url
     "The URL the first request moved to for good: the one that the permanent
     redirects, 301 or 308, at the start of `chain` lead to, the requests
     after them ending at `final-url`; nil when the first is no permanent
     redirect."
     [chain final-url]
     (let [n (count (take-while #(#{301 308} (first %)) chain))]
       (when (pos? n)
         (get (conj (mapv second chain) final-url) n)))))

#?(:clj
   (defn call!
     "Send the request map `{:method :url :headers :body}` and return
     `{:status :url :headers :body}`, the body decoded to a string. A GET
     unless `:method` names another, sent with the `:body` string when there
     is one; `:timeout-seconds` (default 30) bounds the whole exchange,
     beyond which the client's HttpTimeoutException is thrown while the
     headers are awaited and `::timeout` while the body is read;
     `:max-bytes` (default `default-max-bytes`, nil for no limit) bounds the
     body, beyond which `::too-large` is thrown. gzip is undone and the URL
     made legal first (see `legal-url`).

     Redirects are followed, five at most and never from https to http, and
     a redirect to another origin leaves the credentials behind. When the
     redirects begin with permanent ones, `:permanent-url` is where the URL
     moved for good."
     [{:keys [method url headers body timeout-seconds max-bytes]
       :or   {timeout-seconds 30 max-bytes default-max-bytes}}]
     (let [deadline         (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
           [response chain] (send-following! url method (merge {"User-Agent" user-agent} headers) body deadline)
           ^bytes bytes     (with-open [^InputStream in (.body ^HttpResponse response)]
                              (read-within in deadline max-bytes url))
           response         (response->map response bytes)
           moved            (permanent-url chain (:url response))]
       (cond-> (assoc response :body (when (and (not= 304 (:status response)) (pos? (alength bytes)))
                                       (decode (:headers response) bytes max-bytes)))
         moved (assoc :permanent-url moved)))))

#?(:clj
   (defn fetch!
     "GET the feed at `url` and return `{:status :url :headers :body}` with the
     body as a decoded string, plus `:etag` and `:last-modified` when present,
     `:not-before` when the server says how long to wait (see `not-before`),
     and the `:permanent-url` of `call!`. `opts` may carry an `:etag` and
     `:last-modified` from an earlier response, sent as If-None-Match and
     If-Modified-Since, on which a 304 comes back with no body; the
     `:credentials` of a private feed (see `authorization`); a `:user-agent`
     to send instead of `user-agent`; and the `:timeout-seconds` and
     `:max-bytes` of `call!`. A user name and a password in `url` are the
     credentials when `opts` give none, and the request goes to `url`
     without them (see `split-credentials`)."
     ([url]
      (fetch! url {}))
     ([url {:keys [etag last-modified credentials user-agent]
            :or   {user-agent dk.simongray.podcast.http/user-agent}
            :as   opts}]
      (let [[url in-url] (split-credentials url)
            accept       "application/rss+xml, application/xml, text/xml, */*"
            auth         (authorization (or credentials in-url))
            headers      (cond-> {"User-Agent" user-agent "Accept" accept}
                           etag          (assoc "If-None-Match" etag)
                           last-modified (assoc "If-Modified-Since" last-modified)
                           auth          (assoc "Authorization" auth))
            response     (call! (assoc opts :url url :headers headers))
            wait         (not-before (:headers response) (java.util.Date.))]
        (cond-> (assoc response
                       :etag          (header (:headers response) "etag")
                       :last-modified (header (:headers response) "last-modified"))
          wait (assoc :not-before wait))))))

#?(:clj
   (comment
     (select-keys (fetch! "https://example.org/feed.xml")
                  [:status :url :etag :last-modified])
     #_.))
