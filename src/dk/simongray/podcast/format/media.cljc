(ns dk.simongray.podcast.format.media
  "What a media file says about itself, apart from the feed.

  A media file gives its duration, its chapters and its artwork. It's an
  MP3 file, as in dk.simongray.podcast.format.media.mp3 and .id3, or an
  MP4 or M4A file, as in .mp4. The verify! function checks a file against
  the podcast:integrity of an alternate enclosure.

  The read function takes a view of the parts of the file at hand, as in
  dk.simongray.podcast.util.bytes. It gives what they say, or the ranges
  that it still needs. The read! function fetches those ranges until the
  reading is done, most often in one or two requests and a few hundred
  kilobytes. It fetches with the HTTP client of podcast-clj on the JVM,
  with fetch in ClojureScript, or with a function that you give. The
  report function gives the diagnostics of a reading.

  A host that inserts ads serves another file to each request, so the
  duration is that of the file that came.

  The readers are strict, and their namespaces name their quirks. The
  :except option leaves out the quirks that it names."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.media.id3 :as id3]
            [dk.simongray.podcast.format.media.mp3 :as mp3]
            [dk.simongray.podcast.format.media.mp4 :as mp4]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.http :as http])
  #?(:clj (:import [java.security MessageDigest])))

(defn- sniff
  "The format of the file whose start `view` holds, or nil: :mp3 for an ID3
  tag or an MPEG audio frame, and :mp4 for an ftyp box."
  [view]
  (view/need view 0 12)
  (cond
    (= "ID3" (view/code view 0 3))  :mp3
    (= "ftyp" (view/code view 4 4)) :mp4
    (mp3/frame-header view 0)       :mp3))

(defn- mp3-result
  "What read gives for the MP3 file in `view`, read with `opts`."
  [view opts]
  (let [file     (mp3/read view opts)
        frames   (mapcat :frames (:tags file))
        duration (or (:duration file) (id3/duration frames))
        chs      (id3/chapters frames opts)
        artwork  (id3/artwork frames)]
    (view/reported
     (cond-> {:format :mp3}
       duration  (assoc :duration duration)
       (seq chs) (assoc :chapters {:version chapters/version :chapters chs})
       artwork   (assoc :artwork artwork))
     (into (mp3/report file) (id3/report chs)))))

(defn- mp4-result
  "What read gives for the MP4 file in `view`, read with `opts`."
  [view opts]
  (let [{:keys [chapters] :as file} (mp4/read view opts)
        document {:version chapters/version :chapters chapters}]
    (cond-> (assoc (dissoc file :chapters) :format :mp4)
      chapters (assoc :chapters document))))

(defn report
  "The diagnostics of `file`, which read or read! gave. Each diagnostic has
  its message."
  [file]
  (mapv diagnostics/describe (view/report file)))

(defn read
  "What the media file whose parts `view` holds says about itself, read
  with `opts`. It's a map of :format, :duration, :chapters and :artwork,
  each absent when the file doesn't have it.

  - :format is :mp3 or :mp4
  - :duration is in seconds
  - :chapters is a chapters document of dk.simongray.podcast.format.chapters
  - :artwork is a map of :type and :data

  When the view lacks bytes, the result has the ranges that the reading
  needs under :need, as pairs of start and end, end excluded.

  The reading doesn't throw on the content of the file, and report tells
  what it didn't read. The :except of `opts` is the ids of the quirks to
  leave out."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (let [unknown {:id :file/unknown-format :level :warning}]
     (view/reading #(case (sniff view)
                      :mp3 (mp3-result view opts)
                      :mp4 (mp4-result view opts)
                      (view/reported {} [unknown]))))))

