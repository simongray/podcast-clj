# Architecture

This document records the design of the `dk.simongray/podcast` library: what it
is for, how it is layered, the conventions every namespace follows, and the
reasoning behind the choices that were not obvious. The companion survey in
`podcasting-2.0-clojure-survey.md` covers the ecosystem; this document covers
the code.

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
4. **Cross-platform core.** Everything except the XML adapter and the HTTP
   client is `.cljc` and free of platform APIs. Instants are `#inst`, JSON goes
   through a two-line adapter, hashing through one namespace.
5. **Lossless.** What the library does not understand it keeps, as Hiccup,
   so that parse followed by emit loses nothing a feed author wrote.
6. **Testable against reality.** The reference web app and a corpus of real
   feeds are how the library earns trust; the fixture set is a conformance
   suite the ecosystem otherwise lacks.

## Layers

```
                 ┌────────────────────────────────────────────────┐
  reference app  │ web/  dk.simongray.podcast.web.*   (Pedestal)  │  uses only the public API
                 └────────────────────────────────────────────────┘
                 ┌──────────────┬──────────────┬──────────────────┐
  companions     │ chapters     │ transcript   │ opml   value     │  formats, and the accessors
                 │ resolve  (one answer per property, by rule)    │  a client reads through
                 ├──────────────┴──────────────┴──────────────────┤
  feed           │ feed  (parse / emit interpreters)  validate    │  registry-driven
                 ├────────────────────────────────────────────────┤
  vocabulary     │ spec  (tag registry, enumerations)   quirks    │  data, and named repairs
                 ├────────────────────────────────────────────────┤
  primitives     │ xml   time   guid   json   coerce              │  the only reader-conditionals
                 └────────────────────────────────────────────────┘
  jvm only       │ http  (java.net.http fetcher)   index (client) │
```

Dependencies point downward only. `spec` knows nothing about parsing; `feed`
knows nothing about HTTP; the companions depend on `xml`, `time` and `json`
but not on `feed`. The web app is a separate source path (`web/`, alias
`:web`) so the library jar never carries Pedestal.

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
declarations for every alias used in the tree onto the root element, escapes
text or wraps it in CDATA when it contains markup, and indents.

## The vocabulary: a tag registry as data

`dk.simongray.podcast.spec` describes every tag the library understands as a
map. One entry looks like this:

```clojure
{:tag     :podcast/transcript          ; the XML name
 :key     :podcast/transcripts         ; the key in the feed model
 :parent  #{:item}                     ; :channel, :item, :live-item or a tag
 :count   :many                        ; :one or :many
 :attrs   {:url      {:required true :type :url}
           :type     {:required true :type :mime}
           :language {:type :language}
           :rel      {:type :enum :values #{"captions"}}}
 :content :none                        ; type of the node text, or :none
 :doc     "Link to a transcript or closed-captions file."}
```

Three interpreters read the registry:

- `feed/parse` maps Hiccup to the model. For a child element it looks up the
  entry by tag and parent, coerces attributes and content by type, recurses
  into declared children, and stores the result under `:key`, in a vector when
  `:count` is `:many`. Elements with no entry are kept under `:xml/unknown`.
- `feed/emit` does the reverse in registry order, formatting coerced values
  back into their canonical string forms and appending `:xml/unknown` verbatim.
- `validate/validate` checks required attributes, enumerations, cardinality,
  parents and deprecation, and optionally a *profile* (PSP-1, Apple) that adds
  required-element rules on top.

Spec churn becomes a data change. `podcast:images` is one entry marked
`:deprecated :podcast/image`; a new attribute is one line. Two more things
are generated from the registry so that they cannot drift from the code:
`doc/tags.md`, the reference of every element, written by `dev/reference.clj`;
and `dk.simongray.podcast.schema`, Malli schemas of the model as plain data
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
of `itunes:category`, which it mirrors in every way.

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
`podcast:updateFrequency`, evaluated by `time/occurrences`), `locked?`,
`new-feed-url`, `self-url` and `categories`. Each docstring states its
precedence. The two-argument forms
take the feed and an item and apply the inheritance the specifications
describe: people on an item replace the feed's entirely, a value block on an
item overrides, artwork and funding fall back to the channel. `transcript`
takes options instead, since a client's preferences (types, languages, a need
for timings) are its own; with the feed as well, a reference that names no
language is taken to be in the feed's. Nothing here is stored or cached; it
is all derived on each call, and the reference app renders its item rows
through it so that the corpus exercises it.

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
the default set; a caller can pass a subset or none. Each application is
recorded in the report attached to the result, so a feed's distance from the
standards is measurable and the reference app can display it.

