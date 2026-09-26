(ns dk.simongray.podcast.rrule
  "RFC 5545 recurrence rules, as the rrule of `podcast:updateFrequency`
  carries them: parsed into a map of their parts, written back in the RFC's
  order, and evaluated into the instants they produce, from the calendar
  arithmetic of dk.simongray.podcast.time so that the JVM and JavaScript
  agree.

      (parse \"FREQ=WEEKLY;BYDAY=MO,WE\")
      ;; => {:freq \"WEEKLY\" :byday [\"MO\" \"WE\"]}
      (take 2 (occurrences *1 #inst \"2026-01-05T18:00:00Z\"))
      ;; => (#inst \"2026-01-05T18:00:00Z\" #inst \"2026-01-07T18:00:00Z\")"
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.time :as time]))

(def ^:no-doc parts
  "The parts of a recurrence rule, as model keys and as written, in the
  order the RFC lists them."
  [[:freq "FREQ"] [:interval "INTERVAL"] [:count "COUNT"] [:until "UNTIL"]
   [:bysecond "BYSECOND"] [:byminute "BYMINUTE"] [:byhour "BYHOUR"] [:byday "BYDAY"]
   [:bymonthday "BYMONTHDAY"] [:byyearday "BYYEARDAY"] [:byweekno "BYWEEKNO"]
   [:bymonth "BYMONTH"] [:bysetpos "BYSETPOS"] [:wkst "WKST"]])

(def freq-values
  "The values RFC 5545 allows for FREQ."
  #{"SECONDLY" "MINUTELY" "HOURLY" "DAILY" "WEEKLY" "MONTHLY" "YEARLY"})

(defn- part-value
  "The model value of the rule part `k` written as `s`; nil when it does not
  read. Numbers become longs, UNTIL an instant, the BY lists vectors."
  [k s]
  (let [longs (fn [s]
                (let [xs (str/split s #",")]
                  (when (every? #(re-matches #"[+-]?\d+" %) xs)
                    (mapv parse-long xs))))]
    (case k
      :freq  (let [f (str/upper-case s)]
               (when (freq-values f) f))
      :until (when-let [[_ y m d hh mm ss] (re-matches #"(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})Z?)?" s)]
               (time/->inst (time/civil->ms (parse-long y) (parse-long m) (parse-long d)
                                            (if hh (parse-long hh) 0) (if mm (parse-long mm) 0)
                                            (if ss (parse-long ss) 0) 0 0)))
      :byday (let [days (mapv str/upper-case (str/split s #","))]
               (when (every? #(re-matches #"[+-]?\d{0,2}(?:MO|TU|WE|TH|FR|SA|SU)" %) days)
                 days))
      :wkst  (let [d (str/upper-case s)]
               (when (re-matches #"MO|TU|WE|TH|FR|SA|SU" d) d))
      (:interval :count) (when (re-matches #"\d+" s) (parse-long s))
      (:bysecond :byminute :byhour :bymonthday :byyearday :byweekno :bymonth :bysetpos) (longs s)
      s)))

(defn parse
  "Parse the RFC 5545 recurrence rule `s`, \"FREQ=WEEKLY;BYDAY=MO,WE\", into
  a map of its parts by lower-case key: `{:freq \"WEEKLY\" :byday [\"MO\"
  \"WE\"]}`. INTERVAL and COUNT are numbers, UNTIL an instant, the BY lists
  vectors of numbers or day names. Nil when FREQ is missing or invalid or
  a part does not read."
  [s]
  (let [pairs (for [part (str/split (str/replace-first (str/trim (str s)) #"(?i)^RRULE:" "") #";")
                    :when (not (str/blank? part))
                    :let  [[k v] (str/split part #"=" 2)]]
                [(keyword (str/lower-case (str/trim k))) (some-> v str/trim)])
        m     (reduce (fn [m [k v]]
                        (if-some [v' (when v (part-value k v))]
                          (assoc m k v')
                          (reduced nil)))
                      {}
                      pairs)]
    (when (:freq m) m)))

(defn format
  "Write the recurrence rule map `m` (see `parse`) as RFC 5545 does, parts
  in the RFC's order and any other part last."
  [m]
  (let [known (mapv first parts)
        names (into {} parts)
        part  (fn [k v]
                (str (or (names k) (str/upper-case (name k))) "="
                     (cond
                       (inst? v)       (str/replace (time/format-iso8601 v) #"[-:]|\.\d+" "")
                       (sequential? v) (str/join "," (map str v))
                       :else           (str v))))]
    (str/join ";" (concat (for [k known
                                :let [v (get m k)]
                                :when (and (some? v) (not (and (coll? v) (empty? v))))]
                            (part k v))
                          (for [[k v] m
                                :when (not (contains? names k))]
                            (part k v))))))

(def ^:no-doc weekday-codes
  "The RFC 5545 weekday codes, Sunday first as `time/days-from-civil` counts."
  ["SU" "MO" "TU" "WE" "TH" "FR" "SA"])

(def ^:no-doc weekday-numbers
  "Weekday number, 0 Sunday to 6 Saturday, by RFC 5545 code."
  (zipmap weekday-codes (range)))

(def evaluable-parts
  "The rule parts `occurrences` evaluates. A rule with any other part yields
  nil rather than wrong dates."
  #{:freq :interval :count :until :byday :bymonthday :bymonth :wkst})

(defn- weekday-of
  "The weekday, 0 Sunday to 6 Saturday, of the day `days` after the epoch,
  which was a Thursday."
  [days]
  (mod (+ 4 days) 7))

(defn- days-in-month
  [y m]
  (- (if (= m 12) (time/days-from-civil (inc y) 1 1) (time/days-from-civil y (inc m) 1))
     (time/days-from-civil y m 1)))

(defn- parse-byday
  "The BYDAY value `s` as `[ordinal weekday]`: \"MO\" is [nil 1], \"-1FR\"
  is [-1 5]."
  [s]
  (let [[_ ordinal code] (re-matches #"([+-]?\d{1,2})?(SU|MO|TU|WE|TH|FR|SA)" s)]
    [(some-> ordinal parse-long) (weekday-numbers code)]))

(defn- month-days
  "The days of the month `y`-`m` that `rule` selects: BYMONTHDAY, negative
  from the end, intersected with BYDAY when both are given; BYDAY alone, with
  ordinals such as -1FR or every such weekday; else `default-day` when the
  month has it."
  [{:keys [bymonthday byday]} y m default-day]
  (let [n        (days-in-month y m)
        first    (time/days-from-civil y m 1)
        weekdays (fn [wd] (filterv #(= wd (weekday-of (+ first (dec %)))) (range 1 (inc n))))
        by-day   (when byday
                   (set (mapcat (fn [s]
                                  (let [[ordinal wd] (parse-byday s)
                                        ds           (weekdays wd)]
                                    (cond
                                      (nil? ordinal) ds
                                      (pos? ordinal) (when (<= ordinal (count ds)) [(nth ds (dec ordinal))])
                                      :else          (when (<= (- ordinal) (count ds)) [(nth ds (+ (count ds) ordinal))]))))
                                byday)))
        by-month-day (when bymonthday
                       (set (keep #(let [d (if (neg? %) (+ n 1 %) %)] (when (<= 1 d n) d)) bymonthday)))]
    (sort (cond
            (and by-month-day by-day) (filter by-day by-month-day)
            by-month-day              by-month-day
            by-day                    by-day
            (<= default-day n)        [default-day]
            :else                     []))))

(defn occurrences
  "The instants the recurrence rule `rule` (see `parse`) produces from the
  instant `start`, in order, as a lazy sequence: `start`'s time of day on
  every day the rule selects, for up to a century. DAILY, WEEKLY, MONTHLY
  and YEARLY rules with INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH
  and WKST are evaluated; anything else, such as an hourly rule or BYHOUR,
  yields nil, since a wrong date is worse than none."
  [{:keys [freq interval until byday bymonth wkst] n :count :as rule} start]
  (when (and (map? rule)
             (inst? start)
             (every? evaluable-parts (keys rule))
             (#{"DAILY" "WEEKLY" "MONTHLY" "YEARLY"} freq)
             (not (and (= "YEARLY" freq) byday (not bymonth))))
    (let [[day0 hh mm ss millis] (time/split-ms (inst-ms start))
          time-of-day (+ (* hh 3600000) (* mm 60000) (* ss 1000) millis)
          [y0 m0 d0]  (time/civil-from-days day0)
          interval    (or interval 1)
          months      (some-> bymonth set)
          in-month?   (fn [days] (or (nil? months) (contains? months (second (time/civil-from-days days)))))
          days
          (case freq
            "DAILY"   (let [weekdays (some->> byday (map (comp second parse-byday)) set)]
                        (->> (iterate #(+ % interval) day0)
                             (take 36525)
                             (filter in-month?)
                             (filter #(or (nil? weekdays) (contains? weekdays (weekday-of %))))))
            "WEEKLY"  (let [week-start (weekday-numbers (or wkst "MO"))
                            offset     #(mod (- % week-start) 7)
                            week0      (- day0 (offset (weekday-of day0)))
                            weekdays   (or (seq (sort (map (comp second parse-byday) byday)))
                                           [(weekday-of day0)])]
                        (->> (iterate #(+ % (* 7 interval)) week0)
                             (take 5218)
                             (mapcat (fn [week] (map #(+ week (offset %)) weekdays)))
                             (filter #(>= % day0))
                             (filter in-month?)))
            "MONTHLY" (->> (iterate inc 0)
                           (take 1200)
                           (map #(let [total (+ (dec m0) (* % interval))]
                                   [(+ y0 (quot total 12)) (inc (mod total 12))]))
                           (filter (fn [[_ m]] (or (nil? months) (contains? months m))))
                           (mapcat (fn [[y m]] (map #(time/days-from-civil y m %) (month-days rule y m d0))))
                           (filter #(>= % day0)))
            "YEARLY"  (->> (iterate #(+ % interval) y0)
                           (take 100)
                           (mapcat (fn [y]
                                     (for [m (or (seq (sort bymonth)) [m0])
                                           d (month-days rule y m d0)]
                                       (time/days-from-civil y m d))))
                           (filter #(>= % day0))))
          insts (map #(time/->inst (+ (* % 86400000) time-of-day)) days)
          insts (if until (take-while #(<= (inst-ms %) (inst-ms until)) insts) insts)]
      (if n (take n insts) insts))))

(defn next-occurrence
  "The first instant after `at` that `rule` produces from `start`; nil when
  the rule cannot be evaluated or has run out."
  [rule start at]
  (first (drop-while #(<= (inst-ms %) (inst-ms at)) (occurrences rule start))))

(comment
  (parse "FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261231T000000Z")
  (format *1)
  (take 3 (occurrences (parse "FREQ=MONTHLY;BYDAY=-1FR") #inst "2026-01-05T18:00:00Z"))
  #_.)
