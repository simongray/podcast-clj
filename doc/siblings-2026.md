# The needs of the sibling libraries, October 2026

This report covers the seven libraries that podcast-clj is built on,
each by the same author and each with an audience beyond podcasting:
wary-fetch, qname-hiccup, html-pieces, drop-in-search, mind-meld,
like-minded and websub-clj. It does for each of them what [the needs
of podcast-clj](needs-2026.md) did for the library itself, as of 11
October 2026 (wary-fetch 50f108b, qname-hiccup 42bd986, html-pieces
2a6111d, drop-in-search 1f4b5e9, mind-meld f5527a0, like-minded 6fc4264,
websub-clj a1bf7c4), and ends with what a 1.0 of each would take.

Each library was walked as a developer of its audience would use it,
outside podcasting: a webmention receiver and a feed reader on
wary-fetch, a site generator on qname-hiccup, a blog that sanitises
comments on html-pieces, a CMS that searches its records on
drop-in-search, a notes app on mind-meld and like-minded, and a feed
reader, a blog and a small hub on websub-clj. A second audience was the
author's other projects: podcast-clj itself and indieblog, which fetches
strangers' pages with wary-fetch and reads them with html-pieces. Every
claim was run in a fresh JVM from the library's own folder, and the
line references are to the library's own repository.

A **need** is what that audience can't do without and the library lacks
or gets wrong. When in doubt, an item went to the nice-to-haves. The
scores are those of the needs report: difficulty 1 an hour, 2 a day, 3 a
few days, 4 a week or two, 5 immense; gain 1 a few users notice, 2 a real
subset wants it, 3 most users of the library want it, 4 the library is
visibly broken for its audience without it, 5 nothing can ship without
it.


## Summary

The needs, by gain:

| Library | Need | Difficulty | Gain |
|---|---|---|---|
| wary-fetch | An IPv4 host read as the URL Standard reads it | 1 | 3 |
| html-pieces | A body start tag closes an open head | 1 | 3 |
| websub-clj | A hook for a confirmed subscription, with its lease | 1 | 3 |
| drop-in-search | The total number of matches with a page | 1 | 3 |
| like-minded | Finishing a step | 1 | 3 |
| wary-fetch | Network failures as typed data | 2 | 3 |
| wary-fetch | A body streamed to a sink within the limits | 2 | 3 |
| drop-in-search | A hook to normalise terms on both sides | 2 | 3 |
| mind-meld | An entry whose fields merge one by one | 3 | 3 |
| qname-hiccup | White space kept on request | 1 | 2 |
| qname-hiccup | Hiccup without an attribute map | 1 | 2 |
| html-pieces | Tables in text and Markdown | 1 | 2 |
| html-pieces | An unclosed row doesn't nest the next row | 1 | 2 |
| websub-clj | Discovery that reports a hub without a self link | 1 | 2 |
| websub-clj | A bound on deliveries under way at once | 1 | 2 |
| drop-in-search | Dates index as the same words on every platform | 1 | 2 |
| wary-fetch | The charset of an HTML page from its meta tag | 2 | 2 |
| qname-hiccup | The internal DTD subset read, within limits | 2 | 2 |
| html-pieces | Nested lists in text and Markdown | 2 | 2 |

Three of them are podcast-clj's own gaps seen from the other side.
[The needs report](needs-2026.md) asks for a dead network to be told
from a failing feed, which wary-fetch's typed failures would give it.
Its settings are one mind-meld entry per setting because an entry is one
register, and that is what breaks removal. And the twenty lines that
finish a sync step in app.library are what like-minded leaves to every
app.

Across the seven, the security claims hold on the JVM and the one hole
is wary-fetch's IPv4 parsing off the JVM. Every README says what its
library is for and shows the main call, the public APIs are the right
ones, and the floors of Clojure 1.11 and Java 11 are tested in CI. None
of the seven runs its tests in a browser, though all seven claim it.


## wary-fetch

A webmention receiver that fetches strangers' pages, a feed reader that
polls a thousand feeds, a page that fetches through the proxy, and a
tool that downloads files.

### Needs

