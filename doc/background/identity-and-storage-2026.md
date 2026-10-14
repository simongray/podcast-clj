# Feed identity and storage, October 2026

This page designs five changes to the library that [the needs
report](needs-2026.md) found, and that touch the same core: the key of a
feed, the id of an episode, which items the library keeps, how the
library is saved, and how a saved library is read back by a later
version. Built one at a time they would fight each other, so this page
decides the shapes and the order first. Nothing here is built yet; the
batches at the end say what comes first.

The five changes, in the words of the needs report:

1. The stored key of a feed survives a change of its guid.
2. An episode whose guid was rewritten keeps its state.
3. Items that a fetch no longer brings back are kept.
4. The saved library is written in parts.
5. The saved library carries a version and migrates.


## What the standards say

- **A podcast is identified once.** The `podcast:guid` tag says that a
  podcast "gets assigned a podcast:guid once in its lifetime" and that the
  guid "is meant to follow the podcast from then on, for the duration of
  its life, even if the feed url changes". A host that takes a show over
  "should" discover and import it. A feed without one gets the UUIDv5 of
  its URL, without the scheme and trailing slashes, in the podcast
  namespace.
- **An episode is identified by its guid, which never changes.** RSS 2.0
  says the guid "uniquely identifies the item" and that an aggregator may
  use it to decide whether an item is new. PSP-1 says "Each episode must
  have a unique GUID that never changes", and Apple's requirements say the
  same. Apple's guide takes the enclosure URL as the identity of an item
  without a guid, and "will ignore duplicate enclosure URLs".
- **A feed is a window on a channel.** RSS 2.0 sets no limit on the items
  of a channel, and RFC 5005 (sections 3 and 4) lets a host serve the rest
  on earlier pages or in archives, where the subscription document holds
  the newest items. Nothing says that an item that leaves the window was
  deleted.
- **A feed moves by a 301 or by `itunes:new-feed-url`.** RFC 9110 section
  15.4.2 says a client should use the new URL from then on. Neither
  mechanism says anything about the guid.
- **Other apps name things by URL.** The gpodder API names a feed by its
  URL and an episode by its media URL, and OPML by `xmlUrl`. A
  `podcast:remoteItem` names a feed by its guid and an item by its guid.

So the ecosystem has two identities for a show, the guid that is meant to
be permanent and the URL that is meant to change, and one for an episode,
the guid, with the enclosure URL as the fallback and as the name that the
sync APIs use. The design follows them: the key of a feed is a guid
assigned once, an episode is its guid with the enclosure URL as the second
identity, and the window of the feed is not the catalogue.


## Where the library stands

- The key of a feed in the profile and in the feeds map is the guid
  derived at the first fetch on a device: the declared `podcast:guid`, or
  else the UUIDv5 of the URL. The first fetch rekeys the subscription,
  whether the key came from `subscribe`, an OPML list or a sync, and
  nothing rekeys it after. Every episode id is derived again from the feed
  and its current URL at each call, so after a 301 of a feed without a
  declared guid, or a new declared guid, the ids no longer match the key,
  and the show's state is unreachable until the devices disagree and a
  merge drops one feed.
- `profile/rekey` moves the subscription, its settings and its tags, and
  nothing keyed by episode id. The CRDT has no move: a move is a write at
  the new path and a tombstone at the old.
- `feed/diff` compares items by guid alone, `new-episodes` reports every
  added item, and the refresh of the profile queues every one that it
  doesn't know.
- A fresh feed replaces the stored one; nothing passes `:max-pages` on a
  refresh; an entry without a feed is dropped by `applied` and fetched
  again as new.
- `save!` prints the profile, the feeds, the lists and the sync state into
  one library.edn with no version, and `restore!` merges whatever map it
  finds. Only the search index is saved in pieces: a segment is named
  after its contents, and the plan puts the names the store doesn't hold
  and deletes the ones the index no longer has. No store lists by prefix
  or holds sub-folders, a document's name must be a plain file name, and a
  feed's key can be any text, since `podcast:guid` is untyped.


