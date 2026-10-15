(ns dk.simongray.podcast.feed
  "The feed model: canonical Hiccup in, maps out, and back again, by a tag
  registry.

  A feed is the channel map itself, with the items under :rss/items and
  the live items under :podcast/live-items. In the map:

  - a scalar element becomes a coerced scalar
  - an element with attributes or children becomes a map
  - a repeatable element becomes a vector
  - an element that the registry doesn't know stays as Hiccup under
    :xml/unknown

  Nothing throws on a bad value, which stays a string, and the report says
  so. The report is in the metadata of the result under :podcast/report."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.impl :as impl]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.qname-hiccup :as xml]))

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
         feed    (impl/parse-children ctx :channel (xml/elements channel) [])
         ;; by identity: a set of the channel would hash all of the feed
         others  (remove #(identical? % channel) (xml/elements tree))]
     (impl/note-leftovers! ctx tree channel)
     (with-meta (cond-> feed
                  version      (assoc :rss/version version)
                  (seq others) (update :xml/unknown (fnil into []) others))
       {:podcast/report @(:report ctx)
        :xml/namespaces (xml/namespaces tree)}))))

(defn report
  "The diagnostics recorded while parsing `feed`, each with its message from
  the catalogue of diagnostics."
  [feed]
  (mapv diagnostics/describe (:podcast/report (meta feed))))

(defn normalize
  "The `feed` in the shape of the model, by the :registry of `opts`,
  spec/default by default.

  It expands the shorthand that a producer can write, e.g.:

      {:rss/guid \"x\"}            ; for {:rss/guid {:content \"x\"}}
      {:podcast/transcripts {…}}  ; for {:podcast/transcripts [{…}]}

  A string where the model takes another type is read as parse reads the
  text of XML, e.g. a date, a duration such as \"30:00\" or \"no\" for false,
  and its report has :feed/string-value for each. A string that doesn't
  read stays, so that the validator reports it. A parsed feed comes back
  unchanged."
  ([feed]
   (normalize feed {}))
  ([feed opts]
   {:pre [(map? opts)]}
   (let [[feed notes] (impl/normalized feed opts)]
     (cond-> feed
       (seq notes) (vary-meta update :podcast/report (fnil into []) notes)))))

(defn emit
  "The canonical Hiccup document of `feed`, with the `opts` below:

  - :registry, the tag registry, spec/default by default
  - :namespaces, a map from alias to URI for the elements of namespaces
    that the feed wasn't parsed with, or for another URI of one that it was

  It takes the shorthand that a producer can write, and leaves out nil
  values. The known namespaces, those of :namespaces, and the foreign
  namespaces recorded when the feed was parsed go along as metadata under
  :xml/namespaces, for the document to declare."
  ([feed]
   (emit feed {}))
  ([feed {:keys [registry namespaces] :or {registry spec/default} :as opts}]
   {:pre [(map? opts)]}
   (let [registry (spec/registry registry)
         ctx      {:registry registry :plans (volatile! {})}
         feed     (normalize feed {:registry registry})
         version  (or (:rss/version feed) "2.0")
         children (impl/emit-children ctx :channel (dissoc feed :rss/version))]
     (with-meta [:rss {:version version}
                 (into [:channel {}] children)]
       {:xml/namespaces (merge spec/namespaces
                               (:xml/namespaces (meta feed))
                               namespaces)}))))

(defn diff
  "What changed from the feed `before` to the feed `after`, by item identity:

      {:added [item ...] :removed [item ...] :changed [{:before :after} ...]
       :renamed [{:before :after} ...]
       :unchanged 42
       :live-items {:added [...] :removed [...] :changed [...] :unchanged 1}
       :channel-changed? false}

  Items without an identity, with neither a guid nor an enclosure, aren't
  compared, and neither are the later of the items that share one. An
  item that's a removed one under a new guid is :renamed rather than added
  and removed: one with the same enclosure URL, or else the same title and
  a date a day apart at most, when only one item on each side matches. The
  channel counts as changed when anything but its items, live items and
  timestamps differs."
  [before after]
  (let [churn [:rss/items :podcast/live-items
               :rss/last-build-date :rss/pub-date]
        items (impl/item-changes (:rss/items before) (:rss/items after))
        live  (impl/item-changes (:podcast/live-items before)
                                 (:podcast/live-items after))]
    (assoc items
           :live-items       live
           :channel-changed? (not= (apply dissoc before churn)
                                   (apply dissoc after churn)))))

(defn new-episodes
  "The ids of the episodes that the parse `after` of the feed at `url` has
  and the parse `before` hadn't, by the `opts` below, as a map:

      {:added      [[feed-guid item-guid] …]  ; the new ones, oldest first
       :backfilled [[feed-guid item-guid] …]  ; older ones that appeared
       :renamed    [[old-id new-id] …]}       ; those under a new guid

  An episode is new unless it's older than the newest one of `before`, so
  that a host that publishes its back catalogue again, or rewrites guids
  that diff can't pair, adds no old episodes. One without a date is new.
  Without `before`, there are none, since a first parse finds the back
  catalogue. The `opts` are:

  - :only-newer?, false to count every episode that appeared as new"
  ([url before after]
   (new-episodes url before after {}))
  ([url before after {:keys [only-newer?] :or {only-newer? true} :as opts}]
   {:pre [(map? opts)]}
   (if (nil? before)
     {:added [] :backfilled [] :renamed []}
     (let [feed-guid (guid/feed-key after url)
           id        #(vector feed-guid (guid/item-guid %))
           ;; the first two dates say the order, since the items that a
           ;; library keeps of a feed follow those it serves
           [a b]     (keep impl/date-ms (:rss/items after))
           newest    (some->> (seq (keep impl/date-ms (:rss/items before)))
                              (apply max))
           older?    (fn [item]
                       (let [d (impl/date-ms item)]
                         (boolean (and only-newer? newest d (< d newest)))))
           {:keys [added renamed]} (diff before after)
           ordered   (if (and a b (< a b)) added (reverse added))]
       {:added      (mapv id (remove older? ordered))
        :backfilled (mapv id (filter older? ordered))
        :renamed    (mapv (juxt (comp id :before) (comp id :after))
                          renamed)}))))

(defn join-pages
  "One feed of `pages`, the parsed pages of a feed that comes in pages, in
  their order, or nil for no pages.

  The feed is the channel of the first page, with the items of all the
  pages. A feed can gain items while its pages are read, so an item of a
  later page is left out when an earlier page has it.

  The report has the diagnostics of the first page. From a later page, it
  has the diagnostics of the items that the feed takes from the page, and
  the diagnostics without a path. The path of a diagnostic names the item
  where the feed has it."
  [pages]
  (when-let [first-page (first pages)]
    (let [start                  {:items [] :seen #{} :report [] :first? true}
          {:keys [items report]} (reduce #(impl/with-page %1 %2 (report %2))
                                         start
                                         pages)]
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
