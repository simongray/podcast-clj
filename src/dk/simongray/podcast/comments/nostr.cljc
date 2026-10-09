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
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]))

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

(def ^:no-doc max-hints
  "The most relays that a thread takes from the name of its root."
  3)

(defn ^:no-doc root-of
  "The root that the URI `uri` of a thread names, or nil for another text.

  For a note, it's a map of the :id and the :relays that its name gives.
  For an id of NIP-73 of a show, an episode or a publisher, it's a map of
  the id as :external."
  [uri]
  (when (string? uri)
    (let [s     (str/trim uri)
          bare  (str/replace s #"(?i)^nostr:" "")
          named (nostr/decode s)]
      (cond
        (contains? #{:note :nevent} (:type named))
        (let [hints (distinct (keep nostr/relay-url (:relays named)))]
          {:id (:id named) :relays (vec (take max-hints hints))})

        (nostr/hex-id? bare)
        {:id bare :relays []}

        (re-matches #"podcast:(?:item:|publisher:)?guid:.+" s)
        {:external s}))))

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

(defn ^:no-doc relays-of
  "The relays that the reading of `thread` asks with `opts`: those that
  the name of its root gives, and then the :relays of `opts`, or the
  default ones."
  [{:keys [uri] :as thread} opts]
  (vec (distinct (concat (:relays (root-of uri))
                         (keep nostr/relay-url (or (:relays opts) relays))))))

(defn readable?
  "Whether the thread of `interact` can be read with `opts`. Its URI must
  name a note or an id of NIP-73, and there must be a relay to ask."
  [interact opts]
  (boolean (and (root-of (:uri interact))
                (seq (relays-of interact opts)))))

(defn- question
  "The request that asks the relay at `url` for the events of `filters`,
  as the `part` of a reading: the root, the replies to some ids, or the
  authors of some public keys."
  [url part filters]
  {:url           url
   :filters       (vec filters)
   ::comments/for (into [(first part) url] (rest part))})

(defn- root-question
  "The request to the relay at `url` for the `root` of a thread and the
  comments on it, `limit` comments at most for each filter.

  For a note, the comments are the replies of NIP-10 and the comments of
  NIP-22, and an event of NIP-09 can delete the note. For an id of
  NIP-73, they're the notes and the comments with the id in a tag."
  [url {:keys [id external] :as root} limit]
  (question url [:root]
            (if id
              [{"ids" [id]}
               {"kinds" [1 1111] "#e" [id] "limit" limit}
               {"kinds" [1111] "#E" [id] "limit" limit}
               {"kinds" [5] "#e" [id]}]
              [{"kinds" [1 1111] "#i" [external] "limit" limit}
               {"kinds" [1111] "#I" [external] "limit" limit}])))

(defn- replies-question
  "The request to the relay at `url` for the replies to the comments
  `ids`, `limit` replies at most, and for the events that delete them."
  [url ids limit]
  (question url [:replies (vec ids)]
            [{"kinds" [1 1111] "#e" (vec ids) "limit" limit}
             {"kinds" [5] "#e" (vec ids)}]))

(defn- authors-question
  "The request to the relay at `url` for the authors `public-keys`."
  [url public-keys]
  (question url [:authors (vec public-keys)]
            [{"kinds" [0] "authors" (vec public-keys)}]))

(defn- next-question
  "The request that the relay at `url` gets next in the reading of
  `thread` with `opts`, or nil. The reading can ask for more comments when
  `more?`, and `depth` gives how deep each comment is.

  The root comes first, then the replies to the comments nearest the
  root, then the authors. A relay with a failure gets no request."
  [{:keys [uri comments authors] ::keys [relays] :as thread} url
   {:keys [max-comments batch-size] :as opts} more? depth]
  (let [{:keys [read-at error replies asked]} (get relays url)
        open?   #(and (:read-at %)
                      (not (:deleted? %))
                      (nostr/hex-id? (:id %)))
        ids     (->> (vals comments)
                     (filter open?)
                     (map :id)
                     (remove (set replies))
                     (sort-by #(vector (get depth % ##Inf) %))
                     (take batch-size))
        asked   (set asked)
        writers (->> (vals comments)
                     (mapcat #(cons (:author %) (::mentions %)))
                     (remove nil?)
                     distinct
                     (remove #(or (contains? authors %) (contains? asked %)))
                     sort
                     (take batch-size))]
    (cond
      error
      nil

      (nil? read-at)
      (when more?
        (root-question url (root-of uri) max-comments))

      (and more? (seq ids))
      (replies-question url ids max-comments)

      (seq writers)
      (authors-question url writers))))

(defn requests
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
    (when (root-of uri)
      (->> (relays-of thread opts)
           (filter ask-pred)
           (keep #(next-question thread % opts more? depth))))))

(defn resumed
  "The Nostr `thread` as a new reading of it starts: unread again where a
  failure that can pass with time stopped the last reading. This is the
  question that a relay didn't answer."
  [thread]
  (let [unread #(cond-> % (:retry? (:error %)) (dissoc :error))]
    (cond-> (common/retried thread)
      (::relays thread) (update ::relays update-vals unread))))

(defn- refusal
  "What went wrong when the relay at `url` gave the answer `response`, as
  a diagnostic without its level, or nil when nothing did.

  A relay closes a subscription with a reason, whose first word NIP-01
  defines, and NIP-42 for auth-required. A relay that wants to know the
  reader gives auth-required or restricted, and podcast-clj has no key to
  tell it. A failure is :retry? when it can pass with time."
  [url {:keys [error closed timeout? events] :as response}]
  (let [[_ word] (when closed (re-matches #"([a-z-]+):.*" closed))]
    (cond
      error
      (common/failure url response)

      (contains? #{"auth-required" "restricted" "blocked"} word)
      {:id :comments/refused :url url :reason closed}

      closed
      (cond-> {:id :comments/unreachable :url url :reason closed}
        (contains? #{"rate-limited" "error"} word) (assoc :retry? true))

      (and timeout? (empty? events))
      {:id       :comments/unreachable
       :url      url
       :reason   "The relay did not answer in time"
       :timeout? true
       :retry?   true})))

(defn- imeta-attachment
  "The attachment that the `fields` of an imeta tag of NIP-92 describe, or
  nil for a file that isn't an image, a video or a sound."
  [fields]
  (let [pairs   (keep #(let [[k v] (str/split % #" " 2)] (when v [k v])) fields)
        {:strs [url m dim alt]} (into {} pairs)
        media   (some->> m (re-matches #"(image|video|audio)/.+") second)
        kind    (some-> media keyword)
        [_ w h] (some->> dim (re-matches #"(\d{1,6})x(\d{1,6})"))]
    (when (and kind (common/web-url url))
      (common/present {:kind       kind
                       :url        url
                       :media-type m
                       :alt        alt
                       :width      (some-> w parse-long common/dimension)
                       :height     (some-> h parse-long common/dimension)}))))

(defn- attachments
  "The media of `event` from its imeta tags of NIP-92, each with its URL,
  media type, size and alt text. A file that isn't an image, a video or a
  sound is left out."
  [event]
  (into []
        (keep (fn [[tag & fields]]
                (when (= "imeta" tag)
                  (imeta-attachment fields))))
        (get event "tags")))

(def ^:no-doc receipt-kind
  "The kind of the event that tells of a payment, the zap receipt of
  NIP-57."
  9735)

(defn- receipt?
  "Whether the part `p` of a content is a reference to the receipt of a
  payment, by the kind that its name tells."
  [p]
  (and (= :reference (:type p))
       (= receipt-kind (:kind (:target p)))))

(defn receipts
  "The ids of the receipts of payments that the content of `event` names.
  A note that came with a payment names the receipt, as the app Fountain
  writes a boost. The name of the receipt tells its kind, 9735 of NIP-57."
  [event]
  (->> (nostr/parts event)
       (keep #(when (receipt? %) (:id (:target %))))
       distinct
       vec))

(defn- mentions
  "The public keys of the profiles that the content of `event` mentions."
  [event]
  (->> (nostr/parts event)
       (keep #(when (= :mention (:type %)) (:public-key %)))
       distinct
       vec))

(defn- restated
  "The URLs that say again what `event` tells in its tags. They're the web
  pages of the things that its i tags of NIP-73 name, and the media of its
  imeta tags, which are its attachments."
  [event]
  (let [hints (for [[tag _ hint] (get event "tags")
                    :when (and (= "i" tag) hint)]
                hint)]
    (set (concat hints (map :url (attachments event))))))

(defn- lines
  "The parts `ps` of a content as its lines, each the parts of one line."
  [ps]
  (reduce (fn [lines {:keys [type text] :as p}]
            (let [last-line (dec (count lines))]
              (if (= :text type)
                (let [[head & tail] (str/split text #"\n" -1)
                      continued     (update lines last-line conj
                                            {:type :text :text head})]
                  (into continued
                        (map (fn [t] [{:type :text :text t}]))
                        tail))
                (update lines last-line conj p))))
          [[]]
          ps))

(defn- shortened
  "The name `s` of NIP-19 in a form that a person reads: its start and its
  end."
  [s]
  (if (< 24 (count s))
    (str (subs s 0 12) "…" (subs s (- (count s) 6)))
    s))

(defn- shown
  "The part `p` of a content with the :text that a reader sees for it, by
  the `names` of profiles by public key.

  A mention also gets its public key as its :id, which is the id of the
  author in a thread."
  [{:keys [type url uri tag public-key] :as p} names]
  (case type
    :text      p
    :link      (assoc p :text url)
    :mention   (let [label (or (get names public-key) (shortened (subs uri 6)))]
                 (assoc p :id public-key :text (str "@" label)))
    :reference (assoc p :text (shortened (subs uri 6)))
    :hashtag   (assoc p :text (str "#" tag))))

(defn- paragraphs
  "The content of `event` as a reader sees it, with the `names` of
  profiles: its paragraphs, each the lines of the paragraph, and each line
  the parts of the line.

  Some lines say again what the tags of the event tell. Such a line has
  only the web page of the episode or the show that the note is about, or
  only the media of an attachment. It's left out, and so is a reference
  to the receipt of a payment."
  [event names]
  (let [again?     (restated event)
        space?     #(and (= :text (:type %)) (str/blank? (:text %)))
        blank?     #(every? space? %)
        repeated?  #(and (= :link (:type %)) (contains? again? (:url %)))
        restating? (fn [line]
                     (and (not (blank? line))
                          (every? #(or (space? %) (repeated? %)) line)))
        shown-line (fn [line] (map #(shown % names) line))]
    (->> (lines (remove receipt? (nostr/parts event)))
         (remove restating?)
         (partition-by blank?)
         (remove (comp blank? first))
         (map #(map shown-line %)))))

(defn parts
  "The parts of the content of `event` as a reader sees it, in their
  order, with the names of `opts`. Each part is a map with its :type and
  the :text that the content shows for it:

      {:type :text :text \"Is this \"}
      {:type :mention :text \"@Ann\" :id \"3bf0…\" :public-key \"3bf0…\"
       :uri \"nostr:npub1…\"}
      {:type :reference :text \"nevent1qqs9…m9d82\" :target {…}
       :uri \"nostr:nevent1…\"}
      {:type :link :text \"https://example.org/\" :url \"https://example.org/\"}
      {:type :hashtag :text \"#podcast\" :tag \"podcast\"}

  The text of a mention is an at sign and the name of the profile, from
  the :names of `opts`, a map from public keys to names. Without a name,
  it's the start and the end of the npub1… name. The text of a reference
  is the start and the end of its name. The lines that say again what the
  tags of the event tell aren't there. A blank line is between two
  paragraphs."
  ([event]
   (parts event {}))
  ([event {:keys [names] :as opts}]
   {:pre [(map? opts)]}
   (let [break (fn [s] [{:type :text :text s}])]
     (common/joined (->> (paragraphs event names)
                         (map #(apply concat (interpose (break "\n") %)))
                         (interpose (break "\n\n"))
                         (apply concat))))))

(defn hiccup
  "The content of `event` as Hiccup by `opts`: a paragraph for each group
  of lines, in the order of the text. The result is a sequence of nodes
  to put inside a parent element.

  NIP-10 and NIP-22 give a note plain text, and NIP-27 tells what its
  nostr: URIs are. In the Hiccup:

  - A web URL is a link.
  - A mention of a profile is an at sign and the name of the profile from
    the :names of `opts`, a map from public keys to names. Without a
    name, it's the start and the end of the npub1… name.
  - A reference to an event is the start and the end of its name.
  - A hashtag is the word with its number sign.

  The :link-fn of `opts` is a function of a part. It gives the URL that a
  mention, a reference or a hashtag links to, or nil for no link. No web
  page is named for them, since you choose the web app or the scheme that
  shows them.

  Some lines say again what the tags of the event tell. Such a line has
  only the web page of the episode or the show that the note is about, or
  only the media of an attachment. It's left out, and so is a reference
  to the receipt of a payment."
  ([event]
   (hiccup event {}))
  ([event {:keys [names] :as opts}]
   {:pre [(map? opts)]}
   (let [node (fn [{:keys [type text url] :as p}]
                (case type
                  :text text
                  :link (common/user-link url text)
                  (common/linked p text opts)))]
     (apply list (for [paragraph (paragraphs event names)]
                   (into [:p {}]
                         (comp (interpose [[:br {}]]) cat (remove #{""}))
                         (map #(map node %) paragraph)))))))

(defn names
  "The names of the authors that `thread` has, by public key, e.g. for the
  :names of the options."
  [thread]
  (into {}
        (keep (fn [[k {:keys [name]}]] (when name [k name])))
        (:authors thread)))

(defn ^:no-doc note-html
  "The content of `event` as sanitized HTML, with the `names` of profiles,
  or nil for a content that says nothing. A mention, a reference and a
  hashtag have no link."
  [event names]
  (when-let [paragraphs (seq (hiccup event {:names names}))]
    (html/sanitize paragraphs)))

(defn- parent
  "The id of what `event` replies to in a thread whose root is `root`, or
  nil for an event that replies to nothing of the thread.

  The parent of a note of kind 1 is the e tag with the marker reply, or
  else with the marker root, as NIP-10 defines. Without a marker, it's the
  last e tag, which is the older form of NIP-10. The parent of a comment
  of kind 1111 is its e tag, as NIP-22 defines. An event without such a
  tag replies to the root when it names the id of the root in an i tag,
  as NIP-73 defines."
  [event {:keys [external] :as root}]
  (let [e-tag?  #(and (= "e" (first %)) (nostr/hex-id? (second %)))
        es      (filter e-tag? (get event "tags"))
        marker  #(nth % 3 nil)
        marked  (fn [m] (second (last (filter #(= m (marker %)) es))))
        markers #{"reply" "root" "mention"}]
    (or (if (= 1111 (get event "kind"))
          (second (first es))
          (or (marked "reply")
              (marked "root")
              (when-not (some #(contains? markers (marker %)) es)
                (second (last es)))))
        (when (and external (some #{external} (nostr/tag-values event "i")))
          external))))

;; NIP-36 (content-warning); NIP-32 (the l tag of ISO-639-1)
(defn- note-comment
  "The note or the comment `event` as a comment read at `at`, in reply to
  `in-reply-to`, with the `names` of profiles in its content.

  The comment keeps the event, so that an app can write the content in
  its own way. It also keeps the profiles that the content
  mentions, and the receipts of payments that it names."
  [event in-reply-to names at]
  (let [tags     (get event "tags")
        warning  (first (filter #(= "content-warning" (first %)) tags))
        iso-639? (fn [[tag _ kind]] (and (= "l" tag) (= "ISO-639-1" kind)))
        language (some #(when (iso-639? %) (second %)) tags)
        seconds  (json/whole (get event "created_at"))]
    (common/present {:id          (get event "id")
                     :author      (get event "pubkey")
                     :in-reply-to in-reply-to
                     :published   (time/->inst (* 1000 seconds))
                     :content     (note-html event names)
                     :language    language
                     :sensitive?  (some? warning)
                     :summary     (common/first-text (second warning))
                     :attachments (attachments event)
                     :read-at     at
                     ::event      event
                     ::mentions   (mentions event)
                     ::receipts   (receipts event)})))

;; NIP-01 (kind 0: name, picture); NIP-24 (display_name)
(defn- profile-author
  "The `event` of kind 0 as an author read at `at`. It has the name and the
  picture of the content, and the public key as its handle, in the form
  npub1… of NIP-19.

  The content names an address of NIP-05 too. Since podcast-clj doesn't
  check it, it isn't a handle."
  [event at]
  (let [profile (json/object (get event "content"))
        pubkey  (get event "pubkey")
        display (common/first-text (get profile "display_name")
                                   (get profile "name"))
        npub    (nostr/encode {:type :npub :public-key pubkey})]
    (common/present {:id          pubkey
                     :name        display
                     :handle      npub
                     :avatar      (common/web-url (get profile "picture"))
                     :read-at     at
                     ::created-at (json/whole (get event "created_at"))})))

(defn- later?
  "Whether `event` takes the place of an event of the time `created-at`
  and the id `id`: it's later, or of the same time with a lower id.
  NIP-01 defines this for events that replace another."
  [event created-at id]
  (let [at (json/whole (get event "created_at"))]
    (or (nil? created-at)
        (> at created-at)
        (and (= at created-at) (neg? (compare (get event "id") (str id)))))))

(defn- with-author
  "The `thread` with the author of the `event` of kind 0 read at `at`,
  unless the thread has a later one."
  [thread event at]
  (let [pubkey (get event "pubkey")
        {::keys [created-at event-id]} (get-in thread [:authors pubkey])]
    (cond-> thread
      (later? event created-at event-id)
      (assoc-in [:authors pubkey]
                (-> (profile-author event at)
                    (assoc ::event-id (get event "id")))))))

(defn- deleted
  "The comment `c` as a deleted comment read at `at`."
  [c at]
  (assoc (select-keys c [:id :in-reply-to]) :deleted? true :read-at at))

(defn- with-deletion
  "The `thread` with the `event` of kind 5 read into it at `at`.

  The event deletes the comments of its author that its e tags name, as
  NIP-09 defines. The thread keeps what the event names, for a comment
  that comes later."
  [thread event at]
  (let [author (get event "pubkey")]
    (reduce (fn [thread id]
              (let [c     (get-in thread [:comments id])
                    own?  (and c (= author (:author c)))
                    noted (update-in thread [::deletions id]
                                     (fnil conj #{}) author)]
                (cond-> noted
                  own? (assoc-in [:comments id] (deleted c at)))))
            thread
            (filter nostr/hex-id? (nostr/tag-values event "e")))))

(defn- with-comment
  "The `thread` with the note or the comment `event` read into it at `at`,
  in a thread whose root is `root`.

  The root of a thread of a note is the note. Another event must reply to
  something of the thread. An event that its author deleted is a deleted
  comment. The thread holds no more comments than its size limit allows,
  and counts the comments that the limit cut."
  [{:keys [comments] ::keys [deletions] :as thread} event root at]
  (let [id      (get event "id")
        root?   (= id (:id root))
        reply   (when-not root? (parent event root))
        known?  (:read-at (get comments id))
        read    #(note-comment event reply (names thread) at)
        c       (common/guarded id at read)
        gone?   (contains? (get deletions id) (get event "pubkey"))]
    (cond
      known?
      thread

      (not (or root? reply))
      thread

      (>= (count comments) (:thread common/limits))
      (common/truncate thread :thread 1)

      gone?
      (assoc-in thread [:comments id] (deleted c at))

      :else
      (assoc-in thread [:comments id] c))))

(defn- with-event
  "The `thread` with the verified `event` read into it at `at` by its kind,
  in a thread whose root is `root`."
  [thread event root at]
  (case (long (get event "kind"))
    0        (with-author thread event at)
    5        (with-deletion thread event at)
    (1 1111) (with-comment thread event root at)
    thread))

(defn- kept
  "The `events` that the reading of `thread` keeps by `filters`, and the
  number that it leaves out, as a map of :events and :left-out.

  The reading leaves out an event that isn't verified, and one that
  passes none of `filters`. It doesn't check again a comment that the
  thread has."
  [thread events filters]
  (let [known?   (fn [event]
                   (let [id     (get event "id")
                         author (get-in thread [:authors (get event "pubkey")])]
                     (or (:read-at (get-in thread [:comments id]))
                         (= id (::event-id author)))))
        fresh    (remove known? (filter map? events))
        wanted?  #(and (nostr/event? %)
                       (some (fn [f] (nostr/matches? f %)) filters)
                       (nostr/verified? %))
        verified (filterv wanted? fresh)]
    {:events   verified
     :left-out (+ (- (count fresh) (count verified))
                  (count (remove map? events)))}))

(defn- with-root
  "The `thread` with the comment at its `root`, when the reading at `at`
  gave none.

  The root of a thread about a show or an episode is a comment without
  content. A note that no relay gave is a comment with the problem of it."
  [thread {:keys [id external] :as root} at]
  (let [root    (or id external)
        missing {:id     :comments/unreachable
                 :url    (:uri thread)
                 :reason "No relay gave the note"
                 :retry? true}]
    (cond-> (assoc thread :root root :read-at at)
      (not (:read-at (get-in thread [:comments root])))
      (assoc-in [:comments root]
                (if external
                  {:id external :read-at at}
                  {:id id :error missing})))))

(defn- renamed
  "The `thread` with the content of each comment that mentions a profile of
  `changed`, a set of public keys, written again with the names that the
  thread has now."
  [thread changed]
  (let [known   (names thread)
        written (fn [{::keys [event mentions] :as c}]
                  (let [content (when (and event (some changed mentions))
                                  (note-html event known))]
                    (cond-> c content (assoc :content content))))]
    (cond-> thread
      (seq changed) (update :comments update-vals written))))

(defn with-exchanges
  "The Nostr `thread` with the `exchanges` of one of its requests read
  into it at `at`. There's one exchange for the request, and its response
  is the map that nostr/send! gives, or a map of the :error.

  The thread notes what each relay answered, so that a relay gets each
  question once. A relay that didn't answer keeps the problem of it, and
  gets the question again when the problem is :retry?.

  The name of a profile comes after the comment that mentions it. The
  content of the comment then gets the name."
  [{:keys [uri authors] :as thread} [exchange :as exchanges] at]
  (let [{:keys [request response]} exchange
        {:keys [url filters]} request
        [part _ asked] (::comments/for request)
        root    (root-of uri)
        problem (refusal url response)
        {:keys [events left-out]} (kept thread (:events response) filters)
        noted   (fn [relay]
                  (if problem
                    (assoc relay :error problem)
                    (case part
                      :root    (assoc relay :read-at at)
                      :replies (update relay :replies (fnil into #{}) asked)
                      :authors (update relay :asked (fnil into #{}) asked)
                      relay)))
        changed (fn [t]
                  (set (for [[k author] (:authors t)
                             :when (not= author (get authors k))]
                         k)))
        ordered (sort-by #(get % "created_at") events)]
    (as-> thread t
      (update-in t [::relays url] noted)
      (cond-> t (pos? left-out) (update ::left-out (fnil + 0) left-out))
      (reduce #(with-event %1 %2 root at) t ordered)
      (renamed t (changed t))
      (with-root t root at))))

(comment
  (def note-name
    "nostr:note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy")

  (root-of note-name)
  (root-of "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f")

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
    (with-exchanges episode
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
  (hiccup note)
  (hiccup note {:names   {ann-key "Ann"}
                :link-fn (fn [{:keys [uri tag]}]
                           (if uri
                             (str "https://njump.me/" (subs uri 6))
                             (str "https://njump.me/t/" tag)))})
  #_.)
