(ns dk.simongray.podcast.quirks
  "The wild: named accommodations for feeds that bend the standards, applied
  around the strict path and recorded, never silently.

  `repair` rewrites XML text a strict parser rejects (see `repairs`) and
  runs only when the strict parse has failed; `uri->alias` recognizes the
  namespace URI variants feeds bind their prefixes to; `normalize` rewrites
  a canonical Hiccup tree into the form the specifications describe, by
  `rules` and the rules a registry implies. Every rule has an id, every
  application lands in a report, and `:except` leaves the rules it names
  out."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.xml :as xml]))

(defn ^:no-doc uri-key
  "Normalize the namespace `uri` for lookup: no scheme, no case, no trailing
  slashes, since feeds get every one of them wrong."
  [uri]
  (-> (str uri)
      str/lower-case
      (str/replace #"^https?://" "")
      ;; a match may start only where a run of slashes starts: /+$ alone is
      ;; tried again from every slash of a long run, quadratically
      (str/replace #"(^|[^/])/+$" "$1")))

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
  "Diagnostics for every namespace declaration in the raw Hiccup `tree` whose
  URI is not the canonical one for its alias, found in one walk that looks
  at the xmlns attributes alone."
  [tree]
  (letfn [(walk [acc element]
            (let [acc (reduce-kv (fn [acc k uri]
                                   (if (str/starts-with? k "xmlns:")
                                     (let [alias (uri->alias uri)]
                                       (if (and alias (not= alias (xml/uri->alias uri)))
                                         (conj acc {:id :quirk/namespace-variant :level :info :prefix (subs k 6)
                                                    :uri uri :canonical (xml/namespaces alias)})
                                         acc))
                                     acc))
                                 acc
                                 (xml/attrs element))
                  n   (count element)]
              (loop [i 2 acc acc]
                (if (< i n)
                  (let [child (nth element i)]
                    (recur (inc i) (if (vector? child) (walk acc child) acc)))
                  acc))))]
    (walk [] tree)))

(defn ^:no-doc map-outside-cdata
  "Apply `f` to every stretch of `s` that lies outside a CDATA section."
  [f s]
  (loop [from 0
         acc  []]
    (if-let [start (str/index-of s "<![CDATA[" from)]
      (let [end (or (some-> (str/index-of s "]]>" start) (+ 3)) (count s))]
        (recur end (conj acc (f (subs s from start)) (subs s start end))))
      (apply str (conj acc (f (subs s from)))))))

(defn strip-leading-junk
  "Remove anything before the XML declaration or the `<rss>` start tag of
  `s`, whichever comes first, or before its first `<` when it has neither:
  byte order marks, blank lines and stray output from the server, such as a
  PHP warning."
  [s]
  (if-let [start (some->> (re-find #"<\?xml|<rss[\s>/]" s) (str/index-of s))]
    (subs s start)
    (str/replace s #"^[^<]+" "")))

(defn strip-trailing-junk
  "Remove anything but white space after the `</rss>` end tag of `s`: stray
  output from the server, such as a PHP warning."
  [s]
  (let [end (some->> (str/last-index-of s "</rss") (str/index-of s ">") inc)]
    (if (and end (not= "" (xml/trim (subs s end))))
      (subs s 0 end)
      s)))

(defn ^:no-doc legal-code-point?
  "Whether XML 1.0 allows the character with the code point `n`."
  [n]
  (or (= n 9) (= n 10) (= n 13) (<= 0x20 n 0xD7FF) (<= 0xE000 n 0xFFFD) (<= 0x10000 n 0x10FFFF)))

(defn- illegal-reference?
  "Whether `s` may hold a character reference to a character XML 1.0
  forbids, judged at each `&#` alone. One in CDATA, which is no reference,
  counts too; it only sends `strip-control-characters` to look closer."
  [s]
  (let [n (count s)]
    (loop [from 0]
      (if-let [at (str/index-of s "&#" from)]
        (let [[_ ref] (re-find #"^&(#\d+|#[xX][0-9a-fA-F]+);" (subs s at (min n (+ at 32))))]
          (if (and ref (not (some-> (xml/code-point ref) legal-code-point?)))
            true
            (recur (+ at 2))))
        false))))

(defn strip-control-characters
  "Remove the control characters XML 1.0 forbids from `s`, and outside CDATA
  the character references to them, which XML forbids as well. A loop over
  the characters and a look at each `&#` come first, so that only a
  document with something to remove pays for the patterns."
  [s]
  (let [s (if (xml/legal? s) s (str/replace s xml/illegal-characters ""))]
    (if (illegal-reference? s)
      (map-outside-cdata
       #(str/replace % #"&(#\d+|#[xX][0-9a-fA-F]+);"
                     (fn [[whole ref]]
                       (if (some-> (xml/code-point ref) legal-code-point?) whole "")))
       s)
      s)))

(defn replace-html-entities
  "Replace the HTML entities in `s` outside CDATA with numeric references,
  which XML understands; unknown named entities become literal text."
  [s]
  (map-outside-cdata
   #(str/replace % #"&([A-Za-z][A-Za-z0-9]*);"
                 (fn [[whole name]]
                   (cond
                     (xml/xml-entities name)  whole
                     (xml/html-entities name) (str "&#" (xml/html-entities name) ";")
                     :else                    (str "&amp;" name ";"))))
   s))

(defn escape-bare-ampersands
  "Escape every `&` in `s` outside CDATA that does not start a reference."
  [s]
  (map-outside-cdata
   #(str/replace % #"&(?!(?:#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);)" "&amp;")
   s))

(defn- prefix-before?
  "Whether the colon at `i` of `s` ends a name that follows a `<` or white
  space, as a namespace prefix does; the colon of `https:` in a quoted value
  follows a quote."
  [^String s ^long i]
  (loop [j (dec i)]
    (if (neg? j)
      false
      (let [c #?(:clj (int (.charAt s j)) :cljs (.charCodeAt s j))]
        (if (or (and (<= 97 c) (<= c 122)) (and (<= 65 c) (<= c 90)) (and (<= 48 c) (<= c 57))
                (== c 95) (== c 46) (== c 45))
          (recur (dec j))
          (and (< j (dec i)) (or (== c 60) (== c 32) (== c 9) (== c 10) (== c 13))))))))

(defn- prefixed-tags
  "The start tags of `s` that name a namespace prefix, cut as
  `<[A-Za-z][^<>]*` cuts them, found in one pass over the characters: the
  text is most of a feed, and few of its tags name a prefix."
  [^String s]
  ;; primitive locals throughout, -1 for no tag, and comparisons of two
  ;; arguments, which Clojure compiles inline, since this runs for every
  ;; character of the feed
  (let [n (count s)]
    (loop [i 0 start -1 prefixed? false acc (transient [])]
      (if (< i n)
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (cond
            (== c 60) (let [letter? (and (< (inc i) n)
                                         (let [d #?(:clj (int (.charAt s (inc i))) :cljs (.charCodeAt s (inc i)))]
                                           (or (and (<= 65 d) (<= d 90)) (and (<= 97 d) (<= d 122)))))]
                        (recur (inc i) (if letter? i -1) false (if prefixed? (conj! acc (subs s start i)) acc)))
            (== c 62) (recur (inc i) -1 false (if prefixed? (conj! acc (subs s start i)) acc))
            (and (== c 58) (>= start 0) (not prefixed?))
            (recur (inc i) start (prefix-before? s i) acc)
            :else     (recur (inc i) start prefixed? acc)))
        (persistent! (if prefixed? (conj! acc (subs s start n)) acc))))))

(defn undeclared-prefixes
  "The namespace prefixes that tags and attributes in `s` use without any
  `xmlns:` declaration, looked for inside the start tags alone."
  [s]
  (let [tags     (prefixed-tags s)
        used     (into #{} (comp (mapcat #(re-seq #"^<([A-Za-z][\w.-]*):|[ \t\r\n]([A-Za-z][\w.-]*):[\w.-]+=" %))
                                 (mapcat rest)
                                 (remove nil?))
                       tags)
        declared (into #{"xml" "xmlns"} (comp (mapcat #(re-seq #"[ \t\r\n]xmlns:([\w.-]+)=" %)) (map second)) tags)]
    (remove declared used)))

(defn declare-known-prefixes
  "Declare on the root element of `s` every undeclared prefix that names one
  of the namespaces in `xml/namespaces`."
  [s]
  (let [missing (filter #(contains? xml/namespaces (keyword %)) (undeclared-prefixes s))
        open    (re-find #"<[A-Za-z][\w.-]*" s)
        at      (some->> open (str/index-of s))]
    (if (or (empty? missing) (nil? at))
      s
      (let [end (+ at (count open))]
        (str (subs s 0 end)
             (apply str (for [p missing]
                          (str " xmlns:" p "=\"" (xml/namespaces (keyword p)) "\"")))
             (subs s end))))))

(def repairs
  "The text repairs, in the order they are applied."
  [{:id :repair/leading-junk        :f strip-leading-junk}
   {:id :repair/trailing-junk       :f strip-trailing-junk}
   {:id :repair/control-characters  :f strip-control-characters}
   {:id :repair/undeclared-prefixes :f declare-known-prefixes}
   {:id :repair/html-entities       :f replace-html-entities}
   {:id :repair/bare-ampersands     :f escape-bare-ampersands}])

(defn repair
  "Apply every repair in `repairs` to the XML text `s`, except those whose
  ids `:except` in `opts` names. Returns `{:xml s :report [...]}` with a
  diagnostic for each repair that changed something."
  ([s]
   (repair s {}))
  ([s {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (reduce (fn [{:keys [xml] :as acc} {:keys [id f]}]
             (let [xml' (f xml)]
               (cond-> (assoc acc :xml xml')
                 (not= xml xml') (update :report conj {:id id :level :warning}))))
           {:xml s :report []}
           (remove (comp except :id) repairs))))

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

(def yes-no-values
  "What feeds write in yes/no elements such as podcast:locked, and what
  they mean."
  {"yes" "yes" "true" "yes" "no" "no" "false" "no"})

(defn- canonical-text
  "A rewrite of an element's text by the `values` table, case ignored."
  [values]
  (fn [element]
    (let [v (str/lower-case (xml/trim (xml/text element)))]
      (if-let [canonical (values v)]
        (if (= canonical v) element (text-element element canonical))
        element))))

(defn- href->url
  "The `element` with an href attribute renamed to the url the specification
  asks for, when it has no url of its own."
  [[tag attrs :as element]]
  (if (and (contains? attrs :href) (not (contains? attrs :url)))
    (into [tag (-> attrs (dissoc :href) (assoc :url (:href attrs)))] (xml/children element))
    element))

(defn- item-image->itunes
  "The `item` with an attribute-only image element, which RSS 2.0 does not
  have, replaced by the itunes:image it stands for, unless the item has one."
  [item]
  (let [image (first (filter #(and (= :image (xml/tag %))
                                   (:url (xml/attrs %))
                                   (empty? (xml/children %)))
                             (xml/elements item)))]
    (if (or (nil? image) (xml/element item :itunes/image))
      item
      (into [(xml/tag item) (xml/attrs item)]
            (map #(if (identical? % image) [:itunes/image {:href (:url (xml/attrs image))}] %)
                 (xml/children item))))))

(defn- podcast-category->itunes
  "The `channel` with the podcast:category elements Wavlake writes on its
  music feeds, which the specification does not define but which mirror
  itunes:category in every way, read as itunes:category when the channel
  has none of the standard ones."
  [channel]
  (letfn [(category? [x] (and (vector? x) (= :podcast/category (xml/tag x))))
          (convert [[_ attrs & children]]
            (into [:itunes/category attrs] (map #(if (category? %) (convert %) %) children)))]
    (if (or (xml/element channel :itunes/category)
            (not (xml/element channel :podcast/category)))
      channel
      (into [(xml/tag channel) (xml/attrs channel)]
            (map #(if (category? %) (convert %) %) (xml/children channel))))))

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

(defn- srcset-candidates
  "The `[url descriptor]` pairs of the srcset `s`, read as a browser reads
  them: a URL runs to white space, and only commas at its end end it, so
  that a URL with commas in it survives; a descriptor runs to the next
  comma, and nil stands for none."
  [^String s]
  (let [n      (count s)
        blank? #(contains? #{\space \tab \newline \return \formfeed} (.charAt s %))
        comma? #(= \, (.charAt s %))
        skip   (fn [i pred] (loop [i i] (if (and (< i n) (pred i)) (recur (inc i)) i)))]
    (loop [i 0 acc []]
      (let [start (skip i #(or (blank? %) (comma? %)))]
        (if (>= start n)
          acc
          (let [end     (skip start (complement blank?))
                url-end (loop [j end] (if (comma? (dec j)) (recur (dec j)) j))]
            (if (< url-end end)
              (recur end (conj acc [(subs s start url-end) nil]))
              (let [stop (or (str/index-of s "," end) n)]
                (recur (inc stop) (conj acc [(subs s start end) (not-empty (str/trim (subs s end stop)))]))))))))))

(defn- srcset->images
  "The podcast:image elements equivalent to the deprecated `images` element."
  [images]
  (for [[url descriptor] (srcset-candidates (str (:srcset (xml/attrs images))))]
    [:podcast/image (cond-> {:href url}
                      (and descriptor (re-matches #"\d+w" descriptor))
                      (assoc :width (subs descriptor 0 (dec (count descriptor)))))]))

(defn- add-images
  "Add podcast:image children to `parent` for its deprecated podcast:images
  element, unless it already has some."
  [parent]
  (if (or (xml/element parent :podcast/image)
          (not (xml/element parent :podcast/images)))
    parent
    (into parent (mapcat srcset->images (xml/elements parent :podcast/images)))))

(defn- non-canonical-date
  "`{:value s}` when the text of `element` is a date the library reads but
  not in the strict RFC 2822 form; nil otherwise."
  [element]
  (let [s (xml/trim (xml/text element))]
    (when (and (time/parse-date s) (not (time/rfc2822-strict? s)))
      {:value s})))

(def rules
  "The tree rules that hold whatever the registry, applied to every element
  in document order, children before parents; `registry-rules` adds the
  ones a registry implies."
  [(rewrite :quirk/itunes-image-text :itunes/image
            (fn [[tag attrs :as element]]
              (let [href (or (:href attrs) (:url attrs) (not-empty (xml/trim (xml/text element))))]
                (if href
                  [tag (-> attrs (dissoc :url) (assoc :href href))]
                  element))))
   (rewrite :quirk/explicit-value :itunes/explicit (canonical-text explicit-values))
   (rewrite :quirk/transcript-type :podcast/transcript
            (fn [[tag attrs :as element]]
              (if-let [t (transcript-types (some-> (:type attrs) xml/trim str/lower-case))]
                [tag (assoc attrs :type t)]
                element)))
   (rewrite :quirk/attribute-name :podcast/transcript href->url)
   (rewrite :quirk/attribute-name :podcast/chapters href->url)
   (rewrite :quirk/enclosure-length :enclosure
            (fn [[tag attrs :as element]]
              (if (and (contains? attrs :length)
                       (not (re-matches #"\s*\d+\s*" (str (:length attrs)))))
                [tag (dissoc attrs :length)]
                element)))
   (rewrite :quirk/empty-guid :guid
            (fn [element]
              (if (= "" (xml/trim (xml/text element)))
                nil
                element)))
   (rewrite :quirk/deprecated-images :channel add-images)
   (rewrite :quirk/deprecated-images :item add-images)
   (rewrite :quirk/podcast-category :channel podcast-category->itunes)
   (rewrite :quirk/item-image :item item-image->itunes)
   (check :quirk/duration-format :itunes/duration
          (fn [element]
            (let [s (xml/trim (xml/text element))]
              (when (and (time/parse-duration s) (not (re-matches #"\d+" s)))
                {:value s}))))
   (check :quirk/date-format :pubDate non-canonical-date)
   (check :quirk/date-format :lastBuildDate non-canonical-date)])

(defn registry-rules
  "The rules the tag `registry` implies: yes and no written as true and
  false in every element it types as yes-no."
  [registry]
  (for [{:keys [tag content]} (:entries registry)
        :when (= :yes-no content)]
    (rewrite :quirk/yes-no-value tag (canonical-text yes-no-values))))

(defn- apply-rules
  "Apply the rules of `by-tag` for the tag of `element`, recording what they
  did in the `report` accumulator; nil when a rule removed the element."
  [by-tag report element]
  (reduce (fn [element {:keys [id kind f]}]
            (if (nil? element)
              (reduced nil)
              (case kind
                :rewrite (let [element' (f element)]
                           (when (not= element' element)
                             (vswap! report conj {:id id :level :info :tag (xml/tag element)}))
                           element')
                :check   (do (when-let [diagnostic (f element)]
                               (vswap! report conj (merge {:id id :level :info :tag (xml/tag element)}
                                                          diagnostic)))
                             element))))
          element
          (by-tag (xml/tag element))))

(defn- walk
  "`element` with the rules of `by-tag` applied to it and below it, children
  before parents, recording in `report` what they did; the element itself
  when nothing below it changed, so that no untouched subtree is copied."
  [by-tag report element]
  ;; a loop rather than a lazy keep, which costs the stack several frames
  ;; more for every level of nesting
  (let [n       (count element)
        rebuilt (loop [i 2 acc nil]
                  (if (< i n)
                    (let [child  (nth element i)
                          child' (if (string? child) child (walk by-tag report child))
                          acc    (cond
                                   acc                       acc
                                   (identical? child child') nil
                                   :else                     (reduce conj! (transient [(xml/tag element) (xml/attrs element)])
                                                                     (subvec element 2 i)))]
                      (recur (inc i) (if (and acc (some? child')) (conj! acc child') acc)))
                    acc))]
    (apply-rules by-tag report (if rebuilt (persistent! rebuilt) element))))

(defn normalize
  "Rewrite the canonical Hiccup `tree` by `rules` and by the `registry-rules`
  of the tag registry in `opts` (`:registry`, default `spec/default`),
  except the rules whose ids `:except` names, keeping its metadata and
  adding the diagnostics under `:quirks/report`."
  ([tree]
   (normalize tree {}))
  ([tree {:keys [registry except] :or {registry spec/default except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [by-tag (->> (into rules (registry-rules (spec/registry registry)))
                     (remove (comp except :id))
                     (group-by :tag))
         report (volatile! [])
         ;; a rule may remove any element but the root
         tree'  (or (walk by-tag report tree) tree)]
     (with-meta tree' (assoc (meta tree) :quirks/report @report)))))

(defn report
  "The diagnostics `normalize` recorded on `tree`, each with its message from
  the diagnostics catalogue."
  [tree]
  (mapv diagnostics/describe (:quirks/report (meta tree))))

(comment
  (repair "﻿<rss><channel><title>A & B &nbsp;<itunes:author>x</itunes:author></title></channel></rss>")
  (normalize (xml/parse "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>
                         <itunes:image>https://x/y.jpg</itunes:image><itunes:explicit>Yes</itunes:explicit>
                         <item><itunes:duration>1:02:03</itunes:duration><guid></guid></item></channel></rss>"))
  (report *1)
  #_.)
