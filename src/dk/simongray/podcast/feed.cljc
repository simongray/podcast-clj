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
  (:require [clojure.string :as str]
            [dk.simongray.podcast.coerce :as coerce]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

;; The parse threads a context `ctx`: the `:registry` being interpreted and
;; the `:report` accumulator, a volatile.

(defn- note!
  "Record the diagnostic `m` in the report of `ctx`."
  [ctx m]
  (vswap! (:report ctx) conj m))

(defn- parse-attrs
  "Coerce the attributes `attrs` of an element by its registry `entry`.
  Declared attributes get kebab-case keys and typed values; an optional one
  left blank is absent, with a diagnostic, since it says nothing, while a
  required one left blank stays for the validator to judge. The rest keep
  their XML names and string values."
  [ctx entry attrs path]
  (reduce-kv (fn [m k v]
               (if-let [{:keys [type required]} (get (:attrs entry) k)]
                 (let [k' (xml/attr-key k)
                       v' (coerce/parse (or type :text) v)]
                   (cond
                     (and (str/blank? v) (not required))
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
  (let [text (element-text ctx entry element path)]
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

      (seq text)
      (parse-content ctx entry text path)

      :else
      (do (note! ctx {:id :feed/empty-element :level :info :path path})
          ::empty))))

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
  what resolving the namespaces found stays `xml/report` on the tree."
  ([tree]
   (parse tree {}))
  ([tree {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   ;; RSS 2.0 only, on purpose. Atom, RSS 1.0 and JSON Feed are not podcast
   ;; formats in practice: the directories require RSS 2.0, and a live sample
   ;; of 844 indexed feeds (2026-09-25) held no Atom feed at all.
   (when-not (= :rss (xml/tag tree))
     (throw (ex-info "Not an RSS 2.0 document" {:type ::unsupported :root (xml/tag tree)})))
   (let [ctx     {:registry (spec/registry registry) :report (volatile! [])}
         channel (or (xml/element tree :channel)
                     (throw (ex-info "No channel element" {:type ::unsupported})))
         version (:version (xml/attrs tree))
         feed    (parse-children ctx :channel (xml/elements channel) [])
         others  (remove #{channel} (xml/elements tree))]
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
  (`:registry`, default `spec/default`), for the shorthand a producer may
  write: a bare value where an element with attributes carries only
  content, `{:rss/guid \"x\"}` for `{:rss/guid {:content \"x\"}}`, and a
  single value where the model has a vector, `{:podcast/transcripts {…}}`
  for `[{…}]`. What `parse` returns comes back unchanged. `emit` and
  `validate` normalize first, so the shorthand is taken everywhere the
  model is written, while the schemas describe the model alone."
  ([feed]
   (normalize feed {}))
  ([feed {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (normalize-children (spec/registry registry) :channel feed)))

(declare emit-children)

(defn- emit-attrs
  "The attribute map for an element of `entry` in `registry` from the model
  map `m`, with declared attributes back under their XML names."
  [registry entry m]
  (let [context  (spec/children-context entry)
        declared (into {} (for [[k {:keys [type]}] (:attrs entry)
                                :let [v (get m (xml/attr-key k))]
                                :when (some? v)]
                            [k (coerce/format (or type :text) v)]))
        taken    (into #{:content :xml/unknown}
                       (concat (map #(spec/key-for % context) (spec/entries-for registry context))
                               (map xml/attr-key (keys (:attrs entry)))))
        rest     (into {} (for [[k v] m
                                :when (not (contains? taken k))]
                            [k (str v)]))]
    (merge rest declared)))

(defn- emit-element
  "The Hiccup element of `entry` in `registry` for the model value `v`."
  [registry entry v]
  (let [tag  (:tag entry)
        type (spec/content-type entry)]
    (if (spec/map-shaped? registry entry)
      (-> [tag (emit-attrs registry entry v)]
          (into (when (contains? v :content)
                  [(coerce/format type (:content v))]))
          (into (emit-children registry (spec/children-context entry) v)))
      [tag {} (coerce/format type v)])))

(defn- emit-children
  "The Hiccup children of an element in the `parent` context of `registry`,
  from the model map `m`, in registry order and with the unknown elements
  last."
  [registry parent m]
  (concat (for [entry (spec/entries-for registry parent)
                :let [k (spec/key-for entry parent)]
                :when (contains? m k)
                v (if (= :many (spec/count-for entry parent))
                    (get m k)
                    [(get m k)])]
            (emit-element registry entry v))
          (:xml/unknown m)))

(defn emit
  "The canonical Hiccup document for `feed` by the tag registry in `opts`
  (`:registry`, default `spec/default`), ready for `xml/emit`, the
  shorthand of `normalize` taken. The foreign namespaces recorded when the
  feed was parsed are carried over as metadata so that `xml/emit` can
  declare them."
  ([feed]
   (emit feed {}))
  ([feed {:keys [registry] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (let [registry (spec/registry registry)
         feed     (normalize feed {:registry registry})]
     (with-meta [:rss {:version (or (:rss/version feed) "2.0")}
                 (into [:channel {}] (emit-children registry :channel (dissoc feed :rss/version)))]
       {:xml/namespaces (:xml/namespaces (meta feed))}))))

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

(defn- by-guid
  "The `items` that have an identity, by it."
  [items]
  (into {} (keep (fn [item]
                   (when-let [id (item-guid item)]
                     [id item])))
        items))

(defn- item-changes
  "What changed between the item sequences `before` and `after`, by item
  identity; see `diff`."
  [before after]
  (let [old     (by-guid before)
        new     (by-guid after)
        current (filter item-guid after)]
    {:added     (vec (remove #(contains? old (item-guid %)) current))
     :removed   (vec (remove #(contains? new (item-guid %)) (filter item-guid before)))
     :changed   (vec (for [item current
                           :let [previous (get old (item-guid item))]
                           :when (and previous (not= previous item))]
                       {:before previous :after item}))
     :unchanged (count (filter #(= % (get old (item-guid %))) current))}))

(defn diff
  "What changed from the feed `before` to the feed `after`, by item identity:

      {:added [item ...] :removed [item ...] :changed [{:before :after} ...]
       :unchanged 42
       :live-items {:added [...] :removed [...] :changed [...] :unchanged 1}
       :channel-changed? false}

  Live items are compared the same way under `:live-items`, so that a show
  going on air or ending is a change of its own. Items without an identity
  (no guid and no enclosure) are not compared. The channel counts as changed
  when anything but its items, live items and timestamps differs."
  [before after]
  (let [churn [:rss/items :podcast/live-items :rss/last-build-date :rss/pub-date]]
    (assoc (item-changes (:rss/items before) (:rss/items after))
           :live-items (item-changes (:podcast/live-items before) (:podcast/live-items after))
           :channel-changed? (not= (apply dissoc before churn) (apply dissoc after churn)))))

(comment
  (-> "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">
        <channel><title>T</title><podcast:funding url=\"https://x\">Give</podcast:funding>
        <item><title>E1</title><enclosure url=\"https://a/1.mp3\" length=\"12\" type=\"audio/mpeg\"/></item>
        </channel></rss>"
      xml/parse parse)
  (-> *1 emit xml/emit println)
  #_.)
