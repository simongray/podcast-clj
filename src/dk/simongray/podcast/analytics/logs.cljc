(ns dk.simongray.podcast.analytics.logs
  "The requests of a server log as the request maps that
  dk.simongray.podcast.analytics counts downloads from.

  It reads two formats:

  - the combined format of Apache and nginx, and the common format, which
    lacks the referer and the user agent of the combined one
  - the extended format of the W3C, whose #Fields line names its fields,
    e.g. the logs of IIS and of some CDNs

  The requests come one line at a time, so a log of any size can be read
  from line-seq. A line that doesn't read gives a map with a diagnostic
  under :error, which analytics/downloads leaves out as malformed."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]))

;; Apache HTTP Server 2.4, Log Files, and nginx, ngx_http_log_module, as of
;; 2026-10-13: the host, identity, user, time, request line, status and
;; size, then the referer and the user agent, and any fields that a host
;; adds after them
(def ^:no-doc combined-pattern
  "A line of the common or the combined format, with the quoted fields
  after its size together in the last group."
  (let [quoted "\"((?:[^\"\\\\]|\\\\.)*)\""]
    (re-pattern (str "(\\S+) (\\S+) (\\S+) \\[([^\\]]+)\\] " quoted
                     " (\\d{3}|-) (\\d+|-)"
                     "((?: \"(?:[^\"\\\\]|\\\\.)*\")*)\\s*"))))

(def ^:no-doc quoted-field
  "A field in double quotes, with its escapes."
  #"\"((?:[^\"\\]|\\.)*)\"")

(def ^:no-doc time-pattern
  "The time of a line of the combined format, e.g. 26/Sep/2026:08:15:02
  +0200."
  #"(\d\d)/(\w{3})/(\d{4}):(\d\d):(\d\d):(\d\d) ([+-])(\d\d)(\d\d)")

(defn- unescaped
  "The text of the quoted field `s` of a line of the combined format, with
  its escapes undone: a backslash before a quote or a backslash, and \\x
  with two hexadecimal digits for a byte, which nginx writes for a quote
  too. A run of such bytes is UTF-8 text."
  [s]
  (if-not (str/includes? s "\\")
    s
    (apply str
           (for [[token run] (re-seq #"((?:\\x[0-9A-Fa-f]{2})+)|\\.|\\|[^\\]+"
                                     s)]
             (cond
               run
               (bytes/decode (bytes/from-hex (str/replace run "\\x" "")))

               (and (= 2 (count token)) (str/starts-with? token "\\"))
               (subs token 1)

               :else
               token)))))

(defn- log-time
  "The instant of the time `s` of a line of the combined format, or nil."
  [s]
  (when-let [[_ d mon y hh mm ss sign oh om] (re-matches time-pattern s)]
    (let [m                    (time/months (str/lower-case mon))
          [d y hh mm ss oh om] (map parse-long [d y hh mm ss oh om])
          offset               (cond-> (+ (* 60 oh) om) (= "-" sign) -)]
      (when (and m (time/civil? y m d hh mm ss))
        (time/->inst (time/civil->ms y m d hh mm ss 0 offset))))))

(defn- request-line
  "The method and the target of the request line `s`, e.g. GET /1.mp3
  HTTP/1.1, as a pair, or nil for another text, e.g. the bytes of a TLS
  handshake that reached a port of HTTP."
  [s]
  (when-let [[_ method target]
             (re-matches #"([A-Za-z]+) (\S+)(?: HTTP/[0-9.]+)?" s)]
    [(keyword (str/lower-case method)) target]))

(defn- present
  "The text `s` of a field, or nil when it's empty or a dash, which a log
  writes for a field that it has no value of."
  [s]
  (when-not (contains? #{nil "" "-"} s)
    s))

(defn- whole
  "The whole number in the text `s` of a field, or nil."
  [s]
  (some-> (present s) parse-long))

;; IAB Tech Lab, Podcast Measurement Technical Guidelines 2.3: the first
;; address of X-Forwarded-For is the listener's
(defn- first-address
  "The first address of the X-Forwarded-For header `s`, or nil."
  [s]
  (some-> (present s) (str/split #",") first str/trim present))

(defn- target-url
  "The URL of the request `target`, against the :base-url of `opts` when
  they give one, and else as the log has it."
  [target {:keys [base-url] :as opts}]
  (if base-url
    (url/absolute-url base-url target)
    target))

(defn- combined-request
  "The request map of the `line` of a log in the combined format, read by
  `opts`, or nil when the line doesn't read."
  [line {:keys [extra] :as opts}]
  (when-let [[_ host _ _ at request status size more]
             (re-matches combined-pattern line)]
    (let [fields          (map (comp unescaped second)
                               (re-seq quoted-field more))
          [referer agent] fields
          named           (zipmap extra (drop 2 fields))
          [method target] (request-line (unescaped request))
          ip              (or (first-address (:forwarded-for named)) host)]
      (when-let [t (log-time at)]
        (cond-> {:time t :ip ip :bytes (or (whole size) 0)}
          method                   (assoc :method method)
          target                   (assoc :url (target-url target opts))
          (whole status)           (assoc :status (whole status))
          (present (:range named)) (assoc :range (:range named))
          (present agent)          (assoc :user-agent agent)
          (present referer)        (assoc :referer referer))))))

;; W3C, Extended Log File Format, Working Draft of 23 March 1996: fields
;; apart by white space, a dash for a field without a value, and the date
;; and the time in UTC
(defn- w3c-values
  "The values of the `line` of a log in the extended format of the W3C.
  They're apart by tabs when the line has any, and else by spaces, and a
  value in double quotes can hold spaces, with two quotes for one."
  [line]
  (if (str/includes? line "\t")
    (str/split line #"\t" -1)
    (for [[_ quoted bare] (re-seq #"\"((?:[^\"]|\"\")*)\"|(\S+)" line)]
      (if quoted (str/replace quoted "\"\"" "\"") bare))))

(defn- w3c-fields
  "The names in lower case of the fields that the directive `line` of a
  log in the extended format of the W3C names, or nil for another
  directive."
  [line]
  (when-let [[_ names] (re-matches #"(?i)#Fields:\s*(.*)" line)]
    (str/split (str/lower-case (str/trim names)) #"\s+")))

(defn- w3c-request
  "The request map of the `line` of a log in the extended format of the
  W3C, whose fields are `fields`, read by `opts`, or nil when the line
  doesn't read. A text in a header field, such as the user agent, is
  percent-decoded, as some CDNs write it."
  [line fields opts]
  (let [values (w3c-values line)]
    (when (= (count fields) (count values))
      (let [v         (into {}
                            (keep (fn [[k x]] (some->> (present x) (vector k))))
                            (map vector fields values))
            header    #(some-> (v (str "cs(" % ")")) url/percent-decode)
            at        (when (and (v "date") (v "time"))
                        (time/parse-iso8601 (str (v "date") "T" (v "time")
                                                 "Z")))
            query     (some->> (v "cs-uri-query") (str "?"))
            target    (or (v "cs-uri") (some-> (v "cs-uri-stem") (str query)))
            forwarded (or (header "x-forwarded-for")
                          (some-> (v "x-forwarded-for") url/percent-decode))
            ip        (or (first-address forwarded) (v "c-ip"))
            method    (some-> (v "cs-method") str/lower-case keyword)
            status    (whole (v "sc-status"))
            sent      (whole (v "sc-bytes"))]
        (when at
          (cond-> {:time at}
            ip                    (assoc :ip ip)
            method                (assoc :method method)
            target                (assoc :url (target-url target opts))
            status                (assoc :status status)
            sent                  (assoc :bytes sent)
            (header "range")      (assoc :range (header "range"))
            (header "user-agent") (assoc :user-agent (header "user-agent"))
            (header "referer")    (assoc :referer (header "referer"))))))))

(defn- unreadable
  "The map of the line `i` of a log, which doesn't read for the `reason`."
  [i reason]
  (diagnostics/failed {:id     :analytics/unreadable-line
                       :line   i
                       :reason reason}))

(defn- read-one
  "The request map of the `line` at `i` of a log in the format `fmt`, read
  by `opts`, or the map of a line that doesn't read. A line of the W3C
  format has the `fields` of the #Fields line before it."
  [line i fmt fields opts]
  (cond
    (not= :w3c fmt)
    (or (combined-request line opts)
        (unreadable i "It doesn't have the fields of the combined format"))

    (nil? fields)
    (unreadable i "No #Fields line names the fields before it")

    :else
    (or (w3c-request line fields opts)
        (unreadable i "It doesn't have the fields of the #Fields line"))))

(defn requests
  "The request maps of the `lines` of a server log, read by the `opts`
  below, as a lazy sequence in the order of the lines.

  A line that doesn't read gives a map with an :analytics/unreadable-line
  diagnostic under :error, which names the :line, counted from 1. Blank
  lines and the directives of the W3C format give nothing. A request
  takes the first address of X-Forwarded-For as its :ip when the log has
  one, as the IAB asks. The `opts` are these:

  - :format, :combined or :w3c, by default the W3C format when the first
    line that isn't blank is a directive, e.g. #Version: 1.0
  - :base-url, the URL that a path of a request is relative to, e.g.
    https://cdn.example.com/. Without it, the :url is the target of the
    request as the log has it, most often a path.
  - :extra, the names of the quoted fields that the combined format of
    the host adds after the user agent, in their order. Those that count
    are :forwarded-for and :range, e.g. [:forwarded-for] for nginx with
    \"$http_x_forwarded_for\" at the end of the format."
  ([lines]
   (requests lines {}))
  ([lines {fmt :format :as opts}]
   (letfn [(step [lines i fmt fields]
             (lazy-seq
              (when-let [[line & more] (seq lines)]
                (let [line   (str/trim-newline (str line))
                      blank? (str/blank? line)
                      fmt    (or fmt
                                 (when-not blank?
                                   (if (str/starts-with? line "#")
                                     :w3c
                                     :combined)))]
                  (cond
                    blank?
                    (step more (inc i) fmt fields)

                    (and (= :w3c fmt) (str/starts-with? line "#"))
                    (step more (inc i) fmt (or (w3c-fields line) fields))

                    :else
                    (cons (read-one line i fmt fields opts)
                          (step more (inc i) fmt fields)))))))]
     (step lines 1 fmt nil))))

(comment
  (requests
   [(str "203.0.113.5 - - [26/Sep/2026:08:15:02 +0000]"
         " \"GET /episode-54.mp3 HTTP/1.1\" 206 1048576 \"-\""
         " \"Overcast/3.0 (+http://overcast.fm/; iOS podcast app)\"")]
   {:base-url "https://cdn.example.com/"})
  ;; => ({:time #inst "2026-09-26T08:15:02.000-00:00" :ip "203.0.113.5"
  ;;      :bytes 1048576 :method :get :status 206
  ;;      :url "https://cdn.example.com/episode-54.mp3"
  ;;      :user-agent "Overcast/3.0 (+http://overcast.fm/; iOS podcast app)"})
  #_.)
