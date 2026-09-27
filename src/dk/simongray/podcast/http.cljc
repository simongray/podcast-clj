(ns dk.simongray.podcast.http
  "Requests as maps and the client that sends them: on the JVM with
  java.net.http, redirects followed, gzip accepted, conditional requests
  honoured, and the body decoded by the charset the server or the document
  declares; in ClojureScript with fetch.

  A request is `{:method :url :headers :body}`, GET unless `:method` is
  `:post`, the shape the directories and podping build and any HTTP client
  can send; `query-string` encodes the parameters of one the same way on
  both platforms. Responses are plain maps; the `:url` of a response is its
  final URL after redirects.

  Requests move no more bytes than their purpose needs: `probe!` learns
  what a server says about a file from two bytes of it, and `fetcher`
  fetches a file by byte ranges, for the readers of media files and
  images."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.time :as time])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream IOException]
                   [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder
                                  HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers
                                  HttpTimeoutException]
                   [java.nio.charset Charset StandardCharsets]
                   [java.security MessageDigest]
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

(defn- header
  "The first value of `header-name` in the response headers map `headers`,
  whose values are strings or vectors of them."
  [headers header-name]
  (let [v (get headers (str/lower-case header-name))]
    (if (string? v) v (first v))))

(defn- joined
  "All the values of `header-name` in the response headers map `headers`,
  joined by commas as fetch joins them; nil for none."
  [headers header-name]
  (let [v (get headers (str/lower-case header-name))]
    (if (string? v) v (some->> (seq v) (str/join ", ")))))

#?(:clj
   (defn- gzipped?
     "Whether the response with `headers` says that its body is gzipped."
     [headers]
     (= "gzip" (some-> (header headers "content-encoding") str/trim str/lower-case))))

#?(:clj
   (defn- headers-of
     "The headers of `response`, lower-case names to vectors of values."
     [^HttpResponse response]
     (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)]))
           (.map (.headers response)))))

(def ^:no-doc default-max-bytes
  "How much of a response body `call!` reads before giving up, unless told
  otherwise: 100 MB."
  (* 100 1024 1024))

#?(:clj
   (defn- pump
     "Read `in` to its end in pieces, handing each to `(f buffer n)`, and
     throw `::too-large` once more than `max-bytes` have arrived; nil
     `max-bytes` reads everything."
     [^InputStream in max-bytes url f]
     (let [buffer (byte-array 65536)]
       (loop [total 0]
         (let [n (.read in buffer)]
           (when-not (neg? n)
             (let [total (+ total n)]
               (when (and max-bytes (> total max-bytes))
                 (throw (ex-info (str "Response body exceeds " max-bytes " bytes: " url)
                                 {:type ::too-large :url url :max-bytes max-bytes})))
               (f buffer n)
               (recur total))))))))

#?(:clj
   (defn- read-bounded
     "Read `in` to its end into a byte array, as `pump` reads it."
     ^bytes [^InputStream in max-bytes url]
     (let [out (ByteArrayOutputStream.)]
       (pump in max-bytes url (fn [^bytes buffer n] (.write out buffer 0 (int n))))
       (.toByteArray out))))

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
   (defn- within
     "`(f)`, which reads `in`, but only until the instant `deadline` in epoch
     milliseconds, when the stream is closed and `::timeout` thrown, so that
     a server sending its body slowly holds no thread for longer than that."
     [^InputStream in deadline url f]
     (let [late? (atom false)
           task  (.schedule ^ScheduledThreadPoolExecutor @watchdog
                            ^Runnable (fn [] (reset! late? true) (.close in))
                            (long (max 0 (- deadline (System/currentTimeMillis))))
                            TimeUnit/MILLISECONDS)
           late  #(ex-info (str "The response did not arrive in time: " url) {:type ::timeout :url url})]
       (try
         (let [result (f)]
           ;; a stream closed under a read can end as if the body had ended
           (if @late? (throw (late)) result))
         (catch IOException e
           (throw (if @late? (late) e)))
         (finally
           (.cancel ^ScheduledFuture task false))))))

