(ns inspector.dav
  "What Pedestal needs so that the library's WebDAV handler,
  dk.simongray.podcast.sync.webdav.server/handler, can serve the
  inspector's folder at /dav/: an interceptor ahead of Pedestal's own,
  since the router does not match a collection's trailing slash and the
  body-params interceptor would read a put's body before the handler sees
  it."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor :as interceptor]))

(defn requests
  "The interceptor for the requests under `base`, the collection's path
  with its trailing slash: the collection addressed with its slash is routed
  to the route without it, and a put loses its content type so that
  body-params leaves the body alone."
  [base]
  (let [collection (subs base 0 (dec (count base)))]
    (interceptor/interceptor
     {:name  ::requests
      :enter (fn [context]
               (update context :request
                       (fn [{:keys [path-info uri] :as request}]
                         (let [path (or path-info uri)]
                           (cond
                             (= path base)
                             (assoc request
                                    :path-info collection
                                    :uri       collection)

                             (str/starts-with? path base)
                             (-> request
                                 (update :headers dissoc "content-type")
                                 (dissoc :content-type))

                             :else
                             request)))))})))
