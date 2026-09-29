(ns dk.simongray.podcast.feed
  "The feed model: canonical Hiccup in, maps out, and back again, by
  interpreting a tag registry, `dk.simongray.podcast.feed.spec/default` unless
  the options name another.

  A feed is the channel map itself, with items under `:rss/items` and live
  items under `:podcast/live-items`. Scalar elements become coerced scalars,
  elements with attributes or children become maps, repeatable elements
  become vectors, and elements the registry does not know are kept as Hiccup
  under `:xml/unknown`. Nothing throws on bad values: they stay strings and
  the report says so. The report is metadata on the result, under
  `:podcast/report`, and `report` reads it."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.xml :as xml]))

;; The parse threads a context `ctx`: the `:registry` being interpreted, the
;; `:report` accumulator and the `:keys` of the attributes seen, volatiles.
;; The emit threads one too: the `:registry` and the `:plans` of the
;; entries met.

(defn- note!
  "Record the diagnostic `m` in the report of `ctx`."
  [ctx m]
  (vswap! (:report ctx) conj m))

(defn- model-key
  "The model key of the attribute `k`, as `xml/attr-key` gives it, kept in
  `ctx` for the rest of the parse, since a feed names the same attributes
  on every item."
  [ctx k]
  (let [keys (:keys ctx)]
    (or (get @keys k)
        (let [k' (xml/attr-key k)]
          (vswap! keys assoc k k')
          k'))))

(defn- parse-attrs
  "Coerce the attributes `attrs` of an element by its registry `entry`.
  Declared attributes get kebab-case keys and typed values; an optional one
  left blank is absent, with a diagnostic, since it says nothing, while a
  required one left blank stays for the validator to judge. The rest keep
  their XML names and string values."
  [ctx entry attrs path]
  (reduce-kv (fn [m k v]
               (if-let [{:keys [type required]} (get (:attrs entry) k)]
                 (let [k' (model-key ctx k)
                       v' (coerce/parse (or type :text) v)]
                   (cond
                     (and (= "" (xml/trim v)) (not required))
                     (do (note! ctx {:id :feed/empty-attribute :level :info :path (conj path k')})
                         m)

                     (coerce/invalid? v')
                     (do (note! ctx {:id :feed/invalid-value :level :warning
                                     :path (conj path k') :type type :value v})
                         (assoc m k' v))

                     :else
                     (assoc m k' v')))
                 (assoc m k v)))
             {}
             attrs))

(defn- parse-content
  "Coerce the node text `s` by the content type of `entry`, or keep it as a
  string with a diagnostic when it does not read as that type."
  [ctx entry s path]
  (let [type (spec/content-type entry)
        v    (coerce/parse type s)]
    (if (coerce/invalid? v)
      (do (note! ctx {:id :feed/invalid-value :level :warning
                      :path path :type type :value s})
          s)
      v)))

(declare parse-children)

(defn- folds-markup?
  "Whether elements found inside an element of `entry` are folded into its
  text: when the registry expects text there and nothing else, as with a
  description embedded as XHTML instead of CDATA."
  [ctx entry element]
  (and (seq (xml/elements element))
       (not (spec/container? (:registry ctx) entry))
       (not= :none (spec/content-type entry))))

(defn- element-text
  "The text of `element` under `entry`: its direct text, or when markup sits
  where the registry expects text alone, the markup itself for `:html`
  content and every descendant's text otherwise, with a diagnostic."
  [ctx entry element path]
  (if (folds-markup? ctx entry element)
    (do (note! ctx {:id :feed/markup-in-text :level :info :path path
                    :tags (mapv xml/tag (xml/elements element))})
        (if (= :html (spec/content-type entry))
          (xml/inner-xml element)
          (xml/deep-text element)))
    (xml/text element)))

(defn- parse-element
  "Parse the Hiccup `element` by its registry `entry` into a model value, or
  `::empty` for a scalar element with no text."
  [ctx entry element path]
  (let [text (element-text ctx entry element path)
        ;; the spaces kept between two elements are no text of their own
        text (when-not (= "" (xml/trim text)) text)]
    (cond
      (spec/map-shaped? (:registry ctx) entry)
      (let [m (cond-> (parse-attrs ctx entry (xml/attrs element) path)
                (not (folds-markup? ctx entry element))
                (merge (parse-children ctx (spec/children-context entry)
                                       (xml/elements element) path)))]
        (cond
          (= :none (spec/content-type entry))
          (do (when (seq text)
                (note! ctx {:id :feed/unexpected-text :level :info
                            :path path :value text}))
              m)

          (seq text)
          (assoc m :content (parse-content ctx entry text path))

          :else
          m))

      :else
      (do (when-let [attrs (not-empty (xml/attrs element))]
            (note! ctx {:id :feed/dropped-attributes :level :info :path path
                        :attributes (vec (keys attrs))}))
          (cond
            (= :presence (spec/content-type entry))
            (do (when (seq text)
                  (note! ctx {:id :feed/unexpected-text :level :info
                              :path path :value text}))
                true)

            (seq text)
            (parse-content ctx entry text path)

            :else
            (do (note! ctx {:id :feed/empty-element :level :info :path path})
                ::empty))))))

(defn- parse-children
  "Parse the child `elements` of an element in the `parent` context into a
  map keyed by the registry; unknown elements collect under `:xml/unknown`."
  [ctx parent elements path]
  (reduce (fn [m element]
            (let [tag (xml/tag element)]
              (if-let [entry (spec/entry (:registry ctx) tag parent)]
                (let [k     (spec/key-for entry parent)
                      many? (= :many (spec/count-for entry parent))
                      path' (cond-> (conj path k) many? (conj (count (get m k))))
                      v     (parse-element ctx entry element path')]
                  (cond
                    (= v ::empty)
                    m

                    many?
                    (update m k (fnil conj []) v)

                    (contains? m k)
                    (do (note! ctx {:id :feed/duplicate-element :level :warning
                                    :path path' :tag tag})
                        m)

                    :else
                    (assoc m k v)))
                (update m :xml/unknown (fnil conj []) element))))
          {}
          elements))

(defn parse
  "Parse the canonical Hiccup `tree` of an RSS 2.0 document into a feed map
  by the tag registry in `opts` (`:registry`, default `spec/default`). The
  diagnostics made on the way travel as metadata under `:podcast/report`,
  and the foreign namespaces the document used under `:xml/namespaces`;
  what resolving the namespaces found stays under `:xml/report` on the
  tree. The attributes of rss other than its version, and those of channel,
  have no place in the model; the report says when there are any."
  ([tree]
   (parse tree {}))
  ([tree {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (when-not (= :rss (xml/tag tree))
     (throw (ex-info "Not an RSS 2.0 document" {:type ::unsupported :root (xml/tag tree)})))
   (let [ctx     {:registry (spec/registry registry) :report (volatile! []) :keys (volatile! {})}
         channel (or (xml/element tree :channel)
                     (throw (ex-info "No channel element" {:type ::unsupported})))
         version (:version (xml/attrs tree))
         feed    (parse-children ctx :channel (xml/elements channel) [])
         ;; by identity: a set of the channel would hash all of the feed
         others  (remove #(identical? % channel) (xml/elements tree))]
     (doseq [[tag attrs] [[:rss (dissoc (xml/attrs tree) :version)] [:channel (xml/attrs channel)]]
             :when (seq attrs)]
       (note! ctx {:id :feed/dropped-attributes :level :info :path [] :tag tag
                   :attributes (vec (keys attrs))}))
     (when-not (= "" (xml/trim (xml/text channel)))
       (note! ctx {:id :feed/unexpected-text :level :info :path [] :value (xml/text channel)}))
     (with-meta (cond-> feed
                  version      (assoc :rss/version version)
                  (seq others) (update :xml/unknown (fnil into []) others))
       {:podcast/report @(:report ctx)
        :xml/namespaces (xml/foreign-namespaces tree)}))))

(defn report
  "The diagnostics recorded while parsing `feed`, each with its message from
  the diagnostics catalogue."
  [feed]
  (mapv diagnostics/describe (:podcast/report (meta feed))))

(declare normalize-children)

(defn- normalize-value
  "The model value `v` of `entry` in `registry` as the model shapes it: a
  bare value where the element carries content and attributes becomes
  `{:content v}`, and the children of a map are normalized in turn."
  [registry entry v]
  (if-not (spec/map-shaped? registry entry)
    v
    (let [m (if (and (some? v) (not (coll? v)) (not= :none (spec/content-type entry)))
              {:content v}
              v)]
      (if (map? m)
        (normalize-children registry (spec/children-context entry) m)
        m))))

(defn- normalize-children
  "The model map `m` of an element in the `parent` context of `registry`
  with every child value normalized, a single value where the model has a
  vector wrapped in one."
  [registry parent m]
  (reduce (fn [m entry]
            (let [k (spec/key-for entry parent)
                  v (get m k)]
              (if (nil? v)
                m
                (assoc m k (if (= :many (spec/count-for entry parent))
                             (mapv #(normalize-value registry entry %) (if (sequential? v) v [v]))
                             (normalize-value registry entry v))))))
          m
          (spec/entries-for registry parent)))

(defn normalize
  "`feed` in the shape the model gives it, by the tag registry in `opts`
  (`:registry`, default `spec/default`): the shorthand a producer may
  write, `{:rss/guid \"x\"}` for `{:rss/guid {:content \"x\"}}` and
  `{:podcast/transcripts {…}}` for `[{…}]`, expanded. What `parse` returns
  comes back unchanged; `emit` and `validate` normalize first."
  ([feed]
   (normalize feed {}))
  ([feed {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (normalize-children (spec/registry registry) :channel feed)))

(declare emit-children)

(defn- xml-name?
  "Whether the keyword `k` makes an XML attribute name: its name, and its
  namespace when it has one, start with a letter or an underscore and go on
  with letters, digits, dots, hyphens and underscores."
  [k]
  (every? #(re-matches #"[A-Za-z_\u0080-\uFFFF][A-Za-z0-9._\-\u0080-\uFFFF]*" %)
          (keep identity [(namespace k) (name k)])))

(defn- attr-plan
  "What `emit-attrs` needs of `entry`, worked out once for each entry an
  emit meets and kept in `ctx`: the model keys that its children and its
  declared attributes take, and its declared attributes as `[name key
  type]`."
  [ctx entry]
  (let [plans (:plans ctx)]
    (or (get @plans entry)
        (let [context (spec/children-context entry)
              plan    {:taken    (into #{:content :xml/unknown}
                                       (concat (map #(spec/key-for % context) (spec/entries-for (:registry ctx) context))
                                               (map xml/attr-key (keys (:attrs entry)))))
                       :declared (mapv (fn [[k {:keys [type]}]] [k (xml/attr-key k) (or type :text)]) (:attrs entry))}]
          (vswap! plans assoc entry plan)
          plan))))

(defn- emit-attrs
  "The attribute map for an element of `entry` from the model map `m`, with
  declared attributes back under their XML names. Any other key is an
  attribute a parse kept, written when it can be one: a string under an
  XML name."
  [ctx entry m]
  (let [{:keys [taken declared]} (attr-plan ctx entry)
        declared (reduce (fn [acc [k k' type]]
                           (let [v (get m k')]
                             (if (some? v) (assoc acc k (coerce/format type v)) acc)))
                         {}
                         declared)
        rest     (reduce-kv (fn [acc k v]
                              (if (and (keyword? k) (not (contains? taken k)) (string? v) (xml-name? k))
                                (assoc acc k v)
                                acc))
                            {}
                            m)]
    (merge rest declared)))

(defn- emit-element
  "The Hiccup element of `entry` for the model value `v`; nil for a value
  of a map-shaped element that is not a map, which `validate` reports.
  An element of the type :presence is empty, and it is there for true
  alone."
  [ctx entry v]
  (let [tag  (:tag entry)
        type (spec/content-type entry)]
    (cond
      (spec/map-shaped? (:registry ctx) entry)
      (when (map? v)
        (-> [tag (emit-attrs ctx entry v)]
            (into (when (some? (:content v))
                    [(coerce/format type (:content v))]))
            (into (emit-children ctx (spec/children-context entry) v))))

      (= :presence type)
      (when (true? v)
        [tag {}])

      :else
      [tag {} (coerce/format type v)])))

(defn- emit-children
  "The Hiccup children of an element in the `parent` context, from the
  model map `m`, in registry order and with the unknown elements last. A
  nil value is no value."
  [ctx parent m]
  (let [add (fn [acc entry v]
              (if-some [element (when (some? v) (emit-element ctx entry v))]
                (conj! acc element)
                acc))
        acc (reduce (fn [acc entry]
                      (let [v (get m (spec/key-for entry parent))]
                        (if (= :many (spec/count-for entry parent))
                          (reduce #(add %1 entry %2) acc v)
                          (add acc entry v))))
                    (transient [])
                    (spec/entries-for (:registry ctx) parent))]
    (persistent! (reduce conj! acc (:xml/unknown m)))))

(defn emit
  "The canonical Hiccup document for `feed` by the tag registry in `opts`
  (`:registry`, default `spec/default`), ready for `xml/emit`, the
  shorthand of `normalize` taken and nil values left out. The foreign
  namespaces recorded when the feed was parsed, and `:namespaces` in
  `opts`, alias to URI for elements of namespaces it was not, or for
  another URI of one it was, go along as metadata for `xml/emit` to
  declare."
  ([feed]
   (emit feed {}))
  ([feed {:keys [registry namespaces] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (let [registry (spec/registry registry)
         ctx      {:registry registry :plans (volatile! {})}
         feed     (normalize feed {:registry registry})]
     (with-meta [:rss {:version (or (:rss/version feed) "2.0")}
                 (into [:channel {}] (emit-children ctx :channel (dissoc feed :rss/version)))]
       {:xml/namespaces (merge (:xml/namespaces (meta feed)) namespaces)}))))

(defn item-guid
  "The identity of `item`: its `<guid>`, or failing that its enclosure URL, as
  the namespace recommendations say."
  [item]
  (or (not-empty (:content (:rss/guid item)))
      (:url (:rss/enclosure item))))

(defn feed-guid
  "The identity of `feed`: its declared `podcast:guid`, or the one the
  specification derives from `url` when it declares none."
  [feed url]
  (or (not-empty (:podcast/guid feed))
      (when url (guid/from-url url))))

(defn- identified
  "The items among `items` that have an identity, and of those that share
  one, the first."
  [items]
  (let [seen (volatile! #{})]
    (filterv (fn [item]
               (let [id (item-guid item)]
                 (when (and id (not (contains? @seen id)))
                   (vswap! seen conj id))))
             items)))

(defn- item-changes
  "What changed between the item sequences `before` and `after`, by item
  identity; see `diff`."
  [before after]
  (let [before (identified before)
        after  (identified after)
        old    (into {} (map (juxt item-guid identity)) before)
        new    (into {} (map (juxt item-guid identity)) after)]
    {:added     (filterv #(not (contains? old (item-guid %))) after)
     :removed   (filterv #(not (contains? new (item-guid %))) before)
     :changed   (vec (for [item after
                           :let [previous (get old (item-guid item))]
                           :when (and previous (not= previous item))]
                       {:before previous :after item}))
     :unchanged (count (filter #(= % (get old (item-guid %))) after))}))

(defn diff
  "What changed from the feed `before` to the feed `after`, by item identity:

      {:added [item ...] :removed [item ...] :changed [{:before :after} ...]
       :unchanged 42
       :live-items {:added [...] :removed [...] :changed [...] :unchanged 1}
       :channel-changed? false}

  Items without an identity (no guid and no enclosure) are not compared, nor
  are the later of the items that share one; the channel counts as changed
  when anything but its items, live items and timestamps differs."
  [before after]
  (let [churn [:rss/items :podcast/live-items :rss/last-build-date :rss/pub-date]]
    (assoc (item-changes (:rss/items before) (:rss/items after))
           :live-items (item-changes (:podcast/live-items before) (:podcast/live-items after))
           :channel-changed? (not= (apply dissoc before churn) (apply dissoc after churn)))))

(defn- page-report
  "The diagnostics of `page` for the feed that `join-pages` makes. `places`
  has the index in that feed of each item that the feed takes from the
  page, by its index in the page.

  A diagnostic of such an item gets the path of the item in the feed. A
  diagnostic of another part of the page stays when the page is the first
  one, or when the diagnostic has no path."
  [page places first?]
  (keep (fn [{[k i & more] :path :as diagnostic}]
          (cond
            (and (= :rss/items k) (some? i))               (when-some [j (places i)]
                                                             (assoc diagnostic :path (into [k j] more)))
            (or first? (not (contains? diagnostic :path))) diagnostic))
        (report page)))

(defn- with-page
  "The feed `acc` that `join-pages` builds, as `{:items :seen :report
  :first?}`, with the items of `page` that it does not hold, and with the
  report of the page."
  [{:keys [items seen report first?]} page]
  (let [fresh  (keep-indexed (fn [i item] (when-not (contains? seen (item-guid item)) [i item]))
                             (:rss/items page))
        places (into {} (map-indexed (fn [n [i _]] [i (+ (count items) n)])) fresh)]
    {:items  (into items (map second) fresh)
     :seen   (into seen (keep (comp item-guid second)) fresh)
     :report (into report (page-report page places first?))
     :first? false}))

(defn join-pages
  "One feed of `pages`, the parsed pages of a feed that comes in pages, in
  their order. Nil for no pages.

  The feed is the channel of the first page, with the items of all the
  pages. A feed can gain items while its pages are read. So an item of a
  later page is left out when an earlier page holds it, by `item-guid`.

  The report has the diagnostics of the first page. From a later page, it
  has the diagnostics of the items that the feed takes from the page, and
  the diagnostics without a path. The path of a diagnostic names the item
  where the feed has it."
  [pages]
  (when-let [first-page (first pages)]
    (let [{:keys [items report]} (reduce with-page {:items [] :seen #{} :report [] :first? true} pages)]
      (vary-meta (cond-> first-page (seq items) (assoc :rss/items items))
                 assoc :podcast/report report))))

(def page-size
  "How many items a document of `pages` holds, unless its options give
  another `:page-size`."
  100)

(def ^:no-doc document-rels
  "The link relations of RFC 5005, which tie the documents of one feed
  together, and self, the link of a document to itself. Section 3 has
  those of a paged feed, and section 4 has those of an archived feed."
  #{"self" "first" "last" "previous" "next" "prev-archive" "next-archive" "current"})

(defn ^:no-doc document-link?
  "Whether the atom:link `link` has one of the `document-rels`."
  [link]
  (contains? document-rels (some-> (:rel link) str/lower-case)))

(defn- document
  "One document of `feed` for `pages`, as `{:url :feed}`: the feed with the
  `items` alone, at the `url`. `links` has the URL of each link relation
  of the document, and a relation without a URL is left out. They take
  the place of the links of `document-rels` that the feed has."
  [feed url items links]
  (let [type  (or (some #(when (= "self" (some-> (:rel %) str/lower-case)) (:type %)) (:atom/links feed))
                  "application/rss+xml")
        links (for [[rel href] (cons ["self" url] links)
                    :when href]
                {:rel rel :href href :type type})]
    {:url  url
     :feed (-> (dissoc feed :fh/complete :fh/archive)
               (assoc :rss/items (vec items)
                      :atom/links (into (vec links) (remove document-link?) (:atom/links feed))))}))

(defn- paged
  "The documents of `feed` as a paged feed, as section 3 of RFC 5005
  defines it: `size` items to a page, the first page at `url`, and page
  `n` at `(page-url n)`."
  [feed size url page-url]
  (let [chunks (vec (partition-all size (:rss/items feed)))
        n      (max 1 (count chunks))
        at     #(if (= 1 %) url (page-url %))]
    (vec (for [k (range 1 (inc n))]
           (document feed (at k) (get chunks (dec k))
                     [["first" (at 1)]
                      ["last" (at n)]
                      ["previous" (when (< 1 k) (at (dec k)))]
                      ["next" (when (< k n) (at (inc k)))]])))))

(defn- archived
  "The documents of `feed` as an archived feed, as section 4 of RFC 5005
  defines it: the subscription document at `url` with the newest `size`
  items, then the archives, the newest first. Archive `n` is at
  `(page-url n)`, and it holds `size` items, counted from the oldest
  item.

  Section 4 says that the items of an archive do not change. A document
  links to an archive only when the archive is complete. The section
  does not say how to divide the items. Here, an archive is a document
  when the feed has an item that is newer than its items. So the items
  of an archive do not change when the feed gets an item."
  [feed size url page-url]
  (let [items    (vec (:rss/items feed))
        oldest   (vec (rseq items))
        archives (quot (max 0 (dec (count items))) size)
        archive  (fn [k]
                   (-> (document feed (page-url k)
                                 (rseq (subvec oldest (* (dec k) size) (* k size)))
                                 [["current" url]
                                  ["prev-archive" (when (< 1 k) (page-url (dec k)))]
                                  ["next-archive" (when (< k archives) (page-url (inc k)))]])
                       (assoc-in [:feed :fh/archive] true)))]
    (into [(document feed url (take size items)
                     [["prev-archive" (when (pos? archives) (page-url archives))]])]
          (map archive)
          (range archives 0 -1))))

(defn pages
  "The documents that a host serves for `feed` when the feed is too long
  for one document, as RFC 5005 defines them. Each is `{:url :feed}`, in
  the order that a reader fetches them. `join-pages` makes one feed of
  them again.

  `opts` can give these:

  - `:kind`, which is :paged (the default, section 3 of the RFC) or
    :archived (section 4). The pages of a paged feed change with each
    new item. An archived feed has a subscription document with the
    newest items, and archives that do not change, so a reader fetches
    each archive once.
  - `:page-size`, the number of items in a document (default `page-size`)
  - `:url`, the URL of the feed, where its first document is. The
    default is the atom:link rel=self of the feed.
  - `:page-url`, a function of a number that gives the URL of that page or
    archive. Pages count from 1, the first page, and archives count from
    1, the oldest. By default, the URL is `:url` with the query parameter
    page or archive.

  The items are in the order of the feed, the newest first. Each document
  has the channel of the feed, and the links of RFC 5005 in its
  atom:links. An archive has fh:archive."
  ([feed]
   (pages feed {}))
  ([feed {:keys [kind url page-url] :or {kind :paged} :as opts}]
   {:pre [(map? opts)]}
   (let [size     (:page-size opts page-size)
         self     (some #(when (= "self" (some-> (:rel %) str/lower-case)) (:href %)) (:atom/links feed))
         url      (or url self)
         param    (case kind :paged "page" :archived "archive")
         page-url (or page-url #(http/with-query url {param %}))]
     (when-not (string? url)
       (throw (ex-info "The pages of a feed need its URL, as :url or as its atom:link rel=self" {:feed feed})))
     (when-not (and (integer? size) (pos? size))
       (throw (ex-info "The size of a page is a whole number above 0" {:page-size size})))
     (case kind
       :paged    (paged feed size url page-url)
       :archived (archived feed size url page-url)))))

(defn update-media-urls
  "`feed` with `(f url)` in place of the URL of each enclosure, and of each
  source of an alternate enclosure. A value that is no string stays as it
  is.

  A host uses this to put a prefix or a token on the URLs of its media
  files."
  [feed f]
  (let [rewritten #(if (string? %) (f %) %)
        sources   (fn [alternate]
                    (cond-> alternate
                      (:sources alternate)
                      (update :sources (partial mapv #(cond-> % (:uri %) (update :uri rewritten))))))
        item      (fn [item]
                    (cond-> item
                      (get-in item [:rss/enclosure :url])  (update-in [:rss/enclosure :url] rewritten)
                      (:podcast/alternate-enclosures item) (update :podcast/alternate-enclosures
                                                                   (partial mapv sources))))]
    (cond-> feed
      (:rss/items feed) (update :rss/items (partial mapv item)))))

(def token-param
  "The name of the query parameter that holds the token of a subscriber,
  unless the options of `for-subscriber` give another `:param`."
  "token")

(defn for-subscriber
  "`feed` as the private feed of one subscriber, who has the `token`. The
  host makes the tokens and keeps them, and it serves this feed at a URL
  with the token. The token can be a hidden text of
  `dk.simongray.podcast.util.secret`. The feed has it in its URLs, as text.

  The token goes into the query of each URL of a media file, and of each
  link of the feed to its own documents, so that each request tells its
  subscriber. A URL that is not an http or https URL gets no token.

  The feed is closed to the public. It is blocked from the directories:
  podcast:block, itunes:block and googleplay:block say yes. It names no
  WebSub hub, because a subscriber gives the URL of the feed to the hub.
  No standard defines the token of a subscriber, so the query parameter
  is a choice that the options replace. `opts` can give these:

  - `:param`, the name of the query parameter (default `token-param`)
  - `:with-token`, a function of a URL and the token that gives the URL
    of the subscriber, for a host that has the token in the path
  - `:own?`, a predicate of a URL. The URLs that it refuses get no token,
    such as the media files on the server of another company.
  - `:block?` false, for a feed that stays as open as it was"
  ([feed token]
   (for-subscriber feed token {}))
  ([feed token {:keys [param with-token own? block?]
                :or   {param token-param own? (constantly true) block? true}
                :as   opts}]
   {:pre [(map? opts)]}
   (let [token      (secret/reveal token)
         with-token (or with-token #(http/with-query %1 {param %2}))
         personal   #(if (and (http/web-url? %) (own? %)) (with-token % token) %)
         hub?       #(= "hub" (some-> (:rel %) str/lower-case))
         links      (fn [links]
                      (into []
                            (comp (remove #(and block? (hub? %)))
                                  (map #(cond-> % (document-link? %) (update :href personal))))
                            links))]
     (cond-> (update-media-urls feed personal)
       (:atom/links feed) (update :atom/links links)
       block?             (assoc :podcast/blocks [{:content true}]
                                 :itunes/block true
                                 :googleplay/block true)))))

(comment
  (-> "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">
        <channel><title>T</title><podcast:funding url=\"https://x\">Give</podcast:funding>
        <item><title>E1</title><enclosure url=\"https://a/1.mp3\" length=\"12\" type=\"audio/mpeg\"/></item>
        </channel></rss>"
      xml/parse parse)
  (-> *1 emit xml/emit println)
  #_.)
