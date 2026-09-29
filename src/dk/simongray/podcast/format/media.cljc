(ns dk.simongray.podcast.format.media
  "What a media file says about itself, apart from the feed.

  A media file gives its duration, its chapters and its artwork. It is an
  MP3 file (see dk.simongray.podcast.format.mp3 and .id3), or an MP4 or
  M4A file (see .mp4). `verify!` checks a file against the
  podcast:integrity of an alternate enclosure.

  `read` takes a view of the parts of the file at hand (see
  dk.simongray.podcast.bytes). It gives what they say, or the ranges that
  it still needs. `read!` fetches those ranges until the reading is done,
  most often in one or two requests and a few hundred kilobytes. It
  fetches with the HTTP client of podcast-clj on the JVM, with fetch in
  ClojureScript, or with a function of the app. `report` gives the
  diagnostics of a reading.

  A host that inserts ads serves another file to each request, so the
  duration is that of the file that came.

  The readers are strict, and their namespaces name their quirks.
  `:except` in the options leaves out the quirks that it names."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.id3 :as id3]
            [dk.simongray.podcast.format.mp3 :as mp3]
            [dk.simongray.podcast.format.mp4 :as mp4]
            [dk.simongray.podcast.http :as http])
  #?(:clj (:import [java.security MessageDigest])))

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
  (let [file     (mp3/read view opts)
        frames   (mapcat :frames (:tags file))
        duration (or (:duration file) (id3/length frames))
        chs      (id3/chapters frames opts)
        artwork  (id3/artwork frames)]
    (bytes/reported
     (cond-> {:format :mp3}
       duration  (assoc :duration duration)
       (seq chs) (assoc :chapters {:version chapters/version :chapters chs})
       artwork   (assoc :artwork artwork))
     (into (mp3/report file) (id3/report chs)))))

(defn- mp4-result
  "What `read` gives for the MP4 file in `view`."
  [view opts]
  (let [{:keys [chapters] :as file} (mp4/read view opts)]
    (cond-> (assoc (dissoc file :chapters) :format :mp4)
      chapters (assoc :chapters {:version chapters/version :chapters chapters}))))

(defn report
  "The diagnostics of `file`, which `read` or `read!` gave. Each
  diagnostic has its message."
  [file]
  (bytes/report file))

(defn read
  "What the media file whose parts `view` holds says about itself, as
  `{:format :duration :chapters :artwork}`. A key is absent when the file
  does not have its value.

  `:format` is :mp3 or :mp4, and `:duration` is in seconds. `:chapters` is
  a chapters document of dk.simongray.podcast.format.chapters, and
  `:artwork` is `{:type :data}`.

  When the view lacks bytes, the result is `{:need [[start end] ...]}`,
  the ranges that the reading needs, end excluded.

  The reading does not throw on the content of the file. `report` tells
  what it did not read. `opts` can give `:except`, the ids of the quirks
  to leave out."
  ([view]
   (read view {}))
  ([view opts]
   {:pre [(map? opts)]}
   (bytes/reading #(case (sniff view)
                     :mp3 (mp3-result view opts)
                     :mp4 (mp4-result view opts)
                     (bytes/reported {} [{:id :file/unknown-format :level :warning}])))))

