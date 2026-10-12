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

The server is for one listener. Without a password it has no login, so it
then starts only on localhost, or where you say that only a private
network reaches it, e.g. your tailnet.


While you build a client
------------------------

Start the server on Java 21 or later from the root of the repository, with
the folder that your client's build writes to:

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
  [(sync/backend (webdav/store (str js/location.origin "/dav/")))])

(.then (library/sync! @lib backends) #(swap! lib library/merge %))
```

Here `proxy` is `dk.simongray.wary-fetch.proxy` of
[wary-fetch](https://github.com/simongray/wary-fetch), and `webdav` is
`dk.simongray.like-minded.store.webdav` of
[like-minded](https://github.com/simongray/like-minded), two libraries that
come with podcast-clj.

[How to build a podcast app](../doc/building-an-app.md#sync-the-listeners-of-a-website)
says what else a page needs, e.g. where to keep the library. The
client's pages can only run scripts from the server's own files, so an
inline script won't run.


On a home server with Tailscale
-------------------------------

On a machine where Tailscale runs, e.g. a Raspberry Pi with a 64-bit OS
and Docker:

1. Clone the repository, and write the name of the machine on the tailnet
   and the folder of your client's release build in `server/.env`, with
   the setting that only the tailnet reaches the server, so that it needs
   no password:

   ```bash
   PODCAST_SERVER_NAMES=pi.example.ts.net
   PODCAST_SERVER_PRIVATE_NETWORK=true
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

Of each feed, the server keeps only what tells when to check it again and
the guids, dates and media of its episodes. It saves them in the volume,
in `library.edn` and a document per feed, so that after a restart it
only asks each feed whether it changed.


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


Without Tailscale
-----------------

Give the server a password of at least 16 characters in `server/.env`,
e.g. one that `openssl rand -base64 24` makes:

```bash
PODCAST_SERVER_PASSWORD=…
```

Every request must then give the password with HTTP Basic
authentication, with any user name. The browser asks for it when you
open the client, and a gpodder app takes it as the password of its
account. After a wrong password, the address that sent it waits a
second before the server answers it again, twice as long after each one
more, up to a minute. That slows an attacker down, but the password must
still be long. To change it, edit `server/.env` and run the `docker
compose` command again, which also ends the sessions of the gpodder apps.

The server speaks plain HTTP, so put a server for HTTPS in front of it,
e.g. Caddy with this `Caddyfile`, and give the domain in
`PODCAST_SERVER_NAMES`:

```
podcasts.example.org {
    reverse_proxy 127.0.0.1:8400
}
```

With Tailscale, `tailscale funnel` can do the same, since the server
answers Funnel when it has a password.

When a request came over HTTPS, as Caddy tells the server with
X-Forwarded-Proto, the answer tells the browser to come back over HTTPS
alone for a year (HSTS). That covers the server's own name only. To
cover the names below it too, set the header at the server in front.


Settings
--------

Each setting is an environment variable.

| Variable | Default | Meaning |
|---|---|---|
| `PODCAST_SERVER_PORT` | 8400 | The port that the server listens on |
| `PODCAST_SERVER_HOST` | localhost, or 0.0.0.0 in the image | The address that it listens on |
| `PODCAST_SERVER_NAMES` | none | The names of the server besides localhost, separated by commas, e.g. its name on the tailnet, with or without the port |
| `PODCAST_SERVER_PASSWORD` | none | The password that every request must give, of at least 16 characters |
| `PODCAST_SERVER_PRIVATE_NETWORK` | false | Whether only a private network reaches the server, e.g. through tailscale serve, so that it can start without a password on another address than localhost |
| `PODCAST_SERVER_DATA` | `data`, or `/data` in the image | The folder of its data, with the documents in `sync/` |
| `PODCAST_SERVER_CLIENT` | none | The folder of the client's files. A file or folder whose name starts with a dot isn't served. |
| `PODCAST_SERVER_MAX_SYNC_DOCUMENTS` | 1000 | The most documents that the sync folder holds |
| `PODCAST_SERVER_MAX_SYNC_BYTES` | 536870912 (512 MB) | The most bytes that the documents of the sync folder add up to |
| `PODCAST_SERVER_PODCASTINDEX_KEY` and `PODCAST_SERVER_PODCASTINDEX_SECRET` | none | The credentials of an account of the Podcast Index, with which the proxy signs the client's requests to it, so that the client gives `:index {}` |
| `PODCAST_SERVER_OP3_TOKEN` | none | The token of an API key of OP3, which the proxy adds to the client's requests to its API, so that the client calls with a nil token |

The server refuses a request that names it by any other name, as a page
that points a domain of its own at the server would. Any other client can
send the name it likes, so the check only keeps out web pages. It refuses
a write from a page of another site, e.g. a form that posts to the gpodder
API. Without a password, it also refuses whatever Tailscale Funnel brings
from the public internet.

Whoever has the password is the listener, so the server limits what a
leaked one can cost: the sync folder holds no more than its settings
allow, the proxy fetches four files at once and makes the others wait,
and a gpodder app adds at most 1000 subscriptions at a time, of feeds on
the public internet only.