## The decisions

### A key is assigned once

The key of a feed is assigned when the feed is first fetched for a
subscription, as today, and never derived again. It is the guid the
feed declares, or else the UUIDv5 of the URL it was fetched from. From
then on every episode id is `[key item-guid]`, and every function that
derives a feed guid from a feed and a URL takes the key instead:
`episode-id`, `item-of` and `urls` in `app.library`, `profile.queue/refresh`,
`criteria`, `downloads`, `feed.search`, the views of `app.episode` and
`app.show`, and `sync.gpodder/identities`. The views keep a `:guid` next
to the `:id`, which is what the feed says now, for the ecosystem: links,
remote items, Nostr threads and OP3 use that, not the key.

A subscription records whether its key is settled. The entry of a
subscription gains `:guid`, the guid the key was settled on. A `subscribe`
with a fetched feed writes it, one with a URL alone doesn't, and an OPML
outline or a list feed writes it when it names a guid. The first
fetch rekeys a subscription only while it has no `:guid`, so a
subscription that arrived by sync keeps the key that the other device
settled on, which closes the way two devices can disagree today. The
rekey moves everything keyed by the old guid, as writes by the device at
the time of the fetch, with a tombstone at the old path where the
collection has them: the subscription, settings, tags, listening counts,
and the few episode entries a gpodder pull may have made before the first
fetch.

When a later fetch gives another guid, the key stays. The feeds entry
records the new one under `:guid`, the subscription gets it as the feed's
author, as `relocate` writes a new URL, and the refresh reports
`:fetch/guid-changed` with the key, the old and the new guid, so that an
app can show it. The takeover check of `subscribe` and the first fetch
reads the keys and the `:guid` of every subscription and every feeds
entry, so a show can't be subscribed twice under a URL that now declares
the alias of a show already there.

Why not rekey on every change: a rekey is a write to every path of the
show on every device, it has to reach the other devices before their
refresh derives the same change, and the ecosystem's own rule is that
the guid is assigned once. Keeping the key and recording the alias costs
one entry.

### An episode keeps its state through a guid rewrite

`feed/diff` gains a second identity. An added item whose enclosure URL
matches a removed one, or else whose title and date match within a day,
is a `:renamed` pair and leaves `:added` and `:removed`. The enclosure
URL comes first, since it is Apple's fallback identity and the name of
the gpodder API, and since a host that rewrites guids rarely rewrites
media URLs at the same time; the title and date catch the host that
does, e.g. one that adds an OP3 prefix in the same migration.

A rename moves the episode's state. A new `profile/rename-episode` writes
the entries of the old id under the new id in every collection keyed by
episode id, the queue and the playlists with their ranks, and a tombstone
at the old path where the collection compacts; the progress and played
entries, which never compact, keep their old entries as they are. The
writes are the device's own, at the time of the refresh, not the feed
author's, so that the listener's progress on the renamed episode is
pushed to a gpodder server as theirs. Devices converge without
coordination: every device computes the same pairs from the same two
parses, and a device that first sees the feed after the rewrite has no
old parse, computes nothing, and takes the moved entries from the sync.
The refresh result lists `:renamed`.

In the same batch, what counts as new is bounded. The function
`new-episodes` reports as `:added` only the items dated after the newest
item of the old parse, and the rest, dated items that are older, as
`:backfilled`. The refresh of the profile queues and inboxes only the
added ones, and the refresh
result reports both. An item without a date counts as new. So a rewrite
that the second identity misses, or a host that republishes a back
catalogue, can't append hundreds of episodes to the queue, and a config
key turns the rule off for an app that wants every item queued.

### Items outlive the feed

