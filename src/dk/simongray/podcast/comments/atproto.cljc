(ns dk.simongray.podcast.comments.atproto
  "Threads of comments on the AT Protocol, the protocol of Bluesky, read
  from an AppView for dk.simongray.podcast.comments.

  An AppView indexes the network. It gives the thread below a post in one
  call, app.bsky.feed.getPostThread, without an account. The labels that
  the protocol defines apply:

  - a reply whose author asks apps to show it only to signed-in users is
    left out
  - a reply that a moderation service hid is left out
  - a reply with a warning is :sensitive?
  - a reply that the author of the root post hid is :hidden?

  The protocol gives no web page for a post. The at-uri function gives the
  parts of its at URI, from which you can build a link to the web app of
  your choice."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

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

(defn ^:no-doc post-uri?
  "Whether `s` is the at URI of a post."
  [s]
  (let [{:keys [collection rkey]} (at-uri s)]
    (and (= "app.bsky.feed.post" collection)
         (some? rkey))))

(defn- thread-request
  "The request to the AppView at `appview` for the thread below the post
  `uri`: ten levels of replies, and none of the posts above it."
  [appview uri]
  (let [root  (str/replace appview #"/+$" "")
        query (http/query-string {"uri" uri "depth" 10 "parentHeight" 0})]
    {:method        :get
     :url           (str root "/xrpc/app.bsky.feed.getPostThread?" query)
     :headers       {"Accept" "application/json"}
     ::comments/for [:thread uri]}))

;; AT Protocol, Labels. The values are those of the lexicon
;; com.atproto.label.defs, as of 2026-09-30.
(defn ^:no-doc labels
  "The labels that apply to the AT Protocol post `view`: those of the post
  and those of the account of its author, without the labels that are
  negated."
  [view]
  (let [own     (common/values view "labels")
        account (common/values (get view "author") "labels")]
    (remove #(get % "neg")
            (filter map? (concat own account)))))

(defn- withheld?
  "Whether the AT Protocol post `view` is kept from a reader without an
  account. Its author or the post asks so with the label
  !no-unauthenticated, or a moderation service hid it with !hide."
  [view]
  (let [did (get-in view ["author" "did"])]
    (boolean (some (fn [{:strs [val src]}]
                     (or (= "!no-unauthenticated" val)
                         (and (= "!hide" val) (not= src did))))
                   (labels view)))))

(defn- warnings
  "The labels of the AT Protocol post `view` that put it behind a warning.

  They're the labels of adult content and graphic media, and !warn when a
  moderation service set it, since clients ignore it from the author. A
  label on the account of the author counts as a label on each of its
  posts."
  [view]
  (let [did       (get-in view ["author" "did"])
        sensitive #{"porn" "sexual" "nudity" "graphic-media"}]
    (set (for [{:strs [val src]} (labels view)
               :when (or (contains? sensitive val)
                         (and (= "!warn" val) (not= src did)))]
           val))))

(defn- feature
  "What `facet` makes of the text that it marks, by the first of its
  features that podcast-clj knows: a link, a mention of a profile by its
  DID, or a hashtag. The lexicon app.bsky.richtext.facet defines them."
  [facet]
  (some (fn [f]
          (case (get f "$type")
            "app.bsky.richtext.facet#link"
            (when-let [url (common/web-url (get f "uri"))]
              {:type :link :url url})

            "app.bsky.richtext.facet#mention"
            (when (string? (get f "did"))
              {:type :mention :id (get f "did")})

            "app.bsky.richtext.facet#tag"
            (when (string? (get f "tag"))
              {:type :hashtag :tag (get f "tag")})

            nil))
        (filter map? (common/values facet "features"))))

(defn- marks
  "What the `facets` of a post mark in its text of `n` bytes, in order, as
  triples of a start, an end and a part, counted in bytes.

  A facet that overlaps the one before it is left out, as the official
  clients do."
  [facets n]
  (->> (for [facet (filter map? facets)
             :let  [{:strs [byteStart byteEnd]} (get facet "index")
                    start (json/whole byteStart)
                    end   (json/whole byteEnd)
                    part  (feature facet)]
             :when (and start
                        end
                        part
                        (<= 0 start)
                        (< start end)
                        (<= end n))]
         [start end part])
       (sort-by first)
       (reduce (fn [kept [start :as mark]]
                 (let [reach (or (second (peek kept)) 0)]
                   (if (< start reach) kept (conj kept mark))))
               [])))

(defn parts
  "The parts of the content of `record`, the record of an AT Protocol post
  with its \"text\" and its \"facets\", in their order. Each part is a map
  with its :type and the :text that the post shows for it:

      {:type :text :text \"/cc \"}
      {:type :link :text \"atproto.com/blog\" :url \"https://atproto.com/blog\"}
      {:type :mention :text \"@npmx.dev\" :id \"did:plc:u5zp…\"}
      {:type :hashtag :text \"#podcast\" :tag \"podcast\"}

  A facet counts UTF-8 bytes, so the text is cut as bytes."
  [record]
  (let [text (when (map? record) (get record "text"))]
    (if-not (and (string? text) (not (str/blank? text)))
      []
      (let [b      (bytes/encode text)
            n      (bytes/length b)
            cut    #(bytes/decode b %1 %2 :utf8)
            marked (marks (common/values record "facets") n)
            pieces (fn [[start end part] from]
                     [{:type :text :text (cut from start)}
                      (assoc part :text (cut start end))])
            tail   {:type :text :text (cut (or (second (last marked)) 0) n)}]
        (->> (concat (mapcat pieces marked (cons 0 (map second marked)))
                     [tail])
             (remove #(= "" (:text %)))
             vec)))))

(defn hiccup
  "The content of `record`, the record of an AT Protocol post, as Hiccup by
  `opts`: one paragraph, with a break for each line break of the text.
  The result is a sequence of nodes to put inside a parent element.

  A link of the post is a link. The :link of `opts` is a function of a
  part of parts. It gives the URL that a mention or a hashtag links to,
  or nil for no link. The protocol gives no web page for a profile, so you
  choose the web app that shows one."
  ([record]
   (hiccup record {}))
  ([record opts]
   {:pre [(map? opts)]}
   (let [node (fn [{:keys [type text url] :as part}]
                (case type
                  :text (interpose [:br {}] (str/split text #"\n" -1))
                  :link [(common/user-link url text)]
                  [(common/linked part text opts)]))]
     (if-let [ps (seq (parts record))]
       (list (into [:p {}] (comp (mapcat node) (remove #{""})) ps))
       ()))))

(defn ^:no-doc post-html
  "The content of the AT Protocol `record` as sanitized HTML, as hiccup
  gives it, or nil for a post without text. A mention and a hashtag have
  no link."
  [record]
  (when-let [paragraphs (seq (hiccup record))]
    (html/emit (html/sanitize paragraphs))))

(defn- medium
  "The attachment of the `kind` :image or :video that the item `x` of an
  embed view gives by its properties `url` and `preview`, or nil when it
  has no web URL."
  [kind x url preview]
  (when-let [url (common/web-url (get x url))]
    (let [ratio (get x "aspectRatio")
          w     (common/dimension (get ratio "width"))
          h     (common/dimension (get ratio "height"))]
      (common/present {:kind         kind
                       :url          url
                       :preview      (common/web-url (get x preview))
                       :alt          (common/first-text (get x "alt"))
                       :aspect-ratio (when (and w h) [w h])}))))

(defn- embed-attachments
  "The media of the `embed` view of an AT Protocol post, each with the
  ratio of its width to its height. They're the images, the video, or the
  media beside the post that it quotes."
  [embed]
  (case (get embed "$type")
    "app.bsky.embed.images#view"
    (vec (keep #(medium :image % "fullsize" "thumb")
               (common/values embed "images")))

    "app.bsky.embed.gallery#view"
    (vec (keep #(medium :image % "fullsize" "thumbnail")
               (common/values embed "items")))

    "app.bsky.embed.video#view"
    (if-let [video (medium :video embed "playlist" "thumbnail")]
      [(assoc video :media-type "application/vnd.apple.mpegurl")]
      [])

    "app.bsky.embed.recordWithMedia#view"
    (embed-attachments (get embed "media"))

    []))

(defn- post-comment
  "The AT Protocol post `view` as a comment read at `at`, by `opts`.

  It's hidden when it's among the replies that the author of the root
  hid, the :hidden of `opts`. Its replies count as read when the view
  gives them, which :replies? tells."
  [view at {:keys [hidden replies?] :as opts}]
  (let [record    (get view "record")
        labels    (warnings view)
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
             :author      (get-in view ["author" "did"])
             :in-reply-to (get-in record ["reply" "parent" "uri"])
             :published   published
             :content     (post-html record)
             ::record     source
             :language    (common/first-text language)
             :sensitive?  (boolean (seq labels))
             :labels      labels
             :attachments (embed-attachments (get view "embed"))
             :hidden?     (contains? hidden (get view "uri"))
             :read-at     at}
            {::comments/reply-count (common/dimension (get view "replyCount"))
             ::comments/collection  (when replies? {:read-at at})}))))

(defn- profile-author
  "The AT Protocol profile `p` as an author read at `at`: its display name,
  its handle when the AppView verified it, and its avatar."
  [p at]
  (let [handle (common/first-text (get p "handle"))
        handle (when (not= "handle.invalid" handle) handle)]
    (common/present {:id      (get p "did")
                     :name    (common/first-text (get p "displayName") handle)
                     :handle  (some->> handle (str "@"))
                     :avatar  (common/web-url (get p "avatar"))
                     :read-at at})))

(defn- with-view
  "The `thread` with the posts of the AT Protocol thread `view` read at
  `at` below the post `parent`, `level` levels down from the post that was
  asked for. The replies in `hidden` are hidden.

  A post that's gone is a deleted comment. A withheld post is left out
  with the replies below it, and so is a post that the AppView doesn't
  give because of a block. The common/limits of the depth and of a thread
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

      (withheld? post)
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

(defn readable?
  "Whether requests reads the thread of `interact` with `opts`. Its URI
  must be the at URI of a post, and `opts` must name an :appview."
  [{:keys [uri] :as interact} opts]
  (and (post-uri? uri) (string? (:appview opts))))

(defn resumed
  "The AT Protocol `thread` as a new reading of it starts: unread again
  where a failure that can pass with time stopped the last reading.

  This is the root, and the replies below a post."
  [{:keys [comments] :as thread}]
  (let [unread #(cond-> %
                  (:retry? (:error (::comments/collection %)))
                  (dissoc ::comments/collection))]
    (cond-> (common/retried thread)
      comments (update :comments update-vals unread))))

(defn- reply-requests
  "The requests for the replies that the AT Protocol `thread` has no view
  of yet, below the levels that the AppView at `appview` gave, nearest
  the root first."
  [thread appview]
  (let [unread (for [{:keys [id] ::comments/keys [reply-count collection]}
                     (vals (:comments thread))
                     :when (and reply-count (not (:read-at collection)))]
                 (thread-request appview id))]
    (common/nearest-first (common/depths thread) unread)))

(defn requests
  "The requests that the AT Protocol `thread` needs next with `opts`, of
  those whose URLs `ask?` takes.

  They go to the :appview of `opts`. They ask for no more posts than the
  :max-comments of `opts` allows, counted from the last resumed.

  The root post comes first. Then come the posts nearest to it whose
  replies are below the levels that the AppView gives at once."
  [{:keys [uri read-at comments] ::comments/keys [held] :as thread}
   {:keys [appview max-comments] :as opts}
   ask?]
  (let [fresh (- (count (filter :read-at (vals comments))) (or held 0))]
    (->> (cond
           (not (string? appview))
           []

           (nil? read-at)
           (when (post-uri? uri)
             [(thread-request appview uri)])

           (< fresh max-comments)
           (reply-requests thread appview))
         (filter (comp ask? :url)))))

(defn with-exchanges
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

  (parts sample-record)
  (hiccup sample-record)
  (hiccup sample-record
          {:link (fn [{:keys [id]}] (str "https://bsky.app/profile/" id))})
  (post-html sample-record)
  #_.)
