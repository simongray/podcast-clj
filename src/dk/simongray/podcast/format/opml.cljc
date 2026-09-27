(ns dk.simongray.podcast.format.opml
  "Subscription lists as OPML 2.0, the lingua franca every podcast app
  imports and exports.

  A subscription list is a map:

      {:title         \"My podcasts\"
       :subscriptions [{:text \"A show\" :xml-url \"https://example.org/feed.xml\"
                        :html-url \"https://example.org\" :path [\"Tech\"]}]}

  Folders in the OPML become the `:path` of the subscriptions inside them;
  `emit` rebuilds the folders."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.xml :as xml]))

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
  "The subscription for an `outline` element with an xmlUrl, under `path`."
  [path outline]
  (let [attrs (xml/attrs outline)]
    (cond-> (into {} (for [[k v] attrs
                           :when (not (namespace k))]
                       [(outline-key k) v]))
      (seq path) (assoc :path path))))

(defn- outlines
  "The subscriptions in `element`'s outline children, recursing into folders:
  an outline is a subscription when it has an xmlUrl that is not blank."
  [path element]
  (mapcat (fn [outline]
            (let [{:keys [text title]} (xml/attrs outline)
                  url (some (fn [[k v]] (when (= :xml-url (outline-key k)) v)) (xml/attrs outline))]
              (if (str/blank? url)
                (outlines (conj path (or text title "")) outline)
                [(subscription path outline)])))
          (xml/elements element :outline)))

(defn parse
  "Read the OPML document `s` into a subscription list."
  [s]
  (let [tree  (xml/parse s)
        head  (xml/element tree :head)
        body  (xml/element tree :body)
        title (some-> head (xml/element :title) xml/text str/trim)]
    (cond-> {:subscriptions (vec (outlines [] body))}
      (seq title) (assoc :title title))))

(defn- outline
  "The outline element for `subscription`."
  [subscription]
  [:outline (into {:type "rss"}
                  (for [[k v] (dissoc subscription :path)
                        :when (some? v)]
                    [(get attr-names k (keyword (name k))) (str v)]))])

(defn- folders
  "Nest the `subscriptions` into outline folders by their `:path`, keeping
  their order; consecutive subscriptions in the same folder share it."
  [subscriptions]
  (mapcat (fn [run]
            (if-let [folder (first (:path (first run)))]
              [(into [:outline {:text folder}]
                     (folders (map #(update % :path (comp vec rest)) run)))]
              (map outline run)))
          (partition-by (comp first :path) subscriptions)))

(defn emit
  "Write the `subscription-list` (see `parse`) as an OPML 2.0 document."
  [subscription-list]
  (xml/emit
   [:opml {:version "2.0"}
    [:head {} [:title {} (or (:title subscription-list) "Subscriptions")]]
    (into [:body {}] (folders (:subscriptions subscription-list)))]))

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
