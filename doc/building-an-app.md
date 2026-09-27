# How to build a podcast app

This guide shows the functions that a podcast app calls, in the order that a
listener meets them. It assumes that you know Clojure and the basics of
podcast feeds. The inspector in `web/` does the same steps, and you can read
its code as a working example.


## Before you start

An app keeps these values between runs:

- the player state of `app.player`: the subscriptions, the queue, and the
  progress in each episode
- the parsed feeds, by feed guid, each with its URL and the validators of
  the last fetch
- the search index of `app.search`, if the app searches
- the sync cursors that `sync!` returns, if the app syncs

All of these values are EDN. The app decides where to store them.

The examples use these aliases:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.search :as search]
         '[dk.simongray.podcast.app.sync :as sync]
         '[dk.simongray.podcast.app.sync.encrypted :as encrypted]
         '[dk.simongray.podcast.app.sync.file :as file]
         '[dk.simongray.podcast.app.sync.store :as store]
         '[dk.simongray.podcast.app.sync.webdav :as webdav]
         '[dk.simongray.podcast.directory :as directory]
         '[dk.simongray.podcast.feed :as feed]
         '[dk.simongray.podcast.format.chapters :as chapters]
         '[dk.simongray.podcast.format.html :as html]
         '[dk.simongray.podcast.format.opml :as opml]
         '[dk.simongray.podcast.format.transcript :as transcript]
         '[dk.simongray.podcast.podping :as podping]
         '[dk.simongray.podcast.resolve :as resolve]
         '[dk.simongray.podcast.value :as value])

(def now (java.util.Date.))  ; each write to the player state takes the time
```

The examples run on the JVM. In ClojureScript, the functions that send
requests return promises, and some functions exist only on the JVM. See
[Namespaces](namespaces.md).


## Find a show

On the JVM, `directory/search!` searches Apple Podcasts, fyyd and
gpodder.net at the same time. With Podcast Index credentials, it also
searches the Podcast Index. It combines the results into one entry per show.

```clojure
(def found (:results (directory/search! "podcasting 2.0" {:limit 5})))
(map (juxt resolve/title resolve/author :directory/sources) found)
;; => (["Podcasting 2.0" "Podcast Index LLC" {:apple "1584274529" :fyyd 61003 :gpodder "https://…"}] …)

(def url (resolve/self-url (first found)))  ; the URL of the feed
```

In a browser, build the request with `directory/request`, send it, and read
the answer with `directory/response`. The CORS policy of each directory
decides if a web page can send the request.


## Subscribe to a show

Fetch the feed, keep the validators for the next fetch, and add the
subscription to the player state:

```clojure
(def r (podcast/fetch! url))  ; {:status 200 :feed … :etag … :last-modified … :url …}
(def show (:feed r))
(def guid (feed/feed-guid show url))

(def device-id (str (random-uuid)))  ; make it once per installation, and keep it

(def state
  (-> (player/state device-id)
      (player/identify {:name "Phone"} now)
      (player/subscribe guid {:url url :title (resolve/title show)} now)
      (player/configure guid {:auto-queue? true :skip-intro 20} now)))

(def feeds {guid {:url url :feed show :etag (:etag r) :last-modified (:last-modified r)}})
```

`feed/feed-guid` gives the identity of the feed: its `podcast:guid`, or the
guid that the specification derives from the URL. All ids in the player
state use this guid. `(:url r)` is the final URL after redirects.

In a browser, `fetch!` does not exist, and few feed servers accept requests
from other origins. Get the XML through your own server, and give the string
to `podcast/parse`.


## Keep the feeds current

`resolve/next-check` tells when to fetch a feed again. It uses the
update-frequency rule of the feed, its `ttl`, its syndication period, or the
interval between its recent episodes. It ignores episodes dated in the
future, and it never waits more than a year. Send the validators of the
last fetch with the next fetch. If the feed did not change, the server
answers 304 and the result has no `:feed`.

```clojure
(resolve/next-check show now)  ; => #inst "…", or nil for a complete feed

(let [{:keys [etag last-modified]} (feeds guid)
      r2 (podcast/fetch! url {:etag etag :last-modified last-modified})]
  (when-let [fresh (:feed r2)]  ; absent on a 304
    (let [{:keys [state added]} (player/refresh state show fresh url now)]
      (podcast/report fresh)   ; what podcast-clj had to repair or normalize
      (:new-feed-url r2)       ; if present, change the URL of the subscription
      added)))                 ; the ids of the new episodes, oldest first
