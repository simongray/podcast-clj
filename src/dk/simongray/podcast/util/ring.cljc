(ns dk.simongray.podcast.util.ring
  "The Ring requests that the handlers of podcast-clj answer and the
  responses they give: the bodies, credentials, hosts and conditions of
  the requests."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.webcrypto.bytes :as bytes])
  #?(:clj (:import [java.io InputStream])))

(defn body-bytes
  "The bytes of the `body` of a Ring request, which is a string, bytes, a
  stream or nothing. Nil when the body holds more than `max-bytes`, and
  for a stream in ClojureScript."
  [body max-bytes]
  (let [b (cond
            (nil? body)
            (bytes/of [])

            (string? body)
            (bytes/encode body)

            (bytes/bytes? body)
            body

            :else
            #?(:clj  (.readNBytes ^InputStream body (int (inc max-bytes)))
               :cljs nil))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn body-text
  "The text of the `body` of a Ring request, read within `max-bytes`, of
  UTF-8."
  [body max-bytes]
  (when-let [b (body-bytes body max-bytes)]
    (bytes/decode b)))

(defn response
  "The Ring response of `status` with the `body` of `content-type`, and the
  headers `more`."
  ([status content-type body]
   (response status content-type body nil))
  ([status content-type body more]
   {:status  status
    :headers (merge {"Content-Type" content-type} more)
    :body    body}))

(defn text-response
  "The Ring response of `status` with the plain text `message`, and the
  headers `more`, e.g. a refusal."
  ([status message]
   (text-response status message nil))
  ([status message more]
   (response status "text/plain; charset=utf-8" message more)))

(defn host-name
  "The `host` of a Host header or of a server name, in lower case and
  without its port."
  [host]
  (some-> host str/lower-case (str/replace #":\d+$" "")))

(def local-names
  "The names of the machine itself that a Host header can give."
  #{"localhost" "127.0.0.1" "[::1]"})

;; RFC 9110, section 7.2. The check is against DNS rebinding: a web page
;; that points a name of its own at the machine's address gets past no
;; other check of a server without a password.
(defn addressed-to?
  "Whether the Host header of the Ring `request` names the machine itself
  or one of the host `names`, with or without a port.

  A request without a Host header is too, as HTTP/1.0 allows, since the
  check is against web pages, and a client that isn't a browser can send
  any name."
  [request names]
  (let [host (host-name (get-in request [:headers "host"]))]
    (boolean (or (nil? host)
                 (local-names host)
                 (some #(= host (host-name %)) names)))))

(defn https?
  "Whether the Ring `request` came over https: by its scheme, or by the
  X-Forwarded-Proto of a proxy in front, e.g. Caddy."
  [{:keys [scheme headers] :as request}]
  (or (= :https scheme)
      (= "https" (get headers "x-forwarded-proto"))))

;; RFC 7617, section 2 (Basic); RFC 6750, section 2.1 (Bearer)
(defn credentials
  "The credentials in the Authorization header `authorization`, as a map,
  or nil when it has none.

  The map has :username and :password for basic authentication, or :token
  for a bearer token, as http/authorization takes them, and the password
  and the token are hidden texts. The header can be a hidden text too."
  [authorization]
  (let [auth-pattern     #"(?i)\s*(basic|bearer)\s+(\S+)\s*"
        [_ scheme value] (some->> (secret/reveal authorization)
                                  (re-matches auth-pattern))]
    (case (some-> scheme str/lower-case)
      "basic"  (let [b       (bytes/from-base64 value)
                     decoded (some-> b bytes/decode)
                     i       (some-> decoded (str/index-of ":"))]
                 (when i
                   {:username (subs decoded 0 i)
                    :password (secret/hide (subs decoded (inc i)))}))
      "bearer" {:token (secret/hide value)}
      nil)))

;; RFC 9110, section 8.8.3.2, the weak comparison
(defn- tags
  "The entity tags in the header value `s`, without the W/ of the weak
  ones."
  [s]
  (map second (re-seq #"(?:W/)?(\"[^\"]*\")" (str s))))

;; RFC 9110, sections 13.1.2, 13.1.3 and 13.2.2
(defn not-modified?
  "Whether a GET or a HEAD with the lower-case `headers` gets a 304 for the
  representation with the entity tag `etag` and the instant
  `last-modified`, either of which can be nil.

  An If-None-Match decides alone: a 304 when it names the tag, or is *.
  Otherwise an If-Modified-Since gives a 304 when the representation
  changed at most then, to the second."
  [headers etag last-modified]
  (let [match (get headers "if-none-match")
        since (some-> (get headers "if-modified-since") time/parse-rfc2822)]
    (cond
      match
      (or (= "*" (str/trim match))
          (boolean (and etag
                        (some (set (tags etag)) (tags match)))))

      (and since last-modified)
      (<= (quot (inst-ms last-modified) 1000)
          (quot (inst-ms since) 1000))

      :else
      false)))
