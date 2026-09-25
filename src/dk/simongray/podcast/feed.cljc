(ns dk.simongray.podcast.feed
  "The feed model: canonical Hiccup in, maps out, and back again, by
  interpreting the tag registry in dk.simongray.podcast.spec.

  A feed is the channel map itself, with items under `:rss/items` and live
  items under `:podcast/live-items`. Scalar elements become coerced scalars,
  elements with attributes or children become maps, repeatable elements
  become vectors, and elements the registry does not know are kept as Hiccup
  under `:xml/unknown`. Nothing throws on bad values: they stay strings and
  the report says so. The report is metadata on the result, under
  `:podcast/report`, and `report` reads it."
  (:require [dk.simongray.podcast.coerce :as coerce]
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.spec :as spec]
            [dk.simongray.podcast.xml :as xml]))

;;;; Parsing

(defn- note!
  "Record the finding `m` in the `report` accumulator."
  [report m]
  (vswap! report conj m))

(defn- parse-attrs
  "Coerce the attributes `attrs` of an element by its registry `entry`.
  Declared attributes get kebab-case keys and typed values; the rest keep
  their XML names and string values."
  [entry attrs path report]
  (reduce-kv (fn [m k v]
               (if-let [{:keys [type]} (get (:attrs entry) k)]
                 (let [k' (spec/attr-key k)
                       v' (coerce/parse (or type :text) v)]
                   (if (coerce/invalid? v')
                     (do (note! report {:id :feed/invalid-value :level :warning
                                        :path (conj path k') :type type :value v})
                         (assoc m k' v))
                     (assoc m k' v')))
                 (assoc m k v)))
             {}
             attrs))

(defn- parse-content
  "Coerce the node text `s` by the content type of `entry`, or keep it as a
  string with a finding when it does not read as that type."
  [entry s path report]
  (let [type (spec/content-type entry)
        v    (coerce/parse type s)]
    (if (coerce/invalid? v)
      (do (note! report {:id :feed/invalid-value :level :warning
                         :path path :type type :value s})
          s)
      v)))

(declare parse-children)

(defn- container?
  "Whether `entry` has registry children of its own, so that elements inside
  it are expected."
  [entry]
  (contains? spec/containers (spec/children-context entry)))

(defn- folds-markup?
  "Whether elements found inside an element of `entry` are folded into its
  text: when the registry expects text there and nothing else, as with a
  description embedded as XHTML instead of CDATA."
  [entry element]
  (and (seq (xml/elements element))
       (not (container? entry))
       (not= :none (spec/content-type entry))))

(defn- element-text
  "The text of `element` under `entry`: its direct text, or when markup sits
  where the registry expects text alone, the markup itself for `:html`
  content and every descendant's text otherwise, with a finding."
  [entry element path report]
  (if (folds-markup? entry element)
    (do (note! report {:id :feed/markup-in-text :level :info :path path
                       :tags (mapv xml/tag (xml/elements element))})
        (if (= :html (spec/content-type entry))
          (xml/inner-xml element)
          (xml/deep-text element)))
    (xml/text element)))

(defn- parse-element
  "Parse the Hiccup `element` by its registry `entry` into a model value, or
  `::empty` for a scalar element with no text."
  [entry element path report]
  (let [text (element-text entry element path report)]
    (cond
      (spec/map-shaped? entry)
      (let [m (cond-> (parse-attrs entry (xml/attrs element) path report)
                (not (folds-markup? entry element))
                (merge (parse-children (spec/children-context entry)
                                       (xml/elements element) path report)))]
        (cond
          (= :none (spec/content-type entry))
          (do (when (seq text)
                (note! report {:id :feed/unexpected-text :level :info
                               :path path :value text}))
              m)

          (seq text)
          (assoc m :content (parse-content entry text path report))

          :else
          m))

      (seq text)
      (parse-content entry text path report)

      :else
      (do (note! report {:id :feed/empty-element :level :info :path path})
          ::empty))))

(defn- parse-children
  "Parse the child `elements` of an element in the `parent` context into a
  map keyed by the registry; unknown elements collect under `:xml/unknown`."
  [parent elements path report]
  (reduce (fn [m element]
            (let [tag (xml/tag element)]
              (if-let [entry (spec/entry tag parent)]
                (let [k     (spec/key-for entry parent)
                      many? (= :many (spec/count-for entry parent))
                      path' (cond-> (conj path k) many? (conj (count (get m k))))
                      v     (parse-element entry element path' report)]
                  (cond
                    (= v ::empty)
                    m

                    many?
                    (update m k (fnil conj []) v)

                    (contains? m k)
                    (do (note! report {:id :feed/duplicate-element :level :warning
                                       :path path' :tag tag})
                        m)

                    :else
                    (assoc m k v)))
                (update m :xml/unknown (fnil conj []) element))))
          {}
          elements))

(defn parse
  "Parse the canonical Hiccup `tree` of an RSS 2.0 document into a feed map.
  The report of findings made on the way, and the foreign namespaces the
  document used, travel as metadata."
  [tree]
  ;; RSS 2.0 only, on purpose. Atom, RSS 1.0 and JSON Feed are not podcast
  ;; formats in practice: the directories require RSS 2.0, and a live sample
  ;; of 844 indexed feeds (2026-09-25) held no Atom feed at all.
  (when-not (= :rss (xml/tag tree))
    (throw (ex-info "Not an RSS 2.0 document" {:type ::unsupported :root (xml/tag tree)})))
  (let [report  (volatile! [])
        channel (or (xml/element tree :channel)
                    (throw (ex-info "No channel element" {:type ::unsupported})))
        version (:version (xml/attrs tree))
        feed    (parse-children :channel (xml/elements channel) [] report)
        others  (remove #{channel} (xml/elements tree))]
    (with-meta (cond-> feed
                 version      (assoc :rss/version version)
                 (seq others) (update :xml/unknown (fnil into []) others))
      {:podcast/report (into (vec (xml/report tree)) @report)
       :xml/namespaces (xml/foreign-namespaces tree)})))

(defn report
  "The findings recorded while parsing `feed`."
  [feed]
  (:podcast/report (meta feed)))

;;;; Emitting

(declare emit-children)

(defn- emit-attrs
  "The attribute map for an element of `entry` from the model map `m`, with
  declared attributes back under their XML names."
  [entry m]
  (let [declared (into {} (for [[k {:keys [type]}] (:attrs entry)
                                :let [v (get m (spec/attr-key k))]
                                :when (some? v)]
                            [k (coerce/format (or type :text) v)]))
        taken    (into #{:content :xml/unknown}
                       (concat (map #(spec/key-for % (spec/children-context entry))
                                    (spec/entries-for (spec/children-context entry)))
                               (map spec/attr-key (keys (:attrs entry)))))
        rest     (into {} (for [[k v] m
                                :when (not (contains? taken k))]
                            [k (str v)]))]
    (merge rest declared)))

(defn- emit-element
  "The Hiccup element of `entry` for the model value `v`."
  [entry v]
  (let [tag  (:tag entry)
        type (spec/content-type entry)]
    (if (spec/map-shaped? entry)
      (-> [tag (emit-attrs entry v)]
          (into (when (contains? v :content)
                  [(coerce/format type (:content v))]))
          (into (emit-children (spec/children-context entry) v)))
      [tag {} (coerce/format type v)])))

(defn- emit-children
  "The Hiccup children of an element in the `parent` context, from the model
  map `m`, in registry order and with the unknown elements last."
  [parent m]
  (concat (for [entry (spec/entries-for parent)
                :let [k (spec/key-for entry parent)]
                :when (contains? m k)
                v (if (= :many (spec/count-for entry parent))
                    (get m k)
                    [(get m k)])]
            (emit-element entry v))
          (:xml/unknown m)))

(defn emit
  "The canonical Hiccup document for `feed`, ready for `xml/emit`. The foreign
  namespaces recorded when the feed was parsed are carried over as metadata
  so that `xml/emit` can declare them."
  [feed]
  (with-meta [:rss {:version (or (:rss/version feed) "2.0")}
              (into [:channel {}] (emit-children :channel (dissoc feed :rss/version)))]
    {:xml/namespaces (:xml/namespaces (meta feed))}))

;;;; Identity

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
      (when url (guid/feed-guid url))))

;;;; Change between two parses

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
