# Namespaces

podcast-clj has one root namespace, `dk.simongray.podcast`, and a namespace
for each part below it. This page gives the conventions that all namespaces
follow, and one line for each namespace. The docstrings give the details.


## Conventions

- **Reading does not throw on content.** The functions that read a format,
  such as `parse` and `chapters/parse-json`, keep a value that does not read
  as its type as a string. They leave out a value that cannot be a string,
  such as a JSON object as the title of a chapter. They throw only when the
  input is not in the format at all. The functions that write a format, such as `emit` and
  `transcript/emit-vtt`, write its canonical form.
- **What a reader forgave is in the report.** A parsed feed, transcript or
  chapters document is clean data. So is what a reader gives of a media
  file, an image or the record of a payment. What the reader had to repair
  or normalize is in the report, a vector of diagnostics in the metadata of
  the result, under `:podcast/report`. The `report` function of the same
  namespace reads it. Metadata does not survive printing. So data that an
  app keeps or sends holds its report as data, under `:report`: the result
  of a step such as `sync!`, a thread of comments, and the facts of a
  check.
- **A diagnostic is a map.** It has an `:id`, a `:level` (`:info`,
  `:warning` or `:error`) and a `:message`. Other keys give details, such as
  a `:path` into the data or the `:tag` of an element.
  [Diagnostics](diagnostics.md) lists all ids. `summarize` groups
  diagnostics by kind.
- **An accessor of `resolve` gives one answer.** Where vocabularies overlap,
  the docstring of the accessor gives the rule that selects the answer. Most
  accessors take a feed or an item. Where the specifications let an item
  inherit from its channel, the accessor also takes the feed and the item.
  It then applies the inheritance.
- **Options are a map.** Required arguments are positional. Optional
  behavior is a map of options, never a positional flag:
  `(podcast/parse xml {:quirks? false})`,
  `(validate/validate feed {:profile :psp-1})`.
- **Only the functions whose names end in `!` do I/O or read the clock.**
  All other functions take the time as an argument. For example, each write
  to the player state takes the instant of the write, and a Podcast Index
  request takes the instant to sign.
- **A call throws, and a step gives data.** A call sends one request and
  gives the answer. The calls are the functions with the name `call!`, the
  functions of a store, `http/fetch!`, `http/digest!`, and the function
  that `http/fetcher` makes. When a call has no answer to give, it throws
  an `ex-info`, and the `:type` in its data tells why. In ClojureScript, it
  rejects its promise with that `ex-info`. The client of an API throws
  `::failed` with the `:status` for an answer that is a failure. Each
  other function with a name that ends in `!` is a step, such as
  `podcast/fetch!`, `media/read!` and `sync!`. A step throws nothing for
  what a network, a server or a file does. It gives what it got, and the
  part that failed holds `:error`, a diagnostic with the `:reason`. An
  HTTP status is an answer, so `podcast/fetch!` and `http/probe!` give a
  404 as a `:status`. A call and a step throw for an argument that is
  wrong.
- **Requests are maps.** The directories, the Podcast Index, Podping and the
  WebDAV store build requests as `{:method :url :headers :body}`, and pure
  functions read the answers. Any HTTP client can send the requests.
- **What podcast-clj knows about one service is a default.** A public var
  holds the address, the paths, the limits or the rules of the service as
  data. An option of the same name replaces it for one call. Where an
  option takes the name of an entry, such as `:profile :apple`, it also
  takes an entry as data. [Services and their defaults](services.md) lists
  the defaults.
