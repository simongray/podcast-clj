(ns dk.simongray.podcast.util.crdt.list
  "Ordered collections in a state of dk.simongray.podcast.util.crdt, such
  as a queue, which devices reorder in any order without conflict.

  A collection is a map of id to an entry with a :rank, a string that
  sorts as the place of the entry, as util.rank makes them. A move can go
  in a second map of the same ids, whose entries hold only the new rank,
  so that a move never brings back what another device removed. A schema
  marks such collections :ranked?, which problem checks.

      {:queue       {:depth 1 :ranked? true}
       :queue-moves {:depth 1 :ranked? true}}

  The comments name the source of each part, and where it departs from it:

  - Wallace, Realtime Editing of Ordered Sequences, Figma, 2017,
    https://www.figma.com/blog/realtime-editing-of-ordered-sequences/
  - Kleppmann, Gomes, Mulligan and Beresford, Interleaving anomalies in
    collaborative text editors, PaPoC 2019,
    https://doi.org/10.1145/3301419.3323972
  - Kleppmann, Moving Elements in List CRDTs, PaPoC 2020,
    https://doi.org/10.1145/3380787.3393677"
  (:require [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.rank :as rank]))

;; Fractional indexing (Wallace 2017). Deviation: with no server to make the
;; ranks unique, equal ranks stay, and the stamp orders them.
(defn ranked
  "The live `entries` of an ordered collection as [id entry] pairs by
  rank, and by stamp and device when two ranks are equal."
  [entries]
  (sort-by (comp (juxt :rank crdt/stamp-ms (comp str :device)) val)
           (remove (comp :removed? val) entries)))

;; The list of Kleppmann 2020, section 3: an entry's place is an LWW-Register
;; of its own, so a move never undoes a removal on another device. Deviation:
;; the entries form an LWW-element-Set rather than an add-wins one, so of an
;; addition and a removal, the later wins.
(defn with-moves
  "The ordered `entries`, each at the place of its live entry in `moves`
  when that's the later of the two."
  [entries moves]
  (reduce-kv (fn [m id mv]
               (let [e (get entries id)]
                 (if (and e
                          (crdt/live? mv)
                          (identical? mv (crdt/later e mv)))
                   (let [place (select-keys mv [:rank :updated-at :device])]
                     (assoc m id (into e place)))
                   m)))
             entries
             moves))

(defn rank-between
  "A rank between the entries `before` and `after` of the ordered
  `entries`, either nil at an end, and after any entries that share the
  rank of `before`."
  [entries before after]
  (let [a (some->> before (get entries) :rank)
        b (when after
            (->> (drop-while #(not= after (key %)) (ranked entries))
                 (map (comp :rank val))
                 (filter #(or (nil? a) (pos? (compare % a))))
                 first))]
    (rank/between a b)))

(defn- last-rank
  "The greatest rank among the live `entries`, or nil, found in one pass
  so that appending many entries doesn't sort them each time."
  [entries]
  (reduce-kv (fn [r _ {:keys [rank removed?]}]
               (if (and (not removed?)
                        (pos? (compare rank r)))
                 rank
                 r))
             nil
             entries))

(defn rank-at-end
  "A rank after every live entry of the ordered `entries`."
  [entries]
  (rank/between (last-rank entries) nil))

(defn rank-at
  "The rank that moves the entry `id` to `index` among the ordered
  `entries`. An index that isn't whole is rounded down, and NaN counts as
  0."
  [entries id index]
  (let [others (vec (remove #{id} (map key (ranked entries))))
        index  (if (== index index) index 0)
        index  (long (max 0 (min index (count others))))]
    (rank-between entries (get others (dec index)) (get others index))))

;; The device blocks keep appends clear of the interleaving anomaly
;; (Kleppmann et al. 2019, section 2), but single inserts at one place on two
;; devices still tie.
(defn appended
  "The writes that put the `ids`, in order, at the end of the ordered
  `entries` at `path` in `state`, e.g. a queue, as with-moves gives them,
  for crdt/write-each.

  An id that's already there stays where it is. The ranks come from
  rank/after, which keeps them short for many ids. They're in a block of
  the state's device, so that ids that another device appends at the same
  time don't mix with them."
  [state path entries ids]
  (let [fresh (remove #(crdt/live? (get entries %)) (distinct ids))
        ranks (rank/after (last-rank entries) (count fresh) (:device state))]
    (map (fn [id rank]
           [(conj path id) (constantly {:rank rank :removed? false})])
         fresh
         ranks)))

(defn append
  "The `state` with the `ids` at the end of the ordered `entries` at
  `path`, written at `at` as appended gives them."
  [state path entries ids at]
  (if-let [writes (seq (appended state path entries ids))]
    (crdt/write-each state writes at)
    state))

(defn- rank-problem
  "A description of the first entry in `v` at `path` whose rank isn't a
  rank, with the entries `depth` keys below `v`."
  [path depth v]
  (cond
    (not (map? v))
    nil

    (pos? depth)
    (some (fn [[k x]] (rank-problem (conj path k) (dec depth) x)) v)

    :else
    (let [rank (:rank v)]
      (when (and (some? rank) (not (rank/rank? rank)))
        (str "the rank of the entry at " (pr-str path) " is "
             (pr-str rank) ", not a rank")))))

(defn problem
  "A description of the first entry of the collections of `state` that the
  `schema` marks :ranked?, whose :rank isn't a rank, or nil.

  Such a rank, e.g. from another version of an app, would make ranked
  throw. Check the rest of the state with crdt/problem."
  [schema state]
  (some (fn [[k {:keys [depth ranked?]}]]
          (when ranked?
            (rank-problem [k] depth (get state k))))
        schema))

(comment
  (def laptop
    (append {:device "laptop"} [:queue] {} ["a" "b"] #inst "2026-01-02"))

  ;; the device's appends keep their order
  (mapv key (ranked (:queue laptop)))
  (problem {:queue {:depth 1 :ranked? true}}
           (assoc-in laptop [:queue "a" :rank] 5))
  #_.)
