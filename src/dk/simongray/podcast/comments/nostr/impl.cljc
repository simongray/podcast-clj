(ns ^:no-doc dk.simongray.podcast.comments.nostr.impl
  "The reading of Nostr threads behind comments.nostr, and the helpers of
  its events: the questions to relays, the events that they give, and the
  content of a note as parts and Hiccup."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.comments.nostr :as-alias comments.nostr]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def max-hints
  "The most relays that a thread takes from the name of its root."
  3)

(defn root-of
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

(defn- question
  "The request that asks the relay at `url` for the events of `filters`,
  as the `part` of a reading: the root, the replies to some ids, or the
  authors of some public keys."
  [url part filters]
  {:url           url
   :filters       (vec filters)
   ::comments/for (into [(first part) url] (rest part))})

;; NIP-25 (kind 7); NIP-18 (kinds 6 and 16)
(def reaction-kinds
  "The kinds of the events that react to a comment: a reaction, and the
  reposts of a note and of another event."
  [6 7 16])

(defn- root-question
  "The request to the relay at `url` for the `root` of a thread and the
  comments on it, `limit` comments at most for each filter.

  For a note, the comments are the replies of NIP-10 and the comments of
  NIP-22, and an event of NIP-09 can delete the note. The reactions and
  reposts of the note count. For an id of NIP-73, they're the notes and
  the comments with the id in a tag."
  [url {:keys [id external] :as root} limit]
  (question url [:root]
            (if id
              [{"ids" [id]}
               {"kinds" [1 1111] "#e" [id] "limit" limit}
               {"kinds" [1111] "#E" [id] "limit" limit}
               {"kinds" [5] "#e" [id]}
               {"kinds" reaction-kinds "#e" [id] "limit" limit}]
              [{"kinds" [1 1111] "#i" [external] "limit" limit}
               {"kinds" [1111] "#I" [external] "limit" limit}])))

(defn- replies-question
  "The request to the relay at `url` for the replies to the comments
  `ids`, `limit` replies at most, for the events that delete them, and for
  their reactions and reposts."
  [url ids limit]
  (question url [:replies (vec ids)]
            [{"kinds" [1 1111] "#e" (vec ids) "limit" limit}
             {"kinds" [5] "#e" (vec ids)}
             {"kinds" reaction-kinds "#e" (vec ids) "limit" limit}]))

(defn- authors-question
  "The request to the relay at `url` for the authors `public-keys`."
  [url public-keys]
  (question url [:authors (vec public-keys)]
            [{"kinds" [0] "authors" (vec public-keys)}]))

(defn next-question
  "The request that the relay at `url` gets next in the reading of
  `thread` with `opts`, or nil. The reading can ask for more comments when
  `more?`, and `depth` gives how deep each comment is.

  The root comes first, then the replies to the comments nearest the
  root, then the authors. A relay with a failure gets no request."
  [{:keys [uri comments authors] ::comments.nostr/keys [relays] :as thread}
   url
   {:keys [max-comments batch-size] :as opts}
   more?
   depth]
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
                     (mapcat #(cons (:author %) (::comments.nostr/mentions %)))
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

(defn resumed
  "The Nostr `thread` as a new reading of it starts: unread again where a
  failure that can pass with time stopped the last reading. This is the
  question that a relay didn't answer."
  [thread]
  (let [unread #(cond-> % (:retry? (:error %)) (dissoc :error))]
    (cond-> (common/retried thread)
      (::comments.nostr/relays thread)
      (update ::comments.nostr/relays update-vals unread))))

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

(def receipt-kind
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

(defn note-html
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
    (common/present
     (merge {:id          (get event "id")
             :author      (get event "pubkey")
             :in-reply-to in-reply-to
             :published   (time/->inst (* 1000 seconds))
             :content     (note-html event names)
             :language    language
             :sensitive?  (some? warning)
             :summary     (common/first-text (second warning))
             :attachments (attachments event)
             :read-at     at}
            {::comments.nostr/event    event
             ::comments.nostr/mentions (mentions event)
             ::comments.nostr/receipts (receipts event)}))))

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
    (common/present
     (merge {:id      pubkey
             :name    display
             :handle  npub
             :avatar  (common/web-url (get profile "picture"))
             :read-at at}
            {::comments.nostr/created-at
             (json/whole (get event "created_at"))}))))

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
        {::comments.nostr/keys [created-at event-id]}
        (get-in thread [:authors pubkey])]
    (cond-> thread
      (later? event created-at event-id)
      (assoc-in [:authors pubkey]
                (-> (profile-author event at)
                    (assoc ::comments.nostr/event-id (get event "id")))))))

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
                    noted (update-in thread [::comments.nostr/deletions id]
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
  [{:keys [comments] ::comments.nostr/keys [deletions] :as thread}
   event
   root
   at]
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

;; NIP-25: the last e tag names the target, and a content of - is a
;; dislike
(defn- with-reaction
  "The `thread` with the reaction or the repost `event`, kept by the
  comment that its last e tag names, once for each author."
  [thread event]
  (let [target   (last (filter nostr/hex-id? (nostr/tag-values event "e")))
        dislike? (= "-" (str/trim (str (get event "content"))))
        k        (if (= 7 (long (get event "kind")))
                   (when-not dislike? :likes)
                   :reposts)]
    (cond-> thread
      (and target k)
      (update-in [::comments.nostr/reactions target k]
                 (fnil conj #{})
                 (get event "pubkey")))))

(defn- counted
  "The `thread` with the :counts of each comment that has reactions or
  reposts, as the relays gave them."
  [thread]
  (reduce-kv (fn [t id {:keys [likes reposts]}]
               (cond-> t
                 (get-in t [:comments id :read-at])
                 (assoc-in [:comments id :counts]
                           (common/counts {:likes   (count likes)
                                           :reposts (count reposts)}))))
             thread
             (::comments.nostr/reactions thread)))

(defn- with-event
  "The `thread` with the verified `event` read into it at `at` by its kind,
  in a thread whose root is `root`."
  [thread event root at]
  (case (long (get event "kind"))
    0          (with-author thread event at)
    5          (with-deletion thread event at)
    (1 1111)   (with-comment thread event root at)
    (6 7 16)   (with-reaction thread event)
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
                         (= id (::comments.nostr/event-id author)))))
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
        written (fn [{::comments.nostr/keys [event mentions] :as c}]
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
      (update-in t [::comments.nostr/relays url] noted)
      (cond-> t
        (pos? left-out)
        (update ::comments.nostr/left-out (fnil + 0) left-out))
      (reduce #(with-event %1 %2 root at) t ordered)
      (renamed t (changed t))
      (with-root t root at)
      (counted t))))

