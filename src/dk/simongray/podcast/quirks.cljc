(ns dk.simongray.podcast.quirks
  "The wild: named accommodations for feeds that bend the standards, applied
  around the strict path and recorded, never silently.

  There are three kinds:

  - `repair` rewrites XML *text* that a strict parser rejects: junk before
    the declaration, control characters, HTML entities XML lacks, bare
    ampersands, prefixes used without a declaration. It runs only when the
    strict parse has failed.
  - `uri->alias` recognizes the namespace URI variants feeds bind their
    prefixes to (`http` for `https`, casing, a trailing slash, an old URI).
  - `normalize` rewrites a canonical Hiccup *tree* into the form the
    specifications describe: an `itunes:image` written as text becomes an
    `href`, `yes`/`no` in `itunes:explicit` becomes `true`/`false`, misspelt
    transcript types are corrected, and dates and durations in non-canonical
    forms are pointed out.

  Every rule has an id, and every application lands in a report, so that a
  feed's distance from the standards is measurable."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.xml :as xml]))

;;;; Namespace URI variants

(defn uri-key
  "Normalize the namespace `uri` for lookup: no scheme, no case, no trailing
  slashes, since feeds get every one of them wrong."
  [uri]
  (-> (str uri)
      str/lower-case
      (str/replace #"^https?://" "")
      (str/replace #"/+$" "")))

(def uri-variants
  "Namespace URIs seen in the wild that differ from the canonical ones by
  more than scheme, case or a trailing slash, with the alias they mean."
  {"www.google.com/schemas/play-podcasts/1.0/play-podcasts.xsd" :googleplay
   "www.rssboard.org/media-rss"                                  :media
   "w3.org/2005/atom"                                            :atom
   "podlove.org/simple-chapters/1.0"                             :psc})

(def uri->alias
  "Look up a namespace alias leniently: canonical URIs, their spelling
  variants, and the known aliases in `uri-variants`."
  (let [known (into {} (for [[uri alias] xml/uri->alias]
                         [(uri-key uri) alias]))
        table (merge uri-variants known)]
    (fn [uri]
      (get table (uri-key uri)))))

(defn namespace-variants
  "Findings for every namespace declaration in the raw Hiccup `tree` whose
  URI is not the canonical one for its alias."
  [tree]
  (for [element (tree-seq vector? xml/elements tree)
        [prefix uri] (xml/declarations (xml/attrs element))
        :when prefix
        :let [alias (uri->alias uri)]
        :when (and alias (not= alias (xml/uri->alias uri)))]
    {:id :quirk/namespace-variant :level :info :prefix prefix :uri uri
     :canonical (xml/namespaces alias)}))

;;;; Repairing XML text

(def latin1-entities
  "The HTML entity names for code points 192 to 255, in order."
  ["Agrave" "Aacute" "Acirc" "Atilde" "Auml" "Aring" "AElig" "Ccedil" "Egrave"
   "Eacute" "Ecirc" "Euml" "Igrave" "Iacute" "Icirc" "Iuml" "ETH" "Ntilde"
   "Ograve" "Oacute" "Ocirc" "Otilde" "Ouml" "times" "Oslash" "Ugrave" "Uacute"
   "Ucirc" "Uuml" "Yacute" "THORN" "szlig" "agrave" "aacute" "acirc" "atilde"
   "auml" "aring" "aelig" "ccedil" "egrave" "eacute" "ecirc" "euml" "igrave"
   "iacute" "icirc" "iuml" "eth" "ntilde" "ograve" "oacute" "ocirc" "otilde"
   "ouml" "divide" "oslash" "ugrave" "uacute" "ucirc" "uuml" "yacute" "thorn"
   "yuml"])

(def html-entities
  "Code point by name for the HTML entities that XML lacks and feeds use."
  (merge (zipmap latin1-entities (range 192 256))
         {"nbsp" 160 "iexcl" 161 "cent" 162 "pound" 163 "curren" 164 "yen" 165
          "brvbar" 166 "sect" 167 "uml" 168 "copy" 169 "ordf" 170 "laquo" 171
          "not" 172 "shy" 173 "reg" 174 "macr" 175 "deg" 176 "plusmn" 177
          "sup2" 178 "sup3" 179 "acute" 180 "micro" 181 "para" 182 "middot" 183
          "cedil" 184 "sup1" 185 "ordm" 186 "raquo" 187 "frac14" 188 "frac12" 189
          "frac34" 190 "iquest" 191 "OElig" 338 "oelig" 339 "Scaron" 352
          "scaron" 353 "Yuml" 376 "fnof" 402 "circ" 710 "tilde" 732 "ensp" 8194
          "emsp" 8195 "thinsp" 8201 "zwnj" 8204 "zwj" 8205 "lrm" 8206 "rlm" 8207
          "ndash" 8211 "mdash" 8212 "lsquo" 8216 "rsquo" 8217 "sbquo" 8218
          "ldquo" 8220 "rdquo" 8221 "bdquo" 8222 "dagger" 8224 "Dagger" 8225
          "bull" 8226 "hellip" 8230 "permil" 8240 "prime" 8242 "Prime" 8243
          "lsaquo" 8249 "rsaquo" 8250 "euro" 8364 "trade" 8482 "larr" 8592
          "uarr" 8593 "rarr" 8594 "darr" 8595 "harr" 8596 "infin" 8734 "ne" 8800
          "le" 8804 "ge" 8805 "hearts" 9829}))

(def xml-entities
  "The five entities XML predefines."
  #{"amp" "lt" "gt" "quot" "apos"})

(defn- code-point->str
  [n]
  #?(:clj  (String. (Character/toChars n))
     :cljs (js/String.fromCodePoint n)))

(defn decode-entities
  "Replace the character and entity references in `s` with the characters
  they stand for, HTML entities included; unknown references are kept."
  [s]
  (str/replace s #"&(#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);"
               (fn [[whole ref]]
                 (cond
                   (re-find #"^#[xX]" ref)
                   (code-point->str (#?(:clj Long/parseLong :cljs js/parseInt) (subs ref 2) 16))

                   (str/starts-with? ref "#")
                   (code-point->str (parse-long (subs ref 1)))

                   (xml-entities ref)
                   ({"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'"} ref)

                   (html-entities ref)
                   (code-point->str (html-entities ref))

                   :else
                   whole))))

(defn map-outside-cdata
  "Apply `f` to every stretch of `s` that lies outside a CDATA section."
  [f s]
  (loop [from 0
         acc  []]
    (if-let [start (str/index-of s "<![CDATA[" from)]
      (let [end (or (some-> (str/index-of s "]]>" start) (+ 3)) (count s))]
        (recur end (conj acc (f (subs s from start)) (subs s start end))))
      (apply str (conj acc (f (subs s from)))))))

(defn strip-leading-junk
  "Remove anything before the first `<` of `s`: byte order marks, blank lines
  and stray output from the server."
  [s]
  (str/replace s #"^[^<]+" ""))

(defn strip-control-characters
  "Remove the control characters XML 1.0 forbids from `s`."
  [s]
  (str/replace s #"[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]" ""))

(defn replace-html-entities
  "Replace the HTML entities in `s` outside CDATA with numeric references,
  which XML understands; unknown named entities become literal text."
  [s]
  (map-outside-cdata
   #(str/replace % #"&([A-Za-z][A-Za-z0-9]*);"
                 (fn [[whole name]]
                   (cond
                     (xml-entities name)  whole
                     (html-entities name) (str "&#" (html-entities name) ";")
                     :else                (str "&amp;" name ";"))))
   s))

(defn escape-bare-ampersands
  "Escape every `&` in `s` outside CDATA that does not start a reference."
  [s]
  (map-outside-cdata
   #(str/replace % #"&(?!(?:#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);)" "&amp;")
   s))

(defn undeclared-prefixes
  "The namespace prefixes that tags and attributes in `s` use without any
  `xmlns:` declaration."
  [s]
  (let [used     (into #{} (comp (map rest) (mapcat identity) (remove nil?))
                       (re-seq #"<([A-Za-z][\w.-]*):|\s([A-Za-z][\w.-]*):[\w.-]+=" s))
        declared (into #{"xml" "xmlns"} (map second) (re-seq #"xmlns:([\w.-]+)=" s))]
    (remove declared used)))

(defn declare-known-prefixes
  "Declare on the root element of `s` every undeclared prefix that names one
  of the namespaces in `xml/namespaces`."
  [s]
  (let [missing (filter #(contains? xml/namespaces (keyword %)) (undeclared-prefixes s))]
    (if (empty? missing)
      s
      (str/replace-first s #"<([A-Za-z][\w.-]*)"
                         (fn [[_ root]]
                           (apply str "<" root
                                  (for [p missing]
                                    (str " xmlns:" p "=\"" (xml/namespaces (keyword p)) "\""))))))))

(def repairs
  "The text repairs, in the order they are applied."
  [{:id :repair/leading-junk        :f strip-leading-junk}
   {:id :repair/control-characters  :f strip-control-characters}
   {:id :repair/undeclared-prefixes :f declare-known-prefixes}
   {:id :repair/html-entities       :f replace-html-entities}
   {:id :repair/bare-ampersands     :f escape-bare-ampersands}])

(defn repair
  "Apply every repair in `repairs` to the XML text `s`. Returns `{:xml s
  :report [...]}` with a finding for each repair that changed something."
  [s]
  (reduce (fn [{:keys [xml] :as acc} {:keys [id f]}]
            (let [xml' (f xml)]
              (cond-> (assoc acc :xml xml')
                (not= xml xml') (update :report conj {:id id :level :warning}))))
          {:xml s :report []}
          repairs))

;;;; Normalizing the tree

(defn- rewrite
  "A rule that replaces every element tagged `tag` by `(f element)`."
  [id tag f]
  {:id id :kind :rewrite :tag tag :f f})

(defn- check
  "A rule that reports `(f element)` for every element tagged `tag`, when it
  returns anything."
  [id tag f]
  {:id id :kind :check :tag tag :f f})

(defn- text-element
  "The element `element` with its text content replaced by `s`."
  [element s]
  [(xml/tag element) (xml/attrs element) s])

(def explicit-values
  "What feeds write in itunes:explicit, and what they mean."
  {"true" "true" "yes" "true" "explicit" "true"
   "false" "false" "no" "false" "clean" "false"})

(def transcript-types
  "Non-standard spellings of transcript types, and the standard ones."
  {"srt"              "application/x-subrip"
   "application/srt"  "application/x-subrip"
   "text/srt"         "application/x-subrip"
   "vtt"              "text/vtt"
   "text/webvtt"      "text/vtt"
   "json"             "application/json"
   "html"             "text/html"
   "txt"              "text/plain"
   "text"             "text/plain"
   "text/text"        "text/plain"})

(defn- srcset->images
  "The podcast:image elements equivalent to the deprecated `images` element."
  [images]
  (for [candidate (str/split (str (:srcset (xml/attrs images))) #",")
        :let [[url width] (str/split (str/trim candidate) #"\s+")]
        :when (seq url)]
    [:podcast/image (cond-> {:href url}
                      (and width (re-matches #"\d+w" width))
                      (assoc :width (subs width 0 (dec (count width)))))]))

(defn- add-images
  "Add podcast:image children to `parent` for its deprecated podcast:images
  element, unless it already has some."
  [parent]
  (if (or (xml/element parent :podcast/image)
          (not (xml/element parent :podcast/images)))
    parent
    (into parent (mapcat srcset->images (xml/elements parent :podcast/images)))))

(def rules
  "The tree rules, applied to every element in document order, children
  before parents."
  [(rewrite :quirk/itunes-image-text :itunes/image
            (fn [[tag attrs :as element]]
              (let [href (or (:href attrs) (:url attrs) (not-empty (str/trim (xml/text element))))]
                (if href
                  [tag (-> attrs (dissoc :url) (assoc :href href))]
                  element))))
   (rewrite :quirk/explicit-value :itunes/explicit
            (fn [element]
              (let [v (str/lower-case (str/trim (xml/text element)))]
                (if-let [canonical (explicit-values v)]
                  (if (= canonical v) element (text-element element canonical))
                  element))))
   (rewrite :quirk/transcript-type :podcast/transcript
            (fn [[tag attrs :as element]]
              (if-let [t (transcript-types (some-> (:type attrs) str/trim str/lower-case))]
                [tag (assoc attrs :type t)]
                element)))
   (rewrite :quirk/enclosure-length :enclosure
            (fn [[tag attrs :as element]]
              (if (and (contains? attrs :length)
                       (not (re-matches #"\s*\d+\s*" (str (:length attrs)))))
                [tag (dissoc attrs :length)]
                element)))
   (rewrite :quirk/empty-guid :guid
            (fn [element]
              (if (str/blank? (xml/text element))
                nil
                element)))
   (rewrite :quirk/deprecated-images :channel add-images)
   (rewrite :quirk/deprecated-images :item add-images)
   (check :quirk/duration-format :itunes/duration
          (fn [element]
            (let [s (str/trim (xml/text element))]
              (when (and (time/parse-duration s) (not (re-matches #"\d+" s)))
                {:value s}))))
   (check :quirk/date-format :pubDate
          (fn [element]
            (let [s (str/trim (xml/text element))]
              (when (and (time/parse-date s) (not (time/rfc2822-strict? s)))
                {:value s}))))
   (check :quirk/date-format :lastBuildDate
          (fn [element]
            (let [s (str/trim (xml/text element))]
              (when (and (time/parse-date s) (not (time/rfc2822-strict? s)))
                {:value s}))))])

(def rules-by-tag
  (group-by :tag rules))

(defn- apply-rules
  "Apply the rules for the tag of `element`, recording what they did in the
  `report` accumulator; nil when a rule removed the element."
  [report element]
  (reduce (fn [element {:keys [id kind f]}]
            (if (nil? element)
              (reduced nil)
              (case kind
                :rewrite (let [element' (f element)]
                           (when (not= element' element)
                             (vswap! report conj {:id id :level :info :tag (xml/tag element)}))
                           element')
                :check   (do (when-let [finding (f element)]
                               (vswap! report conj (merge {:id id :level :info :tag (xml/tag element)}
                                                          finding)))
                             element))))
          element
          (rules-by-tag (xml/tag element))))

(defn- walk
  [report element]
  (->> (xml/children element)
       (keep #(if (string? %) % (walk report %)))
       (into [(xml/tag element) (xml/attrs element)])
       (apply-rules report)))

(defn normalize
  "Rewrite the canonical Hiccup `tree` by `rules`, keeping its metadata and
  adding the findings under `:quirks/report`."
  [tree]
  (let [report (volatile! [])
        tree'  (walk report tree)]
    (with-meta tree' (assoc (meta tree) :quirks/report @report))))

(defn report
  "The findings `normalize` recorded on `tree`."
  [tree]
  (:quirks/report (meta tree)))

(comment
  (repair "﻿<rss><channel><title>A & B &nbsp;<itunes:author>x</itunes:author></title></channel></rss>")
  (normalize (xml/parse "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>
                         <itunes:image>https://x/y.jpg</itunes:image><itunes:explicit>Yes</itunes:explicit>
                         <item><itunes:duration>1:02:03</itunes:duration><guid></guid></item></channel></rss>"))
  (report *1)
  #_.)