```

If the subscription has `:auto-queue?` set, `player/refresh` adds the new
episodes of the feed to the end of the queue, oldest first. On the first
fetch there is no earlier parse, and no episode is new: the episodes in the
feed are its back catalogue.

Podping tells which feeds changed. Fetch these feeds at once, and keep the
schedule for the other feeds. Keep the last block that you read and the list
of writers. Every few seconds, ask for the blocks after that block:

```clojure
(def polled (podping/poll! nil))                                         ; start at the head of the chain
(def polled (podping/poll! (:block polled) {:writers (:writers polled)}))  ; the pings since then
(podping/affected (:pings polled) (map :url (vals (player/subscriptions state))))
;; => ("https://feeds.podcastindex.org/pc20.xml")                         ; fetch these now
```


## Build the show and episode pages

`resolve/properties` gives one value for each property of a show or of an
episode. Where several vocabularies have the same property, a rule in the
docstring of the accessor selects the value. `search/episodes` selects and
sorts the items by criteria that are data. The criteria can use the progress
of the listener.

```clojure
(resolve/properties show)
;; => {:title "…" :author "…" :artwork "https://…" :categories [["Technology"]] :medium "podcast" …}

(search/episodes show {:where {:status :unplayed} :order :listening :player state :url url})
(search/episodes show {:where {:season 2 :type "full"} :order :newest})

(def item (first (resolve/episodes show)))
(resolve/properties show item)
;; => {:title "…" :published #inst "…" :duration 3600 :numbering {:type "full" :season 2 :episode 3}
;;     :enclosure {:url "…" :type "audio/mpeg"} :transcript {:url "…" :type "text/vtt"} :people [...] …}
(merge item (resolve/properties show item))  ; the raw fields and the resolved values together

[:div.notes (html/hiccup (resolve/description item))]  ; the show notes, sanitized, as Hiccup
```

An episode can have chapters in three places: a linked chapters file,
Podlove chapters in the feed, or timestamps in the show notes. Try them in
this order. In these examples, `fetch-body` is your own HTTP function.

```clojure
(def doc (or (some-> (resolve/chapters-url item) fetch-body chapters/parse-json)
             (resolve/embedded-chapters item)
             (resolve/inferred-chapters item)))
(chapters/at doc 754)  ; the chapter at 12:34
```

`resolve/transcript` selects the transcript that suits the listener: by
language first, then by timings, then by type. Fetch it, and parse it with
its type:

```clojure
(let [{:keys [url type]} (resolve/transcript show item {:languages ["da" "en"]})]
  (transcript/at (transcript/parse (fetch-body url) type) 754))
```


## Play episodes

The player state holds the queue and the progress. The app holds the media
element. Send each event of the media element to `player/step`. `step`
returns the new state and the effects that the app must do: seek in the
media, or play the next episode.

```clojure
(def id (player/episode-id show url item))
(def state (player/enqueue state id now))

(player/resume-position state guid id)  ; where the listener stopped, or after the intro
(resolve/enclosure item {:types ["audio/opus" "audio/mpeg"] :max-bitrate 64000})

(def playing (:state (player/step state [:progress id 1234 3600] now)))  ; on each timeupdate event
(player/step playing [:skip id :forward] now)  ; => {:state … :effects [[:seek id 1264]]}
(player/step playing [:ended id] now)          ; => {:state … :effects [[:play next-id]]}
```

`[:play next-id]` comes only when another episode is in the queue.

The browser player of the inspector is a working example, in
`web/inspector/player.cljs` and `web/inspector/player/view.cljs`.


## Search the episodes

The search index is a value that you build from the feeds. When a feed
changes, build the index again, or use `search/add` and `search/remove`. To
make a transcript searchable, give it to `search/item-document`. In a
browser with little memory, index fewer fields.

```clojure
(def idx (search/index (for [[_ {:keys [url feed]}] feeds
                             item (:rss/items feed)]
                         (search/item-document feed item {:url url}))))

