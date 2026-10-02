(ns dk.simongray.podcast.app.show
  "Everything a show page needs, in one map.

  Use view for what the feed says about the show, and merge in
  fetch-linked-shows! for the shows that it links to."
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
  "The limits of fetch-linked-shows! when its options don't set them: how
  many seconds a request can take, and the most bytes of a publisher feed."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn view
  "The feed `show` as a page shows it, with the `opts` below: the feed with
  the properties of resolve/properties merged in, and these keys:

  - :guid, the id of the feed as feed/feed-guid gives it, when the feed
    has one or `opts` give the :url of the feed
  - :notes, the description as Hiccup, and :text, as plain text
  - :episodes, the items in listening order, as resolve/episodes gives
    them
  - :seasons, as resolve/seasons gives them
  - :links, links to the show in other apps, as links/links gives them

  The `opts` are:

  - :url, the URL of the feed, for a feed that doesn't name its own
  - :except, a set of the keys above to leave out without computing them"
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
  "The diagnostic for the feed at `url` that couldn't be fetched, because
  the request failed with `e` or the answer had the `status` of a failure."
  [url e status]
  (if e
    (assoc (bytes/unreachable e) :url url :id :fetch/unreachable)
    (diagnostics/describe {:id :fetch/unreachable :url url :status status})))

(defn- known-show!
  "Ask the Podcast Index with `opts` for the :show of the remote `item`,
  and give the item with it, in a future or a promise. Without an answer,
  it's the item as it is."
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
  "Fetch the :feed of the publisher `item` of the show `view` with `opts`,
  and give the item with it, in a future or a promise.

  The item also has the :shows of the publisher feed, and :confirmed? when that
  feed lists the show, or an :error when the feed couldn't be fetched."
  [item {:keys [guid] :as view} {:keys [send] :as opts}]
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

(defn fetch-linked-shows!
  "Fetch the shows that the `view` of a show links to, with the `opts`
  below, for a page to merge into the view. Returns a map, in
  ClojureScript as a promise:

  - :podroll has each recommended show with its :show from the Podcast
    Index, when `opts` give the :index credentials.
  - :publisher has the :feed of the publisher, its :shows, and
    :confirmed?, which says whether the publisher feed lists this show.
    The podcast namespace asks apps to show the other shows of a publisher
    only when it does.

  Nothing is thrown for what the network or a server does, and a part
  that failed has an :error. The `opts` are:

  - :send and :allow?, as for any request
  - :timeout-seconds and :max-bytes, over default-limits
  - :index, the credentials of the Podcast Index, and :now, a function of
    the current time to sign the requests with
  - :except, a set of the parts to leave out"
  ([view]
   (fetch-linked-shows! view {}))
  ([view {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts    (merge default-limits
                        {:send http/call!
                         :now  #?(:clj #(java.util.Date.) :cljs #(js/Date.))}
                        opts)
         wanted? (complement except)]
     (async/then (async/all [(when (and (wanted? :podroll) (:index opts))
                               (async/all (map #(known-show! % opts) (:podroll view))))
                             (when (and (wanted? :publisher) (:feed-url (:publisher view)))
                               (publisher-feed! (:publisher view) view opts))])
                 (fn [[podroll publisher]]
                   (cond-> {}
                     (seq podroll) (assoc :podroll (vec podroll))
                     publisher     (assoc :publisher publisher)))))))

(comment
  (def show (:feed (podcast/fetch! "https://feeds.podcastindex.org/pc20.xml")))

  (select-keys (view show) [:title :author :guid :categories :links])
  (map :title (:episodes (view show)))
  (count (:seasons (view show)))

  ;; the podroll as the index knows it, with the credentials of the index
  (def credentials {:key "…" :secret "…"})
  (map (juxt :title (comp :rss/title :show)) (:podroll (fetch-linked-shows! (view show) {:index credentials})))
  (:confirmed? (:publisher (fetch-linked-shows! (view show))))
  (keys (merge (view show) (fetch-linked-shows! (view show))))  ; the page
  #_.)
