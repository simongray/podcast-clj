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

The model is plain data; where the vocabularies overlap, `resolve` has the
precedence rules written down once:

```clojure
(require '[dk.simongray.podcast.resolve :as resolve]
         '[dk.simongray.podcast.feed :as feed])

(let [item (first (:rss/items feed))]
  [(resolve/artwork feed item)  ; the largest podcast:image meant as artwork, else itunes:image…
   (resolve/duration item)      ; seconds, from iTunes or Media RSS
   (resolve/numbering item)     ; => {:type "full" :season 1 :episode 3}
   (resolve/people feed item)   ; the item's people, or the feed's, roles defaulted
   (resolve/enclosure item)     ; what to play, from enclosure, alternateEnclosure or media:content
   (resolve/transcript item)])  ; the transcript reference a client is best served by

(feed/diff yesterdays-parse todays-parse)
;; => {:added [...] :removed [...] :changed [...] :unchanged 203 :channel-changed? false}
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

Parsing is strict first. When a document is malformed, the quirks layer
repairs the text and tries again; when even that fails and `org.jsoup/jsoup`
is on the classpath, the document is parsed leniently with jsoup's XML mode
and the report says so. The library does not depend on jsoup; adding it to
your own dependencies is all it takes.

Conventions
-----------

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
   `parse`. Every finding is a map with `:id`, `:level` (`:info`, `:warning`
   or `:error`) and `:message`, plus `:path` into the data where that
   applies. A report is either something a host can fix or something an
   app can act on; nothing else goes in it.
3. **`resolve` gives one answer per property, by a stated rule.** Where the
   vocabularies overlap, each accessor's docstring says which source wins.
   One-argument forms take a feed or an item; two-argument forms take the
   feed and an item and apply the inheritance the specifications describe.

Keys are namespaced by vocabulary and kebab-cased: `:rss/pub-date`,
`:itunes/episode-type`, `:podcast/alternate-enclosures`. Attributes become
kebab-case keys in their element's map, the element's own text is
`:content`, repeatable elements are vectors under a plural key, and anything
the registry does not know is kept as Hiccup under `:xml/unknown`.

Namespaces
----------

| Namespace | What it does |
|---|---|
| `dk.simongray.podcast` | The front door: `parse`, `emit`, `report`, `parse-tree` |
| `…podcast.xml` | XML to Hiccup and back, namespaces resolved by URI |
| `…podcast.xml.jsoup` | Lenient parser for feeds that are not well-formed at all; needs the optional `org.jsoup/jsoup` |
| `…podcast.quirks` | String repairs, URI variants, tree normalizations |
| `…podcast.spec` | The tag registry and the enumerations of the specifications |
| `…podcast.feed` | Hiccup to feed maps and back; feed and item identity; `diff` between parses |
| `…podcast.resolve` | One answer where the vocabularies give several: title, artwork, author, duration, numbering, enclosure, people, transcript |
| `…podcast.validate` | Registry-driven validation, with PSP-1 and Apple profiles |
| `…podcast.chapters` | JSON Chapters and Podlove Simple Chapters |
| `…podcast.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, speakers included; `turns` merges word-level segments |
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
Every item links to a page that fetches its chapters and transcript and shows
them parsed. The corpus of real feeds in `dev-resources/corpus/` (not in git;
recreate it with `(corpus/download!)` from `dev/corpus.clj`) is listed on the
front page; `(corpus/download-companions!)` fetches a sample of the chapter
and transcript files those feeds link to, and `(corpus/sweep-companions)`
parses them all and tabulates the result.

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
