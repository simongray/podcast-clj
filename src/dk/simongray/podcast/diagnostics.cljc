(ns dk.simongray.podcast.diagnostics
  "Every diagnostic that the library can put in a report, by id, with its
  level and what it means.

  The ids are API, since apps match on them, and doc/diagnostics.md lists
  them all. The namespace of an id names what raises it:

  - fetch, the fetch of a feed
  - discovery, finding the feeds of a show from an address
  - directory, a search or a chart of the directories
  - repair, the text repairs of a malformed document
  - xml, the resolution of XML namespaces
  - quirk, the normalizations of the tree
  - feed, the model
  - transcript and chapters, the companion formats
  - mp3, mp4 and file, media files and images
  - id3, the ID3v2 tags of media files, whose diagnostics are those of
    id3-clj
  - validate, the validator, whose diagnostics are about the feed rather
    than about reading it
  - check, the checks over the network of what the feed links to
  - sync, a sync step, whose diagnostics are those of like-minded
  - gpodder, a sync through the gpodder API
  - library, a library saved to a store and read back
  - podping, a poll of the Hive chain
  - comments, reading a thread of comments
  - value, the records of payments
  - analytics, the list of user agents and the lines of server logs

  The level says how much a diagnostic matters:

  - :error, a rule of a spec or of the chosen requirements is broken, so
    apps or a directory can misread the feed, or refuse it
  - :warning, the feed works, but apps are likely to show or treat
    something worse than the podcaster means
  - :info, something worth knowing that needs no change, e.g. a repair
    that the parse made

  The requirements of a directory can raise a level, e.g. a repeated
  enclosure URL is an error for Apple. An error under Apple's
  requirements breaks a rule of its guide, though Apple Podcasts Connect
  doesn't say which rules it refuses a feed for."
  (:require [dk.simongray.id3.diagnostics :as id3-diagnostics]
            [dk.simongray.like-minded.diagnostics :as sync-diagnostics]
            [dk.simongray.podcast.diagnostics.impl :as impl]))

(def catalogue
  "The diagnostics, in the order they tend to arise, and then those of the
  ID3v2 tags, from the catalogue of id3-clj, and those of a sync step, from
  the catalogue of like-minded."
  (-> impl/podcast-catalogue
      (into id3-diagnostics/catalogue)
      (into sync-diagnostics/catalogue)))

(def by-id
  "The catalogue by id."
  (into {} (map (juxt :id identity)) catalogue))

(def levels
  "The levels of a diagnostic, from the worst."
  [:error :warning :info])

(defn describe
  "The `diagnostic` with a :message and a :level: its own, or else those
  that the catalogue gives for its id. An id that the catalogue doesn't
  know is left as it is. The secrets of its :url are masked, e.g. the
  ?token= of a private feed."
  [diagnostic]
  (let [{:keys [doc level]} (by-id (:id diagnostic))]
    (cond-> (impl/masked diagnostic)
      (and doc (not (:message diagnostic)))  (assoc :message doc)
      (and level (not (:level diagnostic))) (assoc :level level))))

(defn failed
  "A map of the `diagnostic`, described, under :error, as a step gives it
  when it fails."
  [diagnostic]
  {:error (describe diagnostic)})

(defn fetch-failure
  "The diagnostic of the fetch `m` that gave no document, described.

  The `m` is a map of the :url, and the :status of an answer that isn't a
  success, or else the :reason that the request failed. It's :fetch/status
  with a status and :fetch/unreachable without, with the other keys of `m`,
  e.g. a :feed-guid."
  [m]
  (describe (assoc m :id (if (:status m) :fetch/status :fetch/unreachable))))

(defn file-failure
  "The diagnostic of a file that didn't come, described, by the `failure`
  of its request, as http/failure gives it, or by the :status of an answer
  that isn't a success. It's :file/unreachable with the :reason, :type,
  :url and :status of `failure`."
  [failure]
  (describe (into {:id :file/unreachable}
                  (select-keys failure [:reason :type :url :status]))))

(defn summarize
  "Group `diagnostics` by id and message, most frequent first: one row per
  kind, with its :count and its first occurrence as :example."
  [diagnostics]
  (->> (group-by (juxt :id :message) diagnostics)
       (map (fn [[_ [example :as group]]]
              (-> (select-keys example [:id :level :message])
                  (assoc :count (count group) :example example))))
       (sort-by (fn [{:keys [count id]}] [(- count) (str id)]))))

(defn verdict
  "The verdict of `diagnostics` from any reports, e.g. those of a fetch, a
  parse, a validation and a check, as a map:

  - :passed?, whether none is an error
  - :level, the worst level, or nil when there are none
  - :counts, the number at each level
  - :errors, what fails, as summarize gives it"
  [diagnostics]
  (let [counts (frequencies (map :level diagnostics))
        errors (filter (comp #{:error} :level) diagnostics)]
    {:passed? (empty? errors)
     :level   (some #(when (contains? counts %) %) levels)
     :counts  (into {} (map (juxt identity #(get counts % 0))) levels)
     :errors  (summarize errors)}))
