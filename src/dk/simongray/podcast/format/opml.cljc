(ns dk.simongray.podcast.format.opml
  "Subscription lists as OPML 2.0, the format that every podcast app
  imports and exports.

  A subscription list is a map:

      {:title         \"My podcasts\"
       :date-created  #inst \"…\" ; and the other head elements
       :subscriptions [{:text     \"A show\"
                        :xml-url  \"https://example.org/feed.xml\"
                        :html-url \"https://example.org\"
                        :path     [\"Tech\"]
                        :category [\"Daily\" \"/News/Denmark\"]
                        :created  #inst \"…\"}]
       :includes      [{:text \"A list\"
                        :url  \"https://example.org/list.opml\"
                        :path [\"News\"]}]}

  The folders of the OPML become the :path of the subscriptions inside
  them, and back again. In an outline:

  - the categories become a vector, which OPML writes as one
    comma-separated attribute of tags and slash-delimited paths
  - the time it was created becomes an instant, as do the dates of the
    head
  - any other attribute stays a string
  - the episodes inside it, as Overcast's extended export writes them,
    become its :episodes, maps of their attributes, e.g.
    {:enclosure-url \"…\" :played \"1\" :progress \"754\"}

  An outline that includes another OPML list becomes one of the :includes,
  with the :path of its folders. It has the type include, or the type link
  to a URL that ends in .opml."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.names :as names]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.qname-hiccup :as xml]
            [dk.simongray.wary-fetch.async :as async]))

(def ^:no-doc head-elements
  "The head elements of OPML 2.0 besides the title that a subscription list
  holds, by key, in the order of the specification."
  [[:date-created :dateCreated]
   [:date-modified :dateModified]
   [:owner-name :ownerName]
   [:owner-email :ownerEmail]
   [:owner-id :ownerId]
   [:docs :docs]])

(def ^:no-doc attr-names
  "The OPML attribute name of each model key that's camelCase in OPML."
  {:xml-url :xmlUrl :html-url :htmlUrl})

(defn ^:no-doc outline-key
  "The model key of the outline attribute `k`, kebab-cased. The camelCase
  ones match in any case, since some apps write xmlurl."
  [k]
  (let [lower (str/lower-case (name k))]
    (or (some (fn [[key attr]]
                (when (= (str/lower-case (name attr)) lower)
                  key))
              attr-names)
        (names/attr-key k))))

(defn- attributes
  "The attributes of the `outline` element by key, without those of other
  namespaces."
  [outline]
  (into {} (for [[k v] (xml/attrs outline)
                 :when (not (namespace k))]
             [(outline-key k) v])))

;; Overcast's extended export, as of October 2026: the episodes that the
;; listener played, started or deleted, as outlines of the type
;; podcast-episode inside the outline of their feed
(defn- episodes
  "The attributes of the episode outlines inside the `outline` of a feed,
  as Overcast exports them."
  [outline]
  (into []
        (comp (map attributes)
              (filter #(= "podcast-episode" (some-> (:type %) str/lower-case))))
        (xml/elements outline :outline)))

(defn- subscription
  "The subscription of an `outline` with an xmlUrl, under `path`, from its
  attributes `m`. It has the attributes by key, the categories as a vector,
  the time it was created as an instant when it reads as one, and the
  :episodes inside it."
  [path outline m]
  (let [categories (when (string? (:category m))
                     (->> (str/split (:category m) #",")
                          (into [] (comp (map str/trim) (remove str/blank?)))
                          not-empty))
        inside     (episodes outline)]
    (cond-> (dissoc m :category)
      (seq path)             (assoc :path path)
      categories             (assoc :category categories)
      (seq inside)           (assoc :episodes inside)
      (string? (:created m)) (update :created #(or (time/parse-rfc2822 %) %)))))

(defn- inclusion
  "The OPML list that an outline with the attributes `m` includes, when
  it's an inclusion: the type include, or the type link to a URL that ends
  in .opml."
  [{:keys [type url text title] :as m}]
  (let [type (some-> type str/lower-case)]
    (when (and (not (str/blank? url))
               (or (= "include" type)
                   (and (= "link" type)
                        (str/ends-with? (str/lower-case url) ".opml"))))
      (cond-> {:url url}
        (or text title) (assoc :text (or text title))))))

(defn- outlines
  "The subscriptions and the inclusions in the outline children of
  `element`, under the `path` of their folders, recursing into folders.

  An outline is a subscription when its xmlUrl isn't blank, and an
  inclusion when it includes another list. An inclusion is marked
  ::include."
  [path element]
  (mapcat (fn [outline]
            (let [{:keys [xml-url text title] :as m} (attributes outline)
                  included (inclusion m)]
              (cond
                (not (str/blank? xml-url))
                [(subscription path outline m)]

                included
                [(cond-> (assoc included ::include true)
                   (seq path) (assoc :path path))]

                :else
                (outlines (conj path (or text title "")) outline))))
          (xml/elements element :outline)))

(defn parse
  "The subscription list of the OPML document `s`. It throws when `s` isn't
  XML, or its root element isn't opml."
  [s]
  (let [tree     (xml/parse s)
        _        (when-not (= :opml (xml/tag tree))
                   (throw (ex-info "Not an OPML document"
                                   {:type ::unsupported :root (xml/tag tree)})))
        head     (xml/element tree :head)
        body     (xml/element tree :body)
        text     #(some-> head (xml/element %) xml/text str/trim not-empty)
        title    (text :title)
        entries  (outlines [] body)
        includes (into []
                       (comp (filter ::include) (map #(dissoc % ::include)))
                       entries)
        value    (fn [k v]
                   (if (#{:date-created :date-modified} k)
                     (or (time/parse-rfc2822 v) v)
                     v))]
    (into (cond-> {:subscriptions (into [] (remove ::include) entries)}
            title          (assoc :title title)
            (seq includes) (assoc :includes includes))
          (for [[k tag] head-elements
                :let  [v (text tag)]
                :when v]
            [k (value k v)]))))

(defn try-parse
  "Parse the OPML string `s` with the `opts` below, and give a map of the
  :list, or of the :error when it isn't OPML.

  The error is the diagnostic :fetch/malformed with the :reason, for a list
  that came from somewhere else, such as a server. The `opts` give the
  :url of the list for the diagnostic. A platform without an XML parser
  isn't the fault of the list, so that error is still thrown."
  ([s]
   (try-parse s {}))
  ([s {:keys [url] :as opts}]
   {:pre [(map? opts)]}
   (try
     {:list (parse s)}
     (catch #?(:clj Exception :cljs :default) e
       (when (= ::xml/no-parser (:type (ex-data e)))
         (throw e))
       (let [malformed (cond-> {:id     :fetch/malformed
                                :reason (async/explain e)}
                         url (assoc :url url))]
         (diagnostics/failed malformed))))))

(defn ^:no-doc text-of
  "The OPML text of the value `v`:

  - an instant in the date format of RFC 822
  - categories comma-separated, with a comma inside one as an underscore,
    since OPML can't escape it
  - anything else as a string"
  [v]
  (cond
    (inst? v)       (time/format-rfc2822 v)
    (sequential? v) (str/join "," (map #(str/replace (str %) "," "_") v))
    :else           (str v)))

(defn- attrs-of
  "The OPML attributes of the map `m`, the camelCase names of its keys
  with the text of each value that isn't nil."
  [m]
  (into {}
        (for [[k v] m
              :when (some? v)]
          [(get attr-names k (keyword (names/camel (name k)))) (text-of v)])))

(defn- outline
  "The outline element of `entry`: a subscription, with the outlines of
  its :episodes inside, or an inclusion when it's marked ::include."
  [{:keys [url text episodes] :as entry}]
  (if (::include entry)
    [:outline {:type "include" :text (or text url) :url url}]
    (into [:outline (merge {:type "rss"}
                           (attrs-of (dissoc entry :path :episodes)))]
          (for [episode episodes]
            [:outline (merge {:type "podcast-episode"} (attrs-of episode))]))))

(defn- folders
  "The `entries` nested into outline folders by their :path, in order.
  Consecutive entries in the same folder share it."
  [entries]
  (mapcat (fn [run]
            (if-let [folder (first (:path (first run)))]
              [(into [:outline {:text folder}]
                     (folders (map #(update % :path (comp vec rest)) run)))]
              (map outline run)))
          (partition-by (comp first :path) entries)))

(defn emit
  "The OPML 2.0 document of the `subscription-list`, with the inclusions
  after the subscriptions of their folder.

  OPML separates the categories of an outline with commas, and it can't
  escape a comma. So a comma in a category goes out as an underscore."
  [subscription-list]
  (let [includes (map #(assoc % ::include true) (:includes subscription-list))
        entries  (concat (:subscriptions subscription-list) includes)
        top      (comp first :path)
        tops     (distinct (map top entries))
        by-top   (group-by top entries)
        title    (or (:title subscription-list) "Subscriptions")
        head     (for [[k tag] head-elements
                       :let  [v (get subscription-list k)]
                       :when (some? v)]
                   [tag {} (text-of v)])]
    (xml/emit
     [:opml {:version "2.0"}
      (into [:head {} [:title {} title]] head)
      (into [:body {}] (folders (mapcat by-top tops)))])))

(comment
  (parse "<opml version=\"2.0\"><head><title>Mine</title></head><body>
          <outline text=\"Tech\">
            <outline type=\"rss\" text=\"A\" xmlUrl=\"https://example.org/a\"/>
          </outline>
          <outline type=\"rss\" text=\"B\" xmlUrl=\"https://example.org/b\"
                   htmlUrl=\"https://example.org\"/>
          </body></opml>")
  (println (emit *1))
  #_.)
