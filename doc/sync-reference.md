# Sync reference

This page has the contracts and the formats of the sync between devices.
[How to build a podcast
app](building-an-app.md#sync-the-devices-of-a-listener) shows the calls,
and [Syncing](sync.md) explains the design.


## The store contract

A store is a map of functions over named documents, each with a version
token:

```clojure
{:fetch  (fn [name])            ; {:body "…" :version "…"}, or nil
 :put    (fn [name body opts])  ; {:version "…"}, or {:conflict? true}
 :list   (fn [])                ; [{:name "…" :version "…"} …], optional
 :delete (fn [name])            ; optional
 :name   "Nextcloud"}           ; for the UI and the report
```

- A put refuses the write when `opts` has an `:if-version` that isn't the
  current version of the document, and an `:if-version` of nil means that
  the document mustn't exist yet. The check and the write happen as one
  step, so of two puts with the same `:if-version`, only one succeeds. The
  folder store guarantees this within one process.
- A refused write returns `{:conflict? true}` rather than throwing.
- On the JVM, each function returns its value and throws on failure. In
  ClojureScript, it can return a value or a promise.
- The credentials stay in the closure of the constructor, never in the
  map, and `:name` mustn't contain them.
- A store without `:list` can't hold one document per device, so it holds
  one shared document.

To test a store against this contract, use `store/check!`. It writes a
probe document, and for a store without `:list`, it checks that a stale
version is refused, as one shared document needs. A store without
`:delete` keeps the probe document.

Each store namespace has two constructors: `store` makes the store over a
place, and `backend` makes the store and the backend in one call, with the
options of both.


## The backend contract

A backend is a map of two functions and a name, which `sync!` calls:

```clojure
{:pull (fn [state ctx at]) ; {:states […] :ctx ctx' :report […] :partial? false}
 :push (fn [state ctx at]) ; {:ctx ctx' :state state' :report […]}
 :name "gpodder.net"}
```

- The pull returns the states to merge and the cursor for the next step.
  With `:partial? true`, the states are only the changes since a cursor, so
  `sync!` doesn't acknowledge them.
- The push writes the merged state and returns the cursor. Its `:state`
  and `:report` are optional, and it returns a `:state` when it learned
  more while it wrote.
- The `at` is the instant of the step, so the functions never read a
  clock.
- The backends of one `sync!` need different names, since the cursors are
  kept by name, and `sync!` throws on a duplicate.

Any store becomes a backend through `store/backend`. A service with its
own model, such as gpodder, is a backend directly.


## The options of a backend

The options of `store/backend`, and of the `backend` function of each store
namespace:

| Option | Meaning | Default |
|---|---|---|
| `:strategy` | `:per-device` or `:shared` | `:per-device` if the store has `:list`, else `:shared` |
| `:direction` | `:both`, `:pull` (read only) or `:push` (write only) | `:both` |
| `:opml?` | Write `subscriptions.opml` next to the documents | `false` |
| `:app` | The name of the app, written into each document | none |
| `:name` | The name of the backend | the name of the store |
| `:document` | The name of the shared document | `"podcast.edn"` |

With either strategy, a retired device writes nothing, and the report has
`:sync/retired-device`.


## The per-device strategy

Each device writes only its own document, `<device-id>.edn`. A step of
`sync!` with this strategy does the following:

1. List the store, and fetch every document whose version differs from the
   one in the cursor, all at once. Only names that end in `.edn` are sync
   documents, and a document without a version in the listing is always
   fetched.
2. Read each document, and report any that doesn't read.
3. Merge the states into the local state.
4. If the merged state differs from what this device wrote last, put the
   document of this device. It needs no precondition, since no other device
   writes it.
5. With `:opml?`, put `subscriptions.opml` if the subscription list changed.
   The list is what `player/to-opml` gives: the shows with their folders and
   tags, and the followed lists.
6. Delete the documents of retired devices.

If the document of this device changed since the last step, another
installation is using the same device id, and the report has
`:sync/shared-device`. If the document is gone from the store, the next
push writes it again.


## The shared strategy

All devices write one document, `podcast.edn` by default. A step of
`sync!` with this strategy does the following:

1. Fetch the document. If its version is the one in the cursor, there's
   nothing new to merge.
2. Merge it into the local state.
3. If the merged state differs from what this device put last, put it with
   `:if-version` set to the version in the cursor.
4. If the store refuses the put, fetch, merge and put again. After three
   retries, stop and report `:sync/conflict`.
5. Unless step 4 stopped, put `subscriptions.opml` with `:opml?` if the
   subscription list changed.

If the document is gone from the store, the next push writes it again. A
document in a newer format is left alone, since this device can't read all
of it and a put would lose data.


## The result of `sync!`

```clojure
{:state  merged                ; the local state with the pulled states merged
 :ctx    {"Nextcloud" {…} …}   ; the cursors by backend name
 :failed {"Dropbox" "…"}       ; the backends that failed, with the message
 :report [...]}                ; the diagnostics of the step
```

- There's no `:failed` when no backend failed.
- If the pull of a backend fails, `sync!` doesn't push to it, and its
  cursor stays the same. If a push fails, the backend keeps the cursor of
  its pull.
- Run one step at a time, since a step that overlaps another can write an
  older state after a newer one.
- The `:state` doesn't include what the listener did during the step.
  Merge it into the current state with `player/merge`, then compact with
  `player/compact`, since the merge brings back the tombstones that the
  step removed. The function `library/sync!` does both.
- On the JVM, `sync!` returns the map, and in ClojureScript a promise of
  it.
- Its options are `:ctx`, the cursors of the last step, and `:compact`,
  which is `false` to keep every tombstone, or the options of
  `player/compact`.


## The sync document

A sync document is an EDN map:

```clojure
{:version    1                     ; the version of the format
 :device     "8f1c9e2a-…"          ; the id of the device that wrote it
 :written-at #inst "2026-09-26T…"  ; when, by the clock of that device
 :app        "my-app"              ; the app that wrote it, optional
 :state      {…}}                  ; the whole player state of that device
```

- The player state is the map that the docstring of
  `dk.simongray.podcast.app.player` describes.
- The version only changes when the meaning of an existing key changes,
  not for new keys.
- A reader leaves out a document with a newer version, and reports
  `:sync/newer-format`.
- A document takes a few hundred bytes per subscription, and about two
  hundred bytes per episode entry.


## The encrypted envelope

The store of `app.sync.encrypted` wraps each body in this EDN map:

```clojure
{:encrypted 1 :cipher "AES-256-GCM" :kdf "PBKDF2-HMAC-SHA256"
 :iterations 600000 :salt "…" :iv "…" :data "…"}
```

- The `:salt`, `:iv` and `:data` are base64.
- Each store instance seals with one random salt, and each document gets a
  new IV.
- The key is derived from the passphrase with PBKDF2 and HMAC-SHA256,
  using the iterations and the salt of the envelope.
- An envelope can ask for at most `max-iterations`, ten times the default.
  A body that asks for more, or whose salt, IV or data isn't base64, isn't
  an envelope, and is reported as a document that doesn't read.
- A body that isn't an envelope passes through unchanged.
- If the passphrase doesn't open a document, the call fails with the
  exception type `:dk.simongray.podcast.app.sync.encrypted/locked`.
- If the passphrase is a function, the store asks it again when a cached
  key doesn't open a document, and tries once more, so a corrected
  passphrase is used from the next document on.


## The stores of podcast-clj

| Store | Namespace | Platform | Version token |
|---|---|---|---|
| A map in an atom | `app.sync.store/memory` | both | a count of the writes |
| A folder | `app.sync.file` | JVM | the modification time, as exact as the file system keeps it, and the size |
| A WebDAV collection | `app.sync.webdav` | both | the ETag |
| Any store, encrypted | `app.sync.encrypted` | both | the version of the wrapped store |

On the JVM, `app.sync.webdav.server/handler` serves any store over WebDAV:

- A PROPFIND on the collection at depth 0 gives the collection alone, and
  at depth 1 it also lists the documents.
- If the store has no `:list`, a PROPFIND that lists the collection gets
  403. If it has no `:delete`, a DELETE gets 405, and OPTIONS doesn't offer
  DELETE. A 405 names the allowed methods in `Allow`.
- MKCOL gets 405, since the collection already exists.
- A client of such a server removes the same keys from its WebDAV store,
  e.g. `(dissoc (webdav/store url) :list)`, and the backend then uses one
  shared document.
- A PUT carries at most 32 MB, or the `:max-bytes` of the handler, and a
  larger body gets 413.
- A PUT with `If-Match: *` matches the document if it exists, and gets 412
  if it doesn't.
- A name with a slash or a control character, and the names `.` and `..`,
  get 400.

Give the credentials of a WebDAV store as `:credentials`. The store
doesn't send a user name or password from the URL, such as
`https://user:pass@host/`, and its name is the host alone. On the JVM, the
credentials only go to the origin of the collection, never to another host
after a redirect.


## The gpodder backend

The backend of `app.sync.gpodder` speaks the gpodder API on both
platforms, and takes these options:

| Option | Meaning | Default |
|---|---|---|
| `:credentials` | `{:username :password}`, or a function that returns them | none |
| `:api` | `:gpodder` for the paths of gpodder.net under `/api/2`, `:nextcloud` for those of the GPodder Sync app, or a map of the paths of another server. [Services and their defaults](services.md#the-gpodder-api) has the paths. | `:gpodder` |
| `:device` | The id of the device on the server | the device of the state |
| `:caption`, `:type` | What the server shows for the device. The type is desktop, laptop, mobile, server, tablet or other. | `"podcast-clj"`, `"other"` |
| `:identities` | A function of no arguments that returns the lookups that `identities` makes from the app's feeds | none |
| `:feed-guid`, `:feed-url`, `:episode-id`, `:episode-url` | Each of those lookups on its own | none |
| `:name` | The name of the backend | the domain of the server |
| `:page-size`, `:max-pages`, `:batch-size`, `:max-pending` | The limits of the server and of the cursor, see [Services and their defaults](services.md#the-gpodder-api) | 1000, 10, 100 and 500 |
| `:timeout-seconds` | How long each request can take | 30 |
| `:send` | A function that sends a request map, in place of `http/call!` | `http/call!` |

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

- `:sync/unknown-episodes` when such actions are waiting
- `:sync/future-actions` for the actions of a device that name a time more
  than a minute after the pull, which change nothing
- `:sync/malformed-actions` for actions without a feed, a media file or a
  readable time
- `:sync/unnamed-episodes` for episodes that a push couldn't name by a
  media URL
- `:sync/rejected-url` for a URL that the server refused

An entry from a server has `gpodder:` and the id of the server's device as
its `:device`, e.g. `gpodder:antennapod`. Some servers want the session of
a login and others Basic authentication on every request, so the backend
sends both.

On both platforms, `app.sync.gpodder.server/handler` serves the API as a
Ring handler under `/api/2`: the login, the devices, the subscription
changes and the episode actions. It takes the atom of the server's state,
`:authorized?`, a `:step` that syncs the state, and the same lookups as the
backend. The `:step` returns a state, not a promise, so in ClojureScript,
where `sync!` returns a promise, sync separately and merge the result into
the atom.


## Where the stores are, as of September 2026

The URL of a WebDAV collection and the folder of a sync client are facts
about other products that can change, so podcast-clj contains none of
them. Use `store/check!` to make sure that a URL or a path still works.

| WebDAV server | The URL of the collection | The credentials |
|---|---|---|
| Nextcloud | `https://HOST/remote.php/dav/files/USER/FOLDER/` | An app password, from Settings, Security, Devices & sessions |
| ownCloud | `https://HOST/remote.php/dav/files/USER/FOLDER/` | An app password, from Settings, Security |
| Seafile | `https://HOST/seafdav/LIBRARY/FOLDER/` | The WebDAV password, set in Settings |
| Synology DiskStation | `https://HOST:5006/SHARED-FOLDER/FOLDER/` | The password of the account. The WebDAV Server package must be on. |
| Fastmail | `https://myfiles.fastmail.com/FOLDER/` | An app password for Files (WebDAV). The user name is the email address. |
| Hetzner Storage Box | `https://BOX.your-storagebox.de/FOLDER/` | The password of the box. WebDAV must be on, in the Robot. |
| Box | `https://dav.box.com/dav/FOLDER/` | The password of the account, or an app password if two-step verification is on |
| The inspector | `http://localhost:8300/dav/` | None |

If a user name is an email address, percent-encode it in the path, e.g.
`simon%40example.org`.

| gpodder server | The root to give `gpodder/backend` | `:api` | Notes |
|---|---|---|---|
| gpodder.net | `https://gpodder.net` | `:gpodder` | Each device has its own subscriptions. To share them, link the devices on the site, or give the device id of the other app as `:device`. |
| Nextcloud with the GPodder Sync app | `https://HOST` | `:nextcloud` | An app password, as for WebDAV. The app only keeps play actions, so an episode marked as unplayed doesn't reach it. |
| oPodSync | The URL that it's installed at | `:gpodder` | |
| PinePods | `https://HOST` | `:gpodder` | |
| gpodder2go | `https://HOST` | `:gpodder` | It syncs the subscriptions, but not the episode actions. |
| The inspector | `http://localhost:8300` | `:gpodder` | Any user name and password |

| Sync client | macOS | Windows | Linux |
|---|---|---|---|
| iCloud Drive | `~/Library/Mobile Documents/com~apple~CloudDocs/` | `%USERPROFILE%\iCloudDrive\` | |
| Dropbox | `~/Dropbox/` | `%USERPROFILE%\Dropbox\` | `~/Dropbox/` |
| Google Drive | `~/Library/CloudStorage/GoogleDrive-EMAIL/My Drive/` | A drive letter, `G:\My Drive\` by default | |
| OneDrive | `~/Library/CloudStorage/OneDrive-Personal/` | `%USERPROFILE%\OneDrive\` | |
| Seafile | `~/Seafile/` | `%USERPROFILE%\Seafile\` | `~/Seafile/` |
| Nextcloud | `~/Nextcloud/` | `%USERPROFILE%\Nextcloud\` | `~/Nextcloud/` |
| Syncthing | The folder that you set up | | |

With "Optimize Mac Storage" on, iCloud Drive removes files that haven't
been used for a while and leaves a hidden `.name.icloud` placeholder. The
folder store ignores hidden files, so it can't read such a document until
iCloud Drive downloads it again.
