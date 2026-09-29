# Services and their defaults

podcast-clj knows some services by name: the podcast directories, the
Podcast Index, Podping, OP3 and the servers of the gpodder API. This page
lists what podcast-clj knows about each service, and the option that
replaces it. [Architecture](architecture.md#why-the-api-is-regular) gives
the reason. [How to build a podcast
app](building-an-app.md#use-another-service) shows the calls.

Each namespace below is relative to `dk.simongray.podcast`.


## The rule

- A fact that a specification defines is a constant in the code. Examples
  are the well-known paths of LNURL-pay, the methods of the Hive API, and
  the calls of the Mastodon API.
- A fact about one service is a default. A public var holds it as data, and
  an option of the same name replaces it for one call.
- Where an option takes the name of an entry of a table, it also takes an
  entry as data. The entry has the shape of the entries of the table.


## The defaults, as of September 2026

| What | Var | Option | Default |
|---|---|---|---|
| The directories | `directory/sources` | a source, as a name or as an entry | Apple Podcasts, fyyd, gpodder.net and the Podcast Index |
| The root of the Podcast Index API | `directory.podcastindex/base-url` | `:base-url` | `https://api.podcastindex.org/api/1.0/` |
| The Hive nodes that Podping reads | `podping/nodes` | `:nodes`, or one `:node` | four public nodes |
| The service that writes a podping for a host | `podping/gateway` | `:gateway` | `https://podping.cloud/` |
| The instance of OP3 | `analytics.op3/origin` | `:origin` | `https://op3.dev` |
| The URLs that OP3 takes behind its prefix | `analytics.op3/accepts?` | `:accepts?`, a predicate of a URL | the rules of op3.dev |
| The paths of the gpodder API | `app.sync.gpodder/apis` | `:api`, as a name or as a map of paths | `:gpodder` |
| The limits of a gpodder server | `app.sync.gpodder/default-limits` | `:page-size`, `:max-pages`, `:batch-size`, `:max-pending` | 1000, 10, 100 and 500 |
| The paths of statuses, for the Mastodon API | `comments.activitypub/status-paths` | `:status-paths` | the paths of four server programs |
| The requirements of a directory for a feed | `feed.validate/profiles` | `:profile`, as a name or as a map | no profile |
| The rules of a directory for artwork and media files | `check/profiles` | `:profile`, as a name or as a map | no profile |

podcast-clj has no default for these services, and the app gives them:

- the AppView that reads AT Protocol threads, as `:appview`
- the root of a gpodder server, and the URL of a WebDAV collection.
  [Sync stores and documents](stores.md) lists some of them.
- the list of user agents of the OPAWG, which `analytics/user-agents` reads
  from the files that the app keeps


## The directories

An entry of `directory/sources` tells the API that the directory speaks,
and the URLs of the service:

| Name | `:api` | URLs |
|---|---|---|
| `:apple` | `:apple` | `:search` and `:lookup`, the URLs of the two calls. `:trending`, the root of the charts. |
| `:fyyd` | `:fyyd` | `:root` |
| `:gpodder` | `:gpodder` | `:root` |
| `:podcastindex` | `:podcastindex` | `:root`, and `:pages`, the URL of the pages of the shows |

`request`, `response` and `call!` take a source as a name or as an entry.
The `:sources` of `search!` and `trending!` hold names and entries. The
`:id` of an entry names the source in `:directory/source`, in
`:directory/sources` and in `:failed`. An entry without an `:id` has the
name of its API.


## The gpodder API

`app.sync.gpodder/apis` gives the path of each call. In a path, `{user}` is
the user name, and `{device}` is the id of the device.

| Call | `:gpodder` | `:nextcloud` |
|---|---|---|
| `:login` | `/api/2/auth/{user}/login.json` | none |
| `:register` | `/api/2/devices/{user}/{device}.json` | none |
| `:subscriptions`, `:update-subscriptions` | `/api/2/subscriptions/{user}/{device}.json` | `/index.php/apps/gpoddersync/subscriptions`, `/index.php/apps/gpoddersync/subscription_change/create` |
| `:episodes`, `:update-episodes` | `/api/2/episodes/{user}.json` | `/index.php/apps/gpoddersync/episode_action`, `/index.php/apps/gpoddersync/episode_action/create` |

A backend logs in only when its API has a path for `:login`. It registers
its device only when the API has a path for `:register`.

The limits:

| Limit | Default | Meaning |
|---|---|---|
| `:page-size` | 1000 | A page of episode actions with this many actions is full. A pull then asks for one more page. gpodder.net gives 1000 actions at most. |
| `:max-pages` | 10 | The pages of episode actions that one pull reads |
| `:batch-size` | 100 | The episode actions in one request of a push. gpodder.net takes a longer list in the background, and reports no error of it. |
| `:max-pending` | 500 | The actions that the cursor keeps for feeds that the app does not know |


## The statuses of the Mastodon API

`comments/fetch!` reads a thread through the Mastodon API when the server
of the root post refuses the reader. The URL of the root post must tell
the id of the status. Each entry of `comments.activitypub/status-paths` is
a regular expression of the whole path of a URL. Its one group is the id.

| Path | Server program |
|---|---|
| `/@USER/ID` | Mastodon |
| `/@USER/statuses/ID` | GoToSocial |
| `/users/USER/statuses/ID` | The ActivityPub id of a status of Mastodon and of GoToSocial |
| `/notice/ID` | Pleroma and Akkoma |


## OP3

`analytics.op3/accepts?` tells whether op3.dev takes a URL behind its
prefix. OP3 answers a URL that it does not take with an error, so the
episode does not play. It takes an http or https URL with a path and
without a user name. It does not take these domains:

- an IPv6 address
- a name without a dot
- a name that RFC 2606 keeps for tests: `.test`, `.example`, `.invalid`
  and `.localhost`

Another instance of OP3 can take other URLs. Give its `:origin`, and its
rules as `:accepts?`.


## The profiles

A profile of `feed.validate/profiles` holds the elements that a directory
requires and recommends, for the channel and for each item. A profile of
`check/profiles` holds the rules for the artwork, and the levels of the
diagnostics that the directory changes:

| Key | Meaning |
|---|---|
| `:name` | The name of the directory or the standard |
| `:artwork` | `:sizes`, the least and the greatest width in pixels. `:types`, the media types. `:colors`, the color models. `:extension?`, `:alpha?` and `:last-modified?`. |
| `:levels` | The level of a diagnostic, by its id |

Both tables have the profiles `:psp-1` and `:apple`.
