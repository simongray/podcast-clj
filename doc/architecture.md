# Architecture

This page explains how each part of podcast-clj is built.
[Principles](principles.md) sums up what guides its design, and
[Syncing](sync.md) explains the sync between devices.


## Layers

```
  5  high level   app.library  app.show  app.episode  app.feeds
  4  root         dk.simongray.podcast
  3  companions   feed.resolve  feed.check  feed.discovery  feed.publish  format.*  value  value.*  directory  directory.*  updates.*  comments  comments.*  analytics  analytics.*  profile  search  downloads  sync  sync.*
  2  feed model   feed  feed.rss  feed.coerce  feed.validate  feed.schema
  1  vocabulary   feed.spec  feed.quirks
  0  primitives   util.*  crypto.*  feed.guid  diagnostics
```

A namespace only requires namespaces in its own layer or below. The
registry `feed.spec` knows nothing about parsing, `feed` knows nothing about
HTTP, and the companion formats need only the primitives and each other.
The `app` namespaces are the only ones that require the root namespace,
because they call `fetch!`. The names show two of the layers: `app` holds
the top layer and nothing else, and `util` and `crypto` hold the
primitives. A test in `architecture_test.clj` reads the `ns` forms, and
fails on a require that points up a layer, on a cycle, or on a name in the
wrong layer.

A view is a map of the results of the functions below it, under the same
keys, e.g. `(:enclosure (episode/view show item))` is what
`(resolve/enclosure item)` returns. An app can use the view when it wants
everything, and call the function below when it wants one thing.

[Namespaces](namespaces.md) lists the namespaces by subject. These docs
write a function with the last part of its namespace as the alias, e.g.
`resolve/title` is in `feed.resolve`.


## XML as Hiccup

`util.xml` reads XML into Hiccup and writes it back:

```clojure
[:podcast/transcript {:url "https://…/ep1.vtt" :type "text/vtt"}]
[:title {} "Episode 3"]
```

The attribute map is always there, and the namespace of a tag is the
canonical alias of its namespace URI, so RSS 2.0 elements have none. The
aliases come from a table that the caller gives, so `util.xml` knows
nothing of podcasts: the table of a feed's namespaces is in `feed.spec`.

The platform parser (StAX on the JVM, `DOMParser` in JavaScript) doesn't
resolve namespaces. A pure function does that afterwards, which gives the
same result on both platforms and lets it forgive what real feeds get wrong,
such as variant URIs, undeclared prefixes, or the Atom default namespace
that several large hosts put on `<rss>`.


## The tag registry

`feed.spec` describes each element that podcast-clj understands as a map:

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

Three interpreters read it: `feed/parse` turns Hiccup into the model,
`feed/emit` writes the model back in canonical form, and `validate/validate`
reports what breaks the specs, with the requirements of PSP-1 and Apple
Podcasts on top. The Malli schemas in `feed.schema` and the [feed
model](feed-model.md) reference are generated from it too, so none of
them can drift from the code.

The registry is a value, and each interpreter takes it as `:registry`. A
host's own elements, or a new tag that podcast-clj doesn't know yet, can be
added without a fork, and they're read, written and validated like the
standard ones. A change to a spec is a change to data.

The schemas describe what `emit` accepts. `parse` is lenient, so it can
return a map that doesn't match, and the validator says why. The schemas
are for writing feeds and the validator is for judging them.

The specs are the boundary. Elements that no spec defines, like the
proprietary namespaces of hosts, stay outside the registry: the parser keeps
them as Hiccup under `:xml/unknown`, and the validator reports them. A
non-standard element only gets a quirk when it stands in for a standard
one, e.g. an item's `image` with only attributes becomes an `itunes:image`.
RSS 2.0 is the only container, so `parse` refuses Atom and RSS 1.0.


## The feed model

The model is plain maps with a key namespace per vocabulary, so
`:rss/title` and `:itunes/title` can both be present and each keeps what
the feed said. Unknown elements stay as Hiccup and unknown attributes as
strings, so `emit` writes back what `parse` read, in canonical form. XML
comments and processing instructions such as `xml-stylesheet` are dropped,
and the report names every other loss, e.g. a second copy of an element
that the spec allows once.

