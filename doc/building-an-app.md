# How to build a podcast app

This guide shows how to build each part of a podcast app, from finding a
show to syncing devices. Each section starts with the one call that's
usually enough, and then shows the lower-level functions for when you need
more control. [Quickstart](quickstart.md) runs the first steps at a REPL,
and the inspector in `web/` is a working example.


## Before you start

The subscriptions, feeds, queue and play progress of a podcast app are all
kept in one map, the library, which fits in an atom:

```clojure
(def lib
  (atom (library/make device-id {:name   "Phone"
                                 :config {:languages ["da" "en"]}})))

(swap! lib library/subscribe url (podcast/fetch! url))
(swap! lib library/merge (library/refresh! @lib))
(swap! lib library/merge (library/sync! @lib backends))
```

Change it with `swap!`, and put what `refresh!` and `sync!` return into
it with `library/merge`, which keeps what the listener did while they
fetched. In ClojureScript they return a promise:
`(.then (library/refresh! @lib) #(swap! lib library/merge %))`. Make the
`device-id` once for each installation, e.g. with `(str (random-uuid))`,
and keep it.

Each function returns the library along with what it did: `:effects`
after `step`, `:refresh` after `refresh!`, `:sync` after `sync!` and
`:restore` after `restore!`. The
`:config` given to `make` holds the options that the other library
functions use, and the docstring of `app.library` lists them. Keep the
library between runs with `library/save!` and `library/restore!`, as
[Keep the library between runs](#keep-the-library-between-runs) shows.

The parts of the library work with the namespaces they come from, e.g.
every function of `profile`, and of its parts such as `profile.queue`,
takes `(:profile @lib)`. The profile is a CRDT, so that devices can merge
their changes in any order, and where two changed the same thing, the
later change wins. Change it only with these functions, e.g.
`(swap! lib library/update-profile profile.queue/enqueue id)`, and never
with `assoc` or `dissoc`.

Each change is stamped with its time, which the functions of
`app.library` read from the clock, the setting `:now-fn`, so a test can fix
it. For an event that you handle later, e.g. in a background task, give
the time it happened as the option `:at`. Since `update-profile` takes no
options, call the function of `profile` with that time instead, e.g.
`(swap! lib update :profile profile.queue/enqueue id at)`.

The examples call each namespace by the last part of its name, e.g.
`library` for `dk.simongray.podcast.app.library`, or by a longer name
where that part would be unclear, e.g. `feed.guid`, `profile.queue` or
`nostr-comments`.
[The last section](#the-namespaces-of-the-examples) requires them all.
The examples run on the JVM, except those for a browser, and in
ClojureScript the functions that send requests return promises.

Some of the namespaces come from libraries that were spun out of
podcast-clj. It depends on them, so they come with it:

- [wary-fetch](https://github.com/simongray/wary-fetch), HTTP with limits
  and a guard against private addresses, as `http`, `url`, `proxy` and
  `secret`
- [like-minded](https://github.com/simongray/like-minded), the sync
  between devices and the stores of documents it goes through, as
  `store`, `folder`, `webdav`, `webdav-server`, `encrypted` and
  `indexeddb`
- [mind-meld](https://github.com/simongray/mind-meld), the CRDT of the
  profile, as `crdt`
- [drop-in-search](https://github.com/simongray/drop-in-search), full-text
  search, as `search`, `search.queries` and `ciff`
- [html-pieces](https://github.com/simongray/html-pieces), HTML as Hiccup,
  as `html`
- [websub-clj](https://github.com/simongray/websub-clj), WebSub, as
  `websub`


## What you build yourself

- Pick a store for the library, and keep the record of the files on the
  device.
- Play the media, as the effects of `library/step` say.
- Download and delete episode files, as `library/plan` says, when the
  network and the battery allow it.
- Show notifications and make payments.
- Draw the user interface.
- For a host, keep the server logs and the lists of user agents, data
  centres and bots that counting downloads needs.


## Where it runs

- **On the JVM**, everything works. A few functions exist only there,
  e.g. `podping/poll!`, and [Namespaces](namespaces.md) says which.
- **In a browser**, the requests go through a proxy on your server, as the
  next section shows, and the rest runs in the page.
- **In Node**, install a `DOMParser`, e.g. that of `@xmldom/xmldom` as
  `globalThis.DOMParser`. The folder store needs Node 20.16 or 22.3 and
  later, and Nostr comments and wallets need the global `WebSocket` of
  Node 22.
- **On a server of your own**, run the proxy of a web page, the callbacks
  of WebSub, and the sync over WebDAV or the gpodder API if you serve it.
- **In a desktop app on Electron**, the main process is Node and the
  window is a browser, and **in a PWA or a Capacitor app**, the page is a
  browser.

In ClojureScript, add podcast-clj to `deps.edn` and let shadow-cljs read
it with `:deps true`. A browser needs no npm packages, and Node only the
`DOMParser` above.


## Send requests from a web page

Few feed hosts and directories let a page of another site read their
answers, so a page sends its requests through your own server. Mount the
proxy of [wary-fetch](https://github.com/simongray/wary-fetch) there, and
give its sender as `:send`:

```clojure
;; on the server, as a route of Pedestal or any other Ring router
["/proxy" :any (proxy/handler) :route-name ::proxy]

;; in the page, in the config of the library
(library/make device-id {:config {:send (proxy/sender "/proxy")}})
```

Every function that sends requests then works in the page as it does on
the JVM, except the sync over WebDAV and the gpodder API. The proxy
fetches only URLs on the public internet, and refuses to be opened as a
page, but it fetches for anyone who can reach it, so put it behind your
login.

The proxy only sends GET and HEAD, so in a page, the sync over WebDAV or
the gpodder API reaches only a server on your own origin, e.g. that of
`store.webdav.server`, or one that lets your page through CORS. A WebDAV
server must expose the ETag too. Syncing through a folder needs no
server, as [Sync through a folder in a browser](#sync-through-a-folder-in-a-browser)
shows.

A page reaches Nostr relays by itself, so for comments, wrap the `:send`
that `comments/fetch!` has by default: `(proxy/sender "/proxy" {:send
comments/send!})`. The page's cookies and credentials don't go on through
the proxy, so to fetch a private feed through it, give the handler
`{:forward (conj (:forward proxy/defaults) "authorization")}`, unless your
own login uses that header.


## Find a show

Search with `directory/search!`. It asks Apple Podcasts, fyyd and
gpodder.net at the same time, and the Podcast Index too when you give it
credentials, and combines the results into one entry per show:

```clojure
(def found (:results (directory/search! "podcasting 2.0" {:limit 5})))

(map (juxt resolve/title resolve/author :directory/sources) found)
;; => (["Podcasting 2.0" "Podcast Index LLC"
;;      {:apple "1584274529" :fyyd 61003 :gpodder "https://…"}] …)

(def url (resolve/self-url (first found)))
```

For the charts, use `directory/fetch-chart!`, optionally for one category
of Apple Podcasts. A source without a chart for the category, the country
or the language ends up in the `:report`:

```clojure
(def chart
  (directory/fetch-chart! {:category "Technology" :country "dk" :limit 10}))

(map resolve/title (:results chart))
(map (juxt :source :reason) (:report chart))
;; => ([:fyyd "fyyd has no chart by country"]
;;     [:gpodder "gpodder.net has no chart by country"])
```

The Podcast Index has no names for subcategories like Tech News, so give a
category for each source: `{:category {:apple "Tech News" :podcastindex
"Technology,News"}}`.

In a browser, give `search!` the `:send` of the proxy.


## Subscribe to a show

```clojure
(swap! lib library/subscribe url (podcast/fetch! url {:max-pages 20})
       {:auto-queue? true})
```

With `:max-pages`, `fetch!` also reads the older pages of a paged feed,
which is only needed on the first fetch, since the library keeps the
episodes that later fetches no longer bring back. The setting
`:max-pages` does the same for a subscription that `refresh!` fetches for
the first time, e.g. one from another device. The last argument holds the
settings of the subscription, as `profile/configure` takes them, e.g.
`:auto-queue?` puts the show's new episodes in the listener's queue.

A failing network or server doesn't make `fetch!` throw. When it gets no
answer or no feed, the result has an `:error` diagnostic. A status such as
404 is an answer, so it comes back as `:status` without an error. Check
the result before you subscribe, since `library/subscribe` throws the
diagnostic of a result without a feed:

```clojure
(def result (podcast/fetch! url))
(:error result)  ; => nil, or e.g. {:id :fetch/unreachable :reason "…" …}
```

To subscribe without a fetch, e.g. while offline, leave the result out:
`(swap! lib library/subscribe url)`. Then `refresh!` fetches the feed,
and a feed that would take over another subscription goes in its report
instead of throwing.

By default, `fetch!` refuses a URL on your own network, so that a URL that
someone types into a server can't reach it. For a feed on a home server,
give it `{:allow-pred (constantly true)}`.

If the listener gives a show's website, a domain or a link from another app
instead of a feed, find the feeds with `discovery/find-feeds!`:

```clojure
(def candidates (discovery/find-feeds! "freakshow.fm"))

(map (juxt :title :url :found-by) (:feeds candidates))
;; => (["Freak Show" "https://freakshow.fm/feed/mp3" :link]
;;     ["Freak Show" "https://freakshow.fm/feed/opus" :link]
;;     ["Freak Show" "https://freakshow.fm/feed" :link] …)
```

The podcasts come first, so you can subscribe to a single one at once and
let the listener choose among several. Each comes with its parsed `:feed`,
which `library/subscribe` takes in place of the result of `fetch!`. Give
`:known (library/urls @lib)` to put the shows that the listener has first.
When the page names no podcast feed, `:search [:apple]` also asks a
directory for the shows whose website is on that domain. It's off by
default, since it sends the name of the page to the directory.

A private feed can need a user name and password, which `fetch!` takes as
`:credentials`. It doesn't send them on when a redirect leads to another
domain. Keep the password in the platform's keychain, not in the stored
URL, since the subscriptions sync to other devices and go into OPML. To
split a URL like `https://user:pass@host/feed` into the URL and the
credentials, use `url/split-credentials`. Wrap the password with
`secret/hide`, so it prints as `#<secret>`:

```clojure
(def creds {:username "simon" :password (secret/hide (from-keychain))})

(podcast/fetch! url {:credentials creds})
```

In a browser, give `fetch!` the `:send` of the proxy.

Without `app.library`, `feeds/add` keeps the feed along with its ETag and
Last-Modified, and `profile/subscribe` adds the subscription. Every id in
the profile uses the key of a feed: the guid that `feed.guid/feed-guid`
gives it at its first fetch, which stays when the feed later gives itself
another, e.g. after a move. `feeds/add` puts the key on the feed with
`feed.guid/with-key`, so that `feed.guid/episode-id` gives ids by it. A
feed can declare the guid of another subscription and take it over, so
check a new feed with `profile/takeover` first.


## Keep the feeds current

```clojure
(swap! lib library/merge (library/refresh! @lib))

(:refresh @lib)
;; => {:at #inst "…" :added [[feed-guid item-guid] …] :backfilled []
;;     :renamed [] :moved {} :report []
;;     :counts {:checked 12 :unchanged 10 :changed 2 :failed 0}
;;     :seconds 1.4}

(library/next-refresh @lib)  ; when to refresh again
```

This fetches whatever is due: the lists that the listener follows, the
feeds of new subscriptions, and the feeds whose next check has come. The
new episodes go to the queue of a show with `:auto-queue?`, or to the
inbox with `:inbox?`. An episode that the listener already played or
removed on another device doesn't come back. An episode older than the
newest one the library knew isn't new either: it's `:backfilled` rather
than `:added`, so that a host that publishes its back catalogue again
doesn't fill the queue. The setting `:only-newer? false` counts those as
new too.

The `:counts` and `:seconds` say how many feeds were checked and how it
went, e.g. for a status line or a log. Schedule the next refresh at
`library/next-refresh`: when the first feed or list is due, or right away
after a new subscription. After a refresh that found the device offline,
it waits five minutes, the config's `:offline-seconds`.

To announce new episodes, turn on the preference `:notify?`, or the
setting of that name for a show, and ask `library/notifications` after
each refresh. It gives the new episodes that the listener hasn't played,
by show:

```clojure
(swap! lib library/update-profile profile/prefer {:notify? true})

(library/notifications @lib)
;; => [{:feed-guid "917393e3-…" :title "Podcasting 2.0"
;;      :artwork "https://noagendaassets.com/enc/…_pcifeedimage.png"
;;      :episodes [{:id ["917393e3-…" "PC20-274"]
;;                  :title "Episode 274: \"Vibe Cloning\""
;;                  :published #inst "2026-10-09T19:37:54Z"}]}]
```

When a show moves to another host, the new host often gives every episode
a new guid. The library recognises such an episode by its enclosure URL,
or else by its title and a date a day apart at most, and moves its place
in the queue, its marks and its progress to the new id, as `:renamed`
says. A feed that moved for good moves its subscription too, once a feed
of the show answers at the new URL. A feed that failed stays as it was
and waits longer before the next try. So does a feed that comes back
without episodes, as some hosts serve one during an outage, with
`:fetch/empty-feed` in the report. A feed that answers 410, or fails for
about a month, is gone, with `:fetch/gone` in the report, and isn't
checked again until a Podping or a refresh of that feed asks for it.

Many hosts serve only the newest hundred or so episodes, so the library
keeps the ones that a fetch no longer brings back, and `library/episode`,
`library/show` and `library/episodes` mark each with `:retained`: when a
fetch first missed it, and `:gone? true` when the host took it out rather
than leaving it to age out. Show them with the mark, as AntennaPod does,
or hide the gone ones, as Apple Podcasts does. The setting `:max-items`
caps the episodes of a show, dropping the oldest of those that the feed
no longer brings back, but never one that the listener has progress, a
mark or a place in the queue for. These episodes stay on the device, so
another device only has what the host serves.

A refresh while the device has no network changes nothing. Give
`:online? false` when the platform says so, e.g. from
`navigator.onLine`, and nothing is fetched. A refresh in which every fetch
failed before a server answered, at more than one host, counts as offline
too. Either way, no feed or list counts it as a failure, each stays due,
and the report has `:refresh/offline`, so the first refresh after the
network comes back fetches them all. A failed fetch says why in its
`:type`, e.g. `:dk.simongray.wary-fetch/unresolved` for a host whose name
wasn't found.

When to check a feed again depends on its update frequency, its `ttl` or
the interval between its episodes, as `schedule/next-check` reads them,
but it's never more than twelve hours away, so that an early or an extra
episode shows up the same day. The setting `:cadence` changes those
defaults, e.g. `{:max-wait 21600}` for six hours, and `:credentials-of`
gives the credentials of a private feed by its URL, so no password is
stored in the library and none goes to another feed.

The library keeps every feed whole, which takes about twice the size of
its XML in memory. A device that never shows the feeds, such as a server
that only syncs and refreshes, can keep a small part of each with the
setting `:keep-fn feeds/slim`. That part still tells when to check the feed
and which episodes are new.

Podping tells which feeds have changed, so they can be fetched at once.
On the JVM, keep the last block that you read and the writers, and poll
every few seconds:

```clojure
(defn podping-step!
  "Read the pings after the last result `polled`, and refresh the feeds of
  `lib` that they name."
  [lib polled]
  (let [{:keys [block writers]} polled
        polled (podping/poll! block {:writers writers})
        guids  (feeds/affected (:feeds @lib) (:pings polled))]
    (when (seq guids)
      (swap! lib library/merge (library/refresh! @lib {:feed-guids guids})))
    polled))

(def polled (podping/poll!))  ; starts at the head of the chain
```

When no node answers, the result has an `:error` and the block it got to,
so the next poll continues from there. ClojureScript has no `poll!`, but
the nodes answer a web page, so read the blocks with `podping/call!` and
their pings with `podping/pings`.

A server can instead have the feeds pushed to it by a WebSub hub, which
needs a public callback URL. The result of `fetch!` has `:websub` when the
feed names a hub. Subscribe with a secret that nobody can guess, one for
each subscription, e.g. from `websub/token`, and handle the callback with
`websub/handler`:

```clojure
(let [{:keys [hubs topic]} (:websub result)]
  (websub/call! (websub/request :subscribe (first hubs) topic
                                "https://app.example.net/websub/4f1c9e2a"
                                {:secret secret :lease-seconds 864000})))

(def callback
  (websub/handler
    {:wanted-pred (fn [{:keys [mode topic]}] (= topic (pending mode)))
     :deliver!    (fn [{:keys [topic body]}] (swap! inbox conj [topic body]))
     :secret      secret}))
```

The `:deliver!` function only gets feeds with the right signature. Return
from it quickly, and parse the feed afterwards. Renew the subscription
before the lease runs out, at `websub/next-renewal`, and keep polling on
the normal schedule too, since a hub can fail.


## Follow lists of shows

A network or a critic can publish a list of shows as OPML. When a listener
follows it, the shows that join the list become subscriptions. Fetch the
list with `podcast/fetch-list!`, which gives it as `:list`, or an `:error`
as `fetch!` does:

```clojure
(def fetched (podcast/fetch-list! list-url))

(swap! lib library/follow-list list-url (:list fetched)
       {:title "Picks" :folder "Picks" :etag (:etag fetched)})
```

The `:etag` makes the next fetch of the list conditional. After that,
`refresh!` fetches the list again once a day. A show that leaves the list
stays a subscription, since the listener may still like it. A show that
the listener unsubscribed from doesn't come back, and a show is
recognised by its feed URL, so it isn't added twice. In the OPML from
`profile.opml/to-opml`, the followed lists are includes.


## Keep the library between runs

Save the library into a store of its own, e.g. after each refresh, and
read it back into a new library when the app starts:

```clojure
(def data (folder/store "data"))

(library/save! @lib data)
;; => {:put ["feed-917393e3-1b1e-5cef-ace4-edaa54e1f810-1759233600000.edn"
;;           "segment-f62a6169e37bd339.ciff" "index.edn" "library.edn"]
;;     :delete []}

(reset! lib (library/restore! (library/make device-id) data))
```

This writes the profile, the followed lists and the last sync to
`library.edn`, and each feed to a document of its own. The search index
has documents of its own too. Only the documents of the feeds and of the
index that changed since the last save are written again, so saving
after each refresh is cheap. The `:config` can hold functions, so it
isn't saved: give it to `make` again. When the index is missing or
doesn't read, `restore!` builds it again from the feeds, and a feed whose
document is missing is fetched again at the next refresh. A document
that didn't read is in the `:report` under `:restore`. In ClojureScript,
both functions return promises.

`library.edn` says the version of its shape. A library saved by an older
version of podcast-clj is brought up to this one, and one saved by a
newer version isn't read: `restore!` gives the library as `make` made it,
with `:library/newer-format` in the report, and `save!` won't write over
it, so an older app can't destroy what a newer one saved.

Any store will do, but give the library one of its own, since `save!`
deletes the documents of any other index there:

- **On the JVM and in Node,** a folder, as above.
- **In a browser,** IndexedDB, `(indexeddb/store "my-app")`, or the origin private
  file system, `(.then (.getDirectory js/navigator.storage) folder/store)`.
  A browser can clear both when the disk runs low, unless the page asked
  for `navigator.storage.persist()` and got it. If it does, `restore!`
  gives the library as `make` made it, and the next sync brings the
  profile back from the other devices.


## Build the show and episode pages

For a show page, `library/show` gives everything in one map, and for an
episode page, `library/episode` does. Merge in `library/read-files!` to
add what the episode's chapters file, transcript and media file say:

```clojure
(def show-page (library/show @lib guid))
(:notes show-page)     ; the description, sanitised, as Hiccup
(:episodes show-page)  ; the items in listening order
(:links show-page)     ; links to the show in other apps

(def page (merge (library/episode @lib id) (library/read-files! @lib id)))
(chapters/at (:document (:chapters page)) 754)      ; the chapter at 12:34
(transcript/at (:document (:transcript page)) 754)  ; the segment at 12:34
```

The chapters come from the first place that has them: the chapters file,
Podlove chapters in the feed, the media file, or timestamps in the show
notes, and `:source` says which. A file that couldn't be read leaves an
`:error` in its part. The duration is read from the media file, and a host
that inserts ads can send a different file each time, so it can differ
from the feed. For an HLS stream, `:media` is what its playlists say: the
variants with their bitrates, sizes and codecs, the renditions such as
subtitles, the duration, and `:video?`.

The option `:except` leaves out the parts that a page doesn't need, e.g.
the rows of an episode list are much cheaper as `(library/episode @lib id
{:except #{:notes :text :chapters :links}})`.

For an episode with video, `:media-kinds` says whether it offers
`:audio`, `:video` or both, `:poster` is the picture to show before the
video plays, and `:content-links` are the places outside the feed where it
can be watched, e.g. on YouTube.

Links and images in the notes are made absolute against the web page of
the episode or the show, and a link written as a bare domain, as many
feeds do, gets `https://`. To do it your own way, give `:notes-url-fn` a
function of the page and the URL, which gives the URL to keep or nil.

For the shows that a show recommends in its podroll, and the feed of its
publisher, merge in `library/fetch-linked-shows!`. It reads each show of
the podroll from the start of its feed, most often the first 64 KB, or
from the Podcast Index when the config has its credentials as `:index`.
Only show the publisher's other shows when `:confirmed?` is true, since
the publisher's feed must list this show too:

```clojure
(def linked (library/fetch-linked-shows! @lib guid))

(map (comp :rss/title :show) (:podroll linked))
(when (:confirmed? (:publisher linked))
  (:shows (:publisher linked)))
```

Without `app.library`, `show/view` and `episode/view` take a parsed feed
directly. Where the vocabularies overlap, `resolve/properties` gives one
value for each property, and each accessor of `feed.resolve`, such as
`resolve/transcript` or `resolve/podroll`, gives one of them on its own.


## Show the comments on an episode

A feed can name a thread of comments about an episode on ActivityPub, the
AT Protocol or Nostr, and `comments/thread` picks the first one that
podcast-clj can read. It gives nil when there is none, or when the
podcaster turned comments off. For the AT Protocol, give the AppView to
ask for replies:

```clojure
(def opts {:appview "https://public.api.bsky.app"})

(def thread
  (some-> (comments/thread show item opts) (comments/fetch! opts)))

(comments/tree thread)
;; => {:content "<p>…</p>" :author {:name "…" …} :replies [{…} …]}
```

The content of a comment is sanitised HTML. To link mentions and hashtags
to pages of your app, render it with `comments/hiccup` and a `:link-fn`:

```clojure
(defn comment-link
  "The URL in this app of the mention or hashtag `part` of a comment."
  [{:keys [type protocol id tag]}]
  (case type
    :mention (when id (str "/profiles/" protocol "/" (url/percent-encode id)))
    :hashtag (str "/tags/" (url/percent-encode tag))
    nil))

(def reply (first (:replies (comments/tree thread))))

(comments/hiccup thread reply {:link-fn comment-link})
;; => [[:p {} "Good episode, " [:a {:href "/profiles/activitypub/…"} "@ann"]
;;      " " [:a {:href "/tags/podcast"} "#podcast"]]]
```

For a notification, `comments/text` gives the content as plain text, and
`comments/parts` gives the parts as data. Show a comment with a `:summary`
or `:sensitive?` behind a warning. On Bluesky, the author of the root post
can hide replies, and `tree` only shows those with `{:hidden? true}`.

A fetch reads for a minute at most, and 100 comments at a time. To show
something sooner, give it `:max-seconds 5`, show what it read, and fetch
the same thread again to read more. A server that failed for a while is
asked again on the next fetch. To see new comments, start again from
`comments/thread`.

On Nostr, people also write about episodes that name no thread, and
`nostr-comments/about` gives the thread of those notes, for an episode or
a show:

```clojure
(def relays ["wss://relay.fountain.fm" "wss://relay.damus.io"])

(def thread
  (comments/fetch! (nostr-comments/about show item) {:relays relays}))

(:replies (comments/tree thread))
```

Its root is the episode itself, so the root of the tree has no content.
The podcaster didn't choose this thread, so show nothing when
`resolve/social-interacts` gives the protocol `disabled`. Nostr has no web
pages, so a mention has no link unless your `:link-fn` gives one,
such as its `:uri`, a `nostr:` URI that opens a Nostr app on the device.


## Play episodes

The profile holds the queue and the progress, and you control the media
element. Send each event of the media element to `library/step`, and do
the effects it returns:

```clojure
(swap! lib library/update-profile profile.queue/enqueue id)

(:effects (swap! lib library/step [:progress id 1234 3600]))  ; => []
(:effects (swap! lib library/step [:skip id :forward]))
;; => [[:seek id 1264]]
(:effects (swap! lib library/step [:ended id]))
;; => [[:play next-id]]
```

The effects are `[:seek id position]`, `[:play id]`, `[:stop id]` and
`[:played id]`. In ClojureScript, a handler can look like this:

```clojure
(defn apply-effects!
  "Do the `effects` of a step on the media element `audio`."
  [audio effects]
  (doseq [[kind id position] effects]
    (case kind
      :seek   (set! (.-currentTime audio) position)
      :stop   (.pause audio)
      :play   (play-episode! audio id)
      :played nil)))
```

To start an episode, `library/playback` gives what the player needs: the
file, where the listener left it or the end of its intro, the show's
rate, the title and artwork, the chapters, a transcript for captions, and
the metadata of the Media Session, which a browser shows on the lock
screen. With `media-session/apply!`, the buttons there and on the
headphones send their events through `library/step` too:

```clojure
(defn play-episode!
  "Start the episode `id` of the library in the media element `audio`."
  [audio id]
  (let [{:keys [src position rate] :as playback} (library/playback @lib id)
        step! #(apply-effects! audio (:effects (swap! lib library/step %)))]
    (set! (.-src audio) src)
    (set! (.-playbackRate audio) rate)
    (.addEventListener audio "loadedmetadata"
                       #(set! (.-currentTime audio) position)
                       #js {:once true})
    (media-session/apply! playback
                          (media-session/actions @lib id)
                          (fn [details]
                            (run! step! (media-session/events
                                         @lib id (.-currentTime audio)
                                         details))))
    (.play audio)))
```

So that the lock screen shows how far the episode has come, call
`media-session/position!` with the element at its `loadedmetadata`,
`seeked` and `ratechange` events. The next and previous buttons go to
the chapters of the episode by default. The listener's preference
`:track-buttons` can send them to the episodes of the queue instead,
with `:queue`, or to the queue once no chapter is left, with
`:chapters-first`. For buttons of your own, the events
`[:next id]` and `[:previous id]` of `library/step` skip to the next
episode of the queue and go back to the one heard before.

The file is the downloaded one when the config's `:local-file-fn` gives
one for the episode's id, e.g. an object URL, and else the enclosure.
Once `library/read-files!` has read the episode's chapters file, give its
result to `playback` as `:files`, and the metadata has those chapters too.

To pick the file to play yourself, use `resolve/enclosure`, e.g. with
`{:types ["audio/opus" "audio/mpeg"] :max-bitrate 64000}`. To keep to audio when a show also offers video, add
`:playable-pred resolve/audio?`, which leaves out a stream that doesn't
say what it holds. The listener chooses between the audio and the video
of an episode that offers both with the preference `:video?`, or the
show's setting of that name, and `library/episode` and `library/plan`
follow it.

To queue an episode of a show that the listener isn't subscribed to, e.g.
from a search or a link, add it to the library first:

```clojure
(let [fetched (podcast/fetch! url)
      id      [(feed.guid/feed-guid (:feed fetched) url) item-guid]]
  (swap! lib #(-> (library/add-episode % url fetched item-guid)
                  (library/update-profile profile.queue/enqueue id))))
```

The profile keeps the episode as a remote item with the URL of its feed,
as `podcast:remoteItem` names an episode, so `refresh!` fetches that feed
on every device. The library lists only that episode of the show, and
the show's later episodes never count as new. To take it out again, use
`library/remove-episode`, which leaves the queue as it is.

Some settings and events change what `step` does:

- **Intros and outros.** With `:skip-outro 45` in the settings of a show,
  its episodes end 45 seconds early, and `step` returns `[:stop id]` and
  then the effects of an ended episode.
- **Chapters.** The events `[:chapter id :next doc]` and
  `[:chapter id :previous doc]` jump between the chapters of `doc`.
- **Sleep timer.** Set `:sleep` in the library, e.g.
  `(swap! lib assoc :sleep {:episodes 2})`, to stop the player when two
  more episodes have ended. The timer belongs to this device, so it isn't
  synced.
- **Played.** An episode counts as played 30 seconds from its end, and
  listening time isn't counted across a gap of more than a minute. The
  options `:played-threshold` and `:listening-gap` change both.

The browser player of the inspector, in `web/inspector/player.cljs`, is a
working example.


## Download episodes

```clojure
(library/plan @lib files)
;; => {:download [{:id [guid "ep-3"] :url "https://…" :reason :rule …}]
;;     :delete   [{:id [guid "ep-1"] :bytes 50117331 :reason :played}]
;;     :waiting  []}
```

The plan says which episodes to download and which files to delete. The
`files` are your own record of the files on the device, e.g.
`{[guid "ep-1"] {:bytes 50117331 :at #inst "2026-09-09"}}`. Downloading,
deleting and choosing when to do it are up to you, since podcast-clj
knows nothing about the network or the battery. Make a plan after each
refresh, and when the listener plays or queues an episode.

The rules live in the profile, so they sync: the listener's rules are
the preference `:downloads`, and the rule of a show is the setting
`:downloads` of its subscription. A rule selects episodes with the options
of `criteria/episodes`:

```clojure
(def show-rule
  {:download? true
   :episodes  {:where {:duration [600 nil]} :limit 3}})

(swap! lib library/update-profile
       (fn [profile at]
         (-> profile
             (profile/prefer {:downloads {:default      {:played 86400}
                                          :queue        3
                                          :max-episodes 50}}
                             at)
             (profile/configure guid {:downloads show-rule} at))))
```

Here a played file goes after a day, the first three episodes of the queue
are downloaded, and the show gets its three newest episodes of ten minutes
or more. Every key is in `downloads/default-rules`, and those of a show's
rule in `downloads/default-rule`.

To follow the plan:

- Download each file of `:download` in order, and add it to `files` with
  the time as `:at` when the download starts.
- Delete each file of `:delete`, and keep its entry with `:removed?`, so it
  isn't downloaded again.
- Show the listener what's in `:waiting`, which didn't fit within
  `:max-episodes` or `:max-bytes`, or was a file above `:max-file-bytes`.
  Stop a download that runs past `:max-file-bytes` yourself, since a feed
  can give the wrong length.

Set `:keep?` in the entry of a file the listener asked for, and no rule
deletes it. For rules that only apply to this device, give `plan` the
rules as `:rules`. To choose among the files of an episode, e.g. audio
alone, give it the options of `resolve/enclosure` as `:enclosure`. The
plan only has files that `resolve/downloadable?` takes, so never an HLS
stream or a torrent. A rule written by a newer version of podcast-clj does
nothing until this version understands it, and `downloads/problems` says
why.


## Share a show or an episode

No standard defines a link to a show in an app, so `links/links` writes
the link for each app that the show's ids are enough for. A show from
`directory/search!` has the most ids, so it gets the most links:

```clojure
(def ids (links/ids (first found) item))

(links/links ids {:only #{:apple :pocketcasts :antennapod}})
;; => [{:app :antennapod :name "AntennaPod" :type :app
;;      :show "https://antennapod.org/deeplink/subscribe?url=…"
;;      :subscribe "antennapod-subscribe://…"} …]
```

A `:subscribe` link opens the app, so only show it on a device that has
the app. The link formats change, so give your own table as `:apps` when
one is wrong. For a link to a moment in an episode, `links/at-time` puts a
media fragment on the URL of the media file:

```clojure
(links/at-time (:url (resolve/enclosure item)) 754 796.5)
;; => "https://example.org/54.mp3#t=754,796.5"
```


## Search the episodes

The library keeps a full-text index of every feed:

```clojure
(library/search @lib "podcasting 2" {:limit 10})
;; => [{:id     [feed-guid "https://example.com/ep0002"]
;;      :score  0.16
;;      :stored {:title "Episode 2 - The Present" …}
;;      :feed   {…}
;;      :item   {…}}
;;     …]
```

The results are the episodes and shows, best first, and `:offset 10`
gives the next page. The last word counts as a prefix, so this works for
search as you type. A query can also have:

- `"a phrase"`
- `OR` or `|` between words, `NOT`, `!` or `-` before one, and parentheses
- `word~` for a word with typos
- `title:word` or `notes:word` for one field

The docstring of `search.queries/parse` has the rest. The index takes
about two kilobytes per episode, so 50 shows with 28,000 episodes take
about 60 megabytes. To leave it out, e.g. on a watch, give `:search?
false` in the config.

A word that no episode holds also matches words with typos: one in a
word of three to five letters, and two in a longer one, which the option
`:typo-lengths` changes. With `:fuzzy true`, every word can have typos,
and with `:fuzzy false`, only a `word~` can. The words that matched with
typos are under `:typos` in the metadata of the results, and in each
result that holds a word only with typos, so that the UI can say "Near
matches for futrue":

```clojure
(def found (library/search @lib "futrue"))

(:typos (meta found))   ; => {"futrue" ["future"]}
(:typos (first found))  ; => {"futrue" ["future"]}
```

To search only some episodes, give the criteria of `library/episodes` as
`:where`, which leaves out the shows:

```clojure
(library/search @lib "podcasting" {:where {:status :unplayed}})
```

To mark the matches in a result, cut a snippet of its notes:

```clojure
(search.queries/snippet (html/text (resolve/description item))
                        "\"podcasting 2.0\"")
;; => [{:text "A look into the future of podcasting and how we get to "}
;;     {:text "Podcasting 2.0" :match? true}
;;     {:text "!"}]
```

To find where words are said in a transcript, use `transcript/matches`.
Each passage that matches has the time of the cue where it starts:

```clojure
(transcript/matches (:document (:transcript page)) "trailer")
;; => [{:start-time 2.76 :speaker "Sarah" :body "…" :parts […]} …]
```

Building the index takes a few seconds for 50 shows, so `library/save!`
saves it too, as [Keep the library between
runs](#keep-the-library-between-runs) shows.

Without `app.library`, build the index from the feeds yourself, with the
documents of `feed.search`, and search it with
[drop-in-search](https://github.com/simongray/drop-in-search):

```clojure
(defn index-feeds
  "A search index of the shows of `feeds`, a map of `{:url :feed}` entries,
  and of their episodes."
  [feeds]
  (feed.search/index (mapcat #(feed.search/feed-documents (:feed %) (:url %))
                             (vals feeds))))

(search/query (index-feeds feeds) "podcasting 2" {:limit 10})
```

When a feed changes, update the index with `feed.search/with-feed`, and
take a show out with `feed.search/without-feed`. To make transcripts
searchable, give them to `feed.search/item-document`. To keep the index,
`ciff/plan` gives the files to put into a store and the names to delete
there, and `ciff/read-files` reads them back.


## Organize the shows and episodes

The profile also holds how the listener organises shows and episodes,
and it syncs like the rest:

```clojure
(swap! lib library/update-profile
       (fn [profile at]
         (-> profile
             (profile/configure guid {:folder "News" :inbox? true} at)
             (profile/tag guid "Daily" at)
             (profile.marks/star id at)
             (profile.marks/bookmark id 754 "The interview starts" at)
             (profile.marks/rate-episode id 5 at)
             (profile.marks/clip id 754 42.5 "The best answer" at)
             (profile.queue/add-to-playlist "Road trip" id at)
             (profile/save-filter "Starred"
                                  {:where {:starred? true :status :unplayed}
                                   :order :newest}
                                  at))))
```

Each has a function to read it back, such as `profile/folders`,
`profile/tagged`, `profile.queue/inbox`, `profile.marks/starred`,
`profile.marks/bookmarks`, `profile.marks/clips` and
`profile.queue/playlist`. To show the size of the inbox on the app's
icon, give `(count (profile.queue/inbox profile))` to
`navigator.setAppBadge` in a browser, or to the platform's badge.

- A show is in one folder at most, but it can have many tags. Both go into
  OPML with `profile.opml/to-opml`, and `profile.opml/from-opml` reads
  them back, including the OPML of other apps.
- Ratings are from 1 to 5 by default, and `:scale` takes another one, such
  as `{:worst 0 :best 1}` for thumbs. Each rating keeps its scale, so
  `profile.marks/rating` can give it in yours.
- A playlist can be written as a list feed of the podcast namespace with
  `profile.queue/to-list-feed`, so other Podcasting 2.0 apps can play it.
- The seconds heard of each show by month are in `profile/listened`, for
  statistics. Report the position at least every 30 seconds while an
  episode plays, or it isn't counted.

A saved filter holds the options of `library/episodes`. Another device may
have saved it with a newer version, so check it with `criteria/problem`
first:

```clojure
(let [saved (get (profile/filters (:profile @lib)) "Starred")]
  (when-not (criteria/problem saved)
    (library/episodes @lib saved)))
```


## Sync the devices of a listener

Each device writes its whole profile as one document to a store, and
reads the documents of the other devices. The stores and the sync are
those of [like-minded](https://github.com/simongray/like-minded), to which
`sync` gives the profile. [Syncing](sync.md) explains why this is safe.

```clojure
(def creds {:username "simon" :password (secret/hide "app-password")})
(def root "https://cloud.example.org/remote.php/dav/files/simon/podcasts/")

(def cloud (webdav/store root {:credentials creds}))

(store/check! cloud)
;; => {:ok? true :checks [{:id :store/reachable …} …]}

(def backends
  [(sync/backend cloud {:opml? true :app "my-app"})
   (sync/backend (folder/store "/Users/simon/Dropbox/Podcasts")
                 {:app "my-app"})])

(swap! lib library/merge (library/sync! @lib backends))
(library/next-sync @lib)  ; when to sync again
```

A store can be a WebDAV collection or a folder that a sync client such as
Dropbox mirrors. The [providers
page](https://github.com/simongray/like-minded/blob/master/doc/providers.md)
of like-minded has the URLs and paths of some of them. With
`sync/backend`, a store becomes the
backend of a sync: `:app` names your app in the documents, and `:opml?`
also writes the subscriptions as OPML for other apps.

Sync when the app starts, when it goes to the background, and after a
series of changes. After any change, the position of a playing episode
included, `library/next-sync` asks for a sync within seconds, so while an
episode plays, sync when it pauses instead. A button in the settings
screen can test a store with `store/check!`.

A sync also moves each played episode that nothing changed for a year to
the archive of its show, so that the documents stop growing with every
episode played. It still counts as played, but its position and the time
it was played go. For another span, set `:archive-after` in the config,
in milliseconds.

To keep the documents encrypted on the server, wrap the store with
`encrypted/store` and a passphrase, or a function that returns one. Use
`:except` to leave the subscription list readable for other apps:

```clojure
(sync/backend (encrypted/store cloud
                               from-keychain
                               {:except #{sync/opml-name}})
              {:opml? true :app "my-app"})
```

Each sync removes the tombstones that every device has seen, once they're
30 days old. A device that stopped syncing blocks this. Such devices are
listed by `crdt/lagging` of mind-meld, and `crdt/retire` removes one.

To share the subscriptions and the progress of episodes with AntennaPod,
Kasts and other apps of the gpodder API, add a gpodder server as a backend.
The API carries nothing else, e.g. no queue or stars. For the GPodder Sync
app of Nextcloud, give `:api :nextcloud`:

```clojure
(gpodder/backend "https://gpodder.net"
                 {:credentials   creds
                  :identities-fn #(gpodder/identities (vals (:feeds @lib)))})
```

To serve a store from your own Clojure server, mount the Ring handler of
`store.webdav.server`, and for the gpodder API that of
`sync.gpodder.server`. Each serves one listener, so give each listener a
handler of their own behind your login, as their docstrings show. To sync
through a service that has no store yet, write one: like-minded's
[reference](https://github.com/simongray/like-minded/blob/master/doc/reference.md#the-store-contract)
gives the contract, a map of one to four functions.

### Sync through a folder in a browser

In a browser that can pick a folder, give its handle to `folder/store`.
The browser asks the listener for permission, and only during a click:

```clojure
(defn pick-folder!
  "Ask the listener for a sync folder, and give a promise of its backend."
  []
  (.then (js/showDirectoryPicker #js {:mode "readwrite"})
         #(sync/backend (folder/store %) {:opml? true :app "my-app"})))
```

Keep the handle in IndexedDB for the next visit, since it can't go in
EDN. The permission can lapse between visits, so check it before a sync.
If it's `:prompt`, show a button that renews it:

```clojure
(folder/query-permission! handle)    ; promises :granted, :prompt or :denied
(folder/request-permission! handle)  ; from a click
```

A browser without a folder picker can still sync through the folder by
hand. Let the listener save this device's document into the folder, and
load the folder with `<input type="file" webkitdirectory>`:

```clojure
(library/document @lib)  ; => {:name "8f1c….edn" :body "{:version 1 …}"}

(.then (library/sync! @lib [(sync/backend (folder/store (.-files input)))])
       #(swap! lib library/merge %))
```

The store of the files of an input has no `:put`, so its backend only
reads them. Check for the picker itself rather than for a browser, so
that the app uses it wherever it arrives.
The [providers
page](https://github.com/simongray/like-minded/blob/master/doc/providers.md#folders-in-a-browser-as-of-october-2026)
of like-minded has what each browser can do, and the inspector's player
in `web/inspector/player.cljs` does all of the above.

### Sync the listeners of a website

On a website, each browser is a device with a library of its own, and
your server keeps each listener's documents behind your login. A page
that syncs with its own origin sends the cookie of your login along, and
needs no CORS:

```clojure
;; on the server, a folder of documents for each listener
(defn dav
  "The WebDAV handler of the listener who sent `request`."
  [request]
  (let [store (folder/store (str "sync/" (user-id request)))]
    ((webdav-server/handler store "/dav/") request)))

;; in the page
(sync/backend (webdav/store (str js/location.origin "/dav/")))
```

Here `user-id` is your own function that gives the id that your login
found for the request. It goes into a path, so never use a name that the
user typed. Mount `dav` for `/dav` and everything below it, as the
inspector does in `web/inspector/service.clj`. A table in your database
can take the place of the folders, as a store of your own.

In the page:

- Keep the device id under a key of its own, so that a library that
  fails to save doesn't make a new device.
- Save the library to IndexedDB, since a large feed takes more than
  localStorage holds, and keep a database for each account, e.g.
  `(indexeddb/store (str "my-app-" account-id))`.
- Let one tab sync at a time, e.g. with `navigator.locks`, and before each
  step, merge in the library that the last step saved, since the tabs
  share the device id.
- Offer to retire the devices that `crdt/lagging` lists, since private
  windows and cleared site data leave devices that never sync again.


## Pay value for value

The value block of an episode is the `:value` of its view. A time split
in it can pass a stretch of the episode, e.g. a song, to the recipients
of an item in another feed. Fetch the value blocks of those items with
`library/fetch-remote-values!` when the episode starts, rather than at
each payment of a stream. Then `value/recipients-at` gives who is paid at
a position, and `value/payments` divides an amount between them:

```clojure
(def page (merge (library/episode @lib id)
                 (library/fetch-remote-values! @lib id)))

(def paid (value/recipients-at (:value page) 754 (:remote-values page)))

(value/payments (:recipients paid) 1000)
```

The blocks come from the Podcast Index when the config has the `:key`
and `:secret` of an account as `:index`, and else from the feed URL of
the remote item, which few feeds give. When a remote item has no block,
the episode's own recipients get its share. A remote item whose block
couldn't be fetched is in `:remote-report`, and until you fetch it again,
only the fees of its split are paid.

Without `app.library`, `episode/fetch-remote-values!` takes the view
from `episode/view`. To fetch a block yourself, e.g. with
`podcastindex/fetch-remote-value!`, which gives the block, nil or an
`:error`, fold it in with `value/with-remote`.

What goes with a payment is one map, however it's sent. Inside a remote
split, `(:remote-item (:remote paid))` is the remote item, so add its
`:feed-guid` and `:item-guid` as `:remote-feed-guid` and
`:remote-item-guid`:

```clojure
(def payment
  {:action    :boost
   :sats      1000
   :position  754
   :message   "Great episode"
   :feed-guid guid
   :item-guid (feed.guid/item-guid item)
   :app-name  "My app"})
```

Pay a recipient of type `node` with keysend, and give the wallet the
custom records of `value/records`. If its report has
`:value/custom-record`, don't send the payment, since it would reach the
node but not the recipient's wallet:

```clojure
(def records (value/records (assoc payment :sats 50) recipient))
(value/report records)  ; => [], or :value/custom-record
```

Pay a recipient of type `lnaddress` with an invoice from
`lnaddress/request-invoice!`, which also checks its amount. Put the
payment's metadata in the comment, by serving the header of
`metadata/format-header` at a URL of your own and naming that URL:

```clojure
(def invoice
  (lnaddress/request-invoice!
    "ann@getalby.com" 50000
    {:comment (fn [{:keys [comment-length]}]
                (metadata/format-comment payment url comment-length))}))
;; => {:invoice "lnbc500n1…" :msat 50000 :comment "rss::payment::boost …"}

{"x-rss-payment" (metadata/format-header payment)}  ; the header to serve at url
```

If the comment doesn't fit, none is sent and the invoice has no
`:comment`, so tell the listener before paying. Don't pay when an answer
has an `:error`, e.g. an invoice for another amount. The metadata page is
public, and so are the sender's name and message.

### Pay from the listener's wallet

A listener can connect their own wallet with a Nostr Wallet Connect URI.
The URI holds a secret key that can spend from the wallet, so store it as
you would a password:

```clojure
(def connection (nwc/parse uri))  ; the secret prints as #<secret>
(def info (nwc/fetch-info! connection))
;; => {:methods ["pay_invoice" "pay_keysend" …] :encryption ["nip44_v2"] …}

(nwc/ask! connection (nwc/pay-keysend (assoc payment :sats 50) recipient)
          {:info info})
;; => {:method "pay_keysend" :preimage "…" :fees-msat 1000}

(nwc/ask! connection (nwc/pay-invoice (:invoice invoice)) {:info info})
;; => {:method "pay_invoice" :preimage "…" :proven? true :fees-msat 1000}
```

The text to store comes from `nwc/format`. When `:proven?` is false, the
wallet says it paid but gave no proof, so don't pay again, and tell the
listener instead. An answer with an `:error` says whether the request went
out:

| Error | Sent? | What to do |
|---|---|---|
| `:value/wallet-unsupported` | No | Pay another way. The wallet doesn't take the command. |
| `:value/command-too-large` | No | Shorten the listener's message. |
| `:value/wallet-unreachable` | No | Try again later. |
| `:value/wallet-refused` | Yes | Show the `:reason`. The wallet didn't pay. |
| `:value/wallet-silent` | Yes | Don't pay again. Wait until `:expires-at`, then ask with `nwc/lookup-invoice`. |
| `:value/wallet-unreadable` | Yes | Ask the wallet before paying again. |

To keep the listener's money safe, show the recipients and amounts before
paying, since the feed chooses them. Show texts from the wallet as text,
not HTML. Ask the listener before using the `:lud16` of a connection, and
suggest giving the connection a budget in the wallet.

In a browser, a wallet extension such as Alby can give the page a WebLN
provider, which `value.webln` calls in ClojureScript. It counts keysend
payments in whole sats:

```clojure
(webln/pay-keysend! (assoc payment :sats 50) recipient)
(webln/pay-invoice! (:invoice invoice))
;; => a promise of {:preimage "…" :proven? true}
```


## Read the payments that a show receives

As a host, you can read what listeners sent with their payments. A keysend
payment carries the record 7629169, which `value/parse-boostagram` reads.
A payment to a Lightning address has a comment instead, and the metadata
it points to:

```clojure
(value/parse-boostagram (get custom-records 7629169))
(some-> (metadata/parse-comment memo) :url metadata/fetch!)

(value/as-received payment {:msat 25000 :address "ann@getalby.com"})
```

The sender writes both, so they're only claims, and `value/as-received`
keeps the amount the node received and reports any claim that differs.
Remove duplicates by `:id`, since a sender can point to the metadata of
another payment, and show the texts as text, not HTML.


## Validate a feed

To validate a feed, e.g. one that a podcaster submits, fetch it with
`fetch!` and give the answer to `validate/report`, with the requirements of
PSP-1 or Apple Podcasts. It gives the diagnostics of the fetch, the parse
and the validation in one list, and `diagnostics/verdict` says whether the
feed passes:

```clojure
(def diagnostics
  (validate/report (podcast/fetch! url) {:requirements :apple}))

(diagnostics/verdict diagnostics)
;; => {:passed? false
;;     :level   :error
;;     :counts  {:error 2 :warning 187 :info 373}
;;     :errors  ({:id      :validate/unknown-category
;;                :message "Apple Podcasts doesn't list the category"
;;                :count   1 …} …)}
```

A feed that can't be read fails as a whole, e.g. on a 404, or when the
document is an Atom feed. An error breaks a rule of a spec or of the
requirements you chose, a warning is something that apps are likely to
show worse than the podcaster means, and an info needs no change, as
[Diagnostics](diagnostics.md) explains. The requirements raise some
levels, e.g. a repeated enclosure URL is a warning, but an error for
Apple, which keeps only the first of the items.

For a table of the diagnostics, `diagnostics/summarize` groups them by
kind, and `validate/location` writes the path of each as people read it:

```clojure
(validate/location [:rss/items 6 :rss/enclosure :url])
;; => "item 7 › enclosure › url"
```

To validate a feed that you've built rather than fetched, e.g. before you
publish it, give the map to `validate/validate`. The files that a feed
links to are the next section's matter. Add the `:report` of
`check/check-files!` to the diagnostics for a verdict on both.


## Check the files that a feed links to

To check a feed before you publish it, or one that a podcaster submits,
use `check/check-files!`. It fetches the media files, images, transcripts
and chapters files of the newest items, and tells whether they reach a
listener as the feed says. Give it the feed's URL to check the feed itself
too, and the requirements of PSP-1 or Apple Podcasts as `:requirements`:

```clojure
(def result
  (check/check-files! feed {:url url :max-items 10 :requirements :apple}))

(diagnostics/summarize (:report result))
;; => ({:id :check/cors :level :warning :count 10 …}
;;     {:id :check/ranges :level :error :count 3 …} …)

(check/report (:files result) :psp-1)  ; other requirements, no new requests
```

Without `:read? true`, it only sends a HEAD request and asks for the first
bytes of each file. With it, it also reads the media files and the HLS
playlists of video, and compares their duration, codecs and height with
what the feed declares. The limits on files, time and bytes are in
`check/default-limits`. Like `fetch!`, it requests nothing on your own
network unless you give it an `:allow-pred`. For a single file,
`http/probe!` gives the same facts, e.g. the
size of an episode before downloading it.


## Count the downloads of a show

As a host, you count the downloads of your episodes from your server logs,
by the rules of the IAB. Turn each log line into a request map:

```clojure
{:time       #inst "2026-09-26T08:15:02Z"
 :ip         "203.0.113.5"
 :user-agent "Overcast/3.0 (+http://overcast.fm/; iOS podcast app)"
 :method     :get
 :url        "https://cdn.example.com/episode-54.mp3"
 :status     206
 :range      "bytes=0-"
 :bytes      1048576}
```

Then give `analytics/downloads` the requests, the open list of user agents
from <https://github.com/opawg/user-agents-v2>, and the bytes that each
file needs to count, which `analytics/threshold` works out:

```clojure
(defn read-user-agents
  "The OPAWG list of user agents in the checkout at `dir`."
  [dir]
  (analytics/user-agents
    (into {} (for [k [:bots :apps :libraries :browsers :devices :referrers]]
               [k (slurp (str dir "/src/" (name k) ".json"))]))))

(def threshold
  (analytics/threshold {:size 43200000 :header 1253 :duration 2700}))

(def result
  (analytics/downloads
    requests
    {:user-agents  (read-user-agents "user-agents-v2")
     :threshold    {episode-url threshold}
     :exclude-pred data-center?}))

(frequencies (map :url (:downloads result)))            ; downloads per file
(count (distinct (map :listener (:downloads result))))  ; listeners
(frequencies (map :agent-name (:downloads result)))     ; apps
```

The requests that didn't count are under `:dropped`, by reason. Here
`data-center?` is your own predicate, built from lists of data centres,
VPNs and bots, as the IAB asks. A day is a calendar day in UTC, and
`:window :rolling` or `:extending` counts the 24 hours from a listener's
first or last request instead.


## Measure the downloads with OP3

OP3 counts downloads when a feed puts its prefix in front of each
enclosure, which `op3/with-prefix` does:

```clojure
(rss/emit (op3/with-prefix feed))
;; <enclosure url="https://op3.dev/e,pg=…/cdn.example.com/episode-54.mp3" …/>
```

Its API needs the token of an API key from <https://op3.dev/api/keys>:

```clojure
(def show (op3/call! token :show {:show (:podcast/guid feed) :episodes? true}))

(op3/call! token :episode-download-counts {:show (:show-uuid show)})
;; => {:episodes [{:item-guid "PC20-272" :downloads-all 1168 …} …] …}

(def month
  (op3/fetch-downloads! token {:show  (:show-uuid show)
                               :start "2026-09"
                               :end   "2026-10"}))
```

A download from OP3 has the same keys as one from `analytics/downloads`,
and its `:url` is that of the file, without OP3's prefix, as `op3/target`
gives it, so the same code counts both. To try the API first, see the
sample token in [Services](services.md#op3). If a page fails,
`fetch-downloads!` returns the pages before it with an `:error`, and a
`:continuation-token` to read on from.


## Serve the feed of a show

This section is for hosts. Write a feed with `rss/emit`. A long feed is
slow to fetch, so split it with `publish/pages` into documents linked as RFC
5005 describes:

```clojure
(def documents
  (publish/pages feed {:url       "https://example.org/feed.xml"
                       :kind      :archived
                       :page-size 100}))

(map :url documents)
;; => ("https://example.org/feed.xml"            ; the newest items
;;     "https://example.org/feed.xml?archive=2"  ; archives, newest first
;;     "https://example.org/feed.xml?archive=1")
```

The archives don't change when a new episode comes out, so apps fetch each
one only once. Give the feed's URL as `:url` when the feed doesn't name
its own, and other URLs for the archives as `:page-url-fn`, a function of the
page number.

To push changes through WebSub, name the hub in the feed and tell it
about each change:

```clojure
(def feed
  (update feed :atom/links conj {:rel "hub" :href "https://hub.example.org/"}))

(websub/publish! ["https://hub.example.org/"] "https://example.org/feed.xml")
```

A private feed has a token for each subscriber. You make and check the
tokens on your server, and `publish/for-subscriber` writes one into the URLs
of the feed. The result is also closed to the directories:

```clojure
(def private (publish/for-subscriber feed "k3J9x2"))

(get-in private [:rss/items 0 :rss/enclosure :url])
;; => "https://media.example.org/54.mp3?token=k3J9x2"
```

Apply `op3/with-prefix` after `for-subscriber`, since the prefix goes in
front of the whole URL.

To import a show from another host, copy each file that `check/files`
lists, and point the feed at the copies with `publish/update-file-urls`:

```clojure
(def copies
  (into {}
        (for [{:keys [url]} (check/files feed {:max-items :all})]
          [url (copy-file! url)])))

(publish/update-file-urls feed #(get copies % %))
```

Here `copy-file!` is your own function, which copies a file to your server
and returns its new URL.

An MP3 file can hold its chapters with an image for each, and
`media/read!` gives the images as data URLs. JSON Chapters says that an
image is a URL, so write the images as files before you serve the
chapters file:

```clojure
(defn image-url
  "The URL of the file that the image at `url` is written to, if it's a
  data URL, or else `url`."
  [url]
  (if-let [{:keys [type data]} (bytes/from-data-url url)]
    (write-image! type data)
    url))

(def file (media/read! "https://media.example.org/54.mp3"))

(chapters/emit-json (chapters/update-image-urls (:chapters file) image-url))
```

Here `write-image!` is your own function too, which writes an image of a
MIME type and returns the URL of the file.


## Use another service

What podcast-clj knows about a service is a default that an option
replaces, and [Services and their defaults](services.md) lists them all.
To use a copy of a directory, start from the entry of one with the same
API:

```clojure
(def mirror
  (assoc (:fyyd directory/sources)
         :id   :mirror
         :root "https://fyyd.example.org/0.2/"))

(directory/search! "podcasting 2.0" {:sources [:apple mirror]})
```

For another instance of a service, give its address:

```clojure
(podcastindex/call! credentials :podcasts/by-guid {:guid guid}
                    {:base-url "https://index.example.org/api/1.0/"})
(podping/notify-request token feed-url {:gateway "https://ping.example.org/"})
(op3/with-prefix feed {:origin "https://op3.example.org"})
```

A gpodder server with other paths takes them as `:api`, and a directory
that changed its requirements takes them as `:requirements`, e.g.
`(assoc-in (:apple check.rules/requirements) [:artwork :sizes] [1400 4000])`.


## The namespaces of the examples

The namespaces outside `dk.simongray.podcast` are those of the libraries
that [Before you start](#before-you-start) lists.

```clojure
(ns my.app
  (:require [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.ciff :as ciff]
            [dk.simongray.drop-in-search.queries :as search.queries]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.store.encrypted :as encrypted]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.like-minded.store.webdav :as webdav]
            [dk.simongray.like-minded.store.webdav.server :as webdav-server]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.analytics :as analytics]
            [dk.simongray.podcast.analytics.op3 :as op3]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.media-session :as media-session]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.directory.podcastindex :as podcastindex]
            [dk.simongray.podcast.downloads :as downloads]
            [dk.simongray.podcast.feed.check :as check]
            [dk.simongray.podcast.feed.check.rules :as check.rules]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.guid :as feed.guid]
            [dk.simongray.podcast.feed.publish :as publish]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.gpodder :as gpodder]
            [dk.simongray.podcast.updates.podping :as podping]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.lnaddress :as lnaddress]
            [dk.simongray.podcast.value.metadata :as metadata]
            [dk.simongray.podcast.value.nwc :as nwc]
            [dk.simongray.podcast.value.webln :as webln]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.proxy :as proxy]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.websub :as websub]))
```
