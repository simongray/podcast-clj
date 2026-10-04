(ns dk.simongray.podcast.feed.discovery-test
  "Finding the feeds of a show from what a listener types: the text read as
  an address, the feeds that a page names, the ranking, and find-feeds!
  over a network of fixed answers."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]))

(defn- feed-xml
  "An RSS feed with the `title` and the `opts`: its website as :link, its
  :self link, its podcast :guid, and :episode? for an item with an
  enclosure."
  [title {:keys [link self guid episode?] :as opts}]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\""
       " xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel>"
       "<title>" title "</title>"
       (when link (str "<link>" link "</link>"))
       (when self (str "<atom:link rel=\"self\" type=\"application/rss+xml\" href=\"" self "\"/>"))
       (when guid (str "<podcast:guid>" guid "</podcast:guid>"))
       "<item><title>One</title><guid>one</guid>"
       (when episode? "<enclosure url=\"https://media.example/1.mp3\" length=\"1\" type=\"audio/mpeg\"/>")
       "</item></channel></rss>"))

(defn- page
  "A web page titled The Show, with the `parts` in its head."
  [& parts]
  (str "<!DOCTYPE html><html><head><title>Home | The Show</title>" (apply str parts)
       "</head><body><p>Listen</p></body></html>"))

(defn- alternate
  "A link element to an RSS feed at `href`."
  [href]
  (str "<link rel=alternate type=application/rss+xml href=\"" href "\">"))

(defn- network
  "A sender that answers each URL by `answers`, a map of URL to the part
  of a response that isn't a 200 without headers, or to :late for a server
  that doesn't answer in time. It fails for any other URL, and notes each
  URL in `log`. It refuses a URL that the :allow? of the request doesn't
  take, as http/send! does."
  [answers log]
  (fn [{:keys [url allow?] :or {allow? (constantly true)}}]
    (swap! log conj url)
    (cond
      (not (allow? url))          (throw (http/refusal url))
      (= :late (get answers url)) (throw (ex-info (str "No answer in time from " url) {:type ::http/timeout :url url}))
      (contains? answers url)     (merge {:status 200 :url url :headers {}} (get answers url))
      :else                       (throw (ex-info (str "No route to " url) {:url url})))))

(defn- found
  "The :url and :found-by of each of the `feeds` that find-feeds! gives."
  [feeds]
  (mapv (juxt :url :found-by) feeds))

(deftest reading-what-a-listener-types
  (testing "an address without a scheme gets https, and http can follow"
    (is (= {:url "https://podnews.net/rss" :typed? true} (discovery/read-input " podnews.net/rss ")))
    (is (= {:url "https://example.com:8080/feed" :typed? true} (discovery/read-input "example.com:8080/feed"))
        "a domain with a port has no scheme")
    (is (= {:url "https://localhost:8000/rss" :typed? true} (discovery/read-input "localhost:8000/rss"))))
  (testing "an address with a scheme stays, without its fragment"
    (is (= {:url "http://a.example/feed.xml"} (discovery/read-input "http://a.example/feed.xml#top"))))
  (testing "the schemes that only mark a feed"
    (is (= {:url "https://a.example/feed.xml"} (discovery/read-input "feed:https://a.example/feed.xml")))
    (is (= {:url "https://a.example/feed.xml" :typed? true} (discovery/read-input "feed://a.example/feed.xml")))
    (is (= {:url "https://a.example/feed.xml" :typed? true} (discovery/read-input "ITPC://a.example/feed.xml"))))
  (testing "the links of apps"
    (is (= {:url "https://a.example/feed.xml" :typed? true} (discovery/read-input "pktc://subscribe/a.example/feed.xml")))
    (is (= {:url "https://a.example/feed.xml"}
           (discovery/read-input "https://antennapod.org/deeplink/subscribe?url=https%3A%2F%2Fa.example%2Ffeed.xml")))
    (is (= {:apple-id "1200361736"}
           (:ids (discovery/read-input "https://podcasts.apple.com/us/podcast/the-daily/id1200361736?i=1000")))))
  (testing "a fast-follow link of the podcast namespace"
    (is (= {:url  "https://podnews.net/podcast/i8xe9"
            :guid "9b024349-ccf0-5f69-a609-6b82873eab3c"}
           (discovery/read-input "https://podnews.net/podcast/i8xe9#fastfollow-podcast:9b024349-ccf0-5f69-a609-6b82873eab3c"))))
  (testing "words and other schemes are no address"
    (doseq [s ["the daily" "theringer" "mailto:a@b.example" "javascript:alert(1)" ""]]
      (is (= :discovery/unreadable (:id (:error (discovery/read-input s)))) s))))

