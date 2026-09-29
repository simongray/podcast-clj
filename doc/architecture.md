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
  3  companions   format.*  value  value.*  resolve  check  directory  directory.podcastindex  links  podping  websub  nostr  comments  comments.*  analytics  analytics.*  app.*
  2  feed model   feed  feed.coerce  feed.validate  feed.schema
  1  vocabulary   spec  quirks
  0  primitives   xml  xml.jsoup  time  time.rrule  guid  json  http  bytes  bech32  secp256k1  rank  async  diagnostics
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
- `app` holds what an app keeps (`app.player`), finds (`app.search`),
  downloads (`app.downloads`) and syncs (`app.sync`). The stores are under
  `app.sync`.
- The Podcast Index client is under `directory`, because the Podcast Index
  is one of the directories.
- The readers of ActivityPub, of the AT Protocol and of Nostr are under
  `comments`, with what they share in `comments.common`.
- The payment of Lightning addresses and the RSS Payment Metadata are under
  `value`, which holds the payment that they send.
- The client of OP3 is under `analytics`, which counts downloads as OP3
  does.
- `resolve`, `check`, `links`, `podping`, `websub`, `nostr`, `guid`,
  `json`, `http`, `bytes`, `bech32`, `secp256k1`, `rank`, `async` and
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
- **A call throws, and a step gives data.** A call has one answer to
  give. Without the answer it has nothing to return, so it throws. A step
  does more than one thing, and a part of it can fail while the rest has
  a result. A poll keeps the pings that it read, and a check keeps the
  facts of the other files. A failure that is thrown is an exception on
  the JVM, and a rejected promise in ClojureScript. Only a failure that is
  data has one shape for code that runs on both platforms. The failure is
  a diagnostic, so an app handles it as it handles a report.
- **Services as defaults.** A service can close, change its owner, or get
  a copy at another address. A directory can change its rules. The app
  then gives the new address or the new rules as data, and it needs no new
  version of podcast-clj. A fact that a specification defines does not
  change with a service, so it is a constant in the code. [Services and
  their defaults](services.md) lists the defaults.


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
and CDATA, and removes text nodes that contain only whitespace. It keeps a
space between two elements on one line, because inline markup needs it:
`<b>Hello</b> <i>world</i>`. Markup or text after the root element makes
the document malformed, on the JVM as in a browser. `emit` declares each
namespace that the tree uses on the root element. These are the namespaces
of podcast-clj, the foreign namespaces of a parsed feed, and those in the
`:namespaces` option, which win over those of podcast-clj for the same
alias. `emit` escapes text, or wraps text that contains
markup in CDATA. It indents, but writes mixed content as it is, because
indentation would become part of its text.

A no-break space is whitespace on both platforms. The trim of Java keeps
it and the trim of JavaScript removes it, so podcast-clj trims with a
function of its own.


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
  unchanged. A nil value is no value. A value of another type goes out as
  it is, and `validate` reports it.
- `validate/validate` finds what breaks the specifications. Examples are
  missing required elements and attributes, values that are not of their
  type or are outside an enumeration, negative numbers, keys that the
  registry does not define, such as `:rss/titel`, unknown elements and
  deprecated elements. A profile (PSP-1 or Apple) adds rules for required
  and recommended elements.

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
a result, `emit` can write back all that `parse` read, with two exceptions,
which the report names. The model has no place for the attributes of an
element that it reads as one value, or for those of `<rss>` and
`<channel>`. The parser of the JVM accepts a prefix that no declaration
names, but XML cannot write such a prefix without a URI, so `emit` leaves
its elements out.

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

A host with a long feed serves it in more than one document. `feed/pages`
makes the documents, in the two kinds of RFC 5005, and `feed/join-pages`
is its inverse. The pages of a paged feed change with each new item,
because each item moves one place. An archived feed has a subscription
document with the newest items, and archives that do not change. So a
reader fetches each archive once, and a server can cache it for good.
`pages` counts the items of an archive from the oldest item. An archive
is a document only when the feed has a newer item than its items, so that
no archive gets an item later. The subscription document holds the newest
items, and some of them are in the newest archive too. `join-pages` leaves
out an item that it has. RFC 5005 marks a document with an empty element,
`fh:complete` or `fh:archive`. The registry has the type `:presence` for
such an element, which is true because it is there.

`feed/for-subscriber` makes the private feed of one subscriber. The token
of the subscriber goes on the URLs of the media files and on the links of
the feed to its own documents. The feed is blocked from the directories,
and it names no WebSub hub. The host makes the tokens, keeps them, and
checks them at each request. podcast-clj has no store for them.
`feed/update-media-urls` is the function under it, and under the prefix
of OP3.


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
timings belong to the app. The texts of `podcast:txt` do not fall back,
because most purposes are about the element that holds them. The purpose
`ai-content` is different: it works like `itunes:explicit`, and
`ai-content?` falls back as `explicit?` does.

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
   - remove a byte order mark, or other text before `<?xml` or `<rss>`,
     such as a PHP warning
   - remove text after `</rss>`
   - replace the HTML entities that XML does not define, such as `&nbsp;`
   - remove control characters, and the character references to them
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
  elements. A caption can quote a tag, so the format is read from the cue
  timings before the markup. `turns` joins the words of a word-level JSON
  transcript into prose.
- **OPML** (`format.opml`) reads and writes subscription lists. Folders
  become a path. The categories of an outline become a vector, and the
  time it was created and the dates of the list become instants, in the
  formats that OPML 2.0 defines. An outline that includes another list
  becomes an inclusion. `app.player` keeps the tags of a show in the
  categories, the time the listener subscribed in the time of creation,
  and the lists the listener follows in inclusions. In this way the
  listener's own data goes into the parts of OPML 2.0 that carry it,
  and other apps read the rest as they always did.
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
  anywhere, and control characters at the start. A script is text up to
  its end tag, as in a browser, and `text` leaves it out. A description is
  HTML only when it holds a tag or a comment, so plain text that mentions
  `<show@example.com>` stays text. Hiccup is the center, because a
  Clojure app gives Hiccup directly to its renderer.
- **Media files** (`format.media`, with `format.id3`, `format.mp3` and
  `format.mp4`) read what an MP3 or M4A file says about itself: its
  duration, its chapters and its artwork. The readers follow the standards.
  For MP3 files, these are ID3v2.2 to 2.4 with the ID3v2 Chapter Frame
  Addendum, and the MPEG audio frames with a Xing, Info or VBRI header. For
  M4A files, these are the boxes of ISO/IEC 14496-12, with a QuickTime
  chapter track or Nero chapters, and the index of a file written in
  fragments. Each quirk that real files taught the readers has an id, and
  `:except` leaves it out. Examples are a frame size in the form of the
  other ID3 version, and junk between the tag and the audio. The chapters
  have the shape of a JSON chapters document. Each reader keeps its report
  in the metadata of its result, and the `report` function of its
  namespace reads it. A reader takes a view of the
  parts of a file that have arrived (`bytes`). When it needs a part that
  has not arrived, it throws the ranges that it needs. `bytes/read!`
  fetches these ranges with the range requests of `http/fetcher`, and
  reads again. So the readers do no I/O, and one loop fetches with futures
  on the JVM and with promises in JavaScript. Most files need one or two
  requests and a few hundred kilobytes. From a server that ignores ranges,
  the fetcher reads only the start of the file that the reader needs. A
  host that inserts ads can send a different file to each request, so the
  duration is that of the file that came. When a request fails, the
  result of `read!` is the `:error` alone, the diagnostic
  `:file/unreachable`. `verify!` checks a file against
  the `podcast:integrity` of an alternate enclosure, by the rules of W3C
  Subresource Integrity. When it made no check, its verdict has an
  `:error` that tells why.
