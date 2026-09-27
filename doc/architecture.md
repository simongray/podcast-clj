# Architecture

This document records the design of the `dk.simongray/podcast` library: what it
is for, how it is layered, the conventions every namespace follows, and the
reasoning behind the choices that were not obvious. The companion survey in
`podcasting-2.0-clojure-survey.md` covers the ecosystem; this document covers
the code. `sync.md` is the design of the sync engine and `building-an-app.md`
the path through the library from finding a show to playing it.

## Goals

1. **Library first.** Pure functions over plain Clojure data. No framework, no
   global state, no opinion about storage, playback or UI. Anything a client
   might do differently (fetch, cache, render) lives outside the core or behind
   a small adapter.
2. **Podcasting 2.0 and the legacy stack, equally.** RSS 2.0, the iTunes
   namespace, the `podcast` namespace (all formalized tags), and the companion
   formats (JSON chapters, transcripts in four formats, OPML, `podcast:guid`).
   The other namespaces that real feeds carry (`atom`, `content`, `dc`,
   `media`, `psc`, `googleplay`, `spotify`) are read as far as they matter to a
   podcast client and preserved otherwise.
3. **Standards by the book, quirks by the bucket.** Each standard is
   implemented as specified. Every accommodation for the wild is a named
   *quirk*: individually switchable, applied before or after the strict step,
   and recorded in a report. A consumer can see exactly how far a feed departs
   from the standards, and the strict path never gets muddied by leniency.
4. **Cross-platform core.** Everything but the platform adapters, the XML
   parsers, the HTTP client, the folder store and the WebDAV server, is
   `.cljc` and free of platform APIs. Instants are `#inst`, JSON goes through
   a two-line adapter, hashing through one namespace, and deferred values
   through `async`, a future on the JVM and a promise in JavaScript.
5. **Lossless.** What the library does not understand it keeps, as Hiccup,
   so that parse followed by emit loses nothing a feed author wrote.
6. **Testable against reality.** The reference web app and a corpus of real
   feeds are how the library earns trust; the fixture set is a conformance
   suite the ecosystem otherwise lacks.

## Layers

```
                 ┌────────────────────────────────────────────────┐
  reference app  │ web/  inspector.*   (Pedestal)                 │  uses only the public API
                 └────────────────────────────────────────────────┘
                 ┌──────────────┬──────────────┬──────────────────┐
  companions     │ chapters     │ transcript   │ opml   value     │  formats, and the accessors
                 │ resolve  (one answer per property, by rule)    │  a client reads through
                 │ player (state)  search (criteria, index)  sync │  what an app keeps, finds and keeps in step
                 │ directory  podping                             │  the directories and the notifications
                 ├──────────────┴──────────────┴──────────────────┤
  feed           │ feed  (parse / emit interpreters)  validate    │  registry-driven
                 ├────────────────────────────────────────────────┤
  vocabulary     │ spec  (tag registry, enumerations)   quirks    │  data, and named repairs
                 ├────────────────────────────────────────────────┤
  primitives     │ xml  time  rrule  guid  json  rank  async  http│  the only reader conditionals
                 └────────────────────────────────────────────────┘
  jvm only       │ xml.jsoup  sync.file  sync.webdav.server  the ! functions of http, directory and podping
```

Dependencies point downward only. `spec` knows nothing about parsing; `feed`
knows nothing about HTTP; the companions depend on `xml`, `time` and `json`
but not on `feed` or `quirks`, since what they need of XML itself, the
Hiccup accessors, kebab-case names and entity decoding, lives in `xml`. The
inspector is a separate source path (`web/`, alias `:web`) in its own
namespaces, `inspector.*`, outside the library's tree like the dev tooling's
`corpus` and `reference`, so the library jar never carries Pedestal and the
namespace tree says what is library and what is example.

The namespaces follow the layers where one is genuinely part of another,
and stay flat where nothing is: `xml.jsoup` under `xml` and `time.rrule`
under `time`; `feed.coerce`, `feed.validate` and `feed.schema` under `feed`
as the three interpreters of the registry over the model, with `spec` and
`quirks` above them as the vocabulary; the companion formats `chapters`,
`transcript`, `opml` and `html` under `format`; `player`, `search` and
`sync`, what an app keeps, finds and keeps in step, under `app`, with the
stores under `sync` and the WebDAV server under its store; and the Podcast
Index client under `directory`, since the index is one of the directories
`directory` puts behind one shape. `resolve`, `value`, `guid`, `json`,
`http`, `rank`, `async` and `diagnostics` belong to no group and stay at
the top. A test reads the `ns` forms and holds the layering to this.

## Conventions

Learn one part of the library and the rest behaves the same way.

