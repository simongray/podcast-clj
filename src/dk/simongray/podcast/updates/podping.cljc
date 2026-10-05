(ns dk.simongray.podcast.updates.podping
  "Podping: word that a feed has changed, which hosts write to the Hive
  blockchain and anyone can read. A client then fetches the feed that
  changed instead of polling every feed on a schedule.

  A consumer reads blocks from any Hive API node and picks out the
  custom_json operations that the podping writers post. The request
  function builds the JSON-RPC calls as request maps that
  dk.simongray.podcast.util.http/send! or any client can send as a POST.
  The pings function reads the notifications out of the blocks in an
  answer:

      {:block 110254833 :timestamp #inst \"2026-09-26T16:44:57Z\"
       :account \"podping.eee\" :id \"pp_podcast_update\" :version \"1.1\"
       :medium \"podcast\" :reason \"update\"
       :iris [\"https://media.rss.com/…/feed.xml\"]}

  Anyone can write a custom_json, so writers reads which accounts may
  write podpings. They're the ones that the podping account follows on
  the chain, which is how the project publishes its writers. The pinged?
  function says whether a ping concerns the URL of a feed, with the URLs
  compared as podcast:guid compares them.

  The poll! function is the one step of a poller. It reads the pings in
  the blocks since the last one read, a hundred blocks to a request,
  verifies them, and says where to continue from. It's on the JVM only. In
  ClojureScript, send each request with call!, since the nodes answer a
  page from any origin. On both platforms, call! tries the public nodes in
  turn.

  A host announces a change through podping.cloud, and notify-request
  builds that call with the token that the host was given."
  (:require [clojure.string :as str]
            #?(:clj [dk.simongray.podcast.diagnostics :as diagnostics])
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.secret :as secret]
            [dk.simongray.podcast.util.time :as time]))

(def nodes
  "Public Hive API nodes, tried in this order until one answers."
  ["https://api.hive.blog"
   "https://api.deathwing.me"
   "https://api.openhive.network"
   "https://hive-api.arcange.eu"])

(def gateway
  "The URL of podping.cloud, the service that writes a podping for a host.
  The notify-request function sends to it, unless its options give
  another service as :gateway."
  "https://podping.cloud/")

(def calls
  "The calls that a consumer makes to a node:

  - :head-block, for the newest block and the last irreversible one
  - :blocks, with :from and :count, for a run of blocks with their
    operations, of which a hundred answer in about a second
  - :operations, with :block, for the operations of one block
  - :following, for the accounts that podping follows, its writers"
  #{:head-block :blocks :operations :following})

(defn- rpc-call
  "The JSON-RPC method and params of `call` with its `params`, as a pair."
  [call params]
  (case call
    :head-block ["condenser_api.get_dynamic_global_properties" []]
    :blocks     ["block_api.get_block_range"
                 {"starting_block_num" (:from params)
                  "count"              (:count params)}]
    :operations ["condenser_api.get_ops_in_block" [(:block params) false]]
    :following  ["condenser_api.get_following" ["podping" "" "blog" 1000]]
    (throw (ex-info (str "A call the consumer does not make: " call)
                    {:call call}))))

(defn request
  "The JSON-RPC request map for `call`, one of calls, with its `params`
  when it takes any, sent to the :node of `opts`, or else the first of
  nodes."
  ([call]
   (request call {} {}))
  ([call params]
   (request call params {}))
  ([call params {:keys [node] :or {node (first nodes)} :as opts}]
   {:pre [(map? opts)]}
   (let [[method rpc-params] (rpc-call call params)
         body                {"jsonrpc" "2.0"
                              "method"  method
                              "params"  rpc-params
                              "id"      1}]
     {:method  :post
      :url     node
      :headers {"Content-Type" "application/json"
                "User-Agent"   http/user-agent}
      :body    (json/write-str body)})))

(defn ^:no-doc result
  "The result in the JSON-RPC `body`. A body that isn't JSON, or a node
  that answers with an error, throws."
  [body]
  (let [raw (json/object body)]
    (cond
      (nil? raw)
      (let [text (str body)]
        (throw (ex-info "The node did not answer with JSON"
                        {:type ::malformed
                         :body (subs text 0 (min 200 (count text)))})))

      (get raw "error")
      (throw (ex-info (str "The node answered with an error: "
                           (get-in raw ["error" "message"]))
                      {:type ::error :error (get raw "error")}))

      :else
      (get raw "result"))))

(defn head-block
  "The position of the chain in `body`, the answer to a :head-block call,
  as a map of :block, :irreversible and :time. The irreversible block,
  which a fork can no longer take back, is some seconds behind the head."
  [body]
  (let [r (result body)]
    {:block        (get r "head_block_number")
     :irreversible (get r "last_irreversible_block_num")
     :time         (some-> (get r "time") time/parse-iso8601)}))

(defn- podping-id?
  [id]
  (and (string? id)
       (or (= "podping" id) (str/starts-with? id "pp_"))))

(defn- reason-of
  "The `reason` of a ping in today's words: the first versions said
  feed_update for update."
  [reason]
  (case reason "feed_update" "update" reason))

(defn- operation
  "The kind and the value of the operation `op`, as a pair. The `op` is a
  pair already as condenser_api writes it, or a map of a type and a value
  as block_api does."
  [op]
  (if (map? op)
    [(str/replace (str (get op "type")) #"_operation$" "") (get op "value")]
    op))

(defn ^:no-doc ping
  "The podping in the operation `op` of `block` at `timestamp`, or nil
  when it holds none.

  A podping is a custom_json with a podping id and iris, which were urls
  in the first versions. Its medium and reason come from the payload, or
  else from the id."
  [block timestamp op]
  (let [[kind {:strs [id json required_posting_auths]}] (operation op)]
    (when (and (= "custom_json" kind) (podping-id? id))
      (let [body              (json/parse json)
            [_ medium reason] (re-matches #"pp_([A-Za-z]+)_([A-Za-z]+)" id)
            listed            (or (get body "iris")
                                  (get body "urls")
                                  (some-> (get body "url") vector))
            iris              (filter #(and (string? %) (not (str/blank? %)))
                                      listed)]
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
  "The number of the block whose id is `block-id`: the first eight hex
  digits of the id."
  [block-id]
  #?(:clj  (Long/parseLong (subs block-id 0 8) 16)
     :cljs (js/parseInt (subs block-id 0 8) 16)))

(defn ^:no-doc block-pings
  "The podpings in `blocks`, as a block_api answer lists them, in order."
  [blocks]
  (for [{:strs [block_id timestamp transactions]} blocks
        :let [block (block-number block_id)]
        {:strs [operations]} transactions
        op operations
        :let [p (ping block timestamp op)]
        :when p]
    p))

(defn pings
  "The podpings in `body`, the answer to a :blocks or an :operations call,
  in the order of the chain."
  [body]
  (let [r (result body)]
    (vec (if (map? r)
           (block-pings (get r "blocks"))
           (keep (fn [{:strs [block timestamp op]}]
                   (ping block timestamp op))
                 r)))))

(defn writers
  "The accounts that may write podpings, from `body`, the answer to a
  :following call. They're podping itself and the accounts it follows,
  which is how the project publishes its writers."
  [body]
  (into #{"podping"} (map #(get % "following")) (result body)))

(defn pinged?
  "Whether `ping` concerns the feed at `url`, with the URLs compared as
  podcast:guid compares them, so the scheme and a trailing slash don't
  tell them apart."
  [ping url]
  (let [g (guid/from-url url)]
    (boolean (some #(= g (guid/from-url %)) (:iris ping)))))

(defn affected
  "The URLs among `urls` that any of `pings` concerns, in the order given.
  The podcast:guid of each URL is computed once, not again for every ping."
  [pings urls]
  (let [pinged (into #{} (comp (mapcat :iris) (map guid/from-url)) pings)]
    (filterv #(contains? pinged (guid/from-url %)) urls)))

(defn notify-request
  "The call that a host makes with `opts` to announce that the feed at
  `url` changed, with the `token` that the service gave the host.

  The token can be a hidden text of dk.simongray.podcast.util.secret, and
  the request has it in a header as a hidden text. The `opts` are these:

  - :medium, \"podcast\" by default
  - :reason, which is \"update\", \"live\" or \"liveEnd\", \"update\" by
    default
  - :gateway, the URL of another service that takes the same call, in
    place of gateway"
  ([token url]
   (notify-request token url {}))
  ([token url {:keys [medium reason]
               :or   {medium "podcast" reason "update"}
               :as   opts}]
   {:pre [(map? opts)]}
   (let [service (:gateway opts gateway)
         query   (http/query-string {:url url :reason reason :medium medium})]
     {:method  :get
      :url     (str service (if (str/includes? service "?") "&" "?") query)
      :headers {"Authorization" (secret/hide token)
                "User-Agent"    http/user-agent}})))

(defn- call-node!
  "Send `call` with `params` to `node` as `opts` say, and give the body of
  the answer, in ClojureScript in a promise. It throws for each failure
  that call! lists."
  [call params node {:keys [send timeout-seconds]
                     :or   {send http/send! timeout-seconds 10}}]
  (-> (request call params {:node node})
      (assoc :timeout-seconds timeout-seconds)
      send
      (async/then (fn [{:keys [status body]}]
                    (when-not (= 200 status)
                      (throw (ex-info (str "answered " status)
                                      {:status status})))
                    (result body)
                    body))))

(defn call!
  "Send `call` with its `params`, when it takes any, as `opts` say, and
  give the body, for head-block, pings or writers to read.

  Each node is tried in turn until one answers. The `opts` are these:

  - :nodes, the nodes to try, nodes by default
  - :node, the one node to try
  - :timeout-seconds, the time that each node gets, 10 by default
  - :send, the function that sends each request, http/send! by default

  A node fails on an exception, a status other than 200, or an answer
  that isn't JSON or holds a JSON-RPC error, which proxies send with a
  200. When every node fails, the exception names them all.

  In ClojureScript, the body comes in a promise, which is rejected with
  that exception. The public nodes answer a page from any origin."
  ([call]
   (call! call {} {}))
  ([call params]
   (call! call params {}))
  ([call params opts]
   {:pre [(map? opts)]}
   (letfn [(call-in-turn! [[node & more :as candidates] failures]
             (if (empty? candidates)
               (throw (ex-info (str "No node answered " (name call))
                               {:type ::unavailable :failures failures}))
               (async/then
                (async/attempt #(call-node! call params node opts))
                (fn [{:keys [value error]}]
                  (or value
                      (let [failure [node (async/explain error)]]
                        (call-in-turn! more (conj failures failure))))))))]
     (let [candidates (if-let [node (:node opts)]
                        [node]
                        (or (:nodes opts) nodes))]
       (call-in-turn! candidates [])))))

#?(:clj
   (defn ^:no-doc attempted
     "What `f` gives when called, a reading of the answer to a call. When
     it throws, the result has a :podping/unavailable diagnostic under
     :error."
     [f]
     (try
       (f)
       (catch Exception e
         {:error (diagnostics/describe
                  (into {:id :podping/unavailable :reason (async/explain e)}
                        (select-keys (ex-data e) [:failures])))}))))

#?(:clj
   (defn- read-chunk!
     "Read the podpings in the blocks `chunk`, numbered in a row, with one
     request by `opts`. It gives the :pings and :through, the last block
     before the first one that the answer left out."
     [chunk opts]
     (let [params  {:from (first chunk) :count (count chunk)}
           blocks  (get (result (call! :blocks params opts)) "blocks")
           numbers (set (map #(block-number (get % "block_id")) blocks))
           through (last (take-while numbers chunk))
           read?   #(and through (<= (:block %) through))]
       {:through through
        :pings   (filterv read? (block-pings blocks))})))

#?(:clj
   (defn- read-blocks!
     "Read the podpings in the blocks `start` to `end` with `opts`. It
     gives a map of the :pings and the last :block read.

     A request gets 100 blocks, since 100 blocks answer in a second and
     1000 blocks in 40 seconds. A node that leaves blocks out of its answer
     ends the reading before the first block that it left out. So the next
     step asks for them again, and no ping comes twice. A call that fails
     ends the reading too, and the result has its :error."
     [start end opts]
     (loop [chunks (partition-all 100 (range start (inc end)))
            found  []
            read   (dec start)]
       (let [chunk  (first chunks)
             answer (when chunk (attempted #(read-chunk! chunk opts)))
             {:keys [pings through error]} answer
             found  (into found pings)]
         (cond
           (nil? chunk)             {:pings found :block read}
           error                    {:pings found :block read :error error}
           (= through (last chunk)) (recur (rest chunks) found through)
           :else                    {:pings found :block (or through read)})))))

#?(:clj
   (defn poll!
     "Poll the chain for the podpings after the block `from`, up to its
     head, with `opts`. This is the one step that a poller repeats, and it
     gives a map of these:

     - :pings, from the writers that podping follows, in chain order
     - :refused, the pings of each other account
     - :block, the last block read, which is `from` for the next step
     - :writers, to give back in `opts`
     - :error, when no node answered a call

     Without `from`, or with nil, the step starts at the head. The `opts`
     are these:

     - :max-blocks, the most blocks that one step reads, 100 by default,
       which is five minutes of the chain. A poller that's behind reads on
       from :block.
     - :up-to, which is :head by default, or :irreversible, the last block
       that a fork can't take back
     - :writers from the last step, so the step doesn't read them again
     - :verify? false, to keep each ping, whatever account wrote it
     - :nodes, :node, :timeout-seconds and :send, as call! takes them

     The :block is the last block that a node answered with, so the next
     step reads a block that an answer left out.

     Nothing that a node or the network does throws. When no node answers
     a call, the :error is a :podping/unavailable diagnostic. The result
     has the pings of the blocks that the step read before that call, and
     :block names the last of those blocks."
     ([]
      (poll! nil {}))
     ([from]
      (poll! from {}))
     ([from {:keys [max-blocks verify? up-to]
             :or   {max-blocks 100 verify? true up-to :head}
             :as   opts}]
      {:pre [(map? opts)]}
      (let [known    (or (:writers opts)
                         (when verify?
                           (attempted #(writers (call! :following {} opts)))))
            position (if (:error known)
                       known
                       (attempted #(head-block (call! :head-block {} opts))))]
        (if-let [error (:error position)]
          {:pings   []
           :refused []
           :block   from
           :writers (:writers opts)
           :error   error}
          (let [head    (if (= :irreversible up-to)
                          (:irreversible position)
                          (:block position))
                start   (if from (inc from) head)
                end     (min head (+ start (dec max-blocks)))
                writer? #(contains? known (:account %))
                {:keys [pings block error]} (read-blocks! start end opts)
                {ok true refused false}     (if verify?
                                              (group-by writer? pings)
                                              {true pings})]
            (cond-> {:pings   (vec ok)
                     :refused (vec refused)
                     :block   (if (<= start end) block (or from head))
                     :writers known}
              error (assoc :error error))))))))

#?(:clj
   (comment
     (def step (poll!))
     (def step (poll! (:block step) {:writers (:writers step)}))
     (map (juxt :account :medium :reason :iris) (:pings step))
     (affected (:pings step) ["https://feeds.podcastindex.org/pc20.xml"])
     #_.))
