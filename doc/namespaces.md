# Namespaces

Every namespace is under the root namespace, `dk.simongray.podcast`, and
they come in three kinds:

- **Where you start.** The root namespace fetches a feed. The `app`
  namespaces combine the rest into a few calls for an app: one call each to
  subscribe to a show, refresh the feeds, play an episode, sync the
  listener's devices, or get everything that a show page or an episode
  page needs. The subscriptions, feeds, queue and play progress are all in
  one map, the library.
- **One part of podcasting each.** The other namespaces each cover one
  part, such as the feed model in `feed`, the listener's profile in
  `profile` and payments in `value`. Many of them are named for the
  standard they implement, e.g. `format.opml`, `updates.websub` or
  `value.nwc`. Use them when you need one part, or more control than the
  `app` namespaces give, or when you're not building an app, e.g. for a
  host that serves its feeds or a server that syncs profiles.
- **Not specific to podcasts.** The `util` and `crypto` namespaces hold
  general tools, e.g. HTTP, XML, JSON, dates, a CRDT and the cryptography
  that Nostr uses.

The `app` namespaces are built from all the others, and nothing outside
`app` requires them, so you can use any other namespace without them.
[Architecture](architecture.md#layers) explains the rule, which a test
checks.

The tables follow the same order. Names in them are relative to the root,
and everything runs on the JVM and in ClojureScript unless the table says
otherwise. [Principles](principles.md) has the rules that every namespace
follows.

A namespace that talks to a service has the same three steps. Its
`request` builds a request map, a function such as `http/send!` sends the
map and gives the response, and its `response` reads the answer. A `call!`
does all three for a call that you name, e.g. `:search`, and the `:send`
option replaces the function that sends.


## The root namespace

| Namespace | Contents |
|---|---|
| `dk.simongray.podcast` | Fetching a feed in one call with `fetch!`, which sends a conditional request, parses the answer and joins the pages of a paged feed. |

## Apps

| Namespace | Contents |
|---|---|
| `app.library` | The subscriptions, feeds, queue and play progress of an app in one map, with the config that every call uses. Most apps need only `make`, `subscribe`, `refresh!`, `merge`, `episodes`, `show`, `episode`, `step`, `update-profile`, `sync!`, `plan`, and `save!` and `restore!` to keep it between runs. |
| `app.show` | Everything a show page needs in one map, from `view`, and the shows of the podroll and the publisher from `fetch-linked-shows!`. |
| `app.episode` | Everything an episode page needs in one map, from `view`, what the chapters file, transcript and media file say from `read-files!`, and the value blocks that its time splits point at from `fetch-remote-values!`. |
| `app.feeds` | The fetched feeds by guid, with their ETag, Last-Modified and next check. Use `due` for the feeds to fetch now, `refresh!` to fetch them, and `affected` for the feeds that a Podping names. A device that never shows the feeds can keep only what `slim` gives of each. |

## Profile, search and downloads

| Namespace | Contents |
|---|---|
| `profile` | The listener's profile, a CRDT that syncs between their devices: the queue, playlists, inbox, progress, stars, ratings, bookmarks, clips, subscriptions and their settings, followed lists, saved filters, listening counts and devices. Use `step` for the events of a player, `merge` to combine two profiles, and `compact` to drop old tombstones. |
| `search` | Filters and orders of episodes as data, and the search documents of episodes and shows, with `index` for an index of them that weighs the title most. |
| `downloads` | Which episodes to download and which files to delete, from the listener's rules, with `plan`. |

## Sync

| Namespace | Contents |
|---|---|
| `sync` | Sync between devices: `sync!` over any backends, `reconcile` and `next-sync`. |
| `sync.store` | The store contract, whose documents are text or bytes, with `memory`, `backend` for any store, and `check!` to test a store. |
| `sync.file` | A folder as a store, e.g. one that a sync client mirrors, or a Git working tree. It takes a path on the JVM and in Node, and in a browser a folder that the listener picks, the origin private file system or the files they load. |
| `sync.indexeddb` | Browser. An IndexedDB database as a store, e.g. for `library/save!`. |
| `sync.webdav` | A WebDAV collection as a store. |
| `sync.webdav.server` | JVM. A Ring handler that serves any store over WebDAV. |
| `sync.encrypted` | Any store, with its documents encrypted at rest and readable on both platforms. |
| `sync.gpodder` | The gpodder API as a backend, for gpodder.net, the GPodder Sync app for Nextcloud and other servers, with `identities` to map between URLs and guids. |
| `sync.gpodder.server` | The gpodder API as a Ring handler over the profile of one listener, for AntennaPod, Kasts and gPodder. |

## Feeds

| Namespace | Contents |
|---|---|
| `feed.rss` | Parsing and writing feeds: `parse` reads an RSS document into a feed map, `emit` writes one back, `report` gives the diagnostics, and `parse-tree` stops at the Hiccup. For a document that may not be a feed, `try-parse` gives a diagnostic where `parse` throws. |
| `feed` | Hiccup to feed maps and back, and `diff` between two parses. |
| `feed.resolve` | One answer for each property where the vocabularies overlap, with `properties` for all of them and `sources` for where each came from. Also the listening order, `blocked?` and `next-check`. |
| `feed.discovery` | Finding the feeds of a show from what a listener types, such as a domain, a web page or a link of an app, in one call with `find-feeds!`. For more control, `read-input` reads the text, `feed-links`, `header-links` and `feed-anchors` read a page, and `ranked` orders the candidates. |
| `feed.check` | Checks over the network of the files that a feed links to, by the standards and the requirements of PSP-1 and Apple Podcasts. Only `check-files!` sends requests, and `files`, `verdicts` and `report` are pure. |
| `feed.validate` | Validation against the registry, with the requirements of PSP-1 and Apple Podcasts. |
| `feed.publish` | What a host does to a feed before serving it: `pages` splits a long feed as RFC 5005 describes, `for-subscriber` makes a private feed, and `update-media-urls` rewrites the URLs of the media files. |
| `feed.guid` | The identity of feeds, items and episodes: `feed-guid`, `item-guid` and `episode-id`, and the `podcast:guid` that `from-url` derives from a feed URL. |
| `feed.spec` | The tag registry as data, which `registry` indexes or extends, and the enumerations of the specs. |
| `feed.quirks` | The repairs for malformed XML, which `parse-raw` runs when the strict parse fails, and the normalisations of the tree. |
| `feed.coerce` | The value types of the registry, from strings and back. |
| `feed.schema` | Malli schemas of the model, generated from the registry. Malli isn't a dependency. |
| `diagnostics` | Every diagnostic id, with its level and meaning, and `summarize`, which groups a report by kind. |

## Directories and updates

| Namespace | Contents |
|---|---|
| `directory` | Search and charts across Apple Podcasts, fyyd, gpodder.net and the Podcast Index, with one shape for all results. Both `search!` and `fetch-chart!` ask them all at once. |
| `directory.podcastindex` | The Podcast Index API, with signed requests and answers mapped onto the feed model, and `fetch-remote-item!` and `fetch-remote-value!`, which resolve remote items. |
| `directory.links` | Links to a show or an episode in other apps: `ids` reads the ids of a show, `links` writes the links, `link-ids` reads the ids back from a link, and `at-time` links to a moment in an episode. |
| `updates.podping` | Podping: request maps for the Hive chain, and pings read from blocks and checked against the known writers. On the JVM, `poll!` reads the latest pings. For hosts, there's `notify-request`. |
| `updates.websub` | WebSub, for a server: `discover` finds the hubs of a feed, `request` and `call!` subscribe, and `handler` is a Ring handler for the callback. For hosts, there's `publish-request`. |

## Formats

| Namespace | Contents |
|---|---|
| `format.chapters` | JSON Chapters and Podlove Simple Chapters, read and written, and chapters from the timestamps in show notes, with `at` for the chapter at a position and `jump` for the target of a jump to the next or previous one. |
| `format.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, with speakers, and `emit-srt`, `emit-vtt` and `emit-json` to write the first three. Also `matches`, the passages that match a query, with their times. |
| `format.opml` | Subscription lists as OPML, with folders, categories, creation times and includes of other lists. |
| `format.html` | HTML in descriptions as Hiccup: `parse`, `sanitize`, `hiccup`, `text`, `markdown` and `emit`. |
| `format.image` | The type, size and colour model of a PNG, JPEG, GIF or WebP image from its header, and the type alone of other images, fetched by `read!` with only the bytes it needs. |
| `format.media` | The duration, chapters and artwork of a media file, read with byte ranges by `read!`. Also `verify!`, which checks a file against its `podcast:integrity`. |
| `format.media.id3` | ID3v2 tags, versions 2.2 to 2.4, with chapters and pictures. |
| `format.media.mp3` | The MPEG audio frames of an MP3 file, and its duration. |
| `format.media.mp4` | The boxes of an MP4 or M4A file: its duration, chapters and artwork. |

## Comments

| Namespace | Contents |
|---|---|
| `comments` | Comment threads from ActivityPub, the AT Protocol and Nostr, in one shape. Pick the thread of an episode with `thread` and read it with `fetch!`, and a second `fetch!` continues where the first stopped. For display, `tree` arranges the comments, and `parts`, `hiccup` and `text` give the content of a comment. |
| `comments.activitypub` | ActivityPub threads, with each post fetched from its own server, and the Mastodon API when a server won't give the root post. |
| `comments.atproto` | AT Protocol threads from an AppView, with the labels of the protocol, and `at-uri` to split an at URI. |
| `comments.nostr` | Nostr threads from relays, with verified events only. The thread about a show or an episode comes from `about`, by its NIP-73 id. |
| `comments.common` | What the readers share: values from JSON answers, problems with an answer, and safe HTML for a post. |

## Value for value

| Namespace | Contents |
|---|---|
| `value` | Splits, fees and time splits, and one shape for a payment, with `boostagram` and `parse-boostagram` for the bLIP-10 record, `records` for the custom records of a keysend payment, and `as-received` to check a record against what the node received. |
| `value.lnaddress` | Lightning addresses: `request-invoice!` gets an invoice with LNURL-pay and checks its amount, `fetch-node!` gets the node for keysend, and `proves?` checks a preimage against an invoice. |
| `value.metadata` | The RSS Payment Metadata: the comment of a payment to a Lightning address, and the `x-rss-payment` header that `fetch!` reads. |
| `value.nwc` | Nostr Wallet Connect (NIP-47), for paying from the listener's wallet. Read a connection URI with `parse`, make a command such as `pay-invoice` or `get-balance`, and send it with `ask!`, which returns the answer. |
| `value.webln` | WebLN, the wallet interface of a web page. In ClojureScript, `pay-invoice!` and `pay-keysend!` call the provider. |

## Analytics

| Namespace | Contents |
|---|---|
| `analytics` | Download counts from the request logs of a host, by the rules of the IAB, with `downloads`. Also `threshold`, for the bytes of a minute of a file, and `user-agents` and `identify`, which name apps and bots from the OPAWG list. |
| `analytics.op3` | OP3: `with-prefix` puts its prefix on the enclosures of a feed, and `call!` and `fetch-downloads!` read its API. |

## Utilities

| Namespace | Contents |
|---|---|
| `util.xml` | XML to Hiccup and back, with namespaces resolved by URI. |
| `util.xml.jsoup` | JVM. A lenient parser for feeds that aren't well-formed XML, with the optional `org.jsoup/jsoup`. |
| `util.json` | JSON to data and back, on both platforms. |
| `util.time` | Dates, durations and timestamps. |
| `util.time.rrule` | RFC 5545 recurrence rules. |
| `util.http` | Requests as maps, and `send!`, which sends them with byte ranges and limits of size and time, with `try-send!` for a failure as data. Also `probe!`, which learns the facts of a file from two bytes, and `public?`, which checks that a host is on the public internet. The `fetch!` function fetches a feed with conditional requests. For another HTTP client, `revealed` unhides the credentials of a request. |
| `util.proxy` | A proxy on your own server for the requests of a web page: `handler` is its Ring handler, on the JVM, and `sender` gives the page a `:send` that sends each request through it. |
| `util.bytes` | Bytes on both platforms, and `read!`, which fetches the byte ranges that a reader asks for until it's done. Also hex, SHA-1, SHA-256 and HMAC. |
| `util.async` | Futures on the JVM and promises in JavaScript behind one API, `all` and `in-groups` to make calls at the same time, and `iteration` for paged APIs on both platforms. |
| `util.nostr` | The Nostr protocol that the comments and the wallets use: events and their signatures, NIP-19 names, the filters and messages of relays, and `send!` for one exchange with a relay, which can also publish an event. |
| `util.secret` | Secrets that print as `#<secret>`: `hide`, `reveal` and `hidden?`. |
| `util.bech32` | Bech32, the encoding of BIP-173. |
| `util.rank` | Ranks with room for another rank between any two, which stay short at the ends of a list. |
| `util.text` | Text for search on both platforms: `fold`, which drops case and diacritics, and `tokens`, the words of a text, with Chinese, Japanese, Korean and Thai in pairs of characters. |
| `util.text.search` | A full-text index ranked by BM25F: `index`, `add`, `remove` and `query`, with `parse` for its query language of AND, OR, NOT, phrases, prefixes, typos and fields. `plan` and `read-documents` keep it in a store, in the Common Index File Format. Also `snippet` and `matches?` for one text. |
| `util.crdt` | A CRDT of plain maps, which devices merge in any order, on a schema of collections. Write an entry with `write`, a series of them at one stamp with `at-once`, or with `as-device` for what another device did, and keep the registry of devices with `identify`, `retire` and `lagging`. The profile is one. |
| `util.crdt.list` | Ordered collections in such a state, e.g. the queue: `append`, `ranked`, `rank-at` for a move and `with-moves`, so that a move never brings back what another device removed. |

## Cryptography

| Namespace | Contents |
|---|---|
| `crypto.secp256k1` | Schnorr signatures over secp256k1, as BIP-340 defines them: `verify`, `sign` and `public-key`, and `shared-x` for the shared secret of two keys. |
| `crypto.chacha20` | The ChaCha20 stream cipher of RFC 8439. |
| `crypto.nip44` | The encrypted payloads of NIP-44, version 2. |
