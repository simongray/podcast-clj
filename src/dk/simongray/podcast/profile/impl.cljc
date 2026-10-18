(ns ^:no-doc dk.simongray.podcast.profile.impl
  "The internals of dk.simongray.podcast.profile: the schema of its
  collections for mind-meld, the writes and stamps behind its functions,
  the archive of played episodes, the checks of problem and the upgrade of
  earlier profiles."
  (:require [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.util.time :as time]))

(def collections
  "The schema of a profile for dk.simongray.mind-meld: the :depth of the
  entries of each collection, whether its tombstones can be dropped, the
  key of a count that only grows, and which collections are ordered."
  {:subscriptions  {:depth 1 :compact? true}
   :show-ranks     {:depth 1 :ranked? true}
   :remote-items   {:depth 1 :compact? true}
   :settings       {:depth 1}
   :tags           {:depth 2 :compact? true}
   :lists          {:depth 1 :compact? true}
   :episodes       {:depth 1}
   :played         {:depth 1}
   :hidden         {:depth 1}
   ;; each device's set of item guids only grows, so the larger is newer
   :archive        {:depth 2 :count-key :n}
   :queue          {:depth 1 :compact? true :ranked? true}
   :queue-moves    {:depth 1 :compact? true :ranked? true}
   :playlists      {:depth 2 :compact? true :ranked? true}
   :playlist-moves {:depth 2 :compact? true :ranked? true}
   :inbox          {:depth 1 :compact? true}
   :stars          {:depth 1 :compact? true}
   :bookmarks      {:depth 2 :compact? true}
   :clips          {:depth 2 :compact? true}
   :ratings        {:depth 1 :compact? true}
   :filters        {:depth 1 :compact? true}
   :listened       {:depth 3 :count-key :seconds}
   :payments       {:depth 1 :compact? true}
   :preferences    {:depth 1}})

(defn feed-author
  "The author of what the feed `feed-guid` brings about in a profile, such as
  its new episodes in the queue, as a device id that no real device has."
  [feed-guid]
  (str "feed:" feed-guid))

(defn as-the-feed
  "The `profile` with the live subscription `feed-guid` changed by `f`, as
  a write of the feed itself."
  [profile feed-guid f]
  (let [sub   (get-in profile [:subscriptions feed-guid])
        ;; stamped just after the last write to the subscription, so that it
        ;; never undoes what the listener did on another device meanwhile,
        ;; e.g. an unsubscription, and two devices write the same
        write (fn [p]
                (let [after (time/->inst (inc (crdt/stamp-ms sub)))]
                  (crdt/write p [:subscriptions feed-guid]
                              (constantly (f sub))
                              after)))]
    (cond-> profile
      (crdt/live? sub) (crdt/as-device (feed-author feed-guid) write))))

