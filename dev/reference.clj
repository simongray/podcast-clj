(ns reference
  "Generate doc/tags.md, the reference of every element the library
  understands, from the tag registry, and doc/diagnostics.md from the
  diagnostics catalogue. Run `(write!)` after changing either and commit the
  result."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

(def vocabularies
  "The sections of the reference, in order: title, the tag namespaces they
  cover and a line about the source."
  [{:title "RSS 2.0" :namespaces #{nil}
    :about "The core elements, as https://www.rssboard.org/rss-specification defines them."}
   {:title "iTunes" :namespaces #{"itunes"}
    :about "The namespace of Apple, as https://podcasters.apple.com/support/823-podcast-requirements describes it."}
   {:title "Podcast namespace (Podcasting 2.0)" :namespaces #{"podcast"}
    :about "All formalized tags of the `podcast` namespace, as https://github.com/Podcastindex-org/podcast-namespace defines them."}
   {:title "Podlove Simple Chapters" :namespaces #{"psc"}
    :about "Chapters in the feed, as https://podlove.org/simple-chapters/ defines them."}
   {:title "Media RSS" :namespaces #{"media"}
    :about "The Media RSS module of Yahoo, as far as podcast hosts use it."}
   {:title "Google Play and Spotify" :namespaces #{"googleplay" "spotify"}
    :about "The namespaces of two directories, which some feeds still contain."}
   {:title "RawVoice" :namespaces #{"rawvoice"}
    :about "The namespace of Blubrry, as far as its feeds use it."}
   {:title "Atom, Dublin Core, content and syndication" :namespaces #{"atom" "dc" "content" "sy"}
    :about "Modules from the wider RSS world that podcast feeds contain."}
   {:title "Feed paging and archiving" :namespaces #{"fh"}
    :about "The elements of RFC 5005, for a feed that comes in more than one document. The links between the documents are `atom:link` elements."}])

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
       (str "| `" (name k) "` | `" (xml/attr-key k) "` | " (type-name attr) " | "
            (if required "required" "") " |")))))

(defn- element-section
  [{:keys [tag doc count values deprecated] :as entry}]
  (let [parents (sort-by str (:parent entry))
        text    (spec/content-type entry)]
    (concat
     [(str "### `<" (xml/qname tag) ">`")
      ""]
     (when deprecated
       [(str "**Deprecated**" (when (keyword? deprecated) (str ", replaced by `<" (xml/qname deprecated) ">`")) ".")
        ""])
     (when doc [doc ""])
     [(str "- Key: " (keys-line entry))
      (str "- Parent: " (str/join ", " (map parent-name parents)))
      (str "- Count: " (if (map? count)
                         (str/join ", " (for [p parents] (str (name (get count p (:default count))) " under " (parent-name p))))
                         (name count)))
      (str "- Text: " (if (= :none text) "none" (type-name {:type text :values values})))
      ""]
     (attributes-table entry)
     (when (seq (:attrs entry)) [""]))))