(deftest feeds-a-page-names
  (let [page (str "<!DOCTYPE html><html><head><title>A show</title>"
                  "<link rel=\"alternate\" type=\"application/rss+xml\" title=\"The show\" href=\"/feed.xml\">"
                  "<link rel=\"Alternate Feed\" type=\"application/atom+xml\" href=\"https://b.example/atom\">"
                  "<link rel=alternate type=text/html href=/other>"
                  "<link rel=alternate type=application/rss+xml>"
                  "<link rel=stylesheet href=style.css></head><body><p>Listen</p></body></html>")]
    (is (= [{:href "/feed.xml" :type "application/rss+xml" :title "The show"}
            {:href "https://b.example/atom" :type "application/atom+xml"}]
           (discovery/feed-links page))
        "RSS and Atom links only, and none without an href")
    (is (= ["https://a.example/feed.xml" "https://b.example/atom"]
           (map :href (discovery/feed-links page "https://a.example/show/")))
        "made absolute against the address of the page")
    (testing "only an address that an app can fetch"
      (let [link  #(str "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"" % "\">")
            hrefs ["javascript:alert(1)" "data:text/xml,x" "file:///etc/passwd" " java\tscript:alert(1)" "feed.xml"]
            page  (apply str (map link hrefs))]
        (is (= ["https://a.example/show/feed.xml"] (map :href (discovery/feed-links page "https://a.example/show/"))))
        (is (= ["feed.xml"] (map :href (discovery/feed-links page))))))
    (testing "a type with a parameter, and attributes without a value"
      (is (= [{:href "https://a.example/feed.xml" :type "application/rss+xml"}]
             (discovery/feed-links (str "<link rel=alternate type=\"Application/RSS+XML; charset=utf-8\" href=/feed.xml title>"
                                        "<link rel=alternate type=application/rss+xml href>"
                                        "<link rel=alternate type href=/other.xml>")
                                   "https://a.example/"))))
    (testing "the base element of the page, as in a browser"
      (let [page #(str "<base target=_blank><base href=\"" % "\"><base href=\"https://late.example/\">"
                       "<link rel=alternate type=application/rss+xml href=feed.xml>")]
        (is (= ["https://cdn.example/shows/feed.xml"]
               (map :href (discovery/feed-links (page "https://cdn.example/shows/") "https://a.example/show/"))))
        (is (= ["https://a.example/feeds/feed.xml"] (map :href (discovery/feed-links (page "/feeds/") "https://a.example/show/")))
            "relative to the address of the page")
        (is (= ["https://cdn.example/shows/feed.xml"] (map :href (discovery/feed-links (page "https://cdn.example/shows/"))))
            "and with no address of the page")
        (is (= ["https://a.example/show/feed.xml"]
               (map :href (discovery/feed-links (page "javascript:void(0)") "https://a.example/show/")))
            "a base that is no address on the web is none")))))

(deftest feeds-in-the-link-header
  (is (= [{:href "https://a.example/feed.xml" :type "application/rss+xml" :title "The show"}]
         (discovery/header-links {"link" ["</feed.xml>; rel=\"alternate\"; type=\"application/rss+xml\"; title=\"The show\""
                                          "</wp-json/>; rel=\"https://api.w.org/\""]}
                                 "https://a.example/page"))))

(deftest links-that-look-like-feeds
  (let [page (str "<a href=https://cdn.example/show.rss>RSS</a><a href=/feed>Feed</a>"
                  "<a href=/feedback>Feedback</a><a href=/about>About</a><a href=#top>Top</a>"
                  "<a href=mailto:rss@a.example>Mail</a><a href=/feed#x>Again</a>")]
    (is (= ["https://a.example/feed" "https://cdn.example/show.rss"]
           (discovery/feed-anchors page "https://a.example/"))
        "on the domain of the page first, each once, and no word that only starts like one")))