(defn read!
  "Read what the media file at `url` says about itself as read does,
  fetched with `opts`. On the JVM it gives the result, and in
  ClojureScript a promise of it.

  The function dk.simongray.podcast.util.bytes.view/read! fetches the
  ranges that the reading needs. The `opts` are these:

  - :fetch, a function as http/fetcher makes, to fetch your own way
  - :headers, :timeout-seconds and :send, as http/fetcher takes them
  - :allow?, as http/send! takes it
  - :except, as read takes it
  - the limits of view/read!, with view/default-limits for those that
    they don't set

  When the ranges don't come, or the file asks for more than the limits
  allow, the report has :file/incomplete. A server that ignores ranges
  sends a file from its start, so the bytes at an offset cost all the
  bytes before them.

  Nothing that the network or the server does throws. When a request
  fails, the result has a :file/unreachable diagnostic under :error."
  ([url]
   (read! url {}))
  ([url opts]
   (let [read-opts (select-keys opts [:except])]
     (async/then (http/read-by-ranges! url #(read % read-opts) opts)
                 #(cond-> % (:error %) (update :error diagnostics/describe))))))

(def ^:no-doc sri-algorithms
  "The hash algorithms of Subresource Integrity, weakest first, with their
  names in MessageDigest and WebCrypto."
  [["sha256" "SHA-256"] ["sha384" "SHA-384"] ["sha512" "SHA-512"]])

(defn sri
  "The strongest hashes of the SRI integrity metadata `s`, as Subresource
  Integrity of the W3C selects them. It's nil when `s` names no hash of
  sha256, sha384 or sha512.

  The result is a map of the strongest :algorithm that `s` names, e.g.
  \"sha384\", and the set of its base64 :digests."
  [s]
  (let [rank      (into {} (map-indexed (fn [i [a _]] [a i])) sri-algorithms)
        sri-hash  #"(?i)(sha256|sha384|sha512)-([A-Za-z0-9+/]+={0,2})(?:\?\S*)?"
        hashes    (keep #(when-let [[_ a v] (re-matches sri-hash %)]
                           [(str/lower-case a) v])
                        (str/split (str/trim (str s)) #"\s+"))
        strongest (last (sort-by rank (map first hashes)))]
    (when strongest
      {:algorithm strongest
       :digests   (set (keep (fn [[a v]] (when (= a strongest) v)) hashes))})))

(defn ^:no-doc verdict
  "The verdict on the base64 `digest` of a file, against the hashes of
  `metadata`, as sri gives them."
  [{:keys [algorithm digests] :as metadata} digest]
  {:valid? (contains? digests digest) :algorithm algorithm})

(defn ^:no-doc unverified
  "The verdict on a file that wasn't checked, with the `diagnostic` that
  says why."
  [diagnostic]
  {:valid? nil :error (diagnostics/describe diagnostic)})

(defn ^:no-doc absent
  "The verdict on the file at `url` that didn't come, since the request for
  it failed with `e`."
  [url e]
  (unverified (assoc (view/unreachable e) :url url)))

(defn- digest
  "The base64 digest of the bytes `b` by `algorithm`, as MessageDigest and
  WebCrypto name it. In ClojureScript, it's in a promise."
  [algorithm b]
  #?(:clj  (let [md (MessageDigest/getInstance algorithm)]
             (bytes/base64 (.digest md ^bytes b)))
     :cljs (.then (.digest (.-subtle js/crypto) algorithm b)
                  #(bytes/base64 (js/Uint8Array. %)))))

(defn- fetch-whole!
  "Fetch the file at `url` whole with `send` and the limits of `opts`, and
  give the verdict against `metadata` by `algorithm`, in a future or a
  promise."
  [send url metadata algorithm opts]
  (let [request {:url             url
                 :as              :bytes
                 :headers         (:headers opts)
                 :timeout-seconds 600}]
    (async/then
     (http/try-send! send request opts)
     (fn [{:keys [error] {:keys [status body]} :value}]
       (cond
         error
         (absent url error)

         (= 200 status)
         (async/then (digest algorithm (or body (bytes/of [])))
                     #(verdict metadata %))

         :else
         (absent url (http/failed url status)))))))

#?(:clj
   (defn- stream!
     "Stream the file at `url` through the digest by `algorithm`, with the
     limits of `opts`, and give the verdict against `metadata`."
     [url metadata algorithm opts]
     (try
       (let [{:keys [status digests]} (http/digest! url [algorithm] opts)]
         (if digests
           (verdict metadata (bytes/base64 (get digests algorithm)))
           (absent url (http/failed url status))))
       (catch Exception e
         (absent url e)))))

(defn verify!
  "Check whether the file at `url` is what `integrity` says, with `opts`.
  On the JVM it gives the result, and in ClojureScript a promise of it.

  The `integrity` is the podcast:integrity of an alternate enclosure, a
  map of :type and :value. The result has :valid? true and the :algorithm
  when the digest of the whole file is one of the strongest hashes of
  `integrity`. Otherwise, :valid? is false.

  The result has :valid? nil and a diagnostic under :error when no check
  was made:

  - :file/unverifiable for a PGP signature, which needs the key of the
    publisher, or for a value that names no hash of Subresource Integrity
  - :file/unreachable when the file didn't come

  Nothing that the network or the server does throws. On the JVM, the file
  streams through the digest. In ClojureScript, or with :send, the file
  comes whole, up to the :max-bytes of http/send!. The `opts` are these:

  - :send, a function that sends a request map in place of http/send!
  - :headers, :timeout-seconds and :allow?, as http/send! takes them, with
    a timeout of 600 seconds by default"
  ([url integrity]
   (verify! url integrity {}))
  ([url {:keys [type value] :as integrity} {:keys [send] :as opts}]
   (let [kind      (some-> type str/lower-case)
         metadata  (when (= "sri" kind) (sri value))
         algorithm (get (into {} sri-algorithms) (:algorithm metadata))
         reason    (cond
                     (= "pgp-signature" kind)
                     "A PGP signature needs the key of the publisher."

                     (nil? metadata)
                     (str "The integrity names no hash of sha256, sha384 "
                          "or sha512."))]
     (if reason
       (let [unchecked (unverified {:id :file/unverifiable :reason reason})]
         (async/then unchecked identity))
       #?(:clj  (if send
                  (fetch-whole! send url metadata algorithm opts)
                  (stream! url metadata algorithm opts))
          :cljs (fetch-whole! (or send http/send!)
                              url metadata algorithm opts))))))

(comment
  ;; an MP3 file of 20 frames of 128 kbit/s, with no tag
  (def sample
    (let [frame (concat [0xFF 0xFB 0x90 0x00] (repeat 413 0))
          b     (bytes/of (apply concat (repeat 20 frame)))]
      (view/add (view/view) 0 b (bytes/length b))))

  (read sample)
  (map :id (report (read sample)))

  ;; a file that is no media file
  (report (read (view/add (view/view) 0
                          (bytes/encode "<html>not audio</html>") 22)))

  (read! "https://example.org/episode.mp3")
  (sri "sha256-abc= sha384-def=")
  (verify! "https://example.org/episode.mp3" {:type "sri" :value "sha384-def="})
  #_.)