A feeds entry keeps the items that a fetch no longer brings back. After a
fetch, the items of the fresh feed come first, in the feed's order, and
then every stored item that the fresh feed lacks, in their stored order.
The entry's `:retained` map holds a fact for each kept item, by item
guid: when it was last seen in a fetch, and when the host removed it,
when it did. An item is removed by the host when its date lies inside the
window of the fresh feed, between its oldest and its newest item, or when
it has no date, and it leaves the retained map when a fetch brings it
back. An item older than the window has merely aged out. So the fresh
feed stays what the host wrote, the back catalogue stays reachable, a
downloaded episode isn't trimmed because the host capped the feed, and a
removal is a fact an app can show or hide.

A fresh feed with no items, while the entry has items, isn't applied. It
is reported as `:fetch/empty-feed` and scheduled like a failed fetch, so
the outage pages that hosts serve can't wipe a show, and the download plan
doesn't delete its files. A fresh feed with fewer items needs no guard,
since the missing ones are retained anyway.

The first fetch of a subscription takes `:max-pages` from the config, so
that the pages of an RFC 5005 feed read at subscription are kept, and a
subscription that arrived by sync gets the same catalogue on its first
fetch. The default stays one page. A config key `:max-items` caps what an
entry keeps; without it everything is kept, as the mainstream apps do.
The cap drops retained items only, oldest first, and never one that the
profile has an entry for: progress, a played mark, a star, a bookmark, a
clip, a rating, a place in the queue or a playlist, or a remote item.
The slim form keeps the retained items too, so a server names every
episode a gpodder app may report.

What stays per device: the retained items. The profile carries nothing
of them, as it carries nothing of the downloads, so a second device sees
the catalogue only when the host serves it, on the first page or on the
pages that `:max-pages` reads. The diff of a refresh is taken against the
merged list, so an item that returns after a removal isn't new.

### The library is saved in parts

library.edn keeps the profile, the lists, the sync state and the feeds
map without the feeds: each entry keeps its URL, guid, validators, next
check, failures, status, and the name of its document. Each feed goes to
a document of its own with the retained facts, named after the feed and
the time it last changed. The entry gains `:changed-at`, set only when
the feed or the retained items change, since `:fetched-at` moves on a 304
and a failure too. The name is `feed-<id>-<changed-at>.edn`, with the id
the key when the key is a UUID, and else the UUIDv5 of the key in the
podcast namespace, since a key can be any text and a name must be a plain
file name in every store.

The plan of a save mirrors the index. A feed's document is put when the
store's listing doesn't hold its name, and the documents of feeds that
the library no longer has, or that changed, are deleted. A store without
a listing gets every document on every save, as it gets every segment.
library.edn is put last, as today, so that a crash leaves the old library
pointing at documents that still exist. A restore reads library.edn and
then the document of every entry. An entry whose document is missing or
unreadable loses its validators and is fetched again at the next refresh,
as an entry with only its URL is today, with
`:library/unreadable-document` in the report.

The documents prepare the second step, feeds on demand, which this page
decides but a later batch builds: an entry may hold its metadata and no
`:feed`. The pending check already treats such an entry as fetched,
`applied` stops dropping it, `merge` and the index skip it, and the views
give nil for its episodes with a mark that it isn't loaded. A restore
takes which feeds to load, all by default, and `load!` and `unload` move
feeds between the store and the library, so that a phone keeps the feeds
of the current screen in memory and a server keeps none. A refresh loads
the entry it fetches when the config names the store, since the merge of
the items needs the stored ones.

### The saved library has a version

library.edn carries `:version`, which is 2 for the shape above, and a
document without one is version 1. A restore runs the migrations from
the document's version up to its own, each a pure function of the saved
map, before merging the parts. The first migration keeps the feeds that
version 1 holds inline and lets the next save write them out as
documents; the profile keeps migrating lazily, as `profile/migrated` does
today. A document of a newer version isn't merged: `restore!` gives the
library as `make` made it, with `:library/newer-format` in the report, and
`save!` refuses to write over the newer document while that diagnostic
stands, so that an older app can't destroy what a newer one saved. The
sync documents keep their own version in like-minded, and the index
manifest its own in drop-in-search.


## The shapes

