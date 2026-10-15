(ns dk.simongray.podcast.comments.atproto
  "Threads of comments on the AT Protocol, the protocol of Bluesky, read
  from an AppView.

  An AppView indexes the network. It gives the thread below a post in one
  call, app.bsky.feed.getPostThread, without an account. The labels that
  the protocol defines apply:

  - a reply whose author asks apps to show it only to signed-in users is
    left out
  - a reply that a moderation service hid is left out
  - a reply with a warning is :sensitive?
  - a reply that the author of the root post hid is :hidden?

  A thread starts at the at URI of a post, or at the URL of its page on
  Bluesky's web app. Each comment and each author has the :url of its page
  there, and post-url and profile-url give the page on another web app."
  (:require [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.atproto.impl :as impl]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.url :as url]))

(defn at-uri
  "The parts of the at URI `s` as a map of :authority, :collection and
  :rkey, or nil for another string.

  An at URI has the form at://authority/collection/rkey. The authority is
  the DID or the handle of a repository, and the rkey is the record key."
  [s]
  (let [at-form #"at://([^/\s?#]+)(?:/([^/\s?#]+)(?:/([^/\s?#]+))?)?"
        found   (when (string? s) (re-matches at-form s))]
    (when-let [[_ authority collection rkey] found]
      (common/present {:authority  authority
                       :collection collection
                       :rkey       rkey}))))

(def web-origin
  "The origin of Bluesky's web app, whose links to posts name threads, and
  whose pages are the :url of each comment and author."
  "https://bsky.app")

