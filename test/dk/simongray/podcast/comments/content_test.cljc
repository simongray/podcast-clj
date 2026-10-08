(ns dk.simongray.podcast.comments.content-test
  "The content of a comment as parts and as Hiccup, for each protocol: the
  mentions, the hashtags, the links and the custom emoji of a post of
  ActivityPub, of the AT Protocol and of Nostr, the links of an app, and
  the comments of a thread that a fetch read, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.html :as html]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.activitypub :as activitypub]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.test-support :refer [fixture run secret-key signed]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.json :as json]))

(def mastodon
  "A thread of podcastindex.social, with replies from Misskey and another
  Mastodon server."
  (json/read-str (fixture "comments/mastodon.json")))

(def bluesky
  "The answers of an AppView, by the post that each was asked for."
  (json/read-str (fixture "comments/bluesky.json")))

(defn- app-link
  "The link of an app for the part `part` of a content. A mention without
  the id of its profile has no link of the app."
  [{:keys [type protocol id tag uri]}]
  (case type
    :mention   (when id (str "/" protocol "/profiles/" id))
    :hashtag   (str "/" protocol "/tags/" tag)
    :reference (str "/" protocol "/events/" uri)
    nil))

(defn- text
  "The texts of the parts `ps`, one after another."
  [ps]
  (apply str (map :text ps)))

(def post
  "What the reader keeps of a post of Mastodon, with a mention, a custom
  emoji, a hashtag and a link."
  {:html (str "<p><span class=\"h-card\" translate=\"no\"><a href=\"https://podcastindex.social/@dave\" class=\"u-url mention\">"
              "@<span>dave</span></a></span> KDE :blobcat: and :unknown: "
              "<a href=\"/tags/podcast\" class=\"mention hashtag\" rel=\"tag\">#<span>podcast</span></a> see "
              "<a href=\"https://example.org/x\" onclick=\"x()\">example.org</a><br>bye"
              "<script>alert(1)</script><img src=\"https://tracker.example/p.gif\" alt=\"a pixel\"></p><p>Two</p>")
   :base "https://masto.es/users/ann/statuses/1"
   :tags [{:type :mention :id "https://podcastindex.social/users/dave" :name "@dave@podcastindex.social"}
          {:type :emoji :shortcode "blobcat" :url "https://masto.es/emoji/blobcat.png"}]})