1. **`parse` reads leniently and never throws on content.** A value that
   will not coerce stays a string, a missing timing stays absent, a feed
   with a bare ampersand is repaired first, an SRT wrapped in HTML still
   comes back as segments. `parse` throws only when the input is not the
   format at all. Every format has a `parse` and, where writing makes sense,
   an `emit` that writes the canonical form; `sniff` guesses a format from
   its text.
2. **The report is where leniency is confessed.** Data comes back clean,
   and what had to be repaired, normalized or forgiven rides as metadata
   under `:podcast/report`, read with the `report` function next to each
   `parse`. Every diagnostic is a map with `:id`, `:level` (`:info`,
   `:warning` or `:error`) and `:message`, plus `:path` into the data where
   that applies; `diagnostics.md` lists every id. A diagnostic is either
   something a host can fix or something an app can act on; nothing else
   goes in a report. `summarize` groups a report or a validation by kind
   with counts, so that a recommendation repeated for every item is one row.
3. **`resolve` gives one answer per property, by a stated rule.** Where the
   vocabularies overlap, each accessor's docstring says which source wins.
   One-argument forms take a feed or an item; two-argument forms take the
   feed and an item and apply the inheritance the specifications describe.
4. **Optional behaviour is an options map.** What an operation needs is a
   positional argument; what it may be told is a map, never a positional
   flag: `(parse xml {:quirks? false})`, `(validate/validate feed {:profile
   :psp-1})`, `(chapters/emit-json doc {:pretty? true})`,
   `(resolve/transcript feed item {:languages ["da"]})`. Every options map
   is checked to be one.
5. **Nothing reads a clock or does IO but the functions named with a `!`.**
   Every write to the player state takes the instant it happens, a request
   to a directory is a map any client can send and the Podcast Index
   request takes the time it is signed at; `fetch!`, `call!`, `poll!` and
   `sync!` are where the network and the clock are.
6. **Requests as maps, answers as data.** The directories, the Podcast
   Index, Podping and the WebDAV store all build `{:method :url :headers
   :body}` and read `{:status :headers :body}`, so that a browser sends
   them with fetch and the tests run on recorded answers.

Keys are namespaced by vocabulary and kebab-cased: `:rss/pub-date`,
`:itunes/episode-type`, `:podcast/alternate-enclosures`. Attributes become
kebab-case keys in their element's map, the element's own text is
`:content`, repeatable elements are vectors under a plural key, and anything
the registry does not know is kept as Hiccup under `:xml/unknown`.
`tags.md` lists every element with its key, parents, count, types and
attributes; it is generated from the registry.

## The XML layer: Hiccup with namespaces resolved by URI

`dk.simongray.podcast.xml` turns XML into Hiccup and back:

```clojure
[:podcast/transcript {:url "https://…/ep1.vtt" :type "text/vtt"}]
[:title {} "Episode 3"]
```

Every element is `[tag attrs & children]` with the attribute map always
present. Tags and attributes are keywords. The keyword *namespace* is the
canonical alias of the element's namespace URI (`:itunes/author`,
`:podcast/guid`, `:atom/link`); unprefixed RSS 2.0 elements have no namespace
(`:title`, `:pubDate`). Names keep their XML casing at this level.

Two design points matter here:

- **Resolution is by URI, in pure code.** The platform adapter (StAX on the
  JVM, `DOMParser` in the browser) is deliberately *not* namespace-aware; it
  yields raw Hiccup with names exactly as written and `xmlns` declarations as
  ordinary attributes. A pure function then walks the tree, tracks
  declarations in scope, maps each URI to its alias and rewrites the names.
  This gives identical results on every platform, makes namespace handling
  testable without a parser, and lets the quirks layer sit in between
  (undeclared prefixes, misspelled URIs, a default namespace on `<rss>`).
- **Unprefixed means RSS.** RSS 2.0 elements have no namespace. Several large
  hosts declare `xmlns="http://www.w3.org/2005/Atom"` on `<rss>`, which a
  namespace-aware parser would take to mean that `<title>` is an Atom
  element. We ignore the default namespace for element names and report it.

Comments and processing instructions are dropped; adjacent text and CDATA are
coalesced; whitespace-only text nodes are removed. `emit` writes the
declarations for every alias used in the tree onto the root element, the
library's, the foreign ones a parsed feed carried and the ones a client
passes as `:namespaces`, escapes text or wraps it in CDATA when it contains
markup, and indents.

## The vocabulary: a tag registry as data

`dk.simongray.podcast.spec` describes every tag the library understands as a
map. One entry looks like this:

```clojure
{:tag     :podcast/transcript          ; the XML name
 :key     :podcast/transcripts         ; the key in the feed model
 :parent  #{:item}                     ; :channel, :item, :live-item or a tag
 :count   :many                        ; :one or :many
 :attrs   {:url      {:required true :type :url}
           :type     {:required true :type :enum :values transcript-types}
           :language {:type :language}
           :rel      {:type :enum :values #{"captions"}}}
 :content :none                        ; type of the node text, or :none
 :doc     "A transcript or closed-captions file."}
```

