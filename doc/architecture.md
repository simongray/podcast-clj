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
  reference app  │ web/  inspector.*   (Pedestal)                 │  uses only the public API
                 └────────────────────────────────────────────────┘
                 ┌──────────────┬──────────────┬──────────────────┐
  companions     │ chapters     │ transcript   │ opml   value     │  formats, and the accessors
                 │ resolve  (one answer per property, by rule)    │  a client reads through
                 │ player (state)  search (criteria, index)  sync │  what an app keeps, finds and keeps in step
                 ├──────────────┴──────────────┴──────────────────┤
  feed           │ feed  (parse / emit interpreters)  validate    │  registry-driven
                 ├────────────────────────────────────────────────┤
  vocabulary     │ spec  (tag registry, enumerations)   quirks    │  data, and named repairs
                 ├────────────────────────────────────────────────┤
  primitives     │ xml  time  rrule  guid  json  rank  coerce     │  the only reader-conditionals
                 └────────────────────────────────────────────────┘
  jvm only       │ http  directory, podping (their ! functions)   │
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
stores under `sync`; and the Podcast Index client
under `directory`, since the index is one of the directories `directory`
puts behind one shape. `resolve`, `value`, `guid`, `json`, `http`, `rank` and
`diagnostics` belong to no group and stay at the top. A test reads the
`ns` forms and holds the layering to this.

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
- **Shorthand is normalized, not modelled.** A producer may write a bare
  value where an element with attributes carries only content,
  `{:rss/guid "x"}`, or a single value where the model has a vector.
  `feed/normalize` expands both by the registry, and `emit` and `validate`
  call it first, so the shorthand is taken everywhere the model is written
  while `parse`, the accessors and the schemas know one shape. The
  alternative, schemas with `:or` for every such element, would have made
  the model two-shaped for the sake of the writer.
- **Validation never throws.** A value of a shape its element does not
  take, a string where a map belongs or a map where a scalar does, is
  `:validate/wrong-shape`, a diagnostic like the rest.

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
of the recent items. Each docstring states its
precedence. The two-argument forms
take the feed and an item and apply the inheritance the specifications
describe: people on an item replace the feed's entirely, a value block on an
item overrides, artwork and funding fall back to the channel. `transcript`
takes options as well, since a client's preferences (types, languages, a need
for timings) are its own; given the feed, a reference that names no language
is taken to be in the feed's. Nothing here is stored or cached; it
is all derived on each call, and the reference app renders its item rows
through it so that the corpus exercises it.

`properties` gathers every answer into one map with plain keys, the show's
or, given the item as well, the episode's with the inheritance applied, so
that `(merge item (properties feed item))` holds the raw fields and the
resolved ones side by side for an app that needs both. `sources` says where
each answer came from, as paths into what was given, `[:item :rss/title]`
or `[:feed :itunes/explicit]`. It needs no table of precedences: for each
property it calls the accessor on one field at a time and keeps the fields
whose value alone gives the same answer, so it can never disagree with the
accessor's own rule. A property answered by a default, or put together
from several fields, names none. The inspector shows both tables on the
feed and item pages.

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
them all, and `{:quirks? false}` is the strict path alone; the yes/no rule is
derived from the registry in use, so a client's own yes/no elements get it
too. Each application is recorded in the report attached to the result, so a
feed's distance from the standards is measurable and the reference app can
display it.

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
the same shape so the reference app can show one list.
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
  embed, normalized to the same chapter shape. `from-text` reads the third
  source, the timestamp lists in show notes: in the sweep three times as
  many episodes list their chapters that way as link a chapters file, so an
  app gets chapters for them too, reported as inferred.
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
  playback position given `valueTimeSplit`s. Pure arithmetic; no payments,
  but `boostagram` writes the bLIP-10 record an app attaches to the keysend
  payment it makes.
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
`dk.simongray.podcast.app.player` holds that as one state map and pure
operations over it, so that a client writes its UI and nothing else. The
rules of the library hold: no storage, no clock, no media element. Every
operation takes the instant it happens and stamps what it changed with the
state's device id and that instant, or the millisecond after the latest
instant the state has seen when the given one is not later, a hybrid
logical clock in one line; the app persists the map however it likes and
calls in when the player emits events.

The state is the two documents of the survey's sync model in one map:
subscriptions by feed guid, with removals kept as tombstones and their
settings, such as `:auto-queue?` and `:skip-intro`, one entry per field;
episodes by `[feed-guid item-guid]`, with position, duration and the played
mark that `progress` sets within `played-threshold` seconds of the end; the
queue as one entry per episode carrying a rank from `rank`, the order
derived from the ranks; and the listener's `preferences`, the skip amounts
and the playback rate, one entry per field. `merge` reconciles two devices
entry by entry, the later winning, which is commutative, associative and
idempotent (a generative test says so), so that any backend that can store
and fetch a document can sync, which `app.sync` does, below. `refresh`
feeds a fresh parse through `feed/diff` and queues the new episodes of
feeds set to auto-queue; `to-opml` and `from-opml` bridge to the `opml`
namespace with the feed guid carried in each outline, and `skip` moves
within an episode without leaving it. `step` is a small reducer over the player's
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

