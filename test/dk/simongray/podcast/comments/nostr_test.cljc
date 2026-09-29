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
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.test-support :refer [fixture run secret-key signed]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.json :as json]))

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

(defn- id [event] (get event "id"))

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

(defn- relay
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

(defn- read-thread
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

(deftest the-root-of-a-thread
  (let [note-id (id top-note)]
    (is (= {:id note-id :relays []} (nostr-comments/root-of (nostr/uri {:type :note :id note-id}))))
    (is (= {:id note-id :relays []} (nostr-comments/root-of (nostr/encode {:type :note :id note-id}))))
    (is (= {:id note-id :relays []} (nostr-comments/root-of (str "nostr:" note-id))) "an id in hexadecimal")
    (is (= {:id note-id :relays [relay-a relay-b]}
           (nostr-comments/root-of (nostr/uri {:type   :nevent
                                               :id     note-id
                                               :author ann-key
                                               :relays [(str relay-a "/") "https://example.org" relay-b relay-a]}))))
    (is (= 3 (count (:relays (nostr-comments/root-of
                              (nostr/uri {:type :nevent :id note-id :relays (map #(str "wss://r" % ".example") (range 9))})))))
        "three relays of the name at most")
    (doseq [uri [episode "podcast:guid:c90e609a-df1e-596a-bd5e-57bcc8aad6cc" "podcast:publisher:guid:abc"
                 "podcast:item:guid:https://example.org/?p=1"]]
      (is (= {:external uri} (nostr-comments/root-of (str " " uri "\n"))) uri))
    (doseq [uri [nil "" "nostr:" "podcast:guid:" "podcast:item:abc" "isbn:9780765382030" "https://example.org"
                 (nostr/uri {:type :npub :public-key ann-key})
                 (nostr/uri {:type :naddr :identifier "x" :author ann-key :kind 30023})]]
      (is (nil? (nostr-comments/root-of uri)) (pr-str uri))
      (is (not (comments/readable? {:protocol "nostr" :uri uri})) (pr-str uri)))
    (is (comments/readable? thread))
    (is (comments/readable? thread {:relays [relay-a]}))
    (is (not (comments/readable? thread {:relays []})) "no relay to ask")
    (is (not (comments/readable? thread {:relays ["https://example.org"]})))
    (is (comments/readable? {:protocol "nostr" :uri (nostr/uri {:type :nevent :id note-id :relays [relay-a]})}
                            {:relays []})
        "the relay of the name")))

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
                {"kinds" [5] "#e" [(id root)]}]
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

(deftest the-content-of-a-note
  (doseq [[text html]
          {"Plain"                       "<p>Plain</p>"
           "One\ntwo\n\nthree\n\n\n\nfour" "<p>One<br>two</p><p>three</p><p>four</p>"
           "\n\n  Space around  \n"      "<p>  Space around  </p>"
           "<script>alert(1)</script> &" "<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp;</p>"
           "See https://example.org/a?b=1&c=2, and (http://example.org/x)."
           (str "<p>See <a href=\"https://example.org/a?b=1&amp;c=2\" rel=\"nofollow ugc\">https://example.org/a?b=1&amp;c=2</a>, "
                "and (<a href=\"http://example.org/x\" rel=\"nofollow ugc\">http://example.org/x</a>).</p>")
           "https://example.org https://example.org"
           (str "<p><a href=\"https://example.org\" rel=\"nofollow ugc\">https://example.org</a> "
                "<a href=\"https://example.org\" rel=\"nofollow ugc\">https://example.org</a></p>")
           "nostr:npub1abc javascript:alert(1) ftp://example.org #tag"
           "<p>nostr:npub1abc javascript:alert(1) ftp://example.org #tag</p>"}]
    (is (= html (nostr-comments/note-html {"content" text "tags" []} {})) text))
  (doseq [text [nil "" "  \n" 5]]
    (is (nil? (nostr-comments/note-html {"content" text} {})) (pr-str text)))
  (testing "a long content reads in a short time"
    (let [lines (apply str (repeat 60000 "line\n"))
          words (apply str (repeat 60000 "#x https://example.org/a "))]
      (is (true? (= (str/trimr lines) (apply str (map :text (nostr-comments/parts {"content" lines}))))))
      (is (= 1 (count (nostr-comments/hiccup {"content" lines}))))
      (is (true? (= words (apply str (map :text (nostr-comments/parts {"content" words}))))))))
  (testing "the names that a note holds, for a reader"
    (let [ann-name (nostr/encode {:type :npub :public-key ann-key})
          bob-name (nostr/encode {:type :nprofile :public-key bob-key :relays [relay-a]})
          quoted   (nostr/encode {:type :nevent :id (id top-note) :kind 1})
          receipt  (nostr/encode {:type :nevent :id (id reply) :kind 9735})
          note     {"content" (str "Is this nostr:" ann-name " approved, nostr:" bob-name "? #40HPW #other\n\n"
                                   "https://fountain.fm/episode/x\n\n"
                                   "See nostr:" quoted " and https://fountain.fm/episode/x\n"
                                   "https://example.org/a.png\n"
                                   "nostr:" receipt)
                    "tags"    [["i" episode "https://fountain.fm/episode/x"] ["t" "40hpw"]
                               ["imeta" "url https://example.org/a.png" "m image/png"]]}
          short    #(str (subs % 0 12) "…" (subs % (- (count %) 6)))]
      (is (= [[:p {} "Is this " (str "@" (short ann-name)) " approved, " "@Bob" "? " "#40HPW" " #other"]
              [:p {} "See " (short quoted) " and "
               [:a {:href "https://fountain.fm/episode/x" :rel "nofollow ugc"} "https://fountain.fm/episode/x"]]]
             (nostr-comments/hiccup note {:names {bob-key "Bob"}}))
          "no line for the page of the episode, for the media and for the receipt of a payment")
      (is (= [[:p {} "Is this " [:a {:href (str "https://example.org/" ann-name)} "@Ann"] " approved, "
               [:a {:href (str "https://example.org/" bob-name)} "@Bob"] "? "
               [:a {:href "https://example.org/t/40HPW"} "#40HPW"] " #other"]
              [:p {} "See " [:a {:href (str "https://example.org/" quoted)} (short quoted)] " and "
               [:a {:href "https://fountain.fm/episode/x" :rel "nofollow ugc"} "https://fountain.fm/episode/x"]]]
             (nostr-comments/hiccup note {:names {ann-key "Ann" bob-key "Bob"}
                                          :link  (fn [{:keys [uri tag]}]
                                                   (str "https://example.org/" (if uri (subs uri 6) (str "t/" tag))))}))
          "the links of the app")
      (is (= (str "<p>Is this @Ann approved, @" (short bob-name) "? #40HPW #other</p>"
                  "<p>See " (short quoted) " and <a href=\"https://fountain.fm/episode/x\" rel=\"nofollow ugc\">"
                  "https://fountain.fm/episode/x</a></p>")
             (nostr-comments/note-html note {ann-key "Ann"})))
      (is (= [(id reply)] (nostr-comments/receipts note)))
      (is (= [] (nostr-comments/hiccup {"content" (str "https://fountain.fm/episode/x\n\nnostr:" receipt)
                                        "tags"    [["i" episode "https://fountain.fm/episode/x"]]}))
          "a payment without a message")
      (is (= [] (nostr-comments/receipts top-note)))))
  (testing "the name of a profile that comes after the comment"
    (let [mention (signed bob {:kind    1
                               :at      150
                               :content (str "Thanks nostr:" (nostr/encode {:type :npub :public-key (secp256k1/public-key eve)}))
                               :tags    [["i" episode]]})
          profile (signed eve {:kind 0 :at 50 :content (json/write-str {"name" "Eve"})})
          opts    {:relays [relay-a]}
          [read sent] (read-thread thread (relay {relay-a [mention profile bob-profile]}) opts)]
      (is (= "<p>Thanks @Eve</p>" (get-in read [:comments (id mention) :content])))
      (is (= [(secp256k1/public-key eve)] (get-in read [:comments (id mention) ::nostr-comments/mentions])))
      (is (= mention (get-in read [:comments (id mention) ::nostr-comments/event])) "the event, for an app that writes its own content")
      (is (= #{bob-key (secp256k1/public-key eve)} (set (get-in (last sent) [:filters 0 "authors"])))
          "the question for the authors asks for the profiles of the mentions too")
      (is (= {bob-key "bob" (secp256k1/public-key eve) "Eve"} (nostr-comments/names read)))
      (let [[unnamed] (read-thread thread (relay {relay-a [mention]}) opts)]
        (is (re-matches #"<p>Thanks @npub1[a-z0-9]{7}…[a-z0-9]{6}</p>" (get-in unnamed [:comments (id mention) :content]))))))
  (testing "a warning, a language and the media of a note"
    (let [note   (signed ann {:kind    1
                              :at      100
                              :content "Look https://example.org/a.png"
                              :tags    [["i" episode]
                                        ["content-warning" "A spoiler of the end"]
                                        ["l" "da" "ISO-639-1"]
                                        ["l" "podcast" "#t"]
                                        ["imeta" "url https://example.org/a.png" "m image/png" "dim 1200x300"
                                         "alt A chart of the downloads" "x abc"]
                                        ["imeta" "url https://example.org/a.mp4" "m video/mp4"]
                                        ["imeta" "url https://example.org/a.pdf" "m application/pdf"]
                                        ["imeta" "url javascript:alert(1)" "m image/png"]
                                        ["imeta" "m image/png"]]})
          plain  (signed bob {:kind 1 :at 110 :content "No warning" :tags [["i" episode] ["content-warning"]]})
          [read] (read-thread thread (relay {relay-a [note plain]}) {:relays [relay-a]})]
      (is (= {:sensitive?  true
              :summary     "A spoiler of the end"
              :language    "da"
              :attachments [{:kind :image :url "https://example.org/a.png" :media-type "image/png"
                             :alt "A chart of the downloads" :width 1200 :height 300}
                            {:kind :video :url "https://example.org/a.mp4" :media-type "video/mp4"}]}
             (select-keys (get-in read [:comments (id note)]) [:sensitive? :summary :language :attachments])))
      (is (= {:sensitive? true} (select-keys (get-in read [:comments (id plain)]) [:sensitive? :summary :language :attachments]))))))

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
           (async/then (comments/fetch! thread {:relays [relay-a relay-b "wss://c.relay.example"] :send send :now (constantly at)})
                       (fn [read]
                         (is (= ["" ["A good episode: https://example.org/episode ."
                                     ["A reply" ["A reply to the reply"]]]
                                 ["I agree" ["A reply to the comment"]]]
                                (assoc (shape (comments/tree read)) 0 "")))
                         (is (= [{:id :comments/unreachable :url "wss://c.relay.example" :retry? true}
                                 {:id :comments/incomplete}]
                                (map #(select-keys % [:id :url :retry?]) (:report read))))
                         (is (= #{relay-a relay-b "wss://c.relay.example"} (set (map :url @sent))))
                         (is (every? #(and (:allow? %) (:timeout-seconds %) (:max-bytes %)) @sent)
                             "each request has the limits and the guard")
                         (is (not ((:allow? (first @sent)) "wss://127.0.0.1")))
                         (is (not ((:allow? (first @sent)) "http://localhost/")))))))))
