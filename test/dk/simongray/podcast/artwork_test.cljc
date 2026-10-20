(ns dk.simongray.podcast.artwork-test
  "The URLs of thumbnails of artwork, and on the JVM the handler that makes
  them, over a stub of the network and a temporary folder: the refusals,
  the redirects, the cache and its checks with the artwork's ETag, the
  wait after a failure, and the jobs."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is #?(:clj testing)]]
            [dk.simongray.podcast.artwork :as artwork]
            #?@(:clj [[clojure.java.io :as io]
                      [dk.simongray.podcast.test-support :refer [with-temp-dir]]
                      [dk.simongray.wary-fetch.proxy :as proxy]
                      [dk.simongray.wary-fetch.url :as url]])))

(deftest the-sizes-of-the-thumbnails
  (is (= "/artwork?url=https%3A%2F%2Fexample.org%2Fa%20b.jpg%3Fv%3D1&w=64"
         (artwork/thumbnail-url "/artwork" "https://example.org/a b.jpg?v=1" 64)))
  (is (= [{:href "/artwork?url=https%3A%2F%2Fexample.org%2Fa.jpg&w=64" :width 64}
          {:href "/artwork?url=https%3A%2F%2Fexample.org%2Fa.jpg&w=128" :width 128}
          {:href "/artwork?url=https%3A%2F%2Fexample.org%2Fa.jpg&w=256" :width 256}
          {:href "/artwork?url=https%3A%2F%2Fexample.org%2Fa.jpg&w=512" :width 512}]
         (artwork/sizes "/artwork" "https://example.org/a.jpg")))
  (is (nil? (artwork/sizes "/artwork" "data:image/png;base64,iVBORw0KGgo="))
      "none for artwork in a data URL, which no server fetches")
  (is (nil? (artwork/sizes "/artwork" nil))))

#?(:clj
   (defn- jpeg
     "The bytes of a JPEG of `w` by `h` pixels, all blue."
     [w h]
     (let [img (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_RGB)
           out (java.io.ByteArrayOutputStream.)]
       (doseq [x (range w) y (range h)]
         (.setRGB img x y (unchecked-int 0xFF2040A0)))
       (javax.imageio.ImageIO/write img "jpeg" out)
       (.toByteArray out))))

#?(:clj
   (defn- stub
     "A :send that answers each request with the next of the `answers`,
     functions of the request, and logs the requests in the atom `sent`."
     [sent answers]
     (let [left (atom answers)]
       (fn [request]
         (swap! sent conj request)
         (let [answer (first @left)]
           (swap! left rest)
           (answer request))))))

#?(:clj
   (defn- asked
     "The request of an img for the thumbnail `w` wide of the artwork at
     `url`, with the `headers` over those of an img of the same site."
     ([url w]
      (asked url w {}))
     ([url w headers]
      {:request-method :get
       :query-string   (str "url=" (url/percent-encode url) "&w=" w)
       :headers        (merge {"sec-fetch-mode" "no-cors"
                               "sec-fetch-site" "same-origin"}
                              headers)})))

#?(:clj
   (defn- body-bytes
     "The bytes of the body of the `response`, from a file or as they are."
     [{:keys [body]}]
     (if (instance? java.io.File body)
       (java.nio.file.Files/readAllBytes (.toPath ^java.io.File body))
       body)))

#?(:clj
   (def art
     "The URL of the artwork of the tests."
     "https://example.org/art.jpg"))

#?(:clj
   (deftest refusals
     (let [h (artwork/handler {:send (fn [_] (throw (ex-info "no network" {})))})]
       (is (= 405 (:status (h (assoc (asked art 64) :request-method :post)))))
       (is (= 403 (:status (h (asked art 64 {"sec-fetch-site" "cross-site"}))))
           "a page of another site")
       (is (= 403 (:status (h (asked art 64 {"sec-fetch-mode" "navigate"}))))
           "a link that opens it as a page, so that its redirect sends no reader anywhere")
       (is (= 400 (:status (h (asked "file:///etc/passwd" 64)))))
       (is (= 502 (:status (h (asked art 64 {"via" "1.1 wary-fetch"}))))
           "a request that it sent itself, for an artwork whose url names it")
       (is (= 400 (:status (h (asked art 100)))) "a width outside the list")
       (is (= 400 (:status (h {:request-method :get :query-string "w=64" :headers {}})))))))

