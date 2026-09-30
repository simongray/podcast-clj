# How to build a podcast app

This guide shows the functions that a podcast app calls, in the order that a
listener meets them. It assumes that you know Clojure and the basics of
podcast feeds. [Quickstart](quickstart.md) runs the first steps at a REPL.
The inspector in `web/` does the same steps, and you can read its code as
a working example.


## Before you start

An app keeps a listener's subscriptions, feeds, queue and progress in
one map, the library, and `app.library` keeps it whole:

```clojure
(def lib (library/make device-id now {:name "Phone" :settings {:languages ["da" "en"]}}))
(def lib (library/subscribe lib url (podcast/fetch! url {:max-pages 20}) now))
(library/refresh! lib now)                          ; => {:library … :added [id …] :moved {} :report []}
(library/episodes lib {:where {:status :unplayed}})  ; the episodes across the shows
(library/step lib [:progress id 1234 3600] now)     ; => {:library … :effects [[:seek id 1264]]}
(library/sync! lib [cloud] now)                     ; => {:library … :next-at #inst "…"}
(:player lib)                                       ; the player state, for every function of app.player
```

The library holds these values, each a value of another namespace, e.g.
`(:player lib)` is a player state that every function of `app.player`
takes. Each section below starts with the call of the library, and then
shows the functions below it.

- `:player`, the player state of `app.player`: the subscriptions, the
  queue, and the progress in each episode
- `:feeds`, the feeds of `app.feeds`: the parsed feeds, by feed guid,
  each with its URL, the validators of the last fetch and the time of the
  next check
- `:lists`, the parsed OPML lists that the listener follows, by URL, in
  the same way
- `:index`, the search index of `app.search`, when the settings say
  `:search? true`
- `:sync`, the cursors that `sync!` returns
- `:settings`, given once to `make`: the options that every step reads

The files on the device are the app's, and `plan` takes them. All of it
is EDN, and the app decides where to store it.

The examples use these aliases:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.analytics :as analytics]
         '[dk.simongray.podcast.analytics.op3 :as op3]
         '[dk.simongray.podcast.app.downloads :as downloads]
         '[dk.simongray.podcast.app.episode :as episode]
         '[dk.simongray.podcast.app.feeds :as feeds]
         '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.search :as search]
         '[dk.simongray.podcast.app.show :as show]
         '[dk.simongray.podcast.app.sync :as sync]
         '[dk.simongray.podcast.app.sync.encrypted :as encrypted]
         '[dk.simongray.podcast.app.sync.file :as file]
         '[dk.simongray.podcast.app.sync.gpodder :as gpodder]
         '[dk.simongray.podcast.app.sync.store :as store]
         '[dk.simongray.podcast.app.sync.webdav :as webdav]
         '[dk.simongray.podcast.comments :as comments]
         '[dk.simongray.podcast.comments.atproto :as atproto]
         '[dk.simongray.podcast.comments.nostr :as nostr-comments]
         '[dk.simongray.podcast.directory :as directory]
         '[dk.simongray.podcast.directory.links :as links]
         '[dk.simongray.podcast.directory.podcastindex :as podcastindex]
         '[dk.simongray.podcast.feed :as feed]
         '[dk.simongray.podcast.feed.check :as check]
         '[dk.simongray.podcast.feed.resolve :as resolve]
         '[dk.simongray.podcast.format.chapters :as chapters]
         '[dk.simongray.podcast.format.html :as html]
         '[dk.simongray.podcast.format.media :as media]
         '[dk.simongray.podcast.format.opml :as opml]
         '[dk.simongray.podcast.format.transcript :as transcript]
         '[dk.simongray.podcast.updates.podping :as podping]
         '[dk.simongray.podcast.updates.websub :as websub]
         '[dk.simongray.podcast.util.bytes :as bytes]
         '[dk.simongray.podcast.util.http :as http]
         '[dk.simongray.podcast.util.nostr :as nostr]
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

The function `directory/trending!` gives the charts of the directories, and
the charts of one category with `:category`. The category is a name of the
categories of Apple Podcasts, and `directory/apple-genres` has the names:

```clojure
(def chart (directory/trending! {:category "Technology" :country "dk" :limit 10}))
(map resolve/title (:results chart))
(:failed chart)  ; => {:fyyd "fyyd has no chart by category"}
```

The names of the top categories work for Apple Podcasts, the Podcast
Index and gpodder.net. The Podcast Index has no name for a category of
the second level, such as Tech News. For such a category, give a map
with a category for each source:

```clojure
(directory/trending! {:category {:apple "Tech News" :podcastindex "Technology,News"}
                      :credentials credentials})
```

In a browser, build the request with `directory/request`, send it, and read
the answer with `directory/response`. The CORS policy of each directory
decides if a web page can send the request.


## Subscribe to a show

With the library, one call: `(library/subscribe lib url (podcast/fetch!
url {:max-pages 20}) now {:auto-queue? true})`. It keeps the feed with its
validators, adds the subscription with its settings, and indexes the
feed when the library searches. Below it, fetch the feed, keep the
validators for the next fetch, and add the subscription to the player
state:

```clojure
(def r (podcast/fetch! url {:max-pages 20}))  ; {:status 200 :feed … :etag … :last-modified … :url …}
(:error r)                                     ; a diagnostic, when the fetch got no answer or no feed
(def show (:feed r))
(def guid (feed/feed-guid show url))

(def device-id (str (random-uuid)))  ; make it once per installation, and keep it

(def state
  (-> (player/state device-id)
      (player/identify {:name "Phone"} now)
      (player/subscribe guid {:url url :title (resolve/title show)} now)
      (player/configure guid {:auto-queue? true :skip-intro 20 :skip-outro 45} now)))

(def feeds (feeds/add {} url r now))  ; the app's feeds, by guid, with the validators
```

The function `feed/feed-guid` gives the identity of the feed: its
`podcast:guid`, or the guid that the specification derives from the URL. All
ids in the player state use this guid. The `:url` of the result is the final
URL after redirects.

Some feeds come in pages, and the first page holds only the newest episodes.
The option `:max-pages` makes `fetch!` read the older pages too, and join
them into one feed. Use it on the first fetch. Later fetches need only the
first page.

A call of `fetch!` throws nothing for what the network or the server does.
When the request fails, the result has an `:error` and no status. The
`:error` is a diagnostic, `:fetch/unreachable`, and its `:reason` tells what
failed. When the server answers 200 with a body that is no feed, the `:error`
is `:fetch/malformed`, and the result keeps the `:body`. A status such as 404
is an answer, so the result then has the `:status` and no `:error`.

