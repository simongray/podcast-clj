(ns dk.simongray.podcast.feed.quirks
  "Named fixes for feeds whose tree bends the standards: the namespace URIs
  that feeds misspell, and the normalizations of the elements, applied
  after the parse and always reported.

  Every fix has an id, each use of one goes into a report, and the option
  :except leaves out those that it names. The repairs of a feed that
  doesn't parse are in dk.simongray.podcast.feed.repair."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.qname-hiccup :as xml]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-mime :as mime]))

(defn ^:no-doc uri-key
  "The namespace `uri` in the form to look it up by, without scheme, case
  or trailing slashes, since feeds get each of them wrong."
  [uri]
  (-> (str uri)
      str/lower-case
      (str/replace #"^https?://" "")
      url/without-trailing-slashes))

(def ^:no-doc uri-variants
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

;; RSS 2.0 has no namespace, but some hosts declare that of Atom as the
;; default on rss, which would put every element without a prefix in it
(defn strip-default-namespace
  "The raw Hiccup `tree` without the default namespace that its rss element
  declares, with the `opts` below, as a map of the :raw tree and the
  :report. The :except of `opts` leaves this out when it names
  :quirk/rss-default-namespace."
  ([tree]
   (strip-default-namespace tree {}))
  ([tree {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [uri (not-empty (get (xml/attrs tree) "xmlns"))]
     (if (and uri
              (= "rss" (xml/tag tree))
              (not (contains? except :quirk/rss-default-namespace)))
       {:raw    (update tree 1 dissoc "xmlns")
        :report [{:id :quirk/rss-default-namespace :level :info :uri uri}]}
       {:raw tree :report []}))))

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

(def ^:no-doc explicit-values
  "What feeds write in itunes:explicit, and what they mean."
  {"true" "true" "yes" "true" "explicit" "true"
   "false" "false" "no" "false" "clean" "false"})

(def ^:no-doc yes-no-values
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

(def ^:no-doc transcript-type-values
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
  "The podcast:transcript `element` with its type in standard spelling,
  and without parameters such as a charset, which the types of the podcast
  namespace don't have."
  [[tag attrs :as element]]
  (let [type (some-> (:type attrs) xml/trim str/lower-case)
        ;; a bare word such as srt is no MIME type, so only its parameters go
        bare (or (mime/essence type)
                 (some-> type (str/split #";" 2) first str/trim))
        t    (or (transcript-type-values bare)
                 (when (and (not= bare type)
                            (contains? spec/transcript-types bare))
                   bare))]
    (if t
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

(def ^:no-doc normalizations
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

(defn ^:no-doc registry-normalizations
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
  (normalize (xml/parse "<rss
    xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>
    <itunes:image>https://x/y.jpg</itunes:image>
    <itunes:explicit>Yes</itunes:explicit>
    <item><itunes:duration>1:02:03</itunes:duration><guid></guid></item>
    </channel></rss>"))
  (report *1)
  #_.)
