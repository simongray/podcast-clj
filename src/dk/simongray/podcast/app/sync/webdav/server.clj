(ns dk.simongray.podcast.app.sync.webdav.server
  "Any document store served over WebDAV, for a Clojure backend whose
  browser clients sync through their own origin: `handler` is a Ring
  handler answering GET, HEAD, PUT, DELETE and a PROPFIND at depth zero or
  one, with ETags and the preconditions, and OPTIONS and MKCOL so that a
  client can ask. A store without `:list` is served without the listing of
  the collection, and one without `:delete` without DELETE; a client of
  such a server leaves the same keys out of its WebDAV store."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            [dk.simongray.podcast.http :as http]))

(defn- quoted
  [version]
  (str "\"" version "\""))

(defn- unquoted
  "The version in the ETag `etag`, its quotes and a weak marker removed."
  [etag]
  (some-> etag str/trim (str/replace #"^W/" "") (str/replace #"^\"|\"$" "")))

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

(defn- not-allowed
  []
  (plain 405 "Method Not Allowed"))

(defn- header
  "The value of the header `name`, given in lower case, among the request
  `headers`, whatever the case of their names, since only a real servlet
  lower-cases them."
  [headers name]
  (some (fn [[k v]] (when (= name (str/lower-case k)) v)) headers))

(defn- precondition
  "What the request's If-Match or If-None-Match asks of a put, as the
  `:if-version` a store takes: absent for none."
  [headers]
  (let [if-match (header headers "if-match")]
    (cond
      if-match                                 {:if-version (unquoted if-match)}
      (= "*" (header headers "if-none-match")) {:if-version nil}
      :else                                    {})))

(defn- body-text
  "The text of a Ring request `body`: a string, a stream, or nothing."
  [body]
  (cond
    (nil? body)    ""
    (string? body) body
    :else          (slurp body :encoding "UTF-8")))

(defn- version-of
  "The version of the document `name` in `store`, nil when it is not there:
  from the listing when the store has one, so that no body is read to
  learn that a document exists."
  [{:keys [list fetch]} name]
  (if list
    (some #(when (= name (:name %)) (:version %)) (list))
    (:version (fetch name))))

(defn- options
  "The methods the handler answers, DELETE only when `store` can delete."
  [{:keys [delete]}]
  {:status  200
   :headers {"DAV" "1" "Allow" (str "OPTIONS, GET, HEAD, PUT, " (when delete "DELETE, ") "PROPFIND, MKCOL")}
   :body    ""})

(defn- propfind
  "The multistatus for the document `name`, or for the collection at `base`
  when there is none: the collection alone at `depth` \"0\", and with its
  documents otherwise, which a store without `:list` cannot give."
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
    (plain 405 "The collection cannot be listed")))

(defn- get-document
  "The document `name` with its ETag, the body left out for a `head?`."
  [{:keys [fetch]} name head?]
  (if-let [{:keys [body version]} (when name (fetch name))]
    {:status  200
     :headers {"ETag" (quoted version) "Content-Type" (webdav/content-type name)}
     :body    (if head? "" body)}
    (if name (not-found) (not-allowed))))

(defn- put-document
  "The document `name` written from the request `body` under the
  precondition of the `headers`: 201 for a new one, 204 for a replaced
  one, 412 when the precondition failed."
  [{:keys [put] :as store} name headers body]
  (if name
    (let [existed? (some? (version-of store name))
          result   (put name (body-text body) (precondition headers))]
      (if (:conflict? result)
        (plain 412 "Precondition Failed")
        {:status (if existed? 204 201) :headers {"ETag" (quoted (:version result))} :body ""}))
    (not-allowed)))

(defn- delete-document
  "The document `name` deleted: 204, or 404 when it is not there, or 405
  when the store cannot delete."
  [{:keys [delete] :as store} name]
  (cond
    (not delete)                       (not-allowed)
    (and name (version-of store name)) (do (delete name)
                                           {:status 204 :headers {} :body ""})
    :else                              (not-found)))

(defn- make-collection
  "The collection itself acknowledged, a collection within it refused."
  [name]
  (if name
    (not-allowed)
    {:status 201 :headers {} :body ""}))

(defn handler
  "A Ring handler serving `store` over WebDAV under `base`, the path of the
  collection with its trailing slash, `\"/dav/\"`. Mount it under Pedestal,
  ring-jetty or http-kit for the routes `base` and everything below it;
  whether the router passes a request for the collection with its trailing
  slash, and a put's body unread, is the router's business."
  [store base]
  (let [collection (subs base 0 (dec (count base)))]
    (fn [{:keys [request-method uri headers body]}]
      (let [name (when (and (str/starts-with? uri base) (> (count uri) (count base)))
                   (http/percent-decode (subs uri (count base))))]
        (try
          (cond
            (and (not name) (not (#{base collection} uri)))
            (not-found)

            (and name (str/includes? name "/"))
            (plain 400 "A document's name is a plain file name")

            :else
            (case request-method
              :options     (options store)
              :propfind    (propfind store base name (header headers "depth"))
              (:get :head) (get-document store name (= :head request-method))
              :put         (put-document store name headers body)
              :delete      (delete-document store name)
              :mkcol       (make-collection name)
              (not-allowed)))
          (catch clojure.lang.ExceptionInfo e
            (plain 400 (ex-message e))))))))
