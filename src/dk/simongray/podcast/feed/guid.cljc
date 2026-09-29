(ns dk.simongray.podcast.feed.guid
  "Feed identity: the `podcast:guid` UUIDv5 scheme, on both platforms."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]))

(def ^:no-doc podcast-namespace
  "The UUID namespace every `podcast:guid` is derived in."
  "ead4c236-bf58-58c6-a2c6-a6b28d128cb6")

(defn uuid-v5
  "The name-based UUID (RFC 4122 version 5) of the string `name` in the UUID
  namespace `ns`, as a lowercase string."
  [ns name]
  (let [h (bytes/sha1 (bytes/join [(bytes/from-hex (str/replace ns "-" "")) (bytes/encode name)]))
        h (-> (mapv #(bytes/at h %) (range 16))
              (update 6 #(bit-or (bit-and % 0x0f) 0x50))
              (update 8 #(bit-or (bit-and % 0x3f) 0x80)))
        s (bytes/hex (bytes/of h))]
    (str (subs s 0 8) "-" (subs s 8 12) "-" (subs s 12 16) "-"
         (subs s 16 20) "-" (subs s 20 32))))

(defn guid-name
  "The feed `url` as the specification seeds the guid with: without its scheme
  and without trailing slashes."
  [url]
  (-> url
      str/trim
      (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
      ;; a match may start only where a run of slashes starts: /+$ alone is
      ;; tried again from every slash of a long run, quadratically
      (str/replace #"(^|[^/])/+$" "$1")))

(defn from-url
  "The `podcast:guid` a feed at `url` is assigned when its author has not
  declared one."
  [url]
  (uuid-v5 podcast-namespace (guid-name url)))

(def ^:no-doc guid-pattern
  "Matches a UUID in its canonical hyphenated form."
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn guid?
  "Whether `s` looks like a UUID and so a plausible `podcast:guid`."
  [s]
  (boolean (and (string? s) (re-matches guid-pattern s))))

(comment
  (from-url "https://mp3s.nashownotes.com/pc20rss.xml")
  ;; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
  (from-url "podnews.net/rss")
  ;; => "9b024349-ccf0-5f69-a609-6b82873eab3c"
  #_.)
