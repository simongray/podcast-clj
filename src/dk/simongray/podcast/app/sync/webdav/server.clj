(ns dk.simongray.podcast.app.sync.webdav.server
  "Any document store served over WebDAV (RFC 4918), for a Clojure backend
  whose browser clients sync through their own origin: `handler` is a Ring
  handler answering GET, HEAD, PUT, DELETE and a PROPFIND at depth zero or
  one, with ETags and the preconditions, and OPTIONS so that a client can
  ask. MKCOL gets 405, because the collection is there. A store without
  `:list` is served without the listing of the collection, and one without
  `:delete` without DELETE; a client of such a server leaves the same keys
  out of its WebDAV store."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            [dk.simongray.podcast.util.http :as http]))

(defn- quoted
  [version]
  (str "\"" version "\""))

(defn- unquoted
  "The version in the ETag `etag`, its quotes removed."
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
  "One response of a multistatus, for `href` with the `version`, or a
  collection when `version` is nil."
  [href version]
  (str "<d:response><d:href>" (escape href) "</d:href><d:propstat><d:prop>"
       (if version
         (str "<d:getetag>" (escape (quoted version)) "</d:getetag><d:resourcetype/>")
         "<d:resourcetype><d:collection/></d:resourcetype>")
       "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"))

(defn- multistatus
  [entries]
  {:status  207
   :headers {"Content-Type" "application/xml; charset=utf-8"}
   :body    (str "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">"
                 (apply str entries)
                 "</d:multistatus>")})

(defn- plain
  [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- not-found
  []
  (plain 404 "Not Found"))

(defn- allow
  "The methods that `store` answers for a document, or for the collection
  when `document?` is false, as an Allow header value: DELETE only when
  the store can delete."
  [{:keys [delete]} document?]
  (if document?
    (str "OPTIONS, GET, HEAD, PUT, " (when delete "DELETE, ") "PROPFIND")
    "OPTIONS, PROPFIND"))

;; RFC 9110, section 15.5.6
(defn- not-allowed
  "405 for the document `name` of `store`, or for the collection when
  `name` is nil, with the methods that the target answers."
  [store name]
  (assoc-in (plain 405 "Method Not Allowed") [:headers "Allow"] (allow store (some? name))))

;; RFC 9110, sections 13.1.1 and 13.1.2
(defn- precondition
  "What the If-Match or If-None-Match of the request `headers`, by their
  lower-case names, asks of a put of a document whose version is `current`,
  nil when it is not there, as the `:if-version` a store takes: absent for
  none. If-Match: * asks for the current version, or for one no document
  has when there is none. `:failed` when If-Match lists no strong tag,
  which no version matches."
  [headers current]
  (let [if-match (some-> (get headers "if-match") str/trim)]
    (cond
      (= "*" if-match)                      {:if-version (or current "*")}
      if-match                              (if-let [versions (seq (strong-versions if-match))]
                                              {:if-version (or (some #{current} versions) (first versions))}
                                              :failed)
      (= "*" (get headers "if-none-match")) {:if-version nil}
      :else                                 {})))

(defn- plain-name?
  "Whether `name` is a plain file name: no slash, not `.` or `..`, and no
  control character."
  [name]
  (not (or (str/includes? name "/")
           (#{"." ".."} name)
           (re-find #"[\x00-\x1f\x7f]" name))))

(defn- version-of
  "The version of the document `name` in `store`, nil when it is not there:
  from the listing when the store has one, so that no body is read to
  learn that a document exists."
  [{:keys [list fetch]} name]
  (if list
    (some #(when (= name (:name %)) (:version %)) (list))
    (:version (fetch name))))

(defn- options
  "The methods the handler answers for the documents of `store`, whatever
  the target: a client asks the collection what it can do with them."
  [store]
  {:status  200
   :headers {"DAV" "1" "Allow" (allow store true)}
   :body    ""})

;; RFC 4918, sections 9.1 (no Depth is infinity, which is depth 1 here,
;; because the collection holds no collection) and 9.1.1 (403)
(defn- propfind
  "The multistatus of `store` for the document `name`, or for the collection
  at `base` when there is none, at `depth`.

  The collection comes alone at depth \"0\", and with its documents
  otherwise, which a store without `:list` cannot give."
  [{:keys [list] :as store} base name depth]
  (cond
    name
    (if-let [version (version-of store name)]
      (multistatus [(entry (str base (http/encode name)) version)])
      (not-found))

    (= "0" (some-> depth str/trim))
    (multistatus [(entry base nil)])

    list
    (multistatus (cons (entry base nil)
                       (for [{:keys [name version]} (list)]
                         (entry (str base (http/encode name)) version))))

    :else
    (plain 403 "The collection cannot be listed")))

(defn- get-document
  "The document `name` with its ETag, the body left out for a `head?`."
  [{:keys [fetch] :as store} name head?]
  (if-let [{:keys [body version]} (when name (fetch name))]
    {:status  200
     :headers {"ETag" (quoted version) "Content-Type" (webdav/content-type name)}
     :body    (if head? "" body)}
    (if name (not-found) (not-allowed store nil))))

(defn- put-document
  "The document `name` written from the request `body`, at most `max-bytes`,
  under the precondition of the `headers`: 201 for a new one, 204 for a
  replaced one, 412 when the precondition failed, 413 when the body is too
  large."
  [{:keys [put] :as store} name headers body max-bytes]
  (if name
    (let [current (version-of store name)
          asked   (precondition headers current)
          text    (http/body-text body max-bytes)
          result  (when (and text (not= :failed asked)) (put name text asked))]
      (cond
        (nil? text)                             (plain 413 (str "A document holds at most " max-bytes " bytes"))
        (or (= :failed asked) (:conflict? result)) (plain 412 "Precondition Failed")
        :else                                   {:status (if current 204 201) :headers {"ETag" (quoted (:version result))} :body ""}))
    (not-allowed store nil)))

(defn- delete-document
  "The document `name` deleted from `store`: 204, or 404 when it is not
  there, or 405 when the store cannot delete."
  [{:keys [delete] :as store} name]
  (cond
    (not delete)                       (not-allowed store name)
    (and name (version-of store name)) (do (delete name)
                                           {:status 204 :headers {} :body ""})
    :else                              (not-found)))

;; RFC 4918, section 9.3.1: MKCOL is for an unmapped URL, and the
;; collection is there
(defn- make-collection
  "MKCOL refused, on the collection itself and within it."
  [store name]
  (not-allowed store name))

(defn handler
  "A Ring handler serving `store` over WebDAV under `base`, the path of the
  collection with its trailing slash, `\"/dav/\"`. Mount it under Pedestal,
  ring-jetty or http-kit for the routes `base` and everything below it;
  whether the router passes a request for the collection with its trailing
  slash, and a put's body unread, is the router's business. `:max-bytes`
  in `opts` is the most a put may carry, 32 MB unless given, far more than
  the state of any listener."
  ([store base]
   (handler store base {}))
  ([store base {:keys [max-bytes] :or {max-bytes (* 32 1024 1024)} :as opts}]
   {:pre [(map? opts)]}
   (let [collection (subs base 0 (dec (count base)))]
     (fn [{:keys [request-method uri headers body]}]
       ;; only a real servlet lower-cases the header names
       (let [headers (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers)]
         (try
           (let [name (when (and (str/starts-with? uri base) (> (count uri) (count base)))
                        (http/percent-decode (subs uri (count base))))]
             (cond
               (and (not name) (not (#{base collection} uri)))
               (not-found)

               (and name (not (plain-name? name)))
               (plain 400 "A document's name is a plain file name")

               :else
               (case request-method
                 :options     (options store)
                 :propfind    (propfind store base name (get headers "depth"))
                 (:get :head) (get-document store name (= :head request-method))
                 :put         (put-document store name headers body max-bytes)
                 :delete      (delete-document store name)
                 :mkcol       (make-collection store name)
                 (not-allowed store name))))
           ;; a malformed escape in the path, or a name the store's
           ;; filesystem cannot take
           (catch IllegalArgumentException _
             (plain 400 "A document's name is a plain file name"))
           (catch clojure.lang.ExceptionInfo e
             (plain 400 (ex-message e)))))))))
