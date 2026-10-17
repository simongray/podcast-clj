(ns dk.simongray.podcast.app.show
  "Everything a show page needs, in one map."
  (:require [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch.async :as async]))

(def default-limits
  "The limits of a fetch of linked shows when its options don't set them:
  how many seconds a request can take, and the most bytes of a publisher
  feed."
  {:timeout-seconds 15
   :max-bytes       (* 16 1024 1024)})

(defn view
  "The show of `feed` as a page shows it, with the `opts` below: the feed
  with the properties of resolve/properties merged in, and these keys:

  - :guid, the id that the feed gives itself now, for links and remote
    items, when the feed has one or `opts` give the :url of the feed
  - :id, the key that a library keeps the feed under, as guid/feed-key
    gives it, which can differ from the :guid once the feed moved
  - :notes, the description as Hiccup, and :text, as plain text
  - :episodes, the items in listening order, in place of :rss/items
  - :seasons, the items by season, as maps of :season, :name and :items
  - :links, links to the show in other apps

  The `opts` are:

  - :url, the URL of the feed, for a feed that doesn't name its own
  - :notes-url-fn, a function of the web page of the show and a URL in
    its description, which gives the URL to keep or nil, by default the
    URL made absolute
  - :heading-level, the level that the highest heading of the notes
    takes, e.g. 3 on a page whose own headings are h1 and h2
  - :link-fn, a function of each link of the notes, as Hiccup, e.g.
    [:a {:href \"https://example.com\"} \"Example\"], which gives the Hiccup
    in its place, e.g. the link with a :target. What it gives is kept as
    it is, so it's for the app's own code, and the text of the notes has
    its text too.
  - :except, a set of the keys above to leave out without computing them"
  ([feed]
   (view feed {}))
  ([feed {:keys [url notes-url-fn heading-level link-fn except]
          :or   {notes-url-fn resolve/notes-url except #{}}
          :as   opts}]
   {:pre [(map? opts)]}
   (let [wanted? (complement except)
         url     (or url (resolve/self-url feed))
         guid    (guid/feed-guid feed url)
         k       (guid/feed-key feed url)
         page    (resolve/link feed)
         notes   (when (or (wanted? :notes) (wanted? :text))
                   (some-> (resolve/description feed)
                           (html/hiccup
                            (cond-> {:url-fn        #(notes-url-fn page %)
                                     :heading-level heading-level}
                              link-fn (assoc :element-fn          #(if (= :a (first %)) (link-fn %) %)
                                             :trusted-element-fn? true)))))]
     (cond-> (merge feed (resolve/properties feed))
       guid
       (assoc :guid guid)

       k
       (assoc :id k)

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

(defn- look-up-show!
  "Ask the Podcast Index with `opts` for the :show of the remote `item`,
  and give the item with it, in a future or a promise. Without an answer,
  it's the item as it is."
  [item {:keys [index] :as opts}]
  (async/then (podcastindex/fetch-remote-item! index item opts)
              (fn [{:keys [feed]}]
                (cond-> item feed (assoc :show feed)))))

(defn- fetch-feed!
  "Fetch the feed at `url` with `opts`, and give a map of the :feed, or of
  an :error when it couldn't, in a future or a promise."
  [url opts]
  (let [fetch-opts (select-keys opts [:send :allow-pred :timeout-seconds
                                      :max-bytes :user-agent])]
    (async/then (podcast/fetch! url fetch-opts)
                (fn [{:keys [feed error]}]
                  (if feed
                    {:feed feed}
                    {:error error})))))

(defn- find-podroll-show!
  "Give the remote `item` of a podroll with its :show, in a future or a
  promise: from the Podcast Index with the :index of `opts`, or else from
  the start of the feed at its URL. An item that names no URL stays as it
  is without the :index, and one whose feed fails has an :error."
  [item opts]
  (cond
    (:index opts)    (look-up-show! item opts)
    (:feed-url item) (async/then (discovery/fetch-head! (:feed-url item) opts)
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
  below. Returns a map, in ClojureScript as a promise:

  - :podroll has each recommended show with its :show. It comes from the
    Podcast Index when `opts` give the :index credentials, without its
    items, or else from the start of the feed at the URL that the podroll
    names, as discovery/fetch-head! gives it.
  - :publisher has the :feed of the publisher, its :shows, and
    :confirmed?, which says whether the publisher feed lists this show.
    The podcast namespace asks apps to show the other shows of a publisher
    only when it does.

  Nothing is thrown for what the network or a server does, and a part
  that failed has an :error. The `opts` are:

  - :send and :allow-pred, as for any request
  - :timeout-seconds and :max-bytes, over default-limits
  - :index, the credentials of the Podcast Index, and :now-fn, a function of
    the current time to sign the requests with
  - :except, a set of the parts to leave out"
  ([view]
   (fetch-linked-shows! view {}))
  ([view {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (let [opts           (merge default-limits
                               {:send   client/send!
                                :now-fn time/now}
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
