podcast-clj
===========

This is a library for making podcast software in Clojure or ClojureScript.

- It parses podcast feeds & associated files according to the relevant specs,
but supports files that break them too.
- It includes nearly every building block of a modern podcast app in its API,
which has been designed to be highly _decomplected_.
- It also has high-level `app.*` namespaces that compose low-level functions,
so you can start making a podcast app fast.

> **AI;DR** this introduction was written by a regular human, but I want to be
> transparent here: large swaths of this library were developed with assistance
> from frontier LLMs. I have spent a long time on the API design and on
> hardening the library in terms of both security and performance, but it simply
> wouldn't be feasible for me to develop a library with such broad support for
> modern podcasting features without LLMs helping to implementing a lot of it.

Current status
--------------

The library already supports a wide range of podcast features:

- **Feeds.** RSS 2.0, iTunes, and the `podcast` namespace of
  [Podcasting 2.0](https://podcasting2.org) all turn into the same normalised Clojure data.
- **Formats.** Support for chapters, transcripts, OPML, show notes as Hiccup
  or text, and the MP3/M4A files themselves: their chapters, duration and art.
- **Directories and updates.** Apple Podcasts, fyyd, gpodder.net and the
  Podcast Index are all supported. Podping and WebSub are supported too.
- **Apps.** Everything needed to create a podcast app, including sync between
  devices through a folder, WebDAV, gpodder, or a server of your own.
- **Comments.** Get episode comments via ActivityPub, the AT Protocol, or Nostr.
- **Analytics.** OP3 counts and download counts by the guidelines of the IAB.
- **Payment integration.** Lightning addresses, keysend, the wallets of WebLN
  and Nostr Wallet Connect, boostagrams, and the payment metadata of a show.

Feeds as data
-------------

Add it to the `:deps` in your `deps.edn` as a Git dependency,
with the SHA of the latest commit on `master`
(requires Clojure 1.11+ & Java 11+):

```clojure
io.github.simongray/podcast-clj {:git/sha "…"}
```

Then try parsing a feed as a Clojure map:

```clojure
(require '[dk.simongray.podcast :as podcast])

(def feed (podcast/parse (slurp "https://feeds.podcastindex.org/pc20.xml")))

(:rss/title feed)
;; => "Podcasting 2.0"

(-> feed :rss/items first :podcast/transcripts)
;; => [{:url "https://…" :type "application/x-subrip" :rel "captions"}]
```

Each key in the map has the namespace of the vocabulary defining the element.
The feed items are a vector under `:rss/items`. A report (attached as metadata)
says what podcast-clj had to repair or normalise as part of the parse process.
For example, this feed writes `itunes:explicit` as `no` instead of `false`,
gives its SRT transcripts the type `application/srt`, and uses the deprecated
`podcast:images`:

```clojure
(map :id (podcast/summarize (podcast/report feed)))
;; => (:quirk/explicit-value :quirk/transcript-type :quirk/deprecated-images)
```

Podcast feeds are written in several different vocabularies,
so `feed.resolve` can be relied on to get a normalised answer:

```clojure
(require '[dk.simongray.podcast.feed.resolve :as resolve])

(select-keys (resolve/properties feed) [:title :author :categories])
;; => {:title "Podcasting 2.0" :author "Podcast Index LLC" :categories [["News & Politics"] ["Technology"]]}
```

**NOTE:** with `org.jsoup/jsoup` on the classpath,
podcast-clj on the JVM also reads feeds that are _not_ well-formed XML.


An app in a few lines
---------------------

You can use the `app.*` namespaces to create a full-featured podcast app.
An app keeps a listener's subscriptions, feeds, queue, and play progress in
one map (called the podcast `library`), which fits inside an atom.
Subscribing, playing, refreshing, and syncing are each one function call.
A call that changes the library returns a new version along with what it did:

```clojure
(require '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.app.sync.file :as file])

(def now (java.util.Date.))
(def url "https://feeds.podcastindex.org/pc20.xml")
(def lib (atom (library/make "laptop" now {:name "Laptop"})))
(swap! lib library/subscribe url (podcast/fetch! url) now {:auto-queue? true})
(def id (let [[show item] (first (library/episodes @lib {:order :newest}))]
          (library/episode-id @lib show item)))

(merge (library/episode @lib id) (library/read-files! @lib id))  ; a page: the notes, the chapters, the transcript
(:effects (swap! lib library/step [:progress id 754 3600] now))  ; => [], or what the media element must do
(reset! lib (library/refresh! @lib now))                         ; the feeds that are due
(:added (:refresh @lib))                                         ; => [id …], the new episodes
(reset! lib (library/sync! @lib [(file/backend "/Users/simon/Dropbox/Podcasts")] now))  ; the other devices
(:player @lib)                                                   ; the player state, for every function of app.player
```

**NOTE:** the player state in `:player` is in fact a CRDT,
allowing different devices to sync this data without conflict.
Modify it with the functions of `app.player`, not with `assoc`.

Documentation
-------------

Learn more:

- [Quickstart](doc/quickstart.md): a podcast app at a REPL, step by step.
- [How to build a podcast app](doc/building-an-app.md): every step of an app, from finding a show
  to syncing devices.

Look up:

- [Namespaces](doc/namespaces.md): a line for each namespace.
- [The feed model](doc/tags.md): every element with its key and type.
- [Diagnostics](doc/diagnostics.md): every diagnostic that a report can hold.
- [Sync reference](doc/sync-reference.md): the store contract, the documents,
  and where the sync clients keep their folders.
- [Services and their defaults](doc/services.md): what podcast-clj knows about each service.

Understand:

- [Principles](doc/principles.md): what guides the design of podcast-clj.
- [Architecture](doc/architecture.md): how each part of podcast-clj is built.
- [Syncing](doc/sync.md): why the sync between devices is safe.

Background:

- [Podcasting and Clojure, September 2026](doc/podcasting-and-clojure-2026.md):
  the ecosystem that podcast-clj was built for, and how its features compared.


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
The corpus tests read real feeds from `dev-resources/corpus/` (not checked in
to Git). To download the corpus, run `(corpus/download!)` from `dev/corpus.clj`.

CI runs the JVM tests, the Node tests, and the linter on current versions of
Java and Clojure. It also runs the library tests on Java 11 with Clojure 1.11.

The inspector in `web/` is an example app that uses only the public API.
It shows the model, the report and the validation of any feed,
and it has a small player that syncs. To start it on http://localhost:8300/:

```bash
clojure -M:cljs compile player  # once: the browser player
clojure -M:web
```


Not built yet
-------------

- The Open Podcast API, whose spec for episodes is still a draft.
- Stores over the HTTP APIs of cloud services. Each would be a short
  namespace against the store contract.
- A JSON form of the sync documents, for clients in other languages.
- A lenient XML parser for JavaScript.
- A folder store for Node.
- The extensions of Nostr Wallet Connect other than keysend: notifications,
  hold invoices, payment history and BOLT 12 offers.

NIP-04 encryption for wallets without NIP-44 is left out on purpose, since
NIP-04 is deprecated and can't detect a changed message.


License
-------

The podcast-clj project is licensed under the [MIT licence](LICENSE).
