(ns podcast-server.hubs
  "The server's subscriptions to the WebSub hubs of its feeds, so that a
  hub pushes a feed that changed and the server fetches it within a minute.

  The subscriptions are websub-clj's map, by feed guid, kept in the data
  folder with their secrets. After each step of the library, the server
  sends the hubs the requests that its shows need. A push only names the
  feed to fetch at the next step, since the server keeps no feed in memory
  between its steps."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.websub :as websub]
            [io.pedestal.log :as log]))

(def subscriptions-name
  "The document of the data folder that holds the subscriptions."
  "websub-subscriptions.edn")

(def callback-path
  "The path below which the hubs call the server."
  "/websub/")

(defn- saved
  "The subscriptions that `data-store` holds, or none when it holds none or
  they don't read, which is logged. Without them, the shows subscribe
  again."
  [data-store]
  (try
    (or (some-> ((:fetch data-store) subscriptions-name {})
                :body
                websub/parse-subscriptions)
        {})
    (catch Exception e
      (log/warn :msg "The WebSub subscriptions didn't read" :exception e)
      {})))

(defn- save!
  "Write the `subscriptions` to `data-store`, and log a failure."
  [data-store subscriptions]
  (try
    (let [put (:put data-store)]
      (put subscriptions-name (websub/format-subscriptions subscriptions) {}))
    (catch Exception e
      (log/warn :msg "The WebSub subscriptions couldn't be saved"
                :exception e))))

(defn make
  "The WebSub hubs of the server at `public-url`, with the subscriptions
  that `data-store` holds. It's a map of the :public-url, an atom of the
  :subscriptions, which are saved to `data-store` whenever they change,
  and an atom of the feed guids that hubs :pushed since the last step."
  [data-store public-url]
  (let [subscriptions (atom (saved data-store))]
    ;; the latest map is written, whichever change came last
    (add-watch subscriptions ::save
               (fn [_ _ old new]
                 (when (not= old new)
                   (locking subscriptions
                     (save! data-store @subscriptions)))))
    {:public-url    public-url
     :subscriptions subscriptions
     :pushed        (atom #{})}))

(defn callback-url
  "A new callback URL of the `hubs`, which nobody can guess."
  [{:keys [public-url] :as hubs}]
  (str public-url callback-path (websub/token)))

(defn ask!
  "Send the requests that the shows of `library` need to their hubs, as
  the `hubs` keep them, with the `opts` of websub/call!, and log a
  failure. A request that fails is sent again an hour later."
  ([library hubs]
   (ask! library hubs {}))
  ([library {:keys [subscriptions] :as hubs} opts]
   (let [callback-fn (fn [_ _] (callback-url hubs))
         opts        (merge {:user-agent client/user-agent} opts)]
     (doseq [[feed-guid request] (library/hub-requests library
                                                       @subscriptions
                                                       callback-fn)]
       (swap! subscriptions websub/asked feed-guid request (time/now))
       (try
         (websub/call! request opts)
         (catch Exception e
           (log/warn :msg "A WebSub hub didn't take a request"
                     :feed-guid feed-guid
                     :hub (:url request)
                     :exception e)))))))

(defn pushed!
  "The feed guids that the hubs of `hubs` pushed since the last call, which
  are then forgotten."
  [{:keys [pushed] :as hubs}]
  (first (reset-vals! pushed #{})))

(defn handler
  "The Ring handler of the callback URLs of the `hubs`, which confirms the
  checks of the hubs and notes each feed that a hub pushes with the right
  signature."
  [{:keys [subscriptions pushed] :as hubs}]
  (websub/handler
   (assoc (websub/handler-options #(deref subscriptions))
          :verified! #(swap! subscriptions websub/verified % (time/now))
          :denied!   #(swap! subscriptions websub/denied % (time/now))
          :deliver!  (fn [{:keys [request]}]
                       (when-let [[feed-guid] (websub/subscription-of
                                               @subscriptions request)]
                         (swap! pushed conj feed-guid))))))

(defn routes
  "The route of the callback URLs of the `hubs`, in Pedestal's set syntax."
  [hubs]
  #{[(str callback-path "*token") :any (handler hubs)
     :route-name ::callback]})

(defn callback?
  "Whether `uri` is the path of a callback URL, which the hubs call
  without a password."
  [uri]
  (str/starts-with? (str uri) callback-path))