Three interpreters read the registry:

- `feed/parse` maps Hiccup to the model. For a child element it looks up the
  entry by tag and parent, coerces attributes and content by type, recurses
  into declared children, and stores the result under `:key`, in a vector when
  `:count` is `:many`. Elements with no entry are kept under `:xml/unknown`.
- `feed/emit` does the reverse in registry order, formatting coerced values
  back into their canonical string forms and appending `:xml/unknown` verbatim.
- `validate/validate` checks required attributes, enumerations, cardinality,
  parents and deprecation, and a *profile* named in its options (PSP-1,
  Apple) that adds required-element rules on top.

The registry is a value. `spec/tags` is the library's vocabulary as a vector
of entries; `spec/registry` indexes a vector, that one or one extended with a
client's own entries, into the map the interpreters read, and `spec/default`
is the library's vocabulary indexed. Every interpreter takes `:registry` in
its options: `podcast/parse` and `podcast/emit`, `feed/parse` and `feed/emit`,
`validate/validate`, `quirks/normalize`, and `schema/schemas` with its
companions. So a host's proprietary elements, or a new tag before the library
ships it, are read, written, validated and given schemas the same way as the
standard ones, without a fork, and the library's own registry can stay
strict about what it admits.

Spec churn becomes a data change. `podcast:images` is one entry marked
`:deprecated :podcast/image`; a new attribute is one line. Two more things
are generated from the registry so that they cannot drift from the code:
`doc/tags.md`, the reference of every element, written by `dev/reference.clj`;
and `dk.simongray.podcast.feed.schema`, Malli schemas of the model as plain data
(Malli is not a dependency), with one schema per element in a registry and
`schema/feed` and `schema/item` for the maps `parse` returns. The schemas
describe what `emit` accepts: the model with every value coerced and every
required attribute present. Every corpus feed happens to satisfy them, and a
generative test builds feeds from them, emits them and parses them back; but
`parse` is lenient by design and may return a map that fails them, a value
kept as a string or a required attribute left blank, and `validate` is what
says why. The schemas are for authoring, the validator for judging.

What stays outside the registry is a decision, not an oversight. Elements in
the podcast namespace that the specification does not define, such as the
`podcast:hiveAccname` and `podcast:liveValue` of Hive-based hosts, Blubrry's
undocumented RawVoice extensions, and every host's proprietary namespace are
kept as Hiccup under `:xml/unknown` and reported by the validator. The
specification is the boundary; a non-standard element earns a quirk only when
it stands for a standard one, as an item's attribute-only `image` does, or
the nested `podcast:category` that Wavlake writes on its music feeds in place
of `itunes:category`, which it mirrors in every way. The one element the
registry reads against the specification is a channel-level `<author>`, which
RSS 2.0 does not define and a fifth of feeds carry anyway.

RSS 2.0 is the only container: `feed/parse` refuses an Atom or RSS 1.0 root.
The directories require RSS 2.0, and a live sample of 844 indexed feeds on
2026-09-25 held no Atom feed at all.

## The feed model

The model is plain maps with namespaced, kebab-case keys, one namespace per
vocabulary: `:rss/title`, `:rss/pub-date`, `:itunes/episode-type`,
`:podcast/guid`, `:content/encoded`, `:atom/links`. Rules:

- **Scalar elements become scalars.** `<title>` is a string, `<pubDate>` an
  `#inst`, `<itunes:duration>` a number of seconds, `<itunes:explicit>` a
  boolean, `<podcast:locked>` a boolean.
- **Elements with attributes or children become maps.** Attribute names are
  unqualified kebab-case keys (`:start-time`, `:feed-guid`,
  `:is-perma-link`); the node text, when the tag has one, is `:content`;
  declared children are nested under their own keys (`:recipients`,
  `:sources`, `:remote-items`).
- **Structured attribute values are maps.** The `rrule` of
  `podcast:updateFrequency` is the RFC 5545 rule as a map of its parts, the
  `geo` of `podcast:location` is `{:lat :lon}` and its `osm` `{:type :id}`;
  each writes back canonically.
- **Repeatable elements are vectors** under a plural key
  (`:podcast/transcripts`, `:podcast/people`, `:itunes/categories`,
  `:rss/items`, `:podcast/live-items`).
- **Coercion never throws.** A value that cannot be coerced stays a string and
  the report gets a diagnostic.
- **Markup where text belongs is kept.** A description embedded as XHTML
  rather than wrapped in CDATA becomes its markup as a string; other text
  elements with elements inside them become the text of all their
  descendants. Both are reported as `:feed/markup-in-text`.
- **Nothing is dropped.** Unknown elements are Hiccup under `:xml/unknown`;
  unknown attributes stay in the element's map as strings.