- **Images** (`format.image`) reads the header of a PNG, JPEG, GIF or WebP
  image: its type, its size in pixels, its color model and whether it can
  be transparent, and of a JPEG also its coding process and its EXIF
  orientation. It follows the W3C PNG specification, ITU-T T.81 with JFIF,
  and RFC 9649. It knows AVIF, HEIC, HEIF, SVG, BMP, TIFF and ICO images by
  their first bytes, and gives their type alone. The header is in the first
  bytes, so a check of artwork needs no image library, and `read!` fetches
  it through the same loop as the media readers. The first 64 KB hold it
  in most files. A JPEG whose frame comes after large segments, such as a
  color profile, takes a few more requests.
- **Value** (`value`) finds the value block that applies to an item, and
  divides amounts into shares and fees. With time splits, it finds the
  recipients at a position in the episode. It makes no payments. It writes
  and reads what goes with a payment, as [Value for value](#value-for-value)
  tells.


## Checks over the network

`validate` checks the data of a feed. `check` checks the files that the
feed links to, over the network: the media files, the images, the
transcripts, the chapters files and the feed itself. A host can run it
before it publishes, and the inspector shows it.

`check/files` lists what a check fetches: the files of the feed and of its
newest items, each once. `check/feed!` sends the requests and keeps the
facts of each answer. `check/verdicts` turns the facts of a file into
diagnostics. `files` and `verdicts` are pure, so the tests give them feeds
and facts written by hand, with no network. A check fetches as little as
it can:

- a HEAD request and a request for the first two bytes of each media
  file, transcript and chapters file, and of the feed, which `http/probe!`
  sends, and which tell the status, the type, the size, the byte ranges,
  CORS and the validators of the file
- a HEAD request and the header of each image
- with `:read?`, the ranges that the media reader needs of each media file,
  for its format and duration, and each transcript and chapters file whole
  in place of its two bytes
- with `:integrity?`, each file that has a `podcast:integrity`, whole

The requests for the files that web players read, the transcripts, the
chapters and the feed, name an origin that no site has, to learn what CORS
allows. The credentials of a private feed go with the requests for the
feed alone. A source of an alternate enclosure with a type of its own,
such as a torrent, is checked as a file of that type. A host of an overlay
network, such as Tor, is left out, since only its own clients reach it.

The rules come from the standards first:

- RFC 9110 requires HEAD of every general-purpose server. It makes byte
  ranges optional, but says that servers ought to support them when
  possible.
- RSS 2.0 gives the length of an enclosure in bytes, and its type. A
  length of 0 is a size that is not known, as the RSS Profile has it.
- The podcast namespace requires CORS for transcripts, and gives the size
  and the aspect ratio of a `podcast:image`. It takes an image without them
  as square, but the model cannot tell such an image from one of the
  deprecated `podcast:images`, which makes no such claim. So the check
  compares only a size that is declared, with the image as a viewer shows
  it, turned by its EXIF orientation.
- The Fetch standard decides what a web player on another site can read.
  A browser applies the CORS rules to each redirect on the way, as well as
  to the answer. After a redirect to another origin it names the origin
  `null`. So each answer must allow any origin with `*`, or allow the
  origin that it gets, as a server does that repeats it.

A profile adds the rules of a standard or a directory on top, with the
names that `validate` uses. PSP-1 asks for artwork in `itunes:image` that
is a square of 1400 to 3000 pixels, JPEG or PNG, in RGB, with a file
extension that matches its type. A JPEG must also be one that apps and
browsers decode, not lossless, hierarchical or arithmetic-coded. Apple
Podcasts asks for the same, and also refuses transparency, requires byte
ranges, and asks for Last-Modified on the artwork. An image that several
elements name gets the rules of each. The catalogue gives the level of
each diagnostic, and a profile can raise it: a server that ignores byte
ranges is a warning, and an error with `:apple`.

In September 2026, a check of the 28 feeds of the corpus, two items each,
with the Apple profile, found two errors, both artwork with transparency.
Every host answered HEAD and byte ranges. The lengths and the durations of
the files of hosts that insert ads differ from the feed by some percent,
because each request gets another file, so these diagnostics are only
info. Five hosts serve transcripts or chapters without CORS.

In ClojureScript, `feed!` sends with fetch. Node can check any server. A
browser fails without a reason where CORS does not allow the page to read,
and hides the headers that an answer does not expose, such as
Content-Range and ETag. The facts mark such an answer `:filtered?`, and the
rules that need a hidden header pass it. So a check sees most on the JVM or
in Node.


## Careful with the network

podcast-clj moves no more bytes than a purpose needs, and asks a server no
more often than it must:

- A poller sends the ETag and the Last-Modified of the last fetch, so an
  unchanged feed costs a 304 and no body (`podcast/fetch!`). It waits as
  long as the server says (`http/not-before`) and as the feed says
  (`resolve/next-check`).
- The readers of media files and images read by byte ranges, and stop when
  they know enough (`bytes/read!`, `http/fetcher`). Most media files take
  one or two requests and a few hundred kilobytes. Ranges near each other
  come in one request, and a round sends at most 16 requests, one after
  another. A walk through the segments of a file reads further ahead each
  round, up to 1 MB. After 8 rounds the reader gives up. The sizes in a file
  are numbers that its writer chose, so a file can ask for more than it
  holds. One reading fetches 32 MB at most, and it fetches nothing of
  ranges that do not fit in what is left. It starts no request after one
  minute. `bytes/default-limits` holds these numbers, and the options of a
  reading set each of them.
- `http/probe!` learns the facts of a file from a HEAD request and two
  bytes of it.
- A server that ignores a byte range sends the whole file, and some send
  more than the range. The client reads the bytes that it asked for, and
  then closes the connection. From such a server, the bytes at an offset
  cost all the bytes before them. So a reading asks such a server once in
  a round, from the start of the file to an eighth further than it needs.
  It gives up when it needs bytes after the first 32 MB of a file.
- A check probes each file. It reads what a file holds only when it is
  asked to, and then fetches the transcripts and chapters whole. It fetches
  a media file whole only to check its integrity. At most four files are
  under way at a time, and a lane takes the next file as soon as its last
  is done. A feed names the files of its check, so a check looks at 500
  files at most, and it starts no file after ten minutes. The report
  counts the files that it left out. `check/default-limits` holds these
  numbers, and the options of `feed!` set each of them.
- `comments/fetch!` reads a thread of comments in rounds of four requests,
  and it reads the posts nearest the root post first. It asks for no more
  posts when it has 100 from this fetch, and it starts no round after 300
  requests. It starts no request after one minute, and the time that is
  left limits each request. It reads five pages of a collection in a
  round, and each page only once. A second fetch continues an incomplete
  thread. A server that did not answer in time, or that answered 429, gets
  no more requests in the same fetch. The next fetch asks again for what a
  request that failed, a 429 or a status from 500 kept from it. An answer
  is 5 MB at most. The options of `fetch!` set each of these numbers.
- A thread of comments also has limits of size that no option sets. A page
  of replies gives 1,000 items, and an id has 2,048 characters. A thread
  holds 10,000 comments, and the replies of an AT Protocol thread go 64
  levels down. The report counts the replies that such a limit cut.
- `value.lnaddress/invoice!` sends two requests for an invoice, and
  `node!` sends one. An answer is 256 KB at most, since the terms of an
  address can hold an image. The podcast namespace asks apps to keep the
  node of an address for a while. `value.metadata/fetch!` reads two bytes
  of the page of the metadata, whatever the status of the answer.
- `analytics.op3/downloads!` reads at most five pages of 20,000 downloads.
  When there are more, it gives the token of the next page, so that the
  next call reads on from there. When a page fails, it gives the pages
  before that page, the failure, and the token of the page that failed.
- Every body that the client keeps has a limit of size (`:max-bytes`, 100
  MB by default), and every exchange a limit of time (`:timeout-seconds`),
  on both platforms.

A check of five corpus feeds, ten items each, sends 490 requests and moves
4.5 MB in about 20 seconds. The first version fetched every feed,
transcript and chapters file whole: 48.9 MB in 79 seconds.


## Requests that strangers choose

Some requests go to URLs that strangers choose. The servers of a thread of
comments name its posts and their authors. A feed names the domain of a
Lightning address, and that domain names its callback. Anyone who pays a
Lightning address names the URL of the metadata of the payment. Such a
request can make an app on a server ask its own network. That network
holds routers, other services, and the metadata of a cloud server.

The guard is `:allow?`, a predicate of a URL. Each function that sends
requests takes it as an option, and puts it on each request map.
`http/call!` and `http/digest!` ask it about the URL of the request. A
`:send` of the app gets the same request maps, so it can ask too.

The functions that fetch these URLs have `http/public?` as their `:allow?`
by default: `comments/fetch!`, `value.lnaddress/invoice!` and `node!`, and
`value.metadata/fetch!`. `public?` takes no host whose address is loopback,
private, link-local, shared, multicast or reserved, as the IANA registry of
special addresses lists them. It also refuses an IPv6 address that holds
such an IPv4 address. `http/call-public!` is `http/call!` with this guard.

On the JVM, `http/public?` resolves the name of the host and checks every
address that the name resolves to. `http/call!` checks the URL of each
redirect in the same way, with its option `:allow?`. The JVM keeps what it
resolved for 30 seconds by default, so the request goes to the address
that was checked. A name that answers with another address later cannot
move the request, unless the app turns this cache off.

In ClojureScript, a browser cannot resolve a name, and fetch follows
redirects without telling. So `public?` refuses only the addresses and the
local names that a URL writes, such as localhost and .local. A request
from a browser comes from the device of its user, not from a server. A
server in Node that fetches URLs that strangers choose gives these
functions a `:send` of its own.

An app that must reach its own network gives `(constantly true)` as
`:allow?`. The functions that fetch what a feed names have no guard by
default, because the app chose the feed. They are `podcast/fetch!`,
`check/feed!`, `format.media/read!` and `verify!`, `format.image/read!`
and `http/probe!`. A server that fetches feeds or files for strangers
gives them `{:allow? http/public?}`.

The guard is an option of its own, and not a property of `:send`. A
`:send` that logs or signs requests keeps the guard, because the guard is
in each request map.

The check of an integrity fetches a whole file. With a `:send` of the app,
`verify!` and `check/feed!` fetch the file through it, up to the
`:max-bytes` of `http/call!`. Without a `:send`, the JVM streams the file
with `http/digest!`, which takes the guard as its option `:allow?`.


## Secrets

An app holds secrets of the listener. Examples are the password of a
private feed, the token of a service, and the key of a wallet. An app
also prints its data, to a log and at a REPL, and a printed map shows
each of its values.

`secret/hide` wraps a text, and the printer writes the wrapped text as
`#<secret>`. `pr`, `str` and the pretty printer do so on both platforms,
and so does `JSON.stringify` in JavaScript. The reader does not read
`#<secret>`. So a map with a hidden text does not go to a file as EDN and
come back without its secret.

Each function that takes a secret takes a hidden text too, and reads it
at the one place that needs the text:

- the `:credentials` of a request, of a WebDAV store and of gpodder
- the credentials of the Podcast Index
- the token of OP3, of Podping and of a private feed
- the `:secret` of WebSub
- the `:passphrase` of the encrypted store
- the secret key of an event of Nostr

podcast-clj hides the secrets that it makes itself:

- the password that `http/split-credentials` takes out of a URL
- the secret key of a connection to a wallet
- the key that two owners of keys share
- the headers Authorization and Cookie of a request map
- the body of a request with a secret, as the request of WebSub has it

So a request map that is printed shows `#<secret>` in the place of a
password. `http/call!` reads the text when it sends the request. A
request is still a map that any HTTP client can send, with one step
before it: `http/revealed` gives the request with its texts. A client
that does not take this step sends `#<secret>` to the server, and the
server refuses the request.

A hidden text protects from a print by accident, and from no more than
that. A debugger and the console of a browser show the fields of an
object. A request that went through `http/revealed` shows its
credentials. A private feed with a token in its URL has the token in each
address.


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

A chart can be the chart of one category. The category is a name of the
categories of Apple Podcasts, which `itunes:category` has too, and each
source reads it in its own way. Apple takes the id of a genre, the
Podcast Index takes its own names, gpodder.net takes a tag, and fyyd has
no such chart. The Podcast Index gives the chart of all categories for a
name that it does not know, without an error. A chart of the wrong shows
is worse than no chart, so `index/category-filter` throws for such a
name, and the source is then under `:failed`. A source without a chart
of the category is there too, and no source adds the shows of all
categories to the chart of one. [Services and their
defaults](services.md#the-chart-of-a-category) has the facts of each
directory.

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
exception names all of them. `poll!` throws nothing for a call that no
node answered. Its result then has the `:error`, the diagnostic
`:podping/unavailable`, with the pings of the blocks that it read before
that call. The block to continue from is the last of those blocks.

`pinged?` and `affected` compare URLs the way that `podcast:guid` does. For
example, a ping for `http://…/feed/` matches a subscription stored as
`https://…/feed`. `notify-request` is for the host: one GET to podping.cloud
with the token of the host.

The Podping page of the inspector reads the last twenty blocks live. The
tests run on recorded answers, with a node that fails when the test tells it
to.


## Links to the apps

A listener shares a show, and the person who gets the link has another
app. No standard defines a link to a show in an app. Each app has a form
of its own. Most forms hold a key that the app did not make. The keys
are the URL of the feed, the `podcast:guid`, the id of Apple Podcasts
and the id of the Podcast Index. podcast-clj has these keys, so it can
write the links.

The forms are facts about other products, and they change. So `links`
holds them as data in `links/apps`, and an app gives a table of its own.
A form is a vector of texts and keys. It is no text with a syntax of its
own, so the code has no parser for it. [Services and their
defaults](services.md#the-links-of-the-apps) has the table with its date
and its source, and a test keeps the page and the data the same. The
table leaves out the forms that need an id of the app itself, because
podcast-clj has no such id.

`links/ids` reads the keys from a parsed feed, or from a show of
`directory`, whose `:directory/sources` has the ids of the directories.
`links` writes each link that the keys are enough for. A link with the
scheme of an app opens the app, and works only where the app is. A link
to a web page works in each browser.

A link with a time has one standard, the media fragment of the W3C:
`#t=754` on the URL of a media file. `links/at-time` writes it, and a
clip of `app.player` gives its start and its end. The forms of the apps
for a time need the id that the app has for the episode. The research
found no page of an app that defines such a form. So the table has
none.


## WebSub

WebSub is the W3C Recommendation for the same signal. A feed names a hub,
and the hub sends the feed to each subscriber when the feed changes. The
hub calls the subscriber, so a subscriber is a server with a public URL.
An app on a phone has no such URL, and it reads Podping. A server that
keeps the feeds of many listeners can use both.

`websub` has the parts of a subscriber. `discover` finds the hubs and the
topic, which is the URL to subscribe to. It reads the Link headers of the
answer first, as the Recommendation asks, and then the `atom:link`
elements of the feed. `podcast/fetch!` gives the result as `:websub`.
`request` builds the request that subscribes or unsubscribes, and `call!`
sends it. `handler` is a Ring handler for the callback URL, like the
handlers of the WebDAV server and the gpodder server.

A hub calls the callback in three ways, and the handler answers each:

- The hub makes sure that the subscriber asked. The handler asks the app,
  and gives the challenge of the hub back. It sends the challenge as
  `application/octet-stream` with `nosniff`, and it takes only a challenge
  of the characters that the Recommendation allows. A challenge is the
  text of a stranger, and a browser must not read it as a page.
- The hub sends the feed. With a secret, the hub signs the feed with an
  HMAC, and the handler checks the signature before the app gets the
  feed. It answers 200 to a feed without the right signature too, so that
  a stranger learns nothing about the secret. The comparison of the two
  signatures takes the same time wherever they differ.
- The hub tells that it denied a subscription.

The signature is an HMAC with SHA-1, SHA-256, SHA-384 or SHA-512. The JVM
has them, and the Closure Library has them for ClojureScript, so the
handler is one function that gives a response on both platforms. Most hubs
sign with SHA-1. The app can take a smaller set of methods.

A host is a publisher. It names the hub in its feed, and it tells the hub
of each change. The Recommendation leaves that request to the hub, so
`publish-request` has the form of the public hubs as a default. A private
feed names no hub, because a subscriber gives the URL of the feed to the
hub, and that URL holds the token.


## Comments

`podcast:socialInteract` names the root post of a thread of comments about
an episode or a show. Most often, the root post is the podcaster's post
about the episode. The podcaster also chooses the protocol of the thread,
and the specification lists eight protocols. `comments` reads three of
them. These are ActivityPub, which Mastodon, PeerTube and the podcast host
Castopod speak, the AT Protocol of Bluesky, and Nostr. The protocol
`disabled` tells apps that the podcaster wants no public comments.
`resolve/social-interacts` then gives only the `disabled` interact.

A thread is data, like the player state. Each interact that
`resolve/social-interacts` gives, `{:protocol :uri}`, is an unread thread.
For an episode, `thread` picks the first interact by priority that
podcast-clj can read. `fetch!` adds the id of the root post, the comments
by id, and their authors by id. Each comment names the post that it
replies to. `tree` makes the tree from these names.

`comments` is the front door. It sends each call to the reader of the
protocol of the thread: `comments.activitypub`, `comments.atproto` or
`comments.nostr`. The readers share the functions of `comments.common`. They cannot use
functions of `comments`, because `comments` requires them, and a cycle of
requires does not load. The keys that record the reading are in the
namespace `comments`. The readers write them through an `:as-alias`, which
loads nothing.

The content of a comment is HTML that `format.html` sanitized, so a page
can show it as it is. Its links are absolute, and they carry
`rel="nofollow ugc"`, because the app does not vouch for them. podcast-clj
replaces each image in the content with its alt text. An image that loads
with the page tells its server who reads the thread.

The content is also data. `comments/parts` gives it as text, links,
mentions and hashtags, in one shape for the three protocols. Each part has
the text that the content shows for it. `comments/hiccup` gives the
content as Hiccup, and `comments/text` gives it as plain text. The plain
text is the text of the Hiccup, as a reader sees it. It is not the text
that the author sent. A note of Nostr names a profile as a `nostr:` URI
of 69 characters, and the plain text has the name of the profile. An
app gives it one function, `:link`, which tells where a mention or a
hashtag leads. So a mention leads to a page of the app, and the three
protocols need one renderer. Each comment keeps what the renderer reads.
For ActivityPub, that is the HTML and the tags of the post. For the AT
Protocol, it is the record of the post, and for Nostr, the event of the
note.

The three protocols write a mention in three ways. On the AT Protocol, a
facet marks the bytes of a mention and names the DID, so the parts are
exact. On Nostr, the text holds the name of the profile as a `nostr:`
URI. On ActivityPub, the mention is a link in the HTML, and the post
names the actor in a tag. The link goes to the page of the profile, and
the tag has the id of the actor, so the two are different URLs. The
reader matches them by the user name and the server. A link is also a
mention when it has the class `mention`. No standard defines that class.
Mastodon writes it, and other server programs follow Mastodon. Such a
mention can have no id.

The data also records how far the reading went. It holds when `fetch!`
read each post, the next page of each collection, and the pages that it
read. A second `fetch!` reads on from where the first stopped. `requests`
gives the requests that a thread needs next. `with-exchanges` reads the
answers into the thread. Both are pure, and `fetch!` only sends the
requests and receives the answers.

A failure is data in the thread too, and some failures pass with time.
These are a request that got no answer, a 429, and a status from 500.
`resumed` is the first step of each fetch. It makes unread again what such
a failure kept from the last fetch, so the new fetch asks again. A page of
replies that failed is read again from that page. `resumed` also notes how
many comments the thread holds, so `:max-comments` counts the comments of
one fetch. A failure that does not pass stays: a post that is gone, a
server that refuses, and an answer that does not read.

On ActivityPub, each post is a JSON document at its own URL. A root post
can start a conversation and name a collection of all its posts. This
collection is the `context` of FEP-f228, which Mastodon serves from
version 4.5, or the `comments` of a PeerTube video. `fetch!` then reads
that collection. Other root posts name only their direct replies, in their
`replies` collection. For these, `fetch!` reads the replies of each post
down the tree. A conversation reaches posts that this walk misses. Misskey
lists no replies, so the walk cannot reach a reply to a Misskey post.

A collection comes in pages, and each page names the next page.
`next-page` gives the request for the next page, and `async/iteration`
reads the pages with it. `async/iteration` is `clojure.core/iteration` on
the JVM, and the same iteration over promises in ClojureScript. The
reading stops at a page that it read before.

A server speaks only for its own posts and authors, as FEP-fe34 asks. A
page of replies holds the posts of its own server whole. It names the
posts of other servers only by their URLs, and `fetch!` fetches each of
these posts from its own server. `fetch!` does not use a document whose id
or author is on another server, and the report tells so. The collection of
the replies to a post must be on the server of the post. A post replies to
the post that its own `inReplyTo` names, whatever collection lists it. The
handle of an author is its user name at the domain of its id, as in
`@ann@example.social`. `fetch!` does not take a `webfinger` that names
another domain, because only a WebFinger lookup there can prove it. The
web page of a post or of an author is a page on its own server. If it
names a page on another server, its id is its page. So the name of an
author does not lead to the page of another person.

A post is for everyone when its recipients include the Public collection,
or when it names no recipients. `fetch!` leaves out all other posts.

The servers of a thread choose the URLs that `fetch!` fetches. So it sends
nothing to the network of the app, as
[Requests that strangers choose](#requests-that-strangers-choose) tells.

Some servers answer only signed requests. Mastodon sends no CORS headers
with its posts, so a page on another site cannot read them. For such a
root post, `fetch!` reads the thread through the Mastodon API of its
server. Many servers answer this API, and it sends CORS headers. The API
speaks only for its own server. So `fetch!` takes only the statuses and
the accounts of that server from the API. It fetches the posts of other
servers from those servers. An app that signs its requests can give
`fetch!` a `:send` that signs them.

On the AT Protocol, the posts are in the repositories of their authors.
Only an AppView knows the replies to a post. An AppView answers
`app.bsky.feed.getPostThread` without an account, with ten levels of
replies. `fetch!` asks again for each branch that the answer cut.
podcast-clj names no AppView, because the choice belongs to the app.

The labels that the AT Protocol defines apply. A label on the account of
an author counts as a label on each of its posts. `fetch!` leaves out a
reply whose author asks apps to show it only to signed-in users. It also
leaves out a reply that a moderation service hid. An AppView gives no
reply where one of the two authors blocks the other. The report counts
such a reply with the replies that `fetch!` left out. A reply with a
warning is `:sensitive?`. On Bluesky, the author of the root post can hide
replies.
A hidden reply is `:hidden?`, and `tree` shows it only when its options
ask for hidden replies.

On Nostr, a comment is an event that its author signed, and relays hold
the events. A thread has one of two kinds of root. The first is a note
that `podcast:socialInteract` names, by a `nostr:` URI of NIP-21. The
second is the episode itself. NIP-73 gives an episode the id
`podcast:item:guid:` with its guid, and an event names the episode in a
tag. `comments.nostr/about` gives the thread of an episode or a show. Such
a thread needs no tag in the feed, so an app can show comments for each
episode. Its root is a comment without content, because no post is its
root.

NIP-22 defines the comment on an episode as an event of kind 1111. On 29
September 2026, the relays had few such events. The app Fountain writes a
note of kind 1 with the id of the episode in an `i` tag. Other apps reply
to that note as NIP-10 defines. So the reader asks for both kinds, and it
reads the parent of each event by the rules of its kind.

A relay can send anything, and no server speaks for an author. The
signature does. So the reader keeps only the events that
`nostr/verified?` takes and that pass a filter of the request. The report
counts the events that it left out. Each relay gets the same questions,
and an event that two relays give is one comment. An event of kind 5
deletes a comment of its own author, as NIP-09 defines. The thread keeps
the deletion, because another relay can give the comment later.

The name of an author comes from the event of kind 0 of the author. Its
handle is the public key in the form `npub1…`. The event can name an
address of NIP-05, such as `ann@example.org`. podcast-clj does not check
that address, so it does not show it as the handle.

The content of a note is plain text, but a person cannot read all of it.
It names a profile as `nostr:npub1…` and an event as `nostr:nevent1…`,
as NIP-27 defines. `nostr/parts` gives the content as data: text,
mentions, references, links and hashtags. `comments.nostr/hiccup` writes
Hiccup from the parts. A mention becomes the name of its profile, so the
reader also asks the relays for the profiles that the comments mention.
The name comes after the comment, and the content of the comment then
gets the name.

Some lines of a note say again what its tags tell. Fountain ends each
note with the web page of the episode, which the `i` tag of the note has
as its hint. A note that came with a payment also names the receipt of
the payment, an event of kind 9735 of NIP-57. `hiccup` leaves out such
lines, and `receipts` gives the ids of the receipts. A note with a
payment and no message then has no content.

Nostr has no web pages, so a mention has no link of its own. The app
chooses a web app or the `nostr:` scheme, and gives `hiccup` a function
for the links. The `:content` of a comment has no such links. Each
comment keeps its event, so an app writes the content in its own way.

The comments page of the inspector shows the thread of an episode. When
the feed names no thread that podcast-clj reads, the page shows the notes
about the episode on Nostr, with links to the gateway njump.me. The tests run on recorded answers of Mastodon,
Misskey, PeerTube, the Mastodon API and a Bluesky AppView. The tests of
Nostr sign their own events, and one note is a recording of a relay.


## Nostr

`nostr` holds what a podcast app needs of Nostr, under the comments and
the wallets. Nostr has no servers that own data. Each event is a JSON
object with the public key and the signature of its author, and relays
pass the events on.

An event stays the plain data that a relay gives, with string keys. Its
id is the SHA-256 of a text that NIP-01 defines to the character.
`event-id` writes that text itself. A JSON writer escapes more
characters than NIP-01 allows, and the id is then another id.
`verified?` checks the id and the signature.

The signature is a Schnorr signature over the curve secp256k1, as BIP-340
defines it. The JDK has no such curve, and the Web Crypto API of the
browsers has none. So `secp256k1` has the arithmetic of the curve, over
BigInteger on the JVM and BigInt in JavaScript. It passes the test vectors
of BIP-340 on both platforms. `sign` and `public-key` are there for the
requests to a wallet. The time that they take depends on the secret key,
and their docstrings tell so.

A check takes about 1 millisecond on the JVM and in Node, measured on
2026-09-29. It is most of the time that the reading of a thread takes,
so three things keep it short. The size of the field is 2^256 less a
small number. So a product folds its high bits into its low bits, and
needs no division. A number is read as digits with signs, which need an
addition for one digit of six or of nine. The multiples of the generator
are computed once.

`decode` and `encode` read and write the names of NIP-19, which are
Bech32. `bech32` is the encoding of BIP-173, with its test vectors.
podcast-clj reads no name of a secret key.

A relay speaks over a WebSocket. `call!` is one exchange: it opens a
connection, asks for the events of some filters, reads until the relay
has no more, and closes. It is the WebSocket of the JDK on the JVM, and
the WebSocket of the platform in JavaScript. So a request to a relay is a
map, as a request to a server is, and `comments/fetch!` sends both kinds
in the same rounds. `received` is the pure step of the reading, for an app
that holds its connections open. The guard of
[Requests that strangers choose](#requests-that-strangers-choose) applies
to a relay too, because a name of NIP-19 can name any relay.

`nostr/relays` names three public relays, and
[Services and their defaults](services.md#the-relays-of-nostr) tells when
they answered. The relays are a choice of the app, so `:relays` replaces
them.

An app also writes events, for its requests to a wallet. `signed` gives
an event the public key of its author, its id and its signature. `call!`
gives a relay such an event as `:event`. The subscription for the answer
is there before the event goes out, and the exchange ends at the first
event that comes. The relay tells whether it took the event, and the
response has that as `:accepted?` or `:refused`.

A request to a wallet is secret, so its content is encrypted. `nip44` has
the encrypted payloads of NIP-44, in version 2. The two sides share a key,
which each side gets from its own secret key and the public key of the
other side. `secp256k1/shared-x` gives the start of that key. ChaCha20
encrypts the text, and an HMAC tells when somebody changed the payload.
The Web Crypto API has no ChaCha20, so `chacha20` has the cipher of RFC
8439 for both platforms. It passes the examples of the RFC, and on the JVM
the tests compare it with the cipher of the JDK. `nip44` passes the test
vectors that the authors of NIP-44 publish.


## Value for value

A listener can pay a show while they listen, and send a message with the
payment. The `podcast:value` block of a feed names the recipients and
their splits. podcast-clj divides an amount between them, and a wallet
pays. It is the wallet of the app, or the wallet of the listener, which
podcast-clj asks through Nostr Wallet Connect or WebLN. podcast-clj
writes and reads what goes with a payment, and asks Lightning addresses
for invoices.

A payment is one map for each way to send it. Its amounts are in
millisatoshis, as the formats count them. Most of its other keys are the
names of the RSS Payment Metadata in kebab case, and the fields of bLIP-10
map onto them. So a host reads a keysend payment and a payment to a
Lightning address in the same shape.

A keysend payment to a node carries the bLIP-10 record under the custom
record 7629169. `value/boostagram` writes the record, and
`value/parse-boostagram` reads it. Apps write the record in several ways.
Podverse writes `streaming` for stream, Fountain writes numbers as text,
Breez writes the guid of the episode as its itemID, and Alby Hub writes
`feedId`. The reader takes each of these, and the report tells what it
forgave. `value/records` gives all the custom records of a payment, with
the custom key and value of the recipient. The feed chooses the custom
key. So `records` leaves out a key that the Lightning protocol keeps for
itself. Its report tells so, because the payment then does not reach the
wallet.

Many wallets cannot send keysend payments. So a recipient can be a
Lightning address, `user@domain`, as LUD-16 defines it.
`value.lnaddress` builds the requests as maps. LNURL-pay gives an invoice
for an amount. `invoice` checks that the invoice asks for the amount that
was requested, as LUD-06 requires. It reads the amount from the part of
the invoice before its data, and the wallet checks the signature when it
pays. The domain can also name a node for keysend, as the podcast
namespace defines it. `node` reads that node into a recipient of type
node.

A payment to a Lightning address carries only a short comment. The RSS
Payment Metadata of the podcast namespace puts the action, a URL and the
start of the message in the comment. The URL answers with the whole
payment in the header `x-rss-payment`. `value.metadata` writes and reads
the comment and the header. `fetch!` asks for two bytes of the page,
since the header holds the metadata. Helipad asks with HEAD, but BoostBox
answers HEAD with 405, so HEAD does not reach all servers.

The services of Lightning addresses answer errors in many ways. Fountain
answers JSON with status 200, Alby a web page with status 404, and Coinos
text with status 500. Primal answers the keysend lookup with its web app.
So the readers decide on the JSON of an answer, not on its status, as
LUD-01 asks. The callback can be on another host than the address. The
callback of Coinos has a query already, so `invoice-request` adds the
amount and the comment to that query.

A record or metadata is what the sender claims. Anyone can send a payment
of one satoshi with the URL of the metadata of a large payment. So
`value/as-received` takes the amount from the node, and reports a claim
that differs from what the node received.

The feeds choose the domains that `invoice!` and `node!` ask, and the
senders choose the URLs of metadata that a host reads. So these functions
send nothing to the network of the app either. A Lightning address must
name a domain, not an address or a local name. An answer that came over
plain http is an error, since anyone on the way can change its invoice or
its node. A feed chooses the custom key of a recipient. That key cannot
take the place of the bLIP-10 record, of the preimage of keysend, or of
the types below 65536. The Lightning protocol keeps those types for itself.

The tests run on the records of the bLIP-10 document and on the examples
of BOLT 11. They also run on the answers that six services gave about their
own addresses.

### The wallet of the listener

A listener can pay from the own wallet. Nostr Wallet Connect, which NIP-47
defines, takes the commands of an app to a wallet over a relay of Nostr.
The wallet makes a connection URI with a secret key for the app, and the
listener gives the URI to the app. `value.nwc` reads the URI, writes the
commands as data, and reads the answers. `ask!` sends a command and
waits for the answer. The relay sees the kind of each event and the two
public keys. It does not see the command, because NIP-44 encrypts it.

NIP-47 has the commands of invoices. The command of keysend is in the
extension NWC-04, in the repository nostr-wallet-connect/nwc. A wallet
names the commands that it takes in its info event. So `ask!` reads that
event first, and sends no command that the wallet does not take.

podcast-clj writes the encryption of NIP-44 only. NIP-47 keeps the
encryption of NIP-04 for old wallets. NIP-04 has no check of a change,
and its own text tells not to use it for secrets. A wallet that reads
only NIP-04 gives `:value/wallet-unsupported`.

A payment must not go out twice. `ask!` gives a command to one relay.
The event of the command goes out when the relay has the subscription
for the answer. So a relay that refuses the subscription gets no event.
The next relay gets the event only when it did not leave the app, or when
the relay refused it. Each request expires after the time that the app
waits for the answer. When no answer comes in that time, the result is
`:value/wallet-silent`, of level `:error`. The wallet can still pay until
the request expires. So the app waits for that instant, and then asks the
wallet whether it paid.

In a browser, a wallet can give the page a WebLN provider. `value.webln`
calls the provider, in ClojureScript only. WebLN counts a keysend payment
in whole sats. So `keysend-args` takes the whole sats of an amount, and
the bLIP-10 record tells the amount that goes out. The custom records of
WebLN are text, and those of Nostr Wallet Connect are hexadecimal. Both
come from `value/records`.

The connection URI is a secret of the listener, and podcast-clj keeps it
in no place. An app keeps it as it keeps a password. A URI can also come
from a stranger, and it names the relays. So `ask!` sends nothing to the
network of the app, unless the app allows it with `:allow?`.

A map that is printed shows each of its values, and a log prints maps. So
`parse` hides the secret key of a connection with `secret/hide`. `pr`,
`str` and the pretty printer write a hidden text as `#<secret>`, on both
platforms, and the reader does not read that text. `nwc/uri` gives the
text with the secret, for the store of the app. `secret` keeps a text
from a print by accident. A debugger and the console of a browser show
the fields of an object, so they show the text.

The preimage of a payment is its proof. The recipient gives it to the
wallet when the payment arrives, and its SHA-256 is the payment hash of
the invoice. `lnaddress/payment-hash` reads the hash from an invoice, as
BOLT 11 defines it, and `lnaddress/proves?` checks a preimage. `ask!` and
`webln/pay-invoice!` put the result in their answer as `:proven?`. A
false `:proven?` is no error. The wallet tells that it paid, so a second
payment can be one too many.

The table tells what each party can do to a payment, and what stops it.

| Who | What they can do | What stops it, or what is left |
|---|---|---|
| A relay | Read the kind, the two public keys, the time and the size of each event | Nothing. NIP-44 adds padding, so the size tells little. |
| A relay | Read or change a command or an answer | NIP-44 encrypts the content, and the signature of the event covers it. |
| A relay | Answer in the place of the wallet, or give an old answer again | `answer` takes only the signed event of the wallet, encrypted for this connection. `ask!` takes only the answer to its own request. |
| A relay | Hold back the answer, so that the listener pays again | `:value/wallet-silent` tells that the request went out, and until when the wallet can pay. |
| A relay, or who reads a relay | Give the wallet the same request again | The request expires. A wallet knows an event by its id, and podcast-clj trusts the wallet to do one event once. |
| A relay | Refuse the subscription, and take the event | The event goes out after the relay tells that it has the subscription. |
| A feed | Name the recipients and their shares | Nothing. The app shows them to the listener before the payment. |
| A feed | Put a custom record in the place of the record of the payment | `value/records` leaves out the types that the Lightning protocol keeps. |
| A feed | Make a command too large, with a long title | `:value/command-too-large`, and no request goes out. |
| The service of a Lightning address | Give an invoice for a higher amount | `lnaddress/invoice` checks the amount: `:value/wrong-amount`. |
| Who writes a connection URI | Name a relay on the network of the app | `:allow?` is `nostr/public?`. |
| Who writes a connection URI | Name a Lightning address of their own as `:lud16` | The app asks the listener before it uses the address. |
| A wallet | Tell that it paid, without a payment | A result without a preimage is no payment. For an invoice, `ask!` checks the preimage against the payment hash of the invoice, and the answer has `:proven?`. A keysend payment has no such proof. |
| Who reads the logs of the app | Find the secret of a connection | podcast-clj puts the secret in no event, no diagnostic and no exception. A connection hides its secret, so a connection that is printed shows `#<secret>`. |
| Who reads the store of the app | Find the URI of a connection | Nothing in podcast-clj. The app keeps the text of `nwc/uri` where it keeps passwords. |
| Who measures the time of the app | Learn bits of the secret from many signatures | `sign` and `shared-x` do not take constant time. An app signs only when the listener pays. |

The numbers of `secp256k1` are those of the platform, and their
arithmetic does not take constant time. So an app does not sign for a
stranger who asks again and again and measures the time.

The tests have a wallet service that is made of the functions of
podcast-clj. On the JVM, a command also goes to that wallet over a relay
on localhost. No test and no page of the inspector went to a wallet with
money.


## Analytics

A host counts the downloads of its episodes. A download is not a listen,
since many apps download new episodes by themselves. But advertisers pay
by downloads, so the industry counts them. Version 2.2 of the Podcast
Measurement Technical Guidelines of the IAB Tech Lab defines a download.
`analytics` applies these rules to the requests that a host logs.
`analytics.op3` reads the downloads that OP3 counts for a show.

A request is a map of one line of the log of a server. The guidelines
count in five steps, and `downloads` does the first three:

1. Filters leave out the requests that cannot be part of a download. These
   are HEAD requests, answers other than 200 and 206, and requests for two
   bytes. The requests of bots go too, and those of Apple Podcasts on a
   watch, which repeat the downloads of the phone. `drop-reason` names the
   filter that leaves out a request.
2. A download needs enough of the file to play for one minute, and the
   header of the file. `threshold` gives these bytes from the size of the
   file, the size of its header and its duration.
3. The requests of one listener for one file in 24 hours make one
   download. A listener is an IP address and a user agent. Of an IPv6
   address, the first 64 bits count, since one device uses many addresses
   of its network.
4. The downloads become the metrics: the downloads of each episode, show
   and day, and the listeners. `frequencies` and `group-by` do this step.
5. The host audits its counts twice a year.

The guidelines leave some choices to the host, which it states in a
document of its method. podcast-clj makes these choices:

- A window is a calendar day in UTC, as OP3, Castopod and oDL count. With
  `:rolling`, a window is the 24 hours from the first request. The
  guidelines call this more accurate, since a download over midnight
  counts once.
- The requests of a listener add up by the bytes that they hold, and each
  byte counts once. The guidelines do not say whether to add up the ranges
  or to join them. A player that asks again for the same bytes does not
  reach the minute that way.
- A request with no status passes the filters, since a prefix service sees
  no answers. A request with no method does not, since HEAD requests move
  no content.
- The file of a request is its URL without the query, since apps add
  parameters of their own. For a file with no threshold, every listener
  whose requests pass the filters counts, as oDL and OP3 count.

The rules need data from outside podcast-clj. The Open Podcast Analytics
Working Group keeps an open list of the user agents of apps, bots,
libraries and browsers, with the MIT license. `user-agents` reads the files
of the list. `identify` names the agent of a request by the list, with the
keys that OP3 gives it. The list changes often, so podcast-clj holds no
copy, and a host keeps a copy of one commit. The guidelines also ask a host
to leave out data centers, VPNs and known bots. Their lists are outside
too, and some cost money. A host gives them to `downloads` as `:exclude?`.
The guidelines ask a host to leave out the addresses with too many
downloads, and give no number. So the host removes such addresses from the
result by its own rule.

OP3 is a free and open service that counts downloads through a prefix. A
host puts `https://op3.dev/e/` in front of the URL of each enclosure. OP3
then counts each request, and redirects it to the file. `op3/prefix` writes
the prefix, with the podcast guid of the show when the feed has one. OP3
answers some URLs with an error. These are URLs with a user name or with no
path, and hosts that are IPv6 addresses or names without a dot. Such an
episode does not play, so `prefix` leaves its URL as it is. `with-prefix`
puts the prefix on the enclosures and on the sources of the alternate
enclosures, since OP3 finds the episodes of a show by both.

OP3 counts by its own rules, which differ from those of the IAB in a few
places. It counts a request for two bytes when no larger request follows,
since Apple's player sends its later requests to the host, past the
prefix. Its listener is a hash of the address, the user agent and the
referrer. It checks no bytes, since a prefix service does not see the
file. So the counts of OP3 and of `downloads` for one show differ a little.

The API of OP3 gives the downloads of a show one by one, with the app and
the device of each. It also gives the counts of the show and of its
episodes, and the apps of its listeners. `analytics.op3` builds the calls
as maps, and `downloads!` reads the pages of downloads with
`async/iteration`. A download of OP3 has the keys of a download of
`downloads` where OP3 tells the same fact. So an app counts both with the
same code. Each call needs the token of an API key. OP3 makes a key only
for a person who passes a check for humans, so podcast-clj cannot make
one. The token goes in a header and not in the URL, since logs keep URLs.

The tests run on the worked examples of the guidelines, version 2.2 and the
draft of version 2.3. They also run on entries of the list of user agents,
and on the answers that OP3 gave about the show Podcasting 2.0. A check of
the whole list in September 2026 found that each example of an agent or a
referrer names its own entry.


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
- the settings of each subscription, such as `:auto-queue?`, `:skip-intro`
  and the `:folder`, one entry for each field
- the tags of each subscription, one entry for each tag
- the OPML lists that the listener follows, one entry for each URL
- the episodes by `[feed-guid item-guid]`, with position, duration and the
  played mark, which `progress` sets within `played-threshold` seconds of
  the end. The app gives another number of seconds as an option.
- the queue, one entry for each episode, with a rank from `rank` that gives
  the order, and the playlists, ranked in the same way
- the inbox and the stars, one entry for each episode, and the saved
  filters, one entry for each name, each with a tombstone for each removal
- the bookmarks and the clips, one entry for each whole second of an
  episode, and a clip in the shape of a `podcast:soundbite`
- the ratings, one entry for each episode, a number with its scale
- the counts of listening, one for each show, month and device
- the preferences of the listener, such as the skip amounts and the playback
  rate, one entry for each field
- the device registry, with an entry for each device of the listener

`merge` reconciles two states entry by entry, and the later entry wins. The
merge is commutative, associative and idempotent, and a generative test
tests the three properties. As a result, any backend that can store and
fetch a document can sync the state. `app.sync` does this.

`refresh` gives a new parse to `feed/diff`. It puts the new episodes of a
subscription into the queue or the inbox, as the settings say, unless the
state knows them already. `refresh-list` does the same for a followed OPML
list: the shows that join it become subscriptions, unless the state knows
them. It never unsubscribes, because a show that leaves a list can still
be one that the listener likes.

A playlist is a list of episodes beside the one queue, so that `step`
stays about the queue. The podcast namespace has a standard for such a
list, the list feed of the medium podcastL, so `to-list-feed` and
`from-list-feed` connect a playlist to one.

`to-opml` and `from-opml` connect to `format.opml`. They keep the feed
guid, the folder and the tags of each show, and the time the listener
subscribed. `from-opml` uses the feed title before the text of an outline,
which some apps fill with a description. Some apps write one folder around
the whole list. OPML has no mark for such a wrapper, so `from-opml` knows
it by its name, and the names are an option. A folder of the listener that
holds every show has another name, and stays a folder. `skip` moves within
an episode, and never past its start or its end.

`step` is a small reducer over the events of a player: `:progress`,
`:ended`, `:play`, `:skip` and `:chapter`. It returns the new state, and the
effects for the app, such as to seek, to stop where the outro starts, or to
play the next episode. `step` is the only place where behavior is composed,
and it is short on purpose. A jump to a chapter is a skip to the position
that `chapters/jump` gives, so the rules of a skip are its rules too. The
sleep timer counts the episodes that end. It is an option of `step` and a
part of its result, not a part of the state. A timer of one device has no
place in a document that every device gets. A timer that counts minutes
needs a clock, and the clock is of the app. No standard has a scale for
the ratings of a listener, and apps have stars, points and thumbs. So the
scale is an option of the app, and `rating-scale` is the default, from 1
to 5. An entry holds its rating with the worst and the best rating of its
scale, as a Rating of schema.org does. So a rating reads in each scale,
as far on the way from the worst to the best. At each
`:progress`, `step` adds the seconds heard to a count
for the show and the month. The seconds are how far the position moved
since the last report, when that report is a minute old at most. The
minute is `listening-gap`, and the app gives another time as an option.
Each device has a count of its own. Only that device writes it, and it only
grows. So the counts of two devices that count at the same time add up,
and none is lost. The merge takes the higher of two counts of one device.
No standard counts listening, and a log of each play, as the gpodder API
keeps, grows without end. A count by month keeps the document small. An
app that wants the days keeps them on the device.


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

Saved criteria stay true as time goes on. So `:age` is a number of seconds
before now, and `episodes` takes the instant of now as `:at`. The criteria
under `:not` are those that an item does not meet. A set of texts under
`:text` finds an item with one of the texts. With these, a filter
excludes words.

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
prints as EDN. Read back, its sorted set of terms is a hash set, and prefix
search then scans the terms, until the app sorts them again.

The cost is that of an inverted index in persistent maps. On 36,000 items
with their show notes, indexing took five seconds and about 10 KB of heap
for each episode. A query took tens of milliseconds at most. A library of a
few thousand episodes needs tens of megabytes, and less than a second to
build. The show notes are nine tenths of the terms. An app with little
memory, for example in a browser, can index fewer fields: titles and people
alone cost a tenth. It can also index transcripts only for the episodes that
it downloaded.


## Download rules

An app downloads new episodes and deletes old files by rules of the
listener. No standard defines such rules. [Services and their
defaults](services.md#the-rules-of-the-apps-for-downloads) lists the rules
of three apps, and `app.downloads` has their kinds of rule.

`plan` is a pure function. It takes the player state, the parsed feeds,
the files of the device and the instant of now. It gives the episodes to
download and the files to delete, each with its reason. The app
downloads and deletes. The app also knows the network and the power of
the device, so it decides when it follows the plan.

A rule selects its episodes with the options of `search/episodes`, as a
saved filter does. So the rules need no language of their own, and each
criterion of a filter is a criterion of a rule. A number of episodes is
`:limit`, a minimum duration is `:duration`, and the words to exclude are
`:text` under `:not`.

The rules are data in the player state. The rule of a show is the setting
`:downloads` of its subscription, and the rules of the listener are the
preference `:downloads`. They sync as the other settings do. A phone and
a desktop often need different limits, so `plan` also takes the rules as
an option.

The list of the files is not in the state. The files are those of one
device, and the other devices have no use for the list. The app keeps the
entry of a file that went. `plan` downloads no episode that has an
entry, so a file that the listener deleted does not come back.

The rules can come from a later version of podcast-clj. `problems` tells
what this version does not know. `plan` does nothing for a show whose rule
has a problem, because a wrong delete costs more than a late download.


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
WebDAV. `app.sync.gpodder` is a backend over the gpodder API, which keeps a
model of its own, and `app.sync.gpodder.server` serves that API over a
state. Only the namespaces under `app.sync` require `app.sync`.


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
`:new-feed-url` gives it, and if permanent redirects moved it,
`:permanent-url` does. `:not-before` gives the time that the server asks a
client to wait, from `Retry-After` or `Cache-Control`. With `:max-pages`,
`fetch!` follows the pages of a paged feed with `clojure.core/iteration`,
and joins them. The feed names its pages. So a later page gets the
credentials of a private feed only at the origin of the URL that the app
gave. A redirect can move the first page to another origin, and no page
there gets them.

The HTTP client of `http` follows redirects itself, not through the client
of the JDK. The JDK sends the `Authorization` header on to another host
after a redirect. The client of `http` leaves it behind, so the credentials
of a private feed or a WebDAV store go only to their own origin.


## The inspector

The inspector in `web/inspector/` is a small Pedestal service that renders
HTML with the string renderer of Replicant. It is the first client of
podcast-clj, and it exists to test the library. It uses only the public API.

Give it a feed URL, or select a corpus feed. It shows the channel, the
items, each known tag, the unknown elements, the report, the validation
against a profile, and the XML written back. Next to the raw fields, it
shows the resolved properties and their sources. The front page searches the
directories, and the Podping page reads the chain. The comments page of an
episode reads its thread. It reads AT Protocol threads from the public
AppView of Bluesky. The thread page reads the comments below any post, by
the address of the post. The wallet page takes a connection URI of Nostr
Wallet Connect. It shows what the wallet takes and what it holds, and it
pays a boost for an episode, a share for each recipient. It gets the URI
as the post of a form, uses it for that request, and keeps it nowhere.
A form that pays has a token, and a token pays once. So a browser that
sends the form again pays nothing.

The queue page has a small player in ClojureScript, over `app.player`. The
player state is in localStorage, and Replicant renders it into a bar on
every page. The audio element reports its position through `step`, and the
effects of `step` seek or stop the audio element, or start the next
episode. The player also writes the data of each episode into the player
state, as entries of its own under `:inspector/episodes`. `player/write`
stamps them, so they merge and sync as the rest of the state does. So a
browser plays an episode that another device put in the queue. The
folder at `/dav/` is the sync backend that the player shares with any WebDAV
client. A click on a link fetches the next page and replaces only the
content below the bar. As a result, playback continues across pages, but the
inspector is not a single-page app.

The inspector serves its folder at `/dav/` through the `inspector.dav`
interceptor, for two reasons. The router of Pedestal does not match the
trailing slash of a collection. Also, the body-params interceptor of
Pedestal reads the body of a put before the handler can read it.

The inspector also serves the gpodder API at `/api/2/`, over the same
folder, so that AntennaPod and Kasts keep in step with the browser player.
Its server is a device of its own in the folder, and names the shows and
episodes of the feeds that the inspector has fetched. An interceptor keeps
the JSON of these requests from the body-params interceptor.


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
- A fourth corpus, in `dev-resources/corpus/media/`, holds the parts of
  media files that `media/read!` fetched: one episode from each of some
  ninety hosts, with a manifest. `corpus/download-media!` fetches them
  again, and `corpus/media-report` reads them offline. It shows the
  duration of each file beside the duration in its feed. Most durations
  agree within two seconds, and most of the others come from hosts that
  insert ads. The ID3 chapters of a show agreed with its JSON chapters.
  Each quirk that the corpus showed has a fixture in the tests, built byte
  by byte.
- `corpus/check-feeds!` checks the feeds of the corpus over the network,
  and `corpus/check-report` prints what the check found, by diagnostic and
  by feed. Its first run showed the rules that fired on files that were
  right, such as an extension read from a URL in the query. Each fix came
  with a test.
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

- The Open Podcast API, whose specification for episodes is a draft.
- Stores over the HTTP APIs of cloud services. Each is a short namespace
  against the store contract.
- A JSON form of the sync documents, for clients in other languages.
- A lenient XML parser for JavaScript.
- A folder store for Node.
- The release to Clojars.
- The encryption of NIP-04 for wallets that have no NIP-44, because
  NIP-04 is deprecated and has no check of a change.
- The extensions of Nostr Wallet Connect other than keysend: the
  notifications, the hold invoices, the history of payments, and the
  offers of BOLT 12.

[Podcast features outside podcast-clj](feature-gaps.md) tells about the
features that fit no library.
