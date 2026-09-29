# Syncing

This page explains the design of the sync between the devices of one
listener. It covers the engine in `app.sync`, the stores below it, and the
parts of the player state that make sync possible. [Sync stores and
documents](stores.md) gives the contracts and the formats. [How to build a
podcast app](building-an-app.md) gives the steps for an app.


## Why a backend only has to hold documents

`app.player` keeps the player state as one map of stamped entries. Its
`merge` reconciles the states of two devices: the later entry wins. A
generative test shows that the merge is commutative, associative and
idempotent. A merge with these three properties is a join, so a backend
never has to resolve a conflict. It only has to hold documents.

This keeps the backends simple. Each kind of place needs only one short
namespace. Examples are a synced folder, a WebDAV server, a Git repository,
and a service with its own protocol.


## Three kinds of backend

Because the merge is a join, three kinds of backend are sufficient, and all
three use the same engine.

**One document per device, merged on read.** Each device writes only its own
document, and reads all other documents. No two devices write the same
document, so there is no write conflict to detect. As a result, a folder is
a complete backend: a folder that a sync client mirrors, a Git working tree,
or a WebDAV collection. A new device reads the folder and gets everything.
To retire a device, call `player/retire`, and the next sync of each device
deletes its document. Its writes stay in the documents of the other devices,
because each document holds the whole merged state, not only the entries of
its writer. This is the default strategy.

**One shared document, with optimistic concurrency.** A store that cannot
list, such as a key-value store or a single record, holds one document. A
device fetches the document, merges it, and puts it back on the condition
that its version did not change. On a conflict, the device fetches and
merges again. The retry is safe, because a second merge changes nothing.

**A service with its own model.** gpodder and similar services merge on the
server, and send the changes to subscriptions and the episode actions since
a cursor. For these services, podcast-clj translates. A push sends the
changes since the last push as actions of the service. A pull turns what the
service reports since the last pull into a partial state, which merges like
any other state. Such a service is a backend itself, not a store.

Two rules apply to all three kinds. First, the local state is never
replaced, only merged into. A failed pull changes nothing, and a failed push
happens again at the next step. Second, a device can sync to several
backends at the same time. It merges what it reads from all of them, and
writes to each. This lets a listener keep a folder and a server in step, or
move from one store to another by using both for some time.


## Backends and stores

There are two interfaces, at different levels.

A **backend** is what the engine calls. `pull` returns the states to merge
and a cursor for the next step. `push` writes the merged state and returns a
cursor. It can also return a state, if it learned more while it wrote, as
the shared strategy does after a conflict. A service computes what to send
from its cursor with `player/changes-since`. Each thing that moves data
between devices is a backend.

A **store** is the common case below a backend: named documents with version
tokens. `store/backend` makes any store a backend, and adds the strategy,
the envelope, the retries and the diagnostics. A new store thus needs only
two to four functions.

On the JVM, each function returns its value and throws on failure. In
ClojureScript, each function can return a value or a promise. With `async`,
the engine and the stores are written once for both platforms. A deferred
value is a future on the JVM and a promise in ClojureScript. `then` and
`all` take values and deferred values alike. `attempt` calls a function and
gives its value or its error as data.

A refused write is a value, not an exception. It is a normal result of the
shared strategy, and each store must signal it in the same way. Errors of
the network or of a service are exceptions. The engine turns each exception
into an entry under `:failed`, as `directory/search!` does.


## Why stores are maps of functions

The store contract is a map of functions, not a protocol or a multimethod.
The reasons come from this problem:

- To wrap a store is to `update` one of its keys. An encrypting, caching or
  logging store is only a few lines over any other store. A store that fails
  when a test tells it to is two anonymous functions.
- Optional functions are optional keys. The engine looks up the key, and
  needs no `satisfies?` for a store that implements part of the contract.
- The credentials stay in the closure of the constructor, and the map
  carries only a `:name`. A store never prints a token. A record with a
  token field prints it by default.
- Configuration and behavior stay apart. The argument of the constructor is
  the EDN that an app stores, and the store is the value that the app uses.

Nothing is lost for later. When type-based dispatch becomes necessary, a
protocol can be extended to maps, with no change to the existing stores.