## Reports

Parsing returns the feed map; the report travels as metadata under
`:podcast/report` and is read with `podcast/report`. The report is a vector of
diagnostics, each a map with `:id`, `:level` (`:info`, `:warning`, `:error`),
`:message` and, where it applies, `:path` into the model. Quirk applications,
coercion failures and unknown namespaces all land here. Validation
diagnostics have the same shape so the reference app can show one list.
Every id the library raises is catalogued with its level and meaning in
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
  Simple Chapters (`psc:`) elements that six of the hosts in the corpus still
  embed, normalized to the same chapter shape.
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
  two thirds of descriptions in the wild carry markup and all of it comes
  from a vocabulary of some thirty elements: paragraphs, breaks, links,
  emphasis, lists, headings, images, the odd table, unclosed paragraphs and
  list items, pasted word-processor tags. No HTML parser dependency, so the
  JVM and JavaScript agree. `sanitize` keeps the elements, attributes and URL
  schemes a client may render and drops scripts and their kin; `hiccup`
  parses and sanitizes, or turns a plain-text description into paragraphs;
  `text` and `markdown` render the same Hiccup; `emit` writes it back as
  HTML. Hiccup is the centre because a Clojure client hands it straight to
  Replicant, Reagent or Rum.
- **Value** (`value`): the effective value block for an item (item overrides
  channel), share and fee arithmetic, and the recipients active at a
  playback position given `valueTimeSplit`s. Pure arithmetic; no payments.
- **Podcast Index** (`index`): a request builder that produces
  `{:method :url :headers}` with the SHA-1 auth header computed, so any HTTP
  client can send it, and a response reader that maps the index's feed,
  episode and value objects onto the model, so that a feed from the index
  has the same keys as one parsed from RSS and the accessors in `resolve`
  work on both; what only the index knows is under `:index/*`, the original
  under `:index/raw`. The endpoint table follows the API's OpenAPI
  description. Remote items in value time splits are resolved by asking for
  the item's own block first and the feed's second; `remote-value` takes the
  sending function as an argument, so it is tested with stubs and works with
  any client, and `value/with-remote` folds the answer into the recipients
  of the moment. `remote-item-requests` and `remote-item` resolve any remote
  item, of a podroll, publisher, list or time split, to the feed and episode
  the index holds. On the JVM, `call!`, `remote-value!` and `remote-item!`
  use the library's own HTTP client; in ClojureScript `call!` uses `fetch`
  and returns a promise. The key and secret never leave the caller's
  process.

## The player state

A podcast app's own logic is mostly the same in every app: a play queue,
where the listener left each episode and which ones count as played,
subscriptions and their settings, and keeping several devices in step.
`dk.simongray.podcast.player` holds that as one state map and pure
operations over it, so that a client writes its UI and nothing else. The
rules of the library hold: no storage, no clock, no media element. Every
operation takes the instant it happens and stamps what it changed with that
instant and the state's device name; the app persists the map however it
likes and calls in when the player emits events.