(search/query idx "value for va" {:limit 10})  ; all words, the last one as a prefix, best first
;; => [{:id [feed-guid item-guid] :score 7.1 :stored {:title "…" :show "…" :published #inst "…"}} …]
(search/snippet (html/text (resolve/description item)) "value for va")
```


## Sync the devices of a listener

Each device writes its whole state as one document to a store, and reads the
documents of the other devices. `sync!` does one step: pull, merge and push.
[Syncing](sync.md) explains why this is safe.

1. Find the place for the documents: a WebDAV collection, or a folder that a
   sync client mirrors. [Sync stores and documents](stores.md) gives the
   URLs and paths of some providers.
2. Test the store with `store/check!`, for example from a button in the
   settings screen.
3. Make a backend for each place. `webdav/backend` and `file/backend` make
   the store and the backend in one call.
4. Call `sync!` when the app starts, when it goes to the background, and
   after a series of changes. Merge the `:state` of the result into the
   state the app has now, which can hold changes from during the step. Then
   compact it, because the merge brings back the tombstones that the step
   removed. Keep the `:ctx` of the result for the next call.
5. Ask `sync/next-at` when to call `sync!` again, and set a timer.

```clojure
(def creds {:username "simon" :password "app-password"})
(def cloud-root "https://cloud.example.org/remote.php/dav/files/simon/podcasts/")
(store/check! (webdav/store cloud-root {:credentials creds}))  ; => {:ok? true :checks [{:id :store/reachable …} …]}

(def cloud  (webdav/backend cloud-root {:credentials creds :opml? true :app "my-app"}))
(def folder (file/backend "/Users/simon/Library/Mobile Documents/com~apple~CloudDocs/Podcasts" {:app "my-app"}))

(def synced (sync/sync! state [cloud folder] now))  ; => {:state … :ctx {…} :report [] :failed {…}}
(def state (-> state (player/merge (:state synced)) (player/compact now)))  ; keeps what the listener did during the step
(sync/sync! state [cloud folder] (java.util.Date.) {:ctx (:ctx synced)})  ; reads only what changed
(sync/next-at {:at now :changed? true :failures 0} (java.util.Date.))     ; when to call sync! again
```

To keep the documents encrypted on the server, use `encrypted/backend`
around the store. The server then holds only ciphertext. With `:except`, the
subscription list stays readable for other apps.

```clojure
(def sealed (encrypted/backend (webdav/store cloud-root {:credentials creds})
                               {:passphrase (fn [] (from-the-keychain))
                                :opml?      true
                                :except     #{store/opml-name}}))
```

Each step also removes the tombstones that all other devices merged, if they
are older than 30 days. A device that stopped syncing blocks this removal.
`player/lagging` shows such devices, and `player/retire` removes a device
from the sync.

```clojure
(player/devices state)                   ; the devices of the listener, as they described themselves
(player/lagging state)                   ; => {old-tablet-id #inst "…"}: a device that blocks compaction
(player/retire state lost-phone-id now)  ; the next syncs delete its document

(opml/emit (player/to-opml state))       ; the subscriptions as OPML, for other apps
(player/from-opml state (opml/parse opml-text) now)
```

To serve a store from your own Clojure server, for example to a browser
client, mount `dk.simongray.podcast.app.sync.webdav.server/handler`. The
inspector does this at `/dav/`. To sync through a service that podcast-clj
has no store for, write a store. [Sync stores and documents](stores.md)
gives the contract: a map of two to four functions.


## Pay value for value

`resolve/value` gives the value block of an episode. `value/recipients-at`
gives the recipients at a position in the episode, and `value/payments`
divides an amount between them. `value/boostagram` writes the record that
goes with a payment. The app makes the payment itself.

```clojure
(def block (resolve/value show item))
(value/payments (:recipients (value/recipients-at block 754)) 1000)  ; 1000 sats, divided at 12:34
(value/boostagram {:sats 1000 :position 754 :message "Great episode"
                   :feed-guid guid :item-guid (feed/item-guid item)})
```


## What the app does itself

podcast-clj does not do these tasks. The app does them:

- Store the values that [Before you start](#before-you-start) lists.
- Fetch feeds and query directories in a browser, where each server decides
  what a web page can fetch.
- Play the media, through a media element that the effects of `player/step`
  control.
- Download episodes, show notifications and make payments.
- Draw the user interface.