Each store namespace has the same two functions. `store` makes the store
over a place, and `backend` makes the backend in one call, with the options
of both. The places themselves are facts about other products, such as the
URL shape of a server or the folder of a sync client. These facts change.
For this reason, podcast-clj contains none of them, and [Sync stores and
documents](stores.md) has a dated table instead.

`store/check!` tests any store through the contract. It lists the store,
writes a probe document, puts it again with a stale version, reads it back
and removes it. It reports the result as data, for a settings screen. For a
store that cannot list, it tests what one shared document needs: a refused
stale version. Because `check!` uses only the contract, it also works for
future stores. `app.sync.webdav.server/handler` serves any store over
WebDAV, so a Clojure server becomes a sync server with two lines of code.


## The engine

`app.sync` is pure. The I/O is in the backends, at the edge.

`document` writes the state of a device into an envelope, as EDN.
`read-document` reads it back, and throws for a body that is not a sync
document. It also throws for a state with a value that the merge cannot
take, such as a stamp that is not an instant. Without this check, the
merge gives such a value to every device, and an operation fails there.
`player/problem` finds these values. `read-documents` reads the documents
of a pull at the instant of the step, and `reconcile` merges their states
into the local state. Both report problems: a device clock that runs
ahead, a document that does not read, and a document from a newer version
of podcast-clj. A reader leaves out a newer document, because it cannot
understand all of it.

