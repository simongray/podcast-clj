(ns dk.simongray.podcast.util.time
  "Dates and durations as they appear in feeds, parsed leniently and
  formatted canonically, from arithmetic and regular expressions alone so
  that the JVM and JavaScript agree.

  Instants are inst values, java.util.Date or js/Date. Durations and
  timestamps within media are seconds: a long when whole, and a double
  otherwise. The recurrence rules of dk.simongray.podcast.util.time.rrule
  are built on the calendar arithmetic here."
  (:require [clojure.string :as str]))

;; days_from_civil and civil_from_days of Howard Hinnant, in
;; chrono-Compatible Low-Level Date Algorithms
(defn ^:no-doc days-from-civil
  "The number of days from 1970-01-01 to the date `y`-`m`-`d`."
  [y m d]
  (let [y   (if (<= m 2) (dec y) y)
        era (quot (if (neg? y) (- y 399) y) 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (if (> m 2) (- m 3) (+ m 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn ^:no-doc civil-from-days
  "The date that is `days` days after 1970-01-01, as a vector of the year,
  the month and the day."
  [days]
  (let [z   (+ days 719468)
        era (quot (if (neg? z) (- z 146096) z) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y   (+ yoe (* era 400))
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp  (quot (+ (* 5 doy) 2) 153)
        d   (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m   (if (< mp 10) (+ mp 3) (- mp 9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn ^:no-doc civil?
  "Whether `y`-`m`-`d` `hh`:`mm`:`ss` is a real date and time:

  - a day that the month has
  - an hour up to 23, or 24:00:00 for the end of the day
  - up to 60 seconds, for a leap second"
  [y m d hh mm ss]
  (and (<= 1 m 12)
       (<= 1 d 31)
       (= [y m d] (civil-from-days (days-from-civil y m d)))
       (or (< hh 24) (= [hh mm ss] [24 0 0]))
       (< mm 60)
       (<= ss 60)))

(def ^:no-doc max-whole
  "The largest whole number that podcast-clj gives, just under 2^53. Up to
  it, both platforms hold every whole number exactly."
  9.0E15)

(defn ->inst
  "The instant `ms` milliseconds after the epoch."
  [ms]
  #?(:clj  (java.util.Date. (long ms))
     :cljs (js/Date. ms)))

(defn date
  "The instant `inst`, of any kind that inst? takes, as the instant of the
  platform: java.util.Date on the JVM, and js/Date in JavaScript.

  A java.time.Instant, which EDN can't print, comes out as one that EDN
  writes as an inst."
  [inst]
  (->inst (inst-ms inst)))

(defn floor-seconds
  "The instant `inst` with its milliseconds dropped, the precision an RFC
  2822 date can carry."
  [inst]
  (->inst (* 1000 (quot (inst-ms inst) 1000))))

(defn ^:no-doc civil->ms
  "The epoch milliseconds of the civil time `y`-`m`-`d` `hh`:`mm`:`ss` and
  `ms` milliseconds, `offset-minutes` east of UTC."
  [y m d hh mm ss ms offset-minutes]
  (-> (days-from-civil y m d)
      (* 86400000)
      (+ (* hh 3600000) (* mm 60000) (* ss 1000) ms)
      (- (* offset-minutes 60000))))

(def ^:no-doc months
  "The month number of each English month name in lower case, full and
  abbreviated."
  (into {} (mapcat (fn [i name]
                     [[name i] [(subs name 0 3) i]])
                   (range 1 13)
                   ["january" "february" "march" "april" "may" "june" "july"
                    "august" "september" "october" "november" "december"])))

(def ^:no-doc zones
  "The offset in minutes of each zone name that RFC 822 and RFC 2822
  define, and of the ones that feeds use anyway. The obsolete military
  letters read as UTC."
  {"ut" 0 "utc" 0 "gmt" 0 "z" 0
   "est" -300 "edt" -240 "cst" -360 "cdt" -300
   "mst" -420 "mdt" -360 "pst" -480 "pdt" -420
   "cet" 60 "cest" 120 "bst" 60 "ist" 60 "wet" 0 "west" 60
   "eet" 120 "eest" 180 "jst" 540 "aest" 600 "aedt" 660
   "hst" -600 "hdt" -540 "akst" -540 "akdt" -480 "nzst" 720 "nzdt" 780})

(def ^:no-doc rfc2822-strict-pattern
  "The RFC 2822 date in the narrow form that the examples of RSS 2.0 have,
  weekday included."
  #"(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}(?::\d{2})? (?:[+-]\d{4}|GMT|UT)")

(def ^:no-doc rfc2822-lenient-pattern
  "A lenient RFC 2822 date, with groups for the day, the month name, the
  year, the hour, the minute, the second and the zone.

  The weekday, the seconds, the time and the zone are optional, and the
  separators are forgiving. The spaces before the zone belong to the
  optional zone group, so that they and the trailing spaces never compete
  for one run of whitespace, which backtracks quadratically."
  #"(?i)^\s*(?:[a-z]{3,9}\.?,?\s+)?(\d{1,2})(?:st|nd|rd|th)?[\s-]+([a-z]{3,9})\.?,?[\s-]+(\d{2,4})(?:[\s,T]+(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?(?:\s*([a-z]{1,5}|[+-]\d{1,2}:?\d{2}|(?:gmt|utc)[+-]\d{1,2}:?\d{2}))?\s*$")

;; RFC 5322, section 4.3: a zone that is not known reads as -0000
(defn- parse-zone
  "The offset in minutes east of UTC that `s` names, or 0 when there's
  none.

  A zone name that the table lacks reads as UTC too, as a missing zone
  does, so that the date isn't lost. It's nil for AM and PM, which say
  that the time is on a 12-hour clock, and for an offset beyond 23:59."
  [s]
  (if (nil? s)
    0
    (let [s (str/lower-case s)]
      (or (zones s)
          (when-let [[_ sign hh mm] (re-find #"^(?:gmt|utc)?([+-])(\d{1,2}):?(\d{2})$" s)]
            (let [h (parse-long hh)
                  m (parse-long mm)]
              (when (and (< h 24) (< m 60))
                (* (if (= sign "-") -1 1) (+ (* 60 h) m)))))
          (when (and (re-matches #"[a-z]+" s) (not (#{"am" "pm"} s)))
            0)))))

(defn- expand-year
  "The two- or three-digit year `y` as RFC 5322, section 4.3, reads it:
  00 to 49 are the 2000s, and the others count from 1900."
  [y]
  (cond
    (< y 50)   (+ y 2000)
    (< y 1000) (+ y 1900)
    :else      y))

;; RFC 5322, sections 3.3 and 4.3, which replaced RFC 2822. RSS 2.0 names
;; RFC 822, section 5.
(defn parse-rfc2822
  "The instant of the RFC 2822 date `s`, or nil when it isn't a date at
  all, or names a day or a time that doesn't exist, such as 31 February.

  It forgives the deviations that feeds are known for:

  - a missing weekday
  - full month names
  - two-digit years
  - zone names
  - missing seconds
  - a missing or unknown zone, which reads as UTC"
  [s]
  (when-let [[_ d mon y hh mm ss zone] (re-find rfc2822-lenient-pattern s)]
    (let [m          (months (str/lower-case mon))
          offset     (parse-zone zone)
          y          (expand-year (parse-long y))
          d          (parse-long d)
          [hh mm ss] (map #(if % (parse-long %) 0) [hh mm ss])]
      (when (and m offset (civil? y m d hh mm ss))
        (->inst (civil->ms y m d hh mm ss 0 offset))))))

(defn rfc2822-strict?
  "Whether `s` is a date that RFC 2822 permits, written the way that RSS
  feeds are expected to write it, on a day and at a time that exist."
  [s]
  (boolean (and (re-matches rfc2822-strict-pattern s) (parse-rfc2822 s))))

(def ^:no-doc iso8601-pattern
  "An ISO 8601 date or date-time, with groups for the year, the month, the
  day, the hour, the minute, the second, the fraction and the zone. As in
  rfc2822-lenient-pattern, the spaces before the zone belong to its group."
  #"^\s*(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?(?:\s*(Z|z|[+-]\d{2}:?\d{2}))?)?\s*$")

;; RFC 3339, section 5.6, and the forms of ISO 8601 in its appendix A
(defn parse-iso8601
  "The instant of the ISO 8601 date or date-time `s`, or nil, as for a day
  or a time that doesn't exist. A missing zone reads as UTC."
  [s]
  (when-let [[_ y m d hh mm ss frac zone] (re-find iso8601-pattern s)]
    (let [ms               (if frac (parse-long (subs (str frac "00") 0 3)) 0)
          offset           (parse-zone zone)
          [y m d hh mm ss] (map #(if % (parse-long %) 0) [y m d hh mm ss])]
      (when (and offset (civil? y m d hh mm ss))
        (->inst (civil->ms y m d hh mm ss ms offset))))))

(defn parse-date
  "The instant of `s` as an RFC 2822 date, or else as ISO 8601, or nil when
  it's neither."
  [s]
  (when (string? s)
    (or (parse-rfc2822 s) (parse-iso8601 s))))

(defn- pad2
  [n]
  (if (< n 10) (str "0" n) (str n)))

(defn ^:no-doc split-ms
  "The milliseconds `ms` since the epoch as a vector of the days, hours,
  minutes, seconds and milliseconds in UTC."
  [ms]
  (let [days   (quot (if (neg? ms) (- ms 86399999) ms) 86400000)
        rest   (- ms (* days 86400000))
        millis (rem rest 1000)
        secs   (quot rest 1000)]
    [days (quot secs 3600) (rem (quot secs 60) 60) (rem secs 60) millis]))

(def ^:no-doc weekdays
  "The weekday names of RFC 2822, Sunday first, as days-from-civil counts."
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(def ^:no-doc month-names
  "The month names of RFC 2822, January first."
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn format-rfc2822
  "The instant `inst` as an RFC 2822 date in GMT, e.g.
  \"Fri, 25 Sep 2026 16:42:40 GMT\"."
  [inst]
  (let [[days hh mm ss] (split-ms (inst-ms inst))
        [y m d]         (civil-from-days days)]
    (str (weekdays (mod (+ days 4) 7)) ", "
         (pad2 d) " " (month-names (dec m)) " " y " "
         (pad2 hh) ":" (pad2 mm) ":" (pad2 ss) " GMT")))

(defn format-iso8601
  "The instant `inst` as ISO 8601 in UTC, e.g. \"2026-09-25T16:42:40Z\", with
  milliseconds only when they aren't zero."
  [inst]
  (let [[days hh mm ss millis] (split-ms (inst-ms inst))
        [y m d]                (civil-from-days days)]
    (str y "-" (pad2 m) "-" (pad2 d) "T" (pad2 hh) ":" (pad2 mm) ":" (pad2 ss)
         (when (pos? millis)
           (str "." (subs (str (+ 1000 millis)) 1)))
         "Z")))

(defn ^:no-doc whole->long
  "The long equal to `n` when it's a whole number, or else `n` itself, so
  that \"16.0\" and \"16\" read as the same value."
  [n]
  (if (and (float? n) (== n (Math/floor n)) (< (abs n) max-whole))
    (long n)
    n))

(defn- parse-number
  "The long or double written as `s`, with a comma accepted as the decimal
  separator, or nil when the number is too large to hold. A whole number
  is a long, however it was written."
  [s]
  (let [s (str/replace s "," ".")
        n (if (str/includes? s ".") (parse-double s) (parse-long s))]
    (when (and n (not (infinite? n)))
      (whole->long n))))

(defn parse-clock
  "The seconds of the clock-style timestamp `s`, such as \"1:02:03\",
  \"02:03\", \"00:00:02.760\" or the SRT-style \"00:00:02,760\", or nil when
  `s` isn't one."
  [s]
  (when-let [[_ hh mm ss] (re-find #"^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2}(?:[.,]\d+)?)\s*$" s)]
    (+ (* 3600 (if hh (parse-long hh) 0))
       (* 60 (parse-long mm))
       (parse-number ss))))

(defn parse-duration
  "The seconds of the duration `s`, as itunes:duration writes it, or nil
  when it doesn't read. It's plain seconds, e.g. \"3793\" or \"3793.5\", or
  a clock, e.g. \"1:03:13\" or \"63:13\"."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (or (parse-clock s)
          (when (re-matches #"\d+(?:[.,]\d+)?" s)
            (parse-number s))))))

(defn seconds
  "The number of seconds that `x` denotes: a number as it is, or a numeric
  or clock string as parse-duration reads it, and nil for anything else."
  [x]
  (cond
    (number? x) x
    (string? x) (parse-duration x)
    :else       nil))

(defn ^:no-doc countable?
  "Whether `x` is a number of seconds that format-clock and
  format-timestamp can write, one that both platforms count exactly."
  [x]
  (and (number? x) (< (abs x) max-whole)))

(defn format-clock
  "The `seconds` as \"H:MM:SS\", or \"M:SS\" under an hour, without any
  fraction of a second and with a minus sign when negative. It's nil when
  `seconds` isn't a number, or is beyond max-whole."
  [seconds]
  (when (countable? seconds)
    (let [total (long seconds)
          t     (abs total)
          h     (quot t 3600)
          m     (rem (quot t 60) 60)
          s     (rem t 60)]
      (str (when (neg? total) "-")
           (if (pos? h)
             (str h ":" (pad2 m) ":" (pad2 s))
             (str m ":" (pad2 s)))))))

(defn format-timestamp
  "The `seconds` as the \"HH:MM:SS.mmm\" timestamp of WebVTT and Podlove
  Simple Chapters, or of SRT with \",\" as `separator`.

  A negative time, which those formats lack, is zero. It's nil when
  `seconds` isn't a number, or is beyond max-whole."
  ([seconds]
   (format-timestamp seconds "."))
  ([seconds separator]
   (when (countable? seconds)
     (let [millis (max 0 (long (Math/round (* 1000.0 seconds))))
           total  (quot millis 1000)]
       (str (pad2 (quot total 3600)) ":" (pad2 (rem (quot total 60) 60)) ":"
            (pad2 (rem total 60)) separator (subs (str (+ 1000 (rem millis 1000))) 1))))))

(comment
  (parse-rfc2822 "Fri, 25 Sep 2026 16:42:40 +0000")
  (parse-rfc2822 "Fri, 31 Jul 2026 10:02:23 PST")
  (parse-rfc2822 "Thu, 01 Apr 2021 08:00:00 EST")
  (format-rfc2822 (parse-rfc2822 "Fri, 25 Sep 2026 03:00:00 +0200"))
  (parse-iso8601 "2021-09-26T07:30:00.000-0600")
  (parse-duration "02:13:17")
  (format-timestamp 2.76 ",")
  #_.)
