podcast-clj
===========

A Clojure(Script) library for writing podcast software.

It reads feeds and the files they link to as plain data, by the
standards, and repairs the feeds that break them. It has the building
blocks of a podcast app: the shows and episodes of a listener, kept
current, a player that syncs between devices, and the page of an episode
with its chapters, comments and payments. The same code runs on the JVM, in Node
and in the browser.

What it covers:

- **Feeds.** RSS 2.0, iTunes and the `podcast` namespace of
  [Podcasting 2.0](https://podcasting2.org), read into maps and written
  back. One answer for each property where the vocabularies overlap.
  Validation, and checks of the files that a feed links to.
- **Formats.** Chapters, transcripts, OPML, the show notes as Hiccup or
  text, and the MP3 and M4A files themselves: their chapters, duration and
  artwork.
- **Directories and updates.** Apple Podcasts, fyyd, gpodder.net and the
  Podcast Index. Links to a show in the apps. Podping and WebSub.
- **Comments.** The threads on an episode over ActivityPub, the AT
  Protocol and Nostr.
- **Analytics.** Downloads counted by the guidelines of the IAB, and the
  counts of OP3.
- **Apps.** A listener's subscriptions, feeds, queue and progress in one
  map, kept current. Search, download rules, and the sync between devices
  through a folder, WebDAV, gpodder, or a server of your own.
- **Value for value.** Lightning addresses, keysend, the wallets of WebLN
  and Nostr Wallet Connect, boostagrams, and the payment metadata of a
  show.

The library is decomplected. Everything is data, each namespace stands
on its own, and only the functions whose names end in `!` touch the
network. Each of them is named by its verb, and gives what the verb
produced, for the app to merge in. The high-level functions compose the
low-level ones, so you can drop down at any point.


Feeds as data
-------------

The library is not on Clojars yet. Add it to the `:deps` in your
`deps.edn` as a Git dependency, with the SHA of the latest commit on
`master`. It needs Clojure 1.11 or later, and on the JVM, Java 11 or
later.

```clojure
io.github.simongray/podcast-clj {:git/sha "…"}
```

Parse a feed:

```clojure
(require '[dk.simongray.podcast :as podcast])

(def feed (podcast/parse (slurp "https://feeds.podcastindex.org/pc20.xml")))

(:rss/title feed)
;; => "Podcasting 2.0"

(-> feed :rss/items first :podcast/transcripts)
;; => [{:url "https://…" :type "application/x-subrip" :rel "captions"}]
```

The feed is a map. Each key has the namespace of the vocabulary that
defines the element, and the items are a vector under `:rss/items`. The
report tells what podcast-clj had to repair or normalize. This feed
writes `itunes:explicit` as `no` instead of `false`, gives its SRT
transcripts the type `application/srt`, and uses the deprecated
`podcast:images`:

```clojure
(map :id (podcast/summarize (podcast/report feed)))
;; => (:quirk/explicit-value :quirk/transcript-type :quirk/deprecated-images)
```

Where several vocabularies give the same property, `feed.resolve` gives
one answer, and `emit` writes the feed back as XML:

```clojure
(require '[dk.simongray.podcast.feed.resolve :as resolve])

(select-keys (resolve/properties feed) [:title :author :categories])
;; => {:title "Podcasting 2.0" :author "Podcast Index LLC" :categories [["News & Politics"] ["Technology"]]}

(podcast/emit feed)
;; => "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" …"
```

With `org.jsoup/jsoup` on the classpath, podcast-clj on the JVM also reads
feeds that are not well-formed XML.


An app in fourteen lines
------------------------

An app keeps a listener's subscriptions, feeds, queue and progress in
one map, the library, in an atom. Each step of the app is one call, and
a step that changes the library gives it back with what it did in it:

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

The player state under `:player` is a CRDT, so that the devices of a
listener merge without conflict. Each write stamps what it changed with
the time and the device, and a removal is a tombstone. Change it with
the functions of `app.player`, not with `assoc`.

[Quickstart](doc/quickstart.md) runs these lines one by one, and [How to
build a podcast app](doc/building-an-app.md) tells what each call does,
and what to call when a step is not what your app wants.


Documentation
-------------

To learn, and to do:

- [Quickstart](doc/quickstart.md): a podcast app in thirty lines, at a
  REPL.
- [How to build a podcast app](doc/building-an-app.md): every step of an
  app, from finding a show to syncing devices.

To look up:

- [Namespaces](doc/namespaces.md): the conventions of the API, and one
  line for each namespace.
- [The feed model](doc/tags.md): every element with its key and type.
- [Diagnostics](doc/diagnostics.md): every diagnostic that a report can
  hold.
- [Sync stores and documents](doc/stores.md): the store contract, the
  documents, and where the sync clients keep their folders.
- [Services and their defaults](doc/services.md): what podcast-clj knows
  about each service, and the option that replaces it.

To understand:

- [Architecture](doc/architecture.md): the design of podcast-clj, and the
  reasons for it.
- [Syncing](doc/sync.md): why the sync between devices is safe.
- [Podcasting 2.0 and Clojure](doc/podcasting-2.0-clojure-survey.md): the
  survey of the ecosystem that came before podcast-clj, September 2026.
- [Podcast features outside podcast-clj](doc/feature-gaps.md): what apps,
  hosts and other libraries have, and the status of each feature here.


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

The inspector in `web/` is an example app that uses only the public
API. It shows the model, the report and the validation of any feed, and it
has a small player that syncs. To start it on http://localhost:8300/:

```bash
clojure -M:cljs compile player  # once: the browser player
clojure -M:web
```


License
-------

MIT. See [LICENSE](LICENSE).
