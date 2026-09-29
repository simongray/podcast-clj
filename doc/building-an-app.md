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
- the parsed OPML lists that the listener follows, by URL, in the same way
- the search index of `app.search`, if the app searches
- the sync cursors that `sync!` returns, if the app syncs

All of these values are EDN. The app decides where to store them.

The examples use these aliases:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.analytics :as analytics]
         '[dk.simongray.podcast.analytics.op3 :as op3]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.search :as search]
         '[dk.simongray.podcast.app.sync :as sync]
         '[dk.simongray.podcast.app.sync.encrypted :as encrypted]
         '[dk.simongray.podcast.app.sync.file :as file]
         '[dk.simongray.podcast.app.sync.gpodder :as gpodder]
         '[dk.simongray.podcast.app.sync.store :as store]
         '[dk.simongray.podcast.app.sync.webdav :as webdav]
         '[dk.simongray.podcast.bytes :as bytes]
         '[dk.simongray.podcast.check :as check]
         '[dk.simongray.podcast.comments :as comments]
         '[dk.simongray.podcast.comments.atproto :as atproto]
         '[dk.simongray.podcast.directory :as directory]
         '[dk.simongray.podcast.directory.podcastindex :as podcastindex]
         '[dk.simongray.podcast.feed :as feed]
         '[dk.simongray.podcast.format.chapters :as chapters]
         '[dk.simongray.podcast.format.html :as html]
         '[dk.simongray.podcast.format.media :as media]
         '[dk.simongray.podcast.format.opml :as opml]
         '[dk.simongray.podcast.format.transcript :as transcript]
         '[dk.simongray.podcast.http :as http]
         '[dk.simongray.podcast.podping :as podping]
         '[dk.simongray.podcast.resolve :as resolve]
         '[dk.simongray.podcast.value :as value]
         '[dk.simongray.podcast.value.lnaddress :as lnaddress]
         '[dk.simongray.podcast.value.metadata :as metadata])

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
(def r (podcast/fetch! url {:max-pages 20}))  ; {:status 200 :feed … :etag … :last-modified … :url …}
(def show (:feed r))
(def guid (feed/feed-guid show url))

(def device-id (str (random-uuid)))  ; make it once per installation, and keep it

(def state
  (-> (player/state device-id)
      (player/identify {:name "Phone"} now)
      (player/subscribe guid {:url url :title (resolve/title show)} now)
      (player/configure guid {:auto-queue? true :skip-intro 20 :skip-outro 45} now)))

(def feeds {guid {:url url :feed show :etag (:etag r) :last-modified (:last-modified r)}})
```

`feed/feed-guid` gives the identity of the feed: its `podcast:guid`, or the
guid that the specification derives from the URL. All ids in the player
state use this guid. `(:url r)` is the final URL after redirects.

Some feeds come in pages, and the first page holds only the newest
episodes. `:max-pages` makes `fetch!` read the older pages too, and join
them into one feed. Use it on the first fetch. Later fetches need only the
first page.

A listener can give the address of the website of a show, not of its feed.
`html/feed-links` finds the feeds that the page names:

```clojure
(html/feed-links (:body (http/fetch! site-url)) site-url)
;; => ({:href "https://example.com/feed.xml" :type "application/rss+xml" :title "The show"})
```

A private feed can need a user name and a password, or a token. Give them
to `fetch!` as `:credentials`, such as `{:username "…" :password "…"}`.
`fetch!` does not send them on when a redirect leads to another host. A
private feed with a token in its URL needs no credentials.

An address can also hold the user name and the password, as in
`https://user:pass@host/feed.xml`. `fetch!` then sends them as the
credentials, and the URLs in its result do not hold them. Do not keep the
password in the address that the app stores. The player state syncs to the
other devices and to the stores, and an address goes into OPML too.
`http/split-credentials` gives the address and the credentials apart. Keep
the password in the keychain of the platform.

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

The server can also say when to ask again, with `Retry-After` or
`Cache-Control`. The result then has `:not-before`. Give it to
`next-check`, which then answers no earlier. `:not-before` is at most an
hour ahead, because a host can publish an episode before the time that its
`Cache-Control` names.