The subscription entry of the profile, with the settled guid:

    {:url "https://…" :title "…" :added-at #inst "…" :guid "917393e3-…"}

A feeds entry, with the items of the fresh feed first and the retained
items after them:

    {:url        "https://…"
     :guid       "917393e3-…"          ; what the feed says now
     :feed       {… :rss/items […]}    ; fresh items, then retained ones
     :retained   {"item-guid" {:last-seen #inst "…" :gone #inst "…"}}
     :changed-at #inst "…"             ; when the feed or the items changed
     :fetched-at #inst "…" :next-check #inst "…" :failures 0 :status 200
     :etag "…" :last-modified "…"
     :document   "feed-917393e3-…-1760140800000.edn"}

The documents of a saved library:

    library.edn                        ; {:version 2 :profile … :feeds …}
                                       ; with each entry without its :feed
    feed-917393e3-…-1760140800000.edn  ; {:feed … :retained …}
    segment-4ab2f753….ciff, index.edn  ; the search index, as today

A diff, and the refresh result:

    {:added   […] :removed […] :changed […]
     :renamed [{:before … :after …}] …}

    {:at         #inst "…"
     :added      [id …]
     :backfilled [id …]
     :renamed    [[old new] …]
     :moved      {key "https://…"}
     :report     […]}

New diagnostics: `:fetch/guid-changed`, `:fetch/empty-feed` and
`:library/newer-format`.


## The batches

1. **The version and the documents.** `save!`, `restore!`, the
   migrations and the plan of the documents, with `:changed-at` and
   `:document` on the entries. No API changes, and every later change of
   a shape ships with a migration. A few days.
2. **The key.** `:guid` on the subscription and the feeds entry, the
   rekey that moves everything but only an unsettled key, the takeover
   check over aliases, `:fetch/guid-changed`, and the seven namespaces
   that take the key instead of deriving it. Tests: a 301 of a feed
   without a declared guid, a feed that gains a guid, and two devices
   that fetch the same new subscription, in the convergence test. A few
   days.
3. **Retention.** The merge of the items, the retained facts, the empty
   guard, `:max-pages` on the first fetch, `:max-items` with the profile
   check, `slim`, and the mark in the episode view. Tests on a corpus
   feed refreshed with fewer items, with an empty answer, and with pages.
   About three days.
4. **Renamed episodes and the bound on new ones.** `:renamed` in the diff,
   `rename-episode`, `:backfilled`, and the config key that turns the bound
   off. Tests on a corpus feed with rewritten guids, and a two-device
   scenario. A few days.
5. **Feeds on demand.** Entries without a feed, `load!` and `unload`, the
   store in the config, and the server keeping no feeds in memory. A week
   or two, when a library with hundreds of feeds exists to measure it on.

The order: the version first, because batches 2 to 4 change what is
saved; the key before retention, because the retained items are keyed
under it; renames after retention, because a renamed item may be a
retained one; and on-demand loading last, because it changes the views
and gains the most from the measurements that the earlier batches make
possible.


## Left open

These are choices of the design rather than of a spec, made here with a
default that a config key can change:

- Removed items are listed by default, with their mark, as AntennaPod and
  Pocket Casts do; Apple hides them.
- The bound on new episodes is on by default.
- No cap on retained items by default, and one page on the first fetch.
- A rename matches by enclosure URL first, then by title and date within
  a day.


## Sources

- The `podcast:guid` tag:
  <https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md>
- RSS 2.0, `<guid>` and `<enclosure>`:
  <https://www.rssboard.org/rss-specification>
- PSP-1, required `<item>` elements and `<podcast:guid>`:
  <https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification>
- Apple Podcasts, feed requirements:
  <https://podcasters.apple.com/support/823-podcast-requirements>
- RFC 5005, feed paging and archiving: <https://www.rfc-editor.org/rfc/rfc5005>
- RFC 9110 section 15.4.2, 301 Moved Permanently:
  <https://www.rfc-editor.org/rfc/rfc9110#section-15.4.2>
- The gpodder API, episode actions:
  <https://gpoddernet.readthedocs.io/en/latest/api/reference/events.html>