- **Shorthand is normalized, not modelled.** A producer may write a bare
  value where an element with attributes carries only content,
  `{:rss/guid "x"}`, or a single value where the model has a vector.
  `feed/normalize` expands both by the registry, and `emit` and `validate`
  call it first, so the shorthand is taken everywhere the model is written
  while `parse`, the accessors and the schemas know one shape. The
  alternative, schemas with `:or` for every such element, would have made
  the model two-shaped for the sake of the writer.
- **Validation never throws on content.** A value of a shape its element
  does not take, a string where a map belongs or a map where a scalar does,
  is `:validate/wrong-shape`, a diagnostic like the rest; a profile the
  validator does not know is a programming error and throws.

Identity is a function over the model, not a field: `feed/feed-guid` returns
`:podcast/guid` or the UUIDv5 computed from the feed URL; `feed/item-guid`
returns the item's `<guid>`, falling back to the enclosure URL. `feed/diff`
builds on it: given two parses of the same feed it reports the items added,
removed and changed, the same for live items on their own, so that a show
going on air is a change, and whether the channel changed in anything but
its items and timestamps.

## Resolving the vocabularies

The model keeps every vocabulary apart, which is right for fidelity and wrong
for a client that just wants *the* title. `dk.simongray.podcast.resolve` is
where the overlaps are settled, once, in pure functions over the model:
`title`, `description`, `author`, `link`, `published`, `artwork` and `images`,
`explicit?`, `duration`, `numbering`, `enclosure` and `enclosures`,
`transcript`, `chapters-url` and `embedded-chapters`, `people`, `funding`,
`license`, `chat`, `locations`, `value`, the live items in listening order
(`live-items`, `live?`), and for the feed as a whole `medium`, `list-feed?`,
`complete?`, `next-episode` (from the recurrence rule of
`podcast:updateFrequency`, evaluated by `rrule/occurrences`), `locked?`,
`new-feed-url`, `self-url` and `categories`; `blocked?` for what the block
tags of three vocabularies say to one platform; and for presenting a show
`episodes`, the items in listening order (newest first, or for a serial show
by season and number with trailers and bonuses placed by Apple's rules),
`seasons` to group them, and `next-check`, when a poller should look again:
the update-frequency rule, the ttl, the syndication period, or the cadence
of the recent items. Each docstring states its precedence. The two-argument
forms take the feed and an item and apply the inheritance the specifications
describe: people on an item replace the feed's entirely, a value block on an
item overrides, artwork and funding fall back to the channel. `transcript`
takes options as well, since a client's preferences (types, languages, a need
for timings) are its own; given the feed, a reference that names no language
is taken to be in the feed's. Nothing here is stored or cached; it is all
derived on each call, and the reference app renders its item rows through it
so that the corpus exercises it.

`properties` gathers every answer into one map with plain keys, the show's
or, given the item as well, the episode's with the inheritance applied, so
that `(merge item (properties feed item))` holds the raw fields and the
resolved ones side by side for an app that needs both. `sources` says where
each answer came from, as paths into what was given, `[:item :rss/title]`
or `[:feed :itunes/explicit]`. It needs no table of precedences: for each
property it calls the accessor on one field at a time and keeps the fields
whose value alone gives the same answer, so it can never disagree with the
accessor's own rule. A property answered by a default, or put together
from several fields, names none.

## Quirks

`dk.simongray.podcast.quirks` is a catalogue of small, named functions in two
groups, applied around the strict path:

1. **String repairs**, before parsing: strip a byte-order mark or leading
   junk before `<?xml`, replace HTML entities that XML does not define
   (`&nbsp;`, `&copy;`), drop control characters, declare well-known prefixes
   that a feed uses without an `xmlns`.
2. **Tree and value normalizations**, after parsing and before mapping:
   namespace URI variants (`http` for `https`, casing, trailing slash, the old
   GitHub URI for the podcast namespace), `yes`/`no`/`clean` for
   `<itunes:explicit>`, clock formats for `<itunes:duration>`, an
   `<itunes:image>` with a `url` attribute or a text node instead of `href`,
   date strings that bend RFC 2822, and so on.
3. **A lenient parser as the last resort.** When a document is malformed
   even after the repairs, and `org.jsoup/jsoup` is on the classpath,
   `dk.simongray.podcast.xml.jsoup/parse-raw` reads it with jsoup's XML
   mode, which rejects nothing: unquoted attributes, stray angle brackets and
   unclosed or mismatched tags all yield a best-effort tree. It produces the
   same raw Hiccup as the strict adapter, so everything downstream is shared.
   jsoup is optional and discovered with `requiring-resolve`; without it the
   strict parser's error is thrown.

Every quirk has an id, a severity and a one-line description. `parse` applies
them all, `{:quirks? false}` is the strict path alone, and `:except` leaves
out the repairs and rules it names; the yes/no rule is derived from the
registry in use, so a client's own yes/no elements get it too. Each
application is recorded in the report attached to the result, so a feed's
distance from the standards is measurable and the reference app can display
it.

