(ns dk.simongray.podcast.comments.common
  "What the readers of the comments of ActivityPub and of the AT Protocol
  share:

  - the values in JSON answers, and the problem with an answer
  - the HTML of a post, made safe to show
  - the posts that a thread withholds
  - the order of the requests by the depth of their posts"
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(defn ^:no-doc present
  "The map `m` without the entries that say nothing: nil, false, a blank
  string or an empty collection."
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
  "The string `s` when it's an http or https URL with a host."
  [s]
  (when (url/web-url? s)
    s))

(defn ^:no-doc dimension
  "The number `x` when it's a whole number above zero, as a size is."
  [x]
  (when-let [n (json/whole x)]
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
  [{:keys [body] :as response}]
  (json/object body))

(defn ^:no-doc document-of
  "The JSON object that the `exchange` of a :request and a :response got
  in answer. It's the one read when the answer came, or else the one in
  the body of its response."
  [{:keys [response] :as exchange}]
  (if (contains? exchange :document)
    (:document exchange)
    (document response)))

(def ^:no-doc limits
  "The limits of size of a thread, which no option sets:

  - :page, the most items that a page of a collection gives
  - :id, the most characters of an id
  - :thread, the most comments that a thread holds
  - :depth, how many levels down the replies of an AT Protocol thread go

  Servers list some dozens of items to a page, so more is a flood."
  {:page 1000 :id 2048 :thread 10000 :depth 64})

(defn ^:no-doc failure
  "What went wrong with the `response` to the request for `url`, as a
  diagnostic without its level, or nil when nothing did.

  A server that refuses a request that isn't signed answers 401 or 403. A
  request that failed has the :error of it in place of the response, and
  one that took too long is :timeout? as well.

  A failure is :retry? when it can pass with time, so that a later
  reading asks again. The request failed and the response says :retry?,
  or the server answered 429 or a status from 500."
  [url {:keys [status error timeout? retry?] :as response}]
  (let [success?   (and (integer? status) (<= 200 status 299))
        transient? (and (integer? status)
                        (or (= 429 status) (<= 500 status 599)))]
    (cond
      error
      (cond-> {:id :comments/unreachable :url url :reason error}
        timeout? (assoc :timeout? true)
        retry?   (assoc :retry? true))

      (#{401 403} status)
      {:id :comments/refused :url url :status status}

      (not success?)
      (cond-> {:id :comments/unreachable :url url :status status}
        transient? (assoc :retry? true)))))

(defn ^:no-doc resolver
  "A function that makes a URL in a post absolute against `base`, the URL
  of the post, as html/hiccup takes it for :url-fn. Without a base, a URL
  stays as it is."
  [base]
  (if base
    #(url/absolute-url base %)
    identity))

(defn- fit-link
  "The Hiccup of the link with the attributes `attrs` and the fitted
  children `kids`, in a post whose URL is `base`."
  [attrs kids base]
  (let [target (:href attrs)
        href   (if (and base target) (url/absolute-url base target) target)]
    (into [:a (present {:href  (web-url href)
                        :title (:title attrs)
                        :rel   "nofollow ugc"})]
          kids)))

(defn ^:no-doc fit-links
  "The Hiccup `nodes` of a post whose URL is `base`, fit by `opts` to show
  on the page of an app.

  Each link is made absolute against `base`, and marked as a link that a
  user wrote, with rel nofollow ugc. A link that isn't an http or https
  URL has no target. Each image is its alt text, since an image loads when
  the page does. The `opts` can give two functions:

  - :a-fn takes the attributes of a link and its fitted children, and gives
    the node in place of the link, or nil for the link as it is
  - :text-fn takes a text, and gives the nodes in its place"
  ([nodes base]
   (fit-links nodes base {}))
  ([nodes base {:keys [a-fn text-fn]
                :or   {a-fn (constantly nil) text-fn vector}
                :as   opts}]
   (letfn [(nodes-of [x]
             (if-not (vector? x)
               (text-fn x)
               (let [[tag attrs & children] x
                     kids                   (mapcat nodes-of children)]
                 (case tag
                   :img (text-fn (str (:alt attrs)))
                   :a   [(or (a-fn attrs kids) (fit-link attrs kids base))]
                   [(into [tag attrs] kids)]))))]
     (mapcat nodes-of nodes))))

(defn ^:no-doc safe-html
  "The HTML `s` of a post whose URL is `base`, safe for the page of an app
  to show, or nil when it's blank. It's sanitized, with its URLs made
  absolute against `base` and its links fit to show."
  [s base]
  (when (and (string? s) (not (str/blank? s)))
    (html/sanitize (fit-links (html/hiccup s {:url-fn (resolver base)}) base))))

(defn ^:no-doc joined
  "The parts `ps` of a content as a vector. The texts that follow each
  other are one text, and the texts that are empty are left out."
  [ps]
  (let [text? #(= :text (:type %))]
    (into []
          (comp (remove #(and (text? %) (= "" (:text %))))
                (partition-by text?)
                (mapcat #(if (text? (first %))
                           [(assoc (first %) :text (str/join (map :text %)))]
                           %)))
          ps)))

(defn ^:no-doc user-link
  "The Hiccup of a link to `url` with the `label`, for a link that a user
  wrote in a content, with rel nofollow ugc. It's the label alone when the
  URL isn't an http or https URL with a host."
  [url label]
  (if-let [href (web-url url)]
    [:a {:href href :rel "nofollow ugc"} label]
    label))

(defn ^:no-doc linked
  "The Hiccup of the part `part` of a content, with the `label`, linked by
  the :link-fn of `opts`, or else the label.

  The :link-fn is a function of a part, which gives where a mention, a
  hashtag or a reference leads."
  [part label {:keys [link-fn] :as opts}]
  (if-let [href (when link-fn (link-fn part))]
    [:a {:href href} label]
    label))

(defn ^:no-doc guarded
  "The comment that `f` reads of the post `id` at `at`. When the reading
  fails, the comment holds its :error, so that the reading goes on with
  the posts around it."
  [id at f]
  (try
    (f)
    (catch #?(:clj Throwable :cljs :default) e
      (let [error {:id     :comments/malformed
                   :url    id
                   :reason (async/explain e)}]
        {:id id :error error :read-at at}))))

(defn ^:no-doc withhold
  "The `thread` with the post `id` among the posts that it withholds, which
  its report counts."
  [thread id]
  (update thread ::comments/withheld (fnil conj #{}) id))

(defn ^:no-doc truncate
  "The `thread` with `n` more replies among those that the limit `k` of
  limits cut, which its report counts."
  [thread k n]
  (cond-> thread
    (pos? n) (update-in [::comments/truncated k] (fnil + 0) n)))

(defn ^:no-doc retried
  "The `thread` with its root, its posts and its authors unread again,
  where a failure that can pass with time kept them from the reading.

  The failure of a root that the Mastodon API gave stays, since it tells
  why the thread came through the API."
  [{:keys [error via comments authors] :as thread}]
  (let [unread #(cond-> % (:retry? (:error %)) (dissoc :error :read-at))
        kept   #(into {} (remove (comp :retry? :error val)) %)]
    (cond-> thread
      (and (:retry? error) (not via)) (dissoc :error :read-at)
      comments                        (update :comments update-vals unread)
      authors                         (update :authors kept))))

(defn ^:no-doc depths
  "How many replies down from the root of `thread` each of its comments is,
  by the post that each replies to, as a map from ids. A comment that
  doesn't lead to the root has none."
  [{:keys [root comments] :as thread}]
  (let [children (group-by :in-reply-to (vals comments))]
    (loop [depth {root 0} level [root] n 1]
      (if (empty? level)
        depth
        (let [below (into []
                          (comp (mapcat #(get children %))
                                (map :id)
                                (remove depth)
                                (distinct))
                          level)]
          (recur (into depth (map #(vector % n)) below) below (inc n)))))))

(defn ^:no-doc nearest-first
  "The requests `rs`, those for the comments nearest the root first, by the
  `depth` of the comment that the ::comments/for of each names."
  [depth rs]
  (let [place (fn [{[_ id] ::comments/for :keys [url]}]
                [(get depth id ##Inf) url])]
    (sort-by place rs)))

(comment
  (present {:name "Ann" :handle "" :avatar nil :labels #{} :hidden? false})

  ;; a failure that can pass with time is :retry?
  (failure "https://example.social/1" {:status 429})
  (failure "https://example.social/1" {:status 404})
  (failure "https://example.social/1"
           {:error "Connection refused" :retry? true})

  ;; no handler, a link made absolute, and the alt text of an image
  (safe-html (str "<p onclick=\"x()\">Hi <a href=\"/tags/podcast\">#podcast</a>"
                  "<img src=\"x.png\" alt=\"a cat\"></p>")
             "https://example.social/users/ann/statuses/1")

  (depths {:root     "a"
           :comments {"a" {:id "a"}
                      "b" {:id "b" :in-reply-to "a"}
                      "c" {:id "c" :in-reply-to "b"}}})
  #_.)
