# Architecture

This page explains the design of podcast-clj and the reasons for it.
[Namespaces](namespaces.md) gives the conventions of the API, and [The feed
model](tags.md) gives the rules of the model. [Syncing](sync.md) explains
the design of the sync between devices. The
[survey](podcasting-2.0-clojure-survey.md) from September 2026 describes the
ecosystem that podcast-clj was written for.


## Goals

1. **A library, not a framework.** podcast-clj is pure functions over plain
   Clojure data, with no global state. It does not decide how an app stores
   data, plays media or draws its user interface.
2. **All of the feed stack.** podcast-clj reads RSS 2.0, the iTunes
   namespace, all formalized tags of the `podcast` namespace, and the
   companion formats. It reads the other namespaces of real feeds (`atom`,
   `content`, `dc`, `sy`, `media`, `psc`, `rawvoice`, `googleplay`,
   `spotify`) as far as a podcast app needs them, and it keeps the rest.
3. **Standards first, quirks apart.** podcast-clj implements each standard
   as specified. Each accommodation for feeds that break a standard is a
   named quirk. A quirk can be switched off, and each use of a quirk goes
   into a report. An app can see how far a feed is from the standards, and
   the strict path stays strict.
4. **One code base for all platforms.** Most code is `.cljc` and free of
   platform APIs. The platform APIs are in small adapters, for XML, JSON,
   SHA-1, deferred values, encryption and HTTP. Only the lenient XML parser,
   the folder store and the WebDAV server are for the JVM alone.
5. **No silent loss of content.** podcast-clj keeps what it does not
   understand, as Hiccup, so `emit` writes back the elements that `parse`
   read. When a rule drops something, such as the repeat of an element that
   the specification allows once, the report tells so. Only comments and
   processing instructions go without a report.
6. **Tests against real feeds.** The inspector and a corpus of real feeds
   test podcast-clj against reality. The fixtures are a conformance suite,
   which the ecosystem does not have otherwise.


## Layers

```
  4  front door   dk.simongray.podcast
  3  companions   format.*  value  resolve  directory  directory.podcastindex  podping  app.*
  2  feed model   feed  feed.coerce  feed.validate  feed.schema
  1  vocabulary   spec  quirks
  0  primitives   xml  xml.jsoup  time  time.rrule  guid  json  http  rank  async  diagnostics
```

A namespace requires only namespaces in its own layer or in a lower layer.
`spec` knows nothing about parsing, and `feed` knows nothing about HTTP. The
companion formats need only the primitives and each other, not `feed` or
`quirks`. What they need of XML, such as the Hiccup accessors and the
decoding of entities, is in `xml`. `architecture_test.clj` reads the `ns`
forms and makes sure that these rules hold and that there are no cycles.

`xml.jsoup`, `app.sync.file` and `app.sync.webdav.server` run only on the
JVM, and so do some of the functions whose names end in `!`.

The inspector is outside the library, in the source path `web/`, with its
own namespaces, `inspector.*`. The tools for the corpus and for the
generated reference are in `dev/`. As a result, the jar of podcast-clj does
not contain Pedestal, and the namespace tree shows what is library and what
is example.

The names follow the design where one part belongs to another:

- `xml.jsoup` is under `xml`, and `time.rrule` is under `time`.
- `feed.coerce`, `feed.validate` and `feed.schema` are under `feed`.
  `feed.validate` and `feed.schema` interpret the tag registry for the
  model, and `feed.coerce` holds the value types that the registry uses.
- The companion formats are under `format`.
- `app` holds what an app keeps (`app.player`), finds (`app.search`) and
  syncs (`app.sync`). The stores are under `app.sync`.
- The Podcast Index client is under `directory`, because the Podcast Index
  is one of the directories.
- `resolve`, `value`, `guid`, `json`, `http`, `rank`, `async` and
  `diagnostics` belong to no group.


## Why the API is regular

