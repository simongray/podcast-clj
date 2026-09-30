# Sync stores and documents

This page gives the contracts and the formats of the sync between devices.
[How to build a podcast app](building-an-app.md) shows the steps of an app.
[Syncing](sync.md) explains the design.


## The store contract

A store is a map of functions over named documents. Each document has a
version token.

```clojure
{:fetch  (fn [name])            ; {:body "…" :version "…"}, or nil if there is no document
 :put    (fn [name body opts])  ; {:version "…"} when written, {:conflict? true} when refused
 :list   (fn [])                ; [{:name "…" :version "…"} …], optional
 :delete (fn [name])            ; optional
 :name   "Nextcloud"}           ; for the user interface and the report
```

- The function `put` refuses the write when `opts` has an `:if-version`
  that is not the current version of the document. An `:if-version` of
  nil means that the document must not exist yet.
- The function `put` checks the version and writes as one step. Of two
  puts with the same `:if-version`, one succeeds. The folder store does
  this within one process.
- A refused write is the value `{:conflict? true}`, not an exception.
- On the JVM, each function returns its value and throws an exception on
  failure. In ClojureScript, each function can return a value or a promise.
- The constructor of a store keeps the credentials in a closure. The map
  never contains credentials, and `:name` must not contain them.
- A store without `:list` cannot hold one document per device. It holds one
  shared document.

To test a store through this contract, `store/check!` writes a probe
document. It tests a store without `:list` for one shared document, which
needs a refused stale version. A store without `:delete` keeps the probe
document.

Each store namespace has two constructors: `store` makes the store over a
place, and `backend` makes the store and the backend in one call, with
the options of both.


## The backend contract

A backend is a map of two functions and a name, and `sync!` calls the
functions.

```clojure
{:pull (fn [state ctx at])  ; {:states [state …] :ctx ctx' :report […] :partial? false}
 :push (fn [state ctx at])  ; {:ctx ctx' :state state' :report […]}
 :name "gpodder.net"}
```

- The function `pull` returns the states to merge and the cursor for the
  next step. With `:partial? true`, the states are changes since a cursor,
  not whole states, and `sync!` does not acknowledge them.
- The function `push` writes the merged state and returns the cursor. The
  `:state` and the `:report` are optional. A push returns a `:state` when
  it learned more while it wrote.
- The `at` is the instant of the step. The functions do not read a clock.
- The backends of one `sync!` must have different names, because the
  cursors are kept by name. Two backends with the same name make `sync!`
  throw.

Any store is a backend through `store/backend`. A service with a model of
its own, such as gpodder, is a backend directly.


## The options of a backend

The options of `store/backend`, and of the `backend` function of each
store namespace:

| Option | Meaning | Default |
|---|---|---|
| `:strategy` | `:per-device` or `:shared` | `:per-device` if the store has `:list`, else `:shared` |
| `:direction` | `:both`, `:pull` (read only) or `:push` (write only) | `:both` |
| `:opml?` | Write `subscriptions.opml` next to the documents | `false` |
| `:app` | The app's name, written into each document | none |
| `:name` | The name of the backend | the name of the store |
| `:document` | The name of the shared document | `"podcast.edn"` |

With either strategy, a retired device writes nothing, and the report
contains `:sync/retired-device`.


## The per-device strategy

Each device writes only its own document, `<device-id>.edn`. One step of
`sync!` with this strategy does these actions:

1. List the store, and fetch each document whose version is not the version
   in the cursor. Only names that end in `.edn` are sync documents. A
   document without a version in the listing is always fetched. The
   fetches run at the same time.
2. Read each document. Report each document that does not read.
3. Merge the states into the local state.
4. If the merged state is not the state that this device wrote last, put the
   document of this device. No precondition is necessary, because no other
   device writes this document.
5. If `:opml?` is true and the subscription list changed, put
   `subscriptions.opml`. The list is what `player/to-opml` gives: the
   shows with their folders and tags, and the lists that the listener
   follows.
6. Delete the documents of retired devices.

If the version of the document of this device changed since the last step,
another installation uses the same device id. The report then contains
`:sync/shared-device`. If the document of this device is no longer in the
store, the next push writes it again.


## The shared strategy

All devices write one document, `podcast.edn` by default. One step of
`sync!` with this strategy does these actions:

1. Fetch the document. If its version is the version in the cursor, there is
   nothing new to merge.
2. Merge the document into the local state.
3. If the merged state is not the state that this device put last, put it,
   with `:if-version` set to the version in the cursor.
