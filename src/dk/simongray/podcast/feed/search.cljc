(ns dk.simongray.podcast.feed.search
  "The shows and episodes of feeds as documents for full-text search with
  dk.simongray.drop-in-search.

  Item-document and feed-document make the documents of an episode and of
  a show, and index makes an index of them, which ranks a match in a title
  above one in the notes:

      (def idx (index (feed-documents feed url)))
      (search/query idx \"coffee -decaf\")

  An index of many feeds changes one feed at a time with with-feed and
  without-feed, and located gives each result its feed and item."
  (:require [clojure.string :as str]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.transcript :as transcript]))

(defn- notes
  [x]
  (some-> (resolve/description x) html/text))

(defn- names
  [feed item]
  (let [people (if item
                 (resolve/people feed item)
                 (resolve/people feed))]
    (str/join " " (map :name people))))

(defn- present
  "The entries of `m` whose value isn't blank or empty."
  [m]
  (let [filled? (fn [[_ v]]
                  (if (string? v)
                    (not (str/blank? v))
                    (seq v)))]
    (into {} (filter filled?) m)))

(defn item-document
  "The search document of `item` in `feed`, with the `opts` below.

  Its fields are :title, :show, :description, :people, :keywords,
  :chapters and :transcript. A result has the title, show, guid,
  publication date, duration and URL. The `opts` are:

  - :url, where the feed was fetched, which a feed without a podcast:guid
    needs, or it throws
  - :chapters, the parsed chapters file, instead of the embedded ones
  - :transcript, the parsed transcript"
  ([feed item]
   (item-document feed item {}))
  ([feed item {:keys [url chapters transcript] :as opts}]
   {:pre [(map? opts)]}
   (let [chapters (or chapters (resolve/embedded-chapters item))
         titles   (keep :title (:chapters chapters))
         title    (resolve/title item)
         show     (resolve/title feed)
         id       (guid/episode-id feed url item)]
     (when (nil? (first id))
       (throw (ex-info (str "A feed without a podcast:guid needs :url, or its "
                            "episodes share ids with those of other shows")
                       {:title show})))
     {:id     id
      :fields (present {:title       title
                        :show        show
                        :description (notes item)
                        :people      (names feed item)
                        :keywords    (:itunes/keywords item)
                        :chapters    (str/join " " titles)
                        :transcript  (some-> transcript transcript/text)})
      :stored (into {} (filter (comp some? val))
                    {:title     title
                     :show      show
                     :guid      (guid/item-guid item)
                     :url       url
                     :published (resolve/published item)
                     :duration  (resolve/duration item)})})))

(defn feed-document
  "The search document of the show `feed` itself, by its guid, with the
  :url in `opts` where the feed was fetched, for a feed without a
  podcast:guid.

  Its fields are :title, :description, :author, :people and :categories."
  ([feed]
   (feed-document feed {}))
  ([feed {:keys [url] :as opts}]
   {:pre [(map? opts)]}
   (let [title      (resolve/title feed)
         author     (resolve/author feed)
         categories (map #(str/join " " %) (resolve/categories feed))]
     {:id     (guid/feed-guid feed url)
      :fields (present {:title       title
                        :description (notes feed)
                        :author      author
                        :people      (names feed nil)
                        :categories  (str/join " " categories)})
      :stored (into {} (filter (comp some? val))
                    {:title title :author author :url url})})))

(def boosts
  "How much a match in each field weighs in a query, e.g. a word in a
  title counts three times a word in the notes."
  {:title 3 :show 2 :author 2 :people 2 :chapters 2 :keywords 2
   :categories 1 :description 1 :transcript 1})

(def aliases
  "Other names that a query can give the fields of the documents, e.g.
  notes:clojure for a word in the description."
  {"notes" :description})

(defn index
  "An index of the search documents `docs` for dk.simongray.drop-in-search,
  with boosts and aliases as the defaults of its queries, or an empty one
  to add to."
  ([]
   (index []))
  ([docs]
   (search/index docs {:boosts boosts :aliases aliases})))

(defn feed-documents
  "The search documents of the show `feed`, fetched from `url`, and of each
  of its episodes."
  [feed url]
  (into [(feed-document feed {:url url})]
        (map #(item-document feed % {:url url}))
        (:rss/items feed)))

(defn- feed-guid-of
  "The guid of the show of the document `id`: the id of a show's document,
  or the first of the pair that names an episode."
  [id]
  (if (vector? id) (first id) id))

(defn without-feed
  "The `index` without the documents of the show `feed-guid` and of its
  episodes."
  [index feed-guid]
  (->> (search/ids index)
       (filter #(= feed-guid (feed-guid-of %)))
       (apply search/remove index)))

(defn with-feed
  "The `index` with the documents of the show `feed`, fetched from `url`,
  and of its episodes, in place of those it held of the show before."
  [index feed url]
  (apply search/add
         (without-feed index (guid/feed-guid feed url))
         (feed-documents feed url)))

(defn feed-guids
  "The guids of the shows whose documents `index` holds, of the show itself
  or of an episode."
  [index]
  (into #{} (map feed-guid-of) (search/ids index)))

(defn located
  "A function that gives a result of a query of an index of `feeds`, a map
  of feed guid to feed, with its :feed, and for an episode its :item too.

  It looks the episodes of a feed up once, so give it once for all the
  results of a query."
  [feeds]
  (let [items (memoize
               (fn [feed-guid]
                 (into {}
                       (map (juxt guid/item-guid identity))
                       (:rss/items (get feeds feed-guid)))))]
    (fn [{:keys [id] :as result}]
      (if (vector? id)
        (let [[feed-guid item-guid] id]
          (assoc result
                 :feed (get feeds feed-guid)
                 :item (get (items feed-guid) item-guid)))
        (assoc result :feed (get feeds id))))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast.feed.rss :as rss]
              '[dk.simongray.drop-in-search.queries :as search.queries])
     (def f (rss/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (def idx (index (feed-documents f "https://example.com/feed.xml")))
     (search/query idx "futu")
     (search/query idx "title:future -present")
     (def first-notes (html/text (resolve/description (first (:rss/items f)))))
     (search.queries/snippet first-notes "future" (search/query-opts idx))
     #_.))
