(ns dk.simongray.podcast.util.http
  "Requests as maps, and the client that sends them.

  On the JVM, the client is java.net.http. It follows redirects, accepts
  gzip, makes conditional requests, and decodes the body by the charset
  that the server or the document declares. In ClojureScript, the client
  is fetch.

  A request is a map of :method, :url, :headers and :body, and it's a GET
  unless :method names another method. The directories and Podping build
  requests of this shape, so any HTTP client can send them, and
  dk.simongray.podcast.util.url encodes their parameters the same way on
  both platforms. A response is a plain map, whose :url is the final URL
  after redirects.

  A request moves no more bytes than its purpose needs. The probe!
  function gets the facts that a server gives about a file by asking for
  two bytes of it. The fetcher function fetches a file by byte ranges, for
  the readers of media files and images."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.url :as url]
            [dk.simongray.podcast.util.xml :as xml])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream
                            InputStream IOException]
                   [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect HttpRequest
                                  HttpRequest$Builder HttpRequest$BodyPublishers
                                  HttpResponse HttpResponse$BodyHandlers
                                  HttpTimeoutException]
                   [java.security MessageDigest]
                   [java.time Duration]
                   [java.util.concurrent ScheduledFuture
                                         ScheduledThreadPoolExecutor
                                         ThreadFactory TimeUnit]
                   [java.util.zip GZIPInputStream])))

(def user-agent
  "The User-Agent sent with every request unless the caller sets one."
  "podcast-clj (+https://github.com/simongray/podcast-clj)")

(defn ^:no-doc base64
  "The UTF-8 bytes of `s` in base64."
  [s]
  (bytes/base64 (bytes/encode s)))

;; RFC 7617, section 2 (Basic); RFC 6750, section 2.1 (Bearer)
(defn authorization
  "The Authorization header for `credentials`, or nil for no credentials.

  The `credentials` are a map, or a function of no arguments that gives
  one. The map has :username and :password for basic authentication, or
  :token for a bearer token. The password and the token can be hidden
  texts of util.secret, so that a map of credentials doesn't show them
  when it's printed.

  The header has the password or the token, so it's a hidden text too,
  and a request map with it doesn't show it when printed. The send!
  function reads the text when it sends the request, and revealed gives
  the request with the text, for another client."
  [credentials]
  (let [m        (if (fn? credentials) (credentials) credentials)
        username (:username m)
        password (secret/reveal (:password m))
        token    (secret/reveal (:token m))]
    (cond
      token
      (secret/hide (str "Bearer " token))

      username
      (secret/hide (str "Basic " (base64 (str username ":" password))))

      :else
      nil)))

;; RFC 7617, section 2 (Basic); RFC 6750, section 2.1 (Bearer)
(defn credentials
  "The credentials in the Authorization header `authorization`, as the map
  that authorization takes, or nil when it has none.

  The map has :username and :password for basic authentication, or :token
  for a bearer token, and the password and the token are hidden texts. The
  header can be a hidden text too."
  [authorization]
  (let [auth-pattern     #"(?i)\s*(basic|bearer)\s+(\S+)\s*"
        [_ scheme value] (some->> (secret/reveal authorization)
                                  (re-matches auth-pattern))]
    (case (some-> scheme str/lower-case)
      "basic"  (let [b       (bytes/from-base64 value)
                     decoded (some-> b (bytes/decode 0 (bytes/length b) :utf8))
                     i       (some-> decoded (str/index-of ":"))]
                 (when i
                   {:username (subs decoded 0 i)
                    :password (secret/hide (subs decoded (inc i)))}))
      "bearer" {:token (secret/hide value)}
      nil)))

(defn revealed
  "The `request` with the text of each hidden header and of a hidden body,
  as the server needs them.

  The functions that write a request hide the headers Authorization and
  Cookie, and the body of a request with a secret. The send! function
  reads the text when it sends. To send a request with another client,
  take it through revealed first. A request that's revealed shows its
  credentials when it's printed."
  [{:keys [headers] :as request}]
  (cond-> request
    headers                   (update :headers update-vals secret/reveal)
    (contains? request :body) (update :body secret/reveal)))

(defn ^:no-doc header
  "The first value of `header-name` in the response headers map `headers`,
  whose values are strings or vectors of them."
  [headers header-name]
  (let [v (get headers (str/lower-case header-name))]
    (if (string? v) v (first v))))

(defn ^:no-doc joined
  "All the values of `header-name` in the response headers map `headers`,
  joined by commas as fetch joins them, or nil for none."
  [headers header-name]
  (let [v (get headers (str/lower-case header-name))]
    (if (string? v)
      v
      (some->> (seq v) (str/join ", ")))))