**An IPv4 host read as the URL Standard reads it.** Difficulty 1, gain
3. The first promise of the README is that a stranger's URL can't reach
the local network. In Node and in a browser that breaks on an IPv4
address written in octal, hex or fewer than four parts. The parser
(url.cljc:411) reads only dotted decimal, so it reads 012.0.0.1 as
12.0.0.1, which is public. The URL Standard, section 3.5.3, reads a
leading zero as octal, and so do Node and every browser: Node 24 reads
`http://012.0.0.1/` as 10.0.0.1, and 0177.0.0.1, 0x7f.0.0.1 and 127.1 as
127.0.0.1. In Node, `send-public!` sees an address, skips the lookup,
calls it public and hands node:http the hostname that Node's own parser
gives, so it connects to 10.0.0.1. In a browser the three forms that the
parser can't read at all count as public names (url.cljc:480), and fetch
connects to 127.0.0.1. On the JVM the check and the client use the same
JDK parser, so they agree, though the JDK reads 0177.0.0.1 as 177.0.0.1,
unlike every browser. What it takes: the IPv4 parser of the URL Standard
in the address check, and a name of only digits and dots never counted
as a public name. The README's line that in Node "its guards are those
of the JVM" is wrong until then.

**Network failures as typed data.** Difficulty 2, gain 3. A failure has
a :type only for a timeout, a body too large, a refused request and a
failed one (wary_fetch.cljc:168). A host that doesn't resolve comes back
as the reason "java.net.ConnectException:
java.nio.channels.UnresolvedAddressException", and a refused connection
as the same class with ClosedChannelException. A feed reader must tell a
dead name from a refused port from a TLS failure to decide what to do
with a subscription, and today it matches Java class names, which differ
in Node. This is the need that podcast-clj's own report calls telling a
dead network from a failing feed. What it takes: a :type for an
unresolved name, an unreachable host and a TLS failure, set by each
client, and a :reason written for a person (async.cljc:113 is where the
text is made).

**A body streamed to a sink within the limits.** Difficulty 2, gain 3.
Every response holds its whole body in memory, up to :max-bytes, 100 MB
by default. The only streaming reader is `digest!`, JVM only, which
keeps nothing. A tool that downloads a file to disk, or a server that
relays one, has `fetcher` by byte ranges, which costs a request a part
and fails on servers that ignore ranges, or the whole file in memory.
The JVM client already reads in pieces under the size and time limits
(jvm.clj:41), and so does the Node client. What it takes: a :sink option,
a function of each piece or an OutputStream on the JVM and a writable
stream in Node, with the body nil in the response and the same limits
and redirect rules as `send!`. Podcast apps download episodes, so this
is a need of podcast-clj's download rules too.

**The charset of an HTML page from its meta tag.** Difficulty 2, gain 2.
Decoding reads the byte order mark, the Content-Type charset and the XML
declaration (encoding.cljc:55), as RFC 7303 says for XML. An HTML page
served as text/html without a charset, with a meta charset of
windows-1252 in its head, is decoded as UTF-8. That is how indieblog
reads strangers' pages for webmentions and sign-in. The HTML Standard,
section 13.2.3.2, prescans the first 1024 bytes for the meta charset.
What it takes: the prescan when the media type is HTML, and a test.

### Nice-to-haves

- A lookup-free public-host check on the JVM, the one the browser
  branch already does (url.cljc:498); indieblog wrote its own regex.
  Difficulty 1, gain 2.
- A :query-params key on a request, turned into the query string;
  indieblog calls `url/query-string` by hand in seven places.
  Difficulty 1, gain 2.
- The default :allow-pred of `fetch!`, `probe!`, `fetcher` and `digest!`
  set to `url/public?`; today each allows everything. Difficulty 1,
  gain 2.
- The proxy handler in ClojureScript too, for a Node server; its parts
  are cljc and only `handler` is JVM. Difficulty 1, gain 1.
- Brotli and zstd undone on the JVM and in Node, as fetch does in a
  browser. Difficulty 2, gain 1.
- A refusal, with a :type, of a URL that isn't http or https; `fetch!` of
  a file: URL throws the JDK's own IllegalArgumentException. Difficulty
  1, gain 1.

### Defects and rough edges

- A check with `url/public-name?` says that 127.0.0.1 and 10.0.0.1 are
  public names. Its docstring says it's about names, but a developer
  reaching for a cheap check will use it wrong.
- A request body that is a map is sent as its printed text (jvm.clj:131),
  with no error.
- On the JVM `try-send!` gives a future, so reading its value directly
  gives nil; the docstring says so, but the name doesn't.
- Header values are vectors on the JVM and in Node, so a plain lookup of
  content-type gives a vector; `headers/header` is needed and the README
  doesn't say so.

### Release readiness

The README shows `fetch!` first and names every public namespace, and
the internals are marked. JVM and Node are tested in CI with local
servers. The browser client has no test at all; the one named for a
browser runs in Node through the Node client. The tests are broad on
the JVM (redirects, limits, slow bodies, ranges, digests, the proxy)
and thinner in Node. A 1.0 needs the IPv4 fix, typed failures, a sink
and a browser run.


## qname-hiccup

A tool that reads config files, SVG and XHTML, a site generator that
writes a sitemap and an Atom feed by hand, and a feed reader on the
jsoup path.