A listener can give the address of the website of a show, not of its feed.
The function `html/feed-links` finds the feeds that the page names:

```clojure
(html/feed-links (:body (http/fetch! site-url)) site-url)
;; => ({:href "https://example.com/feed.xml" :type "application/rss+xml" :title "The show"})
```

Give the address of the page, so that the links resolve as in a browser.

A private feed can need a user name and a password, or a token. Give them to
`fetch!` as `:credentials`, such as `{:username "…" :password "…"}`. When a
redirect leads to another domain, `fetch!` does not send them on. The later
pages of a feed get them only at the domain of the address that you gave. A
private feed with a token in its URL needs no credentials.

An address can also hold the user name and the password, as in
`https://user:pass@host/feed.xml`. Then `fetch!` sends them as the
credentials, and the URLs in its result do not hold them. Do not keep the
password in the address that the app stores. The player state syncs to the
other devices and to the stores, and an address goes into OPML too. The
function `http/split-credentials` gives the address and the credentials
apart. Keep the password in the keychain of the platform.

While your app holds a password, hide it with `secret/hide`. The printer
writes a hidden text as `#<secret>`, so a log or a REPL does not show it.
Each function that takes a password, a token, a passphrase or a secret key
takes a hidden text too. From `split-credentials`, the password comes as a
hidden text.

```clojure
(def creds {:username "simon" :password (secret/hide (from-the-keychain))})
creds                                     ; => {:username "simon", :password #<secret>}
(podcast/fetch! url {:credentials creds})
```

A request map hides its credentials too, and `http/revealed` gives their
texts to another HTTP client.

In a browser, `fetch!` does not exist, and few feed servers accept requests
from other origins. Get the XML through your own server, and give the string
to `podcast/parse`.


## Keep the feeds current

With the library, one call: `(library/refresh! lib now)`. It fetches the
lists that are due, the feeds of the subscriptions that have none, and
the feeds that are due, and gives each fresh feed to `player/refresh`.
The result names the new episodes under `:added` and the feeds that moved
under `:moved`, with their subscriptions moved too.

Below it, `app.feeds` keeps the feeds of an app in one map, by the guid of
each feed. An entry has the URL, the validators of the last fetch and the
time of the next check. A call of `refresh!` fetches the feeds that are due
and applies each answer: a fresh feed, a 304, a failure, or a move.

```clojure
(def feeds (feeds/add {} url r now))  ; r from podcast/fetch!, as above
(feeds/due feeds now)                  ; => (guid …), the feeds to fetch now

(def step (feeds/refresh! feeds now))
;; => {:feeds   {…}                 ; the map, each fetched entry updated
;;     :changed [guid …]            ; the feeds that gave a fresh feed
;;     :moved   {guid "https://…"}  ; the feeds that moved for good
;;     :report  [...]}              ; the fetches that failed, and why

(doseq [guid (:changed step)]
  (player/refresh state (:feed (feeds guid)) (:feed (get (:feeds step) guid)) url now))
(doseq [[guid new-url] (:moved step)]
  (player/subscribe state guid {:url new-url :title (resolve/title (:feed (get (:feeds step) guid)))} now))
```

A fetch sends the validators of the entry. A feed that did not come stays as
it was, with its failure in the report. Its next check waits longer after
each failure in a row. The next check of a fetched feed is what
`resolve/next-check` gives. It is a day when the feed gives no time, and a
week for a feed that says it is complete. The option `:cadence` changes these
waits. The option `:credentials-of` gives the credentials of a private feed
by its guid, so that no password is in the map that the app stores. In
ClojureScript, give `:fetch`, a function that fetches through your own
server. Keep the map as you keep your other values: it is data.

Below the step, `resolve/next-check` tells when to fetch a feed again. It
reads the update-frequency rule of the feed, its `ttl`, its syndication
period, or the interval between its recent episodes, and it never waits more
than a year. A fetch with the validators of the last one gets a 304 when the
feed did not change. The server can say when to ask again, with `Retry-After`
or `Cache-Control`, and the result then has `:not-before`, at most an hour
ahead. A permanent redirect, 301 or 308, moves a feed for good, and
`:permanent-url` gives the new URL. A temporary redirect does not change the
URL of the subscription.

A private feed can move to another domain. When the app changes the URL of
the subscription, the credentials go to the new domain at each fetch from
then on. Ask the listener before you change the URL of a private feed.

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
(feeds/affected feeds (:pings polled))                                   ; => (guid …), fetch these now
(feeds/refresh! feeds now {:guids (feeds/affected feeds (:pings polled))})

(podping/affected (:pings polled) (map :url (vals (player/subscriptions state))))
;; => ("https://feeds.podcastindex.org/pc20.xml")                         ; the same, by URL
```

When no node of the chain answers, the result of `poll!` has an `:error`,
the diagnostic `:podping/unavailable`. Its `:block` is the last block that
the step read, so the next step reads on from there.

A server can get the feeds from a WebSub hub. The hub calls the server,
so the server needs a public URL, the callback. The result of `fetch!` has
`:websub` when the feed names a hub. Ask the hub for the feed, with a
callback and a secret that nobody can guess, one of each for each
subscription:

```clojure
(let [{:keys [hubs topic]} (:websub r)]
  (websub/call! (websub/request :subscribe (first hubs) topic
                                "https://app.example.net/websub/4f1c9e2a"
                                {:secret secret :lease-seconds 864000})))
;; => {:status 202 :url "https://hub.example.org/"}
```

The function `handler` gives a Ring handler for the callback. The option
`:wanted?` tells whether the server asked for the topic, and `:deliver!` gets
each feed that has the signature of the secret. Return fast from `:deliver!`,
and parse the feed after that:

```clojure
(def callback
  (websub/handler {:wanted?  (fn [{:keys [mode topic lease-seconds]}] (= topic (pending mode)))
                   :deliver! (fn [{:keys [body topic]}] (swap! inbox conj [topic body]))
                   :secret   secret}))

