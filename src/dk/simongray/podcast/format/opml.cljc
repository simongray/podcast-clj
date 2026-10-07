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
  them, and emit rebuilds the folders. In an outline:

  - the categories become a vector, which OPML writes as one
    comma-separated attribute of tags and slash-delimited paths
  - the time it was created becomes an instant, as do the dates of the
    head
  - any other attribute stays a string

  An outline that includes another OPML list becomes one of the :includes,
  with the :path of its folders. It has the type include, or the type link
  to a URL that ends in .opml."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.util.xml :as xml]))

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
        (xml/attr-key k))))

(defn- subscription
  "The subscription of the `outline` element with an xmlUrl, under `path`.
  It has the attributes by key, the categories as a vector, and the time
  it was created as an instant when it reads as one."
  [path outline]
  (let [m          (into {} (for [[k v] (xml/attrs outline)
                                  :when (not (namespace k))]
                              [(outline-key k) v]))
        categories (when (string? (:category m))
                     (->> (str/split (:category m) #",")
                          (into [] (comp (map str/trim) (remove str/blank?)))
                          not-empty))]
    (cond-> (dissoc m :category)
      (seq path)             (assoc :path path)
      categories             (assoc :category categories)
      (string? (:created m)) (update :created #(or (time/parse-rfc2822 %) %)))))

(defn- inclusion
  "The OPML list that `outline` includes, when it's an inclusion: the type
  include, or the type link to a URL that ends in .opml."
  [outline]
  (let [attrs (into {} (for [[k v] (xml/attrs outline)]
                         [(outline-key k) v]))
        {:keys [type url text title]} attrs
        type  (some-> type str/lower-case)]
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
  inclusion when inclusion says so. An inclusion is marked ::include."
  [path element]
  (mapcat (fn [outline]
            (let [{:keys [text title] :as attrs} (xml/attrs outline)
                  url      (some (fn [[k v]]
                                   (when (= :xml-url (outline-key k)) v))
                                 attrs)
                  included (inclusion outline)]
              (cond
                (not (str/blank? url))
                [(subscription path outline)]

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
  "Parse the OPML string `s` as parse does, with the `opts` below, and give
  a map of the :list, or of the :error where parse throws.

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
         {:error (diagnostics/describe malformed)})))))

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

(defn- outline
  "The outline element of `entry`: a subscription, or an inclusion when
  it's marked ::include."
  [{:keys [url text] :as entry}]
  (if (::include entry)
    [:outline {:type "include" :text (or text url) :url url}]
    [:outline (into {:type "rss"}
                    (for [[k v] (dissoc entry :path)
                          :when (some? v)]
                      [(get attr-names k (keyword (name k))) (text-of v)]))]))

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
  "The OPML 2.0 document of the `subscription-list` that parse gives, with
  the inclusions after the subscriptions of their folder.

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
