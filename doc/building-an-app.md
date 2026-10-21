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
(swap! lib library/merge (:library (library/refresh! @lib opts)))
(swap! lib library/merge (:library (library/sync! @lib backends)))
```

Change it with `swap!`, and put the library that `refresh!` and `sync!`
return into it with `library/merge`, which keeps what the listener did
while they fetched. In ClojureScript they return a promise:
`(.then (library/refresh! @lib opts) #(swap! lib library/merge (:library %)))`.
Make the `device-id` once for each installation, e.g. with
`(str (random-uuid))`, and keep it.

A function that also tells what it did returns that beside the new
library, as a map of the `:library` and the rest: the `:effects` of
`step`, the new episodes and the `:report` of `refresh!`, and the
`:report` of `sync!` and `restore!`. The `:config` given to `make` holds
the options that the other library functions use, as data, and the
docstring of `app.library` lists them. The options that are functions,
e.g. `:send` or `:codec`, stay out of the config, so keep them in a map
of your own, here `opts`, and give it to each call. Keep the library
between runs with `library/save!` and `library/restore!`, as [Keep the
library between runs](#keep-the-library-between-runs) shows.

The parts of the library work with the namespaces they come from, e.g.
every function of `profile`, and of its parts such as `profile.queue`,
takes `(:profile @lib)`. The profile is a CRDT, so that devices can merge
their changes in any order, and where two changed the same thing, the
later change wins. Change it only with these functions, e.g.
`(swap! lib library/update-profile profile.queue/enqueue id)`, and never
with `assoc` or `dissoc`.

Each change is stamped with its time, which the functions of
`app.library` read from the clock, the option `:now-fn`, so a test can fix
it. For an event that you handle later, e.g. in a background task, give
the time it happened as the option `:at`. Since `update-profile` takes no
options, call the function of `profile` with that time instead, e.g.
`(swap! lib update :profile profile.queue/enqueue id at)`.

The examples call each namespace by the last part of its name, e.g.
`library` for `dk.simongray.podcast.app.library`, or by a longer name
where that part would be unclear, e.g. `feed.guid`, `profile.queue` or
`nostr-comments`. [The last section](#the-namespaces-of-the-examples)
requires them all. The examples run on the JVM, except those for a
browser, and in ClojureScript the functions that send requests return
promises.

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

- Pick a store for the library.
- Play the media, as the effects of `library/step` say.
- Download and delete episode files, as `library/plan` says, when the
  network and the battery allow it, and tell the library when you do.
- Show notifications and make payments.
- Draw the user interface.
- For a host, keep the server logs and the lists of user agents, data
  centres and bots that counting downloads needs.


## Where it runs

- **On the JVM**, everything works. A few functions exist only there,
  e.g. `podping/poll!`, and [Namespaces](namespaces.md) says which.
- **In a browser**, the requests go through a proxy on your server, as the
  next section shows, and the rest runs in the page.
- **In Node** 20.16 or 22.3 and later, install a `DOMParser`, e.g. that
  of `@xmldom/xmldom` as `globalThis.DOMParser`. Nostr comments and
  wallets need the global `WebSocket` of Node 22. A web worker has no
  `DOMParser` either, so install one there too.
- **On a server of your own**, run the proxy of a web page, the callbacks
  of WebSub, and the sync over WebDAV or the gpodder API if you serve it.
- **In a desktop app on Electron**, the main process is Node and the
  window is a browser, and **in a PWA or a Capacitor app**, the page is a
  browser. Rather than a proxy, give the `:send` of wary-fetch's
  `electron/sender`, which sends through the main process, or of
  `capacitor/sender`, which sends through the native HTTP of Capacitor.
- **In React Native**, install a `DOMParser` as in Node. Give the stores
  of like-minded's `store.kv` and `store.folder` the functions of your
  key-value store and file system, e.g. MMKV and expo-file-system, since
  there's no IndexedDB or folder of its own. The requests need no proxy.

In ClojureScript, add podcast-clj to `deps.edn` and let shadow-cljs read
it with `:deps true`. A browser needs no npm packages, and Node, web
workers and React Native only the `DOMParser` above.

A few feeds aren't well-formed XML even after the repairs that
podcast-clj makes, e.g. with a tag that's never closed. On the JVM, jsoup
reads them when it's on the classpath. In JavaScript, they're refused as
malformed, unless you give a lenient parser of your own as
`:lenient-parser`, beside `:send` in the options of each call that
fetches feeds. It's a function of the XML text that returns the raw
Hiccup that `qname-hiccup/parse-raw` returns, e.g. over htmlparser2.


## Send requests from a web page

Few feed hosts and directories let a page of another site read their
answers, so a page sends its requests through your own server. Mount the
proxy of [wary-fetch](https://github.com/simongray/wary-fetch) there, and
give its sender as `:send`:

```clojure
;; on the server, as a route of Pedestal or any other Ring router
["/proxy" :any (proxy/handler) :route-name ::proxy]

;; in the page, in the options of each call of the library
(def opts {:send (proxy/sender "/proxy")})
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

The Podcast Index and OP3 take their credentials in headers too. To keep
the secrets on your server, e.g. for a public site, sign the requests
there, and give the page credentials without the `:secret`, or a nil
token for OP3:

```clojure
;; on the server
(proxy/handler {:send (podcastindex/sender credentials
                                           {:send (op3/sender token)})})

;; in the page
(library/make device-id {:config {:index {}}})
(def opts {:send (proxy/sender "/proxy")})
```

Anyone who passes your login then spends the quota of your accounts. If
each listener brings their own key instead, the page signs, and the
handler forwards "x-auth-key", "x-auth-date" and "authorization" in its
`:forward`, which works only when your login doesn't use that header.


## Find a show

Search with `directory/search!`. It asks Apple Podcasts, fyyd and
gpodder.net at the same time, and the Podcast Index too when you give it
credentials, and combines the results into one entry per show:

```clojure
(def found (:results (directory/search! "podcasting 2.0" {:limit 5})))

(map (juxt resolve/title resolve/author :directory/sources) found)
;; => (["Podcasting 2.0" "Podcast Index LLC" {:apple "1584274529" :fyyd 61003}]
;;     …)

(def url (resolve/self-url (first found)))
```

The search lasts as long as the slowest directory takes to answer. To
show the results as they come, give it `:on-results-fn`, which gets the
results so far each time a directory answers, with the directories yet to
answer as `:pending`:

```clojure
(directory/search! "podcasting 2.0"
                   {:on-results-fn #(prn (:pending %) (count (:results %)))})
;; prints e.g. [:fyyd :gpodder] 3, then [:fyyd] 6, then nil 6
```

Those results reorder as the directories answer, since the shows that
more of them have move up. For a list from each directory, which stays
as it came, search each on its own with `:sources`, e.g. those of the
listener's preference `:search-sources`, in their order:

```clojure
(for [source (:search-sources (profile/preferences profile))]
  (directory/search! "podcasting 2.0" {:sources [source]}))
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

To show the categories in the listener's language, fetch Apple's names
for them once and keep them, since they rarely change. They're keyed by
the English names that the charts and `resolve/categories` use. Show the
English name when there's no localised one:

```clojure
(def names
  (:names (directory/fetch-category-names! "dk" "da")))

(map #(get names % %) ["News" "Tech News"])
;; => ("Nyheder" "Teknologinyheder")
```

For episodes, ask one source with `directory/call!`. The `:episodes` of a
show come from Apple, fyyd and the Podcast Index, the `:recent` episodes
of any show from fyyd and the Podcast Index, and the episodes `:by-person`
and those `:live` now from the Podcast Index alone:

```clojure
(def episodes
  (directory/call! :apple :episodes {:id "1584274529" :limit 5}))

(let [e (first episodes)]
  (links/link :apple :episode (links/ids (:directory/show e) e)))
;; => "https://podcasts.apple.com/podcast/id1584274529?i=1000794523391"
```

Each episode has the keys of an item and the show it's from as
`:directory/show`, so `links/ids` gives the ids of both.

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
`:auto-queue?` puts the show's new episodes in the listener's queue. The
listener's preference `:subscription-defaults` gives the settings of
every new subscription, e.g. `{:auto-queue? true}`. The subscription
keeps the show's title and its artwork as `:image`, so a device that
gets it by sync can show it before it fetches the feed.

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

A page can name several feeds of megabytes each, as Freak Show's does, and
`:heads? true` fetches only the start of each, most often 64 KB. For one
feed, `discovery/fetch-head!` does the same. The start of a feed is
enough for a preview, and `library/subscribe` takes it for the title, the
artwork and the guid, while `refresh!` fetches the whole feed. With
`{:items 20}`, `fetch-head!` reads on to the end of the 20th item, so a
show's page can list its first episodes while the whole feed is fetched.

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
another, e.g. after a move. When `feeds/add` keeps a feed, it puts the
key on it with `feed.guid/with-key`, so that `feed.guid/episode-id` gives
ids by it. A feed can declare the guid of another subscription and take
it over, so check a new feed with `profile/takeover` first.

### A show of local files

A folder of audio files, e.g. an audiobook or a set of recordings, can be
a show of its own. Read each file with `media/read!` through the file
fetcher of `util.bytes.ranges`, build the show with `local/feed`, and
subscribe to it:

```clojure
(defn local-show
  "The show of the audio files `names` in the folder `dir`."
  [dir names]
  (local/feed (for [name names
                    :let [path (str dir "/" name)]]
                {:url   (str "file://" path)
                 :name  name
                 :media (media/read! path {:fetch (ranges/file-fetcher path)})})))

(swap! lib library/subscribe-local
       (local-show "/Users/ann/Books/Dune" ["01.mp3" "02.mp3"]))
```

The episodes go in the order of their file names, and their titles,
dates and durations come from the tags of the files. The show is titled
by the album that the files name, so give `local/feed` a `:title` for
files without one. No refresh fetches the show, so subscribe again when
the files change. In a browser, read a File that the listener picked
with `ranges/blob-fetcher`, and give it an object URL, which lasts only
as long as the page, so build the show again when the page loads.

The same files give the same guids, so a device with the same files
shares the listener's progress. The other devices get the subscription
by sync without a URL, and list it by its title. In OPML, the show is an
outline with `isComment="true"`, which other apps skip, and
`profile.opml/from-opml` reads it back as such a subscription. To read
the chapters of a local episode, give `episode/read-files!` the same
`:fetch`.


## Keep the feeds current

```clojure
(def refreshed (library/refresh! @lib opts))
(swap! lib library/merge (:library refreshed))

(dissoc refreshed :library)
;; => {:at #inst "…" :added [[feed-guid item-guid] …] :backfilled []
;;     :renamed [] :moved {} :report []
;;     :counts {:checked 12 :unchanged 10 :changed 2 :failed 0 :left 0}
;;     :seconds 1.4}

(library/next-refresh @lib {:after refreshed})  ; when to refresh again
```

This fetches whatever is due: the lists that the listener follows, the
feeds of new subscriptions, and the feeds whose next check has come. The
new episodes go to the inbox, unless the show's settings say otherwise:
to its queue with `:auto-queue?`, at its end, or right after the current
episode with `:next`, or nowhere with `:inbox? false`. With
`:auto-queue-max`, only the newest of one fetch join the queue, and the
others the inbox unless `:inbox?` is false. For a choice of these in the
app, e.g. a select on a show's page, `profile/new-episodes` reads the
settings as one value, and `profile/new-episodes-settings` gives the
settings of a value. If your app offers no such choice, give the config
`:new-episodes`, e.g. `:inbox`, and every show's new episodes go there,
whatever its settings say, without changing them for other apps. With
`:join-where`, criteria of `criteria/where`, only the new episodes that
meet them join either, e.g. `{:type #{"full" "bonus"}}` leaves the
trailers out. An episode that the listener already played or removed on
another device doesn't come back. An episode older than the newest one
the library knew isn't new either: it's `:backfilled` rather than
`:added`, so that a host that publishes its back catalogue again doesn't
fill the queue. The setting `:only-newer? false` counts those as new too.

The `:counts` and `:seconds` say how many feeds were checked and how it
went, e.g. for a status line or a log. Schedule the next refresh at
`library/next-refresh`: when the first feed or list is due, or right away
after a new subscription. After a refresh that found the device offline,
it waits five minutes, the config's `:offline-seconds`, when its `:after`
is what that refresh returned.

A refresh sends each feed's ETag and Last-Modified, so that most feeds
cost a 304 with no body. Some servers give neither, or answer 200 anyway,
and their feeds would come whole every time, to be parsed again. With
`:head-check? true`, such a feed is first read only to the end of its
third item, most often 10 to 30 KB, and fetched whole only when its newest
episodes changed, or at least once a day, the `:whole-every` of the
`:cadence`, so that an edit further down shows up too. The entry of each
feed says whether its server answers 304, as `:validates?`.

A platform gives a task in the background little time, e.g. half a
minute. Give `refresh!` that budget as `:max-seconds`, or a maximum
number of feeds as `:max-feeds`, and the feeds that it doesn't reach stay
due for the next refresh, as the `:left` of the counts says. To show
progress, give it `:on-feed-fn`, which is called as each fetch ends.
To let the listener cancel it, give it a `:signal`: an AbortSignal in
ClojureScript, or an atom that you set to true on the JVM. The feeds that
it stops or keeps from starting stay due, with no failure counted.

In a web page, parsing the feeds that changed can hold up the page for a
moment, so fetch them in a web worker. The refresh comes in parts for
that: `library/due` says on the page what's due, `library/fetch-due!`
fetches it in the worker, puts the documents of the feeds that changed
into the library's store and returns what it found as data, and
`library/refreshed` applies that to the library on the page. Write the
library first, so that the worker finds the documents of its feeds:

```clojure
;; on the page, which sends due to the worker
(def due
  (library/due @lib))

;; in the worker, which sends found back
(library/fetch-due! due store opts)

;; on the page
(swap! lib #(:library (library/refreshed % found opts)))
```

How the page and the worker talk is up to you. The inspector's player
posts EDN to its worker and takes a Web Lock so that one refresh runs at
a time across the tabs, in `web/inspector/fetching.cljs`. A worker has
no `DOMParser`, so install one first. xmldom's only logs the errors of a
malformed feed, so make it throw them:

```clojure
(defn throwing-parser
  "An xmldom parser that throws on errors instead of logging them."
  []
  (xmldom/DOMParser. #js {:onError (fn [level message _context]
                                     (when-not (= level "warning")
                                       (throw (js/Error. message))))}))

(set! (.-DOMParser js/globalThis) throwing-parser)
```

The worker writes nothing but the documents of feeds, so the page's
write of the library is the only one. `refreshed` takes an entry only
where the library still has the one that was due, so a feed that another
tab changed meanwhile stays as it is.

To announce new episodes, turn on the preference `:notify?`, or the
setting of that name for a show, and give `library/notifications` what
each refresh returns. It returns the new episodes that the listener
hasn't played, by show:

```clojure
(swap! lib library/update-profile profile/prefer {:notify? true})

(library/notifications (library/refresh! @lib opts))
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
defaults, e.g. `{:max-wait 21600}` for six hours, and the option
`:credentials-of` gives the credentials of a private feed by its URL, so
no password is stored in the library and none goes to another feed.

The library keeps every feed whole, which takes about twice the size of
its XML in memory. A device that never shows the feeds, such as a server
that only syncs and refreshes, can keep a small part of each with the
option `:keep-fn feeds/slim`, given to each call that fetches or
subscribes. That part still tells when to check the feed and which
episodes are new.

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
      (swap! lib library/merge
             (:library (library/refresh! @lib (assoc opts :feed-guids guids)))))
    polled))

(def polled (podping/poll!))  ; starts at the head of the chain
```

To tell the listener why, `feeds/pinged` gives the reasons of the pings
of each feed, e.g. `#{["live" "podcast"]}` when a live stream started.
When no node answers, the result has an `:error` and the block it got to,
so the next poll continues from there. ClojureScript has no `poll!`, but
the nodes answer a web page, so read the blocks with `podping/call!` and
their pings with `podping/pings`.

A server with a public URL can also have its feeds pushed to it by their
WebSub hubs. The podcast server does this itself, as its
[README](../server/README.md#feeds-pushed-by-websub-hubs) says. In a
server of your own, keep the hub subscriptions in a map of websub-clj's
next to the library. After each refresh, send the hubs the requests that
`library/hub-requests` returns:

```clojure
(def hub-subscriptions (atom {}))

(defn callback-url
  "A new callback URL for the hub subscription of a show, which nobody can
  guess."
  [_feed-guid _subscription]
  (str "https://app.example.net/websub/" (websub/token)))

(defn ask-hubs!
  "Send the WebSub hubs the requests that the shows of `lib` need."
  [lib]
  (doseq [[feed-guid request] (library/hub-requests @lib
                                                    @hub-subscriptions
                                                    callback-url)]
    (swap! hub-subscriptions websub/asked feed-guid request (time/now))
    (websub/call! request)))
```

They include a subscription for each show whose feed names a hub, an
unsubscription for each show the listener left, and the renewals. Each
subscription gets a secret of its own, so save the map with
`websub/format-subscriptions` and keep the text as safe as a password.

Serve the callback URLs with `websub/handler`, which only passes on a feed
with the hub's signature:

```clojure
(def callback
  (websub/handler
   (assoc (websub/handler-options #(deref hub-subscriptions))
          :verified! #(swap! hub-subscriptions websub/verified % (time/now))
          :denied!   #(swap! hub-subscriptions websub/denied % (time/now))
          :deliver!  (fn [{:keys [request] :as delivery}]
                       (let [[feed-guid] (websub/subscription-of
                                          @hub-subscriptions request)]
                         (future
                           (swap! lib #(:library (library/pushed % feed-guid delivery)))))))))
```

The hub waits for the answer, so `:deliver!` applies the push on another
thread. A push adds new episodes to the queue as a refresh does, and
`pushed` returns what changed beside the library, as `refresh!` does. Since a hub can push only what changed, a
push never removes an episode; the next fetch does that. Keep refreshing
on the normal schedule too, since a hub can fail.


## Follow lists of shows

A network or a critic can publish a list of shows as OPML. When a listener
follows it, the shows that join the list become subscriptions. Fetch the
list with `podcast/fetch-list!`, which returns it as `:list`, or an
`:error` as `fetch!` does:

```clojure
(def fetched (podcast/fetch-list! list-url))

(swap! lib library/follow-list list-url (:list fetched)
       {:title "Picks" :folder "Picks" :etag (:etag fetched)})
```

To show the shows of a list before the listener follows it, fetch the
start of each feed with `discovery/fetch-head!`.

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
;; => {:put    ["feed-917393e3-1b1e-5cef-ace4-edaa54e1f810-1759233600000.edn"
;;              "segment-f62a6169e37bd339.ciff" "index.edn" "library.edn"]
;;     :delete []}

(reset! lib (:library (library/restore! (library/make device-id) data)))
```

In ClojureScript, save the documents in Transit rather than EDN, which
ClojureScript reads about 30 times slower: a parsed feed of 20 MB takes
1.2 s to read back from EDN in Chrome, longer than parsing its XML again,
and 40 ms from Transit. Add `com.cognitect/transit-cljs` to your deps, and
give `make` like-minded's codec, and each call that reads or writes
documents too:

```clojure
(require '[dk.simongray.like-minded.transit :as transit])

(library/make device-id {:codec transit/codec})
(def opts {:codec transit/codec})
```

A library saved in EDN still reads, and the next save writes it in
Transit. A restore in ClojureScript that reads EDN has
`:library/slow-format` in its report, as a reminder.

This writes the profile, the followed lists and the last sync to
`library.edn`, and each feed to a document of its own. The search index
has documents of its own too. Only the documents of the feeds and of the
index that changed since the last save are written again, so saving
after each refresh is cheap. The `:config` belongs to your app, so it
isn't saved: give it to `make` again. When the index is missing or
doesn't read, `restore!` builds it again from the feeds, and a feed whose
document is missing is fetched again at the next refresh. A document
that didn't read is in the `:report` beside the library. In
ClojureScript, both functions return promises.

A library of hundreds of shows needn't hold every feed in memory. Restore
it with `{:load :none}`, or with the guids of the shows on the screen,
and read others back with `library/load!` when the listener opens them:

```clojure
(reset! lib (:library (library/restore! (library/make device-id) data {:load :none})))

(swap! lib library/merge (:library (library/load! @lib data [feed-guid])))
(swap! lib library/merge (:library (library/unload! @lib data :all)))  ; e.g. in the background
```

A feed that isn't loaded is still in the search index and in `shows`,
with `:unloaded? true`, and `show` gives its title and artwork, but no
episode of it is in the views or the download plan, whose files of it
stay. With the option `:store`, a refresh loads the feeds that it
fetches, since it needs the episodes they had, and they stay loaded.
Without it, they aren't fetched, and the report has
`:library/not-loaded`. The function `unload!` writes the feeds to their
documents before it lets them go, so nothing is lost if the app stops
before its next save.

The document `library.edn` names the version of its shape. A library
saved by an older version of podcast-clj is brought up to this one, and
one saved by a newer version isn't read: `restore!` returns the library as
`make` made it, which notes it under `:newer-format`, with
`:library/newer-format` in the report too, and `save!` won't write over
it, so an older app can't destroy what a newer one saved.

Any store will do, but give the library one of its own, since `save!`
deletes the documents of any other index there:

- **On the JVM and in Node,** a folder, as above.
- **In a browser,** IndexedDB, `(indexeddb/store "my-app")`, or the
  origin private file system, `(.then (.getDirectory js/navigator.storage)
  folder/store)`. A browser can clear both when the disk runs low, unless
  the page asked for `navigator.storage.persist()` and got it. If the
  browser clears them, `restore!` returns the library as `make` made it,
  and the next sync brings the profile back from the other devices.

### Share the library between tabs and workers

The tabs and workers of a web app share the browser's storage, and each
writes the library, so treat the library's document in the store as the
one place that every tab changes, as you would an atom. Then they write
as one device, and none writes over another. `library/read!` gives the
library and the version of its document, and `library/durable-swap!`
writes a change on condition of that version. When another tab wrote
first, it reads the store again and makes the same change to what it
holds, as `swap!` does:

```clojure
;; known holds what read! gave, and then what each write gave
(.then (library/durable-swap! store @known f opts)
       #(when-not (:error %)
          (reset! known %)))
```

For a loop of your own, `library/write!` takes the version as
`:if-version` and gives `:conflict?` when another tab wrote first.

Each feed and each segment of the search index is a document named by
what it holds, so `write!` puts only those that the store lacks, and the
library's own document last, which holds the profile, the entries of the
feeds and the manifest of the index. Give `read!` the library as it is,
since it reads only what changed, and tell the other tabs of the new
version, e.g. on a BroadcastChannel, so that they read it too. Both read
and write in the codec of `opts` alone, and `read!` reads a library that
`save!` wrote. In ClojureScript, both return promises.

Give the change the time of the listener's action, e.g. as the `:at` of
`library/step`, so that it stamps the same time when it's made again.
`library/documents` and `library/from-documents` are the pure halves of
`write!` and `read!`, for a store of your own. A web worker that
refreshes writes only feed documents, as [Keep the feeds
current](#keep-the-feeds-current) has it.


## Build the show and episode pages

For a show page, `library/show` gives everything in one map, and for an
episode page, `library/episode` does. Merge in `library/read-files!` to
add what the episode's chapters file, transcript and media file say:

```clojure
(def show-page (library/show @lib guid))
(:notes show-page)     ; the description, sanitised, as Hiccup
(:episodes show-page)  ; the items in the order of the show
(:links show-page)     ; links to the show in other apps

(def page (merge (library/episode @lib id) (library/read-files! @lib id)))
(chapters/at (:document (:chapters page)) 754)      ; the chapter at 12:34
(transcript/at (:document (:transcript page)) 754)  ; the segment at 12:34
```

A segment from a WebVTT cue with timestamps inside it, as word-level
transcripts have, also has the timed `:runs` of its text, e.g.
`[{:start-time 1.0 :text "Never "} {:start-time 1.5 :text "drink"}]`, to
highlight each word as it's spoken.

A caption file comes as hundreds of cues of a few words each. To show it
for reading, `transcript/paragraphs` merges the cues into paragraphs, each
a speaker's turn or about a minute of it. Their `:runs` keep the time of
each cue or word.

An episode's id is a pair of strings, `[feed-guid item-guid]`. For a
key that takes one string, `feed.guid/format-episode-id` writes the id as
one, and `feed.guid/parse-episode-id` reads it back.

An item guid is often a URL, and many servers refuse its slashes in a
path even when they're percent-encoded. For the path of an episode page,
`feed.guid/short-hash` gives the first 7 hex digits of the guid's SHA-1,
as git abbreviates a commit, e.g. `/shows/<feed-guid>/episodes/558b541`.
To find the episode again, `feed.guid/matching-items` gives the items of
the feed whose hash starts with them. That's almost always one, and you
can tell two apart by something else in the URL, e.g. a slug of the
title.

Artwork is often 3000 pixels wide. When a feed or a directory gives it in
several sizes, `resolve/artwork-sizes` lists them with their widths, e.g.
for the `srcset` of an `img`, so that a browser loads the smallest that
fits:

```clojure
(resolve/artwork-sizes (first (:results (directory/search! "podcasting 2.0"))))
;; => [{:href "https://is1-ssl.mzstatic.com/…/100x100bb.jpg" :width 100}
;;     {:href "https://is1-ssl.mzstatic.com/…/600x600bb.jpg" :width 600}]
```

To colour a page by its artwork, as Apple Podcasts does, give
`image/colors` the bytes of the image. It returns the colours of a UI,
and in ClojureScript a promise of them:

```clojure
(image/colors (:body (http/send! {:url (:artwork show-page) :as :bytes})))
;; => {:background  "#a90000"   ; for the artwork of Podcasting 2.0
;;     :text        "#ffffff"
;;     :accent      "#fefefe"
;;     :accent-text "#000000"
;;     :complement  "#00a9a9"
;;     :seed        "#a90000"
;;     :palette     ["#a90000" "#fefefe" "#bd3e3a" "#cf706c" "#e0a09e"]}
```

The background is the colour that most of the image has, and the text
is black or white, whichever reads better on it. The accent is the next
colour of the image that stands apart from the background enough for a
control, or else the complement of the background, or else the text
colour. The seed is the first colour of the image that has a hue, for an
app that derives its colours from one, e.g. by turning its hue; it's nil
for an image of greys. For one of them alone, there's
`image/dominant-color`, `image/palette`, `image/seed-color`,
`image/complementary-color` and `image/contrast-color`.

The platform decodes the image: ImageIO on the JVM and the browser in
ClojureScript, where you send the request through the proxy. Node has no
decoder, so the colours are nil there.

To keep the chapters files and transcripts on the device, set `:cache`
in the config to a document store of like-minded, e.g. one in IndexedDB.
A file it keeps is asked for again with its ETag, so an unchanged one
costs next to nothing, and it's read from the store when the request
fails, e.g. offline.

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

The facts of a show are in its view too, e.g. its `:categories`,
`:language` and `:copyright`, the `:update-frequency` that it states, and
its `:trailers`, the newest first. A `:license` from the namespace's list
of licenses comes with its name and the URL of its terms. For a show that
states no schedule, `schedule/cadence` estimates from its feed how often
it publishes, and `resolve/location-url` gives a map of each of its
`:locations`.

Links and images in the notes are made absolute against the web page of
the episode or the show, and a link written as a bare domain, as many
feeds do, gets `https://`. To do it your own way, give `:notes-url-fn` a
function of the page and the URL, which returns the URL to keep or nil.

To make the times in the notes, e.g. 12:34, play the episode from there,
give `:timestamp-fn` a function of the seconds and the text of a time.
It returns the Hiccup in its place, or nil to leave the text, e.g.
`(fn [seconds text] [:a {:href (str "#t=" seconds)} text])`. A time in a
link, or past the end of the episode, stays text.

The headings of the notes start wherever their writer chose, often at h1.
To fit them into the outline of your page, give `:heading-level` the
level that the highest of them takes, e.g. 3 when the page's own headings
are h1 and h2. The others keep their distance below it.

A link in the notes leaves your app, which stops what plays in a page
that holds the player. To open such links in a new tab, give `:link-fn`
a function of each link that returns it with a `:target`, e.g.
`(fn [[tag attrs & children]] (into [tag (assoc attrs :target "_blank")] children))`.
Tell a screen reader that it opens a new tab with `:aria-describedby`,
rather than with text in the link, since the text of the notes, and the
chapters that it implies, has the text of the links too.

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
podcast-clj can read. It returns nil when there is none, or when the
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

To link to the original, use the `:url` of a comment or an author, which
everything but Nostr has. On Bluesky, it's a page on bsky.app, and
`atproto/post-url` gives the page on another web app, e.g. `(atproto/post-url
(:id reply) {:origin "https://deer.social"})`. A comment's `:counts` are its
replies, reposts, likes and quotes, as far as its server counts them. On
Nostr, they're the likes and reposts that the relays gave, one for each
author.

A fetch reads for a minute at most, and 100 comments at a time. To show
something sooner, give it `:max-seconds 5`, show what it read, and fetch
the same thread again to read more. A server that failed for a while is
asked again on the next fetch. To see new comments, start again from
`comments/thread`, and compare the two readings with `comments/changes`,
which gives the comments `:added`, `:edited` and `:deleted` since, e.g.
for a notification of new replies.

Some fediverse servers answer only signed requests, e.g. Mastodon with
authorized fetch, and GoToSocial. When the root post is on such a server,
`fetch!` reads the thread through its Mastodon API, which only has the
replies that server knows, and leaves out the replies on such servers. In
a browser, the podcast server's proxy can sign for you, as its
[README](../server/README.md#comment-threads-on-servers-that-ask-for-signatures)
says. Elsewhere, sign with
[httpsig-clj](https://github.com/simongray/httpsig-clj) and the key of an
actor that you publish:

```clojure
(def send-signed!
  (httpsig/sender comments/send! actor-key
                  {:signers [cavage/sign httpsig/sign]}))

(comments/fetch! thread {:send send-signed!})
```

This signs with draft-cavage, which every fediverse server takes, and
signs again with RFC 9421 for a server that refuses it. A server checks
the signature by fetching the actor and looking up its account with
WebFinger, on port 443 of the actor's domain.

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
pages, so a mention has no link unless your `:link-fn` returns one, such
as its `:uri`, a `nostr:` URI that opens a Nostr app on the device.

### Post on Nostr

A listener can comment, reply and react on Nostr with their own key,
through a signer. Make the event, and post it to the relays of the
thread:

```clojure
(def signer
  (signer/local (signer/secret-key nsec)))  ; nsec1… that the listener gave

(nostr-comments/post! thread
                      (nostr-comments/comment-event thread "Great episode!")
                      signer)
;; => {:event {…} :relays {"wss://relay.fountain.fm" {:accepted? true} …}}
```

The other events are `reply-event` to a comment, `reaction-event` and
`delete-event`. A comment on an episode is of kind 1111 of NIP-22, or of
kind 1 with `{:kind 1}`, as Fountain writes it. To see a post in the
thread, fetch the unread thread again.

There are three kinds of signer:

- `signer/local` holds the key in the app. Make a new key with
  `signer/generate-key!`, and store it as you would a password. The
  listener can keep it, or take it to another app, as `signer/nsec`.
- `signer/nip07` signs with a browser extension such as Alby or nos2x, in
  ClojureScript, and the page never sees the key.
- `signer/remote` asks a remote signer of NIP-46, such as nsec.app, to
  sign. Read the `bunker://` URI that the listener gives with
  `signer/parse`, add a key of the app as `:client-key`, and keep both.
  Then `signer/connect!` once, which returns the listener's public key.

A remote signer can ask the listener to approve a request on a web page
first, and the answer is then `:nostr/signer-approval` with the `:url`.
Show the address of the page before you open it, and ask again once the
listener approved. Only the `bunker://` URI is supported, since the
`nostrconnect://` URI needs a subscription that waits for the signer.


## Play episodes

The profile holds the queue and the progress, and you control the media
element. Send each event of the media element to `library/step`, and do
the effects that it returns beside the library:

```clojure
(swap! lib library/update-profile profile.queue/enqueue id)

(defn step!
  "Run the player `event` through the library, and give its effects."
  [event]
  (let [{:keys [library effects]} (library/step @lib event)]
    (reset! lib library)
    effects))

(step! [:progress id 1234 3600])  ; => []
(step! [:skip id :forward])
;; => [[:seek id 1264]]
(step! [:ended id])
;; => [[:play next-id]]
```

The effects are `[:seek id position]`, `[:play id]`, `[:stop id]`,
`[:played id]` and `[:nothing-next id]`. In ClojureScript, a handler can
look like this:

```clojure
(defn apply-effects!
  "Do the `effects` of a step on the media element `audio`."
  [audio effects]
  (doseq [[kind id position] effects]
    (case kind
      :seek         (set! (.-currentTime audio) position)
      :stop         (.pause audio)
      :play         (play-episode! audio id)
      :played       nil
      :nothing-next nil)))
```

To start an episode, `library/playback` gives what the player needs: the
file, where the listener left it or the end of its intro, the show's
rate, the title and artwork, the chapters, a transcript for captions, and
the metadata of the Media Session, which a browser shows on the lock
screen.

The media element and the page's Media Session are set by
[play-nice](https://github.com/simongray/play-nice), a library that you
add to your app yourself, since podcast-clj doesn't depend on it:

```clojure
dk.simongray/play-nice {:mvn/version "0.3.0"}
```

What `playback` returns goes as it is to `source!` of its `media`
namespace, here as `media-element`, which loads the file at the position
and rate. With its `session` namespace, the buttons on the lock screen
and the headphones send their events through `library/step` too:

```clojure
(defn play-episode!
  "Start the episode `id` of the library in the media element `audio`."
  [audio id]
  (let [{:keys [metadata] :as playback} (library/playback @lib id)
        press! (fn [details]
                 (run! #(apply-effects! audio (step! %))
                       (media-session/events @lib id (.-currentTime audio) details)))]
    (media-element/source! audio playback)
    (session/metadata! metadata)
    (session/actions! (zipmap (media-session/actions @lib id)
                              (repeat press!)))
    (.play audio)))
```

The media element says where the episode is now, and the profile where
the listener left it, so report the position at the moments that
matter, and write the library each time: as the episode pauses, seeks,
moves to a chapter or ends, as it reaches an outro that the listener
skips, as the page is hidden, and at least every 30 seconds while it
plays, since `profile/listened` counts a longer gap as a pause. A write
of the library takes a moment, so a page that writes each move as it
happens keeps it over a reload, and needs no copy of it elsewhere.

The lock screen follows the media element by itself, so it shows how far
the episode has come. The next and previous buttons go to the chapters
of the episode by default. The listener's preference
`:track-buttons` can send them to the episodes of the queue instead,
with `:queue`, or to the queue once no chapter is left, with
`:chapters-first`. For buttons of your own, the events
`[:next id]` and `[:previous id]` of `library/step` skip to the next
episode of the queue and go back to the one heard before.

With the listener's preference `:continuous?`, an episode that ends with
nothing else queued is followed by the first unplayed episode of its
show in its listening order: the next of a serial show, and the newest
of an episodic one. When nothing plays next, the effects of
`library/step` have `[:nothing-next id]`, so you can play an episode of
your own choosing with `[:play id]`, e.g. the first that
`library/recommend` finds like the one that ended. A sleep timer still
stops the player. After a pause, e.g. a call, `playback`
starts a few seconds before where the listener left off, by the
preference `:rewinds`.

The file is the downloaded one when the library's record of the files
has it, as the next section shows, or when the config's
`:local-file-fn` gives one for the episode's id, e.g. an object URL. Else
it's the enclosure. Once `library/read-files!` has read the episode's
chapters file, give its result to `playback` as `:read`, and the
metadata has those chapters too.

To pick the file to play yourself, use `resolve/enclosure`, e.g. with
`{:types ["audio/opus" "audio/mpeg"] :max-bitrate 64000}`. To keep to
audio when a show also offers video, add
`:playable-pred resolve/audio?`, which leaves out a stream that doesn't
say what it holds. The listener chooses between the audio and the video
of an episode that offers both with the preference `:video?`, or the
show's setting of that name, and `library/episode` and `library/plan`
follow it.

To keep to what the browser can play, e.g. Opus or a video codec, ask
it about each enclosure. The `media.capabilities` namespace of play-nice asks
Media Capabilities, and `canPlayType` where that says no, about what
`resolve/decoding-configuration` gives:

```clojure
(defn playable-pred
  "A predicate of an enclosure by the answers `infos` of the browser, which
  takes one whose type says nothing of its format as playable."
  [infos]
  (fn [enclosure]
    (if-let [configuration (resolve/decoding-configuration enclosure)]
      (capabilities/playable? (infos configuration))
      true)))

(-> (capabilities/decoding-infos!
     (keep resolve/decoding-configuration (resolve/enclosures item)))
    (.then (fn [infos]
             (resolve/enclosure item {:playable-pred (playable-pred infos)}))))
```

A file without a type, or with one such as `application/octet-stream`,
is almost always an MP3, so it counts as playable. If the browser can't
play it after all, the audio element fires an error when it starts.

For a page of the queue, `library/queue` gives its episodes, the current
one first, each with its title, show, artwork and position, and the
seconds left of each and of them all. To change it, give
`library/update-profile` the functions of `profile.queue`: `enqueue` to
add an episode at the end, `play-next` to put it after the current one,
`move` to place it, and `dequeue` to take it out.

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
on every device. The library keeps only that episode of the show, and
doesn't check the feed again, so the show's later episodes never count
as new. To take it out again, use `library/remove-episode`, which leaves
the queue as it is. And `library/remove-played-episodes` takes out every
such episode that the listener played, from the queue and the playlists
too, but keeps those with a star or another mark.

A show's live streams, its `podcast:liveItem`s, play by their ids as its
episodes do. The function `library/live-items` lists those of the
subscriptions, the ones on air first, then the ones to come, and a feed
is checked again at the start and the end of each one to come.

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
(library/plan @lib)
;; => {:download [{:id [guid "ep-3"] :url "https://…" :reason :rule …}]
;;     :delete   [{:id [guid "ep-1"] :bytes 50117331 :reason :played}]
;;     :waiting  []}
```

The plan says which episodes to download and which files to delete, by
the library's record of the files on this device. Downloading, deleting
and choosing when to do it are up to you, since podcast-clj knows nothing
about the network or the battery. Make a plan after each refresh, and
when the listener plays or queues an episode.

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
rule in `downloads/default-rule`. To download across all shows, name
saved filters of the profile in `:filters`, with the most episodes of
each, e.g. `{"Unplayed" 5}`, and their episodes come after the queue.

To follow the plan:

- Download each file of `:download` in order. Record the start with
  `library/start-download`, and the end with `library/finish-download`
  and the `:path` and `:bytes` of the file.
- Delete each file of `:delete`, and record it with `library/remove-file`,
  which keeps its entry so that it isn't downloaded again.
- Show the listener what's in `:waiting`, which didn't fit within
  `:max-episodes` or `:max-bytes`, or was a file above `:max-file-bytes`.
  Stop a download that runs past `:max-file-bytes` yourself, since a feed
  can give the wrong length.

```clojure
(defn download!
  "Download the `entry` of a plan with `save!`, your own function that
  writes the file at a URL to the device and gives its path and bytes."
  [entry save!]
  (swap! lib library/start-download entry)
  (let [{:keys [path bytes]} (save! (:url entry))]
    (swap! lib library/finish-download (:id entry) {:path path :bytes bytes})))
```

When a download fails, record it with `library/fail-download`, with the
bytes that came and the ETag, Last-Modified and Date of the answer. The
plan lists it again, at once the first time and later after each failure
that follows. An entry of the plan with `:received` goes on from those
bytes. Send its `:headers` with the request, and `downloads/resume-result`
says what the answer means: append its body, replace the bytes with it,
keep them as the whole file, or start over.

Once a download has finished, `library/playback` plays the file. To read
what the file says about itself, e.g. its chapters, give `media/read!` a
fetcher of the file as `:fetch`: `ranges/file-fetcher` of its path on the
JVM, or `ranges/blob-fetcher` of a File or a Blob in a browser. Besides
the chapters, the duration and the artwork, the result has the file's
`:tags`, e.g. its title and artist, and the `:bitrate`, `:sample-rate`
and `:channels` of its audio.

A file that the listener marks with `library/keep-file` stays whatever
the rules say, and `downloads/usage` of `(:files @lib)` returns the
episodes and bytes of each show on the device, e.g. for a storage screen.
The record is saved with the library, but never synced, since each device
has its own files. To keep a record of your own instead, give it to
`plan` as `files`, e.g. `{[guid "ep-1"] {:bytes 50117331 :at #inst
"2026-09-09"}}`. For rules that only apply to this device, give `plan` the
rules as `:rules`. To choose among the files of an episode, e.g. audio
alone, give it the options of `resolve/enclosure` as `:enclosure`. The
plan only has files that `resolve/downloadable?` takes, so never an HLS
stream or a torrent. A rule written by a newer version of podcast-clj
does nothing until this version understands it, and `downloads/problems`
says why.

The plan deletes the files of a show that the listener left, but for
those that the rules keep and the episodes added on their own, unless
the rules have `:delete-unsubscribed? false`. To name a file, give
`downloads/file-name` the entry of the plan and the title of the episode.
It returns a name that every file system takes, from the letters of the
title, a digest of the episode's id and the extension of the file, e.g.
`Episode-274-Vibe-Cloning-c71b5f4a.mp3`.

In a browser, a host rarely lets a page read an episode, so save it
through your server. Mount wary-fetch's `proxy/stream-handler`, which
passes the file on as it arrives, and send the request through it with
`(proxy/sender "/media")` as `:send` and a `:sink` that writes the file,
e.g. to the origin private file system. The podcast server serves such a
route at `/media`, for the files of the listener's episodes alone. In
Chromium, Background Fetch goes on after the page closes: give
`registration.backgroundFetch.fetch` the URL of the route, e.g.
`/media?url=…`, and store the response in the service worker.


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

A show that the listener subscribed to by its URL has only the ids of its
feed, so `library/identify!` asks the directories for the rest: the
Podcast Index by its guid or its URL when the config has the
credentials, and Apple by its title. The ids stay in the library, and
the views of the show and its episodes link to it in many more apps:

```clojure
(swap! lib library/merge (:library (library/identify! @lib opts)))
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

- `"a phrase"`, whose words must come in a row, though in the notes they
  can be anywhere, since the index holds the notes without the positions
  of their words
- `OR` or `|` between words, `NOT`, `!` or `-` before one, and parentheses
- `word~` for a word with typos
- `title:word` or `notes:word` for one field

The docstring of `search.queries/parse` has the rest. The index takes
about a kilobyte per episode, so 50 shows with 28,000 episodes take
about 30 megabytes. A result holds what a list of results shows, and its
id names the show and the episode. To leave the index out, e.g. on a
watch, give `:search? false` in the config.

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

To show the matches of a result, cut a snippet of its text with
`library/snippet`. It takes the notes when they match, since a list of
results shows the title anyway, and `:width` sets the number of
characters, 160 by default:

```clojure
(def q "\"podcasting 2.0\"")

(library/snippet @lib (first (library/search @lib q)) q)
;; => {:field :description
;;     :parts [{:text "A look into the future of podcasting and how we get to "}
;;             {:text "Podcasting 2.0" :match? true}
;;             {:text "!"}]}
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


## Recommend episodes

To list the episodes of the library most like the one on an episode
page, e.g. under "Maybe you're also interested in…", give its id to
`library/recommend`:

```clojure
(library/recommend @lib [id] {:limit 5})
;; => [{:id [feed-guid "…"] :score 9.3 :stored {…} :feed {…} :item {…}
;;      :terms ["ukraine" "russia" "putin"]}
;;     …]
```

It looks for the words that set the episode apart, those that its title
and notes use twice and few other episodes use, and returns the unplayed
episodes that hold at least two of them, the most first. Each result's
`:terms` are those it holds, e.g. to tell the listener why it came up.
The names of the show's hosts, numbers and dates are left out, since they
link the episode to others by chance, and so are words in more than 8% of
the episodes in its language, e.g. ad text and function words. By
default, the results are episodes of other shows, in the languages of the
episode, and at most two of each show. For a list of them,
`library/listed` gives each id the row that `library/queue` gives its
episodes.

Without ids, it starts from the last ten episodes that the listener
heard, and the last weighs the most, e.g. for a list on the front page.
Such a list blends the topics of those episodes, so for a row of
"Because you listened to…" under each of them, call it once for each.

These options change what it returns:

- `:where`, criteria of `library/episodes` in place of `{:status
  :unplayed}`, e.g. `{:status :unplayed :duration [nil 1800]}` for those
  under half an hour
- `:same-show? true` for episodes of the same show too, `:same-language?
  false` for any language, and `:max-per-show` for another cap than two
- `:from`, criteria that the episodes to start from meet, e.g. `{:not
  {:explicit? true}}`, so that an explicit episode leads to none
- `:unlike`, the ids of episodes whose words count against, e.g. those
  that the listener rated low

For the page of a show that the library doesn't hold, e.g. one found in a
search, give its feed as `:feeds`, and the ids can name its episodes:

```clojure
(library/recommend @lib [(feed.guid/episode-id feed url item)]
                   {:feeds {(feed.guid/feed-guid feed url) {:feed feed :url url}}})
```

`library/any-meets?` takes `:feeds` too, e.g. to tell whether any of the
episodes to start from is explicit, or of an explicit show:

```clojure
(library/any-meets? @lib ids {:not {:explicit? false :show-explicit? false}}
                    {:feeds feeds})
```

The docstring has the rest, e.g. `:weight-fn` and the options of
`more-like-this` of drop-in-search, which finds the episodes.


## Organize the shows and episodes

The profile also holds how the listener organises shows and episodes,
and it syncs like the rest:

```clojure
(swap! lib library/update-profile
       (fn [profile at]
         (-> profile
             (profile/configure guid {:folder "News" :auto-queue? :next} at)
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
`profile.queue/playlist`. For a screen of the inbox, `library/inbox`
gives its episodes, the newest first, as `library/queue` gives those of
the queue. To show the size of the inbox on the app's icon, give
`(count (profile.queue/inbox profile))` to `navigator.setAppBadge` in a
browser, or to the platform's badge.

For a screen of the shows, `library/shows` gives each subscription in
the listener's order, with its title, its artwork, when its newest
episode came out, and how many of its episodes are unplayed, in
progress, played, in the inbox and queued. The shows are in the order of
their titles until the listener moves one with `profile/move-show`. The
settings `:custom-title` and `:order` of a show replace the title of its
feed and its listening order, on its page, in `library/episodes` and in
OPML.

To take an episode out of the lists without marking it played, hide it
with `profile/hide`. A show's setting `:hide-where` hides those of its
episodes that meet the criteria, e.g. `{:status :played}` hides the ones
heard, and `profile/unhide` shows one again. Both `library/episodes` and
the download rules leave the hidden episodes out unless the criteria name
`:hidden?`, e.g. `{:hidden? true}` lists them. To take an episode out of
the history, `profile/forget` drops its progress and its played mark on
every device, so it counts as never heard until the listener plays it
again.

To change many episodes at once, e.g. to clear the inbox or the queue,
`profile/mark-all-played`, `profile/mark-all-unplayed`,
`profile.queue/dismiss-all`, `profile.queue/enqueue-all` and
`profile.queue/dequeue-all` take the ids, and write them with one stamp.
To mark every episode of a show as heard, use `library/mark-show-played`.
A refresh never brings back an episode that the listener dismissed from
the inbox, so to undo a dismiss, `profile.queue/undismiss` and
`profile.queue/undismiss-all` put them back.

- A show is in one folder at most, but it can have many tags. Both go into
  OPML with `profile.opml/to-opml`, and `profile.opml/from-opml` reads
  them back, including the OPML of other apps.
- Overcast's extended export also holds the episodes that the listener
  played, started or deleted. Import its shows with `from-opml`, which
  leaves out those marked as left, refresh, and then
  `library/episodes-from-opml` takes the episodes over by the URLs of
  their media, and `library/to-opml` writes them out in the same form.
- Ratings are from 1 to 5 by default, and `:scale` takes another one, such
  as `{:worst 0 :best 1}` for thumbs. Each rating keeps its scale, so
  `profile.marks/rating` can give it in yours.
- A playlist can be written as a list feed of the podcast namespace with
  `profile.queue/to-list-feed`, so other Podcasting 2.0 apps can play it.
- The seconds heard of each show by month are in `profile/listened`, for
  statistics. Report the position at least every 30 seconds while an
  episode plays, or it isn't counted.

Besides what an episode is, its criteria can name its show, the show's
folder and tags, the queue and the state of its file on the device, e.g.
`{:where {:folder "News" :download :downloaded}}` for the downloaded
episodes of the shows in News. The docstring of `criteria/where` lists
them all. To read the episodes of some shows alone, e.g. for the page of
a show, give `library/episodes` their guids as `:feed-guids`.

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
those of [like-minded](https://github.com/simongray/like-minded), and the
profile they sync is a CRDT of
[mind-meld](https://github.com/simongray/mind-meld). [Syncing](sync.md)
explains why this is safe.

```clojure
(def creds {:username "simon" :password (secret/hide "app-password")})
(def root "https://cloud.example.org/remote.php/dav/files/simon/podcasts/")

(def cloud (webdav/store root {:credentials creds}))

(store/check! cloud)
;; => {:ok? true :checks [{:id :store/reachable …} …]}

(def backends
  [(library/backend cloud {:opml? true :app "my-app"})
   (library/backend (folder/store "/Users/simon/Dropbox/Podcasts")
                    {:app "my-app"})])

(swap! lib library/merge (:library (library/sync! @lib backends)))
(library/next-sync @lib)  ; when to sync again
```

A store can be a WebDAV collection, which like-minded reaches through
[webdav-clj](https://github.com/simongray/webdav-clj), or a folder that a
sync client such as Dropbox mirrors. The [providers
page](https://github.com/simongray/like-minded/blob/master/doc/providers.md)
of like-minded has the URLs and paths of some of them. With
`library/backend`, a store becomes the backend of a sync: `:app` names
your app in the documents, and `:opml?` also writes the subscriptions as
OPML for other apps.

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
(library/backend (encrypted/store cloud
                                  from-keychain
                                  {:except #{library/opml-name}})
                 {:opml? true :app "my-app"})
```

Each sync removes the tombstones that every device has seen, once they're
30 days old. A device that stopped syncing blocks this. Such devices are
listed by `crdt/lagging` of mind-meld, and `crdt/retire` removes one.

When the listener turns sync off on a device, call `library/leave!`
with the backends. It retires the device and writes that through each
backend, so the other devices stop waiting for it and delete its
document. A device that left can't sync under its id again, so to turn
sync back on, give the library a new id with `library/with-device`:

```clojure
(swap! lib library/merge (:library (library/leave! @lib backends)))

;; later, to sync again
(swap! lib library/with-device (str (random-uuid)) {:name "Phone"})
```

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
`gpodder.server`. Each serves one listener, so give each listener a
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
         #(library/backend (folder/store %) {:opml? true :app "my-app"})))
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
(library/document @lib)  ; => {:name "8f1c….edn" :body "{:version 2 …}"}

(.then (library/sync! @lib [(library/backend (folder/store (.-files input)))])
       #(swap! lib library/merge (:library %)))
```

The store of the files of an input has no `:put`, so its backend only
reads them. Check for the picker itself rather than for a browser, so
that the app uses it wherever it arrives. The [providers
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
(library/backend (webdav/store (str js/location.origin "/dav/")))
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
- Let the tabs change the library in the store, as [Share the library
  between tabs and workers](#share-the-library-between-tabs-and-workers)
  has it. Start each step from the library that the store holds, once
  the tab has read what the others wrote and written its own changes,
  and make the library that the step gives one more change, with
  `library/merge`. Then the other devices get only what the store holds,
  whose stamps are in the order that compaction needs.
  like-minded runs one step of a device at a time, and a step that
  begins while another runs does nothing, with `:sync/overlapping-step`.
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
`podcastindex/fetch-remote-value!`, which returns the block, nil or an
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

No wallet pays an invoice after its `:expires-at`, so check it with
`bolt11/expired?` before paying, and ask for a new one once it has
expired. With the listener's consent, the `:payer` option sends their
details to a service that takes them, e.g. `{:name "Ann"}`. When a wallet
doesn't say whether it paid, `lnaddress/verify!` asks the service, if the
invoice has a `:verify` URL:

```clojure
(bolt11/expired? (:invoice invoice) (time/now))  ; => false
(lnaddress/verify! invoice)  ; => {:settled? true :preimage "…"}
```

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

### Pay all the recipients at once

To pay a boost to every recipient, give `recipients/pay!` the wallet's
functions. It divides the amount as `value/payments` does, pays a node
with keysend and a Lightning address with an invoice, one at a time, and
returns an entry for each recipient:

```clojure
(defn record!
  "Record the `entry` of a payment in the profile of the library."
  [entry]
  (swap! lib library/update-profile profile.payments/record entry))

(recipients/pay! (:recipients paid) 1000 (assoc payment :group (str (random-uuid)))
                 {:keysend-fn webln/pay-keysend!
                  :invoice-fn webln/pay-invoice!
                  :sending-fn record!})
;; => [{:id "b7e1…/0" :recipient {…} :sats 990 :status :paid :preimage "…"}
;;     {:id "b7e1…/1" :recipient {…} :sats 10 :status :skipped :error {…}}]
```

The `:sending-fn` records each payment just before it goes to the wallet,
so record the entries of the result too. A payment that
`profile.payments/unsettled` still lists after a crash, or on another
device after a sync, may be paid, so ask the wallet about it rather than
paying it again. With NWC, the functions are `nwc/ask!` of the commands
above, and `:invoice-opts` holds the options of
`lnaddress/request-invoice!`, e.g. the `:comment` with the metadata.


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
PSP-1, Apple Podcasts, Spotify or YouTube. It returns the diagnostics of
the fetch, the parse and the validation in one list, and `diagnostics/verdict`
says whether the feed passes:

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
Apple, which keeps only the first of the items. YouTube makes a video of
each episode, so it takes only audio, and a title of at most 100
characters without HTML.

For a table of the diagnostics, `diagnostics/summarize` groups them by
kind, and `validate/location` writes the path of each as people read it:

```clojure
(validate/location [:rss/items 6 :rss/enclosure :url])
;; => "item 7 › enclosure › url"
```

To validate a feed that you've built rather than fetched, e.g. before you
publish it, give the map to `validate/validate`. The next section checks
the files that a feed links to, and adding the `:report` of
`check/check-files!` to the diagnostics gives a verdict on both.

The value blocks have checks of their own. Of these,
`recipients/problems` reports what would send a payment where the
podcaster doesn't mean it to go, e.g. fees of more than 100 percent or an
address of the wrong form, and `recipients/check!` asks each Lightning
address for its terms and its node for keysend.


## Check the files that a feed links to

To check a feed before you publish it, or one that a podcaster submits,
use `check/check-files!`. It fetches the media files, images, transcripts
and chapters files of the newest items, and tells whether they reach a
listener as the feed says. Give it the feed's URL to check the feed itself
too, and the requirements of PSP-1, Apple Podcasts, Spotify or YouTube as
`:requirements`:

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
`http/probe!` returns the same facts, e.g. the size of an episode before
downloading it.


## Count the downloads of a show

As a host, you count the downloads of your episodes from your server logs,
by the rules of the IAB. Read the lines of a log in the combined format of
Apache and nginx, or the extended format of the W3C, with `logs/requests`:

```clojure
(def requests
  (with-open [r (io/reader "access.log")]
    (doall (logs/requests (line-seq r)
                          {:base-url "https://cdn.example.com/"}))))
```

The `:base-url` turns the paths in the log into the URLs of the files. A
line that can't be parsed becomes a map with an `:error`. If nginx writes
X-Forwarded-For after the user agent, give `:extra [:forwarded-for]`. For
a log in another format, turn each line into a request map yourself:

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

A reading of the file gives the numbers of the threshold: its `:bytes`
are the size, and its `:media-start`, where the audio starts after the
ID3 tag, is the header:

```clojure
(let [{:keys [bytes media-start duration]} (media/read! episode-url)]
  (analytics/threshold {:size bytes :header media-start :duration duration}))
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

This section is for hosts. A feed that you build from your own data lacks
a few things that apps rely on, so fill them in with
`publish/with-defaults`, e.g. the self link, the `podcast:guid`, and a guid
and a media type for each episode. Serve it with `publish/response`:

```clojure
(def feed
  (publish/with-defaults show {:url "https://example.org/feed.xml"}))

(defn feed-handler
  "The Ring response to the `request` for the feed."
  [request]
  (publish/response request feed))
```

The response has an ETag, a Last-Modified and a Cache-Control, and it's a
304 when the app has the feed already, so a check of an unchanged feed
costs next to nothing. It names the feed's WebSub hubs in a Link header
too. With `:media-fn`, a lookup of what `media/read!` gave for each file,
`with-defaults` fills in the length and the duration of the episodes.

A long feed is slow to fetch, so split it with `publish/pages` into
documents linked as RFC 5005 describes:

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
its own, and other URLs for the archives as `:page-url-fn`, a function of
the page number.

To push changes through WebSub, name the hub in the feed and tell it
about each change:

```clojure
(def feed
  (update feed :atom/links conj {:rel "hub" :href "https://hub.example.org/"}))

(websub/publish! ["https://hub.example.org/"] "https://example.org/feed.xml")
```

To announce a change through Podping, give `podping/notify!` the token
that podping.cloud gave you. It returns the `:status` of the answer, or an
`:error` when the service didn't take it:

```clojure
(podping/notify! token "https://example.org/feed.xml")
;; => {:status 200}
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
lists, and point the feed at the copies with `publish/imported`:

```clojure
(def copies
  (into {}
        (for [{:keys [url]} (check/files feed {:max-items :all})]
          [url (copy-file! url)])))

(def imported
  (-> (publish/imported feed #(get copies % %))
      (publish/with-defaults {:url "https://example.org/feed.xml"})
      (publish/moved "https://example.org/feed.xml")))
```

Here `copy-file!` is your own function, which copies a file to your server
and returns its new URL. The episodes and the show keep their guids, so
the apps don't list them twice, and the links of the old host go. A feed
that `resolve/locked?` says is locked throws, since its owner has to
unlock it at the old host first. Then have the old host answer the old
URL with a 301 redirect to yours, and keep the redirect and the
`itunes:new-feed-url` of `moved` for at least four weeks, as Apple asks.
To move a show away from a host that can't redirect, serve the old feed
with `moved` instead.

An MP3 or M4A file can hold its chapters with an image for each, and
`media/read!` gives the images as data URLs. JSON Chapters says that an
image is a URL, so write the images as files before you serve the
chapters file:

```clojure
(defn image-url
  "The URL of the file that the image at `url` is written to, if it's a
  data URL, or else `url`."
  [url]
  (if-let [{:keys [type data]} (image/from-data-url url)]
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


## Test your app

A test of your app can run a library with no network and no clock. The
sender of `testing/sender` answers each request from a map of URLs to
responses, with a 304 when the request names the ETag of its response,
and `testing/feed` writes the XML of a feed with as many episodes as you
ask for. Give each call the sender as its `:send`, and a clock that
stands still as its `:now-fn`:

```clojure
(def url "https://example.com/feed.xml")

(def responses
  (atom {url {:body    (testing/feed {:episodes 3})
              :headers {"ETag" "\"v1\""}}}))

(defn send
  "Answer a request from the responses as they are now."
  [request]
  ((testing/sender @responses) request))

(def opts
  {:send   send
   :now-fn (constantly #inst "2026-02-01")})

(def lib
  (atom (library/make "phone")))

(swap! lib library/subscribe url (podcast/fetch! url {:send send})
       (assoc opts :auto-queue? true))

;; a new episode comes out
(swap! responses assoc url {:body (testing/feed {:episodes 4})})
(swap! lib library/merge
       (:library (library/refresh! @lib (assoc opts :at #inst "2026-02-02"))))

(profile.queue/queue (:profile @lib))
;; => [[feed-guid "https://example.com/4"]]
```

For sync, a backend over `store/memory` keeps the documents in an atom,
and two libraries of different devices sync through one such store.
`downloads/plan` takes the files of a device as data, so a test of the
downloads needs no files either.


## The namespaces of the examples

The namespaces outside `dk.simongray.podcast` are those of the libraries
that [Before you start](#before-you-start) lists, those of play-nice,
which [Play episodes](#play-episodes) adds, and those of httpsig-clj, which
[Show the comments on an episode](#show-the-comments-on-an-episode) adds.

```clojure
(ns my.app
  (:require [clojure.java.io :as io]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.ciff :as ciff]
            [dk.simongray.drop-in-search.queries :as search.queries]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.httpsig :as httpsig]
            [dk.simongray.httpsig.cavage :as cavage]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.store.encrypted :as encrypted]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.like-minded.store.webdav :as webdav]
            [dk.simongray.like-minded.store.webdav.server :as webdav-server]
            [dk.simongray.like-minded.transit :as transit]
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.play-nice.media :as media-element]
            [dk.simongray.play-nice.media.capabilities :as capabilities]
            [dk.simongray.play-nice.session :as session]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.analytics :as analytics]
            [dk.simongray.podcast.analytics.logs :as logs]
            [dk.simongray.podcast.analytics.op3 :as op3]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.media-session :as media-session]
            [dk.simongray.podcast.app.show :as show]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.atproto :as atproto]
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
            [dk.simongray.podcast.feed.local :as local]
            [dk.simongray.podcast.feed.publish :as publish]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.feed.repair :as repair]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.feed.search :as feed.search]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.gpodder :as gpodder]
            [dk.simongray.podcast.nostr.signer :as signer]
            [dk.simongray.podcast.podping :as podping]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.payments :as profile.payments]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.testing :as testing]
            [dk.simongray.podcast.util.bytes.ranges :as ranges]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.podcast.value.bolt11 :as bolt11]
            [dk.simongray.podcast.value.lnaddress :as lnaddress]
            [dk.simongray.podcast.value.metadata :as metadata]
            [dk.simongray.podcast.value.nwc :as nwc]
            [dk.simongray.podcast.value.recipients :as recipients]
            [dk.simongray.podcast.value.webln :as webln]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.proxy :as proxy]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.websub :as websub]))
```