#?(:clj
   (defn- read-within
     "Read `in` as `read-bounded` does, `within` the `deadline`."
     ^bytes [^InputStream in deadline max-bytes url]
     (within in deadline url #(read-bounded in max-bytes url))))

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
   (defn- inflated
     "The `body` bytes of the response with `headers`, gunzipped when the
     response says so, to at most `max-bytes` (nil for no limit)."
     ^bytes [headers ^bytes body max-bytes]
     (if (gzipped? headers)
       (gunzip body max-bytes nil)
       body)))

#?(:clj
   (defn decode
     "The `body` bytes of the response with `headers` as a string, gunzipped
     when the response says so, to at most `max-bytes` (nil for no limit), and
     decoded by `declared-charset`, a byte order mark dropped, as a browser
     drops it."
     ([headers body]
      (decode headers body nil))
     ([headers ^bytes body max-bytes]
      (let [body (inflated headers body max-bytes)
            s    (String. body ^Charset (declared-charset headers body))]
        (cond-> s (str/starts-with? s "\uFEFF") (subs 1))))))

(defn content-range
  "The byte range and the size of the whole that the Content-Range header
  value `s` names, such as `bytes 0-1023/146515`, as `{:start :end :total}`,
  the total nil when the server does not know it; nil when `s` does not
  read."
  [s]
  (when-let [[_ start end total] (some->> s (re-matches #"(?i)\s*bytes\s+(\d{1,15})-(\d{1,15})/(\d{1,15}|\*)\s*"))]
    {:start (parse-long start) :end (parse-long end) :total (when (not= "*" total) (parse-long total))}))

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
     `body` string, answered within `timeout-ms` milliseconds or given up;
     gzip is accepted unless `headers` name an Accept-Encoding."
     ^HttpRequest [^URI uri method headers body timeout-ms]
     (let [builder (cond-> (.timeout (HttpRequest/newBuilder uri) (Duration/ofMillis timeout-ms))
                     (not-any? #(= "accept-encoding" (str/lower-case (str %))) (keys headers))
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
     http or https URL with a host, or leaves https for http, which the
     client of the JDK refuses as well."
     [^URI uri ^HttpResponse response]
     (when-let [location (.orElse (.firstValue (.headers response) "location") nil)]
       (when-let [target (some-> (absolute-url (str uri) location) (URI.))]
         (let [from (some-> (.getScheme uri) str/lower-case)
               to   (some-> (.getScheme target) str/lower-case)]
           (when (and (#{"http" "https"} to)
                      (.getHost target)
                      (not (and (= "https" from) (= "http" to))))
             target))))))

#?(:clj
   (defn- send-following!
     "Send `method` to `url` with `headers` and `body`, and follow the
     redirects, five at most, until the instant `deadline` in epoch
     milliseconds. A 303, or a 301 or 302 to a POST, turns into a GET, as
     browsers and the JDK do. Credentials stay behind when a redirect leaves
     the origin, because the JDK would send them on. Returns the last
     response, and the redirects before it as `[status url headers]`."
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
                      (conj chain [status (str uri) (headers-of response)])))))))))

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

