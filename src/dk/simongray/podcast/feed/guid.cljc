(ns dk.simongray.podcast.feed.guid
  "The identity of feeds and episodes: a feed by its podcast:guid, or the
  one that the UUIDv5 scheme of podcast:guid derives from its URL, and an
  episode by its guid, on both platforms."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]))

(def podcast-namespace
  "The UUID namespace that every podcast:guid is derived in."
  "ead4c236-bf58-58c6-a2c6-a6b28d128cb6")

(defn uuid-v5
  "The name-based UUID (RFC 9562, section 5.5) of the string `name` in the
  UUID namespace `ns`, as a lower-case string."
  [ns name]
  (let [ns-bytes (bytes/from-hex (str/replace ns "-" ""))
        digest   (hash/digest :sha-1
                              (bytes/concat ns-bytes (bytes/encode name)))
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
      url/without-trailing-slashes))

(defn from-url
  "The podcast:guid of a feed at `url` whose author didn't declare one."
  [url]
  (uuid-v5 podcast-namespace (guid-name url)))

(defn normal-url
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

(defn keyed?
  "Whether `feed` has the key that a library keeps it under, as with-key
  gives it, so that feed-key needs no URL."
  [feed]
  (contains? (meta feed) ::key))

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

(defn- unreserved?
  "Whether the byte `x` is a character that a URL needn't percent-encode."
  [x]
  (or (<= 65 x 90) (<= 97 x 122) (<= 48 x 57) (#{45 46 95 126} x)))

;; RFC 3986, sections 2.1 and 2.3
(defn- encoded
  "The text `s` with each byte of its UTF-8 percent-encoded but for the
  unreserved characters of a URL."
  [s]
  (let [b (bytes/encode s)]
    (apply str (for [i (range (bytes/length b))
                     :let [x (bytes/at b i)]]
                 (if (unreserved? x)
                   (char x)
                   (str "%" (str/upper-case (bytes/hex (bytes/of [x])))))))))

(defn- decoded
  "The text that `s` percent-encodes as encoded does, or nil when it isn't
  such a text."
  [s]
  (let [pieces (re-seq #"%[0-9A-Fa-f]{2}|[A-Za-z0-9._~-]+" s)
        text   (bytes/decode (apply bytes/concat
                                    (for [piece pieces]
                                      (if (str/starts-with? piece "%")
                                        (bytes/from-hex (subs piece 1))
                                        (bytes/encode piece)))))]
    ;; anything else in it, or bytes that aren't UTF-8, give another text
    (when (= (str/replace s #"%[0-9a-f]{2}" str/upper-case) (encoded text))
      text)))

(defn format-episode-id
  "The episode `id` as one string, e.g. for a key or a native bridge, or
  nil when it isn't a pair of strings. Each part is percent-encoded and
  they're joined by a colon, so any guid round-trips, e.g.
  917393e3-…:https%3A%2F%2Fexample.com%2Fep%201.

  It isn't for the path of a URL, since many servers refuse a slash there
  even when it's percent-encoded. Name the item there by its short-hash."
  [[feed-guid item-guid :as id]]
  (when (and (string? feed-guid) (string? item-guid) (= 2 (count id)))
    (str (encoded feed-guid) ":" (encoded item-guid))))

(defn parse-episode-id
  "The episode id that format-episode-id gives the string `s` for, or nil
  when `s` is no such string."
  [s]
  (let [parts (when (string? s) (str/split s #":" -1))]
    (when (= 2 (count parts))
      (let [[feed-guid item-guid] (map decoded parts)]
        (when (and feed-guid item-guid)
          [feed-guid item-guid])))))

;; As git abbreviates the hash of a commit: the start of a hash is as good
;; as random, so a few hex digits tell the items of one feed apart.
;; https://git-scm.com/docs/git-rev-parse#_specifying_revisions
(defn short-hash
  "The first `n` hex digits of the SHA-1 of the item guid `s`, by default
  7, e.g. to name an episode of a show in the path of a URL, where the
  guid itself may not go."
  ([s]
   (short-hash s 7))
  ([s n]
   (subs (bytes/hex (hash/digest :sha-1 (bytes/encode s))) 0 n)))

(defn matching-items
  "The items of `items`, in order, whose guids have a SHA-1 that starts
  with the hex digits `h`, as short-hash gives them. That's one item, or
  more when `h` is too short to tell them apart."
  [items h]
  (let [h (str/lower-case h)]
    (filterv #(some-> (item-guid %) (short-hash 40) (str/starts-with? h))
             items)))

(comment
  (from-url "https://mp3s.nashownotes.com/pc20rss.xml")
  ;; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
  (from-url "podnews.net/rss")
  ;; => "9b024349-ccf0-5f69-a609-6b82873eab3c"
  #_.)
