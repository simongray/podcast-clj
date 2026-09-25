dk.simongray/podcast
====================

Podcast feeds as Clojure data: RSS 2.0, the iTunes namespace and the
`podcast` namespace of [Podcasting 2.0](https://podcasting2.org), plus the
companion formats (JSON chapters, transcripts, OPML, `podcast:guid`, value
blocks, the Podcast Index API). Standards are implemented by the book; the
variations found in the wild are absorbed by a separate, named, reported
*quirks* layer. The core is `.cljc`.

This is a library first. A small reference web app, a feed inspector, is
bundled to exercise it against real feeds.

```clojure
(require '[dk.simongray.podcast :as podcast])

(def feed (podcast/parse (slurp "https://feeds.podcastindex.org/pc20.xml")))

(:rss/title feed)                         ; => "Podcasting 2.0"
(:podcast/guid feed)                      ; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
(-> feed :rss/items first :podcast/transcripts)
;; => [{:url "https://…/transcript.srt" :type "application/x-subrip"}]

(podcast/report feed)                     ; what had to be repaired, normalized or given up on
(println (podcast/emit feed))             ; the feed written back out, canonically
```

Design
------

The design is described in [doc/architecture.md](doc/architecture.md); the
ecosystem survey that motivated it is in
[doc/podcasting-2.0-clojure-survey.md](doc/podcasting-2.0-clojure-survey.md).
In one paragraph:

XML becomes Hiccup with namespaces resolved by URI (`:itunes/author`,
`:podcast/transcript`). A **tag registry** describes every element the
library understands as data, and the parser, the emitter and the validator
are interpreters of it, so a spec change is a data change. The **feed model**
is plain maps with namespaced kebab-case keys; scalars are coerced (`#inst`,
seconds, booleans), repeatable elements are vectors, unknown elements are
kept as Hiccup so that parse followed by emit loses nothing. Everything the
lenient path does is a named entry in the **report**.

Namespaces
----------

| Namespace | What it does |
|---|---|
| `dk.simongray.podcast` | The front door: `parse`, `emit`, `report`, `parse-tree` |
| `…podcast.xml` | XML to Hiccup and back, namespaces resolved by URI |
| `…podcast.quirks` | String repairs, URI variants, tree normalizations |
| `…podcast.spec` | The tag registry and the enumerations of the specifications |
| `…podcast.feed` | Hiccup to feed maps and back; feed and item identity |
| `…podcast.validate` | Registry-driven validation, with PSP-1 and Apple profiles |
| `…podcast.chapters` | JSON Chapters and Podlove Simple Chapters |
| `…podcast.transcript` | SRT, WebVTT, JSON and HTML transcripts |
| `…podcast.opml` | Subscription lists |
| `…podcast.value` | Value-for-value arithmetic: splits, fees, time splits |
| `…podcast.index` | Podcast Index API request builder |
| `…podcast.guid` | `podcast:guid` (UUIDv5) and SHA-1 |
| `…podcast.time` | RFC 2822 and ISO 8601 dates, durations, timestamps |
| `…podcast.http` | JVM feed fetcher with conditional requests |

Reference web app
-----------------

```bash
clojure -M:web
```

Then open http://localhost:8300/, paste a feed URL, and see the model, the
report, the validation findings against a profile and the round-tripped XML.
The corpus of real feeds in `dev-resources/corpus/` (not in git; recreate it
with `(corpus/download!)` from `dev/corpus.clj`) is listed on the front page.

Development
-----------

```bash
clojure -M:dev:nrepl                  # nREPL on port 7888
clojure -X:test                       # the test suite on the JVM
npm install                           # once: shadow-cljs and the Node DOMParser
clojure -M:cljs compile test          # the same suite under Node
clojure -M:cljs compile browser-test  # the same suite for a browser; serve target/browser-test
```

The tests are `.cljc` and run on both platforms. Under Node,
`test/dk/simongray/podcast/test_setup.cljs` installs the DOMParser from
`@xmldom/xmldom` as `js/DOMParser`; the library itself has no npm
dependencies. The corpus test is JVM-only, runs over whatever is in
`dev-resources/corpus/`, and passes vacuously when the directory is empty.

Status
------

Early. The JVM path is exercised against a corpus of feeds from some thirty
hosting platforms, and the whole library compiles and passes its tests as
ClojureScript, both under Node and in a browser. See the roadmap at the end
of the architecture document.
