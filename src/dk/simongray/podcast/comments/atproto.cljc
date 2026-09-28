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
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.time :as time]))

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

(defn- withheld?
  "Whether the AT Protocol post `view` is kept from a reader without an
  account: its author or the post asks so with the label
  !no-unauthenticated, or a moderation service hid it with !hide."
  [view]
  (let [did (get-in view ["author" "did"])]
    (boolean (some (fn [{:strs [val src neg]}]
                     (and (not neg)
                          (or (= "!no-unauthenticated" val)
                              (and (= "!hide" val) (not= src did)))))
                   (filter map? (concat (common/values view "labels") (common/values (get view "author") "labels")))))))

(defn- warnings
  "The labels of the AT Protocol post `view` that put it behind a warning:
  those of adult content and graphic media, and !warn when a moderation
  service set it, since clients ignore it from the author."
  [view]
  (let [did (get-in view ["author" "did"])]
    (set (for [{:strs [val src neg]} (filter map? (common/values view "labels"))
               :when (and (not neg)
                          (or (contains? #{"porn" "sexual" "nudity" "graphic-media"} val)
                              (and (= "!warn" val) (not= src did))))]
           val))))

(defn- links
  "The links that the `facets` of a post mark in its text of `n` bytes, as
  `[start end uri]` counted in bytes, in their order; a link that overlaps
  the one before it is left out, as the official clients do."
  [facets n]
  (->> (for [facet (filter map? facets)
             :let  [{:strs [byteStart byteEnd]} (get facet "index")
                    start (common/whole byteStart)
                    end   (common/whole byteEnd)
                    uri   (some #(when (= "app.bsky.richtext.facet#link" (get % "$type")) (common/web-url (get % "uri")))
                                (filter map? (common/values facet "features")))]
             :when (and start end uri (<= 0 start) (< start end) (<= end n))]
         [start end uri])
       (sort-by first)
       (reduce (fn [kept [start :as link]]
                 (if (< start (or (second (peek kept)) 0)) kept (conj kept link)))
               [])))

(defn- segments
  "The text of the UTF-8 bytes `b` as Hiccup: the parts that `links` names
  as links, and line breaks as breaks."
  [b links]
  (let [text  #(bytes/decode b %1 %2 :utf8)
        parts (concat (mapcat (fn [[start end uri] from] [(text from start) [:a {:href uri} (text start end)]])
                              links
                              (cons 0 (map second links)))
                      [(text (or (second (last links)) 0) (bytes/length b))])]
    (mapcat #(if (string? %) (interpose [:br {}] (str/split % #"\n" -1)) [%]) parts)))

(defn ^:no-doc post-html
  "The `text` of an AT Protocol post as sanitized HTML, the text that each
  link of its `facets` marks as a link and its line breaks as breaks.

  A facet counts UTF-8 bytes, so the text is cut as bytes."
  [text facets]
  (when (and (string? text) (not (str/blank? text)))
    (let [b (bytes/encode text)]
      (html/emit (common/fit-links (html/sanitize [(into [:p {}] (segments b (links facets (bytes/length b))))]) nil)))))

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
  "The AT Protocol post `view` as a comment read at `at`: hidden when it is
  among the replies that the author of the root `hidden`, and with its
  replies read when the view gives them, `replies?`."
  [view hidden replies? at]
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
                     :content               (post-html (get record "text") (common/values record "facets"))
                     :language              (common/first-text (first (common/values record "langs")))
                     :sensitive?            (boolean (seq labels))
                     :labels                labels
                     :attachments           (embed-attachments (get view "embed"))
                     :hidden?               (contains? hidden (get view "uri"))
                     :read-at               at
                     ::comments/reply-count (when-let [n (common/whole (get view "replyCount"))] (when (pos? n) n))
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
  below the post `parent`, the replies in `hidden` hidden, 64 levels down
  from `level` at most, and 10,000 comments in all at most.

  A post that is gone is a deleted comment, and a withheld one is left out
  with the replies below it."
  [thread view parent hidden at level]
  (let [post (get view "post")
        uri  (if (map? post) (get post "uri") (get view "uri"))]
    (cond
      (or (> level 64) (not (string? uri)) (>= (count (:comments thread)) 10000))
      thread

      (true? (get view "notFound"))
      (assoc-in thread [:comments uri] (common/present {:id uri :in-reply-to parent :deleted? true :read-at at}))

      (not (map? post))
      thread

      (withheld? post)
      (common/withhold thread uri)

      :else
      (let [replies (get view "replies")
            author  (get post "author")]
        (reduce #(with-view %1 %2 uri hidden at (inc level))
                (cond-> (assoc-in thread [:comments uri] (post-comment post hidden (sequential? replies) at))
                  (string? (get author "did")) (assoc-in [:authors (get author "did")] (profile-author author at)))
                (when (sequential? replies) replies))))))

(defn readable?
  "Whether the thread of `interact` is one that `requests` reads with
  `opts`. Its URI must be the at URI of a post, and `opts` must name an
  `:appview`."
  [{:keys [uri]} opts]
  (and (post-uri? uri) (string? (:appview opts))))

(defn requests
  "The requests that the AT Protocol `thread` needs next, of those whose
  URLs `ask?` takes. They go to the `:appview` of `opts`, and they ask for
  no more posts than the `:max-comments` of `opts` allows.

  The root post comes first. Then come the posts nearest to it whose
  replies are below the levels that the AppView gives at once."
  [{:keys [uri read-at comments] :as thread} {:keys [appview max-comments]} ask?]
  (->> (cond
         (not (string? appview)) []
         (nil? read-at)          (when (post-uri? uri) [(thread-request appview uri)])
         :else                   (let [all (vals comments)]
                                   (when (< (count (filter :read-at all)) max-comments)
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