## Reports

Parsing returns the feed map; the report travels as metadata under
`:podcast/report` and is read with `podcast/report`. The report is a vector of
diagnostics, each a map with `:id`, `:level` (`:info`, `:warning`, `:error`),
`:message` and, where it applies, `:path` into the model. Quirk applications,
coercion failures and unknown namespaces all land here. Each layer records
only what it did, namespace resolution and the quirks on the tree, the model
mapping on the feed, and the front door concatenates them in that order.
Metadata does not survive printing, transit or `into {}`, so a client that
persists a parse persists the report beside it. Validation diagnostics have
the same shape so the reference app can show one list. Every id the library
raises is catalogued with its level and meaning in
`dk.simongray.podcast.diagnostics`, from which `doc/diagnostics.md` is
generated; a test keeps the catalogue and the source in step, since
consumers match on the ids.

The same convention holds for everything else the library parses. A
transcript or a chapters document carries its own report under the same
metadata key, read with `transcript/report` or `chapters/report`: times
written as strings, speakers taken from `Name:` prefixes, an SRT wrapped in
HTML, a declared type the file contradicts, chapters out of order or without
a start. A diagnostic is something a host can fix or an app can act on; the
ordinary processing of a format, such as collapsing HTML whitespace, is not
reported.

## Companion formats

- **Chapters** (`chapters`): JSON Chapters 1.2 parse and emit, and the Podlove
  Simple Chapters (`psc:`) elements that several hosts still embed,
  normalized to the same chapter shape. `from-text` reads the third source,
  the timestamp lists in show notes, which far more episodes carry than link
  a chapters file, so an app gets chapters for them too, reported as
  inferred.
- **Transcripts** (`transcript`): SRT, WebVTT (with `<v>` speaker spans), JSON
  segments, the constrained HTML form and plain text, all read into one
  segment model; VTT, SRT and JSON emitted from it. The wild taught it a few
  things: caption files name the speaker with a `Name:` or `[Name]:` prefix
  only where the speaker changes, so the name carries over the cues that
  follow; some hosts write JSON times as strings; HTML carries layout
  whitespace and sometimes an entire SRT wrapped in `<p>` elements; and
  word-level JSON needs `turns` to read as prose.
- **OPML** (`opml`): subscription lists in and out, folders flattened to a
  path.
- **Embedded HTML** (`html`): the show notes in descriptions, read into
  Hiccup by a small lenient parser of the HTML browsers would build, since
  most descriptions in the wild carry markup and all of it comes from a
  vocabulary of some thirty elements: paragraphs, breaks, links, emphasis,
  lists, headings, images, the odd table, unclosed paragraphs and list
  items, pasted word-processor tags. No HTML parser dependency, so the JVM
  and JavaScript agree. `sanitize` keeps the elements, attributes and URL
  schemes a client may render and drops scripts and their kin; `hiccup`
  parses and sanitizes, or turns a plain-text description into paragraphs;
  `text` and `markdown` render the same Hiccup; `emit` writes it back as
  HTML. Hiccup is the centre because a Clojure client hands it straight to
  its renderer.
- **Value** (`value`): the effective value block for an item (item overrides
  channel), share and fee arithmetic, and the recipients active at a
  playback position given `valueTimeSplit`s. Pure arithmetic; no payments,
  but `boostagram` writes the bLIP-10 record an app attaches to the keysend
  payment it makes.
- **Podcast Index** (`directory.podcastindex`): a request builder that
  produces `{:method :url :headers}` with the SHA-1 auth header computed for
  the instant the caller gives, so any HTTP client can send it, and a
  response reader that maps the index's feed, episode and value objects
  onto the model, so that a feed from the index has the same keys as one
  parsed from RSS and the accessors in `resolve` work on both; what only the
  index knows is under `:index/*`, the original under `:index/raw`. The
  endpoint table follows the API's OpenAPI description. Remote items in
  value time splits are resolved by asking for the item's own block first
  and the feed's second; `remote-value` takes the sending function as an
  argument, so it is tested with stubs and works with any client, and
  `value/with-remote` folds the answer into the recipients of the moment.
  `remote-item-requests` and `remote-item` resolve any remote item, of a
  podroll, publisher, list or time split, to the feed and episode the index
  holds. On the JVM, `call!`, `remote-value!` and `remote-item!` use the
  library's own HTTP client and sign with the clock; in ClojureScript
  `call!` uses `fetch` and returns a promise. The key and secret never leave
  the caller's process.

## The player state

A podcast app's own logic is mostly the same in every app: a play queue,
where the listener left each episode and which ones count as played,
subscriptions and their settings, and keeping several devices in step.
`dk.simongray.podcast.app.player` holds that as one state map and pure
operations over it, so that a client writes its UI and nothing else. The
rules of the library hold: no storage, no clock, no media element. Every
operation takes the instant it happens and stamps what it changed with the
state's device id and that instant, or the millisecond after the latest
instant the state has seen when the given one is not later, a hybrid
logical clock in one line; the app persists the map however it likes and
calls in when the player emits events.

