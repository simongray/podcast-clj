(ns dk.simongray.podcast.comments-test
  "Threads of comments read from the answers that Mastodon, Misskey,
  PeerTube, the Mastodon API and a Bluesky AppView gave, recorded and
  trimmed: a thread through its conversation and down its collections of
  replies, servers that speak only for themselves, the content fit to show,
  the limits and the reading on, the fallback for a server in secure mode,
  and the labels and hidden replies of the AT Protocol, on both platforms;
  and on the JVM, random answers, which the reader reads without throwing."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.async :as async]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.activitypub :as activitypub]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.time :as time]))

(def mastodon
  "A thread of podcastindex.social, whose root starts a conversation, with
  replies from Misskey and another Mastodon server, and their authors."
  (json/read-str (fixture "comments/mastodon.json")))

(def mastodon-api
  "The same thread as the Mastodon API of podcastindex.social gives it."
  (json/read-str (fixture "comments/mastodon-api.json")))

(def peertube
  "A PeerTube video, whose comments collection lists every comment of it."
  (json/read-str (fixture "comments/peertube.json")))

(def bluesky
  "The answers of an AppView for the thread below a post of atproto.com, and
  below a post of Podnews, by the post that each was asked for."
  (json/read-str (fixture "comments/bluesky.json")))

(def root
  "The web page of the root of the Mastodon thread, as a feed names it."
  "https://podcastindex.social/@dave/117294750060235480")

(def root-id
  "The id of the root of the Mastodon thread."
  "https://podcastindex.social/users/dave/statuses/117294750060235480")

(def elsewhere
  "A reply of the Mastodon thread on another server than its root."
  "https://masto.es/users/DavidMarzalC/statuses/117298098358250174")

(def root-page
  "The page of the replies to the root of the Mastodon thread by others."
  "https://podcastindex.social/users/dave/statuses/117294750060235480/replies?only_other_accounts=true&page=true")

(def video
  "The id of the PeerTube video."
  "https://framatube.org/videos/watch/4294a720-f263-4ea4-9392-cf9cea4d5277")

(def bluesky-root
  "The root of the Bluesky thread."
  "at://did:plc:ewvi7nxzyoun6zhxrhs64oiz/app.bsky.feed.post/3msydg6sd7s2d")

(defn- server
  "A send that answers a request with the document that `answers` has for
  its URL, and 404 for a URL that it lacks, noting each URL in `log`."
  [answers log]
  (fn [{:keys [url]}]
    (swap! log conj url)
    (if-let [doc (get answers url)]
      {:status 200 :url url :body (json/write-str doc)}
      {:status 404 :url url :body "{\"error\":\"Not Found\"}"})))

(defn- appview
  "A send that answers a request for the thread below a post with the
  answer that `answers` has for the post, and as an AppView does for a post
  that it lacks, noting each post in `log`."
  [answers log]
  (fn [{:keys [url]}]
    (let [uri (http/percent-decode (second (re-find #"[?&]uri=([^&]+)" url)))]
      (swap! log conj uri)
      (if-let [doc (get answers uri)]
        {:status 200 :url url :body (json/write-str doc)}
        {:status 400 :url url :body "{\"error\":\"NotFound\",\"message\":\"Post not found\"}"}))))

(defn- read-thread
  "The thread of `uri` over ActivityPub read by `fetch!` with the `answers`
  and `opts`, the URLs asked for noted in `log`; deferred."
  [uri answers log & {:as opts}]
  (comments/fetch! {:protocol "activitypub" :uri uri} (merge {:send (server answers log)} opts)))

(defn- shape
  "The tree `node` as nested vectors of the names of the authors."
  [node]
  (into [(get-in node [:author :name])] (map shape) (:replies node)))

(defn- ids
  "The ids of the diagnostics in the report of `thread`."
  [thread]
  (map :id (:report thread)))

(defn- summary
  "The diagnostics in the report of `thread`, each with its keys `ks` alone."
  [thread & ks]
  (map #(select-keys % ks) (:report thread)))

(deftest a-thread-through-its-conversation
  (let [log (atom [])]
    (run #(async/then
           (read-thread root mastodon log)
           (fn [thread]
             (is (= root-id (:root thread)) "the root by its id, not by the page the feed names")
             (is (= ["Dave" ["Quantum Panhandler" ["Dave"]] ["Sir Alecks Gates" ["Dave"]] ["Adam Curry" ["Dave"]] ["David Marzal"]]
                    (shape (comments/tree thread)))
                 "the reply to the Misskey note, which lists no replies, is in the conversation")
             (is (= [] (:report thread)))
             (is (= 14 (count @log)) "the root, the conversation, seven posts and five authors, one request each")
             (let [note (get-in thread [:comments "https://mk.spook.social/notes/arb76m33iw"])]
               (is (= {:url         "https://mk.spook.social/notes/arb76m33iw"
                       :author      "https://mk.spook.social/users/9iyva7z1zi"
                       :in-reply-to root-id
                       :content     (str "<p><a href=\"https://podcastindex.social/@dave\" rel=\"nofollow ugc\">"
                                         "@dave@podcastindex.social</a> Foot Terminal</p>")}
                      (select-keys note [:url :author :in-reply-to :content])))
               (is (= 1789776757839 (inst-ms (:published note)))))
             (is (= {:name "Quantum Panhandler" :handle "@cbrooklyn112@mk.spook.social" :url "https://mk.spook.social/@cbrooklyn112"}
                    (select-keys (get-in thread [:authors "https://mk.spook.social/users/9iyva7z1zi"]) [:name :handle :url]))
                 "the handle is the user name at the host of the actor's id")
             (is (not (str/includes? (get-in thread [:comments "https://podcastindex.social/users/adam/statuses/117297989453672773" :content])
                                     "class="))
                 "the content is sanitized"))))))

(deftest a-thread-down-its-collections-of-replies
  (let [log (atom [])]
    (run #(async/then
           (read-thread root (update mastodon root dissoc "context") log)
           (fn [thread]
             (is (= ["Dave" ["Quantum Panhandler"] ["Sir Alecks Gates" ["Dave"]] ["Adam Curry" ["Dave"]] ["David Marzal"]]
                    (shape (comments/tree thread)))
                 "Misskey lists no replies, so the reply to its note is out of reach")
             (is (= [] (:report thread)))
             (is (not-any? (fn [url] (str/ends-with? url "/statuses/117294849863787127")) @log)
                 "a reply that a page of its own server holds needs no request of its own")
             (is (some #{elsewhere} @log) "a reply on another server is fetched from there"))))))

(deftest a-conversation-that-cannot-be-read
  (let [answers (dissoc mastodon "https://podcastindex.social/contexts/269-117294750060235480")]
    (run #(async/then
           (read-thread root answers (atom []))
           (fn [thread]
             (is (= 7 (count (:comments thread))) "the replies are read down their collections instead")
             (is (= [{:id :comments/unreachable :url "https://podcastindex.social/contexts/269-117294750060235480" :status 404}]
                    (summary thread :id :url :status))))))))

(deftest a-post-that-another-server-holds
  (let [forged  {"id" elsewhere "type" "Note" "attributedTo" "https://masto.es/users/DavidMarzalC"
                 "inReplyTo" root-id "published" "2026-09-19T14:00:00Z" "content" "<p>forged</p>"}
        answers (-> mastodon
                    (update root dissoc "context")
                    (update-in [root-page "items"] (fn [items] (mapv #(if (= elsewhere %) forged %) items))))
        log     (atom [])]
    (run #(async/then
           (read-thread root answers log)
           (fn [thread]
             (is (some #{elsewhere} @log) "is fetched from its own server")
             (is (not (str/includes? (get-in thread [:comments elsewhere :content]) "forged"))))))))

(deftest a-server-speaks-only-for-itself
  (let [claims (-> mastodon
                   (update root dissoc "context")
                   (assoc-in [elsewhere "attributedTo"] "https://podcastindex.social/users/adam")
                   (assoc-in ["https://mk.spook.social/users/9iyva7z1zi" "id"] "https://podcastindex.social/users/cbrooklyn112"))]
    (run #(async/then
           (async/all [(read-thread root claims (atom []))
                       (read-thread root (assoc-in mastodon [root "id"] "https://elsewhere.example/notes/1") (atom []))])
           (fn [[replies roots]]
             (is (= #{{:id :comments/untrusted :url elsewhere}
                      {:id :comments/untrusted :url "https://mk.spook.social/users/9iyva7z1zi"}}
                    (set (summary replies :id :url)))
                 "a reply by an author of another server, and an author with an id there")
             (is (nil? (comments/tree roots)))
             (is (= [{:id :comments/untrusted :url root}] (summary roots :id :url))
                 "a root with an id on another server"))))))

(deftest what-a-post-names-itself
  (let [answers (-> mastodon
                    (update root dissoc "context")
                    ;; a post of the root's server that replies to nothing, listed as a reply
                    (update-in [root-page "items"] conj "https://podcastindex.social/users/adam/statuses/1")
                    (assoc "https://podcastindex.social/users/adam/statuses/1"
                           {"id" "https://podcastindex.social/users/adam/statuses/1" "type" "Note"
                            "attributedTo" "https://podcastindex.social/users/adam" "content" "<p>elsewhere</p>"
                            "replies" "https://masto.es/users/DavidMarzalC/replies"})
                    ;; an author who names another server's user in their user name
                    (assoc-in ["https://mk.spook.social/users/9iyva7z1zi" "preferredUsername"] "Gargron@mastodon.social")
                    (assoc-in ["https://mk.spook.social/users/9iyva7z1zi" "webfinger"] "acct:Gargron@mastodon.social"))
        log     (atom [])]
    (run #(async/then
           (read-thread root answers log)
           (fn [thread]
             (is (= ["Dave" ["Quantum Panhandler"] ["Sir Alecks Gates" ["Dave"]] ["Adam Curry" ["Dave"]] ["David Marzal"]]
                    (shape (comments/tree thread)))
                 "a post is under the post it replies to, not under the one whose collection listed it")
             (is (not-any? #{"https://masto.es/users/DavidMarzalC/replies"} @log)
                 "a collection of replies on another server than its post is not read")
             (is (nil? (get-in thread [:authors "https://mk.spook.social/users/9iyva7z1zi" :handle]))
                 "no handle from a user name that holds an @, and none from a webfinger"))))))

(deftest a-reply-that-is-not-public
  (let [answers (assoc-in mastodon [elsewhere "cc"] ["https://podcastindex.social/users/dave"])]
    (run #(async/then
           (read-thread root answers (atom []))
           (fn [thread]
             (is (= ["Dave" ["Quantum Panhandler" ["Dave"]] ["Sir Alecks Gates" ["Dave"]] ["Adam Curry" ["Dave"]]]
                    (shape (comments/tree thread))))
             (is (= [{:id :comments/withheld :count 1}] (summary thread :id :count))))))))

(deftest the-content-of-a-post
  (let [content (str "<p><a href=\"/tags/podcast\">#podcast</a> <img src=\"https://tracker.example/pixel.gif\" alt=\"a pixel\">"
                     " <a href=\"javascript:alert(1)\">bad</a> <script>alert(1)</script></p>")
        answers (-> mastodon
                    (assoc-in [root "content"] content)
                    (update root dissoc "contentMap"))]
    (run #(async/then
           (read-thread root answers (atom []))
           (fn [thread]
             (is (= (str "<p><a href=\"https://podcastindex.social/tags/podcast\" rel=\"nofollow ugc\">#podcast</a> a pixel"
                         " <a rel=\"nofollow ugc\">bad</a> </p>")
                    (get-in thread [:comments root-id :content]))
                 "links absolute, marked as a user's, and none to a script; images as their alt text; no scripts"))))))

(deftest a-video-and-its-comments
  (let [log (atom [])]
    (run #(async/then
           (read-thread "https://framatube.org/w/9dRFC6Ya11NCVeYKn8ZhiD" peertube log :max-pages 1)
           (fn [thread]
             (is (= ["Framasoft" ["aelmahmoudy" ["Framasoft"]]] (shape (comments/tree thread)))
                 "each comment below the one it replies to, though the collection lists them all")
             (let [video (get-in thread [:comments (:root thread)])]
               (is (= {:title   "What is the Fediverse?"
                       :url     "https://framatube.org/w/9dRFC6Ya11NCVeYKn8ZhiD"
                       :author  "https://framatube.org/accounts/framasoft"
                       :content "<p>What is the Fediverse?<br>A short film about it, with [LILA](https://libreart.info/) &amp; friends.</p>"}
                      (select-keys video [:title :url :author :content]))
                   "Markdown keeps its line breaks, and the person of the account and its channel is the author"))
             (is (= "the subtitles" (get-in thread [:comments "https://mastodon.world/users/aelmahmoudy/statuses/110585879252762665" :summary])))
             (is (= {:deleted? true} (select-keys (get-in thread [:comments "https://gone.example/objects/1"]) [:deleted? :error]))
                 "a comment that is gone is deleted, and nothing below it keeps it in the tree")
             (is (= [] (:report thread)))
             (is (= 3 (count (filter (fn [url] (re-find #"/comments(\?page=\d+)?$" url)) (distinct @log))))
                 "a page at a time, the collection and its two pages in all"))))))

(deftest a-comment-that-is-gone-with-a-reply-below-it
  (let [answers (dissoc peertube "https://mastodon.world/users/aelmahmoudy/statuses/110585879252762665")]
    (run #(async/then
           (read-thread "https://framatube.org/w/9dRFC6Ya11NCVeYKn8ZhiD" answers (atom []))
           (fn [thread]
             (is (= ["Framasoft" [nil ["Framasoft"]]] (shape (comments/tree thread))))
             (is (:deleted? (first (:replies (comments/tree thread))))))))))

(deftest a-poll
  (let [answers (-> mastodon
                    (update root assoc
                            "type" "Question"
                            "oneOf" [{"type" "Note" "name" "Yes" "replies" {"type" "Collection" "totalItems" 3}}
                                     {"type" "Note" "name" "No" "replies" {"type" "Collection" "totalItems" 1.0}}]
                            "endTime" "2026-09-20T00:00:00Z"))]
    (run #(async/then
           (read-thread root answers (atom []))
           (fn [thread]
             (is (= {:choices [{:name "Yes" :votes 3} {:name "No" :votes 1}] :ends #inst "2026-09-20T00:00:00Z"}
                    (get-in thread [:comments root-id :poll]))))))))

(deftest a-server-in-secure-mode
  (let [log     (atom [])
        answers (server (-> (merge mastodon mastodon-api)
                            ;; a status of another server that the API server passes off as its own
                            (update-in ["https://podcastindex.social/api/v1/statuses/117294750060235480/context" "descendants"]
                                       (fn [statuses] (mapv #(if (= "https://mk.spook.social/notes/arb76m33iw" (get % "uri"))
                                                               (assoc % "content" "<p>forged</p>")
                                                               %)
                                                            statuses))))
                        log)
        send    (fn [{:keys [url] :as request}]
                  (if (= root url)
                    (do (swap! log conj url) {:status 401 :url url :body "{\"error\":\"Request not signed\"}"})
                    (answers request)))]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri root} {:send send :concurrency 1})
           (fn [thread]
             (is (= :mastodon-api (:via thread)))
             (is (= ["Dave" ["Quantum Panhandler" ["Dave"]] ["Sir Alecks Gates" ["Dave"]] ["Adam Curry :pci: :pc2blue:" ["Dave"]]
                     ["David Marzal"]]
                    (shape (comments/tree thread)))
                 "one request at a time asks for the status and for the context")
             (is (= [{:id :comments/through-api :level :info :status 401}] (summary thread :id :level :status)))
             (is (= #{root
                      "https://podcastindex.social/api/v1/statuses/117294750060235480"
                      "https://podcastindex.social/api/v1/statuses/117294750060235480/context"
                      "https://mk.spook.social/notes/arb76m33iw" elsewhere
                      "https://mk.spook.social/users/9iyva7z1zi" "https://masto.es/users/DavidMarzalC"}
                    (set @log))
                 "the statuses and accounts of other servers are read from those servers")
             (is (not (str/includes? (get-in thread [:comments "https://mk.spook.social/notes/arb76m33iw" :content]) "forged"))))))))

(deftest a-server-in-secure-mode-whose-api-fails
  (let [send (fn [{:keys [url] :as request}]
               (cond
                 (= root url)                    {:status 401 :url url}
                 (str/ends-with? url "/context") {:status 503 :url url}
                 :else                           ((server mastodon-api (atom [])) request)))]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri root} {:send send})
           (fn [thread]
             (is (= ["Dave"] (shape (comments/tree thread))))
             (is (= [:comments/through-api :comments/unreachable :comments/incomplete] (ids thread))
                 "a later fetch asks the API again, since a 503 can pass"))))))

(deftest a-server-in-secure-mode-whose-api-gives-no-thread
  (let [context (atom "<html>Just a moment...</html>")
        send    (fn [{:keys [url] :as request}]
                  (cond
                    (= root url)                    {:status 401 :url url}
                    (str/ends-with? url "/context") {:status 200 :url url :body @context}
                    :else                           ((server mastodon-api (atom [])) request)))
        fetch   #(comments/fetch! {:protocol "activitypub" :uri root} {:send send})]
    (run #(async/then
           (fetch)
           (fn [thread]
             (is (= ["Dave"] (shape (comments/tree thread))))
             (is (= [:comments/through-api :comments/malformed] (ids thread))
                 "an answer that is no JSON is not a thread without replies")
             (reset! context "{\"error\":\"Record not found\"}")
             (async/then
              (fetch)
              (fn [thread]
                (is (= [:comments/through-api :comments/malformed] (ids thread))
                    "and neither is an object that lists no statuses"))))))))

(deftest the-paths-of-statuses
  (is (= [["https://podcastindex.social" "117294750060235480"]
          ["https://podcastindex.social" "117294750060235480"]
          ["https://gts.example.org" "01HXYZ"]
          ["https://pleroma.example.org" "AbC123"]
          nil
          nil]
         (map #(activitypub/api-status % activitypub/status-paths)
              [root
               (str root-id "/")
               "https://gts.example.org/@ann/statuses/01HXYZ?x=1"
               "https://pleroma.example.org/notice/AbC123#top"
               "https://mk.spook.social/notes/arb76m33iw"
               "not a URL"])))
  (testing "a server in secure mode whose statuses have other paths"
    (let [page   "https://notes.example.org/notes/abc123"
          api    "https://notes.example.org/api/v1/statuses/abc123"
          status {"id" "abc123" "uri" page "url" page "content" "<p>root</p>" "visibility" "public"
                  "account" {"acct" "ann" "display_name" "Ann" "url" "https://notes.example.org/@ann"}}
          log    (atom [])
          send   (fn [{:keys [url]}]
                   (swap! log conj url)
                   (condp = url
                     page                 {:status 401 :url url}
                     api                  {:status 200 :url url :body (json/write-str status)}
                     (str api "/context") {:status 200 :url url :body (json/write-str {"ancestors" [] "descendants" []})}
                     {:status 404 :url url}))
          fetch  (fn [opts] (comments/fetch! {:protocol "activitypub" :uri page} (merge {:send send} opts)))]
      (run #(async/then
             (fetch {})
             (fn [refused]
               (is (= [:comments/refused] (ids refused)) "no paths of podcast-clj tell the id of this status")
               (is (= [page] @log))
               (reset! log [])
               (async/then
                (fetch {:status-paths (conj activitypub/status-paths #"/notes/([0-9a-z]+)")})
                (fn [thread]
                  (is (= :mastodon-api (:via thread)))
                  (is (= ["Ann"] (shape (comments/tree thread))))
                  (is (= page (first @log)))
                  (is (= #{api (str api "/context")} (set (rest @log)))
                      "the status and its context, in one round")))))))))

(deftest the-limits-and-reading-on
  (let [log (atom [])]
    (run #(async/then
           (read-thread root mastodon log :max-comments 3)
           (fn [partial]
             (is (= :comments/incomplete (last (ids partial))))
             (is (< 2 (count (filter :read-at (vals (:comments partial)))) 8))
             (reset! log [])
             (async/then
              (comments/fetch! partial {:send (server mastodon log)})
              (fn [thread]
                (is (= 8 (count (:comments thread))))
                (is (= [] (:report thread)))
                (is (not-any? #{root "https://podcastindex.social/contexts/269-117294750060235480"} @log)
                    "a fetch reads on from where the one before it stopped"))))))))

(deftest a-limit-of-requests
  (let [log (atom [])]
    (run #(async/then
           (read-thread root mastodon log :max-requests 3)
           (fn [thread]
             (is (= 3 (count @log)))
             (is (= [:comments/incomplete] (ids thread))))))))

(deftest a-reading-that-takes-too-long
  (let [clock (atom 0)
        log   (atom [])
        send  (fn [request]
                (swap! log conj [(:url request) (:timeout-seconds request) (:max-bytes request)])
                (swap! clock + 30000)
                ((server mastodon (atom [])) request))]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri root}
                            {:send send :max-seconds 60 :timeout-seconds 50 :max-bytes 1000000 :concurrency 1
                             :now (fn [] (time/->inst @clock))})
           (fn [thread]
             (is (= [[root 50 1000000] ["https://podcastindex.social/contexts/269-117294750060235480" 30 1000000]] @log)
                 "the time that is left cuts the time of a request, and no request starts after a minute")
             (is (= [:comments/incomplete] (ids thread))))))))

(deftest a-server-that-does-not-answer
  (let [answers (assoc-in peertube [(str video "/comments?page=1") "orderedItems"]
                          ["https://slow.example/objects/1" "https://slow.example/objects/2"
                           "https://closed.example/objects/1" "https://closed.example/objects/2"
                           "https://mastodon.world/users/aelmahmoudy/statuses/110585879252762665"])
        log     (atom [])
        send    (fn [{:keys [url] :as request}]
                  (swap! log conj url)
                  (cond
                    (str/starts-with? url "https://slow.example/")
                    (throw (ex-info "request timed out" {:type :dk.simongray.podcast.http/timeout}))

                    (str/starts-with? url "https://closed.example/")
                    (throw (ex-info "Connection refused" {}))

                    (str/starts-with? url "https://mastodon.world/users/aelmahmoudy/statuses")
                    ((server answers (atom [])) request)

                    (str/starts-with? url "https://mastodon.world/")
                    {:status 429 :url url}

                    :else
                    ((server answers (atom [])) request)))]
    (run #(async/then
           (read-thread "https://framatube.org/w/9dRFC6Ya11NCVeYKn8ZhiD" answers log :send send :concurrency 1)
           (fn [thread]
             (is (= ["https://slow.example/objects/1"] (filter (fn [url] (str/starts-with? url "https://slow.example")) @log))
                 "a server that did not answer in time is not asked again")
             (is (= ["https://closed.example/objects/1" "https://closed.example/objects/2"]
                    (filter (fn [url] (str/starts-with? url "https://closed.example")) @log))
                 "a server that refused a connection is, since that costs no time")
             (is (= ["https://mastodon.world/users/aelmahmoudy"] (filter (fn [url] (str/ends-with? url "/users/aelmahmoudy")) @log))
                 "and one that answered 429 is not")
             (is (= ["Framasoft" [nil ["Framasoft"]]] (shape (comments/tree thread)))
                 "the comments of servers that answered, with an author that could not be read")
             (is (= [:comments/incomplete :comments/unreachable :comments/unreachable :comments/unreachable :comments/unreachable]
                    (sort (ids thread)))
                 "the post left on the server that did not answer is still to read"))))))

(deftest a-server-that-answers-again
  (let [top     "https://a.example/1"
        note    (fn [id] {"id" id "type" "Note" "content" "x" "inReplyTo" top})
        answers {top                   (assoc (note top) "inReplyTo" nil
                                              "replies" {"type"  "Collection"
                                                         "items" ["https://b.example/1" "https://b.example/2" "https://c.example/gone"]})
                 "https://b.example/1" (note "https://b.example/1")
                 "https://b.example/2" (note "https://b.example/2")}
        busy?   (atom true)
        log     (atom [])
        send    (fn [{:keys [url] :as request}]
                  (if (and @busy? (= "https://b.example/1" url))
                    (do (swap! log conj url) {:status 429 :url url})
                    ((server answers log) request)))]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri top} {:send send :concurrency 1})
           (fn [busy]
             (is (= [{:id :comments/unreachable :status 429 :retry? true} {:id :comments/incomplete}]
                    (summary busy :id :status :retry?)))
             (is (= [top "https://b.example/1" "https://c.example/gone"] @log)
                 "a server that answered 429 gets no more requests in the same fetch")
             (reset! busy? false)
             (reset! log [])
             (async/then
              (comments/fetch! busy {:send send :concurrency 1})
              (fn [thread]
                (is (= ["https://b.example/1" "https://b.example/2"] @log)
                    "the next fetch asks it again, and it asks no server again that answered 404")
                (is (= [] (:report thread)))
                (is (= 4 (count (filter :read-at (vals (:comments thread)))))))))))))