A producer can write shorthand, e.g. `{:rss/guid "x"}`, or a single value
where the model has a vector. `feed/normalize` expands it, and `emit` and
`validate` call it first, so the rest of podcast-clj only knows one shape.

Identity is a function, not a field, and has a namespace of its own,
`feed.guid`, so that comparing feeds needs no parser. Its `feed-guid`
gives the `podcast:guid`, or the UUIDv5 of the feed URL, and `item-guid`
gives the `<guid>`, or the enclosure URL. The id of an episode is the
pair of the two, from `episode-id`, and `feed/diff` compares two parses
of a feed by this identity.


## Resolving the vocabularies

An app usually wants one title, one image and one duration. `feed.resolve`
settles the overlaps in pure functions, and the docstring of each accessor
gives its precedence. Where the specs let an item inherit from its channel,
the accessor takes both the feed and the item.

`sources` tells where each answer came from, as paths like
`[:item :rss/title]`. It has no table of precedences of its own: it calls
the accessor on one field at a time and keeps the fields that give the same
answer alone. So it can't disagree with the accessor.


## Quirks and reports

`feed.quirks` holds small, named functions of two kinds. Repairs fix the
XML text when the strict parse fails, e.g. a byte order mark, HTML entities
like `&nbsp;`, or bare ampersands. Normalisations fix the tree, e.g. variant
namespace URIs or `yes` in `itunes:explicit`. [Diagnostics](diagnostics.md)
lists them all. If the document is still malformed and `org.jsoup/jsoup` is
on the classpath, its XML mode reads the document into the same raw Hiccup,
so all later steps are shared.

`{:quirks? false}` gives the strict path, and `:except` leaves out the
quirks it names. Each layer reports only what it did, and `rss/parse`
joins the reports in order. Metadata doesn't survive printing, transit or
`into {}`, so an app that stores a parsed feed must store its report next
to it.

Diagnostic ids are part of the API, since apps match on them. The
`diagnostics` namespace lists each id with its level and meaning, and a test
keeps the list and the source in sync.


## Companion formats

- **Chapters** are read from JSON Chapters and Podlove Simple Chapters into
  one shape, or inferred from timestamps in the show notes. More episodes
  have such a list than link a chapters file. The report marks inferred
  chapters.
- **Transcripts** are read from SRT, WebVTT, JSON, HTML and plain text into
  one segment model. Real files taught the reader a few things, e.g. caption
  files only name the speaker when it changes, so the reader carries the
  name over.
- **OPML** carries the profile in the parts of OPML 2.0 that already
  fit it: tags as categories, the time of subscription as `created`, and
  followed lists as includes. Other apps read the rest as they always did.
- **HTML** in show notes is read by a small lenient parser that works like a
  browser, with no dependency, so the JVM and JavaScript agree. `sanitize`
  keeps what an app can render safely. The result is Hiccup, since a Clojure
  app gives Hiccup straight to its renderer.
- **Media files and images** are read without I/O. A reader works on the
  parts of a file that have arrived, and when it needs more, it throws the
  byte ranges it needs. `util.bytes.view/read!` fetches them and reads
  again. So the readers are pure, and one loop does the fetching, with
  futures on the JVM and promises in JavaScript. A host that inserts ads can send a different
  file to each request, so a duration is that of the file that came.


## The network

podcast-clj asks a server for as little as it can:

- `podcast/fetch!` sends the ETag and Last-Modified of the last fetch, so an
  unchanged feed costs a 304, and a poller waits as long as the server and
  the feed ask.
- The media and image readers use byte ranges and stop when they know
  enough. Most media files take one or two requests and a few hundred
  kilobytes.
- `check/check-files!` probes each file that a feed links to with a HEAD
  request and two bytes, and reads more only when asked.

Every function that fetches has limits of size, time and requests in the
`default-limits` of its namespace, and its options change each one.

