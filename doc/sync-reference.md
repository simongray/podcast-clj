# Sync reference

This page has the options and the formats that podcast-clj adds to the
sync of [like-minded](https://github.com/simongray/like-minded). Its
[reference](https://github.com/simongray/like-minded/blob/master/doc/reference.md)
has the contracts of a store and a backend, the strategies, the result
of a step, the documents and the stores, and [Syncing](sync.md) explains
the design.


## The options of the profile

A profile syncs as a state of like-minded with the options of
`profile/sync-options`:

| Option | Value |
|---|---|
| `:crdt` | `profile/merge`, `profile/compact` and `profile/problem`, which keep the rules of [mind-meld](https://github.com/simongray/mind-meld) |
| `:version` | 2 |
| `:kind` | `"podcast-clj/profile"` |
| `:document` | `"podcast.edn"`, the name of a shared document |

- In a library, `library/sync!`, `library/leave!` and `library/document`
  give these options to like-minded, and so does a backend of
  `library/backend`.
- To sync a profile without a library, or to read the document of one,
  give `profile/sync-options` to like-minded, e.g.
  `(like-minded/sync! profile backends profile/sync-options)` or
  `(document/decode body profile/sync-options)`.
- To sync another state than the profile, call like-minded with the
  options of that state.

With `:opml?`, a backend of `library/backend` also writes the listener's
subscription list beside the documents as `subscriptions.opml`, for any
podcast app to import. It holds what `profile.opml/to-opml` gives: the shows
with their folders and tags, and the followed lists. It's an extra
document of like-minded, so it's written when it changes. To leave it
readable in an encrypted store, give its name, `library/opml-name`, in the
`:except` of `encrypted/store`.


## The document of a profile

A document of a profile has the kind `podcast-clj/profile`, and the whole
profile of its device as its `:state`.

- The profile is the map that the docstring of
  `dk.simongray.podcast.profile` describes. It's a CRDT, so
  `profile/merge` combines the profiles of any devices in any order, as
  [The profile as a CRDT](sync.md#the-profile-as-a-crdt) explains.
- A document takes a few hundred bytes per subscription, and about two
  hundred bytes per episode entry.


## A sync of a library

A sync of a library keeps the step under `:sync`, without its `:state`,
which went into the profile, and with the `:at`, `:clock` and `:failures`
that `library/next-sync` reads. Merge the result into the library as it
is by then with `library/merge`. It merges the profiles with
`profile/merge` and then compacts with `profile/compact`, since the merge
brings back the tombstones that the step dropped. The `:compact` option of
a sync is false to keep every tombstone, or else the options of
`profile/compact`.


## The gpodder backend

The backend that `gpodder/backend` makes speaks the gpodder API on both
platforms, and takes these options:

| Option | Meaning | Default |
|---|---|---|
| `:credentials` | `{:username :password}`, or a function that returns them | none |
| `:api` | `:gpodder` for the paths of gpodder.net under `/api/2`, `:nextcloud` for those of the GPodder Sync app, or a map of the paths of another server. [Services and their defaults](services.md#the-gpodder-api) has the paths. | `:gpodder` |
| `:device` | The id of the device on the server | the device of the state |
| `:caption`, `:type` | What the server shows for the device. The type is desktop, laptop, mobile, server, tablet or other. | `"podcast-clj"`, `"other"` |
| `:identities-fn` | A function of no arguments that returns the lookups that `identities` makes from the app's feeds | none |
| `:feed-guid-fn`, `:feed-url-fn`, `:episode-id-fn`, `:episode-url-fn` | Each of those lookups on its own | none |
| `:name` | The name of the backend | the domain of the server |
| `:page-size`, `:max-pages`, `:batch-size`, `:max-pending` | The limits of the server and of the cursor, see [Services and their defaults](services.md#the-gpodder-api) | 1000, 10, 100 and 500 |
| `:timeout-seconds` | How long each request can take | 30 |
| `:user-agent` | The User-Agent of each request | that of podcast-clj |
| `:send` | A function that sends a request map, in place of `client/send!` | `client/send!` |

What crosses, in each direction:

| podcast-clj | gpodder API |
|---|---|
| A subscription, and its removal | The feed URL, added or removed, without any user name and password it holds |
| The position of an episode | `play`, with `started` and `position` at the position, and `total` at the duration, in whole seconds |
| A played episode | `play` to the end: `started`, `position` and `total` at the duration |
| An episode marked as unplayed | `new` |
| Nothing | `download` and `delete`, which a pull ignores |

The cursor keeps the time of each list of changes at the server, the
device that registered, the stamps of the entries at the last push, the
URLs that the server rewrote, and the last `:max-pending` actions for feeds
that the app doesn't know yet. The backend reports:

- `:gpodder/unknown-episodes` when such actions are waiting
- `:gpodder/future-actions` for the actions of a device that name a time more
  than a minute after the pull, which change nothing
- `:gpodder/malformed-actions` for actions without a feed, a media file or a
  readable time
- `:gpodder/unnamed-episodes` for episodes that a push couldn't name by a
  media URL
- `:gpodder/rejected-url` for a URL that the server refused

An entry from a server has `gpodder:` and the id of the server's device as
its `:device`, e.g. `gpodder:antennapod`. Some servers want the session of
a login and others Basic authentication on every request, so the backend
sends both.

On both platforms, `gpodder.server/handler` serves the API as a
Ring handler under `/api/2`: the login, the devices, the subscription
changes and the episode actions. It takes the atom of the server's state,
`:authorized-pred`, a `:sync` function that syncs the state, e.g. one of
`gpodder.server/stepper`, and the same lookups as the backend. The `:sync`
function returns a state, not a promise, so in ClojureScript, where
like-minded's `sync!` returns a promise, sync separately and merge the
result into the atom.


## gpodder servers, as of October 2026

The root of a gpodder server is a fact about another product that can
change, so podcast-clj contains none of them.

| gpodder server | The root to give `gpodder/backend` | `:api` | Notes |
|---|---|---|---|
| gpodder.net | `https://gpodder.net` | `:gpodder` | Each device has its own subscriptions. To share them, link the devices on the site, or give the device id of the other app as `:device`. Since a notice of 6 September 2026, it's a sync service and little else: an account may be archived after six months without use, and the older episode actions may be cut to the latest of each episode on each device, so a pull may not give them all. |
| Nextcloud with the GPodder Sync app | `https://HOST` | `:nextcloud` | An app password, as for WebDAV. The app only keeps play actions, so an episode marked as unplayed doesn't reach it. |
| oPodSync | The URL that it's installed at | `:gpodder` | |
| PinePods | `https://HOST` | `:gpodder` | |
| gpodder2go | `https://HOST` | `:gpodder` | It syncs the subscriptions, but not the episode actions. |
| The inspector | `http://localhost:8300` | `:gpodder` | Any user name and password |

The inspector also serves a WebDAV collection at
`http://localhost:8300/dav/`, with no credentials. For the URLs of the
common WebDAV servers, the folders of the sync clients and what each
browser can do with a folder, see like-minded's
[Providers](https://github.com/simongray/like-minded/blob/master/doc/providers.md).
