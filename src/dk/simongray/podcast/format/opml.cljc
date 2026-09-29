(ns dk.simongray.podcast.format.opml
  "Subscription lists as OPML 2.0, the lingua franca every podcast app
  imports and exports.

  A subscription list is a map:

      {:title         \"My podcasts\"
       :date-created  #inst \"…\"  ; and the other head elements of `head-elements`
       :subscriptions [{:text \"A show\" :xml-url \"https://example.org/feed.xml\"
                        :html-url \"https://example.org\" :path [\"Tech\"]
                        :category [\"Daily\" \"/News/Denmark\"] :created #inst \"…\"}]
       :includes      [{:text \"A list\" :url \"https://example.org/list.opml\" :path [\"News\"]}]}

  Folders in the OPML become the `:path` of the subscriptions inside them;
  `emit` rebuilds the folders. The categories of an outline, which OPML
  writes as one comma-separated attribute of tags and slash-delimited
  paths, become a vector, and the time it was created an instant, as do
  the dates of the head. Any other attribute stays a string. An outline
  that includes another OPML list, by the type include or by a link to a
  URL that ends in .opml, becomes one of the `:includes`, with the `:path`
  of its folders as a subscription has."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.xml :as xml]))

(def ^:no-doc head-elements
  "The head elements of OPML 2.0 besides the title that a subscription list
  holds, by key, in the order of the specification."
  [[:date-created :dateCreated] [:date-modified :dateModified] [:owner-name :ownerName]
   [:owner-email :ownerEmail] [:owner-id :ownerId] [:docs :docs]])

(def ^:no-doc attr-names
  "OPML attribute name by model key, for the camelCase ones."
  {:xml-url :xmlUrl :html-url :htmlUrl})

(defn ^:no-doc outline-key
  "The model key of the outline attribute `k`: the camelCase ones in any
  case, as some apps write xmlurl, and the rest kebab-cased."
  [k]
  (or (some (fn [[key attr]] (when (= (str/lower-case (name attr)) (str/lower-case (name k))) key))
            attr-names)
      (xml/attr-key k)))

(defn- subscription
  "The subscription for an `outline` element with an xmlUrl, under `path`:
  its attributes by key, the categories as a vector and the time it was
  created as an instant when it reads as one."
  [path outline]
  (let [m          (into {} (for [[k v] (xml/attrs outline)
                                  :when (not (namespace k))]
                              [(outline-key k) v]))
        categories (when (string? (:category m))
                     (not-empty (into [] (comp (map str/trim) (remove str/blank?)) (str/split (:category m) #","))))]
    (cond-> (dissoc m :category)
      (seq path)             (assoc :path path)
      categories             (assoc :category categories)
      (string? (:created m)) (update :created #(or (time/parse-rfc2822 %) %)))))

(defn- inclusion
  "The OPML list that `outline` includes, when it is an inclusion: the type
  include, or the type link to a URL that ends in .opml."
  [outline]
  (let [{:keys [type url text title]} (into {} (for [[k v] (xml/attrs outline)] [(outline-key k) v]))
        type (some-> type str/lower-case)]
    (when (and (not (str/blank? url))
               (or (= "include" type)
                   (and (= "link" type) (str/ends-with? (str/lower-case url) ".opml"))))
      (cond-> {:url url}
        (or text title) (assoc :text (or text title))))))

(defn- outlines
  "The subscriptions and the inclusions in `element`'s outline children,
  recursing into folders, each under the `:path` of its folders: an
  outline is a subscription when it has an xmlUrl that is not blank, and
  an inclusion, marked `::include`, as `inclusion` says."
  [path element]
  (mapcat (fn [outline]
            (let [{:keys [text title]} (xml/attrs outline)
                  url      (some (fn [[k v]] (when (= :xml-url (outline-key k)) v)) (xml/attrs outline))
                  included (inclusion outline)]
              (cond
                (not (str/blank? url)) [(subscription path outline)]
                included               [(cond-> (assoc included ::include true) (seq path) (assoc :path path))]
                :else                  (outlines (conj path (or text title "")) outline))))
          (xml/elements element :outline)))

(defn parse
  "Read the OPML document `s` into a subscription list."
  [s]
  (let [tree     (xml/parse s)
        head     (xml/element tree :head)
        body     (xml/element tree :body)
        text     #(some-> head (xml/element %) xml/text str/trim not-empty)
        title    (text :title)
        entries  (outlines [] body)
        includes (into [] (comp (filter ::include) (map #(dissoc % ::include))) entries)]
    (into (cond-> {:subscriptions (into [] (remove ::include) entries)}
            title          (assoc :title title)
            (seq includes) (assoc :includes includes))
          (for [[k tag] head-elements
                :let  [v (text tag)]
                :when v]
            [k (if (#{:date-created :date-modified} k) (or (time/parse-rfc2822 v) v) v)]))))

(defn ^:no-doc text-of
  "The OPML text of the value `v`: an instant in the date format of RFC 822,
  categories comma-separated, a comma inside one as an underscore since
  OPML cannot escape it, and anything else as a string."
  [v]
  (cond
    (inst? v)       (time/format-rfc2822 v)
    (sequential? v) (str/join "," (map #(str/replace (str %) "," "_") v))
    :else           (str v)))

(defn- outline
  "The outline element for `entry`: a subscription, or an inclusion when it
  is marked `::include`."
  [{:keys [url text] :as entry}]
  (if (::include entry)
    [:outline {:type "include" :text (or text url) :url url}]
    [:outline (into {:type "rss"}
                    (for [[k v] (dissoc entry :path)
                          :when (some? v)]
                      [(get attr-names k (keyword (name k))) (text-of v)]))]))

(defn- folders
  "Nest the `entries` into outline folders by their `:path`, keeping their
  order; consecutive entries in the same folder share it."
  [entries]
  (mapcat (fn [run]
            (if-let [folder (first (:path (first run)))]
              [(into [:outline {:text folder}]
                     (folders (map #(update % :path (comp vec rest)) run)))]
              (map outline run)))
          (partition-by (comp first :path) entries)))

(defn emit
  "Write the `subscription-list` (see `parse`) as an OPML 2.0 document, the
  inclusions after the subscriptions of their folder.

  OPML separates the categories of an outline with commas, and it cannot
  escape a comma. So a comma in a category goes out as `_`."
  [subscription-list]
  (let [entries (concat (:subscriptions subscription-list)
                        (map #(assoc % ::include true) (:includes subscription-list)))
        by-top  (group-by (comp first :path) entries)]
    (xml/emit
     [:opml {:version "2.0"}
      (into [:head {} [:title {} (or (:title subscription-list) "Subscriptions")]]
            (for [[k tag] head-elements
                  :let  [v (get subscription-list k)]
                  :when (some? v)]
              [tag {} (text-of v)]))
      (into [:body {}] (folders (mapcat by-top (distinct (map (comp first :path) entries)))))])))

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
