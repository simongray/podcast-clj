(ns dk.simongray.podcast.comments
  "The public comments on an episode or a show, from the thread below the
  root post that podcast:socialInteract names. The comments of ActivityPub
  and of the AT Protocol come in one shape. `comments.activitypub` reads
  the threads of ActivityPub, which Mastodon, PeerTube and the podcast host
  Castopod speak. `comments.atproto` reads those of the AT Protocol, which
  Bluesky speaks, from an AppView.

  A thread is a map. Each interact that `resolve/social-interacts` gives,
  `{:protocol :uri}`, is an unread thread, and `thread` gives the one of an
  episode. `fetch!` reads a thread as far as its limits allow. It adds the
  id of the `:root` post, the `:comments` by id, their `:authors` by id,
  and the `:report` of what went wrong. A later fetch reads on from where
  the last one stopped. `tree` gives the comments in the shape to show them.
  `requests`, `next-page` and `with-exchanges` are the pure steps of
  `fetch!`, for an app that sends the requests itself.

  A comment:

      {:id          \"https://example.social/users/ann/statuses/2\"
       :url         \"https://example.social/@ann/2\"  ; its web page
       :author      \"https://example.social/users/ann\"  ; in :authors
       :in-reply-to \"https://example.social/users/bob/statuses/1\"
       :published   #inst \"2026-09-19T00:22:52Z\"
       :updated     #inst \"2026-09-19T00:30:00Z\"
       :title       \"…\"         ; of a video or an article
       :content     \"<p>…</p>\"  ; sanitized HTML with absolute links
       :language    \"en\"
       :summary     \"…\"         ; a content warning
       :sensitive?  true        ; to show behind a warning
       :labels      #{\"graphic-media\"}  ; what makes it sensitive
       :attachments [{:kind :image :url \"…\" :preview \"…\" :alt \"…\"
                      :media-type \"image/png\" :width 1200 :height 800
                      :aspect-ratio [1200 800]}]
       :poll        {:choices [{:name \"Yes\" :votes 3}] :multiple? true
                     :ends #inst \"2026-09-20T00:00:00Z\"}
       :hidden?     true        ; hidden by the author of the root post
       :read-at     #inst \"2026-09-28T12:00:00Z\"}

  A deleted post is `:deleted?`, and a post that podcast-clj did not get
  has an `:error` that tells why. An author is
  `{:id :name :handle :url :avatar}`. The handle is `@name@domain` on
  ActivityPub and `@handle` on the AT Protocol. A thread that podcast-clj
  read through the Mastodon API is `:via :mastodon-api`. The keys in this
  namespace, such as `::collection`, record how far the reading went."
  (:require [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.comments.activitypub :as activitypub]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.resolve :as resolve]))

(def protocols
  "The protocols of podcast:socialInteract whose threads `fetch!` reads."
  #{"activitypub" "atproto"})

(def default-limits
  "The limits of `fetch!` that its options do not set.

  The docstring of `fetch!` tells what each limit does. A page of a
  collection is a few kilobytes, and the thread of an AppView a few
  megabytes. A round of requests keeps all its answers in memory until it
  ends."
  {:max-comments    100
   :max-requests    300
   :max-seconds     60
   :concurrency     4
   :max-pages       5
   :timeout-seconds 30
   :max-bytes       (* 5 1024 1024)})

(defn ^:no-doc errors
  "The errors of the reading of `thread` below its root: of the Mastodon
  API, its conversation, its posts, the collections of their replies and
  their authors, as diagnostics without their level."
  [{:keys [comments authors] ::keys [api conversation]}]
  (concat (keep :error [api conversation])
          (keep :error (vals comments))
          (keep (comp :error ::collection) (vals comments))
          (keep :error (vals authors))))

(defn ^:no-doc spared-origins
  "The origins that the reading of `thread` asks no more: those of the
  servers that did not answer in time, and of those that answered 429, too
  many requests."
  [{:keys [error] :as thread}]
  (set (for [{:keys [url status timeout?]} (cons error (errors thread))
             :when (or timeout? (= 429 status))]
         (http/origin url))))

(defn ^:no-doc pending
  "The requests that `thread` needs next with the limits of `opts`, of those
  whose URLs `ask?` takes."
  [thread opts ask?]
  (case (:protocol thread)
    "activitypub" (activitypub/requests thread opts ask?)
    "atproto"     (atproto/requests thread opts ask?)
    []))

(defn- distinct-by
  "The `xs` without those whose `f` one before them has."
  [f xs]
  (let [seen (volatile! #{})]
    (filterv #(let [k (f %)] (when-not (contains? @seen k) (vswap! seen conj k))) xs)))

(defn requests
  "The requests that `thread` needs next, as `opts` allow, in the order to
  send them. None when podcast-clj can read no more of the thread.

  The root post comes first. Then come the posts and the collections of
  replies nearest to it, and then their authors. They ask for no more
  posts than the `:max-comments` of `opts` or `default-limits` allows. For
  the AT Protocol, they go to the `:appview` of `opts`. A server that did
  not answer in time, or that answered 429, gets no more requests. Each
  request is a request map for `http/call!`, and each URL comes once."
  ([thread]
   (requests thread {}))
  ([thread opts]
   (let [spared (spared-origins thread)]
     (distinct-by :url (pending thread (merge default-limits opts) #(not (contains? spared (http/origin %))))))))

(defn next-page
  "The request for the page after the one in `exchange`, an exchange
  `{:request :response}` of the reading of a collection. Nil after the last
  page, and for any other exchange.

  Only the collections of ActivityPub come in pages, so it is
  `activitypub/next-page`."
  [exchange]
  (activitypub/next-page exchange))

(defn with-exchanges
  "`thread` with the `exchanges` of one of its `requests` read into it at
  the instant `at`.

  The exchanges are the exchange of the request and, for a collection, the
  exchanges of the pages that `next-page` names after it. Each exchange is
  `{:request :response}`. The response is the map that `http/call!` gives,
  or `{:error reason}` when the request failed."
  [thread exchanges at]
  (case (:protocol thread)
    "activitypub" (activitypub/with-exchanges thread exchanges at)
    "atproto"     (atproto/with-exchanges thread exchanges at)
    thread))

(defn readable?
  "Whether `fetch!` can read the thread of `interact`, a map that
  `resolve/social-interacts` gives, with `opts`.

  The protocol of the thread must be one of `protocols`, and its URI must
  have the form of that protocol. For ActivityPub, the URI is an http or
  https URL. For the AT Protocol, it is the at URI of a post, and `opts`
  must name an `:appview`."
  ([interact]
   (readable? interact {}))
  ([{:keys [protocol] :as interact} opts]
   (case protocol
     "activitypub" (activitypub/readable? interact)
     "atproto"     (atproto/readable? interact opts)
     false)))

(defn thread
  "The unread thread of comments on `item` of `feed` that `fetch!` can read
  with `opts`. Nil when there is none.

  It is the first podcast:socialInteract of the item, by priority, that is
  `readable?`. When the item names none, it is the first of the feed. There
  is none when the podcaster wants no public comments."
  ([feed item]
   (thread feed item {}))
  ([feed item opts]
   (first (filter #(readable? % opts) (resolve/social-interacts feed item)))))

(defn report
  "The diagnostics of `thread` as `fetch!` read it with `opts`. For the AT
  Protocol, `opts` names the `:appview`.

  They tell why podcast-clj did not read the thread, a post, a collection
  of replies or an author. They also tell whether it left out replies, and
  whether a limit stopped the fetch."
  ([thread]
   (report thread {}))
  ([{:keys [protocol uri error via] ::keys [withheld] :as thread} opts]
   (let [opts (merge default-limits opts)]
     (mapv diagnostics/describe
           (cond
             (not (contains? protocols protocol))
             [{:id :comments/unsupported :protocol protocol}]

             (and (= "atproto" protocol) (not (string? (:appview opts))))
             [{:id :comments/no-appview}]

             (not (readable? thread opts))
             [{:id :comments/malformed :url uri}]

             :else
             (concat (when error [(cond-> error via (assoc :id :comments/through-api))])
                     (errors thread)
                     (when (seq withheld) [{:id :comments/withheld :count (count withheld)}])
                     (when (seq (pending thread (assoc opts :max-comments ##Inf) (constantly true)))
                       [{:id :comments/incomplete}])))))))

(defn tree
  "The comments of `thread` in the shape to show them, with `opts`. Nil
  until `fetch!` reads the root post.

  The tree is the root post, with its `:author` as the map of its author.
  Its `:replies` are comments of the same shape, oldest first. An unread
  reply is not in the tree, and neither are the replies below it.
  The tree leaves out the replies that the author of the root post hid.
  With `:hidden? true` in `opts`, it holds them too. A deleted post, or a
  post that podcast-clj did not get, is in the tree only when a reply below
  it is. The tree ends 256 levels down."
  ([thread]
   (tree thread {}))
  ([{:keys [root comments authors]} {:keys [hidden?]}]
   (when-let [top (get comments root)]
     (let [children (group-by :in-reply-to (vals comments))
           shown?   (fn [c] (and (:read-at c) (or hidden? (not (:hidden? c)))))
           ;; the posts below the root, level by level, each once
           levels   (loop [levels [[top]] seen #{root}]
                      (let [below (into [] (comp (mapcat #(get children (:id %))) (filter shown?) (remove #(seen (:id %))))
                                        (peek levels))]
                        (if (or (empty? below) (>= (count levels) 256))
                          levels
                          (recur (conj levels below) (into seen (map :id) below)))))
           order    (fn [{:keys [published id]}] [(if published (inst-ms published) ##Inf) id])
           ;; each post with the replies below it, from the deepest level up
           built    (reduce (fn [built level]
                              (reduce (fn [built {:keys [id author deleted? error] :as c}]
                                        (let [replies (->> (get children id) (keep #(get built (:id %))) (sort-by order) vec)]
                                          (if (and (not= id root) (or deleted? error) (empty? replies))
                                            built
                                            (assoc built id (cond-> (assoc c :replies replies)
                                                              author (assoc :author (get authors author {:id author})))))))
                                      built
                                      level))
                            {}
                            (rseq levels))]
       (get built root)))))

(defn ^:no-doc exchange!
  "The exchange of `request`, sent by the `:send` of `opts` with its limits
  of time and size, the time cut to what is left before its `::deadline`;
  deferred.

  It is `{:request :response :document}`: the response without its body,
  and the JSON object that the body held. When sending failed, the
  response is `{:error reason}`, and `:timeout?` when it took too long; a
  body larger than the limit fails too, when a `:send` did not keep to
  it."
  [request {:keys [send timeout-seconds max-bytes now] ::keys [deadline]}]
  (let [left (when deadline (quot (- deadline (inst-ms (now))) 1000))]
    (async/then (async/attempt #(send (assoc request
                                             :timeout-seconds (max 1 (min timeout-seconds (or left timeout-seconds)))
                                             :max-bytes max-bytes)))
                (fn [{:keys [value error]}]
                  (cond
                    error
                    {:request request :response (cond-> {:error (async/explain error)} (http/timeout? error) (assoc :timeout? true))}

                    (and max-bytes (> (count (str (:body value))) max-bytes))
                    {:request request :response {:error (str "The answer is larger than " max-bytes " bytes")}}

                    :else
                    {:request request :response (dissoc value :body) :document (common/document value)})))))

(defn ^:no-doc failed
  "The exchanges of `request` when reading them failed with `e`: one, with
  the reason as its response."
  [request e]
  [{:request request :response {:error (async/explain e)}}])

(defn ^:no-doc round!
  "The exchanges of the requests of `batch`, all under way at once; deferred.

  Of each request they are its own and those of the pages after it that
  `next-page` names, `limit` pages at most, and none after the
  `::deadline` of `opts`."
  [batch limit {:keys [now] ::keys [deadline] :as opts}]
  (let [in-time? #(or (nil? deadline) (< (inst-ms (now)) deadline))]
    (async/then (async/all (mapv (fn [request]
                                   (async/attempt #(async/iteration (fn [r] (exchange! r opts))
                                                                    :initk request
                                                                    :kf    (fn [exchange] (when (in-time?) (next-page exchange)))
                                                                    :limit limit)))
                                 batch))
                (fn [attempts]
                  (mapv (fn [request {:keys [value error]}] (or value (failed request error))) batch attempts)))))

(defn- with-round
  "`thread` with the exchanges of a `round` of requests read into it at `at`,
  one request after another. The exchanges of a request that cannot be
  read count as a failed request, so that the thread is read on without
  them."
  [thread round at]
  (reduce (fn [thread exchanges]
            (try
              (with-exchanges thread exchanges at)
              (catch #?(:clj Throwable :cljs :default) e
                (with-exchanges thread (failed (:request (first exchanges)) e) at))))
          thread
          round))

(defn fetch!
  "Read `thread` as far as the limits of `opts` allow, and give it with the
  `:report` of what went wrong. On the JVM the thread, in ClojureScript a
  promise of it.

  `thread` is an interact from `resolve/social-interacts`, or a thread
  that an earlier fetch gave. A second fetch reads on from where the first
  stopped. To read what is new, fetch the interact again. `opts`, with
  `default-limits` for the limits:

      {:appview         \"https://…\"  ; to read AT Protocol threads from
       :max-comments    100          ; ask for no more posts after this many
       :max-requests    300          ; start no round after this many
       :max-seconds     60           ; start no request after this long
       :concurrency     4            ; requests under way at a time
       :max-pages       5            ; of a collection in a round
       :timeout-seconds 30           ; for each request
       :max-bytes       5242880      ; of each answer
       :send            f            ; in place of http/call-public!
       :now             f}           ; the time, the clock by default

  The requests go out in rounds. The root post comes first, then the posts
  nearest to it, then the authors. The pages of a collection come one after
  another, as `next-page` names them. A server that did not answer in
  time, or that answered 429 Too Many Requests, gets no more requests. The
  time that is left limits each request. `fetch!` throws nothing.

  The servers of a thread choose the URLs that `fetch!` fetches. So it
  sends with `http/call-public!`, which sends nothing to the network of the
  app. An app that must reach its own network gives `http/call!`. A
  `:send` keeps to the `:timeout-seconds` of each request. A `:send` that
  signs its requests can also read the servers that answer only signed
  requests."
  ([thread]
   (fetch! thread {}))
  ([thread opts]
   (let [{:keys [concurrency max-requests max-pages max-seconds now] :as opts}
         (merge default-limits {:send http/call-public! :now #?(:clj #(java.util.Date.) :cljs #(js/Date.))} opts)
         opts  (cond-> opts max-seconds (assoc ::deadline (+ (inst-ms (now)) (* 1000 max-seconds))))
         batch (fn [thread sent]
                 (when (or (not max-seconds) (< (inst-ms (now)) (::deadline opts)))
                   (seq (take (min (max 1 concurrency) (- max-requests sent)) (requests thread opts)))))
         limit (fn [b sent]
                 (max 1 (min max-pages (quot (- max-requests sent) (count b)))))
         done  #(assoc % :report (report % opts))]
     #?(:clj  (loop [thread thread sent 0]
                (if-let [b (batch thread sent)]
                  (let [round (round! b (limit b sent) opts)]
                    (recur (with-round thread round (now)) (+ sent (count (apply concat round)))))
                  (done thread)))
        :cljs (letfn [(step [thread sent]
                        (if-let [b (batch thread sent)]
                          (async/then (round! b (limit b sent) opts)
                                      #(step (with-round thread % (now)) (+ sent (count (apply concat %)))))
                          (done thread)))]
                (js/Promise.resolve (step thread 0)))))))

#?(:clj
   (comment
     (def thread (fetch! {:protocol "activitypub" :uri "https://podcastindex.social/@dave/117294750060235480"}))
     (:report thread)
     (tree thread)
     (fetch! {:protocol "atproto" :uri "at://did:plc:pzznkrzajsaqev3ffamg3jnr/app.bsky.feed.post/3mvzl4tmue22y"}
             {:appview "https://public.api.bsky.app"})
     #_.))
