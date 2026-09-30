# Quickstart: a podcast app in thirty lines

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

An app keeps a library: one map with everything of a listener in it. Make
one for this device, and subscribe to a show:

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.library :as library]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.sync.file :as file])

(def now (java.util.Date.))
(def url "https://feeds.podcastindex.org/pc20.xml")

(def lib (-> (library/make "laptop" now {:name "Laptop"})
             (library/subscribe url (podcast/fetch! url) now {:auto-queue? true})))

(keys (library/subscriptions lib))
;; => ("917393e3-1b1e-5cef-ace4-edaa54e1f810")
```

`"laptop"` is the id of this device. Make it once per installation, and
keep it. `podcast/fetch!` fetched the feed and parsed it. `subscribe` kept
the feed with the validators of the fetch, added the subscription with its
settings, and named the show by its guid.

The library is EDN. Save it as you save your other values, and read it
back at the next start.

## The episodes

Select episodes across the shows, in the order you want:

```clojure
(def episodes (library/episodes lib {:order :newest :limit 3}))

(map (fn [[show item]] (:rss/title item)) episodes)
;; => ("Episode 272: \"Is this Y'all?\"" "Episode 271: Foot Terminal" "Episode 270: YOLO Guy")

(def id (let [[show item] (first episodes)]
          (library/episode-id lib show item)))
;; => ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]
```

An episode is a `[show item]` pair, the feed and its item as
`podcast/parse` gave them. Its identity for the player is the guid of the
show and the guid of the item.

## A page of an episode

The view of an episode is one map for a page. `episode!` reads the files
of the episode too: the chapters, the transcript and the media file.

```clojure
(def page (library/episode! lib id))

(select-keys page [:title :duration])
;; => {:title "Episode 272: \"Is this Y'all?\"" :duration 5501.208}
(:notes page)                                  ; the show notes, sanitized, as Hiccup
(:source (:chapters page))                     ; => :file, the chapters file of the episode
(count (:chapters (:document (:chapters page))))  ; => 40
(count (:segments (:document (:transcript page))))  ; => 1481
```

A file that does not come leaves `:error` in its part, and the call
throws nothing for what the network does. `library/episode` gives the
view without the files, for a list.

## The player

The player state holds the queue and the progress. Your app holds the
media element, and sends each of its events to `step`. `step` gives the
new library and the effects that the app must do:

```clojure
(def lib (update lib :player player/enqueue id now))
(player/queue (:player lib))
;; => [["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"]]

(def r (library/step lib [:progress id 754 3600] now))      ; on each timeupdate
(:effects r)                                                 ; => []
(:effects (library/step (:library r) [:skip id :forward] now))
;; => [[:seek ["917393e3-1b1e-5cef-ace4-edaa54e1f810" "PC20-272"] 784]]
```

`(:player lib)` is the player state of `app.player`, and every function of
that namespace takes it. `enqueue` is one of more than eighty.

## New episodes

`refresh!` fetches what is due: the feeds, the lists that the listener
follows, and the feeds of subscriptions that have none. Each fresh feed
goes to the player, which queues the new episodes of a show with
`:auto-queue?`.

```clojure
(def r (library/refresh! lib now))
(select-keys r [:added :moved :report])
;; => {:added [] :moved {} :report []}
```

Nothing is due right after a subscribe. Call `refresh!` from a timer, and
give it the time. A feed that did not come stays as it was, with its
failure under `:report`, and the next fetch of it waits longer.

## The other devices

Each device writes its player state to a store as one document, and
reads the documents of the others. A folder that a sync client mirrors is
a store, and so is a WebDAV collection.

```clojure
(def synced (library/sync! (:library r) [(file/backend "/Users/simon/Dropbox/Podcasts" {:app "quickstart"})] now))

(:next-at synced)   ; => #inst "…", when to call sync! again
(:failed synced)    ; => nil, or the backends that failed
```

The folder holds one document for each device. Another device that syncs
through the same folder learns the subscription and the queue. [Sync
stores and documents](stores.md) gives the folders of the sync clients
and the URLs of the WebDAV servers.

## What next

- [How to build a podcast app](building-an-app.md): every step of an app,
  the call of the library first and then the functions below it.
- [Namespaces](namespaces.md): the conventions of the API, and one line
  for each namespace.
- The library is one map. Print it, and read it.
