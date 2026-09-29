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
            #?@(:clj [[dk.simongray.podcast.async :as async]
                      [dk.simongray.podcast.diagnostics :as diagnostics]])
            [dk.simongray.podcast.guid :as guid]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.secret :as secret]
            [dk.simongray.podcast.time :as time]))

(def nodes
  "Public Hive API nodes, tried in this order until one answers."
  ["https://api.hive.blog" "https://api.deathwing.me" "https://api.openhive.network" "https://hive-api.arcange.eu"])

(def gateway
  "The URL of podping.cloud, the service that writes a podping for a host.
  `notify-request` sends to it, unless its options give another service as
  `:gateway`."
  "https://podping.cloud/")

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

(defn ^:no-doc result
  "The result in the JSON-RPC `body`: a body that is not JSON, or a node
  that answers with an error, throws."
  [body]
  (let [raw (json/object body)]
    (cond
      (nil? raw)
      (throw (ex-info "The node did not answer with JSON"
                      {:type ::malformed :body (subs (str body) 0 (min 200 (count (str body))))}))

      (get raw "error")
      (throw (ex-info (str "The node answered with an error: " (get-in raw ["error" "message"]))
                      {:type ::error :error (get raw "error")}))

      :else
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

(defn ^:no-doc ping
  "The podping in the operation `op` of `block` at `timestamp`, nil when it
  holds none: a custom_json with a podping id and iris (urls in the first
  versions), its medium and reason from the payload or else from the id."
  [block timestamp op]
  (let [[kind {:strs [id json required_posting_auths]}] (operation op)]
    (when (and (= "custom_json" kind) (podping-id? id))
      (let [body (json/parse json)
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
  "The podpings in the body of a `:blocks` or an `:operations` answer, in
  the order of the chain."
  [body]
  (let [r (result body)]
    (vec (if (map? r)
           (block-pings (get r "blocks"))
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
  "The URLs among `urls` that any of `pings` concerns, in the order given,
  each URL's podcast:guid computed once, not again for every ping."
  [pings urls]
  (let [pinged (into #{} (comp (mapcat :iris) (map guid/from-url)) pings)]
    (filterv #(contains? pinged (guid/from-url %)) urls)))

(defn notify-request
  "The call that a host makes to announce that the feed at `url` changed,
  with the `token` that the service gave the host. The token can be a
  hidden text of `dk.simongray.podcast.secret`. The request has the token
  in a header, as a hidden text.

  `opts` give the `:medium` (default \"podcast\") and the `:reason`:
  \"update\", \"live\" or \"liveEnd\" (default \"update\"). The call goes to
  `gateway`, or to the `:gateway` of `opts`, the URL of another service
  that takes the same call."
  ([token url]
   (notify-request token url {}))
  ([token url {:keys [medium reason] :or {medium "podcast" reason "update"} :as opts}]
   {:pre [(map? opts)]}
   (let [service (:gateway opts gateway)]
     {:method  :get
      :url     (str service
                    (if (str/includes? service "?") "&" "?")
                    (http/query-string {:url url :reason reason :medium medium}))
      :headers {"Authorization" (secret/hide token) "User-Agent" http/user-agent}})))

(defn call!
  "Send `call` with `params` as `opts` say, and return the body, for
  `head-block`, `pings` or `writers` to read.

  On the JVM, the library's HTTP client tries the `:nodes` in `opts`
  (default `nodes`), or the one `:node`, in turn until one answers, each
  given `:timeout-seconds` (default 10); an exception, a status other than
  200, or an answer that is not JSON or holds a JSON-RPC error, which
  proxies send with a 200, is that node's failure, and when every node
  fails, the exception names them all. In ClojureScript, the platform's
  `fetch` sends it to the node in `opts`, which the public nodes allow from
  any origin, and a js/Promise of the body comes back."
  [call params opts]
  {:pre [(map? opts)]}
  #?(:clj
     (let [candidates (if-let [node (:node opts)] [node] (or (:nodes opts) nodes))
           timeout    (:timeout-seconds opts 10)
           ask!       (fn [node]
                        (try
                          (let [{:keys [status body]} (http/call! (assoc (request call params {:node node})
                                                                         :timeout-seconds timeout))]
                            (if (= 200 status)
                              (do (result body)
                                  {:body body})
                              {:failure (str "answered " status)}))
                          (catch Exception e
                            {:failure (async/explain e)})))]
       (loop [[node & more] candidates
              failures      []]
         (let [{:keys [body failure]} (ask! node)]
           (cond
             body       body
             (seq more) (recur more (conj failures [node failure]))
             :else      (throw (ex-info (str "No node answered " (name call))
                                        {:type ::unavailable :failures (conj failures [node failure])}))))))
     :cljs
     (let [{:keys [url headers body]} (http/revealed (request call params opts))]
       (-> (js/fetch url (clj->js {:method "POST" :headers headers :body body}))
           (.then (fn [res] (.text res)))))))

#?(:clj
   (defn ^:no-doc attempted
     "What `(f)` gives, a reading of the answer to a call. When it throws,
     the result is `{:error diagnostic}`, and the diagnostic is
     `:podping/unavailable`."
     [f]
     (try
       (f)
       (catch Exception e
         {:error (diagnostics/describe
                  (into {:id :podping/unavailable :reason (async/explain e)}
                        (select-keys (ex-data e) [:failures])))}))))

#?(:clj
   (defn- read-chunk!
     "The podpings in the blocks `chunk`, numbers in a row, that one request
     gets: `{:pings :through}`. `:through` is the last block before the
     first block that the node left out of its answer."
     [chunk opts]
     (let [blocks  (get (result (call! :blocks {:from (first chunk) :count (count chunk)} opts)) "blocks")
           numbers (set (map #(block-number (get % "block_id")) blocks))
           through (last (take-while numbers chunk))]
       {:through through
        :pings   (filterv #(and through (<= (:block %) through)) (block-pings blocks))})))

#?(:clj
   (defn- read-blocks!
     "The podpings in the blocks `start` to `end`, and the last block read:
     `{:pings :block}`. A request gets 100 blocks, since 100 blocks answer
     in a second and 1000 blocks in 40 seconds.

     A node that leaves blocks out of its answer ends the reading before
     the first block that it left out. So the next step asks for them
     again, and no ping comes twice. A call that fails ends the reading
     too, and the result has the `:error` of it."
     [start end opts]
     (loop [chunks (partition-all 100 (range start (inc end)))
            found  []
            read   (dec start)]
       (let [chunk                         (first chunks)
             {:keys [pings through error]} (when chunk (attempted #(read-chunk! chunk opts)))
             found                         (into found pings)]
         (cond
           (nil? chunk)             {:pings found :block read}
           error                    {:pings found :block read :error error}
           (= through (last chunk)) (recur (rest chunks) found through)
           :else                    {:pings found :block (or through read)})))))

#?(:clj
   (defn poll!
     "The podpings that came after the block `from`, up to the head of the
     chain. This is the one step that a poller repeats:

         {:pings   [...]                 ; from the writers that podping follows, in chain order
          :refused [...]                 ; from each other account
          :block   110254840             ; the last block read, the `from` of the next step
          :writers #{\"podping.eee\" …}   ; the writers, to give back in `opts`
          :error   {…}}                  ; when no node answered a call

     With `from` nil, the step starts at the head. `opts` can give these:

     - `:max-blocks`, the most blocks that one step reads (default 100,
       five minutes of the chain). A poller that is behind reads on from
       `:block`.
     - `:up-to`, which is `:head` (the default) or `:irreversible`, the
       last block that a fork cannot take back
     - `:writers` from the last step, so that the step does not read them
       again
     - `:verify?` false, to keep each ping, whatever account wrote it
     - `:nodes`, `:node` and `:timeout-seconds`, as `call!` takes them

     `:block` is the last block that a node answered with. So the next
     step reads a block that an answer left out.

     `poll!` throws nothing for what a node or the network does. When no
     node answers a call, the result has the diagnostic
     `:podping/unavailable` as its `:error`. It has the pings of the
     blocks that the step read before that call, and `:block` names the
     last of those blocks."
     ([from]
      (poll! from {}))
     ([from {:keys [max-blocks verify? up-to] :or {max-blocks 100 verify? true up-to :head} :as opts}]
      {:pre [(map? opts)]}
      (let [known    (or (:writers opts)
                         (when verify? (attempted #(writers (call! :following {} opts)))))
            position (if (:error known)
                       known
                       (attempted #(head-block (call! :head-block {} opts))))]
        (if-let [error (:error position)]
          {:pings [] :refused [] :block from :writers (:writers opts) :error error}
          (let [head  (if (= :irreversible up-to) (:irreversible position) (:block position))
                start (if from (inc from) head)
                end   (min head (+ start (dec max-blocks)))
                {:keys [pings block error]} (read-blocks! start end opts)
                {ok true refused false}     (if verify?
                                              (group-by #(contains? known (:account %)) pings)
                                              {true pings})]
            (cond-> {:pings   (vec ok)
                     :refused (vec refused)
                     :block   (if (<= start end) block (or from head))
                     :writers known}
              error (assoc :error error))))))))

#?(:clj
   (comment
     (def step (poll! nil))
     (def step (poll! (:block step) {:writers (:writers step)}))
     (map (juxt :account :medium :reason :iris) (:pings step))
     (affected (:pings step) ["https://feeds.podcastindex.org/pc20.xml"])
     #_.))
