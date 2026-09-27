dk.simongray/podcast
====================

Podcast feeds as Clojure data: RSS 2.0, the iTunes namespace and the
`podcast` namespace of [Podcasting 2.0](https://podcasting2.org), plus the
companion formats (JSON chapters, transcripts, OPML, `podcast:guid`, value
blocks), the directories and the Podcast Index, Podping, and what a podcast
app keeps: a player's state, search, and syncing between devices. Standards
are implemented by the book; the variations found in the wild are absorbed
by a separate, named, reported *quirks* layer. The core is `.cljc` and runs
on the JVM, under Node and in a browser.

This is a library first; a small reference web app, a feed inspector, is
bundled to exercise it against real feeds. It is not on Clojars yet and can
be used as a git dependency on the repository. It needs Clojure 1.11 or
later and, on the JVM, Java 11 or later.

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.resolve :as resolve])

(def feed (podcast/parse (slurp "https://feeds.podcastindex.org/pc20.xml")))

(:rss/title feed)                         ; => "Podcasting 2.0"
(:podcast/guid feed)                      ; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
(-> feed :rss/items first :podcast/transcripts)
;; => [{:url "https://…/transcript.srt" :type "application/x-subrip"}]

(podcast/report feed)                     ; what had to be repaired, normalized or given up on
(println (podcast/emit feed))             ; the feed written back out, canonically

(let [item (first (:rss/items feed))]
  (resolve/properties feed item))         ; one answer per property, where the vocabularies overlap
;; => {:title "…" :artwork "https://…" :duration 3600 :people [...] :enclosure {…} …}
```

Documentation
-------------

- [doc/building-an-app.md](doc/building-an-app.md) is the path from finding
  a show to playing it, with the function to call at each step: the
  directories, fetching and polling, showing a show, playing, searching,
  keeping devices in step, value for value.
- [doc/architecture.md](doc/architecture.md) is the design: the layers, the
  conventions every namespace follows, the tag registry as data and the
  reasoning behind the choices that were not obvious.
- [doc/sync.md](doc/sync.md) is the design of syncing: the player state as
  a CRDT, the engine, the stores and what the seams admit.
- [doc/tags.md](doc/tags.md) lists every element with its key, parents,
  count, types and attributes, and [doc/diagnostics.md](doc/diagnostics.md)
  every diagnostic id; both are generated from the source.
- [doc/podcasting-2.0-clojure-survey.md](doc/podcasting-2.0-clojure-survey.md)
  is the ecosystem survey that motivated the library.

In one paragraph: XML becomes Hiccup with namespaces resolved by URI. A
**tag registry** describes every element the library understands as data,
and the parser, the emitter, the validator and the Malli schemas are
interpreters of it, so a spec change is a data change and a client's own
elements read and write like the library's. The **feed model** is plain
maps with namespaced kebab-case keys; scalars are coerced, repeatable
elements are vectors, unknown elements are kept as Hiccup so that parse
followed by emit loses nothing. Parsing is strict first and repairs only a
malformed document, and everything the lenient path does is a named entry
in the **report**.

Namespaces
----------

| Namespace | What it does |
|---|---|
| `dk.simongray.podcast` | The front door: `parse`, `emit`, `report`, `summarize`, `parse-tree`; on the JVM `fetch!`, a URL to a parsed feed with the headers a poller hands back |
| `…podcast.diagnostics` | Every diagnostic id with its level and meaning |
| `…podcast.xml` | XML to Hiccup and back, namespaces resolved by URI |
| `…podcast.xml.jsoup` | Lenient parser for feeds that are not well-formed at all; needs the optional `org.jsoup/jsoup` |
| `…podcast.time`, `…podcast.time.rrule` | Dates, durations, timestamps; RFC 5545 recurrence rules |
| `…podcast.guid` | `podcast:guid` (UUIDv5) and SHA-1 |
| `…podcast.rank` | Ranks that always leave room for another between two, for ordered lists kept as entries |
| `…podcast.async` | Deferred values written once for both platforms: futures on the JVM, promises in JavaScript |
| `…podcast.http` | Requests as maps; the JVM client, with conditional requests |
| `…podcast.spec` | The tag registry as data, `registry` to index or extend it, and the enumerations of the specifications |
| `…podcast.quirks` | String repairs, URI variants, tree normalizations |
| `…podcast.feed` | Hiccup to feed maps and back; `normalize` for a producer's shorthand; feed and item identity; `diff` between parses |
| `…podcast.feed.validate` | Registry-driven validation, with PSP-1 and Apple profiles |
| `…podcast.feed.schema` | Malli schemas of the model, generated from the registry; Malli itself is optional |
| `…podcast.resolve` | One answer where the vocabularies give several; `properties` for all of them at once; the listening order, `blocked?`, `next-check` |
| `…podcast.value` | Value-for-value arithmetic: splits, fees, time splits, remote blocks; the boostagram record |
| `…podcast.format.chapters` | JSON Chapters, Podlove Simple Chapters, and chapters read from the timestamps in show notes |
| `…podcast.format.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, speakers included |
| `…podcast.format.opml` | Subscription lists |
| `…podcast.format.html` | Embedded HTML as Hiccup: `parse`, `sanitize`, `hiccup`, `text`, `markdown`, `emit` |
| `…podcast.app.player` | A player's state as a CRDT: queue, progress, subscriptions and settings, devices; `merge`, `compact`, `step` |
| `…podcast.app.search` | Episodes filtered and ordered by criteria as data; a full-text index over subscriptions, ranked by BM25 |
| `…podcast.app.sync` | Keeping devices in step: documents, `reconcile`, `sync!` as one step over any backends, `next-at` |
| `…podcast.app.sync.store` | The store contract, `memory`, `backend` over any store by either strategy, and `check!` for a settings screen |
| `…podcast.app.sync.file` | A folder as a store, for a folder a sync client mirrors or a Git working tree; JVM |
| `…podcast.app.sync.webdav` | A WebDAV collection as a store, on both platforms |
| `…podcast.app.sync.webdav.server` | A Ring handler serving any store over WebDAV; JVM |
| `…podcast.app.sync.encrypted` | Any store with its documents encrypted at rest, javax.crypto and WebCrypto opening each other's envelopes |
| `…podcast.directory` | Apple Podcasts, fyyd, gpodder.net and the Podcast Index behind one shape; `search!` and `trending!` on the JVM |
| `…podcast.directory.podcastindex` | Podcast Index API: requests with auth, responses mapped onto the model, remote items resolved |
| `…podcast.podping` | Podping: the Hive calls as request maps, pings read out of blocks, `poll!` on the JVM; `notify-request` for a host |

