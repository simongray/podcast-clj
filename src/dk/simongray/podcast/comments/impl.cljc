(ns ^:no-doc dk.simongray.podcast.comments.impl
  "The internals of comments: the table of the protocol readers that it
  dispatches to, the exchanges and the order of the requests of a fetch,
  and the nesting of a tree."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.activitypub :as activitypub]
            [dk.simongray.podcast.comments.activitypub.impl
             :as activitypub.impl]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.atproto.impl :as atproto.impl]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.comments.nostr.impl :as nostr-comments.impl]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]))

(defn- nostr-opts
  "The `opts` of a hiccup of a note of the Nostr `thread`, with the names
  of its authors as :names, unless `opts` have their own."
  [thread opts]
  (cond-> opts
    (not (contains? opts :names))
    (assoc :names (nostr-comments.impl/names thread))))

(def readers
  "The reader of each protocol, as the functions that this namespace calls
  for a thread of it. A reader keeps the post of a comment under a key of
  its own, which :parts and :hiccup read."
  {"activitypub"
   {:resumed        activitypub.impl/resumed
    :requests       activitypub/requests
    :with-exchanges activitypub.impl/with-exchanges
    :readable?      (fn [interact _] (activitypub.impl/readable? interact))
    :parts          (fn [_ comment]
                      (some-> (::activitypub/source comment)
                              activitypub.impl/parts))
    :hiccup         (fn [_ comment opts]
                      (some-> (::activitypub/source comment)
                              (activitypub.impl/hiccup opts)))}

   "atproto"
   {:resumed        atproto.impl/resumed
    :requests       atproto/requests
    :with-exchanges atproto/with-exchanges
    :readable?      atproto/readable?
    :parts          (fn [_ comment]
                      (some-> (::atproto/record comment) atproto.impl/parts))
    :hiccup         (fn [_ comment opts]
                      (some-> (::atproto/record comment)
                              (atproto.impl/hiccup opts)))}

   "nostr"
   {:resumed        nostr-comments.impl/resumed
    :requests       nostr-comments/requests
    :with-exchanges nostr-comments.impl/with-exchanges
    :readable?      nostr-comments/readable?
    :parts          (fn [thread comment]
                      (some-> (::nostr-comments/event comment)
                              (nostr-comments.impl/parts
                               {:names (nostr-comments.impl/names thread)})))
    :hiccup         (fn [thread comment opts]
                      (some-> (::nostr-comments/event comment)
                              (nostr-comments.impl/hiccup
                               (nostr-opts thread opts))))}})

(defn reader
  "The function `k` of the reader of the protocol of `thread`, or nil for a
  protocol that no reader reads."
  [thread k]
  (get-in readers [(:protocol thread) k]))

(defn errors
  "The errors of the reading of `thread` below its root, as diagnostics
  without their level.

  They're those of the Mastodon API, the conversation, the relays, the
  posts, the collections of their replies and the authors."
  [{:keys [comments authors]
    ::comments/keys [api conversation]
    ::nostr-comments/keys [relays]
    :as thread}]
  (concat (keep :error [api conversation])
          (keep :error (map val (sort-by key relays)))
          (keep :error (vals comments))
          (keep (comp :error ::comments/collection) (vals comments))
          (keep :error (vals authors))))

