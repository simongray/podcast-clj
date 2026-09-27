# Changelog

## Unreleased

- RSS 2.0, the `podcast` namespace of Podcasting 2.0, and the iTunes, Google
  Play, Spotify, Media RSS, RawVoice and Podlove Simple Chapters
  vocabularies. `parse` reads them into plain data, and `emit` writes them
  back in canonical form.
- One tag registry drives the parser, the emitter, the validator, the Malli
  schemas and the generated reference. Each interpreter takes the registry
  under `:registry`, so an app's own elements work like the standard ones.
- A quirks layer that repairs malformed feeds, recognizes namespace URI
  variants, and normalizes common deviations. Each step goes into the
  report, and `:except` switches rules off. jsoup is an optional lenient
  parser for the last resort.
- A catalogue of all diagnostics, with level and message. `summarize` groups
  a report by kind.
- `resolve`: one answer per property where the vocabularies overlap, with
  the inheritance rules of the specifications. Also `properties`, `sources`,
  the listening order, `seasons`, `blocked?`, `next-episode` from RFC 5545
  rules, and `next-check` for polling.
- Companion formats: JSON chapters, Podlove Simple Chapters, and chapters
  from timestamps in show notes. SRT, WebVTT, JSON, HTML and plain-text
  transcripts. OPML. The `podcast:guid` derivation. Value-for-value
  arithmetic and the boostagram record. HTML in descriptions as sanitized
  Hiccup, text and Markdown.
- `podcast/fetch!` on the JVM: a URL to a parsed feed in one step. It
  returns the validators for the next poll, the body and the new feed URL.
- The directories Apple Podcasts, fyyd, gpodder.net and the Podcast Index,
  with one shape for all results. Requests are maps, signed at a time that
  the caller gives. `combine` gives one entry per show. `search!` and
  `trending!` on the JVM.
- Podping: Hive calls as request maps, pings read from blocks, writers
  verified, `poll!` for a poller, and `notify-request` for a host.
- The player state as a state-based CRDT: a ranked queue, progress and
  played marks, subscriptions with settings per field, and a device
  registry. A hybrid logical clock stamps each write. `merge` is the join,
  `compact` removes the tombstones that all devices know, and `step` handles
  player events.
- `search`: episodes filtered and ordered by criteria as data, and a
  full-text index over subscriptions, ranked by BM25, on both platforms.
- Sync through any document store, with one document per device or one
  shared document. Memory, folder and WebDAV stores, an encrypting wrapper,
  a WebDAV server for any store, `store/check!`, `sync/next-at`, and `async`
  for code that runs on both platforms.
- The same code and tests on the JVM, in Node and in a browser. A test of
  the architecture keeps each require pointing down the layers. The
  inspector is a reference web app, with a browser player that syncs through
  its own WebDAV folder.
- Hostile input takes linear time and bounded stack. No regular expression
  backtracks quadratically. Elements nest at most `xml/max-depth` levels in
  XML and `html/max-depth` in HTML. `:timeout-seconds` bounds the whole
  HTTP exchange. `sanitize` reads a URL the way a browser does.
  `sync/read-document` refuses a state that `player/problem` finds fault
  with, and an envelope asks for at most `encrypted/max-iterations`.
- Stores, servers and nodes that fail do not give wrong results. A
  conditional put in the memory and folder stores is one step. A document
  that a store lost is written again, and a document listed without a
  version is always fetched. `sync!` refuses backends with the same name.
  The WebDAV server limits the size of a put and accepts `If-Match: *`.
  Podping goes to the next node when a node answers with an error, and
  `poll!` reads again the blocks that a node left out. A directory that
  refuses a request is a failure, not an answer with no shows.
- Odd values in a feed give no exception and no wrong answer. A date that
  does not exist does not read, and an unknown zone reads as UTC. An
  integer that JavaScript cannot hold exactly does not read, on the JVM
  either. `next-check` ignores items dated in the future and waits at most
  a year. The chapter and transcript readers take any text. A no-break
  space is whitespace, and a space between two inline elements stays. The
  repairs remove junk before and after the document, as a PHP warning
  leaves it.
- `validate` checks the type of each value, not only that it read, and
  reports negative numbers and keys that the registry does not define,
  such as `:rss/titel`. `emit` leaves out nil values and writes a value of
  another type as it is: `:no` stays no. It writes back only the unknown
  attributes that XML can hold, and leaves out the elements of an
  undeclared prefix. `:namespaces` wins over the URIs of the library.
  `inner-xml` escapes text for HTML. The rule parser and the schemas agree
  on the ranges of RFC 5545, and srcset reads URLs with commas in them.
- The companion formats, search and the player take odd input. A script is
  text up to its end tag, and `html/text` leaves it out; a pre keeps its
  indentation, and plain text stays plain. The cue timings of a transcript
  count before a tag, and a blank line in a body survives SRT and WebVTT.
  OPML reads xmlUrl in any case. Search folds the marks that text holds
  already, handles any limit fast, and reads an index read back from EDN.
  `player/skip` ignores a duration of zero or NaN, `player/write` stamps
  the entries of an app, and `changes-since` takes the state as it was, so
  that nothing merged since is missed.