The [conventions](namespaces.md#conventions) of the API have these reasons:

- **Lenient parsing, with a report.** Real feeds break the standards in many
  small ways, and an app must show them anyway. The report makes each
  accommodation visible, so the leniency hides nothing. A diagnostic is a
  problem that a host can correct, or information that an app can act on.
  The ordinary work of a parser, such as collapsing whitespace in HTML, is
  not a diagnostic.
- **Options maps.** A map can get new keys without a change for its callers.
  A positional flag cannot.
- **Time as an argument.** A function that does not read the clock gives the
  same result for the same arguments. This keeps the tests simple, and it
  lets the player state stamp its writes like a hybrid logical clock (see
  [Syncing](sync.md)).
- **Requests as maps.** A browser can send a request map with `fetch`, and
  the tests can run on recorded answers. The JVM client in `http` is one way
  to send the requests, not the only way.


## XML as Hiccup

`xml` reads XML into Hiccup and writes Hiccup back as XML:

```clojure
[:podcast/transcript {:url "https://…/ep1.vtt" :type "text/vtt"}]
[:title {} "Episode 3"]
```

Each element is `[tag attrs & children]`, and the attribute map is always
there. Tags and attribute names are keywords. The namespace of a keyword is
the canonical alias of the namespace URI of the element: `:itunes/author`,
`:podcast/guid`, `:atom/link`. RSS 2.0 elements have no namespace: `:title`,
`:pubDate`. At this level, names keep their case from the XML.

podcast-clj resolves namespaces by URI, in pure code. The platform adapter
(StAX on the JVM, `DOMParser` in JavaScript) does not resolve namespaces. It
gives raw Hiccup, with the names as written and the `xmlns` declarations as
ordinary attributes. Then a pure function walks the tree, maps each URI to
its alias, and rewrites the names. This design has three advantages:

- The result is the same on each platform.
- The tests of namespace handling need no parser.
- The resolution can accept the errors of real feeds. Examples are a variant
  of a namespace URI, an undeclared prefix, and a default namespace on
  `<rss>`.

An element without a prefix is always RSS 2.0. Several large hosts declare
`xmlns="http://www.w3.org/2005/Atom"` on `<rss>`. A namespace-aware parser
then reads `<title>` as an Atom element. podcast-clj ignores the default
namespace for element names, and reports it.

The parser drops comments and processing instructions, joins adjacent text
and CDATA, and removes text nodes that contain only whitespace. `emit`
declares each namespace that the tree uses on the root element. These are
the namespaces of podcast-clj, the foreign namespaces of a parsed feed, and
those in the `:namespaces` option. `emit` escapes text, or wraps text that
contains markup in CDATA, and it indents.


## The tag registry

`spec` describes each element that podcast-clj understands as a map:

```clojure
{:tag     :podcast/transcript          ; the XML name
 :key     :podcast/transcripts         ; the key in the feed model
 :parent  #{:item}                     ; :channel, :item or the tag of the parent
 :count   :many                        ; :one or :many
 :attrs   {:url      {:required true :type :url}
           :type     {:required true :type :enum :values transcript-types}
           :language {:type :language}
           :rel      {:type :enum :values #{"captions"}}}
 :content :none                        ; the type of the text, or :none
 :doc     "A transcript or closed-captions file."}
```

Three interpreters read the registry:

- `feed/parse` maps Hiccup to the model. For each child element, it finds
  the entry by tag and parent, coerces the attributes and the text by type,
  and reads the declared children. It stores the result under `:key`, in a
  vector if `:count` is `:many`. It keeps elements without an entry under
  `:xml/unknown`.
- `feed/emit` does the opposite, in the order of the registry. It writes
  each coerced value back in its canonical form, and appends `:xml/unknown`
  unchanged.
- `validate/validate` finds what breaks the specifications. Examples are
  missing required elements and attributes, values that do not read as their
  type or are outside an enumeration, unknown elements and deprecated
  elements. A profile (PSP-1 or Apple) adds rules for required and
  recommended elements.

The registry is a value. `spec/tags` is the vocabulary of podcast-clj as a
vector of entries. `spec/registry` indexes such a vector into the map that
the interpreters read, and `spec/default` is `spec/tags` indexed. Each
interpreter takes `:registry` in its options. As a result, the interpreters
treat the elements that a host defines like the standard elements. They
read, write and validate them, and give them schemas. This also works for a
new tag that podcast-clj does not know yet, without a fork. The registry of
podcast-clj itself can then stay strict about what it admits.

A change to a specification becomes a change to data. The deprecated
`podcast:images` is one entry, marked `:deprecated :podcast/image`. A new
attribute is one line.

Two artifacts come from the registry, so they cannot drift from the code:

- [The feed model](tags.md), the reference of all elements, which
  `dev/reference.clj` writes.
- The Malli schemas of the model in `feed.schema`, as plain data. Malli is
  not a dependency.

The schemas describe what `emit` accepts: the model with all values coerced
and all required attributes present. All corpus feeds match them. A
generative test builds feeds from the schemas, emits them and parses them
back. But `parse` is lenient, so it can return a map that does not match.
For example, a value can stay a string, or a required attribute can be
missing. `validate` tells why. The schemas are for the writing of feeds, and
the validator is for the judging of feeds.

Some elements stay outside the registry on purpose:

- elements in the `podcast` namespace that the specification does not
  define, such as the `podcast:hiveAccname` and `podcast:liveValue` of
  Hive-based hosts
- the proprietary namespaces of hosts

podcast-clj keeps these elements as Hiccup under `:xml/unknown`, and the
validator reports them. The specification is the boundary. A non-standard
element gets a quirk only when it stands for a standard element. For
example, an item's `image` element with only attributes becomes an
`itunes:image`. The nested `podcast:category` of Wavlake music feeds becomes
`itunes:category`, because it copies the shape of `itunes:category`.

The registry reads one element against the specification: a channel-level
`<author>`. RSS 2.0 does not define it, but a fifth of all feeds contain it.

RSS 2.0 is the only container. `feed/parse` refuses an Atom or RSS 1.0 root.
The directories require RSS 2.0, and in a live sample of 844 indexed feeds
on 2026-09-25, no feed was Atom.


## The feed model

The model is plain maps with namespaced keys, one namespace for each
vocabulary. The namespaces keep the vocabularies apart: `:rss/title` and
`:itunes/title` can both be present, and each keeps what the feed said. The
model is true to the feed. An app that wants one title uses `resolve` (see
the next section).

Unknown elements stay as Hiccup, and unknown attributes stay as strings. As
a result, `emit` can write back all that `parse` read.

A producer can write a shorthand. It can give a bare value for an element
with only text, such as `{:rss/guid "x"}`, or a single value for a vector.
`feed/normalize` expands the shorthand by the registry, and `emit` and
`validate` call it first. A producer can use the shorthand, while `parse`,
the accessors and the schemas know only one shape. The alternative was
schemas with `:or` for each such element. The model then has two shapes,
only to help the producer.

Identity is a function, not a field. `feed/feed-guid` gives the
`podcast:guid` of a feed, or else the UUIDv5 of its URL. `feed/item-guid`
gives the `<guid>` of an item, or else its enclosure URL. `feed/diff` uses
this identity to compare two parses of one feed. It gives the added, removed
and changed items, and the same for live items, so a show that goes live is
a change. It also tells if the channel changed in more than its items and
timestamps.

Validation does not throw on content. A value with a shape that its element
does not take is `:validate/wrong-shape`, a diagnostic like the others. A
profile that the validator does not know is a programming error, and it
throws.


## Resolving the vocabularies

The model keeps each vocabulary apart. This is correct for a round trip, but
an app usually wants one title, one image and one duration. `resolve`
settles the overlaps once, in pure functions over the model. The docstring
of each accessor gives its rule of precedence. `resolve` stores nothing and
caches nothing.

Where the specifications let an item inherit from its channel, the accessor
also takes the feed and the item, and applies the inheritance. People on an
item replace the people of the feed. A value block on an item overrides the
value block of the feed. Artwork and funding fall back to the channel.
`transcript` also takes options, because the preferred types, languages and
timings belong to the app.

`properties` collects all answers in one map with plain keys.
`(merge item (properties feed item))` holds the raw fields and the resolved
values together, for an app that needs both.

`sources` tells where each answer came from, as paths into the input:
`[:item :rss/title]` or `[:feed :itunes/explicit]`. It needs no table of
precedences. For each property, it calls the accessor on one field at a
time, and keeps the fields that alone give the same answer. As a result,
`sources` cannot disagree with the rule of an accessor. A property that
comes from a default, or from several fields together, has no source.


## Quirks and reports

`quirks` holds small, named functions in two groups:

1. **Repairs** of the XML text, for a new parse after the strict parse
   failed. They do these things:
   - remove a byte order mark, or other bytes before `<?xml`
   - replace the HTML entities that XML does not define, such as `&nbsp;`
   - remove control characters
   - escape bare ampersands
   - declare known prefixes that a feed uses without an `xmlns`
2. **Normalizations** of the tree, after parsing and before the mapping to
   the model. They accept these deviations, and more:
   - namespace URI variants: `http` for `https`, another case, a trailing
     slash, or the old GitHub URI of the `podcast` namespace
   - `yes`, `no` and `clean` in `<itunes:explicit>`
   - clock notation in `<itunes:duration>`
   - an `<itunes:image>` with `url` or text instead of `href`
   - dates that bend RFC 2822

[Diagnostics](diagnostics.md) lists all repairs and quirks, under `repair`
and `quirk`.

When a document is still malformed after the repairs, and `org.jsoup/jsoup`
is on the classpath, `xml.jsoup` reads it with the XML mode of jsoup. This
mode accepts any input: unquoted attributes, stray angle brackets, and
unclosed or mismatched tags all give a best-effort tree. The result is the
same raw Hiccup as that of the strict parser, so all later steps are shared.
jsoup is optional, and `parse` finds it with `requiring-resolve`. Without
jsoup, `parse` throws the error of the strict parser.

Elements can nest 256 levels deep, the value of `xml/max-depth`. A document
that nests deeper is malformed, with or without jsoup, on every JDK and in
JavaScript. So each walk of the tree stays in the stack of a thread of the
default size. JDK 24 limits the nesting to 100 levels by default, and older
JDKs have no limit. podcast-clj turns off the limit of the JDK and checks
the depth itself.

Each quirk has an id, a level and a one-line description. `parse` applies
all quirks. `{:quirks? false}` gives the strict path alone, and `:except`
leaves out the repairs and quirks that it names. The yes/no rule comes from
the registry in use, so an app's own yes/no elements get it too.

The report is a vector of diagnostics in the metadata of the result, under
`:podcast/report`. Each layer reports only what it did. The front door joins
the reports in order: namespace resolution and the quirks on the tree first,
then the mapping to the model. Metadata does not survive printing, transit
or `into {}`. An app that stores a parsed feed must store the report next to
it.

Transcripts and chapters documents have a report under the same key, which
`transcript/report` and `chapters/report` read. Validation diagnostics have
the same shape, so the inspector shows them in the same kind of table.

`diagnostics` lists every id with its level and meaning, and
`dev/reference.clj` writes this list to [Diagnostics](diagnostics.md). Apps
match on the ids, so the ids are part of the API. A test makes sure that the
list and the source agree.


## Companion formats

- **Chapters** (`format.chapters`) reads and writes JSON Chapters 1.2. It
  also reads Podlove Simple Chapters (`psc:`), which several hosts still put
  in their feeds, into the same shape. `from-text` reads a third source:
  lists of timestamps in show notes. More episodes have such a list than
  link a chapters file, so an app gets chapters for these episodes too. The
  report marks such chapters as inferred.
- **Transcripts** (`format.transcript`) reads SRT, WebVTT (with `<v>`
  speaker spans), JSON segments, the HTML form and plain text into one
  segment model. It writes VTT, SRT and JSON. Real files taught it some
  facts. Caption files name the speaker with a `Name:` or `[Name]:` prefix
  only when the speaker changes. podcast-clj carries the name over to the
  cues that follow. Some hosts write JSON times as strings. HTML transcripts
  contain layout whitespace, and some contain a whole SRT file in `<p>`
  elements. `turns` joins the words of a word-level JSON transcript into
  prose.
- **OPML** (`format.opml`) reads and writes subscription lists. Folders
  become a path.
- **HTML** (`format.html`) reads the show notes in descriptions into Hiccup.
  Most descriptions contain markup. It comes from a vocabulary of some
  thirty elements, such as paragraphs, breaks, links, emphasis, lists,
  headings and images. Some descriptions also contain tables, unclosed
  paragraphs and list items, or tags from word processors. A small lenient
  parser reads them the way that a browser does. Like a browser, it nests
  elements at most 512 levels deep, the value of `html/max-depth`. It has
  no dependency, so the JVM and JavaScript give the same result. `sanitize`
  keeps the elements, attributes and URL schemes that an app can render
  safely, and removes scripts and similar elements. Before it checks the
  scheme of a URL, it removes what a browser removes: tabs and line breaks
  anywhere, and control characters at the start. Hiccup is the center,
  because a Clojure app gives Hiccup directly to its renderer.
- **Value** (`value`) finds the value block that applies to an item, and
  divides amounts into shares and fees. With time splits, it finds the
  recipients at a position in the episode. It is arithmetic only, and it
  makes no payments. `boostagram` writes the bLIP-10 record that an app
  attaches to the keysend payment that it makes.


## The directories and the Podcast Index

`directory` puts the podcast directories behind one shape: Apple Podcasts,
fyyd and gpodder.net, which need no credentials, and the Podcast Index,
which does. Three endpoints cover what an app asks a directory: `:search` by
words, `:lookup` by the id of the source, and `:trending` for the chart.
Each source answers them as far as it can. The chart of Apple names no feed,
so a lookup by id follows.

A request is a map, so `directory` is `.cljc`. A browser builds the same
URLs, and it can send them if the CORS policy of the directory allows it. A
response becomes shows with the keys of a parsed feed: `:rss/title`,
`:itunes/author`, `:itunes/image`. The feed URL is the `atom:link` with
`rel="self"`, which `resolve/self-url` reads. `:podcast/guid` comes from the
source, or from the feed URL by the rule of the specification. This guid
makes the results from different sources comparable. What only a directory
knows is under `:directory/*`. This is the source, the id of the show there,
its page, counts, a chart rank and the raw record.

`combine` makes one entry per show from the answers of several sources. It
fills the record of the first source from the later ones, and
`:directory/sources` names each source with its id. In this way, a chart
entry gets the URL of its feed. On the JVM, `search!` and `trending!` ask
all sources at the same time. A source that fails becomes an entry under
`:failed`, not an exception.

Podchaser, Listen Notes and Taddy are not included. Each needs an account,
and the four sources above already cover discovery for an app without one.

`directory.podcastindex` builds requests with the SHA-1 authentication
header for an instant that the caller gives, so any HTTP client can send
them. It maps the feed, episode and value objects of the index onto the
model. As a result, a feed from the index has the same keys as a feed parsed
from RSS, and `resolve` works on both. What only the index knows is under
`:index/*`, and the original is under `:index/raw`. The table of endpoints
follows the OpenAPI description of the API.

A value time split can point to a remote item. `remote-value` asks for the
value block of the item first, and then for that of its feed. It takes the
function that sends the requests as an argument, so the tests use stubs and
any client works. `value/with-remote` adds the answer to the recipients of
the moment. `remote-item` resolves any remote item, of a podroll, a
publisher, a list or a time split, to the feed and the episode in the index.
The key and the secret never leave the process of the caller.


## Podping

Podping tells that a feed changed. A host announces the change through
podping.cloud, which writes a `custom_json` operation to the Hive
blockchain. Anyone can read the chain.

`podping` has the same shape as the directories. `request` builds the
JSON-RPC calls of a consumer as request maps. The calls ask for the head of
the chain, a range of blocks, the operations of one block, and the accounts
that write podpings. The public nodes allow any origin, so a browser can
send the requests too. `pings` reads the podpings from the blocks, in both
forms that a node writes an operation, including the `urls` and
`feed_update` of the first versions. It takes the medium and the reason from
the payload, or else from the id.

Anyone can write a `custom_json`, so `writers` reads which accounts can
write podpings: the accounts that the `podping` account follows on the
chain. This is how the Podping project publishes its writers.

On the JVM, `poll!` is the step that a poller repeats, like `fetch!` for a
feed. It reads the pings in the blocks after the last block read, a hundred
blocks to a request. It keeps the pings of the known writers, and returns
the other pings under `:refused`. It also returns the block to continue
from, and the writers. If a node leaves blocks out of its answer, the block
to continue from is the last block before the first gap, so the next step
reads the missing blocks again. If the caller gives the writers back,
`poll!` reads them only once. With `:up-to :irreversible`, it stops at the
last block that a fork cannot take back. `call!` tries the public nodes in
turn. An exception, a status other than 200, an answer that is not JSON and
a JSON-RPC error are failures of that node. If no node answers, the
exception names all of them.

`pinged?` and `affected` compare URLs the way that `podcast:guid` does. For
example, a ping for `http://…/feed/` matches a subscription stored as
`https://…/feed`. `notify-request` is for the host: one GET to podping.cloud
with the token of the host.

The Podping page of the inspector reads the last twenty blocks live. The
tests run on recorded answers, with a node that fails when the test tells it
to.


## The player state

Most of the logic of a podcast app is the same in every app. Each app has a
play queue, a position in each episode, played marks, subscriptions with
settings, and a sync between devices. `app.player` holds all of this as one
map, with pure functions over it. An app then writes only its user
interface.

The rules of podcast-clj apply: no storage, no clock and no media element.
Each function takes the instant of its change. It stamps what it changed
with the device id of the state and with that instant. If the instant is not
later than the latest instant in the state, it uses the next millisecond: a
hybrid logical clock in one rule. The app stores the map as it likes, and
calls in when the player sends events.

The map holds:

- the subscriptions by feed guid, with a tombstone for each removal
- the settings of each subscription, such as `:auto-queue?` and
  `:skip-intro`, one entry for each field
- the episodes by `[feed-guid item-guid]`, with position, duration and the
  played mark, which `progress` sets within `played-threshold` seconds of
  the end
- the queue, one entry for each episode, with a rank from `rank` that gives
  the order
- the preferences of the listener, such as the skip amounts and the playback
  rate, one entry for each field
- the device registry, with an entry for each device of the listener

`merge` reconciles two states entry by entry, and the later entry wins. The
merge is commutative, associative and idempotent, and a generative test
tests the three properties. As a result, any backend that can store and
fetch a document can sync the state. `app.sync` does this.

`refresh` gives a new parse to `feed/diff`, and queues the new episodes of
the subscriptions with `:auto-queue?`. `to-opml` and `from-opml` connect to
`format.opml`, and keep the feed guid in each outline. `skip` moves within
an episode, and never past its start or its end.

`step` is a small reducer over the events of a player: `:progress`,
`:ended`, `:play` and `:skip`. It returns the new state, and the effects for
the app, such as to play the next episode or to seek. `step` is the only
place where behavior is composed, and it is short on purpose.


## Search

`app.search` gives an app its lists of episodes, in two independent parts.

**Criteria and orders as data.** `where` makes a predicate of a feed and an
item from a map such as `{:season 2 :type "full" :status :unplayed}`.
`order` makes a sort key from a keyword such as `:newest`. `episodes`
applies both to one feed, or to a collection of feeds for an inbox of all
subscriptions. Each criterion reads the model through `resolve`, so it means
what the accessor means. For example, `:explicit?` inherits from the
channel, and `:chapters?` counts a chapters file, Podlove chapters and
timestamps in the notes. The criteria that depend on the listener, `:status`
and the `:recent` order, take the player state. Because the criteria are
data, a user interface can build them from a form, and the filter form of
the inspector does this. An unknown criterion or order throws, because it is
a programming error, not content to forgive.

**An index that is a value.** `index` and `add` build a map, `query`
searches it, and `remove` takes a document out. Nothing changes in place,
and nothing runs in the background. A document is `{:id :fields :stored}`.
`item-document` and `feed-document` build documents from the model, and an
app can build other documents, for example for a transcript that it fetched.

One rule analyzes text on both platforms: it lower-cases the words and folds
diacritics. A field or a query given as a vector of strings is not analyzed.
In this way, an app can bring its own stemming or segmentation, and
podcast-clj needs no language model. The postings keep positions, so a
quoted phrase is verified, not approximated. A sorted set of the terms is
next to the postings, so the last word of a query matches each term that
starts with it. Search as you type needs this.

The ranking is BM25 per field, summed with `boosts`. This scheme is simple
and well understood. `query` takes options for other needs:

- the weights of the fields, where a weight of zero leaves a field out
- the `k1` and `b` of BM25
- the shortest word to complete
- a `:rank` function that orders the results by something other than the
  score

`snippet` marks the matches in a text that the caller gives, because the
index does not keep the text.

Lucene was the JVM alternative, but it does not run in a browser. The index
of podcast-clj is a few hundred lines, runs wherever the model runs, and
prints as EDN.

The cost is that of an inverted index in persistent maps. On 36,000 items
with their show notes, indexing took five seconds and about 10 KB of heap
for each episode. A query took tens of milliseconds at most. A library of a
few thousand episodes needs tens of megabytes, and less than a second to
build. The show notes are nine tenths of the terms. An app with little
memory, for example in a browser, can index fewer fields: titles and people
alone cost a tenth. It can also index transcripts only for the episodes that
it downloaded.


## Syncing

`app.sync` keeps the devices of one listener in step, and [Syncing](sync.md)
explains its design. The merge of the player state is a join, so a backend
only has to hold documents. With the default strategy, each device writes
its whole state as `<device-id>.edn`, and reads and merges the documents of
the other devices. Two devices never write the same document, so a folder
that a sync client mirrors is a complete backend. A store that cannot list
holds one shared document instead, which each device puts on the condition
that its version did not change.

`app.sync` requires `app.player` and `async`. `app.sync.store` also requires
`format.opml`, for the subscription list that it can write. `app.sync.file`,
`app.sync.webdav` and `app.sync.encrypted` are stores over `app.sync.store`.
`app.sync.webdav.server` uses the WebDAV store to serve any store over
WebDAV. Only the namespaces under `app.sync` require `app.sync`.


## Platforms

| Concern | JVM | JavaScript |
|---|---|---|
| XML in | `javax.xml.stream`, not namespace-aware, no DTDs | the `js/DOMParser` of the browser, or one that the host installs |
| Lenient XML in | the XML mode of jsoup, if jsoup is present | none |
| JSON | `clojure.data.json` | `js/JSON` |
| SHA-1 (guid, Podcast Index) | `java.security.MessageDigest` | `goog.crypt.Sha1` |
| Instants | `java.util.Date` | `js/Date` |
| Deferred values (`async`) | futures | promises |
| HTTP | `java.net.http`, in `http` | `fetch`, in the functions that send requests |
| Encryption (`app.sync.encrypted`) | `javax.crypto` | WebCrypto |

The parsers of dates and durations and the UUIDv5 computation are written
once, in `.cljc`, from arithmetic and regular expressions. They do not use
`java.time` or `js-joda`, so they give the same result everywhere.

The JavaScript adapter uses only the standard DOM API (`parseFromString`,
`getElementsByTagName`, `item`, `nodeName`, `nodeValue`), so any DOMParser
works. A browser reports a malformed document with a `parsererror` element.
Other implementations throw, or return a document without a root.
podcast-clj turns all three into the same
`:dk.simongray.podcast.xml/malformed` exception. In Node, the test setup
installs `@xmldom/xmldom` as `js/DOMParser`, and configures it to throw on
each error that a browser reports.

Two differences between the platforms shaped the code. First, a DOMParser is
namespace-aware, and it refuses an undeclared prefix that the JVM parser
accepts. The repair that declares prefixes is for this case. Second,
`str/replace-first` in ClojureScript does not take a replacement function
the way that Clojure does, so the repairs do not use it.

On the JVM, the front door adds `fetch!`, the step that a poller repeats. It
sends the conditional headers, and returns a parsed feed or a 304, with the
validators for the next fetch and the XML itself. The URL in the result is
the final URL after redirects. If the feed declares a new URL,
`:new-feed-url` gives it.


## The inspector

The inspector in `web/inspector/` is a small Pedestal service that renders
HTML with the string renderer of Replicant. It is the first client of
podcast-clj, and it exists to test the library. It uses only the public API.

Give it a feed URL, or select a corpus feed. It shows the channel, the
items, each known tag, the unknown elements, the report, the validation
against a profile, and the XML written back. Next to the raw fields, it
shows the resolved properties and their sources. The front page searches the
directories, and the Podping page reads the chain.

The queue page has a small player in ClojureScript, over `app.player`. The
player state is in localStorage, and Replicant renders it into a bar on
every page. The audio element reports its position through `step`, and the
effects of `step` seek the audio element or start the next episode. The
folder at `/dav/` is the sync backend that the player shares with any WebDAV
client. A click on a link fetches the next page and replaces only the
content below the bar. As a result, playback continues across pages, but the
inspector is not a single-page app.

The inspector serves its folder at `/dav/` through the `inspector.dav`
interceptor, for two reasons. The router of Pedestal does not match the
trailing slash of a collection. Also, the body-params interceptor of
Pedestal reads the body of a put before the handler can read it.


## Testing

- Most unit tests are `.cljc` files for `clojure.test`. They run on the JVM,
  in Node and in a browser, through the `:node-test` and `:browser-test`
  builds of shadow-cljs. The `example.xml` feed and the companion examples
  of the `podcast` namespace repository are fixtures (CC0). The JavaScript
  builds put them into the code at compile time.
- Hand-written fixtures test the quirks, with the expected report. Small
  inline feeds test one quirk each. `quirky.xml` combines many quirks, and
  `broken.xml` is malformed in several ways.
- A corpus of real feeds from some thirty hosts is in
  `dev-resources/corpus/`, outside Git. `dev/corpus.clj` downloads it again.
  The corpus test parses each feed, makes sure that nothing throws, and does
  a round trip and a validation.
- A second corpus, in `dev-resources/corpus/companions/`, holds the chapters
  and transcript files that those feeds link to: two per host and type, with
  a manifest. `corpus/sweep-companions` parses all of them. It counts
  segments and speakers, and compares the detected format with the declared
  one. Each quirk that it found has a synthetic fixture in the tests.
- A third corpus, in `dev-resources/corpus/sweep/`, holds a few hundred
  feeds that `corpus/sweep-populations` samples through the Podcast Index.
  The sample has recently updated feeds, new feeds, feeds with value blocks,
  and feeds of each medium other than podcast. `corpus/sweep-report` parses
  them all. It counts parse errors, diagnostics, unknown elements, values
  that do not coerce, and changes in a round trip, by the number of feeds.
  These sweeps found gaps in the registry, quirks and emitter bugs. Each
  correction came with a test.
- Generative tests cover these properties:
  - the three properties of the merge
  - the order of the ranks
  - the convergence of several devices that sync in random order, through
    both strategies
  - the round trip of the feeds that the Malli schemas generate
- The routes of the inspector are tested through the interceptor chain of
  Pedestal, with the network stubbed. The WebDAV folder of the inspector is
  also a real server for the test of the WebDAV store.
- The browser player of the inspector has two namespaces. The pure views
  turn the page state into Hiccup, with event handlers as data. Their tests
  compare Hiccup, in Node and in the browser build. The shell does the
  events. Its test runs on jsdom in Node: it mounts the player, loads an
  episode through a stubbed fetch, and clicks the buttons. The names of
  tests that need Node end in `-node-test`, and the browser build leaves
  them out.
- `architecture_test.clj` reads each `ns` form under `src/` and places each
  namespace in its layer. It fails in these cases:
  - a namespace has no layer
  - a require points to a higher layer
  - the graph of requires has a cycle
  - the registry requires anything
  - a companion format requires more than the primitives and the other
    formats
  - `xml` requires more than `diagnostics`
- CI runs two jobs. The first job runs all JVM tests, the Node tests and the
  linter, on current versions of Java and Clojure. The second job runs the
  library tests on Java 11 with Clojure 1.11. `deps.edn` and the pom declare
  Clojure 1.11 as the minimum, and `java.net.http` needs Java 11. The other
  aliases override Clojure to the current version, but `:test-floor` does
  not.


## Not built

- A backend for a service with its own model, gpodder first.
  [Syncing](sync.md#gpodder-for-later) has the design.
- Stores over the HTTP APIs of cloud services. Each is a short namespace
  against the store contract.
- A JSON form of the sync documents, for clients in other languages.
- A lenient XML parser for JavaScript.
- A folder store for Node.
- The release to Clojars.