#?(:clj
   (deftest thumbnails-and-their-cache
     (with-temp-dir
       (fn [dir]
         (let [sent    (atom [])
               b       (jpeg 1000 1000)
               ok      (fn [_] {:status  200
                                :headers {"etag"          "\"v1\""
                                          "cache-control" "max-age=0"
                                          "content-type"  "application/octet-stream"}
                                :body    b})
               h       (artwork/handler {:cache-dir (io/file dir "artwork")
                                         :send      (stub sent [ok (fn [_] {:status 304 :headers {}})])})
               made    (h (asked art 128))
               again   (h (asked art 64))]
           (testing "the first request"
             (is (= [200 "image/jpeg" "max-age=604800"]
                    [(:status made) (get-in made [:headers "Content-Type"])
                     (get-in made [:headers "Cache-Control"])]))
             (is (= [128 128] (let [img (javax.imageio.ImageIO/read (java.io.ByteArrayInputStream. (body-bytes made)))]
                                [(.getWidth img) (.getHeight img)])))
             (is (= {:url art :as :bytes :headers {"Via" proxy/via}} (select-keys (first @sent) [:url :as :headers]))
                 "with the Via of wary-fetch's proxy, which the handler refuses"))
           (testing "another width, from the folder, without a request, as the server checks the artwork no sooner than a day"
             (is (= [200 1] [(:status again) (count @sent)]))
             (is (= 64 (.getWidth (javax.imageio.ImageIO/read (java.io.ByteArrayInputStream. (body-bytes again)))))))
           (testing "a browser that has the thumbnail"
             (is (= 304 (:status (h (asked art 64 {"if-none-match" (get-in again [:headers "ETag"])}))))))
           (testing "a new handler over the same folder, as after a restart"
             (let [h2 (artwork/handler {:cache-dir (io/file dir "artwork")
                                        :send      (fn [_] (throw (ex-info "no network" {})))})]
               (is (= 200 (:status (h2 (asked art 256))))))))))))

