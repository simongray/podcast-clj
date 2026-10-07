# Quickstart: a podcast app at a REPL

This page goes from a feed URL to a queue, an episode page, a player and a
sync, at a REPL on the JVM. Each step is one call that returns data. [How
to build a podcast app](building-an-app.md) explains each call in more
depth, and what to use when you need more control. The feed is live, so
your outputs will show newer episodes than these.

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
         '[dk.simongray.podcast.profile :as profile]
         '[dk.simongray.podcast.sync :as sync]
         '[dk.simongray.podcast.sync.file :as file])

(def url "https://feeds.podcastindex.org/pc20.xml")

(def lib (atom (library/make "laptop" {:name "Laptop"})))

(-> (swap! lib library/subscribe url (podcast/fetch! url) {:auto-queue? true})
    library/subscriptions
    keys)
;; => ("917393e3-1b1e-5cef-ace4-edaa54e1f810")
```

Here the id of the device is `"laptop"`. In an app, make one id per
installation and keep it. The call to `podcast/fetch!` fetched and parsed
the feed. Then `subscribe` kept the feed along with its ETag and
Last-Modified, added the subscription, and named the show by its guid. The
subscription's setting `:auto-queue?` puts the show's new episodes in the
listener's queue. Each `swap!` returns the whole library, with the feed in it,
so the examples look only at the part that a step changed.

Change the library with `swap!` and the functions of `app.library` and
`profile`, but never `assoc` into `:profile`. The profile is a CRDT, so
that the listener's devices can merge their changes in any order: each
write stamps what it changed with the time and the device, the later of
two changes wins, and a removal is a tombstone. [Syncing](sync.md)
explains how. To keep the library until the next start, save it into a
store with `library/save!`, and read it back with `library/restore!`, as
[How to build a podcast app](building-an-app.md#keep-the-library-between-runs)
shows.

## The episodes

Pick episodes across all the shows, in the order you want:

```clojure
(def episodes (library/episodes @lib {:order :newest :limit 3}))

(map (comp :rss/title second) episodes)
;; => ("Episode 272: \"Is this Y'all?\"" "Episode 271: Foot Terminal" …)

(def id
  (let [[show item] (first episodes)]
    (library/episode-id @lib show item)))

id
;; => ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]
```

An episode is a `[show item]` pair: the feed and its item, as
`rss/parse` returns them. Its id in the profile is the guid of the show
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

The profile holds the queue and the progress, and you control the media
element. Send each of its events to `step`, which returns the library
with the effects to apply under `:effects`:

```clojure
(-> (swap! lib library/update-profile profile/enqueue id) :profile profile/queue)
;; => [["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]]

(:effects (swap! lib library/step [:progress id 754 (:duration page)]))  ; => []
(:effects (swap! lib library/step [:skip id :forward]))
;; => [[:seek ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"] 784]]
```

The profile is `(:profile @lib)`, which every function of `profile`
takes. To change it, `library/update-profile` calls one of them with the
time, like `enqueue` here.

## New episodes

To check for new episodes, call `refresh!`. It fetches whatever is due:
the feeds, the lists that the listener follows, and the feeds of new
subscriptions. The new episodes of a show with `:auto-queue?` go to the
queue. The listener can change the library while it fetches, so put the
result into the atom with `library/merge`, which keeps those changes:

```clojure
(:refresh (swap! lib library/merge (library/refresh! @lib)))
;; => {:at #inst "…" :added [] :moved {} :report []}
```

Nothing is due right after subscribing, so in an app, call `refresh!` from
a timer. The result under `:refresh` lists the new episodes, the feeds that
moved, and the fetches that failed. Once the show has a new episode,
`:added` holds its id, e.g. `[["917393e3-…" "PC20-274"]]`, and the episode
is in the queue. A feed that failed stays as it was, and
waits longer before the next try.

## The other devices

Each device writes its profile to a store as one document, and reads
the documents of the other devices. A folder that a sync client mirrors is
a store, and so is a WebDAV collection. With `sync/backend`, a store
becomes the backend of a sync, here with the app's name in its document:

```clojure
(def folder
  (sync/backend (file/store "/Users/simon/Dropbox/Podcasts")
                {:app "quickstart"}))

(-> (swap! lib library/merge (library/sync! @lib [folder]))
    :sync
    (select-keys [:at :report]))
;; => {:at #inst "…" :report []}
(library/next-sync @lib)  ; when to sync again
```

The result under `:sync` has the cursors for the next sync and its report,
which names any backend that failed. The folder now holds one
document for this device, and another device that syncs through the same
folder gets the subscription and the queue. [Sync
reference](sync-reference.md) lists where the common sync clients and
WebDAV servers keep their folders.

## What next

- [How to build a podcast app](building-an-app.md): every part of an app,
  with the lower-level functions for more control.
- [Principles](principles.md): what guides the design of podcast-clj.
- [Namespaces](namespaces.md): a line for each namespace.
- Look at `(keys @lib)` and `(:profile @lib)` to see what the library
  holds.
