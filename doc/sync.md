# Syncing

This page explains how a listener's devices stay in step, and why it's
safe. [Sync reference](sync-reference.md) has the contracts and the
formats, and [How to build a podcast
app](building-an-app.md#sync-the-devices-of-a-listener) shows the calls.


## How devices merge

Each device keeps the listener's profile: their subscriptions, queue,
progress, stars and the rest. Each change in it is stamped with its time
and the device that made it, and a removal stays as a marker, a
tombstone, so that an older addition from another device can't bring the
item back. To sync, a device merges the profiles of the others into its
own, entry by entry, and where two devices changed the same entry, the
later change wins.

For example, the phone and the laptop are both offline. On the phone, the
listener stars an episode and listens to it up to 30:00. Later, on the
laptop, they unsubscribe from another show and rewind the episode to
2:00. Once both have synced, both have the star, the unsubscription and
the position 2:00, since the rewind came last.

The merge gives the same result in any order, any number of times, which
a generative test checks. In CRDT terms, it's commutative, associative and
idempotent, so it's a join, and the profile is a CRDT. Nobody has to
resolve a conflict, so the backend, the place that the devices sync
through, only has to store documents. Three kinds of backend are enough:

- **One document per device.** Each device writes only its own document and
  reads the documents of the others, so no two devices ever write the same
  file. A folder that a sync client such as Dropbox mirrors, a Git working
  tree or a WebDAV collection is a complete backend, and a new device gets
  everything by reading it. This is the default.
- **One shared document.** A store that can't list its documents, e.g. a
  key-value store, holds a single document. A device fetches it, merges,
  and writes it back only if its version hasn't changed. On a conflict, it
  fetches and merges again, which is safe since a second merge changes
  nothing.
- **A service with its own model.** Services like gpodder merge on the
  server and report the changes since a cursor. The backend translates
  in both directions: a push sends the changes since the last push as the
  service's own actions, and a pull turns what the service reports into a
  partial state that merges like any other.

Two rules hold for all of them. The local state is only ever merged into,
never replaced, so a failed pull changes nothing and a failed push is
simply repeated. A device can also sync with several backends at once,
merging what it reads from all of them, which lets a listener keep a folder
and a server in step, or move from one store to another.


## Backends and stores

There are two levels. A backend is what the engine calls: `pull` returns
the states to merge and a cursor, and `push` writes the merged state. A
store is the common case below a backend, a set of named documents with
version tokens. Any store becomes a backend through `sync/backend`, which
adds the strategy, the retries and the reports, so a new store only needs
two to four functions.

A refused write is a value, not an exception, since it's a normal outcome
of the shared strategy. Errors of the network or a service are exceptions,
and the engine turns each one into a `:sync/backend-failed` diagnostic in
the report.

A store is a map of functions rather than a protocol or a multimethod, for
four reasons:

- Wrapping a store means updating one of its keys, so an encrypting,
  caching or logging store is a few lines over any other store, and a store
  that fails on cue in a test is two anonymous functions.
- The optional functions are optional keys, with no `satisfies?` checks.
- The credentials stay in the constructor's closure, so a store never
  prints a token, as a record with a token field would.
- The constructor's argument is plain EDN that an app can save, and the
  store is the value it uses.


## The engine

The engine in `sync` is pure, and all I/O happens in the backends. A
document that doesn't read is reported and left out. So is a state with a
value that the merge can't handle, such as a stamp that isn't an instant,
since the merge would otherwise spread it to every device. When this
device's own state holds such a value, e.g. after an `assoc`, the step
writes nothing and reports it, since the other devices would leave out
the whole document. A document from a newer version of podcast-clj is
left out too, because this version can't understand all of it.

The engine schedules nothing. Call `sync!` when the app starts, when it
goes to the background, after a series of changes, and when a file watcher
or the service says that something changed. For when to sync next, ask
`sync/next-sync`, or `library/next-sync` for a library. Only run one step at a
time, since a step that overlaps another can write an older state after a
newer one. A file watcher also sees the writes of the step itself, so let
such a call wait until the step ends.

For control over the I/O, e.g. in an iOS background task with a time
budget, do the steps yourself: list, fetch, `reconcile` and put.


## The documents

The documents of one listener live under one root, such as a folder or a
WebDAV collection, and the layout doesn't depend on the app. Two apps
built on podcast-clj can share a root, since the state belongs to
podcast-clj rather than to either app.

The format is EDN, which needs no new dependency and holds the state as it
is. JSON can come later as a defined mapping for clients in other
languages. The episode ids are vectors, which JSON can't use as keys, so
that mapping will have to list them. Transit would carry EDN over JSON, but
it's a dependency with no use yet.

The format version only changes when the meaning of an existing key
changes. A reader merges keys it doesn't know by the shape of their values,
so podcast-clj can grow the state, and an app can add entries of its own,
without a new version.


## The profile as a CRDT

The CRDT is `util.crdt`, which knows nothing of podcasts. The `profile`
namespace gives it a schema: how deep the entries of each collection are, which
entries hold counts that only grow, and which tombstones compact drops.

### Merge by shape

The merge goes through the two states key by key, and handles each value
by its shape:

- a stamped entry: the later of the two
- a map of other values: key by key, down to the stamped entries
- an instant: the later of the two
- anything else: this device's value

A stamped entry is a last-writer-wins register, a map of stamped entries is
a set with tombstones for its removals, and the clock is a max register, so
the state is their product and the merge is its join. When two entries have
the same stamp, the device id decides, and then the printed entry, so every
platform makes the same choice. Entries that an app adds to the state sync
with no change to podcast-clj, as long as `crdt/write` stamps them. What
another device or a service did at a time of its own goes in with
`crdt/as-device`, which keeps a later entry, as a merge would. A value
outside a stamped entry would never agree across devices, so the sync
leaves out a document that holds one.

### Devices

Each state belongs to a device, with a stable id made once per
installation. The registry under `:devices` holds the description of each
device, which only that device writes. Retiring a device is a tombstone of
its own, so the retired device can't undo it by describing itself again,
and the other devices delete its document. The registry has no last-synced
time on purpose: it would change every document at every step, and the
devices would never stop writing.

### The clock

Last-writer-wins trusts the clocks of the devices. The state keeps
`:clock`, the latest stamp it wrote or merged, and a write whose time isn't
later than the clock is stamped a millisecond after it, as a hybrid logical
clock does. A convergence test showed why this is needed: without it, a
device could write an entry that lost to one it had already merged, keep
its own anyway, and never agree with the other devices again. An OPML
import or a followed list writes all its entries with one stamp, or the
millisecond steps would move the clock ahead of the time, and you can do
the same for a series of your own writes with `crdt/at-once`. A full hybrid
logical clock, with a counter in each stamp, would avoid the steps
altogether, but it changes the format of every stamp for little practical
gain.

A clock that's a few seconds off does no harm. A clock that's years ahead
spreads its year to every device through the merge, so the stamps no longer
say when things happened, and tombstones are kept longer. Nothing is lost,
and the states still agree. Refusing such a state wouldn't help, because
the device couldn't sync and its own state would keep the future stamps
even after its clock was fixed. So the sync only warns: `:sync/clock-skew`
names a device that wrote its document more than a minute ahead of this
device's clock.

### The queue

The queue has one entry per episode, ordered by a rank from `util.rank`, a
string with room for another rank between any two, as `util.crdt.list`
keeps it. A removal is a
tombstone. Additions on two devices both survive, a finished episode stays
removed, and only two devices that move the same episode at the same time
need a winner, the later one. A move writes the episode's new place as an
entry of its own, so it never brings back an episode that another device
removed meanwhile. Ranks stay short at the ends of the list,
where most episodes are added. Episodes that one device appends at once
share a block of ranks named after the device, so batches that two devices
append at the same time don't mix. Playlists work the same way.

### Listening counts

The seconds heard are counted per show, month and device. Each count
belongs to one device and only grows, so it's a counter rather than a
register: the merge keeps the higher of two counts from the same device,
and the total is the sum over the devices. A single shared count would lose
seconds when two devices count at the same time. Counting by month rather
than by day keeps the document small.

### Settings, marks and new episodes

The preferences and the settings of a subscription have one entry per
field, so a skip amount changed on one device and a playback rate changed
on another both survive. The settings of a subscription are kept apart from
it, so they survive an unsubscription. The folder is one of these settings,
so a show that two devices move at the same time ends up in the later
folder, never in two. Likewise, an episode's position and its played mark
are separate entries, so a position from a device that hasn't seen the mark
doesn't undo it.

The inbox, stars, bookmarks, clips, saved filters, tags and followed lists
are sets with tombstones, like the subscriptions, so two tags added on two
devices both stay. A rating keeps its scale, since two apps of the same
listener can use different scales.

Every device refreshes its own feeds, so two devices can find the same new
episode. That's why `refresh` only adds an episode to the queue or the
inbox when the state doesn't know it yet. Followed lists work the same way
for shows, which they recognise by feed URL.

A device that refreshes before it syncs doesn't know yet what the listener
did on the others. So the feed is the author of what `refresh` adds, at
the episode's date of publication, and whatever the listener did with the
episode came later and wins. The move of a feed to a new URL is written
the same way, just after the last write to its subscription, so it never
undoes an unsubscription from another device. Once a sync brings an
unsubscription, `library/merge` drops the show's feed, so the device stops
fetching it.

### Compaction

Tombstones are dropped once every device has seen them. Timestamps can't
tell which device saw what, since a device can push late, so the registry
entry of each device has a small version vector, `:seen`: for every other
device, the latest of its stamps that this device merged. A sync updates it
from the whole documents it reads, with two rules. It ignores the registry
itself, or two devices would keep acknowledging each other's
acknowledgements. It never counts the partial changes that a service
sends, since those don't show everything a device saw.

An entry that device A wrote at time `t` can be dropped when every other
live device has acknowledged A up to `t`. The device that compacts counts
too, since its document holds the entry or the entry that replaced it.
This relies on A's entries reaching each device in the order of their
stamps, which holds for the devices in the registry. The entries that a
service such as gpodder brings in can arrive in any order, so their
tombstones stay.
Such tombstones are dropped once they're 30 days old, which leaves room for
a device that joins with a state of its own. Played marks and listening
counts are never dropped, since the listener still sees them. After a
sync, the result is merged into the current state, which brings back the
dropped tombstones, so `library/merge` compacts again afterwards.

While the listener follows a list, the tombstones of unsubscriptions stay.
A feed never gives a new device old episodes, but a list subscribes to
every show that the state doesn't know, so a new device needs the tombstone
to know that the listener left a show.

A device that stops syncing blocks compaction until it's retired, and
`crdt/lagging` lists such devices. A profile restored from a backup can
bring back what the others forgot, so rather than restore one, make a new
library with a new device id, which gets everything from the other
devices at its first sync, and retire the old id.

### What stays out

Anything that belongs to one device stays out of the profile and never
syncs, e.g. the downloads, the sleep timer and the page that the
listener was on. The queue belongs to the listener and follows them
between devices, as in the mainstream apps. If you want a queue per device,
keep it outside the profile.


## The stores

[Sync reference](sync-reference.md#the-stores-of-podcast-clj) lists the
stores that come with podcast-clj: in memory, a folder, a WebDAV collection,
and any of them encrypted. Any place that can hold one document per device,
or one document with a version, can be a store without a change to
podcast-clj.


## The gpodder API

AntennaPod, Kasts, gPodder and most other open-source podcast apps sync
through the gpodder API, which gpodder.net and several self-hosted servers
speak. It's a backend rather than a store, because the server keeps a model
of its own. The client is `sync.gpodder`, and `sync.gpodder.server`
serves the API over a profile.

Only subscriptions and episode actions cross. A played mark is sent as a
play to the end, as AntennaPod writes it, and the action `new` marks an
episode as unplayed. The queue, the settings, the stars and the rest stay
in podcast-clj. [Sync reference](sync-reference.md#the-gpodder-backend)
has the full mapping.

### Pull and push

A pull turns the actions since its cursor into a partial state, which the
sync merges but doesn't acknowledge. Each action becomes the entry that the
same act would make on this device, stamped at the time of the action and
written by `gpodder:` and the id of the device on the server, e.g.
`gpodder:antennapod`. An action that isn't later than the entry changes
nothing, so reading back an action that this device sent does no harm.

The prefix matters, because a device on the server can have the same id as
a device of podcast-clj. Its entries would then look like that device's own
writes, so a push would send them back, and compaction would drop
tombstones too soon, since the server only reports part of what a device
wrote.

An action can name a time in the future, because the clock of its device
is fast, or because an app wrote milliseconds where the API has seconds.
Such a time must not become a stamp, or every device's clock would jump
ahead. An action more than a minute in the future changes nothing until a
later pull reads it again after its time has come, and
`:sync/future-actions` names its device. For the same reason, a push
doesn't send a stamp as the time of an action, but the time on this
device's clock when the listener acted, which the episode entry keeps too.

A push only sends what devices of podcast-clj wrote since the last push,
never what came from a server. A change to the subscriptions carries no
time, so it counts as made when the pull before it saw none. A change on
this device since the last pull wins over a change from the server, as in
AntennaPod, and the first sync joins the two lists.

The servers select actions by the time they name, not by when they
arrived, so a late action could be missed. A pull therefore asks for a day
of actions before its cursor. The merge is idempotent, so an action that
comes twice does no harm.

### Identity

The gpodder API names a show by its feed URL and an episode by its media
URL, but URLs change: feeds move between hosts, schemes and slashes, and
dynamic ad insertion gives a new media URL for every request. The profile
names them by guid, so the backend maps between the two.

A show is found by its URL among the subscriptions, whatever the scheme,
case and trailing slashes, then by the guid that its feed declares, and then
by the guid derived from the URL. An episode is found by the guid of its
item, which AntennaPod, Kasts and the Nextcloud app send with each action,
and otherwise by its media URL in your parsed feeds, through `identities`.
An action for a feed that isn't fetched yet waits in the cursor until the
next pull.

### The server

The server is a device of podcast-clj itself, with one list of
subscriptions for all the devices that use it, as the Nextcloud app has.
It serves one listener, so every user that it lets in shares the profile.
It answers `since` by when each entry arrived rather than by the time in
the action, so an action sent late still reaches the other devices. It keeps
its sessions and arrival times in memory, and after a restart, a device
reads again what it read before, which changes nothing.


## Credentials and privacy

The constructor of a store takes `:credentials` as a map, or as a function
that returns the current map, so that you can refresh tokens that expire.
OAuth flows are up to you. The engine never sees the credentials, and no
result, report or store name contains them.

A sync document describes the listening habits of a listener, and the
provider can read what it stores. The encrypting store, `sync.encrypted`,
wraps any other store, which is what the map-of-functions design was chosen
for. It uses AES-256-GCM with a key that PBKDF2 derives from a passphrase,
through `javax.crypto` on the JVM and WebCrypto in a browser. Each
document carries the salt, the IV and the iteration count that sealed it,
so there's no key file to lose. Plain documents pass through, so you can
start encrypting a folder at any time.


## Testing

The merge properties have a counterpart for the whole engine. Several
devices apply random operations and sync in random order through one
store, with either strategy, followed by three quiet rounds: one for the
entries to reach every device, one for the acknowledgements, and one for
the compaction that follows. At the end, every state is the same. A second
property checks that compaction drops every tombstone that it may, and
changes nothing that the listener sees.
