(ns dk.simongray.podcast.comments.activitypub
  "Threads of comments over ActivityPub, the protocol of Mastodon, PeerTube
  and the podcast host Castopod, read for `comments`.

  Each post is a JSON document at its own URL. A root post can start a
  conversation and name a collection of all its posts. This collection is
  the `context` of FEP-f228, or the `comments` of a PeerTube video.
  `requests` asks for that collection, or else for the `replies`
  collection of each post, down the tree. It asks for each post that a
  collection names by URL at the post's own server, and for each author
  too. A server speaks only for its own posts and authors, as FEP-fe34
  asks. `with-exchanges` leaves out a post that is not public.

  Some servers answer only signed requests, and Mastodon lets no page on
  another site read its posts. For such a root post, `requests` asks the
  Mastodon API of its server for the thread. Many servers answer this API.
  The API speaks only for its own server too."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.time :as time]))

(defn ^:no-doc id-of
  "The id of `x`, an object or the IRI that names one."
  [x]
  (let [id (if (map? x) (get x "id") x)]
    (when (string? id) id)))

(defn ^:no-doc href
  "The URL that `x` names: a string, the href of a Link, the url of an
  object, or the first of these in a list."
  [x]
  (cond
    (string? x)     x
    (map? x)        (href (or (get x "href") (get x "url")))
    (sequential? x) (some href x)))

(defn ^:no-doc document-request
  "The request for the ActivityPub document at `url`, `for` what it asks."
  [url for]
  {:method        :get
   :url           url
   :headers       {"Accept" #?(:clj  "application/activity+json, application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""
                               ;; the quotes of the profile make a browser ask the server
                               ;; first, as CORS has it, and servers answer the plain type too
                               :cljs "application/activity+json")}
   ::comments/for for})

(defn ^:no-doc own-url
  "`url` when it is an http or https URL on the server of `id`. A server
  speaks only for itself, so a post or an author links to no page on
  another server."
  [url id]
  (when (http/same-origin? url id)
    (common/web-url url)))

