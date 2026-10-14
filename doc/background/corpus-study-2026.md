# What real feeds look like, October 2026

This report measures real podcast feeds, so that the plans in [the needs
report](needs-2026.md) and [the design for identity and
storage](identity-and-storage-2026.md) rest on numbers rather than on
reasoning. It has two sources:

- **The Podcast Index database**, the public SQLite dump of 10 October
  2026, which describes 4.7 million feeds: their URL, host, last HTTP
  status, episode count, newest item, popularity and guid.
- **A sample of 5,000 feeds** drawn from it and fetched on 11 October
  2026, then parsed, validated and measured with the library itself, on
  the JVM and under Node.

The tool is `dev/study.clj`, next to the corpus tooling, and the last
section says how to repeat the study. The numbers hold for this sample
and this day; the strata below say what the sample favours.


## Summary

- The library reads real feeds: of 4,669 documents fetched, 4,644 parsed
  as they came, 3 needed a repaired ampersand, 1 needed the lenient
  parser, and the 21 that failed weren't XML feeds at all (19 HTML pages
  and 2 JSON errors served at a feed URL). Under Node the same 21 failed,
  plus the one that only jsoup reads.
- Item caps are everywhere. In the index, 31,975 feeds hold exactly 100
  items against 3,793 with 99, and 13,526 hold exactly 1,000 against 263
  with 999. In the sample, 5% of feeds sit at 100 (Podbean, Libsyn, Omny),
  and among popular feeds a quarter have 100 items or fewer. Only 6% page
  the rest out as RFC 5005 says (10% of popular feeds, nearly all
  SoundCloud and Omny).
- Most feeds derive their identity from their URL. Three of four active
  feeds in the index carry the UUIDv5 of their URL as guid, and only 25%
  of the sample declare a `podcast:guid`, of which half differ from the
  URL's. A move changes the derived guid of the rest.
- The schedule waits too long. Judged by the feed alone, as the library
  does today, 64% of feeds would wait more than a day for their next
  check, 34% more than a week and 8% more than a month; the median wait is
  3.4 days.
- Apple's list of categories is the problem, not typos. 2% of podcast
  feeds name a category Apple doesn't know, and nearly all of those are
  Apple's own names from before 2019, such as "News & Politics", or a
  double-escaped ampersand.
- Enclosures are declared, but lengths lie. A missing type is rare (16
  feeds), a length of 0 is not (6% of feeds, and 125 of the 126 Megaphone
  feeds), and 3.5% of feeds, 6% of popular ones, repeat an enclosure URL
  across items, which Apple ignores.
- The default User-Agent of wary-fetch is refused by 5% of feeds, by
  Cloudflare-fronted hosts such as Omny, Riverside, Audioboom and Castbox,
  while the library's own agent and the agents of apps pass.
- Conditional requests work for 86% of feeds, 43% of feeds name a WebSub
  hub, and the first 605 feeds of the earlier corpus, biased to Podcasting
  2.0 hosts, overstated most namespace tags by two to five times.


## The index

The dump holds 4,739,120 feeds. Its `dead` column is 0 for all and its
`duplicateOf` column empty, so the index's own view of a feed is its last
HTTP status and the date of its newest item.

| Feeds by last status | Count |
|---|---|
| 200 | 4,640,013 |
| 667, a code of the index's own | 91,315 |
| 404 | 1,626 |
| 500, 502, 503 | 2,708 |
| 304 | 1,063 |
| 403 | 718 |
| 410 | 74 |

Of the feeds with a 200, 326,348 published an item in the last month and
394,623 more in the last year, so about 721,000 feeds, 15%, are active.
Another 693,111 published within three years, 2,973,114 earlier, and
252,817 have no dated item. The popularity score is 0 for 2.24 million
feeds and 1 for 1.43 million; 166,564 feeds score 9, the top.

Among the active feeds, the hosts are Anchor (now Spotify for Creators)
with 183,343, Spreaker 104,950, Buzzsprout 66,909, RSS.com 36,331, Podbean
32,102, Libsyn 20,157, Acast 17,742, Transistor 15,597, Captivate 12,851,
Megaphone 11,545, SoundCloud 11,508, Podigee 10,464, Substack 8,595,
Riverside 15,030 over two domains, Omny 8,501, Simplecast 8,084, iVoox
8,025, Ausha 7,102 and RedCircle 6,679. The languages are English 437,000
over three tags, Spanish 50,726, German 29,347, French 26,005, Japanese
15,663, Italian 13,201, Chinese 9,313 and Portuguese 8,399. 96% of active
feeds have an Apple id.