The state is one map: subscriptions by feed guid, with removals kept as
tombstones and their settings, such as `:auto-queue?` and `:skip-intro`,
one entry per field; episodes by `[feed-guid item-guid]`, with position,
duration and the played mark that `progress` sets within
`played-threshold` seconds of the end; the queue as one entry per episode
carrying a rank from `rank`, the order derived from the ranks; the
listener's `preferences`, the skip amounts and the playback rate, one entry
per field; and the registry of the listener's devices. `merge` reconciles
two devices entry by entry, the later winning, which is commutative,
associative and idempotent (a generative test says so), so that any backend
that can store and fetch a document can sync, which `app.sync` does, below.
`refresh` feeds a fresh parse through `feed/diff` and queues the new
episodes of feeds set to auto-queue; `to-opml` and `from-opml` bridge to
the `opml` namespace with the feed guid carried in each outline, and `skip`
moves within an episode without leaving it. `step` is a small reducer over
the player's events, `:progress`, `:ended`, `:play` and `:skip`, returning
the new state and the effects the UI should act on, such as playing the
next episode or seeking to the skipped-to position; it is deliberately the
only place where behaviour is composed, and it is short. The CRDT the state
amounts to, and what syncing asked of it, is in `sync.md`.

## Platform adapters

| Concern | JVM | JS |
|---|---|---|
| XML in | `javax.xml.stream`, namespace-aware off, DTD off | `js/DOMParser`: the browser's, or one the host installs |
| XML in, leniently | jsoup's XML mode, when the optional jsoup is present | none yet |
| JSON | `clojure.data.json` | `js/JSON` |
| SHA-1 (guid, index auth) | `java.security.MessageDigest` | `goog.crypt.Sha1` |
| Instants | `java.util.Date` via millis | `js/Date` via millis |
| Deferred values (`async`) | futures | promises |
| HTTP | `java.net.http` (`http` namespace) | left to the client |
| Encryption (`sync.encrypted`) | `javax.crypto` | WebCrypto |

The date parser, the duration parser and the UUIDv5 computation are written
once in `.cljc` from arithmetic and regular expressions rather than from
`java.time` or `js-joda`, so they behave identically everywhere.

On the JVM the front door adds `fetch!`, the one step a poller repeats: the
conditional headers go out, a parsed feed or a 304 comes back with the
validators for next time and the XML itself, the URL is the final one after
redirects, and the feed's own new-feed-url is surfaced when it declares one.

The JS adapter uses only the standard DOM API (`parseFromString`,
`getElementsByTagName`, `item`, `nodeName`, `nodeValue`), so any DOMParser
will do. A browser reports a malformed document with a `parsererror`
element; other implementations may throw or return a document without a
root, and all three become the same `::malformed` exception. Under Node the
test setup installs `@xmldom/xmldom` as `js/DOMParser`, configured to throw
on every error the way a browser would report one. Two differences between
the platforms shaped the code: a DOMParser is namespace-aware and rejects an
undeclared prefix that the JVM's parser lets through, which is what the
undeclared-prefix repair is for; and ClojureScript's `str/replace-first`
does not accept a function replacement the way Clojure's does, so the
repairs avoid it.

## Finding episodes

`dk.simongray.podcast.app.search` is where an app's lists come from, in two
independent halves.

**Criteria and orders as data.** `where` turns a map such as `{:season 2
:type "full" :status :unplayed}` into a predicate of a feed and an item,
and `order` turns a keyword such as `:newest` into a sort key; `episodes`
applies both to one feed, or to a collection of feeds for the inbox across
subscriptions. Every criterion reads the model through `resolve`, so it
means exactly what the accessor means: `:explicit?` inherits from the
channel, `:chapters?` counts a linked file, embedded Podlove chapters and
the timestamps in the notes alike. Criteria that depend on the listener,
`:status` and the `:recent` order, take the player state, and identify
episodes as the player does, by `[feed-guid item-guid]`. Because the
criteria are data, a UI can build them from a form, and the inspector's
filter form is exactly that. An unknown criterion or order throws: it is
a programming error, not content to forgive.

