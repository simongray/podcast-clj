(ns dk.simongray.podcast.comments
  "The public comments on an episode or a show, from the thread below the
  root post that podcast:socialInteract names.

  The comments of these protocols come in one shape:

  - ActivityPub, which Mastodon, PeerTube and the podcast host Castopod
    speak
  - the AT Protocol, which Bluesky speaks, from an AppView
  - Nostr, from relays, which also has the comments about an episode that
    names no root post

  A thread is a map. An unread thread is a map of the :protocol and the
  :uri of a podcast:socialInteract, and a thread that was read also has:

  - the id of the :root post
  - the :comments by id
  - their :authors by id
  - the :report of what went wrong

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
       :counts      {:replies 2 :reposts 1 :likes 5 :quotes 0}
       :hidden?     true        ; hidden by the author of the root post
       :read-at     #inst \"2026-09-28T12:00:00Z\"}

  A deleted post is :deleted?, and a post that podcast-clj didn't get has
  an :error that tells why. The :counts are those that the server of a
  comment gives with it. On Nostr, they're the :likes and :reposts that
  the relays gave, one for each author. An author is a map of :id, :name,
  :handle, :url and :avatar. The handle is @name@domain on ActivityPub,
  @handle on the AT Protocol and npub1… on Nostr. A thread that
  podcast-clj read through the Mastodon API has :via :mastodon-api. The
  keys in this namespace, such as ::collection, record how far the
  reading went."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.comments.activitypub :as activitypub]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

;; TODO: Consider a library of comments for blogs and other sites: this
;; namespace and comments.*, with the parts of Nostr that reading threads
;; needs (nostr, nostr.secp256k1 and util.bech32). The podcast glue would
;; stay here: the thread of podcast:socialInteract and the ids of NIP-73 for
;; podcasts. What stands in the way today:
;; - value.nwc uses nostr and nostr.secp256k1 too, and value.bolt11 uses
;;   util.bech32, so the payments would require them from a library of
;;   comments, while nostr.nip44 stays here.
;; - The :comments/* diagnostics get their messages from
;;   diagnostics/describe, so the library would need its own.
;; - util.client, util.json and util.time would become its own User-Agent
;;   and small copies.
;; - A blog would soon miss two things: Nostr comments on a web page by its
;;   NIP-73 URL, and the counts of reactions and reposts on Nostr.
;; - ActivityPub is the fediverse, but Bluesky and Nostr aren't, which the
;;   name should allow for.

(defn- nostr-opts
  "The `opts` of a hiccup of a note of the Nostr `thread`, with the names
  of its authors as :names, unless `opts` have their own."
  [thread opts]
  (cond-> opts
    (not (contains? opts :names))
    (assoc :names (nostr-comments/names thread))))

(def ^:no-doc readers
  "The reader of each protocol, as the functions that this namespace calls
  for a thread of it. A reader keeps the post of a comment under a key of
  its own, which :parts and :hiccup read."
  {"activitypub"
   {:resumed        activitypub/resumed
    :requests       activitypub/requests
    :with-exchanges activitypub/with-exchanges
    :readable?      (fn [interact _] (activitypub/readable? interact))
    :parts          (fn [_ comment]
                      (some-> (::activitypub/source comment)
                              activitypub/parts))
    :hiccup         (fn [_ comment opts]
                      (some-> (::activitypub/source comment)
                              (activitypub/hiccup opts)))}

   "atproto"
   {:resumed        atproto/resumed
    :requests       atproto/requests
    :with-exchanges atproto/with-exchanges
    :readable?      atproto/readable?
    :parts          (fn [_ comment]
                      (some-> (::atproto/record comment) atproto/parts))
    :hiccup         (fn [_ comment opts]
                      (some-> (::atproto/record comment)
                              (atproto/hiccup opts)))}

   "nostr"
   {:resumed        nostr-comments/resumed
    :requests       nostr-comments/requests
    :with-exchanges nostr-comments/with-exchanges
    :readable?      nostr-comments/readable?
    :parts          (fn [thread comment]
                      (some-> (::nostr-comments/event comment)
                              (nostr-comments/parts
                               {:names (nostr-comments/names thread)})))
    :hiccup         (fn [thread comment opts]
                      (some-> (::nostr-comments/event comment)
                              (nostr-comments/hiccup
                               (nostr-opts thread opts))))}})

(def protocols
  "The protocols of podcast:socialInteract whose threads can be read."
  (set (keys readers)))

(defn- reader
  "The function `k` of the reader of the protocol of `thread`, or nil for a
  protocol that no reader reads."
  [thread k]
  (get-in readers [(:protocol thread) k]))

;; A page of a collection is a few kilobytes, and the thread of an AppView
;; a few megabytes. A round of requests keeps all its answers in memory
;; until it ends.
(def default-limits
  "The limits of a fetch that its options don't set."
  {:max-comments    100
   :max-requests    300
   :max-seconds     60
   :concurrency     4
   :max-pages       5
   :timeout-seconds 30
   :max-bytes       (* 5 1024 1024)})

(defn ^:no-doc errors
  "The errors of the reading of `thread` below its root, as diagnostics
  without their level.

  They're those of the Mastodon API, the conversation, the relays, the
  posts, the collections of their replies and the authors."
  [{:keys [comments authors]
    ::keys [api conversation]
    ::nostr-comments/keys [relays]
    :as thread}]
  (concat (keep :error [api conversation])
          (keep :error (map val (sort-by key relays)))
          (keep :error (vals comments))
          (keep (comp :error ::collection) (vals comments))
          (keep :error (vals authors))))

(defn ^:no-doc origin
  "The origin of the URL `url` of a server or of a relay."
  [url]
  (url/origin (str/replace (str url) #"(?i)^ws" "http")))

(defn ^:no-doc public?
  "Whether the host of the URL `url` of a server or of a relay is on the
  public internet."
  [url]
  (if (nostr/relay-url url)
    (nostr/public? url)
    (url/public? url)))

(defn send!
  "Send the request map `request` over HTTP, or over a WebSocket when its
  URL is that of a relay."
  [request]
  (if (nostr/relay-url (:url request))
    (nostr/send! request)
    (client/send! request)))

(defn ^:no-doc spared-origins
  "The origins that the reading of `thread` asks no more: those of the
  servers that didn't answer in time or answered 429, Too Many Requests.
  A server is spared in one reading only."
  [{:keys [error] :as thread}]
  (set (for [{:keys [url status timeout?]} (cons error (errors thread))
             :when (or timeout? (= 429 status))]
         (origin url))))

(defn resumed
  "The `thread` as a new reading of it starts, which an app that sends the
  requests itself applies first.

  A failure that can pass with time kept a post, an author, a page of
  replies or a root from the last reading. Such a part is unread again.
  The failure is :retry? in the report: the request failed, or the server
  answered 429 or a status from 500. The thread also notes how many
  comments it holds, so that :max-comments counts the comments of this
  reading."
  [thread]
  (let [thread (if-let [f (reader thread :resumed)]
                 (f thread)
                 thread)
        held   (count (filter :read-at (vals (:comments thread))))]
    (if (pos? held)
      (assoc thread ::held held)
      (dissoc thread ::held))))

(defn ^:no-doc pending
  "The requests that `thread` needs next with the limits of `opts`, of those
  whose URLs `ask-pred` takes."
  [thread opts ask-pred]
  (if-let [f (reader thread :requests)]
    (f thread opts ask-pred)
    []))

(defn- distinct-by
  "The `xs` without those that have the same `f` as one before them."
  [f xs]
  (let [seen   (volatile! #{})
        first? (fn [x]
                 (let [k (f x)]
                   (when-not (contains? @seen k)
                     (vswap! seen conj k))))]
    (filterv first? xs)))

(defn requests
  "The requests that `thread` needs next, as `opts` allow, in the order to
  send them. There are none when podcast-clj can read no more of it.

  The root post comes first. Then come the posts and the collections of
  replies nearest to it, and then their authors. They ask for no more
  posts than the :max-comments of `opts` or default-limits allows,
  counted from the start of the reading. The `opts` also have these:

  - :appview, where the requests for the AT Protocol go
  - :status-paths, for the Mastodon API, in place of
    activitypub/status-paths
  - :relays, where the requests for Nostr go, besides the relays that the
    root names

  A server that didn't answer in time, or that answered 429, gets no more
  requests until the next reading. Each request is a request map, and
  each URL comes once."
  ([thread]
   (requests thread {}))
  ([thread opts]
   (let [spared (spared-origins thread)
         ask?   #(not (contains? spared (origin %)))]
     (distinct-by :url (pending thread (merge default-limits opts) ask?)))))

(defn next-page
  "The request for the page after the one in `exchange`, an exchange of
  the reading of a collection with a :request and a :response. It's nil
  after the last page, and for any other exchange. Only the collections of
  ActivityPub come in pages."
  [exchange]
  (activitypub/next-page exchange))

(defn with-exchanges
  "The `thread` with the `exchanges` of one of its requests read into it at
  the instant `at`.

  The exchanges are the exchange of the request and, for a collection, the
  exchanges of the pages after it. Each exchange is a map of the :request
  and the :response. The response is a response map, or a map of the
  :error when the request failed. Such a response is also :timeout? when
  the request took too long, and :retry? when a later request can get an
  answer."
  [thread exchanges at]
  (if-let [f (reader thread :with-exchanges)]
    (f thread exchanges at)
    thread))

(defn readable?
  "Whether the thread of `interact`, a podcast:socialInteract as a map of
  :protocol and :uri, can be read with `opts`.

  The protocol of the thread must be one of protocols, and its URI must
  have the form of that protocol. For ActivityPub, the URI is an http or
  https URL. For the AT Protocol, it's the at URI of a post or the URL of
  its page on bsky.app, and `opts` must name an :appview. For Nostr, it's
  the name of a note, or the id that NIP-73 gives a show or an episode."
  ([interact]
   (readable? interact {}))
  ([interact opts]
   (if-let [f (reader interact :readable?)]
     (f interact opts)
     false)))

(defn thread
  "The unread thread of comments on `item` of `feed` that can be read with
  `opts`, or nil.

  It's the first podcast:socialInteract of the item, by priority, that can
  be read. When the item names none, it's the first of the feed. There's
  none when the podcaster wants no public comments."
  ([feed item]
   (thread feed item {}))
  ([feed item opts]
   (first (filter #(readable? % opts) (resolve/social-interacts feed item)))))

(defn- reading-report
  "The diagnostics without their levels of the reading of `thread`, a
  thread that can be read with `opts`."
  [{:keys [error via]
    ::keys [withheld truncated]
    ::nostr-comments/keys [left-out]
    :as thread}
   opts]
  (let [unlimited (assoc opts :max-comments ##Inf)
        more?     (seq (pending (resumed thread) unlimited (constantly true)))]
    (concat (when error
              [(cond-> error via (assoc :id :comments/through-api))])
            (errors thread)
            (when (seq withheld)
              [{:id :comments/withheld :count (count withheld)}])
            (when left-out
              [{:id :comments/unverified :count left-out}])
            (for [[limit n] (sort-by key truncated)]
              {:id :comments/truncated :limit limit :count n})
            ;; what the next fetch asks for, with no limit and no server
            ;; spared
            (when more?
              [{:id :comments/incomplete}]))))

(defn report
  "The diagnostics of `thread` as it was read with `opts`, which name the
  :appview for the AT Protocol.

  They tell why podcast-clj didn't read the thread, a post, a collection
  of replies or an author. They also tell whether it left out replies, how
  many replies a limit of size cut, and whether a limit stopped the fetch.
  For Nostr, they tell how many events from a relay aren't verified."
  ([thread]
   (report thread {}))
  ([{:keys [protocol uri] :as thread} opts]
   (let [opts (merge default-limits opts)]
     (mapv diagnostics/describe
           (cond
             (not (contains? protocols protocol))
             [{:id :comments/unsupported :protocol protocol}]

             (and (= "atproto" protocol)
                  (not (string? (:appview opts))))
             [{:id :comments/no-appview}]

             (and (= "nostr" protocol)
                  (nostr-comments/root-of uri)
                  (empty? (nostr-comments/relays-of thread opts)))
             [{:id :comments/no-relay}]

             (not (readable? thread opts))
             [{:id :comments/malformed :url uri}]

             :else
             (reading-report thread opts))))))

(defn- levels
  "The posts of a thread from the post `top` down, level by level, with
  each post once and 256 levels at most. The `children` map gives the
  replies to a post by its id, and `shown-pred` tells which replies count."
  [top children shown-pred]
  (loop [levels [[top]] seen #{(:id top)}]
    (let [below (into []
                      (comp (mapcat #(get children (:id %)))
                            (filter shown-pred)
                            (remove #(seen (:id %))))
                      (peek levels))]
      (if (or (empty? below) (>= (count levels) 256))
        levels
        (recur (conj levels below) (into seen (map :id) below))))))

(defn- oldest-first
  "The key that puts the comment `c` in its place among replies: by the
  time of its publication, and a comment without one last."
  [{:keys [published id] :as c}]
  [(if published (inst-ms published) ##Inf) id])

(defn- nested
  "Each post of `levels` by its id, with the :replies below it from
  `children` and the map of its :author from `authors`.

  The posts are built from the deepest level up. A deleted post, or a post
  with an error, is there only when a reply below it is, or when it's the
  root."
  [levels children authors]
  (let [root (:id (ffirst levels))]
    (reduce (fn [built {:keys [id author deleted? error] :as c}]
              (let [replies (->> (get children id)
                                 (keep #(get built (:id %)))
                                 (sort-by oldest-first)
                                 vec)
                    profile (when author (get authors author {:id author}))
                    post    (cond-> (assoc c :replies replies)
                              author (assoc :author profile))]
                (if (and (not= id root)
                         (or deleted? error)
                         (empty? replies))
                  built
                  (assoc built id post))))
            {}
            (apply concat (rseq levels)))))

(defn tree
  "The comments of `thread` in the shape to show them, with `opts`, or nil
  until the root post is read.

  The tree is the root post, with its :author as the map of its author.
  Its :replies are comments of the same shape, oldest first. An unread
  reply isn't in the tree, and neither are the replies below it. The tree
  leaves out the replies that the author of the root post hid, unless
  `opts` has :hidden?. A deleted post, or a post that podcast-clj didn't
  get, is in the tree only when a reply below it is. The tree ends 256
  levels down."
  ([thread]
   (tree thread {}))
  ([{:keys [root comments authors] :as thread} {:keys [hidden?] :as opts}]
   (when-let [top (get comments root)]
     (let [children (group-by :in-reply-to (vals comments))
           shown?   (fn [c] (and (:read-at c) (or hidden? (not (:hidden? c)))))]
       (get (nested (levels top children shown?) children authors) root)))))

(defn parts
  "The parts of the content of `comment`, a comment of `thread`, in their
  order. Each part is a map with its :type and the :text that the content
  shows for it:

      {:type :text :text \"Good episode, \"}
      {:type :mention :text \"@ann\" :id \"https://example.social/users/ann\"}
      {:type :hashtag :text \"#podcast\" :tag \"podcast\"}
      {:type :link :text \"example.org\" :url \"https://example.org/\"}
      {:type :reference :text \"nevent1qqs9…m9d82\" :uri \"nostr:nevent1…\"}
      {:type :emoji :text \":blobcat:\" :shortcode \"blobcat\"
       :url \"https://…\"}

  The :id of a mention is the id of an author, as the :authors of a
  thread have it. It's the id of an actor on ActivityPub, a DID on the AT
  Protocol and a public key on Nostr. A part has a :url when the content
  itself names a web page for it. A reference names an event of Nostr,
  and an emoji is a custom emoji of ActivityPub.

  The parts are for an app that writes the content in its own way."
  [thread {:keys [content] :as comment}]
  (or (when-let [f (reader thread :parts)]
        (f thread comment))
      (if-let [text (common/first-text (some-> content html/text))]
        [{:type :text :text text}]
        [])))

(defn hiccup
  "The content of `comment`, a comment of `thread`, as Hiccup that a page
  can show, with `opts`. The result is a sequence of nodes to put inside
  a parent element.

  The `opts` are these:

  - :link-fn, a function of a part, with the :protocol of the thread. It
    gives the URL that a mention, a hashtag or a reference links to, or
    nil. So you choose where they lead, in one function for each
    protocol.
  - :emoji?, to show the custom emoji of ActivityPub as images
  - :names, a map from the ids of profiles of Nostr to names, in place of
    the names of the authors of the thread

  For example:

      (hiccup thread comment
              {:link-fn (fn [{:keys [type id tag]}]
                          (case type
                            :mention (str \"/profiles/\" id)
                            :hashtag (str \"/tags/\" tag)
                            nil))})

  When :link-fn gives nil, a mention of ActivityPub keeps the link that the
  post has, and a mention of another protocol has no link.

  The :content of a comment is the same content as HTML, without the
  links of an app. A comment that an older version of podcast-clj read
  has only that HTML, and the Hiccup is then made of it."
  ([thread comment]
   (hiccup thread comment {}))
  ([{:keys [protocol] :as thread}
    {:keys [content] :as comment}
    {:keys [link-fn] :as opts}]
   {:pre [(map? opts)]}
   (let [opts (cond-> opts
                link-fn (assoc :link-fn
                               #(link-fn (assoc % :protocol protocol))))]
     (or (when-let [f (reader thread :hiccup)]
           (f thread comment opts))
         (html/hiccup content)))))

(defn text
  "The content of `comment`, a comment of `thread`, as plain text, with
  `opts`. It's the text that a reader sees in the Hiccup of the content,
  with a blank line between two paragraphs.

      (text thread comment)  ; => \"Is this @Ann approved? #podcast\"

  The text isn't the text that the author sent. A note of Nostr names a
  profile as a nostr: URI of 69 characters, and the text has the name of
  the profile in its place. The text of a post of ActivityPub has no tags
  of HTML.

  The `opts` are these:

  - :links?, to put the URL of each link after it
  - :names, as hiccup takes it"
  ([thread comment]
   (text thread comment {}))
  ([thread comment opts]
   {:pre [(map? opts)]}
   (let [nodes (hiccup thread comment (dissoc opts :link-fn))]
     (html/text nodes (select-keys opts [:links?])))))

(defn ^:no-doc exchange!
  "Send `request` by the :send of `opts`, with its limits of time and size,
  and give the exchange, in a future or a promise. The time is cut to
  what's left before the ::deadline of `opts`.

  The exchange is a map of the :request, the :response without its body,
  and the :document, the JSON object that the body held. When sending
  failed, the response is a map of the :error, and :timeout? when it took
  too long. A body larger than the limit fails too, when a :send didn't
  keep to it.

  A failed response is :retry?, unless the guard of the request refused
  its URL or the answer was too large. A later request gets the same
  failure then."
  [request {:keys [send timeout-seconds max-bytes now-fn] ::keys [deadline]
            :as opts}]
  (let [left    (when deadline (quot (- deadline (inst-ms (now-fn))) 1000))
        seconds (max 1 (min timeout-seconds (or left timeout-seconds)))
        lasting #{::http/refused ::http/too-large}
        stays?  #(contains? lasting (:type (ex-data %)))
        failure (fn [error]
                  (cond-> {:error (async/explain error)}
                    (http/timeout? error) (assoc :timeout? true)
                    (not (stays? error))  (assoc :retry? true)))
        large?  #(and max-bytes (> (count (str (:body %))) max-bytes))]
    (async/then
     (http/try-send! send request (assoc opts :timeout-seconds seconds))
     (fn [{:keys [value error]}]
       (cond
         error
         {:request request :response (failure error)}

         (large? value)
         (let [reason (str "The answer is larger than " max-bytes " bytes")]
           {:request request :response {:error reason}})

         :else
         {:request  request
          :response (dissoc value :body)
          :document (common/document value)})))))

(defn ^:no-doc failed
  "The exchanges of `request` when reading them failed with `e`: one, with
  the reason as its response."
  [request e]
  [{:request request :response {:error (async/explain e)}}])

(defn ^:no-doc send-round!
  "Send the requests of `batch` all at once with `opts`, and give their
  exchanges with up to `limit` pages each, in a future or a promise.

  The exchanges of a request are its own and those of the pages after it,
  with none after the ::deadline of `opts`."
  [batch limit {:keys [now-fn] ::keys [deadline] :as opts}]
  (let [in-time?  #(or (nil? deadline) (< (inst-ms (now-fn)) deadline))
        following (fn [exchange] (when (in-time?) (next-page exchange)))
        pages!    (fn [request]
                    (async/iteration #(exchange! % opts)
                                     :initk request
                                     :kf    following
                                     :limit limit))
        exchanges (fn [request {:keys [value error]}]
                    (or value (failed request error)))]
    (async/then (async/all (mapv #(async/attempt (fn [] (pages! %))) batch))
                #(mapv exchanges batch %))))

(defn- with-round
  "The `thread` with the exchanges of a `round` of requests read into it at
  `at`, one request after another.

  The exchanges of a request that can't be read count as a failed
  request, so the thread is read on without them."
  [thread round at]
  (reduce (fn [thread exchanges]
            (try
              (with-exchanges thread exchanges at)
              (catch #?(:clj Throwable :cljs :default) e
                (let [request (:request (first exchanges))]
                  (with-exchanges thread (failed request e) at)))))
          thread
          round))

(defn changes
  "What changed between two readings of one thread, the `old` and the
  `new`, e.g. to tell the listener of new replies:

      {:added   [comment …]  ; the comments that `old` didn't have
       :edited  [comment …]  ; those with a later :updated
       :deleted [id …]}      ; those deleted since

  The comments are in the order of their :published time. The root of a
  thread about an episode, which has no content, isn't added."
  [old new]
  (let [before    (:comments old)
        live?     #(and (:read-at %) (not (:deleted? %)) (not (:error %)))
        published #(some-> (:published %) inst-ms)
        later?    (fn [{:keys [id updated]}]
                    (let [was (:updated (get before id))]
                      (and updated
                           (or (nil? was)
                               (< (inst-ms was) (inst-ms updated))))))
        comments  (filter live? (vals (:comments new)))]
    {:added   (->> comments
                   (remove #(or (contains? before (:id %))
                                (= (:root new) (:id %))))
                   (sort-by published)
                   vec)
     :edited  (->> comments
                   (filter #(and (live? (get before (:id %))) (later? %)))
                   (sort-by published)
                   vec)
     :deleted (->> (vals (:comments new))
                   (filter #(and (:deleted? %) (live? (get before (:id %)))))
                   (map :id)
                   sort
                   vec)}))

(defn fetch!
  "Read `thread` as far as the limits of `opts` allow, and give it with the
  :report of what went wrong. On the JVM it gives the thread, and in
  ClojureScript a promise of it.

  The `thread` is an unread thread, or one that an earlier fetch gave. A
  second fetch reads on from where the first stopped, and it asks again
  for what failed for a time. To read what's new, fetch the unread thread
  again, and compare the two readings with changes. The `opts` are these,
  with default-limits for the limits:

  - :appview, to read the threads of the AT Protocol from
  - :status-paths, in place of activitypub/status-paths
  - :relays, to read the threads of Nostr from, in place of
    comments.nostr/relays
  - :max-comments, the most posts to ask for in a fetch
  - :max-requests, after which no round starts
  - :max-seconds, after which no request starts
  - :concurrency, the number of requests under way at a time
  - :max-pages, the most pages of a collection in a round
  - :timeout-seconds, for each request
  - :max-bytes, of each answer
  - :allow-pred, which URLs a request can go to
  - :user-agent, as http/send! takes it
  - :send, in place of client/send! and nostr/send!
  - :now-fn, a function that gives the time, the clock by default

  The requests go out in rounds. The root post comes first, then the posts
  nearest to it, then the authors. The pages of a collection come one
  after another. A server that didn't answer in time, or that answered
  429 Too Many Requests, gets no more requests in the same fetch. The time
  that's left limits each request. Nothing throws.

  A thread also has limits of size that no option sets, such as 10,000
  comments in all. The report counts the replies that such a limit cut.

  The servers of a thread choose the URLs that this fetches. So each
  request carries a guard as its :allow-pred, which lets nothing go to a
  private network. To reach your own network, give (constantly true) as
  :allow-pred. A :send keeps to the :timeout-seconds and the :allow-pred
  of each request. A :send that signs its requests can also read the
  servers that answer only signed requests.

  A request to a relay has the :url of the relay and the :filters of
  NIP-01, and goes over a WebSocket. Your :send gets such requests too,
  and answers them as nostr/send! does."
  ([thread]
   (fetch! thread {}))
  ([thread opts]
   (let [{:keys [concurrency max-requests max-pages max-seconds now-fn]
          :as   opts}
         (merge default-limits
                {:send       send!
                 :allow-pred public?
                 :now-fn     time/now}
                opts)
         opts  (cond-> opts
                 max-seconds (assoc ::deadline (+ (inst-ms (now-fn))
                                                  (* 1000 max-seconds))))
         start (resumed thread)
         batch (fn [thread sent]
                 (when (or (not max-seconds)
                           (< (inst-ms (now-fn)) (::deadline opts)))
                   (let [n (min (max 1 concurrency) (- max-requests sent))]
                     (seq (take n (requests thread opts))))))
         limit (fn [b sent]
                 (max 1 (min max-pages (quot (- max-requests sent) (count b)))))
         ;; the thread after one more round, with the requests that went out
         ;; so far, or nil when the thread needs no round
         round (fn [[thread sent]]
                 (when-let [b (batch thread sent)]
                   (async/then (send-round! b (limit b sent) opts)
                               (fn [exchanges]
                                 (let [n (count (apply concat exchanges))]
                                   [(with-round thread exchanges (now-fn))
                                    (+ sent n)])))))]
     (async/then (async/iteration round :initk [start 0] :vf first)
                 (fn [threads]
                   (let [read (or (peek threads) start)]
                     (assoc read :report (report read opts))))))))

(comment
  (def interact
    {:protocol "activitypub"
     :uri      "https://example.social/users/ann/statuses/1"})

  (readable? interact)
  (readable? {:protocol "atproto" :uri "at://did:plc:ann/app.bsky.feed.post/1"})
  (requests interact)
  (map :id (report {:protocol "matrix"
                    :uri      "https://matrix.to/#/#podcast:example.org"}))

  ;; a thread that a fetch read: the root, a reply, and a reply that the
  ;; author of the root hid
  (def read-thread
    (let [at #inst "2026-09-29T12:00:00Z"]
      (assoc interact
             :read-at  at
             :root     "a"
             :authors  {"ann" {:id "ann" :name "Ann"}}
             :comments {"a" {:id "a" :author "ann" :read-at at}
                        "b" {:id          "b"
                             :author      "bob"
                             :in-reply-to "a"
                             :read-at     at}
                        "c" {:id          "c"
                             :author      "ann"
                             :in-reply-to "a"
                             :hidden?     true
                             :read-at     at}})))

  (tree read-thread)
  (tree read-thread {:hidden? true})

  ;; a fetch that sends no request to the network
  (fetch! interact
          {:send (fn [request] {:status 404 :url (:url request) :headers {}})})
  #_.)

#?(:clj
   (comment
     (def dave
       "https://podcastindex.social/@dave/117294750060235480")

     (def fetched
       (fetch! {:protocol "activitypub" :uri dave}))

     (:report fetched)
     (tree fetched)

     (def post
       (str "at://did:plc:pzznkrzajsaqev3ffamg3jnr"
            "/app.bsky.feed.post/3mvzl4tmue22y"))

     (fetch! {:protocol "atproto" :uri post}
             {:appview "https://public.api.bsky.app"})
     #_.))