`feed.check` is split like the rest of podcast-clj: `files` lists what to
fetch and `verdicts` turns what came back into diagnostics, both pure, and
`check-files!` only sends. Its rules come from the standards first (RFC
9110, RSS 2.0, the podcast namespace, and the Fetch standard for CORS), and
the requirements of PSP-1 and Apple Podcasts add theirs on top. A browser
hides much of an answer from a page, so a check sees most on the JVM or in
Node.


## Security

### Untrusted URLs

Some URLs come from people the app has no reason to trust. The servers in a
comment thread name posts and authors, a feed names the domain of a
Lightning address, and anyone who pays a Lightning address names the URL of
its metadata. A request to such a URL can make an app on a server reach its
own network, with its routers, internal services and cloud metadata.

Each function that sends requests takes an `:allow?` predicate and puts it
on every request map, so `http/send!` and an app's own `:send` can both
check it. The functions that fetch untrusted URLs default to `url/public?`,
which refuses loopback, private and other special addresses. The functions
that fetch a feed or the files it links to have no guard by default,
because the app chose the feed. A server that fetches feeds for other people gives them
`{:allow? url/public?}`.

On the JVM, `public?` resolves the name and checks every address, and
`http/send!` checks each redirect too. It follows redirects itself, because
the JDK client sends the `Authorization` header on to other hosts. A browser
can't resolve names and follows redirects without telling, so there
`public?` only refuses the addresses and local names that a URL contains. A
request from a browser comes from the user's own device anyway, but a Node
server that fetches untrusted URLs should give these functions its own
`:send`.

### Untrusted XML

A feed can come from anyone, and XML lets a document make its parser read
local files or expand a few entities into gigabytes. So the JVM parser
reads no DTDs and resolves no external entities, and the reader stops at
a fixed depth of nesting on both platforms, before it can overflow the
stack.

### The proxy for a web page

A page can only read the answers of servers that allow it, so
`util.proxy` sends the requests of a page from your server. It carries
plain HTTP and has no format of its own: the page asks for
`/proxy?url=…`, and gets the answer of the other site. What HTTP has no
place for travels in headers: the URL after redirects as the
Content-Location, and a failure of the proxy itself as the Proxy-Status of
RFC 9209. So the page gets what `http/send!` gives on the JVM, and the
same exceptions.

The answers come from other sites but have the origin of your app. So the
proxy refuses a request that opens it as a page, or that comes from
another site, as the Fetch Metadata headers of the browser tell, and every
answer has a Content-Security-Policy that sandboxes it, for a browser
that doesn't send those headers. Only the headers that the proxy lists
pass, so no other site sets a cookie for yours, and neither the page's
cookies nor its credentials go on. Its requests carry a Via, so it can
refuse to fetch itself, which would multiply each request.

### Secrets

An app holds the listener's secrets, like the password of a private feed, a
service token or a wallet key, and it prints its data to logs and at the
REPL. A function that takes a secret also takes it wrapped by `secret/hide`,
which prints as `#<secret>` on both platforms. Functions that create
secrets wrap them, including the Authorization and Cookie headers of a
request map. `http/send!` unwraps them when it sends, and another HTTP
client needs `http/revealed` first.

This only protects against printing a secret by accident. A debugger or a
browser console shows the fields of an object, and a private feed with a
token in its URL has the token in every address.

### Payments

A payment moves real money, so this table lists who can attack one and
what stops them.