(defn- prefix-length
  "How much of the body of an answer of `status` to read when it answers a
  request for the bytes `range`: of a 206, the bytes from the `start` of
  what it sends to the end of the range, since a server can send more than
  was asked; of a 200 not `gzipped?`, the whole file as it is, the bytes
  from its start to the end of the range. Nil, to read all of the body,
  when the range has no end or the prefix is longer than `max-bytes`."
  [status start gzipped? range max-bytes]
  (let [end  (second range)
        from (cond
               (= 206 status) (or start (first range))
               (= 200 status) (when-not gzipped? 0))]
    (when (and end from)
      (let [n (max 0 (inc (- end from)))]
        (when (<= n (or max-bytes 0x7FFFFFFF))
          n)))))

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
     moved for good, and `:redirects` lists each redirect followed as
     `[status url headers]`, the URL that answered with it and the headers
     of its answer.

     `:as :bytes` keeps the body as bytes, gzip undone, for a media file.
     `:range [start end]` asks for those bytes alone, the end included or
     nil for the rest, as RFC 9110 has it, and not gzipped, so that the
     offsets are those of the file; a server that sends them answers 206,
     and `:content-range` is then what `content-range` reads. A server that
     does not answers 200 with the whole file. Either way no more is read
     than up to the end of the range, when it has one."
     [{:keys [method url headers body timeout-seconds max-bytes as range]
       :or   {timeout-seconds 30 max-bytes default-max-bytes}}]
     (let [method           (some-> method name str/lower-case keyword)
           deadline         (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
           headers          (cond-> (merge {"User-Agent" user-agent} headers)
                              range (assoc "Range" (str "bytes=" (first range) "-" (second range))
                                           "Accept-Encoding" "identity"))
           [response chain] (send-following! url method headers body deadline)
           status           (.statusCode ^HttpResponse response)
           found            (headers-of response)
           received         (content-range (header found "content-range"))
           ^bytes bytes     (with-open [^InputStream in (.body ^HttpResponse response)]
                              (let [n (prefix-length status (:start received) (gzipped? found) range max-bytes)]
                                (cond
                                  ;; an answer to HEAD has no content by RFC 9110, though
                                  ;; some servers send one over HTTP/2
                                  (= :head method) (byte-array 0)
                                  n                (within in deadline url #(.readNBytes in (int n)))
                                  :else            (read-within in deadline max-bytes url))))
           final            (str (.uri ^HttpResponse response))
           moved            (permanent-url chain final)]
       (cond-> {:status  status
                :url     final
                :headers found
                :body    (when (and (not= 304 status) (pos? (alength bytes)))
                           (if (= :bytes as)
                             (inflated found bytes max-bytes)
                             (decode found bytes max-bytes)))}
         moved        (assoc :permanent-url moved)
         (seq chain)  (assoc :redirects chain)
         received     (assoc :content-range received)))))

