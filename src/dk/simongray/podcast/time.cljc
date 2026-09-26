(ns dk.simongray.podcast.time
  "Dates and durations as they appear in feeds, parsed leniently and formatted
  canonically, from arithmetic and regular expressions alone so that the JVM
  and JavaScript agree.

  Instants are `#inst` values (java.util.Date or js/Date). Durations and
  timestamps within media are seconds: a long when whole, a double otherwise.
  Recurrence rules are dk.simongray.podcast.time.rrule's, built on the calendar
  arithmetic here."
  (:require [clojure.string :as str]))

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
  "The date `[y m d]` that is `days` days after 1970-01-01."
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

(defn ->inst
  "The instant `ms` milliseconds after the epoch."
  [ms]
  #?(:clj  (java.util.Date. (long ms))
     :cljs (js/Date. ms)))

(defn floor-seconds
  "The instant `inst` with its milliseconds dropped, the precision an RFC
  2822 date can carry."
  [inst]
  (->inst (* 1000 (quot (inst-ms inst) 1000))))

(defn ^:no-doc civil->ms
  "Epoch milliseconds of the civil time given in UTC, `offset-minutes` east."
  [y m d hh mm ss ms offset-minutes]
  (-> (days-from-civil y m d)
      (* 86400000)
      (+ (* hh 3600000) (* mm 60000) (* ss 1000) ms)
      (- (* offset-minutes 60000))))

(def ^:no-doc months
  "Month number by lowercase English name, full and abbreviated."
  (into {} (mapcat (fn [i name]
                     [[name i] [(subs name 0 3) i]])
                   (range 1 13)
                   ["january" "february" "march" "april" "may" "june" "july"
                    "august" "september" "october" "november" "december"])))

(def ^:no-doc zones
  "Offsets in minutes of the zone names RFC 822 and RFC 2822 define, plus the
  ones feeds use anyway. Military single letters are obsolete and read as UTC."
  {"ut" 0 "utc" 0 "gmt" 0 "z" 0
   "est" -300 "edt" -240 "cst" -360 "cdt" -300
   "mst" -420 "mdt" -360 "pst" -480 "pdt" -420
   "cet" 60 "cest" 120 "bst" 60 "ist" 60 "wet" 0 "west" 60
   "eet" 120 "eest" 180 "jst" 540 "aest" 600 "aedt" 660})