### Needs

**White space kept on request.** Difficulty 1, gain 2. Text of only
white space is dropped unless it sits between two elements on one line
(whitespace.cljc:74). In mixed content the line break between inline
elements is a word space, so `<p><b>Hello</b>\n<i>world</i></p>` parses
to `[:p {} [:b {} "Hello"] [:i {} "world"]]` and reads "Helloworld", and
`<code> </code>` loses its space. XHTML, DocBook, TEI and SVG text are
all mixed content, and XML 1.0, section 2.10, gives xml:space="preserve"
for exactly this, which the parser keeps as an attribute and ignores.
What it takes: a :whitespace option, :compact by default and :keep to
pass every text node through, and xml:space honoured on the element and
its descendants whatever the option.

**Hiccup without an attribute map.** Difficulty 1, gain 2. Every Hiccup
renderer takes `[:title "Emma"]`, and anyone writing a sitemap or an
Atom feed by hand writes that. Passing it to `emit` throws a
ClassCastException from the serializer (serializer.cljc:114), and passing
it to `text` gives "", both without a hint. What it takes: accept the
form, as html-pieces does, or throw an error that names the element. The
README should say which.

**The internal DTD subset read, within limits.** Difficulty 2, gain 2.
With DTD support off (parser.cljc:47), a document that declares an
entity in its DOCTYPE fails with "The entity "e" was referenced, but
not declared", and an XHTML 1.0 page with `&nbsp;` fails the same way.
Inkscape's SVG and DocBook declare entities in the internal subset, and
XHTML needs the named references of its DTD. A browser's DOMParser
expands them, so the platforms differ. What it takes: the internal
subset on with external entities off and the JDK's entity expansion
limit kept low, or at least the HTML named references by option, and a
line in "What a tree keeps" that says the DOCTYPE is ignored today.

### Nice-to-haves

- A known namespace written as the default namespace on request, so
  that an Atom feed emits as `<feed xmlns="…">`, which readers and
  validators are used to (serializer.cljc:241). Difficulty 1, gain 2.
- The metadata recording every namespace in use, known ones too, so that
  a parsed tree emits without the table; today a parsed Atom feed has
  `(xml/namespaces tree)` of `{}` and `emit` throws "Unknown namespace
  alias". Difficulty 1, gain 2.
- A finder by path, e.g. channel, item, title; `element` and `elements`
  go one level. Difficulty 1, gain 2.
- A nil attribute value dropped rather than written as `x=""`.
  Difficulty 1, gain 1.
- Processing instructions kept on request, e.g. xml-stylesheet.
  Difficulty 2, gain 1.
- An option to leave out the XML declaration, for fragments. Difficulty
  1, gain 1.

### Defects and rough edges

- An undeclared prefix parses on the JVM and fails in a browser; the
  tests say so, the README doesn't.
- The README tells Node users to install xmldom as DOMParser. With
  xmldom's defaults a malformed document is logged and partly parsed
  rather than thrown, so the strictness the README promises depends on
  the onError option that only the test setup shows.
- The parse table is URI to alias, the emit map alias to URI. The README
  explains it, but every user writes an inversion once.

### Release readiness

The README shows `parse` and `emit` and names every public function,
and nothing public should be private. JVM and Node run in CI, the
browser doesn't, and the parsererror branch that only browsers take
(parser.cljc:95) is never exercised. The decoding of UTF-16 and the
parse of an InputStream have no test. A 1.0 needs the white space
option, a clear answer to attribute-less Hiccup, a stated entity policy
and a browser run.


## html-pieces

A blog that sanitises comments, a CMS that shows a field as text and
Markdown, and a server that reads whole pages of strangers, which is
what indieblog, podcast-clj's feed discovery and websub-clj's discovery
do with it. Walked as an attacker too, with 28 vectors of javascript:,
data: and event handlers, entity and tab tricks included: every one was
removed and every attribute value escaped. The sanitizer holds.

### Needs

**A body start tag closes an open head.** Difficulty 1, gain 3. The
`</head>` end tag is optional and commonly left out. A page without it
parses with the body inside the head, and `hiccup` drops :head with
everything in it (html_pieces.cljc:58), so
`<html><head><title>T</title><body><p>Hello</p>` gives `()` and `text`
gives "". With `</head>` written, the same page gives the paragraph. The
HTML Standard, section 13.2.6.4.4, pops the head on a body start tag or
any start tag that isn't a head element. This is the case of a reader
view, a reply context, a webmention source, and of finding a feed from a
show's website. What it takes: in the tree builder (tree.cljc:228),
close an open head on a start tag outside the head elements, the set
that websub-clj's document namespace already lists.