**An index that is a value.** `index` and `add` build a map, `query`
searches it, `remove` takes a document out; nothing is mutated and
nothing runs in the background. Documents are `{:id :fields :stored}`,
built from the model by `item-document` and `feed-document` or by hand,
so that an app can index whatever it has, a fetched transcript included.
Text is analyzed by one rule on both platforms, words lower-cased with
diacritics folded, and a field or a query given as a vector of strings
skips analysis, which is how a client brings its own stemming or
segmentation without the library carrying a language model. The postings
keep positions, so a quoted phrase is verified rather than approximated,
and the terms are kept in a sorted set beside the postings, so the last
word of a query expands to every term it is a prefix of, which is what
search as you type needs. Ranking is BM25 per field, summed with
`boosts`, the plain and well-understood scheme, and what a consumer might
want otherwise is an option of `query`: the field weights, a weight of
zero leaving a field out, BM25's `k1` and `b`, the shortest word to
complete, and a `:rank` function that orders the results by something
other than the score, ties by recency say. `snippet` marks the matches in
a text the caller supplies, since the index does not keep the text.
Lucene would have been the JVM answer and no answer in the browser; the
index here is a few hundred lines, runs everywhere the model does, and
prints as EDN.

The cost is that of an inverted index held as persistent maps: measured on
36,000 items with their show notes, five seconds to index and roughly 10 KB
of heap an episode, a query answering in tens of milliseconds at worst. A
listener's library of a few thousand episodes is therefore tens of
megabytes and well under a second to build; the notes are nine tenths of
the terms, so an app short of memory, in a browser say, indexes fewer
fields, titles and people alone costing a tenth, and adds transcripts only
for the episodes it has downloaded.

## The directories

`dk.simongray.podcast.directory` puts the podcast directories behind one
shape, the way `directory.podcastindex` does for the Podcast Index: Apple
Podcasts, fyyd and gpodder.net, which need no credentials, and the Podcast
Index, which does. Three endpoints cover what a client asks a directory for,
`:search` by words, `:lookup` by the source's id and `:trending` for the
chart, and every source answers them as far as it can; Apple's chart names
no feed, so a lookup by its id follows. A request is a map any HTTP client
can send, so the namespace is `.cljc` and a browser client builds the same
URLs, its CORS policy permitting. A response reads into shows keyed like a
parsed feed, `:rss/title`, `:itunes/author`, `:itunes/image`, with the
feed's URL as the `atom:link rel=self` that `resolve/self-url` reads and
`:podcast/guid` derived from that URL by the specification's rule when the
source declares none, which is what makes results from different sources
comparable at all. What only a directory knows sits under `:directory/*`:
the source, the show's id there, its page, an episode or subscriber count,
a chart rank, and the raw record. `combine` folds the answers of several
sources into one entry per show, the first source's record filled in from
the later ones, `:directory/sources` naming each with its id, so that a
chart entry meets its feed. On the JVM `search!` and `trending!` ask every
source at once, and a source that fails is a line under `:failed` rather
than an exception. The inspector's front page searches the directories and
inspects the feed it finds.

Podchaser, Listen Notes and Taddy are left out: each needs an account,
and the four here already cover discovery for a client that has none.

## Podping

`dk.simongray.podcast.podping` is the consumer of Podping, the notification
that a feed changed: hosts announce through podping.cloud, which writes a
custom_json operation to the Hive blockchain, and anyone reads the chain.
The namespace follows the shape of the directories. `request` builds the
JSON-RPC calls a consumer makes, for the chain's position, a run of blocks
with their operations, one block's operations and the writer list, as
request maps with a POST body, so that a browser client sends them too:
the public nodes allow any origin, and `call!` in ClojureScript is one
`fetch`. `pings` reads the podpings out of the blocks answered, in either
shape a node writes an operation, the first versions' `urls` and
`feed_update` included, and takes the medium and reason from the payload
or else from the id; a block's number is the first eight hex digits of its
id. Anyone can write a custom_json, so `writers` reads which accounts may:
the ones the `podping` account follows on the chain, which is how the
project publishes its writers.

On the JVM `poll!` is the poller's one step, like `fetch!` for a feed: the
pings in the blocks after the last one read, a hundred blocks to one
request, verified against the writers read once and handed back, with the
block to continue from and the pings refused kept apart rather than
dropped. It can stop at the last irreversible block for a consumer that
would rather not see a fork's pings. `call!` tries the public nodes in
turn, treating a status other than 200 or a body that is not JSON as that
node's failure, and names them all when none answers. `pinged?` and
`affected` compare URLs as `podcast:guid` compares them, so a host that
pings `http://…/feed/` reaches a subscription stored as `https://…/feed`.
`notify-request` is the host's side, one GET to podping.cloud with the
token it was issued.

The inspector's Podping page reads the last twenty blocks live, which is
how the consumer is exercised against the real chain; the tests run on
recorded answers, with a stand-in node that fails on cue.

## Syncing

`dk.simongray.podcast.app.sync` keeps one listener's devices in step, on the
design in `sync.md`, whose reasoning is not repeated here. The merge being a
join, a backend only has to hold documents: every device writes its whole
state in an envelope, as `<device-id>.edn`, and reads the others' and
merges them, so that no two writers ever meet and a folder any sync client
mirrors is a complete backend. A store is a map of four functions over
named documents with version tokens, its credentials closed over in its
constructor, and `store/backend` turns any store into a backend by one of
two strategies, a document per device or one shared document put on
condition of its version. `sync!` is one step over any number of backends,
pull, merge, push, with a cursor per backend, and on every step it
acknowledges in the registry what the whole documents told and compacts
the tombstones every device has seen.

