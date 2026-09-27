(ns dk.simongray.podcast.format.media
  "What a media file says about itself, apart from the feed: the duration,
  the chapters and the artwork of an MP3 file (see
  dk.simongray.podcast.format.mp3 and .id3) or of an MP4 or M4A file (see
  .mp4), and a check of the file against the podcast:integrity of an
  alternate enclosure.

  `read` takes a view of the parts of the file at hand (see
  dk.simongray.podcast.bytes) and gives what they say, or the ranges it
  still needs. `read!` fetches those ranges until the reading is done,
  most often in one or two requests and a few hundred kilobytes: with the
  library's HTTP client on the JVM, with fetch in ClojureScript, or with a
  function of the app. A host that inserts ads serves another file to each
  request, so the duration is that of the file that came.

  The readers are strict, and their quirks are named in their namespaces;
  `:except` in the options leaves out the quirks it names."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.id3 :as id3]
            [dk.simongray.podcast.format.mp3 :as mp3]
            [dk.simongray.podcast.format.mp4 :as mp4]
            [dk.simongray.podcast.http :as http]))

(defn- sniff
  "The format of the file whose start `view` holds: :mp3 for an ID3 tag or
  an MPEG audio frame, :mp4 for an ftyp box; nil otherwise."
  [view]
  (bytes/need view 0 12)
  (cond
    (= "ID3" (bytes/code view 0 3))  :mp3
    (= "ftyp" (bytes/code view 4 4)) :mp4
    (mp3/frame-header view 0)        :mp3))

(defn- mp3-result
  "What `read` gives for the MP3 file in `view`."
  [view opts]
  (let [{:keys [tags duration report]} (mp3/read view opts)
        frames   (mapcat :frames tags)
        duration (or duration (id3/length frames))
        chs      (id3/chapters frames opts)
        artwork  (id3/artwork frames)]
    (cond-> {:format :mp3
             :report (into report (id3/chapters-report chs))}
      duration  (assoc :duration duration)
      (seq chs) (assoc :chapters {:version chapters/version :chapters chs})
      artwork   (assoc :artwork artwork))))

(defn- mp4-result
  "What `read` gives for the MP4 file in `view`."
  [view opts]
  (let [{:keys [chapters] :as r} (mp4/read view opts)]
    (cond-> (assoc (dissoc r :chapters) :format :mp4)
      chapters (assoc :chapters {:version chapters/version :chapters chapters}))))

