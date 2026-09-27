# Changelog

## Unreleased

- RSS 2.0, the iTunes, Google Play, Spotify, Media RSS, RawVoice and Podlove
  Simple Chapters vocabularies and the whole Podcasting 2.0 namespace, read
  into plain data and written back canonically, driven by one tag registry
  that also yields the validator, the Malli schemas and the generated
  reference. The registry is a value every interpreter takes under
  `:registry`, so a client's own elements are parsed, emitted, validated and
  given schemas like the standard ones.
- A quirks layer that repairs malformed feeds, recognizes namespace URI
  variants and normalizes the deviations found in the wild, every step
  reported and each rule switchable with `:except`; jsoup as an optional
  lenient parser of last resort.
- Every diagnostic catalogued with its level and a message, so a report
  renders without a lookup; `summarize` groups a report by kind.
- `resolve`: one answer per property where the vocabularies overlap, with
  the inheritance rules of the specifications; `properties` and `sources`;
  the listening order, `seasons`, `blocked?`, `next-episode` from RFC 5545
  recurrence rules and `next-check` for when to poll again.
- Companion formats: JSON chapters, Podlove Simple Chapters and chapters
  read from the timestamps in show notes; SRT, WebVTT, JSON, HTML and
  plain-text transcripts; OPML; the `podcast:guid` derivation;
  value-for-value arithmetic and the boostagram record; embedded HTML as
  sanitized Hiccup, text and Markdown.
- `podcast/fetch!` on the JVM, from a URL to a parsed feed in one step with
  the conditional headers for polling, the body and the new-feed-url hint.
- The Podcast Index, and the directories (Apple Podcasts, fyyd, gpodder.net
  and the index) behind one shape: requests as maps signed at a time the
  caller gives, responses keyed like the model, `combine` for one entry per
  show, `search!` and `trending!` on the JVM.
- Podping: the Hive calls as request maps, the pings read out of blocks,
  the writers verified, `poll!` as the poller's step, `notify-request` for a
  host.
- A player's state as a state-based CRDT: the queue as ranked entries,
  progress and played marks, subscriptions and their settings field by
  field, the listener's devices in a registry, a clock that stamps no write
  earlier than what the state has seen, `merge` as the join, `compact` for
  the tombstones every device has seen, and `step` over player events.
- `search`: the items of a feed filtered and ordered by criteria as data,
  and a full-text index over subscriptions that is a value, ranked by BM25,
  with prefixes, phrases and snippets, on both platforms.
- Syncing over any store of documents, one per device merged on read or one
  shared put on condition of its version: the memory, folder and WebDAV
  stores, the encrypting wrapper, a WebDAV server for any store,
  `store/check!` for a settings screen, `sync/next-at` for the cadence, and
  `async` so that the engine and the stores are written once for both
  platforms.
- The same code and tests on the JVM, under Node and in a browser; a test of
  the architecture that keeps every require pointing down a layer; a bundled
  feed inspector as the reference client, with a browser player that syncs
  through the inspector's own WebDAV folder.