**Tables in text and Markdown.** Difficulty 1, gain 2. A table renders as
"h1h2\n12" in `text`: rows end a line, but there is no case for a cell
(render.cljc:189), so cells run together. In `markdown` the two rows come
out with no delimiter row (commonmark.cljc:528), so GFM doesn't read
them as a table. What it takes: a tab or two spaces between cells in
text, and the delimiter row after the first row in Markdown.

**Nested lists in text and Markdown.** Difficulty 2, gain 2. An inner
list comes out as a sibling at the same indentation, with a blank line
that splits the outer list, in both renderings, and an ordered list that
starts at 3 starts at 1. CommonMark 0.31.2, section 5.2, needs the inner
items indented under their parent. What it takes: the list depth in the
render context (render.cljc:106), an indent per level, and the start
attribute for the counter.

**An unclosed row doesn't nest the next row.** Difficulty 1, gain 2. The
table `<table><tr><th>h1<th>h2<tr><td>1<td>2</table>` gives a second :tr
inside the first. The rule for a row closes through the nearest of tr, td and
th (tree.cljc:37) and stops at the th, leaving the row open. A browser
repairs the serialized output, but a Hiccup renderer such as Replicant
draws nested rows. What it takes: close through the row and then any
cell above it, and the same for a cell within a row.

### Nice-to-haves

- The `<base href>` of a whole page honoured as its base URL (HTML
  Standard, section 4.2.3); indieblog does it by hand. Difficulty 1, gain 2.
- Bare URLs in plain text turned into links, as every comment system
  does (the GFM autolink extension). Difficulty 2, gain 2.
- A report of what was discarded and removed, the TODO of the root
  namespace. Difficulty 2, gain 2.
- An excerpt helper: the first n characters of the text at a word
  boundary. Difficulty 1, gain 2.
- The content of a form kept and the element dropped, as DOMPurify does;
  today a comment that holds a form loses its text. Difficulty 1, gain 1.
- A class collection in built Hiccup joined or refused; today
  `[:p {:class ["a" "b"]}]` serializes the printed vector. Difficulty 1,
  gain 1.
- Bidi controls left out of text and Markdown, as the other control
  characters are (Unicode TR 36). Difficulty 1, gain 1.
- Formatting carried past a misnested end tag, the adoption agency
  algorithm, documented as a TODO. Difficulty 3, gain 1.

### Defects and rough edges

- The four needs above are defects.
- A custom element with a dot in its name needs :shorthand? false to
  serialize; the README explains it, but a reader who missed that
  paragraph gets a class on the element.
- A :style map or an event handler in built Hiccup is stringified when
  an option allows the attribute; the README says they aren't supported,
  and a throw would be kinder.
- The README says data.json is needed "when the code compiles"; on the
  JVM it's a runtime dependency that reads the entities file.

### Release readiness

The README is the best of the seven: what the library is for, the four
main calls on one piece of HTML, every option, the Hiccup conventions
and the limits of "pieces, not documents". JVM and Node run in CI with
the html5lib tokenizer suite, with no browser run. The tests are broad
for sanitising, URLs, text, Markdown and built Hiccup, and thin for
tables and whole pages. A 1.0 needs the head fix, tables and nested
lists right, a sentence on what a whole page gives, and a browser run.


## drop-in-search

A CMS that indexes records with a title, a body, a status, an amount and
a date, and shows a results page with highlights and "1 to 10 of N", and
a blog that wants "posts" to find "post". The README example and the
CIFF round trip run as printed.

### Needs

**A hook to normalise terms on both sides of the index.** Difficulty 2,
gain 3. The README's principle is "No stemming", with the last word
completing as a prefix and a field given as a vector of terms taken as
it is, "so you can bring your own stemmer". The second half doesn't
hold. A field of stemmed terms is only reachable by a query given as a
vector of terms (queries.cljc:538), which has no operators, phrases,
prefixes or typos, and `snippet` matches the raw text against the
query's terms by equality (queries.cljc:554), so a stemmed index gets no
highlights: an index of `["run" "shoe"]` matches `["run"]` but not the
string "running", and a snippet of "Running shoes" for `["run"]` marks
nothing. The prefix rule helps only with the last word while it's being
typed, so "posts" never finds "post" in a blog, and the typo fallback
steps in only when the exact word is in no document at all. What it
takes: a :term-fn option of the index, kept in its defaults like
:boosts, applied to every token at index time (segment.cljc:286) and to
every term and phrase of a parsed query and of a snippet's spans. A
function isn't data, so the CIFF manifest and the EDN print can't carry
it; it must be given again to `restore` and `read-files`, as the schema
is given to mind-meld's merge. A day with the tests, and a paragraph in
the README in place of the half-true sentence.

