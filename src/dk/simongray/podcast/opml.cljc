(ns dk.simongray.podcast.opml
  "Subscription lists as OPML 2.0, the lingua franca every podcast app
  imports and exports.

  A subscription list is a map:

      {:title         \"My podcasts\"
       :subscriptions [{:text \"Podcasting 2.0\" :xml-url \"https://…/pc20.xml\"
                        :html-url \"https://podcastindex.org\" :path [\"Tech\"]}]}

  Folders in the OPML become the `:path` of the subscriptions inside them,
  so the list stays flat and easy to merge; `emit` rebuilds the folders."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.xml :as xml]))

(defn- subscription
  "The subscription for an `outline` element with an xmlUrl, under `path`."
  [path outline]
  (let [attrs (xml/attrs outline)]
    (cond-> (into {} (for [[k v] attrs
                           :when (not (namespace k))]
                       [(xml/attr-key k) v]))
      (seq path) (assoc :path path))))

(defn- outlines
  "The subscriptions in `element`'s outline children, recursing into folders."
  [path element]
  (mapcat (fn [outline]
            (let [{:keys [xmlUrl text title]} (xml/attrs outline)]
              (if xmlUrl
                [(subscription path outline)]
                (outlines (conj path (or text title "")) outline))))
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

(def attr-names
  "OPML attribute name by model key, for the camelCase ones."
  {:xml-url :xmlUrl :html-url :htmlUrl})

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
  "Write the subscription list `list` as an OPML 2.0 document."
  [list]
  (xml/emit
   [:opml {:version "2.0"}
    [:head {} [:title {} (or (:title list) "Subscriptions")]]
    (into [:body {}] (folders (:subscriptions list)))]))

(comment
  (parse "<opml version=\"2.0\"><head><title>Mine</title></head><body>
            <outline text=\"Tech\"><outline type=\"rss\" text=\"PC20\" xmlUrl=\"https://feeds.podcastindex.org/pc20.xml\"/></outline>
            <outline type=\"rss\" text=\"ATP\" xmlUrl=\"https://atp.fm/rss\" htmlUrl=\"https://atp.fm\"/>
          </body></opml>")
  (println (emit *1))
  #_.)
