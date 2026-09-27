# Syncing

`dk.simongray.podcast.app.player` keeps a player's state as one map of
stamped entries, and its `merge` reconciles two devices' states by letting
the later entry win; a generative test shows the merge to be commutative,
associative and idempotent. A merge with those three properties is a join,
so a backend never has to resolve a conflict: it only has to hold
documents. This document is the design of the engine that rests on that,
`dk.simongray.podcast.app.sync`, and of the stores under it, kept simple
enough that any place a listener might keep documents, a synced folder, a
WebDAV server, a Git repository, a service with a protocol of its own, or
something nobody has thought of yet, is a short namespace away.

## What a backend has to be

Because merging is a join, three shapes of backend suffice, and all three
reduce to the same engine.

**One document per device, merged on read.** Each device writes only its
own document and reads everyone else's. Two writers never touch the same
document, so there is no write conflict to detect, and a folder is a
complete backend: one a sync client mirrors, a Git working tree, a WebDAV
collection. A new device reads the folder and has everything. Retiring a
device is deleting its document; its writes live on in the other devices'
documents, since every document carries the full merged state and not only
its writer's own entries. This is the default strategy.

**One shared document with optimistic concurrency.** A store that cannot
list, a key-value store or a single record, holds one document instead:
fetch, merge, put on condition that the version is unchanged, and on a
conflict fetch and merge again. The retry is safe because merging twice
changes nothing.

**A service that keeps its own model.** The gpodder family of services
merges on the server and speaks in subscription changes and episode actions
since a cursor. For these the library translates: what changed since the
last push goes out as their actions, and what they report since the last
pull comes back as a partial state to merge in. A service is a backend
directly, not a store.

Two rules hold across all three. Local state is never replaced, only merged
into, so a failed pull changes nothing and a failed push is retried on the
next step. And a device may sync to several backends at once, merging
everything read from all of them and writing to each, which is how a
listener keeps a folder and a server in step, or migrates from one store
to another by running both for a while.

## The seams

There are two, at different heights, and the lower one is derived from the
upper.

A **backend** is what the engine talks to. `pull` takes the local state,
its cursor and the instant of the step, and returns the states to merge
and the cursor for next time; `push` takes the merged state, the cursor
and the instant, writes, and returns the cursor, and a state of its own
when it learned more while writing, as a shared document does on a
conflict. A service computes what to send from its cursor with
`player/changes-since`. Anything that moves data between devices
implements this, including the unusual ones at the end of this document.

A **store** is the common case underneath: named documents with version
tokens. One namespace turns any store into a backend by applying a strategy,
the envelope, the retries and the diagnostics, so that a new store is three
or four functions and gets everything else for free.

```clojure
;; a store: a map of functions; its constructor closes over the credentials
{:fetch  (fn [name])            ; {:body "…" :version "etag"}, nil when absent
 :put    (fn [name body opts])  ; {:version "…"} when written, {:conflict? true}
                                ; when refused; opts carry {:if-version "…"}
 :list   (fn [])                ; [{:name "…" :version "…"} …]; optional
 :delete (fn [name])            ; optional
 :name   "Nextcloud"}           ; for the UI and the report, never the credentials

;; a backend: what the engine drives
{:pull (fn [state ctx at])  ; {:states [state …] :ctx ctx' :report […] :partial? false}
 :push (fn [state ctx at])  ; {:ctx ctx' :state state' :report […]};
                            ; the state and the report optional
 :name "gpodder.net"}
```

On the JVM every function returns its value and throws on failure; in
ClojureScript every function may return a value or a promise.
`dk.simongray.podcast.async` is what lets the engine and the stores be
written once for both: `then`, `all` and `attempt` take a value or a
deferred one, a future on the JVM and a promise in ClojureScript. A refused
precondition is a value, not an exception, because it is an expected
outcome of the single-document strategy and every store author has to
signal it the same way; what the network or the service does wrong is an
exception, and the engine turns exceptions into lines under `:failed`, as
`directory/search!` does.

