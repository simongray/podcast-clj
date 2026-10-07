(ns dk.simongray.podcast.sync.webdav
  "A WebDAV collection (RFC 4918) as a document store, e.g. on Nextcloud,
  ownCloud, Seafile, Synology, Box, Fastmail or a Hetzner storage box.

  It uses GET, PUT, DELETE and a PROPFIND at depth one, with the ETag as
  the version. If-Match makes a put conditional, and If-None-Match: * asks
  for a document that isn't there yet. HEAD asks for the ETag when a
  server answers a put without one, and MKCOL makes the collection when it
  isn't there.

  The function store gives the store over a collection, with the
  credentials closed over. Below that, request builds each call as a
  request map, and response reads the answer. That way, the requests can
  go through any :send, and the tests run on recorded answers. In a
  browser, the server must let the page through CORS and expose the ETag.
  The other side is dk.simongray.podcast.sync.webdav.server, which serves
  any store over WebDAV."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.xml :as xml]))

(def calls
  "The calls that a store makes to a collection:

  - :list, for the documents in it
  - :fetch, :put and :delete, for one document
  - :head, for the version of one document
  - :make-collection, for the collection itself"
  #{:list :fetch :put :delete :head :make-collection})

;; RFC 4918, section 9.1
(def ^:no-doc propfind-body
  "The properties that a listing asks for: the ETag, and whether an entry
  is a collection."
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<d:propfind xmlns:d=\"DAV:\">"
       "<d:prop><d:getetag/><d:resourcetype/></d:prop>"
       "</d:propfind>"))

(defn content-type
  "The Content-Type that the document `name` is put and served with."
  [name]
  (cond
    (str/ends-with? name ".edn")  "application/edn; charset=utf-8"
    (str/ends-with? name ".opml") "text/x-opml; charset=utf-8"
    (str/ends-with? name ".txt")  "text/plain; charset=utf-8"
    :else                         "application/octet-stream"))

(defn ^:no-doc binary?
  "Whether the document `name` holds bytes rather than text, by its
  content-type."
  [name]
  (= "application/octet-stream" (content-type name)))

;; RFC 9110, sections 13.1.1 (If-Match) and 13.1.2 (If-None-Match: *)
(defn request
  "The request map for `call` on the collection at `root`, for the
  document `name` when the call takes one, with the `opts` below.

  The `call` is one of calls, and the `root` is a URL that ends in a
  slash. The `opts` give the :credentials, the :body to put, text or
  bytes, and :as :bytes for a fetch of bytes. For a put, they can give
  :if-version, the version that the document must still have, where nil
  means that it must not exist yet. Without :if-version, the put has no
  condition."
  ([call root]
   (request call root nil {}))
  ([call root name]
   (request call root name {}))
  ([call root name {:keys [credentials body] :as opts}]
   {:pre [(map? opts)]}
   (let [auth        (http/authorization credentials)
         headers     (cond-> {"User-Agent" http/user-agent}
                       auth (assoc "Authorization" auth))
         url         (str root (http/percent-encode name))
         version     (:if-version opts)
         listing     (assoc headers
                            "Depth"        "1"
                            "Content-Type" "application/xml; charset=utf-8")
         put-headers (fn []
                       (let [media (content-type name)]
                         (cond-> (assoc headers "Content-Type" media)
                           (contains? opts :if-version)
                           (assoc (if version "If-Match" "If-None-Match")
                                  (or version "*")))))
         [method url headers body]
         (case call
           :list            [:propfind root listing propfind-body]
           :fetch           [:get url headers nil]
           :head            [:head url headers nil]
           :put             [:put url (put-headers) body]
           :delete          [:delete url headers nil]
           :make-collection [:mkcol root headers nil]
           (throw (ex-info (str "A call a store does not make: " call)
                           {:call call})))]
     (cond-> {:method method :url url :headers headers}
       (some? body)          (assoc :body body)
       (= :bytes (:as opts)) (assoc :as :bytes)))))

(def ^:no-doc uri->alias
  "The library's namespace aliases, with WebDAV's as :d, so that a
  multistatus reads the same whatever prefix a server wrote."
  (assoc xml/uri->alias "DAV:" :d))

(defn- document-name
  "The name of the document that `href` addresses: its last path segment,
  decoded."
  [href]
  ;; a match may start only where a run of slashes starts: /+$ alone is
  ;; tried again from every slash of a long run, quadratically
  (-> (str/replace href #"(^|[^/])/+$" "$1")
      (str/split #"/")
      last
      http/percent-decode))

(defn- entries
  "The entries of the multistatus `body`, as maps of :href, :etag and
  :collection?."
  [body]
  (let [etag        (fn [prop]
                      (some-> (xml/element prop :d/getetag)
                              xml/text
                              str/trim
                              not-empty))
        collection? (fn [prop]
                      (some-> (xml/element prop :d/resourcetype)
                              (xml/element :d/collection)))]
    (for [r (xml/elements (xml/parse body uri->alias) :d/response)
          :let [href  (some-> (xml/element r :d/href) xml/text str/trim)
                props (for [ps (xml/elements r :d/propstat)
                            :let [p (xml/element ps :d/prop)]
                            :when p]
                        p)]
          :when href]
      {:href        href
       :etag        (some etag props)
       :collection? (boolean (some collection? props))})))

;; RFC 4918, sections 9.1.1 (207), 9.3.1 (405 for MKCOL) and 9.7.1 (409
;; for a put without its collection)
(defn response
  "What the `answer` to `call`, a map of :status, :headers and :body, says:

  - :list, the documents as maps of :name and :version, or none for a
    collection that isn't there yet
  - :fetch, the :body and :version, or nil when the document isn't there
  - :head, the :version
  - :put, the :version when it was written, :conflict? when the
    precondition failed, or :missing-collection? when the collection isn't
    there
  - :delete and :make-collection, nil

  Any other status throws ::failed with the :status."
  [call {:keys [status headers body] :as answer}]
  (let [etag    (first (get headers "etag"))
        fail    (fn []
                  (throw (ex-info (str "The server answered " status " to "
                                       (name call))
                                  {:type ::failed :call call :status status})))
        folder? #(or (:collection? %) (str/ends-with? (:href %) "/"))
        listed  (fn [{:keys [href etag]}]
                  {:name (document-name href) :version etag})]
    (case call
      :list            (case status
                         207 (->> (entries body)
                                  (remove folder?)
                                  (map listed)
                                  (sort-by :name)
                                  vec)
                         404 []
                         (fail))
      :fetch           (case status
                         200 {:body body :version etag}
                         404 nil
                         (fail))
      :head            (case status
                         200 {:version etag}
                         (fail))
      :put             (case status
                         (200 201 204) {:version etag}
                         412           {:conflict? true}
                         409           {:missing-collection? true}
                         (fail))
      :delete          (case status
                         (200 204 404) nil
                         (fail))
      :make-collection (case status
                         (201 405) nil
                         (fail)))))

(defn- host
  "The host of the URL `root`, as http/origin reads it, without
  credentials. It has its port when that isn't the default port of the
  scheme."
  [root]
  (when-let [[scheme host port] (http/origin root)]
    (cond-> host
      (not= port (get {"http" 80 "https" 443} scheme)) (str ":" port))))

(defn store
  "The store over the WebDAV collection at `root`, a URL such as
  https://dav.example.org/podcasts/, with the `opts` below:

  - :credentials, a :username and a :password, or a :token, as a map or a
    function of no arguments that returns one
  - :name, for the UI, the host by default
  - :send, a function that sends a request map, in place of http/send!
  - :timeout-seconds, how long a request can take, 30 by default

  The collection is made on the first put when it isn't there, and a
  server that answers a put without an ETag is asked for one."
  ([root]
   (store root {}))
  ([root {:keys [credentials name send timeout-seconds] :as opts}]
   {:pre [(map? opts)]}
   (let [root  (if (str/ends-with? root "/") root (str root "/"))
         call! (fn [call name params]
                 (let [params (assoc params :credentials credentials)
                       asked  (cond-> (request call root name params)
                                timeout-seconds
                                (assoc :timeout-seconds timeout-seconds))]
                   (async/then ((or send http/send!) asked)
                               #(response call %))))
         put!  (fn put! [name body params made?]
                 (async/then
                  (call! :put name (assoc params :body body))
                  (fn [result]
                    (cond
                      (and (:missing-collection? result) (not made?))
                      (async/then (call! :make-collection nil {})
                                  (fn [_] (put! name body params true)))

                      (:missing-collection? result)
                      (throw (ex-info (str "The collection is not there, and "
                                           "making it did not help: " root)
                                      {:type   ::failed
                                       :call   :put
                                       :status 409}))

                      (and (not (:conflict? result))
                           (nil? (:version result)))
                      (call! :head name {})

                      :else
                      result))))]
     {:name   (or name (host root) "WebDAV")
      :fetch  (fn [name opts] (call! :fetch name (select-keys opts [:as])))
      :put    (fn [name body params] (put! name body params false))
      :list   (fn [] (call! :list nil {}))
      :delete (fn [name] (call! :delete name {}))})))

#?(:clj
   (comment
     (require '[dk.simongray.podcast.sync.store :as sync.store])
     (def s (store "http://localhost:8300/dav/"))
     (sync.store/check! s)
     (def b (sync.store/backend s {:opml? true}))
     #_.))
