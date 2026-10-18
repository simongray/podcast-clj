(ns dk.simongray.podcast.feed.schedule
  "When a feed says that it changes, and so when to look at it again.

  The feed tells it by the rule of its podcast:updateFrequency, its ttl,
  its syndication period, or else the dates of its recent items, and a
  feed that says it's complete changes no more."
  (:require [clojure.string :as str]
            [dk.simongray.ical.rrule :as rrule]
            [dk.simongray.ical.tz :as tz]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.time :as time]))

(defn- dates
  "The instants of the items of `feed` dated no later than `at-ms`, newest
  first, as milliseconds.

  An item dated after it is an episode scheduled ahead, or a mistake, and
  neither tells when the feed changes."
  [feed at-ms]
  (->> (:rss/items feed)
       (keep :rss/pub-date)
       (filter inst?)
       (map inst-ms)
       (remove #(> % at-ms))
       (sort >)))

(def ^:no-doc utc
  "The zone that the rules of podcast:updateFrequency are evaluated in,
  since the model keeps their dtstart as an instant."
  (tz/zone "UTC"))

(def ^:no-doc max-occurrences
  "The most occurrences of a rule that next-episode reads: a century of
  daily episodes, so that a rule of every second from long ago can't hold
  up a refresh."
  36525)

(defn- occurrences-after
  "The first occurrence after `at` of the recurrence `rule` that starts at
  `start`, or nil when it has none among the first max-occurrences."
  [rule start at]
  (->> (rrule/occurrences rule (tz/local utc start) utc)
       (take max-occurrences)
       (map #(tz/instant utc %))
       (drop-while #(<= (inst-ms %) (inst-ms at)))
       first))

(defn next-episode
  "When `feed` says that its next episode is due after `at`.

  It's the first instant after that of the recurrence rule of
  podcast:updateFrequency, on the days of UTC. The rule is anchored at its
  dtstart, or else at the date of the latest item published by then, or
  else at `at`. It's nil when the feed is complete, has no rule, or has
  one that breaks RFC 5545 or runs out."
  [feed at]
  (let [{:keys [rrule dtstart]} (:podcast/update-frequency feed)
        latest (some-> (first (dates feed (inst-ms at))) time/->inst)
        anchor (or (when (inst? dtstart) dtstart)
                   latest
                   at)]
    (when (and (rrule/valid? rrule)
               (not (resolve/complete? feed)))
      (occurrences-after rrule anchor at))))

(def ^:no-doc syndication-periods
  "The milliseconds of each period that sy:updatePeriod can name."
  {"hourly"  3600000
   "daily"   86400000
   "weekly"  604800000
   "monthly" 2629800000
   "yearly"  31557600000})

(defn- median
  [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (if (odd? n)
      (v (quot n 2))
      (quot (+ (v (dec (quot n 2))) (v (quot n 2))) 2))))

(defn cadence
  "How often `feed` publishes as of `at`: the median gap in milliseconds
  between its ten most recent items dated no later than `at`, or nil when
  fewer than two of them differ in date.

  It's the schedule to show for a feed that doesn't state one, e.g. about
  every seven days."
  [feed at]
  (let [recent (take 10 (dates feed (inst-ms at)))
        gaps   (filter pos? (map - recent (rest recent)))]
    (when (seq gaps)
      (median gaps))))

(defn- syndication-due
  "When the sy:updatePeriod and sy:updateFrequency of `feed` say to look
  at it again after `at-ms`, or nil when it names no period."
  [feed at-ms]
  (when-let [period (syndication-periods (some-> (:sy/update-period feed)
                                                 str/lower-case))]
    (let [frequency (:sy/update-frequency feed)
          frequency (if (and (number? frequency) (pos? frequency))
                      frequency
                      1)]
      (+ at-ms (quot period frequency)))))

(defn- cadence-due
  "When the recent items of `feed` say to look at it again after `at`, in
  milliseconds: the date of the latest plus the median gap between them,
  or `at` plus that gap when that time is past. It's nil without a gap."
  [feed at]
  (when-let [gap (cadence feed at)]
    (let [at-ms (inst-ms at)
          due   (+ (first (dates feed at-ms)) gap)]
      (if (> due at-ms)
        due
        (+ at-ms gap)))))

(defn next-check
  "When to look at `feed` again for something new, judged at `at`, with the
  `opts` below.

  It's nil when the feed is complete, or when nothing gives a time. The
  time is the first of these that the feed gives:

  - the next occurrence of its update-frequency rule
  - the time `at` plus its ttl
  - the time `at` plus its syndication period divided by its syndication
    frequency
  - the date of its latest item plus the median gap between its recent
    items, or `at` plus that gap when that time is past

  Items dated after `at` don't count, and a feed with fewer than two dated
  items has no gap. The time is at most a year after `at`, the longest
  period that a feed can name.

  The `opts` can give :not-before, the earliest instant that the server
  allows, e.g. the :not-before of a fetch. The answer is then no earlier
  than that instant, even when it's more than a year after `at`. For a
  feed that gives no time, the answer is that instant."
  ([feed at]
   (next-check feed at {}))
  ([feed at {:keys [not-before] :as opts}]
   {:pre [(map? opts)]}
   (when-not (resolve/complete? feed)
     (let [at-ms (inst-ms at)
           ttl   (:rss/ttl feed)
           due   (or (some-> (next-episode feed at) inst-ms)
                     ;; in floating point, so that an absurd ttl cannot overflow
                     (when (and (number? ttl) (pos? ttl))
                       (+ at-ms (* 60000.0 ttl)))
                     (syndication-due feed at-ms)
                     (cadence-due feed at))
           due   (some-> due (min (+ at-ms (syndication-periods "yearly"))))]
       (time/latest (some-> due long time/->inst) not-before)))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast.feed.rss :as rss])
     (def f (rss/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (next-episode f (java.util.Date.))
     (next-check f (java.util.Date.))
     #_.))
