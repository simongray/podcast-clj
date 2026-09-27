(ns dk.simongray.podcast.podping
  "Podping: word that a feed has changed, written to the Hive blockchain by
  the hosts and read by anyone, so that a client fetches the feed that
  changed instead of polling every feed on a schedule.

  A consumer reads blocks from any Hive API node and picks out the
  custom_json operations the podping writers post. `request` builds the
  JSON-RPC calls as request maps for `dk.simongray.podcast.http/call!` or
  any client that sends a POST, and `pings` reads the notifications out of
  the blocks answered:

      {:block 110254833 :timestamp #inst \"2026-09-26T16:44:57Z\"
       :account \"podping.eee\" :id \"pp_podcast_update\" :version \"1.1\"
       :medium \"podcast\" :reason \"update\"
       :iris [\"https://media.rss.com/…/feed.xml\"]}

  Anyone can write a custom_json, so `writers` reads which accounts may
  write podpings: the ones the `podping` account follows on the chain,
  which is how the project publishes its writers. `pinged?` says whether
  a ping concerns a feed URL, the URLs compared as podcast:guid compares
  them. `poll!` is the poller's one step: the pings in the blocks since
  the last one read, a hundred blocks to a request, verified, with where
  to continue from; on the JVM it sends the requests itself, trying the
  public nodes in turn, and in ClojureScript `call!` sends one with fetch,
  which the nodes allow from any origin.

  A host announces a change through podping.cloud; `notify-request` builds
  that call with the token the host was given."
  (:require [clojure.string :as str]
            #?(:clj [dk.simongray.podcast.async :as async])
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.time :as time]))

(def nodes
  "Public Hive API nodes, tried in this order until one answers."
  ["https://api.hive.blog" "https://api.deathwing.me" "https://api.openhive.network" "https://hive-api.arcange.eu"])

(def calls
  "What a consumer asks a node: `:head-block` for the newest block and the
  last irreversible one; `:blocks` with `:from` and `:count` for a run of
  blocks with their operations, a hundred answering in about a second;
  `:operations` with `:block` for one block's operations; `:following` for
  the accounts podping follows, its writers."
  #{:head-block :blocks :operations :following})

(defn request
  "The JSON-RPC request map for `call`, one of `calls`, with `params`, sent
  to the `:node` in `opts` (default the first of `nodes`)."
  ([call params]
   (request call params {}))
  ([call params {:keys [node] :or {node (first nodes)} :as opts}]
   {:pre [(map? opts)]}
   (let [[method rpc-params] (case call
                               :head-block ["condenser_api.get_dynamic_global_properties" []]
                               :blocks     ["block_api.get_block_range" {"starting_block_num" (:from params)
                                                                         "count"              (:count params)}]
                               :operations ["condenser_api.get_ops_in_block" [(:block params) false]]
                               :following  ["condenser_api.get_following" ["podping" "" "blog" 1000]]
                               (throw (ex-info (str "A call the consumer does not make: " call) {:call call})))]
     {:method  :post
      :url     node
      :headers {"Content-Type" "application/json" "User-Agent" http/user-agent}
      :body    (json/write-str {"jsonrpc" "2.0" "method" method "params" rpc-params "id" 1})})))

(defn- result
  "The result in the JSON-RPC `body`: a body that is not JSON, or a node
  that answers with an error, throws."
  [body]
  (let [raw (try (json/read-str body)
                 (catch #?(:clj Exception :cljs :default) e
                   (throw (ex-info "The node did not answer with JSON"
                                   {:type ::malformed :body (subs (str body) 0 (min 200 (count (str body))))}
                                   e))))]
    (if-let [error (get raw "error")]
      (throw (ex-info (str "The node answered with an error: " (get error "message"))
                      {:type ::error :error error}))
      (get raw "result"))))

(defn head-block
  "The chain's position in the body of a `:head-block` answer: `{:block n
  :irreversible n :time inst}`, the block a fork can no longer take back
  being some seconds behind the head."
  [body]
  (let [r (result body)]
    {:block        (get r "head_block_number")
     :irreversible (get r "last_irreversible_block_num")
     :time         (some-> (get r "time") time/parse-iso8601)}))

(defn- podping-id?
  [id]
  (and (string? id) (or (= "podping" id) (str/starts-with? id "pp_"))))

(defn- reason-of
  "The reason a ping gives, in today's words: the first versions said
  feed_update for update."
  [reason]
  (case reason "feed_update" "update" reason))

(defn- operation
  "The kind and value of the operation `op`, as condenser_api writes it,
  `[kind value]`, or as block_api does, `{\"type\" \"…_operation\" \"value\" …}`."
  [op]
  (if (map? op)
    [(str/replace (str (get op "type")) #"_operation$" "") (get op "value")]
    op))

(defn- payload
  "The JSON a custom_json carries, nil when it is not JSON."
  [s]
  (try (json/read-str s) (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- ping
  "The podping in the operation `op` of `block` at `timestamp`, nil when it
  holds none: a custom_json with a podping id and iris (urls in the first
  versions), its medium and reason from the payload or else from the id."
  [block timestamp op]
  (let [[kind {:strs [id json required_posting_auths]}] (operation op)]
    (when (and (= "custom_json" kind) (podping-id? id))
      (let [body (payload json)
            [_ medium reason] (re-matches #"pp_([A-Za-z]+)_([A-Za-z]+)" id)
            iris (->> (or (get body "iris") (get body "urls") (some-> (get body "url") vector))
                      (filter #(and (string? %) (not (str/blank? %)))))]
        (when (and (map? body) (seq iris))
          {:block     block
           :timestamp (some-> timestamp time/parse-iso8601)
           :account   (first required_posting_auths)
           :id        id
           :version   (get body "version")
           :medium    (or (get body "medium") medium "podcast")
           :reason    (reason-of (or (get body "reason") reason "update"))
           :iris      (vec iris)})))))

(defn- block-number
  "The number of a block, the first eight hex digits of its id."
  [block-id]
  #?(:clj  (Long/parseLong (subs block-id 0 8) 16)
     :cljs (js/parseInt (subs block-id 0 8) 16)))

(defn pings
  "The podpings in the body of a `:blocks` or an `:operations` answer, in
  the order of the chain."
  [body]
  (let [r (result body)]
    (vec (if (map? r)
           (for [{:strs [block_id timestamp transactions]} (get r "blocks")
                 :let [block (block-number block_id)]
                 {:strs [operations]} transactions
                 op operations
                 :let [p (ping block timestamp op)]
                 :when p]
             p)
           (keep (fn [{:strs [block timestamp op]}] (ping block timestamp op)) r)))))

(defn writers
  "The accounts that may write podpings, from the body of a `:following`
  answer: the ones the `podping` account follows, which is how the project
  publishes its writers, and `podping` itself."
  [body]
  (into #{"podping"} (map #(get % "following")) (result body)))

(defn pinged?
  "Whether `ping` concerns the feed at `url`, the URLs compared as
  podcast:guid compares them, so that the scheme and a trailing slash do
  not tell them apart."
  [ping url]
  (let [g (guid/from-url url)]
    (boolean (some #(= g (guid/from-url %)) (:iris ping)))))

(defn affected
  "The URLs among `urls` that any of `pings` concerns, in the order given."
  [pings urls]
  (filterv (fn [url] (some #(pinged? % url) pings)) urls))

(defn notify-request
  "The call a host makes to podping.cloud to announce that the feed at
  `url` changed, with the `token` podping.cloud issued it; `opts` may give
  the `:medium` (default \"podcast\") and the `:reason`, \"update\", \"live\"
  or \"liveEnd\" (default \"update\")."
  ([token url]
   (notify-request token url {}))
  ([token url {:keys [medium reason] :or {medium "podcast" reason "update"} :as opts}]
   {:pre [(map? opts)]}
   {:method  :get
    :url     (str "https://podping.cloud/?" (http/query-string {:url url :reason reason :medium medium}))
    :headers {"Authorization" token "User-Agent" http/user-agent}}))

#?(:clj
   (defn- ask!
     "The body `node` answers `call` with, or the failure as `{:failure …}`:
     an exception, or a status that is not 200."
     [node call params timeout]
     (try
       (let [{:keys [status body]} (http/call! (assoc (request call params {:node node}) :timeout-seconds timeout))]
         (if (= 200 status)
           {:body body}
           {:failure (str "answered " status)}))
       (catch Exception e
         {:failure (async/explain e)}))))

#?(:clj
   (defn call!
     "Send `call` with `params` with the library's HTTP client and return the
     body, for `head-block`, `pings` or `writers` to read. The `:nodes` in
     `opts` (default `nodes`), or the one `:node`, are tried in turn until
     one answers, each given `:timeout-seconds` (default 10); when none
     does, the exception names them all."
     [call params opts]
     {:pre [(map? opts)]}
     (let [candidates (if-let [node (:node opts)] [node] (or (:nodes opts) nodes))
           timeout    (:timeout-seconds opts 10)]
       (loop [[node & more] candidates
              failures      []]
         (let [{:keys [body failure]} (ask! node call params timeout)]
           (cond
             body       body
             (seq more) (recur more (conj failures [node failure]))
             :else      (throw (ex-info (str "No node answered " (name call))
                                        {:type ::unavailable :failures (conj failures [node failure])}))))))))

#?(:cljs
   (defn call!
     "Send `call` with `params` to the node in `opts` with the platform's
     `fetch`, which the public nodes allow from any origin, and return a
     js/Promise of the body, for `head-block`, `pings` or `writers` to read."
     [call params opts]
     {:pre [(map? opts)]}
     (let [{:keys [url headers body]} (request call params opts)]
       (-> (js/fetch url (clj->js {:method "POST" :headers headers :body body}))
           (.then (fn [res] (.text res)))))))

#?(:clj
   (defn poll!
     "The poller's one step: the podpings written after the block `from`, up
     to the head of the chain, as

         {:pings   [...]                 ; from the writers podping follows, in chain order
          :refused [...]                 ; from anyone else
          :block   110254840             ; the last block read, to pass as `from` next time
          :writers #{\"podping.eee\" …}}  ; the writers, read once, to pass back

     `from` nil starts at the head. `opts`: `:max-blocks` read in one step
     (default 100, five minutes of blocks in one request; the caller
     continues from `:block` when behind, or asks for more, a hundred per
     request), `:up-to` `:irreversible` to stop at the block a fork can no
     longer take back rather than at the head, `:writers` from the last
     step so that the followers are read once, `:verify?` false to keep
     every ping whoever wrote it, and `:nodes`, `:node` and
     `:timeout-seconds` as for `call!`."
     ([from]
      (poll! from {}))
     ([from {:keys [max-blocks verify? up-to] :or {max-blocks 100 verify? true up-to :head} :as opts}]
      {:pre [(map? opts)]}
      (let [position (head-block (call! :head-block {} opts))
            head     (if (= :irreversible up-to) (:irreversible position) (:block position))
            start    (if from (inc from) head)
            end      (min head (+ start (dec max-blocks)))
            ;; a hundred blocks answer in a second, a thousand in forty
            pings    (into [] (mapcat (fn [chunk]
                                        (pings (call! :blocks {:from (first chunk) :count (count chunk)} opts))))
                           (partition-all 100 (range start (inc end))))
            known    (or (:writers opts)
                         (when verify? (writers (call! :following {} opts))))
            {ok true refused false} (if verify?
                                      (group-by #(contains? known (:account %)) pings)
                                      {true pings})]
        {:pings   (vec ok)
         :refused (vec refused)
         :block   (if (<= start end) end (or from head))
         :writers known}))))

#?(:clj
   (comment
     (def step (poll! nil))
     (def step (poll! (:block step) {:writers (:writers step)}))
     (map (juxt :account :medium :reason :iris) (:pings step))
     (affected (:pings step) ["https://feeds.podcastindex.org/pc20.xml"])
     #_.))