```clojure
(let [{:keys [etag last-modified]} (feeds guid)
      r2 (podcast/fetch! url {:etag etag :last-modified last-modified})]
  (resolve/next-check show now {:not-before (:not-before r2)})  ; => #inst "…", or nil for a complete feed
  (when-let [fresh (:feed r2)]  ; absent on a 304
    (let [{:keys [state added]} (player/refresh state show fresh url now)]
      (podcast/report fresh)   ; what podcast-clj had to repair or normalize
      (:new-feed-url r2)       ; if present, change the URL of the subscription
      (:permanent-url r2)      ; if present, the feed moved for good: change the URL too
      added)))                 ; the ids of the new episodes, oldest first
```

A permanent redirect, 301 or 308, moves a feed for good, and
`:permanent-url` gives the new URL. A temporary redirect does not change
the URL of the subscription.

If the subscription has `:auto-queue?` set, `player/refresh` adds the new
episodes of the feed to the end of the queue, oldest first. If it has
`:inbox?` set instead, they go to the inbox. On the first fetch there is no
earlier parse, and no episode is new: the episodes in the feed are its back
catalogue. Another device can find a new episode first. If the listener
played that episode or took it out of the queue or the inbox, `refresh`
does not bring it back.

Podping tells which feeds changed. Fetch these feeds at once, and keep the
schedule for the other feeds. Keep the last block that you read and the list
of writers. Every few seconds, ask for the blocks after that block:

```clojure
(def polled (podping/poll! nil))                                         ; start at the head of the chain
(def polled (podping/poll! (:block polled) {:writers (:writers polled)}))  ; the pings since then
(podping/affected (:pings polled) (map :url (vals (player/subscriptions state))))
;; => ("https://feeds.podcastindex.org/pc20.xml")                         ; fetch these now
```


## Follow lists of shows

Someone can keep a list of shows as an OPML file on the web, such as the
shows of a network or the picks of a critic. A listener can follow such a
list. The app then fetches the list from time to time, as it fetches the
feeds, and the new shows in the list become subscriptions.

```clojure
(def list-url "https://example.org/picks.opml")
(def state (player/follow-list state list-url {:title "Picks" :folder "Picks"} now))  ; syncs like the rest

(let [{:keys [etag parse]} (lists list-url)  ; your own store of the last fetch of each list
      r (http/fetch! list-url {:etag etag})]
  (when (= 200 (:status r))
    (let [{:keys [state added left]} (player/refresh-list state list-url parse (opml/parse (:body r)) now)]
      added    ; the shows that joined the list, now subscriptions in its folder
      left)))  ; the shows that left the list: they stay subscriptions, so ask the listener
```

`refresh-list` subscribes to a show that joins the list, unless the
listener subscribed to it or left it before. A show that leaves the list
stays a subscription, because the listener can like it. `player/to-opml`
writes the lists that the listener follows as inclusions, the way that
OPML 2.0 includes one list in another, and `player/from-opml` follows them
again.


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

An episode can have chapters in four places: a linked chapters file,
Podlove chapters in the feed, the media file itself, or timestamps in the
show notes. Try them in this order. In these examples, `fetch-body` is your
own HTTP function.

```clojure
(def doc (or (some-> (resolve/chapters-url item) fetch-body chapters/parse-json)
             (resolve/embedded-chapters item)
             (some-> (resolve/enclosure item) :url media/read! :chapters)
             (resolve/inferred-chapters item)))
(chapters/at doc 754)  ; the chapter at 12:34
```

The media file also gives its own duration, and it can hold artwork.
`media/read!` reads them with range requests: most often one or two, and a
few hundred kilobytes. A host that inserts ads can send a different file to
each request. So the duration is that of the file that came, and it can
differ from the duration in the feed. In ClojureScript, `media/read!`
returns a promise.

```clojure
(def file (media/read! (:url (resolve/enclosure item))))
(:duration file)          ; => 3712.4, in seconds
(map :id (:report file))  ; => (:mp3/estimated-duration)
(let [{:keys [type data]} (:artwork file)]
  [:img {:src (bytes/data-url type data)}])
```

An alternate enclosure can give the hash of its file in
`podcast:integrity`. `resolve/enclosure` keeps it under `:integrity`, and
`media/verify!` checks the file against it:

```clojure
(let [{:keys [url integrity]} (resolve/enclosure item {:types ["video/mp4"]})]
  (when integrity
    (media/verify! url integrity)))  ; => {:valid? true :algorithm "sha384"}
```

`resolve/transcript` selects the transcript that suits the listener: by
language first, then by timings, then by type. Fetch it, and parse it with
its type:

```clojure
(let [{:keys [url type]} (resolve/transcript show item {:languages ["da" "en"]})]
  (transcript/at (transcript/parse (fetch-body url) type) 754))
```

