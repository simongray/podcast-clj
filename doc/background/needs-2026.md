# The needs of podcast-clj, October 2026

This report lists what essential podcast software needs and podcast-clj
doesn't have yet, as of 11 October 2026 (commit 53ef0b1), so that the
next upgrades can be planned from it. It has three parts:

- **Undocumented needs**: essential features that the library lacks and
  that no TODO, report, doc or working note names.
- **Documented gaps**: the gaps that the docs, the TODO comments and the
  working notes already name, checked against the code again.
- **Nice-to-haves**: everything else that turned up, one line each.

A **need** is a feature that essential podcast software can't do without:
what every mainstream listener app (Apple Podcasts, Pocket Casts,
Overcast, AntennaPod, Castro, Podcast Addict), every host or validator
(Buzzsprout, Transistor, Apple Podcasts Connect, Cast Feed Validator,
Livewire), or every sync server does, and that someone building on the
library would otherwise have to write as a sizeable piece of
podcast-specific code. When in doubt, an item went to the nice-to-haves.
What the [principles](../principles.md) rule out (audio processing, machine
learning, platform features, services with accounts, the download and
playback themselves) isn't listed at all.

Every item has two scores:

- **Difficulty**: 1 an hour, 2 a day, 3 a few days, 4 a week or two,
  5 immense.
- **Gain**: 1 a few apps or hosts would notice, 2 a real subset would
  (hosts and validators, browser apps, value-for-value apps, servers),
  3 most apps built on the library want it, 4 every listener app on the
  library is visibly broken for its listeners without it, 5 nothing can
  ship without it.

