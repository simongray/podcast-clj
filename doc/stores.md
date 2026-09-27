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

- `put` refuses the write when `opts` has an `:if-version` that is not the
  current version of the document. `:if-version nil` means that the document
  must not exist yet.
- A refused write is the value `{:conflict? true}`, not an exception.
- On the JVM, each function returns its value and throws an exception on
  failure. In ClojureScript, each function can return a value or a promise.
- The constructor of a store keeps the credentials in a closure. The map
  never contains credentials, and `:name` must not contain them.
- A store without `:list` cannot hold one document per device. It holds one
  shared document.

`store/check!` tests a store through this contract, with a probe document.
It tests a store without `:list` for one shared document, which needs a
refused stale version. A store without `:delete` keeps the probe document.

Each store namespace has two constructors. `store` makes the store over a
place. `backend` makes the store and the backend in one call, with the
options of both.


## The backend contract

A backend is a map of two functions and a name. `sync!` calls the functions.

```clojure
{:pull (fn [state ctx at])  ; {:states [state …] :ctx ctx' :report […] :partial? false}
 :push (fn [state ctx at])  ; {:ctx ctx' :state state' :report […]}
 :name "gpodder.net"}
```

- `pull` returns the states to merge and the cursor for the next step.
  `:partial? true` means that the states are changes since a cursor, not
  whole states. `sync!` does not acknowledge partial states.
- `push` writes the merged state and returns the cursor. `:state` and
  `:report` are optional. A push returns a `:state` when it learned more
  while it wrote.
- `at` is the instant of the step. The functions do not read a clock.
- The backends of one `sync!` must have different names, because `sync!`
  keeps the cursors by name.

`store/backend` makes a backend from any store. A service with a model of
its own, such as gpodder, is a backend directly.


## The options of a backend

`store/backend`, and the `backend` function of each store namespace, take
these options:

| Option | Meaning | Default |
|---|---|---|
| `:strategy` | `:per-device` or `:shared` | `:per-device` if the store has `:list`, else `:shared` |
| `:direction` | `:both`, `:pull` (read only) or `:push` (write only) | `:both` |
| `:opml?` | Write `subscriptions.opml` next to the documents | `false` |
| `:app` | The name of the app, written into each document | none |
| `:name` | The name of the backend | the name of the store |
| `:document` | The name of the shared document | `"podcast.edn"` |

With either strategy, a retired device writes nothing, and the report
contains `:sync/retired-device`.


## The per-device strategy

Each device writes only its own document, `<device-id>.edn`. One step of
`sync!` with this strategy does these actions:

1. List the store, and fetch each document whose version is not the version
   in the cursor. Only names that end in `.edn` are sync documents.
2. Read each document. Report each document that does not read.
3. Merge the states into the local state.
4. If the merged state is not the state that this device wrote last, put the
   document of this device. No precondition is necessary, because no other
   device writes this document.
5. If `:opml?` is true and the subscriptions changed, put
   `subscriptions.opml`.
6. Delete the documents of retired devices.

If the version of the document of this device changed since the last step,
another installation uses the same device id. The report then contains
`:sync/shared-device`.


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
   the subscriptions changed.


## The result of `sync!`

```clojure
{:state  merged                ; the local state, with all pulled states merged in
 :ctx    {"Nextcloud" {…} …}   ; the cursors by backend name, for the next step
 :failed {"Dropbox" "…"}       ; the backends that failed, with the message
 :report [...]}                ; the diagnostics of the step
```

- `:failed` is absent when no backend failed.
- If the pull of a backend fails, `sync!` does not push to that backend, and
  its cursor does not change. If a push fails, the backend keeps the cursor
  of its pull.
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

`app.sync.encrypted` puts each body into this EDN map:

```clojure
{:encrypted 1 :cipher "AES-256-GCM" :kdf "PBKDF2-HMAC-SHA256"
 :iterations 600000 :salt "…" :iv "…" :data "…"}
```

- `:salt`, `:iv` and `:data` are base64.
- Each store instance seals with one random salt. Each document gets a new
  IV.
- The key comes from the passphrase through PBKDF2 with HMAC-SHA256, with
  the iterations and the salt of the envelope.
- A body that is not an envelope passes through unchanged.
- If the passphrase does not open a document, the call fails with the
  exception type `:dk.simongray.podcast.app.sync.encrypted/locked`.


## The stores of podcast-clj

| Store | Namespace | Platform | Version token |
|---|---|---|---|
| A map in an atom | `app.sync.store/memory` | both | a count of the writes |
| A folder | `app.sync.file` | JVM | the modification time and the size |
| A WebDAV collection | `app.sync.webdav` | both | the ETag |
| Any store, encrypted | `app.sync.encrypted` | both | the version of the wrapped store |

`app.sync.webdav.server/handler` serves any store over WebDAV, on the JVM.


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
