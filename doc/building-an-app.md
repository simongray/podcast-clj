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
`(.then (library/refresh! @lib) #(swap! lib library/merge %))`.

Each function returns the library along with what it did: `:effects`
after `step`, `:refresh` after `refresh!` and `:sync` after `sync!`. The
`:config` given to `make` holds the options that the other library
functions use, and the docstring of `app.library` lists them. When the
config holds functions, save the library as EDN without its `:config`,
and assoc the config back when you read it.

The parts of the library work with the namespaces they come from, e.g.
every function of `profile` takes `(:profile @lib)`. The profile is a
CRDT, so that devices can merge their changes without conflicts. Change it
only with the functions of `profile`, e.g.
`(swap! lib library/update-profile profile/enqueue id)`, and never with
`assoc` or `dissoc`.

Each change is stamped with its time, which the functions of
`app.library` read from the clock, the setting `:now`, so a test can fix
it. For an event that you handle later, e.g. in a background task, give
the time it happened as the option `:at`.

The examples call each namespace by the last part of its name, e.g.
`library` is `dk.simongray.podcast.app.library`, except `nostr-comments`
for `comments.nostr`. The examples run on the JVM. In ClojureScript, the functions that send
requests return promises, and [Namespaces](namespaces.md) marks the
functions that only exist on the JVM.


## What you build yourself

- Store the library, and the record of the files on the device.
- Play the media, as the effects of `library/step` say.
- Download and delete episode files, as `library/plan` says, when the
  network and the battery allow it.
- Show notifications and make payments.
- Draw the user interface.
- For a host, keep the server logs and the lists of user agents, data
  centres and bots that counting downloads needs.


## Send requests from a web page

Few feed hosts and directories let a page of another site read their
answers, so a page sends its requests through your own server. Mount the
proxy of `util.proxy` there, and give its sender as `:send`:

```clojure
;; on the server, as a route of Pedestal or any other Ring router
["/proxy" :any (proxy/handler) :route-name ::proxy]

;; in the page, in the config of the library
(library/make device-id {:config {:send (proxy/sender "/proxy")}})
```

Every function that sends requests then works in the page as it does on
the JVM. The proxy fetches only URLs on the public internet, and refuses
to be opened as a page, but it fetches for anyone who can reach it, so
put it behind your login.

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
or the language ends up under `:failed`:

```clojure
(def chart
  (directory/fetch-chart! {:category "Technology" :country "dk" :limit 10}))

(map resolve/title (:results chart))
(:failed chart)
;; => {:fyyd "fyyd has no chart by country" :gpodder "gpodder.net has no chart by country"}
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
which is only needed on the first fetch. The last argument holds the
settings of the subscription, as `profile/configure` takes them.

A failing network or server doesn't make `fetch!` throw. When it gets no
answer or no feed, the result has an `:error` diagnostic. A status such as
404 is an answer, so it comes back as `:status` without an error. Check
the result before you subscribe:

```clojure
(def result (podcast/fetch! url))
(:error result)  ; => nil, or e.g. {:id :fetch/unreachable :reason "…" …}
```

If the listener gives a show's website, a domain or a link from another app
instead of a feed, find the feeds with `podcast/find-feeds!`:

```clojure
(def found (podcast/find-feeds! "freakshow.fm"))

(map (juxt :title :url :found-by) (:feeds found))
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
credentials, use `http/split-credentials`. Wrap the password with
`secret/hide`, so it prints as `#<secret>`:

```clojure
(def creds {:username "simon" :password (secret/hide (from-keychain))})

(podcast/fetch! url {:credentials creds})
```

In a browser, give `fetch!` the `:send` of the proxy.

Without the library, `feeds/add` keeps the feed along with its ETag and
Last-Modified, and `profile/subscribe` adds the subscription. Every id in
the profile uses the guid that `feed/feed-guid` gives a feed. A feed can
declare the guid of another subscription and take it over, so check a new
feed with `profile/takeover` first.


## Keep the feeds current

```clojure
(swap! lib library/merge (library/refresh! @lib))

(:refresh @lib)
;; => {:at #inst "…" :added [[feed-guid item-guid] …] :moved {} :report []}
```

