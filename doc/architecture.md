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
  companions     │ chapters     │ transcript   │ opml   value     │  formats and derived views
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
`:deprecated-by :podcast/image`; a new attribute is one line. The registry is
also the natural source for generated documentation and, later, for Malli
schemas.

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
- **Repeatable elements are vectors** under a plural key
  (`:podcast/transcripts`, `:podcast/people`, `:itunes/categories`,
  `:rss/items`, `:podcast/live-items`).
- **Coercion never throws.** A value that cannot be coerced stays a string and
  the report gets a finding.
- **Nothing is dropped.** Unknown elements are Hiccup under `:xml/unknown`;
  unknown attributes stay in the element's map as strings.

Identity is a function over the model, not a field: `guid/feed-guid` returns
`:podcast/guid` or the UUIDv5 computed from the feed URL; `guid/item-guid`
returns the item's `<guid>`, falling back to the enclosure URL.

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

Every quirk has an id, a severity and a one-line description. `parse` applies
the default set; a caller can pass a subset or none. Each application is
recorded in the report attached to the result, so a feed's distance from the
standards is measurable and the reference app can display it.

## Reports

Parsing returns the feed map; the report travels as metadata under
`:podcast/report` and is read with `podcast/report`. The report is a vector of
findings, each a map with `:id`, `:level` (`:info`, `:warning`, `:error`),
`:message` and, where it applies, `:path` into the model. Quirk applications,
coercion failures and unknown namespaces all land here. Validation findings
have the same shape so the reference app can show one list.

## Companion formats

- **Chapters** (`chapters`): JSON Chapters 1.2 parse and emit, and the Podlove
  Simple Chapters (`psc:`) elements that six of the hosts in the corpus still
  embed, normalized to the same chapter shape.
- **Transcripts** (`transcript`): SRT, WebVTT (with `<v>` speaker spans), JSON
  segments and the constrained HTML form, all read into one segment model;
  VTT, SRT and JSON emitted from it.
- **OPML** (`opml`): subscription lists in and out, folders flattened to a
  path.
- **Value** (`value`): the effective value block for an item (item overrides
  channel), share and fee arithmetic, and the recipients active at a
  playback position given `valueTimeSplit`s. Pure arithmetic; no payments.
- **Podcast Index** (`index`): a request builder that produces
  `{:method :url :headers}` with the SHA-1 auth header computed, so any HTTP
  client can send it. The key and secret never leave the caller's process.

## Platform adapters

| Concern | JVM | JS |
|---|---|---|
| XML in | `javax.xml.stream`, namespace-aware off, DTD off | `DOMParser` |
| JSON | `clojure.data.json` | `js/JSON` |
| SHA-1 (guid, index auth) | `java.security.MessageDigest` | `goog.crypt.Sha1` |
| Instants | `java.util.Date` via millis | `js/Date` via millis |
| HTTP | `java.net.http` (`http` namespace) | left to the client |

The date parser, the duration parser and the UUIDv5 computation are written
once in `.cljc` from arithmetic and regular expressions rather than from
`java.time` or `js-joda`, so they behave identically everywhere.

## The reference web app

`web/dk/simongray/podcast/web/` is a small Pedestal service that renders HTML
with Replicant's string renderer. It fetches a feed URL (or one of the corpus
feeds), parses it, and shows the channel, the items, every recognized tag, the
unknown elements, the report, the validation findings against a chosen profile
and the round-tripped XML. It is the first client of the library and exists
to test it; it touches nothing but the public API.

## Testing

- Unit tests per namespace with `clojure.test`; the spec repository's
  `example.xml` and companion examples are fixtures (CC0).
- Hand-written fixture feeds, one quirk each, with the expected report.
- A corpus of real feeds from some thirty hosts in `dev-resources/corpus/`,
  outside git, recreated by `dev/corpus.clj`. A corpus test parses every feed,
  asserts no exceptions, round-trips it, and snapshots the tag inventory and
  report so that changes to the registry or the quirks show up as diffs.

## Roadmap

1. Core: `xml`, `time`, `guid`, `spec`, `feed`, `quirks`, `validate`.
2. Companions: `chapters`, `transcript`, `opml`, `value`, `index`.
3. Reference web app and corpus tooling.
4. ClojureScript build and browser test of the `DOMParser` adapter.
5. Lenient XML adapter (jsoup) for feeds that are not well-formed at all.
6. Malli schemas generated from the registry; Podping consumer; sync model.