#?(:cljs
   (defn- read-body
     "A promise of the body of the fetch response `res` from `url` as bytes:
     its first `n` bytes, the rest left unread, or with `limit?` all of it,
     rejected with `::too-large` once more than `n` bytes arrive. Nil `n`
     reads everything."
     [res n limit? url]
     (let [reader (.getReader (.-body res))
           n      (or n ##Inf)]
       (letfn [(join [chunks]
                 (.catch (.cancel reader) (fn [_] nil))
                 (let [size (min n (reduce + (map #(.-length %) chunks)))
                       out  (js/Uint8Array. size)]
                   (reduce (fn [p chunk]
                             (let [k (min (.-length chunk) (- size p))]
                               (.set out (.subarray chunk 0 k) p)
                               (+ p k)))
                           0 chunks)
                   out))
               (step [chunks got]
                 (cond
                   (and limit? (> got n))
                   (do (.catch (.cancel reader) (fn [_] nil))
                       (throw (ex-info (str "Response body exceeds " n " bytes: " url)
                                       {:type ::too-large :url url :max-bytes n})))

                   (and (not limit?) (>= got n))
                   (join chunks)

                   :else
                   (.then (.read reader)
                          (fn [r]
                            (if (.-done r)
                              (join chunks)
                              (step (conj chunks (.-value r)) (+ got (.-length (.-value r)))))))))]
         (js/Promise.resolve (step [] 0))))))

#?(:cljs
   (defn call!
     "Send the request map `{:method :url :headers :body}` with fetch, as
     `call!` sends it on the JVM, and give a promise of `{:status :url
     :headers :body}`: the body as text in UTF-8, or as bytes with `:as
     :bytes`, and none for HEAD or when it is empty. `:timeout-seconds`
     (default 30) bounds the whole exchange, and `:max-bytes` (default
     `default-max-bytes`, nil for no limit) the body, beyond which
     `::too-large` is thrown. `:range [start end]` asks for those bytes
     alone, the end included or nil for the rest, and `:content-range` is
     what `content-range` reads of a 206; of an answer with more, a 200 with
     the whole file among them, no more is read than up to the end of the
     range.

     fetch follows redirects itself and tells none of them. A browser lets a
     page read an answer from another site only as far as the CORS headers
     of the answer allow, and hides the headers that the answer does not
     expose, Content-Range and ETag among them: `:filtered?` marks such an
     answer."
     [{:keys [method url headers body as range timeout-seconds max-bytes]
       :or   {timeout-seconds 30 max-bytes default-max-bytes}}]
     (let [method (some-> method name str/lower-case keyword)]
       (-> (js/fetch url (clj->js (cond-> {:method  (str/upper-case (name (or method :get)))
                                           :headers (cond-> (or headers {})
                                                      range (assoc "Range" (str "bytes=" (first range) "-" (second range))))
                                           :signal  (js/AbortSignal.timeout (* 1000 timeout-seconds))}
                                    (some? body) (assoc :body body))))
           (.then (fn [res]
                    (let [status   (.-status res)
                          received (content-range (.get (.-headers res) "content-range"))
                          response (cond-> {:status  status
                                            :url     (.-url res)
                                            :headers (into {} (map (fn [[k v]] [k [v]]))
                                                           (es6-iterator-seq (.entries (.-headers res))))}
                                     received                (assoc :content-range received)
                                     (= "cors" (.-type res)) (assoc :filtered? true))
                          ;; fetch undoes gzip itself, so a prefix is one of the file
                          n        (prefix-length status (:start received) false range max-bytes)]
                      (if (or (= :head method) (nil? (.-body res)))
                        response
                        (.then (read-body res (or n max-bytes) (nil? n) url)
                               (fn [b]
                                 (cond-> response
                                   (pos? (.-length b))
                                   (assoc :body (if (= :bytes as) b (.decode (js/TextDecoder.) b))))))))))))))

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
   (defn digest!
     "GET `url` and compute the digests of its body as it streams in, with
     the MessageDigest `algorithms`, such as SHA-384, keeping none of the
     body, which can be a large media file: `{:status :url :digests
     {algorithm bytes}}`, the digests for a 200 alone. `opts` may carry
     `:headers` and `:timeout-seconds` (default 600), which bounds the
     whole exchange as for `call!`."
     ([url algorithms]
      (digest! url algorithms {}))
     ([url algorithms {:keys [headers timeout-seconds] :or {timeout-seconds 600}}]
      (let [deadline      (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
            [response _]  (send-following! url :get (merge {"User-Agent" user-agent "Accept-Encoding" "identity"} headers)
                                           nil deadline)
            status        (.statusCode ^HttpResponse response)
            digests       (into {} (for [a algorithms] [a (MessageDigest/getInstance a)]))]
        (with-open [^InputStream in (.body ^HttpResponse response)]
          (when (= 200 status)
            (within in deadline url
                    #(pump in nil url (fn [^bytes buffer n]
                                        (doseq [^MessageDigest d (vals digests)]
                                          (.update d buffer 0 (int n))))))))
        (cond-> {:status status :url (str (.uri ^HttpResponse response))}
          (= 200 status) (assoc :digests (update-vals digests #(.digest ^MessageDigest %))))))))

(defn- whole-size
  "The size of the file whose first `n` bytes came in a 200, the answer of
  a server that ignores ranges, to a request for the first `asked`: `n`
  when that is less, since the file ended; else the `content-length` of the
  answer, unless the `content-encoding` changed the size."
  [n asked content-length content-encoding]
  (cond
    (< n asked)
    n

    (contains? #{nil "identity"} (some-> content-encoding str/trim str/lower-case))
    (some-> content-length str/trim parse-long)))

(defn- unsatisfied-total
  "The size of the whole file that the Content-Range value `s` of an answer
  of 416 gives, `bytes */length`, as RFC 9110 has it; nil when it gives
  none."
  [s]
  (some->> s (re-matches #"(?i)\s*bytes\s+\*/(\d{1,15})\s*") second parse-long))

(defn fetcher
  "A function that fetches the bytes of the file at `url` by range, as
  `dk.simongray.podcast.bytes/read!` takes it: of a start and an end, end
  excluded, it gives `{:bytes :start :total}`, deferred in ClojureScript. A
  server that ignores ranges sends the file from its start, and only the
  bytes up to the end are read; one that answers 416, for a range past the
  end of the file, gives no bytes and the size of the file, so a reader
  learns that the file is shorter than it says. In a browser it knows the
  total only when the server exposes Content-Range. An app wraps it to log
  or keep what it fetches. `opts` may give the `:headers` and the
  `:timeout-seconds` of the requests, and `:send`, a function that sends a
  request map as `call!` does and in its place."
  [url {:keys [headers timeout-seconds send] :or {send call!}}]
  (fn [start end]
    (async/then
     (send (cond-> {:url url :headers headers :as :bytes :range [start (dec end)]}
             timeout-seconds (assoc :timeout-seconds timeout-seconds)))
     (fn [{:keys [status body content-range] :as response}]
       (let [none #?(:clj (byte-array 0) :cljs (js/Uint8Array. 0))
             body (or body none)]
         (case (long status)
           206 {:bytes body :start (or (:start content-range) start) :total (:total content-range)}
           200 {:bytes body :start 0
                :total (whole-size #?(:clj (alength ^bytes body) :cljs (.-length body)) end
                                   (header (:headers response) "content-length")
                                   (header (:headers response) "content-encoding"))}
           ;; the body of a 416 is at most a page about the error
           416 {:bytes none :start start
                :total (unsatisfied-total (header (:headers response) "content-range"))}
           (throw (ex-info (str "The file answered " status ": " url) {:status status :url url}))))))))

(defn media-type
  "The media type of the Content-Type value `s`, or of a type attribute,
  lower-case and without its parameters; nil for none."
  [s]
  (some-> s (str/split #";" 2) first str/trim str/lower-case not-empty))

(defn- cors
  "The origins that the Access-Control-Allow-Origin of the response
  `headers` allows to read it, all its values joined, as a browser reads
  them, which lets no page read an answer that has two."
  [headers]
  (joined headers "access-control-allow-origin"))

(defn facts
  "What the `response` of `call!` tells about its file, as far as it tells
  it: `{:status :url :redirects :permanent-url :location :type :length
  :range :encoding :etag :last-modified :cors :filtered?}`, the
  `media-type`, the length that Content-Length gives, the Content-Range as
  `content-range` reads it, the Location of a redirect that was not
  followed, and the origins that Access-Control-Allow-Origin allows to read
  the answer. Each redirect on the way is `{:status :url :cors}`, the URL
  that answered with it."
  [response]
  (let [h #(header (:headers response) %)]
    (into {} (remove (comp nil? val))
          {:status        (:status response)
           :url           (:url response)
           :redirects     (not-empty (mapv (fn [[status url headers]]
                                             (cond-> {:status status :url url}
                                               (cors headers) (assoc :cors (cors headers))))
                                           (:redirects response)))
           :permanent-url (:permanent-url response)
           :location      (h "location")
           :type          (media-type (h "content-type"))
           :length        (some->> (h "content-length") str/trim (re-matches #"\d{1,15}") parse-long)
           :range         (:content-range response)
           :encoding      (h "content-encoding")
           :etag          (h "etag")
           :last-modified (h "last-modified")
           :cors          (cors (:headers response))
           :filtered?     (:filtered? response)})))

(defn probe!
  "What the server says about the file at `url`, while no more than two
  bytes of it move: `{:head :get}`, the `facts` of the answers to a HEAD
  request and to a request for the first two bytes, or `{:error}` for a
  request that failed. Most servers answer the second with 206 and the
  size of the whole in Content-Range; one that ignores ranges answers 200,
  of which two bytes are read. The HEAD request asks for the file as it
  is, not gzipped, so that its Content-Length is the size of the file. On
  the JVM the result, in ClojureScript a promise of it. `opts` may give the
  `:headers` of both requests, such as an Origin to learn about CORS,
  `:send`, a function that sends a request map as `call!` does and in its
  place, and the `:timeout-seconds`."
  ([url]
   (probe! url {}))
  ([url {:keys [headers send timeout-seconds] :or {send call!}}]
   (let [answer! (fn [request]
                   (async/then (async/attempt #(send (cond-> (assoc request :url url)
                                                        timeout-seconds (assoc :timeout-seconds timeout-seconds))))
                               (fn [{:keys [value error]}]
                                 (if error {:error (async/explain error)} (facts value)))))]
     (async/then (async/all [(answer! {:method :head :headers (merge {"Accept-Encoding" "identity"} headers)})
                             (answer! {:range [0 1] :as :bytes :headers headers})])
                 (fn [[head got]] {:head head :get got})))))

#?(:clj
   (comment
     (select-keys (fetch! "https://example.org/feed.xml")
                  [:status :url :etag :last-modified])
     #_.))