This fetches whatever is due: the lists that the listener follows, the
feeds of new subscriptions, and the feeds whose next check has come. The
new episodes go to the queue of a show with `:auto-queue?`, or to the
inbox with `:inbox?`. An episode that the listener already played or
removed on another device doesn't come back. A feed that moved for good
moves its subscription too, and a feed that failed stays as it was and
waits longer before the next try.

When to check a feed again depends on its update frequency, its `ttl` or
the interval between its episodes, as `resolve/next-check` reads them. The
setting `:cadence` changes the defaults, and `:credentials-of` gives the
credentials of a private feed by its URL, so no password is stored in the
library and none goes to another feed.

Podping tells which feeds have changed, so they can be fetched at once.
Keep the last block that you read and the writers, and poll every few
seconds:

```clojure
(defn podping-step!
  "Read the pings after the last result `polled`, and refresh the feeds of
  `lib` that they name."
  [lib polled]
  (let [{:keys [block writers]} polled
        polled (podping/poll! block {:writers writers})
        guids  (feeds/affected (:feeds @lib) (:pings polled))]
    (when (seq guids)
      (swap! lib library/merge (library/refresh! @lib {:guids guids})))
    polled))

(def polled (podping/poll! nil))  ; starts at the head of the chain
```

When no node answers, the result has an `:error` and the block it got to,
so the next poll continues from there.

A server can instead have the feeds pushed to it by a WebSub hub, which
needs a public callback URL. The result of `fetch!` has `:websub` when the
feed names a hub. Subscribe with a secret that nobody can guess, one for
each subscription, and handle the callback with `websub/handler`:

```clojure
(let [{:keys [hubs topic]} (:websub result)]
  (websub/call! (websub/request :subscribe (first hubs) topic
                                "https://app.example.net/websub/4f1c9e2a"
                                {:secret secret :lease-seconds 864000})))

(def callback
  (websub/handler
    {:wanted?  (fn [{:keys [mode topic]}] (= topic (pending mode)))
     :deliver! (fn [{:keys [topic body]}] (swap! inbox conj [topic body]))
     :secret   secret}))
```

The `:deliver!` function only gets feeds with the right signature. Return
from it quickly, and parse the feed afterwards. Renew the subscription
before the lease runs out, and keep polling on the normal schedule too,
since a hub can fail.


## Follow lists of shows

A network or a critic can publish a list of shows as OPML. When a listener
follows it, the shows that join the list become subscriptions:

```clojure
(swap! lib library/follow list-url (opml/parse body)
       {:title "Picks" :folder "Picks"})
```

After that, `refresh!` fetches the list again once a day. A show that
leaves the list stays a subscription, since the listener may still like
it. A show that the listener unsubscribed from doesn't come back, and a
show is recognised by its feed URL, so it isn't added twice. In the OPML
from `profile/to-opml`, the followed lists are includes.


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
from the feed.

The option `:except` leaves out the parts that a page doesn't need, e.g.
the rows of an episode list are much cheaper as `(library/episode @lib id
{:except #{:notes :text :chapters :links}})`.

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

Without the library, `show/view` and `episode/view` take a parsed feed
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
to pages of your app, render it with `comments/hiccup` and a `:link`
function:

```clojure
(defn comment-link
  "The URL in this app of the mention or hashtag `part` of a comment."
  [{:keys [type protocol id tag]}]
  (case type
    :mention (when id (str "/profiles/" protocol "/" (http/encode id)))
    :hashtag (str "/tags/" (http/encode tag))
    nil))

(comments/hiccup thread comment {:link comment-link})
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
pages, so a mention has no link unless your `:link` function gives one,
such as its `:uri`, a `nostr:` URI that opens a Nostr app on the device.


## Play episodes

The profile holds the queue and the progress, and you control the media
element. Send each event of the media element to `library/step`, and do
the effects it returns:

```clojure
(swap! lib library/update-profile profile/enqueue id)

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