(defn post-uri
  "The at URI of the post that `s` names, or nil for another string.

  The `s` is the at URI of a post, or the URL of its page on Bluesky's web
  app, https://bsky.app/profile/actor/post/rkey, whose actor is the handle
  or the DID of its author. An AppView reads an at URI with a handle, so
  the at URI of such a page has the actor as it is."
  [s]
  (if (= (url/origin web-origin) (url/origin s))
    (let [path           (str (:path (url/parts s)))
          [_ actor rkey] (re-matches #"/profile/([^/]+)/post/([^/]+)/?" path)]
      (when (and actor
                 (re-matches impl/actor-pattern actor)
                 (impl/rkey? rkey))
        (str "at://" actor "/app.bsky.feed.post/" rkey)))
    (let [{:keys [collection rkey]} (at-uri s)]
      (when (and (= "app.bsky.feed.post" collection) rkey)
        s))))

(defn profile-url
  "The URL of the page of the `actor`, a DID or a handle, on Bluesky's web
  app, or on the web app with the same paths at the :origin of `opts`.
  It's nil for another string."
  ([actor]
   (profile-url actor {}))
  ([actor {:keys [origin] :or {origin web-origin} :as opts}]
   (when (and (string? actor) (re-matches impl/actor-pattern actor))
     (str (url/without-trailing-slashes origin) "/profile/" actor))))

(defn post-url
  "The URL of the page of the post at the at URI `uri` on Bluesky's web
  app, or on the web app with the same paths at the :origin of `opts`.
  It's nil for another URI."
  ([uri]
   (post-url uri {}))
  ([uri opts]
   (let [{:keys [authority collection rkey]} (at-uri uri)]
     (when (and (= "app.bsky.feed.post" collection) (impl/rkey? rkey))
       (some-> (profile-url authority opts) (str "/post/" rkey))))))

(defn- post-comment
  "The AT Protocol post `view` as a comment read at `at`, by `opts`.

  It's hidden when it's among the replies that the author of the root
  hid, the :hidden of `opts`. Its replies count as read when the view
  gives them, which :replies? tells."
  [view at {:keys [hidden replies?] :as opts}]
  (let [record    (get view "record")
        labels    (impl/warnings view)
        created   (time/parse-date (get record "createdAt"))
        indexed   (time/parse-date (get view "indexedAt"))
        ;; the earlier of the two, as the AppView's documentation advises,
        ;; since the app of the author sets the first
        published (if (and created indexed)
                    (min-key inst-ms created indexed)
                    (or created indexed))
        source    (when (map? record) (select-keys record ["text" "facets"]))
        language  (first (common/values record "langs"))]
    (common/present
     (merge {:id          (get view "uri")
             :url         (post-url (get view "uri"))
             :author      (get-in view ["author" "did"])
             :in-reply-to (get-in record ["reply" "parent" "uri"])
             :published   published
             :content     (impl/post-html record)
             ::record     source
             :language    (common/first-text language)
             :sensitive?  (boolean (seq labels))
             :labels      labels
             :attachments (impl/embed-attachments (get view "embed"))
             ;; the post view of app.bsky.feed.defs
             :counts      (common/counts {:replies (get view "replyCount")
                                          :reposts (get view "repostCount")
                                          :likes   (get view "likeCount")
                                          :quotes  (get view "quoteCount")})
             :hidden?     (contains? hidden (get view "uri"))
             :read-at     at}
            {::comments/reply-count (common/dimension (get view "replyCount"))
             ::comments/collection  (when replies? {:read-at at})}))))

(defn- profile-author
  "The AT Protocol profile `p` as an author read at `at`: its display name,
  its handle when the AppView verified it, its page and its avatar."
  [p at]
  (let [handle (common/first-text (get p "handle"))
        handle (when (not= "handle.invalid" handle) handle)]
    (common/present {:id      (get p "did")
                     :name    (common/first-text (get p "displayName") handle)
                     :handle  (some->> handle (str "@"))
                     :url     (profile-url (get p "did"))
                     :avatar  (common/web-url (get p "avatar"))
                     :read-at at})))

(defn- with-view
  "The `thread` with the posts of the AT Protocol thread `view` read at
  `at` below the post `parent`, `level` levels down from the post that was
  asked for. The replies in `hidden` are hidden.

  A post that's gone is a deleted comment. A withheld post is left out
  with the replies below it, and so is a post that the AppView doesn't
  give because of a block. The size limits of the depth and of a thread
  apply, and the thread counts the posts that a limit cut."
  [thread view parent hidden at level]
  (let [post (get view "post")
        uri  (if (map? post) (get post "uri") (get view "uri"))]
    (cond
      (not (string? uri))
      thread

      (> level (:depth common/limits))
      (common/truncate thread :depth 1)

      (and (not (contains? (:comments thread) uri))
           (>= (count (:comments thread)) (:thread common/limits)))
      (common/truncate thread :thread 1)

      (true? (get view "notFound"))
      (let [deleted {:id uri :in-reply-to parent :deleted? true :read-at at}]
        (assoc-in thread [:comments uri] (common/present deleted)))

      (true? (get view "blocked"))
      (common/withhold thread uri)

      (not (map? post))
      thread

      (impl/withheld? post)
      (common/withhold thread uri)

      :else
      (let [replies (get view "replies")
            listed? (sequential? replies)
            author  (get post "author")
            did     (get author "did")
            opts    {:hidden hidden :replies? listed?}
            c       (common/guarded uri at #(post-comment post at opts))
            read    (cond-> (assoc-in thread [:comments uri] c)
                      (string? did)
                      (assoc-in [:authors did] (profile-author author at)))]
        (reduce #(with-view %1 %2 uri hidden at (inc level))
                read
                (when listed? replies))))))

(defn ^:no-doc readable?
  "Whether the thread of `interact` can be read with `opts`. Its URI must
  name a post, as post-uri reads it, and `opts` must name an :appview."
  [{:keys [uri] :as interact} opts]
  (and (some? (post-uri uri)) (string? (:appview opts))))

(defn ^:no-doc requests
  "The requests that the AT Protocol `thread` needs next with `opts`, of
  those whose URLs `ask-pred` takes.

  They go to the :appview of `opts`. They ask for no more posts than the
  :max-comments of `opts` allows, counted from the start of the reading.

  The root post comes first. Then come the posts nearest to it whose
  replies are below the levels that the AppView gives at once."
  [{:keys [uri read-at comments] ::comments/keys [held] :as thread}
   {:keys [appview max-comments] :as opts}
   ask-pred]
  (let [fresh (- (count (filter :read-at (vals comments))) (or held 0))]
    (->> (cond
           (not (string? appview))
           []

           (nil? read-at)
           (when-let [post (post-uri uri)]
             [(impl/thread-request appview post)])

           (< fresh max-comments)
           (impl/reply-requests thread appview))
         (filter (comp ask-pred :url)))))

(defn ^:no-doc with-exchanges
  "The AT Protocol `thread` with the `exchanges` of one of its requests
  read into it at `at`. There's one exchange for the request.

  A root that the AppView doesn't give is a problem of the root, and a
  post that's gone is a deleted comment."
  [{:keys [read-at] :as thread} [exchange :as exchanges] at]
  (let [{:keys [request response]} exchange
        url     (:url request)
        anchor  (second (::comments/for request))
        status  (:status response)
        doc     (common/document-of exchange)
        view    (get doc "thread")
        gone?   (or (and (= 400 status) (= "NotFound" (get doc "error")))
                    (true? (get view "notFound"))
                    (true? (get view "blocked")))
        problem (or (common/failure url response)
                    (when gone?
                      {:id     :comments/unreachable
                       :url    url
                       :reason "The post is gone or blocked"})
                    (when-not (map? (get view "post"))
                      {:id :comments/malformed :url url}))
        gate    (get-in doc ["threadgate" "record"])
        hidden  (set (common/values gate "hiddenReplies"))]
    (cond
      (nil? read-at)
      (if problem
        (let [message (get doc "message")]
          (assoc thread
                 :read-at at
                 :error   (cond-> problem message (assoc :reason message))))
        (-> thread
            (dissoc :error)
            (assoc :read-at at :root (get-in view ["post" "uri"]))
            (with-view view nil hidden at 0)))

      gone?
      (update-in thread [:comments anchor]
                 #(-> (select-keys % [:id :in-reply-to])
                      (assoc :deleted? true :read-at at)))

      problem
      (assoc-in thread [:comments anchor ::comments/collection]
                {:read-at at :error problem})

      :else
      (let [read     (with-view thread view nil hidden at 0)
            post     (get-in read [:comments anchor])
            covered? (get-in post [::comments/collection :read-at])]
        ;; a view that leaves out the replies of the post it was asked for
        ;; again would be asked for again and again
        (cond-> read
          (and post (not covered?))
          (assoc-in [:comments anchor ::comments/collection :read-at] at))))))

(comment
  (at-uri "at://did:plc:ann/app.bsky.feed.post/1")

  ;; the link that a feed pastes, as the at URI of its post, and back
  (post-uri "https://bsky.app/profile/ann.example/post/3k")
  ;; => "at://ann.example/app.bsky.feed.post/3k"
  (post-url "at://did:plc:ann/app.bsky.feed.post/3k")
  ;; => "https://bsky.app/profile/did:plc:ann/post/3k"

  (def root-uri "at://did:plc:ann/app.bsky.feed.post/1")

  (def sample-thread
    {:protocol "atproto" :uri root-uri})

  (def sample-opts
    {:appview "https://public.api.bsky.app" :max-comments 100})

  ;; the one request of an unread thread
  (def sample-request
    (first (requests sample-thread sample-opts (constantly true))))

  ;; the answer of an AppView: a post, and one reply below it
  (def sample-reply
    {"uri"    "at://did:plc:bob/app.bsky.feed.post/2"
     "author" {"did" "did:plc:bob" "handle" "bob.example"}
     "record" {"text"      "A good episode"
               "createdAt" "2026-09-19T01:00:00Z"
               "reply"     {"parent" {"uri" root-uri}}}})

  (def sample-answer
    {"thread"
     {"post"    {"uri"    root-uri
                 "author" {"did"         "did:plc:ann"
                           "handle"      "ann.example"
                           "displayName" "Ann"}
                 "record" {"text"      "What did you think?"
                           "createdAt" "2026-09-19T00:22:52Z"}}
      "replies" [{"post" sample-reply}]}})

  (def sample-read
    (with-exchanges sample-thread
                    [{:request  sample-request
                      :response {:status 200}
                      :document sample-answer}]
                    #inst "2026-09-29T12:00:00Z"))

  (vals (:comments sample-read))
  (:authors sample-read)

  ;; a facet counts bytes, and marks a link or a mention in the text
  (def sample-record
    {"text"   "See https://example.org, @bob.example"
     "facets" [{"index"    {"byteStart" 4 "byteEnd" 23}
                "features" [{"$type" "app.bsky.richtext.facet#link"
                             "uri"   "https://example.org"}]}
               {"index"    {"byteStart" 25 "byteEnd" 37}
                "features" [{"$type" "app.bsky.richtext.facet#mention"
                             "did"   "did:plc:bob"}]}]})

  (impl/parts sample-record)
  (impl/hiccup sample-record)
  (impl/hiccup sample-record
               {:link-fn (comp profile-url :id)})
  (impl/post-html sample-record)
  #_.)