The contract is a map of functions rather than a protocol or a multimethod,
for reasons particular to this problem. Wrapping is `update store :fetch`,
so an encrypting store, a caching store or a logging store is a few lines
over any other store, and a store that fails on cue for the tests is two
lambdas. Optional capabilities are keys, so the engine asks `contains?`
rather than `satisfies?` about a partial implementation. Credentials live
in the constructor's closure and the map carries only a `:name`, so a store
never prints a token, where a record with a token field prints it by
default. Configuration and behaviour are kept apart on purpose: the
constructor's argument is the EDN an app stores, and the store is the value
it uses. Nothing is lost for later: a protocol can be extended to maps
whenever type-based dispatch is wanted, without touching a store already
written.

Every store namespace keeps one convention, so that an app treats them
alike: `store` for the store over a place, and `backend` for the backend
in one call with the options of both. Where the places are, a server's URL
shape or a sync client's folder, is a fact about the product that changes
with it, so the library holds none of them; the guide tables the current
ones, dated. `store/check!` tries any store through the contract, a probe
written, refused with a stale version, read back and removed, and reports
as data for a settings screen; written against the contract, it covers
every store to come. `dk.simongray.podcast.app.sync.webdav.server/handler`
serves any store over WebDAV, so that a Clojure backend is a sync server in
two lines.

## The engine

`dk.simongray.podcast.app.sync` is pure, in the library's way; the IO sits
in the backends at the edge.

`document` writes a device's state into the envelope below as EDN, and
`read-document` reads one back, throwing `::malformed` for a body that is
not one, so that a backend can record it. `read-documents` reads what a
pull fetched, taking the instant `at` of the step, and `reconcile` merges
the states it found into the local state, `{:state merged :report [...]}`;
both put the step's diagnostics in the report: `:sync/clock-skew` for a
document written later than now by more than a minute, naming the device
and the difference; `:sync/unreadable-document` for a body that would not
read; `:sync/newer-format` for an envelope a newer library wrote, which is
left out rather than half understood. `player/changes-since` returns the
entries of a state stamped after an instant, by whichever device, as a
partial state that merges; it is what a service adapter pushes.

`store/backend` builds a backend from a store and a strategy. The step of
the per-device strategy is:

1. `list` the root, and `fetch` every document whose version differs from
   the one the cursor remembers, or that the cursor has not seen. Files that
   do not end in `.edn` are not the engine's and are passed over.
2. `read-document` each, keeping the unreadable ones for the report.
3. `reconcile` the local state with them.
4. When the merged state differs from what this device last wrote, which the
   cursor remembers as a hash, `put` this device's document. The document is
   this device's own, so no precondition is needed; its version changing
   under this device means two installations share a device id, which is
   reported as `:sync/shared-device`. A device that has been retired writes
   nothing and reports `:sync/retired-device`.
5. When the subscriptions changed and the backend was built with `:opml?`,
   write `subscriptions.opml` beside the documents, from `player/to-opml`,
   so that a synced folder holds a file any podcast app imports. The mirror
   is written, never read; import is `player/from-opml`, explicitly. Delete
   the documents of the devices retired since.
6. Return the states, the cursor holding the versions of the documents seen
   and the hash written, and the report.

The single-document strategy fetches the one document, merges, and puts it
with `:if-version`; on `{:conflict? true}` it fetches and merges again,
three times at most, and then reports `:sync/conflict`. A store without
`:list` gets this strategy by default. Both strategies take a `:direction`,
`:both` by default, `:push` for a backup target such as a repository that
is never read back, `:pull` for a read-only source.

`sync!` drives one or several backends and returns `{:state :ctx :failed
:report}`: the merged state, a cursor per backend for the app to persist
beside its state, the backends that failed with their messages, and the
report. The backends are asked at once, and on every step the whole states
read are acknowledged in the registry and the tombstones every device has
seen are compacted, below. On the JVM `sync!` blocks; in ClojureScript it
returns a promise. Losing the cursors costs nothing but one full read and
write. An app that wants control of the IO, an iOS background task with its
own budget, drives the pure functions itself: list, fetch, `reconcile`,
put.

The library schedules nothing, as everywhere else. An app calls `sync!` on
start, on going to the background, after a burst of changes, and when a
file watcher or a service's push says the store changed; `next-at` gives a
timer its policy, soon after a change, later when idle, backing off after
failures.

## The documents

A document is a device's whole merged state in an envelope:

```clojure
{:version    1                        ; the format, for a reader to check
 :device     "8f1c9e2a-…"             ; the writer, the id its state carries
 :written-at #inst "2026-09-26T…"     ; by the writer's clock
 :app        "my-app"                 ; who wrote it, optional
 :state      {…}}                     ; the player state
```

Documents are named `<device-id>.edn` under a root that is the store's
business: a folder, a WebDAV collection, a path in a service. The root
belongs to one listener; two listeners are two roots. The layout is
deliberately app-agnostic. Two apps built on the library can share one
root, since the state is the library's, and a client in another language
can read the folder once it knows the format.

EDN is the format, read with the platform's EDN reader and its `#inst`; the
library has no dependency to add, and EDN carries the state as it is. JSON
is a defined mapping later, for a client in another language: the episode
ids are vectors, which JSON cannot key a map with, so the mapping lists
them. Transit would carry EDN over JSON but is a dependency, and the reasons
for it are not here yet.

The version changes only when the meaning of an existing key changes.
Additive growth needs no bump, because a reader merges through the keys it
does not know by their shape, which is what lets an app add entries of its
own and lets the library grow the state without a flag day. A reader
migrates an older version with a pure function per step and leaves a newer
one out, reported.

Size is a few hundred bytes per subscription and a couple of hundred per
episode entry, so ten thousand episodes run to a few megabytes. Growth is
answered by compaction, below.

## The player state

The state is a state-based CRDT of the simplest kind, and naming its parts
says what merges how.

**Merging by shape.** `merge` merges every key of both states by the shape
of its value: a stamped entry is taken whole from the later side, a map
merges key by key down to the stamped entries, an instant merges as the
later of the two, and anything else stays the local device's. A stamped
entry is a last-writer-wins register, a map of them an add-and-remove set
with tombstones, the clock a max register, and the state their product with
`merge` as its join. An app's own bookmarks or playlists sync with no
change to the library as long as they are stamped.

**Device identity.** The `:device` of a state is a stable id, `(str
(random-uuid))`, generated once per installation and kept by the app, and
the names live in a registry, `:devices {id {:name "Simon's phone"
:platform "ios"}}`, one stamped entry per device written only by that
device, so that a UI can list the devices and let the listener retire one.
A retirement is a tombstone of its own, under `:retired`, so that the
device cannot undo it by describing itself again; any device that sees it
deletes the document when the store can, and the retired device writes no
more. A last-synced instant is deliberately not kept in the registry:
written on every step, it would change every document on every step and
keep every device writing forever.

**A clock that cannot fall behind.** Last-writer-wins trusts the devices'
clocks, and a device with its clock in the future wins every tie until the
others catch up. The state keeps `:clock`, the latest stamp it has written
or merged, merged as the later of the two, and every write stamps with the
instant given or, when that is not later than the clock, the millisecond
after it, the way a hybrid logical clock does; `player/after` is the same
rule for an app stamping entries of its own. Without it a device that
wrote at an instant no later than an entry it had already merged kept its
own entry while every other device kept the other, and the states never
met. The skew itself is reported when it shows, as `:sync/clock-skew`.

**The queue as an element set.** The queue is one entry per episode
carrying a rank from `dk.simongray.podcast.rank`: strings in base 62 that
always leave room for another between two, and that move a digit at the
ends so that a queue appended to a thousand times keeps short ranks.
Removals are tombstones, and the order is derived by rank, then by stamp
and device where two devices ranked at once. Concurrent additions both
survive, a finished episode stays gone, and only two devices moving the
same episode at once need a winner, the later. The one imprecision is a
move between two entries that tie on the rank, which lands after both; it
takes two devices ranking the same place at once and then a third change
between them.

**Settings field by field.** Preferences, and the settings of a
subscription, are one entry per field, so that the skip amount changed on
one device and the rate on another both survive; the subscription's
settings live apart from the subscription so that they outlive an
unsubscription and come back with the feed. The accessors hide the
representation.

