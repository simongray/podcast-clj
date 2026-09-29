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
            [dk.simongray.podcast.bytes :as bytes]
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
   (bytes/reading #(case (sniff view)
                     :mp3 (mp3-result view opts)
                     :mp4 (mp4-result view opts)
                     {:report [{:id :file/unknown-format :level :warning}]}))))

(defn read!
  "What the media file at `url` says about itself, as `read` gives it,
  fetching by `dk.simongray.podcast.bytes/read!` the ranges that the
  reading needs: on the JVM the result, in ClojureScript a promise of it.
  `opts` may give `:fetch`, a function as `http/fetcher` makes, for an app
  that fetches its own way; the `:headers` and `:timeout-seconds` of
  `http/fetcher`, and its `:send`; and the `:except` of `read`. A file
  whose ranges do not come gives a report of `:file/incomplete`.

  The reading fetches the `:max-bytes` of `opts` at most, by default those
  of `bytes/default-limits`. A file that asks for more gives the same
  report. A server that ignores ranges sends a file from its start, so
  the reading throws `::http/too-large` when it needs bytes after those."
  ([url]
   (read! url {}))
  ([url {:keys [fetch] :as opts}]
   (let [read-opts (select-keys opts [:except])
         opts      (merge bytes/default-limits opts)]
     (bytes/read! (or fetch (http/fetcher url opts)) #(read % read-opts) opts))))

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
  digest; in ClojureScript it comes whole, up to the `:max-bytes` of
  `http/call!`, and the result is a promise. `opts` can give the
  `:headers`, the `:timeout-seconds` (default 600) and the `:allow?` of the
  request, as `http/call!` takes them."
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
          :cljs (-> (http/call! (merge {:url url :as :bytes :timeout-seconds 600}
                                       (select-keys opts [:headers :timeout-seconds :max-bytes :allow?])))
                    (.then (fn [{:keys [status body]}]
                             (if (= 200 status)
                               (-> (.digest (.-subtle js/crypto) (names (:algorithm metadata)) (or body (js/Uint8Array. 0)))
                                   (.then #(verdict metadata (bytes/base64 (js/Uint8Array. %)))))
                               {:valid? nil :reason (str "The file answered " status ".")})))))))))
