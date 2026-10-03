# Quickstart: a podcast app at a REPL

This page goes from a feed URL to a queue, an episode page, a player and a
sync, at a REPL on the JVM. Each step is one call that returns data. [How
to build a podcast app](building-an-app.md) explains each call in more
depth, and what to use when you need more control.

Add podcast-clj to your `deps.edn` as a Git dependency, with the SHA of the
latest commit on `master`:

```clojure
io.github.simongray/podcast-clj {:git/sha "…"}
```

## The library

The subscriptions, feeds, queue and play progress of an app are all kept
in one map, the library. Make one for this device, keep it in an atom, and
subscribe to a show:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.sync.file :as file])

(def url "https://feeds.podcastindex.org/pc20.xml")

(def lib (atom (library/make "laptop" {:name "Laptop"})))
(swap! lib library/subscribe url (podcast/fetch! url) {:auto-queue? true})

(keys (library/subscriptions @lib))
;; => ("917393e3-1b1e-5cef-ace4-edaa54e1f810")
```

Here the id of the device is `"laptop"`. In an app, make one id per
installation and keep it. The call to `podcast/fetch!` fetched and parsed
the feed. Then `subscribe` kept the feed along with its ETag and
Last-Modified, added the subscription with its settings, and named the show
by its guid.

Each change is stamped with its time, which the functions of
`app.library` read from the clock.

The library is plain EDN, so you can save `@lib` like any other value and
read it back at the next start. Change it with `swap!` and the functions of
`app.library` and `app.player`, but never `assoc` into `:player`. The player
state is a CRDT, so that the listener's devices can merge their changes
without conflicts: each write stamps what it changed with the time and the
device, and a removal is a tombstone. [Syncing](sync.md) explains how.

## The episodes

Pick episodes across all the shows, in the order you want:

```clojure
(def episodes (library/episodes @lib {:order :newest :limit 3}))

(map (comp :rss/title second) episodes)
;; => ("Episode 272: \"Is this Y'all?\"" "Episode 271: Foot Terminal" …)

(def id
  (let [[show item] (first episodes)]
    (library/episode-id @lib show item)))
;; => ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]
```

An episode is a `[show item]` pair: the feed and its item, as
`podcast/parse` returns them. Its id for the player is the guid of the show
and the guid of the item.

## An episode page

For an episode page, merge `library/episode` with `library/read-files!`,
which reads the episode's chapters file, transcript and media file:

```clojure
(def page (merge (library/episode @lib id) (library/read-files! @lib id)))

(select-keys page [:title :duration])
;; => {:title "Episode 272: \"Is this Y'all?\"" :duration 5501.208}
(:notes page)                                       ; the show notes as Hiccup
(:source (:chapters page))                          ; => :file
(count (:chapters (:document (:chapters page))))    ; => 40
(count (:segments (:document (:transcript page))))  ; => 1481
```

If a file can't be read, its part has an `:error`, and nothing is thrown.
For a list of episodes, use `library/episode` alone, which fetches nothing.

## The player

The player state holds the queue and the progress, and you control the
media element. Send each of its events to `step`, which returns the library
with the effects to apply under `:effects`:

```clojure
(swap! lib library/update-player player/enqueue id)
(player/queue (:player @lib))
;; => [["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]]

(:effects (swap! lib library/step [:progress id 754 3600]))  ; => []
(:effects (swap! lib library/step [:skip id :forward]))
;; => [[:seek ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"] 784]]
```

The player state is `(:player @lib)`, which every function of `app.player`
takes. To change it, `library/update-player` calls one of them with the
time, like `enqueue` here.

## New episodes

To check for new episodes, call `refresh!`. It fetches whatever is due:
the feeds, the lists that the listener follows, and the feeds of new
subscriptions. The new episodes of a show with `:auto-queue?` go to the
queue. Since it fetches, put the result back with `reset!`:

```clojure
(reset! lib (library/refresh! @lib))
(:refresh @lib)
;; => {:at #inst "…" :added [] :moved {} :report []}
```

Nothing is due right after subscribing, so in an app, call `refresh!` from
a timer. The result under `:refresh` lists the new episodes, the feeds that
moved, and the fetches that failed. A feed that failed stays as it was, and
waits longer before the next try.

## The other devices

Each device writes its player state to a store as one document, and reads
the documents of the other devices. A folder that a sync client mirrors is
a store, and so is a WebDAV collection:

```clojure
(def folder
  (file/backend "/Users/simon/Dropbox/Podcasts" {:app "quickstart"}))

(reset! lib (library/sync! @lib [folder]))
(select-keys (:sync @lib) [:at :failed :report])
;; => {:at #inst "…" :report []}
(library/next-sync @lib)  ; when to sync again
```

The result under `:sync` has the cursors for the next sync, its report,
and any backends that failed under `:failed`. The folder now holds one
document for this device, and another device that syncs through the same
folder gets the subscription and the queue. [Sync
reference](sync-reference.md) lists where the common sync clients and
WebDAV servers keep their folders.

## What next

- [How to build a podcast app](building-an-app.md): every part of an app,
  with the lower-level functions for more control.
- [Principles](principles.md): what guides the design of podcast-clj.
- [Namespaces](namespaces.md): a line for each namespace.
- Print `@lib` to see what the library holds.
