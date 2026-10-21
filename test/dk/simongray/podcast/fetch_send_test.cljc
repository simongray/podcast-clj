(ns dk.simongray.podcast.fetch-send-test
  "The fetch! and fetch-list! of the root namespace through a :send of the
  app, on both platforms: the feed and its validators, the pages of a
  paged feed, the credentials of a private one, a list, and a send that
  fails."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(defn- page-xml
  "A page of a paged feed with the items `guids`, linking to `next`."
  [next guids]
  (str "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\"><channel><title>Paged</title>"
       (when next (str "<atom:link rel=\"next\" href=\"" next "\"/>"))
       (apply str (for [g guids] (str "<item><title>E" g "</title><guid>" g "</guid></item>")))
       "</channel></rss>"))

(def answers
  "The answers of the stand-in for a server, by URL."
  {"https://a.example/feed.xml"        {:status 200 :headers {"etag" ["\"v1\""]} :body (page-xml "?page=2" [3])}
   "https://a.example/feed.xml?page=2" {:status 200 :body (page-xml "https://b.example/page3.xml" [2])}
   "https://b.example/page3.xml"       {:status 200 :body (page-xml nil [1])}
   "https://a.example/half.xml"        {:status 200 :body (page-xml "/page.html" [4])}
   "https://a.example/page.html"       {:status 200 :body "<html><body><p>Not a feed</p></body></html>"}
   "https://a.example/broken.xml"      {:status 200 :body "<rss><channel><title>A</title><item><title>One</item></channel></rss>"}
   "https://a.example/list.opml"       {:status 200 :headers {"etag" ["\"l1\""]}
                                        :body (str "<opml version=\"2.0\"><head><title>Picks</title></head><body>"
                                                   "<outline type=\"rss\" text=\"Paged\" xmlUrl=\"https://a.example/feed.xml\"/>"
                                                   "</body></opml>")}})

(defn- answering
  "A send that answers from answers, and notes the URL and the
  Authorization of each request in the atom `sent`."
  [sent]
  (fn [{:keys [url headers]}]
    (swap! sent conj [url (some-> (get headers "Authorization") secret/reveal)])
    (assoc (get answers url {:status 404}) :url url)))

(deftest fetching-through-a-send
  (let [sent (atom [])
        send (answering sent)]
    (run #(async/then (podcast/fetch! "https://u:p@a.example/feed.xml" {:send send :max-pages 5})
                      (fn [{:keys [status etag url feed]}]
                        (is (= [200 "\"v1\"" "https://a.example/feed.xml"] [status etag url]))
                        (is (= ["E3" "E2" "E1"] (map :rss/title (:rss/items feed))) "the pages, through the same send")
                        (is (= [["https://a.example/feed.xml" "Basic dTpw"]
                                ["https://a.example/feed.xml?page=2" "Basic dTpw"]
                                ["https://b.example/page3.xml" nil]]
                               @sent)
                            "the credentials go to the pages of the same origin only")))
         #(async/then (podcast/fetch! "https://a.example/half.xml" {:send send :max-pages 5})
                      (fn [{:keys [feed report]}]
                        (is (= ["E4"] (map :rss/title (:rss/items feed))) "a later page that isn't a feed ends the pages")
                        (is (= [[:fetch/page-failed "https://a.example/page.html"]] (map (juxt :id :url) report)))
                        (is (string? (:reason (first report))) "with the reason that it didn't parse")))
         #(async/then (podcast/fetch! "https://a.example/page.html" {:send send})
                      (fn [{:keys [status error]}]
                        (is (= [200 :fetch/malformed] [status (:id error)]) "an answer that isn't a feed")))
         #(async/then (podcast/fetch! "https://a.example/feed.xml" {:send (fn [_] (async/then nil (fn [_] (throw (ex-info "offline" {})))))})
                      (fn [result]
                        (is (= [:url :error] (keys result)) "a send that fails gives the error, and throws nothing")
                        (is (= :fetch/unreachable (:id (:error result)))))))))

(deftest fetching-with-a-lenient-parser
  (let [lenient (fn [_] ["rss" {} ["channel" {} ["title" {} "Lenient"]]])]
    (run #(async/then (podcast/fetch! "https://a.example/broken.xml"
                                      {:send (answering (atom [])) :lenient-parser lenient})
                      (fn [{:keys [feed]}]
                        (is (= "Lenient" (:rss/title feed))
                            "the parser of the options reads what the repairs can't fix"))))))

(deftest fetching-a-list-through-a-send
  (let [sent (atom [])
        send (answering sent)]
    (run #(async/then (podcast/fetch-list! "https://u:p@a.example/list.opml" {:send send})
                      (fn [{:keys [status etag list]}]
                        (is (= [200 "\"l1\""] [status etag]))
                        (is (= ["https://a.example/feed.xml"] (map :xml-url (:subscriptions list))))
                        (is (= [["https://a.example/list.opml" "Basic dTpw"]] @sent)
                            "with the credentials in the URL")))
         #(async/then (podcast/fetch-list! "https://a.example/page.html" {:send send})
                      (fn [{:keys [status list error]}]
                        (is (= [200 :fetch/malformed] [status (:id error)])
                            "a page of HTML is no empty list")
                        (is (nil? list))))
         #(async/then (podcast/fetch-list! "https://a.example/gone.opml" {:send send})
                      (fn [{:keys [status error]}]
                        (is (= [404 :fetch/status] [status (:id error)]) "a status that failed has its diagnostic")))
         #(async/then (podcast/fetch-list! "https://a.example/list.opml"
                                           {:send (fn [_] (throw (ex-info "offline" {})))})
                      (fn [{:keys [error]}]
                        (is (= :fetch/unreachable (:id error))))))))

#?(:cljs
   (deftest fetching-without-a-dom-parser
     (let [parser js/DOMParser]
       (run (fn []
              (set! (.-DOMParser js/globalThis) js/undefined)
              (-> (podcast/fetch! "https://a.example/feed.xml" {:send (answering (atom []))})
                  (.then (fn [_] (is false "a feed read without a parser"))
                         (fn [e] (is (= :dk.simongray.qname-hiccup/no-parser (:type (ex-data e))) "it throws, rather than blame the server")))
                  (.finally #(set! (.-DOMParser js/globalThis) parser))))))))
