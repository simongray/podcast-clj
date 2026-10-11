# What to do next, October 2026

This page turns the reports of 11 October 2026 into one plan: what to fix
and build next, in order, why each step sits where it does, and where to
read more. The reports are:

- [The needs of podcast-clj](needs-2026.md): what the library lacks,
  scored by difficulty and gain.
- [Feed identity and storage](identity-and-storage-2026.md): the design
  for five of those needs, which share one core.
- [What real feeds look like](corpus-study-2026.md): 5,000 feeds measured
  with the library.
- [A threat model of the server and the handlers](threat-model-2026.md).
- [Hosts, validators and public servers](hosts-and-servers-2026.md): three
  persona walks through the library at a REPL.
- [Standards currency](standards-currency-2026.md): what moved in the
  specs and services since September.
- [A subtractive review](subtractive-review-2026.md): what the code can
  lose.
- [The needs of the sibling libraries](siblings-2026.md).

No code has changed since they were written, so every finding stands as
they give it.


## How it's ranked

Harm first, then cost:

1. What lets a stranger in or leaks a secret.
2. What gives a wrong answer or loses a listener's data.
3. The other needs, by gain and then by difficulty, with the scores of
   the needs reports.
4. Housekeeping: drift in the standards, releases, and what the code can
   lose.

Within a tier the cheap steps go first, so that a fix of an hour doesn't
wait two weeks behind a big batch. Each step is one batch in one
repository, or a short chain of them, so you can stop after any step.
Steps 1 to 4 take about a week and close every gap that the threat model
ranks, the IPv4 hole in wary-fetch, and the wrong answers on real feeds;
steps 5 to 8 take about two weeks and fix the ways a listener loses data.


## At a glance

