(ns recommendations
  "What library/recommend picks for the newest episode of each named feed
  of the corpus, so that a change to the picks can be checked on the same
  episodes each time: the words that its query looks for, and each pick
  with the words of the query that it holds. `named` builds the library
  once, and `print-cut` shows the notes of an episode without the text
  that its show repeats."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [corpus]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]))

(defn library-of
  "A library with an index of the named feeds of the corpus that have
  episodes."
  []
  (reduce (fn [lib [name url]]
            (let [file (io/file corpus/dir (str name ".xml"))
                  feed (when (.exists file) (rss/parse (slurp file)))]
              (cond-> lib
                (seq (:rss/items feed)) (library/subscribe url feed))))
          (library/make "lab" {:config {:search? true}})
          (sort corpus/feeds)))

(def named
  "A library of the named feeds of the corpus."
  (delay (library-of)))

(defn newest
  "The newest episode of each show of `lib`, as pairs of its feed and
  item, by the title of the show."
  [lib]
  (->> (keys (:feeds lib))
       (map #(first (library/episodes lib {:feed-guids [%] :order :newest :limit 1})))
       (sort-by (comp resolve/title first))))

(defn print-picks
  "Print the words that a query for the `episode` of `lib`, a pair of a feed
  and an item, looks for, the best first, and its picks with the `opts` of
  library/recommend."
  [lib [feed item :as episode] opts]
  (let [found (library/recommend lib [(apply library/episode-id lib episode)] opts)]
    (println (str "\n## " (resolve/title feed) ": " (resolve/title item)))
    (println (str "   " (str/join ", " (map (fn [[term weight]] (format "%s %.2f" term weight))
                                            (take 12 (:terms (meta found)))))))
    (doseq [{:keys [feed item terms]} found]
      (println (str "   - " (resolve/title item) " (" (resolve/title feed) "): "
                    (str/join " " terms))))))

(defn print-cut
  "Print the notes of the `episode` of `lib`, a pair of a feed and an item,
  without the text that its show repeats."
  [lib [feed item]]
  (println (get-in (feed.search/item-document
                    feed item {:url      (get (library/urls lib) feed)
                               :repeated (feed.search/repeated-runs feed)})
                   [:fields :description])))

(comment
  (def lib @named)
  ;; the picks of the Episode page of the app
  (run! #(print-picks lib % {:limit 5 :max-per-show 1}) (newest lib))
  (print-cut lib (first (newest lib)))
  #_.)
