# Changelog

## Unreleased

Working toward 1.0, the first release.

- RSS 2.0, the iTunes, Google Play, Spotify, Media RSS, RawVoice and Podlove
  Simple Chapters vocabularies and the whole Podcasting 2.0 namespace, read
  into plain data and written back canonically, driven by one tag registry
  that also yields the validator, the Malli schemas and the generated
  reference.
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
