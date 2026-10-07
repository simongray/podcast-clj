(ns dk.simongray.podcast.app.show
  "Everything a show page needs, in one map.

  Use view for what the feed says about the show, and merge in
  fetch-linked-shows! for the shows that it links to."
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.time :as time]))

(def default-limits
  "The limits of fetch-linked-shows! when its options don't set them: how
  many seconds a request can take, and the most bytes of a publisher feed."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn view
  "The show of `feed` as a page shows it, with the `opts` below: the feed
  with the properties of resolve/properties merged in, and these keys:

  - :guid, the id of the feed as guid/feed-guid gives it, when the feed
    has one or `opts` give the :url of the feed
  - :notes, the description as Hiccup, and :text, as plain text
  - :episodes, the items in listening order, as resolve/episodes gives
    them, in place of :rss/items
  - :seasons, as resolve/seasons gives them
  - :links, links to the show in other apps, as links/links gives them

  The `opts` are:

  - :url, the URL of the feed, for a feed that doesn't name its own
  - :except, a set of the keys above to leave out without computing them"
  ([feed]
   (view feed {}))
  ([feed {:keys [url except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [wanted? (complement except)
         url     (or url (resolve/self-url feed))
         guid    (guid/feed-guid feed url)
         notes   (when (or (wanted? :notes) (wanted? :text))
                   (some-> (resolve/description feed) html/hiccup))]
     (cond-> (merge feed (resolve/properties feed))
       guid
       (assoc :guid guid)

       (and notes (wanted? :notes))
       (assoc :notes notes)

       (and notes (wanted? :text))
       (assoc :text (html/text notes))

       (wanted? :episodes)
       (-> (dissoc :rss/items)
           (assoc :episodes (vec (resolve/episodes feed))))

       (wanted? :seasons)
       (assoc :seasons (vec (resolve/seasons feed)))

       (wanted? :links)
       (assoc :links (links/links (links/ids feed nil {:url url})))))))

(defn- unreachable
  "The diagnostic for the feed at `url` that couldn't be fetched: that the
  answer had the `status` of a failure, or else the `reason` that the
  request failed."
  [url status reason]
  (diagnostics/describe (if status
                          {:id :fetch/status :url url :status status}
                          {:id :fetch/unreachable :url url :reason reason})))

(defn- look-up-show!
  "Ask the Podcast Index with `opts` for the :show of the remote `item`,
  and give the item with it, in a future or a promise. Without an answer,
  it's the item as it is."
  [item {:keys [index send now] :as opts}]
  (let [requests (podcastindex/remote-item-requests
                  index item {:auth-date (quot (inst-ms (now)) 1000)})
        show     (fn [{:keys [value]}]
                   (when (string? (:body value))
                     (:feed (try
                              (podcastindex/response (:body value))
                              (catch #?(:clj Exception :cljs :default) _
                                nil)))))]
    (async/then (async/all (map #(http/try-send! send % opts) requests))
                (fn [answers]
                  (let [found (some show answers)]
                    (cond-> item found (assoc :show found)))))))

(defn- fetch-feed!
  "Fetch and parse the feed at `url` with `opts`, and give a map of the
  :feed, or of an :error when it couldn't, in a future or a promise."
  [url {:keys [send] :as opts}]
  (let [accept  "application/rss+xml, application/xml;q=0.9, */*;q=0.1"
        request {:url url :headers {"Accept" accept}}]
    (async/then (http/try-send! send request opts)
                (fn [{:keys [value error]}]
                  (cond
                    error
                    {:error (unreachable url nil (async/explain error))}

                    (not (<= 200 (long (:status value)) 299))
                    {:error (unreachable url (:status value) nil)}

                    :else
                    (rss/try-parse (str (:body value)) {:url url}))))))

(defn- held
  "How many bytes the view `v` holds from the start of the file on."
  [v]
  (loop [p 0]
    (if-let [[o b] (bytes/part-at v p)]
      (recur (+ o (bytes/length b)))
      p)))

(defn- feed-text
  "The text of the bytes `b` of a feed, as fetch! reads a feed: by the
  charset that it declares on the JVM, and as UTF-8 in JavaScript."
  [b]
  #?(:clj  (http/decode {} b)
     :cljs (bytes/decode b 0 (bytes/length b) :utf8)))

(defn- channel-head
  "A reading of the view `v` of a feed: the text of the feed cut before its
  first item and closed, or the bytes that it needs to find that item."
  [v]
  (bytes/reading
   #(let [n          (held v)
          head       (bytes/text v 0 n :latin1)
          [_ before] (re-find #"(?s)^(.*?)<item[\s/>]" head)]
      (cond
        before
        {:text (str (feed-text (bytes/slice v 0 (count before)))
                    "</channel></rss>")}

        (= n (:total v))
        {:text (feed-text (bytes/slice v 0 n))}

        :else
        (bytes/need v n (inc n))))))

;; TODO: Reconsider the design of these fetches. The podroll reads only the
;; start of each feed, tens of KB instead of the MB of a whole feed.
;; find-feeds!, OPML previews and the guid check before subscribing fetch
;; whole feeds for a title or a guid, and could do the same. The trade-offs:
;; - RSS lets channel elements come after the episodes, so the start of a
;;   feed can miss some. It's enough for a title and artwork, but not for
;;   deciding anything about the show.
;; - Stored as a feed, it would look like a show without episodes.
;; - It only works for encodings like UTF-8, where the first <item> can be
;;   found before decoding.
;; - Made public, it needs a name and a mark that tells it from a feed.
(defn- read-head!
  "Read the feed at `url` with `opts` up to its first item, most often
  from the first 64 KB alone, and give a map of the :feed without its
  items, or of an :error, in a future or a promise."
  [url opts]
  (async/then (http/read-by-ranges! url channel-head opts)
              (fn [{:keys [text error] :as result}]
                (cond
                  text
                  (rss/try-parse text {:url url})

                  error
                  {:error (unreachable url (:status error) (:reason error))}

                  :else
                  {:error (assoc (first (bytes/report result)) :url url)}))))

(defn- find-podroll-show!
  "Give the remote `item` of a podroll with its :show, in a future or a
  promise: from the Podcast Index with the :index of `opts`, or else from
  the head of the feed at its URL, without the items. An item that names
  no URL stays as it is without the :index, and one whose feed fails has
  an :error."
  [item opts]
  (cond
    (:index opts)    (look-up-show! item opts)
    (:feed-url item) (async/then (read-head! (:feed-url item) opts)
                                 (fn [{:keys [feed error]}]
                                   (if error
                                     (assoc item :error error)
                                     (assoc item :show feed))))
    :else            item))

(defn- fetch-publisher-feed!
  "Fetch the :feed of the publisher `item` of the show `view` with `opts`,
  and give the item with it, in a future or a promise.

  The item also has the :shows of the publisher feed, and :confirmed?
  when that feed lists the show, or an :error when the feed couldn't be
  fetched."
  [item {:keys [guid] :as view} opts]
  (async/then (fetch-feed! (:feed-url item) opts)
              (fn [{:keys [feed error]}]
                (if error
                  (assoc item :error error)
                  (let [shows  (vec (resolve/remote-items feed))
                        lists? (some #(= guid (:feed-guid %)) shows)]
                    (assoc item
                           :feed       feed
                           :shows      shows
                           :confirmed? (boolean lists?)))))))

(defn fetch-linked-shows!
  "Fetch the shows that the `view` of a show links to, with the `opts`
  below, for a page to merge into the view. Returns a map, in
  ClojureScript as a promise:

  - :podroll has each recommended show with its :show, the feed without
    its items. It comes from the Podcast Index when `opts` give the :index
    credentials, or else from the start of the feed at the URL that the
    podroll names, as far as its first item.
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
   (let [opts           (merge default-limits
                               {:send http/send!
                                :now  time/now}
                               opts)
         wanted?        (complement except)
         podroll-shows  (delay
                          (when (wanted? :podroll)
                            (async/all #(find-podroll-show! % opts)
                                       (:podroll view))))
         publisher-feed (delay
                          (when (and (wanted? :publisher)
                                     (:feed-url (:publisher view)))
                            (fetch-publisher-feed! (:publisher view)
                                                   view
                                                   opts)))]
     (async/then (async/all force [podroll-shows publisher-feed])
                 (fn [[podroll publisher]]
                   (cond-> {}
                     (seq podroll) (assoc :podroll (vec podroll))
                     publisher     (assoc :publisher publisher)))))))

(comment
  (require '[dk.simongray.podcast :as podcast])
  (def show
    (:feed (podcast/fetch! "https://feeds.podcastindex.org/pc20.xml")))

  (select-keys (view show) [:title :author :guid :categories :links])
  (map :title (:episodes (view show)))
  (count (:seasons (view show)))

  ;; the podroll as the index knows it, with the credentials of the index
  (def credentials {:key "…" :secret "…"})
  (map (juxt :title (comp :rss/title :show))
       (:podroll (fetch-linked-shows! (view show) {:index credentials})))
  (:confirmed? (:publisher (fetch-linked-shows! (view show))))
  (keys (merge (view show) (fetch-linked-shows! (view show))))  ; the page
  #_.)