(deftest guesses-and-rules
  (is (= ["https://a.example/feed/" "https://a.example/rss" "https://a.example/feed.xml" "https://a.example/index.xml"]
         (discovery/guesses "https://a.example/shows/x?y=1")))
  (is (= ["https://feeds.host.example/myshow.xml"]
         (discovery/rule-urls "https://myshow.host.example/"
                              [[#"^https://([a-z0-9-]+)\.host\.example/?$" "https://feeds.host.example/$1.xml"]
                               [#"^https://other\.example/" "https://other.example/feed"]]))))

(deftest the-name-of-a-page
  (is (= "The Show" (discovery/page-name "<title>Home | The Show</title>" "https://show.example/"))
      "the longest part of a title")
  (is (= "The Show"
         (discovery/page-name "<meta property=og:site_name content='The Show'><title>Home – The Show</title>"
                              "https://show.example/"))
      "a home page by the name of its site")
  (is (= "CrimeLess"
         (discovery/page-name "<meta content=iHeart property=og:site_name><meta content='CrimeLess | iHeart' property=og:title>"
                              "https://www.iheart.com/podcast/crimeless/"))
      "another page by its title, without the name of the site")
  (is (nil? (discovery/page-name "<p>No name</p>" "https://a.example/"))))

(deftest a-feed-or-a-page
  (is (discovery/feed-document? "<?xml version=\"1.0\"?>\n<?xml-stylesheet href=\"s.xsl\"?>\n<!-- a > b -->\n<rss version=\"2.0\">"))
  (is (discovery/feed-document? "<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
  (is (discovery/feed-document? "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"))
  (is (not (discovery/feed-document? "<!DOCTYPE html><html><head><title>rss</title></head>")))
  (is (not (discovery/feed-document? "<rss-reader>")) "an element whose name only starts like one")
  (is (discovery/feed-document? (str (apply str (repeat 800 "<?a?>")) "<rss>"))
      "many processing instructions, and the stack holds")
  (is (not (discovery/feed-document? (str (apply str (repeat 3000 "<!---->")) "<rss>")))
      "only the start of a long document counts"))

(deftest the-website-of-a-feed
  (is (discovery/same-website? "https://Example.com" "http://www.example.com/podcast/"))
  (is (discovery/same-website? "https://example.com/show/" "https://www.example.com/show"))
  (is (not (discovery/same-website? "https://example.com/show" "https://example.com/other")))
  (is (not (discovery/same-website? "https://alice.substack.com/" "https://bob.substack.com/"))
      "only www. is left out of a domain")
  (is (not (discovery/same-website? "https://example.com/" nil))))

(deftest known-feeds
  (let [feed  (rss/parse (feed-xml "A" {:link "https://a.example/" :episode? true}))
        known {feed "https://feeds.example/a.xml"}]
    (is (= [["https://feeds.example/a.xml" :known]] (found (discovery/known-matches "https://A.example" known)))
        "by the website of the feed")
    (is (= [["https://feeds.example/a.xml" :known]] (found (discovery/known-matches "http://feeds.example/a.xml/" known)))
        "by its URL")
    (is (empty? (discovery/known-matches "https://b.example/" known)))))

(deftest ranking-the-candidates
  (let [guid  "917393e3-1b1e-5cef-ace4-edaa54e1f810"
        mp3   (rss/parse (feed-xml "Show" {:self "https://feeds.example/mp3" :guid guid :episode? true}))
        opus  (rss/parse (feed-xml "Show" {:self "https://feeds.example/opus" :guid guid :episode? true}))
        blog  (rss/parse (feed-xml "Blog" {}))
        other (rss/parse (feed-xml "Other" {:episode? true}))]
    (is (= [["https://a.example/feed/mp3" :link]
            ["https://a.example/feed/opus" :link]
            ["https://x.example/other.xml" :directory]
            ["https://a.example/feed" :link]]
           (found (discovery/ranked [(discovery/candidate "https://x.example/other.xml" :directory other)
                                     (discovery/candidate "https://a.example/feed" :link blog)
                                     (discovery/candidate "https://a.example/feed/mp3" :link mp3)
                                     (discovery/candidate "https://feeds.example/mp3" :guess mp3)
                                     (discovery/candidate "https://a.example/feed/opus" :link opus)])))
        "podcasts first, then by the place, once by self link, and two formats of one show apart")))

(deftest finding-the-feeds-of-a-show
  (let [log      (atom [])
        opts     (fn [answers & {:as more}]
                   (reset! log [])
                   (merge {:send (network answers log) :allow? (constantly true)} more))
        show     (feed-xml "The Show" {:link "https://show.example/" :episode? true})
        blog     (feed-xml "The Blog" {:link "https://show.example/"})
        search   (:url (directory/request :apple :search {:q "The Show" :limit 8}))
        lookup   (:url (directory/request :apple :lookup {:id "123"}))
        results  #(str "{\"resultCount\":" (count %) ",\"results\":["
                       (apply str (interpose "," (for [[id url] %]
                                                   (str "{\"kind\":\"podcast\",\"collectionId\":" id
                                                        ",\"collectionName\":\"The Show\",\"feedUrl\":\"" url "\"}"))))
                       "]}")]
    (run
     (fn []
       (async/then (discovery/find-feeds! "show.example/feed.xml" (opts {"https://show.example/feed.xml" {:body show}}))
                   (fn [{:keys [feeds error]}]
                     (testing "an address that is a feed"
                       (is (= [["https://show.example/feed.xml" :input]] (found feeds)))
                       (is (= "The Show" (:title (first feeds))))
                       (is (:podcast? (first feeds)))
                       (is (= "The Show" (:rss/title (:feed (first feeds)))) "with the parsed feed")
                       (is (nil? error))))))
     (fn []
       (async/then (discovery/find-feeds! "show.example/feed.xml" (opts {"http://show.example/feed.xml" {:body show}}))
                   (fn [{:keys [feeds]}]
                     (testing "http when https doesn't answer"
                       (is (= [["http://show.example/feed.xml" :input]] (found feeds)))
                       (is (= ["https://show.example/feed.xml" "http://show.example/feed.xml"] @log))))))
     (fn []
       (async/then (discovery/find-feeds! "show.example/feed.xml" (opts {"https://show.example/feed.xml" :late
                                                                        "http://show.example/feed.xml"  {:body show}}))
                   (fn [{:keys [error]}]
                     (testing "no other address after one that doesn't answer in time"
                       (is (= :fetch/unreachable (:id error)))
                       (is (= ["https://show.example/feed.xml"] @log))))))
     (fn []
       (async/then (discovery/find-feeds! "show.example" (opts {} :allow? #(not= "https://show.example" %)))
                   (fn [{:keys [error]}]
                     (testing "no other address after one that isn't allowed"
                       (is (= :fetch/unreachable (:id error)))
                       (is (= ["https://show.example"] @log))))))
     (fn []
       (async/then (discovery/find-feeds! "https://show.example/"
                                        (opts {"https://show.example/"         {:body (page (alternate "/feed") (alternate "/comments/feed")
                                                                                            (alternate "/feed/mp3") (alternate "/gone")
                                                                                            "<a href=/rss>RSS</a>")}
                                               "https://show.example/feed"     {:body blog}
                                               "https://show.example/comments/feed" {:body (feed-xml "Comments" {})}
                                               "https://show.example/feed/mp3" {:body show}
                                               "https://show.example/gone"     {:status 404}}))
                   (fn [{:keys [feeds report]}]
                     (testing "the link elements of a page, the podcast first"
                       (is (= [["https://show.example/feed/mp3" :link]
                               ["https://show.example/feed" :link]
                               ["https://show.example/comments/feed" :link]]
                              (found feeds)))
                       (is (= [[:fetch/status "https://show.example/gone"]] (mapv (juxt :id :url) report))
                           "a link that names no feed")
                       (is (not (some #{"https://show.example/rss"} @log)) "no more places once a podcast is found")))))
     (fn []
       (async/then (discovery/find-feeds! "https://show.example/"
                                        (opts {"https://show.example/"     {:body (page (alternate "/feed") "<a href=/podcast/rss>RSS</a>")}
                                               "https://show.example/feed" {:body blog}
                                               "https://show.example/podcast/rss" {:body show}}))
                   (fn [{:keys [feeds]}]
                     (testing "a link in the page when the link elements name no podcast"
                       (is (= [["https://show.example/podcast/rss" :anchor] ["https://show.example/feed" :link]] (found feeds)))))))
     (fn []
       (async/then (discovery/find-feeds! "https://show.example/"
                                        (opts {"https://show.example/" {:body (page)}
                                               "https://show.example/rss" {:body show}}
                                              :rules [[#"^https://show\.example/$" "https://feeds.example/show"]]))
                   (fn [{:keys [feeds report]}]
                     (testing "the rules of the caller, then the common paths"
                       (is (= [["https://show.example/rss" :guess]] (found feeds)))
                       (is (= [[:fetch/unreachable "https://feeds.example/show"]] (mapv (juxt :id :url) report))
                           "a rule claims a feed")
                       (is (= ["https://show.example/" "https://feeds.example/show"] (take 2 @log)))
                       (is (= #{"https://show.example/feed/" "https://show.example/rss"
                                "https://show.example/feed.xml" "https://show.example/index.xml"}
                              (set (drop 2 @log)))
                           "the paths at once, in no fixed order")))))
     (fn []
       (let [limits (atom {})
             send   (fn [{:keys [url timeout-seconds]}]
                      (swap! limits assoc url timeout-seconds)
                      {:status (if (= "https://show.example/" url) 200 404) :url url :headers {} :body (page)})
             ask    (fn [more]
                      (reset! limits {})
                      (async/then (discovery/find-feeds! "https://show.example/" (merge {:send send :allow? (constantly true)} more))
                                  (fn [_] (mapv @limits ["https://show.example/" "https://show.example/rss"]))))]
         (async/then (ask {})
                     (fn [default]
                       (async/then (ask {:timeout-seconds 5})
                                   (fn [lower]
                                     (testing "a guessed path has less time than the page, and never more than it"
                                       (is (= [15 8] default))
                                       (is (= [5 5] lower)))))))))
     (fn []
       (async/then (discovery/find-feeds! "show.example" (opts {"https://show.example" {:body (page)}}))
                   (fn [{:keys [feeds error]}]
                     (testing "no feed, and no directory without asking"
                       (is (empty? feeds))
                       (is (= [:discovery/not-found "https://show.example"] ((juxt :id :url) error)))
                       (is (not (some #{search} @log)))))))
     (fn []
       (async/then (discovery/find-feeds! "show.example"
                                        (opts {"https://show.example"          {:body (page)}
                                               search                          {:body (results [[1 "https://feeds.example/show"]
                                                                                                [2 "https://feeds.example/elsewhere"]])}
                                               "https://feeds.example/show"      {:body show}
                                               "https://feeds.example/elsewhere" {:body (feed-xml "The Show" {:link "https://elsewhere.example/"
                                                                                                         :episode? true})}}
                                              :search [:apple]))
                   (fn [{:keys [feeds]}]
                     (testing "a directory search, for the feeds whose website is the domain"
                       (is (= [["https://feeds.example/show" :directory]] (found feeds)))))))
     (fn []
       (async/then (discovery/find-feeds! "https://podcasts.apple.com/us/podcast/the-show/id123"
                                        (opts {lookup                       {:body (results [[123 "https://feeds.example/show"]])}
                                               "https://feeds.example/show" {:body show}}))
                   (fn [{:keys [feeds]}]
                     (testing "a link of Apple Podcasts, looked up by its id"
                       (is (= [["https://feeds.example/show" :app-link]] (found feeds)))
                       (is (= [lookup "https://feeds.example/show"] @log) "and no page of Apple")))))
     (fn []
       (async/then (discovery/find-feeds! "show.example"
                                        (opts {"https://show.example" {:body (page)}}
                                              :known {(rss/parse show) "https://feeds.example/show"}))
                   (fn [{:keys [feeds error]}]
                     (testing "a feed that the caller knows, by its website"
                       (is (= [["https://feeds.example/show" :known]] (found feeds)))
                       (is (nil? error))))))
     (fn []
       (let [guid "9b024349-ccf0-5f69-a609-6b82873eab3c"]
         (async/then (discovery/find-feeds! (str "https://show.example/#fastfollow-podcast:" guid)
                                          (opts {"https://show.example/"   {:body (page (alternate "/one") (alternate "/two"))}
                                                 "https://show.example/one" {:body show}
                                                 "https://show.example/two" {:body (feed-xml "Two" {:guid guid :episode? true})}}))
                     (fn [{:keys [feeds]}]
                       (testing "a fast-follow link keeps the feed with its guid"
                         (is (= [["https://show.example/two" :link]] (found feeds))))))))
     (fn []
       (async/then (discovery/find-feeds! "https://show.example/" (opts {"https://show.example/" {:status 404}}))
                   (fn [{:keys [feeds error]}]
                     (testing "a page that isn't there"
                       (is (empty? feeds))
                       (is (= [:fetch/status 404] ((juxt :id :status) error)))))))
     (fn []
       (async/then (discovery/find-feeds! "the daily" (opts {}))
                   (fn [{:keys [feeds error]}]
                     (testing "words, with no request"
                       (is (empty? feeds))
                       (is (= :discovery/unreadable (:id error)))
                       (is (empty? @log)))))))))
