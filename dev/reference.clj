(ns reference
  "Generate doc/feed-model.md, the reference of every element the library
  understands, from the tag registry, and doc/diagnostics.md from the
  diagnostics catalogue. Run `(write!)` after changing either and commit the
  result."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.xml :as xml]))

(def vocabularies
  "The sections of the reference, in order: title, the tag namespaces they
  cover and a line about the source."
  [{:title "RSS 2.0" :namespaces #{nil}
    :about (str "The core elements, as "
                "https://www.rssboard.org/rss-specification defines them.")}
   {:title "iTunes" :namespaces #{"itunes"}
    :about (str "The namespace of Apple, as "
                "https://podcasters.apple.com/support/823-podcast-requirements "
                "describes it.")}
   {:title "Podcast namespace (Podcasting 2.0)" :namespaces #{"podcast"}
    :about (str "All the formalised tags of the `podcast` namespace, as "
                "https://github.com/Podcastindex-org/podcast-namespace "
                "defines them.")}
   {:title "Podlove Simple Chapters" :namespaces #{"psc"}
    :about (str "Chapters in the feed, as "
                "https://podlove.org/simple-chapters/ defines them.")}
   {:title "Media RSS" :namespaces #{"media"}
    :about "The Media RSS module of Yahoo, as far as podcast hosts use it."}
   {:title "Google Play and Spotify" :namespaces #{"googleplay" "spotify"}
    :about "The namespaces of two directories, which some feeds still contain."}
   {:title "RawVoice" :namespaces #{"rawvoice"}
    :about "The namespace of Blubrry, as far as its feeds use it."}
   {:title      "Atom, Dublin Core, content and syndication"
    :namespaces #{"atom" "dc" "content" "sy"}
    :about      "Modules from the wider RSS world that podcast feeds contain."}
   {:title "Feed paging and archiving" :namespaces #{"fh"}
    :about (str "The elements of RFC 5005, for a feed that comes in more "
                "than one document. The links between the documents are "
                "`atom:link` elements.")}])

(defn- parent-name
  [parent]
  (case parent
    :channel "channel"
    :item    "item (and liveItem)"
    (str "<" (xml/qname parent) ">")))

(defn- type-name
  [{:keys [type values]}]
  (str (name (or type :text))
       (when (seq values)
         (str ": " (str/join ", " (map #(str "`" % "`") (sort values)))))))

(defn- keys-line
  "The model key, or the keys per parent when they differ."
  [{:keys [key parent]}]
  (if (map? key)
    (str/join ", " (for [p (sort-by str parent)
                         :let [k (get key p (:default key))]]
                     (str "`" k "` under " (parent-name p))))
    (str "`" key "`")))

(defn- attributes-table
  [{:keys [attrs]}]
  (when (seq attrs)
    (concat
     ["| Attribute | Key | Type | |"
      "|---|---|---|---|"]
     (for [[k {:keys [required] :as attr}] (sort-by (comp str key) attrs)]
       (str "| `" (name k) "` | `" (xml/attr-key k) "` | " (type-name attr)
            " | " (if required "required" "") " |")))))

(defn- element-section
  [{:keys [tag doc count values deprecated] :as entry}]
  (let [parents  (sort-by str (:parent entry))
        text     (spec/content-type entry)
        per      (fn [p]
                   (str (name (get count p (:default count)))
                        " under " (parent-name p)))
        replaced (when (keyword? deprecated)
                   (str ", replaced by `<" (xml/qname deprecated) ">`"))]
    (concat
     [(str "### `<" (xml/qname tag) ">`")
      ""]
     (when deprecated
       [(str "**Deprecated**" replaced ".")
        ""])
     (when doc [doc ""])
     [(str "- Key: " (keys-line entry))
      (str "- Parent: " (str/join ", " (map parent-name parents)))
      (str "- Count: " (if (map? count)
                         (str/join ", " (map per parents))
                         (name count)))
      (str "- Text: " (if (= :none text)
                        "none"
                        (type-name {:type text :values values})))
      ""]
     (attributes-table entry)
     (when (seq (:attrs entry)) [""]))))

(defn- vocabulary-section
  [{:keys [title namespaces about]}]
  (let [covered? #(contains? namespaces (namespace (:tag %)))
        entries  (filter covered? spec/elements)
        uri-line #(when % (str "`" % "`: " (get xml/namespaces (keyword %))))
        uris     (keep uri-line (sort (remove nil? namespaces)))]
    (when (seq entries)
      (concat
       [(str "## " title) "" about ""]
       (when (seq uris) (concat (map #(str "- " %) uris) [""]))
       (mapcat element-section entries)))))

(def value-types
  "How each value type of the registry is read and written."
  [["text" "the text of the element, trimmed"]
   ["html" "text that can contain HTML, kept as written"]
   ["url, email, mime, language" "a string, whose form the validator checks"]
   ["enum" (str "a string, which the validator compares with the listed "
                "values, ignoring case")]
   ["integer" "a long, written back as digits"]
   ["decimal" (str "a number, a long if it's whole, written back without an "
                   "exponent")]
   ["boolean" "`true` or `false` in the XML, a boolean in the model"]
   ["yes-no" "`yes` or `no` in the XML, a boolean in the model"]
   ["presence" (str "an empty element in the XML, `true` in the model when "
                    "the element is there")]
   ["rfc2822" (str "an RFC 2822 date, read leniently, an `#inst` in the "
                   "model, written in GMT")]
   ["iso8601" (str "an ISO 8601 date or date-time, an `#inst` in the model, "
                   "written in UTC")]
   ["duration" (str "seconds as a number, read from seconds or from clock "
                    "notation, written as seconds")]
   ["rrule" (str "an RFC 5545 recurrence rule, a map of its parts in the "
                 "model, written with the parts in the order of the RFC")]
   ["geo" (str "a geo URI, in the model a map of `:lat`, `:lon`, and "
               "optionally `:altitude` and `:uncertainty`")]
   ["osm" (str "an OpenStreetMap reference such as R113314#4, in the model a "
               "map of `:type`, `:id`, and optionally `:revision`")]])

(defn markdown
  "The whole reference as Markdown."
  []
  (str/join "\n"
            (concat
             ["# The feed model"
              ""
              (str "Generated from the tag registry in "
                   "`dk.simongray.podcast.feed.spec` by `dev/reference.clj`,")
              "so don't edit it by hand."
              ""
              (str "A feed is a map of the channel, with its items in a vector "
                   "under `:rss/items` and its live")
              (str "items under `:podcast/live-items`. The model follows a "
                   "few rules:")
              ""
              (str "- A key has the namespace of its vocabulary and a "
                   "kebab-case name, e.g. `:rss/pub-date`,")
              "  `:itunes/episode-type` or `:podcast/alternate-enclosures`."
              (str "- An element with only text becomes a value of its type, "
                   "as listed below.")
              (str "- An element with attributes or children becomes a map, "
                   "with its attributes under the keys")
              (str "  that this page gives, its text under `:content`, and "
                   "each child under its own key.")
              "- A repeatable element becomes a vector under a plural key."
              (str "- A value that doesn't read as its type stays a string, "
                   "and the report has")
              "  `:feed/invalid-value`."
              (str "- An element that this page doesn't list stays as Hiccup "
                   "under `:xml/unknown`, and `emit`")
              (str "  writes it back unchanged. An attribute that this page "
                   "doesn't list stays in the map of")
              "  its element as a string."
              (str "- Markup in an element of type html stays a string of "
                   "markup, and markup in other text")
              (str "  elements becomes the text of its descendants. In both "
                   "cases, the report has")
              "  `:feed/markup-in-text`."
              ""
              (str "Every element on this page is read into the model by "
                   "`parse`, written back by `emit` and")
              (str "checked by `validate`. The last two also accept a "
                   "shorthand, a bare value for an element")
              (str "with only text, such as `{:rss/guid \"x\"}`, and a single "
                   "value for a vector, which")
              (str "`feed/normalize` expands. The identity of a feed and of an "
                   "item comes from `feed/feed-guid`")
              "and `feed/item-guid`."
              ""
              "## Value types"
              ""
              "| Type | How podcast-clj reads and writes it |"
              "|---|---|"]
             (for [[t how] value-types] (str "| " t " | " how " |"))
             [""]
             (mapcat vocabulary-section vocabularies))))

(defn diagnostics-markdown
  "The diagnostics catalogue as Markdown."
  []
  (str/join "\n"
            (concat
             ["# Diagnostics"
              ""
              (str "Generated from `dk.simongray.podcast.diagnostics` by "
                   "`dev/reference.clj`, so don't edit it")
              "by hand."
              ""
              (str "A diagnostic is a map with an `:id`, a `:level` and a "
                   "`:message`, where the message is the")
              (str "meaning below unless the code gives a more specific one. "
                   "Other keys give details, such as")
              (str "a `:path` into the data or the `:tag` of an element. Use "
                   "`report` for the diagnostics of a")
              (str "parsed feed, transcript or chapters document, `validate` "
                   "for those of a feed, and")
              "`summarize` to group diagnostics by kind."
              ""
              (str "The namespace of an id says which part of podcast-clj "
                   "found the problem:")
              ""
              "- `fetch`: the fetch of a feed"
              "- `discovery`: finding the feeds of a show from an address"
              "- `repair`: the repairs of malformed XML"
              "- `xml`: namespace resolution"
              "- `quirk`: the normalisations of the tree"
              "- `feed`: the model"
              "- `transcript` and `chapters`: the companion formats"
              "- `id3`, `mp3`, `mp4` and `file`: the media files and the images"
              "- `validate`: the validator"
              "- `check`: the checks over the network"
              "- `sync`: the sync between devices"
              "- `podping`: a poll of the Hive chain"
              "- `comments`: the threads of comments"
              (str "- `value`: the records of payments, the remote items of "
                   "time splits, the Lightning")
              "  addresses and the wallets"
              "- `analytics`: the list of user agents, and the downloads of OP3"
              ""
              (str "A function that sends requests also returns its failures "
                   "as diagnostics, under the")
              "`:error` of its result."
              ""
              "| Id | Level | Meaning |"
              "|---|---|---|"]
             (for [{:keys [id level doc]} diagnostics/catalogue]
               (str "| `" id "` | " (name level) " | " doc " |"))
             [""])))

(defn write!
  "Write doc/feed-model.md and doc/diagnostics.md."
  []
  (spit (io/file "doc/feed-model.md") (markdown))
  (println "wrote doc/feed-model.md," (count spec/elements) "elements")
  (spit (io/file "doc/diagnostics.md") (diagnostics-markdown))
  (println "wrote doc/diagnostics.md," (count diagnostics/catalogue)
           "diagnostics"))

(comment
  (write!)
  #_.)
