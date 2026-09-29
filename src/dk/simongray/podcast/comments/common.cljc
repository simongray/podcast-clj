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
  (when (http/web-url? s)
    s))

(defn ^:no-doc dimension
  "`x` when it is a whole number above zero, as a size is."
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
  [{:keys [body]}]
  (json/object body))

(defn ^:no-doc document-of
  "The JSON object that the `exchange`, `{:request :response}`, got in
  answer: the one that `comments/fetch!` read when the answer came, or else
  the one in the body of its response."
  [{:keys [response] :as exchange}]
  (if (contains? exchange :document)
    (:document exchange)
    (document response)))

(def ^:no-doc limits
  "The limits of size of a thread, which no option sets. A page of a
  collection gives `:page` items at most, and an id has `:id` characters
  at most. A thread holds `:thread` comments at most. The replies of an AT
  Protocol thread go `:depth` levels down at most. Servers list some dozens
  of items to a page, so more is a flood."
  {:page 1000 :id 2048 :thread 10000 :depth 64})

(defn ^:no-doc failure
  "What went wrong with the `response` to the request for `url`, as a
  diagnostic without its level; nil when nothing did.

  A server that refuses a request that is not signed answers 401 or 403. A
  request that failed has the `:error` of it in place of the response, and
  one that took too long is `:timeout?` as well.

  A failure is `:retry?` when it can pass with time, so that a later
  reading asks again. The request failed and the response says `:retry?`,
  or the server answered 429 or a status from 500."
  [url {:keys [status error timeout? retry?]}]
  (cond
    error                                             (cond-> {:id :comments/unreachable :url url :reason error}
                                                        timeout? (assoc :timeout? true)
                                                        retry?   (assoc :retry? true))
    (#{401 403} status)                               {:id :comments/refused :url url :status status}
    (not (and (integer? status) (<= 200 status 299))) (cond-> {:id :comments/unreachable :url url :status status}
                                                        (and (integer? status) (or (= 429 status) (<= 500 status 599)))
                                                        (assoc :retry? true))))

(defn ^:no-doc fit-links
  "The Hiccup `nodes` of a post whose URL is `base`, fit to show on the page
  of an app.

  Each link is made absolute against `base`, marked as the link of a user
  that the app does not vouch for, and left without a target when it is no
  http or https URL. Each image is its alt text, since an image loads when
  the page does.

  `opts` can give two functions. `:a` gets the attributes of a link and
  its fitted children, and gives the node in the place of the link, or nil
  for the link as it is. `:text` gets a text, and gives the nodes in its
  place."
  ([nodes base]
   (fit-links nodes base {}))
  ([nodes base {:keys [a text] :or {a (constantly nil) text vector}}]
   (letfn [(nodes-of [x]
             (if-not (vector? x)
               (text x)
               (let [[tag attrs children] (html/parts x)
                     kids (mapcat nodes-of children)]
                 (case tag
                   :img (text (str (:alt attrs)))
                   :a   [(or (a attrs kids)
                             (let [target (:href attrs)]
                               (into [:a (present {:href  (web-url (if (and base target) (http/absolute-url base target) target))
                                                   :title (:title attrs)
                                                   :rel   "nofollow ugc"})]
                                     kids)))]
                   [(into [tag attrs] kids)]))))]
     (mapcat nodes-of nodes))))

(defn ^:no-doc safe-html
  "The HTML `s` of a post whose URL is `base`, safe for the page of an app
  to show: sanitized by `html/hiccup`, which makes paragraphs of plain
  text, with its links fit as `fit-links` has it. Nil when it is blank."
  [s base]
  (when (and (string? s) (not (str/blank? s)))
    (html/emit (fit-links (html/hiccup s) base))))

(defn ^:no-doc joined
  "The parts `ps` of a content as a vector. The texts that follow each
  other are one text, and the texts that are empty are left out."
  [ps]
  (reduce (fn [out {:keys [type text] :as p}]
            (cond
              (and (= :text type) (= "" text))
              out

              (and (= :text type) (= :text (:type (peek out))))
              (update-in out [(dec (count out)) :text] str text)

              :else
              (conj out p)))
          []
          ps))

(defn ^:no-doc user-link
  "The Hiccup of a link to `url` that a user wrote in a content, with the
  label `label`. The link is marked as the link of a user that the app
  does not vouch for. It is the label alone when the URL is no http or
  https URL with a host."
  [url label]
  (if-let [href (web-url url)]
    [:a {:href href :rel "nofollow ugc"} label]
    label))

(defn ^:no-doc linked
  "The Hiccup of the part `part` of a content, with the label `label`: a
  link to what the `:link` of `opts` gives for the part, or else the
  label. `:link` is a function of a part, and the app chooses where a
  mention, a hashtag or a reference leads."
  [part label {:keys [link]}]
  (if-let [href (when link (link part))]
    [:a {:href href} label]
    label))

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

(defn ^:no-doc truncate
  "`thread` with `n` more replies among those that the limit `k` of `limits`
  cut, which its report counts."
  [thread k n]
  (cond-> thread
    (pos? n) (update-in [::comments/truncated k] (fnil + 0) n)))

(defn ^:no-doc retried
  "`thread` with its root, its posts and its authors unread again, where a
  failure that can pass with time kept them from the reading.

  The failure of a root that the Mastodon API gave stays, because it tells
  why the thread came through the API."
  [{:keys [error via comments authors] :as thread}]
  (let [unread #(cond-> % (:retry? (:error %)) (dissoc :error :read-at))]
    (cond-> thread
      (and (:retry? error) (not via)) (dissoc :error :read-at)
      comments                        (update :comments update-vals unread)
      authors                         (update :authors #(into {} (remove (comp :retry? :error val)) %)))))

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

(comment
  (present {:name "Ann" :handle "" :avatar nil :labels #{} :hidden? false})

  ;; a failure that can pass with time is :retry?
  (failure "https://example.social/1" {:status 429})
  (failure "https://example.social/1" {:status 404})
  (failure "https://example.social/1" {:error "Connection refused" :retry? true})

  ;; no handler, a link made absolute, and the alt text of an image
  (safe-html "<p onclick=\"x()\">Hi <a href=\"/tags/podcast\">#podcast</a><img src=\"x.png\" alt=\"a cat\"></p>"
             "https://example.social/users/ann/statuses/1")

  (depths {:root     "a"
           :comments {"a" {:id "a"} "b" {:id "b" :in-reply-to "a"} "c" {:id "c" :in-reply-to "b"}}})
  #_.)
