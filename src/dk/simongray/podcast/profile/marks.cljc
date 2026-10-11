(ns dk.simongray.podcast.profile.marks
  "The marks that the listener puts on the episodes of a profile: stars,
  bookmarks, clips and ratings.

  Each is a collection of the profile, so a mark syncs like the rest of
  it. The played mark is the profile's own, since
  dk.simongray.podcast.profile/progress sets it."
  (:require [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.util.time :as time]))

(defn starred?
  "Whether the episode `id` has a star in `profile`."
  [profile id]
  (let [e (get-in profile [:stars id])]
    (crdt/live? e)))

(defn starred
  "The starred episode ids of `profile`, the latest starred first."
  [profile]
  (crdt/live-by-stamp (:stars profile)))

(defn star
  "Star the episode `id` in `profile` at `at`, unless it has a star."
  [profile id at]
  (if (starred? profile id)
    profile
    (crdt/write profile [:stars id] (constantly {:removed? false}) at)))

(defn unstar
  "Take the star off the episode `id` in `profile` at `at`, leaving a
  tombstone, so that it outlives an older star on another device."
  [profile id at]
  (if (starred? profile id)
    (crdt/write profile [:stars id] #(assoc % :removed? true) at)
    profile))

(defn ^:no-doc second-of
  "The whole second of `position`, or nil when it isn't a number of
  seconds from the start."
  [position]
  (when (and (time/finite? position) (<= 0 position))
    (long position)))

(defn bookmark
  "Bookmark the episode `id` in `profile` at `position` seconds at `at`, with
  a `title` when it's given. Another bookmark at the same whole second
  replaces its title."
  ([profile id position at]
   (bookmark profile id position nil at))
  ([profile id position title at]
   (if-let [s (second-of position)]
     (crdt/write profile [:bookmarks id s]
                 (constantly {:title title :removed? false})
                 at)
     profile)))

(defn ^:no-doc unmark
  "The `profile` with a tombstone at `at` in place of the live entry of the
  episode `id` at `position` seconds in the collection `k`, e.g.
  :bookmarks."
  [profile k id position at]
  (let [s (second-of position)
        e (get-in profile [k id s])]
    (if (crdt/live? e)
      (crdt/write profile [k id s] #(assoc % :removed? true) at)
      profile)))

(defn ^:no-doc marks
  "The live entries of the collection `k` of `profile`, the latest written
  first, or those of the episode `id` in order of their seconds. Each is a
  map of its second under `at-key` and the keys `ks` of the entry.

  The collection holds entries by episode and whole second, e.g.
  :bookmarks. Each map also has the episode's :id and :updated-at."
  ([profile k at-key ks]
   (->> (for [[id entries] (get profile k)
              [s e] entries
              :when (not (:removed? e))]
          (clojure.core/merge {:id id at-key s}
                              (zipmap ks (map e ks))
                              {:updated-at (:updated-at e)}))
        (sort-by #(- (crdt/stamp-ms %)))
        vec))
  ([profile k at-key ks id]
   (->> (marks {k (select-keys (get profile k) [id])} k at-key ks)
        (sort-by at-key)
        vec)))

(defn unbookmark
  "Remove the bookmark of the episode `id` at `position` seconds from
  `profile` at `at`, leaving a tombstone."
  [profile id position at]
  (unmark profile :bookmarks id position at))

(defn bookmarks
  "The bookmarks of `profile`, the latest written first, or those of the
  episode `id` in order of position.

  A bookmark is a map of :id, :position in whole seconds, :title and
  :updated-at."
  ([profile]
   (marks profile :bookmarks :position [:title]))
  ([profile id]
   (marks profile :bookmarks :position [:title] id)))

(defn clip
  "Keep a clip of the episode `id` in `profile` at `at`, from `start` seconds
  for `duration` seconds, with a `title` when it's given.

  A clip has the shape of a podcast:soundbite. Another clip at the same
  whole second replaces it, and a start or duration that isn't a number of
  seconds keeps no clip."
  ([profile id start duration at]
   (clip profile id start duration nil at))
  ([profile id start duration title at]
   (let [s     (second-of start)
         kept? (and s
                    (time/finite? duration)
                    (pos? duration))]
     (if kept?
       (let [entry {:duration duration :title title :removed? false}]
         (crdt/write profile [:clips id s] (constantly entry) at))
       profile))))

(defn unclip
  "Remove the clip of the episode `id` that starts at `start` seconds
  from `profile` at `at`, leaving a tombstone."
  [profile id start at]
  (unmark profile :clips id start at))

(defn clips
  "The clips of `profile`, the latest written first, or those of the episode
  `id` in order of start.

  A clip is a map of :id, :start-time in whole seconds, :duration in
  seconds, :title and :updated-at."
  ([profile]
   (marks profile :clips :start-time [:duration :title]))
  ([profile id]
   (marks profile :clips :start-time [:duration :title] id)))

(def rating-scale
  "The scale of ratings when no other is given: from 1 for the worst to 5
  for the best, like the stars of most apps.

  A scale is a map of :worst and :best, two different numbers, as in a
  schema.org Rating. Give another as :scale in the options, e.g.
  {:worst 0 :best 1} for thumbs down and up."
  {:worst 1 :best 5})

(defn ^:no-doc scale?
  "Whether `x` is a rating scale, a map whose :worst and :best are two
  different numbers."
  [x]
  (and (map? x)
       (time/finite? (:worst x))
       (time/finite? (:best x))
       (not (== (:worst x) (:best x)))))

(defn ^:no-doc checked-scale
  "The :worst and :best of a `scale` from the options. It throws when that
  isn't a scale."
  [scale]
  (if (scale? scale)
    (select-keys scale [:worst :best])
    (throw (ex-info (str "A scale of ratings has a :worst and a :best, two "
                         "different numbers")
                    {:scale scale}))))

(defn ^:no-doc scale-of
  "The scale of the rating entry `e`, or the default when it names none."
  [e]
  (clojure.core/merge rating-scale (select-keys e [:worst :best])))

(defn ^:no-doc rating?
  "Whether `n` is a number from the worst to the best of `scale`."
  [n {:keys [worst best] :as scale}]
  (and (scale? scale)
       (number? n)
       (<= (min worst best) n (max worst best))))

(defn ^:no-doc rescaled
  "The rating `n` of the scale `from` converted to the scale `to`, at the
  same point between the worst and the best."
  [n from to]
  (if (= from to)
    n
    (+ (:worst to)
       (* (/ (- n (:worst from)) (double (- (:best from) (:worst from))))
          (- (:best to) (:worst to))))))

(defn rating
  "The rating that the listener gave the episode `id` in `profile`, or nil,
  in the scale of `opts` if it gives one.

  It's in the scale that it was given in. A :scale in `opts` converts it,
  e.g. a 4 of 1 to 5 is 75.25 of 1 to 100."
  ([profile id]
   (rating profile id {}))
  ([profile id {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [removed?] n :rating :as e} (get-in profile [:ratings id])]
     (when (and (number? n) (not removed?))
       (if scale
         (rescaled n (scale-of e) (checked-scale scale))
         n)))))

(defn rated
  "The episodes of `profile` that have a rating, the latest rated first, in
  the scale of `opts` if it gives one.

  Each is a map of :id, :rating, the :worst and :best of its scale, and
  :updated-at. A :scale in `opts` converts the ratings."
  ([profile]
   (rated profile {}))
  ([profile {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (mapv (fn [id]
           (let [e (get-in profile [:ratings id])]
             (clojure.core/merge {:id id :rating (rating profile id opts)}
                                 (if scale (checked-scale scale) (scale-of e))
                                 {:updated-at (:updated-at e)})))
         (crdt/live-by-stamp (:ratings profile)))))

(defn rate-episode
  "Give the episode `id` in `profile` the rating `n` at `at`, in the scale
  of `opts`, replacing any earlier rating.

  The rating must be within its scale, or nothing changes. The profile
  keeps the scale with the rating, so another app can read it in its own
  scale."
  ([profile id n at]
   (rate-episode profile id n at {}))
  ([profile id n at {:keys [scale] :as opts}]
   {:pre [(map? opts)]}
   (let [scale (checked-scale (or scale rating-scale))
         e     (get-in profile [:ratings id])
         same? #(and (not (:removed? e))
                     (number? (:rating e))
                     (== n (:rating e))
                     (= scale (scale-of e)))]
     (if (and (rating? n scale) (not (same?)))
       (crdt/write profile [:ratings id]
                   (constantly (assoc scale :rating n :removed? false))
                   at)
       profile))))

(defn unrate-episode
  "Take the rating off the episode `id` in `profile` at `at`, leaving a
  tombstone."
  [profile id at]
  (if (rating profile id)
    (crdt/write profile [:ratings id] #(assoc % :removed? true) at)
    profile))

(comment
  (def sample
    (-> {:device "laptop"}
        (star ["feed-1" "ep-1"] #inst "2026-01-01T01:00:00Z")
        (bookmark ["feed-1" "ep-1"] 754 "The answer"
                  #inst "2026-01-01T01:30:00Z")
        (clip ["feed-1" "ep-1"] 754 42.5 "The answer, in full"
              #inst "2026-01-01T01:31:00Z")
        (rate-episode ["feed-1" "ep-1"] 5 #inst "2026-01-01T01:32:00Z")))

  (bookmarks sample)
  (clips sample)
  (rated sample)

  ;; a thumb up, and the same rating in the scale of another app
  (def thumbs
    (rate-episode sample ["feed-1" "ep-2"] 1 #inst "2026-01-01T01:33:00Z"
                  {:scale {:worst 0 :best 1}}))

  (rating thumbs ["feed-1" "ep-2"])
  (rating thumbs ["feed-1" "ep-2"] {:scale rating-scale})
  #_.)
