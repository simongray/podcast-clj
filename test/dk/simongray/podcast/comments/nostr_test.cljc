(ns dk.simongray.podcast.comments.nostr-test
  "Threads of comments on Nostr, read from relays that answer with events
  that the tests sign: the comments about an episode, the thread below a
  note, the replies of NIP-10 and the comments of NIP-22, authors,
  deleted comments, events that are not verified, relays that fail, and
  the limits, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.nostr.secp256k1 :as secp256k1]
            [dk.simongray.podcast.nostr.signer :as signer]
            [dk.simongray.podcast.test-support :refer [fixture run secret-key signed]]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webcrypto.bytes :as bytes]))

(def at #inst "2026-09-29T12:00:00Z")

(def ann (secret-key 1))
(def bob (secret-key 2))
(def eve (secret-key 3))

(def ann-key (secp256k1/public-key ann))
(def bob-key (secp256k1/public-key bob))

(def relay-a "wss://a.relay.example")
(def relay-b "wss://b.relay.example")

(def episode
  "The id that NIP-73 gives the episode of the tests."
  "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f")

(defn id [event] (get event "id"))

(def top-note
  "A note about the episode, with the tags that the app Fountain writes."
  (signed ann {:kind    1
               :at      100
               :content "A good episode:\nhttps://example.org/episode."
               :tags    [["k" "podcast:item:guid"] ["i" episode "https://fountain.fm/episode/x"]
                         ["k" "podcast:guid"] ["i" "podcast:guid:c90e609a-df1e-596a-bd5e-57bcc8aad6cc"]]}))

(def top-comment
  "A comment on the episode, as NIP-22 defines it."
  (signed bob {:kind    1111
               :at      110
               :content "I agree"
               :tags    [["I" episode] ["K" "podcast:item:guid"] ["i" episode] ["k" "podcast:item:guid"]]}))

(def reply
  "A reply to the note, as NIP-10 defines it."
  (signed bob {:kind 1 :at 120 :content "A reply" :tags [["e" (id top-note) "" "root"] ["p" ann-key]]}))

(def reply-to-reply
  (signed ann {:kind    1
               :at      130
               :content "A reply to the reply"
               :tags    [["e" (id top-note) "" "root"] ["e" (id reply) relay-a "reply" bob-key] ["p" bob-key]]}))

(def reply-to-comment
  "A reply to the comment, as NIP-22 defines it."
  (signed ann {:kind    1111
               :at      140
               :content "A reply to the comment"
               :tags    [["I" episode] ["K" "podcast:item:guid"]
                         ["e" (id top-comment) relay-a bob-key] ["k" "1111"] ["p" bob-key]]}))

(def elsewhere
  "A note about another episode."
  (signed eve {:kind 1 :at 100 :content "Another episode" :tags [["i" "podcast:item:guid:other"]]}))

(def ann-profile
  (signed ann {:kind    0
               :at      50
               :content (json/write-str {"name"         "ann"
                                         "display_name" "Ann"
                                         "picture"      "https://example.org/ann.png"
                                         "nip05"        "ann@example.org"})}))

(def ann-profile-before
  (signed ann {:kind 0 :at 40 :content (json/write-str {"name" "Anna"})}))

(def bob-profile
  (signed bob {:kind 0 :at 50 :content (json/write-str {"name" "bob" "picture" "javascript:alert(1)"})}))

(def about-episode
  "All that the relays have about the episode."
  [top-note top-comment reply reply-to-reply reply-to-comment elsewhere ann-profile ann-profile-before bob-profile])

(defn relay
  "A function that sends a request as relays do that hold `held`, a map
  from the URL of a relay to its events. A value that is a map is the
  response of the relay, and a relay that is not there fails."
  [held]
  (fn [{:keys [url filters]}]
    (let [x (get held url)]
      (cond
        (map? x) (assoc x :url url)
        (nil? x) (throw (ex-info "Connection refused" {:url url}))
        :else    {:url       url
                  :complete? true
                  :events    (filterv (fn [e] (some #(nostr/matches? % e) filters)) x)}))))

(defn- exchange
  "The exchange of `request` with the relays of `send`."
  [send request]
  {:request  request
   :response (try
               (send request)
               (catch #?(:clj Exception :cljs :default) e
                 {:error (ex-message e) :retry? true}))})

(defn read-thread
  "`thread` as its pure steps read it from the relays of `send` with
  `opts`, with the requests that went out: `[thread requests]`."
  [thread send opts]
  (loop [thread (comments/resumed thread) sent [] rounds 0]
    (let [requests (comments/requests thread opts)]
      (if (or (empty? requests) (= 50 rounds))
        [thread sent]
        (recur (reduce #(comments/with-exchanges %1 [(exchange send %2)] at) thread requests)
               (into sent requests)
               (inc rounds))))))

(defn- shape
  "The tree `node` as the text of each comment with the shapes of its
  replies."
  [node]
  (into [(some-> (:content node) (str/replace #"<[^>]+>" " ") str/trim (str/replace #"\s+" " "))]
        (map shape (:replies node))))

(def thread
  "The unread thread of the episode."
  {:protocol "nostr" :uri episode})

(deftest the-thread-about-a-show-or-an-episode
  (let [feed {:podcast/guid "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"
              :rss/items    [{:rss/guid {:content "d98d189b-dc7b-45b1-8720-d4b98690f31f"}}
                             {:rss/enclosure {:url "https://example.org/2.mp3"}}
                             {:rss/title "An item without a guid"}]}
        [a b c] (:rss/items feed)]
    (is (= thread (nostr-comments/about feed a)))
    (is (= {:protocol "nostr" :uri "podcast:item:guid:https://example.org/2.mp3"} (nostr-comments/about feed b)))
    (is (nil? (nostr-comments/about feed c)))
    (is (= {:protocol "nostr" :uri "podcast:guid:c90e609a-df1e-596a-bd5e-57bcc8aad6cc"} (nostr-comments/about feed)))
    (is (nil? (nostr-comments/about (dissoc feed :podcast/guid))))
    (is (= {:protocol "nostr" :uri "podcast:guid:917393e3-1b1e-5cef-ace4-edaa54e1f810"}
           (nostr-comments/about (dissoc feed :podcast/guid) nil {:url "https://mp3s.nashownotes.com/pc20rss.xml"})))
    (is (comments/readable? (nostr-comments/about feed a)))))

(deftest the-comments-about-an-episode
  (let [opts          {:relays [relay-a]}
        [read sent]   (read-thread thread (relay {relay-a about-episode}) opts)
        tree          (comments/tree read)]
    (is (= episode (:root read)))
    (is (= at (:read-at read)))
    (is (= ["" ["A good episode: https://example.org/episode ."
                ["A reply" ["A reply to the reply"]]]
            ["I agree" ["A reply to the comment"]]]
           (assoc (shape tree) 0 "")))
    (is (= {:id episode :read-at at} (dissoc tree :replies)) "the root is the episode, a comment without content")
    (is (= #{episode (id top-note) (id top-comment) (id reply) (id reply-to-reply) (id reply-to-comment)}
           (set (keys (:comments read))))
        "no note about another episode")
    (testing "a comment"
      (is (= {:id                    (id top-note)
              :author                ann-key
              :in-reply-to           episode
              :published             #inst "1970-01-01T00:01:40Z"
              :content               (str "<p>A good episode:<br><a href=\"https://example.org/episode\" rel=\"nofollow ugc\">"
                                          "https://example.org/episode</a>.</p>")
              :read-at               at
              ::nostr-comments/event top-note}
             (get-in read [:comments (id top-note)])))
      (is (= (id reply) (:in-reply-to (get-in read [:comments (id reply-to-reply)]))))
      (is (= (id top-comment) (:in-reply-to (get-in read [:comments (id reply-to-comment)])))))
    (testing "the authors, each by the last event that the relays have"
      (is (= {:id      ann-key
              :name    "Ann"
              :handle  (nostr/encode {:type :npub :public-key ann-key})
              :avatar  "https://example.org/ann.png"
              :read-at at}
             (select-keys (get-in read [:authors ann-key]) [:id :name :handle :avatar :read-at :url])))
      (is (= {:id bob-key :name "bob" :handle (nostr/encode {:type :npub :public-key bob-key}) :read-at at}
             (select-keys (get-in read [:authors bob-key]) [:id :name :handle :avatar :read-at]))
          "a picture that is no web URL is left out")
      (is (= "Ann" (get-in tree [:replies 0 :author :name]))))
    (testing "the requests: the root, the replies to the comments, and the authors"
      (is (= [[:root relay-a] [:replies relay-a] [:replies relay-a] [:authors relay-a]]
             (map #(vec (take 2 (::comments/for %))) sent)))
      (is (= [{"kinds" [1 1111] "#i" [episode] "limit" 100} {"kinds" [1111] "#I" [episode] "limit" 100}]
             (:filters (first sent))))
      (is (= #{(id top-note) (id top-comment) (id reply-to-comment)} (set (get-in (second sent) [:filters 0 "#e"])))
          "each comment of NIP-22 names the episode, so the first answer has the reply to the comment")
      (is (= [{"kinds" [0] "authors" [ann-key bob-key]}] (map #(update % "authors" sort) (:filters (last sent))))))
    (is (= [] (comments/report read opts)))
    (is (= [] (comments/requests read opts)) "the thread is read")
    (is (= [(comments/resumed read) []] (read-thread read (relay {relay-a about-episode}) opts))
        "a later reading asks the relay for nothing more")))

(deftest the-thread-below-a-note
  (let [root      (signed ann {:kind 1 :at 100 :content "What did you think of the episode?"})
        direct    (signed bob {:kind 1 :at 110 :content "One tag" :tags [["e" (id root)]]})
        nested    (signed ann {:kind 1 :at 120 :content "Two tags" :tags [["e" (id root)] ["e" (id direct)]]})
        deep      (signed bob {:kind    1
                               :at      130
                               :content "Three tags"
                               :tags    [["e" (id root)] ["e" (id direct)] ["e" (id nested)]]})
        comment   (signed bob {:kind    1111
                               :at      140
                               :content "A comment of NIP-22"
                               :tags    [["E" (id root) relay-a ann-key] ["K" "1"] ["P" ann-key]
                                         ["e" (id root) relay-a ann-key] ["k" "1"] ["p" ann-key]]})
        mention   (signed eve {:kind 1 :at 150 :content "Look at this" :tags [["e" (id root) "" "mention"]]})
        quote     (signed eve {:kind 1 :at 160 :content "A quote" :tags [["q" (id root)]]})
        events    [root direct nested deep comment mention quote]
        interact  {:protocol "nostr" :uri (nostr/uri {:type :nevent :id (id root) :relays [relay-b]}) :account-id "ann"}
        opts      {:relays [relay-a]}]
    (is (= [relay-b relay-a] (nostr-comments/relays-of interact opts)) "the relay of the name first")
    (testing "the replies in the older form of NIP-10, and a comment of NIP-22"
      (let [[read sent] (read-thread interact (relay {relay-a events relay-b []}) opts)]
        (is (= (id root) (:root read)))
        (is (= ["What did you think of the episode?"
                ["One tag" ["Two tags" ["Three tags"]]]
                ["A comment of NIP-22"]]
               (shape (comments/tree read))))
        (is (= [{"ids" [(id root)]}
                {"kinds" [1 1111] "#e" [(id root)] "limit" 100}
                {"kinds" [1111] "#E" [(id root)] "limit" 100}
                {"kinds" [5] "#e" [(id root)]}
                {"kinds" [6 7 16] "#e" [(id root)] "limit" 100}]
               (:filters (first sent))))
        (is (= #{relay-a relay-b} (set (map :url sent))))
        (is (= [] (comments/report read opts)))))
    (testing "an event that two relays give is one comment"
      (let [[read] (read-thread interact (relay {relay-a events relay-b events}) opts)]
        (is (= 5 (count (:comments read))))
        (is (nil? (::nostr-comments/left-out read)))))
    (testing "a note that no relay gives"
      (let [[read] (read-thread interact (relay {relay-a (rest events) relay-b []}) opts)
            tree   (comments/tree read)]
        (is (= {:id :comments/unreachable :reason "No relay gave the note" :retry? true}
               (select-keys (:error tree) [:id :reason :retry?])))
        (is (= [nil ["One tag" ["Two tags" ["Three tags"]]] ["A comment of NIP-22"]] (shape tree)))
        (is (= [:comments/unreachable] (map :id (comments/report read opts))))
        (testing "and that a relay gives later"
          (let [[again] (read-thread (assoc-in read [::nostr-comments/relays relay-b] {}) (relay {relay-a [] relay-b [root]}) opts)]
            (is (= "What did you think of the episode?" (first (shape (comments/tree again)))))
            (is (= [] (comments/report again opts)))))))))

(deftest the-thread-that-a-feed-names
  (let [root (signed ann {:kind 1 :at 100 :content "The thread of the episode"})
        feed {:rss/items [{:rss/guid {:content "1"}
                           :podcast/social-interacts [{:protocol "twitter" :uri "https://twitter.com/x/status/1" :priority 1}
                                                      {:protocol "Nostr" :uri (nostr/uri {:type :note :id (id root)}) :priority 2}]}]}]
    (is (= {:protocol "nostr" :uri (nostr/uri {:type :note :id (id root)}) :priority 2}
           (comments/thread feed (first (:rss/items feed)))))))

(deftest events-that-are-not-verified
  (let [forged   (assoc top-comment "content" "Buy my coins")
        unsigned (assoc reply "sig" (get top-note "sig"))
        renamed  (assoc reply-to-reply "id" (id reply-to-comment))
        everyone (fn [{:keys [url]}]
                   {:url url :complete? true :events [top-note forged unsigned renamed elsewhere "text" nil {"id" 5}]})
        opts     {:relays [relay-a]}
        [read]   (read-thread thread everyone opts)]
    (is (= #{episode (id top-note)} (set (keys (:comments read)))))
    (is (= [:comments/unverified] (map :id (comments/report read opts))))
    (is (pos? (:count (first (comments/report read opts)))))
    (is (= ["" ["A good episode: https://example.org/episode ."]] (assoc (shape (comments/tree read)) 0 "")))
    (testing "an event that the request did not ask for counts too"
      (let [[read] (read-thread thread (fn [{:keys [url]}] {:url url :complete? true :events [elsewhere]}) opts)]
        (is (= 1 (::nostr-comments/left-out read)) "the one answer to the one question")))))

(deftest a-comment-that-its-author-deleted
  (let [by-author (signed bob {:kind 5 :at 200 :content "my mistake" :tags [["e" (id reply)] ["k" "1"]]})
        by-other  (signed eve {:kind 5 :at 200 :tags [["e" (id top-note)] ["e" (id top-comment)]]})
        opts      {:relays [relay-a relay-b]}]
    (testing "with a reply below it"
      (let [[read] (read-thread thread (relay {relay-a (conj about-episode by-author by-other) relay-b []}) opts)]
        (is (= {:id (id reply) :in-reply-to (id top-note) :deleted? true :read-at at}
               (get-in read [:comments (id reply)])))
        (is (= ["" ["A good episode: https://example.org/episode ." [nil ["A reply to the reply"]]]
                ["I agree" ["A reply to the comment"]]]
               (assoc (shape (comments/tree read)) 0 ""))
            "only the author of a comment deletes it")))
    (testing "without a reply below it"
      (let [gone   (signed ann {:kind 5 :at 200 :tags [["e" (id reply-to-reply)]]})
            [read] (read-thread thread (relay {relay-a (conj about-episode gone) relay-b []}) opts)]
        (is (= ["" ["A good episode: https://example.org/episode ." ["A reply"]]
                ["I agree" ["A reply to the comment"]]]
               (assoc (shape (comments/tree read)) 0 "")))))
    (testing "on a relay that does not have the comment"
      (let [send   (relay {relay-a [top-note by-author] relay-b [top-note reply]})
            [read] (read-thread thread send opts)]
        (is (:deleted? (get-in read [:comments (id reply)])))
        (is (= [""  ["A good episode: https://example.org/episode ."]] (assoc (shape (comments/tree read)) 0 "")))))))

(deftest reactions-and-reposts
  (let [like    (signed ann {:kind 7 :at 150 :content "+" :tags [["e" (id top-comment)] ["p" bob-key] ["k" "1111"]]})
        again   (signed ann {:kind 7 :at 151 :content "🔥" :tags [["e" (id top-comment)] ["p" bob-key]]})
        dislike (signed eve {:kind 7 :at 152 :content "-" :tags [["e" (id top-comment)]]})
        repost  (signed eve {:kind 16 :at 153 :tags [["e" (id top-comment) relay-a] ["k" "1111"] ["p" bob-key]]})
        quoted  (signed bob {:kind 6 :at 154 :tags [["e" (id reply) relay-a] ["p" bob-key]]})
        [read]  (read-thread thread (relay {relay-a (conj about-episode like again dislike repost quoted)})
                             {:relays [relay-a]})]
    (is (= {:likes 1 :reposts 1} (get-in read [:comments (id top-comment) :counts]))
        "one like for each author, and no dislike")
    (is (= {:likes 0 :reposts 1} (get-in read [:comments (id reply) :counts])))
    (is (nil? (get-in read [:comments (id top-note) :counts])) "none for a comment that nobody reacted to")
    (is (not (contains? (:comments read) (id like))) "a reaction isn't a comment")))

(deftest what-changed-since-a-reading
  (let [opts  {:relays [relay-a]}
        [old] (read-thread thread (relay {relay-a (remove #{reply-to-comment} about-episode)}) opts)
        gone  (signed bob {:kind 5 :at 200 :tags [["e" (id reply)]]})
        [new] (read-thread thread (relay {relay-a (conj about-episode gone)}) opts)]
    (is (= {:added [(id reply-to-comment)] :edited [] :deleted [(id reply)]}
           (update (comments/changes old new) :added #(mapv :id %)))
        "a new reply, and a deleted one, from a second reading of the unread thread")
    (is (= {:added [] :edited [] :deleted []} (comments/changes new new)))
    (is (= (mapv id [top-note top-comment reply reply-to-reply])
           (mapv :id (:added (comments/changes {} old))))
        "everything is new to no reading, in the order of time, but the root")))

(deftest writing-to-a-thread
  (let [[read] (read-thread thread (relay {relay-a about-episode}) {:relays [relay-a]})
        tags   #(get % "tags")]
    (testing "a comment on the episode"
      (is (= {"kind"    1111
              "content" "Hi"
              "tags"    [["I" episode "https://e.example/1"] ["K" "podcast:item:guid"]
                         ["i" episode "https://e.example/1"] ["k" "podcast:item:guid"]]}
             (nostr-comments/comment-event read "Hi" {:url "https://e.example/1"})))
      (is (= {"kind" 1 "content" "Hi" "tags" [["k" "podcast:item:guid"] ["i" episode]]}
             (nostr-comments/comment-event read "Hi" {:kind 1}))
          "as Fountain writes it"))
    (testing "replies"
      (is (= [["I" episode] ["K" "podcast:item:guid"] ["e" (id top-comment) "" bob-key] ["k" "1111"] ["p" bob-key]]
             (tags (nostr-comments/reply-event read (id top-comment) "Yes")))
          "a comment of NIP-22 keeps the root scope of its parent")
      (is (= [["e" (id top-note) "" "root" ann-key] ["p" ann-key]]
             (tags (nostr-comments/reply-event read (id top-note) "Yes")))
          "a reply to the note at the top of a thread of NIP-10")
      (is (= [["e" (id top-note) "" "root"] ["e" (id reply) "" "reply" bob-key] ["p" ann-key] ["p" bob-key]]
             (tags (nostr-comments/reply-event read (id reply) "Yes")))
          "the root and the note that it replies to, and the authors")
      (is (= (nostr-comments/comment-event read "Yes") (nostr-comments/reply-event read episode "Yes")))
      (is (nil? (nostr-comments/reply-event read "unknown" "Yes"))))
    (testing "a deletion and reactions"
      (is (= {"kind" 5 "content" "" "tags" [["e" (id reply)] ["k" "1"]]}
             (nostr-comments/delete-event read (id reply))))
      (is (= {"kind" 7 "content" "+" "tags" [["e" (id top-comment) "" bob-key] ["p" bob-key] ["k" "1111"]]}
             (nostr-comments/reaction-event read (id top-comment))))
      (is (= {"kind" 17 "content" "🔥" "tags" [["k" "podcast:item:guid"] ["i" episode]]}
             (nostr-comments/reaction-event read episode "🔥"))
          "a reaction to the episode itself"))
    (testing "posting"
      (let [send  (fn [{:keys [url]}]
                    (if (= relay-a url)
                      {:url url :accepted? true :sent? true}
                      {:url url :refused "blocked: no" :sent? true}))
            ann-s (signer/local (signer/secret-key ann) {:random-fn #(bytes/of (repeat % 9))})
            event (nostr-comments/comment-event read "Hi")]
        (run #(async/then (nostr-comments/post! read event ann-s {:relays [relay-a relay-b] :send send
                                                                  :now-fn (constantly at)})
                          (fn [{:keys [event relays error]}]
                            (is (nil? error))
                            (is (nostr/verified? event))
                            (is (= [ann-key (quot (inst-ms at) 1000)] [(get event "pubkey") (get event "created_at")]))
                            (is (= {relay-a {:accepted? true} relay-b {:refused "blocked: no"}} relays))))
             #(async/then (nostr-comments/post! read event ann-s {:relays [relay-b] :send send})
                          (fn [{:keys [error]}]
                            (is (= :nostr/not-published (:id error)) "no relay took it"))))))))

(deftest relays-that-fail
  (let [opts {:relays [relay-a relay-b]}]
    (testing "a relay that is not there"
      (let [[read sent] (read-thread thread (relay {relay-a about-episode}) opts)]
        (is (= 5 (dec (count (:comments read)))) "the other relay gives the thread")
        (is (= [{:id :comments/unreachable :url relay-b :retry? true} {:id :comments/incomplete}]
               (map #(select-keys % [:id :url :retry? :status]) (comments/report read opts))))
        (is (= 1 (count (filter #(= relay-b (:url %)) sent))) "and gets no more questions in the reading")
        (testing "is asked again in the next reading"
          (let [[again sent] (read-thread read (relay {relay-a about-episode relay-b about-episode}) opts)]
            (is (= [[:root relay-b]] (map ::comments/for (take 1 sent))))
            (is (every? #(= relay-b (:url %)) sent) "the other relay gave its answers")
            (is (= [] (comments/report again opts)))))))
    (testing "a relay that asks who the reader is"
      (let [[read] (read-thread thread (relay {relay-a about-episode relay-b {:closed "auth-required: tell who you are" :auth? true}}) opts)]
        (is (= [{:id :comments/refused :url relay-b :reason "auth-required: tell who you are" :level :warning}]
               (map #(select-keys % [:id :url :reason :retry? :level]) (comments/report read opts))))
        (is (= [] (second (read-thread read (relay {relay-a about-episode relay-b about-episode}) opts)))
            "it is not asked again")))
    (testing "a relay that closes the subscription, for a time or for good"
      (doseq [[reason retry?] {"rate-limited: slow down" true
                               "error: could not connect to the database" true
                               "kinds not supported" false
                               "unsupported: filter contains unknown elements" false}]
        (let [[read] (read-thread thread (relay {relay-a [] relay-b {:closed reason}}) opts)]
          (is (= (if retry?
                   [{:id :comments/unreachable :url relay-b :reason reason :retry? true} {:id :comments/incomplete}]
                   [{:id :comments/unreachable :url relay-b :reason reason}])
                 (map #(select-keys % [:id :url :reason :retry?]) (comments/report read opts)))
              reason))))
    (testing "a relay that does not answer in time"
      (let [[read sent] (read-thread thread (relay {relay-a about-episode relay-b {:events [] :timeout? true}}) opts)]
        (is (= [{:id :comments/unreachable :url relay-b :timeout? true :retry? true} {:id :comments/incomplete}]
               (map #(select-keys % [:id :url :timeout? :retry?]) (comments/report read opts))))
        (is (= 1 (count (filter #(= relay-b (:url %)) sent))))))
    (testing "a relay that gives events, and then no end"
      (let [[read] (read-thread thread (relay {relay-a [] relay-b {:events [top-note] :timeout? true}}) opts)]
        (is (contains? (:comments read) (id top-note)))
        (is (= [] (comments/report read opts)))))))

(deftest the-limits-of-a-reading
  (let [notes   (mapv #(signed ann {:kind 1 :at (+ 100 %) :content (str "Note " %) :tags [["i" episode]]}) (range 5))
        replies (mapv #(signed bob {:kind 1 :at (+ 200 %2) :content (str "Reply " %2) :tags [["e" (id %1) "" "root"]]})
                      notes
                      (range))
        send    (relay {relay-a (into notes replies)})]
    (testing "the comments of one fetch"
      (let [opts        {:relays [relay-a] :max-comments 3}
            [read sent] (read-thread thread send opts)]
        (is (= [:root :authors] (map (comp first ::comments/for) sent)) "no question for the replies")
        (is (= 3 (get-in (first sent) [:filters 0 "limit"])))
        (is (= 6 (count (:comments read))))
        (is (= [:comments/incomplete] (map :id (comments/report read opts))))
        (testing "and of the next fetch"
          (let [[again sent] (read-thread read send opts)]
            (is (= [:replies :authors] (map (comp first ::comments/for) sent)))
            (is (= 11 (count (:comments again))))
            (is (= [:comments/incomplete] (map :id (comments/report again opts)))
                "the replies to the replies are not asked for")
            (is (= [] (comments/report (first (read-thread again send opts)) opts)))))))
    (testing "the ids of one question"
      (let [[read sent] (read-thread thread send {:relays [relay-a] :batch-size 2})
            asked       (keep #(get-in % [:filters 0 "#e"]) sent)]
        (is (= 11 (count (:comments read))))
        (is (every? #(<= (count %) 2) asked))
        (is (= (set (map id (into notes replies))) (set (apply concat asked))) "each comment once")
        (is (= (count (apply concat asked)) (count (set (apply concat asked)))))))))

(deftest a-note-of-another-app
  (let [note   (json/read-str (fixture "nostr/fountain-note.json"))
        about  {:protocol "nostr" :uri "podcast:item:guid:173eee40-32ae-41c8-a5e5-884c257dd47f"}
        [read] (read-thread about (relay {relay-a [note]}) {:relays [relay-a]})
        c      (get-in read [:comments (id note)])]
    (is (= {:id          (id note)
            :author      "91aeab23b5664edaa57dbe00b041ccb50544f89d7d956345bbd78b7dbaa48660"
            :in-reply-to (:uri about)
            :published   #inst "2026-09-29T15:48:08Z"}
           (select-keys c [:id :author :in-reply-to :published :content])))
    (is (= ["15defcff144b7966e0b0579ab45c9fb2c77bda754f06290b012b40b20b27c3a1"] (::nostr-comments/receipts c))
        "the note is a payment without a message: the page of the episode, and the receipt")
    (is (= [:link :reference] (remove #{:text} (map :type (nostr/parts note)))))))

(deftest a-fetch-of-a-thread
  (let [sent (atom [])
        send (fn [request]
               (swap! sent conj request)
               ((relay {relay-a about-episode relay-b [top-note]}) request))]
    (run (fn []
           (async/then (comments/fetch! thread {:relays [relay-a relay-b "wss://c.relay.example"] :send send :now-fn (constantly at)})
                       (fn [read]
                         (is (= ["" ["A good episode: https://example.org/episode ."
                                     ["A reply" ["A reply to the reply"]]]
                                 ["I agree" ["A reply to the comment"]]]
                                (assoc (shape (comments/tree read)) 0 "")))
                         (is (= [{:id :comments/unreachable :url "wss://c.relay.example" :retry? true}
                                 {:id :comments/incomplete}]
                                (map #(select-keys % [:id :url :retry?]) (:report read))))
                         (is (= #{relay-a relay-b "wss://c.relay.example"} (set (map :url @sent))))
                         (is (every? #(and (:allow-pred %) (:timeout-seconds %) (:max-bytes %)) @sent)
                             "each request has the limits and the guard")
                         (is (not ((:allow-pred (first @sent)) "wss://127.0.0.1")))
                         (is (not ((:allow-pred (first @sent)) "http://localhost/")))))))))
