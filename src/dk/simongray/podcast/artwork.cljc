(ns dk.simongray.podcast.artwork
  "Thumbnails of artwork, which a server makes for its pages, since most
  feeds name a single artwork of 3000 pixels, and a list of episodes draws
  it at 48.

  The handler answers a GET of its path with url and w parameters, e.g.
  /artwork?url=https%3A%2F%2Fexample.org%2Fa.jpg&w=128, with a thumbnail of
  the artwork at that width. A page asks for one with an img whose srcset
  the sizes of this namespace give, and whose src is the artwork itself.
  An artwork that format.image can't scale, or that the server can't
  fetch, gets a redirect to itself, so the img shows it as before.

  The widths are few and fixed, as Pocket Casts has 200, 480 and 960, and
  Next.js allows only the widths that its config lists. Any width that a
  stranger asks for would be another file in the cache."
  (:require [dk.simongray.wary-fetch.url :as url]
            #?@(:clj [[clojure.edn :as edn]
                      [clojure.java.io :as io]
                      [clojure.string :as str]
                      [dk.simongray.podcast.format.image :as image]
                      [dk.simongray.podcast.util.client :as client]
                      [dk.simongray.podcast.util.ring :as ring]
                      [dk.simongray.wary-fetch :as http]
                      [dk.simongray.wary-fetch.headers :as headers]
                      [dk.simongray.wary-fetch.proxy :as proxy]
                      [dk.simongray.webcrypto.bytes :as bytes]
                      [dk.simongray.webcrypto.hash :as hash]]))
  #?(:clj (:import [java.io File]
                   [java.nio.file CopyOption Files StandardCopyOption]
                   [java.util Date]
                   [java.util.concurrent Semaphore TimeUnit])))

;; Pocket Casts, PodcastImage.kt; Next.js, the Image component, imageSizes
;; https://github.com/Automattic/pocket-casts-android/blob/cedff55/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/images/PodcastImage.kt
;; https://nextjs.org/docs/app/api-reference/components/image#imagesizes
(def widths
  "The widths of the thumbnails in pixels. Each is half the next, so the
  halving that format.image/thumbnails does makes each one on its way to
  the next, and they cover rows of 48 to 64 pixels and artwork of 256 at
  two pixels to the CSS pixel."
  [64 128 256 512])

(defn thumbnail-url
  "The URL of the thumbnail `width` pixels wide of the artwork at `url`,
  from the handler at `path`, e.g. /artwork."
  [path url width]
  (str path "?url=" (url/percent-encode url) "&w=" width))

(defn sizes
  "The thumbnails of the artwork at `url` from the handler at `path`, at
  the `widths`, by default those of widths, as maps of :href and :width,
  as resolve/artwork-sizes gives the sizes that a feed names. They're nil
  for a URL that isn't http or https, e.g. a data URL.

  The handler doesn't scale an artwork up, so a thumbnail can be narrower
  than its :width: the widths above the artwork's own lead to one
  thumbnail at its own width, under the smallest of them."
  ([path url]
   (sizes path url widths))
  ([path url widths]
   (when (url/web-url? url)
     (mapv #(hash-map :href (thumbnail-url path url %) :width %) widths))))

#?(:clj
   (def defaults
     "The default options of the handler, which the options you give replace
     one by one:

     - :widths, the widths it makes, widths
     - :cache-dir, the folder that keeps the thumbnails, or nil for none
     - :max-cache-bytes, the most that the folder holds, 200 MB, the
       thumbnails used longest ago dropped first
     - :max-age, the seconds that a browser keeps a thumbnail, a week
     - :max-jobs, the most artworks that it fetches and scales at once, 2,
       since each can take some 40 MB of heap while it reads and decodes
       an artwork of 20 MB
     - :wait-seconds, how long a request waits for a job when the folder
       has no thumbnails of its artwork, 10, before it gets a redirect to
       the artwork
     - :retry-seconds, how long the folder keeps a failed fetch, 5 minutes,
       so that no request fetches the artwork again until then
     - :send, the function that sends a request, client/send!
     - :allow-pred, a predicate of the URLs that it fetches, url/public?
     - :max-bytes, the most that an artwork can hold, 20 MB, as Podverse
       allows
     - :timeout-seconds and :user-agent, as http/send! takes them

     The options can hold the :max-pixels of format.image/thumbnails too."
     {:widths          widths
      :cache-dir       nil
      :max-cache-bytes (* 200 1024 1024)
      :max-age         604800
      :max-jobs        2
      :wait-seconds    10
      :retry-seconds   300
      :send            client/send!
      :allow-pred      url/public?
      :max-bytes       (* 20 1024 1024)
      :timeout-seconds 30}))

#?(:clj
   (defn refusal
     "The Ring response that refuses `request`, or nil when the handler
     answers it with the `opts` over defaults. It refuses what wary-fetch's
     proxy refuses, and a w parameter that isn't one of the :widths:

     - a method other than GET and HEAD
     - a request that the handler sent itself, by its Via header, e.g. for
       a url parameter that names the handler
     - a page of another site, or a link that opens it as a page, so that
       its redirects can't send a reader anywhere
     - a url parameter that isn't an http or https URL"
     ([request]
      (refusal request {}))
     ([request opts]
      (or (proxy/refusal request)
          (let [{:strs [w]} (url/query-params (:query-string request))
                widths      (:widths (merge defaults opts))]
            (when-not (some #(= (str %) w) widths)
              (ring/text-response 400 (str "The w parameter is one of "
                                           (str/join ", " widths) ".")
                                  proxy/safety)))))))

#?(:clj
   (defn- hashed
     "The SHA-256 of the text `s`, in hexadecimal."
     [s]
     (bytes/hex (hash/digest :sha-256 (bytes/encode s)))))

#?(:clj
   (defn- entry-file
     "The file of the entry of the artwork with the hash `h` in `dir`."
     ^File [dir h]
     (io/file dir (str h ".edn"))))

#?(:clj
   (defn- image-file
     "The file of the `thumbnail` of the artwork with the hash `h` in `dir`."
     ^File [dir h {:keys [type width]}]
     (io/file dir (str h "-" width (if (= "image/png" type) ".png" ".jpg")))))