#?(:clj
   (deftest a-check-with-the-etag
     (with-temp-dir
       (fn [dir]
         (let [sent   (atom [])
               cache  (io/file dir "artwork")
               b      (jpeg 200 200)
               h      (artwork/handler {:cache-dir cache
                                        :send      (stub sent [(fn [_] {:status 200 :headers {"etag" "\"v1\""} :body b})
                                                               (fn [_] {:status 304 :headers {}})
                                                               (fn [_] (throw (ex-info "offline" {})))])})
               expire #(spit % (pr-str (assoc (read-string (slurp %)) :fresh-until 0)))]
           (is (= 200 (:status (h (asked art 64)))))
           (let [entry (first (filter #(re-find #"\.edn$" (.getName ^java.io.File %)) (file-seq cache)))]
             (expire entry)
             (is (= 200 (:status (h (asked art 64)))) "a 304 keeps the thumbnails")
             (is (= {"If-None-Match" "\"v1\"" "Via" proxy/via} (:headers (second @sent))))
             (expire entry)
             (is (= 200 (:status (h (asked art 64))))
                 "and a failed check sends the thumbnails it has")
             (is (= 3 (count @sent)))
             (is (= 200 (:status (h (asked art 64)))))
             (is (= 3 (count @sent)) "and waits a while before it checks again")))))))

#?(:clj
   (deftest a-failure-waits-a-while
     (with-temp-dir
       (fn [dir]
         (let [sent   (atom [])
               cache  (io/file dir "artwork")
               h      (artwork/handler {:cache-dir cache
                                        :send      (stub sent [(fn [_] (throw (ex-info "offline" {})))
                                                               (fn [_] {:status 200 :headers {} :body (jpeg 200 200)})])})
               expire #(spit % (pr-str (assoc (read-string (slurp %)) :fresh-until 0)))]
           (is (= [307 "no-store"] ((juxt :status #(get-in % [:headers "Cache-Control"])) (h (asked art 64)))))
           (is (= 307 (:status (h (asked art 128)))))
           (is (= 1 (count @sent)) "without a fetch, though the folder has no thumbnails")
           (run! expire (filter #(re-find #"\.edn$" (.getName ^java.io.File %)) (file-seq cache)))
           (is (= 200 (:status (h (asked art 64)))) "and fetches the artwork after the while")
           (is (= 2 (count @sent))))))))

#?(:clj
   (deftest jobs-at-most
     (with-temp-dir
       (fn [dir]
         (let [slow    "https://slow.example/art.jpg"
               started (promise)
               gate    (promise)
               sent    (atom [])
               b       (jpeg 200 200)
               h       (artwork/handler {:cache-dir    (io/file dir "artwork")
                                         :max-jobs     1
                                         :wait-seconds 0
                                         :send         (fn [{:keys [url]}]
                                                         (swap! sent conj url)
                                                         (when (= slow url)
                                                           (deliver started true)
                                                           @gate)
                                                         {:status 200 :headers {} :body b})})
               _       (h (asked art 64))
               busy    (future (h (asked slow 64)))]
           @started
           (is (= 200 (:status (h (asked art 128)))) "thumbnails from the folder, without a job")
           (is (= [307 "no-store"] ((juxt :status #(get-in % [:headers "Cache-Control"]))
                                    (h (asked "https://example.org/other.jpg" 64))))
               "and the artwork itself when no job is free")
           (deliver gate true)
           (is (= 200 (:status @busy)))
           (is (= [art slow] @sent)))))))

#?(:clj
   (deftest one-copy-above-the-artworks-width
     (with-temp-dir
       (fn [dir]
         (let [cache (io/file dir "artwork")
               h     (artwork/handler {:cache-dir cache
                                       :send      (fn [_] {:status 200 :headers {} :body (jpeg 100 100)})})
               width #(.getWidth (javax.imageio.ImageIO/read (java.io.ByteArrayInputStream. (body-bytes %))))]
           (is (= 64 (width (h (asked art 64)))))
           (is (= 100 (width (h (asked art 128)))) "the artwork at its own width")
           (is (= [307 (str "?url=" (url/percent-encode art) "&w=128") "max-age=604800"]
                  ((juxt :status #(get-in % [:headers "Location"]) #(get-in % [:headers "Cache-Control"]))
                   (h (asked art 512))))
               "and a wider width leads to it, so that a browser keeps one copy")
           (is (= 3 (count (rest (file-seq cache)))) "as does the folder, beside the entry"))))))

#?(:clj
   (deftest redirects-to-the-artwork-itself
     (let [sent  (atom [])
           webp  (byte-array [0x52 0x49 0x46 0x46 0 0 0 0 0x57 0x45 0x42 0x50])
           h     (artwork/handler {:send (stub sent [(fn [_] {:status 200 :headers {} :body webp})
                                                     (fn [_] {:status 404 :headers {} :body (byte-array 0)})
                                                     (fn [_] (throw (ex-info "offline" {})))])})
           check (fn [response]
                   [(:status response) (get-in response [:headers "Location"])
                    (get-in response [:headers "Cache-Control"])])]
       (is (= [307 art "max-age=86400"] (check (h (asked art 64))))
           "an artwork that format.image can't scale, which a browser keeps for a day")
       (is (= [307 art "no-store"] (check (h (asked art 64)))) "an artwork that isn't there")
       (is (= [307 art "no-store"] (check (h (asked art 64)))) "a fetch that fails")
       (is (= [307 "https://example.org/a%20b.jpg" "no-store"]
              (check ((artwork/handler {:send (fn [_] (throw (ex-info "offline" {})))})
                      (asked "https://example.org/a b.jpg" 64))))
           "a Location that HTTP allows")
       (with-temp-dir
         (fn [dir]
           (let [fetches (atom [])
                 cached  (artwork/handler {:cache-dir (io/file dir "artwork")
                                           :send      (stub fetches [(fn [_] {:status 200 :headers {} :body webp})])})]
             (is (= [307 art "max-age=86400"] (check (cached (asked art 64)))))
             (is (= [307 art "max-age=86400"] (check (cached (asked art 128))))
                 "and so does the cache that keeps it, at another width")
             (is (= 1 (count @fetches)) "without a fetch")))))))

#?(:clj
   (deftest the-oldest-go-first
     (with-temp-dir
       (fn [dir]
         (let [cache   (io/file dir "artwork")
               b       (jpeg 1000 1000)
               handler #(artwork/handler {:cache-dir       cache
                                          :max-cache-bytes %
                                          :send            (fn [_] {:status 200 :headers {} :body b})})
               bytes   #(reduce + (map (fn [^java.io.File f] (.length f)) (rest (file-seq cache))))
               urls    (map #(str "https://example.org/" % ".jpg") (range 4))
               _       ((handler 1000000) (asked (first urls) 64))
               cap     (long (* 2.5 (bytes)))
               h       (handler cap)]
           (doseq [u (rest urls)]
             (Thread/sleep 20)
             (is (= 200 (:status (h (asked u 64))))))
           (let [kept (->> (file-seq cache)
                           (filter #(re-find #"\.edn$" (.getName ^java.io.File %)))
                           (map #(:url (read-string (slurp %))))
                           set)]
             (is (= (set (take-last 2 urls)) kept) "the two used last")
             (is (<= (bytes) cap))))))))