**Item caps.** The episode counts of feeds with a 200 fall smoothly from
21 to 33, from 32,428 feeds to 16,397, and then spike at round numbers:

| Items | Feeds at exactly that count | At one less | At one more |
|---|---|---|---|
| 50 | 17,540 | 9,414 | 8,988 |
| 100 | 31,975 | 3,793 | 3,183 |
| 150 | 5,972 | 1,375 | 1,298 |
| 200 | 3,124 | 942 | 868 |
| 300 | 3,892 | 477 | 391 |
| 500 | 4,782 | 139 | 94 |
| 1,000 | 13,526 | 263 | 2 |

**Guids.** Of 20,000 random feeds with a 200, the guid in the index equals
the UUIDv5 of the feed URL for 86%, and for 75% of the active ones. Those
feeds declare no guid of their own, or declare the derived one, so a
move of the URL changes their guid. 4 of 20,000 guids aren't UUIDs. The
type is episodic for 94.6%, serial for 1.7% and unstated for 3.6%. 2% of
feed URLs are http.

**Enclosures.** Of the newest enclosures of active feeds whose URL has no
query string, 89% end in .mp3 and 9.9% in .m4a; .mp4 has 3,137, .wav 619,
.jpg 64, .ogg 62, .m4v 56 and .aac 53. 1.4% are http. A measurement
prefix fronts the newest enclosure of 6.2% of active feeds for Podtrac,
0.8% for OP3, and under 0.6% each for Podsights, Magellan, Spotify,
Claritas, Artsai and Chartable.


## The sample

Five strata, 5,000 feeds in all, drawn from the index with SQL:

| Stratum | Feeds | Drawn from |
|---|---|---|
| popular | 2,000 | the highest popularity among feeds with a 200 and an item in the last year |
| live | 2,000 | at random among the rest of those |
| stale | 400 | at random among feeds with a 200 whose newest item is older than two years |
| failing | 300 | at random among feeds whose last status isn't 200 |
| any | 300 | at random among everything else |

The 605 feeds already on disk, the 31 named corpus feeds and a sweep of
574 fetched through the index's API in September, were measured the same
way first; where they differ, this report says so, since that sample was
drawn from the recently updated, value-for-value and music feeds of the
index.

### Fetching

The feeds were fetched eight domains at a time, one request at a time per
domain with a quarter of a second between them, with a 20-second timeout
and a 32 MB cap.

| Status | Feeds |
|---|---|
| 200 | 4,406 |
| 403 | 271 |
| no answer: refused connection 172, connect timeout 40, TLS failure 30 | 246 |
| 404 | 42 |
| 500 | 17 |
| 418 | 11 |
| 410, 406, 400, 502, 204 | 7 |

The index's statuses mostly hold: of the 300 failing feeds, 220 gave no
answer and 58 answered 200. Of the popular feeds, 1,859 answered 200 and
124 refused with 403.

**User agents.** The 272 refusals went to wary-fetch's default agent,
`wary-fetch (+https://github.com/simongray/wary-fetch)`, which the study
used by calling wary-fetch directly. Fetched again as a browser, 267 of
them answered 200. Fetched again with the library's own agent,
`podcast-clj (+https://github.com/simongray/podcast-clj)`, 253 answered
200 and 15 still refused, 14 of them at one host, feed.xyzfm.space, which
refuses any agent that carries a "+URL". Probes of six refusing hosts
(Omny, Riverside, Audioboom, Castbox, xyzfm and relay.fm) showed that
`podcast-clj/0.1`, `AntennaPod/3.5.0`, Apple's agent and even
`curl/8.7.1` pass everywhere, so the hosts' bot rules object to
wary-fetch's agent as such, and xyzfm's to the URL form. The hosts are
Cloudflare-fronted: Omny 80 feeds, Riverside 64, Audioboom 20, xyzfm 14,
Castbox 12, relay.fm 4, islamhouse 4, and a few with one to three.