(deftest a-page-that-failed-for-a-time
  (let [top     "https://a.example/1"
        page    #(str "https://a.example/replies?page=" %)
        reply   #(str "https://a.example/r" %)
        note    (fn [id] {"id" id "type" "Note" "content" "x" "inReplyTo" top})
        answers (into {top (assoc (note top) "inReplyTo" nil "replies" (page 0))}
                      (mapcat (fn [n]
                                [[(page n) (cond-> {"id" (page n) "type" "CollectionPage" "items" [(reply n)]}
                                             (< n 2) (assoc "next" (page (inc n))))]
                                 [(reply n) (note (reply n))]]))
                      (range 3))
        down?   (atom true)
        log     (atom [])
        send    (fn [{:keys [url] :as request}]
                  (if (and @down? (= (page 1) url))
                    (do (swap! log conj url) {:status 503 :url url})
                    ((server answers log) request)))]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri top} {:send send :concurrency 1})
           (fn [cut]
             (is (= [{:id :comments/unreachable :url (page 1) :status 503 :retry? true} {:id :comments/incomplete}]
                    (summary cut :id :url :status :retry?)))
             (is (= [top (page 0) (page 1) (reply 0)] @log))
             (reset! down? false)
             (reset! log [])
             (async/then
              (comments/fetch! cut {:send send :concurrency 1})
              (fn [thread]
                (is (= [(page 1) (page 2) (reply 1) (reply 2)] @log)
                    "the next fetch reads on from the page that failed")
                (is (= [] (:report thread)))
                (is (= #{top (reply 0) (reply 1) (reply 2)} (set (keys (:comments thread))))))))))))

(deftest what-a-later-fetch-does-not-ask-again
  (let [log   (atom [])
        send  (fn [{:keys [url]}]
                (swap! log conj url)
                (throw (ex-info "The answer is too large" {:type :dk.simongray.podcast.http/too-large})))
        fetch #(comments/fetch! % {:send send})]
    (run #(async/then
           (async/then (fetch {:protocol "activitypub" :uri "https://a.example/1"}) fetch)
           (fn [thread]
             (is (= ["https://a.example/1"] @log) "an answer that is too large is as large the next time")
             (is (= [{:id :comments/unreachable}] (summary thread :id :retry?)))))
         #(async/then
           (async/then (comments/fetch! {:protocol "activitypub" :uri "http://127.0.0.1:1/notes/1"}) comments/fetch!)
           (fn [thread]
             (is (= [{:id :comments/unreachable}] (summary thread :id :retry?))
                 "and a URL that the guard refused is refused the next time"))))))

(deftest a-root-that-did-not-answer-in-time
  (let [top   "https://a.example/1"
        slow? (atom true)
        log   (atom [])
        send  (fn [{:keys [url] :as request}]
                (if @slow?
                  (do (swap! log conj url)
                      (throw (ex-info "request timed out" {:type :dk.simongray.podcast.http/timeout})))
                  ((server {top {"id" top "type" "Note" "content" "root"}} log) request)))]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri top} {:send send})
           (fn [late]
             (is (= [{:id :comments/unreachable :timeout? true :retry? true} {:id :comments/incomplete}]
                    (summary late :id :timeout? :retry?)))
             (reset! slow? false)
             (async/then
              (comments/fetch! late {:send send})
              (fn [thread]
                (is (= [top top] @log))
                (is (= [] (:report thread)))
                (is (= top (:root thread))))))))))

