(ns dk.simongray.podcast.comments
  "The public comments on an episode or a show, from the thread below the
  root post that podcast:socialInteract names. The comments of
  ActivityPub, of the AT Protocol and of Nostr come in one shape.
  `comments.activitypub` reads the threads of ActivityPub, which Mastodon,
  PeerTube and the podcast host Castopod speak. `comments.atproto` reads
  those of the AT Protocol, which Bluesky speaks, from an AppView.
  `comments.nostr` reads those of Nostr from relays. It also reads the
  comments about an episode that names no root post.

  A thread is a map. Each interact that `resolve/social-interacts` gives,
  `{:protocol :uri}`, is an unread thread, and `thread` gives the one of an
  episode. `fetch!` reads a thread as far as its limits allow. It adds the
  id of the `:root` post, the `:comments` by id, their `:authors` by id,
  and the `:report` of what went wrong. A later fetch reads on from where
  the last one stopped. `tree` gives the comments in the shape to show them.
  `resumed`, `requests`, `next-page` and `with-exchanges` are the pure
  steps of `fetch!`, for an app that sends the requests itself.

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
  ActivityPub, `@handle` on the AT Protocol and `npub1…` on Nostr. A
  thread that podcast-clj read through the Mastodon API is `:via
  :mastodon-api`. The keys in this namespace, such as `::collection`,
  record how far the reading went."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.comments.activitypub :as activitypub]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.resolve :as resolve]))

(def protocols
  "The protocols of podcast:socialInteract whose threads `fetch!` reads."
  #{"activitypub" "atproto" "nostr"})

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
  API, its conversation, its relays, its posts, the collections of their
  replies and their authors, as diagnostics without their level."
  [{:keys [comments authors] ::keys [api conversation] ::nostr-comments/keys [relays]}]
  (concat (keep :error [api conversation])
          (keep :error (map val (sort-by key relays)))
          (keep :error (vals comments))
          (keep (comp :error ::collection) (vals comments))
          (keep :error (vals authors))))

(defn ^:no-doc origin
  "The origin of the URL `url` of a server or of a relay, as `http/origin`
  reads the origin of a web URL."
  [url]
  (http/origin (str/replace (str url) #"(?i)^ws" "http")))

(defn ^:no-doc public?
  "Whether the host of the URL `url` of a server or of a relay is on the
  public internet, as `http/public?` and `nostr/public?` tell."
  [url]
  (if (nostr/relay-url url)
    (nostr/public? url)
    (http/public? url)))

(defn ^:no-doc send!
  "Sends the request map `request` as `http/call!` does, or as
  `nostr/call!` does when its URL is that of a relay."
  [request]
  (if (nostr/relay-url (:url request))
    (nostr/call! request)
    (http/call! request)))

(defn ^:no-doc spared-origins
  "The origins that the reading of `thread` asks no more: those of the
  servers that did not answer in time, and of those that answered 429, too
  many requests. `resumed` takes these failures out of a thread, so a
  server is spared in one reading only."
  [{:keys [error] :as thread}]
  (set (for [{:keys [url status timeout?]} (cons error (errors thread))
             :when (or timeout? (= 429 status))]
         (origin url))))

(defn resumed
  "`thread` as a new reading of it starts. `fetch!` applies it first, and
  an app that sends the `requests` itself does the same.

  A failure that can pass with time kept a post, an author, a page of
  replies or a root from the last reading. Such a part is unread again.
  The failure is `:retry?` in the report: the request failed, or the
  server answered 429 or a status from 500. The thread also notes how
  many comments it holds, so that `:max-comments` counts the comments of
  this reading."
  [{:keys [protocol] :as thread}]
  (let [thread (case protocol
                 "activitypub" (activitypub/resumed thread)
                 "atproto"     (atproto/resumed thread)
                 "nostr"       (nostr-comments/resumed thread)
                 thread)
        held   (count (filter :read-at (vals (:comments thread))))]
    (if (pos? held)
      (assoc thread ::held held)
      (dissoc thread ::held))))

(defn ^:no-doc pending
  "The requests that `thread` needs next with the limits of `opts`, of those
  whose URLs `ask?` takes."
  [thread opts ask?]
  (case (:protocol thread)
    "activitypub" (activitypub/requests thread opts ask?)
    "atproto"     (atproto/requests thread opts ask?)
    "nostr"       (nostr-comments/requests thread opts ask?)
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
  posts than the `:max-comments` of `opts` or `default-limits` allows,
  counted from the last `resumed`. For the AT Protocol, they go to the
  `:appview` of `opts`. For the Mastodon API, the `:status-paths` of `opts`
  replace `activitypub/status-paths`. For Nostr, they go to the relays
  that the root names and to the `:relays` of `opts`. A server that did
  not answer in time, or that answered 429, gets no more requests until
  the next `resumed`. Each request is a request map for `http/call!`, or
  for `nostr/call!` when its URL is that of a relay, and each URL comes
  once."
  ([thread]
   (requests thread {}))
  ([thread opts]
   (let [spared (spared-origins thread)]
     (distinct-by :url (pending thread (merge default-limits opts) #(not (contains? spared (origin %))))))))

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
  or `{:error reason}` when the request failed. Such a response is also
  `:timeout?` when the request took too long, and `:retry?` when a later
  request can get an answer."
  [thread exchanges at]
  (case (:protocol thread)
    "activitypub" (activitypub/with-exchanges thread exchanges at)
    "atproto"     (atproto/with-exchanges thread exchanges at)
    "nostr"       (nostr-comments/with-exchanges thread exchanges at)
    thread))

(defn readable?
  "Whether `fetch!` can read the thread of `interact`, a map that
  `resolve/social-interacts` gives, with `opts`.

  The protocol of the thread must be one of `protocols`, and its URI must
  have the form of that protocol. For ActivityPub, the URI is an http or
  https URL. For the AT Protocol, it is the at URI of a post, and `opts`
  must name an `:appview`. For Nostr, it is the name of a note, or the id
  that NIP-73 gives a show or an episode."
  ([interact]
   (readable? interact {}))
  ([{:keys [protocol] :as interact} opts]
   (case protocol
     "activitypub" (activitypub/readable? interact)
     "atproto"     (atproto/readable? interact opts)
     "nostr"       (nostr-comments/readable? interact opts)
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
  of replies or an author. They also tell whether it left out replies, how
  many replies a limit of size cut, and whether a limit stopped the fetch.
  For Nostr, they tell how many events a relay gave that are not
  verified."
  ([thread]
   (report thread {}))
  ([{:keys [protocol uri error via] ::keys [withheld truncated] ::nostr-comments/keys [left-out] :as thread} opts]
   (let [opts (merge default-limits opts)]
     (mapv diagnostics/describe
           (cond
             (not (contains? protocols protocol))
             [{:id :comments/unsupported :protocol protocol}]

             (and (= "atproto" protocol) (not (string? (:appview opts))))
             [{:id :comments/no-appview}]

             (and (= "nostr" protocol) (nostr-comments/root-of uri) (empty? (nostr-comments/relays-of thread opts)))
             [{:id :comments/no-relay}]

             (not (readable? thread opts))
             [{:id :comments/malformed :url uri}]

             :else
             (concat (when error [(cond-> error via (assoc :id :comments/through-api))])
                     (errors thread)
                     (when (seq withheld) [{:id :comments/withheld :count (count withheld)}])
                     (when left-out [{:id :comments/unverified :count left-out}])
                     (for [[limit n] (sort-by key truncated)]
                       {:id :comments/truncated :limit limit :count n})
                     ;; what the next fetch asks for, with no limit and no server spared
                     (when (seq (pending (resumed thread) (assoc opts :max-comments ##Inf) (constantly true)))
                       [{:id :comments/incomplete}])))))))

(defn- levels
  "The posts of a thread from the post `top` down, level by level, each
  post once: `[[top] [replies] ...]`, 256 levels at most. `children` gives
  the replies to a post by its id, and `shown?` tells which replies count."
  [top children shown?]
  (loop [levels [[top]] seen #{(:id top)}]
    (let [below (into [] (comp (mapcat #(get children (:id %))) (filter shown?) (remove #(seen (:id %))))
                      (peek levels))]
      (if (or (empty? below) (>= (count levels) 256))
        levels
        (recur (conj levels below) (into seen (map :id) below))))))

(defn- oldest-first
  "The key that puts the comment `c` in its place among replies: by the
  time of its publication, and a comment without one last."
  [{:keys [published id]}]
  [(if published (inst-ms published) ##Inf) id])

(defn- nested
  "Each post of `levels` with the `:replies` below it and the map of its
  `:author`, by its id, made from the deepest level up. A deleted post, or
  a post with an error, is there only when a reply below it is, or when it
  is the root."
  [levels children authors]
  (let [root (:id (ffirst levels))]
    (reduce (fn [built {:keys [id author deleted? error] :as c}]
              (let [replies (->> (get children id) (keep #(get built (:id %))) (sort-by oldest-first) vec)]
                (if (and (not= id root) (or deleted? error) (empty? replies))
                  built
                  (assoc built id (cond-> (assoc c :replies replies)
                                    author (assoc :author (get authors author {:id author})))))))
            {}
            (apply concat (rseq levels)))))

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
           shown?   (fn [c] (and (:read-at c) (or hidden? (not (:hidden? c)))))]
       (get (nested (levels top children shown?) children authors) root)))))

(defn parts
  "The parts of the content of `comment`, a comment of `thread`, in their
  order. Each part is a map with its `:type` and the `:text` that the
  content shows for it:

      {:type :text :text \"Good episode, \"}
      {:type :mention :text \"@ann\" :id \"https://example.social/users/ann\"}
      {:type :hashtag :text \"#podcast\" :tag \"podcast\"}
      {:type :link :text \"example.org\" :url \"https://example.org/\"}
      {:type :reference :text \"nevent1qqs9…m9d82\" :uri \"nostr:nevent1…\"}
      {:type :emoji :text \":blobcat:\" :shortcode \"blobcat\" :url \"https://…\"}

  The `:id` of a mention is the id of an author, as the `:authors` of a
  thread have it. It is the id of an actor on ActivityPub, a DID on the
  AT Protocol and a public key on Nostr. A part has a `:url` when the
  content itself names a web page for it. A reference names an event of
  Nostr, and an emoji is a custom emoji of ActivityPub.

  The parts are for an app that writes the content in its own way. `text`
  gives the content as plain text. The reader of each protocol has
  `parts` for what it keeps of a comment, and its docstring tells more."
  [{:keys [protocol] :as thread} {:keys [content] :as comment}]
  (or (case protocol
        "activitypub" (some-> (::activitypub/source comment) activitypub/parts)
        "atproto"     (some-> (::atproto/record comment) atproto/parts)
        "nostr"       (some-> (::nostr-comments/event comment)
                              (nostr-comments/parts {:names (nostr-comments/names thread)}))
        nil)
      (if-let [text (common/first-text (some-> content html/text))]
        [{:type :text :text text}]
        [])))

(defn hiccup
  "The content of `comment`, a comment of `thread`, as Hiccup that a page
  can show, with `opts`. The result is a sequence of nodes to put inside
  a parent element, as `[:div (hiccup thread comment)]`.

  `:link` is a function of a part of `parts`, with the `:protocol` of the
  thread. It gives the URL that a mention, a hashtag or a reference links
  to, or nil. So the app chooses where they lead, in one function for
  each protocol:

      (hiccup thread comment
              {:link (fn [{:keys [type id tag]}]
                       (case type
                         :mention (str \"/profiles/\" id)
                         :hashtag (str \"/tags/\" tag)
                         nil))})

  For nil, a mention of ActivityPub keeps the link that the post has, and
  a mention of another protocol has no link. `:emoji? true` shows the
  custom emoji of ActivityPub as images. `:names` is a map from the ids
  of profiles of Nostr to names, in the place of the names of the authors
  of the thread.

  The `:content` of a comment is the same content as HTML, without the
  links of an app. A comment that an older version of podcast-clj read
  has only that HTML, and the Hiccup is then made of it."
  ([thread comment]
   (hiccup thread comment {}))
  ([{:keys [protocol] :as thread} {:keys [content] :as comment} {:keys [link] :as opts}]
   {:pre [(map? opts)]}
   (let [opts (cond-> opts
                link (assoc :link #(link (assoc % :protocol protocol))))]
     (or (case protocol
           "activitypub" (some-> (::activitypub/source comment) (activitypub/hiccup opts))
           "atproto"     (some-> (::atproto/record comment) (atproto/hiccup opts))
           "nostr"       (some-> (::nostr-comments/event comment)
                                 (nostr-comments/hiccup (merge {:names (nostr-comments/names thread)} opts)))
           nil)
         (html/hiccup content)))))

(defn text
  "The content of `comment`, a comment of `thread`, as plain text: the
  text that a reader sees in what `hiccup` gives, as `html/text` writes
  it. A blank line is between two paragraphs.

      (text thread comment)  ; => \"Is this @Ann approved? #podcast\"

  The text is not the text that the author sent. A note of Nostr names a
  profile as a nostr: URI of 69 characters, and the text has the name of
  the profile in its place. The text of a post of ActivityPub has no tags
  of HTML.

  `opts` can give `:links? true`, as `html/text` takes it. Each link then
  has its URL after it. `opts` can also give the `:names` of `hiccup`."
  ([thread comment]
   (text thread comment {}))
  ([thread comment opts]
   {:pre [(map? opts)]}
   (html/text (hiccup thread comment (dissoc opts :link)) (select-keys opts [:links?]))))

(defn ^:no-doc exchange!
  "The exchange of `request`, sent by the `:send` of `opts` with its limits
  of time and size, the time cut to what is left before its `::deadline`;
  deferred.

  It is `{:request :response :document}`: the response without its body,
  and the JSON object that the body held. When sending failed, the
  response is `{:error reason}`, and `:timeout?` when it took too long; a
  body larger than the limit fails too, when a `:send` did not keep to
  it.

  A failed response is `:retry?`, unless the guard of the request refused
  its URL or the answer was too large. A later request gets the same
  failure then."
  [request {:keys [send timeout-seconds max-bytes now] ::keys [deadline] :as opts}]
  (let [left    (when deadline (quot (- deadline (inst-ms (now))) 1000))
        seconds (max 1 (min timeout-seconds (or left timeout-seconds)))
        stays?  #(contains? #{::http/refused ::http/too-large} (:type (ex-data %)))]
    (async/then (http/sent! send request (assoc opts :timeout-seconds seconds))
                (fn [{:keys [value error]}]
                  (cond
                    error
                    {:request request :response (cond-> {:error (async/explain error)}
                                                  (http/timeout? error) (assoc :timeout? true)
                                                  (not (stays? error))  (assoc :retry? true))}

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
  stopped, and it asks again for what failed for a time, as `resumed`
  tells. To read what is new, fetch the interact again. `opts`, with
  `default-limits` for the limits:

      {:appview         \"https://…\"  ; to read AT Protocol threads from
       :status-paths    [#\"…\"]       ; in place of activitypub/status-paths
       :relays          [\"wss://…\"]  ; to read Nostr threads from, in place of nostr/relays
       :max-comments    100          ; ask for no more posts after this many in a fetch
       :max-requests    300          ; start no round after this many
       :max-seconds     60           ; start no request after this long
       :concurrency     4            ; requests under way at a time
       :max-pages       5            ; of a collection in a round
       :timeout-seconds 30           ; for each request
       :max-bytes       5242880      ; of each answer
       :allow?          f            ; which URLs a request can go to
       :send            f            ; in place of http/call! and nostr/call!
       :now             f}           ; the time, the clock by default

  The requests go out in rounds. The root post comes first, then the posts
  nearest to it, then the authors. The pages of a collection come one after
  another, as `next-page` names them. A server that did not answer in
  time, or that answered 429 Too Many Requests, gets no more requests in
  the same fetch. The time that is left limits each request. `fetch!`
  throws nothing.

  A thread also has limits of size that no option sets, such as 10,000
  comments in all. The report counts the replies that such a limit cut.

  The servers of a thread choose the URLs that `fetch!` fetches. So each
  request carries a guard as its `:allow?`, which is `http/public?` for a
  server and `nostr/public?` for a relay. `http/call!` and `nostr/call!`
  then send nothing to the network of the app. An app that must reach its
  own network gives `(constantly true)` as `:allow?`. A `:send` keeps to
  the `:timeout-seconds` and the `:allow?` of each request. A `:send` that
  signs its requests can also read the servers that answer only signed
  requests.

  A request to a relay has the `:url` of the relay and the `:filters` of
  NIP-01, and `nostr/call!` sends it over a WebSocket. A `:send` of the
  app gets such requests too, and answers them as `nostr/call!` does."
  ([thread]
   (fetch! thread {}))
  ([thread opts]
   (let [{:keys [concurrency max-requests max-pages max-seconds now] :as opts}
         (merge default-limits
                {:send   send!
                 :allow? public?
                 :now    #?(:clj #(java.util.Date.) :cljs #(js/Date.))}
                opts)
         opts  (cond-> opts max-seconds (assoc ::deadline (+ (inst-ms (now)) (* 1000 max-seconds))))
         start (resumed thread)
         batch (fn [thread sent]
                 (when (or (not max-seconds) (< (inst-ms (now)) (::deadline opts)))
                   (seq (take (min (max 1 concurrency) (- max-requests sent)) (requests thread opts)))))
         limit (fn [b sent]
                 (max 1 (min max-pages (quot (- max-requests sent) (count b)))))
         ;; the thread after one more round, with the requests that went out
         ;; so far, or nil when the thread needs no round
         round (fn [[thread sent]]
                 (when-let [b (batch thread sent)]
                   (async/then (round! b (limit b sent) opts)
                               (fn [exchanges]
                                 [(with-round thread exchanges (now)) (+ sent (count (apply concat exchanges)))]))))]
     (async/then (async/iteration round :initk [start 0] :vf first)
                 (fn [threads]
                   (let [read (or (peek threads) start)]
                     (assoc read :report (report read opts))))))))

(comment
  (def interact
    {:protocol "activitypub" :uri "https://example.social/users/ann/statuses/1"})

  (readable? interact)
  (readable? {:protocol "atproto" :uri "at://did:plc:ann/app.bsky.feed.post/1"})
  (requests interact)
  (map :id (report {:protocol "matrix" :uri "https://matrix.to/#/#podcast:example.org"}))

  ;; a thread that a fetch read: the root, a reply, and a reply that the
  ;; author of the root hid
  (def read-thread
    (let [at #inst "2026-09-29T12:00:00Z"]
      (assoc interact
             :read-at  at
             :root     "a"
             :authors  {"ann" {:id "ann" :name "Ann"}}
             :comments {"a" {:id "a" :author "ann" :read-at at}
                        "b" {:id "b" :author "bob" :in-reply-to "a" :read-at at}
                        "c" {:id "c" :author "ann" :in-reply-to "a" :hidden? true :read-at at}})))

  (tree read-thread)
  (tree read-thread {:hidden? true})

  ;; a fetch that sends no request to the network
  (fetch! interact {:send (fn [request] {:status 404 :url (:url request) :headers {}})})
  #_.)

#?(:clj
   (comment
     (def fetched
       (fetch! {:protocol "activitypub" :uri "https://podcastindex.social/@dave/117294750060235480"}))

     (:report fetched)
     (tree fetched)
     (fetch! {:protocol "atproto" :uri "at://did:plc:pzznkrzajsaqev3ffamg3jnr/app.bsky.feed.post/3mvzl4tmue22y"}
             {:appview "https://public.api.bsky.app"})
     #_.))