| Who | What they can do | What stops it |
|---|---|---|
| A relay | Read the kind, the public keys, the time and the size of each event | Nothing. NIP-44 pads the content, so the size tells little. |
| A relay | Read or change a command or an answer | NIP-44 encrypts the content, and the signature of the event covers it. |
| A relay | Answer in place of the wallet, or replay an old answer | `answer` takes only the wallet's signed event for this connection, and `ask!` only the answer to its own request. |
| A relay | Refuse the subscription but take the event | The event only goes out once the relay confirms the subscription. |
| A relay | Hold back the answer, so the listener pays again | `:value/wallet-silent` says the request went out, and until when the wallet can pay. |
| A relay, or anyone reading one | Send the wallet the same request again | The request expires, and the wallet is trusted to run an event once. |
| A feed | Name the recipients and their shares | Nothing. The app shows them to the listener before paying. |
| A feed | Replace the record of the payment with a custom record | `value/records` leaves out the types that the Lightning protocol reserves. |
| A sender | Claim a larger payment in its record or metadata | `value/as-received` takes the amount from the node and reports the difference. |
| A Lightning address service | Give an invoice for a higher amount | `lnaddress/invoice` checks the amount. |
| A Lightning address service | Give an invoice for other terms than it served | Nothing. LUD-06 has the wallet check the description hash, but the service writes both, so the check would stop no one, and some services hash other terms than they serve. |
| Anyone on the network path | Change an invoice or a node | An answer over plain http is an error. |
| Whoever writes a connection URI | Name a relay on the app's network | `:allow?` is `nostr/public?`. |
| A wallet | Claim it paid without paying | For an invoice, `ask!` checks the preimage against the payment hash, and the answer has `:proven?`. Keysend has no such proof. |
| Whoever reads the app's logs | Find the secret of a connection | podcast-clj puts it in no event, diagnostic or exception, and a printed connection shows `#<secret>`. Keeping the URI safe in storage is up to the app. |
| Whoever times the app | Learn bits of the secret key from many signatures | Little, since `sign`, `public-key` and `shared-x` aren't constant-time. Every command uses them, so don't poll the wallet, e.g. for its balance. |


## Directories and the Podcast Index

`directory` puts Apple Podcasts, fyyd, gpodder.net and the Podcast Index
behind one shape. The results have the keys of a parsed feed, so
`feed.resolve` works on them, and what only a directory knows goes under
`:directory/*`. `combine` merges the answers of several sources into one
entry per show, so a chart entry, which names no feed, gets its feed URL
from a lookup or another source. A search asks all sources at once, and a
source that fails ends up in the `:report` instead of throwing.

A chart can be for one category, but each source names categories its own
way. The Podcast Index silently gives the chart of all categories for a
name it doesn't know. A chart of the wrong shows is worse than none, so
podcast-clj puts that source in the `:report` instead. It does the same
with a source that can't narrow its chart to the country or the language
asked for, such as gpodder.net, which has one chart for the whole world.

A directory knows the URL of a feed, but rarely the guid that the feed
declares, and a feed that moved keeps the guid of its first URL. So a
result only has the guid that the directory says the feed declares, and
`combine` tells feeds apart by that guid and by the one that the URL
gives.

`directory.podcastindex` signs its requests for an instant the caller gives,
and maps the objects of the index onto the model, so a feed from the index
works like a parsed one. Its own fields are under `:index/*`.


## Podping and WebSub

Both tell an app that a feed has changed. With Podping, a host announces it
through podping.cloud, which writes it to the Hive blockchain, and anyone
can read the chain. `updates.podping` builds the JSON-RPC calls as request
maps and reads the pings from the blocks. Anyone can write to the chain, so
`writers` reads the accounts that the `podping` account follows, and
`poll!` keeps only their pings. A poller repeats `poll!` as it repeats
`fetch!` for a feed, and when a node leaves out blocks, it continues from
before the gap.

With WebSub, a hub sends the feed to each subscriber when it changes, so a
subscriber must be a server with a public URL. An app on a phone uses
Podping instead, and a server that keeps the feeds of many listeners can use
both. `updates.websub` has `discover`, `request`, and a Ring `handler` for
the callback, which checks the hub's HMAC signature before the app sees the
feed. It answers 200 to a wrong signature too, so a stranger learns nothing
about the secret.


## Links to the apps

