podcast-clj
===========

podcast-clj is a Clojure library for podcast feeds and podcast apps.

It reads RSS 2.0 feeds into plain Clojure maps, and writes the maps back as
XML. It supports the iTunes namespace and the `podcast` namespace of
[Podcasting 2.0](https://podcasting2.org). It also reads the files that
feeds link to: JSON chapters, transcripts, OPML subscription lists, and the
MP3 and M4A files themselves, for their chapters, duration and artwork. It
checks over the network whether these files reach a listener as the feed
says. For apps, it searches the podcast directories and the Podcast Index,
and reads Podping notifications. It reads the comments on an episode,
keeps the player state, and syncs this state between devices. For hosts,
it counts downloads by the rules of the IAB, and reads the counts of OP3.

podcast-clj reads a feed by the standards first. If a feed breaks a
standard, a separate layer of named quirks repairs it and reports each
repair. The same code runs on the JVM, in Node and in the browser.

podcast-clj is not on Clojars yet. It needs Clojure 1.11 or later, and on
the JVM, Java 11 or later.


Getting started
---------------

Add podcast-clj to the `:deps` in your `deps.edn` as a Git dependency. Use
the SHA of the latest commit on `master`:

```clojure
io.github.simongray/podcast-clj {:git/sha "…"}
```

Start a REPL and parse a feed:

```clojure
(require '[dk.simongray.podcast :as podcast])

(def feed (podcast/parse (slurp "https://feeds.podcastindex.org/pc20.xml")))

(:rss/title feed)
;; => "Podcasting 2.0"

(:podcast/guid feed)
;; => "917393e3-1b1e-5cef-ace4-edaa54e1f810"
```

The feed is a map. Each key has the namespace of the vocabulary that defines
the element. The items are in a vector under `:rss/items`:

```clojure
(-> feed :rss/items first :podcast/transcripts)
;; => [{:url "https://…" :type "application/x-subrip" :rel "captions"}]
```

The report tells what podcast-clj had to repair or normalize. This feed
writes `itunes:explicit` as `no` instead of `false`, gives its SRT
transcripts the type `application/srt`, and uses the deprecated
`podcast:images`:

```clojure
(map :id (podcast/summarize (podcast/report feed)))
;; => (:quirk/explicit-value :quirk/transcript-type :quirk/deprecated-images)
```

Where several vocabularies give the same property, `feed.resolve` gives one
answer:

```clojure
(require '[dk.simongray.podcast.feed.resolve :as resolve])

(select-keys (resolve/properties feed) [:title :author :categories])
;; => {:title "Podcasting 2.0"
;;     :author "Podcast Index LLC"
;;     :categories [["News & Politics"] ["Technology"]]}
```

`emit` writes the feed back as XML:

```clojure
(podcast/emit feed)
;; => "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" …"
```

With `org.jsoup/jsoup` on the classpath, podcast-clj on the JVM can also
read feeds that are not well-formed XML.


Documentation
-------------

- [How to build a podcast app](doc/building-an-app.md): the functions that
  an app calls, from finding a show to syncing devices.
- [Namespaces](doc/namespaces.md): the conventions of the API, and what each
  namespace contains.
- [The feed model](doc/tags.md): the rules of the model, and every element
  with its key and type.
- [Diagnostics](doc/diagnostics.md): every diagnostic that a report can
  contain.
- [Sync stores and documents](doc/stores.md): the store contract, the sync
  document format, and where some providers keep their stores.
- [Services and their defaults](doc/services.md): what podcast-clj knows
  about each service, and the option that replaces it.
- [Architecture](doc/architecture.md): the design of podcast-clj, and the
  reasons for it.
- [Syncing](doc/sync.md): the design of the sync between devices.
- [Podcasting 2.0 and Clojure](doc/podcasting-2.0-clojure-survey.md): the
  survey of the ecosystem that came before podcast-clj, from September 2026.
- [Podcast features outside podcast-clj](doc/feature-gaps.md): the
  research of September 2026 into what apps, hosts and other libraries
  have. It tells the status of each feature in podcast-clj.


Development
-----------

```bash
clojure -M:dev:nrepl                  # nREPL on port 7888
clojure -X:test                       # all tests on the JVM
clojure -X:test-floor                 # the library tests on Clojure 1.11
npm install                           # once, for the ClojureScript tests
clojure -M:cljs compile test          # all tests in Node
clojure -M:cljs compile browser-test  # all tests in a browser: serve target/browser-test
clojure -T:build jar                  # the jar and its pom, in target/
```

Most tests are `.cljc` files, and they run on the JVM and in ClojureScript.
The corpus tests read real feeds from `dev-resources/corpus/`, which is not
in Git. To download the corpus, run `(corpus/download!)` from
`dev/corpus.clj`. Without the corpus, the corpus tests have nothing to read,
and they pass.

CI runs the JVM tests, the Node tests and the linter on current versions of
Java and Clojure. It also runs the library tests on Java 11 with Clojure
1.11.

The inspector in `web/` is a reference web app that uses only the public
API. It shows the model, the report and the validation of any feed, and it
has a small player that syncs. To start it on http://localhost:8300/:

```bash
clojure -M:cljs compile player  # once: the browser player
clojure -M:web
```


License
-------

MIT. See [LICENSE](LICENSE).
