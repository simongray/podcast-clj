(ns reference
  "Generate doc/tags.md, the reference of every element the library
  understands, from the tag registry. Run `(write!)` after changing the
  registry and commit the result."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

(def vocabularies
  "The sections of the reference, in order: title, the tag namespaces they
  cover and a line about the source."
  [{:title "RSS 2.0" :namespaces #{nil}
    :about "The core elements, as specified at https://www.rssboard.org/rss-specification."}
   {:title "iTunes" :namespaces #{"itunes"}
    :about "Apple's namespace, https://podcasters.apple.com/support/823-podcast-requirements."}
   {:title "Podcast namespace (Podcasting 2.0)" :namespaces #{"podcast"}
    :about "The `podcast` namespace, https://github.com/Podcastindex-org/podcast-namespace; every formalized tag."}
   {:title "Podlove Simple Chapters" :namespaces #{"psc"}
    :about "Chapters embedded in the feed, https://podlove.org/simple-chapters/."}
   {:title "Media RSS" :namespaces #{"media"}
    :about "Yahoo's Media RSS module, as far as podcast hosts use it."}
   {:title "Google Play and Spotify" :namespaces #{"googleplay" "spotify"}
    :about "Directory-specific namespaces still found in feeds."}
   {:title "Atom, Dublin Core, content and syndication" :namespaces #{"atom" "dc" "content" "sy"}
    :about "Modules from the wider RSS world that podcast feeds carry."}])

(defn- xml-name
  [tag]
  (if-let [ns (namespace tag)]
    (str ns ":" (name tag))
    (name tag)))

(defn- parent-name
  [parent]
  (case parent
    :channel "channel"
    :item    "item (and liveItem)"
    (str "<" (xml-name parent) ">")))

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
       (str "| `" (name k) "` | `" (spec/attr-key k) "` | " (type-name attr) " | "
            (if required "required" "") " |")))))

(defn- element-section
  [{:keys [tag doc count content values deprecated] :as entry}]
  (let [parents (sort-by str (:parent entry))
        text    (spec/content-type entry)]
    (concat
     [(str "### `<" (xml-name tag) ">`")
      ""]
     (when deprecated
       [(str "**Deprecated**" (when (keyword? deprecated) (str ", replaced by `<" (xml-name deprecated) ">`")) ".")
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
  [["text" "the element text, trimmed"]
   ["html" "text that may contain HTML, kept as written"]
   ["url, email, mime, language, token" "strings; the validator checks their shape"]
   ["enum" "a string; the validator checks it against the listed values, ignoring case"]
   ["integer" "a long; written back as digits"]
   ["decimal" "a number, a long when whole; written back without an exponent"]
   ["boolean" "`true`/`false` in the XML, a boolean in the model"]
   ["yes-no" "`yes`/`no` in the XML, a boolean in the model"]
   ["rfc2822" "an RFC 2822 date, read leniently, an `#inst` in the model; written as GMT"]
   ["iso8601" "an ISO 8601 date or date-time, an `#inst` in the model; written in UTC"]
   ["duration" "seconds as a number, read from plain seconds or clock notation; written as plain seconds"]
   ["rrule" "an RFC 5545 recurrence rule, a map of its parts in the model; written with the parts in the RFC's order"]
   ["geo" "a geo URI, a map of `:lat`, `:lon` and perhaps `:altitude` and `:uncertainty` in the model"]
   ["osm" "an OpenStreetMap reference such as R113314#4, a map of `:type`, `:id` and perhaps `:revision`"]])

(defn markdown
  "The whole reference as Markdown."
  []
  (str/join "\n"
            (concat
             ["# Elements the library understands"
              ""
              "Generated from the tag registry in `dk.simongray.podcast.spec` by `dev/reference.clj`;"
              "do not edit by hand. Every element listed here is read into the feed model, written"
              "back by `emit`, and checked by `validate`. Elements not listed are kept as Hiccup under"
              "`:xml/unknown` and survive a round trip unchanged."
              ""
              "Keys are namespaced by vocabulary and kebab-cased; an element with attributes or children"
              "becomes a map with its attributes under the keys shown, its text under `:content` and its"
              "children under their own keys; a repeatable element becomes a vector under a plural key."
              ""
              "## Value types"
              ""
              "| Type | Reading and writing |"
              "|---|---|"]
             (for [[t how] value-types] (str "| " t " | " how " |"))
             [""]
             (mapcat vocabulary-section vocabularies))))

(defn write!
  "Write doc/tags.md."
  []
  (spit (io/file "doc/tags.md") (markdown))
  (println "wrote doc/tags.md," (count spec/tags) "elements"))

(comment
  (write!)
  #_.)