4. If the store refuses the put, fetch the document again, merge it, and put
   again. After three such retries, stop and report `:sync/conflict`.
5. Unless step 4 stopped, put `subscriptions.opml` if `:opml?` is true and
   the subscription list changed.

If the document is no longer in the store, the next push writes it again.
A document of a newer format stays as it is: this device cannot read all
of it, so a put would lose data.


## The result of `sync!`

```clojure
{:state  merged                ; the local state, with all pulled states merged in
 :ctx    {"Nextcloud" {…} …}   ; the cursors by backend name, for the next step
 :failed {"Dropbox" "…"}       ; the backends that failed, with the message
 :report [...]}                ; the diagnostics of the step
```

- The `:failed` is absent when no backend failed.
- If the pull of a backend fails, `sync!` does not push to that backend, and
  its cursor does not change. If a push fails, the backend keeps the cursor
  of its pull.
- Run one step at a time. A step that overlaps another step can write an
  older state after a newer one.
- The `:state` does not contain what the listener did during the step. Merge it
  into the current state with `player/merge`, and then compact the result
  with `player/compact`, because the merge brings back the tombstones that
  the step removed.
- On the JVM, `sync!` returns the map. In ClojureScript, it returns a
  promise of the map.
- The options of `sync!` are `:ctx`, the cursors of the last step, and
  `:compact`: `false` to keep all tombstones, or the options of
  `player/compact`.


## The sync document

A sync document is an EDN map:

```clojure
{:version    1                     ; the version of the format
 :device     "8f1c9e2a-…"          ; the id of the device that wrote the document
 :written-at #inst "2026-09-26T…"  ; the time of the write, by the clock of that device
 :app        "my-app"              ; the app that wrote it, optional
 :state      {…}}                  ; the whole player state of that device
```

- The player state is the map that the docstring of
  `dk.simongray.podcast.app.player` describes.
- The version changes only when the meaning of an existing key changes. New
  keys do not change the version.
- A reader leaves out a document with a newer version, and reports
  `:sync/newer-format`.
- A document takes a few hundred bytes for each subscription, and about two
  hundred bytes for each episode entry.


## The encrypted envelope

The store of `app.sync.encrypted` puts each body into this EDN map:

```clojure
{:encrypted 1 :cipher "AES-256-GCM" :kdf "PBKDF2-HMAC-SHA256"
 :iterations 600000 :salt "…" :iv "…" :data "…"}
```

- The `:salt`, the `:iv` and the `:data` are base64.
- Each store instance seals with one random salt. Each document gets a new
  IV.
- The key comes from the passphrase through PBKDF2 with HMAC-SHA256, with
  the iterations and the salt of the envelope.
- An envelope asks for at most `max-iterations`, ten times the default. A
  body that asks for more is not an envelope, and the reader reports it as
  a document that does not read. The same applies to a body whose salt, IV
  or data is not base64.
- A body that is not an envelope passes through unchanged.
- If the passphrase does not open a document, the call fails with the
  exception type `:dk.simongray.podcast.app.sync.encrypted/locked`.
- If the passphrase is a function, the store asks it again when a cached
  key does not open a document, and tries once more. A passphrase that the
  user corrected is then in use from the next document on.


## The stores of podcast-clj

| Store | Namespace | Platform | Version token |
|---|---|---|---|
| A map in an atom | `app.sync.store/memory` | both | a count of the writes |
| A folder | `app.sync.file` | JVM | the modification time, as exact as the file system keeps it, and the size |
| A WebDAV collection | `app.sync.webdav` | both | the ETag |
| Any store, encrypted | `app.sync.encrypted` | both | the version of the wrapped store |

On the JVM, `app.sync.webdav.server/handler` serves any store over WebDAV:

- A PROPFIND on the collection at depth 0 gives the collection alone. At
  depth 1, it also lists the documents.
- If the store has no `:list`, a PROPFIND that lists the collection gets
  403. If the store has no `:delete`, a DELETE gets 405, and OPTIONS does
  not name DELETE. A 405 names the methods of its target in Allow.
- MKCOL gets 405, because the collection is there.
- A client of such a server removes the same keys from its WebDAV store, for
  example `(dissoc (webdav/store url) :list)`. The backend then uses one
  shared document.
- A PUT carries at most 32 MB, or the `:max-bytes` of the handler. A larger
  body gets 413.
- `If-Match: *` matches the document when it exists, and gets 412 when it
  does not.
- A name with a slash or a control character, and the names `.` and `..`,
  get 400.

