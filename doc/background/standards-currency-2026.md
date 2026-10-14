# Standards currency, October 2026

This report checks whether podcast-clj still matches the standards and the
services it implements, as of 11 October 2026. It's frozen as of that date
and won't be updated. The library's specs and service facts were last
checked between 20 September and 10 October 2026, and this report re-reads
each live source and says what moved. Every source was read on 11 October
2026, with the URL in [Sources](#sources). The state of podcast-clj was
checked in its code on the same day, at commit 911059f.

Little has drifted. The podcast namespace, PSP-1, Apple's requirements and
categories, IAB v2.3, the Lightning and Nostr specs, and the forms of the
app links are as the library has them. Six things moved:

- A "Phase 9" proposal (#789) opened in the namespace on 28 September, and
  a `podcast:socialAccount` proposal (#784) on 18 September.
- The OPAWG list of user agents gained four entries since the commit the
  test fixtures pin.
- The operators of gpodder.net announced on 6 September that the service
  is reduced to sync, that its search index has been stale since 2023, and
  that accounts and history may be cut.
- OP3 now redirects two kinds of URL that `op3/accepts?` refuses.
- One of the three default Nostr relays no longer answers with the note
  it had on 29 September.
- The RFC that `feed.guid` cites for UUIDv5 has been obsoleted, with the
  rule renumbered.

Two pre-existing gaps surfaced on the way: PSP-1 names an enclosure type
that `feed.check.rules/formats` doesn't know, and Apple's own table of the
2019 category changes no longer exists, so a validator must take the
mapping from secondary sources.


## The podcast namespace

The registry in `feed.spec` matches the 32 tag docs: every tag, attribute,
parent and count, the mediums and list mediums, the social protocols, the
service slugs, the taxonomy and the Podcast Index categories are as the
repository has them today. The repository has had two commits since
mid-August, both the fix of the UUID in the `podcast:guid` example (PR
#794, merged 10 October). The phases are unchanged: phase 8 is open with
`podcast:image` formalised, and `podcast:follow` (#609) still carries the
labels "recommended" and "phase8". The `poster` purpose of `podcast:image`
is in the tag doc as the library reads it. The HLS type issue (#776) is
open with no pull request, and its last comment is from 17 June.

| What the library cites | Where | What the source says today | Drift | What to do, size |
|---|---|---|---|---|
| The proposals to follow | `doc/podcasting-and-clojure-2026.md`, "Proposals to follow" | A new discussion, #789 "Phase 9 - Proposal Oct 2026", opened on 28 September. It proposes several roles for one `podcast:person`, an AI disclosure on a person, a pinned episode as a `podcast:remoteItem`, a `podcast:store` modelled on funding, and a `podcast:networks` list for publishers. No comment period is set, and the last comment is from 5 October. | Yes, a new list of candidates | Nothing to build yet. The next dated report should follow #789 beside #609. No size. |
| The proposal `socialAccount` (#784) | The same list | Opened 18 September. The author opened PR #787 on 24 September, which clarifies `podcast:socialInteract` rather than adding the tag. No maintainer has answered. | New since the list was written | Nothing. No size. |
| The proposals `sortOrder` #747, `ai` #735, `aiTransparency` #753, `fidelity` #600, `timeOffset` #733, `liveSchedule` #683, the comments file #680, `metaBoost` #676, `private` #627 and `follow` #609 | The same list | No change. The last comments are from March 2026 or earlier, and none has moved to a phase. | No | Nothing |
| The proposal `disclosure` (#669) | The same list | One new question on 24 September, about a fixed list of contexts. No answer. | No | Nothing |
| A Nostr note as the root post (#774) | The same list | Labelled "formalized" and "duplicate", since `nostr` was already in socialprotocols.txt. The registry has it. | No | Nothing |


## PSP-1

The required and recommended elements of the channel and of an item in
`feed.validate/requirements` are those of PSP-1 today, and the repository
has had no commit since before June 2026. Its "Recent changes" section
lists editorial changes only.

| What the library cites | Where | What the source says today | Drift | What to do, size |
|---|---|---|---|---|
| The enclosure types of PSP-1 | The table `feed.check.rules/formats` | PSP-1 names `audio/mpeg`, `audio/m4a`, `video/m4v` and `video/mp4`. The table knows `video/x-m4v`, Apple's spelling, but not `video/m4v`, so the media reader skips a file declared with PSP-1's spelling. | No, a gap that predates September | Add `video/m4v` to the table. An hour. |


## Apple

The requirements, the artwork rules and the categories match. A Podcaster's
Guide to RSS requires the same six channel elements and three item
elements as `feed.validate/requirements`, and recommends the same ones.
Its artwork rules are the ones in `feed.check.rules/requirements`: 1400 to
3000 pixels, JPEG or PNG with the matching extension, RGB, no alpha
channel, and a server that answers HEAD with Last-Modified. The
requirements page asks for HEAD and byte ranges, which the Apple profile
raises to an error. The categories page lists 19 categories and 91
subcategories, the 110 names of `directory/apple-genres`, with the same
ids from the genre service. Every enclosure type of the guide is in
`feed.check.rules/formats`, except `application/pdf`, which isn't media.

| What the library cites | Where | What the source says today | Drift | What to do, size |
|---|---|---|---|---|
| Apple's table of the 2019 category changes | Not cited. `doc/corpus-study-2026.md` counts the old names in real feeds, and `doc/needs-2026.md` has the category check as a need. | Apple's notice of 24 July 2019, as Podnews republished it, says only that old categories keep working "until further notice". Neither it nor Apple's current pages has a mapping. The fullest mapping is Blubrry's article of 17 July 2019, which reproduces Apple's email: six renamed categories, five renamed subcategories, and the dropped subcategories with the category each falls back to. | Not drift, but the source is gone | Keep the mapping as a dated table in `feed.spec`, beside the category list that the needs report puts there, as a default that an option replaces. Take it from Blubrry and Podnews, and let the category check of the needs report map an old name to the new one. A few hours for the table, a day with the check. |
| The categories page | `directory/apple-genres` | The page shows "American Football" and "Football (Soccer)" as display names, with `Football` and `Soccer` as the values to write. The guide's own example of several categories still writes `Health`, the name from before 2019. | No | Nothing. The old-name table above covers the example. |
| The ownership token | The registry entry `:itunes/applepodcastsverify` | The guide now lists `podcast:txt purpose="applepodcastsverify"` beside `itunes:applepodcastsverify`. The registry parses both, and the needs report has the check over both as a need. | No | Nothing |
| The transcript types Apple accepts | The set `feed.spec/transcript-types` | `text/vtt`, `application/srt` and `application/x-subrip`, with VTT preferred. All three are in the set. | No | Nothing |


## IAB and the OPAWG

The guidelines are at version 2.3, as `analytics` and
`doc/architecture.md` say. The IAB Tech Lab page lists v2.3 as released in
September 2026 and was last updated on 29 September, and the GitHub
repository, initialised on 28 September, holds the text. Section 5.5.1 is
the measurement windows, as `analytics` cites it, and the HLS rule that
`doc/video-podcasts-2026.md` cites as section 5.5.2 is in step 1.3 of that
section. The page says nothing about v3.0. The trade press, as a web search
summarised it, still puts v3.0 in 2027.

| What the library cites | Where | What the source says today | Drift | What to do, size |
|---|---|---|---|---|
| Commit 0deded5 of the OPAWG list, of 8 September | The fixture `analytics/user-agents.json` and `test/resources/README.md` | The list is at 1344b58 of 9 October. Thirteen commits since the pin add Podmix as a bot (28 September), the odtune bot (7 October), Once Upon a Pod as an app and a fetcher (8 October), and Spool as an app and a poller (9 October). | Yes, four entries | Refresh the fixture from the new commit and re-date the README. An hour. |


## The services

Apple's search, lookup and chart APIs answer as `directory` expects, and
the genre service gives the 110 names and ids of `apple-genres`. The API
of fyyd answers the lookup, the search and the hot list with the fields
that `directory` reads, and its chart still takes no category. The
categories of the Podcast Index are the 112 of categories.json, and the
repository of its API docs has had no commit since before September. The
48 app links are as Podcast Platform Links has them, with no commit since
29 September. The NIP-47 core, NWC-04, NWC-06 and NIP-44 are unchanged
since 20 September, and `value.nwc` already reads the `extensions` tag of
the info event. The download rules of Apple Podcasts, Pocket Casts and
AntennaPod are as the table in `doc/services.md` has them. The
requirements of directories are covered under PSP-1 and Apple above.

| What the library cites | Where | What the source says today | Drift | What to do, size |
|---|---|---|---|---|
| gpodder.net as a directory and a gpodder server | The entry in `directory/sources`, and the pages `doc/services.md` and `doc/sync-reference.md` | The home page has a notice of 6 September 2026. The operators can't keep running a free global service for more than 100,000 users, so gpodder.net is now a sync mechanism between devices, mainly for gPodder and AntennaPod. Podcast indexing broke in 2023, so search answers from a stale index, and browsing may go. Accounts may be archived after six months without activity, the history may be cut to the latest action of each episode on each device or to a few months, and the web interface may shrink. The search, the tag chart and the lookup still answer. | Yes | Say so in the gpodder servers table of the sync reference and beside the directory in `doc/services.md`, and note that a pull from gpodder.net may lose older actions. An hour. |
| The URLs that OP3 accepts behind its prefix | The predicate `op3/accepts?`, "as its server did in September 2026", and the OP3 section of `doc/services.md` | A prefixed URL whose domain has no dot, or whose top-level domain is `.test`, now gets a 302 to that URL. An IPv6 domain still gets a 400. The API docs have every call of `op3/calls`, plus the hits, the top apps of all shows and the recent episodes with transcripts, and no changelog. | Yes, two of the three rules | Probe again and either loosen the predicate or re-date the docstring and the page with what OP3 refuses today. An hour. |
| The answers of the default Nostr relays | The relays table in `doc/services.md`, dated 29 September | Asked today for the same episode by its NIP-73 id: relay.fountain.fm gave three notes and closed the filter for kind 1111 with "kinds not supported"; relay.damus.io, relay.primal.net and nos.lol gave none; relay.nostr.band didn't answer in ten seconds. On 29 September, relay.primal.net had one note. | Yes, one relay | Nothing to change, since the defaults still give the thread. Re-date the table when it's next touched. An hour. |
| The gpodder servers | The table "gpodder servers, as of September 2026" in `doc/sync-reference.md` | Nextcloud GPodder Sync added Nextcloud 35 and dropped 30 and 31 on 18 September. A release of oPodSync on 11 September added a web player. The only change at mygpo is the notice above. Neither gpodder2go nor PinePods has a commit since September. No commit touches an API path. | No | Nothing |
| The NWC extensions | The wallets table in `doc/services.md` | The repository has NWC-02 to 09, 12 and 321. Its merges of 11 September brought 08, client-initiated connections, and 09, a `lookup_payment` command. None of the extensions is needed for a podcast payment. | No | Nothing |
| The status paths of the Mastodon API | The table `comments.activitypub/status-paths` | Not checked. No source read today changes the URL forms of Mastodon, GoToSocial, Pleroma or Akkoma. | Unknown | Nothing |


## The other standards

The RFCs, the W3C recommendations and the fixed specs that the code cites
don't change, and the files of the living ones that it cites have had no
commit since 20 September: NIP-01, 10, 19, 21, 22, 27, 44, 47, 57 and 73,
the LUDs, BOLT 11, bLIP-10 and BIP-340. The HLS draft is still
draft-pantos-hls-rfc8216bis-22 of 1 May 2026, and the sections of RFC 8216
that `format.hls` cites are the same there. WebSub is in. The two living
WHATWG standards that the code cites by section still have those numbers:
the HTML standard's 4.6.8.1 for the link type "alternate" (updated 9
October), and the MIME Sniffing standard's 6.1 for image patterns (updated
17 July).

| What the library cites | Where | What the source says today | Drift | What to do, size |
|---|---|---|---|---|
| RFC 4122, section 4.3, for the name-based UUID of `podcast:guid` | The namespace `feed.guid` | RFC 9562 of May 2024 obsoletes RFC 4122. UUIDv5 is its section 5.5, and the algorithm is the same. The namespace's tag doc says only "UUIDv5". | A renumbered reference | Cite RFC 9562, section 5.5. An hour at most. |
| ISO/IEC 14496-12, sections 8.3.2, 8.4.3, 12.1.3 and 12.2.3 | The namespace `format.media.mp4` | Not checked. The standard is a PDF behind ISO's publicly available standards page, and its editions renumber sections. | Unknown | Check the section numbers against the 2022 edition when `format.media.mp4` is next touched. An hour. |


## What to do

The sizes are rough: an hour means a small edit with its test, and a day
means a batch.

1. Note the state of gpodder.net in the sync reference and the services
   page, and say that a pull may lose older actions. An hour.
2. Refresh the OPAWG fixture from commit 1344b58 and re-date the README.
   An hour.
3. Probe OP3 again and bring `op3/accepts?` and its page in line with what
   the server refuses today. An hour.
4. Add the 2019 category mapping as a dated table in `feed.spec`, from
   Blubrry and Podnews, and use it in the category check that the needs
   report describes. A few hours for the table, a day with the check.
5. Add `video/m4v` to `feed.check.rules/formats`. An hour.
6. Cite RFC 9562, section 5.5, in `feed.guid`. An hour at most.
7. Re-date the Nostr relays table when the page is next touched, with
   today's answers. An hour.
8. Follow discussion #789 for phase 9, and #784 for `socialAccount`.
   Nothing to build until a maintainer answers.


## Sources

All read on 11 October 2026.

The podcast namespace:

- The repository, its commits since mid-August, the 32 files of
  `docs/tags/`, `README.md`, `docs/1.0.md`, `socialprotocols.txt`,
  `serviceslugs.txt`, `taxonomy.json` and `categories.json`:
  https://github.com/Podcastindex-org/podcast-namespace
- The image tag: https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/image.md
- The guid tag: https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md
- Discussions #784, #747, #735, #753, #669, #600, #733, #683, #680, #774,
  #676, #627, #609 and #789, at
  https://github.com/Podcastindex-org/podcast-namespace/discussions/NUMBER
- Issue #776: https://github.com/Podcastindex-org/podcast-namespace/issues/776
- PR #787: https://github.com/Podcastindex-org/podcast-namespace/pull/787

PSP-1:

- https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification,
  the README and the commits

Apple:

- Podcast RSS feed requirements: https://podcasters.apple.com/support/823-podcast-requirements
- A Podcaster's Guide to RSS: https://help.apple.com/itc/podcasts_connect/#/itcb54353390
- Apple Podcasts categories: https://podcasters.apple.com/support/1691-apple-podcasts-categories
- The genre service: https://itunes.apple.com/WebObjects/MZStoreServices.woa/ws/genres?id=26
- The lookup, the search and the chart of a genre:
  https://itunes.apple.com/lookup?id=1552604868&entity=podcast,
  https://itunes.apple.com/search?term=podcasting+2.0&media=podcast&limit=1
  and https://itunes.apple.com/us/rss/toppodcasts/limit=2/genre=1318/json
- Automatic downloads: https://support.apple.com/en-us/102243
- Apple's notice of 24 July 2019, as Podnews republished it:
  https://podnews.net/press-release/apple-enhanced
- The 2019 changes, by Podnews: https://podnews.net/article/apple-changed-podcast-categories-2019
- The 2019 changes with the mapping, by Blubrry:
  https://blubrry.com/podcast-insider/2019/07/17/how-the-2019-apple-podcast-category-changes-affect-for-your-podcast/

IAB and the OPAWG:

- https://iabtechlab.com/standards/podcast-measurement-guidelines/
- https://github.com/IABTechLab/Podcast-Technical-Measurement, the README
  and `PTM-Guidelines_v2.3.md`
- The trade press on v3.0, as a web search summarised it:
  https://ppc.land/iab-tech-lab-finalises-v2-3-podcast-counting-rules-covering-video/
- The commits of https://github.com/opawg/user-agents-v2 since 8 September

The services:

- fyyd: https://api.fyyd.de/0.2/podcast?podcast_id=1,
  https://api.fyyd.de/0.2/feature/podcast/hot?count=2 and
  https://api.fyyd.de/0.2/search/podcast?title=podcasting&count=1
- gpodder.net: https://gpodder.net/, https://gpodder.net/search.json?q=podcasting+2.0,
  https://gpodder.net/api/2/tag/technology/2.json,
  https://gpodder.net/api/2/data/podcast.json?url=https://mp3s.nashownotes.com/pc20rss.xml
  and https://gpodder.net/toplist/2.json
- The Podcast Index API docs repository: https://github.com/Podcastindex-org/docs-api
- OP3: https://op3.dev/api/docs, and HEAD requests to
  https://op3.dev/e/example.test/x.mp3, https://op3.dev/e/nodot/x.mp3,
  https://op3.dev/e/[::1]/x.mp3 and https://op3.dev/e/cdn.example.com/x.mp3
- Podcast Platform Links: https://github.com/nathangathright/podcast-platform-links
- The relays, asked over WebSocket for the Fountain note of the test
  resources: wss://relay.fountain.fm, wss://relay.damus.io,
  wss://relay.primal.net, wss://nos.lol and wss://relay.nostr.band
- NWC: https://github.com/nostr-wallet-connect/nwc, the README, `04.md`
  and `06.md`
- NIP-47: https://github.com/nostr-protocol/nips/blob/master/47.md
- The commits of https://github.com/nostr-protocol/nips to 01, 10, 19,
  21, 22, 27, 44, 47, 57 and 73
- The gpodder servers: https://github.com/thrillfall/nextcloud-gpodder,
  https://github.com/kd2org/opodsync, https://github.com/oxtyped/gpodder2go,
  https://github.com/madeofpendletonwool/PinePods and
  https://github.com/gpodder/mygpo
- Pocket Casts: https://support.pocketcasts.com/knowledge-base/archiving-episodes/
- AntennaPod: https://antennapod.org/documentation/automation/automatic-downloads
  and https://antennapod.org/documentation/automation/deletion

The other standards:

- The commits of https://github.com/lnurl/luds,
  https://github.com/lightning/bolts (`11-payment-encoding.md`),
  https://github.com/lightning/blips (`blip-0010.md`) and
  https://github.com/bitcoin/bips (`bip-0340.mediawiki`)
- The HLS draft: https://datatracker.ietf.org/doc/draft-pantos-hls-rfc8216bis/
- The HTML standard: https://html.spec.whatwg.org/multipage/links.html
- The MIME Sniffing standard: https://mimesniff.spec.whatwg.org/