Reference web app
-----------------

```bash
clojure -M:web
```

The inspector lives under `web/` in its own namespaces, `inspector.*`: example
code that uses the library's public API, not part of the library. Open
http://localhost:8300/, paste a feed URL, and see the model, the report, the
validation against a profile and the round-tripped XML; every item links to
its chapters and transcript parsed, the front page searches the directories,
and the queue page carries a browser player that syncs through the
inspector's own folder, served over WebDAV at `/dav/` (`INSPECTOR_SYNC_DIR`,
default `target/sync`). The corpus of real feeds in `dev-resources/corpus/`
(not in git; `(corpus/download!)` from `dev/corpus.clj` recreates it) is
listed on the front page.

Development
-----------

```bash
clojure -M:dev:nrepl                  # nREPL on port 7888
clojure -X:test                       # the test suite on the JVM
clojure -X:test-floor                 # the library's tests on Clojure 1.11, the floor it declares
npm install                           # once: shadow-cljs, the Node DOMParser and jsdom
clojure -M:cljs compile test          # the same suite under Node
clojure -M:cljs compile browser-test  # the same suite for a browser; serve target/browser-test
clojure -M:cljs compile player        # the inspector's browser player
clojure -T:build jar                  # the library jar and its pom, in target/
```

The tests are `.cljc` and run on both platforms; the corpus tests are
JVM-only and pass vacuously when `dev-resources/corpus/` is empty. CI runs
the JVM and Node suites and the linter on the current Java and Clojure, and
the library's own tests on Java 11 with Clojure 1.11.

License
-------

MIT; see [LICENSE](LICENSE).
