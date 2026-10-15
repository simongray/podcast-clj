(ns ^:no-doc dk.simongray.podcast.directory.links.impl
  "The internals of directory.links: the regular expressions that read the
  ids back from a link of an app."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn encoding-of
  "The encoding and the key of the part `p` of a form that names a key."
  [p]
  (if (keyword? p) [:plain p] p))

(def id-patterns
  "The regex of the text of each key of the ids in a link. A key that
  isn't here is the text of one part of a path or a query."
  (let [hex #(str "[0-9a-fA-F]{" % "}")]
    {:apple-id                "[0-9]+"
     :apple-episode-id        "[0-9]+"
     :podcastindex-id         "[0-9]+"
     :podcastindex-episode-id "[0-9]+"
     :podcast-guid            (str/join "-" (map hex [8 4 4 4 12]))
     :feed-url                ".+"}))

(defn- quoted
  "The text `s` as a regex that matches it as it is."
  [s]
  (let [special #{\\ \^ \$ \. \| \? \* \+ \( \) \[ \] \{ \}}]
    (str/escape s #(when (special %) (str "\\" %)))))

(defn- web-start
  "The regex of `s`, the start of a form of a link on the web. It takes
  http or https, with or without www., and one more part of the path
  before each part of `s` that has a name. So the form of Apple Podcasts
  reads the links that it shares, which have a country and the name of
  the show in their paths."
  [s]
  (let [web-url             #"(?i)https?://(?:www\.)?([^/?#]*)([^?#]*)(.*)"
        [_ host path query] (re-matches web-url s)
        segment             (fn [part]
                              (str (when (< 1 (count part)) "(?:/[^/?#]+)?")
                                   (quoted part)))]
    (str "https?://(?:www\\.)?" (quoted host)
         (apply str (map segment (re-seq #"/[^/]*" path)))
         (quoted query))))

(defn- part-pattern
  "The regex of the text of the part `p` of a form that names a key."
  [p]
  (let [[encoding k] (encoding-of p)]
    (case encoding
      :encoded   "[^&#]+"
      :base64url "[A-Za-z0-9_-]+"
      :hex       "[0-9a-fA-F]+"
      (get id-patterns k "[^/?#&]+"))))

(defn- form-pattern
  "The regex that reads a link of `form`, with a group for each key."
  [[start & parts :as form]]
  (let [web?       (re-find #"(?i)^https?://" start)
        pattern-of #(if (string? %)
                      (quoted %)
                      (str "(" (part-pattern %) ")"))]
    (re-pattern (str "(?i)^"
                     (if web? (web-start start) (quoted start))
                     (apply str (map pattern-of parts))
                     "(?:[/?#].*)?$"))))

(defn- value-of
  "The value of the key that the part `p` of a form names, read from the
  `text` of the part in a link, or nil when the text doesn't read as one.

  A feed URL that isn't encoded in the form is decoded anyway when it is,
  as some apps encode it. One in a bare form keeps no scheme."
  [p text]
  (let [[encoding k] (encoding-of p)
        encoded-url? (and (= [:plain :feed-url] [encoding k])
                          (not (str/includes? text "://")))
        v            (case encoding
                       :encoded   (url/percent-decode text)
                       :base64url (some-> (bytes/from-base64url text)
                                          bytes/decode)
                       :hex       (some-> (bytes/from-hex text)
                                          bytes/decode)
                       (if encoded-url? (url/percent-decode text) text))
        with-scheme  (cond->> v (= :bare encoding) (str "https://"))]
    (case k
      :feed-url
      (when (url/web-url? with-scheme) v)

      (:podcastindex-id :podcastindex-episode-id)
      (some-> v parse-long)

      v)))

(defn read-form
  "The ids that the link `url` holds by `form`, or nil when the form
  doesn't read the link."
  [form url]
  (let [found (re-find (form-pattern form) url)
        keyed (remove string? (rest form))
        ks    (map (comp second encoding-of) keyed)]
    (when (vector? found)
      (let [values (map value-of keyed (rest found))]
        (when (every? some? values)
          (dissoc (zipmap ks values) :slug))))))
