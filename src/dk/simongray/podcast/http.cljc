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
  (:require [clojure.string :as str])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream IOException]
                   [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder
                                  HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
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

#?(:clj
   (defonce ^{:no-doc true
              :doc    "The shared HTTP client, built on first use: redirects
  followed, a 15 second connect timeout."}
     client
     (delay (-> (HttpClient/newBuilder)
                (.followRedirects HttpClient$Redirect/NORMAL)
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
     made legal first (see `legal-url`)."
     [{:keys [method url headers body timeout-seconds max-bytes]
       :or   {timeout-seconds 30 max-bytes default-max-bytes}}]
     (let [deadline (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
           builder  (-> (HttpRequest/newBuilder (URI. (legal-url url)))
                        (.timeout (Duration/ofSeconds timeout-seconds))
                        (.header "Accept-Encoding" "gzip"))
           builder  (reduce-kv (fn [^HttpRequest$Builder b k v] (.header b (str k) (str v)))
                               builder
                               (merge {"User-Agent" user-agent} headers))
           request  (-> (if (or (nil? method) (= :get method))
                          (.GET ^HttpRequest$Builder builder)
                          (.method ^HttpRequest$Builder builder
                                   (str/upper-case (name method))
                                   (if (some? body)
                                     (HttpRequest$BodyPublishers/ofString (str body))
                                     (HttpRequest$BodyPublishers/noBody))))
                        (.build))
           response (.send ^HttpClient @client request (HttpResponse$BodyHandlers/ofInputStream))
           bytes    (with-open [^InputStream in (.body ^HttpResponse response)]
                      (read-within in deadline max-bytes url))
           response (response->map response bytes)]
       (assoc response :body (when (and (not= 304 (:status response)) (pos? (alength bytes)))
                               (decode (:headers response) bytes max-bytes))))))

#?(:clj
   (defn fetch!
     "GET the feed at `url` and return `{:status :url :headers :body}` with the
     body as a decoded string, plus `:etag` and `:last-modified` when present.
     `opts` may carry an `:etag` and `:last-modified` from an earlier response,
     sent as If-None-Match and If-Modified-Since, on which a 304 comes back
     with no body; a `:user-agent` to send instead of `user-agent`; and the
     `:timeout-seconds` and `:max-bytes` of `call!`."
     ([url]
      (fetch! url {}))
     ([url {:keys [etag last-modified user-agent]
            :or   {user-agent dk.simongray.podcast.http/user-agent}
            :as   opts}]
      (let [accept   "application/rss+xml, application/xml, text/xml, */*"
            headers  (cond-> {"User-Agent" user-agent "Accept" accept}
                       etag          (assoc "If-None-Match" etag)
                       last-modified (assoc "If-Modified-Since" last-modified))
            response (call! (assoc opts :url url :headers headers))]
        (assoc response
               :etag          (header (:headers response) "etag")
               :last-modified (header (:headers response) "last-modified"))))))

#?(:clj
   (comment
     (select-keys (fetch! "https://example.org/feed.xml")
                  [:status :url :etag :last-modified])
     #_.))
