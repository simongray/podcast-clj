(ns dk.simongray.podcast.app.sync.webdav
  "A WebDAV collection as a document store: Nextcloud, ownCloud, Seafile,
  Synology, Box, Fastmail, a Hetzner storage box, or the inspector's own
  folder. Four methods do it, GET, PUT, DELETE and a PROPFIND at depth one
  for the listing, with the ETag as the version; `If-Match` makes a put
  conditional and `If-None-Match: *` asks for a document not there yet.

  `request` builds each call as a request map and `response` reads the
  answer, so that a browser sends them with fetch, the server's origin
  permitting, and the tests run on recorded answers. `store` is the store
  over a collection for `dk.simongray.podcast.app.sync.store/backend`,
  with the credentials closed over: a username and an app password, or a
  token."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.xml :as xml]))

(def calls
  "What a store asks a collection: `:list` for the documents in it, `:fetch`,
  `:put` and `:delete` for one, `:head` for its version alone, and
  `:make-collection` for the collection itself."
  #{:list :fetch :put :delete :head :make-collection})

(defn- base64
  [s]
  #?(:clj  (.encodeToString (java.util.Base64/getEncoder) (.getBytes ^String s "UTF-8"))
     :cljs (js/btoa (js/unescape (js/encodeURIComponent s)))))

(defn- authorization
  "The Authorization header for `credentials`, a map or a function of no
  arguments returning one: `{:username :password}` for basic
  authentication, `{:token}` for a bearer token; nil for none."
  [credentials]
  (let [{:keys [username password token]} (if (fn? credentials) (credentials) credentials)]
    (cond
      token    (str "Bearer " token)
      username (str "Basic " (base64 (str username ":" password)))
      :else    nil)))

(def ^:no-doc propfind-body
  "The properties a listing asks for: the ETag and whether an entry is a
  collection."
  "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>")

(defn- content-type
  [name]
  (cond
    (str/ends-with? name ".edn")  "application/edn; charset=utf-8"
    (str/ends-with? name ".opml") "text/x-opml; charset=utf-8"
    :else                         "application/octet-stream"))

(defn request
  "The request map for `call`, one of `calls`, on the collection at `root`,
  a URL ending in a slash, for the document `name` where the call takes
  one. `opts` carry the `:credentials`, the `:body` to put, and for a put
  `:if-version`: the version the document must still have, or nil for a
  document that must not be there yet; absent, the put is unconditional."
  ([call root]
   (request call root nil {}))
  ([call root name]
   (request call root name {}))
  ([call root name {:keys [credentials body] :as opts}]
   {:pre [(map? opts)]}
   (let [auth    (authorization credentials)
         headers (cond-> {"User-Agent" http/user-agent} auth (assoc "Authorization" auth))
         url     (str root (http/encode name))
         [method url headers body]
         (case call
           :list            [:propfind root (assoc headers "Depth" "1" "Content-Type" "application/xml; charset=utf-8") propfind-body]
           :fetch           [:get url headers nil]
           :head            [:head url headers nil]
           :put             [:put url (cond-> (assoc headers "Content-Type" (content-type name))
                                        (contains? opts :if-version)
                                        (assoc (if (:if-version opts) "If-Match" "If-None-Match") (or (:if-version opts) "*")))
                             body]
           :delete          [:delete url headers nil]
           :make-collection [:mkcol root headers nil]
           (throw (ex-info (str "A call a store does not make: " call) {:call call})))]
     (cond-> {:method method :url url :headers headers}
       (some? body) (assoc :body body)))))

(def ^:no-doc uri->alias
  "The library's namespace aliases, with WebDAV's bound to `d`, so that a
  multistatus reads the same whatever prefix a server wrote."
  (assoc xml/uri->alias "DAV:" :d))

(defn- document-name
  "The name of the document an `href` addresses: its last path segment,
  decoded."
  [href]
  (-> (str/replace href #"/+$" "")
      (str/split #"/")
      last
      http/percent-decode))

(defn- entries
  "The entries of the multistatus `body`: `{:href :etag :collection?}`."
  [body]
  (for [r (xml/elements (xml/parse body uri->alias) :d/response)
        :let [href  (some-> (xml/element r :d/href) xml/text str/trim)
              props (for [ps (xml/elements r :d/propstat) :let [p (xml/element ps :d/prop)] :when p] p)]
        :when href]
    {:href        href
     :etag        (some #(some-> (xml/element % :d/getetag) xml/text str/trim not-empty) props)
     :collection? (boolean (some #(some-> (xml/element % :d/resourcetype) (xml/element :d/collection)) props))}))

(defn response
  "What the answer `{:status :headers :body}` to `call` says. `:list`: the
  documents as `[{:name :version} …]`, none for a collection not there yet;
  `:fetch`: `{:body :version}`, nil when absent; `:head`: `{:version}`;
  `:put`: `{:version}` when written, `{:conflict? true}` when the
  precondition failed, `{:missing-collection? true}` when the collection
  is not there; `:delete` and `:make-collection`: nil. Any other status
  throws `::failed`."
  [call {:keys [status headers body]}]
  (let [etag (first (get headers "etag"))
        fail (fn [] (throw (ex-info (str "The server answered " status " to " (name call))
                                    {:type ::failed :call call :status status})))]
    (case call
      :list            (case status
                         207 (->> (entries body)
                                  (remove #(or (:collection? %) (str/ends-with? (:href %) "/")))
                                  (map (fn [{:keys [href etag]}] {:name (document-name href) :version etag}))
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
  [root]
  (second (re-find #"^[a-z]+://([^/]+)" root)))

#?(:cljs
   (defn- fetch-with
     "A sender over `fetch`, js/fetch or one given: the request map sent, the
     answer as `{:status :headers :body}` in a promise."
     [fetch]
     (fn [{:keys [method url headers body]}]
       (-> (fetch url (clj->js (cond-> {:method (str/upper-case (name method)) :headers headers}
                                 (some? body) (assoc :body body))))
           (.then (fn [res]
                    (.then (.text res)
                           (fn [text]
                             {:status  (.-status res)
                              :headers {"etag" [(.get (.-headers res) "etag")]}
                              :body    text}))))))))

(defn store
  "The store over the WebDAV collection at `root`, a URL such as
  `https://cloud.example.org/remote.php/dav/files/simon/podcasts/`. `opts`:

      {:credentials     {:username \"simon\" :password \"app-password\"}  ; or {:token \"…\"},
                                                                       ; a map or a function of
                                                                       ; no arguments returning one
       :name            \"Nextcloud\"   ; for the UI; default the host
       :timeout-seconds 30            ; on the JVM
       :fetch           js/fetch}     ; in ClojureScript, the fetch to send with

  The collection is created on the first put when it is not there, and a
  server that answers a put without an ETag is asked for one."
  ([root]
   (store root {}))
  ([root {:keys [credentials name] :as opts}]
   {:pre [(map? opts)]}
   (let [root  (if (str/ends-with? root "/") root (str root "/"))
         send  #?(:clj  (fn [request] (http/call! (assoc request :timeout-seconds (:timeout-seconds opts 30))))
                  :cljs (fetch-with (or (:fetch opts) js/fetch)))
         call! (fn [call name params]
                 (store/then (send (request call root name (assoc params :credentials credentials)))
                             #(response call %)))
         put!  (fn [name body params]
                 (store/then (call! :put name (assoc params :body body))
                             (fn [result]
                               (cond
                                 (:missing-collection? result)
                                 (store/then (call! :make-collection nil {})
                                             (fn [_] (call! :put name (assoc params :body body))))

                                 (and (not (:conflict? result)) (nil? (:version result)))
                                 (call! :head name {})

                                 :else result))))]
     {:name   (or name (host root) root)
      :fetch  (fn [name] (call! :fetch name {}))
      :put    (fn [name body params] (put! name body params))
      :list   (fn [] (call! :list nil {}))
      :delete (fn [name] (call! :delete name {}))})))

#?(:clj
   (comment
     (def s (store "http://localhost:8300/dav/"))
     ((:list s))
     ((:put s) "test.edn" "{:version 1 :state {}}" {})
     ((:fetch s) "test.edn")
     #_.))