**The total number of matches with a page.** Difficulty 1, gain 3. A
results page says "1 to 10 of 243", and a filter chip says how many
records match. A `query` with :limit gives the page alone, and its
metadata holds only the typos (drop_in_search.cljc:1124); the matches
are found whole and then cut (line 939). An app that wants the count
runs the query twice. With :filter-pred the count is only known after
the predicate has run over everything, which the cut avoids on purpose.
What it takes: a :total in the metadata of the page whenever it's known,
which is without a filter and when the filter had to read everything
anyway, and a docstring that says when it's absent. An hour.

**Dates index as the same words on every platform.** Difficulty 1, gain
2. A field "holds text, or anything whose text counts", and the text is
the value's string (segment.cljc:286). For an instant that is
`java.util.Date.toString` on the JVM and `Date.toString` in JavaScript:
a record of 2024-03-01 indexes as "fri mar 01 01 00 00 cet 2024" on one
and a longer string on the other, both in the local time zone, and the
query 2024-03 finds nothing. The README's promise of the same results on
each platform doesn't hold for a field with a date, and a nested map
indexes its keys as words too. What it takes: print an instant as ISO
8601 in UTC and leave the rest to the string, and a line in the ns
docstring on what a value's text is. An hour.

### Nice-to-haves

- The README saying when to switch from EDN to CIFF, with numbers: at
  20,000 posts of 120 words the EDN is 53 MB, 2.3 s to print and 3.5 s
  to read, against 23 MB, 0.7 s and 0.5 s for CIFF. Difficulty 1, gain 2.
- A `snippet` that honours the field of a query; today title:foo marks "foo"
  in the body text too (queries.cljc:589). Difficulty 1, gain 2.
- Unknown options and unknown fields in :boosts or :fields refused;
  today :limt is ignored and an unknown field finds nothing. Difficulty
  1, gain 2.
- A count of the documents and a lookup by id; the tests read the index
  map directly. Difficulty 1, gain 2.
- A `snippet` and a `matches?` that take the index itself, so that
  `query-opts` isn't a step every app repeats. Difficulty 1, gain 2.
- Facet counts of a stored key over the matches. Difficulty 2, gain 2.
- Elisions: "Simon's" is the phrase "simon s", so "simons" misses it;
  Lucene's rule. Difficulty 1, gain 1.
- A synonyms option, read at query time. Difficulty 2, gain 1.
- Which terms matched in each result, for the UI. Difficulty 2, gain 1.
- A property test that a document matches `query` exactly when every
  field passes `matches?`, to guard the ranking code. Difficulty 2,
  gain 1.

### Defects and rough edges

- Amounts: 1250.5 prints as "1250.5", so a query of 1250.50 is the
  phrase "1250 50" and misses it.
- Two vars named `default-limits` with different keys
  (drop_in_search.cljc:200 and queries.cljc:34).
- The `ids` of an empty index are nil, not an empty list.
- Performance is fine: 20,000 documents index in 2 s, a one-word query
  takes 10 ms, a prefix 50 ms, a typo 70 ms, and adding a document 1 ms.

### Release readiness

The README shows `query` first and runs as printed. The docs are the
README and the docstrings; the twenty or so options of `index`, `query`,
`parse` and `snippet` have no page of their own, and the shape of the
index is public by use but not by word. The internals are marked var by
var, though the segment namespace isn't marked as a whole. JVM and Node
run in CI; the browser is claimed and untested, though the code uses
only what every current browser has. The tests cover ranking, operators,
typos, scripts without spaces, damaged CIFF files, EDN round trips,
limits and ties, with no generative test. Already on Clojars with a
tagged release. A 1.0 needs the term hook decision, a reference page of
the options, the index shape made opaque or documented, the CIFF
manifest version promised stable, and a browser run.


## mind-meld

A notes app that keeps notes by id, each with a title, a body and tags,
and a todo app with a done flag. Two devices change things offline and
merge. The README examples run as printed, the ordered list included.

### Needs