(defn read!
  "What the media file at `url` says about itself, as `read` gives it. On
  the JVM it gives the result, and in ClojureScript a promise of it.

  `dk.simongray.podcast.bytes/read!` fetches the ranges that the reading
  needs. `opts` can give `:fetch`, a function as `http/fetcher` makes, for
  an app that fetches its own way. They can also give the `:headers`, the
  `:timeout-seconds` and the `:send` of `http/fetcher`, the `:allow?` of
  `http/call!`, and the `:except` of `read`.

  `opts` give the limits of `bytes/read!`, with `bytes/default-limits` for
  the limits that they do not set. When the ranges do not come, or when
  the file asks for more than the limits allow, the report has
  `:file/incomplete`.

  A server that ignores ranges sends a file from its start. The bytes at
  an offset then cost all the bytes before them.

  `read!` throws nothing for what the network or the server does. When a
  request fails, the result is `{:error diagnostic}`, and the diagnostic
  is `:file/unreachable`."
  ([url]
   (read! url {}))
  ([url opts]
   (let [read-opts (select-keys opts [:except])]
     (http/read-by-ranges! url #(read % read-opts) opts))))

(def ^:no-doc sri-algorithms
  "The hash algorithms of Subresource Integrity, weakest first, with their
  names in MessageDigest and WebCrypto."
  [["sha256" "SHA-256"] ["sha384" "SHA-384"] ["sha512" "SHA-512"]])

(defn sri
  "The strongest hashes of the SRI integrity metadata `s`, as the
  Subresource Integrity of the W3C selects them. Nil when `s` names no
  hash of sha256, sha384 or sha512.

  The result is `{:algorithm \"sha384\" :digests #{\"base64…\"}}`: the
  strongest algorithm that `s` names, and its hashes."
  [s]
  (let [rank      (into {} (map-indexed (fn [i [a _]] [a i])) sri-algorithms)
        hashes    (keep #(when-let [[_ a v] (re-matches #"(?i)(sha256|sha384|sha512)-([A-Za-z0-9+/]+={0,2})(?:\?\S*)?" %)]
                           [(str/lower-case a) v])
                        (str/split (str/trim (str s)) #"\s+"))
        strongest (last (sort-by rank (map first hashes)))]
    (when strongest
      {:algorithm strongest
       :digests   (set (keep (fn [[a v]] (when (= a strongest) v)) hashes))})))

(defn ^:no-doc verdict
  "The verdict on the `digest` in base64 of the file, against the hashes
  that `metadata` from `sri` gives."
  [{:keys [algorithm digests]} digest]
  {:valid? (contains? digests digest) :algorithm algorithm})

(defn ^:no-doc unverified
  "The verdict on a file that was not checked. The `diagnostic` tells
  why."
  [diagnostic]
  {:valid? nil :error (diagnostics/describe diagnostic)})

(defn ^:no-doc absent
  "The verdict on the file at `url` that did not come, since the request
  for it failed with `e`."
  [url e]
  (unverified (assoc (bytes/unreachable e) :url url)))

(defn- digest
  "The digest in base64 of the bytes `b`, by the `algorithm` as
  MessageDigest and WebCrypto name it; deferred in ClojureScript."
  [algorithm b]
  #?(:clj  (bytes/base64 (.digest (MessageDigest/getInstance algorithm) ^bytes b))
     :cljs (.then (.digest (.-subtle js/crypto) algorithm b)
                  #(bytes/base64 (js/Uint8Array. %)))))

(defn- whole!
  "The verdict on the file at `url` against `metadata`, by the `algorithm`.
  `send` fetches the file whole, with the limits of `opts`; deferred."
  [send url metadata algorithm opts]
  (async/then
   (http/sent! send {:url url :as :bytes :headers (:headers opts) :timeout-seconds 600} opts)
   (fn [{:keys [error] {:keys [status body]} :value}]
     (cond
       error          (absent url error)
       (= 200 status) (async/then (digest algorithm (or body (bytes/of []))) #(verdict metadata %))
       :else          (absent url (http/failed url status))))))

#?(:clj
   (defn- streamed!
     "The verdict on the file at `url` against `metadata`, by the
     `algorithm`. The file streams through the digest, with the limits of
     `opts`."
     [url metadata algorithm opts]
     (try
       (let [{:keys [status digests]} (http/digest! url [algorithm] opts)]
         (if digests
           (verdict metadata (bytes/base64 (get digests algorithm)))
           (absent url (http/failed url status))))
       (catch Exception e
         (absent url e)))))

(defn verify!
  "Whether the file at `url` is what `integrity` says, the
  podcast:integrity of an alternate enclosure: `{:type \"sri\" :value …}`.
  On the JVM it gives the result, and in ClojureScript a promise of it.

  The result is `{:valid? true :algorithm …}` when the digest of the whole
  file is one of the strongest hashes of `integrity`. `:valid?` is false
  when it is not.

  The result is `{:valid? nil :error diagnostic}` when no check was made.
  The diagnostic is `:file/unverifiable` for a PGP signature, which needs
  the key of the publisher, and for a value that names no hash that
  Subresource Integrity knows. It is `:file/unreachable` when the file
  did not come. `verify!` throws nothing for what the network or the
  server does.

  On the JVM, the file streams through the digest. With `:send` in
  `opts`, and in ClojureScript, the file comes whole, up to the
  `:max-bytes` of `http/call!`. `:send` is a function that sends a request
  map as `http/call!` does and in its place.

  `opts` can also give the `:headers`, the `:timeout-seconds` (default
  600) and the `:allow?` of the request, as `http/call!` takes them."
  ([url integrity]
   (verify! url integrity {}))
  ([url {:keys [type value]} {:keys [send] :as opts}]
   (let [kind      (some-> type str/lower-case)
         metadata  (when (= "sri" kind) (sri value))
         algorithm (get (into {} sri-algorithms) (:algorithm metadata))
         reason    (cond
                     (= "pgp-signature" kind) "A PGP signature needs the key of the publisher."
                     (nil? metadata)          "The integrity names no hash of sha256, sha384 or sha512.")]
     (if reason
       (async/then (unverified {:id :file/unverifiable :reason reason}) identity)
       #?(:clj  (if send
                  (whole! send url metadata algorithm opts)
                  (streamed! url metadata algorithm opts))
          :cljs (whole! (or send http/call!) url metadata algorithm opts))))))

(comment
  ;; an MP3 file of 20 frames of 128 kbit/s, with no tag
  (def sample
    (let [b (bytes/of (apply concat (repeat 20 (concat [0xFF 0xFB 0x90 0x00] (repeat 413 0)))))]
      (bytes/add (bytes/view) 0 b (bytes/length b))))

  (read sample)
  (map :id (report (read sample)))

  ;; a file that is no media file
  (report (read (bytes/add (bytes/view) 0 (bytes/encode "<html>not audio</html>") 22)))

  (read! "https://example.org/episode.mp3")
  (sri "sha256-abc= sha384-def=")
  (verify! "https://example.org/episode.mp3" {:type "sri" :value "sha384-def="})
  #_.)