**Headers.** Of the feeds that answered 200: 78% gave an ETag, 50% a
Last-Modified and 86% one or the other (92% of live feeds, 82% of popular
ones); 93% were gzipped; 73% carried Cache-Control; none carried a WebSub
Link header, so hubs are found in the body only. 11% were reached
through a redirect, 18% of the popular ones, and 20 went from http to
https. The content types were application/rss+xml for 2,011, text/xml
for 1,585, application/xml for 780, text/html for 23 and text/plain for
3. The median feed is 194 KB, the third quartile 694 KB, the ninth decile
1.8 MB and the largest 29 MB; popular feeds are three times larger at
every quantile, with a median of 626 KB.

### Parsing

| Outcome on the JVM | Feeds |
|---|---|
| parsed as it came | 4,644 |
| parsed after a repair of bare ampersands | 3 |
| parsed by the lenient parser, jsoup | 1 |
| failed | 21 |

The 21 failures are 19 HTML pages and 2 JSON documents served at the
feed's URL, which the library reports as "Not an RSS 2.0 document" or,
for one blank answer, "no root element". Under Node, with xmldom as the
DOM parser, the same 21 failed, and so did the one feed that jsoup reads
on the JVM, whose fault is an attribute declared twice. So one feed in
4,669 needs a lenient parser, and that one isn't fixable by the repairs
that run on both platforms. The first 605 feeds had the same result: one
repaired, none failed, all read under Node.

The quirks that the parser reports are the same as in the earlier sweep,
with the duration format at 57% of feeds, an empty element at 39%, an
explicit value other than true or false at 27%, an unknown namespace at
17%, dropped attributes at 4%, a variant namespace URI at 4%, a date
format at 3.6%, a transcript type at 2% and a duplicate element at 1.5%.

### Identity

| Of 4,648 parsed feeds | Feeds | Share |
|---|---|---|
| declare a `podcast:guid` | 1,166 | 25% |
| whose declared guid is the UUIDv5 of the fetched URL | 602 | 13% |
| whose declared guid is the UUIDv5 of the final URL | 583 | 13% |
| whose declared guid matches neither | 557 | 12% |
| whose declared guid isn't a UUID | 12 | 0.3% |
| have two items with one guid | 18 | 0.4% |
| have two items with one enclosure URL | 164 | 3.5% |
| have an item with no guid, identified by its enclosure | 8 | 0.2% |
| have an item with neither | 1 | |
| use URLs as item guids | 1,315 | 28% |

The declared guids come from Buzzsprout (all 311 of its feeds), Spreaker
(201 of 408), RSS.com (all 140), Transistor, Captivate, RedCircle and
Audioboom. The hosts that use URLs as item guids are Spreaker (395 of
408), Anchor (112), iVoox, Feedburner, Podbean, Megaphone, Libsyn,
Buzzsprout, Acast and Simplecast. The duplicate enclosures come from
Libsyn (54 feeds) and Podbean (39), mostly one pair per feed, though 10
feeds have ten or more groups; the duplicate guids are spread over small
hosts, 14 of them a single pair and one feed with 14 items on one guid.
Popular feeds repeat enclosures more often, 6%, and Apple's requirements
say that it "will ignore duplicate enclosure URLs". The first 605 feeds
declared a guid in 68% of cases, which is the bias of that sample.

### Items

The median feed has 80 items, the third quartile 263, the ninth decile
500 and the largest 6,465. Popular feeds have 100 at the first quartile,
247 at the median and 657 at the ninth decile. 34 feeds, 1%, have no
items at all, 7% of the random stratum, and most of those are shows that
never published.

| Feeds at exactly | Feeds | Hosts |
|---|---|---|
| 100 items | 238 | Podbean 73, Libsyn 62, Omny 22 |
| 500 | 86 | SoundCloud 56 |
| 300 | 56 | Podbean 8, Libsyn 4 |
| 50 | 37 | spread |
| 200 | 28 | Podbean 6, Captivate 6 |
| 150 | 21 | |
| 1,000 | 18 | Spreaker 17 |
| 600 | 16 | Simplecast 15 |

