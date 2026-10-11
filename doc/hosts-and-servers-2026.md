# Hosts, validators and public servers, October 2026

The earlier walks through the library took the part of a listener app
and of a sync integrator. On 11 October 2026 three more personas walked
it: a developer building a small podcast host, a developer building a
public feed validator, and a self-hoster putting the podcast server on
the public internet. Each walk ran against the real library at a REPL
and against real feeds, step by step as that person would, and every
finding below was run again before it went in. The needs report already
lists the gaps that the walks met on the way, such as the validator
rules for duplicates, categories and enclosure attributes, a feed
builder with defaults, a Ring response for serving, and the log parser;
those aren't repeated here.

A finding is a defect (a wrong answer), a gap (a step the persona can't
do with the library), something confusing (the right call exists but
misleads or hides), or a doc problem. Each ends with what it takes.


## The host

The host developer read an upload, built a feed from plain data, wrote
it, validated it, split it into pages, made a private copy, built the
Podping and WebSub requests, moved and imported a show, counted
downloads, wrote companion files and checked the files of a feed. Most
of it worked as the guide says: the media readers gave the duration,
chapters, artwork and codecs of real files; the feed built from data
round-tripped and its guid matched Python's `uuid5`; the pages followed
RFC 5005 exactly; the private copy closed the feed to directories and
took the OP3 prefix in the right place; the Podping and WebSub requests
had the shape the services take; fourteen hand-written requests counted
as IAB v2.3 says; and the check of a real feed came back clean.

### Defects

- **A producer's strings are written verbatim, and the validator calls a
  valid date invalid.** A feed map from a web form, with
  `:rss/pub-date "Thu, 01 Oct 2026 06:00:00 GMT"`, `:itunes/duration
  "30:00"` and `:itunes/explicit "no"`, emits as it is, and the
  validator answers `value "Thu, 01 Oct 2026 06:00:00 GMT" is not a
  valid rfc2822`, `value "30:00" is not a valid duration`, `value "no"
  is not a valid boolean`, all errors, while the written feed works in
  apps. The model wants an instant, a number and a boolean, and
  `feed/normalize` leaves strings alone. Either `normalize` reads a
  string under a typed key as the parser does, or the message says that
  the model takes an instant where a string came. A day either way.
- **`to-psc` and `from-psc` aren't inverses.** The first gives Hiccup,
  the second takes the model map, so `(from-psc (to-psc doc))` is an
  empty document, and the Hiccup fits nowhere in a feed map but
  `:xml/unknown`, where the validator reports it as unknown. Let
  `to-psc` give the model map of `:psc/chapters`, which emits and reads
  back. An hour.
- **A WebVTT cue without a voice span takes the previous cue's
  speaker.** A transcript whose third segment has no speaker comes back
  from `emit-vtt` and `parse` with the second's. The carry-over suits
  the "Name:" prefix convention, not voice spans, which WebVTT scopes to
  their cue; and SRT reports the carry-over while VTT doesn't. Carry
  over for prefixes only, and report it. An hour.

### Gaps

- **The private copy and the import stop at the enclosures.**
  `for-subscriber` puts the token on the media files alone, so the
  transcript, the chapters file and the artwork stay public or answer
  401 from a media server behind the token check, and
  `update-media-urls` leaves the same files on the old host after an
  import. The checker already lists every file URL of a feed with its
  path, so a walk over all of them, with the `:own-pred` to leave other
  servers alone, is a day.
- **An OP3 download names the prefixed URL.** The rows of
  `fetch-downloads!` carry `https://op3.dev/e/…` while the host's own
  rows carry the file's URL, so a count by URL over both splits each
  episode in two, against what the guide promises. An `op3/target` that
  strips the prefix, used by the reader. An hour.
- **Chapter images from ID3 become data URIs in the chapters file.** An
  upload with embedded chapter art gives `:img "data:image/png;base64,…"`,
  and `emit-json` writes a 287 KB chapters file for a nine-chapter
  episode. JSON Chapters says `img` is a URL, and a host serves the
  images as files. An option that takes the images as bytes and gives
  them a URL, on `emit-json` or on the reading. Half a day.

### Confusing

- **Every feed built from data gets "rss version is nil".** The
  validator warns when `:rss/version` isn't "2.0", and no producer sets
  it, since `emit` writes it. Warn only when it's present and wrong. An
  hour.
- **Requirement messages name model keys, not elements, and no item.**
  "rss:pub-date is recommended", "podcast:transcripts is recommended",
  once per item, 1,988 times for a feed with no transcripts. A
  podcaster searches Apple's guide and PSP-1 for `<pubDate>` and
  `<podcast:transcript>`. Take the name from the registry, name the
  item, and fold a recommendation missing from every item into one
  diagnostic with a count. Half a day.
