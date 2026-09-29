(ns dk.simongray.podcast.http
  "Requests as maps, and the client that sends them.

  On the JVM, the client is java.net.http. It follows redirects, accepts
  gzip, makes conditional requests, and decodes the body by the charset
  that the server or the document declares. In ClojureScript, the client
  is fetch.

  A request is `{:method :url :headers :body}`. It is a GET unless
  `:method` names another method. The directories and Podping build
  requests of this shape, and any HTTP client can send them.
  `query-string` encodes the parameters of a request the same way on both
  platforms. A response is a plain map. The `:url` of a response is its
  final URL after redirects.

  A request moves no more bytes than its purpose needs. `probe!` learns
  what a server says about a file from two bytes of it. `fetcher` fetches
  a file by byte ranges, for the readers of media files and images."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.time :as time])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream IOException]
                   [java.net IDN InetAddress URI]
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

(defn ^:no-doc query-params
  "The parameters of the query string `s`, decoded, by name. A plus sign
  is a space, as in the body of a form."
  [s]
  (into {} (for [part  (str/split (str s) #"&")
                 :let  [[k v] (map #(percent-decode (str/replace (str %) "+" " ")) (str/split part #"=" 2))]
                 :when (seq k)]
             [k v])))

(defn with-query
  "The URL `url` with `params` added to its query, as `query-string` writes
  them. The parameters go after the parameters that the URL has, and
  before its fragment."
  [url params]
  (let [[_ base fragment] (re-matches #"(?s)([^#]*)(#.*)?" (str url))
        query             (query-string params)
        joint             (cond
                            (not (str/includes? base "?")) "?"
                            (re-find #"[?&]$" base)        ""
                            :else                          "&")]
    (if (str/blank? query)
      (str url)
      (str base joint query fragment))))

(defn ^:no-doc base64
  "The UTF-8 bytes of `s` in base64."
  [s]
  (bytes/base64 (bytes/encode s)))

(defn authorization
  "The Authorization header for `credentials`. Nil for no credentials.

  `credentials` is a map, or a function of no arguments that gives one.
  The map is `{:username :password}` for basic authentication, or
  `{:token}` for a bearer token."
  [credentials]
  (let [{:keys [username password token]} (if (fn? credentials) (credentials) credentials)]
    (cond
      token    (str "Bearer " token)
      username (str "Basic " (base64 (str username ":" password)))
      :else    nil)))

(defn ^:no-doc header
  "The first value of `header-name` in the response headers map `headers`,
  whose values are strings or vectors of them."
  [headers header-name]
  (let [v (get headers (str/lower-case header-name))]
    (if (string? v) v (first v))))

(defn ^:no-doc joined
  "All the values of `header-name` in the response headers map `headers`,
  joined by commas as fetch joins them; nil for none."
  [headers header-name]
  (let [v (get headers (str/lower-case header-name))]
    (if (string? v) v (some->> (seq v) (str/join ", ")))))

(defn- link-of
  "The link that the `tokens` of one value of a Link header give: its
  target, and then its parameters, each after a semicolon. Nil when the
  value starts with no target."
  [[target & params]]
  (when (and target (re-find #"^<\s*[^\s>]" target))
    (reduce (fn [link [k eq v]]
              (let [k (keyword (str/lower-case k))]
                (cond-> link
                  (and (= "=" eq) v (not (contains? link k)))
                  (assoc k (str/replace v #"^\"|\"$" "")))))
            {:href (str/trim (subs target 1 (dec (count target))))}
            (remove #(= ";" (first %)) (partition-by #(= ";" %) params)))))

(defn links
  "The links of the Link header in the response headers map `headers`, as
  section 3 of RFC 8288 defines them. Each link is a map with `:href`,
  its target, and with its parameters by name, such as `:rel` and
  `:type`.

  The links are in the order of the header. The first value of a
  parameter counts, and a target can be relative to the URL of the
  response."
  [headers]
  (->> (re-seq #"<[^>]*>|\"[^\"]*\"|[;,=]|[^\s;,=<\"]+" (str (joined headers "link")))
       (partition-by #(= "," %))
       (keep link-of)
       vec))

(defn ^:no-doc seconds
  "The whole number of seconds that the header value `s` gives. Nil when
  it gives none."
  [s]
  (some->> s str/trim (re-matches #"\d{1,10}") parse-long))

(defn ^:no-doc date-ms
  "The date that the header value `s` gives, in epoch milliseconds. Nil
  when it gives none."
  [s]
  (some-> s time/parse-rfc2822 inst-ms))

(defn ^:no-doc ours
  "The time `t` on the clock of the server, as a time on the clock of the
  client. The response that arrived at `now` names `served` as the time
  that the server sent it. Without `served`, the clocks count as one."
  [t now served]
  (if served (+ now (- t served)) t))

(defn- retry-after
  "When the Retry-After of `headers` says to ask again, in epoch
  milliseconds, as `ours` reads a date. Nil when it gives no time."
  [headers now served]
  (let [retry (header headers "retry-after")]
    (or (some->> (seconds retry) (* 1000) (+ now))
        (some-> (date-ms retry) (ours now served)))))

(defn- fresh-until
  "When the freshness of the response with `headers` ends, in epoch
  milliseconds: by its max-age less its Age, or else by its Expires, as
  `ours` reads a date. Nil when the headers forbid a cache or name no
  time."
  [headers now served]
  (let [age   (* 1000 (or (seconds (header headers "age")) 0))
        cache (some-> (joined headers "cache-control") str/lower-case)]
    (when-not (some->> cache (re-find #"no-cache|no-store"))
      (if-let [max-age (some->> cache (re-find #"max-age=(\d{1,10})") second parse-long)]
        (- (+ now (* 1000 max-age)) age)
        (some-> (date-ms (header headers "expires")) (ours now served) (- (if served age 0)))))))

(defn not-before
  "The earliest instant to ask again, by the response `headers` that arrived
  at `at`. Nil when the headers name no later time, or forbid a cache.

  The instant is the first of these that the headers give:

  - the `Retry-After`, in seconds or as a date
  - the end of the freshness that `Cache-Control: max-age` gives, less
    the `Age`
  - the `Expires`

  A date counts from the `Date` of the response, as RFC 9111 says. So
  the clock of the server and a wrong time zone do not matter. Without a
  `Date`, a date counts from `at`.

  The instant is at most an hour after `at`. A feed host can give its
  feed a freshness of days and still publish within them, and a server
  that is set up wrong must not hold back the polling.

  `headers` maps lower-case names to a value or a vector of values, as
  the responses of `call!` do. A `Retry-After` that does not read counts
  as none. The values of all the `Cache-Control` headers count, as RFC
  9110 joins them."
  [headers at]
  (let [now    (inst-ms at)
        served (date-ms (header headers "date"))
        until  (or (retry-after headers now served)
                   (fresh-until headers now served))]
    (when (and until (> until now))
      (time/->inst (min until (+ now (* 60 60 1000)))))))

(defn split-credentials
  "The URL `url` without the user name and the password that it holds, and
  those as credentials for `authorization`: `[url credentials]`. The
  credentials are nil when the URL holds none.

  Such a URL is `https://user:pass@host/feed.xml`. An app can use this
  when it adds a feed, to keep the password apart from the address. An
  address travels into OPML, sync documents and messages."
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
   (defn ^:no-doc gzipped?
     "Whether the response with `headers` says that its body is gzipped."
     [headers]
     (= "gzip" (some-> (header headers "content-encoding") str/trim str/lower-case))))

#?(:clj
   (defn ^:no-doc headers-of
     "The headers of `response`, lower-case names to vectors of values."
     [^HttpResponse response]
     (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)]))
           (.map (.headers response)))))

(def ^:no-doc default-max-bytes
  "How much of a response body `call!` reads before giving up, unless told
  otherwise: 100 MB."
  (* 100 1024 1024))

#?(:clj
   (defn ^:no-doc pump
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
   (defn ^:no-doc read-bounded
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
   (defn ^:no-doc within
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
   (defn ^:no-doc inflated
     "The `body` bytes of the response with `headers`, gunzipped when the
     response says so, to at most `max-bytes` (nil for no limit)."
     ^bytes [headers ^bytes body max-bytes]
     (if (gzipped? headers)
       (gunzip body max-bytes nil)
       body)))

#?(:clj
   (defn decode
     "The `body` bytes of the response with `headers` as a string.

     The body is gunzipped when the response says so, to at most
     `max-bytes` (nil for no limit). It is decoded by the charset that the
     response declares, or else the XML declaration, or else as UTF-8. A
     byte order mark is left out, as a browser leaves it out."
     ([headers body]
      (decode headers body nil))
     ([headers ^bytes body max-bytes]
      (let [body (inflated headers body max-bytes)
            s    (String. body ^Charset (declared-charset headers body))]
        (cond-> s (str/starts-with? s "\uFEFF") (subs 1))))))

(defn content-range
  "The byte range and the size of the whole file that the Content-Range
  header value `s` names, as `{:start :end :total}`. Nil when `s` does not
  read.

  Such a value is `bytes 0-1023/146515`. `:total` is nil when the server
  does not know the size."
  [s]
  (when-let [[_ start end total] (some->> s (re-matches #"(?i)\s*bytes\s+(\d{1,15})-(\d{1,15})/(\d{1,15}|\*)\s*"))]
    {:start (parse-long start) :end (parse-long end) :total (when (not= "*" total) (parse-long total))}))

(defn byte-ranges
  "The byte ranges that the Range header value `s` asks for, as RFC 9110
  reads them. Nil when `s` does not read, because a server then ignores
  it.

  Such a value is `bytes=0-1023, 5000-`. A range is `[start end]`, with
  the end included, or nil for the rest of the file. `[nil n]` is the last
  `n` bytes."
  [s]
  (when-let [[_ specs] (some->> s (re-matches #"(?i)\s*bytes\s*=\s*(.*)"))]
    (let [ranges (for [spec (map str/trim (str/split specs #","))
                       :when (seq spec)]
                   (when-let [[_ start end] (re-matches #"(\d{0,15})\s*-\s*(\d{0,15})" spec)]
                     (cond
                       (and (seq start) (seq end)) (let [a (parse-long start) b (parse-long end)]
                                                     (when (<= a b) [a b]))
                       (seq start)                 [(parse-long start) nil]
                       (seq end)                   [nil (parse-long end)])))]
      (when (and (seq ranges) (every? some? ranges))
        (vec ranges)))))

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
   (defn- ascii-host
     "The scheme and the authority `head` of a URL, with its host in the
     ASCII form of IDNA, so that java.net.URI reads the host."
     [head]
     (let [[_ before host after] (re-matches #"(?s)(.*?://(?:[^@]*@)?)([^:\[\]]*)(.*)" head)]
       (if (and host (re-find #"[^\x00-\x7F]" host))
         (str before (try (IDN/toASCII host IDN/ALLOW_UNASSIGNED) (catch Exception _ host)) after)
         head))))

#?(:clj
   (defn legal-url
     "`url` with each character that a URI cannot contain percent-encoded
     as UTF-8, such as a space or a letter outside ASCII.

     A bracket in the path is encoded too, because java.net.URI takes none
     there, and so is a percent sign that starts no escape. An escape stays
     as it is, so an encoded URL comes back unchanged. A host with letters
     outside ASCII gets the ASCII form of IDNA."
     [^String url]
     (let [[_ head path tail] (re-matches #"(?s)([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*)?([^?#]*)(.*)" url)]
       (str (encode-illegal (ascii-host (str head)) #{}) (encode-illegal path #{\[ \]}) (encode-illegal tail #{})))))

#?(:clj
   (defn- stripped
     "The URL `s` as a browser takes it: without the tabs and line breaks in
     it, and without the spaces and control characters around it."
     [s]
     (let [^String s (str/replace (str s) #"[\t\n\r]" "")
           n         (.length s)
           space?    (fn [i] (<= (int (.charAt s (int i))) 32))
           start     (loop [i 0] (if (and (< i n) (space? i)) (recur (inc i)) i))
           end       (loop [i n] (if (and (< start i) (space? (dec i))) (recur (dec i)) i))]
       (subs s start end))))

(defn absolute-url
  "`url` made absolute against `base`, the URL of the document that it is
  in, as a browser does it. Nil when one of them does not read as a URL.

  A path, a path from the root, a query alone or a URL without a scheme
  resolves against `base`. An absolute URL stays as it is.

  `base` is a URL with a scheme that can hold a path, so no `mailto:`. An
  empty `url` names the document itself. A path does not go above the
  root, so `../x` in a document at the root is `/x`."
  [base url]
  (when (and base url)
    #?(:clj  (try
               (let [base (legal-url (stripped base))
                     url  (legal-url (stripped url))
                     b    (URI. base)]
                 (when (and (.isAbsolute b) (not (.isOpaque b)))
                   ;; java.net.URI resolves by RFC 2396, which differs from browsers on
                   ;; a query or a fragment alone, on no reference, on a base with no
                   ;; path, and on the dot segments that are left at the root
                   (cond
                     (= "" url)                 (first (str/split base #"#" 2))
                     (str/starts-with? url "#") (str (first (str/split base #"#" 2)) url)
                     (str/starts-with? url "?") (str (first (str/split base #"[?#]" 2)) url)
                     :else
                     (-> (if (and (.getRawAuthority b) (str/blank? (.getRawPath b)))
                           (URI. (str (.getScheme b) "://" (.getRawAuthority b) "/"))
                           b)
                         (.resolve (URI. url))
                         .normalize
                         str
                         (str/replace #"^([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*)(?:/\.\.)+(?:/|(?=[?#])|$)" "$1/")))))
               (catch Exception _
                 nil))
       :cljs (try
               (.-href (js/URL. (str url) (str base)))
               (catch :default _
                 nil)))))

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

(defn ^:no-doc origin
  "The scheme, host and port of the http or https URL `url`, as the URL
  standard and browsers read them. Nil for another URL, and for a URL
  without a host.

  The host is in lower case, and a name with letters outside ASCII is in
  the ASCII form of IDNA. When the URL names no port, the port is the one
  of the scheme."
  [url]
  #?(:clj  (when-let [[_ scheme authority] (re-find #"^[\x00-\x20]*([hH][tT][tT][pP][sS]?):[/\\]*([^/\\?#]*)"
                                                   (str/replace (str url) #"[\t\n\r]" ""))]
             (let [scheme        (str/lower-case scheme)
                   ;; the user name and the password end at the last @
                   [_ host port] (re-matches #"(\[[0-9a-fA-F:.]*\]|[^:\[\]]*)(?::([0-9]*))?"
                                             (subs authority (inc (or (str/last-index-of authority "@") -1))))
                   host          (when (seq host)
                                   (try
                                     (str/lower-case (IDN/toASCII (percent-decode host) IDN/ALLOW_UNASSIGNED))
                                     (catch Exception _
                                       nil)))]
               (when (and (seq host) (not (re-find #"[\x00-\x20\x7F\"#%/<>?@\\^`{|}]" host)))
                 [scheme host (if (seq port) (parse-long port) (get {"http" 80 "https" 443} scheme))])))
     :cljs (try
             (let [u      (js/URL. url)
                   scheme (str/replace (.-protocol u) #":$" "")]
               (when (and (#{"http" "https"} scheme) (seq (.-hostname u)))
                 [scheme (.-hostname u) (if (seq (.-port u)) (parse-long (.-port u)) (get {"http" 80 "https" 443} scheme))]))
             (catch :default _
               nil))))

(defn ^:no-doc host
  "The host of the http or https URL `url`, as `origin` reads it."
  [url]
  (second (origin url)))

(defn ^:no-doc web-url?
  "Whether `s` is an http or https URL with a host."
  [s]
  (boolean (and (string? s) (re-find #"(?i)^https?://" s) (origin s))))

(defn ^:no-doc timeout?
  "Whether the exception `e` from `call!`, or one of its causes, tells that
  the exchange took longer than its time allowed."
  [e]
  (boolean (some #(or (= ::timeout (:type (ex-data %)))
                      #?(:clj  (instance? HttpTimeoutException %)
                         :cljs (= "TimeoutError" (.-name %))))
                 (take-while some? (iterate #?(:clj ex-cause :cljs #(.-cause %)) e)))))

(defn ^:no-doc same-origin?
  "Whether the URLs `a` and `b` have the same scheme, host and port, as
  `origin` reads them. False when a URL has no host."
  [a b]
  (let [o (origin a)]
    (and (some? o) (= o (origin b)))))

(defn ^:no-doc public-ipv4?
  "Whether the IPv4 address of the four numbers `a`, `b`, `c` and `d` is on
  the public internet: not in this network, private, shared, loopback,
  link-local, multicast or reserved, nor kept for protocols, tests and
  documentation, as the IANA registry of special addresses lists them."
  [[a b c _]]
  (not (or (= 0 a)
           (= 10 a)
           (and (= 100 a) (<= 64 b 127))
           (= 127 a)
           (and (= 169 a) (= 254 b))
           (and (= 172 a) (<= 16 b 31))
           (and (= 192 a) (= 0 b) (contains? #{0 2} c))
           (and (= 192 a) (= 168 b))
           (and (= 198 a) (contains? #{18 19} b))
           (and (= 198 a) (= 51 b) (= 100 c))
           (and (= 203 a) (= 0 b) (= 113 c))
           (<= 224 a))))

(defn ^:no-doc public-ipv6?
  "Whether the IPv6 address of the sixteen numbers `b` is on the public
  internet: not unspecified, loopback, unique local, link-local, site-local
  or multicast, nor kept for documentation, discard or Teredo, and not an
  address that holds an IPv4 address that is not public."
  [b]
  (let [word     #(+ (* 256 (nth b %)) (nth b (inc %)))
        ipv4-at  #(public-ipv4? (subvec b % (+ % 4)))
        zeros?   #(every? zero? (subvec b 0 %))]
    (cond
      (zeros? 15)                                               false
      (zeros? 12)                                               (ipv4-at 12)
      (and (zeros? 10) (= 0xffff (word 10)))                    (ipv4-at 12)
      (and (= 0x64 (word 0)) (= 0xff9b (word 2)))               (ipv4-at 12)
      (= 0x2002 (word 0))                                       (ipv4-at 2)
      (and (= 0x2001 (word 0)) (= 0 (word 2)))                  false
      (and (= 0x2001 (word 0)) (= 0xdb8 (word 2)))              false
      (and (= 0x100 (word 0)) (= 0 (word 2) (word 4) (word 6))) false
      (= 0xfc (bit-and 0xfe (nth b 0)))                         false
      (and (= 0xfe (nth b 0)) (<= 0x80 (nth b 1)))              false
      (= 0xff (nth b 0))                                        false
      :else                                                     true)))

#?(:clj
   (defn- public-address?
     "Whether the IP address `a` is on the public internet, as the JDK tells
     of its kinds, and as `public-ipv4?` and `public-ipv6?` tell."
     [^InetAddress a]
     (let [b (mapv #(bit-and % 0xff) (.getAddress a))]
       (and (not (or (.isAnyLocalAddress a) (.isLoopbackAddress a) (.isLinkLocalAddress a)
                     (.isSiteLocalAddress a) (.isMulticastAddress a)))
            (if (= 4 (count b)) (public-ipv4? b) (public-ipv6? b))))))

(defn ^:no-doc ipv4
  "The four numbers of the IPv4 address that the text `s` writes. Nil for
  anything else."
  [s]
  (when-let [[_ & parts] (re-matches #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})" s)]
    (let [ns (mapv parse-long parts)]
      (when (every? #(<= 0 % 255) ns) ns))))

(defn- words
  "The 16-bit words of the groups of `part`, a part of the text of an IPv6
  address. The last group can be an IPv4 address when `end?` says that the
  part ends the address. Nil when a group does not read."
  [part end?]
  (let [gs (if (= "" part) [] (str/split part #":" -1))]
    (reduce (fn [acc [i g]]
              (cond
                (re-matches #"[0-9a-fA-F]{1,4}" g)
                (conj acc #?(:clj (Long/parseLong g 16) :cljs (js/parseInt g 16)))

                (and end? (= i (dec (count gs))) (str/includes? g "."))
                (if-let [[a b c d] (ipv4 g)]
                  (conj acc (+ (* 256 a) b) (+ (* 256 c) d))
                  (reduced nil))

                :else
                (reduced nil)))
            []
            (map-indexed vector gs))))

(defn- ipv6
  "The sixteen numbers of the IPv6 address that the text `s` writes,
  without brackets. Nil for anything else."
  [s]
  (let [[head tail] (str/split s #"::" 2)
        front       (words head (nil? tail))
        back        (when tail (words tail true))
        n           (+ (count front) (count back))
        all         (cond
                      (or (nil? front) (and tail (nil? back))) nil
                      tail                                     (when (< n 8) (concat front (repeat (- 8 n) 0) back))
                      :else                                    (when (= n 8) front))]
    (when all
      (vec (mapcat (fn [w] [(quot w 256) (rem w 256)]) all)))))

(defn ^:no-doc ip-address
  "The bytes of the IP address that the text `s` writes, as numbers: four
  for IPv4, and sixteen for IPv6, with or without the brackets that a URL
  puts around it. Nil for anything else, such as a name or a hash."
  [s]
  (when (string? s)
    (or (ipv4 s)
        (when-let [[_ bracketed bare] (re-matches #"\[([0-9a-fA-F:.]+)\]|([0-9a-fA-F.]*:[0-9a-fA-F:.]*)" s)]
          (ipv6 (or bracketed bare))))))

(defn ^:no-doc public-name?
  "Whether the domain name `host` is a name of the public internet. Such a
  name has a dot in it, and it does not stay on a local network, as
  localhost, .local, .internal and .home.arpa do. The dot that can end a
  name does not count."
  [host]
  (let [h (str/replace (str/lower-case (str host)) #"\.$" "")]
    (and (str/includes? h ".")
         (not (re-find #"(^|\.)(localhost|local|internal|home\.arpa)$" h)))))

(defn public?
  "Whether the host of the http or https URL `url` is on the public
  internet. A host on the network of the app or of its device is not.

  On the JVM, each address that the name of the host resolves to must be
  public. A name that does not resolve is not public. The JVM keeps what
  it resolved for 30 seconds by default, so the request goes to the
  address that this function checked.

  ClojureScript cannot resolve a name in a browser. There, the host must
  not be an address that is not public. A name must have a dot in it, and
  it must not be localhost or a name under .localhost, .local, .internal
  or .home.arpa."
  [url]
  #?(:clj  (try
             ;; the host as the client of the JDK reads it, which sends the request
             (let [h (some-> url str legal-url URI. .getHost)]
               (boolean (and h (every? public-address? (InetAddress/getAllByName h)))))
             (catch Exception _
               false))
     :cljs (let [h (some-> (host url) (str/replace #"\.$" ""))]
             (boolean (and h
                           (if-let [b (ip-address h)]
                             (if (= 4 (count b)) (public-ipv4? b) (public-ipv6? b))
                             (public-name? h)))))))

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

(defn ^:no-doc refusal
  "The exception of a request to `url` that an `:allow?` refused."
  [url]
  (ex-info (str "The request to " url " is not allowed") {:type ::refused :url url}))

(def ^:no-doc credential-headers
  "The names of the headers that hold credentials, in lower case."
  #{"authorization" "cookie" "proxy-authorization"})

(defn ^:no-doc redirected
  "The request that follows `request` after a redirect of `status` to the
  URL `target`.

  A 303, or a 301 or 302 to a POST, turns into a GET without a body, as
  browsers and the JDK do. A HEAD stays a HEAD. Credentials stay behind
  when the redirect leaves the origin."
  [{:keys [method url headers body] :as request} status target]
  (let [get? (or (= 303 status) (and (#{301 302} status) (= :post method)))]
    (assoc request
           :url     target
           :method  (if (and get? (not= :head method)) :get method)
           :headers (cond->> headers
                      (not (same-origin? url target))
                      (into {} (remove (fn [[k _]] (credential-headers (str/lower-case (str k)))))))
           :body    (when-not get? body))))

#?(:clj
   (defn ^:no-doc send-following!
     "Send the `request`, `{:method :url :headers :body}`, and follow the
     redirects, five at most, until the instant `deadline` in epoch
     milliseconds. Each redirect is followed as `redirected` says, because
     the JDK sends credentials on to another origin.

     `allow?` must take the URL of the request and of each redirect, or
     `::refused` is thrown. Returns the last response, and the redirects
     before it as `[status url headers]`."
     [request deadline allow?]
     (loop [{:keys [url method headers body] :as request} (update request :url legal-url)
            chain                                         []]
       (let [uri  (URI. url)
             left (- deadline (System/currentTimeMillis))]
         (when-not (pos? left)
           (throw (HttpTimeoutException. (str "request timed out: " uri))))
         (when-not (allow? url)
           (throw (refusal url)))
         (let [response (.send ^HttpClient @client (java-request uri method headers body left)
                               (HttpResponse$BodyHandlers/ofInputStream))
               status   (.statusCode response)
               target   (when (and (#{301 302 303 307 308} status) (< (count chain) 5))
                          (redirect-target uri response))]
           (if-not target
             [response chain]
             (do (.close ^InputStream (.body response))
                 (recur (redirected request status (str target))
                        (conj chain [status url (headers-of response)])))))))))

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

(defn ^:no-doc prefix-length
  "How much of the body of an answer of `status` to read when it answers a
  request for the bytes `range`: of a 206, the bytes from the `start` of
  what it sends to the end of the range, since a server can send more than
  was asked; of a 200 not `gzipped?`, the whole file as it is, the bytes
  from its start to the end of the range; of any other answer not
  `gzipped?`, such as the page of a 404, as many bytes as the range names.
  Nil, to read all of the body, when the range has no end or the prefix is
  longer than `max-bytes`."
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
     header that a request sets makes fetch ask the server for leave first,
     and a browser names itself."
     (or (exists? js/document) (exists? js/WorkerGlobalScope))))

(defn- request-headers
  "The headers of a request of `call!` for the bytes `range`: `headers`,
  with `user-agent` unless they name another or a browser names itself,
  and with the Range header. On the JVM, a request for a range asks for
  bytes that are not gzipped, so that the offsets are those of the file."
  [headers range]
  (cond-> (merge #?(:clj  {"User-Agent" user-agent}
                    :cljs (when-not browser? {"User-Agent" user-agent}))
                 headers)
    range (assoc "Range" (str "bytes=" (first range) "-" (second range)))
    #?@(:clj [range (assoc "Accept-Encoding" "identity")])))

#?(:clj
   (defn- body-bytes
     "The bytes of the body of `response`, read until `deadline`. The
     `request` tells how much of the body to read, as `prefix-length` has
     it."
     ^bytes [^HttpResponse response {:keys [method url range max-bytes]} deadline]
     (let [headers (headers-of response)
           start   (:start (content-range (header headers "content-range")))]
       (with-open [^InputStream in (.body response)]
         (let [n (prefix-length (.statusCode response) start (gzipped? headers) range max-bytes)]
           (cond
             ;; an answer to HEAD has no content by RFC 9110, though
             ;; some servers send one over HTTP/2
             (= :head method) (byte-array 0)
             n                (within in deadline url #(.readNBytes in (int n)))
             :else            (read-within in deadline max-bytes url)))))))

#?(:clj
   (defn- java-answer
     "The response map of `call!` for the `response` of the JDK with the
     body `bytes`, which came after the redirects `chain`."
     [^HttpResponse response ^bytes bytes chain {:keys [as max-bytes]}]
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
     bytes. With `:first` in `opts`, it is that many bytes from the start,
     and the rest is left unread. With `:max-bytes`, it is the whole body,
     and the promise is rejected with `::too-large` once more bytes
     arrive. Without both, it is the whole body."
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
                       (throw (ex-info (str "Response body exceeds " n " bytes: " url)
                                       {:type ::too-large :url url :max-bytes n})))

                   (and (not all?) (>= got n))
                   (join chunks)

                   :else
                   (.then (.read reader)
                          (fn [r]
                            (if (.-done r)
                              (join chunks)
                              (step (conj chunks (.-value r)) (+ got (.-length (.-value r)))))))))]
         (js/Promise.resolve (step [] 0))))))

#?(:cljs
   (defn- fetch-answer
     "A promise of the response map of `call!` for the fetch response
     `res` to the `request`."
     [res {:keys [method url as range max-bytes]}]
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
         (.then (read-body res url (if n {:first n} {:max-bytes max-bytes}))
                (fn [b]
                  (cond-> response
                    (pos? (.-length b))
                    (assoc :body (if (= :bytes as) b (.decode (js/TextDecoder.) b))))))))))

(defn call!
  "Send the request map `request`, and give the response as `{:status :url
  :headers :body}`. On the JVM it gives the response, and in ClojureScript
  a promise of it.

  The request is `{:method :url :headers :body}`. It is a GET unless
  `:method` names another method, and it goes with the `:body` string when
  it has one. The body of the response is text, with gzip undone. It is
  absent for a HEAD request and for an empty body. With `:as :bytes`, the
  body is bytes, for a media file.

  `:timeout-seconds` (default 30) limits the whole exchange, and
  `:max-bytes` (default `default-max-bytes`, nil for no limit) limits the
  body. An exchange that goes beyond a limit throws `::timeout` or
  `::too-large`. On the JVM, the client of the JDK throws its
  HttpTimeoutException while it waits for the headers.

  `:range [start end]` asks for those bytes alone, the end included, or
  nil for the rest, as RFC 9110 has it. A server that sends the range
  answers 206, and `:content-range` is then what `content-range` reads. A
  server that ignores ranges answers 200 with the whole file. `call!`
  reads no further than the end of the range, when it has one. From
  another answer, such as the page of a 404, it reads no more bytes than
  the range names.

  `:allow?` is a predicate of a URL, as `public?` is. When it refuses the
  URL of the request, `call!` throws `::refused`.

  On the JVM, `call!` makes the URL legal first (see `legal-url`). It
  follows redirects, five at most, and never from https to http. A
  redirect to another origin leaves the credentials behind, and `:allow?`
  must take the URL of each redirect. `:redirects` lists each redirect as
  `[status url headers]`: the URL that answered with it, and the headers
  of its answer. When the redirects begin with permanent ones,
  `:permanent-url` is where the URL moved for good.

  In ClojureScript, fetch sends the request. fetch follows redirects
  itself and tells none of them, so `:allow?` does not see them. A
  browser lets a page read an answer from another site only as far as the
  CORS headers of the answer allow. It hides the headers that the answer
  does not expose, Content-Range and ETag among them. `:filtered?` marks
  such an answer. Outside a browser, as in Node, the request names
  `user-agent` as its User-Agent, unless the `:headers` name another."
  [{:keys [method headers timeout-seconds allow?]
    :or   {timeout-seconds 30 allow? (constantly true)}
    :as   request}]
  (let [method  (some-> method name str/lower-case keyword)
        request (assoc (merge {:max-bytes default-max-bytes} request)
                       :method  method
                       :headers (request-headers headers (:range request)))]
    #?(:clj  (let [deadline         (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
                   [response chain] (send-following! request deadline allow?)
                   bytes            (body-bytes response request deadline)]
               (java-answer response bytes chain request))
       :cljs (let [{:keys [url body]} request]
               (if-not (allow? url)
                 (js/Promise.reject (refusal url))
                 (-> (js/fetch url (clj->js (cond-> {:method  (str/upper-case (name (or method :get)))
                                                     :headers (:headers request)
                                                     :signal  (js/AbortSignal.timeout (* 1000 timeout-seconds))}
                                              (some? body) (assoc :body body))))
                     (.then #(fetch-answer % request))))))))

(defn call-public!
  "`call!` for the request map `request` to a host on the public internet,
  as `public?` tells. On the JVM, the host of each redirect must be public
  too.

  It sends no request to the network of the app or of its device. It
  throws `::refused`, or in ClojureScript rejects with it. The functions
  that fetch URLs that strangers choose put the same guard on each
  request, as their `:allow?`."
  [request]
  (call! (assoc request :allow? public?)))

#?(:clj
   (defn fetch!
     "GET the feed at `url`, and give the response as `{:status :url
     :headers :body}`, the body as text.

     The response also has `:etag` and `:last-modified` when the server
     gives them, and the `:permanent-url` of `call!`. It has `:not-before`
     when the server says how long to wait (see `not-before`).

     `opts` can give these:

     - `:etag` and `:last-modified` from an earlier response. They go as
       If-None-Match and If-Modified-Since, and a 304 then comes back with
       no body.
     - `:credentials` of a private feed (see `authorization`)
     - `:user-agent`, to send in place of `user-agent`
     - `:timeout-seconds` and `:max-bytes`, as `call!` takes them

     A user name and a password in `url` are the credentials when `opts`
     give none. The request goes to `url` without them (see
     `split-credentials`)."
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
     "GET `url`, and give the digests of its body by the MessageDigest
     `algorithms`, such as SHA-384: `{:status :url :digests {algorithm
     bytes}}`. The digests are there for a 200 alone.

     The body streams through the digests, and none of it is kept, so it
     can be a large media file. `opts` can give `:headers`, and the
     `:allow?` of `call!`. `:timeout-seconds` (default 600) limits the
     whole exchange, as for `call!`."
     ([url algorithms]
      (digest! url algorithms {}))
     ([url algorithms {:keys [headers timeout-seconds allow?] :or {timeout-seconds 600 allow? (constantly true)}}]
      (let [deadline      (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
            ;; before the request, since an algorithm that the platform lacks throws
            digests       (into {} (for [a algorithms] [a (MessageDigest/getInstance a)]))
            headers       (merge {"User-Agent" user-agent "Accept-Encoding" "identity"} headers)
            [response _]  (send-following! {:url url :method :get :headers headers} deadline allow?)
            status        (.statusCode ^HttpResponse response)]
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

(defn ^:no-doc limited
  "The request map `request` with the limits of `opts` that `call!` keeps
  to, each when `opts` give it: `:timeout-seconds`, `:max-bytes` and
  `:allow?`."
  [request opts]
  (into request (remove (comp nil? val)) (select-keys opts [:timeout-seconds :max-bytes :allow?])))

(defn ^:no-doc failed
  "The exception for the answer to a request for the file at `url` whose
  `status` is a failure."
  [url status]
  (ex-info (str "The file answered " status ": " url)
           {:type ::failed :status status :url url}))

(defn fetcher
  "A function that fetches the bytes of the file at `url` by range, as
  `dk.simongray.podcast.bytes/read!` takes it.

  The function takes a start and an end, end excluded. It gives `{:bytes
  :start :total}`, deferred in ClojureScript. An app can wrap it to log or
  keep what it fetches. It throws as `call!` does, and it throws
  `::failed` with the `:status` for an answer that is a failure.

  A server that ignores ranges sends the file from its start. The
  function then reads only the bytes up to the end, and its answer is
  `:whole?`. A server answers 416 for a range past the end of the file.
  The function then gives no bytes, and the size of the file, so a reader
  learns that the file is shorter than it says. In a browser, the function
  knows the total only when the server exposes Content-Range.

  `opts` can give the `:headers`, the `:timeout-seconds`, the `:max-bytes`
  and the `:allow?` of the requests. They can also give `:send`, a
  function that sends a request map as `call!` does and in its place."
  ([url]
   (fetcher url {}))
  ([url {:keys [headers send] :or {send call!} :as opts}]
   (fn [start end]
     (async/then
      (send (limited {:url url :headers headers :as :bytes :range [start (dec end)]} opts))
      (fn [{:keys [status body content-range] :as response}]
        (let [body (or body (bytes/of []))]
          (case (long status)
            206 {:bytes body :start (or (:start content-range) start) :total (:total content-range)}
            200 {:bytes body :start 0 :whole? true
                 :total (whole-size (bytes/length body) end
                                    (header (:headers response) "content-length")
                                    (header (:headers response) "content-encoding"))}
            ;; the body of a 416 is at most a page about the error
            416 {:bytes (bytes/of []) :start start
                 :total (unsatisfied-total (header (:headers response) "content-range"))}
            (throw (failed url status)))))))))

(defn ^:no-doc read-by-ranges!
  "What the reader `read` gives of the file at `url`, as
  `dk.simongray.podcast.bytes/read!` gives it. In ClojureScript, it gives
  a promise of it.

  The bytes come by the `:fetch` of `opts`, or by the `fetcher` that
  `opts` make. `opts` give the limits of the reading, with
  `bytes/default-limits` for the limits that they do not set. The
  `:error` of a file that did not come names the `url`."
  [url read {:keys [fetch] :as opts}]
  (let [opts (merge bytes/default-limits opts)]
    (async/then (bytes/read! (or fetch (fetcher url opts)) read opts)
                #(cond-> % (:error %) (assoc-in [:error :url] url)))))

(defn media-type
  "The media type of the Content-Type value `s`, or of a type attribute.
  Nil when `s` names none.

  The media type is in lower case, without its parameters."
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
  it.

  The facts are `{:status :url :redirects :permanent-url :location :type
  :length :range :encoding :etag :last-modified :cors :filtered?}`:

  - `:type` is the `media-type`, and `:length` is what Content-Length
    gives.
  - `:range` is the Content-Range, as `content-range` reads it.
  - `:location` is the Location of a redirect that was not followed.
  - `:cors` is the origins that Access-Control-Allow-Origin allows to
    read the answer.
  - Each of the `:redirects` on the way is `{:status :url :cors}`, with
    the URL that answered with it."
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

(defn ^:no-doc sent!
  "The attempt of `send` at `request`, within the limits of `opts` that
  `limited` names: `{:value response}`, or `{:error e}` when the sending
  failed; deferred."
  ([send request]
   (sent! send request {}))
  ([send request opts]
   (async/attempt #(send (limited request opts)))))

(defn ^:no-doc sent-facts
  "The `facts` of the response of an `attempt` from `sent!`, or `{:error
  diagnostic}` for a request that failed."
  [{:keys [value error]}]
  (if error {:error (bytes/unreachable error)} (facts value)))

(defn probe!
  "What the server says about the file at `url`, while no more than two
  bytes of the file move. On the JVM it gives the result, and in
  ClojureScript a promise of it.

  The result is `{:head :get}`: the `facts` of the answers to a HEAD
  request and to a request for the first two bytes. The facts of a request
  that failed are `{:error diagnostic}`, and the diagnostic is
  `:file/unreachable`.

  Most servers answer the second request with 206, and with the size of
  the whole file in Content-Range. A server that ignores ranges answers
  200, and `probe!` reads two bytes of that answer. The HEAD request asks
  for the file as it is, not gzipped, so that its Content-Length is the
  size of the file.

  `opts` can give the `:headers` of the two requests, such as an Origin to
  learn about CORS. They can give the `:timeout-seconds`, the `:max-bytes`
  and the `:allow?` of the requests. They can also give `:send`, a
  function that sends a request map as `call!` does and in its place."
  ([url]
   (probe! url {}))
  ([url {:keys [headers send] :or {send call!} :as opts}]
   (let [answer! (fn [request]
                   (async/then (sent! send (assoc request :url url) opts) sent-facts))]
     (async/then (async/all [(answer! {:method :head :headers (merge {"Accept-Encoding" "identity"} headers)})
                             (answer! {:range [0 1] :as :bytes :headers headers})])
                 (fn [[head got]] {:head head :get got})))))

(defn ^:no-doc request-bytes
  "The bytes of the `body` of a Ring request, which is a string, bytes, a
  stream or nothing. Nil when the body holds more than `max-bytes`, and
  for a stream in ClojureScript."
  [body max-bytes]
  (let [b (cond
            (nil? body)    (bytes/of [])
            (string? body) (bytes/encode body)
            :else          #?(:clj  (if (bytes? body)
                                      body
                                      (.readNBytes ^InputStream body (int (inc max-bytes))))
                              :cljs (when (instance? js/Uint8Array body) body)))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn ^:no-doc body-text
  "The text of the `body` of a Ring request, which is a string, a stream
  or nothing. Nil when the body holds more than `max-bytes`, and for a
  stream in ClojureScript."
  [body max-bytes]
  (cond
    (nil? body)    ""
    (string? body) (when (<= (count body) max-bytes) body)
    :else          #?(:clj  (let [bytes (.readNBytes ^InputStream body (int (inc max-bytes)))]
                              (when (<= (alength bytes) max-bytes)
                                (String. bytes StandardCharsets/UTF_8)))
                      :cljs nil)))

(comment
  (query-string {:q "clojure podcasts" :max 10 :pretty true})
  (authorization {:username "ann" :password "secret"})
  (split-credentials "https://ann:secret@example.org/feed.xml")
  (absolute-url "https://example.org/shows/feed.xml" "../art/cover.png")
  (origin "HTTPS://Example.org/feed.xml")
  (byte-ranges "bytes=0-1023, 5000-")

  ;; an hour at most, though the server says a day
  (not-before {"cache-control" "max-age=86400"} #inst "2026-09-29T12:00:00Z")

  ;; a POST that a 303 turns into a GET, and credentials that stay behind
  (redirected {:method :post :url "https://a.example/x" :headers {"Authorization" "Basic …"} :body "{}"}
              303 "https://b.example/y")

  (public? "http://192.168.1.10/feed.xml")
  (call! {:url "https://example.org/episode.mp3" :range [0 1023] :as :bytes})
  (probe! "https://example.org/episode.mp3")
  #_.)

#?(:clj
   (comment
     (select-keys (fetch! "https://example.org/feed.xml")
                  [:status :url :etag :last-modified])
     #_.))
