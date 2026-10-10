(ns dk.simongray.podcast.store.webdav.server
  "Any document store served over WebDAV (RFC 4918), for a Clojure backend
  whose browser clients sync through their own origin.

  The server answers these methods, with ETags and preconditions:

  - GET and HEAD, for a document
  - PUT, for a document, when the store has :put
  - DELETE, for a document, when the store has :delete
  - PROPFIND at depth zero or one, with a listing of the collection when
    the store has :list
  - OPTIONS, so that a client can ask which methods there are
  - MKCOL, with a 405, since the collection is already there

  A client of a store without :put, :list or :delete leaves the same keys
  out of its WebDAV store."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.store.webdav :as webdav]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch.url :as url])
  (:import [java.io ByteArrayInputStream]))

(defn- quoted
  [version]
  (str "\"" version "\""))

(defn- unquoted
  "The version in the ETag `etag`, without its quotes."
  [etag]
  (some-> etag str/trim (str/replace #"^\"|\"$" "")))

;; RFC 9110, section 8.8.3.2: a weak tag never matches in the strong
;; comparison that If-Match uses
(defn- strong-versions
  "The versions of the strong entity tags in the header value `s`, a list
  of tags."
  [s]
  (->> (str/split s #",")
       (map str/trim)
       (remove #(str/starts-with? % "W/"))
       (map unquoted)))

(defn- escape
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- entry
  "One response of a multistatus for `href` with the `version`, or for a
  collection when `version` is nil."
  [href version]
  (str "<d:response><d:href>" (escape href) "</d:href><d:propstat><d:prop>"
       (if version
         (str "<d:getetag>" (escape (quoted version)) "</d:getetag>"
              "<d:resourcetype/>")
         "<d:resourcetype><d:collection/></d:resourcetype>")
       "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
       "</d:response>"))

(defn- multistatus
  [entries]
  {:status  207
   :headers {"Content-Type" "application/xml; charset=utf-8"}
   :body    (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                 "<d:multistatus xmlns:d=\"DAV:\">"
                 (apply str entries)
                 "</d:multistatus>")})

(defn- plain
  [status body]
  {:status  status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    body})

(defn- not-found
  []
  (plain 404 "Not Found"))

(defn- allow
  "The Allow header of the methods that `store` answers for a document, or
  for the collection when `document?` is false. PUT and DELETE are there
  only when the store can write and delete."
  [{:keys [put delete] :as store} document?]
  (if document?
    (str "OPTIONS, GET, HEAD, "
         (when put "PUT, ")
         (when delete "DELETE, ")
         "PROPFIND")
    "OPTIONS, PROPFIND"))

;; RFC 9110, section 15.5.6
(defn- not-allowed
  "The 405 answer for the document `name` of `store`, or for the
  collection when `name` is nil, with the methods that it answers."
  [store name]
  (let [methods (allow store (some? name))]
    (assoc-in (plain 405 "Method Not Allowed") [:headers "Allow"] methods)))

;; RFC 9110, sections 13.1.1 and 13.1.2
(defn- precondition
  "The options of a store's put that the If-Match or If-None-Match of the
  request `headers` ask for, given the `current` version of the document.

  The version is nil when the document isn't there. The headers are by
  lower-case name, and the options have an :if-version only when the
  headers ask for one. If-Match: * asks for the current version, or for
  one that no document has when there's none. A tag matches the current
  version by its printed form, so a version can be any value, e.g. a
  number. The result is :failed when If-Match lists no strong tag, since
  no version matches it."
  [headers current]
  (let [if-match (some-> (get headers "if-match") str/trim)]
    (cond
      (= "*" if-match)
      {:if-version (or current "*")}

      if-match
      (if-let [versions (seq (strong-versions if-match))]
        {:if-version (if (and current (some #{(str current)} versions))
                       current
                       (first versions))}
        :failed)

      (= "*" (get headers "if-none-match"))
      {:if-version nil}

      :else
      {})))

(defn- plain-name?
  "Whether `name` is a plain file name, without a slash or a control
  character, and not one dot or two."
  [name]
  (not (or (str/includes? name "/")
           (#{"." ".."} name)
           (re-find #"[\x00-\x1f\x7f]" name))))

(defn- version-of
  "The version of the document `name` in `store`, or nil when it isn't
  there.

  It comes from the listing when the store has one, so that no body is
  read to learn that a document exists."
  [{:keys [list fetch] :as store} name]
  (if list
    (some #(when (= name (:name %)) (:version %)) (list))
    (:version (fetch name {:as :bytes}))))

(defn- options
  "The answer to OPTIONS, with the methods that the handler answers for the
  documents of `store`. A client asks the collection what it can do with
  them, so the answer is the same for any target."
  [store]
  {:status  200
   :headers {"DAV" "1" "Allow" (allow store true)}
   :body    ""})

;; RFC 4918, sections 9.1 (no Depth is infinity, which is depth 1 here,
;; because the collection holds no collection) and 9.1.1 (403)
(defn- propfind
  "The multistatus of `store` for the document `name`, or else for the
  collection at `base`, at `depth`.

  The collection comes alone at depth \"0\", and else with its documents,
  which a store without :list can't give."
  [{:keys [list] :as store} base name depth]
  (let [href #(str base (url/percent-encode %))]
    (cond
      name
      (if-let [version (version-of store name)]
        (multistatus [(entry (href name) version)])
        (not-found))

      (= "0" (some-> depth str/trim))
      (multistatus [(entry base nil)])

      list
      (multistatus (cons (entry base nil)
                         (for [{:keys [name version]} (list)]
                           (entry (href name) version))))

      :else
      (plain 403 "The collection cannot be listed"))))

(defn- get-document
  "The answer with the document `name` of `store` and its ETag, without the
  body for a `head?`, served by the `content-types`. A document of bytes,
  by its name, is a stream of them."
  [{:keys [fetch] :as store} name head? content-types]
  (let [binary? (and name (webdav/binary? name content-types))]
    (if-let [{:keys [body version]} (when name
                                      (fetch name (if binary?
                                                    {:as :bytes}
                                                    {})))]
      {:status  200
       :headers {"ETag"         (quoted version)
                 "Content-Type" (webdav/content-type name content-types)}
       :body    (cond
                  head?   ""
                  binary? (ByteArrayInputStream. ^bytes body)
                  :else   body)}
      (if name (not-found) (not-allowed store nil)))))

(defn- put-document
  "The answer to a put of the document `name` in `store` from the request
  `body` of at most `max-bytes`, under the precondition of the `headers`,
  read as text or bytes by the `content-types`.

  The status is 201 for a new document, 204 for a replaced one, 412 when
  the precondition failed, 413 when the body is too large, and 405 when
  the store can't write."
  [{:keys [put] :as store} name headers body max-bytes content-types]
  (if (and name put)
    (let [current (version-of store name)
          asked   (precondition headers current)
          read    (if (webdav/binary? name content-types)
                    ring/body-bytes
                    ring/body-text)
          given   (read body max-bytes)
          result  (when (and given (not= :failed asked))
                    (put name given asked))]
      (cond
        (nil? given)
        (plain 413 (str "A document holds at most " max-bytes " bytes"))

        (or (= :failed asked) (:conflict? result))
        (plain 412 "Precondition Failed")

        :else
        {:status  (if current 204 201)
         :headers {"ETag" (quoted (:version result))}
         :body    ""}))
    (not-allowed store name)))

(defn- delete-document
  "The answer that deletes the document `name` from `store`: 204, or 404
  when it isn't there, or 405 when the store can't delete."
  [{:keys [delete] :as store} name]
  (cond
    (not delete)                       (not-allowed store name)
    (and name (version-of store name)) (do (delete name)
                                           {:status 204 :headers {} :body ""})
    :else                              (not-found)))

;; RFC 4918, section 9.3.1: MKCOL is for an unmapped URL, and the
;; collection is there
(defn- make-collection
  "The 405 answer to MKCOL, on the collection of `store` itself or on the
  document `name` within it."
  [store name]
  (not-allowed store name))

(defn handler
  "A Ring handler that serves `store` over WebDAV under `base`, the path of
  the collection with its trailing slash, e.g. \"/dav/\", with `opts`.

  Mount it under Pedestal, ring-jetty or http-kit for the route `base` and
  everything below it. Whether the router passes a request for the
  collection with its trailing slash, and the body of a put unread,
  depends on the router. The `opts` are:

  - :max-bytes, the most that a put can send, 32 MB by default, far more
    than the state of any listener
  - :content-types, the Content-Type of a document by the extension of
    its name, over webdav/default-content-types, which also tells text
    from bytes

  The handler checks no credentials, so mount it behind your login. For a
  site with many listeners, give each the store of their own documents,
  e.g. by making the handler for each request, which costs little, with
  a function store-of of your own from a user to their store:

      (fn [request]
        ((handler (store-of (:user request)) \"/dav/\") request))"
  ([store base]
   (handler store base {}))
  ([store base {:keys [max-bytes content-types]
                :or   {max-bytes (* 32 1024 1024)}
                :as   opts}]
   {:pre [(map? opts)]}
   (let [collection (subs base 0 (dec (count base)))]
     (fn [{:keys [request-method uri headers body]}]
       ;; only a real servlet lower-cases the header names
       (let [lower   (fn [[k v]] [(str/lower-case k) v])
             headers (into {} (map lower) headers)]
         (try
           (let [inside? (and (str/starts-with? uri base)
                              (> (count uri) (count base)))
                 name    (when inside?
                           (url/percent-decode (subs uri (count base))))]
             (cond
               (and (not name) (not (#{base collection} uri)))
               (not-found)

               (and name (not (plain-name? name)))
               (plain 400 "A document's name is a plain file name")

               :else
               (case request-method
                 :options     (options store)
                 :propfind    (propfind store base name (get headers "depth"))
                 (:get :head) (get-document store name (= :head request-method)
                                            content-types)
                 :put         (put-document store name headers body max-bytes
                                            content-types)
                 :delete      (delete-document store name)
                 :mkcol       (make-collection store name)
                 (not-allowed store name))))
           ;; a malformed escape in the path, or a name the store's
           ;; filesystem cannot take
           (catch IllegalArgumentException _
             (plain 400 "A document's name is a plain file name"))
           (catch clojure.lang.ExceptionInfo e
             (plain 400 (ex-message e)))))))))
