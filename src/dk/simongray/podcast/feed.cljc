(ns dk.simongray.podcast.feed
  "The feed model: canonical Hiccup in, maps out, and back again, by
  interpreting a tag registry, `dk.simongray.podcast.spec/default` unless
  the options name another.

  A feed is the channel map itself, with items under `:rss/items` and live
  items under `:podcast/live-items`. Scalar elements become coerced scalars,
  elements with attributes or children become maps, repeatable elements
  become vectors, and elements the registry does not know are kept as Hiccup
  under `:xml/unknown`. Nothing throws on bad values: they stay strings and
  the report says so. The report is metadata on the result, under
  `:podcast/report`, and `report` reads it."
  (:require [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

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
          (if (seq text)
            (parse-content ctx entry text path)
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
  of a map-shaped element that is not a map, which `validate` reports."
  [ctx entry v]
  (let [tag  (:tag entry)
        type (spec/content-type entry)]
    (if (spec/map-shaped? (:registry ctx) entry)
      (when (map? v)
        (-> [tag (emit-attrs ctx entry v)]
            (into (when (some? (:content v))
                    [(coerce/format type (:content v))]))
            (into (emit-children ctx (spec/children-context entry) v))))
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

(defn join-pages
  "One feed of `pages`, the parsed pages of a feed that comes in pages, in
  their order: the channel of the first page with the items of all pages.
  An item of a later page that an earlier page holds is left out, by
  `item-guid`, since a feed can gain items while its pages are read. The
  report is the reports of the pages together."
  [[first-page & more :as pages]]
  (let [items (:rss/items (reduce (fn [{:keys [seen] :as acc} page]
                                    (let [fresh (remove #(contains? seen (item-guid %)) (:rss/items page))]
                                      (-> acc
                                          (update :rss/items into fresh)
                                          (update :seen into (keep item-guid fresh)))))
                                  {:rss/items (vec (:rss/items first-page))
                                   :seen      (set (keep item-guid (:rss/items first-page)))}
                                  more))]
    (vary-meta (cond-> first-page (seq items) (assoc :rss/items items))
               assoc :podcast/report (into [] (mapcat report) pages))))

(comment
  (-> "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">
        <channel><title>T</title><podcast:funding url=\"https://x\">Give</podcast:funding>
        <item><title>E1</title><enclosure url=\"https://a/1.mp3\" length=\"12\" type=\"audio/mpeg\"/></item>
        </channel></rss>"
      xml/parse parse)
  (-> *1 emit xml/emit println)
  #_.)
