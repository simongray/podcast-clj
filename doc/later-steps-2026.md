# What to do later, October 2026

This page turns the "Later" list of [What to do next](next-steps-2026.md#later)
into a plan of the same kind: what to fix and build after that plan's
fifteen steps, in order, why each step sits where it does, and where to
read more. The list named six things:

- feeds on demand, the last batch of the
  [identity design](identity-and-storage-2026.md#the-batches);
- the documented gaps of [the needs report](needs-2026.md#documented-gaps);
- bounded growth of the profile, from
  [the threat model](threat-model-2026.md#a-peer-device);
- the siblings' other needs, from [their report](siblings-2026.md);
- the proxy's small edges, from
  [the server walk](hosts-and-servers-2026.md#docs-2);
- the nice-to-haves of both needs reports.

Unlike the first plan's reports, these were written before most of the code
changed. So every item was checked again against the code on 11 October
2026, at c5f88e6 and each sibling's latest commit. What has since been built
is left out, e.g. the check of a saved profile in `restore!` and the
slowing of password guesses. Two findings of that check are new: two
libraries still take an html-pieces that a stranger can steer, and the
feed model page claims checks that the validator doesn't make.


## How it's ranked

As in the first plan, harm first, then cost:

1. What lets a stranger in or leaks a secret.
2. What gives a wrong answer or loses a listener's data.
3. The other needs, by gain and then by difficulty, with the scores of
   the needs reports: gain 3 means most apps built on the library want
   it, gain 2 that a real subset does.
4. Housekeeping: releases, rough edges, and what the code can lose.

Within a tier the cheap steps go first. Each step is one batch in one
repository, or a short chain of them, so you can stop after any step.
Steps 1 to 4 take about a week and close the edges of the first two tiers.
Steps 5 to 8 take about two weeks and give most listener apps what they'd
otherwise write themselves. The rest is months, and its order matters
less.


## At a glance

| # | Step | Where | Size | Why it's here |
|---|---|---|---|---|
| 1 | [html-pieces 0.4.1 everywhere](#1-html-pieces-041-everywhere) | websub-clj, podcast-clj | hours | A page can move the hub that websub-clj discovers |
| 2 | [The server's small edges](#2-the-servers-small-edges) | wary-fetch, podcast server | a day | HSTS on plain HTTP and on names the self-hoster didn't choose |
| 3 | [Wrong answers on real feeds](#3-wrong-answers-on-real-feeds) | podcast-clj | two days | Episodes listed twice, calls that can't work, a page that promises checks |
| 4 | [A bound on the profile's growth](#4-a-bound-on-the-profiles-growth) | podcast-clj | a few days | The one way a peer device hurts the others |
| 5 | [What an app asks after a refresh](#5-what-an-app-asks-after-a-refresh) | podcast-clj | a day or two | Gain 3 at an hour each |
| 6 | [Downloads the library keeps](#6-downloads-the-library-keeps) | podcast-clj | about three days | Every app with downloads writes this bookkeeping |
| 7 | [Filters, seek links and a refresh on a budget](#7-filters-seek-links-and-a-refresh-on-a-budget) | podcast-clj | about three days | Gain 3 at a day each |
| 8 | [Directory ids for a show known by its feed](#8-directory-ids-for-a-show-known-by-its-feed) | podcast-clj | a day | Share links for every subscribed show |
| 9 | [The siblings' needs and their small extras](#9-the-siblings-needs-and-their-small-extras) | seven libraries | about a week | Their last needs, and an hour each for the rest of gain 2 |
| 10 | [podcast-clj's extras of an hour](#10-podcast-cljs-extras-of-an-hour) | podcast-clj | about three days | Gain 2 at an hour each |
| 11 | [The documented gaps of a day](#11-the-documented-gaps-of-a-day) | podcast-clj | about a week | Gaps the docs already name |
| 12 | [Stores and senders for React Native and Capacitor](#12-stores-and-senders-for-react-native-and-capacitor) | like-minded, wary-fetch | a few days | Mobile apps written in ClojureScript |
| 13 | [The media readers reorganised, then Opus and ADTS AAC](#13-the-media-readers-reorganised-then-opus-and-adts-aac) | podcast-clj | about a week | Two common formats that read as unknown |
| 14 | [Offline episodes in a browser](#14-offline-episodes-in-a-browser) | wary-fetch, podcast-clj | a few days | A browser app can't download an episode for the train |
| 15 | [Stores over the APIs of cloud services](#15-stores-over-the-apis-of-cloud-services) | like-minded | a few days | Sync without a WebDAV server |
| 16 | [Listener extras of a day](#16-listener-extras-of-a-day) | podcast-clj | about a week | Gain 2 for listener apps |
| 17 | [Host, comment and payment extras of a day](#17-host-comment-and-payment-extras-of-a-day) | podcast-clj | about a week | Gain 2 for hosts and value-for-value apps |
| 18 | [Feeds on demand](#18-feeds-on-demand) | podcast-clj | a week or two | Libraries of hundreds of feeds |
| 19 | [Many listeners on one server](#19-many-listeners-on-one-server) | podcast server | a week or two | Only if a decision wants it |
| 20 | [A lenient XML parser for JavaScript](#20-a-lenient-xml-parser-for-javascript) | podcast-clj | a week or two | A broken feed reads on the JVM but not in a browser |
| 21 | [A browser run in CI](#21-a-browser-run-in-ci) | seven libraries | a few days | Every README claims the browser and none tests it |
| 22 | [The siblings' rough edges](#22-the-siblings-rough-edges) | seven libraries | a few days | Defects and release readiness |
| 23 | [What the review left optional](#23-what-the-review-left-optional) | podcast-clj | a day or two | Nothing wrong, less code |


## Decisions to make

These block the step they name.

- **Step 2.** Should the server send HSTS only over https and without
  includeSubDomains, or not at all, leaving it to a proxy in front such as
  Caddy? (Decided: only over https, without includeSubDomains, and a
  line in the server's README.)
- **Step 4.** How old may an episode's entry grow before it's folded into
  the show's played watermark: a year, or a number of episodes? And may a
  listening count of such an episode go, or does it stay as a total per
  show? ([A peer device](threat-model-2026.md#a-peer-device) and the
  item in [the needs report](needs-2026.md#the-profile-the-player-the-library-and-sync))
  (Decided: a year by default, set by `:archive-after`, into a set of the
  played item guids per show. The listening counts are per show and month
  already, so they stay.)
- **Step 13.** The reorganisation of the media readers was proposed in
  five batches on 10 October and never approved, and parts of two batches
  were done since. Do all five go ahead, or only what the two new readers
  need?
- **Step 18.** The identity design waits for a library of hundreds of
  feeds to measure feeds on demand on. Build it without one, from a
  synthetic library, or keep waiting?
- **Step 19.** The server's README says it's for one listener. Does it
  stay so, in which case this step goes, or should a household be able
  to share one server?
  ([Many listeners](needs-2026.md#many-listeners-on-one-server))
- **Step 20.** A hand-written lenient parser in cljc, or a JavaScript one
  behind a hook?
  ([A lenient XML parser](needs-2026.md#a-lenient-xml-parser-for-javascript))
- **A release of podcast-clj.** No step releases it. Step 4 changes the
  saved library again, and steps 5 to 8 add API, so a first release fits
  after step 4 or after step 8. When should it go out?


## The steps

### 1. html-pieces 0.4.1 everywhere

- Bump websub-clj to html-pieces 0.4.1 and wary-fetch 0.3.1, and release
  it as 0.2.1.
- Bump podcast-clj to html-pieces 0.4.1, and to websub-clj 0.2.1 once
  that's out.

**Why here.** On 0.4.0, a base element in the body of a page moves the
links that `html/links` reads from its head, and websub-clj's discovery
reads exactly those. So a comment on a page can send a subscriber to
another hub. Version 0.4.1 fixed it for IndieAuth, but neither
websub-clj nor podcast-clj took it. Since podcast-clj runs discovery on
feeds only, its own exposure is small, but websub-clj's users discover on
any page. An hour and your deploy.

**Read more.** The html-pieces commit 8028ba8, and the websub-clj entry
of [the siblings report](siblings-2026.md#websub-clj).

### 2. The server's small edges

- In wary-fetch's proxy, give the page the Location of a redirect that
  the client didn't follow, under a name that the browser doesn't follow,
  so that the page gets what a direct request gives. Today it comes back
  as a bare 30x with no Location, so a page can't tell why.
- In the proxy, keep the type of a body that isn't text as it came. Today
  a binary body fetched without `as=bytes` gets a charset on its type.
- Release wary-fetch 0.3.2, and take it in podcast-clj.
- In the podcast server, send HSTS as the decision says. Today Pedestal's
  default goes out on every answer, plain HTTP included, with
  includeSubDomains for a year, and the README doesn't mention it.

**Why here.** RFC 6797 forbids HSTS over plain HTTP, though browsers
ignore it there. Over https, includeSubDomains pins every name below the
server's for a year without the self-hoster choosing it, and at the root
of a domain that's every other subdomain. The other two make the proxy
answer wrongly. Each is an hour.

**Read more.** [The public server](hosts-and-servers-2026.md#docs-2) of
the server walk.

### 3. Wrong answers on real feeds

- Check the form of the language, email and media-type values, which
  [the feed model page](feed-model.md) says the validator checks and it
  doesn't. Today only URLs are checked.
- List an episode once when a feed lists its guid twice, keeping the
  first, in `resolve/episodes`, the criteria and the search documents.
  The validator reports the duplicate already.
- Strip the formatting tags of SRT cues and decode their entities, as
  the WebVTT reader does. Today an `<i>` shows in the text.
- Send the two batch calls of the Podcast Index as POST with a body.
  Today they go out as GET and can't work.
- Mark a feed gone after a 410 or a long run of failures, skip it when
  checking, and report it once with a reason the UI can show.
- Treat a fresh body that equals the stored one as unchanged, so that a
  server without ETag or Last-Modified doesn't cost a diff and a reindex
  at every check.
- Follow a declared move only when the new URL answers with a feed of
  the same guid, and else keep the old URL with a warning.

**Why here.** Each makes the library wrong or wasteful on real feeds, and
each is an hour, except the move, which is a day.

**Read more.** [Reading feeds](needs-2026.md#reading-feeds),
[Feeds for hosts and validators](needs-2026.md#feeds-for-hosts-and-validators),
[Media files](needs-2026.md#media-files-companion-files-and-downloads) and
[Directories](needs-2026.md#directories-links-updates-comments-and-payments)
in the needs report.

### 4. A bound on the profile's growth

- Fold the entries of old played episodes into a per-show played
  watermark, as the decision says, so that the synced document stops
  growing by two entries for every episode played.
- Let compaction drop what the watermark covers, and keep `profile/problem`
  and the convergence tests honest about it.
- A version of the saved library for the new shape, with its migration.

**Why here.** The document grows for ever and is written whole at every
pause, so a device that has played for years makes every other device's
sync slower, and on a shared server it's the one way a peer hurts the
others. It's last in its tier because it's the largest, and it comes
before a release because it changes what's saved.

**Read more.** [A peer device](threat-model-2026.md#a-peer-device), and
the item on bounded growth under
[The profile](needs-2026.md#the-profile-the-player-the-library-and-sync).

### 5. What an app asks after a refresh

- `library/next-refresh`, the earliest next check of the feeds and the
  lists, beside `next-sync`.
- A per-show `:notify?` setting with a preference over all shows, and
  `library/notifications` with the new episodes of such shows after a
  refresh, grouped by show.
- `library/playback`, the view a player needs for one episode: the
  enclosure or the local file, the resume position, the rate, the
  artwork and the chapters.
- A summary of each refresh: how many feeds were checked, unchanged,
  changed and failed, and how long it took.
- Added after it: the rest of the Media Session, its position state and
  its buttons as player events, with the preference `:track-buttons` for
  where next and previous go.

**Why here.** Most apps want each, and each is an hour.

**Read more.** [Platforms, the server, the API and the docs](needs-2026.md#platforms-the-server-the-api-and-the-docs).

### 6. Downloads the library keeps

- A files part of the library, local to the device: saved by `save!` and
  never synced, with functions for a download that started, finished, or
  was removed, the keep flag, and the space used by show. The plan reads it
  when no files are given, and the episode view gives the local file.
- A partial and a failed state of a file, which the plan lists again, and
  a resume with Range and If-Range.
- An `:unsubscribed` reason to delete the files of a show the listener
  left, a portable file name for a download, and the size and audio offset
  in a media reading.

**Why here.** Every app with downloads writes this bookkeeping today,
since the guide leaves it to you. The download itself stays with the app.

**Read more.** [The record of downloaded files](needs-2026.md#the-record-of-downloaded-files-in-the-library)
and [Media files](needs-2026.md#media-files-companion-files-and-downloads).

### 7. Filters, seek links and a refresh on a budget

- Criteria by show, folder, tag, media kind, download state and queue, and
  a `:feed-guids` option of `library/episodes`, so a show page needn't
  scan every feed.
- A `:timestamp-fn` option of the episode view that turns a 12:34 in the
  show notes into a link of the app's choosing, after sanitising.
- A refresh with a deadline, a most number of feeds, and a function
  called with each result.

**Why here.** Gain 3 at a day each. The criteria come first, since step
6's download state is one of them.

**Read more.** [The profile](needs-2026.md#the-profile-the-player-the-library-and-sync),
[Reading feeds](needs-2026.md#reading-feeds) and
[Platforms](needs-2026.md#platforms-the-server-the-api-and-the-docs).

### 8. Directory ids for a show known by its feed

- A `directory/identify!` that asks the Podcast Index by feed URL or guid,
  or Apple by title, for the show's ids, cached in the feed entry.

**Why here.** A show subscribed by its URL gets only the share links that
a feed URL makes, and the links of every app once its ids are known. Gain
3 at a day.

**Read more.** [Directories](needs-2026.md#directories-links-updates-comments-and-payments).

### 9. The siblings' needs and their small extras

- In wary-fetch, the charset of an HTML page from its meta tag, as the
  HTML standard's prescan finds it. Today a page that names its charset
  only there is read as UTF-8, and a show's website is one such page.
- In qname-hiccup, the internal DTD subset of a document, or at least a
  sentence in the README that its entities fail.
- In html-pieces, nested lists in text and Markdown, and the `start` of
  an ordered list.
- Their extras of gain 2 at an hour each:
  - In wary-fetch, a public check without a lookup on the JVM, and
    `:query-params` on a request.
  - In qname-hiccup, the default namespace on emit, the metadata of every
    namespace, and a finder by path.
  - In html-pieces, an excerpt helper, and the page's base for every
    reader.
  - In drop-in-search, a count and a lookup by id, a snippet that honours
    its field, refusal of unknown options, `snippet` and `matches?` over
    the index itself, and EDN against CIFF numbers in the README.
  - In mind-meld, a `remove`, and the rest of the modelling guide.
  - In like-minded, a clear failure without a device, a guard on
    overlapping steps, and leaving in one call.
  - In websub-clj, a clear error for a secret over plain HTTP, and a job
    queue example.
- A release of each library that changed.

**Why here.** The three needs are a day each with gain 2, and the extras
an hour each, so one round of releases takes them all.

**Read more.** Each library's entry in [the siblings report](siblings-2026.md).

### 10. podcast-clj's extras of an hour

All gain 2 and an hour each:

- The artwork of a subscription kept in the profile, so a synced device
  shows it before its first refresh.
- Continuous play, where new episodes join the queue and how many,
  defaults for new subscriptions, a rewind after a long pause or an
  interruption, which Safari's Audio Session reports, and bulk arities of
  the marks and the queue.
- A `:playable-pred` from a browser's Media Capabilities, for Opus, video
  codecs and alternate enclosures.
- A string form of episode ids for routes and native bridges.
- Media files and images read from a local file or a Blob.
- `podping/notify!` beside `notify-request`, and rules on the delivery
  of the feed document itself in the checker.
- The expiry of a BOLT 11 invoice, and the payer data and verification of
  LUD-18 and LUD-21.
- The headers of the Podcast Index and OP3 through the proxy.

**Why here.** The cheapest of the rest of gain 2, which an app can write
today but shouldn't have to.

**Read more.** The areas of [the nice-to-haves](needs-2026.md#nice-to-haves).

### 11. The documented gaps of a day

- A bsky.app link as the root of a thread, read into its AT URI.
- The head reader of `app.show` made public under a clear name, with a
  mark on its result that tells it from a feed, and used for the
  candidates of discovery, the preview of a list and the guid check
  before subscribing, with a whole fetch when the head lacks the channel.
- The likes and reposts of a comment beside its replies.
- Word timing from the inline timestamps of WebVTT.
- Server logs read into the request maps of `analytics`, for hosts that
  count their own downloads.

**Why here.** Gain 2 at an hour or a day each, and the docs already
promise them as gaps.

**Read more.** [Documented gaps](needs-2026.md#documented-gaps).

### 12. Stores and senders for React Native and Capacitor

- In like-minded, a key-value store and a folder store over adapters that
  the app gives, e.g. over expo-sqlite and expo-file-system.
- In wary-fetch, a sender over CapacitorHttp, and one over IPC for
  Electron.
- The contract test for each.

**Why here.** On Expo there's no IndexedDB and no File System Access, and
on Capacitor requests go cleanly through native HTTP, so every mobile app
in ClojureScript writes these adapters itself today. A day or two each,
and each needs a device to test on.

**Read more.** [Stores and senders](needs-2026.md#stores-and-senders-for-react-native-and-capacitor).

### 13. The media readers reorganised, then Opus and ADTS AAC

- The reorganisation, as the decision says. It was proposed on 10 October
  in five batches: the view and its readers in one utility namespace, the
  range loop in another, one reading context, MP3 giving what MP4 gives,
  and tests that mirror the source. The little-endian readers moved to the
  view and MP3's use of the ID3 context were done in step 15 of the first
  plan.
- Readers of Ogg Opus and ADTS AAC, with a skip of the ID3 tag that ADTS
  files often start with.

**Why here.** Both formats are common in feeds, and a reading says
"unknown format" for them. The reorganisation comes first so that the new
readers are written once, the way the others will be.

**Read more.** [Ogg Opus and ADTS AAC readers](needs-2026.md#ogg-opus-and-adts-aac-readers).

### 14. Offline episodes in a browser

- In wary-fetch's proxy, a streaming mode that passes the body on
  without holding it, passes Range through, and answers 206 with its
  Content-Range, over the sink of wary-fetch.
- In the podcast server, a media route for the enclosures of the
  listener's feeds alone, so that the server isn't an open relay.
- Background Fetch where the browser has it, which is Chromium alone, so
  that a download goes on after the page closes, and the streaming proxy
  elsewhere.

**Why here.** Playing needs no proxy, but saving an episode in a browser
does, since hosts rarely send CORS headers on audio. Today the proxy holds
a body in memory up to 32 MB, so an hour-long episode comes in pieces
that the page stitches together. The sink came with the first plan's step
9, so it's a few days.

**Read more.** [Offline episodes for a browser app](needs-2026.md#offline-episodes-for-a-browser-app).

### 15. Stores over the APIs of cloud services

- A store in like-minded over the files API of Dropbox, and one over
  Google Drive's, each taking a token function, while the OAuth flow stays
  with the app.

**Why here.** On a phone there's often no synced folder and no WebDAV
server, only a cloud drive. A few days each, and each needs an account to
test against.

**Read more.** [Stores over the APIs of cloud services](needs-2026.md#stores-over-the-apis-of-cloud-services).

### 16. Listener extras of a day

All gain 2 and a day each:

- A custom order of the subscriptions, criteria for which new episodes
  join the queue or the inbox, counts per show for a shows screen, and a
  per-show episode order and title.
- Forgetting an episode from the history, and an archived state.
- The feed of a single added episode kept slim and left out of checks,
  and live items played through the library and shown in its views.
- Download rules over all shows, the bitrate and the tags of a media file,
  and Overcast's extended OPML.
- Cancelling a refresh, a cache for companion files, test support for
  apps, and a documented web worker pattern.

**Why here.** Each is for a real subset of listener apps, and each is a
day, so they follow the work of an hour.

**Read more.** [The profile](needs-2026.md#the-profile-the-player-the-library-and-sync),
[Media files](needs-2026.md#media-files-companion-files-and-downloads) and
[Platforms](needs-2026.md#platforms-the-server-the-api-and-the-docs).

### 17. Host, comment and payment extras of a day

All gain 2 and a day each:

- For hosts: defaults for a feed written from plain data, the
  requirements of Spotify and YouTube, an import that refuses a locked
  feed and a move away, and a Ring response for serving a feed.
- For comments: posting on Nostr, signers for the listener's key, and new
  replies since the last read.
- For payments: a check of a feed's value block, paying all recipients
  in one call, and a record of payments in the profile.
- Episode, person and recent calls in the unified directory.

**Why here.** The same as step 16, for hosts, validators and
value-for-value apps.

**Read more.** [Feeds for hosts and validators](needs-2026.md#feeds-for-hosts-and-validators)
and [Directories](needs-2026.md#directories-links-updates-comments-and-payments).

### 18. Feeds on demand

- Entries without a feed, `load!` and `unload`, the store in the config,
  and a server that keeps no feeds in memory, as the identity design has
  it. The document per feed and the entries' `:document` are there
  already.

**Why here.** It pays only for a library of hundreds of feeds, and it's a
week or two.

**Read more.** [The batches](identity-and-storage-2026.md#the-batches) of
the identity design.

### 19. Many listeners on one server

- A handler and a folder per listener, if the decision wants them.

**Why here.** The server is for one listener by design, so this is a
change of scope as much as a step.

**Read more.** [Many listeners on one server](needs-2026.md#many-listeners-on-one-server).

### 20. A lenient XML parser for JavaScript

- The parser that repairs a broken feed on the JVM, for ClojureScript too,
  as the decision says.

**Why here.** A broken feed reads in a JVM app but not in a browser app.
It's a week or two, and few feeds need it.

**Read more.** [A lenient XML parser for JavaScript](needs-2026.md#a-lenient-xml-parser-for-javascript).

### 21. A browser run in CI

- A browser run of the tests in the CI of the seven libraries, and of
  podcast-clj. Of these, like-minded and websub-clj have a browser build
  already that CI doesn't run.

**Why here.** Every README says the library runs in a browser, and none
tests it there. The browser client of wary-fetch has no test at all, not
even of its sink.

**Read more.** [Release readiness across the seven](siblings-2026.md#release-readiness-across-the-seven).

### 22. The siblings' rough edges

- The defects that the siblings report lists and that remain, e.g. a
  map body that wary-fetch sends as printed text, a `write` of mind-meld
  that takes a value that isn't a map, and like-minded's failure without
  a schema.
- What the README of each still lacks for a release: a version and
  deprecation policy, a cljdoc.edn, and the claims that don't hold.
- The commit of like-minded after 0.1.1, in its next release.

**Why here.** Nothing here gives a wrong answer that an app meets in
ordinary use.

**Read more.** The defects and release readiness of each library in
[the siblings report](siblings-2026.md).

### 23. What the review left optional

- The shared `diagnostic` helper, `pull-report`, a Ring response builder
  in `util.ring`, one Host check, the ID3 text width, and `movie-duration`.

**Why here.** The subtractive review left these for a batch that touches
those files anyway, and a release is a good time.

**Read more.** [What to apply first](subtractive-review-2026.md#what-to-apply-first).


## Later

- **The nice-to-haves of gain 2 that take a few days or more**: local
  files as a show, an ID3 writer, chapter images from MP4, Spotify and
  other directories, streaming payments, WebSub subscriptions renewed,
  localised categories, the dominant colour of artwork, and a reference
  client of the server.
- **The nice-to-haves of gain 1**, of both reports.
- **The proposals of the podcast namespace**, among them phase 9 (#789)
  and `podcast:socialAccount` (#784), once a maintainer answers.
- **F5 to F7 of the video report**: alternates grouped by rel, HLS
  downloads counted, and the play position tied to its file, each waiting
  for the evidence that report asks for.
- **The gpodder book kept over a restart of the server.**