- **What a feed or a stranger names is fetched within limits.** The
  functions that fetch such URLs hold their limits in the `default-limits`
  of their namespace, and their options set each limit. The option
  `:allow?` tells which URLs a request can go to, and `:send` sends in
  place of `http/call!`. [Requests that strangers
  choose](architecture.md#requests-that-strangers-choose) tells more.
- **The code is `.cljc`.** Each namespace runs on the JVM and in
  ClojureScript, except the namespaces and functions that the table marks
  "JVM". In ClojureScript, a function that sends requests returns a promise.


## The namespaces

Each name below is relative to `dk.simongray.podcast`.

### The front door

| Namespace | Contents |
|---|---|
| `dk.simongray.podcast` | `parse`, `emit`, `report`, `summarize` and `parse-tree`. JVM: `fetch!`, which fetches a feed and parses it in one step, with the headers that a poller needs. |

### Feeds

| Namespace | Contents |
|---|---|
| `spec` | The tag registry as data. `registry` indexes or extends it. Also the enumerations of the specifications. |
| `quirks` | The repairs of malformed XML, the namespace URI variants, and the normalizations of the tree. |
| `feed` | Hiccup to feed maps and back. `normalize` expands the shorthand of a producer. `feed-guid` and `item-guid` give identity. `diff` compares two parses of a feed. For a host: `pages` splits a long feed into the documents of RFC 5005, and `join-pages` joins them. `for-subscriber` makes the private feed of one subscriber, and `update-media-urls` changes the URLs of the media files. |
| `feed.coerce` | The value types of the tag registry: strings to values, and back. |
| `feed.validate` | Validation by the tag registry, with the PSP-1 and Apple profiles. |
| `feed.schema` | Malli schemas of the model, generated from the tag registry. Malli is not a dependency. |
| `resolve` | One answer where the vocabularies overlap. `properties` gives all answers at once, and `sources` tells where they came from. Also the listening order of the episodes, `blocked?` and `next-check`. |
| `check` | Checks over the network of the files a feed links to: media files, images, transcripts, chapters and the feed itself, by the standards and by the profiles of PSP-1 and Apple Podcasts. `files`, `verdicts` and `report` are pure, and `feed!` sends the requests: two bytes of most files and the header of each image, unless it is asked to read them. `default-limits` holds its limits of files, time and bytes. |
| `diagnostics` | Every diagnostic id, with its level and meaning. |

### Formats

| Namespace | Contents |
|---|---|
| `format.chapters` | JSON Chapters, Podlove Simple Chapters, and chapters from the timestamps in show notes. `at` gives the chapter at a position, and `jump` gives where a jump to the next or the previous chapter goes. |
| `format.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, with speakers. |
| `format.opml` | Subscription lists as OPML, with folders, the categories and creation time of each outline, inclusions of other lists, and the dates and owner of the list. |
| `format.html` | The HTML in descriptions, as Hiccup: `parse`, `sanitize`, `hiccup`, `text`, `markdown` and `emit`. `feed-links` finds the feeds that a web page names. |
| `format.media` | What a media file says about itself: the duration, the chapters and the artwork, read with byte ranges by `read!`. `verify!` checks a file against its `podcast:integrity`. |
| `format.id3` | ID3v2 tags, versions 2.2 to 2.4, with chapters and pictures. |
| `format.mp3` | The MPEG audio frames of an MP3 file, and its duration. |
| `format.mp4` | The boxes of an MP4 or M4A file: its duration, its QuickTime or Nero chapters, and its artwork. |
| `format.image` | The header of a PNG, JPEG, GIF or WebP image: its type, its size in pixels, its color model and whether it can be transparent, and the coding process and EXIF orientation of a JPEG. The type of an AVIF, HEIC, HEIF, SVG, BMP, TIFF or ICO image. `read!` fetches only the bytes it needs. |
| `value` | Value for value: splits, fees, time splits and remote blocks. A payment in one shape: `boostagram` writes the bLIP-10 record of a payment, and `parse-boostagram` reads a record that a node received. `records` gives the custom records of a keysend payment. `as-received` puts a record next to what the node received. |
| `value.lnaddress` | Lightning addresses: the requests of LNURL-pay and of the keysend lookup, and the readers of their answers. `invoice` checks the amount of an invoice. `invoice!` and `node!` send the requests. |
| `value.metadata` | The RSS Payment Metadata: the comment of a payment to a Lightning address, and the header `x-rss-payment` of the URL that the comment names. `fetch!` gets the header. |

### Directories, notifications and comments

| Namespace | Contents |
|---|---|
| `directory` | Apple Podcasts, fyyd, gpodder.net and the Podcast Index, with one shape for all results, and their charts, also for one category. JVM: `call!`, `search!` and `trending!`. |
| `directory.podcastindex` | The Podcast Index API: authenticated requests, responses mapped onto the model, and remote items resolved. `categories` and `category-filter` name its categories. JVM: `remote-value!` and `remote-item!`. |
| `links` | Links to a show and to an episode in the apps of listeners, from a table of the forms of 48 apps and pages. `ids` reads the keys of a show, and `links` writes the links. `at-time` writes the media fragment of a time on the URL of a media file. |
| `podping` | Podping: calls to the Hive chain as request maps, pings read from blocks, and writers verified. JVM: `poll!`. `notify-request` is for a host. |
| `websub` | WebSub, for a server that a hub sends the feeds to. `discover` finds the hubs of a feed, `request` and `call!` subscribe, and `handler` is a Ring handler for the callback, which checks the signature of the hub. `publish-request` is for a host. |
| `comments` | The comments on an episode, from the thread that `podcast:socialInteract` names, over ActivityPub or the AT Protocol, in one shape. `thread` picks the thread of an episode. `fetch!` reads it, and a second `fetch!` reads on from where the first stopped. `tree` gives the comments in the shape to show. `requests`, `next-page` and `with-exchanges` are the pure steps of `fetch!`. |
| `comments.activitypub` | The threads of ActivityPub: a conversation, or the replies of each post, with each post from its own server. It reads through the Mastodon API when a server keeps the root post from the reader. |
| `comments.atproto` | The threads of the AT Protocol, from an AppView, with the labels of the protocol. `at-uri` gives the parts of an at URI. |
| `comments.common` | What the two readers share: the values in JSON answers, the problem with an answer, and the HTML of a post made safe to show. |

### Analytics

| Namespace | Contents |
|---|---|
| `analytics` | The downloads of episodes, counted from the requests that a host logs, by the rules of the IAB. `downloads` gives the downloads, and why the other requests do not count. `threshold` gives the bytes of one minute of a file, and `listener-ip` the part of an address that counts. `user-agents` reads the open list of user agents of the OPAWG, and `identify` names the app, the device and the bots by it. |
| `analytics.op3` | OP3, a free service that counts downloads through a prefix. `prefix` and `with-prefix` put its prefix on the URLs of enclosures. `request`, `response`, `call!` and `downloads!` use its API: the downloads of a show, the counts of shows and episodes, and the apps of the listeners. |

### Apps

| Namespace | Contents |
|---|---|
| `app.player` | The player state as a CRDT: queue, playlists, inbox, progress, stars, ratings, bookmarks, clips, subscriptions with their settings, folders and tags, followed OPML lists, saved filters, counts of listening and devices. `merge`, `compact` and `step`. `step` takes the events of a player, with the jumps between chapters and a sleep timer. Playlists as list feeds. |
| `app.search` | Filters and orders of episodes as data, and a full-text index over subscriptions, ranked by BM25. `problem` tells what in the criteria of a saved filter this version does not know. |
| `app.sync` | The sync between devices: sync documents, `reconcile`, `sync!` over any backends, and `next-at`. |
| `app.sync.store` | The store contract, `memory`, `backend` over any store, and `check!` to test a store. |
| `app.sync.file` | JVM. A folder as a store: a folder that a sync client mirrors, or a Git working tree. |
| `app.sync.webdav` | A WebDAV collection as a store. |
| `app.sync.webdav.server` | JVM. A Ring handler that serves any store over WebDAV. |
| `app.sync.encrypted` | Any store, with its documents encrypted at rest. The JVM and WebCrypto open the documents of each other. |
| `app.sync.gpodder` | The gpodder API as a backend: the subscriptions and the episode actions of gpodder.net, the GPodder Sync app for Nextcloud and other servers. `identities` names the shows and episodes of the app's feeds. |
| `app.sync.gpodder.server` | The gpodder API served over a player state, as a Ring handler, for AntennaPod, Kasts and gPodder. |

### Primitives

| Namespace | Contents |
|---|---|
| `xml` | XML to Hiccup and back, with namespaces resolved by URI. |
| `xml.jsoup` | JVM. A lenient parser for feeds that are not well-formed XML. It needs the optional `org.jsoup/jsoup`. |
| `time` | Dates, durations and timestamps. |
| `time.rrule` | RFC 5545 recurrence rules: parse, write and evaluate. |
| `guid` | The `podcast:guid` UUIDv5, and SHA-1. |
| `json` | JSON strings to data and back, on both platforms. |
| `http` | Requests as maps, credentials as a header, URLs made absolute, and when a server says to ask again. `call!` sends a request with byte ranges and limits of size and time: on the JVM with redirects that keep the credentials to their origin, and in ClojureScript with fetch. `probe!` learns the facts of a file from two bytes of it, and `fetcher` fetches a file by byte ranges. JVM: `fetch!`, which fetches a feed with conditional requests, and `digest!`, which streams a body through a digest. `public?` tells whether a host is on the public internet, and `call-public!` sends only to such hosts. `byte-ranges` reads a Range header, `links` reads a Link header, and `with-query` adds parameters to a URL. |
| `bytes` | Bytes on both platforms, read as numbers and text, and a view of the parts of a file that have arrived, which says what a reader still needs. `read!` fetches it for the reader until it is done, within the limits of bytes, time and rounds of `default-limits`. |
| `async` | Deferred values: futures on the JVM, and promises in JavaScript. `iteration` reads an API that answers in pages, as `clojure.core/iteration` does, on both platforms. |
| `rank` | Ranks that always leave room for another rank between two ranks, and that stay short at the ends of a list. `after` gives the ranks of many items that go to the end at one time. |