To start an episode where the listener stopped, or after its intro, ask
`profile/resume-position`. To pick the file to play, use
`resolve/enclosure`, e.g. with `{:types ["audio/opus" "audio/mpeg"]
:max-bitrate 64000}`.

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
of `search/episodes`:

```clojure
(def show-rule
  {:download? true
   :episodes  {:where {:duration [600 nil]} :limit 3}})

(swap! lib library/update-profile
       (fn [state now]
         (-> state
             (profile/prefer {:downloads {:default      {:played 86400}
                                          :queue        3
                                          :max-episodes 50}}
                             now)
             (profile/configure guid {:downloads show-rule} now))))
```

Here a played file goes after a day, the first three episodes of the queue
are downloaded, and the show gets its three newest episodes of ten minutes
or more. Every key is in `downloads/default-rules`.

To follow the plan:

- Download each file of `:download` in order, and add it to `files` with
  the time as `:at` when the download starts.
- Delete each file of `:delete`, and keep its entry with `:removed?`, so it
  isn't downloaded again.
- Show the listener what's in `:waiting`, which didn't fit within
  `:max-episodes` or `:max-bytes`.

Set `:keep?` in the entry of a file the listener asked for, and no rule
deletes it. For rules that only apply to this device, give `plan` the
rules as `:rules`. A rule written by a newer version of podcast-clj does
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

With `:search? true` in its config, the library keeps a full-text index
of every feed:

```clojure
(library/search @lib "value for va" {:limit 10})
;; => [{:id [feed-guid item-guid] :score 7.1 :stored {:title "…" …}} …]
```

The last word counts as a prefix, so this works for search as you type.
The index of a show with 200 episodes takes about two megabytes, so on a
phone with many shows you may want to leave it off.

Without the library, build the index from the feeds yourself:

```clojure
(defn index-feeds
  "A search index of every item of `feeds`, a map of `{:url :feed}` entries."
  [feeds]
  (search/index (for [{:keys [url feed]} (vals feeds)
                      item (:rss/items feed)]
                  (search/item-document feed item {:url url}))))

(search/snippet (html/text (resolve/description item)) "value for va")
```

When a feed changes, update the index with `search/add` and
`search/remove`. To make transcripts searchable, give them to
`search/item-document`.


## Organize the shows and episodes

The profile also holds how the listener organises shows and episodes,
and it syncs like the rest:

```clojure
(swap! lib library/update-profile
       (fn [state now]
         (-> state
             (profile/configure guid {:folder "News" :inbox? true} now)
             (profile/tag guid "Daily" now)
             (profile/star id now)
             (profile/bookmark id 754 "The interview starts" now)
             (profile/rate-episode id 5 now)
             (profile/clip id 754 42.5 "The best answer" now)
             (profile/add-to-playlist "Road trip" id now)
             (profile/save-filter "Starred"
                                  {:where {:starred? true :status :unplayed}
                                   :order :newest}
                                  now))))
```

Each has a function to read it back, such as `profile/folders`,
`profile/tagged`, `profile/inbox`, `profile/starred`, `profile/bookmarks`,
`profile/clips` and `profile/playlist`.

- A show is in one folder at most, but it can have many tags. Both go into
  OPML with `profile/to-opml`, and `profile/from-opml` reads them back,
  including the OPML of other apps.
- Ratings are from 1 to 5 by default, and `:scale` takes another one, such
  as `{:worst 0 :best 1}` for thumbs. Each rating keeps its scale, so
  `profile/rating` can give it in yours.
- A playlist can be written as a list feed of the podcast namespace with
  `profile/to-list-feed`, so other Podcasting 2.0 apps can play it.
- The seconds heard of each show by month are in `profile/listened`, for
  statistics. Report the position at least every 30 seconds while an
  episode plays, or it isn't counted.

A saved filter holds the options of `library/episodes`. Another device may
have saved it with a newer version, so check it with `search/problem`
first:

```clojure
(let [criteria (get (profile/filters (:profile @lib)) "Starred")]
  (when-not (search/problem criteria)
    (library/episodes @lib criteria)))
```


## Sync the devices of a listener