On the JVM the front door adds `fetch!`, the one step a poller repeats: the
conditional headers go out, a parsed feed or a 304 comes back with the
validators for next time, the URL is the final one after redirects, and the
feed's own new-feed-url is surfaced when it declares one.

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
- The reference app's item page fetches an item's chapters and transcript and
  renders them, so that the companions are exercised on real files whenever a
  feed is inspected.

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

The cost is that of an inverted index held as persistent maps, measured
on 60 sweep feeds with 36,000 items and 6 million terms, show notes
included: five seconds to index and 360 MB of JVM heap, roughly 10 KB an
episode; a query for the commonest word in English touches 24,000
documents in 50 ms, or 37 ms with a limit, and a prefix, a phrase or a
two-word query answers in 1 to 15 ms. A listener's library of a few
thousand episodes is therefore tens of megabytes and well under a second
to build. The notes are nine tenths of the terms, so an app short of
memory, in a browser say, indexes fewer fields, titles and people alone
costing a tenth, and adds transcripts only for the episodes it has
downloaded.

Those numbers came from profiling with clj-async-profiler and criterium,
both on the `:dev` alias, and each finding is kept in the code. The
postings are a hash map updated through a transient with a sorted set of
terms beside it, after a sorted map spent a quarter of the build
comparing strings and another quarter in garbage from its path copying.
One position is kept per term occurrence, a single one as a bare number,
and the term strings are shared between the postings and the documents'
term lists, which halved the footprint. Text is folded once per field,
decomposition serving as its own guard, since a regex scan meant to skip
the work cost more than the work. The scoring arithmetic is primitive,
checked with `:warn-on-boxed`. BM25 ties are frequent, so ties are broken
by the hash of the id rather than by printing it, which alone had taken
two seconds a query; and with a limit, the best results are kept in a
small sorted vector as candidates stream past, since a sorted set with a
comparator proved slower than sorting everything.

## The directories

`dk.simongray.podcast.directory` puts the podcast directories behind one
shape, the way `index` already did for the Podcast Index: Apple Podcasts,
fyyd and gpodder.net, which need no credentials, and the Podcast Index,
which does. Three endpoints cover what a client asks a directory for,
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
source at once, each on its own future, and a source that fails is a line
under `:failed` rather than an exception. The inspector's front page
searches the directories and inspects the feed it finds.

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
project publishes its writers, two dozen of them today.

On the JVM `poll!` is the poller's one step, like `fetch!` for a feed: the
pings in the blocks after the last one read, a hundred blocks to one
request, which a node answers in about a second, verified against the
writers read once and handed back, with the block to continue from and
the pings refused kept apart rather than dropped. It can stop at the last
irreversible block for a consumer that would rather not see a fork's
pings. `call!` tries the public nodes in turn, treating a status other
than 200 or a body that is not JSON as that node's failure, and names them
all when none answers. `pinged?` and `affected` compare URLs as
`podcast:guid` compares them, so a host that pings `http://…/feed/`
reaches a subscription stored as `https://…/feed`. `notify-request` is the
host's side, one GET to podping.cloud with the token it was issued.

The inspector's Podping page reads the last twenty blocks live, which is
how the consumer is exercised against the real chain; the tests run on
recorded answers, with a stand-in node that fails on cue.

## Syncing

`dk.simongray.podcast.app.sync` keeps one listener's devices in step, on
the design in `sync.md`, whose reasoning is not repeated here. The merge
being a join, a backend only has to hold documents: every device writes
its whole state, in an envelope naming the format version, the device and
when it was written, as `<device-id>.edn`, and reads the others' and
merges them, so that no two writers ever meet and a folder any sync client
mirrors is a complete backend. A store is a map of four functions over
named documents with version tokens, `fetch`, `put`, `list` and `delete`,
its credentials closed over in its constructor and never in the map, so
that wrapping one is updating a key and no store prints a token.
`store/backend` turns any store into a backend for `sync!` by one of two
strategies: a document per device, or, for a store that cannot list, one
shared document put on condition of the version fetched and merged again
on a conflict. `sync!` is one step over any number of backends: pull from
each, merge into the local state, push the merged state to each, and hand
back a cursor per backend, the versions seen and a hash of what was
written, so that the next step fetches only what changed and writes only
when something did. The state is never replaced, only merged into; a
backend that fails is a line under `:failed`; and the step reports what it
found, an unreadable document, one from a newer library, a device whose
clock runs ahead, the device's own document changed under it, its
retirement, or a shared document that would not stop changing. On the JVM
the backends are asked at once on futures; in ClojureScript `sync!`
returns a promise and a store's functions may return values or promises,
so a store over localStorage and one over fetch are written the same way.
The memory store is the engine's test double, and the folder store,
`app.sync.file`, covers iCloud Drive, Dropbox, Drive, Seafile, Syncthing
and a Git working tree by writing each file whole and moving it into
place; the stores over the HTTP APIs and the gpodder services follow 1.0,
one namespace each.