Give the credentials of a WebDAV store in `:credentials`. The store does not
send a user name or a password that is in the URL, such as
`https://user:pass@host/`. The name of the store is the host alone. On the
JVM, the store sends the credentials only to the origin of the collection.
A redirect to another host does not get them.


## The gpodder backend

The backend of `app.sync.gpodder` speaks the gpodder API, on both
platforms. It takes these options:

| Option | Meaning | Default |
|---|---|---|
| `:credentials` | `{:username :password}`, or a function that returns them | none |
| `:api` | `:gpodder` for the paths of gpodder.net under `/api/2`, `:nextcloud` for those of the GPodder Sync app, or a map of the paths of another server. [Services and their defaults](services.md#the-gpodder-api) gives the paths. | `:gpodder` |
| `:device` | The id of the device on the server | the device of the state |
| `:caption`, `:type` | What the server shows for the device. The type is desktop, laptop, mobile, server, tablet or other. | `"podcast-clj"`, `"other"` |
| `:identities` | A function of no arguments that gives the ways to name shows and episodes, as `identities` makes them over the app's feeds | none |
| `:feed-guid`, `:feed-url`, `:episode-id`, `:episode-url` | Each of those ways by itself | none |
| `:name` | The name of the backend | the domain of the server |
| `:page-size`, `:max-pages`, `:batch-size`, `:max-pending` | The limits of the server and of the cursor, see [Services and their defaults](services.md#the-gpodder-api) | 1000, 10, 100 and 500 |
| `:timeout-seconds` | The time that each request can take | 30 |
| `:send` | A function that sends a request map, in place of `http/call!` | `http/call!` |

What crosses, in each direction:

| podcast-clj | gpodder API |
|---|---|
| A subscription, and its removal | The feed URL, added or removed. A URL loses the user name and the password that it holds. |
| The position of an episode | `play`, with `started` and `position` at the position, and `total` at the duration, in whole seconds |
| A played episode | `play` to the end: `started`, `position` and `total` at the duration |
| An episode marked as not played | `new` |
| Nothing | `download` and `delete`, which a pull ignores |

The cursor keeps the time of each list of changes at the server, the device
that registered, the stamps of the entries at the last push, the URLs that
the server rewrote, and the actions that name a feed that the app does not
know yet, the last `:max-pending`. A pull reports `:sync/unknown-episodes` when such
actions wait. It reports `:sync/future-actions` for the actions of a device
that name a time more than a minute after the pull, which change nothing.
It reports `:sync/malformed-actions` for the actions that name no feed, no
media file or no time that reads. A push reports `:sync/unnamed-episodes`
for the episodes that it could not name by a media URL, and
`:sync/rejected-url` for a URL that the server refused.

An entry that comes from a server has `gpodder:` and the id of a device of
the server as its `:device`, such as `gpodder:antennapod`.

On both platforms, `app.sync.gpodder.server/handler` serves the API as a
Ring handler. It answers the login, the devices, the changes to the
subscriptions, and the episode actions, under `/api/2`. It takes the atom
of the server's state, `:authorized?`, a `:step` that syncs the state, and
the same ways to name shows and episodes as the backend. A `:step` gives a
state, and not a promise of one. In ClojureScript, `sync!` gives a promise,
so an app there syncs on its own, and merges the result into the atom.


## Where the stores are, as of September 2026

The URL of a WebDAV collection and the folder of a sync client are facts
about other products, and they can change. For this reason, podcast-clj
contains none of them. Use `store/check!` to make sure that a URL or a path
still works.

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

If a user name is an email address, percent-encode it in the path:
`simon%40example.org`.

| gpodder server | The root to give `gpodder/backend` | `:api` | Notes |
|---|---|---|---|
| gpodder.net | `https://gpodder.net` | `:gpodder` | Each device has its own subscriptions. Link the devices on the site to share them. |
| Nextcloud with the GPodder Sync app | `https://HOST` | `:nextcloud` | An app password, as for WebDAV. The app keeps play actions alone. |
| oPodSync | The URL that it is installed at | `:gpodder` | |
| PinePods | `https://HOST` | `:gpodder` | |
| gpodder2go | `https://HOST` | `:gpodder` | It syncs the subscriptions, and not the episode actions. |
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

With "Optimize Mac Storage" on, iCloud Drive removes files that were not
used for some time, and leaves a hidden `.name.icloud` placeholder. The
folder store ignores hidden files, so it cannot read such a document until
iCloud Drive downloads the file again.
