(ns inspector.dav
  "What Pedestal needs so that the library's WebDAV handler,
  dk.simongray.podcast.app.sync.webdav/handler, can serve the inspector's
  folder at /dav/: an interceptor ahead of Pedestal's own, since the router
  does not match a collection's trailing slash and the body-params
  interceptor would read a put's body before the handler sees it."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor :as interceptor]))

(defn requests
  "An interceptor for the requests under `base`, the collection's path with
  its trailing slash, to run before Pedestal's own: it routes a request for
  the collection with its trailing slash, as WebDAV clients address one, to
  the route without it, which is the only one Pedestal's router matches;
  and it drops the content type of a put, so that the body-params
  interceptor, which would otherwise read an EDN or a form body for its own
  purposes and leave the handler an empty stream, passes the body on as it
  came."
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
