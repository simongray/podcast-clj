(ns dk.simongray.podcast.comments.activitypub
  "Threads of comments over ActivityPub, the protocol of Mastodon, PeerTube
  and the podcast host Castopod.

  Each post is a JSON document at its own URL. A root post can start a
  conversation and name a collection of all its posts. This collection is
  the context of FEP-f228, or the comments of a PeerTube video. A reading
  asks for that collection, or else for the replies collection of each
  post, down the tree. It asks for each post that a collection names by
  URL at the post's own server, and for each author too. A server speaks
  only for its own posts and authors, as FEP-fe34 asks, and a post that
  isn't public is left out.

  Some servers answer only signed requests, and Mastodon lets no page on
  another site read its posts. For such a root post, the reading asks the
  Mastodon API of its server for the thread. Many servers answer this API,
  which speaks only for its own server too."
  (:require [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.activitypub.impl :as impl]
            [dk.simongray.podcast.comments.common :as common]))

(def status-paths
  "The paths of statuses on the servers that answer the Mastodon API, as of
  October 2026.

  They're the paths of the web pages and of the ActivityPub ids. Each is a
  regular expression of the whole path, and its one group is the id of
  the status in the API.

  They're the paths of Mastodon, of GoToSocial, of the ActivityPub ids of
  both, the numeric ids of newer Mastodon servers included, and of Pleroma
  and Akkoma, unless the options give the paths of other servers as
  :status-paths."
  [#"/@[^/?#]+/([0-9A-Za-z]+)"
   #"/@[^/?#]+/statuses/([0-9A-Za-z]+)"
   #"/users/[^/?#]+/statuses/([0-9A-Za-z]+)"
   #"/ap/users/[^/?#]+/statuses/([0-9A-Za-z]+)"
   #"/notice/([0-9A-Za-z]+)"])

(defn ^:no-doc requests
  "The requests that the ActivityPub `thread` needs next with `opts`, of
  those whose URLs `ask-pred` takes. They ask for no more posts than the
  :max-comments of `opts` allows, counted from the start of the reading.

  The root post comes first. When its server keeps it from the reader, the
  Mastodon API of that server comes next. Then come the pages of the
  collections and the posts that they name, nearest the root first, and
  then the authors. No more pages are asked for while the posts that wait
  fill the room that :max-comments leaves.

  The URL of the root post tells its id in the Mastodon API, by
  status-paths or by the :status-paths of `opts`."
  [{:keys [uri root read-at comments] ::comments/keys [api held] :as thread}
   {:keys [max-comments] :as opts}
   ask-pred]
  (let [fresh (- (count (filter :read-at (vals comments))) (or held 0))
        room  (- max-comments fresh)
        paths (:status-paths opts status-paths)]
    (->> (cond
           (nil? read-at)
           (when (common/web-url uri)
             [(impl/document-request uri [:root])])

           (and (impl/api-fallback? thread paths)
                (not= #{:status :context} (:read api)))
           (impl/api-requests uri (:read api) paths)

           root
           (impl/thread-requests thread room ask-pred))
         (filter (comp ask-pred :url)))))

(comment
  (def sample-thread
    {:protocol "activitypub"
     :uri      "https://example.social/users/ann/statuses/1"})

  ;; the first request of an unread thread: the root post
  (def sample-request
    (first (requests sample-thread {:max-comments 100} (constantly true))))

  ;; the root post, which lists one reply by its id
  (def sample-replies
    {"id"    "https://example.social/users/ann/statuses/1/replies"
     "type"  "Collection"
     "first" {"type"  "CollectionPage"
              "items" ["https://example.social/users/bob/statuses/2"]}})

  (def sample-root
    {"id"           "https://example.social/users/ann/statuses/1"
     "type"         "Note"
     "attributedTo" "https://example.social/users/ann"
     "to"           ["https://www.w3.org/ns/activitystreams#Public"]
     "published"    "2026-09-19T00:22:52Z"
     "content"      "<p>What did you think of the episode?</p>"
     "replies"      sample-replies})

  (def sample-read
    (impl/with-exchanges sample-thread
                         [{:request  sample-request
                           :response {:status 200 :url (:uri sample-thread)}
                           :document sample-root}]
                         #inst "2026-09-29T12:00:00Z"))

  (:comments sample-read)

  ;; what the thread needs next: the reply, and the author of the root
  (map :url (requests sample-read {:max-comments 100} (constantly true)))

  ;; the server and the id of a status in the Mastodon API
  (impl/api-status "https://mastodon.social/@ann/112233" status-paths)
  #_.)
