# Quickstart: a podcast app at a REPL

This page goes from a feed URL to a queue, an episode page, a player and a
sync, at a REPL on the JVM. Each step is one call that returns data. [How
to build a podcast app](building-an-app.md) explains each call in more
depth, and what to use when you need more control. The feed is live, so
your outputs will show newer episodes than these.

Add the latest release of podcast-clj from
[Clojars](https://clojars.org/dk.simongray/podcast-clj) to your `deps.edn`:

```clojure
dk.simongray/podcast-clj {:mvn/version "0.4.0"}
```

## The library

The subscriptions, feeds, queue and play progress of an app are all kept
in one map, the library. Make one for this device, keep it in an atom, and
subscribe to a show:

```clojure
(require '[dk.simongray.like-minded.store.folder :as folder]
         '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.profile.queue :as profile.queue])

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
;; => ("Episode 274: \"Vibe Cloning\"" "Episode 273: \"Advertising is…\"" …)

(def id
  (let [[show item] (first episodes)]
    (library/episode-id @lib show item)))

id
;; => ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-274"]
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
;; => {:title "Episode 274: \"Vibe Cloning\"" :duration 6064.692244897959}
(:notes page)                                       ; the show notes as Hiccup
(:source (:chapters page))                          ; => :file
(count (:chapters (:document (:chapters page))))    ; => 21
(count (:segments (:document (:transcript page))))  ; => 1483
```

If a file can't be read, its part has an `:error`, and nothing is thrown.
For a list of episodes, use `library/episode` alone, which fetches nothing.

## The player

The profile holds the queue and the progress, and you control the media
element. Send each of its events to `step`, which returns the new
`:library` and the `:effects` to apply:

```clojure
(-> (swap! lib library/update-profile profile.queue/enqueue id)
    :profile
    profile.queue/queue)
;; => [["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-274"]]

(defn step!
  "Run the player `event` through the library, and give its effects."
  [event]
  (let [{:keys [library effects]} (library/step @lib event)]
    (reset! lib library)
    effects))

(step! [:progress id 754 (:duration page)])  ; => []
(step! [:skip id :forward])
;; => [[:seek ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-274"] 784]]
```

The profile is `(:profile @lib)`, which every function of `profile`
takes. To change it, `library/update-profile` calls one of them with the
time, like `enqueue` here.

## New episodes

To check for new episodes, call `refresh!`. It fetches whatever is due:
the feeds, the lists that the listener follows, and the feeds of new
subscriptions. The new episodes of a show with `:auto-queue?` go to the
queue. The listener can change the library while it fetches, so put the
library that it gives into the atom with `library/merge`, which keeps
those changes:

```clojure
(def refreshed (library/refresh! @lib))
(swap! lib library/merge (:library refreshed))

(dissoc refreshed :library)
;; => {:at #inst "…" :added [] :backfilled [] :renamed [] :moved {}
;;     :counts {:checked 0 :unchanged 0 :changed 0 :failed 0 :left 0}
;;     :seconds 0.001 :report []}

(library/next-refresh @lib {:after refreshed})
;; => #inst "…"  ; twelve hours later
```

Nothing is due right after subscribing, so in an app, call `refresh!` from
a timer, at the time that `library/next-refresh` returns. The result lists
the new episodes, the feeds that moved, and the fetches that failed. Once the show has a new episode, `:added` holds its id, e.g.
`[["917393e3-…" "PC20-275"]]`, and the episode is in the queue. A feed
that failed stays as it was, and waits longer before the next try.

## The other devices

Each device writes its profile, a CRDT of
[mind-meld](https://github.com/simongray/mind-meld), to a store as one
document, and reads the documents of the other devices. A folder that a
sync client mirrors is a store, and so is a WebDAV collection, reached
through [webdav-clj](https://github.com/simongray/webdav-clj). The stores
come from [like-minded](https://github.com/simongray/like-minded), which
podcast-clj syncs through and brings along. With `library/backend`, a
store becomes the backend of a sync, here with the app's name in its
document:

```clojure
(def dropbox
  (library/backend (folder/store "/Users/simon/Dropbox/Podcasts")
                   {:app "quickstart"}))

(def synced (library/sync! @lib [dropbox]))
(swap! lib library/merge (:library synced))

(:report synced)          ; => []
(library/next-sync @lib)  ; when to sync again
```

The library keeps the cursors for the next sync under `:sync`, and the
report names any backend that failed. The folder now holds one
document for this device, and another device that syncs through the same
folder gets the subscription and the queue. The [providers
page](https://github.com/simongray/like-minded/blob/master/doc/providers.md)
of like-minded lists where the common sync clients and WebDAV servers
keep their folders.

## What next

- [How to build a podcast app](building-an-app.md): every part of an app,
  with the lower-level functions for more control.
- [Principles](principles.md): what guides the design of podcast-clj.
- [Namespaces](namespaces.md): a line for each namespace.
- Look at `(keys @lib)` and `(:profile @lib)` to see what the library
  holds.
