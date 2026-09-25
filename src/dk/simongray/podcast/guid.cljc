(ns dk.simongray.podcast.guid
  "Feed identity: the `podcast:guid` UUIDv5 scheme and the SHA-1 it and the
  Podcast Index API need, on both platforms."
  (:require [clojure.string :as str])
  #?(:clj  (:import [java.security MessageDigest]
                    [java.nio.charset StandardCharsets])
     :cljs (:require [goog.crypt :as crypt]
                     [goog.crypt.Sha1])))

(def podcast-namespace
  "The UUID namespace every `podcast:guid` is derived in."
  "ead4c236-bf58-58c6-a2c6-a6b28d128cb6")

(defn utf8-bytes
  "The UTF-8 encoding of the string `s` as a byte array."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (crypt/stringToUtf8ByteArray s)))

(defn sha1
  "The SHA-1 digest of `bytes` as a byte array."
  [bytes]
  #?(:clj  (.digest (MessageDigest/getInstance "SHA-1") ^bytes bytes)
     :cljs (let [h (goog.crypt.Sha1.)]
             (.update h bytes)
             (.digest h))))

(defn hex
  "Lowercase hexadecimal rendering of `bytes`."
  [bytes]
  (apply str (map (fn [b]
                    (let [n (bit-and b 0xff)]
                      (str (when (< n 16) "0")
                           #?(:clj  (Integer/toHexString n)
                              :cljs (.toString n 16)))))
                  bytes)))

(defn sha1-hex
  "The SHA-1 of the string `s`, as hex; what the Podcast Index API wants in
  its Authorization header."
  [s]
  (hex (sha1 (utf8-bytes s))))

(defn- uuid-bytes
  "The 16 bytes of the UUID written as `s`."
  [s]
  (let [clean (str/replace s "-" "")]
    (#?(:clj byte-array :cljs clj->js)
     (for [i (range 16)]
       (let [n (#?(:clj Integer/parseInt :cljs js/parseInt) (subs clean (* 2 i) (+ 2 (* 2 i))) 16)]
         #?(:clj (unchecked-byte n) :cljs n))))))

(defn- concat-bytes
  [a b]
  #?(:clj  (let [out (byte-array (+ (alength ^bytes a) (alength ^bytes b)))]
             (System/arraycopy a 0 out 0 (alength ^bytes a))
             (System/arraycopy b 0 out (alength ^bytes a) (alength ^bytes b))
             out)
     :cljs (.concat a b)))

(defn uuid-v5
  "The name-based UUID (RFC 4122 version 5) of the string `name` in the UUID
  namespace `ns`, as a lowercase string."
  [ns name]
  (let [h (vec (sha1 (concat-bytes (uuid-bytes ns) (utf8-bytes name))))
        h (-> h
              (update 6 #(bit-or (bit-and % 0x0f) 0x50))
              (update 8 #(bit-or (bit-and % 0x3f) 0x80)))
        s (hex (take 16 h))]
    (str (subs s 0 8) "-" (subs s 8 12) "-" (subs s 12 16) "-"
         (subs s 16 20) "-" (subs s 20 32))))

(defn guid-name
  "The feed `url` as the specification seeds the guid with: without its scheme
  and without trailing slashes."
  [url]
  (-> url
      str/trim
      (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
      (str/replace #"/+$" "")))

(defn feed-guid
  "The `podcast:guid` a feed at `url` is assigned when its author has not
  declared one."
  [url]
  (uuid-v5 podcast-namespace (guid-name url)))

(def guid-pattern
  "Matches a UUID in its canonical hyphenated form."
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn guid?
  "Whether `s` looks like a UUID and so a plausible `podcast:guid`."
  [s]
  (boolean (and (string? s) (re-matches guid-pattern s))))

(comment
  (feed-guid "https://mp3s.nashownotes.com/pc20rss.xml")
  ;; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
  (feed-guid "podnews.net/rss")
  ;; => "9b024349-ccf0-5f69-a609-6b82873eab3c"
  #_.)
