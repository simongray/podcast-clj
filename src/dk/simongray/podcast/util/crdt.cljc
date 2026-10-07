(ns dk.simongray.podcast.util.crdt
  "A CRDT of plain maps, which devices merge in any order without conflict.

  A state is a map of collections, each a map of entries, maybe nested by
  more keys, with the :device that the state belongs to and its :clock. A
  write stamps an entry with :updated-at and :device, and a removal is an
  entry with :removed?, a tombstone. Merge keeps the later of two entries,
  and compact drops the tombstones that every device has seen.

  A schema maps the key of each collection to its options: the :depth of
  its entries, :compact? when compact drops its tombstones, and :count for
  entries that hold a count under that key, which only grows.

      {:queue    {:depth 1 :compact? true}
       :listened {:depth 3 :count :seconds}}

  The registry of devices, under :devices and :retired, belongs to this
  namespace and needs no schema.

  The comments name the source of each part, and where it departs from it:

  - Shapiro, Preguiça, Baquero and Zawirski, A comprehensive study of
    Convergent and Commutative Replicated Data Types, INRIA RR-7506, 2011,
    https://inria.hal.science/inria-00555588
  - Almeida, Approaches to Conflict-free Replicated Data Types, ACM
    Computing Surveys 57(2), 2024, https://arxiv.org/abs/2310.18220
  - Johnson and Thomas, The Maintenance of Duplicate Databases, RFC 677,
    1975, https://www.rfc-editor.org/rfc/rfc677
  - Kulkarni, Demirbas, Madappa, Avva and Leone, Logical Physical Clocks,
    OPODIS 2014, https://doi.org/10.1007/978-3-319-14472-6_2
  - Almeida, Shoker and Baquero, Delta State Replicated Data Types,
    Journal of Parallel and Distributed Computing 111, 2018,
    https://arxiv.org/abs/1603.01529
  - Wallace, Realtime Editing of Ordered Sequences, Figma, 2017,
    https://www.figma.com/blog/realtime-editing-of-ordered-sequences/
  - Kleppmann, Gomes, Mulligan and Beresford, Interleaving anomalies in
    collaborative text editors, PaPoC 2019,
    https://doi.org/10.1145/3301419.3323972
  - Kleppmann, Moving Elements in List CRDTs, PaPoC 2020,
    https://doi.org/10.1145/3380787.3393677"
  (:refer-clojure :exclude [merge])
  (:require [clojure.walk :as walk]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.rank :as rank]
            [dk.simongray.podcast.util.time :as time]))

;; TODO: Making this namespace a library of its own takes util.json,
;; util.rank and util.time along. The stores in sync.* may go with it; see
;; the TODO in sync.store.