(defn origin
  "The origin of the URL `url` of a server or of a relay."
  [url]
  (url/origin (str/replace (str url) #"(?i)^ws" "http")))

(defn spared-origins
  "The origins that the reading of `thread` asks no more: those of the
  servers that didn't answer in time or answered 429, Too Many Requests.
  A server is spared in one reading only."
  [{:keys [error] :as thread}]
  (set (for [{:keys [url status timeout?]} (cons error (errors thread))
             :when (or timeout? (= 429 status))]
         (origin url))))

(defn pending
  "The requests that `thread` needs next with the limits of `opts`, of those
  whose URLs `ask-pred` takes."
  [thread opts ask-pred]
  (if-let [f (reader thread :requests)]
    (f thread opts ask-pred)
    []))

(defn distinct-by
  "The `xs` without those that have the same `f` as one before them."
  [f xs]
  (let [seen   (volatile! #{})
        first? (fn [x]
                 (let [k (f x)]
                   (when-not (contains? @seen k)
                     (vswap! seen conj k))))]
    (filterv first? xs)))

(defn levels
  "The posts of a thread from the post `top` down, level by level, with
  each post once and 256 levels at most. The `children` map gives the
  replies to a post by its id, and `shown-pred` tells which replies count."
  [top children shown-pred]
  (loop [levels [[top]] seen #{(:id top)}]
    (let [below (into []
                      (comp (mapcat #(get children (:id %)))
                            (filter shown-pred)
                            (remove #(seen (:id %))))
                      (peek levels))]
      (if (or (empty? below) (>= (count levels) 256))
        levels
        (recur (conj levels below) (into seen (map :id) below))))))

(defn- oldest-first
  "The key that puts the comment `c` in its place among replies: by the
  time of its publication, and a comment without one last."
  [{:keys [published id] :as c}]
  [(if published (inst-ms published) ##Inf) id])

(defn nested
  "Each post of `levels` by its id, with the :replies below it from
  `children` and the map of its :author from `authors`.

  The posts are built from the deepest level up. A deleted post, or a post
  with an error, is there only when a reply below it is, or when it's the
  root."
  [levels children authors]
  (let [root (:id (ffirst levels))]
    (reduce (fn [built {:keys [id author deleted? error] :as c}]
              (let [replies (->> (get children id)
                                 (keep #(get built (:id %)))
                                 (sort-by oldest-first)
                                 vec)
                    profile (when author (get authors author {:id author}))
                    post    (cond-> (assoc c :replies replies)
                              author (assoc :author profile))]
                (if (and (not= id root)
                         (or deleted? error)
                         (empty? replies))
                  built
                  (assoc built id post))))
            {}
            (apply concat (rseq levels)))))

(defn exchange!
  "Send `request` by the :send of `opts`, with its limits of time and size,
  and give the exchange, in a future or a promise. The time is cut to
  what's left before the ::deadline of `opts`.

  The exchange is a map of the :request, the :response without its body,
  and the :document, the JSON object that the body held. When sending
  failed, the response is a map of the :error, and :timeout? when it took
  too long. A body larger than the limit fails too, when a :send didn't
  keep to it.

  A failed response is :retry?, unless the guard of the request refused
  its URL or the answer was too large. A later request gets the same
  failure then."
  [request
   {:keys [send timeout-seconds max-bytes now-fn] ::comments/keys [deadline]
    :as opts}]
  (let [left    (when deadline (quot (- deadline (inst-ms (now-fn))) 1000))
        seconds (max 1 (min timeout-seconds (or left timeout-seconds)))
        lasting #{::http/refused ::http/too-large}
        stays?  #(contains? lasting (:type (ex-data %)))
        failure (fn [error]
                  (cond-> {:error (async/explain error)}
                    (http/timeout? error) (assoc :timeout? true)
                    (not (stays? error))  (assoc :retry? true)))
        large?  #(and max-bytes (> (count (str (:body %))) max-bytes))]
    (async/then
     (http/try-send! send request (assoc opts :timeout-seconds seconds))
     (fn [{:keys [value error]}]
       (cond
         error
         {:request request :response (failure error)}

         (large? value)
         (let [reason (str "The answer is larger than " max-bytes " bytes")]
           {:request request :response {:error reason}})

         :else
         {:request  request
          :response (dissoc value :body)
          :document (common/document value)})))))

(defn failed
  "The exchanges of `request` when reading them failed with `e`: one, with
  the reason as its response."
  [request e]
  [{:request request :response {:error (async/explain e)}}])
