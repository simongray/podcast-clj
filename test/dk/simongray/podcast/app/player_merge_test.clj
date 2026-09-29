(ns dk.simongray.podcast.app.player-merge-test
  "The merge of player states is commutative, associative and idempotent
  for whatever states devices can produce, an app's own stamped entries
  included, which is what lets syncing apply it in any order and any
  number of times."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.podcast.app.player :as player]))

(def instants
  "A handful of instants close together, so that ties happen."
  (gen/fmap #(java.util.Date. (* 1000 (long %))) (gen/choose 0 5)))

(def feed-guids
  (gen/elements ["feed-a" "feed-b" "feed-c"]))

(def episode-ids
  (gen/elements [["feed-a" "a1"] ["feed-a" "a2"] ["feed-b" "b1"]]))

(defn- operation
  "A random operation on a state, as a function of the state."
  []
  (gen/one-of
   [(gen/fmap (fn [[g t]] #(player/subscribe % g {:url (str "https://" g "/rss") :title g} t)) (gen/tuple feed-guids instants))
    (gen/fmap (fn [[g t]] #(player/unsubscribe % g t)) (gen/tuple feed-guids instants))
    (gen/fmap (fn [[g t n]] #(player/configure % g {:skip-intro n} t)) (gen/tuple feed-guids instants gen/nat))
    (gen/fmap (fn [[id p t]] #(player/progress % id p 3600 t)) (gen/tuple episode-ids (gen/choose 0 3600) instants))
    (gen/fmap (fn [[id t]] #(player/mark-played % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id t]] #(player/enqueue % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id t]] #(player/play-next % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id t]] #(player/dequeue % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id n t]] #(player/move % id n t)) (gen/tuple episode-ids (gen/choose 0 3) instants))
    (gen/fmap (fn [[id n t]] #(player/skip % id n t)) (gen/tuple episode-ids (gen/choose -60 60) instants))
    (gen/fmap (fn [[n t]] #(player/prefer % {:skip-forward n} t)) (gen/tuple (gen/elements [15 30 45]) instants))
    (gen/fmap (fn [t] #(player/advance % t)) instants)
    (gen/fmap (fn [[n t]] #(player/identify % {:name n} t)) (gen/tuple (gen/elements ["Phone" "Laptop"]) instants))
    (gen/fmap (fn [[d t]] #(player/retire % d t)) (gen/tuple (gen/elements ["phone" "laptop" "car"]) instants))
    (gen/fmap (fn [[id t]] #(player/star % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id t]] #(player/unstar % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id s t]] #(player/bookmark % id s (str "at " s) t)) (gen/tuple episode-ids (gen/elements [30 754]) instants))
    (gen/fmap (fn [[id s t]] #(player/unbookmark % id s t)) (gen/tuple episode-ids (gen/elements [30 754]) instants))
    (gen/fmap (fn [[id s d t]] #(player/clip % id s d (str "from " s) t))
              (gen/tuple episode-ids (gen/elements [30 754]) (gen/elements [10 42.5]) instants))
    (gen/fmap (fn [[id s t]] #(player/unclip % id s t)) (gen/tuple episode-ids (gen/elements [30 754]) instants))
    (gen/fmap (fn [[id n t]] #(player/rate-episode % id n t)) (gen/tuple episode-ids (gen/choose 1 5) instants))
    (gen/fmap (fn [[id n best t]] #(player/rate-episode % id n t {:scale {:worst 0 :best best}}))
              (gen/tuple episode-ids (gen/choose 0 1) (gen/elements [1 100]) instants))
    (gen/fmap (fn [[id t]] #(player/unrate-episode % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id d t]] #(:state (player/step % [:chapter id d {:chapters [{:start-time 0} {:start-time 90}]}] t)))
              (gen/tuple episode-ids (gen/elements [:next :previous]) instants))
    (gen/fmap (fn [[id t]] #(player/write % [:inbox id] (constantly {:removed? false}) t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[id t]] #(player/dismiss % id t)) (gen/tuple episode-ids instants))
    (gen/fmap (fn [[g f t]] #(player/configure % g {:folder f} t)) (gen/tuple feed-guids (gen/elements [nil "News"]) instants))
    (gen/fmap (fn [[f t]] #(player/rename-folder % f "Tech" t)) (gen/tuple (gen/elements [nil "News"]) instants))
    (gen/fmap (fn [[g x t]] #(player/tag % g x t)) (gen/tuple feed-guids (gen/elements ["Tech" "Daily"]) instants))
    (gen/fmap (fn [[g x t]] #(player/untag % g x t)) (gen/tuple feed-guids (gen/elements ["Tech" "Daily"]) instants))
    (gen/fmap (fn [[u f t]] #(player/follow-list % u {:title u :folder f} t))
              (gen/tuple (gen/elements ["https://l/1.opml" "https://l/2.opml"]) (gen/elements [nil "Picks"]) instants))
    (gen/fmap (fn [[u t]] #(player/unfollow-list % u t)) (gen/tuple (gen/elements ["https://l/1.opml" "https://l/2.opml"]) instants))
    (gen/fmap (fn [[n id t]] #(player/add-to-playlist % n id t)) (gen/tuple (gen/elements ["P" "Q"]) episode-ids instants))
    (gen/fmap (fn [[n id t]] #(player/remove-from-playlist % n id t)) (gen/tuple (gen/elements ["P" "Q"]) episode-ids instants))
    (gen/fmap (fn [[id i t]] #(player/move-in-playlist % "P" id i t)) (gen/tuple episode-ids (gen/choose 0 3) instants))
    (gen/fmap (fn [[to t]] #(player/rename-playlist % "P" to t)) (gen/tuple (gen/elements [nil "Q"]) instants))
    (gen/fmap (fn [[id p t]] #(:state (player/step % [:progress id p 3600] t))) (gen/tuple episode-ids (gen/choose 0 3600) instants))
    (gen/fmap (fn [[n t]] #(player/save-filter % n {:where {:starred? true}} t)) (gen/tuple (gen/elements ["A" "B"]) instants))
    (gen/fmap (fn [[n t]] #(player/remove-filter % n t)) (gen/tuple (gen/elements ["A" "B"]) instants))
    (gen/fmap (fn [[k t]] #(assoc-in % [:app/notes k] {:text k :updated-at t :device (:device %)}))
              (gen/tuple (gen/elements ["n1" "n2"]) instants))]))

(def states
  "A device's state after a few random operations."
  (gen/fmap (fn [[device ops]] (reduce (fn [s op] (op s)) (player/state device) ops))
            (gen/tuple (gen/elements ["phone" "laptop" "car"]) (gen/vector (operation) 0 8))))

(defn- same?
  "Whether two merged states agree on everything but whose device they are."
  [x y]
  (= (dissoc x :device) (dissoc y :device)))

(defspec merge-is-commutative 200
  (prop/for-all [a states b states]
    (same? (player/merge a b) (player/merge b a))))

(defspec merge-is-associative 200
  (prop/for-all [a states b states c states]
    (same? (player/merge (player/merge a b) c) (player/merge a (player/merge b c)))))

(defspec merge-is-idempotent 200
  (prop/for-all [a states b states]
    (let [m (player/merge a b)]
      (and (same? m (player/merge m a))
           (same? m (player/merge m b))
           (= m (player/merge m m))))))
