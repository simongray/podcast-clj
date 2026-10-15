(ns dk.simongray.podcast.gpodder
  "The gpodder API as a sync backend of like-minded, for the subscriptions
  and episode actions that a gpodder server keeps.

  Such servers are gpodder.net, the GPodder Sync app for Nextcloud,
  oPodSync and PinePods. They keep the data of apps such as AntennaPod,
  Kasts and gPodder. The design is in doc/sync.md.

  The API names a show by its feed URL, and an episode by the URL of its
  media, where podcast-clj names them by guid. The requests and the
  answers of the API are in dk.simongray.podcast.gpodder.api, and a
  server of it is in dk.simongray.podcast.gpodder.server."
  (:require [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.gpodder.impl :as impl]
            [dk.simongray.wary-fetch.url :as url]))

(def default-limits
  "The limits of a backend when its options don't set them.

  A pull asks for one more page of episode actions when a page holds
  :page-size actions, and it reads :max-pages pages at most. A push sends
  :batch-size episode actions in each request. The page and the batch are
  those of gpodder.net, and another server can have others. The cursor
  keeps the last :max-pending actions that name a feed that the app
  doesn't know."
  {:page-size   1000
   :max-pages   10
   :batch-size  100
   :max-pending 500})

(defn identities
  "The ways to name the shows and episodes of the `feeds`, for the
  :identities-fn of a backend. The feeds are maps of the :url and the
  parsed :feed, and of the :guid that the app keeps the feed under, when
  the feed alone doesn't give it, e.g. for a feed of nothing but its
  items' guids and media. The ways are these:

      {:feed-guid-fn   (fn [url])                        ; the guid of the feed
       :feed-url-fn    (fn [feed-guid])                  ; the URL of the feed
       :episode-id-fn  (fn [podcast-url media-url guid]) ; the episode id
       :episode-url-fn (fn [[feed-guid item-guid]])}     ; the URL of its media

  Each function gives nil for a feed that isn't in `feeds`. An episode is
  found by its guid, since some servers keep the guid, or else by the URL
  of its media. When neither finds it, the episode gets the name that
  podcast-clj gives an episode that its feed leaves out. URLs match
  whatever their scheme, case and trailing slashes.

  The items of a feed are indexed when a lookup first names the feed, so
  keep the result while the app's feeds stay the same."
  [feeds]
  (let [known   (map impl/indexed feeds)
        url-of  (comp guid/normal-url :url)
        by-url  (into {} (map (juxt url-of identity)) known)
        by-guid (into {} (map (juxt :guid identity)) known)
        at-url  #(by-url (guid/normal-url %))
        index   #(some-> % :index deref)]
    {:feed-guid-fn   #(:guid (at-url %))
     :feed-url-fn    #(:url (by-guid %))
     :episode-id-fn  (fn [podcast media guid]
                       (when-let [{g :guid :as found} (at-url podcast)]
                         (let [{:keys [items by-media]} (index found)]
                           [g (or (when (contains? items guid) guid)
                                  (by-media (guid/normal-url media))
                                  guid
                                  media)])))
     :episode-url-fn (fn [[feed-guid item-guid]]
                       (let [items (:items (index (by-guid feed-guid)))]
                         (when-let [item (get items item-guid)]
                           (or (:url (:rss/enclosure item))
                               (:url (resolve/enclosure item))))))}))

(defn backend
  "A sync backend over the gpodder API of the server at `root`, with the
  `opts` below:

  - :credentials, a :username and a :password, or a function that returns
    them
  - :api, :gpodder by default, :nextcloud, or a map of paths as in
    gpodder.api/apis
  - :device, the device's id on the server, the state's by default
  - :caption, the name that the server shows for the device, podcast-clj
    by default
  - :type, desktop, laptop, mobile, server, tablet, or other by default
  - :name, for the UI and the cursors, the host by default
  - :identities-fn, a function that makes the ways to name shows and
    episodes at each step, e.g. #(identities (vals @feeds))
  - :feed-guid-fn, :feed-url-fn, :episode-id-fn and :episode-url-fn, each
    way on its own, in place of :identities-fn
  - :page-size, :max-pages, :batch-size and :max-pending, over
    default-limits
  - :timeout-seconds, how long a request can take
  - :user-agent, as http/send! takes it
  - :send, a function that sends a request map, in place of client/send!

  A pull gives the subscription changes and episode actions since its
  cursors, as a partial state. A push sends what the devices of
  podcast-clj wrote since the last push, and doc/sync.md tells how each
  maps.

  On gpodder.net, each device has its own subscriptions, so link the
  devices of the account there, or give :device the id of the device of
  another app."
  ([root]
   (backend root {}))
  ([root {:keys [name device] :or {name (url/host root)} :as opts}]
   {:pre [(map? opts)]}
   (let [session     (atom nil)
         opts        (merge {:api :gpodder :caption "podcast-clj" :type "other"}
                            default-limits
                            opts
                            {:name name})
         with-device #(assoc opts :device (or device (:device %)))]
     {:name name
      :pull (fn [state cursor at]
              (let [opts (with-device state)]
                (impl/pull! (impl/caller root opts)
                            opts session state cursor at)))
      :push (fn [state cursor _]
              (let [opts (with-device state)]
                (impl/push! (impl/caller root opts)
                            opts session state cursor)))})))

#?(:clj
   (comment
     ;; TODO: try the backend against a live gpodder.net and a Nextcloud
     (def gpodder-net
       (backend "https://gpodder.net"
                {:credentials {:username "…" :password "…"}}))
     #_.))