(defn ^:no-doc web-page
  "The web page of the ActivityPub object `o`: the first url that it names
  on the server of its id, one of the type text/html before the others, or
  else its id."
  [o]
  (let [id   (id-of o)
        urls (common/values o "url")
        html (keep #(when (= "text/html" (get % "mediaType")) (href %)) (filter map? urls))]
    (some #(own-url % id) (concat html (keep href urls) [id]))))

(defn ^:no-doc attributed
  "The id of the author of the ActivityPub object `o`: the first person
  that its attributedTo names, or else the first of any kind, since a
  PeerTube video names its account and its channel."
  [o]
  (let [xs (common/values o "attributedTo")]
    (id-of (or (first (filter #(= "Person" (get % "type")) (filter map? xs)))
               (first xs)))))

(defn ^:no-doc trusted?
  "Whether the ActivityPub object `o`, which came from `url`, speaks for
  its own server: its id and its author are there, as FEP-fe34 asks."
  [o url]
  (and (http/same-origin? (id-of o) url)
       (let [author (attributed o)]
         (or (nil? author) (http/same-origin? author url)))))

(defn ^:no-doc public?
  "Whether the ActivityPub object `o` is for anyone to see: the Public
  collection is among its recipients, or it names no recipients at all, as
  a few servers write their posts."
  [o]
  (let [recipients (map id-of (mapcat #(common/values o %) ["to" "cc" "bto" "bcc"]))]
    (or (empty? recipients)
        (boolean (some #{"https://www.w3.org/ns/activitystreams#Public" "as:Public" "Public"} recipients)))))

(defn ^:no-doc post-of
  "The post in the ActivityPub document `doc`: the object itself, or the
  object of the Create activity that it is. Nil for an actor, a
  collection, another activity, and an object without an id."
  [doc]
  (let [o (if (= "Create" (get doc "type")) (get doc "object") doc)]
    (when (and (map? o)
               (id-of o)
               (not (contains? #{"Person" "Group" "Service" "Application" "Organization" "Collection"
                                 "OrderedCollection" "CollectionPage" "OrderedCollectionPage" "Accept" "Add"
                                 "Announce" "Arrive" "Block" "Create" "Delete" "Dislike" "Flag" "Follow" "Ignore"
                                 "Invite" "Join" "Leave" "Like" "Listen" "Move" "Offer" "Read" "Reject" "Remove"
                                 "TentativeAccept" "TentativeReject" "Travel" "Undo" "Update" "View"}
                               (get o "type"))))
      o)))

(defn- answer-of
  "What the `exchange` of a request for an ActivityPub document tells: the
  `:url` asked for, the `:final` one that answered, the `:status`, the
  `:problem` that kept the answer from being read if one did, and the
  `:document`."
  [{:keys [request response] :as exchange}]
  (let [url     (:url request)
        problem (common/failure url response)]
    {:url      url
     :final    (or (:url response) url)
     :status   (:status response)
     :problem  problem
     :document (when-not problem (common/document-of exchange))}))

(defn ^:no-doc post-problem
  "The problem with the post in the `answer` to the request for `url`: that
  the request failed, that the answer holds no post, or that the post
  speaks for another server than the one that sent it; nil when there is
  none."
  [{:keys [problem document final]} url]
  (let [o (post-of document)]
    (or problem
        (when-not o {:id :comments/malformed :url url})
        (when-not (trusted? o final) {:id :comments/untrusted :url url}))))

(defn- content-of
  "The language and the content of the ActivityPub object `o`, the content
  in HTML that `common/safe-html` makes of it.

  The language is the one that its contentMap names. Text of another media
  type than HTML, as the Markdown of PeerTube, keeps its line breaks, as
  breaks when it holds markup as well."
  [o]
  (let [by-language  (get o "contentMap")
        [language s] (if (string? (get o "content"))
                       [(when (map? by-language) (first (keys by-language))) (get o "content")]
                       (when (map? by-language) (first (filter (comp string? val) by-language))))]
    [language (common/safe-html (if (or (contains? #{nil "text/html"} (get o "mediaType"))
                                        (not (re-find html/markup (str s))))
                                  s
                                  (str/replace s #"\r?\n" "<br>"))
                                (id-of o))]))

(defn- warning
  "The content warning in the summary `s` of an ActivityPub object, as
  text; nil for none, as for the zero-width space that Misskey writes for
  an empty one."
  [s]
  (when (string? s)
    (common/first-text (str/replace (html/text s) (str (char 0x200B)) ""))))

(defn- media-kind
  "The kind of media that the `media-type` names, or else the
  ActivityStreams `type`: :image, :video, :audio, or :file for any other."
  [media-type type]
  (or (when (string? media-type) (#{:image :video :audio} (keyword (first (str/split media-type #"/")))))
      (get {"Image" :image "Video" :video "Audio" :audio} type)
      :file))

(defn ^:no-doc sized
  "The attachment `m` with the `width` and the `height` of its media in
  pixels, and their ratio, when both are known."
  [m width height]
  (let [w (common/dimension width)
        h (common/dimension height)]
    (cond-> m
      (and w h) (assoc :width w :height h :aspect-ratio [w h]))))

(defn- object-attachments
  "The media attached to the ActivityPub object `o`: images, videos and
  sounds, with their alt text and size where it names them."
  [o]
  (vec (for [attachment (common/values o "attachment")
             :when      (contains? #{"Document" "Image" "Video" "Audio"} (get attachment "type"))
             :let       [url        (common/web-url (href (get attachment "url")))
                         media-type (get attachment "mediaType")]
             :when      url]
         (sized (common/present {:kind       (media-kind media-type (get attachment "type"))
                                 :url        url
                                 :media-type (when (string? media-type) media-type)
                                 :alt        (common/first-text (get attachment "summary") (get attachment "name"))})
                (get attachment "width")
                (get attachment "height")))))

(defn- object-poll
  "The poll of the ActivityPub Question `o`: its choices with their votes,
  whether it takes several, and when it ends."
  [o]
  (let [several (common/values o "anyOf")]
    (common/present {:choices   (vec (for [choice (concat (common/values o "oneOf") several)
                                           :let   [text (common/first-text (get choice "name"))]
                                           :when  text]
                                       (common/present {:name text :votes (common/whole (get-in choice ["replies" "totalItems"]))})))
                     :multiple? (boolean (seq several))
                     :ends      (time/parse-date (or (get o "endTime") (get o "closed")))})))

(defn- object-comment
  "The ActivityPub object `o` as a comment read at `at`."
  [o at]
  (let [[language content] (content-of o)
        poll               (object-poll o)]
    (common/present {:id          (id-of o)
                     :url         (web-page o)
                     :author      (attributed o)
                     :in-reply-to (id-of (first (common/values o "inReplyTo")))
                     :published   (time/parse-date (get o "published"))
                     :updated     (time/parse-date (get o "updated"))
                     :title       (common/first-text (get o "name"))
                     :content     content
                     :language    language
                     :summary     (warning (get o "summary"))
                     :sensitive?  (true? (get o "sensitive"))
                     :attachments (object-attachments o)
                     :poll        (when (seq (:choices poll)) poll)
                     :deleted?    (= "Tombstone" (get o "type"))
                     :read-at     at})))

(defn- actor-author
  "The ActivityPub `actor` as an author read at `at`: its name, its handle,
  its profile page and its avatar.

  The handle is its user name at the domain of its id, when the user name
  is one that no other server can pass off as its own."
  [actor at]
  (let [user (common/first-text (get actor "preferredUsername"))
        host (http/host (id-of actor))]
    (common/present {:id      (id-of actor)
                     :name    (common/first-text (get actor "name") user)
                     :handle  (when (and host user (re-matches #"[A-Za-z0-9_.~-]+" user))
                                (str "@" user "@" host))
                     :url     (web-page actor)
                     :avatar  (common/web-url (href (get actor "icon")))
                     :read-at at})))

(defn ^:no-doc listing
  "The items of the ActivityPub collection or collection page `c`, and the
  page after them.

  A collection lists its items itself, or in its first page, or only names
  that page. Nil for a collection that lists nothing itself."
  [c]
  (let [page (get c "first")]
    (cond
      (or (contains? c "items") (contains? c "orderedItems"))
      {:items (concat (common/values c "orderedItems") (common/values c "items")) :next (id-of (get c "next"))}

      (map? page)    (listing page)
      (string? page) {:items [] :next page})))

(declare with-object)

(defn ^:no-doc with-items
  "`thread` with the `items` of a collection page that came from `url`,
  read at `at` as replies to `owner`.

  A public object from the server of `url` is a comment when `objects?`, a
  post that is not public is withheld, and anything else is a comment to
  fetch by its id.

  The `common/limits` of a page, of an id and of a thread apply. The
  thread counts the items that a limit cut, for its report."
  [thread owner items url at objects?]
  (let [{:keys [page] longest :id most :thread} common/limits
        full? (fn [thread id]
                (and (not (contains? (:comments thread) id))
                     (>= (count (:comments thread)) most)))]
    (reduce (fn [thread item]
              (let [id (id-of item)
                    o  (when (and objects? (map? item)) (post-of item))]
                (cond
                  (not (common/web-url id))                 thread
                  (> (count id) longest)                    (common/truncate thread :id 1)
                  (:read-at (get-in thread [:comments id])) thread
                  (full? thread id)                         (common/truncate thread :thread 1)
                  (and o (trusted? o url) (public? o))      (with-object thread o url at true)
                  (and o (trusted? o url))                  (common/withhold thread id)
                  (contains? (:comments thread) id)         thread
                  :else                                     (assoc-in thread [:comments id] {:id id :in-reply-to owner}))))
            (common/truncate thread :page (count (drop page items)))
            (take page items))))

(defn ^:no-doc with-collection
  "`thread` with the collection `c` of replies to `owner` at `path`, a value
  in a document that came from `url`, as far as the document lists it at
  `at`.

  Its items are read with `with-items`, objects among them when
  `objects?`. The collection is read when the document lists all of it,
  or else it names the URL of the page to read it from, which must be on
  the server of `url`."
  [thread path owner c url at objects?]
  (let [id                              (when (http/same-origin? (id-of c) url) (id-of c))
        {:keys [items next] :as listed} (when (map? c) (listing c))
        next                            (when (http/same-origin? next url) next)]
    (-> thread
        (with-items owner items url at objects?)
        (assoc-in path (common/present (cond
                                         next                   {:url next}
                                         (and (nil? listed) id) {:url id}
                                         :else                  {:url id :read-at at}))))))

(defn ^:no-doc with-object
  "`thread` with the ActivityPub object `o`, which came from `url`, as a
  comment read at `at`, and with the replies that it lists itself: those of
  an object `nested?` in another document by their ids alone.

  A post replies to what it names itself, whatever a collection that
  listed it said, but a post that is gone keeps the place that the
  collection gave it."
  [thread o url at nested?]
  (let [id (id-of o)]
    (-> thread
        (update-in [:comments id] (fn [listed]
                                    (let [c (common/guarded id at #(object-comment o at))]
                                      (cond-> c
                                        (and (:deleted? c) (not (:in-reply-to c)) (:in-reply-to listed))
                                        (assoc :in-reply-to (:in-reply-to listed))))))
        (with-collection [:comments id ::comments/collection] id (get o "replies") url at (not nested?)))))

(defn next-page
  "The request for the page after the one in `exchange`, an exchange
  `{:request :response}` of the reading of a collection. Nil after the last
  page, and for any other exchange.

  It is the page that the answer names next, on the same server. It is nil
  when the reading read that page before."
  [{:keys [request response] :as exchange}]
  (let [url  (:url request)
        seen (conj (::comments/seen request #{}) url)
        next (when (and (contains? #{:replies :conversation} (first (::comments/for request)))
                        (not (common/failure url response)))
               (:next (listing (common/document-of exchange))))]
    (when (and next (not (contains? seen next)) (http/same-origin? next (or (:url response) url)))
      (assoc request :url next ::comments/seen seen))))

(defn- read-pages
  "`thread` with the `exchanges` of a reading of the collection at `path`
  read into it at `at`, the items of each page as replies to `owner`.

  The collection is read when the reading ended at its last page, or at a
  page that failed, with the problem of it. When the limits stopped the
  reading, the collection goes on from the page after the last, and it
  keeps the pages read, so that no reading reads one twice."
  [thread path owner exchanges at]
  (let [read (reduce (fn [thread {:keys [request response] :as exchange}]
                       (let [url     (:url request)
                             problem (common/failure url response)
                             doc     (when-not problem (common/document-of exchange))]
                         (if-let [problem (or problem (when-not doc {:id :comments/malformed :url url}))]
                           (update-in thread path assoc :error problem)
                           (with-items thread owner (:items (listing doc)) (or (:url response) url) at true))))
                     thread
                     exchanges)
        more (next-page (last exchanges))]
    (update-in read path #(cond-> (update % ::comments/pages (fnil into #{}) (map (comp :url :request)) exchanges)
                            more       (assoc :url (:url more))
                            (not more) (assoc :read-at at)))))

(defn- conversation-of
  "The collection of the posts of the whole thread that the ActivityPub
  root `o` starts, on its own server: the comments of a PeerTube video, or
  the context of FEP-f228 when the root replies to nothing."
  [o]
  (let [c (or (get o "comments") (when (nil? (get o "inReplyTo")) (get o "context")))]
    (when (http/same-origin? (id-of c) (id-of o))
      c)))

(defn- read-root
  "`thread` with its root post read at `at` from the `answer` to the request
  for its URI, with the conversation that the root starts; or with the
  problem that kept the root from being read. A root that is not public
  is withheld."
  [{:keys [uri] :as thread} {:keys [document final] :as answer} at]
  (let [o       (post-of document)
        problem (post-problem answer uri)]
    (cond
      problem           (assoc thread :read-at at :error problem)
      (not (public? o)) (-> thread (assoc :read-at at) (common/withhold (id-of o)))
      :else             (let [talk (conversation-of o)]
                          (cond-> (-> thread
                                      (dissoc :error)
                                      (assoc :read-at at :root (id-of o))
                                      (with-object o final at false))
                            talk (with-collection [::comments/conversation] (id-of o) talk final at true))))))

(defn- read-item
  "`thread` with the post `id`, which a collection listed, read at `at` from
  the `answer` to the request for it.

  A post that is gone is deleted, one that is not public is withheld, and
  one that the answer names by another id is the comment of that id; else
  the comment has the problem that kept it from being read."
  [thread id {:keys [status document final] :as answer} at]
  (let [o       (post-of document)
        problem (post-problem answer id)
        read?   (:read-at (get-in thread [:comments (id-of o)]))]
    (cond
      (#{404 410} status) (update-in thread [:comments id] assoc :deleted? true :read-at at)
      problem             (update-in thread [:comments id] assoc :error problem :read-at at)
      (not (public? o))   (-> thread (update :comments dissoc id) (common/withhold (id-of o)))
      (= id (id-of o))    (with-object thread o final at false)
      ;; another name of a post that is read already, which reading again
      ;; would list its replies again, and this name with them
      read?               (update thread :comments dissoc id)
      :else               (with-object (update thread :comments dissoc id) o final at false))))

(defn- read-actor
  "`thread` with its author `id` read at `at` from the `answer` to the
  request for it, or with the problem that kept it from being read: an
  actor must speak for the server of `id` and of the answer."
  [thread id {:keys [problem document final]} at]
  (let [actor   (id-of document)
        problem (or problem
                    (when-not actor {:id :comments/malformed :url id})
                    (when-not (and (http/same-origin? actor final) (http/same-origin? actor id))
                      {:id :comments/untrusted :url id}))]
    (assoc-in thread [:authors id] (if problem
                                     {:id id :error problem :read-at at}
                                     (assoc (actor-author document at) :id id)))))

(def status-paths
  "The paths of the pages and of the ActivityPub ids of statuses, on the
  servers that answer the Mastodon API, as of September 2026. Each is a
  regular expression of the whole path, and its one group is the id of the
  status in the API.

  They are the paths of Mastodon, of GoToSocial, of the ActivityPub ids of
  both, and of Pleroma and Akkoma. `requests` reads them, unless its
  options give the paths of other servers as `:status-paths`."
  [#"/@[^/?#]+/([0-9A-Za-z]+)"
   #"/@[^/?#]+/statuses/([0-9A-Za-z]+)"
   #"/users/[^/?#]+/statuses/([0-9A-Za-z]+)"
   #"/notice/([0-9A-Za-z]+)"])

(defn ^:no-doc api-status
  "The origin of the server of the status at the web or ActivityPub URL
  `uri`, and the id of the status in the Mastodon API there, by the `paths`
  of statuses, as `status-paths` has them; nil for another URL."
  [uri paths]
  (when-let [[_ origin path] (when (string? uri)
                               (re-find #"^(https?://[^/?#]+)(/[^?#]*)" uri))]
    (let [path (str/replace path #"(^|[^/])/+$" "$1")
          id   (some (fn [pattern]
                       (let [match (re-matches pattern path)]
                         (when (vector? match) (second match))))
                     paths)]
      (when id
        [origin id]))))

(defn- api-requests
  "The requests to the Mastodon API of the server of `uri` for the parts of
  its thread that it has not `read`: the `:status` of the root and the
  `:context` of the replies below it. `paths` are those of `api-status`."
  [uri read paths]
  (let [[origin id] (api-status uri paths)
        status      (str origin "/api/v1/statuses/" id)]
    (for [[part url] [[:status status] [:context (str status "/context")]]
          :when      (not (contains? read part))]
      {:method :get :url url :headers {"Accept" "application/json"} ::comments/for [part id]})))

(defn ^:no-doc account-id
  "The id of the `account` of the Mastodon API of the server at `origin`:
  its ActivityPub id, or else the URL of its page, when it is on that
  server."
  [account origin]
  (let [id (some common/web-url [(get account "uri") (get account "url")])]
    (when (http/same-origin? id origin) id)))

(defn- account-author
  "The `account` of the Mastodon API of the server at `origin` as an author
  read at `at`."
  [account origin at]
  (let [acct (common/first-text (get account "acct"))]
    (common/present {:id      (account-id account origin)
                     :name    (common/first-text (get account "display_name") (get account "username"))
                     :handle  (when (and acct (re-matches #"[A-Za-z0-9_.~-]+" acct))
                                (str "@" acct "@" (http/host origin)))
                     :url     (own-url (get account "url") origin)
                     :avatar  (common/web-url (get account "avatar"))
                     :read-at at})))

(defn- status-attachments
  "The media attached to the `status` of the Mastodon API."
  [status]
  (vec (for [media (common/values status "media_attachments")
             :let  [url (some common/web-url [(get media "url") (get media "remote_url")])]
             :when url]
         (sized (common/present {:kind    (get {"image" :image "gifv" :video "video" :video "audio" :audio} (get media "type") :file)
                                 :url     url
                                 :preview (common/web-url (get media "preview_url"))
                                 :alt     (common/first-text (get media "description"))})
                (get-in media ["meta" "original" "width"])
                (get-in media ["meta" "original" "height"])))))

(defn- status-poll
  "The `poll` of a status of the Mastodon API: its choices with their votes,
  whether it takes several, and when it ends."
  [poll]
  (common/present {:choices   (vec (for [option (common/values poll "options")
                                         :let   [text (common/first-text (get option "title"))]
                                         :when  text]
                                     (common/present {:name text :votes (common/whole (get option "votes_count"))})))
                   :multiple? (true? (get poll "multiple"))
                   :ends      (time/parse-date (get poll "expires_at"))}))

(defn- status-comment
  "The `status` of the Mastodon API of the server at `origin` as a comment
  read at `at` with its replies, which replies to the status that `ids`
  maps its id in the API to."
  [status ids origin at]
  (let [poll (status-poll (get status "poll"))]
    (common/present {:id                   (get status "uri")
                     :url                  (own-url (get status "url") origin)
                     :author               (account-id (get status "account") origin)
                     :in-reply-to          (get ids (get status "in_reply_to_id"))
                     :published            (time/parse-date (get status "created_at"))
                     :updated              (time/parse-date (get status "edited_at"))
                     :content              (common/safe-html (get status "content") (get status "uri"))
                     :language             (common/first-text (get status "language"))
                     :summary              (common/first-text (get status "spoiler_text"))
                     :sensitive?           (true? (get status "sensitive"))
                     :attachments          (status-attachments status)
                     :poll                 (when (seq (:choices poll)) poll)
                     :read-at              at
                     ::comments/collection {:read-at at}})))

(defn- read-api
  "`thread` with the answer of the Mastodon API about its root read at `at`,
  its `part`, :status or :context, from the `answer` to the request for the
  status `id`, or with the problem that kept the answer from being read.

  The API speaks for its own server alone. Of the public statuses in the
  answer, those of the server are comments with their authors, and those
  of other servers are comments to fetch from them. An answer that holds
  no status, or no list of the statuses below it, is a problem."
  [{:keys [uri root] :as thread} part id {:keys [problem document] origin :url} at]
  (let [statuses    (if (= :status part) [document] (common/values document "descendants"))
        own?        #(and (common/web-url (get % "uri"))
                          (http/same-origin? (get % "uri") origin)
                          (contains? #{nil "public" "unlisted"} (get % "visibility")))
        problem     (or problem
                        (when (and (= :status part) (not (own? document))) {:id :comments/malformed :url uri})
                        (when (and (= :context part) (not (sequential? (get document "descendants"))))
                          {:id :comments/malformed :url origin}))
        ids         (into {id (or root (get document "uri"))} (map (juxt #(get % "id") #(get % "uri"))) statuses)]
    (if problem
      (update thread ::comments/api #(-> % (update :read (fnil conj #{}) part) (assoc :error problem)))
      (reduce (fn [thread status]
                (let [status-uri (get status "uri")]
                  (cond
                    (own? status)
                    (let [c (common/guarded status-uri at #(status-comment status ids origin at))]
                      (cond-> (assoc-in thread [:comments status-uri] c)
                        (:author c) (assoc-in [:authors (:author c)] (account-author (get status "account") origin at))))

                    (or (not (common/web-url status-uri)) (contains? (:comments thread) status-uri))
                    thread

                    :else
                    (assoc-in thread [:comments status-uri]
                              {:id status-uri :in-reply-to (get ids (get status "in_reply_to_id"))}))))
              (cond-> (update thread ::comments/api update :read (fnil conj #{}) part)
                (= :status part) (assoc :root (get document "uri") :via :mastodon-api))
              statuses))))

(defn- api-fallback?
  "Whether `thread` is read through the Mastodon API of the server of its
  root: when the server refused the root unsigned, or the request for it
  failed without an answer, as a request that CORS keeps from a page does.
  `paths` are those of `api-status`."
  [{:keys [uri error]} paths]
  (boolean (and (api-status uri paths)
                (or (= :comments/refused (:id error))
                    (and (= :comments/unreachable (:id error)) (nil? (:status error)) (not (:timeout? error)))))))

(defn- page-requests
  "The requests for the pages of the collections of `thread` that it has
  not read: of its conversation, or else of the replies of each post."
  [{:keys [root comments] ::comments/keys [conversation]}]
  (if (and conversation (not (:error conversation)))
    (when-not (:read-at conversation)
      [(assoc (document-request (:url conversation) [:conversation root]) ::comments/seen (::comments/pages conversation #{}))])
    (for [{:keys [id read-at] ::comments/keys [collection]} (vals comments)
          :when (and read-at (:url collection) (not (:read-at collection)))]
      (assoc (document-request (:url collection) [:replies id]) ::comments/seen (::comments/pages collection #{})))))

(defn readable?
  "Whether the thread of `interact` is one that `requests` reads: its URI
  is an http or https URL."
  [{:keys [uri]}]
  (some? (common/web-url uri)))

(defn- reopened
  "The collection `c` of replies open again from the page that failed, when
  the failure can pass with time."
  [{:keys [error] :as c}]
  (if (:retry? error)
    (cond-> (assoc (dissoc c :error :read-at) :url (:url error))
      (::comments/pages c) (update ::comments/pages disj (:url error)))
    c))

(defn resumed
  "The ActivityPub `thread` as a new reading of it starts: unread again
  where a failure that can pass with time stopped the last reading.

  This is the root, a post, an author, a page of a collection, and the
  answer of the Mastodon API."
  [{:keys [comments] ::comments/keys [api conversation] :as thread}]
  (cond-> (common/retried thread)
    comments               (update :comments update-vals #(cond-> % (::comments/collection %) (update ::comments/collection reopened)))
    conversation           (update ::comments/conversation reopened)
    (:retry? (:error api)) (dissoc ::comments/api)))

(defn requests
  "The requests that the ActivityPub `thread` needs next, of those whose
  URLs `ask?` takes. They ask for no more posts than the `:max-comments`
  of `opts` allows, counted from the last `resumed`.

  The root post comes first. When its server keeps it from the reader, the
  Mastodon API of that server comes next. Then come the pages of the
  collections and the posts that they name, nearest the root first, and
  then the authors. It asks for no more pages while the posts that wait
  fill the room that `:max-comments` leaves.

  The URL of the root post tells its id in the Mastodon API, by
  `status-paths` or by the `:status-paths` of `opts`."
  [{:keys [uri root read-at comments authors via] ::comments/keys [api held] :as thread}
   {:keys [max-comments] :as opts}
   ask?]
  (let [all     (vals comments)
        room    (- max-comments (- (count (filter :read-at all)) (or held 0)))
        waiting (count (remove :read-at all))
        depth   (common/depths thread)
        paths   (:status-paths opts status-paths)]
    (->> (cond
           (nil? read-at)
           (when (common/web-url uri) [(document-request uri [:root])])

           (and (api-fallback? thread paths) (not= #{:status :context} (:read api)))
           (api-requests uri (:read api) paths)

           root
           (concat (when (pos? room)
                     (common/nearest-first depth (concat (when (and (< waiting room) (not via))
                                                           (filter (comp ask? :url) (page-requests thread)))
                                                         (->> (for [{:keys [id read-at]} all :when (nil? read-at)]
                                                                (document-request id [:comment id]))
                                                              (filter (comp ask? :url))
                                                              (common/nearest-first depth)
                                                              (take room)))))
                   (for [author (sort (distinct (keep :author (filter :read-at all))))
                         :when  (and (common/web-url author) (ask? author) (not (contains? authors author)))]
                     (document-request author [:author author]))))
         (filter (comp ask? :url)))))

(defn with-exchanges
  "The ActivityPub `thread` with the `exchanges` of one of its `requests`
  read into it at `at`."
  [thread exchanges at]
  (let [{:keys [request] :as exchange} (first exchanges)
        [kind id]                      (::comments/for request)]
    (case kind
      :replies           (read-pages thread [:comments id ::comments/collection] id exchanges at)
      :conversation      (read-pages thread [::comments/conversation] id exchanges at)
      :root              (read-root thread (answer-of exchange) at)
      :comment           (read-item thread id (answer-of exchange) at)
      :author            (read-actor thread id (answer-of exchange) at)
      (:status :context) (read-api thread kind id (answer-of exchange) at)
      thread)))
