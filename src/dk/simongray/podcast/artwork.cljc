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
                      [dk.simongray.webcrypto.bytes :as bytes]
                      [dk.simongray.webcrypto.hash :as hash]]))
  #?(:clj (:import [java.io File]
                   [java.nio.file CopyOption Files StandardCopyOption]
                   [java.util Date])))

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
  for a URL that isn't http or https, e.g. a data URL."
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
     - :send, the function that sends a request, client/send!
     - :allow-pred, a predicate of the URLs that it fetches, url/public?
     - :max-bytes, the most that an artwork can hold, 20 MB, as Podverse
       allows
     - :max-pixels, the most pixels of an artwork, image/max-pixels
     - :timeout-seconds and :user-agent, as http/send! takes them"
     {:widths          widths
      :cache-dir       nil
      :max-cache-bytes (* 200 1024 1024)
      :max-age         604800
      :send            client/send!
      :allow-pred      url/public?
      :max-bytes       (* 20 1024 1024)
      :max-pixels      image/max-pixels
      :timeout-seconds 30}))

#?(:clj
   (def safety
     "The headers of every answer, as wary-fetch's proxy sends them: a
     browser that opens one as a page runs no scripts, sniffs no other type
     and lets no other site embed it."
     {"Content-Security-Policy"      "default-src 'none'; sandbox"
      "X-Content-Type-Options"       "nosniff"
      "Cross-Origin-Resource-Policy" "same-origin"}))

#?(:clj
   (defn- refused
     "The answer with `status` and the text `message`."
     [status message]
     (ring/text-response status message safety)))

;; Fetch Metadata Request Headers, W3C Working Draft of 2021-03-29: an img
;; sends Sec-Fetch-Mode no-cors, and a link that a page opens navigate
#?(:clj
   (defn refusal
     "The Ring response that refuses `request`, or nil when the handler
     answers it with the `opts` over defaults. It refuses:

     - a method other than GET and HEAD
     - a page of another site, or a link that opens it as a page, so that
       its redirects can't send a reader anywhere
     - a url parameter that isn't an http or https URL
     - a w parameter that isn't one of the :widths"
     ([request]
      (refusal request {}))
     ([{:keys [request-method headers query-string] :as request} opts]
      (let [{:strs [url w]} (url/query-params query-string)
            headers         (update-keys headers str/lower-case)
            opts            (merge defaults opts)]
        (cond
          (not (#{:get :head} request-method))
          (assoc-in (refused 405 "The thumbnails answer GET and HEAD.")
                    [:headers "Allow"] "GET, HEAD")

          (or (= "navigate" (get headers "sec-fetch-mode"))
              (= "cross-site" (get headers "sec-fetch-site")))
          (refused 403 (str "The thumbnails are for the pages of their own"
                            " site, and aren't pages themselves."))

          (not (url/web-url? url))
          (refused 400 "The url parameter names no http or https URL.")

          (not (some #(= (str %) w) (:widths opts)))
          (refused 400 (str "The w parameter is one of "
                            (str/join ", " (:widths opts)) ".")))))))

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
     each of its thumbnails in a file of its own, and drop the oldest past
     `max-bytes`."
     [dir max-bytes {:keys [url thumbnails] :as entry}]
     (when dir
       (let [h (hashed url)]
         (.mkdirs (io/file dir))
         (doseq [[_ thumbnail] thumbnails
                 :when         (:data thumbnail)]
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
                       :headers (cond-> {}
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
   (defn- redirect
     "The answer that sends the browser to the artwork at `url` itself, kept
     for `seconds`, or not at all for nil."
     [url seconds]
     {:status  307
      :headers (assoc safety
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
           headers (assoc safety
                          "ETag"          etag
                          "Cache-Control" (str "max-age=" max-age))]
       (if (ring/not-modified? (update-keys (:headers request) str/lower-case)
                               etag nil)
         {:status 304 :headers headers :body ""}
         (ring/response 200 type
                        (or data (image-file dir (hashed (:url entry)) thumbnail))
                        headers)))))

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
     each artwork's thumbnails in its :cache-dir at every width, and fetches
     the artwork again when its freshness ends, with its ETag and
     Last-Modified. When the artwork didn't change, it keeps them.

     It sends a redirect to the artwork itself when format.image can't scale
     it, which a browser keeps for a day, or when the fetch fails and the
     folder has no thumbnails of it, which a browser doesn't keep."
     ([]
      (handler {}))
     ([opts]
      (let [{:keys [cache-dir max-cache-bytes max-age] :as opts}
            (merge defaults opts)
            running (atom {})]
        (fn [request]
          (or (refusal request opts)
              (let [{:strs [url w]} (url/query-params (:query-string request))
                    width           (parse-long w)
                    stale           (stored cache-dir url)
                    entry           (if (and stale (< (System/currentTimeMillis)
                                                      (:fresh-until stale)))
                                      (do (touch! cache-dir url)
                                          stale)
                                      (try
                                        (shared running url
                                                #(let [entry (fetched url stale opts)]
                                                   (store! cache-dir max-cache-bytes entry)
                                                   entry))
                                        (catch Exception _
                                          (when (seq (:thumbnails stale))
                                            stale))))]
                (cond
                  (nil? entry)
                  (redirect url nil)

                  ;; empty, as an entry that an earlier version kept was
                  (empty? (:thumbnails entry))
                  (redirect url 86400)

                  ;; e.g. a width that the cache didn't make before
                  (nil? (get-in entry [:thumbnails width]))
                  (redirect url nil)

                  :else
                  (sent request entry width cache-dir max-age)))))))))