#?(:clj
   (defn- written!
     "Write the bytes `b` to the `file` whole, through a file beside it, so
     that a reader never sees half of it."
     [^File file ^bytes b]
     (let [part (io/file (.getParentFile file) (str (.getName file) ".part"))]
       (io/copy b part)
       (Files/move (.toPath part) (.toPath file)
                   (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                           StandardCopyOption/ATOMIC_MOVE])))))

#?(:clj
   (defn- stored
     "The entry of the artwork at `url` in the folder `dir`, or nil when the
     folder lacks it or a file of its thumbnails."
     [dir url]
     (when dir
       (let [h    (hashed url)
             file (entry-file dir h)]
         (when (.isFile file)
           (try
             (let [entry (edn/read-string (slurp file))]
               (when (and (= url (:url entry))
                          (every? #(.isFile (image-file dir h %))
                                  (vals (:thumbnails entry))))
                 entry))
             (catch Exception _
               nil)))))))

#?(:clj
   (defn- prune!
     "Delete the artworks of the folder `dir` that were used longest ago,
     until it holds no more than `max-bytes`, but never the one with the
     hash `kept`, which was just written. An artwork was last used when its
     entry file last changed."
     [^File dir max-bytes kept]
     (let [files  (filter #(.isFile ^File %) (.listFiles dir))
           groups (group-by #(re-find #"^[0-9a-f]+" (.getName ^File %)) files)
           used   (fn [[h _]] (.lastModified (entry-file dir h)))
           size   #(reduce + (map (fn [^File f] (.length f)) %))]
       (loop [[[_ group :as oldest] & more] (sort-by used (dissoc groups kept))
              total                         (size files)]
         (when (and oldest (> total max-bytes))
           (run! #(.delete ^File %) group)
           (recur more (- total (size group))))))))

#?(:clj
   (defn- store!
     "Keep the `entry` of an artwork in the folder `dir`, with the :data of
     each of its thumbnails in a file of its own, once for the widths that
     share one, and drop the oldest past `max-bytes`."
     [dir max-bytes {:keys [url thumbnails] :as entry}]
     (when dir
       (let [h (hashed url)]
         (.mkdirs (io/file dir))
         (doseq [thumbnail (vals (into {} (map (juxt :width identity))
                                       (vals thumbnails)))
                 :when     (:data thumbnail)]
           (written! (image-file dir h thumbnail) (:data thumbnail)))
         ;; an artwork that format.image can't scale keeps its nil
         (written! (entry-file dir h)
                   (.getBytes (pr-str (cond-> entry
                                        thumbnails (update :thumbnails update-vals
                                                           #(dissoc % :data))))
                              "UTF-8"))
         (prune! (io/file dir) max-bytes h)))))

#?(:clj
   (defn- touch!
     "Mark the artwork at `url` in the folder `dir` as used now."
     [dir url]
     (when dir
       (.setLastModified (entry-file dir (hashed url))
                         (System/currentTimeMillis)))))

;; RFC 9111, section 4.2.1, as wary-fetch reads it; Next.js keeps a
;; thumbnail for the larger of 4 hours and the max-age of the original
;; https://nextjs.org/docs/app/api-reference/components/image#minimumcachettl
#?(:clj
   (defn- fresh-until
     "When the server checks the artwork again, in epoch milliseconds, by
     the `headers` of its response at `now`: when their freshness ends, but
     no sooner than a day and no later than 30 days."
     [headers now]
     (let [day   (* 24 60 60 1000)
           until (some-> (headers/not-before headers (Date. (long now))
                                             {:max-wait-seconds (* 30 86400)})
                         inst-ms)]
       (max (+ now day) (or until 0)))))

#?(:clj
   (defn- with-tags
     "The `thumbnails` that format.image makes, each with an :etag of its
     bytes."
     [thumbnails]
     (update-vals thumbnails
                  (fn [{:keys [data] :as thumbnail}]
                    (let [tag (subs (bytes/hex (hash/digest :sha-256 data)) 0 32)]
                      (assoc thumbnail :etag (str "\"" tag "\"")))))))

;; RFC 9110, section 7.6.3: the Via of wary-fetch's proxy, which refusal
;; refuses, so that an artwork whose url names the handler isn't fetched
;; again and again
#?(:clj
   (defn- fetched
     "The entry of the artwork at `url`, fetched with the `opts`, or the
     `stale` entry renewed when the artwork didn't change since. An entry
     is a map of the :url, the artwork's :etag and :last-modified, when
     it's :fresh-until, and its :thumbnails, nil for an artwork that
     format.image can't scale. It throws when the fetch fails."
     [url stale {:keys [send widths] :as opts}]
     (let [{:keys [etag last-modified]} stale
           request  (http/with-options
                      {:url     url
                       :as      :bytes
                       :headers (cond-> {"Via" proxy/via}
                                  etag          (assoc "If-None-Match" etag)
                                  last-modified (assoc "If-Modified-Since"
                                                       last-modified))}
                      opts)
           response (send request)
           now      (System/currentTimeMillis)
           header   #(some (fn [[k v]] (when (= % (str/lower-case k)) v))
                           (:headers response))]
       (cond
         (and stale (= 304 (:status response)))
         (assoc stale :fresh-until (fresh-until (:headers response) now))

         (<= 200 (:status response) 299)
         {:url           url
          :etag          (header "etag")
          :last-modified (header "last-modified")
          :fresh-until   (fresh-until (:headers response) now)
          :thumbnails    (some-> (image/thumbnails (:body response) widths opts)
                                 with-tags)}

         :else
         (throw (ex-info (str "The artwork answered " (:status response) ".")
                         {:status (:status response)}))))))