**An entry whose fields merge one by one and can still be removed
whole.** Difficulty 3, gain 3. An entry is one last-writer-wins register
(tree.cljc:9). The phone renames a note while the laptop edits its body,
and after the merge the title change is gone. The same for a todo whose
text is edited on one device while the other ticks it done. That is
what every notes and todo app does offline, and silent loss is what a
CRDT is for avoiding. The README's way out is a deeper schema, one entry
per field, which podcast-clj uses for its settings (profile.cljc:259).
That breaks removal in two ways. A tombstone written over the map of
fields wins every merge, since the map has no stamp and counts as older
than anything (entry.cljc:87), so a later edit of a field is lost, and
`problem` then reports the tombstone as an entry that is not a map. A
:removed? field entry instead keeps the fields, but `live?`
(mind_meld.cljc:58) reads the field entry's map as true, so a note
un-removed with a value of false still counts as removed, and `compact`
(tree.cljc:39) never drops the note. What it takes: the LWW register per
key of Shapiro et al. 2011, section 3.2.1, as Riak's map and Automerge's
map do. A schema option under which an entry's keys each carry a stamp
while the entry as a whole still has :removed?, :updated-at and :device
for `live?`, `compact`, `acknowledge` and the registry; a join that
merges such an entry key by key when neither side is a tombstone and by
the later stamp when one is; a `write` that takes the path to the field
and stamps both; and `problem`, `changes-since` and `latest-by-author`
taught the shape. A few days, with the convergence specs extended to
field writes. Short of that, a README section on modelling that says an
entry is one register, shows the per-field schema with a :removed?
field, and gives a `live?` that reads it.

### Nice-to-haves

- A bound on how far another device's clock can pull this one. After a
  merge with a device a year ahead, every later write on every device is
  stamped in 2027 (mind_meld.cljc:82), so "edited at" lies for good.
  CockroachDB's hybrid logical clock refuses offsets beyond a maximum.
  Difficulty 2, gain 2.