(def ^:no-doc rfc2822-strict-pattern
  "The format RFC 2822 actually allows for a feed date, weekday included."
  #"(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}(?::\d{2})? (?:[+-]\d{4}|GMT|UT)")

(def ^:no-doc rfc2822-lenient-pattern
  "Groups: day, month name, year, hour, minute, second, zone. Weekday, seconds,
  time and zone are all optional; separators are forgiving."
  #"(?i)^\s*(?:[a-z]{3,9}\.?,?\s+)?(\d{1,2})(?:st|nd|rd|th)?[\s-]+([a-z]{3,9})\.?,?[\s-]+(\d{2,4})(?:[\s,T]+(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(?:([a-z]{1,5}|[+-]\d{1,2}:?\d{2}|(?:gmt|utc)[+-]\d{1,2}:?\d{2}))?\s*$")

(defn- parse-zone
  "The offset in minutes east of UTC named by `s`, or nil when unknown."
  [s]
  (if (nil? s)
    0
    (let [s (str/lower-case s)]
      (or (zones s)
          (when-let [[_ sign hh mm] (re-find #"^(?:gmt|utc)?([+-])(\d{1,2}):?(\d{2})$" s)]
            (* (if (= sign "-") -1 1)
               (+ (* 60 (parse-long hh)) (parse-long mm))))
          (when (re-matches #"[a-z]" s)
            0)))))

(defn- expand-year
  "Two-digit years as RFC 2822 reads them: 00-49 are 2000s, 50-99 are 1900s."
  [y]
  (cond
    (< y 50) (+ y 2000)
    (< y 100) (+ y 1900)
    :else y))

(defn parse-rfc2822
  "Parse the RFC 2822 date `s` into an instant, forgiving the deviations feeds
  are known for: missing weekday, full month names, two-digit years, zone
  names, missing seconds or a missing zone (read as UTC). Returns nil when the
  string is not a date at all."
  [s]
  (when-let [[_ d mon y hh mm ss zone] (re-find rfc2822-lenient-pattern s)]
    (let [m      (months (str/lower-case mon))
          offset (parse-zone zone)]
      (when (and m offset)
        (->inst (civil->ms (expand-year (parse-long y)) m (parse-long d)
                           (if hh (parse-long hh) 0)
                           (if mm (parse-long mm) 0)
                           (if ss (parse-long ss) 0)
                           0
                           offset))))))

(defn rfc2822-strict?
  "Whether `s` is a date RFC 2822 permits, written the way the RSS ecosystem
  expects it."
  [s]
  (boolean (re-matches rfc2822-strict-pattern s)))

(def ^:no-doc iso8601-pattern
  "Groups: year, month, day, hour, minute, second, fraction, zone."
  #"^\s*(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?\s*(Z|z|[+-]\d{2}:?\d{2})?)?\s*$")

(defn parse-iso8601
  "Parse the ISO 8601 date or date-time `s` into an instant, or nil. A missing
  zone is read as UTC."
  [s]
  (when-let [[_ y m d hh mm ss frac zone] (re-find iso8601-pattern s)]
    (let [ms     (if frac (parse-long (subs (str frac "00") 0 3)) 0)
          offset (parse-zone zone)]
      (when offset
        (->inst (civil->ms (parse-long y) (parse-long m) (parse-long d)
                           (if hh (parse-long hh) 0)
                           (if mm (parse-long mm) 0)
                           (if ss (parse-long ss) 0)
                           ms
                           offset))))))

(defn parse-date
  "Parse `s` as an RFC 2822 date, falling back to ISO 8601; nil when neither."
  [s]
  (when (string? s)
    (or (parse-rfc2822 s) (parse-iso8601 s))))

(defn- pad2
  [n]
  (if (< n 10) (str "0" n) (str n)))

(defn ^:no-doc split-ms
  "Break `ms` since the epoch into `[days hh mm ss millis]` in UTC."
  [ms]
  (let [days   (quot (if (neg? ms) (- ms 86399999) ms) 86400000)
        rest   (- ms (* days 86400000))
        millis (rem rest 1000)
        secs   (quot rest 1000)]
    [days (quot secs 3600) (rem (quot secs 60) 60) (rem secs 60) millis]))

(def ^:no-doc weekdays
  "The RFC 2822 weekday names, Sunday first as `days-from-civil` counts."
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(def ^:no-doc month-names
  "The RFC 2822 month names, January first."
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn format-rfc2822
  "Format the instant `inst` as RFC 2822 in GMT, e.g.
  \"Fri, 25 Sep 2026 16:42:40 GMT\"."
  [inst]
  (let [[days hh mm ss] (split-ms (inst-ms inst))
        [y m d]         (civil-from-days days)]
    (str (weekdays (mod (+ days 4) 7)) ", "
         (pad2 d) " " (month-names (dec m)) " " y " "
         (pad2 hh) ":" (pad2 mm) ":" (pad2 ss) " GMT")))

(defn format-iso8601
  "Format the instant `inst` as ISO 8601 in UTC, e.g. \"2026-09-25T16:42:40Z\",
  with milliseconds only when they are not zero."
  [inst]
  (let [[days hh mm ss millis] (split-ms (inst-ms inst))
        [y m d]                (civil-from-days days)]
    (str y "-" (pad2 m) "-" (pad2 d) "T" (pad2 hh) ":" (pad2 mm) ":" (pad2 ss)
         (when (pos? millis)
           (str "." (subs (str (+ 1000 millis)) 1)))
         "Z")))

(defn ^:no-doc whole->long
  "The long equal to `n` when it is a whole number, else `n` itself, so that
  \"16.0\" and \"16\" read as the same value."
  [n]
  (if (and (float? n) (== n (Math/floor n)) (< (abs n) 9.0E15))
    (long n)
    n))

(defn- parse-number
  "The long or double written as `s`, with `,` accepted as decimal separator;
  a whole number is a long whichever way it was written."
  [s]
  (let [s (str/replace s "," ".")]
    (if (str/includes? s ".")
      (whole->long (parse-double s))
      (parse-long s))))

(defn parse-clock
  "Parse a clock-style timestamp such as \"1:02:03\", \"02:03\", \"00:00:02.760\"
  or the SRT-style \"00:00:02,760\" into seconds; nil when `s` is not one."
  [s]
  (when-let [[_ hh mm ss] (re-find #"^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2}(?:[.,]\d+)?)\s*$" s)]
    (+ (* 3600 (if hh (parse-long hh) 0))
       (* 60 (parse-long mm))
       (parse-number ss))))

(defn parse-duration
  "Parse an `<itunes:duration>`-style value into seconds: plain seconds
  (\"3793\", \"3793.5\") or a clock (\"1:03:13\", \"63:13\"). Nil when unreadable."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (or (parse-clock s)
          (when (re-matches #"\d+(?:[.,]\d+)?" s)
            (parse-number s))))))

(defn seconds
  "The number of seconds `x` denotes: a number as it is, a numeric or clock
  string parsed as `parse-duration` does; nil for anything else."
  [x]
  (cond
    (number? x) x
    (string? x) (parse-duration x)
    :else       nil))

(defn format-clock
  "Format `seconds` as \"H:MM:SS\", or \"M:SS\" under an hour, dropping any
  fraction of a second."
  [seconds]
  (let [total (long seconds)
        h     (quot total 3600)
        m     (rem (quot total 60) 60)
        s     (rem total 60)]
    (if (pos? h)
      (str h ":" (pad2 m) ":" (pad2 s))
      (str m ":" (pad2 s)))))

(defn format-timestamp
  "Format `seconds` as the \"HH:MM:SS.mmm\" timestamp used by WebVTT, Podlove
  Simple Chapters and, with `separator` \",\", SRT."
  ([seconds]
   (format-timestamp seconds "."))
  ([seconds separator]
   (let [millis (long (Math/round (* 1000.0 seconds)))
         total  (quot millis 1000)]
     (str (pad2 (quot total 3600)) ":" (pad2 (rem (quot total 60) 60)) ":"
          (pad2 (rem total 60)) separator (subs (str (+ 1000 (rem millis 1000))) 1)))))

(comment
  (parse-rfc2822 "Fri, 25 Sep 2026 16:42:40 +0000")
  (parse-rfc2822 "Fri, 31 Jul 2026 10:02:23 PST")
  (parse-rfc2822 "Thu, 01 Apr 2021 08:00:00 EST")
  (format-rfc2822 (parse-rfc2822 "Fri, 25 Sep 2026 03:00:00 +0200"))
  (parse-iso8601 "2021-09-26T07:30:00.000-0600")
  (parse-duration "02:13:17")
  (format-timestamp 2.76 ",")
  #_.)
