(ns dk.simongray.podcast.feed.quirks
  "Named fixes for feeds that bend the standards, applied around the strict
  parse and always reported.

  Every repair and normalization has an id, each use of one goes into a
  report, and the option :except leaves out those that it names."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.entities :as entities]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.qname-hiccup :as xml]))

(defn ^:no-doc uri-key
  "The namespace `uri` in the form to look it up by, without scheme, case
  or trailing slashes, since feeds get each of them wrong."
  [uri]
  (-> (str uri)
      str/lower-case
      (str/replace #"^https?://" "")
      ;; a match may start only where a run of slashes starts: /+$ alone is
      ;; tried again from every slash of a long run, quadratically
      (str/replace #"(^|[^/])/+$" "$1")))

(def uri-variants
  "The namespace URIs in feeds that differ from the canonical ones by more
  than scheme, case or a trailing slash, with the alias that they mean."
  {"www.google.com/schemas/play-podcasts/1.0/play-podcasts.xsd" :googleplay
   "www.rssboard.org/media-rss"                                  :media
   "w3.org/2005/atom"                                            :atom
   "podlove.org/simple-chapters/1.0"                             :psc})

(def uri->alias
  "A function that gives the namespace alias of a URI, leniently: of a
  canonical URI, its spellings, and the known variants."
  (let [known (into {} (for [[uri alias] spec/uri->alias]
                         [(uri-key uri) alias]))
        table (merge uri-variants known)]
    (fn [uri]
      (get table (uri-key uri)))))

(defn- namespace-variant
  "The diagnostic of the attribute `k` with the value `uri`, when it
  declares a namespace by a URI that isn't the canonical one of its
  alias."
  [k uri]
  (when (str/starts-with? k "xmlns:")
    (let [alias (uri->alias uri)]
      (when (and alias
                 (not= alias (spec/uri->alias uri)))
        {:id        :quirk/namespace-variant
         :level     :info
         :prefix    (subs k 6)
         :uri       uri
         :canonical (spec/namespaces alias)}))))

(defn namespace-variants
  "The diagnostics of every namespace declaration in the raw Hiccup `tree`
  whose URI isn't the canonical one of its alias. One walk finds them,
  looking at the xmlns attributes alone."
  [tree]
  (letfn [(walk [acc element]
            (let [acc (reduce-kv (fn [acc k uri]
                                   (if-let [d (namespace-variant k uri)]
                                     (conj acc d)
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
  "The string `s` with `f` applied to every stretch outside a CDATA
  section."
  [f s]
  (loop [from 0
         acc  []]
    (if-let [start (str/index-of s "<![CDATA[" from)]
      (let [end (or (some-> (str/index-of s "]]>" start) (+ 3)) (count s))]
        (recur end (conj acc (f (subs s from start)) (subs s start end))))
      (apply str (conj acc (f (subs s from)))))))

(defn strip-leading-junk
  "The string `s` without anything before the XML declaration or the rss
  start tag, whichever comes first, or else before its first tag.

  That's byte order marks, blank lines and stray output of the server,
  such as a PHP warning."
  [s]
  (if-let [start (some->> (re-find #"<\?xml|<rss[\s>/]" s) (str/index-of s))]
    (subs s start)
    (str/replace s #"^[^<]+" "")))

(defn strip-trailing-junk
  "The string `s` without anything but whitespace after the rss end tag,
  which is stray output of the server, such as a PHP warning."
  [s]
  (let [end (some->> (str/last-index-of s "</rss") (str/index-of s ">") inc)]
    (if (and end
             (not= "" (xml/trim (subs s end))))
      (subs s 0 end)
      s)))

(defn ^:no-doc legal-code-point?
  "Whether XML 1.0, section 2.2, allows the character with the code point
  `n`."
  [n]
  (or (= n 9)
      (= n 10)
      (= n 13)
      (<= 0x20 n 0xD7FF)
      (<= 0xE000 n 0xFFFD)
      (<= 0x10000 n 0x10FFFF)))

(defn- illegal-reference?
  "Whether `s` can hold a character reference to a character that XML 1.0
  forbids, judged at each &# alone. One in CDATA, which isn't a reference,
  counts too, and only costs a closer look."
  [s]
  (let [n (count s)]
    (loop [from 0]
      (if-let [at (str/index-of s "&#" from)]
        (let [window  (subs s at (min n (+ at 32)))
              [_ ref] (re-find #"^&(#\d+|#[xX][0-9a-fA-F]+);" window)]
          (if (and ref
                   (not (some-> (entities/code-point ref) legal-code-point?)))
            true
            (recur (+ at 2))))
        false))))

(defn strip-control-characters
  "The string `s` without the control characters that XML 1.0 forbids, and
  without the character references to them outside CDATA, which XML
  forbids too."
  [s]
  ;; a loop over the characters and a look at each &# come first, so that
  ;; only a document with something to remove pays for the patterns
  (let [s (if (xml/legal? s)
            s
            (str/replace s xml/illegal-characters ""))]
    (if (illegal-reference? s)
      (map-outside-cdata
       #(str/replace % #"&(#\d+|#[xX][0-9a-fA-F]+);"
                     (fn [[whole ref]]
                       (if (some-> (entities/code-point ref) legal-code-point?)
                         whole
                         "")))
       s)
      s)))

(defn replace-html-entities
  "The string `s` with its HTML entities outside CDATA replaced by numeric
  references, which XML understands. Unknown named entities become literal
  text."
  [s]
  (map-outside-cdata
   #(str/replace % #"&([A-Za-z][A-Za-z0-9]*);"
                 (fn [[whole name]]
                   (let [chars (entities/html-entity name)]
                     (cond
                       (entities/xml-entities name)
                       whole

                       chars
                       (entities/character-references chars)

                       :else
                       (str "&amp;" name ";")))))
   s))

(defn escape-bare-ampersands
  "The string `s` with every ampersand outside CDATA that doesn't start a
  reference escaped."
  [s]
  (map-outside-cdata
   #(str/replace %
                 #"&(?!(?:#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);)"
                 "&amp;")
   s))

(defn- prefix-before?
  "Whether the colon at `i` of `s` ends a name that follows the start of a
  tag or whitespace, as a namespace prefix does. The colon of https: in a
  quoted value follows a quote."
  [^String s ^long i]
  (loop [j (dec i)]
    (if (neg? j)
      false
      (let [c #?(:clj (int (.charAt s j)) :cljs (.charCodeAt s j))]
        (if (or (and (<= 97 c) (<= c 122))
                (and (<= 65 c) (<= c 90))
                (and (<= 48 c) (<= c 57))
                (== c 95)
                (== c 46)
                (== c 45))
          (recur (dec j))
          (and (< j (dec i))
               (or (== c 60)
                   (== c 32)
                   (== c 9)
                   (== c 10)
                   (== c 13))))))))

;; One pass over the characters finds them, since the text is most of a
;; feed and few of its tags name a prefix
(defn- prefixed-tags
  "The start tags of `s` that name a namespace prefix."
  [^String s]
  ;; primitive locals throughout, -1 for no tag, and comparisons of two
  ;; arguments, which Clojure compiles inline, since this runs for every
  ;; character of the feed
  (let [n (count s)]
    (loop [i 0 start -1 prefixed? false acc (transient [])]
      (if (< i n)
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (cond
            (== c 60)
            (let [j       (inc i)
                  letter? (and (< j n)
                               (let [d #?(:clj  (int (.charAt s j))
                                          :cljs (.charCodeAt s j))]
                                 (or (and (<= 65 d) (<= d 90))
                                     (and (<= 97 d) (<= d 122)))))
                  acc     (if prefixed? (conj! acc (subs s start i)) acc)]
              (recur j (if letter? i -1) false acc))

            (== c 62)
            (let [acc (if prefixed? (conj! acc (subs s start i)) acc)]
              (recur (inc i) -1 false acc))

            (and (== c 58)
                 (>= start 0)
                 (not prefixed?))
            (recur (inc i) start (prefix-before? s i) acc)

            :else
            (recur (inc i) start prefixed? acc)))
        (persistent! (if prefixed? (conj! acc (subs s start n)) acc))))))

(defn undeclared-prefixes
  "The namespace prefixes that the tags and attributes in `s` use without
  an xmlns: declaration, looked for in the start tags alone."
  [s]
  (let [tags     (prefixed-tags s)
        ;; a prefix of the tag name, or of an attribute name
        prefixed #"^<([A-Za-z][\w.-]*):|[ \t\r\n]([A-Za-z][\w.-]*):[\w.-]+="
        xmlns    #"[ \t\r\n]xmlns:([\w.-]+)="
        used     (into #{}
                       (comp (mapcat #(re-seq prefixed %))
                             (mapcat rest)
                             (remove nil?))
                       tags)
        declared (into #{"xml" "xmlns"}
                       (comp (mapcat #(re-seq xmlns %))
                             (map second))
                       tags)]
    (remove declared used)))

(defn declare-known-prefixes
  "The string `s` with every undeclared prefix that names one of the known
  namespaces declared on its root element."
  [s]
  (let [uri     #(spec/namespaces (keyword %))
        missing (filter uri (undeclared-prefixes s))
        open    (re-find #"<[A-Za-z][\w.-]*" s)
        at      (some->> open (str/index-of s))]
    (if (or (empty? missing) (nil? at))
      s
      (let [end   (+ at (count open))
            xmlns (for [p missing]
                    (str " xmlns:" p "=\"" (uri p) "\""))]
        (str (subs s 0 end)
             (apply str xmlns)
             (subs s end))))))

(def repairs
  "The text repairs, in the order that they're applied."
  [{:id :repair/leading-junk        :f strip-leading-junk}
   {:id :repair/trailing-junk       :f strip-trailing-junk}
   {:id :repair/control-characters  :f strip-control-characters}
   {:id :repair/undeclared-prefixes :f declare-known-prefixes}
   {:id :repair/html-entities       :f replace-html-entities}
   {:id :repair/bare-ampersands     :f escape-bare-ampersands}])

(defn repair
  "Apply every repair to the XML text `s`, except those whose ids the
  :except of `opts` names.

  It returns a map of the :xml and a :report, with a diagnostic for each
  repair that changed something."
  ([s]
   (repair s {}))
  ([s {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (reduce (fn [{:keys [xml] :as acc} {:keys [id f]}]
             (let [xml' (f xml)]
               (cond-> (assoc acc :xml xml')
                 (not= xml xml')
                 (update :report conj {:id id :level :warning}))))
           {:xml s :report []}
           (remove (comp except :id) repairs))))

(def ^:no-doc lenient-parser
  "The parser to fall back on when a document is still malformed after the
  repairs, or nil.

  On the JVM, it's that of jsoup when org.jsoup/jsoup is on the
  classpath, so adding jsoup to the dependencies is all it takes.
  ClojureScript has none."
  (delay
   #?(:clj  (try
              (requiring-resolve 'dk.simongray.qname-hiccup.jsoup/parse-raw)
              (catch Exception _ nil))
      :cljs nil)))

(defn- held
  "The `xml` in a form that can be read twice, first strictly and then for
  the repairs. On the JVM, an InputStream becomes bytes, and a Reader a
  string."
  [xml]
  #?(:clj  (cond
             (instance? java.io.InputStream xml)
             (.readAllBytes ^java.io.InputStream xml)

             (instance? java.io.Reader xml)
             (slurp xml)

             :else
             xml)
     :cljs xml))

(defn- strict
  "Parse the held `xml` strictly into a map with the :raw tree, or the
  :error when it's malformed. Bytes go to the parser as a stream, so that
  it honours the document's own encoding."
  [xml]
  (let [source #?(:clj  (if (bytes? xml)
                          (java.io.ByteArrayInputStream. ^bytes xml)
                          xml)
                  :cljs xml)]
    (try
      {:raw (xml/parse-raw source)}
      (catch #?(:clj Exception :cljs :default) e
        (if (= ::xml/malformed (:type (ex-data e)))
          {:error e}
          (throw e))))))

(defn- repaired
  "Repair the malformed held `xml` with `opts`, and parse it again into a
  map of the :raw tree and its :report.

  The parse is strict when the repairs were enough, and lenient when
  there's a lenient parser and :except in `opts` doesn't name
  :repair/lenient-parser. Otherwise, it throws the error of the strict
  parser."
  [xml {:keys [except] :as opts}]
  (let [text            #?(:clj  (if (bytes? xml) (xml/decode xml) xml)
                           :cljs xml)
        repaired        (repair text opts)
        report          (:report repaired)
        parsed          (strict (:xml repaired))
        parse-leniently (when-not (contains? except :repair/lenient-parser)
                          @lenient-parser)]
    (cond
      (:raw parsed)
      {:raw (:raw parsed) :report report}

      parse-leniently
      {:raw    (parse-leniently (:xml repaired))
       :report (conj report {:id :repair/lenient-parser :level :warning})}

      :else
      (throw (:error parsed)))))

(defn parse-raw
  "Parse the XML document `xml` into its raw tree, and repair it first
  when it's malformed, except by the repairs that the :except of `opts`
  names. It returns a map of the :raw tree and the :report of the repairs.

  The `xml` is a string or, on the JVM, an InputStream or a Reader. The
  strict parse comes first, so a well-formed document has an empty
  report. When the repairs aren't enough, the lenient parser reads the
  document if there is one, and else the error of the strict parse is
  thrown."
  ([xml]
   (parse-raw xml {}))
  ([xml opts]
   {:pre [(map? opts)]}
   (let [xml           (held xml)
         {:keys [raw]} (strict xml)]
     (if raw
       {:raw raw :report []}
       (repaired xml opts)))))

(defn- rewrite
  "The normalization `id` that replaces every element tagged `tag` by what
  `f` gives for it."
  [id tag f]
  {:id id :kind :rewrite :tag tag :f f})

(defn- check
  "The normalization `id` that only reports what `f` gives for every
  element tagged `tag`, when it gives anything."
  [id tag f]
  {:id id :kind :check :tag tag :f f})

(defn- text-element
  "The `element` with its text content replaced by `s`."
  [element s]
  [(xml/tag element) (xml/attrs element) s])

(def explicit-values
  "What feeds write in itunes:explicit, and what they mean."
  {"true" "true" "yes" "true" "explicit" "true"
   "false" "false" "no" "false" "clean" "false"})

(def yes-no-values
  "What feeds write in yes-no elements such as podcast:locked, and what
  they mean."
  {"yes" "yes" "true" "yes" "no" "no" "false" "no"})

(defn- canonical-text
  "A rewrite of the text of an element by the `values` table, ignoring
  case."
  [values]
  (fn [element]
    (let [v (str/lower-case (xml/trim (xml/text element)))]
      (if-let [canonical (values v)]
        (if (= canonical v) element (text-element element canonical))
        element))))

(defn- href->url
  "The `element` with an href attribute renamed to the url that the podcast
  namespace asks for, when it has no url of its own."
  [[tag attrs :as element]]
  (if (and (contains? attrs :href)
           (not (contains? attrs :url)))
    (let [attrs' (-> attrs (dissoc :href) (assoc :url (:href attrs)))]
      (into [tag attrs'] (xml/children element)))
    element))

(defn- item-image->itunes
  "The `item` with an image element of attributes alone, which RSS 2.0
  doesn't have, replaced by the itunes:image that it stands for, unless
  the item has one."
  [item]
  (let [image (first (filter #(and (= :image (xml/tag %))
                                   (:url (xml/attrs %))
                                   (empty? (xml/children %)))
                             (xml/elements item)))]
    (if (or (nil? image)
            (xml/element item :itunes/image))
      item
      (let [itunes [:itunes/image {:href (:url (xml/attrs image))}]]
        (into [(xml/tag item) (xml/attrs item)]
              (map #(if (identical? % image) itunes %))
              (xml/children item))))))

(defn- podcast-category->itunes
  "The `channel` with the podcast:category elements of Wavlake's music
  feeds read as itunes:category, when the channel has none of those.

  The podcast namespace doesn't define podcast:category, but it mirrors
  itunes:category in every way."
  [channel]
  (letfn [(category? [x]
            (and (vector? x)
                 (= :podcast/category (xml/tag x))))
          (convert [x]
            (if (category? x)
              (let [[_ attrs & children] x]
                (into [:itunes/category attrs] (map convert children)))
              x))]
    (if (or (xml/element channel :itunes/category)
            (not (xml/element channel :podcast/category)))
      channel
      (into [(xml/tag channel) (xml/attrs channel)]
            (map convert (xml/children channel))))))

(def transcript-type-values
  "The nonstandard spellings of transcript types, and the standard ones."
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

;; HTML Standard, "Parsing a srcset attribute"
(defn- srcset-candidates
  "The pairs of URL and descriptor of the srcset `s`, read as a browser
  reads them.

  A URL runs to whitespace, and only commas at its end end it, so that a
  URL with commas in it survives. A descriptor runs to the next comma, and
  nil stands for none."
  [^String s]
  (let [n      (count s)
        spaces #{\space \tab \newline \return \formfeed}
        blank? #(contains? spaces (.charAt s %))
        comma? #(= \, (.charAt s %))
        skip   (fn [i pred]
                 (loop [i i]
                   (if (and (< i n) (pred i))
                     (recur (inc i))
                     i)))]
    (loop [i 0 acc []]
      (let [start (skip i #(or (blank? %) (comma? %)))]
        (if (>= start n)
          acc
          (let [end     (skip start (complement blank?))
                url-end (loop [j end]
                          (if (comma? (dec j))
                            (recur (dec j))
                            j))]
            (if (< url-end end)
              (recur end (conj acc [(subs s start url-end) nil]))
              (let [stop       (or (str/index-of s "," end) n)
                    descriptor (not-empty (str/trim (subs s end stop)))
                    candidate  [(subs s start end) descriptor]]
                (recur (inc stop) (conj acc candidate))))))))))

(defn- srcset->images
  "The podcast:image elements that the deprecated `images` element stands
  for."
  [images]
  (for [[url descriptor] (srcset-candidates (str (:srcset (xml/attrs images))))
        :let [width (some->> descriptor (re-matches #"(\d+)w") second)]]
    [:podcast/image (cond-> {:href url}
                      width (assoc :width width))]))

(defn- add-images
  "The `parent` with podcast:image children for its deprecated
  podcast:images element, unless it has some already."
  [parent]
  (if (or (xml/element parent :podcast/image)
          (not (xml/element parent :podcast/images)))
    parent
    (into parent (mapcat srcset->images
                         (xml/elements parent :podcast/images)))))

(defn- non-canonical-date
  "A map with the text of `element` as the :value, or nil. It's there for a
  date that the library reads but that isn't in the strict form of RFC
  2822."
  [element]
  (let [s (xml/trim (xml/text element))]
    (when (and (time/parse-date s)
               (not (time/rfc2822-strict? s)))
      {:value s})))

(defn- non-canonical-duration
  "A map with the text of `element` as the :value, or nil. It's there for a
  duration that the library reads but that isn't a number of seconds."
  [element]
  (let [s (xml/trim (xml/text element))]
    (when (and (time/parse-duration s)
               (not (re-matches #"\d+" s)))
      {:value s})))

(defn- itunes-image-href
  "The itunes:image `element` with its URL in href, where feeds also write
  it as url or as the text."
  [[tag attrs :as element]]
  (let [href (or (:href attrs)
                 (:url attrs)
                 (not-empty (xml/trim (xml/text element))))]
    (if href
      [tag (-> attrs (dissoc :url) (assoc :href href))]
      element)))

(defn- canonical-transcript-type
  "The podcast:transcript `element` with its type in standard spelling."
  [[tag attrs :as element]]
  (let [type (some-> (:type attrs) xml/trim str/lower-case)]
    (if-let [t (transcript-type-values type)]
      [tag (assoc attrs :type t)]
      element)))

(defn- without-bad-length
  "The enclosure `element` without a length that isn't a whole number."
  [[tag attrs :as element]]
  (if (and (contains? attrs :length)
           (not (re-matches #"\s*\d+\s*" (str (:length attrs)))))
    [tag (dissoc attrs :length)]
    element))

(defn- non-empty-guid
  "The guid `element`, or nil when it has no text."
  [element]
  (if (= "" (xml/trim (xml/text element)))
    nil
    element))

(def normalizations
  "The normalizations of the tree that hold whatever the registry, applied
  to every element in document order, children before parents."
  [(rewrite :quirk/itunes-image-text :itunes/image itunes-image-href)
   (rewrite :quirk/explicit-value :itunes/explicit
            (canonical-text explicit-values))
   (rewrite :quirk/transcript-type :podcast/transcript
            canonical-transcript-type)
   (rewrite :quirk/attribute-name :podcast/transcript href->url)
   (rewrite :quirk/attribute-name :podcast/chapters href->url)
   (rewrite :quirk/enclosure-length :enclosure without-bad-length)
   (rewrite :quirk/empty-guid :guid non-empty-guid)
   (rewrite :quirk/deprecated-images :channel add-images)
   (rewrite :quirk/deprecated-images :item add-images)
   (rewrite :quirk/podcast-category :channel podcast-category->itunes)
   (rewrite :quirk/item-image :item item-image->itunes)
   (check :quirk/duration-format :itunes/duration non-canonical-duration)
   (check :quirk/date-format :pubDate non-canonical-date)
   (check :quirk/date-format :lastBuildDate non-canonical-date)])

(defn registry-normalizations
  "The normalizations that the tag `registry` implies: true and false
  rewritten as yes and no in every element that it types as yes-no."
  [registry]
  (for [{:keys [tag content]} (:entries registry)
        :when (= :yes-no content)]
    (rewrite :quirk/yes-no-value tag (canonical-text yes-no-values))))

(defn- apply-normalization
  "The `element` with the normalization of `id`, `kind` and `f` applied,
  and what it did added to the `report`."
  [report element {:keys [id kind f]}]
  (let [noted {:id id :level :info :tag (xml/tag element)}]
    (case kind
      :rewrite (let [element' (f element)]
                 (when (not= element' element)
                   (vswap! report conj noted))
                 element')
      :check   (do (when-let [diagnostic (f element)]
                     (vswap! report conj (merge noted diagnostic)))
                   element))))

(defn- apply-normalizations
  "The `element` with the normalizations of `by-tag` for its tag applied,
  and what they did added to the `report`, or nil when one removed the
  element."
  [by-tag report element]
  (reduce (fn [element normalization]
            (if (nil? element)
              (reduced nil)
              (apply-normalization report element normalization)))
          element
          (by-tag (xml/tag element))))

(defn- transient-head
  "A transient copy of `element` with its children before index `i`."
  [element i]
  (reduce conj!
          (transient [(xml/tag element) (xml/attrs element)])
          (subvec element 2 i)))

(defn- walk
  "The `element` with the normalizations of `by-tag` applied to it and
  below it, children before parents, and what they did added to the
  `report`. It's the element itself when nothing below it changed."
  [by-tag report element]
  ;; a loop rather than a lazy keep, which costs the stack several frames
  ;; more for every level of nesting
  (let [n       (count element)
        rebuilt (loop [i 2 acc nil]
                  (if (< i n)
                    (let [child  (nth element i)
                          child' (if (string? child)
                                   child
                                   (walk by-tag report child))
                          ;; a copy once a child changed, so that no
                          ;; untouched subtree is copied
                          acc    (or acc
                                     (when-not (identical? child child')
                                       (transient-head element i)))]
                      (recur (inc i) (if (and acc (some? child'))
                                       (conj! acc child')
                                       acc)))
                    acc))]
    (apply-normalizations by-tag report (if rebuilt
                                          (persistent! rebuilt)
                                          element))))

(defn normalize
  "Rewrite the canonical Hiccup `tree` by the normalizations, and by those
  that the :registry of `opts` implies, spec/default by default.

  The normalizations whose ids the :except of `opts` names are left out.
  The tree keeps its metadata, with the diagnostics added under
  :quirks/report."
  ([tree]
   (normalize tree {}))
  ([tree {:keys [registry except]
          :or   {registry spec/default except #{}}
          :as   opts}]
   {:pre [(map? opts)]}
   (let [registry (spec/registry registry)
         all      (into normalizations (registry-normalizations registry))
         by-tag   (->> all
                       (remove (comp except :id))
                       (group-by :tag))
         report   (volatile! [])
         ;; a normalization may remove any element but the root
         tree'    (or (walk by-tag report tree) tree)]
     (with-meta tree' (assoc (meta tree) :quirks/report @report)))))

(defn report
  "The diagnostics that the normalizations recorded on `tree`, each with
  its message from the catalogue of diagnostics."
  [tree]
  (mapv diagnostics/describe (:quirks/report (meta tree))))

(comment
  (repair (str "﻿<rss><channel><title>A & B &nbsp;"
               "<itunes:author>x</itunes:author></title></channel></rss>"))
  (parse-raw "<rss><channel><title>A & B</title></channel></rss>")
  (normalize (xml/parse "<rss
    xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>
    <itunes:image>https://x/y.jpg</itunes:image>
    <itunes:explicit>Yes</itunes:explicit>
    <item><itunes:duration>1:02:03</itunes:duration><guid></guid></item>
    </channel></rss>"))
  (report *1)
  #_.)
