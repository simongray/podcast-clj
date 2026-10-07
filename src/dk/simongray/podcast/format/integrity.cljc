(ns dk.simongray.podcast.format.integrity
  "The podcast:integrity of an alternate enclosure, and a check of a file
  against it.

  The integrity is Subresource Integrity metadata of the W3C, e.g.
  sha384-…, or a PGP signature. The sri function reads the metadata, and
  verify! fetches a file and checks its digest, on the JVM as a stream.
  Subresource Integrity is at https://www.w3.org/TR/sri/"
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.podcast.util.http :as http]))

(def ^:no-doc sri-algorithms
  "The hash algorithms of Subresource Integrity, weakest first, with their
  algorithms of util.bytes/digest, whose names in MessageDigest are those
  of WebCrypto too."
  [["sha256" :sha256] ["sha384" :sha384] ["sha512" :sha512]])

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
  "The base64 digest of the bytes `b` by `algorithm`, as util.bytes/digest
  takes it. In ClojureScript, it's in a promise, from WebCrypto, which
  digests a large file fast."
  [algorithm b]
  #?(:clj  (bytes/base64 (bytes/digest algorithm b))
     :cljs (.then (.digest (.-subtle js/crypto)
                           (bytes/digest-names algorithm)
                           b)
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
       (let [named                    (bytes/digest-names algorithm)
             {:keys [status digests]} (http/digest! url [named] opts)]
         (if digests
           (verdict metadata (bytes/base64 (get digests named)))
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
  (sri "sha256-abc= sha384-def=")
  (verify! "https://example.org/episode.mp3" {:type "sri" :value "sha384-def="})
  #_.)
