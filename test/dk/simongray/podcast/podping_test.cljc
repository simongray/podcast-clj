(ns dk.simongray.podcast.podping-test
  "The podping consumer: the calls to a Hive node, the pings read out of
  blocks, the writers, the URLs a ping concerns, and on the JVM the poll
  with its fallback across nodes."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [dk.simongray.podcast.http :as http])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.podping :as podping]
            [dk.simongray.podcast.test-support :refer [fixture]]))

(def head-block
  (fixture "podping/head-block.json"))

(def blocks
  (fixture "podping/blocks.json"))

(def operations
  (fixture "podping/operations.json"))

(def following
  (fixture "podping/following.json"))

(defn- rpc
  "The JSON-RPC body of a request, as data."
  [request]
  (json/read-str (:body request)))

(defn- method-and-params
  [request]
  ((juxt #(get % "method") #(get % "params")) (rpc request)))

(deftest requests
  (let [r (podping/request :head-block {})]
    (is (= :post (:method r)))
    (is (= "https://api.hive.blog" (:url r)))
    (is (= "application/json" (get-in r [:headers "Content-Type"])))
    (is (= {"jsonrpc" "2.0" "method" "condenser_api.get_dynamic_global_properties" "params" [] "id" 1} (rpc r))))
  (is (= ["block_api.get_block_range" {"starting_block_num" 110255079 "count" 2}]
         (method-and-params (podping/request :blocks {:from 110255079 :count 2}))))
  (is (= ["condenser_api.get_ops_in_block" [110254833 false]]
         (method-and-params (podping/request :operations {:block 110254833}))))
  (is (= ["condenser_api.get_following" ["podping" "" "blog" 1000]]
         (method-and-params (podping/request :following {}))))
  (is (= "https://api.deathwing.me" (:url (podping/request :head-block {} {:node "https://api.deathwing.me"}))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (podping/request :accounts {}))))

(deftest reading-the-answers
  (testing "the chain's position"
    (is (= {:block 110254836 :irreversible 110254836 :time #inst "2026-09-26T16:45:06Z"}
           (podping/head-block head-block))))
  (testing "the pings in a run of blocks, numbered from the block ids"
    (let [pings (podping/pings blocks)]
      (is (= 4 (count pings)))
      (is (= [110255079 110255079 110255079 110255080] (map :block pings)))
      (is (= #inst "2026-09-26T16:57:21Z" (:timestamp (first pings))))
      (is (= "podping.eee" (:account (first pings))))
      (is (every? #(= ["podcast" "update" "1.1"] ((juxt :medium :reason :version) %)) pings))
      (is (every? #(str/starts-with? (first (:iris %)) "https://") pings))))
  (testing "the pings in one block's operations, the other operations passed over"
    (let [pings (podping/pings operations)]
      (is (= 2 (count pings)))
      (is (= {:block     110254833
              :timestamp #inst "2026-09-26T16:44:57Z"
              :account   "podping.eee"
              :id        "pp_podcast_update"
              :version   "1.1"
              :medium    "podcast"
              :reason    "update"
              :iris      ["https://media.rss.com/landsraad.guild.navigator.spice.harvest.project.5415198.eb5da4/feed.xml"]}
             (first pings)))))
  (testing "the first versions, block_api's shape, and what is not a ping"
    (let [legacy {"block" 1 "timestamp" "2021-06-01T12:00:00"
                  "op" ["custom_json" {"id" "podping" "required_posting_auths" ["podping.aaa"]
                                       "json" "{\"version\":\"0.3\",\"num_urls\":1,\"reason\":\"feed_update\",\"urls\":[\"https://a/feed\"]}"}]}
          typed  {"block" 2 "timestamp" "2026-01-01T00:00:00"
                  "op" {"type" "custom_json_operation"
                        "value" {"id" "pp_music_live" "required_posting_auths" ["podping.bbb"]
                                 "json" "{\"version\":\"1.0\",\"iris\":[\"https://b/feed\", 7, \"\"]}"}}}
          other  {"block" 3 "timestamp" "2026-01-01T00:00:00"
                  "op" ["custom_json" {"id" "notify" "required_posting_auths" ["someone"] "json" "{}"}]}
          broken {"block" 4 "timestamp" "2026-01-01T00:00:00"
                  "op" ["custom_json" {"id" "pp_podcast_update" "required_posting_auths" ["podping.ccc"] "json" "not json"}]}
          empty  {"block" 5 "timestamp" "2026-01-01T00:00:00"
                  "op" ["custom_json" {"id" "pp_podcast_update" "required_posting_auths" ["podping.ccc"] "json" "{\"iris\":[]}"}]}
          body   (json/write-str {"jsonrpc" "2.0" "id" 1 "result" [legacy typed other broken empty]})
          [a b :as pings] (podping/pings body)]
      (is (= 2 (count pings)))
      (is (= {:account "podping.aaa" :id "podping" :version "0.3" :medium "podcast" :reason "update" :iris ["https://a/feed"]}
             (dissoc a :block :timestamp))
          "urls and feed_update read as iris and update")
      (is (= {:account "podping.bbb" :id "pp_music_live" :version "1.0" :medium "music" :reason "live" :iris ["https://b/feed"]}
             (dissoc b :block :timestamp))
          "the medium and reason from the id when the payload names none; only strings are iris")))
  (testing "what a node should not answer with"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"error"
                          (podping/pings "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"no such block\"}}")))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"JSON"
                          (podping/pings "<html>502 Bad Gateway</html>")))))

(deftest the-writers
  (let [ok (podping/writers following)]
    (is (= 25 (count ok)) "the accounts podping follows, and podping itself")
    (is (contains? ok "podping.eee"))
    (is (contains? ok "podnews") "hosts that write for themselves are followed too")
    (is (contains? ok "podping"))
    (is (not (contains? ok "alecksgates")) "an account podping authorizes to post as it is not thereby a writer")))

(deftest what-a-ping-concerns
  (let [ping {:iris ["https://feeds.podcastindex.org/pc20.xml" "https://example.com/feed/"]}]
    (is (podping/pinged? ping "https://feeds.podcastindex.org/pc20.xml"))
    (is (podping/pinged? ping "http://feeds.podcastindex.org/pc20.xml") "the scheme does not tell URLs apart")
    (is (podping/pinged? ping "https://example.com/feed") "nor a trailing slash")
    (is (not (podping/pinged? ping "https://example.com/other")))
    (is (= ["https://example.com/feed"]
           (podping/affected [ping] ["https://example.com/feed" "https://example.com/other"])))))

(deftest announcing-a-change
  (let [r (podping/notify-request "secret-token" "https://example.com/feed.xml" {:reason "live"})]
    (is (= :get (:method r)))
    (is (= "https://podping.cloud/?url=https%3A%2F%2Fexample.com%2Ffeed.xml&reason=live&medium=podcast" (:url r)))
    (is (= "secret-token" (get-in r [:headers "Authorization"])))
    (is (str/includes? (:url (podping/notify-request "t" "https://x/f")) "reason=update&medium=podcast"))))

#?(:clj
   (defn- node-answering
     "A stand-in for the HTTP client: `answers` by node URL, a function of
     the request body returning a body, or a status to fail with."
     [answers]
     (fn [{:keys [url body]}]
       (let [answer (get answers url)]
         (cond
           (fn? answer)     {:status 200 :url url :body (answer body)}
           (number? answer) {:status answer :url url :body "<html>down</html>"}
           :else            (throw (ex-info "connection refused" {:url url})))))))

#?(:clj
   (defn- chain
     "A node that answers as the fixtures do, with the head at `head`."
     [head]
     (fn [body]
       (cond
         (str/includes? body "get_dynamic_global_properties")
         (json/write-str {"jsonrpc" "2.0" "id" 1 "result" {"head_block_number" head "last_irreversible_block_num" (- head 15)
                                                           "time" "2026-09-26T16:57:24"}})
         (str/includes? body "get_block_range") blocks
         (str/includes? body "get_following")   following))))

#?(:clj
   (deftest polling
     (with-redefs [http/call! (node-answering {"https://api.hive.blog" (chain 110255080)})]
       (testing "the pings since a block, verified, and where to continue"
         (let [step (podping/poll! 110255078 {:node "https://api.hive.blog"})]
           (is (= 4 (count (:pings step))))
           (is (= [] (:refused step)))
           (is (= 110255080 (:block step)))
           (is (= 25 (count (:writers step))))
           (is (= "podping.eee" (:account (first (:pings step)))))))
       (testing "the writers handed back are used as they are"
         (let [step (podping/poll! 110255078 {:node "https://api.hive.blog" :writers #{"podping"}})]
           (is (= [] (:pings step)))
           (is (= 4 (count (:refused step))) "pings from writers not in the set are refused, not lost")))
       (testing "without verification every ping counts"
         (is (= 4 (count (:pings (podping/poll! 110255078 {:node "https://api.hive.blog" :verify? false :writers #{}}))))))
       (testing "nothing new, and starting at the head"
         (is (= {:pings [] :refused [] :block 110255080} (dissoc (podping/poll! 110255080 {:node "https://api.hive.blog"}) :writers)))
         (is (= 110255080 (:block (podping/poll! nil {:node "https://api.hive.blog"})))))
       (testing "a block the node leaves out is asked for again, and no ping comes twice"
         (is (= {:pings [] :refused [] :block 110255077}
                (dissoc (podping/poll! 110255077 {:node "https://api.hive.blog" :verify? false}) :writers)))))
     (with-redefs [http/call! (node-answering {"https://api.hive.blog" (chain 110255095)})]
       (testing "stopping at the last irreversible block"
         (is (= 110255080 (:block (podping/poll! nil {:node "https://api.hive.blog" :up-to :irreversible}))))))))

#?(:clj
   (deftest falling-back-across-nodes
     (testing "a node that fails is passed over"
       (with-redefs [http/call! (node-answering {"https://api.hive.blog"         502
                                                 "https://api.deathwing.me"      nil
                                                 "https://api.openhive.network"  (chain 110255080)})]
         (is (= 110255080 (:block (podping/head-block (podping/call! :head-block {} {})))))))
     (testing "and so is one that answers 200 with an error, as a proxy does"
       (with-redefs [http/call! (node-answering {"https://api.hive.blog"         (constantly "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32003,\"message\":\"Unable to acquire database lock\"}}")
                                                 "https://api.deathwing.me"      (constantly "<html>maintenance</html>")
                                                 "https://api.openhive.network"  (chain 110255080)})]
         (is (= 110255080 (:block (podping/head-block (podping/call! :head-block {} {})))))))
     (testing "when none answers, the failures are named"
       (with-redefs [http/call! (node-answering {})]
         (let [e (try (podping/call! :head-block {} {:nodes ["https://a" "https://b"]}) (catch Exception e e))]
           (is (= :dk.simongray.podcast.podping/unavailable (:type (ex-data e))))
           (is (= ["https://a" "https://b"] (map first (:failures (ex-data e))))))))))