`sync!` drives one or more backends in one step, and asks all of them at the
same time. At each step, it acknowledges in the device registry the whole
states that it read, and compacts the tombstones that all devices know (see
[Provenance and compaction](#provenance-and-compaction)). On the JVM,
`sync!` blocks. In ClojureScript, it returns a promise. If an app loses the
cursors, the cost is one full read and one full write.

An app can also control the I/O, for example in an iOS background task with
a time budget. It then does the steps itself: list, fetch, `reconcile`, put.

podcast-clj schedules nothing. An app calls `sync!` when it starts, when it
goes to the background, and after a series of changes. It also calls `sync!`
when a file watcher or a push from a service tells that the store changed.
The app runs one step at a time, because a step that overlaps another can
write an older state after a newer one. A file watcher also reports the
writes of the step itself, so the app lets such a call wait until the step
ends. `next-at` gives the timer of the app a policy: soon after a change,
later when nothing changed, and with longer waits after failures.


## The documents

The documents of one listener are in one root: a folder, a WebDAV
collection, or a path in a service. Two listeners use two roots. The layout
does not depend on the app. Two apps built on podcast-clj can share one
root, because the state belongs to podcast-clj. A client in another language
can read the folder when it knows the format.

The format is EDN, read with the EDN reader of the platform and its `#inst`.
podcast-clj needs no new dependency for EDN, and EDN holds the state as it
is. JSON can come later as a defined mapping, for a client in another
language. The episode ids are vectors, and JSON cannot use a vector as a
key, so the mapping must list them. Transit can carry EDN over JSON, but it
is a dependency, and there is no reason for it yet.

The version of the format changes only when the meaning of an existing key
changes. New keys need no new version, because a reader merges keys that it
does not know by the shape of their values. As a result, an app can add
entries of its own, and podcast-clj can grow the state without a flag day.
When a version 2 comes, a reader will convert version 1 with a pure
function. It leaves out a version that is newer than its own, and reports
it.

A document takes a few hundred bytes for each subscription and about two
hundred bytes for each episode entry. Ten thousand episodes take a few
megabytes. Compaction limits the growth.


## The player state as a CRDT

The player state is a state-based CRDT of the simplest kind. This section
tells how each part merges.

### Merge by shape

`merge` merges each key of the two states by the shape of its value:

- a stamped entry: the later of the two, whole
- a map of other values: key by key, down to the stamped entries
- an instant: the later of the two
- any other value: the value of the local device

A stamped entry is a last-writer-wins register. A map of stamped entries is
an add-remove set with tombstones. The clock is a max register. The state is
their product, and `merge` is its join. When two entries have the same
instant, the device id decides, and then the printed entry. Each platform
makes the same choice. An app can add entries of its own, such as
playlists, to the state, and they sync with no change to podcast-clj, if
the app stamps them.

### Device identity

The `:device` of a state is a stable id, such as `(str (random-uuid))`. The
app makes it once per installation and keeps it. The device registry holds
the descriptions: `:devices {id {:name "Simon's phone" :platform "ios"}}`.
Each device writes only its own entry, so a user interface can list the
devices and let the listener retire one.

A retirement is a tombstone of its own, under `:retired`, so the retired
device cannot undo it by describing itself again. A retired device writes no
more, with either strategy. With one document per device, each device that
sees the retirement also deletes the document of the retired device, if the
store can delete.

The registry does not keep a last-synced instant. Such an instant changes at
each step, so each document changes at each step, and the devices never stop
writing.

### A clock that cannot fall behind

Last-writer-wins trusts the clocks of the devices. A device with its clock
in the future wins each tie, until the other clocks catch up. The state
keeps `:clock`, the latest stamp that it wrote or merged. A merge keeps the
later of the two clocks. Each write stamps with the given instant. If that
instant is not later than the clock, the write stamps with the millisecond
after the clock, as a hybrid logical clock does. `player/write` writes the
entries of an app by the same rule, and moves the clock on, so that no two
of them get the same stamp.

A convergence test showed why this rule is necessary. Without the rule, a
device sometimes wrote at an instant that was not later than an entry that
it merged before. That device then kept its own entry, while all other
devices kept the merged entry, and the states never met.

A skew of some seconds does no harm. A write after a merge still wins, and
the stamps run ahead of real time by the skew at most, for about as long.
The only effect is on two changes to one entry that two devices make
before either syncs: the device with the fast clock wins, as it does with
any clock and last-writer-wins.

A clock that is years ahead does more. A merge keeps the later clock, so
all devices then stamp in that year until real time catches up. Nothing is
lost, and the states still meet. But the stamps no longer tell when a
thing happened, and `compact` keeps each tombstone until its stamp is
thirty days in the past. To refuse such a state would stop the spread, but
it would also stop the sync of that device, and its own state would keep
the future stamps after its clock was set right. For this reason, the
report warns and nothing more: `:sync/clock-skew` names a device that
wrote its document more than a minute ahead of this device's clock. The
time of the write is the time of the device, not a stamp, so the warning
names the device with the wrong clock, and not the devices that only
merged its stamps.

### The queue as a set of ranked entries

The queue has one entry for each episode, with a rank from `rank`. A rank is
a base-62 string, and there is always room for another rank between two
ranks. At the ends, a new rank changes by one digit, so a queue that gets a
thousand appends still has short ranks.

A removal is a tombstone. The order comes from the ranks, and then from the
stamp and the device when two devices ranked at the same time. Additions on
two devices both survive, and a finished episode stays removed. Only two
devices that move the same episode at the same time need a winner: the later
one.

One imprecision remains. A move between two entries with the same rank lands
after both. This needs two devices that rank the same place at the same
time, and then a third change between them.

Each playlist is a set of ranked entries in the same way, under its name.

### Counts of listening

The seconds that the listener hears are counted for each show, month and
device. Each count belongs to one device, which alone writes it, and it
only grows. For this reason, the later of two stamps is also the higher
count, and a merge by stamp keeps it, as for any other entry. The sum over
the devices is the total. A count for all devices together would lose the
seconds of one device when two devices count at the same time.

A count of one month for each show keeps the document small: a listener of
twenty shows on two devices adds about 500 entries a year. A count for
each day would add thirty times as many.

A device restored from a backup can write a count lower than the count
that the other devices hold, with a later stamp. Retire the id of the
device before the restore, as for compaction.

### Settings field by field

The preferences, and the settings of a subscription, have one entry for each
field. A skip amount changed on one device and a rate changed on another
both survive. The settings of a subscription are apart from the
subscription, so they survive an unsubscription and come back with the feed.
The accessors hide this layout.

The folder of a show is one of these settings. For this reason, when two
devices move a show to two folders at the same time, the later move wins,
and the show is never in two folders.

### Sets of marks

The inbox, the stars, the bookmarks, the saved filters, the tags of a show
and the followed OPML lists are sets, one entry for each episode, second,
name, tag or URL, with a tombstone for each removal, as the subscriptions
are. A star that one device removes stays removed when an older star comes
from another device. Two tags that two devices give a show at the same
time both stay, unlike two folders.

Each device refreshes its feeds, so two devices can find the same new
episode. `refresh` adds an episode to the queue or the inbox only if the
state does not know it yet. Otherwise, a device that finds the episode late
brings back an episode that the listener played or took out on another
device. `refresh-list` follows the same rule for the shows of a followed
list.

### Provenance and compaction

Each entry carries the device that wrote it, and compaction depends on this.
An entry can be dropped only when each live device merged it. If not, an
offline device brings the entry back. For a tombstone, that brings back a
subscription that the listener removed.

Timestamps cannot tell which device saw which entry, because a device can
push late. A small version vector can. The registry entry of each device has
`:seen {other-id latest-stamp}`: for each other device, the latest stamp of
that device that this device merged. `player/acknowledge` keeps the vector
monotone, and `sync!` calls it with the whole documents that it read. Two
rules keep the vector correct:

- It ignores the registry itself. Otherwise, two devices acknowledge the
  acknowledgements of each other, and never stop writing.
- It counts only whole states, never the changes that a service sends since
  a cursor. Such changes do not show all that a device saw.

An entry from device A with the stamp `t` can be dropped when each live
device other than A acknowledged A up to `t`. The device that compacts
counts as one of them, because a document is cumulative. A document written
after `t` contains the entry, or the entry that replaced it. At least one
other device must exist. A device alone gains nothing when it forgets, and a
device that joins later can bring back what it forgot.

`player/compact` drops the tombstones that pass this test and are older
than 30 days: those of unsubscriptions, of lists no longer followed, of
removals from the queue, the playlists and the inbox, and of the tags,
stars, bookmarks and saved filters that the listener took away. The 30
days are a buffer for a device that joins with a state of its own. The
played marks and the counts of listening stay, because the listener still
sees them. `sync!` compacts at each step, unless its options tell it not
to. The app merges the result of a step into its current state, and the
merge keeps what either state holds, the removed tombstones too. For this
reason, the app compacts after the merge as well.

A device that stops syncing blocks compaction until the listener retires it,
and `player/lagging` names such devices for the user interface. A device
restored from a backup can still bring back what all other devices forgot.
To prevent this, retire the id of the device before the restore.

### What stays out

Things that belong to one device stay outside the player state, and never
sync. Examples are the downloads, the cache of titles, and the page that the
listener was on. The queue belongs to the listener and follows the listener
from device to device, as in the mainstream apps. An app that wants one
queue per device keeps it outside the state.


## The stores

`store/memory` is a map in an atom. It is the test double of the engine, and
a store for an app that keeps the documents itself.

`app.sync.file` is a folder on the JVM: a folder that a sync client mirrors,
or a Git working tree. It writes each file whole and then moves it into
place, so the sync client never sees half a file. The version of a file is
its modification time and its size.

`app.sync.webdav` is a collection on any WebDAV server, including
self-hosted servers. It uses plain HTTP: GET, PUT, DELETE, and PROPFIND at
depth one for the listing. The ETag is the version, and `If-Match` is the
precondition. The store is built like the directories: the calls are request
maps, and the tests read recorded multistatus documents. The XML parser of
podcast-clj reads these documents with the `DAV:` namespace bound to one
alias, whatever prefix the server uses. In a browser, the store sends with
`fetch`, if the server allows the origin.

`app.sync.webdav.server` is the other side: a Ring handler that serves any
store over WebDAV. The inspector mounts it at `/dav/` over its folder. As a
result, its browser player syncs from the same origin, and any WebDAV client
can mount the folder.

`app.sync.encrypted` wraps any store (see [Credentials and
privacy](#credentials-and-privacy)).

The interfaces allow other stores with no change to podcast-clj. An HTTP API
that holds files with a version, a revision or an ETag is a store. It needs
one call per operation, with a bearer token. If the API can refuse a stale
version, the store uses that as the precondition. If the API can list its
files, the store can hold one document per device. A service with its own
model is a backend, as the gpodder API is.


## The gpodder API

Most open-source podcast apps sync through the gpodder API: AntennaPod,
Kasts, gPodder and others. gpodder.net and several self-hosted servers
speak it. The API is a backend, not a store, because the server keeps a
model of its own. `app.sync.gpodder` is a client of it, and
`app.sync.gpodder.server` serves it over a state of podcast-clj.

The API keeps two things for a listener, each as changes since a time that
the server gives:

- the subscriptions, as lists of feed URLs added and removed
- the episode actions: `play` with a position and a duration in whole
  seconds, and also `new`, `download` and `delete`. Each action names a
  feed URL, the URL of the media of an episode, a device, and a time in
  UTC.

Nothing else crosses: no queue, no settings and no stars. A played mark
crosses as a play to the end of the episode, which is how AntennaPod writes
it. `new` marks an episode as not played. Downloads and deletions stay on
their device.

### A pull and a push

A pull reads what changed since its cursors, and gives it as a partial state
with `:partial? true`, so that `sync!` acknowledges nothing from it. An
action becomes the episode entry that the same act on this device makes. The
entry is written by the device that the action names, at the time that it
names. An action that is not later than the entry changes nothing. For this
reason, an action that this device sent and reads back does no harm.

A change to the subscriptions carries no time. It counts as made when the
pull before it saw none. So a change on this device since the last pull wins
over a change from the server, as in AntennaPod. At the first pull, a change
counts as made at that pull, so the first sync joins the two lists, as other
clients do.

A push sends what the devices of podcast-clj wrote since the last push, and
never what came from a server. It sends the subscriptions that were added
and removed, and one action for each episode entry that changed. The first
push adds subscriptions and removes none. The cursor keeps the stamp of each
entry at the last push, and a push sends the entries whose stamps differ.

### What the servers do

The servers differ, and the backend works with all of them:

- The GPodder Sync app for Nextcloud has other paths, and keeps only play
  actions. `:api :nextcloud` selects its paths. The paths of each API are
  data, in `gpodder/apis`, so a server with other paths takes a map of its
  paths as `:api`.
- gpodder.net keeps one list of subscriptions for each device. To share
  the list, link the devices on the site, or give the backend the id of the
  device of the other app.
- The servers select the episode actions by the time that an action names,
  not by the time that it arrived. So a server can miss an action that a
  device sends late. For this reason, a pull asks for a day of actions
  before its cursor. The merge is idempotent, so an action that comes twice
  does no harm.
- gpodder.net gives at most 1000 actions in an answer, so a pull reads them
  in pages. It takes more than 100 actions of one request in the background,
  and reports no error of them, so a push sends 100 at most. These numbers
  are the defaults of the backend, in `gpodder/default-limits`, and its
  options replace them for a server with other limits.
- Some servers want the session of a login, and others want Basic
  authentication on each request. The backend logs in at each step and
  sends both.

### Identity

gpodder names a show by its feed URL, and an episode by the URL of its
media. podcast-clj names them by guid, because URLs change. Feeds move
between hosts, schemes and slashes, and dynamic ad insertion gives a
different media URL for each request.

The backend names a show by the subscription in the state that has its URL,
whatever the scheme, the case and the trailing slashes. Failing that, it
uses the guid that the feed declares, from the feeds of the app. Failing
that, it uses the guid that the URL gives (`guid/from-url`). It names an
episode by the guid of its item. AntennaPod, Kasts and the Nextcloud app
send this guid with each action, and AntennaPod matches the actions that it
receives by it. Without a guid, the backend looks up the media URL in the
feeds of the app. `identities` makes these lookups over the parsed feeds of
the app. The feeds change, so the backend takes a function that makes them
again at each step.

An action for a feed that the app does not know yet waits in the cursor, and
the next pull tries it again. The app then has time to fetch the feed of a
new subscription.

### The server

The server is a device of podcast-clj. The app gives it a state and a step
that syncs the state with the other devices of the listener, through any
backend. The server runs the step before it tells what changed, and after it
applies what a device sent. It keeps one list of subscriptions for all
devices, as the Nextcloud app does.

The server notes when each entry arrived, and answers `since` by that time,
not by the time in an action. So an action that a device sends late still
reaches the other devices. The server keeps these times, the sessions and
the registered devices in memory. After a restart, a device reads again what
it read before, which changes nothing.

The inspector serves the API at `/api/2/` over the folder that it serves at
`/dav/`. As a result, AntennaPod, the browser player and any other device
keep one state.


## Other backends

The interfaces are sufficient for backends that nobody planned:

- A Nostr relay holds one replaceable event per device, tagged with the
  device id, and lists by author and kind. It is a store with one document
  per device.
- A Solid pod is a container of resources with ETags: WebDAV with another
  name.
- IPFS publishes the document of a device under its IPNS name.
- An IMAP folder holds one message per device, replaced at each write.
- Two devices on one network each serve their own document, and pull from
  the other device.
- A paste-and-scan exchange uses `document` as text on one side and
  `read-document` on the other: a store with `fetch` and `put` over the
  clipboard.
- A database keeps the documents as rows.
- A key-value service without a listing uses the shared strategy.

None of them needs a change to podcast-clj.


## Credentials and privacy

The constructor of a store takes its `:credentials` as a map, or as a
function without arguments that returns the current map, such as
`{:token "…"}`. Services expire their tokens, and the app refreshes them.
The OAuth flow belongs to the app, as do the Podcast Index credentials. The
engine never sees credentials, and no result, report or `:name` contains
them.

A sync document describes the habits of a listener, and a provider can read
what it stores. The encrypting wrapper, `app.sync.encrypted`, is the
extension that the map-of-functions contract was chosen for. It uses
AES-256-GCM, with a key that PBKDF2 derives from a passphrase. On the JVM it
uses `javax.crypto`, and in a browser it uses WebCrypto, which is
asynchronous. The promise convention of the stores covers this. The wrapper
works over any store, including stores that do not exist yet.

Each encrypted document is an envelope that carries the salt, the IV and the
iteration count that sealed it. There is no key file to lose, and the key is
derived once for each salt. A plain document passes through, so an app can
start to encrypt a folder at any time. A wrong passphrase makes that backend
fail, with the name of the document. With `:except`, the subscription list
that a backend writes stays readable for other apps.


## Testing

The memory store is the test double of the engine, and a store that fails on
cue is two anonymous functions. The ranks have a property test of their own:
a rank between two ranks sorts between them, and no rank ends in a zero.

A convergence property extends the merge properties to the engine. Several
devices apply random operations, and sync in random order through one store,
with either strategy. Then they do three quiet rounds: one for the entries
to reach each device, one for the acknowledgements, and one for the
compaction that follows. At the end, all states are the same. A second
property uses one document per device and compaction with no minimum age. At
its end, all tombstones are gone, and nothing that the listener sees
changed.

The folder store runs against a temporary directory. The shared strategy
runs against a memory store that answers with conflicts on cue. The WebDAV
store runs against recorded answers, PROPFIND included, with a live call in
a comment block. The WebDAV folder of the inspector is tested through
Pedestal, like the other routes. It is also a real WebDAV server for the
test of the WebDAV store in `test/inspector/`, where Pedestal is available.
The library tests stay on the minimum Clojure version. The encrypted tests
hold an envelope sealed on each platform, and open it on the other platform.

The gpodder client reads recorded answers in the shapes of gpodder.net and
the Nextcloud app. It also syncs through the gpodder server, on both
platforms, with another app that the tests play by sending actions to the
server. A stand-in with the paths and the answers of the Nextcloud app wraps
the same server. The inspector's gpodder API is tested through Pedestal,
with a device that syncs over WebDAV through the same folder.


## Decisions

Each of these choices had an alternative:

- The namespaces are under `app.sync`, next to `app.player` and
  `app.search`, not under a top-level `sync` like `directory`. What syncs is
  the state of `app.player`, and the three together describe an app.
- One document per device, merged on read, is the default strategy. One
  shared document is only for a store that cannot list.
- EDN is the only format until a client in another language needs JSON.
- Stores are maps of functions, not a protocol. The constructor of a store
  keeps its credentials in a closure.
- The inspector serves a WebDAV folder, to show a self-hosted setup.
