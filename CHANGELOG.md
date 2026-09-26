# Changelog

## Unreleased

Working toward 1.0, the first release.

- RSS 2.0, the iTunes, Google Play, Spotify, Media RSS, RawVoice and Podlove
  Simple Chapters vocabularies and the whole Podcasting 2.0 namespace, read
  into plain data and written back canonically, driven by one tag registry
  that also yields the validator, the Malli schemas and the generated
  reference.
- The registry is a value: `spec/registry` indexes the library's `tags`, or
  those plus a client's own entries, and every interpreter takes it under
  `:registry` in its options, so proprietary elements are parsed, emitted,
  validated and given schemas like the standard ones.
- Every diagnostic carries a `:message`, from the catalogue where the parser
  records only an id, so a report renders without a lookup.
- RFC 5545 recurrence rules from `podcast:updateFrequency` parsed, written
  and evaluated (`rrule`), so `resolve/next-episode` knows when the next
  episode is due.
- Chapters read from the timestamp lists in show notes
  (`chapters/from-text`), reported as inferred, for the many episodes that
  list them there and link no chapters file.
- `podcast/fetch!` on the JVM, from a URL to a parsed feed in one step with
  the conditional headers for polling and the feed's new-feed-url hint, and
  `resolve/next-check` for when to poll again.
- `resolve/episodes` and `seasons` put items in listening order by Apple's
  rules; `blocked?` reads the block tags for one platform; `enclosure` takes
  type and bitrate preferences.
- `value/boostagram` writes the bLIP-10 record a keysend payment carries.
- `search`: the items of a feed filtered and ordered by criteria as data,
  season, type, duration, dates, transcript, chapters, explicit, person,
  words, and the listener's status given a player state; and a full-text
  index over subscriptions that is a value, ranked by BM25, with prefix
  matching for search as you type, phrases and snippets, on both platforms.
  The field weights, BM25's parameters, the shortest word to complete and
  the order of the results are options of `query`.
- `feed/normalize` expands the shorthand a producer writes, a bare value
  for an element with only content and a single value for a repeatable
  one, and `emit` and `validate` take it; `validate` diagnoses a value of
  the wrong shape (`:validate/wrong-shape`) instead of throwing.
- `resolve/properties` gathers every resolved answer for a show or an
  episode into one map with plain keys, to merge with the raw fields, and
  `resolve/sources` names the fields each answer was read from.
- `directory`: Apple Podcasts, fyyd, gpodder.net and the Podcast Index
  behind one shape, search, lookup and charts as request maps and shows
  keyed like the model, `combine` for one entry per show across sources,
  and `search!` and `trending!` on the JVM asking every source at once.
- The library declares Clojure 1.11 as its floor and runs on Java 11; CI
  verifies the floor beside the current versions. A test of the
  architecture keeps every require pointing down a layer. The inspector's
  browser player has its views as data and is tested on jsdom.
- A guide, doc/building-an-app.md, from finding a show to playing it.
- The namespaces grouped by the architecture: `feed.coerce`,
  `feed.validate` and `feed.schema`; `format.chapters`, `format.transcript`,
  `format.opml` and `format.html`; `app.player` and `app.search`;
  `time.rrule`; `directory.podcastindex`. The inspector is `inspector.*`,
  outside the library's tree.
- A quirks layer that repairs malformed feeds, recognizes namespace URI
  variants and normalizes the deviations found in the wild, every step
  reported; jsoup as an optional lenient parser of last resort.
- `resolve`: one answer per property where the vocabularies overlap, with
  the inheritance rules of the specifications.
- Companion formats: JSON chapters and Podlove Simple Chapters, SRT, WebVTT,
  JSON, HTML and plain-text transcripts, OPML subscription lists, the
  `podcast:guid` derivation and value-for-value arithmetic.
- The Podcast Index API: request maps with the authentication computed,
  responses mapped onto the model, remote items and remote value blocks
  resolved.
- Embedded HTML read into Hiccup by a lenient, dependency-free parser, with a
  sanitizer for clients and renderings as plain text and Markdown.
- A player's state as data: the play queue, progress and played marks,
  skipping by the listener's preferred amounts, subscriptions with settings,
  OPML in and out, a merge for syncing devices and a reducer over player
  events.
- The same code and tests on the JVM, under Node and in a browser.
- A bundled feed inspector as the reference client.