- A modelling guide: an entry is one register, a set is a collection at
  depth 2 with tombstones, a count needs one writer per device so the
  device id goes in the path (entry.cljc:102 says so in a comment, the
  README doesn't), and a sum of a counting collection across devices as
  a function. Difficulty 1, gain 2.
- A `remove` function that writes a tombstone, since the assoc of
  :removed? is in every app. Difficulty 1, gain 2.
- A Clojars release with a build.clj as drop-in-search has; like-minded
  and podcast-clj pin a Git SHA. Difficulty 1, gain 2.
- A `problem` that reports a state without a :device; now it passes, and two
  such states merge to a nil device. Difficulty 1, gain 1.
- A browser run of the tests in CI; the generative tests run on the JVM
  only. Difficulty 1, gain 1.

### Defects and rough edges

- A `write` with a function that returns something other than a map or
  nil throws a ClassCastException from the stamping (entry.cljc:15), not
  a message; a constant string is an easy mistake.
- A `rank-between` with ids that aren't in the entries silently gives the
  rank of an empty list, "V", which sorts the moved item first
  (list.cljc:53); `rank/between` throws in the same case.
- Performance is fine: merging two states of 20,000 entries takes 15 ms,
  and `problem` and `changes-since` about the same.

### Release readiness

The README shows `write` and `merge` first and runs as printed. The ns
docstring holds the state shape and the schema, and the tie-breaking
rule and the registry are in the README's principles; a modelling guide
is the gap. The public API is right and the internal namespaces are
marked. JVM and Node run in CI; the browser is claimed and untested.
The test.check properties cover commutativity, associativity,
idempotence, deltas, random syncs and compaction, and not per-field
schemas, late stamps or clock skew. Not on Clojars. A 1.0 needs the
field-wise entry or its documented substitute, the modelling guide,
clear errors from `write`, and a release.


## like-minded

The notes app syncs through a Dropbox folder from a phone and a laptop,
encrypts the folder, and runs in a browser too. The README examples run
as printed, `next-sync` included, and the store check passes on the
folder and on the encrypting store over it. A Dropbox "conflicted copy"
of a device's document is read as one more state and merges harmlessly.

### Needs

**Finishing a step is the app's to write.** Difficulty 1, gain 3. A step
returns the state, the cursors and the report (like_minded.cljc:242).
The docstring then says: don't put the result in place of the state, but
merge it into the state as it is by then with the merge of the CRDT, and
compact as the step did, since the merge brings back the tombstones that
the step dropped. Then `next-sync` wants :at, :changed? and :failures
(line 142), and the README says to give it the :at of the last step, but
the step carries no :at, no count of failures and no flag of failure. So
every app writes the same twenty lines, and podcast-clj did
(app/library.cljc:745 to 766): merge the step's state into the current
profile, compact at the step's time, count the failures from the report,
keep the time and the clock. An app that skips the compaction gets its
tombstones back on every sync, and one that forgets the merge loses what
the user typed during the step. These are the two mistakes the docstring
warns of, and the library leaves both to the reader. What it takes: :at
and :failed? in the step, and a function that merges a step's state into
the current state by the CRDT of the options, compacts at the step's
time with its :compact option, and gives the state and the failures so
far, so that `next-sync` can take the step and the state. An hour, with
podcast-clj's `library/sync!` as its test.

### Nice-to-haves

- A clear failure for a state without a :device: the document is named
  ".edn", the folder store refuses it as a hidden file, and the report
  says a document's name is a plain file name; `sync!` could check first,
  and the README could say how to make a device id. Difficulty 1, gain 2.
- A guard against overlapping steps, which the docstring tells the app
  to run one at a time. Difficulty 1, gain 2.
- A way to leave: retire this device and delete its document in one
  call, for "turn sync off" in the settings. Difficulty 1, gain 2.
- Changing the passphrase of an encrypting store, which re-seals every
  document; now a new passphrase leaves the old documents unreadable.
  Difficulty 2, gain 2.
- Stores over the APIs of Dropbox, Google Drive and iCloud, for phones
  and for browsers other than Chrome, where there is no folder to pick;
  a documented gap of podcast-clj as well. Difficulty 3, gain 2.
- The whole state in one document on every change: 20,000 notes are a
  2.4 MB write per device per change; a document per collection would
  cut it. Difficulty 4, gain 2.
- Attachments: the stores take bytes, so an app can put an image beside
  the documents, but nothing says so. Difficulty 1, gain 1.
- Conflicted copies of a device's own document are never cleaned up.
  Difficulty 1, gain 1.
- A browser run of the tests in CI; the IndexedDB test checks nothing in
  Node. Difficulty 2, gain 1.

### Defects and rough edges

- A `backend` or a `sync!` without a :schema or a :crdt throws a
  NullPointerException, not the message they mean to (crdt.cljc:20).
- A `sync!` with a fixed :at in the past reports clock skew for every
  document, as designed; worth a sentence in the docstring, since a test
  or a replay hits it.
- Performance is fine: a step that writes 20,000 notes takes 240 ms, and
  the other device pulls them in 450 ms.

### Release readiness

The README shows `sync!` first and runs as printed, and the reference
page has the store and backend contracts, the options, both strategies,
the document and the envelope; the providers page has the folders and
URLs of the common services, dated. The public API is right and the
internals are marked. JVM and Node run in CI, with the WebDAV listings
parsed in Node; the browser stores run only in a manual build. The tests
cover the convergence through both strategies, the store contract of
every store, WebDAV against a stand-in server with Nextcloud fixtures,
envelopes sealed on the other platform, and the diagnostics catalogue
against the source. Depends on mind-meld by Git SHA, two commits behind
its master. A 1.0 needs the step helper, the NullPointerException fix,
mind-meld and itself on Clojars, a device-id sentence, and a browser run
in CI or a documented manual one.


## websub-clj

A feed reader that subscribes at a hub and renews, a blog that publishes
to a hub, and a small hub for one site's feeds.

### Needs

**A hook for a confirmed subscription, with its lease.** Difficulty 1,
gain 3. WebSub 5.3.1 makes the subscriber renew before the lease ends,
and `next-renewal` exists for it. But the lease reaches the app only as
an argument of :wanted-pred (websub.cljc:511), a predicate, so a feed
reader records the start of the lease and schedules the renewal as a
side effect of answering yes. A predicate that must write to a database
is the wrong shape, and nothing tells the app that an unsubscription was
confirmed either. What it takes: a :verified! function of the mode, the
topic, the lease and the request, called after the challenge is
answered, beside :wanted-pred, which stays a pure check.

**Discovery that reports a hub without a self link.** Difficulty 1, gain
2. An RSS feed with a hub link and no self link gives `discover` of nil
and `discover!` of the status alone. WebSub 5.1 does make the self link
the topic, and :url-as-topic? exists for the case, but the app can't
know when to turn it on, since the result doesn't say that a hub was
named (websub.cljc:105 needs both to say anything). What it takes: the
hubs whenever there are hubs, with the topic nil when the self link is
missing, and a line in the README's "Subscribe".

**A bound on deliveries under way at once.** Difficulty 1, gain 2. The
hub sends to every subscriber of a topic at once (hub.cljc:597), which
on the JVM starts a future for each. A topic with a few thousand
subscribers starts that many connections, and a slow subscriber holds a
thread for the whole timeout. wary-fetch has `async/in-groups` for
exactly this. What it takes: a :concurrency option among the hub's
defaults, used in the delivery.

### Nice-to-haves

- A clear error when a secret forces https on an http hub and the https
  call fails; today it's the plain connection error of the https URL.
  Difficulty 1, gain 2.
- The hub's job as a map with a scheduling example on a database queue
  in the README, since every hub needs one. Difficulty 1, gain 2.
- JSON Feed 1.1 hubs in discovery; the document kind is nil for JSON.
  Difficulty 1, gain 1.
- A store contract test that a database store can run. Difficulty 1,
  gain 1.
- html-pieces bumped from 0.2.0 to 0.3.0 in deps.edn. Difficulty 1,
  gain 1.

### Defects and rough edges

- A delivery's :topic is nil when the hub sends no Link header, which the
  docstring says; the README's example reads the body only and doesn't
  say that the callback URL is what identifies the subscription.
- The README says the library runs in the browser. Its functions do, but
  a subscriber is a server with a public callback, and a page can't be
  one; a line on what a page can do (discover, publish through a proxy)
  would stop the question.

### Release readiness

The README shows the main call of each role: `discover!` and `call!`,
`publish!`, and `hub/handler` with `run-job!`. The docstrings cover the
public API, and the internals are marked. The JVM tests include an
end-to-end run of a subscriber, a publisher and a hub over localhost
with the websub.rocks scenarios by number, and the cljc tests run in
Node in CI; a browser build is defined and not run. A 1.0 needs
:verified!, discovery that reports, a concurrency bound, the html-pieces
bump and a browser run.


## Release readiness across the seven

| Library | Clojars | Tags | Docs beside the README | Build |
|---|---|---|---|---|
| wary-fetch | 0.1.0 | v0.1.0 | none | build.clj |
| qname-hiccup | 0.1.0 | v0.1.0 | none | build.clj |
| html-pieces | 0.3.0 | v0.1.0 to v0.3.0 | benchmarks | build.clj |
| drop-in-search | 0.1.0 | v0.1.0 | none | build.clj |
| mind-meld | none | none | none | none |
| like-minded | none | none | reference, providers | none |
| websub-clj | 0.1.0 | v0.1.0 | none | build.clj |

All seven run CI on Java 25 and Node 24 with a Java 11 job for the
floor, declare Clojure 1.11 and test on 1.12, and have a LICENSE. None
has a cljdoc.edn, so cljdoc shows docstrings alone; podcast-clj has one.

The dependency chain is half released. like-minded takes mind-meld by
Git SHA, and podcast-clj takes like-minded the same way, two commits
behind mind-meld's master. websub-clj and podcast-clj pin html-pieces
0.2.0 while 0.3.0 was tagged on 11 October. Everything else comes from
Clojars at 0.1.0. A release of mind-meld and then like-minded, with a
build.clj each, closes the chain; the bump of html-pieces is a line in
two files.

The policies that exist are the release steps in each build.clj, a
Clojars badge on the released ones, and no CHANGELOG by the author's
rule. The policies that don't exist, and that a 1.0 would state in each
README:

- A version scheme: what a 0.x promises, and what a breaking change
  does to the version.
- A compatibility policy for the formats that outlive a version:
  like-minded's sync document (version 1, with a diagnostic for a newer
  one), drop-in-search's CIFF manifest (version 1, the same), and
  podcast-clj's saved library, which gets version 2 in the identity and
  storage design.
