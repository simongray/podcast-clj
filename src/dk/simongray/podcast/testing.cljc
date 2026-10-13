(ns dk.simongray.podcast.testing
  "Stand-ins for the network in the tests of an app built on podcast-clj: a
  sender that answers each request from a map, and the XML of a podcast
  feed to answer with.

  Give the sender as the :send of a library's config, a store/memory of
  like-minded as the store of a sync, and a :now-fn as the clock, and a
  test runs the library with no network and no time passing, as the guide
  shows."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.util.time :as time]))

(defn- lower-case-keys
  "The headers `m` by lower-case name, each value a vector of texts."
  [m]
  (into {}
        (map (fn [[k v]]
               [(str/lower-case (name k))
                (if (sequential? v) (mapv str v) [(str v)])]))
        m))

;; RFC 9110, section 13.1.3: If-Modified-Since counts only without
;; If-None-Match
(defn- unchanged?
  "Whether the request with the lower-case headers `asked` names the copy
  of the response with the lower-case headers `has` by its validators."
  [asked has]
  (let [etag     (first (get has "etag"))
        modified (first (get has "last-modified"))
        match    (first (get asked "if-none-match"))]
    (boolean (if match
               (and etag (= etag match))
               (and modified
                    (= modified (first (get asked "if-modified-since"))))))))

(defn sender
  "A function to give as :send, which answers each request by its URL from
  `responses`, a map of URL to a response, with the `opts` below.

  A response is a map of a :status, 200 by default, the :headers and the
  :body. A request whose If-None-Match names the ETag of its response, or
  whose If-Modified-Since names its Last-Modified, gets a 304, as a server
  answers when nothing changed. A URL without a response gets a 404. The
  `opts` are:

  - :log, an atom of a vector, to which each request is added"
  ([responses]
   (sender responses {}))
  ([responses {:keys [log] :as opts}]
   (fn [{:keys [url headers] :as request}]
     (when log
       (swap! log conj request))
     (if-let [{:keys [status body] :or {status 200} :as response}
              (get responses url)]
       (let [has (lower-case-keys (:headers response))]
         (if (unchanged? (lower-case-keys headers) has)
           {:status 304 :url url :headers has :body ""}
           {:status status :url url :headers has :body body}))
       {:status 404 :url url :headers {} :body "Not found"}))))

(defn- escaped
  "The text `s` for XML."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(def feed-defaults
  "The options of feed that its options don't give."
  {:title    "A show"
   :url      "https://example.com/feed.xml"
   :episodes 3
   :start    #inst "2026-01-01T06:00:00Z"
   :days     7
   :seconds  1800})

(defn- item
  "The XML of episode `n` of a feed with `opts`, which came out `days`
  days after episode `n` - 1, and episode 1 at `start`."
  [n {:keys [url start days seconds]}]
  (let [base      (str/replace url #"/[^/]*$" "")
        published (time/later start (* (dec n) days 86400))]
    (str "<item>"
         "<title>Episode " n "</title>"
         "<guid isPermaLink=\"false\">" (escaped (str base "/" n)) "</guid>"
         "<pubDate>" (time/format-rfc2822 published) "</pubDate>"
         "<description>The notes of episode " n ".</description>"
         "<enclosure url=\"" (escaped (str base "/" n ".mp3"))
         "\" type=\"audio/mpeg\" length=\"" (* 16000 seconds) "\"/>"
         "<itunes:duration>" seconds "</itunes:duration>"
         "</item>")))

;; RSS 2.0, with the itunes namespace of Apple and the podcast namespace
(defn feed
  "The XML of the RSS feed of a podcast with the `opts` below, over
  feed-defaults, e.g. for a response of sender.

  - :title, the title of the show
  - :url, where the feed is, which names its podcast:guid and its files
  - :guid, the podcast:guid, else that of the :url
  - :episodes, how many episodes, numbered from 1, the newest first
  - :start, when episode 1 came out, and :days, how many days apart they
    come
  - :seconds, how long each episode lasts"
  ([]
   (feed {}))
  ([opts]
   {:pre [(map? opts)]}
   (let [{:keys [title url guid episodes] :as o} (merge feed-defaults opts)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          "<rss version=\"2.0\""
          " xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\""
          " xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">"
          "<channel>"
          "<title>" (escaped title) "</title>"
          "<link>" (escaped url) "</link>"
          "<description>The show " (escaped title) ".</description>"
          "<podcast:guid>" (or guid (guid/from-url url)) "</podcast:guid>"
          (apply str (map #(item % o) (range episodes 0 -1)))
          "</channel></rss>"))))

(comment
  (def url "https://example.com/feed.xml")
  (def send (sender {url {:body    (feed {:episodes 2})
                          :headers {"ETag" "\"v1\""}}}))
  (:status (send {:url url :headers {"If-None-Match" "\"v1\""}}))
  #_.)
