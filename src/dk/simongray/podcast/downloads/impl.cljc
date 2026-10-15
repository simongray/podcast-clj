(ns ^:no-doc dk.simongray.podcast.downloads.impl
  "The internals of dk.simongray.podcast.downloads: the parts of a plan
  that select, keep, retry and fit the downloads within the limits, the
  checks of problems and the plain letters of file-name."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.criteria.impl :as criteria.impl]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.wary-fetch.headers :as headers]))

(defn amount?
  "Whether `x` is an amount of a rule: a number that isn't NaN and isn't
  negative, e.g. a count or seconds."
  [x]
  (and (number? x)
       (== x x)
       (not (neg? x))))

(def checks
  "The tests of the values of the rules, by key."
  {:queue                #(or (nil? %) (boolean? %) (amount? %))
   :filters              #(or (nil? %)
                              (and (map? %)
                                   (every? (some-fn nil? amount?) (vals %))))
   :max-episodes         #(or (nil? %) (amount? %))
   :max-bytes            #(or (nil? %) (amount? %))
   :max-file-bytes       #(or (nil? %) (amount? %))
   :make-room?           #(or (nil? %) (boolean? %))
   :delete-unsubscribed? #(or (nil? %) (boolean? %))
   :default              #(or (nil? %) (map? %))
   :shows                #(or (nil? %) (map? %))})

(defn held
  "The episode ids of `profile` under each name in keeps, e.g. the starred
  ones under :starred."
  [profile]
  (let [started? (fn [[_ {:keys [position played?]}]]
                   (and (not played?)
                        (number? position)
                        (pos? position)))]
    {:starred     (set (profile.marks/starred profile))
     :queued      (set (profile.queue/queue profile))
     :playlists   (into #{} cat (vals (profile.queue/playlists profile)))
     :in-progress (into #{}
                        (comp (filter started?) (map first))
                        (profile/episode-entries profile))}))

(defn- guid-of
  [{:keys [url]} feed]
  (guid/feed-key feed (if (ifn? url) (url feed) url)))

(defn- selections
  "The ids of the episodes that the rule in `ctx` of each show of `feeds`
  selects, in order, by feed guid, leaving the hidden ones out unless the
  rule names :hidden?.

  Shows without a feed guid are left out, and so are those whose rule has
  a problem or neither downloads nor trims."
  [{:keys [rule-fn problems] :as ctx} feeds]
  (let [criteria #(-> (select-keys % [:where :order :limit :scale])
                      (update :where criteria/visible))]
    (into {}
          (for [feed feeds
                :let  [feed-guid (guid-of ctx feed)
                       own       (rule-fn feed-guid)]
                :when (and feed-guid
                           (or (:download? own) (:trim? own))
                           (not (contains? problems feed-guid)))
                :let  [options (merge ctx (criteria (:episodes own)))
                       found   (criteria/episodes feed options)]]
            [feed-guid (mapv #(vector feed-guid (guid/item-guid %)) found)]))))

(defn- catalogue
  "The items of `feeds` with the episode `ids`, by id, with the :url of
  `ctx` for feeds without a guid."
  [ctx feeds ids]
  (let [wanted (update-vals (group-by first ids) #(into #{} (map second) %))]
    (into {}
          (for [feed feeds
                :let [feed-guid  (guid-of ctx feed)
                      item-guids (get wanted feed-guid)]
                :when (and feed-guid item-guids)
                item (:rss/items feed)
                :let [item-guid (guid/item-guid item)]
                :when (and item-guid (contains? item-guids item-guid))]
            [[feed-guid item-guid] item]))))

(defn- free?
  "Whether the rules of `ctx` allow deleting the `file` of the episode
  `id`. They don't when the file is gone or has :keep?, when the show's
  rule has a problem, or when its :keep covers the episode."
  [{:keys [rule-fn problems held] :as ctx} id file]
  (not (or (:removed? file)
           (:keep? file)
           (contains? problems (first id))
           (let [kept (:keep (rule-fn (first id)))]
             (some #(contains? (get held %) id) kept)))))

(defn- instants
  [xs]
  (seq (map inst-ms (filter inst? xs))))

(defn- played-ms
  "When the listener played the episode of the entry `e` of a profile, in
  milliseconds: its :played-at, or else its stamp."
  [{:keys [played-at updated-at] :as e}]
  (first (instants [played-at updated-at])))

(defn- left?
  "Whether the listener left the show of the episode `id` in `profile`: it
  has no live subscription, and the episode wasn't added on its own."
  [profile [feed-guid :as id]]
  (not (or (profile/subscribed? profile feed-guid)
           (contains? (profile/remote-items profile) id))))

(defn- expired
  "The reason that the rules in `ctx` delete the `file` of the episode
  `id`, given the ids that the rule of its show `selected`, or nil to keep
  the file.

  The reason is :unsubscribed, :played, :inactive or :trimmed. The
  `selected` ids are nil for a show whose feed wasn't given."
  [{:keys [profile rules rule-fn at] :as ctx} selected id file]
  (let [{:keys [played inactive trim?]} (rule-fn (first id))
        e      (profile/episode-entry profile id)
        {:keys [played? reported-at updated-at]} e
        past?  (fn [from seconds]
                 (and from
                      seconds
                      (<= (+ from (* 1000 seconds)) (inst-ms at))))
        active (some->> (instants [(:at file) reported-at updated-at])
                        (apply max))]
    (cond
      (and (:delete-unsubscribed? rules) (left? profile id)) :unsubscribed
      (and played? (past? (played-ms e) played))             :played
      (and (not played?) (past? active inactive))            :inactive
      (and trim? selected (not (contains? selected id)))     :trimmed)))

(defn- gone
  "The plan entry for deleting the `file` of the episode `id`, for
  `reason`."
  [id file reason]
  (cond-> {:id id :reason reason}
    (:bytes file) (assoc :bytes (:bytes file))))

(defn- deletes
  "The plan entries for the files of `ctx` that their shows' rules delete,
  in order of id. The `selected` ids are those that each rule selects, by
  feed guid."
  [{:keys [files] :as ctx} selected]
  (let [selected (update-vals selected set)]
    (for [[id file] (criteria.impl/sorted-by (comp pr-str key) files)
          :when (free? ctx id file)
          :let [reason (expired ctx (get selected (first id)) id file)]
          :when reason]
      (gone id file reason))))

(defn- spare
  "The plan entries for the `files` that can go to make room for a
  download, the earliest played first. They're the files of played
  episodes that the rules of `ctx` allow deleting."
  [{:keys [profile] :as ctx} files]
  (let [spare?   (fn [[id file]]
                   (and (profile/played? profile id)
                        (free? ctx id file)))
        earliest (fn [[id _]]
                   (let [e (profile/episode-entry profile id)]
                     [(or (played-ms e) 0) (pr-str id)]))]
    (->> files
         (filter spare?)
         (criteria.impl/sorted-by earliest)
         (mapv (fn [[id file]] (gone id file :room))))))

(defn- dismissed?
  "Whether the listener took the episode `id` out of the inbox of `profile`
  without queueing it."
  [profile id]
  (boolean (and (:removed? (get-in profile [:inbox id]))
                (not (profile.queue/queued? profile id)))))

(defn- retrying?
  "Whether the plan of `ctx` lists the failed download of `file` again: once
  the wait of its :retry-waits after the last failure has passed."
  [{:keys [at retry-waits] :as ctx} {:keys [failed-at failures] :as file}]
  (when (and failed-at (not (:removed? file)))
    (let [n    (if (pos-int? failures) failures 1)
          wait (nth retry-waits (min (dec n) (dec (count retry-waits))) 0)]
      (<= (+ (inst-ms failed-at) (* 1000 wait)) (inst-ms at)))))

(defn- wanted?
  "Whether to download the episode `id`, by the profile and files of `ctx`.
  It's false when the device has or had the file, but for a failed
  download whose wait has passed, or when the listener played or
  dismissed the episode."
  [{:keys [profile files] :as ctx} id]
  (not (or (and (contains? files id)
                (not (retrying? ctx (get files id))))
           (profile/played? profile id)
           (dismissed? profile id))))

(defn resume-headers
  "The headers for the rest of the partial `file` that
  dk.simongray.podcast.downloads/resume-headers gives."
  [{:keys [received etag last-modified date] :as file}]
  (let [validator (headers/if-range {"etag"          etag
                                     "last-modified" last-modified
                                     "date"          date})]
    (when (and (pos-int? received) validator)
      {"Range"    (str "bytes=" received "-")
       "If-Range" validator})))

(defn- resumed
  "The plan entry `entry` for downloading the `file` of an episode again,
  with the :received bytes and the :headers that ask for the rest, when
  the file broke off from the same URL and can go on."
  [entry file]
  (let [headers (when (= (:url entry) (:url file))
                  (resume-headers file))]
    (cond-> entry
      headers (assoc :received (:received file) :headers headers))))

(defn- download
  "The plan entry for downloading the `item` of the episode `id` for
  `reason`, with the options in `ctx`, or nil without a media file.

  The file is the enclosure that the :enclosure of `ctx` selects among
  those that can be downloaded, the audio or the video as the listener
  watches the show unless the :enclosure says. The bytes are the length in
  the feed, or else what the :estimate-fn of `ctx` gives for the item and
  the enclosure. A download that broke off goes on where it can."
  [ctx id item reason]
  (let [video?    (profile/prefers-video? (:profile ctx) (first id))
        opts      (-> (merge {:video? video?} (:enclosure ctx))
                      (update :playable-pred
                              #(every-pred resolve/downloadable?
                                           (or % any?))))
        enclosure (some-> item (resolve/enclosure opts))
        estimator (:estimate-fn ctx)]
    (when-let [{:keys [url type length]} enclosure]
      (-> {:id     id
           :url    url
           :bytes  (if (and (number? length) (pos? length))
                     length
                     (estimator item enclosure))
           :reason reason}
          (cond-> type (assoc :type type))
          (resumed (get-in ctx [:files id]))))))

(defn- front
  "The episode ids at the front of the queue of `profile` that the rules'
  `queue` value asks for: all, a number of them, or none."
  [profile queue]
  (cond
    (true? queue)   (profile.queue/queue profile)
    (number? queue) (vec (take queue (profile.queue/queue profile)))
    :else           []))

(defn- filtered
  "The episode ids of `feeds` that the saved filters named in the rules of
  `ctx` select, filter by filter in order of name, each in its own order
  and up to the most that the rules give it, leaving the hidden ones out
  unless it names :hidden?. A filter that the profile lacks, or that has
  a problem, selects none."
  [{:keys [profile rules] :as ctx} feeds]
  (let [saved (profile/filters profile)
        ids   (fn [[name most]]
                (let [criteria (get saved name)
                      limit    (->> [most (:limit criteria)]
                                    (filter number?)
                                    (apply min ##Inf))]
                  (when (and criteria (nil? (criteria/problem criteria)))
                    (for [[feed item] (criteria/episodes
                                       feeds
                                       (-> (merge ctx criteria)
                                           (update :where criteria/visible)
                                           (cond-> (< limit ##Inf)
                                             (assoc :limit limit))))]
                      [(guid-of ctx feed) (guid/item-guid item)]))))]
    (mapcat ids (sort-by (comp str key) (:filters rules)))))

(defn- downloads
  "The downloads that the rules of `ctx` ask for, the most wanted first,
  given the `selected` ids by feed guid and the `feeds` of the episodes.

  These are the front of the queue, in order, then the episodes of the
  saved filters, and then the episodes that the shows' rules select, the
  newest first."
  [{:keys [profile rules rule-fn] :as ctx} selected feeds]
  (let [queued     (front profile (:queue rules))
        by-filter  (remove (set queued) (distinct (filtered ctx feeds)))
        downloads? (fn [[feed-guid _]] (:download? (rule-fn feed-guid)))
        chosen     (->> selected
                        (filter downloads?)
                        (mapcat val)
                        (remove (set (concat queued by-filter)))
                        distinct)
        items      (catalogue ctx feeds (concat queued by-filter chosen))
        newest     (fn [id]
                     (let [ms (some-> (get items id) resolve/published inst-ms)]
                       [(- (or ms 0)) (pr-str id)]))
        entry      (fn [reason id]
                     (when (wanted? ctx id)
                       (download ctx id (get items id) reason)))]
    (concat (keep #(entry :queue %) queued)
            (keep #(entry :filter %) by-filter)
            (keep #(entry :rule %) (criteria.impl/sorted-by newest chosen)))))

(defn- over
  "How far `total` and one more file of `bytes` go over the limits of
  `rules`, as a map of :episodes and :bytes, each 0 within its limit. The
  `total` is a map of the same shape."
  [{:keys [max-episodes max-bytes] :as rules} total bytes]
  {:episodes (if max-episodes
               (max 0 (- (inc (:episodes total)) max-episodes))
               0)
   :bytes    (if max-bytes
               (max 0 (- (+ (:bytes total) bytes) max-bytes))
               0)})

(defn- room
  "The fewest files from the start of `spare` that free the `need`, a map
  of :episodes and :bytes, or nil when all of `spare` isn't enough."
  [spare {:keys [episodes bytes] :as need}]
  (let [freed (reductions + 0 (map #(:bytes % 0) spare))]
    (some (fn [[n sum]]
            (when (and (>= n episodes) (>= sum bytes))
              (take n spare)))
          (map vector (range) freed))))

(defn- placed
  "The `plan` with the download `entry` added within the limits of
  `rules`.

  With room for the file, the entry goes under :download. With
  :make-room?, files from :spare move to :delete until it fits. Otherwise,
  or when the file is above :max-file-bytes, the entry goes under
  :waiting, with the limit that stopped it as :limit."
  [{:keys [max-file-bytes] :as rules} {:keys [total spare] :as plan} entry]
  (let [too-big? (and max-file-bytes (> (:bytes entry) max-file-bytes))
        need     (over rules total (:bytes entry))
        clear    (cond
                   too-big?                   nil
                   (every? zero? (vals need)) []
                   (:make-room? rules)        (room spare need))]
    (if clear
      (-> plan
          (update :download conj entry)
          (update :delete into clear)
          (assoc :spare (subvec spare (count clear))
                 :total {:episodes (- (inc (:episodes total)) (count clear))
                         :bytes    (- (+ (:bytes total) (:bytes entry))
                                      (reduce + (map #(:bytes % 0) clear)))}))
      (let [limit (cond
                    too-big?                :max-file-bytes
                    (pos? (:episodes need)) :max-episodes
                    :else                   :max-bytes)]
        (update plan :waiting conj (assoc entry :limit limit))))))

(defn size
  "The bytes that `file` takes on the device: those received of a download
  that failed, and else its :bytes, which a download that runs expects."
  [file]
  (let [n (if (:failed-at file) (:received file) (:bytes file))]
    (if (number? n) n 0)))

(defn on-device
  "The entries of the `files` of a device that
  dk.simongray.podcast.downloads/on-device gives."
  [files]
  (into {}
        (remove (fn [[_ file]]
                  (or (:removed? file)
                      (and (:failed-at file) (zero? (size file))))))
        files))

(defn planned
  "The plan for the context `ctx` and the `feeds`."
  [{:keys [files rules] :as ctx} feeds]
  (let [selected  (selections ctx feeds)
        going     (vec (deletes ctx selected))
        ;; a download that's tried again counts as its download
        retried   (keep (fn [[id file]] (when (retrying? ctx file) id)) files)
        staying   (apply dissoc (on-device files)
                         (concat (map :id going) retried))
        total     {:episodes (count staying)
                   :bytes    (reduce + (map size (vals staying)))}
        start     {:download []
                   :delete   going
                   :waiting  []
                   :spare    (spare ctx staying)
                   :total    total}]
    (-> (reduce #(placed rules %1 %2) start (downloads ctx selected feeds))
        (dissoc :spare :total))))

(def unaccented
  "The letters that keep their form when their accents are taken off, as
  the letters of a name that every file system takes."
  {\æ "ae" \Æ "AE" \ø "oe" \Ø "OE" \œ "oe" \Œ "OE" \ß "ss" \ł "l" \Ł "L"
   \đ "d" \Đ "D" \ð "d" \Ð "D" \þ "th" \Þ "TH" \ı "i"})

(defn plain
  "The text `s` with the accents taken off its letters, e.g. é as e and ø
  as oe."
  [s]
  (let [decomposed #?(:clj  (java.text.Normalizer/normalize
                             s java.text.Normalizer$Form/NFKD)
                      :cljs (.normalize s "NFKD"))
        letters    (str/escape decomposed unaccented)]
    #?(:clj  (str/replace letters #"\p{M}+" "")
       :cljs (.replace letters (js/RegExp. "\\p{M}+" "gu") ""))))