(defn- one-entry
  "The settings `old` of a subscription, an entry for each setting with its
  :value, as one field-wise entry, in which each setting keeps the stamp of
  its entry."
  [old]
  (let [entries (filter (comp :updated-at val) old)
        latest  (reduce crdt/later nil (vals entries))]
    (when latest
      (into {:updated-at (:updated-at latest)
             :device     (:device latest)
             :stamps     (update-vals (into {} entries)
                                      #(select-keys % [:updated-at :device]))}
            (keep (fn [[k e]] (when-not (:removed? e) [k (:value e)])))
            entries))))

;; before mind-meld 0.2.0, each setting was an entry of its own
(defn upgrade
  "The `profile` of an earlier version in the shape of this one: the
  settings of each subscription as one field-wise entry."
  [profile]
  (let [old? #(and (map? %) (not (contains? % :updated-at)))]
    (cond-> profile
      (map? (:settings profile))
      (update :settings
              #(into {}
                     (keep (fn [[feed-guid s]]
                             (if (old? s)
                               (some->> (one-entry s) (vector feed-guid))
                               [feed-guid s])))
                     %)))))

(defn archived?
  "Whether the archive of `profile` holds the episode `id` as played."
  [profile [feed-guid item-guid :as id]]
  (boolean (some #(contains? (:items %) item-guid)
                 (vals (get-in profile [:archive feed-guid])))))

(defn archived-mark
  "The played mark of the episode `id` that the archive of `profile` holds,
  or nil. It has the earliest stamp of the archives that hold it, which is
  no earlier than its move there."
  [profile [feed-guid item-guid :as id]]
  (let [holders (filter #(contains? (:items %) item-guid)
                        (vals (get-in profile [:archive feed-guid])))]
    (when (seq holders)
      {:played?    true
       :updated-at (:updated-at (apply min-key crdt/stamp-ms holders))})))

(defn forgotten
  "The tombstones of the played marks of the forgotten episodes of
  `profile`, by id."
  [profile]
  (into {} (filter (comp :removed? val)) (:played profile)))

(defn archivable
  "The ids of the episodes of `profile` that nothing changed in the
  `archive-after` milliseconds before `at`, played or not."
  [profile at archive-after]
  (let [cutoff (- (inst-ms at) archive-after)
        old?   #(or (nil? %) (< (crdt/stamp-ms %) cutoff))]
    (for [id    (distinct (concat (keys (:played profile))
                                  (keys (:episodes profile))))
          :when (and (old? (get-in profile [:played id]))
                     (old? (get-in profile [:episodes id]))
                     ;; the archive is a set of item guids
                     (string? (get id 1)))]
      id)))

;; The entries go without a tombstone: a device that still holds them
;; brings back what the archive says too, that the episode is played, and
;; an unplay from a device that hasn't seen the move still wins.
(defn archive
  "The `profile` with the played episodes `ids` moved to the archive at
  `at`: their entries go, and their guids join the archive of this device,
  or of its replica, unless the archive of a device holds them already."
  [profile ids at]
  (let [fresh  (group-by first (remove (partial archived? profile) ids))
        writes (for [[feed-guid ids] fresh]
                 [[:archive feed-guid (crdt/author profile)]
                  (fn [{:keys [items]}]
                    (let [items (into (or items #{}) (map second) ids)]
                      {:items items :n (count items)}))])
        forget (fn [entries]
                 (apply dissoc entries ids))]
    (cond-> profile
      (:played profile)   (update :played forget)
      (:episodes profile) (update :episodes forget)
      (seq writes)        (crdt/write-each writes at))))

(defn out-of-inbox
  "The writes that take the episode `id` out of the inbox of `profile`, if
  it's there."
  [profile id]
  (let [e (get-in profile [:inbox id])]
    (when (crdt/live? e)
      [[[:inbox id] #(assoc % :removed? true)]])))

(defn afresh
  "The episode entry `e`, or an empty one in place of the tombstone of a
  forgotten episode."
  [e]
  (if (:removed? e) {} e))

(defn marked-as
  "The writes that mark the episode `id` of `profile` as `played?` at `at`.
  A played episode leaves the inbox."
  [profile id played? at]
  (let [mark (if played?
               {:played? true :played-at (time/date at)}
               {:played? false})]
    (into [[[:played id] (constantly mark)]]
          (when played?
            (out-of-inbox profile id)))))

(defn unplayed
  "The writes that mark the episode `id` of `profile` as not played at `at`,
  with its position back at the start."
  [profile id at]
  (let [restarted #(assoc (afresh %) :position 0 :reported-at (time/date at))]
    (into [[[:episodes id] restarted]]
          (marked-as profile id false at))))

(defn written
  "The `profile` with the `writes` made at `at` with one stamp, or as it is
  without any, so that its clock doesn't move."
  [profile writes at]
  (if (seq writes)
    (crdt/write-each profile writes at)
    profile))

(defn rewind
  "The seconds to go back after a pause of `pause` seconds, by the pairs of
  `rewinds`, the seconds of a pause and those to go back: those of the
  longest pause that `pause` reaches, or 0."
  [rewinds pause]
  (or (->> rewinds
           (filter #(<= (first %) pause))
           (sort-by first)
           last
           second)
      0))

(defn- key-problem
  "A description of the first key of `profile` of the wrong type for its
  collection. Lists and tags have strings as keys, and bookmarks and clips
  whole seconds."
  [profile]
  (let [whole-second? #(and (integer? %) (<= 0 %))]
    (or (some (fn [url]
                (when-not (string? url)
                  (str "the list at " (pr-str url) " is not at a URL")))
              (keys (:lists profile)))
        (some (fn [[what k]]
                (some (fn [[id entries]]
                        (when-not (every? whole-second? (keys entries))
                          (str "a " what " of " (pr-str id)
                               " is not at a whole number of seconds")))
                      (get profile k)))
              [["bookmark" :bookmarks] ["clip" :clips]])
        (some (fn [[feed-guid ts]]
                (when-not (every? string? (keys ts))
                  (str "a tag of " (pr-str feed-guid) " is not a string")))
              (:tags profile)))))

(defn- mark-problem
  "A description of the first live rating of `profile` outside its scale,
  or of the first live clip whose duration isn't a number of seconds."
  [profile]
  (or (some (fn [[id {:keys [rating removed?] :as e}]]
              (when-not (or removed?
                            (profile.marks/rating? rating
                                                   (profile.marks/scale-of e)))
                (str "the rating of " (pr-str id) " is " (pr-str rating)
                     ", not a number of the scale "
                     (pr-str (profile.marks/scale-of e)))))
            (:ratings profile))
      (some (fn [[id entries]]
              (some (fn [[start {:keys [duration removed?]}]]
                      (when-not (or removed?
                                    (and (number? duration) (pos? duration)))
                        (str "the clip of " (pr-str id) " at " start
                             " lasts " (pr-str duration)
                             ", not a number of seconds")))
                    entries))
            (:clips profile))))

(defn- count-problem
  [profile]
  (some (fn [[feed-guid months]]
          (let [counts (mapcat vals (vals months))]
            (when-not (every? #(number? (:seconds % 0)) counts)
              (str "the listening of " (pr-str feed-guid)
                   " is not counted in seconds"))))
        (:listened profile)))

(defn- archive-problem
  [profile]
  (some (fn [[feed-guid devices]]
          (some (fn [[_ {:keys [items n]}]]
                  (when-not (and (set? items)
                                 (every? string? items)
                                 (= n (count items)))
                    (str "the archive of " (pr-str feed-guid)
                         " is not a set of item guids with its count")))
                devices))
        (:archive profile)))

(defn- criteria-problem
  [profile]
  (some (fn [[name {:keys [criteria]}]]
          (when-not (or (nil? criteria) (map? criteria))
            (str "the criteria of the filter " (pr-str name) " are not a map")))
        (:filters profile)))

(defn- remote-item-problem
  [profile]
  (some (fn [[id {:keys [feed-url removed?]}]]
          (when-not (or removed? (string? feed-url))
            (str "the remote item " (pr-str id) " names no feed URL")))
        (:remote-items profile)))

(def checks
  "The checks of a profile after those of dk.simongray.mind-meld, in order.
  Each takes a profile that is a map and returns a description of the
  first problem of its kind, or nil, and relies on the checks before it."
  [key-problem mark-problem count-problem archive-problem criteria-problem
   remote-item-problem])