**Provenance and compaction.** Every entry carries the device that wrote
it, and compaction rests on that: an entry may be dropped only once every
live device has merged it, or an offline device brings it back, and with
tombstones that resurrects a subscription the listener removed. Timestamps
alone cannot say who has seen what, because a device pushes late; what can
is a small version vector in each device's registry entry, `:seen
{other-id latest-stamp}`, the latest stamp by each other device that this
device has merged, kept monotone by `player/acknowledge`, which `sync!`
calls with the whole documents it read. Two things keep the vector honest:
it ignores the registry itself, or two devices would acknowledge each
other's acknowledgements and never stop writing, and it counts only whole
states, never a service's changes since a cursor, which would overstate
what was seen. An entry by device A stamped at `t` is safe to drop once
every live device other than A has acknowledged A up to `t`, this device
counting as having what it holds, since a device's document is cumulative
and one written after `t` carried the entry or what superseded it; and
there must be at least one such device, since a device alone gains nothing
by forgetting and a device joining later could bring back what it forgot.
`player/compact` drops the tombstones of unsubscriptions and queue removals
that pass, and only those older than thirty days, a buffer for a device
that joins with a state of its own; the played marks stay, since the
listener still sees them. `sync!` compacts on every step unless told not
to. A device that stops syncing holds compaction back until it is retired,
and `player/lagging` names it for the UI. A device restored from a backup
can still resurrect what everyone else forgot; the cure is to retire its id
before restoring.

**What stays out.** Per-device matters, the downloads, the titles cache,
the page the listener was on, live outside the player state and are never
synced. The queue is one listener's queue following them from device to
device, as in the mainstream apps; an app that wants a queue per device
keeps it outside the state.

## The stores

`store/memory` is a map over an atom, the engine's test double and an
app's own store when it keeps the documents itself. `app.sync.file` is a
folder on the JVM, one a sync client mirrors or a Git working tree: every
file is written whole and moved into place, so the client never sees half
of one, and the version is the file's modification time and size.
`app.sync.webdav` is a collection on any WebDAV server, the self-hosted
world included, with plain HTTP: GET, PUT, DELETE and a PROPFIND at depth
one for the listing, the ETag as the version and `If-Match` as the
precondition; it is built the directories' way, the calls as request maps
and the answers read from recorded multistatus documents, which the
library's own XML parser reads with the `DAV:` namespace bound to one alias
whatever prefix a server wrote, and in a browser it sends with fetch, the
server's origin permitting. `app.sync.webdav.server` is the other side, a
Ring handler serving any store over WebDAV, which the inspector mounts at
`/dav/` over its folder so that its browser player syncs from the same
origin and any WebDAV client can mount it. `app.sync.encrypted` wraps any
store, below.

The seams admit the rest without a change to the library. An HTTP API that
holds files with a version, a revision or an ETag, is a store: one call per
operation with a bearer token, refusing a stale version if it can, one
document per device if it can list. A service with its own model is a
backend, gpodder's the first to write, below. Where a server keeps its
collections and a client its folder are facts about those products, which
the guide tables, dated.

## gpodder, for later

The gpodder API is a backend rather than a store, so it is the first test
of the pull and push seam. It has two feeds of changes, each since a
timestamp the server hands back: the subscriptions of a device, as lists of
feed URLs added and removed, and the episode actions, `play` with
`started`, `position` and `total`, plus `download`, `delete` and `new`,
each naming a podcast URL, an episode URL, a device and a timestamp without
a zone, to be read as UTC. A device is registered by an id, ours, with a
caption. Nothing else crosses: no queue, no settings, no preferences, and
no played mark beyond a play whose position reached the total, which is
how the adapter would send one. Several servers speak it, hosted and
self-hosted, and the prize is interop with the apps that sync through
them.

`pull` would read both feeds since the cursors and return a partial state,
`:partial? true` so that nothing is acknowledged from it: subscriptions and
tombstones stamped with the server's timestamp, play actions as progress.
`push` would send `player/changes-since` the last push as subscription
changes and play actions. Both built the directories' way, `request` and
`response` over recorded answers, with a stand-in server in the tests on
both platforms and `backend` in one call like the stores'.

The difficulty is identity. gpodder names a podcast by its feed URL and an
episode by its enclosure URL; the library names them by guid, because URLs
are what changes: feeds move hosts, schemes and slashes, and enclosure URLs
differ per request under dynamic ad insertion, so keyed by URL the same
episode would have different keys on different devices and the registers
would never meet. A feed URL becomes a guid by `guid/from-url` when the
feed declares none; a declared guid, and every episode, needs the app's
parsed feeds. So the backend takes two functions, the episode id of a
podcast and enclosure URL and the enclosure URL of an episode id, and the
library should offer both over a collection of feeds through
`resolve/enclosure`, so that the common case is one line. Where a feed
regenerates or omits its guids, `feed/item-guid` already falls back to the
enclosure URL, which is the same edge. The reverse translation, a
gpodder-compatible server over the library so that those apps sync against
a listener's own Clojure backend, is the same mapping run the other way
and belongs to the reference app later.

## The unusual ones

The seams are meant to be enough for what nobody has planned. A Nostr
relay holds one replaceable event per device, with the device id as its
tag, and lists by author and kind, so it is a per-device store. A Solid pod
is a container of resources with ETags, so it is WebDAV in all but name.
IPFS publishes a device's document under its IPNS name. An IMAP folder
holds one message per device, replaced on each write. Two devices on one
network each serve their own document and pull from the other. A
paste-and-scan exchange is `document` as text on one side and
`read-document` on the other, a store with `fetch` and `put` over the
clipboard. A database keeps documents as rows. A key-value service without
listing is the single-document strategy. None of them needs a change to
the library.

## Credentials and privacy

A constructor takes its `:credentials` as a value or as a function of no
arguments that returns the current token, since services expire theirs and
the app refreshes them; the OAuth dance is the app's, as the Podcast Index
credentials are. The engine never sees credentials, and nothing in a
result, a report or a `:name` carries one.

A document describes a listener's habits, and a provider can read what it
stores. The encrypting wrapper, `app.sync.encrypted`, is the extension the
map-of-functions contract was chosen for: AES-256-GCM under a key PBKDF2
derives from a passphrase, `javax.crypto` on the JVM and WebCrypto in a
browser, whose asynchrony the promise convention covers, over any store,
the ones not yet written included. Each document is an envelope carrying
its own salt, IV and iteration count, so there is no key file to lose and
the key is derived once per salt; a plain document passes through, so a
folder can be encrypted from now on; a wrong passphrase fails that backend
naming the document. The subscription list a backend mirrors can be left
in the clear with `:except`, for other apps to read.

## Testing

The memory store is the engine's test double, and a store that fails on
cue is two lambdas. The ranks have a property of their own, that a rank
between two is between them and none ends in a zero. A convergence property
extends the merge properties to the engine: several devices apply random
operations and sync in random orders through random backends, then run
three quiet rounds, one for the entries to reach everyone, one for the
acknowledgements and one for the compaction that follows, and every state
ends the same, with every tombstone gone and nothing the listener sees
changed. The folder store runs against a temporary directory, the
single-document strategy against a memory store that answers conflicts on
cue, and the WebDAV store against recorded answers, PROPFIND and all, with
a live call in a comment block. The inspector's WebDAV folder is tested
through Pedestal like the other routes and doubles as a real WebDAV server
for the WebDAV store's test in `test/inspector/`, where Pedestal is
allowed; the library's own tests stay on the floor. The encrypted suite
holds an envelope sealed on each platform and opens it on the other.

## Namespaces and layers

The engine and its stores group under `app.sync`, beside `app.player` and
`app.search`: what an app keeps, what it finds, and how it keeps several
devices in step. `app.sync` is the engine; `app.sync.store` the store
contract, the strategies and `check!`; `app.sync.file`, `app.sync.webdav`
and `app.sync.encrypted` the stores, `app.sync.webdav.server` the server;
`async` and `rank` are primitives beside `time` and `guid`, the deferred
values every store is written over and the ranks the queue orders by. A
top-level `sync` group, like `directory`, was the alternative; `app.sync`
won because what is kept in step is `app.player`'s state, and the three
read as one account of an app. Nothing in the library stands on `app.sync`,
and the architecture test holds the layering.

## Decisions

The choices that could have gone otherwise:

- `app.sync` is the group, not a top-level `sync`.
- One document per device, merged on read, is the default strategy; one
  shared document only where a store cannot list.
- EDN is the only format until a client in another language needs JSON.
- Stores are maps of functions, not a protocol, and a store's
  constructor closes over its credentials.
- The inspector serves a WebDAV folder as the self-hosted demonstration.