#?(:clj
   (defn- failed
     "The entry of the artwork at `url` after a fetch at `now` failed: the
     `stale` entry, or else one that is :failed?, fresh for `retry-seconds`,
     so that no request fetches the artwork again until then."
     [url stale now retry-seconds]
     (assoc (or stale {:url url :failed? true})
            :fresh-until (+ now (* 1000 retry-seconds)))))

#?(:clj
   (defn- renewed!
     "The entry of the artwork at `url`, fetched again in place of the
     `stale` one with the `opts` and kept in their folder, when one of the
     `jobs` is free. A request that has stale thumbnails takes a job only
     when one is free at once, and any other waits :wait-seconds for one.
     Without a job, the entry is the stale one, or nil."
     [url stale ^Semaphore jobs
      {:keys [cache-dir max-cache-bytes wait-seconds retry-seconds] :as opts}]
     (if (.tryAcquire jobs (if (seq (:thumbnails stale)) 0 wait-seconds)
                      TimeUnit/SECONDS)
       (try
         (let [entry (try
                       (fetched url stale opts)
                       (catch Exception _
                         (failed url stale (System/currentTimeMillis)
                                 retry-seconds)))]
           (store! cache-dir max-cache-bytes entry)
           entry)
         (finally
           (.release jobs)))
       stale)))

#?(:clj
   (defn- redirect
     "The answer that sends the browser to `url`, kept for `seconds`, or not
     at all for nil."
     [url seconds]
     {:status  307
      :headers (assoc proxy/safety
                      "Location"      (url/legal-url url)
                      "Cache-Control" (if seconds
                                        (str "max-age=" seconds)
                                        "no-store"))
      :body    ""}))