(defn read
  "What the media file whose parts `view` holds says about itself:
  `{:format :duration :chapters :artwork :report}`, the format :mp3 or
  :mp4, the duration in seconds, the chapters as a chapters document of
  dk.simongray.podcast.format.chapters, and the artwork as `{:type :data}`,
  each left out when the file has none; or `{:need [[start end] ...]}`,
  the ranges that the reading needs and the view lacks, end excluded.
  Reading never throws on the content of the file: what it could not read
  is in the report. `opts` may give `:except`, the ids of the quirks to
  leave out."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (let [result (try
                  (case (sniff view)
                    :mp3 (mp3-result view opts)
                    :mp4 (mp4-result view opts)
                    {:report [{:id :file/unknown-format :level :warning}]})
                  (catch #?(:clj Exception :cljs :default) e
                    (cond
                      (bytes/missing e)    {:need (bytes/missing e)}
                      (bytes/truncated? e) {:report [{:id :file/truncated :level :warning}]}
                      :else                {:report [{:id :file/malformed :level :warning :reason (async/explain e)}]})))]
     (cond-> result
       (:report result) (update :report #(mapv diagnostics/describe %))))))

(def ^:no-doc chunk-size
  "The least that `read!` fetches at a time, so that small reads come in
  one request."
  (* 64 1024))

(def ^:no-doc max-rounds
  "How many times `read!` fetches before it gives up."
  8)

(defn- whole-size
  "The size of the file whose first `n` bytes came in a 200, the answer of
  a server that ignores ranges, to a request for the first `asked`: `n`
  when that is less, since the file ended; else the `content-length` of the
  answer, unless the `content-encoding` changed the size."
  [n asked content-length content-encoding]
  (cond
    (< n asked)
    n

    (contains? #{nil "identity"} (some-> content-encoding str/trim str/lower-case))
    (some-> content-length str/trim parse-long)))

#?(:cljs
   (defn- read-prefix
     "A promise of the first `n` bytes of the body of the fetch response
     `res`, all of it when it is shorter, the rest left unread."
     [res n]
     (let [reader (.getReader (.-body res))]
       (letfn [(join [chunks]
                 (.catch (.cancel reader) (fn [_] nil))
                 (let [size (min n (reduce + (map #(.-length %) chunks)))
                       out  (js/Uint8Array. size)]
                   (reduce (fn [p chunk]
                             (let [k (min (.-length chunk) (- size p))]
                               (.set out (.subarray chunk 0 k) p)
                               (+ p k)))
                           0 chunks)
                   out))
               (step [chunks got]
                 (if (>= got n)
                   (join chunks)
                   (.then (.read reader)
                          (fn [r]
                            (if (.-done r)
                              (join chunks)
                              (step (conj chunks (.-value r)) (+ got (.-length (.-value r)))))))))]
         (js/Promise.resolve (step [] 0))))))

(defn fetcher
  "The function that `read!` fetches the bytes of the file at `url` with,
  unless it is given another: a function of a start and an end, end
  excluded, that gives `{:bytes :start :total}`. A server that ignores
  ranges sends the file from its start, and only the bytes up to the end
  are read. On the JVM it sends with the library's HTTP client; in
  ClojureScript it sends with fetch and gives a promise, the total known
  when the server exposes Content-Range. An app wraps it to log or keep
  what it fetches. `opts` may give the `:headers` and the
  `:timeout-seconds` of the requests (default 30, on the JVM)."
  [url {:keys [headers] :as opts}]
  #?(:clj
     (fn [start end]
       (let [{:keys [status body content-range] :as response}
             (http/call! {:url url :headers headers :timeout-seconds (:timeout-seconds opts 30)
                          :as :bytes :range [start (dec end)]})
             body   (or body (bytes/of []))
             header #(first (get-in response [:headers %]))]
         (case (long status)
           206 {:bytes body :start (:start content-range) :total (:total content-range)}
           200 {:bytes body :start 0
                :total (whole-size (bytes/length body) end (header "content-length") (header "content-encoding"))}
           (throw (ex-info (str "The media file answered " status ": " url) {:status status :url url})))))
     :cljs
     (fn [start end]
       (-> (js/fetch url (clj->js {:headers (assoc headers "Range" (str "bytes=" start "-" (dec end)))}))
           (.then (fn [res]
                    (let [header #(.get (.-headers res) %)]
                      (case (.-status res)
                        206 (.then (.arrayBuffer res)
                                   (fn [buffer]
                                     (let [range (http/content-range (header "content-range"))]
                                       {:bytes (js/Uint8Array. buffer) :start (or (:start range) start) :total (:total range)})))
                        200 (.then (read-prefix res end)
                                   (fn [b]
                                     {:bytes b :start 0
                                      :total (whole-size (.-length b) end (header "content-length") (header "content-encoding"))}))
                        (throw (ex-info (str "The media file answered " (.-status res) ": " url) {:status (.-status res)}))))))))))

(defn read!
  "What the media file at `url` says about itself, as `read` gives it,
  fetching the ranges that the reading needs, all that one reading needs
  at once: on the JVM the result, in ClojureScript a promise of it. `opts`
  may give `:fetch`, a function as `fetcher` makes, for an app that fetches
  its own way; the `:headers` and `:timeout-seconds` of `fetcher`; and the
  `:except` of `read`. A file whose ranges do not come gives what the
  reading could do without them, with `:file/incomplete` in the report."
  ([url]
   (read! url {}))
  ([url {:keys [fetch] :as opts}]
   (let [fetch (or fetch (fetcher url opts))
         opts  (select-keys opts [:except])]
     (letfn [(step [view round previous]
               (let [{:keys [need] :as result} (read view opts)]
                 (cond
                   (nil? need)
                   result

                   (or (>= round max-rounds) (= need previous))
                   {:report [(diagnostics/describe {:id :file/incomplete :level :warning :ranges need})]}

                   :else
                   ;; one range, such as a tag, is fetched with what follows
                   ;; it, where the reading most often goes next
                   (async/then (async/all (for [[start end] need]
                                            (fetch start (if (next need)
                                                           (max end (+ start chunk-size))
                                                           (+ end chunk-size)))))
                               (fn [parts]
                                 (step (reduce (fn [v {b :bytes :keys [start total]}]
                                                 (cond-> v b (bytes/add start b total)))
                                               view parts)
                                       (inc round)
                                       need))))))]
       (step (bytes/view) 0 nil)))))

(def ^:no-doc sri-algorithms
  "The hash algorithms of Subresource Integrity, weakest first, with their
  names in MessageDigest and WebCrypto."
  [["sha256" "SHA-256"] ["sha384" "SHA-384"] ["sha512" "SHA-512"]])

(defn sri
  "The strongest hashes of the SRI integrity metadata `s`, as the W3C's
  Subresource Integrity picks them: `{:algorithm \"sha384\" :digests
  #{\"base64…\"}}`, the hashes of the strongest algorithm that it names
  among sha256, sha384 and sha512; nil when it names none of them."
  [s]
  (let [rank      (into {} (map-indexed (fn [i [a _]] [a i])) sri-algorithms)
        hashes    (keep #(when-let [[_ a v] (re-matches #"(?i)(sha256|sha384|sha512)-([A-Za-z0-9+/]+={0,2})(?:\?\S*)?" %)]
                           [(str/lower-case a) v])
                        (str/split (str/trim (str s)) #"\s+"))
        strongest (last (sort-by rank (map first hashes)))]
    (when strongest
      {:algorithm strongest
       :digests   (set (keep (fn [[a v]] (when (= a strongest) v)) hashes))})))

(defn- verdict
  "The verdict on the `digest` in base64 of the file, against the hashes
  that `metadata` from `sri` gives."
  [{:keys [algorithm digests]} digest]
  {:valid? (contains? digests digest) :algorithm algorithm})

(defn verify!
  "Whether the file at `url` is what the podcast:integrity `integrity` of an
  alternate enclosure says, `{:type \"sri\" :value …}`: `{:valid? true
  :algorithm …}` when the digest of the whole file is one of its strongest
  hashes, false when not, and `{:valid? nil :reason …}` for a PGP
  signature, which needs the publisher's key, or for a value with no hash
  that Subresource Integrity knows. On the JVM the file streams through the
  digest; in ClojureScript it comes whole, and the result is a promise.
  `opts` may give the `:headers` and `:timeout-seconds` of the request."
  ([url integrity]
   (verify! url integrity {}))
  ([url {:keys [type value]} opts]
   (let [metadata (when (= "sri" (some-> type str/lower-case)) (sri value))
         names    (into {} sri-algorithms)]
     (if-not metadata
       (let [unverified {:valid? nil :reason (if (= "pgp-signature" (some-> type str/lower-case))
                                               "A PGP signature needs the publisher's key."
                                               "The integrity names no hash of sha256, sha384 or sha512.")}]
         #?(:clj unverified :cljs (js/Promise.resolve unverified)))
       #?(:clj  (let [algorithm (names (:algorithm metadata))
                      {:keys [status digests]} (http/digest! url [algorithm] opts)]
                  (if digests
                    (verdict metadata (bytes/base64 (get digests algorithm)))
                    {:valid? nil :reason (str "The file answered " status ".")}))
          :cljs (-> (js/fetch url (clj->js {:headers (:headers opts)}))
                    (.then (fn [res]
                             (if (.-ok res)
                               (-> (.arrayBuffer res)
                                   (.then #(.digest (.-subtle js/crypto) (names (:algorithm metadata)) %))
                                   (.then #(verdict metadata (bytes/base64 (js/Uint8Array. %)))))
                               {:valid? nil :reason (str "The file answered " (.-status res) ".")})))))))))
