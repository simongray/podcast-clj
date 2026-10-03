The podcast server
==================

This server runs the parts of a podcast app that a browser can't: a proxy
for the requests of the page, and a folder of documents that the
listener's devices sync through. It also serves the files of your client,
so that the client and the server have the same origin, and you only
write the client.

The server is one of the listener's devices too. It syncs every minute and
keeps the listener's feeds current, and it serves the gpodder API over
them, so that AntennaPod and other apps of that API sync with the rest.

The server is for one listener, and it has no login. Reach it only through
a private network, e.g. your tailnet.


While you build a client
------------------------

Start the server from the root of the repository, with the folder that
your client's build writes to:

```bash
PODCAST_SERVER_CLIENT=../my-client/public clojure -M:server
```

Then open http://localhost:8400/. The server reads the files again for
each request, so a new build shows at the next reload. From the REPL, start
it with `(podcast-server.service/start! {:client-dir "../my-client/public"})`.

In the client, send the library's requests through the proxy, and sync
through the folder:

```clojure
(def lib
  (atom (library/make device-id {:config {:send (proxy/sender "/proxy")}})))

(def backends
  [(store/backend (webdav/store (str js/location.origin "/dav/")))])

(.then (library/sync! @lib backends) #(swap! lib library/merge %))
```

[How to build a podcast app](../doc/building-an-app.md#sync-the-listeners-of-a-website)
says what else a page needs, e.g. where to keep the library. The
client's pages can only run scripts from the server's own files, so an
inline script won't run.


On a home server with Tailscale
-------------------------------

On a machine where Tailscale runs, e.g. a Raspberry Pi with a 64-bit OS
and Docker:

1. Clone the repository, and write the name of the machine on the tailnet
   and the folder of your client's release build in `server/.env`:

   ```bash
   PODCAST_SERVER_NAMES=pi.example.ts.net
   PODCAST_CLIENT_DIR=/home/simon/podcast-client
   ```

2. In `server/`, build and start it with `docker compose up -d --build`.
   It listens on port 8400 of localhost only.

3. Let Tailscale serve it over HTTPS, on a port that no other service of
   yours uses, e.g. 8443:

   ```bash
   tailscale serve --bg --https=8443 http://127.0.0.1:8400
   ```

The client is then at https://pi.example.ts.net:8443/. This needs HTTPS
certificates switched on for the tailnet. To update the server, pull and
run the same `docker compose` command again.

The documents are kept in the Docker volume `data`. You don't need to back
them up: each device holds the whole profile and writes it again at its
next sync, while an old copy can bring back what the devices have since
forgotten.

The server keeps the feeds in memory, which takes about twice the size of
their XML. The heap is 512 MB in `compose.yaml`, so raise it if you follow
many large feeds.


AntennaPod and other gpodder apps
---------------------------------

Set up the app's sync with gpodder.net, but give the server's address in
place of gpodder.net, e.g. https://pi.example.ts.net:8443, and any user
name and password. The app then shares the subscriptions and the progress
of episodes with your other devices. The gpodder API carries nothing else,
e.g. no queue or stars.

The gpodder API names an episode by the URL of its media file, which the
server finds in the feeds that it fetched. So a play from another device
reaches the app once the server has fetched that episode's feed, and the
episodes of a private feed never do, since the server has no passwords.
When the server restarts, the app gets every play once more.


Settings
--------

Each setting is an environment variable.

| Variable | Default | Meaning |
|---|---|---|
| `PODCAST_SERVER_PORT` | 8400 | The port that the server listens on |
| `PODCAST_SERVER_HOST` | localhost, or 0.0.0.0 in the image | The address that it listens on |
| `PODCAST_SERVER_NAMES` | none | The names of the server besides localhost, separated by commas, e.g. its name on the tailnet |
| `PODCAST_SERVER_DATA` | `data`, or `/data` in the image | The folder of its data, with the documents in `sync/` |
| `PODCAST_SERVER_CLIENT` | none | The folder of the client's files |

Since the server checks no credentials, it refuses a request that names it
by any other name, as a page that points a domain of its own at the
server would. It also refuses whatever Tailscale Funnel brings from the
public internet.
