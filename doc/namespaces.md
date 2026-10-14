# Namespaces

Every namespace of podcast-clj is under the root namespace,
`dk.simongray.podcast`, and they come in three kinds:

- **Where you start.** The root namespace fetches a feed. The `app`
  namespaces combine the rest into a few calls for an app: one call each to
  subscribe to a show, refresh the feeds, play an episode, sync the
  listener's devices, or get everything that a show page or an episode
  page needs. The subscriptions, feeds, queue and play progress are all in
  one map, the library.
- **One part of podcasting each.** The other namespaces each cover one
  part, such as the feed model in `feed`, the listener's profile in
  `profile` and payments in `value`. Many of them are named for the
  standard they implement, e.g. `format.opml`, `podping` or
  `value.nwc`. Use them when you need one part, or more control than the
  `app` namespaces give, or when you're not building an app, e.g. for a
  host that serves its feeds or a server that syncs profiles.
- **Not specific to podcasts.** The `util` and `nostr` namespaces hold
  general tools, e.g. JSON, dates, and the Nostr protocol and its
  cryptography.

The `app` namespaces are built from all the others, and nothing outside
`app` requires them, so you can use any other namespace without them.
[Architecture](architecture.md#layers) explains the rule, which a test
checks.

Libraries in repositories of their own do some of the work:

- [wary-fetch](https://github.com/simongray/wary-fetch) sends the
  requests, and its namespaces are the `http`, `url`, `proxy`, `async`
  and `secret` of these docs
- [qname-hiccup](https://github.com/simongray/qname-hiccup) reads and
  writes the XML of feeds and lists
- [html-pieces](https://github.com/simongray/html-pieces) reads the HTML
  in show notes and comments
- [drop-in-search](https://github.com/simongray/drop-in-search) is the
  full-text search
- [mind-meld](https://github.com/simongray/mind-meld) is the CRDT that the
  profile is built on, and
  [like-minded](https://github.com/simongray/like-minded) syncs it and
  holds the stores
- [websub-clj](https://github.com/simongray/websub-clj) does WebSub
- [webcrypto-clj](https://github.com/simongray/webcrypto-clj) gives bytes,
  their encodings and hashes
- [id3-clj](https://github.com/simongray/id3-clj) reads the ID3v2 tags of
  media files, and writes them too
- [webvtt-clj](https://github.com/simongray/webvtt-clj) reads and writes
  WebVTT transcripts, as a browser reads them
- [ical-clj](https://github.com/simongray/ical-clj) parses the recurrence
  rules of podcast:updateFrequency and works out when they recur

The tables below follow the order of the three kinds. Names in them are
relative to the root, and everything runs on the JVM and in ClojureScript
unless the table says otherwise. [Principles](principles.md) has the rules
that every namespace follows.

A namespace that talks to a service works in the same three steps. Its
`request` builds a request map, a function such as `http/send!` sends the
map and returns the response, and its `response` reads the answer. A
`call!` does all three for a call that you name, e.g. `:search`, and the
`:send` option replaces the function that sends.


## The root namespace

| Namespace | Contents |
|---|---|
| `dk.simongray.podcast` | Fetching a feed in one call with `fetch!`, which sends a conditional request, parses the answer and joins the pages of a paged feed. `fetch-list!` does the same for an OPML list. |

## Apps

| Namespace | Contents |
|---|---|
| `app.library` | The subscriptions, feeds, queue and play progress of an app in one map, with the config that every call uses. Most apps need only `make`, `subscribe`, `refresh!`, `next-refresh`, `merge`, `episodes`, `show`, `episode`, `playback`, `step`, `update-profile`, `search`, `sync!` with a `backend`, `plan`, `save!` and `restore!`, and `notifications` to announce new episodes. For an episode of a show without a subscription, there's `add-episode`, for a show of local files, `subscribe-local`, and for a library of hundreds of shows, `load!` and `unload!` keep only some feeds in memory. A server that WebSub hubs push feeds to uses `hub-requests` and `pushed`. |
| `app.media-session` | The Media Session of a browser for the episode that plays: which `actions` do something, and the `events` of `library/step` for each. |
| `app.show` | Everything a show page needs in one map, from `view`, and the shows of the podroll and the publisher from `fetch-linked-shows!`. |
| `app.episode` | Everything an episode page needs in one map, from `view`, what the chapters file, transcript and media file say from `read-files!`, the value blocks that its time splits point at from `fetch-remote-values!`, and the metadata of the Media Session from `metadata`. |
| `app.feeds` | The fetched feeds by guid, with their ETag, Last-Modified and next check. Use `due` for the feeds to fetch now, `refresh!` to fetch them, and `affected` for the feeds that a Podping names. A device that never shows the feeds can keep only what `slim` gives of each. |

## Profile, criteria and downloads

| Namespace | Contents |
|---|---|
| `profile` | The listener's profile, a CRDT that syncs between their devices: the subscriptions and their settings, the preferences, single episodes of other shows, progress, saved filters, listening counts and devices. Use `merge` to combine two profiles, `compact` to drop old tombstones and archive old played episodes, and `sync-options` to sync it through like-minded. |
| `profile.queue` | The queue, the inbox and the playlists of the profile, with `to-list-feed` for a playlist as a list feed, and `refresh` for the new episodes of a feed. |
| `profile.marks` | The stars, bookmarks, clips and ratings of the profile. |
| `profile.payments` | The record of the listener's payments in the profile, with `unsettled` for those that went to the wallet with no answer, which mustn't be sent again. |
| `profile.opml` | The OPML lists that the listener follows, and the subscriptions as OPML with `to-opml` and `from-opml`. |
| `profile.player` | The events of a player, with `step`, which returns the new profile and the effects for the UI. |
| `criteria` | Criteria as data that select and order episodes, with `episodes`, for saved filters and download rules. Use `problem` to check criteria from another device. |
| `downloads` | Which episodes to download and which files to delete, from the listener's rules, with `plan`, and the record of the files on a device that the plan reads, kept by `start-download`, `finish-download`, `remove-file` and `keep-file`. |

## Sync

The devices sync through
[like-minded](https://github.com/simongray/like-minded), and what they
sync is the profile, a CRDT of
[mind-meld](https://github.com/simongray/mind-meld). For `library/sync!`,
a backend from `library/backend` gives like-minded the profile's
`sync-options`. The sync and a saved library use like-minded's stores: a
folder, a WebDAV collection through
[webdav-clj](https://github.com/simongray/webdav-clj), IndexedDB, a
key-value store, memory, and an encrypted form of any of them.

| Namespace | Contents |
|---|---|
| `gpodder` | The gpodder API as a backend, for gpodder.net, the GPodder Sync app for Nextcloud and other servers, with `identities` to map between URLs and guids. |
| `gpodder.api` | The gpodder API as data: `request` for the request map of a call, `response` for what its answer says, and `apis` for the paths of each server. |
| `gpodder.server` | The gpodder API as a Ring handler over the profile of one listener, for AntennaPod, Kasts and gPodder, with `stepper` to sync that profile through other backends. |

## Feeds

| Namespace | Contents |
|---|---|
| `feed.rss` | Parsing and writing feeds: `parse` reads an RSS document into a feed map, `emit` writes one back, `report` gives the diagnostics, and `parse-tree` stops at the Hiccup. For a document that may not be a feed, `try-parse` gives a diagnostic where `parse` throws. |
| `feed` | Hiccup to feed maps and back, and `diff` between two parses. |
| `feed.resolve` | One answer for each property where the vocabularies overlap, with `properties` for all of them and `sources` for where each came from. Also the listening order and `blocked?`. |
| `feed.schedule` | When a feed says that it changes, with `next-episode` from its update frequency and `next-check` for when to fetch it again. |
| `feed.discovery` | Finding the feeds of a show from what a listener types, such as a domain, a web page or a link of an app, in one call with `find-feeds!`, and the start of a feed for a preview with `fetch-head!`. For more control, `read-input` reads the text, `feed-links`, `header-links` and `feed-anchors` read a page, and `ranked` orders the candidates. |
| `feed.check` | Checks over the network of the files that a feed links to, by the standards and the requirements of PSP-1 and the directories. Only `check-files!` sends requests, and `files` and `report` are pure. |
| `feed.check.rules` | The rules that judge the facts of a check, by the standards and the `requirements` of PSP-1 and the directories, with `verdicts` for one file. |
| `feed.check.facts` | The facts of the files of a check, gathered over the network for `check-files!`. |
| `feed.validate` | Validation against the registry, with the requirements of PSP-1 and the directories. |
| `feed.publish` | What a host does to a feed: `with-defaults` fills in what a producer leaves out, `imported` and `moved` take a show from another host or away, `pages` splits a long feed as RFC 5005 describes, `for-subscriber` makes a private feed, `update-media-urls` and `update-file-urls` rewrite the URLs of the media files, or of every file, and `response` serves it to a Ring request. |
| `feed.search` | The shows and episodes of feeds as documents for full-text search, with `index` for an index of them that weighs the title most, `with-feed` and `without-feed` to change it a feed at a time, and `located` to give each result its feed and item. |
| `feed.guid` | The identity of feeds, items and episodes: `feed-guid`, `item-guid` and `episode-id`, and the `podcast:guid` that `from-url` derives from a feed URL. |
| `feed.integrity` | The `podcast:integrity` of an alternate enclosure: `sri` reads it, and `verify!` checks a file against it. |
| `feed.local` | A show of local files that no feed serves, e.g. an audiobook: `feed` builds its feed from what each file says about itself, for `library/subscribe-local`. |
| `feed.spec` | The tag registry as data, which `registry` indexes or extends, the enumerations of the specs, and the URIs of the namespaces. |
| `feed.repair` | The repairs for malformed XML, which `parse-raw` runs when the strict parse fails. |
| `feed.quirks` | The normalisations of the tree, and the variant namespace URIs that feeds use. |
| `feed.coerce` | The value types of the registry, from strings and back. |
| `feed.schema` | Malli schemas of the model, generated from the registry. Malli isn't a dependency. |
| `diagnostics` | Every diagnostic id with its level and meaning. Also `summarize`, which groups a report by kind, and `fetch-failure` for a fetch that gave no document. |

## Directories and updates

| Namespace | Contents |
|---|---|
| `directory` | Search and charts across Apple Podcasts, fyyd, gpodder.net and the Podcast Index, with one shape for all results. Both `search!` and `fetch-chart!` ask them all at once, `call!` asks one for the episodes of a show, by a person, recent or live, and `identify!` finds the ids of a show known by its feed. Also `fetch-category-names!`, the names of Apple's categories in another language. |
| `directory.podcastindex` | The Podcast Index API, with signed requests and answers mapped onto the feed model, and `fetch-remote-item!` and `fetch-remote-value!`, which resolve remote items. |
| `directory.links` | Links to a show or an episode in other apps: `ids` reads the ids of a show, `links` writes the links, `link-ids` reads the ids back from a link, and `at-time` links to a moment in an episode. |
| `podping` | Podping: request maps for the Hive chain, and pings read from blocks and checked against the known writers. On the JVM, `poll!` reads the latest pings. For hosts, `notify!` tells the service of Podping that a feed changed. |

## Formats

| Namespace | Contents |
|---|---|
| `format.chapters` | JSON Chapters and Podlove Simple Chapters, read and written, and chapters from the timestamps in show notes, with `at` for the chapter at a position and `jump` for the target of a jump to the next or previous one. |
| `format.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, with speakers and the word timing of WebVTT, and `emit-srt`, `emit-vtt` and `emit-json` to write the first three. Also `matches`, the passages that match a query, with their times. |
| `format.opml` | Subscription lists as OPML, with folders, categories, creation times and includes of other lists. |
| `format.image` | The type, size and colour model of a PNG, JPEG, GIF or WebP image from its header, and the type alone of other images, fetched by `read!` with only the bytes it needs. Also `data-url` and `from-data-url` for data URLs, and `colors`, which picks the colours of a UI from an image, with `dominant-color`, `palette`, `complementary-color` and `contrast-color` for one colour at a time. |
| `format.media` | The duration, chapters, artwork, codecs and video size of a media file, read with byte ranges by `read!`. |
| `format.hls` | HLS playlists: the variants, renditions and duration of a stream, read by `read!`, and `playlist?` to tell a stream from a file. |
| `format.media.id3` | What the ID3v2 tags at the start of an MP3 or AAC file say about it, as id3-clj reads them: the tags, the duration, the artwork and the chapters in seconds. |
| `format.media.mp3` | The MPEG audio frames of an MP3 file, and its duration, chapters and artwork, in the shape that `format.media.mp4` gives them. |
| `format.media.mp4` | The boxes of an MP4 or M4A file: its duration, chapters, artwork, codecs, video size and whether the movie comes first. |
| `format.media.ogg` | The pages of an Ogg file of Opus or Vorbis: its duration and codec, and the chapters and artwork of its comments. |
| `format.media.aac` | The ADTS frames of an AAC file, after its ID3v2 tags: its duration, codecs, chapters and artwork. |

## Comments

| Namespace | Contents |
|---|---|
| `comments` | Comment threads from ActivityPub, the AT Protocol and Nostr, in one shape. Pick the thread of an episode with `thread` and read it with `fetch!`, and a second `fetch!` continues where the first stopped. Use `changes` for what's new between two readings. For display, `tree` arranges the comments, and `parts`, `hiccup` and `text` give the content of a comment. |
| `comments.activitypub` | ActivityPub threads, with each post fetched from its own server, and the Mastodon API when a server won't give the root post. |
| `comments.atproto` | AT Protocol threads from an AppView, with the labels of the protocol, and `at-uri` to split an at URI. |
| `comments.nostr` | Nostr threads from relays, with verified events only. The thread about a show or an episode comes from `about`, by its NIP-73 id. The events of a comment, a reply, a reaction and a deletion come from `comment-event` and the others, and `post!` signs one and gives it to the relays of the thread. |
| `comments.common` | What the readers share: values from JSON answers, problems with an answer, and safe HTML for a post. Apps don't call it. |

## Value for value

| Namespace | Contents |
|---|---|
| `value` | Splits, fees and time splits, and one shape for a payment, with `boostagram` and `parse-boostagram` for the bLIP-10 record, `records` for the custom records of a keysend payment, and `as-received` to check a record against what the node received. |
| `value.lnaddress` | Lightning addresses: `request-invoice!` gets an invoice with LNURL-pay and checks its amount, `fetch-terms!` gets the terms, and `fetch-node!` gets the node for keysend. |
| `value.recipients` | The recipients of the value blocks of a feed: `problems` in a feed, `check!` of their Lightning addresses, and `pay!`, which pays them all through the listener's wallet. |
| `value.bolt11` | The invoices of BOLT 11: `invoice-amount`, `payment-hash`, and `proves?`, which checks a preimage against an invoice. |
| `value.payment` | The JSON fields of the payment map, which the bLIP-10 record and the RSS Payment Metadata share. Apps don't call it. |
| `value.metadata` | The RSS Payment Metadata: the comment of a payment to a Lightning address, and the `x-rss-payment` header that `fetch!` reads. |
| `value.nwc` | Nostr Wallet Connect (NIP-47), for paying from the listener's wallet. Read a connection URI with `parse`, make a command such as `pay-invoice` or `get-balance`, and send it with `ask!`, which returns the answer. |
| `value.webln` | WebLN, the wallet interface of a web page. In ClojureScript, `pay-invoice!` and `pay-keysend!` call the provider. |

## Analytics

| Namespace | Contents |
|---|---|
| `analytics` | Download counts from the request logs of a host, by the rules of the IAB, with `downloads`. Also `threshold`, for the bytes of a minute of a file, and `user-agents` and `identify`, which name apps and bots from the OPAWG list. |
| `analytics.logs` | The lines of a server log as the requests that `downloads` takes, from the combined format of Apache and nginx or the extended format of the W3C, with `requests`. |
| `analytics.op3` | OP3: `with-prefix` puts its prefix on the enclosures of a feed, and `call!` and `fetch-downloads!` read its API. |

## Utilities

| Namespace | Contents |
|---|---|
| `util.json` | JSON to data and back, on both platforms. |
| `util.time` | Dates, durations and timestamps. |
| `util.client` | The User-Agent of podcast-clj, and `send!`, the `:send` of every function that sends unless you give another. |
| `util.ring` | The Ring requests that the handlers answer, and their responses: `body-bytes` and `body-text` read their bodies within a limit, `credentials` reads their Authorization header, `not-modified?` their conditions and `addressed-to?` their Host header, and `response` and `text-response` make the responses. |
| `util.bytes.view` | Views of files whose bytes come in parts, which a reader of media files or images reads. |
| `util.bytes.ranges` | `read!`, which fetches the byte ranges that a reader of a view asks for until it's done, and fetchers of a local file or a Blob. |
| `util.bech32` | Bech32, the encoding of BIP-173. |
| `util.entities` | Character and entity references in the text of feeds, HTML's included: `decode-entities` replaces them with the characters they stand for. |
| `util.names` | The keys of the maps, from camelCase names: `kebab`, and `attr-key` for the attributes of XML, and back to camelCase with `camel`. |
| `testing` | Stand-ins for the network in the tests of an app: `sender`, which answers requests from a map, with a 304 for a copy that's still current, and `feed`, the XML of a podcast feed. |

## Nostr and cryptography

| Namespace | Contents |
|---|---|
| `nostr` | The Nostr protocol that the comments and the wallets use: events and their signatures, NIP-19 names, the filters and messages of relays, and `send!` for one exchange with a relay, which can also publish an event. |
| `nostr.signer` | Signers of the listener's events: `local` over a key that the app holds, `nip07` over a browser extension, and `remote` over a remote signer of NIP-46, with `sign!`, which checks what a signer signed. |
| `nostr.secp256k1` | Schnorr signatures over secp256k1, as BIP-340 defines them: `verify`, `sign` and `public-key`, and `shared-x` for the shared secret of two keys. |
| `nostr.nip44` | The encrypted payloads of NIP-44, version 2, with the ChaCha20 stream cipher of RFC 8439 that they need. |
