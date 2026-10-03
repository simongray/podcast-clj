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
> modern podcasting features without LLMs helping to implement a lot of it.

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
- **Analytics.** Download counts from OP3, and your own counts by the IAB guidelines.
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
(require '[dk.simongray.podcast.diagnostics :as diagnostics])

(map :id (diagnostics/summarize (podcast/report feed)))
;; => (:quirk/explicit-value :quirk/transcript-type :quirk/deprecated-images)
```

Podcast feeds mix several vocabularies,
and `feed.resolve` gives one normalised answer across them:

```clojure
(require '[dk.simongray.podcast.feed.resolve :as resolve])

(select-keys (resolve/properties feed) [:title :author :categories])
;; => {:title "Podcasting 2.0" :author "Podcast Index LLC" :categories [["News & Politics"] ["Technology"]]}
```

**NOTE:** with `org.jsoup/jsoup` on the classpath,
podcast-clj on the JVM also reads feeds that are _not_ well-formed XML.

**NOTE:** in ClojureScript, shadow-cljs reads the dependency from `deps.edn`
with `:deps true`. There are no npm packages to install, but Node needs a
`DOMParser`, e.g. that of `@xmldom/xmldom` as `globalThis.DOMParser`.


An app in a few lines
---------------------

You can use the `app.*` namespaces to create a full-featured podcast app.
An app keeps a listener's subscriptions, feeds, queue, and play progress in
one map (called the podcast `library`), which fits inside an atom.
Subscribing, playing, refreshing, and syncing are each one function call.
A call that changes the library returns a new version along with what it did:

```clojure
(require '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.sync.file :as file])

;; make a library for this device, and subscribe to a show
(def url "https://feeds.podcastindex.org/pc20.xml")
(def lib (atom (-> (library/make "laptop" {:name "Laptop"})
                   (library/subscribe url (podcast/fetch! url)))))

;; the id of the newest episode
(def id (let [[show item] (first (library/episodes @lib {:order :newest}))]
          (library/episode-id @lib show item)))

;; what an episode page shows, e.g. the show notes, chapters and transcript
(merge (library/episode @lib id) (library/read-files! @lib id))

;; tell the library that the player is 754 seconds into the 3600-second
;; episode, and get back what to do with the media element: nothing, here
(:effects (swap! lib library/step [:progress id 754 3600]))
;; => []

;; fetch the feeds that are due for a check, merge in what came without losing
;; what the listener did meanwhile, and list the new episodes
(-> (swap! lib library/merge (library/refresh! @lib)) :refresh :added)
;; => [] right after subscribing, since nothing is due yet.
;;    Later, the new episodes: [["917393e3-…" "PC20-274"]]

;; sync the profile with the listener's other devices through a Dropbox folder
(def dropbox (file/backend "/Users/simon/Dropbox/Podcasts"))
(-> (swap! lib library/merge (library/sync! @lib [dropbox])) :sync :report)
;; => []

;; the listener's profile: the subscriptions, queue, progress, stars and more
(:profile @lib)
```

**NOTE:** the profile in `:profile` is in fact a CRDT,
allowing different devices to sync this data without conflict.
Modify it with the functions of `profile`, not with `assoc`.

Documentation
-------------

Learn more:

- [Quickstart](doc/quickstart.md): a podcast app at a REPL, step by step.
- [How to build a podcast app](doc/building-an-app.md): every step of an app, from finding a show
  to syncing devices.

Look up:

- [Namespaces](doc/namespaces.md): a line for each namespace.
- [The feed model](doc/feed-model.md): every element with its key and type.
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
  the ecosystem that podcast-clj was built for, from Podcasting 2.0 to its
  directories and services, and how its features compared.


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
- The extensions of Nostr Wallet Connect other than keysend: notifications,
  hold invoices, payment history and BOLT 12 offers.

NIP-04 encryption for wallets without NIP-44 is left out on purpose, since
NIP-04 is deprecated and can't detect a changed message.


License
-------

The podcast-clj project is licensed under the [MIT licence](LICENSE).
