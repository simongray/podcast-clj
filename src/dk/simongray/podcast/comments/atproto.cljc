(ns dk.simongray.podcast.comments.atproto
  "Threads of comments on the AT Protocol, the protocol of Bluesky, read
  from an AppView for `comments`.

  An AppView indexes the network. It gives the thread below a post in one
  call, app.bsky.feed.getPostThread, without an account. The labels that
  the protocol defines apply. `with-exchanges` leaves out a reply whose
  author asks apps to show it only to signed-in users, and a reply that a
  moderation service hid. A reply with a warning is `:sensitive?`. A reply
  that the author of the root post hid is `:hidden?`.

  The protocol gives no web page for a post. `at-uri` gives the parts of
  its at URI, from which an app builds a link to the web app of its
  choice."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

(defn at-uri
  "The parts of the at URI `s`, `at://authority/collection/rkey`, as a map
  of `:authority`, `:collection` and `:rkey`. Nil for another string.

  The authority is the DID or the handle of a repository, and the rkey is
  the record key."
  [s]
  (when-let [[_ authority collection rkey] (when (string? s)
                                             (re-matches #"at://([^/\s?#]+)(?:/([^/\s?#]+)(?:/([^/\s?#]+))?)?" s))]
    (common/present {:authority authority :collection collection :rkey rkey})))

(defn ^:no-doc post-uri?
  "Whether `s` is the at URI of a post."
  [s]
  (let [{:keys [collection rkey]} (at-uri s)]
    (and (= "app.bsky.feed.post" collection) (some? rkey))))

(defn- thread-request
  "The request to the AppView at `appview` for the thread below the post
  `uri`: ten levels of replies, and none of the posts above it."
  [appview uri]
  {:method        :get
   :url           (str (str/replace appview #"/+$" "") "/xrpc/app.bsky.feed.getPostThread?"
                       (http/query-string {"uri" uri "depth" 10 "parentHeight" 0}))
   :headers       {"Accept" "application/json"}
   ::comments/for [:thread uri]})

;; AT Protocol, Labels. The values are those of the lexicon
;; com.atproto.label.defs, as of 2026-09-30.
(defn ^:no-doc labels
  "The labels that apply to the AT Protocol post `view`: those of the post
  and those of the account of its author, without the labels that are
  negated."
  [view]
  (remove #(get % "neg")
          (filter map? (concat (common/values view "labels") (common/values (get view "author") "labels")))))

(defn- withheld?
  "Whether the AT Protocol post `view` is kept from a reader without an
  account: its author or the post asks so with the label
  !no-unauthenticated, or a moderation service hid it with !hide."
  [view]
  (let [did (get-in view ["author" "did"])]
    (boolean (some (fn [{:strs [val src]}]
                     (or (= "!no-unauthenticated" val)
                         (and (= "!hide" val) (not= src did))))
                   (labels view)))))

(defn- warnings
  "The labels of the AT Protocol post `view` that put it behind a warning:
  those of adult content and graphic media, and !warn when a moderation
  service set it, since clients ignore it from the author. A label on the
  account of the author counts as a label on each of its posts."
  [view]
  (let [did (get-in view ["author" "did"])]
    (set (for [{:strs [val src]} (labels view)
               :when (or (contains? #{"porn" "sexual" "nudity" "graphic-media"} val)
                         (and (= "!warn" val) (not= src did)))]
           val))))

(defn- feature
  "What `facet` makes of the text that it marks, by the first of its
  features that podcast-clj knows. It is a link, a mention of a profile
  by its DID, or a hashtag. The lexicon app.bsky.richtext.facet defines
  the three."
  [facet]
  (some (fn [f]
          (case (get f "$type")
            "app.bsky.richtext.facet#link"    (when-let [url (common/web-url (get f "uri"))] {:type :link :url url})
            "app.bsky.richtext.facet#mention" (when (string? (get f "did")) {:type :mention :id (get f "did")})
            "app.bsky.richtext.facet#tag"     (when (string? (get f "tag")) {:type :hashtag :tag (get f "tag")})
            nil))
        (filter map? (common/values facet "features"))))

(defn- marks
  "What the `facets` of a post mark in its text of `n` bytes, as `[start
  end part]` counted in bytes, in their order. A facet that overlaps the
  one before it is left out, as the official clients do."
  [facets n]
  (->> (for [facet (filter map? facets)
             :let  [{:strs [byteStart byteEnd]} (get facet "index")
                    start (json/whole byteStart)
                    end   (json/whole byteEnd)
                    part  (feature facet)]
             :when (and start end part (<= 0 start) (< start end) (<= end n))]
         [start end part])
       (sort-by first)
       (reduce (fn [kept [start :as mark]]
                 (if (< start (or (second (peek kept)) 0)) kept (conj kept mark)))
               [])))

(defn parts
  "The parts of the content of `record`, the record of an AT Protocol post
  with its \"text\" and its \"facets\", in their order. Each part is a map
  with its `:type` and the `:text` that the post shows for it:

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
            cut    #(bytes/decode b %1 %2 :utf8)
            marked (marks (common/values record "facets") (bytes/length b))]
        (->> (concat (mapcat (fn [[start end part] from]
                               [{:type :text :text (cut from start)} (assoc part :text (cut start end))])
                             marked
                             (cons 0 (map second marked)))
                     [{:type :text :text (cut (or (second (last marked)) 0) (bytes/length b))}])
             (remove #(= "" (:text %)))
             vec)))))

(defn hiccup
  "The content of `record`, the record of an AT Protocol post, as Hiccup:
  one paragraph, with a break for each line break of the text. The result
  is a sequence of nodes to put inside a parent element.

  A link of the post is a link. `:link` in `opts` is a function of a part
  of `parts`. It gives the URL that a mention or a hashtag links to, or
  nil for no link. The protocol gives no web page for a profile, so the
  app chooses the web app that shows one."
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
  "The content of the AT Protocol `record` as sanitized HTML, as `hiccup`
  gives it. A mention and a hashtag have no link. Nil for a post without
  text."
  [record]
  (when-let [paragraphs (seq (hiccup record))]
    (html/emit (html/sanitize paragraphs))))

(defn- embed-attachments
  "The media of the `embed` view of an AT Protocol post: its images, its
  video, or the media beside the post that it quotes, each with the ratio
  of its width to its height."
  [embed]
  (let [media (fn [kind x url preview]
                (when-let [url (common/web-url (get x url))]
                  (let [w (common/dimension (get-in x ["aspectRatio" "width"]))
                        h (common/dimension (get-in x ["aspectRatio" "height"]))]
                    (common/present {:kind         kind
                                     :url          url
                                     :preview      (common/web-url (get x preview))
                                     :alt          (common/first-text (get x "alt"))
                                     :aspect-ratio (when (and w h) [w h])}))))]
    (case (get embed "$type")
      "app.bsky.embed.images#view"          (vec (keep #(media :image % "fullsize" "thumb") (common/values embed "images")))
      "app.bsky.embed.gallery#view"         (vec (keep #(media :image % "fullsize" "thumbnail") (common/values embed "items")))
      "app.bsky.embed.video#view"           (if-let [video (media :video embed "playlist" "thumbnail")]
                                              [(assoc video :media-type "application/vnd.apple.mpegurl")]
                                              [])
      "app.bsky.embed.recordWithMedia#view" (embed-attachments (get embed "media"))
      [])))

(defn- post-comment
  "The AT Protocol post `view` as a comment read at `at`. It is hidden when
  it is among the replies that the author of the root hid, the `:hidden`
  of `opts`. Its replies count as read when the view gives them, which
  `:replies?` tells."
  [view at {:keys [hidden replies?]}]
  (let [record  (get view "record")
        labels  (warnings view)
        created (time/parse-date (get record "createdAt"))
        indexed (time/parse-date (get view "indexedAt"))]
    (common/present {:id                    (get view "uri")
                     :author                (get-in view ["author" "did"])
                     :in-reply-to           (get-in record ["reply" "parent" "uri"])
                     ;; the earlier of the two, as the AppView's documentation advises,
                     ;; since the app of the author sets the first
                     :published             (if (and created indexed) (min-key inst-ms created indexed) (or created indexed))
                     :content               (post-html record)
                     ::record               (when (map? record) (select-keys record ["text" "facets"]))
                     :language              (common/first-text (first (common/values record "langs")))
                     :sensitive?            (boolean (seq labels))
                     :labels                labels
                     :attachments           (embed-attachments (get view "embed"))
                     :hidden?               (contains? hidden (get view "uri"))
                     :read-at               at
                     ::comments/reply-count (common/dimension (get view "replyCount"))
                     ::comments/collection  (when replies? {:read-at at})})))

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
  "`thread` with the posts of the AT Protocol thread `view` read at `at`
  below the post `parent`, the replies in `hidden` hidden, and `level`
  levels down from the post that was asked for.

  A post that is gone is a deleted comment. A withheld post is left out
  with the replies below it, and so is a post that the AppView does not
  give because of a block. The `common/limits` of the depth and of a thread
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
      (assoc-in thread [:comments uri] (common/present {:id uri :in-reply-to parent :deleted? true :read-at at}))

      (true? (get view "blocked"))
      (common/withhold thread uri)

      (not (map? post))
      thread

      (withheld? post)
      (common/withhold thread uri)

      :else
      (let [replies (get view "replies")
            author  (get post "author")
            c       (common/guarded uri at #(post-comment post at {:hidden hidden :replies? (sequential? replies)}))]
        (reduce #(with-view %1 %2 uri hidden at (inc level))
                (cond-> (assoc-in thread [:comments uri] c)
                  (string? (get author "did")) (assoc-in [:authors (get author "did")] (profile-author author at)))
                (when (sequential? replies) replies))))))

(defn readable?
  "Whether the thread of `interact` is one that `requests` reads with
  `opts`. Its URI must be the at URI of a post, and `opts` must name an
  `:appview`."
  [{:keys [uri]} opts]
  (and (post-uri? uri) (string? (:appview opts))))

(defn resumed
  "The AT Protocol `thread` as a new reading of it starts: unread again
  where a failure that can pass with time stopped the last reading.

  This is the root, and the replies below a post."
  [{:keys [comments] :as thread}]
  (cond-> (common/retried thread)
    comments (update :comments update-vals #(cond-> %
                                              (:retry? (:error (::comments/collection %)))
                                              (dissoc ::comments/collection)))))

(defn requests
  "The requests that the AT Protocol `thread` needs next, of those whose
  URLs `ask?` takes.

  They go to the `:appview` of `opts`. They ask for no more posts than
  the `:max-comments` of `opts` allows, counted from the last `resumed`.

  The root post comes first. Then come the posts nearest to it whose
  replies are below the levels that the AppView gives at once."
  [{:keys [uri read-at comments] ::comments/keys [held] :as thread} {:keys [appview max-comments]} ask?]
  (->> (cond
         (not (string? appview)) []
         (nil? read-at)          (when (post-uri? uri) [(thread-request appview uri)])
         :else                   (let [all (vals comments)]
                                   (when (< (- (count (filter :read-at all)) (or held 0)) max-comments)
                                     (common/nearest-first (common/depths thread)
                                                           (for [{:keys [id] ::comments/keys [reply-count collection]} all
                                                                 :when (and reply-count (not (:read-at collection)))]
                                                             (thread-request appview id))))))
       (filter (comp ask? :url))))

(defn with-exchanges
  "The AT Protocol `thread` with the `exchanges` of one of its `requests`
  read into it at `at`: the one exchange of the request.

  A root that the AppView does not give, and a post that is gone, are
  problems of the root and a deleted comment."
  [{:keys [read-at] :as thread} [{{:keys [url] [_ anchor] ::comments/for} :request {:keys [status] :as response} :response :as exchange}] at]
  (let [doc     (common/document-of exchange)
        view    (get doc "thread")
        gone?   (or (and (= 400 status) (= "NotFound" (get doc "error")))
                    (true? (get view "notFound"))
                    (true? (get view "blocked")))
        problem (or (common/failure url response)
                    (when gone? {:id :comments/unreachable :url url :reason "The post is gone or blocked"})
                    (when-not (map? (get view "post")) {:id :comments/malformed :url url}))
        hidden  (set (common/values (get-in doc ["threadgate" "record"]) "hiddenReplies"))]
    (cond
      (nil? read-at)
      (if problem
        (assoc thread :read-at at :error (cond-> problem (get doc "message") (assoc :reason (get doc "message"))))
        (-> thread
            (dissoc :error)
            (assoc :read-at at :root (get-in view ["post" "uri"]))
            (with-view view nil hidden at 0)))

      gone?
      (update-in thread [:comments anchor] #(-> (select-keys % [:id :in-reply-to]) (assoc :deleted? true :read-at at)))

      problem
      (assoc-in thread [:comments anchor ::comments/collection] {:read-at at :error problem})

      :else
      (let [read (with-view thread view nil hidden at 0)]
        ;; a view that leaves out the replies of the post it was asked for
        ;; again would be asked for again and again
        (cond-> read
          (and (get-in read [:comments anchor]) (not (get-in read [:comments anchor ::comments/collection :read-at])))
          (assoc-in [:comments anchor ::comments/collection :read-at] at))))))

(comment
  (at-uri "at://did:plc:ann/app.bsky.feed.post/1")

  (def sample-thread
    {:protocol "atproto" :uri "at://did:plc:ann/app.bsky.feed.post/1"})

  (def sample-opts
    {:appview "https://public.api.bsky.app" :max-comments 100})

  ;; the one request of an unread thread
  (requests sample-thread sample-opts (constantly true))

  ;; the answer of an AppView: a post, and one reply below it
  (def sample-answer
    {"thread" {"post"    {"uri"    "at://did:plc:ann/app.bsky.feed.post/1"
                          "author" {"did" "did:plc:ann" "handle" "ann.example" "displayName" "Ann"}
                          "record" {"text" "What did you think?" "createdAt" "2026-09-19T00:22:52Z"}}
               "replies" [{"post" {"uri"    "at://did:plc:bob/app.bsky.feed.post/2"
                                   "author" {"did" "did:plc:bob" "handle" "bob.example"}
                                   "record" {"text"      "A good episode"
                                             "createdAt" "2026-09-19T01:00:00Z"
                                             "reply"     {"parent" {"uri" "at://did:plc:ann/app.bsky.feed.post/1"}}}}}]}})

  (def sample-read
    (with-exchanges sample-thread
                    [{:request  (first (requests sample-thread sample-opts (constantly true)))
                      :response {:status 200}
                      :document sample-answer}]
                    #inst "2026-09-29T12:00:00Z"))

  (vals (:comments sample-read))
  (:authors sample-read)

  ;; a facet counts bytes, and marks a link or a mention in the text
  (def sample-record
    {"text"   "See https://example.org, @bob.example"
     "facets" [{"index"    {"byteStart" 4 "byteEnd" 23}
                "features" [{"$type" "app.bsky.richtext.facet#link" "uri" "https://example.org"}]}
               {"index"    {"byteStart" 25 "byteEnd" 37}
                "features" [{"$type" "app.bsky.richtext.facet#mention" "did" "did:plc:bob"}]}]})

  (parts sample-record)
  (hiccup sample-record)
  (hiccup sample-record {:link (fn [{:keys [id]}] (str "https://bsky.app/profile/" id))})
  (post-html sample-record)
  #_.)