No standard defines a link to a show in an app, and the forms that apps use
change over time. So `directory.links` keeps them as data in `links/apps`,
and an app can give its own table. [Services and their
defaults](services.md#the-links-of-the-apps) has the table with its date. A
form is a vector of texts and keys, not a template string, so there's
nothing to parse. A link to a moment in an episode uses the one standard
there is, the W3C media fragment `#t=754` on the URL of the media file.


## Comments

`podcast:socialInteract` names the root post of a thread about an episode,
usually the podcaster's own post. `comments` reads threads on ActivityPub,
the AT Protocol and Nostr in one shape, and hands each call to the reader of
the protocol. If the podcaster chose `disabled`,
`resolve/social-interacts` gives only that.

A thread is data. `fetch!` adds the comments and their authors by id, and
records how far it read, so a second `fetch!` continues where the first
stopped. The requests it needs next come from `requests`, and the answers
go in through `with-exchanges`. Both are pure, and `fetch!` only sends and
receives. Failures that pass with time, such as a timeout or a 429, are
tried again on the next fetch.

The content of a comment is sanitised HTML whose links carry
`rel="nofollow ugc"`. Images are replaced by their alt text, since an image
that loads tells its server who reads the thread. `comments/parts` gives
the content as text, links, mentions and hashtags in one shape for all three
protocols, and the app gives one `:link` function that says where a mention
or a hashtag leads. So one renderer works for all three.

Each protocol has its own idea of who speaks for a post:

- On ActivityPub, a server speaks only for its own posts and authors
  (FEP-fe34), so the posts of other servers are fetched from those servers.
  When a server won't give the root post to the reader, e.g. because it only
  answers signed requests, `fetch!` reads the thread through the server's
  Mastodon API instead.
- On the AT Protocol, only an AppView knows the replies to a post, and the
  app chooses the AppView. The labels of the protocol apply, so replies that
  a moderation service hid, or whose authors only want signed-in users to
  see them, are left out.
- On Nostr, a relay can send anything, so the signature is what counts, and
  the reader keeps only the events that `nostr/verified?` accepts. NIP-73
  gives an episode an id of its own, so a thread needs no tag in the feed,
  and an app can show comments for every episode.


## Nostr

`util.nostr` holds the protocol that both the comments and the wallets
speak, and it knows nothing about podcasts. An event stays the plain data
that a relay sends. Its id is the SHA-256 of a text that NIP-01 defines to
the character, and a JSON writer escapes more than NIP-01 allows, so
`event-id` writes that text itself.

Neither the JDK nor the Web Crypto API has the curve secp256k1 or the cipher
ChaCha20. So `crypto.secp256k1` (the Schnorr signatures of BIP-340) and
`crypto.chacha20` (RFC 8439) implement them in Clojure, and they pass the
test vectors of their specs on both platforms. The NIP-44 encryption of
wallet commands builds on them.

A relay speaks over a WebSocket, and `nostr/send!` is one exchange: open,
ask, read until the relay has no more, close. So a request to a relay is a
map like any other request, and `comments/fetch!` sends both kinds in the
same rounds.


## Value for value

A listener can pay a show while listening, and send a message with the
payment. The `podcast:value` block names the recipients and their splits,
and `value` divides an amount between them. podcast-clj never holds money:
the listener's wallet pays, and podcast-clj talks to it through Nostr Wallet
Connect or WebLN. The NIP-04 encryption of wallets without NIP-44 is left
out on purpose, since NIP-04 is deprecated and can't detect a changed
message.

A payment has one shape however it's sent. Amounts are in millisatoshis,
and most keys are the names of the RSS Payment Metadata, with the fields of
bLIP-10 mapped onto them, so a host reads a keysend payment and a payment
to a Lightning address the same way. Apps write the bLIP-10 record in
several ways, e.g. Fountain writes numbers as text, and `parse-boostagram`
accepts them and reports what it forgave.

Many wallets can't send keysend payments, so a recipient can also be a
Lightning address. The services behind Lightning addresses report errors in
many ways, often with status 200 or a web page, so the readers decide by the
JSON of an answer, not its status, as LUD-01 asks. [Payments](#payments)
lists what can go wrong on purpose.


## Analytics

A download isn't a listen, but advertisers pay by downloads, so hosts count
them. `analytics` counts them from a host's request logs, by version 2.3 of
the Podcast Measurement Technical Guidelines of the IAB Tech Lab, in pure
functions. Filters drop what can't be a download, such as HEAD requests,
bots and two-byte requests. `threshold` gives the bytes of one minute of a
file, and the requests of one listener for one file within 24 hours make
one download.

The guidelines leave some choices to the host. By default, the window is a
calendar day in UTC, as OP3 counts it, and `:rolling` or `:extending` gives
the 24 hours from a listener's first or last request instead. Lists of
bots, data centres and VPNs change often, so podcast-clj keeps no copy:
`user-agents` reads the list of the OPAWG, and the host passes the rest as
`:exclude?`.

OP3 is a free service that counts downloads through a URL prefix.
`op3/prefix` writes the prefix, and the client reads the downloads of OP3
with the same keys as `downloads`, so an app counts both with the same code.
The rules of OP3 differ a little from those of the IAB, so the counts do
too.


## The profile

Most of a podcast app's logic is the same in every app: a queue, a position
in each episode, played marks, and subscriptions with settings. The
`profile` namespace keeps all of it in one map, the listener's profile,
with pure functions over it, so an app only writes its UI.

Each function takes the instant of its change, and stamps what it changes
with that instant and the device id. If the instant isn't later than the
latest one in the state, it uses the next millisecond: a hybrid logical
clock in one rule. A removal is a tombstone. `merge` reconciles two states
entry by entry, and the later entry wins. The merge is commutative,
associative and idempotent, which a generative test checks, so any backend
that can store a document can sync the profile. This is also why the
profile must only change through the functions of `profile` and
`util.crdt`, never with `assoc`. The CRDT itself is in `util.crdt`, which
knows nothing of podcasts: `profile` gives it the schema of its
collections.

`step` takes the events of a player (`:progress`, `:ended`, `:play`,
`:skip` and `:chapter`) and returns the new state with effects for the app,
such as to seek, stop or play the next episode. It's the only place where
behaviour is composed, and it's kept short on purpose.

`refresh` puts the new episodes of a subscription into the queue or the
inbox, as its settings say. A followed OPML list adds the shows that join it
as subscriptions, but never unsubscribes, since a show that leaves a list
can still be one the listener likes.


## Search

The `search` namespace keeps criteria as data. `where` turns a map like
`{:season 2 :status :unplayed}` into a predicate, and `order` turns a
keyword like `:newest` into a sort key. Each criterion reads the model
through `feed.resolve`, so it means what the accessor means. Because the
criteria are data, a UI can build them from a form, and saved filters and
download rules use the same criteria.

Full-text search lives in namespaces that know nothing about podcasts,
`util.text`, `util.text.query` and `util.text.search`, so other projects
can use them too. The query language has a namespace of its own, since a
transcript or a filter matches a query against one text without an index.
The index is a value: `index`, `add` and `remove` build and change a map,
`query` searches it, and nothing runs in the background. Lucene doesn't
run in a browser, but this index runs wherever the model runs, and prints
as EDN. The `search` namespace only makes the documents of episodes and
shows, and gives the title more weight than the notes.

The index keeps its postings in sorted arrays of ints rather than in
maps, which takes far less memory and lets a query start from its rarest
word. As in Lucene, the arrays are in segments: `add` puts new documents
in a small segment of their own, `remove` marks a document in its
segment, and segments merge as they grow, so a change copies little and
the index stays a value. Until a merge drops it, a removed document still
counts in the statistics of the ranking, as it does in Lucene.

An index is kept between runs as documents in a store: a manifest in EDN,
and a document for each segment in the Common Index File Format, which
search engines such as Anserini, PISA and Terrier use to exchange their
indexes. CIFF has no positions, fields or stored values, so they're
fields of its protobuf messages that a reader of CIFF skips. A segment's
document is named after a hash of what it holds, so it's written once,
and the functions stay pure without a counter or random names.

The index only says which documents to put and delete, and
`library/save!` puts them into a store along with the rest of the
library. It's the same contract as for the sync documents, with bodies
of bytes as well as text, so a folder, IndexedDB and the origin private
file system can all hold a library, and an app needs no second kind of
store. Saving is a call that you make, e.g. after each refresh, rather
than something that runs in the background.

Words split at anything that isn't a letter or a digit, as in most
JavaScript search libraries, so "Simon's" is found by "simon", and a query
of "2.0" looks for 2 and 0 in a row. Chinese, Japanese, Korean, Thai and
the other scripts without spaces between words are indexed as overlapping
pairs of characters, as search engines do without a dictionary. The word
segmenters of the platforms were left out: the JVM and the browsers split
the same Chinese text differently, and an index must find the same words
on every device. There's no stemming and no language model. Instead the
last word of a query completes as a prefix, and a field given as a vector
of terms isn't analysed, so an app can bring its own stemmer.

A word that no document holds matches words with typos, much as Typesense
does by default. It's decided for each word, rather than by running a
query again when it finds nothing, so the words spelled right stay
exact, and a page past the end or a filter that leaves nothing doesn't
bring in matches with typos.

The ranking is BM25F, which adds up a word's occurrences in all the fields
of a document, each weighed by its field, before they count. Ranking each
field on its own and adding the scores would let a word that appears once
in each of three fields outrank one repeated in the title. The query
language is the one that Lucene, Elasticsearch and SQLite FTS5 share, with
its words and signs as options, e.g. for queries in another language.


## Download rules

No standard defines download rules. The plan in `downloads` is a pure
function: it takes the profile, the feeds, the files on the device and
the instant, and returns the episodes to download and the files to delete,
each with a reason. The app downloads and deletes, and decides when, since
it knows the network and the battery.

A rule selects episodes with the same options as `search/episodes`, so rules
need no language of their own. The rules sync in the profile, but the
list of files doesn't, since it belongs to one device. A rule from a later
version of podcast-clj does nothing, because a wrong delete costs more than
a late download.


## Platforms

| Concern | JVM | JavaScript |
|---|---|---|
| XML in | `javax.xml.stream`, not namespace-aware, no DTDs | a browser's `DOMParser`, or one installed in Node |
| Lenient XML in | the XML mode of jsoup, if present | none |
| JSON | `clojure.data.json` | `js/JSON` |
| SHA-1 (guid, Podcast Index) | `java.security.MessageDigest` | `goog.crypt.Sha1` |
| Instants | `java.util.Date` | `js/Date` |
| Deferred values (`util.async`) | futures | promises |
| HTTP | `java.net.http`, in `util.http` | `fetch` |
| Folders (`sync.file`) | `java.nio.file` | `node:fs` in Node, and the File System standard in a browser |
| Encryption (`sync.encrypted`) | `javax.crypto` | WebCrypto |

Dates, durations and UUIDv5 are written once in `.cljc`, without `java.time`
or js-joda, so they give the same result everywhere. The JavaScript XML
adapter uses only the standard DOM API, so any `DOMParser` works, and the
Node tests install `@xmldom/xmldom`. A `DOMParser` is namespace-aware and
refuses an undeclared prefix that the JVM accepts, which is why there's a
repair that declares the known prefixes.

On the JVM, `async/then` waits for its future at once, where a promise
would carry on. So requests that should run at the same time go through
`async/all` with a function, which starts each call on a future of its
own before it waits for any of them.


## The inspector

The inspector in `web/inspector/` is a small Pedestal service and the first
client of podcast-clj. It lives outside `src`, so the jar doesn't
contain Pedestal, and it uses only the public API. Give it a feed URL and
it shows the model, the report, the validation and the XML written back,
next to the resolved properties and their sources. It also searches the
directories, reads Podping and comment threads, pays a boost through a
wallet connection, and has a small ClojureScript player over `app.library`
that syncs with other devices over WebDAV and the gpodder API it serves.


## Testing

Most tests are `.cljc` files and run on the JVM, in Node and in a browser.
Hand-written fixtures test each quirk with its expected report. Corpora of
real feeds, companion files and media files, kept outside Git, are parsed,
round-tripped and validated, and every gap they found came with a test.
Generative tests cover the merge of the profile, the order of ranks,
devices that sync in random order, and the round trip of feeds generated
from the Malli schemas. CI also runs the tests without the inspector on
Java 11 with Clojure 1.11, the oldest versions podcast-clj supports.


## Not built yet

- The Open Podcast API, whose spec for episodes is still a draft.
- Stores over the HTTP APIs of cloud services. Each would be a short
  namespace against the store contract.
- A JSON form of the sync documents, for clients in other languages.
- A lenient XML parser for JavaScript.
- The extensions of Nostr Wallet Connect other than keysend: notifications,
  hold invoices, payment history and BOLT 12 offers.
