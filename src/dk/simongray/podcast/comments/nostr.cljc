(ns dk.simongray.podcast.comments.nostr
  "Threads of comments on Nostr, read from relays.

  A thread has one of two kinds of root:

  - a note, which podcast:socialInteract names by a nostr: URI of NIP-21
    or by a name of NIP-19, such as nevent1… or note1…
  - the episode or the show itself, by the id that NIP-73 gives it, such
    as podcast:item:guid:…

  No post is the root of the thread of an episode or a show, so its root
  is a comment without content.

  The comments are the text notes of kind 1, with the replies of NIP-10,
  and the comments of kind 1111 of NIP-22. The authors are the events of
  kind 0 of NIP-01, and an event of kind 5 of NIP-09 deletes a comment of
  its author.

  A relay can send anything. So a reading keeps only the events whose
  signatures verify and that pass a filter of the request. A thread
  counts the events that it left out, and the report tells the count.

  The relays are those that the root names, and then the :relays of the
  options, or the default ones. Each relay gets the same questions, and
  an event that two relays give is one comment."
  (:require [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.comments.nostr.impl :as impl]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.nostr.signer :as signer]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]))

(def relays
  "The relays that podcast-clj asks for comments when neither the root of
  a thread nor the options name a relay. The page doc/services.md tells
  when each relay answered."
  ["wss://relay.fountain.fm"
   "wss://relay.damus.io"
   "wss://relay.primal.net"])

(def batch-size
  "The most ids or public keys in one filter of a request. The option
  :batch-size gives another number."
  50)

(defn about
  "The unread thread of the comments about `feed`, or about its `item`, by
  the id that NIP-73 gives a show and an episode, with `opts`. It's nil
  for a show or an episode without a guid.

  The id of a show is podcast:guid: and its podcast:guid, and the :url of
  `opts` is the URL of a feed that declares none. The id of an episode is
  podcast:item:guid: and the guid of the item.

  Such a thread isn't the thread that the podcaster names. When a
  podcast:socialInteract has the protocol disabled, the podcaster wants
  no public comments shown."
  ([feed]
   (about feed nil {}))
  ([feed item]
   (about feed item {}))
  ([feed item {:keys [url] :as opts}]
   (let [guid   (if item (guid/item-guid item) (guid/feed-guid feed url))
         prefix (if item "podcast:item:guid:" "podcast:guid:")]
     (when guid
       {:protocol "nostr" :uri (str prefix guid)}))))

(defn relays-of
  "The relays that a reading of `thread` asks, and that post! gives an
  event to, with `opts`: those that the name of its root gives, and then
  the :relays of `opts`, or relays."
  ([thread]
   (relays-of thread {}))
  ([{:keys [uri] :as thread} opts]
   (vec (distinct (concat (:relays (impl/root-of uri))
                          (keep nostr/relay-url (or (:relays opts) relays)))))))

(defn ^:no-doc readable?
  "Whether the thread of `interact` can be read with `opts`. Its URI must
  name a note or an id of NIP-73, and there must be a relay to ask."
  [interact opts]
  (boolean (and (impl/root-of (:uri interact))
                (seq (relays-of interact opts)))))