Each device writes its whole profile as one document to a store, and
reads the documents of the other devices. [Syncing](sync.md) explains why
this is safe.

```clojure
(def creds {:username "simon" :password (secret/hide "app-password")})
(def root "https://cloud.example.org/remote.php/dav/files/simon/podcasts/")

(store/check! (webdav/store root {:credentials creds}))
;; => {:ok? true :checks [{:id :store/reachable …} …]}

(def backends
  [(webdav/backend root {:credentials creds :opml? true :app "my-app"})
   (file/backend "/Users/simon/Dropbox/Podcasts" {:app "my-app"})])

(swap! lib library/merge (library/sync! @lib backends))
(library/next-sync @lib)  ; when to sync again
```

A store can be a WebDAV collection or a folder that a sync client such as
Dropbox mirrors. [Sync reference](sync-reference.md) has the URLs and
paths of some providers. Sync when the app starts, when it goes to the
background, and after a series of changes. A button in the settings
screen can test a store with `store/check!`.

To keep the documents encrypted on the server, wrap the store with
`encrypted/backend`, and use `:except` to leave the subscription list
readable for other apps:

```clojure
(encrypted/backend (webdav/store root {:credentials creds})
                   {:passphrase from-keychain
                    :opml?      true
                    :except     #{store/opml-name}})
```

Each sync removes the tombstones that every device has seen, once they're
30 days old. A device that stopped syncing blocks this. Such devices are
listed by `crdt/lagging`, and `crdt/retire` removes one.

To keep in step with AntennaPod, Kasts and other apps of the gpodder API,
add a gpodder server as a backend. For the GPodder Sync app of Nextcloud,
give `:api :nextcloud`:

```clojure
(defn feeds-by-url
  "The parsed feeds of `library` by their URLs."
  [library]
  (into {} (map (juxt :url :feed)) (vals (:feeds library))))

(gpodder/backend "https://gpodder.net"
                 {:credentials creds
                  :identities  #(gpodder/identities (feeds-by-url @lib))})
```

To serve a store from your own Clojure server, mount the Ring handler of
`sync.webdav.server`, and for the gpodder API that of
`sync.gpodder.server`. To sync through a service that has no store
yet, write one: [Sync reference](sync-reference.md) gives the contract,
a map of two to four functions.

### Sync through a folder in a browser

In a browser that can pick a folder, give its handle to `file/backend`.
The browser asks the listener for permission, and only during a click:

```clojure
(defn pick-folder!
  "Ask the listener for a sync folder, and give a promise of its backend."
  []
  (.then (js/showDirectoryPicker #js {:mode "readwrite"})
         #(file/backend % {:opml? true :app "my-app"})))
```

Keep the handle in IndexedDB for the next visit, since it can't go in
EDN. The permission can lapse between visits, so check it before a sync.
If it's `:prompt`, show a button that renews it:

```clojure
(file/query-permission! handle)    ; => a promise of :granted, :prompt or :denied
(file/request-permission! handle)  ; from a click
```

A browser without a folder picker can still sync through the folder by
hand. Let the listener save this device's document into the folder, and
load the folder with `<input type="file" webkitdirectory>`:

```clojure
(library/document @lib)  ; => {:name "8f1c….edn" :body "{:version 1 …}"}

(.then (library/sync! @lib [(file/backend (.-files input))])
       #(swap! lib library/merge %))
```

