(ns ^:no-doc dk.simongray.podcast.feed.check.facts
  "The facts of the files of a check, gathered over the network: the
  answers to the requests for each file, and what the file says about
  itself when the check reads it.

  The facts are data that an app can keep, and the rules of
  dk.simongray.podcast.feed.check.rules judge them. In a browser, fetch
  hides most headers of an answer from another site, so the facts there
  say less."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.check.rules :as rules]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.hls :as hls]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.feed.integrity :as integrity]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.whatwg-mime :as mime]))

(defn ^:no-doc attempted
  "The value of the `attempt` of a reading, or a map with a diagnostic as
  the :error for an attempt that failed."
  [{:keys [value error] :as attempt}]
  (if error
    {:error (diagnostics/file-failure (http/failure error))}
    value))

(defn- diagnosed
  "The `facts` of an answer, with the failure under their :error, if any,
  as a diagnostic."
  [facts]
  (cond-> facts
    (:error facts) (update :error diagnostics/file-failure)))

(defn ^:no-doc read-facts
  "The facts of what a reader gave: the value of the `attempt` with the
  diagnostics that `report-fn` reads from it, as data, or its :error.

  The facts of a check are data that an app keeps, so they hold the
  report."
  [attempt report-fn]
  (let [{:keys [error] :as value} (attempted attempt)]
    (cond-> value
      (not error) (assoc :report (report-fn value)))))

(defn- sent-facts
  "The facts of the answer to an `attempt` to send, or a map with a
  diagnostic as the :error for an attempt that failed."
  [attempt]
  (let [{:keys [error] :as value} (attempted attempt)]
    (if error value (http/facts value))))

(defn ^:no-doc head!
  "Send a HEAD request for `url` with `headers` through `send`, and give
  the facts of the answer, in a future or a promise."
  [send url headers]
  (let [request {:method :head :url url :headers headers}]
    (async/then (http/try-send! send request) sent-facts)))

(defn ^:no-doc probe-url!
  "Probe `url` with `headers` through `send`, and give the facts, in a
  future or a promise. They tell all that a check needs of most files,
  from two bytes of each."
  [send url headers]
  (async/then (http/probe! url {:send send :headers headers})
              #(update-vals % diagnosed)))

(defn- time-left
  "The seconds that the check with `opts` has left, one at least, or nil
  when the check has no time limit."
  [{:keys [now-fn] ::keys [deadline] :as opts}]
  (when deadline
    (max 1 (quot (- deadline (inst-ms (now-fn))) 1000))))

(defn ^:no-doc reading-opts
  "The options of a reading by byte ranges in the check with `opts`: the
  limits of the check that apply to a reading, and `send` to send with."
  [send opts]
  (assoc (select-keys opts [:timeout-seconds :max-bytes :now-fn]) :send send))

(defn- whole-opts
  "The options of the fetch of a whole file for its integrity, in the
  check with `opts`: the :allow-pred, the time that the check has left,
  and the :send of the app, if any."
  [{::keys [own-send] :as opts}]
  ;; a whole file takes longer than a part of it
  (let [left (time-left opts)]
    (cond-> (select-keys opts [:allow-pred])
      left     (assoc :timeout-seconds left)
      own-send (assoc :send own-send))))

(defn- probe-media!
  "Probe the media `file` through `send` with `opts`, and give its facts,
  in a future or a promise.

  They're the facts of a probe, and as `opts` ask, what the file says
  about itself and whether it has its integrity."
  [send {:keys [url declared] :as file} {:keys [read? integrity?] :as opts}]
  (async/then
   (probe-url! send url {})
   (fn [{:keys [head get] :as facts}]
     (let [target     (or (:url get) (:url head) url)
           stated     (mime/essence (:type declared))
           came?      (contains? #{200 206} (:status get))
           readable?  (or (nil? stated) (contains? rules/formats stated))
           read-job   (when (and read? came? readable?)
                        (async/attempt
                         #(media/read! target (reading-opts send opts))))
           verify-job (when (and integrity? came? (:integrity declared))
                        (async/attempt
                         #(integrity/verify! target (:integrity declared)
                                             (whole-opts opts))))]
       (async/then
        (async/all [read-job verify-job])
        (fn [[read verified]]
          (cond-> facts
            read
            (assoc :media (-> (read-facts read media/report)
                              (select-keys [:format :duration :codecs :width
                                            :height :movie-first? :report
                                            :error])))

            ;; a reading was asked, but none knows the type
            (and read? came? (not readable?))
            (assoc :unread-type stated)

            verified
            (assoc :integrity (attempted verified)))))))))

(defn- probe-stream!
  "Probe the HLS stream `file` through `send` with `opts`, and give its
  facts, in a future or a promise.

  They're the facts of a probe, and when `opts` ask to :read? the stream,
  what its playlists say. The requests name the origin of the check, since
  web players read playlists."
  [send {:keys [url] :as file} {:keys [read?] :as opts}]
  (let [headers {"Origin" rules/origin}]
    (async/then
     (probe-url! send url headers)
     (fn [{:keys [get] :as facts}]
       (if (and read? (rules/ok? get))
         (async/then
          (async/attempt #(hls/read! (or (:url get) url)
                                     (assoc (reading-opts send opts)
                                            :headers headers)))
          (fn [attempt]
            (assoc facts :stream (-> (attempted attempt)
                                     (select-keys [:kind :duration :ended?
                                                   :variants :report
                                                   :error])))))
         facts)))))

(defn- probe-image!
  "Probe the image `file` through `send` with `opts`, and give its facts,
  in a future or a promise. They're the answer to a HEAD request, and the
  header of the image, read by range."
  [send {:keys [url] :as file} opts]
  (async/then (head! send url {})
              (fn [head]
                (let [target (if (rules/ok? head) (:url head url) url)
                      read!  #(image/read! target (reading-opts send opts))]
                  (async/then (async/attempt read!)
                              (fn [read]
                                {:head  head
                                 :image (read-facts read image/report)}))))))

(defn- reading
  "What the transcript or chapters file of `kind` holds, read from its
  `body` as its declared `type`.

  It's a map of the :count of segments or chapters and the :report of the
  reader, or of a diagnostic as the :error when the reader fails."
  [kind body type]
  (try
    (if (= :transcript kind)
      (let [t (transcript/parse body type)]
        {:count (count (:segments t)) :report (transcript/report t)})
      (let [doc (chapters/parse-json body)]
        {:count (count (:chapters doc)) :report (chapters/report doc)}))
    (catch #?(:clj Exception :cljs :default) e
      (diagnostics/failed {:id     :file/malformed
                           :reason (async/explain e)}))))

(defn- probe-text!
  "Probe the transcript or chapters `file` through `send` with `opts`, and
  give its facts, in a future or a promise.

  They're the facts of a probe. When `opts` ask to :read? the file,
  they're the answers to a HEAD request and to a GET of the whole file,
  one after the other as in a probe, and what the file holds. The
  requests name the origin of the check, since web players read these
  files."
  [send {:keys [kind url declared] :as file} {:keys [read?] :as opts}]
  (let [headers {"Origin" rules/origin}]
    (if-not read?
      (probe-url! send url headers)
      (async/then
       (head! send url headers)
       (fn [head]
         (async/then
          (http/try-send! send {:url url :headers headers})
          (fn [attempt]
            (let [got  (sent-facts attempt)
                  body (or (get-in attempt [:value :body]) "")]
              (cond-> {:head head :get got}
                (rules/ok? got)
                (assoc :read (reading kind body (:type declared))))))))))))

(defn- probe-feed!
  "Probe the feed at the URL of `file` through `send` with `opts`, and give
  its facts, in a future or a promise.

  The requests name the origin of the check, and have the ::authorization
  of `opts` for a private feed. The caller has the feed, so none of it is
  fetched again."
  [send {:keys [url] :as file} opts]
  (let [auth    (::authorization opts)
        headers (cond-> {"Origin" rules/origin}
                  auth (assoc "Authorization" auth))]
    (probe-url! send url headers)))

(def ^:no-doc probes
  "The function that gets the facts of a file, by the kind of the file."
  {:media      probe-media!
   :stream     probe-stream!
   :image      probe-image!
   :transcript probe-text!
   :chapters   probe-text!
   :feed       probe-feed!})

(defn- probe!
  "Probe `file` by its kind through `send` with `opts`, and give its facts,
  in a future or a promise. On the JVM, the probe runs on a thread of its
  own."
  [send file opts]
  (async/then (async/attempt #((probes (:kind file)) send file opts))
              attempted))

(defn- gathered
  "The facts of `files` in their order, from what the `lanes` gave, with
  the files still in the `queue` :unchecked."
  [files queue lanes]
  (let [by-index (into {} (mapcat :value) lanes)
        waiting  (into {}
                       (map (fn [[i _]] [i {:unchecked :max-seconds}]))
                       queue)
        missed   (ex-info "The file was not checked." {})]
    (mapv #(or (by-index %)
               (waiting %)
               (attempted {:error missed}))
          (range (count files)))))

(defn ^:no-doc gather!
  "Gather the facts of `files` in their order, sending with `send` and
  `opts` from `n` lanes, and give them in a future or a promise.

  Each lane probes the next file when its last one is done, so that at
  most `n` files are under way and no slow file holds up the others. A
  lane takes no file after the ::deadline of `opts`, and a file that no
  lane took is :unchecked."
  [send files {:keys [now-fn] ::keys [deadline] :as opts} n]
  (let [queue (atom (map-indexed vector files))
        next! (fn []
                (when (or (nil? deadline) (< (inst-ms (now-fn)) deadline))
                  (first (first (swap-vals! queue rest)))))
        ;; on the JVM, a lane is a loop on a thread of its own, since a
        ;; probe there gives its value at once, and in ClojureScript a chain
        ;; of promises
        lane  #?(:clj  (fn [_]
                         (loop [done []]
                           (if-let [[i file] (next!)]
                             (recur (conj done [i (probe! send file opts)]))
                             done)))
                 :cljs (fn lane [done]
                         (if-let [[i file] (next!)]
                           (async/then (probe! send file opts)
                                       #(lane (conj done [i %])))
                           done)))]
    (async/then (async/all (repeatedly n #(async/attempt (fn [] (lane [])))))
                #(gathered files @queue %))))