276 feeds, 6%, link to a next page as RFC 5005 describes, 200 of them
popular, which is 10% of that stratum: SoundCloud 237, Omny 24, Amperwave
5, Feedburner 5. So a cap without pages is the common case, and the pages
that exist belong to two hosts. 94% of feeds have a self link, and 43%
name a WebSub hub: every Anchor feed (1,047), Buzzsprout, RSS.com,
Simplecast, Transistor, Captivate, Feedburner, Audioboom, Firstory and
Fireside.

5% of feeds list their items oldest first (RSS.com 35, Subsplash 33,
Riverside 26, Buzzsprout 12), 1% have an undated item, and 3 have an
item dated in the future.

### Enclosures

| Of 4,648 parsed feeds | Feeds | Share |
|---|---|---|
| an item without an enclosure | 119 | 2.6% |
| an enclosure without a length | 75 | 1.6% |
| an enclosure with length 0 | 258 | 5.6% |
| an enclosure without a type | 16 | 0.3% |
| the type `audio/mp3` | 91 | 2% |
| a type that is neither audio nor video | about 35 | 0.8% |

The lengths of 0 are Megaphone's (125 of its 126 feeds), then Spreaker
24, Firstory 15, Radio France 12, RFI 9 and Feedburner 9; dynamic ad
insertion gives a file no fixed size. The missing types are at small
hosts and Feedburner. The odd types are application/octet-stream (22
feeds), application/pdf (6), images and "None".

### Categories

Of the 4,635 feeds whose medium is podcast, 56 name no category and 106,
2%, name one that Apple's list doesn't know: 3% of popular feeds, 1% of
live ones. The names are not typos:

| Unknown name | Feeds | What it is |
|---|---|---|
| News & Politics | 19 | Apple's name until 2019, now News |
| Sports & Recreation | 10 | now Sports |
| Government & Organizations | 9 | now Government |
| Higher Education | 8 | a subcategory Apple dropped |
| Health | 6 | now Health & Fitness |
| Literature | 6 | now Books |
| Games & Hobbies | 5 | now Leisure |
| Society &amp; Culture | 5 | the ampersand escaped twice |
| Language Courses | 4 | now Language Learning |
| Science & Medicine | 4 | now Science |
| Management & Marketing | 4 | now Management |
| empty | 5 | |
| Professional, Training, Outdoor, National, Podcasting, Self-Help, Gadgets, Amateur, Other, K-12 | 2 to 3 each | names Apple dropped in 2019 |