(deftest a-post-of-activitypub
  (testing "the parts"
    (is (= [{:type :mention :text "@dave" :id "https://podcastindex.social/users/dave" :url "https://podcastindex.social/@dave"}
            {:type :text :text " KDE "}
            {:type :emoji :text ":blobcat:" :shortcode "blobcat" :url "https://masto.es/emoji/blobcat.png"}
            {:type :text :text " and :unknown: "}
            {:type :hashtag :text "#podcast" :tag "podcast" :url "https://masto.es/tags/podcast"}
            {:type :text :text " see "}
            {:type :link :text "example.org" :url "https://example.org/x"}
            {:type :text :text "\nbyea pixel\n\nTwo"}]
           (activitypub/parts post)))
    (is (= "@dave KDE :blobcat: and :unknown: #podcast see example.org\nbyea pixel\n\nTwo" (text (activitypub/parts post)))))
  (testing "the Hiccup, with the links that the post has"
    (is (= [[:p {}
             [:span {} [:a {:href "https://podcastindex.social/@dave" :rel "nofollow ugc"} "@" [:span {} "dave"]]]
             " KDE :blobcat: and :unknown: "
             [:a {:href "https://masto.es/tags/podcast" :rel "nofollow ugc"} "#" [:span {} "podcast"]]
             " see "
             [:a {:href "https://example.org/x" :rel "nofollow ugc"} "example.org"]
             [:br {}]
             "bye"
             "a pixel"]
            [:p {} "Two"]]
           (activitypub/hiccup post)))
    (is (= (html/emit (activitypub/hiccup post))
           (html/emit (html/hiccup (str "<p><span><a href=\"https://podcastindex.social/@dave\" rel=\"nofollow ugc\">@<span>dave</span></a>"
                                        "</span> KDE :blobcat: and :unknown: <a href=\"https://masto.es/tags/podcast\" rel=\"nofollow ugc\">"
                                        "#<span>podcast</span></a> see <a href=\"https://example.org/x\" rel=\"nofollow ugc\">example.org</a>"
                                        "<br>byea pixel</p><p>Two</p>"))))
        "the content of the comment"))
  (testing "the links of an app, and the custom emoji as images"
    (is (= [[:p {}
             [:span {} [:a {:href "/profiles/https://podcastindex.social/users/dave"} "@" [:span {} "dave"]]]
             " KDE "
             [:img {:src "https://masto.es/emoji/blobcat.png" :alt ":blobcat:" :title ":blobcat:"}]
             " and :unknown: "
             [:a {:href "/tags/podcast"} "#" [:span {} "podcast"]]
             " see "
             [:a {:href "https://example.org/x" :rel "nofollow ugc"} "example.org"]
             [:br {}]
             "bye"
             "a pixel"]
            [:p {} "Two"]]
           (activitypub/hiccup post {:emoji? true
                                     :link   (fn [{:keys [type id tag]}]
                                               (case type
                                                 :mention (str "/profiles/" id)
                                                 :hashtag (str "/tags/" tag)))})))
    (is (= (activitypub/hiccup post) (activitypub/hiccup post {:link (constantly nil)}))
        "a part without a link of the app keeps the link of the post"))
  (testing "what tells a mention and a hashtag"
    (let [parts-of (fn [markup tags] (remove #(= :text (:type %)) (activitypub/parts {:html markup :base "https://a.example/1" :tags tags})))
          dave     {:type :mention :id "https://b.example/users/dave" :name "@dave@b.example"}]
      (is (= [{:type :mention :text "@dave" :id "https://b.example/users/dave" :url "https://b.example/@dave"}]
             (parts-of "<a href=\"https://b.example/@dave\">@dave</a>" [dave]))
          "a tag of the post with the user name, on the server of the link")
      (is (= [{:type :mention :text "@Dave@B.example" :id "https://b.example/users/dave" :url "https://web.example/@dave"}]
             (parts-of "<a href=\"https://web.example/@dave\">@Dave@B.example</a>" [dave]))
          "the domain in the text of the link")
      (is (= [{:type :mention :text "@dave" :url "https://c.example/@dave"}]
             (parts-of "<a href=\"https://c.example/@dave\" class=\"mention\">@dave</a>" [dave]))
          "the class of the link, and no id for an account on another server")
      (is (= [{:type :link :text "@dave" :url "https://c.example/@dave"}]
             (parts-of "<a href=\"https://c.example/@dave\">@dave</a>" [dave])))
      (is (= [{:type :hashtag :text "#Podcast" :tag "Podcast" :url "https://a.example/tags/podcast"}]
             (parts-of "<a href=\"/tags/podcast\">#Podcast</a>" [{:type :hashtag :tag "podcast"}])))
      (is (= [{:type :hashtag :text "#podcast" :tag "podcast"}]
             (parts-of "<a rel=\"nofollow TAG\">#podcast</a>" [])))
      (is (= [{:type :link :text "#podcast" :url "https://a.example/tags/podcast"}]
             (parts-of "<a href=\"/tags/podcast\">#podcast</a>" [])))
      (is (= [{:type :link :text "podcast" :url "https://a.example/tags/podcast"}]
             (parts-of "<a href=\"/tags/podcast\" class=\"hashtag mention\">podcast</a>" [dave]))
          "a text without the sign of a hashtag or a mention")))
  (testing "what is no content"
    (doseq [source [nil {} {:html nil} {:html ""}]]
      (is (= [] (activitypub/parts source)) (pr-str source))
      (is (= [] (activitypub/hiccup source)) (pr-str source)))
    (is (= [{:type :text :text "Plain text\nof two lines"}] (activitypub/parts {:html "Plain text\nof two lines"}))))
  (testing "a custom emoji after a shortcode that the post does not have"
    (let [emoji {:type :emoji :shortcode "cat" :url "https://a.example/cat.png"}]
      (is (= [{:type :text :text "x:dog:cat: "} (assoc emoji :text ":cat:") {:type :text :text " and "} (assoc emoji :text ":cat:")]
             (activitypub/parts {:html "x:dog:cat: :cat: and :cat:" :tags [emoji]}))
          "the emoji is where its shortcode stands alone")))
  (testing "a long content reads in a short time"
    (let [emoji {:type :emoji :shortcode "cat" :url "https://a.example/cat.png"}
          lines (apply str (repeat 60000 "word\n"))
          cats  (apply str (repeat 40000 ":cat: "))]
      (is (true? (= (str/trimr lines) (text (activitypub/parts {:html lines})))))
      (is (= 79999 (count (activitypub/parts {:html cats :tags [emoji]}))))
      (is (= 79999 (count (drop 2 (first (activitypub/hiccup {:html cats :tags [emoji]} {:emoji? true})))))))))

(deftest a-post-of-the-at-protocol
  (let [facet  (fn [start end feature] {"index" {"byteStart" start "byteEnd" end} "features" [feature]})
        record {"text"   "😎 /cc @npmx.dev, see atproto.com/blog\n\n#Podcast #other"
                "facets" [(facet 9 18 {"$type" "app.bsky.richtext.facet#mention" "did" "did:plc:u5zp7npt5kpueado77kuihyz"})
                          (facet 24 40 {"$type" "app.bsky.richtext.facet#link" "uri" "https://atproto.com/blog"})
                          (facet 42 50 {"$type" "app.bsky.richtext.facet#tag" "tag" "Podcast"})
                          (facet 51 57 {"$type" "app.bsky.richtext.facet#unknown" "tag" "other"})]}]
    (is (= [{:type :text :text "😎 /cc "}
            {:type :mention :text "@npmx.dev" :id "did:plc:u5zp7npt5kpueado77kuihyz"}
            {:type :text :text ", see "}
            {:type :link :text "atproto.com/blog" :url "https://atproto.com/blog"}
            {:type :text :text "\n\n"}
            {:type :hashtag :text "#Podcast" :tag "Podcast"}
            {:type :text :text " #other"}]
           (atproto/parts record)))
    (is (= (get record "text") (text (atproto/parts record))))
    (is (= [[:p {} "😎 /cc " "@npmx.dev" ", see " [:a {:href "https://atproto.com/blog" :rel "nofollow ugc"} "atproto.com/blog"]
             [:br {}] [:br {}] "#Podcast" " #other"]]
           (atproto/hiccup record)))
    (is (= [[:p {} "😎 /cc " [:a {:href "https://bsky.app/profile/did:plc:u5zp7npt5kpueado77kuihyz"} "@npmx.dev"] ", see "
             [:a {:href "https://atproto.com/blog" :rel "nofollow ugc"} "atproto.com/blog"]
             [:br {}] [:br {}] [:a {:href "https://bsky.app/hashtag/Podcast"} "#Podcast"] " #other"]]
           (atproto/hiccup record {:link (fn [{:keys [type id tag]}]
                                           (case type
                                             :mention (str "https://bsky.app/profile/" id)
                                             :hashtag (str "https://bsky.app/hashtag/" tag)))})))
    (is (= (str "<p>😎 /cc @npmx.dev, see <a href=\"https://atproto.com/blog\" rel=\"nofollow ugc\">atproto.com/blog</a>"
                "<br><br>#Podcast #other</p>")
           (atproto/post-html record))
        "the content of the comment, without the links of an app")
    (doseq [r [nil {} {"text" ""} {"text" "\n"} {"facets" []}]]
      (is (= [] (atproto/parts r)) (pr-str r))
      (is (= [] (atproto/hiccup r)) (pr-str r)))))

(deftest the-comments-of-a-thread
  (let [root   "https://podcastindex.social/@dave/117294750060235480"
        send   (fn [{:keys [url]}]
                 (if-let [doc (get mastodon url)]
                   {:status 200 :url url :body (json/write-str doc)}
                   {:status 404 :url url}))
        skies  (first (keys bluesky))
        skies! (fn [{:keys [url]}]
                 (let [uri (second (re-find #"[?&]uri=([^&]+)" url))
                       doc (get bluesky #?(:clj (java.net.URLDecoder/decode ^String uri "UTF-8") :cljs (js/decodeURIComponent uri)))]
                   (if doc
                     {:status 200 :url url :body (json/write-str doc)}
                     {:status 400 :url url :body "{\"error\":\"NotFound\"}"})))
        ann    (secret-key 1)
        bob    (secret-key 2)
        note   (signed ann {:kind    1
                            :at      100
                            :content (str "Thanks nostr:" (nostr/encode {:type :npub :public-key (secp256k1/public-key bob)}) " #podcast")
                            :tags    [["i" "podcast:item:guid:1"] ["t" "podcast"]]})
        named  (signed bob {:kind 0 :at 50 :content (json/write-str {"name" "Bob"})})
        relay  (fn [{:keys [url filters]}]
                 {:url url :complete? true :events (filterv (fn [e] (some #(nostr/matches? % e) filters)) [note named])})
        linked (fn [thread]
                 (for [c (sort-by :id (filter :content (vals (:comments thread))))
                       :let [hiccup (comments/hiccup thread c {:link app-link})]
                       node (tree-seq sequential? seq hiccup)
                       :when (and (vector? node) (= :a (first node)) (str/starts-with? (str (:href (second node))) "/"))]
                   (:href (second node))))]
    (run (fn []
           (async/then (comments/fetch! {:protocol "activitypub" :uri root} {:send send :allow? any?})
                       (fn [thread]
                         (let [with-mention (filter #(some (comp #{:mention} :type) (comments/parts thread %)) (vals (:comments thread)))]
                           (is (seq with-mention) "the recorded thread has mentions")
                           (doseq [c (vals (:comments thread))
                                   :when (:content c)]
                             (is (= (:content c) (html/emit (comments/hiccup thread c))) (:id c))
                             (is (= (str/replace (html/text (:content c)) #"\s+" " ")
                                    (str/replace (text (comments/parts thread c)) #"\s+" " "))
                                 (:id c)))
                           (is (every? #(str/starts-with? % "/activitypub/profiles/https://") (linked thread)))
                           (is (seq (linked thread)))
                           (is (contains? (set (linked thread)) "/activitypub/profiles/https://podcastindex.social/users/dave")
                               "the id of the actor, and not the page that the post links to")
                           (is (= [{:type :mention :text "@cbrooklyn112" :url "https://mk.spook.social/@cbrooklyn112"}]
                                  (filter #(and (= :mention (:type %)) (nil? (:id %)))
                                          (mapcat #(comments/parts thread %) (vals (:comments thread)))))
                               "a mention that the post names in no tag has the page of the link, and no id")))))
         (fn []
           (async/then (comments/fetch! {:protocol "atproto" :uri skies} {:send skies! :allow? any? :appview "https://appview.example"})
                       (fn [thread]
                         (is (seq (:comments thread)))
                         (doseq [c (vals (:comments thread))
                                 :when (:content c)]
                           (is (= (:content c) (html/emit (comments/hiccup thread c))) (:id c))
                           (is (= (get-in c [::atproto/record "text"]) (text (comments/parts thread c))) (:id c)))
                         (is (every? #(re-matches #"/atproto/(profiles/did:[a-z]+:[a-z0-9]+|tags/.+)" %) (linked thread))))))
         (fn []
           (async/then (comments/fetch! {:protocol "nostr" :uri "podcast:item:guid:1"} {:send relay :relays ["wss://relay.example"]})
                       (fn [thread]
                         (let [c (get-in thread [:comments (get note "id")])]
                           (is (= "<p>Thanks @Bob #podcast</p>" (:content c)))
                           (is (= [{:type :text :text "Thanks "}
                                   {:type :mention :text "@Bob" :id (secp256k1/public-key bob)}
                                   {:type :text :text " "}
                                   {:type :hashtag :text "#podcast" :tag "podcast"}]
                                  (map #(select-keys % [:type :text :id :tag]) (comments/parts thread c))))
                           (is (= [[:p {} "Thanks "
                                    [:a {:href (str "/nostr/profiles/" (secp256k1/public-key bob))} "@Bob"]
                                    " "
                                    [:a {:href "/nostr/tags/podcast"} "#podcast"]]]
                                  (comments/hiccup thread c {:link app-link})))
                           (is (= [[:p {} "Thanks " "@Robert" " " "#podcast"]]
                                  (comments/hiccup thread c {:names {(secp256k1/public-key bob) "Robert"}}))
                               "the names of the app")
                           (is (= [] (comments/hiccup thread (get-in thread [:comments (:root thread)])))
                               "the root of a thread about an episode has no content"))))))))

(deftest the-text-of-a-comment
  (let [ann-key (secp256k1/public-key (secret-key 1))
        ann     (nostr/encode {:type :npub :public-key ann-key})
        receipt (nostr/encode {:type :nevent :id (apply str (repeat 64 "a")) :kind 9735})
        note    {"content" (str "Is this nostr:" ann " approved? 🤣 #40HPW\n\nhttps://fountain.fm/episode/x\n\nnostr:" receipt)
                 "tags"    [["i" "podcast:item:guid:1" "https://fountain.fm/episode/x"] ["t" "40HPW"]]}
        nostr   {:protocol "nostr" :authors {ann-key {:id ann-key :name "PODCONF"}}}]
    (testing "a note of Nostr, with the name of a profile in the place of its long name"
      (is (= "Is this @PODCONF approved? 🤣 #40HPW" (comments/text nostr {::nostr-comments/event note})))
      (is (= "Is this @Ann approved? 🤣 #40HPW"
             (comments/text nostr {::nostr-comments/event note} {:names {ann-key "Ann"}})))
      (is (re-matches #"Is this @npub1[a-z0-9]{7}…[a-z0-9]{6} approved\? 🤣 #40HPW"
                      (comments/text {:protocol "nostr"} {::nostr-comments/event note})))
      (is (= "One\ntwo\n\nThree https://example.org/a"
             (comments/text nostr {::nostr-comments/event {"content" "One\ntwo\n\n\nThree https://example.org/a" "tags" []}})))
      (is (= "" (comments/text nostr {::nostr-comments/event {"content" (str "https://fountain.fm/episode/x\n\nnostr:" receipt)
                                                              "tags"    (get note "tags")}}))
          "a payment without a message"))
    (testing "a post of the AT Protocol"
      (is (= "😎 one\n\n/cc @npmx.dev two\nthree"
             (comments/text {:protocol "atproto"}
                            {::atproto/record {"text" "😎 one\n\n/cc @npmx.dev two\nthree" "facets" []}}))))
    (testing "a post of ActivityPub, without the tags of its HTML"
      (is (= "@dave KDE :blobcat: and :unknown: #podcast see example.org\nbyea pixel\n\nTwo"
             (comments/text {:protocol "activitypub"} {::activitypub/source post})))
      (is (= "Shows:\n\n- one\n- two\n\nquoted\n\nbye\nnow"
             (comments/text {:protocol "activitypub"}
                            {::activitypub/source {:html (str "<p>Shows:</p><ul><li>one</li><li>two</li></ul>"
                                                              "<blockquote><p>quoted</p></blockquote><p>bye<br>now</p>")}})))
      (is (= "see example.org (https://example.org/x)"
             (comments/text {:protocol "activitypub"}
                            {::activitypub/source {:html "<p>see <a href=\"https://example.org/x\">example.org</a></p>"}}
                            {:links? true}))
          "each link with its URL"))
    (testing "the link function of an app does not count"
      (is (= "Is this @PODCONF approved? 🤣 #40HPW"
             (comments/text nostr {::nostr-comments/event note} {:link (constantly "https://example.org/") :links? true}))))
    (testing "a comment of an older reading, and a comment without a content"
      (is (= "Hello there\nyou"
             (comments/text {:protocol "atproto"} {:content "<p>Hello <a href=\"https://example.org/\">there</a><br>you</p>"})))
      (is (= "" (comments/text {:protocol "nostr"} {:id "1" :deleted? true}))))))

(deftest nodes-for-a-parent-element
  (let [event  {"content" "One\n\nTwo" "tags" []}
        record {"text" "One"}
        older  {:id "1" :content "<p>One</p>"}
        node?  #(or (string? %) (and (vector? %) (keyword? (first %))))]
    (doseq [[what nodes] {"of ActivityPub"      (activitypub/hiccup post)
                          "of the AT Protocol"  (atproto/hiccup record)
                          "of Nostr"            (nostr-comments/hiccup event)
                          "without a content"   (atproto/hiccup {})
                          "of a thread"         (comments/hiccup {:protocol "nostr"} {::nostr-comments/event event})
                          "of an older reading" (comments/hiccup {:protocol "atproto"} older)}]
      (is (not (vector? nodes)) (str what ": a renderer reads a vector as one element"))
      (is (sequential? nodes) what)
      (is (every? node? nodes) what))))

(deftest a-comment-of-an-older-reading
  (doseq [protocol ["activitypub" "atproto" "nostr" "matrix" nil]]
    (let [thread  {:protocol protocol}
          comment {:id "1" :content "<p>Hello <a href=\"https://example.org/\" rel=\"nofollow ugc\">there</a><br>you</p>"}]
      (is (= [[:p {} "Hello " [:a {:href "https://example.org/" :rel "nofollow ugc"} "there"] [:br {}] "you"]]
             (comments/hiccup thread comment {:link app-link}))
          protocol)
      (is (= [{:type :text :text "Hello there\nyou"}] (comments/parts thread comment)) protocol)
      (is (= [] (comments/hiccup thread {:id "2" :deleted? true})) protocol)
      (is (= [] (comments/parts thread {:id "2" :deleted? true})) protocol))))
