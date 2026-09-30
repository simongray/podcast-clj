# Quickstart: a podcast app at a REPL

This page takes you from a feed URL to a queue, a page of an episode, a
player and a sync, at a REPL on the JVM. Each step is one call of
podcast-clj, and each call gives data. [How to build a podcast
app](building-an-app.md) tells what each call does, and what to call when
a step is not what your app wants.

Add podcast-clj to your `deps.edn` as a Git dependency, with the SHA of
the latest commit on `master`:

```clojure
io.github.simongray/podcast-clj {:git/sha "…"}
```

## The library

An app keeps a listener's subscriptions, feeds, queue and progress in
one map, the library. Make one for this device, keep it in an atom, and
subscribe to a show:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.sync.file :as file])

(def now (java.util.Date.))
(def url "https://feeds.podcastindex.org/pc20.xml")

(def lib (atom (library/make "laptop" now {:name "Laptop"})))
(swap! lib library/subscribe url (podcast/fetch! url) now {:auto-queue? true})

(keys (library/subscriptions @lib))
;; => ("917393e3-1b1e-5cef-ace4-edaa54e1f810")
```

The id of this device is `"laptop"`. Make it once per installation, and
keep it. The call of `podcast/fetch!` fetched the feed and parsed it, and
`subscribe` kept the feed with the validators of the fetch, added the
subscription with its settings, and named the show by its guid.

The library is EDN. Save `@lib` as you save your other values, and read
it back at the next start.

The library is a map, and `swap!` takes the functions of `app.library`
and `app.player`. Do not `assoc` into `:player`. The player state is a
CRDT, so that the devices of a listener merge without conflict. Each
write stamps what it changed with the time and the device, and a
removal is a tombstone. [Syncing](sync.md) tells how.

## The episodes

Select episodes across the shows, in the order you want:

```clojure
(def episodes (library/episodes @lib {:order :newest :limit 3}))

(map (fn [[show item]] (:rss/title item)) episodes)
;; => ("Episode 272: \"Is this Y'all?\"" "Episode 271: Foot Terminal" "Episode 270: YOLO Guy")

(def id (let [[show item] (first episodes)]
          (library/episode-id @lib show item)))
;; => ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]
```

An episode is a `[show item]` pair, the feed and its item as
`podcast/parse` gave them. Its identity for the player is the guid of the
show and the guid of the item.

## A page of an episode

The view of an episode is one map for a page. The step `read-files!`
reads what the files of the episode say: the chapters, the transcript
and the media file. A page is the two merged:

```clojure
(def page (merge (library/episode @lib id) (library/read-files! @lib id)))

(select-keys page [:title :duration])
;; => {:title "Episode 272: \"Is this Y'all?\"" :duration 5501.208}
(:notes page)                                  ; the show notes, sanitized, as Hiccup
(:source (:chapters page))                     ; => :file, the chapters file of the episode
(count (:chapters (:document (:chapters page))))  ; => 40
(count (:segments (:document (:transcript page))))  ; => 1481
```

A file that does not come leaves `:error` in its part, and the step
throws nothing for what the network does. For a list, `library/episode`
alone gives the view, without the files.

## The player

The player state holds the queue and the progress. Your app holds the
media element, and sends each of its events to `step`, which gives the
library with the effects that the app must do under `:effects`:

```clojure
(swap! lib update :player player/enqueue id now)
(player/queue (:player @lib))
;; => [["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]]

(:effects (swap! lib library/step [:progress id 754 3600] now))   ; on each timeupdate
;; => []
(:effects (swap! lib library/step [:skip id :forward] now))
;; => [[:seek ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"] 784]]
```

The player state is `(:player @lib)`. Every function of `app.player`
takes it, and `enqueue` is one of them.

## New episodes

A call of `refresh!` fetches what is due: the feeds, the lists that the
listener follows, and the feeds of subscriptions that have none. Each
fresh feed goes to the player, which queues the new episodes of a show
with `:auto-queue?`. It fetches, so put what it gives back with `reset!`:

```clojure
(reset! lib (library/refresh! @lib now))
(:refresh @lib)
;; => {:at #inst "…" :added [] :moved {} :report []}
```

Nothing is due right after a subscribe. Call `refresh!` from a timer, and
give it the time. The last refresh is under `:refresh`: the new episodes,
the feeds that moved, and the fetches that failed. A feed that did not
come stays as it was, with its failure under `:report`, and the next
fetch of it waits longer.

## The other devices

Each device writes its player state to a store as one document, and
reads the documents of the others. A folder that a sync client mirrors is
a store, and so is a WebDAV collection.

```clojure
(reset! lib (library/sync! @lib [(file/backend "/Users/simon/Dropbox/Podcasts" {:app "quickstart"})] now))
(select-keys (:sync @lib) [:at :failed :report])
;; => {:at #inst "…" :report []}
(library/next-sync @lib now)
;; => #inst "…", when to call sync! again
```

The last step is under `:sync`, with its cursors, the backends that
failed under `:failed`, when any did, and its report. The folder holds
one document for each device. Another device that syncs through the
same folder learns the subscription and the queue. [Sync
stores and documents](stores.md) gives the folders of the sync clients
and the URLs of the WebDAV servers.

## What next

- [How to build a podcast app](building-an-app.md): every step of an app,
  the call of the library first and then the functions below it.
- [Namespaces](namespaces.md): the conventions of the API, and one line
  for each namespace.
- The library is one map. Print `@lib`, and read it.