| # | Step | Where | Size | Why it's here |
|---|---|---|---|---|
| 1 | [Close the server's holes](#1-close-the-servers-holes) | podcast server | a day | Reproduced: an open server behind Caddy, cross-site writes, the password in the log |
| 2 | [A security release of wary-fetch](#2-a-security-release-of-wary-fetch) | wary-fetch, then four pins | hours | Off the JVM, a stranger's URL reaches the local network |
| 3 | [The wrong answers that take an hour](#3-the-wrong-answers-that-take-an-hour) | podcast-clj | a day | Wrong on real feeds today; 64% of feeds wait more than a day between checks |
| 4 | [Ceilings on a leaked password](#4-ceilings-on-a-leaked-password) | podcast server, inspector | two days | Finishes the threat model before the long batches |
| 5 | [A version for the saved library](#5-a-version-for-the-saved-library) | podcast-clj | a few days | An older app overwrites a newer library, and steps 6 to 8 change what's saved |
| 6 | [A feed's key, settled once](#6-a-feeds-key-settled-once) | podcast-clj | a few days | After a move a show's state is unreachable, and three of four feeds can move |
| 7 | [Keep what a fetch no longer brings back](#7-keep-what-a-fetch-no-longer-brings-back) | podcast-clj | about three days | Capped feeds lose their back catalogue and their downloaded files |
| 8 | [Renamed episodes, and a bound on new ones](#8-renamed-episodes-and-a-bound-on-new-ones) | podcast-clj | a few days | A guid rewrite appends the whole back catalogue to the queue |
| 9 | [A dead network isn't a failing feed](#9-a-dead-network-isnt-a-failing-feed) | wary-fetch, then podcast-clj | about three days | After a flight, no feed is checked until tomorrow |
| 10 | [The siblings' small needs and releases](#10-the-siblings-small-needs-and-releases) | six libraries | about three days | Needs of an hour with gain 3, and podcast-clj can't be released before mind-meld and like-minded |
| 11 | [A complete validator](#11-a-complete-validator) | podcast-clj | about a week | Checks every other validator makes, and messages a podcaster can act on |
| 12 | [Field-wise entries in mind-meld](#12-field-wise-entries-in-mind-meld) | mind-meld | a few days | Two devices editing two fields of one entry lose one of the edits |
| 13 | [The host's path](#13-the-hosts-path) | podcast-clj | about three days | Serves a subset, and two decisions come first |
| 14 | [The standards brought up to date](#14-the-standards-brought-up-to-date) | docs and fixtures | an afternoon | Stale facts, nothing broken |
| 15 | [What the code can lose](#15-what-the-code-can-lose) | podcast-clj | about three days | Nothing wrong, but its API changes belong before a release |


## Decisions to make

The reports leave these open. Each blocks the step it names.

- **Step 1.** Should the server refuse to start without a password unless
  it's bound to localhost? The plan assumes yes, as the server walk
  proposes. And is "for yourself and a friend" one listener? Then the
  README's public section needs a paragraph saying so; if not, it should
  point a household at the many-listeners gap.
  ([Questions](hosts-and-servers-2026.md#questions))
- **Step 2.** Should wary-fetch's `fetch!`, `probe!`, `fetcher` and
  `digest!` default to `url/public?` rather than allow every address? It
  changes every caller, so it belongs in a release if at all.
  ([wary-fetch](siblings-2026.md#wary-fetch))
- **Step 4.** Should the library mask a private feed's `?token=` in every
  diagnostic, as the checker does for credentials in a URL, rather than
  only in the server's log?
  ([Questions](hosts-and-servers-2026.md#questions))
- **Step 5.** Does a profile with played marks of the earlier shape exist
  outside this repository? If not, `profile/played-mark`,
  `profile/migrated` and the inspector's `migrate-episodes!` can go now;
  if so, they go with version 2. The design's four defaults under [Left
  open](identity-and-storage-2026.md#left-open) stand unless you object.
  ([What the reading says](subtractive-review-2026.md#what-the-reading-says))
- **Step 10.** Should drop-in-search get a `:term-fn` hook, which has to
  be given again on every restore since a function can't be saved, or
  should its README drop the half-true promise that you can bring your
  own stemmer? ([drop-in-search](siblings-2026.md#drop-in-search))
- **Step 12.** The field-wise entry itself, or first a README section on
  the per-field schema and a `live?` that reads it?
  ([mind-meld](siblings-2026.md#mind-meld))
- **Step 13.** Should `feed/normalize` read a producer's strings, or
  should the model stay typed and only the message change? Is the WebVTT
  speaker carry-over a chosen leniency?
  ([Questions](hosts-and-servers-2026.md#questions))
- **Step 15.** Keep or drop the two option forms that nothing calls or
  tests (`:requirements` as a map, `:block? false`); let
  `comments/next-page` dispatch like its siblings or not; and pick one
  side of the public line for the byte readers of `format.image`.
  ([What the reading says](subtractive-review-2026.md#what-the-reading-says))


## The steps

### 1. Close the server's holes

- Keep the password out of the log: catch the file-system exception in
  the WebDAV handler and answer 400, and hide the Authorization header
  after the login, before anything logs the request. Today a put with a
  300-character name writes the Basic credentials into the log, and in
  Docker into the daemon's logs.
- Refuse cross-site writes to WebDAV and the gpodder API by
  Sec-Fetch-Site, as the proxy does, and give the session cookie
  `SameSite=Strict` and `Secure`. Today a text/plain POST from another
  site changes the subscriptions and adds plays.
- Refuse to start without a password unless bound to localhost, and
  require it in the compose file. Today the only guard without one
  refuses Tailscale Funnel, so behind Caddy the server is open to anyone.
- End the sessions when the password changes. Today a session cookie
  still works for up to a day, so changing a leaked password locks no
  one out.
- Strip the port from the configured names, since a name with a port
  refuses every request, and stop serving dotfiles from the client
  folder.

**Why here.** All of it was reproduced against the setup the README
documents. Two holes let in someone without the password, one leaks the
password to whoever reads the logs, and the remedy for a leak doesn't
work for a day. Each fix is an hour or half a day.

**Read more.** [The public server](hosts-and-servers-2026.md#the-public-server)
and item 0 of [What to build first](hosts-and-servers-2026.md#what-to-build-first);
rows 1, 5 and 6 of [Gaps, ranked](threat-model-2026.md#gaps-ranked), and
[A web page in the listener's browser](threat-model-2026.md#a-web-page-in-the-listeners-browser).

### 2. A security release of wary-fetch

- Read an IPv4 host as section 3.5.3 of the URL Standard does, octal, hex
  and short forms included, and never count a name of only digits and
  dots as a public name. Today Node connects `http://012.0.0.1/` to
  10.0.0.1 after the check called it 12.0.0.1, and in a browser
  `0x7f.0.0.1` passes as a public name.
- Correct the README's line that in Node the guards are those of the JVM.
- Make the default User-Agent a name and a version without the URL, in
  wary-fetch and in podcast-clj. Cloudflare-fronted hosts such as Omny,
  Riverside, Audioboom and Castbox refuse wary-fetch's agent, 5% of the
  sampled feeds, and one host refuses any agent with a URL in it.
- Release it, and bump the pin in podcast-clj, websub-clj, like-minded and
  indieblog.

**Why here.** It breaks the first promise of a released library: a
stranger's URL can reach the local network from Node. It's below step 1
only because the check holds on the JVM, where the podcast server runs.
The two repositories don't overlap, so steps 1 and 2 can run side by
side. The User-Agent rides along because it needs a release anyway.

**Read more.** The needs under [wary-fetch](siblings-2026.md#wary-fetch)
and item 1 of [What to build first](siblings-2026.md#what-to-build-first);
the user agents under [Fetching](corpus-study-2026.md#fetching).

### 3. The wrong answers that take an hour

- Cap the wait between checks of a feed with a `:max-wait` in the
  cadence, six or twelve hours. Judged by the feed alone, as today, 64%
  of real feeds wait more than a day and 34% more than a week.
- Let a clock duration without hours have any number of minutes. Today
  "103:12" doesn't read, so a listener app gets no duration for such an
  episode, and a 572-episode feed that Apple lists fails the Apple
  requirements with 15 errors.
- Drop a declared move to the feed's own URL, in `fetch!` and in the
  refresh. Podbean and Substack declare one on every feed.
- Report a blank or relative enclosure URL as missing, and as an error
  under the directories' requirements.
- Report a missing channel description once rather than twice.
- Give a 403 or a 404 from `fetch!` the `:fetch/status` diagnostic that
  the refresh already makes.
- Add `video/m4v`, the spelling of PSP-1, to the formats of the file
  check, so the media reader doesn't skip such files.

**Why here.** Each makes the library wrong on real feeds today, for every
listener app or validator, and each is an hour, so a day clears them all
before the two weeks of steps 5 to 8. The ceiling is the need that the
corpus study supports most.

**Read more.** [A ceiling on the wait](needs-2026.md#a-ceiling-on-the-wait-between-checks-of-a-feed)
and [The schedule](corpus-study-2026.md#the-schedule);
[The validator](hosts-and-servers-2026.md#the-validator) and item 1 of
[What to build first](hosts-and-servers-2026.md#what-to-build-first);
[PSP-1](standards-currency-2026.md#psp-1).

### 4. Ceilings on a leaked password

- A quota on the count and the bytes of the documents in the sync folder
  (a day).
- A cap on the proxy's requests in flight, with 503 and Retry-After beyond
  it; fifty bodies of 32 MB exceed the compose file's heap of 512 MB.
- A cap on the subscriptions one gpodder push can add, and a refusal of
  anything that isn't a public web URL. Today `ftp://x/` and private
  addresses are kept and fetched every minute.
- A delay on password guesses that grows per address, careful of a
  shared NAT.
- A private feed's token masked in the refresh report that the server
  logs.
- A request without a Host header refused, or the names check documented
  as one for browsers.
- The inspector given `url/public?` and the fetch limits when it binds to
  anything but localhost, and its docs saying it's for a local machine.

**Why here.** None of these is an exploit; each is something the
password grants without a limit, so today a leak can cost the whole
server rather than an afternoon. Two days finish the threat model, so
nothing on it waits behind steps 5 to 8.

**Read more.** [Someone with the password](threat-model-2026.md#someone-with-the-password)
and [Gaps, ranked](threat-model-2026.md#gaps-ranked); the junk
subscriptions under [The public server](hosts-and-servers-2026.md#the-public-server)
and the inspector's guards under [The validator](hosts-and-servers-2026.md#the-validator).

### 5. A version for the saved library

This is batch 1 of the identity design: `:version 2` in library.edn, the
migrations, a document per feed with `:changed-at`, and a save that puts
and deletes documents as the search index does. A newer library isn't
merged, and `save!` refuses to write over it. In the same batch, check a
restored profile with the profile's own check, a nice-to-have of an hour.

Before this step, record a baseline with `(bench/run!)` from
`dev/bench.clj` on a quiet machine, and run `bench/compare` after each of
steps 5 to 8, since they change the core.

**Why here.** Today `restore!` puts a newer library's profile in place
without a word, so an older app destroys what a newer one saved. Steps 6
to 8 all change what's saved, so the version comes first, as the design
says.

**Read more.** [The saved library has a version](identity-and-storage-2026.md#the-saved-library-has-a-version),
[The library is saved in parts](identity-and-storage-2026.md#the-library-is-saved-in-parts)
and [The batches](identity-and-storage-2026.md#the-batches);
[A version and a migration path](needs-2026.md#a-version-and-a-migration-path-for-the-saved-library).

### 6. A feed's key, settled once

Batch 2 of the design: `:guid` on the subscription and the feeds entry, a
rekey that moves everything but only while the key is unsettled, later
guids kept as aliases with `:fetch/guid-changed`, the takeover check over
the aliases, and seven namespaces that take the key rather than derive
it. Add the rule for a declared guid that isn't a UUID: fall back to the
derived one, and say so in the warning. Saving the gpodder handler's book
across a restart, a documented nice-to-have of a day, fits beside it.

**Why here.** Three of four active feeds carry a guid derived from their
URL, and 11% of fetches were redirected, 18% of the popular ones. After a
move the views give nil, progress lands under a guid with no
subscription, and new episodes stop joining the queue. The server walk's
plays lost after a restart come from the same split between the derived
and the declared guid.

**Read more.** [A key is assigned once](identity-and-storage-2026.md#a-key-is-assigned-once);
[Follow a change of the guid](needs-2026.md#follow-a-change-of-the-guid-of-a-subscribed-feed);
[Identity](corpus-study-2026.md#identity); the lost plays under [The public
server](hosts-and-servers-2026.md#the-public-server) and the non-UUID guid
under [The validator](hosts-and-servers-2026.md#the-validator).

### 7. Keep what a fetch no longer brings back

Batch 3 of the design: fresh items first and stored ones after, the
`:retained` facts, a guard against an empty answer, `:max-pages` on the
first fetch, a `:max-items` that never drops an item the profile holds
state for, and the retained items in the slim form.

**Why here.** Caps are the rule at Podbean, Libsyn, Omny, SoundCloud,
Spreaker and Simplecast, and 31,975 feeds in the index hold exactly 100
items against 3,793 with 99. Today the pages read at subscription vanish
at the first refresh, an episode that falls out of the feed can't be
played, and the download plan deletes its file. No other bug in the
reports loses as much. It comes after
step 6 only because the design keys the retained items under the settled
key.

**Read more.** [Items outlive the feed](identity-and-storage-2026.md#items-outlive-the-feed);
[Keep the episodes](needs-2026.md#keep-the-episodes-that-a-fetch-no-longer-brings-back);
[Items](corpus-study-2026.md#items) and [What it changes](corpus-study-2026.md#what-it-changes).

### 8. Renamed episodes, and a bound on new ones

Batch 4 of the design: `:renamed` in the diff, a function that moves an
episode's state to its new id, `:backfilled` for older items that appear,
and a config key that turns the bound off. Take the corpus study's two
corrections with it: match one added item to one removed item, since
3.5% of feeds reuse an enclosure URL, and bound by the newest date rather
than the first item, since 5% of feeds list oldest first.

**Why here.** A host migration rewrites every guid, and 28% of feeds use
URLs as guids, so a change of domain at Spreaker or Anchor does it to all
of them. A show that queues automatically then gets its whole back
catalogue appended to the queue. It needs the retained items of step 7.

**Read more.** [An episode keeps its state](identity-and-storage-2026.md#an-episode-keeps-its-state-through-a-guid-rewrite);
[Recognise an episode whose guid changed](needs-2026.md#recognise-an-episode-whose-guid-changed);
the design's part of [What it changes](corpus-study-2026.md#what-it-changes).

### 9. A dead network isn't a failing feed

- In wary-fetch, a `:type` for an unresolved name, an unreachable host
  and a TLS failure, each with a reason a person can read, and a `:sink`
  that streams a body within the limits. Then a release.
- In podcast-clj, an `:online?` option, and a round in which every fetch
  failed to connect leaves each feed's failures and next check alone,
  with one `:refresh/offline` diagnostic. The validator's unreachable
  file gets a reason per platform from the same types.

**Why here.** After four rounds offline every feed waits a day, so a
listener back from a flight sees nothing new until tomorrow. The fix
needs the typed failures first, and the sink is what the download rules
and offline episodes in a browser build on later.

**Read more.** [Telling a dead network from a failing feed](needs-2026.md#telling-a-dead-network-from-a-failing-feed);
the typed failures and the sink under [wary-fetch](siblings-2026.md#wary-fetch);
the unresolved name under [The host](hosts-and-servers-2026.md#the-host).

### 10. The siblings' small needs and releases

- In html-pieces, a body start tag closes an open head. Today a page
  without `</head>` gives nothing, and finding a feed from a show's
  website is one of the cases. Then tables in text and Markdown, and an
  unclosed row that doesn't nest the next.
- In like-minded, `:at` and `:failed?` on a step, and a function that
  finishes it, so podcast-clj's `library/sync!` loses the twenty lines
  every app writes.
- In websub-clj, a `:verified!` hook with the lease, discovery that
  reports a hub without a self link, and a bound on deliveries at once.
- In drop-in-search, a `:total` with a page, dates indexed as ISO 8601 in
  UTC, and the term hook if you decide for it (a day).
- In qname-hiccup, white space kept on request, and Hiccup without an
  attribute map.
- Releases of mind-meld and then like-minded, each with a build.clj; then
  html-pieces 0.3.0 in websub-clj and podcast-clj.

**Why here.** Each need is an hour with gain 2 or 3 for its library's own
audience, and the releases close a chain that podcast-clj can't be
released without, since it takes mind-meld and like-minded by Git SHA.
It comes after step 9 because, except for the head fix and the step
helper, nothing here changes what podcast-clj gets wrong.

**Read more.** [Summary](siblings-2026.md#summary), the needs of each
library, [Release readiness across the seven](siblings-2026.md#release-readiness-across-the-seven)
and items 2, 4 and 6 of [What to build first](siblings-2026.md#what-to-build-first).

### 11. A complete validator

- Duplicate guids as an error, and duplicate enclosure URLs as an error
  under the Apple requirements, since Apple drops the later item and 6%
  of popular feeds repeat one.
- Categories checked against Apple's list, with the renames of 2019 as a
  dated table in `feed.spec` from Blubrry and Podnews, double-escaped
  ampersands caught, and only when the medium is podcast.
- Enclosure length and type required, and a length of 0 read as unknown,
  which matters more: 6% of feeds and nearly every Megaphone feed.
- Messages that name the element and the item, fold a recommendation
  missing from every item into one, keep the value as a detail, and name
  the duplicate element.
- A verdict over the reports, a "Validate a feed" section in the guide,
  and the levels defined.
- A conformance test over the corpus study's exhibits, so each rule has a
  real feed behind it.

**Why here.** These serve hosts and validators, a real subset with gain
2 or 3, while steps 5 to 8 serve every listener app. Step 3 fixed the
answers that were wrong; what's left are checks the validator doesn't
make and messages a podcaster can't act on.

**Read more.** [Duplicate guids](needs-2026.md#duplicate-guids-and-enclosure-urls-pass-validation),
[Category names](needs-2026.md#category-names-arent-checked-against-apples-list)
and [Enclosure length or type](needs-2026.md#a-missing-enclosure-length-or-type-passes-validation);
[Categories](corpus-study-2026.md#categories), [Enclosures](corpus-study-2026.md#enclosures)
and [A conformance set](corpus-study-2026.md#a-conformance-set);
[Apple](standards-currency-2026.md#apple); [The validator](hosts-and-servers-2026.md#the-validator)
and items 3 and 4 of [What to build first](hosts-and-servers-2026.md#what-to-build-first).

### 12. Field-wise entries in mind-meld

An entry whose keys each carry a stamp while the entry as a whole can
still be removed, as Riak's and Automerge's maps do, with podcast-clj's
settings as the first user; or first the README section that documents
the substitute.

**Why here.** An entry is one register, so a title changed on one device
and a body edited on another lose one of the edits, which a CRDT exists
to avoid. The workaround of an entry per field, which podcast-clj's
settings use, breaks removal. It's gain 3 for mind-meld's audience but a
few days, so it follows the needs of an hour.

**Read more.** [mind-meld](siblings-2026.md#mind-meld).

### 13. The host's path

- A producer's strings either read by `feed/normalize` or named in the
  message, and the version warning only when the version is present and
  wrong.
- A `to-psc` that gives the model map, so it round-trips with `from-psc`.
- The WebVTT speaker carried over for "Name:" prefixes only, and
  reported.
- A private copy and an import that cover every file URL of a feed, not
  only the enclosures.
- An `op3/target` that strips the prefix, so OP3's rows and the host's
  own count together.
- Chapter images from ID3 written as files with URLs, not data URIs.
- A diagnostic when a reading was asked for a type with no reader.
- OP3's preview token in Services.

**Why here.** Hosts are a subset, the producer's strings and the WebVTT
carry-over wait on decisions, and nothing here touches a listener app.

**Read more.** [The host](hosts-and-servers-2026.md#the-host) and items 2
and 5 of [What to build first](hosts-and-servers-2026.md#what-to-build-first).

### 14. The standards brought up to date

- The notice of 6 September on gpodder.net in the sync reference and
  Services: it's now a sync service only, and a pull may lose older
  actions.
- The OPAWG fixture refreshed from commit 1344b58.
- OP3 probed again, and `op3/accepts?` brought in line.
- RFC 9562, section 5.5, cited in `feed.guid`.
- The Nostr relays table re-dated when it's next touched.
- Phase 9 (#789) and `podcast:socialAccount` (#784) watched, with nothing
  to build until a maintainer answers.

**Why here.** Nothing in the code is wrong, only some facts are stale,
and the gpodder.net note is the one a reader would miss. It's an
afternoon and fits anywhere.

**Read more.** [What to do](standards-currency-2026.md#what-to-do).

### 15. What the code can lose

- The thirty `^:no-doc` marks, and the reference page regenerated.
- Seven shared functions in place of about twenty copies. The
  trailing-slash stripper belongs in wary-fetch's `url`, so it can ride
  along with the release of step 9.
- The per-file tidies, and one implementation of the two Podcast Index
  fetchers instead of two.
- The options `:estimate` and `:with-token` renamed with the `-fn`
  suffix.

**Why last.** Nothing here is wrong. But the marks and the renames change
the public API, so they belong before podcast-clj's first release, and
the shared functions touch files that steps 5 to 8 change, so do it
before step 5 or after step 8, not between.

**Read more.** [What the count says](subtractive-review-2026.md#what-the-count-says),
[What the reading says](subtractive-review-2026.md#what-the-reading-says)
and [What to apply first](subtractive-review-2026.md#what-to-apply-first).


## Later

- **Feeds on demand**, batch 5 of the identity design, once a library of
  hundreds of feeds exists to measure it on.
- **The documented gaps** of the needs report, the record of downloaded
  files first, at gain 3 and a day; offline episodes in a browser build on
  the sink of step 9.
- **Bounded growth of the profile**, which the threat model names as the
  one way a peer device hurts the others.
- **The siblings' other needs**: the charset of an HTML page from its meta
  tag in wary-fetch, the internal DTD subset in qname-hiccup, nested lists
  in html-pieces, and one browser step in CI for all seven, since every
  README claims the browser and none tests it.
- **The proxy's small edges** from the server walk: a bare 30x for a
  redirect to a private address, a charset on binary bodies, and HSTS on
  plain HTTP.
- **The nice-to-haves** of both needs reports, by gain.