(defn ^:no-doc requests
  "The requests that the Nostr `thread` needs next with `opts`, of those
  whose URLs `ask-pred` takes, with one request for each relay at most.

  A relay gets the question for the root first. Then it gets the
  questions for the replies to the comments that the thread has, those
  nearest the root first. Then it gets the questions for their authors,
  and for the profiles that they mention. The requests ask for no more
  comments than the :max-comments of `opts` allows, counted from the
  start of the reading.

  Each request is a request map with the :url of a relay and the :filters
  of NIP-01."
  [{:keys [uri comments] ::comments/keys [held] :as thread} opts ask-pred]
  (let [opts  (merge {:batch-size batch-size} opts)
        fresh (- (count (filter :read-at (vals comments))) (or held 0))
        more? (< fresh (:max-comments opts))
        depth (common/depths thread)]
    (when (impl/root-of uri)
      (->> (relays-of thread opts)
           (filter ask-pred)
           (keep #(impl/next-question thread % opts more? depth))))))

;; NIP-22 (kind 1111), and as the app Fountain writes it (kind 1), for an
;; id of NIP-73; NIP-10 for a note
(defn comment-event
  "The event of a new comment with the text `content` at the root of
  `thread`, with the `opts` below, to post with post!. It has no time and
  no signature yet.

  On a show or an episode, it's a comment of kind 1111 of NIP-22 with the
  id of NIP-73, or with :kind 1 a note with the id in an i tag, as the app
  Fountain writes it. On a note, it's a reply of kind 1 of NIP-10.

  - :kind, 1111 or 1, for a thread about a show or an episode
  - :url, the web page of the episode, a hint for other apps"
  ([thread content]
   (comment-event thread content {}))
  ([{:keys [uri] :as thread} content {:keys [kind url] :or {kind 1111}
                                      :as   opts}]
   (let [{:keys [id external]} (impl/root-of uri)
         hinted (fn [tag v] (cond-> [tag v] url (conj url)))
         k      (some-> external impl/external-kind)]
     (cond
       (and external (= 1 kind))
       {"kind" 1 "content" content "tags" [["k" k] (hinted "i" external)]}

       external
       {"kind"    1111
        "content" content
        "tags"    [(hinted "I" external) ["K" k] (hinted "i" external) ["k" k]]}

       id
       (let [author (get-in thread [:comments id :author])]
         {"kind"    1
          "content" content
          "tags"    (cond-> [(cond-> ["e" id "" "root"] author (conj author))]
                      author (conj ["p" author]))})))))

;; NIP-22: a reply keeps the root scope of its parent
(defn reply-event
  "The event of the text `content` that replies to the comment `id` of
  `thread`, which a reading gave, to post with post!. It has no time and
  no signature yet, and it's nil when the thread has no event of that
  comment.

  A reply to a comment of kind 1111 is one too, with the root scope of its
  parent, as NIP-22 defines it. A reply to a note of kind 1 is a note,
  with the e tags of NIP-10 that name the root of its thread and the note.
  A reply to the root of the thread is comment-event."
  [{:keys [root] :as thread} id content]
  (if (= id root)
    (comment-event thread content)
    (when-let [event (get-in thread [:comments id ::event])]
      (let [author (get event "pubkey")
            scope  #(contains? #{"I" "E" "A" "K" "P"} (first %))]
        (if (= 1111 (get event "kind"))
          {"kind"    1111
           "content" content
           "tags"    (into (filterv scope (get event "tags"))
                           [["e" id "" author] ["k" "1111"] ["p" author]])}
          {"kind"    1
           "content" content
           "tags"    (impl/note-reply-tags event)})))))

;; NIP-09
(defn delete-event
  "The event that asks the relays to delete the comment `id` of `thread`,
  with the text `reason` for its readers, to post with post! and the key
  of its author. It's nil when the thread has no event of that comment."
  ([thread id]
   (delete-event thread id ""))
  ([thread id reason]
   (when-let [event (get-in thread [:comments id ::event])]
     {"kind"    5
      "content" (str reason)
      "tags"    [["e" id] ["k" (str (get event "kind"))]]})))

;; NIP-25: kind 7 for an event, and kind 17 for an id of NIP-73
(defn reaction-event
  "The event of the reaction `content` to the comment `id` of `thread`, to
  post with post!. A + is a like, the reaction without `content`. It's nil
  when the thread has no event of that comment.

  A reaction to the show or the episode at the root of a thread about one
  is of kind 17, with its id of NIP-73."
  ([thread id]
   (reaction-event thread id "+"))
  ([{:keys [uri] :as thread} id content]
   (let [{:keys [external]} (impl/root-of uri)]
     (if (and external (= id external))
       {"kind"    17
        "content" content
        "tags"    [["k" (impl/external-kind external)] ["i" external]]}
       (when-let [event (get-in thread [:comments id ::event])]
         (let [author (get event "pubkey")]
           {"kind"    7
            "content" content
            "tags"    [["e" id "" author] ["p" author]
                       ["k" (str (get event "kind"))]]}))))))

(defn post!
  "Sign `event` with `signer`, and give it to the relays of `thread` with
  `opts`. It gives the signed event and what each relay did with it, or a
  map with a diagnostic under :error. On the JVM it gives the result, and
  in ClojureScript a promise of it.

      {:event  {…}
       :relays {\"wss://relay.example\" {:accepted? true}
                \"wss://other.example\" {:refused \"blocked: …\"}}}

  The event gets the time of now, unless it has one. The relays are those
  that the root of the thread names, and then the :relays of `opts`, or
  the default ones, and each gets the event at once. A result has
  :nostr/not-published under :error when no relay took the event, and the
  error of the signer when it didn't sign. The `opts` are these:

  - :relays, in place of the default relays
  - :send, a function that sends a request map to a relay, in place of
    nostr/send!
  - :allow-pred, a predicate of the URL of a relay, nostr/public? by
    default
  - :timeout-seconds, for each relay
  - :now-fn, a function that gives the instant of now, the clock by default"
  ([thread event signer]
   (post! thread event signer {}))
  ([thread event signer {:keys [send allow-pred now-fn]
                         :or   {send       nostr/send!
                                allow-pred nostr/public?
                                now-fn     time/now}
                         :as   opts}]
   (let [event  (cond-> event
                  (nil? (get event "created_at"))
                  (assoc "created_at" (time/unix-seconds (now-fn))))
         urls   (relays-of thread opts)
         limits (assoc (select-keys opts [:timeout-seconds])
                       :allow-pred allow-pred)
         give!  #(http/try-send! send {:url % :event %2} limits)]
     (async/then
      (signer/sign! signer event)
      (fn [signed]
        (if (:error signed)
          signed
          (async/then
           (async/all (map #(give! % signed) urls))
           (fn [attempts]
             (let [relays (impl/published urls attempts)
                   result {:event signed :relays relays}]
               (if (some :accepted? (vals relays))
                 result
                 (merge result (diagnostics/failed
                                {:id     :nostr/not-published
                                 :relays relays}))))))))))))

(comment
  (def note-name
    "nostr:note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy")

  (impl/root-of note-name)
  (impl/root-of "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f")

  (def episode
    (about {:podcast/guid "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"}
           {:rss/guid {:content "d98d189b-dc7b-45b1-8720-d4b98690f31f"}}))

  (def sample-opts
    {:relays ["wss://relay.example"] :max-comments 100})

  ;; the one request of an unread thread, to the one relay
  (def sample-request
    (first (requests episode sample-opts (constantly true))))

  ;; an answer without events: the thread has its root, and the relay
  ;; gets no more questions
  (def sample-read
    (impl/with-exchanges episode
                         [{:request  sample-request
                           :response {:url       "wss://relay.example"
                                      :events    []
                                      :complete? true}}]
                         #inst "2026-09-29T12:00:00Z"))

  (:comments sample-read)
  (requests sample-read sample-opts (constantly true))

  ;; the content of a note as Hiccup: a mention with its name, and no line
  ;; for the web page of the episode, which the i tag of the note names
  (def ann
    "nostr:npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")

  (def ann-key
    "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")

  (def note
    {"content" (str "Is this " ann " approved? #podcast\n\n"
                    "https://fountain.fm/episode/x")
     "tags"    [["i" "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"
                 "https://fountain.fm/episode/x"]
                ["t" "podcast"]]})

  (nostr/parts note)
  (impl/hiccup note)
  (impl/hiccup note {:names   {ann-key "Ann"}
                     :link-fn (fn [{:keys [uri tag]}]
                                (if uri
                                  (str "https://njump.me/" (subs uri 6))
                                  (str "https://njump.me/t/" tag)))})
  #_.)
