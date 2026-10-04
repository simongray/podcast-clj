podcast-clj
===========

This is a library for making podcast software in Clojure or ClojureScript.

- It parses and writes podcast feeds & associated files according to the
relevant specs, but supports files that break them too.
- It includes nearly every building block of a modern podcast app in its API,
which has been designed to be highly _decomplected_.
- It also has high-level `app.*` namespaces that compose low-level functions,
so you can start making a podcast app fast.
- It depends only on Clojure and data.json, and its requests are plain maps,
so you can send them with any HTTP client.

> **AI;DR** this introduction was written by a regular human, but I want to be
> transparent here: large swaths of this library were developed with assistance
> from frontier LLMs. I have spent a long time on the API design and on
> hardening the library in terms of both security and performance, but it simply
> wouldn't be feasible for me to develop a library with such broad support for
> modern podcasting features without LLMs helping to implement a lot of it.

Current status
--------------

- **Feeds.** [RSS 2.0](https://www.rssboard.org/rss-specification),
  [iTunes](https://podcasters.apple.com/support/823-podcast-requirements), and
  [the `podcast` namespace](https://podcastindex.org/namespace/1.0) of
  [Podcasting 2.0](https://podcasting2.org) all turn into the same normalised Clojure data,
  as do [Media RSS](https://www.rssboard.org/media-rss), Google Play and the other
  vocabularies of podcast feeds.
- **Formats.** Support for chapters, transcripts, [OPML](https://opml.org/spec2.opml),
  show notes as Hiccup or text, and the MP3/M4A files themselves: their chapters,
  duration and art.
- **Directories and updates.** [Apple Podcasts](https://podcasts.apple.com),
  [fyyd](https://fyyd.de), [gpodder.net](https://gpodder.net) and the
  [Podcast Index](https://podcastindex.org) are all supported.
  [Podping](https://podping.org) and [WebSub](https://www.w3.org/TR/websub/) are
  supported too. You can also find the feed of a show from its website or a link
  from another app.
- **Apps.** Everything needed to create a podcast app, including a queue,
  playlists, full-text search, download rules, and sync between devices
  through a folder, [WebDAV](https://www.rfc-editor.org/rfc/rfc4918), or
  [a server of your own](server/README.md). Through the [gpodder API](https://gpoddernet.readthedocs.io/en/latest/api/),
  the subscriptions and play progress also sync with apps such as
  [AntennaPod](https://antennapod.org).
- **Hosts.** Validation against
  [PSP-1](https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification)
  and the requirements of Apple Podcasts, checks of the files that a feed links to,
  long feeds split into pages, and a private feed for each subscriber.
- **Comments.** Get episode comments via [ActivityPub](https://www.w3.org/TR/activitypub/),
  [the AT Protocol](https://atproto.com), or [Nostr](https://github.com/nostr-protocol/nips).
- **Analytics.** Download counts from [OP3](https://op3.dev), and your own counts by the
  [IAB guidelines](https://iabtechlab.com/standards/podcast-measurement-guidelines/).
- **Payment integration.** [Lightning addresses](https://lightningaddress.com), keysend,
  the wallets of [WebLN](https://www.webln.guide) and
  [Nostr Wallet Connect](https://github.com/nostr-protocol/nips/blob/master/47.md),
  [boostagrams](https://github.com/lightning/blips/blob/master/blip-0010.md), and the
  payment metadata of a show.

How podcasting works
--------------------

A podcast is published as a feed: an RSS file that the show's host, i.e. the
service that stores its audio, serves at a URL. The feed describes the show in
its `<channel>`, and each episode in an `<item>`, which links to the audio file
in an `<enclosure>` and holds the show notes. Most feeds also use tags that RSS
doesn't define, mainly Apple's `itunes:` tags and the `podcast:` tags of
Podcasting 2.0. Listeners find shows in directories, e.g. Apple Podcasts or the
Podcast Index, and subscribe to them in an app, which checks each feed now and
then for new episodes.

The names in podcast-clj follow these terms. A parsed feed is a map of the
channel and its items as the host wrote them, while `app.show` and
`app.episode` give a show and an episode as a listener sees them, e.g. with one
title and one image, whichever tags they came from. The other namespaces
are mostly named after the part of podcasting they cover, e.g. `directory`,
`comments`, `value` for payments and `analytics` for download counts. What a
listener does, e.g. subscribing, queueing and playing, goes into their profile,
which syncs between their devices. Each device has its own library: the
profile, plus the feeds that the device has fetched.

Feeds as data
-------------

Add it to the `:deps` in your `deps.edn` as a Git dependency,
with the SHA of the latest commit on `master`
(requires Clojure 1.11+ & Java 11+):

```clojure
io.github.simongray/podcast-clj {:git/sha "…"}
```

For ClojureScript, also set `:deps true` in your `shadow-cljs.edn`,
since shadow-cljs only reads Git dependencies from `deps.edn`.

Then try parsing a feed as a Clojure map:

```clojure
(require '[dk.simongray.podcast.feed.rss :as rss])

(def feed (rss/parse (slurp "https://feeds.podcastindex.org/pc20.xml")))

(:rss/title feed)
;; => "Podcasting 2.0"

(-> feed :rss/items first :podcast/transcripts)
;; => [{:url "https://…" :type "application/x-subrip" :rel "captions"}]
```

Each key has the namespace of its vocabulary, and a report in the metadata says
what podcast-clj repaired or normalised while parsing. For example, this feed
writes `itunes:explicit` as `no` instead of `false`, gives its SRT transcripts
the type `application/srt`, and uses the deprecated `podcast:images`:

```clojure
(require '[dk.simongray.podcast.diagnostics :as diagnostics])

(map :id (diagnostics/summarize (rss/report feed)))
;; => (:quirk/explicit-value :quirk/transcript-type :quirk/deprecated-images)
```

For one answer across the vocabularies, use `feed.resolve`:

```clojure
(require '[dk.simongray.podcast.feed.resolve :as resolve])

(select-keys (resolve/properties feed) [:title :author :categories])
;; => {:title "Podcasting 2.0" :author "Podcast Index LLC" :categories [["News & Politics"] ["Technology"]]}
```

**NOTE:** on both platforms, podcast-clj repairs the most common XML errors of
feeds, e.g. bare ampersands. With `org.jsoup/jsoup` on the classpath, it also
reads feeds on the JVM that are still _not_ well-formed XML. In Node, you also
need a `DOMParser` to read XML, e.g. that of `@xmldom/xmldom` as
`globalThis.DOMParser`.


An app in a few lines
---------------------

You can create a full-featured podcast app with the `app.*` namespaces, and
sync the listener's devices through the backends of `sync.*`, e.g. a folder,
WebDAV or the gpodder API. Subscribing, playing, refreshing, and syncing are
each one function call.
A call that changes the library returns a new version, and playing, refreshing
and syncing also record what they did in it:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.sync.file :as file])

;; make a library for this device, and subscribe to a show
(def url "https://feeds.podcastindex.org/pc20.xml")
(def lib (atom (-> (library/make "laptop" {:name "Laptop"})
                   (library/subscribe url (podcast/fetch! url)))))

;; the id of the newest episode
(def id (let [[show item] (first (library/episodes @lib {:order :newest}))]
          (library/episode-id @lib show item)))

;; what an episode page shows, e.g. the show notes, chapters and transcript
(merge (library/episode @lib id) (library/read-files! @lib id))

;; tell the library that the player is 754 seconds into the 3600-second
;; episode, and get back what to do with the media element: nothing, here
(:effects (swap! lib library/step [:progress id 754 3600]))
;; => []

;; fetch the feeds that are due for a check, and list the new episodes;
;; the result is the library from before the fetch plus what it fetched, so
;; merge it in, or anything done during the fetch, e.g. playing, is undone
(-> (swap! lib library/merge (library/refresh! @lib)) :refresh :added)
;; => [] right after subscribing, since nothing is due yet.
;;    Later, the new episodes: [["917393e3-…" "PC20-274"]]

;; sync the profile with the listener's other devices through a Dropbox folder,
;; and see what failed and what the step reported
(def dropbox (file/backend "/Users/simon/Dropbox/Podcasts"))
(-> (swap! lib library/merge (library/sync! @lib [dropbox])) :sync :report)
;; => []

;; the listener's profile: the subscriptions, queue, progress, stars and more
(:profile @lib)
```

**NOTE:** the profile is a CRDT, so change it with the functions of `profile`,
not with `assoc`. [Syncing](doc/sync.md) explains how the devices merge.

A podcast app in a browser also needs a server: a proxy for the files that
other sites don't let a page read, and a folder to sync through. The
[podcast server](server/README.md) in `server/` is that server for one
listener. It serves the Ring handlers of `util.proxy`, `sync.webdav.server` and
`sync.gpodder.server`, which you can also mount in a server of your own.

Documentation
-------------

Learn more:

- [Quickstart](doc/quickstart.md): a podcast app at a REPL, step by step.
- [How to build a podcast app](doc/building-an-app.md): every step of an app, from finding a show
  to syncing devices.

Look up:

- [Namespaces](doc/namespaces.md): a line for each namespace.
- [The feed model](doc/feed-model.md): every element with its key and type.
- [Diagnostics](doc/diagnostics.md): every diagnostic that a report can hold.
- [Sync reference](doc/sync-reference.md): the store contract, the documents,
  and where the sync clients keep their folders.
- [Services and their defaults](doc/services.md): what podcast-clj knows about each service.

Understand:

- [Principles](doc/principles.md): what guides the design of podcast-clj.
- [Architecture](doc/architecture.md): how each part of podcast-clj is built.
- [Syncing](doc/sync.md): why the sync between devices is safe.

Background:

- [Podcasting and Clojure, September 2026](doc/podcasting-and-clojure-2026.md):
  the ecosystem that podcast-clj was built for, from Podcasting 2.0 to its
  directories and services, and how its features compared.


Development
-----------

To run the tests, build the jar, or start the inspector, an example app that
uses only the public API, see [Development](doc/development.md).


License
-------

The podcast-clj project is licensed under the [MIT licence](LICENSE).
