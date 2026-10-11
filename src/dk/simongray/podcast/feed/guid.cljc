(ns dk.simongray.podcast.feed.guid
  "The identity of feeds and episodes: a feed by its podcast:guid, or the
  one that the UUIDv5 scheme of podcast:guid derives from its URL, and an
  episode by its guid, on both platforms."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.wary-fetch.url :as url]))

(def ^:no-doc podcast-namespace
  "The UUID namespace that every podcast:guid is derived in."
  "ead4c236-bf58-58c6-a2c6-a6b28d128cb6")

(defn uuid-v5
  "The name-based UUID (RFC 4122, section 4.3) of the string `name` in the
  UUID namespace `ns`, as a lower-case string."
  [ns name]
  (let [ns-bytes (bytes/from-hex (str/replace ns "-" ""))
        digest   (bytes/sha1 (bytes/join [ns-bytes (bytes/encode name)]))
        octets   (-> (mapv #(bytes/at digest %) (range 16))
                     (update 6 #(bit-or (bit-and % 0x0f) 0x50))
                     (update 8 #(bit-or (bit-and % 0x3f) 0x80)))
        s        (bytes/hex (bytes/of octets))]
    (str (subs s 0 8) "-" (subs s 8 12) "-" (subs s 12 16) "-"
         (subs s 16 20) "-" (subs s 20 32))))

(defn guid-name
  "The feed `url` as the podcast namespace seeds a podcast:guid with, which
  is without its scheme and trailing slashes."
  [url]
  (-> url
      str/trim
      (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
      ;; a match may start only where a run of slashes starts: /+$ alone is
      ;; tried again from every slash of a long run, quadratically
      (str/replace #"(^|[^/])/+$" "$1")))

(defn from-url
  "The podcast:guid of a feed at `url` whose author didn't declare one."
  [url]
  (uuid-v5 podcast-namespace (guid-name url)))

(defn ^:no-doc normal-url
  "The `url` in lower case without credentials, scheme or trailing
  slashes, so that two forms of one feed or media URL are equal."
  [url]
  (-> (str url) url/split-credentials first guid-name str/lower-case))

(def ^:no-doc guid-pattern
  "The pattern of a UUID in its canonical form, with hyphens."
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn guid?
  "Whether `s` looks like a UUID, and so like a podcast:guid."
  [s]
  (boolean (and (string? s)
                (re-matches guid-pattern s))))

(defn item-guid
  "The identity of `item`: its guid, or else its enclosure URL, as Apple's
  A Podcaster's Guide to RSS has it."
  [item]
  (or (not-empty (:content (:rss/guid item)))
      (:url (:rss/enclosure item))))

;; podcast namespace, podcast:guid: a UUIDv5, or else derived from the URL,
;; as the Podcast Index does
(defn feed-guid
  "The identity that `feed` gives itself at `url`: its podcast:guid when
  that's a UUID, or else the one that the podcast namespace derives from
  `url`."
  [feed url]
  (let [declared (:podcast/guid feed)]
    (if (guid? declared)
      declared
      (when url (from-url url)))))

(defn with-key
  "The `feed` with `k` in its metadata as the key that a library keeps it
  under, which episode-id takes over what the feed says now."
  [feed k]
  (vary-meta feed assoc ::key k))

(defn feed-key
  "The key that a library keeps `feed` under, as with-key gives it, or
  else its feed-guid at `url`.

  The key is settled at the first fetch and stays, while the feed-guid
  changes with a new podcast:guid, or with the URL of a feed that has
  none."
  [feed url]
  (or (::key (meta feed))
      (feed-guid feed url)))

(defn episode-id
  "The id of `item` in the `feed` at `url`: [key item-guid], with the key
  of feed-key."
  [feed url item]
  [(feed-key feed url) (item-guid item)])

(comment
  (from-url "https://mp3s.nashownotes.com/pc20rss.xml")
  ;; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
  (from-url "podnews.net/rss")
  ;; => "9b024349-ccf0-5f69-a609-6b82873eab3c"
  #_.)