(podcast/parse (java.io.ByteArrayInputStream. body))  ; on the JVM, the bytes of a feed
```

A subscription lasts for the `:lease-seconds` that the hub gives to
`:wanted?`. Send the request again before the end. Keep the schedule of
`next-check` too, because a hub can fail.


## Follow lists of shows

Someone can keep a list of shows as an OPML file on the web, such as the
shows of a network or the picks of a critic. A listener can follow such a
list. The app then fetches the list from time to time, as it fetches the
feeds, and the new shows in the list become subscriptions.

With the library: `(library/follow lib list-url (opml/parse body) now {:title
"Picks" :folder "Picks"})`. Then `library/refresh!` fetches the list again
after a day, and the feeds of the shows that it brought. A list names a show
by its URL. When the feed then declares a guid of its own, the subscription
moves to it, as `player/rekey` moves it.

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

The function `refresh-list` subscribes to a show that joins the list, unless
the listener subscribed to it or left it before. It knows a show by the URL
of its feed, so a show that the listener has under another guid does not come
in twice. A show that leaves the list stays a subscription, because the
listener can like it. The function `player/to-opml` writes the lists that the
listener follows as inclusions, the way that OPML 2.0 includes one list in
another, and `player/from-opml` follows them again.

While the listener follows a list, the state keeps the record of each show
that the listener left. A new device then does not subscribe to such a
show again.


## Build the show and episode pages

The function `show/view` gives a show as a page shows it, in one map, and
`episode/view` gives an episode. Each is the feed or the item with its
properties merged in, one value for each property, and the other keys that a
page needs. With `episode/view!`, the files of the episode are read too: the
chapters file, the transcript and the media file.

```clojure
(def page (show/view show))
(select-keys page [:title :author :artwork :categories :medium :guid])
(:notes page)     ; the description, sanitized, as Hiccup
(:episodes page)  ; the items in listening order
(:seasons page)   ; the items by season
(:links page)     ; the links to the show in the apps

(def item (first (:episodes page)))
(def v (episode/view! show item {:languages ["da" "en"]}))
;; => {:title "…" :published #inst "…" :duration 3712.4 :numbering {:type "full" :season 2 :episode 3}
;;     :enclosure {:url "…" :type "audio/mpeg"} :notes ([:p {} "…"]) :people [...] :links [...]
;;     :chapters   {:source :file :url "…" :document {:chapters [...]}}
;;     :transcript {:url "…" :type "text/vtt" :document {:segments [...]}}
;;     :media      {:url "…" :duration 3712.4 :artwork {:type "image/jpeg" :data …}} …}

(chapters/at (:document (:chapters v)) 754)      ; the chapter at 12:34
(transcript/at (:document (:transcript v)) 754)  ; the segment at 12:34
(episode/report v)                               ; what the reading of each file found
```

The chapters come from the first of four places that has them. The places are
the chapters file, the Podlove chapters in the feed, the media file, and the
timestamps in the show notes. The `:source` tells which. A file that does not
come leaves `:error` in its part, and `view!` throws nothing for what the
network does. The option `:except` names what a view leaves out and does not
compute. A list of episodes needs no notes, no chapters and no links, so its
rows are `(episode/view show item {:except #{:notes :text :chapters
:links}})`, at a fiftieth of the cost. In `view!`, `#{:media}` leaves the
media file unread. The options of the requests are those of `http/call!`. The
option `:send` sends the requests in its place, for a browser app that
fetches through its own server.

The functions below the views give the same answers, so drop down to them
when a step is not what the page wants. For the properties alone, there is
`resolve/properties`. Where several vocabularies have the same property, a
rule in the docstring of the accessor selects the value. The function
`search/episodes` selects and sorts the items by criteria that are data. The
criteria can use the listener's progress.

```clojure
(resolve/properties show)
;; => {:title "…" :author "…" :artwork "https://…" :categories [["Technology"]] :medium "podcast" …}

(search/episodes show {:where {:status :unplayed} :order :listening :player state :url url})
(search/episodes show {:where {:season 2 :type "full"} :order :newest})

(resolve/properties show item)
(merge item (resolve/properties show item))  ; the raw fields and the resolved values together
[:div.notes (html/hiccup (resolve/description item))]  ; the show notes, sanitized, as Hiccup
```

The four places of the chapters, in the order of `view!`. In these
examples, `fetch-body` is your own HTTP function.

```clojure
(def doc (or (some-> (resolve/chapters-url item) fetch-body chapters/parse-json)
             (resolve/embedded-chapters item)
             (some-> (resolve/enclosure item) :url media/read! :chapters)
             (resolve/inferred-chapters item)))
(chapters/at doc 754)  ; the chapter at 12:34
```

The media file also gives its own duration, and it can hold artwork. A call
of `media/read!` reads them with range requests: most often one or two, and a
few hundred kilobytes. A host that inserts ads can send a different file to
each request. So the duration is that of the file that came, and it can
differ from the duration in the feed. In ClojureScript, `media/read!` returns
a promise.

```clojure
(def file (media/read! (:url (resolve/enclosure item))))
(:duration file)                ; => 3712.4, in seconds
(map :id (media/report file))   ; => (:mp3/estimated-duration)
(let [{:keys [type data]} (:artwork file)]
  [:img {:src (bytes/data-url type data)}])
```

The report is in the metadata of the result, as the report of a parsed
feed is. Metadata does not survive printing. So read the report before
you store the result. The views keep the report of each part as data.

When the file does not come, the result is `{:error …}`. The `:error` is
the diagnostic `:file/unreachable`, with the `:reason`, and with the
`:status` when the server answered with one.

An alternate enclosure can give the hash of its file in `podcast:integrity`.
The function `resolve/enclosure` keeps it under `:integrity`, and
`media/verify!` checks the file against it:

```clojure
(let [{:keys [url integrity]} (resolve/enclosure item {:types ["video/mp4"]})]
  (when integrity
    (media/verify! url integrity)))  ; => {:valid? true :algorithm "sha384"}
```

The `:valid?` is false for a file that does not match. It is nil when
`verify!` made no check, and the `:error` of the result then tells why.

The function `resolve/transcript` selects the transcript that suits the
listener: by language first, then by timings, then by type. Fetch it, and
parse it with its type:

```clojure
(let [{:keys [url type]} (resolve/transcript show item {:languages ["da" "en"]})]
  (transcript/at (transcript/parse (fetch-body url) type) 754))
```

A show can recommend other shows in its podroll, and name the feed of its
publisher. With the credentials of the index, `show/view!` gives the shows of
the podroll as the Podcast Index knows them. It gives the publisher feed with
its shows, and tells whether the publisher confirms the show. The
specification asks an app to show the other shows of a publisher only when
its feed lists this show.

