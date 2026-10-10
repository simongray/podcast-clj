(ns dk.simongray.podcast.util.ring
  "The Ring requests that the handlers of podcast-clj answer: their bodies
  and their credentials."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]
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
    (bytes/decode b 0 (bytes/length b) :utf8)))

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
                     decoded (some-> b (bytes/decode 0 (bytes/length b) :utf8))
                     i       (some-> decoded (str/index-of ":"))]
                 (when i
                   {:username (subs decoded 0 i)
                    :password (secret/hide (subs decoded (inc i)))}))
      "bearer" {:token (secret/hide value)}
      nil)))