The layering: `app.sync` stands on `app.player` and `async`;
`app.sync.store` on both and on `format.opml`, for the subscription list it
mirrors; `app.sync.file`, `app.sync.webdav` and `app.sync.encrypted` are
stores over `app.sync.store`, and `app.sync.webdav.server` stands on the
WebDAV store to serve any store over the same protocol, which the inspector
does at `/dav/` through the `inspector.dav` interceptor, since Pedestal's
router does not match a collection's trailing slash and its body-params
interceptor would read a put's body first. `store/check!` tries any store
through the contract and reports for a settings screen, and `sync/next-at`
says when to sync next, so that an app's timer has a policy while the
library still schedules nothing.

## The reference web app

`web/inspector/` is a small Pedestal service that renders HTML with
Replicant's string renderer. It fetches a feed URL (or one of the corpus
feeds), parses it, and shows the channel, the items, every recognized tag,
the unknown elements, the report, the validation diagnostics against a chosen
profile and the round-tripped XML, with the resolved properties and their
sources beside the raw fields; its front page searches the directories, its
Podping page reads the chain, and its queue page carries a small real player
in ClojureScript over `app.player`: the state lives in localStorage,
Replicant renders it into a bar on every page, an audio element reports its
position through `step`, whose effects seek it or start the next episode,
and the folder at `/dav/` is the sync backend the player and any WebDAV
client share. Clicking a link fetches the next page and swaps only the
content under the bar, so playback survives moving between pages without
the inspector becoming a single-page application. It is the first client of
the library and exists to test it; it touches nothing but the public API.

## Testing

- Unit tests per namespace with `clojure.test`, written as `.cljc` and run on
  the JVM, under Node and in a browser (shadow-cljs `:node-test` and
  `:browser-test` builds); the spec repository's `example.xml` and companion
  examples are fixtures (CC0), inlined into the JS builds at compile time.
- Hand-written fixture feeds, one quirk each, with the expected report.
- A corpus of real feeds from some thirty hosts in `dev-resources/corpus/`,
  outside git, recreated by `dev/corpus.clj`. A corpus test parses every feed,
  asserts no exceptions, round-trips it, and validates it.
- A second corpus, in `dev-resources/corpus/companions/`, of the chapter and
  transcript files those feeds link to: a stratified sample of two per host
  and type, with a manifest. `corpus/sweep-companions` parses them all and
  tabulates segments, speakers and the format sniffed against the one
  declared; the quirks it exposed each have a synthetic fixture in the tests.
- A third corpus, in `dev-resources/corpus/sweep/`, of a few hundred feeds
  sampled through the Podcast Index by `corpus/sweep-populations`: a random
  slice of the recently updated feeds, of the newly added ones, of the feeds
  with value blocks and of every non-podcast medium. `corpus/sweep-report`
  parses them all and aggregates parse errors, report and validation
  diagnostics by the number of feeds they touch, unknown elements by tag,
  values that would not coerce, and round-trip changes; the sweeps are how
  registry gaps, quirks and emitter bugs are found, each fixed with a test.
- Generative tests: the merge's three properties, the ranks, the
  convergence of several devices syncing in random orders through either
  strategy, and the Malli schemas generating feeds that emit and parse back.
- The inspector's routes are tested through Pedestal's interceptor chain
  with the network stubbed, so that the reference app cannot break silently;
  its WebDAV folder doubles as a real server for the WebDAV store's test.
- The inspector's browser player is two namespaces: pure views that turn the
  page state into Hiccup with event handlers as data, tested by equality
  under Node and in the browser build, and a shell that performs the
  events, tested on jsdom under Node, where it is mounted, given an episode
  through a stubbed fetch, and clicked. Node-only tests are named
  `-node-test`, which the browser build leaves out.
- `architecture_test.clj` reads every `ns` form under `src/`, places each
  namespace in the layer this document gives it, and fails when a require
  points up a layer, when the graph has a cycle, when the registry requires
  anything, or when a companion format reaches above the primitives.
- CI runs two jobs: everything on the current Java and Clojure with the
  Node suite and the linter, and the library's own tests on Java 11 with
  Clojure 1.11, the floor `deps.edn` declares and the pom states. The
  aliases override to the current Clojure; the `:test-floor` alias does not.

## Not built

A backend over a service with its own model, gpodder's first, designed in
`sync.md`; stores over the cloud services' HTTP APIs, each a short
namespace against the store contract; a JSON form of the sync documents for
clients in other languages; a lenient XML parser for JavaScript; a folder
store for Node; and the deploy itself.
