(ns dk.simongray.podcast.comments.nostr.impl-test
  "The internals of the reading of Nostr threads: the root that the URI of
  a thread names, and the content of a note as HTML, parts and Hiccup."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.nostr :as-alias nostr-comments]
            [dk.simongray.podcast.comments.nostr-test :refer [ann ann-key bob bob-key bob-profile eve episode id read-thread relay relay-a relay-b reply thread top-note]]
            [dk.simongray.podcast.comments.nostr.impl :as nostr-comments.impl]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.nostr.secp256k1 :as secp256k1]
            [dk.simongray.podcast.test-support :refer [signed]]
            [dk.simongray.podcast.util.json :as json]))

(deftest the-root-of-a-thread
  (let [note-id (id top-note)]
    (is (= {:id note-id :relays []} (nostr-comments.impl/root-of (nostr/uri {:type :note :id note-id}))))
    (is (= {:id note-id :relays []} (nostr-comments.impl/root-of (nostr/encode {:type :note :id note-id}))))
    (is (= {:id note-id :relays []} (nostr-comments.impl/root-of (str "nostr:" note-id))) "an id in hexadecimal")
    (is (= {:id note-id :relays [relay-a relay-b]}
           (nostr-comments.impl/root-of (nostr/uri {:type   :nevent
                                               :id     note-id
                                               :author ann-key
                                               :relays [(str relay-a "/") "https://example.org" relay-b relay-a]}))))
    (is (= 3 (count (:relays (nostr-comments.impl/root-of
                              (nostr/uri {:type :nevent :id note-id :relays (map #(str "wss://r" % ".example") (range 9))})))))
        "three relays of the name at most")
    (doseq [uri [episode "podcast:guid:c90e609a-df1e-596a-bd5e-57bcc8aad6cc" "podcast:publisher:guid:abc"
                 "podcast:item:guid:https://example.org/?p=1"]]
      (is (= {:external uri} (nostr-comments.impl/root-of (str " " uri "\n"))) uri))
    (doseq [uri [nil "" "nostr:" "podcast:guid:" "podcast:item:abc" "isbn:9780765382030" "https://example.org"
                 (nostr/uri {:type :npub :public-key ann-key})
                 (nostr/uri {:type :naddr :identifier "x" :author ann-key :kind 30023})]]
      (is (nil? (nostr-comments.impl/root-of uri)) (pr-str uri))
      (is (not (comments/readable? {:protocol "nostr" :uri uri})) (pr-str uri)))
    (is (comments/readable? thread))
    (is (comments/readable? thread {:relays [relay-a]}))
    (is (not (comments/readable? thread {:relays []})) "no relay to ask")
    (is (not (comments/readable? thread {:relays ["https://example.org"]})))
    (is (comments/readable? {:protocol "nostr" :uri (nostr/uri {:type :nevent :id note-id :relays [relay-a]})}
                            {:relays []})
        "the relay of the name")))

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
    (is (= html (nostr-comments.impl/note-html {"content" text "tags" []} {})) text))
  (doseq [text [nil "" "  \n" 5]]
    (is (nil? (nostr-comments.impl/note-html {"content" text} {})) (pr-str text)))
  (testing "a long content reads in a short time"
    (let [lines (apply str (repeat 60000 "line\n"))
          words (apply str (repeat 60000 "#x https://example.org/a "))]
      (is (true? (= (str/trimr lines) (apply str (map :text (nostr-comments.impl/parts {"content" lines}))))))
      (is (= 1 (count (nostr-comments.impl/hiccup {"content" lines}))))
      (is (true? (= words (apply str (map :text (nostr-comments.impl/parts {"content" words}))))))))
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
             (nostr-comments.impl/hiccup note {:names {bob-key "Bob"}}))
          "no line for the page of the episode, for the media and for the receipt of a payment")
      (is (= [[:p {} "Is this " [:a {:href (str "https://example.org/" ann-name)} "@Ann"] " approved, "
               [:a {:href (str "https://example.org/" bob-name)} "@Bob"] "? "
               [:a {:href "https://example.org/t/40HPW"} "#40HPW"] " #other"]
              [:p {} "See " [:a {:href (str "https://example.org/" quoted)} (short quoted)] " and "
               [:a {:href "https://fountain.fm/episode/x" :rel "nofollow ugc"} "https://fountain.fm/episode/x"]]]
             (nostr-comments.impl/hiccup note {:names   {ann-key "Ann" bob-key "Bob"}
                                          :link-fn (fn [{:keys [uri tag]}]
                                                     (str "https://example.org/" (if uri (subs uri 6) (str "t/" tag))))}))
          "the links of the app")
      (is (= (str "<p>Is this @Ann approved, @" (short bob-name) "? #40HPW #other</p>"
                  "<p>See " (short quoted) " and <a href=\"https://fountain.fm/episode/x\" rel=\"nofollow ugc\">"
                  "https://fountain.fm/episode/x</a></p>")
             (nostr-comments.impl/note-html note {ann-key "Ann"})))
      (is (= [(id reply)] (nostr-comments.impl/receipts note)))
      (is (= [] (nostr-comments.impl/hiccup {"content" (str "https://fountain.fm/episode/x\n\nnostr:" receipt)
                                        "tags"    [["i" episode "https://fountain.fm/episode/x"]]}))
          "a payment without a message")
      (is (= [] (nostr-comments.impl/receipts top-note)))))
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
      (is (= {bob-key "bob" (secp256k1/public-key eve) "Eve"} (nostr-comments.impl/names read)))
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