- **An unresolved name is reported as a connection or TLS failure, with a
  Java class as the reason.** `:check/unreachable` with the reason
  `java.net.ConnectException: java.nio.channels.UnresolvedAddressException`
  for a domain typed wrong, a text a browser build never gives. A reason
  keyword per platform (`:dns`, `:connect`, `:tls`, `:timeout`) and a
  sentence for each. Half a day.
- **The chapters reader isn't called `parse`.** The transcript namespace
  has `parse` with sniffing beside `parse-json`; the chapters namespace
  has only `parse-json` and `from-psc`, so a host reaches for a function
  that isn't there. A `parse` that dispatches, or a line in the namespace
  doc. An hour.

### Docs

- **OP3's preview token is only in a comment.** The API refuses reads
  without a token, the guide says a key is needed, and the token that
  OP3 publishes for trying its API appears nowhere in the docs. Two
  dated sentences in [Services](services.md). An hour.

### What the host still writes

The feed map from the form and the coercion of its strings (about 50
lines); the walk that puts the token or the new server on every file
URL (15); the HTTP layer for the feed, which the needs report lists (40);
the log parser (30 per format); the data-centre and VPN predicate; the
OPAWG checkout (8); stripping the OP3 prefix (3); the chapter images to
files (10); the import that refuses a locked feed, keeps the derived
guid as a declared one, rewrites the file URLs and adds the self link
(15); the move (5).


## The validator

The validator developer fetched a well-formed feed with the limits an
untrusted URL needs, read its quirks, validated it under PSP-1 and Apple,
checked its files and resolved its properties, which all worked; then
parsed 27 feeds from the corpus study's exhibits, fetched nine edge
inputs live, checked three feeds with odd enclosures, and read eight
pages of the inspector as a podcaster.

### Defects

- **A duration like `103:12` is an error that makes a listed feed
  invalid.** The clock pattern allows two digits of minutes when there
  are no hours, so `70:00` reads as 4,200 seconds and `103:12` as
  nothing, and a 572-episode feed that Apple lists gets 15 errors and
  fails the Apple requirements. Let the minutes have any number of
  digits in that branch, and add the case to the tests. An hour.
- **A blank or relative enclosure URL is a warning, an absent one an
  error.** `url=""` gives `:validate/invalid-url` at warning level and
  the feed stays valid under Apple with 21 items nothing can play, while
  a missing `url` is an error. A blank required attribute should be
  reported as missing, and an enclosure without an absolute URL as an
  error under the directories' requirements. An hour.
- **`fetch!` reports a move to the feed's own URL.** Podbean and Substack
  write `itunes:new-feed-url` on every feed, naming the feed itself, and
  `fetch!` gives it as `:new-feed-url`; 14% of feeds carry the element.
  Drop the move when the declared URL is the one answered or the self
  link, and let the refresh do the same. An hour.
- **A guid that isn't a UUID becomes the feed's identity.** A feed
  declaring `<podcast:guid>https://hubhopper.com/podcast/…</podcast:guid>`
  gets that URL as its guid, while the Podcast Index derives the UUID from
  the feed URL, so every key the library makes (the subscription, the
  sync, the OP3 link) is one no directory shares. The validator warns,
  but the identity disagrees with the warning. Fall back to the derived
  guid when the declared one isn't a UUID, and say so in the warning; the
  design for identity should state the rule. An hour, plus the design.
- **A missing channel description is two errors.** The RSS 2.0 check and
  the Apple requirements both report it at the same path, so one fix
  counts twice. Skip in the requirements what the RSS check reported. An
  hour.

### Confusing

- **A 403 or 404 answer has no `:error`.** The docstring of `fetch!`
  promises an error "when there's no answer or no feed", and a 404 gives
  `:status 404` with the ETag and Last-Modified of the error page and
  nothing else, so a validator makes its own diagnostic, and one that
  stores the validators stores the error page's. Add the `:fetch/status`
  diagnostic that the refresh already makes, or say in the docstring
  what a non-200 answer holds. An hour.
- **Messages that embed the value defeat `summarize`.** The invalid
  value, enum, URL, guid and unknown-key diagnostics put the value in
  the message, so a feed with 15 odd durations gives 15 rows where the
  parse report gives one with the value as a detail. Keep the sentence
  generic and put the value and the tag in the diagnostic. An hour.
- **A duplicate element doesn't say which, and extra enclosures are a
  parser note.** 208 warnings with one sentence for a feed whose items
  carry several enclosures each, of which apps play the first. Name the
  element and the count in the message, and add a validator warning for
  an item with more than one enclosure. An hour.
- **The inspector's table drops the path.** A row reads "warning
  :validate/invalid-url 21 not an absolute URL" and names no item and no
  element. Render the path as "item 7 › enclosure › url" with a link to
  the item. An hour.