A show can recommend other shows in its podroll, and name the feed of its
publisher. `resolve/podroll` and `resolve/publisher` give these as remote
items. `podcastindex/remote-item!` finds the feed of a remote item in the
Podcast Index, by its feed guid. The publisher feed lists the shows of the
publisher as remote items too, and a list feed lists episodes or songs of
other feeds in the same way. `resolve/remote-items` gives them. Show the
other shows of a publisher only if its feed lists this show, as the
specification asks. `resolve/social-interacts` tells where the comments on
an episode are, or that the podcaster wants no public comments. [Show the
comments on an episode](#show-the-comments-on-an-episode) tells how to read
them.

```clojure
(resolve/podroll show)  ; => ({:feed-guid "917393e3-…" :feed-url "https://…" :title "…"} …)
(podcastindex/remote-item! {:key "…" :secret "…"} (first (resolve/podroll show)))  ; => {:feed {…}}

(def label (:feed (podcast/fetch! (:feed-url (resolve/publisher show)))))
(resolve/remote-items label)  ; => ({:feed-guid "…" :feed-url "https://…" :medium "podcast"} …)
(some #(= guid (:feed-guid %)) (resolve/remote-items label))  ; true when the publisher confirms the show

(resolve/social-interacts show item)  ; => ({:protocol "activitypub" :uri "https://…" :priority 1} …)
(resolve/soundbites item)             ; => ({:start-time 73 :duration 60 :title "…"} …), parts for a preview
(resolve/ai-content? show item)       ; => true, false, or nil when the feed does not tell
```


## Show the comments on an episode

A podcast feed can name a thread of comments about an episode, with
`podcast:socialInteract`. The thread is on ActivityPub, as on Mastodon, or
on the AT Protocol, as on Bluesky. For an episode, `comments/thread` picks
the first thread, by priority, that podcast-clj can read. Fetch the
thread, and show its tree. For a thread on the AT Protocol, also give the
URL of an AppView. The AppView gives the replies to a post.

```clojure
(def opts {:appview "https://public.api.bsky.app"})

(def thread (some-> (comments/thread show item opts) (comments/fetch! opts)))
(comments/tree thread)  ; => {:content "<p>…</p>" :author {:name "…" :handle "@…"} :replies [{…} …]}
(:report thread)        ; => [{:id :comments/withheld :count 1 :level :info …}]
```

If there is no thread, show no comments. `comments/thread` gives nil when
the podcaster wants no public comments.

The content of a comment is sanitized HTML with absolute links. Render it
with `html/hiccup`. If a comment has a `:summary` or is `:sensitive?`, show
it behind a warning. An attachment gives its `:aspect-ratio` for the
layout. The `:poll` of a comment gives the choices of a poll. On Bluesky,
the author of the root post can hide replies. `tree` leaves out the hidden
replies. To show them, give `tree` the options `{:hidden? true}`.

The servers of a thread choose the URLs that `fetch!` fetches. So `fetch!`
sends nothing to the network of your app, as
[Requests that strangers choose](architecture.md#requests-that-strangers-choose)
tells. If your app must reach its own network, give `fetch!` the `:send`
`http/call!`.

Keep the thread with the episode. `fetch!` reads a thread as far as its
limits allow, and the report tells when it stopped early. By default, it
reads for one minute at most. To show comments sooner, give it
`:max-seconds 5`. Show what it read, then fetch the kept thread again to
read more. To see new comments, fetch a new thread from `comments/thread`.

The AT Protocol gives no web page for a post. Link such a post to a web app
of your choice. Build the link from the DID of the author and the record
key of the post. `atproto/at-uri` gives both.

AppViews that answer without an account, as of September 2026:

| AppView | URL |
|---|---|
| Bluesky | `https://public.api.bsky.app` |
| Blacksky | `https://api.blacksky.community` |


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

The settings of the show above have `:skip-outro`, so `step` ends each of
its episodes 45 seconds before the end. A `:progress` or a `:skip` that
moves the position into the outro gives `[:stop id]`, then `[:played id]`
if the episode was not played before, and then the effects of
`[:ended id]`. `player/outro-start` gives the position where the outro
starts.

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


## Organize the shows and episodes

The player state also holds how the listener organizes the shows and the
episodes. Each change syncs to the other devices, as the queue does.

- A folder is a setting of a subscription. `player/folders` groups the
  shows by folder, and `player/to-opml` writes the folders into the OPML.
- Tags are the listener's own categories. A show is in one folder at
  most, but it can have many tags. `player/to-opml` writes them as the
  categories of the outline, a part of OPML that most apps do not read.
- The inbox holds the new episodes of a show whose settings have
  `:inbox?`. `player/refresh` puts them there. An episode leaves the inbox
  when the listener queues it, plays it or dismisses it.
- The listener can star an episode, and bookmark a position in it.
- A saved filter keeps the criteria of `search/episodes` under a name.
- A playlist is a list of episodes beside the queue, in the order the
  listener gives. Play it by queueing its episodes. `player/to-list-feed`
  writes it as a list feed of the podcast namespace, which Podcasting 2.0
  apps can play, and `player/from-list-feed` reads one.
- `player/step` counts the seconds that the listener hears of each show,
  month by month, for statistics and a review of the year.

```clojure
(def state (-> state
               (player/configure guid {:folder "News" :auto-queue? false :inbox? true} now)
               (player/tag guid "Daily" now)
               (player/star id now)
               (player/bookmark id 754 "The interview starts" now)
               (player/save-filter "Starred" {:where {:starred? true :status :unplayed} :order :newest} now)))

(player/folders state)       ; => {"News" [guid …] nil […]}, nil for the shows in no folder
(player/tagged state)        ; => {"Daily" [guid …] …}
(player/inbox state)         ; => [[feed-guid item-guid] …], the latest to arrive first
(player/dismiss state id now)
(player/starred state)       ; => [id …], the latest starred first
(player/bookmarks state id)  ; => [{:id id :position 754 :title "The interview starts" …}]
(player/listened state)      ; => {guid {"2026-09" 5400.0 …} …}, seconds by show and month

(def state (player/add-to-playlist state "Road trip" id now))
(reduce #(player/enqueue %1 %2 now) state (player/playlist state "Road trip"))  ; play it
(podcast/emit (player/to-list-feed state "Road trip" {:url "https://example.org/road-trip.xml"}))

(let [urls (into {} (for [[_ {:keys [url feed]}] feeds] [feed url]))]  ; for feeds without a podcast:guid
  (search/episodes (keys urls) (assoc (get (player/filters state) "Starred") :player state :url #(urls %))))
```

`player/from-opml` reads the folders, the tags and the dates of
subscribing back. It also reads the OPML of other apps. Some apps put a
description in the text of an outline, so it uses the title of the feed
first. Some apps put the whole list in one folder, so a folder that holds
every show does not become a folder.


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

To keep in step with AntennaPod, Kasts and other apps of the gpodder API,
add a gpodder server as a backend. The API names episodes by URL, so give
the backend a function that looks them up in the parsed feeds of the app:

```clojure
(def feeds (atom {}))  ; the parsed feeds of the app, by URL
(def pods (gpodder/backend "https://gpodder.net" {:credentials creds :identities #(gpodder/identities @feeds)}))
(sync/sync! state [cloud pods] now)  ; the subscriptions and the positions of the other apps, merged
```

A subscription that another app added can name a feed that this app has not
fetched yet. Fetch the feed, and the next step applies the actions that wait
for it. For the GPodder Sync app of a Nextcloud, give `:api :nextcloud`.
To let such apps sync with your own Clojure server, mount
`dk.simongray.podcast.app.sync.gpodder.server/handler`. The inspector does
this at `/api/2/`.


## Pay value for value

`resolve/value` gives the value block of an episode. `value/recipients-at`
gives the recipients at a position in the episode, and `value/payments`
divides an amount between them. The wallet of the app makes each payment.
podcast-clj writes what goes with a payment, in one map for each way to
pay.

```clojure
(def block (resolve/value show item))
(value/payments (:recipients (value/recipients-at block 754)) 1000)  ; 1000 sats, divided at 12:34

(def payment {:action :boost :sats 1000 :position 754 :message "Great episode"
              :feed-guid guid :item-guid (feed/item-guid item) :app-name "My app"})
```

Pay a recipient of type `node` with keysend. Give the wallet the custom
records of `value/records`: the bLIP-10 record, and the custom key and
value of the recipient.

```clojure
(value/records (assoc payment :sats 50) recipient)  ; => {7629169 "{\"action\":\"boost\",…}", 696969 "22"}
```

Pay a recipient of type `lnaddress` with an invoice. `lnaddress/invoice!`
asks the domain of the address for the invoice, and checks its amount.
Put the metadata of the payment in the comment. Serve the header of
`metadata/header` at a URL of your app, and name that URL in the comment.

```clojure
(lnaddress/invoice! "ann@getalby.com" 50000
                    {:comment #(metadata/payment-comment payment "https://app.example/payments/1" (:comment-length %))})
;; => {:invoice "lnbc500n1…" :msat 50000 :network :bitcoin}

{"x-rss-payment" (metadata/header payment)}  ; the header of the page at that URL
```

If the domain names a node for keysend, you can pay the address with
keysend instead. `lnaddress/node!` gives the node as the keys of a
recipient of type `node`. Keep the node for a while, as the podcast
namespace asks.

```clojure
(merge recipient (lnaddress/node! (:address recipient)))  ; => {:type "node" :address "03…" :custom-key "696969" …}
```

If an answer holds `:error`, do not pay. An invoice for another amount is
an error of level `:error`, and so is an answer that came over plain http.
The feeds choose the domains that `invoice!` and `node!` ask, so they send
nothing to the network of your app. The page of the metadata is public,
so the name of the sender and the message are public too.


## Read the payments that a show receives

A host reads what the listeners sent with their payments. The node of the
host gives the custom records of a keysend payment. Read the record
7629169 with `value/parse-boostagram`. A payment to a Lightning address
has a comment instead, which the node gives as the memo of the invoice.
If the comment starts with `rss::payment::`, `metadata/parse-comment`
gives the URL of its metadata, and `metadata/fetch!` reads it.

```clojure
(value/parse-boostagram (get custom-records 7629169))  ; the text or the bytes of the record
(some-> (metadata/parse-comment memo) :url metadata/fetch!)
```

The sender writes the record and the metadata, so each is a claim. Put it
next to what the node received with `value/as-received`. It keeps the
amount of the node, and its report names each claim that differs. Remove
duplicates by `:id`, because a sender can name the metadata of another
payment.

```clojure
(value/as-received payment {:msat 25000 :address "ann@getalby.com"})
```

The senders choose the URLs of the metadata, so `fetch!` sends nothing to
the network of your host. Show the texts of a payment as text, as Hiccup
does. They are what the sender wrote, and HTML in them must not run.


## Check the files that a feed links to

A host checks a feed before it publishes, and an app can check a feed that
a podcaster submits. `check/feed!` fetches the files that the feed and its
newest items link to, and tells whether they reach a listener as the feed
says: the media files, the images, the transcripts and the chapters files.
Give it the URL of the feed to check the feed too, and a profile to add
the rules of PSP-1 or Apple Podcasts. It sends a HEAD request for each
file, asks for two bytes of each file but the images, and reads the header
of each image. `:read? true` also reads what the files hold: the format and
duration of each media file, as far as its duration, and each transcript
and chapters file whole.

```clojure
(def result (check/feed! show {:url "https://example.com/feed.xml" :items 10 :profile :apple}))

(podcast/summarize (:report result))
;; => ({:id :check/cors :level :warning :count 10 :message "The server does not let pages of other sites read the file, …"}
;;     {:id :check/ranges :level :error :count 3 :message "The server does not answer a request for a byte range …"} …)

(first (filter #(= :media (:kind %)) (:files result)))
;; => {:kind :media :url "https://…" :path [:rss/items 0 :rss/enclosure] :declared {:type "audio/mpeg" :length 43200000}
;;     :facts {:head {:status 200 …} :get {:status 206 :range {:start 0 :end 1 :total 43200000} …}}}

(check/report (:files result) :psp-1)  ; the same check by another profile, with no request sent again
```

Each diagnostic has the `:url` of the file and the `:path` of the element
that names it. `check/verdicts` gives the diagnostics of facts that the app
gathered itself. `http/probe!` gives the same facts of any file, such as
the size of an episode before the app downloads it:

```clojure
(get-in (http/probe! (:url (resolve/enclosure item))) [:get :range :total])  ; => 43200000
```


## Count the downloads of a show

A host counts the downloads of its episodes from the logs of its server.
Turn each line of a log into a request map, with the fields that the log
has:

```clojure
{:time       #inst "2026-09-26T08:15:02Z"
 :ip         "203.0.113.5"
 :user-agent "Overcast/3.0 (+http://overcast.fm/; iOS podcast app)"
 :method     :get
 :url        "https://cdn.example.com/episode-54.mp3"
 :status     206
 :range      "bytes=0-"   ; the Range header of the request
 :bytes      1048576}     ; the bytes that the server sent
```

Get a copy of the open list of user agents from
<https://github.com/opawg/user-agents-v2>, and read its files with
`analytics/user-agents`. Give `downloads` the bytes that each file needs:
the header and one minute of the content, which `analytics/threshold`
calculates. Then count the downloads, the listeners and the apps:

```clojure
(def agents
  (analytics/user-agents (into {} (for [file [:bots :apps :libraries :browsers :devices :referrers]]
                                    [file (slurp (str "user-agents-v2/src/" (name file) ".json"))]))))

(def thresholds
  {"https://cdn.example.com/episode-54.mp3" (analytics/threshold {:size 43200000 :header 1253 :duration 2700})})

(def result (analytics/downloads requests {:user-agents agents :threshold thresholds :exclude? data-center?}))

(:dropped result)                                       ; the requests that do not count, by reason
(frequencies (map :url (:downloads result)))            ; the downloads of each file
(count (distinct (map :listener (:downloads result))))  ; the listeners
(frequencies (map :agent-name (:downloads result)))     ; the apps
```

`data-center?` is a predicate of a request. Build it from your lists of the
addresses of data centers, VPNs and known bots, as the guidelines of the
IAB ask. The guidelines also ask a host to leave out the addresses with too
many downloads. Remove them from the downloads by a rule that you state. A
window is a calendar day in UTC. For the 24 hours from the first request of
a listener, give `:window :rolling`.


## Measure the downloads with OP3

OP3 counts the downloads of a show when its feed puts the prefix of OP3 in
front of each enclosure. `op3/with-prefix` puts the prefix on the
enclosures of a feed:

```clojure
(podcast/emit (op3/with-prefix feed))
;; … <enclosure url="https://op3.dev/e,pg=917393e3-…/cdn.example.com/episode-54.mp3" …/> …
```

The API of OP3 needs the token of an API key. Get a key at
<https://op3.dev/api/keys>. Then ask for the show, its counts and its
downloads:

```clojure
(def show (op3/call! token :show {:show (:podcast/guid feed) :episodes? true}))
(:stats-page-url show)  ; the public page of the statistics of the show

(op3/call! token :episode-download-counts {:show (:show-uuid show)})
;; => {:episodes [{:item-guid "PC20-272" :downloads1 998 :downloads-all 1168 …} …] …}

(def month (op3/downloads! token {:show (:show-uuid show) :start "2026-09" :end "2026-10"}))
(frequencies (map :agent-name (:downloads month)))
```

A download of OP3 has the keys of a download of `analytics/downloads` where
OP3 tells the same fact, so the same code counts both. When the answer has
a `:continuation-token`, OP3 has more downloads. Give the token to
`downloads!` in the parameters to read on.


## Use another service

podcast-clj knows the addresses and the rules of some services. Each is a
default, and an option replaces it. [Services and their
defaults](services.md) lists them all.

To search a directory at another address, give its entry in place of its
name. Start from the entry of a directory with the same API:

```clojure
(def mirror (assoc (:fyyd directory/sources) :id :mirror :root "https://fyyd.example.org/0.2/"))

(directory/search! "podcasting 2.0" {:sources [:apple mirror]})
;; => {:results [{:rss/title "Podcasting 2.0" :directory/sources {:apple "1584274529" :mirror 61003} …} …]}
```

To use another instance of a service, give its address:

```clojure
(podcastindex/call! credentials :podcasts/by-guid {:guid guid} {:base-url "https://index.example.org/api/1.0/"})
(podping/notify-request token feed-url {:gateway "https://ping.example.org/"})
(op3/with-prefix feed {:origin "https://op3.example.org"})
```

To sync with a server whose gpodder API has other paths, give the paths as
`:api`. `{user}` is the user name, and `{device}` is the id of the device:

```clojure
(gpodder/backend "https://sync.example.org"
                 {:credentials {:username "simon" :password password}
                  :api         {:subscriptions        "/sync/{user}/shows"
                                :update-subscriptions "/sync/{user}/shows"
                                :episodes             "/sync/{user}/actions"
                                :update-episodes      "/sync/{user}/actions"}
                  :page-size   500})
```

To check a feed against the rules of a directory that changed its rules,
give the rules as the profile:

```clojure
(def wide (assoc-in (:apple check/profiles) [:artwork :sizes] [1400 4000]))

(check/feed! feed {:url url :profile wide})
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
- For a host, keep the logs of the server and the lists that the count of
  downloads uses: user agents, data centers and bots.
