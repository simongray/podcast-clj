(ns dk.simongray.podcast.analytics
  "The downloads of episodes, counted by the rules of the IAB Tech Lab from
  the requests that a podcast host logs.

  The rules are those of the Podcast Measurement Technical Guidelines,
  version 2.3 of September 2026. They count the episodes of video too,
  when a feed delivers them as files.

  A request is a map of one line of the log of a server:

      {:time       #inst \"2026-09-26T08:15:02Z\"
       :ip         \"203.0.113.5\"                 ; or a hash of it
       :user-agent \"Overcast/3.0 (+http://overcast.fm/; iOS podcast app)\"
       :method     :get
       :url        \"https://cdn.example.com/episode-54.mp3\"
       :status     206
       :range      \"bytes=0-\"                     ; the Range header
       :bytes      1048576                         ; of the body that was sent
       :referer    nil}

  The downloads function leaves out the requests that can't be part of a
  download, such as HEAD requests and the requests of bots. It puts
  together the requests of each listener for a file in 24 hours. They
  count as one download when they hold enough of the file to play for one
  minute. A listener is an IP address and a user agent. The listener-ip
  function gives the part of the address that counts, and threshold gives
  the bytes that a download needs.

  The rules need data from outside podcast-clj. The open list of user
  agents of the OPAWG names the apps and the bots, and user-agents reads
  it. Lists of the addresses of data centers, VPNs and known bots come from
  other sources. The host gives them to downloads as a predicate.

  A download of OP3, which dk.simongray.podcast.analytics.op3 reads, has
  the keys of these downloads where OP3 tells the same fact."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

(defn threshold
  "The bytes of `file` that a download needs by the rules of the IAB: the
  header, and one minute of the content. It's nil when the size isn't
  known.

  The `file` is a map of its :size and the size of its :header in bytes,
  and its :duration in seconds. The header of an MP3 file is its ID3 tag.
  An episode of one minute or less needs the whole file, and so does a
  file with no duration."
  [{:keys [size header duration] :as file}]
  (when (and (number? size) (pos? size))
    (let [header (min size (max 0 (or header 0)))]
      (if (and (number? duration) (> duration 60))
        (let [minute (long (Math/ceil (/ (* (- size header) 60.0) duration)))]
          (min size (+ header minute)))
        size))))

(defn- ipv6-text
  "The text of the IPv6 address of the sixteen bytes `b`, as RFC 5952 writes
  it. The first of the longest runs of two or more zero groups becomes ::."
  [b]
  (let [words  (mapv (fn [[hi lo]] (+ (* 256 hi) lo)) (partition 2 b))
        [at n] (reduce (fn [[_ n :as best] i]
                         (let [run (count (take-while zero? (subvec words i)))]
                           (if (and (>= run 2) (> run n)) [i run] best)))
                       [nil 0]
                       (range 8))
        group  #?(:clj  #(Integer/toHexString %)
                  :cljs #(.toString % 16))
        hex    (fn [ws] (str/join ":" (map group ws)))]
    (if at
      (str (hex (subvec words 0 at)) "::" (hex (subvec words (+ at n))))
      (hex words))))

(defn listener-ip
  "The part of the IP address `ip` that tells listeners apart, as the IAB
  counts them.

  That's an IPv4 address whole, and the first 64 bits of an IPv6 address,
  as a prefix such as 2001:db8:1:2::/64. An IPv4 address inside IPv6, as a
  server writes a client of IPv4, is that IPv4 address.

  Other text, such as a hash of an address, stays as it is. So a host that
  keeps a hash in place of the address hashes this part."
  [ip]
  (let [b (http/ip-address ip)]
    (case (count b)
      4  (str/join "." b)
      16 (if (= [0 0 0 0 0 0 0 0 0 0 255 255] (subvec b 0 12))
           (str/join "." (subvec b 12))
           (str (ipv6-text (into (subvec b 0 8) (repeat 8 0))) "/64"))
      ip)))

(def ^:no-doc agent-files
  "The files of the list of user agents that name an agent, with the name
  of their type, in the order of the lookup."
  [[:bots "bot"] [:apps "app"] [:libraries "library"] [:browsers "browser"]])

(defn- entry
  "The entry `e` of the `file` of the list of user agents, with its pattern
  compiled, or a diagnostic when the pattern doesn't compile."
  [file {:strs [name pattern category] :as e}]
  (when (and (string? name) (string? pattern))
    (if-let [re (try
                  (re-pattern pattern)
                  (catch #?(:clj Exception :cljs :default) _
                    nil))]
      (cond-> {:name name :pattern re}
        (string? category) (assoc :category category))
      {:id :analytics/unreadable-pattern :file file :name name})))

(defn user-agents
  "The open list of user agents of the OPAWG from `files`, ready for
  identify.

  The `files` map :bots, :apps, :libraries, :browsers, :devices and
  :referrers to the JSON of the file of that name, as text or read. The
  files are in src/ or build/ of https://github.com/opawg/user-agents-v2.

  Each entry of a file has a name, a pattern and at times a category. The
  pattern is a regular expression of JavaScript. A pattern that doesn't
  compile on the platform is left out, and the report of the list says
  so. The list changes often, so a host keeps a copy of one commit."
  [files]
  (let [parsed  (fn [x] (if (string? x) (json/read-str x) x))
        known   #{:bots :apps :libraries :browsers :devices :referrers}
        entries (for [[file x] files
                      :when (contains? known file)
                      e     (get (parsed x) "entries")
                      :let  [found (entry file e)]
                      :when found]
                  [file found])
        add     (fn [m [file e]]
                  (cond-> m
                    (:pattern e) (update file (fnil conj []) e)))
        report  (into []
                      (comp (map second)
                            (remove :pattern)
                            (map diagnostics/describe))
                      entries)]
    (with-meta (reduce add {} entries)
      {:podcast/report report})))

(defn- matching
  "The first entry of the file `file` of `user-agents` whose pattern `s`
  matches."
  [user-agents file s]
  (some #(when (re-find (:pattern %) s) %) (get user-agents file)))

(defn identify
  "The agent that the header `user-agent` names by the list `user-agents`,
  with the keys that OP3 gives it, and the referrer of the header
  `referer`.

  The result is a map of :agent-type, :agent-name, :device-type and
  :device-name. With the referer of a request from a browser, it also has
  :referrer-type and :referrer-name.

  The agent type is the type of the file of the first entry that matches:
  bot, app, library or browser. A library that bots use is bot-library. A
  user agent that matches no entry is unknown, and its name is the header.
  The device comes from the devices of the list, except for a bot. The
  referrer comes from the referrers of the list, or else it's the domain
  of the referer."
  ([user-agents user-agent]
   (identify user-agents user-agent nil))
  ([user-agents user-agent referer]
   (let [ua               (str/replace (str user-agent) #"[\r\n]" "")
         match-in         (fn [[file :as f]]
                            (some->> (matching user-agents file ua) (vector f)))
         [[_ kind] found] (some match-in agent-files)
         bot-library?     (and (= "library" kind) (= "bot" (:category found)))
         agent-type       (cond
                            bot-library? "bot-library"
                            kind         kind
                            :else        "unknown")
         device           (when (not= "bot" agent-type)
                            (matching user-agents :devices ua))
         referrer         (when (and (= "browser" agent-type)
                                     (not (str/blank? referer)))
                            (or (matching user-agents :referrers referer)
                                (some->> (http/host referer)
                                         (hash-map :category "domain" :name))))]
     (into {} (remove (comp nil? val))
           {:agent-type    agent-type
            :agent-name    (if found (:name found) (not-empty ua))
            :device-type   (:category device)
            :device-name   (:name device)
            :referrer-type (:category referrer)
            :referrer-name (:name referrer)}))))

(defn- watch?
  "Whether `user-agent` is that of Apple Podcasts on a watch, whose
  downloads repeat those of the phone, as the IAB guidelines tell it."
  [user-agent]
  (or (and (str/starts-with? user-agent "atc/")
           (str/includes? user-agent "watchOS"))
      (str/includes? user-agent "(null)/(null) watchOS")))

(defn- bot?
  "Whether `request` comes from a bot. The list names its agent a bot, or
  the user agent says so when the list doesn't know it."
  [{:keys [agent-type user-agent] :as request}]
  (if (contains? #{"bot" "bot-library"} agent-type)
    true
    (and (contains? #{nil "unknown"} agent-type)
         (boolean (re-find #"(?i)bot|crawler|spider" user-agent)))))

(defn ^:no-doc asked-size
  "The bytes that the byte ranges `ranges` ask for together, or ##Inf for
  a range with no end."
  [ranges]
  (let [size (fn [[a b]]
               (cond
                 (nil? a) b
                 (nil? b) ##Inf
                 :else    (inc (- b a))))]
    (reduce + 0 (map size ranges))))

(defn ^:no-doc malformed?
  "Whether the rules can't read `request`.

  It has no instant as :time, or no text as :url or :ip. Or a field that
  it has is of another type than its own: :method isn't a keyword or
  text, or :range, :user-agent or :referer isn't text."
  [{:keys [time url ip method range user-agent referer] :as request}]
  (let [text? #(or (nil? %) (string? %))]
    (not (and (inst? time)
              (string? url)
              (string? ip)
              (seq ip)
              (or (nil? method) (keyword? method) (string? method))
              (text? range)
              (text? user-agent)
              (text? referer)))))

(defn drop-reason
  "Why the rules of the IAB leave out `request` by `opts`, as a keyword, or
  nil when it can be part of a download. These are the filters of the
  first step of the guidelines, in the order that downloads applies them:

  - :malformed, a request with no instant as :time, or no :url or :ip, or
    with a field of another type than the type of that field
  - :method, a request that isn't GET, since HEAD moves no content
  - :status, an answer that isn't 200 or 206, or 000, which Akamai logs
    for a 206 that ended early. A request without a status passes, since
    a prefix service sees no answers.
  - :two-bytes, ranges of two bytes or fewer, which players ask for to
    learn whether a server sends ranges
  - :no-user-agent, a request with no user agent
  - :watch, a request of Apple Podcasts on a watch, which repeats the
    download of the phone
  - :bot, a request whose :agent-type is bot or bot-library, as identify
    names it. When the list doesn't know the agent, it's a user agent
    that says bot, crawler or spider.
  - :excluded, a request that the predicate :exclude? of `opts` takes.
    It's for the addresses of servers, data centers, VPNs and known bots,
    from the lists of the host."
  ([request]
   (drop-reason request {}))
  ([{:keys [method status range user-agent] :as request}
    {:keys [exclude?] :as opts}]
   (cond
     (malformed? request)                                :malformed
     (not= "get" (some-> method name str/lower-case))    :method
     (not (contains? #{nil 200 206 0} status))           :status
     (some-> (http/byte-ranges range) asked-size (<= 2)) :two-bytes
     (str/blank? user-agent)                             :no-user-agent
     (watch? user-agent)                                 :watch
     (bot? request)                                      :bot
     (and exclude? (exclude? request))                   :excluded)))

(defn- held
  "The byte ranges of its file that `request` holds. These are the ranges
  of its Range header, or the whole file, cut to the :bytes that the
  server sent. The bytes arrive in the order of the ranges."
  [{:keys [range bytes] :as request}]
  (let [asked (or (http/byte-ranges range) [[0 nil]])]
    (if (and (number? bytes) (<= 0 bytes))
      (first (reduce (fn [[acc left] [a b]]
                       (let [n    (min left (asked-size [[a b]]))
                             part (if a [a (+ a n -1)] [nil n])]
                         (if (pos? n)
                           [(conj acc part) (- left n)]
                           (reduced [acc 0]))))
                     [[] bytes]
                     asked))
      asked)))

(defn- held-size
  "The bytes that the byte ranges `ranges` hold together, each byte once,
  or ##Inf when a range has no end.

  The ranges from the end of the file count apart, since their start
  isn't known. They all end where the file ends, so together they hold
  the bytes of the longest of them."
  [ranges]
  (let [{from-end true from-start false} (group-by (comp nil? first) ranges)]
    (+ (reduce max 0 (map second from-end))
       (first (reduce (fn [[total reach] [a b]]
                        (cond
                          (nil? b)    (reduced [##Inf reach])
                          (> a reach) [(+ total (inc (- b a))) b]
                          (> b reach) [(+ total (- b reach)) b]
                          :else       [total reach]))
                      [0 -1]
                      (sort-by first from-start))))))

(defn- file-url
  "The URL `url` without its query and fragment: the file that it names."
  [url]
  (first (str/split url #"[?#]" 2)))

;; IAB guidelines 2.3, section 5.5.1: the calendar day, the "first touch"
;; and the "last touch" rolling windows
(defn- same-window?
  "Whether the request at `ms`, in milliseconds since the epoch, falls in
  the window `w` of the kind `window`:

  - :day, the day of the :start of `w`
  - :rolling, the 24 hours from its :start
  - :extending, the 24 hours from its :last request"
  [window {:keys [start last] :as w} ms]
  (case window
    :day       (= (first (time/split-ms start)) (first (time/split-ms ms)))
    :rolling   (< ms (+ start 86400000))
    :extending (< ms (+ last 86400000))))

(defn- judged
  "The `requests` that can be part of a download, and the count of the
  others by the reason that drop-reason gives, as a pair. Each request has
  the agent that the :user-agents of `opts` names."
  [requests {:keys [user-agents] :as opts}]
  (let [known (memoize (fn [ua referer] (identify user-agents ua referer)))
        named (fn [{:keys [user-agent referer] :as request}]
                (if (and user-agents (not (malformed? request)))
                  (merge request (known user-agent referer))
                  request))]
    (reduce (fn [[kept dropped] request]
              (let [request (named request)]
                (if-let [reason (drop-reason request opts)]
                  [kept (update dropped reason (fnil inc 0))]
                  [(conj kept request) dropped])))
            [[] {}]
            requests)))

(defn- with-request
  "The `windows`, a pair of the open windows by listener and file and the
  closed ones, with `request` in the open window of its listener and file,
  or else in a new one. The `file` and `window` are those of windows."
  [file window [open closed :as windows] {:keys [ip user-agent] :as request}]
  (let [listener [(listener-ip ip) user-agent]
        k        (conj listener (file request))
        ms       (inst-ms (:time request))
        w        (get open k)]
    (if (and w (same-window? window w ms))
      (let [grown (-> w
                      (update :ranges into (held request))
                      (update :n inc)
                      (assoc :last ms))]
        [(assoc open k grown) closed])
      (let [opened {:first-request request
                    :start         ms
                    :last          ms
                    :ranges        (held request)
                    :n             1
                    :listener      listener
                    :file          (peek k)}]
        [(assoc open k opened) (cond-> closed w (conj w))]))))

(defn- windows
  "The windows of `requests` for each listener and file, where `file`
  gives the file of a request and `window` is :day, :rolling or
  :extending.

  A window has its first request, the byte ranges that its requests hold,
  and their number."
  [requests file window]
  (let [by-time       (sort-by (comp inst-ms :time) requests)
        step          (partial with-request file window)
        [open closed] (reduce step [{} []] by-time)]
    (into closed (vals open))))

(defn downloads
  "The downloads that `requests` make by the rules of the IAB with `opts`,
  and why the other requests don't count, as a map of :downloads and
  :dropped.

  The requests of one listener for one file in one window of 24 hours make
  one download. They must hold at least the bytes of the threshold of the
  file. A download is the first of its requests, with the :listener that
  it counts for and the agent that identify names. The listener is a pair
  of the listener-ip of the address and the user agent. The downloads
  come in the order of their first requests.

  The :dropped map counts the requests that drop-reason leaves out, by
  reason. Under :short, it counts the requests of listeners who fetched
  less than the threshold. The `opts` are these:

  - :user-agents, as user-agents gives them, to name the apps, the devices
    and the bots
  - :exclude?, a predicate of a request, for the addresses to leave out
  - :threshold, a function of a file that gives the bytes that a download
    needs, or a map of files to bytes
  - :file, a function of a request that gives the file that it asks for,
    by default its URL without the query
  - :window, which is :day by default, :rolling or :extending

  A window is a day in UTC. With :rolling, a window is the 24 hours from
  the first request of a listener for a file. With :extending, it's the
  24 hours from the last request, so each request extends it. These are
  the three windows of the guidelines. For a file with no threshold, any
  request that passes the filters counts.

  The requests of a listener hold the bytes that their ranges ask for, cut
  to the :bytes that the server sent. A byte counts once, whatever the
  number of requests that ask for it."
  ([requests]
   (downloads requests {}))
  ([requests {:keys [threshold file window]
              :or   {file (comp file-url :url) window :day}
              :as   opts}]
   (when-not (contains? #{:day :rolling :extending} window)
     (throw (ex-info (str "A window that downloads does not know: " window)
                     {:window window})))
   (let [[kept dropped] (judged requests opts)
         enough?        (fn [{:keys [ranges] :as w}]
                          (let [needed (when threshold (threshold (:file w)))]
                            (or (nil? needed)
                                (>= (held-size ranges) needed))))
         windowed       (windows kept file window)
         {counted true short false} (group-by enough? windowed)]
     {:downloads (mapv (fn [w]
                         (assoc (:first-request w) :listener (:listener w)))
                       (sort-by :start counted))
      :dropped   (cond-> dropped
                   (seq short) (assoc :short (reduce + (map :n short))))})))

(comment
  ;; an episode of 30 minutes: the header, and one minute of the content
  (threshold {:size 30000000 :header 50000 :duration 1800})
  (listener-ip "2001:db8:1:2:3:4:5:6")

  (def agents
    (user-agents
     {:apps {"entries" [{"name" "Overcast" "pattern" "^Overcast/"}]}
      :bots {"entries" [{"name" "Googlebot" "pattern" "Googlebot"}]}}))

  (identify agents "Overcast/3.0 (+http://overcast.fm/; iOS podcast app)")

  ;; two requests of one listener for one file, and the request of a bot
  (def requests
    [{:time       #inst "2026-09-26T08:15:02Z"
      :ip         "203.0.113.5"
      :user-agent "Overcast/3.0"
      :method     :get
      :url        "https://cdn.example.com/54.mp3"
      :status     206
      :range      "bytes=0-"
      :bytes      1048576}
     {:time       #inst "2026-09-26T08:15:09Z"
      :ip         "203.0.113.5"
      :user-agent "Overcast/3.0"
      :method     :get
      :url        "https://cdn.example.com/54.mp3"
      :status     206
      :range      "bytes=1048576-"
      :bytes      2000000}
     {:time       #inst "2026-09-26T09:00:00Z"
      :ip         "198.51.100.7"
      :user-agent "Googlebot/2.1"
      :method     :get
      :url        "https://cdn.example.com/54.mp3"
      :status     200
      :bytes      30000000}])

  (drop-reason (assoc (peek requests) :agent-type "bot"))
  (downloads requests {:user-agents agents :threshold (constantly 1000000)})
  #_.)