(defn- link-of
  "The link that the `tokens` of one value of a Link header give, the
  target and then the parameters, each after a semicolon. Nil when the
  value starts with no target."
  [[target & params :as tokens]]
  (when (and target
             (re-find #"^<\s*[^\s>]" target))
    (reduce (fn [link [k eq v]]
              (let [k (keyword (str/lower-case k))]
                (cond-> link
                  (and (= "=" eq)
                       v
                       (not (contains? link k)))
                  (assoc k (str/replace v #"^\"|\"$" "")))))
            {:href (str/trim (subs target 1 (dec (count target))))}
            (remove #(= ";" (first %)) (partition-by #(= ";" %) params)))))

(defn links
  "The links of the Link header in the response `headers`, as section 3
  of RFC 8288 defines them.

  Each link is a map with its target as :href, and with its parameters
  by name, e.g. :rel and :type. The links are in the order of the header.
  The first value of a parameter counts, and a target can be relative to
  the URL of the response."
  [headers]
  (->> (re-seq #"<[^>]*>|\"[^\"]*\"|[;,=]|[^\s;,=<\"]+"
               (str (joined headers "link")))
       (partition-by #(= "," %))
       (keep link-of)
       vec))

(defn ^:no-doc seconds
  "The whole number of seconds that the header value `s` gives, or nil
  when it gives none."
  [s]
  (some->> s str/trim (re-matches #"\d{1,10}") parse-long))

(def ^:no-doc asctime-date
  "The pattern of a date in the asctime form, with the month, the day,
  the time and the year as its groups."
  #"\s*[A-Za-z]{3} ([A-Za-z]{3}) +(\d{1,2}) (\d{2}:\d{2}:\d{2}) (\d{4})\s*")

;; RFC 9110, section 5.6.7: of the three forms of a date, asctime has no
;; zone and is UTC
(defn ^:no-doc date-ms
  "The date that the header value `s` gives, in epoch milliseconds, or nil
  when it gives none."
  [s]
  (let [asctime (some->> s (re-matches asctime-date))
        s       (if-let [[_ month day clock year] asctime]
                  (str day " " month " " year " " clock " GMT")
                  s)]
    (some-> s time/parse-rfc2822 inst-ms)))

(defn ^:no-doc ours
  "The time `t` on the clock of the server, as a time on the clock of the
  client. The response that `arrived` names `served` as the time that the
  server sent it. Without `served`, the clocks count as one."
  [t arrived served]
  (if served
    (+ arrived (- t served))
    t))

;; RFC 9110, section 10.2.3
(defn- retry-after
  "When the Retry-After of `headers` says to ask again, in epoch
  milliseconds, with `arrived` and `served` as ours takes them. Nil when
  it gives no time."
  [headers arrived served]
  (let [retry (header headers "retry-after")]
    (or (some->> (seconds retry) (* 1000) (+ arrived))
        (some-> (date-ms retry) (ours arrived served)))))

;; RFC 9111, section 4.2.1
(defn- fresh-until
  "When the freshness of the response with `headers` ends, in epoch
  milliseconds, with `arrived` and `served` as ours takes them. Nil when
  the headers forbid a cache or name no time.

  The freshness ends at the max-age less the Age, or else at the Expires."
  [headers arrived served]
  (let [age     (* 1000 (or (seconds (header headers "age")) 0))
        cache   (some-> (joined headers "cache-control") str/lower-case)
        max-age (some->> cache
                         (re-find #"max-age=(\d{1,10})")
                         second
                         parse-long)
        expires (date-ms (header headers "expires"))]
    (when-not (some->> cache (re-find #"no-cache|no-store"))
      (if max-age
        (- (+ arrived (* 1000 max-age)) age)
        (some-> expires
                (ours arrived served)
                (- (if served age 0)))))))

(defn not-before
  "The earliest instant to ask again, by the response `headers` that arrived
  at `at`. Nil when the headers name no later time, or forbid a cache.

  The instant is the first of these that the headers give:

  - the Retry-After, in seconds or as a date
  - the end of the freshness that the max-age of Cache-Control gives,
    less the Age
  - the Expires

  A date counts from the Date of the response, so that the clock of the
  server and a wrong time zone don't matter (RFC 9111, section 4.2.1).
  Without a Date, a date counts from `at`.

  The instant is at most an hour after `at`, since a feed host can give
  its feed a freshness of days and still publish within them. Also, a
  server that's set up wrong mustn't hold back the polling.

  The `headers` map lower-case names to a value or a vector of values, as
  the responses of send! do. A Retry-After that doesn't read counts as
  none. The values of all the Cache-Control headers count, joined as RFC
  9110, section 5.3, joins them."
  [headers at]
  (let [arrived (inst-ms at)
        served  (date-ms (header headers "date"))
        until   (or (retry-after headers arrived served)
                    (fresh-until headers arrived served))]
    (when (and until (> until arrived))
      (time/->inst (min until (+ arrived (* 60 60 1000)))))))

#?(:clj
   (defonce ^{:no-doc true
              :doc    "The shared HTTP client, built on first use, with a
  connect timeout of 15 seconds. Redirects are left to send!, which
  follows them."}
     client
     (delay (-> (HttpClient/newBuilder)
                (.followRedirects HttpClient$Redirect/NEVER)
                (.connectTimeout (Duration/ofSeconds 15))
                (.build)))))

;; RFC 9110, section 8.4.1.3: x-gzip is gzip
#?(:clj
   (defn ^:no-doc gzipped?
     "Whether the response with `headers` says that its body is gzipped."
     [headers]
     (let [coding (some-> (header headers "content-encoding")
                          str/trim
                          str/lower-case)]
       (contains? #{"gzip" "x-gzip"} coding))))

#?(:clj
   (defn ^:no-doc headers-of
     "The headers of `response`, as a map from lower-case names to vectors
     of values."
     [^HttpResponse response]
     (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)]))
           (.map (.headers response)))))

(def ^:no-doc default-max-bytes
  "How much of a response body send! reads at most, unless the request
  sets another limit: 100 MB."
  (* 100 1024 1024))

(defn ^:no-doc too-large
  "The exception of a response body from `url` that holds more than
  `max-bytes`."
  [url max-bytes]
  (ex-info (str "Response body exceeds " max-bytes " bytes: " url)
           {:type ::too-large :url url :max-bytes max-bytes}))

#?(:clj
   (defn ^:no-doc pump
     "Read `in` from `url` to its end in pieces, give each piece to `f`, and
     throw ::too-large once more than `max-bytes` have arrived.

     The `f` takes the buffer and the length of the piece. With nil
     `max-bytes`, it reads everything."
     [^InputStream in max-bytes url f]
     (let [buffer (byte-array 65536)]
       (loop [total 0]
         (let [n (.read in buffer)]
           (when-not (neg? n)
             (let [total (+ total n)]
               (when (and max-bytes (> total max-bytes))
                 (throw (too-large url max-bytes)))
               (f buffer n)
               (recur total))))))))

#?(:clj
   (defn ^:no-doc read-bounded
     "The bytes of `in` from `url`, read to its end within `max-bytes`, as
     pump reads them."
     ^bytes [^InputStream in max-bytes url]
     (let [out (ByteArrayOutputStream.)]
       (pump in max-bytes url (fn [^bytes buffer n]
                                (.write out buffer 0 (int n))))
       (.toByteArray out))))

#?(:clj
   (defonce ^{:no-doc true
              :doc    "The daemon thread that closes the body of a response
  still being read when its time is up, built on first use."}
     watchdog
     (delay (doto (ScheduledThreadPoolExecutor.
                   1 (reify ThreadFactory
                       (newThread [_ runnable]
                         (doto (Thread. runnable "podcast-clj http watchdog")
                           (.setDaemon true)))))
              (.setRemoveOnCancelPolicy true)))))

#?(:clj
   (defn ^:no-doc within
     "The result of `f`, which reads `in` from `url`, but only until the
     instant `deadline` in epoch milliseconds.

     At the deadline, the stream is closed and ::timeout thrown, so that a
     server that sends its body slowly holds no thread for longer."
     [^InputStream in deadline url f]
     (let [late?   (atom false)
           wait-ms (long (max 0 (- deadline (System/currentTimeMillis))))
           task    (.schedule ^ScheduledThreadPoolExecutor @watchdog
                              ^Runnable (fn [] (reset! late? true) (.close in))
                              wait-ms
                              TimeUnit/MILLISECONDS)
           late    #(ex-info (str "The response did not arrive in time: " url)
                             {:type ::timeout :url url})]
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
     "The bytes of `in` from `url` within `max-bytes`, as read-bounded reads
     them, but only until the `deadline`."
     ^bytes [^InputStream in deadline max-bytes url]
     (within in deadline url #(read-bounded in max-bytes url))))

#?(:clj
   (defn- gunzip
     "The gzipped `body` from `url` inflated, with at most `max-bytes` of the
     result read."
     ^bytes [^bytes body max-bytes url]
     (with-open [in (GZIPInputStream. (ByteArrayInputStream. body))]
       (read-bounded in max-bytes url))))

(defn- charset
  "The charset that the Content-Type of the response `headers` names, or
  nil."
  [headers]
  (some->> (header headers "content-type")
           (re-find #"(?i)charset=\"?([\w.:-]+)")
           second))

#?(:clj
   (defn ^:no-doc inflated
     "The `body` bytes of the response with `headers`, gunzipped when the
     response says so, to at most `max-bytes`, or without a limit for nil."
     ^bytes [headers ^bytes body max-bytes]
     (if (gzipped? headers)
       (gunzip body max-bytes nil)
       body)))

#?(:clj
   (defn decode
     "The `body` bytes of the response with `headers` as a string,
     gunzipped to at most `max-bytes` when the response says so.

     With nil `max-bytes`, there's no limit. The body is decoded as
     util.xml/decode decodes a document, with the charset that the
     Content-Type names."
     ([headers body]
      (decode headers body nil))
     ([headers ^bytes body max-bytes]
      (xml/decode (inflated headers body max-bytes) (charset headers)))))

;; RFC 9110, section 14.4
(defn content-range
  "The byte range and the size of the whole file that the Content-Range
  header value `s` names, e.g. bytes 0-1023/146515, as a map of :start,
  :end and :total.

  It's nil when `s` doesn't read, and the :total is nil when the server
  doesn't know the size."
  [s]
  (let [pattern #"(?i)\s*bytes\s+(\d{1,15})-(\d{1,15})/(\d{1,15}|\*)\s*"]
    (when-let [[_ start end total] (some->> s (re-matches pattern))]
      {:start (parse-long start)
       :end   (parse-long end)
       :total (when (not= "*" total)
                (parse-long total))})))

(defn- byte-range
  "The range that `spec`, one byte range of a Range header, asks for, as
  byte-ranges gives it, or nil when it doesn't read."
  [spec]
  (when-let [[_ start end] (re-matches #"(\d{0,15})\s*-\s*(\d{0,15})" spec)]
    (cond
      (and (seq start) (seq end))
      (let [a (parse-long start)
            b (parse-long end)]
        (when (<= a b) [a b]))

      (seq start)
      [(parse-long start) nil]

      (seq end)
      [nil (parse-long end)])))

(defn byte-ranges
  "The byte ranges that the Range header value `s` asks for, as RFC 9110,
  section 14.1.2, reads them. Nil when `s` doesn't read, because a server
  then ignores it.

  A value such as bytes=0-1023, 5000- asks for two ranges. A range is a
  pair of its start and its end, with the end included, or nil for the
  rest of the file. A nil start and an end of n ask for the last n
  bytes."
  [s]
  (when-let [[_ specs] (some->> s (re-matches #"(?i)\s*bytes\s*=\s*(.*)"))]
    (let [ranges (for [spec (map str/trim (str/split specs #","))
                       :when (seq spec)]
                   (byte-range spec))]
      (when (and (seq ranges)
                 (every? some? ranges))
        (vec ranges)))))

#?(:clj
   (defn- java-request
     "The java.net.http request of `method` to `uri`, with `headers`, the
     `body` string and a timeout of `timeout-ms` milliseconds.

     Gzip is accepted unless the headers name an Accept-Encoding. A header
     and a body that are hidden texts go out as their texts."
     ^HttpRequest [^URI uri method headers body timeout-ms]
     (let [gzip?   (not-any? #(= "accept-encoding" (str/lower-case (str %)))
                             (keys headers))
           add     (fn [^HttpRequest$Builder b k v]
                     (.header b (str k) (str (secret/reveal v))))
           builder (cond-> (.timeout (HttpRequest/newBuilder uri)
                                     (Duration/ofMillis timeout-ms))
                     gzip? (.header "Accept-Encoding" "gzip"))
           builder (reduce-kv add builder headers)]
       (-> (if (or (nil? method) (= :get method))
             (.GET ^HttpRequest$Builder builder)
             (.method ^HttpRequest$Builder builder
                      (str/upper-case (name method))
                      (let [body (secret/reveal body)]
                        (cond
                          (nil? body)   (HttpRequest$BodyPublishers/noBody)
                          (bytes? body) (HttpRequest$BodyPublishers/ofByteArray
                                         body)
                          :else         (HttpRequest$BodyPublishers/ofString
                                         (str body))))))
           (.build)))))

(defn ^:no-doc timeout?
  "Whether the exception `e` from send!, or one of its causes, is a
  timeout."
  [e]
  (let [cause  #?(:clj ex-cause :cljs #(.-cause %))
        causes (take-while some? (iterate cause e))]
    (boolean (some #(or (= ::timeout (:type (ex-data %)))
                        #?(:clj  (instance? HttpTimeoutException %)
                           :cljs (= "TimeoutError" (.-name %))))
                   causes))))

#?(:clj
   (defn- redirect-target
     "Where the redirect `response` to `uri` leads, or nil when it names no
     http or https URL with a host. It's nil too when it leaves https for
     http, which the client of the JDK refuses as well."
     [^URI uri ^HttpResponse response]
     (when-let [location (-> (.headers response)
                             (.firstValue "location")
                             (.orElse nil))]
       (when-let [target (some-> (url/absolute-url (str uri) location) (URI.))]
         (let [from (some-> (.getScheme uri) str/lower-case)
               to   (some-> (.getScheme target) str/lower-case)]
           (when (and (#{"http" "https"} to)
                      (.getHost target)
                      (not (and (= "https" from) (= "http" to))))
             target))))))

(defn ^:no-doc refusal
  "The exception of a request to `url` that :allow? refused."
  [url]
  (ex-info (str "The request to " url " is not allowed")
           {:type ::refused :url url}))

(def ^:no-doc credential-headers
  "The names of the headers that hold credentials, in lower case."
  #{"authorization" "cookie" "proxy-authorization"})

;; RFC 9110, section 15.4
(defn ^:no-doc redirected
  "The request that follows `request` after a redirect of `status` to the
  URL `target`.

  A 303, or a 301 or 302 to a POST, turns into a GET without a body, as
  browsers and the JDK do. A HEAD stays a HEAD. Credentials stay behind
  when the redirect leaves the origin."
  [{:keys [method url headers body] :as request} status target]
  (let [get?        (or (= 303 status)
                        (and (#{301 302} status) (= :post method)))
        credential? (fn [[k _]]
                      (credential-headers (str/lower-case (str k))))]
    (assoc request
           :url     target
           :method  (if (and get? (not= :head method)) :get method)
           :headers (cond->> headers
                      (not (url/same-origin? url target))
                      (into {} (remove credential?)))
           :body    (when-not get? body))))

#?(:clj
   (defn ^:no-doc send-following!
     "Send the `request`, follow its redirects until the instant `deadline`
     in epoch milliseconds, and check each URL with `allow?`.

     It gives the last response and the redirects before it, each as a
     vector of its status, URL and headers. It follows five redirects at
     most, each as redirected says, since the JDK sends credentials on to
     another origin. A URL that `allow?` refuses throws ::refused."
     [request deadline allow?]
     (loop [request (update request :url url/legal-url)
            chain   []]
       (let [{:keys [url method headers body]} request
             uri  (URI. url)
             left (- deadline (System/currentTimeMillis))]
         (when-not (pos? left)
           (throw (HttpTimeoutException. (str "request timed out: " uri))))
         (when-not (allow? url)
           (throw (refusal url)))
         (let [outgoing  (java-request uri method headers body left)
               response  (.send ^HttpClient @client outgoing
                                (HttpResponse$BodyHandlers/ofInputStream))
               status    (.statusCode response)
               redirect? (and (#{301 302 303 307 308} status)
                              (< (count chain) 5))
               target    (when redirect?
                           (redirect-target uri response))]
           (if-not target
             [response chain]
             (do (.close ^InputStream (.body response))
                 (recur (redirected request status (str target))
                        (conj chain [status url (headers-of response)])))))))))

#?(:clj
   (defn- permanent-url
     "The URL that the first request moved to for good, by the redirects in
     `chain` before `final-url`, or nil when the first isn't permanent.

     A redirect is permanent when it's a 301 or a 308, and the URL is the
     one that the permanent redirects at the start of the chain lead to."
     [chain final-url]
     (let [n (count (take-while #(#{301 308} (first %)) chain))]
       (when (pos? n)
         (get (conj (mapv second chain) final-url) n)))))

(defn ^:no-doc prefix-length
  "How much of the body of an answer of `status` to read for a request of
  the bytes `range`, or nil to read all of it. The answer sends from
  `start`, it can be `gzipped?`, and the body is read to `max-bytes`.

  - Of a 206, it's the bytes from the start to the end of the range,
    since a server can send more than was asked.
  - Of a 200 that isn't gzipped, which is the whole file as it is, it's
    the bytes from its start to the end of the range.
  - Of any other answer that isn't gzipped, e.g. the page of a 404, it's
    as many bytes as the range names.

  It's nil when the range has no end, or when the prefix is longer than
  `max-bytes`."
  [status start gzipped? range max-bytes]
  (let [end  (second range)
        from (cond
               (= 206 status) (or start (first range))
               gzipped?       nil
               (= 200 status) 0
               :else          (first range))]
    (when (and end from)
      (let [n (max 0 (inc (- end from)))]
        (when (<= n (or max-bytes 0x7FFFFFFF))
          n)))))

#?(:cljs
   (def ^:no-doc browser?
     "Whether the code runs in a page or a worker of a browser. There, a
     header that a request sets can make fetch send a preflight request
     first, and the browser sends its own User-Agent."
     (or (exists? js/document) (exists? js/WorkerGlobalScope))))

(defn- request-headers
  "The `headers` of a request of send! for the bytes `range`, with the
  Range header, and with user-agent unless they name another or the
  browser sends its own.

  On the JVM, a request for a range asks for bytes that aren't gzipped,
  so that the offsets are those of the file."
  [headers range]
  (cond-> (merge #?(:clj  {"User-Agent" user-agent}
                    :cljs (when-not browser? {"User-Agent" user-agent}))
                 headers)
    range (assoc "Range" (str "bytes=" (first range) "-" (second range)))
    #?@(:clj [range (assoc "Accept-Encoding" "identity")])))

#?(:clj
   (defn- response-bytes
     "The bytes of the body of `response` to `request`, read until
     `deadline`, as much as prefix-length says."
     ^bytes [^HttpResponse response
             {:keys [method url range max-bytes] :as request}
             deadline]
     (let [headers (headers-of response)
           start   (:start (content-range (header headers "content-range")))
           n       (prefix-length (.statusCode response) start
                                  (gzipped? headers) range max-bytes)]
       (with-open [^InputStream in (.body response)]
         (cond
           ;; an answer to HEAD has no content by RFC 9110, section
           ;; 9.3.2, though some servers send one over HTTP/2
           (= :head method) (byte-array 0)
           n                (within in deadline url #(.readNBytes in (int n)))
           :else            (read-within in deadline max-bytes url))))))

#?(:clj
   (defn- java-answer
     "The response map of send! for the `response` of the JDK to `request`,
     with the body `bytes`, which came after the redirects `chain`."
     [^HttpResponse response ^bytes bytes chain
      {:keys [as max-bytes] :as request}]
     (let [status   (.statusCode response)
           headers  (headers-of response)
           final    (str (.uri response))
           moved    (permanent-url chain final)
           received (content-range (header headers "content-range"))]
       (cond-> {:status  status
                :url     final
                :headers headers
                :body    (when (and (not= 304 status) (pos? (alength bytes)))
                           (if (= :bytes as)
                             (inflated headers bytes max-bytes)
                             (decode headers bytes max-bytes)))}
         moved       (assoc :permanent-url moved)
         (seq chain) (assoc :redirects chain)
         received    (assoc :content-range received)))))

#?(:cljs
   (defn- read-body
     "A promise of the body of the fetch response `res` from `url` as
     bytes, as much as `opts` say:

     - With :first, it's that many bytes from the start, and the rest is
       left unread.
     - With :max-bytes, it's the whole body, and the promise is rejected
       with ::too-large once more bytes arrive.
     - Without either, it's the whole body."
     [res url {:keys [max-bytes] :as opts}]
     (let [reader (.getReader (.-body res))
           n      (or (:first opts) max-bytes ##Inf)
           all?   (nil? (:first opts))]
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
                   (and all? (> got n))
                   (do (.catch (.cancel reader) (fn [_] nil))
                       (throw (too-large url n)))

                   (and (not all?) (>= got n))
                   (join chunks)

                   :else
                   (.then (.read reader)
                          (fn [r]
                            (if (.-done r)
                              (join chunks)
                              (let [chunk (.-value r)]
                                (step (conj chunks chunk)
                                      (+ got (.-length chunk)))))))))]
         (js/Promise.resolve (step [] 0))))))

#?(:cljs
   (defn- fetch-answer
     "A promise of the response map of send! for the fetch response `res`
     to the `request`."
     [res {:keys [method url as range max-bytes] :as request}]
     (let [status   (.-status res)
           received (content-range (.get (.-headers res) "content-range"))
           headers  (into {}
                          (map (fn [[k v]] [k [v]]))
                          (es6-iterator-seq (.entries (.-headers res))))
           response (cond-> {:status  status
                             :url     (.-url res)
                             :headers headers}
                      received                (assoc :content-range received)
                      (= "cors" (.-type res)) (assoc :filtered? true))
           ;; fetch undoes gzip itself, so a prefix is one of the file
           n        (prefix-length status (:start received) false range
                                   max-bytes)]
       (if (or (= :head method) (nil? (.-body res)))
         response
         (.then (read-body res url (if n {:first n} {:max-bytes max-bytes}))
                (fn [b]
                  (cond-> response
                    (pos? (.-length b))
                    (assoc :body (if (= :bytes as)
                                   b
                                   (xml/decode b (charset headers)))))))))))

(defn send!
  "Send the `request`, and give the response as a map of :status, :url,
  :headers and :body. On the JVM it gives the response, and in
  ClojureScript a promise of it.

  The request is a map of :method, :url, :headers and :body. It's a GET
  unless :method names another method, and it goes with the :body, a
  string or bytes, when it has one. A header and a body can be hidden texts of
  util.secret, and they go out as their texts. The body of the response
  is text, with gzip undone. It's absent for a HEAD request and for an
  empty body.

  The `request` can also have these keys:

  - :as :bytes, for a body of bytes, e.g. of a media file
  - :timeout-seconds, the limit of the whole exchange, 30 by default
  - :max-bytes, the limit of the body, default-max-bytes by default, or
    nil for no limit
  - :range, a pair of the first and the last byte to ask for, as RFC
    9110, section 14.1.2, has it. A nil last byte asks for the rest.
  - :allow?, a predicate of a URL, as util.url/public? is

  An exchange that goes beyond a limit throws ::timeout or ::too-large.
  On the JVM, the client of the JDK throws its HttpTimeoutException while
  it waits for the headers. When :allow? refuses the URL of the request,
  it throws ::refused.

  A server that sends the range answers 206, and :content-range is then
  what content-range reads. A server that ignores ranges answers 200 with
  the whole file. The body is read no further than the end of the range,
  when it has one. From another answer, e.g. the page of a 404, no more
  bytes are read than the range names.

  On the JVM, the URL is made legal first, as url/legal-url does. Redirects
  are followed, five at most, and never from https to http. A redirect to
  another origin leaves the credentials behind, and :allow? must take the
  URL of each redirect. The :redirects of the response list each redirect
  as a vector of its status, the URL that answered with it, and the
  headers of its answer. When the redirects begin with permanent ones,
  :permanent-url is where the URL moved for good.

  In ClojureScript, fetch sends the request. It follows redirects itself
  and doesn't report them, so :allow? doesn't see them. A browser lets a
  page read an answer from another site only as far as the CORS headers
  of the answer allow. It hides the headers that the answer doesn't
  expose, e.g. Content-Range and ETag, and :filtered? marks such an
  answer. Outside a browser, e.g. in Node, the request has user-agent as
  its User-Agent, unless the :headers name another."
  [{:keys [method headers timeout-seconds allow?]
    :or   {timeout-seconds 30 allow? (constantly true)}
    :as   request}]
  (let [method  (some-> method name str/lower-case keyword)
        request (assoc (merge {:max-bytes default-max-bytes} request)
                       :method  method
                       :headers (request-headers headers (:range request)))]
    #?(:clj  (let [deadline         (+ (System/currentTimeMillis)
                                       (* 1000 timeout-seconds))
                   [response chain] (send-following! request deadline allow?)
                   bytes            (response-bytes response request deadline)]
               (java-answer response bytes chain request))
       :cljs (let [{:keys [url body]} request]
               (if-not (allow? url)
                 (js/Promise.reject (refusal url))
                 ;; a hidden header and a hidden body go out as their texts
                 (let [signal (js/AbortSignal.timeout (* 1000 timeout-seconds))
                       verb   (str/upper-case (name (or method :get)))
                       init   (cond-> {:method  verb
                                       :headers (:headers (revealed request))
                                       :signal  signal}
                                (some? body)
                                (assoc :body (secret/reveal body)))]
                   (-> (js/fetch url (clj->js init))
                       (.then #(fetch-answer % request)))))))))

(defn send-public!
  "Send the `request` with send!, but only to a host on the public
  internet, as url/public? tells. On the JVM, the host of each redirect must
  be public too.

  It sends no request to the network of the app or of its device, and
  throws ::refused instead, or in ClojureScript rejects with it. The
  functions that fetch URLs from outside sources use the same check as
  their :allow?."
  [request]
  (send! (assoc request :allow? url/public?)))

(def ^:no-doc limit-keys
  "The keys of a request that limit what send! does for it, which the
  functions that send take from their options."
  [:timeout-seconds :max-bytes :allow?])

(defn fetch-request
  "The request for the document at `url` that fetch! sends with `opts`,
  the options of fetch!.

  A user name and a password in `url` are the credentials when `opts`
  give none, and the request goes to the URL without them, as
  url/split-credentials splits them."
  ([url]
   (fetch-request url {}))
  ([url {:keys [etag last-modified credentials user-agent accept]
         :or   {user-agent dk.simongray.podcast.util.http/user-agent
                accept     "*/*"}
         :as   opts}]
   (let [[url in-url] (url/split-credentials url)
         auth         (authorization (or credentials in-url))]
     (assoc (select-keys opts limit-keys)
            :url     url
            :headers (cond-> {"User-Agent" user-agent
                              "Accept"     accept}
                       etag          (assoc "If-None-Match" etag)
                       last-modified (assoc "If-Modified-Since" last-modified)
                       auth          (assoc "Authorization" auth))))))

(defn fetch!
  "GET the document at `url` with `opts`, and give the response with the
  body as text, in ClojureScript as a promise.

  The request is the one that fetch-request gives. The response also has
  :etag and :last-modified when the server gives them. It has :not-before
  when the server says how long to wait, as not-before reads it.

  The `opts` are these:

  - :etag and :last-modified from an earlier response. They go as
    If-None-Match and If-Modified-Since, and a 304 then comes back with
    no body.
  - :credentials, as authorization takes them
  - :user-agent, to send in place of user-agent
  - :accept, the Accept header, */* by default
  - :timeout-seconds, :max-bytes and :allow?, as send! takes them
  - :send, the function that sends the request, send! by default"
  ([url]
   (fetch! url {}))
  ([url {:keys [send] :or {send send!} :as opts}]
   (async/then (send (fetch-request url opts))
               (fn [{:keys [headers] :as response}]
                 (let [wait          (not-before headers (time/now))
                       etag          (header headers "etag")
                       last-modified (header headers "last-modified")]
                   (cond-> (assoc response
                                  :etag          etag
                                  :last-modified last-modified)
                     wait (assoc :not-before wait)))))))

#?(:clj
   (defn digest!
     "GET `url` with `opts`, and give the digests of its body by the
     MessageDigest `algorithms`, e.g. SHA-384.

     The result has the :status and the :url. For a 200, it also has the
     :digests, which map each algorithm to the bytes of its digest. The
     body streams through the digests, and none of it is kept, so it can
     be a large media file.

     The `opts` are these:

     - :headers of the request
     - :allow?, as send! takes it
     - :timeout-seconds, the limit of the whole exchange, 600 by default"
     ([url algorithms]
      (digest! url algorithms {}))
     ([url algorithms {:keys [headers timeout-seconds allow?]
                       :or   {timeout-seconds 600 allow? (constantly true)}
                       :as   opts}]
      (let [deadline       (+ (System/currentTimeMillis)
                              (* 1000 timeout-seconds))
            ;; before the request, since an algorithm that the platform lacks
            ;; throws
            digests        (into {} (for [a algorithms]
                                      [a (MessageDigest/getInstance a)]))
            headers        (merge {"User-Agent"      user-agent
                                   "Accept-Encoding" "identity"}
                                  headers)
            request        {:url url :method :get :headers headers}
            [response _]   (send-following! request deadline allow?)
            status         (.statusCode ^HttpResponse response)
            update-digests (fn [^bytes buffer n]
                             (doseq [^MessageDigest d (vals digests)]
                               (.update d buffer 0 (int n))))]
        (with-open [^InputStream in (.body ^HttpResponse response)]
          (when (= 200 status)
            (within in deadline url #(pump in nil url update-digests))))
        (cond-> {:status status :url (str (.uri ^HttpResponse response))}
          (= 200 status)
          (assoc :digests (update-vals digests
                                       #(.digest ^MessageDigest %))))))))

(defn- whole-size
  "The size of the file whose first `n` bytes came in a 200 to a request
  for the first `asked`, by the `content-length` and `content-encoding`
  of the answer.

  A 200 is the answer of a server that ignores ranges. The size is `n`
  when that's less than asked, since the file ended. Otherwise it's the
  content length, unless the content encoding changed the size."
  [n asked content-length content-encoding]
  (let [coding (some-> content-encoding str/trim str/lower-case)]
    (cond
      (< n asked)
      n

      (contains? #{nil "identity"} coding)
      (some-> content-length str/trim parse-long))))

(defn- unsatisfied-total
  "The size of the whole file that the Content-Range value `s` of an
  answer of 416 gives, or nil when it gives none. RFC 9110, section 14.4,
  defines the value."
  [s]
  (some->> s (re-matches #"(?i)\s*bytes\s+\*/(\d{1,15})\s*") second parse-long))

(defn ^:no-doc limited
  "The `request` with :timeout-seconds, :max-bytes and :allow? from
  `opts`, each when the opts give it."
  [request opts]
  (into request (remove (comp nil? val)) (select-keys opts limit-keys)))

(defn ^:no-doc failed
  "The exception for the answer to a request for the file at `url` whose
  `status` is a failure."
  [url status]
  (ex-info (str "The file answered " status ": " url)
           {:type ::failed :status status :url url}))

(defn fetcher
  "A function that fetches the bytes of the file at `url` by range, with
  `opts`, as util.bytes.view/read! takes it.

  The function takes a start and an end, with the end excluded. It gives
  a map of :bytes, :start and :total, or in ClojureScript a promise of
  it. You can wrap it to log or keep what it fetches. It throws as send!
  does, and it throws ::failed with the :status for an answer that's a
  failure.

  A server that ignores ranges sends the file from its start. Then only
  the bytes up to the end are read, and the answer is :whole?. A server
  answers 416 for a range past the end of the file. Then the function
  gives no bytes, and the size of the file, so that a reader can tell
  that the file is shorter than it says. In a browser, the total is known
  only when the server exposes Content-Range.

  The `opts` are these:

  - :headers, :timeout-seconds, :max-bytes and :allow? of the requests
  - :send, a function that sends a request map in place of send!, as
    send! does"
  ([url]
   (fetcher url {}))
  ([url {:keys [headers send] :or {send send!} :as opts}]
   (fn [start end]
     (let [request {:url     url
                    :headers headers
                    :as      :bytes
                    :range   [start (dec end)]}]
       (async/then
        (send (limited request opts))
        (fn [{:keys [status body content-range] :as response}]
          (let [body (or body (bytes/of []))
                h    #(header (:headers response) %)]
            (case (long status)
              206 {:bytes body
                   :start (or (:start content-range) start)
                   :total (:total content-range)}
              200 {:bytes  body
                   :start  0
                   :whole? true
                   :total  (whole-size (bytes/length body) end
                                       (h "content-length")
                                       (h "content-encoding"))}
              ;; the body of a 416 is at most a page about the error
              416 {:bytes (bytes/of [])
                   :start start
                   :total (unsatisfied-total (h "content-range"))}
              (throw (failed url status))))))))))

(defn ^:no-doc read-by-ranges!
  "Read the file at `url` with the reader `read` and `opts`, and give what
  util.bytes.view/read! gives, or in ClojureScript a promise of it.

  The bytes come by the :fetch of the opts, or by a fetcher of the opts.
  The opts also give the limits of the reading, with view/default-limits
  for those that they don't set. The :error of a file that didn't come
  names the `url`."
  [url read {:keys [fetch] :as opts}]
  (let [opts (merge view/default-limits opts)]
    (async/then (view/read! (or fetch (fetcher url opts)) read opts)
                #(cond-> % (:error %) (assoc-in [:error :url] url)))))

(defn media-type
  "The media type of the Content-Type value `s`, or of a type attribute,
  or nil when `s` names none.

  The media type is in lower case, without its parameters."
  [s]
  (some-> s (str/split #";" 2) first str/trim str/lower-case not-empty))

(defn- cors
  "The origins that the Access-Control-Allow-Origin of the response
  `headers` allows to read it, with all its values joined.

  A browser reads them joined too, so no page can read an answer that
  has two."
  [headers]
  (joined headers "access-control-allow-origin"))

(defn facts
  "What the `response` of send! tells about its file, as far as it tells
  it.

  The facts have these keys:

  - :status, :url and :permanent-url, as in the response
  - :redirects, each a map of :status, :url and :cors, with the URL that
    answered with it
  - :location, the Location of a redirect that wasn't followed
  - :type, the media type, as media-type reads it
  - :length, what Content-Length gives
  - :range, the Content-Range, as content-range reads it
  - :encoding, :etag and :last-modified, from their headers
  - :cors, the origins that Access-Control-Allow-Origin allows to read
    the answer
  - :filtered?, as in the response"
  [response]
  (let [h        #(header (:headers response) %)
        redirect (fn [[status url headers]]
                   (let [allowed (cors headers)]
                     (cond-> {:status status :url url}
                       allowed (assoc :cors allowed))))
        length   (some->> (h "content-length")
                          str/trim
                          (re-matches #"\d{1,15}")
                          parse-long)]
    (into {} (remove (comp nil? val))
          {:status        (:status response)
           :url           (:url response)
           :redirects     (not-empty (mapv redirect (:redirects response)))
           :permanent-url (:permanent-url response)
           :location      (h "location")
           :type          (media-type (h "content-type"))
           :length        length
           :range         (:content-range response)
           :encoding      (h "content-encoding")
           :etag          (h "etag")
           :last-modified (h "last-modified")
           :cors          (cors (:headers response))
           :filtered?     (:filtered? response)})))

(defn try-send!
  "Send the `request` with `send`, a function such as send!, and give a
  map of the response as the :value, or of the exception as the :error
  where `send` throws. The `opts` are the :timeout-seconds, :max-bytes
  and :allow? to put in the request.

  The map comes in a future on the JVM and in a promise in ClojureScript,
  as async/attempt gives it, so several requests can be under way at
  once."
  ([send request]
   (try-send! send request {}))
  ([send request opts]
   (async/attempt #(send (limited request opts)))))

(defn ^:no-doc sent-facts
  "The facts of the response of an `attempt` from try-send!, or a map with a
  diagnostic under :error for a request that failed."
  [{:keys [value error] :as attempt}]
  (if error
    {:error (view/unreachable error)}
    (facts value)))

(defn probe!
  "Ask the server about the file at `url` with `opts`, and give the facts,
  while no more than two bytes of the file move. On the JVM it gives
  them, and in ClojureScript a promise of them.

  The result is a map of :head and :get, the facts of the answers to a
  HEAD request and to a request for the first two bytes. The facts of a
  request that failed are a map with the diagnostic :file/unreachable
  under :error, which dk.simongray.podcast.diagnostics/describe gives its
  message.

  Most servers answer the second request with 206, and with the size of
  the whole file in Content-Range. A server that ignores ranges answers
  200, and two bytes of that answer are read. The HEAD request asks for
  the file as it is, not gzipped, so that its Content-Length is the size
  of the file. The two requests go one after the other, so a server sees
  one at a time for one file.

  The `opts` are these:

  - :headers of the two requests, e.g. an Origin to learn about CORS
  - :timeout-seconds, :max-bytes and :allow? of the requests
  - :send, a function that sends a request map in place of send!, as
    send! does"
  ([url]
   (probe! url {}))
  ([url {:keys [headers send] :or {send send!} :as opts}]
   (let [answer!       (fn [request]
                         (-> (try-send! send (assoc request :url url) opts)
                             (async/then sent-facts)))
         head-request  {:method  :head
                        :headers (merge {"Accept-Encoding" "identity"} headers)}
         range-request {:range [0 1] :as :bytes :headers headers}]
     (async/then (answer! head-request)
                 (fn [head]
                   (async/then (answer! range-request)
                               (fn [got] {:head head :get got})))))))

(defn ^:no-doc request-bytes
  "The bytes of the `body` of a Ring request, which is a string, bytes, a
  stream or nothing. Nil when the body holds more than `max-bytes`, and
  for a stream in ClojureScript."
  [body max-bytes]
  (let [b (cond
            (nil? body)
            (bytes/of [])

            (string? body)
            (bytes/encode body)

            :else
            #?(:clj  (if (bytes? body)
                       body
                       (.readNBytes ^InputStream body (int (inc max-bytes))))
               :cljs (when (instance? js/Uint8Array body) body)))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn ^:no-doc body-bytes
  "The bytes of the `body` of a Ring request, which is a string, a stream
  or nothing. Nil when the body holds more than `max-bytes`, and for a
  stream in ClojureScript."
  [body max-bytes]
  (let [b (cond
            (nil? body)    (bytes/encode "")
            (string? body) (bytes/encode body)
            :else          #?(:clj  (.readNBytes ^InputStream body
                                                 (int (inc max-bytes)))
                              :cljs nil))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn ^:no-doc body-text
  "The text of the `body` of a Ring request, as body-bytes reads it with
  `max-bytes`, of UTF-8."
  [body max-bytes]
  (when-let [b (body-bytes body max-bytes)]
    (bytes/decode b 0 (bytes/length b) :utf8)))

(comment
  (authorization {:username "ann" :password "secret"})
  (byte-ranges "bytes=0-1023, 5000-")

  ;; an hour at most, though the server says a day
  (not-before {"cache-control" "max-age=86400"} #inst "2026-09-29T12:00:00Z")

  ;; a POST that a 303 turns into a GET, and credentials that stay behind
  (redirected {:method  :post
               :url     "https://a.example/x"
               :headers {"Authorization" "Basic …"}
               :body    "{}"}
              303
              "https://b.example/y")

  (send! {:url "https://example.org/episode.mp3" :range [0 1023] :as :bytes})
  (probe! "https://example.org/episode.mp3")
  #_.)

#?(:clj
   (comment
     (select-keys (fetch! "https://example.org/feed.xml")
                  [:status :url :etag :last-modified])
     #_.))
