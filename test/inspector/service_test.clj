(ns inspector.service-test
  "The inspector's routes, driven through Pedestal's interceptor chain
  without a server, with feeds served by a stub instead of the network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.crypto.nip44 :as nip44]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.test-support :refer [secret-key with-relay]]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.value.nwc :as nwc]
            [inspector.service :as service]
            [io.pedestal.connector.test :refer [response-for]]))

(def example
  (slurp (io/resource "feeds/namespace-example.xml")))

(def connector
  (delay (service/connector 0)))

(defn- get*
  "GET `path` with the query `params`, percent-encoded as a browser would
  send them, which Pedestal's test helper insists on."
  [path & {:as params}]
  (response-for @connector :get
                (str path (when (seq params)
                            (str "?" (str/join "&" (for [[k v] params]
                                                     (str k "=" (java.net.URLEncoder/encode (str v) "UTF-8")))))))))

(deftest links-a-feed-chooses
  (with-redefs [http/fetch! (fn [url & _]
                              {:status 200 :url url
                               :body   (str "<rss version=\"2.0\"><channel><title>T</title>"
                                            "<item><title>E</title><guid>g</guid>"
                                            "<description><![CDATA[<a href=\"java&#9;script:alert(1)\">notes</a>]]></description>"
                                            "<enclosure url=\"javascript:alert(1)\" type=\"audio/mpeg\" length=\"1\"/></item>"
                                            "</channel></rss>")})]
    (doseq [[path & params] [["/feed" "url" "https://example.com/links.xml"]
                             ["/item" "url" "https://example.com/links.xml" "guid" "g"]]]
      (let [{:keys [status body]} (apply get* path params)]
        (is (= 200 status))
        (is (not (re-find #"href=\"[^\"]*script:" body)) (str path " links nowhere a script runs"))))))

(deftest the-pages
  (with-redefs [http/fetch! (fn [url & _]
                              (cond
                                (str/includes? url "missing") {:status 404 :url url}
                                (str/includes? url "op3")     {:status 200 :url url
                                                               :body   (str/replace example "url=\"https://example.com/file-"
                                                                                    "url=\"https://op3.dev/e/example.com/file-")}
                                :else                         {:status 200 :url url :body example}))]
    (testing "home"
      (let [{:keys [status body]} (get* "/")]
        (is (= 200 status))
        (is (str/includes? body "Podcast feed inspector"))))
    (testing "a feed inspected, as data and as canonical XML"
      (let [{:keys [status body]} (get* "/feed" "url" "https://example.com/one.xml")]
        (is (= 200 status))
        (is (str/includes? body "Podcasting 2.0 Namespace Example")))
      (is (str/includes? (:body (get* "/feed.edn" "url" "https://example.com/one.xml")) ":rss/title"))
      (let [{:keys [status headers body]} (get* "/feed.xml" "url" "https://example.com/one.xml")]
        (is (= 200 status))
        (is (str/starts-with? (get headers "Content-Type") "application/rss+xml"))
        (is (str/includes? body "<podcast:guid>"))))
    (testing "an item"
      (let [{:keys [status body]} (get* "/item" "url" "https://example.com/one.xml" "guid" "https://example.com/ep0003")]
        (is (= 200 status))
        (is (str/includes? body "ep0003"))
        (is (str/includes? body "item :rss/title") "the resolved properties with their sources")))
    (testing "a feed whose enclosures go through OP3 links to its downloads there"
      (is (str/includes? (:body (get* "/feed" "url" "https://example.com/op3.xml"))
                         "href=\"https://op3.dev/show/y0ur-gu1d-g035-h3r3\""))
      (is (not (str/includes? (:body (get* "/feed" "url" "https://example.com/one.xml")) "downloads at OP3"))))
    (testing "no URL goes home; a feed that cannot be fetched is an error page"
      (is (= 302 (:status (get* "/feed"))))
      (let [{:keys [status body]} (get* "/feed" "url" "https://example.com/missing.xml")]
        (is (= 502 status))
        (is (str/includes? body "HTTP 404"))))))

(deftest the-check-page
  (reset! service/cache {})
  (let [runs  (atom [])
        files [{:kind :media :url "https://example.com/e.mp3" :path [:rss/items 0 :rss/enclosure]
                :declared {:type "audio/mpeg"}
                :facts {:head {:status 200 :type "audio/mpeg" :length 1000}
                        :get  {:status 200 :type "audio/mpeg" :length 1000}}}
               {:kind :media :url "https://example.com/f.mp3" :path [:rss/items 1 :rss/enclosure]
                :declared {:type "audio/mpeg"}
                :facts {:error {:id     :file/unreachable
                                :level  :warning
                                :reason "java.net.ConnectException: java.nio.channels.UnresolvedAddressException"}}}
               {:kind :image :url "javascript:alert(1)" :path [] :declared {:sources #{:itunes}}
                :facts {:head {:status 200 :type "image/png"} :image {:type "image/png" :width 1000 :height 1000 :color :rgb}}}]]
    (with-redefs [http/fetch! (fn [url & _] {:status 200 :url url :body example})
                  check/feed! (fn [_ opts]
                                (swap! runs conj opts)
                                {:files files :report (check/report files (:profile opts))})]
      (let [{:keys [status body]} (get* "/check" "url" "https://example.com/check.xml" "items" "3")]
        (is (= 200 status))
        (is (= [{:url "https://example.com/check.xml" :items 3 :read? true :integrity? false}] @runs))
        (is (str/includes? body "check/ranges") "the findings of each file")
        (is (str/includes? body "UnresolvedAddressException") "the reason a file did not come")
        (is (not (str/includes? body "artwork-size")) "no profile, no artwork rules")
        (is (not (re-find #"href=\"[^\"]*script:" body)) "a URL that runs a script is no link"))
      (let [body (:body (get* "/check" "url" "https://example.com/check.xml" "items" "3" "profile" "apple"))]
        (is (str/includes? body "check/artwork-size"))
        (is (= 1 (count @runs)) "another profile judges the same check again, and sends no request"))
      (get* "/check" "url" "https://example.com/check.xml" "items" "999" "integrity" "1")
      (is (= [50 true] ((juxt :items :integrity?) (last @runs))) "at most 50 items"))))

(deftest the-comments-page
  (reset! service/cache {})
  (let [root    "https://podcastindex.social/@dave/117294750060235480"
        answers (json/read-str (slurp (io/resource "comments/mastodon.json")))
        send    (fn [{:keys [url]}]
                  (if-let [doc (get answers url)]
                    {:status 200 :url url :body (json/write-str doc)}
                    {:status 404 :url url}))
        read    comments/fetch!]
    (with-redefs [http/fetch!    (fn [url & _]
                                   {:status 200 :url url
                                    :body   (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">"
                                                 "<channel><title>T</title>"
                                                 "<item><title>Talked</title><guid>t</guid>"
                                                 "<podcast:socialInteract protocol=\"activitypub\" uri=\"" root "\"/></item>"
                                                 "<item><title>Quiet</title><guid>q</guid>"
                                                 "<podcast:socialInteract protocol=\"disabled\"/></item>"
                                                 "<item><title>Silent</title><guid>s</guid></item>"
                                                 "</channel></rss>")})
                  comments/fetch! (fn [interact opts] (read interact (assoc opts :send send)))]
      (testing "the thread of an episode as a tree, and a link to it from the item page"
        (let [{:keys [status body]} (get* "/comments" "url" "https://example.com/talk.xml" "guid" "t")]
          (is (= 200 status))
          (is (str/includes? body "Quantum Panhandler"))
          (is (str/includes? body "@cbrooklyn112@mk.spook.social"))
          (is (re-find #"<div class=\"notes\"><p>" body) "the content of a comment as elements")
          (is (not (str/includes? body "[:p")) "and not as the text of its Hiccup")
          (is (not (re-find #"href=\"[^\"]*script:" body))))
        (is (str/includes? (:body (get* "/item" "url" "https://example.com/talk.xml" "guid" "t")) "/comments?url=")))
      (testing "an episode whose podcaster wants no comments shown, one with no thread, and no episode"
        (is (str/includes? (:body (get* "/comments" "url" "https://example.com/talk.xml" "guid" "q")) "wants no public comments"))
        (let [body (:body (get* "/comments" "url" "https://example.com/talk.xml" "guid" "s"))]
          (is (str/includes? body "names no thread"))
          (is (str/includes? body "podcast:item:guid:s") "the notes about the episode on Nostr")
          (is (str/includes? body "The relays have no note about this episode")))
        (is (= 404 (:status (get* "/comments" "url" "https://example.com/talk.xml" "guid" "nope")))))
      (testing "the comments below a post that an address names"
        (let [{:keys [status body]} (get* "/thread" "uri" root)]
          (is (= 200 status))
          (is (str/includes? body "Quantum Panhandler"))
          (is (str/includes? body "over activitypub"))
          (is (re-find #"<div class=\"notes\"><p>" body))
          (is (str/includes? body "/thread?uri=https%3A%2F%2Fpodcastindex.social%2F%40dave%2F117294750060235480&amp;hidden=1")))
        (let [{:keys [status body]} (get* "/thread")]
          (is (= 200 status))
          (is (str/includes? body "The address of a post"))
          (is (not (str/includes? body "does not know this address"))))
        (is (str/includes? (:body (get* "/thread" "uri" "not an address")) "does not know this address"))
        (is (str/includes? (:body (get* "/")) "action=\"/thread\"") "a form on the front page")))))

(deftest the-address-of-a-post
  (doseq [[address interact]
          {"https://podcastindex.social/@dave/117294750060235480"
           {:protocol "activitypub" :uri "https://podcastindex.social/@dave/117294750060235480"}

           " https://bsky.app/profile/atproto.com/post/3msydg6sd7s2d?x=1 "
           {:protocol "atproto" :uri "at://atproto.com/app.bsky.feed.post/3msydg6sd7s2d"}

           "at://did:plc:ewvi7nxzyoun6zhxrhs64oiz/app.bsky.feed.post/3msydg6sd7s2d"
           {:protocol "atproto" :uri "at://did:plc:ewvi7nxzyoun6zhxrhs64oiz/app.bsky.feed.post/3msydg6sd7s2d"}

           "nostr:note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy"
           {:protocol "nostr" :uri "nostr:note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy"}

           "note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy"
           {:protocol "nostr" :uri "note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy"}

           "https://njump.me/note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy"
           {:protocol "nostr" :uri "nostr:note1tjpa5aa0rhkx6u5fsdye3tt6477eugv389khtmpucfl45aezdumqkkhgwy"}

           "https://njump.me/note1tjpa5aa0rhkx6u5fsdye3tt6477eugv3897htmpucflh5aezdumqxwh3jl"
           {:protocol "activitypub" :uri "https://njump.me/note1tjpa5aa0rhkx6u5fsdye3tt6477eugv3897htmpucflh5aezdumqxwh3jl"}

           "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"
           {:protocol "nostr" :uri "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"}

           "not an address" nil
           "javascript:alert(1)" nil
           "" nil}]
    (is (= interact (#'service/interact-of address)) (pr-str address)))
  (is (nil? (#'service/interact-of nil))))

(deftest quotes-and-parameters
  (with-redefs [http/fetch! (fn [url & _]
                              {:status 200 :url url
                               :body   (str "<rss version=\"2.0\"><channel><title>A \"quoted\" show</title>"
                                            "<item><title>E</title><guid>g</guid></item></channel></rss>")})]
    (testing "a double quote stays a double quote, in text and in attribute values"
      (let [body (:body (get* "/feed" "url" "https://example.com/quoted.xml" "q" "say \"hi\""))]
        (is (str/includes? body "A &quot;quoted&quot; show"))
        (is (str/includes? body "value=\"say &quot;hi&quot;\""))
        (is (not (str/includes? body "&#39;")))))
    (testing "a parameter given twice counts once, and a profile or an order the page does not offer is left out"
      (is (= 200 (:status (response-for @connector :get "/feed?url=https%3A%2F%2Fexample.com%2Fquoted.xml&url=x"))))
      (is (= 200 (:status (get* "/feed" "url" "https://example.com/quoted.xml" "profile" "bogus"))))
      (is (= 200 (:status (get* "/feed" "url" "https://example.com/quoted.xml" "order" "recent")))))))

(deftest fetching-once
  (reset! service/cache {})
  (let [url   "https://example.com/once.xml"
        calls (atom [])]
    (with-redefs [http/fetch! (fn [u & [opts]]
                                (when (= url u) (swap! calls conj opts))
                                (if (:etag opts)
                                  {:status 304 :url u :etag "v1"}
                                  {:status 200 :url u :etag "v1" :body example}))]
      (testing "the pages of a feed fetch and parse it once"
        (is (= 200 (:status (get* "/feed" "url" url))))
        (is (= 200 (:status (get* "/item" "url" url "guid" "https://example.com/ep0003"))))
        (is (= 200 (:status (get* "/search" "url" url "q" "future"))))
        (is (= [{}] @calls)))
      (testing "and later ask the server whether it changed"
        (with-redefs [service/cache-seconds 0]
          (is (= 200 (:status (get* "/feed" "url" url)))))
        (is (= {:etag "v1"} (last @calls))))
      (testing "the cache keeps the most recently used"
        (with-redefs [service/cache-limit 2]
          (doseq [name ["a" "b" "c"]]
            (get* "/feed" "url" (str "https://example.com/" name ".xml")))
          (is (= 2 (count @service/cache))))))))

(deftest search-and-selection
  (with-redefs [http/fetch! (fn [url & _] {:status 200 :url url :body example})]
    (testing "the feed page filters and orders its items by the library's criteria"
      (let [body (:body (get* "/feed" "url" "https://example.com/one.xml" "q" "future" "order" "oldest"))]
        (is (str/includes? body "Items (1 of 3)"))
        (is (str/includes? body "Episode 3 - The Future"))
        (is (not (str/includes? body "Episode 1 - The Past"))))
      (let [body (:body (get* "/feed" "url" "https://example.com/one.xml" "order" "oldest"))]
        (is (str/includes? body "Items (3)"))
        (is (< (str/index-of body "Episode 1 - The Past") (str/index-of body "Episode 3 - The Future")))))
    (testing "full-text search over one feed, with a snippet of the notes"
      (let [{:keys [status body]} (get* "/search" "url" "https://example.com/one.xml" "q" "future")]
        (is (= 200 status))
        (is (str/includes? body "Episode 3 - The Future"))
        (is (str/includes? body "<mark>future</mark>"))
        (is (not (str/includes? body "Episode 2 - The Present"))))
      (is (str/includes? (:body (get* "/search" "url" "https://example.com/one.xml" "q" "nothing-like-this"))
                         "Nothing matched")))
    (testing "search over the corpus answers whatever the corpus holds"
      (is (= 200 (:status (get* "/search" "q" "podcasting")))))))

(defn- directory-fixture
  "The recorded answer of the directory that `url` addresses."
  [url]
  (slurp (io/resource
          (cond
            (str/includes? url "itunes.apple.com/search")     "directory/apple-search.json"
            (str/includes? url "itunes.apple.com/lookup")     "directory/apple-lookup.json"
            (str/includes? url "/rss/toppodcasts/")           "directory/apple-genre-chart.json"
            (str/includes? url "gpodder.net/api/2/tag/")      "directory/gpodder-toplist.json"
            (str/includes? url "rss.marketingtools.apple.com") "directory/apple-charts.json"
            (str/includes? url "api.fyyd.de/0.2/search")      "directory/fyyd-search.json"
            (str/includes? url "api.fyyd.de/0.2/feature")     "directory/fyyd-hot.json"
            (str/includes? url "gpodder.net/search")          "directory/gpodder-search.json"
            (str/includes? url "gpodder.net/toplist")         "directory/gpodder-toplist.json"
            (str/includes? url "api.podcastindex.org")        "index/search-byterm.json"))))

(deftest the-directories
  (with-redefs [http/call! (fn [{:keys [url]}] {:status 200 :url url :body (directory-fixture url)})]
    (testing "a search across the directories, combined, each result linking to its feed"
      (let [{:keys [status body]} (get* "/directory" "q" "podcasting 2.0")]
        (is (= 200 status))
        (is (str/includes? body "Podcasting 2.0"))
        (is (str/includes? body "fyyd"))
        (is (str/includes? body (str "/feed?url=" (java.net.URLEncoder/encode "https://feeds.podcastindex.org/pc20.xml" "UTF-8"))))))
    (testing "the charts without a query"
      (let [{:keys [status body]} (get* "/directory" "country" "dk")]
        (is (= 200 status))
        (is (str/includes? body "Mørkeland"))
        (is (str/includes? body "rank 1"))
        (is (str/includes? body "/directory?country=dk&amp;category=Society%20%26%20Culture")
            "with the links to the charts of some categories")))
    (testing "the charts of one category"
      (let [{:keys [status body]} (get* "/directory" "country" "dk" "category" "Technology")]
        (is (= 200 status))
        (is (str/includes? body "The charts, dk, Technology"))
        (is (str/includes? body "Jagten på det evige liv"))
        (is (str/includes? body "fyyd has no chart by category") "a directory without such a chart tells so")))))

(defn- hive-fixture
  "The recorded answer of a Hive node to the JSON-RPC `body`, with the head
  of the chain where the twenty blocks the page reads start at the two
  recorded ones."
  [body]
  (if (str/includes? body "get_dynamic_global_properties")
    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"head_block_number\":110255098,\"last_irreversible_block_num\":110255083}}"
    (slurp (io/resource
            (cond
              (str/includes? body "get_block_range")  "podping/blocks.json"
              (str/includes? body "get_ops_in_block") "podping/operations.json"
              (str/includes? body "get_following")    "podping/following.json")))))

(deftest the-podpings
  (with-redefs [http/call! (fn [{:keys [url body]}] {:status 200 :url url :body (hive-fixture body)})]
    (let [{:keys [status body]} (get* "/podping")]
      (is (= 200 status))
      (is (str/includes? body "podping.eee"))
      (is (not (str/includes? body "pp_podcast_update")) "the id is not shown, the medium and reason are")
      (is (str/includes? body "Head block 110255098"))
      (is (str/includes? body "writers podping follows"))))
  (with-redefs [http/call! (fn [_] (throw (ex-info "offline" {})))]
    (let [{:keys [status body]} (get* "/podping")]
      (is (= 502 status) "a chain that cannot be read is an error page")
      (is (str/includes? body "Could not read the Hive chain")))))

(deftest the-player
  (with-redefs [http/fetch! (fn [url & _] {:status 200 :url url :body example})]
    (testing "every page carries the player bar and its script; the queue page the shell of the detailed view"
      (let [{:keys [status body]} (get* "/")]
        (is (= 200 status))
        (is (str/includes? body "id=\"player-bar\""))
        (is (str/includes? body "id=\"content\""))
        (is (str/includes? body "/js/player.js")))
      (let [{:keys [status body]} (get* "/queue")]
        (is (= 200 status))
        (is (str/includes? body "id=\"app\""))))
    (testing "the item page links into the player"
      (let [body (:body (get* "/item" "url" "https://example.com/one.xml" "guid" "https://example.com/ep0003"))]
        (is (str/includes? body "/queue?add="))
        (is (str/includes? body "mode=next"))))
    (testing "the episode endpoint answers with what the player needs, as EDN"
      (let [{:keys [status headers body]} (get* "/episode.edn" "url" "https://example.com/one.xml" "guid" "https://example.com/ep0003")
            episode (read-string body)]
        (is (= 200 status))
        (is (str/starts-with? (get headers "Content-Type") "application/edn"))
        (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"] (:id episode)))
        (is (string? (:enclosure episode)))
        (is (string? (:title episode))))
      (is (= 404 (:status (get* "/episode.edn" "url" "https://example.com/one.xml" "guid" "nope")))))
    (testing "the compiled player is served when it exists and explained when it does not"
      (let [{:keys [status body]} (get* "/js/player.js")]
        (is (contains? #{200 404} status))
        (when (= 404 status)
          (is (str/includes? body "compile player"))))
      (is (= 404 (:status (get* "/js/../deps.edn")))))))

(def wallet-secret (secret-key 9))
(def app-secret (secret-key 7))

(defn- wallet-answer
  "What a relay with a wallet of play money answers to `x`, the filters of
  a request or an event. `seen` gets each command. The wallet pays each
  keysend payment, and no invoice."
  [seen x]
  (let [eose (json/write-str ["EOSE" nostr/subscription])
        hex  (apply str (repeat 64 "7"))]
    (cond
      (and (vector? x) (= [13194] (get (first x) "kinds")))
      [(json/write-str ["EVENT" nostr/subscription
                        (nostr/signed {"kind" 13194 "created_at" 1790000000 "tags" [["encryption" "nip44_v2"]]
                                       "content" "pay_keysend get_balance"}
                                      wallet-secret hex)])
       eose]

      (vector? x)
      [eose]

      :else
      (let [shared  (nip44/conversation-key wallet-secret (get x "pubkey"))
            command (json/read-str (nip44/decrypt (get x "content") shared))
            body    {"result_type" (get command "method")
                     "result"      (case (get command "method")
                                     "get_balance" {"balance" 21000000}
                                     "pay_keysend" {"preimage" "0123456789abcdef" "fees_paid" 1000})}]
        (swap! seen conj command)
        [(json/write-str ["OK" (get x "id") true ""])
         (json/write-str ["EVENT" nostr/subscription
                          (nostr/signed {"kind" 23195 "created_at" 1790000001
                                         "tags" [["p" (get x "pubkey")] ["e" (get x "id")]]
                                         "content" (nip44/encrypt (json/write-str body) shared hex)}
                                        wallet-secret hex)])]))))

(defn- post*
  "POST the form `params` to `path`, as a browser sends a form."
  [path params]
  (response-for @connector :post path
                :headers {"content-type" "application/x-www-form-urlencoded"}
                :body (str/join "&" (for [[k v] params]
                                      (str k "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))))

(deftest the-wallet-page
  (with-redefs [http/fetch! (fn [url & _] {:status 200 :url url :body example})]
    (let [episode ["url" "https://example.com/one.xml" "guid" "https://example.com/ep0003"]]
      (testing "the form, and the form of a boost for an episode"
        (let [{:keys [status body]} (get* "/wallet")]
          (is (= 200 status))
          (is (str/includes? body "type=\"password\""))
          (is (not (str/includes? body "name=\"sats\""))))
        (let [body (:body (apply get* "/wallet" episode))]
          (is (str/includes? body "name=\"sats\""))
          (is (str/includes? body "podcaster") "the recipients of the value block"))
        (is (str/includes? (:body (apply get* "/item" episode)) "/wallet?url=") "an episode with a value block links to its boost"))
      (testing "a text that is no connection"
        (let [{:keys [status body]} (post* "/wallet" {"uri" "nostr+walletconnect://abc?secret=s3cr3t"})]
          (is (= 200 status))
          (is (str/includes? body "is no connection URI"))
          (is (not (str/includes? body "s3cr3t")))))
      (let [seen (atom [])]
        (with-relay #(wallet-answer seen %) (atom [])
          (fn [relay]
            (let [uri (nwc/uri {:wallet (secp256k1/public-key wallet-secret) :relays [relay] :secret app-secret})]
              (testing "what the wallet takes and what it holds"
                (let [{:keys [status body]} (post* "/wallet" {"uri" uri})]
                  (is (= 200 status))
                  (is (str/includes? body "pay_keysend, get_balance"))
                  (is (str/includes? body "21000 sats"))
                  (is (str/includes? body "Not asked") "the wallet does not take get_info, so the inspector does not ask")
                  (is (str/includes? body (secp256k1/public-key app-secret)))
                  (is (not (str/includes? body app-secret)) "the page has no part of the secret")
                  (is (= ["get_balance"] (map #(get % "method") @seen)))))
              (testing "a boost for an episode"
                (reset! seen [])
                (let [form  (into {"uri" uri "sats" "1000" "message" "Thanks"} (map vec (partition 2 episode)))
                      token #(second (re-find #"name=\"token\" value=\"([0-9a-f]{32})\"" %))
                      first (:body (apply get* "/wallet" episode))
                      body  (:body (post* "/wallet" (assoc form "token" (token first))))
                      paid  (filter #(= "pay_keysend" (get % "method")) @seen)]
                  (testing "a form pays once"
                    (let [sent (count @seen)]
                      (doseq [again [(assoc form "token" (token first)) form (assoc form "token" "abc")]]
                        (is (str/includes? (:body (post* "/wallet" again)) "The inspector paid nothing")))
                      (is (= [] (filter #(= "pay_keysend" (get % "method")) (drop sent @seen))))
                      (is (not= (token first) (token body)) "the page of the answer has a form with a token of its own")))
                  (is (str/includes? body "A boost of 1000 sats"))
                  (is (= 3 (count (re-seq #"Paid, with a fee of 1 sat<" body))))
                  (is (not (str/includes? body app-secret)))
                  (is (= [490000 10000 500000] (map #(get-in % ["params" "amount"]) paid))
                      "the splits 49, 1 and 50 of the value block of the episode")
                  (is (= ["036557ea56b3b86f08be31bcd2557cae8021b0e3a9413f0c0e52625c6696972e57"
                          "036557ea56b3b86f08be31bcd2557cae8021b0e3a9413f0c0e52625c6696972e57"
                          "02e12fea95f576a680ec1938b7ed98ef0855eadeced493566877d404e404bfbf52"]
                         (map #(get-in % ["params" "pubkey"]) paid)))
                  (is (some #{"get_balance"} (map #(get % "method") (drop 3 @seen))) "the balance after the payments")))
              (testing "no amount, no payment"
                (reset! seen [])
                (post* "/wallet" (into {"uri" uri "sats" ""} (map vec (partition 2 episode))))
                (is (= ["get_balance"] (map #(get % "method") @seen)))))))))))
