(ns dk.simongray.podcast.fetch-send-test
  "The fetch! of the root namespace through a :send of the app, on both
  platforms: the feed and its validators, the pages of a paged feed, the
  credentials of a private one, and a send that fails."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.secret :as secret]))

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
   "https://a.example/page.html"       {:status 200 :body "<html><body><p>Not a feed</p></body></html>"}})

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
         #(async/then (podcast/fetch! "https://a.example/page.html" {:send send})
                      (fn [{:keys [status error]}]
                        (is (= [200 :fetch/malformed] [status (:id error)]) "an answer that isn't a feed")))
         #(async/then (podcast/fetch! "https://a.example/feed.xml" {:send (fn [_] (async/then nil (fn [_] (throw (ex-info "offline" {})))))})
                      (fn [result]
                        (is (= [:url :error] (keys result)) "a send that fails gives the error, and throws nothing")
                        (is (= :fetch/unreachable (:id (:error result)))))))))