[The method](#method) at the end says how the lists were made.


## Summary

The undocumented needs, by gain:

| Need | Difficulty | Gain |
|---|---|---|
| [A ceiling on the wait between checks of a feed](#a-ceiling-on-the-wait-between-checks-of-a-feed) | 1 | 4 |
| [Keep the episodes that a fetch no longer brings back](#keep-the-episodes-that-a-fetch-no-longer-brings-back) | 3 | 4 |
| [Follow a change of the guid of a subscribed feed](#follow-a-change-of-the-guid-of-a-subscribed-feed) | 3 | 4 |
| [Duplicate guids and enclosure URLs pass validation](#duplicate-guids-and-enclosure-urls-pass-validation) | 1 | 3 |
| [Category names aren't checked against Apple's list](#category-names-arent-checked-against-apples-list) | 2 | 3 |
| [A version and a migration path for the saved library](#a-version-and-a-migration-path-for-the-saved-library) | 2 | 3 |
| [Telling a dead network from a failing feed](#telling-a-dead-network-from-a-failing-feed) | 2 | 3 |
| [Recognise an episode whose guid changed](#recognise-an-episode-whose-guid-changed) | 3 | 3 |
| [Saving and loading large libraries in parts](#saving-and-loading-large-libraries-in-parts) | 4 | 3 |
| [A missing enclosure length or type passes validation](#a-missing-enclosure-length-or-type-passes-validation) | 1 | 2 |

None of the documented gaps is a need by the test above: each is
either built since it was written down, left to the app on purpose, or
wanted by a subset of apps. The twelve that come closest are written up
to the same depth under [Documented gaps](#documented-gaps), and the
rest are in the nice-to-haves, marked "documented".

| Documented gap | Difficulty | Gain |
|---|---|---|
| [The record of downloaded files in the library](#the-record-of-downloaded-files-in-the-library) | 2 | 3 |
| [Offline episodes for a browser app](#offline-episodes-for-a-browser-app) | 3 | 2 |
| [Many listeners on one server](#many-listeners-on-one-server) | 4 | 2 |
| [A lenient XML parser for JavaScript](#a-lenient-xml-parser-for-javascript) | 4 | 2 |
| [Ogg Opus and ADTS AAC readers](#ogg-opus-and-adts-aac-readers) | 3 | 2 |
| [Stores over the APIs of cloud services](#stores-over-the-apis-of-cloud-services) | 3 | 2 |
| [Read only the head of a feed for previews](#read-only-the-head-of-a-feed-for-previews) | 2 | 2 |
| [Stores and senders for React Native and Capacitor](#stores-and-senders-for-react-native-and-capacitor) | 2 | 2 |
| [Counts of likes, reposts and replies on a comment](#counts-of-likes-reposts-and-replies-on-a-comment) | 2 | 2 |
| [Word timing from inline WebVTT timestamps](#word-timing-from-inline-webvtt-timestamps) | 2 | 2 |
| [Parsing server logs into request maps](#parsing-server-logs-into-request-maps) | 2 | 2 |
| [A bsky.app link as the root of a thread](#a-bskyapp-link-as-the-root-of-a-thread) | 1 | 2 |


## Undocumented needs

### A ceiling on the wait between checks of a feed

Difficulty 1, gain 4.

A listener app checks every subscribed feed at least every few hours,
whatever rhythm the feed itself suggests, so that an early, extra or late
episode shows up the same day. AntennaPod refreshes every feed on one
interval, 12 hours by default, Pocket Casts polls hourly on its servers,
gPodder has a global interval, and Apple Podcasts, Overcast and Podcast
Addict check on fixed schedules.

The library sets the next check from the feed alone: the
`podcast:updateFrequency` rule, the ttl, the syndication period, or else
the median gap between recent items, capped only at a year
([schedule.cljc:93](../../src/dk/simongray/podcast/feed/schedule.cljc#L93)).
The wait of a day in `app.feeds/default-cadence` applies only when the feed
gives no time at all. So a weekly show is checked once a week and a
monthly show once a month. Most real checks come after the expected time,
since shows and devices run late, and then the whole gap is added again:
the schedule test expects 8 March for a weekly show checked on 1 March
whose latest item is from 22 February. An app can pass every guid to
`refresh!` on a timer, but that throws the schedule away, and neither the
guide nor the code names a ceiling.

**What it takes.** Build on RSS 2.0, where ttl is a hint, and RFC 9111
section 4.2, where freshness bounds a cache and not a poll. Add a
`:max-wait` key to the cadence, six or twelve hours, and apply it in
`check-at` as the latest allowed next check, with `:not-before` still
winning, since it carries a server's Retry-After. The year cap in
`schedule/next-check` stays as the feed's own claim. A shorter wait once a
predicted episode is overdue is optional. The data shape doesn't change.
Tests for a weekly and a monthly feed in `app/feeds_test.cljc`, and a line
in "Keep the feeds current" of the guide. An hour, plus the tests.


### Keep the episodes that a fetch no longer brings back

Difficulty 3, gain 4.

Many hosts serve only the newest items of a feed; the rest sits on older
RFC 5005 pages, or nowhere. Every mainstream app keeps an episode once it
has seen it, so that the back catalogue, the downloads and the play
history stay reachable after the host drops the item from the feed.
AntennaPod, Pocket Casts, Apple Podcasts, Castro, Podcast Addict and
gPodder all do.

The library replaces the stored feed with the fresh one on every refresh
([feeds.cljc:54](../../src/dk/simongray/podcast/app/feeds.cljc#L54), and the
docstring of `refresh` says so). A due feed is fetched without
`:max-pages`, and a subscription that arrives by sync is fetched with a
bare URL ([library.cljc:419](../../src/dk/simongray/podcast/app/library.cljc#L419)),
so the pages read at subscription vanish at the first refresh, and a
second device never sees them. An episode that fell out of the feed can't
be viewed or played, since `item-of` looks only in the stored feed, and
the download plan deletes its file as trimmed. A 200 with an empty
channel, which hosts serve during outages, wipes every item the same way
and counts as changed, so the plan then deletes the show's files. The
guide even says that `:max-pages` "is only needed on the first fetch"
([the guide](../building-an-app.md#subscribe-to-a-show)), which implies that
the pages are kept.

**What it takes.** Build on RSS 2.0, where a feed is a window on the
channel, and RFC 5005 sections 3 and 4. Add a merge policy to
`app.feeds/refresh`: keep the items of the old feed that the fresh page
lacks, each with a last-seen stamp and whether the host removed it,
computed with `feed/diff`, which already gives the removed items. Treat a
fresh feed that is empty, or far shorter than the stored one, as suspect
rather than as removals. Add a cap on the kept episodes to the config, and
a tombstone for an item the listener removed. Let `refresh!` take
`:max-pages` for the first fetch of a pending subscription. The slim form
already keeps the guid, date and media of each item, so the server keeps
the merged list too. Tests in `app/feeds_test.cljc` and
`app/library_test.cljc`, plus a corpus feed refreshed with fewer items.
About three days.


### Follow a change of the guid of a subscribed feed

Difficulty 3, gain 4.

The guid of a feed changes after subscription when the show moves to a
host that writes a `podcast:guid` of its own, and a feed without a
declared guid changes its derived guid at every permanent move, since the
UUIDv5 rule seeds it with the URL. The subscription, the queue, the
progress and the stars are all keyed by that guid. Apple Podcasts, Pocket
Casts and AntennaPod key shows by an internal id that survives a move, and
Fountain, Podverse and CurioCaster follow `podcast:guid`.

The library rekeys only on the first fetch of a subscription
([library.cljc:435](../../src/dk/simongray/podcast/app/library.cljc#L435)). A
later refresh keeps the entry under the old key and changes only its URL,
while every episode id is derived afresh from the feed and that URL, in
`episode-id` and in `profile.queue/refresh`. After a move, `item-of` finds
nothing, so the views and `read-files!` give nil, progress lands under a
guid with no subscription, and no new episode joins the queue or the
inbox, since `subscribed?` fails for the new guid. Three agents reproduced
this at the REPL, with a 301 and with a feed that gains a `podcast:guid`.
The same derivation sits in `criteria`, `downloads`, `feed.search`,
`app.episode` and `app.show`.

**What it takes.** Build on the `podcast:guid` tag, whose guid is meant to
outlive the URL, and RFC 9110 section 15.4.2 for the 301. Make the stored
key the stable id: `episode-id`, `item-of` and `urls` in `app.library`,
`criteria/episodes`, `feed.search/feed-documents`, `downloads/plan` and
`profile.queue/refresh` take the feed guid from the feeds map, or from a
guid passed in, instead of deriving it from the feed and its URL. Then a
refresh ignores a newly declared guid and adds a `:fetch/guid-changed`
diagnostic with the old and the new guid, so that an app can show it. The
alternative, running the first-fetch path on every fetch and rekeying every
episode id in the queue, inbox, progress and marks as CRDT writes, is
bigger. Tests: a feed that moves by 301 and one that gains a guid, in
`app/library_test.cljc` and `profile/queue_test.cljc`. A few days.


### Duplicate guids and enclosure URLs pass validation

Difficulty 1, gain 3.

Every app, directory and sync server keys an episode by its guid, and RSS
2.0, Apple's guide and PSP-1 all say that it must be unique. Two items
with one guid collapse into one episode: the library's own `diff` keeps
the first of the items that share an identity
([feed.cljc:435](../../src/dk/simongray/podcast/feed.cljc#L435)), and
`join-pages` drops the later one, so a host that serves such a feed sees
the second episode vanish from every app built on the library. The
validator never notices: it checks each item alone
([validate.cljc:276](../../src/dk/simongray/podcast/feed/validate.cljc#L276)),
so a feed with the same guid on two items passes as valid even under the
Apple requirements. Cast Feed Validator, Podbase, Livewire and Apple
Podcasts Connect all report a duplicate guid, so a validator built on the
library misses the most common identity error of real feeds.

**What it takes.** The rule is the guid element of RSS 2.0, with Apple's
A Podcaster's Guide to RSS and the item requirements of PSP-1. One pass
over the items in `feed.validate` groups them by `guid/item-guid` and
separately by the enclosure URL, and emits `:validate/duplicate-guid` as
an error and `:validate/duplicate-enclosure` as a warning, since a trailer
and an episode can share a file, each at the path of the later item with
the path of the first as a detail. The ids go into the catalogue in
`diagnostics`, `dev/reference.clj` regenerates the diagnostics page, and
`validate_test.cljc` gains a case. No I/O. A few hours.


### Category names aren't checked against Apple's list

Difficulty 2, gain 3.

Apple Podcasts lists a show only under the categories of its own list, 19
categories and 91 subcategories, and Apple Podcasts Connect refuses a feed
whose category it doesn't know. PSP-1 requires `itunes:category` from that
list, and Spotify and the Podcast Index read the same names. A misspelt or
invented category means that a show is listed nowhere, or that the
submission fails, and the podcaster hears it from the directory rather than
from the host. The library holds the names already: `apple-genres` in
`directory` maps all 110 names to Apple's genre ids for the charts. The
validator never uses them. The registry entry types the text attribute as
plain text with no values
([spec.cljc:340](../../src/dk/simongray/podcast/feed/spec.cljc#L340)), the
Apple requirements only ask that categories exist, and the registry lets a
category nest to any depth, while Apple allows one subcategory. A probe
with the category "Not A Category" and a third level passed as valid.
Cast Feed Validator, Podbase and Livewire all flag an unknown category.

**What it takes.** The list is Apple's, from the Apple Podcasts categories
page and the `itunes:category` section of A Podcaster's Guide to RSS, and
PSP-1 requires it. Validation sits in layer 2 and `directory` in layer 3,
so the names move down to `feed.spec` as a dated table of category to
subcategories, and `directory` derives `apple-genres` from it with the
ids. A structural rule for `itunes:category` walks the nested categories
and emits `:validate/unknown-category` for a name not in the table,
`:validate/wrong-subcategory` for a subcategory under the wrong parent,
and `:validate/category-depth` for a third level, all warnings, and a
predicate requirement makes them errors under the Apple and PSP-1
requirements. Add the ids to the catalogue, regenerate the docs, test in
`validate_test.cljc` and `directory_test.cljc`, and date the table in
[Services](../services.md). A day, since `apple-genres` is flat and the
parent of each subcategory has to be added.


### A version and a migration path for the saved library

Difficulty 2, gain 3.

Every app that ships updates must read the data that an older version of
itself saved. The saved library carries no version of its format. The
function `save!` prints the profile, the feeds, the followed lists and the
last sync into one EDN document with no version key
([library.cljc:862](../../src/dk/simongray/podcast/app/library.cljc#L862)),
and `restore!` reads back whatever map it finds: its helper merges the
named keys over the live library and checks only that the document is a
map, so a document of a newer format replaces the CRDT profile without a
word. An agent confirmed at the REPL that a library.edn holding
`{:version 99 :profile {:future-shape 1}}` is restored as the profile. The
only migration in the code is `profile/migrated`, which moves a played
mark of an older version lazily, and the inspector writes its own
migration of the profile at start
([player.cljs:591](../../web/inspector/player.cljs#L591)), which shows that
apps end up writing this themselves. The sync documents already carry a
version, and like-minded reports `:sync/newer-format` for a newer one, so
the pattern is set. AntennaPod runs SQLite migrations on each update, and
Pocket Casts and Overcast version their stores.

**What it takes.** No standard applies; the pattern is the version of a
like-minded document. Write a version next to the parts in `save!`, and
read it in `restore!`: a newer version leaves the parts as `make` made
them and reports `:library/newer-format`, and an older one, or none, runs
the migrations in order. Keep a vector of pure migrations per part for the
feeds, the followed lists and the sync state, since the profile already
migrates lazily. The first migration of the feeds can drop the feed, the
ETag and Last-Modified and keep the URL, because an entry with only its
URL is fetched again as a first fetch. Add the id to the catalogue,
regenerate the diagnostics page, and add a test that restores a
library.edn of each past version, kept under the test resources. About a
day.


### Telling a dead network from a failing feed

Difficulty 2, gain 3.

A phone in flight mode or a laptop on a train fails every feed on every
refresh, and the library counts each as a failure of the feed. In
`app.feeds`, `failed` adds one to the failures on any error and sets the
next check by the failure cadence, whose waits are 15 minutes, an hour,
four hours and then a day
([feeds.cljc:85](../../src/dk/simongray/podcast/app/feeds.cljc#L85)). After
four attempts offline every feed waits a day, and when the network returns
nothing is due until tomorrow. Every exception of a fetch becomes
`:fetch/unreachable`, which covers a failed connection, a timeout, a
too-large answer and a refused URL alike; wary-fetch types only the last
three, and a failed connection is the platform's own exception. Nothing
in the source mentions being offline or online. AntennaPod checks
connectivity before an automatic refresh and never penalises a feed for
it; Pocket Casts and Apple Podcasts retry at once when the network
returns. An app can recover by calling `refresh!` with every guid once
online, since a success resets the failures, but it has to know that, and
the docs don't say so.

**What it takes.** RFC 9110 section 10.2.3 covers Retry-After, which the
library honours as `:not-before`; nothing covers a client without a
network, so this is the library's own rule. Give `:fetch/unreachable` a
kind, refused, too large, timeout or connection, from the type of the
exception on each platform, or give wary-fetch an unreachable type. Take
an `:online?` option and config key in `library/refresh!` and
`feeds/refresh!`, which an app sets from the platform. When it is false,
or when every fetch of the round failed by connection, leave the failures
and the next check of each feed as they were and put one
`:refresh/offline` diagnostic in the report. A catalogue id, a line in the
guide, and a test with a sender that throws, on both platforms. A day.


### Recognise an episode whose guid changed

Difficulty 3, gain 3.

A host migration or a CMS change rewrites the guid of every item while the
enclosure URL, the title and the date stay the same. A listener app then
recognises the old episode, so that the back catalogue doesn't land in the
inbox or the queue as new, and the progress and the stars aren't lost.
AntennaPod guesses a duplicate by enclosure URL, or by title, date and
duration, and moves the old state to the new guid; Pocket Casts matches on
the media URL; gPodder and the gpodder API name an episode by it. Apple
Podcasts doesn't, which is why Apple tells podcasters never to change
guids.

The library identifies an item by its guid, or else its enclosure URL
([guid.cljc:58](../../src/dk/simongray/podcast/feed/guid.cljc#L58)), and by
nothing else. The diff compares items by that id alone, `new-episodes`
reports every added item, and the refresh of the profile skips only the
ids it already knows, then queues every other added id at its publication
date ([queue.cljc:279](../../src/dk/simongray/podcast/profile/queue.cljc#L279)).
A changed guid so makes every item a new episode, and a show of hundreds
appends them all to the queue of a show that queues automatically, which
an agent reproduced at the REPL.

**What it takes.** Build on RSS 2.0 and Apple's guide, which says that a
changed guid is a new episode, the very thing an app must undo. Add a
second identity to `feed/diff`: an added item whose enclosure URL matches
a removed item, or else whose title and date match within a day, becomes
a renamed pair, enclosure URL first since some hosts rewrite that too,
e.g. when an OP3 prefix is added later. Add a function in `profile` that
moves an episode id in the queue, inbox, progress and marks, as `rekey`
moves a feed, written as the feed's author so that devices converge, and
let `profile.queue/refresh` use it and list the renamed ids. In the same
batch, cap what counts as new to items dated after the newest item of the
old parse, so that an unmatched rewrite can't append a back catalogue.
Tests on `diff`, `queue/refresh` and a corpus feed with rewritten guids.
A few days.


### Saving and loading large libraries in parts

Difficulty 4, gain 3.

A listener of Pocket Casts or Apple Podcasts class has 100 to 300
subscriptions, some of them daily shows with thousands of episodes. The
library keeps every parsed feed whole in memory, about twice the size of
its XML, and `save!` writes all of them with the profile into one
library.edn in one `pr-str`. Measured at the REPL, a 30 MB corpus feed of
888 items makes a 31 MB library.edn that takes 0.8 s to print and 0.7 s to
read back on the JVM, and a browser reads EDN far slower. An app can save
the profile alone after each change of the play position by naming the
keys, which the guide doesn't say, but a save after each refresh still
writes every feed. The search index is the only part saved in pieces. The
one remedy, `feeds/slim` as the keep function, drops the titles too, so
only a server can use it, and the server does. Lookups aren't the
pressure: a hundred calls of `episode` on the last item of that feed took
22 ms. The size of each save and the memory of a phone or a browser are.
Pocket Casts, Apple Podcasts, AntennaPod, Castro and Overcast keep
episodes in a database and load pages of them.

**What it takes.** Two steps. First, write library.edn without the feeds
and a document per feed, put only when its fetch time is later than the
last save, and delete the documents of feeds that left, as the index
already does through its plan; `restore!` reads them all. This changes no
API and takes a few days, with a test on the largest corpus feed. Second,
let an entry of the feeds hold the metadata and the slim part with the
feed absent, and give `show`, `episode`, `episodes` and `search` a way to
read a feed from the store when it's missing, or a keep function that
keeps the newest items whole and the rest slim. That step turns the views
into calls that may read the store, and `merge`, the reconciliation and
the index must agree on which feeds are loaded. A week or two.


### A missing enclosure length or type passes validation

Difficulty 1, gain 2.

RSS 2.0 says that an enclosure has three required attributes, url, length
and type, and Apple's guide repeats it. An app needs the length to show
download progress and the space a file takes, and the type to pick a
player and to know that the file is audio or video at all. Apple Podcasts
Connect rejects a feed whose enclosure lacks a type. The registry marks
only url as required
([spec.cljc:278](../../src/dk/simongray/podcast/feed/spec.cljc#L278)), so the
validator reports a missing url alone; the Apple requirements only ask
that an enclosure exists; and a media type isn't a coerced type, so any
string passes as one. A probe confirmed it: an enclosure with only a url,
one with length 0 and type audio/mp3, and one with type text/html gave no
diagnostic under the Apple requirements, and a parsed `length=""` was
dropped as an optional attribute before the validator saw it. Apple
Podcasts Connect, Cast Feed Validator, Podbase, Livewire and the W3C
validator all report these.

**What it takes.** The enclosure element of RSS 2.0, with the RSS Best
Practices Profile 4.1.1.20.5 for a length of 0 as unknown. Mark length and
type required in the registry, which makes the validator report them and
makes the parser keep a blank one for the validator, as
`:feed/empty-attribute` already promises. Then a structural rule emits
`:validate/unknown-media-type` as a warning for a type that isn't audio,
video or an HLS playlist type, and `:validate/unknown-length` as an info
for a length of 0. On the checker side, a `:check/no-type` warning names
the type the server serves when the feed declares none. Catalogue ids,
regenerated docs, cases in `validate_test.cljc` and `check_test.cljc`. An
hour or two, half a day with the checker.


## Documented gaps

Every gap that the docs, the TODO comments and the working notes name was
checked against the code again. None is a need by the strict test: the
verdict tables of September 2026 were nearly all built in the weeks after
they were written, the download record and the sync server's limits are
choices that the docs state, and the rest serve a subset of apps. These
twelve come closest, by gain. The other documented gaps are in the
nice-to-haves, marked "documented".

### The record of downloaded files in the library

Difficulty 2, gain 3. Documented in [How to build a podcast app](../building-an-app.md#what-you-build-yourself)
and [Syncing](../sync.md#what-stays-out).

The download plan takes the files on the device as a map that you keep
yourself: which episodes have a file, its bytes, when it came, whether the
listener wants it kept, and whether it was removed. The guide assigns that
record to you under "What you build yourself", and the sync page says
that downloads belong to one device. So you write the functions that add
a file when a download starts and ends, mark it removed, sum the storage
used, find the local file of an episode when the player starts, and
persist all of it, since the library has no files part and `save!` writes
none. Every app with downloads has this bookkeeping: AntennaPod's media
table, Pocket Casts' storage screen, Apple Podcasts, Overcast and Castro.
The download itself stays with you.

**What it takes.** A files part of the library, local to the device,
saved by `save!` but never synced, with functions in `downloads` and
`app.library` for a file that started, one that finished (with its bytes
and path), one that was removed, the keep flag, the list, and the usage
by show, and `plan` reading that part when no files are given. Give the
episode view the local file when one exists, so the player picks it. The
plan already defines the entry shape. A test of each with the plan. About
a day.

### Offline episodes for a browser app

Difficulty 3, gain 2. Documented in the working notes on the podcast
server.

A podcast app in a browser or a PWA saves episodes for offline listening,
which every mainstream app does by default. Playing needs no proxy, since
an audio element plays cross-origin media without CORS, which is how the
inspector plays. Saving a file does need `fetch`, and hosts rarely send
CORS headers on audio, so the library's answer is the proxy of wary-fetch,
mounted in the podcast server. That proxy holds each body in memory and
refuses anything over 32 MB, so an hour-long MP3 of 50 to 100 MB can only
be fetched in several range requests that the page stitches together,
each buffered on the server. The server has no route that streams a media
file with Range and Content-Range passed through. The notes on the server
record both limits.

**What it takes.** In wary-fetch's proxy or beside it, a streaming mode
that pipes the upstream body to the Ring response without buffering (an
InputStream body on the JVM, a ReadableStream in Node), passes Range
through and answers 206 with Content-Range, Accept-Ranges and
Content-Length, keeps the allow predicate and the Via loop check, and
takes a byte limit per response as an option. In the podcast server, a
media route restricted to the enclosure URLs of the listener's feeds, so
that the server isn't an open relay. RFC 9110 section 14. A few days,
with tests on both platforms.

### Many listeners on one server

Difficulty 4, gain 2. Documented in the [server's README](../../server/README.md),
[Syncing](../sync.md#the-server) and the docstring of the gpodder handler.

The self-hosted server serves one listener: one sync folder, one gpodder
state, one library that refreshes that listener's feeds, and a single
password with no accounts and no rate limit, as its README says. A
household server or a small hosted service needs a user table, a login
that slows guesses, a WebDAV folder and a gpodder handler per user, and
one shared set of fetched feeds, so that a show followed by many users is
fetched once. The docs show the per-listener composition, a WebDAV handler
per user id and a gpodder handler per listener, so the podcast-specific
gap is only the shared feeds: `make` binds one profile to one set of
feeds, so two listeners can't share them without two copies. The servers gpodder.net,
oPodSync, PinePods and the Nextcloud app all serve many users.

**What it takes.** Split the library into shared feeds and per-listener
profiles: the feeds kept once with their own refresh, and a profile plus
sync cursors per listener that read them. In the server, a user store with
hashed passwords and a login interceptor with a delay, a route that picks
the listener's folder and gpodder handler by the authenticated user, and a
refresh that runs once over the union of all subscriptions. Keep the
one-listener setup as the default. The authentication and devices sections
of the gpodder API, RFC 7617 and RFC 4918. A week or two, with tests over
two listeners and one shared feed.

### A lenient XML parser for JavaScript

Difficulty 4, gain 2. Documented in [Architecture](../architecture.md#not-built-yet).

A share of real feeds isn't well-formed XML. The library repairs the
common faults on both platforms (bare ampersands, HTML entities, control
characters, undeclared prefixes, junk around the document) and falls back
on jsoup for the rest, but only on the JVM: in a browser or Node the
fallback is nil ([repair.cljc:243](../../src/dk/simongray/podcast/feed/repair.cljc#L243)),
so such a feed is refused as malformed and can't be subscribed or
refreshed. What's left after the repairs is mismatched or unclosed tags
and a raw `<` in text. The calibrating agent ran the 31 corpus feeds in a
fresh JVM with jsoup on the classpath: none needed the fallback, or even a
repair, so the residue is zero on mainstream hosts and unknown on the long
tail. AntennaPod, gPodder and Pocket Casts read such feeds, and so does
every JavaScript reader built on htmlparser2 or fast-xml-parser.

**What it takes.** A tolerant XML tokenizer in `.cljc`, or a port of the
recovery rules that html-pieces has for HTML (close open tags at the next
sibling start tag, swallow bare ampersands and lone angle brackets, drop
the rest), feeding qname-hiccup's tree, used where jsoup is used today
with the same diagnostic. A hook for a lenient parser of the app's own is
an hour and a reasonable first step. The full tokenizer is a week or two,
with the corpus of broken feeds and the JVM results as the expected trees.

### Ogg Opus and ADTS AAC readers

Difficulty 3, gain 2. Documented in the working note on the media reader
reorganisation, and [Diagnostics](../diagnostics.md) says that media files
are MP3 or MP4.

Files in Ogg Opus and raw AAC in ADTS give `:file/unknown-format`, or
`:mp3/no-audio` for ADTS files that start with an ID3 tag, since the
sniffing knows only ID3, ftyp and an MPEG frame
([media.cljc:27](../../src/dk/simongray/podcast/format/media.cljc#L27)). An
app gets no duration, tags or artwork from them. Opus is the usual second
format of an alternate enclosure, which Podlove Publisher and some
independent feeds offer, and a few hosts serve .aac files. AntennaPod,
Podcast Addict and Pocket Casts on Android play Opus through the platform.
The note on the media readers names both readers as its second step, and
the ADTS mis-sniff.

**What it takes.** An Ogg namespace that walks pages, reads OpusHead and
OpusTags (Vorbis comments and the picture), and takes the duration from
the granule position of the last page by a tail range, per RFC 3533 and
RFC 7845. An ADTS reader that skips an ID3 tag, reads the header for
sample rate, channels and profile (ISO/IEC 13818-7 section 6.2), and
estimates the duration from the frame count. Two entries in the sniffing,
and corpus files of each. A few days. FLAC, WAV and Vorbis can follow over
the same Ogg walker.

### Stores over the APIs of cloud services

Difficulty 3, gain 2. Documented in [Architecture](../architecture.md#not-built-yet).

On a phone there is often no synced folder and no WebDAV server, only an
account on Dropbox, Google Drive or iCloud. The folder sync needs a place
that holds files, and like-minded has folder, WebDAV, IndexedDB, memory
and encrypted stores only, so a mobile app without a server of its own has
nothing to sync through. Overcast and Pocket Casts sync through their own
servers, and AntennaPod through Nextcloud.

**What it takes.** One store per service in like-minded with the four
functions of the store contract (list, fetch, put with a version
precondition, delete), taking a token function, with the provider's
revision as the version; the OAuth flow stays with the app. The Dropbox
and Google Drive APIs first, CloudKit when a native client exists. A few
days each, and each needs an account to test against.

### Read only the head of a feed for previews

Difficulty 2, gain 2. Documented in a TODO in
[show.cljc:136](../../src/dk/simongray/podcast/app/show.cljc#L136).

The podroll reads the first 64 KB of each feed, but `find-feeds!`, an OPML
preview and the guid check before subscribing fetch and parse whole feeds
of megabytes for a title and a guid. Importing an OPML of 200 shows, or
listing the candidates of a search, is slow and heavy for it, against the
principle of being light on the network. The TODO lists the trade-offs:
RSS lets channel elements come after the items, the head only works for
encodings where the first item can be found before decoding, and a head
stored as a feed would look like a show without episodes.

**What it takes.** Make the head reader public under a clear name, with a
mark on its result that tells it from a feed, and use it in the fetch of
discovery's candidates, the list preview and the guid check, with a whole
fetch as the fallback when the head lacks the channel. Range requests of
RFC 9110. A day.

### Stores and senders for React Native and Capacitor

Difficulty 2, gain 2. Documented in [iOS apps](ios-apps-2026.md).

On Expo there is no IndexedDB and no File System Access, so the library
has no store there, and on Capacitor the clean path for requests is native
HTTP. The iOS report lists both as the app's first-day work: two stores
for Expo, over expo-sqlite or MMKV and over expo-file-system, and a sender
over CapacitorHttp. Each is a small adapter against a four-function
contract, but every app on those platforms writes the same one.

**What it takes.** A key-value store and a folder store in like-minded
over adapters that the app supplies, a sender over CapacitorHttp in
wary-fetch, and a sender over IPC for Electron, each with the contract
test. A day or two each, and each needs a device to test on.

### Counts of likes, reposts and replies on a comment

Difficulty 2, gain 2. Documented in a TODO in
[comments.cljc:64](../../src/dk/simongray/podcast/comments.cljc#L64).

Comment views show how many people liked, reposted or replied. The AppView
already gives the like and repost counts with each post, ActivityPub posts
carry likes and shares collections with their totals, and Nostr has kind 7
reactions and kind 6 reposts. The thread shape has no counts: only the AT
Protocol reply count is read, and only to decide whether to fetch more
([atproto.cljc:270](../../src/dk/simongray/podcast/comments/atproto.cljc#L270)).
Podverse and Castamatic show Mastodon counts, and Fountain shows reactions
and zaps.

**What it takes.** A counts map on each comment from the fields that come
for free on the AT Protocol (the post view of `app.bsky.feed.defs`) and
ActivityPub (sections 5.7 and 5.8), and for Nostr one extra question per
batch of ids for kinds 6 and 7 (NIP-18 and NIP-25), counted after
verification. A day.

### Word timing from inline WebVTT timestamps

Difficulty 2, gain 2. Documented through the `fidelity="word"` proposal in
[Podcasting and Clojure](podcasting-and-clojure-2026.md#proposals-to-follow).

Word-level transcripts in WebVTT carry a timestamp before each word inside
the cue, which the reader strips along with the tags
([transcript.cljc:165](../../src/dk/simongray/podcast/format/transcript.cljc#L165)),
so a karaoke-style highlight or a precise seek to a word is lost. JSON
transcripts with a segment per word are read. Apple Podcasts highlights
words as they are spoken, and Whisper and Deepgram write inline
timestamps. The namespace proposal covers the feed attribute, not the
reading.

**What it takes.** Read the timestamps inside a cue (WebVTT section
3.3.3) into a vector of words with their start times per segment, and let
`emit-vtt` write them back. A day.

### Parsing server logs into request maps

Difficulty 2, gain 2. Documented in [How to build a podcast app](../building-an-app.md#count-the-downloads-of-a-show)
as the host's job.

Counting downloads takes request maps, and the guide tells the host to
turn each log line into one. Every host reads the combined log format of
nginx and Apache, or the W3C extended logs of CloudFront and S3, with
their own date formats, quoting and X-Forwarded-For, of which IAB v2.3
wants the first address. A reader for the common formats would make the
counting usable without a parser of one's own. The CDN formats are vendor
facts that change, which is why the guide left them out.

**What it takes.** A pure `analytics.logs` with a parser for the combined
format and the W3C extended format, giving the request maps that
`downloads` takes with the time as an instant, and a diagnostic for a
line that doesn't read, with tests on real lines. A day.

### A bsky.app link as the root of a thread

Difficulty 1, gain 2. Documented in the same TODO in
[comments.cljc:64](../../src/dk/simongray/podcast/comments.cljc#L64).

Feeds and listeners paste the web form of a post,
`https://bsky.app/profile/handle/post/rkey`, rather than an at URI. The AT
Protocol reader takes only at URIs
([atproto.cljc:41](../../src/dk/simongray/podcast/comments/atproto.cljc#L41)),
so the thread of such a `socialInteract` is skipped, and a feed with a
bsky.app link gets no comments today. The reverse is also missing: a
Bluesky comment has no URL, so an app can't offer "open on Bluesky"
without composing it.

**What it takes.** In `comments.atproto`, a reader that gives the
authority and the record key from a bsky.app URL, one request to
`com.atproto.identity.resolveHandle` when the authority is a handle, and a
URL on each comment from the author's handle and the record key, with an
option for other front ends. An hour or two.


## Nice-to-haves

Everything else that the survey found, by area, with the gain first and
the difficulty second. An item marked "documented" is already named in a
doc, a TODO or a working note. The first few of each area are the ones
that most listener apps have, and that an app can do today in a few lines
over what the library gives; they're nice-to-haves because nothing stops
an app without them, not because they're rare.

### Reading feeds

- **Timestamps in show notes as seek links** (difficulty 2, gain 3). A :timestamp-fn option of episode/view that wraps a 12:34 in the notes in Hiccup of the app's choosing, after sanitising.
- **Duplicate items with one guid listed twice** (difficulty 1, gain 2). Dedupe by item guid, keeping the first, in resolve/episodes, criteria/episodes and the search documents, with a diagnostic from parse.
- **Keep the artwork of a subscription in the profile** (difficulty 1, gain 2). An :image in the subscription details, written on subscribe and relocate, so a device that gets the list by sync shows artwork before its first refresh.
- **Treat an unchanged body as a 304 when the server gives no validators** (difficulty 1, gain 2). Route a fresh feed equal to the stored one, or with an empty diff, through the unchanged path so a server without ETag or Last-Modified doesn't cost a diff and a reindex per check.
- **A gone state for a feed that answers 410 or fails for long** (difficulty 1, gain 2). A :gone flag on the entry after a 410 or a run of failures, skipped by due and reported once with a reason the UI can show.
- **The feed of a single added episode is refreshed and kept whole for ever** (difficulty 2, gain 2). No next-check, or only the slim part, for the feed of a remote item alone, and a tidy that drops played remote items out of the queue and playlists with their feed.
- **Verify a declared move before following it** (difficulty 2, gain 2). Fetch the itunes:new-feed-url in the same refresh and apply the move only when it answers with a feed of the same guid, else keep the old URL and report a warning.
- **Play a live item through the library** (difficulty 2, gain 2). Let item-of, read-files! and step address a podcast:liveItem, with the enclosure or the first contentLink and no progress for a stream.
- **The sortOrder proposal (#747): an episode order named by the creator** (difficulty 1, gain 1, documented). A registry entry and a branch in resolve/episodes once the tag is agreed.
- **The AI disclosure proposals (#735, #753, #669)** (difficulty 1, gain 1, documented). Registry entries and a fallback in ai-content? when a tag is adopted; the txt purpose ai-content is read already.
- **Plain-text titles: markup and double-escaped entities in show and episode titles** (difficulty 1, gain 1). A quirk that strips escaped tags and decodes a second level of entities in a channel or item title, reported as a quirk, and an Apple-level warning in the validator.
- **Relative URLs in enclosures, images and links** (difficulty 1, gain 1). Make :url-typed values absolute against xml:base or the :url that rss/parse takes, with a quirk diagnostic.
- **Items without a playable enclosure are listed as episodes** (difficulty 1, gain 1). A :media criterion and an option of show/view and library/episodes that leave out items without an enclosure, built on resolve/enclosure.
- **A private feed's credentials for read-files! and the download plan** (difficulty 1, gain 1). Look up (credentials-of url) in read-files! and pass it as :credentials to the reads of the same origin.
- **dc:date as the date of an item without pubDate** (difficulty 1, gain 1). A registry entry for dc:date and a fallback in published and the schedule's dates.
- **An accessor for podcast:trailer** (difficulty 1, gain 1). A trailers accessor with the season in show-properties and a row in the show view, over the already parsed :podcast/trailers.
- **skipHours and skipDays in the schedule** (difficulty 1, gain 1). Push a next check that falls in a skipped hour or day to the next allowed one.
- **A retry on a transient network error within a refresh** (difficulty 1, gain 1). One immediate retry of a feed whose fetch threw without a status, behind a :retries option.
- **Tell when :max-pages cut a paged feed** (difficulty 1, gain 1, documented). A :fetch/pages-cut info diagnostic with the next URL when the last page read still names one.
- **The liveSchedule proposal (#683): times of recurring live shows** (difficulty 2, gain 1, documented). A registry entry and a next-live over util.time.rrule, like next-episode.
- **The remaining open proposals: socialAccount (#784), fidelity=word (#600), timeOffset (#733), a comments file (#680), metaBoost (#676), private (#627), podcast:follow (#609)** (difficulty 2, gain 1, documented). A registry entry and an accessor each when adopted, with an offset option in chapters/at and transcript/at for timeOffset; the Nostr root and word-level transcripts are handled already.
- **Group alternate enclosures by rel, so companion content isn't offered as the episode (F5)** (difficulty 2, gain 1, documented). A :group key on each enclosure from its rel, a filter in resolve/enclosure and a companions accessor.
- **find-feeds! takes a podcast:guid** (difficulty 2, gain 1). Accept a bare UUID in read-input and look it up at the Podcast Index, and resolve a remote item without a feed URL the same way in pending.
- **Politeness per domain and spreading of checks** (difficulty 2, gain 1). Group the due feeds by domain with a cap on the calls in flight to each, and add jitter to the next check, as crawlers do.
- **Atom and RSS 1.0 feeds** (difficulty 3, gain 1, documented). A reader that maps Atom entries with link rel=enclosure, and RSS 1.0 items, onto the feed model, reported as a repair.

### Feeds for hosts and validators

- **A podping/notify! that sends the request and reads the answer** (difficulty 1, gain 2). A notify! around notify-request with :send and the error shape of the library, as websub/publish! has.
- **The checker doesn't judge the feed document's own delivery** (difficulty 1, gain 2). Rules on the facts the probe already has: a feed served with a non-XML media type, a feed above a size limit, and later a slow answer once the probe times itself.
- **Language, email and media-type values aren't validated** (difficulty 1, gain 2). Shape checks for :language, :email and :mime in typed-diagnostics, and a fix of doc/feed-model.md:36, which claims they exist.
- **Defaults for a producer writing a feed from plain data** (difficulty 2, gain 2). A publish/with-defaults that fills the self link, lastBuildDate, generator, the podcast:guid, item guids and the media type from the extension, and an optional fill from the files.
- **No requirements of Spotify or YouTube in the validator and the checker** (difficulty 2, gain 2). A :spotify and a :youtube entry in the requirement tables of validate and the checker, with dated limits on artwork, media types and text lengths.
- **Importing a feed from another host, and moving one away** (difficulty 2, gain 2). A publish/import that refuses a locked feed, keeps the guids and rewrites media URLs, a publish/moved that sets both new-feed-url elements, and a doc section with the 301 step.
- **A Ring response for serving a feed: ETag, Last-Modified, Cache-Control, 304 and the Link headers** (difficulty 2, gain 2). A publish/response that gives a Ring response for a feed with ETag, Last-Modified, Content-Type, Cache-Control, the WebSub Link header and a 304 on a conditional request.
- **A rule for sizes that make no sense in the feed checker** (difficulty 1, gain 1, documented). One rule comparing a declared length with duration times bitrate within a factor, with the bitrate added to what alternate-files declares and a threshold in the requirements.
- **A feed with no items passes validation** (difficulty 1, gain 1). A :validate/no-items diagnostic, a warning by default and an error under :apple.
- **The validator can't be told the feed's URL, so a self link that names another URL passes** (difficulty 1, gain 1). A :url option of validate with a self-link mismatch warning through guid/normal-url, and the same rule in feed-verdicts over the answered URL.
- **A permalink guid that isn't a URL passes validation** (difficulty 1, gain 1). A structural rule on :rss/guid that warns when isPermaLink is true or absent and the content isn't an absolute URL.
- **Rewriting media URLs silently changes the identity of items without a guid** (difficulty 1, gain 1). A report of the items without a guid from feed.publish, raised by for-subscriber and op3/with-prefix, and a sentence in the serving section.
- **A media file served without a size gets no diagnostic** (difficulty 1, gain 1). A :check/no-length info rule when neither the HEAD answer nor the range answer gives a size.
- **Writing podcast:integrity for a host** (difficulty 1, gain 1). An integrity/sri-of over bytes or an InputStream that gives the sha384 SRI value for an alternate enclosure.
- **Conditional rules in the validator: episode numbers of serial shows and duplicate season/episode numbers** (difficulty 1, gain 1). A feed-level rule for itunes:episode on every item of a serial show under :apple, a warning for a season and episode pair two items share, and a structural rule for a third category level.
- **Reports over the downloads: by day, episode, app, device and listener** (difficulty 1, gain 1). An analytics/report with the IAB breakdowns and unique listeners per group over the rows that downloads gives.
- **Fetching the OPAWG list of user agents** (difficulty 1, gain 1, documented). A fetch-user-agents! that gets the six files of a pinned commit and gives user-agents.
- **Writing HTML transcripts** (difficulty 1, gain 1). An emit-html over the segment shape with the cite, time and p markup that parse-html reads.
- **An ownership check by podcast:txt or itunes:applepodcastsverify** (difficulty 1, gain 1). A verified? of a feed and a token over resolve/txt and :itunes/applepodcastsverify.
- **Reading and checking the token of a private feed in a request** (difficulty 1, gain 1, documented). A publish/subscriber-token that reads the token of a Ring request as the inverse of :with-token, compared with bytes/same?.
- **Text length limits of the directories** (difficulty 1, gain 1). Length predicates in the requirement vectors for Apple's 4000-byte description and Spotify's title and description limits, dated.
- **Date sanity in the validator: future pubDates and a stale lastBuildDate** (difficulty 1, gain 1). An :at option of validate and rules for a pubDate in the future and a lastBuildDate before the newest item.
- **The HTML of descriptions isn't checked against what apps render** (difficulty 2, gain 1). A rule over html-pieces that reports tags outside Apple's dated list at info level and script, iframe and style at warning.
- **Checking the other URLs that a feed names: link, funding, person, contentLink** (difficulty 2, gain 1). A :kind :link in check/files behind a :links? option, with a HEAD-only probe and a light verdict.
- **Counting downloads incrementally over a large log** (difficulty 3, gain 1). A step over an accumulator of open windows that emits closed downloads, with downloads kept as the convenience over it.
- **Writing podpings to Hive directly, without podping.cloud** (difficulty 3, gain 1). Hive transaction serialization, ECDSA over secp256k1 with a recovery id and a broadcast call, so a host with its own Hive account can ping without the gateway.
- **Line and column numbers in the diagnostics of a validator** (difficulty 3, gain 1). Positions as metadata on the Hiccup of qname-hiccup's parsers, carried onto the model's paths and a :line on diagnostics.
- **Count HLS downloads by segment requests (F6)** (difficulty 3, gain 1, documented). Group segment requests by playlist and listener and count a download when they cover a minute of content, per IAB v2.3 section 5.5.2.

### Media files, companion files and downloads

- **Resume a partial download** (difficulty 2, gain 3). A :partial and a failed state of a file entry that plan lists again, with a resume request of Range and If-Range and a reader of the 206, 200 and 416 answers.
- **Delete the files of a show the listener unsubscribed from** (difficulty 1, gain 2). An :unsubscribed reason in the plan's deletes for files whose show has no live subscription and whose episode isn't a remote item, behind a key of default-rules.
- **SRT formatting tags are kept in the cue text** (difficulty 1, gain 2). Apply strip-tags and decode-entities in the SRT cue as vtt-cue does, with a diagnostic counting the cues that had tags.
- **The size and audio offset of a media file aren't in the reading** (difficulty 1, gain 2). Assoc :bytes from the view's total and :audio-start (the header size the IAB threshold needs) in media/read, and pass :bytes through read-files! so the plan can correct a feed's length.
- **Read media files and images from a local file or Blob** (difficulty 1, gain 2). A public view of whole bytes and a file fetcher per platform (FileChannel, Blob.slice) as the :fetch of media/read! and image/read!.
- **File name and extension for a downloaded file** (difficulty 1, gain 2). A public table of media types, formats and extensions in format.media, and a downloads/file-name that cleans a title to a portable name.
- **Bitrate, sample rate and channels of a media file** (difficulty 2, gain 2). Keep the bitrate, sample rate, channels and VBR flag that the MP3 reader decodes, and read the sample entry and the size of mdat for MP4.
- **The tags of a media file as one map** (difficulty 2, gain 2). A tags function that maps the ID3 frames and the iTunes ilst items to named keys, so an upload or a local file gives a title, artist, date and description.
- **Download rules across all shows** (difficulty 2, gain 2). A :filters source in the download rules that selects over all feeds through the profile's saved filters, with a limit, placed after the queue.
- **Overcast's extended OPML: the subscribed flag and episode progress** (difficulty 2, gain 2). Skip outlines whose subscribed is 0 behind an option, and read the podcast-episode children into the progress and played marks by enclosure URL, with the inverse in to-opml.
- **Local files as episodes: a show that no feed serves** (difficulty 3, gain 2). A local show built from the tags of files under a guid of its own, item ids for files, a subscription without a URL that the schedule skips, and an episode view that reads the file.
- **Write ID3v2 tags, artwork and chapters** (difficulty 3, gain 2). An id3/emit that gives the bytes of an ID3v2.4 or 2.3 tag with text frames, APIC and CHAP/CTOC chapters, and a media/with-tags that puts it ahead of the audio.
- **Chapter images, links and end times from MP4 files** (difficulty 3, gain 2). Read the href atoms after a chapter's text sample and the image track that the chap reference lists, so M4A chapters carry :img and :url as ID3 chapters do.
- **Dominant colour of artwork** (difficulty 4, gain 2). A small palette of the artwork from a downscaled decode, which needs a PNG and JPEG decoder in cljc or platform decoders behind one function.
- **ID3v1 tag at the end of a file** (difficulty 1, gain 1). Read the last 128 bytes when they start with TAG and subtract them from the audio size.
- **The remaining OPML 2.0 head elements** (difficulty 1, gain 1). Five more head elements (expansionState, vertScrollState, the window elements) read and written for a faithful round trip.
- **Verify integrity from bytes on hand** (difficulty 1, gain 1). Publish the check of an integrity map against bytes or a digest, an incremental digest over chunks on the JVM, and keep :integrity in the plan entry.
- **Companion files in the download plan** (difficulty 2, gain 1). An :also list of the chapters, transcript and artwork URLs on each download entry, and the same ids under :delete.
- **Sizes of AVIF, HEIC, SVG and BMP images** (difficulty 2, gain 1, documented). The ispe box of HEIF and AVIF, the DIB header of BMP and the root attributes of SVG, so image/read gives a size for them.
- **Other caption formats: TTML, SBV and LRC** (difficulty 2, gain 1). A TTML reader over qname-hiccup and line readers for SBV and LRC.
- **HLS subtitle renditions as a transcript source** (difficulty 2, gain 1). Fetch the subtitles rendition of a stream and join its WebVTT segments into one transcript, offset by X-TIMESTAMP-MAP.
- **FLAC, WAV and Ogg Vorbis readers** (difficulty 2, gain 1). STREAMINFO, the RIFF fmt and data chunks, and the Vorbis headers over the Ogg walker of the Opus reader.
- **WebM and DASH readers for video facts** (difficulty 3, gain 1, documented). An EBML reader for Segment Info and Tracks, and an MPD reader like format.hls.
- **Offline HLS: segments, init sections, keys and subtitles** (difficulty 4, gain 1, documented). Keep the segment list with URIs, byte ranges, init map and key per segment, and give a plan of the files of one variant.
- **Write MP4/M4A metadata and chapters** (difficulty 4, gain 1). Build a new moov with ilst items and a chapter track and rewrite the stco or co64 offsets.

### The profile, the player, the library and sync

- **Criteria that select by show, folder, tag, media kind, download state and queue** (difficulty 2, gain 3). New criteria (:shows, :folder, :tag, :video?, :queued?, :downloaded?) and a :feed-guids option of library/episodes, so a saved filter can be restricted to shows and a show page needn't scan every feed.
- **A continuous-play preference, and the next episode of the show when the queue is empty** (difficulty 1, gain 2). A :continuous? or :autoplay preference read by ended, and a step in app.library that picks the next episode of the show when the queue runs out, with the sleep timer still winning.
- **Where new episodes join the queue, and how many** (difficulty 1, gain 2). Let :auto-queue? take :front, :next or :back, with a cap on how many episodes of one fetch join.
- **Listener defaults for new subscriptions** (difficulty 1, gain 2). A preference :subscription-defaults merged between default-settings and a show's own settings.
- **Rewind after a pause when resuming** (difficulty 1, gain 2). A :rewind option of resume-position with a table of pause length to seconds, from the entry's :reported-at.
- **Bulk operations: mark all played, dismiss the inbox, clear the queue** (difficulty 1, gain 2). Collection arities of mark-played, mark-unplayed, dismiss, dequeue and enqueue under one stamp, and a mark-show-played in app.library.
- **A custom order of the subscriptions in the profile** (difficulty 2, gain 2). A ranked collection of feed guids with moves, like the queue, and a profile/shows that orders by it with the title as the fallback.
- **Criteria on which new episodes join the queue or the inbox** (difficulty 2, gain 2). A setting holding a :where for auto-queue and the inbox, a predicate option of profile.queue/refresh, and app.library building it with criteria/where over the fresh feed.
- **Per-show counts for the shows screen** (difficulty 2, gain 2). A library/shows summary with counts by status, inbox and queue and the newest date per subscription, in one pass with the profile's entries grouped by feed first.
- **A per-show episode order and a custom title** (difficulty 2, gain 2). Settings :order and :custom-title honoured by library/show, library/episodes and to-opml, with the title surviving relocate.
- **Remove an episode from the history** (difficulty 2, gain 2). A forget that tombstones the episode's entry and played mark, honoured by history, the status criterion, the gpodder push and compaction.
- **An archived state for episodes** (difficulty 2, gain 2). A synced :archived set with archive and unarchive, a criterion that lists and the download rules leave out by default, and a per-show auto-archive setting.
- **Bounded growth of the profile: old progress and played marks** (difficulty 3, gain 2). A per-show played watermark and an archive horizon for old episode entries, so the synced document stops growing by two entries per played episode and being put whole at every pause.
- **Listening statistics summaries, e.g. a year in review** (difficulty 1, gain 1, documented). A profile/statistics over listened and played: totals per year, top shows, episodes finished.
- **The played threshold and the listening gap as synced preferences** (difficulty 1, gain 1). Keys :played-threshold and :listening-gap in default-preferences, read by step when the options give none.
- **Notes on bookmarks** (difficulty 1, gain 1). A :note key kept by bookmark and clip and read back.
- **Named flags for the audio effects of a player** (difficulty 1, gain 1). Named :trim-silence? and :volume-boost? keys in the settings and preferences, so apps that share a profile agree, with the processing left to the app.
- **Pause the updates of a show** (difficulty 1, gain 1). A synced :paused? setting that library/refresh! and refresh-lists honour.
- **Change the passphrase of an encrypted store** (difficulty 2, gain 1). A rekey! in like-minded's encrypted store that lists, fetches with the old passphrase and puts with the new under a version precondition.
- **Follow a list feed of shows (podcast:medium podcastL) as an OPML list is followed** (difficulty 2, gain 1). Let fetch-list! and refresh-list take a list feed whose remote items name feeds, mapping each to the outlines that refresh-list compares.
- **Podping and WebSub in the self-hosted server** (difficulty 2, gain 1). A Podping poll in the server's step with feeds/affected, and a WebSub callback route with renewals when the server has a public name.
- **Skipping chapters that the listener deselected** (difficulty 2, gain 1). A per-episode set of skipped chapters and a check on the :progress event that seeks past one.
- **Statistics beyond seconds per show and month** (difficulty 2, gain 1). Count played episodes per month and seconds saved from the rate in force, beside the seconds heard.
- **Linking a device to the account's others on gpodder.net (sync-devices)** (difficulty 2, gain 1, documented). A :sync-devices call in gpodder.api and a step after register! that links the new device with those the server lists, behind an option.
- **The gpodder server's sessions, arrival times and devices across a restart** (difficulty 2, gain 1, documented). A :store option of the gpodder handler that saves and restores its book as a document.
- **The play position tied to the file it was played in (F7)** (difficulty 2, gain 1, documented). A :file key on the episode entry written by the :progress event and read by resume-position.
- **Syncing the credentials of private feeds** (difficulty 2, gain 1, documented). An optional credentials document written only through the encrypted store and read into :credentials-of on each device.
- **Stemming in search, chosen by the feed's language** (difficulty 3, gain 1, documented). Snowball English and Danish as an analysis option of drop-in-search keyed by the feed's language, at index and query time.
- **A list feed as a subscription** (difficulty 3, gain 1). Treat the remote items of a subscribed list feed as remote items of the profile, so pending fetches their feeds and the show lists them in the list's order.
- **Search quality left out by design: dictionary segmentation for CJK, stop words, early termination, offsets in the index** (difficulty 3, gain 1, documented). Each a separate analysis or scoring option in drop-in-search, left out with reasons that still hold at a library's size.
- **The rest of the gpodder API: favourites, settings, updates, the Simple API, download and delete actions** (difficulty 3, gain 1, documented). Routes and calls for the favourites, settings and updates APIs, the Simple API over to-opml, and a mapping of the download and delete actions.
- **A JSON form of the sync documents** (difficulty 3, gain 1, documented). A codec for like-minded that encodes vector ids as strings and instants as ISO 8601, with a round-trip test and a schema for other languages.
- **The Open Podcast API** (difficulty 4, gain 1, documented). A backend and a Ring handler in the shape of sync.gpodder once the Open Podcast API's episode spec settles, which is also the standard shape of an HTTP API over a library for thin clients.

### Directories, links, updates, comments and payments

- **Directory ids for a show known only by its feed URL** (difficulty 2, gain 3). A directory/identify! that asks the Podcast Index by feed URL or guid, or Apple by title, for the Apple and index ids, cached in the feed entry so a subscribed show gets all 47 share links.
- **The batch lookups of the Podcast Index go out as GET and cannot work** (difficulty 1, gain 2). A :method and :body per path in podcastindex/request for the two POST batch calls, a reader of their answers, and one batch request from fetch-linked-shows! and fetch-remote-values!.
- **The expiry and timestamp of a BOLT 11 invoice aren't read** (difficulty 1, gain 2). An expires-at from the timestamp and the tagged field x (default 3600) and an expired? predicate, surfaced on lnaddress/invoice and nwc/answered.
- **LUD-18 payer data and LUD-21 payment verification for Lightning addresses** (difficulty 1, gain 2). Read payerData into the terms, a :payer option on invoice-request, and a verify! that fetches the kept :verify URL and checks the preimage with bolt11/proves?.
- **Live items in the app layer: views, lists and the next check** (difficulty 2, gain 2). Bring the next check forward to the start and end of a pending live item, keep the :reason and :medium of a Podping in feeds/affected, and list live items across feeds.
- **Posting a comment or a reply on Nostr** (difficulty 2, gain 2). Functions comment-event, reply-event, delete-event and a reaction in comments.nostr with the NIP-22, NIP-10, NIP-73 and NIP-09 tags, and a post! over the thread's relays, in the kind 1 form Fountain's reader threads as well.
- **Signing with the listener's Nostr key: NIP-07 and NIP-46 signers, and new keys** (difficulty 2, gain 2). A signer map with :sign-fn and :public-key-fn, a local key with nsec import behind a secret, a NIP-07 signer over window.nostr and a NIP-46 client over nostr/send! with NIP-44.
- **New replies since the last read, and a watch on a thread** (difficulty 2, gain 2). A pure comments/changes over two threads and a :since option for Nostr and the Mastodon API; a long-lived nostr/subscribe! is a separate, larger change.
- **Checking the value block of a feed for a host or validator** (difficulty 2, gain 2). A pure value/problems (splits, fees, overlaps, the form of node keys and Lightning addresses) and a check-recipients! that fetches each address's LNURL terms and keysend node in the shape of feed.check's report.
- **Paying all recipients of a boost in one call** (difficulty 2, gain 2). A value/pay! with a :pay-fn per method over value/payments and lnaddress/request-invoice!, giving a report per recipient of paid with proof, failed or skipped.
- **A record of the listener's payments in the profile** (difficulty 2, gain 2). A profile.payments of stamped last-writer-wins entries per payment id with the state and the proof, so a sent but unproven payment isn't sent again after a sync.
- **Episode, person and recent calls in the unified directory** (difficulty 2, gain 2). Add :episodes, :by-person, :recent and :live to directory/calls with a request and response per source, Apple's podcastEpisode entity and the Apple episode id in links.
- **Spotify, Podchaser, Listen Notes and Taddy as directories** (difficulty 3, gain 2). A sources entry each with request, response mapping and credentials, a client-credentials token step for Spotify and a :spotify-id form in links.
- **Streaming payments: sats per minute as a ledger over playback** (difficulty 3, gain 2). A pure value.stream step that turns progress events into minutes owed per recipient, honours time splits, the speed and a wallet minimum, and remembers which minutes were paid.
- **WebSub subscriptions kept with the feeds and renewed** (difficulty 3, gain 2). Per-feed lease, secret and callback in app.feeds, subscribe and renewal requests from due, and a delivered step that merges a pushed body through the same parse as fetch!.
- **Relay information of NIP-11 before asking a relay** (difficulty 1, gain 1). A request and reader for a relay's information document, kept per relay, to skip relays that will refuse and size batches to their limits.
- **A time on the links to an episode in other apps** (difficulty 1, gain 1). A :time option of links/links that adds each app's form of a start time to its episode link.
- **NIP-05 verification of Nostr authors** (difficulty 1, gain 1, documented). A request and reader for /.well-known/nostr.json and a verify! that gives :verified? and the relays it lists, cached in :authors.
- **NWC: list_transactions, multi_pay_keysend, notifications and the hold-invoice and BOLT 12 extensions** (difficulty 2, gain 1, documented). One command function each for list_transactions and the multi_pay commands, a mode of ask! that collects one response per id, and a standing subscription for notifications, which nostr/send! lacks.
- **Zap receipts on comments and episodes** (difficulty 2, gain 1, documented). One more question per batch for kind 9735 and a :zaps vector per comment and root with the amount from bolt11 and the sender from the kind 9734 request, verified per NIP-57 appendix F.
- **Sending a zap from the app** (difficulty 2, gain 1). Read allowsNostr and nostrPubkey into the LNURL terms, a kind 9734 zap request for a signer, and a :nostr option on invoice-request.
- **Relay AUTH (NIP-42)** (difficulty 2, gain 1, documented). Answer a relay's AUTH challenge with a signed kind 22242 event in the exchange of nostr/send! when a :signer is given, and resend the REQ.
- **The outbox model: the relays of an author (NIP-65)** (difficulty 2, gain 1). Ask for the kind 10002 relay lists of the authors read and send the next replies question to their write relays too, capped and public only.
- **A Podping stream over WebSocket instead of polling Hive** (difficulty 2, gain 1). A podping/stream! over a generic WebSocket client, if a public push socket with a known message format exists.
- **QR codes for a feed or a link** (difficulty 2, gain 1). A cross-platform util.qr in byte mode with level M, giving a matrix and SVG Hiccup, unless a dependency is the answer.
- **Podping consumer in ClojureScript** (difficulty 2, gain 1, documented). Rewrite read-blocks! and poll! with async/iteration and async/then so a Node server polls Podping with the same code as the JVM.
- **Constant-time secp256k1 signing** (difficulty 3, gain 1, documented). A fixed-window or ladder multiplication with complete formulas and nonce blinding, best effort since BigInt isn't constant time.
- **Nostr live chat for podcast:chat (NIP-53)** (difficulty 3, gain 1). A chat reader for the kind 1311 messages of a kind 30311 live event, with a live subscription and posting, while IRC, XMPP and Matrix stay links.
- **Signed requests to ActivityPub servers that require them** (difficulty 3, gain 1, documented). An actor document, RSA-SHA256 on the JVM and WebCrypto in the browser, and a :send wrapper that adds Date, Digest and Signature, for servers only.
- **Receiving boosts for a host: the node and wallet APIs** (difficulty 3, gain 1, documented). The list_transactions command with its TLV records and the payment_received notification in value.nwc, and a paged reader of received keysends over any node that applies parse-boostagram and as-received.
- **Comment threads over Matrix and Hive for socialInteract** (difficulty 4, gain 1, documented). A reader per protocol with request, response and the common comment shape; Matrix needs a guest login and event relations, Hive a public node.

### Platforms, the server, the API and the docs

- **New-episode notification planning with a per-show setting** (difficulty 1, gain 3). A :notify? setting and a global preference under one name, and a library/notifications that gives the new episodes of such shows after a refresh as small views grouped by show.
- **When to refresh again: library/next-refresh** (difficulty 1, gain 3). A next-refresh over the earliest :next-check of the feeds and the lists, with pending feeds due now, mirroring next-sync.
- **A player view for the media element and the Media Session** (difficulty 1, gain 3). A library/playback of a library and an id that composes the chosen enclosure or local file, the resume position, the rate, the title, the show, the artwork and the chapters.
- **A refresh with a time budget and progress reports** (difficulty 2, gain 3). A :max-seconds deadline checked between the groups of feeds/refresh! that leaves the rest due, a :max-feeds, and an :on-feed-fn called with each result.
- **A string form of episode ids for routes, keys and native bridges** (difficulty 1, gain 2). Two functions in feed.guid, id->string and string->id, that round-trip guids with slashes, spaces and non-ASCII.
- **restore! takes a saved profile without checking it** (difficulty 1, gain 2). Run profile/problem on the saved profile in with-saved, keep the fresh one on a problem and report :library/malformed-profile.
- **The headers of the Podcast Index and OP3 through the proxy** (difficulty 1, gain 2). Forward x-auth-key and x-auth-date by default and document authorization for the index and OP3, or sign for named services on the server, with the docs saying which fits a private and a public deployment.
- **A summary of each refresh for the UI and logs** (difficulty 1, gain 2). Counts of :checked, :unchanged, :changed and :failed and the seconds taken in :refresh.
- **Cancelling a refresh or a request in flight** (difficulty 2, gain 2). A :signal taken by send! (AbortSignal in JavaScript, a flag on the JVM) and checked by feeds/refresh! between groups, leaving the unfetched feeds due.
- **Test support for apps built on the library** (difficulty 2, gain 2). A shipped testing namespace with a sender over a map of URL to response that honours If-None-Match, a fixture feed, and a doc section on testing with store/memory and :now-fn.
- **A cache for companion files in read-files!** (difficulty 2, gain 2). A :cache option that is a like-minded store keyed by URL, read first and refreshed with If-None-Match.
- **A background story for browser apps: refresh and indexing in a web worker** (difficulty 2, gain 2). A documented pattern in which a worker restores the library from the shared IndexedDB store, refreshes, saves and posts the summary while the page restores and merges, with a worker build of the inspector's player as the example and a Web Lock for one writer.
- **Localised category names and translatable diagnostics** (difficulty 3, gain 2). A dated table of Apple's category names by language, as a data file or doc table under the rule against volatile vendor facts in code.
- **A full reference client of the podcast server** (difficulty 4, gain 2). A second shadow-cljs build with Replicant pages over app.library through the proxy and the WebDAV folder, used by the server's compose file.
- **Slow down password guesses at the podcast server** (difficulty 1, gain 1, documented). A map of failed attempts per address with a growing delay and a cap in the login interceptor, careful not to lock out a shared NAT.
- **Credentials of private feeds on the podcast server** (difficulty 1, gain 1, documented). A credentials file in the data folder read into :credentials-of with secret/hide, so private feeds refresh on the server.
- **A health route for the podcast server** (difficulty 1, gain 1, documented). A GET route that answers 200 without the password, let through by the login interceptor, with a healthcheck in compose.yaml and the Dockerfile.
- **Serve a listener's playlist as a list feed from the podcast server** (difficulty 1, gain 1). A GET route that calls to-list-feed with its own URL over the server's library and answers application/rss+xml behind the password.
- **A sleep timer by time as well as by episodes** (difficulty 1, gain 1). An :until instant in the sleep timer, checked by step on :progress and :ended, with the timer cleared after the stop.
- **An :offset for episode lists** (difficulty 1, gain 1). An :offset applied after the order in criteria/episodes, with the same early stop as :limit, as search has.
- **HTTPS in the podcast server itself** (difficulty 2, gain 1, documented). Jetty's SSL options behind two settings for the certificate and key, with a redirect from HTTP.
- **Writes through the proxy** (difficulty 3, gain 1, documented). Write methods for a set of domains that the operator names, bodies within the size limit, Authorization to those domains only, Sec-Fetch-Site same-origin and the domain check on every redirect.
- **A ClojureDart branch for native iOS and Android apps** (difficulty 5, gain 1, documented). A :cljd branch through about 250 reader conditionals in this library and its dependencies plus Dart adapters, blocked by two bugs in ClojureDart's core.


## Method

The survey ran on 11 October 2026 as a workflow of 34 agents over the
checkout at 53ef0b1 and the seven sibling libraries. Seven surveyors each
took one broad area (reading feeds; feeds for hosts; media and companion
files; the profile, the player, the library and sync; directories,
updates, comments and payments; platforms and the API; and an audit of
the gaps already documented) and listed every gap they could confirm in
the code or at the REPL. Two verifiers then went over each area's list
on their own: a skeptic who tried to prove each gap already built or
already documented, and a judge who ruled need or nice-to-have against
the feature lists of the mainstream apps, hosts and validators and scored
it. Writers drafted the needs, a critic looked for what the survey had
missed in two more rounds, and a calibrator put every surviving item on
one scale and merged the duplicates. The ten needs were then checked in
the code once more by hand.

In numbers: 259 candidates were found, 3 of them turned out to be built,
69 were duplicates or outside the scope on purpose, and 187 remain in
this report: 10 needs and 177 nice-to-haves, 51 of which were already
documented somewhere. Out of scope by the principles, and so not listed:
audio processing, machine learning, platform features such as CarPlay or
widgets, services with accounts, the services of hosts such as ad
insertion, the download and the playback themselves, and YouTube as a
media source. Three earlier decisions were also left alone: no LUD-06
description-hash check, a strict OPML parser, and the effects of
`library/step` as they are.