- A deprecation practice: how long a renamed function keeps its old
  name.
- A platform statement per library: the JVM and Node are tested, the
  browser is claimed. One shared browser step in CI, e.g. Playwright
  over each library's test build, would make the claim true for all
  seven at once.


## What to build first

By gain and then by difficulty:

1. wary-fetch's IPv4 parser, an hour, since it's a security promise that
   doesn't hold off the JVM.
2. The hour-long needs with gain 3: html-pieces' head fix, websub-clj's
   :verified!, drop-in-search's total, like-minded's step helper, with
   podcast-clj's `library/sync!` rewritten on the last.
3. wary-fetch's typed failures and sink, and drop-in-search's term hook,
   a day each.
4. The gain-2 needs of an hour each: qname-hiccup's white space and
   attribute-less Hiccup, html-pieces' tables and rows, websub-clj's
   discovery and concurrency, drop-in-search's dates.
5. mind-meld's field-wise entry, a few days, with podcast-clj's settings
   as the first user; or its documented substitute first.
6. The releases: mind-meld, then like-minded, then the bumps in
   websub-clj and podcast-clj.
7. The browser step in CI, once, for all seven.


## Method

Two readers took the seven libraries in two groups, each with the same
brief: read the README, the docs, every public namespace and var and
the tests, then walk the library's typical use as a developer of its
audience would, outside podcasting, and confirm every claim in a fresh
JVM from the library's own folder. Each wrote a report of needs,
nice-to-haves, defects and release readiness with scores, line
references and the standard each feature builds on. The author's own
projects, podcast-clj and indieblog, were the second audience.

Every finding in this page was then re-run by the editor in a fresh JVM,
with Node's own URL parser for the IPv4 case. All of them held. The
release facts were gathered from each repository's deps.edn, tags, CI
workflow and Clojars page.