(deftest a-limit-of-comments-for-each-fetch
  (let [top     "https://a.example/0"
        reply   #(str "https://a.example/" %)
        note    (fn [id] {"id" id "type" "Note" "content" "x" "inReplyTo" top})
        answers (into {top (assoc (note top) "inReplyTo" nil "replies" {"type" "Collection" "items" (mapv reply (range 1 13))})}
                      (map (fn [n] [(reply n) (note (reply n))]))
                      (range 1 13))
        log     (atom [])
        fetch   #(comments/fetch! % {:send (server answers log) :max-comments 5})
        read    #(count (filter :read-at (vals (:comments %))))]
    (run #(async/then
           (fetch {:protocol "activitypub" :uri top})
           (fn [one]
             (async/then
              (fetch one)
              (fn [two]
                (async/then
                 (fetch two)
                 (fn [three]
                   (is (= [5 10 13] (map read [one two three])) "each fetch reads five comments more")
                   (is (= [[:comments/incomplete] [:comments/incomplete] []] (map ids [one two three])))
                   (is (= 13 (count @log)) "and each post once"))))))))))

(deftest replies-that-a-limit-cuts
  (let [top    "https://a.example/0"
        at     #inst "2026-09-28T12:00:00Z"
        note   (fn [i] {"id" (str "https://a.example/" i) "type" "Note" "content" "x" "inReplyTo" top
                        "to" ["https://www.w3.org/ns/activitystreams#Public"]})
        read   (fn [items]
                 (let [thread {:protocol "activitypub" :uri top}
                       doc    (assoc (note 0) "inReplyTo" nil "replies" {"type" "Collection" "items" items})]
                   (comments/with-exchanges thread
                                            [{:request  (first (comments/requests thread))
                                              :response {:status 200 :url top :body (json/write-str doc)}}]
                                            at)))
        cut    (fn [thread opts] (map (juxt :id :limit :count :level) (comments/report thread opts)))]
    (testing "a page of more than a thousand items"
      (let [thread (read (mapv note (range 1 1501)))]
        (is (= 1001 (count (:comments thread))))
        (is (= [[:comments/truncated :page 500 :warning]] (cut thread {})))))
    (testing "an id of more than 2,048 characters"
      (let [thread (read [(note 1) (str "https://a.example/" (apply str (repeat 2049 "x"))) (note 2)])]
        (is (= 3 (count (:comments thread))))
        (is (= [[:comments/truncated :id 1 :warning]] (cut thread {})))))
    (testing "more comments than a thread holds"
      (let [thread (with-redefs [common/limits (assoc common/limits :thread 3)]
                     (read (mapv note (range 1 8))))]
        (is (= 3 (count (:comments thread))))
        (is (= [[:comments/truncated :thread 5 :warning]] (cut thread {})))))
    (testing "replies of an AT Protocol thread below the last level, and a reply that a block keeps back"
      (let [uri    #(str "at://did:plc:" % "/app.bsky.feed.post/1")
            view   (fn [did & replies]
                     {"$type"   "app.bsky.feed.defs#threadViewPost"
                      "post"    {"uri" (uri did) "author" {"did" (str "did:plc:" did)} "record" {"text" did}}
                      "replies" (vec replies)})
            doc    {"thread" (view "a"
                                   (view "b" (view "c" (view "d" (view "e"))))
                                   {"$type" "app.bsky.feed.defs#blockedPost" "uri" (uri "w") "blocked" true})}
            url    "https://appview.example/xrpc/app.bsky.feed.getPostThread?uri=a"
            thread (with-redefs [common/limits (assoc common/limits :depth 2)]
                     (comments/with-exchanges {:protocol "atproto" :uri (uri "a")}
                                              [{:request  {:url url ::comments/for [:thread (uri "a")]}
                                                :response {:status 200 :url url :body (json/write-str doc)}}]
                                              at))]
        (is (= #{(uri "a") (uri "b") (uri "c")} (set (keys (:comments thread)))))
        (is (= [[:comments/withheld nil 1 :info] [:comments/truncated :depth 1 :warning]]
               (cut thread {:appview "https://appview.example"})))))))

(deftest a-page-on-another-server
  (let [at     #inst "2026-09-28T12:00:00Z"
        actor  "https://b.example/users/mallory"
        person {"id" actor "type" "Person" "preferredUsername" "mallory" "name" "Dave Jones"}
        author (fn [url]
                 (-> (comments/with-exchanges {:protocol "activitypub" :uri "https://a.example/1" :root "https://a.example/1" :read-at at}
                                              [{:request  {:url actor ::comments/for [:author actor]}
                                                :response {:status 200 :url actor :body (json/write-str (assoc person "url" url))}}]
                                              at)
                     (get-in [:authors actor])
                     (select-keys [:name :handle :url])))]
    (is (= {:name "Dave Jones" :handle "@mallory@b.example" :url actor} (author "https://podcastindex.social/@dave"))
        "an author links to no page on another server, so its name does not lead to the page of another person")
    (is (= "https://b.example/@mallory"
           (:url (author ["https://podcastindex.social/@dave"
                          {"type" "Link" "mediaType" "text/html" "href" "https://b.example/@mallory"}])))
        "the first page on its own server")
    (is (= ["https://a.example/@ann/2" "https://a.example/notes/2"]
           (map #(activitypub/web-page {"id" "https://a.example/notes/2" "type" "Note" "url" %})
                ["https://a.example/@ann/2" "https://other.example/@ann/2"]))
        "and so for a post")
    (testing "and the Mastodon API"
      (let [page   "https://a.example/@ann/1"
            api    "https://a.example/api/v1/statuses/1"
            status {"id" "1" "uri" "https://a.example/users/ann/statuses/1" "url" "https://other.example/@ann/1"
                    "content" "<p>root</p>" "visibility" "public"
                    "account" {"acct" "ann" "display_name" "Ann" "uri" "https://a.example/users/ann" "url" "https://other.example/@ann"}}
            send   (fn [{:keys [url]}]
                     (condp = url
                       page                 {:status 401 :url url}
                       api                  {:status 200 :url url :body (json/write-str status)}
                       (str api "/context") {:status 200 :url url :body (json/write-str {"ancestors" [] "descendants" []})}))]
        (run #(async/then
               (comments/fetch! {:protocol "activitypub" :uri page} {:send send})
               (fn [thread]
                 (is (= ["Ann"] (shape (comments/tree thread))))
                 (is (= [nil nil] [(:url (get-in thread [:comments (:root thread)]))
                                   (:url (get-in thread [:authors "https://a.example/users/ann"]))])))))))))

(deftest a-warning-on-an-account
  (let [at    #inst "2026-09-28T12:00:00Z"
        uri   #(str "at://did:plc:" % "/app.bsky.feed.post/1")
        post  (fn [did labels]
                {"$type"   "app.bsky.feed.defs#threadViewPost"
                 "replies" []
                 "post"    {"uri" (uri did) "author" {"did" (str "did:plc:" did) "labels" labels} "record" {"text" "hello"}}})
        doc   {"thread" (assoc (post "x" [])
                               "replies" [(post "y" [{"val" "!warn" "src" "did:plc:labeler"}])
                                          (post "z" [{"val" "!warn" "src" "did:plc:z"}])
                                          (post "n" [{"val" "porn" "src" "did:plc:labeler" "neg" true}])])}
        url   "https://appview.example/xrpc/app.bsky.feed.getPostThread?uri=x"
        read  (comments/with-exchanges {:protocol "atproto" :uri (uri "x")}
                                       [{:request  {:url url ::comments/for [:thread (uri "x")]}
                                         :response {:status 200 :url url :body (json/write-str doc)}}]
                                       at)]
    (is (= [{:sensitive? true :labels #{"!warn"}} {} {}]
           (map #(select-keys (get-in read [:comments (uri %)]) [:sensitive? :labels]) ["y" "z" "n"]))
        "a warning that a moderation service put on the account, not one of the author, and not a label that is taken back")))

(deftest an-answer-that-exhausts-a-reader
  (let [deep (str (apply str (repeat 100000 "[")) (apply str (repeat 100000 "]")))
        long (str "[" (apply str (repeat 2000 "7")) "]")]
    (run #(async/then
           (async/all (for [body [deep long]]
                        (comments/fetch! {:protocol "activitypub" :uri "https://a.example/1"}
                                         {:send (fn [{:keys [url]}] {:status 200 :url url :body body})})))
           (fn [threads]
             (is (= [[{:id :comments/malformed :url "https://a.example/1"}] [{:id :comments/malformed :url "https://a.example/1"}]]
                    (map (fn [thread] (summary thread :id :url)) threads))
                 "JSON nested too deep, and a number of thousands of digits"))))))

(deftest the-network-of-the-app
  (let [asked (atom [])
        send  (fn [request]
                (swap! asked conj request)
                {:status 404 :url (:url request)})
        note  {:protocol "activitypub" :uri "https://a.example/notes/1"}]
    (run #(async/then
           (comments/fetch! {:protocol "activitypub" :uri "http://127.0.0.1:1/notes/1"})
           (fn [thread]
             (is (= [:comments/unreachable] (ids thread)))
             (is (str/includes? (:reason (first (:report thread))) "is not allowed")
                 "no request goes to it, unless the app allows it")))
         #(async/then
           (async/all [(comments/fetch! note {:send send})
                       (comments/fetch! note {:send send :allow? any?})])
           (fn [_]
             (is (= #{comments/public? any?} (set (map :allow? @asked)))
                 "each request carries the guard, which a :send gets too")
             (is (= [true false false false]
                    (map comments/public? ["https://93.184.216.34/" "http://127.0.0.1/" "wss://127.0.0.1" "ws://localhost:7777"]))
                 "the guard of a server and of a relay"))))))

(deftest a-post-that-does-not-read
  (let [root   "https://a.example/0"
        at     #inst "2026-09-28T12:00:00Z"
        note   (fn [i content]
                 {"id" (str "https://a.example/" i) "type" "Note" "content" content "inReplyTo" root
                  "to" ["https://www.w3.org/ns/activitystreams#Public"]})
        values (apply str (repeat 5000 "a=\"b\" "))
        doc    (assoc (note 0 "root")
                      "inReplyTo" nil
                      "replies" {"type"  "Collection"
                                 "items" [(note 1 "one") (note 2 (str "<a " values ">two</a>")) (note 3 "boom")]})
        thread {:protocol "activitypub" :uri root}
        read   (fn []
                 (comments/with-exchanges thread
                                          [{:request  (first (comments/requests thread))
                                            :response {:status 200 :url root :body (json/write-str doc)}}]
                                          at))
        hiccup html/hiccup
        shown  (fn [t] (map (juxt :content (comp :id :error)) (map (:comments t) (map #(str "https://a.example/" %) (range 4)))))]
    (testing "a tag with thousands of quoted values, which is a post like any other"
      (is (= [["<p>root</p>" nil] ["<p>one</p>" nil] ["<a rel=\"nofollow ugc\">two</a>" nil] ["<p>boom</p>" nil]]
             (shown (read)))))
    (testing "the posts around it are read, and the report names it"
      (let [t (with-redefs [html/hiccup (fn [s] (if (= "boom" s) (throw (ex-info "It does not read" {})) (hiccup s)))]
                (read))]
        (is (= [["<p>root</p>" nil] ["<p>one</p>" nil] ["<a rel=\"nofollow ugc\">two</a>" nil] [nil :comments/malformed]]
               (shown t)))
        (is (= [[:comments/malformed "https://a.example/3" "It does not read"]]
               (map (juxt :id :url :reason) (comments/report t))))))))

(deftest a-thread-of-the-at-protocol
  (let [log (atom [])]
    (run #(async/then
           (comments/fetch! {:protocol "atproto" :uri bluesky-root} {:send (appview bluesky log) :appview "https://appview.example/"})
           (fn [thread]
             (is (= ["AT Protocol Developers"
                     ["fry69" ["Jim Ray" ["surfdude29" ["surfdude29" ["Alex" ["surfdude29" ["Alex" ["surfdude29"]]]]]]]]
                     ["JP" ["npmx"]]]
                    (shape (comments/tree thread)))
                 "the reply that the root's author hid, the withheld one and the one that is gone are left out")
             (is (= "jack" (get-in (comments/tree thread {:hidden? true}) [:replies 1 :author :name])))
             (is (= [{:id :comments/withheld :count 1}] (summary thread :id :count)))
             (is (= [bluesky-root
                     "at://did:web:fry69.dev/app.bsky.feed.post/3msydt4s5v22m"
                     "at://did:plc:sflxm2fxohaqpfgahgdlm7rl/app.bsky.feed.post/3msymygw7gs2n"
                     "at://did:plc:vmt7o7y6titkqzzxav247zrn/app.bsky.feed.post/3msyraczans2o"]
                    @log)
                 "the root, then each branch that the AppView cut, nearest the root first")
             (let [jp (get-in thread [:comments "at://did:plc:ephkzpinhaqcabtkugtbzrwu/app.bsky.feed.post/3msyinaxspk2k"])]
               (is (= {:content    "<p>😎<br>/cc @npmx.dev</p>"
                       :language   "en"
                       :sensitive? true
                       :labels     #{"graphic-media"}}
                      (select-keys jp [:content :language :sensitive? :labels])))
               (is (= {:kind         :image
                       :alt          "Screenshot from the page: “The TypeScript SDK is available from npm, including npmx.”"
                       :aspect-ratio [1179 229]}
                      (select-keys (first (:attachments jp)) [:kind :alt :aspect-ratio :width :height]))
                   "the ratio of an image, which the protocol gives in place of its size"))
             (is (str/ends-with? (get-in thread [:comments "at://did:web:fry69.dev/app.bsky.feed.post/3msydt4s5v22m" :content])
                                 "-&gt; <a href=\"https://bsky.network/\" rel=\"nofollow ugc\">bsky.network</a></p>")
                 "a link as its facet marks it, after text with characters beyond ASCII")
             (is (:deleted? (get-in thread [:comments "at://did:plc:ewvi7nxzyoun6zhxrhs64oiz/app.bsky.feed.post/3msyzzzzzzz2z"])))
             (is (= {:name "AT Protocol Developers" :handle "@atproto.com"}
                    (select-keys (get-in thread [:authors "did:plc:ewvi7nxzyoun6zhxrhs64oiz"]) [:name :handle]))))))))

(deftest a-post-with-a-video
  (run #(async/then
         (comments/fetch! {:protocol "atproto" :uri "at://did:plc:pzznkrzajsaqev3ffamg3jnr/app.bsky.feed.post/3mvzl4tmue22y"}
                          {:send (appview bluesky (atom [])) :appview "https://appview.example"})
         (fn [thread]
           (is (= ["Podnews podcasting news"] (shape (comments/tree thread)))
               "its one reply is by an author who wants no reader without an account to see it")
           (is (= [{:id :comments/withheld :count 1}] (summary thread :id :count)))
           (is (= {:kind :video :media-type "application/vnd.apple.mpegurl" :aspect-ratio [1080 1080]}
                  (select-keys (first (get-in thread [:comments (:root thread) :attachments])) [:kind :media-type :aspect-ratio])))
           (is (str/ends-with? (get-in thread [:comments (:root thread) :content])
                               (str "<br><br><a href=\"https://podnews.net/update/engaged-views-only\" rel=\"nofollow ugc\">"
                                    "podnews.net/update/engag...</a></p>"))
               "the text of the link as the post shortens it, and its target whole")))))

(deftest a-root-that-the-appview-does-not-give
  (let [gone {bluesky-root {"thread" {"$type" "app.bsky.feed.defs#notFoundPost" "uri" bluesky-root "notFound" true}}}]
    (run #(async/then
           (async/all [(comments/fetch! {:protocol "atproto" :uri bluesky-root} {:send (appview gone (atom [])) :appview "https://a.example"})
                       (comments/fetch! {:protocol "atproto" :uri bluesky-root} {:send (appview {} (atom [])) :appview "https://a.example"})])
           (fn [threads]
             (is (= [[{:id :comments/unreachable :reason "The post is gone or blocked"}]
                     [{:id :comments/unreachable :status 400 :reason "Post not found"}]]
                    (map (fn [thread] (summary thread :id :status :reason)) threads))))))))

(deftest the-request-to-an-appview
  (let [[request] (comments/requests {:protocol "atproto" :uri bluesky-root} {:appview "https://appview.example/"})]
    (is (= (str "https://appview.example/xrpc/app.bsky.feed.getPostThread?uri="
                "at%3A%2F%2Fdid%3Aplc%3Aewvi7nxzyoun6zhxrhs64oiz%2Fapp.bsky.feed.post%2F3msydg6sd7s2d&depth=10&parentHeight=0")
           (:url request)))))

(deftest the-text-of-a-post
  (let [link (fn [start end uri] {"index" {"byteStart" start "byteEnd" end}
                                  "features" [{"$type" "app.bsky.richtext.facet#link" "uri" uri}]})
        html (fn [text facets] (atproto/post-html {"text" text "facets" facets}))]
    (is (= (str "<p>🔴 LIVE <a href=\"https://stream.place/atproto.com\" rel=\"nofollow ugc\">https://stream.place/atproto.com</a>"
                " Off Protocol LIVE</p>")
           (html "🔴 LIVE https://stream.place/atproto.com Off Protocol LIVE"
                 [(link 10 42 "https://stream.place/atproto.com")]))
        "the facets count UTF-8 bytes, ten of them before the link")
    (is (= "<p>a<br>b &amp; &lt;c&gt;</p>" (html "a\nb & <c>" [(link 0 1 "javascript:alert(1)")]))
        "text is escaped, and a link that is no web page is text")
    (is (= "<p><a href=\"https://a.example\" rel=\"nofollow ugc\">ab</a>cd</p>"
           (html "abcd" [(link 0 2 "https://a.example") (link 1 3 "https://b.example") (link 2 9 "https://c.example")]))
        "a facet that overlaps the one before it, or that runs past the text, is left out")
    (is (= "<p><a href=\"https://a.example\" rel=\"nofollow ugc\">ab</a>cd</p>"
           (html "abcd" [(link 0.0 2.0 "https://a.example")]))
        "an offset as JSON can write it, 2.0")
    (doseq [record [nil {} {"text" nil} {"text" "  "} {"text" 5} "text"]]
      (is (nil? (atproto/post-html record)) (pr-str record)))))

(deftest an-at-uri
  (is (= {:authority "did:plc:x" :collection "app.bsky.feed.post" :rkey "3k"} (atproto/at-uri "at://did:plc:x/app.bsky.feed.post/3k")))
  (is (= {:authority "atproto.com"} (atproto/at-uri "at://atproto.com")))
  (is (nil? (atproto/at-uri "https://bsky.app/profile/atproto.com/post/3k"))))

(deftest the-next-page-of-a-collection
  (let [page    {:method :get :url "https://a.example/replies" ::comments/for [:replies "https://a.example/1"]}
        answer  (fn [next] {:status 200 :url "https://a.example/replies" :body (json/write-str {"type" "CollectionPage" "items" [] "next" next})})
        request (comments/next-page {:request page :response (answer "https://a.example/replies?page=2")})]
    (is (= "https://a.example/replies?page=2" (:url request)))
    (is (nil? (comments/next-page {:request request :response (answer "https://a.example/replies")}))
        "a page that this reading read already ends it")
    (is (nil? (comments/next-page {:request page :response (answer "https://b.example/replies?page=2")}))
        "a page on another server ends it")
    (is (nil? (comments/next-page {:request (assoc page ::comments/for [:comment "https://a.example/2"])
                                   :response (answer "https://a.example/replies?page=2")}))
        "a post has no pages")))

(deftest pages-that-go-round
  (let [page    (fn [n next] {"id" (str "https://a.example/replies?page=" n) "type" "CollectionPage"
                              "items" [(str "https://a.example/" n)] "next" (str "https://a.example/replies?page=" next)})
        answers (merge {"https://a.example/1" {"id" "https://a.example/1" "type" "Note" "content" "root"
                                               "replies" "https://a.example/replies?page=0"}}
                       (into {} (for [n (range 3)] [(str "https://a.example/replies?page=" n) (page n (mod (inc n) 3))])))
        log     (atom [])]
    (run #(async/then
           (read-thread "https://a.example/1" answers log :max-pages 2 :concurrency 1)
           (fn [thread]
             (is (= 3 (count (filter (fn [url] (str/includes? url "?page=")) @log)))
                 "each page once, though the pages go round and a round reads two of them")
             (is (not (some #{:comments/incomplete} (ids thread)))))))))

(deftest what-is-not-read
  (is (comments/readable? {:protocol "activitypub" :uri root}))
  (is (not (comments/readable? {:protocol "atproto" :uri bluesky-root})) "a thread of the AT Protocol needs an AppView")
  (is (not (comments/readable? {:protocol "activitypub" :uri "fuffaweb_italia.bio.link"})))
  (is (not (comments/readable? {:protocol "disabled"})))
  (is (not (comments/readable? {:protocol "nostr" :uri "nostr:nevent1qqs"})))
  (let [sent (atom [])
        send (fn [request] (swap! sent conj request) {:status 500})]
    (run #(async/then
           (async/all (for [[interact opts] [[{:protocol "matrix" :uri "https://matrix.to/#/#podcast:example.org"} {}]
                                             [{:protocol "atproto" :uri bluesky-root} {}]
                                             [{:protocol "activitypub" :uri "fuffaweb_italia.bio.link"} {}]
                                             [{:protocol "atproto" :uri "https://bsky.app/profile/atproto.com/post/3msydg6sd7s2d"}
                                              {:appview "https://appview.example"}]
                                             [{:protocol "nostr" :uri "nostr:nevent1qqs"} {}]
                                             [{:protocol "nostr" :uri "podcast:item:guid:1"} {:relays []}]]]
                        (comments/fetch! interact (assoc opts :send send))))
           (fn [threads]
             (is (= [[:comments/unsupported] [:comments/no-appview] [:comments/malformed] [:comments/malformed]
                     [:comments/malformed] [:comments/no-relay]]
                    (map ids threads)))
             (is (empty? @sent)))))))

(deftest the-thread-of-an-episode
  (let [feed (podcast/parse (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel>"
                                 "<title>T</title>"
                                 "<podcast:socialInteract protocol=\"activitypub\" uri=\"https://a.example/show\"/>"
                                 "<item><title>Both</title><guid>both</guid>"
                                 "<podcast:socialInteract protocol=\"activitypub\" uri=\"https://a.example/2\" priority=\"3\"/>"
                                 "<podcast:socialInteract protocol=\"activitypub\" uri=\"fuffaweb_italia.bio.link\" priority=\"1\"/>"
                                 "<podcast:socialInteract protocol=\"atproto\" uri=\"" bluesky-root "\" priority=\"2\"/></item>"
                                 "<item><title>Quiet</title><guid>quiet</guid>"
                                 "<podcast:socialInteract protocol=\"disabled\"/></item>"
                                 "<item><title>Plain</title><guid>plain</guid></item>"
                                 "</channel></rss>"))
        [both quiet plain] (:rss/items feed)]
    (is (= "https://a.example/2" (:uri (comments/thread feed both))) "the first by priority that is readable")
    (is (= bluesky-root (:uri (comments/thread feed both {:appview "https://appview.example"}))))
    (is (nil? (comments/thread feed quiet)) "the podcaster wants no comments")
    (is (= "https://a.example/show" (:uri (comments/thread feed plain))) "the show's thread for an episode without one")))

#?(:clj
   (def json-value
     "Values of JSON as the answers of servers of comments hold them: the names
     that the readers look for, URLs on two servers, and anything else."
     (let [names  (gen/elements ["id" "type" "items" "orderedItems" "first" "next" "replies" "comments" "context"
                                 "attributedTo" "inReplyTo" "content" "contentMap" "mediaType" "url" "href" "attachment"
                                 "summary" "sensitive" "published" "name" "oneOf" "icon" "preferredUsername" "webfinger"
                                 "object" "thread" "post" "uri" "author" "did" "handle" "record" "text" "facets" "index"
                                 "byteStart" "byteEnd" "features" "$type" "embed" "images" "fullsize" "playlist" "media"
                                 "labels" "val" "src" "neg" "reply" "parent" "descendants" "account" "acct" "to" "cc"
                                 "in_reply_to_id" "media_attachments" "notFound" "replyCount" "threadgate" "record"
                                 "hiddenReplies" "error" "createdAt" "indexedAt" "visibility"])
           values (gen/one-of [(gen/elements ["https://a.example/1" "https://a.example/2" "https://a.example/users/x"
                                              "https://b.example/1" "at://did:plc:x/app.bsky.feed.post/1"
                                              "at://did:plc:x/app.bsky.feed.post/2" "did:plc:x" "Note" "Collection"
                                              "OrderedCollectionPage" "Create" "Announce" "Tombstone" "Person" "Image"
                                              "Document" "text/markdown" "image/png" "app.bsky.feed.defs#threadViewPost"
                                              "app.bsky.richtext.facet#link" "app.bsky.embed.images#view"
                                              "app.bsky.embed.video#view" "app.bsky.embed.recordWithMedia#view"
                                              "!hide" "!warn" "!no-unauthenticated" "porn" "NotFound" "public"
                                              "https://www.w3.org/ns/activitystreams#Public"
                                              "2026-09-19T00:12:37Z" "<p>a <b>b</b></p>" "a\nb" ""])
                               gen/string gen/large-integer gen/boolean (gen/return nil)
                               (gen/double* {:infinite? false :NaN? false})])]
       (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner 0 4) (gen/map names inner {:max-elements 6})]))
                          values))))

#?(:clj
   (defn- some-of
     "A generator of maps with some of the entries whose values `gens`, a map
     of keys to generators, make."
     [gens]
     (gen/fmap #(into {} (keep identity) %)
               (apply gen/tuple (for [[k g] gens]
                                  (gen/one-of [(gen/return nil) (gen/fmap (fn [v] [k v]) g)]))))))

#?(:clj
   (def answers
     "Answers as servers of comments give them, under `:any`: ActivityPub
     posts, collections and actors, threads of an AppView and statuses of the
     Mastodon API, their parts there or not and anything in them, and any
     other JSON; under `:root`, by protocol, an answer that is a root for the
     rest to hang from."
     (let [url    (gen/frequency [[3 (gen/return "https://a.example/1")]
                                  [7 (gen/elements ["https://a.example/2" "https://a.example/3" "https://a.example/users/x"
                                                    "https://a.example/replies" "https://a.example/replies?page=2"
                                                    "https://a.example/context" "https://b.example/1" "https://b.example/users/y"])]])
           at-uri (gen/elements ["at://did:plc:x/app.bsky.feed.post/1" "at://did:plc:x/app.bsky.feed.post/2"
                                 "at://did:plc:y/app.bsky.feed.post/3"])
           text   (gen/one-of [gen/string (gen/elements ["<p>a <b>b</b></p>" "a\nb" "🔴 LIVE x" "" "<script>x</script>"
                                                         "<a href=\"/x\">x</a><img src=\"https://t.example/p\">"])])
           date   (gen/one-of [(gen/elements ["2026-09-19T00:12:37Z" "2026-09-19T00:12:37.839+02:00" "junk"]) gen/string])
           label  (some-of {"val" (gen/elements ["!hide" "!warn" "!no-unauthenticated" "porn" "x"])
                            "src" (gen/elements ["did:plc:x" "did:plc:z"]) "neg" gen/boolean})
           public (gen/elements [["https://www.w3.org/ns/activitystreams#Public"] ["https://a.example/users/x/followers"] []])
           ap     (gen/recursive-gen
                   (fn [inner]
                     (gen/one-of
                      [(some-of {"id" url "type" (gen/elements ["Note" "Tombstone" "Video" "Question" "Create" "Person" "Announce"])
                                 "attributedTo" (gen/one-of [url (gen/vector (gen/one-of [url inner]) 0 3)])
                                 "inReplyTo" url "content" text "contentMap" (gen/map (gen/elements ["en" "da"]) text)
                                 "mediaType" (gen/elements ["text/html" "text/markdown"]) "summary" text
                                 "sensitive" gen/boolean "published" date "url" (gen/one-of [url inner]) "name" text
                                 "replies" (gen/one-of [url inner]) "comments" (gen/one-of [url inner])
                                 "context" (gen/one-of [url inner]) "attachment" (gen/vector inner 0 3)
                                 "oneOf" (gen/vector inner 0 3) "object" inner "preferredUsername" text
                                 "icon" (gen/one-of [url inner]) "to" public "cc" public})
                       (some-of {"id" url
                                 "type" (gen/elements ["Collection" "OrderedCollection" "CollectionPage" "OrderedCollectionPage"])
                                 "items" (gen/vector (gen/one-of [url inner]) 0 4)
                                 "orderedItems" (gen/vector (gen/one-of [url inner]) 0 4)
                                 "first" (gen/one-of [url inner]) "next" (gen/one-of [url inner]) "totalItems" gen/nat})]))
                   (gen/one-of [url json-value]))
           facet  (some-of {"index"    (some-of {"byteStart" (gen/choose -2 40) "byteEnd" (gen/choose -2 40)})
                            "features" (gen/vector (some-of {"$type" (gen/elements ["app.bsky.richtext.facet#link"
                                                                                    "app.bsky.richtext.facet#mention"])
                                                             "uri"   (gen/one-of [url text])})
                                                   0 2)})
           embed  (some-of {"$type"    (gen/elements ["app.bsky.embed.images#view" "app.bsky.embed.video#view"
                                                      "app.bsky.embed.gallery#view" "app.bsky.embed.recordWithMedia#view"])
                            "images"   (gen/vector (some-of {"fullsize" url "thumb" url "alt" text
                                                             "aspectRatio" (some-of {"width" gen/large-integer "height" gen/large-integer})})
                                                   0 2)
                            "playlist" url
                            "media"    json-value})
           post   (some-of {"uri"        at-uri
                            "author"     (some-of {"did" (gen/elements ["did:plc:x" "did:plc:y"]) "handle" text "displayName" text
                                                   "avatar" url "labels" (gen/vector label 0 2)})
                            "record"     (some-of {"text" text "createdAt" date "langs" (gen/vector text 0 2)
                                                   "reply" (some-of {"parent" (some-of {"uri" at-uri})}) "facets" (gen/vector facet 0 3)})
                            "embed"      embed
                            "replyCount" gen/large-integer
                            "indexedAt"  date
                            "labels"     (gen/vector label 0 2)})
           view   (gen/recursive-gen
                   (fn [inner]
                     (some-of {"$type"    (gen/elements ["app.bsky.feed.defs#threadViewPost" "app.bsky.feed.defs#notFoundPost"])
                               "post"     post
                               "replies"  (gen/vector inner 0 3)
                               "uri"      at-uri
                               "notFound" gen/boolean}))
                   json-value)
           gate   (some-of {"record" (some-of {"hiddenReplies" (gen/vector at-uri 0 2)})})
           status (some-of {"id" (gen/elements ["1" "2" "3"]) "uri" url "url" url "in_reply_to_id" (gen/elements ["1" "2"])
                            "content" text "created_at" date "spoiler_text" text "sensitive" gen/boolean "poll" json-value
                            "visibility" (gen/elements ["public" "unlisted" "private"])
                            "account" (some-of {"uri" url "url" url "acct" text "username" text "avatar" url})
                            "media_attachments" (gen/vector (some-of {"type" (gen/elements ["image" "video" "gifv"]) "url" url
                                                                      "meta" json-value})
                                                            0 2)})]
       {:root {"activitypub" (gen/fmap #(assoc (if (map? %) % {}) "id" "https://a.example/1" "type" "Note") ap)
               "atproto"     (gen/fmap (fn [[p replies g]]
                                         {"thread"     {"$type"   "app.bsky.feed.defs#threadViewPost"
                                                        "post"    (assoc p "uri" "at://did:plc:x/app.bsky.feed.post/1")
                                                        "replies" replies}
                                          "threadgate" g})
                                       (gen/tuple post (gen/vector view 0 4) gate))}
        :any  (gen/one-of [ap
                           (some-of {"thread" view "error" (gen/return "NotFound") "message" text "threadgate" gate})
                           status
                           (some-of {"descendants" (gen/vector status 0 4)})
                           json-value])})))

#?(:clj
   (defn- read-at-random
     "The thread of `interact` read with each request answered by the next of
     the `answers`, `[status document]` pairs, in turn, the root by `root`,
     forty requests at most."
     [interact root answers]
     (let [opts   {:appview "https://a.example"}
           at     #inst "2026-09-28T12:00:00Z"
           answer (fn [i {:keys [url]}]
                    (let [[status doc] (if (zero? i) [200 root] (nth answers (mod i (count answers))))]
                      {:status status :url url :body (json/write-str doc)}))]
       (loop [thread interact i 0]
         (let [requests (take 4 (comments/requests thread opts))]
           (if (or (empty? requests) (> i 40))
             thread
             (recur (reduce (fn [thread [j request]]
                              (comments/with-exchanges thread [{:request request :response (answer (+ i j) request)}] at))
                            thread
                            (map-indexed vector requests))
                    (+ i (count requests)))))))))

#?(:clj
   (defspec any-answer-reads 300
     (prop/for-all [protocol (gen/elements ["activitypub" "atproto"])
                    root     (gen/one-of (vals (:root answers)))
                    later    (gen/vector (gen/tuple (gen/frequency [[6 (gen/return 200)] [1 (gen/elements [400 401 404 429 500])]])
                                                    (:any answers))
                                         1 12)]
       (let [interact (if (= "atproto" protocol)
                        {:protocol "atproto" :uri "at://did:plc:x/app.bsky.feed.post/1"}
                        {:protocol "activitypub" :uri "https://a.example/1"})
             thread   (read-at-random interact root later)
             tree     (comments/tree thread {:hidden? true})
             nodes    (when tree (tree-seq :replies :replies tree))]
         (and (map? thread)
              (vector? (comments/report thread {:appview "https://a.example"}))
              (apply distinct? (or (seq (map :id nodes)) [nil]))
              (every? :read-at nodes))))))