A backend over the files of an input only reads them. Check for the
picker itself rather than for a browser, so that the app uses it
wherever it arrives. [Sync reference](sync-reference.md#folders-in-a-browser-as-of-october-2026)
has what each browser can do, and the inspector's player in
`web/inspector/player.cljs` does all of the above.


## Pay value for value

The value block of an episode comes from `resolve/value`, the recipients
at a position from `value/recipients-at`, and `value/payments` divides an
amount between them. What goes with a payment is one map, however it's
sent:

```clojure
(def block (resolve/value show item))

(value/payments (:recipients (value/recipients-at block 754)) 1000)

(def payment
  {:action    :boost
   :sats      1000
   :position  754
   :message   "Great episode"
   :feed-guid guid
   :item-guid (feed/item-guid item)
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
`metadata/header` at a URL of your own and naming that URL:

```clojure
(def invoice
  (lnaddress/request-invoice!
    "ann@getalby.com" 50000
    {:comment (fn [{:keys [comment-length]}]
                (metadata/payment-comment payment url comment-length))}))
;; => {:invoice "lnbc500n1…" :msat 50000 :comment "rss::payment::boost …"}

{"x-rss-payment" (metadata/header payment)}  ; the header to serve at url
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
;; => {:methods ["pay_invoice" "pay_keysend" …] :encryption ["nip44_v2"]}

(nwc/ask! connection (nwc/pay-keysend (assoc payment :sats 50) recipient)
          {:info info})
;; => {:method "pay_keysend" :preimage "…" :fees-msat 1000}

(nwc/ask! connection (nwc/pay-invoice (:invoice invoice)) {:info info})
;; => {:method "pay_invoice" :preimage "…" :proven? true :fees-msat 1000}
```

The text to store comes from `nwc/uri`. When `:proven?` is false, the
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


## Check the files that a feed links to

To check a feed before you publish it, or one that a podcaster submits,
use `check/check-files!`. It fetches the media files, images, transcripts
and chapters files of the newest items, and tells whether they reach a
listener as the feed says. Give it the feed's URL to check the feed itself
too, and the requirements of PSP-1 or Apple Podcasts as `:requirements`:

```clojure
(def result
  (check/check-files! feed {:url url :items 10 :requirements :apple}))

(diagnostics/summarize (:report result))
;; => ({:id :check/cors :level :warning :count 10 …}
;;     {:id :check/ranges :level :error :count 3 …} …)

(check/report (:files result) :psp-1)  ; other requirements, no new requests
```

Without `:read? true`, it only sends a HEAD request and asks for the first
bytes of each file. The limits on files, time and bytes are in
`check/default-limits`. If your server checks feeds that other people
submit, give it `:allow? http/public?`, so it never requests your own
network. For a single file, `http/probe!` gives the same facts, e.g. the
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
    {:user-agents (read-user-agents "user-agents-v2")
     :threshold   {episode-url threshold}
     :exclude?    data-center?}))

(frequencies (map :url (:downloads result)))            ; downloads per file
(count (distinct (map :listener (:downloads result))))  ; listeners
(frequencies (map :agent-name (:downloads result)))     ; apps
```

The requests that didn't count are under `:dropped`, by reason. Here
`data-center?` is your own predicate, built from lists of data centres,
VPNs and bots, as the IAB asks. A day is a calendar day in UTC, and
`:window :rolling` counts the 24 hours from a listener's first request
instead.


## Measure the downloads with OP3

OP3 counts downloads when a feed puts its prefix in front of each
enclosure, which `op3/with-prefix` does:

```clojure
(podcast/emit (op3/with-prefix feed))
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
so the same code counts both. If a page fails, `fetch-downloads!` returns
the pages before it with an `:error`, and a `:continuation-token` to read
on from.


## Serve the feed of a show

This section is for hosts. Write a feed with `podcast/emit`. A long feed is
slow to fetch, so split it with `publish/pages` into documents linked as RFC
5005 describes:

```clojure
(def documents (publish/pages feed {:kind :archived :page-size 100}))

(map :url documents)
;; => ("https://example.org/feed.xml"            ; the newest items
;;     "https://example.org/feed.xml?archive=2"  ; archives, newest first
;;     "https://example.org/feed.xml?archive=1")
```

The archives don't change when a new episode comes out, so apps fetch each
one only once. Give the URLs of your server as `:page-url`, a function of
the page number.

To push changes through WebSub, name the hub in the feed and tell it
about each change:

```clojure
(def feed
  (update feed :atom/links conj {:rel "hub" :href "https://hub.example.org/"}))

(websub/call! (websub/publish-request "https://hub.example.org/"
                                      "https://example.org/feed.xml"))
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
`(assoc-in (:apple check/requirements) [:artwork :sizes] [1400 4000])`.
