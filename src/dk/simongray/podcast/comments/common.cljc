(ns dk.simongray.podcast.comments.common
  "What `comments.activitypub` and `comments.atproto` share: the values in
  JSON answers, the problem with an answer, and the HTML of a post made
  safe to show. Also the posts that a thread withholds, and the order of
  the requests by the depth of their posts."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]))

(defn ^:no-doc present
  "`m` without the entries that say nothing: nil, false, a blank string or
  an empty collection."
  [m]
  (into {} (remove (fn [[_ v]]
                     (or (nil? v)
                         (false? v)
                         (and (string? v) (str/blank? v))
                         (and (coll? v) (empty? v)))))
        m))

(defn ^:no-doc first-text
  "The first of `xs` that is a string with more than white space in it."
  [& xs]
  (first (filter #(and (string? %) (not (str/blank? %))) xs)))

(defn ^:no-doc web-url
  "`s` when it is an http or https URL with a host."
  [s]
  (when (and (string? s) (re-find #"(?i)^https?://" s) (http/origin s))
    s))

(defn ^:no-doc whole
  "`x` when it is a whole number, as JSON writes one: 3, or 3.0."
  [x]
  (when (and (number? x) (== x (Math/floor x)) (< (Math/abs (double x)) 9e15))
    (long x)))

(defn ^:no-doc dimension
  "`x` when it is a whole number above zero, as a size is."
  [x]
  (when-let [n (whole x)]
    (when (pos? n) n)))

(defn ^:no-doc values
  "The values of the JSON property `k` of `o` as a sequence: none, one, or
  those of the list it holds."
  [o k]
  (let [x (when (map? o) (get o k))]
    (cond
      (sequential? x) x
      (nil? x)        []
      :else           [x])))

(defn ^:no-doc document
  "The JSON object in the body of the `response`, or nil."
  [{:keys [body]}]
  (let [x (json/parse body)]
    (when (map? x) x)))

(defn ^:no-doc document-of
  "The JSON object that the `exchange`, `{:request :response}`, got in
  answer: the one that `comments/fetch!` read when the answer came, or else
  the one in the body of its response."
  [{:keys [response] :as exchange}]
  (if (contains? exchange :document)
    (:document exchange)
    (document response)))

(defn ^:no-doc failure
  "What went wrong with the `response` to the request for `url`, as a
  diagnostic without its level; nil when nothing did.

  A server that refuses a request that is not signed answers 401 or 403. A
  request that failed has the `:error` of it in place of the response, and
  one that took too long is `:timeout?` as well."
  [url {:keys [status error timeout?]}]
  (cond
    error                                             (cond-> {:id :comments/unreachable :url url :reason error}
                                                        timeout? (assoc :timeout? true))
    (#{401 403} status)                               {:id :comments/refused :url url :status status}
    (not (and (integer? status) (<= 200 status 299))) {:id :comments/unreachable :url url :status status}))

(defn ^:no-doc fit-links
  "The Hiccup `nodes` of a post whose URL is `base`, fit to show on the page
  of an app.

  Each link is made absolute against `base`, marked as the link of a user
  that the app does not vouch for, and left without a target when it is no
  http or https URL. Each image is its alt text, since an image loads when
  the page does."
  [nodes base]
  (letfn [(node [x]
            (if-not (vector? x)
              x
              (let [[tag attrs children] (html/parts x)]
                (case tag
                  :img (str (:alt attrs))
                  :a   (let [target (:href attrs)]
                         (into [:a (present {:href  (web-url (if (and base target) (http/absolute-url base target) target))
                                             :title (:title attrs)
                                             :rel   "nofollow ugc"})]
                               (map node)
                               children))
                  (into [tag attrs] (map node) children)))))]
    (map node nodes)))

(defn ^:no-doc safe-html
  "The HTML `s` of a post whose URL is `base`, safe for the page of an app
  to show: sanitized by `html/hiccup`, which makes paragraphs of plain
  text, with its links fit as `fit-links` has it. Nil when it is blank."
  [s base]
  (when (and (string? s) (not (str/blank? s)))
    (html/emit (fit-links (html/hiccup s) base))))

(defn ^:no-doc guarded
  "The comment that `(f)` reads of the post `id` at `at`. When the reading
  fails, the comment holds the `:error` of it, so that the reading goes on
  with the posts around it."
  [id at f]
  (try
    (f)
    (catch #?(:clj Throwable :cljs :default) e
      {:id id :error {:id :comments/malformed :url id :reason (async/explain e)} :read-at at})))

(defn ^:no-doc withhold
  "`thread` with the post `id` among the posts that it withholds, which its
  report counts."
  [thread id]
  (update thread ::comments/withheld (fnil conj #{}) id))

(defn ^:no-doc depths
  "How many replies down from the root of `thread` each of its comments is,
  by the post that each replies to, as a map from ids. A comment that does
  not lead to the root has none."
  [{:keys [root comments]}]
  (let [children (group-by :in-reply-to (vals comments))]
    (loop [depth {root 0} level [root] n 1]
      (if (empty? level)
        depth
        (let [below (into [] (comp (mapcat #(get children %)) (map :id) (remove depth) (distinct)) level)]
          (recur (into depth (map #(vector % n)) below) below (inc n)))))))

(defn ^:no-doc nearest-first
  "The requests `rs`, those for the comments nearest the root first, by the
  `depth` of the comment that each is `::comments/for`."
  [depth rs]
  (sort-by (fn [{[_ id] ::comments/for :keys [url]}] [(get depth id ##Inf) url]) rs))
