# Podcasting and Clojure, September 2026

This report describes the podcasting ecosystem that podcast-clj was built
for, and how the features of apps, hosts and other libraries compared with
podcast-clj at the time. It's frozen as of September 2026 and won't be
updated. The [README](../../README.md) says what podcast-clj has now.

The first part was put together on 20 September 2026, partly from existing
knowledge of the ecosystem and partly from live checks. Facts that weren't
checked are marked *(unverified)*. The second part read all of its
[sources](#sources) on 27 September 2026.


## Summary

- **Podcasting 2.0** is the `podcast` RSS namespace, with about 30 tags,
  plus open infrastructure: the Podcast Index directory with its free API
  and downloadable database, and Podping for near-real-time notifications
  of feed changes.
- **Support has two tiers.** Mainstream apps such as Apple Podcasts,
  Spotify, Pocket Casts, Overcast and AntennaPod have adopted a few tags,
  mostly transcripts and chapters. A ring of about 15 smaller "2.0 apps"
  implements most of the namespace, including value for value. Hosts are
  well ahead of apps: most independent hosts emit `guid`, `locked`,
  `transcript`, `chapters`, `funding` and `person`, and send Podpings.
- **The holes** are value for value outside the niche apps, live items,
  social interaction, alternate enclosures, location, publisher feeds and
  podrolls. The causes are structural: platform incentives, App Store rules
  for payments, churn in the spec, fragmented formats, and few shared
  libraries outside JavaScript.
- **Libraries.** JavaScript has de facto reference parsers in
  podcast-partytime and feedsmith, and Python and Dart have partial ones.
  The JVM has nothing, since Rome has no module for the podcast namespace,
  and Clojure has nothing for the namespace or the Podcast Index API.


## Podcasting 2.0

### The namespace

The namespace URI is `https://podcastindex.org/namespace/1.0`, usually
with the prefix `podcast:`. It's maintained in the open at
`github.com/Podcastindex-org/podcast-namespace`, with rendered docs at
`podcasting2.org/docs/podcast-namespace`. The tags were introduced in
phases, and this table groups them by purpose instead.

| Group | Tags | Level | Notes |
|---|---|---|---|
| Identity and control | `guid`, `locked`, `txt`, `block`, `updateFrequency`, `license` | channel (`license` also item) | `guid` is a UUIDv5 of the feed URL, and the stable identity that everything else depends on. `locked` keeps other hosts from importing the feed. |
| Episode enrichment | `transcript`, `chapters`, `soundbite`, `season`, `episode`, `trailer`, `person`, `location`, `image` | mostly item; `person`, `location`, `trailer` and `image` also channel | `transcript` and `chapters` are the most widely adopted tags. `image` replaced `images` in 2025, and `location` was revised in 2025 with `rel`, `country` and `geo`. |
| Distribution | `alternateEnclosure` (with `source` and `integrity`), `medium`, `remoteItem`, `podroll`, `publisher`, `contentLink` | item and channel | `medium` turns a feed into music, video, an audiobook and so on. `remoteItem` points to items in other feeds by guid, and underpins playlists, music and publisher feeds. |
| Money | `funding`, `value` (with `valueRecipient`), `valueTimeSplit` | channel and item | `value` was designed around keysend payments to nodes. The current docs also show Lightning address recipients. `valueTimeSplit` redirects the splits for a time range, e.g. for featured music. |
| Live and social | `liveItem`, `socialInteract`, `chat` | `liveItem` channel, `socialInteract` item, `chat` both | `liveItem` is an item with a status and a start and end time. `socialInteract` points to a root post on ActivityPub, Nostr, Bluesky or similar. Both depend on other infrastructure. |

### Podcast Index

- A directory of about four million feeds, run by Podcast Index LLC (Adam
  Curry and Dave Jones) and funded by donations and value for value. It's
  non-profit in practice, with no tracking of listeners.
- **The API** at `api.podcastindex.org/api/1.0` is free with a registered
  key and secret. Each request carries `X-Auth-Key`, `X-Auth-Date` in Unix
  seconds, an `Authorization` header with the hex SHA-1 of key, secret and
  date, and a `User-Agent`. The endpoints cover search, podcasts, episodes,
  recent items, value blocks, categories, stats, hub notifications, adding
  feeds, and a replacement for the iTunes lookup.
- **The database** of all feeds is published as a SQLite file of several
  gigabytes, which is the cheapest way to get a realistic corpus of feeds.
- **The docs** are at `podcastindex-org.github.io/docs-api`, with an
  OpenAPI file. There's example code for several languages, but no
  official JVM client.

### Podping

Podping replaces polling with notifications. Hosts, or a relay at
`podping.cloud`, write small `custom_json` operations to the Hive
blockchain, with feed URLs and a reason such as `update`, `live` or
`liveEnd`. Consumers stream blocks from any Hive API node and filter for
the `podping` id. The reference consumer is `podping-hivewatcher`, in
Python, and the Podcast Index is the largest consumer. Most independent
hosts send Podpings, but Apple, Spotify and the big enterprise hosts don't.
WebSub is the only mainstream alternative.

### Podcast Standards Project

Hosts and app makers formed the project in 2023, among them Buzzsprout,
Transistor, Captivate, Pocket Casts and Overcast. Its PSP-1 spec defines a
baseline of RSS 2.0, the iTunes tags and a small set of `podcast:` tags,
roughly `guid`, `locked`, `transcript`, `funding`, `person` and `chapters`
*(unverified)*. It matters because it's how mainstream apps adopted tags of
the namespace without committing to all of Podcasting 2.0.


## Support in apps and hosts

### Apps

Yes means supported, Partial means limited or only some formats, and ?
means not confirmed.

| App | Platform | transcript | chapters | funding | person | value | liveItem | socialInteract | Sync |
|---|---|---|---|---|---|---|---|---|---|
| Apple Podcasts | iOS, macOS | Yes (SRT and VTT since 2024, otherwise generated by Apple) | Partial (embedded; JSON ?) | No | No | No | No | No | iCloud, private |
| Spotify | all | ? (own transcripts) | Partial (own) | No | No | No | No | No (own comments) | own |
| YouTube Music | all | No | No | No | No | No | No | No | own |
| Pocket Casts | iOS, Android, web | Yes (2024) | Yes (JSON and embedded) | ? | No | No | No | No | own account |
| Overcast | iOS | ? | Partial (embedded only) | No | No | No | No | No | own |
| Castro | iOS | ? | Partial | No | No | No | No | No | iCloud |
| Podcast Addict | Android | Yes | Yes | Yes | Yes | No | Partial | No | own, gpodder |
| AntennaPod | Android, open source | Yes (since 3.4, 2024) | Yes (JSON) | Yes | No | No | No | No | gpodder.net, Nextcloud |
| Fountain | iOS, Android | Yes | Yes | Yes | Yes | Yes (own wallet) | Yes | Partial (Nostr) | own |
| Podverse | iOS, Android, web | Yes | Yes | Yes | Yes | Yes | Yes | Yes | own account |
| CurioCaster | web | Yes | Yes | Yes | Yes | Yes | Yes | Partial | own |
| Castamatic | iOS | Yes | Yes | Yes | Yes | Yes | Yes | Yes | own |
| Podcast Guru | iOS, Android | Yes | Yes | Yes | Yes | Yes | ? | ? | own |
| TrueFans | web | Yes | Yes | Yes | Yes | Yes | Yes | Yes | own |
| Breez, LN Beats, Wavlake | wallets and music | Partial | Partial | Partial | Partial | Yes | No | No | none or own |

The tags that reached the mainstream are the ones that improve listening
in one app without any coordination, like transcripts and chapters. No
mainstream app syncs through anything that a third party can reach: each
uses a private account, or a private iCloud container for Apple and Castro.
The Podcast Index keeps a live list of apps and their features at
`podcastindex.org/apps`. In September 2026 it listed 66 players, and the
most common features were search (46 players), transcripts (39), chapters
(38), funding (25) and the podcast guid (20).

### Hosts

| Host | guid, locked | transcript | chapters | funding, person | value | Podping | Notes |
|---|---|---|---|---|---|---|---|
| Buzzsprout | Yes | Yes | Yes | Yes | Yes | Yes | PSP member |
| Transistor | Yes | Yes | Yes | Yes | Partial | Yes | PSP member |
| Captivate | Yes | Yes | Yes | Yes | Yes | Yes | PSP member |
| RSS.com | Yes | Yes | Yes | Yes | Yes | Yes | |
| Podhome | Yes | Yes | Yes | Yes | Yes | Yes | Built around the namespace, with `liveItem`, `podroll` and `publisher` |
| Castopod (open source) | Yes | Yes | Yes | Yes | Yes | Yes | Speaks ActivityPub, so `socialInteract` works end to end |
| Blubrry | Yes | Yes | Yes | Yes | Yes | Yes | |
| Castos, Spreaker, Podbean | Yes | Partial | Partial | Partial | Partial | Partial | |
| Libsyn | Yes | Partial | ? | ? | No | ? | |
| Acast, Megaphone, Spotify for Creators, Omny | Partial (`guid` at most) | No | No | No | No | No | Enterprise hosts ignore the namespace |

Real feeds mix every generation of tag, including deprecated forms such as
`images`, old `location` attributes and `value` blocks with only node
recipients. The enterprise hosts produce most of the listening hours with
almost none of the new tags.

### Adoption

The Podcast Index publishes counts for each tag, and the figures change
every week *(unverified)*. Hosts emit `guid` and `locked` automatically,
so they're in most actively updated feeds. The tags `transcript` and
`chapters` are in the low hundreds of thousands of feeds, `value` blocks in
the low tens of thousands, and `liveItem`, `socialInteract`,
`alternateEnclosure` and `location` in a few thousand each or fewer.


## Where the holes are

| Hole | Why it exists |
|---|---|
| Value for value in mainstream apps | Apple and Spotify have their own subscription products and take a cut, which Lightning payments would bypass. App Store rules allow wallets only for enrolled organisations, so apps with value for value either run a custodial wallet, like Fountain, or use a wallet from someone else, like Alby or Breez. Some node software is dropping keysend, which forced the spec to add Lightning address recipients, and that change isn't finished. |
| Fragmented transcripts | Four formats (SRT, VTT, JSON and HTML), with inconsistent speaker labels and language tags. Apple's own transcripts make creators less likely to publish theirs. |
| Chapters | JSON chapters and chapters embedded in the media file exist side by side, and apps pick one. Few apps show the images and links of chapters. |
| `liveItem` and `chat` | Only useful when the app also reads Podping for a timely status and can play a live stream, which are both more work than the tag. |
| `socialInteract` | The app has to speak ActivityPub, Nostr or the AT Protocol, each with its own model of identity. Only Castopod closes the loop on the hosting side. |
| `alternateEnclosure` | Hosts don't produce several encodings, so apps have nothing to read. |
| `location`, `person`, `publisher`, `podroll` | Metadata with no obvious use in a UI, adopted by hosts and ignored by most apps. The redesign of `location` in 2025 reset the partial implementations. |
| Churn in the spec | Renamed tags, redesigned attributes and new phases mean that every parser needs an active maintainer, and few libraries outside JavaScript kept up. |
| No conformance suite | The namespace has example feeds and an XSD, but no corpus with expected results, so each implementation finds the edge cases again. |
| Sync | Podcasting 2.0 doesn't define sync of subscriptions or playback. gpodder.net is fragile, and the Open Podcast API was still a draft with no widely deployed server. |


## Libraries

| Ecosystem | Library | What it covers | Relevance |
|---|---|---|---|
| JavaScript | podcast-partytime (and the `@podverse` fork) | The de facto reference parser, from the Podcast Index's own aggregator and used by Podverse. It reports which phases a feed uses. | The best spec by implementation, usable from ClojureScript, but its output has an odd shape. |
| JavaScript | feedsmith (3.0 released 18 September 2026) | A TypeScript parser and generator for RSS, Atom, RDF, JSON Feed and OPML, with the iTunes, Podcast Index, Spotify and other namespaces. | Actively maintained and typed, and a good model for a design around data. |
| JavaScript | podcastdx-client, podcast-index-api | Clients of the Podcast Index API. | Simple, since the authentication header is the only hard part. |
| JavaScript | `@getalby/sdk` | Nostr Wallet Connect, with `pay_keysend`, `pay_invoice` and a WebLN provider. | The practical way to add value for value to a web or React Native app. |
| Python | pod2gen, python-podcastindex, podping-hivewriter and hivewatcher | Feed generation with the namespace, an API client, and a Podping writer and reader. | A reference for reading Podping. |
| Dart | podcast_search | Search in iTunes and the Podcast Index, and feed parsing with chapters, transcripts and people. | Only relevant with ClojureDart and Flutter. |
| JVM | Rome and its modules | Modules for iTunes, MediaRSS, GeoRSS and others, but none for the podcast namespace. | Its module API is awkward and its parser strict, so it isn't worth extending. |
| JVM | AntennaPod's feed parser (Kotlin) | Transcripts, chapters, funding and guid. | Not published as a library. |
| JVM | jsoup | Lenient parsing of XML and HTML. | The fallback for malformed feeds. |
| Clojure | clojure.data.xml, remus, feedparser-clj, clj-rss | StAX parsing with namespaces, wrappers of Rome, and plain RSS generation. | Nothing covers the podcast namespace, the Podcast Index API, Podping or OPML with the podcast conventions. |

Every JVM podcast app parses feeds with code of its own, and nothing on
Maven Central or Clojars targets the namespace.


## Why a Clojure library

A Clojure library made sense, on two conditions. It had to be `.cljc`
first, since the value of Clojure here is one data model and one set of
pure functions for feeds, chapters, transcripts, OPML and sync, shared by a
JVM server, a browser app and a React Native app. A library for the JVM
alone would only serve a crawler. And it shouldn't try to be a framework
for podcast apps, since the player, downloads, storage and UI differ
between platforms and belong to the app.


## Features compared, 27 September 2026

This part compares podcast-clj with podcast apps, hosts, specs and
libraries in other languages, for the features that podcast-clj didn't
have on 27 September 2026. Each feature has one of three verdicts:

- **Target**: the feature fits a library. It needs only pure functions over
  data, requests as maps, and the platform adapters that podcast-clj has.
- **In part**: a library can supply a part, such as the data, the requests
  or the arithmetic, and a server, a wallet, a media player or the app
  supplies the rest.
- **Out**: the feature needs a service that runs all the time, paid
  infrastructure, audio processing, machine learning or a platform API.

The parser already read all of the `podcast` namespace on that day: the 28
tags and 3 child tags that the namespace marks as formalised, and the
changes of 2024 to 2026, such as the `rel` and `country` of
`podcast:location` and the `course` medium.


### Feeds

| Feature | Who has it | Verdict |
|---|---|---|
| Paged feeds, RFC 5005 | AntennaPod and gPodder read them, and Podlove Publisher writes them | Target |
| Finding the feed of a web page | AntennaPod, gPodder | Target |
| Private feeds with a user name and password | Overcast, AntennaPod, gPodder, PinePods | Target |
| Telling a permanent redirect from a temporary one | AntennaPod, gPodder, the Livewire validator | Target |
| Polite polling with `Retry-After` and `Cache-Control` | gPodder, Overcast | Target |
| WebSub | Pocket Casts | In part |

RFC 5005 lets a host split a long feed into pages, linked with `atom:link
rel="next"`, and the feeds of SoundCloud and Audioboom have such links. A
web page names its feeds with `<link rel="alternate"
type="application/rss+xml">`, as the RSS Board and the HTML standard
define. Most private feeds have a token in the URL, but some use HTTP Basic
authentication: Overcast reads the credentials from the URL, and
AntennaPod, gPodder and PinePods ask the listener for them. An app should
only change a stored URL on a permanent redirect, 301 or 308, as AntennaPod
does. A study of server logs from 2024 found that many clients poll more
often after a 429 or a 503, not less.

Many hosts name a WebSub hub, e.g. Buzzsprout, Transistor, Captivate,
RSS.com and Spotify for Creators, and Pocket Casts added WebSub in April
2024. It's only in part because the hub calls a public URL, so a
subscriber needs a server.


### Media files

| Feature | Who has it | Verdict |
|---|---|---|
| Chapters inside MP3 and M4A files | Apple Podcasts, Overcast, Pocket Casts, AntennaPod, Kasts | Target |
| Artwork inside MP3 files | The libraries mutagen, music-metadata and TagLib | Target |
| The duration, from the media file | The libraries podgen and music-metadata | Target |
| Checks of media delivery: HEAD, byte ranges, length and type | The rules of Apple Podcasts, Cast Feed Validator, Podbase | Target |
| Checks of artwork: size, format and colour | The rules of Apple Podcasts, Cast Feed Validator, Podbase | Target |
| CORS on transcript and chapters files | podcast-partytime | Target |
| A check of `podcast:integrity` | No app found | Target |
| Silence trimming, voice boost, loudness and equaliser | Overcast, Pocket Casts, Castro, AntennaPod, Podcast Addict | Out |

Many podcasts put their chapters in the media file rather than the feed:
MP3 files in the ID3v2 frames `CHAP` and `CTOC`, and M4A files in a Nero
`chpl` box or a QuickTime chapter track. Apple Podcasts requires media
servers that answer HEAD and byte-range requests, and artwork that's
square, 1400 to 3000 pixels, JPEG or PNG, in RGB and without an alpha
channel. Browser players also need CORS on transcript and chapters files.
Several libraries read `podcast:integrity`, but no app was found that
checks it. Audio processing belongs to the media engine of the player.


### The profile

| Feature | Who has it | Verdict |
|---|---|---|
| Skipping the outro | Pocket Casts, Castro | Target |
| Stopping after this episode, or after a few | AntennaPod, and the sleep timer of nearly every app | In part |
| Chapter navigation | Pocket Casts, Overcast, Apple Podcasts, Castro, Fountain | Target |
| Starred episodes | Pocket Casts, Castro, Podbean | Target |
| Bookmarks and notes | Pocket Casts, Podcast Addict, Player FM, Podcini | Target |
| Folders for shows | Pocket Casts, Castro, Podbean, Player FM | Target |
| An inbox of new episodes | Castro, AntennaPod, Spotify | Target |
| Ratings that the listener keeps | Podcini | Target |
| Saved filters, or smart playlists | Pocket Casts, Overcast, Downcast, PinePods | Target |
| Playlists and several queues | Pocket Casts, Podcast Addict, Fountain, Podverse, Podcini | Target |
| Listening statistics and a year in review | Pocket Casts, AntennaPod, Spotify, PinePods | Target |
| Rules for downloads and deletes | Apple Podcasts, Pocket Casts, AntennaPod, PinePods | In part |
| Clips | Pocket Casts, Spotify, Fountain, Podverse | In part |

Most of these are small stamped entries, or pure functions over the state.
The sleep timer and download rules are only in part, because the app owns
the clock and does the downloading. A clip as an audio or video file needs
a media encoder.


### Sync between apps

| Feature | Who has it | Verdict |
|---|---|---|
| A client of the gpodder API | AntennaPod, gPodder, Kasts, PinePods | Target |
| A server of the gpodder API | Nextcloud GPodder Sync, oPodSync, gpodder2go, PinePods, mygpo | Target |
| The Open Podcast API | No client or server ships it | In part |
| Sync over the local network, with no server | Podcini | Out |
| The sync services of the apps | Pocket Casts, Castro, Podverse, Apple Podcasts | Out |

Most open-source apps sync through the gpodder API, which gpodder.net and
self-hosted servers speak. The API names shows and episodes by URL, which
makes identity the hard part, and the Nextcloud app adds an optional
episode guid.

AntennaPod, Funkwhale, Kasts, Podfriend and the Nextcloud app started the
Open Podcast API in 2022, to sync subscriptions, progress, favourites and
queues. Its spec for subscriptions was public, but the spec for episodes
was still a draft from June 2024, and no client or server shipped it. Sync
over a local network needs the platform to discover the devices.


### Discovery and sharing

| Feature | Who has it | Verdict |
|---|---|---|
| Accessors for `podroll`, `publisher`, `socialInteract`, `soundbite` and `txt` | Pocket Casts, Apple Podcasts, many Podcasting 2.0 apps | Target |
| Charts by category | The Podcast Index | Target |
| Links to apps, and links to a time | Apple Podcasts, Pocket Casts, Spotify, the Podlove Subscribe Button | In part |
| Personal recommendations | Apple Podcasts, Overcast, Pocket Casts | Out |
| Search inside the audio of all shows | Castbox, Apple Podcasts, Snipd | Out |
| Discovery through a chat | Spotify, YouTube Music, Castro | Out |

Pocket Casts shows a podroll next to similar shows, and Apple Podcasts
reads `txt` to verify the owner of a show. Each app has its own form of
link, and a community list gives the forms of 45 apps. These forms are
facts about other products, so only the stable parts fit a library.
Recommendations, search inside all audio and discovery through a chat need
the data or the models that only a service has.


### Comments and other social features

| Feature | Who has it | Verdict |
|---|---|---|
| Comments from ActivityPub and AT Protocol threads | Podverse, Castamatic, and Castopod writes the threads | Target |
| Comments from Nostr | Fountain | In part |
| Public ratings and reviews | Apple Podcasts, Pocket Casts, Podcast Guru | Out |
| Comments and polls inside one platform | Spotify, Castbox | Out |
| The activity of friends | Fountain | Out |

The tag `podcast:socialInteract` points to the root post of a discussion,
and the spec lists ActivityPub, the AT Protocol, Nostr, Matrix, Hive, Twitter and
Lightning. Castopod publishes episodes over ActivityPub and writes the
tag, and AntennaPod only opens the link in a browser. Fountain shows Nostr
posts about an episode as comments, using the ids that NIP-73 gives
podcasts and episodes. Nostr is only in part because it needs open
WebSocket connections to relays. Ratings, polls and friends need a service
with accounts.


### Value for value

| Feature | Who has it | Verdict |
|---|---|---|
| Lightning addresses: LNURL-pay and the keysend lookup | Fountain, Castamatic, Breez, the library lightning-tools | In part |
| Payment from the listener's wallet: NWC and WebLN | Fountain, Castamatic, Podverse on the web | In part |
| RSS Payment Metadata | Fountain, Castamatic, Helipad, BoostBox | Target |
| Received boostagrams | Helipad, BoostBox | In part |
| Card payments and other kinds of payment | Fountain, and two open proposals | Out |

A recipient can be a Lightning address, paid with LNURL-pay at
`/.well-known/lnurlp/<user>`, or with keysend to the node at
`/.well-known/keysend/<user>`. Fountain has a wallet of its own, Castamatic
and Fountain also pay from the listener's wallet through Nostr Wallet
Connect, and Podverse on the web pays through WebLN. Several large wallets
can't send keysend, so the RSS Payment Metadata of the namespace puts
`rss::payment::`, an action, a URL and a message in the comment of a
payment, and the URL answers with the details in an `x-rss-payment`
header. BoostBox, a small MIT-licensed server in Clojure, stores such
metadata, and Helipad and Castamatic read it.

These are only in part because the wallet makes the payment, and the host
owns the connection to its node. Card payments, and the proposals for
Stripe (#781) and ERC-20 tokens (#768), need accounts with a payment
service.


### Tools for hosts

| Feature | Who has it | Verdict |
|---|---|---|
| Checks of a feed over the network | Cast Feed Validator, Podbase, Livewire, Apple Podcasts | Target |
| OP3: the prefix and the public statistics | Transistor, Captivate, Libsyn, Spreaker, Simplecast, RSS.com, Podhome | Target |
| Download counts by the rules of the IAB | Hosts with IAB certification, OP3 | In part |
| Paged feeds | Podlove Publisher | Target |
| A private feed for each subscriber | Buzzsprout, Transistor, Captivate, Castos | In part |
| Ad insertion, generated show notes, video and websites | Most hosts | Out |

OP3 is a free, open-source service that counts downloads through a prefix
on each enclosure URL, and each show gets a public page of statistics.
Version 2.2 of the Podcast Measurement Technical Guidelines of the IAB Tech
Lab defines a download: a request that passes the filters, counted once
for each IP address and user agent in 24 hours, for at least a minute of
audio. A draft of version 2.3 adds video and analytics prefixes. Counting
is only in part because the host collects the logs and the lists of bots
and data centres, and a private feed only in part because the host serves
the feeds and keeps the tokens.


## Proposals to follow

The namespace had more than 30 open proposals, and the plan for phase 8
named `podcast:follow` (#609) as its next tag. These were the most
relevant to apps, numbered as the discussions in the repository of the
namespace:

- `socialAccount` (#784): the profiles of a show on social platforms
- `sortOrder` (#747): an order of episodes that the creator names
- `ai` (#735), `aiTransparency` (#753) and `disclosure` (#669): disclosing
  the use of AI
- `fidelity="word"` for transcripts (#600): timing for each word
- `timeOffset` for alternate enclosures (#733): shifting the transcript and
  chapters for another file
- `liveSchedule` (#683): the times of recurring live shows
- a comments file for `socialInteract` (#680), and a Nostr note as its root
  post (#774)
- `metaBoost` (#676): an endpoint for the metadata of boosts
- `private` (#627): a mark for a private feed


## Out of scope

These features need services, platforms or processing that a library
can't supply:

- **Audio processing**: silence trimming, voice boost, loudness
  normalisation, an equaliser and a mono mix.
- **Machine learning**: generated transcripts, chapters and summaries,
  translation, questions about an episode, playlists from a prompt, and
  audio digests.
- **Platforms**: CarPlay, Android Auto, Chromecast, AirPlay, Sonos, Alexa,
  watches, TV apps, widgets and notifications.
- **Services with accounts**: public ratings and reviews, polls, comments
  inside one platform, friends, personal recommendations, and search inside
  the audio of all shows.
- **Services of hosts**: dynamic ad insertion, ad marketplaces, IAB
  certification, transcoding, video with HLS, audiograms and websites.
- **The I/O of an app**: downloads in the background, storage, and the type
  of the network.


## Sources

**The ecosystem**

- The podcast namespace: <https://github.com/Podcastindex-org/podcast-namespace>
- Its discussions, by number:
  <https://github.com/Podcastindex-org/podcast-namespace/discussions>
- The namespace docs: <https://podcasting2.org/docs/podcast-namespace>
- The Podcast Index API: <https://podcastindex-org.github.io/docs-api/>
- The Podcasting 2.0 apps: <https://podcastindex.org/apps>
- PSP-1:
  <https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification>
- Rome modules: <https://rometools.github.io/rome/Modules/index.html>
- podcast_search (Dart): <https://pub.dev/packages/podcast_search>
- podcastdx-client: <https://github.com/RyanHirsch/podcastdx-client>
- Alby SDK and NWC: <https://github.com/getAlby/js-sdk>

**Specifications**

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
- Pocket Casts, skipping the intro and outro:
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
- AntennaPod, the comments link:
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
- The Open Podcast API: <https://openpodcastapi.org/about/>,
  <https://github.com/OpenPodcastAPI/api-specs>
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

- podcast-partytime: <https://github.com/RyanHirsch/partytime>,
  <https://www.npmjs.com/package/@podverse/podcast-partytime>
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