The rest are single feeds with a word of their own ("Sermon", "kids",
"Para toda la familia") or an escape gone wrong ("Sports %26
Recreation"). No feed nests a category deeper than Apple's two levels.
The first 605 feeds showed 14% unknown names, nearly all of them music
genres on feeds whose medium is music, which Apple's list doesn't cover
and the validator shouldn't judge.

### The schedule

The library sets the next check of a feed from what the feed says: its
`podcast:updateFrequency`, its ttl, its syndication period, or else the
median gap between its recent items, and a day when it says nothing.
Judged at the time of the study:

| Of 4,648 parsed feeds | Share |
|---|---|
| give no time at all, so wait the default day | 8% |
| would wait more than a day | 64% |
| would wait more than a week | 34% |
| would wait more than a month | 8% |

The waits have a median of 3.4 days, a third quartile of 10 days and a
ninth decile of 25 days; popular feeds a median of 1.9 days, with 57%
over a day and 26% over a week. 17% of feeds give a ttl (every Podbean,
SoundCloud and Acast feed, and Riverside's), 4% a syndication period,
0.6% a `podcast:updateFrequency` and 3% say they're complete. The feeds
that give no time are Anchor's (101), Spreaker's (98) and RSS.com's
(43), whose items are too few or too irregular for a gap.

### Tags

The share of the 4,648 parsed feeds that use each tag, with the share
among the first 605 feeds beside it where it differs by more than a few
points:

| Channel tag | Sample | First 605 |
|---|---|---|
| `itunes:type` | 84% | 73% |
| `podcast:locked` | 26% | 37% |
| `podcast:guid` | 25% | 68% |
| `ttl` | 17% | 7% |
| `itunes:new-feed-url` | 14% | 16% |
| `podcast:medium` | 8% | 35% |
| `podcast:podping` | 5% | 12% |
| `podcast:funding` | 4.5% | 12% |
| `podcast:license` | 4% | 8% |
| `podcast:txt` | 4% | 12% |
| `sy:updatePeriod` | 4% | 1.5% |
| `itunes:complete` | 3% | 4% |
| `podcast:person` | 2.8% | |
| `podcast:location` | 2% | 5% |
| `podcast:podroll` | 1% | 4% |
| `podcast:trailer` | 0.7% | 1% |
| `itunes:applepodcastsverify` | 0.6% | |
| `podcast:updateFrequency` | 0.6% | 1% |
| `podcast:block` | 0.5% | |
| `podcast:value` | 0.3% | |
| `podcast:liveItem` | 3 feeds | 8 feeds |
| `podcast:publisher` | 2 feeds | 25 feeds |

| Item tag, in any item | Sample | First 605 |
|---|---|---|
| `itunes:duration` | 97% | 94% |
| `description` | 95% | 81% |
| `link` | 88% | 82% |
| `itunes:image` | 84% | 69% |
| `itunes:episodeType` | 82% | 72% |
| `itunes:episode` | 57% | |
| `content:encoded` | 40% | 35% |
| `itunes:season` | 40% | |
| `podcast:transcript` | 17% | 29% |
| `podcast:episode` | 7% | 28% |
| `podcast:chapters` | 5% | 6% |
| `media:content` | 4.5% | 4.5% |
| `podcast:season` | 4% | 21% |
| `podcast:soundbite` | 3% | 4% |
| `podcast:txt` | 2% | 8% |
| `podcast:location` | 1.5% | 5% |
| `podcast:person` | 1.4% | |
| `podcast:alternateEnclosure` | 0.6% | 3% |
| `podcast:contentLink` | 0.5% | 3% |
| `podcast:socialInteract` | 0.4% | 2% |
| `podcast:value` | 11 feeds | |

The first 605 feeds weren't measured for `podcast:person` and
`podcast:value`; a sixth of them were drawn from the feeds with a value
block.

### Validation

Under PSP-1, 99% of feeds miss a recommended element, 24% use a
deprecated one, 15% miss a required one, 6% miss a channel element that
RSS 2.0 requires, 3% have an invalid URL, 2.5% an item without an
enclosure, 1% a value that doesn't coerce, 0.7% a value outside an
enumeration and 12 feeds an invalid `podcast:guid`. Apple's requirements
differ only in which elements are required, 9%, and recommended, 56%.


## What it changes

For the needs report:

- **A ceiling on the wait** is the need the numbers support most: two
  thirds of feeds would wait more than a day, a third more than a week,
  and the popular feeds are not much better. Gain 4 stands.
- **Keeping the episodes a fetch no longer brings back** rests on caps
  that are the rule at Podbean, Libsyn, Omny, SoundCloud, Spreaker and
  Simplecast, and on pages that only SoundCloud and Omny serve. Popular
  shows are the ones with the most items behind the cap. Empty answers
  are rare in this sample, 1%, and most of them are shows that never
  published rather than outages, so the empty-feed guard is a small part
  of the batch.
- **Following a change of the feed's guid** matters for three of four
  feeds, which carry the derived guid, and moves happen: 11% of fetches
  were redirected, 18% of popular ones, and 14% of feeds carry an
  `itunes:new-feed-url`. Of the declared guids, half aren't the URL's,
  which is the case where a host that takes a show over writes its own.
- **Duplicate guids** are rare, 0.4% of feeds, and **duplicate enclosure
  URLs** aren't: 3.5% of feeds and 6% of popular ones, mostly at Libsyn
  and Podbean, and Apple drops the later item. The rule for enclosures
  should be an error under the Apple requirements, not a warning.
- **Category names** need Apple's table of names from before 2019 and a
  check for a double-escaped ampersand, more than a check for misspelling.
  The need stands at its size, and the validator should judge categories
  only when the medium is podcast.
- **A missing enclosure type** is rare, 16 feeds; a **length of 0** is
  common, 6%, and one of the largest hosts gives nothing else, so the
  rule that reads 0 as unknown matters more than the one that flags a
  missing attribute.
- **Large libraries** are real: a popular feed has a median of 247 items
  and 626 KB of XML, so a hundred popular subscriptions are about 60 MB of
  XML and twice that parsed.
- **A new finding, the default User-Agent.** wary-fetch's default agent is
  refused by 5% of feeds, at hosts that serve many popular shows, and the
  library's own by a host that refuses any agent with a URL in it. Both
  defaults should take the form that every app uses, a name and a
  version, with the URL left out, and the docs should say that an app
  gives its own.

For the documented gaps:

- **A lenient XML parser for JavaScript** would have read one feed in
  4,669. The nice-to-have verdict stands, and a hook for a parser of the
  app's own is enough. What a browser app meets instead is a web page at
  the feed's URL, 0.5% of fetches, which the discovery already handles and
  the diagnostic could name.
- **Ogg Opus and AAC readers** serve 62 and 53 feeds of 620,000.
- **WebSub subscriptions** would reach 43% of feeds, every one at Spotify
  for Creators, Buzzsprout, RSS.com, Simplecast, Transistor and
  Captivate, which raises that nice-to-have for anyone running a server.
- **Treating an unchanged body as a 304** would spare the diff for the 14%
  of feeds that give neither validator.
- **Value for value** is in 0.3% of feeds, 14 of the sample, and
  `podcast:socialInteract` and `podcast:liveItem` in fewer, which keeps
  every item of those kinds a nice-to-have for a general listener app.

For the design of identity and storage:

- The second identity of an item, the enclosure URL, is weaker than the
  design assumed at Libsyn and Podbean, where 3.5% of feeds reuse one; a
  rename must match an added item to one removed item, never to several.
- 28% of feeds use URLs as item guids, nearly every Spreaker and many
  Anchor feeds, so a change of domain at such a host rewrites every guid
  at once. That is the case the rename rule is for.
- 5% of feeds list oldest first, so the bound on new episodes must use the
  newest date, not the first item.
- Megaphone's lengths of 0 mean that a retained item's size can't be
  trusted from the feed, and the download plan already estimates.


## A conformance set

The study leaves `dev-resources/corpus/study-exhibits.edn`, which names
up to eight feeds of the sample for each of twenty phenomena, with their
index ids and URLs: duplicate guids and enclosures, items without a guid,
non-UUID guids, missing and odd enclosure types, missing lengths, empty
feeds, paged feeds, thousand-item feeds, unknown categories, undated and
future-dated items, oldest-first order, long waits, a complete feed, the
one feed the repairs don't fix, and the one with an item of no identity.
A conformance test can fetch those feeds into the corpus as
`corpus/download!` does and assert the fact that put them there, so that
each rule of the validator, the diff and the schedule has a real feed
behind it. The feeds themselves stay outside git, as the corpus does.


## How to repeat it

The index dump is at
<https://public.podcastindex.org/podcastindex_feeds.db.tgz>, 1.8 GB,
5.1 GB unpacked, under `dev-resources/corpus/index/`, which git ignores.
It answers 403 to curl's own agent, so give it a browser's. The sample is
`sample.tsv` beside it, exported with the SQL in the study's `comment`
block. Then, from a REPL with the `:dev` alias:

    (require 'study)
    (study/download-sample! (study/read-sample (io/file study/index-dir "sample.tsv")))
    (study/reprobe-blocked! (study/study-manifest))
    (def ms (study/measure (study/sample-records) (time/now)))
    (study/summary ms)
    (study/by-population ms)
    (study/fetch-summary (study/study-manifest))
    (study/exhibits ms)

The Node run is a scratch test namespace that reads every file of the
sample with `rss/try-parse` under the shadow-cljs test build, as
[Development](../development.md) describes for the suite.


## Sources

- The Podcast Index database: <https://podcastindex.org/>, the download
  link on the front page.
- The index's API, through which the earlier sweep was drawn:
  <https://podcastindex-org.github.io/docs-api/>
- Apple's categories and their 2019 changes:
  <https://podcasters.apple.com/support/1691-apple-podcasts-categories>
- Apple's feed requirements:
  <https://podcasters.apple.com/support/823-podcast-requirements>
- RFC 5005, feed paging: <https://www.rfc-editor.org/rfc/rfc5005>
