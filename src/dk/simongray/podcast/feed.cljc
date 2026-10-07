(ns dk.simongray.podcast.feed
  "The feed model: canonical Hiccup in, maps out, and back again, by a tag
  registry.

  The registry is dk.simongray.podcast.feed.spec/default unless the
  options give another. A feed is the channel map itself, with the items
  under :rss/items and the live items under :podcast/live-items. In the
  map:

  - a scalar element becomes a coerced scalar
  - an element with attributes or children becomes a map
  - a repeatable element becomes a vector
  - an element that the registry doesn't know stays as Hiccup under
    :xml/unknown

  Nothing throws on a bad value, which stays a string, and the report says
  so. The report is in the metadata of the result under :podcast/report,
  and the report function reads it."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.spec :as spec]
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
  "The model key of the attribute `k`, as xml/attr-key gives it. It's kept
  in `ctx` for the rest of the parse, since a feed names the same
  attributes on every item."
  [ctx k]
  (let [keys (:keys ctx)]
    (or (get @keys k)
        (let [k' (xml/attr-key k)]
          (vswap! keys assoc k k')
          k'))))

(defn- parse-attrs
  "The attributes `attrs` of an element at `path`, coerced by its registry
  `entry`, with the diagnostics noted in `ctx`.

  A declared attribute gets a kebab-case key and a typed value. An
  optional one that's blank is left out with a diagnostic, since it says
  nothing, while a required one that's blank stays for the validator to
  judge. Other attributes keep their XML names and string values."
  [ctx entry attrs path]
  (reduce-kv (fn [m k v]
               (if-let [{:keys [type required]} (get (:attrs entry) k)]
                 (let [k' (model-key ctx k)
                       v' (coerce/parse (or type :text) v)]
                   (cond
                     (and (= "" (xml/trim v))
                          (not required))
                     (do (note! ctx {:id    :feed/empty-attribute
                                     :level :info
                                     :path  (conj path k')})
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
  "The node text `s` coerced by the content type of `entry`. When it
  doesn't read as that type, it stays a string, with a diagnostic at
  `path` in `ctx`."
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
  "Whether the elements inside `element` of `entry` fold into its text, by
  the registry of `ctx`. They do when the registry expects text there and
  nothing else, e.g. a description embedded as XHTML instead of CDATA."
  [ctx entry element]
  (and (seq (xml/elements element))
       (not (spec/container? (:registry ctx) entry))
       (not= :none (spec/content-type entry))))

(defn- element-text
  "The text of `element` under `entry`, with the diagnostics at `path` in
  `ctx`.

  It's the element's own text, unless markup sits where the registry
  expects text alone. Then it's the markup itself for :html content, or
  else the text of every descendant, with a diagnostic."
  [ctx entry element path]
  (if (folds-markup? ctx entry element)
    (do (note! ctx {:id :feed/markup-in-text :level :info :path path
                    :tags (mapv xml/tag (xml/elements element))})
        (if (= :html (spec/content-type entry))
          (xml/inner-xml element)
          (xml/deep-text element)))
    (xml/text element)))

(defn- parse-map-element
  "The model map of the map-shaped `element` of `entry` at `path`, with
  its `text` as the :content, parsed with `ctx`."
  [ctx entry element text path]
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
      m)))

(defn- parse-scalar-element
  "The model value of the scalar `element` of `entry` at `path`, read from
  its `text` with `ctx`, or ::empty when it has no text."
  [ctx entry element text path]
  (when-let [attrs (not-empty (xml/attrs element))]
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
        ::empty)))

(defn- parse-element
  "The model value of the Hiccup `element` at `path`, parsed by its
  registry `entry` with `ctx`, or ::empty for a scalar element without
  text."
  [ctx entry element path]
  (let [text (element-text ctx entry element path)
        ;; the spaces kept between two elements are no text of their own
        text (when-not (= "" (xml/trim text)) text)]
    (if (spec/map-shaped? (:registry ctx) entry)
      (parse-map-element ctx entry element text path)
      (parse-scalar-element ctx entry element text path))))

(defn- parse-children
  "The child `elements` of an element in the `parent` context at `path`,
  parsed with `ctx` into a map keyed by the registry. Unknown elements
  collect under :xml/unknown."
  [ctx parent elements path]
  (reduce (fn [m element]
            (let [tag (xml/tag element)]
              (if-let [entry (spec/entry-for (:registry ctx) tag parent)]
                (let [k     (spec/key-for entry parent)
                      many? (= :many (spec/count-for entry parent))
                      path' (cond-> (conj path k)
                              many? (conj (count (get m k))))
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

(defn- note-leftovers!
  "Note in `ctx` what the rss element `tree` and its `channel` hold that
  has no place in the model: the attributes of rss other than its version,
  those of channel, and the text of channel."
  [ctx tree channel]
  (let [dropped [[:rss (dissoc (xml/attrs tree) :version)]
                 [:channel (xml/attrs channel)]]
        text    (xml/text channel)]
    (doseq [[tag attrs] dropped
            :when (seq attrs)]
      (note! ctx {:id :feed/dropped-attributes :level :info :path [] :tag tag
                  :attributes (vec (keys attrs))}))
    (when-not (= "" (xml/trim text))
      (note! ctx {:id :feed/unexpected-text :level :info :path []
                  :value text}))))

(defn parse
  "Parse the canonical Hiccup `tree` of an RSS 2.0 document into a feed map
  by the :registry of `opts`, spec/default by default.

  The diagnostics go along as metadata under :podcast/report, and the
  foreign namespaces that the document used under :xml/namespaces. What
  resolving the namespaces found stays under :xml/report on the tree. The
  attributes of rss other than its version, and those of channel, have no
  place in the model, and the report says when there are any."
  ([tree]
   (parse tree {}))
  ([tree {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (when-not (= :rss (xml/tag tree))
     (throw (ex-info "Not an RSS 2.0 document"
                     {:type ::unsupported :root (xml/tag tree)})))
   (let [ctx     {:registry (spec/registry registry)
                  :report   (volatile! [])
                  :keys     (volatile! {})}
         channel (or (xml/element tree :channel)
                     (throw (ex-info "No channel element"
                                     {:type ::unsupported})))
         version (:version (xml/attrs tree))
         feed    (parse-children ctx :channel (xml/elements channel) [])
         ;; by identity: a set of the channel would hash all of the feed
         others  (remove #(identical? % channel) (xml/elements tree))]
     (note-leftovers! ctx tree channel)
     (with-meta (cond-> feed
                  version      (assoc :rss/version version)
                  (seq others) (update :xml/unknown (fnil into []) others))
       {:podcast/report @(:report ctx)
        :xml/namespaces (xml/foreign-namespaces tree)}))))

(defn report
  "The diagnostics recorded while parsing `feed`, each with its message from
  the catalogue of diagnostics."
  [feed]
  (mapv diagnostics/describe (:podcast/report (meta feed))))

(declare normalize-children)

(defn- normalize-value
  "The model value `v` of `entry` in `registry`, in the shape of the model.

  A bare value where the element has content and attributes becomes a map
  with `v` as its :content, and the children of a map are normalized in
  turn."
  [registry entry v]
  (if-not (spec/map-shaped? registry entry)
    v
    (let [bare? (and (some? v)
                     (not (coll? v))
                     (not= :none (spec/content-type entry)))
          m     (if bare? {:content v} v)]
      (if (map? m)
        (normalize-children registry (spec/children-context entry) m)
        m))))

(defn- normalize-children
  "The model map `m` of an element in the `parent` context of `registry`,
  with every child value normalized. A single value where the model has a
  vector is wrapped in one."
  [registry parent m]
  (reduce (fn [m entry]
            (let [k (spec/key-for entry parent)
                  v (get m k)]
              (if (nil? v)
                m
                (assoc m k (if (= :many (spec/count-for entry parent))
                             (mapv #(normalize-value registry entry %)
                                   (if (sequential? v) v [v]))
                             (normalize-value registry entry v))))))
          m
          (spec/entries-for registry parent)))

(defn normalize
  "The `feed` in the shape of the model, by the :registry of `opts`,
  spec/default by default.

  It expands the shorthand that a producer can write, e.g.:

      {:rss/guid \"x\"}            ; for {:rss/guid {:content \"x\"}}
      {:podcast/transcripts {…}}  ; for {:podcast/transcripts [{…}]}

  What parse returns comes back unchanged, and emit and validate normalize
  first."
  ([feed]
   (normalize feed {}))
  ([feed {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (normalize-children (spec/registry registry) :channel feed)))

(declare emit-children)

(defn- xml-name?
  "Whether the keyword `k` makes an XML attribute name. Its name, and its
  namespace if any, start with a letter or an underscore and go on with
  letters, digits, dots, hyphens and underscores."
  [k]
  (every? #(re-matches #"[A-Za-z_\u0080-\uFFFF][A-Za-z0-9._\-\u0080-\uFFFF]*" %)
          (keep identity [(namespace k) (name k)])))

(defn- attr-plan
  "What emit-attrs needs of `entry`, worked out once for each entry and
  kept in `ctx`.

  It's the model keys that the children and declared attributes of the
  entry take, and its declared attributes as triples of name, key and
  type."
  [ctx entry]
  (let [plans (:plans ctx)]
    (or (get @plans entry)
        (let [context    (spec/children-context entry)
              child-keys (map #(spec/key-for % context)
                              (spec/entries-for (:registry ctx) context))
              attr-keys  (map xml/attr-key (keys (:attrs entry)))
              declared   (mapv (fn [[k {:keys [type]}]]
                                 [k (xml/attr-key k) (or type :text)])
                               (:attrs entry))
              plan       {:taken    (into #{:content :xml/unknown}
                                          (concat child-keys attr-keys))
                          :declared declared}]
          (vswap! plans assoc entry plan)
          plan))))

(defn- emit-attrs
  "The attribute map of an element of `entry` from the model map `m`, by
  the plan in `ctx`.

  Declared attributes go back under their XML names. Any other key is an
  attribute that a parse kept, written when it can be one: a string under
  an XML name."
  [ctx entry m]
  (let [{:keys [taken declared]} (attr-plan ctx entry)
        declared (reduce (fn [acc [k k' type]]
                           (let [v (get m k')]
                             (if (some? v)
                               (assoc acc k (coerce/format type v))
                               acc)))
                         {}
                         declared)
        rest     (reduce-kv (fn [acc k v]
                              (if (and (keyword? k)
                                       (not (contains? taken k))
                                       (string? v)
                                       (xml-name? k))
                                (assoc acc k v)
                                acc))
                            {}
                            m)]
    (merge rest declared)))

(defn- emit-element
  "The Hiccup element of `entry` for the model value `v`, with `ctx`.

  It's nil for a value of a map-shaped element that isn't a map, which
  validate reports. An element of the type :presence is empty, and it's
  there only for true."
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
  "The Hiccup children of an element in the `parent` context from the model
  map `m`, with `ctx`. They're in registry order, with the unknown elements
  last, and a nil value is no value."
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
  "The canonical Hiccup document of `feed`, ready for xml/emit, with the
  `opts` below:

  - :registry, the tag registry, spec/default by default
  - :namespaces, a map from alias to URI for the elements of namespaces
    that the feed wasn't parsed with, or for another URI of one that it was

  It takes the shorthand of normalize and leaves out nil values. The
  namespaces of spec/namespaces and of :namespaces, and the foreign
  namespaces recorded when the feed was parsed, go along as metadata for
  xml/emit to declare."
  ([feed]
   (emit feed {}))
  ([feed {:keys [registry namespaces] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (let [registry (spec/registry registry)
         ctx      {:registry registry :plans (volatile! {})}
         feed     (normalize feed {:registry registry})
         version  (or (:rss/version feed) "2.0")
         children (emit-children ctx :channel (dissoc feed :rss/version))]
     (with-meta [:rss {:version version}
                 (into [:channel {}] children)]
       {:xml/namespaces (merge spec/namespaces
                               (:xml/namespaces (meta feed))
                               namespaces)}))))

(defn- identified
  "The `items` that have an identity, and of those that share one, the
  first."
  [items]
  (let [seen (volatile! #{})]
    (filterv (fn [item]
               (let [id (guid/item-guid item)]
                 (when (and id (not (contains? @seen id)))
                   (vswap! seen conj id))))
             items)))

(defn- item-changes
  "What changed between the item sequences `before` and `after`, by item
  identity, as diff gives it."
  [before after]
  (let [before (identified before)
        after  (identified after)
        old    (into {} (map (juxt guid/item-guid identity)) before)
        new    (into {} (map (juxt guid/item-guid identity)) after)]
    {:added     (filterv #(not (contains? old (guid/item-guid %))) after)
     :removed   (filterv #(not (contains? new (guid/item-guid %))) before)
     :changed   (vec (for [item after
                           :let [previous (get old (guid/item-guid item))]
                           :when (and previous (not= previous item))]
                       {:before previous :after item}))
     :unchanged (count (filter #(= % (get old (guid/item-guid %))) after))}))

(defn diff
  "What changed from the feed `before` to the feed `after`, by item identity:

      {:added [item ...] :removed [item ...] :changed [{:before :after} ...]
       :unchanged 42
       :live-items {:added [...] :removed [...] :changed [...] :unchanged 1}
       :channel-changed? false}

  Items without an identity, with neither a guid nor an enclosure, aren't
  compared, and neither are the later of the items that share one. The
  channel counts as changed when anything but its items, live items and
  timestamps differs."
  [before after]
  (let [churn [:rss/items :podcast/live-items
               :rss/last-build-date :rss/pub-date]
        items (item-changes (:rss/items before) (:rss/items after))
        live  (item-changes (:podcast/live-items before)
                            (:podcast/live-items after))]
    (assoc items
           :live-items       live
           :channel-changed? (not= (apply dissoc before churn)
                                   (apply dissoc after churn)))))

(defn- page-report
  "The diagnostics of `page` for the feed that join-pages makes, by the
  `places` of its items and whether it's the `first?` page.

  The `places` have the index in that feed of each item that the feed
  takes from the page, by its index in the page. A diagnostic of such an
  item gets the path of the item in the feed. A diagnostic of another part
  of the page stays when the page is the first one, or when the diagnostic
  has no path."
  [page places first?]
  (keep (fn [{[k i & more] :path :as diagnostic}]
          (cond
            (and (= :rss/items k)
                 (some? i))
            (when-some [j (places i)]
              (assoc diagnostic :path (into [k j] more)))

            (or first?
                (not (contains? diagnostic :path)))
            diagnostic))
        (report page)))

(defn- with-page
  "The feed `acc` that join-pages builds, with the items of `page` that it
  doesn't have yet, and the report of the page.

  The feed is a map of the :items, the guids :seen, the :report, and
  whether the next page is the :first?."
  [{:keys [items seen report first?] :as acc} page]
  (let [fresh  (keep-indexed (fn [i item]
                               (when-not (contains? seen (guid/item-guid item))
                                 [i item]))
                             (:rss/items page))
        places (into {}
                     (map-indexed (fn [n [i _]] [i (+ (count items) n)]))
                     fresh)]
    {:items  (into items (map second) fresh)
     :seen   (into seen (keep (comp guid/item-guid second)) fresh)
     :report (into report (page-report page places first?))
     :first? false}))

(defn join-pages
  "One feed of `pages`, the parsed pages of a feed that comes in pages, in
  their order, or nil for no pages.

  The feed is the channel of the first page, with the items of all the
  pages. A feed can gain items while its pages are read, so an item of a
  later page is left out when an earlier page has it, by feed.guid/item-guid.

  The report has the diagnostics of the first page. From a later page, it
  has the diagnostics of the items that the feed takes from the page, and
  the diagnostics without a path. The path of a diagnostic names the item
  where the feed has it."
  [pages]
  (when-let [first-page (first pages)]
    (let [start                  {:items [] :seen #{} :report [] :first? true}
          {:keys [items report]} (reduce with-page start pages)]
      (vary-meta (cond-> first-page
                   (seq items) (assoc :rss/items items))
                 assoc :podcast/report report))))

(comment
  (-> "<rss version=\"2.0\"
        xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">
        <channel><title>T</title>
        <podcast:funding url=\"https://x\">Give</podcast:funding>
        <item><title>E1</title>
        <enclosure url=\"https://a/1.mp3\" length=\"12\" type=\"audio/mpeg\"/>
        </item></channel></rss>"
      xml/parse parse)
  (-> *1 emit xml/emit println)
  #_.)
