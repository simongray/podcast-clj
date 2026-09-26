(ns inspector.dav
  "A document store served over WebDAV: the four methods a sync store needs,
  GET, PUT, DELETE and PROPFIND at depth one, with ETags and the
  preconditions, and OPTIONS, HEAD and MKCOL so that a client can ask. Any
  store of dk.simongray.podcast.app.sync.store can be served; the inspector
  serves a folder, so that the browser player, a WebDAV client and the
  folder store meet in one place, and the library's own WebDAV store has a
  real server to be tested against."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.http :as http]
            [io.pedestal.interceptor :as interceptor]))

(defn- escape
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- quoted
  "`version` as an ETag."
  [version]
  (str "\"" version "\""))

(defn- unquoted
  "The version in the ETag `etag`, its quotes and a weak marker removed."
  [etag]
  (some-> etag str/trim (str/replace #"^W/" "") (str/replace #"^\"|\"$" "")))

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
  [& entries]
  {:status  207
   :headers {"Content-Type" "application/xml; charset=utf-8"}
   :body    (str "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">"
                 (apply str entries)
                 "</d:multistatus>")})

(defn- plain
  [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- precondition
  "What the request's If-Match or If-None-Match asks of a put, as the
  `:if-version` a store takes: absent for none. The header names are read
  whatever their case, since only a real servlet lower-cases them."
  [{:keys [headers]}]
  (let [headers (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers)]
    (cond
      (get headers "if-match")              {:if-version (unquoted (get headers "if-match"))}
      (= "*" (get headers "if-none-match")) {:if-version nil}
      :else                                 {})))

(defn- content-type
  [name]
  (cond
    (str/ends-with? name ".edn")  "application/edn; charset=utf-8"
    (str/ends-with? name ".opml") "text/x-opml; charset=utf-8"
    :else                         "application/octet-stream"))

(defn handler
  "A handler serving `store` over WebDAV at `base`, the path of the
  collection with its trailing slash, for the routes `base` and
  `base*path`."
  [{:keys [fetch put list delete]} base]
  (fn [{:keys [request-method path-params] :as request}]
    (let [name (not-empty (str (:path path-params)))]
      (try
        (case request-method
          :options  {:status  200
                     :headers {"DAV" "1" "Allow" "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL"}
                     :body    ""}
          :propfind (if name
                      (if-let [{:keys [version]} (fetch name)]
                        (multistatus (entry (str base (http/encode name)) version))
                        (plain 404 "Not Found"))
                      (apply multistatus
                             (entry base nil)
                             (for [{:keys [name version]} (list)]
                               (entry (str base (http/encode name)) version))))
          (:get :head) (if-let [{:keys [body version]} (when name (fetch name))]
                         {:status  200
                          :headers {"ETag" (quoted version) "Content-Type" (content-type name)}
                          :body    (if (= :head request-method) "" body)}
                         (plain (if name 404 405) (if name "Not Found" "Method Not Allowed")))
          :put      (if name
                      (let [existed? (some? (fetch name))
                            body     (some-> (:body request) (slurp :encoding "UTF-8"))
                            result   (put name (or body "") (precondition request))]
                        (if (:conflict? result)
                          (plain 412 "Precondition Failed")
                          {:status (if existed? 204 201) :headers {"ETag" (quoted (:version result))} :body ""}))
                      (plain 405 "Method Not Allowed"))
          :delete   (if (and name (fetch name))
                      (do (delete name)
                          {:status 204 :headers {} :body ""})
                      (plain 404 "Not Found"))
          :mkcol    (if name
                      (plain 405 "Method Not Allowed")
                      {:status 201 :headers {} :body ""})
          (plain 405 "Method Not Allowed"))
        (catch clojure.lang.ExceptionInfo e
          (plain 400 (ex-message e)))))))

(defn requests
  "An interceptor for the requests under `base`, to run before Pedestal's
  own: it routes a request for the collection with its trailing slash, as
  WebDAV clients address one, to the route without it, which is the only
  one Pedestal's router matches; and it drops the content type of a put, so
  that the body-params interceptor, which would otherwise read an EDN or a
  form body for its own purposes and leave the handler an empty stream,
  passes the body on as it came."
  [base]
  (let [collection (subs base 0 (dec (count base)))]
    (interceptor/interceptor
     {:name  ::requests
      :enter (fn [context]
               (update context :request
                       (fn [{:keys [path-info uri] :as request}]
                         (let [path (or path-info uri)]
                           (cond
                             (= path base)                (assoc request :path-info collection :uri collection)
                             (str/starts-with? path base) (-> request
                                                              (update :headers dissoc "content-type")
                                                              (dissoc :content-type))
                             :else                        request)))))})))
