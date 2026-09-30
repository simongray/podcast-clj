(ns dk.simongray.podcast.app.show
  "One show as a page shows it, in one map. `view` is what the feed says
  of the show: the feed, its properties as `feed.resolve` answers them,
  the description as Hiccup and as text, the episodes in listening order,
  the seasons, and the links to the apps. `view!` adds what the network
  says: the shows of the podroll as the Podcast Index knows them, and the
  publisher feed with its shows, when it confirms the show.

  A page reads one map. The functions below the view give the same
  answers, so an app drops down to them when a step is not what it wants.
  `dk.simongray.podcast.app.episode` is the view of one episode."
  (:require [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]))

(def default-limits
  "The limits of `view!` that its options do not set: the seconds that a
  request can take, and the most bytes of a publisher feed."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn view
  "The feed `show`, as a page shows it: the feed with the properties of
  `resolve/properties` merged in, and these keys:

  - `:guid`, the identity of the feed as `feed/feed-guid` gives it, when
    the feed has one or `opts` give the `:url` of the feed
  - `:notes`, the description as Hiccup, and `:text`, as plain text
  - `:episodes`, the items in listening order, as `resolve/episodes`
    gives them, and `:seasons`, as `resolve/seasons` gives them
  - `:links`, the links to the show in the apps, as `links/links` gives
    them

  `opts` give the `:url` of the feed, for a feed that does not name its
  own. `:except` is a set of the keys above that the view leaves out, and
  does not compute."
  ([show]
   (view show {}))
  ([show {:keys [url except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [wanted? (complement except)
         url     (or url (resolve/self-url show))
         guid    (feed/feed-guid show url)
         notes   (when (and (or (wanted? :notes) (wanted? :text)) (resolve/description show))
                   (html/hiccup (resolve/description show)))]
     (cond-> (merge show (resolve/properties show))
       guid                         (assoc :guid guid)
       (and notes (wanted? :notes)) (assoc :notes notes)
       (and notes (wanted? :text))  (assoc :text (html/text notes))
       (wanted? :episodes)          (assoc :episodes (vec (resolve/episodes show)))
       (wanted? :seasons)           (assoc :seasons (vec (resolve/seasons show)))
       (wanted? :links)             (assoc :links (links/links (links/ids show nil {:url url})))))))

(defn- unreachable
  "The diagnostic of the feed at `url` that did not come: the request
  failed with `e`, or the answer had the `status` of a failure."
  [url e status]
  (if e
    (assoc (bytes/unreachable e) :url url :id :fetch/unreachable)
    (diagnostics/describe {:id :fetch/unreachable :url url :status status})))

(defn- known-show!
  "The remote item `item` with the `:show` that the Podcast Index knows
  for it, asked with the `:index` credentials of `opts`, signed at the
  time `:now` gives; deferred. The item as it is when the index does not
  know it, or does not answer."
  [item {:keys [index send now] :as opts}]
  (let [requests (podcastindex/remote-item-requests index item {:auth-date (quot (inst-ms (now)) 1000)})]
    (async/then (async/all (map #(http/sent! send % opts) requests))
                (fn [answers]
                  (let [found (some (fn [{:keys [value]}]
                                      (when (string? (:body value))
                                        (:feed (try (podcastindex/response (:body value))
                                                    (catch #?(:clj Exception :cljs :default) _ nil)))))
                                    answers)]
                    (cond-> item found (assoc :show found)))))))

(defn- publisher-feed!
  "The publisher `item` of the view of `show`, with the `:feed` of the
  publisher, whether it `:confirmed?` the show, and its `:shows`, the
  remote items of the publisher feed; deferred. With `:error` when the
  feed did not come."
  [item {:keys [guid]} {:keys [send] :as opts}]
  (let [url (:feed-url item)]
    (async/then (http/sent! send {:url url :headers {"Accept" "application/rss+xml, application/xml;q=0.9, */*;q=0.1"}} opts)
                (fn [{:keys [value error]}]
                  (cond
                    error
                    (assoc item :error (unreachable url error nil))

                    (not (<= 200 (long (:status value)) 299))
                    (assoc item :error (unreachable url nil (:status value)))

                    :else
                    (async/then (async/attempt #(podcast/parse (str (:body value))))
                                (fn [{parsed :value error :error}]
                                  (if error
                                    (assoc item :error (diagnostics/describe {:id :fetch/malformed :url url :reason (async/explain error)}))
                                    (let [shows (vec (resolve/remote-items parsed))]
                                      (assoc item
                                             :feed       parsed
                                             :shows      shows
                                             :confirmed? (boolean (some #(= guid (:feed-guid %)) shows))))))))))))

(defn view!
  "The `view` of the feed `show`, with what the network says of it. On
  the JVM the map, in ClojureScript a promise of it.

  - `:podroll` has each recommended show with `:show`, the show as the
    Podcast Index knows it, when `opts` give the `:index` credentials and
    the index knows the show. The Podcast Index is one of the directories,
    so the show has the keys of `dk.simongray.podcast.directory`.
  - `:publisher` has the `:feed` of the publisher, its `:shows`, and
    `:confirmed?`, which tells whether the publisher feed lists this show.
    The podcast namespace asks an app to show the other shows of a
    publisher only when it does. With `:error` when the feed did not
    come.

  `view!` throws nothing for what the network or a server does. `opts`
  are those of `view`, and of the requests: `:send`, a function that
  sends a request map in the place of `http/call!`, `:allow?`, a
  predicate of a URL, and the `:timeout-seconds` and `:max-bytes` of
  `default-limits`. `:index` gives the credentials of the Podcast Index,
  and `:now` a function that gives the instant of now, to sign them. In
  `:except`, `:podroll` and `:publisher` leave those unasked."
  ([show]
   (view! show {}))
  ([show {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts    (merge default-limits
                        {:send http/call!
                         :now  #?(:clj #(java.util.Date.) :cljs #(js/Date.))}
                        opts)
         wanted? (complement except)
         v       (view show opts)]
     (async/then (async/all [(when (and (wanted? :podroll) (:index opts))
                               (async/all (map #(known-show! % opts) (:podroll v))))
                             (when (and (wanted? :publisher) (:feed-url (:publisher v)))
                               (publisher-feed! (:publisher v) v opts))])
                 (fn [[podroll publisher]]
                   (cond-> v
                     (seq podroll) (assoc :podroll (vec podroll))
                     publisher     (assoc :publisher publisher)))))))

(comment
  (def show (:feed (podcast/fetch! "https://feeds.podcastindex.org/pc20.xml")))

  (select-keys (view show) [:title :author :guid :categories :links])
  (map :title (:episodes (view show)))
  (count (:seasons (view show)))

  ;; the podroll as the index knows it, with the credentials of the index
  (def credentials {:key "…" :secret "…"})
  (map (juxt :title (comp :rss/title :show)) (:podroll (view! show {:index credentials})))
  (:confirmed? (:publisher (view! show)))
  #_.)