- **The inspector loses the library's own diagnosis.** It fetches and
  parses with the low-level functions and shows the exception's message,
  so an Atom feed reads "Not an RSS 2.0 document" where `try-parse` says
  it's Atom, and a 403 reads "HTTP 403 for …". Load with `fetch!` and
  show the diagnostic. An hour.

### Gaps

- **There is no verdict.** A fetch failure is under `:error`, a parse
  failure too, the quirks under `:report`, the file problems under the
  check's report, a 404 under nothing, and `valid?` covers the registry
  and the requirements alone; nothing says "this feed fails under Apple
  for these three reasons", and the inspector's page has two tables and
  no verdict line. A `diagnostics/verdict` over any reports (the worst
  level and the counts) and a report that joins the fetch, the parse and
  the validation of one fetch answer. A day.
- **A media type without a reader gets no facts and no diagnostic.** With
  `:read? true` a WAV enclosure gets a HEAD and a GET and nothing else,
  and the check page shows "audio/x-wav · 52 MB" and an empty column, so
  the podcaster reads "fine". Emit a diagnostic when a reading was asked
  and the type has no reader. An hour.
- **The inspector fetches without the guards a public validator needs.**
  Its feed page passes no `:allow-pred`, `:max-bytes` or
  `:timeout-seconds`, and its check page no `:allow-pred`, so it read a
  server on localhost on request, though the checker's own docstring
  says to give `url/public?` for strangers' feeds. The threat model
  lists it. One limits map for both pages, or a line in Development that
  the inspector is for a local machine. An hour.

### Docs

- **The guide has no section on validating a feed.** Neither the guide,
  the quickstart nor the README shows `validate`, `:requirements`,
  `valid?`, the parse report beside the validation, `try-parse`, or the
  three failure branches after `fetch!`; the host sections start at the
  file check. A "Validate a feed" section of about 40 lines before it.
  Half a day.
- **The levels are never defined.** Nothing says what `:error`,
  `:warning` and `:info` mean, or that a chosen requirement raises a
  level, so a validator guesses that an error under `:apple` means Apple
  Podcasts Connect rejects the feed. One paragraph in the diagnostics
  namespace, which the reference page is generated from. An hour.
- **`<guid>` is required under `:apple`, which Apple may list as
  recommended.** Two listed feeds fail the Apple requirements on that
  alone. PSP-1 requires the guid; Apple's guide, as far as both walks
  remember, recommends it and keys episodes on the enclosure URL without
  it. The standards audit checks the live page; if it agrees, the row
  moves to recommended. An hour.

### What the validator still writes

The facts a verdict page opens with (item count, order, undated items,
duplicates, items without guid, enclosure types and lengths, the next
page), about 40 lines, since `resolve/properties` gives the show and
nothing over the items; the failure branches after `fetch!` (15); the
verdict (10) and the words for the levels; the limits for an untrusted
URL, repeated for `fetch!`, `find-feeds!` and `check-files!` (5); the
filter that drops a move to the feed's own URL (2); and every comparison
with Apple that the needs report lists, 5 to 20 lines each.


## The public server

The third walk, by a self-hoster following the server's README without
Tailscale, is written up here when it completes.


## What to build first

1. The validator's defects: the duration pattern, the blank enclosure
   URL, the self-pointing move, the doubled description error, and the
   rule for a guid that isn't a UUID. A day in all, and every validator
   and every refresh is wrong without them.
2. The producer's path: strings coerced or named in the message, the
   nil version warning, `to-psc` as a model map, the VTT carry-over. A
   day.
3. The messages: element names and item numbers in the requirements,
   values as details, the element in the duplicate warning, the reason
   of an unreachable file. A day, and `summarize` and the inspector's
   table start to work for a podcaster.
4. The verdict, the "Validate a feed" section and the levels. A day and
   a half.
5. The file URLs of a private copy and an import, the OP3 target, the
   chapter images, the no-reader diagnostic, the inspector's guards and
   diagnosis. Two days.


## Questions

- Does Apple's guide list `<guid>` as recommended, and does Apple
  Podcasts Connect take a feed without guids? Both walks think so; the
  standards audit of the same day should settle it.
- Should `feed/normalize` read a producer's strings, which makes the
  plain-data path as lenient as the parser, or should the model stay
  typed and only the message change?
- Is the VTT speaker carry-over a chosen leniency for transcripts that
  put the voice span on the first cue of a turn only? Then the round trip
  can't be exact, and the doc should say so.
- A private feed's `?token=` stays in `:url`, in the WebSub topic and in
  every diagnostic's `:url`. Should the library redact it before a
  validator logs it, as the checker does for credentials in the URL?
