(ns dk.simongray.podcast.http
  "Fetching feeds on the JVM with java.net.http: redirects followed, gzip
  accepted, conditional requests honoured, and the body decoded by the
  charset the server or the document declares.

  Responses are plain maps so that nothing else in the library touches
  java.net.http. The `:url` of a response is its final URL after redirects,
  which is what a stored feed URL should become on a permanent redirect."
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset Charset StandardCharsets]
           [java.time Duration]
           [java.util.zip GZIPInputStream]))

(def user-agent
  "dk.simongray/podcast (+https://github.com/simongray)")

(defonce client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 15))
             (.build))))

(defn- header
  "The first value of `header-name` in the response headers map `headers`."
  [headers header-name]
  (first (get headers (str/lower-case header-name))))

(defn- response->map
  [^HttpResponse response]
  {:status  (.statusCode response)
   :url     (str (.uri response))
   :headers (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)]))
                  (.map (.headers response)))
   :body    (.body response)})

(defn- gunzip
  ^bytes [^bytes body]
  (with-open [in (GZIPInputStream. (ByteArrayInputStream. body))]
    (.readAllBytes ^InputStream in)))

(defn declared-charset
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
        StandardCharsets/UTF_8))))

(defn decode
  "The `body` bytes of the response with `headers` as a string, gunzipped
  when the response says so and decoded by `declared-charset`."
  [headers ^bytes body]
  (let [body (if (= "gzip" (header headers "content-encoding")) (gunzip body) body)]
    (String. body ^Charset (declared-charset headers body))))

(def legal-url-characters
  "The ASCII characters a URI may contain as they are: RFC 3986's unreserved
  and reserved sets, plus the percent sign of an escape."
  (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%"))

(defn legal-url
  "`url` with every character a URI may not contain, such as a space or a
  non-ASCII letter, percent-encoded as UTF-8. Feed URLs in the wild carry
  them verbatim, and java.net.URI rejects them. Percent signs are left as
  they are, so an already encoded URL comes back unchanged."
  [^String url]
  (let [sb (StringBuilder.)]
    (doseq [b (.getBytes url StandardCharsets/UTF_8)]
      (let [n (bit-and b 0xFF)]
        (if (and (< n 128) (legal-url-characters (char n)))
          (.append sb (char n))
          (.append sb (format "%%%02X" n)))))
    (str sb)))

(defn call!
  "Send the GET request map `{:url :headers}` (what dk.simongray.podcast.index
  builds, or anything of that shape) and return `{:status :url :headers
  :body}` with the body decoded to a string; `:timeout-seconds` defaults to
  30. gzip is accepted and undone, and a URL with illegal characters is
  encoded first (see `legal-url`)."
  [{:keys [url headers timeout-seconds] :or {timeout-seconds 30}}]
  (let [builder  (-> (HttpRequest/newBuilder (URI. (legal-url url)))
                     (.timeout (Duration/ofSeconds timeout-seconds))
                     (.header "Accept-Encoding" "gzip"))
        request  (-> (reduce-kv (fn [b k v] (.header b (str k) (str v)))
                                builder
                                (merge {"User-Agent" user-agent} headers))
                     (.GET)
                     (.build))
        response (response->map (.send @client request (HttpResponse$BodyHandlers/ofByteArray)))]
    (update response :body #(when (and (not= 304 (:status response)) (pos? (alength ^bytes %)))
                              (decode (:headers response) %)))))

(defn fetch!
  "GET the feed at `url` and return `{:status :url :headers :body}` with the
  body as a decoded string, plus `:etag` and `:last-modified` when present.
  `opts` may carry an `:etag` and `:last-modified` from an earlier response,
  sent as If-None-Match and If-Modified-Since; a 304 then comes back with no
  body."
  ([url]
   (fetch! url {}))
  ([url {:keys [etag last-modified timeout-seconds user-agent]
         :or   {timeout-seconds 30 user-agent dk.simongray.podcast.http/user-agent}}]
   (let [response (call! {:url             url
                          :timeout-seconds timeout-seconds
                          :headers         (cond-> {"User-Agent" user-agent
                                                    "Accept"     "application/rss+xml, application/xml, text/xml, */*"}
                                             etag          (assoc "If-None-Match" etag)
                                             last-modified (assoc "If-Modified-Since" last-modified))})
         headers  (:headers response)]
     (assoc response
            :etag (header headers "etag")
            :last-modified (header headers "last-modified")))))

(comment
  (select-keys (fetch! "https://feeds.podcastindex.org/pc20.xml") [:status :url :etag :last-modified])
  #_.)