```clojure
(def page (show/view! show {:index {:key "…" :secret "…"}}))
(map (juxt :title (comp :rss/title :show)) (:podroll page))  ; each with :show when the index knows it
(:confirmed? (:publisher page))                               ; true when the publisher feed lists this show
(:shows (:publisher page))                                     ; the remote items of the publisher feed
```

Below the view, `resolve/podroll` and `resolve/publisher` give the remote
items. The function `podcastindex/remote-item!` finds the feed of a remote
item in the Podcast Index by its feed guid. The entries of a publisher feed
or a list feed come from `resolve/remote-items`. The function
`resolve/social-interacts` tells where the comments on an episode are, or
that the podcaster wants no public comments. [Show the comments on an
episode](#show-the-comments-on-an-episode) tells how to read them.

```clojure
(resolve/podroll show)  ; => ({:feed-guid "917393e3-…" :feed-url "https://…" :title "…"} …)
(podcastindex/remote-item! {:key "…" :secret "…"} (first (resolve/podroll show)))  ; => {:feed {…}}

(def label (:feed (podcast/fetch! (:feed-url (resolve/publisher show)))))
(resolve/remote-items label)  ; => ({:feed-guid "…" :feed-url "https://…" :medium "podcast"} …)

(resolve/social-interacts show item)  ; => ({:protocol "activitypub" :uri "https://…" :priority 1} …)
(resolve/soundbites item)             ; => ({:start-time 73 :duration 60 :title "…"} …), parts for a preview
(resolve/ai-content? show item)       ; => true, false, or nil when the feed does not tell
```


## Show the comments on an episode

A podcast feed can name a thread of comments about an episode, with
`podcast:socialInteract`. The thread is on ActivityPub, as on Mastodon, on
the AT Protocol, as on Bluesky, or on Nostr. For an episode,
`comments/thread` picks the first thread, by priority, that podcast-clj
can read. Fetch the thread, and show its tree. For a thread on the AT
Protocol, also give the URL of an AppView. The AppView gives the replies
to a post. For a thread on Nostr, you can give the relays to ask.

```clojure
(def opts {:appview "https://public.api.bsky.app"})

(def thread (some-> (comments/thread show item opts) (comments/fetch! opts)))
(comments/tree thread)  ; => {:content "<p>…</p>" :author {:name "…" :handle "@…"} :replies [{…} …]}
(:report thread)        ; => [{:id :comments/withheld :count 1 :level :info …}]
```

If there is no thread, show no comments. When the podcaster wants no public
comments, `comments/thread` gives nil.

The content of a comment is sanitized HTML with absolute links. Render it
with `html/hiccup`. To lead the mentions and the hashtags to pages of your
app, render the comment with `comments/hiccup`, and give it a function
for the links:

```clojure
(comments/hiccup thread comment
                 {:link (fn [{:keys [type protocol id tag]}]
                          (case type
                            :mention (when id (str "/profiles/" protocol "/" (http/encode id)))
                            :hashtag (str "/tags/" (http/encode tag))
                            nil))})
;; => [[:p {} "Good episode, " [:a {:href "/profiles/activitypub/https%3A%2F%2F…"} "@ann"] " " [:a {:href "/tags/podcast"} "#podcast"]]]
```

The function gets a part of the content. The `:id` of a mention is the id of
an author of the thread. It is the id of an actor on ActivityPub, a DID on
the AT Protocol and a public key on Nostr. For nil, a mention of ActivityPub
keeps the link of its post, and a mention of another protocol has no link.
For an app that shows no HTML, `comments/parts` gives the parts themselves.

For a notification or a line in a list, `comments/text` gives the content as
plain text. It is the text that a reader sees, with the name of a profile in
the place of its id:

```clojure
(comments/text thread comment)  ; => "Is this @Ann approved? 🤣 #podcast"
```

If a comment has a `:summary` or is `:sensitive?`, show it behind a warning.
An attachment gives its `:aspect-ratio` for the layout. The `:poll` of a
comment gives the choices of a poll. On Bluesky, the author of the root post
can hide replies. The function `tree` leaves out the hidden replies. To show
them, give `tree` the options `{:hidden? true}`.

The servers of a thread choose the URLs that `fetch!` fetches. So `fetch!`
sends nothing to your app's network, as
[Requests that strangers choose](architecture.md#requests-that-strangers-choose)
tells. If your app must reach its own network, give `fetch!` the option
`:allow? (constantly true)`.

Keep the thread with the episode. A call of `fetch!` reads a thread as far as
its limits allow, and the report tells when it stopped early. By default, it
reads for one minute at most. To show comments sooner, give it `:max-seconds
5`. Show what it read, then fetch the kept thread again to read more. Each
fetch reads 100 comments more, by default. To see new comments, fetch a new
thread from `comments/thread`.

A server can fail for a time. The report then has `:comments/incomplete`,
and the failure has `:retry?`. The next fetch of the kept thread asks that
server again. A very large thread is cut, and the report tells how many
replies a limit of size cut, as `:comments/truncated`.

The AT Protocol gives no web page for a post. Link such a post to a web app
of your choice. Build the link from the DID of the author and the record key
of the post. The function `atproto/at-uri` gives both.

On Nostr, people also write about an episode that names no thread. Each such
note names the episode by its guid. The function `nostr-comments/about` gives
the thread of these notes, for an episode or for a show:

```clojure
(def about (nostr-comments/about show item))
;; => {:protocol "nostr" :uri "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"}

(def thread (comments/fetch! about {:relays ["wss://relay.fountain.fm" "wss://relay.damus.io"]}))
(:replies (comments/tree thread))  ; the notes about the episode, each with its replies
```

The root of such a thread is the episode, so the root of the tree has no
content. The podcaster did not choose this thread. When
`resolve/social-interacts` gives the protocol `disabled`, show no
comments.

The `:content` of a comment from Nostr is HTML, as that of each other
comment. A mention of a profile is the name of the profile, such as `@Ann`.
The function `comments/hiccup` gives the mentions, the hashtags and the
references to other notes your app's links. A part of Nostr with a
`:uri` is a mention or a reference, and the URI opens an app of Nostr on the
device:

```clojure
(comments/hiccup thread comment {:link (fn [{:keys [uri tag]}]
                                         (or uri (str "https://example.org/tags/" tag)))})
;; => [[:p {} "Is this " [:a {:href "nostr:npub1…"} "@Ann"] " approved? " [:a {:href "https://…"} "#podcast"]]]
```

When a note came with a payment, `(::nostr-comments/receipts comment)`
has the ids of the receipts. Such a note can have no content.

A relay can send anything, so `fetch!` keeps only the events with a
signature that is right, and the report counts the others as
`:comments/unverified`.

Nostr gives no web page for a note, and the handle of an author is the
public key, `npub1…`. For a link, `nostr/uri` gives the `nostr:` URI of a
note, which an app of Nostr on the device opens:

```clojure
(nostr/uri {:type :nevent :id (:id comment) :relays ["wss://relay.fountain.fm"]})
;; => "nostr:nevent1…"
```

Without `:relays`, `fetch!` asks the relays of `comments.nostr/relays`.
[Services and their defaults](services.md#the-relays-of-nostr) tells what
they answered, and names the AppViews that answer without an account.


## Play episodes

The player state holds the queue and the progress. The app holds the media
element. Send each event of the media element to `library/step`, or to
`player/step` below it. Each returns the new state and the effects that the
app must do: seek in the media, or play the next episode.

```clojure
(def id (player/episode-id show url item))
(def state (player/enqueue state id now))

(player/resume-position state guid id)  ; where the listener stopped, or after the intro
(resolve/enclosure item {:types ["audio/opus" "audio/mpeg"] :max-bitrate 64000})

(def playing (:state (player/step state [:progress id 1234 3600] now)))  ; on each timeupdate event
(player/step playing [:skip id :forward] now)  ; => {:state … :effects [[:seek id 1264]]}
(player/step playing [:ended id] now)          ; => {:state … :effects [[:play next-id]]}
```

The effect `[:play next-id]` comes only when another episode is in the queue.

The settings of the show above have `:skip-outro`, so `step` ends each of its
episodes 45 seconds before the end. A `:progress` or a `:skip` that moves the
position into the outro gives `[:stop id]`. Then comes `[:played id]` if the
episode was not played before, and then the effects of `[:ended id]`. The
function `player/outro-start` gives the position where the outro starts.

An episode with chapters lets the listener jump between them. Give `step`
the chapters document of the episode, and the direction:

```clojure
(player/step playing [:chapter id :next doc] now)      ; => {:state … :effects [[:seek id 1500]]}
(player/step playing [:chapter id :previous doc] now)  ; => {:state … :effects [[:seek id 1180]]}
```

A jump to the previous chapter goes to the start of the chapter that plays.
In the first three seconds of a chapter, it goes to the chapter before it. A
jump does not stop at a chapter that is not in the table of contents. For a
player that does not use `step`, `chapters/jump` gives the position alone.

A sleep timer stops the player at the end of an episode. Keep the timer
beside the state, and give it to each `step`:

```clojure
(def timer {:episodes 2})                                   ; stop when two more episodes ended
(def result (player/step playing [:ended id] now {:sleep timer}))
(:effects result)                                           ; => [[:play next-id]]
(:sleep result)                                             ; => {:episodes 1}, the timer for the next step
(player/step (:state result) [:ended next-id] now {:sleep (:sleep result)})
;; => {:state … :effects [[:stop next-id]]}, and no timer
```

The timer belongs to one device, so it is not in the state that syncs. A
timer that counts minutes belongs to the app's clock.

An episode counts as played when the listener is 30 seconds from its end.
Between two reports that are more than 60 seconds apart, `step` counts no
listening. Give `step` other numbers as options:

```clojure
(player/step playing [:progress id 3500 3600] now {:played-threshold 120 :listening-gap 90})
;; => {:state … :effects [[:played id]]}
```

The browser player of the inspector is a working example, in
`web/inspector/player.cljs` and `web/inspector/player/view.cljs`.


## Download episodes

`library/plan`, and `downloads/plan` below it, tells which episodes to
download and which files to delete. The app downloads and deletes the
files, and it keeps the list of the files on the device.

Keep the rules in the player state, so that they sync. The rule of a show
is the setting `:downloads`, and the listener's rules are the
preference `:downloads`:

```clojure
(def state
  (-> state
      (player/prefer {:downloads {:default      {:played 86400}  ; a played file goes after a day
                                  :queue        3                ; have the first three of the queue
                                  :max-episodes 50}}
                     now)
      (player/configure guid
                        {:downloads {:download? true
                                     :episodes  {:where {:duration [600 nil] :not {:text #{"rerun"}}}
                                                 :limit 3}}}
                        now)))

(downloads/problems (downloads/rules state))  ; => nil, this version knows each rule
```

The key `:episodes` has the options of `search/episodes`. The rule above
selects the newest three episodes of the show that last ten minutes or more
and do not have the word rerun. The keys of the rules are in
`downloads/default-rule` and `downloads/default-rules`.

Make a plan after each refresh of the feeds, and when the listener plays
or queues an episode:

```clojure
(def files {[guid "ep-1"] {:bytes 50117331 :at #inst "2026-09-09"}})  ; the files on the device

(downloads/plan state (map :feed (vals feeds)) files now)
;; => {:download [{:id [guid "ep-3"] :url "https://…/3.mp3" :type "audio/mpeg" :bytes 48211004 :reason :rule}]
;;     :delete   [{:id [guid "ep-1"] :bytes 50117331 :reason :played}]
;;     :waiting  []}
```

Then follow the plan:

- Download each file of `:download`, in its order. Add an entry to the
  list of the files when the download starts, with the time as `:at`.
- Delete each file of `:delete`. Keep its entry, and set `:removed?` in
  it. No episode that has an entry is downloaded again.
- Show the listener what is in `:waiting`. These downloads have no room
  within `:max-episodes` and `:max-bytes`.

Set `:keep?` in the entry of a file that the listener asked for. No rule
deletes such a file. To download an episode again, remove its entry.

The rules in the state are for each of the listener's devices. For rules of
one device, give `plan` the rules as an option:

```clojure
(downloads/plan state (map :feed (vals feeds)) files now
                {:rules     (assoc (downloads/rules state) :max-bytes 2000000000 :make-room? true)
                 :enclosure {:types ["audio/opus" "audio/mpeg"]}})
```

The key `:enclosure` has the options of `resolve/enclosure`, which selects
the file of an episode. For feeds that declare no podcast:guid, give `:url`
as a function of the feed, as `search/episodes` takes it.

The function `plan` does not know the network or the battery. The app decides
when it follows the plan.


## Share a show or an episode

A listener shares a show with a person who has another app. The function
`links/links` gives the link to the show in each app. The keys that the links
hold come from `links/ids`, which reads them from a show of `directory`, or
from a parsed feed:

```clojure
(def keys-of-show (links/ids (first found) item))
;; => {:feed-url "https://…" :podcast-guid "917393e3-…" :apple-id "1584274529" :episode-guid "…" …}

(links/links keys-of-show {:only #{:apple :pocketcasts :antennapod}})
;; => [{:app :antennapod :name "AntennaPod" :type :app
;;      :show "https://antennapod.org/deeplink/subscribe?url=https://…"
;;      :subscribe "antennapod-subscribe://…"} …]

(links/link :podlink :show keys-of-show)  ; => "https://pod.link/1584274529", a page for each app
```

A show from `directory/search!` has the ids of the directories, so it has
the most links. A feed alone has its URL and its guid. The link of the
kind `:subscribe` opens the app, so show it only on a device that has the
app. [Services and their defaults](services.md#the-links-of-the-apps)
lists the form of each link. The forms change, so give a table of your
own as `:apps` when a form is wrong.

For a link to a time, put a media fragment on the URL of the media file.
A browser plays the file from that time. A clip of the player state has
the two times:

```clojure
(let [{:keys [start-time duration]} (first (player/clips state id))]
  (links/at-time (:url (resolve/enclosure item)) start-time (+ start-time duration)))
;; => "https://example.org/54.mp3#t=754,796.5"
```


## Search the episodes

With `:search? true` in its settings, the library keeps the index, adds
each feed that comes and removes each that goes, and `library/search`
asks it. The index of a show of 200 episodes is two megabytes, and it
takes a fifth of a second to build. So a phone with many shows leaves it
off.

Below it, the search index is a value that you build from the feeds. When
a feed changes, build the index again, or use `search/add` and
`search/remove`. To make a transcript searchable, give it to
`search/item-document`. In a browser with little memory, index fewer
fields.

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

- A folder is a setting of a subscription. The shows are grouped by folder
  with `player/folders`, and `player/to-opml` writes the folders into the
  OPML.
- Tags are the listener's own categories. A show is in one folder at most,
  but it can have many tags. In OPML, `player/to-opml` writes them as the
  categories of the outline, a part that most apps do not read.
- The inbox holds the new episodes of a show whose settings have `:inbox?`.
  They go there through `player/refresh`. An episode leaves the inbox when
  the listener queues it, plays it or dismisses it.
- The listener can star an episode, and bookmark a position in it.
- The listener can rate an episode. The scale is from 1 to 5, unless the
  app gives another scale as `:scale`. Examples are `{:worst 1 :best
  100}`, and `{:worst 0 :best 1}` for a thumb down and a thumb up. The
  state keeps the scale with each rating. So `(player/rating state id
  {:scale …})` gives a rating from another app in your app's scale.
  The criterion `:rating` of `search/episodes` takes a range of ratings.
- A clip is a part of an episode, with a start, a duration and a title. It
  has the shape of a `podcast:soundbite`.
- A saved filter keeps the criteria of `search/episodes` under a name.
  Another device can save criteria of a later version of podcast-clj. Before
  you offer the filter, ask `search/problem`, which tells what this version
  does not know.
- A filter for the episodes of the last week has the criterion `:age`, a
  number of seconds. The function `search/episodes` takes the time of now as
  `:at`. The criteria under `:not` exclude episodes, such as `{:not {:text
  #{"rerun" "trailer"}}}`.
- A playlist is a list of episodes beside the queue, in the order the
  listener gives. Play it by queueing its episodes. With
  `player/to-list-feed`, it is a list feed of the podcast namespace, which
  Podcasting 2.0 apps can play, and `player/from-list-feed` reads one. The
  list feed is for other people, so the URL of a private feed goes into it
  without its user name and password.
- The function `player/step` counts the seconds that the listener hears of
  each show, month by month, for statistics and a review of the year. It
  counts from one report of the position to the next. So report the position
  at least each 30 seconds while an episode plays. After a minute without a
  report, `step` takes the time for a pause, and counts nothing.

```clojure
(def state (-> state
               (player/configure guid {:folder "News" :auto-queue? false :inbox? true} now)
               (player/tag guid "Daily" now)
               (player/star id now)
               (player/bookmark id 754 "The interview starts" now)
               (player/rate-episode id 5 now)
               (player/clip id 754 42.5 "The best answer" now)
               (player/save-filter "Starred" {:where {:starred? true :status :unplayed} :order :newest} now)))

(player/folders state)       ; => {"News" [guid …] nil […]}, nil for the shows in no folder
(player/tagged state)        ; => {"Daily" [guid …] …}
(player/inbox state)         ; => [[feed-guid item-guid] …], the latest to arrive first
(player/dismiss state id now)
(player/starred state)       ; => [id …], the latest starred first
(player/bookmarks state id)  ; => [{:id id :position 754 :title "The interview starts" …}]
(player/rating state id)     ; => 5
(player/rating state id {:scale {:worst 1 :best 100}})  ; => 100.0, the same rating of 1 to 100
(player/rated state)         ; => [{:id id :rating 5 :worst 1 :best 5 …}], the latest rated first
(player/clips state id)      ; => [{:id id :start-time 754 :duration 42.5 :title "The best answer" …}]
(player/listened state)      ; => {guid {"2026-09" 5400.0 …} …}, seconds by show and month

(def state (player/add-to-playlist state "Road trip" id now))
(reduce #(player/enqueue %1 %2 now) state (player/playlist state "Road trip"))  ; play it
(podcast/emit (player/to-list-feed state "Road trip" {:url "https://example.org/road-trip.xml"}))

(let [urls     (into {} (for [[_ {:keys [url feed]}] feeds] [feed url]))  ; for feeds without a podcast:guid
      criteria (get (player/filters state) "Starred")]
  (when-not (search/problem criteria)
    (search/episodes (keys urls) (assoc criteria :player state :url #(urls %) :at now))))
```

The function `player/from-opml` reads the folders, the tags and the dates of
subscribing back. It also reads the OPML of other apps. Some apps put a
description in the text of an outline, so it uses the title of the feed
first. Some apps put the whole list in one folder with the name `feeds`. Such
a folder does not become a folder. The listener's own folder stays a folder,
also when it holds every show. [Services and their
defaults](services.md#the-folder-around-an-opml-list) lists the names, and
the option `:wrappers` replaces them:

```clojure
(player/from-opml state (opml/parse opml-text) now {:wrappers #{"feeds" "podcasts"}})
```


## Sync the devices of a listener

Each device writes its whole state as one document to a store, and reads the
documents of the other devices. One step of `sync!` is a pull, a merge and a
push. [Syncing](sync.md) explains why this is safe.

With the library, one call: `(library/sync! lib [cloud folder] now)`. It
sends the cursors of the last step, and merges what the listener did during
the step into the result. It compacts, keeps the cursors, and tells under
`:next-at` when to call again. Between steps, `library/next-sync` tells the
same. Below it, the steps by hand:

1. Find the place for the documents: a WebDAV collection, or a folder that a
   sync client mirrors. [Sync stores and documents](stores.md) gives the
   URLs and paths of some providers.
2. Test the store with `store/check!`, for example from a button in the
   settings screen.
3. Make a backend for each place. The functions `webdav/backend` and
   `file/backend` make the store and the backend in one call.
4. Call `sync!` when the app starts, when it goes to the background, and
   after a series of changes. Merge the `:state` of the result into the
   state the app has now, which can hold changes from during the step. Then
   compact it, because the merge brings back the tombstones that the step
   removed. Keep the `:ctx` of the result for the next call.
5. Ask `sync/next-at` when to call `sync!` again, and set a timer.

```clojure
(def creds {:username "simon" :password (secret/hide "app-password")})
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
The function `player/lagging` shows such devices, and `player/retire` removes
a device from the sync.

```clojure
(player/devices state)                   ; the listener's devices, as they described themselves
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
the backend a function that looks them up in the app's parsed feeds:

```clojure
(def feeds (atom {}))  ; the app's parsed feeds, by URL
(def pods (gpodder/backend "https://gpodder.net" {:credentials creds :identities #(gpodder/identities @feeds)}))
(sync/sync! state [cloud pods] now)  ; the subscriptions and the positions of the other apps, merged
```

A subscription that another app added can name a feed that this app has not
fetched yet. Fetch the feed, as `library/refresh!` does, and the next step
applies the actions that wait for it. For the GPodder Sync app of a Nextcloud, give `:api :nextcloud`.
To let such apps sync with your own Clojure server, mount
`dk.simongray.podcast.app.sync.gpodder.server/handler`. The inspector does
this at `/api/2/`.


## Pay value for value

The function `resolve/value` gives the value block of an episode. The
function `value/recipients-at` gives the recipients at a position in the
episode, and `value/payments` divides an amount between them. The wallet of
the app makes each payment. What goes with a payment is one map for each way
to pay.

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
(def records (value/records (assoc payment :sats 50) recipient))
records                ; => {7629169 "{\"action\":\"boost\",…}", 696969 "22"}
(value/report records) ; => [], or :value/custom-record
```

A node can hold many wallets, and the custom key and value name the
wallet of the recipient. The feed chooses the key, so `records` leaves out
a key that the payment cannot carry. The report then has
`:value/custom-record`, of level `:error`. Do not send such a payment: it
reaches the node, and not the wallet.

Pay a recipient of type `lnaddress` with an invoice. A call of
`lnaddress/invoice!` asks the domain of the address for the invoice, and
checks its amount. Put the metadata of the payment in the comment. Serve the
header of `metadata/header` at a URL of your own, and name that URL in the
comment.

```clojure
(lnaddress/invoice! "ann@getalby.com" 50000
                    {:comment #(metadata/payment-comment payment "https://app.example/payments/1" (:comment-length %))})
;; => {:invoice "lnbc500n1…" :msat 50000 :network :bitcoin :comment "rss::payment::boost https://…"}

{"x-rss-payment" (metadata/header payment)}  ; the header of the page at that URL
```

The invoice has the `:comment` that went out. An address can take a short
comment only, or none. When the comment does not fit, none goes out, and
the invoice has no `:comment`. The recipient then does not learn what the
payment is for, so tell the listener before the payment.

If the domain names a node for keysend, you can pay the address with keysend
instead. A call of `lnaddress/node!` gives the node as the keys of a
recipient of type `node`. Keep the node for a while, as the podcast namespace
asks.

```clojure
(merge recipient (lnaddress/node! (:address recipient)))  ; => {:type "node" :address "03…" :custom-key "696969" …}
```

If an answer holds `:error`, do not pay. An invoice for another amount is
an error of level `:error`, and so is an answer that came over plain http.
The feeds choose the domains that `invoice!` and `node!` ask, so they send
nothing to your app's network. The page of the metadata is public,
so the name of the sender and the message are public too.

### Pay from the listener's wallet

A listener with a wallet of their own gives your app a connection URI of
Nostr Wallet Connect. The wallet makes the URI, often with a budget. The
function `nwc/parse` reads it. The URI holds a secret key, and who has it can
spend from the wallet. Keep it as you keep a password, and show it to nobody.

```clojure
(def connection (nwc/parse "nostr+walletconnect://b889…?relay=wss%3A%2F%2Frelay.example&secret=…"))
connection                         ; => {:wallet "b889…" :relays ["wss://relay.example"] :secret #<secret>}
(nwc/uri connection)               ; the text with the secret, for your app's store
(def info (nwc/info! connection))  ; => {:methods ["pay_invoice" "pay_keysend" …] :encryption ["nip44_v2"]}
```

Ask for the info once, and give it to each `ask!` as `:info`. Pay a node
with `pay-keysend`, and the invoice of a Lightning address with
`pay-invoice`.

```clojure
(def command (nwc/pay-keysend (assoc payment :sats 50) recipient))
(value/report command)                       ; => [], or :value/custom-record
(nwc/ask! connection command {:info info})   ; => {:method "pay_keysend" :preimage "…" :fees-msat 1000}

(nwc/ask! connection (nwc/pay-invoice (:invoice invoice)) {:info info})
;; => {:method "pay_invoice" :preimage "…" :proven? true :fees-msat 1000}
(nwc/ask! connection (nwc/get-balance) {:info info})   ; => {:method "get_balance" :msat 21000000}
```

The answer to `pay-invoice` has `:proven?`. It is true when the preimage
is the proof of the invoice. When it is false, the wallet tells that it
paid, and it gave no proof. Do not pay again then. Tell the listener, who
knows the wallet. A keysend payment has no such proof.

An answer with `:error` tells what went wrong, and each error says
whether a payment went out:

| Error | Did the request go out? | What to do |
|---|---|---|
| `:value/wallet-unsupported` | No | Pay in another way. The wallet does not take the command. |
| `:value/command-too-large` | No | Cut the listener's message. A feed can also have a title that is too long. |
| `:value/wallet-unreachable` | No | Ask again later. No relay took the request. |
| `:value/wallet-refused` | Yes | Show the `:reason`. The wallet did not pay, and `:code` tells why. |
| `:value/wallet-silent` | Yes | Wait until `:expires-at`. Then ask with `nwc/lookup-invoice`. |
| `:value/wallet-unreadable` | Yes | Ask the wallet before you pay again. |

Do not send a payment again after `:value/wallet-silent`. The wallet can
still pay until the request expires, which is after `:timeout-seconds`.
Wait some time more, because your app's clocks and the wallet
differ.

Keep the listener's money safe:

- Store the text of `nwc/uri` where your app stores passwords. A
  connection hides its secret, so a connection in a log shows `#<secret>`.
  The text of `nwc/uri` and the text of `secret/reveal` show the secret.
- Show the recipients and the amounts before the payment. The feed
  chooses them.
- Show the texts of an answer as text. The wallet wrote its name and the
  reason of an error, and HTML in them must not run.
- Ask the listener before you use the `:lud16` of a connection. It is a
  claim of who wrote the URI.
- Tell the listener to give the connection a budget in the wallet. The
  budget is the most that a lost secret costs.

In a browser, a wallet can give the page a WebLN provider, as the extension
Alby does. In ClojureScript, `value.webln` calls it. The wallet asks the
listener before each payment.

```clojure
(webln/pay-keysend! (assoc payment :sats 50) recipient)  ; a promise of {:preimage "…" :sats 50}
(webln/pay-invoice! (:invoice invoice))                  ; a promise of {:preimage "…" :proven? true}
```

WebLN counts a keysend payment in whole sats, so a payment of 50,500
millisatoshis sends 50 sats. A page without a provider gets
`:value/no-provider`. When the listener says no, the error is
`:value/wallet-refused`.


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

A host checks a feed before it publishes, and an app can check a feed that a
podcaster submits. A call of `check/feed!` fetches the files that the feed
and its newest items link to: the media files, the images, the transcripts
and the chapters files. It tells whether they reach a listener as the feed
says. Give it the URL of the feed to check the feed too, and a profile to add
the rules of PSP-1 or Apple Podcasts. It sends a HEAD request for each file,
asks for two bytes of each file but the images, and reads the header of each
image. With `:read? true`, it also reads what the files hold: the format and
duration of each media file, as far as its duration, and each transcript and
chapters file whole.

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

A feed names the files of its check, so a check has limits. They are 500
files, ten minutes, and 32 MB of each file that it reads. The limits are in
`check/default-limits`, and the options of `feed!` set each of them. A file
that a limit left out has the facts `{:unchecked …}`, and the report counts
such files with `:check/unchecked`. If your server checks feeds that
strangers submit, give `feed!` the option `:allow? http/public?`. It then
sends no request to your own network.

Each diagnostic has the `:url` of the file and the `:path` of the element
that names it. The function `check/verdicts` gives the diagnostics of facts
that the app gathered itself. A call of `http/probe!` gives the same facts of
any file, such as the size of an episode before the app downloads it:

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

Here `data-center?` is a predicate of a request. Build it from your lists of
the addresses of data centers, VPNs and known bots, as the guidelines of the
IAB ask. The guidelines also ask a host to leave out the addresses with too
many downloads. Remove them from the downloads by a rule that you state. A
window is a calendar day in UTC. For the 24 hours from the first request of a
listener, give `:window :rolling`, and for the 24 hours from the last
request, `:extending`.


## Measure the downloads with OP3

OP3 counts the downloads of a show when its feed puts the prefix of OP3 in
front of each enclosure. The function `op3/with-prefix` puts the prefix on
the enclosures of a feed:

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

A call of `downloads!` reads page after page, and a page can fail, for
example when OP3 answers 429. The answer then has the `:error`, with the
downloads of the pages before that page. Its token names the page that
failed, so the next call reads on from there. For an answer that is a
failure, `call!` throws.


## Serve the feed of a show

This section is for a host. A feed is written by `podcast/emit`. A long feed
is slow to fetch, so serve it in more than one document. The function
`feed/pages` makes the documents, with the links between them that RFC 5005
defines:

```clojure
(def documents (feed/pages feed {:kind :archived :page-size 100}))
(map :url documents)
;; => ("https://example.org/feed.xml"             ; the newest items, for the apps that poll
;;     "https://example.org/feed.xml?archive=2"   ; the archives, the newest first
;;     "https://example.org/feed.xml?archive=1")
(podcast/emit (:feed (first documents)))
```

The archives of an archived feed do not change when the show gets an
episode, so an app fetches each archive once. The pages of the kind
`:paged` change with each episode. Give the URLs of your server as
`:page-url`, a function of the number of a page.

A WebSub hub sends the feed to the servers that subscribe. Name the hub
in the feed, next to the link of the feed to itself. Then tell the hub of
each change:

```clojure
(def feed (update feed :atom/links conj {:rel "hub" :href "https://hub.example.org/"}))
(websub/call! (websub/publish-request "https://hub.example.org/" "https://example.org/feed.xml"))
```

A private feed has a token for each subscriber. Make the tokens and keep
them, and check the token of each request for a feed or a media file. The
function `feed/for-subscriber` writes the token into the URLs of one
subscriber:

```clojure
(def private (feed/for-subscriber feed "k3J9x2"))
(get-in private [:rss/items 0 :rss/enclosure :url])  ; => "https://media.example.org/54.mp3?token=k3J9x2"
(resolve/blocked? private "apple")                   ; => true, the feed is closed to the directories
(feed/pages private)                                 ; the pages have the token too
```

The options give another name for the parameter, or a function that puts the
token in the path. The call of `op3/with-prefix` comes after
`for-subscriber`, because the prefix goes in front of the whole URL.


## Use another service

The addresses and the rules of some services are known by name. Each is a
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
`:api`. In a path, `{user}` is the user name, and `{device}` is the id of the
device:

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

These tasks stay with the app:

- Store the values that [Before you start](#before-you-start) lists.
- Fetch feeds and query directories in a browser, where each server decides
  what a web page can fetch.
- Play the media, through a media element that the effects of `player/step`
  control.
- Download and delete the files of episodes, as the plan of
  `downloads/plan` says, and when the network and the battery allow it.
- Show notifications and make payments.
- Draw the user interface.
- For a host, keep the logs of the server and the lists that the count of
  downloads uses: user agents, data centers and bots.