(def ^:no-doc header
  "The keys of a state that hold no entries: its device and its clock."
  #{:device :clock})

(def ^:no-doc registry
  "The schema of the registry: what each device said of itself, under
  :devices, and the retired devices."
  {:devices {:depth 1}
   :retired {:depth 1}})

(defn- stamp
  [state entry at]
  (assoc entry :updated-at at :device (or (::author state) (:device state))))

(defn- stamped?
  [x]
  (and (map? x)
       (contains? x :updated-at)))

(defn ^:no-doc later-inst
  "The later of the instants `a` and `b`, either of which can be nil."
  [a b]
  (if (and a b)
    (max-key inst-ms b a)
    (or a b)))

(defn ^:no-doc stamp-ms
  "The stamp of the entry `e` in milliseconds, 0 for an entry without one."
  [e]
  (or (some-> (:updated-at e) inst-ms) 0))

;; Each entry is an LWW-Register, so a collection of them is an
;; LWW-element-Set (Shapiro et al. 2011, sections 3.2.1 and 3.3.3), and a
;; removal is a flag on the entry, as in RFC 677.
(defn live?
  "Whether the entry `e` is there and isn't a tombstone."
  [e]
  (and (some? e) (not (:removed? e))))

(defn ^:no-doc live-by-stamp
  "The keys of the live entries of the map `entries`, the latest written
  first."
  [entries]
  (->> entries
       (remove (comp :removed? val))
       (sort-by (comp stamp-ms val) >)
       (mapv key)))

;; A hybrid logical clock (Kulkarni et al. 2014). Deviation: the next
;; millisecond stands in for its logical counter, so a burst of writes moves
;; the stamps ahead of the time, which at-once avoids within one call.
(defn after
  "The instant to stamp a write to `state` at `at` with: `at`, or the
  millisecond after the clock of the state when that's later. This keeps
  each write after what the device has merged."
  [state at]
  (time/->inst (cond-> (inst-ms at)
                 (:clock state) (max (inc (inst-ms (:clock state)))))))

(declare later)

(defn ^:no-doc write-each
  "The `state` with each path and function of `writes` applied at `at` as
  write applies one, all with the same stamp, which becomes the state's
  clock. Within at-once, the stamp is that of at-once. Within as-device,
  it's `at` itself, and an entry that's later than the write stays."
  [state writes at]
  (let [foreign?  (contains? state ::author)
        at        (if foreign?
                    (time/date at)
                    (or (::at state) (after state at)))
        put       (if foreign? later (fn [_ new] new))
        write-one (fn [s [path f]]
                    (update-in s path #(put % (stamp state (f %) at))))]
    (-> (reduce write-one state writes)
        (assoc :clock (later-inst (:clock state) at)))))

(defn write
  "The `state` with the entry at `path` replaced by what `f` returns for
  it, stamped at `at`, or just after the state's clock when that's later.

  The `f` gets nil when there's no entry yet. An app can write entries of
  its own this way, which merge then reconciles like the rest."
  [state path f at]
  (write-each state [[path f]] at))

;; The alternative is the hybrid logical clock of Kulkarni et al. 2014 in
;; full, with a counter in each stamp, so that no series of writes runs ahead
;; of the time. That changes the format of every stamp, and its practical
;; gain was deemed too small.
(defn at-once
  "The `state` after `f` with every write in `f` stamped at the same
  instant, as one write at `at` would be.

  The `f` takes the state. Without at-once, each write in a series is a
  millisecond after the one before it, so a long series moves the clock
  ahead of the time. Wrap a series of an app's own writes in it, e.g. those
  of an import."
  [state at f]
  (if (contains? state ::at)
    (f state)
    (dissoc (f (assoc state ::at (after state at))) ::at)))

;; Deviation: an LWW-Register needs stamps that follow causal order (Shapiro
;; et al. 2011, section 3.2.1), but these are another device's own times,
;; so an act that came later on a slower clock loses.
(defn as-device
  "The `state` after `f`, with every write in `f` made as the device
  `device` at the instants that `f` gives, as a merge of that device's
  state would bring them.

  The `f` takes the state. An entry of the state that's later than such a
  write stays, and the clock moves to the write when that's later. This is
  for what another device or a service did at a time of its own, e.g. the
  changes that a sync service reports."
  [state device f]
  (dissoc (f (assoc state ::author device)) ::author))

(defn identify
  "Describe this device in the registry of `state` at `at`, with a
  `description` such as its :name and :platform, merged into what it
  said before."
  [state description at]
  (write state
         [:devices (:device state)]
         #(clojure.core/merge % description)
         at))

(defn retire
  "Retire the device `device` from `state` at `at`, from any device.

  The device is no longer among the devices of the state. A sync writes
  nothing for it and deletes its document, and the device can't undo the
  retirement by describing itself again."
  [state device at]
  (write state [:retired device] identity at))

(defn retired?
  "Whether `state` has retired the device `device`."
  [state device]
  (contains? (:retired state) device))

(defn devices
  "The devices of `state` by id, as they described themselves with
  identify, except the retired ones."
  [state]
  (apply dissoc (:devices state {}) (keys (:retired state))))

(defn- latest-by-author
  "The latest stamp of each device among the entries of `state`.

  The device registry is left out, or each acknowledgement would be news to
  acknowledge, and two devices would keep writing to each other forever."
  [state]
  (letfn [(walk [m v]
            (cond
              (stamped? v) (update m (:device v) later-inst (:updated-at v))
              (map? v)     (reduce-kv (fn [m _ x] (walk m x)) m v)
              :else        m))]
    (walk {} (apply dissoc state :devices header))))

(defn acknowledge
  "Note in the registry of `state` at `at` that this device has merged the
  whole states `others`.

  Its entry keeps the latest stamp of each other device that's now merged
  here under :seen, which compact goes by. Only whole states count, never
  the changes that a service sends. The entry is only written when it
  grew, so that a sync alone never rewrites a document."
  [state others at]
  (let [me     (:device state)
        known  (get-in state [:devices me :seen] {})
        merged (apply merge-with later-inst known (map latest-by-author others))
        seen   (dissoc merged me)]
    (if (and (= seen known)
             (get-in state [:devices me]))
      state
      (write state [:devices me] #(assoc % :seen seen) at))))

(def default-keep-for
  "How long a tombstone is kept once every device has seen it, thirty days
  in milliseconds, for a device that joins with a state of its own."
  (* 30 24 60 60 1000))

;; Stability (Shapiro et al. 2011, section 4.1, after Wuu and Bernstein,
;; PODC 1984). Deviation: :seen keeps the latest stamp of each author, not a
;; vector clock, which holds only while an author's entries arrive in the
;; order of their stamps. That's true of the devices in the registry, but
;; not of the authors of as-device, so their tombstones stay.
(defn- seen-by-all?
  "Whether every live device of `state`, except the one that wrote the
  entry `e`, has merged it, by the device registry.

  This device counts as having merged what it holds. With no other device,
  it's false, since a lone device gains nothing by forgetting, and a device
  that joins later could bring back what it forgot. It's false, too, for an
  entry of an author outside the registry."
  [state {:keys [device updated-at] :as e}]
  (let [judges (dissoc (devices state) device)]
    (and (contains? (:devices state) device)
         (seq judges)
         (every? (fn [[id {:keys [seen]}]]
                   (or (= id (:device state))
                       (some-> (get seen device)
                               inst-ms
                               (>= (inst-ms updated-at)))))
                 judges))))

(defn compact
  "Drop from `state` at `at` the tombstones of the collections that the
  `schema` marks :compact?, once every live device has merged them and
  they're older than the :keep-for of `opts`.

  The :keep-for is in milliseconds, default-keep-for by default. A device
  that stopped syncing holds the compaction back until it's retired (see
  lagging). The tombstones of an author outside the registry, which
  as-device writes, stay."
  ([schema state at]
   (compact schema state at {}))
  ([schema state at {:keys [keep-for] :or {keep-for default-keep-for} :as opts}]
   {:pre [(map? opts)]}
   (let [droppable? (fn [e]
                      (and (:removed? e)
                           (<= (+ (inst-ms (:updated-at e)) keep-for)
                               (inst-ms at))
                           (seen-by-all? state e)))
         sweep      (fn sweep [m]
                      (reduce-kv (fn [m k v]
                                   (cond
                                     (stamped? v)
                                     (cond-> m (droppable? v) (dissoc k))

                                     (map? v)
                                     (if-some [w (not-empty (sweep v))]
                                       (assoc m k w)
                                       (dissoc m k))

                                     :else
                                     m))
                                 m
                                 m))
         compact-in (fn [s k {:keys [compact?]}]
                      (cond-> s
                        (and compact? (map? (get s k))) (update k sweep)))]
     (reduce-kv compact-in state schema))))

(defn lagging
  "The live devices of `state` that haven't merged everything this one has,
  as a map from id to the latest stamp that each merged, or nil.

  Such a device holds compact back until it catches up or is retired."
  [state]
  (let [me   (:device state)
        seen (get-in state [:devices me :seen] {})
        mine (assoc seen me (get (latest-by-author state) me))]
    (into {}
          (for [[id {theirs :seen}] (dissoc (devices state) me)
                :let  [behind (for [[author latest] (dissoc mine id)
                                    :when latest
                                    :let  [t (get theirs author)]
                                    :when (or (nil? t)
                                              (< (inst-ms t) (inst-ms latest)))]
                                t)]
                :when (seq behind)]
            [id (when (every? some? behind)
                  (apply min-key inst-ms behind))]))))

(defn- key-order
  "A comparison of the keys `a` and `b` of mixed types: keywords first, as
  compare orders them, then any other key by its printed form."
  [a b]
  (if (and (keyword? a) (keyword? b))
    (compare a b)
    (let [kind #(if (keyword? %) 0 1)]
      (compare [(kind a) (pr-str a)] [(kind b) (pr-str b)]))))

(defn- float-bits
  "The bits of the float `x` as 16 hex digits, the same on every platform."
  [x]
  #?(:clj  (let [h (Long/toHexString (Double/doubleToLongBits x))]
             (str (subs "0000000000000000" (count h)) h))
     :cljs (let [v   (js/DataView. (js/ArrayBuffer. 8))
                 hex #(.padStart (.toString % 16) 8 "0")]
             (.setFloat64 v 0 x)
             (str (hex (.getUint32 v 0)) (hex (.getUint32 v 4))))))

(defn- in-order
  "The value `x` in the form that printed prints, with maps and sets sorted
  by key-order, a float with no fraction as a whole number, and any other
  float as its bits, since the platforms print floats differently."
  [x]
  (cond
    (map? x)   (into (sorted-map-by key-order) x)
    (set? x)   (into (sorted-set-by key-order) x)
    (float? x) (or (json/whole x) (tagged-literal 'float (float-bits x)))
    :else      x))

(defn- printed
  "The entry `e` printed with each value as in-order gives it, so that
  the string is the same in Clojure and ClojureScript."
  [e]
  (pr-str (walk/postwalk in-order e)))

(defn- tie-key
  "What orders two entries with the same stamp: the device id of the entry
  `e`, and then the entry printed."
  [e]
  [(str (:device e)) (printed e)])

;; The merge of an LWW-Register (Shapiro et al. 2011, section 3.2.1), with
;; ties broken by device as in RFC 677. Deviation: two entries of one device
;; at one instant, e.g. from as-device, fall back to the printed entry.
(defn- later
  "The later of the stamped entries `a` and `b`.

  On the same instant, the greater device id wins, and then the greater
  printed entry, so that every device picks the same one on either
  platform. Equal entries aren't printed, since two states that agree tie
  on all of them."
  [a b]
  (cond
    (nil? a)                                 b
    (nil? b)                                 a
    (> (stamp-ms a) (stamp-ms b))            a
    (< (stamp-ms a) (stamp-ms b))            b
    (= a b)                                  a
    (pos? (compare (tie-key a) (tie-key b))) a
    :else                                    b))

;; A G-Counter (Shapiro et al. 2011, section 3.1.2) with an entry per device,
;; ordered as a lexicographic product of count and stamp (Almeida 2024,
;; figure 10). Like any G-Counter, it needs one writer per device id.
(defn- higher
  "Of the entries `a` and `b`, which hold a count under the key `k`, the
  one with the higher count, or else the later. A count only grows, so the
  higher count includes the lower one."
  [k a b]
  (let [n #(let [n (get % k)] (if (number? n) n 0))]
    (if (== (n a) (n b))
      (later a b)
      (max-key n a b))))

;; The join of a state-based CRDT (Shapiro et al. 2011, section 2.3.1): maps
;; merge pointwise and the clock by max (Almeida 2024, section 5.1, figure
;; 10). Deviation: any other value is no lattice, so problem names one.
(defn- join
  "The merge of the values `a` and `b` under one key of two states, by
  their shape, as merge describes it. Of two stamped entries, it's the one
  that `pick` returns."
  [pick a b]
  (cond
    (nil? a)                  b
    (nil? b)                  a
    (and (map? a) (map? b))   (if (or (stamped? a) (stamped? b))
                                (pick a b)
                                (merge-with (partial join pick) a b))
    (and (inst? a) (inst? b)) (later-inst a b)
    :else                     a))

(defn merge
  "Reconcile the states `a` and `b` of two devices by the `schema`, key by
  key, by the shape of each value:

  - a stamped entry is a last-writer-wins register, and the later entry
    wins whole, even when it's a tombstone
  - a map of entries merges entry by entry, down through nested maps
  - an instant is a max register
  - any other value is that of `a`, which is why problem names one

  Of two entries of a collection with a :count, it keeps the higher count.
  The merge is commutative, associative and idempotent, so it's safe to
  apply in any order and any number of times."
  [schema a b]
  (let [pick (fn [k]
               (if-let [n (get-in schema [k :count])]
                 (partial higher n)
                 later))
        ks   (disj (into (set (keys a)) (keys b)) :device)]
    (into {:device (:device a)}
          (map (fn [k] [k (join (pick k) (get a k) (get b k))]))
          ks)))

;; Fractional indexing (Wallace 2017). Deviation: with no server to make the
;; ranks unique, equal ranks stay, and the stamp orders them.
(defn ^:no-doc ranked
  "The live `entries` of a ranked map such as a queue, as [id entry] pairs
  by rank, and by stamp and device when two ranks are equal."
  [entries]
  (sort-by (comp (juxt :rank stamp-ms (comp str :device)) val)
           (remove (comp :removed? val) entries)))

;; The list of Kleppmann 2020, section 3: an entry's place is an LWW-Register
;; of its own, so a move never undoes a removal on another device. Deviation:
;; the entries form an LWW-element-Set rather than an add-wins one, so of an
;; addition and a removal, the later wins.
(defn ^:no-doc with-moves
  "The ranked `entries`, each at the place of its live entry in `moves`
  when that's the later of the two."
  [entries moves]
  (reduce-kv (fn [m id mv]
               (let [e (get entries id)]
                 (if (and e
                          (live? mv)
                          (identical? mv (later e mv)))
                   (let [place (select-keys mv [:rank :updated-at :device])]
                     (assoc m id (into e place)))
                   m)))
             entries
             moves))

(defn ^:no-doc rank-between
  "A rank between the entries `before` and `after` of the ranked
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

(defn ^:no-doc last-rank
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

(defn ^:no-doc rank-at
  "The rank that moves the entry `id` to `index` among the ranked
  `entries`. An index that isn't whole is rounded down, and NaN counts
  as 0."
  [entries id index]
  (let [others (vec (remove #{id} (map key (ranked entries))))
        index  (if (== index index) index 0)
        index  (long (max 0 (min index (count others))))]
    (rank-between entries (get others (dec index)) (get others index))))

;; The device blocks keep appends clear of the interleaving anomaly
;; (Kleppmann et al. 2019, section 2), but single inserts at one place on two
;; devices still tie.
(defn ^:no-doc appended
  "The writes that put the `ids`, in order, at the end of the ranked
  `entries` at `path` in `state`, e.g. a queue, as with-moves gives them.

  An id that's already there stays where it is. The ranks come from
  rank/after, which keeps them short for many ids. They're in a block of
  the state's device, so that ids that another device appends at the same
  time don't mix with them."
  [state path entries ids]
  (let [fresh (remove #(live? (get entries %)) (distinct ids))
        ranks (rank/after (last-rank entries) (count fresh) (:device state))]
    (map (fn [id rank]
           [(conj path id) (constantly {:rank rank :removed? false})])
         fresh
         ranks)))

(defn ^:no-doc append
  "The `state` with the `ids` at the end of the ranked `entries` at `path`,
  written at `at` as appended gives them."
  [state path entries ids at]
  (if-let [writes (seq (appended state path entries ids))]
    (write-each state writes at)
    state))

(defn- clock-problem
  [state]
  (when-some [clock (:clock state)]
    (when-not (inst? clock)
      (str "the clock is " (pr-str clock) ", not an instant"))))

(defn- entry-problem
  "A description of the first value in `v` at `path` that isn't a map, or
  of the first entry without a stamp. The entries are `depth` keys below
  `v`."
  [path depth v]
  (cond
    (not (map? v))
    (str "the entry at " (pr-str path) " is not a map")

    (pos? depth)
    (some (fn [[k x]] (entry-problem (conj path k) (dec depth) x)) v)

    (not (stamped? v))
    (str "the entry at " (pr-str path) " is not stamped")))

(defn- collection-problem
  "A description of the first collection of `state` in the `schema` that
  isn't a map, or of the first problem entry-problem finds in one."
  [schema state]
  (some (fn [[k {:keys [depth]}]]
          (when-some [entries (get state k)]
            (if (map? entries)
              (entry-problem [k] depth entries)
              (str k " is not a map"))))
        schema))

(defn- value-problem
  "A description of the first value in `v`, at `path`, that is neither a
  map nor in a stamped entry. Merge keeps each device's own such value, so
  the devices never agree on it."
  [path v]
  (cond
    (or (nil? v) (stamped? v))
    nil

    (map? v)
    (some (fn [[k x]] (value-problem (conj path k) x)) v)

    :else
    (str "the value at " (pr-str path) " is not in a stamped entry")))

(defn- stamp-problem
  "A description of the first entry in `v`, at `path`, whose stamp isn't
  an instant."
  [path v]
  (cond
    (stamped? v)
    (when-not (inst? (:updated-at v))
      (str "the entry at " (pr-str path) " is stamped "
           (pr-str (:updated-at v)) ", not an instant"))

    (map? v)
    (some (fn [[k x]] (stamp-problem (conj path k) x)) v)

    :else
    nil))

(defn- seen-problem
  [state]
  (some (fn [[id {:keys [seen]}]]
          (when-not (or (nil? seen)
                        (and (map? seen) (every? inst? (vals seen))))
            (str "what device " (pr-str id)
                 " has seen is not a map of instants")))
        (:devices state)))

(defn problem
  "A description of what in `state` breaks the rules that merge and
  compact rely on, given the `schema`, or nil.

  A merge spreads the values of another device to every device, where a
  bad value makes a function throw, or two devices disagree. The rules:

  - The state and its collections are maps, and the clock is an instant.
  - Besides the device and the clock, the state holds only maps, down to
    stamped entries.
  - Each entry of a collection has a stamp, which is an instant.
  - What a device has seen is a map of instants."
  [schema state]
  (if (map? state)
    (some #(% state)
          [clock-problem
           #(collection-problem (clojure.core/merge schema registry) %)
           #(value-problem [] (apply dissoc % header))
           #(stamp-problem [] %)
           seen-problem])
    "the state is not a map"))

(defn- changed-part
  "The part of `v` that isn't in `was`, the value at the same place in an
  earlier state, or nil when nothing changed."
  [v was]
  (cond
    (stamped? v)
    (when (not= v was) v)

    (map? v)
    (not-empty (into {} (keep (fn [[k x]]
                                (some->> (changed-part x (get was k))
                                         (vector k))))
                     v))

    :else
    nil))

;; A delta-state (Almeida, Shoker and Baquero 2018), found by comparing two
;; states rather than made by each write.
(defn changes-since
  "The part of `state` that isn't in `previous`, or all of it when
  `previous` is nil, as a partial state that merge takes.

  It's every entry written or merged since, however early its stamp, which
  is what a sync pushes to a service that wants changes rather than the
  whole state."
  [state previous]
  (let [entries (apply dissoc state header)]
    (into (select-keys state header) (changed-part entries previous))))

(comment
  (def schema
    {:notes {:depth 1 :compact? true}
     :plays {:depth 2 :count :n}})

  (def phone
    (-> {:device "phone"}
        (write [:notes "n1"] (constantly {:text "Buy milk"}) #inst "2026-01-01")
        (write [:plays "song" "phone"] #(update % :n (fnil inc 0))
               #inst "2026-01-01")))

  (def laptop
    (-> {:device "laptop"}
        (write [:notes "n1"] (constantly {:removed? true}) #inst "2026-01-02")
        (append [:queue] {} ["a" "b"] #inst "2026-01-02")))

  ;; the later write wins, and the device's appends keep their order
  (merge schema phone laptop)
  (mapv key (ranked (:queue (merge schema phone laptop))))
  (problem schema (assoc phone :theme "dark"))
  #_.)
