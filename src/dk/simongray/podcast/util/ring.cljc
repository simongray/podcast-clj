(ns dk.simongray.podcast.util.ring
  "The Ring requests that the handlers of podcast-clj answer: their bodies,
  their credentials and their conditions."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.secret :as secret])
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

            :else
            #?(:clj  (if (bytes? body)
                       body
                       (.readNBytes ^InputStream body (int (inc max-bytes))))
               :cljs (when (instance? js/Uint8Array body) body)))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn body-text
  "The text of the `body` of a Ring request, read within `max-bytes`, of
  UTF-8."
  [body max-bytes]
  (when-let [b (body-bytes body max-bytes)]
    (bytes/decode b :utf8)))

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
                     decoded (some-> b (bytes/decode :utf8))
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
