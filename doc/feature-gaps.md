# Podcast features outside podcast-clj

This page lists the podcast features that podcast-clj did not have on 27
September 2026. It compares podcast-clj with podcast apps, hosting
platforms, specifications and libraries in other languages. For each
feature, it tells if the feature fits a library, and which part of it
podcast-clj can supply. [Status](#status-on-29-september-2026) tells which
of the features podcast-clj has now.

The research read the websites, help pages, changelogs and app store pages
of the products. It also read the repositories of the open-source projects,
and the specifications. The research read all sources on 27 September 2026,
and [Sources](#sources) lists them. [Podcasting 2.0 and
Clojure](podcasting-2.0-clojure-survey.md) is the survey that came before
podcast-clj.


## Status on 29 September 2026

The research is from 27 September 2026, and the text of this page tells
what podcast-clj had on that day. After the research, podcast-clj got
all nine groups of targets in [A suggested order](#a-suggested-order).
The column **Status** of each table tells the state of a feature on 29
September 2026:

- **Built**: podcast-clj has the feature, or the part of it that the
  verdict gives to a library. The column names the namespace or the
  function that has it.
- **Not built**: podcast-clj does not have the feature. [Not
  built](#not-built) lists these features too.
- A feature with the verdict Out has no status.

The [changelog](../CHANGELOG.md) tells what came with each feature, and
[Namespaces](namespaces.md) tells where each feature is.


## Not built

podcast-clj doesn't have these yet:

- The Open Podcast API, whose spec for episodes is still a draft.
- Stores over the HTTP APIs of cloud services. Each would be a short
  namespace against the store contract.
- A JSON form of the sync documents, for clients in other languages.
- A lenient XML parser for JavaScript.
- A folder store for Node.
- A release to Clojars.
- The extensions of Nostr Wallet Connect other than keysend: notifications,
  hold invoices, payment history and BOLT 12 offers.

NIP-04 encryption for wallets without NIP-44 is left out on purpose, since
NIP-04 is deprecated and can't detect a changed message.


## How to read this page

Each feature has one of three verdicts:

- **Target**: the feature fits podcast-clj. It needs only pure functions
  over data, requests as maps, and the platform adapters that podcast-clj
  has.
- **In part**: podcast-clj can supply a part, such as the data, the
  requests or the arithmetic. A server, a wallet, a media player or the app
  supplies the rest.
- **Out**: the feature needs a service that runs all the time, paid
  infrastructure, audio processing, machine learning or a platform API. An
  app can build such a feature on podcast-clj, but the library cannot supply
  it.

The size of a target is an estimate:

- **Small**: one function or a few, with their tests.
- **Medium**: a new namespace, or changes in several namespaces.
- **Large**: several namespaces, or a protocol with a client side and a
  server side.


## What podcast-clj had on 27 September 2026

- The feed model: RSS 2.0, the iTunes namespace, the `podcast` namespace
  and nine other vocabularies. `parse`, `emit`, `validate` and the Malli
  schemas all come from one tag registry.
- The repairs and quirks for malformed feeds, with a report of each.
- `feed.resolve`: one answer where vocabularies overlap, the listening
  order, the seasons, and `next-check` for polling.
- Chapters in JSON and Podlove form, and from the timestamps in show notes.
  Transcripts in five formats, OPML, and the HTML of show notes.
- The directories Apple Podcasts, fyyd and gpodder.net, and the whole
  Podcast Index API.
- Podping: the pings on the Hive chain, and the request that a host sends.
- Value for value: splits, fees, time splits, remote items and the
  boostagram record.
- The player state as a CRDT: the queue, progress, played marks,
  subscriptions with settings, preferences and devices.
- Search: criteria and orders as data, and a full-text index.
- Sync through any document store, with memory, folder, WebDAV and
  encrypted stores, and a WebDAV server.

The parser reads all of the `podcast` namespace: the 28 tags and the 3 child
tags that the namespace marks as formalized. It also reads the changes of
2024 to 2026, such as the `rel` and `country` of `podcast:location` and the
`course` medium. The open proposals are in [Proposals to
follow](#proposals-to-follow).

The Podcast Index lists 66 podcast players, with the Podcasting 2.0 features
of each. The most common features are search (46 players), transcripts (39),
chapters (38), funding (25) and the podcast guid (20). The parser reads the
data of all of them.

Many typical features of apps need no change in podcast-clj:

- a feed added by its URL
- OPML import and export
- a backup of the state as EDN
- a speed for each show, and the skip of an intro
- search in the transcripts of subscribed shows
- a page for each person, from `podcast:person` and the `search/byperson`
  endpoint of the Podcast Index


## Feeds: fetch and update

An app fetches each feed again and again. These features make the fetch
find more, or cost less.

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Paged feeds, RFC 5005 | AntennaPod and gPodder read them. Podlove Publisher writes them. | Target | Small | Built: `:max-pages` of `podcast/fetch!` |
| Feed discovery from a web page | AntennaPod, gPodder | Target | Small | Built: `format.html/feed-links` |
| Private feeds with a user name and a password | Overcast, AntennaPod, gPodder, PinePods | Target | Small | Built: `:credentials` of `podcast/fetch!` |
| The kind of a redirect: permanent or temporary | AntennaPod, gPodder, the Livewire validator | Target | Small | Built: `:permanent-url` of `podcast/fetch!` |
| Polite polling: `Retry-After` and `Cache-Control` | gPodder, Overcast | Target | Small | Built: `:not-before` of `podcast/fetch!` |
| WebSub | Pocket Casts | In part | Small | Built: `updates.websub` |

**Paged feeds.** RFC 5005 lets a host split a long feed into pages. The pages
link to each other with `atom:link rel="next"`. The feeds of SoundCloud and
Audioboom have such links. The parser reads the links, but `fetch!` does not fetch the next page. A function can follow the links and merge the items of all pages. It
stops at a page with no new items. The element `fh:complete` marks a feed
that holds all its items, so there is no page to follow. RFC 5005 also
defines archived feeds, but the research found no app that follows them.

**Feed discovery.** A listener often knows the address of the website of a
show, not of its feed. A web page names its feeds with `<link
rel="alternate" type="application/rss+xml">`, as the RSS Board and the HTML
standard define. AntennaPod and gPodder read these links. The HTML reader
of podcast-clj can find them, so discovery is one function over the text of
the page.

**Private feeds.** Most private feeds hold a token in the URL, and
podcast-clj fetches them as other feeds. Some feeds need HTTP Basic
authentication instead. Overcast reads the user name and the password from
the URL. AntennaPod, gPodder and PinePods ask the listener for them.
No credentials go with `fetch!`. It can take `:credentials`, as the WebDAV
store does.

**The kind of a redirect.** A host that moves a feed answers with a permanent
redirect, 301 or 308. An app changes the stored URL only on a permanent
redirect, as AntennaPod does. The result of `fetch!` gives the final URL, but
not the kind of redirect. The JVM client keeps the chain of responses, so
`fetch!` can tell if all steps were permanent.

**Polite polling.** A busy server answers 429 or 503, often with a
`Retry-After` header. A server also says how long a response stays fresh,
with `Cache-Control: max-age`. gPodder obeys `Retry-After`, and Overcast
obeys the standard caching headers. A study of server logs from 2024 found
that many clients poll more often after a 429 or a 503. The function `next-check` reads only the feed. It can also read these headers from the last response.

**WebSub.** Many hosts name a WebSub hub in their feeds. Among them are
Buzzsprout, Transistor, Captivate, RSS.com and Spotify for Creators. The hub
pushes each change to its subscribers, and Pocket Casts added WebSub in April
2024. A subscriber must give the hub a public callback URL, so it needs a
server. A function can find the hub in a feed and build the request that subscribes. Podping gives the same signal without a server, and podcast-clj
reads Podping already.


## Media files

Apps read data from the media files, and hosts check that the files reach
the listener.

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Chapters inside MP3 and M4A files | Apple Podcasts, Overcast, Pocket Casts, AntennaPod, Kasts | Target | Medium | Built: `format.media/read!` |
| Artwork inside MP3 files | The libraries mutagen, music-metadata and TagLib | Target | Small | Built: `format.media/read!` |
| The duration, from the media file | The libraries podgen and music-metadata | Target | Medium | Built: `format.media/read!` |
| Checks of media delivery: HEAD, byte ranges, length, type | Rules of Apple Podcasts. Cast Feed Validator, Podbase | Target | Medium | Built: `check/check-files!` |
| Checks of artwork: size, format, color | Rules of Apple Podcasts. Cast Feed Validator, Podbase | Target | Small | Built: `check/check-files!`, `format.image` |
| CORS on transcript and chapter files | podcast-partytime | Target | Small | Built: `check/check-files!` |
| A check of `podcast:integrity` | No app found | Target | Small | Built: `format.media/verify!` |
| Silence trimming, voice boost, loudness, equalizer | Overcast, Pocket Casts, Castro, AntennaPod, Podcast Addict | Out | | |

**Chapters, artwork and duration in the file.** Many podcasts put their
chapters in the media file, not in the feed. MP3 files use the ID3v2 frames
`CHAP` and `CTOC`. M4A files use the Nero `chpl` box or a QuickTime chapter
track. Apple Podcasts, Overcast, Pocket Casts, AntennaPod and Kasts read
these chapters. The ID3 frame `APIC` holds the artwork. The duration comes
from the Xing, Info or VBRI header of an MP3 file, or from the `mvhd` box
of an MP4 file.

In most files, these structures are near the start, and a range request
fetches them. A reader of these bytes is pure code, so it runs on both
platforms. It can give the same chapters map as `chapters/parse-json`. The
HTTP client of podcast-clj reads each body as text, so it first needs a
form that keeps the bytes. Libraries in other languages do the same work:
mutagen in Python, music-metadata in JavaScript and rssblue/chapters in
Rust.

**Checks of media and artwork.** Apple Podcasts requires media servers that
answer HEAD requests and byte-range requests. Its artwork must be square,
from 1400 to 3000 pixels, JPEG or PNG, in RGB and without an alpha channel.
Cast Feed Validator and Podbase check these rules over the network. The
Livewire validator reports redirects, CORS headers and the size of the
feed. Browser players need CORS headers on transcript and chapter files
too.

The validator checks only the data of the feed. A `check!` in the style of
`store/check!` can send the requests and give each check as data. The size
and the color type of an image are in its first bytes, so the artwork check
needs no image library. The inspector can show the result.

**Integrity.** `podcast:integrity` gives an SRI hash or a PGP signature for
an alternate enclosure. The parser reads it, as several other libraries do, but the research found no app that checks it. A check of the SRI form computes
SHA-256, SHA-384 or SHA-512 over the downloaded bytes, and compares the
result with the hash. The JVM has `MessageDigest`, and browsers have
WebCrypto, which podcast-clj uses already for encryption.

**Audio processing.** Silence trimming, voice boost, loudness normalization,
an equalizer and a mono mix change the audio signal. They belong to the media
engine of the player. The data about the audio is where podcast-clj stops.


## The player state

The player state holds what the listener does. These features add to it.
Each new kind of entry must keep `merge` a join, and the generative tests
of the merge must cover it.

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Skip the outro | Pocket Casts, Castro | Target | Small | Built: `player/step`, `player/outro-start` |
| Stop at the end of the episode, or after some episodes | AntennaPod, and the sleep timer of nearly all apps | In part | Small | Built: `:sleep` of `player/step`, and of the library |
| Chapter navigation | Pocket Casts, Overcast, Apple Podcasts, Castro, Fountain | Target | Small | Built: `player/step`, `chapters/jump` |
| Starred episodes | Pocket Casts, Castro, Podbean | Target | Small | Built: `player/star` |
| Bookmarks and notes | Pocket Casts, Podcast Addict, Player FM, Podcini | Target | Small | Built: `player/bookmark` |
| Folders for shows | Pocket Casts, Castro, Podbean, Player FM | Target | Small | Built: `player/folders` |
| An inbox of new episodes | Castro, AntennaPod, Spotify | Target | Small | Built: `player/inbox` |
| Ratings that the listener keeps | Podcini | Target | Small | Built: `player/rate-episode` |
| Saved filters, or smart playlists | Pocket Casts, Overcast, Downcast, PinePods | Target | Small | Built: `player/save-filter` |
| Playlists and several queues | Pocket Casts, Podcast Addict, Fountain, Podverse, Podcini | Target | Medium | Built: `player/playlist` |
| Listening statistics and a year in review | Pocket Casts, AntennaPod, Spotify, PinePods | Target | Medium | Built: `player/listened` |
| Rules for downloads and deletes | Apple Podcasts, Pocket Casts, AntennaPod, PinePods | In part | Medium | Built: `downloads/plan` |
| Clips | Pocket Casts, Spotify, Fountain, Podverse | In part | Small | Built: `player/clip` |

**Skip the outro.** The settings of a show have `:skip-outro`, but `step`
does not act on it. Pocket Casts and Castro skip the outro of a show. The
reducer `step` can end the episode when the position reaches the duration
less the outro.

**The end of an episode.** Nearly every app has a sleep timer, and the
clock that runs it belongs to the app. Two kinds of timer depend on the
queue: stop at the end of this episode, and stop after some episodes.
AntennaPod has the second kind. Both kinds fit `step`. At the end
of an episode, it then gives a stop effect in place of `[:play next-id]`.

**Chapter navigation.** Apps jump to the next or the previous chapter. The
data comes from `chapters/at` and `chapters/with-end-times`. An event with
the chapters of the episode can go to `step`, which gives a seek effect.

**Stars, bookmarks, folders, an inbox and ratings.** These are small
stamped entries, like the entries that the state has now.

- Pocket Casts, Castro and Podbean star episodes.
- Pocket Casts, Podcast Addict and Player FM keep bookmarks. Podcini keeps
  notes and ratings.
- Pocket Casts, Castro, Podbean and Player FM put shows in folders. A
  folder can go into OPML and come back, because OPML has folders.
- Castro, AntennaPod and Spotify collect new episodes in an inbox.
  They are what `player/new-episodes` finds already.

**Saved filters and playlists.** The criteria of `search/episodes` are data,
but the app keeps them. Pocket Casts, Overcast, Downcast and PinePods save
filters as smart playlists. A saved filter is a stamped entry that holds the
criteria, so it syncs to the other devices. Pocket Casts, Podcast Addict,
Fountain and Podverse have playlists, and Podcini has several queues.
There is one queue. A playlist is a second map of ranked entries,
under a name. The code of the queue and of `util.rank` works for it.

**Statistics.** The state holds the position in each episode, not the time
that the listener spent. Pocket Casts, AntennaPod and PinePods show the time
for each show and month. Pocket Casts, AntennaPod and Spotify also give a
year in review. A count of the seconds heard, for each day, show and device,
is a grow-only counter. A grow-only counter is a CRDT of its own, so the
statistics sync like the rest of the state. Each `:progress` event can add to
the count in `step`.

**Download rules.** Apps download new episodes by rules for each show. A rule
sets the number of episodes, words to include or exclude, a minimum duration
or a limit on the space. Apps also delete played episodes. Apple Podcasts,
Pocket Casts, AntennaPod and PinePods have such rules. The decision is a pure
function of the state, the feeds and the rules. The episodes to download and
the episodes to delete can come from a pure function. The app downloads,
deletes and checks the network.

**Clips.** A clip is a part of an episode, with a start, an end and a
title. Pocket Casts, Spotify, Fountain and Podverse share clips. A clip has
the shape of `podcast:soundbite`, so the state can keep clips like other
entries. A file of the clip, as audio or video, needs a media encoder,
which is out.


## Sync between apps

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| A client of the gpodder API | AntennaPod, gPodder, Kasts, PinePods | Target | Large | Built: `app.sync.gpodder` |
| A server of the gpodder API | Nextcloud GPodder Sync, oPodSync, gpodder2go, PinePods, mygpo | Target | Large | Built: `app.sync.gpodder.server` |
| The Open Podcast API | No client or server ships it | In part | Medium | Not built |
| Sync over the local network with no server | Podcini | Out | | |
| The sync services of the apps | Pocket Casts, Castro, Podverse, Apple Podcasts | Out | | |

**The gpodder API.** Most open-source apps sync through the gpodder API.
gpodder.net stopped its directory in 2023, and now it only syncs devices.
Self-hosted servers use the same API. Among them are the GPodder Sync app
for Nextcloud, oPodSync, gpodder2go and PinePods. AntennaPod, gPodder,
Kasts and PinePods are clients. The API syncs subscriptions and episode
actions, such as a play with its position.

[Syncing](sync.md#the-gpodder-api) describes a backend for this API. A
client backend lets a listener sync an app on podcast-clj with AntennaPod or
Kasts. A server over the stores of podcast-clj, like the WebDAV server,
lets those apps sync to a Clojure server. The difficult part is identity.
gpodder names shows and episodes by URL, and podcast-clj names them by
guid. The Nextcloud app adds an optional episode guid, which helps.

**The Open Podcast API.** AntennaPod, Funkwhale, Kasts, Podfriend and the
gPodder app for Nextcloud started the Open Podcast API in 2022. It builds
on the gpodder API. It plans to sync subscriptions, progress, favorites and
queues. The specification for subscriptions is public. The specification
for episodes is still a draft, from June 2024. The research found no client
or server that ships the API. Its model is close to that of podcast-clj: a
guid and a URL for each show, and tombstones for removals. Wait for the
specification for episodes before an implementation.

**Out.** Podcini syncs two devices over the local network with no server.
This needs the discovery of devices, which the platform supplies. Pocket
Casts, Castro, Podverse and Apple Podcasts sync through services of their
own.


## Discovery and sharing

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Accessors for `podroll`, `publisher`, `socialInteract`, `soundbite` and `txt` | Pocket Casts, Apple Podcasts, many Podcasting 2.0 apps | Target | Small | Built: `feed.resolve` |
| Charts by category | The Podcast Index | Target | Small | Built: `:category` of `directory/fetch-chart!` |
| Links to apps, and links with a time | Apple Podcasts, Pocket Casts, Spotify, the Podlove Subscribe Button | In part | Small | Built: `directory.links` |
| Personal recommendations | Apple Podcasts, Overcast, Pocket Casts | Out | | |
| Search inside the audio of all shows | Castbox, Apple Podcasts, Snipd | Out | | |
| Discovery through a chat | Spotify, YouTube Music, Castro | Out | | |

**Accessors.** The model reads `podroll`, `publisher`, `socialInteract`,
`soundbite` and `txt`, but `feed.resolve` has no accessor for them. Apps
show these tags. Pocket Casts shows the podroll next to similar shows. Apple
Podcasts reads `txt` to verify the owner of a show. Accessors can give the
recommended feeds of the podroll, the publisher feed, the discussion of an
episode, the soundbites, and the `txt` values by purpose.

**Charts by category.** The Podcast Index takes a category for its trending
shows. The Podcast Index client of podcast-clj can send it, but
`directory/fetch-chart!` has no option for a category.

**Links to apps.** Apps share a link that opens an episode at a time.
Apple Podcasts, Pocket Casts and Spotify do this. Each app has its own form
of link, and a list from the community gives the forms of 45 apps. The
Podlove Subscribe Button offers a choice of apps in the same way. These
forms are facts about other products. They belong in a dated table in the
docs, like the table of store providers in [Sync stores and
documents](stores.md). The stable parts can come from podcast-clj: the guids of
the feed and the item, the Podcast Index id, and the time.

**Out.** Personal recommendations need the listening data of many
listeners. A search inside the audio of all shows needs an index of all
audio. Discovery through a chat needs a language model. Only a service has
these.


## Comments and other social features

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Comments from ActivityPub and AT Protocol threads | Podverse, Castamatic. Castopod writes the threads. | Target | Medium | Built: `comments` |
| Comments from Nostr | Fountain | In part | Medium | Built: `comments.nostr`, `nostr` |
| Public ratings and reviews | Apple Podcasts, Pocket Casts, Podcast Guru | Out | | |
| Comments and polls inside one platform | Spotify, Castbox | Out | | |
| The activity of friends | Fountain | Out | | |

**Comments.** `podcast:socialInteract` points to the first post of a
discussion about an episode. The specification lists ActivityPub, AT
Protocol, Nostr, Matrix, Hive, Twitter and Lightning. Castopod publishes
episodes over ActivityPub and writes the tag. Podverse and Castamatic show
comments from other apps. AntennaPod only opens the link in a browser.

An ActivityPub thread is a tree of JSON documents over HTTP. An AT Protocol
thread, from Bluesky, comes from one call to a public API. Both fit the
request maps of podcast-clj. A reader can give the comments of both in one
shape. Some ActivityPub servers answer only signed requests, which need the
app's key pair.

**Nostr.** Fountain shows Nostr posts about an episode as comments. NIP-73
gives the ids of podcasts and episodes, from their guids. Nostr needs
WebSocket connections to relays, which the app holds. The filters can be
built and the events read here.

**Out.** Public ratings and reviews, polls, comments inside one platform,
and the activity of friends need a service with accounts.


## Value for value

The namespace `value` computes the share of each recipient and writes the
boostagram record. It does not pay. These features are the steps around the
payment.

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Lightning addresses: LNURL-pay and the keysend lookup | Fountain, Castamatic, Breez, the library lightning-tools | In part | Medium | Built: `value.lnaddress` |
| Payment through the listener's wallet: NWC, WebLN | Fountain, Castamatic, Podverse on the web | In part | Medium | Built: `value.nwc`, `value.webln` |
| RSS Payment Metadata | Fountain, Castamatic, Helipad, BoostBox | Target | Small | Built: `value.metadata` |
| Received boostagrams | Helipad, BoostBox | In part | Small | Built: `value/parse-boostagram` |
| Card payments and other payment types | Fountain, and two open proposals | Out | | |

**Lightning addresses.** A recipient can have a Lightning address in place
of a node. The app then asks the domain of the address for an invoice, with
LNURL-pay at `/.well-known/lnurlp/<user>`. It can also ask
`/.well-known/keysend/<user>` for a node and a custom record. Fountain,
Castamatic and Breez pay Lightning addresses. The Alby library
lightning-tools sends these requests. They fit podcast-clj as maps. The
wallet pays the invoice.

**Wallets.** Fountain has a wallet of its own. Castamatic and Fountain also
pay from the listener's wallet, through Nostr Wallet Connect (NWC).
Podverse on the web pays through WebLN, which a browser extension such as
Alby supplies. A ClojureScript adapter can call `webln.keysend` with the
boostagram record. NWC sends its requests over a Nostr relay, so it needs a
WebSocket connection and the app's key.

**Payment metadata.** Several large wallets cannot send keysend payments.
The "RSS Payment Metadata" document of the namespace describes another
path, from a developer of Fountain. The app pays a Lightning address with
LNURL-pay. The comment of the payment holds `rss::payment::` with an
action, a URL and a message. The URL answers with the details in an
`x-rss-payment` header. BoostBox stores such metadata, and Helipad and
Castamatic read it. BoostBox is a small server in Clojure, with the MIT
license. A function can write and read this metadata, as one writes the bLIP-10 record.

**Received boostagrams.** A host reads what the listeners sent. Helipad reads
an LND node, and stores the boosts and their messages. A reader can take a bLIP-10 record or payment metadata into a map. The connection to the node belongs
to the host.

**Out.** Fountain takes card payments, and the namespace has proposals for
payments through Stripe (#781) and in ERC-20 tokens (#768). These need
accounts with a payment service.


## Tools for hosts

| Feature | Who has it | Verdict | Size | Status |
|---|---|---|---|---|
| Checks of a feed over the network | Cast Feed Validator, Podbase, Livewire, Apple Podcasts | Target | Medium | Built: `feed.check` |
| OP3: the prefix and the public statistics | Transistor, Captivate, Libsyn, Spreaker, Simplecast, RSS.com, Podhome | Target | Small | Built: `analytics.op3` |
| Download counts by the rules of the IAB | Hosts with IAB certification, OP3 | In part | Medium | Built: `analytics/downloads` |
| Paged feeds | Podlove Publisher | Target | Small | Built: `feed/pages` |
| A private feed for each subscriber | Buzzsprout, Transistor, Captivate, Castos | In part | Small | Built: `feed/for-subscriber` |
| Ad insertion, generated show notes, video, websites | Most hosts | Out | | |

**Checks over the network.** [Media files](#media-files) describes them.
The same checks help a host before it publishes.

**OP3.** OP3 is a free and open-source service that counts downloads through
a prefix. A host puts `https://op3.dev/e/` in front of each enclosure URL,
and each show gets a public page of statistics. The API at
`https://op3.dev/api/1` gives the downloads of each show and episode, and the
top apps. Each call needs a bearer token. A client of this API is request
maps and readers of the responses, like the directories. The prefix can go on
each enclosure before `emit`.

**Download counts.** Version 2.2 of the Podcast Measurement Technical
Guidelines of the IAB Tech Lab defines a download. A download is a file
request that passes filters. It counts once for each IP address and user
agent in 24 hours, and it must reach at least one minute of audio. HEAD
requests, two-byte probes, bots and the addresses of data centers do not
count. A pure function over request records can apply these rules. The host
collects the records, and the lists of bots and data centers are external
data. A draft of version 2.3 adds video in RSS and analytics prefixes.

**Paged feeds.** Podlove Publisher writes paged feeds. The pages of a long
feed can be written, with the links that RFC 5005 defines.

**Private feeds.** Buzzsprout, Transistor, Captivate and Castos give each
subscriber a feed with a token in its URL. A feed for each subscriber can be
written. The host serves the feeds and keeps the tokens.

**Out.** Dynamic ad insertion, ad marketplaces, IAB certification,
generated transcripts and show notes, audio cleanup, video with HLS, and
websites are services that a host runs.


## Proposals to follow

The namespace has more than 30 open proposals. The parser keeps the elements
that it does not know under `:xml/unknown`. An app can also add a proposal to
the registry as data. For these reasons, podcast-clj needs no change until
the namespace formalizes a proposal. The plan for phase 8 names
`podcast:follow` (#609) as its next tag. These recent proposals are the most
relevant to apps:

- `socialAccount` (#784): the profiles of the show on social platforms.
- `sortOrder` (#747): an order of episodes that the creator names.
- `ai` (#735), `aiTransparency` (#753) and `disclosure` (#669): the
  disclosure of the use of AI.
- `fidelity="word"` for transcripts (#600): timing for each word.
- `timeOffset` for alternate enclosures (#733): a shift of the transcript
  and the chapters for another file.
- `liveSchedule` (#683): the times of live shows that recur.
- A comments file for `socialInteract` (#680), and a Nostr note as its root
  post (#774).
- `metaBoost` (#676): an endpoint for the metadata of boosts.
- `private` (#627): a mark for a private feed.

The numbers are those of the discussions in the repository of the
namespace.


## Out of scope

These features need services, platforms or processing that a library cannot
supply:

- **Audio processing**: silence trimming, voice boost, loudness
  normalization, an equalizer and a mono mix.
- **Machine learning**: generated transcripts, chapters and summaries,
  translation, questions about an episode, playlists from a text prompt,
  and audio digests.
- **Platforms**: CarPlay, Android Auto, Chromecast, AirPlay, Sonos, Alexa,
  watches, TV apps, widgets and notifications.
- **Services with accounts**: public ratings and reviews, polls, comments
  inside one platform, friends, personal recommendations, and search inside
  the audio of all shows.
- **Services of hosts**: dynamic ad insertion, ad marketplaces, IAB
  certification, transcoding, video with HLS, audiograms and websites.
- **The I/O of an app**: downloads in the background, storage, and the type
  of the network.


## A suggested order

On 29 September 2026, podcast-clj has the targets of all nine groups,
but for the features that have the status Not built. These targets give
the most for their size, in this order:

1. The gaps in the fetch: paged feeds, feed discovery, Basic
   authentication, the kind of a redirect, and polite polling. Each is
   small, and every app that polls feeds needs them.
2. The accessors that `feed.resolve` lacks, and `:skip-outro` in `step`.
   They finish features that podcast-clj has in part.
3. New entries in the player state: stars, bookmarks, folders, an inbox and
   saved filters. Then playlists and statistics.
4. Chapters, artwork and duration from media files, and the integrity
   check. The HTTP client first needs byte bodies and range requests.
5. The checks over the network, for hosts and for the inspector.
6. The gpodder API, first as a client backend and then as a server. The
   Open Podcast API can follow when its specification for episodes is
   complete.
7. Comments from ActivityPub and AT Protocol threads.
8. Lightning addresses, payment metadata and received boostagrams. The
   survey proposes value for value as a separate artifact, and these belong
   there.
9. The OP3 client and the counts of downloads, for hosts.


## Sources

**Specifications**

- The podcast namespace: <https://github.com/Podcastindex-org/podcast-namespace>
- Its discussions, by number:
  <https://github.com/Podcastindex-org/podcast-namespace/discussions>
- `podcast:integrity`:
  <https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/integrity.md>
- `podcast:socialInteract`:
  <https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/social-interact.md>
- `podcast:value`:
  <https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/value.md>
- RSS Payment Metadata:
  <https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/examples/value/metadata.md>
- bLIP-10, the boostagram record:
  <https://github.com/lightning/blips/blob/master/blip-0010.md>
- NIP-73 and NIP-22 of Nostr:
  <https://github.com/nostr-protocol/nips/blob/master/73.md>,
  <https://github.com/nostr-protocol/nips/blob/master/22.md>
- The Podcasting 2.0 apps: <https://podcastindex.org/apps>
- The Podcast Index API: <https://podcastindex-org.github.io/docs-api/>
- PSP-1:
  <https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification>
- RFC 5005, feed paging and archiving: <https://www.rfc-editor.org/rfc/rfc5005>
- RSS autodiscovery: <https://www.rssboard.org/rss-autodiscovery>
- The HTML standard on `rel="alternate"`:
  <https://html.spec.whatwg.org/multipage/links.html#rel-alternate>
- WebSub: <https://www.w3.org/TR/websub/>
- Subresource Integrity: <https://www.w3.org/TR/2016/REC-SRI-20160623/>
- The ID3v2 chapter frames, a mirror:
  <https://mutagen-specs.readthedocs.io/en/latest/id3/id3v2-chapters-1.0.html>
- The Xing and LAME header: <http://gabriel.mp3-tech.org/mp3infotag.html>
- The QuickTime movie header and chapter tracks:
  <https://developer.apple.com/documentation/quicktime-file-format/movie_header_atom>,
  <https://developer.apple.com/documentation/quicktime-file-format/track_reference_type_atom>
- The IAB Podcast Measurement Technical Guidelines:
  <https://iabtechlab.com/standards/podcast-measurement-guidelines/>
- OP3: <https://op3.dev/setup>, <https://op3.dev/api/docs>

**Apps**

- Apple Podcasts, chapters:
  <https://podcasters.apple.com/support/5482-using-chapters-on-apple-podcasts>
- Apple Podcasts, requirements:
  <https://podcasters.apple.com/support/823-podcast-requirements>
- Overcast, for podcasters: <https://overcast.fm/podcasterinfo>
- Pocket Casts, WebSub and Podping:
  <https://blog.pocketcasts.com/2024/04/26/websub-and-podping-support/>
- Pocket Casts, skip intro and outro:
  <https://support.pocketcasts.com/knowledge-base/auto-skipping/>
- Pocket Casts, statistics:
  <https://support.pocketcasts.com/knowledge-base/listening-stats/>
- Pocket Casts, Playback 2025:
  <https://blog.pocketcasts.com/2025/12/10/pocket-casts-playback-2025-is-here/>
- Pocket Casts, recommendations:
  <https://blog.pocketcasts.com/2025/05/29/recommendations/>
- Pocket Casts, private feeds:
  <https://support.pocketcasts.com/knowledge-base/private-or-members-only-feeds/>
- Castro: <https://castro.fm/>
- Fountain, changelog: <https://fountain.fm/changelog>
- Fountain 1.4, wallets: <https://blog.fountain.fm/p/1-4>
- Castamatic, NWC: <https://castamatic.com/blog/2026/02/21/nwc/>
- Snipd: <https://www.snipd.com/all-features>
- Spotify, clips:
  <https://newsroom.spotify.com/2026-05-27/podcast-clips-save-share-moments/>

**Open-source apps and servers**

- AntennaPod: <https://antennapod.org/documentation/>
- AntennaPod, the parser:
  <https://github.com/AntennaPod/AntennaPod/tree/develop/parser>
- AntennaPod, Echo:
  <https://github.com/AntennaPod/AntennaPod/tree/develop/ui/echo>
- AntennaPod, comments link:
  <https://github.com/AntennaPod/AntennaPod/pull/7127>
- gPodder: <https://github.com/gpodder/gpodder>
- podcastparser: <https://github.com/gpodder/podcastparser>
- Kasts: <https://apps.kde.org/kasts/>
- Podverse: <https://podverse.fm/about>
- Podcini.A: <https://github.com/XilinJia/Podcini.A>
- PinePods: <https://github.com/madeofpendletonwool/PinePods>
- Castopod: <https://docs.castopod.org/main/en/>
- Podlove: <https://docs.podlove.org/>, <https://podlove.org/paged-feeds/>
- Podlove Subscribe Button:
  <https://github.com/podlove/podlove-subscribe-button>

**Sync**

- The gpodder.net API:
  <https://gpoddernet.readthedocs.io/en/latest/api/index.html>
- gpodder.net: <https://gpodder.net/>
- GPodder Sync for Nextcloud: <https://github.com/thrillfall/nextcloud-gpodder>
- oPodSync: <https://fossil.kd2.org/opodsync/>
- gpodder2go: <https://github.com/oxtyped/gpodder2go>
- The servers that AntennaPod lists:
  <https://antennapod.org/documentation/general/synchronization>
- The Open Podcast API: <https://openpodcastapi.org/about/>
- The draft for episodes: <https://github.com/OpenPodcastAPI/api-specs/pull/95>

**Hosts and validators**

- The IAB list of compliant companies:
  <https://iabtechlab.com/compliance-programs/compliant-companies/>
- Buzzsprout: <https://www.buzzsprout.com/features>
- Transistor: <https://transistor.fm/features/>
- Captivate: <https://www.captivate.fm/features>
- Castos: <https://castos.com/features/>
- Cast Feed Validator: <https://www.castfeedvalidator.com/about.php>
- Podbase: <https://podba.se/validate/>
- Livewire validator: <https://validator.livewire.io/>
- Apple Podcasts, validation:
  <https://podcasters.apple.com/support/829-validate-your-podcast>

**Libraries**

- podcast-partytime: <https://github.com/RyanHirsch/partytime>
- feedsmith: <https://github.com/macieklamberski/feedsmith>
- podgen: <https://github.com/tobinus/python-podgen>
- FeedKit: <https://github.com/nmdias/FeedKit>
- rssblue/chapters: <https://github.com/rssblue/chapters>
- mutagen: <https://github.com/quodlibet/mutagen>
- music-metadata: <https://github.com/Borewit/music-metadata>
- TagLib: <https://github.com/taglib/taglib>
- Alby lightning-tools: <https://github.com/getAlby/js-lightning-tools>
- WebLN: <https://www.webln.guide>
- Links to podcast apps:
  <https://github.com/nathangathright/podcast-platform-links>

**Value for value**

- Helipad: <https://github.com/Podcastindex-org/helipad>
- BoostBox: <https://github.com/noblepayne/boostbox>

**Polling**

- A study of how feed readers poll:
  <https://www.earth.org.uk/RSS-efficiency.html>