;; NIP-73: the kind of an id is the id without its guid
(defn external-kind
  "The kind that NIP-73 gives the id `external` of a show, an episode or a
  publisher, e.g. podcast:item:guid."
  [external]
  (str/replace external #":[^:]*$" ""))

;; NIP-10, the marked e tags, and the older positional ones
(defn- note-root
  "The e tag of the root of the thread of NIP-10 that the note `event` is
  in, or nil when the note is the root."
  [event]
  (let [es     (filter #(and (= "e" (first %)) (nostr/hex-id? (second %)))
                       (get event "tags"))
        marker #(nth % 3 nil)]
    (or (first (filter #(= "root" (marker %)) es))
        (when-not (some #(contains? #{"reply" "root" "mention"} (marker %)) es)
          (first es)))))

(defn note-reply-tags
  "The tags of a note of kind 1 that replies to the note `event`, as
  NIP-10 has them: the root and the note, and the authors that the note
  names with its own."
  [event]
  (let [id     (get event "id")
        author (get event "pubkey")
        root   (note-root event)
        root-e (when root
                 (cond-> ["e" (second root) "" "root"]
                   (nostr/hex-id? (nth root 4 nil)) (conj (nth root 4))))
        people (distinct (concat (nostr/tag-values event "p") [author]))]
    (into (if root-e
            [root-e ["e" id "" "reply" author]]
            [["e" id "" "root" author]])
          (map #(vector "p" %))
          people)))

(defn published
  "What the relays at `urls` did with an event, by URL, from the
  `attempts` of giving it to each of them."
  [urls attempts]
  (into {}
        (map (fn [url {:keys [value error]}]
               [url (cond
                      error
                      {:error (async/explain error)}

                      (:accepted? value)
                      {:accepted? true}

                      :else
                      {:refused (or (:refused value)
                                    (:closed value)
                                    "No answer")})])
             urls
             attempts)))