(defn- vocabulary-section
  [{:keys [title namespaces about]}]
  (let [entries (filter #(contains? namespaces (namespace (:tag %))) spec/tags)
        uris    (keep #(when % (str "`" % "`: " (get xml/namespaces (keyword %)))) (sort (remove nil? namespaces)))]
    (when (seq entries)
      (concat
       [(str "## " title) "" about ""]
       (when (seq uris) (concat (map #(str "- " %) uris) [""]))
       (mapcat element-section entries)))))

(def value-types
  "How each value type of the registry is read and written."
  [["text" "the text of the element, trimmed"]
   ["html" "text that can contain HTML, kept as written"]
   ["url, email, mime, language" "a string, which the validator checks for its form"]
   ["enum" "a string, which the validator compares with the listed values, ignoring case"]
   ["integer" "a long, written back as digits"]
   ["decimal" "a number, a long if it is whole, written back without an exponent"]
   ["boolean" "`true` or `false` in the XML, a boolean in the model"]
   ["yes-no" "`yes` or `no` in the XML, a boolean in the model"]
   ["presence" "an empty element in the XML, `true` in the model when the element is there"]
   ["rfc2822" "an RFC 2822 date, read leniently, an `#inst` in the model, written in GMT"]
   ["iso8601" "an ISO 8601 date or date-time, an `#inst` in the model, written in UTC"]
   ["duration" "seconds as a number, read from seconds or from clock notation, written as seconds"]
   ["rrule" "an RFC 5545 recurrence rule, a map of its parts in the model, written with the parts in the order of the RFC"]
   ["geo" "a geo URI, in the model a map of `:lat`, `:lon`, and optionally `:altitude` and `:uncertainty`"]
   ["osm" "an OpenStreetMap reference such as R113314#4, in the model a map of `:type`, `:id`, and optionally `:revision`"]])

(defn markdown
  "The whole reference as Markdown."
  []
  (str/join "\n"
            (concat
             ["# The feed model"
              ""
              "Generated from the tag registry in `dk.simongray.podcast.spec` by `dev/reference.clj`."
              "Do not edit this file by hand."
              ""
              "A feed is a map: the channel, with its items in a vector under `:rss/items` and its live"
              "items under `:podcast/live-items`. The model follows these rules:"
              ""
              "- A key has the namespace of its vocabulary and a kebab-case name: `:rss/pub-date`,"
              "  `:itunes/episode-type`, `:podcast/alternate-enclosures`."
              "- An element with only text becomes a value of its type. The value types are below."
              "- An element with attributes or children becomes a map. Its attributes are under the keys"
              "  that this page gives, its text is under `:content`, and each child is under its own key."
              "- A repeatable element becomes a vector under a plural key."
              "- A value that does not read as its type stays a string, and the report contains"
              "  `:feed/invalid-value`."
              "- An element that this page does not list stays as Hiccup under `:xml/unknown`, and `emit`"
              "  writes it back unchanged. An attribute that this page does not list stays in the map of"
              "  its element, as a string."
              "- Markup in an element of type html stays as a string of markup. Markup in another text"
              "  element becomes the text of its descendants. In both cases, the report contains"
              "  `:feed/markup-in-text`."
              ""
              "`parse` reads each element on this page into the model, `emit` writes it back, and"
              "`validate` checks it. `emit` and `validate` also accept a shorthand: a bare value for an"
              "element that has only text, such as `{:rss/guid \"x\"}`, and a single value for a vector."
              "`feed/normalize` expands the shorthand. `feed/feed-guid` and `feed/item-guid` give the"
              "identity of a feed and of an item."
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
              "Generated from `dk.simongray.podcast.diagnostics` by `dev/reference.clj`. Do not edit"
              "this file by hand."
              ""
              "A diagnostic is a map with an `:id`, a `:level` and a `:message`. The message is the"
              "meaning below, unless the code gives a more specific message. Other keys give details,"
              "such as a `:path` into the data or the `:tag` of an element. `report` reads the"
              "diagnostics of a parsed feed, transcript or chapters document. `validate` returns the"
              "diagnostics of a feed. `summarize` groups diagnostics by kind."
              ""
              "The namespace of an id tells which part of podcast-clj found the problem:"
              ""
              "- `fetch`: the fetch of a feed"
              "- `repair`: the repairs of malformed XML"
              "- `xml`: namespace resolution"
              "- `quirk`: the normalizations of the tree"
              "- `feed`: the model"
              "- `transcript` and `chapters`: the companion formats"
              "- `id3`, `mp3`, `mp4` and `file`: the media files and the images"
              "- `validate`: the validator"
              "- `check`: the checks over the network"
              "- `sync`: the sync between devices"
              "- `podping`: a poll of the Hive chain"
              "- `comments`: the threads of comments"
              "- `value`: the records of payments, the Lightning addresses and the wallets"
              "- `analytics`: the list of user agents, and the downloads of OP3"
              ""
              "A function that sends requests gives a failure as a diagnostic too, under the"
              "`:error` of its result."
              ""
              "| Id | Level | Meaning |"
              "|---|---|---|"]
             (for [{:keys [id level doc]} diagnostics/catalogue]
               (str "| `" id "` | " (name level) " | " doc " |"))
             [""])))

(defn write!
  "Write doc/tags.md and doc/diagnostics.md."
  []
  (spit (io/file "doc/tags.md") (markdown))
  (println "wrote doc/tags.md," (count spec/tags) "elements")
  (spit (io/file "doc/diagnostics.md") (diagnostics-markdown))
  (println "wrote doc/diagnostics.md," (count diagnostics/catalogue) "diagnostics"))

(comment
  (write!)
  #_.)
