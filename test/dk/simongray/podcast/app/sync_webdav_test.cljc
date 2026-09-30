(ns dk.simongray.podcast.app.sync-webdav-test
  "The WebDAV store: the calls as request maps, the answers read, in the
  shape Nextcloud writes them and in another prefix, and two devices kept
  in step through a server standing in for one, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [dk.simongray.podcast.util.http :as http])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.app.sync :as sync]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.app.sync.webdav :as webdav]
            #?(:clj [dk.simongray.podcast.app.sync.webdav.server :as server])
            #?(:cljs [dk.simongray.podcast.util.http :as http])
            [dk.simongray.podcast.test-support :refer [t2 t3 a phone-state laptop-state fixture #?@(:clj [same?])]]))

(def root
  "https://cloud.example.org/remote.php/dav/files/simon/podcasts/")

(def nextcloud-propfind
  (fixture "webdav/nextcloud-propfind.xml"))

(deftest a-backend-in-one-call
  (let [b (webdav/backend "https://cloud.example.org/remote.php/dav/files/simon/podcasts/"
                          {:credentials {:username "simon" :password "x"} :opml? true :app "test"})]
    (is (= "cloud.example.org" (:name b)) "named after the host")
    (is (= "cloud.example.org" (:name (webdav/store "https://simon:secret@cloud.example.org/podcasts/")))
        "never after the credentials a URL carries")
    (is (fn? (:pull b)))
    (is (fn? (:push b)))
    (is (= "Nextcloud" (:name (webdav/backend "https://cloud.example.org/dav/" {:name "Nextcloud"}))))))

(deftest requests
  (let [creds {:credentials {:username "simon" :password "app pass"}}
        listing (webdav/request :list root nil creds)]
    (is (= :propfind (:method listing)))
    (is (= root (:url listing)))
    (is (= "1" (get-in listing [:headers "Depth"])))
    (is (= "Basic c2ltb246YXBwIHBhc3M=" (get-in (http/revealed listing) [:headers "Authorization"])))
    (is (not (re-find #"c2ltb246" (pr-str listing))) "a request that is printed does not show the password")
    (is (str/includes? (:body listing) "<d:getetag/>"))
    (is (= "Bearer t"
           (get-in (http/revealed (webdav/request :list root nil {:credentials {:token "t"}})) [:headers "Authorization"])))
    (is (= "Bearer later"
           (get-in (http/revealed (webdav/request :list root nil {:credentials (constantly {:token "later"})}))
                   [:headers "Authorization"]))
        "credentials may be a function, for a token that is refreshed")
    (is (nil? (get-in (webdav/request :list root) [:headers "Authorization"])))
    (testing "one document"
      (is (= [:get (str root "phone%201.edn")] ((juxt :method :url) (webdav/request :fetch root "phone 1.edn"))))
      (is (= [:head (str root "x.edn")] ((juxt :method :url) (webdav/request :head root "x.edn"))))
      (is (= [:delete (str root "x.edn")] ((juxt :method :url) (webdav/request :delete root "x.edn"))))
      (is (= [:mkcol root] ((juxt :method :url) (webdav/request :make-collection root)))))
    (testing "a put and its preconditions"
      (let [put (fn [opts] (:headers (webdav/request :put root "x.edn" (assoc opts :body "{}"))))]
        (is (= "\"abc\"" (get (put {:if-version "\"abc\""}) "If-Match")))
        (is (= "*" (get (put {:if-version nil}) "If-None-Match")))
        (is (not-any? #{"If-Match" "If-None-Match"} (keys (put {}))))
        (is (= "application/edn; charset=utf-8" (get (put {}) "Content-Type")))
        (is (= "text/x-opml; charset=utf-8" (get-in (webdav/request :put root "subscriptions.opml" {:body "<opml/>"}) [:headers "Content-Type"])))
        (is (= "{}" (:body (webdav/request :put root "x.edn" {:body "{}"}))))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (webdav/request :lock root "x.edn")))))

(deftest responses
  (testing "a listing, in Nextcloud's shape: the collection itself and a folder in it left out"
    (is (= [{:name "8f1c9e2a-7a4b-4c1d-9e2f-0a1b2c3d4e5f.edn" :version "\"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6\""}
            {:name "laptop at home.edn" :version "\"0f9e8d7c6b5a4938271605f4e3d2c1b0\""}
            {:name "subscriptions.opml" :version "\"123456789abcdef0123456789abcdef0\""}]
           (webdav/response :list {:status 207 :headers {} :body nextcloud-propfind}))))
  (testing "the same listing under another prefix and with absolute hrefs"
    (let [body (-> nextcloud-propfind
                   (str/replace "d:" "D:")
                   (str/replace "xmlns:d=" "xmlns:D=")
                   (str/replace "<D:href>/remote.php" "<D:href>https://cloud.example.org/remote.php"))]
      (is (= ["8f1c9e2a-7a4b-4c1d-9e2f-0a1b2c3d4e5f.edn" "laptop at home.edn" "subscriptions.opml"]
             (map :name (webdav/response :list {:status 207 :headers {} :body body}))))))
  (is (= [] (webdav/response :list {:status 404 :headers {} :body ""})) "a collection not there yet lists nothing")
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"500" (webdav/response :list {:status 500 :headers {} :body ""})))
  (testing "one document"
    (is (= {:body "{}" :version "\"v1\""} (webdav/response :fetch {:status 200 :headers {"etag" ["\"v1\""]} :body "{}"})))
    (is (nil? (webdav/response :fetch {:status 404 :headers {} :body "Not Found"})))
    (is (= {:version "\"v1\""} (webdav/response :head {:status 200 :headers {"etag" ["\"v1\""]} :body ""}))))
  (testing "a put"
    (is (= {:version "\"v2\""} (webdav/response :put {:status 201 :headers {"etag" ["\"v2\""]} :body ""})))
    (is (= {:version nil} (webdav/response :put {:status 204 :headers {} :body ""})) "a server that says nothing of the version")
    (is (= {:conflict? true} (webdav/response :put {:status 412 :headers {} :body ""})))
    (is (= {:missing-collection? true} (webdav/response :put {:status 409 :headers {} :body ""})))
    (is (thrown? #?(:clj Exception :cljs js/Error) (webdav/response :put {:status 403 :headers {} :body ""}))))
  (is (nil? (webdav/response :delete {:status 204 :headers {} :body ""})))
  (is (nil? (webdav/response :delete {:status 404 :headers {} :body ""})))
  (is (nil? (webdav/response :make-collection {:status 405 :headers {} :body ""})) "a collection already there"))

(defn- quoted
  [version]
  (str "\"" version "\""))

(defn- stand-in
  "A WebDAV server over `documents`, an atom of name to `{:body :version}`,
  as a function of a request map to a response map: the four methods, the
  preconditions, and a collection that appears on the first put."
  [documents]
  (fn [{:keys [method url headers body]}]
    (let [name (some-> (re-find #"/dav/(.+)$" url) second http/percent-decode)
          doc  (get @documents name)
          entry (fn [href version]
                  (str "<D:response><D:href>" href "</D:href><D:propstat><D:prop>"
                       (if version
                         (str "<D:getetag>&quot;" version "&quot;</D:getetag><D:resourcetype/>")
                         "<D:resourcetype><D:collection/></D:resourcetype>")
                       "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"))]
      (case method
        :propfind (if (::created @documents)
                    {:status  207
                     :headers {}
                     :body    (str "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">"
                                   (entry "/dav/" nil)
                                   (apply str (for [[n {:keys [version]}] (dissoc @documents ::created)]
                                                (entry (str "/dav/" (http/encode n)) version)))
                                   "</D:multistatus>")}
                    {:status 404 :headers {} :body ""})
        :get      (if doc
                    {:status 200 :headers {"etag" [(quoted (:version doc))]} :body (:body doc)}
                    {:status 404 :headers {} :body ""})
        :mkcol    (do (swap! documents assoc ::created true)
                      {:status 201 :headers {} :body ""})
        :put      (cond
                    (not (::created @documents))
                    {:status 409 :headers {} :body ""}

                    (and (contains? headers "If-Match") (not= (get headers "If-Match") (some-> doc :version quoted)))
                    {:status 412 :headers {} :body ""}

                    (and (contains? headers "If-None-Match") doc)
                    {:status 412 :headers {} :body ""}

                    :else
                    (let [version (str (inc (or (some-> doc :version parse-long) 0)))]
                      (swap! documents assoc name {:body body :version version})
                      {:status (if doc 204 201) :headers {"etag" [(quoted version)]} :body ""}))
        :delete   (do (swap! documents dissoc name)
                      {:status 204 :headers {} :body ""})))))

#?(:clj
   (defn- ring
     "A Ring request for the handler."
     [method uri & {:keys [headers body]}]
     {:request-method method :uri uri :headers (or headers {}) :body body}))

#?(:clj
   (deftest serving-a-store
     (let [documents (atom {})
           handler   (server/handler (store/memory documents) "/dav/")]
       (testing "the collection, with and without its slash"
         (is (= 207 (:status (handler (ring :propfind "/dav/")))))
         (is (= 207 (:status (handler (ring :propfind "/dav")))))
         (is (str/includes? (:body (handler (ring :propfind "/dav/"))) "<d:collection/>"))
         (is (= 200 (:status (handler (ring :options "/dav/")))))
         (is (= "1" (get-in (handler (ring :options "/dav/")) [:headers "DAV"])))
         (is (str/includes? (get-in (handler (ring :options "/dav/")) [:headers "Allow"]) "DELETE"))
         (is (= 405 (:status (handler (ring :get "/dav/")))))
         (is (= "OPTIONS, PROPFIND" (get-in (handler (ring :get "/dav/")) [:headers "Allow"]))
             "a 405 names the methods of the target, as RFC 9110 asks")
         (is (= 404 (:status (handler (ring :get "/elsewhere"))))))
       (testing "a document put, listed, fetched and deleted, the name decoded"
         (let [{:keys [status headers]} (handler (ring :put "/dav/phone%201.edn" :body "{:version 1 :state {}}"))]
           (is (= 201 status))
           (is (= "\"1\"" (get headers "ETag")))
           (is (= ["phone 1.edn"] (keys @documents)))
           (is (= "{:version 1 :state {}}" (:body (handler (ring :get "/dav/phone%201.edn")))))
           (is (= "" (:body (handler (ring :head "/dav/phone%201.edn")))))
           (is (str/includes? (:body (handler (ring :propfind "/dav/"))) "<d:href>/dav/phone%201.edn</d:href>"))
           (is (not (str/includes? (:body (handler (ring :propfind "/dav/" :headers {"depth" "0"}))) "phone%201.edn"))
               "at depth zero, the collection alone")
           (is (= [{:name "phone 1.edn" :version "\"1\""}]
                  (webdav/response :list (update (handler (ring :propfind "/dav/")) :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %)))))
           (is (= 412 (:status (handler (ring :put "/dav/phone%201.edn" :body "x" :headers {"If-Match" "\"stale\""})))))
           (is (= 412 (:status (handler (ring :put "/dav/phone%201.edn" :body "x" :headers {"If-Match" "W/\"1\""}))))
               "a weak tag never matches: If-Match uses the strong comparison")
           (is (= 412 (:status (handler (ring :put "/dav/phone%201.edn" :body "x" :headers {"if-none-match" "*"})))))
           (is (= 204 (:status (handler (ring :put "/dav/phone%201.edn" :body "{}" :headers {"If-Match" "\"stale\", \"1\""}))))
               "one tag of a list matches")
           (is (= 204 (:status (handler (ring :put "/dav/phone%201.edn" :body "{}" :headers {"If-Match" "\"2\""})))))
           (is (= 204 (:status (handler (ring :delete "/dav/phone%201.edn")))))
           (is (= 404 (:status (handler (ring :delete "/dav/phone%201.edn")))))
           (is (= 404 (:status (handler (ring :get "/dav/phone%201.edn")))))))
       (testing "what is refused"
         (is (= 400 (:status (handler (ring :put "/dav/sub/x.edn" :body "x")))) "no paths within the collection")
         (is (= 400 (:status (handler (ring :put "/dav/%2e%2e" :body "x")))) "nor . or ..")
         (is (= 400 (:status (handler (ring :put "/dav/x%00.edn" :body "x")))) "nor a control character")
         (is (= 405 (:status (handler (ring :lock "/dav/x.edn")))))
         (is (= "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND" (get-in (handler (ring :lock "/dav/x.edn")) [:headers "Allow"])))
         (is (= 405 (:status (handler (ring :mkcol "/dav/sub")))))
         (is (= 405 (:status (handler (ring :mkcol "/dav/")))) "the collection is there, and MKCOL is for an unmapped URL"))
       (testing "a percent sign that starts no escape is part of the name"
         (is (= 404 (:status (handler (ring :get "/dav/100%.edn")))))))
     (testing "If-Match: *, and the size of a put"
       (let [handler (server/handler (store/memory) "/dav/" {:max-bytes 8})]
         (is (= 412 (:status (handler (ring :put "/dav/a.edn" :body "{}" :headers {"If-Match" "*"})))) "no document to match")
         (is (= 201 (:status (handler (ring :put "/dav/a.edn" :body "{}")))))
         (is (= 204 (:status (handler (ring :put "/dav/a.edn" :body "{:a 1}" :headers {"If-Match" "*"})))) "any version matches")
         (is (= 413 (:status (handler (ring :put "/dav/a.edn" :body "{:a 1 :b 2}")))) "more than :max-bytes")
         (is (= 413 (:status (handler (ring :put "/dav/a.edn" :body (java.io.ByteArrayInputStream. (.getBytes "{:a 1 :b 2}" "UTF-8")))))))))))

#?(:clj
   (deftest a-collection-that-cannot-be-made
     (with-redefs [http/call! (fn [{:keys [method]}] {:status (if (= :put method) 409 405) :headers {} :body ""})]
       (is (thrown-with-msg? Exception #"making it did not help" ((:put (webdav/store root)) "p.edn" "{}" {}))
           "a put that fails for want of the collection fails, rather than seeming written"))))

#?(:clj
   (deftest serving-a-store-that-cannot-list-or-delete
     (let [documents (atom {})
           handler   (server/handler (dissoc (store/memory documents) :list :delete) "/dav/")]
       (testing "a document is put, found and fetched without a listing"
         (is (= 201 (:status (handler (ring :put "/dav/podcast.edn" :body "{}")))))
         (is (= 204 (:status (handler (ring :put "/dav/podcast.edn" :body "{}" :headers {"If-Match" "\"1\""})))))
         (is (= 207 (:status (handler (ring :propfind "/dav/podcast.edn")))))
         (is (= "{}" (:body (handler (ring :get "/dav/podcast.edn"))))))
       (testing "the collection alone at depth zero, and no listing"
         (let [{:keys [status body]} (handler (ring :propfind "/dav/" :headers {"Depth" "0"}))]
           (is (= 207 status))
           (is (not (str/includes? body "podcast.edn"))))
         (is (= 403 (:status (handler (ring :propfind "/dav/" :headers {"Depth" "1"})))))
         (is (= 403 (:status (handler (ring :propfind "/dav/")))) "no Depth is infinity"))
       (testing "no DELETE, and OPTIONS says so"
         (is (= 405 (:status (handler (ring :delete "/dav/podcast.edn")))))
         (is (= "OPTIONS, GET, HEAD, PUT, PROPFIND" (get-in (handler (ring :delete "/dav/podcast.edn")) [:headers "Allow"])))
         (is (= ["podcast.edn"] (keys @documents)))
         (is (not (str/includes? (get-in (handler (ring :options "/dav/")) [:headers "Allow"]) "DELETE")))))))

#?(:clj
   (defn- through
     "The library's HTTP client stood in by the Ring `handler`: a request map
     sent to it, its answer in the client's shape."
     [handler]
     (fn [{:keys [method url headers body]}]
       (-> (handler (ring method (str/replace url #"^https?://[^/]+" "") :headers headers :body body))
           (update :headers #(into {} (map (fn [[k v]] [(str/lower-case k) [v]])) %))))))

#?(:clj
   (deftest two-devices-through-a-server-that-cannot-list-or-delete
     (let [documents (atom {})
           client    (dissoc (webdav/store "https://dav.example.org/dav/") :list :delete)]
       (with-redefs [http/call! (through (server/handler (dissoc (store/memory documents) :list :delete) "/dav/"))]
         (let [{:keys [ok? checks]} (store/check! client)
               backend (store/backend client)
               phone   (sync/sync! (phone-state) [backend] t2)
               laptop  (sync/sync! (laptop-state) [backend] t3)]
           (is ok? (pr-str checks))
           (is (nil? (:failed phone)))
           (is (nil? (:failed laptop)))
           (is (= #{"podcast.edn" store/probe-name} (set (keys @documents)))
               "one shared document, and the probe that the store cannot delete")
           (is (= 900 (:position (player/episode (:state laptop) a)))))))))

#?(:clj
   (deftest checking-a-collection
     (testing "a collection that is not there yet is made for the probe"
       (let [documents (atom {})]
         (with-redefs [http/call! (stand-in documents)]
           (let [{:keys [ok? checks]} (store/check! (webdav/store "https://dav.example.org/dav/"))]
             (is ok?)
             (is (every? :ok? checks) (pr-str checks))
             (is (= {} (dissoc @documents ::created)) "the probe is gone")))))
     (testing "a server that refuses the credentials"
       (with-redefs [http/call! (fn [_] {:status 401 :headers {} :body "Unauthorized"})]
         (let [{:keys [ok? checks]} (store/check! (webdav/store "https://dav.example.org/dav/" {:credentials {:username "x" :password "y"}}))]
           (is (not ok?))
           (is (= [[:store/reachable true] [:store/authenticated false]] (map (juxt :id :ok?) checks)))
           (is (str/includes? (:message (second checks)) "401")))))
     (testing "a server that cannot be reached"
       (with-redefs [http/call! (fn [_] (throw (java.net.ConnectException. "Connection refused")))]
         (let [{:keys [ok? checks]} (store/check! (webdav/store "https://dav.example.org/dav/"))]
           (is (not ok?))
           (is (= [[:store/reachable false]] (map (juxt :id :ok?) checks)))
           (is (str/includes? (:message (first checks)) "Connection refused")))))))

#?(:clj
   (deftest two-devices-through-a-webdav-collection
     (let [documents (atom {})]
       (with-redefs [http/call! (stand-in documents)]
         (let [backend (store/backend (webdav/store "https://dav.example.org/dav") {:opml? true})
               phone   (sync/sync! (phone-state) [backend] t2)
               laptop  (sync/sync! (laptop-state) [backend] t3)
               phone2  (sync/sync! (:state phone) [backend] t3 {:ctx (:ctx phone)})]
           (is (nil? (:failed phone)) "the collection was made on the first put")
           (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"} (set (keys (dissoc @documents ::created)))))
           (is (= 900 (:position (player/episode (:state phone2) a))))
           (is (same? (:state laptop) (:state phone2)))
           (is (= "dav.example.org" (:name backend)) "named after the host")
           (is (= {"laptop.edn" "\"1\"" "phone.edn" "\"2\""} (:versions (get (:ctx phone2) "dav.example.org")))
               "the ETags are the versions"))))))

#?(:cljs
   (defn- fetch-stand-in
     "`js/fetch` over the stand-in server: the request as fetch sends it,
     the answer as a js/Response."
     [documents]
     (let [server (stand-in documents)]
       (fn [url init]
         (let [{:keys [status headers body]} (server {:method  (keyword (str/lower-case (.-method init)))
                                                      :url     url
                                                      :headers (js->clj (.-headers init))
                                                      :body    (.-body init)})]
           (js/Promise.resolve
            (js/Response. (when-not (#{201 204} status) body)
                          (clj->js {:status status :headers (into {} (for [[k [v]] headers :when v] [k v]))}))))))))

#?(:cljs
   (deftest two-devices-through-a-webdav-collection-with-fetch
     (async done
       (let [documents (atom {})
             backend   (store/backend (webdav/store "https://dav.example.org/dav/" {:fetch (fetch-stand-in documents)}))]
         (-> (sync/sync! (phone-state) [backend] t2)
             (.then (fn [phone]
                      (is (nil? (:failed phone)))
                      (is (= #{"phone.edn"} (set (keys (dissoc @documents ::created)))))
                      (sync/sync! (laptop-state) [backend] t3)))
             (.then (fn [laptop]
                      (is (= 900 (:position (player/episode (:state laptop) a))))
                      (is (= #{"feed-a" "feed-b"} (set (keys (player/subscriptions (:state laptop))))))
                      (is (= #{"phone.edn" "laptop.edn"} (set (keys (dissoc @documents ::created)))))
                      (store/check! (webdav/store "https://dav.example.org/dav/" {:fetch (fetch-stand-in documents)}))))
             (.then (fn [{:keys [ok? checks]}]
                      (is ok? (pr-str checks))
                      (is (= 7 (count checks)))
                      (is (= #{"phone.edn" "laptop.edn"} (set (keys (dissoc @documents ::created)))) "the probe is gone")))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally done))))))

#?(:cljs
   (deftest the-global-fetch-by-default
     (async done
       (let [documents (atom {})
             original  (.-fetch js/globalThis)]
         (set! (.-fetch js/globalThis) (fetch-stand-in documents))
         (-> (sync/sync! (phone-state) [(store/backend (webdav/store "https://dav.example.org/dav/"))] t2)
             (.then (fn [phone]
                      (is (nil? (:failed phone)) "a store without :fetch sends with js/fetch")
                      (is (= #{"phone.edn"} (set (keys (dissoc @documents ::created)))))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally (fn []
                         (set! (.-fetch js/globalThis) original)
                         (done))))))))