The state is the two documents of the survey's sync model in one map:
subscriptions by feed guid, with `default-settings` such as `:auto-queue?`
and `:skip-intro`, and removals kept as tombstones; episodes by `[feed-guid
item-guid]`, with position, duration and the played mark that `progress`
sets within `played-threshold` seconds of the end; and the queue as one
ordered entry. `merge` reconciles two devices by letting the later entry
win, the queue whole, which is commutative, associative and idempotent (a
generative test says so), so that any backend that can store and fetch a
document can sync: the backends stay outside the library. `refresh` feeds a
fresh parse through `feed/diff` and queues the new episodes of feeds set to
auto-queue; `to-opml` and `from-opml` bridge to the `opml` namespace with the
feed guid carried in each outline. Listener `preferences`, the skip amounts
and the playback rate, are one more merged entry, and `skip` moves within an
episode without leaving it. `step` is a small reducer over the player's
events, `:progress`, `:ended`, `:play` and `:skip`, returning the new state
and the effects the UI should act on, such as playing the next episode or
seeking to the skipped-to position; it is deliberately the only place where
behaviour is composed, and it is short. The inspector carries a small real
player over it, in ClojureScript: the state lives in the browser in
localStorage, Replicant renders it into a bar on every page, and an audio
element there reports its position through `step`, whose effects seek it
where a skip landed or start the next episode when one ends, so that the
effects can be heard rather than read. Clicking a link fetches the next page
and swaps only the content under the bar, the way Turbo keeps a permanent
element alive, so playback survives moving between pages without the
inspector becoming a single-page application; the queue page is the
detailed view of the same state. The server only supplies episode data. It
is also the library's first browser client, so the ClojureScript build earns
its keep.

## Platform adapters

| Concern | JVM | JS |
|---|---|---|
| XML in | `javax.xml.stream`, namespace-aware off, DTD off | `js/DOMParser`: the browser's, or one the host installs |
| XML in, leniently | jsoup's XML mode, when the optional jsoup is present | none yet |
| JSON | `clojure.data.json` | `js/JSON` |
| SHA-1 (guid, index auth) | `java.security.MessageDigest` | `goog.crypt.Sha1` |
| Instants | `java.util.Date` via millis | `js/Date` via millis |
| HTTP | `java.net.http` (`http` namespace) | left to the client |

The date parser, the duration parser and the UUIDv5 computation are written
once in `.cljc` from arithmetic and regular expressions rather than from
`java.time` or `js-joda`, so they behave identically everywhere.

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

## The reference web app

`web/dk/simongray/podcast/web/` is a small Pedestal service that renders HTML
with Replicant's string renderer. It fetches a feed URL (or one of the corpus
feeds), parses it, and shows the channel, the items, every recognized tag, the
unknown elements, the report, the validation diagnostics against a chosen profile
and the round-tripped XML. It is the first client of the library and exists
to test it; it touches nothing but the public API.

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
  values that would not coerce, and round-trip changes. The first sweep, of
  554 feeds on 2026-09-25, had no parse failures; it turned up the registry
  gaps, quirks and emitter bugs fixed alongside it, each with a test.
- The inspector's routes are tested through Pedestal's interceptor chain
  with the network stubbed, so that the reference app cannot break silently.
- The reference app's item page fetches an item's chapters and transcript and
  renders them, so that the companions are exercised on real files whenever a
  feed is inspected.

## Roadmap

1. Core: `xml`, `time`, `guid`, `spec`, `feed`, `quirks`, `validate`. Done.
2. Companions: `chapters`, `transcript`, `opml`, `value`, `index`. Done.
3. Reference web app and corpus tooling. Done.
4. ClojureScript build and browser test of the `DOMParser` adapter. Done.
5. Lenient XML adapter (jsoup) for feeds that are not well-formed at all. Done.
6. `resolve`: accessors with precedence rules; `feed/diff`. Done.
7. Real-world chapter and transcript files as a second corpus. Done.
8. Generated tag reference; Malli schemas from the registry. Done.
9. Podcast Index: sending requests and mapping responses into the model. Done.
10. Podping consumer; sync model. Deferred until a crawler or an app needs them.
11. Hardening: a sweep of several hundred indexed feeds, with the registry,
    quirks and emitter fixes it turned up. Done.
12. The small correctness items the sweep left: emit speed, diagnostics
    summaries, live items in `feed/diff`, structured attribute values, the
    accessors still missing, remote items through the index. Done.
13. An API review (optional behaviour as options maps, the diagnostics
    catalogue, a lint pass) and the release scaffolding: license, build,
    CI, changelog. Done, up to the deploy itself; the first published
    version will be a complete 1.0.
14. Embedded HTML as Hiccup, sanitized, and as text and Markdown. Done.
15. The player state: queue, progress, subscriptions, OPML, merge and the
    reducer, which is also the pure core of the sync model of item 10.
    Done.