The player state gained what syncing needs and what would have been
painful to change once states are persisted. The device is a stable id,
generated once per installation, and the devices describe themselves in a
registry, so that a UI lists them and the listener retires a lost one, a
tombstone the device cannot undo by coming back. The state keeps a clock,
the latest instant it has written or merged, and every write stamps with
the given instant or the millisecond after the clock when that is not
later: a hybrid logical clock that keeps a device with a slow clock from
being overruled by what it has already merged, which the convergence
property found necessary before any argument did, a device writing at an
instant no later than an entry it had merged keeping its own entry while
every other device kept the other. And `merge` goes by the shape of each
value, a map of stamped entries, one stamped entry or an instant, so that
an app's own entries sync as the library's do. Three devices applying
random operations and syncing in random orders through either strategy
end in the same state, which is the merge's three properties carried
through the engine.

The state is a state-based CRDT of the simplest kind, and naming its parts
says what merges how: every stamped entry is a last-writer-wins register, a
map of them by id or by field an add-and-remove set with tombstones, the
clock a max register, and `merge` the join. Two places were coarser than
that and were reworked before 1.0, since both change the stored format. The
queue was one register, the later queue winning whole, so that two devices
enqueuing while apart lost one side's additions, and an episode finished on
the phone came back from a laptop that had not heard. It is now one entry
per episode with a rank from `rank`, strings in base 62 that always leave
room for another between two and stay short at the ends, the order derived
by rank and then by stamp, removals as tombstones: concurrent additions both
survive, a finished episode stays gone, and only two devices moving the same
episode at once need a winner. Preferences and a subscription's settings
were one register each, so that the skip amount changed on one device and
the rate on another lost one; both are now one entry per field, behind the
same accessors, and a subscription's settings live apart from it so that
they outlive an unsubscription and come back with the feed.

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
10. Podping consumer, done as item 23; the sync engine, whose pure core is
    `app.player/merge`, is item 24.
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
16. The structural review before 1.0: the registry as a value every
    interpreter takes, so that clients extend the vocabulary without a
    fork; names and entity decoding moved down into `xml` so that the
    layering holds; each layer reporting only its own diagnostics. Done.
17. The surface review: a message on every diagnostic, from the catalogue
    where the parser records only an id; the transcript accessor's arities
    aligned with the rest of `resolve`; the plumbing hidden from the API
    documentation with `:no-doc`; `rrule` split out of `time`; the linter
    in CI and a test that keeps the generated documents current. Done.
18. Podcast-specific ergonomics the sweep ranked: chapters from the
    timestamp lists in show notes, `fetch!` as the poller's one step with
    `next-check` for when to return, `episodes` and `seasons` for the
    listening order, the boostagram record, `blocked?`, and an enclosure
    chooser with preferences. Done.
19. Finding episodes: criteria and orders as data over the model and the
    player state, and a full-text index over subscriptions with BM25,
    prefixes, phrases and snippets, on both platforms; the inspector's
    filter form and search pages. Done.
20. Model ergonomics: a producer's shorthand normalized by the registry
    and taken by `emit` and `validate`, a validator that diagnoses wrong
    shapes instead of throwing, and `resolve/properties` with `sources`
    for the raw and the resolved side by side. Done.
21. The directories behind one shape: Apple Podcasts, fyyd, gpodder.net and
    the Podcast Index, searched, looked up and their charts read, combined
    into one entry per show, with recorded answers as fixtures. Done.
22. Trust before 1.0: the player's views as data with the shell tested on
    jsdom, the architecture as a test, the Clojure and Java floor declared
    and verified in CI beside the current versions, the dependency bumps,
    and the guide from finding a show to playing it. Done.
23. The Podping consumer: the Hive calls as request maps, pings read out of
    a block, writers verified as the accounts podping follows, `poll!` as the poller's
    step, and the host's announcement; the HTTP client made cross-platform
    for the request-building half and given POST. Done.
24. Syncing: the design in `sync.md`, then its foundation: the engine over
    the player state, stores as maps of functions holding one document per
    device or one shared, the memory and folder stores, and the player
    state given device ids, a registry, a clock and a merge by shape, then
    the queue as ranked entries and the settings field by field. Done; the
    stores over the HTTP APIs and the gpodder services follow 1.0.