#?(:clj
   (defn- sent
     "The answer with the thumbnail of the `entry` for the `request`, from
     the folder `dir` unless the thumbnail holds its :data, or a 304 when
     the browser has it."
     [request entry width dir max-age]
     (let [{:keys [type etag data] :as thumbnail} (get-in entry [:thumbnails width])
           headers (assoc proxy/safety
                          "ETag"          etag
                          "Cache-Control" (str "max-age=" max-age))]
       (if (ring/not-modified? (update-keys (:headers request) str/lower-case)
                               etag nil)
         {:status 304 :headers headers :body ""}
         (ring/response 200 type
                        (or data (image-file dir (hashed (:url entry)) thumbnail))
                        headers)))))

#?(:clj
   (defn- smallest-width
     "The smallest width of the `entry` whose thumbnail is as wide as the
     one at `width`, which differs from `width` when the widths above the
     artwork's own share it."
     [entry width]
     (let [{:keys [thumbnails]} entry
           shown                (:width (get thumbnails width))]
       (apply min (keep (fn [[w thumbnail]]
                          (when (= shown (:width thumbnail)) w))
                        thumbnails)))))

#?(:clj
   (defn- answer
     "The answer to the `request` for the thumbnail of the artwork at `url`
     at `width` by its `entry`, with the :cache-dir and :max-age of `opts`."
     [request url entry width {:keys [cache-dir max-age] :as opts}]
     (cond
       (or (nil? entry) (:failed? entry))
       (redirect url nil)

       ;; empty, as an entry that an earlier version kept was
       (empty? (:thumbnails entry))
       (redirect url 86400)

       ;; e.g. a width that the cache didn't make before
       (nil? (get-in entry [:thumbnails width]))
       (redirect url nil)

       ;; a width above the artwork's own, so that every such width leads
       ;; to one URL, which a browser fetches and keeps once; the URL is
       ;; relative, so it keeps the handler's path
       (< (smallest-width entry width) width)
       (redirect (thumbnail-url "" url (smallest-width entry width)) max-age)

       :else
       (sent request entry width cache-dir max-age))))

#?(:clj
   (defn- shared
     "What `f` gives for the key `k`, once for all the threads that ask for
     `k` while `f` runs, so that two requests for the same artwork fetch it
     once. The atom `running` holds the keys that run."
     [running k f]
     (let [mine (delay (try (f) (finally (swap! running dissoc k))))
           now  (get (swap! running #(if (contains? % k) % (assoc % k mine))) k)]
       @now)))

#?(:clj
   (defn handler
     "A Ring handler of thumbnails of artwork, with the `opts` over
     defaults.

     It answers a request with a refusal as refusal gives it, or with the
     thumbnail of the artwork at its url parameter, at the width of its w
     parameter, which a browser keeps for the :max-age of `opts`. It keeps
     each artwork's thumbnails in its :cache-dir, and fetches the artwork
     again when its freshness ends, with its ETag and Last-Modified. When
     the artwork didn't change, it keeps them.

     It fetches and scales :max-jobs artworks at most at once, but sends
     thumbnails from the folder without waiting for a job. When a fetch
     fails, it sends the thumbnails it has, and keeps them, or the failure,
     for :retry-seconds before it fetches the artwork again.

     It sends a redirect to the artwork itself when format.image can't
     scale it, which a browser keeps for a day, or when it has no
     thumbnails of it and the fetch fails or waits too long for a job,
     which a browser doesn't keep. A width above the artwork's own gets a
     redirect to the smallest such width, since they share one thumbnail."
     ([]
      (handler {}))
     ([opts]
      (let [{:keys [cache-dir max-jobs] :as opts} (merge defaults opts)
            jobs    (Semaphore. max-jobs)
            running (atom {})]
        (fn [request]
          (or (refusal request opts)
              (let [{:strs [url w]} (url/query-params (:query-string request))
                    stale           (stored cache-dir url)
                    entry           (if (and stale (< (System/currentTimeMillis)
                                                      (:fresh-until stale)))
                                      (do (touch! cache-dir url)
                                          stale)
                                      (try
                                        (shared running url
                                                #(renewed! url stale jobs opts))
                                        (catch Exception _
                                          (when (seq (:thumbnails stale))
                                            stale))))]
                (answer request url entry (parse-long w) opts))))))))
