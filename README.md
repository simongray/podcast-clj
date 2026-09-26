dk.simongray/podcast
====================

Podcast feeds as Clojure data: RSS 2.0, the iTunes namespace and the
`podcast` namespace of [Podcasting 2.0](https://podcasting2.org), plus the
companion formats (JSON chapters, transcripts, OPML, `podcast:guid`, value
blocks, the Podcast Index API). Standards are implemented by the book; the
variations found in the wild are absorbed by a separate, named, reported
*quirks* layer. The core is `.cljc`.

This is a library first. A small reference web app, a feed inspector, is
bundled to exercise it against real feeds. It is not on Clojars yet: the
first release will be 1.0, once the library is complete. Until then it can
be used as a git dependency on the repository.

It needs Clojure 1.11 or later, for `parse-long` and `abs`, and on the JVM
Java 11 or later, for the `java.net.http` client behind `http` and `fetch!`.
Development pins the current Clojure 1.12 in `deps.edn`; the floor is what
the library itself requires.

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

On the JVM one call fetches and parses, with the validators for the next
poll, and the accessors say what to show and when to look again:

```clojure
(require '[dk.simongray.podcast.chapters :as chapters])

(def r (podcast/fetch! url {:etag etag :last-modified last-modified}))
(:feed r)                                        ; absent on a 304: nothing changed
(:new-feed-url r)                                ; the feed says it has moved
(resolve/next-check (:feed r) (java.util.Date.)) ; when to look again: rule, ttl, period or cadence
(resolve/episodes (:feed r))                     ; listening order; a serial show by season and number
(resolve/seasons (:feed r))                      ; => ({:season 2 :name "…" :items [...]} ...)
(resolve/blocked? (:feed r) :spotify)            ; what the block tags say for one platform
(chapters/from-text (resolve/description item))  ; chapters from "12:34 Topic" lines in the notes
```

Show notes arrive as HTML in a string. `html` reads them into Hiccup the way
a browser would, with a sanitizer, so that a Clojure client renders them
directly, and renders the same Hiccup as text or Markdown:

```clojure
(require '[dk.simongray.podcast.html :as html])

(html/hiccup (resolve/description item))
;; => ([:p {} "Today: " [:a {:href "https://…"} "the show notes"]] [:ul {} [:li {} "one"] ...])
[:div.notes (html/hiccup (resolve/description item))]  ; a sequence is a fragment: render it as is
(html/text (resolve/description item))     ; "Today: the show notes\n\n- one\n..."
(html/markdown (resolve/description item)) ; "Today: [the show notes](https://…)\n\n- one\n..."
```

What a player keeps between plays is plain data too, with pure operations
over it, so any UI gets a queue, progress, played marks and subscriptions
without owning the logic, and two devices' states merge into one:

```clojure
(require '[dk.simongray.podcast.player :as player])

(def now #inst "2026-09-26T10:00:00Z")
(def id (player/episode-id feed "https://feeds.podcastindex.org/pc20.xml" item))

(-> (player/state "phone")
    (player/subscribe (feed/feed-guid feed url) {:url url :title (resolve/title feed)} now)
    (player/enqueue id now)
    (player/progress id 1234 3600 now))     ; nothing here reads a clock or stores anything

(player/resume-position s feed-guid id)     ; where to start, honouring the feed's skip-intro
(player/step s [:skip id :forward] now)     ; => {:state ... :effects [[:seek id 1264]]}, by the listener's preferences
(player/step s [:ended id] now)             ; => {:state ... :effects [[:play next-id]]}
(player/merge phone-state laptop-state)     ; the later entry wins everywhere; tombstones beat old additions
(opml/emit (player/to-opml s))              ; the subscriptions as OPML, guids included
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
   `parse`. Every diagnostic is a map with `:id`, `:level` (`:info`,
   `:warning` or `:error`) and `:message`, plus `:path` into the data where
   that applies; [doc/diagnostics.md](doc/diagnostics.md) lists every id. A
   diagnostic is either something a host can fix or something an app can act
   on; nothing else goes in a report. `summarize` groups a report or a
   validation by kind with counts, so that a recommendation repeated for
   every item is one row.
3. **`resolve` gives one answer per property, by a stated rule.** Where the
   vocabularies overlap, each accessor's docstring says which source wins.
   One-argument forms take a feed or an item; two-argument forms take the
   feed and an item and apply the inheritance the specifications describe.
4. **Optional behaviour is an options map.** What an operation needs is a
   positional argument; what it may be told is a map, never a positional
   flag: `(parse xml {:quirks? false})`, `(validate/validate feed {:profile
   :psp-1})`, `(chapters/emit-json doc {:pretty? true})`,
   `(resolve/transcript feed item {:languages ["da"]})`.

Keys are namespaced by vocabulary and kebab-cased: `:rss/pub-date`,
`:itunes/episode-type`, `:podcast/alternate-enclosures`. Attributes become
kebab-case keys in their element's map, the element's own text is
`:content`, repeatable elements are vectors under a plural key, and anything
the registry does not know is kept as Hiccup under `:xml/unknown`.
[doc/tags.md](doc/tags.md) lists every element with its key, parents, count,
types and attributes; it is generated from the registry.

The same registry yields Malli schemas of the model as plain data, without
a dependency on Malli:

```clojure
(require '[dk.simongray.podcast.schema :as schema]
         '[malli.core :as m])

(m/validate schema/feed feed)          ; true for every feed in the corpus
(m/explain schema/item item)           ; where a hand-built item goes wrong before emit
(m/schema (schema/tag :podcast/value)) ; the schema of one element
```

The schemas describe what `emit` accepts: the model with every value coerced
and every required attribute present. `parse` is lenient and may return a map
that fails them, a value kept as a string or a required attribute left blank,
and `validate` is what says why; validating a map you built yourself before
`emit` catches mistakes early.

The registry is a value, and every interpreter takes one under `:registry`
in its options, so a vocabulary extended with a client's own elements is
read, written, validated and given schemas exactly like the library's:

```clojure
(require '[dk.simongray.podcast.spec :as spec])

(def tags
  (conj spec/tags {:tag :acme/rating :key :acme/rating :parent #{:channel} :count :one
                   :attrs {:scheme {}} :content :decimal :doc "Acme's rating of the show."}))

(:acme/rating (podcast/parse xml {:registry tags}))  ; => {:scheme "stars" :content 4.5}
(podcast/emit feed {:registry tags})                 ; written back, the namespace declared
(validate/validate feed {:registry tags :profile :psp-1})
(schema/feed-schema tags)                            ; the Malli schema of the extended model
```

The Podcast Index speaks the same model. A request is a map any HTTP client
can send, and a response comes back keyed like a parsed feed or item, with
what only the index knows under `:index/*`:

```clojure
(require '[dk.simongray.podcast.index :as index])

(index/request credentials :search/by-term {:q "podcasting 2.0" :max 5})
;; => {:method :get :url "https://api.podcastindex.org/api/1.0/search/byterm?q=…" :headers {…}}

(-> (index/call! credentials :podcasts/by-guid {:guid "917393e3-…"}) :feed resolve/title)  ; JVM
;; => "Podcasting 2.0"

;; a value time split that points at another feed's item: fetch that block…
(def remote (index/remote-value! credentials {:feed-guid "a94f…" :item-guid "…#1"}))
;; …and fold it into who is paid at that moment
(value/with-remote block (value/recipients-at block 100) remote)
```

Namespaces
----------

| Namespace | What it does |
|---|---|
| `dk.simongray.podcast` | The front door: `parse`, `emit`, `report`, `summarize`, `parse-tree`; on the JVM `fetch!`, a URL to a parsed feed with the headers a poller hands back |
| `dk.simongray.podcast.diagnostics` | Every diagnostic id with its level and meaning; rendered as [doc/diagnostics.md](doc/diagnostics.md) |
| `dk.simongray.podcast.html` | Embedded HTML as Hiccup: `parse`, `sanitize`, `hiccup`, `text`, `markdown`, `emit` |
| `dk.simongray.podcast.player` | A player's state as data: the queue, progress and played marks, subscriptions and settings, OPML in and out, `merge` for syncing, `step` for driving it |
| `…podcast.xml` | XML to Hiccup and back, namespaces resolved by URI; kebab-case names, character and entity references |
| `…podcast.xml.jsoup` | Lenient parser for feeds that are not well-formed at all; needs the optional `org.jsoup/jsoup` |
| `…podcast.quirks` | String repairs, URI variants, tree normalizations |
| `…podcast.spec` | The tag registry as data, `registry` to index or extend it, and the enumerations of the specifications; rendered as [doc/tags.md](doc/tags.md) |
| `…podcast.schema` | Malli schemas of the model, generated from the registry as plain data; Malli itself is optional |
| `…podcast.feed` | Hiccup to feed maps and back; feed and item identity; `diff` between parses |
| `…podcast.resolve` | One answer where the vocabularies give several: title, artwork, author, duration, numbering, enclosure, people, transcript; the listening order (`episodes`, `seasons`), `blocked?`, `next-check` |
| `…podcast.validate` | Registry-driven validation, with PSP-1 and Apple profiles |
| `…podcast.chapters` | JSON Chapters, Podlove Simple Chapters, and chapters read from the timestamps in show notes |
| `…podcast.transcript` | SRT, WebVTT, JSON, HTML and plain-text transcripts, speakers included; `turns` merges word-level segments |
| `…podcast.opml` | Subscription lists |
| `…podcast.value` | Value-for-value arithmetic: splits, fees, time splits, remote blocks folded in; the boostagram record a payment carries |
| `…podcast.index` | Podcast Index API: requests with auth, responses mapped onto the model, remote value resolution |
| `…podcast.guid` | `podcast:guid` (UUIDv5) and SHA-1 |
| `…podcast.time` | RFC 2822 and ISO 8601 dates, durations, timestamps |
| `…podcast.rrule` | RFC 5545 recurrence rules parsed, written and evaluated, for `podcast:updateFrequency` |
| `…podcast.http` | JVM feed fetcher with conditional requests |

Reference web app
-----------------

```bash
clojure -M:web
```

Then open http://localhost:8300/, paste a feed URL, and see the model, the
report, the validation diagnostics against a profile and the round-tripped XML.
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
clojure -M:cljs compile player        # the inspector's browser player, served at /queue
clojure -T:build jar                  # the library jar and its pom, in target/
```

The tests are `.cljc` and run on both platforms. Under Node,
`test/dk/simongray/podcast/test_setup.cljs` installs the DOMParser from
`@xmldom/xmldom` as `js/DOMParser`; the library itself has no npm
dependencies. The corpus test is JVM-only, runs over whatever is in
`dev-resources/corpus/`, and passes vacuously when the directory is empty.

The corpus tooling is `dev/corpus.clj`, for the REPL: `(corpus/download!)`
and `(corpus/sweep)` for the named feeds, `(corpus/download-companions!)`
and `(corpus/sweep-companions)` for the chapter and transcript files they
link to, and `(corpus/download-sweep! (corpus/sweep-populations credentials))`
followed by `(corpus/sweep-report)` for a few hundred feeds sampled through
the Podcast Index, which is how registry gaps and quirks are found.

Status
------

Working toward a complete 1.0, which will be the first release. The library
is exercised against a corpus of feeds from some thirty hosting platforms and
a sweep of several hundred more sampled through the Podcast Index, and the
whole of it compiles and passes its tests as ClojureScript, both under Node
and in a browser. See the roadmap at the end of the architecture document.

License
-------

MIT; see [LICENSE](LICENSE).
