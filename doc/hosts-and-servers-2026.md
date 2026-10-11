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
- **Two listed feeds fail the Apple requirements for lacking `<guid>`
  alone.** Both walks remembered Apple's guide as recommending the guid.
  A read of the guide on the same day settles it the other way: `<guid>`
  is under the required episode tags, as the `:apple` table has it, so
  the two feeds are wrong and the table is right. Nothing to change.

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

The self-hoster read the README, the service and the compose file as a
person deploying for themself and a friend behind Caddy, started the
server with a password, and probed it with curl as an attacker and as a
user: every route with and without the password, the headers, the Host
and Funnel checks, 200 wrong passwords in a row, the proxy with private
and spoofed addresses, redirects, a 50 MB file and byte ranges, WebDAV
with every method and odd names and bodies, the gpodder API as
AntennaPod uses it as two people with one password, and a restart.
Much of it held: the proxy refused every private address in every
spelling, the password compare took constant time, the Host check
stopped DNS rebinding, every body was bounded, paths out of the client
folder were refused, no credential reached a site upstream, and no
Server header leaked.

### Defects

- **An uncaught exception logs the password.** A WebDAV put with a
  300-character name hits a file-system exception that the handler
  doesn't catch, the server answers 500, and Pedestal's servlet
  interceptor logs the whole request context, the Authorization header
  with the Basic credentials included. Reproduced twice: once in the
  walk, once again afterwards. In Docker the line lands in the daemon's
  logs. Catch the I/O exception in the WebDAV handler and answer 400,
  and put an interceptor after the login that hides the Authorization
  header before anything logs the request. Half a day.
- **The gpodder API has no cross-site defence.** A POST with a text/plain
  body and the browser's cached credentials changes the subscriptions
  and adds plays, since the handler reads JSON out of any body and
  checks neither Origin nor Sec-Fetch-Site, which the proxy does check;
  the WebDAV handler is saved by the CORS preflight. The session cookie
  has no SameSite and no Secure. The threat model lists both. An hour.
- **The documented public path with an empty password is an open
  server.** Without a password the only guard refuses requests that
  carry the Tailscale Funnel header, which Caddy never sends, and the
  compose file makes the password optional while it requires the names.
  So `docker compose up` behind Caddy without a password serves the
  internet with no login. Refuse to start without a password unless the
  host is localhost, and make the password required in the compose
  file. An hour.
- **A server name with a port never matches.** The Host check strips the
  port from the request but not from the configured names, so
  `PODCAST_SERVER_NAMES=pi.example.ts.net:8443`, which the README's own
  example invites, answers 403 to every request with a message about the
  name. Strip the port from the names too. An hour.
- **Junk subscriptions are kept and fetched every minute.** The gpodder
  handler takes any string as a subscription, including `ftp://x/`, `not
  a url` and a private address, the server's device then tries each one
  every 60 seconds and logs a warning each time, and the reads filter
  them out, so they're stored but invisible. Refuse what isn't a public
  web URL at the handler, and back off a feed that keeps failing, which
  the needs report's ceiling and dead-network items cover. An hour.
- **Plays that were synced disappear after a restart.** Before a restart
  the API gave four episode actions, after it one: an action needs the
  media URL, which the handler reconstructs from a book kept in memory,
  and three of the four entries were keyed by the subscription's
  URL-derived guid while the fetched feed is keyed by its declared one,
  so after the restart the names can't be re-derived. The README
  promises duplicates after a restart, not losses. Persisting the book is
  a known gap; the guid duality is the identity design's first batch.

### Docs

- **Changing the password doesn't end sessions, and isn't described.** A
  wrong password with a still-valid session cookie is answered 200 for
  up to a day, since sessions are checked first. Tie a session to the
  password, or drop them at start, and write the steps. An hour.
- **The public section never says that a friend is the same listener.** A
  second person with another user name and the same password sees the
  first's devices, subscriptions and plays, and writes into them; the
  README says "any user name and password" and reads as if names
  separate people. One paragraph, pointing at the many-listeners gap.
- **The whole client folder is served, dotfiles included.** A `.env` in
  the folder is served to anyone with the password. One line to point
  the setting at a release build, and skip dotfiles in the file routes.
- **Small proxy edges.** A redirect to a private address comes back as a
  bare 30x with no Location and no Proxy-Status; a binary body fetched
  without `as=bytes` gets a charset on its type; HSTS with
  includeSubdomains goes out on every answer, plain HTTP included, which
  the README doesn't mention.

### What the self-hoster still writes

The browser client itself, which is weeks; a Caddyfile and a compose
`.env`, which the README gives; a backup of the volume, which a
two-person server wants although the README says none is needed; and
log rotation with a way to quiet the per-minute warnings.


## What to build first

0. The server's four small defects: the credentials kept out of the log
   on an error, the cross-site check and the cookie attributes, a
   refusal to start on the public path without a password, and the port
   stripped from the names. A day, and the "Without Tailscale" section
   is safe to follow.
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

- Should `feed/normalize` read a producer's strings, which makes the
  plain-data path as lenient as the parser, or should the model stay
  typed and only the message change?
- Is the VTT speaker carry-over a chosen leniency for transcripts that
  put the voice span on the first cue of a turn only? Then the round trip
  can't be exact, and the doc should say so.
- A private feed's `?token=` stays in `:url`, in the WebSub topic and in
  every diagnostic's `:url`. Should the library redact it before a
  validator logs it, as the checker does for credentials in the URL?
- Is the one-listener model meant to cover "for yourself and a friend",
  which the README's "any user name" invites, or should the public
  section steer a household to wait for the many-listeners feature?
- Should the server refuse to run without a password when it isn't bound
  to localhost, or is the open server behind Caddy operator error?
