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
  chapters document is clean data. What the reader had to repair or
  normalize is in the report, a vector of diagnostics in the metadata of the
  result, under `:podcast/report`. The `report` function of the same
  namespace reads it.
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
- **Requests are maps.** The directories, the Podcast Index, Podping and the
  WebDAV store build requests as `{:method :url :headers :body}`, and pure
  functions read the answers. Any HTTP client can send the requests.
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
| `feed` | Hiccup to feed maps and back. `normalize` expands the shorthand of a producer. `feed-guid` and `item-guid` give identity. `diff` compares two parses of a feed. |
| `feed.coerce` | The value types of the tag registry: strings to values, and back. |
| `feed.validate` | Validation by the tag registry, with the PSP-1 and Apple profiles. |
| `feed.schema` | Malli schemas of the model, generated from the tag registry. Malli is not a dependency. |
| `resolve` | One answer where the vocabularies overlap. `properties` gives all answers at once, and `sources` tells where they came from. Also the listening order of the episodes, `blocked?` and `next-check`. |
| `diagnostics` | Every diagnostic id, with its level and meaning. |

### Formats

| Namespace | Contents |
|---|---|
| `format.chapters` | JSON Chapters, Podlove Simple Chapters, and chapters from the timestamps in show notes. |
| `format.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, with speakers. |
| `format.opml` | Subscription lists as OPML, with folders, the categories and creation time of each outline, inclusions of other lists, and the dates and owner of the list. |
| `format.html` | The HTML in descriptions, as Hiccup: `parse`, `sanitize`, `hiccup`, `text`, `markdown` and `emit`. `feed-links` finds the feeds that a web page names. |
| `format.media` | What a media file says about itself: the duration, the chapters and the artwork, read with byte ranges by `read!`. `verify!` checks a file against its `podcast:integrity`. |
| `format.id3` | ID3v2 tags, versions 2.2 to 2.4, with chapters and pictures. |
| `format.mp3` | The MPEG audio frames of an MP3 file, and its duration. |
| `format.mp4` | The boxes of an MP4 or M4A file: its duration, its QuickTime or Nero chapters, and its artwork. |
| `value` | Value for value: splits, fees, time splits and remote blocks. `boostagram` writes the record of a payment. |

### Directories and notifications

| Namespace | Contents |
|---|---|
| `directory` | Apple Podcasts, fyyd, gpodder.net and the Podcast Index, with one shape for all results. JVM: `call!`, `search!` and `trending!`. |
| `directory.podcastindex` | The Podcast Index API: authenticated requests, responses mapped onto the model, and remote items resolved. JVM: `remote-value!` and `remote-item!`. |
| `podping` | Podping: calls to the Hive chain as request maps, pings read from blocks, and writers verified. JVM: `poll!`. `notify-request` is for a host. |

### Apps

| Namespace | Contents |
|---|---|
| `app.player` | The player state as a CRDT: queue, playlists, inbox, progress, stars, bookmarks, subscriptions with their settings, folders and tags, followed OPML lists, saved filters, counts of listening and devices. `merge`, `compact` and `step`. Playlists as list feeds. |
| `app.search` | Filters and orders of episodes as data, and a full-text index over subscriptions, ranked by BM25. |
| `app.sync` | The sync between devices: sync documents, `reconcile`, `sync!` over any backends, and `next-at`. |
| `app.sync.store` | The store contract, `memory`, `backend` over any store, and `check!` to test a store. |
| `app.sync.file` | JVM. A folder as a store: a folder that a sync client mirrors, or a Git working tree. |
| `app.sync.webdav` | A WebDAV collection as a store. |
| `app.sync.webdav.server` | JVM. A Ring handler that serves any store over WebDAV. |
| `app.sync.encrypted` | Any store, with its documents encrypted at rest. The JVM and WebCrypto open the documents of each other. |

### Primitives

| Namespace | Contents |
|---|---|
| `xml` | XML to Hiccup and back, with namespaces resolved by URI. |
| `xml.jsoup` | JVM. A lenient parser for feeds that are not well-formed XML. It needs the optional `org.jsoup/jsoup`. |
| `time` | Dates, durations and timestamps. |
| `time.rrule` | RFC 5545 recurrence rules: parse, write and evaluate. |
| `guid` | The `podcast:guid` UUIDv5, and SHA-1. |
| `json` | JSON strings to data and back, on both platforms. |
| `http` | Requests as maps, credentials as a header, URLs made absolute, and when a server says to ask again. JVM: the client that sends them, with conditional requests, byte ranges, and redirects that keep the credentials to their origin, and `digest!`, which streams a body through a digest. |
| `bytes` | Bytes on both platforms, read as numbers and text, and a view of the parts of a file that have arrived, which says what a reader still needs. |
| `async` | Deferred values: futures on the JVM, and promises in JavaScript. |
| `rank` | Ranks that always leave room for another rank between two ranks. |
